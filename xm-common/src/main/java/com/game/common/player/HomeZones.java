package com.game.common.player;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import java.util.List;
import java.util.Map;

/**
 * 批量查玩家归属区（home zone）。组队、帮会、聚宝斋共用（trade-spec Q6）；基线各服务各有一份同形的接口：
 * go/match/internal/team/homezone.go:20-29 {@code HomeZoneLookup}、go/guild 的 home_zone.go:15-24、
 * go/trade/internal/logic/home_zone.go:13-84 {@code DataServiceHomeZone}，都查 data_service 的 {@code player:zone} 映射。
 * Java 没有 data_service，生产实现 {@link PlayerHomeZones} 读 {@code xm_java.player.zone_id}（team D5、guild D3、trade T3）。
 *
 * <p>为什么要查：区隔离的功能（队伍 zone、帮会 zone、聚宝斋市场分区）只认服务端记下的归属区，客户端请求体里的 zone 一律不信。契约：
 * <ul>
 *   <li>返回 map 缺某个 id（或值为 0）= 没有这名玩家的归属区（数据状态），调用方回各自的「归属区未确认」码
 *       （组队 4019、帮会 14012、聚宝斋 20001）；</li>
 *   <li>抛 {@link DependencyException}（或其他 RuntimeException）= 查询失败（故障：MySQL 不可用、超时、预算用完），调用方按故障处理
 *       （组队 4030、帮会信封 1003、聚宝斋 in-band 1003）；</li>
 *   <li>一次只查少量 id（调用者、目标、审批人与申请人）；实现自带单次超时，请求预算仍能截断它；</li>
 *   <li>阻塞，只在工作线程上调用（不得在 Netty I/O 线程或场景逻辑线程上调用）；线程安全。</li>
 * </ul>
 */
@FunctionalInterface
public interface HomeZones {

    /** @return 玩家 → 归属区（uint32 位模式）；缺项 = 未知 */
    Map<Long, Integer> homeZones(List<Long> playerIds, Deadline deadline);

    /**
     * 查单个玩家的归属区。
     *
     * @return 归属区（uint32 位模式）；0 = 未知（缺项或值为 0，同 home_zone.go:55-58 的「未映射」）
     * @throws RuntimeException 查询失败（同 {@link #homeZones}）
     */
    default int homeZoneOf(long playerId, Deadline deadline) {
        Map<Long, Integer> zones = homeZones(List.of(playerId), deadline);
        Integer zone = zones == null ? null : zones.get(playerId);
        return zone == null ? 0 : zone;
    }
}
