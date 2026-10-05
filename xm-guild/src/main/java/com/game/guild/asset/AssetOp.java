package com.game.guild.asset;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetOpRequest;
import java.util.Objects;

/**
 * 一行待办资产指令在内存里的形状（基线 assetop.Op，types.go:189-229）。由同步投递（服务拿预留结果拼）或 {@link GuildAssetStore#claim}
 * （回读库里的行拼）产生；调用方与循环只读它。数值字段一律按无符号位模式持有。
 *
 * @param opId          guild_asset_op 主键；同时是 correlation_id（同步首投与重投一致，asset_store.go:481-484）
 * @param playerId      资产的主人（高基数，<b>不得</b>进指标 label）
 * @param stream        AssetStream 数值（库里的 uint32 原值：坏行可能是越界值，{@link AssetOpDecisions#applyRpcOf} 判坏、不猜方向）
 * @param seq           本流内的流水号，从 1 起
 * @param streamEpoch   分配 seq 时 seq 行的纪元
 * @param correlationId 进 scene 资产流水的关联号（= opId）
 * @param txType        TransactionType 数值（24 / 25 / 26），scene 按流白名单校验
 * @param bundle        要变动的资产（Abort 也照样带上并进签名）
 * @param attempts      已投递次数（决定退避与是否读已落盘账本）；uint32
 * @param deadlineMs    到期改发 Abort；0 = 永不中止。循环路径取库里的值（看得到离帮提前截止），同步路径取内存值（§2.3）
 * @param leaseToken    本次领取的令牌（同步路径是插行令牌）；重排按它 CAS
 * @param lastReason    上一次答复留下的原因码；唯一用途是部分发放（27007）的粘性证据
 */
public record AssetOp(long opId, long playerId, int stream, long seq, long streamEpoch, long correlationId, int txType,
                      AssetBundle bundle, int attempts, long deadlineMs, long leaseToken, int lastReason) {

    public AssetOp {
        Objects.requireNonNull(bundle, "bundle");
    }

    /**
     * 拼成 scene 请求（Request，types.go:217-229）。<b>不带签名</b>：签名由调用方在每次实际发包前按当前时间现签（auth.go），
     * 这样重查也不会因为时间窗过期被拒。
     */
    public AssetOpRequest request() {
        return AssetOpRequest.newBuilder()
                .setPlayerId(playerId)
                .setStreamValue(stream)
                .setSeq(seq)
                .setCorrelationId(correlationId)
                .setTxType(txType)
                .setBundle(bundle)
                .setStreamEpoch(streamEpoch)
                .build();
    }
}
