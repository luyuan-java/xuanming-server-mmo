package com.game.robot.scenario;

import com.game.common.time.GameDay;
import com.game.contract.MessageIdRegistry;
import com.game.proto.BagInfo;
import com.game.proto.BagItemInfo;
import com.game.proto.GetBagRequest;
import com.game.proto.GetBagResponse;
import com.game.proto.GmAddCurrencyRequest;
import com.game.proto.GmAddCurrencyResponse;
import com.game.proto.GmDeductCurrencyRequest;
import com.game.proto.GmDeductCurrencyResponse;
import com.game.proto.guild.ApplyJoinGuildRequest;
import com.game.proto.guild.ApplyJoinGuildResponse;
import com.game.proto.guild.BuyGuildShopGoodsRequest;
import com.game.proto.guild.BuyGuildShopGoodsResponse;
import com.game.proto.guild.CreateGuildRequest;
import com.game.proto.guild.CreateGuildResponse;
import com.game.proto.guild.DisbandGuildRequest;
import com.game.proto.guild.DisbandGuildResponse;
import com.game.proto.guild.DonateToGuildRequest;
import com.game.proto.guild.DonateToGuildResponse;
import com.game.proto.guild.GetGuildDonateOptionsRequest;
import com.game.proto.guild.GetGuildDonateOptionsResponse;
import com.game.proto.guild.GetGuildShopRequest;
import com.game.proto.guild.GetGuildShopResponse;
import com.game.proto.guild.GetPlayerGuildRequest;
import com.game.proto.guild.GetPlayerGuildResponse;
import com.game.proto.guild.GuildAssetOrderStatus;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.GuildChangedS2C;
import com.game.proto.guild.GuildDonateOptionView;
import com.game.proto.guild.GuildDonationView;
import com.game.proto.guild.GuildInfo;
import com.game.proto.guild.GuildMember;
import com.game.proto.guild.GuildShopGoodsView;
import com.game.proto.guild.GuildShopOrderView;
import com.game.proto.guild.ReviewGuildApplicationRequest;
import com.game.proto.guild.ReviewGuildApplicationResponse;
import com.game.proto.guild.UpgradeGuildRequest;
import com.game.proto.guild.UpgradeGuildResponse;
import com.game.robot.client.GameConnection;
import com.game.robot.client.Received;
import com.game.robot.client.RobotException;
import com.game.robot.flow.EnteredPlayer;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.table.AssetErrorTip;
import com.game.table.GuildErrorTip;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongUnaryOperator;

/**
 * 帮会经济端到端（对应 mmorpg robot/guild_economy_smoke.go 的 full 模式；guild-economy-spec §11.6）：帮主 A 与成员 B 两个 run-tag 新号，
 * 经 gate → xm-guild →（资产通道）→ xm-scene 走一遍 捐献 → 升级 → 兑换，断言帮会资金、个人帮贡、每日次数 / 限购、scene 上的银两 / 灵石与背包物品
 * （Go :1-49、:141-337）：
 * <ol>
 *   <li>第 1 步 A、B 都不在帮（新号）；A 建帮（Lv.1、资金 0、上限 30），B 申请、A 审批通过（Go :160-193）；</li>
 *   <li>第 2 步 A 读捐献页（3 个选项、没有结算中）与商店（11 件）；今日用量全 0 才是 full 模式——新号恒为 0（D18），所以不移植基线的
 *       degraded 模式，用量非 0 直接判失败并提示换 run-tag（Go :195-216）；</li>
 *   <li>第 3 步 B 的灵石清零（新号本就是 0）；第 4 步 记下 A 的背包初值，GM 给 A 加 300,000 银两、100 灵石（Go :218-222、:240-245）；</li>
 *   <li>第 5–6 步 A 大捐两次 → 资金 12,000 / 24,000、A 的累计 / 可用帮贡 120 / 240；第三次 14024、资金不变（Go :247-256）；</li>
 *   <li>第 7 步 A 灵石捐 → 资金 44,000、可用帮贡 440；A 的银两 = 初值 + 300,000 − 200,000、灵石 = 初值 + 100 − 100（Go :258-271）；</li>
 *   <li>第 8 步 B 灵石捐 → 14025、视图 REJECTED；B 该项今日用量仍 0、最近结果是 27000（Go :273-274、:350-374）；</li>
 *   <li>第 9 步 B 升级 14016 且带帮会；A 升级 → Lv.2、资金 24,000、上限 35；A 带旧等级再升 → 受理、仍 Lv.2 / 24,000（Go :276-296）；</li>
 *   <li>第 10 步 商店：103 解锁、104 未解锁、可用帮贡 440、202 单次最多 1 份；第 11 步 兑换 101 → 帮贡 410、物品 15 数量 +5（Go :298-318）；</li>
 *   <li>第 12 步 104 → 14029、B 兑 101 → 14031、一次 2 份 202 → 14030；第 13 步 连兑 5 份 301 → 帮贡 160，第 6 份 14030（Go :320-334、:388-399）；</li>
 *   <li>A 解散（Go :232）。</li>
 * </ol>
 * 结算中（PENDING）的单照基线轮询对应页面直到落定（至多 {@link #SETTLE_TIMEOUT}）。推送（spec §5）：A 升级 → B 收 LEVEL_UP（actor = A、target = 0），
 * 操作者不收、带旧等级重升（staleView）不推；捐献 / 兑换只在<b>后台</b>（重投循环）终结时推 FUNDS_CHANGED / DELIVERY_DONE 给本人
 * （actor = 0、target = 本人）——同步投递当场终结的不推（economy_logic.go:667-691 的 syncDeliveryKey），所以收尾按「回包是 PENDING 的笔数」核对每人收到的条数。
 *
 * <p>Java 增项：建帮 / 升级快照的 upgrade_cost_funds；选项与商品的排序和字段；next_daily / weekly_reset_ms 等于 {@link GameDay} 的下一个切点；
 * 没写行的拒绝不带视图；Lv.2 再升资金不足 → 14022 且带快照（基线只在 degraded 模式里测）；未知捐献项 0 → 14027、未知商品 0 → 14028；
 * 兑换份数 0 按 1 份；撞上限的那次不占次数；收尾用量与累计帮贡；
 * 解散后经济读写都是 14002；全程在同一游戏日。通道关闭时 53 / 233 回 14026（升级与两个读照常）由 xm-guild 服务单测覆盖——本机切片开着通道；
 * GuildRule 截止 600 s 的 Abort 路径同样不在这里等。
 *
 * <p>前置：xm-guild {@code xm.guild.asset-op.enabled=true}；xm-scene 与 xm-guild 注入同一个 {@code XM_ASSET_OP_SECRET_GUILD}
 * （tools/local/start-slice.sh 没设时生成本机随机值）；{@code XM_RUN_MODE=dev}（GM 加 / 扣币 37 / 49 放行）。消息号一律按「服务 + 方法」从契约解析。
 * 节拍复用 {@link GuildScenario.Bot}：相邻请求隔 300 ms、同号 1.1 s 内至多 3 次（gate 限频：经济读 10 / s、写 5 / s，37 / 49 / 191 表外缺省 3 / s）。
 */
public final class GuildEconomyScenario {

    private static final String SERVICE = "GuildService";
    private static final String BAG_SERVICE = "SceneBagClientPlayer";
    private static final String CURRENCY_SERVICE = "SceneCurrencyClientPlayer";
    static final String REF = "PARITY「帮会经济」行";

    /** 一个捐献选项的期望值（GuildDonate 行；currency 0 银两 / 1 灵石）。 */
    record DonateRow(int id, int currencyType, long cost, long contributionGain, long fundsGain, int dailyLimit, int minGuildLevel) {
    }

    /** 一件商品的期望值（GuildShop 行 + 按 Item 堆叠算出的单次上限 MaxBuyCount；limit_period 0 不限 / 1 每游戏日 / 2 每周）。 */
    record GoodsRow(int id, int itemId, int itemCount, long cost, int requiredLevel, int limitPeriod, int limitCount, int maxBuyCount) {
    }

    // ---- 期望值来自默认配表 GuildDonate / GuildShop / GuildLevel / Item。与基线一样**故意写死**（Go :59-95）：同时盯住「配表被人改了」
    // 与「服务端没按配表算」；GuildEconomyScenarioTest 核对它们与同步来的配表一致，配表真要改就改这里。----
    static final DonateRow DONATE_BIG = new DonateRow(2, 0, 100_000, 120, 12_000, 2, 1);
    static final DonateRow DONATE_SPIRIT = new DonateRow(3, 1, 100, 200, 20_000, 1, 1);
    static final int DONATE_OPTION_COUNT = 3;

    static final long LEVEL1_UPGRADE_COST = 20_000;
    static final long LEVEL2_UPGRADE_COST = 50_000;
    static final int LEVEL1_MAX_MEMBERS = 30;
    static final int LEVEL2_MAX_MEMBERS = 35;

