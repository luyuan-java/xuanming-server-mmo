package com.game.gateway.ratelimit;

/**
 * 限流裁决（同基线 RateLimitDecision）：放行；排队（100，客户端过 {@code retryAfterMs} 再来，{@code queuePos} 是估计值，
 * 分波未开放时 -1）；拒绝（429，{@code reason} = {@code IP_RATE_LIMIT} / {@code ACCOUNT_COOLDOWN}）。
 */
public record RateLimitDecision(Kind kind, String reason, long retryAfterMs, long queuePos) {

    public enum Kind { PASS, QUEUE, DENY }

    public static final String IP_RATE_LIMIT = "IP_RATE_LIMIT";
    public static final String ACCOUNT_COOLDOWN = "ACCOUNT_COOLDOWN";

    private static final RateLimitDecision PASS = new RateLimitDecision(Kind.PASS, null, 0, 0);

    public static RateLimitDecision pass() {
        return PASS;
    }

    public static RateLimitDecision queue(long retryAfterMs, long queuePos) {
        return new RateLimitDecision(Kind.QUEUE, "QUEUEING", retryAfterMs, queuePos);
    }

    public static RateLimitDecision deny(String reason) {
        return new RateLimitDecision(Kind.DENY, reason, 0, 0);
    }
}
