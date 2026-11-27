package com.game.guild.war;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.guild.asset.AssetOp;
import com.game.guild.rules.GuildLimits;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.proto.TransactionType;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.guild.GuildWarBountyStatus;
import com.game.proto.guild.GuildWarBountyTier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/**
 * 帮战 §7.4（r2-gw#5 / #6 / #7）纯单测（零新增第三方依赖）：
 * <ul>
 *   <li>r2-gw#5：某成员未决满窗（16 条）不阻断整局结算与到期退款，自动按原 op_id 记入 guild_war_credit_owed 并由循环幂等补插；</li>
 *   <li>r2-gw#6：冻结角色记待入账、解冻后按原 op_id 补插；已删角色零写入，已删领赏人抛 bounty_winner_deleted 保持追杀令 ACTIVE；</li>
 *   <li>r2-gw#7：ensureSchema 建 escrow_op_id 索引与待入账表；purgeTerminalBounties 仅清理满 30 天终态行（3/4/5/6），绝不碰 1/2。</li>
 * </ul>
 */
final class GuildWarCreditOwedAndPurgeTest {

    private static AssetBundle silver(long amount) {
        return AssetBundle.newBuilder()
                .addCurrencies(AssetCurrency.newBuilder()
                        .setCurrencyType(CurrencyType.CURRENCY_TYPE_GOLD_VALUE)
                        .setAmount(amount))
                .build();
    }

    /** 记录所有执行的 SQL 与绑定参数的轻量级模拟连接工厂。 */
    private static final class FakeDb {
        final DataSource ds = mock(DataSource.class);
        final List<String> executedSql = new ArrayList<>();
        final List<Object[]> owedInserts = new ArrayList<>();
        final List<Object[]> assetOpInserts = new ArrayList<>();
        final List<Long> deletedBounties = new ArrayList<>();
        final List<Long> deletedOwedOpIds = new ArrayList<>();
        final List<Long> deferredOwedOpIds = new ArrayList<>();
        final Map<Long, Integer> pendingOpCountByPlayer = new HashMap<>();
        final Map<Long, Integer> bountyStatusById = new HashMap<>();
        final Map<Long, Long> bountyResolvedById = new HashMap<>();
        final Map<Long, GuildWarStore.BountyRow> bountyRowById = new HashMap<>();
        final List<Object[]> owedRowsForConvert = new ArrayList<>();
        boolean battleExists = false;

        FakeDb() throws Exception {
            when(ds.getConnection()).thenAnswer(inv -> newConnection());
        }

        private Connection newConnection() throws Exception {
            Connection c = mock(Connection.class);
            Statement st = mock(Statement.class);
            when(c.createStatement()).thenReturn(st);
            when(st.execute(anyString())).thenAnswer(inv -> {
                executedSql.add(inv.getArgument(0));
                return true;
            });
            when(c.prepareStatement(anyString())).thenAnswer(inv -> newPrepared(inv.getArgument(0)));
            return c;
        }

