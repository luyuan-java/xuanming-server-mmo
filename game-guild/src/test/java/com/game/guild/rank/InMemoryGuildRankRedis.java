package com.game.guild.rank;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用：语义与 {@link RedissonGuildRankRedis} 各段 Lua 相同的内存实现（不模拟过期——测试直接改 / 删锁键来模拟；记下设过的 TTL）。
 * ZREVRANGE 的同分顺序照 Redis：成员串按字节序降序。可注入故障。
 */
class InMemoryGuildRankRedis implements GuildRankRedis {

    final Map<String, String> strings = new HashMap<>();
    final Map<String, Long> ttls = new HashMap<>();
    final Map<String, Map<String, Double>> zsets = new HashMap<>();
    final Map<String, Set<String>> sets = new HashMap<>();
    final AtomicInteger tryLockCalls = new AtomicInteger();
    final AtomicInteger renewCalls = new AtomicInteger();
    final AtomicInteger unlockCalls = new AtomicInteger();
    final AtomicInteger addTempCalls = new AtomicInteger();
    final Map<String, AtomicInteger> failures = new ConcurrentHashMap<>();

    /** 让名为 {@code op} 的操作接下来失败 {@code times} 次（-1 = 一直失败）。 */
    void fail(String op, int times) {
        failures.put(op, new AtomicInteger(times));
    }

    private boolean failing(String op) {
        AtomicInteger n = failures.get(op);
        if (n == null || n.get() == 0) {
            return false;
        }
        if (n.get() > 0) {
            n.decrementAndGet();
        }
        return true;
    }

    private static <T> CompletionStage<T> down() {
        return CompletableFuture.failedFuture(new IllegalStateException("redis down"));
    }

    synchronized Map<String, Double> zset(String key) {
        return zsets.getOrDefault(key, Map.of());
    }

