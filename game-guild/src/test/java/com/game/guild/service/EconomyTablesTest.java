package com.game.guild.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.guild.rules.GuildLimits;
import com.game.guild.store.EconomyStore;
import com.game.table.ConfigTables;
import com.game.table.GuildDonateTable;
import com.game.table.GuildLevelTable;
import com.game.table.GuildRuleTable;
import com.game.table.GuildShopTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * 帮会经济配表校验与现查（照 mmorpg economy_config_test.go 全部用例；guild-economy-spec §0.5、§11.1 EconomyTablesTest）。
 * 坏样例逐条在合法样例上改一处；真实配表（config-data/tables）必须通过，且与 §0.5 写下的当前数据一致。
 */
class EconomyTablesTest {

    // ================================================================ 合法样例（GuildDonate.xlsx / GuildShop.xlsx 默认行）

    private static GuildRuleTable legalRule() {
        return GuildRuleTable.newBuilder().setId(1).setApplicationExpireHours(72).setMaxPendingApplicationsPerPlayer(3)
                .setMaxPendingApplicationsPerGuild(50).setAssetOpDeadlineSeconds(600).setAssetOpRetryBaseMs(1000)
                .setReunionMinOnlineMembers(3).build();
    }

    private static List<GuildLevelTable> legalLevels() {
        long[][] rows = {
                {1, 30, 2, 20000}, {2, 35, 2, 50000}, {3, 40, 3, 100000}, {4, 45, 3, 180000}, {5, 50, 4, 300000},
                {6, 60, 4, 460000}, {7, 70, 5, 680000}, {8, 80, 5, 960000}, {9, 90, 6, 1300000}, {10, 100, 6, 0}};
        List<GuildLevelTable> levels = new ArrayList<>();
        for (long[] r : rows) {
            levels.add(GuildLevelTable.newBuilder().setId((int) r[0]).setMaxMembers((int) r[1]).setMaxOfficers((int) r[2])
                    .setUpgradeCostFunds(r[3]).build());
        }
        return levels;
    }

    private static GuildDonateTable donate(int id, String name, int currency, long cost, long contribution, long funds,
                                           int daily, int minLevel) {
        return GuildDonateTable.newBuilder().setId(id).setName(name).setCurrencyType(currency).setCostAmount(cost)
                .setContributionGain(contribution).setFundsGain(funds).setDailyLimit(daily).setMinGuildLevel(minLevel).build();
    }

    private static List<GuildDonateTable> legalDonates() {
        return new ArrayList<>(List.of(
                donate(1, "银两小捐", 0, 10000, 10, 1000, 5, 1),
                donate(2, "银两大捐", 0, 100000, 120, 12000, 2, 1),
                donate(3, "灵石捐献", 1, 100, 200, 20000, 1, 1)));
    }

    private static GuildShopTable shop(int id, String name, int category, int item, int count, long cost, int level, int period,
                                       int limit) {
        return GuildShopTable.newBuilder().setId(id).setName(name).setCategory(category).setItemId(item).setItemCount(count)
                .setCostContribution(cost).setRequiredGuildLevel(level).setLimitPeriod(period).setLimitCount(limit).build();
    }

    private static List<GuildShopTable> legalShops() {
        return new ArrayList<>(List.of(
                shop(101, "培元丹", 1, 15, 5, 30, 1, 1, 10),
                shop(102, "回灵散", 1, 16, 5, 30, 1, 1, 10),
                shop(103, "精炼石", 1, 17, 1, 80, 2, 1, 5),
                shop(104, "修行秘录残页", 1, 18, 1, 150, 3, 2, 5),
                shop(201, "帮会令牌", 2, 19, 1, 300, 3, 2, 3),
                shop(202, "玄铁护符", 2, 12, 1, 800, 4, 2, 1),
                shop(203, "灵兽口粮", 2, 20, 10, 120, 2, 1, 3),
                shop(204, "藏经阁手札", 2, 13, 1, 1500, 6, 2, 1),
                shop(301, "花灯", 3, 21, 1, 50, 1, 1, 5),
                shop(302, "月饼礼盒", 3, 22, 1, 100, 1, 2, 7),
                shop(303, "同心结", 3, 23, 1, 200, 5, 0, 0)));
    }

