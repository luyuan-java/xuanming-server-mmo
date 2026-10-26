package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldChannelDirectoryStore;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldPlan;
import com.game.proto.SceneChannelDirectory;
import com.game.scenemanager.SceneNodeSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChannelDirectoryPublisherTest {
    private final WorldChannelStore plans = mock(WorldChannelStore.class);
    private final WorldChannelDirectoryStore directories = mock(WorldChannelDirectoryStore.class);
    private final SceneNodeSource nodes = mock(SceneNodeSource.class);
    private final WorldChannelProperties world = WorldChannelProperties.defaults();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final WorldChannelMetrics metrics = new WorldChannelMetrics(meters, List.of(7, 8));

    private ChannelDirectoryPublisher publisher(ChannelSwitchProperties props, Set<Integer> zones) {
        return new ChannelDirectoryPublisher(plans, directories, nodes, props, world, List.of(7, 8), () -> zones, "leader-a", () -> 123, metrics);
    }

    @Test
    void 扩缩容关闭仍发布_采用已提交版本_成功两图且不预占不改计划() throws Exception {
        WorldChannel channel = ChannelDirectoryBuilderTest.channel(1, 1);
        when(plans.readPlan(1)).thenReturn(new WorldPlan(19, List.of(channel)));
        when(nodes.list(1)).thenReturn(List.of(ChannelDirectoryBuilderTest.node(1, ChannelDirectoryBuilderTest.scene(1, 13))));
        when(directories.publish(eq(1), eq("leader-a"), eq(19L), anyInt(), any(), any())).thenReturn(true);
        var publisher = publisher(ChannelSwitchProperties.defaults(), Set.of(1));
        assertThat(world.autoscale().enabled()).isFalse();
        publisher.publishOnce();
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(directories).publish(eq(1), eq("leader-a"), eq(19L), eq(7), bytes.capture(), eq(Duration.ofSeconds(11)));
        var directory = SceneChannelDirectory.parseFrom(bytes.getValue());
        assertThat(directory.getChannels(0).getPlayerCount()).isEqualTo(13);
        assertThat(directory.getUpdatedAtMs()).isEqualTo(123);
        verify(directories).publish(eq(1), eq("leader-a"), eq(19L), eq(8), any(), eq(Duration.ofSeconds(11)));
        verify(plans).readPlan(1);
        verifyNoMoreInteractions(plans);
        assertThat(count("ok")).isEqualTo(1);
    }

    @Test
    void 版本或任期被围栏_余下地图不发_下一拍重读_读取失败不发布空值() {
        when(plans.readPlan(1)).thenReturn(new WorldPlan(20, List.of()));
        when(nodes.list(1)).thenReturn(List.of());
        var publisher = publisher(ChannelSwitchProperties.defaults(), Set.of(1));
        publisher.publishOnce();
        verify(directories).publish(eq(1), eq("leader-a"), eq(20L), eq(7), any(), any());
        verifyNoMoreInteractions(directories);
        assertThat(count("fenced")).isEqualTo(1);
        when(plans.readPlan(1)).thenThrow(new IllegalStateException("down"));
        publisher.publishOnce();
        verify(plans, times(2)).readPlan(1);
        verifyNoMoreInteractions(directories);
        assertThat(count("error")).isEqualTo(1);
    }

    @Test
    void 禁止发布或没有领导权时零IO_无效旧线号不发布() {
        publisher(new ChannelSwitchProperties(false, 0, 0, 0, -1), Set.of(1)).publishOnce();
        publisher(ChannelSwitchProperties.defaults(), Set.of()).publishOnce();
        verifyNoInteractions(plans, directories, nodes);
        when(plans.readPlan(1)).thenReturn(new WorldPlan(20, List.of(ChannelDirectoryBuilderTest.channel(1, 0))));
        when(nodes.list(1)).thenReturn(List.of());
        publisher(ChannelSwitchProperties.defaults(), Set.of(1)).publishOnce();
        verifyNoInteractions(directories);
        assertThat(count("error")).isEqualTo(1);
    }

    @Test
    void 读取确认空计划才发布空目录_计empty_所有指标标签只有固定result() throws Exception {
        when(plans.readPlan(1)).thenReturn(new WorldPlan(20, List.of()));
        when(nodes.list(1)).thenReturn(List.of());
        when(directories.publish(eq(1), eq("leader-a"), eq(20L), anyInt(), any(), any())).thenReturn(true);
        publisher(ChannelSwitchProperties.defaults(), Set.of(1)).publishOnce();
        var bytes = ArgumentCaptor.forClass(byte[].class);
        verify(directories).publish(eq(1), eq("leader-a"), eq(20L), eq(7), bytes.capture(), any());
        assertThat(SceneChannelDirectory.parseFrom(bytes.getValue()).getChannelsList()).isEmpty();
        assertThat(count("empty")).isEqualTo(1);
        assertThat(meters.find(WorldChannelMetrics.DIRECTORY_PUBLISH).counters()).hasSize(4).allSatisfy(counter -> {
            assertThat(counter.getId().getTags()).hasSize(1);
            assertThat(counter.getId().getTags().getFirst().getKey()).isEqualTo("result");
            assertThat(counter.getId().getTag("result")).isIn("ok", "empty", "error", "fenced");
        });
    }

    private double count(String result) { return meters.get(WorldChannelMetrics.DIRECTORY_PUBLISH).tag("result", result).counter().count(); }

    @Test
    void 独立两秒调度_不跟随五秒规划拍_停服停止发布线程() throws Exception {
        var coordinator = mock(WorldChannelCoordinator.class);
        var tick = mock(ScheduledExecutorService.class);
        var renew = mock(ScheduledExecutorService.class);
        var directory = mock(ScheduledExecutorService.class);
        when(tick.awaitTermination(anyLong(), any())).thenReturn(true);
        var publisher = publisher(ChannelSwitchProperties.defaults(), Set.of());
        var runner = new WorldChannelRunner(coordinator, Duration.ofSeconds(5), Duration.ofSeconds(10), publisher,
                tick, renew, directory);
        verify(tick).scheduleWithFixedDelay(any(Runnable.class), eq(0L), eq(5000L), eq(TimeUnit.MILLISECONDS));
        var task = ArgumentCaptor.forClass(Runnable.class);
        verify(directory).scheduleWithFixedDelay(task.capture(), eq(2000L), eq(2000L), eq(TimeUnit.MILLISECONDS));
        task.getValue().run();
        verifyNoInteractions(plans, nodes, directories);
        runner.close();
        verify(directory).shutdownNow();
        verify(coordinator).releaseAll();
    }
}
