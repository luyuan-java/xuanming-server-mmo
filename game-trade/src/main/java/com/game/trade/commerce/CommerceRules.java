package com.game.trade.commerce;

import com.game.proto.*;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.trade.*;
import com.game.table.CommonErrorTip;
import com.game.table.TradeErrorTip;
import com.game.trade.store.pb.TradeCommerceRow;
import java.util.*;

/** 两方合同的纯状态迁移；余额与实例只能由资产通道改变。 */
public final class CommerceRules {
    public static final int WAITING = 1, ESCROWING = 3, DELIVERING = 4, DELIVERED = 5, CANCEL_PENDING = 6,
                            CANCELLED = 7, MANUAL = 10, SETTLED = 11, BID_KIND = 100;
    public static final int INVALID = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    public static final int MISSING = TradeErrorTip.trade_error.kTradeOrderNotFound_VALUE;
    public static final int CONFLICT = TradeErrorTip.trade_error.kTradeOrderStateConflict_VALUE;
    public static final int REQUEST_CONFLICT = TradeErrorTip.trade_error.kTradeRequestConflict_VALUE;
    public static final int SELF = TradeErrorTip.trade_error.kTradeCannotTradeSelf_VALUE;
    public static final int NOT_TRADABLE = TradeErrorTip.trade_error.kTradeAssetNotTradable_VALUE;
    public static final int ENDED = TradeErrorTip.trade_error.kTradeAuctionEnded_VALUE;
    public static final int TITLE_SENSITIVE = TradeErrorTip.trade_error.kTradeTitleSensitive_VALUE;
    public static final int TEXT_CHECK_UNAVAILABLE = TradeErrorTip.trade_error.kTradeTextCheckUnavailable_VALUE;
    public static final class Refusal extends RuntimeException {
        private final int tip;
        public Refusal(int tip) {
            super("交易请求被拒绝");
            this.tip = tip;
        }
        public int tip() {
            return tip;
        }
    }
    public record Policy(Set<Integer> items, Set<Integer> pets, Set<Integer> currencies, boolean instances,
        List<TradeMarketView> markets) {
        public Policy {
            items = Set.copyOf(items);
            pets = Set.copyOf(pets);
            currencies = Set.copyOf(currencies);
            markets = List.copyOf(markets);
        }
        private static boolean isBoundCurrency(int currencyType) {
            return currencyType == CurrencyType.CURRENCY_TYPE_BIND_DIAMOND_VALUE
                    || currencyType == CurrencyType.CURRENCY_TYPE_BIND_GOLD_VALUE;
        }
        public void selection(TradeAssetSelection v) {
            if (v.getItemsCount() > 16 || v.getItemUuidsCount() > 8
                || v.getItemsCount() + v.getItemUuidsCount() == 0 && v.getPetId() == 0 && v.getCurrencyAmount() == 0)
                throw new Refusal(INVALID);
            if (v.getCurrencyAmount() < 0 || v.getCurrencyAmount() == 0 && v.getCurrencyType() != 0)
                throw new Refusal(INVALID);
            if (v.getCurrencyAmount() > 0 && (isBoundCurrency(v.getCurrencyType()) || !currencies.contains(v.getCurrencyType())))
                throw new Refusal(NOT_TRADABLE);
            long total = 0;
            for (var i : v.getItemsMap().entrySet()) {
                if (i.getValue() <= 0 || i.getValue() > 4096 || !items.contains(i.getKey()))
                    throw new Refusal(NOT_TRADABLE);
                total += i.getValue();
                if (total > 4096)
                    throw new Refusal(INVALID);
            }
            if ((v.getItemUuidsCount() > 0 || v.getPetId() != 0) && !instances)
                throw new Refusal(NOT_TRADABLE);
            Set<Long> ids = new HashSet<>();
            for (long id : v.getItemUuidsList())
                if (id == 0 || !ids.add(id))
                    throw new Refusal(INVALID);
        }
        boolean snapshot(AssetSnapshot v) {
            for (var i : v.getItemsList())
                if (!items.contains(i.getConfigId()))
                    return false;
            return !v.hasPet() || pets.contains(v.getPet().getPetTableId());
        }
        public void price(long price, int type) {
            if (price <= 0 || isBoundCurrency(type) || !currencies.contains(type))
                throw new Refusal(INVALID);
        }
    }
    private CommerceRules() {}
    public static AssetBundle bundle(TradeAssetSelection v) {
        var b = AssetBundle.newBuilder().addAllItemUuids(v.getItemUuidsList()).setPetId(v.getPetId());
        if (v.getCurrencyAmount() != 0)
            b.addCurrencies(
                CurrencyAmount.newBuilder().setCurrencyType(v.getCurrencyType()).setAmount(v.getCurrencyAmount()));
        v.getItemsMap()
            .keySet()
            .stream()
            .sorted(Integer::compareUnsigned)
            .forEach(id -> b.addItems(ItemGrant.newBuilder().setConfigId(id).setCount(v.getItemsOrDefault(id, 0))));
        return b.build();
    }
    public static AssetBundle transfer(TradeCommerceLeg l) {
        if (l.getBundle().getItemInstancesCount() > 0 || l.getBundle().hasPetInstance())
            throw new IllegalStateException("托管请求包含非扣出字段");
        var b = l.getBundle().toBuilder().addAllItemInstances(l.getSnapshot().getItemsList());
        if (l.getSnapshot().hasPet())
            b.setPetInstance(l.getSnapshot().getPet());
        if (b.getItemInstancesCount() != b.getItemUuidsCount() || (b.getPetId() != 0) != b.hasPetInstance())
            throw new IllegalStateException("托管完整实例快照缺失或与合同不符");
        Set<Long> wanted = new HashSet<>();
        for (long id : b.getItemUuidsList())
            if (id == 0 || !wanted.add(id))
                throw new IllegalStateException("托管实例选择损坏");
        for (var item : b.getItemInstancesList())
            if (!wanted.remove(item.getItemUuid()))
                throw new IllegalStateException("托管物品身份与合同不符");
        if (!wanted.isEmpty() || b.hasPetInstance() && b.getPetInstance().getPetId() != b.getPetId())
            throw new IllegalStateException("托管实例身份与合同不符");
        // 扣出用GUID选择，入账只用可信完整原件，不能混带两种命令的字段。
        return b.clearItemUuids().clearPetId().build();
    }
    static final String STAGE_OFFICIAL_DELIVER = "official_deliver";
    static final String STAGE_OFFICIAL_REVERSED = "official_reversed";
    static final String OFFICIAL_CREDIT_ROLE = "official_credit";
    static final String OFFICIAL_REVERSE_PREFIX = "official_reverse_";
    static final String OFFICIAL_REGRANT_PREFIX = "official_regrant_";
    static final String UNWOUND_BY = "unwound";
    static final int OUTCOME_PARTIAL = 5;
    static final int TX_AUCTION_SELL = 3;
    static final int TX_AUCTION_BUY = 4;
    static final int TX_AUCTION_RETURN = 30;
    static final int TX_PAYMENT_REVERSAL = 31;

