package com.game.guild.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/** 申请推送冷却（基线 guild_manage_repo_test.go:570；假 Redis 版）。 */
class ApplyPushCooldownTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));

    private final Map<String, Long> keys = new ConcurrentHashMap<>();

    @Test
    void 窗口内同一帮会同一申请人只放行一次_不同组合互不影响() {
        ApplyPushCooldown cooldown = new ApplyPushCooldown((key, ttl) ->
                CompletableFuture.completedFuture(keys.putIfAbsent(key, ttl) == null), REDIS_KEYS);
        long g = Long.MIN_VALUE + 3;
        assertThat(cooldown.tryMark(g, 5, Deadline.after(1_000))).isTrue();
        assertThat(cooldown.tryMark(g, 5, Deadline.after(1_000))).isFalse();
        assertThat(cooldown.tryMark(g, 6, Deadline.after(1_000))).isTrue();
        assertThat(cooldown.tryMark(g + 1, 5, Deadline.after(1_000))).isTrue();
        assertThat(keys).containsEntry(REDIS_KEYS.guildApplyPush(g, 5), 60_000L);
        assertThat(REDIS_KEYS.guildApplyPush(g, 5)).isEqualTo("game:guild:{g:9223372036854775811}:apply-push:5");
    }

    @Test
    void 冷却键带注入的命名空间() {
        ApplyPushCooldown cooldown = new ApplyPushCooldown((key, ttl) ->
                CompletableFuture.completedFuture(keys.putIfAbsent(key, ttl) == null), new RedisKeys(GameNamespace.of("nstest")));
        assertThat(cooldown.tryMark(7, 5, Deadline.after(1_000))).isTrue();
        assertThat(keys).containsOnlyKeys("nstest:guild:{g:7}:apply-push:5");
    }

    @Test
    void Redis出错或超时都不推_从不抛() {
        ApplyPushCooldown failing = new ApplyPushCooldown((key, ttl) ->
                CompletableFuture.failedFuture(new IllegalStateException("redis down")), REDIS_KEYS);
        assertThat(failing.tryMark(1, 2, Deadline.after(1_000))).isFalse();

        ApplyPushCooldown throwing = new ApplyPushCooldown((key, ttl) -> {
            throw new IllegalStateException("client closed");
        }, REDIS_KEYS);
        assertThat(throwing.tryMark(1, 2, Deadline.after(1_000))).isFalse();

        ApplyPushCooldown stuck = new ApplyPushCooldown((key, ttl) -> new CompletableFuture<>(), REDIS_KEYS);
        assertThat(stuck.tryMark(1, 2, Deadline.after(20))).isFalse();
    }
}
