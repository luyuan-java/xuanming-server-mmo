package com.game.friend;

import com.game.friend.store.FriendLimits;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xm-friend 业务配置（{@code xm.friend.*}）。缺省值与启动校验照 mmorpg go/friend internal/config（friend-spec.md §0.4）。
 *
 * @param maxFriends            每人好友数上限（缺省 200，硬天花板 300：推荐的二度查询开销按它的平方增长）
 * @param maxPendingRequests    出站待处理申请上限（缺省 50）
 * @param maxIncomingRequests   入站待处理申请上限（缺省 200）
 * @param maxBlocks             黑名单上限（缺省 200）
 * @param requestQuotaPerMinute 每人每分钟发申请的次数（缺省 10，固定 60 s 窗口；Redis 故障时放行）
 * @param listReadHardLimit     列表读的行数上限，同时是在线批量读的分批大小（缺省 1000）
 * @param cacheTtl              好友列表 / 入站申请缓存的 TTL（缺省 30m；必须为正，否则不一致时无法自愈）
 * @param requestBudget         整请求预算（缺省 3500 ms，须在 [500 ms, 3500 ms] 内：先于 gate 调 friend 的 5 s Dubbo 超时）
 * @param pushTimeout           推送的上界（缺省 1500 ms）
 * @param queryTimeout          每条 SQL 查询超时的上限（缺省 3 s；实际取它与剩余预算的较小者，不得超过预算）
 * @param workerThreads         阻塞工作线程数（MySQL / 等 Redis 都在这组线程上，不占 Dubbo 线程）
 * @param workerQueueCapacity   工作队列上限；满了直接回 1003，不无限堆积
 * @param recommend             推荐与在线目录（{@link Recommend}）
 * @param sweep                 后台清理（{@link Sweep}）
 */
