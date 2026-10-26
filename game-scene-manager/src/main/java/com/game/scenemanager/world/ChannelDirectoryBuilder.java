package com.game.scenemanager.world;

import com.game.api.ChannelKinds;
import com.game.api.proto.ChannelState;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.WorldChannel;
import com.game.proto.SceneChannelDirectory;
import com.game.proto.SceneChannelInfo;
import com.game.proto.SceneChannelState;
import com.game.scenemanager.SceneAssigner;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 只按已持久化计划和一次节点目录快照构建展示目录，不预占也不改计划。 */
public final class ChannelDirectoryBuilder {
    private ChannelDirectoryBuilder() {}

    public static SceneChannelDirectory build(int zoneId, int configId, List<WorldChannel> channels,
                                              List<SceneNodeInfo> nodes, ChannelSwitchProperties props,
                                              WorldChannelProperties world, long nowMs) {
        long max = props.effectiveMaxPlayers(world);
        var result = SceneChannelDirectory.newBuilder().setZoneId(zoneId).setSceneConfigId(configId)
                .setUpdatedAtMs(nowMs).setSwitchEnabled(!props.disabled())
                .setSwitchCooldownSeconds(props.effectiveCooldownSeconds()).setMaxPlayersPerChannel((int) max);
        Set<Integer> numbers = new HashSet<>();
        List<WorldChannel> ordered = channels.stream().filter(c -> c.getSceneConfigId() == configId)
                .filter(c -> ChannelKinds.isWorldChannel(c.getKind()))
                .sorted((a, b) -> Integer.compareUnsigned(a.getChannelNo(), b.getChannelNo())).toList();
        for (WorldChannel channel : ordered) {
            if (channel.getChannelNo() == 0 || !numbers.add(channel.getChannelNo())) {
                throw new IllegalStateException("计划线号尚未修复，不发布虚假目录");
            }
            SceneEntry entry = uniqueEntry(zoneId, channel, nodes);
            long count = entry == null ? 0 : Integer.toUnsignedLong(entry.getPlayerCount());
            SceneChannelState state;
            if (channel.getState() == ChannelState.CHANNEL_DRAINING || entry != null && entry.getDraining()) {
                state = SceneChannelState.SCENE_CHANNEL_STATE_CLOSING;
            } else if (channel.getState() != ChannelState.CHANNEL_ACTIVE || entry == null) {
                state = SceneChannelState.SCENE_CHANNEL_STATE_UNAVAILABLE;
            } else if (count >= max) {
                state = SceneChannelState.SCENE_CHANNEL_STATE_FULL;
            } else if (count * 100 >= max * props.effectiveBusyPercent()) {
                state = SceneChannelState.SCENE_CHANNEL_STATE_BUSY;
            } else {
                state = SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH;
            }
            result.addChannels(SceneChannelInfo.newBuilder().setSceneId(channel.getSceneId())
                    .setChannelNo(channel.getChannelNo()).setPlayerCount((int) count).setState(state));
        }
        return result.build();
    }

    /** 计划的承载节点必须唯一且确实上报该世界实例；重复节点/场景身份一律不可用。 */
    public static SceneEntry uniqueEntry(int zoneId, WorldChannel channel, List<SceneNodeInfo> nodes) {
        SceneEntry found = null;
        int nodeHolders = 0;
        int sceneHolders = 0;
        for (SceneNodeInfo node : nodes) {
            if (!SceneAssigner.isUsable(node, zoneId)) continue;
            if (node.getNodeId() == channel.getNodeId()) nodeHolders++;
            for (SceneEntry scene : node.getScenesList()) {
                if (scene.getSceneId() != channel.getSceneId()) continue;
                sceneHolders++;
                if (node.getNodeId() == channel.getNodeId() && scene.getSceneConfigId() == channel.getSceneConfigId()
                        && ChannelKinds.isWorldChannel(scene.getKind())) found = scene;
            }
        }
        return nodeHolders == 1 && sceneHolders == 1 ? found : null;
    }
}
