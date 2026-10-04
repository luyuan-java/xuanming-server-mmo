package com.game.team.homezone;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import java.util.List;
import java.util.Map;

/**
 * 批量查玩家 home zone（基线 go/match/internal/team/homezone.go:20-29 HomeZoneLookup，team-spec §3.2 / §6.7）。
 *
 * <p>为什么要查：队伍 zone 与成员 zone 必须写进记录（跨区校验靠它），而客户端请求里没有、也不能信任 zone。查不到一律 fail-closed：
 * 不论 allow-cross-zone 怎么配，zone=0 都不许写进记录。
 *
 * <p>契约：
 * <ul>
 *   <li>返回 map 缺某个 id（或值为 0）= 没有这名玩家的 home zone（数据状态），调用方回 4019；</li>
 *   <li>抛 {@link DependencyException}（或其他 RuntimeException）= 查询失败（故障：MySQL 不可用、超时、预算用完），调用方回 4030；</li>
 *   <li>一次最多 2 个 id（组队只查调用者或目标）；实现自带超时，请求预算仍能截断它；</li>
 *   <li>阻塞，只在工作线程上调用；线程安全。</li>
 * </ul>
 */
@FunctionalInterface
public interface HomeZones {

    /** @return 玩家 → home zone（uint32 位模式）；缺项 = 未知 */
    Map<Long, Integer> homeZones(List<Long> playerIds, Deadline deadline);
}
