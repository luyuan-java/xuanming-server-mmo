package com.game.scene.merchant;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.player.store.state.ClientRequestRecordState;
import com.game.proto.BuyMysteryMerchantGoodsRequest;
import com.game.proto.BuyMysteryMerchantGoodsResponse;
import com.game.proto.MysteryMerchantGoodsInfo;
import com.game.proto.MysteryMerchantInfo;
import com.game.proto.MysteryMerchantNotify;
import com.game.proto.MysteryMerchantShelfEntry;
import com.game.proto.MysteryMerchantState;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagFeature;
import com.game.scene.bag.BagService;
import com.game.scene.currency.CurrencyService;
import com.game.scene.merchant.MysteryMerchantTables.ResolvedGoods;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Bag;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerClientRequestLedger.Verdict;
import com.game.scene.player.Wallet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;
import com.game.table.MysteryMerchantErrorTip;
import com.google.protobuf.ByteString;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 神秘商人场景侧服务（314 查询 / 315 购买 / 316 公告；对齐 C++ {@code PlayerMysteryMerchantSystem}）。
 * 仅在场景逻辑线程调用。
 */
public final class MysteryMerchantService {

    private static final Logger log = LoggerFactory.getLogger(MysteryMerchantService.class);
    public static final long NEXT_APPEAR_GRACE_MS = 30_000L;
    private static final long MAX_U32 = 0xFFFF_FFFFL;

    static final int OK = 0;
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final int NOT_PRESENT = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantNotPresent_VALUE;
    static final int NOT_HERE = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantNotHere_VALUE;
    static final int TOO_FAR = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantTooFar_VALUE;
    static final int SHELF_CHANGED = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantShelfChanged_VALUE;
    static final int LIMIT_REACHED = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantLimitReached_VALUE;
    static final int CURRENCY_NOT_ENOUGH = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantCurrencyNotEnough_VALUE;
    static final int BAG_FULL = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantBagFull_VALUE;
    static final int IN_BATTLE = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantInBattle_VALUE;
    static final int FROZEN = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantFrozen_VALUE;
    static final int INVALID_COUNT = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantInvalidCount_VALUE;
    static final int ITEM_BLOCKED = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantItemBlocked_VALUE;
    static final int INTERNAL_ERROR = MysteryMerchantErrorTip.mystery_merchant_error.kMysteryMerchantInternalError_VALUE;

    private final MysteryMerchantTables tables;
    private final BagService bags;
    private final CurrencyService currency;
    private final SceneClock clock;
    private final SceneMetrics metrics;
    private MysteryMerchantState cached;
    private long announcedVersion;

    public MysteryMerchantService(MysteryMerchantTables tables, BagService bags, CurrencyService currency,
                                  SceneClock clock, SceneMetrics metrics) {
        this.tables = tables;
        this.bags = bags;
        this.currency = currency;
        this.clock = clock;
        this.metrics = metrics;
    }

    public static long stateVersion(MysteryMerchantState state) {
        return state.getRoundId() * 1000L + Integer.toUnsignedLong(state.getRevision());
    }

    public static boolean isPresent(MysteryMerchantState state, long nowMs) {
        return state != null
                && Long.compareUnsigned(nowMs, state.getAppearAtMs()) >= 0
                && Long.compareUnsigned(nowMs, state.getExpireAtMs()) < 0;
    }

    public static boolean shouldDiscardAsStale(MysteryMerchantState incoming, MysteryMerchantState current, long nowMs) {
        return current != null
                && Long.compareUnsigned(nowMs, current.getExpireAtMs()) < 0
                && Long.compareUnsigned(stateVersion(incoming), stateVersion(current)) < 0;
    }

    public static long nextAppearAtMs(MysteryMerchantState state, long nowMs) {
        long intervalMs = Integer.toUnsignedLong(state.getIntervalSeconds()) * 1000L;
        if (intervalMs == 0) {
            return 0L;
        }
        if (Long.compareUnsigned(nowMs, state.getAppearAtMs()) < 0) {
            return state.getAppearAtMs();
        }
        long elapsed = nowMs - state.getAppearAtMs();
        long rounds = Long.divideUnsigned(elapsed, intervalMs);
        long lastBoundary = state.getAppearAtMs() + rounds * intervalMs;
        if (rounds > 0 && Long.compareUnsigned(nowMs - lastBoundary, NEXT_APPEAR_GRACE_MS) < 0) {
            return lastBoundary;
        }
        return lastBoundary + intervalMs;
    }

    public static boolean withinInteractRange(double px, double py, double mx, double my, int range) {
        double dx = px - mx;
        double dy = py - my;
        double limit = Integer.toUnsignedLong(range);
        return dx * dx + dy * dy <= limit * limit;
    }

