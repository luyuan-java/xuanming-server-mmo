package com.game.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.gateway.ratelimit.RateLimitStore.Admission;
import com.game.gateway.ratelimit.RateLimitStore.Bucket;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

/**
 * 令牌桶 Lua（容量 = burst、每秒补 rps、IP 桶先判、区桶空时退回 IP 令牌、等待毫秒向下取整、取 Redis 服务器时间、键闲置过期）
 * 与冷却（SET NX PX）的真 Redis 集成测试。默认跳过；显式开启：{@code -Dxm.it.redis=redis://127.0.0.1:6379}。用 DB 13 与随机键隔离。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedisRateLimitStoreIntegrationTest {

    private static RedissonClient redis;
    private static RedisRateLimitStore store;
    private final String prefix = "xm:it:rl:" + ThreadLocalRandom.current().nextLong(Long.MAX_VALUE);
    private final String ipKey = prefix + ":ip";
    private final String zoneKey = prefix + ":zone";

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        store = new RedisRateLimitStore(redis);
    }

    @AfterAll
    static void disconnect() {
        redis.shutdown();
    }

    @AfterEach
    void cleanup() {
        redis.getKeys().delete(ipKey, zoneKey);
    }

    @Test
    void 只判IP桶_满桶起步_取空后按rps补() {
        long t = 1_000_000;
        Bucket ip = new Bucket(ipKey, 2, 3);
        assertThat(store.admitAt(t, ip, null)).isEqualTo(new Admission(Admission.Kind.OK, 0, 2));
        assertThat(store.admitAt(t, ip, null).remaining()).isEqualTo(1);
        assertThat(store.admitAt(t, ip, null).remaining()).isZero();
        assertThat(store.admitAt(t, ip, null).kind()).isEqualTo(Admission.Kind.IP_EMPTY);
        assertThat(store.admitAt(t + 499, ip, null).kind()).isEqualTo(Admission.Kind.IP_EMPTY);
        assertThat(store.admitAt(t + 500, ip, null).kind()).isEqualTo(Admission.Kind.OK);
        assertThat(store.admitAt(t + 100_000, ip, null).remaining()).as("补满封顶 burst").isEqualTo(2);
        assertThat(redis.getKeys().remainTimeToLive(ipKey)).isBetween(1L, RedisRateLimitStore.IDLE_TTL.toMillis());
        assertThat(redis.getKeys().countExists(zoneKey)).isZero();
    }

    @Test
    void 区桶空排队_等待毫秒_IP令牌退回_IP桶空时不碰区桶() {
        long t = 2_000_000;
        Bucket ip = new Bucket(ipKey, 1, 2);
        Bucket zone = new Bucket(zoneKey, 2, 1);
        assertThat(store.admitAt(t, ip, zone)).isEqualTo(new Admission(Admission.Kind.OK, 0, 0));
        Admission queued = store.admitAt(t, ip, zone);
        assertThat(queued).isEqualTo(new Admission(Admission.Kind.ZONE_EMPTY, 500, 0));
        assertThat(store.admitAt(t, ip, zone).kind()).isEqualTo(Admission.Kind.ZONE_EMPTY);
        assertThat(store.admitAt(t, ip, null).kind()).as("排队的两次没扣 IP 令牌，还剩 1 个").isEqualTo(Admission.Kind.OK);
        assertThat(store.admitAt(t + 500, ip, zone).kind()).as("IP 桶空先回 IP_EMPTY，区桶补上的令牌不动")
                .isEqualTo(Admission.Kind.IP_EMPTY);
        assertThat(store.admitAt(t + 1000, ip, zone).kind()).isEqualTo(Admission.Kind.OK);
    }

    @Test
    void 等待毫秒向下取整_每秒补充超过1000时为0() {
        Bucket ip = new Bucket(ipKey, 100, 100);
        Bucket zone = new Bucket(zoneKey, 2000, 1);
        assertThat(store.admitAt(5_000, ip, zone).kind()).isEqualTo(Admission.Kind.OK);
        Admission empty = store.admitAt(5_000, ip, zone);
        assertThat(empty.kind()).isEqualTo(Admission.Kind.ZONE_EMPTY);
        assertThat(empty.waitMs()).as("同 Bucket4j 纳秒 / 10^6：不足 1 ms 回 0，客户端按 2 s 退避").isZero();
        assertThat(store.admitAt(5_001, ip, zone).kind()).isEqualTo(Admission.Kind.OK);
    }

    @Test
    void 生产路径取Redis服务器时间() {
        Bucket ip = new Bucket(ipKey, 100, 100);
        Bucket zone = new Bucket(zoneKey, 1, 1);
        assertThat(store.admit(ip, zone).kind()).isEqualTo(Admission.Kind.OK);
        Admission empty = store.admit(ip, zone);
        assertThat(empty.kind()).isEqualTo(Admission.Kind.ZONE_EMPTY);
        assertThat(empty.waitMs()).as("满 1 s 补一个，减去几次往返").isBetween(700L, 1000L);
    }

    @Test
    void 冷却_期间拒绝_过期放行() throws Exception {
        String key = prefix + ":cd";
        assertThat(store.tryCooldown(key, Duration.ofMillis(200))).isTrue();
        assertThat(store.tryCooldown(key, Duration.ofMillis(200))).isFalse();
        Thread.sleep(300);
        assertThat(store.tryCooldown(key, Duration.ofMillis(200))).isTrue();
        assertThat(store.tryCooldown(prefix + ":cd2", Duration.ZERO)).isTrue();
        redis.getKeys().delete(key);
    }
}
