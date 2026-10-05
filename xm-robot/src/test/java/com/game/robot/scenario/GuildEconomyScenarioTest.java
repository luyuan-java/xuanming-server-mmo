package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.time.GameDay;
import com.game.contract.MessageIdRegistry;
import com.game.proto.BagInfo;
import com.game.proto.BagItemInfo;
import com.game.proto.CurrencyComp;
import com.game.proto.guild.GetGuildDonateOptionsResponse;
import com.game.proto.guild.GetGuildShopResponse;
import com.game.proto.guild.GuildAssetOrderStatus;
import com.game.proto.guild.GuildDonateOptionView;
import com.game.proto.guild.GuildDonationView;
import com.game.proto.guild.GuildInfo;
import com.game.proto.guild.GuildShopGoodsView;
import com.game.proto.guild.GuildShopOrderView;
import com.game.robot.RobotOptions;
import com.game.robot.UsageException;
import com.game.table.ConfigTables;
import com.game.table.GuildDonateTable;
import com.game.table.GuildLevelTable;
import com.game.table.GuildShopTable;
import com.game.table.ItemTable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** guild-economy 场景里不连服务端就能钉住的部分：写死的期望值与配表、消息号与限频、切点判定、视图判定、无符号输出、子命令与账号。 */
class GuildEconomyScenarioTest {

    /** 大于 Long.MAX_VALUE 的号（无符号 uint64）。 */
    private static final long BIG = 0x8000_0000_0000_0001L;
    /** 单次最多份数封顶（基线 MaxShopBuyCount，constants.go:179）。 */
    private static final int MAX_SHOP_BUY_COUNT = 20;

    @Test
    void 写死的捐献与升级期望值与同步来的配表一致() {
        ConfigTables tables = tables();
        assertThat(tables.guildDonate().size()).isEqualTo(GuildEconomyScenario.DONATE_OPTION_COUNT);
        for (GuildEconomyScenario.DonateRow row : List.of(GuildEconomyScenario.DONATE_BIG, GuildEconomyScenario.DONATE_SPIRIT)) {
            GuildDonateTable t = tables.guildDonate().get(row.id());
            assertThat(new GuildEconomyScenario.DonateRow(t.getId(), t.getCurrencyType(), t.getCostAmount(), t.getContributionGain(),
                    t.getFundsGain(), t.getDailyLimit(), t.getMinGuildLevel())).isEqualTo(row);
            assertThat(t.getName()).as("捐献页按名字展示").isNotEmpty();
        }
        assertThat(GuildEconomyScenario.DONATE_BIG.currencyType()).isEqualTo(GuildEconomyScenario.CURRENCY_SILVER);
        assertThat(GuildEconomyScenario.DONATE_SPIRIT.currencyType()).isEqualTo(GuildEconomyScenario.CURRENCY_SPIRIT);
        assertThat(tables.guildDonate().contains(GuildScenario.UNKNOWN_DONATE_ID)).as("未知捐献项必须查不到").isFalse();
        // 第 6 步要撞上限、第 7 步资金要够升级：两次大捐 + 灵石捐的资金减去 1 级花费 = 第 9 步的 24,000
        assertThat(GuildEconomyScenario.DONATE_BIG.dailyLimit()).isEqualTo(2);
        GuildLevelTable level1 = tables.guildLevel().get(1);
        GuildLevelTable level2 = tables.guildLevel().get(2);
        assertThat(level1.getUpgradeCostFunds()).isEqualTo(GuildEconomyScenario.LEVEL1_UPGRADE_COST);
        assertThat(level2.getUpgradeCostFunds()).isEqualTo(GuildEconomyScenario.LEVEL2_UPGRADE_COST);
        assertThat(level1.getMaxMembers()).isEqualTo(GuildEconomyScenario.LEVEL1_MAX_MEMBERS);
        assertThat(level2.getMaxMembers()).isEqualTo(GuildEconomyScenario.LEVEL2_MAX_MEMBERS);
        long funds = 2 * GuildEconomyScenario.DONATE_BIG.fundsGain() + GuildEconomyScenario.DONATE_SPIRIT.fundsGain();
        assertThat(funds).isGreaterThanOrEqualTo(GuildEconomyScenario.LEVEL1_UPGRADE_COST);
        assertThat(funds - GuildEconomyScenario.LEVEL1_UPGRADE_COST).isEqualTo(24_000)
                .as("第 9 步 Lv.2 再升要撞 14022").isLessThan(GuildEconomyScenario.LEVEL2_UPGRADE_COST);
        // GM 发的钱刚好够第 5–7 步
        assertThat(GuildEconomyScenario.GM_SILVER).isGreaterThanOrEqualTo(2 * GuildEconomyScenario.DONATE_BIG.cost());
        assertThat(GuildEconomyScenario.GM_SPIRIT).isGreaterThanOrEqualTo(GuildEconomyScenario.DONATE_SPIRIT.cost());
    }

