package com.game.guild.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/** 真 Redis 验 Lua 原子只增、精度、无 TTL 与损坏拒绝；只删随机命名空间中的本测试键。 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class TerminalCleanupWatermarkRedisIntegrationTest {

    @Test
    void 多副本乱序推进只增_大时间戳不丢精度_无TTL_损坏不能覆盖() throws Exception {
        RedisKeys keys = new RedisKeys(GameNamespace.of("wm" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)));
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(10);
        var redis = Redisson.create(config);
        String key = keys.guildTerminalCleanupWatermark();
        try {
            var a = new RedissonTerminalCleanupWatermark(redis, keys);
            var b = new RedissonTerminalCleanupWatermark(redis, keys);
            assertThat(a.read(Deadline.after(5000))).isZero();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                var first = pool.submit(() -> a.advance(1_700_000_000_100L, Deadline.after(5000)));
                var second = pool.submit(() -> b.advance(1_700_000_000_099L, Deadline.after(5000)));
                first.get(); second.get();
            }
            assertThat(a.read(Deadline.after(5000))).isEqualTo(1_700_000_000_100L);
            a.advance(9_007_199_254_740_993L, Deadline.after(5000));
            b.advance(9_007_199_254_740_992L, Deadline.after(5000));
            assertThat(b.read(Deadline.after(5000))).isEqualTo(9_007_199_254_740_993L);
            assertThat(redis.getBucket(key).remainTimeToLive()).isEqualTo(-1);
            // 沿用已有水位也必须消掉意外 TTL，不能等它过期后再回退成未清理。
            for (long offered : new long[] {9_007_199_254_740_992L, 9_007_199_254_740_993L}) {
                assertThat(redis.getBucket(key).expire(Duration.ofMinutes(1))).isTrue();
                assertThat(redis.getBucket(key).remainTimeToLive()).isPositive();
                a.advance(offered, Deadline.after(5000));
                assertThat(a.read(Deadline.after(5000))).isEqualTo(9_007_199_254_740_993L);
                assertThat(redis.getBucket(key).remainTimeToLive()).isEqualTo(-1);
            }
            redis.getBucket(key, StringCodec.INSTANCE).set("broken");
            assertThatThrownBy(() -> a.read(Deadline.after(5000))).isInstanceOf(Deadline.DependencyException.class);
            assertThatThrownBy(() -> b.advance(Long.MAX_VALUE, Deadline.after(5000))).isInstanceOf(RuntimeException.class);
            assertThat(redis.getBucket(key, StringCodec.INSTANCE).get()).isEqualTo("broken");
        } finally {
            redis.getKeys().delete(key);
            redis.shutdown();
        }
    }
}
