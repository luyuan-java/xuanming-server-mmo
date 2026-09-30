package com.game.gate.session;

import com.game.api.ClientMessageService;
import com.game.api.DubboGroups;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientForward;
import com.game.api.proto.ClientReply;
import com.game.api.proto.EnterScene;
import com.game.api.proto.NodeLinkFrame;
import com.game.api.proto.PlayerEnter;
import com.game.api.proto.PlayerEnterResult;
import com.game.api.proto.PlayerKicked;
import com.game.api.proto.PlayerLeave;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.common.token.GateTokens;
import com.game.gate.link.SceneLinks;
import com.game.gate.session.ClientSession.PendingRequest;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.game.table.CommonErrorTip;
import com.game.table.SceneErrorTip;
import java.time.InstantSource;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话层核心逻辑：令牌握手、按消息号路由、login 应答与会话指令、scene 链路事件、断线。
 *
 * <p>线程模型：所有 {@code on*} 方法都必须在会话所属的 EventLoop 上调用
 * （{@link ClientChannelHandler} 与 {@link SceneEventRouter} 保证）。本类自身不持有会话可变状态。
 *
 * <p>客户端可见行为以 mmorpg C++ gate（{@code client_message_processor.cpp}）与 {@code docs/reference/} 的契约为准：
 * <ul>
 *   <li>首帧必须是 {@code ClientTokenVerifyRequest}；未握手的 {@code ClientRequest} 直接断开、不回包；</li>
 *   <li>未知 / 非客户端消息号不回包，计非法包；整包超过 1KB 回 {@code MessageContent.error_message=1010}，计非法包；</li>
 *   <li>按消息号限频（C++ MessageLimiter：MessageLimiter 表，缺省每秒 3 条）：超频回
 *       {@code MessageContent.error_message=1008}，计非法包，不转发；</li>
 *   <li>后端不可用、玩家不在场景、Java 版尚未实现的后端域：推 23 {@code TipInfoMessage{1003}}
 *       （不用「信封错误 + 空 body」：robot 不读信封错误，会把空 body 当成功，见 login 契约「Java 必须做到」第 4 条）；</li>
 *   <li>应答 {@code MessageContent.message_id} = 请求号、{@code id} = 请求 id；{@code error_message} 只在 tip≠0 时出现；</li>
 *   <li>进场景失败（scene 回 3023、建链失败、链路层已关）：推 23 {3023}，会话回到「已登录、未进游戏」（玩家绑定清零），
 *       客户端可在同一连接上重试 EnterGame 或回选角建角（login 契约 §6.3）；</li>
 *   <li>数据归属被别的会话接管（scene 发来 {@code PlayerKicked}）：推 23 {2017} 后断开（本里程碑不发 34）。</li>
 * </ul>
 */
