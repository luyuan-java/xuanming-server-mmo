package com.game.guild.service;

import static com.game.guild.service.GuildServiceFixture.NOW;
import static com.game.guild.service.GuildServiceFixture.ZONE;
import static com.game.guild.service.GuildServiceFixture.d;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetStream;
import com.game.common.deadline.Deadline;
import com.game.common.time.GameDay;
import com.game.guild.asset.AssetOp;
import com.game.guild.asset.AssetOpProcessor;
import com.game.guild.asset.AssetOpProcessor.Processed;
import com.game.guild.asset.AssetOpStatus;
import com.game.guild.asset.DeliveryOrigin;
import com.game.guild.rules.GuildReject;
import com.game.guild.rules.GuildTip;
import com.game.guild.store.EconomyStore.OpState;
import com.game.guild.store.EconomyStore.Reserved;
import com.game.guild.store.EconomyStore.ShopReserved;
import com.game.guild.store.EconomyStore.ShopUsageKey;
import com.game.guild.store.EconomyStore.Upgraded;
import com.game.guild.store.GuildData;
import com.game.guild.store.TxOutcome;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.proto.guild.BuyGuildShopGoodsRequest;
import com.game.proto.guild.BuyGuildShopGoodsResponse;
import com.game.proto.guild.DonateToGuildRequest;
import com.game.proto.guild.DonateToGuildResponse;
import com.game.proto.guild.GetGuildDonateOptionsRequest;
import com.game.proto.guild.GetGuildDonateOptionsResponse;
import com.game.proto.guild.GetGuildShopRequest;
import com.game.proto.guild.GetGuildShopResponse;
import com.game.proto.guild.GuildAssetOrderStatus;
import com.game.proto.guild.GuildChangeKind;
import com.game.proto.guild.GuildShopGoodsView;
import com.game.proto.guild.UpgradeGuildRequest;
import com.game.proto.guild.UpgradeGuildResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 帮会经济五个 RPC 的完整顺序（照基线 economy_logic_test.go；guild-economy-spec §11.2 GuildEconomyServiceTest）：通道关闭 / 未知选项 / 超过单次上限都先于发号；
 * 前置坏路径；PENDING 不进 error_message；兑换 REJECTED 回 14027 且余额为退回后直读值；升级业务拒绝带 GuildInfo、not_member 不带；staleView 不推送；
 * 同步投递带 SYNC、预算不够跳过；回读失败按 PENDING。
 */
class GuildEconomyServiceTest {

    static final long G = 500;
    static final long ME = 1001;
    static final long OTHER = 1002;

    private final GuildServiceFixture f = new GuildServiceFixture();
    private final FakeChannel channel = new FakeChannel();
    private GuildEconomyService economy;

    /** 同步投递替身：记下每一行与 origin，按用例给的动作改回读状态。 */
    static final class FakeChannel implements AssetOpProcessor {
        final List<AssetOp> ops = Collections.synchronizedList(new ArrayList<>());
        final List<DeliveryOrigin> origins = Collections.synchronizedList(new ArrayList<>());
        final List<Long> budgets = Collections.synchronizedList(new ArrayList<>());
        volatile Consumer<AssetOp> onDeliver = op -> { };

        @Override
        public CompletableFuture<Processed> processOne(AssetOp op, DeliveryOrigin origin, long budgetMillis) {
            ops.add(op);
            origins.add(origin);
            budgets.add(budgetMillis);
            onDeliver.accept(op);
            return CompletableFuture.completedFuture(new Processed(null, false, AssetOpStatus.PENDING));
        }
    }

    @BeforeEach
    void setUp() {
        f.zones.put(ME, ZONE);
        f.names.put(ME, "甲");
        f.store.put(guildWith(1, 0, 0));
        economy = f.economy(channel, Runnable::run);
        f.economyStore.contribution(G, ME, 500, 440);
    }

