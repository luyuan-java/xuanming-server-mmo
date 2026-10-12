package com.game.guild.asset;

/**
 * 一次资产投递没拿到 scene 的答复（传输层失败：连不上 / 超时 / scene 过载或未就绪 / 调用方鉴权失败；或定位故障：位置记录 / 节点目录读不出来）。
 * 结局未知：调用方按 Retry 处理、用同一 seq 重投（scene 对见过的 seq 只读答复），重排时<b>不</b>覆盖行上的 last_outcome / last_reason / durable
 * （E12）。消息只带定位信息（节点地址、流、seq），不带密钥与请求体。
 */
public final class AssetDeliveryException extends RuntimeException {

    public AssetDeliveryException(String message) {
        super(message);
    }

    public AssetDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
