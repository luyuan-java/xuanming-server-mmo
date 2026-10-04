package com.game.chat.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/** 五段脚本 / 命令在真 Redis 上的行为（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}，DB 13）。 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedissonChatStoreIntegrationTest {

    private static final String PREFIX = "xm:it-chat-" + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ":";
    private static RedissonClient redis;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void cleanup() {
        redis.getKeys().deleteByPattern(PREFIX + "*");
        redis.shutdown();
    }

    @Test
    void 占位_完结_释放都按值比较() {
        ChatStore store = new RedissonChatStore(redis);
        String key = PREFIX + "req";
        assertThat(store.claim(key, "pending:a", 60).toCompletableFuture().join()).isTrue();
        assertThat(store.claim(key, "pending:b", 60).toCompletableFuture().join()).isFalse();
        assertThat(store.release(key, "pending:b").toCompletableFuture().join()).isFalse();
        assertThat(store.markDone(key, "pending:b", "done", 60).toCompletableFuture().join()).isFalse();
        assertThat(store.markDone(key, "pending:a", "done", 60).toCompletableFuture().join()).isTrue();
        assertThat(store.get(key).toCompletableFuture().join()).isEqualTo("done");
        assertThat(redis.getBucket(key, StringCodec.INSTANCE).remainTimeToLive()).isBetween(1L, 60_000L);
        String other = PREFIX + "req2";
        store.claim(other, "pending:c", 60).toCompletableFuture().join();
        assertThat(store.release(other, "pending:c").toCompletableFuture().join()).isTrue();
        assertThat(store.get(other).toCompletableFuture().join()).isNull();
    }

    @Test
    void 限速计数带过期_历史LPUSH_LTRIM_EXPIRE() {
        ChatStore store = new RedissonChatStore(redis);
        String rl = PREFIX + "rl";
        assertThat(store.incrWithTtl(rl, 1).toCompletableFuture().join()).isEqualTo(1);
        assertThat(store.incrWithTtl(rl, 1).toCompletableFuture().join()).isEqualTo(2);
        assertThat(redis.getBucket(rl, StringCodec.INSTANCE).remainTimeToLive()).isBetween(1L, 1_000L);

        String log = PREFIX + "log";
        for (int i = 0; i < 5; i++) {
            store.append(log, ("m" + i).getBytes(StandardCharsets.UTF_8), 3, 600).toCompletableFuture().join();
        }
        List<byte[]> got = store.range(log, 10).toCompletableFuture().join();
        assertThat(got).extracting(b -> new String(b, StandardCharsets.UTF_8)).containsExactly("m4", "m3", "m2");
        assertThat(store.range(log, 2).toCompletableFuture().join()).hasSize(2);
        assertThat(redis.getList(log).remainTimeToLive()).isBetween(1L, 600_000L);
    }
}
