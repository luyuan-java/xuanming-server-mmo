package com.game.scenemanager.world;

import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldChannels;
import com.game.discovery.world.WorldPlanBatch;
import com.game.discovery.world.WorldPlanSnapshot;
import com.game.scenemanager.world.PlanEvent.AutoscaleAction;
import com.game.scenemanager.world.PlanEvent.AutoscaleOutcome;
import com.game.scenemanager.world.PlanResult.ChannelCounts;
import com.game.scenemanager.world.WorldChannelProperties.Coverage;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

/**
 * 频道计划规划器：一拍的纯函数 {@code plan(快照, 目录, 缺席表, 配置, now) → 改动}（scene-channels-spec §4.6.2）。
 * 不碰 Redis，唯一的外部调用是发号（{@link SceneIdSource}）与镜像源查询（{@link MirrorSources}，5.1 恒「否」）。
 *
 * <p>顺序（基线没有单一入口，Java 把散落的几条后台路径收成一拍）：
 * <ol>
 *   <li><b>P1 孤儿 conf</b>（{@code cleanup-orphans}）：World 表里没有的图，其 ACTIVE 记录转 DRAINING(ORPHAN)，期望数 / 冷却字段删除
 *       （基线 orphan_cleanup.go:42-90、:201-204 是直接删整组 + DestroyScene；Java 走排空，节点把在场玩家同节点改派到默认大世界）。</li>
 *   <li><b>P2 死节点</b>：缺席满 {@code dead-node-grace} 的节点，删掉它名下全部记录（D12）；容量由 P5 用新号在活节点补出（D4）。</li>
 *   <li><b>P3 排空推进</b>（与扩缩容开关无关，D19，修 B6）：节点已应用到这条记录的版本、且目录里已没有这个场景 → 删记录（收尾）；
 *       排空满 {@code autoscale.drain-timeout}：缩容排空回滚 ACTIVE 并期望数 +1（D10、Q5，修 B4），其它原因只告警。</li>
 *   <li><b>P4 自动扩缩容</b>（开启且到期）：{@link WorldAutoscaler}。放在补建之前，扩容新增的期望数在同一次写入里就被 P5 实现。</li>
 *   <li><b>P5 补建</b>：每张 World 图（表序）期望数 = Redis 合法值，否则配置种子（HSETNX 播种，不钳上限，同基线 DesiredWorldChannelCount，
 *       world_autoscale.go:73-99）；per-node 模式先让每个活节点至少一个 ACTIVE，再补足到期望数；多出来的不删（同基线 world_init.go:181）。</li>
 *   <li><b>P6 择机迁移</b>（只在 hash 模式）：{@link WorldRebalancePlanner}。</li>
 *   <li><b>P7</b>：与快照做差得出最小改动集。什么都没变就是空批次（不写、不推进 ver）。期望数的播种（N）与过期冷却字段的清理（Y）
 *       不单独触发写入，只搭有实际改动的那次写入的便车——没写进去之前，内存里照样按种子算，语义等同基线「首次访问即播种」。</li>
 * </ol>
 *
 * <p>线程安全：无可变字段，可在任意线程调用（实际只在控制面 tick 线程上用）。
 */
public final class WorldChannelPlanner {

    private final WorldChannelProperties props;
    private final List<Integer> worldConfs;
    private final Set<Integer> worldConfSet;
    private final MirrorSources mirrors;
    private final SceneIdSource ids;

    /**
     * @param worldConfIds World 表的世界地图（scene_config_id），按表序，非空
     * @param mirrors      镜像源查询（5.1 用 {@link MirrorSources#NONE}）
     * @param ids          新频道的 scene_id 来源（生产为发号租约上的 {@code LeaseGatedSnowflake::tryNext}）
     */
    public WorldChannelPlanner(WorldChannelProperties props, List<Integer> worldConfIds, MirrorSources mirrors,
                               SceneIdSource ids) {
        if (worldConfIds.isEmpty()) {
            throw new IllegalArgumentException("World 表没有任何世界地图");
        }
        this.props = props;
        this.worldConfs = List.copyOf(new LinkedHashSet<>(worldConfIds));
        this.worldConfSet = Set.copyOf(worldConfs);
        this.mirrors = mirrors;
        this.ids = ids;
    }

