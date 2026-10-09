package com.game.common.token;

import com.game.proto.GateTokenPayload;
import com.google.protobuf.ByteString;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;

/**
 * 签发 gate 令牌（客户端契约，形状与 mmorpg {@code loginqueue.SignGateToken} 一致）：
 * {@code GateTokenPayload} 序列化字节 + {@link GateTokens} 签名（64 字节小写 hex ASCII）。
 *
 * <p>与 mmorpg 的差异：本版固定填 {@code target_zone_id = zone_id}，把令牌钉死在签发时的 zone，
 * gate 按 {@link GateTokens#verify} 拒绝跨 zone 使用（mmorpg 普通登录填 0）。
 *
 * <p>线程安全：{@link SecureRandom} 与 {@link GateTokens} 都可并发使用。
 */
public final class GateTokenIssuer {

    /** 令牌有效期，与 mmorpg go/login 的 {@code gateTokenTTL}（10 分钟）一致。 */
    public static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    /** 每会话 HMAC 密钥长度（HMAC-SHA256 的自然密钥长度），与 mmorpg {@code hmacSessionKeyLen} 一致。 */
    static final int SESSION_KEY_BYTES = 32;

    private final GateTokens tokens;
    private final Clock clock;
    private final SecureRandom random;

    public GateTokenIssuer(GateTokens tokens, Clock clock, SecureRandom random) {
        this.tokens = tokens;
        this.clock = clock;
        this.random = random;
    }

    /** 为选中的 gate 签一张新令牌；有效期从本次调用时刻起算。 */
    public IssuedGateToken issue(int gateNodeId, int zoneId) {
        long deadline = clock.instant().plus(TOKEN_TTL).getEpochSecond();
        byte[] sessionKey = new byte[SESSION_KEY_BYTES];
        random.nextBytes(sessionKey);
        ByteString payload = GateTokenPayload.newBuilder()
                .setGateNodeId(gateNodeId)
                .setZoneId(zoneId)
                .setExpireTimestamp(deadline)
                .setHmacSessionKey(ByteString.copyFrom(sessionKey))
                .setTargetZoneId(zoneId)
                .build()
                .toByteString();
        return new IssuedGateToken(payload, tokens.sign(payload), deadline);
    }

    /**
     * @param payload          {@code GateTokenPayload} 序列化字节，客户端原样回传给 gate
     * @param signature        签名（64 字节 hex ASCII）
     * @param deadlineEpochSec 过期时刻（Unix 秒），等于 payload 里的 {@code expire_timestamp}
     */
    public record IssuedGateToken(ByteString payload, ByteString signature, long deadlineEpochSec) {
    }
}
