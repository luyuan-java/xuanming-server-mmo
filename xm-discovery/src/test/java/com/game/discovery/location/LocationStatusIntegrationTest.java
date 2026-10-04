package com.game.discovery.location;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.proto.PlayerLocation;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 位置记录状态的批量读（{@link PlayerLocationDirectory#statusesAsync}，组队会话四态用）的真 Redis 集成测试。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 11 与随机大号 player_id（含 ≥ 2^63）隔离，只删自己写的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class LocationStatusIntegrationTest {

    private static final long BASE = Long.MIN_VALUE + (1L << 51) + ThreadLocalRandom.current().nextLong(1L << 40);

    private static RedissonClient redis;
    private static PlayerLocationDirectory directory;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(11);
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

    private static PlayerLocation at(long player) {
        return PlayerLocation.newBuilder().setPlayerId(player).setZoneId(1).setSceneNodeId(3).setSceneId(100)
                .setSceneConfigId(2).setOwnerEpoch(5).build();
    }

    @Test
    void 在线_租约_墓碑_没有_损坏_各给各的状态() throws Exception {
        long online = BASE;
        long leased = BASE + 1;
        long loggedOut = BASE + 2;
        long missing = BASE + 3;
        long badState = BASE + 4;
        long wrongType = BASE + 5;
        directory.putAsync(at(online), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        directory.leaseAsync(at(leased), 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        directory.removeAsync(loggedOut, 5, 1).toCompletableFuture().get(5, TimeUnit.SECONDS);
        redis.<String, String>getMap(RedisKeys.playerLocation(badState), StringCodec.INSTANCE).put("s", "z");
        redis.<String>getBucket(RedisKeys.playerLocation(wrongType), StringCodec.INSTANCE).set("not-a-hash");

        Map<Long, LocationStatus> statuses = directory.statusesAsync(
                List.of(online, leased, loggedOut, missing, badState, wrongType, online)).get(5, TimeUnit.SECONDS);

        assertThat(statuses.keySet()).as("去重、保持入参顺序")
                .containsExactly(online, leased, loggedOut, missing, badState, wrongType);
        assertThat(statuses).containsEntry(online, LocationStatus.ONLINE)
                .containsEntry(leased, LocationStatus.RECONNECT_LEASE)
                .containsEntry(loggedOut, LocationStatus.LOGGED_OUT)
                .containsEntry(missing, LocationStatus.MISSING)
                .containsEntry(badState, LocationStatus.ERROR)
                .containsEntry(wrongType, LocationStatus.ERROR);
    }

    @Test
    void 空入参_直接返回空表() throws Exception {
        assertThat(directory.statusesAsync(List.of()).get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void 读失败_每人都是ERROR_future正常完成() throws Exception {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(11);
        RedissonClient closed = Redisson.create(config);
        closed.shutdown();

        Map<Long, LocationStatus> statuses = new PlayerLocationDirectory(closed)
                .statusesAsync(List.of(BASE + 6, BASE + 7)).get(5, TimeUnit.SECONDS);

        assertThat(statuses).containsEntry(BASE + 6, LocationStatus.ERROR).containsEntry(BASE + 7, LocationStatus.ERROR);
    }
}
