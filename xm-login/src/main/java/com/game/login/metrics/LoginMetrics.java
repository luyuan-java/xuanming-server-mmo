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

    static final String REQUESTS = "xm.login.requests";
    static final String OWNER_CLAIMS = "xm.login.owner.claims";
    static final String TAKEOVER_REQUESTS = "xm.login.owner.takeover.requests";
    static final String PLAYERS_CREATED = "xm.login.players.created";
    static final String BACKEND_CALLS = "xm.login.backend.calls";
    static final String ABANDONED_ENTERS = "xm.login.abandoned.enters";

    /** 没有处理器接管的消息号（契约里有但 Java 版未实现、或根本不存在）统一用的方法标签。 */
    public static final String UNROUTED = "unrouted";

    /** 延迟直方图的桶边界：固定 11 个，覆盖 5ms～10s（客户端单步预算 15s）；不开百分位直方图。 */
    static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};

    /** 一个客户端请求在 login 的结果（{@code xm.login.requests{result}}）。 */
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

    /** EnterGame 夺取玩家数据归属这一步的结局（{@code xm.login.owner.claims{outcome}}），每次走到夺权的进游戏恰好计一次。 */
    public enum ClaimOutcome {
        /** 第一次就夺到（上一个写者已释放或租约已过期）。 */
        CLAIMED,
        /** 撞上仍被持有、请持有者让出后在等待窗口内夺到（顶号 / 快速重进）。 */
        WAITED,
        /** 等待窗口内一直被持有，回 2005。 */
        TIMEOUT,
        /** 夺权时角色已不存在，回 2011。 */
        NOT_FOUND,
        /** 夺权故障（数据库异常、退避重试时工作队列满）。 */
        ERROR
    }

    /** login 调 scene-manager 分配场景的结果。 */
    public enum AssignResult {
        OK,
        /** scene-manager 回了 tip（无场景等）。 */
        REJECTED,
        /** 调用失败 / 超时。 */
        ERROR
    }

    /** gate 通知「进场未送达」后 login 代为释放归属的结果（{@code xm.login.abandoned.enters{result}}）。 */
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
    private final Map<AbandonResult, Counter> abandoned;
    private final Counter takeoverRequests;
    private final Counter playersCreated;

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
     * 一个客户端请求处理完（{@code xm.login.requests{method, result}}），耗时从 Dubbo 线程受理起算，含排队。
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

    /** 一次 scene-manager 场景分配调用结束（{@code xm.login.backend.calls{backend=scene-manager, method=assign}}）。 */
    public void sceneAssignCompleted(AssignResult result, long elapsedNanos) {
        sceneAssigns.get(result).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
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
