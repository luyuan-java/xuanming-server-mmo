package com.game.discovery.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.WorldChannel;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link WorldPlanBatch} / {@link WorldPlanOp}：plan_version 统一填写与改动的合法性（不连 Redis）。 */
class WorldPlanBatchTest {

    static WorldChannel channel(long sceneId, int conf, int node, int slot) {
        return WorldChannel.newBuilder().setSceneId(sceneId).setSceneConfigId(conf).setNodeId(node).setSlot(slot)
                .setState(ChannelState.CHANNEL_ACTIVE).setKind(ChannelKind.CHANNEL_KIND_WORLD)
                .setCreatedMs(1_000).setStateSinceMs(1_000).build();
    }

    @Test
    void 写入批次里每条记录的_plan_version_都是期望版本加一() {
        WorldPlanBatch batch = new WorldPlanBatch(41)
                .putChannel(channel(7, 1, 1, 0))
                .putChannel(channel(8, 1, 2, 1).toBuilder().setPlanVersion(3).build())
                .add(new WorldPlanOp.PutChannel(channel(9, 2, 1, 0).toBuilder().setPlanVersion(42).build()));
        assertThat(batch.expectedVersion()).isEqualTo(41);
        assertThat(batch.writtenVersion()).isEqualTo(42);
        assertThat(batch.ops()).hasSize(3).allSatisfy(op ->
                assertThat(((WorldPlanOp.PutChannel) op).channel().getPlanVersion()).isEqualTo(42));
        // 其余字段原样
        assertThat(((WorldPlanOp.PutChannel) batch.ops().get(1)).channel().getSlot()).isEqualTo(1);
    }

    @Test
    void 领导者的批次_写入后版本取期望加一与快照_Redis_TIME_的较大者_丢写重写不撞号() {
        // 正常：版本号早已是毫秒量级，加一更大
        WorldPlanSnapshot ahead = new WorldPlanSnapshot(1_800_000_000_500L, 1_800_000_000_000L, Map.of(),
                Map.of(), Map.of());
        assertThat(WorldPlanBatch.forSnapshot(ahead).writtenVersion()).isEqualTo(1_800_000_000_501L);
        // 第一次写 / 版本号是旧的小整数：直接跳到 Redis TIME
        WorldPlanSnapshot fresh = new WorldPlanSnapshot(0, 1_800_000_000_000L, Map.of(), Map.of(), Map.of());
        WorldPlanBatch first = WorldPlanBatch.forSnapshot(fresh).putChannel(channel(7, 1, 1, 0));
        assertThat(first.expectedVersion()).isZero();
        assertThat(first.writtenVersion()).isEqualTo(1_800_000_000_000L);
        assertThat(((WorldPlanOp.PutChannel) first.ops().get(0)).channel().getPlanVersion()).isEqualTo(1_800_000_000_000L);
        // Redis 丢了版本 v（写于 t1）、退回 v 之前的值后，领导者在 t2 > t1 重写：新版本 ≥ t2，严格大于丢失的 v
        long lost = WorldPlanBatch.forSnapshot(new WorldPlanSnapshot(56, 1_800_000_000_000L, Map.of(),
                Map.of(), Map.of())).writtenVersion();
        long rewritten = WorldPlanBatch.forSnapshot(new WorldPlanSnapshot(56, 1_800_000_005_000L, Map.of(),
                Map.of(), Map.of())).writtenVersion();
        assertThat(rewritten).isGreaterThan(lost);
        assertThatThrownBy(() -> new WorldPlanBatch(5, 5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanBatch(5, 4)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 改动按加入顺序保留() {
        WorldPlanBatch batch = new WorldPlanBatch(0)
                .seedDesired(1, 16)
                .putChannel(channel(7, 1, 1, 0))
                .removeChannel(6)
                .setDesired(1, 2)
                .removeDesired(9)
                .setCooldown(1, 5_000)
                .removeCooldown(9);
        assertThat(batch.ops()).containsExactly(
                new WorldPlanOp.SeedDesired(1, 16),
                new WorldPlanOp.PutChannel(channel(7, 1, 1, 0).toBuilder().setPlanVersion(1).build()),
                new WorldPlanOp.RemoveChannel(6),
                new WorldPlanOp.SetDesired(1, 2),
                new WorldPlanOp.RemoveDesired(9),
                new WorldPlanOp.SetCooldown(1, 5_000),
                new WorldPlanOp.RemoveCooldown(9));
        assertThat(batch.size()).isEqualTo(7);
        assertThat(batch.isEmpty()).isFalse();
        assertThat(new WorldPlanBatch(0).isEmpty()).isTrue();
        assertThatThrownBy(() -> batch.ops().add(new WorldPlanOp.RemoveChannel(1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 频道记录的号不能为_0_状态必须明确_slot_不越界() {
        assertThatThrownBy(() -> new WorldPlanOp.PutChannel(channel(0, 1, 1, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanOp.PutChannel(channel(7, 0, 1, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanOp.PutChannel(channel(7, 1, 0, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanOp.PutChannel(
                channel(7, 1, 1, 0).toBuilder().setState(ChannelState.CHANNEL_STATE_UNSPECIFIED).build()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanOp.PutChannel(channel(7, 1, 1, WorldChannels.MAX_SLOT + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanOp.PutChannel(null)).isInstanceOf(IllegalArgumentException.class);
        // 边界内的都收；scene_id 是 uint64，最高位为 1 也合法
        new WorldPlanOp.PutChannel(channel(-1L, 1, 1, WorldChannels.MAX_SLOT));
        new WorldPlanOp.PutChannel(channel(7, 1, 1, 0).toBuilder().setState(ChannelState.CHANNEL_DRAINING).build());
    }

    @Test
    void 期望数必须至少为_1_conf_不能为_0() {
        assertThatThrownBy(() -> new WorldPlanOp.SetDesired(1, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanOp.SeedDesired(1, -1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanOp.SetDesired(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanOp.SetCooldown(0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanOp.RemoveChannel(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanBatch(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WorldPlanBatch(0).add(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
