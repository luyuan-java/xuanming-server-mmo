package com.game.team.dispatch;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.proto.Empty;
import com.game.proto.TipInfoMessage;
import com.game.proto.team.ApplyJoinTeamRequest;
import com.game.proto.team.CreateTeamRequest;
import com.game.proto.team.DisbandTeamRequest;
import com.game.proto.team.GetMyTeamRequest;
import com.game.proto.team.HandleApplicationRequest;
import com.game.proto.team.InviteToTeamRequest;
import com.game.proto.team.KickMemberRequest;
import com.game.proto.team.LeaveTeamRequest;
import com.game.proto.team.ListMyInvitesRequest;
import com.game.proto.team.RespondInviteRequest;
import com.game.proto.team.StartTeamMatchRequest;
import com.game.proto.team.TeamEventS2C;
import com.game.proto.team.TeamInviteS2C;
import com.game.proto.team.TeamSnapshotS2C;
import com.game.proto.team.TransferLeaderRequest;
import com.game.table.CommonErrorTip;
import com.game.team.metrics.TeamMetrics;
import com.game.team.metrics.TeamMetrics.RequestResult;
import com.game.team.rules.TeamTips;
import com.game.team.service.TeamMethods;
import com.game.team.service.TeamService;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.Timer;
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
 * 按消息号把 {@link ClientCall} 派发给 {@link TeamService}（team-spec §6.3；基线 go/match/internal/team/server.go）。
 *
 * <p>准入（顺序即代码顺序）：
 * <ol>
 *   <li>不认识的号 → 信封 1013（契约里没有）/ 1006（契约里有、不归 team）；gate 只把 {@code ClientPlayerTeam} 的号路由过来，正常走不到；</li>
 *   <li><b>先解析请求体</b>：失败 → 信封 1003（基线 gRPC 解码发生在拦截器与业务之前，路由服把解码错误翻成信封 1003；§8.2 第 9 条）；</li>
 *   <li>上行 203 / 213 / 215（服务端推送的号）→ {@code ClientReply{tip_id=0}}、空 body：不读不写、不看身份（基线回 Empty，D13；
 *       gate 按 Empty 应答类型不回包）；</li>
 *   <li>会话没有绑定玩家（{@code player_id == 0}）→ <b>in-band</b> {@code TeamResponse{4001}} / {@code ListMyInvitesResponse{4001}}，
 *       不带视图（基线 server.go:95-99、:153-157）。<b>与好友不同</b>：好友回信封 1003，组队照抄会让客户端停用本连接的组队（§8.2 第 1 条）；</li>
 *   <li>投递到 {@code team-worker} 有界池；队列满、排队已超预算、处理器抛异常 → in-band 4030，不带视图、不带 parameters（D12）。</li>
 * </ol>
 *
 * <p>每个请求以受理时刻 + 预算（缺省 3500 ms）为截止时刻（含排队）；Dubbo 线程只做解析、身份检查与投递，处理全在工作线程上执行。
 * 返回的 future 永不异常完成。启动时校验 15 个方法都在 {@code message_id.txt} 里、请求类型与契约一致、12 个 C2S 应答都有
 * {@code error_message} 字段、3 个推送占位的应答是 Empty；缺号或不符即启动失败（同步产物与代码脱节）。
 */
public final class TeamDispatcher {

    private static final Logger log = LoggerFactory.getLogger(TeamDispatcher.class);

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

