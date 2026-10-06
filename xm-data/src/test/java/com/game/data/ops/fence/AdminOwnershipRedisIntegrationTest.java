package com.game.data.ops.fence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.OwnerTakeover;
import com.game.data.DataConfiguration;
import com.game.data.metrics.DataMetrics;
import com.game.data.ops.fence.AdminOwnership.Claim;
import com.game.data.testing.DataSqlFixture;
import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.location.PlayerLocationDirectory.Refresh;
import com.game.discovery.proto.PlayerLocation;
import com.game.player.store.OwnerState;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 离线栅栏的两条 Redis 通路在真 Redis 上的语义（data-ops-spec §12.4；缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，
 * DB 13，与 xm-data 其他 Redis 集成测试同库；玩家库用 {@link DataSqlFixture}，H2 或 {@code -Dxm.it.mysql}）。被测的是<b>生产接线</b>
 * （{@link DataConfiguration#adminOwnership}：让出请求 = {@link RedisTakeoverRequests}，墓碑 = {@code PlayerLocationDirectory.removeAsync(p, E', 1)}）。
 *
 * <ul>
 *   <li>让出请求：发到 {@code xm:owner-takeover}，按 xm-scene 的订阅口径（{@code ByteArrayCodec} + {@code OwnerTakeover.parseFrom}）收得到，
 *       字节与 xm-login 的 {@code RedisOwnerTakeovers} 发的逐字节相同；持有者放手之后夺得到。</li>
 *   <li>位置墓碑：按 (epoch, 序号) 盖过旧实例留下的在线记录，旧实例迟到的写 / 续期不再生效；位置记录已属于更新的进场时墓碑不生效、
 *       不抹掉人家的在线记录。</li>
 * </ul>
 * pub/sub 频道不分库，本机若有别的进程也在发让出请求会一起收到：只认本用例的随机玩家号。只删自己写的位置键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class AdminOwnershipRedisIntegrationTest {

    private RedissonClient redis;
    private DataSqlFixture db;
    private ScheduledExecutorService fence;
    private SimpleMeterRegistry meters;
    private AdminOwnership ownership;
    private PlayerLocationDirectory directory;
    private final List<Long> players = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        db = DataSqlFixture.create();
        fence = Executors.newSingleThreadScheduledExecutor();
        meters = new SimpleMeterRegistry();
        ObjectProvider<RedissonClient> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(redis);
        ownership = new DataConfiguration().adminOwnership(db.playerStore(System::currentTimeMillis), db.transactionManager,
                provider, new DataMetrics(meters), fence);
        directory = new PlayerLocationDirectory(redis);
    }

    @AfterEach
    void tearDown() throws Exception {
        fence.shutdownNow();
        for (long player : players) {
            redis.getKeys().delete(RedisKeys.playerLocation(player));
        }
        redis.shutdown();
        db.close();
    }

    /** 随机玩家号（库里造一行：被 {@code epoch} 持有或已释放）。 */
    private long player(long epoch, boolean released) {
        long id = ThreadLocalRandom.current().nextLong(1L << 40, 1L << 60);
        players.add(id);
        long now = System.currentTimeMillis();
        db.insertPlayer(id, 1, 9, 1001, epoch, released, released ? 0 : now + 60_000, now - 1000, now - 1000);
        return id;
    }

    private OwnerState owner(long playerId) {
        return db.tx().execute(s -> db.playerMapper.selectOwnerForUpdate(playerId));
    }

    private static PlayerLocation location(long player, long epoch) {
        return PlayerLocation.newBuilder().setPlayerId(player).setZoneId(1).setSceneNodeId(3).setSceneId(100 + epoch)
                .setSceneConfigId(2).setOwnerEpoch(epoch).build();
    }

    private static <T> T get(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private double tombstones(String result) {
        Counter c = meters.find("xm.data.location.tombstones").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    private static void sleep(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ 让出请求

    @Test
    void kick发的让出请求_scene的订阅口径收得到同一对玩家号与epoch_字节同login发的_每次重试重发_持有者放手后夺到() {
        long p = player(5, false);
        // xm-login 的 RedisOwnerTakeovers 对同一对 (玩家, epoch) 发的字节
        byte[] sameAsLogin = OwnerTakeover.newBuilder().setPlayerId(p).setOwnerEpoch(5).build().toByteArray();
        List<byte[]> received = new CopyOnWriteArrayList<>();
        // 同 xm-scene OwnerTakeoverSubscriber：ByteArrayCodec 的频道、byte[] 监听、OwnerTakeover.parseFrom
        RTopic topic = redis.getTopic(RedisKeys.ownerTakeoverTopic(), ByteArrayCodec.INSTANCE);
        int listener = topic.addListener(byte[].class, (channel, message) -> {
            OwnerTakeover takeover;
            try {
                takeover = OwnerTakeover.parseFrom(message);
            } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                return;
            }
            if (takeover.getPlayerId() != p) {
                return; // 别的进程发的
            }
            received.add(message);
            // 持有 epoch 5 的 scene：第一条当没处理成（逻辑线程忙），第二条才写回并释放
            if (received.size() == 2 && takeover.getOwnerEpoch() == 5) {
                db.playerMapper.releaseOwner(p, 5, System.currentTimeMillis());
            }
        });
        try {
            Claim claim = ownership.claim(p, true, Duration.ofSeconds(10), AdminOwnershipRedisIntegrationTest::sleep);

            assertThat(claim).isEqualTo(new Claim.Claimed(6, true));
            assertThat(owner(p)).satisfies(o -> {
                assertThat(o.ownerEpoch()).isEqualTo(6);
                assertThat(o.released()).isFalse();
            });
            // 每次重试都重发；每条都是 {p, 5}，与 login 发的逐字节相同
            assertThat(received.size()).isGreaterThanOrEqualTo(2);
            assertThat(received).allSatisfy(message -> assertThat(message).isEqualTo(sameAsLogin));
            assertThat(meters.get("xm.data.ops.claims").tag("outcome", "kicked").counter().count()).isEqualTo(1);
        } finally {
            topic.removeListener(listener);
            ownership.releaseAll();
        }
    }

    @Test
    void 没有任何scene持有那个epoch_让出请求发出去也没人放手_到点Busy_归属原样() {
        long p = player(5, false);
        List<OwnerTakeover> received = new CopyOnWriteArrayList<>();
        RTopic topic = redis.getTopic(RedisKeys.ownerTakeoverTopic(), ByteArrayCodec.INSTANCE);
        int listener = topic.addListener(byte[].class, (channel, message) -> {
            try {
                OwnerTakeover takeover = OwnerTakeover.parseFrom(message);
                if (takeover.getPlayerId() == p) {
                    received.add(takeover);
                }
            } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                // 不是本用例的消息
            }
        });
        try {
            long leaseBefore = owner(p).leaseUntil();

            Claim claim = ownership.claim(p, true, Duration.ofMillis(400), AdminOwnershipRedisIntegrationTest::sleep);

            assertThat(claim).isEqualTo(new Claim.Busy(5));
            assertThat(owner(p)).isEqualTo(new OwnerState(5, false, leaseBefore));
            await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= 2);
            assertThat(received).allSatisfy(takeover -> assertThat(takeover.getOwnerEpoch()).isEqualTo(5));
            assertThat(ownership.heldCount()).isZero();
        } finally {
            topic.removeListener(listener);
        }
    }

    // ------------------------------------------------------------------ 位置墓碑

    @Test
    void 释放前的位置墓碑_盖过旧实例留下的在线记录_旧实例迟到的写与续期不再生效_新进场的写照常生效() throws Exception {
        long p = player(5, true); // 被踢的 scene 已写回并释放，但它不改位置记录
        assertThat(get(directory.putAsync(location(p, 5), 3))).isTrue();
        assertThat(directory.find(p)).map(PlayerLocation::getOwnerEpoch).contains(5L);

        assertThat(ownership.claim(p, false, Duration.ofSeconds(1), AdminOwnershipRedisIntegrationTest::sleep))
                .isEqualTo(new Claim.Claimed(6, false));
        // 持有期间不碰位置记录：还是旧实例那条「在线」
        assertThat(get(directory.statusesAsync(List.of(p)))).containsEntry(p, LocationStatus.ONLINE);

        ownership.release(p);

        // 墓碑 (epoch 6, 序号 1) 盖过 (epoch 5, 序号 3)：读者当没有；键还在（TTL 60 s 内挡住旧实例迟到的写）
        assertThat(directory.find(p)).isEmpty();
        assertThat(get(directory.statusesAsync(List.of(p)))).containsEntry(p, LocationStatus.LOGGED_OUT);
        assertThat(get(directory.findHolderAsync(p)).status()).isEqualTo(LocationStatus.LOGGED_OUT);
        Map<String, String> fields = redis.<String, String>getMap(RedisKeys.playerLocation(p), StringCodec.INSTANCE)
                .readAllMap();
        assertThat(fields).containsOnly(Map.entry("e", "6"), Map.entry("q", Long.toString(AdminOwnership.TOMBSTONE_SEQ)),
                Map.entry("s", "x"));
        assertThat(redis.getKeys().remainTimeToLive(RedisKeys.playerLocation(p)))
                .isBetween(1L, PlayerLocationDirectory.ONLINE_TTL.toMillis());
        assertThat(tombstones("ok")).isEqualTo(1);
        // 墓碑先于释放，释放也做了
        assertThat(owner(p).ownerEpoch()).isEqualTo(6);
        assertThat(owner(p).released()).isTrue();

        // 旧实例（epoch 5）迟到的写、断线租约、在线续期都被丢弃
        assertThat(get(directory.putAsync(location(p, 5), 4))).isFalse();
        assertThat(get(directory.leaseAsync(location(p, 5), 5))).isFalse();
        assertThat(get(directory.refreshAsync(List.of(new Refresh(location(p, 5), 3))))).isZero();
        assertThat(directory.find(p)).isEmpty();
        assertThat(get(directory.statusesAsync(List.of(p)))).containsEntry(p, LocationStatus.LOGGED_OUT);

        // 之后玩家重新进场（epoch 7）：新持有者的写照常生效
        assertThat(get(directory.putAsync(location(p, 7), 1))).isTrue();
        assertThat(directory.find(p)).map(PlayerLocation::getOwnerEpoch).contains(7L);
    }

    @Test
    void 位置记录已属于更新的进场_迟到的墓碑不生效_不抹掉人家的在线记录_记stale() throws Exception {
        long p = player(5, true);
        assertThat(ownership.claim(p, false, Duration.ofSeconds(1), AdminOwnershipRedisIntegrationTest::sleep))
                .isEqualTo(new Claim.Claimed(6, false));
        // 运维的租约过期后玩家重新进场（epoch 7），新实例写了在线记录；运维这边的续约还没察觉
        db.jdbc().update("UPDATE player SET owner_epoch = 7, owner_released = 0, owner_lease_until = ? WHERE player_id = ?",
                System.currentTimeMillis() + 60_000, p);
        assertThat(get(directory.putAsync(location(p, 7), 1))).isTrue();

        ownership.release(p);

        assertThat(tombstones("stale")).isEqualTo(1);
        assertThat(tombstones("ok")).isZero();
        assertThat(directory.find(p)).map(PlayerLocation::getOwnerEpoch).contains(7L);
        assertThat(get(directory.statusesAsync(List.of(p)))).containsEntry(p, LocationStatus.ONLINE);
        // 带 epoch 6 的释放也改不到行：新持有者的归属原样
        assertThat(owner(p).ownerEpoch()).isEqualTo(7);
        assertThat(owner(p).released()).isFalse();
    }

    @Test
    void 没有位置记录时墓碑照写_登出过的玩家被运维回档后仍是登出() throws Exception {
        long p = player(5, true);
        assertThat(directory.find(p)).isEmpty();
        assertThat(ownership.claim(p, false, Duration.ofSeconds(1), AdminOwnershipRedisIntegrationTest::sleep))
                .isEqualTo(new Claim.Claimed(6, false));

        ownership.releaseAll();

        assertThat(get(directory.statusesAsync(List.of(p)))).containsEntry(p, LocationStatus.LOGGED_OUT);
        assertThat(tombstones("ok")).isEqualTo(1);
        assertThat(owner(p).released()).isTrue();
    }
}
