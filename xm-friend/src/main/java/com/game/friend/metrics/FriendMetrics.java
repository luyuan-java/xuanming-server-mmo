package com.game.friend.metrics;

import com.game.friend.store.JdbcFriendStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * xm-friend 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出）。指标名与标签只在这里定义。
 *
 * <p>对应基线 go/friend internal/metrics：{@code friend_push_total}、{@code friend_rate_quota_total}、
 * {@code friend_online_lookup_total} 的全部标签组合启动时预建为 0——不预建的话「从未发生」的序列不存在，
 * {@code rate(...{outcome="error"}) > 0} 这类告警既不报警也不报错。Java 增项：缓存、失效失败、守卫重试、计数下溢。
 *
 * <p><b>标签基数</b>（AGENTS.md §5）：不以 player_id / zone 作标签；{@code method} 只取 {@code ClientPlayerFriend} 的方法名或
 * {@link #UNROUTED}。线程安全。
 */
public final class FriendMetrics implements JdbcFriendStore.Events {

    static final String REQUESTS = "xm.friend.requests";
    static final String PUSHES = "xm.friend.pushes";
    static final String REQUEST_QUOTA = "xm.friend.request.quota";
    static final String ONLINE_LOOKUPS = "xm.friend.online.lookups";
    static final String CACHE = "xm.friend.cache";
    static final String CACHE_INVALIDATION_FAILURES = "xm.friend.cache.invalidation.failures";
    static final String GUARD_RETRIES = "xm.friend.guard.retries";
    static final String COUNT_UNDERFLOWS = "xm.friend.count.underflows";
    static final String DIRECTORY_QUOTA = "xm.friend.directory.quota";
    static final String SWEEP_PENDING_ROWS = "xm.friend.sweep.pending.rows";
    static final String SWEEP_IDLE_CAPACITY_ROWS = "xm.friend.sweep.idle.capacity.rows";

    public static final String UNROUTED = "unrouted";

    /** 与 gate / login 同一套 SLO 桶（5ms～10s）。 */
    static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};

    /** 一个客户端请求的结果（{@code xm.friend.requests{result}}）。 */
    public enum RequestResult {
        /** 应答体没有 error_message。 */
        OK,
        /** 应答体是非 1003 的 tip（业务拒绝）。 */
        BUSINESS_ERROR,
        /** 应答体是 1003（依赖故障 / 处理器异常）。 */
        INTERNAL_ERROR,
        /** 工作队列满。 */
        OVERLOADED,
        /** 请求体解析失败。 */
        BAD_REQUEST,
        /** 会话没有绑定玩家（player_id = 0）。 */
        UNAUTHENTICATED,
        /** 上行 235（服务端推送的消息号）。 */
        FORBIDDEN,
        /** 不认识的消息号。 */
        UNSUPPORTED
    }

    public enum PushReason { REQUEST_RECEIVED, REQUEST_ACCEPTED }

    public enum PushOutcome { OK, OFFLINE, ERROR }

    public enum QuotaOutcome { ALLOWED, REJECTED, ERROR }

    public enum LookupOutcome { OK, OFFLINE, ERROR }

    public enum CacheKind { LIST, PENDING }

    public enum CacheResult { HIT, MISS, FILL_SKIPPED, FILL_FAILED, ERROR }

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> requests = new ConcurrentHashMap<>();
    private final Map<PushReason, Map<PushOutcome, Counter>> pushes = new EnumMap<>(PushReason.class);
    private final Map<QuotaOutcome, Counter> quota = new EnumMap<>(QuotaOutcome.class);
    private final Map<LookupOutcome, Counter> lookups = new EnumMap<>(LookupOutcome.class);
    private final Map<CacheKind, Map<CacheResult, Counter>> cache = new EnumMap<>(CacheKind.class);
    private final Counter invalidationFailures;
    private final Counter guardMissingRow;
    private final Counter guardEnsureDeadlock;
    private final Counter countUnderflows;
    private final Map<QuotaOutcome, Counter> directoryQuota = new EnumMap<>(QuotaOutcome.class);
    /** 清理积压（受 batch-limit 封顶）：键 = 模式（report_only / delete），启动即建为 0。 */
    private final Map<String, AtomicLong> sweepPending = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> sweepIdle = new ConcurrentHashMap<>();

    public FriendMetrics(MeterRegistry registry) {
        this.registry = registry;
        for (PushReason reason : PushReason.values()) {
            Map<PushOutcome, Counter> byOutcome = new EnumMap<>(PushOutcome.class);
            for (PushOutcome outcome : PushOutcome.values()) {
                byOutcome.put(outcome, Counter.builder(PUSHES)
                        .description("好友事件推送（235）的结局：ok = 已发布到在订阅的 gate，offline = 不在线，error = 依赖故障或没有订阅者")
                        .tag("reason", tagValue(reason)).tag("outcome", tagValue(outcome))
                        .register(registry));
            }
            pushes.put(reason, byOutcome);
        }
        for (QuotaOutcome outcome : QuotaOutcome.values()) {
            quota.put(outcome, Counter.builder(REQUEST_QUOTA)
                    .description("发好友申请的每分钟配额判定；error 是配额 fail-open 的唯一信号，须配告警")
                    .tag("outcome", tagValue(outcome)).register(registry));
        }
        for (LookupOutcome outcome : LookupOutcome.values()) {
            lookups.put(outcome, Counter.builder(ONLINE_LOOKUPS)
                    .description("好友列表的在线状态读，每个去重后的玩家计一次")
                    .tag("outcome", tagValue(outcome)).register(registry));
        }
        for (CacheKind kind : CacheKind.values()) {
            Map<CacheResult, Counter> byResult = new EnumMap<>(CacheResult.class);
            for (CacheResult result : CacheResult.values()) {
                byResult.put(result, Counter.builder(CACHE)
                        .description("好友列表 / 入站申请缓存：hit / miss（回源）/ fill_skipped（代次变了放弃回填）/ fill_failed / error（读失败回 1003）")
                        .tag("cache", tagValue(kind)).tag("result", tagValue(result)).register(registry));
            }
            cache.put(kind, byResult);
        }
        this.invalidationFailures = Counter.builder(CACHE_INVALIDATION_FAILURES)
                .description("写已提交但缓存失效失败的键数（该键最多陈旧一个 cache-ttl）").register(registry);
        this.guardMissingRow = Counter.builder(GUARD_RETRIES)
                .description("容量守卫的重试：missing_row = 守卫缺行（回收竞态），ensure_deadlock = 补容量行遇 1213；正常运行时应恒为 0")
                .tag("kind", "missing_row").register(registry);
        this.guardEnsureDeadlock = Counter.builder(GUARD_RETRIES)
                .description("容量守卫的重试：missing_row = 守卫缺行（回收竞态），ensure_deadlock = 补容量行遇 1213；正常运行时应恒为 0")
                .tag("kind", "ensure_deadlock").register(registry);
        this.countUnderflows = Counter.builder(COUNT_UNDERFLOWS)
                .description("friend_count 减到 0 以下被拦截的次数（计数与边数已脱节，需要人工排查）").register(registry);
        for (QuotaOutcome outcome : QuotaOutcome.values()) {
            directoryQuota.put(outcome, Counter.builder(DIRECTORY_QUOTA)
                    .description("在线目录翻页的每分钟配额判定（故障时拒绝：fail-closed）")
                    .tag("outcome", tagValue(outcome)).register(registry));
        }
        for (String mode : List.of("report_only", "delete")) {
            AtomicLong pending = new AtomicLong();
            AtomicLong idle = new AtomicLong();
            sweepPending.put(mode, pending);
            sweepIdle.put(mode, idle);
            Gauge.builder(SWEEP_PENDING_ROWS, pending, AtomicLong::get)
                    .description("清理看到的已过保留期的终态好友申请（受 batch-limit 封顶：等于它只说明积压 ≥ 一批；长期不变说明清理没在跑）")
                    .tag("mode", mode).register(registry);
            Gauge.builder(SWEEP_IDLE_CAPACITY_ROWS, idle, AtomicLong::get)
                    .description("清理看到的已过保留期的零好友容量行（受 batch-limit 封顶）")
                    .tag("mode", mode).register(registry);
        }
    }

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void requestCompleted(Timer.Sample sample, String method, RequestResult result) {
        String key = method + '\0' + result.name();
        Timer timer = requests.get(key);
        if (timer == null) {
            timer = requests.computeIfAbsent(key, k -> Timer.builder(REQUESTS)
                    .description("friend 处理客户端请求的耗时与结果（从受理到应答，含工作队列排队）")
                    .tag("method", method).tag("result", tagValue(result))
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(registry));
        }
        sample.stop(timer);
    }

    public void push(PushReason reason, PushOutcome outcome) {
        pushes.get(reason).get(outcome).increment();
    }

    public void directoryQuota(QuotaOutcome outcome) {
        directoryQuota.get(outcome).increment();
    }

    /** 清理的两个 Gauge（模式只取 report_only / delete，别的不刷）。 */
    public void sweepPendingRows(String mode, long value) {
        AtomicLong gauge = sweepPending.get(mode);
        if (gauge != null) {
            gauge.set(value);
        }
    }

    public void sweepIdleCapacityRows(String mode, long value) {
        AtomicLong gauge = sweepIdle.get(mode);
        if (gauge != null) {
            gauge.set(value);
        }
    }

    public void quota(QuotaOutcome outcome) {
        quota.get(outcome).increment();
    }

    public void onlineLookups(int ok, int offline, int error) {
        lookups.get(LookupOutcome.OK).increment(ok);
        lookups.get(LookupOutcome.OFFLINE).increment(offline);
        lookups.get(LookupOutcome.ERROR).increment(error);
    }

    public void cache(CacheKind kind, CacheResult result) {
        cache.get(kind).get(result).increment();
    }

    public void invalidationFailed() {
        invalidationFailures.increment();
    }

    @Override
    public void guardRetry(String kind) {
        ("ensure_deadlock".equals(kind) ? guardEnsureDeadlock : guardMissingRow).increment();
    }

    @Override
    public void countUnderflow() {
        countUnderflows.increment();
    }

    static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
