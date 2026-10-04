package com.game.friend.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.game.discovery.RedisKeys;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.metrics.FriendMetrics.CacheKind;
import com.game.friend.metrics.FriendMetrics.CacheResult;
import com.game.friend.support.Deadline;
import com.game.friend.support.Deadline.DependencyException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 好友列表 / 入站申请的版本化缓存（基线 friend_repo.go loadVersionedFriendCache，friend-spec.md §3）。
 *
 * <p><b>正确性</b>：读者先读代次 g、再读库、回填时比较代次；写者在<b>提交之后</b>原子地「换代次 + 删数据键」。写者在读者快照之后提交时，
 * 换代次早于回填 → 回填被拒；晚于回填 → 删掉旧回填。所以写后不会残留旧快照。
 *
 * <p>与基线的有意差异（spec §10.1）：
 * <ul>
 *   <li>D6：代次是<b>不会重现的值</b>，不是 INCR 一个永久计数（代次键不会无界累积）：失效时写「调用方 UUID + Redis 服务器 TIME」
 *       （Redisson 在响应超时后重发同一段 EVAL 时 TIME 已不同，不会把后来者写的代次改回旧值）；读者遇到代次缺失时<b>自己建一个</b>
 *       （SET NX，带 2 × TTL）再拿它比较，回填把「代次缺失」当成不符——代次键被淘汰（volatile-* 策略下它带 TTL）或过期都只会让回填落空，
 *       不会让旧快照落地。</li>
 *   <li>D7：回源单飞（同一键同一时刻只有一个读者回源）用可回收的 future；等待者在自己的剩余预算内限时等待，
 *       等到后<b>重新走一遍</b>读流程，不直接复用领头者的结果。</li>
 * </ul>
 *
 * <p>故障语义（同基线）：读数据键 / 代次键失败、缓存值坏 → {@link DependencyException}（调用方回 1003，不让 Redis 故障把全服列表读压到
 * MySQL）；回源 SQL 失败原样上抛；回填失败只记日志与指标；失效失败只记日志与指标（该键最多陈旧一个 TTL）。
 *
 * <p>全部方法在工作线程上调用（阻塞等待异步 Redis 结果，上界是请求预算）。
 */
public final class FriendCache {

    private static final Logger log = LoggerFactory.getLogger(FriendCache.class);

    /** 缓存需要的 Redis 操作（Lua 原子性由实现保证，见 {@link RedissonFriendCacheRedis}）。 */
    public interface CacheRedis {
        /** 读字符串键；不存在为 null。 */
        CompletionStage<String> get(String key);

        /** 读代次；缺失时原子地写入 {@code candidate}（带 TTL）并返回它。 */
        CompletionStage<String> generation(String generationKey, String candidate, long ttlMillis);

        /** 代次存在且等于 {@code expectedGeneration} 时写数据键（带 TTL）并返回 true；否则（含代次缺失）不写、返回 false。 */
        CompletionStage<Boolean> fill(String generationKey, String dataKey, String expectedGeneration, String payload,
                                      long ttlMillis);

        /** 原子地把代次写成「{@code newGeneration} + 服务器时间」（带 TTL，每次执行都不同）并删除数据键。 */
        CompletionStage<Void> invalidate(String generationKey, String dataKey, String newGeneration, long generationTtlMillis);
    }

    private final CacheRedis redis;
    private final Duration ttl;
    private final FriendMetrics metrics;
    private final ObjectMapper json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final ConcurrentHashMap<String, CompletableFuture<Void>> flights = new ConcurrentHashMap<>();

    public FriendCache(CacheRedis redis, Duration ttl, FriendMetrics metrics) {
        this.redis = redis;
        this.ttl = ttl;
        this.metrics = metrics;
    }

    /**
     * 读一个列表：命中直接返回；未命中由单飞领头者回源（{@code loader} 是阻塞的 SQL 读，已按上限截断）并回填。
     *
     * @throws DependencyException Redis 读失败、缓存值坏、等单飞超过预算
     */
    public <T> List<T> load(CacheKind kind, String dataKey, Class<T> type, Deadline deadline, Supplier<List<T>> loader) {
        JavaType listType = json.getTypeFactory().constructCollectionType(List.class, type);
        while (true) {
            List<T> hit = readData(kind, dataKey, listType, deadline);
            if (hit != null) {
                metrics.cache(kind, CacheResult.HIT);
                return hit;
            }
            CompletableFuture<Void> mine = new CompletableFuture<>();
            CompletableFuture<Void> leader = flights.putIfAbsent(dataKey, mine);
            if (leader != null) {
                // 别人在回源：限时等它结束，再从头读（大概率命中它的回填）
                deadline.await(leader, "等待缓存回源 " + dataKey);
                continue;
            }
            try {
                return fillAsLeader(kind, dataKey, listType, deadline, loader);
            } finally {
                flights.remove(dataKey, mine);
                mine.complete(null);
            }
        }
    }

