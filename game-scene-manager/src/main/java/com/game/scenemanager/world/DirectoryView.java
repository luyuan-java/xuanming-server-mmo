package com.game.scenemanager.world;

import com.game.api.ChannelKinds;
import com.game.api.proto.ChannelKind;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.scenemanager.SceneAssigner;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 领导者一拍看到的 scene 节点目录（scene-channels-spec §4.6.1）：节点对「实际建出了什么」的报告。
 * 只收可用节点（判法同分配，{@link SceneAssigner#isUsable}）；不可变，按节点号 / 场景号无符号升序迭代。
 *
 * <p>批次 5.3 起场景条目带种类与镜像源（dungeon-mirror-spec §6.5）：镜像 / 副本实例是节点自有的，不在计划里；规划器只按计划记录的
 * scene_id 查目录（{@code scenes().get(channel.getSceneId())}），实例的号与计划不相交，所以各步骤不需要再按种类过滤。
 * 「谁是镜像源」由 {@link DirectoryMirrorSources#of} 从这里推导。
 *
 * @param nodes 节点号 → 节点
 */
public record DirectoryView(Map<Integer, Node> nodes) {

    public DirectoryView {
        TreeMap<Integer, Node> sorted = new TreeMap<>(Integer::compareUnsigned);
        sorted.putAll(nodes);
        nodes = Collections.unmodifiableSortedMap(sorted);
    }

    /**
     * 一个节点的目录条目。
     *
     * @param nodeId             节点号
     * @param instanceId         进程实例 id
     * @param appliedPlanVersion 节点已应用的计划版本（0 = 还没应用过 / 旧版本节点）
     * @param scenes             场景号 → 场景（无符号升序）
     */
    public record Node(int nodeId, String instanceId, long appliedPlanVersion, Map<Long, Scene> scenes) {

        public Node {
            TreeMap<Long, Scene> sorted = new TreeMap<>(Long::compareUnsigned);
            sorted.putAll(scenes);
            scenes = Collections.unmodifiableSortedMap(sorted);
        }

        /** 节点上报里有这个场景、且没在排空（「已建出且可分配」）。 */
        public boolean hostsActive(long sceneId) {
            Scene scene = scenes.get(sceneId);
            return scene != null && !scene.draining();
        }
    }

    /**
     * 节点上报的一个场景。
     *
     * @param sceneId       场景号
     * @param sceneConfigId 场景配置号
     * @param players       在场人数（uint32 → 非负 long）
     * @param draining      节点上它在排空中（实例：回收宽限 / 级联排空 / 显式销毁中）
     * @param kind          种类（批次 5.3；UNSPECIFIED = 旧版本节点，按主世界读，见 {@link #isWorldChannel()}）
     * @param sourceSceneId 镜像的源频道（只有 MIRROR 非 0）
     */
    public record Scene(long sceneId, int sceneConfigId, long players, boolean draining, ChannelKind kind,
                        long sourceSceneId) {

        public Scene {
            Objects.requireNonNull(kind, "kind");
        }

        /** 主世界频道（5.1 / 5.2 的写法，单测用）。 */
        public Scene(long sceneId, int sceneConfigId, long players, boolean draining) {
            this(sceneId, sceneConfigId, players, draining, ChannelKind.CHANNEL_KIND_WORLD, 0);
        }

        /** 是不是主世界频道（{@link ChannelKinds#isWorldChannel}）；镜像 / 副本实例与不认识的种类为 false。 */
        public boolean isWorldChannel() {
            return ChannelKinds.isWorldChannel(kind);
        }
    }

    public static DirectoryView empty() {
        return new DirectoryView(Map.of());
    }

    /** 从目录条目构建：跳过不可用的条目（节点号 0、串 zone、链路地址不全）与场景号 0 的场景。 */
    public static DirectoryView of(int zoneId, List<SceneNodeInfo> entries) {
        Map<Integer, Node> nodes = new TreeMap<>(Integer::compareUnsigned);
        for (SceneNodeInfo info : entries) {
            if (!SceneAssigner.isUsable(info, zoneId)) {
                continue;
            }
            Map<Long, Scene> scenes = new TreeMap<>(Long::compareUnsigned);
            for (SceneEntry entry : info.getScenesList()) {
                if (entry.getSceneId() == 0) {
                    continue;
                }
                scenes.put(entry.getSceneId(), new Scene(entry.getSceneId(), entry.getSceneConfigId(),
                        Integer.toUnsignedLong(entry.getPlayerCount()), entry.getDraining(), entry.getKind(),
                        entry.getSourceSceneId()));
            }
            nodes.put(info.getNodeId(), new Node(info.getNodeId(), info.getInstanceId(), info.getAppliedPlanVersion(), scenes));
        }
        return new DirectoryView(nodes);
    }

    public boolean isPresent(int nodeId) {
        return nodes.containsKey(nodeId);
    }

    public Node node(int nodeId) {
        return nodes.get(nodeId);
    }
}
