package com.game.common.net;

import java.util.Objects;

/**
 * 边缘节点（gate / battle）连接准入策略（对齐主仓 {@code gate-connection-admission-control.md} P1-1..P1-4、P0-2、P2-2）。
 *
 * <p>默认推导：
 * <ul>
 *   <li>持续新建速率：{@code max(100, maxConnections / 100)} 条/秒；桶深 = {@code maxConnections}；</li>
 *   <li>未验签连接预算：{@code B = 默认持续速率 × 握手期限秒数}（上限 20000 时 2000，上限 &lt; 10000 时 1000），早拒起点 {@code S = B / 2}；</li>
 *   <li>可淘汰年龄：默认 3000 ms（必须短于握手期限）；</li>
 *   <li>按公网来源前缀限速：默认关闭（0）；开启时默认桶深 = {@code 速率 × 10}。</li>
 * </ul>
 */
public record ConnectionAdmissionPolicy(
        int maxConnections,
        long handshakeTimeoutMs,
        int acceptRatePerSecond,
        int acceptBurst,
        int preAuthBudget,
        int preAuthEarlyDropStart,
        long preAuthEvictableAgeMs,
        double sourceRatePerSecond,
        double sourceBurst) {

    public static final int MIN_RATE = 1;
    public static final int MAX_RATE = 1_000_000;
    public static final long DEFAULT_EVICTABLE_AGE_MS = 3_000L;

    public ConnectionAdmissionPolicy {
        if (maxConnections < 0) {
            throw new IllegalArgumentException("maxConnections 不能为负: " + maxConnections);
        }
        if (handshakeTimeoutMs < 0) {
            throw new IllegalArgumentException("handshakeTimeoutMs 不能为负: " + handshakeTimeoutMs);
        }
        if (maxConnections > 0) {
            validateRange("acceptRatePerSecond", acceptRatePerSecond, MIN_RATE, MAX_RATE);
            validateRange("acceptBurst", acceptBurst, MIN_RATE, MAX_RATE);
            if (preAuthBudget < 1) {
                throw new IllegalArgumentException("preAuthBudget 必须 >= 1: " + preAuthBudget);
            }
            if (preAuthEarlyDropStart < 1 || preAuthEarlyDropStart > preAuthBudget) {
                throw new IllegalArgumentException("preAuthEarlyDropStart 必须在 [1, preAuthBudget("
                        + preAuthBudget + ")] 之间: " + preAuthEarlyDropStart);
            }
            if (preAuthEvictableAgeMs < 1) {
                throw new IllegalArgumentException("preAuthEvictableAgeMs 必须 >= 1: " + preAuthEvictableAgeMs);
            }
            if (handshakeTimeoutMs > 0 && preAuthEvictableAgeMs >= handshakeTimeoutMs) {
                throw new IllegalArgumentException("preAuthEvictableAgeMs(" + preAuthEvictableAgeMs
                        + ") 必须短于 handshakeTimeoutMs(" + handshakeTimeoutMs + ")");
            }
        }
        if (sourceRatePerSecond < 0 || Double.isNaN(sourceRatePerSecond) || Double.isInfinite(sourceRatePerSecond)) {
            throw new IllegalArgumentException("sourceRatePerSecond 非法: " + sourceRatePerSecond);
        }
        if (sourceBurst < 0 || Double.isNaN(sourceBurst) || Double.isInfinite(sourceBurst)) {
            throw new IllegalArgumentException("sourceBurst 非法: " + sourceBurst);
        }
    }

    /** 按连接上限与握手期限推导默认策略（{@code maxConnections == 0} 表示不限总量/速率，供 dev 与纯单元测试用）。 */
    public static ConnectionAdmissionPolicy defaults(int maxConnections, long handshakeTimeoutMs) {
        return of(maxConnections, handshakeTimeoutMs, null, null, null, null, null, null, null);
    }

    /** 允许逐项覆写默认推导；显式传入非法值直接抛 {@link IllegalArgumentException} 拒绝启动。 */
    public static ConnectionAdmissionPolicy of(
            int maxConnections,
            long handshakeTimeoutMs,
            Integer acceptRateOverride,
            Integer acceptBurstOverride,
            Integer preAuthBudgetOverride,
            Integer preAuthEarlyDropStartOverride,
            Long preAuthEvictableAgeMsOverride,
            Double sourceRateOverride,
            Double sourceBurstOverride) {
        if (maxConnections < 0) {
            throw new IllegalArgumentException("maxConnections 不能为负: " + maxConnections);
        }
        if (handshakeTimeoutMs < 0) {
            throw new IllegalArgumentException("handshakeTimeoutMs 不能为负: " + handshakeTimeoutMs);
        }
        if (maxConnections == 0) {
            double srcRate = sourceRateOverride == null ? 0.0 : sourceRateOverride;
            double srcBurst = sourceBurstOverride == null ? (srcRate > 0 ? srcRate * 10.0 : 0.0) : sourceBurstOverride;
            return new ConnectionAdmissionPolicy(0, handshakeTimeoutMs, 0, 0, 0, 0, 0L, srcRate, srcBurst);
        }
        int defaultRate = Math.max(100, maxConnections / 100);
        int rate = acceptRateOverride != null ? acceptRateOverride : defaultRate;
        int burst = acceptBurstOverride != null ? acceptBurstOverride : Math.max(1, maxConnections);
        long handshakeSec = handshakeTimeoutMs > 0 ? Math.max(1L, (handshakeTimeoutMs + 999L) / 1000L) : 10L;
        int defaultBudget = (int) Math.min(Integer.MAX_VALUE, (long) defaultRate * handshakeSec);
        int budget = preAuthBudgetOverride != null ? preAuthBudgetOverride : defaultBudget;
        int earlyStart = preAuthEarlyDropStartOverride != null
                ? preAuthEarlyDropStartOverride
                : Math.max(1, budget / 2);
        long defaultEvictAge = handshakeTimeoutMs > 0 && handshakeTimeoutMs <= DEFAULT_EVICTABLE_AGE_MS
                ? Math.max(1L, handshakeTimeoutMs / 2L)
                : DEFAULT_EVICTABLE_AGE_MS;
        long evictAge = preAuthEvictableAgeMsOverride != null ? preAuthEvictableAgeMsOverride : defaultEvictAge;
        double srcRate = sourceRateOverride == null ? 0.0 : sourceRateOverride;
        double srcBurst = sourceBurstOverride == null ? (srcRate > 0 ? srcRate * 10.0 : 0.0) : sourceBurstOverride;
        return new ConnectionAdmissionPolicy(maxConnections, handshakeTimeoutMs, rate, burst,
                budget, earlyStart, evictAge, srcRate, srcBurst);
    }

    /**
     * 解析规范十进制环境变量（不接受 0、前导 +、空白或非数字，写错即抛异常拒绝启动）。
     *
     * @param name 变量名（仅用于报错提示）
     * @param raw  原始字符串；{@code null} 表示未配置返回 {@code null}
     */
    public static Integer parseStrictPositiveInt(String name, String raw, int min, int max) {
        Objects.requireNonNull(name, "name");
        if (raw == null) {
            return null;
        }
        if (raw.isEmpty() || !raw.chars().allMatch(c -> c >= '0' && c <= '9') || (raw.length() > 1 && raw.charAt(0) == '0')) {
            throw new IllegalArgumentException(name + " 必须为规范正十进制整数: \"" + raw + "\"");
        }
        long val;
        try {
            val = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " 越界: \"" + raw + "\"", e);
        }
        if (val < min || val > max) {
            throw new IllegalArgumentException(name + " 必须在 [" + min + ", " + max + "] 之间: " + val);
        }
        return (int) val;
    }

    /** 从系统环境变量读取 gate 或 battle 的覆写配置并构造策略（prefix 如 {@code "GATE_"} 或 {@code "BATTLE_"}）。 */
    public static ConnectionAdmissionPolicy fromEnv(String prefix, int maxConnections, long handshakeTimeoutMs) {
        return fromEnv(prefix, maxConnections, handshakeTimeoutMs, System::getenv);
    }

    public static ConnectionAdmissionPolicy fromEnv(String prefix, int maxConnections, long handshakeTimeoutMs,
                                                    java.util.function.Function<String, String> env) {
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(env, "env");
        Integer rate = parseStrictPositiveInt(prefix + "ACCEPT_RATE_PER_SECOND",
                env.apply(prefix + "ACCEPT_RATE_PER_SECOND"), MIN_RATE, MAX_RATE);
        Integer burst = parseStrictPositiveInt(prefix + "ACCEPT_BURST",
                env.apply(prefix + "ACCEPT_BURST"), MIN_RATE, MAX_RATE);
        Integer budget = parseStrictPositiveInt(prefix + "PREAUTH_BUDGET",
                env.apply(prefix + "PREAUTH_BUDGET"), 1, MAX_RATE);
        Integer earlyStart = parseStrictPositiveInt(prefix + "PREAUTH_EARLY_DROP_START",
                env.apply(prefix + "PREAUTH_EARLY_DROP_START"), 1, MAX_RATE);
        Integer evictAge = parseStrictPositiveInt(prefix + "PREAUTH_EVICTABLE_AGE_MS",
                env.apply(prefix + "PREAUTH_EVICTABLE_AGE_MS"), 1, MAX_RATE);
        Integer srcRate = parseStrictPositiveInt(prefix + "SOURCE_RATE_PER_SECOND",
                env.apply(prefix + "SOURCE_RATE_PER_SECOND"), MIN_RATE, MAX_RATE);
        Integer srcBurst = parseStrictPositiveInt(prefix + "SOURCE_BURST",
                env.apply(prefix + "SOURCE_BURST"), MIN_RATE, MAX_RATE);
        return of(maxConnections, handshakeTimeoutMs, rate, burst, budget, earlyStart,
                evictAge == null ? null : evictAge.longValue(),
                srcRate == null ? null : srcRate.doubleValue(),
                srcBurst == null ? null : srcBurst.doubleValue());
    }

    public boolean enabled() {
        return maxConnections > 0;
    }

    private static void validateRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " 必须在 [" + min + ", " + max + "] 之间: " + value);
        }
    }
}