    static boolean isOfficialReverseLeg(String role) {
        return role != null && role.startsWith(OFFICIAL_REVERSE_PREFIX);
    }

    static String baseRole(String role) {
        if (role == null) return "";
        int cut = role.indexOf('#');
        return cut < 0 ? role : role.substring(0, cut);
    }

    /** 判断发放腿是否属于退回本人托管资产（撤单、过期、竞价退款、撮合差价、整单退回、人工退款及补发）。 */
    static boolean isReturnLeg(String role) {
        String base = baseRole(role);
        return switch (base) {
            case "seller_return", "buyer_return", "bid_return", "buyer_surplus", "rmb_return_credit" -> true;
            default -> base.endsWith("/refund") || base.startsWith("seller_return_");
        };
    }

    /**
     * 计算腿进资产通道的交易类型：仅当退回腿付回托管者本人（或旧腿 escrow_owner_id=0）时带 {@link #TX_AUCTION_RETURN}（30），
     * 改绑给同账号其他角色则按普通成交所得 {@link #TX_AUCTION_BUY}（4）受获取封禁约束。
     */
    static int legTxType(TradeCommerceLegOrBuilder l) {
        if (l.getStream() == 3) {
            return isOfficialReverseLeg(l.getRole()) ? TX_PAYMENT_REVERSAL : TX_AUCTION_SELL;
        }
        if (isReturnLeg(l.getRole()) && (l.getEscrowOwnerId() == 0 || l.getEscrowOwnerId() == l.getPlayerId())) {
            return TX_AUCTION_RETURN;
        }
        return TX_AUCTION_BUY;
    }

