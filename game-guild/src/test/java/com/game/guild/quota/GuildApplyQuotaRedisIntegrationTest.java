package com.game.guild.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.guild.GuildConfiguration;
import com.game.guild.GuildProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/** 只连显式指定的本机隔离 Redis；独立随机命名空间，只清理本例键。 */
@EnabledIfSystemProperty(named = "game.guild.quota-test-redis", matches = ".+")
class GuildApplyQuotaRedisIntegrationTest {
    @Test
    void 真实Redis跨两客户端原子配额_固定窗口_修复无TTL_过期恢复() throws Exception {
        URI endpoint = URI.create(System.getProperty("game.guild.quota-test-redis"));
        if (!"redis".equals(endpoint.getScheme()) || !List.of("127.0.0.1", "localhost").contains(endpoint.getHost())
                || endpoint.getPort() != 13379 || endpoint.getUserInfo() != null
                || (endpoint.getPath() != null && !endpoint.getPath().isEmpty())) {
            throw new IllegalArgumentException("配额测试仅允许 redis://127.0.0.1:13379，不能指向已有环境");
        }
        RedisKeys keys = new RedisKeys(GameNamespace.of("guildquotait" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)));
        RedissonClient first = connect(endpoint.toString());
        RedissonClient second = null;
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try (var workers = Executors.newFixedThreadPool(4)) {
            second = connect(endpoint.toString());
            GuildConfiguration wiring = new GuildConfiguration();
            GuildProperties properties = new GuildProperties(null, null, null, null, null, null, null, null);
            GuildApplyQuota one = wiring.guildApplyQuota(first, keys, properties, meters);
            GuildApplyQuota two = wiring.guildApplyQuota(second, keys, properties, meters);
            // 连接初始化在并发断言前完成，测试的是配额而非冷启动时延。
            first.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, "return 1", RScript.ReturnType.INTEGER);
            second.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, "return 1", RScript.ReturnType.INTEGER);
            List<Callable<Boolean>> requests = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                GuildApplyQuota current = i % 2 == 0 ? one : two;
                requests.add(() -> current.tryAcquire(-1L, Deadline.after(3000)));
            }
            int admitted = 0;
            for (var result : workers.invokeAll(requests)) {
                if (result.get()) {
                    admitted++;
                }
            }
            assertThat(admitted).isEqualTo(10);
            String playerKey = keys.guildApplyQuota(-1L);
            var bucket = first.<String>getBucket(playerKey, StringCodec.INSTANCE);
            assertThat(bucket.get()).isEqualTo("24");
            assertThat(bucket.remainTimeToLive()).isBetween(1L, 60000L);
            assertThat(one.tryAcquire(1, Deadline.after(3000))).isTrue();
            bucket.set("9");
            assertThat(bucket.remainTimeToLive()).isEqualTo(-1);
            assertThat(two.tryAcquire(-1L, Deadline.after(3000))).isTrue();
            assertThat(bucket.remainTimeToLive()).isBetween(1L, 60000L);
            long before = bucket.remainTimeToLive();
            assertThat(one.tryAcquire(-1L, Deadline.after(3000))).isFalse();
            assertThat(bucket.remainTimeToLive()).isLessThanOrEqualTo(before);
            first.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                    "return redis.call('PEXPIREAT', KEYS[1], 1)", RScript.ReturnType.INTEGER, List.of(playerKey));
            assertThat(two.tryAcquire(-1L, Deadline.after(3000))).isTrue();
            assertThat(bucket.get()).isEqualTo("1");
            assertThat(meters.get("game.guild.apply.quota").tag("result", "error").counter().count()).isZero();
        } finally {
            try {
                first.getKeys().delete(keys.guildApplyQuota(-1L), keys.guildApplyQuota(1));
            } finally {
                try {
                    if (second != null) {
                        second.shutdown();
                    }
                } finally {
                    try {
                        first.shutdown();
                    } finally {
                        meters.close();
                    }
                }
            }
        }
    }

    private static RedissonClient connect(String address) {
        Config config = new Config();
        config.useSingleServer().setAddress(address).setDatabase(11).setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(4).setRetryAttempts(0).setTimeout(250);
        return Redisson.create(config);
    }
}
