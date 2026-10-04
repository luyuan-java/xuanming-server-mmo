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
        Integer workerQueueCapacity) {

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
    }

    public FriendLimits limits() {
        return new FriendLimits(maxFriends, maxPendingRequests, maxIncomingRequests, maxBlocks);
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
