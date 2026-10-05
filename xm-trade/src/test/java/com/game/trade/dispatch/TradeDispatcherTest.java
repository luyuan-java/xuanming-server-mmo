package com.game.trade.dispatch;

import static com.game.trade.service.TradeServiceFixture.PLAYER_A;
import static com.game.trade.service.TradeServiceFixture.PLAYER_B;
import static com.game.trade.service.TradeServiceFixture.ZONE_A;
import static com.game.trade.service.TradeServiceFixture.onSale;
import static com.game.trade.service.TradeServiceFixture.storeDown;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.contract.TradeContractFixtures;
import com.game.proto.TipInfoMessage;
import com.game.proto.trade.BrowseListingsResponse;
import com.game.proto.trade.GetListingDetailRequest;
import com.game.proto.trade.GetListingDetailResponse;
import com.game.proto.trade.GetMyShelfRequest;
import com.game.proto.trade.GetMyShelfResponse;
import com.game.proto.trade.ListingSection;
import com.game.proto.trade.MarketScope;
import com.game.proto.trade.SeedListingRequest;
import com.game.proto.trade.SetFavoriteRequest;
import com.game.proto.trade.SetFavoriteResponse;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.rules.TradeTips;
import com.game.trade.service.JubaozhaiService;
import com.game.trade.service.TradeServiceFixture;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 派发器的准入与错误映射（trade-spec §5.3、§9.2 TradeDispatcherTest；基线 session_test.go 的准入用例、forwardlogic.go 的信封形状）：
 * 4 个号的路由、199 → 信封 1003（forbidden）、player_id = 0 → 信封 1003、解析失败 → 信封 1003（且先于身份判定）、队列满 / 排队超预算 →
 * in-band 1003、处理器异常 → in-band 1003、1013 与 1006、启动时缺号即失败、任何应答的 tip 都不带 parameters、SetFavorite 的 in-band 失败回填
 * listing_id、成功应答没有 error_message。
 */
class TradeDispatcherTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final ClientReply ENVELOPE_1003 = ClientReply.newBuilder().setTipId(TradeTips.SERVICE_UNAVAILABLE).build();
    private static final ByteString BAD_BODY = ByteString.copyFrom(new byte[] {(byte) 0xFF, 0x01});

    private final TradeServiceFixture f = new TradeServiceFixture();

    private static int id(String method) {
        return REGISTRY.requireId(TradeMethods.SERVICE, method);
    }

    private static int seedId() {
        return REGISTRY.requireId(TradeMethods.ADMIN_SERVICE, TradeMethods.SEED_LISTING);
    }

    private TradeDispatcher dispatcher(JubaozhaiService service, Executor executor, long budgetMillis) {
        return new TradeDispatcher(REGISTRY, service, executor, f.metrics, budgetMillis);
    }

    private TradeDispatcher dispatcher() {
        return dispatcher(f.service(MarketScope.MARKET_SCOPE_ZONE), Runnable::run, 3500);
    }

    private static ClientCall call(int messageId, long playerId, ByteString body) {
        return ClientCall.newBuilder().setMessageId(messageId).setBody(body).setRequestId(77)
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(9).setPlayerId(playerId)).build();
    }

    private static ClientCall call(String method, long playerId, Message body) {
        return call(id(method), playerId, body.toByteString());
    }

    private double requests(String method, String result) {
        return f.meters.get("xm.trade.requests").tag("method", method).tag("result", result).timer().count();
    }

    private static ClientReply done(CompletableFuture<ClientReply> future) {
        assertThat(future).isDone();
        return future.join();
    }

    private static TipInfoMessage inBandTip(ClientReply reply, Message prototype) throws InvalidProtocolBufferException {
        assertThat(reply.getTipId()).as("in-band：信封 tip 必须为 0").isZero();
        Message body = prototype.getParserForType().parseFrom(reply.getBody());
        TipInfoMessage tip = (TipInfoMessage) body.getField(body.getDescriptorForType().findFieldByName("error_message"));
        assertThat(tip.getParametersList()).as("tip 永不带 parameters").isEmpty();
        return tip;
    }

    // ================================================================ 路由与启动校验

    @Test
    void 接管4个客户端号与199_契约里的ClientPlayerJubaozhai恰好就是这4个() {
        TradeDispatcher d = dispatcher();
        Set<Integer> expected = new HashSet<>();
        for (String method : TradeMethods.CLIENT_REQUESTS) {
            expected.add(id(method));
        }
        assertThat(expected).containsExactlyInAnyOrder(196, 197, 198, 200);
        assertThat(d.clientMessageIds()).isEqualTo(expected);
        expected.add(seedId());
        assertThat(seedId()).isEqualTo(199);
        assertThat(d.routedMessageIds()).isEqualTo(expected);

        Set<String> contract = new HashSet<>();
        for (MessageMethod m : REGISTRY.all()) {
            if (TradeMethods.SERVICE.equals(m.serviceName())) {
                contract.add(m.methodName());
                assertThat(m.responsePrototype().getDescriptorForType().findFieldByName("error_message")).as(m.key()).isNotNull();
            }
        }
        assertThat(contract).containsExactlyInAnyOrderElementsOf(TradeMethods.CLIENT_REQUESTS);
    }

    @Test
    void 启动校验_契约缺号即失败() {
        for (String key : List.of(TradeMethods.SERVICE + TradeMethods.BROWSE_LISTINGS, TradeMethods.SERVICE + TradeMethods.SET_FAVORITE,
                TradeMethods.SERVICE + TradeMethods.GET_MY_SHELF, TradeMethods.ADMIN_SERVICE + TradeMethods.SEED_LISTING)) {
            MessageIdRegistry broken = TradeContractFixtures.registryWithout(key);
            assertThatThrownBy(() -> new TradeDispatcher(broken, f.service(MarketScope.MARKET_SCOPE_ZONE), Runnable::run, f.metrics, 3500))
                    .as(key).isInstanceOf(IllegalStateException.class);
        }
    }

    // ================================================================ 正常路由与结果分类

    @Test
    void 四个方法都路由到服务_成功应答没有error_message_计ok() throws Exception {
        f.store.listings.put(11L, onSale(11, PLAYER_A, ZONE_A));
        TradeDispatcher d = dispatcher();

        ClientReply browse = done(d.dispatch(call(TradeMethods.BROWSE_LISTINGS, PLAYER_B, TradeServiceFixture.browse().build())));
        ClientReply detail = done(d.dispatch(call(TradeMethods.GET_LISTING_DETAIL, PLAYER_B,
                GetListingDetailRequest.newBuilder().setListingId(11).build())));
        ClientReply favorite = done(d.dispatch(call(TradeMethods.SET_FAVORITE, PLAYER_B,
                SetFavoriteRequest.newBuilder().setListingId(11).setFavorite(true).build())));
        ClientReply shelf = done(d.dispatch(call(TradeMethods.GET_MY_SHELF, PLAYER_A, GetMyShelfRequest.getDefaultInstance())));

        assertThat(List.of(browse, detail, favorite, shelf)).allMatch(r -> r.getTipId() == 0 && r.getTipParametersCount() == 0);
        assertThat(BrowseListingsResponse.parseFrom(browse.getBody()).hasErrorMessage()).isFalse();
        assertThat(BrowseListingsResponse.parseFrom(browse.getBody()).getServerNowMs()).isEqualTo(TradeServiceFixture.NOW_MS);
        GetListingDetailResponse detailBody = GetListingDetailResponse.parseFrom(detail.getBody());
        assertThat(detailBody.hasErrorMessage()).isFalse();
        assertThat(detailBody.getDetail().getSummary().getListingId()).isEqualTo(11);
        SetFavoriteResponse favoriteBody = SetFavoriteResponse.parseFrom(favorite.getBody());
        assertThat(favoriteBody.hasErrorMessage()).isFalse();
        assertThat(favoriteBody.getFavorite()).isTrue();
        assertThat(GetMyShelfResponse.parseFrom(shelf.getBody()).hasErrorMessage()).isFalse();

        for (String method : TradeMethods.CLIENT_REQUESTS) {
            assertThat(requests(method, "ok")).as(method).isEqualTo(1);
        }
    }

    @Test
    void 业务拒绝计business_error_in_band故障计internal_error() throws Exception {
        TradeDispatcher d = dispatcher();

        ClientReply auction = done(d.dispatch(call(TradeMethods.BROWSE_LISTINGS, PLAYER_B,
                TradeServiceFixture.browse().setSection(ListingSection.LISTING_SECTION_AUCTION).build())));
        assertThat(inBandTip(auction, BrowseListingsResponse.getDefaultInstance()).getId()).isEqualTo(TradeTips.FEATURE_DISABLED);
        assertThat(requests(TradeMethods.BROWSE_LISTINGS, "business_error")).isEqualTo(1);

        f.store.failOn.put("CountSellerListings", storeDown());
        ClientReply shelf = done(d.dispatch(call(TradeMethods.GET_MY_SHELF, PLAYER_A, GetMyShelfRequest.getDefaultInstance())));
        assertThat(inBandTip(shelf, GetMyShelfResponse.getDefaultInstance()).getId()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);
        assertThat(requests(TradeMethods.GET_MY_SHELF, "internal_error")).isEqualTo(1);
    }

    @Test
    void SetFavorite服务层拒绝回填listing_id() throws Exception {
        ClientReply reply = done(dispatcher().dispatch(call(TradeMethods.SET_FAVORITE, PLAYER_B,
                SetFavoriteRequest.newBuilder().setListingId(424242).setFavorite(true).build())));

        assertThat(inBandTip(reply, SetFavoriteResponse.getDefaultInstance()).getId()).isEqualTo(TradeTips.LISTING_NOT_FOUND);
        assertThat(SetFavoriteResponse.parseFrom(reply.getBody()).getListingId()).isEqualTo(424242);
    }

    // ================================================================ 准入：信封

    @Test
    void 上行199回信封1003_计forbidden_不碰库() {
        ClientReply reply = done(dispatcher().dispatch(call(seedId(), PLAYER_B,
                TradeServiceFixture.seedRequest().build().toByteString())));

        assertThat(reply).isEqualTo(ENVELOPE_1003);
        assertThat(requests(TradeMethods.SEED_LISTING, "forbidden")).isEqualTo(1);
        assertThat(f.store.calls).isEmpty();
    }

    @Test
    void 会话player_id为0或没有会话_信封1003_计unauthenticated() {
        TradeDispatcher d = dispatcher();

        ClientReply zero = done(d.dispatch(call(TradeMethods.GET_MY_SHELF, 0, GetMyShelfRequest.getDefaultInstance())));
        ClientReply none = done(d.dispatch(ClientCall.newBuilder().setMessageId(id(TradeMethods.GET_MY_SHELF)).setRequestId(5).build()));
        ClientReply seed = done(d.dispatch(call(seedId(), 0, SeedListingRequest.getDefaultInstance().toByteString())));

        assertThat(zero).isEqualTo(ENVELOPE_1003);
        assertThat(none).isEqualTo(ENVELOPE_1003);
        assertThat(seed).as("199 也先判身份（同基线拦截器顺序）").isEqualTo(ENVELOPE_1003);
        assertThat(requests(TradeMethods.GET_MY_SHELF, "unauthenticated")).isEqualTo(2);
        assertThat(requests(TradeMethods.SEED_LISTING, "unauthenticated")).isEqualTo(1);
        assertThat(f.store.calls).isEmpty();
    }

    @Test
    void 请求体解析失败回信封1003_且先于身份判定() {
        TradeDispatcher d = dispatcher();

        ClientReply bad = done(d.dispatch(call(id(TradeMethods.BROWSE_LISTINGS), PLAYER_B, BAD_BODY)));
        ClientReply badNoPlayer = done(d.dispatch(call(id(TradeMethods.SET_FAVORITE), 0, BAD_BODY)));

        assertThat(bad).isEqualTo(ENVELOPE_1003);
        assertThat(badNoPlayer).isEqualTo(ENVELOPE_1003);
        assertThat(requests(TradeMethods.BROWSE_LISTINGS, "bad_request")).isEqualTo(1);
        assertThat(requests(TradeMethods.SET_FAVORITE, "bad_request")).as("解析先于身份：计 bad_request 而不是 unauthenticated").isEqualTo(1);
        assertThat(requests(TradeMethods.SET_FAVORITE, "unauthenticated")).isZero();
        assertThat(f.store.calls).isEmpty();
    }

    @Test
    void 不认识的号_契约里没有回1013_有但不归trade回1006() {
        TradeDispatcher d = dispatcher();
        int otherDomain = REGISTRY.requireId("ClientPlayerFriend", "AddFriend");

        ClientReply missing = done(d.dispatch(call(999_999, PLAYER_B, ByteString.EMPTY)));
        ClientReply foreign = done(d.dispatch(call(otherDomain, PLAYER_B, ByteString.EMPTY)));

        assertThat(missing).isEqualTo(ClientReply.newBuilder().setTipId(TradeTips.MESSAGE_ID_NOT_FOUND).build());
        assertThat(foreign).isEqualTo(ClientReply.newBuilder().setTipId(TradeTips.FEATURE_UNAVAILABLE).build());
        assertThat(requests(TradeMetrics.UNROUTED, "unsupported")).isEqualTo(2);
    }

    // ================================================================ 过载与处理器异常：in-band 1003

    @Test
    void 工作队列满回in_band1003不带原因串_SetFavorite回填listing_id() throws Exception {
        Executor full = task -> {
            throw new RejectedExecutionException("队列满");
        };
        TradeDispatcher d = dispatcher(f.service(MarketScope.MARKET_SCOPE_ZONE), full, 3500);

        ClientReply browse = done(d.dispatch(call(TradeMethods.BROWSE_LISTINGS, PLAYER_B, TradeServiceFixture.browse().build())));
        ClientReply favorite = done(d.dispatch(call(TradeMethods.SET_FAVORITE, PLAYER_B,
                SetFavoriteRequest.newBuilder().setListingId(77).setFavorite(true).build())));

        assertThat(inBandTip(browse, BrowseListingsResponse.getDefaultInstance()).getId()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);
        assertThat(inBandTip(favorite, SetFavoriteResponse.getDefaultInstance()).getId()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);
        assertThat(SetFavoriteResponse.parseFrom(favorite.getBody()).getListingId()).isEqualTo(77);
        assertThat(BrowseListingsResponse.parseFrom(browse.getBody()).getListingsCount()).isZero();
        assertThat(requests(TradeMethods.BROWSE_LISTINGS, "overloaded")).isEqualTo(1);
        assertThat(requests(TradeMethods.SET_FAVORITE, "overloaded")).isEqualTo(1);
        assertThat(f.store.calls).isEmpty();
    }

    @Test
    void 排队超过预算回in_band1003_不调用服务() throws Exception {
        Executor slow = task -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            task.run();
        };
        TradeDispatcher d = dispatcher(f.service(MarketScope.MARKET_SCOPE_ZONE), slow, 10);

        ClientReply reply = done(d.dispatch(call(TradeMethods.GET_MY_SHELF, PLAYER_A, GetMyShelfRequest.getDefaultInstance())));

        assertThat(inBandTip(reply, GetMyShelfResponse.getDefaultInstance()).getId()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);
        assertThat(requests(TradeMethods.GET_MY_SHELF, "overloaded")).isEqualTo(1);
        assertThat(f.store.calls).isEmpty();
    }

    @Test
    void 处理器抛RuntimeException回in_band1003_计internal_error() throws Exception {
        TradeServiceFixture broken = new TradeServiceFixture();
        JubaozhaiService service = new JubaozhaiService(broken.store, broken.homes, broken.metrics,
                TradeServiceFixture.market(MarketScope.MARKET_SCOPE_GLOBAL), () -> {
                    throw new IllegalStateException("时钟坏了");
                });
        TradeDispatcher d = new TradeDispatcher(REGISTRY, service, Runnable::run, f.metrics, 3500);

        ClientReply favorite = done(d.dispatch(call(TradeMethods.SET_FAVORITE, PLAYER_B,
                SetFavoriteRequest.newBuilder().setListingId(31).setFavorite(true).build())));
        ClientReply shelf = done(d.dispatch(call(TradeMethods.GET_MY_SHELF, PLAYER_A, GetMyShelfRequest.getDefaultInstance())));

        assertThat(inBandTip(favorite, SetFavoriteResponse.getDefaultInstance()).getId()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);
        assertThat(SetFavoriteResponse.parseFrom(favorite.getBody()).getListingId()).isEqualTo(31);
        assertThat(inBandTip(shelf, GetMyShelfResponse.getDefaultInstance()).getId()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);
        assertThat(requests(TradeMethods.SET_FAVORITE, "internal_error")).isEqualTo(1);
        assertThat(requests(TradeMethods.GET_MY_SHELF, "internal_error")).isEqualTo(1);
    }

    @Test
    void 处理器抛Error时future照样完成() throws Exception {
        TradeServiceFixture broken = new TradeServiceFixture();
        JubaozhaiService service = new JubaozhaiService(broken.store, broken.homes, broken.metrics,
                TradeServiceFixture.market(MarketScope.MARKET_SCOPE_GLOBAL), () -> {
                    throw new AssertionError("模拟 Error");
                });
        AtomicReference<Throwable> escaped = new AtomicReference<>();
        Executor catching = task -> {
            try {
                task.run();
            } catch (Throwable t) {
                escaped.set(t);
            }
        };
        TradeDispatcher d = new TradeDispatcher(REGISTRY, service, catching, f.metrics, 3500);

        ClientReply reply = done(d.dispatch(call(TradeMethods.GET_MY_SHELF, PLAYER_A, GetMyShelfRequest.getDefaultInstance())));

        assertThat(inBandTip(reply, GetMyShelfResponse.getDefaultInstance()).getId()).isEqualTo(TradeTips.SERVICE_UNAVAILABLE);
        assertThat(escaped.get()).as("Error 照样抛给线程池").isInstanceOf(AssertionError.class);
    }

    @Test
    void 结果分类() {
        assertThat(TradeDispatcher.resultOf(BrowseListingsResponse.getDefaultInstance())).isEqualTo(TradeMetrics.RequestResult.OK);
        assertThat(TradeDispatcher.resultOf(BrowseListingsResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(0)).build())).isEqualTo(TradeMetrics.RequestResult.OK);
        assertThat(TradeDispatcher.resultOf(BrowseListingsResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(TradeTips.HOME_ZONE_UNKNOWN)).build()))
                .isEqualTo(TradeMetrics.RequestResult.BUSINESS_ERROR);
        assertThat(TradeDispatcher.resultOf(BrowseListingsResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(TradeTips.SERVICE_UNAVAILABLE)).build()))
                .isEqualTo(TradeMetrics.RequestResult.INTERNAL_ERROR);
    }
}
