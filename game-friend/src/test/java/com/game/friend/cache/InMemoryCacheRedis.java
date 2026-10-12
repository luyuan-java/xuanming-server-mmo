package com.game.friend.cache;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/** 测试用：语义与三段 Lua 相同的内存实现（不模拟 TTL；「服务器时间」用自增计数代替），可注入故障。 */
public final class InMemoryCacheRedis implements FriendCache.CacheRedis {

    public final Map<String, String> values = new ConcurrentHashMap<>();
    public final Map<String, Long> ttls = new ConcurrentHashMap<>();
    private final AtomicLong serverTime = new AtomicLong();
    public volatile boolean failGet;
    public volatile boolean failFill;
    public volatile boolean failInvalidate;
    /** 读代次之后、回填之前插一段（模拟写者在读者快照之后提交）。 */
    public volatile Runnable beforeFill;

    @Override
    public synchronized CompletionStage<String> get(String key) {
        if (failGet) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        return CompletableFuture.completedFuture(values.get(key));
    }

    @Override
    public synchronized CompletionStage<String> generation(String generationKey, String candidate, long ttlMillis) {
        if (failGet) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        String current = values.get(generationKey);
        if (current == null) {
            values.put(generationKey, candidate);
            ttls.put(generationKey, ttlMillis);
            current = candidate;
        }
        return CompletableFuture.completedFuture(current);
    }

    @Override
    public CompletionStage<Boolean> fill(String generationKey, String dataKey, String expectedGeneration, String payload,
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
            String generation = values.get(generationKey);
            if (generation == null || !generation.equals(expectedGeneration)) {
                return CompletableFuture.completedFuture(false);
            }
            values.put(dataKey, payload);
            ttls.put(dataKey, ttlMillis);
            return CompletableFuture.completedFuture(true);
        }
    }

    @Override
    public synchronized CompletionStage<Void> invalidate(String generationKey, String dataKey, String newGeneration,
                                                         long generationTtlMillis) {
        if (failInvalidate) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        values.put(generationKey, newGeneration + ":" + serverTime.incrementAndGet());
        ttls.put(generationKey, generationTtlMillis);
        values.remove(dataKey);
        return CompletableFuture.completedFuture(null);
    }
}
