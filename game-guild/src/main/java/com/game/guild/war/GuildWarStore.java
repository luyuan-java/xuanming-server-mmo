package com.game.guild.war;

import com.game.proto.guild.GuildWarBountyTier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * 帮会全天帮战 V2 七表 JDBC 持久化仓储（同时兼容 MySQL 8 与 H2 单测，严格沿用全库表间锁序 13..19 顺延，不回头锁旧表）。
 */
public class GuildWarStore {

    public record LeylineRow(int zoneId, int leylineId, long ownerGuildId, int bannerHp, long shieldUntilMs,
                             long capturedAtMs, long lastDividendAtMs, int dividendWeekKey, int weeklyDividendCount,
                             long lastSiegeAlertMs, long updatedMs) {
    }

    public record GarrisonRow(long guildId, int slotIndex, long playerId, String playerName, int level,
                              String appearanceId, String petName, int petTableId, byte[] snapshotPayload,
                              long updatedMs) {
    }

    public record WeeklyProgressRow(long guildId, int weekKey, long guildMerit, int dividendPoolCount,
                                    long mvp1PlayerId, long mvp1Merit, long mvp2PlayerId, long mvp2Merit,
                                    String firstWinNamesJson, long firstWinAtMs, long updatedMs) {
    }

    public record PlayerStateRow(long playerId, int zoneId, int peaceMode, long peaceReadyAtMs, int openChallenge,
                                 long protectUntilMs, int streakCount, int streakDistinctCount,
                                 long lastStreakOpponentId, int weekKey, long weeklyMerit, int milestoneClaimedMask,
                                 int dividendClaimedCount, long bountyImmunityUntilMs, int bountyPayoutDayKey,
                                 int bountyPayoutCountToday, long nextGarrisonRefreshMs, long nextTrackAtMs,
                                 long updatedMs) {
    }

    public record BountyRow(long bountyId, int zoneId, long issuerPlayerId, long issuerGuildId, long targetPlayerId,
                            long targetGuildId, int tier, long escrowSilver, long taxSilver, int publicBoard,
                            int status, int counterKills, String counterKillersJson, long escrowOpId,
                            long createdAtMs, long expiresAtMs, long resolvedAtMs, long winnerPlayerId) {
    }

    public record BattleRow(long battleId, int zoneId, int warBattleKind, int leylineId, long bountyId,
                            long attackerGuildId, long defenderGuildId, int outcome, int totalRounds,
                            int bannerHpBefore, int bannerHpAfter, int bannerShielded, int bountyOutcomeTag,
                            long jackpotContribution, long jackpotSilver, int diminishPercent,
                            long attackerContribution, long attackerSilver, long attackerFunds, long attackerMerit,
                            long defenderContribution, long defenderSilver, long defenderFunds, long defenderMerit,
                            byte[] summaryPayload, long finishedAtMs) {
    }

    private final DataSource dataSource;

    public GuildWarStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    /** 仅测试替身子类使用。 */
    protected GuildWarStore() {
        this.dataSource = null;
    }