    /** 商品引用的物品及其 max_stack_size：15–23 为 999，12、13 为 1。 */
    private static Map<Integer, Integer> legalStacks() {
        Map<Integer, Integer> stacks = new HashMap<>();
        for (int item = 15; item <= 23; item++) {
            stacks.put(item, 999);
        }
        stacks.put(12, 1);
        stacks.put(13, 1);
        return stacks;
    }

    private static EconomyTables.ItemStacks stacks(Map<Integer, Integer> map) {
        return item -> map.containsKey(item) ? OptionalInt.of(map.get(item)) : OptionalInt.empty();
    }

    /** 一份可改的合法输入；每个用例重新构造。 */
    private static final class Input {
        GuildRuleTable rule = legalRule();
        List<GuildLevelTable> levels = legalLevels();
        List<GuildDonateTable> donates = legalDonates();
        List<GuildShopTable> shops = legalShops();
        EconomyTables.ItemStacks stacks = stacks(legalStacks());

        void validate() {
            EconomyTables.validate(rule, levels, donates, shops, stacks);
        }

        void donate(int index, Consumer<GuildDonateTable.Builder> mutate) {
            GuildDonateTable.Builder b = donates.get(index).toBuilder();
            mutate.accept(b);
            donates.set(index, b.build());
        }

        void shop(int index, Consumer<GuildShopTable.Builder> mutate) {
            GuildShopTable.Builder b = shops.get(index).toBuilder();
            mutate.accept(b);
            shops.set(index, b.build());
        }
    }

    @Test
    void 默认行能通过() {
        assertThatCode(() -> new Input().validate()).doesNotThrowAnyException();
    }

