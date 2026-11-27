package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.api.PlayerInboxService;
import com.game.common.workshard.LoopLiveness;
import com.game.common.workshard.WorkShard;
import com.game.match.global.GlobalMatchStore;
import com.game.match.metrics.MatchMetrics;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-10/12 匹配终局发件箱 64 片 HRW 分工（整局 battle_id、个人 player_id）与循环活性/兜底。 */
class BattleCompletionShardingTest {

    @Test
    void 终局重投整局按battleId个人按playerId分64片且LIMIT前置_截止自动放弃不分片_canary不读发件箱() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        BattleCompletionStore store = new BattleCompletionStore(ds, c -> clock.get());
        GlobalMatchStore formations = mock(GlobalMatchStore.class);
        when(formations.terminalDeadlineCandidates(anyLong(), anyLong()))
                .thenReturn(new GlobalMatchStore.TerminalDeadlineBatch(0, List.of()));

        WorkShard.Membership stable = new WorkShard.Membership(30_000L, clock::get);
        stable.setSelf("match-a");
        stable.updateMembers(List.of("match-a", "match-b"));

        AtomicInteger panics = new AtomicInteger();
        LoopLiveness liveness = new LoopLiveness("match", "terminal_replay", 300L, clock::get);
        liveness.bindCallbacks(panics::incrementAndGet, null);

        try (BattleCompletionReplay replay = new BattleCompletionReplay(
                store, formations, mock(PlayerInboxService.class),
                (e, orig) -> CompletableFuture.completedFuture(null),
                "0123456789abcdef0123456789abcdef", clock::get, mock(MatchMetrics.class))
                .withWorkShard(stable)
                .withLiveness(liveness)) {
            clock.addAndGet(5_000L);
            replay.roundSafely();
            assertThat(liveness.lastCompletedEpochSeconds()).isEqualTo(clock.get() / 1000L);
            assertThat(panics.get()).isZero();

            // 模拟未捕获 Error（如 OOM/AssertionError）不推进活性时间戳但循环不死
            when(formations.terminalDeadlineCandidates(anyLong(), anyLong())).thenThrow(new AssertionError("boom"));
            long beforeError = liveness.lastCompletedEpochSeconds();
            clock.addAndGet(10_000L);
            replay.roundSafely();
            assertThat(liveness.lastCompletedEpochSeconds()).isEqualTo(beforeError);
            assertThat(panics.get()).isEqualTo(1);
        }
        assertThat(liveness.isClosed()).isTrue();

        verify(conn, atLeastOnce()).prepareStatement(argThat(sql ->
                sql.contains("MOD(battle_id,64) IN(") && sql.indexOf("MOD(battle_id,64)") < sql.indexOf("LIMIT 16")));
        verify(conn, atLeastOnce()).prepareStatement(argThat(sql ->
                sql.contains("MOD(player_id,64) IN(") && sql.contains("MAX(battle_id)")));

        // canary 不在成员表内 -> 整局/个人发件箱均不碰库，仅执行截止自动放弃检查
        DataSource canaryDs = mock(DataSource.class);
        BattleCompletionStore canaryStore = new BattleCompletionStore(canaryDs, c -> clock.get());
        GlobalMatchStore canaryFormations = mock(GlobalMatchStore.class);
        when(canaryFormations.terminalDeadlineCandidates(anyLong(), anyLong()))
                .thenReturn(new GlobalMatchStore.TerminalDeadlineBatch(0, List.of()));
        WorkShard.Membership canary = new WorkShard.Membership(30_000L, clock::get);
        canary.setSelf("match-canary");
        canary.updateMembers(List.of("match-a", "match-b"));
        try (BattleCompletionReplay canaryReplay = new BattleCompletionReplay(
                canaryStore, canaryFormations, mock(PlayerInboxService.class),
                (e, orig) -> CompletableFuture.completedFuture(null),
                "0123456789abcdef0123456789abcdef", clock::get, mock(MatchMetrics.class))
                .withWorkShard(canary)) {
            canaryReplay.round();
        }
        verify(canaryFormations).terminalDeadlineCandidates(0L, 0L);
        verify(canaryDs, never()).getConnection();
    }
}
