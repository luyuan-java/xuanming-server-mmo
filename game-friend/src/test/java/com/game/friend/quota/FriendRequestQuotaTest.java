package com.game.friend.quota;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.friend.metrics.FriendMetrics;
import com.game.common.deadline.Deadline;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FriendRequestQuotaTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FriendMetrics metrics = new FriendMetrics(registry);

    private double count(String outcome) {
        return registry.get("game.friend.request.quota").tag("outcome", outcome).counter().count();
    }

    @Test
    void 第limit次恰好用完仍放行_超过才拒_键带玩家号() {
        AtomicLong n = new AtomicLong();
        List<String> keys = new ArrayList<>();
        FriendRequestQuota quota = new FriendRequestQuota(key -> {
            keys.add(key);
            return CompletableFuture.completedFuture(n.incrementAndGet());
        }, REDIS_KEYS, 2, metrics);
        assertThat(quota.tryAcquire(-1L, Deadline.after(1000))).isTrue();
        assertThat(quota.tryAcquire(-1L, Deadline.after(1000))).isTrue();
        assertThat(quota.tryAcquire(-1L, Deadline.after(1000))).isFalse();
        assertThat(keys).containsOnly(REDIS_KEYS.friendRequestQuota(-1L));
        assertThat(keys.get(0)).isEqualTo("game:friend:{18446744073709551615}:quota");
        assertThat(count("allowed")).isEqualTo(2);
        assertThat(count("rejected")).isEqualTo(1);
    }

    /** 计数器键只跟注入的 RedisKeys 走：同一个 JVM 里 game 与 nstest 两套各数各的（发申请配额与在线目录配额）。 */
    @Test
    void 计数器键跟注入的命名空间走() {
        RedisKeys nstest = new RedisKeys(GameNamespace.of("nstest"));
        List<String> keys = new ArrayList<>();
        new FriendRequestQuota(key -> {
            keys.add(key);
            return CompletableFuture.completedFuture(1L);
        }, nstest, 2, metrics).tryAcquire(-1L, Deadline.after(1000));
        new FriendRequestQuota(key -> {
            keys.add(key);
            return CompletableFuture.completedFuture(1L);
        }, REDIS_KEYS, 2, metrics).tryAcquire(-1L, Deadline.after(1000));
        new DirectoryQuota(key -> {
            keys.add(key);
            return CompletableFuture.completedFuture(1L);
        }, nstest, metrics).tryAcquire(7, Deadline.after(1000));

        assertThat(keys).containsExactly("nstest:friend:{18446744073709551615}:quota",
                "game:friend:{18446744073709551615}:quota", nstest.friendDirectoryQuota(7));
        assertThat(keys.get(2)).startsWith("nstest:");
    }

    @Test
    void Redis出错或超时放行并计error_limit为0放行不计() {
        FriendRequestQuota failing = new FriendRequestQuota(
                key -> CompletableFuture.failedFuture(new IllegalStateException("down")), REDIS_KEYS, 10, metrics);
        assertThat(failing.tryAcquire(1, Deadline.after(1000))).isTrue();
        FriendRequestQuota hanging = new FriendRequestQuota(key -> new CompletableFuture<>(), REDIS_KEYS, 10, metrics);
        assertThat(hanging.tryAcquire(1, Deadline.after(20))).isTrue();
        assertThat(count("error")).isEqualTo(2);

        FriendRequestQuota unlimited = new FriendRequestQuota(key -> {
            throw new AssertionError("不该调用");
        }, REDIS_KEYS, 0, metrics);
        assertThat(unlimited.tryAcquire(1, Deadline.after(1000))).isTrue();
        assertThat(count("allowed") + count("rejected")).isZero();
    }
}
