package com.game.data.consume;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 独立于消费线程的只读组级探针；不能以关闭探针隐藏 UNKNOWN。 */
@ConfigurationProperties("game.data.audit-probe")
public record AuditProbeProperties(@DefaultValue("30s") Duration interval,
                                   @DefaultValue("10s") Duration timeout,
                                   @DefaultValue("90s") Duration staleAfter) {
    public AuditProbeProperties {
        if (interval == null || timeout == null || staleAfter == null
                || interval.compareTo(Duration.ofSeconds(5)) < 0
                || interval.compareTo(Duration.ofMinutes(10)) > 0
                || timeout.compareTo(Duration.ofMillis(100)) < 0
                || timeout.compareTo(Duration.ofSeconds(30)) > 0
                || staleAfter.compareTo(interval.plus(timeout.multipliedBy(2))) < 0
                || staleAfter.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException("audit-probe：interval ∈ [5s,10m]，timeout ∈ [100ms,30s]，"
                    + "stale-after ∈ [interval + 2 × timeout,1h]");
        }
    }
}
