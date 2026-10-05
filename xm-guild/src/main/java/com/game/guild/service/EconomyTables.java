package com.game.guild.service;

import com.game.common.time.GameDay;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildTableRules;
import com.game.guild.store.EconomyStore;
import com.game.table.ConfigTables;
import com.game.table.GuildDonateTable;
import com.game.table.GuildLevelTable;
import com.game.table.GuildRuleTable;
import com.game.table.GuildShopTable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Supplier;

/**
 * 帮会经济的配表校验与运行期现查（基线 economy_config.go；guild-economy-spec §0.5、§7.7 第 1 步、§11.1 EconomyTablesTest）。
 *
 * <p>两条纪律沿用 {@link GuildTableRules}：
 * <ol>
 *   <li><b>配表只存 id、用时现查</b>（{@link Lookup}）：{@link ConfigTables} 是整体替换的不可变快照；启动校验已保证行存在且自洽，
 *       运行期查不到 = 配表被错误替换，一律 fail-closed（调用方按故障处理，不给默认值）；</li>
 *   <li><b>启动期拒启而不是运行期兜底</b>（{@link #validate}）：捐献扣的是玩家的货币，表错了在运行期表现为「扣了钱没入账」「一次兑换溢出背包」
 *       这类不可逆的静默错误；启动期拒绝的代价只是一次部署回滚。</li>
 * </ol>
 * 表里的 uint32 / uint64 在 Java 按位模式持有，这里全部按无符号比较与打印；错误文案照抄基线（带表名、行 id、字段名与实际值）。
 * 全是纯函数（{@link Lookup#of} 的实现每次现取快照），任意线程可调。
 */
public final class EconomyTables {

    /** 可捐献的货币：0 银两（kCurrencyGold）。权威定义在 C++ currency.h，没有 proto 镜像（economy_config.go:25-32）。 */
    public static final int DONATE_CURRENCY_GOLD = 0;
    /** 可捐献的货币：1 灵石（kCurrencyDiamond）。绑定灵石（2）按 R1 不可捐。 */
    public static final int DONATE_CURRENCY_DIAMOND = 1;
    /** 商店分类：1 修行补给 / 2 帮会珍藏 / 3 节庆好礼；客户端每个分类一个页签，所以每个分类至少要有一行。 */
    public static final List<Integer> SHOP_CATEGORIES = List.of(1, 2, 3);

    // 配表取值的合法区间（economy_config.go:38-57）。判据与文案用同一组数。
    public static final int MIN_ASSET_OP_DEADLINE_SECONDS = 60;
    public static final int MAX_ASSET_OP_DEADLINE_SECONDS = 86_400;
    public static final int MIN_ASSET_OP_RETRY_BASE_MS = 100;
    public static final int MAX_ASSET_OP_RETRY_BASE_MS = 60_000;
    /** upgrade_cost_funds 的上限：防误填超大值（多写几个 0 会让帮会永远升不上去，且没有任何报错）。 */
    public static final long MAX_UPGRADE_COST_FUNDS = 1_000_000_000_000L;
    /** cost_contribution 的上限：保证 cost × {@value GuildLimits#MAX_SHOP_BUY_COUNT} 远离 uint64 溢出。 */
    public static final long MAX_SHOP_COST_CONTRIBUTION = 1_000_000_000L;
    /** 每种货币至多几行捐献选项：客户端一个货币面板只放两个按钮。 */
    public static final int MAX_DONATE_OPTIONS_PER_CURRENCY = 2;

    /** Item.max_stack_size 的查询：空 = 物品不存在。 */
    @FunctionalInterface
    public interface ItemStacks {
        OptionalInt maxStack(int itemId);
    }

    private EconomyTables() {
    }

    // ================================================================ 启动校验

    /** 校验一份配表快照（ValidateEconomyTables，economy_config.go:73-84）；失败拒启。 */
    public static void validate(ConfigTables tables) {
        validate(tables.guildRule().find(GuildTableRules.RULE_ROW_ID).orElse(null), tables.guildLevel().all(),
                tables.guildDonate().all(), tables.guildShop().all(), itemStacks(tables));
    }

