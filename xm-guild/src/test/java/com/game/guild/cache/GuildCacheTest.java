package com.game.guild.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import com.game.guild.cache.GuildCacheMetrics.CacheKind;
import com.game.guild.cache.GuildCacheMetrics.CacheResult;
import com.game.guild.cache.pb.GuildMemberSnapshot;
import com.game.guild.cache.pb.GuildSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 版本化缓存（guild-spec §1.11、§7.8、§11.4 的缓存条目；假 Redis 版）。 */
class GuildCacheTest {

    private static final Duration TTL = Duration.ofMinutes(30);
    /** ≥ 2^63 的帮会号与玩家号：键名、值、比较都必须按无符号。 */
    private static final long G = Long.MIN_VALUE + 7;
    private static final long P = Long.MIN_VALUE + 1_000_001;

    private final InMemoryGuildCacheRedis redis = new InMemoryGuildCacheRedis();
    private final RecordingCacheMetrics metrics = new RecordingCacheMetrics();
    private final ScheduledExecutorService background = Executors.newSingleThreadScheduledExecutor();
    private final GuildCacheInvalidator invalidator = new GuildCacheInvalidator(redis, TTL, background, metrics,
            List.of(1L, 1L, 1L), 1_000);

    /** 假 MySQL：帮会快照与 uk_guild_member 映射。 */
    private final Map<Long, GuildSnapshot> guildRows = new ConcurrentHashMap<>();
    private final Map<Long, Long> memberRows = new ConcurrentHashMap<>();
    private final AtomicInteger snapshotLoads = new AtomicInteger();
    private final AtomicInteger mappingLoads = new AtomicInteger();

    private final GuildCache cache = new GuildCache(redis, TTL, (guildId, deadline) -> {
        snapshotLoads.incrementAndGet();
        return guildRows.get(guildId);
    }, (playerId, deadline) -> {
        mappingLoads.incrementAndGet();
        return memberRows.getOrDefault(playerId, 0L);
    }, invalidator, metrics);

    @AfterEach
    void shutdown() {
        background.shutdownNow();
    }

    private static GuildSnapshot snapshot(long guildId, String name, long... members) {
        GuildSnapshot.Builder b = GuildSnapshot.newBuilder().setGuildId(guildId).setName(name).setLevel(1).setZoneId(3)
                .setMaxMembers(30).setScore(-5).setFunds(-1L);
        for (long member : members) {
            b.addMembers(GuildMemberSnapshot.newBuilder().setPlayerId(member).setRole(member == members[0] ? 3 : 0)
                    .setJoinTimeMs(1).setLastActiveMs(1).setContributionTotal(-2L).setContributionBalance(9));
        }
        if (members.length > 0) {
            b.setLeaderId(members[0]);
        }
        return b.build();
    }

    private static Deadline budget() {
        return Deadline.after(2_000);
    }

    // ------------------------------------------------------------------ 快照

    @Test
    void 快照未命中回源并回填_再读命中_值是带TTL的protobuf() throws Exception {
        GuildSnapshot row = snapshot(G, "帮会", P);
        guildRows.put(G, row);
        assertThat(cache.guild(G, budget())).contains(row);
        assertThat(cache.guild(G, budget())).contains(row);
        assertThat(snapshotLoads).hasValue(1);
        String key = RedisKeys.guildSnapshot(G);
        assertThat(key).isEqualTo("xm:guild:{g:9223372036854775815}:snap");
        assertThat(GuildSnapshot.parseFrom(redis.values.get(key))).isEqualTo(row);
        assertThat(redis.ttls.get(key)).isEqualTo(TTL.toMillis());
        assertThat(redis.ttls.get(RedisKeys.cacheGeneration(key))).isEqualTo(TTL.toMillis() * 2);
        assertThat(metrics.count(CacheKind.SNAPSHOT, CacheResult.MISS)).isEqualTo(1);
        assertThat(metrics.count(CacheKind.SNAPSHOT, CacheResult.HIT)).isEqualTo(1);
    }

    @Test
    void 不存在的帮会不做负缓存_每次都回源() {
        assertThat(cache.guild(G, budget())).isEmpty();
        assertThat(cache.guild(G, budget())).isEmpty();
        assertThat(snapshotLoads).hasValue(2);
        assertThat(redis.values).doesNotContainKey(RedisKeys.guildSnapshot(G));
    }

