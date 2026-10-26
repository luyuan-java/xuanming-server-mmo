package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.WorldChannel;
import com.game.proto.SceneChannelInfo;
import com.game.proto.SceneChannelState;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelDirectoryBuilderTest {
    @Test
    void 状态优先级_人数边界_线号排序_政策元数据() {
        var channels = List.of(channel(1, 6), channel(2, 5), channel(3, 4), channel(4, 3), channel(5, 2),
                channel(6, 1).toBuilder().setState(ChannelState.CHANNEL_DRAINING).build());
        var nodes = List.of(node(1, scene(1, 59), scene(2, 60), scene(3, 99), scene(4, 100)));
        var directory = ChannelDirectoryBuilder.build(1, 7, channels, nodes,
                new ChannelSwitchProperties(true, 13, 100, 60, 2), WorldChannelProperties.defaults(), 1234);
        assertThat(directory.getZoneId()).isEqualTo(1);
        assertThat(directory.getSceneConfigId()).isEqualTo(7);
        assertThat(directory.getUpdatedAtMs()).isEqualTo(1234);
        assertThat(directory.getSwitchEnabled()).isFalse();
        assertThat(directory.getSwitchCooldownSeconds()).isEqualTo(13);
        assertThat(directory.getMaxPlayersPerChannel()).isEqualTo(100);
        assertThat(directory.getChannelsList()).extracting(SceneChannelInfo::getChannelNo).containsExactly(1, 2, 3, 4, 5, 6);
        assertThat(directory.getChannelsList()).extracting(SceneChannelInfo::getState).containsExactly(
                SceneChannelState.SCENE_CHANNEL_STATE_CLOSING, SceneChannelState.SCENE_CHANNEL_STATE_UNAVAILABLE,
                SceneChannelState.SCENE_CHANNEL_STATE_FULL, SceneChannelState.SCENE_CHANNEL_STATE_BUSY,
                SceneChannelState.SCENE_CHANNEL_STATE_BUSY, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH);
        assertThat(directory.getChannels(2).getPlayerCount()).isEqualTo(100);
    }

    @Test
    void 节点身份歧义_映射不符_私有实例_坏节点皆不可用_排空优先() {
        var channel = channel(1, 1);
        for (var nodes : List.of(List.of(node(1, scene(1, -1)), node(1)),
                List.of(node(1, scene(1, 0)), node(2, scene(1, 0))), List.of(node(2, scene(1, 0))),
                List.of(node(1, scene(1, 0).toBuilder().setSceneConfigId(8).build())),
                List.of(node(1, scene(1, 0).toBuilder().setKind(ChannelKind.CHANNEL_KIND_MIRROR).build())),
                List.of(node(1, scene(1, 0)).toBuilder().setLinkPort(0).build()))) {
            assertThat(build(channel, nodes).getState()).isEqualTo(SceneChannelState.SCENE_CHANNEL_STATE_UNAVAILABLE);
            assertThat(build(channel.toBuilder().setState(ChannelState.CHANNEL_DRAINING).build(), nodes).getState())
                    .isEqualTo(SceneChannelState.SCENE_CHANNEL_STATE_CLOSING);
        }
        assertThat(build(channel, List.of(node(1, scene(1, -1)))).getState()).isEqualTo(SceneChannelState.SCENE_CHANNEL_STATE_FULL);
        assertThat(build(channel, List.of(node(1, scene(1, -1).toBuilder().setDraining(true).build()))).getState())
                .isEqualTo(SceneChannelState.SCENE_CHANNEL_STATE_CLOSING);
    }

    @Test
    void 未升级或重复线号拒绝发布_私有实例和其他地图不进目录() {
        assertThatThrownBy(() -> build(channel(1, 0), List.of())).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ChannelDirectoryBuilder.build(1, 7, List.of(channel(1, 1), channel(2, 1)), List.of(),
                ChannelSwitchProperties.defaults(), WorldChannelProperties.defaults(), 1)).isInstanceOf(IllegalStateException.class);
        var dir = ChannelDirectoryBuilder.build(1, 7, List.of(channel(1, 1), channel(2, 2).toBuilder().setSceneConfigId(8).build(),
                        channel(3, 3).toBuilder().setKind(ChannelKind.CHANNEL_KIND_DUNGEON).build()), List.of(),
                ChannelSwitchProperties.defaults(), WorldChannelProperties.defaults(), 1);
        assertThat(dir.getChannelsList()).singleElement().satisfies(c -> assertThat(c.getSceneId()).isEqualTo(1));
    }

    static WorldChannel channel(long id, int number) {
        return WorldChannel.newBuilder().setSceneId(id).setSceneConfigId(7).setNodeId(1).setChannelNo(number)
                .setState(ChannelState.CHANNEL_ACTIVE).build();
    }
    static SceneNodeInfo node(int id, SceneEntry... scenes) {
        return SceneNodeInfo.newBuilder().setZoneId(1).setNodeId(id).setInstanceId("node-" + id)
                .setLinkHost("127.0.0.1").setLinkPort(21000 + id).addAllScenes(List.of(scenes)).build();
    }
    static SceneEntry scene(long id, int count) {
        return SceneEntry.newBuilder().setSceneId(id).setSceneConfigId(7).setPlayerCount(count).build();
    }
    private static SceneChannelInfo build(WorldChannel channel, List<SceneNodeInfo> nodes) {
        return ChannelDirectoryBuilder.build(1, 7, List.of(channel), nodes, ChannelSwitchProperties.defaults(),
                WorldChannelProperties.defaults(), 1).getChannels(0);
    }
}
