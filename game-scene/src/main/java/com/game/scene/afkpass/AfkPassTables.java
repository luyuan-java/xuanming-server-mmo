package com.game.scene.afkpass;

import com.game.table.AfkPassRuleTable;
import com.game.table.ConfigTables;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 挂机月卡规则表视图（{@code AfkPassRule}，单行全局规则 id=1）。
 *
 * <p>对齐权威表约束：取值越界或缺行时记 ERROR、置为不可用（{@link #rule()} 返回 null），
 * 令 {@code BuyAfkPass} fail-closed 返回 {@code kAfkPassUnavailable(32004)}，但不影响 GM 与已开通玩家的离线托管。
 */
public final class AfkPassTables {

    private static final Logger log = LoggerFactory.getLogger(AfkPassTables.class);
    public static final int ROW_ID = 1;

    public record Rule(
            int periodDays,
            int priceCurrencyType,
            long price,
            int maxRemainingDays,
            int remindBeforeHours) {
    }

    private final Rule rule;

    private AfkPassTables(Rule rule) {
        this.rule = rule;
    }

    public static AfkPassTables from(ConfigTables tables) {
        Objects.requireNonNull(tables, "tables");
        AfkPassRuleTable row = tables.afkPassRule().find(ROW_ID).orElse(null);
        if (row == null) {
            log.error("[AfkPass] AfkPassRule 表缺少 id={} 规则行，开通入口将返回 kAfkPassUnavailable", ROW_ID);
            return unavailable();
        }
        return fromRow(row);
    }

    public static AfkPassTables fromRow(AfkPassRuleTable row) {
        if (row == null) {
            return unavailable();
        }
        int periodDays = row.getPeriodDays();
        int currencyType = row.getPriceCurrencyType();
        long price = row.getPrice();
        int maxRemainingDays = row.getMaxRemainingDays();
        int remindHours = row.getRemindBeforeHours();
        if (periodDays < 1 || periodDays > AfkPassRules.MAX_PERIOD_DAYS
                || currencyType < 0 || currencyType > 2
                || price < 1 || price > AfkPassRules.MAX_PRICE
                || maxRemainingDays < periodDays || maxRemainingDays > AfkPassRules.MAX_REMAINING_DAYS
                || remindHours < 0 || remindHours > AfkPassRules.MAX_REMIND_BEFORE_HOURS) {
            log.error("[AfkPass] AfkPassRule 规则行越界 period_days={} currency={} price={} max_remaining={} remind_hours={}",
                    periodDays, currencyType, price, maxRemainingDays, remindHours);
            return unavailable();
        }
        return new AfkPassTables(new Rule(periodDays, currencyType, price, maxRemainingDays, remindHours));
    }

    public static AfkPassTables of(Rule rule) {
        return new AfkPassTables(rule);
    }

    public static AfkPassTables unavailable() {
        return new AfkPassTables(null);
    }

    /** 有效规则；不可用返回 null。 */
    public Rule rule() {
        return rule;
    }
}
