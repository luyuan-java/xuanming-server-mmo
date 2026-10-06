package com.game.scene.battle;

import static com.game.scene.world.SceneMessageIds.push;
import static com.game.scene.world.SceneMessageIds.tip;

import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.discovery.battle.BattleRedis;
import com.game.discovery.battle.BattleRedis.EnterRead;
import com.game.proto.BattleConfirmedEvent;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleReconnectS2C;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleSettlementEvent;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.scene.battle.BattleFreeze.Phase;
import com.game.scene.battle.BattleSettlementService.Outcome;
import com.game.scene.battle.BattleSettlementService.Result;
import com.game.scene.battle.PlayerBattle.Recovery;
import com.game.scene.metrics.SceneBattleMetrics;
import com.game.scene.metrics.SceneBattleMetrics.AckResult;
import com.game.scene.metrics.SceneBattleMetrics.AckTrigger;
import com.game.scene.metrics.SceneBattleMetrics.Cancel;
import com.game.scene.metrics.SceneBattleMetrics.Confirm;
import com.game.scene.metrics.SceneBattleMetrics.HintTrigger;
import com.game.scene.metrics.SceneBattleMetrics.Prepare;
import com.game.scene.metrics.SceneBattleMetrics.RebuildReason;
import com.game.scene.metrics.SceneBattleMetrics.RebuildResult;
import com.game.scene.metrics.SceneBattleMetrics.Rescue;
import com.game.scene.metrics.SceneBattleMetrics.SettlementPath;
import com.game.scene.metrics.SceneBattleMetrics.SettlementResult;
import com.game.scene.pet.PetService;
import com.game.scene.team.TeamFollow;
import com.game.scene.world.BattleHooks;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.CommonErrorTip;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * scene 侧回合制战斗冻结与结算的全部流程（scene-battle-spec §7.4–§7.12；基线 {@code player_battle.cpp}）：备战 / 取消 / 确认 / 进场恢复 /
 * reaper / 结算到达 / 销账。冻结状态的唯一改写方（{@link #clearFreeze} 是解冻的唯一入口）。
 *
 * <p><b>线程</b>：全部方法只在场景逻辑线程上调用；Redis 脚本一律异步，结果经 {@code logic} 投递回逻辑线程后先核对
 * 「{@code world.playerById(pid) == player}，且冻结对象没变」再动状态（比基线 {@code GuidForLog} 更强，与 5.2 的 {@code switching() == sw} 同一纪律）。
 * 次序不靠连接 FIFO（§1.8）：要么同一段 Lua，要么 future 链。<b>两条各自发出的脚本，Redis 上谁先执行、回调谁先回到逻辑线程都没有保证</b>，
 * 所以读到的快照可能早于本实例已经完成的销账——由 {@link PlayerBattle} 的恢复代际与已销账集合兜住（审计 FRZ-1）：
 * 进场恢复只认最新一代的读；凡是本实例已发出过销账的局，不管哪条读把它带回来，一律按「已应用」处理，不再应用、不再按锁重建（{@link #applyAndFinish}）。
 *
 * <p><b>回调不许把状态卡住</b>（审计 FRZ-2 / STL-8）：回调里的意外异常、或逻辑线程拒绝投递，都不得让进场恢复停在 PENDING（置 RETRY、
 * 计 {@code recovery{error}}，由 reaper 重跑），也不得让应答 future 永不完成（备战 / 结算的应答异常完成 = 结局未知，调用方按传输失败处理；
 * 取消 / 解冻的应答正常完成——效果在应答前已经生效）。
 *
 * <p><b>与 5.2 互斥</b>（§7.13）：备战拒绝在途的换图（{@link SceneWorld#switchInFlight}，过期的 RESOLVING 槽就地作废）；挂冻结的路径（迟到确认、进场恢复）
 * 先看 FREEZING；结算在 FREEZING 时回 DEFERRED。
 */
public final class PlayerBattleService implements BattleHooks {

    private static final Logger log = LoggerFactory.getLogger(PlayerBattleService.class);

    static final int OK = 0;
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final int ENTITY_NULL = CommonErrorTip.common_error.kEntityIsNull_VALUE;
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    static final int SESSION_NOT_FOUND = CommonErrorTip.common_error.kSessionNotFound_VALUE;

    /** 解冻时对锁的处置（§7.4）。 */
    enum LockAction {
        /**
         * 只删备战锁（{@code b == X 且 s ≠ F} 才删，{@link BattleLocks#deletePreparingIfMatch}）：取消备战、备战失败这些「只该删 P 锁」的路径。
         * 删除被 Redisson 排到确认之后时锁已标 F、迟到确认可能已重建 FIGHTING 冻结——这把锁不能再删（审计 FRZ-7）。删完再补一次组队跟随。
         */
        DELETE_PREPARING,
        /** 条件删锁、不看 s（reaper 判废专用）：删完（回到逻辑线程、实例未换）再补一次组队跟随。 */
        DELETE_IF_MATCH,
        /** 锁留着（reaper 备战到期、结算应用后由 ACK 放锁）；立即补跟随（跟随链读到锁会放弃）。 */
        KEEP_LOCK
    }

    /** 快照路由的来源：会话、gate 节点与实例（链路登记表）、scene 节点 / 实例 / zone。会话链路不在时为 null。 */
    @FunctionalInterface
    public interface Routing {
        BattleRouting of(ScenePlayer player);
    }

    private final BattleLocks locks;
    private final BattleSettlementService settlements;
    private final SceneBattleTables tables;
    private final PetService pets;
    private final Routing routing;
    private final TeamFollow teamFollow;
    private final SceneBattleMetrics metrics;
    private final SceneClock clock;
    private final Executor logic;
    private final int reconnectHintId;
    private final int battleEndId;
    /** 装配时绑定一次（世界由同一个组合根建，钩子先于世界存在）。 */
    private SceneWorld world;

    /**
     * @param logic           投递回场景逻辑线程（停服后可能拒绝，拒绝时丢弃这次结果）
     * @param reconnectHintId 144 {@code BattleClientPlayer.NotifyBattleReconnect}
     * @param battleEndId     150 {@code BattleClientPlayer.NotifyBattleEnd}
     */
    public PlayerBattleService(BattleLocks locks, BattleSettlementService settlements, SceneBattleTables tables, PetService pets,
                               Routing routing, TeamFollow teamFollow, SceneBattleMetrics metrics, SceneClock clock, Executor logic,
                               int reconnectHintId, int battleEndId) {
        this.locks = Objects.requireNonNull(locks, "locks");
        this.settlements = Objects.requireNonNull(settlements, "settlements");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.pets = Objects.requireNonNull(pets, "pets");
        this.routing = Objects.requireNonNull(routing, "routing");
        this.teamFollow = Objects.requireNonNull(teamFollow, "teamFollow");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.logic = Objects.requireNonNull(logic, "logic");
        this.reconnectHintId = reconnectHintId;
        this.battleEndId = battleEndId;
    }

    /** 绑定世界（装配时一次；世界的钩子就是本对象）。 */
    public void attach(SceneWorld world) {
        if (this.world != null && this.world != world) {
            throw new IllegalStateException("PlayerBattleService 已绑定了另一个世界");
        }
        this.world = Objects.requireNonNull(world, "world");
    }

    // ================================================================== 备战（§7.5）

    /** 备战（逻辑线程）；应答在写锁结局回来后完成（仍在逻辑线程上）。 */
    public CompletableFuture<PrepareBattleResponse> prepare(PrepareBattleRequest request) {
        long playerId = request.getPlayerId();
        long battleId = request.getBattleId();
        long deadline = request.getDeadlineMs();
        if (playerId == 0 || battleId == 0 || deadline == 0) {
            return prepareRejected(INVALID_PARAMETER, Prepare.INVALID);
        }
        ScenePlayer player = world.playerById(playerId);
        if (player == null) {
            return prepareRejected(ENTITY_NULL, Prepare.NOT_HERE);
        }
        if (world.switchInFlight(player)) {
            // FREEZING 对应基线 PlayerFrozenComp；RESOLVING 是 D4（与 5.2 互斥）。经世界的「在途」判定而不是直接看 switchPhase：
            // 过了期限的 RESOLVING 槽（结果回调丢了）在这里就地作废，不会一直把备战挡成 1006（D4 只允许 ≤ 4 s 的竞态，审计 GAT-14）
            return prepareRejected(FEATURE_UNAVAILABLE, Prepare.SWITCHING);
        }
        if (player.inBattle()) {
            return prepareRejected(FEATURE_UNAVAILABLE, Prepare.IN_BATTLE);
        }
        if (!readyForNextBattle(player, battleId)) {
            return prepareRejected(FEATURE_UNAVAILABLE, Prepare.NOT_READY);
        }
        if (player.attributes().health() == 0) {
            return prepareRejected(FEATURE_UNAVAILABLE, Prepare.DEAD);
        }
        BattleRouting route = routing.of(player);
        if (route == null) {
            // Java 实例一定绑着会话链路，这里只做防御（基线组快照失败 1011）
            log.error("备战组快照失败：会话链路不在登记表里 player={} battle_id={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId));
            return prepareRejected(SESSION_NOT_FOUND, Prepare.NOT_HERE);
        }
        BattlePlayerSnapshot snapshot = BattleSnapshots.build(player, tables, pets.buildBattleSnapshot(player), route);
        long prepareDeadline = request.getPrepareDeadlineMs() != 0 ? request.getPrepareDeadlineMs() : deadline;
        BattleFreeze freeze = new BattleFreeze(battleId, request.getBattleNodeId(), Phase.PREPARING, deadline, prepareDeadline, true);
        freeze.setLockPending(true);
        player.battle().setFreeze(freeze);
        // D5：备战即停步，旁观者在下一个同步帧收到速度 0 的 66
        world.haltForBattle(player);
        CompletableFuture<PrepareBattleResponse> reply = new CompletableFuture<>();
        long ttl = BattleRedis.lockTtlSec(prepareDeadline, clock.epochMillis());
        onLogic(locks.prepareLock(playerId, battleId, request.getBattleNodeId(), deadline, prepareDeadline, ttl), (held, error) -> {
            try {
                onPrepareLocked(player, freeze, snapshot, held, error, reply);
            } catch (RuntimeException e) {
                abandonPrepare(player, freeze);
                throw e;
            }
        }, reply::completeExceptionally);
        return reply;
    }

    /**
     * 备战写锁的回调中途抛了意外异常（应答随后异常完成 = 结局未知，调用方按「可能已冻结」补发取消）：把状态收拾成「没有这个冻结、没有这一局的备战锁」
     * （审计 FRZ-2）。冻结若还挂着就摘掉；再尽力只删一次备战锁——正常流程里紧随其后的那次尽力删锁可能正是被这个异常跳过的
     * （例如写锁超时、解冻那一步抛出），不补的话孤儿锁留到备战 TTL。删的是 {@code b == X 且 s ≠ F} 的锁，对别的局、对已确认的局都无害。
     */
    private void abandonPrepare(ScenePlayer player, BattleFreeze freeze) {
        long playerId = player.playerId();
        long battleId = freeze.battleId();
        try {
            if (world.playerById(playerId) == player && player.battle().freeze() == freeze) {
                freeze.setLockPending(false);
                clearFreeze(player, LockAction.KEEP_LOCK);
            }
            onLogic(locks.deletePreparingIfMatch(playerId, battleId),
                    (r, e) -> logScriptError("备战回调出错后尽力删锁", playerId, battleId, e));
        } catch (RuntimeException suppressed) {
            log.error("备战回调出错后的收尾也出错 player={} battle_id={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId), suppressed);
        }
    }

    /** 进场规整时（{@code PlayerInitializer}）：战斗结算账本加载时判了损坏就大声报一次（做法同 {@code AssetOpService.checkLedgerOnLoad}，审计 STL-11）。 */
    public static void checkLedgerOnLoad(ScenePlayer player) {
        String reason = player.battleLedger().invalidReason();
        if (reason != null) {
            log.error("战斗结算账本损坏：该玩家的结算一律延后、备战一律 1006（原样保留，等人工排查） player={} 原因={}",
                    Long.toUnsignedString(player.playerId()), reason);
        }
    }

    /** 恢复已就绪、账本完好、账本里没有未落盘的条目（D4；已落盘未销账的不挡，§7.5 第 1.5 步）。 */
    private static boolean readyForNextBattle(ScenePlayer player, long wantedBattleId) {
        if (player.battle().recovery() != Recovery.READY) {
            return false;
        }
        BattleLedger ledger = player.battleLedger();
        if (ledger.invalidReason() != null) {
            // 与「恢复中 / 未落盘」同一个计数取值，靠这条日志区分（审计 STL-11）
            log.warn("备战被拒：战斗结算账本损坏（{}），等人工修复 player={} battle_id={}", ledger.invalidReason(),
                    Long.toUnsignedString(player.playerId()), Long.toUnsignedString(wantedBattleId));
            return false;
        }
        for (long battleId : ledger.battleIds()) {
            if (!BattleLedger.persistedHas(player.persistedState(), battleId)) {
                return false;
            }
        }
        return true;
    }

    private void onPrepareLocked(ScenePlayer player, BattleFreeze freeze, BattlePlayerSnapshot snapshot, String held, Throwable error,
                                 CompletableFuture<PrepareBattleResponse> reply) {
        long playerId = player.playerId();
        long battleId = freeze.battleId();
        boolean current = world.playerById(playerId) == player && player.battle().freeze() == freeze;
        if (!current) {
            // 实例已换或冻结已不是这个：占到了、或结局不明（出错 / 超时：脚本可能已执行、只是回复丢了）都尽力删一次
            // （match 认为这人没冻结成功，不会对他发取消；审计 FRZ-6 / RDS-10）。只删备战锁：这把锁若已被确认标成 F 就不是我们能删的
            if (error != null || "0".equals(held)) {
                onLogic(locks.deletePreparingIfMatch(playerId, battleId),
                        (r, e) -> logScriptError("删过期备战锁", playerId, battleId, e));
            }
            metrics.prepare(Prepare.STALE);
            log.info("备战写锁回来时实例已换 / 冻结已不是这个，回 1004 player={} battle_id={} 写锁结局={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId), error != null ? error.toString() : held);
            reply.complete(prepareResponse(ENTITY_NULL));
            return;
        }
        freeze.setLockPending(false);
        if (error != null) {
            // Redis 出错 / 超时：解冻，再尽力删一次（脚本可能已执行、只是回复丢了；只删备战锁，审计 FRZ-7）；残余见 §10.5
            clearFreeze(player, LockAction.KEEP_LOCK);
            onLogic(locks.deletePreparingIfMatch(playerId, battleId),
                    (r, e) -> logScriptError("备战失败后尽力删锁", playerId, battleId, e));
            metrics.prepare(Prepare.REDIS_ERROR);
            log.warn("备战写锁失败，回 1003 player={} battle_id={}: {}", Long.toUnsignedString(playerId), Long.toUnsignedString(battleId),
                    error.toString());
            reply.complete(prepareResponse(SERVICE_UNAVAILABLE));
            return;
        }
        if (!"0".equals(held)) {
            // 被别的局（或同局已 F）占着：独占（D3），锁不动
            clearFreeze(player, LockAction.KEEP_LOCK);
            metrics.prepare(Prepare.LOCK_HELD);
            log.info("备战被拒：战斗锁被占 player={} battle_id={} 锁上={}", Long.toUnsignedString(playerId), Long.toUnsignedString(battleId),
                    held);
            reply.complete(prepareResponse(FEATURE_UNAVAILABLE));
            return;
        }
        if (freeze.cancelRequested()) {
            // 写锁在途时收到的取消：写锁完成后才删（Redisson 可能把删锁排到 SET 之前，§7.6）
            clearFreeze(player, LockAction.DELETE_PREPARING);
            metrics.prepare(Prepare.CANCELLED);
            reply.complete(prepareResponse(FEATURE_UNAVAILABLE));
            return;
        }
        metrics.prepare(Prepare.OK);
        log.info("备战冻结 player={} battle_id={} node={} deadline={} prepare_deadline={}", Long.toUnsignedString(playerId),
                Long.toUnsignedString(battleId), freeze.battleNodeId(), freeze.deadlineMs(), freeze.prepareDeadlineMs());
        // 成功不带 error_message（有它 = 备战被拒；dev gather / robot 按「没有 error_message 且带快照」判成功）
        reply.complete(PrepareBattleResponse.newBuilder()
                .setSnapshot(snapshot)
                .setTableFingerprint(tables.fingerprint())
                .build());
    }

    private CompletableFuture<PrepareBattleResponse> prepareRejected(int tipId, Prepare result) {
        metrics.prepare(result);
        return CompletableFuture.completedFuture(prepareResponse(tipId));
    }

    private static PrepareBattleResponse prepareResponse(int tipId) {
        return PrepareBattleResponse.newBuilder().setErrorMessage(tip(tipId)).build();
    }

    // ================================================================== 取消（§7.6）

    /** 取消备战（逻辑线程）；需要删锁的分支等脚本结局回来再完成（取消的效果在应答时已生效）。 */
    public CompletableFuture<Void> cancel(CancelBattlePrepareRequest request) {
        long playerId = request.getPlayerId();
        long battleId = request.getBattleId();
        if (playerId == 0 || battleId == 0) {
            metrics.cancel(Cancel.MISMATCH);
            return CompletableFuture.completedFuture(null);
        }
        ScenePlayer player = world.playerById(playerId);
        if (player == null) {
            // 离线：一段 CANCEL_OFFLINE（基线两次往返）
            // 应答等脚本结局；回调出错 / 逻辑线程已停也照常完成（取消只管尽力，调用方不按传输失败重试，审计 STL-8）
            CompletableFuture<Void> done = new CompletableFuture<>();
            onLogic(locks.cancelOffline(playerId, battleId), (result, error) -> {
                try {
                    if (error != null) {
                        metrics.cancel(Cancel.OFFLINE_ERROR);
                        log.warn("离线取消备战的脚本失败 player={} battle_id={}: {}", Long.toUnsignedString(playerId),
                                Long.toUnsignedString(battleId), error.toString());
                    } else if (result != null && result == BattleRedis.PREPARING_DELETE_DONE) {
                        metrics.cancel(Cancel.OFFLINE_DELETED);
                    } else if (result != null && result == BattleRedis.PREPARING_DELETE_FIGHTING) {
                        metrics.cancel(Cancel.OFFLINE_REJECTED_FIGHTING);
                        log.info("离线取消被拒：这一局已确认开战 player={} battle_id={}", Long.toUnsignedString(playerId),
                                Long.toUnsignedString(battleId));
                    } else {
                        metrics.cancel(Cancel.OFFLINE_ABSENT);
                    }
                } finally {
                    done.complete(null);
                }
            }, aborted -> done.complete(null));
            return done;
        }
        BattleFreeze freeze = player.battle().freeze();
        if (freeze == null) {
            // 在线、没有冻结：忽略，不碰锁（B2）
            metrics.cancel(Cancel.IDEMPOTENT);
            return CompletableFuture.completedFuture(null);
        }
        if (freeze.battleId() != battleId) {
            metrics.cancel(Cancel.MISMATCH);
            return CompletableFuture.completedFuture(null);
        }
        if (freeze.phase() == Phase.FIGHTING) {
            // B1：确认已到 = 房间建成过，这时的取消是建房超时后的过期回滚
            metrics.cancel(Cancel.REJECTED_FIGHTING);
            log.info("取消备战被拒：已确认开战 player={} battle_id={}", Long.toUnsignedString(playerId), Long.toUnsignedString(battleId));
            return CompletableFuture.completedFuture(null);
        }
        if (freeze.lockPending()) {
            freeze.requestCancel();
            metrics.cancel(Cancel.DEFERRED);
            return CompletableFuture.completedFuture(null);
        }
        metrics.cancel(Cancel.CLEARED);
        log.info("取消备战，解冻 player={} battle_id={}", Long.toUnsignedString(playerId), Long.toUnsignedString(battleId));
        // 只删备战锁：这条删除若被排到紧随其后的确认之后，锁已标 F、迟到确认已重建 FIGHTING，不能再删（审计 FRZ-7）
        return clearFreeze(player, LockAction.DELETE_PREPARING);
    }

    // ================================================================== 确认（§7.7）

    /** 开局确认（逻辑线程）；只计数，不回结论。 */
    public void confirm(BattleConfirmedEvent event) {
        long playerId = event.getPlayerId();
        long battleId = event.getBattleId();
        long deadline = event.getDeadlineMs();
        if (playerId == 0 || battleId == 0) {
            metrics.confirm(Confirm.INVALID);
            log.error("确认事件参数非法，丢弃 player={} battle_id={}", Long.toUnsignedString(playerId), Long.toUnsignedString(battleId));
            return;
        }
        ScenePlayer player = world.playerById(playerId);
        long ttl = deadline == 0 ? 0 : BattleRedis.lockTtlSec(deadline, clock.epochMillis());
        if (player == null) {
            if (deadline == 0) {
                metrics.confirm(Confirm.OFFLINE_MISS);
                return;
            }
            onLogic(locks.confirm(playerId, battleId, deadline, ttl), (fields, error) ->
                    metrics.confirm(error != null ? Confirm.ERROR : fields == null ? Confirm.OFFLINE_MISS : Confirm.OFFLINE_EXTENDED));
            return;
        }
        if (player.frozen()) {
            // 交出在途：只续锁、不挂冻结；目标节点进场恢复时据锁重建 FIGHTING 并推 144（两种冻结互斥）
            onLogic(locks.confirm(playerId, battleId, deadline, ttl), (fields, error) ->
                    metrics.confirm(error != null ? Confirm.ERROR : fields == null ? Confirm.OFFLINE_MISS : Confirm.FROZEN_EXTENDED));
            return;
        }
        BattleFreeze freeze = player.battle().freeze();
        if (freeze != null) {
            if (freeze.battleId() != battleId) {
                metrics.confirm(Confirm.MISMATCH);
                return;
            }
            if (freeze.phase() == Phase.FIGHTING) {
                if (freeze.lockExtended()) {
                    metrics.confirm(Confirm.IDEMPOTENT);
                } else {
                    // D9：之前的续期没成功才再发
                    metrics.confirm(Confirm.REEXTENDED);
                    extendFighting(player, freeze);
                }
                return;
            }
            freeze.upgrade(deadline);
            metrics.confirm(Confirm.UPGRADED);
            log.info("确认开战：冻结升级 FIGHTING player={} battle_id={} deadline={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId), freeze.deadlineMs());
            extendFighting(player, freeze);
            // 冻结是在别的实例上建的（D7：按锁重建 / 沿用旧实例）：推 144，之后不再推
            pushReconnectOnce(player, freeze, HintTrigger.CONFIRM);
            return;
        }
        BattleLedger ledger = player.battleLedger();
        if (ledger.invalidReason() == null && ledger.has(battleId)) {
            // 这一局已应用、待销账：不重建，推进销账
            metrics.confirm(Confirm.LEDGER_HIT);
            writeOff(player, battleId, AckTrigger.APPLY);
            return;
        }
        onLogic(locks.confirm(playerId, battleId, deadline, ttl), (fields, error) -> onLateConfirm(player, battleId, deadline, fields,
                error));
    }

    /** FIGHTING 的条件续期（标 F、写正式期限、续 TTL）；成功置 {@code lockExtended}。 */
    private void extendFighting(ScenePlayer player, BattleFreeze freeze) {
        long playerId = player.playerId();
        long deadline = freeze.deadlineMs();
        onLogic(locks.confirm(playerId, freeze.battleId(), deadline, BattleRedis.lockTtlSec(deadline, clock.epochMillis())),
                (fields, error) -> {
                    if (error != null) {
                        metrics.confirm(Confirm.ERROR);
                        log.warn("确认续锁失败（下一次补发确认再续） player={} battle_id={}: {}", Long.toUnsignedString(playerId),
                                Long.toUnsignedString(freeze.battleId()), error.toString());
                        return;
                    }
                    if (fields == null) {
                        log.warn("确认续锁时锁已不是本局 player={} battle_id={}", Long.toUnsignedString(playerId),
                                Long.toUnsignedString(freeze.battleId()));
                        return;
                    }
                    if (player.battle().freeze() == freeze) {
                        freeze.setLockExtended(true);
                    }
                });
    }

    /**
     * 迟到确认（发出时在线、没有冻结）：一段 CONFIRM 已核对锁并标 F；回来后核对实例、不在交出冻结、账本没有这一局 → 挂 FIGHTING 并推 144（D10）。
     *
     * <p>回调时已经有了<b>同一局的 PREPARING 冻结</b>（确认在途期间，进场恢复按更早的快照重建出来的，审计 FRZ-5）→ 按正常确认升级，不丢弃：
     * 否则锁已是 F、内存却停在 PREPARING，要等下一次补发确认才追上（这一次可能已是最后一次补发）。
     *
     * <p>{@code deadline == 0}（事件没带期限）时这段 CONFIRM 只标了 F、没续期（脚本对 0 不写 d、不 EXPIRE）：{@code lockExtended} 不得置真，
     * 当场按锁里的期限补一次续期（审计 RDS-9；否则之后的补发全走零 Redis 的幂等分支，锁停在备战 TTL 提前过期）。
     */
    private void onLateConfirm(ScenePlayer player, long battleId, long deadline, Map<String, String> fields, Throwable error) {
        long playerId = player.playerId();
        if (error != null) {
            metrics.confirm(Confirm.ERROR);
            metrics.rebuild(RebuildReason.LATE_CONFIRM, RebuildResult.ERROR);
            log.warn("迟到确认的脚本失败 player={} battle_id={}: {}", Long.toUnsignedString(playerId), Long.toUnsignedString(battleId),
                    error.toString());
            return;
        }
        if (fields == null) {
            metrics.confirm(Confirm.MISMATCH);
            metrics.rebuild(RebuildReason.LATE_CONFIRM, RebuildResult.MISS);
            return;
        }
        if (world.playerById(playerId) != player || player.frozen()) {
            metrics.confirm(Confirm.IDEMPOTENT);
            metrics.rebuild(RebuildReason.LATE_CONFIRM, RebuildResult.MISS);
            return;
        }
        long lockDeadline = BattleRedis.parseUnsigned(fields.get(BattleRedis.FIELD_DEADLINE));
        BattleFreeze existing = player.battle().freeze();
        if (existing != null) {
            if (existing.battleId() == battleId && existing.phase() == Phase.PREPARING && !existing.lockPending()) {
                existing.upgrade(deadline != 0 ? deadline : lockDeadline);
                metrics.confirm(Confirm.UPGRADED);
                log.info("迟到确认回来时已有同局的备战冻结：升级 FIGHTING player={} battle_id={} deadline={}",
                        Long.toUnsignedString(playerId), Long.toUnsignedString(battleId), existing.deadlineMs());
                markExtendedByConfirm(player, existing, deadline);
                pushReconnectOnce(player, existing, HintTrigger.CONFIRM);
                return;
            }
            // 别的局的冻结，或同局已是 FIGHTING / 写锁在途：这条确认没有可做的事
            metrics.confirm(Confirm.IDEMPOTENT);
            metrics.rebuild(RebuildReason.LATE_CONFIRM, RebuildResult.MISS);
            return;
        }
        BattleLedger ledger = player.battleLedger();
        if ((ledger.invalidReason() == null && ledger.has(battleId)) || player.battle().writtenOff(battleId)) {
            // 这一局已应用（待销账），或本实例已为它发出过销账（这条 CONFIRM 的回复早于那次销账，是过期结果）：不重建，推进销账
            metrics.confirm(Confirm.LEDGER_HIT);
            metrics.rebuild(RebuildReason.LATE_CONFIRM, RebuildResult.LEDGER_HIT);
            writeOff(player, battleId, AckTrigger.APPLY);
            return;
        }
        BattleFreeze freeze = new BattleFreeze(battleId, (int) BattleRedis.parseUnsigned(fields.get(BattleRedis.FIELD_NODE)),
                Phase.FIGHTING, deadline != 0 ? deadline : lockDeadline,
                BattleRedis.parseUnsigned(fields.get(BattleRedis.FIELD_PREPARE_DEADLINE)), false);
        attachFreeze(player, freeze);
        metrics.confirm(Confirm.REBUILT);
        metrics.rebuild(RebuildReason.LATE_CONFIRM, RebuildResult.REBUILT);
        log.info("迟到确认按锁重建 FIGHTING 冻结 player={} battle_id={} deadline={}", Long.toUnsignedString(playerId),
                Long.toUnsignedString(battleId), freeze.deadlineMs());
        markExtendedByConfirm(player, freeze, deadline);
        pushReconnectOnce(player, freeze, HintTrigger.LATE_CONFIRM);
    }

    /**
     * 一段带事件期限的 CONFIRM 刚刚成功（锁已标 F、写了 d、续了 TTL）→ 续期已确认；事件期限为 0 时那段脚本没有续期，
     * 立即按冻结里的期限（= 锁的 d）补一次，成功才置 {@code lockExtended}（审计 RDS-9）。
     */
    private void markExtendedByConfirm(ScenePlayer player, BattleFreeze freeze, long eventDeadline) {
        if (eventDeadline != 0) {
            freeze.setLockExtended(true);
        } else {
            freeze.setLockExtended(false);
            extendFighting(player, freeze);
        }
    }

    // ================================================================== 进场恢复（§7.8）

    @Override
    public void onEntered(SceneWorld w, ScenePlayer player, ScenePlayer carried) {
        if (carried != null) {
            BattleFreeze old = carried.battle().freeze();
            if (old != null && !old.lockPending()) {
                // 第 0 步：同 epoch 沿用旧实例——复制成新对象（复位运行态标记）；写锁在途的不沿用（§7.8 第 0 步）
                BattleFreeze copy = old.carriedCopy();
                player.battle().setFreeze(copy);
                metrics.rebuild(RebuildReason.CARRIED, RebuildResult.REBUILT);
                if (!carried.session().equals(player.session())) {
                    // 会话换了（重连 / 顶号）才推；同会话的重复进场不推。PREPARING 永不推（B5）
                    pushReconnectOnce(player, copy, HintTrigger.CARRIED);
                }
            }
            // 旧实例的内存整个被沿用（账本随 persistentState 过来）：它「已为哪些局发出过销账」这份认识也一并沿用
            player.battle().inheritWrittenOff(carried.battle());
        }
        startRecovery(player);
    }

    /**
     * 交出没提交、原地解冻之后：<b>重跑一次完整的进场恢复</b>（第 1 步起）。规格原文只要求补「锁步骤」（§10.5：冻结期间到达的确认只续了锁、
     * 没挂冻结），这里有意做成超集（审计 FRZ-9 / GAT-13）：锁步骤本身就需要 ENTER_READ 的结果，那次读失败时若不落到 RETRY 就没有任何东西重试；
     * 重跑全流程还能把冻结期间被 DEFERRED 的结算当场补上、把冻结期间没 forget 的账本条目销掉。代价：一次往返内 {@code recovery = PENDING}
     * （备战 1006、结算 DEFERRED），读失败转 RETRY 由 reaper 重读。与还在途的更早一次恢复读靠代际号区分，只认最新一代。
     */
    @Override
    public void onUnfrozenInPlace(SceneWorld w, ScenePlayer player) {
        startRecovery(player);
    }

    /**
     * 第 1 步：恢复读（ENTER_READ），回来后在逻辑线程上处理。每次调用开新一代：更早发出、还没回来的那次读作废（回调按代际号丢弃），
     * 同一实例上不会有两份快照各跑一遍第 2–4 步（审计 FRZ-9）。
     */
    private void startRecovery(ScenePlayer player) {
        PlayerBattle battle = player.battle();
        battle.setRecovery(Recovery.PENDING);
        long generation = battle.nextRecoveryGeneration();
        long playerId = player.playerId();
        CompletableFuture<EnterRead> read;
        try {
            read = locks.enterRead(playerId);
        } catch (RuntimeException e) {
            // 端口约定出错以异常完成；同步抛出只做防御（钩子不得抛异常，也不得把恢复卡在 PENDING）
            read = CompletableFuture.failedFuture(e);
        }
        onLogic(read, (snapshot, error) -> onEnterRead(player, generation, snapshot, error));
    }

    private void onEnterRead(ScenePlayer player, long generation, EnterRead read, Throwable error) {
        long playerId = player.playerId();
        if (world.playerById(playerId) != player) {
            return;
        }
        PlayerBattle battle = player.battle();
        if (battle.recoveryGeneration() != generation) {
            // 之后又发起过恢复读（原地解冻 / reaper 重试）：这份快照更旧，交给最新一代裁决
            log.debug("进场恢复读回来时已有更新的一代在途，丢弃这份快照 player={} 代际 {} / {}", Long.toUnsignedString(playerId), generation,
                    battle.recoveryGeneration());
            return;
        }
        if (error != null) {
            // D20：读失败 → RETRY，由 reaper 重读；期间结算 DEFERRED、备战 1006
            battle.setRecovery(Recovery.RETRY);
            metrics.recovery(SceneBattleMetrics.Recovery.RETRY);
            log.warn("进场恢复读失败，等 reaper 重读 player={}: {}", Long.toUnsignedString(playerId), error.toString());
            return;
        }
        boolean deferred;
        try {
            deferred = recoverFrom(player, read);
        } catch (RuntimeException e) {
            // 第 2–4 步中途的意外异常：不能停在 PENDING（reaper 只重跑 RETRY，否则本实例备战一律 1006、结算一律 DEFERRED 直到重登）。
            // 已应用的局有账本兜着，重跑是幂等的；确定性的异常会让 reaper 每轮重跑并刷这条 ERROR，比永久 PENDING 可观测（审计 FRZ-2）
            battle.setRecovery(Recovery.RETRY);
            metrics.recovery(SceneBattleMetrics.Recovery.ERROR);
            log.error("进场恢复处理快照时出错，置 RETRY 等 reaper 重跑 player={}", Long.toUnsignedString(playerId), e);
            return;
        }
        // 第 5 步
        if (deferred) {
            battle.setRecovery(Recovery.RETRY);
            metrics.recovery(SceneBattleMetrics.Recovery.RETRY);
        } else {
            battle.setRecovery(Recovery.READY);
            metrics.recovery(SceneBattleMetrics.Recovery.READY);
        }
    }

    /**
     * 进场恢复的第 2–4 步（拿到快照之后）。
     *
     * @return 有延后的待结算记录（第 5 步据此置 RETRY）
     */
    private boolean recoverFrom(ScenePlayer player, EnterRead read) {
        long playerId = player.playerId();
        // 第 2 步：待结算记录按 battle_id 无符号升序逐个应用；坏字段只删该字段；遇延后即停（不能越过它应用更新的局）
        Set<Long> recordIds = new HashSet<>();
        Set<Long> corruptIds = new HashSet<>();
        List<BattleSettlementData> valid = new ArrayList<>();
        for (BattleRedis.SettlementField field : read.settlements()) {
            // 字段名只认规范的无符号十进制（否则 0 = 坏字段）；删坏字段用字段名的原始字节，不经 String 往返
            long fieldId = field.battleId();
            if (fieldId != 0) {
                recordIds.add(fieldId);
            }
            BattleSettlementData settlement = parseRecord(field.value());
            if (fieldId == 0 || settlement == null || settlement.getBattleId() != fieldId || settlement.getPlayerId() != playerId) {
                if (fieldId != 0) {
                    corruptIds.add(fieldId);
                }
                metrics.pendingCorrupt();
                log.error("待结算记录有坏字段，只删这一个字段 player={} field={}", Long.toUnsignedString(playerId), field.name());
                onLogic(locks.deleteSettlementField(playerId, field.rawName()),
                        (r, e) -> logScriptError("删坏的待结算字段", playerId, fieldId, e));
                continue;
            }
            valid.add(settlement);
        }
        valid.sort(Comparator.comparing(BattleSettlementData::getBattleId, Long::compareUnsigned));
        boolean deferred = false;
        // 本轮已经走过「应用并收尾」的局（APPLIED / ALREADY_APPLIED 都已销账或已压存盘；DEFERRED 的不销账）
        Set<Long> handled = new HashSet<>();
        Set<Long> concluded = new HashSet<>();
        for (BattleSettlementData settlement : valid) {
            Result result = applyAndFinish(player, settlement, SettlementPath.LOGIN, AckTrigger.LOGIN);
            handled.add(settlement.getBattleId());
            if (result.outcome() == Outcome.DEFERRED) {
                deferred = true;
                log.warn("进场恢复：待结算记录延后（{}），停在这一局、不越过它应用更新的局 player={} battle_id={}", result.reason(),
                        Long.toUnsignedString(playerId), Long.toUnsignedString(settlement.getBattleId()));
                break;
            }
            concluded.add(settlement.getBattleId());
        }
        // 第 3 步：锁（没有冻结时；第 2 步中途停下也照做）
        if (!player.inBattle()) {
            long lockBattle = read.lockBattleId();
            if (lockBattle != 0) {
                BattleLedger ledger = player.battleLedger();
                if (recordIds.contains(lockBattle)) {
                    // 这一局已经结束（结算记录就是证据）：不重建，否则把玩家冻进一场打完的战斗。三种情形分开计（审计 OPS-13）：
                    // 坏字段 → skipped_corrupt；本轮刚应用 / 账本命中（离线结算后登录的常态，已在第 2 步销账）→ ledger_hit；
                    // 延后或还没轮到 → skipped_pending
                    metrics.rebuild(RebuildReason.LOGIN, corruptIds.contains(lockBattle) ? RebuildResult.SKIPPED_CORRUPT
                            : concluded.contains(lockBattle) ? RebuildResult.LEDGER_HIT : RebuildResult.SKIPPED_PENDING);
                } else if ((ledger.invalidReason() == null && ledger.has(lockBattle)) || player.battle().writtenOff(lockBattle)) {
                    // 已应用待销账；或本实例已为它发出过销账（这份快照早于那次销账，锁其实已放）：不重建，再推一次销账
                    metrics.rebuild(RebuildReason.LOGIN, RebuildResult.LEDGER_HIT);
                    writeOff(player, lockBattle, AckTrigger.LOGIN);
                    handled.add(lockBattle);
                } else {
                    rebuildFromLock(player, lockBattle, read.lock(), read.lockTtlSec());
                }
            }
        }
        // 第 4 步：账本里其余条目逐个销账（加载自库的天然 durable）
        BattleLedger ledger = player.battleLedger();
        if (ledger.invalidReason() == null) {
            for (long battleId : ledger.battleIds()) {
                if (!handled.contains(battleId)) {
                    writeOff(player, battleId, AckTrigger.LOGIN);
                }
            }
        }
        return deferred;
    }

    /**
     * 按锁重建冻结（§1.2 的取值规则，{@link BattleFreeze#fromLock}），再 TOUCH 复核。是否推 144 <b>按重建那一刻的阶段</b>决定，
     * 不看回调时刻的阶段（审计 FRZ-4 / RDS-8：重建出 PREPARING、复核在途时确认到达，升级那一下已经推过，这里不得再推）：
     * <ul>
     *   <li>没命中 → 撤销（锁留着）；</li>
     *   <li>命中（含「锁上已是 F、入参是 P、什么都没改」的返回 2：按命中保留这个 PREPARING 冻结，不升级、不推，等下一次补发确认升级）→
     *       重建时是 FIGHTING 的推 144；</li>
     *   <li>复核出错 → 保守保留冻结；重建时是 FIGHTING 的<b>照样推 144</b>（冻结既然留着，重连提示就该给，同基线不等复核结果就推，审计 FRZ-3），
     *       并把 {@code lockExtended} 置回假，让下一次补发确认再续一次锁。</li>
     * </ul>
     * 同一个冻结对象上 144 至多一条（{@link #pushReconnectOnce}）。
     */
    private void rebuildFromLock(ScenePlayer player, long battleId, Map<String, String> lock, long ttlSec) {
        long playerId = player.playerId();
        if (player.frozen()) {
            // 进场后马上发的 63 已进入交出：不挂冻结（两种冻结互斥）；交出提交后目标节点重建，没提交则原地解冻后重跑进场恢复
            metrics.rebuild(RebuildReason.LOGIN, RebuildResult.MISS);
            return;
        }
        long now = clock.epochMillis();
        BattleFreeze freeze = BattleFreeze.fromLock(battleId, lock, ttlSec, now);
        boolean fighting = freeze.phase() == Phase.FIGHTING;
        attachFreeze(player, freeze);
        long touchTtl = BattleRedis.lockTtlSec(freeze.effectiveDeadlineMs(), now);
        onLogic(locks.touch(playerId, battleId, touchTtl, fighting ? BattleRedis.STATE_FIGHTING : BattleRedis.STATE_PREPARING,
                freeze.deadlineMs(), freeze.prepareDeadlineMs()), (hit, error) -> {
            if (world.playerById(playerId) != player || player.battle().freeze() != freeze) {
                return;
            }
            if (error != null) {
                // 复核失败：保守冻结（锁很可能还在），到期由 reaper 处理
                metrics.rebuild(RebuildReason.LOGIN, RebuildResult.ERROR);
                log.warn("进场按锁重建的复核脚本失败，保守保留冻结 player={} battle_id={}: {}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(battleId), error.toString());
                if (fighting) {
                    freeze.setLockExtended(false);
                    pushReconnectOnce(player, freeze, HintTrigger.LOGIN);
                }
                return;
            }
            if (hit == null || hit == BattleRedis.TOUCH_MISS) {
                clearFreeze(player, LockAction.KEEP_LOCK);
                metrics.rebuild(RebuildReason.LOGIN, RebuildResult.REVERTED);
                return;
            }
            metrics.rebuild(RebuildReason.LOGIN, RebuildResult.REBUILT);
            log.info("进场按锁重建冻结 player={} battle_id={} phase={} deadline={} 复核={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId), freeze.phase(), freeze.deadlineMs(), hit);
            if (fighting) {
                // PREPARING 永不推（房间还没建，补签会被判 BattleGone，B5）
                pushReconnectOnce(player, freeze, HintTrigger.LOGIN);
            }
        });
    }

    // ================================================================== reaper（§7.9）

    /** 每 reaper 间隔一次（逻辑线程）：备战 / 战斗到期、FIGHTING 判废前 rescue、账本排空、恢复重试；顺带推冻结人数。 */
    public void reap() {
        long now = clock.epochMillis();
        int preparing = 0;
        int fighting = 0;
        for (ScenePlayer player : world.playersSnapshot()) {
            if (world.playerById(player.playerId()) != player) {
                continue;
            }
            try {
                BattleFreeze freeze = player.battle().freeze();
                if (freeze != null && freeze.phase() == Phase.PREPARING) {
                    if (now > freeze.effectiveDeadlineMs()) {
                        // 只摘冻结、锁留到 TTL（给迟到的确认留重建余地）
                        clearFreeze(player, LockAction.KEEP_LOCK);
                        metrics.freezeExpired(SceneBattleMetrics.Phase.PREPARING);
                        log.info("备战到期，解冻（锁保留到 TTL） player={} battle_id={}", Long.toUnsignedString(player.playerId()),
                                Long.toUnsignedString(freeze.battleId()));
                    } else {
                        preparing++;
                    }
                } else if (freeze != null) {
                    fighting++;
                    reapFighting(player, freeze, now);
                }
                Recovery recovery = player.battle().recovery();
                if (recovery == Recovery.RETRY) {
                    // 只重启恢复、本轮不排空账本（审计 FRZ-1）：销账与恢复读背靠背发出时，两条脚本在 Redis 上的次序、两个回调的次序都没有保证，
                    // 快照早于销账而回调晚于 forget 就会把已销账的局再应用一遍。恢复读回来后第 2、4 步自己会销账
                    startRecovery(player);
                } else if (recovery == Recovery.READY) {
                    BattleLedger ledger = player.battleLedger();
                    if (ledger.invalidReason() == null) {
                        for (long battleId : ledger.battleIds()) {
                            writeOff(player, battleId, AckTrigger.REAPER);
                        }
                    }
                }
                // PENDING：恢复读在途，账本由它回来后的第 4 步销
            } catch (RuntimeException e) {
                log.error("reaper 处理一名玩家出错（继续下一个） player={}", Long.toUnsignedString(player.playerId()), e);
            }
        }
        metrics.frozen(preparing, fighting);
    }

    private void reapFighting(ScenePlayer player, BattleFreeze freeze, long now) {
        long deadline = freeze.deadlineMs();
        if (now > deadline + BattleRedis.LOCK_EXTRA_TTL_SEC * 1000) {
            // 期限 + 60 s 仍读不到本局记录（锁也已过期）：不读、直接判废
            clearFreeze(player, LockAction.DELETE_IF_MATCH);
            metrics.rescue(Rescue.GAVE_UP);
            metrics.freezeExpired(SceneBattleMetrics.Phase.FIGHTING);
            log.warn("战斗期限 + 60 s 仍未结算，判废 player={} battle_id={}", Long.toUnsignedString(player.playerId()),
                    Long.toUnsignedString(freeze.battleId()));
            return;
        }
        if (now <= deadline + BattleRedis.FIGHTING_EXPIRY_GRACE.toMillis() || freeze.rescuing()) {
            return;
        }
        // D21：过了宽限，判废前先读本局记录
        freeze.setRescuing(true);
        long playerId = player.playerId();
        onLogic(locks.readSettlement(playerId, freeze.battleId()), (bytes, error) -> onRescueRead(player, freeze, bytes, error));
    }

    private void onRescueRead(ScenePlayer player, BattleFreeze freeze, byte[] bytes, Throwable error) {
        long playerId = player.playerId();
        if (world.playerById(playerId) != player || player.battle().freeze() != freeze) {
            return;
        }
        freeze.setRescuing(false);
        if (error != null) {
            // 读出错不判废（判废删锁后随后到达的重投会被 DISCARDED 并销账），下一轮再读
            metrics.rescue(Rescue.ERROR);
            log.warn("rescue 读本局记录出错，下一轮再读 player={} battle_id={}: {}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(freeze.battleId()), error.toString());
            return;
        }
        BattleSettlementData settlement = bytes == null ? null : parseRecord(bytes);
        if (settlement == null || settlement.getBattleId() != freeze.battleId() || settlement.getPlayerId() != playerId) {
            voidFighting(player, freeze, Rescue.MISS);
            return;
        }
        if (player.battle().recovery() != Recovery.READY || player.frozen()) {
            metrics.settlement(SettlementPath.RESCUE, player.frozen() ? SettlementResult.DEFERRED_FROZEN
                    : SettlementResult.DEFERRED_RECOVERING);
            voidFighting(player, freeze, Rescue.DEFERRED);
            return;
        }
        Result result = applyAndFinish(player, settlement, SettlementPath.RESCUE, AckTrigger.APPLY);
        switch (result.outcome()) {
            case APPLIED -> metrics.rescue(Rescue.APPLIED);
            case ALREADY_APPLIED -> metrics.rescue(Rescue.ALREADY_APPLIED);
            // DEFERRED：记录留在 Redis，由进场恢复按局序补应用
            case DEFERRED -> voidFighting(player, freeze, Rescue.DEFERRED);
            case DISCARDED -> voidFighting(player, freeze, Rescue.MISS);
        }
    }

    private void voidFighting(ScenePlayer player, BattleFreeze freeze, Rescue reason) {
        if (player.battle().freeze() != freeze) {
            return;
        }
        clearFreeze(player, LockAction.DELETE_IF_MATCH);
        metrics.rescue(reason);
        metrics.freezeExpired(SceneBattleMetrics.Phase.FIGHTING);
        log.info("战斗期限 + 宽限已过、没有可应用的本局记录（{}），判废 player={} battle_id={}", reason,
                Long.toUnsignedString(player.playerId()), Long.toUnsignedString(freeze.battleId()));
    }

    // ================================================================== 结算到达（§7.10）

    /**
     * 结算投递（逻辑线程）。实例核对与解析在提供方（Dubbo 线程）上已做；这里从第 2 步的语义检查起。
     *
     * @param envelopePlayerId 信封里的 player_id（必须与结算里的相同）
     */
    public CompletableFuture<SceneBattleReply> deliver(long envelopePlayerId, BattleSettlementData settlement) {
        long playerId = settlement.getPlayerId();
        long battleId = settlement.getBattleId();
        if (playerId == 0 || battleId == 0 || envelopePlayerId != playerId) {
            metrics.settlement(SettlementPath.ONLINE, SettlementResult.DISCARDED_INVALID);
            log.error("结算投递非法，丢弃（不销账） envelope_player={} player={} battle_id={}", Long.toUnsignedString(envelopePlayerId),
                    Long.toUnsignedString(playerId), Long.toUnsignedString(battleId));
            return completed(reply(SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED));
        }
        ScenePlayer player = world.playerById(playerId);
        if (player == null) {
            // Java 没有「退出中」：离场同步移除、写回已带账本；battle 下一轮按位置重新解析（离线时由 ACK_IF_SUPERSEDED 判取代，D17）
            metrics.settlement(SettlementPath.ONLINE, SettlementResult.NOT_HERE);
            return completed(reply(SceneBattleStatus.SCENE_BATTLE_NOT_HERE, SettlementDisposition.SETTLEMENT_DISPOSITION_UNSPECIFIED));
        }
        if (player.battle().recovery() != Recovery.READY) {
            metrics.settlement(SettlementPath.ONLINE, SettlementResult.DEFERRED_RECOVERING);
            return completed(deferred());
        }
        if (player.frozen()) {
            metrics.settlement(SettlementPath.ONLINE, SettlementResult.DEFERRED_FROZEN);
            return completed(deferred());
        }
        BattleFreeze freeze = player.battle().freeze();
        if (freeze != null && freeze.battleId() != battleId) {
            // 玩家已在下一局（J12）：丢弃并销账
            metrics.settlement(SettlementPath.ONLINE, SettlementResult.DISCARDED_MISMATCH);
            log.warn("结算的局与当前冻结不符，丢弃并销账 player={} battle_id={} 冻结={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId), Long.toUnsignedString(freeze.battleId()));
            writeOff(player, battleId, AckTrigger.DISCARD);
            return completed(reply(SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED));
        }
        if (freeze != null) {
            return completed(toReply(applyAndFinish(player, settlement, SettlementPath.ONLINE, AckTrigger.APPLY)));
        }
        // 没有冻结：按锁（第 7 步）。应答等读锁结局；回调出错 / 逻辑线程已停 → 应答异常完成（= 结局未知，battle 下一轮重投会命中账本；
        // 不异常完成的话提供方的在途名额永不归还，审计 STL-8）
        CompletableFuture<SceneBattleReply> reply = new CompletableFuture<>();
        onLogic(locks.readLockBattleId(playerId), (lockBattle, error) -> {
            if (world.playerById(playerId) != player || player.battle().recovery() != Recovery.READY || player.frozen()) {
                metrics.settlement(SettlementPath.BY_LOCK, player.frozen() ? SettlementResult.DEFERRED_FROZEN
                        : SettlementResult.DEFERRED_RECOVERING);
                reply.complete(deferred());
                return;
            }
            if (error != null) {
                metrics.settlement(SettlementPath.BY_LOCK, SettlementResult.DEFERRED_LOCK_READ);
                log.warn("结算到达时读锁失败，延后 player={} battle_id={}: {}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(battleId), error.toString());
                reply.complete(deferred());
                return;
            }
            // 判定次序同规格 §7.10 第 7 步与基线（pb.cpp:1881-1904，审计 STL-9）：先看锁，再看回调时刻的冻结
            if (lockBattle == null || lockBattle != battleId) {
                // 锁不在或易主：这一局已作废（B14），丢弃并销账（回调时已有别的局的冻结也照销：ACK 只删 X 的记录，b ≠ X 的锁不动）
                metrics.settlement(SettlementPath.BY_LOCK, SettlementResult.DISCARDED_VOID);
                log.info("结算到达时没有冻结且锁不是本局（已作废），丢弃并销账 player={} battle_id={} 锁={}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(battleId), lockBattle == null ? "?" : Long.toUnsignedString(lockBattle));
                writeOff(player, battleId, AckTrigger.DISCARD);
                reply.complete(reply(SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED));
                return;
            }
            BattleFreeze now = player.battle().freeze();
            if (now != null && now.battleId() != battleId) {
                // 锁读到的是本局，但回调时已有别的局的冻结：丢弃，不销账（同 pb.cpp:1898-1904）
                metrics.settlement(SettlementPath.BY_LOCK, SettlementResult.DISCARDED_MISMATCH);
                reply.complete(reply(SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED));
                return;
            }
            reply.complete(toReply(applyAndFinish(player, settlement, now != null ? SettlementPath.ONLINE : SettlementPath.BY_LOCK,
                    AckTrigger.APPLY)));
        }, reply::completeExceptionally);
        return reply;
    }

    /**
     * 应用并收尾（§7.10 第 9 步）：APPLIED / ALREADY_APPLIED → HOLD(X) → 冻结是这一局就解冻（锁留着）→ 销账 → 本次新应用才推 150；
     * DEFERRED 原样（冻结保留）；DISCARDED（归属不符）不销账。
     *
     * <p><b>全部应用路径（online / by_lock / login / rescue）的唯一收口</b>，所以过期读在这里统一兜住（审计 FRZ-1）：本实例已为这一局发出过销账
     * （{@link PlayerBattle#writtenOff}）→ 带它回来的那份读（恢复读的快照、读锁、rescue 读）早于那次销账，或者这一局是本实例决定丢弃的——
     * 账本可能已经 forget，不能再靠账本去重——<b>不调应用</b>，按 ALREADY_APPLIED 收尾（不推 150；再销一次账把可能残留的记录 / 锁清掉）。
     */
    private Result applyAndFinish(ScenePlayer player, BattleSettlementData settlement, SettlementPath path, AckTrigger trigger) {
        long playerId = player.playerId();
        long battleId = settlement.getBattleId();
        Result result;
        if (player.battle().writtenOff(battleId) && !player.battleLedger().has(battleId)) {
            log.warn("已销账的局又被读到（过期快照 / 迟到的读），按已应用处理、不再应用 player={} battle_id={} path={}",
                    Long.toUnsignedString(playerId), Long.toUnsignedString(battleId), path);
            result = Result.ALREADY;
        } else {
            result = settlements.apply(world, player, settlement);
        }
        metrics.settlement(path, result.reason());
        if (result.outcome() == Outcome.APPLIED || result.outcome() == Outcome.ALREADY_APPLIED) {
            // 锁留到落盘：先 HOLD 再摘冻结；ACK 与放锁同一段 Lua
            onLogic(locks.hold(playerId, battleId, BattleRedis.LOCK_HOLD_AFTER_APPLY_SEC),
                    (r, e) -> logScriptError("结算后续锁", playerId, battleId, e));
            BattleFreeze freeze = player.battle().freeze();
            if (freeze != null && freeze.battleId() == battleId) {
                clearFreeze(player, LockAction.KEEP_LOCK);
            }
            writeOff(player, battleId, trigger);
            if (result.outcome() == Outcome.APPLIED) {
                // 184（有宝宝条目时，应用里已推）→ 150，同一条 gate 链路 FIFO；账本命中的不推（B9）
                world.sendTo(player, push(battleEndId, BattleEndS2C.newBuilder()
                        .setBattleId(battleId)
                        .setOutcome(settlement.getOutcome())
                        .setSettlement(settlement)
                        .build()));
            }
        }
        return result;
    }

    private static SceneBattleReply toReply(Result result) {
        return switch (result.outcome()) {
            case APPLIED -> reply(SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_APPLIED);
            case ALREADY_APPLIED -> reply(SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_ALREADY_APPLIED);
            case DEFERRED -> deferred();
            case DISCARDED -> reply(SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED);
        };
    }

    private static SceneBattleReply deferred() {
        return reply(SceneBattleStatus.SCENE_BATTLE_DEFERRED, SettlementDisposition.SETTLEMENT_DISPOSITION_UNSPECIFIED);
    }

    private static SceneBattleReply reply(SceneBattleStatus status, SettlementDisposition disposition) {
        return SceneBattleReply.newBuilder().setStatus(status).setSettlement(disposition).build();
    }

    // ================================================================== 销账（§7.12）

    /**
     * 销账唯一入口（基线 {@code AckSettlementPending} 的 1、2 条）：账本没有 X → 立即 ACK（判废或重复投递）；有且已落盘 → ACK；
     * 有但没落盘 → 压一次存盘，等落盘回调（{@link #onPersisted}）或 reaper 再判。
     */
    void writeOff(ScenePlayer player, long battleId, AckTrigger trigger) {
        BattleLedger ledger = player.battleLedger();
        boolean recorded = ledger.invalidReason() == null && ledger.has(battleId);
        if (recorded && !BattleLedger.persistedHas(player.persistedState(), battleId)) {
            SceneWorld.SaveRequest save = world.requestSave(player);
            metrics.ack(trigger, AckResult.DEFERRED);
            if (save == SceneWorld.SaveRequest.UNCHANGED) {
                // 没写 = 内存与落库快照相同，条目既然在内存里就必然也在快照里：比对或快照维护有 bug
                log.error("战斗账本条目不在落库快照里，但存盘因与快照相同被跳过 player={} battle_id={}",
                        Long.toUnsignedString(player.playerId()), Long.toUnsignedString(battleId));
            }
            return;
        }
        ack(player, battleId, trigger);
    }

    private void ack(ScenePlayer player, long battleId, AckTrigger trigger) {
        long playerId = player.playerId();
        // 发出销账的那一刻就记下（不等结局）：此后任何一条读把这一局带回来都不再应用（见 applyAndFinish）。脚本失败也不撤：
        // 账本里有的条目照旧由账本挡着并重试销账；账本里没有的是本实例决定丢弃的局，同样不该再应用
        player.battle().markWrittenOff(battleId);
        onLogic(locks.ack(playerId, battleId), (bits, error) -> {
            if (error != null) {
                // 账本条目保留，落盘回调 / reaper / 进场重试
                metrics.ack(trigger, AckResult.ERROR);
                log.warn("销账脚本失败（账本条目保留，稍后重试） player={} battle_id={}: {}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(battleId), error.toString());
                return;
            }
            long r = bits == null ? 0 : bits;
            metrics.ack(trigger, r == 0 ? AckResult.NOT_OURS : AckResult.RELEASED);
            if (world.playerById(playerId) != player) {
                return;
            }
            if (!player.frozen()) {
                // FREEZING 时不改账本（冻结中玩家的可变状态必须与冻结快照一致）：条目随冻结快照交给下一个持有者
                player.battleLedger().forget(battleId);
            }
            if ((r & 2) != 0 && !player.inBattle()) {
                // 锁确实被这次删掉：补一次组队跟随（次序由 future 链保证，不依赖连接 FIFO）
                teamFollow.onBattleFreezeCleared(world, player);
            }
        });
    }

    @Override
    public void onPersisted(SceneWorld w, ScenePlayer player) {
        BattleLedger ledger = player.battleLedger();
        if (ledger.invalidReason() != null || ledger.size() == 0) {
            return;
        }
        boolean pending = false;
        for (long battleId : ledger.battleIds()) {
            if (BattleLedger.persistedHas(player.persistedState(), battleId)) {
                ack(player, battleId, AckTrigger.PERSISTED);
            } else {
                pending = true;
            }
        }
        if (pending) {
            // 还有不 durable 的（在这次快照之后才登记的）：再压一次；只在 SAVED 时链式重存，FAILED 交给 reaper
            world.requestSave(player);
        }
    }

    // ================================================================== 解冻与工具

    /**
     * 解冻的唯一入口（基线 {@code RemoveInBattleComp} / {@code ClearBattleFreeze}）：摘掉冻结；{@code DELETE_PREPARING}（只删备战锁）/
     * {@code DELETE_IF_MATCH}（reaper 判废，不看 s）→ 发条件删锁，<b>删完</b>（回到逻辑线程、实例未换、仍无冻结）补一次组队跟随；
     * {@code KEEP_LOCK} → 立即补（锁还在，跟随链读到锁会放弃，锁真正放掉时由销账回调补）。
     *
     * @return 删锁完成（KEEP_LOCK / 本来就没有冻结时立即完成）。删锁回调出错、逻辑线程已停也照常完成——解冻在返回前已经生效（审计 STL-8）
     */
    CompletableFuture<Void> clearFreeze(ScenePlayer player, LockAction action) {
        BattleFreeze freeze = player.battle().freeze();
        if (freeze == null) {
            return CompletableFuture.completedFuture(null);
        }
        player.battle().setFreeze(null);
        if (action == LockAction.KEEP_LOCK) {
            teamFollow.onBattleFreezeCleared(world, player);
            return CompletableFuture.completedFuture(null);
        }
        long playerId = player.playerId();
        long battleId = freeze.battleId();
        CompletableFuture<Void> done = new CompletableFuture<>();
        CompletableFuture<Long> deletion = action == LockAction.DELETE_PREPARING
                ? locks.deletePreparingIfMatch(playerId, battleId) : locks.deleteIfMatch(playerId, battleId);
        onLogic(deletion, (r, error) -> {
            try {
                logScriptError("解冻删锁", playerId, battleId, error);
                if (error == null && action == LockAction.DELETE_PREPARING && r != null && r == BattleRedis.PREPARING_DELETE_FIGHTING) {
                    // 删除排到了确认之后：锁已标 F（迟到确认会据此重建 FIGHTING），留着
                    log.info("解冻时锁已被确认标成 F，保留 player={} battle_id={}", Long.toUnsignedString(playerId),
                            Long.toUnsignedString(battleId));
                }
                if (world.playerById(playerId) == player && !player.inBattle()) {
                    teamFollow.onBattleFreezeCleared(world, player);
                }
            } finally {
                done.complete(null);
            }
        }, aborted -> done.complete(null));
        return done;
    }

    /** 挂上一个冻结（迟到确认 / 进场恢复）：同时停步（移动上行此后被丢弃，旧速度不能继续外推）。 */
    private void attachFreeze(ScenePlayer player, BattleFreeze freeze) {
        player.battle().setFreeze(freeze);
        if (!player.velocity().isOrigin()) {
            world.haltForBattle(player);
        }
    }

    /**
     * 这个冻结对象的 144 还没推过就推一次、并记下已推（{@code preparedHere}）。PREPARING 永不推（房间还没建，补签会被判 BattleGone，B5）。
     * 推 144 的全部路径（确认升级、迟到确认、进场重建的复核、沿用旧实例）都经这里，同一个冻结对象上恰好一次（§13.2「推且只推一次」）。
     */
    private void pushReconnectOnce(ScenePlayer player, BattleFreeze freeze, HintTrigger trigger) {
        if (freeze.phase() != Phase.FIGHTING || freeze.preparedHere()) {
            return;
        }
        freeze.setPreparedHere(true);
        pushReconnect(player, freeze.battleId(), trigger);
    }

    private void pushReconnect(ScenePlayer player, long battleId, HintTrigger trigger) {
        metrics.reconnectHint(trigger);
        world.sendTo(player, push(reconnectHintId, BattleReconnectS2C.newBuilder().setBattleId(battleId).build()));
        log.info("推 144 战斗重连提示 player={} battle_id={} trigger={}", Long.toUnsignedString(player.playerId()),
                Long.toUnsignedString(battleId), trigger);
    }

    /** 解析待结算记录（{@code BattleSettlementEvent} 字节）；坏字节为 null。 */
    static BattleSettlementData parseRecord(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        try {
            BattleSettlementEvent event = BattleSettlementEvent.parseFrom(bytes);
            return event.hasSettlement() ? event.getSettlement() : null;
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }

    /** 异步结局投递回逻辑线程（逻辑线程已停时丢弃）；没有挂着的应答 future 的调用用它。 */
    private <T> void onLogic(CompletableFuture<T> future, BiConsumer<T, Throwable> callback) {
        onLogic(future, callback, null);
    }

    /**
     * 异步结局投递回逻辑线程。
     *
     * @param onAborted 回调<b>没能跑完</b>时调用，让挂在这次调用上的应答 future 有个结局（审计 FRZ-2 / STL-8）：回调抛了异常（在逻辑线程上调，
     *                  入参是那个异常），或逻辑线程拒绝投递（停服；在完成 future 的线程上调，<b>不得碰玩家状态</b>）。只许完成 future；可为 null
     */
    private <T> void onLogic(CompletableFuture<T> future, BiConsumer<T, Throwable> callback, Consumer<Throwable> onAborted) {
        future.whenComplete((value, error) -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            try {
                logic.execute(() -> {
                    try {
                        callback.accept(value, cause);
                    } catch (RuntimeException e) {
                        log.error("回合制战斗的异步回调出错", e);
                        aborted(onAborted, e);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.debug("逻辑线程已停止，丢弃回合制战斗的异步结局");
                aborted(onAborted, e);
            }
        });
    }

    private static void aborted(Consumer<Throwable> onAborted, Throwable cause) {
        if (onAborted == null) {
            return;
        }
        try {
            onAborted.accept(cause);
        } catch (RuntimeException e) {
            log.error("回合制战斗异步回调的收尾出错", e);
        }
    }

    private static void logScriptError(String what, long playerId, long battleId, Throwable error) {
        if (error != null) {
            log.warn("{}失败 player={} battle_id={}: {}", what, Long.toUnsignedString(playerId), Long.toUnsignedString(battleId),
                    error.toString());
        }
    }

    private static <T> CompletableFuture<T> completed(T value) {
        return CompletableFuture.completedFuture(value);
    }
}
