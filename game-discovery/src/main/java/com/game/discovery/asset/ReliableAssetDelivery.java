package com.game.discovery.asset;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.asset.SceneAssetOpClients;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.common.deadline.Deadline;
import com.game.discovery.location.SceneAssetLocator;
import com.game.discovery.location.SceneAssetLocator.Failure;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.NoHolder;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 邮件、交易和帮会共用的可靠资产投递。只定位、签名、投递和用同一操作重查；不保存钱包、分配 seq 或终结业务账。
 * APPLIED/REJECTED 必须有 scene SQL 持久化确认；未知、超时、未 durable 都由业务 outbox 保留原操作继续重投。
 * 注入的客户端和定时器由装配方关闭。本类不阻塞调用线程，返回 future 从不异常完成。
 */
public final class ReliableAssetDelivery {
    @FunctionalInterface
    public interface Locator { CompletableFuture<Resolution> resolve(long playerId); }
    @FunctionalInterface
    public interface Transport {
        CompletableFuture<AssetOpResponse> call(SceneAssetEndpoint endpoint, AssetRpc rpc, AssetOpRequest request, Duration timeout);
    }
    @FunctionalInterface
    public interface Signer { AssetOpRequest sign(AssetRpc rpc, AssetOpRequest request, long nowMs); }

    public enum Requery { DURABLE, TIMEOUT, ERROR }
    /** 指标只传固定类别与有限流号，业务方沿用自己的低基数指标。实现不得抛异常。 */
    public interface Observer {
        default void noLocation(int stream, AssetRpc rpc) { }
        default void rpc(int stream, AssetRpc rpc, AssetOutcome outcome, boolean failed, long nanos) { }
        default void partial(int stream) { }
        default void outcomeFlip(int stream) { }
        default void requery(AssetRpc rpc, Requery result) { }
    }

