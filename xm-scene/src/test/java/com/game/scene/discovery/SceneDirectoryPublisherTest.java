package com.game.scene.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SceneDirectoryPublisherTest {

    private static final SceneNodeInfo IDENTITY = SceneNodeInfo.newBuilder()
            .setZoneId(1).setNodeId(3).setInstanceId("inst").setLinkHost("127.0.0.1").setLinkPort(21000).build();
    private static final SceneEntry ENTRY = SceneEntry.newBuilder()
            .setSceneId(88).setSceneConfigId(1).setPlayerCount(2).build();

    @SuppressWarnings("unchecked")
    private final NodeDirectory<SceneNodeInfo> directory = mock(NodeDirectory.class);

    @Test
    void 发布身份加场景快照_TTL15秒() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY, () -> List.of(ENTRY));

        publisher.publishOnce();

        ArgumentCaptor<SceneNodeInfo> info = ArgumentCaptor.forClass(SceneNodeInfo.class);
        verify(directory).publish(eq(1), eq(3), info.capture(), eq(SceneDirectoryPublisher.TTL));
        assertThat(info.getValue()).isEqualTo(IDENTITY.toBuilder().addScenes(ENTRY).build());
    }

    @Test
    void 正常停止_删除条目且之后不再发布() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY, () -> List.of(ENTRY));

        publisher.stop(true);
        publisher.publishOnce();

        verify(directory).remove(1, 3);
        verify(directory, never()).publish(anyInt(), anyInt(), any(), any());
    }

    @Test
    void 租约丢失停止_不删条目() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY, () -> List.of(ENTRY));

        publisher.stop(false);

        verify(directory, never()).remove(anyInt(), anyInt());
    }

    @Test
    void 取快照失败_本轮不发布() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY, () -> {
            throw new IllegalStateException("逻辑线程超时");
        });

        publisher.publishOnce();

        verify(directory, never()).publish(anyInt(), anyInt(), any(), any());
    }
}
