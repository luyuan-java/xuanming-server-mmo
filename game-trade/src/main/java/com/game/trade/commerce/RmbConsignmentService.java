package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;
import static com.game.trade.commerce.RmbConsignmentClient.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.api.proto.PlayerTradeCommand;
import com.game.api.proto.PlayerTradeReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.proto.trade.*;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import java.util.function.BiConsumer;

/** 一个持久合同贯穿托管、角色冻结、商城、资产腿和原请求过户；所有网络调用均在SQL事务外。 */
public final class RmbConsignmentService {
    public static final Set<String> METHODS = Set.of("CreateRmbConsignment", "GetRmbConsignment", "ListRmbConsignments",
        "BuyRmbConsignment", "CancelRmbConsignment", "GetRmbOrder", "ListRmbOrders", "CancelRmbOrder",
        "ListSellerIncomes", "CreateWithdrawal", "GetWithdrawal");
    private final CommerceEngine e;
    private final RmbConsignmentClient mall;
    private final PlayerTradeClient players;
    private final Set<Integer> currencies;
    // 仅由唯一 CommerceRuntime 驱动；失败项尝试后前移，表尾绕回，绝不让前20离线合同饿死第21单。
    private String deliveryCursor = "", returnCursor = "";
    public RmbConsignmentService(CommerceEngine e, RmbConsignmentClient mall, PlayerTradeClient players, Set<Integer> currencies) {
        this.e = e; this.mall = mall; this.players = players;
        this.currencies = currencies == null ? Set.of(0) : Set.copyOf(currencies);
        if (this.currencies.stream().anyMatch(c -> c != 0 && c != 1)) throw new IllegalArgumentException("绑定货币禁止交易");
    }
    private PlayerTradeReply profile(long player, String account, Deadline d) throws Exception {
        if (player == 0 || account == null || account.isEmpty()) throw new Refusal(INVALID);
        var result = players.execute(PlayerTradeCommand.newBuilder().setOperation(PlayerTradeCommand.Operation.PROFILE)
            .setPlayerId(player).setSellerAccount(account), d);
        if (!result.getCode().equals("OK") || result.getOwnerRevision() <= 0 || result.getHomeZone() == 0)
            throw new Refusal(CONFLICT);
        return result;
    }
    public Message client(String method, SessionContext session, Message in, Deadline d) throws Exception {
        long me = session.getPlayerId(); String account = session.getAccount();
        var actor = profile(me, account, d);
        if (session.getOwnerRevision() <= 0 || actor.getOwnerRevision() != session.getOwnerRevision()) throw new Refusal(CONFLICT);
        return switch (method) {
            case "CreateRmbConsignment" -> answer(create(me, account, session.getOwnerRevision(), (CreateRmbConsignmentRequest) in, d));
            case "GetRmbConsignment" -> {
                long id = ((GetRmbConsignmentRequest) in).getListingId(); var local = e.store.get(id);
                yield local != null && local.getKind() == 6 && !CommerceEngine.workflow(local).getRmb().hasRegisteredListing()
                    ? answer(local) : RmbConsignmentResponse.newBuilder().setListing(listing(mall.get(id, d)))
                        .setServerNowMs(e.clock.getAsLong()).build();
            }
            case "ListRmbConsignments" -> {
                var input = (ListRmbConsignmentsRequest) in;
                var out = ListRmbConsignmentsResponse.newBuilder().setServerNowMs(e.clock.getAsLong());
                for (var item : array(mall.call("list", Map.of("afterListingId", input.getCursor(), "limit",
                        CommerceClientService.limit(input.getLimit(), 100)), d), "listings")) {
                    var value = listing(item); out.addListings(value).setNextCursor(Long.toUnsignedString(value.getListingId()));
                }
                yield out.build();
            }
            case "BuyRmbConsignment" -> {
                var input = (BuyRmbConsignmentRequest) in;
                yield orderAnswer(order(mall.call("buy", Map.of("listingId", Long.toUnsignedString(input.getListingId()),
                    "buyerPlayerId", Long.toUnsignedString(me), "buyerAccount", account, "requestId", input.getClientRequestId(),
                    "buyerOwnerRevision",Long.toUnsignedString(session.getOwnerRevision())), d)));
            }
            case "CancelRmbConsignment" -> {
                long id = ((CancelRmbConsignmentRequest) in).getListingId(); var row = e.store.get(id);
                if (row == null || row.getKind() != 6) throw new Refusal(MISSING);
                var w = CommerceEngine.workflow(row);
                if (!w.getRmb().getSellerAccount().equals(account)) throw new Refusal(MISSING);
                if (w.getRmb().hasRegisteredListing())
                    yield RmbConsignmentResponse.newBuilder().setListing(listing(mall.call("cancel",
                        Map.of("listingId", Long.toUnsignedString(id), "sellerAccount", account), d)))
                        .setServerNowMs(e.clock.getAsLong()).build();
                edit(row, d, (r, current) -> current.setCancelRequested(true));
                yield answer(e.store.get(id));
            }
            case "GetRmbOrder", "CancelRmbOrder" -> {
                String orderId = method.equals("GetRmbOrder") ? ((GetCommerceOrderRequest) in).getOrderId()
                    : ((CancelCommerceOrderRequest) in).getOrderId();
                yield orderAnswer(order(mall.call(method.equals("GetRmbOrder") ? "orders/get" : "orders/cancel",
                    Map.of("account", account, "orderId", orderId), d)));
            }
            case "ListRmbOrders" -> {
                var input = (ListCommerceOrdersRequest) in;
                var out = ListCommerceOrdersResponse.newBuilder().setServerNowMs(e.clock.getAsLong());
                for (var item : array(mall.call("orders/list", Map.of("account", account, "afterOrderId", input.getCursor(),
                        "limit", CommerceClientService.limit(input.getLimit(), 100)), d), "")) {
                    var value = order(item); out.addOrders(value).setNextCursor(value.getOrderId());
                }
                yield out.build();
            }
            case "ListSellerIncomes" -> {
                var input = (ListSellerIncomesRequest) in;
                var out = ListSellerIncomesResponse.newBuilder().setServerNowMs(e.clock.getAsLong());
                for (var item : array(mall.call("incomes", Map.of("account", account, "afterOrderId", input.getCursor(),
                        "limit", CommerceClientService.limit(input.getLimit(), 100)), d), "incomes")) {
                    out.addIncomes(RmbSellerIncome.newBuilder().setOrderId(text(item,"orderId"))
                        .setGrossFen(number(item,"grossFen")).setFeeFen(number(item,"feeFen")).setNetFen(number(item,"netFen"))
                        .setReservedFen(number(item,"reservedFen")).setPaidFen(number(item,"paidFen"))
                        .setAvailableMs(number(item,"availableMs"))).setNextCursor(text(item,"orderId"));
                }
                yield out.build();
            }
            case "CreateWithdrawal" -> {
                var input = (CreateWithdrawalRequest) in;
                yield WithdrawalResponse.newBuilder().setWithdrawal(withdrawal(mall.call("withdrawals/create",
                    Map.of("account", account, "requestId", input.getClientRequestId(), "amountFen", input.getAmountFen()), d))).build();
            }
            case "GetWithdrawal" -> WithdrawalResponse.newBuilder().setWithdrawal(withdrawal(mall.call("withdrawals/get",
                Map.of("account", account, "withdrawalId", ((GetWithdrawalRequest) in).getWithdrawalId()), d))).build();
            default -> throw new Refusal(INVALID);
        };
    }
    private TradeCommerceRow create(long me, String account, long actorRevision, CreateRmbConsignmentRequest input, Deadline d) throws Exception {
        if (input.getClientRequestId().length() < 8 || input.getClientRequestId().length() > 80
                || input.getPriceFen() <= 0 || input.getPriceFen() > 9_000_000_000_000_000L
                || input.getTitle().isBlank() || input.getTitle().length() > 128) throw new Refusal(INVALID);
        String key = CommerceEngine.key("rmb", me, input.getClientRequestId() + ":" + Long.toUnsignedString(actorRevision)
            + ":" + HexFormat.of().formatHex(CommerceEngine.sha(account.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toByteArray()));
        boolean character = input.getCharacterPlayerId() != 0;
        var selection = input.getAssets();
        String kind;
        if (character) {
            if (!selection.equals(TradeAssetSelection.getDefaultInstance())) throw new Refusal(INVALID);
            kind = "CHARACTER";
        } else {
            int categories = (selection.getItemsCount() + selection.getItemUuidsCount() > 0 ? 1 : 0)
                + (selection.getPetId() != 0 ? 1 : 0) + (selection.getCurrencyAmount() != 0 ? 1 : 0);
            if (categories != 1) throw new Refusal(INVALID);
            new Policy(e.policy.items(), e.policy.pets(), currencies, e.policy.instances(), e.policy.markets()).selection(selection);
            kind = selection.getPetId() != 0 ? "PET" : selection.getCurrencyAmount() > 0 ? "CURRENCY" : "ITEMS";
        }
        long owner = character ? input.getCharacterPlayerId() : me;
        var identity = profile(owner, account, d);
        var rmb = TradeRmbConsignmentWorkflow.newBuilder().setSellerAccount(account)
            .setSellerOwnerRevision(identity.getOwnerRevision()).setCharacterPlayerId(input.getCharacterPlayerId())
            .setMarketZone(identity.getHomeZone()).setAssetKind(kind).setTitle(input.getTitle()).setPriceFen(input.getPriceFen())
            .setClientRequestId(input.getClientRequestId());
        // 摘要绑定完整客户端原件及本次可信account/revision/zone；新账号世代不能复用旧角色的请求键。
        byte[] originalInput = CommerceEngine.deterministic(input), trustedIdentity = CommerceEngine.deterministic(rmb.build());
        ByteString digest = CommerceEngine.sha(java.nio.ByteBuffer.allocate(4 + originalInput.length + trustedIdentity.length)
            .putInt(originalInput.length).put(originalInput).put(trustedIdentity).array());
        var prior = e.store.byRequest(key);
        if (prior != null) return replay(prior, account, digest);
        long now = e.clock.getAsLong(); long id = e.ids.getAsLong();
        rmb.setEscrowReference(Long.toUnsignedString(id));
        var workflow = TradeCommerceWorkflow.newBuilder().setSellerAssets(selection).setRmb(rmb)
            .setStage(character ? "rmb_freeze" : "rmb_escrow");
        var row = TradeCommerceRow.newBuilder().setCommerceId(id).setRequestKey(key).setRequestDigest(digest).setKind(6)
            .setStatus(ESCROWING).setSellerPlayerId(owner).setUnitPrice(input.getPriceFen()).setVersion(1)
            .setCreatedMs(now).setUpdatedMs(now).setWorkflowProto(workflow.build().toByteString()).build();
        try {
            return e.store.transaction(d, c -> {
                CommerceStore.lockAssetOwners(c, owner, me);
                lockIdentity(c, me, account, actorRevision);
                lockIdentity(c, owner, account, identity.getOwnerRevision());
                CommerceStore.insert(c, "trade_commerce", row); return row;
            });
        } catch (SQLException failure) {
            var existing = e.store.byRequest(key); if (existing != null) return replay(existing, account, digest);
            throw failure;
        }
    }
    private static TradeCommerceRow replay(TradeCommerceRow row, String account, ByteString digest) throws SQLException {
        if (row.getKind() != 6 || !row.getRequestDigest().equals(digest)
                || !CommerceEngine.workflow(row).getRmb().getSellerAccount().equals(account)) throw new Refusal(REQUEST_CONFLICT);
        return row;
    }
    static long lockIdentity(Connection c, long player, String account, long revision) throws SQLException {
        com.game.common.player.PlayerSessionAuthority.lock(c, player, account, revision, 2);
        return revision;
    }
    private static CommerceOrderResponse orderAnswer(CommerceOrderView order) {
        return CommerceOrderResponse.newBuilder().setOrder(order).setServerNowMs(System.currentTimeMillis()).build();
    }
    RmbConsignmentResponse answer(TradeCommerceRow row) throws SQLException {
        var w = CommerceEngine.workflow(row); var terms = w.getRmb();
        RmbConsignmentView view = terms.hasRegisteredListing() ? terms.getRegisteredListing()
            : RmbConsignmentView.newBuilder().setListingId(row.getCommerceId()).setSellerPlayerId(row.getSellerPlayerId())
                .setMarketZone(terms.getMarketZone()).setAssetKind(terms.getAssetKind()).setTitle(terms.getTitle())
                .setPriceFen(terms.getPriceFen()).setState(row.getStatus() == CANCELLED ? "RETURNED" : "ESCROWING")
                .setPhase(w.getStage()).build();
        return RmbConsignmentResponse.newBuilder().setListing(view).setServerNowMs(e.clock.getAsLong()).build();
    }
    private void edit(TradeCommerceRow observed, Deadline d,
                      BiConsumer<TradeCommerceRow.Builder, TradeCommerceWorkflow.Builder> update) throws SQLException {
        e.store.transaction(d, c -> {
            var current = e.store.lock(c, observed.getCommerceId());
            if (current == null || current.getVersion() != observed.getVersion()) return null;
            var row = current.toBuilder(); var workflow = CommerceEngine.workflow(current);
            update.accept(row, workflow); row.setWorkflowProto(workflow.build().toByteString()).setUpdatedMs(e.clock.getAsLong());
            e.store.save(c, row); return null;
        });
    }
    private PlayerTradeReply role(TradeCommerceRow row, PlayerTradeCommand.Operation operation, Deadline d) throws Exception {
        var rmb = CommerceEngine.workflow(row).getRmb();
        return players.execute(PlayerTradeCommand.newBuilder().setOperation(operation).setListingId(row.getCommerceId())
            .setPlayerId(rmb.getCharacterPlayerId()).setSellerAccount(rmb.getSellerAccount())
            .setBuyerAccount(operation == PlayerTradeCommand.Operation.TRANSFER ? rmb.getBuyerAccount() : "")
            .setOwnerRevision(rmb.getSellerOwnerRevision()).setTxId(operation == PlayerTradeCommand.Operation.TRANSFER ? rmb.getTransferTxId() : 0), d);
    }
    /** 由唯一 CommerceRuntime 工作线程推进；每一步网络失败只保留原持久身份，不重建订单或seq。 */
    void advance(TradeCommerceRow row, Deadline d) throws Exception {
        e.advance(row, d); row = e.store.get(row.getCommerceId());
        var w = CommerceEngine.workflow(row); var rmb = w.getRmb();
        boolean character = rmb.getCharacterPlayerId() != 0;
        if (w.getStage().equals("rmb_freeze")) {
            var reply = role(row, PlayerTradeCommand.Operation.BEGIN, d); requireRole(reply);
            edit(row, d, (r, next) -> next.setStage("rmb_confirm").getRmbBuilder().setFreezeReceipt(reply.toByteString()));
        } else if (w.getStage().equals("rmb_confirm")) {
            var reply = role(row, w.getCancelRequested() ? PlayerTradeCommand.Operation.CANCEL : PlayerTradeCommand.Operation.CONFIRM, d);
            requireRole(reply);
            if (w.getCancelRequested()) {
                if (!reply.getState().equals("UNLOCKED")) throw new IllegalStateException("原冻结尚未解锁");
                finish(row, CANCELLED, d);
            } else {
                if (!reply.getState().equals("FROZEN")) throw new IllegalStateException("角色尚未完成持久冻结");
                edit(row, d, (r, next) -> next.setStage("rmb_register").getRmbBuilder()
                    .setEscrowProof(HexFormat.of().formatHex(CommerceEngine.sha(rmb.getFreezeReceipt().toByteArray()).toByteArray())));
            }
        } else if (w.getStage().equals("rmb_register")) {
            // 该阶段可能已经跨过 Mall 提交点；即使同时撤单也必须重放原注册，再由 Mall 登记撤单。
            // 禁止把 HTTP 未知误认为“尚未上架”，否则一份资产会同时可买又已退回卖家。
            String proof = rmb.getEscrowProof();
            if (proof.isEmpty()) {
                var debit = leg(w, "seller_debit");
                if (!applied(debit)) throw new IllegalStateException("商城注册缺少真实托管");
                proof = HexFormat.of().formatHex(CommerceEngine.sha(CommerceEngine.deterministic(debit.build())).toByteArray());
                String original = proof; edit(row, d, (r, next) -> next.getRmbBuilder().setEscrowProof(original));
                return;
            }
            Map<String,Object> registration = new LinkedHashMap<>();
            registration.put("listingId", Long.toUnsignedString(row.getCommerceId()));
            registration.put("sellerPlayerId", Long.toUnsignedString(row.getSellerPlayerId())); registration.put("sellerAccount", rmb.getSellerAccount());
            registration.put("marketZone", Integer.toUnsignedLong(rmb.getMarketZone())); registration.put("assetKind", rmb.getAssetKind());
            registration.put("assetCurrencyType", rmb.getAssetKind().equals("CURRENCY") ? w.getSellerAssets().getCurrencyType() : null);
            registration.put("escrowReference", rmb.getEscrowReference()); registration.put("escrowProof", proof);
            registration.put("priceFen", rmb.getPriceFen()); registration.put("title", rmb.getTitle());
            var accepted = listing(mall.call("register", registration, d));
            if (accepted.getListingId() != row.getCommerceId() || accepted.getSellerPlayerId() != row.getSellerPlayerId()
                    || accepted.getPriceFen() != rmb.getPriceFen() || !accepted.getAssetKind().equals(rmb.getAssetKind()))
                throw new SecurityException("商城注册回执与原托管不符");
            edit(row, d, (r, next) -> { next.getRmbBuilder().setRegisteredListing(accepted); next.setStage("rmb_open");
                r.setStatus(next.getCancelRequested() ? CANCEL_PENDING : 2); });
        } else if (w.getStage().equals("rmb_open") && w.getCancelRequested()) {
            try {
                var cancellation = listing(mall.call("cancel", Map.of("listingId", Long.toUnsignedString(row.getCommerceId()),
                    "sellerAccount", rmb.getSellerAccount()), d));
                if (!Set.of("RETURNING", "RETURNED").contains(cancellation.getState()))
                    throw new IllegalStateException("商城尚未登记原寄售撤单");
                edit(row, d, (r, next) -> { next.setStage(character ? "rmb_character_return" : "rmb_return");
                    r.setStatus(CANCEL_PENDING); });
            } catch (OfficialMallClient.HttpFailure failure) {
                if (failure.status() != 409) throw failure;
                // 购买已先在商城提交；不能退回仍在交付的资产，交付扫描继续推进原订单。
                edit(row, d, (r, next) -> { next.setCancelRequested(false); r.setStatus(2); });
            }
        } else if (w.getStage().equals("rmb_transfer")) {
            var receipt = role(row, PlayerTradeCommand.Operation.TRANSFER, d); requireRole(receipt);
            if (!receipt.getState().equals("TRANSFERRED") || receipt.getOwnerRevision() != rmb.getSellerOwnerRevision() + 1)
                throw new SecurityException("角色过户回执不符");
            edit(row, d, (r, next) -> next.setStage("rmb_receipt"));
        } else if (w.getStage().equals("rmb_character_return")) {
            var receipt = role(row, PlayerTradeCommand.Operation.CANCEL, d); requireRole(receipt);
            if (!receipt.getState().equals("UNLOCKED")) throw new IllegalStateException("角色返还排空未完");
            edit(row, d, (r, next) -> next.setStage("rmb_return_receipt"));
        } else if (w.getStage().equals("rmb_receipt") || w.getStage().equals("rmb_refund")) {
            mall.httpReceipt(rmb.getMallOrderId(), row.getCommerceId(), CommerceEngine.sha(CommerceEngine.deterministic(w.build())),
                w.getStage().equals("rmb_receipt") ? "DELIVERED" : "REFUND_PENDING", d);
            if (w.getStage().equals("rmb_receipt")) finish(row, SETTLED, d);
            else edit(row, d, (r, next) -> { next.setStage(character ? "rmb_character_return" : "rmb_return"); r.setStatus(CANCEL_PENDING); });
        } else if (w.getStage().equals("rmb_return_receipt")) {
            if (rmb.hasRegisteredListing()) mall.call("returns/receipt", Map.of("listingId", Long.toUnsignedString(row.getCommerceId()),
                "escrowReference", rmb.getEscrowReference(), "escrowProof", rmb.getEscrowProof(), "returnProof",
                HexFormat.of().formatHex(CommerceEngine.sha(CommerceEngine.deterministic(w.build())).toByteArray())), d);
            finish(row, CANCELLED, d);
        }
    }
    private static void requireRole(PlayerTradeReply reply) {
        if (!reply.getCode().equals("OK")) throw new IllegalStateException("角色权威尚未完成：" + reply.getCode());
    }
    /** 原意图摘要只含 intent；分页包装不进入合同。每轮限20，未知保留原 seq/tx。 */
    void poll(Deadline d) throws Exception {
        Exception first = null;
        var deliveries = OfficialMallClient.deliveryPage(mall.call("deliveries/poll",
            Map.of("limit", 20, "cursor", deliveryCursor), d), deliveryCursor);
        if (deliveries.isEmpty()) deliveryCursor = "";
        for (var entry : deliveries) {
            if (d.remainingMillis() < 400) break;
            try { bindDelivery(entry.intent(), d); }
            catch (Exception unknown) { if (first == null) first = unknown; else first.addSuppressed(unknown); }
            deliveryCursor = entry.cursor();
            if (Thread.currentThread().isInterrupted()) break;
        }
        if (d.remainingMillis() >= 400 && !Thread.currentThread().isInterrupted()) {
            var entries = array(mall.call("returns/poll", Map.of("limit", 20, "cursor", returnCursor), d), "entries");
            if (entries.size() > 20) throw new IllegalStateException("商城返还页越界");
            String previous = returnCursor; var seen = new HashSet<String>();
            // 整页先验证扫描包装，再做任何副作用。
            for (var entry : entries) {
                String id = text(entry.path("intent"), "listingId"), cursor = text(entry, "cursor");
                CommerceClientService.id(id);
                if (!seen.add(id)) throw new IllegalStateException("商城返还重复商品");
                if (!cursor.isEmpty()) {
                    String[] next = returnKey(cursor), old = previous.isEmpty() ? null : returnKey(previous);
                    if (!next[1].equals(id) || next[1].compareTo(next[2]) > 0 || old != null
                        && (!next[2].equals(old[2]) || next[1].compareTo(old[1]) <= 0))
                        throw new IllegalStateException("商城返还游标乱序");
                } else if (entry != entries.get(entries.size() - 1)) throw new IllegalStateException("商城返还页提前绕回");
                previous = cursor;
            }
            if (entries.isEmpty()) returnCursor = "";
            for (var entry : entries) {
                if (d.remainingMillis() < 400) break;
                try { bindReturn(entry.get("intent"), d); }
                catch (Exception unknown) { if (first == null) first = unknown; else first.addSuppressed(unknown); }
                returnCursor = text(entry, "cursor");
                if (Thread.currentThread().isInterrupted()) break;
            }
        }
        if (first != null) throw first;
    }
    private static String[] returnKey(String cursor) {
        String[] parts = cursor.split(":", -1);
        if (parts.length != 3 || !parts[0].equals("r1")) throw new IllegalStateException("返还游标格式非法");
        CommerceClientService.id(parts[1]); CommerceClientService.id(parts[2]); return parts;
    }
    private void verifyEscrow(JsonNode intent, TradeCommerceRow row, TradeRmbConsignmentWorkflow original) {
        if (!text(intent,"escrowReference").equals(original.getEscrowReference())
            || !text(intent,"escrowProof").equals(original.getEscrowProof()) || !original.hasRegisteredListing()
            || CommerceClientService.id(text(intent,"listingId")) != row.getCommerceId())
            throw new SecurityException("商城托管原件不符");
    }
    private void bindDelivery(JsonNode intent, Deadline d) throws Exception {
        long id = CommerceClientService.id(text(intent,"listingId"));
        long buyer = CommerceClientService.id(text(intent,"buyerPlayerId"));
        long originalRevision = CommerceClientService.id(text(intent,"buyerOwnerRevision"));
        String account = text(intent,"buyerAccount"), order = text(intent,"orderId");
        var digest = CommerceEngine.sha(intent.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long txId = e.ids.getAsLong();
        e.store.transaction(d, c -> {
            boolean changed = false;
            try { com.game.common.player.PlayerSessionAuthority.lock(c,buyer,account,originalRevision,2); }
            catch (SecurityException ownershipChanged) { changed = true; }
            var row = e.store.lock(c, id); if (row == null || row.getKind() != 6) throw new Refusal(MISSING);
            var w = CommerceEngine.workflow(row); var original = w.getRmb(); verifyEscrow(intent, row, original);
            if (number(intent,"amountFen") != original.getPriceFen()) throw new SecurityException("商城金额与原托管不符");
            if (!original.getMallOrderId().isEmpty()) {
                if (!original.getMallOrderId().equals(order) || !original.getBuyerAccount().equals(account)
                    || row.getBuyerPlayerId() != buyer || original.getBuyerOwnerRevision() != originalRevision
                    || !original.getDeliveryDigest().equals(digest))
                    throw new SecurityException("商城交付原请求发生变化");
                return null;
            }
            if (!w.getStage().equals("rmb_open") || original.getSellerAccount().equals(account)) throw new Refusal(CONFLICT);
            w.getRmbBuilder().setMallOrderId(order).setBuyerAccount(account).setBuyerOwnerRevision(originalRevision)
                .setDeliveryDigest(digest).setTransferTxId(txId);
            w.setStage(changed ? "rmb_refund" : original.getCharacterPlayerId() != 0 ? "rmb_transfer" : "rmb_deliver");
            e.store.save(c, row.toBuilder().setBuyerPlayerId(buyer).setStatus(changed ? 8 : DELIVERING)
                .setUpdatedMs(e.clock.getAsLong()).setWorkflowProto(w.build().toByteString()));
            return null;
        });
    }
    private void bindReturn(JsonNode intent, Deadline d) throws Exception {
        long id = CommerceClientService.id(text(intent,"listingId"));
        var observed = e.store.get(id); if (observed == null || observed.getKind() != 6) throw new Refusal(MISSING);
        var original = CommerceEngine.workflow(observed).getRmb(); verifyEscrow(intent, observed, original);
        if (!text(intent,"sellerAccount").equals(original.getSellerAccount())
            || CommerceClientService.id(text(intent,"sellerPlayerId")) != observed.getSellerPlayerId())
            throw new SecurityException("返还原账号不符");
        edit(observed, d, (row, workflow) -> {
            if (workflow.getStage().equals("rmb_open")) {
                workflow.setStage(original.getCharacterPlayerId() != 0 ? "rmb_character_return" : "rmb_return");
                row.setStatus(CANCEL_PENDING);
            }
        });
    }
    private void finish(TradeCommerceRow observed, int state, Deadline d) throws SQLException {
        e.store.transaction(d, c -> {
            var current = e.store.lock(c, observed.getCommerceId());
            if (current == null || current.getVersion() != observed.getVersion()) return null;
            var w = CommerceEngine.workflow(current).setStage("done");
            e.store.markConsumed(c, current.getCommerceId());
            e.store.save(c, current.toBuilder().setStatus(state).setUpdatedMs(e.clock.getAsLong()).setWorkflowProto(w.build().toByteString()));
            return null;
        });
    }
}
