package com.game.scenemanager.travel;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.RedirectToZoneRequest;
import com.game.api.proto.RedirectToZoneResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SelectTravelTargetRequest;
import com.game.api.proto.SelectTravelTargetResponse;
import com.game.api.proto.WorldChannel;
import com.game.common.token.GateTokenIssuer;
import com.game.common.token.GateTokens;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.drain.GateDrainMarks;
import com.game.discovery.gate.RedisGateSource;
import com.game.discovery.world.RedissonWorldChannelStore;
import com.game.discovery.world.WorldChannelStore;
import com.game.discovery.world.WorldPlanBatch;
import com.game.discovery.world.WorldPlanWriteResult;
import com.game.discovery.zone.ZoneMergeFence;
import com.game.proto.GateTokenPayload;
import com.game.scenemanager.RedisSceneNodeSource;
import com.game.scenemanager.SceneAssigner;
import com.game.scenemanager.WorldSceneConfigs;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 跨 zone 选路连真 Redis（批次 5.4，zone-travel-spec §11.4、§11.8）：频道计划经 {@link RedissonWorldChannelStore} 的领导者写入、
 * scene / gate 节点按各自进程的方式写目录、运维排空标记经 {@link GateDrainMarks}，{@link TravelRouter} 读出来完成地图预检、
 * 同地址去重与剔排空、签票据；票据在目标 gate 上验得过。默认跳过；显式开启：{@code -Dgame.it.redis=redis://127.0.0.1:6379}。
 * 用 DB 9 与随机大号 zone 隔离，结束时只删自己写的键。
 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class TravelRouterRedisIntegrationTest {

    private static final int FROM = 1;
    private static final long PLAYER = 4242;
    private static final String SECRET = "travel-it-secret";
    private static final WorldSceneConfigs WORLD = new WorldSceneConfigs(1, Set.of(1, 2));

    private RedissonClient redis;
    private int zone;
    private WorldChannelStore store;
    private NodeDirectory<GateNodeInfo> gateDirectory;
    private NodeDirectory<SceneNodeInfo> sceneDirectory;
    private GateDrainMarks marks;
    private TravelRouter router;

    @BeforeEach
    void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(9);
        redis = Redisson.create(config);
        zone = ThreadLocalRandom.current().nextInt(100_000_000, 2_000_000_000);
        store = new RedissonWorldChannelStore(redis);
        gateDirectory = new NodeDirectory<>(redis, NodeTypes.GATE, GateNodeInfo.parser());
        sceneDirectory = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
        marks = new GateDrainMarks(redis);
        router = new TravelRouter(store, new RedisSceneNodeSource(redis), new RedisGateSource(redis), WORLD, ZoneMergeFence.OPEN,
                new GateTokenIssuer(GateTokens.ofUtf8(SECRET), Clock.systemUTC(), new SecureRandom()), new SimpleMeterRegistry());
    }

    @AfterEach
    void cleanup() {
        for (int node = 1; node <= 4; node++) {
            gateDirectory.remove(zone, node);
            sceneDirectory.remove(zone, node);
            marks.clear(zone, node);
        }
        redis.getKeys().deleteByPattern("game:world:{z:" + Integer.toUnsignedString(zone) + "}:*");
        redis.shutdown();
    }

    /** 以领导者身份写一版计划：默认主世界 ACTIVE、地图 2 只有 DRAINING 记录。 */
    private void writePlan() {
        String token = "it-" + zone;
        assertThat(store.tryAcquireLeader(zone, token, Duration.ofSeconds(30))).isTrue();
        WorldPlanBatch batch = new WorldPlanBatch(0)
                .putChannel(channel(9_000_001L, 1, ChannelState.CHANNEL_ACTIVE))
                .putChannel(channel(9_000_002L, 2, ChannelState.CHANNEL_DRAINING));
        assertThat(store.write(zone, token, batch)).isEqualTo(WorldPlanWriteResult.written(1));
        store.releaseLeader(zone, token);
    }

    private static WorldChannel channel(long sceneId, int conf, ChannelState state) {
        return WorldChannel.newBuilder().setSceneId(sceneId).setSceneConfigId(conf).setNodeId(1).setState(state)
                .setKind(ChannelKind.CHANNEL_KIND_WORLD).build();
    }

    private GateNodeInfo gate(int node, String host, int players, long startedAtMs) {
        return GateNodeInfo.newBuilder().setZoneId(zone).setNodeId(node).setInstanceId("it-g" + node)
                .setClientHost(host).setClientPort(11010).setPlayerCount(players).setStartedAtMs(startedAtMs).build();
    }

    private void publishSceneNode() {
        sceneDirectory.publish(zone, 1, SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(1).setInstanceId("it-s1")
                .setLinkHost("127.0.0.1").setLinkPort(21010)
                .addScenes(SceneEntry.newBuilder().setSceneId(9_000_001L).setSceneConfigId(1)).build(), Duration.ofSeconds(30));
    }

    @Test
    void 传送_读真计划与真gate目录_同地址去重剔排空_票据在目标gate上验得过() throws Exception {
        writePlan();
        // 节点 1：同地址的陈旧条目（人数最少）；节点 2：同地址重启后的新进程；节点 3：人数最少但被标排空；节点 4：另一台
        gateDirectory.publish(zone, 1, gate(1, "10.8.0.1", 0, 1_000L), Duration.ofSeconds(30));
        gateDirectory.publish(zone, 2, gate(2, "10.8.0.1", 20, 2_000L), Duration.ofSeconds(30));
        gateDirectory.publish(zone, 3, gate(3, "10.8.0.3", 0, 2_000L), Duration.ofSeconds(30));
        gateDirectory.publish(zone, 4, gate(4, "10.8.0.4", 30, 2_000L), Duration.ofSeconds(30));
        marks.mark(zone, 3, "it-g3", Duration.ofSeconds(60), Map.of(2, "it-g2", 4, "it-g4"));

        SelectTravelTargetResponse response = router.selectTravelTarget(SelectTravelTargetRequest.newBuilder()
                .setFromZoneId(FROM).setToZoneId(zone).setPlayerId(PLAYER).build()).get(5, TimeUnit.SECONDS);

        assertThat(response.getTipId()).isZero();
        assertThat(response.getSceneConfigId()).isEqualTo(1);
        assertThat(response.getRedirect().getGateNodeId()).isEqualTo(2);
        assertThat(response.getRedirect().getGateHost()).isEqualTo("10.8.0.1");
        assertThat(response.getRedirect().getGatePort()).isEqualTo(11010);
        GateTokenPayload payload = GateTokenPayload.parseFrom(response.getRedirect().getTokenPayload());
        assertThat(payload.getPlayerId()).isEqualTo(PLAYER);
        assertThat(payload.getTargetZoneId()).isEqualTo(zone);
        assertThat(GateTokens.ofUtf8(SECRET).verify(response.getRedirect().getTokenPayload(),
                response.getRedirect().getTokenSignature(), 2, zone, Instant.now().getEpochSecond()).ok()).isTrue();

        // 只有 DRAINING 记录的图照样放行；计划里没有的图拒绝
        SelectTravelTargetResponse draining = router.selectTravelTarget(SelectTravelTargetRequest.newBuilder()
                .setFromZoneId(FROM).setToZoneId(zone).setPlayerId(PLAYER).setWantSceneConfigId(2).build()).get(5, TimeUnit.SECONDS);
        assertThat(draining.getTipId()).isZero();
        assertThat(draining.getSceneConfigId()).isEqualTo(2);
    }

    @Test
    void 传送_计划里没有这张图回无场景() throws Exception {
        gateDirectory.publish(zone, 1, gate(1, "10.8.0.1", 0, 1_000L), Duration.ofSeconds(30));

        SelectTravelTargetResponse response = router.selectTravelTarget(SelectTravelTargetRequest.newBuilder()
                .setFromZoneId(FROM).setToZoneId(zone).setPlayerId(PLAYER).build()).get(5, TimeUnit.SECONDS);

        assertThat(response.getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);
    }

    @Test
    void 重定向_读真scene节点目录_没有节点拒_有节点选gate签票() throws Exception {
        gateDirectory.publish(zone, 1, gate(1, "10.8.0.1", 5, 1_000L), Duration.ofSeconds(30));
        RedirectToZoneRequest request = RedirectToZoneRequest.newBuilder().setFromZoneId(FROM).setToZoneId(zone)
                .setPlayerId(PLAYER).build();

        assertThat(router.redirectToZone(request).get(5, TimeUnit.SECONDS).getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);

        publishSceneNode();
        RedirectToZoneResponse response = router.redirectToZone(request).get(5, TimeUnit.SECONDS);
        assertThat(response.getTipId()).isZero();
        assertThat(response.getRedirect().getGateNodeId()).isEqualTo(1);
        assertThat(GateTokens.ofUtf8(SECRET).verify(response.getRedirect().getTokenPayload(),
                response.getRedirect().getTokenSignature(), 1, zone, Instant.now().getEpochSecond()).ok()).isTrue();
    }
}
