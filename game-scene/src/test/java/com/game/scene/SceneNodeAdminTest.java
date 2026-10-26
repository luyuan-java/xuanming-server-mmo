package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.game.api.proto.TeleportPlayerResponse;
import com.game.common.GameNamespace;
import com.game.common.KafkaNames;
import com.game.discovery.RedisKeys;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.Vec3;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.DefaultEventLoop;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 2.10 管理口投递边界：不起节点或中间件，直接兑现捕获到的真实逻辑任务。 */
class SceneNodeAdminTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SceneMetrics metrics = new SceneMetrics(meters);
    private final SceneWorld world = mock(SceneWorld.class);
    private final DefaultEventLoop logic = mock(DefaultEventLoop.class);
    private final SceneNode node = new SceneNode(null, null, new RedisKeys(GameNamespace.of("game")), null, null,
            null, null, null, null, null, null, null, null, metrics, null,
            new KafkaNames(GameNamespace.of("game")), null, null);

    private void running() {
        ReflectionTestUtils.setField(node, "world", world);
        ReflectionTestUtils.setField(node, "logicLoop", logic);
        ReflectionTestUtils.setField(node, "running", true);
    }

    private Runnable queuedTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(logic).execute(task.capture());
        return task.getValue();
    }

    @Test
    void 未启动时立即异常完成_不投递也不读取场景() {
        CompletableFuture<TeleportPlayerResponse> pending = node.teleportPlayer(7, 1, 2, 3);

        assertThat(pending).isCompletedExceptionally();
        assertThatThrownBy(pending::join).hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("场景节点没在运行");
        verifyNoInteractions(world, logic);
    }

    @Test
    void 只在逻辑任务执行时调用瞬移并把tip交回() {
        running();
        Vec3 target = new Vec3(-1.25, -0.0, 3.5);
        when(world.adminTeleport(7, target)).thenReturn(1004);

        CompletableFuture<TeleportPlayerResponse> pending = node.teleportPlayer(7, target.x(), target.y(), target.z());

        assertThat(pending).isNotDone();
        verifyNoInteractions(world);
        queuedTask().run();
        assertThat(pending.join()).isEqualTo(TeleportPlayerResponse.newBuilder().setTipId(1004).build());
        verify(world).adminTeleport(7, target);
        verifyNoMoreInteractions(world);
    }

    @Test
    void HTTP已取消的任务兑现时不瞬移也不计瞬移指标() {
        running();
        CompletableFuture<TeleportPlayerResponse> pending = node.teleportPlayer(7, 1, 2, 3);
        assertThat(pending.cancel(false)).isTrue();

        queuedTask().run();

        assertThat(pending.isCancelled()).isTrue();
        verifyNoInteractions(world);
        assertThat(meters.find("game.scene.teleports").counters().stream().mapToDouble(counter -> counter.count()).sum())
                .isZero();
    }

    @Test
    void 逻辑队列拒绝时立即异常完成_不等HTTP超时() {
        running();
        doThrow(new RejectedExecutionException("逻辑线程已停")).when(logic).execute(any(Runnable.class));

        CompletableFuture<TeleportPlayerResponse> pending = node.teleportPlayer(7, 1, 2, 3);

        assertThat(pending).isCompletedExceptionally();
        assertThatThrownBy(pending::join).hasCauseInstanceOf(RejectedExecutionException.class);
        verifyNoInteractions(world);
    }

    @Test
    void 逻辑任务抛异常时让HTTP立即拿到失败() {
        running();
        when(world.adminTeleport(7, new Vec3(1, 2, 3))).thenThrow(new IllegalStateException("瞬移拒绝重入场景帧"));
        CompletableFuture<TeleportPlayerResponse> pending = node.teleportPlayer(7, 1, 2, 3);

        queuedTask().run();

        assertThat(pending).isCompletedExceptionally();
        assertThatThrownBy(pending::join).hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("瞬移拒绝重入场景帧");
        verify(world).adminTeleport(7, new Vec3(1, 2, 3));
    }

    @Test
    void 玩家管理bean原样委托给节点入口() {
        SceneNode mockedNode = mock(SceneNode.class);
        CompletableFuture<TeleportPlayerResponse> expected = CompletableFuture.completedFuture(
                TeleportPlayerResponse.getDefaultInstance());
        when(mockedNode.teleportPlayer(7, 1, 2, 3)).thenReturn(expected);

        assertThat(new SceneNodeConfiguration().scenePlayerAdmin(mockedNode).teleportPlayer(7, 1, 2, 3))
                .isSameAs(expected);
        verify(mockedNode).teleportPlayer(7, 1, 2, 3);
    }
}