    /**
     * 纯校验（validateEconomyTables，economy_config.go:86-114）。开头<b>再调一次</b> {@link GuildTableRules#validate}：GuildRule / GuildLevel
     * 的结构规则只写一份，这里要用「GuildLevel 从 1 连续」推出最高级（= 行数），不能假设调用方先调过那边。
     *
     * @param stacks Item 表查询；null 只可能是接线错误（判不了「一次兑换会不会溢出背包」，不能放行）
     * @throws IllegalStateException 任一条规则不过（消息带表名 / 行 id / 字段名 / 实际值）
     */
    public static void validate(GuildRuleTable rule, List<GuildLevelTable> levels, List<GuildDonateTable> donates,
                                List<GuildShopTable> shops, ItemStacks stacks) {
        GuildTableRules.validate(rule, levels);
        // 走到这里 rule 非 null、levels 非空、无空行且 id 从 1 连续，所以最高级就是行数
        long maxLevel = levels.size();

        long deadline = Integer.toUnsignedLong(rule.getAssetOpDeadlineSeconds());
        if (deadline < MIN_ASSET_OP_DEADLINE_SECONDS || deadline > MAX_ASSET_OP_DEADLINE_SECONDS) {
            throw fail("GuildRule[%d].asset_op_deadline_seconds=%d 越界,应在 [%d,%d]", GuildTableRules.RULE_ROW_ID, deadline,
                    MIN_ASSET_OP_DEADLINE_SECONDS, MAX_ASSET_OP_DEADLINE_SECONDS);
        }
        long retryBase = Integer.toUnsignedLong(rule.getAssetOpRetryBaseMs());
        if (retryBase < MIN_ASSET_OP_RETRY_BASE_MS || retryBase > MAX_ASSET_OP_RETRY_BASE_MS) {
            throw fail("GuildRule[%d].asset_op_retry_base_ms=%d 越界,应在 [%d,%d]", GuildTableRules.RULE_ROW_ID, retryBase,
                    MIN_ASSET_OP_RETRY_BASE_MS, MAX_ASSET_OP_RETRY_BASE_MS);
        }
        for (GuildLevelTable row : levels) {
            long cost = row.getUpgradeCostFunds();
            if (Long.compareUnsigned(cost, MAX_UPGRADE_COST_FUNDS) > 0) {
                throw fail("GuildLevel[%d].upgrade_cost_funds=%s 超过上限 %d(防误填超大值)", Integer.toUnsignedLong(row.getId()),
                        Long.toUnsignedString(cost), MAX_UPGRADE_COST_FUNDS);
            }
        }
        validateDonateRows(donates, maxLevel);
        validateShopRows(shops, maxLevel, stacks);
    }