    static final int SHOP_GOODS_COUNT = 11;
    /** 培元丹：物品 15 × 5，帮贡 30，每日 10 份；物品堆叠 999 → 单次 min(999 / 5, 20) = 20 份。 */
    static final GoodsRow GOODS_PILL = new GoodsRow(101, 15, 5, 30, 1, 1, 10, 20);
    /** 需帮会 Lv.2。 */
    static final GoodsRow GOODS_LEVEL2 = new GoodsRow(103, 17, 1, 80, 2, 1, 5, 20);
    /** 需帮会 Lv.3（每周 5 份）。 */
    static final GoodsRow GOODS_LEVEL3 = new GoodsRow(104, 18, 1, 150, 3, 2, 5, 20);
    /** 堆叠 1 的物品：单次最多 1 份。 */
    static final GoodsRow GOODS_AMULET = new GoodsRow(202, 12, 1, 800, 4, 2, 1, 1);
    /** 花灯：帮贡 50，每日 5 份。 */
    static final GoodsRow GOODS_LANTERN = new GoodsRow(301, 21, 1, 50, 1, 1, 5, 20);
    static final List<GoodsRow> CHECKED_GOODS = List.of(GOODS_PILL, GOODS_LEVEL2, GOODS_LEVEL3, GOODS_AMULET, GOODS_LANTERN);
    /** 配表里没有的商品（表校验要求 id ≠ 0）。 */
    static final int UNKNOWN_GOODS_ID = 0;

    static final long GM_SILVER = 300_000;
    static final long GM_SPIRIT = 100;
    /** 货币槽位：银两 = 金币 0、灵石 = 钻石 1（Go :97-103；与 CurrencyScenario 同序）。 */
    static final int CURRENCY_SILVER = 0;
    static final int CURRENCY_SPIRIT = 1;
    /** 主背包：物品与货币都在这一份快照里（Go econBag）。 */
    private static final int MAIN_BAG = 0;

    /**
     * 结算中的单最多等多久。基线 10 s（Go econSettleTimeout）；Java 放宽到 15 s：同步投递 2.5 s 没定下来时由重投循环按 2 s 节拍、
     * 1 s 起的退避接手，本机两三轮足够，多留的只是失败时多等几秒。
     */
    static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(15);
    static final Duration SETTLE_POLL = Duration.ofMillis(500);
    /** 开跑时离游戏日切点不足这么久就记一条观察：跨过切点每日次数会重置，限次断言随之失效。 */
    static final Duration CUTOVER_MARGIN = Duration.ofMinutes(3);

    private static final int TIP_NOT_IN_GUILD = GuildErrorTip.guild_error.kGuildNotInGuild_VALUE;
    private static final int TIP_RANK_TOO_LOW = GuildErrorTip.guild_error.kGuildRankTooLow_VALUE;
    private static final int TIP_FUNDS_INSUFFICIENT = GuildErrorTip.guild_error.kGuildFundsInsufficient_VALUE;
    private static final int TIP_DONATE_LIMIT = GuildErrorTip.guild_error.kGuildDonateLimit_VALUE;
    private static final int TIP_CURRENCY_INSUFFICIENT = GuildErrorTip.guild_error.kGuildCurrencyInsufficient_VALUE;
    private static final int TIP_ASSET_PENDING = GuildErrorTip.guild_error.kGuildAssetPending_VALUE;
    private static final int TIP_ASSET_REJECTED = GuildErrorTip.guild_error.kGuildAssetRejected_VALUE;
    private static final int TIP_GOODS_NOT_FOUND = GuildErrorTip.guild_error.kGuildShopGoodsNotFound_VALUE;
    private static final int TIP_LEVEL_TOO_LOW = GuildErrorTip.guild_error.kGuildShopLevelTooLow_VALUE;
    private static final int TIP_SHOP_LIMIT = GuildErrorTip.guild_error.kGuildShopLimit_VALUE;
    private static final int TIP_CONTRIBUTION_INSUFFICIENT = GuildErrorTip.guild_error.kGuildContributionInsufficient_VALUE;
    /** scene 原因码只进视图的 reason_tip_id，不直接做成 tip（spec §0.4）。 */
    static final int REASON_CURRENCY_INSUFFICIENT = AssetErrorTip.asset_error.kAssetCurrencyInsufficient_VALUE;
    private static final int REASON_BAG_FULL = AssetErrorTip.asset_error.kAssetBagFull_VALUE;

    private static final GuildAssetOrderStatus PENDING = GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING;
    private static final GuildAssetOrderStatus APPLIED = GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED;
    private static final GuildAssetOrderStatus REJECTED = GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED;

    private final PlayerFlow flow;
    private final String accountA;
    private final String accountB;
    private final Duration requestTimeout;
    private final int create;
    private final int getPlayerGuild;
    private final int apply;
    private final int review;
    private final int disband;
    private final int notify;
    private final int donateOptions;
    private final int donate;
    private final int upgrade;
    private final int shop;
    private final int buy;
    private final int getBag;
    private final int gmAdd;
    private final int gmDeduct;
    private final CheckReport report = new CheckReport();
    private final List<GameConnection> connections = new ArrayList<>();
    /** 每个机器人每种推送「应该收到几条」：只有回包 PENDING、由重投循环终结的那几笔才推（键 = 机器人名 + kind）。 */
    private final Map<String, Integer> expectedPushes = new HashMap<>();
    private GuildScenario.Bot leader;
    private long guildId;
    private boolean disbanded;

    public GuildEconomyScenario(PlayerFlow flow, MessageIdRegistry registry, String accountPrefix, String runTag,
                                Duration requestTimeout) {
        this.flow = flow;
        this.accountA = accountName(accountPrefix, runTag, "a");
        this.accountB = accountName(accountPrefix, runTag, "b");
        this.requestTimeout = requestTimeout;
        this.create = registry.requireId(SERVICE, "CreateGuild");
        this.getPlayerGuild = registry.requireId(SERVICE, "GetPlayerGuild");
        this.apply = registry.requireId(SERVICE, "ApplyJoinGuild");
        this.review = registry.requireId(SERVICE, "ReviewGuildApplication");
        this.disband = registry.requireId(SERVICE, "DisbandGuild");
        this.notify = registry.requireId(SERVICE, "NotifyGuildChanged");
        this.donateOptions = registry.requireId(SERVICE, "GetGuildDonateOptions");
        this.donate = registry.requireId(SERVICE, "DonateToGuild");
        this.upgrade = registry.requireId(SERVICE, "UpgradeGuild");
        this.shop = registry.requireId(SERVICE, "GetGuildShop");
        this.buy = registry.requireId(SERVICE, "BuyGuildShopGoods");
        this.getBag = registry.requireId(BAG_SERVICE, "GetBag");
        this.gmAdd = registry.requireId(CURRENCY_SERVICE, "GmAddCurrency");
        this.gmDeduct = registry.requireId(CURRENCY_SERVICE, "GmDeductCurrency");
    }

    public static String accountName(String prefix, String runTag, String suffix) {
        return prefix + "ge" + runTag + "_" + suffix;
    }

    public String accountA() {
        return accountA;
    }

    public CheckReport run() {
        try {
            runChecks();
        } catch (RobotException | RuntimeException e) {
            report.fail("流程中断", e.getMessage() == null ? e.toString() : e.getMessage(), REF);
        } finally {
            disbandLeftover();
            connections.forEach(GameConnection::close);
        }
        return report;
    }

