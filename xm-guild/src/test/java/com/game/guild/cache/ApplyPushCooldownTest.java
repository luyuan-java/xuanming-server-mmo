package com.game.guild.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** 申请推送冷却（基线 guild_manage_repo_test.go:570；假 Redis 版）。 */
class ApplyPushCooldownTest {

    private final Map<String, Long> keys = new ConcurrentHashMap<>();

    @Test
    void 窗口内同一帮会同一申请人只放行一次_不同组合互不影响() {
        ApplyPushCooldown cooldown = new ApplyPushCooldown((key, ttl) ->
                CompletableFuture.completedFuture(keys.putIfAbsent(key, ttl) == null));
        long g = Long.MIN_VALUE + 3;
        assertThat(cooldown.tryMark(g, 5, Deadline.after(1_000))).isTrue();
        assertThat(cooldown.tryMark(g, 5, Deadline.after(1_000))).isFalse();
        assertThat(cooldown.tryMark(g, 6, Deadline.after(1_000))).isTrue();
        assertThat(cooldown.tryMark(g + 1, 5, Deadline.after(1_000))).isTrue();
        assertThat(keys).containsEntry(RedisKeys.guildApplyPush(g, 5), 60_000L);
        assertThat(RedisKeys.guildApplyPush(g, 5)).isEqualTo("xm:guild:{g:9223372036854775811}:apply-push:5");
    }

    @Test
    void Redis出错或超时都不推_从不抛() {
        ApplyPushCooldown failing = new ApplyPushCooldown((key, ttl) ->
                CompletableFuture.failedFuture(new IllegalStateException("redis down")));
        assertThat(failing.tryMark(1, 2, Deadline.after(1_000))).isFalse();

        ApplyPushCooldown throwing = new ApplyPushCooldown((key, ttl) -> {
            throw new IllegalStateException("client closed");
        });
        assertThat(throwing.tryMark(1, 2, Deadline.after(1_000))).isFalse();

        ApplyPushCooldown stuck = new ApplyPushCooldown((key, ttl) -> new CompletableFuture<>());
        assertThat(stuck.tryMark(1, 2, Deadline.after(20))).isFalse();
    }
}
