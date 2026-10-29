package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorldEvacuationPlannerTest {
    private PlanFixture fixture() {
        return new PlanFixture().confs(1).active(101, 1, 10, 0).nodeHostingPlan(10).node(20, 0).desired(1, 1);
    }
    private void evacuating(PlanFixture f, int id, boolean value) {
        var node = f.nodes.get(id);
        f.nodes.put(id, new DirectoryView.Node(id, node.instanceId(), node.appliedPlanVersion(), node.scenes(), value, 9));
    }

    @Test void 疏散节点仍在场_原号转排空与远端新号同批写入且期望不变() {
        var f = fixture();
        evacuating(f, 10, true);
        var result = f.plan();
        assertThat(result.channels().get(101L).getDrainReason()).isEqualTo(DrainReason.DRAIN_EVACUATE);
        assertThat(result.channels().get(101L).getState()).isEqualTo(ChannelState.CHANNEL_DRAINING);
        assertThat(f.created(result)).singleElement().satisfies(channel -> {
            assertThat(channel.getNodeId()).isEqualTo(20);
            assertThat(channel.getPlanVersion()).isEqualTo(result.channels().get(101L).getPlanVersion());
        });
        assertThat(result.removed()).isEmpty();
        assertThat(PlanFixture.ops(result, com.game.discovery.world.WorldPlanOp.SetDesired.class)).isEmpty();
    }

    @Test void 疏散缩容超时只改原因不回滚且撤销标记后按覆盖建新号() {
        var f = fixture().draining(101, 1, 10, 0, DrainReason.DRAIN_SCALE_IN, 0, PlanFixture.NOW - 3_600_000);
        evacuating(f, 10, true);
        var result = f.plan();
        assertThat(result.channels().get(101L).getDrainReason()).isEqualTo(DrainReason.DRAIN_EVACUATE);
        assertThat(result.channels().get(101L).getState()).isEqualTo(ChannelState.CHANNEL_DRAINING);
        assertThat(PlanFixture.ops(result, com.game.discovery.world.WorldPlanOp.SetDesired.class)).isEmpty();
        f.apply(result);
        evacuating(f, 10, false);
        var restored = f.plan();
        assertThat(restored.channels().get(101L).getState()).isEqualTo(ChannelState.CHANNEL_DRAINING);
        assertThat(f.created(restored)).singleElement().satisfies(channel -> assertThat(channel.getNodeId()).isEqualTo(10));
    }

    @Test void 死节点必须缺席满短宽限且租约明确不存在_持有或未知等长宽限() {
        for (Boolean held : new Boolean[]{false, true, null}) {
            var f = fixture();
            f.nodes.remove(10);
            f.absent(10, Duration.ofSeconds(19));
            if (held == null) f.nodeLeases.remove(10); else f.nodeLeases.put(10, held);
            assertThat(f.plan().channels()).containsKey(101L);
            f.absentNanos.put(10, Duration.ofSeconds(20).toNanos());
            assertThat(f.plan().channels().containsKey(101L)).isEqualTo(!Boolean.FALSE.equals(held));
            f.absentNanos.put(10, Duration.ofSeconds(119).toNanos());
            assertThat(f.plan().channels().containsKey(101L)).isEqualTo(!Boolean.FALSE.equals(held));
            f.absentNanos.put(10, Duration.ofSeconds(120).toNanos());
            assertThat(f.plan().channels()).doesNotContainKey(101L);
        }
    }

    @Test void 同号新实例在场不判死也不误疏散() {
        var f = fixture();
        var before = f.nodes.get(10);
        f.nodes.put(10, new DirectoryView.Node(10, "new-instance", 0, before.scenes(), false, 99));
        assertThat(f.plan().channels().get(101L).getState()).isEqualTo(ChannelState.CHANNEL_ACTIVE);
    }

    @Test void hash有玩家无本地兄弟可用远端余量_关开关恢复旧限制() {
        for (boolean enabled : new boolean[]{true, false}) {
            var f = new PlanFixture().confs(1).props(PlanFixture.props(Map.of("coverage", "hash",
                    "autoscale.enabled", true, "cross-node-relocation", enabled)))
                    .active(101, 1, 10, 0).active(102, 1, 20, 1).desired(1, 2)
                    .node(10, 0, PlanFixture.scene(101, 1, 40)).node(20, 0, PlanFixture.scene(102, 1, 1500));
            f.autoscaleDue = true;
            assertThat(f.plan().channels().get(101L).getState()).isEqualTo(enabled
                    ? ChannelState.CHANNEL_DRAINING : ChannelState.CHANNEL_ACTIVE);
        }
    }

    @Test void hash有本地兄弟时不能借远端余量_镜像源仍不可缩() {
        var f = new PlanFixture().confs(1).props(PlanFixture.props(Map.of("coverage", "hash", "autoscale.enabled", true)))
                .active(101, 1, 10, 0).active(102, 1, 10, 1).active(103, 1, 20, 2).desired(1, 3)
                .node(10, 0, PlanFixture.scene(101, 1, 90), PlanFixture.scene(102, 1, 1990))
                .node(20, 0, PlanFixture.scene(103, 1, 500));
        f.autoscaleDue = true;
        assertThat(f.plan().channels().get(101L).getState()).isEqualTo(ChannelState.CHANNEL_ACTIVE);
        f.node(10, 0, PlanFixture.scene(101, 1, 90), PlanFixture.scene(102, 1, 1900));
        f.mirrors = scene -> scene == 101;
        assertThat(f.plan().channels().get(101L).getState()).isEqualTo(ChannelState.CHANNEL_ACTIVE);
    }

    @Test void 长宽限必须大于短宽限且短宽限不得短于目录发布周期() {
        assertThat(PlanFixture.props(Map.of()).stuckNodeGrace()).isEqualTo(Duration.ofSeconds(120));
        assertThat(PlanFixture.props(Map.of()).crossNodeRelocation()).isTrue();
        assertThatThrownBy(() -> PlanFixture.props(Map.of("stuck-node-grace", "20s"))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> PlanFixture.props(Map.of("dead-node-grace", "4s"))).isInstanceOf(RuntimeException.class);
    }
}
