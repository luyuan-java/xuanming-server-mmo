package com.game.match.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.workshard.LoopLiveness;
import com.game.common.workshard.WorkShard;
import com.game.proto.rank.RankAdminResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** 覆盖 2026-11-10/12 匹配投榜/摘榜发件箱按 64 片 HRW 分工与循环活性。 */
class MatchOutboxShardingAndLivenessTest {

    @Test
    void 摘榜发件箱按playerId分64片且LIMIT前置_canary不读库_关闭时摘除活性序列() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false);

        AtomicLong clock = new AtomicLong(1_700_000_000_000L);
        WorkShard.Membership stable = new WorkShard.Membership(30_000L, clock::get);
        stable.setSelf("match-1");
        stable.updateMembers(List.of("match-1", "match-2"));

        GlobalRankProperties props = new GlobalRankProperties(
                "127.0.0.1", 20880, "0123456789abcdef0123456789abcdef");
        LoopLiveness liveness = new LoopLiveness("match", "rank_outbox", 300L, clock::get);
        try (RatingRankOutbox outbox = new RatingRankOutbox(ds, props)
                .withWorkShard(stable)
                .withLiveness(liveness)) {
            assertThat(outbox.publishPendingRankRemovals(req -> RankAdminResponse.getDefaultInstance())).isZero();
        }
        verify(conn).prepareStatement(argThat(sql ->
                sql.contains("MOD(player_id,64) IN(") && sql.indexOf("MOD(player_id,64)") < sql.indexOf("LIMIT 16")));
        assertThat(liveness.isClosed()).isTrue();

        // canary 不在成员表内 -> 不碰库
        DataSource canaryDs = mock(DataSource.class);
        WorkShard.Membership canary = new WorkShard.Membership(30_000L, clock::get);
        canary.setSelf("match-canary");
        canary.updateMembers(List.of("match-1", "match-2"));
        try (RatingRankOutbox canaryOutbox = new RatingRankOutbox(canaryDs, props).withWorkShard(canary)) {
            assertThat(canaryOutbox.publishPendingRankRemovals(req -> RankAdminResponse.getDefaultInstance())).isZero();
        }
        verify(canaryDs, never()).getConnection();
    }
}