    /** 卖家所得腿（seller_receive 及其补发），交付阶段需等买家交付腿到账后再发。 */
    static boolean isSellerProceeds(TradeCommerceLegOrBuilder l) {
        return l != null && "seller_receive".equals(baseRole(l.getRole()));
    }

    /** 沿补发链找到当前生效腿；{@link #UNWOUND_BY} 表示整单自动退回取代，停在原腿。 */
    static TradeCommerceLeg.Builder currentLeg(TradeCommerceWorkflow.Builder w, String name) {
        var cur = leg(w, name);
        int hops = 0;
        while (cur != null && !cur.getSupersededBy().isEmpty() && !UNWOUND_BY.equals(cur.getSupersededBy())) {
            if (++hops > w.getLegsCount()) break;
            var next = leg(w, cur.getSupersededBy());
            if (next == null) break;
            cur = next;
        }
        return cur;
    }

    /** 成交交付先买家后卖家：卖家所得腿在买家当前交付腿尚未到账前压着不发。 */
    static boolean heldForBuyerFirst(TradeCommerceWorkflow.Builder w, TradeCommerceLegOrBuilder l) {
        if (!isSellerProceeds(l)) return false;
        var buyer = currentLeg(w, "buyer_receive");
        return buyer != null && !applied(buyer);
    }

    /** 发出循环判据：未发、未被取代、未被先买家后卖家闸压住。 */
    static boolean legReadyToSend(TradeCommerceWorkflow.Builder w, TradeCommerceLegOrBuilder l) {
        return l.getOpId() == 0 && l.getSupersededBy().isEmpty() && !heldForBuyerFirst(w, l);
    }

    /** 交付阶段可自动整单退回：两条交付腿均为原腿、买家一点未到、卖家从未发出。 */
    static boolean unwindable(TradeCommerceWorkflow.Builder w) {
        var buyer = leg(w, "buyer_receive");
        var seller = leg(w, "seller_receive");
        return buyer != null && seller != null
            && buyer.getSupersededBy().isEmpty() && seller.getSupersededBy().isEmpty()
            && done(buyer) && !applied(buyer) && buyer.getOutcome() != OUTCOME_PARTIAL
            && seller.getOpId() == 0;
    }

    /** 人工处置辅助：转人工合同里尚未发出的卖家所得可按被闸压住放过，整单退回时应一并标取代且禁止部分到账合同整单退回。 */
    static boolean canManualFullRefund(TradeCommerceWorkflowOrBuilder w) {
        for (var l : w.getLegsList()) {
            if (l.getStream() == 4 && l.getOutcome() == OUTCOME_PARTIAL) {
                return false;
            }
        }
        return true;
    }

    static boolean isHeldUnissuedSellerProceeds(TradeCommerceLegOrBuilder l) {
        return l != null && l.getStream() == 4 && l.getOpId() == 0 && l.getSupersededBy().isEmpty()
            && isSellerProceeds(l);
    }

