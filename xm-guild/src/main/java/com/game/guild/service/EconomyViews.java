package com.game.guild.service;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.common.time.GameDay;
import com.game.guild.metrics.GuildMetrics.EconomyResult;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildTip;
import com.game.guild.asset.AssetOpDecisions;
import com.game.guild.store.EconomyStore.ShopUsageKey;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.proto.guild.GuildAssetOrderStatus;
import com.game.proto.guild.GuildDonateOptionView;
import com.game.proto.guild.GuildDonationView;
import com.game.proto.guild.GuildShopGoodsView;
import com.game.proto.guild.GuildShopOrderView;
import com.game.table.GuildDonateTable;
import com.game.table.GuildShopTable;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会经济的视图装配与结果映射（基线 economy_logic.go:458-663 的 orderViewOf / resultOfOrder / donationRejectTip / donationViewOf /
 * shopOrderViewOf / recentResults / shopUsedCount；guild-economy-spec §0.3、§3.0「视图与回读」）。全是纯函数（只有「payload 解不开」记一条 ERROR），
 * 任意线程可调；数值字段一律按无符号位模式原样搬运。
 */
public final class EconomyViews {

    private static final Logger log = LoggerFactory.getLogger(EconomyViews.class);

    /** 客户端视图状态 + 视图里的原因码（PENDING 取 last_reason，终态取 reason_tip_id）。 */
    public record OrderView(GuildAssetOrderStatus status, int reason) {
    }

    private EconomyViews() {
    }

