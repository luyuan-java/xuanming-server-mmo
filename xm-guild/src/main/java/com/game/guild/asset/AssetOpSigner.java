package com.game.guild.asset;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetOpRequest;
import java.util.Objects;

/**
 * 帮会的资产请求签名器（基线 {@code assetop.Signer}，auth.go:57-67、:151-162 与 {@code svc/asset_op.go:42-76} 的 NewAssetOpSigner；
 * guild-economy-spec §2.4 第 6 条、§4.8、§7.5、E4 / E5）。规范串、HMAC 与密钥规则只在 xm-api 的 {@link AssetOpSignatures} 一处
 * （scene 验签用同一份代码），这里只绑定「调用方 = guild、密钥来自 {@value AssetOpSignatures#SECRET_ENV_GUILD}」。
 *
 * <p>密钥去首尾空白后至少 32 字节，否则<b>拒绝启动</b>（通道开启时；资产路径 fail-closed：没有签名器就没有「先写进 outbox、以后再投」的中间态，
 * 写进去也永远投不出去）。密钥值不进日志、指标、错误文本与 {@link #toString()}。
 *
 * <p>每次实际发包都要调 {@link #sign} 现签（签名带时间戳，scene 只接受 ±300 s；重查若复用旧签名，慢路径上会被判验签失败，caller.go:173-176）。
 * 不可变，线程安全。
 */
public final class AssetOpSigner {

    private final String secret;

    /**
     * @param rawSecret 环境变量原值（可为 null）；规范化后不够长抛 {@link IllegalStateException}（文本只有变量名与长度下限）
     */
    public AssetOpSigner(String rawSecret) {
        this.secret = AssetOpSignatures.requireSecret(AssetOpSignatures.SECRET_ENV_GUILD, rawSecret);
    }

    /** 从环境变量 {@value AssetOpSignatures#SECRET_ENV_GUILD} 建签名器（不做 dev 放行：本机由 start-slice.sh 生成并注入同一把）。 */
    public static AssetOpSigner fromEnvironment() {
        return new AssetOpSigner(System.getenv(AssetOpSignatures.SECRET_ENV_GUILD));
    }

    /**
     * 签一份请求的副本（{@code auth{caller="guild", timestamp_ms=nowMs, signature_hex}}），入参不变。
     *
     * @param nowMs 这一次实际发包的时刻（Unix 毫秒）
     */
    public AssetOpRequest sign(AssetRpc rpc, AssetOpRequest request, long nowMs) {
        return AssetOpSignatures.sign(Objects.requireNonNull(rpc, "rpc"), request, AssetOpSignatures.CALLER_GUILD, secret,
                nowMs);
    }

    @Override
    public String toString() {
        return "AssetOpSigner{caller=" + AssetOpSignatures.CALLER_GUILD + "}";
    }
}
