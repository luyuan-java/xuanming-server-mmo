package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelState;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldPlanWriteResult;
import com.game.scenemanager.SceneNodeSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/**
 * 控制面一拍（scene-channels-spec §4.4、§4.6、§4.14、§9.3 WorldChannelCoordinatorTest）：假存储 + 假目录 + 假单调时钟。
 * 写入回 −1 放弃领导、−2 下拍重算；降级清零 gauge；当选立即跑一拍；缺席表按单调时钟、换任期从零计时。
 */
class WorldChannelCoordinatorTest {

    private static final int ZONE = 1;
    private static final List<Integer> CONFS = List.of(1, 2);

    private final FakeWorldChannelStore store = new FakeWorldChannelStore();
    /** 节点号 → 目录条目。 */
    private final Map<Integer, SceneNodeInfo> directory = new TreeMap<>();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final WorldChannelMetrics metrics = new WorldChannelMetrics(meters, CONFS);
    private final SceneNodeSource source = zone -> zone == ZONE ? new ArrayList<>(directory.values()) : List.of();
    private long nanos = 1_000_000_000L;
    private long nextId = 5000;
    private boolean leaseValid = true;

    WorldChannelCoordinatorTest() {
        store.registerZone(ZONE);
    }

    private WorldChannelCoordinator coordinator(String token, WorldChannelProperties props) {
        WorldChannelPlanner planner = new WorldChannelPlanner(props, CONFS, MirrorSources.NONE,
                () -> leaseValid ? OptionalLong.of(nextId++) : OptionalLong.empty());
        return new WorldChannelCoordinator(store, source, planner, props, metrics, NodeAvailability.ALL, token, () -> nanos);
    }

    private WorldChannelCoordinator coordinator() {
        return coordinator("sm-a:1", WorldChannelProperties.defaults());
    }

    /** 目录里放一个节点，承载计划里属于它的全部 ACTIVE 记录（人数 {@code players}），已应用当前版本。 */
    private void host(int nodeId, int players) {
        SceneNodeInfo.Builder b = SceneNodeInfo.newBuilder().setZoneId(ZONE).setNodeId(nodeId).setInstanceId("inst-" + nodeId)
                .setLinkHost("127.0.0.1").setLinkPort(21000 + nodeId).setAppliedPlanVersion(store.zone(ZONE).version);
        for (WorldChannel c : store.zone(ZONE).channels.values()) {
            if (c.getNodeId() == nodeId && c.getState() == ChannelState.CHANNEL_ACTIVE) {
                b.addScenes(SceneEntry.newBuilder().setSceneId(c.getSceneId()).setSceneConfigId(c.getSceneConfigId())
                        .setPlayerCount(players));
            }
        }
        directory.put(nodeId, b.build());
    }

    private double ticks(String result) {
        return meters.get(WorldChannelMetrics.TICKS).tag("result", result).counter().count();
    }

    private double gauge(String name, String... tags) {
        return meters.get(name).tags(tags).gauge().value();
    }

    private List<WorldChannel> channelsOn(int nodeId) {
        return store.zone(ZONE).channels.values().stream().filter(c -> c.getNodeId() == nodeId).toList();
    }

    @Test
    void 当选后立即跑一拍_按覆盖铺好_计ok() {
        host(10, 0);
        WorldChannelCoordinator c = coordinator();

        c.tick();

        assertThat(store.zone(ZONE).leader).isEqualTo("sm-a:1");
        assertThat(c.ledZones()).containsExactly(ZONE);
        assertThat(channelsOn(10)).hasSize(2);
        assertThat(store.zone(ZONE).version).isEqualTo(store.nowMs);
        assertThat(store.zone(ZONE).desired).containsEntry(1, 1).containsEntry(2, 1);
        assertThat(ticks("ok")).isEqualTo(1);
        assertThat(gauge(WorldChannelMetrics.LEADER_ZONES)).isEqualTo(1);
        assertThat(gauge(WorldChannelMetrics.CHANNELS, "scene_config", "1", "state", "active")).isEqualTo(1);
        assertThat(meters.get(WorldChannelMetrics.TICK).timer().count()).isEqualTo(1);
    }

    @Test
    void 收敛后不再写入_ver不动() {
        host(10, 0);
        WorldChannelCoordinator c = coordinator();
        c.tick();
        host(10, 0);

        c.tick();
        c.tick();

        assertThat(store.writes).hasSize(1);
        assertThat(store.zone(ZONE).version).isEqualTo(store.nowMs);
        assertThat(ticks("ok")).isEqualTo(3);
    }