    /**
     * 库内状态 → 客户端视图状态（orderViewOf，economy_logic.go:460-480）：<b>显式 switch</b>，两个枚举数值恰好相同也不直接转型
     * （库枚举将来加值时客户端枚举未必跟着加；proto 注释说「按值直接转换」，以代码为准）。PENDING → (PENDING, last_reason)；四个终态 →
     * (对应值, reason_tip_id)；其它 → (UNSPECIFIED, 0)，调用方记 ERROR。
     */
    public static OrderView orderViewOf(GuildAssetOpStatus status, int lastReason, int reasonTipId) {
        if (status == null) {
            return new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_UNSPECIFIED, 0);
        }
        return switch (status) {
            case GUILD_ASSET_OP_STATUS_PENDING -> new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_PENDING, lastReason);
            case GUILD_ASSET_OP_STATUS_APPLIED -> new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED, reasonTipId);
            case GUILD_ASSET_OP_STATUS_REJECTED ->
                    new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED, reasonTipId);
            case GUILD_ASSET_OP_STATUS_ABORTED -> new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_ABORTED, reasonTipId);
            case GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL ->
                    new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_APPLIED_PARTIAL, reasonTipId);
            default -> new OrderView(GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_UNSPECIFIED, 0);
        };
    }

    /** 进入「最近结果」的四种终态（isTerminalOpStatus，economy_logic.go:482-493）。 */
    public static boolean isTerminal(GuildAssetOpStatus status) {
        return status == GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED
                || status == GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_REJECTED
                || status == GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_ABORTED
                || status == GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_APPLIED_PARTIAL;
    }

    /** 写 RPC 成功路径按视图状态计指标（resultOfOrder，economy_logic.go:495-511）：未知值 error。 */
    public static EconomyResult resultOfOrder(GuildAssetOrderStatus order) {
        if (order == null) {
            return EconomyResult.ERROR;
        }
        return switch (order) {
            case GUILD_ASSET_ORDER_STATUS_PENDING -> EconomyResult.PENDING;
            case GUILD_ASSET_ORDER_STATUS_APPLIED -> EconomyResult.APPLIED;
            case GUILD_ASSET_ORDER_STATUS_REJECTED -> EconomyResult.REJECTED;
            case GUILD_ASSET_ORDER_STATUS_ABORTED -> EconomyResult.ABORTED;
            case GUILD_ASSET_ORDER_STATUS_APPLIED_PARTIAL -> EconomyResult.APPLIED_PARTIAL;
            default -> EconomyResult.ERROR;
        };
    }

    /**
     * 捐献被 scene 永久拒绝时的 error_message（donationRejectTip，economy_logic.go:513-523）：只在 REJECTED 时填；货币不足（27000）→ 14025，
     * 其余 → 14027。PENDING / APPLIED / ABORTED / APPLIED_PARTIAL 都不填（PENDING 用视图表达，不能塞进 error_message）。
     *
     * @return null = 不填
     */
    public static GuildTip donationRejectTip(GuildAssetOrderStatus order, int reason) {
        if (order != GuildAssetOrderStatus.GUILD_ASSET_ORDER_STATUS_REJECTED) {
            return null;
        }
        return reason == AssetOpDecisions.REASON_CURRENCY_INSUFFICIENT ? GuildTip.CURRENCY_INSUFFICIENT : GuildTip.ASSET_REJECTED;
    }

    /**
     * 一行捐献指令 → 视图（donationViewOf，economy_logic.go:547-567）。货币种类与数额只在 payload 里（指令行不另存）：解不开时只缺这两个
     * 展示字段、记 ERROR，不让整页失败——状态与收益仍然是对的。
     */
    public static GuildDonationView donationViewOf(GuildAssetOpRow row) {
        OrderView view = orderViewOf(row.getStatus(), row.getLastReason(), row.getReasonTipId());
        GuildDonationView.Builder b = GuildDonationView.newBuilder()
                .setOpId(row.getOpId()).setDonateId(row.getRefId()).setStatus(view.status())
                .setContributionGain(row.getContributionDelta()).setFundsGain(row.getFundsDelta())
                .setReasonTipId(view.reason()).setCreatedMs(row.getCreatedMs());
        Optional<AssetCurrency> currency = donationCurrencyOf(row.getPayload());
        if (currency.isPresent()) {
            b.setCurrencyType(currency.get().getCurrencyType()).setCostAmount(currency.get().getAmount());
        } else {
            log.error("[GuildEconomy] donation payload undecodable op_id={}", Long.toUnsignedString(row.getOpId()));
        }
        return b.build();
    }

    /** 捐献 payload 里那唯一一笔货币（donationCurrencyOf，economy_logic.go:569-582）：形状不是「恰好一笔货币」一律视为解不开。 */
    public static Optional<AssetCurrency> donationCurrencyOf(ByteString payload) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        AssetBundle bundle;
        try {
            bundle = AssetBundle.parseFrom(payload);
        } catch (InvalidProtocolBufferException e) {
            return Optional.empty();
        }
        if (bundle.getCurrenciesCount() != 1) {
            return Optional.empty();
        }
        return Optional.of(bundle.getCurrencies(0));
    }

    /** 一行兑换指令 → 视图（shopOrderViewOf，economy_logic.go:597-609）：goods_id = ref_id，份数 = ref_count，总帮贡 = contribution_delta。 */
    public static GuildShopOrderView shopOrderViewOf(GuildAssetOpRow row) {
        OrderView view = orderViewOf(row.getStatus(), row.getLastReason(), row.getReasonTipId());
        return GuildShopOrderView.newBuilder()
                .setOpId(row.getOpId()).setGoodsId(row.getRefId()).setCount(row.getRefCount()).setStatus(view.status())
                .setCostContribution(row.getContributionDelta()).setReasonTipId(view.reason())
                .setCreatedMs(row.getCreatedMs()).build();
    }

    /**
     * 从最近的行（按 (stream_epoch, seq) 倒序，新的在前）里挑出 10 分钟内进入终态、且 {@code keep} 认可的行，至多 5 条（recentResults，
     * economy_logic.go:611-632）。终态行的 updated_ms 就是终结时刻，按它判「最近」；{@code atMs ≤ 600000} 时下界取 0。
     */
    public static List<GuildAssetOpRow> recentResults(List<GuildAssetOpRow> rows, long atMs, Predicate<GuildAssetOpRow> keep) {
        long cutoff = Long.compareUnsigned(atMs, GuildLimits.RECENT_RESULT_WINDOW_MS) > 0
                ? atMs - GuildLimits.RECENT_RESULT_WINDOW_MS : 0;
        List<GuildAssetOpRow> out = new ArrayList<>(GuildLimits.RECENT_RESULT_KEEP);
        for (GuildAssetOpRow row : rows) {
            if (out.size() == GuildLimits.RECENT_RESULT_KEEP) {
                break;
            }
            if (!isTerminal(row.getStatus()) || Long.compareUnsigned(row.getUpdatedMs(), cutoff) < 0 || !keep.test(row)) {
                continue;
            }
            out.add(row);
        }
        return out;
    }

    /**
     * 一件商品在 now 所在周期里已兑换的份数（含待发放；shopUsedCount，economy_logic.go:584-595）。周期键与兑换预留用同一个函数算
     * （一个请求只取一次 now）：展示与占用各算各的，跨 05:00 / 周一切点时会出现「页面说还能买、点下去说限购已满」。不限购（键 0）或非法周期回 0。
     */
    public static int shopUsedCount(GuildShopTable row, Map<ShopUsageKey, Integer> usage, long nowMs) {
        OptionalInt periodKey = GameDay.periodKey(row.getLimitPeriod(), nowMs);
        if (periodKey.isEmpty() || periodKey.getAsInt() == 0) {
            return 0;
        }
        Integer used = usage.get(new ShopUsageKey(row.getId(), periodKey.getAsInt()));
        return used == null ? 0 : used;
    }

    /** 捐献页的一个选项（economy_logic.go:1099-1112）：{@code unlocked = 帮会等级 ≥ min_guild_level}（缓存快照的等级，无符号比较）。 */
    public static GuildDonateOptionView donateOptionView(GuildDonateTable row, int usedToday, int guildLevel) {
        return GuildDonateOptionView.newBuilder()
                .setDonateId(row.getId()).setName(row.getName()).setCurrencyType(row.getCurrencyType())
                .setCostAmount(row.getCostAmount()).setContributionGain(row.getContributionGain())
                .setFundsGain(row.getFundsGain()).setDailyLimit(row.getDailyLimit()).setUsedToday(usedToday)
                .setMinGuildLevel(row.getMinGuildLevel())
                .setUnlocked(Integer.compareUnsigned(guildLevel, row.getMinGuildLevel()) >= 0)
                .build();
    }

    /** 商店的一件商品（economy_logic.go:1177-1197）：{@code unlocked = 帮会等级 ≥ required_guild_level}；单次上限由调用方按 Item 堆叠算好。 */
    public static GuildShopGoodsView shopGoodsView(GuildShopTable row, int usedCount, int maxBuyCount, int guildLevel) {
        return GuildShopGoodsView.newBuilder()
                .setGoodsId(row.getId()).setName(row.getName()).setCategory(row.getCategory()).setItemId(row.getItemId())
                .setItemCount(row.getItemCount()).setCostContribution(row.getCostContribution())
                .setRequiredGuildLevel(row.getRequiredGuildLevel())
                .setUnlocked(Integer.compareUnsigned(guildLevel, row.getRequiredGuildLevel()) >= 0)
                .setLimitPeriod(row.getLimitPeriod()).setLimitCount(row.getLimitCount()).setUsedCount(usedCount)
                .setMaxBuyCount(maxBuyCount)
                .build();
    }
}
