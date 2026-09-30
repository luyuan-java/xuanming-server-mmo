package com.game.common.token;

import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.spec.SecretKeySpec;

/**
 * gate → scene 节点链路握手鉴权（Java 版内部契约，不是客户端契约）。gate 在 {@code LinkHello} 里带上
 * {@code auth_timestamp} 与 {@code auth_mac}，scene 校验通过才接受链路。
 *
 * <p>MAC = HMAC-SHA256(secret, {@code "gate_node_id|gate_instance_id|zone_id|lease_epoch|auth_timestamp"}) 的
 * <b>64 字节小写 hex ASCII</b>（与 gate 令牌同一种编码）。四个数字按无符号十进制书写（proto 里都是无符号类型）；
 * 只有 gate_instance_id 是自由文本，它夹在两端都是纯数字的字段中间，所以拼接结果能唯一还原出五个字段。
 * {@code lease_epoch} 是 gate 节点号租约的防护代次，纳入 MAC 才能防止它被篡改成更大的值去顶掉别人的链路。
 *
 * <p>校验顺序：MAC（常数时间比较）→ 时间戳与本地时钟相差不超过 {@link #MAX_CLOCK_SKEW_SECONDS}。
 * 残余风险：窗口内截获的握手可被重放；链路不加密，能在路径上篡改流量的人不受此约束——
 * 这一层只挡「不知道密钥的人连上 scene 冒充 gate」，传输安全仍靠内网隔离。
 *
 * <p>无状态，线程安全。
 */
public final class NodeLinkAuth {

    /** 握手时间戳与本地时钟允许的最大偏差（秒，双向）。 */
    public static final long MAX_CLOCK_SKEW_SECONDS = 60;
    /** 密钥的环境变量名：xm-gate 与 xm-scene 必须配成同一个值；只从环境变量注入，不进任何配置文件。 */
    public static final String SECRET_ENV = "XM_NODE_LINK_SECRET";

    private final SecretKeySpec key;

    public NodeLinkAuth(byte[] secret) {
        this.key = HmacSha256Hex.key(secret, "节点链路密钥");
    }

    public static NodeLinkAuth ofUtf8(String secret) {
        return new NodeLinkAuth(secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 用环境变量 {@link #SECRET_ENV} 的值构造（进程启动时调用）。
     *
     * @throws IllegalStateException 未设置或为空白：拒绝启动（fail-fast），不允许无鉴权的链路
     */
    public static NodeLinkAuth requireFromEnvValue(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + SECRET_ENV + "（节点链路密钥，xm-gate 与 xm-scene 必须一致），拒绝启动");
        }
        return ofUtf8(secret);
    }

    /**
     * 算握手 MAC（64 字节 hex ASCII）。
     *
     * @param leaseEpoch    gate 节点号租约的防护代次（uint64 按位存进 long）
     * @param authTimestamp Unix 秒（uint64 按位存进 long）
     */
    public ByteString sign(int gateNodeId, String gateInstanceId, int zoneId, long leaseEpoch, long authTimestamp) {
        return ByteString.copyFrom(mac(gateNodeId, gateInstanceId, zoneId, leaseEpoch, authTimestamp),
                StandardCharsets.US_ASCII);
    }

    /**
     * 校验握手。
     *
     * @param nowEpochSeconds 本地当前 Unix 秒（调用方注入时钟，便于测试）
     */
    public Verdict verify(int gateNodeId, String gateInstanceId, int zoneId, long leaseEpoch, long authTimestamp,
                          ByteString authMac, long nowEpochSeconds) {
        byte[] expected = mac(gateNodeId, gateInstanceId, zoneId, leaseEpoch, authTimestamp)
                .getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, authMac.toByteArray())) {
            return Verdict.BAD_MAC;
        }
        // uint64 超过 long 上限时按位是负数：一律视为时间戳非法。两个非负数相减不会溢出。
        if (authTimestamp < 0 || Math.abs(nowEpochSeconds - authTimestamp) > MAX_CLOCK_SKEW_SECONDS) {
            return Verdict.STALE_TIMESTAMP;
        }
        return Verdict.OK;
    }

    /** MAC 的输入串（包内可见供测试固化格式）。 */
    static String canonical(int gateNodeId, String gateInstanceId, int zoneId, long leaseEpoch, long authTimestamp) {
        return Integer.toUnsignedString(gateNodeId) + '|' + gateInstanceId + '|' + Integer.toUnsignedString(zoneId)
                + '|' + Long.toUnsignedString(leaseEpoch) + '|' + Long.toUnsignedString(authTimestamp);
    }

    private String mac(int gateNodeId, String gateInstanceId, int zoneId, long leaseEpoch, long authTimestamp) {
        String input = canonical(gateNodeId, gateInstanceId == null ? "" : gateInstanceId, zoneId, leaseEpoch,
                authTimestamp);
        return HmacSha256Hex.hex(key, input.getBytes(StandardCharsets.UTF_8));
    }

    public enum Verdict {
        OK,
        /** MAC 不对（密钥不同或字段被改）。 */
        BAD_MAC,
        /** 时间戳与本地时钟相差超过 {@link #MAX_CLOCK_SKEW_SECONDS}。 */
        STALE_TIMESTAMP
    }
}
