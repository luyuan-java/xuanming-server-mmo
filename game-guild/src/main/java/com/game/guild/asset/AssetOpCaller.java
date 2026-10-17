package com.game.guild.asset;

import com.game.api.asset.AssetRpc;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.common.deadline.Deadline;
import com.game.discovery.asset.ReliableAssetDelivery;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import com.game.guild.asset.AssetOpMetrics.RequeryResult;
import com.game.guild.asset.AssetOpMetrics.RpcOutcome;
import com.game.guild.rules.GuildLimits;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.LongSupplier;

/** 帮会资产投递的业务适配；可靠定位、重签、重查算法与邮件/交易共用，帮会指标和结算接口保持原契约。 */
public final class AssetOpCaller implements AssetOpProcessor.Applier {
    @FunctionalInterface
    public interface Locator { CompletableFuture<Resolution> resolveAsync(long playerId); }
    @FunctionalInterface
    public interface Transport {
        CompletableFuture<AssetOpResponse> call(SceneAssetEndpoint endpoint, AssetRpc rpc, AssetOpRequest request, Duration timeout);
    }
    public record Delivery(AssetOpResult result, Throwable error) {
        public Delivery {
            if (result == null && error == null) throw new IllegalArgumentException("投递结局既没有结果也没有错误");
        }
        public static Delivery answered(AssetOpResult result) { return new Delivery(Objects.requireNonNull(result), null); }
        public static Delivery failed(Throwable error) { return new Delivery(null, Objects.requireNonNull(error)); }
        static Delivery flipped(AssetOpResult result, Throwable error) { return new Delivery(result, error); }
        public boolean answered() { return error == null; }
        public boolean noAnswer() { return result == null; }
    }
    private final ReliableAssetDelivery delivery;

    public AssetOpCaller(Locator locator, Transport transport, AssetOpSigner signer, ScheduledExecutorService timer,
                         LongSupplier clockMs, AssetOpMetrics metrics) {
        this(locator, transport, signer, timer, clockMs, metrics, GuildLimits.ASSET_CALL_TIMEOUT_MS,
                GuildLimits.ASSET_REQUERY_DELAYS_MS);
    }

    AssetOpCaller(Locator locator, Transport transport, AssetOpSigner signer, ScheduledExecutorService timer,
                  LongSupplier clockMs, AssetOpMetrics metrics, long callTimeoutMs, List<Long> requeryDelaysMs) {
        Objects.requireNonNull(metrics);
        delivery = new ReliableAssetDelivery(locator::resolveAsync, transport::call, signer::sign, timer, clockMs,
                new ReliableAssetDelivery.Observer() {
                    @Override public void noLocation(int stream, AssetRpc rpc) { metrics.noLocation(stream, rpc); }
                    @Override public void rpc(int stream, AssetRpc rpc, AssetOutcome outcome, boolean failed, long nanos) {
                        metrics.rpc(stream, rpc, failed ? RpcOutcome.ERROR : RpcOutcome.of(outcome), nanos);
                    }
                    @Override public void partial(int stream) { metrics.partial(stream); }
                    @Override public void outcomeFlip(int stream) { metrics.outcomeFlip(stream); }
                    @Override public void requery(AssetRpc rpc, ReliableAssetDelivery.Requery result) {
                        metrics.requery(rpc, RequeryResult.valueOf(result.name()));
                    }
                }, callTimeoutMs, requeryDelaysMs);
    }

    @Override
    public CompletableFuture<Delivery> deliver(AssetRpc rpc, AssetOpRequest request, Deadline budget) {
        return delivery.deliver(rpc, request, budget).thenApply(result -> {
            AssetOpResult response = result.response() == null ? null : new AssetOpResult(result.response().getOutcome(),
                    result.response().getReason(), result.response().getDurable(), result.response().getPartial(), result.local());
            if (result.error() instanceof ReliableAssetDelivery.OutcomeFlipException) {
                return Delivery.flipped(response, new AssetOutcomeFlipException(result.error().getMessage()));
            }
            return result.error() == null ? Delivery.answered(response)
                    : Delivery.failed(new AssetDeliveryException("资产投递不可确认", result.error()));
        });
    }
}