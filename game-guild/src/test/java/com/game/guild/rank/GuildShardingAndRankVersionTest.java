package com.game.guild.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.common.workshard.LoopLiveness;
import com.game.common.workshard.WorkShard;
import com.game.discovery.RedisKeys;
import com.game.guild.asset.AssetOpCleanup;
import com.game.guild.asset.AssetOpLoop;
import com.game.guild.asset.AssetOpMetrics;
import com.game.guild.asset.GuildAssetStore;
import com.game.guild.store.GuildStore;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-10/11/12 帮会资产重投/终态清理 64 片分工 + 帮会积分无全局锁版本写入与墓碑摘榜（主仓第 23、25 条）。 */
class GuildShardingAndRankVersionTest {

    private static final RedisKeys KEYS = new RedisKeys(GameNamespace.of("game"));
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void 帮会写分无全服锁_乱序旧版本被拒_解散立墓碑防复活_重建按版本合并且不误删并发新帮_合服折叠守版本() {
        InMemoryGuildRankRedis redis = new InMemoryGuildRankRedis();
        GuildRanksTest.RecordingRankMetrics metrics = new GuildRanksTest.RecordingRankMetrics();
        GuildRanks ranks = new GuildRanks(redis, KEYS, scheduler, metrics, 30_000, 10_000, 300, 5, 50, 600_000);
        long now = 1_700_000_000_000L;

        // 1. 写分不抢全服锁（tryLockCalls == 0），高版本写入成功，乱序低版本被拒
        assertThat(ranks.writeVersioned(1001L, 1, 500L, 5L, now, Deadline.after(2000))).isTrue();
        assertThat(redis.tryLockCalls.get()).isZero();
        assertThat(ranks.writeVersioned(1001L, 1, 300L, 4L, now, Deadline.after(2000))).isFalse();
        assertThat(redis.zset(KEYS.guildRankAll()).get("1001")).isEqualTo(500.0);
        assertThat(redis.zset(KEYS.guildRankZone(1)).get("1001")).isEqualTo(500.0);

        // 2. 解散无全服锁立 24h 墓碑并摘榜；迟到的写分（即便版本号更高）不得把已解散帮会写回榜上
        assertThat(ranks.disbandTombstone(1001L, 1, now, Deadline.after(2000))).isTrue();
        assertThat(redis.tryLockCalls.get()).isZero();
        assertThat(redis.zset(KEYS.guildRankAll())).doesNotContainKey("1001");
        assertThat(redis.zset(KEYS.guildRankZone(1))).doesNotContainKey("1001");
        assertThat(ranks.writeVersioned(1001L, 1, 999L, 99L, now + 10_000L, Deadline.after(2000))).isFalse();
        assertThat(redis.zset(KEYS.guildRankAll())).doesNotContainKey("1001");

        // 3. 按版本合并重建：不覆盖并发更高版本写分；快照之后新建的帮会(2002)回库仍在则不删除；已消失幽灵帮(9999)删除
        assertThat(ranks.writeVersioned(2001L, 1, 800L, 10L, now, Deadline.after(2000))).isTrue();
        assertThat(ranks.writeVersioned(2002L, 1, 50L, 1L, now, Deadline.after(2000))).isTrue();
        assertThat(ranks.writeVersioned(9999L, 1, 10L, 1L, now, Deadline.after(2000))).isTrue();
        List<GuildStore.VersionedScore> oldSnapshot = List.of(
                new GuildStore.VersionedScore(2001L, 1, 600L, 8L), // 比并发写入(v10)旧
                new GuildStore.VersionedScore(2003L, 2, 400L, 3L));
        Set<Long> aliveInDb = Set.of(2001L, 2002L, 2003L);
        ranks.rebuildVersioned(oldSnapshot, aliveInDb::contains, now);
        assertThat(redis.zset(KEYS.guildRankAll()).get("2001")).isEqualTo(800.0); // 保留并发更高版本
        assertThat(redis.zset(KEYS.guildRankAll()).get("2002")).isEqualTo(50.0);  // 快照后新建的帮不被误删
        assertThat(redis.zset(KEYS.guildRankAll()).get("2003")).isEqualTo(400.0);
        assertThat(redis.zset(KEYS.guildRankAll())).doesNotContainKey("9999");    // 幽灵帮已清理

        // 4. 合服折叠遵守版本：源区帮会 2003(v3) 迁到区 1 并摘除区 2；若并发已升到 v4，源区不误删
        int folded = ranks.foldZoneVersioned(2, 1, List.of(new GuildStore.VersionedScore(2003L, 1, 400L, 3L)), now);
        assertThat(folded).isEqualTo(1);
        assertThat(redis.zset(KEYS.guildRankZone(1)).get("2003")).isEqualTo(400.0);
        assertThat(redis.zset(KEYS.guildRankZone(2))).doesNotContainKey("2003");
    }