    private void runChecks() throws RobotException {
        GuildScenario.Bot a = enter("A", accountA);
        leader = a;
        GuildScenario.Bot b = enter("B", accountB);
        long startMs = System.currentTimeMillis();
        int startDay = GameDay.dayKey(startMs);
        report.note("A=" + uid(a.id()) + " B=" + uid(b.id()) + " 游戏日 " + startDay);
        long untilReset = GameDay.nextDailyResetMillis(startMs) - startMs;
        if (untilReset < CUTOVER_MARGIN.toMillis()) {
            report.note("距下一个游戏日切点（UTC+8 05:00）只剩 " + untilReset / 1000 + " s：跑过切点每日次数会重置，限次断言可能失效");
        }

        // ---- 第 1 步：干净的起点 + 建帮 + B 入帮（Go :160-193） ----
        String aProblem = notInGuildProblem(a);
        String bProblem = notInGuildProblem(b);
        must(aProblem.isEmpty() && bProblem.isEmpty(), "第 1 步 前置：A、B 都不在帮会（run-tag 新号）",
                "A：" + (aProblem.isEmpty() ? "不在帮" : aProblem) + "；B：" + (bProblem.isEmpty() ? "不在帮" : bProblem));
        CreateGuildResponse created = a.call(create, CreateGuildRequest.newBuilder().setName("经济测" + GuildScenario.nonce()).build(),
                CreateGuildResponse.parser());
        GuildInfo guild = created.getGuild();
        guildId = guild.getGuildId();
        must(GuildScenario.tipOf(created) == 0 && guildId != 0 && guild.getLevel() == 1 && guild.getFunds() == 0
                        && guild.getMaxMembers() == LEVEL1_MAX_MEMBERS,
                "第 1 步 A 建帮：Lv.1、资金 0、上限 " + LEVEL1_MAX_MEMBERS, GuildScenario.describe(created) + economyOf(guild));
        report.check(guild.getUpgradeCostFunds() == LEVEL1_UPGRADE_COST,
                "第 1 步 建帮快照 upgrade_cost_funds = GuildLevel[1] 的 " + LEVEL1_UPGRADE_COST, economyOf(guild), REF);
        ApplyJoinGuildResponse applied = b.call(apply, ApplyJoinGuildRequest.newBuilder().setGuildId(guildId).build(),
                ApplyJoinGuildResponse.parser());
        must(GuildScenario.tipOf(applied) == 0, "第 1 步 B 申请入帮", GuildScenario.describeTip(applied));
        ReviewGuildApplicationResponse approved = a.call(review, ReviewGuildApplicationRequest.newBuilder()
                .setApplicantPlayerId(b.id()).setApprove(true).build(), ReviewGuildApplicationResponse.parser());
        must(GuildScenario.tipOf(approved) == 0, "第 1 步 A 通过 B 的申请", GuildScenario.describeTip(approved));
        GuildInfo bGuild = myGuild(b);
        must(bGuild.getGuildId() == guildId && GuildScenario.roleOf(bGuild, b.id()) == GuildScenario.ROLE_MEMBER,
                "第 1 步 B 查到自己在帮、是普通成员", GuildScenario.describe(bGuild));

        // ---- 第 2 步：读两页，判模式（Go :195-216） ----
        long before = System.currentTimeMillis();
        GetGuildDonateOptionsResponse options = donateOptions(a);
        long after = System.currentTimeMillis();
        must(GuildScenario.tipOf(options) == 0 && options.getOptionsCount() == DONATE_OPTION_COUNT
                        && options.getPendingDonationsCount() == 0,
                "第 2 步 A 读捐献页：" + DONATE_OPTION_COUNT + " 个选项、没有结算中", describe(options));
        String optionsProblem = donateOptionsProblem(options, 1);
        report.check(optionsProblem.isEmpty() && options.getContributionTotal() == 0 && options.getContributionBalance() == 0,
                "第 2 步 选项按 donate_id 升序；大捐 / 灵石捐的币种、花费、收益、每日次数、门槛与配表一致且已解锁；本人帮贡 0 / 0",
                optionsProblem.isEmpty() ? describe(options) : optionsProblem, REF);
        report.check(resetMatches(options.getNextDailyResetMs(), before, after, GameDay::nextDailyResetMillis),
                "第 2 步 捐献页 next_daily_reset_ms 是下一个游戏日切点（UTC+8 05:00）",
                resetDetail(options.getNextDailyResetMs(), before, GameDay::nextDailyResetMillis), REF);
        before = System.currentTimeMillis();
        GetGuildShopResponse shopPage = shop(a);
        after = System.currentTimeMillis();
        must(GuildScenario.tipOf(shopPage) == 0 && shopPage.getGoodsCount() == SHOP_GOODS_COUNT,
                "第 2 步 A 读商店：" + SHOP_GOODS_COUNT + " 件（GuildShop 默认行）", describe(shopPage));
        String goodsProblem = shopGoodsProblem(shopPage, 1);
        report.check(goodsProblem.isEmpty() && shopPage.getContributionBalance() == 0 && shopPage.getPendingOrdersCount() == 0,
                "第 2 步 商品按 (category, goods_id) 升序；101 / 103 / 104 / 202 / 301 的字段、Lv.1 解锁与单次上限对得上；可用帮贡 0、没有待发放",
                goodsProblem.isEmpty() ? describe(shopPage) : goodsProblem, REF);
        report.check(resetMatches(shopPage.getNextDailyResetMs(), before, after, GameDay::nextDailyResetMillis)
                        && resetMatches(shopPage.getNextWeeklyResetMs(), before, after, GameDay::nextWeeklyResetMillis),
                "第 2 步 商店 next_daily / next_weekly_reset_ms 是下一个游戏日 / 游戏周切点（周一 05:00）",
                resetDetail(shopPage.getNextDailyResetMs(), before, GameDay::nextDailyResetMillis) + "；"
                        + resetDetail(shopPage.getNextWeeklyResetMs(), before, GameDay::nextWeeklyResetMillis), REF);
        int bigUsed = option(options, DONATE_BIG.id()).getUsedToday();
        int spiritUsed = option(options, DONATE_SPIRIT.id()).getUsedToday();
        int pillUsed = goods(shopPage, GOODS_PILL.id()).getUsedCount();
        int lanternUsed = goods(shopPage, GOODS_LANTERN.id()).getUsedCount();
        must(bigUsed == 0 && spiritUsed == 0 && pillUsed == 0 && lanternUsed == 0, "第 2 步 今日用量全为 0（基线 full 模式）",
                "大捐 " + bigUsed + " 灵石捐 " + spiritUsed + " 兑 101 " + pillUsed + " 兑 301 " + lanternUsed
                        + "；非 0 说明同一游戏日重复用了同一个 --run-tag（计数按玩家、按游戏日，解散清不掉），省略 --run-tag 换新号");
        report.note("economy mode=full guild_id=" + uid(guildId));

        // ---- 第 3 步：B 的灵石清零（第 8 步要 B 余额不足；Go :218-222） ----
        long bSpirit = currency(bag(b), CURRENCY_SPIRIT);
        if (bSpirit != 0) {
            GmDeductCurrencyResponse deducted = gmDeduct(b, CURRENCY_SPIRIT, bSpirit);
            must(deducted.getErrorMessage().getId() == 0 && deducted.getBalanceAfter() == 0, "第 3 步 GM 把 B 的灵石扣到 0（49）",
                    "tip=" + deducted.getErrorMessage().getId() + " balance_after=" + uid(deducted.getBalanceAfter()));
        } else {
            report.note("第 3 步 B 的灵石本就是 0（新号），不用 GM 扣");
        }

        // ---- 第 4 步：GM 发钱（Go :240-245） ----
        BagInfo aBag0 = bag(a);
        long silver0 = currency(aBag0, CURRENCY_SILVER);
        long spirit0 = currency(aBag0, CURRENCY_SPIRIT);
        GmAddCurrencyResponse silverAdded = gmAdd(a, CURRENCY_SILVER, GM_SILVER);
        must(silverAdded.getErrorMessage().getId() == 0 && silverAdded.getBalanceAfter() == silver0 + GM_SILVER,
                "第 4 步 GM 给 A 加 " + GM_SILVER + " 银两（37）", "tip=" + silverAdded.getErrorMessage().getId() + " balance_after="
                        + uid(silverAdded.getBalanceAfter()) + "，期望 " + uid(silver0 + GM_SILVER));
        GmAddCurrencyResponse spiritAdded = gmAdd(a, CURRENCY_SPIRIT, GM_SPIRIT);
        must(spiritAdded.getErrorMessage().getId() == 0 && spiritAdded.getBalanceAfter() == spirit0 + GM_SPIRIT,
                "第 4 步 GM 给 A 加 " + GM_SPIRIT + " 灵石（37）", "tip=" + spiritAdded.getErrorMessage().getId() + " balance_after="
                        + uid(spiritAdded.getBalanceAfter()) + "，期望 " + uid(spirit0 + GM_SPIRIT));

        // ---- 第 5 / 6 步：大捐两次，第三次撞每日上限（Go :247-256） ----
        int aSection = a.mark();
        int bSection = b.mark();
        donateApplied(a, DONATE_BIG, "第 5 步");
        checkGuild(a, "第 5 步", 1, DONATE_BIG.fundsGain(), DONATE_BIG.contributionGain(), DONATE_BIG.contributionGain());
        donateApplied(a, DONATE_BIG, "第 6 步");
        long funds = 2 * DONATE_BIG.fundsGain();
        long total = 2 * DONATE_BIG.contributionGain();
        checkGuild(a, "第 6 步", 1, funds, total, total);
        DonateToGuildResponse limited = donate(a, DONATE_BIG.id());
        report.check(GuildScenario.tipOf(limited) == TIP_DONATE_LIMIT && !limited.hasDonation(),
                "第 6 步 第 " + (DONATE_BIG.dailyLimit() + 1) + " 次大捐 → 14024，不写行、不带捐献视图",
                describe(limited) + dayHint(startDay), REF);
        checkGuild(a, "第 6 步 撞上限之后", 1, funds, total, total);
        GetGuildDonateOptionsResponse afterLimit = donateOptions(a);
        report.check(option(afterLimit, DONATE_BIG.id()).getUsedToday() == DONATE_BIG.dailyLimit()
                        && afterLimit.getContributionTotal() == total && afterLimit.getContributionBalance() == total,
                "第 6 步 捐献页：大捐今日用量 " + DONATE_BIG.dailyLimit() + "（撞上限的那次不占）、本人帮贡 " + total + " / " + total,
                describe(afterLimit), REF);

        // ---- 第 7 步：灵石捐，核对货币（Go :258-271） ----
        donateApplied(a, DONATE_SPIRIT, "第 7 步");
        funds += DONATE_SPIRIT.fundsGain(); // 44,000
        total += DONATE_SPIRIT.contributionGain(); // 440
        long balance = total;
        checkGuild(a, "第 7 步", 1, funds, total, balance);
        BagInfo aBag1 = bag(a);
        long wantSilver = silver0 + GM_SILVER - 2 * DONATE_BIG.cost();
        long wantSpirit = spirit0 + GM_SPIRIT - DONATE_SPIRIT.cost();
        report.check(currency(aBag1, CURRENCY_SILVER) == wantSilver,
                "第 7 步 A 的银两 = 初值 " + uid(silver0) + " + " + GM_SILVER + " − " + 2 * DONATE_BIG.cost(),
                "银两 " + uid(currency(aBag1, CURRENCY_SILVER)) + "，期望 " + uid(wantSilver), REF);
        report.check(currency(aBag1, CURRENCY_SPIRIT) == wantSpirit,
                "第 7 步 A 的灵石 = 初值 " + uid(spirit0) + " + " + GM_SPIRIT + " − " + DONATE_SPIRIT.cost(),
                "灵石 " + uid(currency(aBag1, CURRENCY_SPIRIT)) + "，期望 " + uid(wantSpirit), REF);

        // ---- 第 8 步：B 余额不足（Go :273-274） ----
        memberCannotAfford(b, "第 8 步");

        // ---- 第 9 步：升级（Go :276-296） ----
        UpgradeGuildResponse denied = b.call(upgrade, UpgradeGuildRequest.newBuilder().setExpectedLevel(1).build(),
                UpgradeGuildResponse.parser());
        report.check(GuildScenario.tipOf(denied) == TIP_RANK_TOO_LOW && denied.getGuild().getGuildId() == guildId,
                "第 9 步 B（普通成员）升级 → 14016，回包仍带帮会快照（客户端据此刷新资金）",
                GuildScenario.describeTip(denied) + (denied.hasGuild() ? economyOf(denied.getGuild()) : " 无帮会"), REF);
        int aUpgradeMark = a.mark();
        int bUpgradeMark = b.mark();
        UpgradeGuildResponse upgraded = a.call(upgrade, UpgradeGuildRequest.newBuilder().setExpectedLevel(1).build(),
                UpgradeGuildResponse.parser());
        funds -= LEVEL1_UPGRADE_COST; // 24,000
        GuildInfo up = upgraded.getGuild();
        must(GuildScenario.tipOf(upgraded) == 0 && up.getLevel() == 2 && up.getFunds() == funds && up.getMaxMembers() == LEVEL2_MAX_MEMBERS,
                "第 9 步 A 升级 → Lv.2、资金 " + funds + "、上限 " + LEVEL2_MAX_MEMBERS,
                GuildScenario.describeTip(upgraded) + economyOf(up));
        report.check(up.getUpgradeCostFunds() == LEVEL2_UPGRADE_COST,
                "第 9 步 升级后快照 upgrade_cost_funds = GuildLevel[2] 的 " + LEVEL2_UPGRADE_COST, economyOf(up), REF);
        expectPush(b, bUpgradeMark, GuildChangeKind.GUILD_CHANGE_KIND_LEVEL_UP, a.id(), 0,
                "第 9 步 B 收到 LEVEL_UP（actor = A、target = 0）");
        report.check(changes(a, aUpgradeMark, GuildChangeKind.GUILD_CHANGE_KIND_LEVEL_UP).isEmpty(),
                "第 9 步 操作者 A 不收 LEVEL_UP（回包就是最新状态）", describeChanges(a, aUpgradeMark), REF);
        UpgradeGuildResponse again = a.call(upgrade, UpgradeGuildRequest.newBuilder().setExpectedLevel(1).build(),
                UpgradeGuildResponse.parser());
        report.check(GuildScenario.tipOf(again) == 0 && again.getGuild().getLevel() == 2 && again.getGuild().getFunds() == funds,
                "第 9 步 A 带旧等级 1 再升 → 受理、仍 Lv.2 / " + funds + "（重复点击不连升、不扣钱）",
                GuildScenario.describeTip(again) + economyOf(again.getGuild()), REF);
        // Java 增项（基线只在 degraded 模式里测资金不足）：Lv.2 → 3 要 50,000，资金 24,000 不够；业务拒绝也带最新快照、资金不变
        UpgradeGuildResponse poor = a.call(upgrade, UpgradeGuildRequest.newBuilder().setExpectedLevel(2).build(),
                UpgradeGuildResponse.parser());
        report.check(GuildScenario.tipOf(poor) == TIP_FUNDS_INSUFFICIENT && poor.getGuild().getLevel() == 2
                        && poor.getGuild().getFunds() == funds,
                "第 9 步 A 再升 Lv.3（资金 " + funds + " < " + LEVEL2_UPGRADE_COST + "）→ 14022，回包带帮会、仍 Lv.2 / " + funds,
                GuildScenario.describeTip(poor) + (poor.hasGuild() ? economyOf(poor.getGuild()) : " 无帮会"), REF);

        // ---- 第 10 步：商店随等级解锁（Go :298-310） ----
        GetGuildShopResponse level2Shop = shop(a);
        report.check(goods(level2Shop, GOODS_LEVEL2.id()).getUnlocked() && !goods(level2Shop, GOODS_LEVEL3.id()).getUnlocked(),
                "第 10 步 Lv.2 帮会：" + GOODS_LEVEL2.id() + " 已解锁、" + GOODS_LEVEL3.id() + " 未解锁", describe(level2Shop), REF);
        report.check(level2Shop.getContributionBalance() == balance, "第 10 步 商店可用帮贡 " + balance,
                "contribution_balance=" + uid(level2Shop.getContributionBalance()), REF);
        report.check(goods(level2Shop, GOODS_AMULET.id()).getMaxBuyCount() == 1,
                "第 10 步 " + GOODS_AMULET.id() + "（堆叠 1 的物品）单次最多 1 份",
                "max_buy_count=" + goods(level2Shop, GOODS_AMULET.id()).getMaxBuyCount(), REF);
        String level2Problem = shopGoodsProblem(level2Shop, 2);
        report.check(level2Problem.isEmpty(), "第 10 步 Lv.2 帮会的解锁位与其余字段（商品页的 unlocked 按帮会等级 ≥ 门槛）",
                level2Problem, REF);
        report.check(changes(b, bUpgradeMark, GuildChangeKind.GUILD_CHANGE_KIND_LEVEL_UP).size() == 1,
                "第 9 步 带旧等级重升与资金不足都没有再推 LEVEL_UP（B 只收到 1 条）", describeChanges(b, bUpgradeMark), REF);

        // ---- 第 11 步：兑换一份，背包到货（Go :312-318） ----
        long pill0 = itemCount(bag(a), GOODS_PILL.itemId());
        balance -= GOODS_PILL.cost(); // 410
        buyApplied(a, GOODS_PILL, 1, balance, "第 11 步");
        long pill1 = itemCount(bag(a), GOODS_PILL.itemId());
        report.check(pill1 == pill0 + GOODS_PILL.itemCount(),
                "第 11 步 A 背包里物品 " + GOODS_PILL.itemId() + " 数量 +" + GOODS_PILL.itemCount(),
                "兑换前 " + pill0 + "、兑换后 " + pill1, REF);

        // ---- 第 12 步：三种拒绝（都在发号之前，不留指令行；Go :320-321、:388-399） ----
        expectBuyRejected(a, GOODS_LEVEL3.id(), 1, TIP_LEVEL_TOO_LOW,
                "第 12 步 A 兑换 " + GOODS_LEVEL3.id() + "（需 Lv." + GOODS_LEVEL3.requiredLevel() + "）→ 14029");
        expectBuyRejected(b, GOODS_PILL.id(), 1, TIP_CONTRIBUTION_INSUFFICIENT,
                "第 12 步 B（可用帮贡 0）兑换 " + GOODS_PILL.id() + " → 14031");
        expectBuyRejected(a, GOODS_AMULET.id(), 2, TIP_SHOP_LIMIT,
                "第 12 步 A 一次兑 2 份 " + GOODS_AMULET.id() + "（单次上限 1）→ 14030");
        // Java 增项：配表查不到的商品 / 捐献项（先于发号，spec §3.2 第 5 步、§3.5 第 3 步）
        expectBuyRejected(a, UNKNOWN_GOODS_ID, 1, TIP_GOODS_NOT_FOUND, "商品 " + UNKNOWN_GOODS_ID + "（配表没有）→ 14028");
        DonateToGuildResponse unknownDonate = donate(a, GuildScenario.UNKNOWN_DONATE_ID);
        report.check(GuildScenario.tipOf(unknownDonate) == TIP_ASSET_REJECTED && !unknownDonate.hasDonation(),
                "捐献项 " + GuildScenario.UNKNOWN_DONATE_ID + "（配表没有）→ 14027，不带捐献视图", describe(unknownDonate), REF);

        // ---- 第 13 步：限购（Go :323-334；同号连发由 Bot 的节拍退火） ----
        for (int i = 1; i <= GOODS_LANTERN.limitCount(); i++) {
            balance -= GOODS_LANTERN.cost();
            // Java 增项：第 1 份不填份数（老客户端），服务端按 1 份算（spec §3.5 第 4 步）
            buyApplied(a, GOODS_LANTERN, i == 1 ? 0 : 1, balance, "第 13 步 第 " + i + " 份");
        }
        expectBuyRejected(a, GOODS_LANTERN.id(), 1, TIP_SHOP_LIMIT,
                "第 13 步 第 " + (GOODS_LANTERN.limitCount() + 1) + " 份 " + GOODS_LANTERN.id() + "（每日 " + GOODS_LANTERN.limitCount()
                        + " 份）→ 14030" + dayHint(startDay));

        // ---- 收尾核对（Java 增项） ----
        GetGuildShopResponse finalShop = shop(a);
        report.check(goods(finalShop, GOODS_PILL.id()).getUsedCount() == 1
                        && goods(finalShop, GOODS_LANTERN.id()).getUsedCount() == GOODS_LANTERN.limitCount()
                        && finalShop.getContributionBalance() == balance && finalShop.getPendingOrdersCount() == 0,
                "收尾 商店：" + GOODS_PILL.id() + " 用量 1、" + GOODS_LANTERN.id() + " 用量 " + GOODS_LANTERN.limitCount()
                        + "、可用帮贡 " + balance + "、没有待发放", describe(finalShop), REF);
        checkGuild(a, "收尾", 2, funds, total, balance);
        checkPushCount(a, aSection, GuildChangeKind.GUILD_CHANGE_KIND_FUNDS_CHANGED,
                "收尾 A 收到的 FUNDS_CHANGED 条数 = 后台终结的捐献笔数（同步投递当场终结的不推）");
        checkPushCount(b, bSection, GuildChangeKind.GUILD_CHANGE_KIND_FUNDS_CHANGED,
                "收尾 B 收到的 FUNDS_CHANGED 条数 = 后台终结的捐献笔数");
        checkPushCount(a, aSection, GuildChangeKind.GUILD_CHANGE_KIND_DELIVERY_DONE,
                "收尾 A 收到的 DELIVERY_DONE 条数 = 后台终结的兑换笔数（同步投递当场终结的不推）");
        int endDay = GameDay.dayKey(System.currentTimeMillis());
        report.check(endDay == startDay, "全程在同一游戏日内（UTC+8 05:00 切日；跨过切点每日次数重置，限次断言无效）",
                "开始 " + startDay + "、结束 " + endDay, REF);

        // ---- 解散（Go :232） ----
        DisbandGuildResponse disbandedResp = a.call(disband, DisbandGuildRequest.getDefaultInstance(), DisbandGuildResponse.parser());
        disbanded = GuildScenario.tipOf(disbandedResp) == 0;
        report.check(disbanded, "A 解散帮会", GuildScenario.describeTip(disbandedResp), REF);
        if (disbanded) {
            // Java 增项：经济五个号都先过「在帮」前置（spec §3.0），解散后读写都是 14002
            GetGuildShopResponse goneShop = shop(a);
            report.check(GuildScenario.tipOf(goneShop) == TIP_NOT_IN_GUILD, "解散后 A 读商店 → 14002",
                    GuildScenario.describeTip(goneShop), REF);
            DonateToGuildResponse goneDonate = donate(a, DONATE_BIG.id());
            report.check(GuildScenario.tipOf(goneDonate) == TIP_NOT_IN_GUILD && !goneDonate.hasDonation(),
                    "解散后 A 捐献 → 14002、不带捐献视图", describe(goneDonate), REF);
        }
        report.note("等价于基线 GUILD_ECONOMY_SMOKE_OK mode=full guild_id=" + uid(guildId) + " funds=" + uid(funds) + " level=2 balance="
                + uid(balance));
    }

