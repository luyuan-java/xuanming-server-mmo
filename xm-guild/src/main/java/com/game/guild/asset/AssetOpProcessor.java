package com.game.guild.asset;

import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetOpRequest;
import com.game.common.deadline.Deadline;
import com.game.guild.asset.AssetOpCaller.Delivery;
import java.util.concurrent.CompletableFuture;

/**
 * 「处理一行待办资产指令」的能力（基线 {@code Loop.ProcessOne}，reconcile.go:478-528）：决定方向 → 投一次 → 按 Decide 终结或重排。
 * 生产实现是 {@link AssetOpLoop}；帮会经济服务的同步投递只依赖这个接口（通道关闭时没有实现，服务回 14026），单测可以换假的。
 */
public interface AssetOpProcessor {

    /**
     * 处理一行（异步，future 从不异常完成）。投递只拿 {@code min(budgetMillis, op-budget − 700 ms)}，落库另有 700 ms 自有预算、不随请求取消，
     * 在有界的落库执行器上跑阻塞 JDBC（同步路径不占 guild-worker，Q5）。
     *
     * @param origin       谁触发的（{@link DeliveryOrigin#SYNC} 时终结不推送，E9）
     * @param budgetMillis 投递预算（同步路径 = min(2500, 请求剩余 − 1000)）
     */
    CompletableFuture<Processed> processOne(AssetOp op, DeliveryOrigin origin, long budgetMillis);

    /**
     * 处理一行的结果（基线 Processed，reconcile.go:322-327）。
     *
     * @param result    本次真实答复（没拿到答复时为 null）；同步路径据它告诉玩家发生了什么，但回包一律以库里回读的状态为准
     * @param finalized 本次是否终结了这一行（CAS 命中）
     * @param status    终结时的状态；没终结为 {@link AssetOpStatus#PENDING}
     */
    record Processed(AssetOpResult result, boolean finalized, AssetOpStatus status) {

        static Processed pending(AssetOpResult result) {
            return new Processed(result, false, AssetOpStatus.PENDING);
        }
    }

    /** 「把一条指令投给 scene」的能力（基线 Applier，reconcile.go:165-168）；生产实现是 {@link AssetOpCaller}，单测注入假的。 */
    @FunctionalInterface
    interface Applier {
        /** future 从不异常完成。 */
        CompletableFuture<Delivery> deliver(AssetRpc rpc, AssetOpRequest request, Deadline budget);
    }
}
