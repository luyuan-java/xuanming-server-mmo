package com.game.common.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.crypto.spec.SecretKeySpec;

/**
 * 运维指令（GM 远程停机）的签名校验（同 mmorpg gate_security.h VerifyGmRequest，gate 与 scene 共用）：
 * <ul>
 *   <li>密钥只从环境变量 {@value #SECRET_ENV} 读，没配一律拒（fail-closed）；</li>
 *   <li>签名 = HMAC-SHA256(密钥, canonical) 的 64 位小写 hex，canonical =
 *       {@code 方法名\n目标\n操作人\n时间戳原文\nnonce\n原因}——绑方法名与目标（基线是节点号；Java 是 {@code 区:节点号:实例}，
 *       见 {@link GmShutdownHandler}），一次抓包不能拿去停别的节点、别的区的同号节点、或重启后的新进程；</li>
 *   <li>时间戳（Unix 秒）与本机时钟差不超过窗口（{@value #SKEW_ENV}，缺省 300 s，钳到 [30, 900]）；</li>
 *   <li>先验签、后登记 nonce：nonce 在 2 × 窗口内只能用一次，去重表满（{@value #MAX_NONCES}）即拒——淘汰旧项会重新打开重放窗口，
 *       而只有持有密钥的调用方能往表里塞东西。</li>
 * </ul>
 * 与基线不同：签名信封不寄生在 operator 字段里（基线 muduo RPC 没有元数据边信道），而是 HTTP 头分开传（{@link Envelope}）；
 * 操作人与 nonce 只收可打印 ASCII（进日志、进审计）。线程安全。
 */
public final class GmRequestAuth {

    public static final String SECRET_ENV = "XM_GM_ADMIN_SECRET";
    public static final String SKEW_ENV = "XM_GM_AUTH_SKEW_SECONDS";
    static final long DEFAULT_SKEW_SECONDS = 300;
    static final long MIN_SKEW_SECONDS = 30;
    static final long MAX_SKEW_SECONDS = 900;
    static final int MAX_NONCES = 1024;
    static final int MAX_OPERATOR_LENGTH = 64;
    static final int MAX_NONCE_LENGTH = 128;
    static final int SIGNATURE_HEX_LENGTH = 64;
    /** 原因的最大字符数（原样进 canonical 与日志）。 */
    public static final int MAX_REASON_LENGTH = 256;

    /** 校验结局；名字进日志（同基线 GmAuthResultName）。 */
    public enum Result {
        OK("ok"),
        SECRET_NOT_CONFIGURED("secret_not_configured"),
        MALFORMED_ENVELOPE("malformed_envelope"),
        TIMESTAMP_OUT_OF_WINDOW("timestamp_out_of_window"),
        SIGNATURE_MISMATCH("signature_mismatch"),
        REPLAYED_NONCE("replayed_nonce");

        private final String logName;

        Result(String logName) {
            this.logName = logName;
        }

        public String logName() {
            return logName;
        }
    }

    /** 签名信封：操作人、时间戳原文（Unix 秒）、nonce、签名 hex。 */
    public record Envelope(String operator, String timestamp, String nonce, String signature) {
    }

    private final SecretKeySpec key;
    private final long skewSeconds;
    private final LongSupplier nowSeconds;
    private final Deque<Seen> seenOrder = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();

    /** @param secret 密钥；null 或空表示没配（一律拒） */
    public GmRequestAuth(byte[] secret, long skewSeconds, LongSupplier nowSeconds) {
        this.key = secret == null || secret.length == 0 ? null : HmacSha256Hex.key(secret, "GM 运维密钥");
        this.skewSeconds = skewSeconds;
        this.nowSeconds = nowSeconds;
    }

    /**
     * @param secretEnvValue 环境变量 {@value #SECRET_ENV} 的值（可为空：没配，一律拒）
     * @param skewEnvValue   环境变量 {@value #SKEW_ENV} 的值（可为空）
     */
    public static GmRequestAuth fromEnvValues(String secretEnvValue, String skewEnvValue, LongSupplier nowSeconds) {
        byte[] bytes = secretEnvValue == null || secretEnvValue.isBlank() ? null
                : secretEnvValue.trim().getBytes(StandardCharsets.UTF_8);
        return new GmRequestAuth(bytes, resolveSkew(skewEnvValue), nowSeconds);
    }