    /** 采纳新轮次或换线记录；若应向本节点全体在线玩家推 316 公告则返回非空通知体。 */
    public Optional<MysteryMerchantNotify> applyState(MysteryMerchantState incoming, long nowMs) {
        if (incoming == null || incoming.getRoundId() == 0) {
            return Optional.empty();
        }
        if (shouldDiscardAsStale(incoming, cached, nowMs)) {
            return Optional.empty();
        }
        this.cached = incoming;
        long version = stateVersion(incoming);
        if (isPresent(incoming, nowMs) && version != announcedVersion) {
            announcedVersion = version;
            return Optional.of(MysteryMerchantNotify.newBuilder()
                    .setInfo(publicInfo(incoming, nowMs))
                    .build());
        }
        return Optional.empty();
    }

    public long nowMillis() {
        return clock.epochMillis();
    }

    /** 构建 314 / 315 的客户端展示状态（仅与商人同线时下发货架与个人已购数）。 */
    public MysteryMerchantInfo buildInfo(ScenePlayer player, long nowMs) {
        if (cached == null) {
            return MysteryMerchantInfo.newBuilder()
                    .setServerTimeMs(nowMs)
                    .setInteractRange(tables.interactRange())
                    .build();
        }
        if (!isPresent(cached, nowMs)) {
            return MysteryMerchantInfo.newBuilder()
                    .setServerTimeMs(nowMs)
                    .setNextAppearAtMs(nextAppearAtMs(cached, nowMs))
                    .setInteractRange(tables.interactRange())
                    .build();
        }
        MysteryMerchantInfo.Builder out = publicInfo(cached, nowMs).toBuilder();
        if (player == null || player.scene() == null || player.scene().sceneId() != cached.getSceneId()) {
            return out.build();
        }
        out.setHere(true);
        for (MysteryMerchantShelfEntry entry : cached.getShelfList()) {
            ResolvedGoods g = tables.resolve(entry);
            if (g == null) {
                continue;
            }
            out.addGoods(MysteryMerchantGoodsInfo.newBuilder()
                    .setGoodsId(g.goodsId())
                    .setItemConfigId(g.itemConfigId())
                    .setItemCount(g.itemCount())
                    .setName(g.name())
                    .setDescription(g.description())
                    .setIconKey(g.iconKey())
                    .setCurrencyType(g.currencyType())
                    .setOriginalPrice(g.originalPrice())
                    .setPrice(g.price())
                    .setDiscountPercent(g.discountPercent())
                    .setLimitCount(g.limitCount())
                    .setBoughtCount(player.mysteryMerchant().boughtCount(cached.getRoundId(), g.goodsId())));
        }
        return out.build();
    }