    /** 在交付阶段处理买家腿终局未到账：能自动整单退回则建退回腿转 return/CANCEL_PENDING，否则转 MANUAL 继续压住卖家所得。 */
    static void settleBuyerFirstDelivery(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w) {
        var buyer = currentLeg(w, "buyer_receive");
        if (buyer == null || !done(buyer) || applied(buyer)) {
            return;
        }
        var seller = currentLeg(w, "seller_receive");
        if (seller == null || seller.getOpId() != 0) {
            return;
        }
        if (!unwindable(w)) {
            w.setStage("manual");
            r.setStatus(MANUAL);
            return;
        }
        if (leg(w, "seller_return") != null || leg(w, "buyer_return") != null) {
            throw new IllegalStateException("买家交付腿被拒要整单退回，合同里却已经有退回腿");
        }
        buyer.setSupersededBy(UNWOUND_BY);
        seller.setSupersededBy(UNWOUND_BY);
        addEscrowLeg(w, "seller_return", seller.getPlayerId(), buyer.getEscrowOwnerId(), buyer.getBundle());
        if (r.getKind() != 5) {
            // 拍品(kind 5)不退钱：钱仍在赢家出价合同中，由出价看到拍品撤单结案后自行退款。
            addEscrowLeg(w, "buyer_return", buyer.getPlayerId(), seller.getEscrowOwnerId(), seller.getBundle());
        }
        w.setStage("return");
        r.setStatus(CANCEL_PENDING);
    }

    /** 拍品撤单结案后校验持有中的出价冻结款是否仍安全留在出价合同里（卖家未发过款且赢家一点没到过货）。 */
    static boolean heldBidKeepsFunds(TradeCommerceWorkflowOrBuilder lot, long bidId) {
        if (lot.getWinningBidId() != bidId) {
            return true;
        }
        for (var l : lot.getLegsList()) {
            if ("seller_receive".equals(l.getRole()) && l.getOpId() != 0) {
                return false;
            }
            if ("buyer_receive".equals(baseRole(l.getRole())) && l.getOpId() != 0
                && (l.getOutcome() == 0 || l.getOutcome() == 2 || l.getOutcome() == OUTCOME_PARTIAL)) {
                return false;
            }
        }
        return true;
    }

    /** 判本步推进是否让一件拍品首次进入结案态（SETTLED / CANCELLED），是则返回需唤醒的赢家出价合同号。 */
    static long finishedLotBid(TradeCommerceRow before, TradeCommerceRow after, TradeCommerceWorkflowOrBuilder w) {
        if (after.getKind() != 5) return 0L;
        if (before.getStatus() == SETTLED || before.getStatus() == CANCELLED) return 0L;
        if (after.getStatus() != SETTLED && after.getStatus() != CANCELLED) return 0L;
        return w.getWinningBidId();
    }

    /** 按官方合同各腿结局计算买家当前是否持有发放：发放腿(stream=4)到账后持有，扣回腿(stream=3)到账后不持有。 */
    private static Boolean officialHeldIfSettled(TradeCommerceWorkflow.Builder w) {
        boolean held = false;
        for (var l : w.getLegsBuilderList()) {
            if (!l.getSupersededBy().isEmpty()) {
                continue;
            }
            if (!done(l)) {
                return null;
            }
            if (applied(l)) {
                held = l.getStream() == 4;
            }
        }
        return held;
    }