    @Test
    void 每条规则各造一个坏样例_文案带表名或字段名() {
        record Case(String name, Consumer<Input> mutate, String wantContains) {
        }
        List<Case> cases = List.of(
                // 先走 GuildTableRules.validate（结构规则只写一份）
                new Case("缺少规则行", in -> in.rule = null, "GuildRule"),
                new Case("等级表为空", in -> in.levels = List.of(), "GuildLevel"),
                // GuildRule 的两列资产参数
                new Case("截止时长 59 秒", in -> in.rule = in.rule.toBuilder().setAssetOpDeadlineSeconds(59).build(),
                        "asset_op_deadline_seconds"),
                new Case("截止时长超过一天", in -> in.rule = in.rule.toBuilder().setAssetOpDeadlineSeconds(86_401).build(),
                        "asset_op_deadline_seconds"),
                new Case("截止时长按无符号看是大值", in -> in.rule = in.rule.toBuilder().setAssetOpDeadlineSeconds(-1).build(),
                        "asset_op_deadline_seconds"),
                new Case("重投基准 99 毫秒", in -> in.rule = in.rule.toBuilder().setAssetOpRetryBaseMs(99).build(),
                        "asset_op_retry_base_ms"),
                new Case("重投基准超过 60 秒", in -> in.rule = in.rule.toBuilder().setAssetOpRetryBaseMs(60_001).build(),
                        "asset_op_retry_base_ms"),
                new Case("升级花费超过 1e12", in -> in.levels.set(0, in.levels.get(0).toBuilder()
                        .setUpgradeCostFunds(EconomyTables.MAX_UPGRADE_COST_FUNDS + 1).build()), "upgrade_cost_funds"),
                new Case("升级花费按无符号看是大值", in -> in.levels.set(0, in.levels.get(0).toBuilder()
                        .setUpgradeCostFunds(-1L).build()), "upgrade_cost_funds"),
                // GuildDonate
                new Case("捐献有空行", in -> in.donates.add(null), "GuildDonate"),
                new Case("捐献 id 为 0", in -> in.donate(0, b -> b.setId(0)), "GuildDonate"),
                new Case("捐献 id 重复", in -> in.donate(1, b -> b.setId(1)), "GuildDonate.id"),
                new Case("绑定灵石不可捐", in -> in.donate(2, b -> b.setCurrencyType(2)), "currency_type"),
                new Case("扣款为 0", in -> in.donate(0, b -> b.setCostAmount(0)), "cost_amount"),
                new Case("扣款超过 MaxInt64", in -> in.donate(0, b -> b.setCostAmount(1L << 63)), "cost_amount"),
                new Case("两项收益都为 0", in -> in.donate(0, b -> b.setContributionGain(0).setFundsGain(0)), "contribution_gain"),
                new Case("每日次数为 0", in -> in.donate(0, b -> b.setDailyLimit(0)), "daily_limit"),
                new Case("捐献等级为 0", in -> in.donate(0, b -> b.setMinGuildLevel(0)), "min_guild_level"),
                new Case("捐献等级超过最高级", in -> in.donate(0, b -> b.setMinGuildLevel(11)), "min_guild_level"),
                new Case("同一货币三行", in -> in.donates.add(donate(4, "", 0, 1, 1, 0, 1, 1)), "currency_type=0"),
                // GuildShop
                new Case("商品有空行", in -> in.shops.add(null), "GuildShop"),
                new Case("商品 id 为 0", in -> in.shop(0, b -> b.setId(0)), "GuildShop"),
                new Case("商品 id 重复", in -> in.shop(1, b -> b.setId(101)), "GuildShop.id"),
                new Case("分类非法", in -> in.shop(0, b -> b.setCategory(4)), "category"),
                new Case("物品不存在", in -> in.shop(0, b -> b.setItemId(999_999)), "item_id"),
                new Case("物品堆叠为 0", in -> in.stacks = item -> OptionalInt.of(0), "item_id"),
                new Case("每份数量为 0", in -> in.shop(0, b -> b.setItemCount(0)), "item_count"),
                new Case("每份数量超过堆叠", in -> in.shop(0, b -> b.setItemCount(1000)), "item_count"),
                new Case("堆叠 1 的物品每份 2 个", in -> in.shop(5, b -> b.setItemCount(2)), "item_count"),
                new Case("帮贡花费为 0", in -> in.shop(0, b -> b.setCostContribution(0)), "cost_contribution"),
                new Case("帮贡花费超过 1e9", in -> in.shop(0, b -> b.setCostContribution(EconomyTables.MAX_SHOP_COST_CONTRIBUTION + 1)),
                        "cost_contribution"),
                new Case("商品等级为 0", in -> in.shop(0, b -> b.setRequiredGuildLevel(0)), "required_guild_level"),
                new Case("商品等级 11", in -> in.shop(7, b -> b.setRequiredGuildLevel(11)), "required_guild_level"),
                new Case("限购周期非法", in -> in.shop(0, b -> b.setLimitPeriod(3)), "limit_period"),
                new Case("不限购却填了份数", in -> in.shop(10, b -> b.setLimitCount(5)), "limit_count"),
                new Case("限购却填 0 份", in -> in.shop(0, b -> b.setLimitCount(0)), "limit_count"),
                new Case("缺节庆分类", in -> in.shops = new ArrayList<>(in.shops.subList(0, 8)), "category=3"),
                new Case("缺物品表查询", in -> in.stacks = null, "Item"));
        for (Case c : cases) {
            Input in = new Input();
            c.mutate().accept(in);
            assertThatThrownBy(in::validate).as(c.name()).isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(c.wantContains());
        }
    }

    // ================================================================ MaxBuyCount

