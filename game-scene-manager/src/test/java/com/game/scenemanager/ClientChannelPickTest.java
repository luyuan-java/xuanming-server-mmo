package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.ChannelSwitchCooldown;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldPlan;
import com.game.scenemanager.SwitchTargetSelector.Result;
import com.game.scenemanager.world.ChannelSwitchProperties;
import com.game.scenemanager.world.FakeWorldChannelStore;
import com.game.scenemanager.world.WorldChannelProperties;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 通过真实 selector 验预检先于预占，失败无写；未置手动位的旧路径完全不读新依赖。 */
class ClientChannelPickTest {
    private final WorldChannelStore plans = mock(WorldChannelStore.class);
    private final FakeWorldChannelStore reservations = new FakeWorldChannelStore();
    private final WorldSceneConfigs configs = new WorldSceneConfigs(7, Set.of(7));
    private boolean cooling;
    private RuntimeException cooldownFailure;
    private int cooldownReads;
    private final ChannelSwitchCooldown cooldown = new ChannelSwitchCooldown() {
        public boolean active(long playerId) {
            cooldownReads++;
            if (cooldownFailure != null) throw cooldownFailure;
            return cooling;
        }
        public void start(long playerId, Duration ttl) { throw new AssertionError("预检不能写冷却"); }
    };
    private WorldChannel channel = WorldChannel.newBuilder().setSceneId(20).setSceneConfigId(7).setNodeId(2)
            .setState(ChannelState.CHANNEL_ACTIVE).setChannelNo(2).build();
    private SceneEntry entry = SceneEntry.newBuilder().setSceneId(20).setSceneConfigId(7).build();
    private ChannelSwitchProperties props = ChannelSwitchProperties.defaults();

    @Test
    void 拒绝优先级_排空配置关闭人数冷却_全部无副作用() {
        cooling = true;
        entry = entry.toBuilder().setPlayerCount(2000).build();
        props = new ChannelSwitchProperties(true, 0, 0, 0, 0);
        channel = channel.toBuilder().setState(ChannelState.CHANNEL_DRAINING).build();
        reject(request().toBuilder().setWantSceneConfigId(8).build(), Result.CHANNEL_CLOSING);
        channel = channel.toBuilder().setState(ChannelState.CHANNEL_ACTIVE).build();
        reject(request().toBuilder().setWantSceneConfigId(8).build(), Result.CHANNEL_CONF_MISMATCH);
        reject(request(), Result.CHANNEL_DISABLED);
        props = ChannelSwitchProperties.defaults();
        reject(request(), Result.CHANNEL_FULL);
        assertThat(cooldownReads).isZero();
        entry = entry.toBuilder().setPlayerCount(1999).build();
        reject(request(), Result.CHANNEL_COOLDOWN);
        assertThat(cooldownReads).isEqualTo(1);
    }

    @Test
    void 同节点和跨节点都预检_成功仅返回冷却秒数_确认前不写冷却() {
        for (int fromNode : new int[] {1, 2}) {
            var result = selector().select(request().toBuilder().setFromSceneNodeId(fromNode).build());
            assertThat(result.result()).isEqualTo(Result.OK);
            assertThat(result.response().getSwitchCooldownSeconds()).isEqualTo(10);
            assertThat(result.response().getSceneId()).isEqualTo(20);
        }
        assertThat(reservations.reservationCount(1, 20)).isEqualTo(1);
        assertThat(cooldownReads).isEqualTo(2);
    }

    @Test
    void 无实际换场不读不返回冷却_负配置关闭冷却() {
        cooling = true;
        for (var request : List.of(request().toBuilder().setFromSceneId(20).build(),
                request().toBuilder().setFromSceneId(0).build(), request().toBuilder().setFromSceneNodeId(0).build())) {
            var result = selector().select(request);
            assertThat(result.result()).isEqualTo(Result.OK);
            assertThat(result.response().getSwitchCooldownSeconds()).isZero();
        }
        props = new ChannelSwitchProperties(false, -1, 0, 0, 0);
        assertThat(selector().select(request()).response().getSwitchCooldownSeconds()).isZero();
        assertThat(cooldownReads).isZero();
    }

    @Test
    void 自动按图和未标记的显式跟随重放不访问计划冷却_私有实例不受新政策限制() {
        props = new ChannelSwitchProperties(true, 0, 1, 0, 0);
        var selector = selector();
        when(plans.readPlan(anyInt())).thenThrow(new IllegalStateException("offline"));
        assertThat(selector.select(request().toBuilder().setClientChannelPick(false).build()).result()).isEqualTo(Result.OK);
        assertThat(selector.select(request().toBuilder().setWantSceneId(0).build()).result()).isEqualTo(Result.OK);
        for (var kind : List.of(ChannelKind.CHANNEL_KIND_MIRROR, ChannelKind.CHANNEL_KIND_DUNGEON)) {
            channel = channel.toBuilder().setKind(kind).build();
            entry = entry.toBuilder().setKind(kind).build();
            assertThat(selector().select(request()).result()).isEqualTo(Result.OK);
        }
        assertThat(cooldownReads).isZero();
    }

