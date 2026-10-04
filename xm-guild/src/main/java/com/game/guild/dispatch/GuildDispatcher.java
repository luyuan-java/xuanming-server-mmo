package com.game.guild.dispatch;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.contract.MessageIdRegistry;
import com.game.contract.MessageMethod;
import com.game.guild.metrics.GuildMetrics;
import com.game.guild.metrics.GuildMetrics.RequestResult;
import com.game.guild.rules.GuildTip;
import com.game.guild.rules.GuildTips;
import com.game.guild.service.GuildManageService;
import com.game.guild.service.GuildRankService;
import com.game.guild.service.GuildService;
import com.game.proto.Empty;
import com.game.proto.TipInfoMessage;
import com.game.proto.guild.ApplyJoinGuildRequest;
import com.game.proto.guild.CancelGuildApplicationRequest;
import com.game.proto.guild.CreateGuildRequest;
import com.game.proto.guild.DisbandGuildRequest;
import com.game.proto.guild.GetGuildRankByGuildRequest;
import com.game.proto.guild.GetGuildRankRequest;
import com.game.proto.guild.GetGuildRequest;
import com.game.proto.guild.GetPlayerGuildRequest;
import com.game.proto.guild.KickGuildMemberRequest;
import com.game.proto.guild.LeaveGuildRequest;
import com.game.proto.guild.ListGuildApplicationsRequest;
import com.game.proto.guild.ListMyGuildApplicationsRequest;
import com.game.proto.guild.ReviewGuildApplicationRequest;
import com.game.proto.guild.SetAnnouncementRequest;
import com.game.proto.guild.SetGuildMemberRoleRequest;
import com.game.proto.guild.TransferGuildLeaderRequest;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.Timer;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
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
 * 按消息号把 {@link ClientCall} 派发给帮会服务（guild-spec §7.3；基线 guild_server.go 零分支委托 + 拦截器链 guild.go:454-498 +
 * 会话白名单 session.go）。
 *
 * <p>准入（顺序即代码顺序；基线 gRPC 的解码发生在拦截器之前，所以<b>解析先于身份检查</b>）：
 * <ol>
 *   <li>不认识的号 → 信封 1013（契约里没有）/ 1006（契约里有、不归 guild）；gate 只把 GuildService 的号路由过来，正常走不到；</li>
 *   <li>解析请求体（含 proto3 非法 UTF-8）：失败 → 信封 1003，计 bad_request；</li>
 *   <li>会话没有绑定玩家（{@code player_id == 0}）→ 信封 1003，打 ERROR（基线 Unauthenticated；<b>与 team 不同</b>，同 friend）；</li>
 *   <li>上行 8 UpdateGuildScore / 220 NotifyGuildChanged → 信封 1003，计 forbidden（基线会话白名单 PermissionDenied；220 的应答是 Empty，
 *       但 tip ≠ 0 时 gate 照样回信封）；</li>
 *   <li>4.5 / 4.6 的 10 个号 → <b>in-band 1006</b>（写进各自应答的 error_message，D13）：客户端进捐献 / 商店页会自动拉 120 / 228，
 *       信封会让整个帮会模块进隔离；</li>
 *   <li>投递到 {@code guild-worker} 有界池。队列满、或排队已超预算 → <b>in-band 14021「guild service overloaded」</b>（已拍板，代替 D11 的信封：
 *       瞬时过载不该让客户端停用帮会模块）；处理器抛异常（依赖故障、配表缺行、双存储矛盾、存储内部错误）→ <b>信封 1003</b>、记 ERROR
 *       （基线 gRPC error → 路由服信封 1003；与 friend 的 in-band 1003 不同，客户端随之进隔离）。</li>
 * </ol>
 * 信封一律不带 parameters（同路由服 rejected，forwardlogic.go:225-232）；in-band tip 带基线英文原因串。
 *
 * <p>每个请求以受理时刻 + 预算（缺省 3500 ms）为截止时刻（含排队）；Dubbo 线程只做解析、身份检查与投递，处理全在工作线程上执行
 * （AGENTS.md §3）。返回的 future 永不异常完成。启动时校验 28 个方法都在 {@code message_id.txt} 里、契约里的 GuildService 没有本类不认识的方法、
 * 16 个处理器的请求类型与契约一致、除 220 外每个应答都有 {@code error_message} 字段、220 的应答是 Empty；不符即启动失败。
 */
