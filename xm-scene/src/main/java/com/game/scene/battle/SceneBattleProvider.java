package com.game.scene.battle;

import com.game.api.SceneBattleService;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.game.proto.BattleConfirmedEvent;
import com.game.proto.BattleSettlementEvent;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.scene.metrics.SceneBattleMetrics;
import com.game.scene.metrics.SceneBattleMetrics.RpcMethod;
import com.game.scene.metrics.SceneBattleMetrics.RpcResult;
import com.game.table.CommonErrorTip;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code SceneBattleService} 的跨进程提供方（Dubbo Triple，与资产通道同一个端口、group {@code scene-battle}、{@code register = false}；
 * scene-battle-spec §7.3，D1）。照 {@code SceneAssetOpProvider} 的做法，在 Dubbo 线程上依次做：
 * <ol>
 *   <li>在途名额（{@code xm.scene.battle-rpc-max-inflight}，与资产通道各自独立）：超出回 OVERLOADED（保证没进逻辑线程、零副作用）；</li>
 *   <li>实例核对：{@code target_instance_id} 与本进程不符 → NOT_HERE，不进逻辑线程（等价基线 Kafka 的 target_instance_id 过滤）；</li>
 *   <li>解析 body：失败时备战回 HANDLED + {@code {1005}}，结算回 HANDLED + DISCARDED，其余 HANDLED，都打 ERROR；</li>
 *   <li>投递逻辑线程（{@link PlayerBattleService}）：被拒 = 逻辑线程已停 → future 异常完成（调用方按传输失败处理）。</li>
 * </ol>
 * 应答在回写线程池（{@code scene-asset-reply}，与资产通道共用）上完成，Dubbo 的序列化不占逻辑线程。线程安全。
 */
public final class SceneBattleProvider implements SceneBattleService {

    private static final Logger log = LoggerFactory.getLogger(SceneBattleProvider.class);

    private static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;

    private final Supplier<PlayerBattleService> service;
    private final String instanceId;
    private final Executor logic;
    private final int maxInFlight;
    private final Semaphore permits;
    private final Executor replies;
    private final SceneBattleMetrics metrics;
    private final AtomicLong overloads = new AtomicLong();

