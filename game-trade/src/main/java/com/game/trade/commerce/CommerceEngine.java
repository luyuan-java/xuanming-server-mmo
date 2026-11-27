package com.game.trade.commerce;

import static com.game.trade.commerce.CommerceRules.*;

import com.game.common.deadline.Deadline;
import com.game.proto.*;
import com.game.proto.trade.*;
import com.game.trade.store.pb.TradeAssetOpRow;
import com.game.trade.store.pb.TradeCommerceRow;
import com.google.protobuf.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** SQL合同与可靠资产腿的业务边界。成功只来自已提交玩家SQL账本，未知永不补发新seq。 */
public final class CommerceEngine {
    private static final Logger log = LoggerFactory.getLogger(CommerceEngine.class);
    /** 确定的持久数据错误，只隔离这一份合同，不能每轮阻塞全服。 */
    static class CorruptContract extends SQLException {
        CorruptContract(String message) {
            super(message);
        }
        CorruptContract(String message, Throwable cause) {
            super(message, cause);
        }
    }
    /** 参与方角色已换主或已删除（不可逆失配）：当场转人工并记 reason=owner_changed，供 redirect 改绑。 */
    static final class OwnerChangedContract extends CorruptContract {
        OwnerChangedContract(String message) {
            super(message);
        }
    }
    final CommerceStore store;
    final LongSupplier ids;
    final Policy policy;
    final LongSupplier clock;
    private final com.game.api.proto.SessionContext actor;
    private final PlayerTradeGuard playerTradeGuard;
    private final SellerCreditService credits;
    private final ReferencePrice.Reader referencePrices;
    private final WantedSkillBook.SkillCatalog wantedSkills;
    public CommerceEngine(CommerceStore store, LongSupplier ids, Policy policy, LongSupplier clock) {
        this(store, ids, policy, clock, null, null, null, null, null);
    }
    private CommerceEngine(CommerceStore store, LongSupplier ids, Policy policy, LongSupplier clock,
                            com.game.api.proto.SessionContext actor,
                            PlayerTradeGuard playerTradeGuard, SellerCreditService credits,
                            ReferencePrice.Reader referencePrices,
                            WantedSkillBook.SkillCatalog wantedSkills) {
        this.store = Objects.requireNonNull(store);
        this.ids = Objects.requireNonNull(ids);
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.actor = actor;
        this.playerTradeGuard = playerTradeGuard;
        this.credits = credits;
        this.referencePrices = referencePrices;
        this.wantedSkills = wantedSkills;
    }
    /** 挂载游戏内当面交易防骗门禁（返回共享底层账本的新引擎视图）。 */
    public CommerceEngine withPlayerTradeGuard(PlayerTradeGuard guard) {
        ReferencePrice.Reader effectivePrices = referencePrices != null ? referencePrices : (guard == null ? null : guard.prices());
        return new CommerceEngine(store, ids, policy, clock, actor, guard, credits, effectivePrices, wantedSkills);
    }
    /** 挂载卖家店铺信用服务（官方托管首次结案后幂等计入真实成交）。 */
    public CommerceEngine withSellerCreditService(SellerCreditService service) {
        return new CommerceEngine(store, ids, policy, clock, actor, playerTradeGuard, service, referencePrices, wantedSkills);
    }
    /** 挂载近 7 日真实成交中位参考价查询器。 */
    public CommerceEngine withReferencePrices(ReferencePrice.Reader reader) {
        return new CommerceEngine(store, ids, policy, clock, actor, playerTradeGuard, credits, reader, wantedSkills);
    }
    /** 挂载悬赏求书（kind 7）技能目录（未挂载时悬赏求书关闭，统一回 FEATURE_DISABLED）。 */
    public CommerceEngine withWantedSkills(WantedSkillBook.SkillCatalog catalog) {
        return new CommerceEngine(store, ids, policy, clock, actor, playerTradeGuard, credits, referencePrices, catalog);
    }
    PlayerTradeGuard playerTradeGuard() { return playerTradeGuard; }
    SellerCreditService credits() { return credits; }
    ReferencePrice.Reader referencePrices() { return referencePrices; }
    WantedSkillBook.SkillCatalog wantedSkills() { return wantedSkills; }
    /** 每次客户端调用显式绑定原会话；共享的号源/仓储不变，不存线程局部或进程级请求状态。 */
    CommerceEngine forActor(com.game.api.proto.SessionContext actor) {
        if (actor == null || actor.getPlayerId() == 0 || actor.getAccount().isEmpty() || actor.getOwnerRevision() <= 0)
            throw new Refusal(INVALID);
        return new CommerceEngine(store, ids, policy, clock, actor, playerTradeGuard, credits, referencePrices, wantedSkills);
    }
    com.game.api.proto.SessionContext actor() { return actor; }
    private String playerRequestKey(long me, String request) {
        if (actor == null) return key("player", me, request);
        if (actor.getPlayerId() != me) throw new Refusal(INVALID);
        return key("player:" + Long.toUnsignedString(actor.getOwnerRevision()) + ":"
            + HexFormat.of().formatHex(sha(actor.getAccount().getBytes(StandardCharsets.UTF_8)).toByteArray()), me, request);
    }
    private void verifyActor(java.sql.Connection c, long me) throws SQLException {
        if (actor != null) {
            if (actor.getPlayerId() != me) throw new Refusal(INVALID);
            com.game.common.player.PlayerSessionAuthority.lock(c, me, actor.getAccount(), actor.getOwnerRevision(), 2);
        }
    }
    static ByteString sha(byte[] bytes) {
        try {
            return ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
    static byte[] deterministic(Message v) {
        byte[] out = new byte[v.getSerializedSize()];
        try {
            var c = CodedOutputStream.newInstance(out);
            c.useDeterministicSerialization();
            v.writeTo(c);
            c.checkNoSpaceLeft();
            return out;
        } catch (java.io.IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    static String key(String scope, long player, String request) {
        return HexFormat.of().formatHex(
            sha((scope + ":" + Long.toUnsignedString(player) + ":" + request).getBytes(StandardCharsets.UTF_8))
                .toByteArray());
    }
    static TradeCommerceWorkflow.Builder workflow(TradeCommerceRow r) throws SQLException {
        try {
            return TradeCommerceWorkflow.parseFrom(r.getWorkflowProto()).toBuilder();
        } catch (InvalidProtocolBufferException e) {
            throw new CorruptContract("交易合同损坏", e);
        }
    }
    /** 只重放同一玩家的完整原请求；已受理合同不依赖当前拍品等易变状态。 */
    TradeCommerceRow priorRequest(long me, String request, Message input, Deadline deadline) throws SQLException {
        CommerceStore.active(deadline);
        if (me == 0 || request.length() < 8 || request.length() > 80)
            throw new Refusal(INVALID);
        var prior = store.byRequest(playerRequestKey(me, request));
        if (prior != null && !prior.getRequestDigest().equals(sha(deterministic(input))))
            throw new Refusal(REQUEST_CONFLICT);
        return prior;
    }
    @FunctionalInterface
    private interface PostLockCheck {
        void verify(Connection connection, long nowMs) throws SQLException;
    }

    TradeCommerceRow create(long me, String request, Message input, TradeCommerceRow.Builder row,
        TradeCommerceWorkflow.Builder w, Deadline deadline) throws SQLException {
        return create(me, request, input, row, w, deadline, null);
    }

    private TradeCommerceRow create(long me, String request, Message input, TradeCommerceRow.Builder row,
        TradeCommerceWorkflow.Builder w, Deadline deadline, PostLockCheck postLockCheck) throws SQLException {
        var prior = priorRequest(me, request, input, deadline);
        if (prior != null)
            return prior;
        String requestKey = playerRequestKey(me, request);
        ByteString digest = sha(deterministic(input));
        long now = clock.getAsLong();
        row.setCommerceId(ids.getAsLong())
            .setRequestKey(requestKey)
            .setRequestDigest(digest)
            .setCreatedMs(now)
            .setUpdatedMs(now)
            .setVersion(1)
            .setWorkflowProto(w.build().toByteString());
        try {
            return store.transaction(deadline, c -> {
                CommerceStore.lockAssetOwners(c, row.getSellerPlayerId(), row.getBuyerPlayerId());
                verifyActor(c, me);
                if (postLockCheck != null) {
                    postLockCheck.verify(c, now);
                }
                if (row.getSellerPlayerId() != 0) w.setSellerIdentity(CommerceStore.roleIdentity(c, row.getSellerPlayerId()));
                if (row.getBuyerPlayerId() != 0) w.setBuyerIdentity(CommerceStore.roleIdentity(c, row.getBuyerPlayerId()));
                row.setWorkflowProto(w.build().toByteString());
                row.setOnOrderBook(CommerceStore.computeOnOrderBook(row));
                TradeCommerceRow built = row.build();
                CommerceStore.insert(c, "trade_commerce", built);
                return built;
            });
        } catch (SQLException failure) {
            var stored = store.byRequest(requestKey);
            if (stored != null) {
                if (!stored.getRequestDigest().equals(digest))
                    throw new Refusal(REQUEST_CONFLICT);
                return stored;
            }
            throw failure;
        }
    }
    public TradeCommerceRow createPlayerTrade(long me, CreatePlayerTradeRequest in, Deadline d) throws SQLException {
        if (in.getBuyerPlayerId() == 0)
            throw new Refusal(INVALID);
        if (in.getBuyerPlayerId() == me)
            throw new Refusal(SELF);
        policy.selection(in.getSellerAssets());
        policy.selection(in.getBuyerAssets());
        PostLockCheck guardCheck = playerTradeGuard == null ? null : (c, nowMs) -> {
            var eval = playerTradeGuard.evaluate(
                    c, me, in.getBuyerPlayerId(), in.getSellerAssets(), in.getBuyerAssets(), nowMs);
            // shortcut: TradeCommerceWorkflow 与场景物品暂未落盘 48h 冷静锁契约，触发冷静锁时先 fail-closed 拒放，待契约扩展后升级。
            if (eval.requireCoolingLock()) {
                throw new Refusal(NOT_TRADABLE);
            }
        };
        return create(me, in.getClientRequestId(), in,
            TradeCommerceRow.newBuilder()
                .setKind(2)
                .setStatus(ESCROWING)
                .setSellerPlayerId(me)
                .setBuyerPlayerId(in.getBuyerPlayerId())
                .setExpiresMs(clock.getAsLong() + 900000),
            TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(in.getSellerAssets())
                .setBuyerAssets(in.getBuyerAssets())
                .setStage("offer_escrow"),
            d,
            guardCheck);
    }
    public TradeCommerceRow consignment(long me, CreateConsignmentRequest in, Deadline d) throws SQLException {
        policy.selection(in.getAssets());
        policy.price(in.getPrice(), in.getCurrencyType());
        if (in.getDurationMs() < 60000 || in.getDurationMs() > 30L * 86400000)
            throw new Refusal(INVALID);
        if (in.getDesignatedBuyerPlayerId() == me)
            throw new Refusal(SELF);
        return create(me, in.getClientRequestId(), in,
            TradeCommerceRow.newBuilder()
                .setKind(3)
                .setStatus(ESCROWING)
                .setSellerPlayerId(me)
                .setUnitPrice(in.getPrice())
                .setCurrencyType(in.getCurrencyType())
                .setExpiresMs(clock.getAsLong() + in.getDurationMs()),
            TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(in.getAssets())
                .setDesignatedBuyerPlayerId(in.getDesignatedBuyerPlayerId())
                .setStage("escrow"),
            d);
    }
    public TradeCommerceRow lot(long me, CreateAuctionLotRequest in, Deadline d) throws SQLException {
        policy.selection(in.getAssets());
        policy.price(in.getOpeningPrice(), in.getCurrencyType());
        if (in.getMinimumIncrement() <= 0 || in.getDurationMs() < 60000 || in.getDurationMs() > 7L * 86400000)
            throw new Refusal(INVALID);
        return create(me, in.getClientRequestId(), in,
            TradeCommerceRow.newBuilder()
                .setKind(5)
                .setStatus(ESCROWING)
                .setSellerPlayerId(me)
                .setUnitPrice(in.getOpeningPrice())
                .setCurrencyType(in.getCurrencyType())
                .setExpiresMs(clock.getAsLong() + in.getDurationMs()),
            TradeCommerceWorkflow.newBuilder()
                .setSellerAssets(in.getAssets())
                .setMinimumIncrement(in.getMinimumIncrement())
                .setStage("escrow"),
            d);
    }
    public TradeCommerceRow market(long me, PlaceMarketOrderRequest in, Deadline d) throws SQLException {
        policy.price(in.getUnitPrice(), in.getCurrencyType());
        if (in.getQuantity() <= 0 || in.getQuantity() > 4096 || in.getMarketId() != 1
            || !policy.items().contains(in.getItemConfigId()) || in.getSideValue() < 1 || in.getSideValue() > 2
            || in.getUnitPrice() > Long.MAX_VALUE / in.getQuantity()
            || policy.markets().stream().noneMatch(m
                -> m.getMarketId() == 1 && m.getItemConfigId() == in.getItemConfigId()
                    && m.getCurrencyType() == in.getCurrencyType()))
            throw new Refusal(INVALID);
        var selection = TradeAssetSelection.newBuilder();
        if (in.getSideValue() == 1)
            selection.setCurrencyType(in.getCurrencyType()).setCurrencyAmount(in.getUnitPrice() * in.getQuantity());
        else
            selection.putItems(in.getItemConfigId(), in.getQuantity());
        return create(me, in.getClientRequestId(), in,
            TradeCommerceRow.newBuilder()
                .setKind(4)
                .setStatus(ESCROWING)
                .setSellerPlayerId(me)
                .setMarketId(in.getMarketId())
                .setItemConfigId(in.getItemConfigId())
                .setSide(in.getSideValue())
                .setUnitPrice(in.getUnitPrice())
                .setQuantity(in.getQuantity())
                .setCurrencyType(in.getCurrencyType()),
            TradeCommerceWorkflow.newBuilder().setSellerAssets(selection).setStage("escrow"), d);
    }
    public TradeCommerceRow get(long me, long id) throws SQLException {
        var r = store.get(id);
        if (r == null
            || r.getSellerPlayerId() != me && r.getBuyerPlayerId() != me
                && r.getKind() != 3 && r.getKind() != 5 && r.getKind() != WantedSkillBook.KIND)
            throw new Refusal(MISSING);
        return r;
    }

    /** 悬赏求书发榜（kind 7）：无状态校验后在角色锁下数活单（至多 3 张）并建托管单。 */
    public TradeCommerceRow createWantedSkillBookOrder(long me, String posterName,
            CreateWantedSkillBookOrderRequest in, Deadline d) throws SQLException {
        if (wantedSkills == null) {
            throw new Refusal(WantedSkillBook.ERR_DISABLED);
        }
        long reward = in.getRewardGold();
        if (Long.compareUnsigned(reward, WantedSkillBook.REWARD_MIN_GOLD) < 0
                || Long.compareUnsigned(reward, WantedSkillBook.REWARD_MAX_GOLD) > 0) {
            throw new Refusal(INVALID);
        }
        if (!wantedSkills.isEligible(in.getSkillTableId())) {
            throw new Refusal(INVALID);
        }
        WantedSkillBook.SkillInfo info = wantedSkills.lookup(in.getSkillTableId())
                .orElseThrow(() -> new Refusal(INVALID));
        TradeWantedSkillBookTerms terms = TradeWantedSkillBookTerms.newBuilder()
                .setSkillTableId(in.getSkillTableId())
                .setScrollId(info.scrollId())
                .setRequireMasterSignature(in.getRequireMasterSignature())
                .setRewardGold(reward)
                .setSellerNetGold(WantedSkillBook.sellerNetGold(reward))
                .setPosterName(posterName == null ? "" : posterName)
                .build();
        PostLockCheck limitCheck = (c, nowMs) -> {
            try (var st = c.prepareStatement(
                    "SELECT commerce_id FROM trade_commerce WHERE buyer_player_id=? AND kind=? AND status NOT IN (?,?) "
                            + "ORDER BY commerce_id LIMIT ? FOR UPDATE")) {
                st.setQueryTimeout(2);
                st.setObject(1, me >= 0 ? (Object) me : new java.math.BigInteger(Long.toUnsignedString(me)));
                st.setInt(2, WantedSkillBook.KIND);
                st.setInt(3, CANCELLED);
                st.setInt(4, SETTLED);
                st.setInt(5, WantedSkillBook.MAX_ACTIVE_ORDERS);
                int active = 0;
                try (var rs = st.executeQuery()) {
                    while (rs.next()) {
                        active++;
                    }
                }
                if (active >= WantedSkillBook.MAX_ACTIVE_ORDERS) {
                    throw new Refusal(WantedSkillBook.ERR_LIMIT);
                }
            }
        };
        return create(me, in.getClientRequestId(), in,
                TradeCommerceRow.newBuilder()
                        .setKind(WantedSkillBook.KIND)
                        .setStatus(ESCROWING)
                        .setBuyerPlayerId(me)
                        .setUnitPrice(reward)
                        .setCurrencyType(com.game.proto.common.base.CurrencyType.CURRENCY_TYPE_GOLD_VALUE)
                        .setItemConfigId(WantedSkillBook.SEALED_SKILL_BOOK_CONFIG_ID),
                TradeCommerceWorkflow.newBuilder()
                        .setStage(WantedSkillBook.STAGE_ESCROW)
                        .setWantedSkillBook(terms),
                d,
                limitCheck);
    }

    /** 悬赏求书揭榜（kind 7）：同请求号重放查原揭榜结论（在途/成交回合同、不符退书回 20019、未扣到回 20015）。 */
    public TradeCommerceRow fulfillWantedSkillBookOrder(long me, long id, String requestId,
            long sealedItemId, Deadline d) throws SQLException {
        if (wantedSkills == null) {
            throw new Refusal(WantedSkillBook.ERR_DISABLED);
        }
        if (me == 0L || requestId == null || requestId.length() < 8 || requestId.length() > 80 || sealedItemId == 0L) {
            throw new Refusal(INVALID);
        }
        return store.transaction(d, c -> {
            if (actor != null) verifyActor(c, me);
            else CommerceStore.lockAssetOwners(c, me);
            var current = store.lock(c, id);
            if (current == null) {
                throw new Refusal(MISSING);
            }
            var r = current.toBuilder();
            var w = workflow(current);
            if (r.getKind() != WantedSkillBook.KIND || !w.hasWantedSkillBook()) {
                throw new Refusal(CONFLICT);
            }
            String requestKey = key(WantedSkillBook.FULFILL_SCOPE, me, requestId);
            WantedSkillBook.MatchedAttempt prior = WantedSkillBook.attemptByKey(w, requestKey);
            if (prior != null) {
                var uuids = prior.leg().getBundle().getItemUuidsList();
                if (uuids.size() != 1 || uuids.getFirst() != sealedItemId) {
                    throw new Refusal(REQUEST_CONFLICT);
                }
                if (prior.attempt() == w.getQuantityReserved() && r.getSellerPlayerId() == me) {
                    return current;
                }
                if (prior.leg().getOutcome() == 2) {
                    throw new Refusal(WantedSkillBook.ERR_MISMATCH);
                }
                throw new Refusal(NOT_TRADABLE);
            }
            if (!WantedSkillBook.STAGE_OPEN.equals(w.getStage())
                    || r.getStatus() != WAITING
                    || r.getSellerPlayerId() != 0L
                    || w.getCancelRequested()) {
                throw new Refusal(CONFLICT);
            }
            if (me == r.getBuyerPlayerId()) {
                throw new Refusal(SELF);
            }
            long actorAttempts = w.getLegsList().stream()
                    .filter(l -> l.getPlayerId() == me && l.getRole().startsWith(WantedSkillBook.DEBIT_PREFIX))
                    .count();
            if (actorAttempts >= 3 || Long.compareUnsigned(w.getQuantityReserved(), 256L) >= 0) {
                throw new Refusal(CONFLICT);
            }
            r.setSellerPlayerId(me).setStatus(ESCROWING);
            w.setSellerIdentity(CommerceStore.roleIdentity(c, me));
            long attempt = w.getQuantityReserved() + 1L;
            w.setQuantityReserved(attempt);
            add(w, WantedSkillBook.debitRole(attempt, requestKey), me, 3,
                    AssetBundle.newBuilder().addItemUuids(sealedItemId).build());
            w.setStage(WantedSkillBook.STAGE_FILL);
            r.setWorkflowProto(w.build().toByteString()).setUpdatedMs(clock.getAsLong());
            return store.save(c, r);
        });
    }

    /** 列全服挂榜中的悬赏求书单（按悬赏额降序、commerce_id 降序）。 */
    public List<TradeCommerceRow> listOpenWantedSkillBookOrders(Optional<WantedSkillBook.BoardCursor> after, int limit)
            throws SQLException {
        String base = "kind=? AND market_id=0 AND item_config_id=? AND side=0 AND status=? AND on_order_book=0";
        if (after.isEmpty()) {
            return store.list(base + " ORDER BY unit_price DESC,commerce_id DESC LIMIT ?",
                    WantedSkillBook.KIND, WantedSkillBook.SEALED_SKILL_BOOK_CONFIG_ID, WAITING, limit);
        }
        var cur = after.get();
        return store.list(base + " AND (unit_price<? OR (unit_price=? AND commerce_id<?)) "
                        + "ORDER BY unit_price DESC,commerce_id DESC LIMIT ?",
                WantedSkillBook.KIND, WantedSkillBook.SEALED_SKILL_BOOK_CONFIG_ID, WAITING,
                cur.rewardGold(), cur.rewardGold(), cur.commerceId(), limit);
    }

    /** 列本人尚未结案的悬赏求书单（按 created_ms 升序、commerce_id 升序）。 */
    public List<TradeCommerceRow> listMyWantedSkillBookOrders(long poster, Optional<WantedSkillBook.MyCursor> after, int limit)
            throws SQLException {
        long afterCreated = after.map(WantedSkillBook.MyCursor::createdMs).orElse(0L);
        long afterId = after.map(WantedSkillBook.MyCursor::commerceId).orElse(0L);
        return store.list(
                "buyer_player_id=? AND kind=? AND status NOT IN (?,?) "
                        + "AND (created_ms>? OR (created_ms=? AND commerce_id>?)) ORDER BY created_ms,commerce_id LIMIT ?",
                poster, WantedSkillBook.KIND, CANCELLED, SETTLED, afterCreated, afterCreated, afterId, limit);
    }
    public TradeCommerceRow mutate(long me, long id, String action, Deadline d, String... requestId)
        throws SQLException {
        return store.transaction(d, c -> {
            if (actor != null) verifyActor(c, me);
            else if (action.equals("buy")) CommerceStore.lockAssetOwners(c, me);
            var current = store.lock(c, id);
            if (current == null)
                throw new Refusal(MISSING);
            var r = current.toBuilder();
            var w = workflow(current);
            long now = CommerceStore.sqlNow(c);
            switch (action) {
                case "confirm" -> {
                    if (r.getKind() != 2 || !w.getStage().equals("confirm") || r.getExpiresMs() <= now
                        || w.getCancelRequested())
                        throw new Refusal(CONFLICT);
                    if (me == r.getSellerPlayerId()) {
                        if (!w.getBuyerConfirmed() || !applied(leg(w, "buyer_debit")))
                            throw new Refusal(CONFLICT);
                        w.setSellerConfirmed(true);
                    } else if (me == r.getBuyerPlayerId()) {
                        if (!applied(leg(w, "seller_debit")))
                            throw new Refusal(CONFLICT);
                        w.setBuyerConfirmed(true);
                        if (!applied(leg(w, "buyer_debit"))) {
                            w.setStage("buyer_escrow");
                            r.setStatus(ESCROWING);
                        }
                    } else
                        throw new Refusal(MISSING);
                    if (w.getSellerConfirmed() && w.getBuyerConfirmed()) {
                        w.setStage("escrow");
                        r.setStatus(ESCROWING);
                    }
                }
                case "buy" -> {
                    if (requestId.length != 1 || requestId[0].length() < 8 || requestId[0].length() > 80)
                        throw new Refusal(INVALID);
                    String requestKey = key("consignment_buy", me, requestId[0]);
                    for (var prior : w.getLegsList())
                        if (prior.getRole().endsWith("_" + requestKey)) {
                            if (r.getBuyerPlayerId() == me && List.of("pay", "deliver", "done").contains(w.getStage()))
                                return current;
                            throw new Refusal(CONFLICT);
                        }
                    if (r.getKind() == 3 && r.getBuyerPlayerId() == me
                        && List.of("pay", "deliver", "done").contains(w.getStage()))
                        return current;
                    if (r.getKind() != 3 || !w.getStage().equals("open") || r.getBuyerPlayerId() != 0
                        || r.getExpiresMs() <= now)
                        throw new Refusal(CONFLICT);
                    if (me == r.getSellerPlayerId())
                        throw new Refusal(SELF);
                    if (w.getDesignatedBuyerPlayerId() != 0 && w.getDesignatedBuyerPlayerId() != me)
                        throw new Refusal(CONFLICT);
                    // 对齐 Go r2-craft#1：单玩家最多尝试 3 次扣款、单挂单总上限 256，防止 0 金币恶意刷爆挂单。
                    long actorAttempts = w.getLegsList().stream()
                            .filter(l -> l.getPlayerId() == me && l.getRole().startsWith("buyer_debit_"))
                            .count();
                    if (actorAttempts >= 3 || w.getQuantityReserved() >= 256)
                        throw new Refusal(CONFLICT);
                    r.setBuyerPlayerId(me).setStatus(ESCROWING);
                    w.setBuyerIdentity(CommerceStore.roleIdentity(c, me));
                    w.setBuyerAssets(TradeAssetSelection.newBuilder()
                                         .setCurrencyType(r.getCurrencyType())
                                         .setCurrencyAmount(r.getUnitPrice()))
                        .setQuantityReserved(w.getQuantityReserved() + 1)
                        .setStage("pay");
                    add(w, "buyer_debit_" + Long.toUnsignedString(w.getQuantityReserved()) + "_" + requestKey, me, 3,
                        bundle(w.getBuyerAssets()));
                }
                case "cancel" -> {
                    if (me != r.getSellerPlayerId() && me != r.getBuyerPlayerId())
                        throw new Refusal(MISSING);
                    if (r.getStatus() == CANCELLED)
                        return current;
                    boolean marketCancellable = w.getStage().equals("market_open")
                            && r.getStatus() == ESCROWING
                            && Long.compareUnsigned(r.getFilledQuantity(), r.getQuantity()) < 0;
                    boolean wantedCancellable = r.getKind() == WantedSkillBook.KIND
                            && me == r.getBuyerPlayerId()
                            && (WantedSkillBook.STAGE_ESCROW.equals(w.getStage())
                                || WantedSkillBook.STAGE_OPEN.equals(w.getStage()));
                    if (r.getKind() == 2 && List.of("confirm", "offer_escrow", "buyer_escrow").contains(w.getStage())
                        || wantedCancellable
                        || me == r.getSellerPlayerId()
                            && (w.getStage().equals("open")
                                || marketCancellable
                                || w.getStage().equals("lot_open") && w.getHighestBidder() == 0)) {
                        w.setCancelRequested(true);
                        r.setStatus(CANCEL_PENDING);
                        store.expireDebits(c, id, now);
                    } else
                        throw new Refusal(CONFLICT);
                }
                default -> throw new Refusal(INVALID);
            }
            r.setWorkflowProto(w.build().toByteString()).setUpdatedMs(clock.getAsLong());
            return store.save(c, r);
        });
    }

    /**
     * 将商城报来的付款修订号单调写入官方合同（只增不减），非 MANUAL 合同回到 {@code official_deliver} 重新收敛发放。
     */
    public TradeCommerceRow applyPaymentRevision(long commerceId, int revision, Deadline d) throws SQLException {
        return store.transaction(d, c -> {
            var current = store.lock(c, commerceId);
            if (current == null) {
                throw new CorruptContract("官方订单合同不存在");
            }
            var w = workflow(current);
            if (current.getKind() != 1 || Integer.compareUnsigned(w.getPaymentRevision(), revision) >= 0) {
                return current;
            }
            var r = current.toBuilder();
            w.setPaymentRevision(revision).setPaymentRevoked((revision & 1) == 1);
            if (r.getStatus() != MANUAL) {
                w.setStage(STAGE_OFFICIAL_DELIVER);
                r.setStatus(DELIVERING);
            }
            r.setWorkflowProto(w.build().toByteString()).setUpdatedMs(clock.getAsLong());
            return store.save(c, r);
        });
    }

    /** 仅等待时间/玩家操作的合同最远复查间隔（1 小时），有腿在途或等子单收尾的兜底复查间隔（30 秒，靠事件唤醒即时推进）。 */
    static final long IDLE_RECHECK_MAX_MS = 3_600_000L;
    static final long INFLIGHT_FALLBACK_RECHECK_MS = 30_000L;

    /** 计算合同无变化时的下次调度时刻：纯等时间/玩家操作排到截止（最远 1h）；有在途腿或等子单排 30s 兜底（由事件唤醒提前）。 */
    static long nextCheckAtMs(TradeCommerceRow row, TradeCommerceWorkflow w, long nowMs) {
        String stage = w.getStage();
        boolean waitingOnlyOnTimeOrPlayer = !w.getCancelRequested()
                && (stage.equals("confirm") || stage.equals("open") || stage.equals("lot_open")
                    || stage.equals("market_open") || stage.equals(WantedSkillBook.STAGE_OPEN));
        if (waitingOnlyOnTimeOrPlayer) {
            long maxAt = nowMs + IDLE_RECHECK_MAX_MS;
            long exp = row.getExpiresMs();
            if (exp != 0L && Long.compareUnsigned(exp, nowMs) > 0) {
                return Long.compareUnsigned(exp, maxAt) < 0 ? exp : maxAt;
            }
            return maxAt;
        }
        return nowMs + INFLIGHT_FALLBACK_RECHECK_MS;
    }

    private record AdvanceOutcome(TradeCommerceRow saved, Optional<SellerCreditService.CompletedTradeFact> creditFact,
                                  List<Long> contractsToWake) {
        static final AdvanceOutcome SKIPPED = new AdvanceOutcome(null, Optional.empty(), List.of());
    }

    public TradeCommerceRow advance(TradeCommerceRow prior, Deadline d) throws SQLException {
        var pw = workflow(prior);
        store.ensureSeqs(d, prior.getSellerPlayerId(), prior.getBuyerPlayerId(), pw.getHighestBidder());
        long[] opIds = new long[8];
        for (int i = 0; i < opIds.length; i++) opIds[i] = ids.getAsLong();
        AdvanceOutcome outcome = store.transaction(d, c -> {
            // 旧未终局合同缺少原件时隔离，禁止以恢复时的当前owner补出“原身份”。
            if ((prior.getKind() >= 1 && prior.getKind() <= 5
                    || prior.getKind() == WantedSkillBook.KIND || prior.getKind() == BID_KIND)
                && (prior.getSellerPlayerId() != 0 && !pw.hasSellerIdentity()
                    || prior.getBuyerPlayerId() != 0 && !pw.hasBuyerIdentity()))
                throw new CorruptContract("交易合同缺少参与者原账号或归属修订");
            if (prior.getKind() != 6 && (pw.hasSellerIdentity() || pw.hasBuyerIdentity())) {
                var owners = new java.util.HashMap<Long, TradeRoleIdentity>(2);
                if (prior.getSellerPlayerId() != 0 && pw.hasSellerIdentity()) {
                    owners.put(prior.getSellerPlayerId(), pw.getSellerIdentity());
                }
                if (prior.getBuyerPlayerId() != 0 && pw.hasBuyerIdentity()) {
                    owners.put(prior.getBuyerPlayerId(), pw.getBuyerIdentity());
                }
                CommerceStore.lockAdvanceOwners(c, owners);
            }
            if (prior.getKind() == 6 && pw.getRmb().getCharacterPlayerId() == 0) {
                var rmbOwners = new java.util.HashMap<Long, TradeRoleIdentity>(2);
                if (Set.of("rmb_escrow", "rmb_return").contains(pw.getStage())) {
                    rmbOwners.put(prior.getSellerPlayerId(), TradeRoleIdentity.newBuilder()
                            .setAccount(pw.getRmb().getSellerAccount())
                            .setOwnerRevision(pw.getRmb().getSellerOwnerRevision())
                            .build());
                }
                if (pw.getStage().equals("rmb_deliver")) {
                    rmbOwners.put(prior.getBuyerPlayerId(), TradeRoleIdentity.newBuilder()
                            .setAccount(pw.getRmb().getBuyerAccount())
                            .setOwnerRevision(pw.getRmb().getBuyerOwnerRevision())
                            .build());
                }
                CommerceStore.lockAdvanceOwners(c, rmbOwners);
            }
            var current = store.lock(c, prior.getCommerceId());
            // 原身份与点锁来自prior；新版本必须等下一轮用自身原件取锁，不能借旧观察推进。
            if (current == null || current.getVersion() != prior.getVersion())
                return AdvanceOutcome.SKIPPED;
            var r = current.toBuilder();
            var w = workflow(current);
            for (var l : w.getLegsList())
                if (l.getOutcome() != 0 && l.getOpId() == 0)
                    throw new CorruptContract("交易资产终局缺少原操作ID，拒绝重开扣款");
            long now = CommerceStore.sqlNow(c);
            for (var l : w.getLegsBuilderList()) {
                if (l.getOpId() == 0 || l.getOutcome() != 0)
                    continue;
                var rows = store.ops(c, "op_id=?", l.getOpId());
                if (rows.size() != 1)
                    throw new CorruptContract("交易资产待办丢失");
                var op = rows.getFirst();
                // 终局按状态判定；人工终结与已证实未扣的放弃刻意不写 durable，不能把另一方托管永远扣住。
                if (op.getStatus() == 1)
                    continue;
                if (op.getStatus() < 2 || op.getStatus() > 6)
                    throw new CorruptContract("交易资产待办状态非法");
                if (op.getStatus() == 5) {
                    l.setOutcome(OUTCOME_PARTIAL);
                    markManual(r, w, MANUAL_REASON_APPLIED_PARTIAL,
                            "资产指令部分到账/部分扣出 leg=" + l.getRole() + " op_id=" + Long.toUnsignedString(op.getOpId()),
                            now);
                    r.setWorkflowProto(w.build().toByteString()).setUpdatedMs(now);
                    TradeCommerceRow quarantined = store.save(c, r);
                    return new AdvanceOutcome(quarantined, Optional.empty(), List.of());
                }
                if (op.getStatus() != 1) {
                    l.setOutcome(op.getStatus());
                    if (!op.getEscrowSnapshot().isEmpty())
                        try {
                            l.setSnapshot(AssetSnapshot.parseFrom(op.getEscrowSnapshot()));
                        } catch (InvalidProtocolBufferException e) {
                            throw new CorruptContract("托管快照损坏", e);
                        }
                    if (l.getStream() == 3 && applied(l) && !isOfficialReverseLeg(l.getRole()))
                        try {
                            transfer(l.build());
                        } catch (IllegalStateException corrupted) {
                            throw new CorruptContract("已扣出托管缺少可转交原件", corrupted);
                        }
                }
            }
            try {
                plan(r, w, now, policy);
            } catch (RuntimeException corrupted) {
                throw new CorruptContract("交易合同状态自相矛盾", corrupted);
            }
            marketBarrier(c, r, w);
            var pending = new ArrayList<>(w.getLegsBuilderList().stream().filter(l -> legReadyToSend(w, l)).toList());
            pending.sort((a, b) -> {
                int cmp = Long.compareUnsigned(a.getPlayerId(), b.getPlayerId());
                if (cmp == 0)
                    cmp = Integer.compare(a.getStream(), b.getStream());
                return cmp == 0 ? a.getRole().compareTo(b.getRole()) : cmp;
            });
            if (pending.size() > 8)
                throw new CorruptContract("单步交易腿数超过8");
            for (int i = 0; i < pending.size(); i++) {
                CommerceStore.active(d);
                var l = pending.get(i);
                l.setOpId(opIds[i]);
                var op = TradeAssetOpRow.newBuilder()
                             .setOpId(l.getOpId())
                             .setPlayerId(l.getPlayerId())
                             .setStream(l.getStream())
                             .setKind(l.getStream() == 3 ? 1 : 2)
                             .setStatus(1)
                             .setTxType(legTxType(l))
                             .setRefKind(2)
                             .setRefId(r.getCommerceId())
                             .setPayload(l.getBundle().toByteString())
                             .setNextAttemptMs(now)
                             .setCreatedMs(now)
                             .setUpdatedMs(now)
                             .setLastSendMs(now);
                if (l.getStream() == 3 && r.getExpiresMs() > 0)
                    op.setDeadlineMs(r.getExpiresMs());
                if (l.getStream() == 3 && w.getCancelRequested())
                    op.setDeadlineMs(now);
                store.enqueue(c, op);
            }
            boolean markDone = w.getStage().equals("done");
            if (markDone)
                store.markConsumed(c, r.getCommerceId());
            long engineNow = clock.getAsLong();
            TradeCommerceWorkflow builtWorkflow = w.build();
            ByteString nextWorkflowBytes = builtWorkflow.toByteString();
            r.setWorkflowProto(nextWorkflowBytes);
            boolean nextOnBook = CommerceStore.computeOnOrderBook(r);
            boolean unchanged = pending.isEmpty()
                    && !markDone
                    && r.getStatus() == current.getStatus()
                    && r.getBuyerPlayerId() == current.getBuyerPlayerId()
                    && r.getSellerPlayerId() == current.getSellerPlayerId()
                    && r.getFilledQuantity() == current.getFilledQuantity()
                    && nextOnBook == current.getOnOrderBook()
                    && nextWorkflowBytes.equals(current.getWorkflowProto());
            if (unchanged) {
                long nextCheck = nextCheckAtMs(current, builtWorkflow, engineNow);
                store.rescheduleUnchanged(c, current.getCommerceId(), current.getVersion(), nextCheck);
                return new AdvanceOutcome(current, Optional.empty(), List.of());
            }
            // 库时钟只裁决截止；updated_ms 是按引擎时钟扫描的调度键。
            r.setUpdatedMs(engineNow);
            TradeCommerceRow saved = store.save(c, r);
            Optional<SellerCreditService.CompletedTradeFact> fact = current.getStatus() != SETTLED && saved.getStatus() == SETTLED
                    ? SellerCreditService.settledTradeFact(saved, builtWorkflow, engineNow)
                    : Optional.empty();
            List<Long> toWake = new ArrayList<>(2);
            long bidToWake = finishedLotBid(current, saved, builtWorkflow);
            if (bidToWake != 0L) {
                toWake.add(bidToWake);
            }
            // 市场成交子单收尾（SETTLED/CANCELLED/MANUAL）后，唤醒买单与卖单两张母单及时收敛。
            if (saved.getKind() == 4 && saved.getSide() == 0
                    && (current.getStatus() != SETTLED && current.getStatus() != CANCELLED && current.getStatus() != MANUAL)
                    && (saved.getStatus() == SETTLED || saved.getStatus() == CANCELLED || saved.getStatus() == MANUAL)) {
                if (saved.getParentId() != 0L) {
                    toWake.add(saved.getParentId());
                }
                if (saved.getCounterpartyId() != 0L) {
                    toWake.add(saved.getCounterpartyId());
                }
            }
            return new AdvanceOutcome(saved, fact, toWake);
        });
        if (outcome != null && !outcome.contractsToWake().isEmpty()) {
            try {
                store.wakeMany(outcome.contractsToWake(), clock.getAsLong(),
                        Deadline.after(Math.min(1000, Math.max(200, d.remainingMillis()))));
            } catch (SQLException | RuntimeException wakeFailure) {
                log.warn("合同收尾后唤醒关联合同失败 targets={}", outcome.contractsToWake(), wakeFailure);
            }
        }
        if (outcome != null && outcome.creditFact().isPresent()) {
            recordSettledCreditBestEffort(outcome.creditFact().get());
        }
        return outcome == null ? null : outcome.saved();
    }

    void recordSettledCreditBestEffort(SellerCreditService.CompletedTradeFact fact) {
        if (credits == null || fact == null) {
            return;
        }
        try {
            credits.recordCompletedTrade(fact);
        } catch (SQLException | RuntimeException failure) {
            log.error("官方托管真实成交记入卖家信用失败，不阻塞主账本 order_id={} seller_id={}",
                    fact.orderId(), Long.toUnsignedString(fact.sellerPlayerId()), failure);
        }
    }
    private void marketBarrier(Connection c, TradeCommerceRow.Builder r, TradeCommerceWorkflow.Builder w)
        throws SQLException {
        if (r.getKind() != 4 || r.getSide() == 0)
            return;
        var children = store.list(
            c, "kind=4 AND side=0 AND (parent_id=? OR counterparty_id=?)", r.getCommerceId(), r.getCommerceId());
        // 成交子单转人工不再连带母单（jubaozhai-market.md §15.7.1 第 4 类）：母单只等子单进入 SETTLED/CANCELLED 终态。
        boolean pending = children.stream().anyMatch(v -> v.getStatus() != SETTLED && v.getStatus() != CANCELLED);
        if (w.getStage().equals("done") && pending) {
            w.setStage("market_wait");
            r.setStatus(CANCEL_PENDING);
        }
        if (w.getStage().equals("market_wait") && !pending) {
            w.setStage("done");
            r.setStatus(CANCELLED);
        }
        if (w.getStage().equals("market_open") && r.getQuantity() == r.getFilledQuantity() && !pending) {
            w.setStage("done");
            r.setStatus(SETTLED);
        }
    }
}