    @Test
    void 没有活节点时计no_nodes() {
        coordinator().tick();

        assertThat(ticks("no_nodes")).isEqualTo(1);
        assertThat(store.writes).isEmpty();
    }

    @Test
    void 发号租约无效时计no_lease且不新建() {
        leaseValid = false;
        host(10, 0);

        coordinator().tick();

        assertThat(ticks("no_lease")).isEqualTo(1);
        assertThat(store.zone(ZONE).channels).isEmpty();
    }

    @Test
    void 别人持锁时只计not_leader_不读快照() {
        store.zone(ZONE).leader = "sm-b:2";
        host(10, 0);
        WorldChannelCoordinator c = coordinator();

        c.tick();

        assertThat(ticks("not_leader")).isEqualTo(1);
        assertThat(store.snapshots).isZero();
        assertThat(store.writes).isEmpty();
        assertThat(c.ledZones()).isEmpty();
        assertThat(gauge(WorldChannelMetrics.LEADER_ZONES)).isZero();
    }

    @Test
    void 写入回负1_立即降级并清掉该zone的gauge() {
        host(10, 0);
        WorldChannelCoordinator c = coordinator();
        c.tick();
        host(10, 0);
        host(20, 0); // 新节点：要写
        store.forcedWriteResult = WorldPlanWriteResult.FENCED;

        c.tick();

        assertThat(ticks("fenced")).isEqualTo(1);
        assertThat(c.ledZones()).isEmpty();
        assertThat(gauge(WorldChannelMetrics.CHANNELS, "scene_config", "1", "state", "active")).isZero();
        assertThat(gauge(WorldChannelMetrics.LEADER_ZONES)).isZero();
    }

    @Test
    void 写入回负2_本拍作废_下拍重读重算() {
        host(10, 0);
        WorldChannelCoordinator c = coordinator();
        store.forcedWriteResult = WorldPlanWriteResult.CONFLICT;

        c.tick();
        assertThat(ticks("conflict")).isEqualTo(1);
        assertThat(store.zone(ZONE).channels).isEmpty();
        assertThat(c.ledZones()).containsExactly(ZONE);

        c.tick();
        assertThat(ticks("ok")).isEqualTo(1);
        assertThat(channelsOn(10)).hasSize(2);
    }

    @Test
    void 续期发现锁已被夺_立即降级_下拍不规划() {
        host(10, 0);
        WorldChannelCoordinator c = coordinator();
        c.tick();
        store.zone(ZONE).leader = "sm-b:2";

        c.renewLeaders();

        assertThat(c.ledZones()).isEmpty();
        assertThat(gauge(WorldChannelMetrics.CHANNELS, "scene_config", "1", "state", "active")).isZero();
        int snapshots = store.snapshots;
        c.tick();
        assertThat(store.snapshots).isEqualTo(snapshots);
        assertThat(ticks("not_leader")).isEqualTo(1);
    }

    @Test
    void 快照失败_计error_什么也不写() {
        host(10, 0);
        store.snapshotFailure = new IllegalStateException("redis down");

        coordinator().tick();

        assertThat(ticks("error")).isEqualTo(1);
        assertThat(store.writes).isEmpty();
    }

    @Test
    void 读zone集合失败计error() {
        store.zonesFailure = new IllegalStateException("redis down");

        coordinator().tick();

        assertThat(ticks("error")).isEqualTo(1);
    }

    @Test
    void 写入抛异常_计error_开始执行的扩缩容决定计error() {
        WorldChannelProperties props = PlanFixture.props(Map.of("autoscale.enabled", "true"));
        host(10, 0);
        WorldChannelCoordinator c = coordinator("sm-a:1", props);
        c.tick();
        host(10, 3000);
        nanos += Duration.ofSeconds(30).toNanos();
        store.writeFailure = new IllegalStateException("timeout");

        c.tick();

        assertThat(ticks("error")).isEqualTo(1);
        assertThat(meters.get(WorldChannelMetrics.AUTOSCALE).tag("action", "scale_out").tag("outcome", "error")
                .counter().count()).isEqualTo(2); // 两张图都满了
        assertThat(meters.get(WorldChannelMetrics.AUTOSCALE).tag("action", "scale_out").tag("outcome", "ok")
                .counter().count()).isZero();
    }

