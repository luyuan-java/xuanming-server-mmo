package com.game.gate.metrics;

import com.game.api.proto.NodeLinkFrame;
import com.game.common.token.GateTokens;
import com.game.net.client.ClientFrameException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.composite.CompositeMeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/**
 * gate 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出，见 architecture.md §11）。
 * 指标名与标签只在这里定义，业务代码只调语义方法。
 *
 * <p><b>标签基数约束</b>（AGENTS.md §5）：不以 player_id / session_id / 账号 / IP 作标签。消息维度只用契约里客户端可发的
 * 方法名（{@code MessageIdRegistry} 的客户端白名单，有界）；不认识的消息号一律归到 {@code unknown}，
 * 公网流量造不出新的时间序列。其余标签都是本类里的枚举。
 *
 * <p><b>线程安全</b>：任意线程可调（Netty I/O 线程、Dubbo 回调线程、链路线程）。枚举维度的计数器在构造时建好放进只读表，
 * 热路径不拼标签；按消息方法的计数器首次出现时注册一次，之后查表（例外：战斗上行拒绝 {@code result=battle_rejected}
 * 的那几条由会话层在装配时经 {@link #registerRequest} 预建）。
 */
public final class GateMetrics {

    static final String SESSIONS_ACTIVE = "xm.gate.sessions.active";
    static final String SCENE_LINKS = "xm.gate.scene.links";
    static final String HANDSHAKES = "xm.gate.handshakes";
    static final String CLIENT_REQUESTS = "xm.gate.client.requests";
    static final String INVALID_FRAMES = "xm.gate.client.invalid.frames";
    static final String DISCONNECTS = "xm.gate.disconnects";
    static final String BACKEND_CALLS = "xm.gate.backend.calls";
    static final String LINK_FRAMES = "xm.gate.link.frames";
    static final String LINK_DROPPED = "xm.gate.link.dropped";
    static final String LINK_EVENTS = "xm.gate.link.events";
    static final String PUSHES = "xm.gate.pushes";
    static final String SCENE_TRANSFERS = "xm.gate.scene.transfers";
    static final String REDIRECTS = "xm.gate.redirects";

    /** 不在客户端白名单里的消息号（以及没有路由的请求）统一用的标签值。 */
    public static final String UNKNOWN = "unknown";