    // ================================================================ 捐献 / 兑换

    /** 捐一次并等到入账：回包 APPLIED 即同步投递当场终结；结算中就轮询捐献页，落定后等后台终结的 FUNDS_CHANGED（Go econDonateApplied）。 */
    private void donateApplied(GuildScenario.Bot bot, DonateRow row, String step) throws RobotException {
        int mark = bot.mark();
        DonateToGuildResponse response = donate(bot, row.id());
        int tip = GuildScenario.tipOf(response);
        must(tip == 0, step + " " + bot.name + " 捐献 " + row.id() + " 受理", describe(response) + hint(tip));
        GuildDonationView view = response.getDonation();
        String viewProblem = response.hasDonation() ? donationViewProblem(view, row) : "没有捐献视图（已写入指令时必填）";
        report.check(viewProblem.isEmpty() && response.getGuild().getGuildId() == guildId,
                step + " 回包带帮会快照与捐献视图（op_id ≠ 0、币种 / 花费 / 收益取配表行）",
                viewProblem.isEmpty() ? describe(response) : viewProblem, REF);
        if (view.getStatus() == APPLIED) {
            report.note(step + " 同步投递当场入账（op " + uid(view.getOpId()) + "）");
            return;
        }
        must(view.getStatus() == PENDING, step + " 捐献视图是 APPLIED 或结算中", describe(view));
        report.note(step + " 回包结算中（reason " + view.getReasonTipId() + "），轮询捐献页等重投循环落定");
        GuildDonationView settled = awaitDonation(bot, view.getOpId(), step);
        must(settled.getStatus() == APPLIED, step + " 捐献 " + row.id() + " 最终 APPLIED", describe(settled));
        expectBackgroundPush(bot, mark, GuildChangeKind.GUILD_CHANGE_KIND_FUNDS_CHANGED,
                step + " 后台终结推 FUNDS_CHANGED 给本人（actor = 0、target = " + bot.name + "）");
    }

