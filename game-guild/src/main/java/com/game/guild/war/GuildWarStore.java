package com.game.guild.war;

import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetStream;
import com.game.guild.asset.AssetOp;
import com.game.guild.rules.GuildLimits;
import com.game.guild.rules.GuildRoles;
import com.game.guild.store.AssetOpColumns;
import com.game.guild.store.pb.GuildAssetOpKind;
import com.game.guild.store.pb.GuildAssetOpRow;
import com.game.guild.store.pb.GuildAssetOpStatus;
import com.game.guild.store.pb.GuildDailyCounterKind;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.TransactionType;
import com.game.proto.guild.GuildWarBountyTier;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
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
                            long createdAtMs, long expiresAtMs, long resolvedAtMs, long winnerPlayerId,
                            long escrowGuildFunds) {
        public BountyRow(long bountyId, int zoneId, long issuerPlayerId, long issuerGuildId, long targetPlayerId,
                         long targetGuildId, int tier, long escrowSilver, long taxSilver, int publicBoard,
                         int status, int counterKills, String counterKillersJson, long escrowOpId,
                         long createdAtMs, long expiresAtMs, long resolvedAtMs, long winnerPlayerId) {
            this(bountyId, zoneId, issuerPlayerId, issuerGuildId, targetPlayerId, targetGuildId, tier,
                    escrowSilver, taxSilver, publicBoard, status, counterKills, counterKillersJson, escrowOpId,
                    createdAtMs, expiresAtMs, resolvedAtMs, winnerPlayerId, 0L);
        }
    }

    /** 事务外计算灵脉旗势变更所依据的快照，事务内按 leyline_id 升序行锁逐项核验（r2-gw#3）。 */
    public record LeylineBasis(int leylineId, long ownerGuildId, int bannerHp, long shieldUntilMs,
                               boolean attackerOwnsOther) {
    }

    /** 追杀令原子结算计划：与对局终局同事务校验、落态、发赏及撕单退另一半（r2-gw#1 / r2-gw#2）。 */
    public record BountySettlePlan(
            boolean touch, boolean resolved,
            int expectedCounterKills, String expectedCounterKillersJson,
            int nextStatus, int nextCounterKills, String nextCounterKillersJson,
            long winnerPlayerId, long winnerGuildId,
            long rewardSilver, long rewardOpId, AssetBundle rewardBundle, long rewardLeaseToken,
            int dailyPayoutLimit,
            long issuerPlayerId, long issuerGuildId,
            long issuerRefundSilver, long issuerRefundOpId, AssetBundle issuerRefundBundle,
            long issuerRefundLeaseToken, long issuerRefundGuildFunds) {
        public static final BountySettlePlan NONE = new BountySettlePlan(
                false, false, 0, "", 2, 0, "", 0L, 0L, 0L, 0L, null, 0L, 0, 0L, 0L, 0L, 0L, null, 0L, 0L);
    }

    /** 到期追杀令同事务置 EXPIRED(5) 并原路退款的结果（r2-gw#2）。 */
    public record ExpireBountyResult(boolean expired, AssetOp refundOp) {
    }

    private static final String BOUNTY_COLS =
            "bounty_id, zone_id, issuer_player_id, issuer_guild_id, target_player_id, target_guild_id, tier, "
                    + "escrow_silver, tax_silver, public_board, status, counter_kills, counter_killers_json, "
                    + "escrow_op_id, created_at_ms, expires_at_ms, resolved_at_ms, winner_player_id, escrow_guild_funds";

    public record BattleRow(long battleId, int zoneId, int warBattleKind, int leylineId, long bountyId,
                            long attackerGuildId, long defenderGuildId, int outcome, int totalRounds,
                            int bannerHpBefore, int bannerHpAfter, int bannerShielded, int bountyOutcomeTag,
                            long jackpotContribution, long jackpotSilver, int diminishPercent,
                            long attackerContribution, long attackerSilver, long attackerFunds, long attackerMerit,
                            long defenderContribution, long defenderSilver, long defenderFunds, long defenderMerit,
                            byte[] summaryPayload, long finishedAtMs) {
    }

    public record FeudRow(long feudId, int zoneId, long issuerGuildId, String issuerGuildName,
                          long targetGuildId, String targetGuildName, int status,
                          int issuerWins, int targetWins, long issuerMerit, long targetMerit,
                          long createdAtMs, long acceptDeadlineMs, long startedAtMs, long endsAtMs,
                          long winnerGuildId, long updatedMs) {
    }

    public record FeudLookup(Optional<FeudRow> activeOrPending, Optional<FeudRow> justFinished) {
    }

    public record FeudBattleResult(Optional<FeudRow> feud, boolean justFinished) {
    }

    public record MilestoneClaimResult(int mask, AssetOp assetOp) {
    }

    public record DividendClaimResult(int claimedCount, AssetOp assetOp) {
    }

    public record PostBountyResult(BountyRow bounty, AssetOp escrowOp) {
    }

    public record PlayerBattleCredit(long playerId, long guildId, long contributionGain, long silverGain,
                                     long opId, AssetBundle bundle, long leaseToken) {
    }

    public record SettleBattleResult(boolean settled, boolean bountyUpdated, List<AssetOp> createdOps) {
    }

    /** 角色入账护栏状态（r2-gw#6）：NORMAL=可入账，FROZEN=过户/冻结中记待入账，DELETED=已注销不写任何记录。 */
    public enum CreditFenceState {
        NORMAL, FROZEN, DELETED
    }

    /** 事务内按 player_id 锁并判别角色状态（与 guild_activity_reward_owed 共用 P 首锁；隔离无 player 表的单测退化为 NORMAL）。 */
    @FunctionalInterface
    public interface RoleFence {
        CreditFenceState classifyInTx(Connection c, long playerId) throws SQLException;

        RoleFence OPEN = (c, playerId) -> CreditFenceState.NORMAL;

        RoleFence PLAYER_TABLE = (c, playerId) -> {
            if (playerId == 0L) {
                return CreditFenceState.NORMAL;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT trade_listing_id FROM player WHERE player_id = ? FOR UPDATE")) {
                ps.setLong(1, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return CreditFenceState.DELETED;
                    }
                    return rs.getLong(1) != 0L ? CreditFenceState.FROZEN : CreditFenceState.NORMAL;
                }
            } catch (SQLException e) {
                String state = e.getSQLState();
                if ("42S02".equals(state) || "42102".equals(state)) {
                    return CreditFenceState.NORMAL;
                }
                throw e;
            }
        };
    }

    /** 终态追杀令保留期：30 天后分批清理（r2-gw#7）。 */
    public static final long TERMINAL_BOUNTY_RETENTION_MS = 30L * 24 * 3600 * 1000L;

    private final DataSource dataSource;
    private volatile RoleFence roleFence = RoleFence.OPEN;

    public GuildWarStore withRoleFence(RoleFence fence) {
        this.roleFence = Objects.requireNonNull(fence, "roleFence");
        return this;
    }

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
                      escrow_guild_funds BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (bounty_id)
                    )
                    """);
            try {
                st.execute("ALTER TABLE guild_war_bounty ADD COLUMN escrow_guild_funds BIGINT NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {
                // 列已存在（新表或已升级表）时忽略
            }
            try {
                st.execute("CREATE INDEX idx_guild_war_bounty_escrow_op ON guild_war_bounty (escrow_op_id)");
            } catch (SQLException ignored) {
                // 索引已存在时忽略
            }
            try {
                st.execute("CREATE INDEX idx_guild_war_bounty_status_resolved ON guild_war_bounty (status, resolved_at_ms)");
            } catch (SQLException ignored) {
                // 索引已存在时忽略
            }
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_war_credit_owed (
                      player_id BIGINT NOT NULL,
                      op_id BIGINT NOT NULL,
                      guild_id BIGINT NOT NULL,
                      kind INT NOT NULL,
                      tx_type INT NOT NULL,
                      ref_id INT NOT NULL,
                      period_key INT NOT NULL,
                      payload BLOB NOT NULL,
                      next_attempt_ms BIGINT NOT NULL DEFAULT 0,
                      created_ms BIGINT NOT NULL DEFAULT 0,
                      updated_ms BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (player_id, op_id)
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
            st.execute("""
                    CREATE TABLE IF NOT EXISTS guild_war_feud (
                      feud_id BIGINT NOT NULL,
                      zone_id INT NOT NULL,
                      issuer_guild_id BIGINT NOT NULL,
                      issuer_guild_name VARCHAR(64) NOT NULL DEFAULT '',
                      target_guild_id BIGINT NOT NULL,
                      target_guild_name VARCHAR(64) NOT NULL DEFAULT '',
                      status INT NOT NULL DEFAULT 1,
                      issuer_wins INT NOT NULL DEFAULT 0,
                      target_wins INT NOT NULL DEFAULT 0,
                      issuer_merit BIGINT NOT NULL DEFAULT 0,
                      target_merit BIGINT NOT NULL DEFAULT 0,
                      created_at_ms BIGINT NOT NULL DEFAULT 0,
                      accept_deadline_ms BIGINT NOT NULL DEFAULT 0,
                      started_at_ms BIGINT NOT NULL DEFAULT 0,
                      ends_at_ms BIGINT NOT NULL DEFAULT 0,
                      winner_guild_id BIGINT NOT NULL DEFAULT 0,
                      updated_ms BIGINT NOT NULL DEFAULT 0,
                      PRIMARY KEY (feud_id)
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
                     "SELECT " + BOUNTY_COLS + " FROM guild_war_bounty WHERE zone_id = ? AND status = 2 AND expires_at_ms > ? AND (public_board = 1 OR issuer_guild_id = ?) ORDER BY created_at_ms DESC LIMIT 50")) {
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
                     "SELECT " + BOUNTY_COLS + " FROM guild_war_bounty WHERE target_player_id = ? AND status IN (1, 2) AND expires_at_ms > ? LIMIT 1")) {
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
                     "SELECT " + BOUNTY_COLS + " FROM guild_war_bounty WHERE bounty_id = ?")) {
            ps.setLong(1, bountyId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(readBounty(rs));
                }
            }
        }
        return Optional.empty();
    }

    public List<BountyRow> listExpiredActiveBounties(long nowMs, int limit) throws SQLException {
        if (limit <= 0) {
            return List.of();
        }
        List<BountyRow> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT " + BOUNTY_COLS + " FROM guild_war_bounty WHERE status = 2 AND expires_at_ms <= ? ORDER BY expires_at_ms ASC LIMIT ?")) {
            ps.setLong(1, nowMs);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(readBounty(rs));
                }
            }
        }
        return out;
    }

    /** 到期 ACTIVE 追杀令收尾（r2-gw#2）：同事务置 EXPIRED(5) 并原路退回托管（银两档退发布者、公费档退发单帮资金）。 */
    public ExpireBountyResult expireBountyWithRefund(BountyRow candidate, long refundOpId, AssetBundle refundBundle,
                                                     long leaseUntilMs, long leaseToken, long nowMs) throws SQLException {
        if (candidate == null || candidate.bountyId() == 0L) {
            return new ExpireBountyResult(false, null);
        }
        boolean isGuildPublic = candidate.tier() == GuildWarBountyTier.GUILD_WAR_BOUNTY_TIER_GUILD_PUBLIC_VALUE;
        long refundFunds = isGuildPublic ? candidate.escrowGuildFunds() : 0L;
        long refundSilver = isGuildPublic ? 0L : candidate.escrowSilver();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // 严格锁序：P(护栏副本) -> G(公费档退资金) -> M -> Q -> O(银两档退款指令) -> 17(guild_war_bounty 点锁复核) -> 24(待入账)
                CreditFenceState issuerFence = (refundSilver > 0 && candidate.issuerPlayerId() != 0)
                        ? roleFence.classifyInTx(c, candidate.issuerPlayerId())
                        : CreditFenceState.NORMAL;
                if (refundFunds > 0 && candidate.issuerGuildId() != 0) {
                    creditGuildFundsTx(c, candidate.issuerGuildId(), refundFunds);
                }
                AssetOp refundOp = null;
                boolean deferRefundToOwed = false;
                if (refundSilver > 0 && refundOpId != 0 && refundBundle != null && candidate.issuerPlayerId() != 0
                        && issuerFence != CreditFenceState.DELETED) {
                    if (candidate.issuerGuildId() != 0) {
                        lockGuildMemberRoleTx(c, candidate.issuerGuildId(), candidate.issuerPlayerId());
                    }
                    if (issuerFence == CreditFenceState.FROZEN) {
                        deferRefundToOwed = true;
                    } else {
                        refundOp = enqueueWarAssetOpTx(c, refundOpId, candidate.issuerPlayerId(), candidate.issuerGuildId(),
                                AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE,
                                GuildAssetOpKind.GUILD_ASSET_OP_KIND_BOUNTY_PAYOUT,
                                TransactionType.TX_GUILD_BOUNTY_PAYOUT_VALUE,
                                refundBundle, 1, 1, 0, 0L, leaseUntilMs, leaseToken, nowMs);
                        if (refundOp == null) {
                            // r2-gw#5：未决满窗时改为落待入账表，后续循环用同一 op_id 补插，不阻断追杀令到期收尾
                            deferRefundToOwed = true;
                        }
                    }
                }
                BountyRow locked = loadBountyForUpdateTx(c, candidate.bountyId());
                if (locked == null || locked.status() != 2 || Long.compareUnsigned(locked.expiresAtMs(), nowMs) > 0) {
                    c.rollback();
                    return new ExpireBountyResult(false, null);
                }
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE guild_war_bounty SET status = 5, resolved_at_ms = ? WHERE bounty_id = ? AND status = 2")) {
                    upd.setLong(1, nowMs);
                    upd.setLong(2, candidate.bountyId());
                    if (upd.executeUpdate() != 1) {
                        c.rollback();
                        return new ExpireBountyResult(false, null);
                    }
                }
                if (deferRefundToOwed) {
                    insertWarCreditOwedTx(c, candidate.issuerPlayerId(), refundOpId, candidate.issuerGuildId(),
                            GuildAssetOpKind.GUILD_ASSET_OP_KIND_BOUNTY_PAYOUT,
                            TransactionType.TX_GUILD_BOUNTY_PAYOUT_VALUE, 1, 0, refundBundle, nowMs);
                }
                c.commit();
                return new ExpireBountyResult(true, refundOp);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static BountyRow loadBountyForUpdateTx(Connection c, long bountyId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + BOUNTY_COLS + " FROM guild_war_bounty WHERE bounty_id = ? FOR UPDATE")) {
            ps.setLong(1, bountyId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readBounty(rs) : null;
            }
        }
    }

    private static void insertWarCreditOwedTx(Connection c, long playerId, long opId, long guildId,
                                              GuildAssetOpKind kind, int txType, int refId, int periodKey,
                                              AssetBundle bundle, long nowMs) throws SQLException {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO guild_war_credit_owed (player_id, op_id, guild_id, kind, tx_type, ref_id, period_key, payload, next_attempt_ms, created_ms, updated_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ins.setLong(1, playerId);
            ins.setLong(2, opId);
            ins.setLong(3, guildId);
            ins.setInt(4, kind.getNumber());
            ins.setInt(5, txType);
            ins.setInt(6, refId);
            ins.setInt(7, periodKey);
            ins.setBytes(8, bundle.toByteArray());
            ins.setLong(9, nowMs);
            ins.setLong(10, nowMs);
            ins.setLong(11, nowMs);
            ins.executeUpdate();
        }
    }

    /**
     * 后台循环补插帮战待入账表（r2-gw#5 / r2-gw#6）：
     * 每条独立短事务，严格按 P → M → Q → O → 24(待入账点锁复核删行) 执行，复用预发 {@code op_id}；
     * 已删角色直接清理待入账不建 seq/指令，冻结中或流仍满窗延后重试。
     */
    public List<AssetOp> convertCreditOwed(int limit, long nowMs, long leaseUntilMs,
                                           java.util.function.LongSupplier leaseTokens) throws SQLException {
        int cap = Math.max(1, limit);
        record Key(long playerId, long opId) {}
        List<Key> due = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_id, op_id FROM guild_war_credit_owed WHERE next_attempt_ms <= ? ORDER BY next_attempt_ms ASC, created_ms ASC, op_id ASC LIMIT ?")) {
            ps.setLong(1, nowMs);
            ps.setInt(2, cap);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    due.add(new Key(rs.getLong(1), rs.getLong(2)));
                }
            }
        }
        List<AssetOp> converted = new ArrayList<>();
        for (Key key : due) {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try {
                    CreditFenceState fence = roleFence.classifyInTx(c, key.playerId());
                    if (fence == CreditFenceState.NORMAL) {
                        // 严格遵循 P -> Q -> 24 锁序，先锁 seq 行（与 settle/expire 同向，避免潜在间隙锁成环）
                        try (PreparedStatement lockSeq = c.prepareStatement(
                                "SELECT player_id FROM guild_player_op_seq WHERE player_id = ? AND stream = ? FOR UPDATE")) {
                            lockSeq.setLong(1, key.playerId());
                            lockSeq.setInt(2, AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE);
                            try (ResultSet ignored = lockSeq.executeQuery()) {
                                // 仅加锁
                            }
                        }
                    }
                    long guildId;
                    int kindNum;
                    int txType;
                    int refId;
                    int periodKey;
                    byte[] payload;
                    try (PreparedStatement lock = c.prepareStatement(
                            "SELECT guild_id, kind, tx_type, ref_id, period_key, payload FROM guild_war_credit_owed WHERE player_id = ? AND op_id = ? FOR UPDATE")) {
                        lock.setLong(1, key.playerId());
                        lock.setLong(2, key.opId());
                        try (ResultSet rs = lock.executeQuery()) {
                            if (!rs.next()) {
                                c.rollback();
                                continue;
                            }
                            guildId = rs.getLong(1);
                            kindNum = rs.getInt(2);
                            txType = rs.getInt(3);
                            refId = rs.getInt(4);
                            periodKey = rs.getInt(5);
                            payload = rs.getBytes(6);
                        }
                    }
                    if (fence == CreditFenceState.DELETED) {
                        try (PreparedStatement del = c.prepareStatement(
                                "DELETE FROM guild_war_credit_owed WHERE player_id = ? AND op_id = ?")) {
                            del.setLong(1, key.playerId());
                            del.setLong(2, key.opId());
                            del.executeUpdate();
                        }
                        c.commit();
                        continue;
                    }
                    if (fence == CreditFenceState.FROZEN) {
                        deferOwedTx(c, key.playerId(), key.opId(), nowMs + 5_000L, nowMs);
                        c.commit();
                        continue;
                    }
                    AssetBundle bundle;
                    try {
                        bundle = AssetBundle.parseFrom(payload);
                    } catch (com.google.protobuf.InvalidProtocolBufferException bad) {
                        deferOwedTx(c, key.playerId(), key.opId(), nowMs + 60_000L, nowMs);
                        c.commit();
                        continue;
                    }
                    GuildAssetOpKind kind = GuildAssetOpKind.forNumber(kindNum);
                    if (kind == null) {
                        kind = GuildAssetOpKind.GUILD_ASSET_OP_KIND_WAR_REWARD;
                    }
                    AssetOp op = enqueueWarAssetOpTx(c, key.opId(), key.playerId(), guildId,
                            AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE, kind, txType,
                            bundle, refId, 1, periodKey, 0L, leaseUntilMs,
                            leaseTokens != null ? leaseTokens.getAsLong() : 0L, nowMs);
                    if (op == null) {
                        deferOwedTx(c, key.playerId(), key.opId(), nowMs + 5_000L, nowMs);
                        c.commit();
                        continue;
                    }
                    int deleted;
                    try (PreparedStatement del = c.prepareStatement(
                            "DELETE FROM guild_war_credit_owed WHERE player_id = ? AND op_id = ?")) {
                        del.setLong(1, key.playerId());
                        del.setLong(2, key.opId());
                        deleted = del.executeUpdate();
                    }
                    if (deleted != 1) {
                        c.rollback();
                        continue;
                    }
                    c.commit();
                    converted.add(op);
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }
        return converted;
    }

    private static void deferOwedTx(Connection c, long playerId, long opId, long nextAttemptMs, long nowMs)
            throws SQLException {
        try (PreparedStatement upd = c.prepareStatement(
                "UPDATE guild_war_credit_owed SET next_attempt_ms = ?, updated_ms = ? WHERE player_id = ? AND op_id = ?")) {
            upd.setLong(1, nextAttemptMs);
            upd.setLong(2, nowMs);
            upd.setLong(3, playerId);
            upd.setLong(4, opId);
            upd.executeUpdate();
        }
    }

    /** 清理超过 30 天保留期的终态追杀令（r2-gw#7）：一行一短事务，主键点锁复核终态(3/4/5/6)后删，绝不删状态 1/2。 */
    public int purgeTerminalBounties(long nowMs, int limit) throws SQLException {
        long cutoff = nowMs - TERMINAL_BOUNTY_RETENTION_MS;
        if (cutoff <= 0L) {
            return 0;
        }
        int cap = Math.max(1, limit);
        List<Long> candidates = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT bounty_id FROM guild_war_bounty WHERE status IN (3, 4, 5, 6) AND resolved_at_ms > 0 AND resolved_at_ms <= ? ORDER BY resolved_at_ms ASC, bounty_id ASC LIMIT ?")) {
            ps.setLong(1, cutoff);
            ps.setInt(2, cap);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(rs.getLong(1));
                }
            }
        }
        int deleted = 0;
        for (long bountyId : candidates) {
            try (Connection c = dataSource.getConnection()) {
                c.setAutoCommit(false);
                try {
                    Integer status = null;
                    long resolvedAt = 0L;
                    try (PreparedStatement lock = c.prepareStatement(
                            "SELECT status, resolved_at_ms FROM guild_war_bounty WHERE bounty_id = ? FOR UPDATE")) {
                        lock.setLong(1, bountyId);
                        try (ResultSet rs = lock.executeQuery()) {
                            if (rs.next()) {
                                status = rs.getInt(1);
                                resolvedAt = rs.getLong(2);
                            }
                        }
                    }
                    if (status == null || (status != 3 && status != 4 && status != 5 && status != 6)
                            || resolvedAt <= 0L || Long.compareUnsigned(resolvedAt, cutoff) > 0) {
                        c.rollback();
                        continue;
                    }
                    try (PreparedStatement del = c.prepareStatement(
                            "DELETE FROM guild_war_bounty WHERE bounty_id = ?")) {
                        del.setLong(1, bountyId);
                        deleted += del.executeUpdate();
                    }
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }
        return deleted;
    }

    /** 检查角色在帮战待入账表里是否仍有挂起项（用于角色过户/注销挂起全集校验 r2-gw#6）。 */
    public long countOwedCredits(long playerId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COUNT(*) FROM guild_war_credit_owed WHERE player_id = ?")) {
            ps.setLong(1, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public PostBountyResult postBountyWithAsset(long bountyId, int zoneId, int dayKey, long issuerPlayerId,
                                                long issuerGuildId, long targetPlayerId, long targetGuildId,
                                                GuildWarBountyTier tier, GuildWarRules.BountyTierSpec spec,
                                                boolean publicBoard, int pubDailyLimit, int ttlSec, long nowMs,
                                                long opId, AssetBundle debitBundle, long leaseUntilMs,
                                                long leaseToken) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // 严格锁序：G(公费扣帮会资金) -> M(发起人成员行) -> Q(seq) -> O(asset_op) -> C(公费日限) -> 16(target) -> 17(bounty)
                if (spec.costGuildFunds() > 0) {
                    Long funds = null;
                    try (PreparedStatement ps = c.prepareStatement("SELECT funds FROM guild WHERE guild_id = ? FOR UPDATE")) {
                        ps.setLong(1, issuerGuildId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) funds = rs.getLong(1);
                        }
                    }
                    if (funds == null) {
                        c.rollback();
                        throw new WarRuleException("not_member");
                    }
                    if (Long.compareUnsigned(funds, spec.costGuildFunds()) < 0) {
                        c.rollback();
                        throw new WarRuleException("funds_insufficient");
                    }
                    try (PreparedStatement upd = c.prepareStatement("UPDATE guild SET funds = funds - ? WHERE guild_id = ?")) {
                        upd.setLong(1, spec.costGuildFunds());
                        upd.setLong(2, issuerGuildId);
                        upd.executeUpdate();
                    }
                }
                Integer role = lockGuildMemberRoleTx(c, issuerGuildId, issuerPlayerId);
                if (role == null) {
                    c.rollback();
                    throw new WarRuleException("not_member");
                }
                if (spec.requiresOfficer() && !GuildRoles.rank(role).atLeast(GuildRoles.Rank.OFFICER)) {
                    c.rollback();
                    throw new WarRuleException("rank_too_low");
                }
                int initialStatus = 2;
                long escrowOpId = 0L;
                AssetOp escrowOp = null;
                if (spec.costSilver() > 0 && opId != 0 && debitBundle != null) {
                    escrowOp = enqueueWarAssetOpTx(c, opId, issuerPlayerId, issuerGuildId,
                            AssetStream.ASSET_STREAM_GUILD_DEBIT_VALUE,
                            GuildAssetOpKind.GUILD_ASSET_OP_KIND_BOUNTY_ESCROW,
                            TransactionType.TX_GUILD_BOUNTY_ESCROW_VALUE,
                            debitBundle, tier.getNumber(), 1, dayKey, nowMs + 60_000L, leaseUntilMs, leaseToken, nowMs);
                    if (escrowOp == null) {
                        c.rollback();
                        throw new WarRuleException("asset_pending");
                    }
                    initialStatus = 1; // ESCROW_PENDING，由 JdbcGuildAssetStore 终结后翻 ACTIVE(2) 或 EXPIRED(5)
                    escrowOpId = opId;
                } else if (spec.costGuildFunds() > 0 && pubDailyLimit > 0) {
                    if (!incrementDailyCounterTx(c, issuerGuildId,
                            GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_WAR_BOUNTY_PUBLIC, 1, dayKey, pubDailyLimit, nowMs)) {
                        c.rollback();
                        throw new WarRuleException("bounty_limit");
                    }
                }
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
                        "INSERT INTO guild_war_bounty (bounty_id, zone_id, issuer_player_id, issuer_guild_id, target_player_id, target_guild_id, tier, escrow_silver, tax_silver, public_board, status, counter_kills, counter_killers_json, escrow_op_id, created_at_ms, expires_at_ms, resolved_at_ms, winner_player_id, escrow_guild_funds) VALUES (?,?,?,?,?,?,?,?,?,?,?,0,'',?,?,?,0,0,?)")) {
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
                    ins.setLong(15, spec.costGuildFunds());
                    ins.executeUpdate();
                }
                c.commit();
                BountyRow row = new BountyRow(bountyId, zoneId, issuerPlayerId, issuerGuildId, targetPlayerId, targetGuildId,
                        tier.getNumber(), spec.poolSilver(), spec.taxSilver(), publicBoard ? 1 : 0, initialStatus,
                        0, "", escrowOpId, nowMs, expiresAtMs, 0L, 0L, spec.costGuildFunds());
                return new PostBountyResult(row, escrowOp);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
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
                        "INSERT INTO guild_war_bounty (bounty_id, zone_id, issuer_player_id, issuer_guild_id, target_player_id, target_guild_id, tier, escrow_silver, tax_silver, public_board, status, counter_kills, counter_killers_json, escrow_op_id, created_at_ms, expires_at_ms, resolved_at_ms, winner_player_id, escrow_guild_funds) VALUES (?,?,?,?,?,?,?,?,?,?,?,0,'',?,?,?,0,0,?)")) {
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
                    ins.setLong(15, spec.costGuildFunds());
                    ins.executeUpdate();
                }
                c.commit();
                return new BountyRow(bountyId, zoneId, issuerPlayerId, issuerGuildId, targetPlayerId, targetGuildId,
                        tier.getNumber(), spec.poolSilver(), spec.taxSilver(), publicBoard ? 1 : 0, initialStatus,
                        0, "", escrowOpId, nowMs, expiresAtMs, 0L, 0L, spec.costGuildFunds());
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

    public MilestoneClaimResult claimMilestoneWithAsset(long playerId, long guildId, int zoneId, int weekKey,
                                                        int milestoneId, long requiredGuildMerit, long minPersonalMerit,
                                                        long contributionGain, long opId, AssetBundle rewardBundle,
                                                        long leaseUntilMs, long leaseToken, long nowMs) throws SQLException {
        if (milestoneId < 1 || milestoneId > 30) {
            throw new WarRuleException("milestone_locked");
        }
        int bit = 1 << (milestoneId - 1);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // 先普通读预检，不给明显不达标/已领的请求白分配 seq；锁内复核保强一致。
                WeeklyProgressRow preProg = readWeeklyProgressTx(c, guildId, weekKey, false);
                PlayerStateRow preSt = readPlayerStateTx(c, playerId, zoneId, weekKey, false);
                if ((preSt.milestoneClaimedMask() & bit) != 0) {
                    c.rollback();
                    throw new WarRuleException("milestone_claimed");
                }
                if (preProg.guildMerit() < requiredGuildMerit || preSt.weeklyMerit() < minPersonalMerit) {
                    c.rollback();
                    throw new WarRuleException("milestone_locked");
                }
                // 严格锁序：M -> Q -> O -> 15(weekly_progress) -> 16(player_state)
                if (lockGuildMemberRoleTx(c, guildId, playerId) == null) {
                    c.rollback();
                    throw new WarRuleException("not_member");
                }
                AssetOp op = null;
                if (opId != 0 && rewardBundle != null) {
                    op = enqueueWarAssetOpTx(c, opId, playerId, guildId,
                            AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE,
                            GuildAssetOpKind.GUILD_ASSET_OP_KIND_WAR_REWARD,
                            TransactionType.TX_GUILD_WAR_REWARD_VALUE,
                            rewardBundle, milestoneId, 1, weekKey, 0L, leaseUntilMs, leaseToken, nowMs);
                    if (op == null) {
                        c.rollback();
                        throw new WarRuleException("asset_pending");
                    }
                }
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
                creditGuildMemberContributionTx(c, guildId, playerId, contributionGain);
                c.commit();
                return new MilestoneClaimResult(nextMask, op);
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

    public DividendClaimResult claimDividendWithAsset(long playerId, long guildId, int zoneId, int weekKey,
                                                      long minPersonalMerit, int playerWeeklyCap,
                                                      long silverPerShare, long contribPerShare,
                                                      long opId, long leaseUntilMs, long leaseToken,
                                                      long nowMs) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                // 严格锁序：先普通读估算可领份数 -> M -> Q -> O -> 15(weekly_progress) -> 16(player_state)，并发份数变化回滚报冲突
                WeeklyProgressRow preProg = readWeeklyProgressTx(c, guildId, weekKey, false);
                PlayerStateRow preSt = readPlayerStateTx(c, playerId, zoneId, weekKey, false);
                if (preSt.weeklyMerit() < minPersonalMerit) {
                    c.rollback();
                    throw new WarRuleException("dividend_empty");
                }
                int estClaimable = Math.min(preProg.dividendPoolCount(), playerWeeklyCap) - preSt.dividendClaimedCount();
                if (estClaimable <= 0) {
                    c.rollback();
                    throw new WarRuleException("dividend_empty");
                }
                if (lockGuildMemberRoleTx(c, guildId, playerId) == null) {
                    c.rollback();
                    throw new WarRuleException("not_member");
                }
                AssetOp op = null;
                long silverTotal = estClaimable * silverPerShare;
                if (opId != 0 && silverTotal > 0) {
                    AssetBundle bundle = AssetBundle.newBuilder()
                            .addCurrencies(AssetCurrency.newBuilder()
                                    .setCurrencyType(CurrencyType.CURRENCY_TYPE_GOLD_VALUE)
                                    .setAmount(silverTotal))
                            .build();
                    op = enqueueWarAssetOpTx(c, opId, playerId, guildId,
                            AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE,
                            GuildAssetOpKind.GUILD_ASSET_OP_KIND_WAR_REWARD,
                            TransactionType.TX_GUILD_WAR_REWARD_VALUE,
                            bundle, weekKey, estClaimable, weekKey, 0L, leaseUntilMs, leaseToken, nowMs);
                    if (op == null) {
                        c.rollback();
                        throw new WarRuleException("asset_pending");
                    }
                }
                WeeklyProgressRow prog = lockOrDefaultWeeklyProgressTx(c, guildId, weekKey);
                PlayerStateRow st = lockOrDefaultPlayerStateTx(c, playerId, zoneId, weekKey);
                if (st.weeklyMerit() < minPersonalMerit) {
                    c.rollback();
                    throw new WarRuleException("dividend_empty");
                }
                int realClaimable = Math.min(prog.dividendPoolCount(), playerWeeklyCap) - st.dividendClaimedCount();
                if (realClaimable <= 0) {
                    c.rollback();
                    throw new WarRuleException("dividend_empty");
                }
                if (op != null && realClaimable != estClaimable) {
                    c.rollback();
                    throw new WarRuleException("write_conflict");
                }
                PlayerStateRow updated = new PlayerStateRow(st.playerId(), zoneId, st.peaceMode(), st.peaceReadyAtMs(),
                        st.openChallenge(), st.protectUntilMs(), st.streakCount(), st.streakDistinctCount(),
                        st.lastStreakOpponentId(), weekKey, st.weeklyMerit(), st.milestoneClaimedMask(),
                        st.dividendClaimedCount() + realClaimable, st.bountyImmunityUntilMs(),
                        st.bountyPayoutDayKey(), st.bountyPayoutCountToday(), st.nextGarrisonRefreshMs(),
                        st.nextTrackAtMs(), nowMs);
                upsertPlayerState(c, updated);
                creditGuildMemberContributionTx(c, guildId, playerId, realClaimable * contribPerShare);
                c.commit();
                return new DividendClaimResult(realClaimable, op);
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
                                List<Long> defenderIds, LeylineBasis leylineBasis, boolean capturedLeyline,
                                long nextShieldUntilMs, BountySettlePlan bountyPlan, long bountyImmunitySec,
                                long protectSec, long nowMs) throws SQLException {
        return settleBattleWithAssets(battle, weekKey, dayKey, attackerIds, defenderIds, leylineBasis, capturedLeyline,
                nextShieldUntilMs, bountyPlan, bountyImmunitySec, protectSec, 0L, 0L, List.of(), 0L, nowMs).settled();
    }

    /** 完整带资产通道的幂等终局结算（严格锁序：G -> M -> Q -> O -> C -> 13..19，零跨事务丢赏金与丢更新）。 */
    public SettleBattleResult settleBattleWithAssets(BattleRow battle, int weekKey, int dayKey, List<Long> attackerIds,
                                                     List<Long> defenderIds, LeylineBasis leylineBasis,
                                                     boolean capturedLeyline, long nextShieldUntilMs,
                                                     BountySettlePlan bountyPlan, long bountyImmunitySec,
                                                     long protectSec, long attackerGuildFunds, long defenderGuildFunds,
                                                     List<PlayerBattleCredit> credits, long leaseUntilMs,
                                                     long nowMs) throws SQLException {
        BountySettlePlan plan = bountyPlan == null ? BountySettlePlan.NONE : bountyPlan;
        List<AssetOp> createdOps = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement exists = c.prepareStatement(
                        "SELECT 1 FROM guild_war_battle WHERE battle_id = ?")) {
                    exists.setLong(1, battle.battleId());
                    try (ResultSet rs = exists.executeQuery()) {
                        if (rs.next()) {
                            c.rollback();
                            return new SettleBattleResult(false, false, List.of());
                        }
                    }
                }
                long g1 = battle.attackerGuildId();
                long g2 = battle.defenderGuildId();
                // 0. 护栏副本首锁（r2-gw#6：按 player_id 无符号升序；已删不写、冻结记待入账；领赏人已删保持追杀令 ACTIVE 等到期退发布者）
                java.util.TreeSet<Long> fenceTargets = new java.util.TreeSet<>(Long::compareUnsigned);
                if (attackerIds != null) {
                    for (long pid : attackerIds) if (pid != 0L) fenceTargets.add(pid);
                }
                if (defenderIds != null) {
                    for (long pid : defenderIds) if (pid != 0L) fenceTargets.add(pid);
                }
                if (credits != null) {
                    for (PlayerBattleCredit cr : credits) if (cr.playerId() != 0L) fenceTargets.add(cr.playerId());
                }
                if (plan.touch() && plan.resolved()) {
                    if (plan.winnerPlayerId() != 0L) fenceTargets.add(plan.winnerPlayerId());
                    if (plan.issuerPlayerId() != 0L) fenceTargets.add(plan.issuerPlayerId());
                }
                java.util.Map<Long, CreditFenceState> fenceStates = new java.util.HashMap<>(fenceTargets.size() * 2 + 1);
                for (long pid : fenceTargets) {
                    fenceStates.put(pid, roleFence.classifyInTx(c, pid));
                }
                if (plan.touch() && plan.resolved() && plan.rewardSilver() > 0 && plan.winnerPlayerId() != 0L
                        && fenceStates.get(plan.winnerPlayerId()) == CreditFenceState.DELETED) {
                    c.rollback();
                    throw new WarRuleException("bounty_winner_deleted");
                }
                // 0a. 帮会资金入账（G：合并参战资金与公费撕单退一半资金，按 guild_id 无符号升序锁表防互锁）
                java.util.TreeMap<Long, Long> guildFunds = new java.util.TreeMap<>(Long::compareUnsigned);
                if (attackerGuildFunds > 0 && g1 != 0) guildFunds.merge(g1, attackerGuildFunds, Long::sum);
                if (defenderGuildFunds > 0 && g2 != 0) guildFunds.merge(g2, defenderGuildFunds, Long::sum);
                if (plan.touch() && plan.resolved() && plan.issuerRefundGuildFunds() > 0 && plan.issuerGuildId() != 0) {
                    guildFunds.merge(plan.issuerGuildId(), plan.issuerRefundGuildFunds(), Long::sum);
                }
                for (var entry : guildFunds.entrySet()) {
                    creditGuildFundsTx(c, entry.getKey(), entry.getValue());
                }
                // 0b. 锁成员行并记帮贡（M：按 (guild_id, player_id) 无符号升序，跳过已删角色）
                record MemberLock(long guildId, long playerId, long contrib) {}
                List<MemberLock> memberLocks = new ArrayList<>();
                if (credits != null) {
                    for (PlayerBattleCredit cr : credits) {
                        if (cr.guildId() != 0 && cr.playerId() != 0
                                && fenceStates.get(cr.playerId()) != CreditFenceState.DELETED) {
                            memberLocks.add(new MemberLock(cr.guildId(), cr.playerId(), cr.contributionGain()));
                        }
                    }
                }
                if (plan.touch() && plan.resolved()) {
                    if (plan.rewardSilver() > 0 && plan.rewardOpId() != 0 && plan.rewardBundle() != null
                            && plan.winnerGuildId() != 0 && plan.winnerPlayerId() != 0
                            && fenceStates.get(plan.winnerPlayerId()) != CreditFenceState.DELETED) {
                        memberLocks.add(new MemberLock(plan.winnerGuildId(), plan.winnerPlayerId(), 0L));
                    }
                    if (plan.issuerRefundSilver() > 0 && plan.issuerRefundOpId() != 0 && plan.issuerRefundBundle() != null
                            && plan.issuerGuildId() != 0 && plan.issuerPlayerId() != 0
                            && fenceStates.get(plan.issuerPlayerId()) != CreditFenceState.DELETED) {
                        memberLocks.add(new MemberLock(plan.issuerGuildId(), plan.issuerPlayerId(), 0L));
                    }
                }
                memberLocks.sort(Comparator.comparing(MemberLock::guildId, Long::compareUnsigned)
                        .thenComparing(MemberLock::playerId, Long::compareUnsigned));
                for (MemberLock ml : memberLocks) {
                    Integer r = lockGuildMemberRoleTx(c, ml.guildId(), ml.playerId());
                    if (r != null && ml.contrib() > 0) {
                        creditGuildMemberContributionTx(c, ml.guildId(), ml.playerId(), ml.contrib());
                    }
                }
                // 0c. 参战银两与追杀赏金/撕单退款资产指令合并按 player_id 无符号升序入队（Q -> O；满窗/冻结改落待入账表，已删跳过，不整笔拒绝 r2-gw#5/#6）
                record AssetCredit(long playerId, long guildId, long opId, AssetBundle bundle, long leaseToken,
                                   GuildAssetOpKind kind, int txType, int refId, int periodKey) {}
                List<AssetCredit> assetCredits = new ArrayList<>();
                if (credits != null) {
                    for (PlayerBattleCredit cr : credits) {
                        if (cr.silverGain() > 0 && cr.opId() != 0 && cr.bundle() != null) {
                            assetCredits.add(new AssetCredit(cr.playerId(), cr.guildId(), cr.opId(), cr.bundle(),
                                    cr.leaseToken(), GuildAssetOpKind.GUILD_ASSET_OP_KIND_WAR_REWARD,
                                    TransactionType.TX_GUILD_WAR_REWARD_VALUE, battle.warBattleKind(), weekKey));
                        }
                    }
                }
                if (plan.touch() && plan.resolved()) {
                    if (plan.rewardSilver() > 0 && plan.rewardOpId() != 0 && plan.rewardBundle() != null
                            && plan.winnerPlayerId() != 0) {
                        assetCredits.add(new AssetCredit(plan.winnerPlayerId(), plan.winnerGuildId(),
                                plan.rewardOpId(), plan.rewardBundle(), plan.rewardLeaseToken(),
                                GuildAssetOpKind.GUILD_ASSET_OP_KIND_BOUNTY_PAYOUT,
                                TransactionType.TX_GUILD_BOUNTY_PAYOUT_VALUE, 1, dayKey));
                    }
                    if (plan.issuerRefundSilver() > 0 && plan.issuerRefundOpId() != 0 && plan.issuerRefundBundle() != null
                            && plan.issuerPlayerId() != 0) {
                        assetCredits.add(new AssetCredit(plan.issuerPlayerId(), plan.issuerGuildId(),
                                plan.issuerRefundOpId(), plan.issuerRefundBundle(), plan.issuerRefundLeaseToken(),
                                GuildAssetOpKind.GUILD_ASSET_OP_KIND_BOUNTY_PAYOUT,
                                TransactionType.TX_GUILD_BOUNTY_PAYOUT_VALUE, 1, dayKey));
                    }
                }
                assetCredits.sort(Comparator.comparing(AssetCredit::playerId, Long::compareUnsigned));
                List<AssetCredit> deferredOwed = new ArrayList<>();
                for (AssetCredit ac : assetCredits) {
                    CreditFenceState st = fenceStates.getOrDefault(ac.playerId(), CreditFenceState.NORMAL);
                    if (st == CreditFenceState.DELETED) {
                        continue;
                    }
                    if (st == CreditFenceState.FROZEN) {
                        deferredOwed.add(ac);
                        continue;
                    }
                    AssetOp op = enqueueWarAssetOpTx(c, ac.opId(), ac.playerId(), ac.guildId(),
                            AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE, ac.kind(), ac.txType(),
                            ac.bundle(), ac.refId(), 1, ac.periodKey(), 0L, leaseUntilMs, ac.leaseToken(), nowMs);
                    if (op == null) {
                        deferredOwed.add(ac);
                    } else {
                        createdOps.add(op);
                    }
                }
                // 0d. 追杀领赏每日上限强一致计数（C：超上限回滚并抛 bounty_daily_cap，由外层重算保持追杀令 ACTIVE 不吞托管）
                if (plan.touch() && plan.resolved() && plan.rewardSilver() > 0
                        && plan.dailyPayoutLimit() > 0 && plan.winnerPlayerId() != 0) {
                    if (!incrementDailyCounterTx(c, plan.winnerPlayerId(),
                            GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_WAR_BOUNTY_PAYOUT, 1, dayKey,
                            plan.dailyPayoutLimit(), nowMs)) {
                        c.rollback();
                        throw new WarRuleException("bounty_daily_cap");
                    }
                }
                // 1. 灵脉旗势更新（锁序 13：按 leyline_id 升序锁本区全部灵脉并逐项核对事务外快照，防并发覆盖与一帮双占 r2-gw#3）
                if (leylineBasis != null && battle.leylineId() != 0 && battle.bannerShielded() == 0) {
                    LeylineRow lockedTarget = null;
                    boolean lockedOwnsOther = false;
                    try (PreparedStatement lockLey = c.prepareStatement(
                            "SELECT leyline_id, owner_guild_id, banner_hp, shield_until_ms FROM guild_war_leyline WHERE zone_id=? ORDER BY leyline_id ASC FOR UPDATE")) {
                        lockLey.setInt(1, battle.zoneId());
                        try (ResultSet rs = lockLey.executeQuery()) {
                            while (rs.next()) {
                                int lid = rs.getInt(1);
                                long owner = rs.getLong(2);
                                int hp = rs.getInt(3);
                                long shield = rs.getLong(4);
                                if (lid == leylineBasis.leylineId()) {
                                    lockedTarget = new LeylineRow(battle.zoneId(), lid, owner, hp, shield,
                                            0L, 0L, 0, 0, 0L, 0L);
                                } else if (battle.attackerGuildId() != 0 && owner == battle.attackerGuildId()) {
                                    lockedOwnsOther = true;
                                }
                            }
                        }
                    }
                    if (lockedTarget == null
                            || lockedTarget.ownerGuildId() != leylineBasis.ownerGuildId()
                            || lockedTarget.bannerHp() != leylineBasis.bannerHp()
                            || lockedTarget.shieldUntilMs() != leylineBasis.shieldUntilMs()
                            || lockedOwnsOther != leylineBasis.attackerOwnsOther()) {
                        c.rollback();
                        throw new WarRuleException("stale_snapshot");
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
                // 2. 周战功累计（锁序 15：按 guild_id 升序更新，防两帮互打成环死锁）
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
                // 3. 玩家战功、战后保护、免战冷却、连胜、追杀免疫与当日领赏计数（锁序 16，按 player_id 无符号升序，杜绝 17->16 逆序与日切清零错写）
                boolean atkWon = battle.outcome() == 1;
                boolean defWon = battle.outcome() == 2;
                long oppOfAtk = defenderIds.isEmpty() ? 0L : defenderIds.get(0);
                long oppOfDef = attackerIds.isEmpty() ? 0L : attackerIds.get(0);
                long bountyTargetId = (plan.touch() && plan.resolved() && !defenderIds.isEmpty() && bountyImmunitySec > 0)
                        ? defenderIds.get(0) : 0L;
                long bountyPayoutWinnerId = (plan.touch() && plan.resolved() && plan.rewardSilver() > 0)
                        ? plan.winnerPlayerId() : 0L;
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
                    if (fenceStates.get(pid) == CreditFenceState.DELETED) {
                        continue;
                    }
                    boolean isAtk = atkSet.contains(pid);
                    long immunityUntil = (bountyTargetId != 0L && pid == bountyTargetId)
                            ? nowMs + bountyImmunitySec * 1000L : 0L;
                    boolean incPayout = bountyPayoutWinnerId != 0L && pid == bountyPayoutWinnerId;
                    updateParticipantState(c, pid, battle.zoneId(), weekKey, dayKey,
                            isAtk ? battle.attackerMerit() : battle.defenderMerit(),
                            isAtk ? atkWon : defWon,
                            isAtk ? defWon : atkWon,
                            isAtk ? oppOfAtk : oppOfDef,
                            protectSec, immunityUntil, incPayout, nowMs);
                }
                // 4. 追杀令推进 / 结单 / 反杀撕单（锁序 17：仅 plan.touch=true 才锁写，行锁内复核原快照防并发覆盖与清零反杀数）
                int bountyUpdated = 0;
                if (plan.touch() && battle.bountyId() != 0) {
                    BountyRow locked = loadBountyForUpdateTx(c, battle.bountyId());
                    if (locked == null || locked.status() != 2
                            || Long.compareUnsigned(locked.expiresAtMs(), nowMs) <= 0
                            || locked.counterKills() != plan.expectedCounterKills()
                            || !Objects.equals(locked.counterKillersJson(),
                            plan.expectedCounterKillersJson() == null ? "" : plan.expectedCounterKillersJson())) {
                        c.rollback();
                        throw new WarRuleException("stale_snapshot");
                    }
                    try (PreparedStatement upd = c.prepareStatement(
                            "UPDATE guild_war_bounty SET status=?, counter_kills=?, counter_killers_json=?, resolved_at_ms=?, winner_player_id=? WHERE bounty_id=? AND status=2")) {
                        upd.setInt(1, plan.nextStatus());
                        upd.setInt(2, plan.nextCounterKills());
                        upd.setString(3, plan.nextCounterKillersJson() == null ? "" : plan.nextCounterKillersJson());
                        upd.setLong(4, plan.resolved() ? nowMs : 0L);
                        upd.setLong(5, plan.winnerPlayerId());
                        upd.setLong(6, battle.bountyId());
                        bountyUpdated = upd.executeUpdate();
                    }
                    if (bountyUpdated != 1) {
                        c.rollback();
                        throw new WarRuleException("stale_snapshot");
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
                // 7. 落盘待入账表（锁序 24，在所有原表之后；循环用预发 op_id 幂等补插 guild_asset_op）
                for (AssetCredit owed : deferredOwed) {
                    insertWarCreditOwedTx(c, owed.playerId(), owed.opId(), owed.guildId(),
                            owed.kind(), owed.txType(), owed.refId(), owed.periodKey(), owed.bundle(), nowMs);
                }
                c.commit();
                return new SettleBattleResult(true, bountyUpdated == 1, createdOps);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** 追杀/反杀赏金独立入账（受每日领赏上限约束，严格锁序 M -> Q -> O -> C -> 16）。 */
    public Optional<AssetOp> enqueueBountyPayout(long playerId, long guildId, int zoneId, int weekKey, int dayKey,
                                                 int dailyPayoutLimit, long opId, AssetBundle bundle,
                                                 long leaseUntilMs, long leaseToken, long nowMs) throws SQLException {
        if (playerId == 0 || opId == 0 || bundle == null) {
            return Optional.empty();
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (guildId != 0) {
                    lockGuildMemberRoleTx(c, guildId, playerId);
                }
                AssetOp op = enqueueWarAssetOpTx(c, opId, playerId, guildId,
                        AssetStream.ASSET_STREAM_GUILD_CREDIT_VALUE,
                        GuildAssetOpKind.GUILD_ASSET_OP_KIND_BOUNTY_PAYOUT,
                        TransactionType.TX_GUILD_BOUNTY_PAYOUT_VALUE,
                        bundle, 1, 1, dayKey, 0L, leaseUntilMs, leaseToken, nowMs);
                if (op == null) {
                    c.rollback();
                    return Optional.empty();
                }
                if (dailyPayoutLimit > 0 && !incrementDailyCounterTx(c, playerId,
                        GuildDailyCounterKind.GUILD_DAILY_COUNTER_KIND_WAR_BOUNTY_PAYOUT, 1, dayKey,
                        dailyPayoutLimit, nowMs)) {
                    c.rollback();
                    return Optional.empty();
                }
                PlayerStateRow st = lockOrDefaultPlayerStateTx(c, playerId, zoneId, weekKey);
                int todayCount = st.bountyPayoutDayKey() == dayKey ? st.bountyPayoutCountToday() + 1 : 1;
                upsertPlayerState(c, new PlayerStateRow(st.playerId(), zoneId, st.peaceMode(), st.peaceReadyAtMs(),
                        st.openChallenge(), st.protectUntilMs(), st.streakCount(), st.streakDistinctCount(),
                        st.lastStreakOpponentId(), weekKey, st.weeklyMerit(), st.milestoneClaimedMask(),
                        st.dividendClaimedCount(), st.bountyImmunityUntilMs(), dayKey, todayCount,
                        st.nextGarrisonRefreshMs(), st.nextTrackAtMs(), nowMs));
                c.commit();
                return Optional.of(op);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static Integer lockGuildMemberRoleTx(Connection c, long guildId, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT role FROM guild_member WHERE guild_id = ? AND player_id = ? FOR UPDATE")) {
            ps.setLong(1, guildId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : null;
            }
        }
    }

    private static void creditGuildMemberContributionTx(Connection c, long guildId, long playerId, long delta) throws SQLException {
        if (delta <= 0 || guildId == 0 || playerId == 0) {
            return;
        }
        try (PreparedStatement upd = c.prepareStatement(
                "UPDATE guild_member SET contribution_balance = contribution_balance + ?, contribution_total = contribution_total + ? WHERE guild_id = ? AND player_id = ?")) {
            upd.setLong(1, delta);
            upd.setLong(2, delta);
            upd.setLong(3, guildId);
            upd.setLong(4, playerId);
            upd.executeUpdate();
        }
    }

    private static void creditGuildFundsTx(Connection c, long guildId, long delta) throws SQLException {
        if (delta <= 0 || guildId == 0) {
            return;
        }
        boolean exists;
        try (PreparedStatement lock = c.prepareStatement("SELECT funds FROM guild WHERE guild_id = ? FOR UPDATE")) {
            lock.setLong(1, guildId);
            try (ResultSet rs = lock.executeQuery()) {
                exists = rs.next();
            }
        }
        if (exists) {
            try (PreparedStatement upd = c.prepareStatement("UPDATE guild SET funds = funds + ? WHERE guild_id = ?")) {
                upd.setLong(1, delta);
                upd.setLong(2, guildId);
                upd.executeUpdate();
            }
        }
    }

    private static boolean incrementDailyCounterTx(Connection c, long ownerId, GuildDailyCounterKind kind,
                                                   int refId, int periodKey, int limit, long nowMs) throws SQLException {
        if (limit <= 0) {
            return true;
        }
        Integer used = null;
        try (PreparedStatement lock = c.prepareStatement(
                "SELECT used_count FROM guild_daily_counter WHERE player_id = ? AND counter_kind = ? AND ref_id = ? AND period_key = ? FOR UPDATE")) {
            lock.setLong(1, ownerId);
            lock.setInt(2, kind.getNumber());
            lock.setInt(3, refId);
            lock.setInt(4, periodKey);
            try (ResultSet rs = lock.executeQuery()) {
                if (rs.next()) used = rs.getInt(1);
            }
        }
        if (used != null) {
            if (Integer.compareUnsigned(used, limit) >= 0) {
                return false;
            }
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE guild_daily_counter SET used_count = used_count + 1, updated_ms = ? WHERE player_id = ? AND counter_kind = ? AND ref_id = ? AND period_key = ?")) {
                upd.setLong(1, nowMs);
                upd.setLong(2, ownerId);
                upd.setInt(3, kind.getNumber());
                upd.setInt(4, refId);
                upd.setInt(5, periodKey);
                upd.executeUpdate();
            }
            return true;
        }
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO guild_daily_counter (player_id, counter_kind, ref_id, period_key, used_count, updated_ms) VALUES (?, ?, ?, ?, 1, ?)")) {
            ins.setLong(1, ownerId);
            ins.setInt(2, kind.getNumber());
            ins.setInt(3, refId);
            ins.setInt(4, periodKey);
            ins.setLong(5, nowMs);
            ins.executeUpdate();
        }
        return true;
    }

    private static AssetOp enqueueWarAssetOpTx(Connection c, long opId, long playerId, long guildId, int stream,
                                               GuildAssetOpKind kind, int txType, AssetBundle bundle,
                                               int refId, int refCount, int periodKey, long deadlineMs,
                                               long leaseUntilMs, long leaseToken, long nowMs) throws SQLException {
        boolean seqExists;
        try (PreparedStatement check = c.prepareStatement(
                "SELECT 1 FROM guild_player_op_seq WHERE player_id = ? AND stream = ?")) {
            check.setLong(1, playerId);
            check.setInt(2, stream);
            try (ResultSet rs = check.executeQuery()) {
                seqExists = rs.next();
            }
        }
        if (!seqExists) {
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT IGNORE INTO guild_player_op_seq (player_id, stream, next_seq, epoch, updated_ms) VALUES (?, ?, 1, ?, ?)")) {
                ins.setLong(1, playerId);
                ins.setInt(2, stream);
                ins.setLong(3, Math.max(1L, nowMs));
                ins.setLong(4, nowMs);
                ins.executeUpdate();
            }
        }
        long nextSeq;
        long epoch;
        try (PreparedStatement lock = c.prepareStatement(
                "SELECT next_seq, epoch FROM guild_player_op_seq WHERE player_id = ? AND stream = ? FOR UPDATE")) {
            lock.setLong(1, playerId);
            lock.setInt(2, stream);
            try (ResultSet rs = lock.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("guild_player_op_seq 行缺失 player=" + Long.toUnsignedString(playerId));
                }
                nextSeq = rs.getLong(1);
                epoch = rs.getLong(2);
            }
        }
        if (epoch == 0) {
            throw new IllegalStateException("guild_player_op_seq 纪元为 0 player=" + Long.toUnsignedString(playerId));
        }
        List<Long> pending = new ArrayList<>();
        try (PreparedStatement pend = c.prepareStatement(
                "SELECT seq FROM guild_asset_op WHERE player_id = ? AND stream = ? AND stream_epoch = ? AND status = ? ORDER BY seq LIMIT ?")) {
            pend.setLong(1, playerId);
            pend.setInt(2, stream);
            pend.setLong(3, epoch);
            pend.setInt(4, GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING_VALUE);
            pend.setInt(5, GuildLimits.ASSET_OP_MAX_PENDING + 1);
            try (ResultSet rs = pend.executeQuery()) {
                while (rs.next()) pending.add(rs.getLong(1));
            }
        }
        if (pending.size() >= GuildLimits.ASSET_OP_MAX_PENDING
                || (!pending.isEmpty() && Long.compareUnsigned(nextSeq - pending.getFirst(), GuildLimits.ASSET_OP_MAX_SPAN) >= 0)) {
            return null;
        }
        try (PreparedStatement bump = c.prepareStatement(
                "UPDATE guild_player_op_seq SET next_seq = next_seq + 1, updated_ms = ? WHERE player_id = ? AND stream = ?")) {
            bump.setLong(1, nowMs);
            bump.setLong(2, playerId);
            bump.setInt(3, stream);
            bump.executeUpdate();
        }
        GuildAssetOpRow row = GuildAssetOpRow.newBuilder()
                .setOpId(opId)
                .setPlayerId(playerId)
                .setStream(stream)
                .setSeq(nextSeq)
                .setGuildId(guildId)
                .setKind(kind)
                .setStatus(GuildAssetOpStatus.GUILD_ASSET_OP_STATUS_PENDING)
                .setNextAttemptMs(leaseUntilMs)
                .setDeadlineMs(deadlineMs)
                .setPayload(bundle.toByteString())
                .setRefId(refId)
                .setRefCount(refCount)
                .setPeriodKey(periodKey)
                .setCreatedMs(nowMs)
                .setUpdatedMs(nowMs)
                .setLeaseUntilMs(leaseUntilMs)
                .setLeaseToken(leaseToken)
                .setTxType(txType)
                .setStreamEpoch(epoch)
                .build();
        Object[] args = AssetOpColumns.insertArgs(row);
        try (PreparedStatement ins = c.prepareStatement(AssetOpColumns.INSERT)) {
            for (int i = 0; i < args.length; i++) {
                ins.setObject(i + 1, args[i]);
            }
            ins.executeUpdate();
        }
        return new AssetOp(opId, playerId, stream, nextSeq, epoch, opId, txType, bundle, 0, deadlineMs, leaseToken, 0);
    }

    public void updateStance(PlayerStateRow updated) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            upsertPlayerState(c, updated);
        }
    }

    // ── 帮战 V2.5 · 宿敌约战战书仓储 ──────────────────────────────────────────

    public FeudLookup getActiveOrPendingFeud(long guildId, long nowMs) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT feud_id, zone_id, issuer_guild_id, issuer_guild_name, target_guild_id, target_guild_name, status, issuer_wins, target_wins, issuer_merit, target_merit, created_at_ms, accept_deadline_ms, started_at_ms, ends_at_ms, winner_guild_id, updated_ms FROM guild_war_feud WHERE (issuer_guild_id = ? OR target_guild_id = ?) AND status IN (1, 2) ORDER BY created_at_ms DESC LIMIT 1")) {
            ps.setLong(1, guildId);
            ps.setLong(2, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new FeudLookup(Optional.empty(), Optional.empty());
                }
                FeudRow row = readFeud(rs);
                if (row.status() == 1 && row.acceptDeadlineMs() > 0 && Long.compareUnsigned(nowMs, row.acceptDeadlineMs()) >= 0) {
                    try (PreparedStatement upd = c.prepareStatement("UPDATE guild_war_feud SET status = 5, updated_ms = ? WHERE feud_id = ? AND status = 1")) {
                        upd.setLong(1, nowMs);
                        upd.setLong(2, row.feudId());
                        upd.executeUpdate();
                    }
                    return new FeudLookup(Optional.empty(), Optional.empty());
                }
                if (row.status() == 2 && row.endsAtMs() > 0 && Long.compareUnsigned(nowMs, row.endsAtMs()) >= 0) {
                    long winner = GuildWarRules.resolveFeudWinner(row.issuerGuildId(), row.targetGuildId(),
                            row.issuerWins(), row.targetWins(), row.issuerMerit(), row.targetMerit());
                    try (PreparedStatement upd = c.prepareStatement("UPDATE guild_war_feud SET status = 3, winner_guild_id = ?, updated_ms = ? WHERE feud_id = ? AND status = 2")) {
                        upd.setLong(1, winner);
                        upd.setLong(2, nowMs);
                        upd.setLong(3, row.feudId());
                        if (upd.executeUpdate() > 0) {
                            FeudRow fin = new FeudRow(row.feudId(), row.zoneId(), row.issuerGuildId(), row.issuerGuildName(),
                                    row.targetGuildId(), row.targetGuildName(), 3, row.issuerWins(), row.targetWins(),
                                    row.issuerMerit(), row.targetMerit(), row.createdAtMs(), row.acceptDeadlineMs(),
                                    row.startedAtMs(), row.endsAtMs(), winner, nowMs);
                            return new FeudLookup(Optional.empty(), Optional.of(fin));
                        }
                    }
                    return new FeudLookup(Optional.empty(), Optional.empty());
                }
                return new FeudLookup(Optional.of(row), Optional.empty());
            }
        }
    }

    public Optional<FeudRow> getLastFinishedFeud(long guildId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT feud_id, zone_id, issuer_guild_id, issuer_guild_name, target_guild_id, target_guild_name, status, issuer_wins, target_wins, issuer_merit, target_merit, created_at_ms, accept_deadline_ms, started_at_ms, ends_at_ms, winner_guild_id, updated_ms FROM guild_war_feud WHERE (issuer_guild_id = ? OR target_guild_id = ?) AND status = 3 ORDER BY ends_at_ms DESC LIMIT 1")) {
            ps.setLong(1, guildId);
            ps.setLong(2, guildId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readFeud(rs)) : Optional.empty();
            }
        }
    }

    public long getLastFeudIssuedAt(long issuerGuildId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT COALESCE(MAX(created_at_ms), 0) FROM guild_war_feud WHERE issuer_guild_id = ? AND status IN (1, 2, 3)")) {
            ps.setLong(1, issuerGuildId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    public FeudRow issueFeud(FeudRow rec, int cooldownSec, long nowMs) throws SQLException {
        getActiveOrPendingFeud(rec.issuerGuildId(), nowMs);
        getActiveOrPendingFeud(rec.targetGuildId(), nowMs);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COUNT(1) FROM guild_war_feud WHERE status IN (1, 2) AND (issuer_guild_id IN (?, ?) OR target_guild_id IN (?, ?))")) {
                    ps.setLong(1, rec.issuerGuildId());
                    ps.setLong(2, rec.targetGuildId());
                    ps.setLong(3, rec.issuerGuildId());
                    ps.setLong(4, rec.targetGuildId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            c.rollback();
                            throw new WarRuleException("feud_active");
                        }
                    }
                }
                long lastIssued = 0L;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT COALESCE(MAX(created_at_ms), 0) FROM guild_war_feud WHERE issuer_guild_id = ? AND status IN (1, 2, 3)")) {
                    ps.setLong(1, rec.issuerGuildId());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) lastIssued = rs.getLong(1);
                    }
                }
                if (lastIssued > 0 && Long.compareUnsigned(nowMs, lastIssued + cooldownSec * 1000L) < 0) {
                    c.rollback();
                    throw new WarRuleException("feud_cooldown");
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO guild_war_feud (feud_id, zone_id, issuer_guild_id, issuer_guild_name, target_guild_id, target_guild_name, status, issuer_wins, target_wins, issuer_merit, target_merit, created_at_ms, accept_deadline_ms, started_at_ms, ends_at_ms, winner_guild_id, updated_ms) VALUES (?,?,?,?,?,?,1,0,0,0,0,?,?,0,0,0,?)")) {
                    ins.setLong(1, rec.feudId());
                    ins.setInt(2, rec.zoneId());
                    ins.setLong(3, rec.issuerGuildId());
                    ins.setString(4, rec.issuerGuildName());
                    ins.setLong(5, rec.targetGuildId());
                    ins.setString(6, rec.targetGuildName());
                    ins.setLong(7, rec.createdAtMs());
                    ins.setLong(8, rec.acceptDeadlineMs());
                    ins.setLong(9, nowMs);
                    ins.executeUpdate();
                }
                c.commit();
                return new FeudRow(rec.feudId(), rec.zoneId(), rec.issuerGuildId(), rec.issuerGuildName(),
                        rec.targetGuildId(), rec.targetGuildName(), 1, 0, 0, 0L, 0L, rec.createdAtMs(),
                        rec.acceptDeadlineMs(), 0L, 0L, 0L, nowMs);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public FeudRow respondFeud(long feudId, long responderGuildId, boolean accept, int durationSec, long nowMs) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                FeudRow row = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT feud_id, zone_id, issuer_guild_id, issuer_guild_name, target_guild_id, target_guild_name, status, issuer_wins, target_wins, issuer_merit, target_merit, created_at_ms, accept_deadline_ms, started_at_ms, ends_at_ms, winner_guild_id, updated_ms FROM guild_war_feud WHERE feud_id = ? FOR UPDATE")) {
                    ps.setLong(1, feudId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) row = readFeud(rs);
                    }
                }
                if (row == null || row.status() != 1) {
                    c.rollback();
                    throw new WarRuleException("feud_not_found");
                }
                if (row.acceptDeadlineMs() > 0 && Long.compareUnsigned(nowMs, row.acceptDeadlineMs()) >= 0) {
                    try (PreparedStatement upd = c.prepareStatement("UPDATE guild_war_feud SET status = 5, updated_ms = ? WHERE feud_id = ?")) {
                        upd.setLong(1, nowMs);
                        upd.setLong(2, feudId);
                        upd.executeUpdate();
                    }
                    c.commit();
                    throw new WarRuleException("feud_not_found");
                }
                FeudRow updated;
                if (accept) {
                    if (responderGuildId != row.targetGuildId()) {
                        c.rollback();
                        throw new WarRuleException("feud_not_found");
                    }
                    long endsAt = nowMs + durationSec * 1000L;
                    try (PreparedStatement upd = c.prepareStatement("UPDATE guild_war_feud SET status = 2, started_at_ms = ?, ends_at_ms = ?, updated_ms = ? WHERE feud_id = ?")) {
                        upd.setLong(1, nowMs);
                        upd.setLong(2, endsAt);
                        upd.setLong(3, nowMs);
                        upd.setLong(4, feudId);
                        upd.executeUpdate();
                    }
                    updated = new FeudRow(row.feudId(), row.zoneId(), row.issuerGuildId(), row.issuerGuildName(),
                            row.targetGuildId(), row.targetGuildName(), 2, 0, 0, 0L, 0L, row.createdAtMs(),
                            row.acceptDeadlineMs(), nowMs, endsAt, 0L, nowMs);
                } else {
                    if (responderGuildId != row.targetGuildId() && responderGuildId != row.issuerGuildId()) {
                        c.rollback();
                        throw new WarRuleException("feud_not_found");
                    }
                    try (PreparedStatement upd = c.prepareStatement("UPDATE guild_war_feud SET status = 4, updated_ms = ? WHERE feud_id = ?")) {
                        upd.setLong(1, nowMs);
                        upd.setLong(2, feudId);
                        upd.executeUpdate();
                    }
                    updated = new FeudRow(row.feudId(), row.zoneId(), row.issuerGuildId(), row.issuerGuildName(),
                            row.targetGuildId(), row.targetGuildName(), 4, 0, 0, 0L, 0L, row.createdAtMs(),
                            row.acceptDeadlineMs(), 0L, 0L, 0L, nowMs);
                }
                c.commit();
                return updated;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    public Optional<FeudRow> getActiveFeudBetween(long g1, long g2, long nowMs) throws SQLException {
        if (g1 == 0 || g2 == 0 || g1 == g2) return Optional.empty();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT feud_id, zone_id, issuer_guild_id, issuer_guild_name, target_guild_id, target_guild_name, status, issuer_wins, target_wins, issuer_merit, target_merit, created_at_ms, accept_deadline_ms, started_at_ms, ends_at_ms, winner_guild_id, updated_ms FROM guild_war_feud WHERE status = 2 AND ((issuer_guild_id = ? AND target_guild_id = ?) OR (issuer_guild_id = ? AND target_guild_id = ?)) LIMIT 1")) {
            ps.setLong(1, g1);
            ps.setLong(2, g2);
            ps.setLong(3, g2);
            ps.setLong(4, g1);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                FeudRow row = readFeud(rs);
                if (row.endsAtMs() > 0 && Long.compareUnsigned(nowMs, row.endsAtMs()) >= 0) {
                    return Optional.empty();
                }
                return Optional.of(row);
            }
        }
    }

    public FeudBattleResult recordFeudBattle(long feudId, long atkGuildId, long defGuildId, boolean atkWon,
                                             boolean defWon, long atkMerit, long defMerit, long nowMs) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                FeudRow row = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT feud_id, zone_id, issuer_guild_id, issuer_guild_name, target_guild_id, target_guild_name, status, issuer_wins, target_wins, issuer_merit, target_merit, created_at_ms, accept_deadline_ms, started_at_ms, ends_at_ms, winner_guild_id, updated_ms FROM guild_war_feud WHERE feud_id = ? FOR UPDATE")) {
                    ps.setLong(1, feudId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) row = readFeud(rs);
                    }
                }
                if (row == null || row.status() != 2) {
                    c.rollback();
                    return new FeudBattleResult(Optional.empty(), false);
                }
                int iw = row.issuerWins(), tw = row.targetWins();
                long im = row.issuerMerit(), tm = row.targetMerit();
                if (atkGuildId == row.issuerGuildId() && defGuildId == row.targetGuildId()) {
                    if (atkWon) iw++; else if (defWon) tw++;
                    im += atkMerit; tm += defMerit;
                } else if (atkGuildId == row.targetGuildId() && defGuildId == row.issuerGuildId()) {
                    if (atkWon) tw++; else if (defWon) iw++;
                    tm += atkMerit; im += defMerit;
                }
                boolean finished = row.endsAtMs() > 0 && Long.compareUnsigned(nowMs, row.endsAtMs()) >= 0;
                int nextStatus = finished ? 3 : 2;
                long winner = finished ? GuildWarRules.resolveFeudWinner(row.issuerGuildId(), row.targetGuildId(), iw, tw, im, tm) : 0L;
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE guild_war_feud SET status=?, issuer_wins=?, target_wins=?, issuer_merit=?, target_merit=?, winner_guild_id=?, updated_ms=? WHERE feud_id=?")) {
                    upd.setInt(1, nextStatus);
                    upd.setInt(2, iw);
                    upd.setInt(3, tw);
                    upd.setLong(4, im);
                    upd.setLong(5, tm);
                    upd.setLong(6, winner);
                    upd.setLong(7, nowMs);
                    upd.setLong(8, feudId);
                    upd.executeUpdate();
                }
                c.commit();
                FeudRow updated = new FeudRow(row.feudId(), row.zoneId(), row.issuerGuildId(), row.issuerGuildName(),
                        row.targetGuildId(), row.targetGuildName(), nextStatus, iw, tw, im, tm, row.createdAtMs(),
                        row.acceptDeadlineMs(), row.startedAtMs(), row.endsAtMs(), winner, nowMs);
                return new FeudBattleResult(Optional.of(updated), finished);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static FeudRow readFeud(ResultSet rs) throws SQLException {
        return new FeudRow(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getString(4), rs.getLong(5),
                rs.getString(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getLong(10), rs.getLong(11),
                rs.getLong(12), rs.getLong(13), rs.getLong(14), rs.getLong(15), rs.getLong(16), rs.getLong(17));
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

    private void updateParticipantState(Connection c, long playerId, int zoneId, int weekKey, int dayKey,
                                        long meritDelta, boolean won, boolean lost, long opponentId, long protectSec,
                                        long bountyImmunityUntil, boolean incrementBountyPayout,
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
        long nextImmunity = bountyImmunityUntil > 0L
                ? Math.max(st.bountyImmunityUntilMs(), bountyImmunityUntil) : st.bountyImmunityUntilMs();
        int nextPayoutDayKey = st.bountyPayoutDayKey();
        int nextPayoutCount = st.bountyPayoutCountToday();
        if (incrementBountyPayout) {
            nextPayoutCount = st.bountyPayoutDayKey() == dayKey ? st.bountyPayoutCountToday() + 1 : 1;
            nextPayoutDayKey = dayKey;
        }
        upsertPlayerState(c, new PlayerStateRow(playerId, zoneId, st.peaceMode(), peaceReadyAt,
                st.openChallenge(), protectUntil, nextStreak, nextDistinct, nextLastOpp, weekKey,
                st.weeklyMerit() + meritDelta, st.milestoneClaimedMask(), st.dividendClaimedCount(),
                nextImmunity, nextPayoutDayKey, nextPayoutCount,
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
                rs.getLong(15), rs.getLong(16), rs.getLong(17), rs.getLong(18), rs.getLong(19));
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
