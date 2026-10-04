package com.game.friend.dispatch;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.metrics.FriendMetrics.RequestResult;
import com.game.friend.support.Deadline;
import com.game.friend.service.FriendService;
import com.game.friend.service.RecommendService;
import com.game.proto.TipInfoMessage;
import com.game.proto.friend.AcceptFriendRequest;
import com.game.proto.friend.AddFriendRequest;
import com.game.proto.friend.BlockRequest;
import com.game.proto.friend.GetFriendListRequest;
import com.game.proto.friend.GetPendingRequestsRequest;
import com.game.proto.friend.ListBlocksRequest;
import com.game.proto.friend.RecommendFriendsRequest;
import com.game.proto.friend.RejectFriendRequest;
import com.game.proto.friend.RemoveFriendRequest;
import com.game.proto.friend.UnblockRequest;
import com.game.table.CommonErrorTip;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按消息号把 {@link ClientCall} 派发给 {@link FriendService}（spec §7.2 / §7.4）。
 *
 * <p>准入按消息号做：
 * <ul>
 *   <li>10 个 C2S 方法放行；</li>
 *   <li>上行 235（{@code NotifyFriendEvent} 是服务端推送）→ 信封 1003（基线会话白名单不含它 → PermissionDenied → 路由服信封 1003）；</li>
 *   <li>会话没有绑定玩家（{@code player_id == 0}，还没进游戏）→ 信封 1003，打 ERROR（基线会话拦截器判坏头）；</li>
 *   <li>不认识的号 → 1013（契约里没有）或 1006（契约里有、不归 friend）。</li>
 * </ul>
 * 请求体解析失败回信封 1003（与基线客户端所见一致；login 的惯例是 1014，spec D11）。工作队列满 / 处理器异常回 in-band 1003 应答体。
 *
 * <p>每个请求以受理时刻 + 预算为截止时刻（含排队）；全部处理在工作线程池上执行，Dubbo 线程只投递。返回的 future 永不异常完成。
 */
public final class FriendDispatcher {

    private static final Logger log = LoggerFactory.getLogger(FriendDispatcher.class);

    /** proto/friend/friend.proto 的客户端服务名（message_id.txt 的键前缀）。 */
    public static final String SERVICE = "ClientPlayerFriend";
    static final String NOTIFY_METHOD = "NotifyFriendEvent";
    static final String ERROR_MESSAGE_FIELD = "error_message";

    @FunctionalInterface
    interface Handler<Q extends Message> {
        Message handle(long me, Q request, Deadline deadline);
    }

