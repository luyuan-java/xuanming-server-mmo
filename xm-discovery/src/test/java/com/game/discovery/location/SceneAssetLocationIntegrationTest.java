package com.game.discovery.location;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneNodeInfo;
import com.game.discovery.NodeDirectory;
import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.NoHolder;
import com.game.discovery.location.SceneAssetLocator.ResolveResult;
import com.game.discovery.proto.PlayerLocation;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.config.Config;

/**
 * 资产通道定位的真 Redis 集成测试（guild-economy-spec §11.4）：{@link PlayerLocationDirectory#findHolderAsync} 的各状态与乱序写、
 * {@link NodeDirectory#findAsync} 的单条读与 TTL 过期、{@code rpc_host / rpc_port} 的发布与摘除，以及两者拼起来的 {@link SceneAssetLocator}。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 10、随机大号 player_id（含 ≥ 2^63）与随机 zone 隔离，
 * 只删自己写的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class SceneAssetLocationIntegrationTest {

    private static final long BASE = Long.MIN_VALUE + (1L << 52) + ThreadLocalRandom.current().nextLong(1L << 40);
    /** 随机 zone：节点目录键 xm:nodes:scene:{zone} 只属于本测试。 */
    private static final int ZONE = 900_000 + ThreadLocalRandom.current().nextInt(90_000);
    private static final String TYPE = "scene";

    private static RedissonClient redis;
    private static PlayerLocationDirectory locations;
    private static NodeDirectory<SceneNodeInfo> scenes;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(10);
        redis = Redisson.create(config);
        locations = new PlayerLocationDirectory(redis);
        scenes = new NodeDirectory<>(redis, TYPE, SceneNodeInfo.parser());
    }

    @AfterAll
    static void cleanup() {
        for (long i = 0; i < 20; i++) {
            redis.getKeys().delete(RedisKeys.playerLocation(BASE + i));
        }
        redis.getKeys().delete(RedisKeys.nodeDirectory(TYPE, ZONE));
        redis.shutdown();
    }

    private static PlayerLocation at(long player, int node, long epoch) {
        return PlayerLocation.newBuilder().setPlayerId(player).setZoneId(ZONE).setSceneNodeId(node).setSceneId(100)
                .setSceneConfigId(2).setOwnerEpoch(epoch).build();
    }

    private static HolderRead holder(long player) throws Exception {
        return locations.findHolderAsync(player).get(5, TimeUnit.SECONDS);
    }

    private static SceneNodeInfo node(int node, String instance, int rpcPort) {
        return SceneNodeInfo.newBuilder().setZoneId(ZONE).setNodeId(node).setInstanceId(instance).setLinkHost("127.0.0.1")
                .setLinkPort(21000).setRpcHost(rpcPort == 0 ? "" : "127.0.0.1").setRpcPort(rpcPort).build();
    }

    @Test
    void 严格读_在线租约墓碑没有与各种损坏() throws Exception {
        long online = BASE;
        long leased = BASE + 1;
        long loggedOut = BASE + 2;
        long missing = BASE + 3;
        long badState = BASE + 4;
        long wrongType = BASE + 5;
        long corrupt = BASE + 6;
        long mismatched = BASE + 7;
        locations.putAsync(at(online, 3, 5), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        locations.leaseAsync(at(leased, 3, 5), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        locations.removeAsync(loggedOut, 5, 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        redis.<String, String>getMap(RedisKeys.playerLocation(badState), StringCodec.INSTANCE).put("s", "z");
        redis.<String>getBucket(RedisKeys.playerLocation(wrongType), StringCodec.INSTANCE).set("not-a-hash");
        var raw = new CompositeCodec(StringCodec.INSTANCE, ByteArrayCodec.INSTANCE);
        redis.<String, byte[]>getMap(RedisKeys.playerLocation(corrupt), raw).put("s", "o".getBytes());
        redis.<String, byte[]>getMap(RedisKeys.playerLocation(corrupt), raw).put("v", new byte[] {(byte) 0xff, 1});
        redis.<String, byte[]>getMap(RedisKeys.playerLocation(mismatched), raw).put("s", "o".getBytes());
        redis.<String, byte[]>getMap(RedisKeys.playerLocation(mismatched), raw).put("v", at(online, 3, 5).toByteArray());

        assertThat(holder(online)).isEqualTo(new HolderRead(LocationStatus.ONLINE, at(online, 3, 5), null));
        assertThat(holder(leased).status()).isEqualTo(LocationStatus.RECONNECT_LEASE);
        assertThat(holder(leased).location()).isNull();
        assertThat(holder(loggedOut).status()).isEqualTo(LocationStatus.LOGGED_OUT);
        assertThat(holder(missing).status()).isEqualTo(LocationStatus.MISSING);
        assertThat(holder(badState).status()).isEqualTo(LocationStatus.ERROR);
        assertThat(holder(wrongType).status()).as("键类型不对（WRONGTYPE）").isEqualTo(LocationStatus.ERROR);
        assertThat(holder(corrupt).status()).isEqualTo(LocationStatus.ERROR);
        assertThat(holder(mismatched).status()).as("值里的 player_id 与键不符").isEqualTo(LocationStatus.ERROR);
    }

    @Test
    void 乱序写_旧写晚到不覆盖_读到的总是最新的那次() throws Exception {
        long player = BASE + 8;
        // 同一次进场（epoch 5）：断线（序号 2）先落地，进场（序号 1）晚到被丢弃
        locations.leaseAsync(at(player, 3, 5), 2).toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertThat(locations.putAsync(at(player, 3, 5), 1).toCompletableFuture().get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(holder(player).status()).isEqualTo(LocationStatus.RECONNECT_LEASE);
        // 新的进场（epoch 6）在别的节点上线：覆盖
        assertThat(locations.putAsync(at(player, 4, 6), 1).toCompletableFuture().get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(holder(player).location().getSceneNodeId()).isEqualTo(4);
        // 旧进场的登出墓碑晚到：不覆盖
        assertThat(locations.removeAsync(player, 5, 9).toCompletableFuture().get(5, TimeUnit.SECONDS)).isFalse();
        assertThat(holder(player).status()).isEqualTo(LocationStatus.ONLINE);
    }

    @Test
    void 节点目录单条读_rpc地址发布与摘除_TTL过期_坏条目是故障() throws Exception {
        scenes.publish(ZONE, 11, node(11, "inst-a", 21100), Duration.ofSeconds(30));
        Optional<SceneNodeInfo> found = scenes.findAsync(ZONE, 11).get(5, TimeUnit.SECONDS);
        assertThat(found).isPresent();
        assertThat(found.get().getRpcHost()).isEqualTo("127.0.0.1");
        assertThat(found.get().getRpcPort()).isEqualTo(21100);
        assertThat(scenes.list(ZONE)).as("整表读照旧").extracting(SceneNodeInfo::getNodeId).contains(11);
        assertThat(scenes.findAsync(ZONE, 12).get(5, TimeUnit.SECONDS)).isEmpty();

        scenes.remove(ZONE, 11);
        assertThat(scenes.findAsync(ZONE, 11).get(5, TimeUnit.SECONDS)).as("摘除后读不到").isEmpty();

        scenes.publish(ZONE, 13, node(13, "inst-b", 21101), Duration.ofMillis(300));
        assertThat(scenes.findAsync(ZONE, 13).get(5, TimeUnit.SECONDS)).isPresent();
        Thread.sleep(600);
        assertThat(scenes.findAsync(ZONE, 13).get(5, TimeUnit.SECONDS)).as("TTL 过期").isEmpty();

        redis.getMapCache(RedisKeys.nodeDirectory(TYPE, ZONE), new CompositeCodec(StringCodec.INSTANCE, ByteArrayCodec.INSTANCE))
                .fastPut("14", new byte[] {(byte) 0xff, 0x01}, 30, TimeUnit.SECONDS);
        assertThatThrownBy(() -> scenes.findAsync(ZONE, 14).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasMessageContaining("解析失败");
        scenes.remove(ZONE, 14);
    }

    @Test
    void 定位端到端_位置记录加节点目录() throws Exception {
        SceneAssetLocator locator = new SceneAssetLocator(locations, scenes, null);
        long player = BASE + 9;
        long leased = BASE + 10;
        locations.putAsync(at(player, 21, 5), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        locations.leaseAsync(at(leased, 21, 5), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertThat(locator.resolveAsync(player).get(5, TimeUnit.SECONDS)).as("节点还没发布")
                .isEqualTo(new NoHolder(ResolveResult.NODE_UNKNOWN));
        scenes.publish(ZONE, 21, node(21, "inst-old", 0), Duration.ofSeconds(30));
        assertThat(locator.resolveAsync(player).get(5, TimeUnit.SECONDS)).as("旧版本节点不提供资产通道")
                .isEqualTo(new NoHolder(ResolveResult.NO_RPC_PORT));
        scenes.publish(ZONE, 21, node(21, "inst-new", 21100), Duration.ofSeconds(30));
        assertThat(locator.resolveAsync(player).get(5, TimeUnit.SECONDS)).isEqualTo(new Found(at(player, 21, 5),
                new SceneAssetEndpoint(ZONE, 21, "inst-new", "127.0.0.1", 21100)));
        assertThat(locator.resolveAsync(leased).get(5, TimeUnit.SECONDS)).isEqualTo(new NoHolder(ResolveResult.LEASE));
        assertThat(locator.resolveAsync(BASE + 11).get(5, TimeUnit.SECONDS))
                .isEqualTo(new NoHolder(ResolveResult.NOT_ONLINE));
        scenes.remove(ZONE, 21);
        assertThat(locator.resolveAsync(player).get(5, TimeUnit.SECONDS)).as("摘目录之后调用方找不到它")
                .isEqualTo(new NoHolder(ResolveResult.NODE_UNKNOWN));
    }
}
