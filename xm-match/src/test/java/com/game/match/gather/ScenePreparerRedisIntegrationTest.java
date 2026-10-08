package com.game.match.gather;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.SceneBattleService;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.rpc.NodeRpcClients;
import com.game.discovery.NodeDirectory;
import com.game.discovery.NodeTypes;
import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.SceneAssetLocator;
import com.game.discovery.proto.PlayerLocation;
import com.game.match.gather.ScenePreparer.Endpoint;
import com.game.match.gather.ScenePreparer.Prepare;
import com.game.match.testing.FakeNodeCalls;
import com.game.match.testing.FakeSceneBattle;
import com.game.proto.PrepareBattleRequest;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 同号节点跨 zone 碰撞在<b>真 Redis</b> 上的备战定位（spectate-spec §10.4 末条、Z3；缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}）：
 * 位置记录（{@code xm:location:<pid>}）与 scene 目录（{@code xm:nodes:scene:<zone>}）都经生产用的类写进 Redis——scene 节点自己发布目录、
 * 写位置用的就是这两个类——再由生产装配的 {@link SceneAssetLocator} 读出来；只有 scene 的 Dubbo 入口是替身。钉的是键的形状：
 * 两个 zone 的目录是<b>两把不同的键</b>、字段名都是 {@code "1"}，读错键就会打到另一个 zone 的同号节点。
 *
 * <p>用 DB 13（同 xm-match 其它真 Redis 用例）；三个 zone 号与玩家号都取随机的大数，目录键只属于本用例，结束时只删自己写过的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class ScenePreparerRedisIntegrationTest {

    private static final long BATTLE = 7_000_000_650L;
    private static RedissonClient redis;
    private static PlayerLocationDirectory locations;
    private static NodeDirectory<SceneNodeInfo> scenes;

    /** 本用例的三个 zone：相邻的三个随机大号（真实的 zone 号是个位数，不会撞上）。 */
    private final int zone1 = 600_000_000 + ThreadLocalRandom.current().nextInt(100_000_000) * 3;
    private final int zone2 = zone1 + 1;
    private final int zone3 = zone1 + 2;
    private final long playerBase = (1L << 52) + ThreadLocalRandom.current().nextLong(1L << 40) * 16;
    private final long a = playerBase + 1;
    private final long b = playerBase + 2;
    private final Set<Long> writtenPlayers = new LinkedHashSet<>();

    private final FakeNodeCalls<SceneBattleService> calls = new FakeNodeCalls<>();
    private final FakeSceneBattle sceneZ1 = new FakeSceneBattle("it-scene-z1");
    private final FakeSceneBattle sceneZ2 = new FakeSceneBattle("it-scene-z2");
    private final FakeSceneBattle sceneZ3 = new FakeSceneBattle("it-scene-z3");
    private final NodeRpcClients.Target targetZ1 = new NodeRpcClients.Target("127.0.0.1", 21100, "it-scene-z1");
    private final NodeRpcClients.Target targetZ2 = new NodeRpcClients.Target("127.0.0.1", 21110, "it-scene-z2");
    private final NodeRpcClients.Target targetZ3 = new NodeRpcClients.Target("127.0.0.1", 21120, "it-scene-z3");
    private ScenePreparer preparer;

    @BeforeAll
    static void open() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(4);
        redis = Redisson.create(config);
        locations = new PlayerLocationDirectory(redis);
        scenes = new NodeDirectory<>(redis, NodeTypes.SCENE, SceneNodeInfo.parser());
    }

    @AfterAll
    static void close() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    @BeforeEach
    void publishTwoZones() {
        // 两个 zone 各一台 1 号 scene：节点号相同，实例与直连地址不同（本机双 zone 切片的 21100 / 21110）
        publish(zone1, "it-scene-z1", 21100);
        publish(zone2, "it-scene-z2", 21110);
        calls.register(targetZ1, sceneZ1);
        calls.register(targetZ2, sceneZ2);
        calls.register(targetZ3, sceneZ3);
        // 生产装配的形状：位置记录与 scene 目录都来自同一个 Redis
        preparer = new ScenePreparer(new SceneAssetLocator(locations, scenes, null), calls);
    }

    @AfterEach
    void removeMine() {
        for (long playerId : writtenPlayers) {
            redis.getKeys().delete(RedisKeys.playerLocation(playerId));
        }
        for (int zone : new int[] {zone1, zone2, zone3}) {
            // RMapCache 除了哈希本身还有记 TTL 的伴生键：整个对象一起删
            redis.getMapCache(RedisKeys.nodeDirectory(NodeTypes.SCENE, zone), StringCodec.INSTANCE).delete();
        }
    }

    private void publish(int zone, String instance, int rpcPort) {
        scenes.publish(zone, 1, SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(1).setInstanceId(instance).setLinkHost("127.0.0.1")
                .setLinkPort(rpcPort - 100).setRpcHost("127.0.0.1").setRpcPort(rpcPort).build(), Duration.ofSeconds(60));
    }

    /** 位置记录：在线、指向某个 zone 的 1 号 scene。同一次进场（epoch 相同）里写序号大的盖掉小的。 */
    private void online(long playerId, int zone, long seq) throws Exception {
        writtenPlayers.add(playerId);
        PlayerLocation at = PlayerLocation.newBuilder().setPlayerId(playerId).setZoneId(zone).setSceneNodeId(1).setSceneId(1001).setSceneConfigId(1)
                .setOwnerEpoch(5).build();
        assertThat(locations.putAsync(at, seq).toCompletableFuture().get(5, TimeUnit.SECONDS)).as("位置记录写进去了").isTrue();
    }

    private static PrepareBattleRequest request(long playerId) {
        return PrepareBattleRequest.newBuilder().setPlayerId(playerId).setBattleId(BATTLE).setBattleNodeId(3).setDeadlineMs(900_000)
                .setPrepareDeadlineMs(642_000).build();
    }

    private static Prepare.Ok ok(Prepare result) {
        assertThat(result).isInstanceOf(Prepare.Ok.class);
        return (Prepare.Ok) result;
    }

    private static Prepare.Failed failed(Prepare result) {
        assertThat(result).isInstanceOf(Prepare.Failed.class);
        return (Prepare.Failed) result;
    }

    @Test
    void 两个zone的目录是两把键_字段名都是1_各按位置记录的zone读到自己的那台() throws Exception {
        online(a, zone1, 1);
        online(b, zone2, 1);

        Prepare.Ok first = ok(preparer.prepare(request(a)));
        Prepare.Ok second = ok(preparer.prepare(request(b)));

        assertThat(first.endpoint()).isEqualTo(new Endpoint(zone1, 1, targetZ1));
        assertThat(second.endpoint()).isEqualTo(new Endpoint(zone2, 1, targetZ2));
        assertThat(sceneZ1.frozen()).as("zone 1 的 1 号只冻结了 A").containsOnlyKeys(a);
        assertThat(sceneZ2.frozen()).as("zone 2 的 1 号只冻结了 B").containsOnlyKeys(b);
        assertThat(sceneZ1.calls).extracting(FakeSceneBattle.Call::targetInstanceId).containsExactly("it-scene-z1");
        assertThat(sceneZ2.calls).extracting(FakeSceneBattle.Call::targetInstanceId).containsExactly("it-scene-z2");
        // 碰撞是存储里真实存在的：两把键、同一个字段名
        String keyZ1 = RedisKeys.nodeDirectory(NodeTypes.SCENE, zone1);
        String keyZ2 = RedisKeys.nodeDirectory(NodeTypes.SCENE, zone2);
        assertThat(keyZ1).isNotEqualTo(keyZ2);
        assertThat(redis.<String, byte[]>getMapCache(keyZ1, StringCodec.INSTANCE).readAllKeySet()).containsExactly("1");
        assertThat(redis.<String, byte[]>getMapCache(keyZ2, StringCodec.INSTANCE).readAllKeySet()).containsExactly("1");
        assertThat(scenes.list(zone1)).extracting(SceneNodeInfo::getInstanceId).containsExactly("it-scene-z1");
        assertThat(scenes.list(zone2)).extracting(SceneNodeInfo::getInstanceId).containsExactly("it-scene-z2");
    }

    @Test
    void 取消发回备战时记下的端点_位置记录此刻已指向另一个zone的同号节点也不重读() throws Exception {
        online(a, zone1, 1);
        online(b, zone2, 1);
        Prepare.Ok first = ok(preparer.prepare(request(a)));
        Prepare.Ok second = ok(preparer.prepare(request(b)));
        // 两人的位置记录对调：各自指向「另一个 zone 的 1 号」。按位置重新解析的实现会把取消发错节点
        online(a, zone2, 2);
        online(b, zone1, 2);

        assertThat(preparer.cancel(a, BATTLE, first.endpoint())).isTrue();
        assertThat(preparer.cancel(b, BATTLE, second.endpoint())).isTrue();

        assertThat(sceneZ1.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:" + a, "cancel:" + a);
        assertThat(sceneZ2.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:" + b, "cancel:" + b);
        assertThat(sceneZ1.frozen()).isEmpty();
        assertThat(sceneZ2.frozen()).isEmpty();
        assertThat(calls.remembered).as("取消经「记下来的目标」发").extracting(FakeNodeCalls.Call::target).containsExactly(targetZ1, targetZ2);
    }

    @Test
    void 排队之后旅行到第三个zone_按新的位置记录找到那里的1号() throws Exception {
        publish(zone3, "it-scene-z3", 21120);
        online(b, zone2, 1);
        online(b, zone3, 2);

        Prepare.Ok result = ok(preparer.prepare(request(b)));

        assertThat(result.endpoint()).isEqualTo(new Endpoint(zone3, 1, targetZ3));
        assertThat(sceneZ3.frozen()).containsOnlyKeys(b);
        assertThat(sceneZ2.calls).isEmpty();
        assertThat(sceneZ1.calls).isEmpty();
    }

    @Test
    void zone2的1号从目录摘掉_zone1的1号还在_B是no_location_不会落到zone1的同号节点() throws Exception {
        online(b, zone2, 1);
        scenes.remove(zone2, 1);

        Prepare.Failed result = failed(preparer.prepare(request(b)));

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NO_LOCATION);
        assertThat(result.endpoint()).isNull();
        assertThat(result.cancelNeeded()).isFalse();
        assertThat(calls.calls).as("请求没发出").isEmpty();
        assertThat(sceneZ1.calls).isEmpty();
    }

    @Test
    void 位置是重连租约_登出墓碑_没有记录_都是no_location_不发调用() throws Exception {
        long leased = playerBase + 3;
        long loggedOut = playerBase + 4;
        long missing = playerBase + 5;
        writtenPlayers.add(leased);
        writtenPlayers.add(loggedOut);
        PlayerLocation leasedAt = PlayerLocation.newBuilder().setPlayerId(leased).setZoneId(zone1).setSceneNodeId(1).setSceneId(1001).setOwnerEpoch(5)
                .build();
        assertThat(locations.leaseAsync(leasedAt, 1).toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(locations.removeAsync(loggedOut, 5, 1).toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();

        for (long playerId : new long[] {leased, loggedOut, missing}) {
            Prepare.Failed result = failed(preparer.prepare(request(playerId)));

            assertThat(result.outcome()).as("player %d", playerId).isEqualTo(GatherOutcome.NO_LOCATION);
            assertThat(result.cancelNeeded()).as("player %d", playerId).isFalse();
        }
        assertThat(calls.calls).isEmpty();
    }
}
