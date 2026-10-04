package com.game.guild.metrics;

import com.game.guild.cache.GuildCacheMetrics;
import com.game.guild.cache.InvalidationOp;
import com.game.guild.dispatch.GuildMethods;
import com.game.guild.presence.OnlineStatuses;
import com.game.guild.presence.PlayerNames;
import com.game.guild.push.GuildPushes;
import com.game.guild.push.GuildPushes.PushOutcome;
import com.game.guild.rank.GuildRankMetrics;
import com.game.guild.store.GuildTxListener;
import com.game.guild.store.GuildTxOp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * xm-guild 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出；guild-spec §8.2）。指标名与标签只在这里定义。
 *
 * <ul>
 *   <li>{@code xm_guild_requests_seconds{method, result}} ← serverbase 的 {@code rpc_duration_seconds} 与 {@code rpc_inband_*}：
 *       {@code fault} 单独一类（in-band 14008，基线 Tip 表 fault 列），不并进 {@code internal_error}（信封 1003）；</li>
 *   <li>{@code xm_guild_pushes_total{kind, outcome}} ← {@code guild_push_total}（按收件人计）；</li>
 *   <li>{@code xm_guild_tx_deadlocks_total} / {@code xm_guild_tx_budget_exceeded_total} / {@code xm_guild_tx_lock_wait_timeouts_total}
 *       {@code {op}} ← {@code guild_tx_*_total}；</li>
 *   <li>{@code xm_guild_cache_invalidation_failures_total{op}} ← {@code guild_cache_invalidate_failed_total}；</li>
 *   <li>{@code xm_guild_profile_lookup_failures_total} ← {@code guild_player_name_lookup_failed_total}（按批计）；</li>
 *   <li>Java 增项：{@code xm_guild_cache_total{cache, result}}、{@code xm_guild_online_lookups_total{outcome}}、
 *       {@code xm_guild_rank_ops_total{op, outcome}}；工作线程池 {@code executor_*{name="guild-worker"}} 由池自己绑定。</li>
 * </ul>
 *
 * <p>全部可能出现的标签组合启动时预建为 0（不预建的话「从未发生」的序列不存在，{@code rate(...) > 0} 的告警既不报警也不报错）。
 * <b>标签基数</b>（AGENTS.md §5）：不以 player_id / guild_id / zone 作标签；{@code method} 只取 {@link GuildMethods} 的 28 个方法名或
 * {@link #UNROUTED}；{@code op} 只取 {@link GuildTxOp} / {@link InvalidationOp} 的固定集合。线程安全、不抛异常。
 */
public final class GuildMetrics implements GuildTxListener, GuildCacheMetrics, GuildRankMetrics, GuildPushes.PushMetrics,
        OnlineStatuses.Metrics, PlayerNames.Metrics {

    static final String REQUESTS = "xm.guild.requests";
    static final String PUSHES = "xm.guild.pushes";
    static final String TX_DEADLOCKS = "xm.guild.tx.deadlocks";
    static final String TX_BUDGET_EXCEEDED = "xm.guild.tx.budget.exceeded";
    static final String TX_LOCK_WAIT_TIMEOUTS = "xm.guild.tx.lock.wait.timeouts";
    static final String CACHE_INVALIDATION_FAILURES = "xm.guild.cache.invalidation.failures";
    static final String CACHE = "xm.guild.cache";
    static final String PROFILE_LOOKUP_FAILURES = "xm.guild.profile.lookup.failures";
    static final String ONLINE_LOOKUPS = "xm.guild.online.lookups";
    static final String RANK_OPS = "xm.guild.rank.ops";

    /** 不认识的消息号（契约里没有，或有但不归 guild）。 */
    public static final String UNROUTED = "unrouted";

    /** 与 gate / login / friend / team 同一套 SLO 桶（5ms～10s）。 */
    static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};

    /** 一个客户端请求的结果（{@code xm.guild.requests{result}}，guild-spec §7.3 / §8.2）。 */
    public enum RequestResult {
        /** 应答体没有 error_message（成功）。 */
        OK,
        /** 应答体是业务 tip（in-band 拒绝，含 14021 写冲突）。 */
        BUSINESS_ERROR,
        /** 应答体是 in-band 故障码 14008（发号器不可用；基线 serverbase 计 {@code rpc_inband_fault_total}）。 */
        FAULT,
        /** 信封 1003：依赖故障、双存储矛盾、配表缺行、处理器异常。 */
        INTERNAL_ERROR,
        /** 工作队列满或请求在队列里等过了预算（in-band 14021「guild service overloaded」）。 */
        OVERLOADED,
        /** 请求体解析失败（信封 1003）。 */
        BAD_REQUEST,
        /** 会话没有绑定玩家（player_id = 0，信封 1003）。 */
        UNAUTHENTICATED,
        /** 上行 8 UpdateGuildScore / 220 NotifyGuildChanged（基线会话白名单拒绝，信封 1003）。 */
        FORBIDDEN,
        /** 4.5 / 4.6 占位号（in-band 1006，D13），或不认识的号（信封 1013 / 1006）。 */
        UNSUPPORTED
    }

    private final MeterRegistry registry;
    private final ConcurrentHashMap<String, Timer> requests = new ConcurrentHashMap<>();
    private final Map<String, Map<PushOutcome, Counter>> pushes = new HashMap<>();
    private final Map<GuildTxOp, Counter> txDeadlocks = new EnumMap<>(GuildTxOp.class);
    private final Map<GuildTxOp, Counter> txBudgetExceeded = new EnumMap<>(GuildTxOp.class);
    private final Map<GuildTxOp, Counter> txLockWaitTimeouts = new EnumMap<>(GuildTxOp.class);
    private final Map<InvalidationOp, Counter> invalidationFailures = new EnumMap<>(InvalidationOp.class);
    private final Map<CacheKind, Map<CacheResult, Counter>> cache = new EnumMap<>(CacheKind.class);
    private final Counter profileLookupFailures;
    private final Map<OnlineStatuses.Outcome, Counter> onlineLookups = new EnumMap<>(OnlineStatuses.Outcome.class);
    private final Map<RankOp, Map<RankOutcome, Counter>> rankOps = new EnumMap<>(RankOp.class);

    public GuildMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 请求：只预建可能出现的组合（16 个 C2S 走工作线程池；8 / 220 只会被拒；10 个占位只回 1006）
        for (String method : GuildMethods.CLIENT_REQUESTS) {
            for (RequestResult result : List.of(RequestResult.OK, RequestResult.BUSINESS_ERROR, RequestResult.INTERNAL_ERROR,
                    RequestResult.OVERLOADED, RequestResult.BAD_REQUEST, RequestResult.UNAUTHENTICATED)) {
                timer(method, result);
            }
        }
        timer(GuildMethods.CREATE_GUILD, RequestResult.FAULT);
        for (String method : GuildMethods.FORBIDDEN) {
            for (RequestResult result : List.of(RequestResult.FORBIDDEN, RequestResult.BAD_REQUEST,
                    RequestResult.UNAUTHENTICATED)) {
                timer(method, result);
            }
        }
        for (String method : GuildMethods.PLACEHOLDERS) {
            for (RequestResult result : List.of(RequestResult.UNSUPPORTED, RequestResult.BAD_REQUEST,
                    RequestResult.UNAUTHENTICATED)) {
                timer(method, result);
            }
        }
        timer(UNROUTED, RequestResult.UNSUPPORTED);

        for (String kind : GuildPushes.KIND_LABELS) {
            Map<PushOutcome, Counter> byOutcome = new EnumMap<>(PushOutcome.class);
            for (PushOutcome outcome : PushOutcome.values()) {
                byOutcome.put(outcome, Counter.builder(PUSHES)
                        .description("帮会变更推送 220 的结局，按收件人计：ok = 已发布到在订阅的 gate，offline = 不在线，"
                                + "error = gate 不在订阅 / 超出推送上限，session_error = 在线目录读失败整批不推")
                        .tag("kind", kind).tag("outcome", outcome.label()).register(registry));
            }
            pushes.put(kind, byOutcome);
        }
        for (GuildTxOp op : GuildTxOp.values()) {
            txDeadlocks.put(op, Counter.builder(TX_DEADLOCKS)
                    .description("帮会写事务判定可整体重跑（1213 / 9007）的次数，含用尽重试的最后一次")
                    .tag("op", op.label()).register(registry));
            txBudgetExceeded.put(op, Counter.builder(TX_BUDGET_EXCEEDED)
                    .description("帮会写事务跑满子预算（1500 ms，解散 2500 ms）而请求还活着，回 14021")
                    .tag("op", op.label()).register(registry));
            txLockWaitTimeouts.put(op, Counter.builder(TX_LOCK_WAIT_TIMEOUTS)
                    .description("帮会写事务锁等待超时（1205，innodb_lock_wait_timeout=1），回 14021、不重试")
                    .tag("op", op.label()).register(registry));
        }
        for (InvalidationOp op : InvalidationOp.values()) {
            invalidationFailures.put(op, Counter.builder(CACHE_INVALIDATION_FAILURES)
                    .description("提交后缓存失效在同步尝试与后台有界重试之后仍失败（每次放弃计一次；相关键最多陈旧一个 cache-ttl）")
                    .tag("op", op.label()).register(registry));
        }
        for (CacheKind kind : CacheKind.values()) {
            Map<CacheResult, Counter> byResult = new EnumMap<>(CacheResult.class);
            for (CacheResult result : CacheResult.values()) {
                byResult.put(result, Counter.builder(CACHE)
                        .description("帮会快照 / 玩家映射缓存：hit / miss（回源）/ fill_skipped（代次变了放弃回填）/ fill_failed / "
                                + "error（读失败或值坏，请求回信封 1003）")
                        .tag("cache", kind.label()).tag("result", result.label()).register(registry));
            }
            cache.put(kind, byResult);
        }
        this.profileLookupFailures = Counter.builder(PROFILE_LOOKUP_FAILURES)
                .description("批量取展示名失败的批次数（名字留空，不影响 RPC 结果；请求预算先用完的不计）").register(registry);
        for (OnlineStatuses.Outcome outcome : OnlineStatuses.Outcome.values()) {
            onlineLookups.put(outcome, Counter.builder(ONLINE_LOOKUPS)
                    .description("成员 / 申请人在线状态批量读（每次计一次）：timeout / error 时全体按离线显示")
                    .tag("outcome", outcome.label()).register(registry));
        }
        for (RankOp op : RankOp.values()) {
            Map<RankOutcome, Counter> byOutcome = new EnumMap<>(RankOutcome.class);
            for (RankOutcome outcome : RankOutcome.values()) {
                byOutcome.put(outcome, Counter.builder(RANK_OPS)
                        .description("排行维护：add = 建帮入榜，remove = 解散清榜，rebuild = 启动重建；lock_timeout / error 只记日志，"
                                + "缺口由下次启动重建自愈")
                        .tag("op", op.label()).tag("outcome", outcome.label()).register(registry));
            }
            rankOps.put(op, byOutcome);
        }
    }

    // ------------------------------------------------------------------ 请求

    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }

    public void requestCompleted(Timer.Sample sample, String method, RequestResult result) {
        sample.stop(timer(method, result));
    }

    private Timer timer(String method, RequestResult result) {
        String key = method + '\0' + result.name();
        Timer timer = requests.get(key);
        if (timer == null) {
            timer = requests.computeIfAbsent(key, k -> Timer.builder(REQUESTS)
                    .description("guild 处理客户端请求的耗时与结果（从受理到应答，含工作队列排队）")
                    .tag("method", method).tag("result", tagValue(result))
                    .serviceLevelObjectives(LATENCY_BUCKETS)
                    .register(registry));
        }
        return timer;
    }

    // ------------------------------------------------------------------ 推送 / 在线 / 名字

    @Override
    public void pushed(String kindLabel, PushOutcome outcome, int recipients) {
        Map<PushOutcome, Counter> byOutcome = pushes.get(kindLabel);
        if (byOutcome == null) {
            byOutcome = pushes.get(GuildPushes.OTHER_KIND);
        }
        byOutcome.get(outcome).increment(recipients);
    }

    @Override
    public void onlineLookup(OnlineStatuses.Outcome outcome) {
        onlineLookups.get(outcome).increment();
    }

    @Override
    public void lookupFailed() {
        profileLookupFailures.increment();
    }

    // ------------------------------------------------------------------ 事务基座

    @Override
    public void deadlockObserved(GuildTxOp op) {
        txDeadlocks.get(op).increment();
    }

    @Override
    public void lockWaitTimeout(GuildTxOp op) {
        txLockWaitTimeouts.get(op).increment();
    }

    @Override
    public void budgetExceeded(GuildTxOp op) {
        txBudgetExceeded.get(op).increment();
    }

    // ------------------------------------------------------------------ 缓存 / 排行

    @Override
    public void cache(CacheKind kind, CacheResult result) {
        cache.get(kind).get(result).increment();
    }

    @Override
    public void invalidationGaveUp(InvalidationOp op) {
        invalidationFailures.get(op).increment();
    }

    @Override
    public void rankOp(RankOp op, RankOutcome outcome) {
        rankOps.get(op).get(outcome).increment();
    }

    static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
