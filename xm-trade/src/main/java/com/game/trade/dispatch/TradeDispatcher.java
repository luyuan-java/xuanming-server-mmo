package com.game.trade.dispatch;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.proto.TipInfoMessage;
import com.game.proto.trade.BrowseListingsRequest;
import com.game.proto.trade.BrowseListingsResponse;
import com.game.proto.trade.GetListingDetailRequest;
import com.game.proto.trade.GetListingDetailResponse;
import com.game.proto.trade.GetMyShelfRequest;
import com.game.proto.trade.GetMyShelfResponse;
import com.game.proto.trade.SetFavoriteRequest;
import com.game.proto.trade.SetFavoriteResponse;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.metrics.TradeMetrics.RequestResult;
import com.game.trade.rules.TradeTip;
import com.game.trade.rules.TradeTips;
import com.game.trade.service.JubaozhaiService;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.Timer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按消息号把 {@link ClientCall} 派发给 {@link JubaozhaiService}（trade-spec §5.3；基线 jubaozhai_server.go 零分支委托 + 拦截器链 trade.go:328-349 +
 * 会话白名单 session.go）。
 *
 * <p>准入（顺序即代码顺序；基线 grpc-go 先解码请求再走拦截器，所以<b>解析先于身份检查</b>，§0.8、§7.4）：
 * <ol>
 *   <li>不认识的号 → 信封 1013（契约里没有）/ 1006（契约里有、不归 trade），计 {@code unrouted/unsupported}；gate 只把
 *       {@code ClientPlayerJubaozhai} 的号路由过来，正常走不到；</li>
 *   <li>解析请求体（含 proto3 非法 UTF-8）：失败 → 信封 1003，计 bad_request；</li>
 *   <li>会话没有绑定玩家（{@code player_id == 0}）→ 信封 1003，打 ERROR，计 unauthenticated（T6；基线 Unauthenticated → 路由服信封 1003）；</li>
 *   <li>上行 199 {@code TradeAdmin.SeedListing} → 信封 1003，计 forbidden（基线会话白名单 PermissionDenied；gate 本来就不收 199）；</li>
 *   <li>投递到 {@code trade-worker} 有界池。队列满、或排队已超预算 → <b>in-band 1003</b>（不带原因串；T7，同 friend 的形状但去掉英文原因）；
 *       处理器抛 RuntimeException（实现 bug：服务层已把存储 / 归属区故障转成 in-band 1003）→ in-band 1003，计 internal_error。</li>
 * </ol>
 * 解析之后的所有 in-band 失败都按该方法的拒绝形状回包：SetFavorite 回填请求里的 {@code listing_id}（jubaozhai_logic.go:273-276）。
 * 信封与 in-band tip 一律<b>不带 parameters</b>（tipOf，jubaozhai_logic.go:420-422；N5 不采纳）。
 *
 * <p>每个请求以受理时刻 + 预算（缺省 3500 ms = 基线 Timeout − 500）为截止时刻（含排队）；Dubbo 线程只做解析、身份检查与投递，处理全在工作线程上执行
 * （AGENTS.md §3）。返回的 future 永不异常完成。启动时校验：4 个方法与 199 都在 {@code message_id.txt} 里、契约里的 {@code ClientPlayerJubaozhai}
 * 没有本类不认识的方法、请求 / 应答类型与处理器一致、每个应答都有 {@code error_message}；不符即启动失败。
 */
