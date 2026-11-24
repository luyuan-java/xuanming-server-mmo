package com.game.match.ranked;

import com.game.common.token.NetworkProbes;
import com.game.match.feature.pb.MatchRankedBattleRow;
import com.game.match.feature.pb.MatchRankedPlayerPrefsRow;
import com.game.match.feature.pb.MatchRankedPlayerSeasonRow;
import com.game.match.feature.pb.MatchRankedRewardRow;
import com.game.match.feature.pb.MatchRankedSeasonRow;
import com.game.pbmysql.PbMysql;
import com.game.proto.RankedPresetSelection;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.RankedBattleSettlementView;
import com.game.proto.match.RankedCosmeticLoadout;
import com.game.proto.match.RankedMatchRatingStatus;
import com.game.proto.match.RankedPlayerSeasonSummary;
import com.game.proto.match.RankedRewardEntitlement;
import com.game.proto.match.RankedRewardKind;
import com.game.proto.match.RankedSeasonInfo;
import com.game.proto.match.RankedSeasonState;
import com.game.proto.match.RankedTier;
import com.game.table.RankedRuleTable;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/** 论剑排位五张权威表的存取（赛季、单排预设与饰品偏好、个人赛季进度、权益、排位战果明细）。 */
public final class RankedStore {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    public enum WriteStatus {
        OK, SEASON_SETTLING, PRESET_INVALID, RULE_VERSION_MISMATCH
    }

    public record PrefsWriteResult(WriteStatus status, MatchRankedPlayerPrefsRow row, int ruleVersion) {
    }

    public record ProfileData(MatchRankedSeasonRow currentSeason,
                              MatchRankedPlayerSeasonRow currentProgress,
                              MatchRankedPlayerPrefsRow prefs,
                              List<MatchRankedRewardRow> entitlements,
                              List<MatchRankedPlayerSeasonRow> history,
                              List<MatchRankedSeasonRow> pastSeasons) {
    }

    public record SettlementInput(long playerId, long battleId, int seasonId, int teamSize,
                                  RankedMatchRatingStatus status, eBattleOutcome outcome,
                                  int playerTeamIndex, int totalRounds, boolean participated, boolean fled,
                                  long ratingDeltaCenti, int characterPresetId, int petPresetId) {
    }

    private final DataSource dataSource;
    private final RankedTables tables;
    private final LongSupplier clockMs;