    /** 幂等建表（MySQL / H2 通用 DDL，顺序即表间锁序 13..19）。 */
    public void ensureSchema() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_war_leyline (
                      zone_id INT NOT NULL,
                      leyline_id INT NOT NULL,
                      owner_guild_id BIGINT NOT NULL DEFAULT 0,
                      banner_hp INT NOT NULL DEFAULT 600,
                      shield_until_ms BIGINT NOT NULL DEFAULT 0,
                      captured_at_ms BIGINT NOT NULL DEFAULT 0,
                      last_dividend_at_ms BIGINT NOT NULL DEFAULT 0,
                      dividend_week_key INT NOT NULL DEFAULT 0,
                      weekly_dividend_count INT NOT NULL DEFAULT 0,
                      last_siege_alert_ms BIGINT NOT NULL DEFAULT 0,
                      updated_ms BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (zone_id, leyline_id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_war_garrison (
                      guild_id BIGINT NOT NULL,
                      slot_index INT NOT NULL,
                      player_id BIGINT NOT NULL,
                      player_name VARCHAR(64) NOT NULL DEFAULT '',
                      level INT NOT NULL DEFAULT 50,
                      appearance_id VARCHAR(64) NOT NULL DEFAULT '',
                      pet_name VARCHAR(64) NOT NULL DEFAULT '',
                      pet_table_id INT NOT NULL DEFAULT 0,
                      snapshot_payload BLOB,
                      updated_ms BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (guild_id, slot_index)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_war_weekly_progress (
                      guild_id BIGINT NOT NULL,
                      week_key INT NOT NULL,
                      guild_merit BIGINT NOT NULL DEFAULT 0,
                      dividend_pool_count INT NOT NULL DEFAULT 0,
                      mvp1_player_id BIGINT NOT NULL DEFAULT 0,
                      mvp1_merit BIGINT NOT NULL DEFAULT 0,
                      mvp2_player_id BIGINT NOT NULL DEFAULT 0,
                      mvp2_merit BIGINT NOT NULL DEFAULT 0,
                      first_win_names_json VARCHAR(512) NOT NULL DEFAULT '',
                      first_win_at_ms BIGINT NOT NULL DEFAULT 0,
                      updated_ms BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (guild_id, week_key)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_war_player_state (
                      player_id BIGINT NOT NULL,
                      zone_id INT NOT NULL DEFAULT 0,
                      peace_mode INT NOT NULL DEFAULT 0,
                      peace_ready_at_ms BIGINT NOT NULL DEFAULT 0,
                      open_challenge INT NOT NULL DEFAULT 1,
                      protect_until_ms BIGINT NOT NULL DEFAULT 0,
                      streak_count INT NOT NULL DEFAULT 0,
                      streak_distinct_count INT NOT NULL DEFAULT 0,
                      last_streak_opponent_id BIGINT NOT NULL DEFAULT 0,
                      week_key INT NOT NULL DEFAULT 0,
                      weekly_merit BIGINT NOT NULL DEFAULT 0,
                      milestone_claimed_mask INT NOT NULL DEFAULT 0,
                      dividend_claimed_count INT NOT NULL DEFAULT 0,
                      bounty_immunity_until_ms BIGINT NOT NULL DEFAULT 0,
                      bounty_payout_day_key INT NOT NULL DEFAULT 0,
                      bounty_payout_count_today INT NOT NULL DEFAULT 0,
                      next_garrison_refresh_ms BIGINT NOT NULL DEFAULT 0,
                      next_track_at_ms BIGINT NOT NULL DEFAULT 0,
                      updated_ms BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (player_id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_war_bounty (
                      bounty_id BIGINT NOT NULL,
                      zone_id INT NOT NULL,
                      issuer_player_id BIGINT NOT NULL,
                      issuer_guild_id BIGINT NOT NULL,
                      target_player_id BIGINT NOT NULL,
                      target_guild_id BIGINT NOT NULL,
                      tier INT NOT NULL,
                      escrow_silver BIGINT NOT NULL DEFAULT 0,
                      tax_silver BIGINT NOT NULL DEFAULT 0,
                      public_board INT NOT NULL DEFAULT 0,
                      status INT NOT NULL DEFAULT 2,
                      counter_kills INT NOT NULL DEFAULT 0,
                      counter_killers_json VARCHAR(512) NOT NULL DEFAULT '',
                      escrow_op_id BIGINT NOT NULL DEFAULT 0,
                      created_at_ms BIGINT NOT NULL DEFAULT 0,
                      expires_at_ms BIGINT NOT NULL DEFAULT 0,
                      resolved_at_ms BIGINT NOT NULL DEFAULT 0,
                      winner_player_id BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (bounty_id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_war_battle (
                      battle_id BIGINT NOT NULL,
                      zone_id INT NOT NULL,
                      war_battle_kind INT NOT NULL,
                      leyline_id INT NOT NULL DEFAULT 0,
                      bounty_id BIGINT NOT NULL DEFAULT 0,
                      attacker_guild_id BIGINT NOT NULL,
                      defender_guild_id BIGINT NOT NULL DEFAULT 0,
                      outcome INT NOT NULL,
                      total_rounds INT NOT NULL DEFAULT 0,
                      banner_hp_before INT NOT NULL DEFAULT 0,
                      banner_hp_after INT NOT NULL DEFAULT 0,
                      banner_shielded INT NOT NULL DEFAULT 0,
                      bounty_outcome_tag INT NOT NULL DEFAULT 0,
                      jackpot_contribution BIGINT NOT NULL DEFAULT 0,
                      jackpot_silver BIGINT NOT NULL DEFAULT 0,
                      diminish_percent INT NOT NULL DEFAULT 100,
                      attacker_contribution BIGINT NOT NULL DEFAULT 0,
                      attacker_silver BIGINT NOT NULL DEFAULT 0,
                      attacker_funds BIGINT NOT NULL DEFAULT 0,
                      attacker_merit BIGINT NOT NULL DEFAULT 0,
                      defender_contribution BIGINT NOT NULL DEFAULT 0,
                      defender_silver BIGINT NOT NULL DEFAULT 0,
                      defender_funds BIGINT NOT NULL DEFAULT 0,
                      defender_merit BIGINT NOT NULL DEFAULT 0,
                      summary_payload BLOB,
                      finished_at_ms BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (battle_id)
                    )
                    """);
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_war_rivalry (
                      guild_a_id BIGINT NOT NULL,
                      guild_b_id BIGINT NOT NULL,
                      week_key INT NOT NULL,
                      clash_count INT NOT NULL DEFAULT 0,
                      mutual_bounty INT NOT NULL DEFAULT 0,
                      updated_ms BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (guild_a_id, guild_b_id, week_key)
                    )
                    """);
        }
    }

    public void ensureZoneLeylines(int zoneId, long nowMs) throws SQLException {
        if (zoneId == 0) {
            return;
        }
        try (Connection c = dataSource.getConnection()) {
            for (GuildWarRules.LeylineMeta meta : GuildWarRules.ALL_LEYLINES) {
                try (PreparedStatement check = c.prepareStatement(
                        "SELECT 1 FROM guild_war_leyline WHERE zone_id = ? AND leyline_id = ?")) {
                    check.setInt(1, zoneId);
                    check.setInt(2, meta.id());
                    try (ResultSet rs = check.executeQuery()) {
                        if (rs.next()) {
                            continue;
                        }
                    }
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO guild_war_leyline (zone_id, leyline_id, owner_guild_id, banner_hp, shield_until_ms, captured_at_ms, last_dividend_at_ms, dividend_week_key, weekly_dividend_count, last_siege_alert_ms, updated_ms) VALUES (?, ?, 0, ?, 0, 0, ?, 0, 0, 0, ?)")) {
                    ins.setInt(1, zoneId);
                    ins.setInt(2, meta.id());
                    ins.setInt(3, GuildWarRules.DEFAULT_LEYLINE_CAPTURE_INIT_HP);
                    ins.setLong(4, nowMs);
                    ins.setLong(5, nowMs);
                    ins.executeUpdate();
                } catch (SQLException ignoredDuplicate) {
                    // 并发首建时已存在则忽略
                }
            }
        }
    }

    public List<LeylineRow> listLeylines(int zoneId) throws SQLException {
        List<LeylineRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT zone_id, leyline_id, owner_guild_id, banner_hp, shield_until_ms, captured_at_ms, last_dividend_at_ms, dividend_week_key, weekly_dividend_count, last_siege_alert_ms, updated_ms FROM guild_war_leyline WHERE zone_id = ? ORDER BY leyline_id ASC")) {
            ps.setInt(1, zoneId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new LeylineRow(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getInt(4),
                            rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getInt(8),
                            rs.getInt(9), rs.getLong(10), rs.getLong(11)));
                }
            }
        }
        return out;
    }

    public PlayerStateRow getOrDefaultPlayerState(long playerId, int zoneId, int weekKey) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return readPlayerStateTx(c, playerId, zoneId, weekKey, false);
        }
    }