    /** B 灵石为 0 时捐灵石 → 货币不足且视图 REJECTED；次数退回、最近结果记下原因（Go econMemberCannotAfford）。 */
    private void memberCannotAfford(GuildScenario.Bot bot, String step) throws RobotException {
        int mark = bot.mark();
        DonateToGuildResponse response = donate(bot, DONATE_SPIRIT.id());
        int tip = GuildScenario.tipOf(response);
        GuildDonationView view = response.getDonation();
        if (tip == TIP_CURRENCY_INSUFFICIENT && view.getStatus() == REJECTED) {
            report.check(response.hasDonation() && view.getOpId() != 0 && view.getReasonTipId() == REASON_CURRENCY_INSUFFICIENT
                            && response.getGuild().getGuildId() == guildId,
                    step + " " + bot.name + " 灵石捐献 → 14025、视图 REJECTED（原因 27000）、仍带帮会快照", describe(response), REF);
        } else if (tip == 0 && view.getStatus() == PENDING) {
            // scene 已判拒但还没落盘（未 durable）：视图是结算中，等它落定
            report.note(step + " 灵石捐献回包结算中（reason " + view.getReasonTipId() + "），轮询捐献页等落定");
            GuildDonationView settled = awaitDonation(bot, view.getOpId(), step);
            report.check(settled.getStatus() == REJECTED && settled.getReasonTipId() == REASON_CURRENCY_INSUFFICIENT,
                    step + " " + bot.name + " 灵石捐献最终 REJECTED、原因 27000", describe(settled), REF);
            expectBackgroundPush(bot, mark, GuildChangeKind.GUILD_CHANGE_KIND_FUNDS_CHANGED,
                    step + " 后台终结（含拒绝）推 FUNDS_CHANGED 给本人（actor = 0、target = " + bot.name + "）");
        } else {
            report.fail(step + " " + bot.name + " 灵石捐献 → 14025 + REJECTED",
                    describe(response) + "，期望 tip=" + TIP_CURRENCY_INSUFFICIENT + " 且 REJECTED（或结算中再落定为 REJECTED）" + hint(tip), REF);
        }
        GetGuildDonateOptionsResponse options = donateOptions(bot);
        report.check(option(options, DONATE_SPIRIT.id()).getUsedToday() == 0,
                step + " 被拒之后 " + bot.name + " 的灵石捐献今日用量仍为 0（REJECTED 退回次数）", describe(options), REF);
        List<GuildDonationView> recent = options.getRecentResultsList();
        report.check(!recent.isEmpty() && recent.get(0).getStatus() == REJECTED
                        && recent.get(0).getReasonTipId() == REASON_CURRENCY_INSUFFICIENT,
                step + " " + bot.name + " 的最近结果第一条是 REJECTED、原因 27000（货币不足）", describeDonations(recent), REF);
    }

