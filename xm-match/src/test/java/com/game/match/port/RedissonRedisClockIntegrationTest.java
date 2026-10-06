package com.game.match.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * Redis 时间的生产实现连真 Redis（缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}）：读回的是 Redis 的 {@code TIME} 换算成的毫秒、
 * 与同一台 Redis 上脚本里取的时间同源、单调不减；Redis 不可达时在请求预算内以依赖异常失败。只读，不写任何键（用 DB 13）。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedissonRedisClockIntegrationTest {

    private static RedissonClient redis;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void close() {
        if (redis != null) {
            redis.shutdown();
        }
    }

    /** 直接取 Redis 的 TIME（秒、微秒两段），在测试里换算。 */
    private static long redisTimeMs() {
        List<Object> time = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, "return redis.call('TIME')", RScript.ReturnType.MULTI,
                List.of());
        return Long.parseLong(time.get(0).toString()) * 1000 + Long.parseLong(time.get(1).toString()) / 1000;
    }

    @Test
    void 读回的是Redis的TIME_毫秒_夹在前后两次直接读之间() {
        RedissonRedisClock clock = new RedissonRedisClock(redis);

        long before = redisTimeMs();
        long now = clock.nowMs(Deadline.after(3000));
        long after = redisTimeMs();

        assertThat(now).isBetween(before, after);
        assertThat(now).as("是 Unix 毫秒（2026 年之后、13 位）").isGreaterThan(1_767_225_600_000L).isLessThan(10_000_000_000_000L);
    }

    @Test
    void 单调不减_隔一段时间读差出相应的毫秒() throws Exception {
        RedissonRedisClock clock = new RedissonRedisClock(redis);

        long first = clock.nowMs(Deadline.after(3000));
        TimeUnit.MILLISECONDS.sleep(120);
        long second = clock.nowMs(Deadline.after(3000));

        assertThat(second - first).isBetween(100L, 2000L);
    }

    @Test
    void 客户端已不可用_以依赖异常失败_不退回本机时间() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13).setConnectionMinimumIdleSize(1).setConnectionPoolSize(2);
        RedissonClient closed = Redisson.create(config);
        RedissonRedisClock clock = new RedissonRedisClock(closed);
        assertThat(clock.nowMs(Deadline.after(3000))).isPositive();
        closed.shutdown();

        long started = System.nanoTime();
        assertThatThrownBy(() -> clock.nowMs(Deadline.after(1500))).isInstanceOf(Deadline.DependencyException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("在请求预算附近返回").isLessThan(5000);
    }
}
