package com.game.battle.rpc;

import com.game.api.BattleNodeService;
import com.game.api.proto.BattleAdmission;
import com.game.api.proto.CreateBattleResult;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.admission.AdmissionPhase;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.CreateResult;
import com.game.battle.metrics.BattleMetrics.RpcMethod;
import com.game.battle.metrics.BattleMetrics.RpcResult;
import com.game.battle.room.BattleRoomService;
import com.game.battle.room.BattleScheduler;
import com.game.battle.room.RoomOrigin;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.Empty;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.TipInfoMessage;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * battle 控制面提供方（基线 {@code BattleNodeImpl}，{@code cpp/nodes/battle/handler/grpc/battle_node.cpp}；battle-node-spec §6.2、§7.3、§7.8）。
 * 由 {@link BattleRpcServer} 按节点导出（Dubbo Triple，{@code register = false}，group {@code battle-node}）；dev 管理接口经
 * {@link #createBattle(CreateBattleRequest, RoomOrigin)} 走同一条准入与投递路径。
 *
 * <p><b>线程</b>（§7.3）：Dubbo 业务线程只读准入闸（原子量）、占在途名额、把任务投递进 battle 逻辑线程，立即返回 future，<b>不阻塞、不碰房间</b>；
 * 房间服务只在逻辑线程上调；future 在回复执行器（{@code battle-rpc-reply}）上完成，Triple 的序列化与写出不占逻辑线程（同 scene 的
 * {@code scene-asset-reply}）。回复执行器已停（停机末尾）时退回到完成线程上直接完成，保证每次调用恰好释放一次名额、恰好计一次指标。
 *
 * <p><b>createBattle 的节点级准入</b>（基线三道关，Agones 许可不移植，§11 N4；任一不过即 {@code NOT_ALLOCATABLE}，保证零副作用）：
 * <ol>
 *   <li>Dubbo 线程：准入闸不是 OPEN → {@code not_started} / {@code closed}；</li>
 *   <li>在途数 ≥ {@code xm.battle.rpc-max-inflight} → {@code overloaded}（Java 独有，§11 N20）；</li>
 *   <li>投递被拒（逻辑线程正在关闭）→ {@code closed}；</li>
 *   <li>逻辑线程上复核准入闸：不是 OPEN → {@code closed_in_loop}（停机的「关闸 + 作废全部房间」在同一个逻辑任务里，排在它后面的建房必然看到 CLOSED）；
 *       否则交给 {@link BattleRoomService#createBattle} → {@code ADMITTED} + 契约应答字节（业务拒绝在应答的 error_message 里）。</li>
 * </ol>
 * 提供方从不产出 {@code UNSPECIFIED}。
 *
 * <p><b>其余四个方法</b>：业务错误都在应答的 error_message 里，Dubbo 层恒成功（同基线 {@code node.cpp:127-209}）；在途超限、投递被拒、处理中抛异常时
 * future 异常完成（调用方按传输失败处理）。它们不看准入闸：停机作废之后房间表为空，补签自然回 1005、观众登记回 1004（§7.11 第 4 步）。
 *
 * <p><b>指标</b>：每次调用恰好计一次 {@code xm_battle_rpc_seconds{method, result}}（含逻辑线程排队）；createBattle 的 {@code NOT_ALLOCATABLE}
 * 另计 {@code xm_battle_room_creates_total{result=not_allocatable}}（其余建房结局由房间计）。
 *
 * <p>线程安全：全部方法可由 Dubbo 业务线程、管理 Tomcat 线程并发调用。
 */
public final class BattleNodeServiceImpl implements BattleNodeService {

    private static final Logger log = LoggerFactory.getLogger(BattleNodeServiceImpl.class);

    /** {@code CreateBattleResult.reason}：准入闸还没打开（启动未完成）。 */
    public static final String REASON_NOT_STARTED = AdmissionPhase.NOT_STARTED.wireName();
    /** {@code CreateBattleResult.reason}：准入闸已关闭（停机 / 租约丢失），或逻辑线程拒绝投递（正在关闭）。 */
    public static final String REASON_CLOSED = AdmissionPhase.CLOSED.wireName();
    /** {@code CreateBattleResult.reason}：投递之后、执行之前准入闸被关闭（逻辑线程上复核）。 */
    public static final String REASON_CLOSED_IN_LOOP = "closed_in_loop";
    /** {@code CreateBattleResult.reason}：控制面在途调用已满（{@code xm.battle.rpc-max-inflight}）。 */
    public static final String REASON_OVERLOADED = "overloaded";

    /** 拒绝日志的采样间隔（第一次必打，之后每 256 次一条；拒绝量由外部流量决定，逐条打会放大成日志风暴）。 */
    private static final long LOG_SAMPLE_MASK = 0xFF;

    private final AdmissionGate admission;
    private final BattleScheduler logic;
    private final BattleRoomService rooms;
    private final Executor replies;
    private final int maxInFlight;
    private final Semaphore permits;
    private final BattleMetrics metrics;
    private final LongSupplier nanoClock;
    private final Map<RpcMethod, AtomicLong> overloads = new EnumMap<>(RpcMethod.class);
    private final Map<String, AtomicLong> notAllocatable = Map.of(
            REASON_NOT_STARTED, new AtomicLong(), REASON_CLOSED, new AtomicLong(),
            REASON_CLOSED_IN_LOOP, new AtomicLong(), REASON_OVERLOADED, new AtomicLong());

    /**
     * @param admission   建房准入闸（与 {@code BattleNode} 共用）
     * @param logic       battle 逻辑线程（房间服务的唯一执行线程）
     * @param rooms       房间服务（只在逻辑线程上调）
     * @param replies     回复执行器（{@code battle-rpc-reply}；不得是逻辑线程）
     * @param maxInFlight 在途上限（{@code xm.battle.rpc-max-inflight}，≥ 1）
     */
    public BattleNodeServiceImpl(AdmissionGate admission, BattleScheduler logic, BattleRoomService rooms, Executor replies,
                                 int maxInFlight, BattleMetrics metrics) {
        this(admission, logic, rooms, replies, maxInFlight, metrics, System::nanoTime);
    }

    BattleNodeServiceImpl(AdmissionGate admission, BattleScheduler logic, BattleRoomService rooms, Executor replies,
                          int maxInFlight, BattleMetrics metrics, LongSupplier nanoClock) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("控制面在途上限至少为 1: " + maxInFlight);
        }
        this.admission = Objects.requireNonNull(admission, "admission");
        this.logic = Objects.requireNonNull(logic, "logic");
        this.rooms = Objects.requireNonNull(rooms, "rooms");
        this.replies = Objects.requireNonNull(replies, "replies");
        this.maxInFlight = maxInFlight;
        this.permits = new Semaphore(maxInFlight);
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        for (RpcMethod method : RpcMethod.values()) {
            overloads.put(method, new AtomicLong());
        }
    }

    // ---------------------------------------------------------------- BattleNodeService（Dubbo）

    /** match 建房（{@link RoomOrigin#MATCH}）。 */
    @Override
    public CompletableFuture<CreateBattleResult> createBattle(CreateBattleRequest request) {
        return createBattle(request, RoomOrigin.MATCH);
    }

    @Override
    public CompletableFuture<Empty> destroyBattle(DestroyBattleRequest request) {
        return submit(RpcMethod.DESTROY_BATTLE, request, req -> {
            rooms.destroyBattle(req);
            return Empty.getDefaultInstance();
        }, response -> RpcResult.OK);
    }

    @Override
    public CompletableFuture<IssueBattleTicketResponse> issueBattleTicket(IssueBattleTicketRequest request) {
        return submit(RpcMethod.ISSUE_BATTLE_TICKET, request, rooms::issueBattleTicket,
                response -> resultOf(response.hasErrorMessage(), response.getErrorMessage()));
    }

    @Override
    public CompletableFuture<AddObserverResponse> addObserver(AddObserverRequest request) {
        return submit(RpcMethod.ADD_OBSERVER, request, rooms::addObserver,
                response -> resultOf(response.hasErrorMessage(), response.getErrorMessage()));
    }

    @Override
    public CompletableFuture<Empty> removeObserver(RemoveObserverRequest request) {
        return submit(RpcMethod.REMOVE_OBSERVER, request, req -> {
            rooms.removeObserver(req);
            return Empty.getDefaultInstance();
        }, response -> RpcResult.OK);
    }

    // ---------------------------------------------------------------- 进程内入口（dev 管理接口）

    /**
     * 建房，带房间来源（dev 管理接口传 {@link RoomOrigin#DEV}：照常推送、照常补发确认，但永不投递结算与结果事件，§7.12）。
     * 准入判定与投递路径同 Dubbo 入口。
     */
    public CompletableFuture<CreateBattleResult> createBattle(CreateBattleRequest request, RoomOrigin origin) {
        long startedAt = nanoClock.getAsLong();
        if (request == null || origin == null) {
            metrics.rpc(RpcMethod.CREATE_BATTLE, RpcResult.ERROR, nanoClock.getAsLong() - startedAt);
            return CompletableFuture.failedFuture(new IllegalArgumentException("createBattle 缺少请求或房间来源"));
        }
        // 第 1 道：Dubbo 线程上读准入闸（闸没开就连名额都不占）
        AdmissionPhase phase = admission.phase();
        if (phase != AdmissionPhase.OPEN) {
            return CompletableFuture.completedFuture(notAllocatable(request, phase.wireName(), startedAt));
        }
        // 第 2 道：在途上限（不排队进逻辑线程：逻辑线程的任务队列无界，积压会把直连 I/O 与回合计时挤在后面）
        if (!permits.tryAcquire()) {
            return CompletableFuture.completedFuture(notAllocatable(request, REASON_OVERLOADED, startedAt));
        }
        CompletableFuture<CreateBattleResult> reply = new CompletableFuture<>();
        try {
            // 第 3、4 道：投递进逻辑线程，在那里复核准入闸后才交给房间服务
            logic.execute(() -> {
                CreateBattleResult result;
                RpcResult outcome;
                try {
                    if (!admission.isOpen()) {
                        result = notAllocatableResult(REASON_CLOSED_IN_LOOP);
                        outcome = RpcResult.NOT_ALLOCATABLE;
                    } else {
                        CreateBattleResponse response = rooms.createBattle(request, origin);
                        result = CreateBattleResult.newBuilder()
                                .setAdmission(BattleAdmission.BATTLE_ADMISSION_ADMITTED)
                                .setResponse(response.toByteString())
                                .build();
                        outcome = resultOf(response.hasErrorMessage(), response.getErrorMessage());
                    }
                } catch (Throwable t) {
                    completeOffLogic(() -> finishCreate(request, reply, null, null, t, startedAt));
                    return;
                }
                CreateBattleResult done = result;
                RpcResult doneOutcome = outcome;
                completeOffLogic(() -> finishCreate(request, reply, done, doneOutcome, null, startedAt));
            });
        } catch (RejectedExecutionException e) {
            permits.release();
            return CompletableFuture.completedFuture(notAllocatable(request, REASON_CLOSED, startedAt));
        }
        return reply;
    }

    /** 当前在途调用数（已占名额、还没完成）。任何线程可调。 */
    public int inFlight() {
        return maxInFlight - permits.availablePermits();
    }

    // ---------------------------------------------------------------- 内部

    private void finishCreate(CreateBattleRequest request, CompletableFuture<CreateBattleResult> reply,
                              CreateBattleResult result, RpcResult outcome, Throwable error, long startedAt) {
        permits.release();
        long elapsed = nanoClock.getAsLong() - startedAt;
        if (error != null) {
            metrics.rpc(RpcMethod.CREATE_BATTLE, RpcResult.ERROR, elapsed);
            log.error("createBattle 处理中抛出异常（调用方按传输失败处理，结局未知） battle_id={}",
                    Long.toUnsignedString(request.getBattleId()), error);
            reply.completeExceptionally(error);
            return;
        }
        if (outcome == RpcResult.NOT_ALLOCATABLE) {
            countNotAllocatable(request, result.getReason(), elapsed);
        } else {
            metrics.rpc(RpcMethod.CREATE_BATTLE, outcome, elapsed);
        }
        reply.complete(result);
    }

    private CreateBattleResult notAllocatable(CreateBattleRequest request, String reason, long startedAt) {
        countNotAllocatable(request, reason, nanoClock.getAsLong() - startedAt);
        return notAllocatableResult(reason);
    }

    private void countNotAllocatable(CreateBattleRequest request, String reason, long elapsedNanos) {
        metrics.roomCreate(CreateResult.NOT_ALLOCATABLE);
        metrics.rpc(RpcMethod.CREATE_BATTLE, RpcResult.NOT_ALLOCATABLE, elapsedNanos);
        AtomicLong counter = notAllocatable.get(reason);
        long n = counter == null ? 0 : counter.getAndIncrement();
        if ((n & LOG_SAMPLE_MASK) == 0) {
            log.warn("createBattle 拒绝：本节点不可分配（NOT_ALLOCATABLE，match 不发 destroy、换节点重试一次；同原因每 256 次采样一条） "
                            + "battle_id={} reason={} admission={} in_flight={}/{} 累计={}",
                    Long.toUnsignedString(request.getBattleId()), reason, admission.phase().wireName(), inFlight(),
                    maxInFlight, n + 1);
        }
    }

    private static CreateBattleResult notAllocatableResult(String reason) {
        return CreateBattleResult.newBuilder()
                .setAdmission(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE)
                .setReason(reason)
                .build();
    }

    /** 应答的指标结局：带非 0 的 error_message → business_error，否则 ok（含建房幂等命中）。 */
    private static RpcResult resultOf(boolean hasError, TipInfoMessage error) {
        return hasError && error.getId() != 0 ? RpcResult.BUSINESS_ERROR : RpcResult.OK;
    }

    /** 其余四个方法的公共路径：占名额 → 投递 → 逻辑线程上处理 → 回复执行器上完成。 */
    private <Q, R> CompletableFuture<R> submit(RpcMethod method, Q request, Function<Q, R> handler,
                                               Function<R, RpcResult> classify) {
        long startedAt = nanoClock.getAsLong();
        if (request == null) {
            metrics.rpc(method, RpcResult.ERROR, nanoClock.getAsLong() - startedAt);
            return CompletableFuture.failedFuture(new IllegalArgumentException(method.label() + " 缺少请求"));
        }
        if (!permits.tryAcquire()) {
            metrics.rpc(method, RpcResult.ERROR, nanoClock.getAsLong() - startedAt);
            logOverload(method);
            return CompletableFuture.failedFuture(new RejectedExecutionException(
                    "battle control plane overloaded (max in-flight " + maxInFlight + ")"));
        }
        CompletableFuture<R> reply = new CompletableFuture<>();
        try {
            logic.execute(() -> {
                R response;
                try {
                    response = handler.apply(request);
                } catch (Throwable t) {
                    completeOffLogic(() -> finish(method, reply, null, t, classify, startedAt));
                    return;
                }
                R done = response;
                completeOffLogic(() -> finish(method, reply, done, null, classify, startedAt));
            });
        } catch (RejectedExecutionException e) {
            permits.release();
            metrics.rpc(method, RpcResult.ERROR, nanoClock.getAsLong() - startedAt);
            return CompletableFuture.failedFuture(e);
        }
        return reply;
    }

    private <R> void finish(RpcMethod method, CompletableFuture<R> reply, R response, Throwable error,
                            Function<R, RpcResult> classify, long startedAt) {
        permits.release();
        long elapsed = nanoClock.getAsLong() - startedAt;
        if (error != null) {
            metrics.rpc(method, RpcResult.ERROR, elapsed);
            log.error("{} 处理中抛出异常（调用方按传输失败处理）", method.label(), error);
            reply.completeExceptionally(error);
            return;
        }
        RpcResult result;
        try {
            result = classify.apply(response);
        } catch (RuntimeException e) {
            result = RpcResult.ERROR;
        }
        metrics.rpc(method, result, elapsed);
        reply.complete(response);
    }

    /** 在回复执行器上执行；回复执行器已停时退回到当前线程（只在停机末尾、逻辑线程已停之后发生）。 */
    private void completeOffLogic(Runnable completion) {
        try {
            replies.execute(completion);
        } catch (RejectedExecutionException e) {
            completion.run();
        }
    }

    private void logOverload(RpcMethod method) {
        long n = overloads.get(method).getAndIncrement();
        if ((n & LOG_SAMPLE_MASK) == 0) {
            log.warn("battle 控制面在途已满，{} 按传输失败拒绝（每 256 次采样一条） max_in_flight={} 累计={}", method.label(),
                    maxInFlight, n + 1);
        }
    }
}