@ConfigurationProperties("xm.friend")
public record FriendProperties(
        Integer maxFriends,
        Integer maxPendingRequests,
        Integer maxIncomingRequests,
        Integer maxBlocks,
        Integer requestQuotaPerMinute,
        Integer listReadHardLimit,
        Duration cacheTtl,
        Duration requestBudget,
        Duration pushTimeout,
        Duration queryTimeout,
        Integer workerThreads,
        Integer workerQueueCapacity,
        Recommend recommend,
        Sweep sweep) {

    /** 好友数硬天花板（基线 maxFriendsCeiling）。 */
    public static final int MAX_FRIENDS_CEILING = 300;
    /** 整请求预算的区间（基线 Timeout ∈ [1000, 4000] ms、预算 = Timeout − 500 ms）。 */
    public static final Duration MIN_REQUEST_BUDGET = Duration.ofMillis(500);
    public static final Duration MAX_REQUEST_BUDGET = Duration.ofMillis(3500);

    public FriendProperties {
        maxFriends = positiveOr(maxFriends, 200, "max-friends");
        if (maxFriends > MAX_FRIENDS_CEILING) {
            throw new IllegalArgumentException("xm.friend.max-friends 不能超过 " + MAX_FRIENDS_CEILING + ": " + maxFriends);
        }
        maxPendingRequests = positiveOr(maxPendingRequests, 50, "max-pending-requests");
        maxIncomingRequests = positiveOr(maxIncomingRequests, 200, "max-incoming-requests");
        maxBlocks = positiveOr(maxBlocks, 200, "max-blocks");
        requestQuotaPerMinute = positiveOr(requestQuotaPerMinute, 10, "request-quota-per-minute");
        listReadHardLimit = positiveOr(listReadHardLimit, 1000, "list-read-hard-limit");
        cacheTtl = positiveOr(cacheTtl, Duration.ofMinutes(30), "cache-ttl");
        requestBudget = positiveOr(requestBudget, Duration.ofMillis(3500), "request-budget");
        if (requestBudget.compareTo(MIN_REQUEST_BUDGET) < 0 || requestBudget.compareTo(MAX_REQUEST_BUDGET) > 0) {
            throw new IllegalArgumentException("xm.friend.request-budget 必须在 [" + MIN_REQUEST_BUDGET + ", " + MAX_REQUEST_BUDGET
                    + "] 内（gate 调 friend 的 Dubbo 超时 5 s 先到会让客户端看到失败而写已落库）: " + requestBudget);
        }
        pushTimeout = positiveOr(pushTimeout, Duration.ofMillis(1500), "push-timeout");
        queryTimeout = positiveOr(queryTimeout, Duration.ofSeconds(3), "query-timeout");
        if (queryTimeout.compareTo(requestBudget) > 0 && queryTimeout.toSeconds() > (requestBudget.toMillis() + 999) / 1000) {
            throw new IllegalArgumentException("xm.friend.query-timeout 不能超过 request-budget（向上取整到秒）: " + queryTimeout);
        }
        workerThreads = positiveOr(workerThreads, 16, "worker-threads");
        workerQueueCapacity = positiveOr(workerQueueCapacity, 1024, "worker-queue-capacity");
        recommend = recommend == null ? new Recommend(null, null, null) : recommend;
        sweep = sweep == null ? new Sweep(null, null, null, null) : sweep;
    }

    public FriendLimits limits() {
        return new FriendLimits(maxFriends, maxPendingRequests, maxIncomingRequests, maxBlocks);
    }

    /**
     * 推荐（{@code xm.friend.recommend.*}）。
     *
     * @param defaultLimit 请求 limit = 0 时的取值（缺省 10，不得大于上限）
     * @param maxLimit     limit 上限（缺省 20，硬天花板 20）
     * @param maxExclude   exclude_player_ids 条数上限（缺省 64；超了回 1005，不截断）
     */
    public record Recommend(Integer defaultLimit, Integer maxLimit, Integer maxExclude) {

        public static final int MAX_LIMIT_CEILING = 20;

        public Recommend {
            defaultLimit = positiveOr(defaultLimit, 10, "recommend.default-limit");
            maxLimit = positiveOr(maxLimit, 20, "recommend.max-limit");
            if (maxLimit > MAX_LIMIT_CEILING) {
                throw new IllegalArgumentException("xm.friend.recommend.max-limit 不能超过 " + MAX_LIMIT_CEILING + ": " + maxLimit);
            }
            if (defaultLimit > maxLimit) {
                throw new IllegalArgumentException("xm.friend.recommend.default-limit（" + defaultLimit
                        + "）不能大于 max-limit（" + maxLimit + "）");
            }
            maxExclude = positiveOr(maxExclude, 64, "recommend.max-exclude");
        }
    }

    /**
     * 后台清理（{@code xm.friend.sweep.*}）：终态好友申请与零好友容量行。
     *
     * @param mode          {@code report_only}（缺省：只数不删，积压 &gt; 0 打告警日志）或 {@code delete}
     * @param interval      节拍（缺省 5m）；单轮预算 min(interval, 30 s)
     * @param retentionDays 保留天数（缺省 7，[1, 36500]）；也是容量行回收的保留期
     * @param batchLimit    一轮至多处理的行数（缺省 1000）
     */
    public record Sweep(String mode, Duration interval, Integer retentionDays, Integer batchLimit) {

        public Sweep {
            mode = mode == null ? "report_only" : mode;
            if (!mode.equals("report_only") && !mode.equals("delete")) {
                throw new IllegalArgumentException("xm.friend.sweep.mode 只能是 report_only 或 delete: '" + mode + "'");
            }
            interval = positiveOr(interval, Duration.ofMinutes(5), "sweep.interval");
            if (interval.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("xm.friend.sweep.interval 至少 1 秒: " + interval);
            }
            retentionDays = positiveOr(retentionDays, 7, "sweep.retention-days");
            if (retentionDays > 36500) {
                throw new IllegalArgumentException("xm.friend.sweep.retention-days 不能超过 36500: " + retentionDays);
            }
            batchLimit = positiveOr(batchLimit, 1000, "sweep.batch-limit");
        }
    }

    private static Duration positiveOr(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("xm.friend." + name + " 必须为正: " + value);
        }
        return value;
    }

    private static int positiveOr(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("xm.friend." + name + " 必须为正: " + value);
        }
        return value;
    }
}
