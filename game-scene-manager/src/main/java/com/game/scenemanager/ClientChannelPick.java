package com.game.scenemanager;

import com.game.api.ChannelKinds;
import com.game.api.proto.ChannelState;
import com.game.api.proto.ChannelKind;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.discovery.world.ChannelSwitchCooldown;
import com.game.discovery.world.WorldChannelStore;
import com.game.scenemanager.SwitchTargetSelector.Result;
import com.game.scenemanager.world.ChannelDirectoryBuilder;
import com.game.scenemanager.world.ChannelSwitchProperties;
import com.game.scenemanager.world.WorldChannelProperties;
import java.util.List;
import java.util.Objects;

/** 手动选线的纯读预检；读模型不参与判定，读取计划权威与当前节点人数，绝不抢冷却或预占。 */
public final class ClientChannelPick {
    public record Verdict(Result rejection, int cooldownSeconds) {
        static Verdict reject(Result result) { return new Verdict(result, 0); }
    }
    private final WorldChannelStore plans;
    private final ChannelSwitchCooldown cooldown;
    private final ChannelSwitchProperties props;
    private final WorldChannelProperties world;

    public ClientChannelPick(WorldChannelStore plans, ChannelSwitchCooldown cooldown, ChannelSwitchProperties props,
                             WorldChannelProperties world) {
        this.plans = Objects.requireNonNull(plans);
        this.cooldown = Objects.requireNonNull(cooldown);
        this.props = Objects.requireNonNull(props);
        this.world = Objects.requireNonNull(world);
    }

    public Verdict check(SelectSwitchTargetRequest request, List<SceneNodeInfo> nodes) {
        var members = plans.readPlan(request.getZoneId()).channels().stream()
                .filter(c -> c.getSceneId() == request.getWantSceneId() && ChannelKinds.isWorldChannel(c.getKind())).toList();
        if (members.isEmpty()) {
            // 计划缺席不能反推为私有实例：世界频道删除/计划重建时，节点目录可能仍有旧条目。
            // 只有节点明确上报、身份唯一的镜像/副本才沿用既有豁免。
            return uniquePrivateInstance(request, nodes) ? new Verdict(null, 0) : Verdict.reject(Result.CHANNEL_UNAVAILABLE);
        }
        if (members.size() != 1) return Verdict.reject(Result.CHANNEL_UNAVAILABLE);
        var channel = members.getFirst();
        if (channel.getState() == ChannelState.CHANNEL_DRAINING) return Verdict.reject(Result.CHANNEL_CLOSING);
        if (channel.getState() != ChannelState.CHANNEL_ACTIVE) return Verdict.reject(Result.CHANNEL_UNAVAILABLE);
        if (request.getWantSceneConfigId() != 0 && request.getWantSceneConfigId() != channel.getSceneConfigId()) {
            return Verdict.reject(Result.CHANNEL_CONF_MISMATCH);
        }
        if (props.disabled()) return Verdict.reject(Result.CHANNEL_DISABLED);
        var scene = ChannelDirectoryBuilder.uniqueEntry(request.getZoneId(), channel, nodes);
        if (scene == null) return Verdict.reject(Result.CHANNEL_UNAVAILABLE);
        if (scene.getDraining()) return Verdict.reject(Result.CHANNEL_CLOSING);
        if (Integer.toUnsignedLong(scene.getPlayerCount()) >= props.effectiveMaxPlayers(world)) {
            return Verdict.reject(Result.CHANNEL_FULL);
        }
        boolean changing = request.getFromSceneNodeId() != 0 && request.getFromSceneId() != 0
                && request.getFromSceneId() != request.getWantSceneId();
        int seconds = changing ? props.effectiveCooldownSeconds() : 0;
        if (seconds > 0 && cooldown.active(request.getPlayerId())) return Verdict.reject(Result.CHANNEL_COOLDOWN);
        return new Verdict(null, seconds);
    }

    private static boolean uniquePrivateInstance(SelectSwitchTargetRequest request, List<SceneNodeInfo> nodes) {
        SceneNodeInfo holder = null;
        ChannelKind kind = null;
        int scenes = 0;
        for (SceneNodeInfo node : nodes) {
            for (var entry : node.getScenesList()) {
                if (entry.getSceneId() == request.getWantSceneId()) {
                    scenes++;
                    holder = node;
                    kind = entry.getKind();
                }
            }
        }
        if (scenes != 1 || (kind != ChannelKind.CHANNEL_KIND_MIRROR && kind != ChannelKind.CHANNEL_KIND_DUNGEON)) return false;
        int nodeId = holder.getNodeId();
        return nodes.stream().filter(node -> node.getNodeId() == nodeId).count() == 1;
    }
}
