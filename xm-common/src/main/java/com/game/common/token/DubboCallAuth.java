package com.game.common.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.spec.SecretKeySpec;

/**
 * Java 版进程之间 Dubbo 调用的调用方鉴权（Java 版内部契约，不是客户端契约）。
 *
 * <p>为什么需要：Dubbo 会把 {@code 127.*} 当作无效绑定地址，{@code dubbo.protocol.host=127.0.0.1} 实际绑定到
 * {@code 0.0.0.0}（{@code DUBBO_IP_TO_BIND} 也不接受回环地址），所以 login / scene-manager 的 Dubbo 端口无法只绑本机；
 * 而 {@code ClientMessageService} 完全信任调用方填的 {@code SessionContext}。没有这一层，能连上端口的任何人都能以任意账号建角、
 * 夺取任意角色的数据归属。
 *
 * <p>协议：调用方每次调用带两个附件（Triple 下是 HTTP/2 头，键名小写）：
 * <ul>
 *   <li>{@code xm-auth-ts}：Unix 秒，十进制；</li>
 *   <li>{@code xm-auth-mac}：HMAC-SHA256(secret, {@code "service|method|ts"}) 的 64 字节小写 hex
 *       （service = 接口全名，method = 方法名，ts 按无符号十进制）。</li>
 * </ul>
 * 提供方先验 MAC（常数时间比较），再验 ts 与本地时钟相差不超过 {@link #MAX_CLOCK_SKEW_SECONDS}；任何一项不过都拒绝，
 * 对外不说原因。
 *
 * <p>残余风险（与节点链路握手相同）：MAC 不覆盖请求体，能在内网截获流量的人可在 60s 窗口内把附件嫁接到别的请求体上重放；
 * 这一层只挡「不知道密钥的人直接调端口」，传输安全仍靠内网隔离。
 *
 * <p>无状态，线程安全。
 */
public final class DubboCallAuth {

    /** 密钥的环境变量名：xm-login、xm-scene-manager、xm-gate 必须配成同一个值；只从环境变量注入。 */
    public static final String SECRET_ENV = "XM_DUBBO_SECRET";
    /** 调用时间戳与提供方本地时钟允许的最大偏差（秒，双向）。 */
    public static final long MAX_CLOCK_SKEW_SECONDS = 60;
    /** 附件键：时间戳。 */
    public static final String TIMESTAMP_KEY = "xm-auth-ts";
    /** 附件键：MAC。 */
    public static final String MAC_KEY = "xm-auth-mac";

    private final SecretKeySpec key;

    public DubboCallAuth(byte[] secret) {
        this.key = HmacSha256Hex.key(secret, "Dubbo 调用鉴权密钥");
    }

    public static DubboCallAuth ofUtf8(String secret) {
        return new DubboCallAuth(secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 用环境变量 {@link #SECRET_ENV} 的值构造（进程启动时调用）。
     *
     * @throws IllegalStateException 未设置或为空白：拒绝启动（fail-fast），不允许无鉴权的 Dubbo 面
     */
    public static DubboCallAuth requireFromEnvValue(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("缺少环境变量 " + SECRET_ENV
                    + "（Dubbo 调用鉴权密钥，xm-login、xm-scene-manager、xm-gate 必须一致），拒绝启动");
        }
        return ofUtf8(secret);
    }

    /** 调用方：算这次调用的 MAC（64 字节小写 hex）。 */
    public String sign(String service, String method, long epochSeconds) {
        return HmacSha256Hex.hex(key, canonical(service, method, epochSeconds).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 提供方：校验一次调用带来的附件。
     *
     * @param timestamp       {@link #TIMESTAMP_KEY} 附件原文（可能为 null）
     * @param mac             {@link #MAC_KEY} 附件原文（可能为 null）
     * @param nowEpochSeconds 本地当前 Unix 秒
     */
    public Verdict verify(String service, String method, String timestamp, String mac, long nowEpochSeconds) {
        if (timestamp == null || mac == null) {
            return Verdict.MISSING;
        }
        long ts;
        try {
            ts = Long.parseUnsignedLong(timestamp);
        } catch (NumberFormatException e) {
            return Verdict.BAD_MAC;
        }
        byte[] expected = sign(service, method, ts).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(expected, mac.getBytes(StandardCharsets.US_ASCII))) {
            return Verdict.BAD_MAC;
        }
        // uint64 超过 long 上限时按位是负数：一律视为时间戳非法。两个非负数相减不会溢出。
        if (ts < 0 || Math.abs(nowEpochSeconds - ts) > MAX_CLOCK_SKEW_SECONDS) {
            return Verdict.STALE_TIMESTAMP;
        }
        return Verdict.OK;
    }

    /** MAC 的输入串（包内可见供测试固化格式）。 */
    static String canonical(String service, String method, long epochSeconds) {
        return service + '|' + method + '|' + Long.toUnsignedString(epochSeconds);
    }

    public enum Verdict {
        OK,
        /** 没带鉴权附件。 */
        MISSING,
        /** MAC 不对（密钥不同、字段被改、时间戳不是数字）。 */
        BAD_MAC,
        /** 时间戳与本地时钟相差超过 {@link #MAX_CLOCK_SKEW_SECONDS}。 */
        STALE_TIMESTAMP
    }
}
