package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldPlanWriteResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/** 线号是计划持久字段，不是 placement slot；验证修旧记录、占号和释放时机。 */
class ChannelNumbersTest {
    @Test
    void 旧零值和重复号按无符号场景号补齐_先保留所有合法号_第二拍无写() {
        var f = new PlanFixture().confs(1).active(10, 1, 1, 0).active(20, 1, 1, 1)
                .active(30, 1, 1, 2).active(Long.MIN_VALUE, 1, 1, 3).desired(1, 4);
        number(f, 10, 0); number(f, 20, 1); number(f, 30, 1); number(f, Long.MIN_VALUE, 0);
        f.nodeHostingPlan(1);
        var result = f.plan();
        assertThat(result.channels().values()).extracting(WorldChannel::getChannelNo).containsExactly(2, 1, 3, 4);
        assertThat(PlanFixture.puts(result)).extracting(WorldChannel::getSceneId).containsExactly(10L, 30L, Long.MIN_VALUE);
        f.apply(result);
        assertThat(f.plan().batch().isEmpty()).isTrue();
    }

    @Test
    void 排空仍占号_删记录后新实例才复用最小空洞_重新创建规划器不重排() {
        var f = new PlanFixture().confs(1).active(10, 1, 1, 0).desired(1, 2)
                .draining(20, 1, 1, 1, DrainReason.DRAIN_REBALANCE, 7, PlanFixture.NOW);
        number(f, 10, 1); number(f, 20, 2);
        f.node(1, 0, PlanFixture.scene(10, 1, 0), PlanFixture.drainingScene(20, 1, 1));
        f.apply(f.plan());
        assertThat(f.channels.values()).extracting(WorldChannel::getChannelNo).containsExactly(1, 2, 3);
        assertThat(f.planner().plan(f.input()).batch().isEmpty()).isTrue();
        f.nodeHostingPlan(1); // 节点随后确认旧排空实例已销毁。
        var node = f.nodes.get(1);
        var scenes = new java.util.HashMap<>(node.scenes()); scenes.remove(20L);
        f.nodes.put(1, new DirectoryView.Node(1, "inst-1", f.version, scenes));
        f.desired(1, 3);
        var next = f.plan();
        assertThat(PlanFixture.removes(next)).containsExactly(20L);
        assertThat(PlanFixture.puts(next)).singleElement().satisfies(c -> assertThat(c.getChannelNo()).isEqualTo(2));
        assertThat(next.channels().values()).filteredOn(c -> c.getSceneId() == 10).singleElement()
                .satisfies(c -> assertThat(c.getChannelNo()).isEqualTo(1));
    }

    @Test
    void 缩容排空超时回滚和slot变化不改变线号_不同图独立编号() {
        var f = new PlanFixture().active(10, 1, 1, 0)
                .draining(20, 1, 1, 0, DrainReason.DRAIN_SCALE_IN, 7,
                        PlanFixture.NOW - Duration.ofMinutes(6).toMillis()).active(30, 2, 1, 0);
        number(f, 10, 1); number(f, 20, 7); number(f, 30, 1);
        f.node(1, 0, PlanFixture.scene(10, 1, 0), PlanFixture.drainingScene(20, 1, 1), PlanFixture.scene(30, 2, 0));
        var result = f.plan();
        assertThat(result.channels().values()).filteredOn(c -> c.getSceneId() == 20).singleElement().satisfies(c -> {
            assertThat(c.getState()).isEqualTo(ChannelState.CHANNEL_ACTIVE);
            assertThat(c.getSlot()).isEqualTo(1);
            assertThat(c.getChannelNo()).isEqualTo(7);
        });
        assertThat(result.channels().values()).filteredOn(c -> c.getSceneId() == 30).singleElement()
                .satisfies(c -> assertThat(c.getChannelNo()).isEqualTo(1));
    }

    @Test
    void uint32最高合法号保持不变_不会误判成负数() {
        var f = new PlanFixture().confs(1).active(10, 1, 1, 0).active(20, 1, 1, 1).desired(1, 2);
        number(f, 10, -1); number(f, 20, 0); f.nodeHostingPlan(1);
        assertThat(f.plan().channels().values()).extracting(WorldChannel::getChannelNo).containsExactly(-1, 1);
    }

    @Test
    void 补号与原计划同一CAS_冲突不生效_重试持久化_领导切换不重排() {
        var store = new FakeWorldChannelStore();
        store.registerZone(1);
        store.zone(1).channels.put(10L, ChannelDirectoryBuilderTest.channel(10, 0));
        store.zone(1).version = 7;
        var source = (com.game.scenemanager.SceneNodeSource) zone ->
                List.of(ChannelDirectoryBuilderTest.node(1, ChannelDirectoryBuilderTest.scene(10, 0)));
        var props = WorldChannelProperties.defaults();
        var planner = WorldChannelPlanner.fromDirectory(props, List.of(7), () -> OptionalLong.of(100));
        var metrics = new WorldChannelMetrics(new SimpleMeterRegistry(), List.of(7));
        var first = new WorldChannelCoordinator(store, source, planner, props, metrics, NodeAvailability.ALL, "a", System::nanoTime);
        store.forcedWriteResult = WorldPlanWriteResult.CONFLICT;
        first.tick();
        assertThat(store.readPlan(1).version()).isEqualTo(7);
        assertThat(store.readPlan(1).channels()).singleElement().satisfies(c -> assertThat(c.getChannelNo()).isZero());
        first.tick();
        var persisted = store.readPlan(1);
        assertThat(persisted.version()).isGreaterThan(7);
        assertThat(persisted.channels()).singleElement().satisfies(c -> assertThat(c.getChannelNo()).isEqualTo(1));
        first.releaseAll();
        var second = new WorldChannelCoordinator(store, source, planner, props, metrics, NodeAvailability.ALL, "b", System::nanoTime);
        second.tick();
        assertThat(store.readPlan(1)).isEqualTo(persisted);
    }

    private static void number(PlanFixture f, long id, int number) {
        f.channels.compute(id, (ignored, channel) -> channel.toBuilder().setChannelNo(number).build());
    }
}
