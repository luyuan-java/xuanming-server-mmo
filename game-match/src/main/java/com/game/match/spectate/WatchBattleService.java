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
 * 本地 163 的有界流程：检查票据/锁，按旧原件清退（同场也一样），读取会话与固定落点，
 * 先持久化包含完整 Add 的标记，再调用 battle。每次操作有规范 UUID、25 秒准入截止、固定实例、路由与公开名字。
 *
 * <p>标记按原字符串 compare-delete：并发或迟到取消不能删除后来操作。取消必须验证 cancelled、原 UUID、固定实例；
 * 超时、空回包、无证明拒绝、目录判死均不冒充取消成功。Add 未知以独立 3 秒预算补发原件 Remove：
 * 墓碑能封闭仍在途的同 UUID Add；取消未知继续保留标记，重看与开局清退可以重放同一原件恢复。
 * 不存在“同场只改 nonce 不清退”的分支。旧 battle:nonce 无法通过当前 battle 账本，可作为旧格式按值清理；
 * 新格式损坏或玩家身份错配则保留并失败。
 *
 * <p>既有判定顺序、随机两次上限、建房窗口/attempt 守护、复查票据和锁的方向、4.5 秒请求预算保持。
 * 同场重看先推旧观战结束，再新操作分配；成功应答返回新 spectator_operation_id。全球 SQL 观战仍走 GlobalWatchService。
 * 此路径依旧是本地兼容链，Redis TTL、开局尽力清退与跨存储复查不是全球 SQL 事务互斥的证明。
 *
 * <p>可见性闸（mmorpg battle-spectate-tiered-fanout.md §10 第 6 条，与 Go 版 watchbattlelogic 同口径）：指定场在互斥检查之后、
 * 任何副作用（清退旧观战、写标记、调 battle）之前用一次原子读选定，落点记录里冻结的可见性不是 PUBLIC（{@link SpectateVisibilityPolicy}）
 * 就回与「记录不在」逐字节相同的 16018「该战斗不存在或已结束」，两者都不清退旧观战——否则「旧观战有没有被拆」会成为探测私密局是否存在的旁路；
 * 随机模式挑到私密局（本不该在索引里）只摘成员、换下一场。私密局的落点记录一律不删（179 补签靠它定位 battle）。
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
    private final Supplier<String> operations;
    private final Executor recheckThreads;
    private GlobalWatchService global;
    public WatchBattleService withGlobal(GlobalWatchService global){this.global=global;return this;}
    /** 大厅档准入（缺省关闭：观众只走直连席，满了回 16021）。装配时设一次。 */
    private SpectateRelay relay = SpectateRelay.disabled();
    public WatchBattleService withRelay(SpectateRelay relay){this.relay=Objects.requireNonNull(relay,"relay");return this;}
    private java.util.function.BiFunction<Long,Deadline,String> publicNames=(player,deadline)->"";
    public WatchBattleService withPublicNames(java.util.function.BiFunction<Long,Deadline,String> names){publicNames=Objects.requireNonNull(names);return this;}
    public WatchBattleResponse watch(SessionContext session,com.game.proto.match.WatchBattleRequest request,Deadline deadline){
        if(global!=null){
            // 全球路径自己给出出口：业务拒绝各记各的 outcome，重放已存回执与首答一致
            var verdict=global.watch(session,request,deadline);count(verdict.outcome());return verdict.response();
        }
        return watch(session,request.getBattleId(),deadline);
    }

    /** 一次判定的结论：出口计数与应答。 */
    private record Verdict(WatchOutcome outcome, WatchBattleResponse response) {
    }

    /** 一次请求自己的小状态。只在这一次请求的线程上读写。 */
    private static final class Pending {
        /** 本次请求写下、还没有交代清楚的标记（出未预期异常时按值尽力释放，J2）。 */
        String mark;
        /** 本次已写入标记的完整原件；Add 发出后异常也只能按它补偿。 */
        LocalObserverOperation original;
        boolean addMayHaveRun;
    }

    /** 生产装配：随机数取 {@link ThreadLocalRandom}，操作号取 {@link java.util.UUID#randomUUID}，复查的两次读各起一条虚拟线程。 */
    public WatchBattleService(SpectateStore store, PlacementStore placements, PlayerStatusReader players, TicketReader tickets,
                              ObserverDialer observers, MatchMetrics metrics) {
        this(store, placements, players, tickets, observers, metrics, () -> ThreadLocalRandom.current().nextDouble(), () -> java.util.UUID.randomUUID().toString(),
                Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(RECHECK_THREAD_PREFIX, 0).factory()));
    }

    /**
     * @param store          观战标记、可观战索引、落点的原子读
     * @param placements     现有装配保留的落点存储；新观战原子读走 store，取消只用持久原件
     * @param players        战斗锁与在线目录（别的进程拥有）
     * @param tickets        票据的只读口（只用于复查；入口与抢占的「有没有票」在 {@code store} 的脚本里判）
     * @param observers      按落点直拨 battle 的观众 RPC
     * @param random         随机选场用的 [0, 1) 随机数（测试给定值）
     * @param operations     每次新观战使用的规范 UUID
     * @param recheckThreads 复查两次读的执行器：任务会阻塞到读完或请求截止；测试可传 {@code Runnable::run}
     */
    WatchBattleService(SpectateStore store, PlacementStore placements, PlayerStatusReader players, TicketReader tickets, ObserverDialer observers,
                       MatchMetrics metrics, DoubleSupplier random, Supplier<String> operations, Executor recheckThreads) {
        this.store = Objects.requireNonNull(store, "store");
        this.placements = Objects.requireNonNull(placements, "placements");
        this.players = Objects.requireNonNull(players, "players");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.observers = Objects.requireNonNull(observers, "observers");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.random = Objects.requireNonNull(random, "random");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.recheckThreads = Objects.requireNonNull(recheckThreads, "recheckThreads");
    }

    /**
     * 处理一次 163。
     *
     * @param session            gate 填的会话上下文：身份只取 {@code player_id}（0 = 没进游戏）；公开名字由注入的查询口提供
     * @param requestedBattleId  请求里的 {@code battle_id}（0 = 随机观战）
     * @param deadline           本次请求的截止
     * @return 应答（业务拒绝、依赖故障、预算不足在 {@code error_message}；成功带战斗号与新观战操作 UUID）
     * @throws RuntimeException 只在未预期的异常（bug）时：已抢到、还没交给 battle 的标记已按值尽力释放，由派发器回信封 1003
     */
    public WatchBattleResponse watch(SessionContext session, long requestedBattleId, Deadline deadline) {
        if(global!=null)throw new IllegalArgumentException("全球观战必须保留客户端原UUID");
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
                log.error("[spectate] WatchBattle 出了未预期的异常，按原件恢复本次操作 player={} operation={}", id(playerId),
                        pending.original == null ? "unavailable" : pending.original.add().getSpectatorOperationId());
                if (pending.addMayHaveRun) compensate(pending.original, mark, SpectateRules.REASON_OUTCOME_UNKNOWN);
                else releaseAsyncQuietly(playerId, mark);
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

        // 可见性闸：指定场在互斥检查之后、任何副作用（清退旧观战、写标记、调 battle）之前用一次原子读选定。放在互斥检查之后，
        // 排队中 / 战斗中的玩家不论指定哪一场都先得到同一个互斥结论，不额外暴露目标场的信息；这次读到的快照留给下面的登记循环用
        boolean randomMode = requestedBattleId == 0;
        SpectateStore.Snapshot specified = null;
        if (!randomMode) {
            try {
                specified = store.read(requestedBattleId, d);
            } catch (Deadline.DependencyException e) {
                return busy("读落点记录", player, e);
            }
            switch (classify(player, requestedBattleId, specified, specified.published(), d)) {
                case WATCHABLE -> {
                    // 往下清退旧场、登记
                }
                case ABSENT -> {
                    log.info("[spectate] 指定观战的战斗没有落点记录 player={} battle_id={} published={}", player, id(requestedBattleId),
                            specified.published());
                    return rejected(WatchOutcome.NOT_FOUND, MatchTip.WATCH_NOT_FOUND);
                }
                case PRIVATE -> {
                    // 与「记录不在」逐字节同一个应答，只在指标上区分
                    return rejected(WatchOutcome.PRIVATE, MatchTip.WATCH_NOT_FOUND);
                }
                case CORRUPT -> {
                    return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
                }
            }
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

        // 选场 + 登记：随机两轮、指定一轮（指定场已在副作用之前选定，这里复用那次原子读）
        int rounds = randomMode ? MatchBudgets.RANDOM_WATCH_ROUNDS : 1;
        for (int round = 0; round < rounds; round++) {
            long battleId = requestedBattleId;
            // 「是否已公开 + 落点 + 时刻」一次原子读：建房窗口判定的三个输入不得晚于读落点（W6）
            SpectateStore.Snapshot snapshot = specified;
            if (randomMode) {
                try {
                    battleId = pickRandom(d);
                } catch (Deadline.DependencyException e) {
                    return busy("随机选场", player, e);
                }
                if (battleId == 0) {
                    break; // 没有可看的场
                }
                try {
                    snapshot = store.read(battleId, d);
                } catch (Deadline.DependencyException e) {
                    return busy("读落点记录", player, e);
                }
                // 随机挑出来的就是索引成员，按已公开处理（同基线）
                switch (classify(player, battleId, snapshot, true, d)) {
                    case WATCHABLE -> {
                        // 往下登记
                    }
                    case ABSENT, PRIVATE -> {
                        continue; // 成员已摘（落点不动），换下一场
                    }
                    case CORRUPT -> {
                        return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
                    }
                }
            }
            String battle = id(battleId);
            boolean published = randomMode || snapshot.published();
            if (!(snapshot.record() instanceof SpectateStore.Record.Found found)) {
                // classify 只对好记录回 WATCHABLE：走到这里是程序缺陷
                throw new IllegalStateException("可观战判定之后落点记录不是好记录 battle_id=" + battle);
            }
            BattlePlacement placement = found.placement();

            // 大厅档（mmorpg battle-spectate-tiered-fanout.md §15.6）：已是中继档的场不撞直连席，不调 battle、不写标记，只签凭证
            try {
                if (relay.relayed(battleId, d)) {
                    SpectateRelay.Admission admission = relay.lobby(placement, playerId, snapshot.redisNowMs(), d);
                    if (admission instanceof SpectateRelay.Admission.Rejected refused && refused.tip() == MatchTip.WATCH_NOT_FOUND) {
                        // 来源实例已换，房间随它没了：与 battle 回 1004 同口径懒剔除（按 attempt 守护），随机模式换下一场
                        if (placement.getAttempt() != 0) {
                            evict(new Eviction.Dead(battleId, placement.getAttempt()), IndexEviction.DEAD_NODE, d);
                        }
                        if (randomMode) {
                            continue;
                        }
                    }
                    return settle(player, battleId, admission);
                }
            } catch (Deadline.DependencyException e) {
                return busy("读观战档位", player, e);
            }

            // 第 13 行：原子完成「没有票据 ∧ 没有标记 → 写标记」。标记必须先于观众登记生效
            LocalObserverOperation original;
            try {
                original = LocalObserverOperation.create(placement, playerId, routing, publicName(playerId, d), operations.get(), snapshot.redisNowMs());
            } catch (IllegalArgumentException invalid) {
                log.error("[spectate] 固定观战原件不完整，不抢标记、不调用 battle player={} battle_id={}", player, battle);
                return rejected(WatchOutcome.REJECTED, MatchTip.WATCH_NOT_WATCHABLE);
            }
            String mark = original.mark();
            pending.original = original;
            pending.addMayHaveRun = false;
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
                    // 命令被重发时首轮可能已写入标记：按本次的值释放
                    releaseMark(playerId, player, mark, d, pending);
                    log.info("[spectate] 抢观战标记时发现已有票据，不登记观众 player={} battle_id={}", player, battle);
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
                releaseMark(playerId, player, mark, d, pending);
                log.warn("[spectate] WatchBattle 剩余预算不足 {} ms，不发 AddObserver player={} battle_id={}", MatchBudgets.WATCH_ADD_MIN_BUDGET_MS,
                        player, battle);
                return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
            }
            Deadline hardStop = SpectateRules.reserveBefore(d, MatchBudgets.WATCH_ADD_RESERVE_MS);
            Duration timeout = SpectateRules.hopTimeout(MatchBudgets.ADD_OBSERVER_TIMEOUT_MS, hardStop);
            AddObserverRequest request = original.add();
            pending.addMayHaveRun = true;
            Outcome outcome = observers.add(placement, request, timeout, hardStop);

            boolean deadNode;
            switch (outcome) {
                case Outcome.Replied replied when replied.tipId() == 0 -> {
                    // 观众已登记：从这里起标记要留着（复查没命中就是成功；命中由自我清退按值删）
                    pending.mark = null;
                    return recheck(playerId, player, battleId, placement, original, mark, randomMode, d);
                }
                case Outcome.Replied replied when replied.tipId() == MatchTips.BATTLE_ROOM_NOT_FOUND -> deadNode = false;
                case Outcome.Dead dead -> deadNode = true;
                case Outcome.Replied replied when replied.tipId() == MatchTips.SPECTATE_FULL -> {
                    // 第 17a 行：观众席已满，明确拒绝（battle 留了墓碑，同一次登记不会迟到翻成准入）：回滚标记；随机模式也不换场
                    releaseMark(playerId, player, mark, d, pending);
                    log.info("[spectate] battle 观众席已满 player={} battle_id={} node={} relay={}", player, battle, node(placement), relay.enabled());
                    // 开关开着就转大厅档，这次不算满员；关着回 16021
                    return relay.enabled() ? settle(player, battleId, relay.escalate(placement, playerId, snapshot.redisNowMs(), d))
                            : rejected(WatchOutcome.FULL, MatchTip.WATCH_FULL);
                }
                case Outcome.Replied replied -> {
                    // 是参战者 / 签不出票：明确拒绝，回滚标记；随机模式也不换场（BW6）
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
                    // 原件取消会先立墓碑，能封闭仍在途的 Add；没有正证明时保留原标记供下一次清退恢复。
                    pending.mark = null;
                    compensate(original, mark, SpectateRules.REASON_OUTCOME_UNKNOWN);
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

    /**
     * 大厅档准入的结局 → 出口。大厅档的观众在 match 没有逐人状态（§15.1）：标记此刻一定不是本次写下的。
     * 每名观众一行的日志只到 DEBUG：大厅档没有每房 20 席的上限，热门场开播时 INFO 会随观众数线性增长；趋势看 {@code game.match.watch.battle{outcome=lobby}}。
     */
    private Verdict settle(String player, long battleId, SpectateRelay.Admission admission) {
        return switch (admission) {
            case SpectateRelay.Admission.Lobby lobby -> {
                log.debug("[spectate] 观战转大厅档 player={} battle_id={}", player, id(battleId));
                yield new Verdict(WatchOutcome.LOBBY, lobby.response());
            }
            case SpectateRelay.Admission.Rejected refused -> rejected(refused.outcome(), refused.tip());
            case SpectateRelay.Admission.Unavailable unavailable -> rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
        };
    }

    // ================================================================ 可见性闸：选中的一场能不能看

    /** 选中的一场（指定场或随机挑中的索引成员）能不能看。 */
    private enum Target {
        /** 好记录且冻结的可见性是 PUBLIC：往下登记。 */
        WATCHABLE,
        /** 落点记录不在：客户端看到「不存在或已结束」。 */
        ABSENT,
        /** 非公开局：客户端看到的与 {@link #ABSENT} 逐字节相同，不泄露私密局存在与否。 */
        PRIVATE,
        /** 记录在但损坏：16004，不剔除（BW9：删掉它会毁掉补签的定位）。 */
        CORRUPT
    }

    /**
     * 判定选中的一场能不能看（指定场与随机模式共用这一判定），并做判定附带的收尾：
     * <ul>
     *   <li>记录损坏：计 {@code anomalies{corrupt_record}}，不剔除；</li>
     *   <li>记录不在：只摘读之前已在索引里的成员，且落点此刻仍不存在才摘（{@link Eviction.Missing}），永不删落点——读到「没有」之后才预写的
     *       记录（一场正在建房的战斗）不能动；</li>
     *   <li>私密局：同样只摘读之前已在索引里的成员（开局钩子不公开私密局，在就是残留），落点留给 179 补签。</li>
     * </ul>
     * 不碰观战标记、不调 battle，所以能放在 163 的一切副作用之前。
     *
     * @param published 读的那一刻是否已在可观战索引里（随机选场挑出来的成员恒为 true）
     */
    private Target classify(String player, long battleId, SpectateStore.Snapshot snapshot, boolean published, Deadline d) {
        return switch (snapshot.record()) {
            case SpectateStore.Record.Corrupt corrupt -> {
                metrics.watchableAnomaly(WatchAnomaly.CORRUPT_RECORD);
                log.error("[spectate] WatchBattle 落点记录损坏（不剔除） player={} battle_id={}: {}", player, id(battleId), corrupt.why());
                yield Target.CORRUPT;
            }
            case SpectateStore.Record.Absent absent -> {
                if (published) {
                    evict(new Eviction.Missing(battleId), IndexEviction.MISSING_RECORD, d);
                }
                yield Target.ABSENT;
            }
            case SpectateStore.Record.Found found -> {
                if (SpectateVisibilityPolicy.isPublic(found.placement())) {
                    yield Target.WATCHABLE;
                }
                if (published) {
                    // 无条件只摘成员（与非法成员同一个存储原语），不删落点
                    log.warn("[spectate] 可观战索引里有非公开局（残留），只摘成员、不删落点 battle_id={} visibility={}", id(battleId),
                            Integer.toUnsignedString(found.placement().getSpectateVisibility()));
                    evict(new Eviction.Invalid(SpectateRules.member(battleId)), IndexEviction.PRIVATE, d);
                }
                log.info("[spectate] 拒绝观战非公开局 player={} battle_id={}", player, id(battleId));
                yield Target.PRIVATE;
            }
        };
    }

    // ================================================================ 第 7 行：旧标记

    /**
     * 入口处收拾旧标记（基线 {@code watchbattlelogic.go:94-113}、{@code spectate.go:282-308}）。
     *
     * @return null = 旧标记已删，继续往下判；非 null = 就此回包（预算不够换场、或删旧标记失败）
     */
    private Verdict clearPreviousMark(long playerId, String player, String oldValue, long requestedBattleId, Deadline d, Pending pending) {
        Optional<LocalObserverOperation> decoded = LocalObserverOperation.decode(oldValue);
        if (decoded.isEmpty()) {
            // 旧 battle:nonce 不能通过现行 battle 准入；仅这种旧格式与普通脏值允许按值清除。
            // 新格式损坏不能被解释为已经取消，否则会丢失恢复入口。
            if (LocalObserverOperation.isVersionedMark(oldValue)) return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
            metrics.spectateEviction(EvictReason.REWATCH, EvictResult.INVALID_MARK);
            return releasePrevious(playerId, player, oldValue, d, true);
        }
        LocalObserverOperation original = decoded.get();
        if (original.playerId() != playerId) return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
        long needMs = MatchBudgets.WATCH_REWATCH_RESERVE_MS + MatchBudgets.WATCH_ADD_MIN_BUDGET_MS;
        if (d.remainingMillis() < needMs) return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
        Deadline hardStop = SpectateRules.reserveBefore(d, MatchBudgets.WATCH_REWATCH_RESERVE_MS);
        Outcome outcome = observers.remove(original, SpectateRules.REASON_REWATCH,
                SpectateRules.hopTimeout(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS, hardStop), hardStop);
        EvictResult result = evictResultOf(outcome);
        metrics.spectateEviction(EvictReason.REWATCH, result);
        if (result != EvictResult.REMOVED) {
            log.warn("[spectate] 旧观战原件取消未获证明，保留原标记、不登记新操作 player={} battle_id={} outcome={}",
                    player, id(original.battleId()), outcome);
            return rejected(WatchOutcome.INTERNAL, MatchTip.BUSY);
        }
        // 同场重看、换场、随机模式完全同口径；只有原件取消证明才允许释放旧槽。
        return releasePrevious(playerId, player, oldValue, d, true);
    }
    /**
     * 按读到的原串删旧标记。删不掉（Redis 故障 / 预算已尽）→ 16004：带着一个没删掉的旧标记往下走，抢占只会得到一条误导的 16016。
     *
     * @param retryAsync 删失败之后要不要再尽力异步删一次。换场 / 脏标记：要（旧场已清退或本来就指不到哪一场，标记只是残留）。
     *                   只有取消已确认或明确旧格式无有效准入时调用；任何未知取消均不进入此方法。
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
    private Verdict recheck(long playerId, String player, long battleId, BattlePlacement placement, LocalObserverOperation original, String mark, boolean randomMode, Deadline d) {
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
            return new Verdict(WatchOutcome.OK, MatchTips.watchAccepted(battleId).toBuilder()
                    .setSpectatorOperationId(original.add().getSpectatorOperationId()).build());
        }

        // 自我清退使用独立预算；业务应答不等取消，只有迟到的原操作正证明才能触发按值删除。
        log.info("[spectate] 观战与并发的排队 / 开局冲突，自我清退 player={} battle_id={} ticket={} locked={}", player, id(battleId), hasTicket,
                locked);
        compensate(original, mark, SpectateRules.REASON_CONCURRENT_QUEUE);
        return rejected(WatchOutcome.QUEUED, MatchTip.WATCH_QUEUED);
    }

    /** 有界补偿使用同一原件；未知保留恢复槽，迟到正证明也只能 compare-delete 自己的原串。 */
    private void compensate(LocalObserverOperation original, String mark, String reason) {
        EvictReason metricReason = SpectateRules.REASON_OUTCOME_UNKNOWN.equals(reason) ? EvictReason.WATCH_OUTCOME_UNKNOWN : EvictReason.CONCURRENT_QUEUE;
        try {
            observers.removeAsync(original, reason).thenAccept(outcome -> {
                EvictResult result = evictResultOf(outcome);
                metrics.spectateEviction(metricReason, result);
                if (result == EvictResult.REMOVED) releaseAsyncQuietly(original.playerId(), mark);
                else log.warn("[spectate] 补偿未获原操作取消证明，保留标记 player={} battle_id={} outcome={}",
                        id(original.playerId()), id(original.battleId()), outcome);
            });
        } catch (RuntimeException failure) {
            log.warn("[spectate] 原观战补偿未发出，保留标记 player={} battle_id={}", id(original.playerId()), id(original.battleId()), failure);
            metrics.spectateEviction(metricReason, EvictResult.RPC_FAILED);
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
            case Outcome.Replied replied -> replied.tipId() == 0 ? EvictResult.REMOVED : EvictResult.RPC_FAILED;
            case Outcome.Dead dead -> EvictResult.RPC_FAILED;
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
    private String publicName(long player,Deadline deadline){try{String name=publicNames.apply(player,deadline);return name==null?"":name;}catch(RuntimeException failure){return "";}}
}
