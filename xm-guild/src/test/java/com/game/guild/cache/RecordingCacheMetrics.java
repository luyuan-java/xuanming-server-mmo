package com.game.guild.cache;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** 测试用：把缓存层报告的结局逐项计数。 */
final class RecordingCacheMetrics implements GuildCacheMetrics {

    private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

    @Override
    public void cache(CacheKind kind, CacheResult result) {
        counts.computeIfAbsent(kind.label() + "/" + result.label(), k -> new AtomicInteger()).incrementAndGet();
    }

    @Override
    public void invalidationGaveUp(InvalidationOp op) {
        counts.computeIfAbsent("gave_up/" + op.label(), k -> new AtomicInteger()).incrementAndGet();
    }

    int count(CacheKind kind, CacheResult result) {
        AtomicInteger n = counts.get(kind.label() + "/" + result.label());
        return n == null ? 0 : n.get();
    }

    int gaveUp(InvalidationOp op) {
        AtomicInteger n = counts.get("gave_up/" + op.label());
        return n == null ? 0 : n.get();
    }
}