    /** 轮询捐献页，直到这笔不在结算中、并出现在最近结果里（Go econAwaitDonation）。 */
    private GuildDonationView awaitDonation(GuildScenario.Bot bot, long opId, String step) throws RobotException {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        while (true) {
            GetGuildDonateOptionsResponse options = donateOptions(bot);
            if (GuildScenario.tipOf(options) != 0) {
                throw new RobotException(step + " 轮询捐献页 " + GuildScenario.describeTip(options));
            }
            boolean pending = options.getPendingDonationsList().stream().anyMatch(v -> v.getOpId() == opId);
            if (!pending) {
                return options.getRecentResultsList().stream().filter(v -> v.getOpId() == opId).findFirst()
                        .orElseThrow(() -> new RobotException(step + " op " + uid(opId) + " 已不在结算中，但最近结果里也没有它："
                                + describeDonations(options.getRecentResultsList())));
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new RobotException(step + " op " + uid(opId) + " 结算超时（" + SETTLE_TIMEOUT.toSeconds()
                        + " s）：看 xm-guild 日志里该 op 的资产通道投递与 xm-scene 日志 [AssetOp]");
            }
            sleep(SETTLE_POLL);
        }
    }

    /** 兑换并等到发放，核对提交后的可用帮贡与订单视图；待发放就轮询商店页，落定后等 DELIVERY_DONE（Go econBuyApplied）。 */
    private void buyApplied(GuildScenario.Bot bot, GoodsRow row, int count, long wantBalance, String step) throws RobotException {
        int mark = bot.mark();
        BuyGuildShopGoodsResponse response = buy(bot, row.id(), count);
        int tip = GuildScenario.tipOf(response);
        must(tip == 0, step + " " + bot.name + " 兑换 " + row.id() + (count == 0 ? "（不填份数）" : " ×" + count) + " 受理",
                describe(response) + hint(tip));
        must(response.getContributionBalance() == wantBalance, step + " 兑换 " + row.id() + " 后可用帮贡 " + wantBalance,
                describe(response));
        int effective = Math.max(count, 1);
        GuildShopOrderView order = response.getOrder();
        report.check(response.hasOrder() && order.getOpId() != 0 && order.getGoodsId() == row.id() && order.getCount() == effective
                        && order.getCostContribution() == row.cost() * effective && order.getCreatedMs() != 0,
                step + " 订单视图：op_id ≠ 0、商品 " + row.id() + "、" + effective + " 份、总帮贡 " + row.cost() * effective
                        + (count == 0 ? "（份数 0 按 1 份）" : ""), describe(response), REF);
        if (order.getStatus() == APPLIED) {
            report.note(step + " 同步投递当场发放（op " + uid(order.getOpId()) + "）");
            return;
        }
        must(order.getStatus() == PENDING, step + " 订单是 APPLIED 或待发放", describe(order));
        report.note(step + " 回包待发放（reason " + order.getReasonTipId() + "），轮询商店页等重投循环落定");
        GuildShopOrderView settled = awaitOrder(bot, order.getOpId(), step);
        must(settled.getStatus() == APPLIED, step + " 兑换 " + row.id() + " 最终 APPLIED", describe(settled));
        expectBackgroundPush(bot, mark, GuildChangeKind.GUILD_CHANGE_KIND_DELIVERY_DONE,
                step + " 后台终结推 DELIVERY_DONE 给本人（actor = 0、target = " + bot.name + "）");
    }

    /** 轮询商店页，直到这单不在待发放、并出现在最近订单里。 */
    private GuildShopOrderView awaitOrder(GuildScenario.Bot bot, long opId, String step) throws RobotException {
        long deadline = System.nanoTime() + SETTLE_TIMEOUT.toNanos();
        while (true) {
            sleep(SETTLE_POLL);
            GetGuildShopResponse page = shop(bot);
            if (GuildScenario.tipOf(page) != 0) {
                throw new RobotException(step + " 轮询商店页 " + GuildScenario.describeTip(page));
            }
            boolean pending = page.getPendingOrdersList().stream().anyMatch(o -> o.getOpId() == opId);
            if (!pending) {
                return page.getRecentOrdersList().stream().filter(o -> o.getOpId() == opId).findFirst()
                        .orElseThrow(() -> new RobotException(step + " op " + uid(opId) + " 已不在待发放，但最近订单里也没有它"));
            }
            if (System.nanoTime() - deadline >= 0) {
                Optional<GuildShopOrderView> still = page.getPendingOrdersList().stream().filter(o -> o.getOpId() == opId).findFirst();
                int reason = still.map(GuildShopOrderView::getReasonTipId).orElse(0);
                throw new RobotException(step + " op " + uid(opId) + " 待发放超时（" + SETTLE_TIMEOUT.toSeconds() + " s），最近原因 " + reason
                        + (reason == REASON_BAG_FULL ? "（27001 = 背包满）" : ""));
            }
        }
    }

    /** 没写行的拒绝：只回 error_message、不带订单视图。 */
    private void expectBuyRejected(GuildScenario.Bot bot, int goodsId, int count, int wantTip, String name) throws RobotException {
        BuyGuildShopGoodsResponse response = buy(bot, goodsId, count);
        int got = GuildScenario.tipOf(response);
        report.check(got == wantTip && !response.hasOrder(), name + "，不带订单视图",
                describe(response) + (got == wantTip ? "" : "，期望 tip=" + wantTip + hint(got)), REF);
    }

    // ================================================================ 请求

    private GetGuildDonateOptionsResponse donateOptions(GuildScenario.Bot bot) throws RobotException {
        return bot.call(donateOptions, GetGuildDonateOptionsRequest.getDefaultInstance(), GetGuildDonateOptionsResponse.parser());
    }

    private DonateToGuildResponse donate(GuildScenario.Bot bot, int donateId) throws RobotException {
        return bot.call(donate, DonateToGuildRequest.newBuilder().setDonateId(donateId).build(), DonateToGuildResponse.parser());
    }

    private GetGuildShopResponse shop(GuildScenario.Bot bot) throws RobotException {
        return bot.call(shop, GetGuildShopRequest.getDefaultInstance(), GetGuildShopResponse.parser());
    }

    private BuyGuildShopGoodsResponse buy(GuildScenario.Bot bot, int goodsId, int count) throws RobotException {
        return bot.call(buy, BuyGuildShopGoodsRequest.newBuilder().setGoodsId(goodsId).setCount(count).build(),
                BuyGuildShopGoodsResponse.parser());
    }

    /** GetPlayerGuild：必须受理且带帮会。 */
    private GuildInfo myGuild(GuildScenario.Bot bot) throws RobotException {
        GetPlayerGuildResponse response = bot.call(getPlayerGuild, GetPlayerGuildRequest.getDefaultInstance(),
                GetPlayerGuildResponse.parser());
        if (GuildScenario.tipOf(response) != 0 || !response.hasGuild()) {
            throw new RobotException(bot.name + " GetPlayerGuild 期望受理且带帮会，实得 " + GuildScenario.describeTip(response));
        }
        return response.getGuild();
    }

    /** @return 空串 = 不在任何帮会；否则是原因 */
    private String notInGuildProblem(GuildScenario.Bot bot) throws RobotException {
        GetPlayerGuildResponse response = bot.call(getPlayerGuild, GetPlayerGuildRequest.getDefaultInstance(),
                GetPlayerGuildResponse.parser());
        int tip = GuildScenario.tipOf(response);
        if (tip == TIP_NOT_IN_GUILD) {
            return "";
        }
        if (tip == 0) {
            return "已在帮会 " + uid(response.getGuild().getGuildId()) + "（--run-tag 重复用过？省略它换新号）";
        }
        return "GetPlayerGuild " + GuildScenario.describeTip(response);
    }

    /** 读主背包（191）：物品与货币都在这一份快照里。 */
    private BagInfo bag(GuildScenario.Bot bot) throws RobotException {
        GetBagResponse response = bot.call(getBag, GetBagRequest.newBuilder().setBagType(MAIN_BAG).build(), GetBagResponse.parser());
        if (response.getErrorMessage().getId() != 0 || !response.hasBag()) {
            throw new RobotException(bot.name + " 读背包（191）tip=" + response.getErrorMessage().getId() + " has_bag=" + response.hasBag());
        }
        return response.getBag();
    }

    /** GM 加货币（37）：只在 dev / test 运行模式放行；生产模式 gate 推 23 {1006} 且不转发，表现为等不到应答。 */
    private GmAddCurrencyResponse gmAdd(GuildScenario.Bot bot, int currencyType, long amount) throws RobotException {
        try {
            return bot.call(gmAdd, GmAddCurrencyRequest.newBuilder().setCurrencyType(currencyType).setAmount(amount).build(),
                    GmAddCurrencyResponse.parser());
        } catch (RobotException e) {
            throw new RobotException(e.getMessage() + GM_HINT, e);
        }
    }

    private GmDeductCurrencyResponse gmDeduct(GuildScenario.Bot bot, int currencyType, long amount) throws RobotException {
        try {
            return bot.call(gmDeduct, GmDeductCurrencyRequest.newBuilder().setCurrencyType(currencyType).setAmount(amount).build(),
                    GmDeductCurrencyResponse.parser());
        } catch (RobotException e) {
            throw new RobotException(e.getMessage() + GM_HINT, e);
        }
    }