    @Override
    public synchronized CompletionStage<Boolean> tryLock(String lockKey, String token, long ttlMillis) {
        tryLockCalls.incrementAndGet();
        if (failing("tryLock")) {
            return down();
        }
        String holder = strings.get(lockKey);
        if (holder != null && !holder.equals(token)) {
            return CompletableFuture.completedFuture(false);
        }
        strings.put(lockKey, token);
        ttls.put(lockKey, ttlMillis);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletionStage<Boolean> renewLock(String lockKey, String token, long ttlMillis) {
        renewCalls.incrementAndGet();
        if (failing("renew")) {
            return down();
        }
        if (!token.equals(strings.get(lockKey))) {
            return CompletableFuture.completedFuture(false);
        }
        ttls.put(lockKey, ttlMillis);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletionStage<Boolean> unlock(String lockKey, String token) {
        unlockCalls.incrementAndGet();
        if (failing("unlock")) {
            return down();
        }
        if (!token.equals(strings.get(lockKey))) {
            return CompletableFuture.completedFuture(false);
        }
        strings.remove(lockKey);
        ttls.remove(lockKey);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletionStage<Boolean> add(String lockKey, String token, String allKey, String zonesKey,
                                                     String zoneKey, String zoneMember, String score, String member) {
        if (failing("add")) {
            return down();
        }
        if (!token.equals(strings.get(lockKey))) {
            return CompletableFuture.completedFuture(false);
        }
        zsets.computeIfAbsent(allKey, k -> new HashMap<>()).put(member, Double.parseDouble(score));
        if (zoneKey != null) {
            zsets.computeIfAbsent(zoneKey, k -> new HashMap<>()).put(member, Double.parseDouble(score));
            sets.computeIfAbsent(zonesKey, k -> new LinkedHashSet<>()).add(zoneMember);
        }
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletionStage<Set<String>> members(String setKey) {
        if (failing("members")) {
            return down();
        }
        return CompletableFuture.completedFuture(new LinkedHashSet<>(sets.getOrDefault(setKey, Set.of())));
    }

    @Override
    public synchronized CompletionStage<Boolean> remove(String lockKey, String token, List<String> rankKeys, String member) {
        if (failing("remove")) {
            return down();
        }
        if (!token.equals(strings.get(lockKey))) {
            return CompletableFuture.completedFuture(false);
        }
        for (String key : rankKeys) {
            Map<String, Double> zset = zsets.get(key);
            if (zset != null) {
                zset.remove(member);
                if (zset.isEmpty()) {
                    zsets.remove(key);
                }
            }
        }
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public synchronized CompletionStage<Long> addTemp(String tmpKey, List<String> scoreMemberPairs, long ttlMillis) {
        addTempCalls.incrementAndGet();
        if (failing("addTemp")) {
            return down();
        }
        Map<String, Double> zset = zsets.computeIfAbsent(tmpKey, k -> new HashMap<>());
        long added = 0;
        for (int i = 0; i < scoreMemberPairs.size(); i += 2) {
            if (zset.put(scoreMemberPairs.get(i + 1), Double.parseDouble(scoreMemberPairs.get(i))) == null) {
                added++;
            }
        }
        ttls.put(tmpKey, ttlMillis);
        return CompletableFuture.completedFuture(added);
    }

    @Override
    public synchronized CompletionStage<Long> swap(SwapPlan plan) {
        if (failing("swap")) {
            return down();
        }
        if (!plan.token().equals(strings.get(plan.lockKey()))) {
            return CompletableFuture.completedFuture(0L);
        }
        if (zset(plan.tmpAllKey()).size() != plan.expectedAll()) {
            return CompletableFuture.completedFuture(-1L);
        }
        for (ZoneSwap zone : plan.zones()) {
            if (zset(zone.tmpKey()).size() != zone.expected()) {
                return CompletableFuture.completedFuture(-1L);
            }
        }
        zsets.remove(plan.allKey());
        plan.oldZoneKeys().forEach(zsets::remove);
        rename(plan.tmpAllKey(), plan.allKey());
        for (ZoneSwap zone : plan.zones()) {
            zsets.remove(zone.formalKey());
            rename(zone.tmpKey(), zone.formalKey());
        }
        sets.remove(plan.zonesKey());
        for (ZoneSwap zone : plan.zones()) {
            sets.computeIfAbsent(plan.zonesKey(), k -> new LinkedHashSet<>()).add(zone.zoneMember());
        }
        return CompletableFuture.completedFuture(1L);
    }

    private void rename(String from, String to) {
        Map<String, Double> zset = zsets.remove(from);
        if (zset != null && !zset.isEmpty()) {
            zsets.put(to, zset);
            ttls.remove(from);
            ttls.remove(to); // PERSIST
        }
    }

    @Override
    public synchronized CompletionStage<Long> delete(List<String> keys) {
        if (failing("delete")) {
            return down();
        }
        long n = 0;
        for (String key : keys) {
            if (zsets.remove(key) != null | strings.remove(key) != null | sets.remove(key) != null) {
                n++;
            }
            ttls.remove(key);
        }
        return CompletableFuture.completedFuture(n);
    }

    /** ZREVRANGE 的顺序：分数降序，同分按成员串降序。 */
    private List<Map.Entry<String, Double>> sorted(String key) {
        List<Map.Entry<String, Double>> entries = new ArrayList<>(zset(key).entrySet());
        entries.sort(Comparator.<Map.Entry<String, Double>>comparingDouble(Map.Entry::getValue)
                .thenComparing(Map.Entry::getKey).reversed());
        return entries;
    }

    @Override
    public synchronized CompletionStage<RawPage> page(String key, String start, long size) {
        if (failing("page")) {
            return down();
        }
        List<Map.Entry<String, Double>> entries = sorted(key);
        long total = entries.size();
        double from = Double.parseDouble(start);
        List<String> members = new ArrayList<>();
        List<String> scores = new ArrayList<>();
        if (size != 0 && from < total) {
            long stop = Math.min((long) from + size - 1, total - 1);
            for (long i = (long) from; i <= stop; i++) {
                members.add(entries.get((int) i).getKey());
                scores.add(redisScore(entries.get((int) i).getValue()));
            }
        }
        return CompletableFuture.completedFuture(new RawPage(total, members, scores));
    }

    @Override
    public synchronized CompletionStage<RawRank> rank(String key, String member) {
        if (failing("rank")) {
            return down();
        }
        List<Map.Entry<String, Double>> entries = sorted(key);
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).getKey().equals(member)) {
                return CompletableFuture.completedFuture(new RawRank(i, redisScore(entries.get(i).getValue())));
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Redis 回分数的写法：整数不带小数点，其余用最短表示。 */
    private static String redisScore(double score) {
        if (score == Math.rint(score) && Math.abs(score) < 1e17) {
            return Long.toString((long) score);
        }
        return Double.toString(score).replace("E", "e+").replace("e+-", "e-");
    }
}
