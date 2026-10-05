package com.game.guild;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xm-guild 业务配置（{@code xm.guild.*}，guild-spec §7.12、guild-economy-spec §7.6）。缺省值同基线 go/guild（guild.yaml / config.go / 包常量）。
 *
 * <p>业务上限（申请有效期、每人 / 每帮待审数、成员与长老上限）<b>只读配表</b>，不做成配置项（guild_manage_logic.go:37-96）；
 * 事务子预算、重试次数、冷却、榜页长上限、清理行数、名字与公告上限、资产通道的同步投递预算 / 单次调用 / 重查 / settle / 守卫是代码常量
 * （{@code rules.GuildLimits}），也不开放配置。
 *
 * @param requestBudget       整请求预算（缺省 3500 ms = 基线 Timeout − 500，须在 [500 ms, 3500 ms] 内：先于 gate 调 guild 的 5 s Dubbo 超时结束，
 *                            否则客户端看到失败而写已落库；config.go:16-19）
 * @param cacheTtl            帮会快照与玩家 → 帮会映射缓存的 TTL（缺省 30m，必须为正，否则不一致时无法自愈；guild.yaml:67-68）
 * @param pushTimeout         一批推送的上界（缺省 3 s，基线 guildPushBudget，push.go:41；不继承请求预算）
 * @param onlineLookupTimeout 成员 / 申请人在线状态批量读的独立上限（缺省 800 ms 与预算的较小者，D6：基线 MGET 没有独立超时，
 *                            §9.1 第 11 条），实际取它与请求剩余预算的较小者；显式配置不得超过 request-budget
 * @param queryTimeout        每条 SQL 查询超时的上限（缺省 3 s 与预算向上取整到秒的较小者；实际取它与剩余预算的较小者，显式配置不得超过预算）
 * @param workerThreads       阻塞工作线程数（MySQL / 等 Redis 都在这组线程上，不占 Dubbo 线程；缺省 16）
 * @param workerQueueCapacity 工作队列上限（缺省 1024）；满了回 in-band 14021「guild service overloaded」，不无限堆积
 * @param assetOp             资产通道与重投循环（{@code xm.guild.asset-op.*}，整段缺省 = 通道关闭，fail-closed）
 */
