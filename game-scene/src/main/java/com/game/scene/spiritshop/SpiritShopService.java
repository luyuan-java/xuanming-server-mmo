package com.game.scene.spiritshop;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.player.store.state.ClientRequestRecordState;
import com.game.proto.BuySpiritShopGoodsRequest;
import com.game.proto.BuySpiritShopGoodsResponse;
import com.game.proto.GetSpiritShopInfoResponse;
import com.game.proto.SpiritShopGoodsView;
import com.game.proto.SpiritShopInfo;
import com.game.proto.SpiritShopItemEntry;
import com.game.proto.SpiritShopLimitGroupView;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagFeature;
import com.game.scene.bag.BagService;
import com.game.scene.currency.CurrencyService;
import com.game.scene.equip.EquipmentCraftService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerBags;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerClientRequestLedger.Verdict;
import com.game.scene.player.PlayerSpiritShop;
import com.game.scene.player.Wallet;
import com.game.scene.spiritshop.SpiritShopTables.ItemGrant;
import com.game.scene.spiritshop.SpiritShopTables.ResolvedGoods;
import com.game.scene.spiritshop.SpiritShopTables.ResolvedGroup;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.CommonErrorTip;
import com.game.table.SpiritShopErrorTip;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 官方灵石百宝阁场景侧服务（{@code SceneSpiritShopClientPlayer} 509 查询 / 510 购买；
 * 对齐 {@code spirit-shop-and-starter-packs-v1.md §2.5–§2.6}）。
 *
 * <p>仅在场景逻辑线程调用：通过候选背包 {@link PlayerBags} + {@link CurrencyService#prepareDeduction} 单事务原子提交，
 * 零 {@code [UNPAID_GRANT]} 窗口，且成功后立即触发 {@link SceneWorld#requestKeyProgressSave}。
 */
public final class SpiritShopService {

    private static final long MAX_U32 = 0xFFFF_FFFFL;

    public static final int OK = 0;
    public static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    public static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    public static final int UNAVAILABLE = SpiritShopErrorTip.spirit_shop_error.kSpiritShopUnavailable_VALUE;
    public static final int GOODS_OFF_SHELF = SpiritShopErrorTip.spirit_shop_error.kSpiritShopGoodsOffShelf_VALUE;
    public static final int PRICE_CHANGED = SpiritShopErrorTip.spirit_shop_error.kSpiritShopPriceChanged_VALUE;
    public static final int INVALID_COUNT = SpiritShopErrorTip.spirit_shop_error.kSpiritShopInvalidCount_VALUE;
    public static final int LIMIT_REACHED = SpiritShopErrorTip.spirit_shop_error.kSpiritShopLimitReached_VALUE;
    public static final int GROUP_LIMIT_REACHED = SpiritShopErrorTip.spirit_shop_error.kSpiritShopGroupLimitReached_VALUE;
    public static final int CURRENCY_NOT_ENOUGH = SpiritShopErrorTip.spirit_shop_error.kSpiritShopCurrencyNotEnough_VALUE;
    public static final int BAG_FULL = SpiritShopErrorTip.spirit_shop_error.kSpiritShopBagFull_VALUE;
    public static final int FROZEN = SpiritShopErrorTip.spirit_shop_error.kSpiritShopFrozen_VALUE;
    public static final int IN_BATTLE = SpiritShopErrorTip.spirit_shop_error.kSpiritShopInBattle_VALUE;
    public static final int ITEM_BLOCKED = SpiritShopErrorTip.spirit_shop_error.kSpiritShopItemBlocked_VALUE;

    private final SpiritShopTables tables;
    private final BagService bags;
    private final CurrencyService currency;
    private final SceneClock clock;
    private final SceneMetrics metrics;

    public SpiritShopService(SpiritShopTables tables, BagService bags, CurrencyService currency,
                             SceneClock clock, SceneMetrics metrics) {
        this.tables = Objects.requireNonNull(tables, "tables");
        this.bags = Objects.requireNonNull(bags, "bags");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public long nowMillis() {
        return clock.epochMillis();
    }

    /** 509 GetSpiritShopInfo：纯读取货架、双余额与个人/共享组限购状态。 */
    public GetSpiritShopInfoResponse getInfo(ScenePlayer player, long nowMs) {
        if (!player.spiritShop().isValid()) {
            return GetSpiritShopInfoResponse.newBuilder().setErrorMessage(tip(UNAVAILABLE)).build();
        }
        return GetSpiritShopInfoResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setInfo(buildInfo(player, nowMs))
                .build();
    }

    public SpiritShopInfo buildInfo(ScenePlayer player, long nowMs) {
        long[] week = EquipmentCraftService.resolveWeek(nowMs);
        long weekStartMs = week == null ? 0L : week[0];
        long nextResetMs = week == null ? 0L : week[1];
        PlayerSpiritShop shop = player.spiritShop();

        SpiritShopInfo.Builder out = SpiritShopInfo.newBuilder()
                .setServerTimeMs(nowMs)
                .setShopRevision(shop.shopRevision())
                .setDiamondBalance(player.wallet().balance(Wallet.DIAMOND))
                .setBindDiamondBalance(player.wallet().balance(Wallet.BOUND_DIAMOND));

        for (ResolvedGroup group : tables.groups()) {
            long used = shop.groupUsedWeight(group.groupId(), group.resetKind(), weekStartMs);
            out.addLimitGroups(SpiritShopLimitGroupView.newBuilder()
                    .setGroupId(group.groupId())
                    .setName(group.name())
                    .setResetKind(group.resetKind())
                    .setMaxWeight(group.maxWeight())
                    .setUsedWeight((int) Math.min(used, MAX_U32))
                    .setNextResetMs(group.resetKind() == PlayerSpiritShop.RESET_WEEKLY ? nextResetMs : 0L));
        }

        for (ResolvedGoods g : tables.sellableGoods()) {
            long bought = shop.boughtCount(g.goodsId(), g.limitResetKind(), weekStartMs);
            SpiritShopGoodsView.Builder gv = SpiritShopGoodsView.newBuilder()
                    .setGoodsId(g.goodsId())
                    .setTabId(g.tabId())
                    .setSortOrder(g.sortOrder())
                    .setName(g.name())
                    .setDescription(g.description())
                    .setIconKey(g.iconKey())
                    .setBadgeText(g.badgeText())
                    .setCurrencyType(g.currencyType())
                    .setUnitPrice(g.unitPrice())
                    .setMaxBuyPerRequest(g.maxBuyPerRequest())
                    .setLimitResetKind(g.limitResetKind())
                    .setLimitCount(g.limitCount())
                    .setBoughtCount((int) Math.min(bought, MAX_U32))
                    .setLimitGroupId(g.limitGroupId())
                    .setLimitGroupWeight(g.limitGroupWeight())
                    .setNextResetMs(g.limitCount() > 0 && g.limitResetKind() == PlayerSpiritShop.RESET_WEEKLY
                            ? nextResetMs : 0L);
            for (ItemGrant grant : g.items()) {
                gv.addItems(SpiritShopItemEntry.newBuilder()
                        .setItemConfigId(grant.itemConfigId())
                        .setItemCount(grant.itemCount())
                        .setBound(grant.bound()));
            }
            out.addGoods(gv);
        }
        return out.build();
    }

    /** 510 BuySpiritShopGoods：严格按 §2.6 不可重排顺序执行幂等、闸门、报价、限购、封禁与原子提交。 */
    public BuySpiritShopGoodsResponse buy(SceneWorld world, ScenePlayer player, BuySpiritShopGoodsRequest req,
                                          BagFeature bagView, long nowMs) {
        // 1. 参数与幂等查重（排在一切状态预检之前）
        if (req.getClientRequestId() == 0 || hasUnknownFields(req)) {
            return rejectBuy(INVALID_PARAMETER);
        }
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return rejectBuy(SERVICE_UNAVAILABLE);
        }
        long clientRequestId = req.getClientRequestId();
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(req.toBuilder().clearClientRequestId().build());
        var check = ledger.check(clientRequestId, PlayerClientRequestLedger.KIND_BUY_SPIRIT_SHOP, fingerprint);
        if (check.verdict() == Verdict.REPLAY) {
            return successBuy(player, bagView, nowMs, true);
        }
        if (check.verdict() == Verdict.MISMATCH) {
            return rejectBuy(INVALID_PARAMETER);
        }

        // 2. 可写闸与战斗闸
        if (player.frozen()) {
            return rejectBuy(FROZEN);
        }
        if (player.inBattle()) {
            metrics.battleGateReject(SceneMetrics.BattleGate.SPIRIT_SHOP);
            return rejectBuy(IN_BATTLE);
        }

        // 3. 查表校验与报价防呆
        ResolvedGoods goods = tables.sellableGoods(req.getGoodsId());
        if (goods == null) {
            return rejectBuy(GOODS_OFF_SHELF);
        }
        int rawCount = req.getCount();
        if (rawCount == 0 || Integer.compareUnsigned(rawCount, goods.maxBuyPerRequest()) > 0) {
            return rejectBuy(INVALID_COUNT);
        }
        if (req.getExpectedCurrencyType() != goods.currencyType()
                || req.getExpectedUnitPrice() != goods.unitPrice()
                || !itemsMatch(req.getExpectedItemsList(), goods.items())) {
            return rejectBuy(PRICE_CHANGED);
        }

        // 4. 限购预检（个人 + 共享组 + 抗时钟回拨）
        PlayerSpiritShop shop = player.spiritShop();
        if (!shop.isValid()) {
            return rejectBuy(UNAVAILABLE);
        }
        ResolvedGroup group = goods.limitGroupId() != 0 ? tables.group(goods.limitGroupId()) : null;
        if (goods.limitGroupId() != 0 && group == null) {
            return rejectBuy(GOODS_OFF_SHELF);
        }
        boolean needsWeek = goods.limitResetKind() == PlayerSpiritShop.RESET_WEEKLY
                || (group != null && group.resetKind() == PlayerSpiritShop.RESET_WEEKLY);
        long weekStartMs = 0L;
        if (needsWeek) {
            long[] week = EquipmentCraftService.resolveWeek(nowMs);
            if (week == null) {
                return rejectBuy(UNAVAILABLE);
            }
            weekStartMs = week[0];
        }
        long count = Integer.toUnsignedLong(rawCount);
        if (goods.limitCount() != 0) {
            long bought = shop.boughtCount(goods.goodsId(), goods.limitResetKind(), weekStartMs);
            long limit = Integer.toUnsignedLong(goods.limitCount());
            if (bought >= limit || count > limit - bought) {
                return rejectBuy(LIMIT_REACHED);
            }
        }
        long addWeight = 0L;
        int groupResetKind = PlayerSpiritShop.RESET_LIFETIME;
        if (group != null) {
            groupResetKind = group.resetKind();
            addWeight = Integer.toUnsignedLong(goods.limitGroupWeight()) * count;
            long used = shop.groupUsedWeight(group.groupId(), groupResetKind, weekStartMs);
            long maxWeight = Integer.toUnsignedLong(group.maxWeight());
            if (used >= maxWeight || addWeight > maxWeight - used) {
                return rejectBuy(GROUP_LIMIT_REACHED);
            }
        }
        if (!shop.canRecordPurchase(goods.goodsId(), count, goods.limitResetKind(),
                goods.limitGroupId(), addWeight, groupResetKind, weekStartMs)) {
            return rejectBuy(UNAVAILABLE);
        }

        // 5. 封禁、余额与数量边界预检（严格按 currency_type 校验，绝不拿绑定灵石抵扣灵石）
        for (ItemGrant grant : goods.items()) {
            if (bags.isItemGainBlocked(player, grant.itemConfigId())) {
                return rejectBuy(ITEM_BLOCKED);
            }
            long totalQty = Integer.toUnsignedLong(grant.itemCount()) * count;
            if (totalQty == 0 || totalQty > MAX_U32) {
                return rejectBuy(INVALID_COUNT);
            }
        }
        if (goods.unitPrice() <= 0 || goods.unitPrice() > Long.MAX_VALUE / count) {
            return rejectBuy(UNAVAILABLE);
        }
        long totalCost = goods.unitPrice() * count;
        if (player.wallet().balance(goods.currencyType()) < totalCost) {
            return rejectBuy(CURRENCY_NOT_ENOUGH);
        }

        // 6. 原子候选规划与单事务提交（先克隆背包全量规划双物品与绑定分堆，再扣灵石与背包同帧发布）
        PlayerBags candidateBags = PlayerBags.restore(player.bags().toState());
        candidateBags.normalize(bags.tables());
        List<Bag.Written> allWritten = new ArrayList<>(goods.items().size());
        for (ItemGrant grant : goods.items()) {
            long totalQty = Integer.toUnsignedLong(grant.itemCount()) * count;
            Bag.AddResult added = bags.prepareEquipmentGrant(player, candidateBags,
                    Map.of(grant.itemConfigId(), totalQty), grant.bound());
            if (!added.ok()) {
                int tipId = added.tip() == Bag.NO_SPACE ? BAG_FULL
                        : added.tip() == BagService.REFUSED ? (player.frozen() ? FROZEN : ITEM_BLOCKED)
                        : UNAVAILABLE;
                return rejectBuy(tipId);
            }
            allWritten.addAll(added.written());
        }
        CurrencyService.PreparedDeduction deduction = currency.prepareDeduction(player, goods.currencyType(), totalCost);
        if (deduction.tipId() != 0) {
            return rejectBuy(deduction.tipId() == Wallet.FROZEN ? FROZEN : CURRENCY_NOT_ENOUGH);
        }

        String extra = "{\"source\":\"spirit_shop\",\"goods_id\":" + Integer.toUnsignedString(goods.goodsId())
                + ",\"count\":" + count
                + ",\"unit_price\":" + Long.toUnsignedString(goods.unitPrice())
                + ",\"currency_type\":" + goods.currencyType()
                + ",\"bound\":" + goods.allBound() + "}";
        deduction.commit(candidateBags, AssetAudit.Reason.SPIRIT_SHOP_BUY, clientRequestId, extra);
        shop.recordPurchase(goods.goodsId(), count, goods.limitResetKind(),
                goods.limitGroupId(), addWeight, groupResetKind, weekStartMs);
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(clientRequestId)
                .setKind(PlayerClientRequestLedger.KIND_BUY_SPIRIT_SHOP)
                .setFingerprint(fingerprint));
        bags.auditEquipment(player, List.of(), allWritten, AssetAudit.Reason.SPIRIT_SHOP_BUY, clientRequestId, extra);

        // 7. 关键进度存盘
        if (world != null) {
            world.requestKeyProgressSave(player);
        }
        return successBuy(player, bagView, nowMs, false);
    }

    private static boolean hasUnknownFields(BuySpiritShopGoodsRequest req) {
        if (!req.getUnknownFields().asMap().isEmpty()) {
            return true;
        }
        for (SpiritShopItemEntry e : req.getExpectedItemsList()) {
            if (!e.getUnknownFields().asMap().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean itemsMatch(List<SpiritShopItemEntry> expected, List<ItemGrant> actual) {
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int i = 0; i < actual.size(); i++) {
            SpiritShopItemEntry e = expected.get(i);
            ItemGrant a = actual.get(i);
            if (e.getItemConfigId() != a.itemConfigId()
                    || e.getItemCount() != a.itemCount()
                    || e.getBound() != a.bound()) {
                return false;
            }
        }
        return true;
    }

    private BuySpiritShopGoodsResponse successBuy(ScenePlayer player, BagFeature bagView, long nowMs, boolean replayed) {
        return BuySpiritShopGoodsResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setInfo(buildInfo(player, nowMs))
                .setBag(bagView.snapshot(player, BagType.INVENTORY))
                .setReplayed(replayed)
                .build();
    }

    private static BuySpiritShopGoodsResponse rejectBuy(int tipId) {
        return BuySpiritShopGoodsResponse.newBuilder().setErrorMessage(tip(tipId)).build();
    }
}
