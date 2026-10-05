package com.game.trade.rules;

/**
 * 聚宝斋的代码常量（基线 go/trade/internal/constants/constants.go:49-79；trade-spec §0.6「代码常量」、§5.11 最后一行）。
 * 写死在代码里，不做成配置项。
 *
 * <p>可配的市场参数（范围、缺省页长、页长上限、页码上限、收藏上限）与整请求预算归 {@code xm.trade.*} 配置项（§5.11），不在这里。
 * 按码点（Go rune）计的是展示文本，按字节计的是资源键。
 */
public final class TradeLimits {

    // ---- 输入上限（constants.go:50-68） ----

    /** BrowseListings.search 去空白后的最大码点数（constants.go:52）。 */
    public static final int MAX_SEARCH_RUNES = 64;

    /** 商品标题最大码点数（constants.go:54；标题进 LIKE 搜索、不进索引）。只用于 SeedListing。 */
    public static final int MAX_TITLE_RUNES = 64;

    /** 列表「信息」列摘要最大码点数（constants.go:56）。只用于 SeedListing。 */
    public static final int MAX_SUMMARY_RUNES = 128;

    /** 详情描述最大码点数（constants.go:58）。只用于 SeedListing。 */
    public static final int MAX_DESCRIPTION_RUNES = 512;

    /** 客户端图标资源键最大<b>字节</b>数，字符集 {@code [a-z0-9_]}（constants.go:60）。只用于 SeedListing。 */
    public static final int MAX_ICON_KEY_LEN = 64;

    /** 商品等级上限（constants.go:62）。只用于 SeedListing。 */
    public static final int MAX_LEVEL = 1000;

    /** 单价上限（分），即 1 亿元（constants.go:64）。只用于 SeedListing。 */
    public static final long MAX_PRICE_FEN = 10_000_000_000L;

    /** 公示期上限 30 天（毫秒；constants.go:66）。只用于 SeedListing。 */
    public static final long MAX_NOTICE_DURATION_MS = 30L * 24 * 3_600_000L;

    /** 寄售期上限 90 天（毫秒；constants.go:68）。只用于 SeedListing。 */
    public static final long MAX_SALE_DURATION_MS = 90L * 24 * 3_600_000L;

    // ---- 单次调用上限（constants.go:70-78）：与整请求预算取先到者 ----

    /** <b>单次</b>归属区查询的上限（毫秒；constants.go:71）。实际取 {@code min(它, 请求剩余)}（同 GuildLimits.HOME_ZONE_LOOKUP_TIMEOUT_MS）。 */
    public static final long HOME_ZONE_LOOKUP_TIMEOUT_MS = 1_500L;

    /**
     * <b>单次</b> MySQL 调用的上限（毫秒；constants.go:78）。实际取 {@code min(它, 请求剩余)}；收藏写入整个带重试的调用一共只有这一份
     * （listing_repo.go:302-305 的 {@code bounded(ctx)} 包住整个 WithTxRetry）。
     */
    public static final long STORE_OP_TIMEOUT_MS = 2_000L;

    // ---- 收藏写入的有界重试（listing_repo.go:288-312；asset_op_repo.go:873-895；seq.go:356-425；decide.go:126-156） ----

    /** 收藏写入的总尝试次数（txRetryAttempts，asset_op_repo.go:874）：撞 1213 / 1205 / 9007 时整条重跑一次。 */
    public static final int FAVORITE_WRITE_ATTEMPTS = 2;

    /**
     * 两次尝试之间的退避基数（毫秒；DefaultTxRetryConfig 的 BaseBackoff 10 ms，第一次退避不翻倍）。实际等待带 ±20% 抖动
     * （NextAttemptMs，decide.go:144-155：{@code delay × (0.8 + 0.4 × rnd)}，向下取整），即 8–12 ms；算式在 JdbcListingStore.backoffMillis。
     */
    public static final long FAVORITE_RETRY_BACKOFF_MS = 10L;

    // ---- 连接 ----

    /**
     * 连接级 {@code innodb_lock_wait_timeout}（秒；Q7 已拍板取 2，对齐单次 SQL 上限）。基线不设锁等待，但每次调用被 2000 ms 的 ctx 截断；
     * friend 取 3、guild 取 1。只有收藏写入会等锁。写在连接串的 sessionVariables 里（application.yaml），这里只是单一事实源与测试夹具用。
     */
    public static final int LOCK_WAIT_TIMEOUT_SECONDS = 2;

    private TradeLimits() {
    }
}