public final class TradeDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TradeDispatcher.class);

    static final String ERROR_MESSAGE_FIELD = "error_message";

    /** 一个客户端方法的处理器。 */
    @FunctionalInterface
    interface Handler<Q extends Message> {
        Message handle(long me, Q request, Deadline deadline);
    }

    /** 该方法的 in-band 失败应答（只设 error_message；SetFavorite 另回填 listing_id）。 */
    @FunctionalInterface
    interface Rejecter<Q extends Message> {
        Message reject(Q request, TradeTip tip);
    }

    private enum Kind {
        /** 4 个 C2S：进工作线程池。 */
        CLIENT,
        /** 199：客户端不得上行，信封 1003。 */
        FORBIDDEN
    }

    /** 一个号的契约与处置。{@code handler} / {@code rejecter} 只在 {@link Kind#CLIENT} 上非空。 */
    private record Route(String method, Kind kind, Message requestPrototype, Handler<Message> handler, Rejecter<Message> rejecter) {

        ClientReply inBand(Message request, TradeTip tip) {
            return ClientReply.newBuilder().setBody(rejecter.reject(request, tip).toByteString()).build();
        }
    }

    private final MessageIdRegistry registry;
    private final Map<Integer, Route> routes;
    private final Executor executor;
    private final TradeMetrics metrics;
    private final long budgetMillis;

    /**
     * @param executor     {@code trade-worker} 工作池（测试可传同步执行器）
     * @param budgetMillis 整请求预算（{@code xm.trade.request-budget}，已校验在 [500, 3500] ms 内）
     * @throws IllegalStateException 方法在 message_id.txt 里缺号、契约里多出不认识的方法、或类型与契约不符（同步产物与代码脱节）
     */
    public TradeDispatcher(MessageIdRegistry registry, JubaozhaiService service, Executor executor, TradeMetrics metrics,
                           long budgetMillis) {
        this.registry = registry;
        this.executor = executor;
        this.metrics = metrics;
        this.budgetMillis = budgetMillis;
        Map<Integer, Route> byId = new HashMap<>();
        client(byId, TradeMethods.BROWSE_LISTINGS, BrowseListingsRequest.class, BrowseListingsResponse.class,
                service::browseListings, (request, tip) -> JubaozhaiService.rejectBrowse(tip));
        client(byId, TradeMethods.GET_LISTING_DETAIL, GetListingDetailRequest.class, GetListingDetailResponse.class,
                service::getListingDetail, (request, tip) -> JubaozhaiService.rejectDetail(tip));
        client(byId, TradeMethods.SET_FAVORITE, SetFavoriteRequest.class, SetFavoriteResponse.class,
                service::setFavorite, (request, tip) -> JubaozhaiService.rejectFavorite(request.getListingId(), tip));
        client(byId, TradeMethods.GET_MY_SHELF, GetMyShelfRequest.class, GetMyShelfResponse.class,
                service::getMyShelf, (request, tip) -> JubaozhaiService.rejectShelf(tip));
        MessageMethod seed = contractOf(TradeMethods.ADMIN_SERVICE, TradeMethods.SEED_LISTING);
        byId.put(seed.messageId(), new Route(TradeMethods.SEED_LISTING, Kind.FORBIDDEN, seed.requestPrototype(), null, null));

        Set<String> unknown = new TreeSet<>();
        for (MessageMethod m : registry.all()) {
            if (TradeMethods.SERVICE.equals(m.serviceName()) && !TradeMethods.CLIENT_REQUESTS.contains(m.methodName())) {
                unknown.add(m.methodName());
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("契约里的 " + TradeMethods.SERVICE + " 有派发器不认识的方法（先在 TradeMethods 登记处置）: "
                    + unknown);
        }
        this.routes = Collections.unmodifiableMap(byId);
    }

    private <Q extends Message, R extends Message> void client(Map<Integer, Route> byId, String method, Class<Q> requestType,
                                                               Class<R> responseType, Handler<Q> handler, Rejecter<Q> rejecter) {
        MessageMethod contract = contractOf(TradeMethods.SERVICE, method);
        if (contract.requestPrototype().getClass() != requestType || contract.responsePrototype().getClass() != responseType) {
            throw new IllegalStateException("处理器类型与契约不符: " + contract.key() + " 契约="
                    + contract.requestPrototype().getClass().getName() + " → " + contract.responsePrototype().getClass().getName()
                    + " 处理器=" + requestType.getName() + " → " + responseType.getName());
        }
        if (contract.responsePrototype().getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD) == null) {
            throw new IllegalStateException("应答类型没有 error_message 字段: " + contract.key());
        }
        Handler<Message> erasedHandler = (me, request, deadline) -> handler.handle(me, requestType.cast(request), deadline);
        Rejecter<Message> erasedRejecter = (request, tip) -> rejecter.reject(requestType.cast(request), tip);
        byId.put(contract.messageId(), new Route(method, Kind.CLIENT, contract.requestPrototype(), erasedHandler, erasedRejecter));
    }

    private MessageMethod contractOf(String service, String method) {
        int id = registry.requireId(service, method);
        return registry.byId(id).orElseThrow(() -> new IllegalStateException("契约里找不到 " + service + method));
    }

    /** 已登记的消息号（4 个 C2S + 199；测试与启动日志用）。 */
    public Set<Integer> routedMessageIds() {
        return routes.keySet();
    }

    /** 4 个 C2S 的消息号（启动日志用）。 */
    public Set<Integer> clientMessageIds() {
        Set<Integer> ids = new TreeSet<>();
        routes.forEach((id, route) -> {
            if (route.kind() == Kind.CLIENT) {
                ids.add(id);
            }
        });
        return ids;
    }

    public CompletableFuture<ClientReply> dispatch(ClientCall call) {
        Timer.Sample sample = metrics.startTimer();
        Deadline deadline = Deadline.after(budgetMillis); // 受理时刻起算，含排队（§5.4）
        Route route = routes.get(call.getMessageId());
        if (route == null) {
            metrics.requestCompleted(sample, TradeMetrics.UNROUTED, RequestResult.UNSUPPORTED);
            return CompletableFuture.completedFuture(unsupported(call));
        }
        Message request = parse(route, call);
        if (request == null) {
            metrics.requestCompleted(sample, route.method(), RequestResult.BAD_REQUEST);
            return CompletableFuture.completedFuture(envelope(TradeTips.SERVICE_UNAVAILABLE));
        }
        long me = call.hasSession() ? call.getSession().getPlayerId() : 0;
        if (me == 0) {
            log.error("[trade] 会话没有绑定玩家（未进游戏）method={} {}", route.method(), describe(call.getSession()));
            metrics.requestCompleted(sample, route.method(), RequestResult.UNAUTHENTICATED);
            return CompletableFuture.completedFuture(envelope(TradeTips.SERVICE_UNAVAILABLE));
        }
        if (route.kind() == Kind.FORBIDDEN) {
            log.warn("[trade] 客户端上行了内部方法 {}.{}（message_id={}）{}", TradeMethods.ADMIN_SERVICE, route.method(),
                    call.getMessageId(), describe(call.getSession()));
            metrics.requestCompleted(sample, route.method(), RequestResult.FORBIDDEN);
            return CompletableFuture.completedFuture(envelope(TradeTips.SERVICE_UNAVAILABLE));
        }
        CompletableFuture<ClientReply> reply = new CompletableFuture<>();
        SessionContext session = call.getSession();
        try {
            executor.execute(() -> {
                try {
                    reply.complete(handle(route, me, request, deadline, sample, session));
                } catch (Throwable t) { // handle 已兜住 RuntimeException；这里只防 Error 让 future 永不完成
                    reply.complete(route.inBand(request, TradeTip.INTERNAL_ERROR));
                    throw t;
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("[trade] 工作队列已满，拒绝请求 method={} {}", route.method(), describe(session));
            metrics.requestCompleted(sample, route.method(), RequestResult.OVERLOADED);
            reply.complete(route.inBand(request, TradeTip.OVERLOADED));
        }
        return reply;
    }

    /** 工作线程上：跑处理器，返回应答体（in-band 失败也是应答体）。 */
    private ClientReply handle(Route route, long me, Message request, Deadline deadline, Timer.Sample sample,
                               SessionContext session) {
        if (deadline.expired()) {
            log.warn("[trade] 请求在工作队列里等过了预算 method={} {}", route.method(), describe(session));
            metrics.requestCompleted(sample, route.method(), RequestResult.OVERLOADED);
            return route.inBand(request, TradeTip.OVERLOADED);
        }
        Message response;
        try {
            response = route.handler().handle(me, request, deadline);
            if (response == null) {
                throw new IllegalStateException("处理器返回了空应答");
            }
        } catch (RuntimeException e) {
            log.error("[trade] {} 处理失败（回 in-band 1003）{}", route.method(), describe(session), e);
            metrics.requestCompleted(sample, route.method(), RequestResult.INTERNAL_ERROR);
            return route.inBand(request, TradeTip.INTERNAL_ERROR);
        }
        metrics.requestCompleted(sample, route.method(), resultOf(response));
        return ClientReply.newBuilder().setBody(response.toByteString()).build();
    }

    /** 应答体的结果分类：没有 error_message（或 id 为 0）→ ok；1003（trade 唯一的 in-band 故障码）→ internal_error；其余 → business_error。 */
    static RequestResult resultOf(Message response) {
        FieldDescriptor field = response.getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD);
        if (field == null || !response.hasField(field)) {
            return RequestResult.OK;
        }
        TipInfoMessage tip = (TipInfoMessage) response.getField(field);
        if (tip.getId() == 0) {
            return RequestResult.OK;
        }
        return TradeTips.isFault(tip.getId()) ? RequestResult.INTERNAL_ERROR : RequestResult.BUSINESS_ERROR;
    }

    private static Message parse(Route route, ClientCall call) {
        try {
            return route.requestPrototype().getParserForType().parseFrom(call.getBody());
        } catch (InvalidProtocolBufferException e) {
            log.warn("[trade] 请求体解析失败 method={} {}: {}", route.method(), describe(call.getSession()), e.getMessage());
            return null;
        }
    }

    private ClientReply unsupported(ClientCall call) {
        Optional<MessageMethod> known = registry.byId(call.getMessageId());
        int tip = known.isPresent() ? TradeTips.FEATURE_UNAVAILABLE : TradeTips.MESSAGE_ID_NOT_FOUND;
        log.warn("[trade] 不处理的消息号 message_id={} 方法={} {}", call.getMessageId(),
                known.map(MessageMethod::key).orElse("<未知>"), describe(call.getSession()));
        return envelope(tip);
    }

    /** 信封：只设 tip_id，不带 parameters（同路由服 rejected，forwardlogic.go:142-155）。 */
    private static ClientReply envelope(int tipId) {
        return ClientReply.newBuilder().setTipId(tipId).build();
    }

    static String describe(SessionContext session) {
        return "gate=" + session.getGateNodeId() + " session=" + session.getSessionId()
                + " player=" + Long.toUnsignedString(session.getPlayerId());
    }

    /** 测试用：登记的方法名集合。 */
    Set<String> routedMethods() {
        Set<String> out = new HashSet<>();
        routes.values().forEach(r -> out.add(r.method()));
        return out;
    }
}
