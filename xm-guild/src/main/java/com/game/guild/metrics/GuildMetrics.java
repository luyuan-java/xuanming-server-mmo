package com.game.guild.metrics;

import com.game.api.asset.AssetRpc;
import com.game.discovery.location.SceneAssetLocator.ResolveResult;
import com.game.guild.asset.AssetOpAction;
import com.game.guild.asset.AssetOpMetrics;
import com.game.guild.asset.AssetOpStatus;
import com.game.guild.asset.GuildAssetStore.CleanupTable;
import com.game.guild.asset.GuildAssetStore.Orphan;
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
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * xm-guild 的低基数指标（Micrometer，经 actuator 以 Prometheus 格式导出；guild-spec §8.2、guild-economy-spec §8.2）。指标名与标签只在这里定义。
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
 * <p>4.5 帮会经济与资产通道（guild-economy-spec §8.2）：
 * <ul>
 *   <li>{@code xm_guild_economy_requests_total{rpc, result}} ← {@code guild_economy_requests_total}（result 集合同基线，去掉 Java 不存在的
 *       denied / unavailable）；{@code xm_guild_asset_sync_skipped_total{kind}}；{@code xm_guild_asset_orphans_total{kind, what}}；
 *       {@code xm_guild_asset_cleanup_deleted_total{table}}；</li>
 *   <li>{@code xm_guild_internal_list_applied_total{result}} 与 {@code xm_guild_internal_list_applied_rows}（桶 1, 5, 20, 100, 500；基线另有 0）；</li>
 *   <li>{@code xm_guild_assetop_*} ← 基线 {@code assetop_*} 全集（{@link AssetOpMetrics}）：{@code rpc_total{stream, rpc, outcome}}、
 *       {@code rpc_duration_seconds{rpc}}（基线 {@code rpc_seconds}：Micrometer 不允许计数器与计时器同名，计时器多一个 duration 段）、
 *       {@code requery_total}、{@code finalize_total}、{@code reschedule_total}、{@code reschedule_lost_total}、{@code unknown_total}、
 *       {@code outcome_flip_total}、{@code partial_total}、{@code claim_total}、{@code ledger_read_total}、{@code manual_resolve_total}、
 *       {@code store_errors_total}、{@code pending_oldest_age_seconds}；</li>
 *   <li>{@code xm_guild_scene_resolve_total{result}} ← {@code scenenode_resolve_total}；落库与循环 worker 池
 *       {@code executor_*{name="guild-asset-settle" | "guild-asset-worker"}} 由池自己绑定。</li>
 * </ul>
 *
 * <p>全部可能出现的标签组合启动时预建为 0（不预建的话「从未发生」的序列不存在，{@code rate(...) > 0} 的告警既不报警也不报错）。
 * <b>标签基数</b>（AGENTS.md §5）：不以 player_id / guild_id / op_id / seq / zone 作标签；{@code method} 只取 {@link GuildMethods} 的 28 个方法名或
 * {@link #UNROUTED}；{@code op} 只取 {@link GuildTxOp} / {@link InvalidationOp} 的固定集合；资产通道的流号只分三档。线程安全、不抛异常。
 */
public final class GuildMetrics implements GuildTxListener, GuildCacheMetrics, GuildRankMetrics, GuildPushes.PushMetrics,
        OnlineStatuses.Metrics, PlayerNames.Metrics, AssetOpMetrics {

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
    // ---- 4.5 ----
    static final String ECONOMY_REQUESTS = "xm.guild.economy.requests";
    static final String ASSET_SYNC_SKIPPED = "xm.guild.asset.sync.skipped";
    static final String ASSET_ORPHANS = "xm.guild.asset.orphans";
    static final String ASSET_CLEANUP_DELETED = "xm.guild.asset.cleanup.deleted";
    static final String INTERNAL_LIST_APPLIED = "xm.guild.internal.list.applied";
    static final String INTERNAL_LIST_APPLIED_ROWS = "xm.guild.internal.list.applied.rows";
    static final String ASSETOP_RPC = "xm.guild.assetop.rpc";
    static final String ASSETOP_RPC_DURATION = "xm.guild.assetop.rpc.duration";
    static final String ASSETOP_REQUERY = "xm.guild.assetop.requery";
    static final String ASSETOP_FINALIZE = "xm.guild.assetop.finalize";
    static final String ASSETOP_RESCHEDULE = "xm.guild.assetop.reschedule";
    static final String ASSETOP_RESCHEDULE_LOST = "xm.guild.assetop.reschedule.lost";
    static final String ASSETOP_UNKNOWN = "xm.guild.assetop.unknown";
    static final String ASSETOP_OUTCOME_FLIP = "xm.guild.assetop.outcome.flip";
    static final String ASSETOP_PARTIAL = "xm.guild.assetop.partial";
    static final String ASSETOP_CLAIM = "xm.guild.assetop.claim";
    static final String ASSETOP_LEDGER_READ = "xm.guild.assetop.ledger.read";
    static final String ASSETOP_MANUAL_RESOLVE = "xm.guild.assetop.manual.resolve";
    static final String ASSETOP_STORE_ERRORS = "xm.guild.assetop.store.errors";
    static final String ASSETOP_PENDING_OLDEST_AGE = "xm.guild.assetop.pending.oldest.age.seconds";
    static final String SCENE_RESOLVE = "xm.guild.scene.resolve";

    /** 不认识的消息号（契约里没有，或有但不归 guild）。 */
    public static final String UNROUTED = "unrouted";

    /** 与 gate / login / friend / team 同一套 SLO 桶（5ms～10s）。 */
    static final Duration[] LATENCY_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500), Duration.ofSeconds(5), Duration.ofSeconds(10)};

    /** 单次资产 RPC 的耗时桶（基线 assetop_rpc_seconds：.005–2.5 s，metrics.go:51-56）。 */
    static final Duration[] ASSET_RPC_BUCKETS = {
            Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
            Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1),
            Duration.ofMillis(2500)};

    /**
     * 内部查询每次返回行数的桶（基线 0, 1, 5, 20, 100, 500，guild_internal_server.go:93-98）：Micrometer 的 SLO 边界必须 &gt; 0，去掉 0——
     * 0 行落在 {@code le="1"} 里，空结果另有 {@code result="ok_empty"} 计数。
     */
    static final double[] LIST_APPLIED_ROW_BUCKETS = {1, 5, 20, 100, 500};

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
        /** 4.6 活动占位号（in-band 1006，D13），或不认识的号（信封 1013 / 1006）。 */
        UNSUPPORTED
    }

    /** 经济 RPC（{@code xm_guild_economy_requests_total{rpc}}，economy_logic.go:85-91）。 */
    public enum EconomyRpc {
        GET_DONATE_OPTIONS, DONATE, UPGRADE, GET_SHOP, BUY_SHOP_GOODS;

        public String label() {
            return tagValue(this);
        }
    }

    /**
     * 经济 RPC 的结果（{@code xm_guild_economy_requests_total{result}}，economy_logic.go:93-118）。写 RPC 成功时按视图状态计（applied / pending / …），
     * 读 RPC 成功计 ok，升级 expected_level 不符计 unchanged；故障计 error（基线的 denied / unavailable 在 Java 不存在）。
     */
    public enum EconomyResult {
        OK, UNCHANGED, APPLIED, PENDING, REJECTED, ABORTED, APPLIED_PARTIAL, LIMIT, INSUFFICIENT, PENDING_GUARD, BUSY_RETRY,
        FENCE, NOT_MEMBER, LEVEL, RANK, NOT_FOUND, DISABLED, ID_UNAVAILABLE, OTHER_REJECT, ERROR;

        public String label() {
            return tagValue(this);
        }
    }

    /** 同步投递被跳过的指令种类（{@code xm_guild_asset_sync_skipped_total{kind}}）。 */
    public enum AssetKind {
        DONATE, SHOP;

        public String label() {
            return tagValue(this);
        }
    }

    /** 内部查询的结果（{@code xm_guild_internal_list_applied_total{result}}，guild_internal_server.go:67-82）。 */
    public enum ListAppliedResult {
        OK_EMPTY, OK_ROWS, INVALID, RETENTION, UNAVAILABLE, ERROR;

        public String label() {
            return tagValue(this);
        }
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
    // ---- 4.5 ----
    private final Map<EconomyRpc, Map<EconomyResult, Counter>> economyRequests = new EnumMap<>(EconomyRpc.class);
    private final Map<AssetKind, Counter> syncSkipped = new EnumMap<>(AssetKind.class);
    private final Map<Orphan, Counter> orphans = new EnumMap<>(Orphan.class);
    private final Map<CleanupTable, Counter> cleanupDeleted = new EnumMap<>(CleanupTable.class);
    private final Map<ListAppliedResult, Counter> listApplied = new EnumMap<>(ListAppliedResult.class);
    private final DistributionSummary listAppliedRows;
    private final Map<String, Counter> assetRpc = new HashMap<>();
    private final Map<AssetRpc, Timer> assetRpcDuration = new EnumMap<>(AssetRpc.class);
    private final Map<String, Counter> requery = new HashMap<>();
    private final Map<String, Counter> finalizeTotal = new HashMap<>();
    private final Map<String, Counter> rescheduleTotal = new HashMap<>();
    private final Map<String, Counter> rescheduleLost = new HashMap<>();
    private final Map<String, Counter> unknownTotal = new HashMap<>();
    private final Map<String, Counter> outcomeFlip = new HashMap<>();
    private final Map<String, Counter> partialTotal = new HashMap<>();
    private final Map<ClaimResult, Counter> claims = new EnumMap<>(ClaimResult.class);
    private final Map<LedgerReadResult, Counter> ledgerReads = new EnumMap<>(LedgerReadResult.class);
    private final Map<AssetOpStatus, Counter> manualResolves = new EnumMap<>(AssetOpStatus.class);
    private final Map<StoreOp, Counter> storeErrors = new EnumMap<>(StoreOp.class);
    private final Map<String, AtomicLong> pendingOldestAge = new HashMap<>();
    private final Map<ResolveResult, Counter> sceneResolve = new EnumMap<>(ResolveResult.class);

    public GuildMetrics(MeterRegistry registry) {
        this.registry = registry;
        // 请求：只预建可能出现的组合（16 + 5 个 C2S 走工作线程池；8 / 220 只会被拒；5 个活动占位只回 1006）
        for (List<String> methods : List.of(GuildMethods.CLIENT_REQUESTS, GuildMethods.ECONOMY_REQUESTS)) {
            for (String method : methods) {
                for (RequestResult result : List.of(RequestResult.OK, RequestResult.BUSINESS_ERROR,
                        RequestResult.INTERNAL_ERROR, RequestResult.OVERLOADED, RequestResult.BAD_REQUEST,
                        RequestResult.UNAUTHENTICATED)) {
                    timer(method, result);
                }
            }
        }
        // in-band 14008：建帮发号与 op_id 发号（捐献 / 兑换）
        for (String method : List.of(GuildMethods.CREATE_GUILD, GuildMethods.DONATE_TO_GUILD,
                GuildMethods.BUY_GUILD_SHOP_GOODS)) {
            timer(method, RequestResult.FAULT);
        }
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

        // ================================================================ 4.5 经济
        for (EconomyRpc rpc : EconomyRpc.values()) {
            Map<EconomyResult, Counter> byResult = new EnumMap<>(EconomyResult.class);
            for (EconomyResult result : EconomyResult.values()) {
                byResult.put(result, Counter.builder(ECONOMY_REQUESTS)
                        .description("帮会经济 RPC 的结果：写 RPC 成功按指令视图状态计（applied / pending / …），读 RPC 成功计 ok，"
                                + "升级 expected_level 不符计 unchanged；业务拒绝按原因计；error = 故障（信封 1003）")
                        .tag("rpc", rpc.label()).tag("result", result.label()).register(registry));
            }
            economyRequests.put(rpc, byResult);
        }
        for (AssetKind kind : AssetKind.values()) {
            syncSkipped.put(kind, Counter.builder(ASSET_SYNC_SKIPPED)
                    .description("请求剩余预算不足、跳过同步投递的次数（行在租约到期后由重投循环接手）。持续上升说明预留事务或发号偏慢")
                    .tag("kind", kind.label()).register(registry));
        }
        for (Orphan orphan : Orphan.values()) {
            orphans.put(orphan, Counter.builder(ASSET_ORPHANS)
                    .description("资产指令终结时对侧账无处可记：donate/guild_gone = 帮会已解散，donate/member_gone = 捐献者已离帮（资金照记），"
                            + "shop/refund_member_gone = 兑换被拒但兑换者已离帮（帮贡退不回去）")
                    .tag("kind", orphan.kind()).tag("what", orphan.what()).register(registry));
        }
        for (CleanupTable table : CleanupTable.values()) {
            cleanupDeleted.put(table, Counter.builder(ASSET_CLEANUP_DELETED)
                    .description("账本清理删掉的行数（终态指令行 / 过期计数行）")
                    .tag("table", table.label()).register(registry));
        }
        for (ListAppliedResult result : ListAppliedResult.values()) {
            listApplied.put(result, Counter.builder(INTERNAL_LIST_APPLIED)
                    .description("内部查询 ListAppliedAssetOpsSince 的结果（回档前检查）：unavailable / error 上升 = 回档闸问不到、所有回档被拒；"
                            + "retention = 快照早于终态流水保留期")
                    .tag("result", result.label()).register(registry));
        }
        this.listAppliedRows = DistributionSummary.builder(INTERNAL_LIST_APPLIED_ROWS)
                .description("内部查询 ListAppliedAssetOpsSince 成功调用每次返回的行数（单页上限 500）")
                .serviceLevelObjectives(LIST_APPLIED_ROW_BUCKETS).register(registry);

        // ================================================================ 4.5 资产通道（assetop_*）
        List<String> streams = AssetOpMetrics.STREAM_LABELS;
        for (String stream : streams) {
            for (AssetRpc rpc : AssetRpc.values()) {
                for (RpcOutcome outcome : RpcOutcome.values()) {
                    assetRpc.put(key(stream, rpc.wireName(), outcome.label()), Counter.builder(ASSETOP_RPC)
                            .description("资产 RPC 次数，按结局分：error = 传输层失败，no_location = 没人持有、没发出去")
                            .tag("stream", stream).tag("rpc", rpc.wireName()).tag("outcome", outcome.label())
                            .register(registry));
                }
            }
            for (AssetOpStatus status : AssetOpStatus.values()) {
                if (status.terminal()) {
                    finalizeTotal.put(key(stream, status.label()), Counter.builder(ASSETOP_FINALIZE)
                            .description("终结的资产指令行数，按最终状态分")
                            .tag("stream", stream).tag("status", status.label()).register(registry));
                }
            }
            for (AssetOpAction action : List.of(AssetOpAction.AWAIT_DURABLE, AssetOpAction.RETRY, AssetOpAction.ALERT)) {
                rescheduleTotal.put(key(stream, action.label()), Counter.builder(ASSETOP_RESCHEDULE)
                        .description("重排次数：await_durable / retry / alert")
                        .tag("stream", stream).tag("reason", action.label()).register(registry));
            }
            rescheduleLost.put(stream, Counter.builder(ASSETOP_RESCHEDULE_LOST)
                    .description("重排落空次数：租约已被别的副本接管，本次投递算出的结果被丢弃")
                    .tag("stream", stream).register(registry));
            unknownTotal.put(stream, Counter.builder(ASSETOP_UNKNOWN)
                    .description("scene 回 UNKNOWN / 坏流号 / 终结分支算不出状态的次数（配置不一致 / 纪元过期 / 跳号 / 验签失败），必须告警")
                    .tag("stream", stream).register(registry));
            outcomeFlip.put(stream, Counter.builder(ASSETOP_OUTCOME_FLIP)
                    .description("同一 seq 两次查询给出不同终结结局的次数，违反不变量 I2，必须告警")
                    .tag("stream", stream).register(registry));
            partialTotal.put(stream, Counter.builder(ASSETOP_PARTIAL)
                    .description("部分发放次数；对侧账不做，须人工补偿")
                    .tag("stream", stream).register(registry));
        }
        for (AssetRpc rpc : AssetRpc.values()) {
            assetRpcDuration.put(rpc, Timer.builder(ASSETOP_RPC_DURATION)
                    .description("单次资产 RPC 耗时（含 scene 逻辑线程排队；基线 assetop_rpc_seconds）")
                    .tag("rpc", rpc.wireName()).serviceLevelObjectives(ASSET_RPC_BUCKETS).register(registry));
            for (RequeryResult result : RequeryResult.values()) {
                requery.put(key(rpc.wireName(), result.label()), Counter.builder(ASSETOP_REQUERY)
                        .description("durable 重查结果：durable = 等到了落盘，timeout = 预算用完仍未落盘，error = 重查出错")
                        .tag("rpc", rpc.wireName()).tag("result", result.label()).register(registry));
            }
        }
        for (ClaimResult result : ClaimResult.values()) {
            claims.put(result, Counter.builder(ASSETOP_CLAIM)
                    .description("领取单行的结果：claimed / lost（被别的副本领走或已终结）/ poison（payload 解不开）")
                    .tag("result", result.label()).register(registry));
        }
        for (LedgerReadResult result : LedgerReadResult.values()) {
            ledgerReads.put(result, Counter.builder(ASSETOP_LEDGER_READ)
                    .description("离线读已落盘账本的结果：finalized / unseen / absent / error")
                    .tag("result", result.label()).register(registry));
        }
        for (AssetOpStatus status : AssetOpStatus.values()) {
            if (status.terminal()) {
                manualResolves.put(status, Counter.builder(ASSETOP_MANUAL_RESOLVE)
                        .description("人工终结次数，按最终状态分")
                        .tag("status", status.label()).register(registry));
            }
        }
        for (StoreOp op : StoreOp.values()) {
            storeErrors.put(op, Counter.builder(ASSETOP_STORE_ERRORS)
                    .description("资产账本存储出错次数：list / claim / decode / finalize / reschedule / ledger_read / pending_age / "
                            + "manual_resolve / settle_rejected（同步投递的落库执行器已满）")
                    .tag("op", op.label()).register(registry));
        }
        for (int stream : AssetOpMetrics.GUILD_STREAMS) {
            String label = AssetOpMetrics.streamLabel(stream);
            AtomicLong bits = new AtomicLong(Double.doubleToLongBits(0));
            pendingOldestAge.put(label, bits);
            Gauge.builder(ASSETOP_PENDING_OLDEST_AGE, bits, b -> Double.longBitsToDouble(b.get()))
                    .description("最老未决行的年龄（秒）；超过业务截止时间很久就是卡死行")
                    .tag("stream", label).register(registry);
        }
        for (ResolveResult result : ResolveResult.values()) {
            sceneResolve.put(result, Counter.builder(SCENE_RESOLVE)
                    .description("资产通道定位持有者节点的结局：found / not_online / lease / logged_out / node_unknown / no_rpc_port / "
                            + "error（error = Redis 出错或记录 / 目录条目损坏，按故障重投并告警）")
                    .tag("result", result.label()).register(registry));
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

    // ------------------------------------------------------------------ 4.5 经济

    public void economyRequest(EconomyRpc rpc, EconomyResult result) {
        economyRequests.get(rpc).get(result).increment();
    }

    public void syncSkipped(AssetKind kind) {
        syncSkipped.get(kind).increment();
    }

    public void assetOrphan(Orphan orphan) {
        orphans.get(orphan).increment();
    }

    public void assetCleanupDeleted(CleanupTable table, long n) {
        if (n > 0) {
            cleanupDeleted.get(table).increment(n);
        }
    }

    /** 内部查询一次调用的结果；成功（ok_empty / ok_rows）时另记行数直方图。 */
    public void listApplied(ListAppliedResult result, int rows) {
        listApplied.get(result).increment();
        if (result == ListAppliedResult.OK_EMPTY || result == ListAppliedResult.OK_ROWS) {
            listAppliedRows.record(rows);
        }
    }

    // ------------------------------------------------------------------ 4.5 资产通道（AssetOpMetrics）

    @Override
    public void rpc(int stream, AssetRpc rpc, RpcOutcome outcome, long elapsedNanos) {
        assetRpc.get(key(AssetOpMetrics.streamLabel(stream), rpc.wireName(), outcome.label())).increment();
        assetRpcDuration.get(rpc).record(Math.max(0, elapsedNanos), TimeUnit.NANOSECONDS);
    }

    @Override
    public void noLocation(int stream, AssetRpc rpc) {
        // 不进耗时直方图：没有网络往返可言（metrics.go:191-198）
        assetRpc.get(key(AssetOpMetrics.streamLabel(stream), rpc.wireName(), RpcOutcome.NO_LOCATION.label())).increment();
    }

    @Override
    public void requery(AssetRpc rpc, RequeryResult result) {
        requery.get(key(rpc.wireName(), result.label())).increment();
    }

    @Override
    public void outcomeFlip(int stream) {
        outcomeFlip.get(AssetOpMetrics.streamLabel(stream)).increment();
    }

    @Override
    public void partial(int stream) {
        partialTotal.get(AssetOpMetrics.streamLabel(stream)).increment();
    }

    @Override
    public void resolved(ResolveResult result) {
        if (result != null) {
            sceneResolve.get(result).increment();
        }
    }

    @Override
    public void finalized(int stream, AssetOpStatus status) {
        Counter c = finalizeTotal.get(key(AssetOpMetrics.streamLabel(stream), status.label()));
        if (c != null) {
            c.increment();
        }
    }

    @Override
    public void rescheduled(int stream, AssetOpAction reason) {
        Counter c = rescheduleTotal.get(key(AssetOpMetrics.streamLabel(stream), reason.label()));
        if (c != null) {
            c.increment();
        }
    }

    @Override
    public void rescheduleLost(int stream) {
        rescheduleLost.get(AssetOpMetrics.streamLabel(stream)).increment();
    }

    @Override
    public void unknown(int stream) {
        unknownTotal.get(AssetOpMetrics.streamLabel(stream)).increment();
    }

    @Override
    public void claim(ClaimResult result) {
        claims.get(result).increment();
    }

    @Override
    public void ledgerRead(LedgerReadResult result) {
        ledgerReads.get(result).increment();
    }

    @Override
    public void manualResolve(AssetOpStatus status) {
        Counter c = manualResolves.get(status);
        if (c != null) {
            c.increment();
        }
    }

    @Override
    public void storeError(StoreOp op) {
        storeErrors.get(op).increment();
    }

    @Override
    public void pendingOldestAge(int stream, double seconds) {
        AtomicLong bits = pendingOldestAge.get(AssetOpMetrics.streamLabel(stream));
        if (bits != null) {
            bits.set(Double.doubleToLongBits(Math.max(0, seconds)));
        }
    }

    private static String key(String... parts) {
        return String.join("\0", parts);
    }

    static String tagValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