    private static final String GM_HINT = "（GM 指令要 XM_RUN_MODE=dev 放行；生产模式 gate 推 23 {1006} 且不转发）";

    // ================================================================ 推送

    /** 记下「这笔由后台终结、应推一条」，再等那条推送（可能早在轮询期间就到了，所以从触发前的 mark 起找）。 */
    private void expectBackgroundPush(GuildScenario.Bot bot, int mark, GuildChangeKind kind, String name) throws RobotException {
        expectedPushes.merge(pushKey(bot, kind), 1, Integer::sum);
        expectPush(bot, mark, kind, 0, bot.id(), name);
    }

    /** 从 {@code mark} 起等第一条本帮、该 kind 的 220 推送，并核对 actor / target。 */
    private void expectPush(GuildScenario.Bot bot, int mark, GuildChangeKind kind, long actor, long target, String name)
            throws RobotException {
        Optional<Received> push = bot.connection().await(mark, r -> isChange(r, kind), GuildScenario.PUSH_TIMEOUT);
        GuildChangedS2C change = push.map(r -> r.parseOrNull(GuildChangedS2C.parser())).orElse(null);
        report.check(change != null && change.getActorPlayerId() == actor && change.getTargetPlayerId() == target, name,
                change != null ? GuildScenario.describe(change)
                        : GuildScenario.PUSH_TIMEOUT.toSeconds() + " s 内没收到；此刻收到的推送：[" + describeChanges(bot, mark) + "]"
                        + bot.describeSince(mark), REF);
    }

    /** 收尾：每人每种推送的条数恰好等于后台终结的笔数（多了 = 同步终结也推了；少了 = 推送丢了）。 */
    private void checkPushCount(GuildScenario.Bot bot, int mark, GuildChangeKind kind, String name) {
        int want = expectedPushes.getOrDefault(pushKey(bot, kind), 0);
        List<GuildChangedS2C> got = changes(bot, mark, kind);
        report.check(got.size() == want, name, "收到 " + got.size() + " 条，期望 " + want + "（多了 = 同步终结的也推了，或回读前恰被循环终结；"
                + "少了 = 推送丢了）：[" + describeChanges(bot, mark) + "]", REF);
    }

    private boolean isChange(Received r, GuildChangeKind kind) {
        if (r.messageId() != notify || r.requestId() != 0) {
            return false;
        }
        GuildChangedS2C change = r.parseOrNull(GuildChangedS2C.parser());
        return change != null && change.getKind() == kind && change.getGuildId() == guildId;
    }

    /** 从 {@code mark} 起此刻已收到的本帮、该 kind 的推送。 */
    private List<GuildChangedS2C> changes(GuildScenario.Bot bot, int mark, GuildChangeKind kind) {
        List<GuildChangedS2C> out = new ArrayList<>();
        for (Received r : bot.connection().inbox().snapshot(mark)) {
            if (isChange(r, kind)) {
                out.add(r.parseOrNull(GuildChangedS2C.parser()));
            }
        }
        return out;
    }

    /** 此刻留底的全部 220 推送（只用于失败信息）。 */
    private String describeChanges(GuildScenario.Bot bot, int mark) {
        List<String> parts = new ArrayList<>();
        for (Received r : bot.connection().inbox().snapshot(mark)) {
            if (r.messageId() == notify && r.requestId() == 0) {
                GuildChangedS2C change = r.parseOrNull(GuildChangedS2C.parser());
                parts.add(change == null ? "解不开的 220" : GuildScenario.describe(change));
            }
        }
        return parts.isEmpty() ? "空" : String.join(" ", parts);
    }

    private static String pushKey(GuildScenario.Bot bot, GuildChangeKind kind) {
        return bot.name + "/" + kind;
    }

    // ================================================================ 断言与生命周期

    /** 读自己的帮会，核对等级、资金与本人两列帮贡（Go econCheckGuild）。 */
    private void checkGuild(GuildScenario.Bot bot, String step, int level, long funds, long total, long balance) throws RobotException {
        GuildInfo guild = myGuild(bot);
        GuildMember me = member(guild, bot.id());
        report.check(guild.getGuildId() == guildId && guild.getLevel() == level && guild.getFunds() == funds && me != null
                        && me.getContributionTotal() == total && me.getContributionBalance() == balance,
                step + " " + bot.name + " 的帮会：Lv." + level + "、资金 " + uid(funds) + "、本人帮贡 累计 " + uid(total) + " / 可用 " + uid(balance),
                economyOf(guild) + (me == null ? " 成员表里没有本人" : " 本人帮贡 累计 " + uid(me.getContributionTotal()) + " / 可用 "
                        + uid(me.getContributionBalance())), REF);
    }

    /** 后续步骤依赖的检查：不通过即中止（记一条失败后抛出）。 */
    private void must(boolean passed, String name, String detail) throws RobotException {
        report.check(passed, name, detail, REF);
        if (!passed) {
            throw new RobotException("「" + name + "」未通过，后续步骤依赖它");
        }
    }

    private GuildScenario.Bot enter(String name, String account) throws RobotException {
        EnteredPlayer player = flow.enter(account, new Timings());
        connections.add(player.connection());
        return new GuildScenario.Bot(name, player, requestTimeout);
    }

    /** 流程中断时尽力解散建好的帮会（带着结算中的单解散也安全：离帮钩子把它们的截止拉到当下，spec §2.9）。 */
    private void disbandLeftover() {
        if (leader == null || guildId == 0 || disbanded) {
            return;
        }
        try {
            DisbandGuildResponse response = leader.call(disband, DisbandGuildRequest.getDefaultInstance(), DisbandGuildResponse.parser());
            report.note("流程中断后尽力解散帮会 " + uid(guildId) + "：" + GuildScenario.describeTip(response));
        } catch (RobotException | RuntimeException e) {
            report.note("流程中断后解散帮会 " + uid(guildId) + " 失败（留在库里；run-tag 新号不影响下次）：" + e.getMessage());
        }
    }

