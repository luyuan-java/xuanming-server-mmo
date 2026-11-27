package com.game.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.workshard.LoopLiveness;
import com.game.common.workshard.WorkShard;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-10 全服匹配第四批：整队开局恢复按发起者 actor_player_id 分 64 片（LIMIT 前置）与循环活性。 */
class TeamLaunchRecoveryShardingTest {

    @Test
    void 整队开局恢复按actorPlayerId分64片且LIMIT前置_非成员不碰库_关闭摘除活性() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        AtomicLong now = new AtomicLong(1_700_000_000_000L);
        WorkShard.Membership stable = new WorkShard.Membership(30_000L, now::get);
        stable.setSelf("team-a");
        stable.updateMembers(List.of("team-a", "team-b"));
        LoopLiveness liveness = new LoopLiveness("team", "launch_recovery", 300L, now::get);

        try (TeamLaunchRecovery recovery = new TeamLaunchRecovery(ds, mock(TeamService.class))
                .withWorkShard(stable)
                .withLiveness(liveness)) {
            now.addAndGet(5_000L);
            recovery.roundSafely();
            assertThat(liveness.lastCompletedEpochSeconds()).isEqualTo(now.get() / 1000L);
        }
        assertThat(liveness.isClosed()).isTrue();
        verify(conn).prepareStatement(argThat(sql ->
                sql.contains("MOD(l.actor_player_id,64) IN(") && sql.indexOf("MOD(l.actor_player_id,64)") < sql.indexOf("LIMIT 2")));

        DataSource canaryDs = mock(DataSource.class);
        WorkShard.Membership canary = new WorkShard.Membership(30_000L, now::get);
        canary.setSelf("team-canary");
        canary.updateMembers(List.of("team-a", "team-b"));
        try (TeamLaunchRecovery recovery = new TeamLaunchRecovery(canaryDs, mock(TeamService.class)).withWorkShard(canary)) {
            recovery.round();
        }
        verify(canaryDs, never()).getConnection();
    }
}