    /**
     * @param service     逻辑线程上的业务（节点没启动好时为 null）
     * @param instanceId  本进程实例 id（节点目录里的那个）
     * @param logic       投递到场景逻辑线程
     * @param maxInFlight 在途上限（≥ 1）
     * @param replies     回写线程池（不得是场景逻辑线程）
     */
    public SceneBattleProvider(Supplier<PlayerBattleService> service, String instanceId, Executor logic, int maxInFlight,
                               Executor replies, SceneBattleMetrics metrics) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("战斗 RPC 在途上限至少为 1: " + maxInFlight);
        }
        this.service = Objects.requireNonNull(service, "service");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.logic = Objects.requireNonNull(logic, "logic");
        this.maxInFlight = maxInFlight;
        this.permits = new Semaphore(maxInFlight);
        this.replies = Objects.requireNonNull(replies, "replies");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public CompletableFuture<SceneBattleReply> prepareBattle(SceneBattleCall call) {
        return submit(RpcMethod.PREPARE, call, (svc, body) -> {
            PrepareBattleRequest request;
            try {
                request = PrepareBattleRequest.parseFrom(body.getBody());
            } catch (InvalidProtocolBufferException e) {
                log.error("备战请求体解析失败 player={}", Long.toUnsignedString(body.getPlayerId()));
                return CompletableFuture.completedFuture(handled(PrepareBattleResponse.newBuilder()
                        .setErrorMessage(com.game.proto.TipInfoMessage.newBuilder().setId(INVALID_PARAMETER)).build()));
            }
            return onLogic(() -> svc.prepare(request).thenApply(SceneBattleProvider::handled));
        });
    }

    @Override
    public CompletableFuture<SceneBattleReply> cancelBattlePrepare(SceneBattleCall call) {
        return submit(RpcMethod.CANCEL, call, (svc, body) -> {
            CancelBattlePrepareRequest request;
            try {
                request = CancelBattlePrepareRequest.parseFrom(body.getBody());
            } catch (InvalidProtocolBufferException e) {
                log.error("取消备战请求体解析失败 player={}", Long.toUnsignedString(body.getPlayerId()));
                return CompletableFuture.completedFuture(status(SceneBattleStatus.SCENE_BATTLE_HANDLED));
            }
            return onLogic(() -> svc.cancel(request).thenApply(ignored -> status(SceneBattleStatus.SCENE_BATTLE_HANDLED)));
        });
    }

    @Override
    public CompletableFuture<SceneBattleReply> confirmBattle(SceneBattleCall call) {
        return submit(RpcMethod.CONFIRM, call, (svc, body) -> {
            BattleConfirmedEvent event;
            try {
                event = BattleConfirmedEvent.parseFrom(body.getBody());
            } catch (InvalidProtocolBufferException e) {
                log.error("确认事件解析失败 player={}", Long.toUnsignedString(body.getPlayerId()));
                return CompletableFuture.completedFuture(status(SceneBattleStatus.SCENE_BATTLE_HANDLED));
            }
            return onLogic(() -> {
                svc.confirm(event);
                return CompletableFuture.completedFuture(status(SceneBattleStatus.SCENE_BATTLE_HANDLED));
            });
        });
    }

    @Override
    public CompletableFuture<SceneBattleReply> applySettlement(SceneBattleCall call) {
        return submit(RpcMethod.SETTLEMENT, call, (svc, body) -> {
            BattleSettlementEvent event;
            try {
                event = BattleSettlementEvent.parseFrom(body.getBody());
            } catch (InvalidProtocolBufferException e) {
                log.error("结算投递解析失败（丢弃，不销账） player={}", Long.toUnsignedString(body.getPlayerId()));
                return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED)
                        .setSettlement(SettlementDisposition.SETTLEMENT_DISCARDED).build());
            }
            return onLogic(() -> svc.deliver(body.getPlayerId(), event.getSettlement()));
        });
    }

    /** 当前在途调用数。 */
    public int inFlight() {
        return maxInFlight - permits.availablePermits();
    }

    @FunctionalInterface
    private interface Handler {
        CompletableFuture<SceneBattleReply> handle(PlayerBattleService service, SceneBattleCall call);
    }

    @FunctionalInterface
    private interface LogicTask {
        CompletableFuture<SceneBattleReply> run();
    }

    private CompletableFuture<SceneBattleReply> submit(RpcMethod method, SceneBattleCall call, Handler handler) {
        PlayerBattleService svc = service.get();
        if (svc == null || call == null) {
            metrics.rpc(method, RpcResult.ERROR);
            return CompletableFuture.failedFuture(new IllegalStateException(svc == null ? "scene battle not ready" : "empty call"));
        }
        if (!permits.tryAcquire()) {
            metrics.rpc(method, RpcResult.OVERLOADED);
            long n = overloads.getAndIncrement();
            if ((n & 0xFF) == 0) {
                log.warn("战斗 RPC 在途已满，回过载（每 256 次采样一条） method={} max_in_flight={} total={}", method, maxInFlight, n + 1);
            }
            return CompletableFuture.completedFuture(status(SceneBattleStatus.SCENE_BATTLE_OVERLOADED));
        }
        if (!instanceId.equals(call.getTargetInstanceId())) {
            permits.release();
            metrics.rpc(method, RpcResult.NOT_HERE);
            return CompletableFuture.completedFuture(status(SceneBattleStatus.SCENE_BATTLE_NOT_HERE));
        }
        CompletableFuture<SceneBattleReply> handled;
        try {
            handled = handler.handle(svc, call);
        } catch (RuntimeException e) {
            handled = CompletableFuture.failedFuture(e);
        }
        CompletableFuture<SceneBattleReply> reply = new CompletableFuture<>();
        handled.whenComplete((response, error) -> completeOffLogic(() -> {
            permits.release();
            if (error != null) {
                metrics.rpc(method, RpcResult.ERROR);
                reply.completeExceptionally(error instanceof CompletionException && error.getCause() != null ? error.getCause() : error);
            } else {
                metrics.rpc(method, resultOf(response));
                reply.complete(response);
            }
        }));
        return reply;
    }

    /** 在逻辑线程上执行（被拒 = 逻辑线程已停 → 异常完成）；任务自己返回的 future 在逻辑线程上完成。 */
    private CompletableFuture<SceneBattleReply> onLogic(LogicTask task) {
        CompletableFuture<SceneBattleReply> result = new CompletableFuture<>();
        try {
            logic.execute(() -> {
                try {
                    task.run().whenComplete((value, error) -> {
                        if (error != null) {
                            result.completeExceptionally(error);
                        } else {
                            result.complete(value);
                        }
                    });
                } catch (RuntimeException e) {
                    log.error("战斗 RPC 在逻辑线程上处理出错", e);
                    result.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    /** 在回写线程上执行；回写池已停时退回到当前线程（只在停服末尾、逻辑线程已停之后发生）。 */
    private void completeOffLogic(Runnable completion) {
        try {
            replies.execute(completion);
        } catch (RejectedExecutionException e) {
            completion.run();
        }
    }

    static RpcResult resultOf(SceneBattleReply reply) {
        return switch (reply.getStatus()) {
            case SCENE_BATTLE_HANDLED -> RpcResult.HANDLED;
            case SCENE_BATTLE_NOT_HERE -> RpcResult.NOT_HERE;
            case SCENE_BATTLE_DEFERRED -> RpcResult.DEFERRED;
            case SCENE_BATTLE_OVERLOADED -> RpcResult.OVERLOADED;
            case SCENE_BATTLE_STATUS_UNSPECIFIED, UNRECOGNIZED -> RpcResult.ERROR;
        };
    }

    private static SceneBattleReply handled(PrepareBattleResponse response) {
        return SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED).setBody(response.toByteString()).build();
    }

    private static SceneBattleReply status(SceneBattleStatus status) {
        return SceneBattleReply.newBuilder().setStatus(status).build();
    }
}
