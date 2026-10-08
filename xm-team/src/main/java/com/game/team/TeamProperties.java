package com.game.team;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xm-team 业务配置（{@code xm.team.*}，team-spec §6.11）。缺省值同基线 go/match（match_service.yaml / team 包常量）。
 * 规则常量（容量 5、申请 / 邀请 TTL 与上限……）写死在代码里，不做成配置（§0.4）。组队人数（{@code pve-team-size-by-config-id}）
 * 不在 xm-team 配：它归 xm-match（{@code xm.match.pve-team-size-by-config-id}），xm-team 经 {@code checkTeamMatch} 取结论（match-spec Q11）。
 *
 * @param allowCrossZone      是否允许不同 home zone 的玩家同队（缺省 false；从 true 改回 false 只拦新增）
 * @param requestBudget       整请求预算（缺省 3500 ms，须在 [500 ms, 3500 ms] 内：先于 gate 调 team 的 5 s Dubbo 超时把 in-band 结果回去）
 * @param pushBatchBudget     一批推送的总预算（缺省 3 s，基线 notify.go:37 pushBatchBudget）
 * @param homeZoneTimeout     home zone 单次查询预算（缺省 1500 ms，基线 homezone.go:31-33；请求预算仍能截断它）
 * @param workerThreads       请求工作线程数（缺省 16；等 Redis、读 MySQL 都在这组线程上，不占 Dubbo 线程）
 * @param workerQueueCapacity 请求工作队列上限（缺省 1024；满了回 in-band 4030，不无限堆积）
 * @param pushThreads         推送线程数（缺省 4）
 * @param pushQueueCapacity   推送队列上限（缺省 1024；满了整批放弃，记 error）
 * @param matchEndThreads     整队开战收尾（清开战锁、退票）的线程数（缺省 4；一次收尾通常是一两次 Redis 往返，Redis 持续故障时
 *                            一个任务最坏占线程 110 s）
 * @param matchEndQueueCapacity 整队开战收尾队列上限（缺省 1024；满了放弃这次收尾并记 ERROR，开战锁靠自然过期）
 */
@ConfigurationProperties("xm.team")
public record TeamProperties(
        Boolean allowCrossZone,
        Duration requestBudget,
        Duration pushBatchBudget,
        Duration homeZoneTimeout,
        Integer workerThreads,
        Integer workerQueueCapacity,
        Integer pushThreads,
        Integer pushQueueCapacity,
        Integer matchEndThreads,
        Integer matchEndQueueCapacity) {

    /** 整请求预算的区间（基线 teamRPCBudget = 3500 ms，赶在 gate 的 5 s Dubbo 超时之前）。 */
    public static final Duration MIN_REQUEST_BUDGET = Duration.ofMillis(500);
    public static final Duration MAX_REQUEST_BUDGET = Duration.ofMillis(3500);

    public TeamProperties {
        allowCrossZone = allowCrossZone != null && allowCrossZone;
        requestBudget = positiveOr(requestBudget, MAX_REQUEST_BUDGET, "request-budget");
        if (requestBudget.compareTo(MIN_REQUEST_BUDGET) < 0 || requestBudget.compareTo(MAX_REQUEST_BUDGET) > 0) {
            throw new IllegalArgumentException("xm.team.request-budget 必须在 [" + MIN_REQUEST_BUDGET + ", " + MAX_REQUEST_BUDGET
                    + "] 内（gate 调 team 的 Dubbo 超时 5 s 先到会让客户端看到失败而写已落盘）: " + requestBudget);
        }
        pushBatchBudget = positiveOr(pushBatchBudget, Duration.ofSeconds(3), "push-batch-budget");
        homeZoneTimeout = positiveOr(homeZoneTimeout, Duration.ofMillis(1500), "home-zone-timeout");
        workerThreads = positiveOr(workerThreads, 16, "worker-threads");
        workerQueueCapacity = positiveOr(workerQueueCapacity, 1024, "worker-queue-capacity");
        pushThreads = positiveOr(pushThreads, 4, "push-threads");
        pushQueueCapacity = positiveOr(pushQueueCapacity, 1024, "push-queue-capacity");
        matchEndThreads = positiveOr(matchEndThreads, 4, "match-end-threads");
        matchEndQueueCapacity = positiveOr(matchEndQueueCapacity, 1024, "match-end-queue-capacity");
    }

    /** MySQL 单条查询超时的上限（秒）：请求预算向上取整到秒（实际取它与剩余预算的较小者）。 */
    public int queryTimeoutCapSeconds() {
        return (int) Math.max(1, (requestBudget.toMillis() + 999) / 1000);
    }

    private static Duration positiveOr(Duration value, Duration fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException("xm.team." + name + " 必须为正: " + value);
        }
        return value;
    }

    private static int positiveOr(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("xm.team." + name + " 必须为正: " + value);
        }
        return value;
    }
}