        private PreparedStatement newPrepared(String sql) throws Exception {
            PreparedStatement ps = mock(PreparedStatement.class);
            Map<Integer, Object> params = new HashMap<>();
            Answer<Void> capture = inv -> {
                params.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            };
            org.mockito.Mockito.doAnswer(capture).when(ps).setLong(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyLong());
            org.mockito.Mockito.doAnswer(capture).when(ps).setInt(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
            org.mockito.Mockito.doAnswer(capture).when(ps).setString(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());
            org.mockito.Mockito.doAnswer(capture).when(ps).setBytes(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any());

            when(ps.executeQuery()).thenAnswer(inv -> {
                executedSql.add(sql);
                return queryResult(sql, params);
            });
            when(ps.executeUpdate()).thenAnswer(inv -> {
                executedSql.add(sql);
                return updateResult(sql, params);
            });
            return ps;
        }

        private ResultSet queryResult(String sql, Map<Integer, Object> p) throws Exception {
            ResultSet rs = mock(ResultSet.class);
            if (sql.contains("FROM guild_war_battle WHERE battle_id")) {
                when(rs.next()).thenReturn(battleExists);
                return rs;
            }
            if (sql.contains("SELECT 1 FROM guild_player_op_seq")) {
                when(rs.next()).thenReturn(true);
                return rs;
            }
            if (sql.contains("SELECT next_seq, epoch FROM guild_player_op_seq")) {
                AtomicBoolean first = new AtomicBoolean(true);
                when(rs.next()).thenAnswer(i -> first.getAndSet(false));
                long pid = (Long) p.getOrDefault(1, 0L);
                long cnt = pendingOpCountByPlayer.getOrDefault(pid, 0);
                when(rs.getLong(1)).thenReturn(cnt + 1L);
                when(rs.getLong(2)).thenReturn(1L);
                return rs;
            }
            if (sql.contains("SELECT seq FROM guild_asset_op")) {
                long pid = (Long) p.getOrDefault(1, 0L);
                int cnt = pendingOpCountByPlayer.getOrDefault(pid, 0);
                AtomicInteger idx = new AtomicInteger(0);
                when(rs.next()).thenAnswer(i -> idx.incrementAndGet() <= cnt);
                when(rs.getLong(1)).thenAnswer(i -> (long) idx.get());
                return rs;
            }
            if (sql.contains("FROM guild_war_bounty WHERE status IN (3, 4, 5, 6)")) {
                List<Long> ids = new ArrayList<>(bountyStatusById.keySet());
                AtomicInteger idx = new AtomicInteger(-1);
                when(rs.next()).thenAnswer(i -> idx.incrementAndGet() < ids.size());
                when(rs.getLong(1)).thenAnswer(i -> ids.get(idx.get()));
                return rs;
            }
            if (sql.contains("SELECT status, resolved_at_ms FROM guild_war_bounty WHERE bounty_id")) {
                long bid = (Long) p.get(1);
                boolean exists = bountyStatusById.containsKey(bid);
                AtomicBoolean first = new AtomicBoolean(exists);
                when(rs.next()).thenAnswer(i -> first.getAndSet(false));
                when(rs.getInt(1)).thenReturn(bountyStatusById.getOrDefault(bid, 0));
                when(rs.getLong(2)).thenReturn(bountyResolvedById.getOrDefault(bid, 0L));
                return rs;
            }
            if (sql.contains("FROM guild_war_bounty WHERE bounty_id = ? FOR UPDATE")) {
                long bid = (Long) p.get(1);
                GuildWarStore.BountyRow row = bountyRowById.get(bid);
                AtomicBoolean first = new AtomicBoolean(row != null);
                when(rs.next()).thenAnswer(i -> first.getAndSet(false));
                if (row != null) {
                    when(rs.getLong(1)).thenReturn(row.bountyId());
                    when(rs.getInt(2)).thenReturn(row.zoneId());
                    when(rs.getLong(3)).thenReturn(row.issuerPlayerId());
                    when(rs.getLong(4)).thenReturn(row.issuerGuildId());
                    when(rs.getLong(5)).thenReturn(row.targetPlayerId());
                    when(rs.getLong(6)).thenReturn(row.targetGuildId());
                    when(rs.getInt(7)).thenReturn(row.tier());
                    when(rs.getLong(8)).thenReturn(row.escrowSilver());
                    when(rs.getLong(9)).thenReturn(row.taxSilver());
                    when(rs.getInt(10)).thenReturn(row.publicBoard());
                    when(rs.getInt(11)).thenReturn(row.status());
                    when(rs.getInt(12)).thenReturn(row.counterKills());
                    when(rs.getString(13)).thenReturn(row.counterKillersJson());
                    when(rs.getLong(14)).thenReturn(row.escrowOpId());
                    when(rs.getLong(15)).thenReturn(row.createdAtMs());
                    when(rs.getLong(16)).thenReturn(row.expiresAtMs());
                    when(rs.getLong(17)).thenReturn(row.resolvedAtMs());
                    when(rs.getLong(18)).thenReturn(row.winnerPlayerId());
                    when(rs.getLong(19)).thenReturn(row.escrowGuildFunds());
                }
                return rs;
            }
            if (sql.contains("SELECT player_id, op_id FROM guild_war_credit_owed")) {
                AtomicInteger idx = new AtomicInteger(-1);
                when(rs.next()).thenAnswer(i -> idx.incrementAndGet() < owedRowsForConvert.size());
                when(rs.getLong(1)).thenAnswer(i -> (Long) owedRowsForConvert.get(idx.get())[0]);
                when(rs.getLong(2)).thenAnswer(i -> (Long) owedRowsForConvert.get(idx.get())[1]);
                return rs;
            }
            if (sql.contains("SELECT guild_id, kind, tx_type, ref_id, period_key, payload FROM guild_war_credit_owed")) {
                long pid = (Long) p.get(1);
                long opId = (Long) p.get(2);
                Object[] found = owedRowsForConvert.stream()
                        .filter(r -> (Long) r[0] == pid && (Long) r[1] == opId)
                        .findFirst().orElse(null);
                AtomicBoolean first = new AtomicBoolean(found != null);
                when(rs.next()).thenAnswer(i -> first.getAndSet(false));
                if (found != null) {
                    when(rs.getLong(1)).thenReturn((Long) found[2]);
                    when(rs.getInt(2)).thenReturn((Integer) found[3]);
                    when(rs.getInt(3)).thenReturn((Integer) found[4]);
                    when(rs.getInt(4)).thenReturn((Integer) found[5]);
                    when(rs.getInt(5)).thenReturn((Integer) found[6]);
                    when(rs.getBytes(6)).thenReturn((byte[]) found[7]);
                }
                return rs;
            }
            when(rs.next()).thenReturn(false);
            return rs;
        }

        private int updateResult(String sql, Map<Integer, Object> p) {
            if (sql.startsWith("INSERT INTO guild_war_credit_owed")) {
                owedInserts.add(new Object[] {p.get(1), p.get(2), p.get(3), p.get(4)});
                return 1;
            }
            if (sql.startsWith("INSERT INTO guild_asset_op")) {
                assetOpInserts.add(new Object[] {p.get(1), p.get(2)});
                return 1;
            }
            if (sql.startsWith("DELETE FROM guild_war_bounty WHERE bounty_id")) {
                deletedBounties.add((Long) p.get(1));
                return 1;
            }
            if (sql.startsWith("DELETE FROM guild_war_credit_owed")) {
                deletedOwedOpIds.add((Long) p.get(2));
                return 1;
            }
            if (sql.startsWith("UPDATE guild_war_credit_owed SET next_attempt_ms")) {
                deferredOwedOpIds.add((Long) p.get(4));
                return 1;
            }
            return 1;
        }
    }

