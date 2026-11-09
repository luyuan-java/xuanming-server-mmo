package com.game.data.consistency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 终局发件箱只读采样；独立于异常成局分页的预算和生命周期。 */
@ConfigurationProperties("game.data.consistency.terminal-backlog")
public record TerminalOutboxBacklogProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") Duration interval,
        @DefaultValue("3s") Duration budget,
        @DefaultValue("150s") Duration staleAfter) {
    public TerminalOutboxBacklogProperties {
        if (interval == null || interval.toMillis() < 1_000 || interval.toMillis() > 300_000
                || budget == null || budget.toMillis() < 100 || budget.toMillis() > 3_000
                || staleAfter == null || staleAfter.compareTo(interval.plus(budget)) < 0
                || staleAfter.toMillis() > 900_000) {
            throw new IllegalArgumentException("终局发件箱只读采样的周期、预算或有效期非法");
        }
    }
}