    @Test
    void 写死的商品期望值与同步来的配表和物品堆叠一致() {
        ConfigTables tables = tables();
        assertThat(tables.guildShop().size()).isEqualTo(GuildEconomyScenario.SHOP_GOODS_COUNT);
        for (GuildEconomyScenario.GoodsRow row : GuildEconomyScenario.CHECKED_GOODS) {
            GuildShopTable t = tables.guildShop().get(row.id());
            ItemTable item = tables.item().get(t.getItemId());
            int maxBuy = maxBuyCount(item.getMaxStackSize(), t.getItemCount());
            assertThat(new GuildEconomyScenario.GoodsRow(t.getId(), t.getItemId(), t.getItemCount(), t.getCostContribution(),
                    t.getRequiredGuildLevel(), t.getLimitPeriod(), t.getLimitCount(), maxBuy)).as("商品 %d", row.id()).isEqualTo(row);
            assertThat(t.getName()).isNotEmpty();
        }
        assertThat(tables.guildShop().contains(GuildEconomyScenario.UNKNOWN_GOODS_ID)).isFalse();
        // 第 12 / 13 步的前提
        assertThat(GuildEconomyScenario.GOODS_AMULET.maxBuyCount()).as("堆叠 1 的物品单次 1 份").isEqualTo(1);
        assertThat(GuildEconomyScenario.GOODS_LEVEL2.requiredLevel()).isEqualTo(2);
        assertThat(GuildEconomyScenario.GOODS_LEVEL3.requiredLevel()).isEqualTo(3);
        assertThat(GuildEconomyScenario.GOODS_LANTERN.limitPeriod()).as("花灯每游戏日限购").isEqualTo(GameDay.PERIOD_DAILY);
        assertThat(GuildEconomyScenario.GOODS_PILL.limitPeriod()).isEqualTo(GameDay.PERIOD_DAILY);
        long balance = 2 * GuildEconomyScenario.DONATE_BIG.contributionGain() + GuildEconomyScenario.DONATE_SPIRIT.contributionGain();
        assertThat(balance).isEqualTo(440);
        long after = balance - GuildEconomyScenario.GOODS_PILL.cost()
                - GuildEconomyScenario.GOODS_LANTERN.limitCount() * GuildEconomyScenario.GOODS_LANTERN.cost();
        assertThat(after).as("101 一份 + 301 五份之后").isEqualTo(160);
        // 第 12 步 B 帮贡 0 兑 101 → 14031；A 兑 104 → 14029（Lv.2 < 3）
        assertThat(GuildEconomyScenario.GOODS_PILL.cost()).isPositive();
    }

