package com.game.guild.zone;

import com.game.common.deadline.Deadline;
import java.util.List;
import java.util.Map;

/**
 * 批量查玩家归属区（基线 HomeZoneLookup，home_zone.go:15-24；guild-spec §1.13、§7.10，D3）。
 *
 * <p>帮会按 zone 隔离：客户端请求的 zone 只认归属区，请求体里的 zone_id 一律不信。契约：
 * <ul>
 *   <li>返回 map 缺某个 id（或值为 0）= 没有这名玩家的归属区（数据状态）：调用方回 14012（申请人则 14018）；</li>
 *   <li>抛 {@link Deadline.DependencyException}（或其他 RuntimeException）= 查询失败（故障），调用方回信封 1003；</li>
 *   <li>一次至多 2 个 id（审批通过时审批人与申请人合成一条 IN 查询，§7.10）；实现自带超时，请求预算仍能截断它；</li>
 *   <li>阻塞，只在工作线程上调用；线程安全。</li>
 * </ul>
 */
@FunctionalInterface
public interface HomeZones {

    /** @return 玩家 → 归属区（uint32 位模式）；缺项 = 未知 */
    Map<Long, Integer> homeZones(List<Long> playerIds, Deadline deadline);
}