    public List<Integer> worldConfIds() {
        return worldConfs;
    }

    /** 择机迁移前要读预占数的候选（见 {@link WorldRebalancePlanner#probe}）。 */
    public List<Long> rebalanceProbe(WorldPlanSnapshot snapshot, DirectoryView directory, List<Integer> liveNodes) {
        return WorldRebalancePlanner.probe(snapshot, directory, ChannelPlacement.sortNodes(liveNodes), worldConfs);
    }

    public PlanResult plan(PlanInput input) {
        PlanDraft draft = new PlanDraft(input, props, worldConfs, worldConfSet);
        for (WorldChannel channel : draft.channels.values()) {
            draft.writable(channel); // 坏记录先登记告警；之后各步骤都不改写它们
        }
        orphans(draft);
        deadNodes(draft);
        drains(draft);
        if (input.autoscaleDue() && props.autoscale().enabled()) {
            WorldAutoscaler.run(draft, mirrors);
        }
        provision(draft);
        if (props.coverage() == Coverage.HASH) {
            WorldRebalancePlanner.run(draft, mirrors, ids);
        }
        return finish(draft);
    }

    // ---------------------------------------------------------------- P1

    private void orphans(PlanDraft draft) {
        if (!props.cleanupOrphans()) {
            return;
        }
        for (WorldChannel channel : List.copyOf(draft.channels.values())) {
            if (channel.getState() == ChannelState.CHANNEL_ACTIVE && !draft.isWorld(channel.getSceneConfigId())
                    && draft.writable(channel)) {
                draft.put(draft.draining(channel, DrainReason.DRAIN_ORPHAN));
            }
        }
        for (int conf : draft.snapshot().desired().keySet()) {
            if (!draft.isWorld(conf)) {
                draft.desiredRemoved.add(conf);
            }
        }
        for (int conf : draft.snapshot().cooldownUntilMs().keySet()) {
            if (!draft.isWorld(conf)) {
                draft.cooldownRemoved.add(conf);
            }
        }
    }

    // ---------------------------------------------------------------- P2

    private void deadNodes(PlanDraft draft) {
        long graceNanos = props.deadNodeGrace().toNanos();
        for (WorldChannel channel : List.copyOf(draft.channels.values())) {
            Long absent = draft.input.absentNanos().get(channel.getNodeId());
            if (absent == null) {
                continue;
            }
            if (absent >= graceNanos) {
                draft.finish(channel, true);
            } else {
                draft.nodeGonePending++;
            }
        }
    }

    // ---------------------------------------------------------------- P3

    private void drains(PlanDraft draft) {
        long timeoutMs = props.autoscale().drainTimeout().toMillis();
        for (WorldChannel channel : List.copyOf(draft.channels.values())) {
            if (channel.getState() != ChannelState.CHANNEL_DRAINING || !draft.writable(channel)) {
                continue;
            }
            DirectoryView.Node node = draft.directory().node(channel.getNodeId());
            if (node == null) {
                continue; // 节点缺席：宽限期后 P2 删
            }
            // 版本条件保证节点已经看到这次排空，不会把「节点还没来得及建」误当成「已销毁」
            if (Long.compareUnsigned(node.appliedPlanVersion(), channel.getPlanVersion()) >= 0
                    && !node.scenes().containsKey(channel.getSceneId())) {
                draft.finish(channel, false);
                continue;
            }
            if (draft.nowMs - channel.getStateSinceMs() < timeoutMs) {
                continue;
            }
            int conf = channel.getSceneConfigId();
            if (channel.getDrainReason() == DrainReason.DRAIN_SCALE_IN && draft.isWorld(conf)) {
                revert(draft, channel);
            } else {
                draft.anomalies.add("排空超时仍未收尾 scene=" + Long.toUnsignedString(channel.getSceneId()) + " "
                        + PlanDraft.shortText(channel) + "（不可分配，等节点排空或宽限期后清理）");
            }
        }
    }

