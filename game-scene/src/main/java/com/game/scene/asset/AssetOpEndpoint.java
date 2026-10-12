package com.game.scene.asset;

import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产通道在 scene 节点上的进程内入口：任意线程调用，请求投递到场景逻辑线程处理，结局经 future 带回。
 * 跨进程传输（帮会 / 交易服务按玩家所在节点直连过来）接在这一层外面：{@link SceneAssetOpProvider}（Dubbo Triple，见 architecture.md §4.12）。
 * future 异常完成（逻辑线程已关闭、处理中抛异常）等同传输失败：调用方按结局未知、用同一 seq 重投。
 *
 * <p>注意：future 在<b>场景逻辑线程</b>上完成；跨进程的提供方必须把后续（Dubbo 序列化与回写）切到别的线程，不能挂在这个 future 上同步跑。
 */
public final class AssetOpEndpoint {

    private static final Logger log = LoggerFactory.getLogger(AssetOpEndpoint.class);

    private final Executor logic;
    private final AssetOpService service;

    public AssetOpEndpoint(Executor logic, AssetOpService service) {
        this.logic = logic;
        this.service = service;
    }

    public CompletableFuture<AssetOpResponse> debit(AssetOpRequest request) {
        return submit(AssetRpc.DEBIT, request);
    }

    public CompletableFuture<AssetOpResponse> abortDebit(AssetOpRequest request) {
        return submit(AssetRpc.ABORT_DEBIT, request);
    }

    public CompletableFuture<AssetOpResponse> credit(AssetOpRequest request) {
        return submit(AssetRpc.CREDIT, request);
    }

    /** 按入口投递到逻辑线程（三个入口方法的共同实现）。 */
    public CompletableFuture<AssetOpResponse> submit(AssetRpc rpc, AssetOpRequest request) {
        CompletableFuture<AssetOpResponse> future = new CompletableFuture<>();
        try {
            logic.execute(() -> {
                try {
                    future.complete(service.handle(rpc, request));
                } catch (RuntimeException e) {
                    log.error("[AssetOp] 处理请求时出错 rpc={} player={} seq={}", rpc.wireName(),
                            Long.toUnsignedString(request.getPlayerId()), Long.toUnsignedString(request.getSeq()), e);
                    future.completeExceptionally(e);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}