    /** 在既有事务连接上加行锁读取玩家帮战状态（不存在则同连接插入默认行再加 FOR UPDATE，杜绝嵌套借连接死锁与并发覆盖）。 */
    private PlayerStateRow lockOrDefaultPlayerStateTx(Connection c, long playerId, int zoneId, int weekKey) throws SQLException {
        PlayerStateRow existing = readPlayerStateOrNullTx(c, playerId, weekKey, true);
        if (existing != null) {
            return existing;
        }
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO guild_war_player_state (player_id, zone_id, peace_mode, peace_ready_at_ms, open_challenge, protect_until_ms, streak_count, streak_distinct_count, last_streak_opponent_id, week_key, weekly_merit, milestone_claimed_mask, dividend_claimed_count, bounty_immunity_until_ms, bounty_payout_day_key, bounty_payout_count_today, next_garrison_refresh_ms, next_track_at_ms, updated_ms) VALUES (?,?,0,0,1,0,0,0,0,?,0,0,0,0,0,0,0,0,0)")) {
            ins.setLong(1, playerId);
            ins.setInt(2, zoneId);
            ins.setInt(3, weekKey);
            ins.executeUpdate();
        } catch (SQLException dup) {
            // 并发线程已抢先插入，回落重新 FOR UPDATE 读出提交态行
        }
        return readPlayerStateTx(c, playerId, zoneId, weekKey, true);
    }

    private PlayerStateRow readPlayerStateTx(Connection c, long playerId, int zoneId, int weekKey, boolean forUpdate) throws SQLException {
        PlayerStateRow found = readPlayerStateOrNullTx(c, playerId, weekKey, forUpdate);
        if (found != null) {
            return found;
        }
        return new PlayerStateRow(playerId, zoneId, 0, 0L, 1, 0L, 0, 0, 0L, weekKey, 0L, 0, 0, 0L, 0, 0, 0L, 0L, 0L);
    }

    private PlayerStateRow readPlayerStateOrNullTx(Connection c, long playerId, int weekKey, boolean forUpdate) throws SQLException {
        String sql = "SELECT player_id, zone_id, peace_mode, peace_ready_at_ms, open_challenge, protect_until_ms, streak_count, streak_distinct_count, last_streak_opponent_id, week_key, weekly_merit, milestone_claimed_mask, dividend_claimed_count, bounty_immunity_until_ms, bounty_payout_day_key, bounty_payout_count_today, next_garrison_refresh_ms, next_track_at_ms, updated_ms FROM guild_war_player_state WHERE player_id = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int storedWeek = rs.getInt(10);
                    boolean keepWeek = weekKey == 0 || storedWeek == weekKey;
                    int effectiveWeek = weekKey == 0 ? storedWeek : weekKey;
                    long weeklyMerit = keepWeek ? rs.getLong(11) : 0L;
                    int mask = keepWeek ? rs.getInt(12) : 0;
                    int divClaimed = keepWeek ? rs.getInt(13) : 0;
                    return new PlayerStateRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getLong(4),
                            rs.getInt(5), rs.getLong(6), rs.getInt(7), rs.getInt(8), rs.getLong(9),
                            effectiveWeek, weeklyMerit, mask, divClaimed, rs.getLong(14), rs.getInt(15),
                            rs.getInt(16), rs.getLong(17), rs.getLong(18), rs.getLong(19));
                }
            }
        }
        return null;
    }

    public void upsertPlayerState(Connection c, PlayerStateRow st) throws SQLException {
        try (PreparedStatement upd = c.prepareStatement(
                "UPDATE guild_war_player_state SET zone_id=?, peace_mode=?, peace_ready_at_ms=?, open_challenge=?, protect_until_ms=?, streak_count=?, streak_distinct_count=?, last_streak_opponent_id=?, week_key=?, weekly_merit=?, milestone_claimed_mask=?, dividend_claimed_count=?, bounty_immunity_until_ms=?, bounty_payout_day_key=?, bounty_payout_count_today=?, next_garrison_refresh_ms=?, next_track_at_ms=?, updated_ms=? WHERE player_id=?")) {
            upd.setInt(1, st.zoneId());
            upd.setInt(2, st.peaceMode());
            upd.setLong(3, st.peaceReadyAtMs());
            upd.setInt(4, st.openChallenge());
            upd.setLong(5, st.protectUntilMs());
            upd.setInt(6, st.streakCount());
            upd.setInt(7, st.streakDistinctCount());
            upd.setLong(8, st.lastStreakOpponentId());
            upd.setInt(9, st.weekKey());
            upd.setLong(10, st.weeklyMerit());
            upd.setInt(11, st.milestoneClaimedMask());
            upd.setInt(12, st.dividendClaimedCount());
            upd.setLong(13, st.bountyImmunityUntilMs());
            upd.setInt(14, st.bountyPayoutDayKey());
            upd.setInt(15, st.bountyPayoutCountToday());
            upd.setLong(16, st.nextGarrisonRefreshMs());
            upd.setLong(17, st.nextTrackAtMs());
            upd.setLong(18, st.updatedMs());
            upd.setLong(19, st.playerId());
            if (upd.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO guild_war_player_state (player_id, zone_id, peace_mode, peace_ready_at_ms, open_challenge, protect_until_ms, streak_count, streak_distinct_count, last_streak_opponent_id, week_key, weekly_merit, milestone_claimed_mask, dividend_claimed_count, bounty_immunity_until_ms, bounty_payout_day_key, bounty_payout_count_today, next_garrison_refresh_ms, next_track_at_ms, updated_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ins.setLong(1, st.playerId());
            ins.setInt(2, st.zoneId());
            ins.setInt(3, st.peaceMode());
            ins.setLong(4, st.peaceReadyAtMs());
            ins.setInt(5, st.openChallenge());
            ins.setLong(6, st.protectUntilMs());
            ins.setInt(7, st.streakCount());
            ins.setInt(8, st.streakDistinctCount());
            ins.setLong(9, st.lastStreakOpponentId());
            ins.setInt(10, st.weekKey());
            ins.setLong(11, st.weeklyMerit());
            ins.setInt(12, st.milestoneClaimedMask());
            ins.setInt(13, st.dividendClaimedCount());
            ins.setLong(14, st.bountyImmunityUntilMs());
            ins.setInt(15, st.bountyPayoutDayKey());
            ins.setInt(16, st.bountyPayoutCountToday());
            ins.setLong(17, st.nextGarrisonRefreshMs());
            ins.setLong(18, st.nextTrackAtMs());
            ins.setLong(19, st.updatedMs());
            ins.executeUpdate();
        }
    }

    public WeeklyProgressRow getOrDefaultWeeklyProgress(long guildId, int weekKey) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return readWeeklyProgressTx(c, guildId, weekKey, false);
        }
    }

    /** 同事务连接读取帮会周战功进度（加 FOR UPDATE，避免跨连接嵌套借池死锁）。 */
    private WeeklyProgressRow lockOrDefaultWeeklyProgressTx(Connection c, long guildId, int weekKey) throws SQLException {
        return readWeeklyProgressTx(c, guildId, weekKey, true);
    }

    private WeeklyProgressRow readWeeklyProgressTx(Connection c, long guildId, int weekKey, boolean forUpdate) throws SQLException {
        String sql = "SELECT guild_id, week_key, guild_merit, dividend_pool_count, mvp1_player_id, mvp1_merit, mvp2_player_id, mvp2_merit, first_win_names_json, first_win_at_ms, updated_ms FROM guild_war_weekly_progress WHERE guild_id = ? AND week_key = ?"
                + (forUpdate ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, guildId);
            ps.setInt(2, weekKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new WeeklyProgressRow(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getInt(4),
                            rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8),
                            rs.getString(9), rs.getLong(10), rs.getLong(11));
                }
            }
        }
        return new WeeklyProgressRow(guildId, weekKey, 0L, 0, 0L, 0L, 0L, 0L, "", 0L, 0L);
    }

    public List<GarrisonRow> listGarrison(long guildId) throws SQLException {
        List<GarrisonRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT guild_id, slot_index, player_id, player_name, level, appearance_id, pet_name, pet_table_id, snapshot_payload, updated_ms FROM guild_war_garrison WHERE guild_id = ? ORDER BY slot_index ASC")) {
            ps.setLong(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GarrisonRow(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getString(4),
                            rs.getInt(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                            rs.getBytes(9), rs.getLong(10)));
                }
            }
        }
        return out;
    }

    public List<GarrisonRow> upsertGarrison(long guildId, long targetPlayerId, int zoneId, int weekKey,
                                            boolean withdraw, GarrisonRow rec, long nowMs,
                                            int maxSlots, int cooldownSec) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                PlayerStateRow st = lockOrDefaultPlayerStateTx(c, targetPlayerId, zoneId, weekKey);
                if (!withdraw && Long.compareUnsigned(nowMs, st.nextGarrisonRefreshMs()) < 0) {
                    c.rollback();
                    throw new WarRuleException("garrison_cooldown");
                }
                List<GarrisonRow> existing = listGarrisonTx(c, guildId);
                if (withdraw) {
                    try (PreparedStatement del = c.prepareStatement(
                            "DELETE FROM guild_war_garrison WHERE guild_id = ? AND player_id = ?")) {
                        del.setLong(1, guildId);
                        del.setLong(2, targetPlayerId);
                        del.executeUpdate();
                    }
                } else {
                    int slot = -1;
                    for (GarrisonRow g : existing) {
                        if (g.playerId() == targetPlayerId) {
                            slot = g.slotIndex();
                            break;
                        }
                    }
                    if (slot < 0) {
                        boolean[] used = new boolean[maxSlots + 1];
                        for (GarrisonRow g : existing) {
                            if (g.slotIndex() >= 1 && g.slotIndex() <= maxSlots) {
                                used[g.slotIndex()] = true;
                            }
                        }
                        for (int s = 1; s <= maxSlots; s++) {
                            if (!used[s]) {
                                slot = s;
                                break;
                            }
                        }
                        if (slot < 0) {
                            c.rollback();
                            throw new WarRuleException("garrison_full");
                        }
                        try (PreparedStatement ins = c.prepareStatement(
                                "INSERT INTO guild_war_garrison (guild_id, slot_index, player_id, player_name, level, appearance_id, pet_name, pet_table_id, snapshot_payload, updated_ms) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                            ins.setLong(1, guildId);
                            ins.setInt(2, slot);
                            ins.setLong(3, targetPlayerId);
                            ins.setString(4, rec.playerName());
                            ins.setInt(5, rec.level());
                            ins.setString(6, rec.appearanceId());
                            ins.setString(7, rec.petName());
                            ins.setInt(8, rec.petTableId());
                            ins.setBytes(9, rec.snapshotPayload());
                            ins.setLong(10, nowMs);
                            ins.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement upd = c.prepareStatement(
                                "UPDATE guild_war_garrison SET player_name=?, level=?, appearance_id=?, pet_name=?, pet_table_id=?, snapshot_payload=?, updated_ms=? WHERE guild_id=? AND slot_index=?")) {
                            upd.setString(1, rec.playerName());
                            upd.setInt(2, rec.level());
                            upd.setString(3, rec.appearanceId());
                            upd.setString(4, rec.petName());
                            upd.setInt(5, rec.petTableId());
                            upd.setBytes(6, rec.snapshotPayload());
                            upd.setLong(7, nowMs);
                            upd.setLong(8, guildId);
                            upd.setInt(9, slot);
                            upd.executeUpdate();
                        }
                    }
                    PlayerStateRow nextSt = new PlayerStateRow(st.playerId(), zoneId, st.peaceMode(), st.peaceReadyAtMs(),
                            st.openChallenge(), st.protectUntilMs(), st.streakCount(), st.streakDistinctCount(),
                            st.lastStreakOpponentId(), st.weekKey(), st.weeklyMerit(), st.milestoneClaimedMask(),
                            st.dividendClaimedCount(), st.bountyImmunityUntilMs(), st.bountyPayoutDayKey(),
                            st.bountyPayoutCountToday(), nowMs + cooldownSec * 1000L, st.nextTrackAtMs(), nowMs);
                    upsertPlayerState(c, nextSt);
                }
                List<GarrisonRow> after = listGarrisonTx(c, guildId);
                c.commit();
                return after;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public List<PlayerStateRow> listOpenChallengePlayers(int zoneId, int limit) throws SQLException {
        List<PlayerStateRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_id, zone_id, peace_mode, peace_ready_at_ms, open_challenge, protect_until_ms, streak_count, streak_distinct_count, last_streak_opponent_id, week_key, weekly_merit, milestone_claimed_mask, dividend_claimed_count, bounty_immunity_until_ms, bounty_payout_day_key, bounty_payout_count_today, next_garrison_refresh_ms, next_track_at_ms, updated_ms FROM guild_war_player_state WHERE zone_id = ? AND open_challenge = 1 AND peace_mode = 0 ORDER BY updated_ms DESC LIMIT ?")) {
            ps.setInt(1, zoneId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readPlayerState(rs));
                }
            }
        }
        return out;
    }

    public List<PlayerStateRow> listStreakLeaders(int zoneId, int limit) throws SQLException {
        List<PlayerStateRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_id, zone_id, peace_mode, peace_ready_at_ms, open_challenge, protect_until_ms, streak_count, streak_distinct_count, last_streak_opponent_id, week_key, weekly_merit, milestone_claimed_mask, dividend_claimed_count, bounty_immunity_until_ms, bounty_payout_day_key, bounty_payout_count_today, next_garrison_refresh_ms, next_track_at_ms, updated_ms FROM guild_war_player_state WHERE zone_id = ? AND streak_count >= 3 AND streak_distinct_count >= 2 ORDER BY streak_count DESC LIMIT ?")) {
            ps.setInt(1, zoneId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readPlayerState(rs));
                }
            }
        }
        return out;
    }

    public List<BountyRow> listActiveBounties(int zoneId, long guildId, long nowMs) throws SQLException {
        List<BountyRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bounty_id, zone_id, issuer_player_id, issuer_guild_id, target_player_id, target_guild_id, tier, escrow_silver, tax_silver, public_board, status, counter_kills, counter_killers_json, escrow_op_id, created_at_ms, expires_at_ms, resolved_at_ms, winner_player_id FROM guild_war_bounty WHERE zone_id = ? AND status = 2 AND expires_at_ms > ? AND (public_board = 1 OR issuer_guild_id = ?) ORDER BY created_at_ms DESC LIMIT 50")) {
            ps.setInt(1, zoneId);
            ps.setLong(2, nowMs);
            ps.setLong(3, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readBounty(rs));
                }
            }
        }
        return out;
    }

    public Optional<BountyRow> activeBountyByTarget(long targetPlayerId, long nowMs) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bounty_id, zone_id, issuer_player_id, issuer_guild_id, target_player_id, target_guild_id, tier, escrow_silver, tax_silver, public_board, status, counter_kills, counter_killers_json, escrow_op_id, created_at_ms, expires_at_ms, resolved_at_ms, winner_player_id FROM guild_war_bounty WHERE target_player_id = ? AND status IN (1, 2) AND expires_at_ms > ? LIMIT 1")) {
            ps.setLong(1, targetPlayerId);
            ps.setLong(2, nowMs);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readBounty(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<BountyRow> getBounty(long bountyId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bounty_id, zone_id, issuer_player_id, issuer_guild_id, target_player_id, target_guild_id, tier, escrow_silver, tax_silver, public_board, status, counter_kills, counter_killers_json, escrow_op_id, created_at_ms, expires_at_ms, resolved_at_ms, winner_player_id FROM guild_war_bounty WHERE bounty_id = ?")) {
            ps.setLong(1, bountyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readBounty(rs));
                }
            }
        }
        return Optional.empty();
    }

    public BountyRow postBounty(long bountyId, int zoneId, long issuerPlayerId, long issuerGuildId,
                                long targetPlayerId, long targetGuildId, GuildWarBountyTier tier,
                                GuildWarRules.BountyTierSpec spec, boolean publicBoard,
                                int ttlSec, long nowMs, int initialStatus, long escrowOpId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // 先锁目标玩家状态行串行化对同一目标的并发发单，再用同连接查重与豁免期
                PlayerStateRow targetState = lockOrDefaultPlayerStateTx(c, targetPlayerId, zoneId, 0);
                if (Long.compareUnsigned(nowMs, targetState.bountyImmunityUntilMs()) < 0) {
                    c.rollback();
                    throw new WarRuleException("bounty_active");
                }
                try (PreparedStatement dup = c.prepareStatement(
                        "SELECT 1 FROM guild_war_bounty WHERE target_player_id = ? AND status IN (1, 2) AND expires_at_ms > ? FOR UPDATE")) {
                    dup.setLong(1, targetPlayerId);
                    dup.setLong(2, nowMs);
                    try (ResultSet rs = dup.executeQuery()) {
                        if (rs.next()) {
                            c.rollback();
                            throw new WarRuleException("bounty_active");
                        }
                    }
                }
                long expiresAtMs = nowMs + ttlSec * 1000L;
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO guild_war_bounty (bounty_id, zone_id, issuer_player_id, issuer_guild_id, target_player_id, target_guild_id, tier, escrow_silver, tax_silver, public_board, status, counter_kills, counter_killers_json, escrow_op_id, created_at_ms, expires_at_ms, resolved_at_ms, winner_player_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,0,'',?,?,?,0,0)")) {
                    ins.setLong(1, bountyId);
                    ins.setInt(2, zoneId);
                    ins.setLong(3, issuerPlayerId);
                    ins.setLong(4, issuerGuildId);
                    ins.setLong(5, targetPlayerId);
                    ins.setLong(6, targetGuildId);
                    ins.setInt(7, tier.getNumber());
                    ins.setLong(8, spec.poolSilver());
                    ins.setLong(9, spec.taxSilver());
                    ins.setInt(10, publicBoard ? 1 : 0);
                    ins.setInt(11, initialStatus);
                    ins.setLong(12, escrowOpId);
                    ins.setLong(13, nowMs);
                    ins.setLong(14, expiresAtMs);
                    ins.executeUpdate();
                }
                c.commit();
                return new BountyRow(bountyId, zoneId, issuerPlayerId, issuerGuildId, targetPlayerId, targetGuildId,
                        tier.getNumber(), spec.poolSilver(), spec.taxSilver(), publicBoard ? 1 : 0, initialStatus,
                        0, "", escrowOpId, nowMs, expiresAtMs, 0L, 0L);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public long markTrackCooldown(long playerId, int zoneId, int weekKey, long nowMs, int cooldownSec) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                PlayerStateRow st = lockOrDefaultPlayerStateTx(c, playerId, zoneId, weekKey);
                if (Long.compareUnsigned(nowMs, st.nextTrackAtMs()) < 0) {
                    c.rollback();
                    throw new WarRuleException("track_cooldown");
                }
                long nextTrack = nowMs + cooldownSec * 1000L;
                PlayerStateRow updated = new PlayerStateRow(st.playerId(), zoneId, st.peaceMode(), st.peaceReadyAtMs(),
                        st.openChallenge(), st.protectUntilMs(), st.streakCount(), st.streakDistinctCount(),
                        st.lastStreakOpponentId(), st.weekKey(), st.weeklyMerit(), st.milestoneClaimedMask(),
                        st.dividendClaimedCount(), st.bountyImmunityUntilMs(), st.bountyPayoutDayKey(),
                        st.bountyPayoutCountToday(), st.nextGarrisonRefreshMs(), nextTrack, nowMs);
                upsertPlayerState(c, updated);
                c.commit();
                return nextTrack;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public int claimMilestone(long playerId, long guildId, int zoneId, int weekKey, int milestoneId,
                              long requiredGuildMerit, long minPersonalMerit, long nowMs) throws SQLException {
        if (milestoneId < 1 || milestoneId > 30) {
            throw new WarRuleException("milestone_locked");
        }
        int bit = 1 << (milestoneId - 1);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                WeeklyProgressRow prog = lockOrDefaultWeeklyProgressTx(c, guildId, weekKey);
                PlayerStateRow st = lockOrDefaultPlayerStateTx(c, playerId, zoneId, weekKey);
                if ((st.milestoneClaimedMask() & bit) != 0) {
                    c.rollback();
                    throw new WarRuleException("milestone_claimed");
                }
                if (prog.guildMerit() < requiredGuildMerit || st.weeklyMerit() < minPersonalMerit) {
                    c.rollback();
                    throw new WarRuleException("milestone_locked");
                }
                int nextMask = st.milestoneClaimedMask() | bit;
                PlayerStateRow updated = new PlayerStateRow(st.playerId(), zoneId, st.peaceMode(), st.peaceReadyAtMs(),
                        st.openChallenge(), st.protectUntilMs(), st.streakCount(), st.streakDistinctCount(),
                        st.lastStreakOpponentId(), weekKey, st.weeklyMerit(), nextMask, st.dividendClaimedCount(),
                        st.bountyImmunityUntilMs(), st.bountyPayoutDayKey(), st.bountyPayoutCountToday(),
                        st.nextGarrisonRefreshMs(), st.nextTrackAtMs(), nowMs);
                upsertPlayerState(c, updated);
                c.commit();
                return nextMask;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public int claimDividend(long playerId, long guildId, int zoneId, int weekKey, long minPersonalMerit,
                             int playerWeeklyCap, long nowMs) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                WeeklyProgressRow prog = lockOrDefaultWeeklyProgressTx(c, guildId, weekKey);
                PlayerStateRow st = lockOrDefaultPlayerStateTx(c, playerId, zoneId, weekKey);
                if (st.weeklyMerit() < minPersonalMerit) {
                    c.rollback();
                    throw new WarRuleException("dividend_empty");
                }
                int maxAllowed = Math.min(prog.dividendPoolCount(), playerWeeklyCap);
                int claimable = maxAllowed - st.dividendClaimedCount();
                if (claimable <= 0) {
                    c.rollback();
                    throw new WarRuleException("dividend_empty");
                }
                PlayerStateRow updated = new PlayerStateRow(st.playerId(), zoneId, st.peaceMode(), st.peaceReadyAtMs(),
                        st.openChallenge(), st.protectUntilMs(), st.streakCount(), st.streakDistinctCount(),
                        st.lastStreakOpponentId(), weekKey, st.weeklyMerit(), st.milestoneClaimedMask(),
                        st.dividendClaimedCount() + claimable, st.bountyImmunityUntilMs(),
                        st.bountyPayoutDayKey(), st.bountyPayoutCountToday(), st.nextGarrisonRefreshMs(),
                        st.nextTrackAtMs(), nowMs);
                upsertPlayerState(c, updated);
                c.commit();
                return claimable;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public List<Long> listRivals(long guildId, int weekKey) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT guild_a_id, guild_b_id, clash_count, mutual_bounty FROM guild_war_rivalry WHERE (guild_a_id = ? OR guild_b_id = ?) AND week_key = ?")) {
            ps.setLong(1, guildId);
            ps.setLong(2, guildId);
            ps.setInt(3, weekKey);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long a = rs.getLong(1);
                    long b = rs.getLong(2);
                    if (GuildWarRules.isRivalryActive(rs.getInt(3), rs.getInt(4) != 0)) {
                        out.add(a == guildId ? b : a);
                    }
                }
            }
        }
        return out;
    }

    public List<BattleRow> listRecentBattles(long guildId, int limit) throws SQLException {
        List<BattleRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT battle_id, zone_id, war_battle_kind, leyline_id, bounty_id, attacker_guild_id, defender_guild_id, outcome, total_rounds, banner_hp_before, banner_hp_after, banner_shielded, bounty_outcome_tag, jackpot_contribution, jackpot_silver, diminish_percent, attacker_contribution, attacker_silver, attacker_funds, attacker_merit, defender_contribution, defender_silver, defender_funds, defender_merit, summary_payload, finished_at_ms FROM guild_war_battle WHERE attacker_guild_id = ? OR defender_guild_id = ? ORDER BY finished_at_ms DESC LIMIT ?")) {
            ps.setLong(1, guildId);
            ps.setLong(2, guildId);
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readBattle(rs));
                }
            }
        }
        return out;
    }

    public Optional<BattleRow> getBattle(long battleId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT battle_id, zone_id, war_battle_kind, leyline_id, bounty_id, attacker_guild_id, defender_guild_id, outcome, total_rounds, banner_hp_before, banner_hp_after, banner_shielded, bounty_outcome_tag, jackpot_contribution, jackpot_silver, diminish_percent, attacker_contribution, attacker_silver, attacker_funds, attacker_merit, defender_contribution, defender_silver, defender_funds, defender_merit, summary_payload, finished_at_ms FROM guild_war_battle WHERE battle_id = ?")) {
            ps.setLong(1, battleId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readBattle(rs));
                }
            }
        }
        return Optional.empty();
    }

    /** 幂等写入单局帮战结算、更新灵脉旗势、周战功、宿敌计数、连胜与追杀令状态（已结算返回 false）。 */
    public boolean settleBattle(BattleRow battle, int weekKey, int dayKey, List<Long> attackerIds,
                                List<Long> defenderIds, boolean capturedLeyline, long nextShieldUntilMs,
                                boolean bountyResolved, int nextBountyStatus, int nextCounterKills,
                                String nextCounterKillersJson, long winnerPlayerId, long bountyImmunitySec,
                                long protectSec, long nowMs) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement exists = c.prepareStatement(
                        "SELECT 1 FROM guild_war_battle WHERE battle_id = ?")) {
                    exists.setLong(1, battle.battleId());
                    try (ResultSet rs = exists.executeQuery()) {
                        if (rs.next()) {
                            c.rollback();
                            return false;
                        }
                    }
                }
                // 1. 灵脉旗势更新（先 FOR UPDATE 锁灵脉行，按表序 13 推进）
                if (battle.leylineId() != 0 && battle.bannerShielded() == 0) {
                    try (PreparedStatement lockLey = c.prepareStatement(
                            "SELECT 1 FROM guild_war_leyline WHERE zone_id=? AND leyline_id=? FOR UPDATE")) {
                        lockLey.setInt(1, battle.zoneId());
                        lockLey.setInt(2, battle.leylineId());
                        lockLey.executeQuery().close();
                    }
                    if (capturedLeyline) {
                        try (PreparedStatement upd = c.prepareStatement(
                                "UPDATE guild_war_leyline SET owner_guild_id=?, banner_hp=?, shield_until_ms=?, captured_at_ms=?, updated_ms=? WHERE zone_id=? AND leyline_id=?")) {
                            upd.setLong(1, battle.attackerGuildId());
                            upd.setInt(2, battle.bannerHpAfter());
                            upd.setLong(3, nextShieldUntilMs);
                            upd.setLong(4, nowMs);
                            upd.setLong(5, nowMs);
                            upd.setInt(6, battle.zoneId());
                            upd.setInt(7, battle.leylineId());
                            upd.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement upd = c.prepareStatement(
                                "UPDATE guild_war_leyline SET banner_hp=?, shield_until_ms=?, updated_ms=? WHERE zone_id=? AND leyline_id=?")) {
                            upd.setInt(1, battle.bannerHpAfter());
                            upd.setLong(2, nextShieldUntilMs);
                            upd.setLong(3, nowMs);
                            upd.setInt(4, battle.zoneId());
                            upd.setInt(5, battle.leylineId());
                            upd.executeUpdate();
                        }
                    }
                }
                // 2. 周战功累计（按 guild_id 升序更新，防两帮互打成环死锁）
                long g1 = battle.attackerGuildId();
                long g2 = battle.defenderGuildId();
                if (g1 != 0 && g2 != 0 && g1 != g2) {
                    if (Long.compareUnsigned(g1, g2) <= 0) {
                        if (battle.attackerMerit() > 0) addGuildWeeklyMerit(c, g1, weekKey, battle.attackerMerit(), nowMs);
                        if (battle.defenderMerit() > 0) addGuildWeeklyMerit(c, g2, weekKey, battle.defenderMerit(), nowMs);
                    } else {
                        if (battle.defenderMerit() > 0) addGuildWeeklyMerit(c, g2, weekKey, battle.defenderMerit(), nowMs);
                        if (battle.attackerMerit() > 0) addGuildWeeklyMerit(c, g1, weekKey, battle.attackerMerit(), nowMs);
                    }
                } else {
                    if (battle.attackerMerit() > 0 && g1 != 0) addGuildWeeklyMerit(c, g1, weekKey, battle.attackerMerit(), nowMs);
                    if (battle.defenderMerit() > 0 && g2 != 0) addGuildWeeklyMerit(c, g2, weekKey, battle.defenderMerit(), nowMs);
                }
                // 3. 玩家战功、战后保护、免战冷却与连胜（按 player_id 无符号升序同连接 FOR UPDATE，避免双人互锁与覆盖）
                boolean atkWon = battle.outcome() == 1;
                boolean defWon = battle.outcome() == 2;
                long oppOfAtk = defenderIds.isEmpty() ? 0L : defenderIds.get(0);
                long oppOfDef = attackerIds.isEmpty() ? 0L : attackerIds.get(0);
                java.util.Set<Long> atkSet = new java.util.HashSet<>(attackerIds);
                java.util.List<Long> allParticipants = new ArrayList<>(attackerIds.size() + defenderIds.size());
                allParticipants.addAll(attackerIds);
                for (long pid : defenderIds) {
                    if (!atkSet.contains(pid)) {
                        allParticipants.add(pid);
                    }
                }
                allParticipants.sort(Long::compareUnsigned);
                for (long pid : allParticipants) {
                    boolean isAtk = atkSet.contains(pid);
                    updateParticipantState(c, pid, battle.zoneId(), weekKey,
                            isAtk ? battle.attackerMerit() : battle.defenderMerit(),
                            isAtk ? atkWon : defWon,
                            isAtk ? defWon : atkWon,
                            isAtk ? oppOfAtk : oppOfDef,
                            protectSec, nowMs);
                }
                // 4. 追杀令推进 / 结单 / 反杀撕单（先 FOR UPDATE 锁追杀令行，且仅在本次真正结单时给目标挂免疫期，同连接读防覆盖战功）
                if (battle.bountyId() != 0) {
                    int bountyUpdated;
                    try (PreparedStatement lockB = c.prepareStatement(
                            "SELECT 1 FROM guild_war_bounty WHERE bounty_id=? FOR UPDATE")) {
                        lockB.setLong(1, battle.bountyId());
                        lockB.executeQuery().close();
                    }
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE guild_war_bounty SET status=?, counter_kills=?, counter_killers_json=?, resolved_at_ms=?, winner_player_id=? WHERE bounty_id=? AND status=2")) {
                        upd.setInt(1, nextBountyStatus);
                        upd.setInt(2, nextCounterKills);
                        upd.setString(3, nextCounterKillersJson == null ? "" : nextCounterKillersJson);
                        upd.setLong(4, bountyResolved ? nowMs : 0L);
                        upd.setLong(5, winnerPlayerId);
                        upd.setLong(6, battle.bountyId());
                        bountyUpdated = upd.executeUpdate();
                    }
                    if (bountyResolved && bountyUpdated == 1 && !defenderIds.isEmpty() && bountyImmunitySec > 0) {
                        PlayerStateRow targetSt = lockOrDefaultPlayerStateTx(c, defenderIds.get(0), battle.zoneId(), weekKey);
                        upsertPlayerState(c, new PlayerStateRow(targetSt.playerId(), battle.zoneId(), targetSt.peaceMode(),
                                targetSt.peaceReadyAtMs(), targetSt.openChallenge(), targetSt.protectUntilMs(),
                                targetSt.streakCount(), targetSt.streakDistinctCount(), targetSt.lastStreakOpponentId(),
                                weekKey, targetSt.weeklyMerit(), targetSt.milestoneClaimedMask(),
                                targetSt.dividendClaimedCount(), nowMs + bountyImmunitySec * 1000L,
                                dayKey, targetSt.bountyPayoutCountToday(), targetSt.nextGarrisonRefreshMs(),
                                targetSt.nextTrackAtMs(), nowMs));
                    }
                }
                // 5. 宿敌计数
                if (battle.attackerGuildId() != 0 && battle.defenderGuildId() != 0
                        && battle.attackerGuildId() != battle.defenderGuildId()) {
                    long ga = Math.min(battle.attackerGuildId(), battle.defenderGuildId());
                    long gb = Math.max(battle.attackerGuildId(), battle.defenderGuildId());
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE guild_war_rivalry SET clash_count = clash_count + 1, updated_ms = ? WHERE guild_a_id=? AND guild_b_id=? AND week_key=?")) {
                        upd.setLong(1, nowMs);
                        upd.setLong(2, ga);
                        upd.setLong(3, gb);
                        upd.setInt(4, weekKey);
                        if (upd.executeUpdate() == 0) {
                            try (PreparedStatement ins = c.prepareStatement(
                                    "INSERT INTO guild_war_rivalry (guild_a_id, guild_b_id, week_key, clash_count, mutual_bounty, updated_ms) VALUES (?,?,?,1,0,?)")) {
                                ins.setLong(1, ga);
                                ins.setLong(2, gb);
                                ins.setInt(3, weekKey);
                                ins.setLong(4, nowMs);
                                ins.executeUpdate();
                            }
                        }
                    }
                }
                // 6. 写入幂等终局记录
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO guild_war_battle (battle_id, zone_id, war_battle_kind, leyline_id, bounty_id, attacker_guild_id, defender_guild_id, outcome, total_rounds, banner_hp_before, banner_hp_after, banner_shielded, bounty_outcome_tag, jackpot_contribution, jackpot_silver, diminish_percent, attacker_contribution, attacker_silver, attacker_funds, attacker_merit, defender_contribution, defender_silver, defender_funds, defender_merit, summary_payload, finished_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ins.setLong(1, battle.battleId());
                    ins.setInt(2, battle.zoneId());
                    ins.setInt(3, battle.warBattleKind());
                    ins.setInt(4, battle.leylineId());
                    ins.setLong(5, battle.bountyId());
                    ins.setLong(6, battle.attackerGuildId());
                    ins.setLong(7, battle.defenderGuildId());
                    ins.setInt(8, battle.outcome());
                    ins.setInt(9, battle.totalRounds());
                    ins.setInt(10, battle.bannerHpBefore());
                    ins.setInt(11, battle.bannerHpAfter());
                    ins.setInt(12, battle.bannerShielded());
                    ins.setInt(13, battle.bountyOutcomeTag());
                    ins.setLong(14, battle.jackpotContribution());
                    ins.setLong(15, battle.jackpotSilver());
                    ins.setInt(16, battle.diminishPercent());
                    ins.setLong(17, battle.attackerContribution());
                    ins.setLong(18, battle.attackerSilver());
                    ins.setLong(19, battle.attackerFunds());
                    ins.setLong(20, battle.attackerMerit());
                    ins.setLong(21, battle.defenderContribution());
                    ins.setLong(22, battle.defenderSilver());
                    ins.setLong(23, battle.defenderFunds());
                    ins.setLong(24, battle.defenderMerit());
                    ins.setBytes(25, battle.summaryPayload());
                    ins.setLong(26, battle.finishedAtMs());
                    ins.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public void updateStance(PlayerStateRow updated) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            upsertPlayerState(c, updated);
        }
    }

    private void addGuildWeeklyMerit(Connection c, long guildId, int weekKey, long delta, long nowMs) throws SQLException {
        try (PreparedStatement upd = c.prepareStatement(
                "UPDATE guild_war_weekly_progress SET guild_merit = guild_merit + ?, updated_ms = ? WHERE guild_id = ? AND week_key = ?")) {
            upd.setLong(1, delta);
            upd.setLong(2, nowMs);
            upd.setLong(3, guildId);
            upd.setInt(4, weekKey);
            if (upd.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO guild_war_weekly_progress (guild_id, week_key, guild_merit, dividend_pool_count, mvp1_player_id, mvp1_merit, mvp2_player_id, mvp2_merit, first_win_names_json, first_win_at_ms, updated_ms) VALUES (?,?,?,0,0,0,0,0,'',0,?)")) {
            ins.setLong(1, guildId);
            ins.setInt(2, weekKey);
            ins.setLong(3, delta);
            ins.setLong(4, nowMs);
            ins.executeUpdate();
        }
    }

    private void updateParticipantState(Connection c, long playerId, int zoneId, int weekKey, long meritDelta,
                                        boolean won, boolean lost, long opponentId, long protectSec,
                                        long nowMs) throws SQLException {
        PlayerStateRow st = lockOrDefaultPlayerStateTx(c, playerId, zoneId, weekKey);
        int nextStreak = st.streakCount();
        int nextDistinct = st.streakDistinctCount();
        long nextLastOpp = st.lastStreakOpponentId();
        if (won) {
            nextStreak++;
            if (opponentId != 0 && opponentId != st.lastStreakOpponentId()) {
                nextDistinct++;
                nextLastOpp = opponentId;
            }
        } else if (lost) {
            nextStreak = 0;
            nextDistinct = 0;
            nextLastOpp = 0L;
        }
        long protectUntil = Math.max(st.protectUntilMs(), nowMs + protectSec * 1000L);
        long peaceReadyAt = Math.max(st.peaceReadyAtMs(), nowMs + GuildWarRules.DEFAULT_PEACE_COOLDOWN_SEC * 1000L);
        upsertPlayerState(c, new PlayerStateRow(playerId, zoneId, st.peaceMode(), peaceReadyAt,
                st.openChallenge(), protectUntil, nextStreak, nextDistinct, nextLastOpp, weekKey,
                st.weeklyMerit() + meritDelta, st.milestoneClaimedMask(), st.dividendClaimedCount(),
                st.bountyImmunityUntilMs(), st.bountyPayoutDayKey(), st.bountyPayoutCountToday(),
                st.nextGarrisonRefreshMs(), st.nextTrackAtMs(), nowMs));
    }

    /** 退帮 / 踢人 / 注销同事务清理守旗阵槽位。 */
    public static void cleanupGarrisonTx(com.game.guild.store.GuildJdbc tx, long guildId, List<Long> playerIds)
            throws SQLException {
        if (guildId == 0L || playerIds == null || playerIds.isEmpty()) {
            return;
        }
        for (long pid : playerIds) {
            tx.update("DELETE FROM guild_war_garrison WHERE guild_id = ? AND player_id = ?", guildId, pid);
        }
    }

    /** 帮会解散同事务清空守旗阵并释放其占领的灵脉归属。 */
    public static void cleanupDisbandTx(com.game.guild.store.GuildJdbc tx, long guildId, long nowMs)
            throws SQLException {
        if (guildId == 0L) {
            return;
        }
        tx.update("DELETE FROM guild_war_garrison WHERE guild_id = ?", guildId);
        tx.update("UPDATE guild_war_leyline SET owner_guild_id = 0, shield_until_ms = 0, updated_ms = ? WHERE owner_guild_id = ?",
                nowMs, guildId);
    }

    private static List<GarrisonRow> listGarrisonTx(Connection c, long guildId) throws SQLException {
        List<GarrisonRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT guild_id, slot_index, player_id, player_name, level, appearance_id, pet_name, pet_table_id, snapshot_payload, updated_ms FROM guild_war_garrison WHERE guild_id = ? ORDER BY slot_index ASC")) {
            ps.setLong(1, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new GarrisonRow(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getString(4),
                            rs.getInt(5), rs.getString(6), rs.getString(7), rs.getInt(8),
                            rs.getBytes(9), rs.getLong(10)));
                }
            }
        }
        return out;
    }

    private static PlayerStateRow readPlayerState(ResultSet rs) throws SQLException {
        return new PlayerStateRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getLong(4),
                rs.getInt(5), rs.getLong(6), rs.getInt(7), rs.getInt(8), rs.getLong(9),
                rs.getInt(10), rs.getLong(11), rs.getInt(12), rs.getInt(13), rs.getLong(14),
                rs.getInt(15), rs.getInt(16), rs.getLong(17), rs.getLong(18), rs.getLong(19));
    }

    private static BountyRow readBounty(ResultSet rs) throws SQLException {
        return new BountyRow(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getLong(4),
                rs.getLong(5), rs.getLong(6), rs.getInt(7), rs.getLong(8), rs.getLong(9),
                rs.getInt(10), rs.getInt(11), rs.getInt(12), rs.getString(13), rs.getLong(14),
                rs.getLong(15), rs.getLong(16), rs.getLong(17), rs.getLong(18));
    }

    private static BattleRow readBattle(ResultSet rs) throws SQLException {
        return new BattleRow(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7), rs.getInt(8), rs.getInt(9), rs.getInt(10), rs.getInt(11),
                rs.getInt(12), rs.getInt(13), rs.getLong(14), rs.getLong(15), rs.getInt(16),
                rs.getLong(17), rs.getLong(18), rs.getLong(19), rs.getLong(20), rs.getLong(21),
                rs.getLong(22), rs.getLong(23), rs.getLong(24), rs.getBytes(25), rs.getLong(26));
    }

    public static final class WarRuleException extends RuntimeException {
        public final String code;

        public WarRuleException(String code) {
            super(code);
            this.code = code;
        }
    }
}