    /** 单次上限的公式：堆叠 1 → 1；否则 min(20, 堆叠 / 每份数量)（TestMaxBuyCount）。客户端据它限制份数输入。 */
    @Test
    void 单次上限公式() {
        record Case(String name, GuildShopTable row, int maxStack, int want) {
        }
        GuildShopTable.Builder b = GuildShopTable.newBuilder().setId(1);
        List<Case> cases = List.of(
                new Case("培元丹：999 / 5 被 20 封顶", b.setItemCount(5).build(), 999, GuildLimits.MAX_SHOP_BUY_COUNT),
                new Case("灵兽口粮：999 / 10 被 20 封顶", b.setItemCount(10).build(), 999, GuildLimits.MAX_SHOP_BUY_COUNT),
                new Case("堆叠 1 恒为 1", b.setItemCount(1).build(), 1, 1),
                new Case("堆叠 30 每份 10 → 3", b.setItemCount(10).build(), 30, 3),
                new Case("恰好 20", b.setItemCount(5).build(), 100, 20),
                new Case("每份数量超过堆叠 → 0", b.setItemCount(20).build(), 10, 0),
                new Case("每份数量为 0 → 0", b.setItemCount(0).build(), 999, 0),
                new Case("堆叠为 0 → 0", b.setItemCount(5).build(), 0, 0),
                new Case("行为空 → 0", null, 999, 0),
                new Case("堆叠按无符号看是大值", b.setItemCount(1).build(), -1, 20));
        for (Case c : cases) {
            assertThat(EconomyTables.maxBuyCount(c.row(), c.maxStack())).as(c.name()).isEqualTo(c.want());
        }
        assertThat(GuildLimits.MAX_SHOP_BUY_COUNT).isEqualTo(20);
    }

    /** 默认商店行的单次上限（202 / 204 = 1，其余 20；TestDefaultShopRowsBuyCounts）。 */
    @Test
    void 默认商店行的单次上限() {
        Map<Integer, Integer> want = Map.ofEntries(Map.entry(101, 20), Map.entry(102, 20), Map.entry(103, 20),
                Map.entry(104, 20), Map.entry(201, 20), Map.entry(202, 1), Map.entry(203, 20), Map.entry(204, 1),
                Map.entry(301, 20), Map.entry(302, 20), Map.entry(303, 20));
        Map<Integer, Integer> stacks = legalStacks();
        for (GuildShopTable row : legalShops()) {
            assertThat(EconomyTables.maxBuyCount(row, stacks.get(row.getItemId()))).as("GuildShop[%d]", row.getId())
                    .isEqualTo(want.get(row.getId()));
        }
    }

    // ================================================================ 时序交叉校验

    /** 配置与配表各自合法、组合起来会出错的两条交叉约束（TestValidateAssetOpTiming）。 */
    @Test
    void 时序交叉校验() {
        Duration lease = Duration.ofSeconds(10);
        Duration interval = Duration.ofSeconds(2);
        Duration maxBackoff = Duration.ofSeconds(60);
        GuildRuleTable rule60 = legalRule().toBuilder().setAssetOpDeadlineSeconds(60).setAssetOpRetryBaseMs(1000).build();

        assertThatCode(() -> EconomyTables.validateAssetOpTiming(legalRule(), lease, interval, maxBackoff))
                .as("默认配置与默认配表").doesNotThrowAnyException();
        assertThatCode(() -> EconomyTables.validateAssetOpTiming(rule60, lease, interval, maxBackoff))
                .as("截止取配表下限 60 s 仍接受默认配置").doesNotThrowAnyException();
        assertThatThrownBy(() -> EconomyTables.validateAssetOpTiming(rule60, Duration.ofSeconds(120), interval, maxBackoff))
                .as("租约 120 s 对截止 60 s").hasMessageContaining("asset_op_deadline_seconds");
        assertThatThrownBy(() -> EconomyTables.validateAssetOpTiming(rule60, Duration.ofSeconds(58), interval, maxBackoff))
                .as("租约加一个对账间隔恰好等于截止：循环第一次领到它时恰好到截止，按 now ≥ deadline 改发中止")
                .isInstanceOf(IllegalStateException.class);
        GuildRuleTable base5s = legalRule().toBuilder().setAssetOpDeadlineSeconds(600).setAssetOpRetryBaseMs(5000).build();
        assertThatThrownBy(() -> EconomyTables.validateAssetOpTiming(base5s, lease, interval, Duration.ofSeconds(2)))
                .as("退避基数大于封顶").hasMessageContaining("asset_op_retry_base_ms");
        GuildRuleTable base2s = legalRule().toBuilder().setAssetOpDeadlineSeconds(600).setAssetOpRetryBaseMs(2000).build();
        assertThatCode(() -> EconomyTables.validateAssetOpTiming(base2s, lease, interval, Duration.ofSeconds(2)))
                .as("退避基数等于封顶").doesNotThrowAnyException();
    }