    /** 将官方商城合同的发放收敛到最新付款修订号（对齐 Go official_reversal.go planOfficial）。 */
    static void planOfficial(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w) {
        Boolean held = officialHeldIfSettled(w);
        if (held == null) {
            return;
        }
        boolean entitled = !w.getPaymentRevoked();
        if (held == entitled) {
            if (entitled) {
                w.setStage("done");
                r.setStatus(SETTLED);
            } else {
                w.setStage(STAGE_OFFICIAL_REVERSED);
                r.setStatus(CANCELLED);
            }
            return;
        }
        String rev = Integer.toUnsignedString(w.getPaymentRevision());
        String role = OFFICIAL_CREDIT_ROLE;
        int stream = 4;
        if (!entitled) {
            role = OFFICIAL_REVERSE_PREFIX + rev;
            stream = 3;
        } else if (leg(w, OFFICIAL_CREDIT_ROLE) != null && w.getPaymentRevision() > 0) {
            role = OFFICIAL_REGRANT_PREFIX + rev;
        }
        if (leg(w, role) == null) {
            add(w, role, r.getBuyerPlayerId(), stream, w.getOfficialBundle());
            return;
        }
        if (OFFICIAL_CREDIT_ROLE.equals(role) && w.getPaymentRevision() == 0) {
            w.setStage("official_refund");
            r.setStatus(8);
            return;
        }
        w.setStage("manual");
        r.setStatus(MANUAL);
    }

    /** 官方订单回报商城扣回待办的结论；空串表示合同尚未收敛到当前修订号。 */
    static String officialReversalState(TradeCommerceRow r, TradeCommerceWorkflow w) {
        if (r.getStatus() == MANUAL) {
            return "MANUAL";
        }
        if (r.getStatus() == SETTLED && !w.getPaymentRevoked()) {
            return "DELIVERED";
        }
        if (r.getStatus() == CANCELLED && w.getPaymentRevoked()) {
            return "REVERSED";
        }
        return "";
    }

