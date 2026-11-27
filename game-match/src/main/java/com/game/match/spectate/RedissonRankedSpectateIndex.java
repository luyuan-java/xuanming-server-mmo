package com.game.match.spectate;

import com.game.common.deadline.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.ScoredEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link RankedSpectateIndex} 的 Redisson 16 桶 Redis Cluster 实现（{@code spectate:ranked:active:{srk:0..15}}）：
 * 写路径单桶 O(log N) 并附 30 分钟安全 TTL；读路径带 1 秒进程内快照缓存，避免大厅 164 高并发放大到 16 桶扫库。
 */
public final class RedissonRankedSpectateIndex implements RankedSpectateIndex {

    private static final Logger log = LoggerFactory.getLogger(RedissonRankedSpectateIndex.class);
    static final long LOBBY_CACHE_TTL_MS = 1_000L;
    private static final Duration BUCKET_SAFETY_TTL = Duration.ofMinutes(30);
    private static final int PER_BUCKET_FETCH = 16;

    private record LobbyCache(long loadedAtMs, List<Long> battleIds) { }

    private final RedissonClient redis;
    private final String prefix;
    private final LongSupplier clockMs;
    private final AtomicReference<LobbyCache> lobbyCache = new AtomicReference<>();

    public RedissonRankedSpectateIndex(RedissonClient redis, String prefix) {
        this(redis, prefix, System::currentTimeMillis);
    }

    RedissonRankedSpectateIndex(RedissonClient redis, String prefix, LongSupplier clockMs) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.prefix = prefix == null ? "" : prefix;
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
    }

    @Override
    public void record(long battleId, long createdAtMs) {
        if (battleId == 0L || createdAtMs <= 0L) {
            return;
        }
        String key = RankedSpectateIndex.bucketKey(prefix, RankedSpectateIndex.bucketOf(battleId));
        try {
            RScoredSortedSet<String> zset = redis.getScoredSortedSet(key, StringCodec.INSTANCE);
            zset.add(createdAtMs, SpectateRules.member(battleId));
            zset.expire(BUCKET_SAFETY_TTL);
        } catch (RuntimeException e) {
            log.warn("[spectate] record ranked active battle failed battle_id={}: {}",
                    Long.toUnsignedString(battleId), e.toString());
        }
    }

    @Override
    public void remove(long battleId) {
        if (battleId == 0L) {
            return;
        }
        String key = RankedSpectateIndex.bucketKey(prefix, RankedSpectateIndex.bucketOf(battleId));
        try {
            redis.getScoredSortedSet(key, StringCodec.INSTANCE).remove(SpectateRules.member(battleId));
        } catch (RuntimeException e) {
            log.warn("[spectate] remove ranked active battle failed battle_id={}: {}",
                    Long.toUnsignedString(battleId), e.toString());
        }
    }

    @Override
    public long sweepExpired(long cutoffMs) {
        long total = 0L;
        for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            String key = RankedSpectateIndex.bucketKey(prefix, bucket);
            try {
                total += redis.getScoredSortedSet(key, StringCodec.INSTANCE)
                        .removeRangeByScore(Double.NEGATIVE_INFINITY, true, cutoffMs, false);
            } catch (RuntimeException e) {
                log.warn("[spectate] sweep ranked active bucket={} failed: {}", bucket, e.toString());
            }
        }
        return total;
    }

    @Override
    public List<Long> activeBattles(int limit, long nowMs, Deadline deadline) {
        if (limit <= 0) {
            return List.of();
        }
        long tick = clockMs.getAsLong();
        LobbyCache cached = lobbyCache.get();
        if (cached != null && tick >= cached.loadedAtMs() && (tick - cached.loadedAtMs()) < LOBBY_CACHE_TTL_MS) {
            return cached.battleIds().size() > limit ? cached.battleIds().subList(0, limit) : cached.battleIds();
        }
        long cutoffMs = SpectateRules.staleCutoff(nowMs);
        Map<Long, Double> merged = new LinkedHashMap<>();
        int perBucket = Math.min(PER_BUCKET_FETCH, Math.max(4, limit));
        for (int bucket = 0; bucket < BUCKET_COUNT; bucket++) {
            if (deadline != null && deadline.expired()) {
                break;
            }
            String key = RankedSpectateIndex.bucketKey(prefix, bucket);
            try {
                RScoredSortedSet<String> zset = redis.getScoredSortedSet(key, StringCodec.INSTANCE);
                var entries = zset.entryRangeReversed(cutoffMs, true, Double.POSITIVE_INFINITY, true, 0, perBucket);
                if (entries != null) {
                    for (ScoredEntry<String> entry : entries) {
                        var parsed = SpectateRules.parseMember(entry.getValue());
                        if (parsed.isPresent() && entry.getScore() != null) {
                            merged.merge(parsed.getAsLong(), entry.getScore(), Math::max);
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("[spectate] read ranked active bucket={} failed: {}", bucket, e.toString());
            }
        }
        List<Long> sorted = new ArrayList<>(merged.keySet());
        sorted.sort(Comparator.<Long>comparingDouble(id -> merged.getOrDefault(id, 0.0)).reversed()
                .thenComparing(Long::compareUnsigned));
        List<Long> snapshot = List.copyOf(sorted);
        lobbyCache.set(new LobbyCache(tick, snapshot));
        return snapshot.size() > limit ? snapshot.subList(0, limit) : snapshot;
    }

    void clearCacheForTest() {
        lobbyCache.set(null);
    }
}