    @Test
    void 读取失败和目标身份歧义均拒绝_不预占不返回内部异常() {
        var selector = selector();
        when(plans.readPlan(anyInt())).thenThrow(new IllegalStateException("redis-secret"));
        assertRejected(selector.select(request()), Result.CHANNEL_READ_ERROR);
        doReturn(new WorldPlan(1, List.of(channel))).when(plans).readPlan(anyInt());
        cooldownFailure = new IllegalStateException("redis-secret");
        reject(request(), Result.CHANNEL_READ_ERROR);
        cooldownFailure = null;
        var duplicate = new SwitchTargetSelector(zone -> List.of(node(), node()), configs,
                new ChannelSelector(zone -> List.of(node()), reservations, Duration.ofSeconds(10)),
                new ClientChannelPick(plans, cooldown, props, WorldChannelProperties.defaults()));
        assertRejected(duplicate.select(request()), Result.CHANNEL_UNAVAILABLE);
        var unreadable = new SwitchTargetSelector(zone -> { throw new IllegalStateException("down"); }, configs,
                ChannelSelector.withoutReservations(zone -> List.of()),
                new ClientChannelPick(plans, cooldown, props, WorldChannelProperties.defaults()));
        assertRejected(unreadable.select(request()), Result.CHANNEL_READ_ERROR);
        assertThat(reservations.reservationCount(1, 20)).isZero();
    }

    @Test
    void 世界计划缺席不能靠旧节点目录绕过关闭满员冷却_未知种类也拒绝() {
        props = new ChannelSwitchProperties(true, 0, 1, 0, 0);
        cooling = true;
        for (int kind : new int[] {ChannelKind.CHANNEL_KIND_WORLD_VALUE, ChannelKind.CHANNEL_KIND_UNSPECIFIED_VALUE, 99}) {
            entry = entry.toBuilder().setKindValue(kind).setPlayerCount(2000).build();
            var selector = selector();
            doReturn(new WorldPlan(1, List.of())).when(plans).readPlan(anyInt());
            assertRejected(selector.select(request()), Result.CHANNEL_UNAVAILABLE);
            assertThat(reservations.reservationCount(1, 20)).isZero();
        }
        assertThat(cooldownReads).isZero();
    }

    @Test
    void 仅明确且身份唯一的私有实例可在计划之外豁免() {
        props = new ChannelSwitchProperties(true, 0, 1, 0, 0);
        cooling = true;
        for (var kind : List.of(ChannelKind.CHANNEL_KIND_MIRROR, ChannelKind.CHANNEL_KIND_DUNGEON)) {
            entry = entry.toBuilder().setKind(kind).setPlayerCount(2000).build();
            var selector = selector();
            doReturn(new WorldPlan(1, List.of())).when(plans).readPlan(anyInt());
            // 相同节点号出现两次，即使第二份不带目标实例，也不能猜是哪一个进程。
            SceneNodeSource duplicate = zone -> List.of(node(), node().toBuilder().clearScenes().build());
            var ambiguous = new SwitchTargetSelector(duplicate, configs,
                    new ChannelSelector(duplicate, reservations, Duration.ofSeconds(10)),
                    new ClientChannelPick(plans, cooldown, props, WorldChannelProperties.defaults()));
            int before = reservations.reservationCount(1, 20);
            assertRejected(ambiguous.select(request()), Result.CHANNEL_UNAVAILABLE);
            assertThat(reservations.reservationCount(1, 20)).isEqualTo(before);
            var allowed = selector.select(request());
            assertThat(allowed.result()).isEqualTo(Result.OK);
            assertThat(allowed.response().getSwitchCooldownSeconds()).isZero();
        }
        assertThat(cooldownReads).isZero();
    }

    private SwitchTargetSelector selector() {
        doReturn(new WorldPlan(1, List.of(channel))).when(plans).readPlan(anyInt());
        SceneNodeSource source = zone -> List.of(node());
        return new SwitchTargetSelector(source, configs, new ChannelSelector(source, reservations, Duration.ofSeconds(10)),
                new ClientChannelPick(plans, cooldown, props, WorldChannelProperties.defaults()));
    }
    private SceneNodeInfo node() {
        return SceneNodeInfo.newBuilder().setZoneId(1).setNodeId(2).setInstanceId("node-2")
                .setLinkHost("127.0.0.1").setLinkPort(21002).addScenes(entry).build();
    }
    private static SelectSwitchTargetRequest request() {
        return SelectSwitchTargetRequest.newBuilder().setZoneId(1).setPlayerId(42).setFromSceneNodeId(1)
                .setFromSceneId(10).setWantSceneId(20).setWantSceneConfigId(7).setClientChannelPick(true).build();
    }
    private void reject(SelectSwitchTargetRequest request, Result result) {
        assertRejected(selector().select(request), result);
        assertThat(reservations.reservationCount(1, 20)).isZero();
    }
    private static void assertRejected(SwitchTargetSelector.Selection selection, Result expected) {
        assertThat(selection.result()).isEqualTo(expected);
        assertThat(selection.response().getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);
        assertThat(selection.response().getSceneId()).isZero();
        assertThat(selection.response().getSwitchCooldownSeconds()).isZero();
        assertThat(selection.response().toString()).doesNotContain("redis-secret");
    }
}