    /** 时间窗（同基线 ResolveGmSkewSeconds）：没配 / 不是整数取 300，钳到 [30, 900]。 */
    static long resolveSkew(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SKEW_SECONDS;
        }
        long parsed;
        try {
            parsed = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_SKEW_SECONDS;
        }
        return Math.max(MIN_SKEW_SECONDS, Math.min(MAX_SKEW_SECONDS, parsed));
    }

    public boolean configured() {
        return key != null;
    }

    /**
     * @param method       方法名（gate {@code Gate.GmGracefulShutdown}、scene {@code Scene.GmGracefulShutdown}）
     * @param target       签名目标（{@link GmShutdownHandler#target}：{@code 区:节点号:实例}）
     * @param reason       停机原因（原样进 canonical，可为空串）
     */
    public Result verify(String method, String target, Envelope envelope, String reason) {
        if (key == null) {
            return Result.SECRET_NOT_CONFIGURED;
        }
        if (!wellFormed(envelope) || reason != null && reason.length() > MAX_REASON_LENGTH) {
            return Result.MALFORMED_ENVELOPE;
        }
        long now = nowSeconds.getAsLong();
        long requestTime = Long.parseLong(envelope.timestamp());
        if (Math.abs(requestTime - now) > skewSeconds) {
            return Result.TIMESTAMP_OUT_OF_WINDOW;
        }
        String expected = HmacSha256Hex.hex(key, canonical(method, target, envelope.operator(),
                envelope.timestamp(), envelope.nonce(), reason == null ? "" : reason).getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                envelope.signature().getBytes(StandardCharsets.US_ASCII))) {
            return Result.SIGNATURE_MISMATCH;
        }
        // 保留整个窗口宽度的两倍：时间戳可以比本机早 skew 也可以晚 skew，同一 nonce 能过时间窗的整段时间里都得留在表里
        return register(envelope.nonce(), now, skewSeconds * 2) ? Result.OK : Result.REPLAYED_NONCE;
    }

    /** canonical 串（同基线 BuildGmCanonicalString）；签名方（运维工具）与校验方用同一个写法。 */
    public static String canonical(String method, String target, String operator, String timestamp, String nonce,
                                   String reason) {
        return method + '\n' + target + '\n' + operator + '\n' + timestamp + '\n' + nonce + '\n' + reason;
    }

    /** 签名（运维工具与测试用）。 */
    public static String sign(byte[] secret, String canonical) {
        return HmacSha256Hex.hex(HmacSha256Hex.key(secret, "GM 运维密钥"), canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean wellFormed(Envelope e) {
        if (e == null || !printable(e.operator(), MAX_OPERATOR_LENGTH) || !printable(e.nonce(), MAX_NONCE_LENGTH)) {
            return false;
        }
        String ts = e.timestamp();
        if (ts == null || ts.isEmpty() || ts.length() > 18) {
            return false;
        }
        for (int i = 0; i < ts.length(); i++) {
            if (ts.charAt(i) < '0' || ts.charAt(i) > '9') {
                return false;
            }
        }
        String sig = e.signature();
        if (sig == null || sig.length() != SIGNATURE_HEX_LENGTH) {
            return false;
        }
        for (int i = 0; i < sig.length(); i++) {
            char c = sig.charAt(i);
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return false;
            }
        }
        return true;
    }

    private static boolean printable(String s, int maxLength) {
        if (s == null || s.isEmpty() || s.length() > maxLength) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x21 || c > 0x7E) {
                return false;
            }
        }
        return true;
    }

    private synchronized boolean register(String nonce, long now, long retainSeconds) {
        while (!seenOrder.isEmpty() && seenOrder.peekFirst().expiresAt() <= now) {
            seen.remove(seenOrder.pollFirst().nonce());
        }
        if (seen.contains(nonce) || seenOrder.size() >= MAX_NONCES) {
            return false;
        }
        seenOrder.addLast(new Seen(now + retainSeconds, nonce));
        seen.add(nonce);
        return true;
    }

    private record Seen(long expiresAt, String nonce) {
    }
}
