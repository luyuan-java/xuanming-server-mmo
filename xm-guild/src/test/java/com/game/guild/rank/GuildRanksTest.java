package com.game.guild.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import com.game.guild.rank.GuildRankMetrics.RankOp;
import com.game.guild.rank.GuildRankMetrics.RankOutcome;
import com.game.guild.rank.GuildRanks.RankEntry;
import com.game.guild.rank.GuildRanks.RankPage;
import com.game.guild.rank.GuildRanks.RankRow;
import com.game.guild.rank.GuildRanks.RebuildResult;
import com.game.guild.rules.GuildLimits;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 排行（guild-spec §5、D16；假 Redis 版。真 Redis 见 {@link GuildRanksRedisIntegrationTest}）。 */
class GuildRanksTest {

    private static final String LOCK = RedisKeys.guildRankLock();
    private static final String ALL = RedisKeys.guildRankAll();
    private static final String ZONES = RedisKeys.guildRankZones();
    private static final long BIG = Long.MIN_VALUE + 5; // 9223372036854775813

    private final InMemoryGuildRankRedis redis = new InMemoryGuildRankRedis();
    private final RecordingRankMetrics metrics = new RecordingRankMetrics();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** 锁 TTL 30 s、续期 10 s、等锁上限 300 ms、轮询 5 ms、请求预留 50 ms、临时键 10 min。 */
    private final GuildRanks ranks = new GuildRanks(redis, scheduler, metrics, 30_000, 10_000, 300, 5, 50, 600_000);

    @AfterEach
    void shutdown() {
        scheduler.shutdownNow();
    }

    private static Deadline budget() {
        return Deadline.after(3_000);
    }

