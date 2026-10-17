package com.game.match.global;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("game.match.global")
public record GlobalMatchProperties(boolean enabled, boolean requireTiDb, Integer waitSeconds, Integer hardLatencyMs,
                                    Integer profileTtlSeconds, String probeSecret) {
    public GlobalMatchProperties {
        waitSeconds = waitSeconds == null ? 30 : waitSeconds;
        hardLatencyMs = hardLatencyMs == null ? 180 : hardLatencyMs;
        profileTtlSeconds = profileTtlSeconds == null ? 120 : profileTtlSeconds;
        probeSecret = probeSecret == null ? "" : probeSecret;
        if (enabled && (probeSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32 || waitSeconds < 1
                || hardLatencyMs < 20 || hardLatencyMs > 1000 || profileTtlSeconds < 30 || profileTtlSeconds > 600))
            throw new IllegalArgumentException("全球匹配配置或 MATCH_PROBE_SECRET 无效");
    }
}
