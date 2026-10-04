package com.game.login.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.discovery.RedisKeys;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/** 连真 Redis（{@code -Dxm.it.redis=...}，缺省跳过）：设备数上限的先判再登记、续期、注销、按有效期自愈。 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class RedisLoginDevicesIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(30);

    private RedissonClient redis;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T00:00:00Z"));
    private RedisLoginDevices devices;
    private final String account = "robot_it_" + UUID.randomUUID().toString().substring(0, 8);
    private final String other = account + "b";

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(13);
        redis = Redisson.create(config);
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now.get();
            }
        };
        devices = new RedisLoginDevices(redis, clock, TTL, 3);
    }

    @AfterEach
    void tearDown() {
        redis.getKeys().delete(RedisKeys.loginDevices(account), RedisKeys.loginDevices(other),
                RedisKeys.loginDeviceSession("g/1"), RedisKeys.loginDeviceSession("g/2"), RedisKeys.loginDeviceSession("g/9"));
        redis.shutdown();
    }

    private RScoredSortedSet<String> set() {
        return redis.getScoredSortedSet(RedisKeys.loginDevices(account), StringCodec.INSTANCE);
    }

    @Test
    void 窗口内三个放行_第四个拒绝且不登记_已登记的会话续期放行() {
        assertThat(devices.admit(account, "g/1")).isTrue();
        assertThat(devices.admit(account, "g/2")).isTrue();
        assertThat(devices.admit(account, "g/3")).isTrue();
        assertThat(devices.admit(account, "g/4")).isFalse();
        assertThat(set().readAll()).containsExactlyInAnyOrder("g/1", "g/2", "g/3");

        now.set(now.get().plusSeconds(60));
        assertThat(devices.admit(account, "g/1")).isTrue();
        assertThat(set().getScore("g/1")).isEqualTo((double) now.get().plus(TTL).toEpochMilli());
        assertThat(set().remainTimeToLive()).isBetween(TTL.toMillis() - 60_000, TTL.toMillis());
    }

    @Test
    void 注销腾出名额_不在集合里也成功() {
        devices.admit(account, "g/1");
        devices.admit(account, "g/2");
        devices.admit(account, "g/3");
        devices.leave("g/2", account);
        devices.leave("g/9", account);
        assertThat(devices.admit(account, "g/4")).isTrue();
    }

    @Test
    void 过了有效期没注销的自愈() {
        devices.admit(account, "g/1");
        devices.admit(account, "g/2");
        now.set(now.get().plus(TTL).minusSeconds(1));
        devices.admit(account, "g/3");
        assertThat(devices.admit(account, "g/4")).isFalse();
        now.set(now.get().plusSeconds(2));
        assertThat(devices.admit(account, "g/4")).as("g/1、g/2 已过期").isTrue();
        assertThat(set().readAll()).containsExactlyInAnyOrder("g/3", "g/4");
    }

    @Test
    void 登录成功记下归属_换号成功从旧名单注销_会话结束按归属注销_gate记的账号为空也行() {
        devices.admit(account, "g/1");
        devices.bind(account, "g/1", "");
        assertThat(redis.<String>getBucket(RedisKeys.loginDeviceSession("g/1"), StringCodec.INSTANCE).get())
                .isEqualTo(account);
        assertThat(redis.getBucket(RedisKeys.loginDeviceSession("g/1")).remainTimeToLive())
                .isBetween(TTL.toMillis() - 60_000, TTL.toMillis());

        devices.admit(other, "g/1");
        devices.bind(other, "g/1", account);
        assertThat(set().contains("g/1")).as("换号成功后旧账号名单里没有它").isFalse();
        RScoredSortedSet<String> otherSet = redis.getScoredSortedSet(RedisKeys.loginDevices(other), StringCodec.INSTANCE);
        assertThat(otherSet.contains("g/1")).isTrue();

        devices.leave("g/1", "");
        assertThat(otherSet.contains("g/1")).isFalse();
        assertThat(redis.getBucket(RedisKeys.loginDeviceSession("g/1")).isExists()).isFalse();
    }

    @Test
    void 撤销只摘本账号名单里的这一个() {
        devices.admit(account, "g/1");
        devices.admit(account, "g/2");
        devices.revoke(account, "g/1");
        assertThat(set().readAll()).containsExactly("g/2");
    }
}
