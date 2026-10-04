package com.game.guild.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.guild.rank.GuildRankMetrics.RankOp;
import com.game.guild.rank.GuildRankMetrics.RankOutcome;
import com.game.guild.rank.GuildRanks.RankEntry;
import com.game.guild.rank.GuildRanks.RankPage;
import com.game.guild.rank.GuildRanks.RankRow;
import com.game.guild.rank.GuildRanksTest.RecordingRankMetrics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;

/**
 * 排行脚本在真 Redis 上的行为（guild-spec §11.4；移植 rank_page_test.go、rank_zone_integration_test.go:120-182；
 * 缺省跳过：{@code -Dxm.it.redis=redis://127.0.0.1:6379}）。
 *
 * <p>用 DB 10。排行的键是全服单例（{@code xm:guild:{rank}:*}），本类每个用例前后只删这一组键与本类用到的区榜 / 临时键，不 FLUSHDB。
 */
@EnabledIfSystemProperty(named = "xm.it.redis", matches = ".+")
class GuildRanksRedisIntegrationTest {

    private static final int DB = 10;
    private static final int[] ZONES = {2, 10, 11, 20, 21, 30, -1};
    /** 重建临时键的通配（{@code RedisKeys.guildRankTmpAll / guildRankTmpZone} 的公共前缀）。 */
    private static final String TMP_PATTERN = "xm:guild:{rank}:tmp:*";
    private static RedissonClient redis;

    private final RecordingRankMetrics metrics = new RecordingRankMetrics();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private GuildRanks ranks;

    @BeforeAll
    static void connect() {
        Config config = new Config();
        config.useSingleServer().setAddress(System.getProperty("xm.it.redis")).setDatabase(DB);
        redis = Redisson.create(config);
    }

    @AfterAll
    static void disconnect() {
        wipe();
        redis.shutdown();
    }

    @BeforeEach
    void setUp() {
        wipe();
        ranks = new GuildRanks(new RedissonGuildRankRedis(redis), scheduler, metrics);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        wipe();
    }

    /** 只删排行这一组键（含历史区榜与临时键）。 */
    private static void wipe() {
        List<String> keys = new ArrayList<>(List.of(RedisKeys.guildRankAll(), RedisKeys.guildRankZones(),
                RedisKeys.guildRankLock()));
        for (int zone : ZONES) {
            keys.add(RedisKeys.guildRankZone(zone));
        }
        redis.getKeys().getKeysStream(KeysScanOptions.defaults().pattern(TMP_PATTERN)).forEach(keys::add);
        redis.getKeys().delete(keys.toArray(String[]::new));
    }

    private static Deadline budget() {
        return Deadline.after(3_000);
    }

    private RScoredSortedSet<String> zset(String key) {
        return redis.getScoredSortedSet(key, StringCodec.INSTANCE);
    }

    private void assertNotRanked(String key, long guildId) {
        assertThat(zset(key).getScore(Long.toUnsignedString(guildId))).as("guild %d 不该还在 %s 里", guildId, key).isNull();
    }