    /**
     * 延迟直方图的桶边界（Prometheus {@code _bucket{le=...}}）。固定 11 个，覆盖 5ms～10s（Dubbo 调用超时 5s）；
     * 不开百分位直方图：它每个时间序列要带上百个桶。
     */
    static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};

    /** 令牌握手结果（{@code xm.gate.handshakes{result}}）。 */
    public enum HandshakeResult {
        OK,
        BAD_SIGNATURE,
        BAD_PAYLOAD,
        WRONG_GATE,
        WRONG_ZONE,
        EXPIRED,
        /** 连上后在时限内没握手。 */
        TIMEOUT,
        /** 没握手就发业务包（断线自动重连的 robot 会这样）。 */
        MISSING;

        public static HandshakeResult of(GateTokens.Failure failure) {
            return switch (failure) {
                case BAD_SIGNATURE -> BAD_SIGNATURE;
                case BAD_PAYLOAD -> BAD_PAYLOAD;
                case WRONG_GATE -> WRONG_GATE;
                case WRONG_ZONE -> WRONG_ZONE;
                case EXPIRED -> EXPIRED;
            };
        }
    }

    /**
     * 已握手会话上的一个 {@code ClientRequest} 在 gate 的最终去向（{@code xm.gate.client.requests{result}}），每个请求恰好计一次。
     * {@code unknown_message} / {@code oversized} / {@code rate_limited} / {@code gm_rejected} 四种即 C++ gate 的「非法包」。
     */
    public enum RequestResult {
        /** 已交给后端（login 调用已发出 / scene 帧已被链路层受理）。login 调用的结果另见 {@code xm.gate.backend.calls}。 */
        FORWARDED,
        /** scene 域消息，但会话不在场景里：推 23 {1003}。 */
        NOT_IN_SCENE,
        /** scene 链路层不可用（帧未被受理）。 */
        LINK_UNAVAILABLE,
        /** Java 版尚未接入的后端域：推 23 {1003}。 */
        UNSUPPORTED,
        /** 消息号不存在或不属于客户端协议服务：不回包，计非法包。 */
        UNKNOWN_MESSAGE,
        /** 整包超过 1KB：回 1010，计非法包。 */
        OVERSIZED,
        /** 按消息号超频：回 1008，计非法包。 */
        RATE_LIMITED,
        /** GM 类指令而运行模式不是 dev / test：推 23 {1006}，计非法包，不转发。 */
        GM_REJECTED,
        /**
         * 只走客户端直连的号（战斗服务 {@code BattleClientPlayer} 的 12 个号）发到了大厅连接上：GM 闸之后当场推 23 {1003}，
         * 不计非法包、不断连、不进待处理队列、不过热关停（scene-battle-spec §7.19，D12）。正常客户端不会发，持续增长说明有
         * 旧客户端或误走大厅的战斗上行。这 12 个时间序列在装配时预建（{@link GateMetrics#registerRequest}）。
         */
        BATTLE_REJECTED,
        /** 命中热关停规则：回信封 1003（同基线经路由服看到的形状），不转发、不计非法包。 */
        KILLED,
        /** 会话排队请求超限：断开。 */
        OVERFLOW,
        /** 会话已在关闭，排队中或迟到的请求被丢弃。 */
        DROPPED,
        /**
         * 会话已被重定向（推过 124 RedirectToGateNotify，批次 5.4）：之后收到的请求一律丢弃、<b>不回包</b>、不计非法包，
         * 等客户端自己断开或收口时限到（zone-travel-spec §5.7）。
         */
        REDIRECTED
    }

    /** gate 主动断开连接的原因（{@code xm.gate.disconnects{reason}}），每次主动断开恰好计一次；客户端自己断开、停服 / 丢租约的批量关闭不计。 */
    public enum DisconnectReason {
        HANDSHAKE_TIMEOUT,
        HANDSHAKE_REJECTED,
        NO_HANDSHAKE,
        /** 非法包累计到阈值。 */
        ILLEGAL_PACKETS,
        PENDING_OVERFLOW,
        /** 客户端写缓冲越过高水位（客户端不读）。 */
        WRITE_BUFFER_FULL,
        /** 帧格式非法（解码层）。 */
        INVALID_FRAME,
        SESSION_ID_EXHAUSTED,
        /** login 下发 CloseSession。 */
        SERVER_DIRECTIVE,
        /** scene 发来 PlayerKicked（顶号 / 失去归属）。 */
        KICKED,
        /** 会话所在的 scene 链路断开。 */
        SCENE_LINK_DOWN,
        /** 服务端经推送通道踢下线（GatePush.kick_tip_id）。 */
        SERVER_KICK,
        /**
         * 跨节点换图交出之后没能落到目标节点（目标节点拒绝、到目标的链路不可用 / 建链失败、改绑指令非法）：
         * 推 23 {tip} 后断开，不回大厅、不发 34（scene-handoff-spec D5 / D11）。
         */
        TRANSFER_FAILED,
        /**
         * 已重定向的会话（推过 124，批次 5.4）在收口时限（{@code xm.gate.redirect-linger}）内没有自己断开：到点直接关，不推 tip。
         */
        REDIRECT_LINGER
    }

    /** 重定向（给客户端推 124 RedirectToGateNotify，批次 5.4）的来源（{@code xm.gate.redirects{source}}）。 */
    public enum RedirectSource {
        /** 226 跨 zone 传送：源 scene 发来带 {@code redirect} 的 {@code PlayerTransfer}。 */
        TRAVEL,
        /** 登录期重定向（GO-5）：login 在 EnterGame 的应答里带 {@code RedirectToGate} 会话指令。 */
        LOGIN
    }

    /**
     * 一次重定向在 gate 的结局（{@code xm.gate.redirects{result}}），每条重定向帧 / 每条重定向指令恰好计一次。
     * 重定向帧<b>不</b>计进 {@code xm.gate.scene.transfers}：那个指标与 5.2 改绑的 {@code handed_off} 勾稽，不能混。
     * 勾稽：scene 侧 {@code transfers{reason = travel, result = handed_off}} ≈ {@code redirects{source = travel}} 的 sent + invalid + stale + orphan。
     */
    public enum RedirectResult {
        /** 已给客户端推 124，会话转入已重定向状态。 */
        SENT,
        /** 绑定对得上但内容非法（地址空、端口越界、票据或签名为空、同时带目标节点、新 epoch 不大于旧 epoch）：推 23 {3027} 后断开。 */
        INVALID,
        /** 会话线程上判定过期（会话已关闭 / 正在关闭，或绑定对不上）：丢弃，不推 124。 */
        STALE,
        /** 路由层找不到会话（已断开并从会话表释放）：丢弃。只会出现在 {@code source = travel}。 */
        ORPHAN
    }

    /**
     * 跨节点换图改绑（源 scene 的 {@code PlayerTransfer}）在 gate 的结局（{@code xm.gate.scene.transfers{result}}）。
     * 每条改绑指令恰好计 {@code rebound} / {@code stale} / {@code orphan} / {@code invalid} 之一；
     * 每次 {@code rebound} 至多再计 {@code entered} / {@code enter_failed} / {@code link_unavailable} / {@code undeliverable} 之一
     * （之间断线、离开游戏、目标链路断开的不计）。勾稽：scene 侧 {@code transfers{handed_off}} ≈ rebound + stale + orphan。
     */
    public enum SceneTransferResult {
        /** 会话已改绑到目标节点，并向它发出（或尝试发出）{@code PlayerEnter{transfer = true}}。 */
        REBOUND,
        /** 会话线程上判定过期（会话已关闭 / 正在关闭，或绑定对不上）：请 login 放弃新 epoch，不改绑。 */
        STALE,
        /** 路由层找不到会话（已断开并从会话表释放）：请 login 放弃新 epoch。 */
        ORPHAN,
        /** 绑定对得上但帧内容非法（目标节点为 0、新 epoch 不大于旧 epoch）：推 23 {3023} 后断开。 */
        INVALID,
        /** 改绑后到目标节点的链路层不可用（send 返回 0）：放弃新 epoch，推 23 {3023} 后断开。 */
        LINK_UNAVAILABLE,
        /** 交出进场帧没能送到目标节点（建链失败 / 排队溢出）：放弃新 epoch；会话仍在这次进场上时推 23 {3023} 后断开。 */
        UNDELIVERABLE,
        /** 目标节点确认交出进场。 */
        ENTERED,
        /** 目标节点拒绝交出进场（已由目标节点释放新 epoch）：推 23 {tip} 后断开。 */
        ENTER_FAILED
    }

    /** gate 对 login 的 Dubbo 调用（{@code ClientMessageService} 的方法）。 */
    public enum LoginCall {
        HANDLE("handle"),
        SESSION_CLOSED("sessionClosed"),
        ABANDON_ENTER("abandonEnter");

        private final String method;

        LoginCall(String method) {
            this.method = method;
        }
    }

    /** scene 链路状态变化（{@code xm.gate.link.events{event}}）。 */
    public enum LinkEvent {
        /** 开始建链（新代次）。 */
        CONNECTING,
        READY,
        /** 建链失败（寻址 / 连接 / 握手）。 */
        CONNECT_FAILED,
        /** 就绪过的链路断开。 */
        DOWN
    }

    /** 服务端推送的种类（{@code xm.gate.pushes{kind}}）。单条消息与按序的一批消息（{@code MessageBatch}）都计 MESSAGE。 */
    public enum PushKind {
        MESSAGE,
        KICK
    }

    /** 服务端推送对每个目标会话的结局（{@code xm.gate.pushes{result}}），每个目标恰好计一次（一批消息也只计一次）。 */
    public enum PushResult {
        /** 已写给客户端（一批消息：全部送出；踢下线：已推 tip 并开始关闭）。 */
        DELIVERED,
        /** 会话号在本 gate 上已不存在。 */
        NO_SESSION,
        /** 会话还在，但已不在游戏里、正在关闭或在游戏里的不是目标玩家（玩家栅栏）。 */
        NOT_BOUND,
        /** 推送指向的 gate 实例不是本进程（节点号被复用前的旧条目）：整条丢弃，按目标数计。 */
        STALE_INSTANCE,
        /** 消息格式不对（解析失败、没有动作、MessageContent 损坏、MessageBatch 为空或有一条损坏）：整条丢弃，按目标数计（无目标计 1）。 */
        INVALID
    }

    /** 没发出去的链路帧（{@code xm.gate.link.dropped{reason}}）。 */
    public enum LinkDrop {
        /** 本 gate 节点号租约无效，不新建链路。 */
        LEASE_INVALID,
        /** 链路未就绪且排队已满。 */
        QUEUE_FULL,
        /** 排队中的帧随建链失败 / 断链被丢弃。 */
        LINK_FAILED,
        /** 链路层已关闭或重试后仍不可用。 */
        UNAVAILABLE
    }

    private final MeterRegistry registry;
    private final Map<HandshakeResult, Counter> handshakes;
    private final Map<DisconnectReason, Counter> disconnects;
    private final Map<ClientFrameException.Reason, Counter> invalidFrames;
    private final Map<LinkEvent, Counter> linkEvents;
    private final Map<LinkDrop, Counter> linkDrops;
    private final Map<PushKind, Map<PushResult, Counter>> pushes;
    private final Map<SceneTransferResult, Counter> sceneTransfers;
    private final Map<RedirectSource, Map<RedirectResult, Counter>> redirects;
    private final Map<NodeLinkFrame.BodyCase, Counter> framesOut;
    private final Map<NodeLinkFrame.BodyCase, Counter> framesIn;
    private final Map<LoginCall, Timer> loginCallsOk;
    private final Map<LoginCall, Timer> loginCallsFailed;
    /** login 以外的后端：键 = backend + "/ok" 或 "/error"（只有 gate 配置的几个后端）。 */
    private final Map<String, Timer> backendTimers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RequestKey, Counter> requests = new ConcurrentHashMap<>();

    public GateMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.handshakes = counters(HandshakeResult.class, HANDSHAKES, "result", "gate 令牌握手结果");
        this.disconnects = counters(DisconnectReason.class, DISCONNECTS, "reason", "gate 主动断开的客户端连接");
        this.invalidFrames = counters(ClientFrameException.Reason.class, INVALID_FRAMES, "reason",
                "解码层非法的客户端帧（随即断开）");
        this.linkEvents = counters(LinkEvent.class, LINK_EVENTS, "event", "gate → scene 链路状态变化");
        this.linkDrops = counters(LinkDrop.class, LINK_DROPPED, "reason", "没发出去的 gate → scene 链路帧");
        this.pushes = new EnumMap<>(PushKind.class);
        for (PushKind kind : PushKind.values()) {
            EnumMap<PushResult, Counter> byResult = new EnumMap<>(PushResult.class);
            for (PushResult result : PushResult.values()) {
                byResult.put(result, Counter.builder(PUSHES)
                        .description("服务端经推送通道发给会话的消息 / 踢下线，按目标会话计结局")
                        .tag("kind", tagValue(kind))
                        .tag("result", tagValue(result))
                        .register(registry));
            }
            pushes.put(kind, byResult);
        }
        this.sceneTransfers = counters(SceneTransferResult.class, SCENE_TRANSFERS, "result",
                "跨节点换图改绑指令（scene 的 PlayerTransfer）在 gate 的结局");
        // 重定向（批次 5.4）：source × result 全量预建（2 × 4 条），平时恒为 0 的组合也在
        this.redirects = new EnumMap<>(RedirectSource.class);
        for (RedirectSource source : RedirectSource.values()) {
            EnumMap<RedirectResult, Counter> byResult = new EnumMap<>(RedirectResult.class);
            for (RedirectResult result : RedirectResult.values()) {
                byResult.put(result, Counter.builder(REDIRECTS)
                        .description("重定向（给客户端推 124 RedirectToGateNotify）在 gate 的结局：travel = 226 跨 zone 传送，login = 登录期重定向")
                        .tag("source", tagValue(source))
                        .tag("result", tagValue(result))
                        .register(registry));
            }
            redirects.put(source, byResult);
        }
        this.framesOut = frameCounters("out");
        this.framesIn = frameCounters("in");
        this.loginCallsOk = loginTimers("ok");
        this.loginCallsFailed = loginTimers("error");
    }

    /** 不导出任何指标的实例（测试 / 不关心指标的装配用）：没有子注册表的 {@link CompositeMeterRegistry} 上计量器都是空操作。 */
    public static GateMetrics noop() {
        return new GateMetrics(new CompositeMeterRegistry());
    }

    // ================================================================ 状态量（启动装配时绑定一次）

    /** 在线会话数（{@code xm.gate.sessions.active}）。{@code sessions} 在抓取线程上调用，必须线程安全、不阻塞。 */
    public void bindSessionCount(IntSupplier sessions) {
        Gauge.builder(SESSIONS_ACTIVE, () -> sessions.getAsInt())
                .description("gate 当前会话数（含未握手、正在收尾的）")
                .register(registry);
    }

    /** 当前 scene 链路数（建链中 + 就绪，{@code xm.gate.scene.links}）。同上，必须线程安全、不阻塞。 */
    public void bindSceneLinkCount(IntSupplier links) {
        Gauge.builder(SCENE_LINKS, () -> links.getAsInt())
                .description("gate 到各 scene 节点的链路数（建链中 + 就绪）")
                .register(registry);
    }

    // ================================================================ 客户端连接

    public void handshake(HandshakeResult result) {
        handshakes.get(result).increment();
    }

    public void disconnected(DisconnectReason reason) {
        disconnects.get(reason).increment();
    }

    public void invalidFrame(ClientFrameException.Reason reason) {
        invalidFrames.get(reason).increment();
    }

    /**
     * 一个客户端请求的最终去向（{@code xm.gate.client.requests{route, method, result}}）。
     *
     * @param route  后端域（{@code login} / {@code scene} / {@code unsupported}）；消息号不认识时传 null
     * @param method 客户端白名单里的方法名（{@code 服务.方法}）；消息号不认识时传 null
     */
    public void request(String route, String method, RequestResult result) {
        requestCounter(route, method, result).increment();
    }

    /**
     * 预建一个请求去向的计数器（值为 0，不计数；重复调用无副作用）。请求计数器缺省是首次出现时才注册，
     * 平时恒为 0、一出现就要告警的去向（{@link RequestResult#BATTLE_REJECTED}）要在装配时先建好，否则第一次增长之前
     * 这条时间序列不存在，{@code rate()} / {@code increase()} 看不到从 0 到 1 的那一跳。
     * {@code route} / {@code method} 必须来自客户端白名单（基数有界），参数含义同 {@link #request}。
     */
    public void registerRequest(String route, String method, RequestResult result) {
        requestCounter(route, method, result);
    }

    private Counter requestCounter(String route, String method, RequestResult result) {
        RequestKey key = new RequestKey(route == null ? UNKNOWN : route, method == null ? UNKNOWN : method, result);
        Counter counter = requests.get(key);
        if (counter == null) {
            counter = requests.computeIfAbsent(key, k -> Counter.builder(CLIENT_REQUESTS)
                    .description("已握手会话上的客户端请求，按最终去向")
                    .tag("route", k.route())
                    .tag("method", k.method())
                    .tag("result", tagValue(k.result()))
                    .register(registry));
        }
        return counter;
    }

    // ================================================================ login（Dubbo）

    /** 开始一次后端调用计时（用注册表的单调时钟）。 */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /** 一次 login 调用结束（{@code xm.gate.backend.calls{backend=login, method, result}}）；ok = 正常应答（含带 tip 的应答）。 */
    public void loginCallCompleted(LoginCall call, Timer.Sample sample, boolean ok) {
        sample.stop((ok ? loginCallsOk : loginCallsFailed).get(call));
    }

    /**
     * 一次 login 以外的客户端消息后端（friend ……）调用结束（{@code xm.gate.backend.calls{backend, method=handle, result}}）。
     * backend 取值只来自 gate 配置的后端集合（{@code MessageRoutes.SERVICE_BACKENDS}），基数有界。
     */
    public void backendCallCompleted(String backend, Timer.Sample sample, boolean ok) {
        sample.stop(backendTimers.computeIfAbsent(backend + (ok ? "/ok" : "/error"), k -> Timer.builder(BACKEND_CALLS)
                .description("gate 对后端的 Dubbo 调用耗时（从发出到应答回到回调）")
                .tag("backend", backend)
                .tag("method", "handle")
                .tag("result", ok ? "ok" : "error")
                .serviceLevelObjectives(LATENCY_BUCKETS)
                .register(registry)));
    }

    // ================================================================ scene 链路

    public void linkFrameOut(NodeLinkFrame.BodyCase type) {
        framesOut.get(type).increment();
    }

    public void linkFrameIn(NodeLinkFrame.BodyCase type) {
        framesIn.get(type).increment();
    }

    public void linkEvent(LinkEvent event) {
        linkEvents.get(event).increment();
    }

    /** 跨节点换图改绑的一个结局（见 {@link SceneTransferResult} 的计数口径）。 */
    public void sceneTransfer(SceneTransferResult result) {
        sceneTransfers.get(result).increment();
    }

    /** 一次重定向的结局（{@code xm.gate.redirects{source, result}}，见 {@link RedirectResult} 的计数口径）。 */
    public void redirect(RedirectSource source, RedirectResult result) {
        redirects.get(source).get(result).increment();
    }

    public void push(PushKind kind, PushResult result, int targets) {
        pushes.get(kind).get(result).increment(targets);
    }

    public void linkDropped(LinkDrop reason, int frames) {
        if (frames > 0) {
            linkDrops.get(reason).increment(frames);
        }
    }

    // ================================================================ 内部

    private <E extends Enum<E>> Map<E, Counter> counters(Class<E> type, String name, String tag, String description) {
        EnumMap<E, Counter> map = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            map.put(value, Counter.builder(name).description(description).tag(tag, tagValue(value)).register(registry));
        }
        return map;
    }

    private Map<NodeLinkFrame.BodyCase, Counter> frameCounters(String direction) {
        EnumMap<NodeLinkFrame.BodyCase, Counter> map = new EnumMap<>(NodeLinkFrame.BodyCase.class);
        for (NodeLinkFrame.BodyCase type : NodeLinkFrame.BodyCase.values()) {
            map.put(type, Counter.builder(LINK_FRAMES)
                    .description("gate ↔ scene 链路帧（out = 已写上链路，in = 从链路收到）")
                    .tag("direction", direction)
                    .tag("type", tagValue(type))
                    .register(registry));
        }
        return map;
    }

    private Map<LoginCall, Timer> loginTimers(String result) {
        EnumMap<LoginCall, Timer> map = new EnumMap<>(LoginCall.class);
        for (LoginCall call : LoginCall.values()) {
            map.put(call, Timer.builder(BACKEND_CALLS)
                    .description("gate 对后端的 Dubbo 调用耗时（从发出到应答回到回调）")
                    .tag("backend", "login")
                    .tag("method", call.method)
                    .tag("result", result)
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(registry));
        }
        return map;
    }

    static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private record RequestKey(String route, String method, RequestResult result) {
    }
}