    /** 等异步释放落地（释放是 fire-and-forget）。 */
    private void awaitUnlocked() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (true) {
            synchronized (redis) {
                if (!redis.strings.containsKey(LOCK)) {
                    return;
                }
            }
            if (System.nanoTime() > until) {
                throw new AssertionError("锁没有被释放");
            }
            Thread.sleep(2);
        }
    }

    @Test
    void 键形状_统一hash标签() {
        assertThat(ALL).isEqualTo("xm:guild:{rank}:all");
        assertThat(RedisKeys.guildRankZone(-1)).isEqualTo("xm:guild:{rank}:zone:4294967295");
        assertThat(ZONES).isEqualTo("xm:guild:{rank}:zones");
        assertThat(LOCK).isEqualTo("xm:guild:{rank}:lock");
        assertThat(RedisKeys.guildRankTmpAll("t")).isEqualTo("xm:guild:{rank}:tmp:t:all");
        assertThat(RedisKeys.guildRankTmpZone("t", 7)).isEqualTo("xm:guild:{rank}:tmp:t:zone:7");
        assertThat(GuildLimits.RANK_LOCK_TTL_MS).isEqualTo(30_000);
        assertThat(GuildRanks.RENEW_EVERY_MS).isEqualTo(10_000);
    }

    @Test
    void 入榜_全服榜区榜与区索引一段写_无符号成员_锁已释放() throws Exception {
        assertThat(ranks.add(BIG, 3, 0, budget())).isTrue();
        assertThat(ranks.add(7, 0, 5, budget())).isTrue();
        assertThat(redis.zset(ALL)).containsOnlyKeys("9223372036854775813", "7");
        assertThat(redis.zset(RedisKeys.guildRankZone(3))).containsOnlyKeys("9223372036854775813");
        assertThat(redis.sets.get(ZONES)).containsExactly("3"); // zone 0 只进全服榜，不进索引
        awaitUnlocked();
        assertThat(redis.ttls).doesNotContainKey(LOCK);
        assertThat(metrics.count(RankOp.ADD, RankOutcome.OK)).isEqualTo(2);
    }

    @Test
    void 请求路径等锁受预算约束_拿不到只记lock_timeout() {
        redis.strings.put(LOCK, "someone-else");
        GuildRanks patient = new GuildRanks(redis, scheduler, metrics, 30_000, 10_000, 5_000, 5, 50, 600_000);
        long started = System.nanoTime();
        // 剩余 200 ms − 预留 50 ms → 最多等 150 ms（远小于等锁上限 5 s）
        assertThat(patient.add(1, 1, 0, Deadline.after(200))).isFalse();
        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsed).isBetween(100L, 1_000L);
        assertThat(redis.tryLockCalls.get()).isGreaterThan(5);
        assertThat(metrics.count(RankOp.ADD, RankOutcome.LOCK_TIMEOUT)).isEqualTo(1);
        assertThat(redis.zset(ALL)).isEmpty();

        // 预算已不够预留：只试一次
        redis.tryLockCalls.set(0);
        assertThat(ranks.remove(1, 1, Deadline.after(10))).isFalse();
        assertThat(redis.tryLockCalls).hasValue(1);
        assertThat(metrics.count(RankOp.REMOVE, RankOutcome.LOCK_TIMEOUT)).isEqualTo(1);
        assertThat(redis.strings).containsEntry(LOCK, "someone-else"); // 别人的锁不动
    }

    @Test
    void 等锁期间对方释放_拿到锁照常写() throws Exception {
        redis.strings.put(LOCK, "someone-else");
        scheduler.schedule(() -> {
            synchronized (redis) {
                redis.strings.remove(LOCK);
            }
        }, 50, TimeUnit.MILLISECONDS);
        assertThat(ranks.add(9, 2, 4, budget())).isTrue();
        assertThat(redis.zset(RedisKeys.guildRankZone(2))).containsEntry("9", 4.0);
    }

    @Test
    void Redis故障与锁中途失效都只记error_从不抛() throws Exception {
        redis.fail("tryLock", 1);
        assertThat(ranks.add(1, 1, 0, budget())).isFalse();
        assertThat(metrics.count(RankOp.ADD, RankOutcome.ERROR)).isEqualTo(1);

        redis.fail("add", 1);
        assertThat(ranks.add(1, 1, 0, budget())).isFalse();
        assertThat(metrics.count(RankOp.ADD, RankOutcome.ERROR)).isEqualTo(2);
        awaitUnlocked();

        // 锁在持有期间被别人接管（过期后被抢）：写脚本的令牌围栏拒绝，什么都不写
        GuildRankRedis stolen = new InMemoryGuildRankRedis() {
            @Override
            public synchronized java.util.concurrent.CompletionStage<Boolean> add(String lockKey, String token,
                    String allKey, String zonesKey, String zoneKey, String zoneMember, String score, String member) {
                strings.put(lockKey, "thief");
                return super.add(lockKey, token, allKey, zonesKey, zoneKey, zoneMember, score, member);
            }
        };
        GuildRanks fenced = new GuildRanks(stolen, scheduler, metrics, 30_000, 10_000, 300, 5, 50, 600_000);
        assertThat(fenced.add(1, 1, 0, budget())).isFalse();
        assertThat(((InMemoryGuildRankRedis) stolen).zset(ALL)).isEmpty();
        assertThat(metrics.count(RankOp.ADD, RankOutcome.ERROR)).isEqualTo(3);

        redis.fail("members", 1);
        assertThat(ranks.remove(1, 1, budget())).isFalse();
        assertThat(metrics.count(RankOp.REMOVE, RankOutcome.ERROR)).isEqualTo(1);
    }

    @Test
    void 清榜_全服榜全部区榜与提示区_别的帮不动() throws Exception {
        ranks.add(5003, 30, 100, budget());
        ranks.add(5004, 30, 200, budget());
        ranks.add(5003, 10, 100, budget()); // 历史残留：同一帮出现在另一个区榜
        synchronized (redis) {
            redis.zsets.computeIfAbsent(RedisKeys.guildRankZone(20), k -> new java.util.HashMap<>()).put("5003", 1.0);
        }
        // 提示区 20 不在索引里（模拟没进索引的区榜）：显式带上
        assertThat(ranks.remove(5003, 20, budget())).isTrue();
        assertThat(redis.zset(ALL)).containsOnlyKeys("5004");
        assertThat(redis.zset(RedisKeys.guildRankZone(30))).containsOnlyKeys("5004");
        assertThat(redis.zset(RedisKeys.guildRankZone(10))).isEmpty();
        assertThat(redis.zset(RedisKeys.guildRankZone(20))).isEmpty();
        assertThat(metrics.count(RankOp.REMOVE, RankOutcome.OK)).isEqualTo(1);
    }

    @Test
    void 重建_原子换榜清掉幽灵_旧区榜与索引重写_临时键带TTL且最后删掉() throws Exception {
        ranks.add(1, 1, 50, budget());
        ranks.add(666, 9, 999, budget()); // 幽灵：MySQL 里已不存在
        awaitUnlocked();
        AtomicInteger tmpTtlChecks = new AtomicInteger();
        RebuildResult result = ranks.rebuild(sink -> {
            for (int i = 0; i < 600; i++) {
                sink.accept(new RankRow(1000 + i, i % 3 == 0 ? 0 : (i % 3 == 1 ? 1 : -1), i));
            }
            // 第一批（256 个）已经写进临时键，带 TTL
            synchronized (redis) {
                redis.ttls.forEach((key, ttl) -> {
                    if (key.contains(":tmp:")) {
                        assertThat(ttl).isEqualTo(600_000L);
                        tmpTtlChecks.incrementAndGet();
                    }
                });
            }
        });
        assertThat(tmpTtlChecks.get()).isPositive();
        assertThat(result).isEqualTo(new RebuildResult(600, 2));
        assertThat(redis.zset(ALL)).hasSize(600).doesNotContainKey("666").doesNotContainKey("1");
        assertThat(redis.zset(RedisKeys.guildRankZone(1))).hasSize(200);
        assertThat(redis.zset(RedisKeys.guildRankZone(-1))).hasSize(200);
        assertThat(redis.zset(RedisKeys.guildRankZone(9))).isEmpty();
        assertThat(redis.sets.get(ZONES)).containsExactlyInAnyOrder("1", "4294967295");
        assertThat(redis.zsets.keySet()).noneMatch(key -> key.contains(":tmp:"));
        assertThat(redis.ttls.keySet()).noneMatch(key -> key.contains(":tmp:") || key.equals(ALL));
        assertThat(redis.addTempCalls.get()).isGreaterThan(3); // 分批写
        awaitUnlocked();
        assertThat(metrics.count(RankOp.REBUILD, RankOutcome.OK)).isEqualTo(1);
    }

    @Test
    void 重建_空表清空全部榜() throws Exception {
        ranks.add(1, 1, 50, budget());
        assertThat(ranks.rebuild(sink -> {
        })).isEqualTo(new RebuildResult(0, 0));
        assertThat(redis.zsets).isEmpty();
        assertThat(redis.sets).doesNotContainKey(ZONES);
    }

    @Test
    void 重建_等锁超时拒启() {
        redis.strings.put(LOCK, "someone-else");
        assertThatThrownBy(() -> ranks.rebuild(sink -> sink.accept(new RankRow(1, 1, 1))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("超时");
        assertThat(metrics.count(RankOp.REBUILD, RankOutcome.LOCK_TIMEOUT)).isEqualTo(1);
        assertThat(redis.strings).containsEntry(LOCK, "someone-else");
        assertThat(redis.addTempCalls).hasValue(0);
    }

    @Test
    void 重建_临时键成员数不符时作废() {
        GuildRanks evicting = new GuildRanks(new InMemoryGuildRankRedis() {
            @Override
            public synchronized java.util.concurrent.CompletionStage<Long> swap(SwapPlan plan) {
                zsets.remove(plan.tmpAllKey()); // 临时键在换榜前被淘汰
                return super.swap(plan);
            }
        }, scheduler, metrics, 30_000, 10_000, 300, 5, 50, 600_000);
        assertThatThrownBy(() -> evicting.rebuild(sink -> sink.accept(new RankRow(1, 1, 1))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("成员数不符");
        assertThat(metrics.count(RankOp.REBUILD, RankOutcome.ERROR)).isEqualTo(1);
    }

    @Test
    void 重建_锁被接管时作废_数据源异常原样上抛() throws Exception {
        InMemoryGuildRankRedis shared = new InMemoryGuildRankRedis();
        GuildRanks owner = new GuildRanks(shared, scheduler, metrics, 30_000, 10_000, 300, 5, 50, 600_000);
        assertThatThrownBy(() -> owner.rebuild(sink -> {
            sink.accept(new RankRow(1, 1, 1));
            synchronized (shared) {
                shared.strings.put(LOCK, "thief"); // 过期后被别的实例接管
            }
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("锁已失效");
        assertThat(shared.zset(ALL)).isEmpty();
        assertThat(shared.zsets.keySet()).noneMatch(key -> key.contains(":tmp:"));
        synchronized (shared) {
            shared.strings.remove(LOCK);
        }

        assertThatThrownBy(() -> owner.rebuild(sink -> {
            throw new IllegalArgumentException("mysql down");
        })).isInstanceOf(IllegalArgumentException.class).hasMessage("mysql down");
    }

    @Test
    void 持锁期间按间隔续期_关闭后停止() throws Exception {
        GuildRanks fastRenew = new GuildRanks(redis, scheduler, metrics, 30_000, 20, 300, 5, 50, 600_000);
        fastRenew.rebuild(sink -> {
            sink.accept(new RankRow(1, 1, 1));
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        int renewed = redis.renewCalls.get();
        assertThat(renewed).isGreaterThanOrEqualTo(3);
        Thread.sleep(100);
        assertThat(redis.renewCalls.get()).isLessThanOrEqualTo(renewed + 1);
    }

    @Test
    void 请求路径持锁不续期() throws Exception {
        InMemoryGuildRankRedis slow = new InMemoryGuildRankRedis() {
            @Override
            public java.util.concurrent.CompletionStage<Boolean> add(String lockKey, String token, String allKey,
                    String zonesKey, String zoneKey, String zoneMember, String score, String member) {
                try {
                    Thread.sleep(120);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.add(lockKey, token, allKey, zonesKey, zoneKey, zoneMember, score, member);
            }
        };
        GuildRanks fastRenew = new GuildRanks(slow, scheduler, metrics, 30_000, 20, 300, 5, 50, 600_000);
        assertThat(fastRenew.add(1, 1, 1, budget())).isTrue();
        assertThat(slow.renewCalls).hasValue(0);
    }

    // ------------------------------------------------------------------ 读

    /** 移植基线 rank_page_test.go:18-66：超大页码为空，不会因乘法溢出重新读到前几名。 */
    @Test
    void 分页边界_uint32与int64溢出() {
        for (int zone : new int[] {0, 2}) {
            String key = zone == 0 ? ALL : RedisKeys.guildRankZone(zone);
            synchronized (redis) {
                Map<String, Double> zset = redis.zsets.computeIfAbsent(key, k -> new ConcurrentHashMap<>());
                for (int i = 0; i < 6; i++) {
                    zset.put(Integer.toString(101 + i), (double) (60 - i * 10));
                }
            }
            int maxUint32 = -1;
            assertPage(zone, 1, 4, List.of(101L, 102L, 103L, 104L), 1);
            assertPage(zone, 2, 4, List.of(105L, 106L), 5);
            assertPage(zone, 3, 4, List.of(), 0);
            assertPage(zone, 0, 4, List.of(), 0);
            assertPage(zone, 1, 0, List.of(), 0);
            assertPage(zone, 214748366, 20, List.of(), 0);
            assertPage(zone, maxUint32, maxUint32, List.of(), 0);
            assertPage(zone, 1, maxUint32, List.of(101L, 102L, 103L, 104L, 105L, 106L), 1);
        }
    }

    private void assertPage(int zone, int page, int size, List<Long> ids, int firstRank) {
        RankPage got = ranks.page(zone, page, size, budget());
        assertThat(got.total()).isEqualTo(6);
        assertThat(got.entries()).extracting(RankEntry::guildId).containsExactlyElementsOf(ids);
        for (int i = 0; i < got.entries().size(); i++) {
            assertThat(got.entries().get(i).rank()).isEqualTo(firstRank + i);
        }
        if (!ids.isEmpty()) {
            assertThat(got.entries().get(0).score()).isEqualTo(70 - 10 * (firstRank));
        }
    }

    @Test
    void 同分按成员串降序_9排在10前面() {
        for (long id : new long[] {10, 9, 100, BIG}) {
            ranks.add(id, 1, 0, budget());
        }
        assertThat(ranks.page(1, 1, 10, budget()).entries()).extracting(RankEntry::guildId)
                .containsExactly(BIG, 9L, 100L, 10L); // "9223…" > "9" > "100" > "10"
    }

    @Test
    void 单帮名次_在榜与不在榜() {
        ranks.add(BIG, 1, 77, budget());
        ranks.add(5, 1, 99, budget());
        assertThat(ranks.rankOf(BIG, 1, budget())).contains(new RankEntry(BIG, 77, 2));
        assertThat(ranks.rankOf(BIG, 0, budget())).contains(new RankEntry(BIG, 77, 2));
        assertThat(ranks.rankOf(BIG, 2, budget())).isEmpty();
        assertThat(ranks.rankOf(0, 1, budget())).isEmpty();
    }

    @Test
    void 读失败是依赖故障() {
        redis.fail("page", 1);
        assertThatThrownBy(() -> ranks.page(1, 1, 10, budget())).isInstanceOf(DependencyException.class);
        redis.fail("rank", 1);
        assertThatThrownBy(() -> ranks.rankOf(1, 1, budget())).isInstanceOf(DependencyException.class);
    }

    @Test
    void 分数与成员解析照基线() {
        assertThat(GuildRanks.toScore("60", "k")).isEqualTo(60);
        assertThat(GuildRanks.toScore("-1.5", "k")).isEqualTo(-1);
        assertThat(GuildRanks.toScore("1e+20", "k")).isEqualTo(Long.MAX_VALUE);
        assertThat(GuildRanks.toScore("9.2233720368547758e+18", "k")).isEqualTo(Long.MAX_VALUE);
        assertThat(GuildRanks.toScore("-inf", "k")).isEqualTo(Long.MIN_VALUE);
        assertThatThrownBy(() -> GuildRanks.toScore("abc", "k")).isInstanceOf(DependencyException.class);
        assertThat(GuildRanks.parseMember("18446744073709551615")).isEqualTo(-1L);
        assertThat(GuildRanks.parseMember("x")).isZero();
        assertThat(GuildRanks.parseMember("-1")).isZero();
    }

    /** 测试用：把排行维护的结局逐项计数。 */
    static final class RecordingRankMetrics implements GuildRankMetrics {

        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        @Override
        public void rankOp(RankOp op, RankOutcome outcome) {
            counts.computeIfAbsent(op.label() + "/" + outcome.label(), k -> new AtomicInteger()).incrementAndGet();
        }

        int count(RankOp op, RankOutcome outcome) {
            AtomicInteger n = counts.get(op.label() + "/" + outcome.label());
            return n == null ? 0 : n.get();
        }
    }
}
