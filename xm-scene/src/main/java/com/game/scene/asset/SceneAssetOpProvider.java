package com.game.scene.asset;

import com.game.api.SceneAssetOpService;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.AssetOpResult;
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
 * 资产通道的跨进程提供方（Dubbo Triple，{@link SceneAssetRpcServer} 按节点导出，{@code register = false}；guild-economy-spec §4.6 / E1）：
 * 把调用转给进程内入口 {@link AssetOpEndpoint}（投递到场景逻辑线程），结局经异步 future 带回。基线 C++ 的 gRPC sync 线程把请求
 * {@code runInLoop} 后 {@code future.get()} 同步等、每条在途请求占一条 poller（{@code scene_node_service.cpp:344-396}），
 * 进程默认 8 条 poller 隐式给了在途上限；Java 是异步提供方，没有这层天然闸，所以：
 * <ul>
 *   <li><b>显式在途上限</b>（{@code xm.scene.asset-op-max-inflight}，缺省 256）：超出直接回失败的 future（「过载」），不排队——
 *       逻辑线程的任务队列无界，一次积压回放（scene 恢复后各副本的老行同时到期）会把移动 / 视野帧挤在后面。调用方当传输失败、走 Retry，
 *       不会误记账。上限应 ≥ 副本数 × Workers + 同步投递并发（guild-economy-spec Q9）。</li>
 *   <li><b>回写切出逻辑线程</b>：{@link AssetOpEndpoint} 的 future 在逻辑线程上完成，Dubbo 的序列化与回写挂在我们返回的 future 上；
 *       所以这里另建一个 future、在回写线程池（{@code scene-asset-reply}）上完成它（AGENTS.md §3 线程所有权）。回写池已停（停服末尾）时
 *       退回到完成线程上直接完成——那时逻辑线程已停，不会占它；保证每次调用恰好释放一次名额、恰好计一次指标。</li>
 *   <li>未就绪（{@link AssetOpEndpoint} 还没建）、逻辑线程已停（{@link RejectedExecutionException}）、处理中抛异常：future 异常完成
 *       = 传输失败（调用方用同一 seq 重投）。</li>
 * </ul>
 * 鉴权两层：Dubbo 调用方 MAC（xm-api 的 SPI 过滤器，缺 {@code XM_DUBBO_SECRET} 导出即失败）+ 请求体 HMAC（{@link AssetOpAuth}，在逻辑线程上验）。
 *
 * <p>线程安全：三个方法由 Dubbo 业务线程并发调用。
 */
public final class SceneAssetOpProvider implements SceneAssetOpService {

    private static final Logger log = LoggerFactory.getLogger(SceneAssetOpProvider.class);

    private final Supplier<AssetOpEndpoint> endpoint;
    private final int maxInFlight;
    private final Semaphore permits;
    private final Executor replies;
    private final SceneMetrics metrics;
    private final AtomicLong overloads = new AtomicLong();

    /**
     * @param endpoint    进程内入口（节点没启动好时为 null）
     * @param maxInFlight 在途上限（≥ 1）
     * @param replies     回写线程池（不得是场景逻辑线程）
     */
    public SceneAssetOpProvider(Supplier<AssetOpEndpoint> endpoint, int maxInFlight, Executor replies,
                                SceneMetrics metrics) {
        if (maxInFlight < 1) {
            throw new IllegalArgumentException("资产通道在途上限至少为 1: " + maxInFlight);
        }
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.maxInFlight = maxInFlight;
        this.permits = new Semaphore(maxInFlight);
        this.replies = Objects.requireNonNull(replies, "replies");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    public CompletableFuture<AssetOpResponse> debit(AssetOpRequest request) {
        return submit(AssetRpc.DEBIT, request);
    }

    @Override
    public CompletableFuture<AssetOpResponse> abortDebit(AssetOpRequest request) {
        return submit(AssetRpc.ABORT_DEBIT, request);
    }

    @Override
    public CompletableFuture<AssetOpResponse> credit(AssetOpRequest request) {
        return submit(AssetRpc.CREDIT, request);
    }

    /** 当前在途调用数（已占名额、还没回写）。 */
    public int inFlight() {
        return maxInFlight - permits.availablePermits();
    }

    private CompletableFuture<AssetOpResponse> submit(AssetRpc rpc, AssetOpRequest request) {
        AssetOpEndpoint ops = endpoint.get();
        if (ops == null || request == null) {
            metrics.assetOp(rpc, AssetOpResult.ERROR);
            return CompletableFuture.failedFuture(new IllegalStateException(
                    ops == null ? "scene asset channel not ready" : "empty asset op request"));
        }
        if (!permits.tryAcquire()) {
            metrics.assetOp(rpc, AssetOpResult.OVERLOADED);
            logOverload(rpc);
            return CompletableFuture.failedFuture(new RejectedExecutionException(
                    "scene asset op overloaded (max in-flight " + maxInFlight + ")"));
        }
        CompletableFuture<AssetOpResponse> handled;
        try {
            handled = ops.submit(rpc, request);
        } catch (RuntimeException e) {
            handled = CompletableFuture.failedFuture(e);
        }
        CompletableFuture<AssetOpResponse> reply = new CompletableFuture<>();
        handled.whenComplete((response, error) -> completeOffLogic(() -> {
            permits.release();
            if (error != null) {
                metrics.assetOp(rpc, AssetOpResult.ERROR);
                reply.completeExceptionally(error instanceof CompletionException && error.getCause() != null
                        ? error.getCause() : error);
            } else {
                metrics.assetOp(rpc, resultOf(response));
                reply.complete(response);
            }
        }));
        return reply;
    }

    /** 在回写线程上执行；回写池已停时退回到当前线程（只在停服末尾、逻辑线程已停之后发生）。 */
    private void completeOffLogic(Runnable completion) {
        try {
            replies.execute(completion);
        } catch (RejectedExecutionException e) {
            completion.run();
        }
    }

    static AssetOpResult resultOf(AssetOpResponse response) {
        return switch (response.getOutcome()) {
            case ASSET_OUTCOME_APPLIED -> AssetOpResult.APPLIED;
            case ASSET_OUTCOME_REJECTED -> AssetOpResult.REJECTED;
            case ASSET_OUTCOME_RETRY -> AssetOpResult.RETRY;
            case ASSET_OUTCOME_NOT_HERE -> AssetOpResult.NOT_HERE;
            case ASSET_OUTCOME_UNKNOWN, UNRECOGNIZED -> AssetOpResult.UNKNOWN;
        };
    }

    /** 过载由外部流量决定，逐条打 WARN 会被放大成日志风暴：每 256 次采样一条。 */
    private void logOverload(AssetRpc rpc) {
        long n = overloads.getAndIncrement();
        if ((n & 0xFF) == 0) {
            log.warn("[AssetOp] 资产通道在途已满，回过载（调用方按传输失败重投；每 256 次采样一条） rpc={} max_in_flight={} total={}",
                    rpc.wireName(), maxInFlight, n + 1);
        }
    }
}
