package com.game.scene.afkpass;

import com.game.scene.player.PlayerTravelPass;
import com.game.table.AfkPassRuleTable;
import com.game.table.ConfigTables;
import com.game.table.ItemTable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 挂机月卡/季享卡多套餐规则表视图（{@code AfkPassRule}，id=1 为 30 天轻享卡，id=2 为 90 天季享卡）。
 *
 * <p>对齐权威表约束：任一套餐越界或引用的开卡直赠道具/头像框非法时记 ERROR 并 fail-closed 拒开放。
 */
public final class AfkPassTables {

    private static final Logger log = LoggerFactory.getLogger(AfkPassTables.class);
    public static final int ROW_ID = 1;

    public record Rule(
            int planId,
            int periodDays,
            int priceCurrencyType,
            long price,
            int maxRemainingDays,
            int remindBeforeHours,
            int bonusItemId,
            int bonusItemCount,
            String bonusItemName,
            String bonusFrameKey,
            String planName,
            String iconKey) {

        public Rule(int periodDays, int priceCurrencyType, long price, int maxRemainingDays, int remindBeforeHours) {
            this(ROW_ID, periodDays, priceCurrencyType, price, maxRemainingDays, remindBeforeHours,
                    0, 0, "", "", "", "");
        }
    }

    private final List<Rule> plans;
    private final Map<Integer, Rule> byPlanId;

    private AfkPassTables(List<Rule> plans) {
        this.plans = List.copyOf(plans);
        Map<Integer, Rule> map = new LinkedHashMap<>();
        for (Rule r : plans) {
            map.put(r.planId(), r);
        }
        this.byPlanId = Map.copyOf(map);
    }

    public static AfkPassTables from(ConfigTables tables) {
        Objects.requireNonNull(tables, "tables");
        List<AfkPassRuleTable> rows = new ArrayList<>(tables.afkPassRule().all());
        if (rows.isEmpty()) {
            log.error("[AfkPass] AfkPassRule 表为空，开通入口将返回 kAfkPassUnavailable");
            return unavailable();
        }
        rows.sort(Comparator.comparingInt(AfkPassRuleTable::getId));
        List<Rule> parsed = new ArrayList<>(rows.size());
        for (AfkPassRuleTable row : rows) {
            Rule r = parseRow(row, tables);
            if (r == null) {
                return unavailable();
            }
            parsed.add(r);
        }
        return new AfkPassTables(parsed);
    }

    public static AfkPassTables fromRow(AfkPassRuleTable row) {
        Rule parsed = parseRow(row, null);
        return parsed == null ? unavailable() : new AfkPassTables(List.of(parsed));
    }

    private static Rule parseRow(AfkPassRuleTable row, ConfigTables tables) {
        if (row == null) {
            return null;
        }
        int planId = row.getId();
        int periodDays = row.getPeriodDays();
        int currencyType = row.getPriceCurrencyType();
        long price = row.getPrice();
        int maxRemainingDays = row.getMaxRemainingDays();
        int remindHours = row.getRemindBeforeHours();
        int bonusItemId = row.getBonusItemId();
        int bonusItemCount = row.getBonusItemCount();
        String bonusFrameKey = row.getBonusFrameKey();
        if (planId <= 0
                || periodDays < 1 || periodDays > AfkPassRules.MAX_PERIOD_DAYS
                || currencyType < 0 || currencyType > 2
                || price < 1 || price > AfkPassRules.MAX_PRICE
                || maxRemainingDays < periodDays || maxRemainingDays > AfkPassRules.MAX_REMAINING_DAYS
                || remindHours < 0 || remindHours > AfkPassRules.MAX_REMIND_BEFORE_HOURS
                || ((bonusItemId == 0) != (bonusItemCount == 0))
                || bonusItemCount < 0 || bonusItemCount > 99
                || (!bonusFrameKey.isEmpty() && !PlayerTravelPass.isValidCosmeticKey(bonusFrameKey))) {
            log.error("[AfkPass] AfkPassRule 规则行越界 plan_id={} period_days={} currency={} price={} max_remaining={} remind_hours={}",
                    planId, periodDays, currencyType, price, maxRemainingDays, remindHours);
            return null;
        }
        String bonusItemName = "";
        if (bonusItemId != 0 && tables != null) {
            ItemTable item = tables.item().find(bonusItemId).orElse(null);
            if (item == null) {
                log.error("[AfkPass] AfkPassRule plan_id={} 引用的直赠道具 bonus_item_id={} 不存在", planId, bonusItemId);
                return null;
            }
            bonusItemName = item.getName();
        }
        return new Rule(planId, periodDays, currencyType, price, maxRemainingDays, remindHours,
                bonusItemId, bonusItemCount, bonusItemName, bonusFrameKey, row.getPlanName(), row.getIconKey());
    }

    public static AfkPassTables of(Rule rule) {
        return rule == null ? unavailable() : new AfkPassTables(List.of(rule));
    }

    public static AfkPassTables unavailable() {
        return new AfkPassTables(List.of());
    }

    /** 默认套餐规则（id=1 或首项）；不可用返回 null。 */
    public Rule rule() {
        if (plans.isEmpty()) {
            return null;
        }
        Rule first = byPlanId.get(ROW_ID);
        return first != null ? first : plans.getFirst();
    }

    /** 按 plan_id 查找套餐；planId==0 返回默认套餐，查不到返回 null。 */
    public Rule plan(int planId) {
        if (plans.isEmpty()) {
            return null;
        }
        if (planId == 0) {
            return rule();
        }
        return byPlanId.get(planId);
    }

    public List<Rule> plans() {
        return plans;
    }
}