    @Test
    void ensureSchema创建escrowOpId索引与待入账表且PLAYER_TABLE护栏正确识别正常冻结与已删() throws Exception {
        FakeDb db = new FakeDb();
        GuildWarStore store = new GuildWarStore(db.ds);
        store.ensureSchema();
        assertThat(db.executedSql).anyMatch(s -> s.contains("idx_guild_war_bounty_escrow_op") && s.contains("escrow_op_id"));
        assertThat(db.executedSql).anyMatch(s -> s.contains("CREATE TABLE IF NOT EXISTS guild_war_credit_owed"));

        Connection c = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getLong(1)).thenReturn(0L, 42L);
        assertThat(GuildWarStore.RoleFence.PLAYER_TABLE.classifyInTx(c, 1L)).isEqualTo(GuildWarStore.CreditFenceState.NORMAL);
        assertThat(GuildWarStore.RoleFence.PLAYER_TABLE.classifyInTx(c, 2L)).isEqualTo(GuildWarStore.CreditFenceState.FROZEN);
        assertThat(GuildWarStore.RoleFence.PLAYER_TABLE.classifyInTx(c, 3L)).isEqualTo(GuildWarStore.CreditFenceState.DELETED);
    }

    @Test
    void 满窗与冻结落待入账_已删零写入且领赏人已删保活追杀令() throws Exception {
        FakeDb db = new FakeDb();
        Map<Long, GuildWarStore.CreditFenceState> fence = new HashMap<>();
        fence.put(101L, GuildWarStore.CreditFenceState.NORMAL);
        fence.put(102L, GuildWarStore.CreditFenceState.NORMAL);
        fence.put(201L, GuildWarStore.CreditFenceState.FROZEN);
        fence.put(999L, GuildWarStore.CreditFenceState.DELETED);
        db.pendingOpCountByPlayer.put(101L, GuildLimits.ASSET_OP_MAX_PENDING); // 101 未决满窗

        GuildWarStore store = new GuildWarStore(db.ds).withRoleFence((c, pid) ->
                fence.getOrDefault(pid, GuildWarStore.CreditFenceState.NORMAL));
        long now = 1_800_000_000_000L;

        GuildWarStore.BattleRow battle = new GuildWarStore.BattleRow(
                7001L, 1, 1, 0, 0L, 1001L, 1002L, 1, 4,
                0, 0, 0, 0, 0L, 0L, 100,
                100L, 500L, 0L, 10L,
                50L, 200L, 0L, 5L, "{}".getBytes(), now);
        List<GuildWarStore.PlayerBattleCredit> credits = List.of(
                new GuildWarStore.PlayerBattleCredit(101L, 1001L, 100L, 500L, 880_101L, silver(500), 11L),
                new GuildWarStore.PlayerBattleCredit(102L, 1001L, 100L, 500L, 880_102L, silver(500), 12L),
                new GuildWarStore.PlayerBattleCredit(201L, 1002L, 50L, 200L, 880_201L, silver(200), 13L),
                new GuildWarStore.PlayerBattleCredit(999L, 1002L, 50L, 200L, 880_999L, silver(200), 14L));

        GuildWarStore.SettleBattleResult res = store.settleBattleWithAssets(
                battle, 202648, 20261127, List.of(101L, 102L), List.of(201L, 999L),
                null, false, 0L, GuildWarStore.BountySettlePlan.NONE, 0L, 0L,
                0L, 0L, credits, now + 30_000L, now);

        assertThat(res.settled()).isTrue();
        // 仅 102 直接建指令；101（满窗）与 201（冻结）落 guild_war_credit_owed 且沿用预发 op_id；已删 999 零写入
        assertThat(res.createdOps()).extracting(AssetOp::opId).containsExactly(880_102L);
        assertThat(db.owedInserts).extracting(r -> (Long) r[1]).containsExactlyInAnyOrder(880_101L, 880_201L);
        assertThat(db.owedInserts).extracting(r -> (Long) r[0]).doesNotContain(999L);

        // 领赏人 999 已删除 -> 抛 bounty_winner_deleted 保持追杀令 ACTIVE
        GuildWarStore.BountySettlePlan delWinner = new GuildWarStore.BountySettlePlan(
                true, true, 0, "", GuildWarBountyStatus.GUILD_WAR_BOUNTY_STATUS_COMPLETED_VALUE, 0, "",
                999L, 1001L, 1000L, 881_999L, silver(1000), 21L, 3,
                102L, 1001L, 0L, 0L, null, 0L, 0L);
        assertThatThrownBy(() -> store.settleBattleWithAssets(
                battle, 202648, 20261127, List.of(999L), List.of(201L),
                null, false, 0L, delWinner, 0L, 0L,
                0L, 0L, List.of(), now + 30_000L, now))
                .isInstanceOf(GuildWarStore.WarRuleException.class)
                .hasMessageContaining("bounty_winner_deleted");
    }

