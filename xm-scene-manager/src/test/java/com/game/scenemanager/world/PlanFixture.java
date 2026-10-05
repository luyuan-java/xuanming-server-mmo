package com.game.scenemanager.world;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldPlanOp;
import com.game.discovery.world.WorldPlanSnapshot;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * 规划器单测的输入搭建：快照（版本、Redis TIME、频道、期望数、冷却）+ 目录 + 缺席表 + 配置 + 发号。
 * 发号从 {@link #nextId} 递增；{@link #leaseValid} 为假时发号为空。
 */
final class PlanFixture {

    static final long NOW = 1_800_000_000_000L;

    WorldChannelProperties props = WorldChannelProperties.defaults();
    List<Integer> confs = List.of(1, 2);
    long version;
    long now = NOW;
    final TreeMap<Long, WorldChannel> channels = new TreeMap<>(Long::compareUnsigned);
    final Map<Integer, Integer> desired = new HashMap<>();
    final Map<Integer, Long> cooldown = new HashMap<>();
    final Map<Integer, DirectoryView.Node> nodes = new HashMap<>();
    final Map<Integer, Long> absentNanos = new HashMap<>();
    final Map<Long, Long> reservations = new HashMap<>();
    /** null = 目录在场的全部节点。 */
    List<Integer> live;
    boolean autoscaleDue;
    boolean rebalanceDue;
    MirrorSources mirrors = MirrorSources.NONE;
    long nextId = 1000;
    boolean leaseValid = true;

    // ---------------------------------------------------------------- 搭建

    PlanFixture props(WorldChannelProperties props) {
        this.props = props;
        return this;
    }

    PlanFixture confs(Integer... confs) {
        this.confs = List.of(confs);
        return this;
    }

    PlanFixture desired(int conf, int count) {
        desired.put(conf, count);
        return this;
    }

    /** 每张图期望数 = 当前已有的 ACTIVE 数（避免补建干扰扩缩容用例）。 */
    PlanFixture desiredMatchesActive() {
        for (int conf : confs) {
            int n = (int) channels.values().stream()
                    .filter(c -> c.getSceneConfigId() == conf && c.getState() == ChannelState.CHANNEL_ACTIVE).count();
            desired.put(conf, Math.max(1, n));
        }
        return this;
    }

    /** 计划里加一条 ACTIVE 记录（plan_version = 当前快照版本）。 */
    PlanFixture active(long sceneId, int conf, int node, int slot) {
        channels.put(sceneId, channel(sceneId, conf, node, slot, ChannelState.CHANNEL_ACTIVE, DrainReason.DRAIN_REASON_UNSPECIFIED,
                version, now));
        return this;
    }

    PlanFixture draining(long sceneId, int conf, int node, int slot, DrainReason reason, long planVersion, long sinceMs) {
        channels.put(sceneId, channel(sceneId, conf, node, slot, ChannelState.CHANNEL_DRAINING, reason, planVersion, sinceMs));
        return this;
    }

    /** 目录里加一个节点（已应用版本 {@code applied}），场景用 {@link #scene} 造。 */
    PlanFixture node(int nodeId, long applied, DirectoryView.Scene... scenes) {
        Map<Long, DirectoryView.Scene> map = new HashMap<>();
        for (DirectoryView.Scene s : scenes) {
            map.put(s.sceneId(), s);
        }
        nodes.put(nodeId, new DirectoryView.Node(nodeId, "inst-" + nodeId, applied, map));
        return this;
    }

    /** 目录里的节点，承载计划里属于它的全部记录（人数 0、未排空），已应用当前版本。 */
    PlanFixture nodeHostingPlan(int nodeId) {
        List<DirectoryView.Scene> scenes = new ArrayList<>();
        for (WorldChannel c : channels.values()) {
            if (c.getNodeId() == nodeId) {
                scenes.add(scene(c.getSceneId(), c.getSceneConfigId(), 0));
            }
        }
        return node(nodeId, version, scenes.toArray(DirectoryView.Scene[]::new));
    }

    PlanFixture absent(int nodeId, Duration forHowLong) {
        absentNanos.put(nodeId, forHowLong.toNanos());
        return this;
    }

    static DirectoryView.Scene scene(long sceneId, int conf, long players) {
        return new DirectoryView.Scene(sceneId, conf, players, false);
    }

    static DirectoryView.Scene drainingScene(long sceneId, int conf, long players) {
        return new DirectoryView.Scene(sceneId, conf, players, true);
    }

    static WorldChannel channel(long sceneId, int conf, int node, int slot, ChannelState state, DrainReason reason,
                                long planVersion, long sinceMs) {
        return WorldChannel.newBuilder()
                .setSceneId(sceneId)
                .setSceneConfigId(conf)
                .setNodeId(node)
                .setSlot(slot)
                .setState(state)
                .setDrainReason(reason)
                .setCreatedMs(sinceMs)
                .setStateSinceMs(sinceMs)
                .setPlanVersion(planVersion)
                .setKind(ChannelKind.CHANNEL_KIND_WORLD)
                .build();
    }

    // ---------------------------------------------------------------- 执行

    WorldChannelPlanner planner() {
        return new WorldChannelPlanner(props, confs, mirrors, () -> leaseValid ? OptionalLong.of(nextId++) : OptionalLong.empty());
    }

    PlanInput input() {
        WorldPlanSnapshot snapshot = new WorldPlanSnapshot(version, now, channels, desired, cooldown);
        List<Integer> liveNodes = live != null ? live : new ArrayList<>(nodes.keySet());
        return new PlanInput(1, snapshot, new DirectoryView(nodes), absentNanos, liveNodes, autoscaleDue, rebalanceDue,
                reservations);
    }

    PlanResult plan() {
        return planner().plan(input());
    }

    /** 把一次规划结果「写进」本夹具（模拟写入成功），版本 +1。 */
    PlanFixture apply(PlanResult result) {
        if (result.batch().isEmpty()) {
            return this;
        }
        for (WorldPlanOp op : result.batch().ops()) {
            switch (op) {
                case WorldPlanOp.PutChannel put -> channels.put(put.channel().getSceneId(), put.channel());
                case WorldPlanOp.RemoveChannel remove -> channels.remove(remove.sceneId());
                case WorldPlanOp.SetDesired set -> desired.put(set.sceneConfigId(), set.count());
                case WorldPlanOp.SeedDesired seed -> desired.putIfAbsent(seed.sceneConfigId(), seed.count());
                case WorldPlanOp.RemoveDesired remove -> desired.remove(remove.sceneConfigId());
                case WorldPlanOp.SetCooldown set -> cooldown.put(set.sceneConfigId(), set.untilMs());
                case WorldPlanOp.RemoveCooldown remove -> cooldown.remove(remove.sceneConfigId());
            }
        }
        version = result.batch().writtenVersion();
        return this;
    }

    // ---------------------------------------------------------------- 结果断言辅助

    static List<WorldChannel> puts(PlanResult result) {
        List<WorldChannel> out = new ArrayList<>();
        for (WorldPlanOp op : result.batch().ops()) {
            if (op instanceof WorldPlanOp.PutChannel put) {
                out.add(put.channel());
            }
        }
        return out;
    }

    static List<Long> removes(PlanResult result) {
        List<Long> out = new ArrayList<>();
        for (WorldPlanOp op : result.batch().ops()) {
            if (op instanceof WorldPlanOp.RemoveChannel remove) {
                out.add(remove.sceneId());
            }
        }
        return out;
    }

    static <T extends WorldPlanOp> List<T> ops(PlanResult result, Class<T> type) {
        List<T> out = new ArrayList<>();
        for (WorldPlanOp op : result.batch().ops()) {
            if (type.isInstance(op)) {
                out.add(type.cast(op));
            }
        }
        return out;
    }

    /** 新建的（快照里没有的）ACTIVE 记录。 */
    List<WorldChannel> created(PlanResult result) {
        return puts(result).stream().filter(c -> !channels.containsKey(c.getSceneId())).toList();
    }

    static WorldChannelProperties props(Map<String, Object> overrides) {
        return WorldChannelPropertiesTest.bind(overrides);
    }
}