    @Test
    void 写者在读者快照之后提交_回填被拒_下一次读拿到新值() {
        guildRows.put(G, snapshot(G, "旧", P));
        redis.beforeFill = () -> {
            guildRows.put(G, snapshot(G, "新", P));
            invalidator.afterCommit(InvalidationOp.ANNOUNCEMENT, G, List.of(), budget());
        };
        assertThat(cache.guild(G, budget()).orElseThrow().getName()).isEqualTo("旧"); // 本次返回读库时的 MySQL 结果
        assertThat(redis.values).doesNotContainKey(RedisKeys.guildSnapshot(G));        // 但旧快照没有落地
        assertThat(cache.guild(G, budget()).orElseThrow().getName()).isEqualTo("新");
        assertThat(metrics.count(CacheKind.SNAPSHOT, CacheResult.FILL_SKIPPED)).isEqualTo(1);
    }

    @Test
    void 读者遇到代次缺失自己建一个_代次在回填前被淘汰则回填落空() {
        guildRows.put(G, snapshot(G, "a", P));
        String key = RedisKeys.guildSnapshot(G);
        String gen = RedisKeys.cacheGeneration(key);
        cache.guild(G, budget());
        assertThat(redis.values).containsKey(gen);
        redis.values.remove(key);
        redis.beforeFill = () -> redis.values.remove(gen);
        assertThat(cache.guild(G, budget())).isPresent();
        assertThat(redis.values).doesNotContainKey(key);
        assertThat(metrics.count(CacheKind.SNAPSHOT, CacheResult.FILL_SKIPPED)).isEqualTo(1);
    }

    @Test
    void 读失败_代次读失败_坏值_身份不符都是依赖故障() {
        guildRows.put(G, snapshot(G, "a", P));
        redis.failGet = true;
        assertThatThrownBy(() -> cache.guild(G, budget())).isInstanceOf(DependencyException.class);
        redis.failGet = false;

        redis.failGeneration = true;
        assertThatThrownBy(() -> cache.guild(G, budget())).isInstanceOf(DependencyException.class);
        assertThat(snapshotLoads).hasValue(0); // 代次必须先于回源读到
        redis.failGeneration = false;

        String key = RedisKeys.guildSnapshot(G);
        redis.values.put(key, new byte[] {(byte) 0xFF, 0x01});
        assertThatThrownBy(() -> cache.guild(G, budget())).isInstanceOf(DependencyException.class);
        redis.values.put(key, snapshot(G + 1, "别的帮", P).toByteArray());
        assertThatThrownBy(() -> cache.guild(G, budget())).isInstanceOf(DependencyException.class)
                .hasMessageContaining("不符");

        redis.values.put(RedisKeys.guildOfPlayer(P), "abc".getBytes(StandardCharsets.US_ASCII));
        assertThatThrownBy(() -> cache.guildIdOf(P, budget())).isInstanceOf(DependencyException.class);
        assertThat(metrics.count(CacheKind.SNAPSHOT, CacheResult.ERROR)).isEqualTo(4);
        assertThat(metrics.count(CacheKind.MAPPING, CacheResult.ERROR)).isEqualTo(1);
    }

    @Test
    void 回填失败只记指标_读照常返回() {
        guildRows.put(G, snapshot(G, "a", P));
        redis.failFill = true;
        assertThat(cache.guild(G, budget())).isPresent();
        assertThat(metrics.count(CacheKind.SNAPSHOT, CacheResult.FILL_FAILED)).isEqualTo(1);
    }

    @Test
    void 回源异常原样上抛_单飞已回收() {
        GuildCache failing = new GuildCache(redis, TTL, (guildId, deadline) -> {
            throw new IllegalStateException("mysql down");
        }, (playerId, deadline) -> 0L, invalidator, metrics);
        assertThatThrownBy(() -> failing.guild(G, budget())).isInstanceOf(IllegalStateException.class)
                .hasMessage("mysql down");
        guildRows.put(G, snapshot(G, "a", P));
        assertThat(cache.guild(G, budget())).isPresent();
    }

    // ------------------------------------------------------------------ 映射

