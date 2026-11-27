package com.game.guild.jianghu;

import com.game.common.deadline.Deadline;
import com.game.guild.rules.GuildTip;
import com.game.proto.JianghuDuelBattleContext;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleOutcome;
import com.game.proto.guild.GetGuildJianghuGrudgeWallResponse;
import com.game.proto.guild.GuildJianghuGrudgeRecordView;
import com.game.proto.guild.GuildJianghuRivalSummaryView;
import com.game.proto.match.MatchMode;
import java.math.BigInteger;
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
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;

/**
 * 帮派生死状恩怨墙领域服务（对齐 Go {@code go/guild/internal/data/jianghu_grudge_repo.go} 与
 * {@code go/guild/internal/logic/jianghu_grudge_logic.go}）。
 *
 * <p>仅记录两支不同帮会纯同帮小队正常完赛（{@code total_rounds > 0 && outcome != VOID}）的天墉生死状对局，
 * 生产模式使用三表强一致 SQL 落库（表锁序 20 {@code guild_jianghu_rival_summary} → 21 {@code guild_jianghu_grudge_record}
 * → 23 {@code guild_jianghu_grudge_applied}，按 {@code guild_id} 升序防交叉死锁），幂等去重唯一依赖永不裁剪的
 * {@code guild_jianghu_grudge_applied}（r2-jh#3），每帮保留最近 20 条战绩，且仅本帮成员可读。
 */
public final class GuildJianghuGrudgeService {

    public static final int MAX_WALL_RECORDS = 20;

    /** 查询操作者当前在册帮会 ID（0 表示不在任何帮会）。 */
    @FunctionalInterface
    public interface MemberGuildLookup {
        long playerGuildId(long playerId, Deadline deadline);
    }

    private record RivalKey(long guildId, long rivalGuildId) {
    }

    private record GuildSide(
            long guildId,
            long rivalId,
            String rivalName,
            int outcome,
            String ourCaptain,
            String rivalCaptain) {
    }

    private static final class RivalSummary {
        final long rivalGuildId;
        String rivalGuildName;
        int wins;
        int losses;
        int draws;
        long lastBattleMs;

        RivalSummary(long rivalGuildId, String rivalGuildName) {
            this.rivalGuildId = rivalGuildId;
            this.rivalGuildName = rivalGuildName;
        }
    }

    private final DataSource dataSource;
    private final MemberGuildLookup memberLookup;

    // 内存态仅用于无 DataSource 的纯单测构造
    private final Object lock = new Object();
    private final Set<Long> appliedBattles = new HashSet<>();
    private final Map<RivalKey, RivalSummary> rivals = new HashMap<>();
    private final Map<Long, List<GuildJianghuGrudgeRecordView>> recordsByGuild = new HashMap<>();

    /** 纯内存实例（仅单测使用）。 */
    public GuildJianghuGrudgeService() {
        this(null, null);
    }

    /** 带成员校验的内存实例（单测用）。 */
    public GuildJianghuGrudgeService(MemberGuildLookup memberLookup) {
        this(null, memberLookup);
    }

    private GuildJianghuGrudgeService(DataSource dataSource, MemberGuildLookup memberLookup) {
        this.dataSource = dataSource;
        this.memberLookup = memberLookup;
    }

    /** 生产装配入口：权威 SQL 持久化 + 帮会成员资格核验。 */
    public static GuildJianghuGrudgeService withDataSource(DataSource dataSource, MemberGuildLookup memberLookup) {
        return new GuildJianghuGrudgeService(
                Objects.requireNonNull(dataSource, "帮派恩怨墙数据源未装配"),
                Objects.requireNonNull(memberLookup, "帮派恩怨墙成员查询未装配"));
    }

