package com.game.guild.jianghu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionContext;
import com.game.contract.MessageIdRegistry;
import com.game.guild.dispatch.GuildDispatcher;
import com.game.guild.dispatch.GuildMethods;
import com.game.guild.rules.GuildTips;
import com.game.guild.service.GuildServiceFixture;
import com.game.proto.JianghuDuelBattleContext;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleOutcome;
import com.game.proto.guild.GetGuildJianghuGrudgeWallRequest;
import com.game.proto.guild.GetGuildJianghuGrudgeWallResponse;
import com.game.proto.match.MatchMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 帮派生死状恩怨墙（RPC 547）三表强一致持久化、锁序与成员资格回归。
 */
final class GuildJianghuGrudgeServiceTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private final GuildServiceFixture f = new GuildServiceFixture();

    @AfterEach
    void close() {
        f.close();
    }

    private static BattleResultEvent duelEvent(long battleId, long gidA, String nameA, long gidB, String nameB,
                                               eBattleOutcome outcome, int rounds, long finishedMs) {
        return BattleResultEvent.newBuilder()
                .setBattleId(battleId)
                .setMatchMode(MatchMode.MATCH_MODE_JIANGHU_DUEL_VALUE)
                .setOutcome(outcome)
                .setTotalRounds(rounds)
                .setFinishedAtMs(finishedMs)
                .setJianghuDuelContext(JianghuDuelBattleContext.newBuilder()
                        .setSideAGuildId(gidA)
                        .setSideAGuildName(nameA)
                        .setSideBGuildId(gidB)
                        .setSideBGuildName(nameB)
                        .setWritTier(2)
                        .setTeamSize(3)
                        .setChallengerCaptainName("剑圣")
                        .setTargetCaptainName("刀狂"))
                .build();
    }

    @Test
    void 派发器接线547校验本帮成员资格并返回恩怨墙() throws Exception {
        Map<Long, Long> playerGuilds = Map.of(1001L, 500L);
        GuildJianghuGrudgeService service = new GuildJianghuGrudgeService((pid, d) -> playerGuilds.getOrDefault(pid, 0L));
        assertThat(service.recordDuelResult(duelEvent(
                8001L, 500L, "青云盟", 600L, "血煞阁", eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 7, 1_700_000_000_000L)))
                .isTrue();

        GuildDispatcher dispatcher = new GuildDispatcher(
                REGISTRY, f.guilds, f.manage, f.rankService, f.economy, null, null, service,
                Runnable::run, f.metrics, 3500);
        int msgId = REGISTRY.requireId(GuildMethods.SERVICE, GuildMethods.GET_GUILD_JIANGHU_GRUDGE_WALL);

        // 非帮众返回 in-band 14002（绝不返回 1006 占位也不泄露他人帮会数据）
        ClientReply outsiderReply = dispatcher.dispatch(ClientCall.newBuilder()
                .setMessageId(msgId)
                .setBody(GetGuildJianghuGrudgeWallRequest.getDefaultInstance().toByteString())
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(1).setPlayerId(9999L))
                .build()).join();
        assertThat(outsiderReply.getTipId()).isZero();
        GetGuildJianghuGrudgeWallResponse outsider = GetGuildJianghuGrudgeWallResponse.parseFrom(outsiderReply.getBody());
        assertThat(outsider.getErrorMessage().getId()).isEqualTo(GuildTips.NOT_IN_GUILD);

        // 本帮成员拿到宿敌汇总与战绩
        ClientReply memberReply = dispatcher.dispatch(ClientCall.newBuilder()
                .setMessageId(msgId)
                .setBody(GetGuildJianghuGrudgeWallRequest.getDefaultInstance().toByteString())
                .setSession(SessionContext.newBuilder().setGateNodeId(1).setSessionId(2).setPlayerId(1001L))
                .build()).join();
        assertThat(memberReply.getTipId()).isZero();
        GetGuildJianghuGrudgeWallResponse wall = GetGuildJianghuGrudgeWallResponse.parseFrom(memberReply.getBody());
        assertThat(wall.getErrorMessage().getId()).isZero();
        assertThat(wall.getGuildId()).isEqualTo(500L);
        assertThat(wall.getRivalsCount()).isEqualTo(1);
        assertThat(wall.getRivals(0).getRivalGuildId()).isEqualTo(600L);
        assertThat(wall.getRivals(0).getWins()).isEqualTo(1);
        assertThat(wall.getRecentRecordsCount()).isEqualTo(1);
        assertThat(wall.getRecentRecords(0).getBattleId()).isEqualTo(8001L);
    }

    @Test
    void sql三表持久化严格升序锁序并由永不裁剪闸门防止20场裁掉后重复计分() throws Exception {
        FakeGrudgeDb db = new FakeGrudgeDb();
        GuildJianghuGrudgeService service = GuildJianghuGrudgeService.withDataSource(db.ds, (pid, d) -> 100L);
        service.ensureSchema();
        assertThat(db.ddlStatements).anySatisfy(sql -> assertThat(sql).contains("guild_jianghu_rival_summary"));
        assertThat(db.ddlStatements).anySatisfy(sql -> assertThat(sql).contains("guild_jianghu_grudge_record"));
        assertThat(db.ddlStatements).anySatisfy(sql -> assertThat(sql).contains("guild_jianghu_grudge_applied"));

        // 散人混编、同帮、VOID 与 0 回合局全部忽略
        assertThat(service.recordDuelResult(duelEvent(1L, 0L, "", 200L, "B", eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 5, 1000L))).isFalse();
        assertThat(service.recordDuelResult(duelEvent(2L, 100L, "A", 100L, "A", eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 5, 1000L))).isFalse();
        assertThat(service.recordDuelResult(duelEvent(3L, 100L, "A", 200L, "B", eBattleOutcome.BATTLE_OUTCOME_VOID, 5, 1000L))).isFalse();
        assertThat(service.recordDuelResult(duelEvent(4L, 100L, "A", 200L, "B", eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 0, 1000L))).isFalse();

        // 故意传入 sideA=200 > sideB=100，验证写入顺序强制按 guild_id 升序（100 先于 200）且先 Pass1 汇总再 Pass2 战绩再 Pass3 闸门
        assertThat(service.recordDuelResult(duelEvent(1001L, 200L, "大号帮", 100L, "小号帮",
                eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 6, 10_000L))).isTrue();
        assertThat(db.lastTxOps).containsExactly(
                "summary:100", "summary:200",
                "record:100", "record:200",
                "applied:1001");

        // 连续再打 21 场，把 1001 号战绩挤出每帮 20 条上限
        for (int i = 2; i <= 22; i++) {
            assertThat(service.recordDuelResult(duelEvent(1000L + i, 100L, "小号帮", 200L, "大号帮",
                    eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 4, 10_000L + i * 1000L))).isTrue();
        }
        GetGuildJianghuGrudgeWallResponse wall = service.getWall(100L);
        assertThat(wall.getRivalsCount()).isEqualTo(1);
        assertThat(wall.getRivals(0).getWins()).isEqualTo(21);
        assertThat(wall.getRivals(0).getLosses()).isEqualTo(1);
        assertThat(wall.getRecentRecordsCount()).isEqualTo(GuildJianghuGrudgeService.MAX_WALL_RECORDS);
        assertThat(wall.getRecentRecordsList()).noneMatch(r -> r.getBattleId() == 1001L);

        // 重投已被裁掉的 1001 号战报：guild_jianghu_grudge_applied 仍持有该 battle_id，必须幂等忽略、胜负不多算（r2-jh#3）
        assertThat(service.recordDuelResult(duelEvent(1001L, 200L, "大号帮", 100L, "小号帮",
                eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 6, 10_000L))).isFalse();
        GetGuildJianghuGrudgeWallResponse afterReplay = service.getWall(100L);
        assertThat(afterReplay.getRivals(0).getWins()).isEqualTo(21);
        assertThat(afterReplay.getRivals(0).getLosses()).isEqualTo(1);
    }

    /** 轻量模拟三张恩怨墙表，核验 SQL 语句顺序、裁剪与 guild_jianghu_grudge_applied 闸门。 */
    private static final class FakeGrudgeDb {
        final DataSource ds = mock(DataSource.class);
        final List<String> ddlStatements = new ArrayList<>();
        final List<String> lastTxOps = new ArrayList<>();
        final Set<Long> applied = new HashSet<>();
        final Map<String, SummaryRow> summaries = new HashMap<>();
        final Map<Long, List<RecRow>> records = new HashMap<>();

        static final class SummaryRow {
            final long guildId;
            final long rivalId;
            String rivalName;
            int wins;
            int losses;
            int draws;
            long lastBattleMs;

            SummaryRow(long guildId, long rivalId, String rivalName, int wins, int losses, int draws, long lastBattleMs) {
                this.guildId = guildId;
                this.rivalId = rivalId;
                this.rivalName = rivalName;
                this.wins = wins;
                this.losses = losses;
                this.draws = draws;
                this.lastBattleMs = lastBattleMs;
            }
        }

        record RecRow(long guildId, long battleId, long rivalId, String rivalName, int outcome,
                      int writTier, int teamSize, int totalRounds, String ourCap, String rivalCap, long finishedMs) {
        }

        FakeGrudgeDb() throws SQLException {
            when(ds.getConnection()).thenAnswer(inv -> newConnection());
        }

        private Connection newConnection() throws SQLException {
            Connection c = mock(Connection.class);
            when(c.getAutoCommit()).thenReturn(true);
            Statement st = mock(Statement.class);
            when(c.createStatement()).thenReturn(st);
            when(st.execute(anyString())).thenAnswer(inv -> {
                ddlStatements.add(inv.getArgument(0));
                return false;
            });
            doAnswer(inv -> {
                if (Boolean.FALSE.equals(inv.getArgument(0))) {
                    lastTxOps.clear();
                }
                return null;
            }).when(c).setAutoCommit(org.mockito.ArgumentMatchers.anyBoolean());
            when(c.prepareStatement(anyString())).thenAnswer(inv -> newPrepared(inv.getArgument(0)));
            return c;
        }

        private PreparedStatement newPrepared(String sql) throws SQLException {
            PreparedStatement ps = mock(PreparedStatement.class);
            Object[] params = new Object[16];
            doAnswer(inv -> {
                params[(int) inv.getArgument(0)] = inv.getArgument(1);
                return null;
            }).when(ps).setObject(anyInt(), org.mockito.ArgumentMatchers.any());
            doAnswer(inv -> {
                params[(int) inv.getArgument(0)] = inv.getArgument(1);
                return null;
            }).when(ps).setInt(anyInt(), anyInt());
            doAnswer(inv -> {
                params[(int) inv.getArgument(0)] = inv.getArgument(1);
                return null;
            }).when(ps).setString(anyInt(), anyString());

            when(ps.executeQuery()).thenAnswer(inv -> {
                if (sql.contains("FROM guild_jianghu_grudge_applied")) {
                    long bid = ((Number) params[1]).longValue();
                    return applied.contains(bid) ? rowsResultSet(List.<Object[]>of(new Object[] {bid})) : rowsResultSet(List.of());
                }
                if (sql.contains("SELECT battle_id FROM guild_jianghu_grudge_record")) {
                    long gid = ((Number) params[1]).longValue();
                    int offset = (int) params[2];
                    List<RecRow> sorted = sortedRecords(gid);
                    List<Object[]> stale = new ArrayList<>();
                    for (int i = offset; i < sorted.size(); i++) {
                        stale.add(new Object[] {sorted.get(i).battleId()});
                    }
                    return rowsResultSet(stale);
                }
                if (sql.contains("FROM guild_jianghu_rival_summary")) {
                    long gid = ((Number) params[1]).longValue();
                    int limit = (int) params[2];
                    List<Object[]> rows = summaries.values().stream()
                            .filter(r -> r.guildId == gid)
                            .sorted(Comparator.comparingLong((SummaryRow r) -> r.lastBattleMs).reversed()
                                    .thenComparingLong(r -> -r.rivalId))
                            .limit(limit)
                            .map(r -> new Object[] {r.rivalId, r.rivalName, r.wins, r.losses, r.draws, r.lastBattleMs})
                            .toList();
                    return rowsResultSet(rows);
                }
                if (sql.contains("FROM guild_jianghu_grudge_record")) {
                    long gid = ((Number) params[1]).longValue();
                    int limit = (int) params[2];
                    List<Object[]> rows = sortedRecords(gid).stream()
                            .limit(limit)
                            .map(r -> new Object[] {
                                    r.battleId(), r.rivalId(), r.rivalName(), r.outcome(),
                                    r.writTier(), r.teamSize(), r.totalRounds(),
                                    r.ourCap(), r.rivalCap(), r.finishedMs()})
                            .toList();
                    return rowsResultSet(rows);
                }
                throw new AssertionError("unexpected query: " + sql);
            });

            when(ps.executeUpdate()).thenAnswer(inv -> {
                if (sql.startsWith("UPDATE guild_jianghu_rival_summary")) {
                    long gid = ((Number) params[7]).longValue();
                    long rid = ((Number) params[8]).longValue();
                    SummaryRow row = summaries.get(gid + ":" + rid);
                    if (row == null) {
                        return 0;
                    }
                    lastTxOps.add("summary:" + gid);
                    row.rivalName = (String) params[1];
                    row.wins += (int) params[2];
                    row.losses += (int) params[3];
                    row.draws += (int) params[4];
                    long fin = ((Number) params[5]).longValue();
                    row.lastBattleMs = Math.max(row.lastBattleMs, fin);
                    return 1;
                }
                if (sql.startsWith("INSERT INTO guild_jianghu_rival_summary")) {
                    long gid = ((Number) params[1]).longValue();
                    long rid = ((Number) params[2]).longValue();
                    lastTxOps.add("summary:" + gid);
                    summaries.put(gid + ":" + rid, new SummaryRow(
                            gid, rid, (String) params[3], (int) params[4], (int) params[5], (int) params[6],
                            ((Number) params[7]).longValue()));
                    return 1;
                }
                if (sql.startsWith("INSERT INTO guild_jianghu_grudge_record")) {
                    long gid = ((Number) params[1]).longValue();
                    long bid = ((Number) params[2]).longValue();
                    lastTxOps.add("record:" + gid);
                    List<RecRow> list = records.computeIfAbsent(gid, k -> new ArrayList<>());
                    if (list.stream().anyMatch(r -> r.battleId() == bid)) {
                        throw new SQLException("Duplicate entry", "23000", 1062);
                    }
                    list.add(new RecRow(
                            gid, bid, ((Number) params[3]).longValue(), (String) params[4], (int) params[5],
                            (int) params[6], (int) params[7], (int) params[8], (String) params[9], (String) params[10],
                            ((Number) params[11]).longValue()));
                    return 1;
                }
                if (sql.startsWith("DELETE FROM guild_jianghu_grudge_record")) {
                    long gid = ((Number) params[1]).longValue();
                    long bid = ((Number) params[2]).longValue();
                    records.getOrDefault(gid, List.of()).removeIf(r -> r.battleId() == bid);
                    return 1;
                }
                if (sql.startsWith("INSERT INTO guild_jianghu_grudge_applied")) {
                    long bid = ((Number) params[1]).longValue();
                    lastTxOps.add("applied:" + bid);
                    if (!applied.add(bid)) {
                        throw new SQLException("Duplicate entry", "23000", 1062);
                    }
                    return 1;
                }
                throw new AssertionError("unexpected update: " + sql);
            });
            return ps;
        }

        private List<RecRow> sortedRecords(long gid) {
            return records.getOrDefault(gid, List.of()).stream()
                    .sorted(Comparator.comparingLong(RecRow::finishedMs).reversed()
                            .thenComparingLong(r -> -r.battleId()))
                    .toList();
        }

        private static ResultSet rowsResultSet(List<Object[]> rows) throws SQLException {
            ResultSet rs = mock(ResultSet.class);
            int[] idx = {-1};
            when(rs.next()).thenAnswer(inv -> ++idx[0] < rows.size());
            when(rs.getObject(anyInt())).thenAnswer(inv -> rows.get(idx[0])[(int) inv.getArgument(0) - 1]);
            when(rs.getInt(anyInt())).thenAnswer(inv -> ((Number) rows.get(idx[0])[(int) inv.getArgument(0) - 1]).intValue());
            when(rs.getString(anyInt())).thenAnswer(inv -> (String) rows.get(idx[0])[(int) inv.getArgument(0) - 1]);
            return rs;
        }
    }
}