    @Test
    void 映射0也缓存_无符号十进制() {
        assertThat(cache.guildIdOf(P, budget())).isZero();
        assertThat(cache.guildIdOf(P, budget())).isZero();
        assertThat(mappingLoads).hasValue(1);
        String key = RedisKeys.guildOfPlayer(P);
        assertThat(key).isEqualTo("xm:guild:{p:9223372036855775809}:gid");
        assertThat(redis.text(key)).isEqualTo("0");
        assertThat(redis.ttls.get(key)).isEqualTo(TTL.toMillis());

        long other = P + 1;
        memberRows.put(other, G);
        assertThat(cache.guildIdOf(other, budget())).isEqualTo(G);
        assertThat(redis.text(RedisKeys.guildOfPlayer(other))).isEqualTo(Long.toUnsignedString(G));
        assertThat(cache.guildIdOf(other, budget())).isEqualTo(G);
        assertThat(mappingLoads).hasValue(2);
    }

    @Test
    void 同一实体的数据键与代次键共用hash标签_好友代次键形状不变() {
        assertThat(RedisKeys.cacheGeneration(RedisKeys.guildSnapshot(G))).isEqualTo("xm:guild:{g:9223372036854775815}:snap:gen");
        assertThat(RedisKeys.cacheGeneration(RedisKeys.guildOfPlayer(5))).isEqualTo("xm:guild:{p:5}:gid:gen");
        assertThat(RedisKeys.friendCacheGeneration(RedisKeys.friendList(42))).isEqualTo("xm:friend:{42}:list:gen");
        assertThat(RedisKeys.guildApplyPush(G, 5)).isEqualTo("xm:guild:{g:9223372036854775815}:apply-push:5");
    }

    // ------------------------------------------------------------------ 单飞

