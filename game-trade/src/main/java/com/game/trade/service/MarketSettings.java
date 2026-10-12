package com.game.trade.service;

import com.game.proto.trade.MarketScope;

/**
 * 服务层看到的市场参数（基线 MarketConf，config.go:189-214；trade-spec §0.6、§5.11）。由 {@code TradeProperties.Market} 校验后转换而来，
 * 这里不再校验——服务单测要能构造启动校验会挡住的值（例如 {@code MARKET_SCOPE_UNSPECIFIED}，走 1003 的 fail-closed 分支）。
 *
 * <p>整数字段是 uint32 位模式（配置校验保证 &gt; 0）。
 *
 * @param scope                 市场范围（ScopeEnum 的结果）：ZONE 按调用者归属区、GLOBAL 全服；UNSPECIFIED 时浏览回 1003、详情 / 收藏判不可见
 * @param defaultPageSize       请求 page_size = 0 时的页长
 * @param maxPageSize           page_size 的上限
 * @param maxPage               页码上限（防深分页：OFFSET ≤ (maxPage − 1) × maxPageSize）；0 = 不封顶（只在测试里出现）
 * @param maxFavoritesPerPlayer 每玩家收藏条数软上限（计数包括已经看不见的收藏，Q8）
 */
public record MarketSettings(MarketScope scope, int defaultPageSize, int maxPageSize, int maxPage, int maxFavoritesPerPlayer) {
}
