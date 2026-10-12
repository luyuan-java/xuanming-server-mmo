package com.game.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.CreateDungeonInstanceRequest;
import com.game.api.proto.CreateDungeonInstanceResponse;
import com.game.api.proto.CreateInstanceRequest;
import com.game.api.proto.CreateInstanceResponse;
import com.game.api.proto.DestroyInstanceRequest;
import com.game.api.proto.DestroyInstanceResponse;
import com.game.api.proto.SceneEntry;
import com.google.protobuf.CodedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

/**
 * 批次 5.3 的内部契约（dungeon-mirror-spec §6.5、§7.2「常量」）：{@code ChannelKind} 的取值、{@code SceneEntry} 追加字段的号与新旧互读、
 * {@code CreateInstanceRequest / Response} 与 dev 管理口（{@code scene_admin.proto}）的字段号，以及「UNSPECIFIED 当 WORLD、
 * 不认识的取值不当 WORLD」的唯一判法。
 */
class SceneInstanceContractTest {

    @Test
    void 种类取值固定_MIRROR为2_DUNGEON为3() {
        assertThat(ChannelKind.CHANNEL_KIND_UNSPECIFIED.getNumber()).isZero();
        assertThat(ChannelKind.CHANNEL_KIND_WORLD.getNumber()).isEqualTo(1);
        assertThat(ChannelKind.CHANNEL_KIND_MIRROR.getNumber()).isEqualTo(2);
        assertThat(ChannelKind.CHANNEL_KIND_DUNGEON.getNumber()).isEqualTo(3);
    }

    @Test
    void 只有WORLD与UNSPECIFIED算主世界频道_实例与不认识的取值都不算() {
        assertThat(ChannelKinds.isWorldChannel(ChannelKind.CHANNEL_KIND_WORLD)).isTrue();
        assertThat(ChannelKinds.isWorldChannel(ChannelKind.CHANNEL_KIND_UNSPECIFIED)).isTrue();
        assertThat(ChannelKinds.isWorldChannel(ChannelKind.CHANNEL_KIND_MIRROR)).isFalse();
        assertThat(ChannelKinds.isWorldChannel(ChannelKind.CHANNEL_KIND_DUNGEON)).isFalse();
        assertThat(ChannelKinds.isWorldChannel(ChannelKind.UNRECOGNIZED)).isFalse();
        SceneEntry future = SceneEntry.newBuilder().setKindValue(9).build();
        assertThat(ChannelKinds.isWorldChannel(future.getKind())).as("更新版本才有的种类按「不是主世界」读").isFalse();
    }

    @Test
    void 旧版本节点写的条目没有kind_按UNSPECIFIED读_新条目往返不丢字段() throws Exception {
        // 5.1 / 5.2 的节点只写 1..4 号字段
        SceneEntry old = SceneEntry.parseFrom(SceneEntry.newBuilder().setSceneId(77).setSceneConfigId(1).setPlayerCount(3)
                .build().toByteArray());
        assertThat(old.getKind()).isEqualTo(ChannelKind.CHANNEL_KIND_UNSPECIFIED);
        assertThat(old.getSourceSceneId()).isZero();

        SceneEntry mirror = SceneEntry.newBuilder().setSceneId(0x8000_0000_0000_0001L).setSceneConfigId(1)
                .setKind(ChannelKind.CHANNEL_KIND_MIRROR).setSourceSceneId(0x8000_0000_0000_0002L).build();
        assertThat(SceneEntry.parseFrom(mirror.toByteArray())).isEqualTo(mirror);
    }

    @Test
    void SceneEntry追加字段的号是5和6() throws IOException {
        SceneEntry entry = SceneEntry.newBuilder().setKind(ChannelKind.CHANNEL_KIND_MIRROR).setSourceSceneId(42).build();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(expected);
        out.writeEnum(5, 2);
        out.writeUInt64(6, 42);
        out.flush();
        assertThat(entry.toByteArray()).isEqualTo(expected.toByteArray());
    }

    @Test
    void 取号请求与应答的字段号() throws IOException {
        CreateInstanceRequest request = CreateInstanceRequest.newBuilder().setZoneId(1).setRequesterSceneNodeId(2)
                .setPlayerId(3).setKind(ChannelKind.CHANNEL_KIND_MIRROR).setSourceSceneId(5).setSceneConfigId(6)
                .setMirrorConfigId(7).setDungeonConfigId(8).build();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        CodedOutputStream out = CodedOutputStream.newInstance(expected);
        out.writeUInt32(1, 1);
        out.writeUInt32(2, 2);
        out.writeUInt64(3, 3);
        out.writeEnum(4, 2);
        out.writeUInt64(5, 5);
        out.writeUInt32(6, 6);
        out.writeUInt32(7, 7);
        out.writeUInt32(8, 8);
        out.flush();
        assertThat(request.toByteArray()).isEqualTo(expected.toByteArray());

        CreateInstanceResponse response = CreateInstanceResponse.newBuilder().setTipId(1).setSceneNodeId(2)
                .setSceneId(3).build();
        ByteArrayOutputStream expectedResponse = new ByteArrayOutputStream();
        CodedOutputStream outResponse = CodedOutputStream.newInstance(expectedResponse);
        outResponse.writeUInt32(1, 1);
        outResponse.writeUInt32(2, 2);
        outResponse.writeUInt64(3, 3);
        outResponse.flush();
        assertThat(response.toByteArray()).isEqualTo(expectedResponse.toByteArray());
    }

    @Test
    void dev管理口请求与应答的字段号() throws IOException {
        assertThat(CreateDungeonInstanceRequest.newBuilder().setDungeonConfigId(1).build().toByteArray())
                .isEqualTo(new byte[] {0x08, 0x01});

        CreateDungeonInstanceResponse created = CreateDungeonInstanceResponse.newBuilder().setTipId(1).setSceneId(2)
                .setSceneConfigId(17).setSceneNodeId(4).build();
        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        CodedOutputStream outCreated = CodedOutputStream.newInstance(expected);
        outCreated.writeUInt32(1, 1);
        outCreated.writeUInt64(2, 2);
        outCreated.writeUInt32(3, 17);
        outCreated.writeUInt32(4, 4);
        outCreated.flush();
        assertThat(created.toByteArray()).isEqualTo(expected.toByteArray());

        assertThat(DestroyInstanceRequest.newBuilder().setSceneId(0x8000_0000_0000_0001L).build().toByteArray())
                .isEqualTo(new byte[] {0x08, (byte) 0x81, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80,
                        (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x01});
        assertThat(DestroyInstanceResponse.newBuilder().setTipId(3000).build().toByteArray())
                .isEqualTo(new byte[] {0x08, (byte) 0xB8, 0x17});
    }
}
