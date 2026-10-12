package com.game.guild.service;

import com.game.guild.rank.GuildRankRedis;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * 服务单测用的内存版排行 Redis：只实现请求路径用到的操作（维护锁、入榜、区索引、清榜、分页、名次）；重建三件套不支持（服务单测不重建）。
 * 入榜 / 清榜写进 {@code journal}，用来断言「解散先清榜、再推送」。{@link #failReads} 打开后分页与名次读异常完成。
 */
final class FakeRankRedis implements GuildRankRedis {

    final Map<String, Map<String, Double>> zsets = new HashMap<>();
    final Map<String, Set<String>> sets = new HashMap<>();
    private final Map<String, String> locks = new HashMap<>();
    private final Consumer<String> journal;
    volatile boolean failReads;

    FakeRankRedis(Consumer<String> journal) {
        this.journal = journal;
    }

    @Override
    public synchronized CompletionStage<Boolean> tryLock(String lockKey, String token, long ttlMillis) {
        String holder = locks.putIfAbsent(lockKey, token);
        return CompletableFuture.completedFuture(holder == null || holder.equals(token));
    }

    @Override
    public synchronized CompletionStage<Boolean> renewLock(String lockKey, String token, long ttlMillis) {
        return CompletableFuture.completedFuture(token.equals(locks.get(lockKey)));
    }

    @Override
    public synchronized CompletionStage<Boolean> unlock(String lockKey, String token) {
        return CompletableFuture.completedFuture(locks.remove(lockKey, token));
    }

    @Override
    public synchronized CompletionStage<Boolean> add(String lockKey, String token, String allKey, String zonesKey,
                                                     String zoneKey, String zoneMember, String score, String member) {
        if (!token.equals(locks.get(lockKey))) {
            return CompletableFuture.completedFuture(false);
        }
        zsets.computeIfAbsent(allKey, k -> new HashMap<>()).put(member, Double.parseDouble(score));
        if (zoneKey != null) {
            zsets.computeIfAbsent(zoneKey, k -> new HashMap<>()).put(member, Double.parseDouble(score));
            sets.computeIfAbsent(zonesKey, k -> new LinkedHashSet<>()).add(zoneMember);
        }
        journal.accept("rank.add " + member);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletionStage<Set<String>> members(String setKey) {
        return CompletableFuture.completedFuture(new LinkedHashSet<>(sets.getOrDefault(setKey, Set.of())));
    }

    @Override
    public synchronized CompletionStage<Boolean> remove(String lockKey, String token, List<String> rankKeys, String member) {
        if (!token.equals(locks.get(lockKey))) {
            return CompletableFuture.completedFuture(false);
        }
        for (String key : rankKeys) {
            Map<String, Double> zset = zsets.get(key);
            if (zset != null) {
                zset.remove(member);
            }
        }
        journal.accept("rank.remove " + member);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletionStage<Long> addTemp(String tmpKey, List<String> scoreMemberPairs, long ttlMillis) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("服务单测不重建排行"));
    }

    @Override
    public CompletionStage<Long> swap(SwapPlan plan) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("服务单测不重建排行"));
    }

    @Override
    public CompletionStage<Long> delete(List<String> keys) {
        return CompletableFuture.completedFuture(0L);
    }

    /** 直接放一条（不经锁），给读路径的用例造数据。 */
    synchronized void put(String key, long guildId, long score) {
        zsets.computeIfAbsent(key, k -> new HashMap<>()).put(Long.toUnsignedString(guildId), (double) score);
    }

    private List<Map.Entry<String, Double>> sorted(String key) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(zsets.getOrDefault(key, Map.of()).entrySet());
        entries.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                .thenComparing(Map.Entry::getKey).reversed());
        return entries;
    }

    @Override
    public synchronized CompletionStage<RawPage> page(String key, String start, long size) {
        if (failReads) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        List<Map.Entry<String, Double>> entries = sorted(key);
        long total = entries.size();
        long from = Long.parseUnsignedLong(start);
        List<String> members = new ArrayList<>();
        List<String> scores = new ArrayList<>();
        if (size != 0 && Long.compareUnsigned(from, total) < 0) {
            long stop = Math.min(from + size - 1, total - 1);
            for (long i = from; i <= stop; i++) {
                members.add(entries.get((int) i).getKey());
                scores.add(Long.toString(entries.get((int) i).getValue().longValue()));
            }
        }
        return CompletableFuture.completedFuture(new RawPage(total, members, scores));
    }

    @Override
    public synchronized CompletionStage<RawRank> rank(String key, String member) {
        if (failReads) {
            return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
        }
        List<Map.Entry<String, Double>> entries = sorted(key);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getKey().equals(member)) {
                return CompletableFuture.completedFuture(
                        new RawRank(i, Long.toString(entries.get(i).getValue().longValue())));
            }
        }
        return CompletableFuture.completedFuture(null);
    }
}
