package com.game.trade.service;

import com.game.common.deadline.Deadline;
import com.game.common.player.HomeZones;
import com.game.trade.metrics.TradeMetrics;
import com.game.trade.metrics.TradeMetrics.LookupResult;
import com.game.trade.rules.TradeTip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 查一名玩家的归属区并翻译成聚宝斋的结局（基线 resolveHomeZone，jubaozhai_logic.go:360-377 + DataServiceHomeZone.HomeZone 的计数，
 * home_zone.go:46-61；trade-spec §2.6、§5.6）：
 * <ul>
 *   <li>查到非 0 → 归属区，计 ok；</li>
 *   <li>缺行或 zone_id = 0 → {@code unknownTip}（20001，INFO 日志），计 unmapped；</li>
 *   <li>任何 RuntimeException（{@code DependencyException}：读失败、超时、预算用完）→ {@link TradeTip#HOME_ZONE_FAULT}（in-band 1003，ERROR 日志），
 *       计 error。注意是 in-band，不是 guild 那种信封：trade 基线这里本来就是 in-band（T3）。</li>
 * </ul>
 * player_id = 0 不查、按未映射（home_zone.go:66-75 过滤 0 之后为空就不发 RPC）；Java 的派发层与播种校验已先挡掉 0，这里只是同形兜底。
 * 基线「lookup 没接线 → 1003」在 Java 是装配错误（Spring 起不来），没有对应分支（§5.6）。
 *
 * <p>阻塞（JDBC），只在 trade-worker 线程或播种接口的 Tomcat 线程上调用；线程安全。
 */
final class HomeZoneResolver {

    private static final Logger log = LoggerFactory.getLogger(HomeZoneResolver.class);

    /** 查询结局：{@code reject == null} 时 {@code zone} 是归属区（uint32 位模式，非 0）。 */
    record Resolution(int zone, TradeTip reject) {

        boolean rejected() {
            return reject != null;
        }
    }

    private final HomeZones lookup;
    private final TradeMetrics metrics;

    HomeZoneResolver(HomeZones lookup, TradeMetrics metrics) {
        this.lookup = lookup;
        this.metrics = metrics;
    }

    /**
     * @param method     发起查询的方法（只进日志）
     * @param unknownTip 未映射时的 tip（调用者 {@link TradeTip#HOME_ZONE_UNKNOWN} / 种子卖家 {@link TradeTip#SELLER_HOME_ZONE_UNKNOWN}，都是 20001）
     */
    Resolution resolve(long playerId, Deadline deadline, String method, TradeTip unknownTip) {
        if (playerId == 0) {
            metrics.homeZoneLookup(LookupResult.UNMAPPED);
            log.info("[trade] {}: player_id 为 0，按没有 home_zone 处理", method);
            return new Resolution(0, unknownTip);
        }
        int zone;
        try {
            zone = lookup.homeZoneOf(playerId, deadline);
        } catch (RuntimeException e) {
            metrics.homeZoneLookup(LookupResult.ERROR);
            log.error("[trade] {}: 查询 home_zone 失败 player={}", method, Long.toUnsignedString(playerId), e);
            return new Resolution(0, TradeTip.HOME_ZONE_FAULT);
        }
        if (zone == 0) {
            metrics.homeZoneLookup(LookupResult.UNMAPPED);
            log.info("[trade] {}: player={} 没有 home_zone（player 行缺失或 zone_id 为 0）", method, Long.toUnsignedString(playerId));
            return new Resolution(0, unknownTip);
        }
        metrics.homeZoneLookup(LookupResult.OK);
        return new Resolution(zone, null);
    }
}
