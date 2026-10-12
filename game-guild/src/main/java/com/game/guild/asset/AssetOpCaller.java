package com.game.guild.asset;

import com.game.api.asset.AssetRpc;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.common.deadline.Deadline;
import com.game.discovery.location.SceneAssetLocator.Failure;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.NoHolder;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import com.game.guild.asset.AssetOpMetrics.RequeryResult;
import com.game.guild.asset.AssetOpMetrics.RpcOutcome;
import com.game.guild.rules.GuildLimits;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 把一条资产指令投出去，并拿回一个可信的结局（基线 {@code go/shared/assetop/caller.go:73-229}；guild-economy-spec §2.4、§7.5）。
 *
 * <p>契约（同 {@code Caller.Do}）：
 * <ul>
 *   <li>返回的 future <b>从不异常完成</b>；{@link Delivery#answered()} 时结果一定可以交给 {@link AssetOpDecisions#decide}（可能是未 durable 的中间态）；</li>
 *   <li>此刻没有节点持有该玩家（不在线 / 重连租约 / 已登出 / 节点不在目录 / 节点不提供资产通道）→ 本地合成 NOT_HERE（{@code local = true}），
 *       <b>不是错误</b>，计 {@code rpc_total{outcome="no_location"}}；</li>
 *   <li>定位故障（Redis 出错、记录 / 目录条目损坏）与传输失败 → {@link Delivery#error()} 为 {@link AssetDeliveryException}，调用方按 Retry；</li>
 *   <li>scene 回 NOT_HERE：重新定位，<b>只有换了节点地址</b>才再调一次（同节点重调只会得到同样的答复）；重定位得到「没人持有」或故障时返回
 *       scene 的 NOT_HERE（{@code local = false}，不触发离线读账本，caller.go:118-129）；</li>
 *   <li>APPLIED / REJECTED 但未 durable：用<b>同一请求</b>按 100 / 200 / 400 ms 重查（每次重签）；任何「没等到」（预算不够、重查出错、重查拿到非终结
 *       结局）都返回<b>最后一次</b>结果，由上层排 AWAIT_DURABLE；结局变了 → {@link Delivery#error()} 为 {@link AssetOutcomeFlipException}
 *       （违反 I2，{@code outcome_flip_total}），结果是最新的那次答复；</li>
 *   <li>请求不会被就地修改：每次实际发包都现签一份副本（{@link AssetOpSigner}）。</li>
 * </ul>
 *
 * <p><b>线程</b>：全程异步，不阻塞调用线程（同步投递不占 guild-worker，Q5）：定位是异步 Redis，调用是 Dubbo 异步，重查的等待由 {@code timer}
 * 定时（不 sleep）；回调里只做签名与挂下一步。单次调用的上限 = min(800 ms, 剩余投递预算)。线程安全。
 */
public final class AssetOpCaller implements AssetOpProcessor.Applier {

    /** 玩家 → 持有者节点的资产通道地址（生产 {@code SceneAssetLocator::resolveAsync}；future 不得异常完成，异常完成也按故障处理）。 */
    @FunctionalInterface
    public interface Locator {
        CompletableFuture<Resolution> resolveAsync(long playerId);
    }

    /** 发一次资产调用（生产 {@code SceneAssetOpClients::call}）；future 异常完成 = 传输失败。不得阻塞调用线程。 */
    @FunctionalInterface
    public interface Transport {
        CompletableFuture<AssetOpResponse> call(SceneAssetEndpoint endpoint, AssetRpc rpc, AssetOpRequest request,
                                                Duration timeout);
    }

    /**
     * 一次投递的结局（二选一，外加翻转）：
     * <ul>
     *   <li>{@code result != null && error == null}：拿到了答复（含本地 NOT_HERE）；</li>
     *   <li>{@code result == null && error != null}：没拿到 scene 的答复（{@link AssetDeliveryException}）；</li>
     *   <li>{@code result != null && error instanceof AssetOutcomeFlipException}：结局翻转，result 是最新那次答复。</li>
     * </ul>
     */
    public record Delivery(AssetOpResult result, Throwable error) {

        public Delivery {
            if (result == null && error == null) {
                throw new IllegalArgumentException("投递结局既没有结果也没有错误");
            }
        }

        public static Delivery answered(AssetOpResult result) {
            return new Delivery(Objects.requireNonNull(result, "result"), null);
        }

        public static Delivery failed(Throwable error) {
            return new Delivery(null, Objects.requireNonNull(error, "error"));
        }

        static Delivery flipped(AssetOpResult latest, AssetOutcomeFlipException error) {
            return new Delivery(latest, error);
        }

        /** 拿到了可以交给 decide 的答复（没有错误）。 */
        public boolean answered() {
            return error == null;
        }

        /** 根本没拿到 scene 的答复（传输 / 定位故障）：重排时不覆盖行上的答复列（E12）。 */
        public boolean noAnswer() {
            return result == null;
        }
    }

    private final Locator locator;
    private final Transport transport;
    private final AssetOpSigner signer;
    private final ScheduledExecutorService timer;
    private final LongSupplier clockMs;
    private final AssetOpMetrics metrics;
    private final long callTimeoutMs;
    private final List<Long> requeryDelaysMs;

    /**
     * @param timer   重查等待的定时器（只做「到点完成一个 future」，回调里不阻塞）
     * @param clockMs 签名时间戳的时钟（Unix 毫秒）
     */
    public AssetOpCaller(Locator locator, Transport transport, AssetOpSigner signer, ScheduledExecutorService timer,
                         LongSupplier clockMs, AssetOpMetrics metrics) {
        this(locator, transport, signer, timer, clockMs, metrics, GuildLimits.ASSET_CALL_TIMEOUT_MS,
                GuildLimits.ASSET_REQUERY_DELAYS_MS);
    }

    /** 测试用：可改单次超时与重查序列（空序列 = 不重查）。 */
    AssetOpCaller(Locator locator, Transport transport, AssetOpSigner signer, ScheduledExecutorService timer,
                  LongSupplier clockMs, AssetOpMetrics metrics, long callTimeoutMs, List<Long> requeryDelaysMs) {
        this.locator = Objects.requireNonNull(locator, "locator");
        this.transport = Objects.requireNonNull(transport, "transport");
        // 没有签名的请求会被 scene 判 UNKNOWN 且不记账，行会一直卡着：构造时就要求签名器（ErrNoSigner，caller.go:89-91）
        this.signer = Objects.requireNonNull(signer, "signer");
        this.timer = Objects.requireNonNull(timer, "timer");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        if (callTimeoutMs < 1) {
            throw new IllegalArgumentException("资产调用单次超时必须为正: " + callTimeoutMs);
        }
        this.callTimeoutMs = callTimeoutMs;
        this.requeryDelaysMs = List.copyOf(requeryDelaysMs);
    }

    /**
     * 投递一次并返回结局（future 从不异常完成）。
     *
     * @param budget 这一次投递的总预算（单次调用、重查等待与重查调用都在它之内；落库的 700 ms 不在这里）
     */
    @Override
    public CompletableFuture<Delivery> deliver(AssetRpc rpc, AssetOpRequest request, Deadline budget) {
        if (rpc == null || request == null || budget == null) {
            return CompletableFuture.completedFuture(Delivery.failed(
                    new IllegalArgumentException("资产投递缺参数 rpc=" + rpc + " request=" + (request != null))));
        }
        Call call = new Call(rpc, request, budget);
        CompletableFuture<Delivery> out;
        try {
            out = locate(request.getPlayerId(), budget).thenCompose(resolution -> switch (resolution) {
                case NoHolder ignored -> {
                    // 没人持有：本地合成 NOT_HERE。不记账、不终结，交给重投循环退避（caller.go:96-111）
                    metrics.noLocation(request.getStreamValue(), rpc);
                    yield CompletableFuture.completedFuture(Delivery.answered(AssetOpResult.localNotHere()));
                }
                case Failure failure -> CompletableFuture.completedFuture(Delivery.failed(
                        new AssetDeliveryException("定位持有者失败: " + failure.reason())));
                case Found found -> call.invoke(found.endpoint())
                        .thenCompose(first -> call.afterFirst(found.endpoint(), first));
            });
        } catch (RuntimeException e) {
            out = CompletableFuture.failedFuture(e);
        }
        // 兜底：任何意外（实现 bug）都折成「没拿到答复」，绝不让调用方的链断在异常上
        return out.handle((delivery, error) -> error != null
                ? Delivery.failed(new AssetDeliveryException("资产投递内部错误", unwrap(error)))
                : delivery);
    }

    /**
     * 定位持有者，受本次投递预算约束（基线 Resolve(ctx) 在带截止的 apply ctx 下跑）：定位是两次 Redis 读，Redis 卡住时单条命令按
     * Redisson 的超时与重试最多阻塞数秒；不约束的话同步捐献 / 兑换会拖过 gate 的 5 s，客户端把已提交的预留当失败再点一次。
     * 预算已用完直接按「定位失败」（不发 Redis 读）；超时按「定位超时」——都是没拿到答复，交给重投循环。
     */
    private CompletableFuture<Resolution> locate(long playerId, Deadline budget) {
        long remaining = budget.remainingMillis();
        if (remaining <= 0) {
            return CompletableFuture.completedFuture(new Failure("定位前投递预算已用完"));
        }
        CompletableFuture<Resolution> resolution;
        try {
            resolution = locator.resolveAsync(playerId);
        } catch (RuntimeException e) {
            resolution = CompletableFuture.failedFuture(e);
        }
        if (resolution == null) {
            return CompletableFuture.completedFuture(new Failure("定位返回了空的 future"));
        }
        return resolution.copy()
                .completeOnTimeout(new Failure("定位超时（" + remaining + " ms 内没拿到结果）"), remaining, TimeUnit.MILLISECONDS)
                .handle((r, error) -> error != null || r == null
                        ? new Failure("定位出错: " + (error == null ? "空结果" : unwrap(error)))
                        : r);
    }

    /** 一次投递的状态（目标请求、预算）；只在本次投递的回调链里用。 */
    private final class Call {
        private final AssetRpc rpc;
        private final AssetOpRequest request;
        private final Deadline budget;

        Call(AssetRpc rpc, AssetOpRequest request, Deadline budget) {
            this.rpc = rpc;
            this.request = request;
            this.budget = budget;
        }

        /** 第一次答复之后：NOT_HERE 换节点；未 durable 进重查。 */
        CompletableFuture<Delivery> afterFirst(SceneAssetEndpoint target, Delivery first) {
            if (!first.answered()) {
                return CompletableFuture.completedFuture(first);
            }
            AssetOpResult res = first.result();
            if (res.outcome() == com.game.api.proto.AssetOutcome.ASSET_OUTCOME_NOT_HERE) {
                // scene 说人不在这儿：位置可能刚过期。重新定位，只有换了节点（地址）才重调一次（caller.go:118-129）
                return locate(request.getPlayerId(), budget).thenCompose(next -> {
                    if (next instanceof Found found && !found.endpoint().address().equals(target.address())) {
                        return invoke(found.endpoint()).thenCompose(second -> second.answered()
                                ? settleOrRequery(found.endpoint(), second.result())
                                : CompletableFuture.completedFuture(second));
                    }
                    return settleOrRequery(target, res);
                });
            }
            return settleOrRequery(target, res);
        }

        private CompletableFuture<Delivery> settleOrRequery(SceneAssetEndpoint target, AssetOpResult res) {
            if (!AssetOpDecisions.isSceneTerminal(res.outcome()) || res.durable()) {
                return CompletableFuture.completedFuture(Delivery.answered(res));
            }
            return requery(target, res, 0);
        }

        /**
         * 用同一请求重查，直到拿到 durable、预算用完或出错（requery，caller.go:137-171）。任何「没等到」都返回最后一次结果：上层按 AWAIT_DURABLE
         * 重排，而不是把一个未落盘的结局当成终结。
         */
        private CompletableFuture<Delivery> requery(SceneAssetEndpoint target, AssetOpResult last, int round) {
            if (round >= requeryDelaysMs.size()) {
                metrics.requery(rpc, RequeryResult.TIMEOUT);
                return CompletableFuture.completedFuture(Delivery.answered(last));
            }
            long wait = requeryDelaysMs.get(round);
            if (budget.remainingMillis() <= wait) {
                // 睡完就没有预算再发一次了（基线 sleepCtx 在 ctx 到期时返回错误 → timeout）
                metrics.requery(rpc, RequeryResult.TIMEOUT);
                return CompletableFuture.completedFuture(Delivery.answered(last));
            }
            return delay(wait).handle((ignored, error) -> error)
                    .thenCompose(delayError -> {
                        if (delayError != null) {
                            metrics.requery(rpc, RequeryResult.TIMEOUT);
                            return CompletableFuture.completedFuture(Delivery.answered(last));
                        }
                        return invoke(target).thenCompose(again -> {
                            if (!again.answered()) {
                                metrics.requery(rpc, RequeryResult.ERROR);
                                return CompletableFuture.completedFuture(Delivery.answered(last));
                            }
                            AssetOpResult res = again.result();
                            if (!AssetOpDecisions.isSceneTerminal(res.outcome())) {
                                // 重查期间换了节点 / 账本被判损坏：旧答复仍有效（结局固定），但这次拿不到落盘证据。按「没等到」处理，绝不当成翻转
                                metrics.requery(rpc, RequeryResult.TIMEOUT);
                                return CompletableFuture.completedFuture(Delivery.answered(last));
                            }
                            if (res.outcome() != last.outcome()) {
                                metrics.outcomeFlip(request.getStreamValue());
                                return CompletableFuture.completedFuture(Delivery.flipped(res, new AssetOutcomeFlipException(
                                        "资产结局翻转 stream=" + request.getStreamValue() + " seq="
                                                + Long.toUnsignedString(request.getSeq()) + " " + RpcOutcome.of(last.outcome()).label()
                                                + " -> " + RpcOutcome.of(res.outcome()).label())));
                            }
                            if (res.durable()) {
                                metrics.requery(rpc, RequeryResult.DURABLE);
                                return CompletableFuture.completedFuture(Delivery.answered(res));
                            }
                            return requery(target, res, round + 1);
                        });
                    });
        }

        /**
         * 发一次包：现签 → 调用（单次上限 min(800 ms, 剩余预算)）→ 转成结果（invoke，caller.go:177-229）。future 不异常完成。
         * 每次都重签：签名带时间戳，scene 只接受 ±300 s；重查若复用旧签名，慢路径上会被判验签失败。
         */
        CompletableFuture<Delivery> invoke(SceneAssetEndpoint target) {
            long remaining = budget.remainingMillis();
            if (remaining <= 0) {
                metrics.rpc(request.getStreamValue(), rpc, RpcOutcome.ERROR, 0);
                return CompletableFuture.completedFuture(Delivery.failed(new AssetDeliveryException(
                        "投递预算已用完，未发出 " + rpc.wireName() + " endpoint=" + target.address() + " seq="
                                + Long.toUnsignedString(request.getSeq()))));
            }
            long timeout = Math.min(callTimeoutMs, remaining);
            long start = System.nanoTime();
            CompletableFuture<AssetOpResponse> response;
            try {
                AssetOpRequest signed = signer.sign(rpc, request, clockMs.getAsLong());
                response = transport.call(target, rpc, signed, Duration.ofMillis(timeout));
                if (response == null) {
                    response = CompletableFuture.failedFuture(new IllegalStateException("传输返回了空的 future"));
                }
            } catch (RuntimeException e) {
                response = CompletableFuture.failedFuture(e);
            }
            return response.handle((resp, error) -> {
                long elapsed = System.nanoTime() - start;
                if (error != null || resp == null) {
                    metrics.rpc(request.getStreamValue(), rpc, RpcOutcome.ERROR, elapsed);
                    return Delivery.failed(new AssetDeliveryException("调用 " + rpc.wireName() + " 失败（endpoint="
                            + target.address() + " seq=" + Long.toUnsignedString(request.getSeq()) + "）",
                            error == null ? new IllegalStateException("空应答") : unwrap(error)));
                }
                AssetOpResult res = AssetOpResult.of(resp);
                metrics.rpc(request.getStreamValue(), rpc, RpcOutcome.of(res.outcome()), elapsed);
                if (res.partial()) {
                    metrics.partial(request.getStreamValue());
                }
                return Delivery.answered(res);
            });
        }
    }

    /** 到点完成的 future（定时器关了 → 异常完成，调用方按「没等到」处理）。 */
    private CompletableFuture<Void> delay(long millis) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        try {
            timer.schedule(() -> done.complete(null), millis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            done.completeExceptionally(e);
        }
        return done;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }
}