    /** GuildDonate（validateDonateRows，economy_config.go:116-161）。 */
    static void validateDonateRows(List<GuildDonateTable> rows, long maxLevel) {
        Set<Integer> seen = new HashSet<>();
        Map<Integer, Integer> perCurrency = new HashMap<>();
        for (int i = 0; i < rows.size(); i++) {
            GuildDonateTable row = rows.get(i);
            if (row == null) {
                throw fail("GuildDonate 表第 %d 行为空", i + 1);
            }
            long id = Integer.toUnsignedLong(row.getId());
            // 0 是请求里 donate_id 的 proto 缺省值：客户端漏填字段时不能恰好命中一个真实选项
            if (id == 0) {
                throw fail("GuildDonate 第 %d 行 id=0:0 是请求缺省值,不能对应真实选项", i + 1);
            }
            // 重复 id 会让选项列表里出现两个按钮、点下去却扣同一行的数值
            if (!seen.add(row.getId())) {
                throw fail("GuildDonate.id=%d 重复", id);
            }
            int currency = row.getCurrencyType();
            if (currency != DONATE_CURRENCY_GOLD && currency != DONATE_CURRENCY_DIAMOND) {
                throw fail("GuildDonate[%d].currency_type=%d 非法,只允许 %d(银两)/ %d(灵石)", id, Integer.toUnsignedLong(currency),
                        DONATE_CURRENCY_GOLD, DONATE_CURRENCY_DIAMOND);
            }
            // scene 的 Debit 按有符号 64 位校验数额，超过 MaxInt64 会被判成非法包（永久拒绝）：位模式为负即越界
            long cost = row.getCostAmount();
            if (cost <= 0) {
                throw fail("GuildDonate[%d].cost_amount=%s 越界,应在 [1,%d]", id, Long.toUnsignedString(cost), Long.MAX_VALUE);
            }
            // 两项收益都为 0 = 玩家白扣钱，只能是填错
            if (row.getContributionGain() == 0 && row.getFundsGain() == 0) {
                throw fail("GuildDonate[%d] 的 contribution_gain 与 funds_gain 不能同时为 0", id);
            }
            if (row.getDailyLimit() == 0) {
                throw fail("GuildDonate[%d].daily_limit=0:每日次数至少为 1", id);
            }
            long minLevel = Integer.toUnsignedLong(row.getMinGuildLevel());
            if (minLevel < 1 || minLevel > maxLevel) {
                throw fail("GuildDonate[%d].min_guild_level=%d 越界,应在 [1,%d](GuildLevel 最高级)", id, minLevel, maxLevel);
            }
            int count = perCurrency.merge(currency, 1, Integer::sum);
            if (count > MAX_DONATE_OPTIONS_PER_CURRENCY) {
                throw fail("GuildDonate:currency_type=%d 的选项超过 %d 行(客户端一个货币面板只放两个按钮)", currency,
                        MAX_DONATE_OPTIONS_PER_CURRENCY);
            }
        }
    }

