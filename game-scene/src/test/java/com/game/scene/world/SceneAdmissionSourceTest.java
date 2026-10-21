package com.game.scene.world;

import static org.assertj.core.api.Assertions.*;

import com.game.api.proto.PlayerEnter;
import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;

class SceneAdmissionSourceTest {
    private final SceneAdmissionSource source = new SceneAdmissionSource("7", "scene-instance-original", 3);

    private PlayerEnter enter() {
        return PlayerEnter.newBuilder().setPlayerId(11).setSessionId(19).setSceneId(23).setOwnerEpoch(5)
                .setAccount(" 原账号 ").setOwnerRevision(9).setLoginGeneration(4).setTransfer(true).build();
    }

    @Test
    void 场景代采用原E_跨节点保留首次登录E与原归属修订() {
        var original = source.original(enter());
        assertThat(original.getGeneration()).isEqualTo(5);
        assertThat(original.getOwnerEpoch()).isEqualTo(5);
        assertThat(original.getLoginGeneration()).isEqualTo(4);
        assertThat(original.getOwnerRevision()).isEqualTo(9);
        assertThat(original.getSceneNodeId()).isEqualTo("7");
        assertThat(original.getSceneInstanceId()).isEqualTo("scene-instance-original");
        assertThat(original.getZoneId()).isEqualTo(3);
        assertThat(original.getSceneId()).isEqualTo(23);
        assertThat(original.getSessionId()).isEqualTo(19);
        assertThat(source.original(enter()).toByteArray()).isEqualTo(original.toByteArray());
    }

    @Test
    void 缺原件或超内部修订范围拒绝_不读取当前owner补权() {
        for (var bad : java.util.List.of(enter().toBuilder().clearAccount().build(),
                enter().toBuilder().clearOwnerRevision().build(), enter().toBuilder().clearLoginGeneration().build(),
                enter().toBuilder().setOwnerRevision(Long.MIN_VALUE).build(),
                enter().toBuilder().setLoginGeneration(6).build(),
                enter().toBuilder().setUnknownFields(UnknownFieldSet.newBuilder().addField(99,
                        UnknownFieldSet.Field.newBuilder().addVarint(1).build()).build()).build())) {
            assertThatThrownBy(() -> source.original(bad)).isInstanceOf(RuntimeException.class);
        }
    }
}
