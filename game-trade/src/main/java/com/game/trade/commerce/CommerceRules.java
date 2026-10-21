package com.game.trade.commerce;

import com.game.proto.*;
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
        public void selection(TradeAssetSelection v) {
            if (v.getItemsCount() > 16 || v.getItemUuidsCount() > 8
                || v.getItemsCount() + v.getItemUuidsCount() == 0 && v.getPetId() == 0 && v.getCurrencyAmount() == 0)
                throw new Refusal(INVALID);
            if (v.getCurrencyAmount() < 0 || v.getCurrencyAmount() == 0 && v.getCurrencyType() != 0)
                throw new Refusal(INVALID);
            if (v.getCurrencyAmount() > 0 && !currencies.contains(v.getCurrencyType()))
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
            if (price <= 0 || !currencies.contains(type))
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
    static void transferred(TradeCommerceWorkflow.Builder w, String role, long to, TradeCommerceLeg.Builder from) {
        add(w, role, to, 4, transfer(from.build()));
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
        if (w.getStage().equals("official_deliver")) {
            add(w, "official_credit", r.getBuyerPlayerId(), 4, w.getOfficialBundle());
            var l = leg(w, "official_credit");
            if (!done(l))
                return;
            if (applied(l)) {
                w.setStage("done");
                r.setStatus(SETTLED);
            } else {
                w.setStage("official_refund");
                r.setStatus(8);
            }
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
            add(w, "seller_receive", r.getSellerPlayerId(), 4, bundle(w.getBuyerAssets()));
        }
        if ((w.getStage().equals("open") || w.getStage().equals("lot_open")) && r.getExpiresMs() <= now)
            w.setCancelRequested(true);
        if (w.getCancelRequested()
            && (w.getStage().equals("open") || w.getStage().equals("market_open")
                || w.getStage().equals("lot_open") && w.getHighestBidder() == 0)) {
            w.setStage("return");
            r.setStatus(CANCEL_PENDING);
            AssetBundle b = transfer(leg(w, "seller_debit").build());
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
                add(w, "seller_return", r.getSellerPlayerId(), 4, b);
        }
        if (w.getStage().equals("deliver") || w.getStage().equals("return")) {
            boolean ready = true, ok = true;
            for (var l : w.getLegsBuilderList())
                if (l.getStream() == 4) {
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
