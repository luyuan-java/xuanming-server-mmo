package com.game.guild.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 提交后失效与后台有界重试（基线 guild_manage_repo_test.go:508 的移植 + Java 增项）。 */
class GuildCacheInvalidatorTest {

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final long G = Long.MIN_VALUE + 11;

    private final InMemoryGuildCacheRedis redis = new InMemoryGuildCacheRedis();
    private final RecordingCacheMetrics metrics = new RecordingCacheMetrics();
    private final ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void shutdown() {
        background.shutdownNow();
    }

    private GuildCacheInvalidator invalidator(List<Long> delays, long budgetMillis) {
        return new GuildCacheInvalidator(redis, TTL, background, metrics, delays, budgetMillis);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("等待超时");
            }
            Thread.sleep(5);
        }
    }

    @Test
    void 失效快照与映射_0被忽略_重复的键只失效一次_代次带两倍TTL() {
        String snap = RedisKeys.guildSnapshot(G);
        String map1 = RedisKeys.guildOfPlayer(1);
        String map2 = RedisKeys.guildOfPlayer(-1L);
        redis.values.put(snap, bytes("x"));
        redis.values.put(map1, bytes("1"));
        redis.values.put(map2, bytes("1"));
        invalidator(List.of(1L), 100).afterCommit(InvalidationOp.DISBAND, G, Deadline.after(1_000), 1, 0, -1L, 1);
        assertThat(redis.values).doesNotContainKeys(snap, map1, map2);
        assertThat(redis.invalidateCalls).hasValue(3);
        assertThat(redis.ttls.get(RedisKeys.cacheGeneration(snap))).isEqualTo(TTL.toMillis() * 2);
        assertThat(redis.text(RedisKeys.cacheGeneration(map2))).isNotBlank();
    }

    @Test
    void 帮会号为0只失效映射_没有目标什么都不做() {
        invalidator(List.of(1L), 100).afterCommit(InvalidationOp.VERIFY_MAPPING, 0, List.of(), Deadline.after(1_000));
        assertThat(redis.invalidateCalls).hasValue(0);
        invalidator(List.of(1L), 100).afterCommit(InvalidationOp.VERIFY_MAPPING, 0, Deadline.after(1_000), 5);
        assertThat(redis.invalidateCalls).hasValue(1);
        assertThat(redis.values).containsKey(RedisKeys.cacheGeneration(RedisKeys.guildOfPlayer(5)));
        assertThat(redis.values).doesNotContainKey(RedisKeys.cacheGeneration(RedisKeys.guildSnapshot(0)));
    }

    @Test
    void 同步失败_后台第一轮重试成功_不计放弃() throws Exception {
        redis.failInvalidations.set(1);
        String snap = RedisKeys.guildSnapshot(G);
        redis.values.put(snap, bytes("x"));
        invalidator(List.of(1L, 1L, 1L), 1_000).afterCommit(InvalidationOp.KICK, G, Deadline.after(1_000), 7);
        // 同步那次：快照失败、映射成功
        eventually(() -> !redis.values.containsKey(snap));
        assertThat(redis.invalidateCalls).hasValue(3); // 快照 ×2 + 映射 ×1：后台只重试失败的那个键
        Thread.sleep(50);
        assertThat(metrics.gaveUp(InvalidationOp.KICK)).isZero();
    }

    @Test
    void 重试全部用尽_计一次放弃() throws Exception {
        redis.failInvalidations.set(-1);
        invalidator(List.of(1L, 1L, 1L), 1_000).afterCommit(InvalidationOp.TRANSFER, G, Deadline.after(1_000), 7);
        eventually(() -> metrics.gaveUp(InvalidationOp.TRANSFER) == 1);
        assertThat(redis.invalidateCalls).hasValue(8); // 2 个键 × (同步 1 + 后台 3)
        Thread.sleep(50);
        assertThat(metrics.gaveUp(InvalidationOp.TRANSFER)).isEqualTo(1);
    }

    @Test
    void 后台预算不够等下一轮_提前放弃() throws Exception {
        redis.failInvalidations.set(-1);
        invalidator(List.of(1L, 500L, 500L), 200).afterCommit(InvalidationOp.LEAVE, G, Deadline.after(1_000));
        eventually(() -> metrics.gaveUp(InvalidationOp.LEAVE) == 1);
        assertThat(redis.invalidateCalls).hasValue(2); // 同步 1 + 第一轮 1；第二轮 500 ms 超出 200 ms 预算
    }

    @Test
    void 缺省退避是100_400_1600毫秒_预算3秒() {
        assertThat(com.game.guild.rules.GuildLimits.INVALIDATE_RETRY_DELAYS_MS).containsExactly(100L, 400L, 1_600L);
        assertThat(com.game.guild.rules.GuildLimits.INVALIDATE_BACKGROUND_BUDGET_MS).isEqualTo(3_000L);
    }

    @Test
    void 永不抛_同步抛出的异常与已关闭的调度器都只计放弃() throws Exception {
        GuildCache.CacheRedis throwing = new InMemoryGuildCacheRedis() {
            @Override
            public CompletionStage<Void> invalidate(String generationKey, String dataKey, byte[] newGeneration,
                                                    long generationTtlMillis) {
                throw new IllegalStateException("client closed");
            }
        };
        ScheduledExecutorService closed = Executors.newSingleThreadScheduledExecutor();
        closed.shutdownNow();
        GuildCacheInvalidator invalidator = new GuildCacheInvalidator(throwing, TTL, closed, metrics, List.of(1L), 1_000);
        assertThatCode(() -> invalidator.afterCommit(InvalidationOp.CREATE, G, Deadline.after(1_000), 3))
                .doesNotThrowAnyException();
        assertThat(metrics.gaveUp(InvalidationOp.CREATE)).isEqualTo(1);

        // 预算已用完：同步那次只等 1 ms，失败的交给后台
        GuildCache.CacheRedis slow = new InMemoryGuildCacheRedis() {
            @Override
            public CompletionStage<Void> invalidate(String generationKey, String dataKey, byte[] newGeneration,
                                                    long generationTtlMillis) {
                return new CompletableFuture<>(); // 永不完成
            }
        };
        GuildCacheInvalidator stuck = new GuildCacheInvalidator(slow, TTL, background, metrics, List.of(1L), 100);
        assertThatCode(() -> stuck.afterCommit(InvalidationOp.REVIEW, G, Deadline.after(0), 3)).doesNotThrowAnyException();
        eventually(() -> metrics.gaveUp(InvalidationOp.REVIEW) == 1);
    }

    @Test
    void op标签与基线固定集合一致() {
        assertThat(java.util.Arrays.stream(InvalidationOp.values()).map(InvalidationOp::label)).containsExactly(
                "create", "set_role", "kick", "transfer", "leave", "apply", "cancel", "review", "disband", "announcement",
                "verify_mapping", "score", "upgrade", "asset_finalize", "activity", "trial_settle", "donate", "shop",
                "insert_guard");
        assertThat(InvalidationOp.ofLabel("verify_mapping")).isEqualTo(InvalidationOp.VERIFY_MAPPING);
        assertThatThrownBy(() -> InvalidationOp.ofLabel("nope")).isInstanceOf(IllegalArgumentException.class);
    }
}
