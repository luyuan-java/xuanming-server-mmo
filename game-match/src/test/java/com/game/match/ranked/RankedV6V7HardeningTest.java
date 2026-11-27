package com.game.match.ranked;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.proto.BattlePlacement;
import com.game.match.rating.RatingTestDatabase;
import com.game.match.spectate.RankedSpectateIndex;
import com.game.match.spectate.SpectateStore;
import com.game.match.spectate.SpectateTierStore;
import com.game.match.spectate.WatchableListService;
import com.game.proto.eSpectateVisibility;
import com.game.proto.match.BattleWatchSummary;
import com.game.proto.match.MatchMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-26 论剑 V6/V7 生产级强一致性与水平扩展加固：16 桶排位直播防 PVE 稀释、排序、与事务内复用连接读赛季。 */
class RankedV6V7HardeningTest {

    @Test
    void 十六桶排位直播索引键格式正确且ListWatchableBattles排在赛事后PVE前并按巅峰分降序() {
        // 1. 校验 16 桶 Redis Cluster hash tag 键格式与均匀取模
        for (int b = 0; b < 16; b++) {
            assertThat(RankedSpectateIndex.bucketKey("game", b))
                    .isEqualTo("game:spectate:ranked:active:{srk:" + b + "}");
            assertThat(RankedSpectateIndex.bucketOf(1000L * 16 + b)).isEqualTo(b);
        }

        // 2. 30 场较新的普通 PVE 战局 + 3 场高段正式排位局 + 1 场赛事局，limit=5：
        //    赛事局排第 1，3 场高段排位紧随其后且按 ranked_peak_rating_centi DESC 排序，PVE 无法稀释排位席位。
        long nowMs = 1_700_000_000_000L;
        List<SpectateStore.Scored> genericMembers = new ArrayList<>();
        Map<Long, SpectateStore.Record> records = new LinkedHashMap<>();

        // 赛事局 9001
        records.put(9001L, new SpectateStore.Record.Found(placement(9001L, MatchMode.MATCH_MODE_SEASON_TOURNAMENT_VALUE, nowMs - 60_000, 0, 0)));
        // 3 场高段排位（比 PVE 开局早，但在独立 16 桶排位索引里）
        records.put(8001L, new SpectateStore.Record.Found(placement(8001L, MatchMode.MATCH_MODE_PVP_TEAM_VALUE, nowMs - 40_000, 1, 165_000L)));
        records.put(8002L, new SpectateStore.Record.Found(placement(8002L, MatchMode.MATCH_MODE_PVP_TEAM_VALUE, nowMs - 50_000, 3, 195_000L)));
        records.put(8003L, new SpectateStore.Record.Found(placement(8003L, MatchMode.MATCH_MODE_PVP_TEAM_VALUE, nowMs - 30_000, 1, 180_000L)));
        // 30 场最新 PVE 战局
        for (int i = 0; i < 30; i++) {
            long id = 5000L + i;
            long created = nowMs - (30 - i) * 100L;
            genericMembers.add(new SpectateStore.Scored(Long.toUnsignedString(id), created));
            records.put(id, new SpectateStore.Record.Found(placement(id, MatchMode.MATCH_MODE_PVE_TEAM_VALUE, created, 0, 0)));
        }

        SpectateStore store = mock(SpectateStore.class);
        when(store.list(anyInt(), any())).thenReturn(new SpectateStore.Listed(genericMembers.subList(0, 5), nowMs));
        when(store.readPlacements(any(), any())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            Map<Long, SpectateStore.Record> out = new LinkedHashMap<>();
            for (long id : ids) {
                out.put(id, records.getOrDefault(id, new SpectateStore.Record.Absent()));
            }
            return out;
        });

        SpectateTierStore tiers = mock(SpectateTierStore.class);
        when(tiers.activeFeatured(anyInt(), any())).thenReturn(List.of(9001L));

        Map<Long, Long> inMemRankedBuckets = new ConcurrentHashMap<>();
        RankedSpectateIndex rankedIndex = new RankedSpectateIndex() {
            @Override public void record(long battleId, long createdAtMs) { inMemRankedBuckets.put(battleId, createdAtMs); }
            @Override public void remove(long battleId) { inMemRankedBuckets.remove(battleId); }
            @Override public long sweepExpired(long cutoffMs) {
                long before = inMemRankedBuckets.size();
                inMemRankedBuckets.entrySet().removeIf(e -> e.getValue() < cutoffMs);
                return before - inMemRankedBuckets.size();
            }
            @Override public List<Long> activeBattles(int limit, long now, Deadline d) {
                return inMemRankedBuckets.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                        .limit(limit).map(Map.Entry::getKey).toList();
            }
        };
        rankedIndex.record(8001L, nowMs - 40_000);
        rankedIndex.record(8002L, nowMs - 50_000);
        rankedIndex.record(8003L, nowMs - 30_000);

        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        try {
            WatchableListService svc = new WatchableListService(store, new MatchMetrics(meters, new MetricLabels(id -> false)))
                    .withFeatured(tiers)
                    .withRankedIndex(rankedIndex);
            var res = svc.list(5, Deadline.after(3000));
            assertThat(res).isInstanceOf(WatchableListService.Result.Listed.class);
            List<BattleWatchSummary> list = ((WatchableListService.Result.Listed) res).response().getBattlesList();
            assertThat(list).extracting(BattleWatchSummary::getBattleId)
                    .containsExactly(9001L, 8002L, 8003L, 8001L, 5000L);
            assertThat(list.get(0).getFeatured()).isTrue();
            assertThat(list.get(1).getRankedPeakRatingCenti()).isEqualTo(195_000L);
            assertThat(list.get(2).getRankedPeakRatingCenti()).isEqualTo(180_000L);
            assertThat(list.get(3).getRankedPeakRatingCenti()).isEqualTo(165_000L);
        } finally {
            meters.close();
        }
    }

    @Test
    void ensureActiveSeasonInTx复用调用方连接绝不嵌套建连() throws Exception {
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            DataSource spyDs = mock(DataSource.class);
            var tables = RankedTables.from(com.game.table.ConfigTables.load(java.nio.file.Path.of("../config-data/tables")));
            RankedStore store = new RankedStore(spyDs, tables, System::currentTimeMillis);
            try (Connection existingTxConn = db.dataSource.getConnection()) {
                var season = store.ensureActiveSeasonInTx(existingTxConn);
                assertThat(season.getSeasonId()).isEqualTo(1);
            }
            // 事务内读取赛季全程不从 DataSource 另借第二条连接，杜绝连接池耗尽死锁
            verify(spyDs, never()).getConnection();
        }
    }

    private static BattlePlacement placement(long battleId, int mode, long createdAtMs, int teamSize, long peakCenti) {
        return BattlePlacement.newBuilder()
                .setBattleId(battleId)
                .setMode(mode)
                .setBattleConfigId(101)
                .setCreatedAtMs(createdAtMs)
                .setSpectateVisibility(eSpectateVisibility.SPECTATE_VISIBILITY_PUBLIC_VALUE)
                .setRankedTeamSize(teamSize)
                .setRankedPeakRatingCenti(peakCenti)
                .setAttempt(1)
                .build();
    }
}