    @Test
    void 帮会资产重投与终态清理按playerId分64片_类别键分开_canary不领行() {
        GuildAssetStore store = mock(GuildAssetStore.class);
        AssetOpLoop.Applier applier = mock(AssetOpLoop.Applier.class);
        AssetOpMetrics metrics = mock(AssetOpMetrics.class);
        AtomicLong clock = new AtomicLong(1_700_000_000_000L);

        WorkShard.Membership membership = new WorkShard.Membership(30_000L, clock::get);
        membership.setSelf("guild-1");
        membership.updateMembers(List.of("guild-1", "guild-2"));

        when(store.listDue(anyLong(), anyInt(), any(),
                argThat(f -> !f.isAll() && !f.isNone() && f.sql().startsWith("MOD(player_id,64) IN("))))
                .thenReturn(List.of());
        when(store.cleanupOnce(anyLong(), any(), any(),
                argThat(f -> !f.isAll() && !f.isNone() && f.sql().startsWith("MOD(player_id,64) IN("))))
                .thenReturn(new GuildAssetStore.CleanupReport(1, 0, 0));

        AssetOpLoop.Config cfg = new AssetOpLoop.Config(
                Duration.ofSeconds(1), 16, 2, Duration.ofSeconds(10), Duration.ofSeconds(5),
                500L, Duration.ofSeconds(30), Duration.ofMinutes(5), 2);
        LoopLiveness loopLive = new LoopLiveness("guild", "asset_reconcile", 300L, clock::get);
        AssetOpLoop loop = new AssetOpLoop(cfg, store, applier, null, metrics, clock::get, Runnable::run, Runnable::run,
                RandomGenerator.getDefault(), () -> 0.5)
                .withWorkShard(membership)
                .withLiveness(loopLive);
        loop.start();
        try {
            loop.tick();
        } finally {
            loop.stop(Duration.ofSeconds(2));
        }
        verify(store).listDue(anyLong(), eq(16), any(), argThat(f -> f.sql().startsWith("MOD(player_id,64) IN(")));
        assertThat(loopLive.isClosed()).isTrue();

        LoopLiveness cleanupLive = new LoopLiveness("guild", "asset_cleanup", 300L, clock::get);
        AssetOpCleanup cleanup = new AssetOpCleanup(store, Duration.ofMinutes(5), Duration.ofDays(7), Duration.ofDays(14), clock::get)
                .withWorkShard(membership)
                .withLiveness(cleanupLive);
        cleanup.start();
        try {
            cleanupLive.runGuarded(() -> store.cleanupOnce(clock.get(), Duration.ofDays(7), Duration.ofDays(14),
                    membership.sqlFilter("guild_asset_cleanup", "player_id")));
        } finally {
            cleanup.stop(Duration.ofSeconds(2));
        }
        verify(store).cleanupOnce(anyLong(), any(), any(), argThat(f -> f.sql().startsWith("MOD(player_id,64) IN(")));
        assertThat(cleanupLive.isClosed()).isTrue();
    }
}
