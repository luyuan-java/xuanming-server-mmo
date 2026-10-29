package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import com.game.discovery.NodeIdLease;
import com.game.discovery.RedisKeys;
import com.game.scene.discovery.SceneDirectoryPublisher;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.world.NodeEvacuation;
import com.game.scene.world.SceneWorld;
import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.ScheduledFuture;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 节点组合根的生命周期边界：不启动中间件，手动兑现真正的逻辑任务与收敛检查。 */
class SceneEvacuationLifecycleTest {
    private final SceneWorld world = mock(SceneWorld.class);
    private final DefaultEventLoop loop = mock(DefaultEventLoop.class);
    private final SceneDirectoryPublisher publisher = mock(SceneDirectoryPublisher.class);
    private final NodeIdLease lease = mock(NodeIdLease.class);
    private final List<Runnable> tasks = new ArrayList<>();
    private final List<Runnable> checks = new ArrayList<>();
    private final ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
    private final SceneNode node = new SceneNode(null, null, new RedisKeys(GameNamespace.of("game")), null, null,
            null, null, null, null, null, null, null, null, SceneMetrics.noop(), null,
            new KafkaNames(GameNamespace.of("game")), null, null);

    @BeforeEach void prepare() {
        ReflectionTestUtils.setField(node, "world", world);
        ReflectionTestUtils.setField(node, "logicLoop", loop);
        ReflectionTestUtils.setField(node, "publisher", publisher);
        ReflectionTestUtils.setField(node, "lease", lease);
        ReflectionTestUtils.setField(node, "running", true);
        ReflectionTestUtils.setField(node, "hasActiveWorldChannel", true);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(loop).execute(any(Runnable.class));
        doAnswer(call -> { checks.add(call.getArgument(0)); return scheduled; }).when(loop)
                .scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));
        when(lease.isValid()).thenReturn(true);
    }

    @Test void 疏散只在逻辑任务执行后开始_直到包含帧冲刷的谓词为真才完成() {
        CompletableFuture<String> done = ReflectionTestUtils.invokeMethod(node, "beginEvacuation", NodeEvacuation.Cause.SHUTDOWN);
        assertThat(done).isNotDone();
        verifyNoInteractions(world);
        tasks.removeFirst().run();
        verify(world).beginNodeEvacuation(NodeEvacuation.Cause.SHUTDOWN, Duration.ofSeconds(15));
        verify(publisher).requestPublishNow();
        assertThat(node.ready()).isFalse();
        checks.getFirst().run();
        assertThat(done).isNotDone();
        when(world.nodeEvacuated()).thenReturn(true);
        checks.getFirst().run();
        assertThat(done).isCompletedWithValue("drained");
        verify(scheduled).cancel(false);
    }

    @Test void 截止预算用尽与后续冲突共用同一收敛检查() {
        CompletableFuture<String> first = ReflectionTestUtils.invokeMethod(node, "beginEvacuation", NodeEvacuation.Cause.SHUTDOWN);
        tasks.removeFirst().run();
        CompletableFuture<String> second = ReflectionTestUtils.invokeMethod(node, "beginEvacuation", NodeEvacuation.Cause.CONFLICT);
        tasks.removeFirst().run();
        assertThat(checks).hasSize(1);
        when(world.nodeEvacuation()).thenReturn(NodeEvacuation.begin(NodeEvacuation.Cause.CONFLICT,
                System.nanoTime() - TimeUnit.SECONDS.toNanos(30), Duration.ofSeconds(15)));
        checks.getFirst().run();
        assertThat(first).isCompletedWithValue("budget_exceeded");
        assertThat(second).isCompletedWithValue("budget_exceeded");
    }

    @Test void 挂起立刻降低readiness并暂停目录_停止后迟到任务不再疏散() {
        assertThat(node.ready()).isTrue();
        ReflectionTestUtils.invokeMethod(node, "onIdentitySuspended");
        assertThat(node.ready()).isFalse();
        verify(publisher).pause();
        tasks.removeFirst().run();
        verify(world).stopAcceptingEnters();
        CompletableFuture<String> done = ReflectionTestUtils.invokeMethod(node, "beginEvacuation", NodeEvacuation.Cause.SHUTDOWN);
        ReflectionTestUtils.setField(node, "closing", true);
        tasks.removeFirst().run();
        assertThat(done).isCompletedWithValue("stopping");
        verify(world, never()).beginNodeEvacuation(any(), any());
    }

    @Test void 没有可承接登录的世界频道时readiness保持关闭() {
        when(world.sceneEntries()).thenReturn(List.of());
        ReflectionTestUtils.invokeMethod(node, "updateChannelReadiness", world);
        assertThat(node.ready()).isFalse();
        var mirror = com.game.api.proto.SceneEntry.newBuilder()
                .setKind(com.game.api.proto.ChannelKind.CHANNEL_KIND_MIRROR).build();
        var draining = com.game.api.proto.SceneEntry.newBuilder()
                .setKind(com.game.api.proto.ChannelKind.CHANNEL_KIND_WORLD).setDraining(true).build();
        when(world.sceneEntries()).thenReturn(List.of(mirror, draining));
        ReflectionTestUtils.invokeMethod(node, "updateChannelReadiness", world);
        assertThat(node.ready()).isFalse();
        when(world.sceneEntries()).thenReturn(List.of(draining.toBuilder().setDraining(false).build()));
        ReflectionTestUtils.invokeMethod(node, "updateChannelReadiness", world);
        assertThat(node.ready()).isTrue();
    }

    @Test void 链路回调先于仓储清算且共用一个预算() throws Exception {
        var clock = new AtomicLong(100);
        List<String> order = new ArrayList<>();
        var settlement = SceneShutdown.writesThenTransfers(timeout -> {
            assertThat(timeout).isEqualTo(10);
            order.add("write_callback"); clock.addAndGet(7); return 1;
        }, timeout -> {
            assertThat(timeout).isEqualTo(3);
            order.add("ownership_release"); return 2;
        }, clock::get);
        assertThat(settlement.await(10)).isEqualTo(3);
        assertThat(order).containsExactly("write_callback", "ownership_release");
    }
}
