package com.game.guild.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GuildApplyQuotaTest {
    private static final RedisKeys KEYS = new RedisKeys(GameNamespace.of("guildquota"));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    @AfterEach
    void closeMeters() {
        meters.close();
    }

    @Test
    void 两个实例合用每玩家配额_身份和命名空间不窄化() {
        Map<String, Long> counts = new ConcurrentHashMap<>();
        Function<String, java.util.concurrent.CompletionStage<Long>> incr = key ->
                CompletableFuture.completedFuture(counts.merge(key, 1L, Long::sum));
        GuildApplyQuota first = new GuildApplyQuota(incr, KEYS, 2, meters);
        GuildApplyQuota second = new GuildApplyQuota(incr, KEYS, 2, meters);
        assertThat(first.tryAcquire(-1L, Deadline.after(1000))).isTrue();
        assertThat(second.tryAcquire(-1L, Deadline.after(1000))).isTrue();
        assertThat(first.tryAcquire(-1L, Deadline.after(1000))).isFalse();
        assertThat(second.tryAcquire(1, Deadline.after(1000))).isTrue();
        assertThat(counts).containsEntry("guildquota:guild:{apply:18446744073709551615}", 3L)
                .containsEntry("guildquota:guild:{apply:1}", 1L);
        assertThat(meters.get("game.guild.apply.quota").tag("result", "rejected").counter().count()).isEqualTo(1);
        assertThat(meters.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey()).containsExactly("result"));
    }

    @Test
    void Redis异常空值和非正数是可观测的背压降级() {
        for (Long result : java.util.Arrays.asList(null, 0L, -1L)) {
            GuildApplyQuota quota = new GuildApplyQuota(k -> CompletableFuture.completedFuture(result), KEYS, 10, meters);
            assertThat(quota.tryAcquire(1, Deadline.after(1000))).isTrue();
        }
        GuildApplyQuota sync = new GuildApplyQuota(k -> { throw new IllegalStateException("Redis 不可用"); }, KEYS, 10, meters);
        assertThat(sync.tryAcquire(1, Deadline.after(1000))).isTrue();
        GuildApplyQuota async = new GuildApplyQuota(k -> CompletableFuture.failedFuture(new IllegalStateException()),
                KEYS, 10, meters);
        assertThat(async.tryAcquire(1, Deadline.after(1000))).isTrue();
        assertThat(meters.get("game.guild.apply.quota").tag("result", "error").counter().count()).isEqualTo(5);
    }

    @Test
    void 子预算到期取消等待_父预算仍有效可降级() {
        CompletableFuture<Long> pending = new CompletableFuture<>();
        GuildApplyQuota quota = new GuildApplyQuota(k -> pending, KEYS, 10, meters);
        assertThat(quota.tryAcquire(1, Deadline.after(3000))).isTrue();
        assertThat(pending.isCancelled()).isTrue();
        assertThat(meters.get("game.guild.apply.quota").tag("result", "error").counter().count()).isEqualTo(1);
    }

    @Test
    void 父预算过期或线程取消绝不因降级继续() {
        AtomicInteger calls = new AtomicInteger();
        GuildApplyQuota quota = new GuildApplyQuota(k -> {
            calls.incrementAndGet();
            return CompletableFuture.completedFuture(1L);
        }, KEYS, 10, meters);
        assertThatThrownBy(() -> quota.tryAcquire(1, Deadline.after(-1))).isInstanceOf(DependencyException.class);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> quota.tryAcquire(1, Deadline.after(1000))).isInstanceOf(DependencyException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(calls.get()).isZero();
        CompletableFuture<Long> pending = new CompletableFuture<>();
        GuildApplyQuota waiting = new GuildApplyQuota(k -> pending, KEYS, 10, meters);
        assertThatThrownBy(() -> waiting.tryAcquire(1, Deadline.after(15))).isInstanceOf(DependencyException.class);
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test
    void 配额范围和零玩家身份必须拒绝() {
        for (int invalid : new int[]{-1, 0, 601}) {
            assertThatThrownBy(() -> new GuildApplyQuota(k -> CompletableFuture.completedFuture(1L), KEYS, invalid, meters))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        GuildApplyQuota quota = new GuildApplyQuota(k -> CompletableFuture.completedFuture(1L), KEYS, 600, meters);
        assertThatThrownBy(() -> quota.tryAcquire(0, Deadline.after(1000))).isInstanceOf(IllegalArgumentException.class);
    }
}