    private <T> List<T> fillAsLeader(CacheKind kind, String dataKey, JavaType listType, Deadline deadline,
                                     Supplier<List<T>> loader) {
        // 拿到单飞之后再读一次：上一个领头者可能刚回填完
        List<T> hit = readData(kind, dataKey, listType, deadline);
        if (hit != null) {
            metrics.cache(kind, CacheResult.HIT);
            return hit;
        }
        metrics.cache(kind, CacheResult.MISS);
        String generationKey = RedisKeys.friendCacheGeneration(dataKey);
        // 代次必须在 SQL 之前读（正确性论证的前提）；缺失时建一个自己的（否则回填永远写不进去且零报错）
        String generation;
        try {
            generation = deadline.await(redis.generation(generationKey, UUID.randomUUID().toString(), ttl.toMillis() * 2),
                    "读缓存代次 " + generationKey);
        } catch (DependencyException e) {
            metrics.cache(kind, CacheResult.ERROR);
            throw e;
        }
        if (generation == null || generation.isEmpty()) {
            metrics.cache(kind, CacheResult.ERROR);
            throw new DependencyException("缓存代次为空 " + generationKey);
        }
        List<T> rows = loader.get();
        String payload;
        try {
            payload = json.writeValueAsString(rows == null ? List.of() : rows); // 空列表写 "[]"：区分「未命中」与「命中空列表」
        } catch (JsonProcessingException e) {
            throw new DependencyException("序列化缓存值失败 " + dataKey, e);
        }
        try {
            boolean filled = Boolean.TRUE.equals(deadline.await(
                    redis.fill(generationKey, dataKey, generation, payload, ttl.toMillis()), "回填缓存 " + dataKey));
            if (!filled) {
                metrics.cache(kind, CacheResult.FILL_SKIPPED);
            }
        } catch (DependencyException e) {
            metrics.cache(kind, CacheResult.FILL_FAILED);
            log.error("[friend] 回填缓存失败，本次直接返回 MySQL 权威结果 key={}: {}", dataKey, rootMessage(e));
        }
        return rows == null ? List.of() : rows;
    }

    /** 读数据键：命中返回列表；未命中返回 null；Redis 失败或值坏抛 {@link DependencyException}。 */
    private <T> List<T> readData(CacheKind kind, String dataKey, JavaType listType, Deadline deadline) {
        String raw;
        try {
            raw = deadline.await(redis.get(dataKey), "读缓存 " + dataKey);
        } catch (DependencyException e) {
            metrics.cache(kind, CacheResult.ERROR);
            throw e;
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return json.readValue(raw, listType);
        } catch (JsonProcessingException e) {
            metrics.cache(kind, CacheResult.ERROR);
            throw new DependencyException("缓存值无法解码 " + dataKey, e);
        }
    }

    /**
     * 写已提交之后失效一批键（并发发出，在剩余预算内等全部完成）。永不抛：失败只记 ERROR 与指标——把失效失败上抛会让一次成功的写
     * 被定性成 1003，客户端重试撞「已申请」、推送也被跳过。调用方在推送之前调它（对方收到推送立即拉取时必须读到新值）。
     */
    public void invalidateAfterCommit(Collection<String> dataKeys, Deadline deadline) {
        long generationTtl = ttl.toMillis() * 2;
        List<CompletableFuture<Void>> calls = new ArrayList<>(dataKeys.size());
        for (String dataKey : dataKeys) {
            String generation = UUID.randomUUID().toString();
            calls.add(redis.invalidate(RedisKeys.friendCacheGeneration(dataKey), dataKey, generation, generationTtl)
                    .toCompletableFuture());
        }
        List<String> keys = List.copyOf(dataKeys);
        for (int i = 0; i < calls.size(); i++) {
            try {
                deadline.await(calls.get(i), "失效缓存 " + keys.get(i));
            } catch (DependencyException e) {
                metrics.invalidationFailed();
                log.error("[friend] 写已提交但缓存失效失败（该键最多陈旧一个 TTL）key={}: {}", keys.get(i), rootMessage(e));
            }
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return e.getMessage() + (root == e ? "" : ": " + root);
    }
}
