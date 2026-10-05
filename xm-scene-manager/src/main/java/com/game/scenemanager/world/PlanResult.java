package com.game.scenemanager.world;

import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldPlanBatch;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 规划器一拍的输出（scene-channels-spec §4.6.2 P7）。
 *
 * @param batch             要写入的改动（期望版本 = 快照版本）；空 = 本拍什么也不用写（不推进 ver，节点不必重读）
 * @param channels          写入成功后的整张频道表（改写过的记录 plan_version 已是写入后的版本号），按 scene_id 无符号升序
 * @param noLease           需要新建频道但发号租约无效：本拍停止了全部新建（{@code world_ticks_total{result=no_lease}}）
 * @param noNodes           没有活节点：补建整体跳过（{@code result=no_nodes}）
 * @param events            要计入指标的决定（只在写入成功或无需写入时计）
 * @param counts            写入成功后各图的频道数（{@code xm_scene_manager_world_channels}）；键是 scene_config_id，
 *                          World 表之外的图归到 {@link #OTHER_CONFIG}
 * @param nodeGonePending   宽限期内缺席节点上的记录数（{@code rebalance_pending{reason=node_gone}}）
 * @param betterHomePending 落点不对、可择机迁移但本拍没迁的空频道数（{@code rebalance_pending{reason=better_home}}；per-node 模式恒 0）
 * @param removed           本拍删掉的记录（排空收尾、死节点）。只用于日志与测试：5.3 的镜像级联<b>不</b>接在这里——实例由承载节点自有，
 *                          源频道在节点上被销毁时由节点本地级联（dungeon-mirror-spec §6.11、D11），scene-manager 对实例零状态
 * @param anomalies         需要运维关注的情况（排空超时不回滚、slot 用尽、到达频道上限、坏记录…），协调者按 zone 去重后告警
 */
public record PlanResult(WorldPlanBatch batch, Map<Long, WorldChannel> channels, boolean noLease, boolean noNodes,
                         List<PlanEvent> events, Map<Integer, ChannelCounts> counts, int nodeGonePending,
                         int betterHomePending, List<WorldChannel> removed, List<String> anomalies) {

    /** {@link #counts} 里 World 表之外的图（孤儿 conf）汇总到的键。 */
    public static final int OTHER_CONFIG = 0;

    public PlanResult {
        channels = Collections.unmodifiableMap(channels);
        events = List.copyOf(events);
        counts = Map.copyOf(counts);
        removed = List.copyOf(removed);
        anomalies = List.copyOf(anomalies);
    }

    /**
     * 一张图的频道数。
     *
     * @param active   ACTIVE 记录数
     * @param draining DRAINING 记录数
     * @param missing  ACTIVE、节点在场、节点已应用到这条记录的版本、但目录里没有这个场景（节点拒建，如双方 World 表不一致，§4.14）
     */
    public record ChannelCounts(int active, int draining, int missing) {
    }
}