public final class GuildDispatcher {

    private static final Logger log = LoggerFactory.getLogger(GuildDispatcher.class);

    static final String ERROR_MESSAGE_FIELD = "error_message";

    @FunctionalInterface
    interface Handler<Q extends Message> {
        Message handle(long me, Q request, Deadline deadline);
    }

    /** 一个号的契约与处置。{@code handler} 只在 {@link Kind#CLIENT} 上非空。 */
    private record Route(String method, Kind kind, Message requestPrototype, Message responsePrototype,
                         Handler<Message> handler) {

        /** in-band 应答体：只设置 error_message（每个帮会应答的字段 1）。 */
        ClientReply inBand(TipInfoMessage tip) {
            Message.Builder builder = responsePrototype.newBuilderForType();
            FieldDescriptor field = builder.getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD);
            builder.setField(field, tip);
            return ClientReply.newBuilder().setBody(builder.build().toByteString()).build();
        }
    }

    private enum Kind {
        /** 4.4 的 16 个 C2S：进工作线程池。 */
        CLIENT,
        /** 8 / 220：客户端不得上行，信封 1003。 */
        FORBIDDEN,
        /** 4.5 / 4.6 的 10 个号：in-band 1006。 */
        PLACEHOLDER
    }

    private final MessageIdRegistry registry;
    private final Map<Integer, Route> routes;
    private final Executor executor;
    private final GuildMetrics metrics;
    private final long budgetMillis;

    /**
     * @param executor     {@code guild-worker} 工作池（测试可传同步执行器）
     * @param budgetMillis 整请求预算（{@code xm.guild.request-budget}，已校验在 [500, 3500] ms 内）
     * @throws IllegalStateException 方法在 message_id.txt 里缺号、契约里多出不认识的方法、或类型与契约不符（同步产物与代码脱节）
     */
    public GuildDispatcher(MessageIdRegistry registry, GuildService guilds, GuildManageService manage,
                           GuildRankService ranks, Executor executor, GuildMetrics metrics, long budgetMillis) {
        this.registry = registry;
        this.executor = executor;
        this.metrics = metrics;
        this.budgetMillis = budgetMillis;
        Map<Integer, Route> byId = new HashMap<>();
        client(byId, GuildMethods.CREATE_GUILD, CreateGuildRequest.class, guilds::createGuild);
        client(byId, GuildMethods.GET_GUILD, GetGuildRequest.class, guilds::getGuild);
        client(byId, GuildMethods.GET_PLAYER_GUILD, GetPlayerGuildRequest.class, guilds::getPlayerGuild);
        client(byId, GuildMethods.LEAVE_GUILD, LeaveGuildRequest.class, guilds::leaveGuild);
        client(byId, GuildMethods.DISBAND_GUILD, DisbandGuildRequest.class, guilds::disbandGuild);
        client(byId, GuildMethods.SET_ANNOUNCEMENT, SetAnnouncementRequest.class, guilds::setAnnouncement);
        client(byId, GuildMethods.SET_GUILD_MEMBER_ROLE, SetGuildMemberRoleRequest.class, manage::setGuildMemberRole);
        client(byId, GuildMethods.KICK_GUILD_MEMBER, KickGuildMemberRequest.class, manage::kickGuildMember);
        client(byId, GuildMethods.TRANSFER_GUILD_LEADER, TransferGuildLeaderRequest.class, manage::transferGuildLeader);
        client(byId, GuildMethods.APPLY_JOIN_GUILD, ApplyJoinGuildRequest.class, manage::applyJoinGuild);
        client(byId, GuildMethods.CANCEL_GUILD_APPLICATION, CancelGuildApplicationRequest.class,
                manage::cancelGuildApplication);
        client(byId, GuildMethods.LIST_MY_GUILD_APPLICATIONS, ListMyGuildApplicationsRequest.class,
                manage::listMyGuildApplications);
        client(byId, GuildMethods.LIST_GUILD_APPLICATIONS, ListGuildApplicationsRequest.class,
                manage::listGuildApplications);
        client(byId, GuildMethods.REVIEW_GUILD_APPLICATION, ReviewGuildApplicationRequest.class,
                manage::reviewGuildApplication);
        client(byId, GuildMethods.GET_GUILD_RANK, GetGuildRankRequest.class, ranks::getGuildRank);
        client(byId, GuildMethods.GET_GUILD_RANK_BY_GUILD, GetGuildRankByGuildRequest.class, ranks::getGuildRankByGuild);
        for (String method : GuildMethods.FORBIDDEN) {
            other(byId, method, Kind.FORBIDDEN);
        }
        for (String method : GuildMethods.PLACEHOLDERS) {
            other(byId, method, Kind.PLACEHOLDER);
        }
        Set<String> handled = new HashSet<>();
        byId.values().forEach(r -> handled.add(r.method()));
        if (!handled.containsAll(GuildMethods.CLIENT_REQUESTS) || byId.size() != GuildMethods.all().size()) {
            throw new IllegalStateException("帮会方法没有全部登记: " + GuildMethods.all() + " 已登记 " + handled);
        }
        Set<String> unknown = new TreeSet<>();
        for (MessageMethod m : registry.all()) {
            if (GuildMethods.SERVICE.equals(m.serviceName()) && !handled.contains(m.methodName())) {
                unknown.add(m.methodName());
            }
        }
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("契约里的 " + GuildMethods.SERVICE + " 有派发器不认识的方法（先在 GuildMethods 登记处置）: "
                    + unknown);
        }
        this.routes = Collections.unmodifiableMap(byId);
    }

    private <Q extends Message> void client(Map<Integer, Route> byId, String method, Class<Q> requestType, Handler<Q> handler) {
        MessageMethod contract = contractOf(method);
        if (contract.requestPrototype().getClass() != requestType) {
            throw new IllegalStateException("处理器类型与契约不符: " + contract.key() + " 契约="
                    + contract.requestPrototype().getClass().getName() + " 处理器=" + requestType.getName());
        }
        requireErrorMessage(contract);
        Handler<Message> erased = (me, request, deadline) -> handler.handle(me, requestType.cast(request), deadline);
        byId.put(contract.messageId(), new Route(method, Kind.CLIENT, contract.requestPrototype(),
                contract.responsePrototype(), erased));
    }

    private void other(Map<Integer, Route> byId, String method, Kind kind) {
        MessageMethod contract = contractOf(method);
        if (GuildMethods.NOTIFY_GUILD_CHANGED.equals(method)) {
            if (contract.responsePrototype().getClass() != Empty.class) {
                throw new IllegalStateException("推送占位的应答类型不是 Empty: " + contract.key());
            }
        } else {
            requireErrorMessage(contract);
        }
        byId.put(contract.messageId(), new Route(method, kind, contract.requestPrototype(), contract.responsePrototype(),
                null));
    }

    private MessageMethod contractOf(String method) {
        int id = registry.requireId(GuildMethods.SERVICE, method);
        return registry.byId(id).orElseThrow(() -> new IllegalStateException("契约里找不到 " + GuildMethods.SERVICE + method));
    }

    private static void requireErrorMessage(MessageMethod contract) {
        if (contract.responsePrototype().getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD) == null) {
            throw new IllegalStateException("应答类型没有 error_message 字段: " + contract.key());
        }
    }

    /** 已登记的 28 个消息号（测试与启动日志用）。 */
    public Set<Integer> routedMessageIds() {
        return routes.keySet();
    }

    /** 某一组的消息号（启动日志用）。 */
    public Set<Integer> messageIdsOf(List<String> methods) {
        Set<Integer> ids = new TreeSet<>();
        routes.forEach((id, r) -> {
            if (methods.contains(r.method())) {
                ids.add(id);
            }
        });
        return ids;
    }

    public CompletableFuture<ClientReply> dispatch(ClientCall call) {
        Timer.Sample sample = metrics.startTimer();
        Route route = routes.get(call.getMessageId());
        if (route == null) {
            metrics.requestCompleted(sample, GuildMetrics.UNROUTED, RequestResult.UNSUPPORTED);
            return CompletableFuture.completedFuture(unsupported(call));
        }
        Message request = parse(route, call);
        if (request == null) {
            metrics.requestCompleted(sample, route.method(), RequestResult.BAD_REQUEST);
            return CompletableFuture.completedFuture(envelope(GuildTips.SERVICE_UNAVAILABLE));
        }
        long me = call.hasSession() ? call.getSession().getPlayerId() : 0;
        if (me == 0) {
            log.error("[guild] 会话没有绑定玩家（未进游戏）method={} {}", route.method(), describe(call.getSession()));
            metrics.requestCompleted(sample, route.method(), RequestResult.UNAUTHENTICATED);
            return CompletableFuture.completedFuture(envelope(GuildTips.SERVICE_UNAVAILABLE));
        }
        switch (route.kind()) {
            case FORBIDDEN -> {
                log.warn("[guild] 客户端上行了不开放给客户端的方法 {}（message_id={}）{}", route.method(), call.getMessageId(),
                        describe(call.getSession()));
                metrics.requestCompleted(sample, route.method(), RequestResult.FORBIDDEN);
                return CompletableFuture.completedFuture(envelope(GuildTips.SERVICE_UNAVAILABLE));
            }
            case PLACEHOLDER -> {
                metrics.requestCompleted(sample, route.method(), RequestResult.UNSUPPORTED);
                return CompletableFuture.completedFuture(route.inBand(GuildTip.FEATURE_UNAVAILABLE.proto()));
            }
            case CLIENT -> {
                // 往下走
            }
        }
        Deadline deadline = Deadline.after(budgetMillis);
        CompletableFuture<ClientReply> reply = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    reply.complete(handle(route, me, request, deadline, sample, call.getSession()));
                } catch (Throwable t) { // handle 已兜住 RuntimeException；这里只防 Error 让 future 永不完成
                    reply.complete(envelope(GuildTips.SERVICE_UNAVAILABLE));
                    throw t;
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("[guild] 工作队列已满，拒绝请求 method={} {}", route.method(), describe(call.getSession()));
            metrics.requestCompleted(sample, route.method(), RequestResult.OVERLOADED);
            reply.complete(route.inBand(GuildTip.OVERLOADED.proto()));
        }
        return reply;
    }

    private ClientReply handle(Route route, long me, Message request, Deadline deadline, Timer.Sample sample,
                               SessionContext session) {
        if (deadline.expired()) {
            log.warn("[guild] 请求在工作队列里等过了预算 method={} {}", route.method(), describe(session));
            metrics.requestCompleted(sample, route.method(), RequestResult.OVERLOADED);
            return route.inBand(GuildTip.OVERLOADED.proto());
        }
        Message response;
        try {
            response = route.handler().handle(me, request, deadline);
        } catch (RuntimeException e) {
            log.error("[guild] {} 处理失败（回信封 1003）{}", route.method(), describe(session), e);
            metrics.requestCompleted(sample, route.method(), RequestResult.INTERNAL_ERROR);
            return envelope(GuildTips.SERVICE_UNAVAILABLE);
        }
        metrics.requestCompleted(sample, route.method(), resultOf(response));
        return ClientReply.newBuilder().setBody(response.toByteString()).build();
    }

    /** 应答体的结果分类：没有 error_message（或 id 为 0）→ ok；14008（帮会段唯一的 in-band 故障码）→ fault；其余 → business_error。 */
    static RequestResult resultOf(Message response) {
        FieldDescriptor field = response.getDescriptorForType().findFieldByName(ERROR_MESSAGE_FIELD);
        if (field == null || !response.hasField(field)) {
            return RequestResult.OK;
        }
        TipInfoMessage tip = (TipInfoMessage) response.getField(field);
        if (tip.getId() == GuildTips.OK) {
            return RequestResult.OK;
        }
        return GuildTips.isFault(tip.getId()) ? RequestResult.FAULT : RequestResult.BUSINESS_ERROR;
    }

    private static Message parse(Route route, ClientCall call) {
        try {
            return route.requestPrototype().getParserForType().parseFrom(call.getBody());
        } catch (InvalidProtocolBufferException e) {
            log.warn("[guild] 请求体解析失败 method={} {}: {}", route.method(), describe(call.getSession()), e.getMessage());
            return null;
        }
    }

    private ClientReply unsupported(ClientCall call) {
        Optional<MessageMethod> known = registry.byId(call.getMessageId());
        int tip = known.isPresent() ? GuildTips.FEATURE_UNAVAILABLE : GuildTips.MESSAGE_ID_NOT_FOUND;
        log.warn("[guild] 不处理的消息号 message_id={} 方法={} {}", call.getMessageId(),
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