public final class ClientDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ClientDispatcher.class);

    /** C++ kMaxClientMessageSize：整个 ClientRequest 序列化后超过 1KB 即拒绝。 */
    static final int MAX_REQUEST_BYTES = 1024;

    static final String DOMAIN_LOGIN = DubboGroups.LOGIN;
    static final String DOMAIN_SCENE = "scene";

    static final int TIP_SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final int TIP_MESSAGE_SIZE_EXCEEDED = CommonErrorTip.common_error.kMessageSizeExceeded_VALUE;
    static final int TIP_RATE_LIMIT_EXCEEDED = CommonErrorTip.common_error.kRateLimitExceeded_VALUE;
    static final int TIP_ENTER_SCENE_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;

    private static final ClientTokenVerifyResponse VERIFY_OK = ClientTokenVerifyResponse.newBuilder().setSuccess(true).build();

    private final GateIdentity identity;
    private final GateTokens tokens;
    private final InstantSource clock;
    private final LongSupplier nanoClock;
    private final MessageRoutes routes;
    private final int tipMessageId;
    private final ClientMessageService login;
    private final SceneLinks links;
    private final SessionRegistry registry;
    private final GateLimits limits;
    private final AtomicLong securityRejections = new AtomicLong();

    /**
     * @param tipMessageId 服务端推 tip 用的消息号（{@code SceneClientPlayerCommon.SendTipToClient}，现为 23）
     * @param login        login 后端（Dubbo group {@code login}）
     */
    public ClientDispatcher(GateIdentity identity, GateTokens tokens, InstantSource clock, MessageRoutes routes, int tipMessageId,
                            ClientMessageService login, SceneLinks links, SessionRegistry registry, GateLimits limits) {
        this(identity, tokens, clock, routes, tipMessageId, login, links, registry, limits, System::nanoTime);
    }

    /** 同上；限频用的单调时钟可注入（测试用）。 */
    ClientDispatcher(GateIdentity identity, GateTokens tokens, InstantSource clock, MessageRoutes routes, int tipMessageId,
                     ClientMessageService login, SceneLinks links, SessionRegistry registry, GateLimits limits,
                     LongSupplier nanoClock) {
        this.identity = identity;
        this.tokens = tokens;
        this.clock = clock;
        this.routes = routes;
        this.tipMessageId = tipMessageId;
        this.login = login;
        this.links = links;
        this.registry = registry;
        this.limits = limits;
        this.nanoClock = nanoClock;
    }

    // ================================================================ 连接生命周期

    public void onConnected(ClientSession s) {
        s.scheduleHandshakeTimeout(limits.handshakeTimeout(), () -> {
            if (!s.verified && !s.closed) {
                logRejection("handshake_timeout", s);
                closeNow(s);
            }
        });
    }

    public void onTokenVerify(ClientSession s, ClientTokenVerifyRequest request) {
        if (s.closing || s.closed) {
            return;
        }
        if (s.verified) {
            // 已验证的会话再次 verify 直接回成功（C++ 同）。
            s.send(VERIFY_OK);
            return;
        }
        GateTokens.Verdict verdict = tokens.verify(request.getPayload(), request.getSignature(),
                identity.nodeId(), identity.zoneId(), clock.instant().getEpochSecond());
        if (!verdict.ok()) {
            logRejection("token_" + verdict.failure(), s);
            s.closing = true;
            s.cancelHandshakeTimeout();
            s.sendThenClose(ClientTokenVerifyResponse.newBuilder()
                    .setSuccess(false)
                    .setError(clientError(verdict.failure()))
                    .build());
            return;
        }
        s.verified = true;
        s.cancelHandshakeTimeout();
        s.send(VERIFY_OK);
    }

    public void onRequest(ClientSession s, ClientRequest request) {
        if (s.closing || s.closed) {
            return;
        }
        if (!s.verified) {
            // 断线自动重连的 robot 会在未握手的新连接上直接发业务包（robot 契约 §7.3）：拒绝并断开，不回包。
            logRejection("request_before_token_verify", s);
            closeNow(s);
            return;
        }
        MessageRoute route = routes.clientRoute(request.getMessageId());
        if (route == null) {
            registerIllegal(s, "unknown_message_id", request.getMessageId());
            return;
        }
        if (request.getSerializedSize() > MAX_REQUEST_BYTES) {
            s.send(envelopeError(request, TIP_MESSAGE_SIZE_EXCEEDED));
            registerIllegal(s, "oversized", request.getMessageId());
            return;
        }
        if (!s.rateLimiter.tryAcquire(request.getMessageId(), limits.messageLimits().limitOf(request.getMessageId()),
                nanoClock.getAsLong())) {
            // C++ CheckMessageLimit 同形：信封错误 1008，计非法包（持续超频的连接到阈值被断开），不转发。
            s.send(envelopeError(request, TIP_RATE_LIMIT_EXCEEDED));
            registerIllegal(s, "rate_limited", request.getMessageId());
            return;
        }
        if (s.pending.size() >= limits.maxPendingRequests()) {
            log.warn("会话待处理请求超限，断开 session={} pending={} message_id={}",
                    sid(s), s.pending.size(), request.getMessageId());
            closeNow(s);
            return;
        }
        s.pending.add(new PendingRequest(route, request));
        drain(s);
    }

    /** 连接断开（channelInactive）。 */
    public void onDisconnected(ClientSession s) {
        if (s.closed) {
            return;
        }
        s.closed = true;
        s.closing = true;
        s.cancelHandshakeTimeout();
        s.pending.clear();
        leaveScene(s, false);
        if (s.inFlight) {
            // 等在途的 login 调用完成后再通知 login（见 onLoginCompleted），否则 login 可能先收到断线、后处理完进游戏。
            return;
        }
        finishClose(s);
    }

    // ================================================================ 上行处理（同一会话严格按到达顺序）

    private void drain(ClientSession s) {
        while (!s.inFlight && !s.closing && !s.closed) {
            PendingRequest next = s.pending.poll();
            if (next == null) {
                return;
            }
            dispatch(s, next);
        }
    }

    private void dispatch(ClientSession s, PendingRequest p) {
        switch (p.route().domain()) {
            case DOMAIN_LOGIN -> callLogin(s, p.request());
            case DOMAIN_SCENE -> forwardToScene(s, p.request());
            default -> {
                // Java 版尚未实现的后端域（friend / guild / battle ...）：与 C++ 找不到目标节点时同形。
                log.debug("消息域未接入 Java 版 session={} message_id={} domain={}", sid(s), p.route().messageId(), p.route().domain());
                sendTip(s, TIP_SERVICE_UNAVAILABLE);
            }
        }
    }

    private void callLogin(ClientSession s, ClientRequest request) {
        ClientCall call = ClientCall.newBuilder()
                .setSession(context(s))
                .setMessageId(request.getMessageId())
                .setBody(request.getBody())
                .setRequestId(request.getId())
                .build();
        s.inFlight = true;
        s.loginTouched = true;
        CompletableFuture<ClientReply> future;
        try {
            future = login.handle(call);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        if (future == null) {
            future = CompletableFuture.failedFuture(new IllegalStateException("login 返回了 null future"));
        }
        future.whenComplete((reply, error) -> s.execute(() -> onLoginCompleted(s, request, reply, error)));
    }

    private void onLoginCompleted(ClientSession s, ClientRequest request, ClientReply reply, Throwable error) {
        s.inFlight = false;
        if (s.closing || s.closed) {
            // 连接已断（或正在断）：不再向客户端发送，只把身份变化记到会话上，让给 login 的断线通知带上最终身份；
            // 没送出去的进场由 login 释放归属。
            if (error == null && reply != null) {
                recordIdentity(s, reply.getDirectivesList());
            }
            if (s.closed) {
                finishClose(s);
            }
            return;
        }
        if (error != null || reply == null) {
            log.warn("login 调用失败 session={} message_id={} 原因={}", sid(s), request.getMessageId(), rootCause(error).toString());
            sendTip(s, TIP_SERVICE_UNAVAILABLE);
        } else {
            replyToClient(s, request, reply);
            applyDirectives(s, reply.getDirectivesList());
        }
        drain(s);
    }

    private void replyToClient(ClientSession s, ClientRequest request, ClientReply reply) {
        // 是否回包由契约决定，不能看 body 是否为空：proto3 全默认值的应答序列化后就是 0 字节
        // （新账号的 LoginResponse 即如此），吞掉它客户端会一直等到超时。
        MessageRoute route = routes.clientRoute(request.getMessageId());
        boolean hasResponse = route == null || route.hasResponse();
        if (!hasResponse && reply.getTipId() == 0) {
            return;
        }
        MessageContent.Builder out = MessageContent.newBuilder()
                .setMessageId(request.getMessageId())
                .setId(request.getId());
        if (!reply.getBody().isEmpty()) {
            out.setSerializedMessage(reply.getBody());
        }
        if (reply.getTipId() != 0) {
            // 只在失败时设置：proto3 子消息一旦 set 就会上线（哪怕 id=0），客户端会判为失败。
            out.setErrorMessage(TipInfoMessage.newBuilder()
                    .setId(reply.getTipId())
                    .addAllParameters(reply.getTipParametersList()));
        }
        s.send(out.build());
    }

    private void applyDirectives(ClientSession s, List<SessionDirective> directives) {
        for (SessionDirective directive : directives) {
            if (s.closing) {
                return;
            }
            switch (directive.getKindCase()) {
                case BIND_ACCOUNT -> s.account = directive.getBindAccount().getAccount();
                case ENTER_SCENE -> enterScene(s, directive.getEnterScene());
                case CLOSE_SESSION -> closeByServer(s, directive.getCloseSession().getTipId());
                case UNBIND_PLAYER -> unbindPlayer(s);
                case KIND_NOT_SET -> log.warn("login 下发了空会话指令 session={}", sid(s));
            }
        }
    }

    /**
     * 迟到的应答（会话已断或正在断）：只记账号 / 玩家，不发 PlayerEnter / PlayerLeave，不改场景绑定——
     * 场景绑定由断线流程按 {@link ClientSession#scenePlayerId} 放掉。没送出去的进场告诉 login 释放归属。
     */
    private void recordIdentity(ClientSession s, List<SessionDirective> directives) {
        for (SessionDirective directive : directives) {
            switch (directive.getKindCase()) {
                case BIND_ACCOUNT -> s.account = directive.getBindAccount().getAccount();
                case ENTER_SCENE -> {
                    EnterScene enter = directive.getEnterScene();
                    s.playerId = enter.getPlayerId();
                    abandonEnter(s, enter.getPlayerId(), enter.getOwnerEpoch(), "会话已在关闭");
                }
                case UNBIND_PLAYER -> s.playerId = 0;
                default -> {
                    // CloseSession 无需再执行：连接已断。
                }
            }
        }
    }

    private void enterScene(ClientSession s, EnterScene enter) {
        if (enter.getPlayerId() == 0 || enter.getSceneNodeId() == 0) {
            log.error("login 下发的 EnterScene 非法 session={} player_id={} scene_node_id={}",
                    sid(s), enter.getPlayerId(), enter.getSceneNodeId());
            abandonEnter(s, enter.getPlayerId(), enter.getOwnerEpoch(), "进场指令非法");
            failEnter(s, TIP_ENTER_SCENE_FAILED);
            return;
        }
        // login 不会给已绑定玩家的会话下发进场（EnterGame 对这种会话回 2028，见 xm-login EnterGameHandler），
        // 所以正常流程里这里不在场景中。仍先让旧场景放掉旧玩家：一个会话绝不同时挂两个场景实例（防御）。
        leaveScene(s, false);
        s.playerId = enter.getPlayerId();
        s.sceneNodeId = enter.getSceneNodeId();
        s.scenePlayerId = enter.getPlayerId();
        s.sceneOwnerEpoch = enter.getOwnerEpoch();
        PlayerEnter playerEnter = PlayerEnter.newBuilder()
                .setSessionId(s.sessionId())
                .setPlayerId(enter.getPlayerId())
                .setSceneId(enter.getSceneId())
                .setOwnerEpoch(enter.getOwnerEpoch())
                .build();
        long gen = links.send(enter.getSceneNodeId(), NodeLinkFrame.newBuilder().setPlayerEnter(playerEnter).build());
        if (gen == 0) {
            abandonEnter(s, enter.getPlayerId(), enter.getOwnerEpoch(), "链路层不可用");
            failEnter(s, TIP_ENTER_SCENE_FAILED);
            return;
        }
        s.sceneLinkGen = gen;
        log.info("会话进场 session={} player_id={} scene_node_id={} scene_id={} link_gen={} owner_epoch={}",
                sid(s), enter.getPlayerId(), enter.getSceneNodeId(), enter.getSceneId(), gen, enter.getOwnerEpoch());
    }

    /**
     * 进场失败：解绑场景并清掉玩家绑定，会话回到「已登录、未进游戏」——与 LeaveGame 之后相同，只是不发 PlayerLeave
     * （玩家没进成场景）。客户端之后可以在同一连接上重试 EnterGame（任一角色）或回选角建角（login 契约 §6.3），
     * login 按 {@code SessionContext.player_id = 0} 放行。迟到 / 重复的结果帧会被 (节点, 代次, player_id, epoch) 守卫丢弃。
     */
    private void failEnter(ClientSession s, int tipId) {
        unbindScene(s);
        s.playerId = 0;
        if (!s.closing) {
            sendTip(s, tipId);
        }
    }

    private void closeByServer(ClientSession s, int tipId) {
        log.info("login 指示关闭会话 session={} tip={}", sid(s), tipId);
        s.closing = true;
        if (tipId != 0) {
            s.sendThenClose(tip(tipId));
        } else {
            s.close();
        }
    }

    /**
     * LeaveGame 成功（{@code UnbindPlayer}）：在场景里就让场景放掉玩家（主动离开），再清掉会话上的玩家 / 场景绑定。
     * 账号保留：连接还在，客户端可以回选角界面重新建角 / 进游戏。
     */
    private void unbindPlayer(ClientSession s) {
        long playerId = s.playerId;
        int sceneNodeId = s.sceneNodeId;
        leaveScene(s, true);
        s.playerId = 0;
        log.info("会话离开游戏 session={} player_id={} scene_node_id={}", sid(s), playerId, sceneNodeId);
    }

    private void forwardToScene(ClientSession s, ClientRequest request) {
        if (s.sceneNodeId == 0) {
            // 与 C++ HandleTcpNodeMessage 找不到场景节点时同形。
            sendTip(s, TIP_SERVICE_UNAVAILABLE);
            return;
        }
        ClientForward forward = ClientForward.newBuilder()
                .setSessionId(s.sessionId())
                .setPlayerId(s.scenePlayerId)
                .setMessageId(request.getMessageId())
                .setBody(request.getBody())
                .setRequestId(request.getId())
                .build();
        links.send(s.sceneNodeId, NodeLinkFrame.newBuilder().setClientForward(forward).build());
    }

    // ================================================================ scene 链路事件（已由 SceneEventRouter 投递到会话线程）

    public void onToClient(ClientSession s, int sceneNodeId, long linkGen, MessageContent content) {
        if (s.closing || s.closed) {
            return;
        }
        if (!s.boundTo(sceneNodeId, linkGen)) {
            log.debug("丢弃不属于会话当前场景链路的下行 session={} node={} gen={} message_id={}",
                    sid(s), sceneNodeId, linkGen, content.getMessageId());
            return;
        }
        s.send(content);
    }

    /** scene 的进场结果。只认会话当前绑定的那次进场（节点、链路代次、玩家、epoch 都对得上）；失败即回到未进游戏。 */
    public void onPlayerEnterResult(ClientSession s, int sceneNodeId, long linkGen, PlayerEnterResult result) {
        if (s.closed || !s.boundTo(sceneNodeId, linkGen) || s.scenePlayerId != result.getPlayerId()) {
            return;
        }
        if (result.getOwnerEpoch() != s.sceneOwnerEpoch) {
            log.debug("丢弃更早一次进场的迟到结果 session={} player_id={} 结果 epoch={} 当前 epoch={} tip={}", sid(s),
                    result.getPlayerId(), result.getOwnerEpoch(), s.sceneOwnerEpoch, result.getTipId());
            return;
        }
        if (result.getTipId() == 0) {
            return;
        }
        log.info("scene 拒绝进场 session={} player_id={} node={} tip={}", sid(s), s.scenePlayerId, sceneNodeId,
                result.getTipId());
        failEnter(s, result.getTipId());
    }

    /** 进场帧没能送到 scene（建链失败 / 排队溢出）：scene 从未见过它，告诉 login 释放归属；失败即回到未进游戏。 */
    public void onEnterUndeliverable(ClientSession s, int sceneNodeId, long linkGen, PlayerEnter enter) {
        if (s.closed || !s.boundTo(sceneNodeId, linkGen) || s.scenePlayerId != enter.getPlayerId()
                || s.sceneOwnerEpoch != enter.getOwnerEpoch()) {
            return;
        }
        abandonEnter(s, enter.getPlayerId(), enter.getOwnerEpoch(), "建链失败");
        // 与 scene 进场失败同一个码（scene 契约 §3.5：23 TipInfoMessage{3023}）。
        failEnter(s, TIP_ENTER_SCENE_FAILED);
    }

    /**
     * scene 已把会话上的玩家移出场景（数据归属被别的会话接管 / 本实例失去归属），该写回的已写回：
     * 解绑场景（不再发 PlayerLeave），推 tip（2017）后关闭连接。只认会话当前绑定的那次进场。
     */
    public void onPlayerKicked(ClientSession s, int sceneNodeId, long linkGen, PlayerKicked kicked) {
        if (s.closed || !s.boundTo(sceneNodeId, linkGen) || s.scenePlayerId != kicked.getPlayerId()
                || s.sceneOwnerEpoch != kicked.getOwnerEpoch()) {
            return;
        }
        log.info("scene 踢出会话上的玩家 session={} player_id={} node={} epoch={} tip={}", sid(s), kicked.getPlayerId(),
                sceneNodeId, kicked.getOwnerEpoch(), kicked.getTipId());
        unbindScene(s);
        if (s.closing) {
            return;
        }
        s.closing = true;
        s.pending.clear();
        if (kicked.getTipId() != 0) {
            s.sendThenClose(tip(kicked.getTipId()));
        } else {
            s.close();
        }
    }

    public void onSceneLinkDown(ClientSession s, int sceneNodeId, long linkGen) {
        if (s.closed || !s.boundTo(sceneNodeId, linkGen)) {
            return;
        }
        // scene 那一侧已随链路丢掉这个会话（并负责释放 / 写回）；gate 关闭连接，客户端重连后重新进场。链路已断，不发 PlayerLeave。
        log.info("scene 链路断开，关闭其上的会话 session={} player_id={} node={}", sid(s), s.scenePlayerId, sceneNodeId);
        unbindScene(s);
        closeNow(s);
    }

    // ================================================================ 内部

    /**
     * 在场景里就发 {@code PlayerLeave}（玩家取场景绑定上记的那个）并解绑场景（玩家绑定不动）；不在场景里什么也不做。
     *
     * @param voluntary true = 客户端主动 LeaveGame；false = 断线 / 换进别的场景
     */
    private void leaveScene(ClientSession s, boolean voluntary) {
        if (s.sceneNodeId == 0) {
            return;
        }
        PlayerLeave leave = PlayerLeave.newBuilder()
                .setSessionId(s.sessionId())
                .setPlayerId(s.scenePlayerId)
                .setVoluntary(voluntary)
                .build();
        links.send(s.sceneNodeId, NodeLinkFrame.newBuilder().setPlayerLeave(leave).build());
        unbindScene(s);
    }

    private static void unbindScene(ClientSession s) {
        s.sceneNodeId = 0;
        s.sceneLinkGen = 0;
        s.scenePlayerId = 0;
        s.sceneOwnerEpoch = 0;
    }

    /**
     * 一次进游戏夺得的归属确定没有送到任何 scene（PlayerEnter 从未写上链路）：告诉 login 释放（带 epoch 围栏），
     * 玩家不必等归属租约过期就能再进。尽力而为，失败只记日志。
     */
    private void abandonEnter(ClientSession s, long playerId, long ownerEpoch, String reason) {
        if (playerId == 0 || ownerEpoch == 0) {
            return;
        }
        log.info("进场未送达 scene，请 login 释放归属 session={} player_id={} epoch={} 原因={}",
                sid(s), playerId, ownerEpoch, reason);
        AbandonedEnter event = AbandonedEnter.newBuilder()
                .setSession(context(s))
                .setPlayerId(playerId)
                .setOwnerEpoch(ownerEpoch)
                .build();
        try {
            CompletableFuture<?> future = login.abandonEnter(event);
            if (future != null) {
                future.whenComplete((ack, error) -> {
                    if (error != null) {
                        log.warn("通知 login 释放未送达进场的归属失败（等租约过期） player_id={} epoch={} 原因={}",
                                playerId, ownerEpoch, rootCause(error).toString());
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("通知 login 释放未送达进场的归属失败（等租约过期） player_id={} epoch={} 原因={}",
                    playerId, ownerEpoch, e.toString());
        }
    }

    private void finishClose(ClientSession s) {
        if (s.loginTouched || s.playerId != 0) {
            notifyLoginClosed(s);
        }
        registry.release(s);
    }

    private void notifyLoginClosed(ClientSession s) {
        SessionClosed event = SessionClosed.newBuilder().setSession(context(s)).setVoluntary(false).build();
        try {
            CompletableFuture<?> future = login.sessionClosed(event);
            if (future != null) {
                future.whenComplete((ack, error) -> {
                    if (error != null) {
                        log.warn("通知 login 会话结束失败 session={} player_id={} 原因={}",
                                sid(s), event.getSession().getPlayerId(), rootCause(error).toString());
                    }
                });
            }
        } catch (RuntimeException e) {
            log.warn("通知 login 会话结束失败 session={} 原因={}", sid(s), e.toString());
        }
    }

    private SessionContext context(ClientSession s) {
        return SessionContext.newBuilder()
                .setGateNodeId(identity.nodeId())
                .setGateInstanceId(identity.instanceId())
                .setSessionId(s.sessionId())
                .setZoneId(identity.zoneId())
                .setAccount(s.account)
                .setPlayerId(s.playerId)
                .setClientIp(s.clientIp())
                .build();
    }

    private void registerIllegal(ClientSession s, String reason, int messageId) {
        s.illegalPackets++;
        log.debug("非法包 session={} reason={} message_id={} count={}", sid(s), reason, messageId, s.illegalPackets);
        int threshold = limits.illegalPacketThreshold();
        if (threshold > 0 && s.illegalPackets >= threshold) {
            log.warn("非法包达到阈值，断开 session={} count={} reason={} message_id={}", sid(s), s.illegalPackets, reason, messageId);
            closeNow(s);
        }
    }

    /** 信封错误：{@code MessageContent{message_id=请求号, id=请求 id, error_message{tip}}}（C++ 同形，客户端按 message_id 对上请求）。 */
    private static MessageContent envelopeError(ClientRequest request, int tipId) {
        return MessageContent.newBuilder()
                .setMessageId(request.getMessageId())
                .setId(request.getId())
                .setErrorMessage(TipInfoMessage.newBuilder().setId(tipId))
                .build();
    }

    private void sendTip(ClientSession s, int tipId) {
        s.send(tip(tipId));
    }

    private MessageContent tip(int tipId) {
        return MessageContent.newBuilder()
                .setMessageId(tipMessageId)
                .setSerializedMessage(TipInfoMessage.newBuilder().setId(tipId).build().toByteString())
                .build();
    }

    private static void closeNow(ClientSession s) {
        s.closing = true;
        s.close();
    }

    /** 未认证流量由公网决定，逐条打 INFO 会被放大成日志 DoS：明细只打 DEBUG，每 1024 次采样一条 INFO。 */
    private void logRejection(String reason, ClientSession s) {
        long n = securityRejections.getAndIncrement();
        if ((n & 0x3FF) == 0) {
            log.info("客户端被拒（每 1024 次采样一条） reason={} session={} peer={} total={}", reason, sid(s), s.clientIp(), n + 1);
        } else {
            log.debug("客户端被拒 reason={} session={} peer={}", reason, sid(s), s.clientIp());
        }
    }

    /** 与 C++ gate 的错误文案一致（客户端只拼进日志）。 */
    static String clientError(GateTokens.Failure failure) {
        return switch (failure) {
            case BAD_SIGNATURE -> "invalid token signature";
            case BAD_PAYLOAD -> "malformed token payload";
            case WRONG_GATE -> "token not for this gate";
            case WRONG_ZONE -> "token not for this zone";
            case EXPIRED -> "token expired";
        };
    }

    private static Throwable rootCause(Throwable error) {
        if (error == null) {
            return new IllegalStateException("login 返回了空应答");
        }
        Throwable t = error;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static String sid(ClientSession s) {
        return Integer.toUnsignedString(s.sessionId());
    }
}