    /** 幂等建表（锁序位置 20 / 21 / 23，与 Go proto/guild/guild_db.proto 列名主键完全一致）。 */
    public void ensureSchema() throws SQLException {
        if (dataSource == null) {
            return;
        }
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_jianghu_rival_summary (
                      guild_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
                      rival_guild_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
                      rival_guild_name VARCHAR(128) NOT NULL DEFAULT '',
                      wins INT UNSIGNED NOT NULL DEFAULT 0,
                      losses INT UNSIGNED NOT NULL DEFAULT 0,
                      draws INT UNSIGNED NOT NULL DEFAULT 0,
                      last_battle_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
                      PRIMARY KEY (guild_id, rival_guild_id)
                    )
                    """);
            try {
                st.execute("CREATE INDEX idx_guild_jianghu_rival_summary_0 ON guild_jianghu_rival_summary (guild_id, last_battle_ms)");
            } catch (SQLException ignored) {
                // 索引已存在时忽略
            }
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_jianghu_grudge_record (
                      guild_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
                      battle_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
                      rival_guild_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
                      rival_guild_name VARCHAR(128) NOT NULL DEFAULT '',
                      outcome_for_guild INT UNSIGNED NOT NULL DEFAULT 0,
                      writ_tier INT UNSIGNED NOT NULL DEFAULT 0,
                      team_size INT UNSIGNED NOT NULL DEFAULT 0,
                      total_rounds INT UNSIGNED NOT NULL DEFAULT 0,
                      our_captain_name VARCHAR(128) NOT NULL DEFAULT '',
                      rival_captain_name VARCHAR(128) NOT NULL DEFAULT '',
                      finished_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
                      PRIMARY KEY (guild_id, battle_id)
                    )
                    """);
            try {
                st.execute("CREATE INDEX idx_guild_jianghu_grudge_record_0 ON guild_jianghu_grudge_record (guild_id, finished_ms)");
            } catch (SQLException ignored) {
                // 索引已存在时忽略
            }
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_jianghu_grudge_applied (
                      battle_id BIGINT UNSIGNED NOT NULL DEFAULT 0,
                      applied_ms BIGINT UNSIGNED NOT NULL DEFAULT 0,
                      PRIMARY KEY (battle_id)
                    )
                    """);
        }
    }

    /** 幂等入账两帮纯同帮生死状战果；重复 battle_id、散人混编、同帮切磋或 VOID/0 回合局均忽略并返回 false。 */
    public boolean recordDuelResult(BattleResultEvent ev) {
        if (ev == null || ev.getBattleId() == 0L
                || ev.getMatchMode() != MatchMode.MATCH_MODE_JIANGHU_DUEL_VALUE
                || ev.getTotalRounds() == 0
                || ev.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_VOID
                || !ev.hasJianghuDuelContext()) {
            return false;
        }
        JianghuDuelBattleContext duel = ev.getJianghuDuelContext();
        long gidA = duel.getSideAGuildId();
        long gidB = duel.getSideBGuildId();
        if (gidA == 0L || gidB == 0L || gidA == gidB) {
            return false;
        }
        int outA;
        int outB;
        switch (ev.getOutcome()) {
            case BATTLE_OUTCOME_SIDE_A_WIN -> {
                outA = 1;
                outB = 2;
            }
            case BATTLE_OUTCOME_SIDE_B_WIN -> {
                outA = 2;
                outB = 1;
            }
            case BATTLE_OUTCOME_DRAW -> {
                outA = 3;
                outB = 3;
            }
            default -> {
                return false;
            }
        }
        long finishedMs = ev.getFinishedAtMs() != 0L ? ev.getFinishedAtMs() : ev.getBattleId();
        GuildSide first = new GuildSide(gidA, gidB, nonNull(duel.getSideBGuildName()), outA,
                nonNull(duel.getChallengerCaptainName()), nonNull(duel.getTargetCaptainName()));
        GuildSide second = new GuildSide(gidB, gidA, nonNull(duel.getSideAGuildName()), outB,
                nonNull(duel.getTargetCaptainName()), nonNull(duel.getChallengerCaptainName()));
        // 按无符号 guild_id 升序排列，保证双帮并发结算行锁全序不死锁。
        GuildSide[] sides = Long.compareUnsigned(first.guildId(), second.guildId()) <= 0
                ? new GuildSide[] {first, second}
                : new GuildSide[] {second, first};

        if (dataSource != null) {
            try {
                return recordDuelResultSql(ev, duel, finishedMs, sides);
            } catch (SQLException e) {
                throw new IllegalStateException("帮派恩怨墙落库失败 battleId=" + Long.toUnsignedString(ev.getBattleId()), e);
            }
        }
        synchronized (lock) {
            if (!appliedBattles.add(ev.getBattleId())) {
                return false;
            }
            for (GuildSide s : sides) {
                applySideInMemoryLocked(s, ev, duel, finishedMs);
            }
            return true;
        }
    }

    /**
     * C2S 入口（RPC 547 {@code GetGuildJianghuGrudgeWall}）：严格校验调用者必须为本帮在册成员，非帮众返回 in-band 14002。
     */
    public GetGuildJianghuGrudgeWallResponse getWallForPlayer(long playerId, Deadline deadline) {
        if (memberLookup == null) {
            return GetGuildJianghuGrudgeWallResponse.newBuilder()
                    .setErrorMessage(GuildTip.NOT_IN_ANY_GUILD.proto())
                    .build();
        }
        long guildId = memberLookup.playerGuildId(playerId, deadline);
        if (guildId == 0L) {
            return GetGuildJianghuGrudgeWallResponse.newBuilder()
                    .setErrorMessage(GuildTip.NOT_IN_ANY_GUILD.proto())
                    .build();
        }
        return getWall(guildId);
    }

    public GetGuildJianghuGrudgeWallResponse getWall(long guildId) {
        GetGuildJianghuGrudgeWallResponse.Builder resp = GetGuildJianghuGrudgeWallResponse.newBuilder()
                .setGuildId(guildId);
        if (guildId == 0L) {
            return resp.build();
        }
        if (dataSource != null) {
            try {
                return loadWallSql(guildId);
            } catch (SQLException e) {
                throw new IllegalStateException("读取帮派恩怨墙失败 guildId=" + Long.toUnsignedString(guildId), e);
            }
        }
        synchronized (lock) {
            List<RivalSummary> guildRivals = new ArrayList<>();
            for (Map.Entry<RivalKey, RivalSummary> e : rivals.entrySet()) {
                if (e.getKey().guildId() == guildId) {
                    guildRivals.add(e.getValue());
                }
            }
            guildRivals.sort(Comparator
                    .comparingLong((RivalSummary r) -> r.lastBattleMs).reversed()
                    .thenComparing((a, b) -> Long.compareUnsigned(b.rivalGuildId, a.rivalGuildId)));
            int limit = Math.min(MAX_WALL_RECORDS, guildRivals.size());
            for (int i = 0; i < limit; i++) {
                RivalSummary r = guildRivals.get(i);
                resp.addRivals(GuildJianghuRivalSummaryView.newBuilder()
                        .setRivalGuildId(r.rivalGuildId)
                        .setRivalGuildName(nonNull(r.rivalGuildName))
                        .setWins(r.wins)
                        .setLosses(r.losses)
                        .setDraws(r.draws)
                        .setLastBattleMs(r.lastBattleMs)
                        .build());
            }
            List<GuildJianghuGrudgeRecordView> recs = recordsByGuild.getOrDefault(guildId, List.of());
            resp.addAllRecentRecords(recs);
        }
        return resp.build();
    }

    private boolean recordDuelResultSql(BattleResultEvent ev, JianghuDuelBattleContext duel,
                                        long finishedMs, GuildSide[] sides) throws SQLException {
        Object battleObj = toUnsigned(ev.getBattleId());
        Object finishedObj = toUnsigned(finishedMs);
        try (Connection c = dataSource.getConnection()) {
            boolean prevAuto = c.getAutoCommit();
            int prevIso = c.getTransactionIsolation();
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            boolean committed = false;
            try {
                // 非加锁幂等预检：已入过账直接返回，不空锁两帮汇总行。
                try (PreparedStatement check = c.prepareStatement(
                        "SELECT battle_id FROM guild_jianghu_grudge_applied WHERE battle_id=?")) {
                    check.setObject(1, battleObj);
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            return false;
                        }
                    }
                }

                // Pass 1（位置 20）：按 guild_id 升序全部更新/插入 guild_jianghu_rival_summary。
                for (GuildSide s : sides) {
                    upsertRivalSummaryTx(c, s, finishedObj);
                }

                // Pass 2（位置 21）：按 guild_id 升序全部写入 guild_jianghu_grudge_record 并裁剪至最近 20 场。
                for (GuildSide s : sides) {
                    try (PreparedStatement ins = c.prepareStatement(
                            "INSERT INTO guild_jianghu_grudge_record("
                                    + "guild_id,battle_id,rival_guild_id,rival_guild_name,outcome_for_guild,"
                                    + "writ_tier,team_size,total_rounds,our_captain_name,rival_captain_name,finished_ms) "
                                    + "VALUES(?,?,?,?,?,?,?,?,?,?,?)")) {
                        ins.setObject(1, toUnsigned(s.guildId()));
                        ins.setObject(2, battleObj);
                        ins.setObject(3, toUnsigned(s.rivalId()));
                        ins.setString(4, s.rivalName());
                        ins.setInt(5, s.outcome());
                        ins.setInt(6, duel.getWritTier());
                        ins.setInt(7, duel.getTeamSize());
                        ins.setInt(8, ev.getTotalRounds());
                        ins.setString(9, s.ourCaptain());
                        ins.setString(10, s.rivalCaptain());
                        ins.setObject(11, finishedObj);
                        ins.executeUpdate();
                    } catch (SQLException dup) {
                        if (isDuplicateKey(dup)) {
                            return false;
                        }
                        throw dup;
                    }
                    trimGuildRecordsTx(c, s.guildId());
                }

                // Pass 3（位置 23）：写永不裁剪的入账闸门 guild_jianghu_grudge_applied（r2-jh#3 唯一去重键）。
                try (PreparedStatement gate = c.prepareStatement(
                        "INSERT INTO guild_jianghu_grudge_applied(battle_id,applied_ms) VALUES(?,?)")) {
                    gate.setObject(1, battleObj);
                    gate.setObject(2, toUnsigned(System.currentTimeMillis()));
                    gate.executeUpdate();
                } catch (SQLException dup) {
                    if (isDuplicateKey(dup)) {
                        return false;
                    }
                    throw dup;
                }

                c.commit();
                committed = true;
                return true;
            } finally {
                if (!committed) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                    }
                }
                try {
                    c.setTransactionIsolation(prevIso);
                    c.setAutoCommit(prevAuto);
                } catch (SQLException ignored) {
                }
            }
        }
    }

    private static void upsertRivalSummaryTx(Connection c, GuildSide s, Object finishedObj) throws SQLException {
        int winInc = s.outcome() == 1 ? 1 : 0;
        int lossInc = s.outcome() == 2 ? 1 : 0;
        int drawInc = s.outcome() == 3 ? 1 : 0;
        Object guildObj = toUnsigned(s.guildId());
        Object rivalObj = toUnsigned(s.rivalId());
        if (updateRivalSummaryTx(c, guildObj, rivalObj, s.rivalName(), winInc, lossInc, drawInc, finishedObj) > 0) {
            return;
        }
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO guild_jianghu_rival_summary("
                        + "guild_id,rival_guild_id,rival_guild_name,wins,losses,draws,last_battle_ms) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            ins.setObject(1, guildObj);
            ins.setObject(2, rivalObj);
            ins.setString(3, s.rivalName());
            ins.setInt(4, winInc);
            ins.setInt(5, lossInc);
            ins.setInt(6, drawInc);
            ins.setObject(7, finishedObj);
            ins.executeUpdate();
        } catch (SQLException dup) {
            if (!isDuplicateKey(dup)) {
                throw dup;
            }
            // 并发首插撞 1062：回退执行原子累加 UPDATE，不丢失本次胜负。
            updateRivalSummaryTx(c, guildObj, rivalObj, s.rivalName(), winInc, lossInc, drawInc, finishedObj);
        }
    }

    private static int updateRivalSummaryTx(Connection c, Object guildObj, Object rivalObj, String rivalName,
                                            int winInc, int lossInc, int drawInc, Object finishedObj) throws SQLException {
        try (PreparedStatement upd = c.prepareStatement(
                "UPDATE guild_jianghu_rival_summary "
                        + "SET rival_guild_name=?, wins=wins+?, losses=losses+?, draws=draws+?, "
                        + "last_battle_ms=CASE WHEN last_battle_ms>? THEN last_battle_ms ELSE ? END "
                        + "WHERE guild_id=? AND rival_guild_id=?")) {
            upd.setString(1, rivalName);
            upd.setInt(2, winInc);
            upd.setInt(3, lossInc);
            upd.setInt(4, drawInc);
            upd.setObject(5, finishedObj);
            upd.setObject(6, finishedObj);
            upd.setObject(7, guildObj);
            upd.setObject(8, rivalObj);
            return upd.executeUpdate();
        }
    }

    private static void trimGuildRecordsTx(Connection c, long guildId) throws SQLException {
        Object guildObj = toUnsigned(guildId);
        List<Long> stale = new ArrayList<>();
        try (PreparedStatement sel = c.prepareStatement(
                "SELECT battle_id FROM guild_jianghu_grudge_record "
                        + "WHERE guild_id=? ORDER BY finished_ms DESC, battle_id DESC LIMIT 100 OFFSET ?")) {
            sel.setObject(1, guildObj);
            sel.setInt(2, MAX_WALL_RECORDS);
            try (ResultSet rs = sel.executeQuery()) {
                while (rs.next()) {
                    stale.add(readUnsignedLong(rs.getObject(1)));
                }
            }
        }
        for (long bid : stale) {
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM guild_jianghu_grudge_record WHERE guild_id=? AND battle_id=?")) {
                del.setObject(1, guildObj);
                del.setObject(2, toUnsigned(bid));
                del.executeUpdate();
            }
        }
    }

    private GetGuildJianghuGrudgeWallResponse loadWallSql(long guildId) throws SQLException {
        GetGuildJianghuGrudgeWallResponse.Builder resp = GetGuildJianghuGrudgeWallResponse.newBuilder()
                .setGuildId(guildId);
        Object guildObj = toUnsigned(guildId);
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT rival_guild_id,rival_guild_name,wins,losses,draws,last_battle_ms "
                            + "FROM guild_jianghu_rival_summary "
                            + "WHERE guild_id=? ORDER BY last_battle_ms DESC, rival_guild_id DESC LIMIT ?")) {
                ps.setObject(1, guildObj);
                ps.setInt(2, MAX_WALL_RECORDS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        resp.addRivals(GuildJianghuRivalSummaryView.newBuilder()
                                .setRivalGuildId(readUnsignedLong(rs.getObject(1)))
                                .setRivalGuildName(nonNull(rs.getString(2)))
                                .setWins(rs.getInt(3))
                                .setLosses(rs.getInt(4))
                                .setDraws(rs.getInt(5))
                                .setLastBattleMs(readUnsignedLong(rs.getObject(6)))
                                .build());
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT battle_id,rival_guild_id,rival_guild_name,outcome_for_guild,writ_tier,"
                            + "team_size,total_rounds,our_captain_name,rival_captain_name,finished_ms "
                            + "FROM guild_jianghu_grudge_record "
                            + "WHERE guild_id=? ORDER BY finished_ms DESC, battle_id DESC LIMIT ?")) {
                ps.setObject(1, guildObj);
                ps.setInt(2, MAX_WALL_RECORDS);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        resp.addRecentRecords(GuildJianghuGrudgeRecordView.newBuilder()
                                .setBattleId(readUnsignedLong(rs.getObject(1)))
                                .setRivalGuildId(readUnsignedLong(rs.getObject(2)))
                                .setRivalGuildName(nonNull(rs.getString(3)))
                                .setOutcomeForGuild(rs.getInt(4))
                                .setWritTier(rs.getInt(5))
                                .setTeamSize(rs.getInt(6))
                                .setTotalRounds(rs.getInt(7))
                                .setOurCaptainName(nonNull(rs.getString(8)))
                                .setRivalCaptainName(nonNull(rs.getString(9)))
                                .setFinishedMs(readUnsignedLong(rs.getObject(10)))
                                .build());
                    }
                }
            }
        }
        return resp.build();
    }

    private void applySideInMemoryLocked(GuildSide s, BattleResultEvent ev,
                                         JianghuDuelBattleContext duel, long finishedMs) {
        RivalSummary summary = rivals.computeIfAbsent(
                new RivalKey(s.guildId(), s.rivalId()), k -> new RivalSummary(s.rivalId(), s.rivalName()));
        summary.rivalGuildName = s.rivalName();
        summary.lastBattleMs = Math.max(summary.lastBattleMs, finishedMs);
        switch (s.outcome()) {
            case 1 -> summary.wins++;
            case 2 -> summary.losses++;
            case 3 -> summary.draws++;
            default -> { }
        }
        List<GuildJianghuGrudgeRecordView> list = recordsByGuild.computeIfAbsent(s.guildId(), k -> new ArrayList<>());
        list.add(GuildJianghuGrudgeRecordView.newBuilder()
                .setBattleId(ev.getBattleId())
                .setRivalGuildId(s.rivalId())
                .setRivalGuildName(s.rivalName())
                .setOutcomeForGuild(s.outcome())
                .setWritTier(duel.getWritTier())
                .setTeamSize(duel.getTeamSize())
                .setTotalRounds(ev.getTotalRounds())
                .setOurCaptainName(s.ourCaptain())
                .setRivalCaptainName(s.rivalCaptain())
                .setFinishedMs(finishedMs)
                .build());
        list.sort(Comparator
                .comparingLong(GuildJianghuGrudgeRecordView::getFinishedMs).reversed()
                .thenComparing((a, b) -> Long.compareUnsigned(b.getBattleId(), a.getBattleId())));
        if (list.size() > MAX_WALL_RECORDS) {
            list.subList(MAX_WALL_RECORDS, list.size()).clear();
        }
    }

    private static boolean isDuplicateKey(SQLException e) {
        if (e.getErrorCode() == 1062) {
            return true;
        }
        String state = e.getSQLState();
        return "23000".equals(state) || "23505".equals(state);
    }

    private static Object toUnsigned(long v) {
        return v < 0L ? new BigInteger(Long.toUnsignedString(v)) : v;
    }

    private static long readUnsignedLong(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof BigInteger bi) {
            return bi.longValue();
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        return Long.parseUnsignedLong(raw.toString().trim());
    }

    private static String nonNull(String s) {
        return s == null ? "" : s;
    }
}
