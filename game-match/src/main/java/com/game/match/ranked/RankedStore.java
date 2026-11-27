package com.game.match.ranked;

import com.game.common.token.NetworkProbes;
import com.game.match.feature.pb.MatchRankedBattleRow;
import com.game.match.feature.pb.MatchRankedFeaturedBuildRow;
import com.game.match.feature.pb.MatchRankedPlayerPrefsRow;
import com.game.match.feature.pb.MatchRankedPlayerSeasonRow;
import com.game.match.feature.pb.MatchRankedRewardRow;
import com.game.match.feature.pb.MatchRankedRivalryRow;
import com.game.match.feature.pb.MatchRankedSeasonRow;
import com.game.pbmysql.PbMysql;
import com.game.proto.BattleParticipationFact;
import com.game.proto.BattleSettlementData;
import com.game.proto.RankedMatchContext;
import com.game.proto.RankedMemberLoadout;
import com.game.proto.RankedPresetSelection;
import com.game.proto.RankedRivalryEncounter;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.RankedBattleSettlementView;
import com.game.proto.match.RankedCosmeticLoadout;
import com.game.proto.match.RankedFeaturedBuildView;
import com.game.proto.match.RankedMatchRatingStatus;
import com.game.proto.match.RankedPlayerSeasonSummary;
import com.game.proto.match.RankedRewardEntitlement;
import com.game.proto.match.RankedRewardKind;
import com.game.proto.match.RankedRivalrySummary;
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
    private static final long FEATURED_CACHE_TTL_MS = 5_000L;

    private record FeaturedCacheKey(int seasonId, int limit) {
    }

    private record FeaturedCacheEntry(long loadedAtMs, List<RankedFeaturedBuildView> builds) {
    }

    private final java.util.concurrent.ConcurrentHashMap<FeaturedCacheKey, FeaturedCacheEntry> featuredCache =
            new java.util.concurrent.ConcurrentHashMap<>();

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
                              List<MatchRankedSeasonRow> pastSeasons,
                              List<RankedFeaturedBuildView> featuredBuilds,
                              List<RankedRivalrySummary> topRivals) {
    }

    public record SettlementInput(long playerId, long battleId, int seasonId, int teamSize,
                                  RankedMatchRatingStatus status, eBattleOutcome outcome,
                                  int playerTeamIndex, int totalRounds, boolean participated, boolean fled,
                                  long ratingDeltaCenti, int characterPresetId, int petPresetId) {
    }

    /** 与评分事务同提交的排位落账摘要（只把本季已亮段玩家交给排行榜 outbox）。 */
    public record SeasonCommitResult(int seasonId, String seasonKey, long rankGeneration,
                                     Map<Long, Long> qualifiedScores,
                                     Map<Long, RankedBattleSettlementView> views) {
    }

    /** 封榜钩子（对接 RankAdmin.FreezeRankGeneration；测试可注入替身或 null）。 */
    @FunctionalInterface
    public interface RankFreezeHook {
        void freeze(MatchRankedSeasonRow season) throws Exception;
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
            long carryRating = readCarryOverRating(c, playerId);
            MatchRankedPlayerSeasonRow progress = readPlayerSeason(c, current.getSeasonId(), playerId)
                    .orElseGet(() -> defaultProgress(current.getSeasonId(), playerId, carryRating));
            MatchRankedPlayerPrefsRow prefs = readPrefs(c, playerId).orElseGet(() -> defaultPrefs(playerId));
            List<MatchRankedRewardRow> rewards = readRewards(c, playerId);
            List<MatchRankedPlayerSeasonRow> history = List.of();
            List<MatchRankedSeasonRow> pastSeasons = List.of();
            if (includeHistory) {
                history = readPlayerHistory(c, playerId, current.getSeasonId());
                pastSeasons = readPastSeasons(c, current.getSeasonId());
            }
            List<RankedFeaturedBuildView> featuredBuilds = readTopFeaturedBuildsCached(c, current.getSeasonId(), 5);
            List<RankedRivalrySummary> topRivals = readTopRivals(c, current.getSeasonId(), playerId, 8);
            return new ProfileData(current, progress, prefs, rewards, history, pastSeasons, featuredBuilds, topRivals);
        }
    }

    /** 读取单人已保存预设（未保存时返回官方默认破阵/护主）。 */
    public RankedPresetSelection loadPlayerPresets(long playerId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            MatchRankedPlayerPrefsRow prefs = readPrefs(c, playerId).orElseGet(() -> defaultPrefs(playerId));
            return toPresetSelection(prefs);
        }
    }

    public RankedTables tables() {
        return tables;
    }

    /** 组装本局正式人宠会武(PVP_TEAM)权威上下文：使用本人真实养成(use_free_preset=false)；若当前赛季正在结算中返回空。 */
    public Optional<RankedMatchContext> buildRankedContext(int teamSize, List<Long> roster,
                                                           Map<Long, RankedPresetSelection> overrides) throws SQLException {
        return buildRankedContext(teamSize, roster, null, overrides);
    }

    /** 同 {@link #buildRankedContext(int, List, Map)}，显式接收分队结果 {@code teams} 避免蛇形分队将队友误配成宿敌。 */
    public Optional<RankedMatchContext> buildRankedContext(int teamSize, List<Long> roster, int[] teams,
                                                           Map<Long, RankedPresetSelection> overrides) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            MatchRankedSeasonRow current = ensureSeason(c);
            long nowMs = clockMs.getAsLong();
            if (current.getState() != RankedSeasonState.RANKED_SEASON_STATE_ACTIVE_VALUE
                    || (current.getEndMs() > 0L && Long.compareUnsigned(nowMs, current.getEndMs()) >= 0)) {
                return Optional.empty();
            }
            RankedMatchContext.Builder ctx = RankedMatchContext.newBuilder()
                    .setSeasonId(current.getSeasonId())
                    .setSeasonKey(current.getSeasonKey())
                    .setRankGeneration(current.getRankGeneration() != 0L
                            ? current.getRankGeneration()
                            : Integer.toUnsignedLong(current.getSeasonId()))
                    .setRuleVersion(current.getRuleVersion())
                    .setConfigFingerprint(tables.configFingerprint())
                    .setTeamSize(teamSize)
                    .setUseFreePreset(false);
            long minRatingCenti = 0L;
            long maxRatingCenti = 0L;
            boolean hasMemberRating = false;
            if (roster != null) {
                for (long pid : roster) {
                    Optional<MatchRankedPlayerSeasonRow> row = readPlayerSeason(c, current.getSeasonId(), pid);
                    long ratingCenti = RankedTables.INITIAL_RATING_CENTI;
                    if (row.isPresent()) {
                        ratingCenti = row.get().getRatingCenti();
                        if (row.get().getCurrentWinStreak() >= 3) {
                            ctx.putMemberWinStreaks(pid, row.get().getCurrentWinStreak());
                        }
                    }
                    if (!hasMemberRating) {
                        minRatingCenti = ratingCenti;
                        maxRatingCenti = ratingCenti;
                        hasMemberRating = true;
                    } else {
                        if (ratingCenti < minRatingCenti) minRatingCenti = ratingCenti;
                        if (ratingCenti > maxRatingCenti) maxRatingCenti = ratingCenti;
                    }
                }
            }
            int ruleBanSlots = tables.rule().getSkillBanSlotsPerTeam();
            if (ruleBanSlots > 0 && hasMemberRating
                    && minRatingCenti >= Integer.toUnsignedLong(tables.rule().getSkillBanMinRatingCenti())) {
                ctx.setSkillBanSlotsPerTeam(ruleBanSlots);
            }
            long spectateMinCenti = Integer.toUnsignedLong(tables.rule().getTierQingfengCenti());
            if (spectateMinCenti <= 0L) spectateMinCenti = 152_000L;
            ctx.setPeakRatingCenti(maxRatingCenti)
                    .setAllowPublicSpectate(hasMemberRating && maxRatingCenti >= spectateMinCenti);
            if (roster != null && roster.size() >= 2) {
                List<Long> sideA;
                List<Long> sideB;
                if (teams != null && teams.length == roster.size()) {
                    sideA = new ArrayList<>();
                    sideB = new ArrayList<>();
                    for (int i = 0; i < roster.size(); i++) {
                        if (teams[i] == 0) sideA.add(roster.get(i));
                        else if (teams[i] == 1) sideB.add(roster.get(i));
                    }
                } else {
                    int split = Math.min(Math.max(1, teamSize), roster.size() / 2);
                    sideA = roster.subList(0, split);
                    sideB = roster.subList(split, roster.size());
                }
                if (!sideA.isEmpty() && !sideB.isEmpty()) {
                    ctx.addAllRivalryEncounters(loadRivalryEncountersForRoster(c, current.getSeasonId(), sideA, sideB));
                }
            }
            return Optional.of(ctx.build());
        }
    }

    /** 组装娱乐轮换免费 3×3 预设上下文（护卫救援 109 / 物法易势 5V5 110）：season_id=0 且 use_free_preset=true。 */
    public RankedMatchContext buildFreePresetContext(int teamSize, List<Long> roster,
                                                     Map<Long, RankedPresetSelection> overrides) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            RankedMatchContext.Builder ctx = RankedMatchContext.newBuilder()
                    .setSeasonId(0)
                    .setRuleVersion(tables.ruleVersion())
                    .setConfigFingerprint(tables.configFingerprint())
                    .setTeamSize(teamSize)
                    .setUseFreePreset(true);
            List<Long> sortedRoster = new ArrayList<>(roster);
            sortedRoster.sort(Long::compareUnsigned);
            for (long playerId : sortedRoster) {
                RankedPresetSelection sel = overrides == null ? null : overrides.get(playerId);
                if (sel == null || !tables.isValidPair(sel.getCharacterPresetId(), sel.getPetPresetId())) {
                    MatchRankedPlayerPrefsRow prefs = readPrefs(c, playerId).orElseGet(() -> defaultPrefs(playerId));
                    sel = toPresetSelection(prefs);
                }
                ctx.addMembers(RankedMemberLoadout.newBuilder()
                        .setPlayerId(playerId)
                        .setPresets(sel)
                        .build());
            }
            return ctx.build();
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
                MatchRankedPlayerPrefsRow existing = readPrefsForUpdate(c, playerId).orElseGet(() -> defaultPrefs(playerId));
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
                        || !ownsTitleCode(owned, loadout.getEquippedTitleCode())) {
                    c.rollback();
                    return new PrefsWriteResult(WriteStatus.PRESET_INVALID, null, tables.ruleVersion());
                }
                MatchRankedPlayerPrefsRow existing = readPrefsForUpdate(c, playerId).orElseGet(() -> defaultPrefs(playerId));
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
                long carryRating = readCarryOverRating(c, in.playerId());
                MatchRankedPlayerSeasonRow prev = readPlayerSeasonForUpdate(c, seasonId, in.playerId())
                        .orElseGet(() -> defaultProgress(seasonId, in.playerId(), carryRating));

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
                int curStreak = prev.getCurrentWinStreak();
                int bestStreak = prev.getBestWinStreak();
                if (confirmed) {
                    if (in.outcome() == eBattleOutcome.BATTLE_OUTCOME_DRAW) {
                        draws++;
                    } else if ((in.playerTeamIndex() == 0 && in.outcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                            || (in.playerTeamIndex() == 1 && in.outcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN)) {
                        wins++;
                        curStreak++;
                        if (curStreak > bestStreak) {
                            bestStreak = curStreak;
                        }
                    } else {
                        losses++;
                        curStreak = 0;
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
                    int winTarget = tables.winMilestoneMatches();
                    if ((bits & 8) == 0 && winTarget > 0 && wins >= winTarget) {
                        bits |= 8;
                        unlocked.add(grantReward(c, in.playerId(), seasonId, RankedRewardKind.RANKED_REWARD_WIN_MILESTONE,
                                winTarget, season.getSeasonKey() + "_win_milestone_" + winTarget, now));
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
                        .setCurrentWinStreak(curStreak)
                        .setBestWinStreak(bestStreak)
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
                        .setPetPresetId(in.petPresetId())
                        .setWinStreakAfter(curStreak);
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
                        .setWinStreakAfter(curStreak)
                        .build());
                c.commit();
                return viewBuilder.build();
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    /** 与 {@code RatingStore.applyOnce} 同事务落账整场排位结算，并返回本季已亮段玩家分值供排行榜投递。 */
    public SeasonCommitResult recordBattleResultInTx(Connection c, BattleResultEvent event,
                                                     Map<Long, Long> preCenti, Map<Long, Long> nextCenti,
                                                     long nowMs) throws SQLException {
        return recordBattleResultInTx(c, event, preCenti, nextCenti, false, nowMs);
    }

    /** 同 {@link #recordBattleResultInTx(Connection, BattleResultEvent, Map, Map, long)}，显式区分 0 回合局与回合打满平局。 */
    public SeasonCommitResult recordBattleResultInTx(Connection c, BattleResultEvent event,
                                                     Map<Long, Long> preCenti, Map<Long, Long> nextCenti,
                                                     boolean roundCapDraw, long nowMs) throws SQLException {
        RankedMatchContext ctx = event.getRankedContext();
        int requestedSeasonId = ctx.getSeasonId();
        MatchRankedSeasonRow season = requestedSeasonId != 0
                ? readSeasonById(c, requestedSeasonId).orElseThrow(() -> new SQLException("排位赛季不存在 season=" + requestedSeasonId))
                : ensureSeason(c);
        int seasonId = season.getSeasonId();
        int teamSize = ctx.getTeamSize();
        if (teamSize == 0 && event.getTeamsCount() > 0) {
            teamSize = event.getTeams(0).getPlayerIdsCount();
        }
        Map<Long, BattleParticipationFact> factsByPlayer = new HashMap<>();
        for (BattleParticipationFact fact : event.getParticipationFactsList()) {
            factsByPlayer.put(fact.getPlayerId(), fact);
        }
        Map<Long, BattleSettlementData> settlementsByPlayer = new HashMap<>();
        for (BattleSettlementData st : event.getSettlementsList()) {
            settlementsByPlayer.put(st.getPlayerId(), st);
        }
        java.util.Set<Long> fledSet = new java.util.HashSet<>(event.getFledPlayerIdsList());
        Map<Long, RankedPresetSelection> loadoutByPlayer = new HashMap<>();
        for (RankedMemberLoadout loadout : ctx.getMembersList()) {
            loadoutByPlayer.put(loadout.getPlayerId(), loadout.getPresets());
        }
        Map<Long, Integer> teamByPlayer = new HashMap<>();
        for (BattleResultTeam team : event.getTeamsList()) {
            for (long pid : team.getPlayerIdsList()) {
                teamByPlayer.putIfAbsent(pid, team.getTeamIndex());
            }
        }
        Map<Long, Long> qualifiedScores = new java.util.LinkedHashMap<>();
        Map<Long, RankedBattleSettlementView> views = new java.util.LinkedHashMap<>();
        List<Long> sortedPlayers = new ArrayList<>(nextCenti.keySet());
        sortedPlayers.sort(Long::compareUnsigned);
        RankedRuleTable rule = tables.rule();
        Map<Long, MatchRankedPlayerSeasonRow> preSeasonRows = new HashMap<>();
        for (long pid : sortedPlayers) {
            preSeasonRows.put(pid, readPlayerSeasonForUpdate(c, seasonId, pid)
                    .orElseGet(() -> defaultProgress(seasonId, pid, preCenti.getOrDefault(pid, RankedTables.INITIAL_RATING_CENTI))));
        }
        boolean zeroRound = event.getTotalRounds() == 0;
        eBattleOutcome effectiveOutcome = roundCapDraw ? eBattleOutcome.BATTLE_OUTCOME_DRAW : event.getOutcome();
        // 论剑 V6：预读双方历史宿敌行（FOR UPDATE），供本局雪耻复仇判定与同事务更新。
        record RivalryPair(long playerId, long opponentId) {}
        Map<RivalryPair, MatchRankedRivalryRow> priorRivalries = new HashMap<>();
        if (!zeroRound) {
            List<RivalryPair> pairs = new ArrayList<>();
            for (long pid : sortedPlayers) {
                int myTeam = teamByPlayer.getOrDefault(pid, 0);
                for (long oppId : sortedPlayers) {
                    if (teamByPlayer.getOrDefault(oppId, 0) != myTeam) {
                        pairs.add(new RivalryPair(pid, oppId));
                    }
                }
            }
            pairs.sort((a, b) -> {
                int cmp = Long.compareUnsigned(a.playerId(), b.playerId());
                return cmp != 0 ? cmp : Long.compareUnsigned(a.opponentId(), b.opponentId());
            });
            for (RivalryPair pair : pairs) {
                readRivalryRowForUpdate(c, seasonId, pair.playerId(), pair.opponentId())
                        .ifPresent(row -> priorRivalries.put(pair, row));
            }
        }
        Map<String, FeaturedBuildMatchDelta> buildDeltas = new java.util.TreeMap<>();
        for (long playerId : sortedPlayers) {
            Optional<MatchRankedBattleRow> existing = readBattleRow(c, playerId, event.getBattleId());
            if (existing.isPresent()) {
                RankedBattleSettlementView view = toBattleView(existing.get());
                views.put(playerId, view);
                Optional<MatchRankedPlayerSeasonRow> curRow = readPlayerSeason(c, seasonId, playerId);
                if (curRow.isPresent() && curRow.get().getQualified()) {
                    qualifiedScores.put(playerId, curRow.get().getRatingCenti());
                }
                continue;
            }
            long beforeRating = preCenti.getOrDefault(playerId, RankedTables.INITIAL_RATING_CENTI);
            long afterRating = zeroRound ? beforeRating : nextCenti.getOrDefault(playerId, beforeRating);
            long delta = afterRating - beforeRating;
            boolean fled = fledSet.contains(playerId);
            int teamIndex = teamByPlayer.getOrDefault(playerId, 0);
            boolean playerWon = !zeroRound && (
                    (teamIndex == 0 && effectiveOutcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                            || (teamIndex == 1 && effectiveOutcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN));
            boolean participated = !zeroRound && ((factsByPlayer.containsKey(playerId) || settlementsByPlayer.containsKey(playerId))
                    ? RankedTables.evaluateParticipation(factsByPlayer.get(playerId),
                            settlementsByPlayer.get(playerId), event.getTotalRounds(), playerWon, fled)
                    : !fled);
            RankedPresetSelection presets = loadoutByPlayer.get(playerId);
            int charPresetId = presets != null ? presets.getCharacterPresetId() : rule.getDefaultCharacterPresetId();
            int petPresetId = presets != null ? presets.getPetPresetId() : rule.getDefaultPetPresetId();

            MatchRankedPlayerSeasonRow prev = preSeasonRows.get(playerId);
            RankedTier tierBefore = tables.tierFor(beforeRating, prev.getQualified());
            if (zeroRound) {
                // 0 回合不计分、不改胜负/连胜/宿敌与段位里程碑，仅落盘 ZERO_ROUND 战报视图。
                RankedBattleSettlementView view = RankedBattleSettlementView.newBuilder()
                        .setBattleId(event.getBattleId())
                        .setSeasonId(seasonId)
                        .setTeamSize(teamSize)
                        .setStatus(RankedMatchRatingStatus.RANKED_MATCH_RATING_ZERO_ROUND)
                        .setOutcome(effectiveOutcome)
                        .setPlayerTeamIndex(teamIndex)
                        .setTotalRounds(0)
                        .setParticipated(false)
                        .setFled(fled)
                        .setRatingBeforeCenti(beforeRating)
                        .setRatingAfterCenti(beforeRating)
                        .setRatingDeltaCenti(0L)
                        .setTierBefore(tierBefore)
                        .setTierAfter(tierBefore)
                        .setNewlyQualified(false)
                        .setSeasonValidMatchesAfter(prev.getValidMatches())
                        .setSettledAtMs(nowMs)
                        .setCharacterPresetId(charPresetId)
                        .setPetPresetId(petPresetId)
                        .setWinStreakAfter(prev.getCurrentWinStreak())
                        .build();
                insertBattleRow(c, MatchRankedBattleRow.newBuilder()
                        .setPlayerId(playerId)
                        .setBattleId(event.getBattleId())
                        .setSeasonId(seasonId)
                        .setTeamSize(teamSize)
                        .setStatus(RankedMatchRatingStatus.RANKED_MATCH_RATING_ZERO_ROUND_VALUE)
                        .setOutcome(effectiveOutcome.getNumber())
                        .setPlayerTeamIndex(teamIndex)
                        .setTotalRounds(0)
                        .setParticipated(false)
                        .setFled(fled)
                        .setRatingBeforeCenti(beforeRating)
                        .setRatingAfterCenti(beforeRating)
                        .setRatingDeltaCenti(0L)
                        .setTierBefore(tierBefore.getNumber())
                        .setTierAfter(tierBefore.getNumber())
                        .setNewlyQualified(false)
                        .setSeasonValidMatchesAfter(prev.getValidMatches())
                        .setNewlyUnlockedRewardsProto(ByteString.EMPTY)
                        .setSettledAtMs(nowMs)
                        .setCharacterPresetId(charPresetId)
                        .setPetPresetId(petPresetId)
                        .setWinStreakAfter(prev.getCurrentWinStreak())
                        .build());
                views.put(playerId, view);
                continue;
            }
            boolean qualifiedAfter = prev.getQualified() || participated;
            boolean newlyQualified = !prev.getQualified() && qualifiedAfter;
            long qualifiedAt = prev.getQualified() ? prev.getQualifiedAtMs() : (newlyQualified ? nowMs : 0L);
            int validAfter = prev.getValidMatches() + (participated ? 1 : 0);
            int wins = prev.getWins();
            int losses = prev.getLosses();
            int draws = prev.getDraws();
            int curStreak = prev.getCurrentWinStreak();
            int bestStreak = prev.getBestWinStreak();
            int endedOpponentStreak = 0;
            if (effectiveOutcome == eBattleOutcome.BATTLE_OUTCOME_DRAW) {
                draws++;
            } else if (playerWon) {
                wins++;
                curStreak++;
                if (curStreak > bestStreak) {
                    bestStreak = curStreak;
                }
                BattleSettlementData st = settlementsByPlayer.get(playerId);
                if (st != null && st.getEndedOpponentStreak() >= 3) {
                    endedOpponentStreak = st.getEndedOpponentStreak();
                } else {
                    for (Map.Entry<Long, Integer> opp : teamByPlayer.entrySet()) {
                        if (opp.getValue() == teamIndex) {
                            continue;
                        }
                        int oppStreak = ctx.getMemberWinStreaksOrDefault(opp.getKey(), 0);
                        if (oppStreak < 3) {
                            MatchRankedPlayerSeasonRow oppRow = preSeasonRows.get(opp.getKey());
                            if (oppRow != null) {
                                oppStreak = oppRow.getCurrentWinStreak();
                            }
                        }
                        if (oppStreak >= 3 && oppStreak > endedOpponentStreak) {
                            endedOpponentStreak = oppStreak;
                        }
                    }
                }
            } else {
                losses++;
                curStreak = 0;
            }
            long peak = qualifiedAfter ? Math.max(prev.getPeakRatingCenti(), afterRating) : prev.getPeakRatingCenti();
            int bits = prev.getMilestoneBits();
            List<RankedRewardEntitlement> unlocked = new ArrayList<>();
            if (participated) {
                if ((bits & 1) == 0 && validAfter >= rule.getMilestoneAvatarMatches()) {
                    bits |= 1;
                    unlocked.add(grantReward(c, playerId, seasonId, RankedRewardKind.RANKED_REWARD_AVATAR_PATTERN,
                            rule.getMilestoneAvatarMatches(), season.getSeasonKey() + "_avatar_pattern", nowMs));
                }
                if ((bits & 2) == 0 && validAfter >= rule.getMilestoneCardMatches()) {
                    bits |= 2;
                    unlocked.add(grantReward(c, playerId, seasonId, RankedRewardKind.RANKED_REWARD_CARD_BACKGROUND,
                            rule.getMilestoneCardMatches(), season.getSeasonKey() + "_card_bg", nowMs));
                }
                if ((bits & 4) == 0 && validAfter >= rule.getMilestoneTalismanMatches()) {
                    bits |= 4;
                    unlocked.add(grantReward(c, playerId, seasonId, RankedRewardKind.RANKED_REWARD_TALISMAN,
                            rule.getMilestoneTalismanMatches(), season.getSeasonKey() + "_qingyun_talisman", nowMs));
                }
                int winTarget = tables.winMilestoneMatches();
                if ((bits & 8) == 0 && winTarget > 0 && wins >= winTarget) {
                    bits |= 8;
                    unlocked.add(grantReward(c, playerId, seasonId, RankedRewardKind.RANKED_REWARD_WIN_MILESTONE,
                            winTarget, season.getSeasonKey() + "_win_milestone_" + winTarget, nowMs));
                }
            }
            MatchRankedPlayerSeasonRow nextProgress = prev.toBuilder()
                    .setSeasonId(seasonId)
                    .setPlayerId(playerId)
                    .setQualified(qualifiedAfter)
                    .setRatingCenti(afterRating)
                    .setPeakRatingCenti(peak)
                    .setValidMatches(validAfter)
                    .setWins(wins)
                    .setLosses(losses)
                    .setDraws(draws)
                    .setQualifiedAtMs(qualifiedAt)
                    .setMilestoneBits(bits)
                    .setUpdatedMs(nowMs)
                    .setCurrentWinStreak(curStreak)
                    .setBestWinStreak(bestStreak)
                    .build();
            upsertPlayerSeason(c, nextProgress);
            if (qualifiedAfter) {
                qualifiedScores.put(playerId, afterRating);
            }
            boolean revengedRival = false;
            String revengedOpponentName = "";
            int revengedWinsAfter = 0;
            int revengedLossesAfter = 0;
            if (!zeroRound) {
                boolean isDraw = effectiveOutcome == eBattleOutcome.BATTLE_OUTCOME_DRAW;
                long bestRevengeOpp = 0L;
                MatchRankedRivalryRow bestRevengePrior = null;
                for (long oppId : sortedPlayers) {
                    if (teamByPlayer.getOrDefault(oppId, 0) == teamIndex) {
                        continue;
                    }
                    MatchRankedRivalryRow prior = priorRivalries.get(new RivalryPair(playerId, oppId));
                    BattleSettlementData oppSt = settlementsByPlayer.get(oppId);
                    String oppName = (oppSt != null && !oppSt.getPlayerDisplayName().isEmpty())
                            ? oppSt.getPlayerDisplayName()
                            : (prior != null ? prior.getOpponentName() : "");
                    if (playerWon && isRivalryRevengeTarget(prior)) {
                        boolean better = bestRevengePrior == null
                                || Integer.compareUnsigned(prior.getLosses(), bestRevengePrior.getLosses()) > 0
                                || (prior.getLosses() == bestRevengePrior.getLosses()
                                        && Integer.compareUnsigned(prior.getWins(), bestRevengePrior.getWins()) > 0)
                                || (prior.getLosses() == bestRevengePrior.getLosses()
                                        && prior.getWins() == bestRevengePrior.getWins()
                                        && Long.compareUnsigned(oppId, bestRevengeOpp) < 0);
                        if (better) {
                            bestRevengeOpp = oppId;
                            bestRevengePrior = prior;
                            revengedOpponentName = oppName;
                        }
                    }
                    MatchRankedRivalryRow nextRivalry = buildNextRivalryRow(seasonId, playerId, oppId, oppName,
                            prior, isDraw, playerWon, event.getBattleId(), nowMs);
                    upsertRivalryRow(c, nextRivalry);
                }
                BattleSettlementData selfSt = settlementsByPlayer.get(playerId);
                if (bestRevengePrior != null) {
                    revengedRival = true;
                    revengedWinsAfter = bestRevengePrior.getWins() + 1;
                    revengedLossesAfter = bestRevengePrior.getLosses();
                } else if (playerWon && selfSt != null && selfSt.getRevengedRival()) {
                    revengedRival = true;
                    revengedOpponentName = selfSt.getRevengedOpponentName();
                    revengedWinsAfter = selfSt.getRevengedWinsAfter();
                    revengedLossesAfter = selfSt.getRevengedLossesAfter();
                }
                if (!ctx.getUseFreePreset() && participated && selfSt != null) {
                    collectFeaturedBuildDelta(buildDeltas, rule.getTierQingfengCenti(),
                            playerId, selfSt, afterRating, playerWon);
                }
            }
            RankedTier tierAfter = tables.tierFor(afterRating, qualifiedAfter);
            RankedBattleSettlementView view = RankedBattleSettlementView.newBuilder()
                    .setBattleId(event.getBattleId())
                    .setSeasonId(seasonId)
                    .setTeamSize(teamSize)
                    .setStatus(RankedMatchRatingStatus.RANKED_MATCH_RATING_CONFIRMED)
                    .setOutcome(effectiveOutcome)
                    .setPlayerTeamIndex(teamIndex)
                    .setTotalRounds(event.getTotalRounds())
                    .setParticipated(participated)
                    .setFled(fled)
                    .setRatingBeforeCenti(beforeRating)
                    .setRatingAfterCenti(afterRating)
                    .setRatingDeltaCenti(delta)
                    .setTierBefore(tierBefore)
                    .setTierAfter(tierAfter)
                    .setNewlyQualified(newlyQualified)
                    .setSeasonValidMatchesAfter(validAfter)
                    .addAllNewlyUnlockedRewards(unlocked)
                    .setSettledAtMs(nowMs)
                    .setCharacterPresetId(charPresetId)
                    .setPetPresetId(petPresetId)
                    .setWinStreakAfter(curStreak)
                    .setEndedOpponentStreak(endedOpponentStreak)
                    .setRevengedRival(revengedRival)
                    .setRevengedOpponentName(revengedOpponentName)
                    .setRevengedWinsAfter(revengedWinsAfter)
                    .setRevengedLossesAfter(revengedLossesAfter)
                    .build();
            byte[] rewardBlob = NetworkProbes.bytes(RankedBattleSettlementView.newBuilder()
                    .addAllNewlyUnlockedRewards(unlocked).build());
            insertBattleRow(c, MatchRankedBattleRow.newBuilder()
                    .setPlayerId(playerId)
                    .setBattleId(event.getBattleId())
                    .setSeasonId(seasonId)
                    .setTeamSize(teamSize)
                    .setStatus(RankedMatchRatingStatus.RANKED_MATCH_RATING_CONFIRMED_VALUE)
                    .setOutcome(effectiveOutcome.getNumber())
                    .setPlayerTeamIndex(teamIndex)
                    .setTotalRounds(event.getTotalRounds())
                    .setParticipated(participated)
                    .setFled(fled)
                    .setRatingBeforeCenti(beforeRating)
                    .setRatingAfterCenti(afterRating)
                    .setRatingDeltaCenti(delta)
                    .setTierBefore(tierBefore.getNumber())
                    .setTierAfter(tierAfter.getNumber())
                    .setNewlyQualified(newlyQualified)
                    .setSeasonValidMatchesAfter(validAfter)
                    .setNewlyUnlockedRewardsProto(ByteString.copyFrom(rewardBlob))
                    .setSettledAtMs(nowMs)
                    .setCharacterPresetId(charPresetId)
                    .setPetPresetId(petPresetId)
                    .setWinStreakAfter(curStreak)
                    .setEndedOpponentStreak(endedOpponentStreak)
                    .setRevengedRival(revengedRival)
                    .setRevengedOpponentName(revengedOpponentName)
                    .setRevengedWinsAfter(revengedWinsAfter)
                    .setRevengedLossesAfter(revengedLossesAfter)
                    .build());
            views.put(playerId, view);
        }
        if (!buildDeltas.isEmpty()) {
            applyFeaturedBuildDeltasTx(c, seasonId, event.getBattleId(), teamSize, event.getTotalRounds(), nowMs, buildDeltas);
        }
        long rankGen = season.getRankGeneration() != 0L ? season.getRankGeneration() : Integer.toUnsignedLong(seasonId);
        return new SeasonCommitResult(seasonId, season.getSeasonKey(), rankGen,
                Map.copyOf(qualifiedScores), Map.copyOf(views));
    }

    /** 记录作废对局（节点丢失/强制中止）：不改分、不记有效场次、不发里程碑权益，幂等落明细供客户端查询。 */
    public void recordVoidBattle(long battleId, RankedMatchContext ctx, List<Long> playerIds) throws SQLException {
        if (battleId == 0L || playerIds == null || playerIds.isEmpty()) {
            return;
        }
        Map<Long, RankedPresetSelection> loadoutByPlayer = new HashMap<>();
        if (ctx != null) {
            for (RankedMemberLoadout m : ctx.getMembersList()) {
                loadoutByPlayer.put(m.getPlayerId(), m.getPresets());
            }
        }
        int seasonId = ctx != null ? ctx.getSeasonId() : 0;
        int teamSize = ctx != null ? ctx.getTeamSize() : 0;
        for (long playerId : playerIds) {
            RankedPresetSelection p = loadoutByPlayer.get(playerId);
            recordBattleSettlement(new SettlementInput(
                    playerId, battleId, seasonId, teamSize,
                    RankedMatchRatingStatus.RANKED_MATCH_RATING_VOID,
                    eBattleOutcome.BATTLE_OUTCOME_ONGOING,
                    0, 0, false, false, 0L,
                    p != null ? p.getCharacterPresetId() : tables.rule().getDefaultCharacterPresetId(),
                    p != null ? p.getPetPresetId() : tables.rule().getDefaultPetPresetId()));
        }
    }

    /** 换季第 1 步（幂等）：将当前 ACTIVE 赛季置为 SETTLING，阻断旧季新局准入；已成局仍回流旧季结算。 */
    public MatchRankedSeasonRow beginSeasonRollover(int seasonId) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                MatchRankedSeasonRow cur = readSeasonById(c, seasonId)
                        .orElseThrow(() -> new SQLException("赛季不存在 season=" + seasonId));
                if (cur.getState() == RankedSeasonState.RANKED_SEASON_STATE_ACTIVE_VALUE) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE match_ranked_season SET state=? WHERE season_id=?")) {
                        ps.setInt(1, RankedSeasonState.RANKED_SEASON_STATE_SETTLING_VALUE);
                        ps.setInt(2, seasonId);
                        ps.executeUpdate();
                    }
                    cur = cur.toBuilder().setState(RankedSeasonState.RANKED_SEASON_STATE_SETTLING_VALUE).build();
                }
                c.commit();
                return cur;
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    /** 换季批处理单事务玩家上限，避免大赛季单事务撞 TiDB 事务体积/锁超时上限。 */
    public static final int SEAL_BATCH_SIZE = 200;

    /**
     * 换季第 2 步（幂等可恢复、分批短事务）：对 SETTLING 赛季执行封榜钩子、按 (rating DESC, qualified_at_ms ASC, player_id ASC)
     * 分批落定最终名次与荣誉（榜首须 >= 无双线 185000，否则榜首空缺）、旧季置 CLOSED 并开启 S+1（Elo 由 match_rating 继承，不预写新季榜）。
     */
    public MatchRankedSeasonRow sealSeasonAndOpenNext(int seasonId, Map<Long, String> playerNames,
                                                      RankFreezeHook freezeHook) throws Exception {
        java.util.function.LongFunction<String> nameLookup = id -> playerNames == null ? "" : playerNames.getOrDefault(id, "");
        return sealSeasonAndOpenNext(seasonId, nameLookup, freezeHook, SEAL_BATCH_SIZE);
    }

    /** 同 {@link #sealSeasonAndOpenNext(int, Map, RankFreezeHook)}，按需懒查榜首昵称并支持自定义每批人数（单测可调小验证多批次提交）。 */
    public MatchRankedSeasonRow sealSeasonAndOpenNext(int seasonId, java.util.function.LongFunction<String> championNameLookup,
                                                      RankFreezeHook freezeHook, int batchSize) throws Exception {
        int effectiveBatch = Math.max(1, batchSize);
        MatchRankedSeasonRow cur;
        try (Connection c = dataSource.getConnection()) {
            cur = readSeasonById(c, seasonId)
                    .orElseThrow(() -> new SQLException("赛季不存在 season=" + seasonId));
        }
        int nextId = seasonId + 1;
        if (cur.getState() == RankedSeasonState.RANKED_SEASON_STATE_CLOSED_VALUE) {
            try (Connection c = dataSource.getConnection()) {
                Optional<MatchRankedSeasonRow> existingNext = readSeasonById(c, nextId);
                if (existingNext.isPresent()) {
                    return existingNext.get();
                }
            }
        }
        if (cur.getState() == RankedSeasonState.RANKED_SEASON_STATE_ACTIVE_VALUE) {
            cur = beginSeasonRollover(seasonId);
        }
        if (freezeHook != null && !cur.getRankBoardSealed()) {
            freezeHook.freeze(cur);
        }

        // SETTLING 态已冻结该赛季所有新战果，可安全在事务外读取全量达标名单并排序，随后按固定批次短事务幂等落奖。
        List<MatchRankedPlayerSeasonRow> qualified = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT season_id,player_id,qualified,rating_centi,peak_rating_centi,valid_matches,wins,losses,draws,qualified_at_ms,milestone_bits,final_rank,champion_awarded,updated_ms,current_win_streak,best_win_streak "
                             + "FROM match_ranked_player_season WHERE season_id=? AND qualified=1")) {
            ps.setInt(1, seasonId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    qualified.add(scanPlayerSeason(rs));
                }
            }
        }
        qualified.sort((a, b) -> {
            int scoreCmp = Long.compare(b.getRatingCenti(), a.getRatingCenti());
            if (scoreCmp != 0) return scoreCmp;
            int timeCmp = Long.compareUnsigned(a.getQualifiedAtMs(), b.getQualifiedAtMs());
            if (timeCmp != 0) return timeCmp;
            return Long.compareUnsigned(a.getPlayerId(), b.getPlayerId());
        });
        long wushuangMin = Integer.toUnsignedLong(tables.rule().getTierWushuangCenti());
        long champId = 0L;
        String champName = "";
        long champScore = 0L;
        if (!qualified.isEmpty() && qualified.getFirst().getRatingCenti() >= wushuangMin) {
            champId = qualified.getFirst().getPlayerId();
            champScore = qualified.getFirst().getRatingCenti();
            if (championNameLookup != null) {
                String resolved = championNameLookup.apply(champId);
                champName = resolved == null ? "" : resolved;
            }
        }

        long now = clockMs.getAsLong();
        String seasonKey = cur.getSeasonKey();
        for (int start = 0; start < qualified.size(); start += effectiveBatch) {
            int end = Math.min(qualified.size(), start + effectiveBatch);
            sealQualifiedBatch(seasonId, seasonKey, qualified, start, end, champId, now);
        }

        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                MatchRankedSeasonRow locked = readSeasonByIdForUpdate(c, seasonId)
                        .orElseThrow(() -> new SQLException("赛季不存在 season=" + seasonId));
                Optional<MatchRankedSeasonRow> alreadyNext = readSeasonById(c, nextId);
                if (locked.getState() == RankedSeasonState.RANKED_SEASON_STATE_CLOSED_VALUE && alreadyNext.isPresent()) {
                    c.commit();
                    return alreadyNext.get();
                }
                releaseSeasonFeaturedReplaysTx(c, seasonId);
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE match_ranked_season SET state=?,champion_player_id=?,champion_name=?,champion_rating_centi=?,rank_board_sealed=1 WHERE season_id=?")) {
                    upd.setInt(1, RankedSeasonState.RANKED_SEASON_STATE_CLOSED_VALUE);
                    upd.setObject(2, PbMysql.uint64(champId));
                    upd.setString(3, champName);
                    upd.setLong(4, champScore);
                    upd.setInt(5, seasonId);
                    upd.executeUpdate();
                }
                if (alreadyNext.isPresent()) {
                    c.commit();
                    invalidateFeaturedCache(seasonId);
                    return alreadyNext.get();
                }
                RankedRuleTable rule = tables.rule();
                long start = now;
                long end = start + Integer.toUnsignedLong(rule.getSeasonDurationDays()) * MILLIS_PER_DAY;
                MatchRankedSeasonRow nextSeason = MatchRankedSeasonRow.newBuilder()
                        .setSeasonId(nextId)
                        .setSeasonKey("s" + nextId)
                        .setSeasonName("第" + nextId + "赛季")
                        .setRankGeneration(Integer.toUnsignedLong(nextId))
                        .setRuleVersion(rule.getRuleVersion())
                        .setState(RankedSeasonState.RANKED_SEASON_STATE_ACTIVE_VALUE)
                        .setStartMs(start)
                        .setEndMs(end)
                        .setBattleConfigId(rule.getBattleConfigId())
                        .build();
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO match_ranked_season(season_id,season_key,season_name,rank_generation,rule_version,state,start_ms,end_ms,battle_config_id,champion_player_id,champion_name,champion_rating_centi,rank_board_sealed) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                    ins.setInt(1, nextSeason.getSeasonId());
                    ins.setString(2, nextSeason.getSeasonKey());
                    ins.setString(3, nextSeason.getSeasonName());
                    ins.setObject(4, PbMysql.uint64(nextSeason.getRankGeneration()));
                    ins.setInt(5, nextSeason.getRuleVersion());
                    ins.setInt(6, nextSeason.getState());
                    ins.setObject(7, PbMysql.uint64(nextSeason.getStartMs()));
                    ins.setObject(8, PbMysql.uint64(nextSeason.getEndMs()));
                    ins.setInt(9, nextSeason.getBattleConfigId());
                    ins.setObject(10, PbMysql.uint64(0L));
                    ins.setString(11, "");
                    ins.setLong(12, 0L);
                    ins.setBoolean(13, false);
                    ins.executeUpdate();
                }
                c.commit();
                invalidateFeaturedCache(seasonId);
                return nextSeason;
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    private void sealQualifiedBatch(int seasonId, String seasonKey, List<MatchRankedPlayerSeasonRow> qualified,
                                    int start, int end, long champId, long now) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                for (int i = start; i < end; i++) {
                    MatchRankedPlayerSeasonRow row = qualified.get(i);
                    long rankPos = i + 1L;
                    boolean isChamp = champId != 0L && row.getPlayerId() == champId;
                    RankedTier peakTier = tables.tierFor(row.getPeakRatingCenti(), true);
                    if (peakTier == RankedTier.RANKED_TIER_YUNZHANG
                            || peakTier == RankedTier.RANKED_TIER_TIANSHU
                            || peakTier == RankedTier.RANKED_TIER_WUSHUANG) {
                        String tierCode = switch (peakTier) {
                            case RANKED_TIER_WUSHUANG -> "wushuang";
                            case RANKED_TIER_TIANSHU -> "tianshu";
                            default -> "yunzhang";
                        };
                        grantReward(c, row.getPlayerId(), seasonId, RankedRewardKind.RANKED_REWARD_TIER_BADGE,
                                peakTier.getNumber(), seasonKey + "_tier_badge_" + tierCode, now);
                        grantReward(c, row.getPlayerId(), seasonId, RankedRewardKind.RANKED_REWARD_TIER_TITLE,
                                peakTier.getNumber(), seasonKey + "_tier_title_" + tierCode, now);
                    }
                    if (isChamp) {
                        grantReward(c, row.getPlayerId(), seasonId, RankedRewardKind.RANKED_REWARD_CHAMPION_INSCRIPTION,
                                RankedTier.RANKED_TIER_WUSHUANG_VALUE, seasonKey + "_champion_inscription", now);
                    }
                    upsertPlayerSeason(c, row.toBuilder()
                            .setFinalRank(rankPos)
                            .setChampionAwarded(isChamp)
                            .setUpdatedMs(now)
                            .build());
                }
                c.commit();
            } catch (Exception e) {
                rollbackQuietly(c, e);
                throw e;
            }
        }
    }

    /** 崩溃恢复入口：若当前最新赛季处于 SETTLING（或已过 end_ms），自动完成封榜并开启下一季。 */
    public MatchRankedSeasonRow recoverSeasonRollover(Map<Long, String> playerNames,
                                                      RankFreezeHook freezeHook) throws Exception {
        return recoverSeasonRollover(id -> playerNames == null ? "" : playerNames.getOrDefault(id, ""), freezeHook);
    }

    /** 同 {@link #recoverSeasonRollover(Map, RankFreezeHook)}，仅在产生榜首时懒查榜首昵称。 */
    public MatchRankedSeasonRow recoverSeasonRollover(java.util.function.LongFunction<String> championNameLookup,
                                                      RankFreezeHook freezeHook) throws Exception {
        MatchRankedSeasonRow cur = ensureActiveSeason();
        long now = clockMs.getAsLong();
        if (cur.getState() == RankedSeasonState.RANKED_SEASON_STATE_SETTLING_VALUE
                || (cur.getState() == RankedSeasonState.RANKED_SEASON_STATE_ACTIVE_VALUE
                && cur.getEndMs() != 0L && Long.compareUnsigned(now, cur.getEndMs()) >= 0)) {
            return sealSeasonAndOpenNext(cur.getSeasonId(), championNameLookup, freezeHook, SEAL_BATCH_SIZE);
        }
        return cur;
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
        List<Integer> allowedSizes = tables.allowedTeamSizes(
                com.game.match.support.MatchModes.PVP_TEAM, rule.getBattleConfigId());
        if (allowedSizes.isEmpty()) {
            allowedSizes = List.of(1, 3);
        }
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
                .addAllAllowedTeamSizes(allowedSizes)
                .setUsesOwnProgression(true)
                .addAllPrimaryTeamSizes(RankedTables.PRIMARY_TEAM_SIZES)
                .addAllSpecialTeamSizes(RankedTables.SPECIAL_TEAM_SIZES)
                .setTierQingfengCenti(rule.getTierQingfengCenti())
                .setTierYunzhangCenti(rule.getTierYunzhangCenti())
                .setTierTianshuCenti(rule.getTierTianshuCenti())
                .setTierWushuangCenti(rule.getTierWushuangCenti())
                .setMilestoneWinMatches(tables.winMilestoneMatches())
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
                .setCurrentWinStreak(row.getCurrentWinStreak())
                .setBestWinStreak(row.getBestWinStreak())
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

    private Optional<MatchRankedSeasonRow> readSeasonByIdForUpdate(Connection c, int seasonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,season_key,season_name,rank_generation,rule_version,state,start_ms,end_ms,battle_config_id,champion_player_id,champion_name,champion_rating_centi,rank_board_sealed "
                        + "FROM match_ranked_season WHERE season_id=? FOR UPDATE")) {
            ps.setInt(1, seasonId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(scanSeason(rs)) : Optional.empty();
            }
        }
    }

    /** 跨季沿用 match_rating 权威 Elo；无记录或表未建时回落 150000（1500 分）。 */
    private long readCarryOverRating(Connection c, long playerId) {
        try (PreparedStatement ps = c.prepareStatement("SELECT rating_centi FROM match_rating WHERE player_id=?")) {
            ps.setObject(1, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException ignored) {
            // 仅隔离单测环境未建 match_rating 表时回落默认分
        }
        return RankedTables.INITIAL_RATING_CENTI;
    }

    /** 按 season_id 查询排位赛季元数据（供排位榜 outbox 后台投递解析 seasonKey 与代次）。 */
    public Optional<MatchRankedSeasonRow> findSeason(int seasonId) throws SQLException {
        if (seasonId <= 0) {
            return Optional.empty();
        }
        try (Connection c = dataSource.getConnection()) {
            return readSeasonById(c, seasonId);
        }
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
        return readPrefsInternal(c, playerId, false);
    }

    private Optional<MatchRankedPlayerPrefsRow> readPrefsForUpdate(Connection c, long playerId) throws SQLException {
        return readPrefsInternal(c, playerId, true);
    }

    private Optional<MatchRankedPlayerPrefsRow> readPrefsInternal(Connection c, long playerId, boolean forUpdate) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_id,character_preset_id,pet_preset_id,rule_version,equipped_talisman_code,equipped_avatar_pattern_code,equipped_card_bg_code,equipped_title_code,hide_talisman,updated_ms "
                        + "FROM match_ranked_player_prefs WHERE player_id=?" + (forUpdate ? " FOR UPDATE" : ""))) {
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
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO match_ranked_player_prefs(player_id,character_preset_id,pet_preset_id,rule_version,equipped_talisman_code,equipped_avatar_pattern_code,equipped_card_bg_code,equipped_title_code,hide_talisman,updated_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE character_preset_id=VALUES(character_preset_id),pet_preset_id=VALUES(pet_preset_id),"
                        + "rule_version=VALUES(rule_version),equipped_talisman_code=VALUES(equipped_talisman_code),"
                        + "equipped_avatar_pattern_code=VALUES(equipped_avatar_pattern_code),equipped_card_bg_code=VALUES(equipped_card_bg_code),"
                        + "equipped_title_code=VALUES(equipped_title_code),hide_talisman=VALUES(hide_talisman),updated_ms=VALUES(updated_ms)")) {
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

    private Optional<MatchRankedPlayerSeasonRow> readPlayerSeasonForUpdate(Connection c, int seasonId, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,player_id,qualified,rating_centi,peak_rating_centi,valid_matches,wins,losses,draws,qualified_at_ms,milestone_bits,final_rank,champion_awarded,updated_ms,current_win_streak,best_win_streak "
                        + "FROM match_ranked_player_season WHERE season_id=? AND player_id=? FOR UPDATE")) {
            ps.setInt(1, seasonId);
            ps.setObject(2, PbMysql.uint64(playerId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(scanPlayerSeason(rs)) : Optional.empty();
            }
        }
    }

    private Optional<MatchRankedPlayerSeasonRow> readPlayerSeason(Connection c, int seasonId, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,player_id,qualified,rating_centi,peak_rating_centi,valid_matches,wins,losses,draws,qualified_at_ms,milestone_bits,final_rank,champion_awarded,updated_ms,current_win_streak,best_win_streak "
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
                "SELECT season_id,player_id,qualified,rating_centi,peak_rating_centi,valid_matches,wins,losses,draws,qualified_at_ms,milestone_bits,final_rank,champion_awarded,updated_ms,current_win_streak,best_win_streak "
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
                .setCurrentWinStreak(rs.getInt(15))
                .setBestWinStreak(rs.getInt(16))
                .build();
    }

    private void upsertPlayerSeason(Connection c, MatchRankedPlayerSeasonRow row) throws SQLException {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO match_ranked_player_season(season_id,player_id,qualified,rating_centi,peak_rating_centi,valid_matches,wins,losses,draws,qualified_at_ms,milestone_bits,final_rank,champion_awarded,updated_ms,current_win_streak,best_win_streak) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE qualified=VALUES(qualified),rating_centi=VALUES(rating_centi),peak_rating_centi=VALUES(peak_rating_centi),"
                        + "valid_matches=VALUES(valid_matches),wins=VALUES(wins),losses=VALUES(losses),draws=VALUES(draws),"
                        + "qualified_at_ms=VALUES(qualified_at_ms),milestone_bits=VALUES(milestone_bits),final_rank=VALUES(final_rank),"
                        + "champion_awarded=VALUES(champion_awarded),updated_ms=VALUES(updated_ms),current_win_streak=VALUES(current_win_streak),best_win_streak=VALUES(best_win_streak)")) {
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
            ins.setInt(15, row.getCurrentWinStreak());
            ins.setInt(16, row.getBestWinStreak());
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
        try (PreparedStatement check = c.prepareStatement(
                "SELECT item_code,granted_at_ms FROM match_ranked_reward WHERE player_id=? AND season_id=? AND kind=? AND tier_value=?")) {
            check.setObject(1, PbMysql.uint64(playerId));
            check.setInt(2, seasonId);
            check.setInt(3, kind.getNumber());
            check.setInt(4, tierValue);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    return RankedRewardEntitlement.newBuilder()
                            .setSeasonId(seasonId)
                            .setKind(kind)
                            .setTierValue(tierValue)
                            .setItemCode(rs.getString(1))
                            .setGrantedAtMs(Long.parseUnsignedLong(rs.getString(2)))
                            .build();
                }
            }
        }
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
                        + "season_valid_matches_after,newly_unlocked_rewards_proto,settled_at_ms,character_preset_id,pet_preset_id,"
                        + "win_streak_after,ended_opponent_streak,revenged_rival,revenged_opponent_name,revenged_wins_after,revenged_losses_after "
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
                        + "season_valid_matches_after,newly_unlocked_rewards_proto,settled_at_ms,character_preset_id,pet_preset_id,"
                        + "win_streak_after,ended_opponent_streak,revenged_rival,revenged_opponent_name,revenged_wins_after,revenged_losses_after "
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
                        + "season_valid_matches_after,newly_unlocked_rewards_proto,settled_at_ms,character_preset_id,pet_preset_id,"
                        + "win_streak_after,ended_opponent_streak,revenged_rival,revenged_opponent_name,revenged_wins_after,revenged_losses_after) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
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
            ins.setInt(22, row.getWinStreakAfter());
            ins.setInt(23, row.getEndedOpponentStreak());
            ins.setBoolean(24, row.getRevengedRival());
            ins.setString(25, row.getRevengedOpponentName());
            ins.setInt(26, row.getRevengedWinsAfter());
            ins.setInt(27, row.getRevengedLossesAfter());
            ins.executeUpdate();
        }
    }

    private static MatchRankedBattleRow scanBattleRow(ResultSet rs) throws SQLException {
        byte[] blob = rs.getBytes(18);
        String revName = rs.getString(25);
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
                .setWinStreakAfter(rs.getInt(22))
                .setEndedOpponentStreak(rs.getInt(23))
                .setRevengedRival(rs.getBoolean(24))
                .setRevengedOpponentName(revName == null ? "" : revName)
                .setRevengedWinsAfter(rs.getInt(26))
                .setRevengedLossesAfter(rs.getInt(27))
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
                .setPetPresetId(row.getPetPresetId())
                .setWinStreakAfter(row.getWinStreakAfter())
                .setEndedOpponentStreak(row.getEndedOpponentStreak())
                .setRevengedRival(row.getRevengedRival())
                .setRevengedOpponentName(row.getRevengedOpponentName())
                .setRevengedWinsAfter(row.getRevengedWinsAfter())
                .setRevengedLossesAfter(row.getRevengedLossesAfter());
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

    private static MatchRankedPlayerSeasonRow defaultProgress(int seasonId, long playerId, long ratingCenti) {
        return MatchRankedPlayerSeasonRow.newBuilder()
                .setSeasonId(seasonId)
                .setPlayerId(playerId)
                .setRatingCenti(ratingCenti)
                .setPeakRatingCenti(0L)
                .build();
    }

    private static boolean ownsTitleCode(List<MatchRankedRewardRow> owned, String code) {
        return ownsCode(owned, RankedRewardKind.RANKED_REWARD_TIER_TITLE, code)
                || ownsCode(owned, RankedRewardKind.RANKED_REWARD_WIN_MILESTONE, code);
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

    // ---- 论剑 V6：宿敌恩怨账本与问道名局热门流派（对齐 Go ranked.go） ----

    /** 允许全服玩家观摩「问道名局」高段热门流派代表战报（fail-closed：查 featured_battle_id 索引）。 */
    public boolean isFeaturedRankedReplayAllowed(long battleId) {
        if (battleId == 0L) {
            return false;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM match_ranked_featured_build WHERE featured_battle_id=? LIMIT 1")) {
            ps.setObject(1, PbMysql.uint64(battleId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private static boolean isRivalryRevengeTarget(MatchRankedRivalryRow row) {
        if (row == null || (row.getWins() + row.getLosses()) == 0) {
            return false;
        }
        return row.getLastOutcome() == 2 || Integer.compareUnsigned(row.getLosses(), row.getWins()) > 0;
    }

    private static MatchRankedRivalryRow buildNextRivalryRow(int seasonId, long playerId, long opponentId,
                                                             String opponentName, MatchRankedRivalryRow prior,
                                                             boolean isDraw, boolean playerWon,
                                                             long battleId, long nowMs) {
        int wins = prior != null ? prior.getWins() : 0;
        int losses = prior != null ? prior.getLosses() : 0;
        int draws = prior != null ? prior.getDraws() : 0;
        String name = (opponentName != null && !opponentName.isEmpty())
                ? opponentName
                : (prior != null ? prior.getOpponentName() : "");
        int lastOutcome;
        if (isDraw) {
            draws++;
            lastOutcome = 3;
        } else if (playerWon) {
            wins++;
            lastOutcome = 1;
        } else {
            losses++;
            lastOutcome = 2;
        }
        return MatchRankedRivalryRow.newBuilder()
                .setSeasonId(seasonId)
                .setPlayerId(playerId)
                .setOpponentId(opponentId)
                .setOpponentName(name)
                .setWins(wins)
                .setLosses(losses)
                .setDraws(draws)
                .setLastOutcome(lastOutcome)
                .setLastBattleId(battleId)
                .setUpdatedMs(nowMs)
                .build();
    }

    private Optional<MatchRankedRivalryRow> readRivalryRowForUpdate(Connection c, int seasonId, long playerId, long opponentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,player_id,opponent_id,opponent_name,wins,losses,draws,last_outcome,last_battle_id,updated_ms "
                        + "FROM match_ranked_rivalry WHERE season_id=? AND player_id=? AND opponent_id=? FOR UPDATE")) {
            ps.setInt(1, seasonId);
            ps.setObject(2, PbMysql.uint64(playerId));
            ps.setObject(3, PbMysql.uint64(opponentId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(scanRivalryRow(rs)) : Optional.empty();
            }
        }
    }

    private Optional<MatchRankedRivalryRow> readRivalryRow(Connection c, int seasonId, long playerId, long opponentId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,player_id,opponent_id,opponent_name,wins,losses,draws,last_outcome,last_battle_id,updated_ms "
                        + "FROM match_ranked_rivalry WHERE season_id=? AND player_id=? AND opponent_id=?")) {
            ps.setInt(1, seasonId);
            ps.setObject(2, PbMysql.uint64(playerId));
            ps.setObject(3, PbMysql.uint64(opponentId));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(scanRivalryRow(rs)) : Optional.empty();
            }
        }
    }

    private void upsertRivalryRow(Connection c, MatchRankedRivalryRow row) throws SQLException {
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO match_ranked_rivalry(season_id,player_id,opponent_id,opponent_name,wins,losses,draws,last_outcome,last_battle_id,updated_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?) "
                        + "ON DUPLICATE KEY UPDATE opponent_name=VALUES(opponent_name),wins=VALUES(wins),losses=VALUES(losses),"
                        + "draws=VALUES(draws),last_outcome=VALUES(last_outcome),last_battle_id=VALUES(last_battle_id),updated_ms=VALUES(updated_ms)")) {
            ins.setInt(1, row.getSeasonId());
            ins.setObject(2, PbMysql.uint64(row.getPlayerId()));
            ins.setObject(3, PbMysql.uint64(row.getOpponentId()));
            ins.setString(4, row.getOpponentName());
            ins.setInt(5, row.getWins());
            ins.setInt(6, row.getLosses());
            ins.setInt(7, row.getDraws());
            ins.setInt(8, row.getLastOutcome());
            ins.setObject(9, PbMysql.uint64(row.getLastBattleId()));
            ins.setObject(10, PbMysql.uint64(row.getUpdatedMs()));
            ins.executeUpdate();
        }
    }

    private List<RankedRivalryEncounter> loadRivalryEncountersForRoster(Connection c, int seasonId,
                                                                        List<Long> sideA, List<Long> sideB) throws SQLException {
        List<RankedRivalryEncounter> out = new ArrayList<>();
        for (long a : sideA) {
            for (long b : sideB) {
                appendRivalryEncounter(c, seasonId, a, b, out);
                appendRivalryEncounter(c, seasonId, b, a, out);
            }
        }
        out.sort((a, b) -> {
            int cmp = Long.compareUnsigned(a.getPlayerId(), b.getPlayerId());
            return cmp != 0 ? cmp : Long.compareUnsigned(a.getOpponentId(), b.getOpponentId());
        });
        return out;
    }

    private void appendRivalryEncounter(Connection c, int seasonId, long playerId, long opponentId,
                                        List<RankedRivalryEncounter> out) throws SQLException {
        Optional<MatchRankedRivalryRow> row = readRivalryRow(c, seasonId, playerId, opponentId);
        if (row.isEmpty()) {
            return;
        }
        MatchRankedRivalryRow r = row.get();
        if (r.getWins() + r.getLosses() == 0) {
            return;
        }
        out.add(RankedRivalryEncounter.newBuilder()
                .setPlayerId(r.getPlayerId())
                .setOpponentId(r.getOpponentId())
                .setOpponentName(r.getOpponentName())
                .setWins(r.getWins())
                .setLosses(r.getLosses())
                .setRevengeTarget(isRivalryRevengeTarget(r))
                .build());
    }

    private List<RankedRivalrySummary> readTopRivals(Connection c, int seasonId, long playerId, int limit) throws SQLException {
        List<RankedRivalrySummary> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,player_id,opponent_id,opponent_name,wins,losses,draws,last_outcome,last_battle_id,updated_ms "
                        + "FROM match_ranked_rivalry WHERE season_id=? AND player_id=? "
                        + "ORDER BY (wins+losses) DESC,updated_ms DESC LIMIT ?")) {
            ps.setInt(1, seasonId);
            ps.setObject(2, PbMysql.uint64(playerId));
            ps.setInt(3, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MatchRankedRivalryRow r = scanRivalryRow(rs);
                    if (r.getWins() + r.getLosses() + r.getDraws() == 0) {
                        continue;
                    }
                    out.add(RankedRivalrySummary.newBuilder()
                            .setOpponentId(r.getOpponentId())
                            .setOpponentName(r.getOpponentName())
                            .setWins(r.getWins())
                            .setLosses(r.getLosses())
                            .setDraws(r.getDraws())
                            .setLastOutcome(r.getLastOutcome())
                            .setLastBattleId(r.getLastBattleId())
                            .setUpdatedMs(r.getUpdatedMs())
                            .build());
                }
            }
        }
        return out;
    }

    private static MatchRankedRivalryRow scanRivalryRow(ResultSet rs) throws SQLException {
        String name = rs.getString(4);
        return MatchRankedRivalryRow.newBuilder()
                .setSeasonId(rs.getInt(1))
                .setPlayerId(Long.parseUnsignedLong(rs.getString(2)))
                .setOpponentId(Long.parseUnsignedLong(rs.getString(3)))
                .setOpponentName(name == null ? "" : name)
                .setWins(rs.getInt(5))
                .setLosses(rs.getInt(6))
                .setDraws(rs.getInt(7))
                .setLastOutcome(rs.getInt(8))
                .setLastBattleId(Long.parseUnsignedLong(rs.getString(9)))
                .setUpdatedMs(Long.parseUnsignedLong(rs.getString(10)))
                .build();
    }

    private record CanonicalBuild(String buildKey, String skillCsv, List<Integer> skills) {}

    private static CanonicalBuild canonicalBuild(int sealId, int petTableId, List<Integer> rawSkills) {
        java.util.TreeSet<Integer> sorted = new java.util.TreeSet<>();
        if (rawSkills != null) {
            for (int sid : rawSkills) {
                if (sid > 0) {
                    sorted.add(sid);
                }
            }
        }
        List<Integer> norm = new ArrayList<>(4);
        for (int sid : sorted) {
            norm.add(sid);
            if (norm.size() >= 4) {
                break;
            }
        }
        if (sealId == 0 && petTableId == 0 && norm.isEmpty()) {
            return new CanonicalBuild("", "", List.of());
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < norm.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(Integer.toUnsignedString(norm.get(i)));
        }
        String csv = sb.toString();
        String key = Integer.toUnsignedString(sealId) + ":" + Integer.toUnsignedString(petTableId) + ":" + csv;
        return new CanonicalBuild(key, csv, norm);
    }

    private static List<Integer> parseSkillCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<Integer> out = new ArrayList<>(4);
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) continue;
            try {
                int v = Integer.parseUnsignedInt(trimmed);
                if (v > 0) out.add(v);
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    private static final class FeaturedBuildMatchDelta {
        final String buildKey;
        final int seasonSealId;
        final int starterPetTableId;
        final String coreSkillCsv;
        int appearances;
        int wins;
        int highTierWins;
        boolean hasWinner;
        long bestWinnerPlayerId;
        String bestWinnerName = "";
        long bestWinnerRating;

        FeaturedBuildMatchDelta(String buildKey, int seasonSealId, int starterPetTableId, String coreSkillCsv) {
            this.buildKey = buildKey;
            this.seasonSealId = seasonSealId;
            this.starterPetTableId = starterPetTableId;
            this.coreSkillCsv = coreSkillCsv;
        }
    }

    private static void collectFeaturedBuildDelta(Map<String, FeaturedBuildMatchDelta> deltas,
                                                  int tierQingfengCenti, long playerId,
                                                  BattleSettlementData st, long afterRating, boolean playerWon) {
        CanonicalBuild cb = canonicalBuild(st.getFinalSeasonSealId(), st.getStarterPetTableId(), st.getCoreSkillIdsList());
        if (cb.buildKey().isEmpty()) {
            return;
        }
        long highTierMin = tierQingfengCenti > 0 ? Integer.toUnsignedLong(tierQingfengCenti) : 152_000L;
        FeaturedBuildMatchDelta d = deltas.computeIfAbsent(cb.buildKey(),
                k -> new FeaturedBuildMatchDelta(k, st.getFinalSeasonSealId(), st.getStarterPetTableId(), cb.skillCsv()));
        d.appearances++;
        if (playerWon) {
            d.wins++;
            if (afterRating >= highTierMin) {
                d.highTierWins++;
            }
            if (!d.hasWinner
                    || afterRating > d.bestWinnerRating
                    || (afterRating == d.bestWinnerRating && Long.compareUnsigned(playerId, d.bestWinnerPlayerId) < 0)) {
                d.hasWinner = true;
                d.bestWinnerPlayerId = playerId;
                d.bestWinnerName = st.getPlayerDisplayName();
                d.bestWinnerRating = afterRating;
            }
        }
    }

    /**
     * 按 build_key 升序原子落库本局聚合流派计数（杜绝按 playerId 乱序加锁引发 AB-BA 死锁），
     * 仅在本局确有战报正文时更新 featured_battle_id，并在事务末尾按 battle_id 升序统一增减
     * {@code match_battle_report.ref_count}（对齐 Go {@code applyFeaturedBuildDeltasTx}）。
     */
    private void applyFeaturedBuildDeltasTx(Connection c, int seasonId, long battleId,
                                            int teamSize, int totalRounds, long nowMs,
                                            Map<String, FeaturedBuildMatchDelta> deltas) throws SQLException {
        if (deltas.isEmpty()) {
            return;
        }
        boolean battleHasReplay = false;
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM match_battle_report WHERE battle_id=?")) {
            ps.setObject(1, PbMysql.uint64(battleId));
            try (ResultSet rs = ps.executeQuery()) {
                battleHasReplay = rs.next();
            }
        }
        List<String> sortedKeys = new ArrayList<>(deltas.keySet());
        java.util.Collections.sort(sortedKeys);
        Map<Long, Long> reportRefDelta = new java.util.TreeMap<>(Long::compareUnsigned);
        for (String key : sortedKeys) {
            FeaturedBuildMatchDelta d = deltas.get(key);
            MatchRankedFeaturedBuildRow existing = readFeaturedBuildRow(c, seasonId, key).orElse(null);
            MatchRankedFeaturedBuildRow.Builder b = existing != null
                    ? existing.toBuilder()
                    : MatchRankedFeaturedBuildRow.newBuilder()
                            .setSeasonId(seasonId)
                            .setBuildKey(d.buildKey)
                            .setSeasonSealId(d.seasonSealId)
                            .setStarterPetTableId(d.starterPetTableId)
                            .setCoreSkillIds(d.coreSkillCsv);
            b.setAppearances(b.getAppearances() + d.appearances)
                    .setWins(b.getWins() + d.wins)
                    .setHighTierWins(b.getHighTierWins() + d.highTierWins);
            if (battleHasReplay && d.hasWinner
                    && (b.getFeaturedBattleId() == 0L || d.bestWinnerRating >= b.getFeaturedRatingCenti())) {
                long oldBattleId = b.getFeaturedBattleId();
                b.setFeaturedBattleId(battleId)
                        .setFeaturedPlayerId(d.bestWinnerPlayerId)
                        .setFeaturedPlayerName(d.bestWinnerName)
                        .setFeaturedRatingCenti(d.bestWinnerRating)
                        .setFeaturedTeamSize(teamSize)
                        .setFeaturedTotalRounds(totalRounds);
                if (oldBattleId != battleId) {
                    reportRefDelta.merge(battleId, 1L, Long::sum);
                    if (oldBattleId != 0L) {
                        reportRefDelta.merge(oldBattleId, -1L, Long::sum);
                    }
                }
            }
            b.setUpdatedMs(nowMs);
            MatchRankedFeaturedBuildRow next = b.build();
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO match_ranked_featured_build(season_id,build_key,season_seal_id,starter_pet_table_id,core_skill_ids,appearances,wins,high_tier_wins,featured_battle_id,featured_player_id,featured_player_name,featured_rating_centi,featured_team_size,featured_total_rounds,updated_ms) "
                            + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) "
                            + "ON DUPLICATE KEY UPDATE appearances=VALUES(appearances),wins=VALUES(wins),high_tier_wins=VALUES(high_tier_wins),"
                            + "featured_battle_id=VALUES(featured_battle_id),featured_player_id=VALUES(featured_player_id),featured_player_name=VALUES(featured_player_name),"
                            + "featured_rating_centi=VALUES(featured_rating_centi),featured_team_size=VALUES(featured_team_size),featured_total_rounds=VALUES(featured_total_rounds),updated_ms=VALUES(updated_ms)")) {
                ins.setInt(1, next.getSeasonId());
                ins.setString(2, next.getBuildKey());
                ins.setInt(3, next.getSeasonSealId());
                ins.setInt(4, next.getStarterPetTableId());
                ins.setString(5, next.getCoreSkillIds());
                ins.setInt(6, next.getAppearances());
                ins.setInt(7, next.getWins());
                ins.setInt(8, next.getHighTierWins());
                ins.setObject(9, PbMysql.uint64(next.getFeaturedBattleId()));
                ins.setObject(10, PbMysql.uint64(next.getFeaturedPlayerId()));
                ins.setString(11, next.getFeaturedPlayerName());
                ins.setLong(12, next.getFeaturedRatingCenti());
                ins.setInt(13, next.getFeaturedTeamSize());
                ins.setInt(14, next.getFeaturedTotalRounds());
                ins.setObject(15, PbMysql.uint64(next.getUpdatedMs()));
                ins.executeUpdate();
            }
        }
        com.game.match.report.BattleReportStore.adjustRefCounts(c, reportRefDelta);
        invalidateFeaturedCache(seasonId);
    }

    /** 换季封榜释放旧季全部热门流派代表战报的 ref_count 护航引用，避免旧赛季永久钉死录像正文（对齐 Go ranked_rollover.go）。 */
    private static void releaseSeasonFeaturedReplaysTx(Connection c, int seasonId) throws SQLException {
        Map<Long, Integer> released = new java.util.TreeMap<>(Long::compareUnsigned);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT featured_battle_id FROM match_ranked_featured_build WHERE season_id=? AND featured_battle_id>0")) {
            ps.setInt(1, seasonId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long bid = Long.parseUnsignedLong(rs.getString(1));
                    if (bid != 0L) {
                        released.merge(bid, 1, Integer::sum);
                    }
                }
            }
        }
        if (released.isEmpty()) {
            return;
        }
        try (PreparedStatement upd = c.prepareStatement(
                "UPDATE match_ranked_featured_build SET featured_battle_id=0 WHERE season_id=? AND featured_battle_id>0")) {
            upd.setInt(1, seasonId);
            upd.executeUpdate();
        }
        com.game.match.report.BattleReportStore.releaseBodies(c, released);
    }

    private List<RankedFeaturedBuildView> readTopFeaturedBuildsCached(Connection c, int seasonId, int limit) throws SQLException {
        long now = clockMs.getAsLong();
        FeaturedCacheKey key = new FeaturedCacheKey(seasonId, limit);
        FeaturedCacheEntry cached = featuredCache.get(key);
        if (cached != null && now >= cached.loadedAtMs() && (now - cached.loadedAtMs()) < FEATURED_CACHE_TTL_MS) {
            return cached.builds();
        }
        List<RankedFeaturedBuildView> fresh = List.copyOf(readTopFeaturedBuilds(c, seasonId, limit));
        featuredCache.put(key, new FeaturedCacheEntry(now, fresh));
        return fresh;
    }

    private void invalidateFeaturedCache(int seasonId) {
        featuredCache.keySet().removeIf(k -> k.seasonId() == seasonId);
    }

    /**
     * 论剑 V7「不服再战」权威防伪校验（对齐 Go {@code ValidateRankedRematchSource}）：
     * 1. {@code sourceBattleId} 必须是双方刚刚结算的正式自养 1V1 论剑排位对局（{@code season_id > 0}、{@code team_size == 1}、
     *    非作废、两人分属敌对阵营）；
     * 2. 距终局落账时间不超过 {@code maxAgeMs}（默认 10 分钟），防止伪造任意 battle_id 骚扰陌生人或重放陈年旧局。
     */
    public boolean validateRankedRematchSource(long sourceBattleId, long challengerId, long targetId, long maxAgeMs)
            throws SQLException {
        if (sourceBattleId == 0L || challengerId == 0L || targetId == 0L || challengerId == targetId) {
            return false;
        }
        try (Connection c = dataSource.getConnection()) {
            Optional<MatchRankedBattleRow> rowA = readBattleRow(c, challengerId, sourceBattleId);
            Optional<MatchRankedBattleRow> rowB = readBattleRow(c, targetId, sourceBattleId);
            if (rowA.isEmpty() || rowB.isEmpty()) {
                return false;
            }
            MatchRankedBattleRow a = rowA.get();
            MatchRankedBattleRow b = rowB.get();
            if (a.getSeasonId() == 0 || a.getSeasonId() != b.getSeasonId()
                    || a.getTeamSize() != 1 || b.getTeamSize() != 1
                    || a.getStatus() != RankedMatchRatingStatus.RANKED_MATCH_RATING_CONFIRMED_VALUE
                    || b.getStatus() != RankedMatchRatingStatus.RANKED_MATCH_RATING_CONFIRMED_VALUE
                    || a.getPlayerTeamIndex() == b.getPlayerTeamIndex()) {
                return false;
            }
            if (maxAgeMs > 0L) {
                long now = clockMs.getAsLong();
                long settled = a.getSettledAtMs();
                if (Long.compareUnsigned(now, settled) > 0
                        && Long.compareUnsigned(now - settled, maxAgeMs) > 0) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 删角收尾清理（对齐 Go {@code role_fence_purge.go:11-26, 186-258}）：
     * 清空该角色在 {@code match_ranked_featured_build} 的展示名、清空对手宿敌行里的对方名并删除本人宿敌账本行，
     * 随后整删该玩家在论剑四张个人表（{@code match_ranked_player_prefs / player_season / reward / battle}）中的全部行。
     */
    public static void purgePlayerRankedData(Connection c, long playerId) throws SQLException {
        java.util.TreeSet<Integer> seasons = new java.util.TreeSet<>();
        String[] seasonQueries = {
                "SELECT DISTINCT season_id FROM match_ranked_player_season WHERE player_id=?",
                "SELECT DISTINCT season_id FROM match_ranked_rivalry WHERE player_id=?",
                "SELECT DISTINCT season_id FROM match_ranked_featured_build WHERE featured_player_id=?"
        };
        for (String sql : seasonQueries) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setObject(1, PbMysql.uint64(playerId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int sid = rs.getInt(1);
                        if (sid != 0) {
                            seasons.add(sid);
                        }
                    }
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT season_id FROM match_ranked_season");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int sid = rs.getInt(1);
                if (sid != 0) {
                    seasons.add(sid);
                }
            }
        }
        for (int seasonId : seasons) {
            try (PreparedStatement upd = c.prepareStatement(
                    "UPDATE match_ranked_featured_build SET featured_player_name='' WHERE season_id=? AND featured_player_id=?")) {
                upd.setInt(1, seasonId);
                upd.setObject(2, PbMysql.uint64(playerId));
                upd.executeUpdate();
            }
            List<Long> opponents = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT opponent_id FROM match_ranked_rivalry WHERE season_id=? AND player_id=? ORDER BY opponent_id")) {
                ps.setInt(1, seasonId);
                ps.setObject(2, PbMysql.uint64(playerId));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        opponents.add(Long.parseUnsignedLong(rs.getString(1)));
                    }
                }
            }
            for (long oppId : opponents) {
                try (PreparedStatement upd = c.prepareStatement(
                        "UPDATE match_ranked_rivalry SET opponent_name='' WHERE season_id=? AND player_id=? AND opponent_id=?")) {
                    upd.setInt(1, seasonId);
                    upd.setObject(2, PbMysql.uint64(oppId));
                    upd.setObject(3, PbMysql.uint64(playerId));
                    upd.executeUpdate();
                }
            }
            if (!opponents.isEmpty()) {
                try (PreparedStatement del = c.prepareStatement(
                        "DELETE FROM match_ranked_rivalry WHERE season_id=? AND player_id=?")) {
                    del.setInt(1, seasonId);
                    del.setObject(2, PbMysql.uint64(playerId));
                    del.executeUpdate();
                }
            }
        }
        String[] personalDeletes = {
                "DELETE FROM match_ranked_player_prefs WHERE player_id=?",
                "DELETE FROM match_ranked_player_season WHERE player_id=?",
                "DELETE FROM match_ranked_reward WHERE player_id=?",
                "DELETE FROM match_ranked_battle WHERE player_id=?"
        };
        for (String delSql : personalDeletes) {
            try (PreparedStatement del = c.prepareStatement(delSql)) {
                del.setObject(1, PbMysql.uint64(playerId));
                del.executeUpdate();
            }
        }
    }

    private Optional<MatchRankedFeaturedBuildRow> readFeaturedBuildRow(Connection c, int seasonId, String buildKey) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,build_key,season_seal_id,starter_pet_table_id,core_skill_ids,appearances,wins,high_tier_wins,featured_battle_id,featured_player_id,featured_player_name,featured_rating_centi,featured_team_size,featured_total_rounds,updated_ms "
                        + "FROM match_ranked_featured_build WHERE season_id=? AND build_key=? FOR UPDATE")) {
            ps.setInt(1, seasonId);
            ps.setString(2, buildKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(scanFeaturedBuildRow(rs)) : Optional.empty();
            }
        }
    }

    private List<RankedFeaturedBuildView> readTopFeaturedBuilds(Connection c, int seasonId, int limit) throws SQLException {
        List<RankedFeaturedBuildView> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT season_id,build_key,season_seal_id,starter_pet_table_id,core_skill_ids,appearances,wins,high_tier_wins,featured_battle_id,featured_player_id,featured_player_name,featured_rating_centi,featured_team_size,featured_total_rounds,updated_ms "
                        + "FROM match_ranked_featured_build WHERE season_id=? AND wins>0 "
                        + "ORDER BY high_tier_wins DESC,wins DESC,featured_rating_centi DESC,updated_ms DESC LIMIT ?")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    MatchRankedFeaturedBuildRow r = scanFeaturedBuildRow(rs);
                    out.add(RankedFeaturedBuildView.newBuilder()
                            .setBuildKey(r.getBuildKey())
                            .setSeasonSealId(r.getSeasonSealId())
                            .setStarterPetTableId(r.getStarterPetTableId())
                            .addAllCoreSkillIds(parseSkillCsv(r.getCoreSkillIds()))
                            .setAppearances(r.getAppearances())
                            .setWins(r.getWins())
                            .setHighTierWins(r.getHighTierWins())
                            .setFeaturedBattleId(r.getFeaturedBattleId())
                            .setFeaturedPlayerId(r.getFeaturedPlayerId())
                            .setFeaturedPlayerName(r.getFeaturedPlayerName())
                            .setFeaturedRatingCenti(r.getFeaturedRatingCenti())
                            .setFeaturedTeamSize(r.getFeaturedTeamSize())
                            .setFeaturedTotalRounds(r.getFeaturedTotalRounds())
                            .setUpdatedMs(r.getUpdatedMs())
                            .build());
                }
            }
        }
        return out;
    }

    private static MatchRankedFeaturedBuildRow scanFeaturedBuildRow(ResultSet rs) throws SQLException {
        String csv = rs.getString(5);
        String name = rs.getString(11);
        return MatchRankedFeaturedBuildRow.newBuilder()
                .setSeasonId(rs.getInt(1))
                .setBuildKey(rs.getString(2))
                .setSeasonSealId(rs.getInt(3))
                .setStarterPetTableId(rs.getInt(4))
                .setCoreSkillIds(csv == null ? "" : csv)
                .setAppearances(rs.getInt(6))
                .setWins(rs.getInt(7))
                .setHighTierWins(rs.getInt(8))
                .setFeaturedBattleId(Long.parseUnsignedLong(rs.getString(9)))
                .setFeaturedPlayerId(Long.parseUnsignedLong(rs.getString(10)))
                .setFeaturedPlayerName(name == null ? "" : name)
                .setFeaturedRatingCenti(rs.getLong(12))
                .setFeaturedTeamSize(rs.getInt(13))
                .setFeaturedTotalRounds(rs.getInt(14))
                .setUpdatedMs(Long.parseUnsignedLong(rs.getString(15)))
                .build();
    }
}