    private void awaitUnlocked() throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (redis.getBucket(RedisKeys.guildRankLock()).isExists()) {
            if (System.nanoTime() > until) {
                throw new AssertionError("锁没有被释放");
            }
            Thread.sleep(2);
        }
    }

    @Test
    void 建帮以0分进全服榜区榜与区索引_成员是无符号十进制() throws Exception {
        long big = Long.MIN_VALUE + 9;
        assertThat(ranks.add(big, 2, 0, budget())).isTrue();
        assertThat(zset(RedisKeys.guildRankAll()).getScore("9223372036854775817")).isEqualTo(0.0);
        assertThat(zset(RedisKeys.guildRankZone(2)).getScore("9223372036854775817")).isEqualTo(0.0);
        assertThat(redis.<String>getSet(RedisKeys.guildRankZones(), StringCodec.INSTANCE).readAll()).containsExactly("2");
        awaitUnlocked();
        assertThat(ranks.rankOf(big, 2, budget())).contains(new RankEntry(big, 0, 1));
        assertThat(ranks.page(2, 1, 20, budget())).isEqualTo(new RankPage(List.of(new RankEntry(big, 0, 1)), 1));
        // 分数按 int64 十进制写入，Redis 取最近的 double（同基线 float64(score)）
        assertThat(ranks.add(big, 2, Long.MAX_VALUE, budget())).isTrue();
        assertThat(ranks.rankOf(big, 2, budget()).orElseThrow().score()).isEqualTo(Long.MAX_VALUE);
        assertThat(ranks.add(big, 2, -123_456_789_012L, budget())).isTrue();
        assertThat(ranks.rankOf(big, 0, budget()).orElseThrow().score()).isEqualTo(-123_456_789_012L);
        // zone 0 只进全服榜，不进区索引
        assertThat(ranks.add(77, 0, 3, budget())).isTrue();
        assertThat(zset(RedisKeys.guildRankAll()).getScore("77")).isEqualTo(3.0);
        assertThat(redis.<String>getSet(RedisKeys.guildRankZones(), StringCodec.INSTANCE).readAll()).containsExactly("2");
        assertThat(ranks.rankOf(77, 2, budget())).isEmpty();
        assertThat(metrics.count(RankOp.ADD, RankOutcome.OK)).isEqualTo(4);
    }

    /** rank_page_test.go:18-66：超大页码为空，不会因乘法溢出重新读到前几名。 */
    @Test
    void 分页边界_uint32与int64溢出() {
        for (int zone : new int[] {0, 2}) {
            for (int i = 0; i < 6; i++) {
                ranks.add(101 + i, 2, 60 - i * 10, budget());
            }
            int max = -1;
            assertPage(zone, 1, 4, List.of(101L, 102L, 103L, 104L), 1);
            assertPage(zone, 2, 4, List.of(105L, 106L), 5);
            assertPage(zone, 3, 4, List.of(), 0);
            assertPage(zone, 0, 4, List.of(), 0);
            assertPage(zone, 1, 0, List.of(), 0);
            assertPage(zone, 214748366, 20, List.of(), 0);
            assertPage(zone, max, max, List.of(), 0);
            assertPage(zone, 1, max, List.of(101L, 102L, 103L, 104L, 105L, 106L), 1);
        }
        assertThat(ranks.page(30, 1, 20, budget())).isEqualTo(new RankPage(List.of(), 0));
    }

    private void assertPage(int zone, int page, int size, List<Long> ids, int firstRank) {
        RankPage got = ranks.page(zone, page, size, budget());
        assertThat(got.total()).isEqualTo(6);
        assertThat(got.entries()).extracting(RankEntry::guildId).containsExactlyElementsOf(ids);
        for (int i = 0; i < got.entries().size(); i++) {
            assertThat(got.entries().get(i).rank()).isEqualTo(firstRank + i);
            assertThat(got.entries().get(i).score()).isEqualTo(70 - 10L * (firstRank + i));
        }
    }

    @Test
    void 同分按成员串降序_与基线一致() {
        for (long id : new long[] {10, 9, 100, Long.MIN_VALUE + 9}) {
            ranks.add(id, 2, 0, budget());
        }
        assertThat(ranks.page(2, 1, 10, budget()).entries()).extracting(RankEntry::guildId)
                .containsExactly(Long.MIN_VALUE + 9, 9L, 100L, 10L);
        assertThat(ranks.rankOf(9, 2, budget()).orElseThrow().rank()).isEqualTo(2);
    }

    /** rank_zone_integration_test.go:120：调用方拿着陈旧的源 zone 清榜，权威区榜里的条目也必须消失。 */
    @Test
    void 清榜_陈旧提示区也能从权威区榜移除_别的帮原封不动() throws Exception {
        long guild = 5001;
        int sourceZone = 10;
        int targetZone = 20;
        ranks.add(guild, targetZone, 999, budget());
        zset(RedisKeys.guildRankZone(sourceZone)).add(999, "5001"); // 历史残留
        ranks.add(5004, targetZone, 200, budget());
        assertThat(ranks.remove(guild, sourceZone, budget())).isTrue();
        assertNotRanked(RedisKeys.guildRankAll(), guild);
        assertNotRanked(RedisKeys.guildRankZone(targetZone), guild);
        assertNotRanked(RedisKeys.guildRankZone(sourceZone), guild);
        assertThat(zset(RedisKeys.guildRankZone(targetZone)).getScore("5004")).isEqualTo(200.0);
        assertThat(ranks.rankOf(guild, targetZone, budget())).isEmpty();
        awaitUnlocked();
    }

    @Test
    void 重建_原子替换并清掉幽灵_临时键带TTL_正式键无TTL() throws Exception {
        ranks.add(666, 21, 999, budget()); // 幽灵：MySQL 里已没有
        ranks.add(1, 11, 5, budget());     // 旧值
        awaitUnlocked();
        AtomicLong tmpTtl = new AtomicLong(-10);
        GuildRanks.RebuildResult result = ranks.rebuild(sink -> {
            for (int i = 0; i < 300; i++) {
                sink.accept(new RankRow(1 + i, i % 2 == 0 ? 11 : -1, 1_000 - i));
            }
            // 已经写过一批的临时键带 TTL（10 min）
            redis.getKeys().getKeysStream(KeysScanOptions.defaults().pattern(TMP_PATTERN))
                    .forEach(key -> tmpTtl.set(redis.getBucket(key).remainTimeToLive()));
        });
        assertThat(tmpTtl.get()).isBetween(Duration.ofMinutes(9).toMillis(), Duration.ofMinutes(10).toMillis());
        assertThat(result).isEqualTo(new GuildRanks.RebuildResult(300, 2));
        assertThat(zset(RedisKeys.guildRankAll()).size()).isEqualTo(300);
        assertNotRanked(RedisKeys.guildRankAll(), 666);
        assertNotRanked(RedisKeys.guildRankZone(21), 666);
        assertThat(zset(RedisKeys.guildRankZone(11)).getScore("1")).isEqualTo(1_000.0);
        assertThat(zset(RedisKeys.guildRankZone(-1)).size()).isEqualTo(150);
        assertThat(redis.<String>getSet(RedisKeys.guildRankZones(), StringCodec.INSTANCE).readAll())
                .containsExactlyInAnyOrder("11", "4294967295");
        assertThat(redis.getKeys().getKeysStream(KeysScanOptions.defaults().pattern(TMP_PATTERN)).count())
                .isZero();
        for (String key : List.of(RedisKeys.guildRankAll(), RedisKeys.guildRankZone(11), RedisKeys.guildRankZone(-1))) {
            assertThat(redis.getKeys().remainTimeToLive(key)).as(key).isEqualTo(-1L);
        }
        assertThat(ranks.page(11, 1, 3, budget()).entries()).extracting(RankEntry::guildId).containsExactly(1L, 3L, 5L);
        awaitUnlocked();
        assertThat(metrics.count(RankOp.REBUILD, RankOutcome.OK)).isEqualTo(1);
    }

    @Test
    void 重建_空表清空全部榜() throws Exception {
        ranks.add(1, 11, 5, budget());
        awaitUnlocked();
        assertThat(ranks.rebuild(sink -> {
        })).isEqualTo(new GuildRanks.RebuildResult(0, 0));
        assertThat(redis.getKeys().countExists(RedisKeys.guildRankAll(), RedisKeys.guildRankZone(11),
                RedisKeys.guildRankZones())).isZero();
    }

    @Test
    void 维护锁续期_持锁期间别人等不到_释放后接管() throws Exception {
        GuildRanks slowOwner = new GuildRanks(new RedissonGuildRankRedis(redis), scheduler, metrics, 300, 100, 5_000, 20,
                300, 600_000);
        GuildRanks other = new GuildRanks(new RedissonGuildRankRedis(redis), scheduler, metrics);
        AtomicInteger blocked = new AtomicInteger();
        slowOwner.rebuild(sink -> {
            sink.accept(new RankRow(1, 2, 1));
            try {
                Thread.sleep(900); // 远超 300 ms 的 TTL：靠续期一直持有
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (!other.add(2, 2, 2, Deadline.after(500))) {
                blocked.incrementAndGet();
            }
            assertThat(redis.getBucket(RedisKeys.guildRankLock()).remainTimeToLive()).isBetween(1L, 300L);
        });
        assertThat(blocked).hasValue(1);
        assertThat(metrics.count(RankOp.ADD, RankOutcome.LOCK_TIMEOUT)).isEqualTo(1);
        awaitUnlocked();
        assertThat(other.add(2, 2, 2, budget())).isTrue();
    }

    @Test
    void 维护锁过期接管_持锁实例崩溃后最多卡一个TTL() {
        redis.<String>getBucket(RedisKeys.guildRankLock(), StringCodec.INSTANCE).set("crashed-instance", Duration.ofMillis(300));
        long started = System.nanoTime();
        assertThat(ranks.add(7, 2, 1, budget())).isTrue();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(150L, 2_000L);
    }

    @Test
    void 重建_锁被接管时作废_正式榜不动() {
        ranks.add(42, 2, 1, budget());
        assertThatThrownBy(() -> ranks.rebuild(sink -> {
            sink.accept(new RankRow(1, 2, 1));
            redis.<String>getBucket(RedisKeys.guildRankLock(), StringCodec.INSTANCE).set("thief", Duration.ofSeconds(30));
        })).isInstanceOf(IllegalStateException.class).hasMessageContaining("锁已失效");
        assertThat(zset(RedisKeys.guildRankAll()).getScore("42")).isEqualTo(1.0);
        assertThat(zset(RedisKeys.guildRankAll()).getScore("1")).isNull();
        assertThat(redis.<String>getBucket(RedisKeys.guildRankLock(), StringCodec.INSTANCE).get()).isEqualTo("thief");
        assertThat(redis.getKeys().getKeysStream(KeysScanOptions.defaults().pattern(TMP_PATTERN)).count())
                .isZero();
    }

    @Test
    void 单帮名次一段脚本_不在榜答空() {
        ranks.add(5, 2, 99, budget());
        ranks.add(6, 2, 77, budget());
        assertThat(ranks.rankOf(6, 2, budget())).contains(new RankEntry(6, 77, 2));
        assertThat(ranks.rankOf(6, 30, budget())).isEmpty();
        assertThat(ranks.rankOf(0, 2, budget())).isEmpty();
    }
}