        /** in-band 应答体：只设置 error_message{id}（不带视图、不带 parameters）。 */
        ClientReply inBand(int tipId) {
            Message.Builder builder = responsePrototype.newBuilderForType();
            FieldDescriptor field = builder.getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD);
            builder.setField(field, TipInfoMessage.newBuilder().setId(tipId).build());
            return ClientReply.newBuilder().setBody(builder.build().toByteString()).build();
        }
    }

    /** 推送占位：只解析请求体（与基线一样先解码），不处理。 */
    private record PushRoute(String method, Message requestPrototype) {
    }

    private final MessageIdRegistry registry;
    private final Map<Integer, Route<?>> routes;
    private final Map<Integer, PushRoute> pushRoutes;
    private final Executor executor;
    private final TeamMetrics metrics;
    private final long budgetMillis;

    /**
     * @param executor     {@code team-worker} 工作池（测试可传同步执行器）
     * @param budgetMillis 整请求预算（{@code xm.team.request-budget}，已校验在 [500, 3500] ms 内）
     * @throws IllegalStateException 方法在 message_id.txt 里缺号、或类型与契约不符
     */
    public TeamDispatcher(MessageIdRegistry registry, TeamService service, Executor executor, TeamMetrics metrics,
                          long budgetMillis) {
        this.registry = registry;
        this.executor = executor;
        this.metrics = metrics;
        this.budgetMillis = budgetMillis;
        Map<Integer, Route<?>> byId = new HashMap<>();
        add(byId, TeamMethods.CREATE_TEAM, CreateTeamRequest.class, service::createTeam);
        add(byId, TeamMethods.GET_MY_TEAM, GetMyTeamRequest.class, service::getMyTeam);
        add(byId, TeamMethods.APPLY_JOIN_TEAM, ApplyJoinTeamRequest.class, service::applyJoinTeam);
        add(byId, TeamMethods.HANDLE_APPLICATION, HandleApplicationRequest.class, service::handleApplication);
        add(byId, TeamMethods.INVITE_TO_TEAM, InviteToTeamRequest.class, service::inviteToTeam);
        add(byId, TeamMethods.RESPOND_INVITE, RespondInviteRequest.class, service::respondInvite);
        add(byId, TeamMethods.LIST_MY_INVITES, ListMyInvitesRequest.class, service::listMyInvites);
        add(byId, TeamMethods.LEAVE_TEAM, LeaveTeamRequest.class, service::leaveTeam);
        add(byId, TeamMethods.KICK_MEMBER, KickMemberRequest.class, service::kickMember);
        add(byId, TeamMethods.TRANSFER_LEADER, TransferLeaderRequest.class, service::transferLeader);
        add(byId, TeamMethods.DISBAND_TEAM, DisbandTeamRequest.class, service::disbandTeam);
        add(byId, TeamMethods.START_TEAM_MATCH, StartTeamMatchRequest.class, service::startTeamMatch);
        if (!byId.values().stream().map(Route::method).toList().containsAll(TeamMethods.REQUESTS)) {
            throw new IllegalStateException("组队 C2S 方法没有全部接管: " + TeamMethods.REQUESTS);
        }
        this.routes = Collections.unmodifiableMap(byId);
        Map<Integer, PushRoute> pushes = new HashMap<>();
        addPush(pushes, TeamMethods.NOTIFY_TEAM_SNAPSHOT, TeamSnapshotS2C.class);
        addPush(pushes, TeamMethods.NOTIFY_TEAM_INVITE, TeamInviteS2C.class);
        addPush(pushes, TeamMethods.NOTIFY_TEAM_EVENT, TeamEventS2C.class);
        this.pushRoutes = Collections.unmodifiableMap(pushes);
    }

    private <Q extends Message> void add(Map<Integer, Route<?>> byId, String method, Class<Q> requestType, Handler<Q> handler) {
        MessageMethod contract = contractOf(method, requestType);
        Message response = contract.responsePrototype();
        if (response.getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD) == null) {
            throw new IllegalStateException("应答类型没有 error_message 字段: " + contract.key());
        }
        byId.put(contract.messageId(), new Route<>(method, contract.requestPrototype(), response, requestType, handler));
    }

    private void addPush(Map<Integer, PushRoute> byId, String method, Class<? extends Message> requestType) {
        MessageMethod contract = contractOf(method, requestType);
        if (contract.responsePrototype().getClass() != Empty.class) {
            throw new IllegalStateException("推送占位的应答类型不是 Empty: " + contract.key());
        }
        byId.put(contract.messageId(), new PushRoute(method, contract.requestPrototype()));
    }

    private MessageMethod contractOf(String method, Class<? extends Message> requestType) {
        int id = registry.requireId(TeamMethods.SERVICE, method);
        MessageMethod contract = registry.byId(id).orElseThrow();
        if (contract.requestPrototype().getClass() != requestType) {
            throw new IllegalStateException("处理器类型与契约不符: " + contract.key() + " 契约="
                    + contract.requestPrototype().getClass().getName() + " 处理器=" + requestType.getName());
        }
        return contract;
    }

    /** 已接管的 12 个 C2S 消息号（测试与启动日志用）。 */
    public Set<Integer> routedMessageIds() {
        return routes.keySet();
    }

    /** 3 个推送占位的消息号。 */
    public Set<Integer> pushMessageIds() {
        return pushRoutes.keySet();
    }

    public CompletableFuture<ClientReply> dispatch(ClientCall call) {
        Timer.Sample sample = metrics.startTimer();
        int messageId = call.getMessageId();
        PushRoute push = pushRoutes.get(messageId);
        if (push != null) {
            if (parse(push.requestPrototype(), call) == null) {
                metrics.requestCompleted(sample, push.method(), RequestResult.BAD_REQUEST);
                return CompletableFuture.completedFuture(envelope(CommonErrorTip.common_error.kServiceUnavailable_VALUE));
            }
            log.warn("[team] 客户端上行了服务端推送的消息号 {}（空操作）{}", messageId, describe(call.getSession()));
            metrics.requestCompleted(sample, push.method(), RequestResult.FORBIDDEN);
            return CompletableFuture.completedFuture(ClientReply.getDefaultInstance());
        }
        Route<?> route = routes.get(messageId);
        if (route == null) {
            metrics.requestCompleted(sample, TeamMetrics.UNROUTED, RequestResult.UNSUPPORTED);
            return CompletableFuture.completedFuture(unsupported(call));
        }
        Message request = parse(route.requestPrototype(), call);
        if (request == null) {
            metrics.requestCompleted(sample, route.method(), RequestResult.BAD_REQUEST);
            return CompletableFuture.completedFuture(envelope(CommonErrorTip.common_error.kServiceUnavailable_VALUE));
        }
        long me = call.hasSession() ? call.getSession().getPlayerId() : 0;
        if (me == 0) {
            log.warn("[team] 会话没有绑定玩家（未进游戏）method={} {}", route.method(), describe(call.getSession()));
            metrics.requestCompleted(sample, route.method(), RequestResult.UNAUTHENTICATED);
            return CompletableFuture.completedFuture(route.inBand(TeamTips.PLAYER_ID));
        }
        Deadline deadline = Deadline.after(budgetMillis);
        CompletableFuture<ClientReply> reply = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    reply.complete(handle(route, me, request, deadline, sample, call.getSession()));
                } catch (Throwable t) { // handle 已兜住 RuntimeException；这里只防 Error 让 future 永不完成
                    reply.complete(route.inBand(TeamTips.INTERNAL));
                    throw t;
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("[team] 工作队列已满，拒绝请求 method={} {}", route.method(), describe(call.getSession()));
            metrics.requestCompleted(sample, route.method(), RequestResult.OVERLOADED);
            reply.complete(route.inBand(TeamTips.INTERNAL));
        }
        return reply;
    }

    private ClientReply handle(Route<?> route, long me, Message request, Deadline deadline, Timer.Sample sample,
                               SessionContext session) {
        if (deadline.expired()) {
            log.warn("[team] 请求在工作队列里等过了预算 method={} {}", route.method(), describe(session));
            metrics.requestCompleted(sample, route.method(), RequestResult.OVERLOADED);
            return route.inBand(TeamTips.INTERNAL);
        }
        Message response;
        try {
            response = route.invoke(me, request, deadline);
        } catch (RuntimeException e) {
            log.error("[team] {} 处理失败 {}", route.method(), describe(session), e);
            metrics.requestCompleted(sample, route.method(), RequestResult.INTERNAL_ERROR);
            return route.inBand(TeamTips.INTERNAL);
        }
        metrics.requestCompleted(sample, route.method(), resultOf(response));
        return ClientReply.newBuilder().setBody(response.toByteString()).build();
    }

    /** 应答体的结果分类：没有 error_message → ok；4030（team 段唯一的故障码）→ internal_error；其余 → business_error。 */
    static RequestResult resultOf(Message response) {
        FieldDescriptor field = response.getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD);
        if (field == null || !response.hasField(field)) {
            return RequestResult.OK;
        }
        TipInfoMessage tip = (TipInfoMessage) response.getField(field);
        if (tip.getId() == TeamTips.OK) {
            return RequestResult.OK;
        }
        return TeamTips.isFault(tip.getId()) ? RequestResult.INTERNAL_ERROR : RequestResult.BUSINESS_ERROR;
    }

    private static Message parse(Message prototype, ClientCall call) {
        try {
            return prototype.getParserForType().parseFrom(call.getBody());
        } catch (InvalidProtocolBufferException e) {
            log.warn("[team] 请求体解析失败 message_id={} {}: {}", call.getMessageId(), describe(call.getSession()),
                    e.getMessage());
            return null;
        }
    }

    private ClientReply unsupported(ClientCall call) {
        Optional<MessageMethod> known = registry.byId(call.getMessageId());
        int tip = known.isPresent()
                ? CommonErrorTip.common_error.kFeatureUnavailable_VALUE
                : CommonErrorTip.common_error.kMessageIdNotFound_VALUE;
        log.warn("[team] 不处理的消息号 message_id={} 方法={} {}", call.getMessageId(),
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
