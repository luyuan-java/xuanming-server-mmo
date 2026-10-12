package com.game.scenemanager.world;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldChannels;
import com.game.discovery.world.WorldPlanSnapshot;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 规划器一拍的工作底稿：快照的可变副本，P1–P6 依次在上面改，P7 拿它与快照做差得出最小改动集
 * （scene-channels-spec §4.6.2）。只在一次 {@link WorldChannelPlanner#plan} 调用里存在，非线程安全。
 *
 * <p>为什么做差而不是边改边记 op：同一条记录一拍里可能被改多次（孤儿转排空后又因节点死亡被删），做差只写最终结果；
 * 更重要的是「什么都没变就什么都不写」——每次写入都会推进 ver，节点每秒看到 ver 变化就整读一次计划。
 */
final class PlanDraft {

    final PlanInput input;
    final WorldChannelProperties props;
    final List<Integer> worldConfs;
    final Set<Integer> worldConfSet;
    final long nowMs;
    /** scene_id（无符号）→ 当前记录（改动前的 plan_version 原样保留，P7 才统一盖写入后的版本）。 */
    final TreeMap<Long, WorldChannel> channels = new TreeMap<>(Long::compareUnsigned);
    /** 本拍显式改写的期望数（Q）：conf → 最终值。 */
    final Map<Integer, Integer> desiredSet = new HashMap<>();
    /** 本拍删除的期望数字段（X，孤儿 conf）。 */
    final Set<Integer> desiredRemoved = new LinkedHashSet<>();
    /** 本拍设置的冷却（C）：conf → 到期毫秒。 */
    final Map<Integer, Long> cooldownSet = new HashMap<>();
    /** 本拍删除的冷却字段（Y，孤儿 conf）。 */
    final Set<Integer> cooldownRemoved = new LinkedHashSet<>();
    final List<PlanEvent> events = new ArrayList<>();
    final List<String> anomalies = new ArrayList<>();
    final List<WorldChannel> removed = new ArrayList<>();
    final Set<Long> reportedCorrupt = new HashSet<>();
    boolean noLease;
    boolean noNodes;
    /** 发号失败后本拍不再新建任何频道（P5、P6 都看它）。 */
    boolean creationStopped;
    int nodeGonePending;
    int betterHomePending;

    PlanDraft(PlanInput input, WorldChannelProperties props, List<Integer> worldConfs, Set<Integer> worldConfSet) {
        this.input = input;
        this.props = props;
        this.worldConfs = worldConfs;
        this.worldConfSet = worldConfSet;
        this.nowMs = input.snapshot().nowMs();
        this.channels.putAll(input.snapshot().channels());
    }

    WorldPlanSnapshot snapshot() {
        return input.snapshot();
    }

    DirectoryView directory() {
        return input.directory();
    }

    boolean isWorld(int sceneConfigId) {
        return worldConfSet.contains(sceneConfigId);
    }

    /**
     * 某图当前的期望频道数（同基线 DesiredWorldChannelCount，world_autoscale.go:73-99）：本拍改写过的值 → Redis 里 ≥1 的合法值 →
     * 配置种子。Redis 里有字段但不合法（≤0）时用种子、<b>不覆盖</b>（基线 HSETNX 失败后回种子）。
     */
    int effectiveDesired(int sceneConfigId) {
        Integer set = desiredSet.get(sceneConfigId);
        if (set != null) {
            return set;
        }
        Integer stored = snapshot().desired().get(sceneConfigId);
        if (stored != null && stored >= 1) {
            return stored;
        }
        return props.seedFor(sceneConfigId);
    }

    /**
     * 扩缩容 / 排空回滚改写期望数时的基数：{@code max(期望数, 该图当前 ACTIVE 数)}。基线两者相等（补建只补到期望数），基数就是期望数
     * （world_autoscale.go:243、:339-341）；Java per-node 覆盖会让 ACTIVE 数超过期望数（新节点加入时多铺一个，§4.6.4），
     * 这时还按「期望数 ± 1」改写，扩容会落在已有数量之内、一个也补不出来。
     */
    int scalingBase(int sceneConfigId) {
        return Math.max(effectiveDesired(sceneConfigId), active(sceneConfigId).size());
    }

    void setDesired(int sceneConfigId, int count) {
        desiredSet.put(sceneConfigId, count);
    }

    /** 记录在不在冷却中（冷却到期毫秒 &gt; now；本拍刚设的也算）。 */
    boolean inCooldown(int sceneConfigId) {
        Long until = cooldownSet.get(sceneConfigId);
        if (until == null) {
            until = snapshot().cooldownUntilMs().get(sceneConfigId);
        }
        return until != null && until > nowMs;
    }

    void put(WorldChannel channel) {
        channels.put(channel.getSceneId(), channel);
    }

    /** 删记录（排空收尾 / 死节点），并按原因计事件。 */
    void finish(WorldChannel channel, boolean nodeDead) {
        channels.remove(channel.getSceneId());
        removed.add(channel);
        int conf = channel.getSceneConfigId();
        if (channel.getState() == ChannelState.CHANNEL_DRAINING) {
            if (channel.getDrainReason() == DrainReason.DRAIN_SCALE_IN) {
                events.add(new PlanEvent.Autoscale(PlanEvent.AutoscaleAction.SCALE_IN, PlanEvent.AutoscaleOutcome.DRAINED, conf));
            } else if (channel.getDrainReason() == DrainReason.DRAIN_REBALANCE) {
                events.add(new PlanEvent.Migration(PlanEvent.MigrationReason.BETTER_HOME, PlanEvent.MigrationOutcome.DONE, conf));
            }
        } else if (nodeDead && channel.getState() == ChannelState.CHANNEL_ACTIVE) {
            // Java 的 urgent 迁移 = 删死节点上的记录 + 同一次写入里 P5 在活节点上用新号补出容量（§4.8）：计划与收尾同时发生
            events.add(new PlanEvent.Migration(PlanEvent.MigrationReason.NODE_GONE, PlanEvent.MigrationOutcome.PLANNED, conf));
            events.add(new PlanEvent.Migration(PlanEvent.MigrationReason.NODE_GONE, PlanEvent.MigrationOutcome.DONE, conf));
        }
    }

    /** 某图当前的 ACTIVE 记录（scene_id 无符号升序）。 */
    List<WorldChannel> active(int sceneConfigId) {
        List<WorldChannel> out = new ArrayList<>();
        for (WorldChannel channel : channels.values()) {
            if (channel.getState() == ChannelState.CHANNEL_ACTIVE && channel.getSceneConfigId() == sceneConfigId) {
                out.add(channel);
            }
        }
        return out;
    }

    /** 某图 ACTIVE 记录已用的 slot。 */
    Set<Integer> activeSlots(int sceneConfigId) {
        Set<Integer> slots = new HashSet<>();
        for (WorldChannel channel : active(sceneConfigId)) {
            slots.add(channel.getSlot());
        }
        return slots;
    }

    /**
     * 记录能否被本拍改写：三个号非 0、状态是 ACTIVE / DRAINING、slot 在范围内（写入批次会拒绝不合格的记录，整拍失败）。
     * 坏记录（运维手改、旧版本写坏）只告警一次、不碰，死节点清理照常能删它。
     */
    boolean writable(WorldChannel channel) {
        boolean ok = channel.getSceneId() != 0 && channel.getSceneConfigId() != 0 && channel.getNodeId() != 0
                && (channel.getState() == ChannelState.CHANNEL_ACTIVE || channel.getState() == ChannelState.CHANNEL_DRAINING)
                && Integer.compareUnsigned(channel.getSlot(), WorldChannels.MAX_SLOT) <= 0;
        if (!ok && reportedCorrupt.add(channel.getSceneId())) {
            anomalies.add("坏的频道记录（不改写）scene=" + Long.toUnsignedString(channel.getSceneId()) + " " + shortText(channel));
        }
        return ok;
    }

    /** 新建一条 ACTIVE 记录（created_ms = state_since_ms = now，kind = WORLD）。 */
    WorldChannel newActive(long sceneId, int sceneConfigId, int nodeId, int slot) {
        return WorldChannel.newBuilder()
                .setSceneId(sceneId)
                .setSceneConfigId(sceneConfigId)
                .setNodeId(nodeId)
                .setSlot(slot)
                .setState(ChannelState.CHANNEL_ACTIVE)
                .setCreatedMs(nowMs)
                .setStateSinceMs(nowMs)
                .setKind(ChannelKind.CHANNEL_KIND_WORLD)
                .build();
    }

    /** 转 DRAINING（state_since_ms = now）。 */
    WorldChannel draining(WorldChannel channel, DrainReason reason) {
        return channel.toBuilder()
                .setState(ChannelState.CHANNEL_DRAINING)
                .setDrainReason(reason)
                .setStateSinceMs(nowMs)
                .build();
    }

    static String shortText(WorldChannel channel) {
        return "{conf=" + Integer.toUnsignedString(channel.getSceneConfigId())
                + " node=" + Integer.toUnsignedString(channel.getNodeId())
                + " slot=" + Integer.toUnsignedString(channel.getSlot())
                + " state=" + channel.getState()
                + (channel.getState() == ChannelState.CHANNEL_DRAINING ? " reason=" + channel.getDrainReason() : "")
                + " ver=" + Long.toUnsignedString(channel.getPlanVersion()) + "}";
    }
}
