package com.game.friend.directory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.config.Config;

/** SCAN 经 Lua 带回游标与键、MGET 取条目（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 13，键前缀随机）。 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class DirectoryRedisIntegrationTest {

    private static final String PREFIX = "xm:it-dir-" + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ":";
    private static RedissonClient redis;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        for (int i = 1; i <= 150; i++) {
            redis.getBucket(PREFIX + i, ByteArrayCodec.INSTANCE).set(new byte[] {(byte) i});
        }
    }

    @AfterAll
    static void cleanup() {
        redis.getKeys().deleteByPattern(PREFIX + "*");
        redis.shutdown();
    }

    @Test
    void SCAN走完一遍拿到全部键_MGET取值() {
        RedissonDirectoryRedis dir = new RedissonDirectoryRedis(redis, PREFIX);
        Set<String> seen = new HashSet<>();
        long cursor = 0;
        int rounds = 0;
        do {
            OnlineDirectory.ScanPage page = dir.scan(cursor).toCompletableFuture().join();
            seen.addAll(page.keys());
            cursor = page.next();
            rounds++;
        } while (cursor != 0 && rounds < 100_000);
        assertThat(seen).hasSize(150).allMatch(k -> k.startsWith(PREFIX));

        List<String> keys = new ArrayList<>(List.of(PREFIX + 1, PREFIX + 2, PREFIX + "missing"));
        Map<String, byte[]> values = dir.mget(keys).toCompletableFuture().join();
        assertThat(values.get(PREFIX + 2)).containsExactly(2);
        assertThat(values.get(PREFIX + "missing")).isNull();
    }
}
