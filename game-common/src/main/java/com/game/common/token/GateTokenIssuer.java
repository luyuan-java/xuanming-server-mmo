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
 * <p>两种票据（docs/porting/zone-travel-spec.md §1.4）：
 * <ul>
 *   <li><b>普通登录令牌</b> {@link #issue}（gateway 签）：有效期 {@link #TOKEN_TTL}，带每会话 HMAC 密钥，{@code player_id = 0}。
 *       与 mmorpg 的差异：本版固定填 {@code target_zone_id = zone_id}，把令牌钉死在签发时的 zone，
 *       gate 按 {@link GateTokens#verify} 拒绝跨 zone 使用（mmorpg 普通登录填 0）。</li>
 *   <li><b>重定向票据</b> {@link #issueRedirect}（批次 5.4，scene-manager 签；跨 zone 传送 226 与登录期重定向 GO-5 用）：有效期
 *       {@link #REDIRECT_TICKET_TTL}，<b>不带</b>会话密钥，带持票者 {@code player_id} 与 {@code target_zone_id}。
 *       字段与 mmorpg scene_manager 的 {@code signRedirectToGate}（{@code gate_redirect.go:80-87}）一致。</li>
 * </ul>
 *
 * <p>线程安全：{@link SecureRandom} 与 {@link GateTokens} 都可并发使用。
 */
public final class GateTokenIssuer {

    /** 令牌有效期，与 mmorpg go/login 的 {@code gateTokenTTL}（10 分钟）一致。 */
    public static final Duration TOKEN_TTL = Duration.ofMinutes(10);

    /**
     * 重定向票据的有效期（同 mmorpg 的 300 s）。客户端可见：它决定 124 的 {@code token_deadline}，也是待落点位置记录存活时长的上限
     * （{@code PlayerLocationDirectory.AWAIT_PLACEMENT_MAX_TTL} 与它同值）。有意不做成配置。
     */
    public static final Duration REDIRECT_TICKET_TTL = Duration.ofSeconds(300);

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
     * 为一名玩家签一张重定向票据（批次 5.4）：只有这名玩家、只在目标 zone 的这台 gate 上能用；有效期从本次调用时刻起算
     * {@link #REDIRECT_TICKET_TTL}。
     *
     * <p>payload = {@code {gate_node_id, zone_id = gateZoneId, expire_timestamp = now + 300, player_id, target_zone_id}}，
     * <b>不填</b> {@code hmac_session_key}（同基线；本版没有任何地方读它）。目标 gate 验票（{@link GateTokens#verify}）先判
     * {@code gate_node_id} 再判 {@code target_zone_id}：两个 zone 里节点号相同的 gate 也不会收错票。
     *
     * <p>返回的 {@code payload} 就是签名时的原字节：下游（scene-manager 的应答、scene 的链路帧、login 的会话指令、gate 下发的 124）
     * 一律原样拷贝，不得解析后重新序列化——重新序列化的字节与签名不一定对得上，而验票失败发生在源端已经交出归属之后。
     *
     * @param gateNodeId   选中的目标 gate 节点号，非 0
     * @param gateZoneId   这台 gate 所在的 zone（目录条目里的 zone），非 0
     * @param playerId     持票者，非 0（0 在 gate / login 眼里是「不绑定持票者的普通令牌」，谁拿到都能用）
     * @param targetZoneId 目标 zone，非 0（0 在 {@link GateTokens#verify} 里是「不限 zone」）
     * @throws IllegalArgumentException 任一参数为 0：这样的票据会悄悄失去持票者绑定或 zone 绑定，宁可不签（调用方的编程错误）
     */
    public IssuedGateToken issueRedirect(int gateNodeId, int gateZoneId, long playerId, int targetZoneId) {
        if (gateNodeId == 0 || gateZoneId == 0 || playerId == 0 || targetZoneId == 0) {
            throw new IllegalArgumentException("重定向票据的参数不能为 0: gate_node_id=" + Integer.toUnsignedString(gateNodeId)
                    + " gate_zone_id=" + Integer.toUnsignedString(gateZoneId)
                    + " player_id=" + Long.toUnsignedString(playerId)
                    + " target_zone_id=" + Integer.toUnsignedString(targetZoneId));
        }
        long deadline = clock.instant().plus(REDIRECT_TICKET_TTL).getEpochSecond();
        ByteString payload = GateTokenPayload.newBuilder()
                .setGateNodeId(gateNodeId)
                .setZoneId(gateZoneId)
                .setExpireTimestamp(deadline)
                .setPlayerId(playerId)
                .setTargetZoneId(targetZoneId)
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
