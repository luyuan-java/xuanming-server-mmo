package com.game.scene.asset;

import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 资产通道在 scene 节点上的进程内入口：任意线程调用，请求投递到场景逻辑线程处理，结局经 future 带回。
 * 跨进程传输（帮会 / 交易服务按玩家所在节点调过来）随路线图 4.5 接在这一层外面。
 * future 异常完成（逻辑线程已关闭、处理中抛异常）等同传输失败：调用方按结局未知、用同一 seq 重投。
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
        return submit(AssetOpService.Rpc.DEBIT, request);
    }

    public CompletableFuture<AssetOpResponse> abortDebit(AssetOpRequest request) {
        return submit(AssetOpService.Rpc.ABORT_DEBIT, request);
    }

    public CompletableFuture<AssetOpResponse> credit(AssetOpRequest request) {
        return submit(AssetOpService.Rpc.CREDIT, request);
    }

    private CompletableFuture<AssetOpResponse> submit(AssetOpService.Rpc rpc, AssetOpRequest request) {
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
