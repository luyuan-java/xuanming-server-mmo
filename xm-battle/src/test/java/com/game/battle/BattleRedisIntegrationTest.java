package com.game.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.proto.BattleNodeInfo;
import com.game.api.proto.SceneNodeInfo;
import com.game.battle.admin.DevRoutingResolver;
import com.game.battle.admission.AdmissionGate;
import com.game.battle.admission.AdmissionPhase;
import com.game.battle.directory.BattleDirectoryPublisher;
import com.game.battle.edge.DirectEdge;
import com.game.battle.edge.EdgeDependencies;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.port.LoggingActivityResultSink;
import com.game.battle.port.LoggingBattleResultSink;
import com.game.battle.port.LoggingSceneBattleEvents;
import com.game.battle.port.LoggingSettlementSink;
import com.game.battle.room.BattleClock;
import com.game.battle.room.BattleRoomService;
import com.game.battle.room.RoomDependencies;
import com.game.common.RunMode;
import com.game.common.token.BattleTickets;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.presence.PlayerPresenceDirectory;
import com.game.discovery.proto.PlayerLocation;
import com.game.discovery.proto.PlayerPresence;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.CreateBattleRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 连真 Redis 的节点身份、目录与 dev 路由补全（battle-node-spec §7.10、§7.12、§13.6；缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 14）。
 * 租约与目录用生产实现（{@link BattleInfrastructure#production}），房间 / 直连面 / 控制面导出用假实现：核对作用域 0 的键、目录条目与 TTL、
 * 真实的租约丢失（键被别的实例占走 → 续期发现 → 关闸、删本实例条目、房间不作废）与停机删条目、交还租约。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class BattleRedisIntegrationTest {

    private static final int DB = 14;
    private static final long PLAYER = 7_700_001L;

    private RedissonClient redis;
    private BattleNode node;

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB);
        redis = Redisson.create(config);
        cleanUp();
    }

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop();
        }
        cleanUp();
        redis.shutdown();
    }

    private void cleanUp() {
        redis.getKeys().deleteByPattern("xm:node-id:battle:0:*");
        redis.getKeys().deleteByPattern("xm:node-id-epoch:battle:0:*");
        redis.getKeys().delete(RedisKeys.nodeDirectory(NodeTypes.BATTLE, 0), RedisKeys.nodeDirectory(NodeTypes.SCENE, 1),
                RedisKeys.presence(PLAYER), RedisKeys.playerLocation(PLAYER));
    }

    /** 租约与目录是真的，其余借用假实现。 */
    private static final class HybridInfrastructure implements BattleInfrastructure {
        final BattleInfrastructure real;
        final FakeBattleInfrastructure fake = new FakeBattleInfrastructure();

        HybridInfrastructure(RedissonClient redis) {
            this.real = BattleInfrastructure.production(redis);
        }

        @Override
        public Lease acquireLease(ScheduledExecutorService scheduler, String instanceId, Runnable onLost) {
            return real.acquireLease(scheduler, instanceId, onLost);
        }

        @Override
        public BattleDirectoryPublisher.Directory directory(int nodeId) {
            return real.directory(nodeId);
        }

        @Override
        public RpcExport exportRpc(BattleNodeService provider, String host, int port) {
            return fake.exportRpc(provider, host, port);
        }

        @Override
        public BattleRoomService rooms(RoomDependencies deps) {
            return fake.rooms(deps);
        }

        @Override
        public DirectEdge edge(EdgeDependencies deps) {
            return fake.edge(deps);
        }
    }

    private BattleNode start(HybridInfrastructure infra, AdmissionGate admission, BattleMetrics metrics) {
        node = new BattleNode(BattleProperties.defaults(), RunMode.DEV, "127.0.0.1", BattleNodeTest.tables(),
                BattleTickets.ofUtf8("x".repeat(40)),
                new OutboundPorts((pid, contents) -> { }, new LoggingSceneBattleEvents(metrics), new LoggingSettlementSink(metrics),
                        new LoggingActivityResultSink(metrics), new LoggingBattleResultSink(metrics)),
                admission, metrics, BattleClock.SYSTEM, infra);
        node.start();
        return node;
    }

    private List<BattleNodeInfo> directoryEntries() {
        return new NodeDirectory<>(redis, NodeTypes.BATTLE, BattleNodeInfo.parser()).list(0);
    }

    private static void eventually(Duration within, Runnable assertion) throws InterruptedException {
        long deadline = System.nanoTime() + within.toNanos();
        while (true) {
            try {
                assertion.run();
                return;
            } catch (AssertionError e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(50);
            }
        }
    }

    @Test
    void 租约作用域0_目录条目accepting_停机删条目并交还租约() {
        AdmissionGate admission = new AdmissionGate();
        BattleNode n = start(new HybridInfrastructure(redis), admission, new BattleMetrics(new SimpleMeterRegistry()));
        int nodeId = n.identity().orElseThrow().nodeId();

        String leaseKey = RedisKeys.nodeId(NodeTypes.BATTLE, 0, nodeId);
        assertThat(leaseKey).isEqualTo("xm:node-id:battle:0:" + nodeId);
        assertThat(redis.<String>getBucket(leaseKey, StringCodec.INSTANCE).get()).isEqualTo(n.instanceId());
        assertThat(redis.getBucket(leaseKey).remainTimeToLive()).isBetween(1L, 15_000L);
        assertThat(redis.getAtomicLong(RedisKeys.nodeIdEpoch(NodeTypes.BATTLE, 0, nodeId)).get()).isPositive();

        assertThat(directoryEntries()).singleElement().satisfies(info -> {
            assertThat(info.getNodeId()).isEqualTo(nodeId);
            assertThat(info.getInstanceId()).isEqualTo(n.instanceId());
            assertThat(info.getAccepting()).isTrue();
            assertThat(info.getRpcPort()).isEqualTo(21234);
            assertThat(info.getClientPort()).isEqualTo(12000);
        });

        n.stop();
        node = null;
        assertThat(directoryEntries()).as("停机删条目").isEmpty();
        assertThat(redis.getBucket(leaseKey).isExists()).as("交还租约").isFalse();
        assertThat(admission.phase()).isEqualTo(AdmissionPhase.CLOSED);
    }

    @Test
    void 真实租约丢失_续期发现被占_关闸_删本实例条目_不作废房间() throws Exception {
        AdmissionGate admission = new AdmissionGate();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HybridInfrastructure infra = new HybridInfrastructure(redis);
        BattleNode n = start(infra, admission, new BattleMetrics(registry));
        int nodeId = n.identity().orElseThrow().nodeId();
        infra.fake.rooms.rooms.set(1);

        // 别的实例占走了这个号（模拟 Redis 故障期间键过期后被抢）：下一次续期（≤ TTL/3 = 5 s）发现「号已不属于本实例」
        redis.<String>getBucket(RedisKeys.nodeId(NodeTypes.BATTLE, 0, nodeId), StringCodec.INSTANCE)
                .set("someone-else", Duration.ofSeconds(15));

        eventually(Duration.ofSeconds(8), () -> assertThat(admission.phase()).isEqualTo(AdmissionPhase.CLOSED));
        eventually(Duration.ofSeconds(2), () -> assertThat(directoryEntries()).isEmpty());
        assertThat(registry.get("xm.battle.lease.lost").counter().count()).isEqualTo(1);
        assertThat(infra.fake.rooms.calls).noneMatch(c -> c.startsWith("abortAll"));
        assertThat(n.isRunning()).isTrue();

        n.stop();
        node = null;
        assertThat(redis.<String>getBucket(RedisKeys.nodeId(NodeTypes.BATTLE, 0, nodeId), StringCodec.INSTANCE).get())
                .as("丢失后交还不删别人的键").isEqualTo("someone-else");
    }

    @Test
    void dev路由补全_读在线目录_位置记录_scene目录() throws Exception {
        new PlayerPresenceDirectory(redis).putAsync(PlayerPresence.newBuilder().setPlayerId(PLAYER).setZoneId(1)
                .setGateNodeId(2).setGateInstanceId("gate-inst").setSessionId(33).build()).toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        new PlayerLocationDirectory(redis).putAsync(PlayerLocation.newBuilder().setPlayerId(PLAYER).setZoneId(1)
                .setSceneNodeId(4).setSceneId(99).setOwnerEpoch(1).build(), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser()).publish(1, 4, SceneNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(4).setInstanceId("scene-inst").build(), Duration.ofSeconds(15));

        DevRoutingResolver.Resolution<CreateBattleRequest> r = DevRoutingResolver.redis(redis).fillCreate(
                CreateBattleRequest.newBuilder().setBattleId(1).addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(PLAYER))
                        .build());

        assertThat(r.ok()).isTrue();
        assertThat(r.request().getPlayers(0).getRouting()).isEqualTo(BattleRouting.newBuilder().setSessionId(33)
                .setGateNodeId(2).setGateInstanceId("gate-inst").setZoneId(1).setSceneNodeId(4).setSceneInstanceId("scene-inst")
                .build());

        DevRoutingResolver.Resolution<CreateBattleRequest> offline = DevRoutingResolver.redis(redis).fillCreate(
                CreateBattleRequest.newBuilder().addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(PLAYER + 1)).build());
        assertThat(offline.ok()).isFalse();
    }
}
