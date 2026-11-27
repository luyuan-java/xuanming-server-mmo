package com.game.guild.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.common.player.PlayerRouteAuthority;
import com.game.guild.rules.GuildRoles;
import com.game.guild.rules.GuildTips;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

/** 帮会残余收口校验：历练作废 14042、积分 score_version 原子推进与账号注销帮主自动让位/解散。 */
class GuildParityClosureTest {

    @Test
    void 历练作废有对局号回14042_无对局号回14041() {
        assertThat(GuildActivityStore.cancelledLobbyEndTip(99001L)).isEqualTo(GuildTips.TRIAL_BATTLE_ABORTED);
        assertThat(GuildActivityStore.cancelledLobbyEndTip(0L)).isEqualTo(GuildTips.TRIAL_SERVICE_BUSY);
    }

    @Test
    void 写分SQL与帮主让位排序形状符合索引规范() {
        assertThat(JdbcGuildStore.LOCK_GUILD_SCORE).endsWith("WHERE guild_id = ? FOR UPDATE");
        assertThat(JdbcGuildStore.UPDATE_GUILD_SCORE).endsWith("WHERE guild_id = ?");
        assertThat(JdbcGuildStore.SELECT_ALL_VERSIONED_SCORES).doesNotContain("FOR UPDATE");
        assertThat(JdbcGuildStore.SELECT_SUCCESSOR_MEMBER)
                .contains("ORDER BY role DESC, contribution_total DESC, join_time_ms ASC, player_id ASC LIMIT 1");
    }

