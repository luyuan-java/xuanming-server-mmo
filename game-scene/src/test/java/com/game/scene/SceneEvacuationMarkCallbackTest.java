package com.game.scene;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;
import org.springframework.test.util.ReflectionTestUtils;

/** 真实标记读取与组合根回调：Redis 线程只读，结果兑现前重验租约与停服状态。 */
class SceneEvacuationMarkCallbackTest {
    private final RedisKeys keys = new RedisKeys(GameNamespace.of("game"));
    private final SceneNodeProperties props = mock(SceneNodeProperties.class);
    private final RedissonClient redis = mock(RedissonClient.class);
    @SuppressWarnings("unchecked") private final RBucket<String> mark = mock(RBucket.class);
    private final SceneWorld world = mock(SceneWorld.class);
    private final DefaultEventLoop loop = mock(DefaultEventLoop.class);
    private final NodeIdLease lease = mock(NodeIdLease.class);
    private final SceneDirectoryPublisher publisher = mock(SceneDirectoryPublisher.class);
    private final List<Runnable> tasks = new ArrayList<>();
    private final SceneNode node = new SceneNode(props, redis, keys, null, null, null, null, null, null, null, null,
            null, null, SceneMetrics.noop(), null, new KafkaNames(GameNamespace.of("game")), null, null);

    @BeforeEach void prepare() {
        when(props.zoneId()).thenReturn(1);
        when(lease.nodeId()).thenReturn(3);
        when(lease.leaseEpoch()).thenReturn(7L);
        when(lease.isValid()).thenReturn(true);
        when(redis.<String>getBucket(keys.sceneEvacuating(1, 3), StringCodec.INSTANCE)).thenReturn(mark);
        ReflectionTestUtils.setField(node, "world", world);
        ReflectionTestUtils.setField(node, "logicLoop", loop);
        ReflectionTestUtils.setField(node, "publisher", publisher);
        ReflectionTestUtils.setField(node, "lease", lease);
        ReflectionTestUtils.setField(node, "running", true);
        doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(loop).execute(any(Runnable.class));
    }

    private void read() { ReflectionTestUtils.invokeMethod(node, "onPlanSynchronized", lease, lease.leaseEpoch()); }
    private String instance() { return (String) ReflectionTestUtils.getField(node, "instanceId"); }

    @Test void 本实例标记只在逻辑回调开始疏散并补发目录() {
        when(mark.get()).thenReturn("123:" + instance());
        when(world.nodeEvacuating()).thenReturn(false, true);
        read();
        verifyNoInteractions(world);
        tasks.removeFirst().run();
        verify(world).beginNodeEvacuation(NodeEvacuation.Cause.ADMIN, Duration.ofSeconds(15));
        verify(publisher).resume();
        assertThat(node.ready()).isFalse();
    }

    @Test void Redis读失败保持原疏散而不是撤销() {
        when(mark.get()).thenThrow(new RedisException("offline"));
        when(world.nodeEvacuating()).thenReturn(true);
        read();
        tasks.removeFirst().run();
        verify(world, never()).beginNodeEvacuation(any(), any());
        verify(world, never()).endNodeEvacuation();
        assertThat(node.ready()).isFalse();
    }

    @Test void 旧实例标记只尝试结束ADMIN_不误开始新一轮() {
        when(mark.get()).thenReturn("123:old-instance");
        read();
        tasks.removeFirst().run();
        verify(world).endNodeEvacuation();
        verify(world, never()).beginNodeEvacuation(any(), any());
    }

    @Test void 租约换代后迟到标记结果不得作用于新代次() {
        when(mark.get()).thenReturn("123:" + instance());
        read();
        when(lease.leaseEpoch()).thenReturn(8L);
        tasks.removeFirst().run();
        verifyNoInteractions(world, publisher);
    }

    @Test void 停服或租约失效后迟到结果不再恢复接客() {
        when(mark.get()).thenReturn(null);
        read();
        when(lease.isValid()).thenReturn(false);
        tasks.removeFirst().run();
        verifyNoInteractions(world, publisher);
        when(lease.isValid()).thenReturn(true);
        read();
        ReflectionTestUtils.setField(node, "closing", true);
        tasks.removeFirst().run();
        verifyNoInteractions(world, publisher);
    }

    private boolean applyPlan(long originalEpoch) {
        return ReflectionTestUtils.invokeMethod(node, "applyChannelPlanForIdentity", lease, originalEpoch,
                world, 12L, List.of());
    }

    @Test void 计划逻辑兑现前失效或换代不能改世界_有效原件才应用() {
        when(lease.isValid()).thenReturn(false);
        assertThatThrownBy(() -> applyPlan(7)).isInstanceOf(IllegalStateException.class);
        when(lease.isValid()).thenReturn(true);
        when(lease.leaseEpoch()).thenReturn(8L);
        assertThatThrownBy(() -> applyPlan(7)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(world);

        when(world.applyChannelPlan(12L, List.of())).thenReturn(true);
        assertThat(applyPlan(8)).isTrue();
        verify(world).applyChannelPlan(12L, List.of());
    }

    @Test void 旧租约对象或旧世界的迟到计划不得作用于重建节点_停服也拒绝() {
        ReflectionTestUtils.setField(node, "lease", mock(NodeIdLease.class));
        assertThatThrownBy(() -> applyPlan(7)).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(node, "lease", lease);
        SceneWorld replacement = mock(SceneWorld.class);
        ReflectionTestUtils.setField(node, "world", replacement);
        assertThatThrownBy(() -> applyPlan(7)).isInstanceOf(IllegalStateException.class);
        ReflectionTestUtils.setField(node, "world", world);
        ReflectionTestUtils.setField(node, "closing", true);
        assertThatThrownBy(() -> applyPlan(7)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(world, replacement);
    }

    @Test void 旧拍宣布同步时已经换代或换租约对象_不读标记不排恢复任务() {
        when(lease.leaseEpoch()).thenReturn(8L);
        ReflectionTestUtils.invokeMethod(node, "onPlanSynchronized", lease, 7L);
        ReflectionTestUtils.setField(node, "lease", mock(NodeIdLease.class));
        ReflectionTestUtils.invokeMethod(node, "onPlanSynchronized", lease, 8L);
        assertThat(tasks).isEmpty();
        verifyNoInteractions(mark, world, publisher);
    }
}