    @Test
    void 到期退款分流_待入账按原opId补插_终态满30天清理不碰活跃单() throws Exception {
        FakeDb db = new FakeDb();
        Map<Long, GuildWarStore.CreditFenceState> fence = new HashMap<>();
        fence.put(101L, GuildWarStore.CreditFenceState.NORMAL);
        fence.put(201L, GuildWarStore.CreditFenceState.FROZEN);
        fence.put(999L, GuildWarStore.CreditFenceState.DELETED);
        db.pendingOpCountByPlayer.put(101L, GuildLimits.ASSET_OP_MAX_PENDING);

        GuildWarStore store = new GuildWarStore(db.ds).withRoleFence((c, pid) ->
                fence.getOrDefault(pid, GuildWarStore.CreditFenceState.NORMAL));
        long now = 1_800_000_000_000L;

        // 1. 到期追杀令发布者 101 满窗 -> 置 EXPIRED(5) 且退款按原 op_id 落 guild_war_credit_owed
        GuildWarStore.BountyRow b1 = new GuildWarStore.BountyRow(
                601L, 1, 101L, 1001L, 201L, 1002L,
                GuildWarBountyTier.GUILD_WAR_BOUNTY_TIER_NORMAL_SILVER_VALUE,
                1000L, 0L, 1, 2, 0, "", 1001L, now - 10_000L, now - 1_000L, 0L, 0L, 0L);
        db.bountyRowById.put(601L, b1);
        GuildWarStore.ExpireBountyResult exp1 = store.expireBountyWithRefund(
                b1, 881_601L, silver(1000), now + 30_000L, 31L, now);
        assertThat(exp1.expired()).isTrue();
        assertThat(exp1.refundOp()).isNull();
        assertThat(db.owedInserts).extracting(r -> (Long) r[1]).containsExactly(881_601L);

        // 2. 到期追杀令发布者 999 已删 -> 放弃退款、零待入账，仍置 EXPIRED(5)
        GuildWarStore.BountyRow b2 = new GuildWarStore.BountyRow(
                602L, 1, 999L, 1001L, 201L, 1002L,
                GuildWarBountyTier.GUILD_WAR_BOUNTY_TIER_NORMAL_SILVER_VALUE,
                1000L, 0L, 1, 2, 0, "", 1002L, now - 10_000L, now - 1_000L, 0L, 0L, 0L);
        db.bountyRowById.put(602L, b2);
        db.owedInserts.clear();
        GuildWarStore.ExpireBountyResult exp2 = store.expireBountyWithRefund(
                b2, 881_602L, silver(1000), now + 30_000L, 32L, now);
        assertThat(exp2.expired()).isTrue();
        assertThat(db.owedInserts).isEmpty();

        // 3. 待入账循环补插：101（清空未决后 NORMAL）按原 op_id 转为正式指令并删待入账；201（FROZEN）延后；999（DELETED）直接清待入账不建指令
        db.pendingOpCountByPlayer.put(101L, 0);
        byte[] payload = silver(500).toByteArray();
        db.owedRowsForConvert.add(new Object[] {101L, 880_101L, 1001L, GuildAssetOpKind.GUILD_ASSET_OP_KIND_WAR_REWARD_VALUE,
                TransactionType.TX_GUILD_WAR_REWARD_VALUE, 1, 202648, payload});
        db.owedRowsForConvert.add(new Object[] {201L, 880_201L, 1002L, GuildAssetOpKind.GUILD_ASSET_OP_KIND_WAR_REWARD_VALUE,
                TransactionType.TX_GUILD_WAR_REWARD_VALUE, 1, 202648, payload});
        db.owedRowsForConvert.add(new Object[] {999L, 880_999L, 1002L, GuildAssetOpKind.GUILD_ASSET_OP_KIND_WAR_REWARD_VALUE,
                TransactionType.TX_GUILD_WAR_REWARD_VALUE, 1, 202648, payload});
        AtomicLong tok = new AtomicLong(500L);
        List<AssetOp> converted = store.convertCreditOwed(10, now, now + 30_000L, tok::incrementAndGet);
        assertThat(converted).extracting(AssetOp::opId).containsExactly(880_101L);
        assertThat(db.deletedOwedOpIds).containsExactlyInAnyOrder(880_101L, 880_999L);
        assertThat(db.deferredOwedOpIds).containsExactly(880_201L);

        // 4. 终态追杀令满 30 天清理（r2-gw#7）：删 701(status=6, 31d前)，留 702(status=3, 29d前) 与 703(status=2 ACTIVE)
        long oldResolved = now - GuildWarStore.TERMINAL_BOUNTY_RETENTION_MS - 86_400_000L;
        long recentResolved = now - GuildWarStore.TERMINAL_BOUNTY_RETENTION_MS + 86_400_000L;
        db.bountyStatusById.put(701L, 6);
        db.bountyResolvedById.put(701L, oldResolved);
        db.bountyStatusById.put(702L, 3);
        db.bountyResolvedById.put(702L, recentResolved);
        db.bountyStatusById.put(703L, 2);
        db.bountyResolvedById.put(703L, oldResolved);
        int purged = store.purgeTerminalBounties(now, 200);
        assertThat(purged).isEqualTo(1);
        assertThat(db.deletedBounties).containsExactly(701L);
    }
}