    @Test
    void 写分同事务推进score_version_帮会不存在返回空() throws Exception {
        try (MockedStatic<PlayerRouteAuthority> route = mockStatic(PlayerRouteAuthority.class)) {
            route.when(() -> PlayerRouteAuthority.requireMeta(any(Connection.class), anyInt(), anyBoolean()))
                    .thenAnswer(inv -> null);

            Connection conn = mock(Connection.class);
            when(conn.getAutoCommit()).thenReturn(true);
            when(conn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

            PreparedStatement lockStmt = mock(PreparedStatement.class);
            ResultSet lockRs = mock(ResultSet.class);
            when(lockRs.next()).thenReturn(true, false);
            when(lockRs.getLong(1)).thenReturn(3L);
            when(lockRs.getString(1)).thenReturn("3");
            when(lockRs.getObject(1)).thenReturn(3L);
            when(lockRs.getLong(2)).thenReturn(7L);
            when(lockRs.getString(2)).thenReturn("7");
            when(lockRs.getObject(2)).thenReturn(7L);
            when(lockStmt.executeQuery()).thenReturn(lockRs);

            PreparedStatement updateStmt = mock(PreparedStatement.class);
            when(updateStmt.executeUpdate()).thenReturn(1);

            when(conn.prepareStatement(JdbcGuildStore.LOCK_GUILD_SCORE)).thenReturn(lockStmt);
            when(conn.prepareStatement(JdbcGuildStore.UPDATE_GUILD_SCORE)).thenReturn(updateStmt);

            JdbcGuildStore store = new JdbcGuildStore(new GuildTx(w -> conn, 1, GuildTxListener.NONE), GuildTxHooks.NONE);
            Optional<GuildStore.VersionedScore> updated = store.updateGuildScore(5001L, 1200L, Deadline.after(5_000));
            assertThat(updated).isPresent();
            assertThat(updated.get().guildId()).isEqualTo(5001L);
            assertThat(updated.get().zoneId()).isEqualTo(3);
            assertThat(updated.get().score()).isEqualTo(1200L);
            assertThat(updated.get().scoreVersion()).isEqualTo(8L);
        }
    }

    @Test
    void 注销收尾入参非法提前拒绝_不在帮返回NOT_IN_GUILD() throws Exception {
        try (MockedStatic<PlayerRouteAuthority> route = mockStatic(PlayerRouteAuthority.class)) {
            route.when(() -> PlayerRouteAuthority.requireMeta(any(Connection.class), anyInt(), anyBoolean()))
                    .thenAnswer(inv -> null);
            Connection conn = mock(Connection.class);
            when(conn.getAutoCommit()).thenReturn(true);
            when(conn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
            PreparedStatement stmt = mock(PreparedStatement.class);
            ResultSet emptyRs = mock(ResultSet.class);
            when(emptyRs.next()).thenReturn(false);
            when(stmt.executeQuery()).thenReturn(emptyRs);
            when(stmt.executeUpdate()).thenReturn(0);
            when(conn.prepareStatement(anyString())).thenReturn(stmt);

            JdbcGuildStore store = new JdbcGuildStore(new GuildTx(w -> conn, 1, GuildTxListener.NONE), GuildTxHooks.NONE);
            assertThatThrownBy(() -> store.purgeDeletedPlayer(0L, 1000L, Deadline.after(5_000)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> store.purgeDeletedPlayer(42L, 0L, Deadline.after(5_000)))
                    .isInstanceOf(IllegalArgumentException.class);

            GuildStore.DeletionOutcome out = store.purgeDeletedPlayer(42L, 1000L, Deadline.after(5_000));
            assertThat(out.kind()).isEqualTo(GuildStore.DeletionOutcome.Kind.NOT_IN_GUILD);
            assertThat(out.guildId()).isZero();
            assertThat(GuildRoles.LEADER).isEqualTo(3);
        }
    }

    @Test
    void 转让帮主拒绝冻结中与已注销目标() throws Exception {
        try (MockedStatic<PlayerRouteAuthority> route = mockStatic(PlayerRouteAuthority.class)) {
            route.when(() -> PlayerRouteAuthority.requireMeta(any(Connection.class), anyInt(), anyBoolean()))
                    .thenAnswer(inv -> null);
            for (var fenceState : List.of(
                    com.game.guild.war.GuildWarStore.CreditFenceState.FROZEN,
                    com.game.guild.war.GuildWarStore.CreditFenceState.DELETED)) {
                Connection conn = mock(Connection.class);
                when(conn.getAutoCommit()).thenReturn(true);
                when(conn.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);

                PreparedStatement lockGuild = mock(PreparedStatement.class);
                ResultSet guildRs = mock(ResultSet.class);
                when(guildRs.next()).thenReturn(true, false);
                when(guildRs.getLong(1)).thenReturn(1L);    // level
                when(guildRs.getObject(2)).thenReturn(10L); // leader_id
                when(guildRs.getLong(3)).thenReturn(1L);    // zone_id
                when(guildRs.getLong(4)).thenReturn(50L);   // max_members
                when(lockGuild.executeQuery()).thenReturn(guildRs);

                PreparedStatement lockMember = mock(PreparedStatement.class);
                ResultSet memberRs = mock(ResultSet.class);
                when(memberRs.next()).thenReturn(true, true, false);
                when(memberRs.getLong(1)).thenReturn((long) GuildRoles.LEADER, (long) GuildRoles.OFFICER);
                when(lockMember.executeQuery()).thenReturn(memberRs);

                when(conn.prepareStatement(JdbcGuildStore.LOCK_GUILD)).thenReturn(lockGuild);
                when(conn.prepareStatement(JdbcGuildStore.LOCK_MEMBER_ROLE)).thenReturn(lockMember);

                JdbcGuildStore store = new JdbcGuildStore(new GuildTx(w -> conn, 1, GuildTxListener.NONE), GuildTxHooks.NONE)
                        .withTargetFence((c, pid) -> fenceState);
                var outcome = store.transferLeader(500L, 10L, 20L, lvl -> java.util.OptionalInt.of(2), Deadline.after(5_000));
                assertThat(outcome.rejection()).isEqualTo(com.game.guild.rules.GuildReject.TARGET_NOT_MEMBER);
            }
        }
    }

    @Test
    void 帮会内部注销收尾提交后按结局失效缓存_解散摘榜_让位推送() {
        GuildStore store = mock(GuildStore.class);
        com.game.guild.cache.GuildCacheInvalidator inv = mock(com.game.guild.cache.GuildCacheInvalidator.class);
        com.game.guild.rank.GuildRanks ranks = mock(com.game.guild.rank.GuildRanks.class);
        com.game.guild.push.GuildPushes pushes = mock(com.game.guild.push.GuildPushes.class);
        com.game.guild.GuildInternalServiceProvider provider =
                new com.game.guild.GuildInternalServiceProvider(null, store, inv, ranks, pushes, Runnable::run, 5_000L);

        // 1. 帮主自动让位 -> 失效缓存 + 推 LEADER_TRANSFERRED，不摘榜
        when(store.purgeDeletedPlayer(org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new GuildStore.DeletionOutcome(GuildStore.DeletionOutcome.Kind.LEADER_TRANSFERRED,
                        700L, 2, 20L, List.of(20L, 30L)));
        assertThat(provider.purgeRemovedRole(10L).join()).isTrue();
        org.mockito.Mockito.verify(inv).afterCommit(
                org.mockito.ArgumentMatchers.eq(com.game.guild.cache.InvalidationOp.TRANSFER),
                org.mockito.ArgumentMatchers.eq(700L), any(), org.mockito.ArgumentMatchers.eq(10L));
        org.mockito.Mockito.verify(pushes).notify(
                com.game.proto.guild.GuildChangeKind.GUILD_CHANGE_KIND_LEADER_TRANSFERRED,
                700L, 10L, 20L, List.of(20L, 30L));
        org.mockito.Mockito.verify(ranks, org.mockito.Mockito.never())
                .remove(org.mockito.ArgumentMatchers.anyLong(), anyInt(), any());

        // 2. 独人帮主解散 -> 失效缓存 + 摘帮会榜
        when(store.purgeDeletedPlayer(org.mockito.ArgumentMatchers.eq(99L), org.mockito.ArgumentMatchers.anyLong(), any()))
                .thenReturn(new GuildStore.DeletionOutcome(GuildStore.DeletionOutcome.Kind.DISBANDED,
                        800L, 3, 0L, List.of()));
        assertThat(provider.purgeRemovedRole(99L).join()).isTrue();
        org.mockito.Mockito.verify(ranks).remove(org.mockito.ArgumentMatchers.eq(800L), org.mockito.ArgumentMatchers.eq(3), any());
    }
}