    /** 保留完整响应，不能在适配层丢掉托管快照或未来字段。partial 即便 durable 也只能转人工。 */
    public record Delivery(AssetOpResponse response, boolean local, Throwable error) {
        public Delivery {
            if (response == null && error == null) throw new IllegalArgumentException("资产投递没有响应或错误");
        }
        public boolean answered() { return error == null; }
        public boolean terminal() { return answered() && response.getDurable() && sceneTerminal(response.getOutcome()); }
        public boolean completed() { return terminal() && !response.getPartial() && response.getOutcome() == AssetOutcome.ASSET_OUTCOME_APPLIED; }
        public static Delivery answered(AssetOpResponse response) { return new Delivery(Objects.requireNonNull(response), false, null); }
        public static Delivery failed(Throwable error) { return new Delivery(null, false, Objects.requireNonNull(error)); }
        static Delivery noHolder() {
            return new Delivery(AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_NOT_HERE).build(), true, null);
        }
    }

    public static final class OutcomeFlipException extends IllegalStateException {
        public OutcomeFlipException() { super("同一资产操作的持久结局发生翻转"); }
    }

    private final Locator locator;
    private final Transport transport;
    private final Signer signer;
    private final ScheduledExecutorService timer;
    private final LongSupplier clockMs;
    private final Observer observer;
    private final long callTimeoutMs;
    private final List<Long> requeryDelays;

    public ReliableAssetDelivery(SceneAssetLocator locator, SceneAssetOpClients clients, String caller, String rawSecret,
                                 ScheduledExecutorService timer, LongSupplier clockMs) {
        this(locator::resolveAsync, clients::call, signer(caller, rawSecret), timer, clockMs, new Observer() { },
                800, List.of(100L, 200L, 400L));
    }

    public ReliableAssetDelivery(Locator locator, Transport transport, Signer signer, ScheduledExecutorService timer,
                                 LongSupplier clockMs, Observer observer, long callTimeoutMs, List<Long> requeryDelays) {
        this.locator = Objects.requireNonNull(locator);
        this.transport = Objects.requireNonNull(transport);
        this.signer = Objects.requireNonNull(signer);
        this.timer = Objects.requireNonNull(timer);
        this.clockMs = Objects.requireNonNull(clockMs);
        this.observer = Objects.requireNonNull(observer);
        if (callTimeoutMs < 1 || requeryDelays.stream().anyMatch(wait -> wait < 0)) throw new IllegalArgumentException("资产投递预算非法");
        this.callTimeoutMs = callTimeoutMs;
        this.requeryDelays = List.copyOf(requeryDelays);
    }

    private static Signer signer(String caller, String rawSecret) {
        String env = switch (caller) {
            case AssetOpSignatures.CALLER_GUILD -> AssetOpSignatures.SECRET_ENV_GUILD;
            case AssetOpSignatures.CALLER_TRADE -> AssetOpSignatures.SECRET_ENV_TRADE;
            case AssetOpSignatures.CALLER_MAIL -> AssetOpSignatures.SECRET_ENV_MAIL;
            default -> throw new IllegalArgumentException("未注册的资产调用方");
        };
        String secret = AssetOpSignatures.requireSecret(env, rawSecret);
        return (rpc, request, now) -> AssetOpSignatures.sign(rpc, request, caller, secret, now);
    }

    public CompletableFuture<Delivery> deliver(AssetRpc rpc, AssetOpRequest request, Deadline budget) {
        if (rpc == null || request == null || budget == null) return CompletableFuture.completedFuture(
                Delivery.failed(new IllegalArgumentException("资产投递缺参数")));
        Call call = new Call(rpc, request, budget);
        try {
            return locate(request.getPlayerId(), budget).thenCompose(resolution -> switch (resolution) {
                case NoHolder ignored -> {
                    observer.noLocation(request.getStreamValue(), rpc);
                    yield CompletableFuture.completedFuture(Delivery.noHolder());
                }
                case Failure failure -> CompletableFuture.completedFuture(Delivery.failed(new IllegalStateException("定位资产持有者失败")));
                case Found found -> call.invoke(found.endpoint()).thenCompose(first -> call.afterFirst(found.endpoint(), first));
            }).handle((result, error) -> error == null ? result : Delivery.failed(unwrap(error)));
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(Delivery.failed(error));
        }
    }

    private CompletableFuture<Resolution> locate(long playerId, Deadline budget) {
        long remaining = budget.remainingMillis();
        if (remaining <= 0) return CompletableFuture.completedFuture(new Failure("资产投递预算已用完"));
        try {
            var pending = locator.resolve(playerId);
            if (pending == null) return CompletableFuture.completedFuture(new Failure("资产定位返回空future"));
            return pending.copy().completeOnTimeout(new Failure("资产定位超时"), remaining, TimeUnit.MILLISECONDS)
                    .handle((value, error) -> error != null || value == null ? new Failure("资产定位不可确认") : value);
        } catch (RuntimeException error) {
            return CompletableFuture.completedFuture(new Failure("资产定位不可确认"));
        }
    }

    private final class Call {
        private final AssetRpc rpc;
        private final AssetOpRequest request;
        private final Deadline budget;
        Call(AssetRpc rpc, AssetOpRequest request, Deadline budget) { this.rpc = rpc; this.request = request; this.budget = budget; }

        CompletableFuture<Delivery> afterFirst(SceneAssetEndpoint target, Delivery first) {
            if (!first.answered()) return CompletableFuture.completedFuture(first);
            if (first.response().getOutcome() == AssetOutcome.ASSET_OUTCOME_NOT_HERE) {
                return locate(request.getPlayerId(), budget).thenCompose(next -> {
                    if (next instanceof Found found && !found.endpoint().address().equals(target.address())) {
                        return invoke(found.endpoint()).thenCompose(second -> settle(found.endpoint(), second));
                    }
                    return settle(target, first);
                });
            }
            return settle(target, first);
        }

        CompletableFuture<Delivery> settle(SceneAssetEndpoint target, Delivery response) {
            if (!response.answered() || !sceneTerminal(response.response().getOutcome()) || response.response().getDurable()) {
                return CompletableFuture.completedFuture(response);
            }
            return requery(target, response, 0);
        }

        CompletableFuture<Delivery> requery(SceneAssetEndpoint target, Delivery last, int round) {
            if (round >= requeryDelays.size() || budget.remainingMillis() <= requeryDelays.get(round)) {
                observer.requery(rpc, Requery.TIMEOUT);
                return CompletableFuture.completedFuture(last);
            }
            var delayed = new CompletableFuture<Void>();
            try { timer.schedule(() -> delayed.complete(null), requeryDelays.get(round), TimeUnit.MILLISECONDS); }
            catch (RuntimeException error) { delayed.completeExceptionally(error); }
            return delayed.handle((ignored, error) -> error).thenCompose(delayError -> {
                if (delayError != null) { observer.requery(rpc, Requery.TIMEOUT); return CompletableFuture.completedFuture(last); }
                return invoke(target).thenCompose(again -> {
                    if (!again.answered()) { observer.requery(rpc, Requery.ERROR); return CompletableFuture.completedFuture(last); }
                    AssetOpResponse response = again.response();
                    if (!sceneTerminal(response.getOutcome())) { observer.requery(rpc, Requery.TIMEOUT); return CompletableFuture.completedFuture(last); }
                    if (response.getOutcome() != last.response().getOutcome()) {
                        observer.outcomeFlip(request.getStreamValue());
                        return CompletableFuture.completedFuture(new Delivery(response, false, new OutcomeFlipException()));
                    }
                    if (response.getDurable()) { observer.requery(rpc, Requery.DURABLE); return CompletableFuture.completedFuture(again); }
                    return requery(target, again, round + 1);
                });
            });
        }

        CompletableFuture<Delivery> invoke(SceneAssetEndpoint target) {
            long remaining = budget.remainingMillis();
            if (remaining <= 0) return CompletableFuture.completedFuture(Delivery.failed(new IllegalStateException("资产投递预算已用完")));
            long timeout = Math.min(callTimeoutMs, remaining);
            long start = System.nanoTime();
            CompletableFuture<AssetOpResponse> response;
            try {
                response = transport.call(target, rpc, signer.sign(rpc, request, clockMs.getAsLong()), Duration.ofMillis(timeout));
                if (response == null) response = CompletableFuture.failedFuture(new IllegalStateException("空资产响应future"));
            } catch (RuntimeException error) { response = CompletableFuture.failedFuture(error); }
            return response.copy().orTimeout(timeout, TimeUnit.MILLISECONDS).handle((value, error) -> {
                if (error != null || value == null) {
                    observer.rpc(request.getStreamValue(), rpc, null, true, System.nanoTime() - start);
                    return Delivery.failed(error == null ? new IllegalStateException("空资产响应") : unwrap(error));
                }
                observer.rpc(request.getStreamValue(), rpc, value.getOutcome(), false, System.nanoTime() - start);
                if (value.getPartial()) observer.partial(request.getStreamValue());
                return Delivery.answered(value);
            });
        }
    }

    private static boolean sceneTerminal(AssetOutcome outcome) {
        return outcome == AssetOutcome.ASSET_OUTCOME_APPLIED || outcome == AssetOutcome.ASSET_OUTCOME_REJECTED;
    }

    private static Throwable unwrap(Throwable error) {
        while ((error instanceof java.util.concurrent.CompletionException || error instanceof java.util.concurrent.ExecutionException)
                && error.getCause() != null) error = error.getCause();
        return error;
    }
}
