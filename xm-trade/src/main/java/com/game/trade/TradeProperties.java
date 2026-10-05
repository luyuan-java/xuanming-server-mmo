package com.game.trade;

import com.game.trade.rules.ListingRules;
import com.game.trade.service.MarketSettings;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xm-trade 业务配置（{@code xm.trade.*}；trade-spec §5.11）。缺省值与启动校验照基线 go/trade 的 Config.Validate（config.go:255-325）与
 * MarketConf（config.go:189-202），不合法即拒启。
 *
 * <p>单次上限（归属区 1500 ms、单条 SQL 2000 ms、收藏写入 2 次尝试）、文本 / 图标 / 等级 / 价格 / 时长上限是代码常量（{@code rules.TradeLimits}），
 * 不开放配置。运行模式（播种开关）读 {@code xm.run-mode}；运维令牌只从环境变量 {@code XM_ADMIN_TOKEN} 读。
 *
 * @param market              市场参数（{@link Market}；scope <b>必填</b>）
 * @param requestBudget       整请求预算（缺省 3500 ms = 基线 Timeout 4000 − 500，config.go:28-39、:219-221；须在 [500 ms, 3500 ms] 内：
 *                            先于 gate 调 trade 的 5 s Dubbo 超时结束，in-band 结果才不会被调用方超时吞掉）
 * @param queryTimeout        每条 SQL 查询超时的上限（缺省 3 s 与预算向上取整到秒的较小者；实际取它、单次 2000 ms 上限与剩余预算三者的较小者；
 *                            显式配置不得超过预算）
 * @param workerThreads       阻塞工作线程数（缺省 16；MySQL 都在这组线程上，不占 Dubbo 线程）
 * @param workerQueueCapacity 工作队列上限（缺省 1024）；满了回 in-band 1003，不无限堆积
 */
@ConfigurationProperties("xm.trade")
public record TradeProperties(
        Market market,
        Duration requestBudget,
        Duration queryTimeout,
        Integer workerThreads,
        Integer workerQueueCapacity) {

    /** 整请求预算的区间（基线 Timeout ∈ [1000, 4000] ms、预算 = Timeout − 500 ms）。 */
    public static final Duration MIN_REQUEST_BUDGET = Duration.ofMillis(500);
    public static final Duration MAX_REQUEST_BUDGET = Duration.ofMillis(3500);

    public TradeProperties {
        if (market == null) {
            throw new IllegalArgumentException("xm.trade.market.scope 必填（zone 或 global）：范围决定玩家能看到哪些商品，写错或漏写都必须在启动时暴露"
                    + "（基线 MarketConf.Scope 不给缺省值，config.go:191-193）");
        }
        requestBudget = positiveOr(requestBudget, MAX_REQUEST_BUDGET, "request-budget");
        if (requestBudget.compareTo(MIN_REQUEST_BUDGET) < 0 || requestBudget.compareTo(MAX_REQUEST_BUDGET) > 0) {
            throw new IllegalArgumentException("xm.trade.request-budget 必须在 [" + MIN_REQUEST_BUDGET + ", " + MAX_REQUEST_BUDGET
                    + "] 内（gate 调 trade 的 Dubbo 超时 5 s 先到会让客户端看到失败而不是 in-band 结果）: " + requestBudget);
        }
        queryTimeout = positiveOr(queryTimeout, min(Duration.ofSeconds(3), Duration.ofSeconds(ceilSeconds(requestBudget))),
                "query-timeout");
        if (queryTimeout.toSeconds() > ceilSeconds(requestBudget)) {
            throw new IllegalArgumentException("xm.trade.query-timeout 不能超过 request-budget（向上取整到秒）: " + queryTimeout);
        }
        workerThreads = positiveOr(workerThreads, 16, "worker-threads");
        workerQueueCapacity = positiveOr(workerQueueCapacity, 1024, "worker-queue-capacity");
    }

    /** MySQL 单条查询超时的上限（秒，至少 1）。 */
    public int queryTimeoutCapSeconds() {
        return (int) Math.max(1, queryTimeout.toSeconds());
    }

    /**
     * 市场参数（{@code xm.trade.market.*}；基线 Market 段，trade.yaml:91-100）。
     *
     * @param scope                 {@code zone}（浏览 / 详情 / 收藏按调用者归属区，别区商品对调用者「不存在」，zone_filter 被忽略）或
     *                              {@code global}（全服可见，zone_filter ≠ 0 时按它过滤）。<b>没有缺省值</b>，只认这两个字面量（区分大小写，同 ScopeEnum）
     * @param defaultPageSize       请求 page_size = 0 时的页长（缺省 20，&gt; 0 且 ≤ maxPageSize）
     * @param maxPageSize           page_size 上限（缺省 20，&gt; 0）
     * @param maxPage               页码上限（缺省 100，&gt; 0）：防深分页，OFFSET ≤ (maxPage − 1) × maxPageSize
     * @param maxFavoritesPerPlayer 每玩家收藏条数软上限（缺省 100，&gt; 0）
     */
    public record Market(String scope, Integer defaultPageSize, Integer maxPageSize, Integer maxPage, Integer maxFavoritesPerPlayer) {

        public static final String SCOPE_ZONE = "zone";
        public static final String SCOPE_GLOBAL = "global";

        public Market {
            if (!SCOPE_ZONE.equals(scope) && !SCOPE_GLOBAL.equals(scope)) {
                throw new IllegalArgumentException("xm.trade.market.scope 必须是 \"" + SCOPE_ZONE + "\" 或 \"" + SCOPE_GLOBAL + "\"，得到 "
                        + (scope == null ? "<未配置>" : "\"" + scope + "\"") + "（config.go:305-307）");
            }
            defaultPageSize = positiveOr(defaultPageSize, 20, "market.default-page-size");
            maxPageSize = positiveOr(maxPageSize, 20, "market.max-page-size");
            maxPage = positiveOr(maxPage, 100, "market.max-page");
            maxFavoritesPerPlayer = positiveOr(maxFavoritesPerPlayer, 100, "market.max-favorites-per-player");
            if (defaultPageSize > maxPageSize) {
                throw new IllegalArgumentException("xm.trade.market.default-page-size（" + defaultPageSize
                        + "）不能大于 max-page-size（" + maxPageSize + "）（config.go:320-323）");
            }
        }

        /** 服务层的市场参数（scope 转成协议枚举）。 */
        public MarketSettings settings() {
            return new MarketSettings(ListingRules.scopeOf(scope), defaultPageSize, maxPageSize, maxPage, maxFavoritesPerPlayer);
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
            throw new IllegalArgumentException("xm.trade." + name + " 必须为正: " + value);
        }
        return value;
    }

    private static int positiveOr(Integer value, int fallback, String name) {
        if (value == null) {
            return fallback;
        }
        if (value <= 0) {
            throw new IllegalArgumentException("xm.trade." + name + " 必须为正: " + value);
        }
        return value;
    }
}