    @Test
    void 死节点按单调时钟满宽限期才删并在活节点重铺() {
        host(10, 0);
        host(20, 0);
        WorldChannelCoordinator c = coordinator();
        c.tick();
        assertThat(channelsOn(20)).hasSize(2);
        host(10, 0);
        directory.remove(20);

        nanos += Duration.ofSeconds(1).toNanos(); // 首次看到缺席
        c.tick();
        nanos += Duration.ofSeconds(18).toNanos();
        c.tick();
        assertThat(channelsOn(20)).hasSize(2);
        assertThat(gauge(WorldChannelMetrics.REBALANCE_PENDING, "reason", "node_gone")).isEqualTo(2);

        nanos += Duration.ofSeconds(2).toNanos(); // 缺席 20 s
        c.tick();
        assertThat(channelsOn(20)).isEmpty();
        assertThat(channelsOn(10)).hasSize(2); // 期望数 1，节点 10 上本来就有，不必补
        assertThat(gauge(WorldChannelMetrics.REBALANCE_PENDING, "reason", "node_gone")).isZero();
        assertThat(meters.get(WorldChannelMetrics.REBALANCE_MIGRATIONS).tag("reason", "node_gone").tag("outcome", "done")
                .counter().count()).isEqualTo(2);
    }

    @Test
    void 换任期后缺席表从零计时() {
        host(10, 0);
        host(20, 0);
        WorldChannelCoordinator c = coordinator();
        c.tick();
        host(10, 0);
        directory.remove(20);
        nanos += Duration.ofSeconds(1).toNanos();
        c.tick(); // 缺席起点
        store.zone(ZONE).leader = "sm-b:2";
        c.renewLeaders(); // 降级
        store.zone(ZONE).leader = null; // 对方也走了

        nanos += Duration.ofSeconds(25).toNanos();
        c.tick(); // 重新当选：从零计时
        assertThat(c.ledZones()).containsExactly(ZONE);
        assertThat(channelsOn(20)).hasSize(2);

        nanos += Duration.ofSeconds(20).toNanos();
        c.tick();
        assertThat(channelsOn(20)).isEmpty();
    }

    @Test
    void 扩缩容按check_interval节拍决策() {
        WorldChannelProperties props = PlanFixture.props(Map.of("autoscale.enabled", "true"));
        host(10, 0);
        WorldChannelCoordinator c = coordinator("sm-a:1", props);
        c.tick(); // t=0：铺好（本拍也跑过决策：负载集为空）
        host(10, 3000);

        nanos += Duration.ofSeconds(5).toNanos();
        c.tick();
        assertThat(store.zone(ZONE).desired).containsEntry(1, 1);

        nanos += Duration.ofSeconds(25).toNanos(); // 距上次决策 30 s
        c.tick();
        assertThat(store.zone(ZONE).desired).containsEntry(1, 2).containsEntry(2, 2);
        assertThat(channelsOn(10)).hasSize(4);
        assertThat(meters.get(WorldChannelMetrics.AUTOSCALE).tag("action", "scale_out").tag("outcome", "ok")
                .counter().count()).isEqualTo(2);
    }

    @Test
    void 发号租约确认丢失_放锁并停止竞选() {
        host(10, 0);
        WorldChannelCoordinator c = coordinator();
        c.tick();

        c.onIdLeaseLost();

        assertThat(store.zone(ZONE).leader).isNull();
        assertThat(c.isStopped()).isTrue();
        int snapshots = store.snapshots;
        c.tick();
        assertThat(store.snapshots).isEqualTo(snapshots);
        assertThat(store.zone(ZONE).leader).isNull();
        assertThat(gauge(WorldChannelMetrics.LEADER_ZONES)).isZero();
    }

    @Test
    void 停服放锁只删自己的() {
        host(10, 0);
        WorldChannelCoordinator a = coordinator();
        a.tick();
        WorldChannelCoordinator b = coordinator("sm-b:2", WorldChannelProperties.defaults());
        b.tick(); // 跟随者

        b.releaseAll();
        assertThat(store.zone(ZONE).leader).isEqualTo("sm-a:1");

        a.releaseAll();
        assertThat(store.zone(ZONE).leader).isNull();
    }

    @Test
    void 两个协调者轮流跑_频道数不翻倍() {
        host(10, 0);
        host(20, 0);
        WorldChannelCoordinator a = coordinator();
        WorldChannelCoordinator b = coordinator("sm-b:2", WorldChannelProperties.defaults());

        for (int i = 0; i < 3; i++) {
            a.tick();
            b.tick();
            host(10, 0);
            host(20, 0);
        }

        assertThat(store.zone(ZONE).channels).hasSize(4);
        assertThat(store.writes).hasSize(1);
    }
}
