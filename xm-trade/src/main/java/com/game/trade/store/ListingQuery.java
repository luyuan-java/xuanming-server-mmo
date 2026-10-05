package com.game.trade.store;

import java.util.Objects;

/**
 * 浏览列表的查询描述（基线 data.ListingQuery，listing_repo.go:19-42；trade-spec §1.7）。由服务层在完成全部校验后填写；
 * 存储层只负责把它翻译成参数化 SQL，不再做业务判断。
 *
 * <p>整数字段是位模式：{@code marketZone} / {@code subcategory} 是 uint32，{@code searchListingId} / {@code favoritesOf} / {@code nowMs}
 * 是 uint64。{@code tab} / {@code category} / {@code sort} 是契约枚举（ListingTab / ListingCategory / ListingSort）的整数值。
 *
 * @param marketZone       0 = 不按分区过滤（global 且 zone_filter = 0）；否则 {@code market_zone = ?}
 * @param tab              决定状态与时间窗：PUBLIC_NOTICE = 公示中；ON_SALE = 寄售中（含 LOCKED）。其余值存储层拒绝
 * @param category         必填（1..9）；0 存储层拒绝
 * @param subcategory      0 = 全部
 * @param titleLikePattern {@code ""} = 不搜。非空时必须<b>已经</b>用 ListingRules.escapeLike 以 {@code '!'} 为转义符转义、并加好
 *                         {@code %} 通配（ListingRules.likePattern）；SQL 固定写 {@code ESCAPE '!'}
 * @param searchListingId  非 0 时与标题搜索取并集（{@code listing_id = ? OR title LIKE ?}）；只在 titleLikePattern 非空时生效
 * @param favoritesOf      非 0 时只看该玩家收藏过的商品
 * @param sort             从固定映射取 ORDER BY 片段（ListingSql.orderBy），绝不拼用户输入
 * @param nowMs            本次请求唯一一次取到的服务端时间（Unix 毫秒），公示 / 寄售窗口都按它判定
 */
public record ListingQuery(
        int marketZone,
        int tab,
        int category,
        int subcategory,
        String titleLikePattern,
        long searchListingId,
        long favoritesOf,
        int sort,
        long nowMs) {

    public ListingQuery {
        Objects.requireNonNull(titleLikePattern, "titleLikePattern（不搜传空串）");
    }

    /** 最小查询：页签 + 类目 + now；其余条件取零值（不分区、全部子类、不搜、不限收藏、默认排序）。 */
    public static ListingQuery of(int tab, int category, long nowMs) {
        return new ListingQuery(0, tab, category, 0, "", 0, 0, 0, nowMs);
    }

    public ListingQuery withMarketZone(int zone) {
        return new ListingQuery(zone, tab, category, subcategory, titleLikePattern, searchListingId, favoritesOf, sort, nowMs);
    }

    public ListingQuery withTab(int newTab) {
        return new ListingQuery(marketZone, newTab, category, subcategory, titleLikePattern, searchListingId, favoritesOf, sort, nowMs);
    }

    public ListingQuery withSubcategory(int newSubcategory) {
        return new ListingQuery(marketZone, tab, category, newSubcategory, titleLikePattern, searchListingId, favoritesOf, sort, nowMs);
    }

    /** 搜索条件：pattern 已转义并带通配；listingId 为 0 表示不按编号。 */
    public ListingQuery withSearch(String pattern, long listingId) {
        return new ListingQuery(marketZone, tab, category, subcategory, pattern, listingId, favoritesOf, sort, nowMs);
    }

    public ListingQuery withFavoritesOf(long playerId) {
        return new ListingQuery(marketZone, tab, category, subcategory, titleLikePattern, searchListingId, playerId, sort, nowMs);
    }

    public ListingQuery withSort(int newSort) {
        return new ListingQuery(marketZone, tab, category, subcategory, titleLikePattern, searchListingId, favoritesOf, newSort, nowMs);
    }
}