@ConfigurationProperties("xm.guild")
public record GuildProperties(
        Duration requestBudget,
        Duration cacheTtl,
        Duration pushTimeout,
        Duration onlineLookupTimeout,
        Duration queryTimeout,
        Integer workerThreads,
        Integer workerQueueCapacity,
        AssetOp assetOp) {

    /** 整请求预算的区间（基线 Timeout − 500 ms，Timeout ≤ 路由服 5000 − 1000）。 */
    public static final Duration MIN_REQUEST_BUDGET = Duration.ofMillis(500);
    public static final Duration MAX_REQUEST_BUDGET = Duration.ofMillis(3500);

    public GuildProperties {
        requestBudget = positiveOr(requestBudget, MAX_REQUEST_BUDGET, "request-budget");
        if (requestBudget.compareTo(MIN_REQUEST_BUDGET) < 0 || requestBudget.compareTo(MAX_REQUEST_BUDGET) > 0) {
            throw new IllegalArgumentException("xm.guild.request-budget 必须在 [" + MIN_REQUEST_BUDGET + ", " + MAX_REQUEST_BUDGET
                    + "] 内（gate 调 guild 的 Dubbo 超时 5 s 先到会让客户端看到失败而写已落库）: " + requestBudget);
        }
        cacheTtl = positiveOr(cacheTtl, Duration.ofMinutes(30), "cache-ttl");
        pushTimeout = positiveOr(pushTimeout, Duration.ofSeconds(3), "push-timeout");
        // 两个上限不写时取缺省值与预算的较小者（预算调小时不必跟着改）；显式写了超过预算的值则拒启
        onlineLookupTimeout = positiveOr(onlineLookupTimeout, min(Duration.ofMillis(800), requestBudget),
                "online-lookup-timeout");
        if (onlineLookupTimeout.compareTo(requestBudget) > 0) {
            throw new IllegalArgumentException("xm.guild.online-lookup-timeout 不能超过 request-budget（在线状态只是展示字段）: "
                    + onlineLookupTimeout);
        }
        queryTimeout = positiveOr(queryTimeout, min(Duration.ofSeconds(3), Duration.ofSeconds(ceilSeconds(requestBudget))),
                "query-timeout");
        if (queryTimeout.toSeconds() > ceilSeconds(requestBudget)) {
            throw new IllegalArgumentException("xm.guild.query-timeout 不能超过 request-budget（向上取整到秒）: " + queryTimeout);
        }
        workerThreads = positiveOr(workerThreads, 16, "worker-threads");
        workerQueueCapacity = positiveOr(workerQueueCapacity, 1024, "worker-queue-capacity");
        assetOp = assetOp == null ? AssetOp.defaults() : assetOp;
    }

    /** MySQL 单条查询超时的上限（秒，至少 1）：{@code query-timeout} 取整秒（实际再取它与剩余预算向上取整的较小者）。 */
    public int queryTimeoutCapSeconds() {
        return (int) Math.max(1, queryTimeout.toSeconds());
    }

    /**
     * 资产通道（{@code xm.guild.asset-op.*}；基线 AssetOp 段，config.go:94-235、guild.yaml:117-143；guild-economy-spec §0.6、§7.6）。
     *
     * <p>{@code enabled} 缺省 <b>false</b>（基线「整段缺失 = 关闭」，fail-closed；Spring 的缺省值与 Go 的零值不同，必须显式写 false，§9.2 第 12 条）：
     * 关闭时捐献 / 兑换回 14026、不写任何行，升级与两个读页照常，清理按自己的开关，内部查询照常。开启时才校验循环参数并建签名器 / 定位 /
     * 调用方 / 循环。清理参数只在 {@code cleanup-enabled} 时校验；两个保留期<b>总是</b>校验（内部查询的可证明窗口依赖终态保留期，与清理开关无关，
     * guild.go:366-367）。区间与基线完全一致。
     *
     * @param enabled               通道开关
     * @param reconcileInterval     Tick 间隔（缺省 2 s，[200 ms, 60 s]）
     * @param reconcileBatch        每次 Tick 至多领多少行（缺省 100，[workers, 1000]）
     * @param workers               <b>每个副本</b>同时在途的资产 RPC 上限（缺省 8，[1, 64]；理由见 reconcile.go:229-241，Q10 保持 8）
     * @param lease                 循环的行租约，也是插行租约（缺省 10 s，[op-budget + 2 s, 600 s]）
     * @param opBudget              单行预算，含 700 ms 固定落库（缺省 2500 ms，[1 s, 10 s]）
     * @param maxBackoff            退避封顶，也是 ALERT 的重排间隔（缺省 60 s，[1 s, 600 s]）
     * @param poisonDelay           毒行推迟多久（缺省 1 h，[60 s, 24 h]）
     * @param ledgerReadMinAttempts 离线读已落盘账本的尝试门槛（缺省 3，≥ 1；E8 接线后生效）
     * @param cleanupEnabled        清理开关（缺省 false，与 enabled 互相独立）
     * @param cleanupInterval       清理间隔（缺省 10 min，[1 min, 1440 min]）
     * @param terminalRetention     终态行保留期（缺省 30 d，[7 d, 365 d]）；也是内部查询可证明窗口的下界
     * @param counterRetention      计数行保留期（缺省 30 d，[7 d, 365 d]）；清理按 max(它, 8 d) 算
     */
    public record AssetOp(
            Boolean enabled,
            Duration reconcileInterval,
            Integer reconcileBatch,
            Integer workers,
            Duration lease,
            Duration opBudget,
            Duration maxBackoff,
            Duration poisonDelay,
            Integer ledgerReadMinAttempts,
            Boolean cleanupEnabled,
            Duration cleanupInterval,
            Duration terminalRetention,
            Duration counterRetention) {

        public static final Duration MIN_RECONCILE_INTERVAL = Duration.ofMillis(200);
        public static final Duration MAX_RECONCILE_INTERVAL = Duration.ofSeconds(60);
        public static final int MAX_RECONCILE_BATCH = 1000;
        public static final int MAX_WORKERS = 64;
        public static final Duration MIN_OP_BUDGET = Duration.ofSeconds(1);
        public static final Duration MAX_OP_BUDGET = Duration.ofSeconds(10);
        /** 租约相对单行预算的余量（reconcile.go:28-35 leaseHeadroom）。 */
        public static final Duration LEASE_HEADROOM = Duration.ofSeconds(2);
        public static final Duration MAX_LEASE = Duration.ofSeconds(600);
        public static final Duration MIN_MAX_BACKOFF = Duration.ofSeconds(1);
        public static final Duration MAX_MAX_BACKOFF = Duration.ofSeconds(600);
        public static final Duration MIN_POISON_DELAY = Duration.ofSeconds(60);
        public static final Duration MAX_POISON_DELAY = Duration.ofHours(24);
        public static final Duration MIN_CLEANUP_INTERVAL = Duration.ofMinutes(1);
        public static final Duration MAX_CLEANUP_INTERVAL = Duration.ofMinutes(1440);
        public static final Duration MIN_RETENTION = Duration.ofDays(7);
        public static final Duration MAX_RETENTION = Duration.ofDays(365);

        public AssetOp {
            enabled = enabled != null && enabled;
            reconcileInterval = positive(reconcileInterval, Duration.ofSeconds(2), "reconcile-interval");
            workers = positive(workers, 8, "workers");
            reconcileBatch = positive(reconcileBatch, 100, "reconcile-batch");
            opBudget = positive(opBudget, Duration.ofMillis(2500), "op-budget");
            lease = positive(lease, Duration.ofSeconds(10), "lease");
            maxBackoff = positive(maxBackoff, Duration.ofSeconds(60), "max-backoff");
            poisonDelay = positive(poisonDelay, Duration.ofHours(1), "poison-delay");
            ledgerReadMinAttempts = positive(ledgerReadMinAttempts, 3, "ledger-read-min-attempts");
            cleanupEnabled = cleanupEnabled != null && cleanupEnabled;
            cleanupInterval = positive(cleanupInterval, Duration.ofMinutes(10), "cleanup-interval");
            terminalRetention = positive(terminalRetention, Duration.ofDays(30), "terminal-retention");
            counterRetention = positive(counterRetention, Duration.ofDays(30), "counter-retention");

            // 保留期总是校验：内部查询的可证明窗口依赖终态保留期，与清理开关无关（guild.go:366-367）
            within(terminalRetention, MIN_RETENTION, MAX_RETENTION, "terminal-retention");
            within(counterRetention, MIN_RETENTION, MAX_RETENTION, "counter-retention");
            if (cleanupEnabled) {
                within(cleanupInterval, MIN_CLEANUP_INTERVAL, MAX_CLEANUP_INTERVAL, "cleanup-interval");
            }
            if (enabled) {
                // 同 config.go:94-235 与 assetop.LoopConfig.validate（reconcile.go:284-320）：一眼能看出的配置错，启动时就拒
                within(reconcileInterval, MIN_RECONCILE_INTERVAL, MAX_RECONCILE_INTERVAL, "reconcile-interval");
                if (workers < 1 || workers > MAX_WORKERS) {
                    throw new IllegalArgumentException("xm.guild.asset-op.workers 必须在 [1, " + MAX_WORKERS + "] 内: " + workers);
                }
                if (reconcileBatch < workers || reconcileBatch > MAX_RECONCILE_BATCH) {
                    throw new IllegalArgumentException("xm.guild.asset-op.reconcile-batch 必须在 [workers(" + workers + "), "
                            + MAX_RECONCILE_BATCH + "] 内: " + reconcileBatch);
                }
                within(opBudget, MIN_OP_BUDGET, MAX_OP_BUDGET, "op-budget");
                // 租约必须覆盖「处理耗时 + 余量」，否则一行还在处理中就被另一个副本领走
                within(lease, opBudget.plus(LEASE_HEADROOM), MAX_LEASE, "lease");
                within(maxBackoff, MIN_MAX_BACKOFF, MAX_MAX_BACKOFF, "max-backoff");
                within(poisonDelay, MIN_POISON_DELAY, MAX_POISON_DELAY, "poison-delay");
            }
        }

        /** 整段缺省（通道关闭、清理关闭）。 */
        public static AssetOp defaults() {
            return new AssetOp(null, null, null, null, null, null, null, null, null, null, null, null, null);
        }

        private static void within(Duration value, Duration min, Duration max, String name) {
            if (value.compareTo(min) < 0 || value.compareTo(max) > 0) {
                throw new IllegalArgumentException("xm.guild.asset-op." + name + " 必须在 [" + min + ", " + max + "] 内: " + value);
            }
        }

        private static Duration positive(Duration value, Duration fallback, String name) {
            if (value == null) {
                return fallback;
            }
            if (value.isNegative() || value.isZero()) {
                throw new IllegalArgumentException("xm.guild.asset-op." + name + " 必须为正: " + value);
            }
            return value;
        }

        private static int positive(Integer value, int fallback, String name) {
            if (value == null) {
                return fallback;
            }
            if (value <= 0) {
                throw new IllegalArgumentException("xm.guild.asset-op." + name + " 必须为正: " + value);
            }
            return value;
        }
    }

    private static long ceilSeconds(Duration d) {
        return (d.toMillis() + 999) / 1000;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    private static Duration positiveOr(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("xm.guild." + name + " 必须为正: " + value);
        }
        return value;
    }

    private static int positiveOr(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("xm.guild." + name + " 必须为正: " + value);
        }
        return value;
    }
}
