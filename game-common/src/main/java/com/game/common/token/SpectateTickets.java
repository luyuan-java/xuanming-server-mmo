package com.game.common.token;

import com.game.proto.SpectateTicket;
import com.game.proto.SpectateTicketPayload;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;

/**
 * 观战订阅凭证：match 签、gate 本地验（mmorpg battle-spectate-tiered-fanout.md §15.2、§15.5，D98；与 mmorpg Go {@code signSpectateTicket}、
 * C++ {@code gate_spectate_ticket} 同算法）。凭证字节 = {@link SpectateTicket} 的序列化，客户端原样转交、不解析。
 *
 * <p>签名 = HMAC-SHA256(密钥, payload 序列化字节) 的 <b>64 字节小写 hex ASCII</b>（与 gate 令牌、battle 直连票据同口径）。验签对象是 payload 的原字节，
 * gate 验过才反序列化，两版不必对 protobuf 的编码细节达成一致。密钥与 gate / battle 令牌分域（{@link SpectateSecretPolicy}）。
 *
 * <p>无状态，线程安全（每次调用新建 {@code Mac}）。
 */
public final class SpectateTickets {

    /** 没配密钥时的实例：{@link #verify} 一律拒绝（fail-closed），{@link #sign} 抛异常。dev / test 空密钥放行时用它。 */
    private static final SpectateTickets DISABLED = new SpectateTickets();

    private final SecretKeySpec key;

    private SpectateTickets() {
        this.key = null;
    }

    public SpectateTickets(byte[] secret) {
        this.key = HmacSha256Hex.key(secret, "观战凭证密钥");
    }

    /** 签名用密钥原始字节（不去空白，同 {@link BattleTickets#ofUtf8}）；空串或 null 回 {@link #disabled()}。 */
    public static SpectateTickets ofUtf8(String secret) {
        return secret == null || secret.isEmpty() ? DISABLED : new SpectateTickets(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 没配密钥：验签一律拒绝，签发抛异常。 */
    public static SpectateTickets disabled() {
        return DISABLED;
    }

    /** 配了密钥才能签发和验证。 */
    public boolean enabled() {
        return key != null;
    }

    /**
     * 签一张凭证。
     *
     * @return {@link SpectateTicket} 的序列化字节（即 {@code WatchBattleResponse.spectate_ticket}）
     * @throws IllegalStateException 没配密钥
     */
    public ByteString sign(SpectateTicketPayload payload) {
        Objects.requireNonNull(payload, "payload");
        if (key == null) {
            throw new IllegalStateException("观战凭证密钥未配置，不能签发");
        }
        ByteString raw = payload.toByteString();
        return SpectateTicket.newBuilder().setPayload(raw).setSignature(hexSignature(raw)).build().toByteString();
    }

    /**
     * gate 的校验，顺序同 C++ 版：密钥已配 → 凭证可解析 → 验签 → payload 可解析 → 本会话的玩家 → 请求的场次 → 未过期 → 帧来源实例非空。
     *
     * @param ticket    客户端转交的凭证字节
     * @param playerId  会话当前的玩家（0 = 没进游戏：一律拒绝）
     * @param battleId  {@code SubscribeSpectateRequest.battle_id}
     * @param nowWallMs gate 墙钟（Unix 毫秒，调用方注入）
     */
    public Verdict verify(ByteString ticket, long playerId, long battleId, long nowWallMs) {
        if (key == null) {
            return Verdict.fail(Failure.NO_SECRET);
        }
        SpectateTicket parsed;
        try {
            parsed = SpectateTicket.parseFrom(ticket);
        } catch (InvalidProtocolBufferException e) {
            return Verdict.fail(Failure.BAD_FORMAT);
        }
        // 常数时间比较：签名由客户端转交，逐字节提前返回会泄露匹配长度
        if (!MessageDigest.isEqual(hexSignature(parsed.getPayload()).toByteArray(), parsed.getSignature().toByteArray())) {
            return Verdict.fail(Failure.BAD_SIGNATURE);
        }
        SpectateTicketPayload payload;
        try {
            payload = SpectateTicketPayload.parseFrom(parsed.getPayload());
        } catch (InvalidProtocolBufferException e) {
            return Verdict.fail(Failure.BAD_FORMAT);
        }
        if (playerId == 0 || payload.getPlayerId() != playerId) {
            return Verdict.fail(Failure.WRONG_PLAYER);
        }
        if (battleId == 0 || payload.getBattleId() != battleId) {
            return Verdict.fail(Failure.WRONG_BATTLE);
        }
        if (Long.compareUnsigned(payload.getExpireAtMs(), nowWallMs) <= 0) {
            return Verdict.fail(Failure.EXPIRED);
        }
        // 实例为空的凭证收不到任何帧（gate 按实例过滤）：签发方的缺陷，拒绝比让客户端空等 30 s 好
        if (payload.getBattleInstanceId().isEmpty()) {
            return Verdict.fail(Failure.BAD_FORMAT);
        }
        return new Verdict(null, payload);
    }

    private ByteString hexSignature(ByteString payload) {
        return ByteString.copyFrom(HmacSha256Hex.hex(key, payload.toByteArray()), StandardCharsets.US_ASCII);
    }

    /** 凭证被拒的原因（只进指标与日志；客户端一律看到 {@code MatchSpectateTicketInvalid}，不告诉它错在哪）。 */
    public enum Failure {
        /** gate 没配密钥。 */
        NO_SECRET,
        /** 凭证或 payload 解析失败，或缺帧来源实例。 */
        BAD_FORMAT,
        BAD_SIGNATURE,
        /** 不是本会话的玩家。 */
        WRONG_PLAYER,
        /** 不是请求的那一场。 */
        WRONG_BATTLE,
        EXPIRED
    }

    /** 校验结果：{@code failure == null} 即通过，此时 {@code payload} 为解析后的内容。 */
    public record Verdict(Failure failure, SpectateTicketPayload payload) {

        static Verdict fail(Failure failure) {
            return new Verdict(failure, null);
        }

        public boolean ok() {
            return failure == null;
        }
    }
}
