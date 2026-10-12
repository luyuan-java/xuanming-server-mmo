package com.game.login.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * login 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出，见 architecture.md §11）。
 * 指标名与标签只在这里定义，业务代码只调语义方法。
 *
 * <p><b>标签基数约束</b>（AGENTS.md §5）：不以 player_id / session_id / 账号作标签。{@code method} 只取 login 处理器的方法名
 * （契约里 {@code ClientPlayerLogin} 的方法，现 5 个）或 {@link #UNROUTED}；其余标签都是本类里的枚举。
 *
 * <p>线程安全：任意线程可调（Dubbo 线程、login 工作线程、异步回调线程）。
 */
public final class LoginMetrics {

    static final String REQUESTS = "game.login.requests";
    static final String OWNER_CLAIMS = "game.login.owner.claims";
    static final String TAKEOVER_REQUESTS = "game.login.owner.takeover.requests";
    static final String PLAYERS_CREATED = "game.login.players.created";
    static final String BACKEND_CALLS = "game.login.backend.calls";
    static final String ABANDONED_ENTERS = "game.login.abandoned.enters";
    static final String ENTER_ROUTES = "game.login.enter.routes";
    static final String TICKET_HOLDER_REJECTED = "game.login.ticket.holder.rejected";

    /** 没有处理器接管的消息号（契约里有但 Java 版未实现、或根本不存在）统一用的方法标签。 */
    public static final String UNROUTED = "unrouted";

    /** 延迟直方图的桶边界：固定 11 个，覆盖 5ms～10s（客户端单步预算 15s）；不开百分位直方图。 */
    static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};

    /** 一个客户端请求在 login 的结果（{@code game.login.requests{result}}）。 */
    public enum RequestResult {
        /** 处理器正常应答，应答体里没有 {@code error_message}（含空应答类型的方法）。 */
        OK,
        /** 业务拒绝：应答体带 {@code error_message}。 */
        BUSINESS_ERROR,
        /** 处理器故障（异常 / future 异常完成），转成 1003。 */
        INTERNAL_ERROR,
        /** 工作队列满，快速回 1003。 */
        OVERLOADED,
        /** 请求体解析失败（传输层 tip）。 */
        BAD_REQUEST,
        /** 没有处理器接管的消息号（传输层 tip）。 */
        UNSUPPORTED
    }

    /** EnterGame 夺取玩家数据归属这一步的结局（{@code game.login.owner.claims{outcome}}），每次走到夺权的进游戏恰好计一次。 */
    public enum ClaimOutcome {
        /** 第一次就夺到（上一个写者已释放或租约已过期）。 */
        CLAIMED,
        /** 撞上仍被持有、请持有者让出后在等待窗口内夺到（顶号 / 快速重进）。 */
        WAITED,
        /** 等待窗口内一直被持有，或分配回来时进游戏的时间预算已用完（没有发起夺权），回 2005。 */
        TIMEOUT,
        /** 夺权时角色已不存在，回 2011。 */
        NOT_FOUND,
        /** 夺权故障（数据库异常、退避重试时工作队列满）。 */
        ERROR
    }

    /** login 调 scene-manager 的结果（分配场景 {@code assign}、登录期重定向签票 {@code redirectToZone}）。 */
    public enum AssignResult {
        OK,
        /** scene-manager 回了 tip（无场景、目标 zone 没有可用的 gate / scene 节点等）。 */
        REJECTED,
        /**
         * 调用失败 / 超时（含进游戏的时间预算已用完、没有发起调用）；登录期重定向的应答残缺（tip 为 0 却缺票据或地址）也算这一类。
         */
        ERROR
    }

    /**
     * EnterGame 的去向（{@code game.login.enter.routes{route}}，批次 5.4，zone-travel-spec §5.8 的去向表）：
     * 每次走到「选去向」的进游戏（过了 2018 / 持票者 / 2028 / 设备续期 / 2005 / 角色归属这些前置校验）计一次；
     * 登录期重定向（GO-5）等签票有了结局才计 {@link #REDIRECT} 或 {@link #REDIRECT_FALLBACK} 之一（签票回来时工作队列已满、
     * 链以异常结束的那一次不计，它在 {@code game.login.requests{result=internal_error}} 里）。只描述去向，不描述随后分配 / 夺权的成败。
     */
    public enum EnterRoute {
        /** 本 zone 首登（默认主世界）：没有位置记录 / 登出墓碑 / 别的 zone 的待落点（不牵引）。 */
        FIRST,
        /** 回原实例：本 zone 的在线或断线重连租约记录（顶号、短线重连）。 */
        RESUME,
        /** 本 zone 的待落点（跨 zone 传送的第二条腿）：按记录里的地图落，不带实例。 */
        AWAITING,
        /** 这条连接持票钉在本 zone，位置记录却在别的 zone 的节点上：票据优先、不再弹走，本 zone 首登。 */
        PINNED,
        /** 登录期重定向（GO-5）成功：回 EnterGame 成功应答 + RedirectToGate 指令，不夺权、不分配。 */
        REDIRECT,
        /** 登录期重定向没成（调用失败 / 超时 / 拒绝 / 应答残缺），退化为本 zone 首登（X11）。 */
        REDIRECT_FALLBACK,
        /** 读位置记录失败，按存档里上次所在的地图选。 */
        LOCATION_ERROR
    }

    /** gate 通知「进场未送达」后 login 代为释放归属的结果（{@code game.login.abandoned.enters{result}}）。 */
    public enum AbandonResult {
        RELEASED,
        /** epoch 围栏没过：已被新的夺权取代或已释放，什么也没做。 */
        STALE,
        /** 释放时数据库故障（等租约过期兜底）。 */
        FAILED,
        /** 工作队列满（等租约过期兜底）。 */
        OVERLOADED,
        /** 通知里玩家号或 epoch 为 0，忽略。 */
        INVALID
    }

    private final MeterRegistry registry;
    private final ConcurrentHashMap<RequestKey, Timer> requests = new ConcurrentHashMap<>();
    private final Map<ClaimOutcome, Timer> claims;
    private final Map<AssignResult, Timer> sceneAssigns;
    private final Map<AssignResult, Timer> sceneRedirects;
    private final Map<AbandonResult, Counter> abandoned;
    private final Map<EnterRoute, Counter> enterRoutes;
    private final Counter takeoverRequests;
    private final Counter playersCreated;
    private final Counter ticketHolderRejected;

    public LoginMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.claims = new EnumMap<>(ClaimOutcome.class);
        for (ClaimOutcome outcome : ClaimOutcome.values()) {
            claims.put(outcome, Timer.builder(OWNER_CLAIMS)
                    .description("EnterGame 夺取玩家数据归属的结局与耗时（含等待持有者让出）")
                    .tag("outcome", tagValue(outcome))
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(registry));
        }
        this.sceneAssigns = new EnumMap<>(AssignResult.class);
        for (AssignResult result : AssignResult.values()) {
            sceneAssigns.put(result, Timer.builder(BACKEND_CALLS)
                    .description("login 对后端的 Dubbo 调用耗时")
                    .tag("backend", "scene-manager")
                    .tag("method", "assign")
                    .tag("result", tagValue(result))
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(registry));
        }
        this.sceneRedirects = new EnumMap<>(AssignResult.class);
        for (AssignResult result : AssignResult.values()) {
            sceneRedirects.put(result, Timer.builder(BACKEND_CALLS)
                    .description("login 对后端的 Dubbo 调用耗时")
                    .tag("backend", "scene-manager")
                    .tag("method", "redirectToZone")
                    .tag("result", tagValue(result))
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(registry));
        }
        this.enterRoutes = new EnumMap<>(EnterRoute.class);
        for (EnterRoute route : EnterRoute.values()) {
            enterRoutes.put(route, Counter.builder(ENTER_ROUTES)
                    .description("EnterGame 的去向（首登 / 回原实例 / 待落点 / 票据钉住 / 登录期重定向及其回落 / 读位置失败）")
                    .tag("route", tagValue(route))
                    .register(registry));
        }
        this.ticketHolderRejected = Counter.builder(TICKET_HOLDER_REJECTED)
                .description("EnterGame 因持票者不符回 2011 的次数（连接握手时出示的重定向票据属于别的角色）")
                .register(registry);
        this.abandoned = new EnumMap<>(AbandonResult.class);
        for (AbandonResult result : AbandonResult.values()) {
            abandoned.put(result, Counter.builder(ABANDONED_ENTERS)
                    .description("gate 通知进场未送达后 login 代为释放归属的结果")
                    .tag("result", tagValue(result))
                    .register(registry));
        }
        this.takeoverRequests = Counter.builder(TAKEOVER_REQUESTS)
                .description("夺权撞上仍被持有的归属、请持有者让出的次数（每次重试都会再请一次）")
                .register(registry);
        this.playersCreated = Counter.builder(PLAYERS_CREATED)
                .description("新建成功的角色数（上次应答丢失的重试命中已建角色不计）")
                .register(registry);
    }

    // ================================================================ 客户端请求

    /** 开始一次请求计时（注册表的单调时钟）。 */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    /**
     * 一个客户端请求处理完（{@code game.login.requests{method, result}}），耗时从 Dubbo 线程受理起算，含排队。
     *
     * @param method 处理器方法名（{@code Login} / {@code CreatePlayer} / ...）或 {@link #UNROUTED}
     */
    public void requestCompleted(Timer.Sample sample, String method, RequestResult result) {
        RequestKey key = new RequestKey(method, result);
        Timer timer = requests.get(key);
        if (timer == null) {
            timer = requests.computeIfAbsent(key, k -> Timer.builder(REQUESTS)
                    .description("login 处理客户端请求的耗时与结果（从受理到应答，含工作队列排队）")
                    .tag("method", k.method())
                    .tag("result", tagValue(k.result()))
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(registry));
        }
        sample.stop(timer);
    }

    // ================================================================ 进游戏 / 建角 / 归属

    /** 夺权这一步结束；{@code elapsedNanos} 从第一次夺权尝试起算（调用方的单调时钟）。 */
    public void ownerClaimCompleted(ClaimOutcome outcome, long elapsedNanos) {
        claims.get(outcome).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    public void takeoverRequested() {
        takeoverRequests.increment();
    }

    /** 一次 scene-manager 场景分配调用结束（{@code game.login.backend.calls{backend=scene-manager, method=assign}}）。 */
    public void sceneAssignCompleted(AssignResult result, long elapsedNanos) {
        sceneAssigns.get(result).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    /**
     * 一次登录期重定向签票调用结束（{@code game.login.backend.calls{backend=scene-manager, method=redirectToZone}}）。
     */
    public void sceneRedirectCompleted(AssignResult result, long elapsedNanos) {
        sceneRedirects.get(result).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    /** 一次进游戏选定了去向（{@code game.login.enter.routes{route}}）。 */
    public void enterRouted(EnterRoute route) {
        enterRoutes.get(route).increment();
    }

    /** 持票者不符，回 2011（{@code game.login.ticket.holder.rejected}）。 */
    public void ticketHolderRejected() {
        ticketHolderRejected.increment();
    }

    public void playerCreated() {
        playersCreated.increment();
    }

    public void abandonedEnter(AbandonResult result) {
        abandoned.get(result).increment();
    }

    static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    private record RequestKey(String method, RequestResult result) {
    }
}
