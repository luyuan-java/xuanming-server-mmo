package com.game.discovery.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory.Refresh;
import com.game.discovery.proto.PlayerLocation;
import java.util.List;
import java.util.concurrent.CompletionStage;
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
 * 玩家位置（按 (epoch, 写序号) 只收更新的写、在线续期补回、重连租约、登出墓碑）的真 Redis 集成测试。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 13 与随机大号 player_id 隔离。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class PlayerLocationIntegrationTest {

    private static final long BASE = (1L << 51) + ThreadLocalRandom.current().nextLong(1L << 40);

    private static RedissonClient redis;
    private static PlayerLocationDirectory directory;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
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

    private static PlayerLocation at(long player, long epoch, long sceneId) {
        return PlayerLocation.newBuilder().setPlayerId(player).setZoneId(1).setSceneNodeId(3).setSceneId(sceneId)
                .setSceneConfigId(2).setOwnerEpoch(epoch).build();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static long ttlMillis(long player) {
        return redis.getKeys().remainTimeToLive(RedisKeys.playerLocation(player));
    }

    @Test
    void 写入后可查_在线TTL_按epoch与序号只收更新的写() throws Exception {
        long p = BASE;
        assertThat(directory.find(p)).isEmpty();
        assertThat(await(directory.putAsync(at(p, 5, 100), 1))).isTrue();
        assertThat(directory.find(p)).contains(at(p, 5, 100));
        assertThat(ttlMillis(p)).isBetween(50_000L, 60_000L);
        assertThat(await(directory.putAsync(at(p, 4, 999), 99))).as("旧持有者迟到的写").isFalse();
        assertThat(await(directory.putAsync(at(p, 5, 101), 1))).as("同序号重放").isFalse();
        assertThat(await(directory.putAsync(at(p, 5, 101), 2))).as("同一次进场换场景").isTrue();
        assertThat(directory.find(p)).contains(at(p, 5, 101));
        assertThat(await(directory.putAsync(at(p, 10, 102), 1))).as("新进场（位数更多），序号从头数").isTrue();
        assertThat(await(directory.putAsync(at(p, 9, 103), 50))).as("9 < 10：按数值比，不按字典序").isFalse();
        assertThat(await(directory.putAsync(at(p, 10, 104), 10))).isTrue();
        assertThat(await(directory.putAsync(at(p, 10, 105), 9))).as("序号 9 < 10：也按数值比").isFalse();
        assertThat(directory.find(p)).contains(at(p, 10, 104));
    }

    @Test
    void 进场_换场景_断线三条乱序到达_最后生效的是断线时的位置() throws Exception {
        long p = BASE + 1;
        // 实测重排：断线（3）先到，换场景（2）、进场（1）晚到——晚到的都比已存的旧，丢弃
        assertThat(await(directory.leaseAsync(at(p, 7, 200), 3))).isTrue();
        assertThat(await(directory.putAsync(at(p, 7, 200), 2))).isFalse();
        assertThat(await(directory.putAsync(at(p, 7, 100), 1))).isFalse();
        assertThat(directory.find(p)).as("租约内还在").contains(at(p, 7, 200));
        assertThat(ttlMillis(p)).isBetween(1L, 30_000L);
        assertThat(await(directory.refreshAsync(List.of(new Refresh(at(p, 7, 200), 2))))).isZero();
        assertThat(ttlMillis(p)).as("晚到的续期不把租约续长").isBetween(1L, 30_000L);
    }

    @Test
    void 主动离开写成墓碑_读者当没有_晚到的续期与换场景都不复活_新进场照常写() throws Exception {
        long p = BASE + 2;
        await(directory.putAsync(at(p, 7, 100), 1));
        assertThat(await(directory.removeAsync(p, 6, 9))).as("旧持有者迟到的登出碰不到新记录").isFalse();
        assertThat(directory.find(p)).isPresent();
        assertThat(await(directory.removeAsync(p, 7, 3))).isTrue();
        assertThat(directory.find(p)).isEmpty();
        assertThat(ttlMillis(p)).isBetween(1L, 60_000L);
        assertThat(await(directory.refreshAsync(List.of(new Refresh(at(p, 7, 100), 1))))).as("墓碑在，不补回").isZero();
        assertThat(await(directory.putAsync(at(p, 7, 101), 2))).as("同 epoch 晚到的换场景").isFalse();
        assertThat(directory.find(p)).isEmpty();
        assertThat(await(directory.putAsync(at(p, 8, 200), 1))).as("新的进场").isTrue();
        assertThat(directory.find(p)).contains(at(p, 8, 200));
    }

    @Test
    void 键已不在时登出也立墓碑_挡住乱序的续期补回() throws Exception {
        long p = BASE + 7;
        assertThat(await(directory.removeAsync(p, 3, 2))).isTrue();
        assertThat(await(directory.refreshAsync(List.of(new Refresh(at(p, 3, 100), 1))))).isZero();
        assertThat(directory.find(p)).isEmpty();
    }

    @Test
    void 续期_这一序号的在线记录延长_丢了补回_别人的与更新的写不动() throws Exception {
        long mine = BASE + 3;
        long lost = BASE + 4;
        long taken = BASE + 5;
        await(directory.putAsync(at(mine, 1, 100), 4));
        redis.getKeys().expire(RedisKeys.playerLocation(mine), 5, TimeUnit.SECONDS);
        await(directory.putAsync(at(taken, 9, 900), 1));
        int restored = await(directory.refreshAsync(List.of(new Refresh(at(mine, 1, 100), 4),
                new Refresh(at(lost, 2, 200), 1), new Refresh(at(taken, 8, 800), 7))));
        assertThat(restored).isEqualTo(1);
        assertThat(ttlMillis(mine)).as("续回在线 TTL").isGreaterThan(30_000L);
        assertThat(directory.find(lost)).contains(at(lost, 2, 200));
        assertThat(directory.find(taken)).contains(at(taken, 9, 900));
        assertThat(await(directory.refreshAsync(List.of(new Refresh(at(mine, 1, 100), 3))))).as("序号对不上").isZero();
        assertThat(await(directory.refreshAsync(List.of()))).isZero();
    }

    @Test
    void 值与键不符按没有() throws Exception {
        long p = BASE + 6;
        await(directory.putAsync(at(p + 100, 1, 100), 1));
        redis.getKeys().rename(RedisKeys.playerLocation(p + 100), RedisKeys.playerLocation(p));
        assertThat(directory.find(p)).isEmpty();
    }
}
