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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地开局前按观战标记里的完整原 Add、固定端点与实例清退；每人最多 3 秒，取消正证明才按原串删槽。
 * 无证明、超时、未送达、原目录缺失不能冒充取消成功，标记保留供下次恢复；迟到取消不能摘新操作。
 * 新格式损坏/玩家身份错配保留并记 invalid_mark；无法通过当前 battle 账本的旧格式可按值清除。
 *
 * <p>本地兼容链仍为尽力清退，不用异常阻断既有 gather；日志和指标明确未确认，不能据此声称观战/参战强事务互斥。
 * SQL 全球分支维持已有许可事务。开局成功后的公开逻辑保持 attempt 守护，失败只影响随机/列表可见性。
 * 没有读回最新落点然后无原件 Remove 的分支，也没有“RPC 失败但标记照删”的分支。
 */
public final class SpectateGatherHooks implements GatherHooks {

    private static final Logger log = LoggerFactory.getLogger(SpectateGatherHooks.class);

    /** 每名有标记的成员的总上限：RemoveObserver + 删标记。就是 matched TTL 公式里的那一项，不能改大。 */
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
        if(store.sqlAuthority()) {
            // SQL许可在提交点已与票据互斥；强模式清退不能吞故障或凭空删guard。
            var marks=store.marksOf(members,Deadline.after(smallOpBudgetMs));
            for(var mark:marks.entrySet())store.release(mark.getKey(),mark.getValue(),Deadline.after(perMemberBudgetMs));
            return;
        }
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

    /** 清退一名成员只读原件，不依赖后来已过期、被改写或搬家的落点索引。 */
    private void evictOne(long playerId, String markValue) {
        Deadline deadline = Deadline.after(perMemberBudgetMs);
        Optional<LocalObserverOperation> decoded = LocalObserverOperation.decode(markValue);
        if (decoded.isEmpty()) {
            metrics.spectateEviction(EvictReason.ENTER_GATHER, EvictResult.INVALID_MARK);
            if (!LocalObserverOperation.isVersionedMark(markValue)) releaseMark(playerId, markValue, deadline);
            return;
        }
        LocalObserverOperation original = decoded.get();
        if (original.playerId() != playerId) {
            metrics.spectateEviction(EvictReason.ENTER_GATHER, EvictResult.INVALID_MARK);
            return;
        }
        Outcome outcome;
        try {
            outcome = dialer.remove(original, SpectateRules.REASON_ENTER_GATHER,
                    SpectateRules.hopTimeout(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS, deadline), deadline);
        } catch (RuntimeException failure) {
            outcome = new Outcome.Unknown("清退调用异常");
        }
        boolean cancelled = outcome instanceof Outcome.Replied replied && replied.tipId() == 0;
        metrics.spectateEviction(EvictReason.ENTER_GATHER, cancelled ? EvictResult.REMOVED : EvictResult.RPC_FAILED);
        if (cancelled) releaseMark(playerId, markValue, deadline);
        else log.warn("[spectate] 开局清退未获原操作取消证明，保留标记 player={} battle_id={} outcome={}",
                Long.toUnsignedString(playerId), Long.toUnsignedString(original.battleId()), outcome);
    }
    /**
     * 按读到的原值删标记。这名成员的截止还有富余就同步删（等到截止为止）；剩余不足 {@link #MIN_SYNC_RELEASE_MS}（RPC 把时间用完了：
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
