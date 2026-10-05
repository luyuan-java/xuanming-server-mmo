package com.game.common.token;

import com.game.proto.BattleTicketPayload;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import javax.crypto.spec.SecretKeySpec;

/**
 * battle 直连票据（客户端契约；基线 {@code cpp/nodes/battle/battle_security.h} 的 {@code SignTicket} / {@code VerifyTicketSignature} /
 * {@code ClassifyTicketFields}，battle-node-spec §2.2、§7.5）。
 *
 * <p>签名 = HMAC-SHA256(secret, {@code BattleTicketPayload} 序列化字节) 的 <b>64 字节小写 hex ASCII</b>（与 {@link GateTokens} 同一口径，
 * 不是 proto 注释里说的 32 字节原值）。签发方与验签方都是 battle 节点自己，所以 payload 的字节不需要与 C++ 一致；但验签必须用
 * <b>收到的原始 payload 字节</b>重算（不能「解析后重新序列化」：未知字段、字段顺序都会让重算的字节不同），通过之后才解析。
 *
 * <p>与基线的差异（§11 N5）：密钥任何运行模式都必填，构造时空密钥直接抛异常，没有「dev 空密钥跳过验签」的分支；
 * 密钥强度与「不得与 gate 密钥相同」见 {@link BattleSecretPolicy}。
 *
 * <p>无状态，线程安全（每次调用新建 {@link javax.crypto.Mac}）。
 */
public final class BattleTickets {

    /** 签名长度：SHA-256 32 字节 = 64 个 hex 字符。 */
    public static final int SIGNATURE_LENGTH = 64;

    private final SecretKeySpec key;

    /** @throws IllegalArgumentException 密钥为空（null 或 0 字节） */
    public BattleTickets(byte[] secret) {
        this.key = HmacSha256Hex.key(secret, "battle 票据密钥");
    }

    /** 按 UTF-8 取密钥的原始字节（<b>不去首尾空白</b>，同基线 {@code token_security.h} 直接用配置值签名）。 */
    public static BattleTickets ofUtf8(String secret) {
        return new BattleTickets(secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 签名：返回 64 字节小写 hex ASCII。调用方必须把<b>同一份</b> {@code payloadBytes} 放进 {@code BattleAssignedS2C.token_payload}
     * （只调一次 {@code toByteString()}，§7.5）。
     */
    public ByteString sign(ByteString payloadBytes) {
        return ByteString.copyFrom(HmacSha256Hex.hex(key, payloadBytes.toByteArray()), StandardCharsets.US_ASCII);
    }

    /**
     * 常数时间验签（基线 {@code VerifyTicketSignature}）：用收到的原始 payload 字节重算后逐字节比较，<b>大小写敏感</b>
     * （大写 hex 不通过）。长度不同、签名为空一律不通过。
     */
    public boolean signatureMatches(ByteString payloadBytes, ByteString signature) {
        byte[] expected = HmacSha256Hex.hex(key, payloadBytes.toByteArray()).getBytes(StandardCharsets.US_ASCII);
        byte[] actual = signature.toByteArray();
        // expected 恒为 64 字节；actual 为空或长度不同时 isEqual 返回 false（长度不是秘密）
        return expected.length != 0 && actual.length != 0 && MessageDigest.isEqual(expected, actual);
    }

    /**
     * 票据字段判定（基线 {@code ClassifyTicketFields}，{@code battle_security.h:153-184}）。顺序即「最便宜的先拒」，是契约
     * （基线单测断言了这个顺序）：身份为空 → 节点号 → 实例 → 期限 → 角色。
     *
     * <ul>
     *   <li>{@code battle_id} 或 {@code player_id} 为 0 → {@link Verdict#EMPTY_IDENTITY}；</li>
     *   <li>{@code battle_node_id ≠ selfNodeId} → {@link Verdict#NODE_MISMATCH}；</li>
     *   <li>实例 id 任一侧为空或不等 → {@link Verdict#INSTANCE_MISMATCH}（节点重启后节点号被复用）；</li>
     *   <li>{@code expire_at_ms ≤ nowMs}（按 uint64 比较；等于也算过期）→ {@link Verdict#EXPIRED}；</li>
     *   <li>角色的整数值不是 1 / 2（含未知枚举值）→ {@link Verdict#ROLE_INVALID}。</li>
     * </ul>
     *
     * @param selfNodeId     本节点号（租约）
     * @param selfInstanceId 本进程实例 UUID
     * @param nowMs          当前 Unix 毫秒（调用方注入时钟）
     */
    public static Verdict classify(BattleTicketPayload payload, int selfNodeId, String selfInstanceId, long nowMs) {
        if (payload.getBattleId() == 0 || payload.getPlayerId() == 0) {
            return Verdict.EMPTY_IDENTITY;
        }
        if (payload.getBattleNodeId() != selfNodeId) {
            return Verdict.NODE_MISMATCH;
        }
        String ticketInstance = payload.getBattleInstanceId();
        if (ticketInstance.isEmpty() || selfInstanceId == null || selfInstanceId.isEmpty()
                || !ticketInstance.equals(selfInstanceId)) {
            return Verdict.INSTANCE_MISMATCH;
        }
        if (Long.compareUnsigned(payload.getExpireAtMs(), nowMs) <= 0) {
            return Verdict.EXPIRED;
        }
        int role = payload.getRoleValue();
        if (role != eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT_VALUE
                && role != eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER_VALUE) {
            return Verdict.ROLE_INVALID;
        }
        return Verdict.OK;
    }

    /** 字段判定结果（基线 {@code TicketVerdict}）。 */
    public enum Verdict {
        OK,
        /** battle_id / player_id 为 0。 */
        EMPTY_IDENTITY,
        /** 不是签给本节点的（节点号不同）。 */
        NODE_MISMATCH,
        /** 节点号相同但实例 UUID 不同（节点重启后节点号被复用），或任一侧为空。 */
        INSTANCE_MISMATCH,
        /** 已过房间作废期限。 */
        EXPIRED,
        /** 角色不是参战者 / 观众。 */
        ROLE_INVALID;

        /** 基线 {@code TicketVerdictName}：ok / empty_identity / node_mismatch / instance_mismatch / expired / role_invalid（日志与指标标签用）。 */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * 握手应答里给客户端的拒绝串（逐字照基线 {@code edge.cpp}：{@code "ticket rejected: " + 名字}，客户端可见）。
         *
         * @throws IllegalStateException 对 {@link #OK} 调用
         */
        public String clientError() {
            if (this == OK) {
                throw new IllegalStateException("OK 没有拒绝串");
            }
            return "ticket rejected: " + wireName();
        }
    }
}
