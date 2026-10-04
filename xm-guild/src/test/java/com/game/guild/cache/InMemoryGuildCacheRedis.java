package com.game.guild.cache;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 测试用：语义与 {@link RedissonGuildCacheRedis} 三段 Lua 相同的内存实现（不模拟 TTL，只记下来；「服务器时间」用自增计数代替），可注入故障。
 */
class InMemoryGuildCacheRedis implements GuildCache.CacheRedis {

    final Map<String, byte[]> values = new ConcurrentHashMap<>();
    final Map<String, Long> ttls = new ConcurrentHashMap<>();
    private final AtomicLong serverTime = new AtomicLong();
    final AtomicInteger getCalls = new AtomicInteger();
    final AtomicInteger getAllCalls = new AtomicInteger();
    final AtomicInteger invalidateCalls = new AtomicInteger();
    volatile boolean failGet;
    volatile boolean failGetAll;
    volatile boolean failGeneration;
    volatile boolean failFill;
    /** 前 N 次失效调用失败（之后成功）；-1 = 一直失败。 */
    final AtomicInteger failInvalidations = new AtomicInteger();
    /** 读代次之后、回填之前插一段（模拟写者在读者快照之后提交）。 */
    volatile Runnable beforeFill;

    String text(String key) {
        byte[] v = values.get(key);
        return v == null ? null : new String(v, StandardCharsets.UTF_8);
    }

    @Override
    public synchronized CompletionStage<byte[]> get(String key) {
        getCalls.incrementAndGet();
        if (failGet) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        return CompletableFuture.completedFuture(values.get(key));
    }

    @Override
    public synchronized CompletionStage<Map<String, byte[]>> getAll(List<String> keys) {
        getAllCalls.incrementAndGet();
        if (failGetAll) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        Map<String, byte[]> found = new HashMap<>();
        for (String key : keys) {
            byte[] v = values.get(key);
            if (v != null) {
                found.put(key, v);
            }
        }
        return CompletableFuture.completedFuture(found);
    }

    @Override
    public synchronized CompletionStage<byte[]> generation(String generationKey, byte[] candidate, long ttlMillis) {
        if (failGet || failGeneration) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        byte[] current = values.get(generationKey);
        if (current == null) {
            values.put(generationKey, candidate);
            ttls.put(generationKey, ttlMillis);
            current = candidate;
        }
        return CompletableFuture.completedFuture(current);
    }

    @Override
    public CompletionStage<Boolean> fill(String generationKey, String dataKey, byte[] expectedGeneration, byte[] payload,
                                         long ttlMillis) {
        Runnable hook = beforeFill;
        if (hook != null) {
            beforeFill = null;
            hook.run();
        }
        synchronized (this) {
            if (failFill) {
                return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
            }
            byte[] generation = values.get(generationKey);
            if (generation == null || !Arrays.equals(generation, expectedGeneration)) {
                return CompletableFuture.completedFuture(false);
            }
            values.put(dataKey, payload);
            ttls.put(dataKey, ttlMillis);
            ttls.put(generationKey, ttlMillis * 2); // 同 FILL 脚本：回填成功把代次续到 2 × 数据 TTL
            return CompletableFuture.completedFuture(true);
        }
    }

    @Override
    public synchronized CompletionStage<Void> invalidate(String generationKey, String dataKey, byte[] newGeneration,
                                                         long generationTtlMillis) {
        invalidateCalls.incrementAndGet();
        int failures = failInvalidations.get();
        if (failures != 0) {
            if (failures > 0) {
                failInvalidations.decrementAndGet();
            }
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        values.put(generationKey, (new String(newGeneration, StandardCharsets.US_ASCII) + ":" + serverTime.incrementAndGet())
                .getBytes(StandardCharsets.US_ASCII));
        ttls.put(generationKey, generationTtlMillis);
        values.remove(dataKey);
        return CompletableFuture.completedFuture(null);
    }
}
