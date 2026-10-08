package com.game.match.spectate;

import com.game.api.match.MatchBudgets;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.battle.BattleRoutings;
import com.game.discovery.proto.PlayerPresence;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.EvictReason;
import com.game.match.metrics.MatchMetrics.EvictResult;
import com.game.match.metrics.MatchMetrics.IndexEviction;
import com.game.match.metrics.MatchMetrics.WatchAnomaly;
import com.game.match.metrics.MatchMetrics.WatchOutcome;
import com.game.match.placement.PlacementStore;
import com.game.match.port.PlayerStatusReader;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.match.spectate.SpectateStore.Eviction;
import com.game.match.support.MatchTip;
import com.game.match.support.MatchTips;
import com.game.match.ticket.TicketReader;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattleRouting;
import com.game.proto.match.WatchBattleResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 163 WatchBattle 的流程（spectate-spec §3.1 的判定表、§4.4 的七步；基线 {@code watchbattlelogic.go:52-267}）。<b>判定顺序本身就是客户端契约</b>，
 * 逐行照搬；每一行的码与 {@code parameters[0]} 在 {@link MatchTip} 的 {@code WATCH_*}（两条 16004 复用 {@code NO_IDENTITY} / {@code BUSY}）。
 *
 * <table>
 *   <caption>163 的判定顺序（# 是规格 §3.1 的行号）</caption>
 *   <tr><th>#</th><th>条件</th><th>应答</th><th>outcome</th></tr>
 *   <tr><td>1</td><td>会话没有绑定玩家（只认 {@code SessionContext.player_id}，请求体一律忽略，W12）</td><td>16004「缺少玩家身份」</td><td>internal</td></tr>
 *   <tr><td>2 / 6</td><td>读票据与观战标记出错（一段只读脚本，一起成败）</td><td>16004「服务器繁忙,请稍后再试」</td><td>internal</td></tr>
 *   <tr><td>3</td><td>持票，任意状态（含开局后 60 s 的 ready 残留，BW1）</td><td>16014「匹配中无法观战」</td><td>queued</td></tr>
 *   <tr><td>4</td><td>读战斗锁出错</td><td>16004「服务器繁忙,请稍后再试」</td><td>internal</td></tr>
 *   <tr><td>5</td><td>战斗锁存在</td><td>16015「战斗尚未结束,无法观战」</td><td>in_battle</td></tr>
 *   <tr><td>7</td><td>已有标记：<b>不拒绝</b>。值非法 → 按原串删；显式重看同一场 → 只删标记、<b>不发</b> RemoveObserver；
 *       其余（换场、随机）→ 读旧场落点，在则<b>同步</b> RemoveObserver({@code rewatch}) 之后才删标记、往下走</td><td>继续</td><td>—</td></tr>
 *   <tr><td>8</td><td>读在线目录出错</td><td>16004「服务器繁忙,请稍后再试」</td><td>internal</td></tr>
 *   <tr><td>9</td><td>在线目录没有条目</td><td>16019「会话不在线,无法观战」</td><td>offline</td></tr>
 *   <tr><td>10</td><td>在线目录条目缺 gate 实例（数据损坏）</td><td>16004「服务器繁忙,请稍后再试」</td><td>internal</td></tr>
 *   <tr><td>11</td><td>选场循环里：随机选场 / 读落点（含损坏）/ 抢标记出错</td><td>16004「服务器繁忙,请稍后再试」</td><td>internal</td></tr>
 *   <tr><td>12</td><td>指定场：落点不存在（已公开时只摘索引成员，<b>永不删落点</b>）</td><td>16018「该战斗不存在或已结束」</td><td>not_found</td></tr>
 *   <tr><td>13</td><td>抢标记：被并发的另一条 163 占着 → 16016；发现有票（入口检查之后才建出来的，W2）→ 16014、不调 AddObserver
 *       （第 7 行刚按「重看同一场」删过旧标记时，另发一条异步的自我清退，见下面的纪律）</td>
 *       <td>16016「已在观战另一场战斗」/ 16014</td><td>already_watching / queued</td></tr>
 *   <tr><td>14</td><td>登记成功后复查命中票据或战斗锁（读票失败按无票；读锁失败或等超时按<b>有锁</b>，BW2）→ 异步自我清退</td>
 *       <td>16014「匹配中无法观战」</td><td>queued</td></tr>
 *   <tr><td>15</td><td>成功</td><td>{@code {battle_id}}，不带 {@code error_message}</td><td>ok</td></tr>
 *   <tr><td>16</td><td>battle 回「房间不存在」（1004）或直拨判死：在建房窗口内 → 不剔除；窗口外 → 按 attempt 守护剔除，随机 → 下一轮</td>
 *       <td>16018「该战斗不存在或已结束」</td><td>not_found</td></tr>
 *   <tr><td>17</td><td>battle 的其它拒绝、没送达、结局不明（随机也不换场，BW6）</td><td>16018「该战斗当前无法观战」</td><td>rejected</td></tr>
 *   <tr><td>18</td><td>随机两轮都没成</td><td>16017「当前没有可观战的战斗」</td><td>no_battle</td></tr>
 *   <tr><td>J</td><td>剩余预算不够发下一跳（换场前不足 2.2 s：旧标记原样保留；登记前不足 1 s：回滚刚抢到的标记，重看同一场时保留它）</td>
 *       <td>16004「服务器繁忙,请稍后再试」</td><td>internal</td></tr>
 *   <tr><td>J2</td><td>未预期异常（bug）：原样抛给派发器（信封 1003）；已抢到、还没交给 battle 的标记按值尽力释放</td><td>—</td><td>internal</td></tr>
 * </table>
 * 「在途已满」（J 行的另一半，outcome {@code overloaded}）不进这里，在 {@link WatchBattleHandler#onOverload()}。
 *
 * <p><b>观战标记的纪律</b>（规格 §7.1 第 4–7 条；每一条都有只靠它挡住的用例）：
 * <ul>
 *   <li><b>先标记、后登记</b>：标记先于 AddObserver 写入，之后才开始的 gather 一定能读到并清退。</li>
 *   <li><b>一律按值删</b>（W3）：标记的值带本次请求的 nonce，两条并发 163 不会删掉对方刚抢到的标记；入口删旧标记按读到的原串删。</li>
 *   <li><b>结局不明保留</b>（W4）：AddObserver 超时 / 连上后断开时 battle 可能已登记这名观众——标记留着，下一次开局清退才摘得到他；
 *       明确拒绝与「确定没送达」才回滚。不补发 RemoveObserver（它可能先于在途的 Add 到达）。</li>
 *   <li><b>换场的 Remove 先于 Add</b>：同步等旧场的 RemoveObserver 返回（或到它的硬截止）才往下走——随机模式可能重挑同一场，
 *       迟到的 Remove 会把刚登记的观众摘掉。只有复查命中后的自我清退是异步的（那时应答已定，剩余预算可能只有约 0.2 s）。</li>
 *   <li><b>重看同一场不发 Remove</b>：否则给仍活着的旧会话推一条假的 166；重推 177 / 首帧由 battle 的幂等分支负责。
 *       代价是从删掉旧标记到 AddObserver 之间，玩家仍登记在这一场、却没有标记指着它——<b>不调 AddObserver 就回包</b>的两个出口不能就这样走掉
 *       （开局清退只认标记）：抢标记回有票（16014）→ 补一条异步的自我清退（{@code concurrent_queue}；基线在同一交错下靠复查摘掉他）；
 *       登记前预算不足（16004）→ 保留刚抢到的标记。其余出口与基线相同（不在线、读落点失败等同样留下「在名单、无标记」，随那一场结束清理）；
 *       入口删旧标记<b>失败</b>（16004）→ 不补发那次异步删除，旧标记原样留着（基线在同样的故障下也是标记还在）；残余只有「等到截止、
 *       而那条删除其实已在路上并随后成功」这一种（Redis 卡顿时可见，随那一场结束清理）。</li>
 *   <li><b>复查的两个方向不能写反</b>：读票失败 → 按无票（尽力收窄，不引入新的失败面）；读锁失败 → 按有锁（宁可多清退一个观众，
 *       也不放进「观战 + 参战」）。只查一次，不做二次复查（切磋的备战晚于复查时两样都读不到，照常成功——规格 §7.1 第 16 条的既定结局）。</li>
 * </ul>
 *
 * <p><b>预算</b>（W10；lead 裁决 3）：整个请求一个截止（受理时刻 + 4500 ms，由派发器给）。每一次 Redis 读写都只等到它；两跳 battle RPC
 * 各有一个更早的<b>硬截止</b>交给 {@link ObserverDialer}——换场的 Remove 是「请求截止 − {@link MatchBudgets#WATCH_REWATCH_RESERVE_MS}」，
 * 登记的 Add 是「请求截止 − {@link MatchBudgets#WATCH_ADD_RESERVE_MS}」——直拨的本地等待、目录读与探测都夹在硬截止之内，
 * 所以 163 一定先于 gate 的 5 s 给出 in-band 应答。预算不够发下一跳时不去发一个注定超时的调用，直接回 16004。
 *
 * <p><b>与基线只在 Redis 故障时可见的出入</b>：删旧标记（入口）或随机换场前回滚本轮标记<b>失败</b>时回 16004——基线忽略删除失败继续往下走，
 * 随后的抢占会被自己没删掉的标记挡成 16016「已在观战另一场战斗」，那是一条误导的应答。
 *
 * <p><b>指标</b>：每次请求恰好记一个 {@code xm_match_watch_battle_total{outcome}}；对旧标记的每一次处置另记
 * {@code xm_match_spectate_evictions_total}（{@code already_watching} 只计 16016，W14）；摘索引成员记
 * {@code xm_match_watchable_index_evictions_total}（只在存储回报「真的摘了」时）；损坏的落点记 {@code xm_match_watchable_anomalies_total}。
 * 观众 RPC 自己的结局由 {@link ObserverDialer} 的实现记。
 *
 * <p><b>线程</b>：{@link #watch} 阻塞，跑在 163 自己的虚拟线程上（{@link SpectateExecutor}）；只在 future 上等，不持锁、不用 {@code synchronized}。
 * 复查的两次读各在一条虚拟线程上并行做（名字 {@code match-spectate-recheck-<序号>}，不占 163 的在途许可：每个请求至多两条，都只活到请求截止）。
 * 无状态、线程安全。
 */
public final class WatchBattleService {

    private static final Logger log = LoggerFactory.getLogger(WatchBattleService.class);

    /** 复查子任务的虚拟线程名前缀。 */
    static final String RECHECK_THREAD_PREFIX = "match-spectate-recheck-";

    private final SpectateStore store;
    private final PlacementStore placements;
    private final PlayerStatusReader players;
    private final TicketReader tickets;
    private final ObserverDialer observers;
    private final MatchMetrics metrics;
    private final DoubleSupplier random;
    private final Supplier<String> nonces;
    private final Executor recheckThreads;

    /** 一次判定的结论：出口计数与应答。 */
    private record Verdict(WatchOutcome outcome, WatchBattleResponse response) {
    }

    /** 一次请求自己的小状态。只在这一次请求的线程上读写。 */
    private static final class Pending {
        /** 本次请求写下、还没有交代清楚的标记（出未预期异常时按值尽力释放，J2）。 */
        String mark;
        /**
         * 第 7 行「显式重看同一场」删掉了旧标记、<b>没有发</b> RemoveObserver：玩家多半仍登记在这一场的观众名单里，而此刻没有任何标记指着它。
         * 走到 AddObserver 的出口各有交代（成功 → 新标记；结局不明 → 保留新标记；其余与基线相同）；<b>不调 AddObserver 就回包</b>的两个
         * Java 独有出口（抢标记回有票、登记前预算不足）要自己补上，见 {@code decide} 里的两处。
         */
        boolean sameBattleRewatch;
    }

    /** 生产装配：随机数取 {@link ThreadLocalRandom}，nonce 取 {@link SpectateRules#newNonce}，复查的两次读各起一条虚拟线程。 */
    public WatchBattleService(SpectateStore store, PlacementStore placements, PlayerStatusReader players, TicketReader tickets,
                              ObserverDialer observers, MatchMetrics metrics) {
        this(store, placements, players, tickets, observers, metrics, () -> ThreadLocalRandom.current().nextDouble(), SpectateRules::newNonce,
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(RECHECK_THREAD_PREFIX, 0).factory()));
    }

    /**
     * @param store          观战标记、可观战索引、落点的原子读
     * @param placements     落点记录的普通读（只用来找旧场的地址，换场时清退）
     * @param players        战斗锁与在线目录（别的进程拥有）
     * @param tickets        票据的只读口（只用于复查；入口与抢占的「有没有票」在 {@code store} 的脚本里判）
     * @param observers      按落点直拨 battle 的观众 RPC
     * @param random         随机选场用的 [0, 1) 随机数（测试给定值）
     * @param nonces         每次抢标记用的 nonce（{@link SpectateRules#newNonce} 的形状）
     * @param recheckThreads 复查两次读的执行器：任务会阻塞到读完或请求截止；测试可传 {@code Runnable::run}
     */
    WatchBattleService(SpectateStore store, PlacementStore placements, PlayerStatusReader players, TicketReader tickets, ObserverDialer observers,
                       MatchMetrics metrics, DoubleSupplier random, Supplier<String> nonces, Executor recheckThreads) {
        this.store = Objects.requireNonNull(store, "store");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.players = Objects.requireNonNull(players, "players");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.observers = Objects.requireNonNull(observers, "observers");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.random = Objects.requireNonNull(random, "random");
        this.nonces = Objects.requireNonNull(nonces, "nonces");
        this.recheckThreads = Objects.requireNonNull(recheckThreads, "recheckThreads");
    }

    /**
     * 处理一次 163。
     *
     * @param session            gate 填的会话上下文：身份只取 {@code player_id}（0 = 没进游戏）；{@code account} 作 {@code observer_name}（只进 battle 的日志）
     * @param requestedBattleId  请求里的 {@code battle_id}（0 = 随机观战）
     * @param deadline           本次请求的截止
     * @return 应答（业务拒绝、依赖故障、预算不足都在它的 {@code error_message} 里；成功只带 {@code battle_id}）
     * @throws RuntimeException 只在未预期的异常（bug）时：已抢到、还没交给 battle 的标记已按值尽力释放，由派发器回信封 1003
     */
    public WatchBattleResponse watch(SessionContext session, long requestedBattleId, Deadline deadline) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(deadline, "deadline");
        long playerId = session.getPlayerId();
        Pending pending = new Pending();
        Verdict verdict;
        try {
            verdict = decide(session, playerId, requestedBattleId, deadline, pending);
        } catch (RuntimeException | Error e) {
            String mark = pending.mark;
            if (mark != null) {
                log.error("[spectate] WatchBattle 出了未预期的异常，按值尽力释放本次抢到的标记 player={} mark={}", id(playerId), mark);
                releaseAsyncQuietly(playerId, mark);
            }
            count(WatchOutcome.INTERNAL);
            throw e;
        }
        count(verdict.outcome());
        return verdict.response();
    }

    // ================================================================ 判定主干

    private Verdict decide(SessionContext session, long playerId, long requestedBattleId, Deadline d, Pending pending) {
        // 第 1 行：身份只认会话
        if (playerId == 0) {
            log.warn("[spectate] WatchBattle 缺少会话身份 gate={} session={}", Integer.toUnsignedString(session.getGateNodeId()),
                    Integer.toUnsignedString(session.getSessionId()));
            return rejected(WatchOutcome.INTERNAL, MatchTip.NO_IDENTITY);
        }
        String player = id(playerId);

        // 第 2、3、6 行：票据与标记一次原子读
        SpectateStore.Entry entry;
        try {
            entry = store.entry(playerId, d);
        } catch (Deadline.DependencyException e) {
            return busy("读票据与观战标记", player, e);
        }
        if (entry.hasTicket()) {
            return rejected(WatchOutcome.QUEUED, MatchTip.WATCH_QUEUED);
        }

        // 第 4、5 行：战斗锁（不在 {match} 槽，进不了上面那段脚本；并发的开局由登记之后的复查兜底）
        boolean locked;
        try {
            locked = players.inBattle(playerId, d);
        } catch (Deadline.DependencyException e) {
            return busy("读战斗锁", player, e);
        }
        if (locked) {
            return rejected(WatchOutcome.IN_BATTLE, MatchTip.WATCH_IN_BATTLE);
        }

        // 第 7 行：已有标记不拒绝，先收拾旧场
        if (entry.mark().isPresent()) {
            Verdict stop = clearPreviousMark(playerId, player, entry.mark().get(), requestedBattleId, d, pending);
            if (stop != null) {
                return stop;
            }
        }

        // 第 8、9、10 行：观众必须在线——路由四个字段都取在线目录（W11），177 也按在线目录推
        Optional<PlayerPresence> presence;
        try {
            presence = players.presence(playerId, d);
        } catch (Deadline.DependencyException e) {
            return busy("读在线目录", player, e);
        }
        if (presence.isEmpty()) {
            return rejected(WatchOutcome.OFFLINE, MatchTip.WATCH_OFFLINE);
        }
        if (presence.get().getGateInstanceId().isEmpty()) {
            log.error("[spectate] WatchBattle 在线目录条目缺 gate 实例（数据损坏） player={} gate={} zone={}", player,
                    Integer.toUnsignedString(presence.get().getGateNodeId()), Integer.toUnsignedString(presence.get().getZoneId()));
            return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
        }
        BattleRouting routing = BattleRoutings.gatePart(presence.get());

        // 选场 + 登记：随机两轮、指定一轮
        boolean randomMode = requestedBattleId == 0;
        int rounds = randomMode ? MatchBudgets.RANDOM_WATCH_ROUNDS : 1;
        for (int round = 0; round < rounds; round++) {
            long battleId = requestedBattleId;
            if (randomMode) {
                try {
                    battleId = pickRandom(d);
                } catch (Deadline.DependencyException e) {
                    return busy("随机选场", player, e);
                }
                if (battleId == 0) {
                    break; // 没有可看的场
                }
            }
            String battle = id(battleId);

            // 「是否已公开 + 落点 + 时刻」一次原子读：建房窗口判定的三个输入不得晚于读落点（W6）
            SpectateStore.Snapshot snapshot;
            try {
                snapshot = store.read(battleId, d);
            } catch (Deadline.DependencyException e) {
                return busy("读落点记录", player, e);
            }
            // 随机挑出来的就是索引成员，按已公开处理（同基线）
            boolean published = randomMode || snapshot.published();
            BattlePlacement placement;
            switch (snapshot.record()) {
                case SpectateStore.Record.Corrupt corrupt -> {
                    // BW9：损坏的记录回 16004、不剔除（删掉它会毁掉补签的定位）；计数告警
                    metrics.watchableAnomaly(WatchAnomaly.CORRUPT_RECORD);
                    log.error("[spectate] WatchBattle 落点记录损坏（不剔除） player={} battle_id={}: {}", player, battle, corrupt.why());
                    return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
                }
                case SpectateStore.Record.Absent absent -> {
                    // 只摘索引里的残留成员，永不删落点：读到「没有」之后才预写的记录（一场正在建房的战斗）不能动
                    if (published) {
                        evict(new Eviction.Missing(battleId), IndexEviction.MISSING_RECORD, d);
                    }
                    if (randomMode) {
                        continue;
                    }
                    log.info("[spectate] 指定观战的战斗没有落点记录 player={} battle_id={} published={}", player, battle, published);
                    return rejected(WatchOutcome.NOT_FOUND, MatchTip.WATCH_NOT_FOUND);
                }
                case SpectateStore.Record.Found found -> placement = found.placement();
            }

            // 第 13 行：原子完成「没有票据 ∧ 没有标记 → 写标记」。标记必须先于观众登记生效
            String mark = SpectateRules.encodeMark(battleId, nonces.get());
            pending.mark = mark;
            SpectateStore.Acquire acquired;
            try {
                acquired = store.acquire(playerId, mark, d);
            } catch (Deadline.DependencyException e) {
                // 结局不明：标记可能已经写下，按本次的值尽力删
                releaseAsyncQuietly(playerId, mark);
                pending.mark = null;
                return busy("写观战标记", player, e);
            }
            switch (acquired) {
                case QUEUED -> {
                    // 入口检查之后才建出的票据（W2）
                    if (pending.sameBattleRewatch) {
                        // 重看同一场：旧标记已在入口删掉、没发 Remove，玩家仍登记在这一场——不调 AddObserver 就回包的话，他带着观众登记去开局，
                        // 而开局清退只认标记、摘不到他。自我清退，结局同基线（那边 SETNX 成功 → 幂等的 AddObserver → 复查命中 → RemoveObserver）
                        selfEvict(playerId, player, battleId, placement);
                    }
                    // 命令被重发时首轮可能已写入标记：按本次的值释放
                    releaseMark(playerId, player, mark, d, pending);
                    log.info("[spectate] 抢观战标记时发现已有票据，不登记观众 player={} battle_id={} same_battle_rewatch={}", player, battle,
                            pending.sameBattleRewatch);
                    return rejected(WatchOutcome.QUEUED, MatchTip.WATCH_QUEUED);
                }
                case BUSY -> {
                    pending.mark = null; // 没有写任何东西；占着标记的是另一条并发 163，它的值不归本次请求删
                    log.info("[spectate] 观战标记被并发的另一条 WatchBattle 占着 player={} battle_id={}", player, battle);
                    return rejected(WatchOutcome.ALREADY_WATCHING, MatchTip.WATCH_ALREADY);
                }
                case OK -> {
                    // 往下登记观众
                }
            }

            // J 行：剩余预算不够发 AddObserver → 回滚标记，不去发一个注定超时的调用
            if (d.remainingMillis() < MatchBudgets.WATCH_ADD_MIN_BUDGET_MS) {
                if (pending.sameBattleRewatch) {
                    // 重看同一场：玩家多半仍登记在这一场（旧标记已删、没发 Remove），刚抢到的标记指的正是它——留着（W4 的口径：
                    // 可能仍登记着就不删），下一次 163 或开局清退才摘得到他
                    pending.mark = null;
                    log.warn("[spectate] WatchBattle 剩余预算不足 {} ms，不发 AddObserver；重看同一场，保留观战标记 player={} battle_id={}",
                            MatchBudgets.WATCH_ADD_MIN_BUDGET_MS, player, battle);
                    return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
                }
                releaseMark(playerId, player, mark, d, pending);
                log.warn("[spectate] WatchBattle 剩余预算不足 {} ms，不发 AddObserver player={} battle_id={}", MatchBudgets.WATCH_ADD_MIN_BUDGET_MS,
                        player, battle);
                return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
            }
            Deadline hardStop = SpectateRules.reserveBefore(d, MatchBudgets.WATCH_ADD_RESERVE_MS);
            Duration timeout = SpectateRules.hopTimeout(MatchBudgets.ADD_OBSERVER_TIMEOUT_MS, hardStop);
            AddObserverRequest request = AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(playerId).setRouting(routing)
                    .setObserverName(session.getAccount()).build();
            Outcome outcome = observers.add(placement, request, timeout, hardStop);

            boolean deadNode;
            switch (outcome) {
                case Outcome.Replied replied when replied.tipId() == 0 -> {
                    // 观众已登记：从这里起标记要留着（复查没命中就是成功；命中由自我清退按值删）
                    pending.mark = null;
                    return recheck(playerId, player, battleId, placement, mark, randomMode, d);
                }
                case Outcome.Replied replied when replied.tipId() == MatchTips.BATTLE_ROOM_NOT_FOUND -> deadNode = false;
                case Outcome.Dead dead -> deadNode = true;
                case Outcome.Replied replied -> {
                    // 观众已满 / 是参战者 / 签不出票：明确拒绝，回滚标记；随机模式也不换场（BW6）
                    releaseMark(playerId, player, mark, d, pending);
                    log.info("[spectate] battle 拒绝观战 player={} battle_id={} node={} tip_id={}", player, battle, node(placement),
                            Integer.toUnsignedString(replied.tipId()));
                    return rejected(WatchOutcome.REJECTED, MatchTip.WATCH_NOT_WATCHABLE);
                }
                case Outcome.NotDelivered notDelivered -> {
                    // 请求确定没送达：battle 没有登记，回滚标记
                    releaseMark(playerId, player, mark, d, pending);
                    log.error("[spectate] AddObserver 没有送达 player={} battle_id={} node={}: {}", player, battle, node(placement),
                            notDelivered.detail());
                    return rejected(WatchOutcome.REJECTED, MatchTip.WATCH_NOT_WATCHABLE);
                }
                case Outcome.Unknown unknown -> {
                    // 结局不明（W4）：battle 可能已登记这名观众——标记保留，下一次开局清退才摘得到他；不补发 RemoveObserver
                    pending.mark = null;
                    log.error("[spectate] AddObserver 结局不明，保留观战标记 player={} battle_id={} node={}: {}", player, battle, node(placement),
                            unknown.detail());
                    return rejected(WatchOutcome.REJECTED, MatchTip.WATCH_NOT_WATCHABLE);
                }
            }

            // 第 16 行：房间不存在（battle 回 1004，或所在进程已判死）
            boolean released = releaseMark(playerId, player, mark, d, pending);
            if (SpectateRules.roomMayBeCreating(published, placement.getCreatedAtMs(), snapshot.redisNowMs())) {
                // 房间可能只是还没建好（落点先于建房写入、换节点时还会改写）：只回「不存在」，落点与索引都不动
                log.info("[spectate] 房间不存在但落点仍在建房窗口内，不剔除 player={} battle_id={} created_at_ms={} checked_at_ms={} dead_node={}",
                        player, battle, Long.toUnsignedString(placement.getCreatedAtMs()), snapshot.redisNowMs(), deadNode);
                return rejected(WatchOutcome.NOT_FOUND, MatchTip.WATCH_NOT_FOUND);
            }
            log.info("[spectate] 观战目标已收尾，懒剔除 player={} battle_id={} attempt={} dead_node={}", player, battle,
                    Integer.toUnsignedString(placement.getAttempt()), deadNode);
            if (placement.getAttempt() != 0) {
                // 按 attempt 守护（W7）：落点在这期间被改写到重试节点的话，什么都不动
                evict(new Eviction.Dead(battleId, placement.getAttempt()), deadNode ? IndexEviction.DEAD_NODE : IndexEviction.ROOM_MISSING, d);
            }
            if (randomMode) {
                if (!released) {
                    // 本轮的标记没能确认删掉：下一轮的抢占会被它挡成 16016，那是一条误导的应答——按依赖故障收场
                    log.error("[spectate] 随机观战换场前回滚标记失败 player={} battle_id={}", player, battle);
                    return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
                }
                continue;
            }
            return rejected(WatchOutcome.NOT_FOUND, MatchTip.WATCH_NOT_FOUND);
        }

        // 第 18 行
        return rejected(WatchOutcome.NO_BATTLE, MatchTip.WATCH_NO_BATTLE);
    }

    // ================================================================ 第 7 行：旧标记

    /**
     * 入口处收拾旧标记（基线 {@code watchbattlelogic.go:94-113}、{@code spectate.go:282-308}）。
     *
     * @return null = 旧标记已删，继续往下判；非 null = 就此回包（预算不够换场、或删旧标记失败）
     */
    private Verdict clearPreviousMark(long playerId, String player, String oldValue, long requestedBattleId, Deadline d, Pending pending) {
        Optional<SpectateRules.Mark> decoded = SpectateRules.decodeMark(oldValue);
        if (decoded.isEmpty()) {
            log.warn("[spectate] 观战标记值非法，直接清除 player={} value='{}'", player, oldValue);
            metrics.spectateEviction(EvictReason.REWATCH, EvictResult.INVALID_MARK);
            return releasePrevious(playerId, player, oldValue, d, true);
        }
        long previous = decoded.get().battleId();
        if (requestedBattleId != 0 && requestedBattleId == previous) {
            // 重看同一场：只删标记。不发 RemoveObserver——那会给仍活着的旧会话推一条假的 166；重推 177 与首帧、换会话关旧直连由 battle 的幂等分支负责
            log.info("[spectate] 重看同一场，只删旧标记 player={} battle_id={}", player, id(previous));
            Verdict stop = releasePrevious(playerId, player, oldValue, d, false);
            if (stop == null) {
                pending.sameBattleRewatch = true; // 从这里起：名单里可能还有他，标记却没有了
            }
            return stop;
        }

        // 换场 / 随机：旧场还在就先同步清退
        EvictResult result;
        switch (placements.read(previous, d)) {
            case PlacementStore.Read.Failed failed -> {
                log.error("[spectate] 清退旧场时读落点记录失败，不发 RemoveObserver player={} battle_id={}: {}", player, id(previous), failed.why());
                result = EvictResult.READ_FAILED;
            }
            case PlacementStore.Read.Absent absent -> result = EvictResult.NO_RECORD; // 旧场已收尾，标记只是残留
            case PlacementStore.Read.Found found -> {
                // J 行：换场要先后发两跳。预算不够时什么都不做——旧标记原样保留，玩家仍在看旧场
                long needMs = MatchBudgets.WATCH_REWATCH_RESERVE_MS + MatchBudgets.WATCH_ADD_MIN_BUDGET_MS;
                if (d.remainingMillis() < needMs) {
                    log.warn("[spectate] WatchBattle 剩余预算不足 {} ms，不换场（旧标记保留） player={} previous_battle_id={}", needMs, player,
                            id(previous));
                    return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
                }
                Deadline hardStop = SpectateRules.reserveBefore(d, MatchBudgets.WATCH_REWATCH_RESERVE_MS);
                Outcome outcome = observers.remove(found.placement(), playerId, SpectateRules.REASON_REWATCH,
                        SpectateRules.hopTimeout(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS, hardStop), hardStop);
                result = evictResultOf(outcome);
                if (result == EvictResult.RPC_FAILED) {
                    log.error("[spectate] 清退旧场的 RemoveObserver 没调通（名单里的残留随那一场结束清理） player={} battle_id={} node={}: {}",
                            player, id(previous), node(found.placement()), outcome);
                } else {
                    log.info("[spectate] 已清退旧场 player={} battle_id={} reason={} result={}", player, id(previous), SpectateRules.REASON_REWATCH,
                            result);
                }
            }
        }
        metrics.spectateEviction(EvictReason.REWATCH, result);
        return releasePrevious(playerId, player, oldValue, d, true);
    }

    /**
     * 按读到的原串删旧标记。删不掉（Redis 故障 / 预算已尽）→ 16004：带着一个没删掉的旧标记往下走，抢占只会得到一条误导的 16016。
     *
     * @param retryAsync 删失败之后要不要再尽力异步删一次。换场 / 脏标记：要（旧场已清退或本来就指不到哪一场，标记只是残留）。
     *                   重看同一场：不要——玩家仍登记在这一场，旧标记留着才对（开局清退只认标记；基线在同样的故障下标记也还在）。
     */
    private Verdict releasePrevious(long playerId, String player, String oldValue, Deadline d, boolean retryAsync) {
        try {
            store.release(playerId, oldValue, d);
            return null;
        } catch (Deadline.DependencyException e) {
            log.error("[spectate] WatchBattle 删旧观战标记失败 player={} 再异步删一次={}: {}", player, retryAsync, why(e));
            if (retryAsync) {
                releaseAsyncQuietly(playerId, oldValue);
            }
            return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
        }
    }

    // ================================================================ 随机选场

    /**
     * 随机挑一场（基线 {@code spectate.go:236-274}）：至多 {@link MatchBudgets#RANDOM_PICK_TRIES} 挑；挑到非法成员就按原串剔除后重挑。
     * 过期成员不会被挑中（存储只在未过期的成员里取，W8）。
     *
     * @return 挑中的 battle_id；0 = 没有可看的场
     * @throws Deadline.DependencyException 读索引失败（调用方回 16004）
     */
    private long pickRandom(Deadline d) {
        for (int attempt = 0; attempt < MatchBudgets.RANDOM_PICK_TRIES; attempt++) {
            SpectateStore.Pick pick = store.pickRandom(random.getAsDouble(), d);
            if (!(pick instanceof SpectateStore.Pick.Member member)) {
                return 0;
            }
            OptionalLong battleId = SpectateRules.parseMember(member.member());
            if (battleId.isPresent()) {
                return battleId.getAsLong();
            }
            log.error("[spectate] 可观战索引出现非法成员，剔除 member='{}'", member.member());
            evict(new Eviction.Invalid(member.member()), IndexEviction.INVALID_MEMBER, d);
        }
        return 0;
    }

    // ================================================================ 第 14、15 行：登记成功之后的复查

    /**
     * AddObserver 成功之后再看一次票据与战斗锁（基线 {@code watchbattlelogic.go:206-231}）：收窄「入口检查 → 写标记」之间的窗口——
     * 并发的 gather（尤其即时开局的 PVE_SOLO）可能在本次写标记之前就做完了清退、读不到标记。两次读并行，各自只等到请求截止。
     */
    private Verdict recheck(long playerId, String player, long battleId, BattlePlacement placement, String mark, boolean randomMode, Deadline d) {
        CompletableFuture<Boolean> ticketRead = startRead(() -> tickets.read(playerId, d).isPresent());
        CompletableFuture<Boolean> lockRead = startRead(() -> players.inBattle(playerId, d));
        boolean hasTicket = false;
        try {
            hasTicket = d.await(ticketRead, "复查读票据");
        } catch (Deadline.DependencyException e) {
            // 读票失败只记日志、按无票：复查是尽力收窄，不引入新的失败面
            log.error("[spectate] 复查读票据失败（按无票继续） player={}: {}", player, why(e));
        }
        boolean locked;
        try {
            locked = d.await(lockRead, "复查读战斗锁");
        } catch (Deadline.DependencyException e) {
            // 读锁失败（含等到请求截止）按有锁（BW2）：宁可多清退一个观众，也不放进「观战 + 参战」
            log.error("[spectate] 复查读战斗锁失败（按有锁处理） player={}: {}", player, why(e));
            locked = true;
        }
        if (!hasTicket && !locked) {
            log.info("[spectate] 观战接入成功 player={} battle_id={} node={} random={}", player, id(battleId), node(placement), randomMode);
            return new Verdict(WatchOutcome.OK, MatchTips.watchAccepted(battleId));
        }

        // 自我清退：RemoveObserver 发出即返回（应答不依赖它的结果；这时剩余预算可能只有约 0.2 s），然后按值删标记
        log.info("[spectate] 观战与并发的排队 / 开局冲突，自我清退 player={} battle_id={} ticket={} locked={}", player, id(battleId), hasTicket,
                locked);
        selfEvict(playerId, player, battleId, placement);
        releaseMark(playerId, player, mark, d, null);
        return rejected(WatchOutcome.QUEUED, MatchTip.WATCH_QUEUED);
    }

    /**
     * 自我清退：RemoveObserver({@code concurrent_queue}) 发出即返回、永不抛——应答不依赖它的结果。两处用：复查命中（第 14 行），
     * 以及「重看同一场」时抢标记发现已有票据（第 13 行的 16014：旧标记已删而观众登记还在）。battle 对不在名单里的人是空操作。
     */
    private void selfEvict(long playerId, String player, long battleId, BattlePlacement placement) {
        try {
            observers.removeAsync(placement, playerId, SpectateRules.REASON_CONCURRENT_QUEUE)
                    .thenAccept(outcome -> selfEvicted(player, battleId, outcome));
        } catch (RuntimeException e) { // 约定不抛；自我清退发不出去不改变应答
            log.error("[spectate] 自我清退的 RemoveObserver 没能发出 player={} battle_id={}", player, id(battleId), e);
            metrics.spectateEviction(EvictReason.CONCURRENT_QUEUE, EvictResult.RPC_FAILED);
        }
    }

    /** 自我清退的结局（在直拨器的线程上回调：只记指标与日志，不阻塞）。 */
    private void selfEvicted(String player, long battleId, Outcome outcome) {
        EvictResult result = evictResultOf(outcome);
        metrics.spectateEviction(EvictReason.CONCURRENT_QUEUE, result);
        if (result == EvictResult.RPC_FAILED) {
            log.error("[spectate] 自我清退的 RemoveObserver 没调通（名单里的残留随那一场结束清理） player={} battle_id={}: {}", player, id(battleId),
                    outcome);
        }
    }

    /** 在复查的执行器上起一次读；起不了（拒收、内存耗尽）就是一次失败的读。 */
    private CompletableFuture<Boolean> startRead(Supplier<Boolean> read) {
        try {
            return CompletableFuture.supplyAsync(read, recheckThreads);
        } catch (RuntimeException | OutOfMemoryError e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ================================================================ 小工具

    /**
     * 按值释放本次的标记，永不抛：预算内同步做（之后的应答或下一轮抢占要看到它已经不在）；预算已尽或同步失败就尽力异步再发一次。
     *
     * @param pending 非 null 时顺手清掉「出异常要释放」的记号（已经交代过了）
     * @return true = 同步释放没有出错（标记确定不是本次的值了）；false = 只发出了尽力的异步释放
     */
    private boolean releaseMark(long playerId, String player, String mark, Deadline d, Pending pending) {
        if (pending != null) {
            pending.mark = null;
        }
        if (!d.expired()) {
            try {
                store.release(playerId, mark, d);
                return true;
            } catch (Deadline.DependencyException e) {
                log.error("[spectate] 释放观战标记失败（再尽力异步释放一次） player={}: {}", player, why(e));
            }
        }
        releaseAsyncQuietly(playerId, mark);
        return false;
    }

    private void releaseAsyncQuietly(long playerId, String mark) {
        try {
            store.releaseAsync(playerId, mark);
        } catch (RuntimeException e) { // 约定不抛
            log.error("[spectate] 异步释放观战标记时出错（标记随 TTL 自清） player={}", id(playerId), e);
        }
    }

    /** 尽力剔除：失败只记日志（清扫与之后的 163 / 164 会再摘）；存储回报「真的摘了」才计数。 */
    private void evict(Eviction eviction, IndexEviction reason, Deadline d) {
        try {
            if (store.evict(eviction, d)) {
                metrics.watchableIndexEvicted(reason, 1);
            }
        } catch (Deadline.DependencyException e) {
            log.error("[spectate] 剔除可观战索引成员失败（忽略） member='{}' reason={}: {}", eviction.member(), reason, why(e));
        }
    }

    private static EvictResult evictResultOf(Outcome outcome) {
        return switch (outcome) {
            case Outcome.Replied replied -> EvictResult.REMOVED;
            case Outcome.Dead dead -> EvictResult.NO_RECORD;
            case Outcome.NotDelivered notDelivered -> EvictResult.RPC_FAILED;
            case Outcome.Unknown unknown -> EvictResult.RPC_FAILED;
        };
    }

    private Verdict busy(String stage, String player, Deadline.DependencyException e) {
        log.error("[spectate] WatchBattle {}失败 player={}: {}", stage, player, why(e));
        return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
    }

    private static Verdict rejected(WatchOutcome outcome, MatchTip tip) {
        return new Verdict(outcome, MatchTips.watchRejected(tip));
    }

    private void count(WatchOutcome outcome) {
        try {
            metrics.watchBattle(outcome);
        } catch (RuntimeException e) {
            log.warn("[spectate] 记 163 出口指标出错（忽略）", e);
        }
    }

    private static String id(long unsigned) {
        return Long.toUnsignedString(unsigned);
    }

    private static String node(BattlePlacement placement) {
        return Integer.toUnsignedString(placement.getBattleNodeId()) + "(" + placement.getRpcHost() + ":"
                + Integer.toUnsignedString(placement.getRpcPort()) + "#" + placement.getBattleInstanceId() + ")";
    }

    private static String why(Deadline.DependencyException e) {
        Throwable cause = e.getCause();
        return cause == null ? e.getMessage() : e.getMessage() + " <- " + cause;
    }
}
