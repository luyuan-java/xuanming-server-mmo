package com.game.guild.asset;

/**
 * 同一个 seq 在两次查询之间给出了不同的终结结局（基线 ErrOutcomeFlip，types.go:262-264）：违反不变量 I2，必定是 bug 或数据损坏。
 * 调用方在 durable 重查里发现翻转时抛它（或把它放进 future 的异常完成里）；{@link AssetOpDecisions#decide} 据此判 ALERT——只能告警 + 人工，
 * 绝不能自动终结。异常消息只放 op_id / 流 / seq 这类定位信息，不放密钥与请求体。
 */
public final class AssetOutcomeFlipException extends RuntimeException {

    public AssetOutcomeFlipException(String message) {
        super(message);
    }
}
