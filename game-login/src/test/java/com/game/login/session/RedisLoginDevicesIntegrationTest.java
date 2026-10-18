package com.game.login.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.login.session.LoginDevices.Admission;
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

/** 连真 Redis（{@code -Dgame.it.redis=...}，缺省跳过）：设备数上限的先判再登记、续期、注销、按有效期自愈。 */
@EnabledIfSystemProperty(named = "game.it.redis", matches = ".+")
class RedisLoginDevicesIntegrationTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));

    private static final Duration TTL = Duration.ofMinutes(30);

    private RedissonClient redis;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T00:00:00Z"));
    private RedisLoginDevices devices;
    private final String account = "robot_it_" + UUID.randomUUID().toString().substring(0, 8);
    private final String other = account + "b";

    @BeforeEach
    void setUp() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("game.it.redis")).setDatabase(13);
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
        devices = new RedisLoginDevices(redis, REDIS_KEYS, clock, TTL, 3);
    }

    @AfterEach
    void tearDown() {
        redis.getKeys().delete(REDIS_KEYS.loginDevices(account), REDIS_KEYS.loginDevices(other),
                REDIS_KEYS.loginDeviceSession("g/1"), REDIS_KEYS.loginDeviceSession("g/2"), REDIS_KEYS.loginDeviceSession("g/9"));
        redis.shutdown();
    }

    private RScoredSortedSet<String> set() {
        return redis.getScoredSortedSet(REDIS_KEYS.loginDevices(account), StringCodec.INSTANCE);
    }

    @Test
    void 窗口内三个放行_第四个拒绝且不登记_已登记的会话续期放行() {
        assertThat(devices.admit(account, "g/1")).isEqualTo(Admission.ADMITTED);
        assertThat(devices.admit(account, "g/2")).isEqualTo(Admission.ADMITTED);
        assertThat(devices.admit(account, "g/3")).isEqualTo(Admission.ADMITTED);
        assertThat(devices.admit(account, "g/4")).isEqualTo(Admission.FULL);
        assertThat(set().readAll()).containsExactlyInAnyOrder("g/1", "g/2", "g/3");

        now.set(now.get().plusSeconds(60));
        assertThat(devices.admit(account, "g/1")).isEqualTo(Admission.ADMITTED);
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
        assertThat(devices.admit(account, "g/4")).isEqualTo(Admission.ADMITTED);
    }

    @Test
    void 过了有效期没注销的自愈() {
        devices.admit(account, "g/1");
        devices.admit(account, "g/2");
        now.set(now.get().plus(TTL).minusSeconds(1));
        devices.admit(account, "g/3");
        assertThat(devices.admit(account, "g/4")).isEqualTo(Admission.FULL);
        now.set(now.get().plusSeconds(2));
        assertThat(devices.admit(account, "g/4")).as("g/1、g/2 已过期").isEqualTo(Admission.ADMITTED);
        assertThat(set().readAll()).containsExactlyInAnyOrder("g/3", "g/4");
    }

    @Test
    void 登录成功记下归属_换号成功从旧名单注销_会话结束按归属注销_gate记的账号为空也行() {
        devices.admit(account, "g/1");
        devices.bind(account, "g/1", "");
        assertThat(redis.<String>getBucket(REDIS_KEYS.loginDeviceSession("g/1"), StringCodec.INSTANCE).get())
                .isEqualTo(account);
        assertThat(redis.getBucket(REDIS_KEYS.loginDeviceSession("g/1")).remainTimeToLive())
                .isBetween(TTL.toMillis() - 60_000, TTL.toMillis());

        devices.admit(other, "g/1");
        devices.bind(other, "g/1", account);
        assertThat(set().contains("g/1")).as("换号成功后旧账号名单里没有它").isFalse();
        RScoredSortedSet<String> otherSet = redis.getScoredSortedSet(REDIS_KEYS.loginDevices(other), StringCodec.INSTANCE);
        assertThat(otherSet.contains("g/1")).isTrue();

        devices.leave("g/1", "");
        assertThat(otherSet.contains("g/1")).isFalse();
        assertThat(redis.getBucket(REDIS_KEYS.loginDeviceSession("g/1")).isExists()).isFalse();
    }

    @Test
    void 撤销只摘本账号名单里的这一个() {
        devices.admit(account, "g/1");
        devices.admit(account, "g/2");
        devices.revoke(account, "g/1");
        assertThat(set().readAll()).containsExactly("g/2");
    }

    // ---------------------------------------------------------------- 关闭墓碑（迟到写，mmorpg client-request-ordering.md §6.1）

    private org.redisson.api.RBucket<String> reverse(String sessionKey) {
        return redis.getBucket(REDIS_KEYS.loginDeviceSession(sessionKey), StringCodec.INSTANCE);
    }

    @Test
    void 会话结束按归属注销并留短期墓碑_迟到的续期与绑定都不写_离开窗口不清墓碑_别的会话不受影响() {
        devices.admit(account, "g/1");
        devices.bind(account, "g/1", "");

        devices.close("g/1", "");

        assertThat(set().contains("g/1")).as("按记下的归属注销").isFalse();
        assertThat(reverse("g/1").get()).isEqualTo(RedisLoginDevices.CLOSED_MARKER);
        assertThat(reverse("g/1").remainTimeToLive()).as("墓碑是短期的，不是登记的 30 分钟")
                .isBetween(1L, LoginDevices.CLOSED_TOMBSTONE_TTL.toMillis());

        assertThat(devices.admit(account, "g/1")).isEqualTo(Admission.CLOSED);
        assertThat(devices.bind(account, "g/1", "")).isFalse();
        devices.leave("g/1", account);
        assertThat(devices.admit(account, "g/1")).as("迟到的进场应答走 leave，墓碑仍在").isEqualTo(Admission.CLOSED);
        assertThat(set().contains("g/1")).isFalse();
        assertThat(reverse("g/1").get()).isEqualTo(RedisLoginDevices.CLOSED_MARKER);
        assertThat(reverse("g/1").remainTimeToLive()).as("续期脚本没有把墓碑续成登记的有效期")
                .isLessThanOrEqualTo(LoginDevices.CLOSED_TOMBSTONE_TTL.toMillis());

        assertThat(devices.admit(account, "g/9")).isEqualTo(Admission.ADMITTED);
    }

    @Test
    void 登录在途时会话结束_结束时还不知道归属_迟到的绑定撤销本次登记且不记归属() {
        // 新会话第一次登录：admit 之后、bind 之前 gate 注销了会话（gate 记的账号为空，反查键也还没写），close 找不到它计在哪个账号下。
        assertThat(devices.admit(account, "g/2")).isEqualTo(Admission.ADMITTED);
        devices.close("g/2", "");
        assertThat(set().contains("g/2")).isTrue();

        assertThat(devices.bind(account, "g/2", "")).isFalse();

        assertThat(set().contains("g/2")).as("迟到的绑定撤销了这次登录的登记").isFalse();
        assertThat(reverse("g/2").get()).isEqualTo(RedisLoginDevices.CLOSED_MARKER);
    }

    @Test
    void 换号登录在途时会话结束_旧账号按gate给的账号注销_新账号的登记由迟到的绑定撤销() {
        devices.admit(account, "g/9");
        devices.bind(account, "g/9", "");
        RScoredSortedSet<String> otherSet = redis.getScoredSortedSet(REDIS_KEYS.loginDevices(other), StringCodec.INSTANCE);
        assertThat(devices.admit(other, "g/9")).isEqualTo(Admission.ADMITTED);

        devices.close("g/9", account);
        assertThat(set().contains("g/9")).isFalse();
        assertThat(devices.bind(other, "g/9", account)).isFalse();

        assertThat(otherSet.contains("g/9")).isFalse();
        assertThat(reverse("g/9").get()).isEqualTo(RedisLoginDevices.CLOSED_MARKER);
    }
}