    /** GuildShop（validateShopRows，economy_config.go:163-226）。 */
    static void validateShopRows(List<GuildShopTable> rows, long maxLevel, ItemStacks stacks) {
        if (stacks == null) {
            throw fail("GuildShop 校验缺少 Item 表查询");
        }
        Set<Integer> seen = new HashSet<>();
        Set<Integer> categories = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            GuildShopTable row = rows.get(i);
            if (row == null) {
                throw fail("GuildShop 表第 %d 行为空", i + 1);
            }
            long id = Integer.toUnsignedLong(row.getId());
            if (id == 0) {
                throw fail("GuildShop 第 %d 行 id=0:0 是请求缺省值,不能对应真实商品", i + 1);
            }
            if (!seen.add(row.getId())) {
                throw fail("GuildShop.id=%d 重复", id);
            }
            int category = row.getCategory();
            if (!SHOP_CATEGORIES.contains(category)) {
                throw fail("GuildShop[%d].category=%d 非法,只允许 %s", id, Integer.toUnsignedLong(category), SHOP_CATEGORIES);
            }
            long itemId = Integer.toUnsignedLong(row.getItemId());
            OptionalInt stack = stacks.maxStack(row.getItemId());
            if (stack.isEmpty() || stack.getAsInt() == 0) {
                throw fail("GuildShop[%d].item_id=%d 在 Item 表里不存在或 max_stack_size=0", id, itemId);
            }
            int maxStack = stack.getAsInt();
            // item_count ≤ max_stack_size 同时覆盖了「堆叠 1 的物品每份只能 1 个」（每份占 1 格）
            long itemCount = Integer.toUnsignedLong(row.getItemCount());
            if (itemCount < 1 || itemCount > Integer.toUnsignedLong(maxStack)) {
                throw fail("GuildShop[%d].item_count=%d 越界,应在 [1,%d](物品 %d 的 max_stack_size)", id, itemCount,
                        Integer.toUnsignedLong(maxStack), itemId);
            }
            // 由上一条可推出 MaxBuyCount ≥ 1；仍显式判一次，防公式日后改动后悄悄变成 0（那会让这件商品永远买不了）
            if (maxBuyCount(row, maxStack) < 1) {
                throw fail("GuildShop[%d] 单次可兑换份数为 0(item_count=%d,max_stack_size=%d)", id, itemCount,
                        Integer.toUnsignedLong(maxStack));
            }
            long cost = row.getCostContribution();
            if (cost == 0 || Long.compareUnsigned(cost, MAX_SHOP_COST_CONTRIBUTION) > 0) {
                throw fail("GuildShop[%d].cost_contribution=%s 越界,应在 [1,%d]", id, Long.toUnsignedString(cost),
                        MAX_SHOP_COST_CONTRIBUTION);
            }
            long required = Integer.toUnsignedLong(row.getRequiredGuildLevel());
            if (required < 1 || required > maxLevel) {
                throw fail("GuildShop[%d].required_guild_level=%d 越界,应在 [1,%d](GuildLevel 最高级)", id, required, maxLevel);
            }
            int period = row.getLimitPeriod();
            if (period != GameDay.PERIOD_NONE && period != GameDay.PERIOD_DAILY && period != GameDay.PERIOD_WEEKLY) {
                throw fail("GuildShop[%d].limit_period=%d 非法,只允许 %d(不限)/ %d(每日)/ %d(每周)", id,
                        Integer.toUnsignedLong(period), GameDay.PERIOD_NONE, GameDay.PERIOD_DAILY, GameDay.PERIOD_WEEKLY);
            }
            // 不限购就不占计数行（period_key = 0）；限购却填 0 份等于永远买不了。两者必须同时成立或同时不成立
            if ((period == GameDay.PERIOD_NONE) != (row.getLimitCount() == 0)) {
                throw fail("GuildShop[%d]:limit_period=%d 与 limit_count=%d 不一致(不限购时份数必须为 0,限购时至少为 1)", id,
                        Integer.toUnsignedLong(period), Integer.toUnsignedLong(row.getLimitCount()));
            }
            categories.add(category);
        }
        for (int c : SHOP_CATEGORIES) {
            if (!categories.contains(c)) {
                throw fail("GuildShop 缺少 category=%d 的商品(客户端每个分类一个页签)", c);
            }
        }
    }

    /**
     * 一件商品单次最多兑换的份数（MaxBuyCount，economy_config.go:228-255）：max_stack_size == 1 → 1（每份占 1 格）；否则
     * {@code min(20, max_stack_size / item_count)}——一次兑换的物品总数不超过一组堆叠，背包只需一格空位就能收下。<b>不看限购</b>
     * （限购份数更小时以限购为准，事务内判）。行为 null、堆叠为 0 或 item_count 为 0（坏配表）回 0 = 不可兑换，fail-closed。
     *
     * @param maxStack Item.max_stack_size（uint32 位模式）
     */
    public static int maxBuyCount(GuildShopTable row, int maxStack) {
        if (row == null || maxStack == 0) {
            return 0;
        }
        if (maxStack == 1) {
            return 1;
        }
        int itemCount = row.getItemCount();
        if (itemCount == 0) {
            return 0;
        }
        int perStack = Integer.divideUnsigned(maxStack, itemCount);
        return Integer.compareUnsigned(perStack, GuildLimits.MAX_SHOP_BUY_COUNT) < 0 ? perStack : GuildLimits.MAX_SHOP_BUY_COUNT;
    }

    /**
     * 运行配置（{@code xm.guild.asset-op.*}）与配表（GuildRule 的两列资产参数）之间的<b>交叉</b>约束（ValidateAssetOpTiming，
     * economy_config.go:270-302）：两边各自合法、组合起来会静默出错的那一类。只在资产通道开启时调用；失败拒启。
     * <ol>
     *   <li>{@code lease + reconcile-interval < asset_op_deadline_seconds}：插行时 next_attempt_ms = lease_until_ms = start + lease，
     *       同步投递被跳过的捐献最早在 start + lease 之后的下一轮才被循环领到；那时若已过截止，循环直接改发中止——一次扣款都没尝试就被撤销。
     *       余量只取一个对账间隔，<b>不</b>再加 max-backoff（会把截止 60 s、封顶 60 s 这类合法组合拒掉）；</li>
     *   <li>{@code asset_op_retry_base_ms ≤ max-backoff}：把配表列名与配置项名一起报出来，运维不必翻两处。</li>
     * </ol>
     *
     * @throws IllegalStateException 不满足（或 GuildRule 缺行）
     */
    public static void validateAssetOpTiming(ConfigTables tables, Duration lease, Duration reconcileInterval,
                                             Duration maxBackoff) {
        GuildRuleTable rule = tables.guildRule().find(GuildTableRules.RULE_ROW_ID).orElse(null);
        if (rule == null) {
            throw fail("GuildRule 表缺少 id=%d 的规则行", GuildTableRules.RULE_ROW_ID);
        }
        validateAssetOpTiming(rule, lease, reconcileInterval, maxBackoff);
    }

    /** {@link #validateAssetOpTiming(ConfigTables, Duration, Duration, Duration)} 的纯函数部分（validateAssetOpTiming）。 */
    public static void validateAssetOpTiming(GuildRuleTable rule, Duration lease, Duration reconcileInterval,
                                             Duration maxBackoff) {
        Duration deadline = Duration.ofSeconds(Integer.toUnsignedLong(rule.getAssetOpDeadlineSeconds()));
        if (lease.plus(reconcileInterval).compareTo(deadline) >= 0) {
            throw fail("xm.guild.asset-op.lease(%s)+ xm.guild.asset-op.reconcile-interval(%s)必须小于 GuildRule[%d]"
                            + ".asset_op_deadline_seconds(%s):否则同步投递被跳过的捐献在重投循环第一次领到它之前就已过截止,未尝试扣款即被中止",
                    lease, reconcileInterval, GuildTableRules.RULE_ROW_ID, deadline);
        }
        Duration base = Duration.ofMillis(Integer.toUnsignedLong(rule.getAssetOpRetryBaseMs()));
        if (base.compareTo(maxBackoff) > 0) {
            throw fail("GuildRule[%d].asset_op_retry_base_ms(%s)不得大于 xm.guild.asset-op.max-backoff(%s)",
                    GuildTableRules.RULE_ROW_ID, base, maxBackoff);
        }
    }

    // ================================================================ 运行期现查

    /**
     * 服务用到的经济配表现查（economy_config.go:259-362）。生产实现 {@link #of} 每次都从当前快照读；单测换假实现（{@link ConfigTables}
     * 只能从磁盘加载，造不出「缺行」的快照）。查不到时的处置在调用方：GuildRule 缺行 → 故障；Item 缺行 → 兑换故障、商店页
     * MaxBuyCount = 0 记 ERROR 不失败；donate_id / goods_id 查不到 → 14027 / 14028。
     */
    public interface Lookup {

        /** GuildDonate[id]；null = 查不到。 */
        GuildDonateTable donateOption(int donateId);

        /** GuildShop[id]；null = 查不到。 */
        GuildShopTable shopGoods(int goodsId);

        /** Item[id].max_stack_size；空 = 物品不存在。 */
        OptionalInt itemMaxStack(int itemId);

        /** 捐献指令从创建到改发中止的时长 = GuildRule[1] 第 5 列 × 1000（assetOpDeadlineMs，:305-311）；空 = 缺行（故障）。 */
        OptionalLong assetOpDeadlineMs();

        /** 重投退避基数 = GuildRule[1] 第 6 列（AssetOpRetryBase，:259-268）；缺行回 0，让循环构造失败而不是带着猜出来的默认值跑。 */
        long assetOpRetryBaseMs();

        /**
         * 升级用的 GuildLevel 行（upgradeLevelLookup，:320-330）：扣的是<b>当前等级行</b>的花费，新上限取<b>下一级行</b>的 max_members，
         * 两次查询都在仓储事务里做。null = 缺行。可直接当 {@link EconomyStore.UpgradeLevels} 用（{@code lookup::upgradeLevel}）。
         */
        EconomyStore.UpgradeLevel upgradeLevel(int level);

        /** 捐献选项按 donate_id 升序（复制一份、跳过 null 再排，不动快照内部列表；sortedDonateRows，:334-347）。 */
        List<GuildDonateTable> sortedDonateRows();

        /** 商品按 (category, goods_id) 升序（sortedShopRows，:349-362）。 */
        List<GuildShopTable> sortedShopRows();

        /** 以配表快照为数据源（{@code tables} 每次调用现取，便于将来整体替换快照做热更）。 */
        static Lookup of(Supplier<ConfigTables> tables) {
            return new Lookup() {
                @Override
                public GuildDonateTable donateOption(int donateId) {
                    return tables.get().guildDonate().find(donateId).orElse(null);
                }

                @Override
                public GuildShopTable shopGoods(int goodsId) {
                    return tables.get().guildShop().find(goodsId).orElse(null);
                }

                @Override
                public OptionalInt itemMaxStack(int itemId) {
                    return itemStacks(tables.get()).maxStack(itemId);
                }

                @Override
                public OptionalLong assetOpDeadlineMs() {
                    return EconomyTables.assetOpDeadlineMs(tables.get());
                }

                @Override
                public long assetOpRetryBaseMs() {
                    return EconomyTables.assetOpRetryBaseMs(tables.get());
                }

                @Override
                public EconomyStore.UpgradeLevel upgradeLevel(int level) {
                    return EconomyTables.upgradeLevel(tables.get(), level);
                }

                @Override
                public List<GuildDonateTable> sortedDonateRows() {
                    return EconomyTables.sortedDonateRows(tables.get().guildDonate().all());
                }

                @Override
                public List<GuildShopTable> sortedShopRows() {
                    return EconomyTables.sortedShopRows(tables.get().guildShop().all());
                }
            };
        }
    }

    /** 以一份快照为数据源的 Item 堆叠查询。 */
    public static ItemStacks itemStacks(ConfigTables tables) {
        return itemId -> tables.item().find(itemId)
                .map(row -> OptionalInt.of(row.getMaxStackSize()))
                .orElse(OptionalInt.empty());
    }

    /** GuildRule[1] 第 5 列 × 1000；空 = 缺行。 */
    public static OptionalLong assetOpDeadlineMs(ConfigTables tables) {
        return tables.guildRule().find(GuildTableRules.RULE_ROW_ID)
                .map(rule -> OptionalLong.of(Integer.toUnsignedLong(rule.getAssetOpDeadlineSeconds()) * 1000L))
                .orElse(OptionalLong.empty());
    }

    /** GuildRule[1] 第 6 列；缺行回 0。 */
    public static long assetOpRetryBaseMs(ConfigTables tables) {
        return tables.guildRule().find(GuildTableRules.RULE_ROW_ID)
                .map(rule -> Integer.toUnsignedLong(rule.getAssetOpRetryBaseMs()))
                .orElse(0L);
    }

    /** GuildLevel[level] 的升级两列；null = 缺行。 */
    public static EconomyStore.UpgradeLevel upgradeLevel(ConfigTables tables, int level) {
        return tables.guildLevel().find(level)
                .map(row -> new EconomyStore.UpgradeLevel(row.getUpgradeCostFunds(), row.getMaxMembers()))
                .orElse(null);
    }

    /** 复制、跳过 null、按 donate_id 无符号升序。 */
    public static List<GuildDonateTable> sortedDonateRows(List<GuildDonateTable> all) {
        List<GuildDonateTable> rows = new ArrayList<>(all.size());
        for (GuildDonateTable row : all) {
            if (row != null) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingLong(row -> Integer.toUnsignedLong(row.getId())));
        return rows;
    }

    /** 复制、跳过 null、按 (category, goods_id) 无符号升序。 */
    public static List<GuildShopTable> sortedShopRows(List<GuildShopTable> all) {
        List<GuildShopTable> rows = new ArrayList<>(all.size());
        for (GuildShopTable row : all) {
            if (row != null) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.<GuildShopTable>comparingLong(row -> Integer.toUnsignedLong(row.getCategory()))
                .thenComparingLong(row -> Integer.toUnsignedLong(row.getId())));
        return rows;
    }

    private static IllegalStateException fail(String format, Object... args) {
        return new IllegalStateException(String.format(format, args));
    }
}
