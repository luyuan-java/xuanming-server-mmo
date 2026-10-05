package com.game.scenemanager.world;

import com.game.discovery.world.WorldPlanSnapshot;
import java.util.List;
import java.util.Map;

/**
 * 规划器一拍的输入（scene-channels-spec §4.6.1）。全部由协调者在 tick 线程上读好、算好，规划器不碰 Redis。
 *
 * @param zoneId            zone
 * @param snapshot          计划快照（版本号、频道表、期望数、冷却、Redis TIME）
 * @param directory         目录（只含可用节点）
 * @param absentNanos       计划引用了但目录缺席的节点 → 已缺席多久（单调时钟纳秒，{@link AbsenceTracker}）
 * @param liveNodes         活节点：目录在场且 {@link NodeAvailability} 放行，按数值无符号升序
 * @param autoscaleDue      本拍要跑扩缩容决策（开启且距上次满 {@code check-interval}）
 * @param rebalanceDue      本拍要跑择机迁移（{@code coverage=hash}、预算 &gt; 0，且活节点集合变化或距上次满 {@code rebalance.interval}）
 * @param reservationCounts 择机迁移候选的未到期预占数（scene_id → 条数，{@link WorldRebalancePlanner#probe} 的结果去 Redis 读回）；
 *                          缺一个就不迁那一个（fail-closed）
 */
public record PlanInput(int zoneId, WorldPlanSnapshot snapshot, DirectoryView directory, Map<Integer, Long> absentNanos,
                        List<Integer> liveNodes, boolean autoscaleDue, boolean rebalanceDue,
                        Map<Long, Long> reservationCounts) {

    public PlanInput {
        absentNanos = Map.copyOf(absentNanos);
        liveNodes = ChannelPlacement.sortNodes(liveNodes);
        reservationCounts = Map.copyOf(reservationCounts);
    }
}
