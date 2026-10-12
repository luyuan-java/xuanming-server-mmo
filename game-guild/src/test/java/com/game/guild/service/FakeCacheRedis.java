package com.game.guild.service;

import com.game.guild.cache.GuildCache;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 服务单测用的内存版缓存 Redis（语义同 {@code RedissonGuildCacheRedis} 的 Lua：代次比较后回填、换代次 + 删数据键）。
 * 每次失效把数据键交给 {@code journal}，用来断言「推送发生在缓存失效之后」。{@link #failReads} 打开后所有读都异常完成。
 */
final class FakeCacheRedis implements GuildCache.CacheRedis {

    private final Map<String, byte[]> values = new HashMap<>();
    private final Consumer<String> journal;
    volatile boolean failReads;

    FakeCacheRedis(Consumer<String> journal) {
        this.journal = journal;
    }

    @Override
    public synchronized CompletionStage<byte[]> get(String key) {
        if (failReads) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        byte[] v = values.get(key);
        return CompletableFuture.completedFuture(v == null ? null : v.clone());
    }

    @Override
    public synchronized CompletionStage<Map<String, byte[]>> getAll(List<String> keys) {
        if (failReads) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        Map<String, byte[]> out = new HashMap<>();
        for (String k : keys) {
            if (values.containsKey(k)) {
                out.put(k, values.get(k).clone());
            }
        }
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public synchronized CompletionStage<byte[]> generation(String generationKey, byte[] candidate, long ttlMillis) {
        if (failReads) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        values.putIfAbsent(generationKey, candidate.clone());
        return CompletableFuture.completedFuture(values.get(generationKey).clone());
    }

    @Override
    public synchronized CompletionStage<Boolean> fill(String generationKey, String dataKey, byte[] expectedGeneration,
                                                      byte[] payload, long ttlMillis) {
        byte[] current = values.get(generationKey);
        if (current == null || !Arrays.equals(current, expectedGeneration)) {
            return CompletableFuture.completedFuture(false);
        }
        values.put(dataKey, payload.clone());
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletionStage<Void> invalidate(String generationKey, String dataKey, byte[] newGeneration,
                                                         long generationTtlMillis) {
        values.put(generationKey, newGeneration.clone());
        values.remove(dataKey);
        journal.accept("invalidate " + dataKey);
        return CompletableFuture.completedFuture(null);
    }

    synchronized boolean has(String key) {
        return values.containsKey(key);
    }
}