    @AfterEach
    void close() {
        f.close();
    }

    /** 帮 G：ME 是帮主、OTHER 是成员；ME 的快照帮贡 {@code balance}。 */
    private static GuildData guildWith(int level, long funds, long balance) {
        return new GuildData(G, "帮" + G, ME, level, "", 1000, 30, ZONE, 0, funds,
                List.of(new GuildData.Member(ME, 3, 1000, 1000, balance, balance),
                        new GuildData.Member(OTHER, 0, 1000, 1000, 0, 0)));
    }

    private double economyCount(String rpc, String result) {
        return f.meters.get("xm.guild.economy.requests").tag("rpc", rpc).tag("result", result).counter().count();
    }

    private void noMint() {
        f.opIds = () -> {
            throw new AssertionError("不该发号");
        };
    }

    private DonateToGuildResponse donate(int donateId, Deadline deadline) {
        return economy.donateToGuild(ME, DonateToGuildRequest.newBuilder().setDonateId(donateId).build(), deadline).join();
    }

    private void reserveDonationOk() {
        f.economyStore.donate = in -> TxOutcome.ok(new Reserved(9, 1_699_000_000_000L));
    }

    // ================================================================ 捐献

    @Test
    void 通道关闭时捐献与兑换回14026_先于发号_不写行() {
        noMint();
        GuildEconomyService disabled = f.economy(null, Runnable::run);
        DonateToGuildResponse resp = disabled.donateToGuild(ME, DonateToGuildRequest.newBuilder().setDonateId(1).build(), d())
                .join();
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.ASSET_CHANNEL_DISABLED.proto());
        assertThat(resp.hasDonation()).isFalse();
        assertThat(resp.hasGuild()).isFalse();
        BuyGuildShopGoodsResponse buy = disabled.buyGuildShopGoods(ME, BuyGuildShopGoodsRequest.newBuilder().setGoodsId(101)
                .build(), d()).join();
        assertThat(buy.getErrorMessage()).isEqualTo(GuildTip.ASSET_CHANNEL_DISABLED.proto());
        assertThat(economyCount("donate", "disabled")).isEqualTo(1);
        assertThat(economyCount("buy_shop_goods", "disabled")).isEqualTo(1);
    }

    @Test
    void 未知捐献选项回14027_先于发号() {
        noMint();
        DonateToGuildResponse resp = donate(0, d());
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.DONATE_OPTION_NOT_FOUND.proto());
        assertThat(economyCount("donate", "not_found")).isEqualTo(1);
        assertThat(channel.ops).isEmpty();
    }

    @Test
    void 不在帮回14002_不发号() {
        noMint();
        f.store.guilds.clear();
        f.store.playerGuild.clear();
        assertThat(donate(1, d()).getErrorMessage()).isEqualTo(GuildTip.NOT_IN_ANY_GUILD.proto());
        assertThat(economyCount("donate", "not_member")).isEqualTo(1);
    }

    @Test
    void 快照里没有本人时以MySQL复核_自愈到真实的帮_矛盾时fail_closed() {
        // 先读一次：缓存里留下 ME → G 的映射
        assertThat(economy.getGuildDonateOptions(ME, GetGuildDonateOptionsRequest.getDefaultInstance(), d()).hasErrorMessage())
                .isFalse();
        // ME 已离开 G、入了 600；映射缓存仍指着 G（失效失败），G 的快照也重读成没有 ME
        f.store.put(new GuildData(600, "帮600", ME, 1, "", 1000, 30, ZONE, 0, 0,
                List.of(new GuildData.Member(ME, 3, 1000, 1000, 0, 0))));
        f.store.guilds.put(G, new GuildData(G, "帮" + G, OTHER, 1, "", 1000, 30, ZONE, 0, 0,
                List.of(new GuildData.Member(OTHER, 3, 1000, 1000, 0, 0))));
        f.invalidator.afterCommit(com.game.guild.cache.InvalidationOp.VERIFY_MAPPING, G, List.of(), d());
        f.economyStore.contribution(600, ME, 1, 2);
        GetGuildDonateOptionsResponse resp = economy.getGuildDonateOptions(ME, GetGuildDonateOptionsRequest.getDefaultInstance(),
                d());
        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getContributionBalance()).isEqualTo(2);

        // 两份 MySQL 数据互相矛盾（M5 说在 600，600 的成员里却没有他）：故障，不当成未入帮
        f.store.guilds.put(600L, new GuildData(600, "帮600", OTHER, 1, "", 1000, 30, ZONE, 0, 0,
                List.of(new GuildData.Member(OTHER, 3, 1000, 1000, 0, 0))));
        f.invalidator.afterCommit(com.game.guild.cache.InvalidationOp.VERIFY_MAPPING, 600, List.of(), d());
        assertThatThrownBy(() -> economy.getGuildDonateOptions(ME, GetGuildDonateOptionsRequest.getDefaultInstance(), d()))
                .isInstanceOf(IllegalStateException.class);
        assertThat(economyCount("get_donate_options", "error")).isEqualTo(1);
    }

    @Test
    void 等级不够回14029_先于发号() {
        noMint();
        f.economyTables.ruleMissing = false;
        // 三个选项都是 Lv1：拿商店的 103（Lv2）验证兑换的预判
        BuyGuildShopGoodsResponse resp = economy.buyGuildShopGoods(ME, BuyGuildShopGoodsRequest.newBuilder().setGoodsId(103)
                .build(), d()).join();
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.GUILD_LEVEL_TOO_LOW.proto());
        assertThat(economyCount("buy_shop_goods", "level")).isEqualTo(1);
    }

    @Test
    void GuildRule缺行是故障() {
        f.economyTables.ruleMissing = true;
        assertThatThrownBy(() -> donate(1, d())).isInstanceOf(GuildFaultException.class)
                .hasMessageContaining("GuildRule row 1 missing");
        assertThat(economyCount("donate", "error")).isEqualTo(1);
    }

    @Test
    void 发号失败回14008() {
        f.opIds = () -> 0;
        assertThat(donate(1, d()).getErrorMessage()).isEqualTo(GuildTip.ASSET_OP_ID_UNAVAILABLE.proto());
        f.opIds = () -> {
            throw new IllegalStateException("lease lost");
        };
        assertThat(donate(1, d()).getErrorMessage()).isEqualTo(GuildTip.ASSET_OP_ID_UNAVAILABLE.proto());
        assertThat(economyCount("donate", "id_unavailable")).isEqualTo(2);
    }

    @Test
    void 捐献_预留入参_同步投递带SYNC_APPLIED回包取配表数值() {
        reserveDonationOk();
        channel.onDeliver = op -> f.economyStore.opStates.put(op.opId(),
                new OpState(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, 0, 0));
        DonateToGuildResponse resp = donate(2, d());

        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getDonation().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED);
        assertThat(resp.getDonation().getDonateId()).isEqualTo(2);
        assertThat(resp.getDonation().getCostAmount()).isEqualTo(100_000);
        assertThat(resp.getDonation().getContributionGain()).isEqualTo(120);
        assertThat(resp.getDonation().getFundsGain()).isEqualTo(12_000);
        assertThat(resp.getDonation().getCreatedMs()).isEqualTo(NOW);
        assertThat(resp.getGuild().getGuildId()).isEqualTo(G);

        var in = f.economyStore.donations.getFirst();
        assertThat(in.opId()).isEqualTo(resp.getDonation().getOpId());
        assertThat(in.periodKey()).isEqualTo(GameDay.dayKey(NOW));
        assertThat(in.deadlineMs()).isEqualTo(NOW + 600_000);
        assertThat(in.leaseUntilMs()).isEqualTo(NOW + 10_000);
        assertThat(in.dailyLimit()).isEqualTo(2);
        assertThat(in.leaseToken()).isNotZero();

        AssetOp op = channel.ops.getFirst();
        assertThat(channel.origins).containsExactly(DeliveryOrigin.SYNC);
        assertThat(op.stream()).isEqualTo(AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE);
        assertThat(op.txType()).isEqualTo(24);
        assertThat(op.correlationId()).isEqualTo(op.opId());
        assertThat(op.seq()).isEqualTo(9);
        assertThat(op.deadlineMs()).isEqualTo(NOW + 600_000);
        assertThat(op.leaseToken()).isEqualTo(in.leaseToken());
        assertThat(op.bundle().getCurrencies(0).getAmount()).isEqualTo(100_000);
        assertThat(AssetBundle.newBuilder().addCurrencies(op.bundle().getCurrencies(0)).build().toByteString())
                .isEqualTo(in.payload());
        assertThat(channel.budgets.getFirst()).isBetween(300L, 2_500L);
        assertThat(economyCount("donate", "applied")).isEqualTo(1);
        // 同步终结不推送：推送由后台终结回调决定（服务自己不推）
        assertThat(f.pushes).isEmpty();
    }

    @Test
    void 捐献_PENDING不进error_message_视图带最近一次暂时原因() {
        reserveDonationOk();
        channel.onDeliver = op -> f.economyStore.opStates.put(op.opId(),
                new OpState(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING, 27002, 0));
        DonateToGuildResponse resp = donate(1, d());
        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getDonation().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING);
        assertThat(resp.getDonation().getReasonTipId()).isEqualTo(27002);
        assertThat(resp.hasGuild()).isTrue();
        assertThat(economyCount("donate", "pending")).isEqualTo(1);
    }

    @Test
    void 捐献_REJECTED货币不足回14025_其它拒绝回14027() {
        reserveDonationOk();
        channel.onDeliver = op -> f.economyStore.opStates.put(op.opId(),
                new OpState(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED, 27000, 27000));
        DonateToGuildResponse resp = donate(3, d());
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.CURRENCY_INSUFFICIENT.proto());
        assertThat(resp.getDonation().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED);
        assertThat(resp.getDonation().getReasonTipId()).isEqualTo(27000);

        channel.onDeliver = op -> f.economyStore.opStates.put(op.opId(),
                new OpState(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED, 27004, 27004));
        assertThat(donate(3, d()).getErrorMessage()).isEqualTo(GuildTip.ASSET_REJECTED.proto());
        // ABORTED 不填 error_message
        channel.onDeliver = op -> f.economyStore.opStates.put(op.opId(),
                new OpState(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED, 0, 0));
        assertThat(donate(3, d()).hasErrorMessage()).isFalse();
        assertThat(economyCount("donate", "rejected")).isEqualTo(2);
        assertThat(economyCount("donate", "aborted")).isEqualTo(1);
    }

    @Test
    void 捐献_事务拒绝映射_次数上限_守卫_写冲突_不在帮() {
        f.economyStore.donate = in -> TxOutcome.reject(GuildReject.DONATE_LIMIT);
        DonateToGuildResponse resp = donate(1, d());
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.DONATE_LIMIT.proto());
        assertThat(resp.hasDonation()).as("没写行的拒绝不带视图").isFalse();
        f.economyStore.donate = in -> TxOutcome.reject(GuildReject.TOO_MANY_PENDING);
        assertThat(donate(1, d()).getErrorMessage()).isEqualTo(GuildTip.TOO_MANY_PENDING.proto());
        f.economyStore.donate = in -> TxOutcome.reject(GuildReject.WRITE_CONFLICT);
        assertThat(donate(1, d()).getErrorMessage()).isEqualTo(GuildTip.WRITE_CONFLICT.proto());
        f.economyStore.donate = in -> TxOutcome.reject(GuildReject.NOT_MEMBER);
        assertThat(donate(1, d()).getErrorMessage()).isEqualTo(GuildTip.NOT_A_MEMBER.proto());
        assertThat(channel.ops).isEmpty();
        assertThat(economyCount("donate", "limit")).isEqualTo(1);
        assertThat(economyCount("donate", "pending_guard")).isEqualTo(1);
        assertThat(economyCount("donate", "busy_retry")).isEqualTo(1);
        assertThat(economyCount("donate", "not_member")).isEqualTo(1);
    }

    @Test
    void 剩余预算不够就跳过同步投递_计sync_skipped_回读照常() {
        reserveDonationOk();
        f.economyStore.opStates.put(f.nextOpId.get() + 1, new OpState(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING, 0, 0));
        DonateToGuildResponse resp = donate(1, Deadline.after(1_100));
        assertThat(channel.ops).isEmpty();
        assertThat(resp.getDonation().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING);
        assertThat(f.meters.get("xm.guild.asset.sync.skipped").tag("kind", "donate").counter().count()).isEqualTo(1);
    }

    @Test
    void 回读失败或行不见了都按PENDING展示_不报故障() {
        reserveDonationOk();
        f.economyStore.opStateFailure = new Deadline.DependencyException("mysql down");
        DonateToGuildResponse resp = donate(1, d());
        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getDonation().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING);
        f.economyStore.opStateFailure = null;
        assertThat(donate(1, d()).getDonation().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING);
    }

    @Test
    void 投递之后回工作池时队列满了_不回读直接按PENDING回包() {
        reserveDonationOk();
        GuildEconomyService full = f.economy(channel, task -> {
            throw new RejectedExecutionException("full");
        });
        DonateToGuildResponse resp = full.donateToGuild(ME, DonateToGuildRequest.newBuilder().setDonateId(1).build(), d()).join();
        assertThat(resp.getDonation().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING);
        assertThat(resp.hasGuild()).isFalse();
        assertThat(f.journal).noneMatch(j -> j.startsWith("economy.opState"));
    }

    // ================================================================ 兑换

    @Test
    void 兑换超过单次上限回14030_先于发号() {
        noMint();
        f.store.put(guildWith(4, 0, 10_000));
        BuyGuildShopGoodsResponse resp = economy.buyGuildShopGoods(ME, BuyGuildShopGoodsRequest.newBuilder().setGoodsId(202)
                .setCount(2).build(), d()).join();
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.COUNT_EXCEEDS_MAX_BUY.proto());
        assertThat(economyCount("buy_shop_goods", "limit")).isEqualTo(1);
    }

    @Test
    void 兑换_快照帮贡不够先直读确认_确实不够回14031_不在帮回14002() {
        noMint();
        f.economyStore.contribution(G, ME, 10, 10);
        BuyGuildShopGoodsResponse resp = economy.buyGuildShopGoods(ME, BuyGuildShopGoodsRequest.newBuilder().setGoodsId(101)
                .build(), d()).join();
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.CONTRIBUTION_INSUFFICIENT.proto());
        f.economyStore.contributions.clear();
        resp = economy.buyGuildShopGoods(ME, BuyGuildShopGoodsRequest.newBuilder().setGoodsId(101).build(), d()).join();
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.NOT_A_MEMBER.proto());
    }

    @Test
    void 兑换_count为0按1份_REJECTED回14027且余额为退回后的直读值() {
        f.store.put(guildWith(1, 0, 440));
        f.economyStore.shop = in -> TxOutcome.ok(new ShopReserved(G, 3, 1_699_000_000_000L, 410));
        channel.onDeliver = op -> {
            f.economyStore.opStates.put(op.opId(), new OpState(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED, 27004, 27004));
            f.economyStore.contribution(G, ME, 500, 440);
        };
        BuyGuildShopGoodsResponse resp = economy.buyGuildShopGoods(ME, BuyGuildShopGoodsRequest.newBuilder().setGoodsId(101)
                .build(), d()).join();
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.ASSET_REJECTED.proto());
        assertThat(resp.getOrder().getCount()).isEqualTo(1);
        assertThat(resp.getOrder().getCostContribution()).isEqualTo(30);
        assertThat(resp.getContributionBalance()).isEqualTo(440);

        var in = f.economyStore.shopOrders.getFirst();
        assertThat(in.count()).isEqualTo(1);
        assertThat(in.cost()).isEqualTo(30);
        assertThat(in.periodKey()).isEqualTo(GameDay.dayKey(NOW));
        assertThat(in.limitCount()).isEqualTo(10);
        AssetOp op = channel.ops.getFirst();
        assertThat(op.stream()).isEqualTo(AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE);
        assertThat(op.txType()).isEqualTo(25);
        assertThat(op.deadlineMs()).as("兑换永不中止").isZero();
        assertThat(op.bundle().getItems(0).getConfigId()).isEqualTo(15);
        assertThat(op.bundle().getItems(0).getCount()).isEqualTo(5);
        assertThat(resp.getOrder().getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED);
    }

    @Test
    void 兑换_APPLIED回预留后的余额_没有GuildInfo() {
        f.store.put(guildWith(1, 0, 440));
        f.economyStore.shop = in -> TxOutcome.ok(new ShopReserved(G, 3, 1_699_000_000_000L, 380));
        channel.onDeliver = op -> f.economyStore.opStates.put(op.opId(),
                new OpState(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, 0, 0));
        BuyGuildShopGoodsResponse resp = economy.buyGuildShopGoods(ME, BuyGuildShopGoodsRequest.newBuilder().setGoodsId(101)
                .setCount(2).build(), d()).join();
        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getContributionBalance()).isEqualTo(380);
        assertThat(resp.getOrder().getCostContribution()).isEqualTo(60);
        assertThat(economyCount("buy_shop_goods", "applied")).isEqualTo(1);
    }

    @Test
    void 兑换物品缺行是故障() {
        f.store.put(guildWith(1, 0, 440));
        f.economyTables.missingItems.add(15);
        assertThatThrownBy(() -> economy.buyGuildShopGoods(ME, BuyGuildShopGoodsRequest.newBuilder().setGoodsId(101).build(),
                d())).isInstanceOf(GuildFaultException.class).hasMessageContaining("Item row 15 of GuildShop[101] missing");
    }

    // ================================================================ 升级

    @Test
    void 升级成功推LEVEL_UP给其他成员_回最新GuildInfo() {
        f.economyStore.upgrade = (expected, levels) -> {
            f.store.put(guildWith(2, 0, 0));
            return TxOutcome.ok(new Upgraded(G, ME, true, 2, List.of(ME, OTHER), false));
        };
        UpgradeGuildResponse resp = economy.upgradeGuild(ME, UpgradeGuildRequest.newBuilder().setExpectedLevel(1).build(), d());
        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getGuild().getLevel()).isEqualTo(2);
        var push = f.onlyPush();
        assertThat(push.change().getKind()).isEqualTo(GuildChangeKind.GUILD_CHANGE_KIND_LEVEL_UP);
        assertThat(push.change().getActorPlayerId()).isEqualTo(ME);
        assertThat(push.recipients()).containsExactly(OTHER);
        assertThat(economyCount("upgrade", "ok")).isEqualTo(1);
    }

    @Test
    void 升级_expected_level过期不扣钱不推送_回unchanged() {
        f.economyStore.upgrade = (expected, levels) -> TxOutcome.ok(new Upgraded(G, ME, false, 2, List.of(), true));
        UpgradeGuildResponse resp = economy.upgradeGuild(ME, UpgradeGuildRequest.newBuilder().setExpectedLevel(1).build(), d());
        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.hasGuild()).isTrue();
        assertThat(f.pushes).isEmpty();
        assertThat(economyCount("upgrade", "unchanged")).isEqualTo(1);
    }

    @Test
    void 升级业务拒绝也带GuildInfo_不在帮不带_配表缺行是故障() {
        f.economyStore.upgrade = (expected, levels) -> TxOutcome.reject(GuildReject.FUNDS_INSUFFICIENT);
        UpgradeGuildResponse resp = economy.upgradeGuild(ME, UpgradeGuildRequest.newBuilder().setExpectedLevel(1).build(), d());
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.FUNDS_INSUFFICIENT.proto());
        assertThat(resp.hasGuild()).isTrue();
        f.economyStore.upgrade = (expected, levels) -> TxOutcome.reject(GuildReject.RANK_TOO_LOW);
        resp = economy.upgradeGuild(ME, UpgradeGuildRequest.getDefaultInstance(), d());
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.RANK_TOO_LOW.proto());
        assertThat(resp.hasGuild()).isTrue();
        f.economyStore.upgrade = (expected, levels) -> TxOutcome.reject(GuildReject.GUILD_GONE);
        resp = economy.upgradeGuild(ME, UpgradeGuildRequest.getDefaultInstance(), d());
        assertThat(resp.getErrorMessage()).isEqualTo(GuildTip.GUILD_NOT_FOUND.proto());
        assertThat(resp.hasGuild()).isFalse();
        f.economyStore.upgrade = (expected, levels) -> TxOutcome.reject(GuildReject.LEVEL_CONFIG_MISSING);
        assertThatThrownBy(() -> economy.upgradeGuild(ME, UpgradeGuildRequest.getDefaultInstance(), d()))
                .isInstanceOf(GuildFaultException.class);
        assertThat(economyCount("upgrade", "insufficient")).isEqualTo(1);
        assertThat(economyCount("upgrade", "rank")).isEqualTo(1);
        assertThat(economyCount("upgrade", "not_member")).isEqualTo(1);
        assertThat(economyCount("upgrade", "error")).isEqualTo(1);
    }

    @Test
    void 升级不经资产通道_通道关闭照常() {
        f.economyStore.upgrade = (expected, levels) -> TxOutcome.ok(new Upgraded(G, ME, false, 1, List.of(), false));
        UpgradeGuildResponse resp = f.economy(null, Runnable::run).upgradeGuild(ME, UpgradeGuildRequest.getDefaultInstance(),
                d());
        assertThat(resp.hasErrorMessage()).isFalse();
    }

    // ================================================================ 两个读页

    private static GuildAssetOpRow row(long opId, GuildAssetOpKind kind, long guildId, GuildAssetOpStatus status, long updatedMs) {
        return GuildAssetOpRow.newBuilder().setOpId(opId).setKind(kind).setGuildId(guildId).setStatus(status)
                .setUpdatedMs(updatedMs).setRefId(1).setRefCount(1).setCreatedMs(updatedMs).build();
    }

    @Test
    void 捐献页_选项升序_今日用量_只列本帮捐献_最近结果十分钟内终态() {
        f.economyStore.donateUsage.put(2, 1);
        f.economyStore.pending.put(1, List.of(
                row(1, GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, G, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING, NOW),
                row(2, GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, 999, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING, NOW)));
        f.economyStore.recent.put(1, List.of(
                row(5, GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, G, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, NOW - 1),
                row(4, GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, G, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING, NOW),
                row(3, GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE, G, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED,
                        NOW - 700_000)));
        GetGuildDonateOptionsResponse resp = economy.getGuildDonateOptions(ME, GetGuildDonateOptionsRequest.getDefaultInstance(),
                d());
        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getOptionsList()).extracting(o -> o.getDonateId()).containsExactly(1, 2, 3);
        assertThat(resp.getOptions(1).getUsedToday()).isEqualTo(1);
        assertThat(resp.getOptionsList()).allMatch(o -> o.getUnlocked());
        assertThat(resp.getPendingDonationsList()).extracting(v -> v.getOpId()).containsExactly(1L);
        assertThat(resp.getRecentResultsList()).extracting(v -> v.getOpId()).containsExactly(5L);
        assertThat(resp.getContributionTotal()).isEqualTo(500);
        assertThat(resp.getContributionBalance()).isEqualTo(440);
        assertThat(resp.getNextDailyResetMs()).isEqualTo(GameDay.nextDailyResetMillis(NOW));
        assertThat(f.economyStore.lastDayKey).isEqualTo(GameDay.dayKey(NOW));
        assertThat(economyCount("get_donate_options", "ok")).isEqualTo(1);
    }

    @Test
    void 读页_成员行不在回14002_读失败是故障() {
        f.economyStore.contributions.clear();
        assertThat(economy.getGuildDonateOptions(ME, GetGuildDonateOptionsRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_A_MEMBER.proto());
        assertThat(economy.getGuildShop(ME, GetGuildShopRequest.getDefaultInstance(), d()).getErrorMessage())
                .isEqualTo(GuildTip.NOT_A_MEMBER.proto());
        f.economyStore.contribution(G, ME, 1, 1);
        f.economyStore.readFailure = new Deadline.DependencyException("down");
        assertThatThrownBy(() -> economy.getGuildShop(ME, GetGuildShopRequest.getDefaultInstance(), d()))
                .isInstanceOf(Deadline.DependencyException.class);
        assertThat(economyCount("get_shop", "error")).isEqualTo(1);
    }

    @Test
    void 商店页_按分类与id升序_单次上限_限购用量_只列兑换_物品缺行不失败() {
        f.economyTables.missingItems.add(23);
        f.economyStore.shopUsage.put(new ShopUsageKey(101, GameDay.dayKey(NOW)), 4);
        f.economyStore.shopUsage.put(new ShopUsageKey(104, GameDay.weekKey(NOW)), 2);
        f.economyStore.pending.put(2, List.of(
                row(1, GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP, 999, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING, NOW),
                row(2, GuildAssetOpKind.GUILD_ASSET_OP_KIND_ACTIVITY_REWARD, G, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING,
                        NOW)));
        GetGuildShopResponse resp = economy.getGuildShop(ME, GetGuildShopRequest.getDefaultInstance(), d());
        assertThat(resp.hasErrorMessage()).isFalse();
        assertThat(resp.getGoodsList()).extracting(GuildShopGoodsView::getGoodsId)
                .containsExactly(101, 102, 103, 104, 201, 202, 203, 204, 301, 302, 303);
        Map<Integer, GuildShopGoodsView> byId = new java.util.HashMap<>();
        resp.getGoodsList().forEach(g -> byId.put(g.getGoodsId(), g));
        assertThat(byId.get(202).getMaxBuyCount()).isEqualTo(1);
        assertThat(byId.get(204).getMaxBuyCount()).isEqualTo(1);
        assertThat(byId.get(101).getMaxBuyCount()).isEqualTo(20);
        assertThat(byId.get(303).getMaxBuyCount()).as("物品缺行：显示为不可兑换").isZero();
        assertThat(byId.get(101).getUsedCount()).isEqualTo(4);
        assertThat(byId.get(104).getUsedCount()).isEqualTo(2);
        assertThat(byId.get(303).getUsedCount()).as("不限购").isZero();
        assertThat(byId.get(101).getUnlocked()).isTrue();
        assertThat(byId.get(103).getUnlocked()).isFalse();
        assertThat(resp.getPendingOrdersList()).extracting(v -> v.getOpId()).containsExactly(1L);
        assertThat(resp.getContributionBalance()).isEqualTo(440);
        assertThat(resp.getNextWeeklyResetMs()).isEqualTo(GameDay.nextWeeklyResetMillis(NOW));
    }
}