    @Test
    void 单飞_同键并发未命中只回源一次_不同键互不阻塞() throws Exception {
        guildRows.put(G, snapshot(G, "a", P));
        guildRows.put(G + 1, snapshot(G + 1, "b", P));
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loadsOfG = new AtomicInteger();
        GuildCache blocking = new GuildCache(redis, TTL, (guildId, deadline) -> {
            if (guildId == G) {
                loadsOfG.incrementAndGet();
                inLoader.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return guildRows.get(guildId);
        }, (playerId, deadline) -> 0L, invalidator, metrics);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<Optional<GuildSnapshot>>> results = new ArrayList<>();
            results.add(pool.submit(() -> blocking.guild(G, Deadline.after(5_000))));
            assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 6; i++) {
                results.add(pool.submit(() -> blocking.guild(G, Deadline.after(5_000))));
            }
            // 别的键不排在 G 的回源后面
            assertThat(pool.submit(() -> blocking.guild(G + 1, Deadline.after(5_000))).get(2, TimeUnit.SECONDS)).isPresent();
            Thread.sleep(50);
            release.countDown();
            for (Future<Optional<GuildSnapshot>> f : results) {
                assertThat(f.get(5, TimeUnit.SECONDS)).isPresent();
            }
            assertThat(loadsOfG).hasValue(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void 等单飞超过自己的预算是依赖故障() throws Exception {
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GuildCache blocking = new GuildCache(redis, TTL, (guildId, deadline) -> {
            inLoader.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }, (playerId, deadline) -> 0L, invalidator, metrics);
        Thread leader = Thread.ofPlatform().start(() -> blocking.guild(G, Deadline.after(5_000)));
        try {
            assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> blocking.guild(G, Deadline.after(50))).isInstanceOf(DependencyException.class);
        } finally {
            release.countDown();
            leader.join(5_000);
        }
    }

    // ------------------------------------------------------------------ 批量展示读（D15）

    @Test
    void 批量展示读_一次MGET_缺失的回源_坏值与失败只缺席() {
        long g2 = G + 2;
        long g3 = G + 3;
        long ghost = G + 4;
        guildRows.put(G, snapshot(G, "a", P));
        guildRows.put(g2, snapshot(g2, "b", P));
        guildRows.put(g3, snapshot(g3, "c", P));
        cache.guild(G, budget()); // G 已在缓存
        redis.values.put(RedisKeys.guildSnapshot(g3), new byte[] {(byte) 0xFF}); // g3 坏值
        snapshotLoads.set(0);

        Map<Long, GuildSnapshot> found = cache.guildsForDisplay(List.of(G, g2, g3, ghost, G), budget());
        assertThat(found).containsOnlyKeys(G, g2);
        assertThat(found.get(g2).getName()).isEqualTo("b");
        assertThat(redis.getAllCalls).hasValue(1);
        assertThat(snapshotLoads).hasValue(2); // g2 与 ghost 回源；g3 坏值不回源
        assertThat(metrics.count(CacheKind.SNAPSHOT, CacheResult.ERROR)).isEqualTo(1);

        redis.failGetAll = true;
        assertThat(cache.guildsForDisplay(List.of(G, g2), budget())).isEmpty();
        assertThat(cache.guildsForDisplay(List.of(), budget())).isEmpty();
    }

    // ------------------------------------------------------------------ 复核与解析（ResolvePlayerGuild）

    @Test
    void 复核映射_相同不写Redis_不同则失效映射() {
        memberRows.put(P, G);
        assertThat(cache.verifyGuildIdOf(P, G, budget())).isEqualTo(G);
        assertThat(redis.invalidateCalls).hasValue(0);
        redis.values.put(RedisKeys.guildOfPlayer(P), "0".getBytes(StandardCharsets.US_ASCII));
        assertThat(cache.verifyGuildIdOf(P, 0, budget())).isEqualTo(G);
        assertThat(redis.invalidateCalls).hasValue(1);
        assertThat(redis.values).doesNotContainKey(RedisKeys.guildOfPlayer(P));
    }

    @Test
    void 解析_未入帮() {
        assertThat(cache.resolve(P, budget())).isEmpty();
        assertThat(mappingLoads).hasValue(2); // 读映射回源一次 + 复核一次
    }

    @Test
    void 解析_陈旧的0映射自愈() {
        redis.values.put(RedisKeys.guildOfPlayer(P), "0".getBytes(StandardCharsets.US_ASCII));
        memberRows.put(P, G);
        guildRows.put(G, snapshot(G, "a", P));
        assertThat(cache.resolve(P, budget()).orElseThrow().getGuildId()).isEqualTo(G);
        assertThat(redis.values).doesNotContainKey(RedisKeys.guildOfPlayer(P));
    }

    @Test
    void 解析_快照里没有本人_复核为0回未入帮_并失效快照() {
        redis.values.put(RedisKeys.guildOfPlayer(P), Long.toUnsignedString(G).getBytes(StandardCharsets.US_ASCII));
        guildRows.put(G, snapshot(G, "a", P + 9));
        cache.guild(G, budget());
        assertThat(cache.resolve(P, budget())).isEmpty();
        assertThat(redis.values).doesNotContainKey(RedisKeys.guildSnapshot(G));
        assertThat(redis.values).doesNotContainKey(RedisKeys.guildOfPlayer(P));
    }

    @Test
    void 解析_被踢后的陈旧快照_绕过缓存直读MySQL() {
        long g2 = G + 2;
        redis.values.put(RedisKeys.guildOfPlayer(P), Long.toUnsignedString(G).getBytes(StandardCharsets.US_ASCII));
        redis.values.put(RedisKeys.guildSnapshot(G), snapshot(G, "旧帮", P + 9).toByteArray());
        memberRows.put(P, g2);
        guildRows.put(g2, snapshot(g2, "新帮", P + 9, P));
        assertThat(cache.resolve(P, budget()).orElseThrow().getName()).isEqualTo("新帮");

        // 复核后的帮会已不存在 → 未入帮
        memberRows.put(P, G + 3);
        redis.values.put(RedisKeys.guildOfPlayer(P), Long.toUnsignedString(G).getBytes(StandardCharsets.US_ASCII));
        assertThat(cache.resolve(P, budget())).isEmpty();
    }

    @Test
    void 解析_两份MySQL数据矛盾时fail_closed() {
        redis.values.put(RedisKeys.guildOfPlayer(P), Long.toUnsignedString(G).getBytes(StandardCharsets.US_ASCII));
        memberRows.put(P, G);
        guildRows.put(G, snapshot(G, "a", P + 9)); // uk_guild_member 说 P 在 G，G 的成员里却没有 P
        assertThatThrownBy(() -> cache.resolve(P, budget())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("矛盾");
    }

    @Test
    void 构造参数校验() {
        assertThatThrownBy(() -> new GuildCache(redis, Duration.ZERO, (g, d) -> null, (p, d) -> 0L, invalidator,
                metrics)).isInstanceOf(IllegalArgumentException.class);
    }
}