    private static void sleep(Duration duration) throws RobotException {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RobotException("等待被中断", e);
        }
    }

    /** 只给最常见的误配写人话：资产通道没开时经济写 RPC 一律回 14026（Go econTipHint）。 */
    private static String hint(int tip) {
        return tip == TIP_ASSET_PENDING
                ? "（14026：资产通道没开——xm-guild 的 xm.guild.asset-op.enabled=false 或缺 XM_ASSET_OP_SECRET_GUILD——或还有太多未结算的单）"
                : "";
    }

    private static String dayHint(int startDay) {
        int now = GameDay.dayKey(System.currentTimeMillis());
        return now == startDay ? "" : "（注意：已跨过游戏日切点 " + startDay + " → " + now + "，每日次数已重置）";
    }

    // ================================================================ 纯函数（GuildEconomyScenarioTest 钉住）

    /** 背包里某种货币的余额；repeated 可以比货币种类短，没写到的槽位就是 0（Go econCurrency）。 */
    static long currency(BagInfo bag, int currencyType) {
        List<Long> values = bag.getCurrency().getValuesList();
        return currencyType < values.size() ? values.get(currencyType) : 0;
    }

    /** 背包里某配置号物品的总数（count 是 uint32，按无符号累加）。 */
    static long itemCount(BagInfo bag, int configId) {
        long sum = 0;
        for (BagItemInfo item : bag.getItemsList()) {
            if (item.getConfigId() == configId) {
                sum += Integer.toUnsignedLong(item.getCount());
            }
        }
        return sum;
    }

    /** 选项；没有时为缺省实例（donate_id 0），由调用方的检查报出来。 */
    static GuildDonateOptionView option(GetGuildDonateOptionsResponse options, int donateId) {
        return options.getOptionsList().stream().filter(o -> o.getDonateId() == donateId).findFirst()
                .orElse(GuildDonateOptionView.getDefaultInstance());
    }

    static GuildShopGoodsView goods(GetGuildShopResponse shop, int goodsId) {
        return shop.getGoodsList().stream().filter(g -> g.getGoodsId() == goodsId).findFirst()
                .orElse(GuildShopGoodsView.getDefaultInstance());
    }

    static GuildMember member(GuildInfo guild, long playerId) {
        return guild.getMembersList().stream().filter(m -> m.getPlayerId() == playerId).findFirst().orElse(null);
    }

    /**
     * 返回的切点是否等于请求前后任一时刻的「下一个切点」（GameDay 严格晚于 t；两次取值只在请求恰好跨过切点时不同）。
     */
    static boolean resetMatches(long value, long beforeMs, long afterMs, LongUnaryOperator next) {
        return value != 0 && (value == next.applyAsLong(beforeMs) || value == next.applyAsLong(afterMs));
    }

    private static String resetDetail(long value, long beforeMs, LongUnaryOperator next) {
        return "回包 " + uid(value) + "，GameDay 算出 " + next.applyAsLong(beforeMs);
    }

    /**
     * 捐献页：选项按 donate_id 升序；大捐 / 灵石捐两项的字段与期望一致，解锁位按 {@code guildLevel ≥ min_guild_level}。
     *
     * @return 空串 = 符合；否则是不符之处
     */
    static String donateOptionsProblem(GetGuildDonateOptionsResponse options, int guildLevel) {
        List<GuildDonateOptionView> list = options.getOptionsList();
        for (int i = 1; i < list.size(); i++) {
            if (Integer.compareUnsigned(list.get(i - 1).getDonateId(), list.get(i).getDonateId()) >= 0) {
                return "选项没有按 donate_id 严格升序：" + describeOptionIds(list);
            }
        }
        for (DonateRow row : List.of(DONATE_BIG, DONATE_SPIRIT)) {
            GuildDonateOptionView view = option(options, row.id());
            if (view.getDonateId() != row.id()) {
                return "缺少选项 " + row.id() + "：" + describeOptionIds(list);
            }
            boolean unlocked = guildLevel >= row.minGuildLevel();
            if (view.getName().isEmpty() || view.getCurrencyType() != row.currencyType() || view.getCostAmount() != row.cost()
                    || view.getContributionGain() != row.contributionGain() || view.getFundsGain() != row.fundsGain()
                    || view.getDailyLimit() != row.dailyLimit() || view.getMinGuildLevel() != row.minGuildLevel()
                    || view.getUnlocked() != unlocked) {
                return "选项 " + row.id() + " 与配表不符：" + describe(view) + "，期望 " + row + " unlocked=" + unlocked;
            }
        }
        return "";
    }

    /**
     * 商店页：商品按 (category, goods_id) 升序；{@link #CHECKED_GOODS} 各件的字段与期望一致，解锁位按 {@code guildLevel ≥ required_guild_level}。
     *
     * @return 空串 = 符合；否则是不符之处
     */
    static String shopGoodsProblem(GetGuildShopResponse shop, int guildLevel) {
        List<GuildShopGoodsView> list = shop.getGoodsList();
        for (int i = 1; i < list.size(); i++) {
            GuildShopGoodsView prev = list.get(i - 1);
            GuildShopGoodsView cur = list.get(i);
            int byCategory = Integer.compareUnsigned(prev.getCategory(), cur.getCategory());
            if (byCategory > 0 || byCategory == 0 && Integer.compareUnsigned(prev.getGoodsId(), cur.getGoodsId()) >= 0) {
                return "商品没有按 (category, goods_id) 升序：第 " + i + " 件 " + prev.getCategory() + "/" + prev.getGoodsId() + " 之后是 "
                        + cur.getCategory() + "/" + cur.getGoodsId();
            }
        }
        for (GoodsRow row : CHECKED_GOODS) {
            GuildShopGoodsView view = goods(shop, row.id());
            if (view.getGoodsId() != row.id()) {
                return "缺少商品 " + row.id();
            }
            boolean unlocked = guildLevel >= row.requiredLevel();
            if (view.getName().isEmpty() || view.getItemId() != row.itemId() || view.getItemCount() != row.itemCount()
                    || view.getCostContribution() != row.cost() || view.getRequiredGuildLevel() != row.requiredLevel()
                    || view.getLimitPeriod() != row.limitPeriod() || view.getLimitCount() != row.limitCount()
                    || view.getMaxBuyCount() != row.maxBuyCount() || view.getUnlocked() != unlocked) {
                return "商品 " + row.id() + " 与配表不符：" + describe(view) + "，期望 " + row + " unlocked=" + unlocked;
            }
        }
        return "";
    }

    /**
     * 捐献回包的视图：op_id ≠ 0、donate_id 对、币种 / 花费 / 两项收益取配表行、created_ms ≠ 0（spec §3.2 第 14 步）。
     *
     * @return 空串 = 符合；否则是不符之处
     */
    static String donationViewProblem(GuildDonationView view, DonateRow row) {
        if (view.getOpId() == 0 || view.getDonateId() != row.id() || view.getCreatedMs() == 0) {
            return "视图 op_id / donate_id / created_ms 不对：" + describe(view) + "，期望 donate_id=" + row.id();
        }
        if (view.getCurrencyType() != row.currencyType() || view.getCostAmount() != row.cost()
                || view.getContributionGain() != row.contributionGain() || view.getFundsGain() != row.fundsGain()) {
            return "视图币种 / 花费 / 收益与配表行不符：" + describe(view) + "，期望 " + row;
        }
        return "";
    }

    /** 帮会的经济字段（号与金额按无符号十进制）。 */
    static String economyOf(GuildInfo guild) {
        return " guild_id=" + uid(guild.getGuildId()) + " level=" + Integer.toUnsignedString(guild.getLevel()) + " funds="
                + uid(guild.getFunds()) + " max_members=" + Integer.toUnsignedString(guild.getMaxMembers()) + " upgrade_cost_funds="
                + uid(guild.getUpgradeCostFunds());
    }

    static String describe(GuildDonationView view) {
        return "op=" + uid(view.getOpId()) + " donate=" + Integer.toUnsignedString(view.getDonateId()) + " " + view.getStatus()
                + " currency=" + view.getCurrencyType() + " cost=" + uid(view.getCostAmount()) + " gain=" + uid(view.getContributionGain())
                + " funds=" + uid(view.getFundsGain()) + " reason=" + view.getReasonTipId() + " created_ms=" + uid(view.getCreatedMs());
    }

    static String describe(GuildShopOrderView order) {
        return "op=" + uid(order.getOpId()) + " goods=" + Integer.toUnsignedString(order.getGoodsId()) + " count="
                + Integer.toUnsignedString(order.getCount()) + " " + order.getStatus() + " cost=" + uid(order.getCostContribution())
                + " reason=" + order.getReasonTipId() + " created_ms=" + uid(order.getCreatedMs());
    }

    static String describe(GuildDonateOptionView view) {
        return view.getDonateId() + "「" + view.getName() + "」currency=" + view.getCurrencyType() + " cost=" + uid(view.getCostAmount())
                + " gain=" + uid(view.getContributionGain()) + " funds=" + uid(view.getFundsGain()) + " used=" + view.getUsedToday()
                + "/" + view.getDailyLimit() + " min_level=" + view.getMinGuildLevel() + " unlocked=" + view.getUnlocked();
    }

    static String describe(GuildShopGoodsView view) {
        return view.getGoodsId() + "「" + view.getName() + "」category=" + view.getCategory() + " item=" + view.getItemId() + "×"
                + view.getItemCount() + " cost=" + uid(view.getCostContribution()) + " level=" + view.getRequiredGuildLevel() + " unlocked="
                + view.getUnlocked() + " limit=" + view.getLimitPeriod() + "/" + view.getLimitCount() + " used=" + view.getUsedCount()
                + " max_buy=" + view.getMaxBuyCount();
    }

    static String describe(DonateToGuildResponse response) {
        return GuildScenario.describeTip(response) + (response.hasDonation() ? " " + describe(response.getDonation()) : " 无捐献视图")
                + (response.hasGuild() ? economyOf(response.getGuild()) : " 无帮会");
    }

    static String describe(BuyGuildShopGoodsResponse response) {
        return GuildScenario.describeTip(response) + (response.hasOrder() ? " " + describe(response.getOrder()) : " 无订单视图")
                + " contribution_balance=" + uid(response.getContributionBalance());
    }

    static String describe(GetGuildDonateOptionsResponse options) {
        List<String> parts = new ArrayList<>();
        options.getOptionsList().forEach(o -> parts.add(describe(o)));
        return GuildScenario.describeTip(options) + " 帮贡 " + uid(options.getContributionTotal()) + " / "
                + uid(options.getContributionBalance()) + " 结算中 " + options.getPendingDonationsCount() + " 笔 最近结果 "
                + options.getRecentResultsCount() + " 条 选项 " + parts;
    }

    static String describe(GetGuildShopResponse shop) {
        List<String> parts = new ArrayList<>();
        for (GoodsRow row : CHECKED_GOODS) {
            parts.add(describe(goods(shop, row.id())));
        }
        return GuildScenario.describeTip(shop) + " 可用帮贡 " + uid(shop.getContributionBalance()) + " 共 " + shop.getGoodsCount()
                + " 件 待发放 " + shop.getPendingOrdersCount() + " 单 最近 " + shop.getRecentOrdersCount() + " 单 " + parts;
    }

    static String describeDonations(List<GuildDonationView> views) {
        List<String> parts = new ArrayList<>();
        views.forEach(v -> parts.add(describe(v)));
        return views.isEmpty() ? "空" : String.join("；", parts);
    }

    private static String describeOptionIds(List<GuildDonateOptionView> list) {
        List<String> ids = new ArrayList<>();
        list.forEach(o -> ids.add(Integer.toUnsignedString(o.getDonateId())));
        return ids.toString();
    }

    private static String uid(long value) {
        return Long.toUnsignedString(value);
    }
}
