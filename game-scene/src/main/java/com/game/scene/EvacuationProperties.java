package com.game.scene;

import com.game.scene.world.EvacuationPolicy;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 节点疏散的有界并发、重试和停机预算；旧单节点部署可关闭停机前迁移。 */
@ConfigurationProperties("game.scene.evacuation")
public record EvacuationProperties(
        @DefaultValue("32") int maxInFlight,
        @DefaultValue("1s") Duration retryInitial,
        @DefaultValue("30s") Duration retryMaximum,
        @DefaultValue("15s") Duration budget,
        @DefaultValue("true") boolean evacuateBeforeShutdown) {
    public EvacuationProperties {
        new EvacuationPolicy(maxInFlight, retryInitial, retryMaximum);
        if (budget == null || budget.isNegative() || budget.isZero() || budget.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("疏散预算须大于0且不超过1分钟");
        }
    }
    public static EvacuationProperties defaults() {
        return new EvacuationProperties(32, Duration.ofSeconds(1), Duration.ofSeconds(30), Duration.ofSeconds(15), true);
    }
    public EvacuationPolicy policy() { return new EvacuationPolicy(maxInFlight, retryInitial, retryMaximum); }
}