    public RankedStore(DataSource dataSource, RankedTables tables, LongSupplier clockMs) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
    }

    /** 确保至少存在一个当前赛季（空表时按 RankedRule 惰性创建 S1）。 */
    public MatchRankedSeasonRow ensureActiveSeason() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            return ensureSeason(c);
        }
    }

    public ProfileData loadProfile(long playerId, boolean includeHistory) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            MatchRankedSeasonRow current = ensureSeason(c);
            MatchRankedPlayerSeasonRow progress = readPlayerSeason(c, current.getSeasonId(), playerId)
                    .orElseGet(() -> defaultProgress(current.getSeasonId(), playerId));
            MatchRankedPlayerPrefsRow prefs = readPrefs(c, playerId).orElseGet(() -> defaultPrefs(playerId));
            List<MatchRankedRewardRow> rewards = readRewards(c, playerId);
            List<MatchRankedPlayerSeasonRow> history = List.of();
            List<MatchRankedSeasonRow> pastSeasons = List.of();
            if (includeHistory) {
                history = readPlayerHistory(c, playerId, current.getSeasonId());
                pastSeasons = readPastSeasons(c, current.getSeasonId());
            }
            return new ProfileData(current, progress, prefs, rewards, history, pastSeasons);
        }
    }

    public PrefsWriteResult savePreset(long playerId, int characterPresetId, int petPresetId, int ruleVersion) throws SQLException {
        int currentRuleVer = tables.ruleVersion();
        if (ruleVersion != 0 && ruleVersion != currentRuleVer) {
            return new PrefsWriteResult(WriteStatus.RULE_VERSION_MISMATCH, null, currentRuleVer);
        }
        if (!tables.isValidPair(characterPresetId, petPresetId)) {
            return new PrefsWriteResult(WriteStatus.PRESET_INVALID, null, currentRuleVer);
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                MatchRankedSeasonRow season = ensureSeason(c);
                if (season.getState() == RankedSeasonState.RANKED_SEASON_STATE_SETTLING_VALUE) {
                    c.rollback();
                    return new PrefsWriteResult(WriteStatus.SEASON_SETTLING, null, currentRuleVer);
                }
                MatchRankedPlayerPrefsRow existing = readPrefs(c, playerId).orElseGet(() -> defaultPrefs(playerId));
                long now = clockMs.getAsLong();
                MatchRankedPlayerPrefsRow updated = existing.toBuilder()
                        .setPlayerId(playerId)
                        .setCharacterPresetId(characterPresetId)
                        .setPetPresetId(petPresetId)
                        .setRuleVersion(currentRuleVer)
                        .setUpdatedMs(now)
                        .build();
                upsertPrefs(c, updated);
                c.commit();
                return new PrefsWriteResult(WriteStatus.OK, updated, currentRuleVer);
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    public PrefsWriteResult updateCosmetic(long playerId, RankedCosmeticLoadout loadout) throws SQLException {
        if (loadout == null) {
            return new PrefsWriteResult(WriteStatus.PRESET_INVALID, null, tables.ruleVersion());
        }
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                List<MatchRankedRewardRow> owned = readRewards(c, playerId);
                if (!ownsCode(owned, RankedRewardKind.RANKED_REWARD_TALISMAN, loadout.getEquippedTalismanCode())
                        || !ownsCode(owned, RankedRewardKind.RANKED_REWARD_AVATAR_PATTERN, loadout.getEquippedAvatarPatternCode())
                        || !ownsCode(owned, RankedRewardKind.RANKED_REWARD_CARD_BACKGROUND, loadout.getEquippedCardBgCode())
                        || !ownsCode(owned, RankedRewardKind.RANKED_REWARD_TIER_TITLE, loadout.getEquippedTitleCode())) {
                    c.rollback();
                    return new PrefsWriteResult(WriteStatus.PRESET_INVALID, null, tables.ruleVersion());
                }
                MatchRankedPlayerPrefsRow existing = readPrefs(c, playerId).orElseGet(() -> defaultPrefs(playerId));
                long now = clockMs.getAsLong();
                MatchRankedPlayerPrefsRow updated = existing.toBuilder()
                        .setPlayerId(playerId)
                        .setEquippedTalismanCode(loadout.getEquippedTalismanCode())
                        .setEquippedAvatarPatternCode(loadout.getEquippedAvatarPatternCode())
                        .setEquippedCardBgCode(loadout.getEquippedCardBgCode())
                        .setEquippedTitleCode(loadout.getEquippedTitleCode())
                        .setHideTalisman(loadout.getHideTalisman())
                        .setUpdatedMs(now)
                        .build();
                upsertPrefs(c, updated);
                c.commit();
                return new PrefsWriteResult(WriteStatus.OK, updated, tables.ruleVersion());
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    /** 幂等记录一局排位结算，同步更新本季进度、首次亮段、4/8/12 里程碑权益与排位战果明细。 */
    public RankedBattleSettlementView recordBattleSettlement(SettlementInput in) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                Optional<MatchRankedBattleRow> existing = readBattleRow(c, in.playerId(), in.battleId());
                if (existing.isPresent()) {
                    c.rollback();
                    return toBattleView(existing.get());
                }
                MatchRankedSeasonRow season = in.seasonId() != 0 ? readSeasonById(c, in.seasonId()).orElseGet(() -> {
                    try { return ensureSeason(c); } catch (SQLException e) { throw new IllegalStateException(e); }
                }) : ensureSeason(c);
                int seasonId = season.getSeasonId();
                long now = clockMs.getAsLong();
                MatchRankedPlayerSeasonRow prev = readPlayerSeason(c, seasonId, in.playerId())
                        .orElseGet(() -> defaultProgress(seasonId, in.playerId()));

                long beforeRating = prev.getRatingCenti();
                RankedTier tierBefore = tables.tierFor(beforeRating, prev.getQualified());
                boolean confirmed = in.status() == RankedMatchRatingStatus.RANKED_MATCH_RATING_CONFIRMED;
                long afterRating = confirmed ? Math.max(0L, beforeRating + in.ratingDeltaCenti()) : beforeRating;
                long delta = afterRating - beforeRating;
                boolean qualifiedAfter = prev.getQualified() || (confirmed && in.participated());
                boolean newlyQualified = !prev.getQualified() && qualifiedAfter;
                long qualifiedAt = prev.getQualified() ? prev.getQualifiedAtMs() : (newlyQualified ? now : 0L);
                int validAfter = prev.getValidMatches() + (confirmed && in.participated() ? 1 : 0);
                int wins = prev.getWins();
                int losses = prev.getLosses();
                int draws = prev.getDraws();
                if (confirmed) {
                    if (in.outcome() == eBattleOutcome.BATTLE_OUTCOME_DRAW) {
                        draws++;
                    } else if ((in.playerTeamIndex() == 0 && in.outcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                            || (in.playerTeamIndex() == 1 && in.outcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)) {
                        wins++;
                    } else {
                        losses++;
                    }
                }
                long peak = qualifiedAfter ? Math.max(prev.getPeakRatingCenti(), afterRating) : prev.getPeakRatingCenti();
                int bits = prev.getMilestoneBits();
                List<RankedRewardEntitlement> unlocked = new ArrayList<>();
                RankedRuleTable rule = tables.rule();
                if (confirmed && in.participated()) {
                    if ((bits & 1) == 0 && validAfter >= rule.getMilestoneAvatarMatches()) {
                        bits |= 1;
                        unlocked.add(grantReward(c, in.playerId(), seasonId, RankedRewardKind.RANKED_REWARD_AVATAR_PATTERN,
                                rule.getMilestoneAvatarMatches(), season.getSeasonKey() + "_avatar_pattern", now));
                    }
                    if ((bits & 2) == 0 && validAfter >= rule.getMilestoneCardMatches()) {
                        bits |= 2;
                        unlocked.add(grantReward(c, in.playerId(), seasonId, RankedRewardKind.RANKED_REWARD_CARD_BACKGROUND,
                                rule.getMilestoneCardMatches(), season.getSeasonKey() + "_card_bg", now));
                    }
                    if ((bits & 4) == 0 && validAfter >= rule.getMilestoneTalismanMatches()) {
                        bits |= 4;
                        unlocked.add(grantReward(c, in.playerId(), seasonId, RankedRewardKind.RANKED_REWARD_TALISMAN,
                                rule.getMilestoneTalismanMatches(), season.getSeasonKey() + "_qingyun_talisman", now));
                    }
                }
                MatchRankedPlayerSeasonRow nextProgress = prev.toBuilder()
                        .setSeasonId(seasonId)
                        .setPlayerId(in.playerId())
                        .setQualified(qualifiedAfter)
                        .setRatingCenti(afterRating)
                        .setPeakRatingCenti(peak)
                        .setValidMatches(validAfter)
                        .setWins(wins)
                        .setLosses(losses)
                        .setDraws(draws)
                        .setQualifiedAtMs(qualifiedAt)
                        .setMilestoneBits(bits)
                        .setUpdatedMs(now)
                        .build();
                upsertPlayerSeason(c, nextProgress);

                RankedTier tierAfter = tables.tierFor(afterRating, qualifiedAfter);
                RankedBattleSettlementView.Builder viewBuilder = RankedBattleSettlementView.newBuilder()
                        .setBattleId(in.battleId())
                        .setSeasonId(seasonId)
                        .setTeamSize(in.teamSize())
                        .setStatus(in.status())
                        .setOutcome(in.outcome())
                        .setPlayerTeamIndex(in.playerTeamIndex())
                        .setTotalRounds(in.totalRounds())
                        .setParticipated(in.participated())
                        .setFled(in.fled())
                        .setRatingBeforeCenti(beforeRating)
                        .setRatingAfterCenti(afterRating)
                        .setRatingDeltaCenti(delta)
                        .setTierBefore(tierBefore)
                        .setTierAfter(tierAfter)
                        .setNewlyQualified(newlyQualified)
                        .setSeasonValidMatchesAfter(validAfter)
                        .addAllNewlyUnlockedRewards(unlocked)
                        .setSettledAtMs(now)
                        .setCharacterPresetId(in.characterPresetId())
                        .setPetPresetId(in.petPresetId());
                byte[] rewardBlob = NetworkProbes.bytes(RankedBattleSettlementView.newBuilder()
                        .addAllNewlyUnlockedRewards(unlocked).build());
                insertBattleRow(c, MatchRankedBattleRow.newBuilder()
                        .setPlayerId(in.playerId())
                        .setBattleId(in.battleId())
                        .setSeasonId(seasonId)
                        .setTeamSize(in.teamSize())
                        .setStatus(in.status().getNumber())
                        .setOutcome(in.outcome().getNumber())
                        .setPlayerTeamIndex(in.playerTeamIndex())
                        .setTotalRounds(in.totalRounds())
                        .setParticipated(in.participated())
                        .setFled(in.fled())
                        .setRatingBeforeCenti(beforeRating)
                        .setRatingAfterCenti(afterRating)
                        .setRatingDeltaCenti(delta)
                        .setTierBefore(tierBefore.getNumber())
                        .setTierAfter(tierAfter.getNumber())
                        .setNewlyQualified(newlyQualified)
                        .setSeasonValidMatchesAfter(validAfter)
                        .setNewlyUnlockedRewardsProto(ByteString.copyFrom(rewardBlob))
                        .setSettledAtMs(now)
                        .setCharacterPresetId(in.characterPresetId())
                        .setPetPresetId(in.petPresetId())
                        .build());
                c.commit();
                return viewBuilder.build();
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    public Optional<RankedBattleSettlementView> getBattleResult(long playerId, long battleId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            Optional<MatchRankedBattleRow> row = battleId == 0L
                    ? readLatestBattleRow(c, playerId)
                    : readBattleRow(c, playerId, battleId);
            if (row.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(toBattleView(row.get()));
        }
    }

    public RankedSeasonInfo toSeasonInfo(MatchRankedSeasonRow row) {
        RankedRuleTable rule = tables.rule();
        RankedSeasonState state = RankedSeasonState.forNumber(row.getState());
        return RankedSeasonInfo.newBuilder()
                .setSeasonId(row.getSeasonId())
                .setSeasonKey(row.getSeasonKey())
                .setSeasonName(row.getSeasonName())
                .setRankGeneration(row.getRankGeneration())
                .setRuleVersion(row.getRuleVersion())
                .setState(state == null ? RankedSeasonState.RANKED_SEASON_STATE_UNSPECIFIED : state)
                .setStartMs(row.getStartMs())
                .setEndMs(row.getEndMs())
                .setBattleConfigId(row.getBattleConfigId())
                .setWaitPromptSeconds(rule.getWaitPromptSeconds())
                .setSpreadTier1Seconds(rule.getSpreadTier1Seconds())
                .setSpreadTier1Points(rule.getSpreadTier1Points())
                .setSpreadTier2Seconds(rule.getSpreadTier2Seconds())
                .setSpreadTier2Points(rule.getSpreadTier2Points())
                .setSpreadTier3Points(rule.getSpreadTier3Points())
                .setChampionPlayerId(row.getChampionPlayerId())
                .setChampionName(row.getChampionName())
                .setChampionRatingCenti(row.getChampionRatingCenti())
                .setRankBoardSealed(row.getRankBoardSealed())
                .build();
    }

    public RankedPlayerSeasonSummary toProgressSummary(MatchRankedPlayerSeasonRow row, String seasonKey) {
        return RankedPlayerSeasonSummary.newBuilder()
                .setSeasonId(row.getSeasonId())
                .setSeasonKey(seasonKey)
                .setQualified(row.getQualified())
                .setRatingCenti(row.getRatingCenti())
                .setCurrentTier(tables.tierFor(row.getRatingCenti(), row.getQualified()))
                .setPeakRatingCenti(row.getPeakRatingCenti())
                .setPeakTier(tables.tierFor(row.getPeakRatingCenti(), row.getQualified()))
                .setValidMatches(row.getValidMatches())
                .setWins(row.getWins())
                .setLosses(row.getLosses())
                .setDraws(row.getDraws())
                .setQualifiedAtMs(row.getQualifiedAtMs())
                .setMilestoneBits(row.getMilestoneBits())
                .setFinalRank(row.getFinalRank())
                .setChampionAwarded(row.getChampionAwarded())
                .build();
    }

    public RankedPresetSelection toPresetSelection(MatchRankedPlayerPrefsRow prefs) {
        int ch = prefs.getCharacterPresetId() == 0 ? tables.rule().getDefaultCharacterPresetId() : prefs.getCharacterPresetId();
        int pet = prefs.getPetPresetId() == 0 ? tables.rule().getDefaultPetPresetId() : prefs.getPetPresetId();
        return RankedPresetSelection.newBuilder()
                .setCharacterPresetId(ch)
                .setPetPresetId(pet)
                .build();
    }

    public RankedCosmeticLoadout toCosmeticLoadout(MatchRankedPlayerPrefsRow prefs, List<MatchRankedRewardRow> owned) {
        String talisman = prefs.getEquippedTalismanCode();
        if (talisman.isEmpty() && !prefs.getHideTalisman()) {
            // 默认佩戴最新已解锁玉符
            for (MatchRankedRewardRow r : owned) {
                if (r.getKind() == RankedRewardKind.RANKED_REWARD_TALISMAN_VALUE) {
                    talisman = r.getItemCode();
                }
            }
        }
        return RankedCosmeticLoadout.newBuilder()
                .setEquippedTalismanCode(prefs.getHideTalisman() ? "" : talisman)
                .setEquippedAvatarPatternCode(prefs.getEquippedAvatarPatternCode())
                .setEquippedCardBgCode(prefs.getEquippedCardBgCode())
                .setEquippedTitleCode(prefs.getEquippedTitleCode())
                .setHideTalisman(prefs.getHideTalisman())
                .build();
    }

    public static RankedRewardEntitlement toEntitlement(MatchRankedRewardRow row) {
        RankedRewardKind kind = RankedRewardKind.forNumber(row.getKind());
        return RankedRewardEntitlement.newBuilder()
                .setSeasonId(row.getSeasonId())
                .setKind(kind == null ? RankedRewardKind.RANKED_REWARD_KIND_UNSPECIFIED : kind)
                .setTierValue(row.getTierValue())
                .setItemCode(row.getItemCode())
                .setGrantedAtMs(row.getGrantedAtMs())
                .build();
    }

    private MatchRankedSeasonRow ensureSeason(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,season_key,season_name,rank_generation,rule_version,state,start_ms,end_ms,battle_config_id,champion_player_id,champion_name,champion_rating_centi,rank_board_sealed "
                        + "FROM match_ranked_season ORDER BY season_id DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                return scanSeason(rs);
            }
        }
        RankedRuleTable rule = tables.rule();
        long start = clockMs.getAsLong();
        long end = start + Integer.toUnsignedLong(rule.getSeasonDurationDays()) * MILLIS_PER_DAY;
        MatchRankedSeasonRow s1 = MatchRankedSeasonRow.newBuilder()
                .setSeasonId(1)
                .setSeasonKey("s1")
                .setSeasonName("第一赛季")
                .setRankGeneration(1L)
                .setRuleVersion(rule.getRuleVersion())
                .setState(RankedSeasonState.RANKED_SEASON_STATE_ACTIVE_VALUE)
                .setStartMs(start)
                .setEndMs(end)
                .setBattleConfigId(rule.getBattleConfigId())
                .build();
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO match_ranked_season(season_id,season_key,season_name,rank_generation,rule_version,state,start_ms,end_ms,battle_config_id,champion_player_id,champion_name,champion_rating_centi,rank_board_sealed) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ins.setInt(1, s1.getSeasonId());
            ins.setString(2, s1.getSeasonKey());
            ins.setString(3, s1.getSeasonName());
            ins.setObject(4, PbMysql.uint64(s1.getRankGeneration()));
            ins.setInt(5, s1.getRuleVersion());
            ins.setInt(6, s1.getState());
            ins.setObject(7, PbMysql.uint64(s1.getStartMs()));
            ins.setObject(8, PbMysql.uint64(s1.getEndMs()));
            ins.setInt(9, s1.getBattleConfigId());
            ins.setObject(10, PbMysql.uint64(0L));
            ins.setString(11, "");
            ins.setLong(12, 0L);
            ins.setBoolean(13, false);
            ins.executeUpdate();
        } catch (SQLException dup) {
            return readSeasonById(c, 1).orElseThrow(() -> dup);
        }
        return s1;
    }

    private Optional<MatchRankedSeasonRow> readSeasonById(Connection c, int seasonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,season_key,season_name,rank_generation,rule_version,state,start_ms,end_ms,battle_config_id,champion_player_id,champion_name,champion_rating_centi,rank_board_sealed "
                        + "FROM match_ranked_season WHERE season_id=?")) {
            ps.setInt(1, seasonId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(scanSeason(rs)) : Optional.empty();
            }
        }
    }

    private List<MatchRankedSeasonRow> readPastSeasons(Connection c, int currentSeasonId) throws SQLException {
        List<MatchRankedSeasonRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,season_key,season_name,rank_generation,rule_version,state,start_ms,end_ms,battle_config_id,champion_player_id,champion_name,champion_rating_centi,rank_board_sealed "
                        + "FROM match_ranked_season WHERE season_id<? ORDER BY season_id DESC")) {
            ps.setInt(1, currentSeasonId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(scanSeason(rs));
                }
            }
        }
        return out;
    }

    private static MatchRankedSeasonRow scanSeason(ResultSet rs) throws SQLException {
        return MatchRankedSeasonRow.newBuilder()
                .setSeasonId(rs.getInt(1))
                .setSeasonKey(rs.getString(2))
                .setSeasonName(rs.getString(3))
                .setRankGeneration(Long.parseUnsignedLong(rs.getString(4)))
                .setRuleVersion(rs.getInt(5))
                .setState(rs.getInt(6))
                .setStartMs(Long.parseUnsignedLong(rs.getString(7)))
                .setEndMs(Long.parseUnsignedLong(rs.getString(8)))
                .setBattleConfigId(rs.getInt(9))
                .setChampionPlayerId(Long.parseUnsignedLong(rs.getString(10)))
                .setChampionName(rs.getString(11))
                .setChampionRatingCenti(rs.getLong(12))
                .setRankBoardSealed(rs.getBoolean(13))
                .build();
    }

    private Optional<MatchRankedPlayerPrefsRow> readPrefs(Connection c, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_id,character_preset_id,pet_preset_id,rule_version,equipped_talisman_code,equipped_avatar_pattern_code,equipped_card_bg_code,equipped_title_code,hide_talisman,updated_ms "
                        + "FROM match_ranked_player_prefs WHERE player_id=?")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(MatchRankedPlayerPrefsRow.newBuilder()
                        .setPlayerId(Long.parseUnsignedLong(rs.getString(1)))
                        .setCharacterPresetId(rs.getInt(2))
                        .setPetPresetId(rs.getInt(3))
                        .setRuleVersion(rs.getInt(4))
                        .setEquippedTalismanCode(rs.getString(5))
                        .setEquippedAvatarPatternCode(rs.getString(6))
                        .setEquippedCardBgCode(rs.getString(7))
                        .setEquippedTitleCode(rs.getString(8))
                        .setHideTalisman(rs.getBoolean(9))
                        .setUpdatedMs(Long.parseUnsignedLong(rs.getString(10)))
                        .build());
            }
        }
    }

    private void upsertPrefs(Connection c, MatchRankedPlayerPrefsRow row) throws SQLException {
        try (PreparedStatement del = c.prepareStatement("DELETE FROM match_ranked_player_prefs WHERE player_id=?")) {
            del.setObject(1, PbMysql.uint64(row.getPlayerId()));
            del.executeUpdate();
        }
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO match_ranked_player_prefs(player_id,character_preset_id,pet_preset_id,rule_version,equipped_talisman_code,equipped_avatar_pattern_code,equipped_card_bg_code,equipped_title_code,hide_talisman,updated_ms) VALUES(?,?,?,?,?,?,?,?,?,?)")) {
            ins.setObject(1, PbMysql.uint64(row.getPlayerId()));
            ins.setInt(2, row.getCharacterPresetId());
            ins.setInt(3, row.getPetPresetId());
            ins.setInt(4, row.getRuleVersion());
            ins.setString(5, row.getEquippedTalismanCode());
            ins.setString(6, row.getEquippedAvatarPatternCode());
            ins.setString(7, row.getEquippedCardBgCode());
            ins.setString(8, row.getEquippedTitleCode());
            ins.setBoolean(9, row.getHideTalisman());
            ins.setObject(10, PbMysql.uint64(row.getUpdatedMs()));
            ins.executeUpdate();
        }
    }

    private Optional<MatchRankedPlayerSeasonRow> readPlayerSeason(Connection c, int seasonId, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,player_id,qualified,rating_centi,peak_rating_centi,valid_matches,wins,losses,draws,qualified_at_ms,milestone_bits,final_rank,champion_awarded,updated_ms "
                        + "FROM match_ranked_player_season WHERE season_id=? AND player_id=?")) {
            ps.setInt(1, seasonId);
            ps.setObject(2, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(scanPlayerSeason(rs)) : Optional.empty();
            }
        }
    }

    private List<MatchRankedPlayerSeasonRow> readPlayerHistory(Connection c, long playerId, int currentSeasonId) throws SQLException {
        List<MatchRankedPlayerSeasonRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,player_id,qualified,rating_centi,peak_rating_centi,valid_matches,wins,losses,draws,qualified_at_ms,milestone_bits,final_rank,champion_awarded,updated_ms "
                        + "FROM match_ranked_player_season WHERE player_id=? AND season_id<? ORDER BY season_id DESC")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            ps.setInt(2, currentSeasonId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(scanPlayerSeason(rs));
                }
            }
        }
        return out;
    }

    private static MatchRankedPlayerSeasonRow scanPlayerSeason(ResultSet rs) throws SQLException {
        return MatchRankedPlayerSeasonRow.newBuilder()
                .setSeasonId(rs.getInt(1))
                .setPlayerId(Long.parseUnsignedLong(rs.getString(2)))
                .setQualified(rs.getBoolean(3))
                .setRatingCenti(rs.getLong(4))
                .setPeakRatingCenti(rs.getLong(5))
                .setValidMatches(rs.getInt(6))
                .setWins(rs.getInt(7))
                .setLosses(rs.getInt(8))
                .setDraws(rs.getInt(9))
                .setQualifiedAtMs(Long.parseUnsignedLong(rs.getString(10)))
                .setMilestoneBits(rs.getInt(11))
                .setFinalRank(Long.parseUnsignedLong(rs.getString(12)))
                .setChampionAwarded(rs.getBoolean(13))
                .setUpdatedMs(Long.parseUnsignedLong(rs.getString(14)))
                .build();
    }

    private void upsertPlayerSeason(Connection c, MatchRankedPlayerSeasonRow row) throws SQLException {
        try (PreparedStatement del = c.prepareStatement(
                "DELETE FROM match_ranked_player_season WHERE season_id=? AND player_id=?")) {
            del.setInt(1, row.getSeasonId());
            del.setObject(2, PbMysql.uint64(row.getPlayerId()));
            del.executeUpdate();
        }
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO match_ranked_player_season(season_id,player_id,qualified,rating_centi,peak_rating_centi,valid_matches,wins,losses,draws,qualified_at_ms,milestone_bits,final_rank,champion_awarded,updated_ms) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ins.setInt(1, row.getSeasonId());
            ins.setObject(2, PbMysql.uint64(row.getPlayerId()));
            ins.setBoolean(3, row.getQualified());
            ins.setLong(4, row.getRatingCenti());
            ins.setLong(5, row.getPeakRatingCenti());
            ins.setInt(6, row.getValidMatches());
            ins.setInt(7, row.getWins());
            ins.setInt(8, row.getLosses());
            ins.setInt(9, row.getDraws());
            ins.setObject(10, PbMysql.uint64(row.getQualifiedAtMs()));
            ins.setInt(11, row.getMilestoneBits());
            ins.setObject(12, PbMysql.uint64(row.getFinalRank()));
            ins.setBoolean(13, row.getChampionAwarded());
            ins.setObject(14, PbMysql.uint64(row.getUpdatedMs()));
            ins.executeUpdate();
        }
    }

    private List<MatchRankedRewardRow> readRewards(Connection c, long playerId) throws SQLException {
        List<MatchRankedRewardRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_id,season_id,kind,tier_value,item_code,granted_at_ms FROM match_ranked_reward WHERE player_id=? ORDER BY season_id,kind,tier_value")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(MatchRankedRewardRow.newBuilder()
                            .setPlayerId(Long.parseUnsignedLong(rs.getString(1)))
                            .setSeasonId(rs.getInt(2))
                            .setKind(rs.getInt(3))
                            .setTierValue(rs.getInt(4))
                            .setItemCode(rs.getString(5))
                            .setGrantedAtMs(Long.parseUnsignedLong(rs.getString(6)))
                            .build());
                }
            }
        }
        return out;
    }

    private RankedRewardEntitlement grantReward(Connection c, long playerId, int seasonId, RankedRewardKind kind,
                                                int tierValue, String itemCode, long nowMs) throws SQLException {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO match_ranked_reward(player_id,season_id,kind,tier_value,item_code,granted_at_ms) VALUES(?,?,?,?,?,?)")) {
            ins.setObject(1, PbMysql.uint64(playerId));
            ins.setInt(2, seasonId);
            ins.setInt(3, kind.getNumber());
            ins.setInt(4, tierValue);
            ins.setString(5, itemCode);
            ins.setObject(6, PbMysql.uint64(nowMs));
            ins.executeUpdate();
        }
        return RankedRewardEntitlement.newBuilder()
                .setSeasonId(seasonId)
                .setKind(kind)
                .setTierValue(tierValue)
                .setItemCode(itemCode)
                .setGrantedAtMs(nowMs)
                .build();
    }

    private Optional<MatchRankedBattleRow> readBattleRow(Connection c, long playerId, long battleId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_id,battle_id,season_id,team_size,status,outcome,player_team_index,total_rounds,participated,fled,"
                        + "rating_before_centi,rating_after_centi,rating_delta_centi,tier_before,tier_after,newly_qualified,"
                        + "season_valid_matches_after,newly_unlocked_rewards_proto,settled_at_ms,character_preset_id,pet_preset_id "
                        + "FROM match_ranked_battle WHERE player_id=? AND battle_id=?")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            ps.setObject(2, PbMysql.uint64(battleId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(scanBattleRow(rs)) : Optional.empty();
            }
        }
    }

    private Optional<MatchRankedBattleRow> readLatestBattleRow(Connection c, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_id,battle_id,season_id,team_size,status,outcome,player_team_index,total_rounds,participated,fled,"
                        + "rating_before_centi,rating_after_centi,rating_delta_centi,tier_before,tier_after,newly_qualified,"
                        + "season_valid_matches_after,newly_unlocked_rewards_proto,settled_at_ms,character_preset_id,pet_preset_id "
                        + "FROM match_ranked_battle WHERE player_id=? ORDER BY settled_at_ms DESC,battle_id DESC LIMIT 1")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(scanBattleRow(rs)) : Optional.empty();
            }
        }
    }

    private void insertBattleRow(Connection c, MatchRankedBattleRow row) throws SQLException {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO match_ranked_battle(player_id,battle_id,season_id,team_size,status,outcome,player_team_index,total_rounds,participated,fled,"
                        + "rating_before_centi,rating_after_centi,rating_delta_centi,tier_before,tier_after,newly_qualified,"
                        + "season_valid_matches_after,newly_unlocked_rewards_proto,settled_at_ms,character_preset_id,pet_preset_id) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            ins.setObject(1, PbMysql.uint64(row.getPlayerId()));
            ins.setObject(2, PbMysql.uint64(row.getBattleId()));
            ins.setInt(3, row.getSeasonId());
            ins.setInt(4, row.getTeamSize());
            ins.setInt(5, row.getStatus());
            ins.setInt(6, row.getOutcome());
            ins.setInt(7, row.getPlayerTeamIndex());
            ins.setInt(8, row.getTotalRounds());
            ins.setBoolean(9, row.getParticipated());
            ins.setBoolean(10, row.getFled());
            ins.setLong(11, row.getRatingBeforeCenti());
            ins.setLong(12, row.getRatingAfterCenti());
            ins.setLong(13, row.getRatingDeltaCenti());
            ins.setInt(14, row.getTierBefore());
            ins.setInt(15, row.getTierAfter());
            ins.setBoolean(16, row.getNewlyQualified());
            ins.setInt(17, row.getSeasonValidMatchesAfter());
            ins.setBytes(18, row.getNewlyUnlockedRewardsProto().toByteArray());
            ins.setObject(19, PbMysql.uint64(row.getSettledAtMs()));
            ins.setInt(20, row.getCharacterPresetId());
            ins.setInt(21, row.getPetPresetId());
            ins.executeUpdate();
        }
    }

    private static MatchRankedBattleRow scanBattleRow(ResultSet rs) throws SQLException {
        byte[] blob = rs.getBytes(18);
        return MatchRankedBattleRow.newBuilder()
                .setPlayerId(Long.parseUnsignedLong(rs.getString(1)))
                .setBattleId(Long.parseUnsignedLong(rs.getString(2)))
                .setSeasonId(rs.getInt(3))
                .setTeamSize(rs.getInt(4))
                .setStatus(rs.getInt(5))
                .setOutcome(rs.getInt(6))
                .setPlayerTeamIndex(rs.getInt(7))
                .setTotalRounds(rs.getInt(8))
                .setParticipated(rs.getBoolean(9))
                .setFled(rs.getBoolean(10))
                .setRatingBeforeCenti(rs.getLong(11))
                .setRatingAfterCenti(rs.getLong(12))
                .setRatingDeltaCenti(rs.getLong(13))
                .setTierBefore(rs.getInt(14))
                .setTierAfter(rs.getInt(15))
                .setNewlyQualified(rs.getBoolean(16))
                .setSeasonValidMatchesAfter(rs.getInt(17))
                .setNewlyUnlockedRewardsProto(blob == null ? ByteString.EMPTY : ByteString.copyFrom(blob))
                .setSettledAtMs(Long.parseUnsignedLong(rs.getString(19)))
                .setCharacterPresetId(rs.getInt(20))
                .setPetPresetId(rs.getInt(21))
                .build();
    }

    private static RankedBattleSettlementView toBattleView(MatchRankedBattleRow row) throws SQLException {
        RankedMatchRatingStatus st = RankedMatchRatingStatus.forNumber(row.getStatus());
        eBattleOutcome outcome = eBattleOutcome.forNumber(row.getOutcome());
        RankedTier tb = RankedTier.forNumber(row.getTierBefore());
        RankedTier ta = RankedTier.forNumber(row.getTierAfter());
        RankedBattleSettlementView.Builder b = RankedBattleSettlementView.newBuilder()
                .setBattleId(row.getBattleId())
                .setSeasonId(row.getSeasonId())
                .setTeamSize(row.getTeamSize())
                .setStatus(st == null ? RankedMatchRatingStatus.RANKED_MATCH_RATING_UNSPECIFIED : st)
                .setOutcome(outcome == null ? eBattleOutcome.BATTLE_OUTCOME_ONGOING : outcome)
                .setPlayerTeamIndex(row.getPlayerTeamIndex())
                .setTotalRounds(row.getTotalRounds())
                .setParticipated(row.getParticipated())
                .setFled(row.getFled())
                .setRatingBeforeCenti(row.getRatingBeforeCenti())
                .setRatingAfterCenti(row.getRatingAfterCenti())
                .setRatingDeltaCenti(row.getRatingDeltaCenti())
                .setTierBefore(tb == null ? RankedTier.RANKED_TIER_UNSPECIFIED : tb)
                .setTierAfter(ta == null ? RankedTier.RANKED_TIER_UNSPECIFIED : ta)
                .setNewlyQualified(row.getNewlyQualified())
                .setSeasonValidMatchesAfter(row.getSeasonValidMatchesAfter())
                .setSettledAtMs(row.getSettledAtMs())
                .setCharacterPresetId(row.getCharacterPresetId())
                .setPetPresetId(row.getPetPresetId());
        if (!row.getNewlyUnlockedRewardsProto().isEmpty()) {
            try {
                RankedBattleSettlementView holder = RankedBattleSettlementView.parseFrom(row.getNewlyUnlockedRewardsProto());
                b.addAllNewlyUnlockedRewards(holder.getNewlyUnlockedRewardsList());
            } catch (InvalidProtocolBufferException e) {
                throw new SQLException("corrupt ranked battle rewards blob", e);
            }
        }
        return b.build();
    }

    private MatchRankedPlayerPrefsRow defaultPrefs(long playerId) {
        return MatchRankedPlayerPrefsRow.newBuilder()
                .setPlayerId(playerId)
                .setCharacterPresetId(tables.rule().getDefaultCharacterPresetId())
                .setPetPresetId(tables.rule().getDefaultPetPresetId())
                .setRuleVersion(tables.ruleVersion())
                .build();
    }

    private static MatchRankedPlayerSeasonRow defaultProgress(int seasonId, long playerId) {
        return MatchRankedPlayerSeasonRow.newBuilder()
                .setSeasonId(seasonId)
                .setPlayerId(playerId)
                .setRatingCenti(RankedTables.INITIAL_RATING_CENTI)
                .setPeakRatingCenti(0L)
                .build();
    }

    private static boolean ownsCode(List<MatchRankedRewardRow> owned, RankedRewardKind kind, String code) {
        if (code == null || code.isEmpty()) {
            return true;
        }
        for (MatchRankedRewardRow r : owned) {
            if (r.getKind() == kind.getNumber() && code.equals(r.getItemCode())) {
                return true;
            }
        }
        return false;
    }

    private static void rollbackQuietly(Connection c, Exception primary) {
        try {
            c.rollback();
        } catch (SQLException rollback) {
            primary.addSuppressed(rollback);
        }
    }
}
