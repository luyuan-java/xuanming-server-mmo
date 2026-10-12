package com.game.guild.cache;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import com.game.guild.cache.GuildCacheMetrics.CacheKind;
import com.game.guild.cache.GuildCacheMetrics.CacheResult;
import com.game.guild.cache.pb.GuildMemberSnapshot;
import com.game.guild.cache.pb.GuildSnapshot;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 帮会快照与玩家 → 帮会映射的版本化缓存（基线 guild_repo.go:186-296 GetGuild / GetPlayerGuildID，
 * guild_manage_repo.go:589-677 VerifyPlayerGuildID / ResolvePlayerGuild；guild-spec §1.11、§7.8）。
 *
 * <p><b>正确性</b>（同基线 guild_repo.go:186-189）：读者先读代次 g、再读库、回填时比较代次；写者在<b>提交之后</b>原子地「换代次 + 删数据键」
 * （{@link GuildCacheInvalidator}）。换代次早于回填 → 回填被拒；晚于回填 → 删掉旧回填。所以写后不会残留旧快照。
 *
 * <p>与基线的有意差异（D10，照 friend D6 / D7）：
 * <ul>
 *   <li>代次是<b>不会重现的值</b>：失效时写「调用方 UUID + Redis 服务器 TIME」（带 2 × TTL），不是 INCR 一个永久计数
 *       （修 §9.1 第 3 条：代次键不再无界累积，也不怕淘汰或 Redisson 重发造成 ABA）；读者遇到代次缺失时自己 SET NX 一个再拿它比较，
 *       回填把「代次缺失」当成不符。</li>
 *   <li>按键单飞：同一键同一时刻只有一个回源者，等待者在<b>自己的</b>剩余预算内限时等、等到后从头再读一遍（不复用领头者的结果）。
 *       基线是进程级的一把全局互斥锁，所有 guild 与 player 的 miss 互相排队（修 §9.1 第 1 条）。</li>
 *   <li>回填失败只记日志与指标，读请求照常返回 MySQL 结果（基线回错误，修 §9.1 第 4 条）。</li>
 *   <li>值是 Java 自有的 {@link GuildSnapshot} protobuf（基线 JSON）；映射值是无符号十进制。</li>
 * </ul>
 *
 * <p>与基线相同：映射的 0 也缓存一个 TTL；不存在的帮会不做负缓存（N9）；授权一律不看缓存（调用方的事）；
 * {@link #verifyGuildIdOf} 与 cached 相同就不写 Redis；{@link #resolve} 的坏路径绕过缓存直读 MySQL、仍不含本人时 fail-closed。
 *
 * <p>故障语义：读数据键 / 代次键失败、缓存值坏、等单飞超过预算 → {@link DependencyException}（信封 1003，同基线结局）；
 * 回源（loader）抛的异常原样上抛；回填失败只记日志与指标。
 *
 * <p>线程：全部方法只在 guild-worker 上调用（阻塞等待异步 Redis 结果，上界是请求预算；AGENTS.md §3）。
 */
public final class GuildCache {

    private static final Logger log = LoggerFactory.getLogger(GuildCache.class);

    /**
     * 缓存需要的 Redis 操作（生产实现 {@link RedissonGuildCacheRedis}；Lua 原子性由实现保证）。参数与值一律字节（快照是任意字节）。
     * 实现必须异步返回、不阻塞调用线程，失败以异常完成的 stage 表达。
     */
    public interface CacheRedis {

        /** {@code GET}；不存在为 null。 */
        CompletionStage<byte[]> get(String key);

        /** 一次多键读（MGET）；结果只含存在的键（缺失的键不在 map 里或值为 null）。 */
        CompletionStage<Map<String, byte[]>> getAll(List<String> keys);

        /** 读代次；缺失时原子地写入 {@code candidate}（PX {@code ttlMillis}）并返回它。 */
        CompletionStage<byte[]> generation(String generationKey, byte[] candidate, long ttlMillis);

        /** 代次存在且等于 {@code expectedGeneration} 时写数据键（PX {@code ttlMillis}）并返回 true；否则（含代次缺失）不写、返回 false。 */
        CompletionStage<Boolean> fill(String generationKey, String dataKey, byte[] expectedGeneration, byte[] payload,
                                      long ttlMillis);

        /** 原子地把代次写成「{@code newGeneration} + 服务器时间」（PX {@code generationTtlMillis}，每次执行都不同）并删除数据键。 */
        CompletionStage<Void> invalidate(String generationKey, String dataKey, byte[] newGeneration, long generationTtlMillis);
    }

    /**
     * 帮会快照的回源（基线 loadGuild，guild_repo.go:460-498：先 G9 再 M11，成员按 player_id 升序）。阻塞的 SQL 读，在调用线程上执行，
     * 查询超时受 {@code deadline} 约束。
     */
    @FunctionalInterface
    public interface SnapshotLoader {

        /** @return 帮会快照；帮会不存在为 null。SQL 故障抛异常（原样上抛给调用方）。 */
        GuildSnapshot load(long guildId, Deadline deadline);
    }

    /** 玩家 → 帮会映射的回源（基线 loadPlayerGuildFromMySQL：M5，uk_guild_member 唯一索引点查）。 */
    @FunctionalInterface
    public interface MappingLoader {

        /** @return 玩家所在帮会号；未入帮为 0。SQL 故障抛异常。 */
        long load(long playerId, Deadline deadline);
    }

    /**
     * 读者建的候选代次的 TTL：只需活过一次「读代次 → 读库 → 回填」（请求预算 ≤ 3.5 s）；回填成功时脚本把它续到 2 × 数据 TTL。
     * 回源发现不存在（不做负缓存）时它几秒就消失——否则客户端拿任意 guild_id 刷 GetGuild 就能在共用的 Redis 里攒下大量一小时的孤儿键。
     * 过期了只会让本次回填被拒（代次缺失算不符），不影响正确性。
     */
    static final long CANDIDATE_GENERATION_TTL_MS = 10_000L;

    private final CacheRedis redis;
    private final long ttlMillis;
    private final SnapshotLoader snapshots;
    private final MappingLoader mappings;
    private final GuildCacheInvalidator invalidator;
    private final GuildCacheMetrics metrics;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> flights = new ConcurrentHashMap<>();

    public GuildCache(CacheRedis redis, Duration ttl, SnapshotLoader snapshots, MappingLoader mappings,
                      GuildCacheInvalidator invalidator, GuildCacheMetrics metrics) {
        if (ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("缓存 TTL 必须 > 0: " + ttl);
        }
        this.redis = redis;
        this.ttlMillis = ttl.toMillis();
        this.snapshots = snapshots;
        this.mappings = mappings;
        this.invalidator = invalidator;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ 帮会快照

    /**
     * 读帮会快照（基线 GetGuild，guild_repo.go:190-228）：命中直接返回；未命中由单飞领头者回源并回填。不存在为 empty（不做负缓存）。
     *
     * @throws DependencyException Redis 读失败、缓存值坏、等单飞超过预算
     */
    public Optional<GuildSnapshot> guild(long guildId, Deadline deadline) {
        String dataKey = RedisKeys.guildSnapshot(guildId);
        return load(CacheKind.SNAPSHOT, dataKey, deadline,
                raw -> Optional.of(decodeSnapshot(guildId, dataKey, raw)),
                () -> snapshots.load(guildId, deadline),
                GuildSnapshot::toByteArray);
    }

    /**
     * 展示用的批量读（D15：排行一页一次多键 GET，代替基线 enrichRankEntries 的逐条 GetGuild）：一次 MGET，缺失的逐个经单飞回源。
     *
     * <p>任何失败（MGET 失败、值坏、回源失败、预算用完）都<b>只记 ERROR</b>、该帮缺席，绝不抛——同基线 enrichRankEntries 的
     * 「出错只打 ERROR，该条只带 guild_id / score / rank」（guild_logic.go:691-693）。不存在的帮同样缺席（幽灵条目）。
     *
     * @return 帮会号 → 快照（只含读到的；顺序同入参去重后的顺序）
     */
    public Map<Long, GuildSnapshot> guildsForDisplay(Collection<Long> guildIds, Deadline deadline) {
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(guildIds));
        Map<Long, GuildSnapshot> found = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return found;
        }
        List<String> keys = ids.stream().map(RedisKeys::guildSnapshot).toList();
        Map<String, byte[]> raw;
        try {
            raw = deadline.await(redis.getAll(keys), "批量读帮会快照");
        } catch (DependencyException e) {
            metrics.cache(CacheKind.SNAPSHOT, CacheResult.ERROR);
            log.error("[guild] 批量读帮会快照失败，本页 {} 条只带 id / 分数 / 名次: {}", ids.size(), rootMessage(e));
            return found;
        }
        for (int i = 0; i < ids.size(); i++) {
            long guildId = ids.get(i);
            byte[] value = raw == null ? null : raw.get(keys.get(i));
            try {
                if (value != null) {
                    GuildSnapshot snapshot;
                    try {
                        snapshot = decodeSnapshot(guildId, keys.get(i), value);
                    } catch (DependencyException e) {
                        metrics.cache(CacheKind.SNAPSHOT, CacheResult.ERROR);
                        throw e;
                    }
                    found.put(guildId, snapshot);
                    metrics.cache(CacheKind.SNAPSHOT, CacheResult.HIT);
                } else {
                    guild(guildId, deadline).ifPresent(snapshot -> found.put(guildId, snapshot));
                }
            } catch (RuntimeException e) {
                log.error("[guild] 读帮会快照失败，该条只带 id / 分数 / 名次 guild={}: {}", Long.toUnsignedString(guildId),
                        rootMessage(e));
            }
        }
        return found;
    }

    // ------------------------------------------------------------------ 玩家 → 帮会映射

    /**
     * 读玩家所在帮会（基线 GetPlayerGuildID，guild_repo.go:230-270）：未入帮为 0，<b>0 也缓存一个 TTL</b>。
     *
     * @throws DependencyException Redis 读失败、缓存值坏、等单飞超过预算
     */
    public long guildIdOf(long playerId, Deadline deadline) {
        String dataKey = RedisKeys.guildOfPlayer(playerId);
        return load(CacheKind.MAPPING, dataKey, deadline,
                raw -> Optional.of(decodeMapping(dataKey, raw)),
                () -> mappings.load(playerId, deadline),
                guildId -> Long.toUnsignedString(guildId).getBytes(StandardCharsets.US_ASCII))
                .orElse(0L);
    }

    /**
     * 以 MySQL 复核缓存映射 {@code cached}（基线 VerifyPlayerGuildID，guild_manage_repo.go:597-615）：相同 → 原样返回且<b>不写 Redis</b>
     * （不制造无谓的代次翻转）；不同 → 提交后式失效映射键（op = verify_mapping，失败交后台重试）后返回 MySQL 值。Redis 失败不影响返回值。
     *
     * @throws RuntimeException 回源失败（原样上抛）
     */
    public long verifyGuildIdOf(long playerId, long cached, Deadline deadline) {
        long actual = mappings.load(playerId, deadline);
        if (actual != cached) {
            invalidator.afterCommit(InvalidationOp.VERIFY_MAPPING, 0, List.of(playerId), deadline);
        }
        return actual;
    }

    /**
     * 客户端 GetPlayerGuild 与 4.5 / 4.6 前置专用的权威解析（基线 ResolvePlayerGuild，guild_manage_repo.go:617-668）。
     *
     * <ol>
     *   <li>缓存映射为 0 → MySQL 复核；仍为 0 → 未入帮（empty）。</li>
     *   <li>读快照；快照里有本人 → 返回。</li>
     *   <li>帮会不在或快照里没有本人 → 失效该帮快照（op = verify_mapping）→ 复核映射：为 0 → 未入帮；否则<b>绕过缓存</b>直读 MySQL
     *       （Redis 仍坏着时缓存会把同一份旧快照再读回来）：读回不存在 → 未入帮；仍不含本人 → 抛 {@link IllegalStateException}
     *       （两份 MySQL 数据互相矛盾，fail-closed，不当成未入帮——否则玩家会在数据损坏时去建新帮，把矛盾扩大成两个帮）。</li>
     * </ol>
     *
     * @throws DependencyException Redis 读失败、缓存值坏、预算用完
     * @throws IllegalStateException guild_member 说玩家在 G，G 的成员快照里却没有他（调用方按故障处理：信封 1003）
     */
    public Optional<GuildSnapshot> resolve(long playerId, Deadline deadline) {
        long guildId = guildIdOf(playerId, deadline);
        if (guildId == 0) {
            guildId = verifyGuildIdOf(playerId, 0, deadline);
            if (guildId == 0) {
                return Optional.empty();
            }
        }
        Optional<GuildSnapshot> cached = guild(guildId, deadline);
        if (cached.isPresent() && hasMember(cached.get(), playerId)) {
            return cached;
        }
        // 帮已不存在，或快照里没有本人：快照与映射至少有一个是陈旧的
        invalidator.afterCommit(InvalidationOp.VERIFY_MAPPING, guildId, List.of(), deadline);
        long verified = verifyGuildIdOf(playerId, guildId, deadline);
        if (verified == 0) {
            return Optional.empty();
        }
        GuildSnapshot fresh = snapshots.load(verified, deadline);
        if (fresh == null) {
            return Optional.empty();
        }
        if (!hasMember(fresh, playerId)) {
            throw new IllegalStateException("帮会快照与 uk_guild_member 矛盾 guild=" + Long.toUnsignedString(verified)
                    + " player=" + Long.toUnsignedString(playerId));
        }
        return Optional.of(fresh);
    }

    /** 快照的成员里有没有这个玩家。 */
    public static boolean hasMember(GuildSnapshot snapshot, long playerId) {
        for (GuildMemberSnapshot member : snapshot.getMembersList()) {
            if (member.getPlayerId() == playerId) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ 版本化 cache-aside 的公共骨架

    /** 回源的结果：null 表示「不存在」（不回填）。 */
    @FunctionalInterface
    private interface Source<T> {
        T get();
    }

    /**
     * 读一个键：命中直接返回；未命中由单飞领头者回源并回填。
     *
     * @param decode 解码命中的值（坏值抛 {@link DependencyException}）
     * @param source 回源；返回 null 表示不存在（不回填、返回 empty）
     * @param encode 回填的值
     */
    private <T> Optional<T> load(CacheKind kind, String dataKey, Deadline deadline, Function<byte[], Optional<T>> decode,
                                 Source<T> source, Function<T, byte[]> encode) {
        while (true) {
            Optional<T> hit = readData(kind, dataKey, deadline, decode);
            if (hit != null) {
                metrics.cache(kind, CacheResult.HIT);
                return hit;
            }
            CompletableFuture<Void> mine = new CompletableFuture<>();
            CompletableFuture<Void> leader = flights.putIfAbsent(dataKey, mine);
            if (leader != null) {
                // 别人在回源：在自己的剩余预算内限时等它结束，再从头读（大概率命中它的回填）
                deadline.await(leader, "等待缓存回源 " + dataKey);
                continue;
            }
            try {
                return fillAsLeader(kind, dataKey, deadline, decode, source, encode);
            } finally {
                flights.remove(dataKey, mine);
                mine.complete(null);
            }
        }
    }

    private <T> Optional<T> fillAsLeader(CacheKind kind, String dataKey, Deadline deadline,
                                         Function<byte[], Optional<T>> decode, Source<T> source,
                                         Function<T, byte[]> encode) {
        // 拿到单飞之后再读一次：上一个领头者可能刚回填完
        Optional<T> hit = readData(kind, dataKey, deadline, decode);
        if (hit != null) {
            metrics.cache(kind, CacheResult.HIT);
            return hit;
        }
        metrics.cache(kind, CacheResult.MISS);
        String generationKey = RedisKeys.cacheGeneration(dataKey);
        // 代次必须在 SQL 之前读（正确性论证的前提）；缺失时建一个自己的（否则回填永远写不进去且零报错）
        byte[] generation;
        try {
            generation = deadline.await(redis.generation(generationKey,
                    UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII), Math.min(ttlMillis * 2, CANDIDATE_GENERATION_TTL_MS)),
                    "读缓存代次 " + generationKey);
        } catch (DependencyException e) {
            metrics.cache(kind, CacheResult.ERROR);
            throw e;
        }
        if (generation == null || generation.length == 0) {
            metrics.cache(kind, CacheResult.ERROR);
            throw new DependencyException("缓存代次为空 " + generationKey);
        }
        T value = source.get();
        if (value == null) {
            return Optional.empty(); // 不存在：不做负缓存（同基线 guild_repo.go:209-212）
        }
        byte[] payload = encode.apply(value);
        try {
            boolean filled = Boolean.TRUE.equals(deadline.await(
                    redis.fill(generationKey, dataKey, generation, payload, ttlMillis), "回填缓存 " + dataKey));
            if (!filled) {
                metrics.cache(kind, CacheResult.FILL_SKIPPED);
            }
        } catch (DependencyException e) {
            metrics.cache(kind, CacheResult.FILL_FAILED);
            log.error("[guild] 回填缓存失败，本次直接返回 MySQL 权威结果 key={}: {}", dataKey, rootMessage(e));
        }
        return Optional.of(value);
    }

    /** 读数据键：命中返回解码值；未命中返回 null；Redis 失败或值坏抛 {@link DependencyException}。 */
    private <T> Optional<T> readData(CacheKind kind, String dataKey, Deadline deadline,
                                     Function<byte[], Optional<T>> decode) {
        byte[] raw;
        try {
            raw = deadline.await(redis.get(dataKey), "读缓存 " + dataKey);
        } catch (DependencyException e) {
            metrics.cache(kind, CacheResult.ERROR);
            throw e;
        }
        if (raw == null) {
            return null;
        }
        try {
            return decode.apply(raw);
        } catch (DependencyException e) {
            metrics.cache(kind, CacheResult.ERROR);
            throw e;
        }
    }

    /** 解码快照：解不开或 guild_id 与键不符都是坏值。 */
    private static GuildSnapshot decodeSnapshot(long guildId, String dataKey, byte[] raw) {
        GuildSnapshot snapshot;
        try {
            snapshot = GuildSnapshot.parseFrom(raw);
        } catch (InvalidProtocolBufferException e) {
            throw new DependencyException("缓存值无法解码 " + dataKey, e);
        }
        if (snapshot.getGuildId() != guildId) {
            throw new DependencyException("缓存值与键不符 " + dataKey + " value_guild="
                    + Long.toUnsignedString(snapshot.getGuildId()));
        }
        return snapshot;
    }

    /** 解码映射：无符号十进制（0 = 未入帮）；解不开是坏值。 */
    private static long decodeMapping(String dataKey, byte[] raw) {
        String text = new String(raw, StandardCharsets.US_ASCII);
        try {
            return Long.parseUnsignedLong(text);
        } catch (NumberFormatException e) {
            throw new DependencyException("缓存值无法解码 " + dataKey, e);
        }
    }

    static String rootMessage(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = e.getMessage() == null ? e.toString() : e.getMessage();
        return message + (root == e ? "" : ": " + root);
    }
}
