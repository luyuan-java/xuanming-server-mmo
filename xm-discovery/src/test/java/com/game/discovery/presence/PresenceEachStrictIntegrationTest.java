package com.game.discovery.presence;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPresenceDirectory.PresenceRead;
import com.game.discovery.presence.PlayerPresenceDirectory.PresenceRead.Status;
import com.game.discovery.proto.PlayerPresence;
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
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.config.Config;

/**
 * 在线目录严格逐人读（{@link PlayerPresenceDirectory#findEachStrictAsync}，组队会话四态用）的真 Redis 集成测试。
 * 默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 11 与随机大号 player_id（含 ≥ 2^63）隔离，只删自己写的键。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class PresenceEachStrictIntegrationTest {

    /** 高位为 1（≥ 2^63，有符号视角是负数）的玩家号：键与比较都必须按无符号处理。 */
    private static final long BASE = Long.MIN_VALUE + (1L << 50) + ThreadLocalRandom.current().nextLong(1L << 40);

    private static RedissonClient redis;
    private static PlayerPresenceDirectory directory;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(11);
        redis = Redisson.create(config);
        directory = new PlayerPresenceDirectory(redis);
    }

    @AfterAll
    static void cleanup() {
        for (long i = 0; i < 10; i++) {
            redis.getKeys().delete(RedisKeys.presence(BASE + i));
        }
        redis.shutdown();
    }

    private static PlayerPresence entry(long player) {
        return PlayerPresence.newBuilder().setPlayerId(player).setZoneId(1).setGateNodeId(2)
                .setGateInstanceId("gate-it").setSessionId(7).setOnlineSinceMs(1000).setOwnerEpoch(3).build();
    }

    @Test
    void 在线_不在线_损坏_与键不符_逐人给结果() throws Exception {
        long online = BASE;
        long absent = BASE + 1;
        long corrupt = BASE + 2;
        long mismatch = BASE + 3;
        directory.putAsync(entry(online)).toCompletableFuture().get(5, TimeUnit.SECONDS);
        redis.getBucket(RedisKeys.presence(corrupt), ByteArrayCodec.INSTANCE).set(new byte[] {(byte) 0xff, 0x01});
        redis.getBucket(RedisKeys.presence(mismatch), ByteArrayCodec.INSTANCE).set(entry(BASE + 9).toByteArray());

        Map<Long, PresenceRead> reads = directory.findEachStrictAsync(List.of(online, absent, corrupt, mismatch, online))
                .get(5, TimeUnit.SECONDS);

        assertThat(reads.keySet()).as("去重、保持入参顺序").containsExactly(online, absent, corrupt, mismatch);
        assertThat(reads.get(online)).isEqualTo(PresenceRead.online(entry(online)));
        assertThat(reads.get(absent).status()).isEqualTo(Status.ABSENT);
        assertThat(reads.get(corrupt).status()).as("损坏不降级成离线").isEqualTo(Status.ERROR);
        assertThat(reads.get(mismatch).status()).as("与键不符不降级成离线").isEqualTo(Status.ERROR);
        assertThat(reads.get(absent).presence()).isNull();
    }

    @Test
    void 空入参_直接返回空表() throws Exception {
        assertThat(directory.findEachStrictAsync(List.of()).get(5, TimeUnit.SECONDS)).isEmpty();
    }

    @Test
    void 整个往返失败_每人都是ERROR_future正常完成() throws Exception {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(11);
        RedissonClient closed = Redisson.create(config);
        closed.shutdown();

        Map<Long, PresenceRead> reads = new PlayerPresenceDirectory(closed)
                .findEachStrictAsync(List.of(BASE + 4, BASE + 5)).get(5, TimeUnit.SECONDS);

        assertThat(reads).containsOnlyKeys(BASE + 4, BASE + 5);
        assertThat(reads.values()).allSatisfy(r -> assertThat(r.status()).isEqualTo(Status.ERROR));
    }
}