    static TradeCommerceLeg.Builder leg(TradeCommerceWorkflow.Builder w, String name) {
        for (var l : w.getLegsBuilderList())
            if (l.getRole().equals(name))
                return l;
        return null;
    }
    static boolean done(TradeCommerceLeg.Builder l) {
        return l != null && l.getOutcome() != 0;
    }
    static boolean applied(TradeCommerceLeg.Builder l) {
        return l != null && l.getOutcome() == 2;
    }
    static void add(TradeCommerceWorkflow.Builder w, String role, long player, int stream, AssetBundle b) {
        if (leg(w, role) == null)
            w.addLegs(TradeCommerceLeg.newBuilder().setRole(role).setPlayerId(player).setStream(stream).setBundle(b));
    }
    static void addEscrowLeg(TradeCommerceWorkflow.Builder w, String role, long to, long escrowOwnerId, AssetBundle b) {
        if (leg(w, role) == null)
            w.addLegs(TradeCommerceLeg.newBuilder().setRole(role).setPlayerId(to).setStream(4)
                .setEscrowOwnerId(escrowOwnerId).setBundle(b));
    }
    static void transferred(TradeCommerceWorkflow.Builder w, String role, long to, TradeCommerceLeg.Builder from) {
        addEscrowLeg(w, role, to, from.getPlayerId(), transfer(from.build()));
    }
    public static void plan(TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w, long now, Policy policy) {
        if (r.getKind() == 6) {
            if (w.getStage().equals("rmb_escrow")) {
                add(w, "seller_debit", r.getSellerPlayerId(), 3, bundle(w.getSellerAssets()));
                var debit = leg(w, "seller_debit");
                if (!done(debit)) return;
                if (!applied(debit)) { w.setStage("done"); r.setStatus(CANCELLED); return; }
                if (!policy.snapshot(debit.getSnapshot())) w.setCancelRequested(true);
                w.setStage(w.getCancelRequested() ? "rmb_return" : "rmb_register");
                r.setStatus(w.getCancelRequested() ? CANCEL_PENDING : WAITING);
            }
            if (w.getStage().equals("rmb_deliver")) {
                transferred(w, "rmb_credit", r.getBuyerPlayerId(), leg(w, "seller_debit"));
                var credit = leg(w, "rmb_credit");
                if (done(credit)) {
                    w.setStage(applied(credit) ? "rmb_receipt" : "rmb_refund");
                    r.setStatus(applied(credit) ? DELIVERING : 8);
                }
            }
            if (w.getStage().equals("rmb_return")) {
                transferred(w, "rmb_return_credit", r.getSellerPlayerId(), leg(w, "seller_debit"));
                var credit = leg(w, "rmb_return_credit");
                if (done(credit)) {
                    w.setStage(applied(credit) ? "rmb_return_receipt" : "manual");
                    r.setStatus(applied(credit) ? CANCEL_PENDING : MANUAL);
                }
            }
            return;
        }
        if (w.getStage().equals(STAGE_OFFICIAL_DELIVER)) {
            planOfficial(r, w);
            return;
        }
        if (w.getStage().equals("offer_escrow")) {
            add(w, "seller_debit", r.getSellerPlayerId(), 3, bundle(w.getSellerAssets()));
            var a = leg(w, "seller_debit");
            if (!done(a))
                return;
            if (!applied(a)) {
                w.setStage("done");
                r.setStatus(CANCELLED);
                return;
            }
            if (!policy.snapshot(a.getSnapshot()))
                w.setCancelRequested(true);
            w.setStage("confirm");
            r.setStatus(WAITING);
        }
        if (w.getStage().equals("buyer_escrow")) {
            add(w, "buyer_debit", r.getBuyerPlayerId(), 3, bundle(w.getBuyerAssets()));
            var b = leg(w, "buyer_debit");
            if (!done(b))
                return;
            if (!applied(b) || !policy.snapshot(b.getSnapshot()))
                w.setCancelRequested(true);
            w.setStage("confirm");
            r.setStatus(WAITING);
        }
        if (r.getKind() == 2 && w.getStage().equals("confirm") && (w.getCancelRequested() || r.getExpiresMs() <= now)) {
            w.setStage("return");
            r.setStatus(CANCEL_PENDING);
            if (applied(leg(w, "seller_debit")))
                transferred(w, "seller_return", r.getSellerPlayerId(), leg(w, "seller_debit"));
            if (applied(leg(w, "buyer_debit")))
                transferred(w, "buyer_return", r.getBuyerPlayerId(), leg(w, "buyer_debit"));
        }
        if (w.getStage().equals("bid_freeze")) {
            add(w, "bid_debit", r.getSellerPlayerId(), 3, bundle(w.getSellerAssets()));
            var b = leg(w, "bid_debit");
            if (done(b)) {
                if (applied(b))
                    w.setStage("bid_ready");
                else {
                    w.setStage("done");
                    r.setStatus(CANCELLED);
                }
            }
        }
        if (w.getStage().equals("bid_refund")) {
            var b = leg(w, "bid_debit");
            if (!applied(b))
                throw new IllegalStateException("竞价退款没有已提交扣币");
            transferred(w, "bid_return", r.getSellerPlayerId(), b);
            w.setStage("return");
            r.setStatus(CANCEL_PENDING);
        }
        if (w.getStage().equals("escrow")) {
            add(w, "seller_debit", r.getSellerPlayerId(), 3, bundle(w.getSellerAssets()));
            if (r.getKind() == 2)
                add(w, "buyer_debit", r.getBuyerPlayerId(), 3, bundle(w.getBuyerAssets()));
            var a = leg(w, "seller_debit");
            var b = leg(w, "buyer_debit");
            if (!done(a) || r.getKind() == 2 && !done(b))
                return;
            if (!applied(a) || r.getKind() == 2 && !applied(b) || applied(a) && !policy.snapshot(a.getSnapshot())
                || applied(b) && !policy.snapshot(b.getSnapshot())) {
                w.setStage("return");
                r.setStatus(CANCEL_PENDING);
                if (applied(a))
                    transferred(w, "seller_return", r.getSellerPlayerId(), a);
                if (applied(b))
                    transferred(w, "buyer_return", r.getBuyerPlayerId(), b);
            } else if (r.getKind() == 2) {
                w.setStage("deliver");
                r.setStatus(DELIVERING);
                transferred(w, "seller_receive", r.getSellerPlayerId(), b);
                transferred(w, "buyer_receive", r.getBuyerPlayerId(), a);
            } else
                w.setStage(r.getKind() == 4 ? "market_open" : r.getKind() == 5 ? "lot_open" : "open");
        }
        if (w.getStage().equals("pay")) {
            String role = "buyer_debit_" + Long.toUnsignedString(w.getQuantityReserved());
            var b = leg(w, role);
            for (var candidate : w.getLegsBuilderList())
                if (candidate.getRole().startsWith(role + "_")) {
                    b = candidate;
                    break;
                }
            if (b == null) {
                add(w, role, r.getBuyerPlayerId(), 3, bundle(w.getBuyerAssets()));
                b = leg(w, role);
            }
            if (!done(b))
                return;
            if (!applied(b)) {
                w.setStage("open");
                r.setBuyerPlayerId(0);
            } else {
                w.setStage("deliver");
                r.setStatus(DELIVERING);
                transferred(w, "buyer_receive", r.getBuyerPlayerId(), leg(w, "seller_debit"));
                transferred(w, "seller_receive", r.getSellerPlayerId(), b);
            }
        }
        if (w.getStage().equals("lot_open") && r.getExpiresMs() <= now && w.getHighestBidder() != 0) {
            w.setStage("deliver");
            r.setStatus(DELIVERING);
            transferred(w, "buyer_receive", w.getHighestBidder(), leg(w, "seller_debit"));
            addEscrowLeg(w, "seller_receive", r.getSellerPlayerId(), w.getHighestBidder(), bundle(w.getBuyerAssets()));
        }
        if ((w.getStage().equals("open") || w.getStage().equals("lot_open")) && r.getExpiresMs() <= now)
            w.setCancelRequested(true);
        if (w.getCancelRequested()
            && (w.getStage().equals("open") || w.getStage().equals("market_open")
                || w.getStage().equals("lot_open") && w.getHighestBidder() == 0)) {
            w.setStage("return");
            r.setStatus(CANCEL_PENDING);
            var sellerDebit = leg(w, "seller_debit");
            AssetBundle b = transfer(sellerDebit.build());
            if (r.getKind() == 4) {
                long remaining =
                    Integer.toUnsignedLong(r.getQuantity()) - Integer.toUnsignedLong(r.getFilledQuantity());
                var remainingBundle = AssetBundle.newBuilder();
                if (remaining > 0) {
                    if (r.getSide() == 1)
                        remainingBundle.addCurrencies(CurrencyAmount.newBuilder()
                                .setCurrencyType(r.getCurrencyType())
                                .setAmount(Math.multiplyExact(remaining, r.getUnitPrice())));
                    else
                        remainingBundle.addItems(
                            ItemGrant.newBuilder().setConfigId(r.getItemConfigId()).setCount((int) remaining));
                }
                b = remainingBundle.build();
            }
            if (b.getCurrenciesCount() + b.getItemsCount() + b.getItemInstancesCount() > 0 || b.hasPetInstance())
                addEscrowLeg(w, "seller_return", r.getSellerPlayerId(), sellerDebit.getPlayerId(), b);
        }
        if (w.getStage().equals("deliver")) {
            settleBuyerFirstDelivery(r, w);
        }
        if (w.getStage().equals("deliver") || w.getStage().equals("return")) {
            boolean ready = true, ok = true;
            for (var l : w.getLegsBuilderList())
                if (l.getStream() == 4 && l.getSupersededBy().isEmpty()) {
                    ready &= done(l);
                    ok &= applied(l);
                }
            if (ready) {
                w.setStage("done");
                r.setStatus(!ok ? MANUAL : r.getStatus() == CANCEL_PENDING ? CANCELLED : SETTLED);
            }
        }
    }
}
