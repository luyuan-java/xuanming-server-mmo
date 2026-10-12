package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 「谁是镜像源」从目录推导（批次 5.3，dungeon-mirror-spec §6.6、§12.3 DirectoryMirrorSourcesTest）：多节点；排空中（回收宽限 / 级联）的镜像也算源；
 * WORLD / UNSPECIFIED / DUNGEON 条目不算；镜像从目录消失后源不再被认出（基线 TestDestroyMirror_UnlinksFromSourceSet）。
 */
class DirectoryMirrorSourcesTest {

    private static final int ZONE = 1;
    private static final long SRC_A = 0x8000_0000_0000_0101L;
    private static final long SRC_B = 0x8000_0000_0000_0201L;

    @Test
    void 多节点上的镜像都认_排空中的镜像也算源() {
        DirectoryView view = DirectoryView.of(ZONE, List.of(
                node(1, world(SRC_A, 1), mirror(0x8000_0000_0000_0901L, 1, SRC_A, false)),
                node(2, world(SRC_B, 2), mirror(0x8000_0000_0000_0902L, 2, SRC_B, true))));

        MirrorSources sources = DirectoryMirrorSources.of(view);

        assertThat(sources.isSource(SRC_A)).isTrue();
        assertThat(sources.isSource(SRC_B)).as("回收宽限 / 级联排空中的镜像还没销毁").isTrue();
        assertThat(sources.isSource(0x8000_0000_0000_0901L)).as("镜像本身不是源").isFalse();
    }

    @Test
    void 主世界_未指定_副本条目都不算源_源号为0的镜像也不算() {
        DirectoryView view = DirectoryView.of(ZONE, List.of(node(1,
                world(SRC_A, 1),
                SceneEntry.newBuilder().setSceneId(0x8000_0000_0000_0102L).setSceneConfigId(1).setSourceSceneId(SRC_A).build(),
                SceneEntry.newBuilder().setSceneId(0x8000_0000_0000_0903L).setSceneConfigId(17)
                        .setKind(ChannelKind.CHANNEL_KIND_DUNGEON).setSourceSceneId(SRC_B).build(),
                mirror(0x8000_0000_0000_0904L, 1, 0, false))));

        MirrorSources sources = DirectoryMirrorSources.of(view);

        assertThat(sources).isSameAs(MirrorSources.NONE);
        assertThat(sources.isSource(SRC_A)).isFalse();
        assertThat(sources.isSource(SRC_B)).isFalse();
    }

    @Test
    void 镜像销毁后目录里没有它_源不再被认出() {
        DirectoryView before = DirectoryView.of(ZONE, List.of(node(1, world(SRC_A, 1), mirror(0x8000_0000_0000_0901L, 1, SRC_A, false))));
        DirectoryView after = DirectoryView.of(ZONE, List.of(node(1, world(SRC_A, 1))));

        assertThat(DirectoryMirrorSources.of(before).isSource(SRC_A)).isTrue();
        assertThat(DirectoryMirrorSources.of(after).isSource(SRC_A)).isFalse();
    }

    @Test
    void 目录视图照抄种类与源_旧版本节点的条目按主世界读() {
        DirectoryView view = DirectoryView.of(ZONE, List.of(node(1, world(SRC_A, 1),
                SceneEntry.newBuilder().setSceneId(0x8000_0000_0000_0102L).setSceneConfigId(1).build(),
                mirror(0x8000_0000_0000_0901L, 1, SRC_A, false))));

        DirectoryView.Node node = view.node(1);
        assertThat(node.scenes().get(SRC_A).isWorldChannel()).isTrue();
        assertThat(node.scenes().get(0x8000_0000_0000_0102L).kind()).isEqualTo(ChannelKind.CHANNEL_KIND_UNSPECIFIED);
        assertThat(node.scenes().get(0x8000_0000_0000_0102L).isWorldChannel()).isTrue();
        DirectoryView.Scene m = node.scenes().get(0x8000_0000_0000_0901L);
        assertThat(m.kind()).isEqualTo(ChannelKind.CHANNEL_KIND_MIRROR);
        assertThat(m.sourceSceneId()).isEqualTo(SRC_A);
        assertThat(m.isWorldChannel()).isFalse();
    }

    private static SceneNodeInfo node(int nodeId, SceneEntry... scenes) {
        return SceneNodeInfo.newBuilder().setZoneId(ZONE).setNodeId(nodeId).setLinkHost("127.0.0.1")
                .setLinkPort(21000 + nodeId).addAllScenes(List.of(scenes)).build();
    }

    private static SceneEntry world(long sceneId, int conf) {
        return SceneEntry.newBuilder().setSceneId(sceneId).setSceneConfigId(conf).setKind(ChannelKind.CHANNEL_KIND_WORLD).build();
    }

    private static SceneEntry mirror(long sceneId, int conf, long source, boolean draining) {
        return SceneEntry.newBuilder().setSceneId(sceneId).setSceneConfigId(conf).setKind(ChannelKind.CHANNEL_KIND_MIRROR)
                .setSourceSceneId(source).setDraining(draining).build();
    }
}
