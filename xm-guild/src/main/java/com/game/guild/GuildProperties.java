package com.game.guild;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xm-guild 业务配置（{@code xm.guild.*}，guild-spec §7.12）。缺省值同基线 go/guild（guild.yaml / config.go / 包常量）。
 *
 * <p>业务上限（申请有效期、每人 / 每帮待审数、成员与长老上限）<b>只读配表</b>，不做成配置项（guild_manage_logic.go:37-96）；
 * 事务子预算、重试次数、冷却、榜页长上限、清理行数、名字与公告上限是代码常量（{@code rules.GuildLimits}），也不开放配置。
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
 */
@ConfigurationProperties("xm.guild")
public record GuildProperties(
        Duration requestBudget,
        Duration cacheTtl,
        Duration pushTimeout,
        Duration onlineLookupTimeout,
        Duration queryTimeout,
        Integer workerThreads,
        Integer workerQueueCapacity) {

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
    }

    /** MySQL 单条查询超时的上限（秒，至少 1）：{@code query-timeout} 取整秒（实际再取它与剩余预算向上取整的较小者）。 */
    public int queryTimeoutCapSeconds() {
        return (int) Math.max(1, queryTimeout.toSeconds());
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
