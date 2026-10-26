package com.game.discovery.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/** 真 Redis 验证 binary、主版本/领导者双围栏、目录过期及跨对象共享玩家冷却；只清随机命名空间自己的键。 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class ChannelSwitchRedisIntegrationTest {
    @Test
    void 目录双围栏_binary与TTL_玩家冷却跨节点共享到期消失() throws Exception {
        RedisKeys keys = new RedisKeys(GameNamespace.of("ch" + UUID.randomUUID().toString().replace("-", "").substring(0, 20)));
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(9);
        var redis = Redisson.create(config);
        String[] ownKeys = {keys.worldLeader(1), keys.worldPlanVersion(1), keys.worldChannelDirectory(1, 7),
                keys.channelSwitchCooldown(42)};
        try {
            var directories = new RedissonWorldChannelDirectoryStore(redis, keys);
            var plans = new RedissonWorldChannelStore(redis, keys);
            assertThat(directories.read(1, 7)).isNull();
            assertThat(plans.tryAcquireLeader(1, "a", Duration.ofSeconds(30))).isTrue();
            redis.getBucket(keys.worldPlanVersion(1), StringCodec.INSTANCE).set("9007199254740993");
            byte[] payload = {0, -128, -1, 13, 10, 1};
            assertThat(directories.publish(1, "a", 9_007_199_254_740_993L, 7, payload, Duration.ofSeconds(2))).isTrue();
            assertThat(directories.read(1, 7)).containsExactly(payload);
            assertThat(directories.publish(1, "a", 9_007_199_254_740_992L, 7, new byte[] {9}, Duration.ofSeconds(2))).isFalse();
            assertThat(plans.releaseLeader(1, "a")).isTrue();
            assertThat(plans.tryAcquireLeader(1, "b", Duration.ofSeconds(30))).isTrue();
            assertThat(directories.publish(1, "a", 9_007_199_254_740_993L, 7, new byte[] {9}, Duration.ofSeconds(2))).isFalse();
            assertThat(directories.read(1, 7)).containsExactly(payload);
            assertThat(directories.publish(1, "b", 9_007_199_254_740_993L, 7, payload, Duration.ofMillis(100))).isTrue();
            var firstNode = new RedissonChannelSwitchCooldown(redis, keys);
            var secondNode = new RedissonChannelSwitchCooldown(redis, keys);
            assertThat(firstNode.active(42)).isFalse();
            firstNode.start(42, Duration.ofMillis(100));
            assertThat(secondNode.active(42)).isTrue();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while ((directories.read(1, 7) != null || secondNode.active(42)) && System.nanoTime() < deadline) Thread.sleep(10);
            assertThat(directories.read(1, 7)).isNull();
            assertThat(secondNode.active(42)).isFalse();
        } finally {
            redis.getKeys().delete(ownKeys);
            redis.shutdown();
        }
    }
}