    /** 315 购买神秘商人商品（幂等重放优先于全部预检；先发物后扣款免退款回路）。 */
    public BuyMysteryMerchantGoodsResponse buy(ScenePlayer player, BuyMysteryMerchantGoodsRequest req,
                                               BagFeature bagView, long nowMs) {
        long clientRequestId = req.getClientRequestId();
        ByteString fingerprint = ByteString.EMPTY;
        if (clientRequestId != 0) {
            if (!player.clientRequestLedger().isValid()) {
                return rejectBuy(SERVICE_UNAVAILABLE);
            }
            fingerprint = PlayerClientRequestLedger.fingerprint(req.toBuilder().clearClientRequestId().build());
            var check = player.clientRequestLedger().check(
                    clientRequestId, PlayerClientRequestLedger.KIND_BUY_MYSTERY_MERCHANT, fingerprint);
            if (check.verdict() == Verdict.REPLAY) {
                return successBuy(player, bagView, nowMs, true);
            }
            if (check.verdict() == Verdict.MISMATCH) {
                return rejectBuy(INVALID_PARAMETER);
            }
        }
        if (player.frozen()) {
            return rejectBuy(FROZEN);
        }
        if (player.inBattle()) {
            metrics.battleGateReject(SceneMetrics.BattleGate.MYSTERY_MERCHANT);
            return rejectBuy(IN_BATTLE);
        }
        if (req.getGoodsId() == 0 || req.getCount() == 0
                || Integer.compareUnsigned(req.getCount(), MysteryMerchantTables.MAX_BUY_COUNT) > 0) {
            return rejectBuy(INVALID_COUNT);
        }
        MysteryMerchantState state = cached;
        if (state == null || !isPresent(state, nowMs)) {
            return rejectBuy(NOT_PRESENT);
        }
        if (req.getRoundId() != state.getRoundId()) {
            return rejectBuy(SHELF_CHANGED);
        }
        if (player.scene() == null || player.scene().sceneId() != state.getSceneId()) {
            return rejectBuy(NOT_HERE);
        }
        if (!withinInteractRange(player.position().x(), player.position().y(),
                state.getPosition().getX(), state.getPosition().getY(), tables.interactRange())) {
            return rejectBuy(TOO_FAR);
        }
        MysteryMerchantShelfEntry shelfEntry = null;
        for (MysteryMerchantShelfEntry entry : state.getShelfList()) {
            if (entry.getGoodsId() == req.getGoodsId()) {
                shelfEntry = entry;
                break;
            }
        }
        if (shelfEntry == null) {
            return rejectBuy(SHELF_CHANGED);
        }
        ResolvedGoods goods = tables.resolve(shelfEntry);
        if (goods == null) {
            return rejectBuy(INTERNAL_ERROR);
        }
        if (req.getExpectedPrice() != goods.price()
                || req.getExpectedCurrencyType() != goods.currencyType()
                || req.getExpectedItemConfigId() != goods.itemConfigId()
                || req.getExpectedItemCount() != goods.itemCount()) {
            return rejectBuy(SHELF_CHANGED);
        }
        int bought = player.mysteryMerchant().boughtCount(state.getRoundId(), goods.goodsId());
        if (bought >= goods.limitCount() || req.getCount() > goods.limitCount() - bought) {
            return rejectBuy(LIMIT_REACHED);
        }
        long count = Integer.toUnsignedLong(req.getCount());
        long totalItems = Integer.toUnsignedLong(goods.itemCount()) * count;
        if (goods.price() <= 0 || goods.price() > Long.MAX_VALUE / count || totalItems > MAX_U32) {
            return rejectBuy(INTERNAL_ERROR);
        }
        long totalCost = goods.price() * count;
        if (bags.isItemGainBlocked(player, goods.itemConfigId())) {
            return rejectBuy(ITEM_BLOCKED);
        }
        if (player.wallet().balance(goods.currencyType()) < totalCost) {
            return rejectBuy(CURRENCY_NOT_ENOUGH);
        }
        // 先试发物（BagService.addItems 全或无候选提交）：失败时货币零改动
        String extra = "{\"merchant_round\":" + Long.toUnsignedString(state.getRoundId())
                + ",\"goods_id\":" + Integer.toUnsignedString(goods.goodsId())
                + ",\"count\":" + count + "}";
        Bag.AddResult added = bags.addItems(player, BagType.INVENTORY, Map.of(goods.itemConfigId(), totalItems),
                AssetAudit.Reason.MYSTERY_MERCHANT_BUY, state.getRoundId(), extra);
        if (!added.ok()) {
            return rejectBuy(added.tip() == Bag.NO_SPACE ? BAG_FULL : INTERNAL_ERROR);
        }
        Wallet.Change deducted = currency.deduct(player, goods.currencyType(), totalCost,
                AssetAudit.Reason.MYSTERY_MERCHANT_BUY, state.getRoundId());
        player.mysteryMerchant().recordPurchase(state.getRoundId(), goods.goodsId(), req.getCount());
        if (!deducted.ok()) {
            log.error("[MysteryMerchant][UNPAID_GRANT] player={} round={} goods={} cost={} tip={}",
                    Long.toUnsignedString(player.playerId()), Long.toUnsignedString(state.getRoundId()),
                    goods.goodsId(), totalCost, deducted.tipId());
            return rejectBuy(INTERNAL_ERROR);
        }
        if (clientRequestId != 0) {
            player.clientRequestLedger().remember(ClientRequestRecordState.newBuilder()
                    .setKey(clientRequestId)
                    .setKind(PlayerClientRequestLedger.KIND_BUY_MYSTERY_MERCHANT)
                    .setFingerprint(fingerprint));
        }
        return successBuy(player, bagView, nowMs, false);
    }

    private MysteryMerchantInfo publicInfo(MysteryMerchantState state, long nowMs) {
        return MysteryMerchantInfo.newBuilder()
                .setActive(true)
                .setRoundId(state.getRoundId())
                .setSceneConfigId(state.getSceneConfigId())
                .setSceneId(state.getSceneId())
                .setChannelNo(state.getChannelNo())
                .setPosition(state.getPosition())
                .setAppearAtMs(state.getAppearAtMs())
                .setExpireAtMs(state.getExpireAtMs())
                .setNextAppearAtMs(nextAppearAtMs(state, nowMs))
                .setServerTimeMs(nowMs)
                .setHere(false)
                .setInteractRange(tables.interactRange())
                .build();
    }

    private BuyMysteryMerchantGoodsResponse successBuy(ScenePlayer player, BagFeature bagView, long nowMs, boolean replayed) {
        return BuyMysteryMerchantGoodsResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setInfo(buildInfo(player, nowMs))
                .setBag(bagView.snapshot(player, BagType.INVENTORY))
                .setReplayed(replayed)
                .build();
    }

    private static BuyMysteryMerchantGoodsResponse rejectBuy(int tipId) {
        return BuyMysteryMerchantGoodsResponse.newBuilder().setErrorMessage(tip(tipId)).build();
    }
}
