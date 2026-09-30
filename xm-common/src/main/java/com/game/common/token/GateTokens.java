package com.game.common.token;

import com.game.proto.GateTokenPayload;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * gate 令牌：客户端契约（mmorpg 的 Go {@code gatetoken.go} 与 C++ {@code HmacSha256Hex} 同形）。
 *
 * <p>签名 = HMAC-SHA256(secret, {@code GateTokenPayload} 序列化字节) 的 <b>64 字节小写 hex ASCII</b>，
 * 不是 proto 注释里说的 32 字节原值。校验用常数时间比较。
 *
 * <p>无状态，线程安全（每次调用新建 {@link Mac}）。
 */
public final class GateTokens {

    private final SecretKeySpec key;

    public GateTokens(byte[] secret) {
        this.key = HmacSha256Hex.key(secret, "gate 令牌密钥");
    }

    public static GateTokens ofUtf8(String secret) {
        return new GateTokens(secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 返回签名（64 字节 hex ASCII）。 */
    public ByteString sign(ByteString payload) {
        return ByteString.copyFrom(hexHmac(payload.toByteArray()), StandardCharsets.US_ASCII);
    }

    /**
     * 按 C++ gate 的顺序校验：签名 → payload 可解析 → 本 gate → 目标 zone → 未过期。
     *
     * @param nowEpochSeconds 当前 Unix 秒（调用方注入时钟，便于测试）
     */
    public Verdict verify(ByteString payload, ByteString signature, int selfGateNodeId, int selfZoneId, long nowEpochSeconds) {
        byte[] expected = hexHmac(payload.toByteArray()).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, signature.toByteArray())) {
            return Verdict.fail(Failure.BAD_SIGNATURE);
        }
        GateTokenPayload parsed;
        try {
            parsed = GateTokenPayload.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            return Verdict.fail(Failure.BAD_PAYLOAD);
        }
        if (parsed.getGateNodeId() != selfGateNodeId) {
            return Verdict.fail(Failure.WRONG_GATE);
        }
        if (parsed.getTargetZoneId() != 0 && parsed.getTargetZoneId() != selfZoneId) {
            return Verdict.fail(Failure.WRONG_ZONE);
        }
        if (parsed.getExpireTimestamp() <= nowEpochSeconds) {
            return Verdict.fail(Failure.EXPIRED);
        }
        return new Verdict(null, parsed);
    }

    private String hexHmac(byte[] payload) {
        return HmacSha256Hex.hex(key, payload);
    }

    public enum Failure {
        BAD_SIGNATURE,
        BAD_PAYLOAD,
        WRONG_GATE,
        WRONG_ZONE,
        EXPIRED
    }

    /** 校验结果：{@code failure == null} 即通过，此时 {@code payload} 为解析后的令牌。 */
    public record Verdict(Failure failure, GateTokenPayload payload) {

        static Verdict fail(Failure failure) {
            return new Verdict(failure, null);
        }

        public boolean ok() {
            return failure == null;
        }
    }
}