    /** 缩容排空超时：回滚 ACTIVE、期望数 +1（钳上限）。slot 已被别的 ACTIVE 记录占用时换未用的最小 slot。 */
    private void revert(PlanDraft draft, WorldChannel channel) {
        int conf = channel.getSceneConfigId();
        int slot = channel.getSlot();
        Set<Integer> used = draft.activeSlots(conf);
        if (used.contains(slot)) {
            OptionalInt free = ChannelPlacement.minUnusedSlot(used);
            if (free.isEmpty()) {
                draft.anomalies.add("缩容排空超时但 conf=" + Integer.toUnsignedString(conf) + " 的 slot 已用尽，无法回滚 scene="
                        + Long.toUnsignedString(channel.getSceneId()));
                return;
            }
            slot = free.getAsInt();
        }
        int base = draft.scalingBase(conf);
        draft.put(channel.toBuilder()
                .setState(ChannelState.CHANNEL_ACTIVE)
                .setDrainReason(DrainReason.DRAIN_REASON_UNSPECIFIED)
                .setStateSinceMs(draft.nowMs)
                .setSlot(slot)
                .build());
        draft.setDesired(conf, props.autoscale().clamp(base + 1));
        draft.events.add(new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.REVERTED, conf));
        draft.anomalies.add("缩容排空超时，回滚为 ACTIVE scene=" + Long.toUnsignedString(channel.getSceneId()) + " "
                + PlanDraft.shortText(channel));
    }

    // ---------------------------------------------------------------- P5

    private void provision(PlanDraft draft) {
        List<Integer> live = draft.input.liveNodes();
        if (live.isEmpty()) {
            draft.noNodes = true;
            return;
        }
        for (int conf : worldConfs) {
            if (draft.creationStopped) {
                return;
            }
            provisionConf(draft, conf, draft.effectiveDesired(conf), live);
        }
    }

    private void provisionConf(PlanDraft draft, int conf, int desired, List<Integer> live) {
        List<WorldChannel> active = draft.active(conf);
        Map<Integer, Integer> byNode = new HashMap<>();
        Set<Integer> slots = new HashSet<>();
        for (WorldChannel channel : active) {
            byNode.merge(channel.getNodeId(), 1, Integer::sum);
            slots.add(channel.getSlot());
        }
        int count = active.size();
        if (props.coverage() == Coverage.PER_NODE) {
            // 覆盖：每个活节点对这张图至少一个 ACTIVE（保住「63 按地图换场景永远在本节点完成」，§4.6.4；
            // hash 覆盖下本节点没有该图时由 5.2 的跨节点换图兜住）
            for (int node : live) {
                if (byNode.getOrDefault(node, 0) == 0) {
                    if (!add(draft, conf, node, slots, byNode, live)) {
                        return;
                    }
                    count++;
                }
            }
        }
        while (count < desired) {
            if (!add(draft, conf, -1, slots, byNode, live)) {
                return;
            }
            count++;
        }
    }

    /**
     * 加一个频道：取未用的最小 slot → 选节点（per-node：指定的覆盖节点或 ACTIVE 最少者；hash：落点）→ 发号 → 记录。
     * slot 用尽（ERROR，停止该图）或发号失败（停止本拍全部新建）回 false。
     *
     * @param coverNode per-node 覆盖步骤指定的节点；-1 = 按放置策略选
     */
    private boolean add(PlanDraft draft, int conf, int coverNode, Set<Integer> slots, Map<Integer, Integer> byNode,
                        List<Integer> live) {
        if (draft.creationStopped) {
            return false;
        }
        OptionalInt slot = ChannelPlacement.minUnusedSlot(slots);
        if (slot.isEmpty()) {
            draft.anomalies.add("conf=" + Integer.toUnsignedString(conf) + " 的 slot 0.." + WorldChannels.MAX_SLOT
                    + " 已用尽，停止为它新建频道");
            return false;
        }
        int node;
        if (coverNode != -1) {
            node = coverNode;
        } else if (props.coverage() == Coverage.PER_NODE) {
            node = ChannelPlacement.leastLoadedNode(live, byNode);
        } else {
            node = ChannelPlacement.hashTarget(conf, slot.getAsInt(), live);
        }
        OptionalLong id = ids.tryNext();
        if (id.isEmpty()) {
            draft.noLease = true;
            draft.creationStopped = true;
            return false;
        }
        draft.put(draft.newActive(id.getAsLong(), conf, node, slot.getAsInt()));
        slots.add(slot.getAsInt());
        byNode.merge(node, 1, Integer::sum);
        return true;
    }

    // ---------------------------------------------------------------- P7

    private PlanResult finish(PlanDraft draft) {
        WorldPlanSnapshot snapshot = draft.snapshot();
        WorldPlanBatch batch = WorldPlanBatch.forSnapshot(snapshot);
        long written = batch.writtenVersion();
        TreeMap<Long, WorldChannel> finalChannels = new TreeMap<>(Long::compareUnsigned);
        boolean changed = false;
        for (Map.Entry<Long, WorldChannel> e : draft.channels.entrySet()) {
            WorldChannel original = snapshot.channels().get(e.getKey());
            if (original != null && original.equals(e.getValue())) {
                finalChannels.put(e.getKey(), original);
                continue;
            }
            WorldChannel stamped = e.getValue().toBuilder().setPlanVersion(written).build();
            batch.putChannel(stamped);
            finalChannels.put(e.getKey(), stamped);
            changed = true;
        }
        for (long sceneId : snapshot.channels().keySet()) {
            if (!draft.channels.containsKey(sceneId)) {
                batch.removeChannel(sceneId);
                changed = true;
            }
        }
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(draft.desiredSet).entrySet()) {
            if (!Objects.equals(snapshot.desired().get(e.getKey()), e.getValue())) {
                batch.setDesired(e.getKey(), e.getValue());
                changed = true;
            }
        }
        for (int conf : draft.desiredRemoved) {
            batch.removeDesired(conf);
            changed = true;
        }
        for (Map.Entry<Integer, Long> e : new TreeMap<>(draft.cooldownSet).entrySet()) {
            batch.setCooldown(e.getKey(), e.getValue());
            changed = true;
        }
        for (int conf : draft.cooldownRemoved) {
            batch.removeCooldown(conf);
            changed = true;
        }
        if (changed) {
            // 便车：播种（HSETNX，已有值不覆盖）与过期冷却清理
            for (int conf : worldConfs) {
                if (!draft.desiredSet.containsKey(conf) && !snapshot.desired().containsKey(conf)) {
                    batch.seedDesired(conf, props.seedFor(conf));
                }
            }
            for (Map.Entry<Integer, Long> e : snapshot.cooldownUntilMs().entrySet()) {
                int conf = e.getKey();
                if (e.getValue() <= draft.nowMs && !draft.cooldownSet.containsKey(conf) && !draft.cooldownRemoved.contains(conf)) {
                    batch.removeCooldown(conf);
                }
            }
        } else {
            batch = WorldPlanBatch.forSnapshot(snapshot);
        }
        return new PlanResult(batch, finalChannels, draft.noLease, draft.noNodes, draft.events,
                counts(draft, finalChannels), draft.nodeGonePending, draft.betterHomePending, draft.removed, draft.anomalies);
    }

    /** 各图频道数（{@code xm_scene_manager_world_channels}）：World 表之外的图归到 {@link PlanResult#OTHER_CONFIG}。 */
    private Map<Integer, ChannelCounts> counts(PlanDraft draft, Map<Long, WorldChannel> channels) {
        Map<Integer, int[]> raw = new HashMap<>();
        for (int conf : worldConfs) {
            raw.put(conf, new int[3]);
        }
        raw.put(PlanResult.OTHER_CONFIG, new int[3]);
        for (WorldChannel channel : channels.values()) {
            int key = draft.isWorld(channel.getSceneConfigId()) ? channel.getSceneConfigId() : PlanResult.OTHER_CONFIG;
            int[] c = raw.get(key);
            if (channel.getState() == ChannelState.CHANNEL_ACTIVE) {
                c[0]++;
                DirectoryView.Node node = draft.directory().node(channel.getNodeId());
                if (node != null && Long.compareUnsigned(node.appliedPlanVersion(), channel.getPlanVersion()) >= 0
                        && !node.scenes().containsKey(channel.getSceneId())) {
                    c[2]++;
                }
            } else if (channel.getState() == ChannelState.CHANNEL_DRAINING) {
                c[1]++;
            }
        }
        Map<Integer, ChannelCounts> out = new HashMap<>();
        raw.forEach((conf, c) -> out.put(conf, new ChannelCounts(c[0], c[1], c[2])));
        return out;
    }
}
