package com.game.battle.room;

import com.game.battle.engine.BattleConstants;
import com.game.battle.engine.BattleStart;
import com.game.battle.engine.TurnBattleEngine;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.CreateResult;
import com.game.battle.metrics.BattleMetrics.Disconnect;
import com.game.battle.metrics.BattleMetrics.RoomEnd;
import com.game.battle.metrics.BattleMetrics.RoundTrigger;
import com.game.battle.metrics.BattleMetrics.SceneEventKind;
import com.game.battle.metrics.BattleMetrics.SceneEventResult;
import com.game.battle.metrics.BattleMetrics.TicketPath;
import com.game.battle.protocol.BattleMessageIds.Notify;
import com.game.battle.push.BattleOutbound;
import com.game.battle.ticket.BattleTicketIssuer;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.MessageContent;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.SetAutoBattleRequest;
import com.game.proto.SetAutoBattleResponse;
import com.game.proto.SpectateEndS2C;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.StopWatchBattleResponse;
import com.game.proto.SubmitBattleActionRequest;
import com.game.proto.SubmitBattleActionResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.TurnResultS2C;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleOutcome;
import com.game.proto.eBattleTicketRole;
import com.game.proto.eSpectateEndReason;
import com.game.table.CommonErrorTip.common_error;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link BattleRoomService} 的实现（基线 {@code BattleRoomManager}，{@code room.cpp:486-1653}、{@code :2033-2081}；battle-node-spec §4、§5、§7.6）。
 *
 * <p>内部件：房间表 {@link RoomTable}（唯一增删口，hooks 维护房间数）、{@link BattleRoom}、视角裁剪 {@link BattleViews}、
 * 指纹闸 {@link FingerprintGuard}、结果组装 {@link BattleResultAssembler}、下行出口 {@link BattleOutbound}、签票 {@link BattleTicketIssuer}。
 *
 * <p><b>线程</b>：除 {@link #roomCount()} 外全部方法与全部计时器回调都在逻辑线程上执行（方法开头 {@link BattleScheduler#assertInLoop()}），
 * 内部无锁。计时器回调捕获房间对象，执行时比较对象身份（{@code rooms.find(id) == room && !room.closed}，§11 N14），
 * Destroy 之后同 id 重建时旧回调打不到新房间。
 *
 * <p><b>R8</b>：{@link #resolveRound} 可能当场收尾并删房，调用方必须在调用之前填完应答、之后不再碰房间（它返回 {@link RoundOutcome}，
 * 不返回房间）。出站端口与大厅回落的异常一律吞掉并打 ERROR：外部缺陷不能把房间的收尾流程打断在半路。
 */
public final class BattleRoomServiceImpl implements BattleRoomService {

    private static final Logger log = LoggerFactory.getLogger(BattleRoomServiceImpl.class);

    static final int SUCCESS = common_error.kSuccess_VALUE;
    static final int INVALID_TABLE_DATA = common_error.kInvalidTableData_VALUE;
    static final int SERVICE_UNAVAILABLE = common_error.kServiceUnavailable_VALUE;
    static final int ENTITY_IS_NULL = common_error.kEntityIsNull_VALUE;
    static final int INVALID_PARAMETER = common_error.kInvalidParameter_VALUE;
    static final int FEATURE_UNAVAILABLE = common_error.kFeatureUnavailable_VALUE;
    static final int RATE_LIMIT_EXCEEDED = common_error.kRateLimitExceeded_VALUE;
    static final int PLAYER_NOT_FOUND_IN_SESSION = common_error.kPlayerNotFoundInSession_VALUE;

    /** 一回合结算之后房间的去向（R8）。 */
    enum RoundOutcome {
        /** 未分胜负，房间还在，下一回合已装填。 */
        ONGOING,
        /** 已分胜负，房间已收尾并从表里移除。 */
        FINISHED
    }

    private final RoomDependencies deps;
    private final BattleScheduler scheduler;
    private final BattleClock clock;
    private final BattleMetrics metrics;
    private final FingerprintGuard fingerprints;
    private final BattleTicketIssuer issuer;
    private final BattleOutbound outbound;
    private final AtomicInteger roomCount = new AtomicInteger();
    private final RoomTable<BattleRoom> rooms;

    public BattleRoomServiceImpl(RoomDependencies deps) {
        this.deps = Objects.requireNonNull(deps, "deps");
        this.scheduler = deps.scheduler();
        this.clock = deps.clock();
        this.metrics = deps.metrics();
        this.fingerprints = new FingerprintGuard(deps.fingerprintMode(), deps.tableFingerprint(), metrics);
        this.issuer = new BattleTicketIssuer(deps.identity(), deps.tickets(), metrics);
        this.outbound = new BattleOutbound(deps.messageIds(), deps.lobby(), metrics);
        this.rooms = new RoomTable<>(new RoomHooks() {
            @Override
            public void onCreated(long battleId) {
                roomCount.incrementAndGet();
            }

            @Override
            public void onRemoved(long battleId) {
                roomCount.decrementAndGet();
            }
        });
    }

    /** 构造时给的依赖（装配与测试核对用）。 */
    public RoomDependencies dependencies() {
        return deps;
    }

    // ===================================================================================== 控制面

    @Override
    public CreateBattleResponse createBattle(CreateBattleRequest request, RoomOrigin origin) {
        scheduler.assertInLoop();
        Objects.requireNonNull(origin, "origin");
        long battleId = request.getBattleId();
        // battle_id 在第一行就回填，每条路径都带（room.cpp:490）
        CreateBattleResponse.Builder response = CreateBattleResponse.newBuilder().setBattleId(battleId);

        // 0 幂等：match 补偿路径可能重试；不比较请求内容、零副作用（B7）
        if (rooms.find(battleId) != null) {
            metrics.roomCreate(CreateResult.IDEMPOTENT);
            log.info("CreateBattle 幂等命中 battle_id={} origin={}", Long.toUnsignedString(battleId), origin);
            return response.build();
        }
        // 1–2 参数与路由 fail-closed
        String invalid = invalidReason(request);
        if (invalid != null) {
            metrics.roomCreate(CreateResult.INVALID);
            log.error("CreateBattle 参数非法 battle_id={} reason={}", Long.toUnsignedString(battleId), invalid);
            return response.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        // 3 指纹闸
        if (fingerprints.check(request) == FingerprintGuard.Decision.REJECT) {
            metrics.roomCreate(CreateResult.FINGERPRINT_REJECT);
            return response.setErrorMessage(TipInfoMessage.newBuilder().setId(FEATURE_UNAVAILABLE)
                    .addParameters(fingerprints.rejectionText(request))).build();
        }
        // 4 引擎开局（房间还是局部对象，拒绝即无副作用）
        TurnBattleEngine engine;
        switch (TurnBattleEngine.start(request, deps.data())) {
            case BattleStart.Started started -> engine = started.engine();
            case BattleStart.Rejected rejected -> {
                metrics.roomCreate(CreateResult.ENGINE_REJECT);
                log.error("CreateBattle 引擎初始化失败（快照 / 表数据非法） battle_id={} battle_config_id={} reason={} detail={}",
                        Long.toUnsignedString(battleId), Integer.toUnsignedString(request.getBattleConfigId()), rejected.reason(),
                        rejected.detail());
                return response.setErrorMessage(tip(INVALID_TABLE_DATA)).build();
            }
        }
        // 6 整场期限：deadline_ms ≤ now（不只是没填）一律取兜底；必须在签票之前落到房间上
        long now = clock.epochMillis();
        long deadlineMs = request.getDeadlineMs();
        if (Long.compareUnsigned(deadlineMs, now) <= 0) {
            deadlineMs = now + RoomConstants.DEFAULT_DEADLINE_MS;
        }
        // 5 抄参战名单（无符号升序）
        BattleRoom room = new BattleRoom(battleId, request.getMatchMode(), request.getBattleConfigId(), request.getActivityContext(),
                origin, engine, deadlineMs);
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            room.routingByPlayer.put(snapshot.getPlayerId(), snapshot.getRouting());
        }
        // 7 fail-closed 预签：插表、装计时器、推送、发确认之前给全体参战者签好；任一人签不出整局拒绝，零副作用
        Map<Long, BattleAssignedS2C> assignments = new HashMap<>();
        for (long playerId : room.routingByPlayer.keySet()) {
            Optional<BattleAssignedS2C> assigned = issuer.issue(battleId, playerId, deadlineMs,
                    eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT, TicketPath.CREATE);
            if (assigned.isEmpty()) {
                metrics.roomCreate(CreateResult.TICKET_FAILED);
                log.error("CreateBattle 参战票据签不出，拒绝开局(kServiceUnavailable) battle_id={} player_id={}",
                        Long.toUnsignedString(battleId), Long.toUnsignedString(playerId));
                return response.setErrorMessage(tip(SERVICE_UNAVAILABLE)).build();
            }
            assignments.put(playerId, assigned.get());
        }
        // 8 插表 = 房间生命周期起点
        if (rooms.emplace(battleId, room) == null) {
            // 开头已判过幂等且同一逻辑任务内无人插表：走到这里是程序缺陷。按幂等命中处理（回 OK），否则会诱发 match 的补偿 destroy
            metrics.roomCreate(CreateResult.IDEMPOTENT);
            log.error("CreateBattle 重复插表（幂等判定失效），新建的房间对象丢弃 battle_id={}", Long.toUnsignedString(battleId));
            return response.build();
        }

        // ---- 插表之后的副作用，顺序固定（room.cpp:597-632）
        long untilDeadline = deadlineMs - now;
        room.battleTimer = scheduler.after(untilDeadline < 0 ? Long.MAX_VALUE : untilDeadline, () -> onBattleDeadline(room));
        armRoundTimer(room);
        BattleStateS2C start = fullState(room);
        for (Map.Entry<Long, BattleRouting> entry : room.routingByPlayer.entrySet()) {
            long playerId = entry.getKey();
            ViewerState view = BattleViews.forParticipant(start, playerId, engine.selfItems(playerId));
            // 177 先于 143（R5、O1）：同一批交给出口，经 gate 时是一条 GatePush{message_batch}
            outbound.pushLobby(battleId, playerId, room.directOf(playerId), List.of(
                    outbound.announcement(Notify.BATTLE_ASSIGNED, assignments.get(playerId)),
                    outbound.announcement(Notify.BATTLE_START, view.toBattleStart(battleId))));
            confirm(room, playerId, entry.getValue());
        }
        room.confirmResendTimer = scheduler.every(RoomConstants.CONFIRM_RESEND_INTERVAL_MS, () -> resendConfirmed(room));

        metrics.roomCreate(CreateResult.OK);
        log.info("CreateBattle 成功 battle_id={} players={} battle_config_id={} match_mode={} deadline_ms={} origin={} activity_kind={} "
                        + "activity_id={}",
                Long.toUnsignedString(battleId), request.getPlayersCount(), Integer.toUnsignedString(request.getBattleConfigId()),
                request.getMatchMode(), Long.toUnsignedString(deadlineMs), origin, request.getActivityContext().getKindValue(),
                request.getActivityContext().getActivityId());
        return response.build();
    }

    /**
     * 建房第 1–2 步的纯判定（{@code room.cpp:499-521}）：{@code battle_id == 0} 或没有玩家；任一快照的 {@code routing.gate_instance_id}
     * 或 {@code scene_instance_id} 为空（出站防僵尸过滤会被关掉，打完也送不回结果）。通过返回 null，否则返回日志用的原因。
     */
    static String invalidReason(CreateBattleRequest request) {
        if (request.getBattleId() == 0 || request.getPlayersCount() == 0) {
            return "battle_id 为 0 或没有玩家: players=" + request.getPlayersCount();
        }
        for (BattlePlayerSnapshot snapshot : request.getPlayersList()) {
            BattleRouting routing = snapshot.getRouting();
            if (routing.getGateInstanceId().isEmpty() || routing.getSceneInstanceId().isEmpty()) {
                return "路由信息不完整: player_id=" + Long.toUnsignedString(snapshot.getPlayerId()) + " gate_instance_id="
                        + routing.getGateInstanceId() + " scene_instance_id=" + routing.getSceneInstanceId();
            }
        }
        return null;
    }

    @Override
    public void destroyBattle(DestroyBattleRequest request) {
        scheduler.assertInLoop();
        BattleRoom room = rooms.find(request.getBattleId());
        if (room == null) {
            log.info("DestroyBattle 幂等命中 battle_id={} reason={}", Long.toUnsignedString(request.getBattleId()), request.getReason());
            return;
        }
        // 补偿 / 回滚：不结算、不发结果事件、不通知 scene（已 FIGHTING 的玩家冻结到期限，由 scene reaper 解除，Q5）
        room.cancelTimers();
        notifySpectateEndAndClose(room, eSpectateEndReason.SPECTATE_END_BATTLE_ABORTED, eBattleOutcome.BATTLE_OUTCOME_ONGOING,
                Disconnect.BATTLE_CLOSED);
        // 参战者没有终局包可等：只看到 FIN（O6）
        closeDirectConnections(room, Disconnect.BATTLE_CLOSED);
        eraseRoom(room, RoomEnd.DESTROYED);
        log.info("DestroyBattle 完成 battle_id={} reason={}", Long.toUnsignedString(room.battleId), request.getReason());
    }

    @Override
    public void abortAll(String reason) {
        scheduler.assertInLoop();
        if (rooms.isEmpty()) {
            return;
        }
        log.info("作废全部战斗房间 rooms={} reason={}", rooms.size(), reason);
        // 先拷 id 再逐个删：每间都经唯一删除口，各触发一次 onRemoved
        for (long battleId : rooms.ids()) {
            BattleRoom room = rooms.find(battleId);
            if (room == null) {
                continue;
            }
            room.cancelTimers();
            notifySpectateEndAndClose(room, eSpectateEndReason.SPECTATE_END_BATTLE_ABORTED, eBattleOutcome.BATTLE_OUTCOME_ONGOING,
                    Disconnect.SHUTDOWN);
            closeDirectConnections(room, Disconnect.SHUTDOWN);
            eraseRoom(room, RoomEnd.ABORTED);
        }
    }

    @Override
    public IssueBattleTicketResponse issueBattleTicket(IssueBattleTicketRequest request) {
        scheduler.assertInLoop();
        IssueBattleTicketResponse.Builder response = IssueBattleTicketResponse.newBuilder();
        long playerId = request.getPlayerId();
        if (playerId == 0) {
            // 0 只可能是 match 漏填：参数错误，不是 1012
            log.warn("IssueBattleTicket 请求缺少 player_id battle_id={}", Long.toUnsignedString(request.getBattleId()));
            return response.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        BattleRoom room = rooms.find(request.getBattleId());
        if (room == null) {
            log.debug("IssueBattleTicket 房间不存在 battle_id={} player_id={}", Long.toUnsignedString(request.getBattleId()),
                    Long.toUnsignedString(playerId));
            return response.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        eBattleTicketRole role;
        if (room.isParticipant(playerId)) {
            role = eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT;
        } else if (room.isObserver(playerId)) {
            role = eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER;
        } else {
            log.warn("IssueBattleTicket 玩家不在此战斗 battle_id={} player_id={}", Long.toUnsignedString(room.battleId),
                    Long.toUnsignedString(playerId));
            return response.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        Optional<BattleAssignedS2C> assigned = issuer.issue(room.battleId, playerId, room.deadlineMs, role, TicketPath.REISSUE);
        if (assigned.isEmpty()) {
            return response.setErrorMessage(tip(SERVICE_UNAVAILABLE)).build();
        }
        log.info("IssueBattleTicket 补签成功 battle_id={} player_id={} role={}", Long.toUnsignedString(room.battleId),
                Long.toUnsignedString(playerId), role);
        return response.setAssignment(assigned.get()).build();
    }

    @Override
    public AddObserverResponse addObserver(AddObserverRequest request) {
        scheduler.assertInLoop();
        AddObserverResponse.Builder response = AddObserverResponse.newBuilder();
        long battleId = request.getBattleId();
        long observerId = request.getObserverPlayerId();
        BattleRoom room = rooms.find(battleId);
        if (room == null) {
            // 1004 是 match 懒剔除观战索引的信号，不能换 tip
            log.info("AddObserver 房间不存在 battle_id={} observer={}", Long.toUnsignedString(battleId), Long.toUnsignedString(observerId));
            return response.setErrorMessage(tip(ENTITY_IS_NULL)).build();
        }
        if (observerId == 0 || request.getRouting().getGateInstanceId().isEmpty()) {
            log.error("AddObserver 参数非法 battle_id={} observer={} gate_instance_id={}", Long.toUnsignedString(battleId),
                    Long.toUnsignedString(observerId), request.getRouting().getGateInstanceId());
            return response.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        // 单槽互斥：参战者不能观战自己这局
        if (room.isParticipant(observerId)) {
            log.warn("AddObserver 观众是参战者 battle_id={} observer={}", Long.toUnsignedString(battleId), Long.toUnsignedString(observerId));
            return response.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }

        BattleRouting existing = room.routingByObserver.get(observerId);
        if (existing != null) {
            return addExistingObserver(room, observerId, existing, request, response);
        }

        // 满员判定排在幂等之后（room.cpp:865-871）
        if (room.routingByObserver.size() >= RoomConstants.MAX_OBSERVERS_PER_ROOM) {
            log.info("AddObserver 观众已满 battle_id={} observer={} cap={}", Long.toUnsignedString(battleId),
                    Long.toUnsignedString(observerId), RoomConstants.MAX_OBSERVERS_PER_ROOM);
            return response.setErrorMessage(tip(RATE_LIMIT_EXCEEDED)).build();
        }
        // fail-closed：登记之前签票，签不出不登记
        Optional<BattleAssignedS2C> assigned = issuer.issue(battleId, observerId, room.deadlineMs,
                eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER, TicketPath.OBSERVER);
        if (assigned.isEmpty()) {
            return response.setErrorMessage(tip(SERVICE_UNAVAILABLE)).build();
        }
        room.routingByObserver.put(observerId, request.getRouting());
        room.observerNames.put(observerId, request.getObserverName());
        // 只推落点分配；观战首帧随直连握手下发（此刻观众一定还没有直连）
        pushAssignment(room, observerId, assigned.get());
        log.info("AddObserver 成功 battle_id={} observer={} name={} observers={}", Long.toUnsignedString(battleId),
                Long.toUnsignedString(observerId), request.getObserverName(), room.routingByObserver.size());
        return response.build();
    }

    /** AddObserver 的幂等路径（{@code room.cpp:812-860}）：先重签（签不出就摘除 + 关直连 + 1003），再按会话是否变化分别处理。 */
    private AddObserverResponse addExistingObserver(BattleRoom room, long observerId, BattleRouting existing, AddObserverRequest request,
                                                    AddObserverResponse.Builder response) {
        BattleRouting routing = request.getRouting();
        boolean sameSession = existing.getSessionId() == routing.getSessionId()
                && existing.getGateNodeId() == routing.getGateNodeId()
                && existing.getGateInstanceId().equals(routing.getGateInstanceId());
        Optional<BattleAssignedS2C> assigned = issuer.issue(room.battleId, observerId, room.deadlineMs,
                eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER, TicketPath.OBSERVER);
        if (assigned.isEmpty()) {
            // match 收到任何错误都会删观战标记：房间侧必须同步摘除并关直连
            room.routingByObserver.remove(observerId);
            room.observerNames.remove(observerId);
            closeDirectOf(room, observerId, Disconnect.BATTLE_CLOSED);
            log.error("AddObserver 幂等路径签票失败，摘除观众 battle_id={} observer={} same_session={}", Long.toUnsignedString(room.battleId),
                    Long.toUnsignedString(observerId), sameSession);
            return response.setErrorMessage(tip(SERVICE_UNAVAILABLE)).build();
        }
        if (sameSession) {
            // 同会话重试：重推落点分配；有活直连时再推观战快照（否则丢弃，握手时补）
            log.info("AddObserver 幂等命中，重推落点分配 battle_id={} observer={}", Long.toUnsignedString(room.battleId),
                    Long.toUnsignedString(observerId));
            pushAssignment(room, observerId, assigned.get());
            pushSpectateState(room, observerId);
            return response.build();
        }
        log.info("AddObserver 幂等命中但会话已变，刷新路由 battle_id={} observer={} old_session_id={} new_session_id={}",
                Long.toUnsignedString(room.battleId), Long.toUnsignedString(observerId), Integer.toUnsignedString(existing.getSessionId()),
                Integer.toUnsignedString(routing.getSessionId()));
        room.routingByObserver.put(observerId, routing);
        room.observerNames.put(observerId, request.getObserverName());
        // 旧客户端实例已不在：它的直连可能半开，先关掉，让分配包回落到新会话；首帧随新直连握手下发，这里不推
        closeDirectOf(room, observerId, Disconnect.BATTLE_CLOSED);
        pushAssignment(room, observerId, assigned.get());
        return response.build();
    }

    @Override
    public void removeObserver(RemoveObserverRequest request) {
        scheduler.assertInLoop();
        BattleRoom room = rooms.find(request.getBattleId());
        long observerId = request.getObserverPlayerId();
        if (room == null || !room.isObserver(observerId)) {
            return;
        }
        // 被动清退：先告知原因再关直连（没有直连时丢弃，战斗帧不回落）
        SpectateEndS2C end = SpectateEndS2C.newBuilder()
                .setBattleId(room.battleId)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_ONGOING)
                .setReason(eSpectateEndReason.SPECTATE_END_REMOVED)
                .build();
        outbound.pushBattleFrame(room.battleId, observerId, room.directOf(observerId), Notify.SPECTATE_END,
                outbound.frame(Notify.SPECTATE_END, end));
        room.routingByObserver.remove(observerId);
        room.observerNames.remove(observerId);
        closeDirectOf(room, observerId, Disconnect.BATTLE_CLOSED);
        log.info("RemoveObserver 完成 battle_id={} observer={} reason={}", Long.toUnsignedString(room.battleId),
                Long.toUnsignedString(observerId), request.getReason());
    }

    // ===================================================================================== 直连面

    @Override
    public OptionalInt attachDirect(long battleId, long playerId, eBattleTicketRole role, DirectLink link) {
        scheduler.assertInLoop();
        BattleRoom room = rooms.find(battleId);
        if (room == null || link == null) {
            return OptionalInt.empty();
        }
        // 按票上的角色核对名单：参战票只认参战名单，观众票只认观众名单（票不能换角色用）
        BattleRouting routing = switch (role) {
            case BATTLE_TICKET_ROLE_PARTICIPANT -> room.routingByPlayer.get(playerId);
            case BATTLE_TICKET_ROLE_OBSERVER -> room.routingByObserver.get(playerId);
            default -> null;
        };
        if (routing == null) {
            log.warn("battle 直连挂接被拒：玩家不在名单 battle_id={} player_id={} role={}", Long.toUnsignedString(battleId),
                    Long.toUnsignedString(playerId), role);
            return OptionalInt.empty();
        }
        // 先放新的再关旧的（R7）：旧连接迟到的断开回调按引用比较，不会误摘新连接
        DirectLink old = room.directByPlayer.put(playerId, link);
        if (old != null && old != link) {
            log.info("battle 直连重连，替换旧连接 battle_id={} player_id={} old_peer={} new_peer={}", Long.toUnsignedString(battleId),
                    Long.toUnsignedString(playerId), old.peer(), link.peer());
            old.closeNow(Disconnect.REPLACED);
        }
        return OptionalInt.of(routing.getSessionId());
    }

    @Override
    public void onDirectVerified(long battleId, long playerId, eBattleTicketRole role) {
        scheduler.assertInLoop();
        // 参战者由客户端自己发 140 补拉，这里不推
        if (role != eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER) {
            return;
        }
        BattleRoom room = rooms.find(battleId);
        if (room == null || !room.isObserver(playerId)) {
            return;
        }
        // 观战首帧只随直连下发（握手应答之后，O2）
        pushSpectateState(room, playerId);
    }

    @Override
    public void detachDirect(long battleId, long playerId, DirectLink link) {
        scheduler.assertInLoop();
        BattleRoom room = rooms.find(battleId);
        if (room == null) {
            return;
        }
        // 只摘「仍指向自己」的槽（引用相等）
        if (link != null && room.directByPlayer.get(playerId) == link) {
            room.directByPlayer.remove(playerId);
        }
    }

    @Override
    public SubmitBattleActionResponse submit(long playerId, SubmitBattleActionRequest request) {
        scheduler.assertInLoop();
        SubmitBattleActionResponse.Builder response = SubmitBattleActionResponse.newBuilder();
        if (playerId == 0) {
            return response.setErrorMessage(tip(PLAYER_NOT_FOUND_IN_SESSION)).build();
        }
        BattleRoom room = rooms.find(request.getBattleId());
        if (room == null) {
            // 常见竞态：战斗刚结束，行动包晚到
            log.debug("SubmitBattleAction 房间不存在 battle_id={} player_id={}", Long.toUnsignedString(request.getBattleId()),
                    Long.toUnsignedString(playerId));
            return response.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        // 防串房：观众也算不是参战者
        if (!room.isParticipant(playerId)) {
            log.warn("SubmitBattleAction 玩家不在此战斗 battle_id={} player_id={}", Long.toUnsignedString(room.battleId),
                    Long.toUnsignedString(playerId));
            return response.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        int validation = room.engine.validateAction(playerId, request.getAction());
        if (validation != SUCCESS) {
            if (validation == 0) {
                // 0 不是任何 tip，写进去客户端会读成成功却没落账
                log.error("SubmitBattleAction 校验返回 0（引擎契约破坏） battle_id={} player_id={}", Long.toUnsignedString(room.battleId),
                        Long.toUnsignedString(playerId));
            }
            return response.setErrorMessage(tip(validation != 0 ? validation : INVALID_PARAMETER)).build();
        }
        boolean allReady = room.engine.submitAction(playerId, request.getAction());
        // R8：应答在结算之前填完；结算可能当场删房
        SubmitBattleActionResponse built = response.build();
        if (allReady) {
            // 全员就绪当场结算（全自动房间里挂机玩家的合法提交也会，B2 照搬）
            resolveRound(room, RoundTrigger.ALL_READY);
        }
        return built;
    }

    @Override
    public BattleStateS2C getState(long playerId, GetBattleStateRequest request) {
        scheduler.assertInLoop();
        BattleRoom room = rooms.find(request.getBattleId());
        boolean member = room != null && playerId != 0 && (room.isParticipant(playerId) || room.isObserver(playerId));
        if (!member) {
            // 回默认状态（battle_id = 0，0 字节），不回错误码：客户端据此丢掉本地战斗界面
            return BattleStateS2C.getDefaultInstance();
        }
        BattleStateS2C full = fullState(room);
        ViewerState view = room.isParticipant(playerId)
                ? BattleViews.forParticipant(full, playerId, room.engine.selfItems(playerId))
                : BattleViews.forObserver(full);
        return view.state();
    }

    @Override
    public SetAutoBattleResponse setAuto(long playerId, SetAutoBattleRequest request) {
        scheduler.assertInLoop();
        SetAutoBattleResponse.Builder response = SetAutoBattleResponse.newBuilder();
        if (playerId == 0) {
            return response.setErrorMessage(tip(PLAYER_NOT_FOUND_IN_SESSION)).build();
        }
        BattleRoom room = rooms.find(request.getBattleId());
        if (room == null) {
            return response.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        // 观众不能切自动
        if (!room.isParticipant(playerId)) {
            log.warn("SetAutoBattle 玩家不在此战斗 battle_id={} player_id={}", Long.toUnsignedString(room.battleId),
                    Long.toUnsignedString(playerId));
            return response.setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        // 置位前采样就绪态，判断本次置位是否造成「未就绪 → 全员就绪」的翻转
        boolean wasAllReady = room.engine.allPlayersReady();
        int result = room.engine.setActorAuto(playerId, request.getEnabled());
        if (result != SUCCESS) {
            // Java 引擎成功码是 1000（engine-spec D2）；0 不是 tip，兜底成 1005
            return response.setErrorMessage(tip(result != 0 ? result : INVALID_PARAMETER)).build();
        }
        SetAutoBattleResponse built = response.build();
        // 只在翻转时立即结算：已全员就绪的房间重复开启不加速；关闭永不触发结算
        if (request.getEnabled() && !wasAllReady && room.engine.allPlayersReady()) {
            resolveRound(room, RoundTrigger.AUTO_FLIP);
        }
        return built;
    }

    @Override
    public StopWatchBattleResponse stopWatch(long playerId, StopWatchBattleRequest request) {
        scheduler.assertInLoop();
        StopWatchBattleResponse.Builder response = StopWatchBattleResponse.newBuilder();
        if (playerId == 0) {
            return response.setErrorMessage(tip(PLAYER_NOT_FOUND_IN_SESSION)).build();
        }
        BattleRoom room = rooms.find(request.getBattleId());
        // 幂等：房间不在、不是观众都回成功
        if (room == null || !room.isObserver(playerId)) {
            return response.build();
        }
        // 主动退出不推 166；应答由直连面在本方法返回后写出，之后才 FIN（O8）
        room.routingByObserver.remove(playerId);
        room.observerNames.remove(playerId);
        closeDirectOf(room, playerId, Disconnect.BATTLE_CLOSED);
        log.info("StopWatchBattle 完成 battle_id={} observer={}", Long.toUnsignedString(room.battleId), Long.toUnsignedString(playerId));
        return response.build();
    }

    // ===================================================================================== 任意线程

    @Override
    public int roomCount() {
        return roomCount.get();
    }

    // ===================================================================================== 计时器与结算（逻辑线程）

    /** 测试与排障用：按 id 查房间（逻辑线程）。 */
    BattleRoom room(long battleId) {
        return rooms.find(battleId);
    }

    /** 计时器回调的身份检查（§11 N14）：表里仍是这一个对象且没有进入收尾。 */
    private boolean isCurrent(BattleRoom room) {
        return !room.closed && rooms.find(room.battleId) == room;
    }

    /**
     * 装填回合窗口（{@code ArmRoundTimer}，{@code room.cpp:1022-1037}）：装填那一刻全员就绪（含空真）就是 2000 ms，否则 6000 ms；
     * 之后切换挂机不改变本回合的窗口。
     */
    private void armRoundTimer(BattleRoom room) {
        long windowMs = room.engine.allPlayersReady() ? BattleConstants.AUTO_ROUND_INTERVAL_MS : BattleConstants.ROUND_DURATION_MS;
        room.actionDeadlineMs = clock.epochMillis() + windowMs;
        room.roundTimer = scheduler.after(windowMs, () -> {
            if (isCurrent(room)) {
                resolveRound(room, RoundTrigger.TIMER);
            }
        });
    }

    /**
     * 结算当前回合（{@code ResolveRound}，{@code room.cpp:1039-1080}）：先取消旧窗口（提前结算时它还挂着）→ 引擎结算 → 未分胜负先装填
     * 下一回合再回填新截止（已分胜负回填 0）→ 透传出手序 → 广播 139 / 158 → 已分胜负则收尾并删房。
     */
    RoundOutcome resolveRound(BattleRoom room, RoundTrigger trigger) {
        long started = System.nanoTime();
        room.roundTimer.cancel();
        metrics.round(trigger);
        TurnResultS2C.Builder result = room.engine.resolveCurrentRound().toBuilder();
        eBattleOutcome outcome = room.engine.outcome();
        boolean ongoing = outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING;
        if (ongoing) {
            armRoundTimer(room);
            result.getStateBuilder().setActionDeadlineMs(room.actionDeadlineMs);
        } else {
            result.getStateBuilder().setActionDeadlineMs(0);
        }
        result.setBattleId(room.battleId);
        result.getStateBuilder().setBattleId(room.battleId);
        result.clearActionOrder().addAllActionOrder(room.engine.lastActionOrder());
        broadcastTurnResult(room, result.build());
        if (!ongoing) {
            finishBattle(room, outcome, eSpectateEndReason.SPECTATE_END_BATTLE_FINISHED);
            eraseRoom(room, RoomEnd.FINISHED);
        }
        metrics.roundResolve(System.nanoTime() - started);
        return ongoing ? RoundOutcome.ONGOING : RoundOutcome.FINISHED;
    }

    /** 整场期限（{@code OnBattleDeadline}，{@code room.cpp:1082-1105}）：仍未分胜负就盖 DRAW；没有终局 139；观众收 ABORTED + 该 outcome。 */
    private void onBattleDeadline(BattleRoom room) {
        if (!isCurrent(room)) {
            return;
        }
        eBattleOutcome outcome = room.engine.outcome();
        if (outcome == eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            outcome = eBattleOutcome.BATTLE_OUTCOME_DRAW;
        }
        log.warn("战斗整场超时强制收尾 battle_id={} outcome={}", Long.toUnsignedString(room.battleId), outcome);
        room.roundTimer.cancel();
        finishBattle(room, outcome, eSpectateEndReason.SPECTATE_END_BATTLE_ABORTED);
        eraseRoom(room, RoomEnd.DEADLINE);
    }

    /** 确认事件补发（{@code ResendBattleConfirmed}，{@code room.cpp:1107-1128}；计次实现，§11 N19）。 */
    private void resendConfirmed(BattleRoom room) {
        if (!isCurrent(room)) {
            return;
        }
        if (room.confirmResendsLeft <= 0) {
            // 窗口已过：scene 侧锁也已过期，再补发无意义，停表
            room.confirmResendTimer.cancel();
            return;
        }
        room.confirmResendsLeft--;
        for (Map.Entry<Long, BattleRouting> entry : room.routingByPlayer.entrySet()) {
            confirm(room, entry.getKey(), entry.getValue());
        }
        log.debug("BattleConfirmedEvent 周期补发 battle_id={} players={} left={}", Long.toUnsignedString(room.battleId),
                room.routingByPlayer.size(), room.confirmResendsLeft);
    }

    /** 每个参战者一份裁剪过的 139；有观众时另做<b>一份</b>观众版 158，同一帧写给全部观众（§11 N15）。 */
    private void broadcastTurnResult(BattleRoom room, TurnResultS2C result) {
        BattleStateS2C full = result.getState();
        for (long playerId : room.routingByPlayer.keySet()) {
            ViewerState view = BattleViews.forParticipant(full, playerId, room.engine.selfItems(playerId));
            outbound.pushBattleFrame(room.battleId, playerId, room.directOf(playerId), Notify.TURN_RESULT,
                    outbound.frame(Notify.TURN_RESULT, view.toTurnResult(result)));
        }
        if (!room.routingByObserver.isEmpty()) {
            MessageContent spectate = outbound.frame(Notify.SPECTATE_TURN_RESULT, BattleViews.forObserver(full).toTurnResult(result));
            for (long observerId : room.routingByObserver.keySet()) {
                outbound.pushBattleFrame(room.battleId, observerId, room.directOf(observerId), Notify.SPECTATE_TURN_RESULT, spectate);
            }
        }
    }

    /**
     * 正常收尾（{@code FinishBattle}，{@code room.cpp:1130-1221}）：只组装与发送，不动房间表（调用方随后 {@link #eraseRoom}）。顺序固定（R6）：
     * 取消计时器 → 按 player_id 升序逐人「150 → 结算端口」→ 观众 166 + 关闭 → 关参战者直连 → 结果事件。
     */
    private void finishBattle(BattleRoom room, eBattleOutcome outcome, eSpectateEndReason spectateReason) {
        room.cancelTimers();
        List<BattleSettlementData> settlements = new ArrayList<>(room.routingByPlayer.size());
        for (Map.Entry<Long, BattleRouting> entry : room.routingByPlayer.entrySet()) {
            long playerId = entry.getKey();
            // 强制平局路径引擎结算里还是 ONGOING：以节点判定为准统一盖章
            BattleSettlementData settlement = room.engine.buildSettlement(playerId).toBuilder()
                    .setOutcome(outcome)
                    .setBattleId(room.battleId)
                    .build();
            settlements.add(settlement);
            BattleEndS2C end = BattleEndS2C.newBuilder().setBattleId(room.battleId).setOutcome(outcome).setSettlement(settlement).build();
            outbound.pushBattleFrame(room.battleId, playerId, room.directOf(playerId), Notify.BATTLE_END, outbound.frame(Notify.BATTLE_END, end));
            dispatchSettlement(room, playerId, entry.getValue(), settlement);
        }
        notifySpectateEndAndClose(room, spectateReason, outcome, Disconnect.BATTLE_CLOSED);
        // 终局包已全部写出：关直连（应答 → FIN 由直连面保证，R3）
        closeDirectConnections(room, Disconnect.BATTLE_CLOSED);
        BattleResultEvent event = BattleResultAssembler.assemble(room.battleId, room.matchMode, room.battleConfigId, room.activityContext,
                outcome, settlements, clock.epochMillis());
        dispatchResult(room, event);
        log.info("战斗结束 battle_id={} outcome={} players={} teams={} total_rounds={} fled={} dead={} origin={} activity_kind={}",
                Long.toUnsignedString(room.battleId), outcome, room.routingByPlayer.size(), event.getTeamsCount(), event.getTotalRounds(),
                event.getFledPlayerIdsCount(), event.getDeadPlayerIdsCount(), room.origin, room.activityContext.getKindValue());
    }

    /** 观众收 166 后关其直连，然后清空观众表（{@code room.cpp:1297-1325}）；166 每次只构造一帧。 */
    private void notifySpectateEndAndClose(BattleRoom room, eSpectateEndReason reason, eBattleOutcome outcome, Disconnect disconnect) {
        if (room.routingByObserver.isEmpty()) {
            return;
        }
        MessageContent end = outbound.frame(Notify.SPECTATE_END, SpectateEndS2C.newBuilder()
                .setBattleId(room.battleId)
                .setOutcome(outcome)
                .setReason(reason)
                .build());
        for (long observerId : room.routingByObserver.keySet()) {
            outbound.pushBattleFrame(room.battleId, observerId, room.directOf(observerId), Notify.SPECTATE_END, end);
            closeDirectOf(room, observerId, disconnect);
        }
        log.info("观战清退 battle_id={} observers={} reason={}", Long.toUnsignedString(room.battleId), room.routingByObserver.size(), reason);
        room.routingByObserver.clear();
        room.observerNames.clear();
    }

    /** 立刻摘槽，再优雅关闭（应答 → FIN，1 s 强关兜底；{@code room.cpp:1618-1636}）。 */
    private void closeDirectOf(BattleRoom room, long playerId, Disconnect reason) {
        DirectLink link = room.directByPlayer.remove(playerId);
        if (link != null) {
            link.closeGracefully(reason);
        }
    }

    /** 关房间里剩下的全部直连（按 player_id 升序；先清槽再关）。 */
    private void closeDirectConnections(BattleRoom room, Disconnect reason) {
        if (room.directByPlayer.isEmpty()) {
            return;
        }
        List<DirectLink> links = new ArrayList<>(room.directByPlayer.values());
        room.directByPlayer.clear();
        for (DirectLink link : links) {
            link.closeGracefully(reason);
        }
    }

    /** 置 closed 后经唯一删除口移除（恰好一次 onRemoved），计房间结局。 */
    private void eraseRoom(BattleRoom room, RoomEnd reason) {
        room.closed = true;
        if (rooms.find(room.battleId) == room && rooms.erase(room.battleId)) {
            metrics.roomEnd(reason);
        }
    }

    /** 全知快照 + battle_id + 当前回合截止（未裁剪：只能交给 {@link BattleViews}）。 */
    private static BattleStateS2C fullState(BattleRoom room) {
        return room.engine.buildStateSnapshot().toBuilder()
                .setBattleId(room.battleId)
                .setActionDeadlineMs(room.actionDeadlineMs)
                .build();
    }

    /** 161 观战快照（全员冷却清空、无道具、action_deadline_ms = 房间值、观众数 = 当前观众数）。 */
    private void pushSpectateState(BattleRoom room, long observerId) {
        ViewerState view = BattleViews.forObserver(fullState(room));
        outbound.pushBattleFrame(room.battleId, observerId, room.directOf(observerId), Notify.SPECTATE_STATE,
                outbound.frame(Notify.SPECTATE_STATE, view.toSpectateState(room.routingByObserver.size())));
    }

    /** 177 落点分配（大厅公告：有活直连直写，否则经 gate 回落）。 */
    private void pushAssignment(BattleRoom room, long playerId, BattleAssignedS2C assigned) {
        outbound.pushLobby(room.battleId, playerId, room.directOf(playerId),
                List.of(outbound.announcement(Notify.BATTLE_ASSIGNED, assigned)));
    }

    // ===================================================================================== 出站端口（异步、不阻塞；异常吞掉）

    private void confirm(BattleRoom room, long playerId, BattleRouting routing) {
        try {
            deps.sceneEvents().confirm(routing, playerId, room.battleId, room.deadlineMs);
        } catch (RuntimeException e) {
            log.error("确认事件端口抛出异常（已吞掉，由下一次补发覆盖） battle_id={} player_id={}", Long.toUnsignedString(room.battleId),
                    Long.toUnsignedString(playerId), e);
        }
    }

    private void dispatchSettlement(BattleRoom room, long playerId, BattleRouting routing, BattleSettlementData settlement) {
        if (room.origin == RoomOrigin.DEV) {
            // dev 房间永不结算（§7.9、§7.12）：dev 接口不能变成发奖口子
            metrics.sceneEvent(SceneEventKind.SETTLEMENT, SceneEventResult.SKIPPED);
            log.info("dev 房间不投递结算 battle_id={} player_id={} outcome={}", Long.toUnsignedString(room.battleId),
                    Long.toUnsignedString(playerId), settlement.getOutcome());
            return;
        }
        try {
            deps.settlements().dispatch(routing, playerId, settlement);
        } catch (RuntimeException e) {
            log.error("结算端口抛出异常（已吞掉） battle_id={} player_id={}", Long.toUnsignedString(room.battleId),
                    Long.toUnsignedString(playerId), e);
        }
    }

    private void dispatchResult(BattleRoom room, BattleResultEvent event) {
        if (room.origin == RoomOrigin.DEV) {
            log.info("dev 房间不投递对局结果 battle_id={} outcome={}", Long.toUnsignedString(room.battleId), event.getOutcome());
            return;
        }
        try {
            if (BattleResultAssembler.isActivity(room.activityContext)) {
                deps.activityResults().dispatch(event);
            } else {
                deps.results().publish(event);
            }
        } catch (RuntimeException e) {
            log.error("对局结果端口抛出异常（已吞掉） battle_id={}", Long.toUnsignedString(room.battleId), e);
        }
    }

    private static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }
}
