package com.game.scenemanager.world;

/**
 * 规划器一拍里做出的、要计入指标的决定（scene-channels-spec §6.2）。规划是纯函数：事件随 {@link PlanResult} 交回，
 * 协调者只在这一拍的写入<b>成功</b>（或本拍无需写入）时才计数——写入被围栏 / 冲突时决定作废，下拍重算，不重复计。
 */
public sealed interface PlanEvent {

    /** {@code xm_scene_manager_world_autoscale_total{action}}。 */
    enum AutoscaleAction { SCALE_OUT, SCALE_IN }

    /** {@code xm_scene_manager_world_autoscale_total{outcome}}（基线 ok / drained / error / max_reached，Java 加 reverted / no_victim）。 */
    enum AutoscaleOutcome { OK, DRAINED, REVERTED, MAX_REACHED, NO_VICTIM, ERROR }

    /** {@code xm_scene_manager_rebalance_migrations_total{reason}}（基线 node_gone / better_home）。 */
    enum MigrationReason { NODE_GONE, BETTER_HOME }

    /** {@code xm_scene_manager_rebalance_migrations_total{outcome}}：planned = 写进计划，done = 旧记录收尾。 */
    enum MigrationOutcome { PLANNED, DONE }

    /** 一次扩缩容决定（{@code sceneConfigId} 只用于日志，不作指标标签之外的维度）。 */
    record Autoscale(AutoscaleAction action, AutoscaleOutcome outcome, int sceneConfigId) implements PlanEvent {
    }

    /** 一次迁移进展。 */
    record Migration(MigrationReason reason, MigrationOutcome outcome, int sceneConfigId) implements PlanEvent {
    }
}