    // ================================================================ 真实配表与现查

    private static Path tableDir() {
        return Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
    }

    /** 真实配表必须通过，且与规格 §0.5 写下的当前数据一致（数据变了先改规格与期望值）。 */
    @Test
    void 真实配表通过且现查与当前数据一致() {
        ConfigTables tables = ConfigTables.load(tableDir());
        assertThatCode(() -> EconomyTables.validate(tables)).doesNotThrowAnyException();
        assertThatCode(() -> EconomyTables.validateAssetOpTiming(tables, Duration.ofSeconds(10), Duration.ofSeconds(2),
                Duration.ofSeconds(60))).doesNotThrowAnyException();

        EconomyTables.Lookup lookup = EconomyTables.Lookup.of(() -> tables);
        assertThat(lookup.assetOpDeadlineMs()).hasValue(600_000L);
        assertThat(lookup.assetOpRetryBaseMs()).isEqualTo(1_000L);
        assertThat(lookup.upgradeLevel(1)).isEqualTo(new EconomyStore.UpgradeLevel(20_000, 30));
        assertThat(lookup.upgradeLevel(2)).isEqualTo(new EconomyStore.UpgradeLevel(50_000, 35));
        assertThat(lookup.upgradeLevel(10)).isEqualTo(new EconomyStore.UpgradeLevel(0, 100));
        assertThat(lookup.upgradeLevel(11)).as("缺行 → null（仓储回 LEVEL_CONFIG_MISSING）").isNull();
        EconomyStore.UpgradeLevels asStoreLookup = lookup::upgradeLevel;
        assertThat(asStoreLookup.find(3)).isEqualTo(new EconomyStore.UpgradeLevel(100_000, 40));

        assertThat(lookup.sortedDonateRows()).extracting(GuildDonateTable::getId).containsExactly(1, 2, 3);
        assertThat(lookup.donateOption(2).getCostAmount()).isEqualTo(100_000);
        assertThat(lookup.donateOption(0)).isNull();
        assertThat(lookup.sortedShopRows()).extracting(GuildShopTable::getId)
                .containsExactly(101, 102, 103, 104, 201, 202, 203, 204, 301, 302, 303);
        assertThat(lookup.shopGoods(202).getItemId()).isEqualTo(12);
        assertThat(lookup.shopGoods(999)).isNull();
        assertThat(lookup.itemMaxStack(12)).hasValue(1);
        assertThat(lookup.itemMaxStack(15)).hasValue(999);
        assertThat(lookup.itemMaxStack(999_999)).isEmpty();
        for (GuildShopTable row : lookup.sortedShopRows()) {
            int want = row.getId() == 202 || row.getId() == 204 ? 1 : 20;
            assertThat(EconomyTables.maxBuyCount(row, lookup.itemMaxStack(row.getItemId()).orElseThrow()))
                    .as("GuildShop[%d]", row.getId()).isEqualTo(want);
        }
    }

    /** 排序先复制、跳过 null、不动入参；(category, id) 无符号升序。 */
    @Test
    void 排序复制且跳过空行() {
        List<GuildShopTable> shops = new ArrayList<>(legalShops());
        java.util.Collections.reverse(shops);
        shops.add(3, null);
        List<GuildShopTable> before = new ArrayList<>(shops);
        assertThat(EconomyTables.sortedShopRows(shops)).extracting(GuildShopTable::getId)
                .containsExactly(101, 102, 103, 104, 201, 202, 203, 204, 301, 302, 303);
        assertThat(shops).isEqualTo(before);

        List<GuildDonateTable> donates = new ArrayList<>(legalDonates());
        donates.add(donate(-1, "大 id", 1, 1, 1, 1, 1, 1));
        java.util.Collections.reverse(donates);
        assertThat(EconomyTables.sortedDonateRows(donates)).extracting(GuildDonateTable::getId)
                .as("id 按无符号排：-1 = 4294967295 排最后").containsExactly(1, 2, 3, -1);
    }
}