    private record Route<Q extends Message>(String method, Message requestPrototype, Message responsePrototype,
                                            Class<Q> requestType, Handler<Q> handler) {

        Message invoke(long me, Message request, Deadline deadline) {
            return handler.handle(me, requestType.cast(request), deadline);
        }

        /** 应答体：只设置 error_message（每个好友应答的字段 1）。 */
        ClientReply failure(int tipId, String message) {
            Message.Builder builder = responsePrototype.newBuilderForType();
            FieldDescriptor field = builder.getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD);
            builder.setField(field, TipInfoMessage.newBuilder().setId(tipId).addParameters(message).build());
            return ClientReply.newBuilder().setBody(builder.build().toByteString()).build();
        }
    }

    private final MessageIdRegistry registry;
    private final Map<Integer, Route<?>> routes;
    private final int notifyMessageId;
    private final Executor executor;
    private final FriendMetrics metrics;
    private final long budgetMillis;

    /**
     * @throws IllegalStateException 方法在 message_id.txt 里缺号、或类型与契约不符（同步产物与代码脱节）
     */
    public FriendDispatcher(MessageIdRegistry registry, FriendService service, RecommendService recommend, Executor executor,
                            FriendMetrics metrics, long budgetMillis) {
        this.registry = registry;
        this.executor = executor;
        this.metrics = metrics;
        this.budgetMillis = budgetMillis;
        this.notifyMessageId = registry.requireId(SERVICE, NOTIFY_METHOD);
        Map<Integer, Route<?>> byId = new HashMap<>();
        add(byId, "AddFriend", AddFriendRequest.class, service::addFriend);
        add(byId, "AcceptFriend", AcceptFriendRequest.class, service::acceptFriend);
        add(byId, "RejectFriend", RejectFriendRequest.class, service::rejectFriend);
        add(byId, "RemoveFriend", RemoveFriendRequest.class, service::removeFriend);
        add(byId, "GetFriendList", GetFriendListRequest.class, service::getFriendList);
        add(byId, "GetPendingRequests", GetPendingRequestsRequest.class, service::getPendingRequests);
        add(byId, "Block", BlockRequest.class, service::block);
        add(byId, "Unblock", UnblockRequest.class, service::unblock);
        add(byId, "ListBlocks", ListBlocksRequest.class, service::listBlocks);
        add(byId, "RecommendFriends", RecommendFriendsRequest.class, recommend::recommendFriends);
        this.routes = Collections.unmodifiableMap(byId);
    }

    private <Q extends Message> void add(Map<Integer, Route<?>> byId, String method, Class<Q> requestType, Handler<Q> handler) {
        int id = registry.requireId(SERVICE, method);
        MessageMethod contract = registry.byId(id).orElseThrow();
        if (contract.requestPrototype().getClass() != requestType) {
            throw new IllegalStateException("处理器类型与契约不符: " + contract.key() + " 契约="
                    + contract.requestPrototype().getClass().getName() + " 处理器=" + requestType.getName());
        }
        Message response = contract.responsePrototype();
        if (response.getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD) == null) {
            throw new IllegalStateException("应答类型没有 error_message 字段: " + contract.key());
        }
        byId.put(id, new Route<>(method, contract.requestPrototype(), response, requestType, handler));
    }

    /** 已接管的消息号（测试与启动日志用）。 */
    public Set<Integer> routedMessageIds() {
        return routes.keySet();
    }

    /** {@code NotifyFriendEvent} 的消息号（推送用）。 */
    public int notifyMessageId() {
        return notifyMessageId;
    }

    public CompletableFuture<ClientReply> dispatch(ClientCall call) {
        var sample = metrics.startTimer();
        int messageId = call.getMessageId();
        if (messageId == notifyMessageId) {
            log.warn("[friend] 客户端上行了服务端推送的消息号 {} {}", messageId, describe(call.getSession()));
            metrics.requestCompleted(sample, NOTIFY_METHOD, RequestResult.FORBIDDEN);
            return CompletableFuture.completedFuture(envelope(CommonErrorTip.common_error.kServiceUnavailable_VALUE));
        }
        Route<?> route = routes.get(messageId);
        if (route == null) {
            metrics.requestCompleted(sample, FriendMetrics.UNROUTED, RequestResult.UNSUPPORTED);
            return CompletableFuture.completedFuture(unsupported(call));
        }
        long me = call.hasSession() ? call.getSession().getPlayerId() : 0;
        if (me == 0) {
            log.error("[friend] 会话没有绑定玩家（未进游戏）method={} {}", route.method(), describe(call.getSession()));
            metrics.requestCompleted(sample, route.method(), RequestResult.UNAUTHENTICATED);
            return CompletableFuture.completedFuture(envelope(CommonErrorTip.common_error.kServiceUnavailable_VALUE));
        }
        Deadline deadline = Deadline.after(budgetMillis);
        CompletableFuture<ClientReply> reply = new CompletableFuture<>();
        try {
            executor.execute(() -> reply.complete(handle(route, me, call, deadline, sample)));
        } catch (RejectedExecutionException e) {
            log.warn("[friend] 工作队列已满，拒绝请求 method={} {}", route.method(), describe(call.getSession()));
            metrics.requestCompleted(sample, route.method(), RequestResult.OVERLOADED);
            reply.complete(route.failure(CommonErrorTip.common_error.kServiceUnavailable_VALUE, "service overloaded"));
        }
        return reply;
    }

    private ClientReply handle(Route<?> route, long me, ClientCall call, Deadline deadline,
                               io.micrometer.core.instrument.Timer.Sample sample) {
        Message request;
        try {
            request = route.requestPrototype().getParserForType().parseFrom(call.getBody());
        } catch (InvalidProtocolBufferException e) {
            log.warn("[friend] 请求体解析失败 method={} {}: {}", route.method(), describe(call.getSession()), e.getMessage());
            metrics.requestCompleted(sample, route.method(), RequestResult.BAD_REQUEST);
            return envelope(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
        }
        if (deadline.expired()) {
            log.warn("[friend] 请求在工作队列里等过了预算 method={} {}", route.method(), describe(call.getSession()));
            metrics.requestCompleted(sample, route.method(), RequestResult.OVERLOADED);
            return route.failure(CommonErrorTip.common_error.kServiceUnavailable_VALUE, "service overloaded");
        }
        Message response;
        try {
            response = route.invoke(me, request, deadline);
        } catch (RuntimeException e) {
            log.error("[friend] {} 处理失败 {}", route.method(), describe(call.getSession()), e);
            metrics.requestCompleted(sample, route.method(), RequestResult.INTERNAL_ERROR);
            return route.failure(CommonErrorTip.common_error.kServiceUnavailable_VALUE, "storage unavailable");
        }
        metrics.requestCompleted(sample, route.method(), resultOf(response));
        return ClientReply.newBuilder().setBody(response.toByteString()).build();
    }

    static RequestResult resultOf(Message response) {
        FieldDescriptor field = response.getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD);
        if (field == null || !response.hasField(field)) {
            return RequestResult.OK;
        }
        TipInfoMessage tip = (TipInfoMessage) response.getField(field);
        return tip.getId() == CommonErrorTip.common_error.kServiceUnavailable_VALUE
                ? RequestResult.INTERNAL_ERROR : RequestResult.BUSINESS_ERROR;
    }

    private ClientReply unsupported(ClientCall call) {
        Optional<MessageMethod> known = registry.byId(call.getMessageId());
        int tip = known.isPresent()
                ? CommonErrorTip.common_error.kFeatureUnavailable_VALUE
                : CommonErrorTip.common_error.kMessageIdNotFound_VALUE;
        log.warn("[friend] 不处理的消息号 message_id={} 方法={} {}", call.getMessageId(),
                known.map(MessageMethod::key).orElse("<未知>"), describe(call.getSession()));
        return envelope(tip);
    }

    private static ClientReply envelope(int tipId) {
        return ClientReply.newBuilder().setTipId(tipId).build();
    }

    static String describe(SessionContext session) {
        return "gate=" + session.getGateNodeId() + " session=" + session.getSessionId()
                + " player=" + Long.toUnsignedString(session.getPlayerId());
    }
}
