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
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.RedirectToGate;
import com.game.api.proto.SessionClosed;
import com.game.api.proto.SessionContext;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.ZoneRedirect;
import com.game.common.killswitch.KillSwitch;
import com.game.common.token.GateTokens;
import com.game.gate.link.SceneLinks;
import com.game.gate.link.EnterFailure;
import com.game.gate.metrics.GateMetrics;
import com.game.gate.metrics.GateMetrics.DisconnectReason;
import com.game.gate.metrics.GateMetrics.HandshakeResult;
import com.game.gate.metrics.GateMetrics.LoginCall;
import com.game.gate.metrics.GateMetrics.PushResult;
import com.game.gate.metrics.GateMetrics.RedirectResult;
import com.game.gate.metrics.GateMetrics.RedirectSource;
import com.game.gate.metrics.GateMetrics.RequestResult;
import com.game.gate.metrics.GateMetrics.SceneTransferResult;
import com.game.gate.session.ClientSession.BackendPending;
import com.game.gate.session.ClientSession.PendingRequest;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.game.proto.RedirectToGateNotify;
import com.game.proto.TipInfoMessage;
import com.game.table.CommonErrorTip;
import com.game.table.SceneErrorTip;
import io.micrometer.core.instrument.Timer;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话层核心逻辑：令牌握手、按消息号路由、login 应答与会话指令、scene 链路事件、断线。
 *
 * <p>线程模型：所有带 {@link ClientSession} 参数的 {@code on*} 方法都必须在会话所属的 EventLoop 上调用
 * （{@link ClientChannelHandler} 与 {@link SceneEventRouter} 保证）；{@code on*WithoutSession} 不碰会话状态，任意线程可调。
 * 本类自身不持有会话可变状态。
 *
 * <p>客户端可见行为以 mmorpg C++ gate（{@code client_message_processor.cpp}）与 {@code docs/reference/} 的契约为准：
 * <ul>
 *   <li>首帧必须是 {@code ClientTokenVerifyRequest}；未握手的 {@code ClientRequest} 直接断开、不回包；</li>
 *   <li>未知 / 非客户端消息号不回包，计非法包；整包超过 1KB 回 {@code MessageContent.error_message=1010}，计非法包；</li>
 *   <li>按消息号限频（C++ MessageLimiter：MessageLimiter 表，缺省每秒 3 条）：超频回
 *       {@code MessageContent.error_message=1008}，计非法包，不转发；</li>
 *   <li>后端（login 与 friend / match 等）调用失败或超时、热关停、会话后端 lane 满（1008）或排队超时：回带请求 id 的信封错误
 *       （与 C++ gate 路由 lane 同形，mmorpg client-request-ordering.md §4.7，2026-10-14 两版同批改），客户端按请求 id 当场对上失败；
 *       mmorpg 的 Go robot 同批改为读信封错误（以前只解 body，会把空 body 当成功）；</li>
 *   <li>玩家不在场景、Java 版尚未实现的后端域：回带请求 id 的信封 1003（与 C++ {@code HandleTcpNodeMessage} 找不到场景节点、
 *       {@code HandleGrpcNodeMessage} 找不到业务节点时同形；2026-10-15 起由 23 {@code TipInfoMessage{1003}} 改为信封，
 *       mmorpg client-request-ordering.md O6「所有 gate 级失败回执都带请求 id」，两版同批）；不计非法包、不断连；</li>
 *   <li>战斗服务 {@code BattleClientPlayer} 的 12 个号（{@link MessageRoute#directOnly()}）：GM 闸之后当场回带请求 id 的信封 1003，
 *       不计非法包、不断连、不过热关停、不排在途请求之后（基线 {@code :937-950}；scene-battle-spec §2.5、§7.19，D12）；
 *       GM 闸拒绝同样回带请求 id 的信封 1006。两者 2026-10-15 起由 23 推送改为信封（mmorpg client-request-ordering.md O6，两版同批）；</li>
 *   <li>应答 {@code MessageContent.message_id} = 请求号、{@code id} = 请求 id；{@code error_message} 只在 tip≠0 时出现；</li>
 *   <li>进场景失败（scene 回 3023、建链失败、链路层已关）：推 23 {3023}，会话回到「已登录、未进游戏」（玩家绑定清零），
 *       客户端可在同一连接上重试 EnterGame 或回选角建角（login 契约 §6.3）；</li>
 *   <li>数据归属被别的会话接管（scene 发来 {@code PlayerKicked}）：推 23 {2017} 后断开（本里程碑不发 34）。</li>
 *   <li>跨节点换图（源 scene 发来 {@code PlayerTransfer}）：会话改绑到目标节点，不发 PlayerLeave、在线登记保持，客户端只看到
 *       目标节点的 79 / 21 / 47；交出之后没能落到目标节点（目标节点拒绝、链路不可用 / 建链失败）推 23 {3023 或目标节点的 tip}
 *       后断开——不回大厅、不弹回源场景、不发 34（scene-handoff-spec §5.7，D5 / D11）。</li>
 *   <li>跨 zone 重定向（批次 5.4，zone-travel-spec §5.7）：源 scene 发来带 {@code redirect} 的 {@code PlayerTransfer}（226 传送），
 *       或 login 在 EnterGame 应答里带 {@code RedirectToGate} 指令（登录期重定向 GO-5，应答先发）：解绑场景（不发 PlayerLeave、撤在线登记），
 *       推 124 {@code RedirectToGateNotify}（{@code MessageContent} 不设 id），<b>不断开</b>；会话转入已重定向状态，之后的请求静默丢弃，
 *       客户端在收口时限（{@code game.gate.redirect-linger}）内没自己断开就由 gate 关闭（不推 tip）。重定向载荷非法推 23 {3027} 后断开。</li>
 *   <li>握手时出示的票据里的持票者与目标 zone 存进会话，随每个转 login / 后端的调用交出（{@code SessionContext} 8 / 9 号字段）；gate 只存不判。</li>
 * </ul>
 */
public final class ClientDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ClientDispatcher.class);
    /** LeaveGame 在契约里的方法名（服务裸名.方法名）。 */
    private static final String LEAVE_GAME_METHOD = "ClientPlayerLogin.LeaveGame";

    /** C++ kMaxClientMessageSize：整个 ClientRequest 序列化后超过 1KB 即拒绝。 */
    static final int MAX_REQUEST_BYTES = 1024;

    static final String DOMAIN_LOGIN = DubboGroups.LOGIN;
    static final String DOMAIN_SCENE = "scene";

    static final int TIP_SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final int TIP_FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;
    static final int TIP_MESSAGE_SIZE_EXCEEDED = CommonErrorTip.common_error.kMessageSizeExceeded_VALUE;
    static final int TIP_RATE_LIMIT_EXCEEDED = CommonErrorTip.common_error.kRateLimitExceeded_VALUE;
    static final int TIP_ENTER_SCENE_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;
    /** 跨 zone 传送受理后未成（3027，基线 {@code genericFailTip}）：重定向载荷非法时推这个码后断开。 */
    static final int TIP_ZONE_TRAVEL_FAILED = SceneErrorTip.scene_error.kZoneTravelTargetBusy_VALUE;

    /**
     * 会话后端 lane 的排队 + 在途总上限:超过就回带请求 id 的信封 1008、不断连、不计非法包。
     * 与 C++ 版 gate 路由 lane 同值同形(mmorpg cpp/nodes/gate/handler/rpc/client_request_lane.h kMaxLaneDepth,
     * client-request-ordering.md O6)。正常客户端按功能单飞,排到 32 条只可能是在刷。
     */
    static final int MAX_BACKEND_LANE_DEPTH = 32;
    /**
     * 会话后端 lane 的排队等待上限:超过就不再发出,回带请求 id 的信封 1003(明确「没有执行」,客户端重试安全)。
     * 不变式:gate 调后端的超时(5 s)+ 本值 + 1 s ≤ 客户端请求超时 15 s(client-request-ordering.md §5.2 I4)。
     * 与 C++ 版 kMaxLaneWait 同值。
     */
    static final long MAX_BACKEND_LANE_WAIT_NANOS = TimeUnit.SECONDS.toNanos(5);
    /**
     * 后端调用以<b>结果未知</b>的错误结束（请求已经交给 Dubbo、可能到了后端）时，会话后端 lane 至少等到「发出 + 本值」才放行下一条。
     * 与 gate 调后端的 Dubbo 消费端超时同值（application.yaml {@code dubbo.consumer.timeout: 5000}，改一个要同改另一个）：
     * 后端各自的请求预算都小于它、从受理时刻起算、提交点前查截止（PARITY「客户端请求按会话保序」逐方法核实），超时这条路径本来就要等满它；
     * 断连、流被重置、提供方重启这类错误却会在后端 handler 还在跑时提前结束调用，立刻放行会让下一条与上一条的提交交错
     * （mmorpg client-request-ordering.md §10 的 2026-10-16 订正）。同步抛出（请求根本没发出去）不等。
     * C++ 版不需要这一条：gRPC 的取消沿 gate → 路由服 → 目标服务传播，目标 handler 的 ctx 随之结束，提交点前查 ctx。
     */
    static final long BACKEND_RESULT_UNKNOWN_HOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(5000);

    private static final ClientTokenVerifyResponse VERIFY_OK = ClientTokenVerifyResponse.newBuilder().setSuccess(true).build();

    private final GateIdentity identity;
    private final GateTokens tokens;
    private final InstantSource clock;
    private final LongSupplier nanoClock;
    private final MessageRoutes routes;
    private final int tipMessageId;
    /**
     * 推 124 {@code RedirectToGateNotify} 用的消息号（{@code SceneClientPlayerCommon.RedirectToGate}，批次 5.4）；0 = 没装配
     * （只收 tip 号的旧重载）：这时重定向帧 / 重定向指令一律按内容非法收口（推 23 {3027} 后断开），绝不发消息号为 0 的包。
     */
    private final int redirectToGateMessageId;
    private final ClientMessageService login;
    /** login 以外的客户端消息后端：Dubbo group（= 消息域）→ 服务（friend ……）。 */
    private final Map<String, ClientMessageService> backends;
    private final SceneLinks links;
    private final SessionRegistry registry;
    private final GateLimits limits;
    private final GateMetrics metrics;
    private final PresenceRecorder presence;
    private final AtomicLong securityRejections = new AtomicLong();

    /**
     * @param tipMessageId 服务端推 tip 用的消息号（{@code SceneClientPlayerCommon.SendTipToClient}，现为 23）
     * @param login        login 后端（Dubbo group {@code login}）
     * @param metrics      会话层指标（握手、请求去向、主动断开、login 调用耗时）
     * @param presence     玩家在线目录的写入口（进场确认 / 场景绑定结束时调用）
     */
    public ClientDispatcher(GateIdentity identity, GateTokens tokens, InstantSource clock, MessageRoutes routes, int tipMessageId,
                            ClientMessageService login, SceneLinks links, SessionRegistry registry, GateLimits limits,
                            GateMetrics metrics, PresenceRecorder presence) {
        this(identity, tokens, clock, routes, PushMessageIds.tipOnly(tipMessageId), login, Map.of(), links, registry, limits, metrics, presence,
                System::nanoTime);
    }

    /** @param backends login 以外的客户端消息后端（消息域 → Dubbo 服务），没有的域回「服务不可用」 */
    public ClientDispatcher(GateIdentity identity, GateTokens tokens, InstantSource clock, MessageRoutes routes, int tipMessageId,
                            ClientMessageService login, Map<String, ClientMessageService> backends, SceneLinks links,
                            SessionRegistry registry, GateLimits limits, GateMetrics metrics, PresenceRecorder presence) {
        this(identity, tokens, clock, routes, PushMessageIds.tipOnly(tipMessageId), login, backends, links, registry, limits, metrics, presence,
                System::nanoTime);
    }

    /** 同上；限频用的单调时钟可注入（测试用）。 */
    ClientDispatcher(GateIdentity identity, GateTokens tokens, InstantSource clock, MessageRoutes routes, int tipMessageId,
                     ClientMessageService login, SceneLinks links, SessionRegistry registry, GateLimits limits,
                     GateMetrics metrics, PresenceRecorder presence, LongSupplier nanoClock) {
        this(identity, tokens, clock, routes, PushMessageIds.tipOnly(tipMessageId), login, Map.of(), links, registry, limits, metrics, presence,
                nanoClock);
    }

    ClientDispatcher(GateIdentity identity, GateTokens tokens, InstantSource clock, MessageRoutes routes, int tipMessageId,
                     ClientMessageService login, Map<String, ClientMessageService> backends, SceneLinks links,
                     SessionRegistry registry, GateLimits limits, GateMetrics metrics, PresenceRecorder presence,
                     LongSupplier nanoClock) {
        this(identity, tokens, clock, routes, PushMessageIds.tipOnly(tipMessageId), login, backends, links, registry, limits,
                metrics, presence, nanoClock);
    }

    /**
     * 生产装配（批次 5.4 起）：两个推送消息号一起给——tip（23）与重定向通知（124）。上面四个只收 {@code tipMessageId} 的重载
     * 等于 {@link PushMessageIds#tipOnly}（重定向通知的号为 0），给不关心重定向的测试装配用。
     *
     * @param pushIds  gate 自己组包下发的推送消息号
     * @param backends login 以外的客户端消息后端（消息域 → Dubbo 服务），没有的域回「服务不可用」
     */
    public ClientDispatcher(GateIdentity identity, GateTokens tokens, InstantSource clock, MessageRoutes routes,
                            PushMessageIds pushIds, ClientMessageService login, Map<String, ClientMessageService> backends,
                            SceneLinks links, SessionRegistry registry, GateLimits limits, GateMetrics metrics,
                            PresenceRecorder presence) {
        this(identity, tokens, clock, routes, pushIds, login, backends, links, registry, limits, metrics, presence,
                System::nanoTime);
    }

    /** 同上；限频用的单调时钟可注入（测试用）。全部重载最终都到这里。 */
    ClientDispatcher(GateIdentity identity, GateTokens tokens, InstantSource clock, MessageRoutes routes,
                     PushMessageIds pushIds, ClientMessageService login, Map<String, ClientMessageService> backends,
                     SceneLinks links, SessionRegistry registry, GateLimits limits, GateMetrics metrics,
                     PresenceRecorder presence, LongSupplier nanoClock) {
        this.identity = identity;
        this.tokens = tokens;
        this.clock = clock;
        this.routes = routes;
        this.tipMessageId = pushIds.tip();
        this.redirectToGateMessageId = pushIds.redirectToGate();
        this.login = login;
        this.backends = Map.copyOf(backends);
        this.links = links;
        this.registry = registry;
        this.limits = limits;
        this.metrics = metrics;
        this.presence = presence;
        this.nanoClock = nanoClock;
        // 战斗上行拒绝平时恒为 0：装配时把这几条时间序列先建出来（见 GateMetrics#registerRequest）。
        // 手写的路由表（测试里的 lambda）不枚举，all() 为空，什么也不建。
        for (MessageRoute route : routes.all()) {
            if (route.directOnly()) {
                metrics.registerRequest(route.domain(), route.method(), RequestResult.BATTLE_REJECTED);
            }
        }
    }

    /** 同一会话层的指标（{@link ClientChannelHandler} / {@link ClientPipeline} 的连接级事件也记在这里）。 */
    GateMetrics metrics() {
        return metrics;
    }

    // ================================================================ 连接生命周期

    public void onConnected(ClientSession s) {
        s.scheduleHandshakeTimeout(limits.handshakeTimeout(), () -> {
            if (!s.verified && !s.closed) {
                logRejection("handshake_timeout", s);
                if (!s.closing) {
                    metrics.handshake(HandshakeResult.TIMEOUT);
                    metrics.disconnected(DisconnectReason.HANDSHAKE_TIMEOUT);
                }
                closeNow(s);
            }
        });
    }

    public void onTokenVerify(ClientSession s, ClientTokenVerifyRequest request) {
        if (s.closing || s.closed) {
            return;
        }
        if (s.redirected) {
            // 已重定向：客户端的一切上行都静默丢弃（含再次握手），等它自己断开或收口时限到
            return;
        }
        if (s.verified) {
            // 已验证的会话再次 verify 直接回成功（C++ 同）；不重新验票，会话上的票据字段也不被覆盖。
            s.send(VERIFY_OK);
            return;
        }
        GateTokens.Verdict verdict = tokens.verify(request.getPayload(), request.getSignature(),
                identity.nodeId(), identity.zoneId(), clock.instant().getEpochSecond());
        if (!verdict.ok()) {
            logRejection("token_" + verdict.failure(), s);
            metrics.handshake(HandshakeResult.of(verdict.failure()));
            metrics.disconnected(DisconnectReason.HANDSHAKE_REJECTED);
            s.closing = true;
            s.cancelHandshakeTimeout();
            s.sendThenClose(ClientTokenVerifyResponse.newBuilder()
                    .setSuccess(false)
                    .setError(clientError(verdict.failure()))
                    .build());
            return;
        }
        s.verified = true;
        // 只存不判（基线 DispatchTokenVerify :1092-1099）：gate 要到绑定会话才知道玩家，持票者是否就是进游戏的角色由 login 判（2011），
        // 在 gate 拒只会在 login 留下残留会话。普通登录令牌的 player_id 为 0
        s.ticketPlayerId = verdict.payload().getPlayerId();
        s.ticketTargetZoneId = verdict.payload().getTargetZoneId();
        s.cancelHandshakeTimeout();
        metrics.handshake(HandshakeResult.OK);
        s.send(VERIFY_OK);
    }

    public void onRequest(ClientSession s, ClientRequest request) {
        if (s.closing || s.closed) {
            if (s.verified) {
                countRequest(routes.clientRoute(request.getMessageId()), RequestResult.DROPPED);
            }
            return;
        }
        if (s.redirected) {
            // 已重定向（推过 124）：一切请求静默丢弃——不回包（也不回「未进场景」的信封 1003）、不计非法包、不限频、不转发
            // （zone-travel-spec §5.7；合规客户端此刻正在换连接，几秒内自己关闭）
            countRequest(routes.clientRoute(request.getMessageId()), RequestResult.REDIRECTED);
            return;
        }
        if (!s.verified) {
            // 断线自动重连的 robot 会在未握手的新连接上直接发业务包（robot 契约 §7.3）：拒绝并断开，不回包。
            logRejection("request_before_token_verify", s);
            metrics.handshake(HandshakeResult.MISSING);
            metrics.disconnected(DisconnectReason.NO_HANDSHAKE);
            closeNow(s);
            return;
        }
        MessageRoute route = routes.clientRoute(request.getMessageId());
        if (route == null) {
            countRequest(null, RequestResult.UNKNOWN_MESSAGE);
            registerIllegal(s, "unknown_message_id", request.getMessageId());
            return;
        }
        if (request.getSerializedSize() > MAX_REQUEST_BYTES) {
            s.send(envelopeError(request, TIP_MESSAGE_SIZE_EXCEEDED));
            countRequest(route, RequestResult.OVERSIZED);
            registerIllegal(s, "oversized", request.getMessageId());
            return;
        }
        if (!s.rateLimiter.tryAcquire(request.getMessageId(), limits.messageLimits().limitOf(request.getMessageId()),
                nanoClock.getAsLong())) {
            // C++ CheckMessageLimit 同形：信封错误 1008，计非法包（持续超频的连接到阈值被断开），不转发。
            s.send(envelopeError(request, TIP_RATE_LIMIT_EXCEEDED));
            countRequest(route, RequestResult.RATE_LIMITED);
            registerIllegal(s, "rate_limited", request.getMessageId());
            return;
        }
        if (route.gm() && !limits.gmCommandsAllowed()) {
            // GM 闸第一道锁（C++ ClassifyGmClientMessage，排在体积 / 限频之后）：回带请求 id 的信封 1006、计非法包、不转发。
            // 2026-10-15 起由 23 {1006} 改为信封（mmorpg client-request-ordering.md O6，两版同批）：这道闸排在后端 lane 之前，
            // 回执可能先于更早排队请求的应答到达，不带 id 的 23 会被客户端当成别的请求的失败。
            // scene 入口按同一判据还有第二道锁，防绕开 gate 直连链路端口。
            // 逐条只打 DEBUG（同基线：拒绝发生在踢线阈值之前，逐条 WARN 会让这道闸本身成为日志放大面）；
            // 趋势看指标 game_gate_client_requests_total{result="gm_rejected"}，踢线有 WARN
            log.debug("运行模式不允许 GM 指令，拒绝 session={} method={}", sid(s), route.method());
            s.send(envelopeError(request, TIP_FEATURE_UNAVAILABLE));
            countRequest(route, RequestResult.GM_REJECTED);
            registerIllegal(s, "gm_rejected", request.getMessageId());
            return;
        }
        if (route.directOnly()) {
            // 战斗上行只走客户端到 game-battle 的直连，gate 永不中继（基线 client_message_processor.cpp:937-950；
            // scene-battle-spec §2.5、§7.19，D12）。位置与基线相同：体积 / 限频 / GM 闸之后、按域分派之前——
            // 所以排在热关停之前（热关停了某个战斗方法时这里先拒，客户端看到的是本闸的信封 1003），
            // 也不进待处理队列（login 调用在途时当场回；队列满了不因它断连）。
            // 回执是带请求 id 的信封 1003（2026-10-15 起，原为 23 {1003}；mmorpg client-request-ordering.md O6，两版同批，
            // D12 已同步修订）：理由同 GM 闸。
            // 合法协议号（旧客户端或误走大厅）：不计非法包、不断连——踢线会把大厅连接一起拆掉。逐条只打 DEBUG，
            // 趋势看指标 game_gate_client_requests_total{result="battle_rejected"}
            log.debug("拒绝经 gate 的战斗消息（战斗只走客户端直连） session={} message_id={} method={}", sid(s),
                    request.getMessageId(), route.method());
            s.send(envelopeError(request, TIP_SERVICE_UNAVAILABLE));
            countRequest(route, RequestResult.BATTLE_REJECTED);
            return;
        }
        Optional<KillSwitch> killSwitch = KillSwitch.global();
        if (killSwitch.isPresent() && killSwitch.get().blocked(route.rpcPath()).isPresent()) {
            // 热关停（运维止血阀）：在限频之后、转发之前（同基线：gate 先按消息号限频，服务端拦截器再短路）；
            // 客户端看到的是信封 1003，与基线经路由服看到的一致；不计非法包
            log.debug("方法被热关停，拒绝 session={} method={}", sid(s), route.method());
            killSwitch.get().recordBlocked(route.method());
            countRequest(route, RequestResult.KILLED);
            s.send(envelopeError(request, TIP_SERVICE_UNAVAILABLE));
            return;
        }
        if (backends.containsKey(route.domain())) {
            enqueueBackend(s, route, request);
            return;
        }
        if (s.pending.size() >= limits.maxPendingRequests()) {
            log.warn("会话待处理请求超限，断开 session={} pending={} message_id={}",
                    sid(s), s.pending.size(), request.getMessageId());
            countRequest(route, RequestResult.OVERFLOW);
            metrics.disconnected(DisconnectReason.PENDING_OVERFLOW);
            closeNow(s);
            return;
        }
        if (isLeaveGame(route)) {
            s.leaveGameRequests++;
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
        // 客户端先关（已重定向的会话正常就是这样收场）：收口定时器作废
        s.cancelRedirectLinger();
        // 后端 lane 的延后放行一并作废：排队项随断线丢弃，不再发出
        s.cancelBackendRelease();
        discardPending(s, RequestResult.DROPPED);
        // 已重定向的会话没有场景绑定，这里什么也不发
        leaveScene(s, s.leaveGameRequests > 0);
        if (s.inFlight) {
            // 等在途的 login 调用完成后再通知 login（见 onLoginCompleted），否则 login 可能先收到断线、后处理完进游戏。
            return;
        }
        finishClose(s);
    }

    // ================================================================ 上行处理（同一会话严格按到达顺序）

    private void drain(ClientSession s) {
        while (!s.inFlight && !s.closing && !s.closed && !s.redirected) {
            PendingRequest next = s.pending.poll();
            if (next == null) {
                return;
            }
            dispatch(s, next);
        }
    }

    private void dispatch(ClientSession s, PendingRequest p) {
        switch (p.route().domain()) {
            case DOMAIN_LOGIN -> {
                countRequest(p.route(), RequestResult.FORWARDED);
                callLogin(s, p.request());
            }
            case DOMAIN_SCENE -> countRequest(p.route(), forwardToScene(s, p.request()));
            default -> {
                // Java 版尚未实现的后端域：与 C++ 找不到目标节点时同形。战斗服务 BattleClientPlayer 到不了这里：
                // 它在 onRequest 的直连闸（route.directOnly()）当场被拒，不进待处理队列。
                // 回带请求 id 的信封 1003（2026-10-15 起，原为 23 {1003}；mmorpg client-request-ordering.md O6，两版同批）：
                // 同一会话可能还有别的请求在等应答，不带 id 的 23 会被客户端当成别的请求的失败。
                log.debug("消息域未接入 Java 版 session={} message_id={} domain={}", sid(s), p.route().messageId(), p.route().domain());
                countRequest(p.route(), RequestResult.UNSUPPORTED);
                s.send(envelopeError(p.request(), TIP_SERVICE_UNAVAILABLE));
            }
        }
    }

    // ================================================================ login 以外的后端（friend ……）

    /**
     * 入会话后端 lane（{@link ClientSession#backendQueue}）：login 以外的全部后端域共用一条，按到达顺序逐条发出，
     * 上一条完成才发下一条，跨域同样保序（C++ 版 client-request-ordering.md O3；以前按域各一条、域之间并发）。
     * 不占会话唯一的 {@link ClientSession#inFlight}，不阻塞 login / scene。
     * 排队 + 在途达到 {@link #MAX_BACKEND_LANE_DEPTH} 回带请求 id 的信封 1008,不断连、不计非法包(与 C++ 版同形;
     * 以前是满 64 断连,会把大厅连接一起拆掉)。
     */
    private void enqueueBackend(ClientSession s, MessageRoute route, ClientRequest request) {
        ArrayDeque<BackendPending> queue = s.backendQueue;
        int depth = queue.size() + (s.backendInFlight ? 1 : 0);
        if (depth >= MAX_BACKEND_LANE_DEPTH) {
            log.debug("会话后端 lane 已满,回 1008 session={} depth={} message_id={} domain={}", sid(s), depth,
                    request.getMessageId(), route.domain());
            countRequest(route, RequestResult.LANE_FULL);
            s.send(envelopeError(request, TIP_RATE_LIMIT_EXCEEDED));
            return;
        }
        queue.add(new BackendPending(route, request, context(s), nanoClock.getAsLong()));
        drainBackend(s);
    }

    private void drainBackend(ClientSession s) {
        ArrayDeque<BackendPending> queue = s.backendQueue;
        while (!queue.isEmpty() && !s.backendInFlight && !s.closing && !s.closed && !s.redirected) {
            BackendPending next = queue.poll();
            if (nanoClock.getAsLong() - next.enqueuedAtNanos() > MAX_BACKEND_LANE_WAIT_NANOS) {
                // 排队太久:不再发出(客户端的 15 s 请求超时快到了,发出去也来不及回),明确回「没有执行」。
                countRequest(next.route(), RequestResult.QUEUE_EXPIRED);
                s.send(envelopeError(next.request(), TIP_SERVICE_UNAVAILABLE));
                continue;
            }
            String domain = next.route().domain();
            // 入队时已确认该域有后端（onRequest 的 backends.containsKey）;装配后 backends 不再变。
            ClientMessageService backend = backends.get(domain);
            countRequest(next.route(), RequestResult.FORWARDED);
            callBackend(s, domain, backend, next.request(), sessionAtSend(s, next));
        }
    }

    /**
     * 排队项发出时的会话身份：只降不升（mmorpg client-request-ordering.md §17，C++ 版 router_lane.h {@code PlayerIdAtSend} 同一规则）。
     * 入队快照里的玩家与会话当前绑定的玩家一致才保留，否则按 {@code player_id = 0} 发出：排队期间 LeaveGame 成功（UnbindPlayer）
     * 之后不以已离开的角色执行，排队期间换了角色也不算到新角色头上；快照为 0（入队时没进游戏）的照旧为 0。其余字段原样。
     */
    static SessionContext sessionAtSend(SessionContext snapshot, long currentPlayerId) {
        if (snapshot.getPlayerId() == 0 || snapshot.getPlayerId() == currentPlayerId) {
            return snapshot;
        }
        return snapshot.toBuilder().setPlayerId(0).clearOwnerRevision().build();
    }

    private SessionContext sessionAtSend(ClientSession s, BackendPending next) {
        SessionContext session = sessionAtSend(next.session(), s.playerId);
        if (session != next.session()) {
            log.debug("排队期间会话身份已变，按未进游戏发出 session={} message_id={} 入队时 player_id={} 现在 player_id={}", sid(s),
                    next.request().getMessageId(), next.session().getPlayerId(), s.playerId);
        }
        return session;
    }

    /**
     * login 以外的客户端消息后端：与 login 同一条 Dubbo 契约（{@link ClientMessageService#handle}）。这些后端不下发会话指令，
     * 断线不等它们（断线通知只给 login）；身份取请求<b>入队时</b>的会话快照（排队期间离开游戏 / 换角色进游戏不会把它算到新角色头上），
     * 发出时只降不升（{@link #sessionAtSend(SessionContext, long)}：离开游戏之后不再带已离开的角色），
     * 由后端按其中的玩家号判定（没进游戏的会话照常转发，由后端回它自己的码）。
     */
    private void callBackend(ClientSession s, String domain, ClientMessageService backend, ClientRequest request,
                             SessionContext session) {
        ClientCall call = ClientCall.newBuilder()
                .setSession(session)
                .setMessageId(request.getMessageId())
                .setBody(request.getBody())
                .setRequestId(request.getId())
                .build();
        s.backendInFlight = true;
        Timer.Sample sample = metrics.startTimer();
        long sentAtNanos = nanoClock.getAsLong();
        CompletableFuture<ClientReply> future;
        boolean handedOff = true;
        try {
            future = backend.handle(call);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
            handedOff = false;
        }
        if (future == null) {
            future = CompletableFuture.failedFuture(new IllegalStateException(domain + " 返回了 null future"));
            handedOff = false;
        }
        // 同步抛出 / 返回 null：请求没交给 Dubbo，失败时不必等（见 BACKEND_RESULT_UNKNOWN_HOLD_NANOS）
        boolean mayHaveReachedBackend = handedOff;
        future.whenComplete((reply, error) -> {
            metrics.backendCallCompleted(domain, sample, error == null && reply != null);
            s.execute(() -> onBackendCompleted(s, domain, request, reply, error, mayHaveReachedBackend, sentAtNanos));
        });
    }

    private void onBackendCompleted(ClientSession s, String domain, ClientRequest request, ClientReply reply,
                                    Throwable error, boolean mayHaveReachedBackend, long sentAtNanos) {
        if (s.closing || s.closed) {
            // 连接已断：不再向客户端发送；断线流程不等这些后端（会话可能已释放），什么都不用补
            s.backendInFlight = false;
            return;
        }
        if (error != null || reply == null) {
            // 同基线路由服（forwardlogic.go：上游任何错误或超时都翻成带请求 id 的信封 1003）：回带 id 的信封，客户端当场按信封错误处理；
            // 只推 23 {1003} 不回应答的话，客户端要等自己的 15 s 请求超时才知道失败（trade-spec T8）。
            log.warn("{} 调用失败 session={} message_id={} 原因={}", domain, sid(s), request.getMessageId(),
                    rootCause(error).toString());
            s.send(envelopeError(request, TIP_SERVICE_UNAVAILABLE));
            // 结果未知（请求可能已到后端、handler 可能还在跑）：回执照常立即给，但 lane 等到「发出 + 调用超时」才放行下一条。
            // reply == null 且没有异常说明 handler 已经跑完（只是返回了空），不算未知。
            long holdNanos = error != null && mayHaveReachedBackend
                    ? sentAtNanos + BACKEND_RESULT_UNKNOWN_HOLD_NANOS - nanoClock.getAsLong() : 0;
            if (holdNanos > 0 && s.scheduleBackendRelease(holdNanos, () -> onBackendReleaseDue(s))) {
                log.info("{} 调用结果未知，会话后端 lane 延后 {} ms 放行 session={} message_id={} 排队={}", domain,
                        TimeUnit.NANOSECONDS.toMillis(holdNanos), sid(s), request.getMessageId(), s.backendQueue.size());
                return;
            }
        } else {
            // 已重定向之前转出、之后才回来的应答也照常下发（zone-travel-spec §5.7：合规客户端已在换连接，无害）
            replyToClient(s, request, reply);
        }
        s.backendInFlight = false;
        drainBackend(s);
    }

    /**
     * 结果未知的延后放行到点（在会话所属 EventLoop 上）。直接持有会话对象而不按会话号重查：定时器只活到「发出 + 调用超时」，
     * 断线时撤销，到点仍先看 closing / closed；持有对象本身也就不会被复用的会话号带到别的连接上。
     */
    private void onBackendReleaseDue(ClientSession s) {
        s.backendInFlight = false;
        if (s.closing || s.closed) {
            return;
        }
        drainBackend(s);
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
        Timer.Sample sample = metrics.startTimer();
        CompletableFuture<ClientReply> future;
        try {
            future = login.handle(call);
        } catch (RuntimeException e) {
            future = CompletableFuture.failedFuture(e);
        }
        if (future == null) {
            future = CompletableFuture.failedFuture(new IllegalStateException("login 返回了 null future"));
        }
        future.whenComplete((reply, error) -> {
            metrics.loginCallCompleted(LoginCall.HANDLE, sample, error == null && reply != null);
            s.execute(() -> onLoginCompleted(s, request, reply, error));
        });
    }

    private void onLoginCompleted(ClientSession s, ClientRequest request, ClientReply reply, Throwable error) {
        s.inFlight = false;
        boolean succeededWhileClosing = (s.closing || s.closed) && error == null && reply != null;
        if (isLeaveGame(routes.clientRoute(request.getMessageId())) && s.leaveGameRequests > 0 && !succeededWhileClosing) {
            // 关闭途中成功的 LeaveGame 不清：它的 UnbindPlayer 来不及照常发主动离开，留给断线流程按主动离开发
            s.leaveGameRequests--;
        }
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
            // 与后端失败同形:带请求 id 的信封 1003,客户端当场按请求 id 对上失败,不用等 15 s 超时。
            // 以前推 23 {1003} 不带 id(对齐 C++ 基线的失败桥);C++ 版 gate 路由 lane 起失败回执一律带 id,两版同批改
            // (mmorpg client-request-ordering.md §4.7)。
            log.warn("login 调用失败 session={} message_id={} 原因={}", sid(s), request.getMessageId(), rootCause(error).toString());
            s.send(envelopeError(request, TIP_SERVICE_UNAVAILABLE));
        } else {
            // 先回应答、再执行指令：登录期重定向（RedirectToGate）的 26 应答因此恒先于 124。
            // 已重定向之前转出、之后才回来的应答照常下发，其中的指令由 applyDirectives 一律忽略
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
        for (int i = 0; i < directives.size(); i++) {
            if (s.closing || s.redirected) {
                // 会话已在关闭，或已重定向（之前的重定向帧 / 指令，或本应答里排在前面的 RedirectToGate）：余下的指令一律不再生效——
                // 已重定向的会话没有场景绑定，UnbindPlayer / EnterScene 都不能再生效，CloseSession 由收口定时器覆盖（zone-travel-spec §5.7）
                ignoreDirectives(s, directives.subList(i, directives.size()), s.closing ? "会话已在关闭" : "会话已重定向");
                return;
            }
            SessionDirective directive = directives.get(i);
            switch (directive.getKindCase()) {
                case BIND_ACCOUNT -> s.account = directive.getBindAccount().getAccount();
                case ENTER_SCENE -> enterScene(s, directive.getEnterScene());
                case CLOSE_SESSION -> closeByServer(s, directive.getCloseSession().getTipId());
                case UNBIND_PLAYER -> unbindPlayer(s);
                case REDIRECT_TO_GATE -> redirectByLogin(s, directive.getRedirectToGate());
                case KIND_NOT_SET -> log.warn("login 下发了空会话指令 session={}", sid(s));
            }
        }
    }

    /** 不再执行的指令：只记日志；其中的重定向指令按过期计数（每条重定向指令恰好计一次结局）。 */
    private void ignoreDirectives(ClientSession s, List<SessionDirective> directives, String reason) {
        for (SessionDirective directive : directives) {
            if (directive.hasRedirectToGate()) {
                log.info("忽略 login 的重定向指令（{}） session={} target_zone={}", reason, sid(s),
                        directive.getRedirectToGate().getRedirect().getTargetZoneId());
                metrics.redirect(RedirectSource.LOGIN, RedirectResult.STALE);
            } else {
                log.debug("忽略 login 的会话指令（{}） session={} kind={}", reason, sid(s), directive.getKindCase());
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
                    s.ownerRevision = enter.getOwnerRevision();
                    s.loginGeneration = enter.getOwnerEpoch();
                    s.ownerAccount = s.account;
                    abandonEnter(s, enter.getPlayerId(), enter.getOwnerEpoch(), "会话已在关闭");
                }
                case UNBIND_PLAYER -> { s.playerId = 0; s.ownerRevision = 0; s.loginGeneration = 0; s.ownerAccount = ""; }
                case REDIRECT_TO_GATE -> {
                    // 登录期重定向不夺权、不绑定玩家，没有要释放的东西；连接已断，124 也无处可推
                    log.info("会话已断，忽略迟到的重定向指令 session={} target_zone={}", sid(s),
                            directive.getRedirectToGate().getRedirect().getTargetZoneId());
                    metrics.redirect(RedirectSource.LOGIN, RedirectResult.STALE);
                }
                default -> {
                    // CloseSession 无需再执行：连接已断。
                }
            }
        }
    }

    private void enterScene(ClientSession s, EnterScene enter) {
        if (enter.getPlayerId() == 0 || enter.getSceneNodeId() == 0 || enter.getSceneId() == 0 || enter.getOwnerEpoch() <= 0
                || enter.getOwnerRevision() <= 0 || s.account.isBlank()) {
            log.error("login 下发的 EnterScene 非法 session={} player_id={} scene_node_id={}",
                    sid(s), enter.getPlayerId(), enter.getSceneNodeId());
            abandonEnter(s, enter.getPlayerId(), enter.getOwnerEpoch(), "进场指令非法");
            failEnter(s, TIP_ENTER_SCENE_FAILED);
            return;
        }
        // login 不会给已绑定玩家的会话下发进场（EnterGame 对这种会话回 2028，见 game-login EnterGameHandler），
        // 所以正常流程里这里不在场景中。仍先让旧场景放掉旧玩家：一个会话绝不同时挂两个场景实例（防御）。
        leaveScene(s, false);
        s.playerId = enter.getPlayerId();
        s.ownerRevision = enter.getOwnerRevision();
        s.loginGeneration = enter.getOwnerEpoch();
        s.ownerAccount = s.account;
        s.sceneNodeId = enter.getSceneNodeId();
        s.scenePlayerId = enter.getPlayerId();
        s.sceneOwnerEpoch = enter.getOwnerEpoch();
        PlayerEnter playerEnter = PlayerEnter.newBuilder()
                .setSessionId(s.sessionId())
                .setPlayerId(enter.getPlayerId())
                .setSceneId(enter.getSceneId())
                .setOwnerEpoch(enter.getOwnerEpoch())
                .setAccount(s.ownerAccount)
                .setOwnerRevision(s.ownerRevision)
                .setLoginGeneration(s.loginGeneration)
                .build();
        long gen = beginSceneEntry(s, enter.getSceneNodeId(), playerEnter);
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
        s.ownerRevision = 0;
        s.loginGeneration = 0;
        s.ownerAccount = "";
        if (!s.closing) {
            sendTip(s, tipId);
        }
    }

    private void closeByServer(ClientSession s, int tipId) {
        log.info("login 指示关闭会话 session={} tip={}", sid(s), tipId);
        metrics.disconnected(DisconnectReason.SERVER_DIRECTIVE);
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
        s.ownerRevision = 0;
        s.loginGeneration = 0;
        s.ownerAccount = "";
        log.info("会话离开游戏 session={} player_id={} scene_node_id={}", sid(s), playerId, sceneNodeId);
    }

    /** 转给会话所在的 scene；返回这个请求在 gate 的去向（记指标用）。 */
    private RequestResult forwardToScene(ClientSession s, ClientRequest request) {
        if (s.sceneNodeId == 0) {
            // 与 C++ HandleTcpNodeMessage 找不到场景节点时同形：回带请求 id 的信封 1003（2026-10-15 起，原为 23 {1003}；
            // mmorpg client-request-ordering.md O6，两版同批）。没有发出，客户端重试安全；应答类型为 Empty 的号同样回。
            s.send(envelopeError(request, TIP_SERVICE_UNAVAILABLE));
            return RequestResult.NOT_IN_SCENE;
        }
        ClientForward forward = ClientForward.newBuilder()
                .setSessionId(s.sessionId())
                .setPlayerId(s.scenePlayerId)
                .setMessageId(request.getMessageId())
                .setBody(request.getBody())
                .setRequestId(request.getId())
                .build();
        long gen = links.send(s.sceneNodeId, s.sceneLinkGen, NodeLinkFrame.newBuilder().setClientForward(forward).build());
        if (gen == 0) {
            // 链路层没受理这一帧（确定未发出）：回带请求 id 的信封 1003，客户端当场按请求 id 对上失败、重试安全。
            // 以前什么都不回，客户端只能等自己的 15 s 超时；对齐 mmorpg HandleTcpNodeMessage 的「RpcClient 未挂」
            // （client-request-ordering.md O6，2026-10-15）。应答类型为 Empty 的号同样回。
            s.send(envelopeError(request, TIP_SERVICE_UNAVAILABLE));
            return RequestResult.LINK_UNAVAILABLE;
        }
        return RequestResult.FORWARDED;
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

    /**
     * scene 的进场结果。只认会话当前绑定的那次进场（节点、链路代次、玩家、epoch 都对得上）。
     * 普通进场失败即回到未进游戏；交出进场（{@link ClientSession#transferEntering}）失败推 23 {tip} 后断开。
     */
    public void onPlayerEnterResult(ClientSession s, int sceneNodeId, long linkGen, PlayerEnterResult result) {
        if (s.closed || !s.boundTo(sceneNodeId, linkGen) || s.scenePlayerId != result.getPlayerId()) {
            return;
        }
        if (result.getOwnerEpoch() != s.sceneOwnerEpoch) {
            log.debug("丢弃更早一次进场的迟到结果 session={} player_id={} 结果 epoch={} 当前 epoch={} tip={}", sid(s),
                    result.getPlayerId(), result.getOwnerEpoch(), s.sceneOwnerEpoch, result.getTipId());
            return;
        }
        clearPendingSceneEntry(s); // scene 回包已证明 write 发生，不能再按本地未发送重试。
        if (s.transferEntering) {
            onTransferEnterResult(s, sceneNodeId, result);
            return;
        }
        if (result.getTipId() == 0) {
            if (!s.closing && !s.presenceOnline) {
                s.presenceOnline = true;
                presence.online(s.scenePlayerId, s.sessionId(), s.sceneOwnerEpoch);
            }
            return;
        }
        log.info("scene 拒绝进场 session={} player_id={} node={} tip={}", sid(s), s.scenePlayerId, sceneNodeId,
                result.getTipId());
        failEnter(s, result.getTipId());
    }

    /**
     * 交出进场的结果。成功：<b>无条件</b>以新 epoch 重登在线目录——gap 期间 {@code presenceOnline} 一直为 true（服务端推送照常送达），
     * 只看这个标记就不会重登，在线条目会停在旧 epoch（目录以 epoch 高者为准，新 epoch 的条目覆盖旧的）。
     * 失败：目标节点已释放这次进场的 epoch，源节点的实例早已移除，推 23 {tip} 后断开（不回大厅）。
     */
    private void onTransferEnterResult(ClientSession s, int sceneNodeId, PlayerEnterResult result) {
        s.transferEntering = false;
        if (result.getTipId() == 0) {
            metrics.sceneTransfer(SceneTransferResult.ENTERED);
            log.info("跨节点换图进场成功 session={} player_id={} node={} epoch={}", sid(s), s.scenePlayerId, sceneNodeId,
                    s.sceneOwnerEpoch);
            if (!s.closing) {
                s.presenceOnline = true;
                presence.online(s.scenePlayerId, s.sessionId(), s.sceneOwnerEpoch);
            }
            return;
        }
        metrics.sceneTransfer(SceneTransferResult.ENTER_FAILED);
        log.warn("目标 scene 拒绝跨节点换图进场，断开 session={} player_id={} node={} epoch={} tip={}", sid(s),
                s.scenePlayerId, sceneNodeId, s.sceneOwnerEpoch, result.getTipId());
        failTransfer(s, result.getTipId());
    }

    /**
     * 兼容不可重试的未送达回报（如排队溢出）：告诉 login 释放原 E；不要求会话仍在（G10）。
     * 生产临时故障经带 EnterFailure 的重载，在固定原身份与预算内补发；同 E 的旧尝试回调不能释放新尝试。
     * 不可重试时普通进场回到未进游戏；交出进场推 23 {3023} 后断开。补发预算耗尽则两者均关闭。
     */
    public void onEnterUndeliverable(ClientSession s, int sceneNodeId, long linkGen, PlayerEnter enter) {
        onEnterUndeliverable(s, sceneNodeId, linkGen, enter, EnterFailure.PERMANENT);
    }

    public void onEnterUndeliverable(ClientSession s, int sceneNodeId, long linkGen, PlayerEnter enter, EnterFailure failure) {
        PendingSceneEntry pending = s.pendingSceneEntry;
        if (pending != null && pending.enter.getPlayerId() == enter.getPlayerId()
                && pending.enter.getOwnerEpoch() == enter.getOwnerEpoch() && !pending.matches(sceneNodeId, linkGen, enter)) {
            return; // 旧尝试的重复回调不能释放新尝试仍持有的同一份 E。
        }
        if (pending == null && !s.closed && s.scenePlayerId == enter.getPlayerId() && s.sceneOwnerEpoch == enter.getOwnerEpoch()
                && s.sceneNodeId != 0) return; // 已派发或已确认的原 E 不再有本地未发送凭据。
        if (pending != null && pending.matches(sceneNodeId, linkGen, enter) && !s.closed && !s.closing && !s.redirected) {
            if (pending.knownUnsent) return; // 同一尝试只安排一次退避，重复回调既不重发也不释放。
            pending.knownUnsent = true;
            long delay = pending.retryDelay(failure, nanoClock.getAsLong());
            if (failure.retryable() && delay >= 0) {
                metrics.entryRetry(GateMetrics.EntryRetryOutcome.DEFERRED);
                armEntryExpiry(s, pending);
                try {
                    pending.retry = s.channel().eventLoop().schedule(() -> retrySceneEntry(s, pending), delay, TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException stopped) { expireSceneEntry(s, pending); }
                return;
            }
            if (failure.retryable()) { expireSceneEntry(s, pending); return; }
            clearPendingSceneEntry(s);
        }
        boolean current = !s.closed && s.boundTo(sceneNodeId, linkGen) && s.scenePlayerId == enter.getPlayerId()
                && s.sceneOwnerEpoch == enter.getOwnerEpoch();
        abandonEnter(s, enter.getPlayerId(), enter.getOwnerEpoch(), current ? "建链失败" : "建链失败（会话已不在这次进场上）");
        if (enter.getTransfer()) {
            metrics.sceneTransfer(SceneTransferResult.UNDELIVERABLE);
        }
        if (!current) {
            return;
        }
        // 与 scene 进场失败同一个码（scene 契约 §3.5：23 TipInfoMessage{3023}）。
        if (s.transferEntering) {
            log.warn("跨节点换图进场帧没能送到目标 scene，断开 session={} player_id={} node={} epoch={}", sid(s),
                    enter.getPlayerId(), sceneNodeId, enter.getOwnerEpoch());
            failTransfer(s, TIP_ENTER_SCENE_FAILED);
            return;
        }
        failEnter(s, TIP_ENTER_SCENE_FAILED);
    }

    /**
     * 路由层找不到会话（已断开并从会话表释放，或会话线程已停）时没送到 scene 的进场帧：同样请 login 释放（G10）。
     * 不碰任何会话状态，任意线程可调。
     */
    public void onEnterUndeliverableWithoutSession(int sceneNodeId, long linkGen, PlayerEnter enter) {
        if (enter.getTransfer()) {
            metrics.sceneTransfer(SceneTransferResult.UNDELIVERABLE);
        }
        abandonEnter(sessionlessContext(enter.getSessionId()), enter.getPlayerId(), enter.getOwnerEpoch(),
                "建链失败（会话已释放）");
    }

    // ================================================================ 跨节点换图改绑（批次 5.2，scene-handoff-spec §5.7）

    /**
     * 源 scene 的改绑指令。源节点已用一笔带围栏的交出事务把冻结快照落库、把归属从 from_epoch 推进到 to_epoch（E → E+1），
     * 实例已从源节点移除；E+1 只为这一帧铸出，{@code PlayerEnter{E+1}} 也只会由这个会话发出。
     * <ul>
     *   <li>会话已关闭 / 正在关闭，或绑定对不上（节点、链路代次、玩家、from_epoch 任一不符；会话号被复用给新会话时同样不符）：
     *       过期帧，请 login 带围栏放弃 E+1，不改绑；</li>
     *   <li>否则就地改绑（{@link #rebindForTransfer}）。</li>
     * </ul>
     * 改绑之后源节点的迟到帧（ToClient、PlayerKicked{E}、PlayerEnterResult{E}、链路断开）因 (节点, 代次) 不再匹配被丢弃；
     * 改绑之前源节点发出的下行（冻结中请求的应答等）与本帧同链路、按序先到，照常下发。
     *
     * <p>带 {@code redirect} 的帧不是改绑指令，而是跨 zone 传送的重定向（批次 5.4），先按 {@code hasRedirect()} 分流给
     * {@link #onTravelRedirect}，不走下面任何一条 5.2 判定。
     */
    public void onPlayerTransfer(ClientSession s, int sceneNodeId, long linkGen, PlayerTransfer transfer) {
        if (transfer.hasRedirect()) {
            onTravelRedirect(s, sceneNodeId, linkGen, transfer);
            return;
        }
        long playerId = transfer.getPlayerId();
        long fromEpoch = transfer.getFromEpoch();
        long toEpoch = transfer.getToEpoch();
        if (!s.closed && toEpoch > fromEpoch && s.scenePlayerId == playerId && s.sceneOwnerEpoch == toEpoch) {
            // 会话已按这条指令改绑过（E+1 只由这一次交出铸出，会话绑定到 E+1 只能是因为它）：重复帧不能当过期处理，
            // 否则会放弃目标节点正在用的 E+1。链路是 TCP、scene 只发一次，正常不会出现。
            log.warn("重复的改绑指令，忽略 session={} player_id={} node={} epoch={}→{}", sid(s), playerId, sceneNodeId,
                    fromEpoch, toEpoch);
            metrics.sceneTransfer(SceneTransferResult.STALE);
            return;
        }
        if (s.closed || s.closing || !s.boundTo(sceneNodeId, linkGen) || s.scenePlayerId != playerId
                || s.sceneOwnerEpoch != fromEpoch) {
            log.info("改绑指令已过期（会话已离开 / 已关闭 / 已换进别处），放弃新 epoch session={} player_id={} node={} gen={} epoch={}→{} "
                            + "会话绑定 node={} gen={} player_id={} epoch={}", sid(s), playerId, sceneNodeId, linkGen, fromEpoch,
                    toEpoch, s.sceneNodeId, s.sceneLinkGen, s.scenePlayerId, s.sceneOwnerEpoch);
            metrics.sceneTransfer(SceneTransferResult.STALE);
            abandonEnter(s, playerId, toEpoch, "改绑指令已过期");
            return;
        }
        if (transfer.getTargetSceneNodeId() == 0 || toEpoch <= fromEpoch) {
            // scene 的缺陷，不该出现。绑定不动：断线流程照常向源节点发 PlayerLeave（源节点若其实还持有实例就写回释放，
            // 已交出就只记位置）；新 epoch 合法时一并放弃。
            log.error("非法的改绑指令，断开 session={} player_id={} node={} 目标 node={} scene_id={} epoch={}→{}", sid(s),
                    playerId, sceneNodeId, transfer.getTargetSceneNodeId(), transfer.getTargetSceneId(), fromEpoch, toEpoch);
            metrics.sceneTransfer(SceneTransferResult.INVALID);
            if (toEpoch > fromEpoch) {
                abandonEnter(s, playerId, toEpoch, "改绑指令非法");
            }
            closeForTransfer(s, TIP_ENTER_SCENE_FAILED);
            return;
        }
        rebindForTransfer(s, transfer);
    }

    /**
     * 路由层找不到会话（已断开并从会话表释放，或会话线程已停）时的改绑指令：E+1 不会再有人为它发 PlayerEnter，
     * 请 login 带围栏放弃（scene-handoff-spec §5.7：会话关闭后很快就从会话表删除，不在路由层收尾这份归属就只能等租约过期）。
     * 带 {@code redirect} 的帧（跨 zone 传送，批次 5.4）只计 {@code orphan}、不放弃：源节点的交出已同时释放 E+1，没有可放弃的归属。
     * 不碰任何会话状态，任意线程可调。
     */
    public void onPlayerTransferWithoutSession(int sceneNodeId, long linkGen, PlayerTransfer transfer) {
        if (transfer.hasRedirect()) {
            log.info("跨 zone 重定向帧的会话已不在，丢弃（新 epoch 已由源节点释放） session={} player_id={} node={} gen={} epoch={}→{} "
                            + "target_zone={}", Integer.toUnsignedString(transfer.getSessionId()), transfer.getPlayerId(),
                    sceneNodeId, linkGen, transfer.getFromEpoch(), transfer.getToEpoch(),
                    transfer.getRedirect().getTargetZoneId());
            metrics.redirect(RedirectSource.TRAVEL, RedirectResult.ORPHAN);
            return;
        }
        log.info("改绑指令的会话已不在，放弃新 epoch session={} player_id={} node={} gen={} epoch={}→{}",
                Integer.toUnsignedString(transfer.getSessionId()), transfer.getPlayerId(), sceneNodeId, linkGen,
                transfer.getFromEpoch(), transfer.getToEpoch());
        metrics.sceneTransfer(SceneTransferResult.ORPHAN);
        abandonEnter(sessionlessContext(transfer.getSessionId()), transfer.getPlayerId(), transfer.getToEpoch(),
                "改绑指令的会话已释放");
    }

    /**
     * 就地改绑：<b>不发</b> PlayerLeave（源节点已移除实例）、<b>不撤</b>在线登记（gap 期间服务端推送不经 scene，照常送达）；
     * 绑定改为 (目标节点, 新代次, 同一玩家, E+1) 并标记交出进场中，再向目标节点发 {@code PlayerEnter{transfer = true}}。
     * 链路层不可用（send 返回 0）：放弃 E+1，推 23 {3023} 后断开。
     */
    private void rebindForTransfer(ClientSession s, PlayerTransfer transfer) {
        int fromNode = s.sceneNodeId;
        int targetNode = transfer.getTargetSceneNodeId();
        s.sceneNodeId = targetNode;
        s.sceneLinkGen = 0;
        s.sceneOwnerEpoch = transfer.getToEpoch();
        s.transferEntering = true;
        metrics.sceneTransfer(SceneTransferResult.REBOUND);
        PlayerEnter enter = PlayerEnter.newBuilder()
                .setSessionId(s.sessionId())
                .setPlayerId(s.scenePlayerId)
                .setSceneId(transfer.getTargetSceneId())
                .setOwnerEpoch(transfer.getToEpoch())
                .setAccount(s.ownerAccount)
                .setOwnerRevision(s.ownerRevision)
                .setLoginGeneration(s.loginGeneration)
                .setTransfer(true)
                .build();
        long gen = beginSceneEntry(s, targetNode, enter);
        if (gen == 0) {
            log.warn("跨节点换图改绑后到目标 scene 的链路层不可用，断开 session={} player_id={} 目标 node={} epoch={}", sid(s),
                    enter.getPlayerId(), targetNode, enter.getOwnerEpoch());
            metrics.sceneTransfer(SceneTransferResult.LINK_UNAVAILABLE);
            abandonEnter(s, enter.getPlayerId(), enter.getOwnerEpoch(), "链路层不可用");
            failTransfer(s, TIP_ENTER_SCENE_FAILED);
            return;
        }
        s.sceneLinkGen = gen;
        log.info("会话跨节点换图改绑 session={} player_id={} node={}→{} scene_id={} link_gen={} epoch={}→{}", sid(s),
                enter.getPlayerId(), fromNode, targetNode, transfer.getTargetSceneId(), gen, transfer.getFromEpoch(),
                transfer.getToEpoch());
    }

    /**
     * 交出之后没能落到目标节点：解绑场景（不发 PlayerLeave——目标节点要么没见过这次进场、要么已拒绝并释放；在线登记撤销），
     * 推 23 {tip} 后断开。不回大厅（会话上没有可回的场景）、不弹回源场景、不发 34（D5 / D11）。
     * 客户端重连后按位置记录与归属重新进游戏：E+1 已释放时立即可进，否则至多等租约过期。
     */
    private void failTransfer(ClientSession s, int tipId) {
        unbindScene(s);
        closeForTransfer(s, tipId);
    }

    private void closeForTransfer(ClientSession s, int tipId) {
        if (s.closing) {
            return;
        }
        metrics.disconnected(DisconnectReason.TRANSFER_FAILED);
        s.closing = true;
        discardPending(s, RequestResult.DROPPED);
        s.sendThenClose(tip(tipId != 0 ? tipId : TIP_ENTER_SCENE_FAILED));
    }

    // ================================================================ 跨 zone 重定向（批次 5.4，zone-travel-spec §5.7）

    /**
     * 源 scene 的跨 zone 传送重定向（{@code PlayerTransfer.redirect}）。源节点已用一笔「交出并释放」事务把冻结快照落库、
     * 把归属从 from_epoch 推进到 to_epoch 并同时释放（E+1 没有持有者，目标 zone 的 login 夺到 E+2），实例已移除。
     * <ol>
     *   <li>会话已关闭 / 正在关闭，或 (节点, 链路代次, 玩家, from_epoch) 对不上：过期帧，只计 {@code stale}——<b>不</b>放弃 E+1
     *       （已释放，没有可放弃的归属；与 5.2 改绑的过期处理不同）；</li>
     *   <li>帧内容非法（地址空、端口越界、票据或签名为空、同时带目标节点、新 epoch 不大于旧 epoch，或本 gate 没装配 124 的号）：
     *       计 {@code invalid}，解绑场景后推 23 {3027} 并断开（基线隐患「空 payload 兜底会推出空地址的 124」，inventory gate.md:321）；</li>
     *   <li>否则解绑场景（<b>不发</b> PlayerLeave——源节点已移除实例；撤在线登记），推 124，会话转入已重定向。</li>
     * </ol>
     * 这一帧之前源节点发出的下行（226 应答等）同链路先到、照常下发；之后源节点的迟到帧因绑定已清被丢弃。
     */
    private void onTravelRedirect(ClientSession s, int sceneNodeId, long linkGen, PlayerTransfer transfer) {
        long playerId = transfer.getPlayerId();
        long fromEpoch = transfer.getFromEpoch();
        long toEpoch = transfer.getToEpoch();
        ZoneRedirect redirect = transfer.getRedirect();
        if (s.closed || s.closing || !s.boundTo(sceneNodeId, linkGen) || s.scenePlayerId != playerId
                || s.sceneOwnerEpoch != fromEpoch) {
            log.info("跨 zone 重定向帧已过期（会话已离开 / 已关闭 / 已换进别处），丢弃（新 epoch 已由源节点释放） session={} player_id={} "
                            + "node={} gen={} epoch={}→{} target_zone={} 会话绑定 node={} gen={} player_id={} epoch={}", sid(s),
                    playerId, sceneNodeId, linkGen, fromEpoch, toEpoch, redirect.getTargetZoneId(), s.sceneNodeId,
                    s.sceneLinkGen, s.scenePlayerId, s.sceneOwnerEpoch);
            metrics.redirect(RedirectSource.TRAVEL, RedirectResult.STALE);
            return;
        }
        String invalid;
        if (transfer.getTargetSceneNodeId() != 0) {
            invalid = "同时带目标节点";
        } else if (toEpoch <= fromEpoch) {
            invalid = "新 epoch 不大于旧 epoch";
        } else {
            invalid = invalidRedirect(redirect);
        }
        if (invalid != null) {
            // scene 的缺陷，不该出现。源节点发这一帧之前已交出并移除实例：解绑（断线不再发 PlayerLeave），推 3027 后断开；
            // 客户端重登按位置记录与归属重新进游戏（E+1 已释放，立即可进）
            log.error("非法的跨 zone 重定向帧（{}），断开 session={} player_id={} node={} 目标 node={} epoch={}→{} target_zone={} "
                            + "gate_node={} gate={}:{} payload={}B signature={}B", invalid, sid(s), playerId, sceneNodeId,
                    transfer.getTargetSceneNodeId(), fromEpoch, toEpoch, redirect.getTargetZoneId(), redirect.getGateNodeId(),
                    redirect.getGateHost(), Integer.toUnsignedString(redirect.getGatePort()), redirect.getTokenPayload().size(),
                    redirect.getTokenSignature().size());
            metrics.redirect(RedirectSource.TRAVEL, RedirectResult.INVALID);
            unbindScene(s);
            closeForTransfer(s, TIP_ZONE_TRAVEL_FAILED);
            return;
        }
        unbindScene(s);
        log.info("会话跨 zone 传送：解绑源场景并推重定向 session={} player_id={} node={} epoch={}→{}", sid(s), playerId,
                sceneNodeId, fromEpoch, toEpoch);
        enterRedirected(s, RedirectSource.TRAVEL, redirect);
    }

    /**
     * login 的登录期重定向（GO-5：请求进游戏的角色此刻在别的 zone）。EnterGame 的应答已先发出（{@link #onLoginCompleted} 先回包、
     * 后执行指令），这里推 124、会话转入已重定向；会话本就没有场景绑定。指令内容非法：计 {@code invalid}，推 23 {3027} 后断开。
     */
    private void redirectByLogin(ClientSession s, RedirectToGate directive) {
        ZoneRedirect redirect = directive.getRedirect();
        String invalid = directive.hasRedirect() ? invalidRedirect(redirect) : "缺 redirect";
        if (invalid != null) {
            log.error("login 下发的重定向指令非法（{}），断开 session={} target_zone={} gate_node={} gate={}:{} payload={}B signature={}B",
                    invalid, sid(s), redirect.getTargetZoneId(), redirect.getGateNodeId(), redirect.getGateHost(),
                    Integer.toUnsignedString(redirect.getGatePort()), redirect.getTokenPayload().size(),
                    redirect.getTokenSignature().size());
            metrics.redirect(RedirectSource.LOGIN, RedirectResult.INVALID);
            closeForTransfer(s, TIP_ZONE_TRAVEL_FAILED);
            return;
        }
        if (s.sceneNodeId != 0) {
            // 防御：login 不会给已在场景里的会话下发（EnterGame 对已绑定玩家的会话回 2028）。已重定向的会话绝不挂着场景，先让场景放掉
            log.error("login 对仍在场景里的会话下发了重定向指令，先离场 session={} player_id={} node={}", sid(s), s.scenePlayerId,
                    s.sceneNodeId);
            leaveScene(s, false);
        }
        log.info("会话登录期重定向 session={} account={}", sid(s), s.account);
        enterRedirected(s, RedirectSource.LOGIN, redirect);
    }

    /**
     * 重定向载荷的内容校验（两种来源共用）。返回 null = 合法，否则是不合法的原因（只进日志）。
     * 只看推 124 用得到的字段；票据内容 gate 不解析、不验签（目标 gate 验），{@code target_zone_id} / {@code gate_node_id} 只进日志。
     */
    private String invalidRedirect(ZoneRedirect redirect) {
        if (redirectToGateMessageId == 0) {
            return "本 gate 没装配 124 的消息号";
        }
        if (redirect.getGateHost().isBlank()) {
            return "gate_host 为空";
        }
        int port = redirect.getGatePort();
        if (port < 1 || port > 65535) {
            return "gate_port 越界";
        }
        if (redirect.getTokenPayload().isEmpty()) {
            return "token_payload 为空";
        }
        if (redirect.getTokenSignature().isEmpty()) {
            return "token_signature 为空";
        }
        return null;
    }

    /**
     * 会话转入已重定向：丢弃排队中的请求（计 {@code redirected}）→ 推 124 → 挂收口定时器。调用方已解绑场景（或会话本就不在场景里）。
     * 在途的 login / 后端调用不撤：它们的应答回来照常下发、指令忽略。
     */
    private void enterRedirected(ClientSession s, RedirectSource source, ZoneRedirect redirect) {
        s.redirected = true;
        discardPending(s, RequestResult.REDIRECTED);
        s.send(redirectNotify(redirect));
        metrics.redirect(source, RedirectResult.SENT);
        // 定时任务只捕获会话号，不持有会话对象：到点重新查表、确认仍是这个已重定向的会话再关（AGENTS.md §3 回调生命周期）
        int sessionId = s.sessionId();
        s.scheduleRedirectLinger(limits.redirectLinger(), () -> onRedirectLingerExpired(sessionId));
        log.info("已推 124 重定向 session={} source={} target_zone={} gate_node={} gate={}:{} deadline={} linger={}s", sid(s),
                source, redirect.getTargetZoneId(), redirect.getGateNodeId(), redirect.getGateHost(),
                Integer.toUnsignedString(redirect.getGatePort()), redirect.getTokenDeadline(),
                limits.redirectLinger().toSeconds());
    }

    /**
     * 124 {@code RedirectToGateNotify}：五个字段逐一取自 {@link ZoneRedirect}（gate_host → target_ip、gate_port → target_port、
     * token_payload / token_signature / token_deadline 原样）。票据原字节直接拷贝（{@code ByteString} 不解析、不重新序列化，
     * 否则字节与签名不一定对得上）。信封 {@code MessageContent{message_id = 124}} <b>不设 id</b>（基线 gate_event_handler.cpp:234-271）。
     */
    private MessageContent redirectNotify(ZoneRedirect redirect) {
        RedirectToGateNotify notify = RedirectToGateNotify.newBuilder()
                .setTargetIp(redirect.getGateHost())
                .setTargetPort(redirect.getGatePort())
                .setTokenPayload(redirect.getTokenPayload())
                .setTokenSignature(redirect.getTokenSignature())
                .setTokenDeadline(redirect.getTokenDeadline())
                .build();
        return MessageContent.newBuilder()
                .setMessageId(redirectToGateMessageId)
                .setSerializedMessage(notify.toByteString())
                .build();
    }

    /**
     * 收口定时器到点（在原会话的 EventLoop 上）。定时器在断线时撤销、会话号要等断线流程结束才释放，所以查到的只可能是原会话；
     * 仍按「回调先查有效」再判一遍：会话已不在、已在关闭或不再是已重定向就什么都不做。
     */
    private void onRedirectLingerExpired(int sessionId) {
        ClientSession s = registry.get(sessionId);
        if (s == null) {
            return;
        }
        if (!s.inEventLoop()) {
            s.execute(() -> closeLingering(s));
            return;
        }
        closeLingering(s);
    }

    /** 已重定向的会话到收口时限仍没自己断开：直接关闭，不推任何 tip（客户端此刻要么已在新连接上，要么根本没实现跟随 124）。 */
    private void closeLingering(ClientSession s) {
        if (s.closed || s.closing || !s.redirected) {
            return;
        }
        log.info("已重定向的会话到收口时限仍未断开，关闭 session={} player_id={} linger={}s", sid(s), s.playerId,
                limits.redirectLinger().toSeconds());
        metrics.disconnected(DisconnectReason.REDIRECT_LINGER);
        closeNow(s);
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
        metrics.disconnected(DisconnectReason.KICKED);
        s.closing = true;
        discardPending(s, RequestResult.DROPPED);
        if (kicked.getTipId() != 0) {
            s.sendThenClose(tip(kicked.getTipId()));
        } else {
            s.close();
        }
    }

    // ================================================================ 服务端推送（GatePush，会话所属 EventLoop 上）

    /**
     * 服务端经推送通道发给玩家的消息：只发给「scene 已确认进场、在游戏里的正是这个玩家、没在关闭」的会话（玩家栅栏），
     * 否则丢弃（至多一次，业务方以拉取兜底）。消息原样下发（推送的 id 为 0）。
     */
    public PushResult deliverPush(ClientSession s, long playerId, MessageContent content) {
        if (!pushable(s, playerId)) {
            return PushResult.NOT_BOUND;
        }
        s.send(content);
        return PushResult.DELIVERED;
    }

    /** 服务端经推送通道踢下线：栅栏同 {@link #deliverPush}；推 23 {tip} 后关闭（断线流程照常：离场写回、通知 login）。 */
    public PushResult kickByServer(ClientSession s, long playerId, int tipId) {
        if (!pushable(s, playerId)) {
            return PushResult.NOT_BOUND;
        }
        log.info("服务端踢下线 session={} player_id={} tip={}", sid(s), playerId, tipId);
        metrics.disconnected(DisconnectReason.SERVER_KICK);
        s.closing = true;
        discardPending(s, RequestResult.DROPPED);
        s.sendThenClose(tip(tipId));
        return PushResult.DELIVERED;
    }

    private static boolean pushable(ClientSession s, long playerId) {
        return !s.closed && !s.closing && s.presenceOnline && s.scenePlayerId == playerId;
    }

    public void onSceneLinkDown(ClientSession s, int sceneNodeId, long linkGen) {
        onSceneLinkDown(s, sceneNodeId, linkGen, false);
    }

    public void onSceneLinkDown(ClientSession s, int sceneNodeId, long linkGen, boolean superseded) {
        if (s.closed || !s.boundTo(sceneNodeId, linkGen)) {
            return;
        }
        if (s.pendingSceneEntry != null && s.pendingSceneEntry.knownUnsent
                && s.pendingSceneEntry.generation == linkGen) return; // 该帧已被未发送回调认领，正在退避补发。
        // scene 那一侧已随链路丢掉这个会话（并负责释放 / 写回）；gate 关闭连接，客户端重连后重新进场。链路已断，不发 PlayerLeave。
        log.info("scene 链路断开，关闭其上的会话 session={} player_id={} node={}", sid(s), s.scenePlayerId, sceneNodeId);
        boolean transferPending = s.transferEntering;
        unbindScene(s);
        if (s.closing) return;
        metrics.disconnected(superseded ? DisconnectReason.SCENE_LINK_SUPERSEDED : DisconnectReason.SCENE_LINK_DOWN);
        s.closing = true;
        discardPending(s, RequestResult.DROPPED);
        // 交出进场尚未确认沿用 5.2 F11：仅断开，不能把未知进场结局当成可重试的普通服务错误。
        if (transferPending) s.close();
        else s.sendThenClose(tip(TIP_SERVICE_UNAVAILABLE));
    }

    // ================================================================ 内部

    /**
     * 在场景里就发 {@code PlayerLeave}（玩家取场景绑定上记的那个）并解绑场景（玩家绑定不动）；不在场景里什么也不做。
     *
     * @param voluntary true = 客户端主动 LeaveGame；false = 断线 / 换进别的场景
     */
    /** 路由是不是 LeaveGame（17）：按契约方法名认，不写死消息号。 */
    private static boolean isLeaveGame(MessageRoute route) {
        return route != null && LEAVE_GAME_METHOD.equals(route.method());
    }

    private void leaveScene(ClientSession s, boolean voluntary) {
        PendingSceneEntry pending = s.pendingSceneEntry;
        if (pending != null) {
            boolean cancelled = pending.knownUnsent || links.cancelQueuedEnter(pending.nodeId, pending.generation, pending.enter);
            clearPendingSceneEntry(s);
            if (cancelled) {
                metrics.entryRetry(GateMetrics.EntryRetryOutcome.CANCELLED);
                abandonEnter(s, pending.enter.getPlayerId(), pending.enter.getOwnerEpoch(), "客户端离开，撤销尚未发送的进场");
                unbindScene(s);
                return;
            }
        }
        if (s.sceneNodeId == 0) {
            return;
        }
        PlayerLeave leave = PlayerLeave.newBuilder()
                .setSessionId(s.sessionId())
                .setPlayerId(s.scenePlayerId)
                .setVoluntary(voluntary)
                .build();
        links.send(s.sceneNodeId, s.sceneLinkGen, NodeLinkFrame.newBuilder().setPlayerLeave(leave).build());
        unbindScene(s);
    }

    /** 解绑场景：场景绑定结束的唯一出口，在线目录同时撤销（离场、进场失败、被踢、链路断开、断线都经过这里）。 */
    private void unbindScene(ClientSession s) {
        clearPendingSceneEntry(s);
        if (s.presenceOnline) {
            s.presenceOnline = false;
            presence.offline(s.scenePlayerId, s.sessionId());
        }
        s.sceneNodeId = 0;
        s.sceneLinkGen = 0;
        s.scenePlayerId = 0;
        s.sceneOwnerEpoch = 0;
        s.transferEntering = false;
    }

    /** 每次只保存一份原进场；与 scene 交出的 SQL E、登录代、账号修订完全一致。 */
    private long beginSceneEntry(ClientSession s, int node, PlayerEnter enter) {
        clearPendingSceneEntry(s);
        PendingSceneEntry pending = new PendingSceneEntry(node, enter, nanoClock.getAsLong());
        s.pendingSceneEntry = pending;
        long generation = submitSceneEntry(s, pending);
        if (generation == 0) clearPendingSceneEntry(s);
        else armEntryExpiry(s, pending);
        return generation;
    }

    private long submitSceneEntry(ClientSession s, PendingSceneEntry pending) {
        pending.attempts++;
        pending.knownUnsent = false;
        long generation = links.send(pending.nodeId, NodeLinkFrame.newBuilder().setPlayerEnter(pending.enter).build());
        pending.generation = generation;
        s.sceneLinkGen = generation;
        if (generation == 0) pending.knownUnsent = true;
        return generation;
    }

    private void retrySceneEntry(ClientSession s, PendingSceneEntry pending) {
        pending.retry = null;
        if (!currentEntry(s, pending)) { cancelStaleSceneEntry(s, pending); return; }
        if (nanoClock.getAsLong() - pending.deadline >= 0 || pending.attempts >= PendingSceneEntry.MAX_ATTEMPTS) {
            expireSceneEntry(s, pending); return;
        }
        if (submitSceneEntry(s, pending) == 0) expireSceneEntry(s, pending);
    }

    private void armEntryExpiry(ClientSession s, PendingSceneEntry pending) {
        if (pending.expiry != null) pending.expiry.cancel(false);
        try {
            pending.expiry = s.channel().eventLoop().schedule(() -> expireSceneEntry(s, pending),
                    Math.max(0, pending.deadline - nanoClock.getAsLong()), TimeUnit.NANOSECONDS);
        } catch (RejectedExecutionException stopped) { expireSceneEntry(s, pending); }
    }

    private static boolean currentEntry(ClientSession s, PendingSceneEntry pending) {
        return s.pendingSceneEntry == pending && !s.closed && !s.closing && !s.redirected
                && s.scenePlayerId == pending.enter.getPlayerId() && s.sceneOwnerEpoch == pending.enter.getOwnerEpoch()
                && s.ownerRevision == pending.enter.getOwnerRevision() && s.loginGeneration == pending.enter.getLoginGeneration()
                && s.ownerAccount.equals(pending.enter.getAccount()) && s.sceneNodeId == pending.nodeId;
    }

    private void expireSceneEntry(ClientSession s, PendingSceneEntry pending) {
        if (!currentEntry(s, pending)) { cancelStaleSceneEntry(s, pending); return; }
        boolean cancelled = pending.knownUnsent || links.cancelQueuedEnter(pending.nodeId, pending.generation, pending.enter);
        // false 也可能是 fail 已摘队列、未发送回调尚在本 EventLoop 排队；保留原件等其证明，不能误吞该回调。
        if (!cancelled) return; // flush 赢了竞态则等 dispatched 清理；不重试、不释放 E、不引入应答超时。
        clearPendingSceneEntry(s);
        metrics.entryRetry(GateMetrics.EntryRetryOutcome.EXHAUSTED);
        abandonEnter(s, pending.enter.getPlayerId(), pending.enter.getOwnerEpoch(), "进场本地补发预算用尽");
        log.warn("进场补发放弃 session={} player={} node={} attempts={} elapsed_ms={}", sid(s),
                pending.enter.getPlayerId(), pending.nodeId, pending.attempts,
                TimeUnit.NANOSECONDS.toMillis(nanoClock.getAsLong() - pending.started));
        unbindScene(s);
        if (pending.enter.getTransfer()) closeForTransfer(s, TIP_ENTER_SCENE_FAILED);
        else if (!s.closing) {
            metrics.disconnected(DisconnectReason.SCENE_ENTRY_FAILED);
            s.closing = true;
            discardPending(s, RequestResult.DROPPED);
            s.sendThenClose(tip(TIP_ENTER_SCENE_FAILED));
        }
    }

    public void onEnterDispatched(ClientSession s, int node, long generation, PlayerEnter enter) {
        PendingSceneEntry pending = s.pendingSceneEntry;
        if (pending != null && pending.matches(node, generation, enter)) {
            if (pending.attempts > 1) metrics.entryRetry(GateMetrics.EntryRetryOutcome.RECOVERED);
            clearPendingSceneEntry(s);
        }
    }

    private void cancelStaleSceneEntry(ClientSession s, PendingSceneEntry pending) {
        if (s.pendingSceneEntry != pending) return;
        boolean cancelled = pending.knownUnsent || links.cancelQueuedEnter(pending.nodeId, pending.generation, pending.enter);
        clearPendingSceneEntry(s);
        if (cancelled) {
            metrics.entryRetry(GateMetrics.EntryRetryOutcome.CANCELLED);
            abandonEnter(s, pending.enter.getPlayerId(), pending.enter.getOwnerEpoch(), "原进场身份已被会话替换");
        }
    }

    private static void clearPendingSceneEntry(ClientSession s) {
        PendingSceneEntry pending = s.pendingSceneEntry;
        s.pendingSceneEntry = null;
        if (pending != null) pending.cancelTimers();
    }

    private void abandonEnter(ClientSession s, long playerId, long ownerEpoch, String reason) {
        abandonEnter(context(s), playerId, ownerEpoch, reason);
    }

    /**
     * 一份归属确定没有送到任何 scene（进游戏夺得的或跨节点交出铸出的，对应的 PlayerEnter 从未写上链路 / 不会再发）：
     * 告诉 login 释放（带 epoch 围栏：已被新的夺权取代或已释放时什么也不做），玩家不必等归属租约过期就能再进。
     * 尽力而为，失败只记日志。只读不可变字段、异步 Dubbo，任意线程可调。
     *
     * @param session 只用于 login 侧日志（会话已释放时由 {@link #sessionlessContext} 补齐）
     */
    private void abandonEnter(SessionContext session, long playerId, long ownerEpoch, String reason) {
        if (playerId == 0 || ownerEpoch == 0) {
            return;
        }
        log.info("进场未送达 scene，请 login 释放归属 session={} player_id={} epoch={} 原因={}",
                Integer.toUnsignedString(session.getSessionId()), playerId, ownerEpoch, reason);
        AbandonedEnter event = AbandonedEnter.newBuilder()
                .setSession(session)
                .setPlayerId(playerId)
                .setOwnerEpoch(ownerEpoch)
                .build();
        Timer.Sample sample = metrics.startTimer();
        try {
            CompletableFuture<?> future = login.abandonEnter(event);
            if (future != null) {
                future.whenComplete((ack, error) -> {
                    metrics.loginCallCompleted(LoginCall.ABANDON_ENTER, sample, error == null);
                    if (error != null) {
                        log.warn("通知 login 释放未送达进场的归属失败（等租约过期） player_id={} epoch={} 原因={}",
                                playerId, ownerEpoch, rootCause(error).toString());
                    }
                });
            } else {
                metrics.loginCallCompleted(LoginCall.ABANDON_ENTER, sample, false);
            }
        } catch (RuntimeException e) {
            metrics.loginCallCompleted(LoginCall.ABANDON_ENTER, sample, false);
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
        Timer.Sample sample = metrics.startTimer();
        try {
            CompletableFuture<?> future = login.sessionClosed(event);
            if (future != null) {
                future.whenComplete((ack, error) -> {
                    metrics.loginCallCompleted(LoginCall.SESSION_CLOSED, sample, error == null);
                    if (error != null) {
                        log.warn("通知 login 会话结束失败 session={} player_id={} 原因={}",
                                sid(s), event.getSession().getPlayerId(), rootCause(error).toString());
                    }
                });
            } else {
                metrics.loginCallCompleted(LoginCall.SESSION_CLOSED, sample, false);
            }
        } catch (RuntimeException e) {
            metrics.loginCallCompleted(LoginCall.SESSION_CLOSED, sample, false);
            log.warn("通知 login 会话结束失败 session={} 原因={}", sid(s), e.toString());
        }
    }

    /**
     * 会话上下文：所有转 login 与后端域的调用（客户端消息、断线通知、未送达进场的释放）都经它，同基线「两种转发模式同一个
     * BuildSessionDetails」（inventory gate.md:330，基线曾漏掉一种）。票据的持票者与目标 zone（8 / 9 号字段）也在这里带上。
     */
    private SessionContext context(ClientSession s) {
        return SessionContext.newBuilder()
                .setGateNodeId(identity.nodeId())
                .setGateInstanceId(identity.instanceId())
                .setSessionId(s.sessionId())
                .setZoneId(identity.zoneId())
                .setAccount(s.account)
                .setPlayerId(s.playerId)
                .setOwnerRevision(s.ownerRevision)
                .setClientIp(s.clientIp())
                .setTicketPlayerId(s.ticketPlayerId)
                .setTicketTargetZoneId(s.ticketTargetZoneId)
                .build();
    }

    /** 会话已不在会话表里时的上下文：只有本 gate 的身份与帧里的会话号（login 只拿它打日志），不带票据字段。 */
    private SessionContext sessionlessContext(int sessionId) {
        return SessionContext.newBuilder()
                .setGateNodeId(identity.nodeId())
                .setGateInstanceId(identity.instanceId())
                .setSessionId(sessionId)
                .setZoneId(identity.zoneId())
                .build();
    }

    private void registerIllegal(ClientSession s, String reason, int messageId) {
        s.illegalPackets++;
        log.debug("非法包 session={} reason={} message_id={} count={}", sid(s), reason, messageId, s.illegalPackets);
        int threshold = limits.illegalPacketThreshold();
        if (threshold > 0 && s.illegalPackets >= threshold) {
            log.warn("非法包达到阈值，断开 session={} count={} reason={} message_id={}", sid(s), s.illegalPackets, reason, messageId);
            metrics.disconnected(DisconnectReason.ILLEGAL_PACKETS);
            closeNow(s);
        }
    }

    /** 记一个请求的去向；{@code route} 为 null 表示消息号不认识。 */
    private void countRequest(MessageRoute route, RequestResult result) {
        if (route == null) {
            metrics.request(null, null, result);
        } else {
            metrics.request(route.domain(), route.method(), result);
        }
    }

    /**
     * 丢弃还没处理的排队请求（login / scene 的会话队列与会话后端 lane），逐个计去向（每个请求在 gate 只计一次）：
     * 会话关闭时计 {@code dropped}，会话转入已重定向时计 {@code redirected}。
     */
    private void discardPending(ClientSession s, RequestResult result) {
        for (PendingRequest p : s.pending) {
            countRequest(p.route(), result);
        }
        s.pending.clear();
        for (BackendPending p : s.backendQueue) {
            countRequest(p.route(), result);
        }
        s.backendQueue.clear();
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