    @Test
    void 用到的消息号都能从契约解析_限频表允许节拍() {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        GuildEconomyScenario scenario = new GuildEconomyScenario(null, registry, "robot_java_", "x1", Duration.ofSeconds(1));
        assertThat(scenario.accountA()).isEqualTo("robot_java_gex1_a");
        ConfigTables tables = tables();
        List<Integer> checked = new ArrayList<>();
        for (Map.Entry<String, String> m : List.of(
                Map.entry("GuildService", "GetGuildDonateOptions"), Map.entry("GuildService", "DonateToGuild"),
                Map.entry("GuildService", "UpgradeGuild"), Map.entry("GuildService", "GetGuildShop"),
                Map.entry("GuildService", "BuyGuildShopGoods"), Map.entry("SceneBagClientPlayer", "GetBag"),
                Map.entry("SceneCurrencyClientPlayer", "GmAddCurrency"), Map.entry("SceneCurrencyClientPlayer", "GmDeductCurrency"))) {
            int id = registry.requireId(m.getKey(), m.getValue());
            tables.messageLimiter().find(id).ifPresent(row -> {
                // Bot 的节拍：同号 1.1 s 内至多 3 次 —— 限频表里有行的号必须至少允许每秒 3 条
                assertThat(row.getTimeWindow()).as("%s.%s 的时间窗（秒）", m.getKey(), m.getValue()).isEqualTo(1);
                assertThat(row.getMaxRequests()).as("%s.%s", m.getKey(), m.getValue())
                        .isGreaterThanOrEqualTo(GuildScenario.SAME_ID_MAX_IN_WINDOW);
                checked.add(id);
            });
        }
        assertThat(checked).as("经济五个号都在限频表里（spec §0.2）").hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void 切点判定_请求前后任一时刻的下一个切点都算对() {
        long justBefore = OffsetDateTime.of(2026, 10, 5, 4, 59, 59, 999_000_000, ZoneOffset.ofHours(8)).toInstant().toEpochMilli();
        long atCut = justBefore + 1;
        long todayCut = GameDay.nextDailyResetMillis(justBefore);
        long tomorrowCut = GameDay.nextDailyResetMillis(atCut);
        assertThat(todayCut).isEqualTo(atCut);
        assertThat(tomorrowCut - todayCut).isEqualTo(Duration.ofDays(1).toMillis());
        assertThat(GuildEconomyScenario.resetMatches(todayCut, justBefore, atCut, GameDay::nextDailyResetMillis)).isTrue();
        assertThat(GuildEconomyScenario.resetMatches(tomorrowCut, justBefore, atCut, GameDay::nextDailyResetMillis)).isTrue();
        assertThat(GuildEconomyScenario.resetMatches(todayCut, atCut, atCut + 10, GameDay::nextDailyResetMillis))
                .as("恰在切点时回当天切点 = 不严格晚于 now").isFalse();
        assertThat(GuildEconomyScenario.resetMatches(0, justBefore, justBefore, GameDay::nextDailyResetMillis)).isFalse();
        long monday = GameDay.nextWeeklyResetMillis(justBefore);
        assertThat(GuildEconomyScenario.resetMatches(monday, justBefore, justBefore + 5, GameDay::nextWeeklyResetMillis)).isTrue();
        assertThat(GuildEconomyScenario.resetMatches(todayCut, justBefore, justBefore + 5, GameDay::nextWeeklyResetMillis))
                .as("2026-10-05 是周一：周切点同日切点；换一天就不同").isTrue();
        long tuesday = justBefore + Duration.ofDays(1).toMillis();
        assertThat(GuildEconomyScenario.resetMatches(GameDay.nextDailyResetMillis(tuesday), tuesday, tuesday,
                GameDay::nextWeeklyResetMillis)).isFalse();
    }

    @Test
    void 背包货币缺槽为0_物品数按无符号累加() {
        BagInfo bag = BagInfo.newBuilder().setCurrency(CurrencyComp.newBuilder().addValues(7).addValues(-1L))
                .addItems(BagItemInfo.newBuilder().setConfigId(15).setCount(5))
                .addItems(BagItemInfo.newBuilder().setConfigId(15).setCount(-1))
                .addItems(BagItemInfo.newBuilder().setConfigId(16).setCount(3)).build();
        assertThat(GuildEconomyScenario.currency(bag, GuildEconomyScenario.CURRENCY_SILVER)).isEqualTo(7);
        assertThat(Long.toUnsignedString(GuildEconomyScenario.currency(bag, GuildEconomyScenario.CURRENCY_SPIRIT)))
                .isEqualTo("18446744073709551615");
        assertThat(GuildEconomyScenario.currency(bag, 5)).isZero();
        assertThat(GuildEconomyScenario.itemCount(bag, 15)).isEqualTo(5 + 4_294_967_295L);
        assertThat(GuildEconomyScenario.itemCount(bag, 99)).isZero();
    }

    @Test
    void 捐献视图判定_op_id_与配表行() {
        GuildDonationView ok = donation(GuildEconomyScenario.DONATE_BIG).build();
        assertThat(GuildEconomyScenario.donationViewProblem(ok, GuildEconomyScenario.DONATE_BIG)).isEmpty();
        assertThat(GuildEconomyScenario.donationViewProblem(ok.toBuilder().setOpId(0).build(), GuildEconomyScenario.DONATE_BIG))
                .contains("op_id");
        assertThat(GuildEconomyScenario.donationViewProblem(ok.toBuilder().setCostAmount(1).build(), GuildEconomyScenario.DONATE_BIG))
                .contains("与配表行不符");
        assertThat(GuildEconomyScenario.donationViewProblem(ok, GuildEconomyScenario.DONATE_SPIRIT)).contains("donate_id=3");
        assertThat(GuildEconomyScenario.describe(ok)).contains("op=9223372036854775809", "GUILD_ASSET_ORDER_STATUS_APPLIED")
                .doesNotContain("-");
    }

    @Test
    void 捐献页判定_升序_字段_解锁位() {
        GetGuildDonateOptionsResponse ok = GetGuildDonateOptionsResponse.newBuilder()
                .addOptions(GuildDonateOptionView.newBuilder().setDonateId(1).setName("小捐"))
                .addOptions(option(GuildEconomyScenario.DONATE_BIG, true))
                .addOptions(option(GuildEconomyScenario.DONATE_SPIRIT, true)).build();
        assertThat(GuildEconomyScenario.donateOptionsProblem(ok, 1)).isEmpty();
        GetGuildDonateOptionsResponse unsorted = ok.toBuilder().clearOptions()
                .addOptions(option(GuildEconomyScenario.DONATE_SPIRIT, true))
                .addOptions(option(GuildEconomyScenario.DONATE_BIG, true)).build();
        assertThat(GuildEconomyScenario.donateOptionsProblem(unsorted, 1)).contains("升序");
        assertThat(GuildEconomyScenario.donateOptionsProblem(ok, 0)).as("等级 0 < 门槛 1，unlocked 应为 false").contains("unlocked=false");
        GetGuildDonateOptionsResponse missing = ok.toBuilder().removeOptions(2).build();
        assertThat(GuildEconomyScenario.donateOptionsProblem(missing, 1)).contains("缺少选项 3");
        assertThat(GuildEconomyScenario.option(missing, 3).getDonateId()).isZero();
    }

    @Test
    void 商店判定_按分类再按号升序_解锁位随帮会等级() {
        GetGuildShopResponse level1 = shop(1);
        assertThat(GuildEconomyScenario.shopGoodsProblem(level1, 1)).isEmpty();
        assertThat(GuildEconomyScenario.shopGoodsProblem(shop(2), 2)).isEmpty();
        assertThat(GuildEconomyScenario.shopGoodsProblem(level1, 2)).as("Lv.2 时 103 应已解锁").contains("商品 103");
        List<GuildShopGoodsView> reversed = new ArrayList<>(level1.getGoodsList());
        Collections.reverse(reversed);
        assertThat(GuildEconomyScenario.shopGoodsProblem(level1.toBuilder().clearGoods().addAllGoods(reversed).build(), 1))
                .contains("升序");
        assertThat(GuildEconomyScenario.goods(level1, 202).getMaxBuyCount()).isEqualTo(1);
        assertThat(GuildEconomyScenario.goods(level1, 999).getGoodsId()).isZero();
        assertThat(GuildEconomyScenario.describe(level1)).contains("可用帮贡 0", "共 5 件");
    }

    @Test
    void 摘要里的号与金额按无符号十进制() {
        GuildInfo guild = GuildInfo.newBuilder().setGuildId(BIG).setLevel(2).setFunds(-1L).setMaxMembers(35)
                .setUpgradeCostFunds(50_000).build();
        assertThat(GuildEconomyScenario.economyOf(guild)).isEqualTo(
                " guild_id=9223372036854775809 level=2 funds=18446744073709551615 max_members=35 upgrade_cost_funds=50000");
        GuildShopOrderView order = GuildShopOrderView.newBuilder().setOpId(-2L).setGoodsId(301).setCount(1)
                .setStatus(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING).setCostContribution(50).setReasonTipId(27001).build();
        assertThat(GuildEconomyScenario.describe(order)).contains("op=18446744073709551614", "goods=301", "PENDING", "reason=27001");
        assertThat(GuildEconomyScenario.describeDonations(List.of())).isEqualTo("空");
    }

    @Test
    void guild_economy子命令_连字符与下划线都认_账号带ge标签() throws Exception {
        Map<String, String> env = Map.of(RobotOptions.PASSWORD_ENV, "p");
        RobotOptions options = RobotOptions.parse(List.of("guild-economy", "--run-tag", "x1"), env, 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.GUILD_ECONOMY);
        assertThat(RobotOptions.parse(List.of("guild_economy"), env, 0).scenario()).isEqualTo(RobotOptions.Scenario.GUILD_ECONOMY);
        assertThat(RobotOptions.parse(List.of("guild"), env, 0).scenario()).isEqualTo(RobotOptions.Scenario.GUILD);
        String a = GuildEconomyScenario.accountName(options.accountPrefix(), options.runTag(), "a");
        assertThat(a).isEqualTo("robot_java_gex1_a");
        assertThat(GuildEconomyScenario.accountName(options.accountPrefix(), options.runTag(), "b")).hasSize(a.length())
                .isNotEqualTo(GuildScenario.accountName(options.accountPrefix(), options.runTag(), "b"));
        assertThat(RobotOptions.usage()).contains("|guild-economy|", "  guild-economy ");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("guild-economy", "--prefix", "p".repeat(60)), env, 0))
                .isInstanceOf(UsageException.class).hasMessageContaining("超过");
    }

    // ================================================================ 夹具

    private static ConfigTables tables() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        return ConfigTables.load(dir);
    }

    /** 基线 MaxBuyCount（economy_config.go:243-255）：堆叠 0 → 0；堆叠 1 → 1；每份 0 个 → 0；否则 min(堆叠 / 每份, 20)。 */
    private static int maxBuyCount(int maxStack, int itemCount) {
        if (maxStack == 0) {
            return 0;
        }
        if (maxStack == 1) {
            return 1;
        }
        if (itemCount == 0) {
            return 0;
        }
        return Math.min(Integer.divideUnsigned(maxStack, itemCount), MAX_SHOP_BUY_COUNT);
    }

    private static GuildDonationView.Builder donation(GuildEconomyScenario.DonateRow row) {
        return GuildDonationView.newBuilder().setOpId(BIG).setDonateId(row.id())
                .setStatus(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED).setCurrencyType(row.currencyType())
                .setCostAmount(row.cost()).setContributionGain(row.contributionGain()).setFundsGain(row.fundsGain())
                .setCreatedMs(1_700_000_000_000L);
    }

    private static GuildDonateOptionView option(GuildEconomyScenario.DonateRow row, boolean unlocked) {
        return GuildDonateOptionView.newBuilder().setDonateId(row.id()).setName("捐" + row.id()).setCurrencyType(row.currencyType())
                .setCostAmount(row.cost()).setContributionGain(row.contributionGain()).setFundsGain(row.fundsGain())
                .setDailyLimit(row.dailyLimit()).setMinGuildLevel(row.minGuildLevel()).setUnlocked(unlocked).build();
    }

    /** CHECKED_GOODS 五件按 (category, id) 升序：101 / 103 / 104 修行补给，202 帮会珍藏，301 节庆好礼。 */
    private static GetGuildShopResponse shop(int guildLevel) {
        GetGuildShopResponse.Builder out = GetGuildShopResponse.newBuilder();
        for (GuildEconomyScenario.GoodsRow row : GuildEconomyScenario.CHECKED_GOODS) {
            out.addGoods(GuildShopGoodsView.newBuilder().setGoodsId(row.id()).setName("货" + row.id()).setCategory(row.id() / 100)
                    .setItemId(row.itemId()).setItemCount(row.itemCount()).setCostContribution(row.cost())
                    .setRequiredGuildLevel(row.requiredLevel()).setUnlocked(guildLevel >= row.requiredLevel())
                    .setLimitPeriod(row.limitPeriod()).setLimitCount(row.limitCount()).setMaxBuyCount(row.maxBuyCount()));
        }
        return out.build();
    }
}
