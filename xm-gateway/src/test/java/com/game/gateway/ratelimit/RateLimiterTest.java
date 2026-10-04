package com.game.gateway.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 限流的判定顺序与结局（分波 → 区桶 → IP 桶 → 账号冷却）、存储出错放行、配置校验。存储是内存实现（真 Redis 见集成测试）。 */
class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);

    /** 内存令牌桶（与 Redis 版同一套贪心补充算法）与冷却。 */
    private final class MemoryStore implements RateLimitStore {
        final Map<String, double[]> buckets = new HashMap<>();
        final Map<String, Long> cooldowns = new HashMap<>();
        long cooldownNow;
        boolean broken;
        int calls;

        @Override
        public Admission admit(Bucket ip, Bucket zone) {
            calls++;
            if (broken) {
                throw new IllegalStateException("Redis 不可达");
            }
            double[] ipBucket = refill(ip);
            if (ipBucket[0] < 1) {
                return new Admission(Admission.Kind.IP_EMPTY, 0, 0);
            }
            if (zone == null) {
                ipBucket[0] -= 1;
                return new Admission(Admission.Kind.OK, 0, (long) ipBucket[0]);
            }
            double[] zoneBucket = refill(zone);
            if (zoneBucket[0] < 1) {
                return new Admission(Admission.Kind.ZONE_EMPTY, (long) Math.floor((1 - zoneBucket[0]) * 1000 / zone.rps()),
                        0);
            }
            ipBucket[0] -= 1;
            zoneBucket[0] -= 1;
            return new Admission(Admission.Kind.OK, 0, (long) zoneBucket[0]);
        }

        private double[] refill(Bucket bucket) {
            long nowMs = now.get();
            double[] b = buckets.computeIfAbsent(bucket.key(), k -> new double[] {bucket.burst(), nowMs});
            b[0] = Math.min(bucket.burst(), b[0] + (nowMs - b[1]) * bucket.rps() / 1000.0);
            b[1] = nowMs;
            return b;
        }

        @Override
        public boolean tryCooldown(String key, Duration cooldown) {
            Long until = cooldowns.get(key);
            if (until != null && until > cooldownNow) {
                return false;
            }
            cooldowns.put(key, cooldownNow + cooldown.toMillis());
            return true;
        }
    }

    private static RateLimitSettings settings(Map<Integer, RateLimitSettings.ZoneLimit> overrides,
                                              RateLimitSettings.Wave wave) {
        return settings(overrides, wave, 2L);
    }

    private static RateLimitSettings settings(Map<Integer, RateLimitSettings.ZoneLimit> overrides,
                                              RateLimitSettings.Wave wave, long ipBurst) {
        return new RateLimitSettings(true, 500L, 100L, overrides, 2L, ipBurst, 5000L, List.of(), wave);
    }

    @Test
    void 关闭时一律放行() {
        RateLimiter limiter = new RateLimiter(RateLimitSettings.disabled(), new MemoryStore(), now::get);
        for (int i = 0; i < 100; i++) {
            assertThat(limiter.check(1, "1.2.3.4", "a", RateLimiter.Scope.ASSIGN).kind())
                    .isEqualTo(RateLimitDecision.Kind.PASS);
        }
    }

    @Test
    void 区桶空了排队_带补一个令牌要等的毫秒() {
        MemoryStore store = new MemoryStore();
        RateLimiter limiter = new RateLimiter(settings(Map.of(1, new RateLimitSettings.ZoneLimit(1L, 2L)), null), store,
                now::get);
        assertThat(limiter.check(1, "10.0.0.1", null, RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
        assertThat(limiter.check(1, "10.0.0.2", null, RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
        RateLimitDecision queued = limiter.check(1, "10.0.0.3", null, RateLimiter.Scope.ASSIGN);
        assertThat(queued.kind()).isEqualTo(RateLimitDecision.Kind.QUEUE);
        assertThat(queued.retryAfterMs()).isEqualTo(1000);
        now.addAndGet(1000);
        assertThat(limiter.check(1, "10.0.0.3", null, RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
    }

    @Test
    void 同IP超频429() {
        RateLimiter limiter = new RateLimiter(settings(null, null), new MemoryStore(), now::get);
        assertThat(limiter.check(1, "1.1.1.1", null, RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
        assertThat(limiter.check(1, "1.1.1.1", null, RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
        assertThat(limiter.check(1, "1.1.1.1", null, RateLimiter.Scope.ASSIGN).reason()).isEqualTo("IP_RATE_LIMIT");
        assertThat(limiter.check(1, "1.1.1.9", null, RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
    }

    @Test
    void 同身份同IP冷却429_冷却按端点分开_别的IP报同一账号不受影响() {
        MemoryStore store = new MemoryStore();
        RateLimiter limiter = new RateLimiter(settings(null, null, 10L), store, now::get);

        assertThat(limiter.check(1, "2.2.2.2", "robot_0001", RateLimiter.Scope.LOGIN).kind())
                .isEqualTo(RateLimitDecision.Kind.PASS);
        assertThat(limiter.check(1, "2.2.2.2", "robot_0001", RateLimiter.Scope.ASSIGN).kind())
                .as("login 成功紧接着 assign-gate 是正常顺序").isEqualTo(RateLimitDecision.Kind.PASS);
        assertThat(limiter.check(1, "2.2.2.2", "robot_0001", RateLimiter.Scope.ASSIGN).reason()).isEqualTo("ACCOUNT_COOLDOWN");
        assertThat(limiter.check(1, "3.3.3.3", "robot_0001", RateLimiter.Scope.ASSIGN).kind())
                .as("别人在别的 IP 报这个账号挡不住他").isEqualTo(RateLimitDecision.Kind.PASS);
        assertThat(store.cooldowns.keySet()).as("键里没有原账号").noneMatch(k -> k.contains("robot_0001"));
        store.cooldownNow += 5000;
        assertThat(limiter.check(1, "2.2.2.2", "robot_0001", RateLimiter.Scope.ASSIGN).kind())
                .isEqualTo(RateLimitDecision.Kind.PASS);
    }

    @Test
    void 分波_没开放的区排队_retry_after是距开放的秒数() {
        long start = now.get() / 1000;
        RateLimitSettings.Wave wave = new RateLimitSettings.Wave(true, start, List.of(
                new RateLimitSettings.WaveStep(0, List.of(1L)), new RateLimitSettings.WaveStep(600, List.of(-1L))));
        RateLimiter limiter = new RateLimiter(settings(null, wave), new MemoryStore(), now::get);
        assertThat(limiter.check(1, "1.1.1.1", null, RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
        RateLimitDecision closed = limiter.check(2, "1.1.1.2", null, RateLimiter.Scope.ASSIGN);
        assertThat(closed.kind()).isEqualTo(RateLimitDecision.Kind.QUEUE);
        assertThat(closed.retryAfterMs()).isEqualTo(600_000);
        assertThat(closed.queuePos()).isEqualTo(-1);
        now.addAndGet(600_000);
        assertThat(limiter.check(2, "1.1.1.2", null, RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
    }

    @Test
    void 存储出错放行_之后一段时间不再碰存储() {
        MemoryStore store = new MemoryStore();
        store.broken = true;
        RateLimiter limiter = new RateLimiter(settings(null, null), store, now::get);
        assertThat(limiter.check(1, "1.1.1.1", "a", RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
        assertThat(store.calls).isEqualTo(1);
        assertThat(limiter.check(1, "1.1.1.1", "a", RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
        assertThat(store.calls).as("放行窗口内不再等 Redis 超时").isEqualTo(1);
        store.broken = false;
        now.addAndGet(RateLimiter.FAIL_OPEN_HOLD.toMillis());
        assertThat(limiter.check(1, "1.1.1.1", "a", RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
        assertThat(store.calls).as("窗口过了照常判").isGreaterThan(1);
    }

    @Test
    void 先判IP桶再判区桶_被IP拒掉的请求不吃区令牌() {
        MemoryStore store = new MemoryStore();
        RateLimiter limiter = new RateLimiter(settings(Map.of(1, new RateLimitSettings.ZoneLimit(1L, 3L)), null), store,
                now::get);
        for (int i = 0; i < 10; i++) {
            limiter.check(1, "6.6.6.6", null, RateLimiter.Scope.ASSIGN);
        }
        assertThat(limiter.check(1, "6.6.6.7", null, RateLimiter.Scope.ASSIGN).kind())
                .as("单 IP 狂发只吃掉 IP 桶允许的 2 个区令牌").isEqualTo(RateLimitDecision.Kind.PASS);
    }

    @Test
    void 区桶空时排队不吃IP令牌_同一NAT后面等区桶的人不会被429() {
        MemoryStore store = new MemoryStore();
        RateLimiter limiter = new RateLimiter(settings(Map.of(1, new RateLimitSettings.ZoneLimit(1L, 1L)), null), store,
                now::get);
        assertThat(limiter.check(1, "9.9.9.9", null, RateLimiter.Scope.ASSIGN).kind()).isEqualTo(RateLimitDecision.Kind.PASS);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.check(1, "9.9.9.9", null, RateLimiter.Scope.ASSIGN).kind())
                    .isEqualTo(RateLimitDecision.Kind.QUEUE);
        }
        assertThat(limiter.check(2, "9.9.9.9", null, RateLimiter.Scope.ASSIGN).kind())
                .as("IP 桶还剩 1 个：排队的那 5 次没扣").isEqualTo(RateLimitDecision.Kind.PASS);
    }

    @Test
    void 区号没核对上时照判分波_不判区桶() {
        MemoryStore store = new MemoryStore();
        RateLimitSettings.Wave wave = new RateLimitSettings.Wave(true, now.get() / 1000,
                List.of(new RateLimitSettings.WaveStep(0, List.of(1L))));
        RateLimiter limiter = new RateLimiter(settings(null, wave), store, now::get);
        assertThat(limiter.checkUnverifiedZone(1, "1.1.1.1", "a", RateLimiter.Scope.LOGIN).kind())
                .isEqualTo(RateLimitDecision.Kind.PASS);
        assertThat(store.buckets.keySet()).noneMatch(k -> k.contains(":zone:"));
        assertThat(limiter.checkUnverifiedZone(1, "1.1.1.1", "a", RateLimiter.Scope.LOGIN).reason())
                .isEqualTo("ACCOUNT_COOLDOWN");
        assertThat(limiter.checkUnverifiedZone(2, "1.1.1.2", null, RateLimiter.Scope.LOGIN).kind()).as("二区不在波次里")
                .isEqualTo(RateLimitDecision.Kind.QUEUE);
    }

    @Test
    void 打开分波必须给起点_参数非正拒绝() {
        assertThatThrownBy(() -> new RateLimitSettings.Wave(true, null, List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("start-epoch-sec");
        assertThatThrownBy(() -> new RateLimitSettings(true, 0L, null, null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
