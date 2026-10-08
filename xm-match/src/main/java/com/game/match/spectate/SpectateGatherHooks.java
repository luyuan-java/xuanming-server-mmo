package com.game.match.spectate;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.match.gather.GatherHooks;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.EvictReason;
import com.game.match.metrics.MatchMetrics.EvictResult;
import com.game.match.metrics.MatchMetrics.WatchAnomaly;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.match.spectate.SpectateStore.Record;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 开局管线的两个观战接缝（spectate-spec §4.6；基线 {@code gather.go:228-234} → {@code spectate.go:282-308}，{@code spectate.go:163-191}）。
 *
 * <p><b>{@link #beforePrepare}——开局前清退</b>（第 2.5 步，五个入口都经过）：一名玩家同一时刻只保留一条 battle 直连，观战直连与随后的参战直连
 * 不能并存，所以进 gather 的成员若正在观战，先把他从那一场摘掉（battle 推 166 REMOVED 并关直连）。
 * <ol>
 *   <li>一次 {@link SpectateStore#marksOf} 读出全员的观战标记（至多等 {@value #SMALL_OP_BUDGET_MS} ms）。<b>读失败 → 只记日志、全部跳过、
 *       标记不删</b>，计 {@code anomalies{mark_read_failed}}（同基线「读标记出错 → 返回」）。</li>
 *   <li>有标记的成员按名单顺序<b>逐人串行</b>，每人一个 {@value MatchBudgets#REMOVE_OBSERVER_TIMEOUT_MS} ms 的截止，下面三步都算在里面：
 *       <ul>
 *         <li>标记值解析不了 → 不读落点、不发 RPC；</li>
 *         <li>读那一场的落点（{@link SpectateStore#read}）：读失败 / 记录损坏 → 不发 RPC（不拿读不出来的东西去找 battle）；记录不在 → 那一场已收尾；</li>
 *         <li>记录在 → 按<b>落点记录的地址</b>直拨 {@code removeObserver(reason = enter_gather)}（带硬截止的重载，超时 = min(3 s, 这名成员的剩余)）。
 *             结局只进日志与指标。</li>
 *       </ul>
 *       不论走到哪一步，最后都<b>按读到的原值</b>删标记（{@link SpectateStore#release}：只删这一个值，并发的 163 刚抢到的新标记删不掉）。
 *       截止已到或所剩无几（读落点或 RPC 把这名成员的时间用完了）就不再等 Redis，改发 {@link SpectateStore#releaseAsync}，接着处理下一人。</li>
 * </ol>
 * <b>时间</b>：matched 票据的 TTL 与下发给 scene 的备战期限里，每名成员给清退留的就是这 3 s（{@code MatchBudgets.matchedTicketTtlSeconds}），
 * 不能超——所以读落点、RPC、删标记共用一个截止，而不是各等各的（Redis 单条命令最坏约 4.2 s，各等各的会把一名成员拖到 7 s 以上）。
 * 开头那一次读标记算在公式给 Redis 小操作留的 10 s 余量里。整个钩子的上限 = 1 s + 有标记的人数 × 3 s。
 *
 * <p><b>{@link #onStarted}——公开</b>（第 5 步，全员票据已置 ready、落点已按最终 attempt 补写之后）：{@link SpectateStore#publish}
 * 把这一场登记进可观战索引（存储在脚本里核对落点的 attempt 仍是这一次的，保证「索引里出现的成员，其落点一定是最终那一次写的」）。
 * 没登记上（Redis 出错 / 超时，或落点不在、已被改写）只是这一场不进列表与随机选场，按战斗号指定观战与补签不受影响；
 * 计 {@code anomalies{publish_failed}}。失败路径管线不调它：没建成的场次永远不在索引里。
 *
 * <p><b>永不阻断开局</b>：两个方法都<b>不抛任何异常</b>（协作者违约抛出的、连同 {@code Error}，一律记日志后吞掉），也不重试。
 * 清退时一名成员身上的意外不连累后面的人：存储 / 直拨器违约抛出运行时异常，这名成员按「读失败」/「没调通」收场、标记照删，接着处理下一人。
 * gather 随后失败时，这里清退过的观众不恢复（battle 已推 166、客户端已收起观战界面，再挂回去只会留一个没有直连的观众）。
 * 客户端兜底：RemoveObserver 丢了或 166 晚到，客户端收到参战的 177 会把直连改连新的一局。
 *
 * <p>线程：两个方法都在 gather 的虚拟线程上被同步调用，阻塞等 Redis / battle（各自有界），不在 {@code synchronized} 块里阻塞。无状态，线程安全。
 */
public final class SpectateGatherHooks implements GatherHooks {

    private static final Logger log = LoggerFactory.getLogger(SpectateGatherHooks.class);

    /** 每名有标记的成员的总上限：读落点 + RemoveObserver + 删标记。就是 matched TTL 公式里的那一项，不能改大。 */
    static final long PER_MEMBER_BUDGET_MS = MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS;
    /** 钩子里单独的 Redis 小操作（开头读全员标记、开局后登记索引）的等待上限：同基线每条观战 Redis 命令的 1 s 截止，算在公式的 10 s 余量里。 */
    static final long SMALL_OP_BUDGET_MS = 1_000;
    /** 这名成员的截止只剩不到这么多毫秒时，删标记不再同步等 Redis，直接走异步。 */
    static final long MIN_SYNC_RELEASE_MS = 50;

    private final SpectateStore store;
    private final ObserverDialer dialer;
    private final MatchMetrics metrics;
    private final long perMemberBudgetMs;
    private final long smallOpBudgetMs;

    public SpectateGatherHooks(SpectateStore store, ObserverDialer dialer, MatchMetrics metrics) {
        this(store, dialer, metrics, PER_MEMBER_BUDGET_MS, SMALL_OP_BUDGET_MS);
    }

    /** 测试用：把两个时限收短（生产恒为 {@link #PER_MEMBER_BUDGET_MS} / {@link #SMALL_OP_BUDGET_MS}）。 */
    SpectateGatherHooks(SpectateStore store, ObserverDialer dialer, MatchMetrics metrics, long perMemberBudgetMs, long smallOpBudgetMs) {
        this.store = Objects.requireNonNull(store, "store");
        this.dialer = Objects.requireNonNull(dialer, "dialer");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (perMemberBudgetMs < 1 || smallOpBudgetMs < 1) {
            throw new IllegalArgumentException("时限必须为正: perMember=" + perMemberBudgetMs + " smallOp=" + smallOpBudgetMs);
        }
        this.perMemberBudgetMs = perMemberBudgetMs;
        this.smallOpBudgetMs = smallOpBudgetMs;
    }

    // ================================================================ 开局前清退

    @Override
    public void beforePrepare(List<Long> members) {
        try {
            evictSpectators(members);
        } catch (Throwable t) { // 清退永不阻断开局：任何意外都只记日志
            log.error("[spectate] 开局前清退出现意外异常（忽略，开局照常） members={}", describe(members), t);
        }
    }

    private void evictSpectators(List<Long> members) {
        if (members == null || members.isEmpty()) {
            return;
        }
        Map<Long, String> marks;
        try {
            marks = new HashMap<>(store.marksOf(members, Deadline.after(smallOpBudgetMs)));
        } catch (Deadline.DependencyException e) {
            log.error("[spectate] 开局前读观战标记失败：本次不清退、标记不动（开局照常） members={}: {}", describe(members), e.toString());
            metrics.watchableAnomaly(WatchAnomaly.MARK_READ_FAILED);
            return;
        }
        for (long playerId : members) {
            String markValue = marks.remove(playerId); // 名单顺序；同一人只处理一次
            if (markValue == null) {
                continue;
            }
            try {
                evictOne(playerId, markValue);
            } catch (RuntimeException e) { // 协作者违约抛出的：这名成员到此为止，不连累后面的人
                log.error("[spectate] 开局前清退这名成员时出现意外异常（忽略，继续处理后面的人） player={}", Long.toUnsignedString(playerId), e);
            }
        }
    }

    /** 清退一名正在观战的成员：整个过程不超过 {@link #perMemberBudgetMs}。 */
    private void evictOne(long playerId, String markValue) {
        Deadline deadline = Deadline.after(perMemberBudgetMs);
        Optional<SpectateRules.Mark> mark = SpectateRules.decodeMark(markValue);
        EvictResult result;
        if (mark.isEmpty()) {
            log.warn("[spectate] 观战标记值非法，直接清除 player={} value='{}'", Long.toUnsignedString(playerId), markValue);
            result = EvictResult.INVALID_MARK;
        } else {
            result = removeFromBattle(playerId, mark.get().battleId(), deadline);
        }
        releaseMark(playerId, markValue, deadline);
        metrics.spectateEviction(EvictReason.ENTER_GATHER, result);
        log.info("[spectate] 开局前清退观战 player={} battle_id={} result={}", Long.toUnsignedString(playerId),
                mark.map(m -> Long.toUnsignedString(m.battleId())).orElse("?"), result);
    }

    /** 读那一场的落点并发 RemoveObserver；返回这一次清退的结局（只用于指标与日志）。 */
    private EvictResult removeFromBattle(long playerId, long battleId, Deadline deadline) {
        Record record;
        try {
            record = store.read(battleId, deadline).record();
        } catch (Deadline.DependencyException e) {
            log.error("[spectate] 清退时读落点记录失败，不发 RemoveObserver player={} battle_id={}: {}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId), e.toString());
            return EvictResult.READ_FAILED;
        } catch (RuntimeException e) { // 存储的约定是只抛依赖异常；抛了别的同样按读失败收场（标记照删）
            log.error("[spectate] 清退时读落点记录抛了约定之外的异常，不发 RemoveObserver player={} battle_id={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId), e);
            return EvictResult.READ_FAILED;
        }
        return switch (record) {
            // 那一场已收尾（记录 TTL 已过或已被剔除）：标记是残留
            case Record.Absent absent -> EvictResult.NO_RECORD;
            case Record.Corrupt corrupt -> {
                log.error("[spectate] 清退时落点记录损坏，不发 RemoveObserver player={} battle_id={}: {}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(battleId), corrupt.why());
                yield EvictResult.READ_FAILED;
            }
            case Record.Found found -> remove(playerId, found.placement(), deadline);
        };
    }

    private EvictResult remove(long playerId, BattlePlacement placement, Deadline deadline) {
        Outcome outcome;
        try {
            outcome = dialer.remove(placement, playerId, SpectateRules.REASON_ENTER_GATHER,
                    SpectateRules.hopTimeout(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS, deadline), deadline);
        } catch (RuntimeException e) { // 直拨器的约定是永不抛；抛了按没调通收场（标记照删）
            log.error("[spectate] 开局前清退的 RemoveObserver 抛了约定之外的异常 player={} battle_id={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(placement.getBattleId()), e);
            return EvictResult.RPC_FAILED;
        }
        return switch (outcome) {
            case Outcome.Replied replied -> EvictResult.REMOVED;
            // 那一场所在的进程已被别的进程接手：房间不在了，等同记录已收尾
            case Outcome.Dead dead -> EvictResult.NO_RECORD;
            case Outcome.NotDelivered notDelivered -> {
                logRpcFailure(playerId, placement, "请求没有送达", notDelivered.detail());
                yield EvictResult.RPC_FAILED;
            }
            case Outcome.Unknown unknown -> {
                logRpcFailure(playerId, placement, "结局不明", unknown.detail());
                yield EvictResult.RPC_FAILED;
            }
            // 直拨器违约回了 null：按没调通处理
            case null -> {
                logRpcFailure(playerId, placement, "直拨器没有给出结局", "null");
                yield EvictResult.RPC_FAILED;
            }
        };
    }

    private static void logRpcFailure(long playerId, BattlePlacement placement, String what, String detail) {
        log.warn("[spectate] 开局前清退的 RemoveObserver 没调通（{}；名单里的残留随那一场结束清理，客户端收到参战的 177 会自行改连） player={} battle_id={} "
                        + "node={}({}:{}#{}): {}", what, Long.toUnsignedString(playerId), Long.toUnsignedString(placement.getBattleId()),
                Integer.toUnsignedString(placement.getBattleNodeId()), placement.getRpcHost(), Integer.toUnsignedString(placement.getRpcPort()),
                placement.getBattleInstanceId(), detail);
    }

    /**
     * 按读到的原值删标记。这名成员的截止还有富余就同步删（等到截止为止）；剩余不足 {@link #MIN_SYNC_RELEASE_MS}（读落点或 RPC 把时间用完了：
     * 再同步等一次 Redis 几乎必然等到超时）、或同步删失败 / 结局不明，就尽力异步发一次（按值删，重复无害），不再占用后面成员与备战的时间。
     */
    private void releaseMark(long playerId, String markValue, Deadline deadline) {
        if (deadline.remainingMillis() < MIN_SYNC_RELEASE_MS) {
            log.warn("[spectate] 清退用完了这名成员的时限，观战标记改为异步删除 player={}", Long.toUnsignedString(playerId));
            store.releaseAsync(playerId, markValue);
            return;
        }
        try {
            store.release(playerId, markValue, deadline);
        } catch (RuntimeException e) { // 依赖异常（失败 / 等到截止）；约定之外的异常同样处理
            log.warn("[spectate] 删观战标记失败或结局不明，再异步尽力删一次（删不掉就留到 TTL） player={}: {}", Long.toUnsignedString(playerId), e.toString());
            store.releaseAsync(playerId, markValue);
        }
    }

    // ================================================================ 开局后公开

    @Override
    public void onStarted(BattlePlacement placement) {
        try {
            publish(placement);
        } catch (Throwable t) { // 公开失败只是这一场不进列表：任何意外都只记日志与指标
            metrics.watchableAnomaly(WatchAnomaly.PUBLISH_FAILED);
            log.error("[spectate] 登记可观战索引出现意外异常（忽略，开局照常；这一场不进观战列表） battle_id={}",
                    placement == null ? "null" : Long.toUnsignedString(placement.getBattleId()), t);
        }
    }

    private void publish(BattlePlacement placement) {
        Objects.requireNonNull(placement, "placement");
        String battle = Long.toUnsignedString(placement.getBattleId());
        boolean published;
        try {
            published = store.publish(placement, Deadline.after(smallOpBudgetMs));
        } catch (Deadline.DependencyException e) {
            metrics.watchableAnomaly(WatchAnomaly.PUBLISH_FAILED);
            log.error("[spectate] 登记可观战索引失败（不影响开局与补签；这一场不进观战列表） battle_id={}: {}", battle, e.toString());
            return;
        }
        if (!published) {
            metrics.watchableAnomaly(WatchAnomaly.PUBLISH_FAILED);
            log.warn("[spectate] 没有登记进可观战索引：落点记录不在或已不是这一次的 attempt={}（这一场不进观战列表） battle_id={}",
                    Integer.toUnsignedString(placement.getAttempt()), battle);
            return;
        }
        log.info("[spectate] 战斗已登记可观战 battle_id={} node={} mode={} players={}", battle, Integer.toUnsignedString(placement.getBattleNodeId()),
                Integer.toUnsignedString(placement.getMode()), placement.getPlayerNamesCount());
    }

    /** 名单的日志写法（无符号十进制）。在 catch 里也会调：对空名单、空元素都不抛。 */
    private static List<String> describe(List<Long> members) {
        return members == null ? List.of() : members.stream().map(id -> id == null ? "null" : Long.toUnsignedString(id)).toList();
    }
}
