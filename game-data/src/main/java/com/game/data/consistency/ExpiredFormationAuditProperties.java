package com.game.data.consistency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 周期只读巡检的固定上限；配置只能收紧每轮数据库负担。 */
@ConfigurationProperties("game.data.consistency.expired-formations")
public record ExpiredFormationAuditProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("60s") Duration interval,
        @DefaultValue("3s") Duration budget,
        @DefaultValue("150s") Duration staleAfter,
        @DefaultValue("128") int pageSize,
        @DefaultValue("1024") int maxRows) {
    public ExpiredFormationAuditProperties {
        if (interval == null || interval.toMillis() < 1_000 || interval.toMillis() > 300_000
                || budget == null || budget.toMillis() < 100 || budget.toMillis() > 3_000
                || staleAfter == null || staleAfter.compareTo(interval.plus(budget)) < 0
                || staleAfter.toMillis() > 900_000 || pageSize < 1 || pageSize > 256
                || maxRows < 1 || maxRows > 4_096) {
            throw new IllegalArgumentException("到期成局只读巡检的周期、预算或分页配置非法");
        }
    }
}
