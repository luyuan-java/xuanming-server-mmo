package com.game.scene.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.PlayerLocationDirectory.HolderRead;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.location.PlayerLocationDirectory.Refresh;
import com.game.discovery.proto.PlayerLocation;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 待落点位置记录的真 Redis 集成测试（批次 5.4，zone-travel-spec §11.8、§5.9 的状态迁移表）：源节点经 {@link RedisPlayerLocations} 写待落点
 * (E, q+1)，之后源节点迟到的 (E, q) 写与续期被拒、(E, q+2) 的回写覆盖它、目标 zone 以 (E+2, 1) 进场覆盖它、TTL 到期后消失；三个读者
 * （login 的 {@code find}、资产通道的 {@code findHolderAsync}、组队的 {@code statusesAsync}）不改代码就认得出它。
 * 默认跳过；显式开启：{@code -Dgame.it.redis=redis://127.0.0.1:6379}。用 DB 13 与随机大号 player_id 隔离。
 *
 * <p>源节点 S 在 zone 1、节点 3、场景 100；目标 zone 2；E = 1（{@link WorldTestAccess#player(long)} 的 epoch）。
 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class AwaitingPlacementRedisIntegrationTest {

    private static final long BASE = (1L << 52) + ThreadLocalRandom.current().nextLong(1L << 40);
    private static final int SOURCE_ZONE = 1;
    private static final int SOURCE_NODE = 3;
    private static final int TARGET_ZONE = 2;
    private static final long E = 1;

    private static RedissonClient redis;
    private static PlayerLocationDirectory directory;

    /** 假的「逻辑线程」：Redisson 的回调线程只往这里投，测试线程取出来跑。 */
    private final LinkedBlockingQueue<Runnable> logicQueue = new LinkedBlockingQueue<>();

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        directory = new PlayerLocationDirectory(redis);
    }

    @AfterAll
    static void cleanup() {
        for (long i = 0; i < 10; i++) {
            redis.getKeys().delete(RedisKeys.playerLocation(BASE + i));
        }
        redis.shutdown();
    }

    private RedisPlayerLocations sourceNode() {
        return new RedisPlayerLocations(directory, SOURCE_ZONE, SOURCE_NODE, logicQueue::add);
    }

    /** 源节点在旅途前写过的在线记录 / 之后补写的源场景重连租约的值。 */
    private static PlayerLocation atSource(long player) {
        return PlayerLocation.newBuilder().setPlayerId(player).setZoneId(SOURCE_ZONE).setSceneNodeId(SOURCE_NODE).setSceneId(100)
                .setSceneConfigId(1).setOwnerEpoch(E).build();
    }

    /** 待落点的值：目标 zone、节点号 0、场景号 0、解析后的地图。 */
    private static PlayerLocation pending(long player, int sceneConfigId) {
        return PlayerLocation.newBuilder().setPlayerId(player).setZoneId(TARGET_ZONE).setSceneConfigId(sceneConfigId)
                .setOwnerEpoch(E).build();
    }

    /** 第二条腿在目标 zone 进场写的在线记录（E+2、序号 1）。 */
    private static PlayerLocation atTarget(long player) {
        return PlayerLocation.newBuilder().setPlayerId(player).setZoneId(TARGET_ZONE).setSceneNodeId(7).setSceneId(200)
                .setSceneConfigId(5).setOwnerEpoch(E + 2).build();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static long ttlMillis(long player) {
        return redis.getKeys().remainTimeToLive(RedisKeys.playerLocation(player));
    }

    private static LocationStatus status(long player) throws Exception {
        Map<Long, LocationStatus> statuses = await(directory.statusesAsync(List.of(player)));
        return statuses.get(player);
    }

    /** 写待落点，等结果投递回「逻辑线程」后在测试线程上执行，返回写没写上。 */
    private boolean place(ScenePlayer removed, int sceneConfigId, Duration ttl) throws Exception {
        CompletableFuture<Boolean> written = new CompletableFuture<>();
        sourceNode().awaitingPlacement(removed, TARGET_ZONE, sceneConfigId, ttl, written::complete);
        Runnable task = logicQueue.poll(5, TimeUnit.SECONDS);
        assertThat(task).as("结果经逻辑执行器投递").isNotNull();
        assertThat(written).as("Redisson 的回调线程只做投递").isNotDone();
        task.run();
        return written.getNow(null);
    }

    @Test
    void 待落点之后_源节点迟到的旧序号写与续期被拒_三个读者认得出_回写覆盖它_目标zone以E加2进场覆盖它() throws Exception {
        long p = BASE;
        ScenePlayer removed = WorldTestAccess.player(p);
        long q = removed.nextLocationSeq();
        // 旅途前：(E, q) o @S
        assertThat(await(directory.putAsync(atSource(p), q))).isTrue();

        // 交出提交 + 写待落点：(E, q+1) l @z2 节点 0
        assertThat(place(removed, 5, Duration.ofSeconds(287))).isTrue();
        assertThat(removed.locationSeq()).isEqualTo(q + 1);
        assertThat(directory.find(p)).as("login 照常拿到值，按节点号 0 认出待落点").contains(pending(p, 5));
        assertThat(ttlMillis(p)).as("TTL = 调用方给的票据剩余").isBetween(280_000L, 287_000L);
        HolderRead holder = await(directory.findHolderAsync(p));
        assertThat(holder.status()).as("资产通道：此刻没有节点持有").isEqualTo(LocationStatus.RECONNECT_LEASE);
        assertThat(holder.location()).isNull();
        assertThat(status(p)).as("组队的会话四态").isEqualTo(LocationStatus.RECONNECT_LEASE);

        // 源节点迟到的 (E, q) 写与续期：一律被拒，待落点原样
        assertThat(await(directory.putAsync(atSource(p), q))).isFalse();
        assertThat(await(directory.leaseAsync(atSource(p), q))).isFalse();
        assertThat(await(directory.refreshAsync(List.of(new Refresh(atSource(p), q))))).isZero();
        assertThat(directory.find(p)).contains(pending(p, 5));
        assertThat(ttlMillis(p)).as("迟到的续期没把 TTL 改成在线的 60 s").isGreaterThan(200_000L);

        // TF7 / 交叉离开：(E, q+2) 写回源场景的重连租约，覆盖待落点
        assertThat(await(directory.leaseAsync(atSource(p), removed.nextLocationSeq()))).isTrue();
        assertThat(directory.find(p)).contains(atSource(p));
        assertThat(ttlMillis(p)).isBetween(1L, 30_000L);

        // 第二条腿：(E+2, 1) o @T 覆盖它；之后源节点同 epoch 的任何写都碰不到
        assertThat(await(directory.putAsync(atTarget(p), 1))).isTrue();
        assertThat(directory.find(p)).contains(atTarget(p));
        assertThat(await(directory.leaseAsync(atSource(p), removed.nextLocationSeq()))).isFalse();
        assertThat(await(directory.findHolderAsync(p)).status()).isEqualTo(LocationStatus.ONLINE);
    }

    @Test
    void 目标zone直接以E加2进场覆盖待落点_源节点之后同epoch的回写被拒() throws Exception {
        long p = BASE + 1;
        ScenePlayer removed = WorldTestAccess.player(p);
        assertThat(await(directory.putAsync(atSource(p), removed.nextLocationSeq()))).isTrue();
        assertThat(place(removed, 5, Duration.ofSeconds(300))).isTrue();

        assertThat(await(directory.putAsync(atTarget(p), 1))).isTrue();
        assertThat(directory.find(p)).contains(atTarget(p));
        assertThat(await(directory.leaseAsync(atSource(p), removed.nextLocationSeq()))).as("(E, q+2) 晚于 (E+2, 1) 到达").isFalse();
        assertThat(directory.find(p)).contains(atTarget(p));
    }

    @Test
    void 别的登录已以更大的epoch进场_待落点写不上回false() throws Exception {
        long p = BASE + 2;
        ScenePlayer removed = WorldTestAccess.player(p);
        assertThat(await(directory.putAsync(atSource(p), removed.nextLocationSeq()))).isTrue();
        assertThat(await(directory.putAsync(atTarget(p), 1))).isTrue();

        assertThat(place(removed, 5, Duration.ofSeconds(300))).isFalse();
        assertThat(directory.find(p)).contains(atTarget(p));
    }

    @Test
    void TTL到期后待落点消失_find为空_状态MISSING() throws Exception {
        long p = BASE + 3;
        ScenePlayer removed = WorldTestAccess.player(p);
        assertThat(await(directory.putAsync(atSource(p), removed.nextLocationSeq()))).isTrue();

        // 票据已过期算出的非正 TTL 截到 1 s
        assertThat(place(removed, 0, Duration.ofSeconds(-3))).isTrue();
        assertThat(directory.find(p)).as("地图 0 = 目标 zone 的默认主世界").contains(pending(p, 0));
        assertThat(ttlMillis(p)).isBetween(1L, 1_000L);

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (directory.find(p).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        assertThat(directory.find(p)).as("旅途放弃：到期无记录 → 任意 zone 首登").isEmpty();
        assertThat(status(p)).isEqualTo(LocationStatus.MISSING);
        assertThat(await(directory.findHolderAsync(p)).status()).isEqualTo(LocationStatus.MISSING);
    }
}
