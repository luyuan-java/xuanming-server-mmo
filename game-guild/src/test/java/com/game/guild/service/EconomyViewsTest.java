package com.game.guild.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetItem;
import com.game.common.time.GameDay;
import com.game.guild.metrics.GuildMetrics.EconomyResult;
import com.game.guild.rules.GuildTip;
import com.game.guild.service.EconomyViews.OrderView;
import com.game.guild.store.EconomyStore.ShopUsageKey;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.proto.guild.GuildAssetOrderStatus;
import com.game.proto.guild.GuildDonationView;
import com.game.proto.guild.GuildShopOrderView;
import com.game.table.GuildShopTable;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 视图与映射的纯函数（照基线 economy_logic_test.go:949-1197；guild-economy-spec §11.1「视图与映射」）：orderViewOf、resultOfOrder、donationRejectTip、
 * donationViewOf（payload 解不开只缺两字段）、shopOrderViewOf、recentResults（窗口、上限 5、keep）、shopUsedCount。
 */
class EconomyViewsTest {

    static final long NOW = 1_700_000_000_000L;

    @Test
    void 库内状态到视图状态_显式映射_PENDING取last_reason终态取reason_tip_id() {
        assertThat(EconomyViews.orderViewOf(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING, 27001, 9))
                .isEqualTo(new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING, 27001));
        assertThat(EconomyViews.orderViewOf(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED, 27001, 0))
                .isEqualTo(new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED, 0));
        assertThat(EconomyViews.orderViewOf(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED, 1, 27000))
                .isEqualTo(new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED, 27000));
        assertThat(EconomyViews.orderViewOf(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED, 1, 0).status())
                .isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_ABORTED);
        assertThat(EconomyViews.orderViewOf(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL, 27007, 0).status())
                .isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED_PARTIAL);
        assertThat(EconomyViews.orderViewOf(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_UNSPECIFIED, 5, 6))
                .isEqualTo(new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_UNSPECIFIED, 0));
        assertThat(EconomyViews.orderViewOf(GuildAssetOpStatus.UNRECOGNIZED, 5, 6).status())
                .isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_UNSPECIFIED);
    }

    @Test
    void 写RPC按视图状态计结果_未知值error() {
        assertThat(EconomyViews.resultOfOrder(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING))
                .isEqualTo(EconomyResult.PENDING);
        assertThat(EconomyViews.resultOfOrder(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED))
                .isEqualTo(EconomyResult.APPLIED);
        assertThat(EconomyViews.resultOfOrder(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED))
                .isEqualTo(EconomyResult.REJECTED);
        assertThat(EconomyViews.resultOfOrder(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_ABORTED))
                .isEqualTo(EconomyResult.ABORTED);
        assertThat(EconomyViews.resultOfOrder(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED_PARTIAL))
                .isEqualTo(EconomyResult.APPLIED_PARTIAL);
        assertThat(EconomyViews.resultOfOrder(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_UNSPECIFIED))
                .isEqualTo(EconomyResult.ERROR);
    }

    @Test
    void 捐献拒绝tip只在REJECTED时填_货币不足14025_其它14027() {
        assertThat(EconomyViews.donationRejectTip(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED, 27000))
                .isEqualTo(GuildTip.CURRENCY_INSUFFICIENT);
        assertThat(EconomyViews.donationRejectTip(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED, 27005))
                .isEqualTo(GuildTip.ASSET_REJECTED);
        assertThat(EconomyViews.donationRejectTip(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED, 0))
                .isEqualTo(GuildTip.ASSET_REJECTED);
        for (GuildAssetOrderStatus other : List.of(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING,
                GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED, GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_ABORTED,
                GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED_PARTIAL)) {
            assertThat(EconomyViews.donationRejectTip(other, 27000)).as(other.name()).isNull();
        }
    }

    private static GuildAssetOpRow.Builder row(long opId) {
        return GuildAssetOpRow.newBuilder().setOpId(opId).setRefId(2).setRefCount(3).setContributionDelta(120)
                .setFundsDelta(12_000).setCreatedMs(NOW - 5).setUpdatedMs(NOW)
                .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING).setLastReason(27002);
    }

    @Test
    void 捐献视图_货币从payload解_解不开只缺两个字段() {
        ByteString payload = AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(1).setAmount(100))
                .build().toByteString();
        GuildDonationView view = EconomyViews.donationViewOf(row(9).setPayload(payload).build());
        assertThat(view.getOpId()).isEqualTo(9);
        assertThat(view.getDonateId()).isEqualTo(2);
        assertThat(view.getCurrencyType()).isEqualTo(1);
        assertThat(view.getCostAmount()).isEqualTo(100);
        assertThat(view.getContributionGain()).isEqualTo(120);
        assertThat(view.getFundsGain()).isEqualTo(12_000);
        assertThat(view.getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING);
        assertThat(view.getReasonTipId()).isEqualTo(27002);
        assertThat(view.getCreatedMs()).isEqualTo(NOW - 5);

        // 两笔货币 / 物品包 / 坏字节 / 空：都视为解不开
        for (ByteString bad : List.of(
                AssetBundle.newBuilder().addCurrencies(AssetCurrency.getDefaultInstance())
                        .addCurrencies(AssetCurrency.getDefaultInstance()).build().toByteString(),
                AssetBundle.newBuilder().addItems(AssetItem.newBuilder().setConfigId(1).setCount(1)).build().toByteString(),
                ByteString.copyFrom(new byte[] {(byte) 0xFF, 0x01}), ByteString.EMPTY)) {
            GuildDonationView v = EconomyViews.donationViewOf(row(9).setPayload(bad).build());
            assertThat(v.getCostAmount()).isZero();
            assertThat(v.getFundsGain()).isEqualTo(12_000);
        }
    }

    @Test
    void 兑换视图_goods_count_cost取行上的列() {
        GuildShopOrderView view = EconomyViews.shopOrderViewOf(row(9).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED)
                .setReasonTipId(27001).build());
        assertThat(view.getGoodsId()).isEqualTo(2);
        assertThat(view.getCount()).isEqualTo(3);
        assertThat(view.getCostContribution()).isEqualTo(120);
        assertThat(view.getStatus()).isEqualTo(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED);
        assertThat(view.getReasonTipId()).isEqualTo(27001);
    }

    @Test
    void 最近结果_十分钟内终态_至多5条_按扫描顺序_keep过滤() {
        List<GuildAssetOpRow> rows = new ArrayList<>();
        rows.add(row(10).build()); // PENDING：跳过
        for (long id = 9; id >= 1; id--) {
            rows.add(row(id).setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED)
                    .setKind(id == 8 ? GuildAssetOpKind.GUILD_ASSET_OP_KIND_SHOP : GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE)
                    .setUpdatedMs(id == 7 ? NOW - 600_001 : NOW - 600_000).build());
        }
        List<GuildAssetOpRow> recent = EconomyViews.recentResults(rows, NOW,
                r -> r.getKind() == GuildAssetOpKind.GUILD_ASSET_OP_KIND_DONATE);
        assertThat(recent).extracting(GuildAssetOpRow::getOpId).containsExactly(9L, 6L, 5L, 4L, 3L);
        // atMs ≤ 窗口：下界取 0
        assertThat(EconomyViews.recentResults(rows, 5, r -> true)).hasSize(5);
    }

    @Test
    void 商品已用份数按当前周期键取_不限购为0() {
        GuildShopTable daily = GuildShopTable.newBuilder().setId(101).setLimitPeriod(GameDay.PERIOD_DAILY).setLimitCount(10).build();
        GuildShopTable weekly = GuildShopTable.newBuilder().setId(104).setLimitPeriod(GameDay.PERIOD_WEEKLY).setLimitCount(5)
                .build();
        GuildShopTable unlimited = GuildShopTable.newBuilder().setId(303).setLimitPeriod(GameDay.PERIOD_NONE).build();
        GuildShopTable invalid = GuildShopTable.newBuilder().setId(999).setLimitPeriod(7).build();
        Map<ShopUsageKey, Integer> usage = Map.of(new ShopUsageKey(101, GameDay.dayKey(NOW)), 4,
                new ShopUsageKey(101, GameDay.dayKey(NOW - 86_400_000L)), 9, new ShopUsageKey(104, GameDay.weekKey(NOW)), 2);
        assertThat(EconomyViews.shopUsedCount(daily, usage, NOW)).isEqualTo(4);
        assertThat(EconomyViews.shopUsedCount(weekly, usage, NOW)).isEqualTo(2);
        assertThat(EconomyViews.shopUsedCount(unlimited, usage, NOW)).isZero();
        assertThat(EconomyViews.shopUsedCount(invalid, usage, NOW)).isZero();
    }
}
