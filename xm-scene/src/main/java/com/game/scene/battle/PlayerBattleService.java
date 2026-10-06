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
import com.game.scene.world.SwitchPhase;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * scene 侧回合制战斗冻结与结算的全部流程（scene-battle-spec §7.4–§7.12；基线 {@code player_battle.cpp}）：备战 / 取消 / 确认 / 进场恢复 /
 * reaper / 结算到达 / 销账。冻结状态的唯一改写方（{@link #clearFreeze} 是解冻的唯一入口）。
 *
 * <p><b>线程</b>：全部方法只在场景逻辑线程上调用；Redis 脚本一律异步，结果经 {@code logic} 投递回逻辑线程后先核对
 * 「{@code world.playerById(pid) == player}，且冻结对象没变」再动状态（比基线 {@code GuidForLog} 更强，与 5.2 的 {@code switching() == sw} 同一纪律）。
 * 次序不靠连接 FIFO（§1.8）：要么同一段 Lua，要么 future 链。
 *
 * <p><b>与 5.2 互斥</b>（§7.13）：备战拒绝 {@code switchPhase ≠ NONE}；挂冻结的路径（迟到确认、进场恢复）先看 FREEZING；结算在 FREEZING 时回 DEFERRED。
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
        /** 条件删锁，删完（回到逻辑线程、实例未换）再补一次组队跟随。 */
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
        if (player.switchPhase() != SwitchPhase.NONE) {
            // FREEZING 对应基线 PlayerFrozenComp；RESOLVING 是 D4（与 5.2 互斥）
            return prepareRejected(FEATURE_UNAVAILABLE, Prepare.SWITCHING);
        }
        if (player.inBattle()) {
            return prepareRejected(FEATURE_UNAVAILABLE, Prepare.IN_BATTLE);
        }
        if (!readyForNextBattle(player)) {
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
        onLogic(locks.prepareLock(playerId, battleId, request.getBattleNodeId(), deadline, prepareDeadline, ttl),
                (held, error) -> onPrepareLocked(player, freeze, snapshot, held, error, reply));
        return reply;
    }

    /** 恢复已就绪、账本完好、账本里没有未落盘的条目（D4；已落盘未销账的不挡，§7.5 第 1.5 步）。 */
    private static boolean readyForNextBattle(ScenePlayer player) {
        if (player.battle().recovery() != Recovery.READY) {
            return false;
        }
        BattleLedger ledger = player.battleLedger();
        if (ledger.invalidReason() != null) {
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
            // 实例已换或冻结已不是这个：占到了就删（match 认为这人没冻结成功，不会对他发取消）
            if (error == null && "0".equals(held)) {
                onLogic(locks.deleteIfMatch(playerId, battleId), (r, e) -> logScriptError("删过期备战锁", playerId, battleId, e));
            }
            metrics.prepare(Prepare.STALE);
            log.info("备战写锁回来时实例已换 / 冻结已不是这个，回 1004 player={} battle_id={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId));
            reply.complete(prepareResponse(ENTITY_NULL));
            return;
        }
        freeze.setLockPending(false);
        if (error != null) {
            // Redis 出错 / 超时：解冻，再尽力删一次（脚本可能已执行、只是回复丢了）；残余见 §10.5
            clearFreeze(player, LockAction.KEEP_LOCK);
            onLogic(locks.deleteIfMatch(playerId, battleId), (r, e) -> logScriptError("备战失败后尽力删锁", playerId, battleId, e));
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
            clearFreeze(player, LockAction.DELETE_IF_MATCH);
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
            CompletableFuture<Void> done = new CompletableFuture<>();
            onLogic(locks.cancelOffline(playerId, battleId), (result, error) -> {
                if (error != null) {
                    metrics.cancel(Cancel.OFFLINE_ERROR);
                    log.warn("离线取消备战的脚本失败 player={} battle_id={}: {}", Long.toUnsignedString(playerId),
                            Long.toUnsignedString(battleId), error.toString());
                } else if (result == 1) {
                    metrics.cancel(Cancel.OFFLINE_DELETED);
                } else if (result == 2) {
                    metrics.cancel(Cancel.OFFLINE_REJECTED_FIGHTING);
                    log.info("离线取消被拒：这一局已确认开战 player={} battle_id={}", Long.toUnsignedString(playerId),
                            Long.toUnsignedString(battleId));
                } else {
                    metrics.cancel(Cancel.OFFLINE_ABSENT);
                }
                done.complete(null);
            });
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
        return clearFreeze(player, LockAction.DELETE_IF_MATCH);
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
            if (!freeze.preparedHere()) {
                // 冻结是在别的实例上建的（D7）：推 144，之后不再推
                pushReconnect(player, battleId, HintTrigger.CONFIRM);
                freeze.setPreparedHere(true);
            }
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

    /** 迟到确认（在线、没有冻结）：一段 CONFIRM 已核对锁并标 F；回来后核对实例、仍无冻结、账本仍无、不在交出冻结 → 挂 FIGHTING 并推 144（D10）。 */
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
        if (world.playerById(playerId) != player || player.inBattle() || player.frozen()) {
            metrics.confirm(Confirm.IDEMPOTENT);
            metrics.rebuild(RebuildReason.LATE_CONFIRM, RebuildResult.MISS);
            return;
        }
        BattleLedger ledger = player.battleLedger();
        if (ledger.invalidReason() == null && ledger.has(battleId)) {
            metrics.confirm(Confirm.LEDGER_HIT);
            metrics.rebuild(RebuildReason.LATE_CONFIRM, RebuildResult.LEDGER_HIT);
            writeOff(player, battleId, AckTrigger.APPLY);
            return;
        }
        long lockDeadline = BattleRedis.parseUnsigned(fields.get(BattleRedis.FIELD_DEADLINE));
        BattleFreeze freeze = new BattleFreeze(battleId, (int) BattleRedis.parseUnsigned(fields.get(BattleRedis.FIELD_NODE)),
                Phase.FIGHTING, deadline != 0 ? deadline : lockDeadline,
                BattleRedis.parseUnsigned(fields.get(BattleRedis.FIELD_PREPARE_DEADLINE)), false);
        freeze.setLockExtended(true);
        attachFreeze(player, freeze);
        metrics.confirm(Confirm.REBUILT);
        metrics.rebuild(RebuildReason.LATE_CONFIRM, RebuildResult.REBUILT);
        log.info("迟到确认按锁重建 FIGHTING 冻结 player={} battle_id={} deadline={}", Long.toUnsignedString(playerId),
                Long.toUnsignedString(battleId), freeze.deadlineMs());
        pushReconnect(player, battleId, HintTrigger.LATE_CONFIRM);
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
                if (copy.phase() == Phase.FIGHTING && !carried.session().equals(player.session())) {
                    pushReconnect(player, copy.battleId(), HintTrigger.CARRIED);
                }
            }
        }
        startRecovery(player);
    }

    @Override
    public void onUnfrozenInPlace(SceneWorld w, ScenePlayer player) {
        // 交出冻结期间到达的确认只续了锁（§7.7）：原地解冻后补跑一次进场恢复（锁步骤重建冻结，§10.5）
        startRecovery(player);
    }

    /** 第 1 步：恢复读（ENTER_READ），回来后在逻辑线程上处理。 */
    private void startRecovery(ScenePlayer player) {
        player.battle().setRecovery(Recovery.PENDING);
        long playerId = player.playerId();
        onLogic(locks.enterRead(playerId), (read, error) -> onEnterRead(player, read, error));
    }

    private void onEnterRead(ScenePlayer player, EnterRead read, Throwable error) {
        long playerId = player.playerId();
        if (world.playerById(playerId) != player) {
            return;
        }
        if (error != null) {
            // D20：读失败 → RETRY，由 reaper 重读；期间结算 DEFERRED、备战 1006
            player.battle().setRecovery(Recovery.RETRY);
            metrics.recovery(SceneBattleMetrics.Recovery.RETRY);
            log.warn("进场恢复读失败，等 reaper 重读 player={}: {}", Long.toUnsignedString(playerId), error.toString());
            return;
        }
        // 第 2 步：待结算记录按 battle_id 无符号升序逐个应用；坏字段只删该字段；遇延后即停（不能越过它应用更新的局）
        Set<Long> recordIds = new HashSet<>();
        List<BattleSettlementData> valid = new ArrayList<>();
        for (Map.Entry<String, byte[]> field : read.settlements().entrySet()) {
            long fieldId = BattleRedis.parseUnsigned(field.getKey());
            if (fieldId != 0) {
                recordIds.add(fieldId);
            }
            BattleSettlementData settlement = parseRecord(field.getValue());
            if (fieldId == 0 || settlement == null || settlement.getBattleId() != fieldId || settlement.getPlayerId() != playerId) {
                metrics.pendingCorrupt();
                log.error("待结算记录有坏字段，只删这一个字段 player={} field={}", Long.toUnsignedString(playerId), field.getKey());
                String name = field.getKey();
                onLogic(locks.deleteSettlementField(playerId, name), (r, e) -> logScriptError("删坏的待结算字段", playerId, fieldId, e));
                continue;
            }
            valid.add(settlement);
        }
        valid.sort(Comparator.comparing(BattleSettlementData::getBattleId, Long::compareUnsigned));
        boolean deferred = false;
        Set<Long> handled = new HashSet<>();
        for (BattleSettlementData settlement : valid) {
            Result result = applyAndFinish(player, settlement, SettlementPath.LOGIN, AckTrigger.LOGIN);
            handled.add(settlement.getBattleId());
            if (result.outcome() == Outcome.DEFERRED) {
                deferred = true;
                log.warn("进场恢复：待结算记录延后（{}），停在这一局、不越过它应用更新的局 player={} battle_id={}", result.reason(),
                        Long.toUnsignedString(playerId), Long.toUnsignedString(settlement.getBattleId()));
                break;
            }
        }
        // 第 3 步：锁（没有冻结时；第 2 步中途停下也照做）
        if (!player.inBattle()) {
            long lockBattle = read.lockBattleId();
            if (lockBattle != 0) {
                BattleLedger ledger = player.battleLedger();
                if (recordIds.contains(lockBattle)) {
                    // 这一局已经结束（结算记录就是证据）：不重建，否则把玩家冻进一场打完的战斗
                    metrics.rebuild(RebuildReason.LOGIN, RebuildResult.SKIPPED_CORRUPT);
                } else if (ledger.invalidReason() == null && ledger.has(lockBattle)) {
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
        // 第 5 步
        if (deferred) {
            player.battle().setRecovery(Recovery.RETRY);
            metrics.recovery(SceneBattleMetrics.Recovery.RETRY);
        } else {
            player.battle().setRecovery(Recovery.READY);
            metrics.recovery(SceneBattleMetrics.Recovery.READY);
        }
    }

    /** 按锁重建冻结（§1.2 的取值规则），再 TOUCH 复核；没命中撤销；FIGHTING 复核通过后推 144。 */
    private void rebuildFromLock(ScenePlayer player, long battleId, Map<String, String> lock, long ttlSec) {
        long playerId = player.playerId();
        if (player.frozen()) {
            // 进场后马上发的 63 已进入交出：不挂冻结（两种冻结互斥）；交出提交后目标节点重建，没提交则原地解冻后补跑
            metrics.rebuild(RebuildReason.LOGIN, RebuildResult.MISS);
            return;
        }
        long now = clock.epochMillis();
        String state = lock.get(BattleRedis.FIELD_STATE);
        boolean fighting = !BattleRedis.STATE_PREPARING.equals(state);
        long deadline = BattleRedis.parseUnsigned(lock.get(BattleRedis.FIELD_DEADLINE));
        if (deadline == 0) {
            deadline = now + Math.max(ttlSec, 0) * 1000;
        }
        long prepareDeadline = BattleRedis.parseUnsigned(lock.get(BattleRedis.FIELD_PREPARE_DEADLINE));
        if (!fighting && prepareDeadline == 0) {
            prepareDeadline = deadline;
        }
        BattleFreeze freeze = new BattleFreeze(battleId, (int) BattleRedis.parseUnsigned(lock.get(BattleRedis.FIELD_NODE)),
                fighting ? Phase.FIGHTING : Phase.PREPARING, deadline, prepareDeadline, false);
        freeze.setLockExtended(fighting);
        attachFreeze(player, freeze);
        long touchTtl = BattleRedis.lockTtlSec(freeze.effectiveDeadlineMs(), now);
        onLogic(locks.touch(playerId, battleId, touchTtl, fighting ? BattleRedis.STATE_FIGHTING : BattleRedis.STATE_PREPARING,
                deadline, prepareDeadline), (hit, error) -> {
            if (world.playerById(playerId) != player || player.battle().freeze() != freeze) {
                return;
            }
            if (error != null) {
                // 复核失败：保守冻结（锁很可能还在），到期由 reaper 处理
                metrics.rebuild(RebuildReason.LOGIN, RebuildResult.ERROR);
                log.warn("进场按锁重建的复核脚本失败，保守保留冻结 player={} battle_id={}: {}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(battleId), error.toString());
                return;
            }
            if (hit == 0) {
                clearFreeze(player, LockAction.KEEP_LOCK);
                metrics.rebuild(RebuildReason.LOGIN, RebuildResult.REVERTED);
                return;
            }
            metrics.rebuild(RebuildReason.LOGIN, RebuildResult.REBUILT);
            log.info("进场按锁重建冻结 player={} battle_id={} phase={} deadline={}", Long.toUnsignedString(playerId),
                    Long.toUnsignedString(battleId), freeze.phase(), freeze.deadlineMs());
            if (freeze.phase() == Phase.FIGHTING) {
                // PREPARING 永不推（房间还没建，补签会被判 BattleGone，B5）
                pushReconnect(player, battleId, HintTrigger.LOGIN);
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
                BattleLedger ledger = player.battleLedger();
                if (ledger.invalidReason() == null) {
                    for (long battleId : ledger.battleIds()) {
                        writeOff(player, battleId, AckTrigger.REAPER);
                    }
                }
                if (player.battle().recovery() == Recovery.RETRY) {
                    startRecovery(player);
                }
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
        // 没有冻结：按锁（第 7 步）
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
            BattleFreeze now = player.battle().freeze();
            if (now != null && now.battleId() != battleId) {
                // 回调时已有别的局的冻结：丢弃，不销账（同 pb.cpp:1898-1904）
                metrics.settlement(SettlementPath.BY_LOCK, SettlementResult.DISCARDED_MISMATCH);
                reply.complete(reply(SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED));
                return;
            }
            if (lockBattle == null || lockBattle != battleId) {
                // 锁不在或易主：这一局已作废（B14），丢弃并销账
                metrics.settlement(SettlementPath.BY_LOCK, SettlementResult.DISCARDED_VOID);
                log.info("结算到达时没有冻结且锁不是本局（已作废），丢弃并销账 player={} battle_id={} 锁={}", Long.toUnsignedString(playerId),
                        Long.toUnsignedString(battleId), lockBattle == null ? "?" : Long.toUnsignedString(lockBattle));
                writeOff(player, battleId, AckTrigger.DISCARD);
                reply.complete(reply(SceneBattleStatus.SCENE_BATTLE_HANDLED, SettlementDisposition.SETTLEMENT_DISCARDED));
                return;
            }
            reply.complete(toReply(applyAndFinish(player, settlement, now != null ? SettlementPath.ONLINE : SettlementPath.BY_LOCK,
                    AckTrigger.APPLY)));
        });
        return reply;
    }

    /**
     * 应用并收尾（§7.10 第 9 步）：APPLIED / ALREADY_APPLIED → HOLD(X) → 冻结是这一局就解冻（锁留着）→ 销账 → 本次新应用才推 150；
     * DEFERRED 原样（冻结保留）；DISCARDED（归属不符）不销账。
     */
    private Result applyAndFinish(ScenePlayer player, BattleSettlementData settlement, SettlementPath path, AckTrigger trigger) {
        long playerId = player.playerId();
        long battleId = settlement.getBattleId();
        Result result = settlements.apply(world, player, settlement);
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
     * 解冻的唯一入口（基线 {@code RemoveInBattleComp} / {@code ClearBattleFreeze}）：摘掉冻结；{@code DELETE_IF_MATCH} → 发条件删锁，
     * <b>删完</b>（回到逻辑线程、实例未换、仍无冻结）补一次组队跟随；{@code KEEP_LOCK} → 立即补（锁还在，跟随链读到锁会放弃，锁真正放掉时由销账回调补）。
     *
     * @return 删锁完成（KEEP_LOCK / 本来就没有冻结时立即完成）
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
        CompletableFuture<Void> done = new CompletableFuture<>();
        onLogic(locks.deleteIfMatch(playerId, freeze.battleId()), (r, error) -> {
            logScriptError("解冻删锁", playerId, freeze.battleId(), error);
            if (world.playerById(playerId) == player && !player.inBattle()) {
                teamFollow.onBattleFreezeCleared(world, player);
            }
            done.complete(null);
        });
        return done;
    }

    /** 挂上一个冻结（迟到确认 / 进场恢复）：同时停步（移动上行此后被丢弃，旧速度不能继续外推）。 */
    private void attachFreeze(ScenePlayer player, BattleFreeze freeze) {
        player.battle().setFreeze(freeze);
        if (!player.velocity().isOrigin()) {
            world.haltForBattle(player);
        }
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

    /** 异步结局投递回逻辑线程（逻辑线程已停时丢弃）。 */
    private <T> void onLogic(CompletableFuture<T> future, BiConsumer<T, Throwable> callback) {
        future.whenComplete((value, error) -> {
            Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
            try {
                logic.execute(() -> {
                    try {
                        callback.accept(value, cause);
                    } catch (RuntimeException e) {
                        log.error("回合制战斗的异步回调出错", e);
                    }
                });
            } catch (RejectedExecutionException e) {
                log.debug("逻辑线程已停止，丢弃回合制战斗的异步结局");
            }
        });
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
