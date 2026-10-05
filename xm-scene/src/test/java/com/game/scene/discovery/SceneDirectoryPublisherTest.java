package com.game.scene.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.scene.discovery.SceneDirectoryPublisher.Snapshot;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SceneDirectoryPublisherTest {

    /** 身份含资产通道的直连地址（rpc_host / rpc_port）：每轮发布原样带上，场景列表与已应用的计划版本另填。 */
    private static final SceneNodeInfo IDENTITY = SceneNodeInfo.newBuilder()
            .setZoneId(1).setNodeId(3).setInstanceId("inst").setLinkHost("127.0.0.1").setLinkPort(21000)
            .setRpcHost("127.0.0.1").setRpcPort(21100).build();
    private static final SceneEntry ENTRY = SceneEntry.newBuilder()
            .setSceneId(88).setSceneConfigId(1).setPlayerCount(2).build();
    private static final SceneEntry DRAINING = SceneEntry.newBuilder()
            .setSceneId(0x8000_0000_0000_0089L).setSceneConfigId(1).setPlayerCount(1).setDraining(true).build();

    @SuppressWarnings("unchecked")
    private final NodeDirectory<SceneNodeInfo> directory = mock(NodeDirectory.class);

    @Test
    void 发布身份加场景快照与已应用的计划版本_TTL15秒() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY,
                () -> new Snapshot(List.of(ENTRY, DRAINING), 0x8000_0000_0000_0005L));

        publisher.publishOnce();

        ArgumentCaptor<SceneNodeInfo> info = ArgumentCaptor.forClass(SceneNodeInfo.class);
        verify(directory).publish(eq(1), eq(3), info.capture(), eq(SceneDirectoryPublisher.TTL));
        assertThat(info.getValue()).isEqualTo(IDENTITY.toBuilder().addScenes(ENTRY).addScenes(DRAINING)
                .setAppliedPlanVersion(0x8000_0000_0000_0005L).build());
        assertThat(info.getValue().getSceneNodeType()).as("5.1 恒 0（D15）").isZero();
    }

    @Test
    void 正常停止_删除条目且之后不再发布() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY,
                () -> new Snapshot(List.of(ENTRY), 0));

        publisher.stop(true);
        publisher.publishOnce();

        verify(directory).remove(1, 3);
        verify(directory, never()).publish(anyInt(), anyInt(), any(), any());
    }

    @Test
    void 租约丢失停止_不删条目() {
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY,
                () -> new Snapshot(List.of(ENTRY), 0));

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

    @Test
    void 立即补发_启动后在调度线程上发一次新快照_没启动或已停止时什么也不做() {
        AtomicLong applied = new AtomicLong(1);
        SceneDirectoryPublisher publisher = new SceneDirectoryPublisher(directory, IDENTITY,
                () -> new Snapshot(List.of(ENTRY), applied.get()));
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        try {
            publisher.requestPublishNow();
            verify(directory, never()).publish(anyInt(), anyInt(), any(), any());

            publisher.start(scheduler);
            verify(directory, timeout(2_000).times(1)).publish(anyInt(), anyInt(), any(), any());
            applied.set(2);
            publisher.requestPublishNow();

            ArgumentCaptor<SceneNodeInfo> info = ArgumentCaptor.forClass(SceneNodeInfo.class);
            verify(directory, timeout(2_000).times(2)).publish(eq(1), eq(3), info.capture(), any());
            assertThat(info.getAllValues()).extracting(SceneNodeInfo::getAppliedPlanVersion).containsExactly(1L, 2L);

            publisher.stop(false);
            publisher.requestPublishNow();
            verify(directory, times(2)).publish(anyInt(), anyInt(), any(), any());
        } finally {
            scheduler.shutdownNow();
        }
    }
}
