package com.game.match.tournament;

import com.game.match.feature.MatchFeatureTables;
import com.game.match.feature.pb.MatchTournamentChampionMembersBlob;
import com.game.match.feature.pb.MatchTournamentMemberRow;
import com.game.proto.BattlePetSettlementData;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.TournamentBattleContext;
import com.game.proto.TournamentLeylineElement;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleOutcome;
import com.game.proto.mail.MailAttachments;
import com.game.proto.mail.MailItem;
import com.game.proto.match.GetTournamentHighlightsResponse;
import com.game.proto.match.GetTournamentOverviewResponse;
import com.game.proto.match.GetTournamentPredictionBoardResponse;
import com.game.proto.match.ListTournamentStandingsResponse;
import com.game.proto.match.TournamentHighlightEntry;
import com.game.proto.match.TournamentHighlightTag;
import com.game.proto.match.TournamentInviteView;
import com.game.proto.match.TournamentMatchPhase;
import com.game.proto.match.TournamentMatchState;
import com.game.proto.match.TournamentMatchView;
import com.game.proto.match.TournamentMemberRole;
import com.game.proto.match.TournamentMemberView;
import com.game.proto.match.TournamentPlayerRewardView;
import com.game.proto.match.TournamentPredictionEntryView;
import com.game.proto.match.TournamentPredictionRankEntry;
import com.game.proto.match.TournamentRewardDeliveryState;
import com.game.proto.match.TournamentRewardItem;
import com.game.proto.match.TournamentRewardTierKind;
import com.game.proto.match.TournamentSeasonView;
import com.game.proto.match.TournamentSquadState;
import com.game.proto.match.TournamentSquadView;
import com.game.proto.match.TournamentStage;
import com.game.table.MatchErrorTip;
import com.game.table.TournamentRewardTable;
import com.game.table.TournamentRuleTable;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.sql.DataSource;

/**
 * 全服赛季战队锦标赛《群仙问道赛》V1 SQL 权威存储（兼容 MySQL/TiDB 与 H2 单测模式，事务内绝不调 RPC）。
 */
public final class TournamentStore {

    private static final long MILLIS_PER_DAY = 86_400_000L;

    public static final class DomainException extends RuntimeException {
        private final int tipId;

        public DomainException(int tipId) {
            super("tournament error tip=" + tipId);
            this.tipId = tipId;
        }

        public int tipId() {
            return tipId;
        }
    }

    public record LeaveOutcome(boolean disbanded, TournamentSquadView squad) {
    }

    public record LaunchSpec(long matchId, int seasonId, TournamentMatchPhase phase, int roundIndex,
                             int battleConfigId, List<Long> teamA, List<Long> teamB, boolean featured,
                             int bestOf, int gameIndex, int winsA, int winsB,
                             TournamentLeylineElement leylineElement,
                             long squadAId, String squadAName, int squadAEmblemId,
                             long squadBId, String squadBName, int squadBEmblemId,
                             boolean isWarmup, boolean isThirdPlace) {
        public TournamentBattleContext toBattleContext() {
            return toBattleContext(0);
        }

        public TournamentBattleContext toBattleContext(int skillBanSlotsPerTeam) {
            return TournamentBattleContext.newBuilder()
                    .setSeasonId(seasonId)
                    .setMatchId(matchId)
                    .setBestOf(Math.max(1, bestOf))
                    .setGameIndex(Math.max(1, gameIndex))
                    .setWinsA(winsA)
                    .setWinsB(winsB)
                    .setLeylineElement(leylineElement == null ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED : leylineElement)
                    .setSquadAId(squadAId)
                    .setSquadAName(squadAName == null ? "" : squadAName)
                    .setSquadBId(squadBId)
                    .setSquadBName(squadBName == null ? "" : squadBName)
                    .setSquadAEmblemId(emblemOrDefault(squadAEmblemId))
                    .setSquadBEmblemId(emblemOrDefault(squadBEmblemId))
                    .setIsWarmup(isWarmup)
                    .setIsThirdPlace(isThirdPlace)
                    .setSkillBanSlotsPerTeam(skillBanSlotsPerTeam)
                    .build();
        }
    }

    public record PrepareMatchOutcome(TournamentMatchView match, LaunchSpec launch) {
    }

    public record ClaimableReward(long rewardId, int seasonId, int tierKind, int tierId, byte[] attachments) {
    }

    public record SaluteOutcome(int seasonId, int saluteDay, long championSaluteCount, long rewardId) {
    }

    public record PredictOutcome(int countA, int countB) {
    }

    private record SeasonRow(int seasonId, String seasonKey, String seasonName, int ruleVersion, int stage,
                             long registrationStartMs, long qualifierStartMs, long knockoutStartMs,
                             long settleStartMs, long endMs, int battleTeamSize, int rosterMaxSize,
                             int knockoutTeamCount, long championSquadId, String championSquadName,
                             String championMemberSummary, long settledMs, long updatedMs,
                             byte[] championMembersBlob, long championSaluteCount,
                             int championEmblemId, long thirdPlaceSquadId, String thirdPlaceSquadName,
                             int thirdPlaceEmblemId, long finalMvpPlayerId, String finalMvpPlayerName,
                             int finalMvpScore, int championHonorBits) {
    }

    private record SquadRow(long squadId, int seasonId, String name, String nameNorm, long captainPlayerId,
                            int state, int memberCount, int qualifierPoints, int qualifierPlayed,
                            int qualifierWins, int qualifierLosses, int qualifierDraws,
                            int seedRank, int finalRank, int cheerCount, long registeredMs, long updatedMs,
                            int emblemId, int honorBits) {
    }

    private record MatchRow(long matchId, int seasonId, int phase, int roundIndex, int slotIndex,
                            long squadAId, long squadBId, byte[] lineupA, byte[] lineupB,
                            boolean readyA, boolean readyB, int state, long battleId, long winnerSquadId,
                            int outcome, boolean featuredSpectate, long scheduledStartMs, long deadlineMs,
                            long finishedMs, long updatedMs,
                            int bestOf, int currentGameIndex, int winsA, int winsB,
                            int currentLeylineElement, String leylineSequence, long intermissionDeadlineMs,
                            boolean isThirdPlace, long mvpPlayerId, String mvpPlayerName, long mvpSquadId,
                            int mvpScore, long mvpDamage, long mvpHealing, int mvpControls,
                            int bannedLeylineA, int bannedLeylineB) {
    }

    private static int emblemOrDefault(int id) {
        return TournamentRules.isValidEmblemId(id) ? id : TournamentRules.DEFAULT_EMBLEM_ID;
    }

    private final DataSource dataSource;
    private final TournamentTables tables;
    private final LongSupplier clockMs;

    public TournamentStore(DataSource dataSource, TournamentTables tables, LongSupplier clockMs) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
    }

    public TournamentTables tables() {
        return tables;
    }

    public long nowMs() {
        return clockMs.getAsLong();
    }

    private void requireOpen() {
        if (!tables.rule().getEnabled()) {
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentNotOpen_VALUE);
        }
    }

    public TournamentSquadView createSquad(long playerId, String playerName, int zoneId, String squadName, long squadId) throws SQLException {
        return createSquad(playerId, playerName, zoneId, squadName, 0, squadId);
    }

    public TournamentSquadView createSquad(long playerId, String playerName, int zoneId, String squadName, int emblemId, long squadId) throws SQLException {
        requireOpen();
        TournamentRules.NormalizedName normalized = TournamentRules.normalizeSquadName(squadName)
                .orElseThrow(() -> new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNameInvalid_VALUE));
        int normEmblem = TournamentRules.normalizeEmblemId(emblemId)
                .orElseThrow(() -> new DomainException(MatchErrorTip.match_error.kMatchTournamentEmblemInvalid_VALUE));
        if (playerId == 0L || squadId == 0L) {
            throw new DomainException(MatchErrorTip.match_error.kMatchInternal_VALUE);
        }
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() != TournamentStage.TOURNAMENT_STAGE_REGISTRATION_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            if (findPlayerSquadIdTx(c, cur.seasonId(), playerId) != 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentAlreadyInSquad_VALUE);
            }
            if (squadNameExistsTx(c, cur.seasonId(), normalized.norm())) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNameInvalid_VALUE);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + MatchFeatureTables.TOURNAMENT_SQUAD
                            + "(squad_id,season_id,name,name_norm,captain_player_id,state,member_count,"
                            + "qualifier_points,qualifier_played,qualifier_wins,qualifier_losses,qualifier_draws,"
                            + "seed_rank,final_rank,cheer_count,registered_ms,updated_ms,emblem_id,honor_bits) VALUES(?,?,?,?,?,?,1,0,0,0,0,0,0,0,0,0,?,?,0)")) {
                ps.setLong(1, squadId);
                ps.setInt(2, cur.seasonId());
                ps.setString(3, normalized.display());
                ps.setString(4, normalized.norm());
                ps.setLong(5, playerId);
                ps.setInt(6, TournamentSquadState.TOURNAMENT_SQUAD_STATE_DRAFT_VALUE);
                ps.setLong(7, now);
                ps.setInt(8, normEmblem);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + MatchFeatureTables.TOURNAMENT_MEMBER
                            + "(season_id,player_id,squad_id,role,starter_slot,player_name,zone_id,joined_ms,class_id,gender,appearance_id) VALUES(?,?,?,?,1,?,?,?,0,0,'')")) {
                ps.setInt(1, cur.seasonId());
                ps.setLong(2, playerId);
                ps.setLong(3, squadId);
                ps.setInt(4, TournamentMemberRole.TOURNAMENT_MEMBER_ROLE_CAPTAIN_VALUE);
                ps.setString(5, playerName == null ? "" : playerName);
                ps.setInt(6, zoneId);
                ps.setLong(7, now);
                ps.executeUpdate();
            }
            SquadRow sq = readSquadForUpdateTx(c, squadId);
            return toSquadView(sq, loadMembersTx(c, squadId));
        });
    }

    public TournamentInviteView inviteMember(long captainId, long targetPlayerId, String inviterName) throws SQLException {
        requireOpen();
        if (captainId == 0L || targetPlayerId == 0L || captainId == targetPlayerId) {
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentLineupInvalid_VALUE);
        }
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() != TournamentStage.TOURNAMENT_STAGE_REGISTRATION_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            long squadId = findPlayerSquadIdTx(c, cur.seasonId(), captainId);
            if (squadId == 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            SquadRow sq = readSquadForUpdateTx(c, squadId);
            if (sq.captainPlayerId() != captainId) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentNotCaptain_VALUE);
            }
            if (sq.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_DRAFT_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadLocked_VALUE);
            }
            if (sq.memberCount() >= cur.rosterMaxSize()) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadFull_VALUE);
            }
            if (findPlayerSquadIdTx(c, cur.seasonId(), targetPlayerId) != 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentAlreadyInSquad_VALUE);
            }
            long ttlSec = tables.rule().getInviteTtlSeconds() > 0 ? tables.rule().getInviteTtlSeconds() : 259200L;
            long expiresMs = now + ttlSec * 1000L;
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_INVITE
                            + " SET inviter_player_id=?,state=1,created_ms=?,expires_ms=? WHERE squad_id=? AND invitee_player_id=?")) {
                ps.setLong(1, captainId);
                ps.setLong(2, now);
                ps.setLong(3, expiresMs);
                ps.setLong(4, squadId);
                ps.setLong(5, targetPlayerId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + MatchFeatureTables.TOURNAMENT_INVITE
                                + "(squad_id,invitee_player_id,season_id,inviter_player_id,state,created_ms,expires_ms) VALUES(?,?,?,?,1,?,?)")) {
                    ps.setLong(1, squadId);
                    ps.setLong(2, targetPlayerId);
                    ps.setInt(3, cur.seasonId());
                    ps.setLong(4, captainId);
                    ps.setLong(5, now);
                    ps.setLong(6, expiresMs);
                    ps.executeUpdate();
                }
            }
            return TournamentInviteView.newBuilder()
                    .setSquadId(squadId)
                    .setSeasonId(cur.seasonId())
                    .setSquadName(sq.name())
                    .setInviterPlayerId(captainId)
                    .setInviterName(inviterName == null ? "" : inviterName)
                    .setCurrentMemberCount(sq.memberCount())
                    .setRosterMaxSize(cur.rosterMaxSize())
                    .setCreatedMs(now)
                    .setExpiresMs(expiresMs)
                    .build();
        });
    }

    public TournamentSquadView respondInvite(long playerId, String playerName, int zoneId, long squadId, boolean accept) throws SQLException {
        requireOpen();
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            int invSeason;
            int invState;
            long invExpires;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT season_id,state,expires_ms FROM " + MatchFeatureTables.TOURNAMENT_INVITE
                            + " WHERE squad_id=? AND invitee_player_id=? FOR UPDATE")) {
                ps.setLong(1, squadId);
                ps.setLong(2, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new DomainException(MatchErrorTip.match_error.kMatchTournamentInviteNotFound_VALUE);
                    }
                    invSeason = rs.getInt(1);
                    invState = rs.getInt(2);
                    invExpires = rs.getLong(3);
                }
            }
            if (invSeason != cur.seasonId() || invState != 1 || invExpires <= now) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentInviteNotFound_VALUE);
            }
            if (!accept) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_INVITE + " SET state=3 WHERE squad_id=? AND invitee_player_id=?")) {
                    ps.setLong(1, squadId);
                    ps.setLong(2, playerId);
                    ps.executeUpdate();
                }
                return null;
            }
            if (cur.stage() != TournamentStage.TOURNAMENT_STAGE_REGISTRATION_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            SquadRow sq = readSquadForUpdateTx(c, squadId);
            if (sq == null) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            if (sq.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_DRAFT_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadLocked_VALUE);
            }
            if (sq.memberCount() >= cur.rosterMaxSize()) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadFull_VALUE);
            }
            if (findPlayerSquadIdTx(c, cur.seasonId(), playerId) != 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentAlreadyInSquad_VALUE);
            }
            List<TournamentMemberView> members = loadMembersTx(c, squadId);
            Set<Integer> usedSlots = new HashSet<>();
            for (TournamentMemberView m : members) {
                if (m.getStarterSlot() > 0) {
                    usedSlots.add(m.getStarterSlot());
                }
            }
            int role = TournamentMemberRole.TOURNAMENT_MEMBER_ROLE_SUBSTITUTE_VALUE;
            int slot = 0;
            for (int cand = 1; cand <= cur.battleTeamSize(); cand++) {
                if (!usedSlots.contains(cand)) {
                    role = TournamentMemberRole.TOURNAMENT_MEMBER_ROLE_STARTER_VALUE;
                    slot = cand;
                    break;
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + MatchFeatureTables.TOURNAMENT_MEMBER
                            + "(season_id,player_id,squad_id,role,starter_slot,player_name,zone_id,joined_ms,class_id,gender,appearance_id) VALUES(?,?,?,?,?,?,?,?,0,0,'')")) {
                ps.setInt(1, cur.seasonId());
                ps.setLong(2, playerId);
                ps.setLong(3, squadId);
                ps.setInt(4, role);
                ps.setInt(5, slot);
                ps.setString(6, playerName == null ? "" : playerName);
                ps.setInt(7, zoneId);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD + " SET member_count=?,updated_ms=? WHERE squad_id=?")) {
                ps.setInt(1, sq.memberCount() + 1);
                ps.setLong(2, now);
                ps.setLong(3, squadId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_INVITE + " SET state=2 WHERE squad_id=? AND invitee_player_id=?")) {
                ps.setLong(1, squadId);
                ps.setLong(2, playerId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_INVITE + " SET state=4 WHERE invitee_player_id=? AND season_id=? AND state=1 AND squad_id<>?")) {
                ps.setLong(1, playerId);
                ps.setInt(2, cur.seasonId());
                ps.setLong(3, squadId);
                ps.executeUpdate();
            }
            return toSquadView(readSquadForUpdateTx(c, squadId), loadMembersTx(c, squadId));
        });
    }

    public LeaveOutcome leaveSquad(long actorId, long targetPlayerId) throws SQLException {
        requireOpen();
        long target = targetPlayerId == 0L ? actorId : targetPlayerId;
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() != TournamentStage.TOURNAMENT_STAGE_REGISTRATION_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            long squadId = findPlayerSquadIdTx(c, cur.seasonId(), actorId);
            if (squadId == 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            SquadRow sq = readSquadForUpdateTx(c, squadId);
            if (sq.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_DRAFT_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadLocked_VALUE);
            }
            if (target != actorId && sq.captainPlayerId() != actorId) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentNotCaptain_VALUE);
            }
            if (findPlayerSquadIdTx(c, cur.seasonId(), target) != squadId) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM " + MatchFeatureTables.TOURNAMENT_MEMBER + " WHERE season_id=? AND player_id=?")) {
                ps.setInt(1, cur.seasonId());
                ps.setLong(2, target);
                ps.executeUpdate();
            }
            List<TournamentMemberView> remaining = loadMembersTx(c, squadId);
            if (remaining.isEmpty()) {
                String releasedNorm = "__disbanded_" + Long.toUnsignedString(squadId) + "_" + sq.nameNorm();
                if (releasedNorm.length() > 190) {
                    releasedNorm = releasedNorm.substring(0, 190);
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                                + " SET state=?,member_count=0,name_norm=?,updated_ms=? WHERE squad_id=?")) {
                    ps.setInt(1, TournamentSquadState.TOURNAMENT_SQUAD_STATE_DISBANDED_VALUE);
                    ps.setString(2, releasedNorm);
                    ps.setLong(3, now);
                    ps.setLong(4, squadId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_INVITE + " SET state=4 WHERE squad_id=? AND state=1")) {
                    ps.setLong(1, squadId);
                    ps.executeUpdate();
                }
                return new LeaveOutcome(true, null);
            }
            long captainId = sq.captainPlayerId();
            if (target == captainId) {
                TournamentMemberView nextCap = remaining.get(0);
                captainId = nextCap.getPlayerId();
                int nextSlot = nextCap.getStarterSlot() > 0 ? nextCap.getStarterSlot() : 1;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MEMBER
                                + " SET role=?,starter_slot=? WHERE season_id=? AND player_id=?")) {
                    ps.setInt(1, TournamentMemberRole.TOURNAMENT_MEMBER_ROLE_CAPTAIN_VALUE);
                    ps.setInt(2, nextSlot);
                    ps.setInt(3, cur.seasonId());
                    ps.setLong(4, captainId);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                            + " SET captain_player_id=?,member_count=?,updated_ms=? WHERE squad_id=?")) {
                ps.setLong(1, captainId);
                ps.setInt(2, remaining.size());
                ps.setLong(3, now);
                ps.setLong(4, squadId);
                ps.executeUpdate();
            }
            TournamentSquadView updated = target != actorId
                    ? toSquadView(readSquadForUpdateTx(c, squadId), loadMembersTx(c, squadId))
                    : null;
            return new LeaveOutcome(false, updated);
        });
    }

    public TournamentSquadView updateLineup(long captainId, List<Long> starterIds) throws SQLException {
        requireOpen();
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() == TournamentStage.TOURNAMENT_STAGE_SETTLED_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            long squadId = findPlayerSquadIdTx(c, cur.seasonId(), captainId);
            if (squadId == 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            SquadRow sq = readSquadForUpdateTx(c, squadId);
            if (sq.captainPlayerId() != captainId) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentNotCaptain_VALUE);
            }
            if (starterIds == null || starterIds.size() != cur.battleTeamSize()) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentLineupInvalid_VALUE);
            }
            List<TournamentMemberView> members = loadMembersTx(c, squadId);
            Set<Long> memberSet = new HashSet<>();
            for (TournamentMemberView m : members) {
                memberSet.add(m.getPlayerId());
            }
            Map<Long, Integer> slotByPid = new HashMap<>();
            for (int i = 0; i < starterIds.size(); i++) {
                long pid = starterIds.get(i);
                if (pid == 0L || !memberSet.contains(pid) || slotByPid.containsKey(pid)) {
                    throw new DomainException(MatchErrorTip.match_error.kMatchTournamentLineupInvalid_VALUE);
                }
                slotByPid.put(pid, i + 1);
            }
            for (TournamentMemberView m : members) {
                int slot = slotByPid.getOrDefault(m.getPlayerId(), 0);
                int role = m.getPlayerId() == sq.captainPlayerId()
                        ? TournamentMemberRole.TOURNAMENT_MEMBER_ROLE_CAPTAIN_VALUE
                        : (slot > 0
                        ? TournamentMemberRole.TOURNAMENT_MEMBER_ROLE_STARTER_VALUE
                        : TournamentMemberRole.TOURNAMENT_MEMBER_ROLE_SUBSTITUTE_VALUE);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MEMBER
                                + " SET role=?,starter_slot=? WHERE season_id=? AND player_id=?")) {
                    ps.setInt(1, role);
                    ps.setInt(2, slot);
                    ps.setInt(3, cur.seasonId());
                    ps.setLong(4, m.getPlayerId());
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD + " SET updated_ms=? WHERE squad_id=?")) {
                ps.setLong(1, now);
                ps.setLong(2, squadId);
                ps.executeUpdate();
            }
            return toSquadView(sq, loadMembersTx(c, squadId));
        });
    }

    public TournamentSquadView registerSquad(long captainId) throws SQLException {
        requireOpen();
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() != TournamentStage.TOURNAMENT_STAGE_REGISTRATION_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            long squadId = findPlayerSquadIdTx(c, cur.seasonId(), captainId);
            if (squadId == 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            SquadRow sq = readSquadForUpdateTx(c, squadId);
            if (sq.captainPlayerId() != captainId) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentNotCaptain_VALUE);
            }
            if (sq.state() == TournamentSquadState.TOURNAMENT_SQUAD_STATE_REGISTERED_VALUE) {
                return toSquadView(sq, loadMembersTx(c, squadId));
            }
            if (sq.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_DRAFT_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadLocked_VALUE);
            }
            if (sq.memberCount() < cur.battleTeamSize()) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentMemberNotEnough_VALUE);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                            + " SET state=?,registered_ms=?,updated_ms=? WHERE squad_id=?")) {
                ps.setInt(1, TournamentSquadState.TOURNAMENT_SQUAD_STATE_REGISTERED_VALUE);
                ps.setLong(2, now);
                ps.setLong(3, now);
                ps.setLong(4, squadId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_INVITE + " SET state=4 WHERE squad_id=? AND state=1")) {
                ps.setLong(1, squadId);
                ps.executeUpdate();
            }
            return toSquadView(readSquadForUpdateTx(c, squadId), loadMembersTx(c, squadId));
        });
    }

    public int cheerSquad(long playerId, long squadId) throws SQLException {
        requireOpen();
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() == TournamentStage.TOURNAMENT_STAGE_SETTLED_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            SquadRow sq = readSquadForUpdateTx(c, squadId);
            if (sq == null || sq.seasonId() != cur.seasonId()
                    || sq.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_REGISTERED_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            if (hasCheeredTx(c, cur.seasonId(), playerId)) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentAlreadyCheered_VALUE);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + MatchFeatureTables.TOURNAMENT_CHEER
                            + "(season_id,player_id,squad_id,rewarded,created_ms) VALUES(?,?,?,0,?)")) {
                ps.setInt(1, cur.seasonId());
                ps.setLong(2, playerId);
                ps.setLong(3, squadId);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            int nextCount = sq.cheerCount() + 1;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD + " SET cheer_count=?,updated_ms=? WHERE squad_id=?")) {
                ps.setInt(1, nextCount);
                ps.setLong(2, now);
                ps.setLong(3, squadId);
                ps.executeUpdate();
            }
            return nextCount;
        });
    }

    public PrepareMatchOutcome prepareMatch(long captainId, long matchId, List<Long> lineupIds, long newMatchId) throws SQLException {
        requireOpen();
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() != TournamentStage.TOURNAMENT_STAGE_QUALIFIER_VALUE
                    && cur.stage() != TournamentStage.TOURNAMENT_STAGE_KNOCKOUT_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            long mySquadId = findPlayerSquadIdTx(c, cur.seasonId(), captainId);
            if (mySquadId == 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            SquadRow mySquad = readSquadForUpdateTx(c, mySquadId);
            if (mySquad.captainPlayerId() != captainId) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentNotCaptain_VALUE);
            }
            if (mySquad.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_REGISTERED_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            List<TournamentMemberView> members = loadMembersTx(c, mySquadId);
            List<Long> lineup = resolveLineup(members, lineupIds, cur.battleTeamSize());
            byte[] encodedLineup = TournamentRules.encodeLineup(lineup);
            TournamentRuleTable rule = tables.rule();

            if (cur.stage() == TournamentStage.TOURNAMENT_STAGE_KNOCKOUT_VALUE) {
                MatchRow m = matchId != 0L
                        ? readMatchForUpdateTx(c, matchId)
                        : findActiveKnockoutMatchTx(c, cur.seasonId(), mySquadId);
                if (m == null || m.seasonId() != cur.seasonId()
                        || m.phase() != TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE
                        || (m.state() != TournamentMatchState.TOURNAMENT_MATCH_STATE_WAITING_OPPONENT_VALUE
                        && m.state() != TournamentMatchState.TOURNAMENT_MATCH_STATE_READY_VALUE
                        && m.state() != TournamentMatchState.TOURNAMENT_MATCH_STATE_INTERMISSION_VALUE)
                        || m.squadAId() == 0L || m.squadBId() == 0L) {
                    throw new DomainException(MatchErrorTip.match_error.kMatchTournamentMatchNotReady_VALUE);
                }
                byte[] lineA = m.lineupA();
                byte[] lineB = m.lineupB();
                boolean readyA = m.readyA();
                boolean readyB = m.readyB();
                if (mySquadId == m.squadAId()) {
                    lineA = encodedLineup;
                    readyA = true;
                } else if (mySquadId == m.squadBId()) {
                    lineB = encodedLineup;
                    readyB = true;
                } else {
                    throw new DomainException(MatchErrorTip.match_error.kMatchTournamentMatchNotReady_VALUE);
                }
                int nextState = (readyA && readyB)
                        ? TournamentMatchState.TOURNAMENT_MATCH_STATE_READY_VALUE
                        : m.state();
                long nextInterDeadline = (readyA && readyB) ? 0L : m.intermissionDeadlineMs();
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " SET lineup_a=?,lineup_b=?,ready_a=?,ready_b=?,state=?,intermission_deadline_ms=?,updated_ms=? WHERE match_id=?")) {
                    ps.setBytes(1, lineA);
                    ps.setBytes(2, lineB);
                    ps.setBoolean(3, readyA);
                    ps.setBoolean(4, readyB);
                    ps.setInt(5, nextState);
                    ps.setLong(6, nextInterDeadline);
                    ps.setLong(7, now);
                    ps.setLong(8, m.matchId());
                    ps.executeUpdate();
                }
                MatchRow updated = readMatchForUpdateTx(c, m.matchId());
                Map<Long, SquadRow> squads = loadSquadMapTx(c, m.squadAId(), m.squadBId());
                SquadRow sqA = squads.get(m.squadAId());
                SquadRow sqB = squads.get(m.squadBId());
                TournamentLeylineElement leyline = TournamentLeylineElement.forNumber(updated.currentLeylineElement());
                LaunchSpec launch = (readyA && readyB)
                        ? new LaunchSpec(m.matchId(), cur.seasonId(), TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT,
                        m.roundIndex(), rule.getBattleConfigId(),
                        TournamentRules.decodeLineup(lineA), TournamentRules.decodeLineup(lineB), true,
                        updated.bestOf(), updated.currentGameIndex(), updated.winsA(), updated.winsB(),
                        leyline == null ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED : leyline,
                        m.squadAId(), sqA == null ? "" : sqA.name(), sqA == null ? TournamentRules.DEFAULT_EMBLEM_ID : sqA.emblemId(),
                        m.squadBId(), sqB == null ? "" : sqB.name(), sqB == null ? TournamentRules.DEFAULT_EMBLEM_ID : sqB.emblemId(),
                        false, updated.isThirdPlace())
                        : null;
                return new PrepareMatchOutcome(toMatchView(updated, squads, loadGameBattleIdsTx(c, m.matchId())), launch);
            }

            // 海选分支
            if (mySquad.qualifierPlayed() >= rule.getQualifierMaxMatches()) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentQualifierLimit_VALUE);
            }
            MatchRow existing = findActiveQualifierMatchTx(c, cur.seasonId(), mySquadId);
            if (existing != null) {
                if (existing.state() == TournamentMatchState.TOURNAMENT_MATCH_STATE_WAITING_OPPONENT_VALUE
                        && existing.squadAId() == mySquadId) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH + " SET lineup_a=?,updated_ms=? WHERE match_id=?")) {
                        ps.setBytes(1, encodedLineup);
                        ps.setLong(2, now);
                        ps.setLong(3, existing.matchId());
                        ps.executeUpdate();
                    }
                    existing = readMatchForUpdateTx(c, existing.matchId());
                }
                return new PrepareMatchOutcome(toMatchView(existing, loadSquadMapTx(c, existing.squadAId(), existing.squadBId()), List.of()), null);
            }
            int totalReg = countRegisteredSquadsTx(c, cur.seasonId());
            int maxPairMeetings = totalReg <= 4 ? 2 : 1;
            MatchRow paired = findWaitingQualifierOpponentTx(c, cur.seasonId(), mySquadId, maxPairMeetings);
            if (paired != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " SET squad_b_id=?,lineup_b=?,ready_b=1,state=?,updated_ms=? WHERE match_id=?")) {
                    ps.setLong(1, mySquadId);
                    ps.setBytes(2, encodedLineup);
                    ps.setInt(3, TournamentMatchState.TOURNAMENT_MATCH_STATE_READY_VALUE);
                    ps.setLong(4, now);
                    ps.setLong(5, paired.matchId());
                    ps.executeUpdate();
                }
                MatchRow updated = readMatchForUpdateTx(c, paired.matchId());
                Map<Long, SquadRow> squads = loadSquadMapTx(c, updated.squadAId(), updated.squadBId());
                SquadRow sqA = squads.get(updated.squadAId());
                LaunchSpec launch = new LaunchSpec(paired.matchId(), cur.seasonId(),
                        TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_QUALIFIER, paired.roundIndex(),
                        rule.getBattleConfigId(), TournamentRules.decodeLineup(paired.lineupA()), lineup, false,
                        1, 1, 0, 0, TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED,
                        updated.squadAId(), sqA == null ? "" : sqA.name(), sqA == null ? TournamentRules.DEFAULT_EMBLEM_ID : sqA.emblemId(),
                        mySquadId, mySquad.name(), mySquad.emblemId(),
                        false, false);
                return new PrepareMatchOutcome(toMatchView(updated, squads, List.of()), launch);
            }
            if (newMatchId == 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchInternal_VALUE);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + MatchFeatureTables.TOURNAMENT_MATCH
                            + "(match_id,season_id,phase,round_index,slot_index,squad_a_id,squad_b_id,lineup_a,lineup_b,"
                            + "ready_a,ready_b,state,battle_id,winner_squad_id,outcome,featured_spectate,"
                            + "scheduled_start_ms,deadline_ms,finished_ms,updated_ms,"
                            + "best_of,current_game_index,wins_a,wins_b,current_leyline_element,leyline_sequence,intermission_deadline_ms,"
                            + "is_third_place,mvp_player_id,mvp_player_name,mvp_squad_id,mvp_score,mvp_damage,mvp_healing,mvp_controls,banned_leyline_a,banned_leyline_b) "
                            + "VALUES(?,?,?,?,0,?,0,?,?,1,0,?,0,0,0,0,?,?,0,?,1,1,0,0,0,'',0,0,0,'',0,0,0,0,0,0,0)")) {
                ps.setLong(1, newMatchId);
                ps.setInt(2, cur.seasonId());
                ps.setInt(3, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_QUALIFIER_VALUE);
                ps.setInt(4, mySquad.qualifierPlayed() + 1);
                ps.setLong(5, mySquadId);
                ps.setBytes(6, encodedLineup);
                ps.setBytes(7, new byte[0]);
                ps.setInt(8, TournamentMatchState.TOURNAMENT_MATCH_STATE_WAITING_OPPONENT_VALUE);
                ps.setLong(9, now);
                ps.setLong(10, cur.knockoutStartMs());
                ps.setLong(11, now);
                ps.executeUpdate();
            }
            MatchRow created = readMatchForUpdateTx(c, newMatchId);
            return new PrepareMatchOutcome(toMatchView(created, loadSquadMapTx(c, mySquadId, 0L), List.of()), null);
        });
    }

    public void bindMatchBattle(long matchId, long battleId, boolean featured) throws SQLException {
        long now = clockMs.getAsLong();
        inTx(c -> {
            MatchRow m = readMatchForUpdateTx(c, matchId);
            if (m == null) {
                return null;
            }
            if (battleId == 0L) {
                if (m.state() == TournamentMatchState.TOURNAMENT_MATCH_STATE_READY_VALUE) {
                    if (m.phase() == TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_QUALIFIER_VALUE
                            || m.phase() == TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_WARMUP_VALUE) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                        + " SET squad_b_id=0,lineup_b=?,ready_b=0,state=?,updated_ms=? WHERE match_id=?")) {
                            ps.setBytes(1, new byte[0]);
                            ps.setInt(2, TournamentMatchState.TOURNAMENT_MATCH_STATE_WAITING_OPPONENT_VALUE);
                            ps.setLong(3, now);
                            ps.setLong(4, matchId);
                            ps.executeUpdate();
                        }
                    } else if (m.bestOf() >= 3 && (m.winsA() > 0 || m.winsB() > 0)) {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                        + " SET ready_a=0,ready_b=0,state=?,intermission_deadline_ms=?,updated_ms=? WHERE match_id=?")) {
                            ps.setInt(1, TournamentMatchState.TOURNAMENT_MATCH_STATE_INTERMISSION_VALUE);
                            ps.setLong(2, now + 15_000L);
                            ps.setLong(3, now);
                            ps.setLong(4, matchId);
                            ps.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement ps = c.prepareStatement(
                                "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                        + " SET ready_a=0,ready_b=0,state=?,updated_ms=? WHERE match_id=?")) {
                            ps.setInt(1, TournamentMatchState.TOURNAMENT_MATCH_STATE_WAITING_OPPONENT_VALUE);
                            ps.setLong(2, now);
                            ps.setLong(3, matchId);
                            ps.executeUpdate();
                        }
                    }
                }
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " SET battle_id=?,state=?,featured_spectate=?,intermission_deadline_ms=0,updated_ms=? WHERE match_id=? AND state IN(1,2,7)")) {
                ps.setLong(1, battleId);
                ps.setInt(2, TournamentMatchState.TOURNAMENT_MATCH_STATE_IN_BATTLE_VALUE);
                ps.setBoolean(3, featured);
                ps.setLong(4, now);
                ps.setLong(5, matchId);
                ps.executeUpdate();
            }
            int gameIdx = Math.max(1, m.currentGameIndex());
            upsertMatchGameTx(c, m.matchId(), gameIdx, m.seasonId(), m.phase(), battleId, m.currentLeylineElement(), 0L, 0, 0L);
            return null;
        });
    }

    public boolean applyBattleResult(BattleResultEvent event, int roundLimit) throws SQLException {
        if (event == null || event.getBattleId() == 0L) {
            return false;
        }
        long now = clockMs.getAsLong();
        return inTx(c -> {
            long gameMatchId = 0L;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT match_id,finished_ms FROM " + MatchFeatureTables.TOURNAMENT_MATCH_GAME + " WHERE battle_id=? FOR UPDATE")) {
                ps.setLong(1, event.getBattleId());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        gameMatchId = rs.getLong(1);
                        if (rs.getLong(2) > 0L) {
                            return false;
                        }
                    }
                }
            }
            MatchRow m = gameMatchId != 0L
                    ? readMatchForUpdateTx(c, gameMatchId)
                    : findMatchByBattleIdForUpdateTx(c, event.getBattleId());
            if (m == null || m.state() == TournamentMatchState.TOURNAMENT_MATCH_STATE_FINISHED_VALUE
                    || m.state() == TournamentMatchState.TOURNAMENT_MATCH_STATE_FORFEIT_VALUE) {
                return false;
            }
            SeasonRow season = readSeasonForUpdateTx(c, m.seasonId());
            long firstId = Long.compareUnsigned(m.squadAId(), m.squadBId()) <= 0 ? m.squadAId() : m.squadBId();
            long secondId = firstId == m.squadAId() ? m.squadBId() : m.squadAId();
            SquadRow sqFirst = readSquadForUpdateTx(c, firstId);
            SquadRow sqSecond = readSquadForUpdateTx(c, secondId);
            SquadRow sqA = sqFirst.squadId() == m.squadAId() ? sqFirst : sqSecond;
            SquadRow sqB = sqFirst.squadId() == m.squadAId() ? sqSecond : sqFirst;

            eBattleOutcome outcome = event.getOutcome();
            if (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN && roundLimit > 0
                    && event.getTotalRounds() >= roundLimit && hasSideSurvivors(event, 0)) {
                outcome = eBattleOutcome.BATTLE_OUTCOME_DRAW;
            }
            TournamentRuleTable rule = tables.rule();
            int gameIdx = Math.max(1, m.currentGameIndex());
            long mvpPlayerId = m.mvpPlayerId();
            String mvpPlayerName = m.mvpPlayerName();
            long mvpSquadId = m.mvpSquadId();
            int mvpScore = m.mvpScore();
            long mvpDamage = m.mvpDamage();
            long mvpHealing = m.mvpHealing();
            int mvpControls = m.mvpControls();

            long gameWinnerId = 0L;
            if (m.phase() == TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_WARMUP_VALUE) {
                if (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN) {
                    gameWinnerId = sqA.squadId();
                } else if (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN) {
                    gameWinnerId = sqB.squadId();
                }
                int winTeam = gameWinnerId == sqA.squadId() ? 0 : (gameWinnerId == sqB.squadId() ? 1 : -1);
                var mvpOpt = TournamentRules.computeMatchMvp(event.getSettlementsList(), winTeam);
                if (mvpOpt.isPresent() && (mvpPlayerId == 0L || mvpOpt.get().score() > mvpScore)) {
                    var mv = mvpOpt.get();
                    mvpPlayerId = mv.playerId();
                    mvpPlayerName = lookupMemberNameTx(c, m.seasonId(), mv.playerId());
                    mvpSquadId = mv.teamIndex() == 0 ? m.squadAId() : m.squadBId();
                    mvpScore = mv.score();
                    mvpDamage = mv.damage();
                    mvpHealing = mv.healing();
                    mvpControls = mv.controls();
                }
                upsertMatchGameTx(c, m.matchId(), gameIdx, m.seasonId(), m.phase(), event.getBattleId(),
                        m.currentLeylineElement(), gameWinnerId, outcome.getNumber(), now);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " SET state=?,winner_squad_id=?,outcome=?,mvp_player_id=?,mvp_player_name=?,mvp_squad_id=?,mvp_score=?,mvp_damage=?,mvp_healing=?,mvp_controls=?,finished_ms=?,updated_ms=? WHERE match_id=?")) {
                    ps.setInt(1, TournamentMatchState.TOURNAMENT_MATCH_STATE_FINISHED_VALUE);
                    ps.setLong(2, gameWinnerId);
                    ps.setInt(3, outcome.getNumber());
                    ps.setLong(4, mvpPlayerId);
                    ps.setString(5, mvpPlayerName);
                    ps.setLong(6, mvpSquadId);
                    ps.setInt(7, mvpScore);
                    ps.setLong(8, mvpDamage);
                    ps.setLong(9, mvpHealing);
                    ps.setInt(10, mvpControls);
                    ps.setLong(11, now);
                    ps.setLong(12, now);
                    ps.setLong(13, m.matchId());
                    ps.executeUpdate();
                }
                return true;
            }
            if (m.phase() == TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_QUALIFIER_VALUE) {
                int ptsA = sqA.qualifierPoints(), wA = sqA.qualifierWins(), lA = sqA.qualifierLosses(), dA = sqA.qualifierDraws();
                int ptsB = sqB.qualifierPoints(), wB = sqB.qualifierWins(), lB = sqB.qualifierLosses(), dB = sqB.qualifierDraws();
                if (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN) {
                    gameWinnerId = sqA.squadId();
                    wA++;
                    ptsA += rule.getQualifierWinPoints();
                    lB++;
                    ptsB += rule.getQualifierLossPoints();
                } else if (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN) {
                    gameWinnerId = sqB.squadId();
                    wB++;
                    ptsB += rule.getQualifierWinPoints();
                    lA++;
                    ptsA += rule.getQualifierLossPoints();
                } else {
                    dA++;
                    dB++;
                    ptsA += rule.getQualifierDrawPoints();
                    ptsB += rule.getQualifierDrawPoints();
                }
                int winTeam = gameWinnerId == sqA.squadId() ? 0 : (gameWinnerId == sqB.squadId() ? 1 : -1);
                var mvpOpt = TournamentRules.computeMatchMvp(event.getSettlementsList(), winTeam);
                if (mvpOpt.isPresent() && (mvpPlayerId == 0L || mvpOpt.get().score() > mvpScore)) {
                    var mv = mvpOpt.get();
                    mvpPlayerId = mv.playerId();
                    mvpPlayerName = lookupMemberNameTx(c, m.seasonId(), mv.playerId());
                    mvpSquadId = mv.teamIndex() == 0 ? m.squadAId() : m.squadBId();
                    mvpScore = mv.score();
                    mvpDamage = mv.damage();
                    mvpHealing = mv.healing();
                    mvpControls = mv.controls();
                }
                int honorA = TournamentRules.computeQualifierHonorBits(
                        sqA.honorBits(), sqA.qualifierPlayed() + 1, wA, lA, rule.getParticipationMinMatches());
                int honorB = TournamentRules.computeQualifierHonorBits(
                        sqB.honorBits(), sqB.qualifierPlayed() + 1, wB, lB, rule.getParticipationMinMatches());
                updateQualifierStatsTx(c, sqA.squadId(), ptsA, sqA.qualifierPlayed() + 1, wA, lA, dA, honorA, now);
                updateQualifierStatsTx(c, sqB.squadId(), ptsB, sqB.qualifierPlayed() + 1, wB, lB, dB, honorB, now);
                upsertMatchGameTx(c, m.matchId(), gameIdx, m.seasonId(), m.phase(), event.getBattleId(),
                        m.currentLeylineElement(), gameWinnerId, outcome.getNumber(), now);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " SET state=?,winner_squad_id=?,outcome=?,mvp_player_id=?,mvp_player_name=?,mvp_squad_id=?,mvp_score=?,mvp_damage=?,mvp_healing=?,mvp_controls=?,finished_ms=?,updated_ms=? WHERE match_id=?")) {
                    ps.setInt(1, TournamentMatchState.TOURNAMENT_MATCH_STATE_FINISHED_VALUE);
                    ps.setLong(2, gameWinnerId);
                    ps.setInt(3, outcome.getNumber());
                    ps.setLong(4, mvpPlayerId);
                    ps.setString(5, mvpPlayerName);
                    ps.setLong(6, mvpSquadId);
                    ps.setInt(7, mvpScore);
                    ps.setLong(8, mvpDamage);
                    ps.setLong(9, mvpHealing);
                    ps.setInt(10, mvpControls);
                    ps.setLong(11, now);
                    ps.setLong(12, now);
                    ps.setLong(13, m.matchId());
                    ps.executeUpdate();
                }
                return true;
            }

            // 淘汰赛分支（BO1 / BO3 + 60 秒局间换阵 + MVP + 神机竞猜结算）
            if (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN) {
                gameWinnerId = sqA.squadId();
            } else if (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN) {
                gameWinnerId = sqB.squadId();
            } else {
                var tieA = extractTieMetrics(event, 0, sqA.squadId(), sqA.seedRank());
                var tieB = extractTieMetrics(event, 1, sqB.squadId(), sqB.seedRank());
                gameWinnerId = TournamentRules.resolveKnockoutDrawWinner(tieA, tieB) == 0 ? sqA.squadId() : sqB.squadId();
            }
            int winTeam = gameWinnerId == sqA.squadId() ? 0 : 1;
            var mvpOpt = TournamentRules.computeMatchMvp(event.getSettlementsList(), winTeam);
            if (mvpOpt.isPresent() && (mvpPlayerId == 0L || mvpOpt.get().score() > mvpScore)) {
                var mv = mvpOpt.get();
                mvpPlayerId = mv.playerId();
                mvpPlayerName = lookupMemberNameTx(c, m.seasonId(), mv.playerId());
                mvpSquadId = mv.teamIndex() == 0 ? m.squadAId() : m.squadBId();
                mvpScore = mv.score();
                mvpDamage = mv.damage();
                mvpHealing = mv.healing();
                mvpControls = mv.controls();
            }
            MatchRow mWithMvp = new MatchRow(
                    m.matchId(), m.seasonId(), m.phase(), m.roundIndex(), m.slotIndex(),
                    m.squadAId(), m.squadBId(), m.lineupA(), m.lineupB(), m.readyA(), m.readyB(),
                    m.state(), m.battleId(), m.winnerSquadId(), m.outcome(), m.featuredSpectate(),
                    m.scheduledStartMs(), m.deadlineMs(), m.finishedMs(), m.updatedMs(),
                    m.bestOf(), m.currentGameIndex(), m.winsA(), m.winsB(),
                    m.currentLeylineElement(), m.leylineSequence(), m.intermissionDeadlineMs(),
                    m.isThirdPlace(), mvpPlayerId, mvpPlayerName, mvpSquadId, mvpScore, mvpDamage, mvpHealing, mvpControls,
                    m.bannedLeylineA(), m.bannedLeylineB());
            upsertMatchGameTx(c, m.matchId(), gameIdx, m.seasonId(), m.phase(), event.getBattleId(),
                    m.currentLeylineElement(), gameWinnerId, outcome.getNumber(), now);

            int winsA = m.winsA() + (gameWinnerId == sqA.squadId() ? 1 : 0);
            int winsB = m.winsB() + (gameWinnerId == sqB.squadId() ? 1 : 0);
            int bestOf = Math.max(1, m.bestOf());
            int winsNeeded = bestOf / 2 + 1;
            if (winsA >= winsNeeded || winsB >= winsNeeded) {
                long seriesWinner = winsA >= winsNeeded ? sqA.squadId() : sqB.squadId();
                long loserId = seriesWinner == sqA.squadId() ? sqB.squadId() : sqA.squadId();
                SquadRow winnerSq = seriesWinner == sqA.squadId() ? sqA : sqB;
                int winnerWins = seriesWinner == sqA.squadId() ? winsA : winsB;
                int loserWins = seriesWinner == sqA.squadId() ? winsB : winsA;
                int totalRounds = TournamentRules.totalKnockoutRounds(season.knockoutTeamCount());
                boolean isChamp = !m.isThirdPlace() && m.roundIndex() >= totalRounds;
                int nextHonor = TournamentRules.computeKnockoutSeriesHonorBits(
                        winnerSq.honorBits(), bestOf, winnerWins, loserWins, isChamp);
                if (nextHonor != winnerSq.honorBits()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD + " SET honor_bits=?,updated_ms=? WHERE squad_id=?")) {
                        ps.setInt(1, nextHonor);
                        ps.setLong(2, now);
                        ps.setLong(3, winnerSq.squadId());
                        ps.executeUpdate();
                    }
                }
                advanceKnockoutWinnerTx(c, season, mWithMvp, seriesWinner, loserId, now);
                settleMatchPredictionsTx(c, season, mWithMvp, seriesWinner, now);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " SET state=?,wins_a=?,wins_b=?,winner_squad_id=?,outcome=?,mvp_player_id=?,mvp_player_name=?,mvp_squad_id=?,mvp_score=?,mvp_damage=?,mvp_healing=?,mvp_controls=?,intermission_deadline_ms=0,finished_ms=?,updated_ms=? WHERE match_id=?")) {
                    ps.setInt(1, TournamentMatchState.TOURNAMENT_MATCH_STATE_FINISHED_VALUE);
                    ps.setInt(2, winsA);
                    ps.setInt(3, winsB);
                    ps.setLong(4, seriesWinner);
                    ps.setInt(5, outcome.getNumber());
                    ps.setLong(6, mvpPlayerId);
                    ps.setString(7, mvpPlayerName);
                    ps.setLong(8, mvpSquadId);
                    ps.setInt(9, mvpScore);
                    ps.setLong(10, mvpDamage);
                    ps.setLong(11, mvpHealing);
                    ps.setInt(12, mvpControls);
                    ps.setLong(13, now);
                    ps.setLong(14, now);
                    ps.setLong(15, m.matchId());
                    ps.executeUpdate();
                }
            } else {
                int nextGame = gameIdx + 1;
                List<TournamentLeylineElement> seq = TournamentRules.decodeLeylineSequence(m.leylineSequence());
                int nextLeyline = TournamentRules.leylineForGame(seq, nextGame).getNumber();
                int interSec = rule.getIntermissionSeconds() > 0 ? rule.getIntermissionSeconds() : TournamentRules.DEFAULT_INTERMISSION_SEC;
                long interDeadline = now + interSec * 1000L;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " SET state=?,wins_a=?,wins_b=?,current_game_index=?,current_leyline_element=?,ready_a=0,ready_b=0,intermission_deadline_ms=?,outcome=?,mvp_player_id=?,mvp_player_name=?,mvp_squad_id=?,mvp_score=?,mvp_damage=?,mvp_healing=?,mvp_controls=?,updated_ms=? WHERE match_id=?")) {
                    ps.setInt(1, TournamentMatchState.TOURNAMENT_MATCH_STATE_INTERMISSION_VALUE);
                    ps.setInt(2, winsA);
                    ps.setInt(3, winsB);
                    ps.setInt(4, nextGame);
                    ps.setInt(5, nextLeyline);
                    ps.setLong(6, interDeadline);
                    ps.setInt(7, outcome.getNumber());
                    ps.setLong(8, mvpPlayerId);
                    ps.setString(9, mvpPlayerName);
                    ps.setLong(10, mvpSquadId);
                    ps.setInt(11, mvpScore);
                    ps.setLong(12, mvpDamage);
                    ps.setLong(13, mvpHealing);
                    ps.setInt(14, mvpControls);
                    ps.setLong(15, now);
                    ps.setLong(16, m.matchId());
                    ps.executeUpdate();
                }
            }
            return true;
        });
    }

    /** 可恢复驱动锦标赛四阶段推进（REGISTRATION -> QUALIFIER -> KNOCKOUT -> SETTLED -> 下一赛季）。 */
    public void advanceSeason(long nowOverrideMs, LongSupplier idGenerator) throws SQLException {
        long now = nowOverrideMs > 0L ? nowOverrideMs : clockMs.getAsLong();
        inTx(c -> {
            SeasonRow latest = ensureSeasonTx(c, now);
            // 始终先对当前赛季行加 FOR UPDATE，防止多副本并发重复生成淘汰赛对阵或重复结算。
            SeasonRow cur = readSeasonForUpdateTx(c, latest.seasonId());
            if (cur == null) {
                return null;
            }
            switch (TournamentStage.forNumber(cur.stage())) {
                case TOURNAMENT_STAGE_REGISTRATION -> {
                    if (now >= cur.qualifierStartMs()) {
                        transitionToQualifierTx(c, cur, now);
                    }
                }
                case TOURNAMENT_STAGE_QUALIFIER -> {
                    if (now >= cur.knockoutStartMs()) {
                        transitionToKnockoutTx(c, cur, now, idGenerator);
                    }
                }
                case TOURNAMENT_STAGE_KNOCKOUT -> {
                    resolveOverdueKnockoutTx(c, cur, now);
                    SeasonRow refreshed = readSeasonForUpdateTx(c, cur.seasonId());
                    if (refreshed == null || refreshed.stage() != TournamentStage.TOURNAMENT_STAGE_KNOCKOUT_VALUE) {
                        return null;
                    }
                    boolean canSettle = refreshed.knockoutTeamCount() == 0
                            || now >= refreshed.settleStartMs()
                            || (refreshed.championSquadId() != 0L && !hasOpenThirdPlaceMatchTx(c, refreshed.seasonId()));
                    if (canSettle) {
                        settleSeasonTx(c, refreshed, now, idGenerator);
                    }
                }
                case TOURNAMENT_STAGE_SETTLED -> {
                    if (now >= cur.endMs()) {
                        openNextSeasonTx(c, cur, now);
                    }
                }
                default -> {
                }
            }
            return null;
        });
    }

    public GetTournamentOverviewResponse loadOverview(long playerId, boolean includeHistory) throws SQLException {
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            int regCount = countRegisteredSquadsTx(c, cur.seasonId());
            boolean salutedToday = salutedTodayTx(c, playerId, now);
            GetTournamentOverviewResponse.Builder resp = GetTournamentOverviewResponse.newBuilder()
                    .setCurrentSeason(toSeasonView(cur, regCount, salutedToday))
                    .addAllRewardTiers(tables.rewardTierViews())
                    .setServerTimeMs(now);
            long mySquadId = findPlayerSquadIdTx(c, cur.seasonId(), playerId);
            if (mySquadId != 0L) {
                SquadRow sq = readSquadTx(c, mySquadId);
                if (sq != null) {
                    resp.setMySquad(toSquadView(sq, loadMembersTx(c, mySquadId)));
                }
                MatchRow active = findActiveMatchForSquadTx(c, cur.seasonId(), mySquadId);
                if (active != null) {
                    resp.setMyActiveMatch(toMatchView(active, loadSquadMapTx(c, active.squadAId(), active.squadBId()),
                            loadGameBattleIdsTx(c, active.matchId())));
                }
            }
            // 待处理邀请
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT squad_id,season_id,inviter_player_id,created_ms,expires_ms FROM "
                            + MatchFeatureTables.TOURNAMENT_INVITE
                            + " WHERE invitee_player_id=? AND season_id=? AND state=1 AND expires_ms>? ORDER BY created_ms DESC")) {
                ps.setLong(1, playerId);
                ps.setInt(2, cur.seasonId());
                ps.setLong(3, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long sid = rs.getLong(1);
                        SquadRow sq = readSquadTx(c, sid);
                        if (sq == null || sq.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_DRAFT_VALUE) {
                            continue;
                        }
                        resp.addPendingInvites(TournamentInviteView.newBuilder()
                                .setSquadId(sid)
                                .setSeasonId(rs.getInt(2))
                                .setSquadName(sq.name())
                                .setInviterPlayerId(rs.getLong(3))
                                .setCurrentMemberCount(sq.memberCount())
                                .setRosterMaxSize(cur.rosterMaxSize())
                                .setCreatedMs(rs.getLong(4))
                                .setExpiresMs(rs.getLong(5))
                                .build());
                    }
                }
            }
            // 进行中精选对局（仅正式海选/淘汰赛，排除零积分热身演练）
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " WHERE season_id=? AND phase IN(1,2) AND state=3 ORDER BY featured_spectate DESC, phase DESC, round_index DESC")) {
                ps.setInt(1, cur.seasonId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MatchRow m = scanMatch(rs);
                        resp.addFeaturedMatches(toMatchView(m, loadSquadMapTx(c, m.squadAId(), m.squadBId()),
                                loadGameBattleIdsTx(c, m.matchId())));
                    }
                }
            }
            // 本季助威
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT squad_id FROM " + MatchFeatureTables.TOURNAMENT_CHEER + " WHERE season_id=? AND player_id=?")) {
                ps.setInt(1, cur.seasonId());
                ps.setLong(2, playerId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long cid = rs.getLong(1);
                        resp.setMyCheeredSquadId(cid);
                        SquadRow sq = readSquadTx(c, cid);
                        if (sq != null) {
                            resp.setMyCheeredSquadName(sq.name());
                        }
                    }
                }
            }
            // 奖励账单
            String rewSql = includeHistory
                    ? "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_REWARD + " WHERE player_id=? ORDER BY season_id DESC, tier_kind ASC"
                    : "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_REWARD + " WHERE player_id=? AND season_id=? ORDER BY tier_kind ASC";
            try (PreparedStatement ps = c.prepareStatement(rewSql)) {
                ps.setLong(1, playerId);
                if (!includeHistory) {
                    ps.setInt(2, cur.seasonId());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        resp.addMyRewards(scanRewardView(rs));
                    }
                }
            }
            // 历届冠军殿堂
            if (includeHistory) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SEASON
                                + " WHERE stage=? AND champion_squad_id>0 ORDER BY season_id DESC")) {
                    ps.setInt(1, TournamentStage.TOURNAMENT_STAGE_SETTLED_VALUE);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            resp.addHallOfFame(toSeasonView(scanSeason(rs), 0, salutedToday));
                        }
                    }
                }
            }
            return resp.build();
        });
    }

    public ListTournamentStandingsResponse listStandings(int seasonId, long playerId, int page, int pageSize) throws SQLException {
        int p = page <= 0 ? 1 : page;
        int psLimit = pageSize <= 0 ? 20 : Math.min(pageSize, 50);
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow season = seasonId == 0 ? ensureSeasonTx(c, now) : readSeasonTx(c, seasonId);
            if (season == null) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            boolean salutedToday = playerId != 0L && salutedTodayTx(c, playerId, now);
            List<SquadRow> all = new ArrayList<>();
            Map<Long, SquadRow> byId = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SQUAD
                            + " WHERE season_id=? AND state IN(2,3,4)")) {
                ps.setInt(1, season.seasonId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SquadRow sq = scanSquad(rs);
                        all.add(sq);
                        byId.put(sq.squadId(), sq);
                    }
                }
            }
            all.sort(Comparator.<SquadRow, Integer>comparing(s -> s.finalRank() > 0 ? 0 : 1)
                    .thenComparingInt(SquadRow::finalRank)
                    .thenComparing(Comparator.comparingInt(SquadRow::qualifierPoints).reversed())
                    .thenComparing(Comparator.comparingInt(SquadRow::qualifierWins).reversed())
                    .thenComparing(Comparator.comparingInt(SquadRow::qualifierDraws).reversed())
                    .thenComparingLong(SquadRow::registeredMs)
                    .thenComparing((a, b) -> Long.compareUnsigned(a.squadId(), b.squadId())));

            int start = (int) Math.min((long) (p - 1) * psLimit, all.size());
            int end = Math.min(start + psLimit, all.size());
            ListTournamentStandingsResponse.Builder resp = ListTournamentStandingsResponse.newBuilder()
                    .setSeason(toSeasonView(season, all.size(), salutedToday))
                    .setTotalSquads(all.size())
                    .setServerTimeMs(now);
            for (SquadRow sq : all.subList(start, end)) {
                resp.addStandings(toSquadView(sq, loadMembersTx(c, sq.squadId())));
            }
            if (playerId != 0L) {
                long mySid = findPlayerSquadIdTx(c, season.seasonId(), playerId);
                if (mySid != 0L && byId.containsKey(mySid)) {
                    resp.setMySquad(toSquadView(byId.get(mySid), loadMembersTx(c, mySid)));
                }
            }
            Map<Long, Map<Long, Integer>> predCounts = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT match_id,predicted_squad_id,COUNT(*) FROM " + MatchFeatureTables.TOURNAMENT_PREDICTION
                            + " WHERE season_id=? GROUP BY match_id,predicted_squad_id")) {
                ps.setInt(1, season.seasonId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        predCounts.computeIfAbsent(rs.getLong(1), k -> new HashMap<>())
                                .put(rs.getLong(2), rs.getInt(3));
                    }
                }
            }
            Map<Long, Long> myPicks = new HashMap<>();
            if (playerId != 0L) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT match_id,predicted_squad_id FROM " + MatchFeatureTables.TOURNAMENT_PREDICTION
                                + " WHERE season_id=? AND player_id=?")) {
                    ps.setInt(1, season.seasonId());
                    ps.setLong(2, playerId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            myPicks.put(rs.getLong(1), rs.getLong(2));
                        }
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " WHERE season_id=? AND phase=? ORDER BY round_index ASC, slot_index ASC")) {
                ps.setInt(1, season.seasonId());
                ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MatchRow m = scanMatch(rs);
                        TournamentMatchView base = toMatchView(m, byId, loadGameBattleIdsTx(c, m.matchId()));
                        Map<Long, Integer> bySquad = predCounts.get(m.matchId());
                        int cntA = bySquad == null ? 0 : bySquad.getOrDefault(m.squadAId(), 0);
                        int cntB = bySquad == null ? 0 : bySquad.getOrDefault(m.squadBId(), 0);
                        long myPick = myPicks.getOrDefault(m.matchId(), 0L);
                        resp.addKnockoutBracket(base.toBuilder()
                                .setMyPredictedSquadId(myPick)
                                .setPredictCountA(cntA)
                                .setPredictCountB(cntB)
                                .build());
                    }
                }
            }
            return resp.build();
        });
    }

    public List<ClaimableReward> claimableRewards(long playerId, int seasonId) throws SQLException {
        return inTx(c -> {
            String sql = seasonId == 0
                    ? "SELECT reward_id,season_id,tier_kind,tier_id,attachments FROM " + MatchFeatureTables.TOURNAMENT_REWARD
                    + " WHERE player_id=? AND delivery_state=? ORDER BY season_id DESC, tier_kind ASC"
                    : "SELECT reward_id,season_id,tier_kind,tier_id,attachments FROM " + MatchFeatureTables.TOURNAMENT_REWARD
                    + " WHERE player_id=? AND season_id=? AND delivery_state=? ORDER BY tier_kind ASC";
            List<ClaimableReward> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                if (seasonId == 0) {
                    ps.setInt(2, TournamentRewardDeliveryState.TOURNAMENT_REWARD_DELIVERY_CLAIMABLE_VALUE);
                } else {
                    ps.setInt(2, seasonId);
                    ps.setInt(3, TournamentRewardDeliveryState.TOURNAMENT_REWARD_DELIVERY_CLAIMABLE_VALUE);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        byte[] raw = rs.getBytes(5);
                        list.add(new ClaimableReward(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                                raw == null ? new byte[0] : raw));
                    }
                }
            }
            return list;
        });
    }

    public TournamentPlayerRewardView markRewardClaimed(int seasonId, long playerId, int tierKind, long mailId) throws SQLException {
        long now = clockMs.getAsLong();
        return inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_REWARD
                            + " SET delivery_state=?,mail_id=?,claimed_ms=?,updated_ms=? WHERE season_id=? AND player_id=? AND tier_kind=?")) {
                ps.setInt(1, TournamentRewardDeliveryState.TOURNAMENT_REWARD_DELIVERY_CLAIMED_VALUE);
                ps.setLong(2, mailId);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.setInt(5, seasonId);
                ps.setLong(6, playerId);
                ps.setInt(7, tierKind);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_REWARD
                            + " WHERE season_id=? AND player_id=? AND tier_kind=?")) {
                ps.setInt(1, seasonId);
                ps.setLong(2, playerId);
                ps.setInt(3, tierKind);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return scanRewardView(rs);
                    }
                }
            }
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentRewardUnavailable_VALUE);
        });
    }

    public List<TournamentPlayerRewardView> claimRewards(long playerId, int seasonId) throws SQLException {
        long now = clockMs.getAsLong();
        return inTx(c -> {
            String sql = seasonId == 0
                    ? "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_REWARD + " WHERE player_id=? AND delivery_state=? ORDER BY season_id DESC, tier_kind ASC FOR UPDATE"
                    : "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_REWARD + " WHERE player_id=? AND season_id=? AND delivery_state=? ORDER BY tier_kind ASC FOR UPDATE";
            List<TournamentPlayerRewardView> claimed = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, playerId);
                if (seasonId == 0) {
                    ps.setInt(2, TournamentRewardDeliveryState.TOURNAMENT_REWARD_DELIVERY_CLAIMABLE_VALUE);
                } else {
                    ps.setInt(2, seasonId);
                    ps.setInt(3, TournamentRewardDeliveryState.TOURNAMENT_REWARD_DELIVERY_CLAIMABLE_VALUE);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        TournamentPlayerRewardView v = scanRewardView(rs);
                        claimed.add(v.toBuilder()
                                .setDeliveryState(TournamentRewardDeliveryState.TOURNAMENT_REWARD_DELIVERY_CLAIMED)
                                .setClaimedMs(now)
                                .build());
                    }
                }
            }
            if (claimed.isEmpty()) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentRewardUnavailable_VALUE);
            }
            for (TournamentPlayerRewardView v : claimed) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_REWARD
                                + " SET delivery_state=?,claimed_ms=?,updated_ms=? WHERE season_id=? AND player_id=? AND tier_kind=?")) {
                    ps.setInt(1, TournamentRewardDeliveryState.TOURNAMENT_REWARD_DELIVERY_CLAIMED_VALUE);
                    ps.setLong(2, now);
                    ps.setLong(3, now);
                    ps.setInt(4, v.getSeasonId());
                    ps.setLong(5, playerId);
                    ps.setInt(6, v.getTierKindValue());
                    ps.executeUpdate();
                }
            }
            return claimed;
        });
    }

    // ---- 阶段推进内部辅助 ----

    private void transitionToQualifierTx(Connection c, SeasonRow cur, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                        + " SET state=?,registered_ms=?,updated_ms=? WHERE season_id=? AND state=? AND member_count>=?")) {
            ps.setInt(1, TournamentSquadState.TOURNAMENT_SQUAD_STATE_REGISTERED_VALUE);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setInt(4, cur.seasonId());
            ps.setInt(5, TournamentSquadState.TOURNAMENT_SQUAD_STATE_DRAFT_VALUE);
            ps.setInt(6, cur.battleTeamSize());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                        + " SET state=?,updated_ms=? WHERE season_id=? AND state=? AND member_count<?")) {
            ps.setInt(1, TournamentSquadState.TOURNAMENT_SQUAD_STATE_DISBANDED_VALUE);
            ps.setLong(2, now);
            ps.setInt(3, cur.seasonId());
            ps.setInt(4, TournamentSquadState.TOURNAMENT_SQUAD_STATE_DRAFT_VALUE);
            ps.setInt(5, cur.battleTeamSize());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + MatchFeatureTables.TOURNAMENT_INVITE + " SET state=4 WHERE season_id=? AND state=1")) {
            ps.setInt(1, cur.seasonId());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + MatchFeatureTables.TOURNAMENT_SEASON + " SET stage=?,updated_ms=? WHERE season_id=?")) {
            ps.setInt(1, TournamentStage.TOURNAMENT_STAGE_QUALIFIER_VALUE);
            ps.setLong(2, now);
            ps.setInt(3, cur.seasonId());
            ps.executeUpdate();
        }
    }

    private void transitionToKnockoutTx(Connection c, SeasonRow cur, long now, LongSupplier idGenerator) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                        + " SET state=?,updated_ms=? WHERE season_id=? AND phase IN(?,?) AND state=1")) {
            ps.setInt(1, TournamentMatchState.TOURNAMENT_MATCH_STATE_FORFEIT_VALUE);
            ps.setLong(2, now);
            ps.setInt(3, cur.seasonId());
            ps.setInt(4, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_QUALIFIER_VALUE);
            ps.setInt(5, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_WARMUP_VALUE);
            ps.executeUpdate();
        }
        List<TournamentRules.StandingEntry> entries = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SQUAD
                        + " WHERE season_id=? AND state=? FOR UPDATE")) {
            ps.setInt(1, cur.seasonId());
            ps.setInt(2, TournamentSquadState.TOURNAMENT_SQUAD_STATE_REGISTERED_VALUE);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SquadRow sq = scanSquad(rs);
                    entries.add(new TournamentRules.StandingEntry(
                            sq.squadId(), sq.qualifierPoints(), sq.qualifierWins(), sq.qualifierDraws(), sq.registeredMs()));
                }
            }
        }
        entries.sort(TournamentRules.STANDING_ORDER);
        int bracketSize = TournamentRules.knockoutBracketSize(entries.size(), tables.rule().getKnockoutMaxTeams());
        for (int i = 0; i < entries.size(); i++) {
            int seed = i + 1;
            long sid = entries.get(i).squadId();
            if (bracketSize == 0 || seed > bracketSize) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                                + " SET seed_rank=?,final_rank=?,state=?,updated_ms=? WHERE squad_id=?")) {
                    ps.setInt(1, seed);
                    ps.setInt(2, seed);
                    ps.setInt(3, TournamentSquadState.TOURNAMENT_SQUAD_STATE_ELIMINATED_VALUE);
                    ps.setLong(4, now);
                    ps.setLong(5, sid);
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD + " SET seed_rank=?,updated_ms=? WHERE squad_id=?")) {
                    ps.setInt(1, seed);
                    ps.setLong(2, now);
                    ps.setLong(3, sid);
                    ps.executeUpdate();
                }
            }
        }
        if (bracketSize == 0) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SEASON
                            + " SET stage=?,knockout_team_count=0,updated_ms=? WHERE season_id=?")) {
                ps.setInt(1, TournamentStage.TOURNAMENT_STAGE_KNOCKOUT_VALUE);
                ps.setLong(2, now);
                ps.setInt(3, cur.seasonId());
                ps.executeUpdate();
            }
            return;
        }
        int totalRounds = TournamentRules.totalKnockoutRounds(bracketSize);
        long roundWindowMs = Math.max(1000L, tables.rule().getKnockoutRoundTimeoutSeconds() * 1000L);
        int[][] pairs = TournamentRules.seedPairs(bracketSize);

        long[][] ids = new long[totalRounds + 1][];
        long[][] sideA = new long[totalRounds + 1][];
        long[][] sideB = new long[totalRounds + 1][];
        int[][] states = new int[totalRounds + 1][];
        long[][] winners = new long[totalRounds + 1][];
        for (int r = 1; r <= totalRounds; r++) {
            int slots = bracketSize >> r;
            ids[r] = new long[slots];
            sideA[r] = new long[slots];
            sideB[r] = new long[slots];
            states[r] = new int[slots];
            winners[r] = new long[slots];
            for (int slot = 0; slot < slots; slot++) {
                ids[r][slot] = idGenerator.getAsLong();
                states[r][slot] = TournamentMatchState.TOURNAMENT_MATCH_STATE_WAITING_OPPONENT_VALUE;
                if (r == 1) {
                    int sA = pairs[slot][0];
                    int sB = pairs[slot][1];
                    sideA[r][slot] = sA <= entries.size() ? entries.get(sA - 1).squadId() : 0L;
                    sideB[r][slot] = sB <= entries.size() ? entries.get(sB - 1).squadId() : 0L;
                    if (sideA[r][slot] != 0L && sideB[r][slot] == 0L) {
                        states[r][slot] = TournamentMatchState.TOURNAMENT_MATCH_STATE_BYE_VALUE;
                        winners[r][slot] = sideA[r][slot];
                    } else if (sideB[r][slot] != 0L && sideA[r][slot] == 0L) {
                        states[r][slot] = TournamentMatchState.TOURNAMENT_MATCH_STATE_BYE_VALUE;
                        winners[r][slot] = sideB[r][slot];
                    }
                }
            }
        }
        if (totalRounds >= 2) {
            for (int slot = 0; slot < ids[1].length; slot++) {
                if (states[1][slot] == TournamentMatchState.TOURNAMENT_MATCH_STATE_BYE_VALUE && winners[1][slot] != 0L) {
                    if ((slot % 2) == 0) {
                        sideA[2][slot / 2] = winners[1][slot];
                    } else {
                        sideB[2][slot / 2] = winners[1][slot];
                    }
                }
            }
        }
        for (int r = 1; r <= totalRounds; r++) {
            long rStart = now + (long) (r - 1) * roundWindowMs;
            long rDeadline = rStart + roundWindowMs;
            int bestOf = TournamentRules.bestOfForKnockout(bracketSize, r, tables.rule().getBo3Top4Enabled());
            for (int slot = 0; slot < ids[r].length; slot++) {
                List<TournamentLeylineElement> leylines = TournamentRules.drawLeylines(cur.seasonId(), ids[r][slot], bestOf);
                String leylineSeq = TournamentRules.encodeLeylineSequence(leylines);
                int firstLeyline = TournamentRules.leylineForGame(leylines, 1).getNumber();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + MatchFeatureTables.TOURNAMENT_MATCH
                                + "(match_id,season_id,phase,round_index,slot_index,squad_a_id,squad_b_id,lineup_a,lineup_b,"
                                + "ready_a,ready_b,state,battle_id,winner_squad_id,outcome,featured_spectate,"
                                + "scheduled_start_ms,deadline_ms,finished_ms,updated_ms,"
                                + "best_of,current_game_index,wins_a,wins_b,current_leyline_element,leyline_sequence,intermission_deadline_ms,"
                                + "is_third_place,mvp_player_id,mvp_player_name,mvp_squad_id,mvp_score,mvp_damage,mvp_healing,mvp_controls,banned_leyline_a,banned_leyline_b) "
                                + "VALUES(?,?,?,?,?,?,?,?,?,0,0,?,0,?,0,1,?,?,?,?,?,1,0,0,?,?,0,0,0,'',0,0,0,0,0,0,0)")) {
                    ps.setLong(1, ids[r][slot]);
                    ps.setInt(2, cur.seasonId());
                    ps.setInt(3, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
                    ps.setInt(4, r);
                    ps.setInt(5, slot);
                    ps.setLong(6, sideA[r][slot]);
                    ps.setLong(7, sideB[r][slot]);
                    ps.setBytes(8, new byte[0]);
                    ps.setBytes(9, new byte[0]);
                    ps.setInt(10, states[r][slot]);
                    ps.setLong(11, winners[r][slot]);
                    ps.setLong(12, rStart);
                    ps.setLong(13, rDeadline);
                    ps.setLong(14, states[r][slot] == TournamentMatchState.TOURNAMENT_MATCH_STATE_BYE_VALUE ? now : 0L);
                    ps.setLong(15, now);
                    ps.setInt(16, bestOf);
                    ps.setInt(17, firstLeyline);
                    ps.setString(18, leylineSeq);
                    ps.executeUpdate();
                }
            }
        }
        // V3 季军争夺战：四强及以上且开关开启时，在末轮追加 slot=1 季军争夺战（同样支持 BO3 与独立灵脉）。
        if (bracketSize >= 4 && tables.rule().getThirdPlaceMatchEnabled()) {
            long thirdMatchId = idGenerator.getAsLong();
            long rStart = now + (long) (totalRounds - 1) * roundWindowMs;
            long rDeadline = rStart + roundWindowMs;
            int bestOf = TournamentRules.bestOfForKnockout(bracketSize, totalRounds, tables.rule().getBo3Top4Enabled());
            List<TournamentLeylineElement> leylines = TournamentRules.drawLeylines(cur.seasonId(), thirdMatchId, bestOf);
            String leylineSeq = TournamentRules.encodeLeylineSequence(leylines);
            int firstLeyline = TournamentRules.leylineForGame(leylines, 1).getNumber();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + MatchFeatureTables.TOURNAMENT_MATCH
                            + "(match_id,season_id,phase,round_index,slot_index,squad_a_id,squad_b_id,lineup_a,lineup_b,"
                            + "ready_a,ready_b,state,battle_id,winner_squad_id,outcome,featured_spectate,"
                            + "scheduled_start_ms,deadline_ms,finished_ms,updated_ms,"
                            + "best_of,current_game_index,wins_a,wins_b,current_leyline_element,leyline_sequence,intermission_deadline_ms,"
                            + "is_third_place,mvp_player_id,mvp_player_name,mvp_squad_id,mvp_score,mvp_damage,mvp_healing,mvp_controls,banned_leyline_a,banned_leyline_b) "
                            + "VALUES(?,?,?,?,1,0,0,?,?,0,0,?,0,0,0,1,?,?,0,?,?,1,0,0,?,?,0,1,0,'',0,0,0,0,0,0,0)")) {
                ps.setLong(1, thirdMatchId);
                ps.setInt(2, cur.seasonId());
                ps.setInt(3, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
                ps.setInt(4, totalRounds);
                ps.setBytes(5, new byte[0]);
                ps.setBytes(6, new byte[0]);
                ps.setInt(7, TournamentMatchState.TOURNAMENT_MATCH_STATE_WAITING_OPPONENT_VALUE);
                ps.setLong(8, rStart);
                ps.setLong(9, rDeadline);
                ps.setLong(10, now);
                ps.setInt(11, bestOf);
                ps.setInt(12, firstLeyline);
                ps.setString(13, leylineSeq);
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + MatchFeatureTables.TOURNAMENT_SEASON
                        + " SET stage=?,knockout_team_count=?,updated_ms=? WHERE season_id=?")) {
            ps.setInt(1, TournamentStage.TOURNAMENT_STAGE_KNOCKOUT_VALUE);
            ps.setInt(2, bracketSize);
            ps.setLong(3, now);
            ps.setInt(4, cur.seasonId());
            ps.executeUpdate();
        }
    }

    private void resolveOverdueKnockoutTx(Connection c, SeasonRow season, long now) throws SQLException {
        if (season.knockoutTeamCount() == 0) {
            return;
        }
        List<MatchRow> overdue = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                        + " WHERE season_id=? AND phase=? AND state IN(1,2) AND deadline_ms>0 AND deadline_ms<=?"
                        + " ORDER BY round_index ASC, slot_index ASC FOR UPDATE")) {
            ps.setInt(1, season.seasonId());
            ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
            ps.setLong(3, now);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    overdue.add(scanMatch(rs));
                }
            }
        }
        for (MatchRow m : overdue) {
            if (m.squadAId() == 0L && m.squadBId() == 0L) {
                continue;
            }
            long winnerId;
            long loserId = 0L;
            if (m.squadAId() != 0L && m.squadBId() == 0L) {
                winnerId = m.squadAId();
            } else if (m.squadBId() != 0L && m.squadAId() == 0L) {
                winnerId = m.squadBId();
            } else if (m.readyA() && !m.readyB()) {
                winnerId = m.squadAId();
                loserId = m.squadBId();
            } else if (m.readyB() && !m.readyA()) {
                winnerId = m.squadBId();
                loserId = m.squadAId();
            } else {
                SquadRow sqA = readSquadTx(c, m.squadAId());
                SquadRow sqB = readSquadTx(c, m.squadBId());
                int seedA = sqA == null ? 0 : sqA.seedRank();
                int seedB = sqB == null ? 0 : sqB.seedRank();
                if (seedB == 0 || (seedA > 0 && seedA <= seedB)) {
                    winnerId = m.squadAId();
                    loserId = m.squadBId();
                } else {
                    winnerId = m.squadBId();
                    loserId = m.squadAId();
                }
            }
            advanceKnockoutWinnerTx(c, season, m, winnerId, loserId, now);
            settleMatchPredictionsTx(c, season, m, winnerId, now);
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " SET state=?,winner_squad_id=?,finished_ms=?,updated_ms=? WHERE match_id=?")) {
                ps.setInt(1, TournamentMatchState.TOURNAMENT_MATCH_STATE_FORFEIT_VALUE);
                ps.setLong(2, winnerId);
                ps.setLong(3, now);
                ps.setLong(4, now);
                ps.setLong(5, m.matchId());
                ps.executeUpdate();
            }
        }
    }

    private void advanceKnockoutWinnerTx(Connection c, SeasonRow season, MatchRow m, long winnerId, long loserId, long now) throws SQLException {
        int totalRounds = TournamentRules.totalKnockoutRounds(season.knockoutTeamCount());
        if (m.isThirdPlace()) {
            if (loserId != 0L) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                                + " SET state=?,final_rank=4,updated_ms=? WHERE squad_id=?")) {
                    ps.setInt(1, TournamentSquadState.TOURNAMENT_SQUAD_STATE_ELIMINATED_VALUE);
                    ps.setLong(2, now);
                    ps.setLong(3, loserId);
                    ps.executeUpdate();
                }
            }
            if (winnerId != 0L) {
                SquadRow third = readSquadTx(c, winnerId);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                                + " SET state=?,final_rank=3,updated_ms=? WHERE squad_id=?")) {
                    ps.setInt(1, TournamentSquadState.TOURNAMENT_SQUAD_STATE_ELIMINATED_VALUE);
                    ps.setLong(2, now);
                    ps.setLong(3, winnerId);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_SEASON
                                + " SET third_place_squad_id=?,third_place_squad_name=?,third_place_emblem_id=?,updated_ms=? WHERE season_id=?")) {
                    ps.setLong(1, winnerId);
                    ps.setString(2, third == null ? "" : third.name());
                    ps.setInt(3, third == null ? TournamentRules.DEFAULT_EMBLEM_ID : emblemOrDefault(third.emblemId()));
                    ps.setLong(4, now);
                    ps.setInt(5, season.seasonId());
                    ps.executeUpdate();
                }
            }
            return;
        }
        if (m.roundIndex() >= totalRounds) {
            if (loserId != 0L) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                                + " SET state=?,final_rank=2,updated_ms=? WHERE squad_id=?")) {
                    ps.setInt(1, TournamentSquadState.TOURNAMENT_SQUAD_STATE_ELIMINATED_VALUE);
                    ps.setLong(2, now);
                    ps.setLong(3, loserId);
                    ps.executeUpdate();
                }
            }
            SquadRow champ = readSquadTx(c, winnerId);
            List<TournamentMemberView> mems = loadMembersTx(c, winnerId);
            String summary = mems.stream()
                    .map(v -> v.getPlayerName().isEmpty() ? "#" + Long.toUnsignedString(v.getPlayerId()) : v.getPlayerName())
                    .reduce((a, b) -> a + " / " + b).orElse("");
            MatchTournamentChampionMembersBlob.Builder blobBuilder = MatchTournamentChampionMembersBlob.newBuilder();
            for (TournamentMemberView mv : mems) {
                blobBuilder.addMembers(MatchTournamentMemberRow.newBuilder()
                        .setSeasonId(season.seasonId())
                        .setPlayerId(mv.getPlayerId())
                        .setSquadId(winnerId)
                        .setRole(mv.getRoleValue())
                        .setStarterSlot(mv.getStarterSlot())
                        .setPlayerName(mv.getPlayerName())
                        .setZoneId(mv.getZoneId())
                        .setJoinedMs(mv.getJoinedMs())
                        .setClassId(mv.getClassId())
                        .setGender(mv.getGender())
                        .setAppearanceId(mv.getAppearanceId())
                        .build());
            }
            byte[] champBlob = blobBuilder.build().toByteArray();
            int champHonor = (champ == null ? 0 : champ.honorBits()) | TournamentRules.HONOR_BIT_CHAMPION;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                            + " SET state=?,final_rank=1,honor_bits=?,updated_ms=? WHERE squad_id=?")) {
                ps.setInt(1, TournamentSquadState.TOURNAMENT_SQUAD_STATE_CHAMPION_VALUE);
                ps.setInt(2, champHonor);
                ps.setLong(3, now);
                ps.setLong(4, winnerId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SEASON
                            + " SET champion_squad_id=?,champion_squad_name=?,champion_emblem_id=?,champion_member_summary=?,champion_members_blob=?,final_mvp_player_id=?,final_mvp_player_name=?,final_mvp_score=?,champion_honor_bits=?,updated_ms=? WHERE season_id=?")) {
                ps.setLong(1, winnerId);
                ps.setString(2, champ == null ? "" : champ.name());
                ps.setInt(3, champ == null ? TournamentRules.DEFAULT_EMBLEM_ID : emblemOrDefault(champ.emblemId()));
                ps.setString(4, summary);
                ps.setBytes(5, champBlob);
                ps.setLong(6, m.mvpPlayerId());
                ps.setString(7, m.mvpPlayerName() == null ? "" : m.mvpPlayerName());
                ps.setInt(8, m.mvpScore());
                ps.setInt(9, champHonor);
                ps.setLong(10, now);
                ps.setInt(11, season.seasonId());
                ps.executeUpdate();
            }
            return;
        }

        boolean routedLoserToThird = false;
        if (totalRounds >= 2 && m.roundIndex() == totalRounds - 1 && loserId != 0L) {
            long thirdMatchId = 0L;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT match_id FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " WHERE season_id=? AND phase=? AND round_index=? AND slot_index=1 AND is_third_place=1 LIMIT 1")) {
                ps.setInt(1, season.seasonId());
                ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
                ps.setInt(3, totalRounds);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        thirdMatchId = rs.getLong(1);
                    }
                }
            }
            if (thirdMatchId != 0L) {
                String thirdCol = (m.slotIndex() % 2) == 0 ? "squad_a_id" : "squad_b_id";
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH + " SET " + thirdCol + "=?,updated_ms=? WHERE match_id=?")) {
                    ps.setLong(1, loserId);
                    ps.setLong(2, now);
                    ps.setLong(3, thirdMatchId);
                    ps.executeUpdate();
                }
                routedLoserToThird = true;
                int siblingState = 0;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT state FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " WHERE season_id=? AND phase=? AND round_index=? AND slot_index=? LIMIT 1")) {
                    ps.setInt(1, season.seasonId());
                    ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
                    ps.setInt(3, m.roundIndex());
                    ps.setInt(4, m.slotIndex() ^ 1);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            siblingState = rs.getInt(1);
                        }
                    }
                }
                if (siblingState == TournamentMatchState.TOURNAMENT_MATCH_STATE_BYE_VALUE) {
                    SquadRow third = readSquadTx(c, loserId);
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                    + " SET state=?,winner_squad_id=?,finished_ms=?,updated_ms=? WHERE match_id=?")) {
                        ps.setInt(1, TournamentMatchState.TOURNAMENT_MATCH_STATE_BYE_VALUE);
                        ps.setLong(2, loserId);
                        ps.setLong(3, now);
                        ps.setLong(4, now);
                        ps.setLong(5, thirdMatchId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                                    + " SET state=?,final_rank=3,updated_ms=? WHERE squad_id=?")) {
                        ps.setInt(1, TournamentSquadState.TOURNAMENT_SQUAD_STATE_ELIMINATED_VALUE);
                        ps.setLong(2, now);
                        ps.setLong(3, loserId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE " + MatchFeatureTables.TOURNAMENT_SEASON
                                    + " SET third_place_squad_id=?,third_place_squad_name=?,third_place_emblem_id=?,updated_ms=? WHERE season_id=?")) {
                        ps.setLong(1, loserId);
                        ps.setString(2, third == null ? "" : third.name());
                        ps.setInt(3, third == null ? TournamentRules.DEFAULT_EMBLEM_ID : emblemOrDefault(third.emblemId()));
                        ps.setLong(4, now);
                        ps.setInt(5, season.seasonId());
                        ps.executeUpdate();
                    }
                }
            }
        }
        if (!routedLoserToThird && loserId != 0L) {
            int elimRank = TournamentRules.rankForEliminatedRound(season.knockoutTeamCount(), m.roundIndex());
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                            + " SET state=?,final_rank=?,updated_ms=? WHERE squad_id=?")) {
                ps.setInt(1, TournamentSquadState.TOURNAMENT_SQUAD_STATE_ELIMINATED_VALUE);
                ps.setInt(2, elimRank);
                ps.setLong(3, now);
                ps.setLong(4, loserId);
                ps.executeUpdate();
            }
        }
        String col = (m.slotIndex() % 2) == 0 ? "squad_a_id" : "squad_b_id";
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH + " SET " + col
                        + "=?,updated_ms=? WHERE season_id=? AND phase=? AND round_index=? AND slot_index=?")) {
            ps.setLong(1, winnerId);
            ps.setLong(2, now);
            ps.setInt(3, season.seasonId());
            ps.setInt(4, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
            ps.setInt(5, m.roundIndex() + 1);
            ps.setInt(6, m.slotIndex() / 2);
            ps.executeUpdate();
        }
    }

    private void settleSeasonTx(Connection c, SeasonRow season, long now, LongSupplier idGenerator) throws SQLException {
        List<SquadRow> squads = new ArrayList<>();
        Set<Long> top4 = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SQUAD + " WHERE season_id=? AND state IN(2,3,4)")) {
            ps.setInt(1, season.seasonId());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SquadRow sq = scanSquad(rs);
                    squads.add(sq);
                    if (sq.finalRank() >= 1 && sq.finalRank() <= 4) {
                        top4.add(sq.squadId());
                    }
                }
            }
        }
        for (SquadRow sq : squads) {
            Optional<TournamentRewardTable> tierOpt = tables.rewardForPlacement(sq.finalRank(), sq.qualifierPlayed());
            if (tierOpt.isEmpty()) {
                continue;
            }
            TournamentRewardTable tier = tierOpt.get();
            byte[] attBytes = TournamentTables.buildMailAttachments(tier).toByteArray();
            for (TournamentMemberView mem : loadMembersTx(c, sq.squadId())) {
                insertRewardIfAbsentTx(c, season.seasonId(), mem.getPlayerId(), tier, idGenerator.getAsLong(),
                        sq.squadId(), sq.finalRank(), attBytes, now);
            }
        }
        Optional<TournamentRewardTable> cheerOpt = tables.cheerReward();
        if (cheerOpt.isPresent() && !top4.isEmpty()) {
            TournamentRewardTable cheerTier = cheerOpt.get();
            byte[] attBytes = TournamentTables.buildMailAttachments(cheerTier).toByteArray();
            List<long[]> hits = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player_id,squad_id FROM " + MatchFeatureTables.TOURNAMENT_CHEER
                            + " WHERE season_id=? AND rewarded=0")) {
                ps.setInt(1, season.seasonId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long pid = rs.getLong(1);
                        long sid = rs.getLong(2);
                        if (top4.contains(sid)) {
                            hits.add(new long[]{pid, sid});
                        }
                    }
                }
            }
            for (long[] h : hits) {
                insertRewardIfAbsentTx(c, season.seasonId(), h[0], cheerTier, idGenerator.getAsLong(),
                        h[1], 0, attBytes, now);
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_CHEER + " SET rewarded=1 WHERE season_id=? AND player_id=?")) {
                    ps.setInt(1, season.seasonId());
                    ps.setLong(2, h[0]);
                    ps.executeUpdate();
                }
            }
        }
        Optional<TournamentRewardTable> predOpt = tables.predictionReward();
        if (predOpt.isPresent() && tables.rule().getPredictionEnabled()) {
            int minHits = tables.rule().getPredictionRewardMinHits() > 0 ? tables.rule().getPredictionRewardMinHits() : 2;
            TournamentRewardTable predTier = predOpt.get();
            byte[] attBytes = TournamentTables.buildMailAttachments(predTier).toByteArray();
            List<Long> winners = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player_id FROM " + MatchFeatureTables.TOURNAMENT_PREDICTION
                            + " WHERE season_id=? AND result_state=1 GROUP BY player_id HAVING COUNT(*)>=?")) {
                ps.setInt(1, season.seasonId());
                ps.setInt(2, minHits);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        winners.add(rs.getLong(1));
                    }
                }
            }
            for (long pid : winners) {
                insertRewardIfAbsentTx(c, season.seasonId(), pid, predTier, idGenerator.getAsLong(),
                        0L, 0, attBytes, now);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + MatchFeatureTables.TOURNAMENT_SEASON + " SET stage=?,settled_ms=?,updated_ms=? WHERE season_id=?")) {
            ps.setInt(1, TournamentStage.TOURNAMENT_STAGE_SETTLED_VALUE);
            ps.setLong(2, now);
            ps.setLong(3, now);
            ps.setInt(4, season.seasonId());
            ps.executeUpdate();
        }
    }

    private void openNextSeasonTx(Connection c, SeasonRow cur, long now) throws SQLException {
        int nextId = cur.seasonId() + 1;
        if (readSeasonForUpdateTx(c, nextId) != null) {
            return;
        }
        insertNewSeasonTx(c, nextId, now);
    }

    // ---- 底层查询与映射辅助 ----

    private SeasonRow ensureSeasonTx(Connection c, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SEASON + " ORDER BY season_id DESC LIMIT 1")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return scanSeason(rs);
                }
            }
        }
        insertNewSeasonTx(c, 1, now);
        return readSeasonForUpdateTx(c, 1);
    }

    private void insertNewSeasonTx(Connection c, int seasonId, long startMs) throws SQLException {
        TournamentRuleTable r = tables.rule();
        long qStart = startMs + r.getRegistrationDays() * MILLIS_PER_DAY;
        long kStart = qStart + r.getQualifierDays() * MILLIS_PER_DAY;
        long sStart = kStart + r.getKnockoutDays() * MILLIS_PER_DAY;
        long endMs = sStart + r.getSettleDisplayDays() * MILLIS_PER_DAY;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + MatchFeatureTables.TOURNAMENT_SEASON
                        + "(season_id,season_key,season_name,rule_version,stage,registration_start_ms,qualifier_start_ms,"
                        + "knockout_start_ms,settle_start_ms,end_ms,battle_team_size,roster_max_size,knockout_team_count,"
                        + "champion_squad_id,champion_squad_name,champion_member_summary,settled_ms,updated_ms,"
                        + "champion_members_blob,champion_salute_count,"
                        + "champion_emblem_id,third_place_squad_id,third_place_squad_name,third_place_emblem_id,"
                        + "final_mvp_player_id,final_mvp_player_name,final_mvp_score,champion_honor_bits) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0,0,'','',0,?,?,0,0,0,'',0,0,'',0,0) "
                        + "ON DUPLICATE KEY UPDATE season_id=season_id")) {
            ps.setInt(1, seasonId);
            ps.setString(2, "ts" + seasonId);
            ps.setString(3, "第" + seasonId + "赛季·群仙问道赛");
            ps.setInt(4, r.getRuleVersion());
            ps.setInt(5, TournamentStage.TOURNAMENT_STAGE_REGISTRATION_VALUE);
            ps.setLong(6, startMs);
            ps.setLong(7, qStart);
            ps.setLong(8, kStart);
            ps.setLong(9, sStart);
            ps.setLong(10, endMs);
            ps.setInt(11, r.getBattleTeamSize());
            ps.setInt(12, r.getRosterMaxSize());
            ps.setLong(13, startMs);
            ps.setBytes(14, new byte[0]);
            ps.executeUpdate();
        }
    }

    private void insertRewardIfAbsentTx(Connection c, int seasonId, long playerId, TournamentRewardTable tier,
                                        long rewardId, long squadId, int finalRank, byte[] attBytes, long now) throws SQLException {
        try (PreparedStatement check = c.prepareStatement(
                "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_REWARD + " WHERE season_id=? AND player_id=? AND tier_kind=?")) {
            check.setInt(1, seasonId);
            check.setLong(2, playerId);
            check.setInt(3, tier.getTierKind());
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    return;
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO " + MatchFeatureTables.TOURNAMENT_REWARD
                        + "(season_id,player_id,tier_kind,reward_id,squad_id,final_rank,tier_id,gold_amount,"
                        + "attachments,title_code,badge_code,delivery_state,mail_id,claimed_ms,updated_ms) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,0,0,?)")) {
            ps.setInt(1, seasonId);
            ps.setLong(2, playerId);
            ps.setInt(3, tier.getTierKind());
            ps.setLong(4, rewardId);
            ps.setLong(5, squadId);
            ps.setInt(6, finalRank);
            ps.setInt(7, tier.getId());
            ps.setLong(8, tier.getGoldAmount());
            ps.setBytes(9, attBytes);
            ps.setString(10, tier.getTitleCode());
            ps.setString(11, tier.getBadgeCode());
            ps.setInt(12, TournamentRewardDeliveryState.TOURNAMENT_REWARD_DELIVERY_CLAIMABLE_VALUE);
            ps.setLong(13, now);
            ps.executeUpdate();
        }
    }

    private SeasonRow readSeasonTx(Connection c, int seasonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SEASON + " WHERE season_id=?")) {
            ps.setInt(1, seasonId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? scanSeason(rs) : null;
            }
        }
    }

    private SeasonRow readSeasonForUpdateTx(Connection c, int seasonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SEASON + " WHERE season_id=? FOR UPDATE")) {
            ps.setInt(1, seasonId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? scanSeason(rs) : null;
            }
        }
    }

    private SquadRow readSquadTx(Connection c, long squadId) throws SQLException {
        if (squadId == 0L) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SQUAD + " WHERE squad_id=?")) {
            ps.setLong(1, squadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? scanSquad(rs) : null;
            }
        }
    }

    private SquadRow readSquadForUpdateTx(Connection c, long squadId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SQUAD + " WHERE squad_id=? FOR UPDATE")) {
            ps.setLong(1, squadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? scanSquad(rs) : null;
            }
        }
    }

    private MatchRow readMatchForUpdateTx(Connection c, long matchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH + " WHERE match_id=? FOR UPDATE")) {
            ps.setLong(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? scanMatch(rs) : null;
            }
        }
    }

    private MatchRow findMatchByBattleIdForUpdateTx(Connection c, long battleId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH + " WHERE battle_id=? FOR UPDATE")) {
            ps.setLong(1, battleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? scanMatch(rs) : null;
            }
        }
    }

    private MatchRow findActiveMatchForSquadTx(Connection c, int seasonId, long squadId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                        + " WHERE season_id=? AND (squad_a_id=? OR squad_b_id=?) AND state IN(1,2,3,7)"
                        + " ORDER BY CASE WHEN phase=2 THEN 3 WHEN phase=1 THEN 2 ELSE 1 END DESC, round_index DESC, match_id DESC LIMIT 1")) {
            ps.setInt(1, seasonId);
            ps.setLong(2, squadId);
            ps.setLong(3, squadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? scanMatch(rs) : null;
            }
        }
    }

    private MatchRow findActiveQualifierMatchTx(Connection c, int seasonId, long squadId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                        + " WHERE season_id=? AND phase=? AND (squad_a_id=? OR squad_b_id=?) AND state IN(1,2,3) LIMIT 1 FOR UPDATE")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_QUALIFIER_VALUE);
            ps.setLong(3, squadId);
            ps.setLong(4, squadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? scanMatch(rs) : null;
            }
        }
    }

    private MatchRow findActiveKnockoutMatchTx(Connection c, int seasonId, long squadId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                        + " WHERE season_id=? AND phase=? AND (squad_a_id=? OR squad_b_id=?) AND state IN(1,2,7) ORDER BY round_index ASC LIMIT 1 FOR UPDATE")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
            ps.setLong(3, squadId);
            ps.setLong(4, squadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? scanMatch(rs) : null;
            }
        }
    }

    private MatchRow findWaitingQualifierOpponentTx(Connection c, int seasonId, long mySquadId, int maxPairMeetings) throws SQLException {
        List<MatchRow> candidates = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                        + " WHERE season_id=? AND phase=? AND state=1 AND squad_a_id<>? AND squad_b_id=0 ORDER BY match_id ASC LIMIT 16 FOR UPDATE")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_QUALIFIER_VALUE);
            ps.setLong(3, mySquadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    candidates.add(scanMatch(rs));
                }
            }
        }
        for (MatchRow cand : candidates) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COUNT(*) FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " WHERE season_id=? AND phase=? AND state IN(2,3,4,6)"
                            + " AND ((squad_a_id=? AND squad_b_id=?) OR (squad_a_id=? AND squad_b_id=?))")) {
                ps.setInt(1, seasonId);
                ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_QUALIFIER_VALUE);
                ps.setLong(3, mySquadId);
                ps.setLong(4, cand.squadAId());
                ps.setLong(5, cand.squadAId());
                ps.setLong(6, mySquadId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) < maxPairMeetings) {
                        return cand;
                    }
                }
            }
        }
        return null;
    }

    private long findPlayerSquadIdTx(Connection c, int seasonId, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT squad_id FROM " + MatchFeatureTables.TOURNAMENT_MEMBER + " WHERE season_id=? AND player_id=?")) {
            ps.setInt(1, seasonId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    private boolean squadNameExistsTx(Connection c, int seasonId, String norm) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_SQUAD + " WHERE season_id=? AND name_norm=?")) {
            ps.setInt(1, seasonId);
            ps.setString(2, norm);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private boolean hasCheeredTx(Connection c, int seasonId, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_CHEER + " WHERE season_id=? AND player_id=?")) {
            ps.setInt(1, seasonId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private int countRegisteredSquadsTx(Connection c, int seasonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + MatchFeatureTables.TOURNAMENT_SQUAD + " WHERE season_id=? AND state IN(2,3,4)")) {
            ps.setInt(1, seasonId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private List<TournamentMemberView> loadMembersTx(Connection c, long squadId) throws SQLException {
        List<TournamentMemberView> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_id,player_name,zone_id,role,starter_slot,joined_ms,class_id,gender,appearance_id FROM "
                        + MatchFeatureTables.TOURNAMENT_MEMBER
                        + " WHERE squad_id=? ORDER BY role ASC, starter_slot ASC, joined_ms ASC")) {
            ps.setLong(1, squadId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    TournamentMemberRole role = TournamentMemberRole.forNumber(rs.getInt(4));
                    String app = rs.getString(9);
                    out.add(TournamentMemberView.newBuilder()
                            .setPlayerId(rs.getLong(1))
                            .setPlayerName(rs.getString(2))
                            .setZoneId(rs.getInt(3))
                            .setRole(role == null ? TournamentMemberRole.TOURNAMENT_MEMBER_ROLE_UNSPECIFIED : role)
                            .setStarterSlot(rs.getInt(5))
                            .setJoinedMs(rs.getLong(6))
                            .setClassId(rs.getInt(7))
                            .setGender(rs.getInt(8))
                            .setAppearanceId(app == null ? "" : app)
                            .build());
                }
            }
        }
        return out;
    }

    private void upsertMatchGameTx(Connection c, long matchId, int gameIndex, int seasonId, int phase,
                                   long battleId, int leylineElement, long winnerSquadId, int outcome, long finishedMs) throws SQLException {
        if (matchId == 0L || battleId == 0L) {
            return;
        }
        int gIdx = Math.max(1, gameIndex);
        boolean exists;
        try (PreparedStatement check = c.prepareStatement(
                "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_MATCH_GAME + " WHERE match_id=? AND game_index=?")) {
            check.setLong(1, matchId);
            check.setInt(2, gIdx);
            try (ResultSet rs = check.executeQuery()) {
                exists = rs.next();
            }
        }
        if (exists) {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH_GAME
                            + " SET battle_id=?,leyline_element=?,winner_squad_id=?,outcome=?,finished_ms=? WHERE match_id=? AND game_index=?")) {
                ps.setLong(1, battleId);
                ps.setInt(2, leylineElement);
                ps.setLong(3, winnerSquadId);
                ps.setInt(4, outcome);
                ps.setLong(5, finishedMs);
                ps.setLong(6, matchId);
                ps.setInt(7, gIdx);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + MatchFeatureTables.TOURNAMENT_MATCH_GAME
                            + "(match_id,game_index,season_id,phase,battle_id,leyline_element,winner_squad_id,outcome,finished_ms) "
                            + "VALUES(?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, matchId);
                ps.setInt(2, gIdx);
                ps.setInt(3, seasonId);
                ps.setInt(4, phase);
                ps.setLong(5, battleId);
                ps.setInt(6, leylineElement);
                ps.setLong(7, winnerSquadId);
                ps.setInt(8, outcome);
                ps.setLong(9, finishedMs);
                ps.executeUpdate();
            }
        }
    }

    private List<Long> loadGameBattleIdsTx(Connection c, long matchId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT battle_id FROM " + MatchFeatureTables.TOURNAMENT_MATCH_GAME
                        + " WHERE match_id=? ORDER BY game_index ASC")) {
            ps.setLong(1, matchId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long bid = rs.getLong(1);
                    if (bid != 0L) {
                        ids.add(bid);
                    }
                }
            }
        }
        return ids;
    }

    private boolean salutedTodayTx(Connection c, long playerId, long nowMs) throws SQLException {
        if (playerId == 0L) {
            return false;
        }
        int day = TournamentRules.utc8DayKey(nowMs);
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_SALUTE + " WHERE player_id=? AND salute_day=? LIMIT 1")) {
            ps.setLong(1, playerId);
            ps.setInt(2, day);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** 扫描 BO3 局间换阵倒计时到期的淘汰赛对局，按上局合法阵容自动切 READY 并返回建房规格。 */
    public List<LaunchSpec> takeDueIntermissionLaunches(long nowOverrideMs) throws SQLException {
        long now = nowOverrideMs > 0L ? nowOverrideMs : clockMs.getAsLong();
        return inTx(c -> {
            TournamentRuleTable rule = tables.rule();
            List<MatchRow> due = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " WHERE phase=? AND state=? AND intermission_deadline_ms>0 AND intermission_deadline_ms<=?"
                            + " ORDER BY match_id ASC LIMIT 16 FOR UPDATE")) {
                ps.setInt(1, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
                ps.setInt(2, TournamentMatchState.TOURNAMENT_MATCH_STATE_INTERMISSION_VALUE);
                ps.setLong(3, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        due.add(scanMatch(rs));
                    }
                }
            }
            List<LaunchSpec> specs = new ArrayList<>();
            for (MatchRow m : due) {
                if (m.squadAId() == 0L || m.squadBId() == 0L) {
                    continue;
                }
                SeasonRow season = readSeasonForUpdateTx(c, m.seasonId());
                if (season == null) {
                    continue;
                }
                List<Long> teamA = TournamentRules.decodeLineup(m.lineupA());
                if (teamA.size() != season.battleTeamSize()) {
                    List<TournamentMemberView> membersA = loadMembersTx(c, m.squadAId());
                    if (membersA.size() >= season.battleTeamSize()) {
                        teamA = resolveLineup(membersA, List.of(), season.battleTeamSize());
                    }
                }
                List<Long> teamB = TournamentRules.decodeLineup(m.lineupB());
                if (teamB.size() != season.battleTeamSize()) {
                    List<TournamentMemberView> membersB = loadMembersTx(c, m.squadBId());
                    if (membersB.size() >= season.battleTeamSize()) {
                        teamB = resolveLineup(membersB, List.of(), season.battleTeamSize());
                    }
                }
                if (teamA.size() != season.battleTeamSize() || teamB.size() != season.battleTeamSize()) {
                    continue;
                }
                SquadRow sqA = readSquadTx(c, m.squadAId());
                SquadRow sqB = readSquadTx(c, m.squadBId());
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " SET lineup_a=?,lineup_b=?,ready_a=1,ready_b=1,state=?,intermission_deadline_ms=0,updated_ms=? WHERE match_id=?")) {
                    ps.setBytes(1, TournamentRules.encodeLineup(teamA));
                    ps.setBytes(2, TournamentRules.encodeLineup(teamB));
                    ps.setInt(3, TournamentMatchState.TOURNAMENT_MATCH_STATE_READY_VALUE);
                    ps.setLong(4, now);
                    ps.setLong(5, m.matchId());
                    ps.executeUpdate();
                }
                TournamentLeylineElement ley = TournamentLeylineElement.forNumber(m.currentLeylineElement());
                specs.add(new LaunchSpec(
                        m.matchId(), m.seasonId(), TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT,
                        m.roundIndex(), rule.getBattleConfigId(), teamA, teamB, true,
                        m.bestOf(), m.currentGameIndex(), m.winsA(), m.winsB(),
                        ley == null ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED : ley,
                        m.squadAId(), sqA == null ? "" : sqA.name(), sqA == null ? TournamentRules.DEFAULT_EMBLEM_ID : sqA.emblemId(),
                        m.squadBId(), sqB == null ? "" : sqB.name(), sqB == null ? TournamentRules.DEFAULT_EMBLEM_ID : sqB.emblemId(),
                        false, m.isThirdPlace()));
            }
            return specs;
        });
    }

    /** 原子执行每日一次免费观道致敬（主键 (player_id, salute_day) 防刷，累加全服致敬计数）。 */
    public SaluteOutcome saluteChampion(long playerId, int requestedSeasonId, long rewardId) throws SQLException {
        requireOpen();
        if (playerId == 0L || rewardId == 0L) {
            throw new DomainException(MatchErrorTip.match_error.kMatchInternal_VALUE);
        }
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow season = null;
            if (requestedSeasonId != 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SEASON
                                + " WHERE season_id=? AND champion_squad_id>0 FOR UPDATE")) {
                    ps.setInt(1, requestedSeasonId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            season = scanSeason(rs);
                        }
                    }
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SEASON
                                + " WHERE champion_squad_id>0 ORDER BY season_id DESC LIMIT 1 FOR UPDATE")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            season = scanSeason(rs);
                        }
                    }
                }
            }
            if (season == null) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentNoChampionToSalute_VALUE);
            }
            int saluteDay = TournamentRules.utc8DayKey(now);
            try (PreparedStatement check = c.prepareStatement(
                    "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_SALUTE
                            + " WHERE player_id=? AND salute_day=? FOR UPDATE")) {
                check.setLong(1, playerId);
                check.setInt(2, saluteDay);
                try (ResultSet rs = check.executeQuery()) {
                    if (rs.next()) {
                        throw new DomainException(MatchErrorTip.match_error.kMatchTournamentAlreadySalutedToday_VALUE);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + MatchFeatureTables.TOURNAMENT_SALUTE
                            + "(player_id,salute_day,season_id,reward_id,mail_id,created_ms) VALUES(?,?,?,?,0,?)")) {
                ps.setLong(1, playerId);
                ps.setInt(2, saluteDay);
                ps.setInt(3, season.seasonId());
                ps.setLong(4, rewardId);
                ps.setLong(5, now);
                ps.executeUpdate();
            } catch (java.sql.SQLIntegrityConstraintViolationException dup) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentAlreadySalutedToday_VALUE);
            }
            long newCount = season.championSaluteCount() + 1L;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SEASON
                            + " SET champion_salute_count=?,updated_ms=? WHERE season_id=?")) {
                ps.setLong(1, newCount);
                ps.setLong(2, now);
                ps.setInt(3, season.seasonId());
                ps.executeUpdate();
            }
            return new SaluteOutcome(season.seasonId(), saluteDay, newCount, rewardId);
        });
    }

    /** 待补发的观道致敬邮件记录（mail_id=0 代表上次发信失败，后台可凭原 reward_id 幂等补发）。 */
    public record PendingSaluteMail(long playerId, int saluteDay, int seasonId, long rewardId) {
    }

    /** 事务外观道致敬邮件投递成功后回填 mail_id 供审计。 */
    public void updateSaluteMailId(long playerId, int saluteDay, long mailId) throws SQLException {
        if (playerId == 0L || saluteDay == 0 || mailId == 0L) {
            return;
        }
        inTx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SALUTE
                            + " SET mail_id=? WHERE player_id=? AND salute_day=?")) {
                ps.setLong(1, mailId);
                ps.setLong(2, playerId);
                ps.setInt(3, saluteDay);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** 查询尚未确认投递成功（mail_id=0）的观道致敬邮件，供后台驱动器按原 reward_id 幂等补发。 */
    public List<PendingSaluteMail> listPendingSaluteMails(int limit) throws SQLException {
        int cap = Math.max(1, Math.min(limit, 100));
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT player_id,salute_day,season_id,reward_id FROM " + MatchFeatureTables.TOURNAMENT_SALUTE
                             + " WHERE mail_id=0 AND reward_id<>0 ORDER BY created_ms ASC LIMIT ?")) {
            ps.setInt(1, cap);
            List<PendingSaluteMail> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new PendingSaluteMail(rs.getLong(1), rs.getInt(2), rs.getInt(3), rs.getLong(4)));
                }
            }
            return out;
        }
    }

    /** 校验 battle_id 是否允许全服公开回放（仅淘汰赛且配表开关开启，绝不放行海选/私密切磋）。 */
    public boolean isPublicKnockoutReplayAllowed(long battleId) {
        if (battleId == 0L || !tables.rule().getPublicKnockoutReplay()) {
            return false;
        }
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_MATCH_GAME
                            + " WHERE battle_id=? AND phase=? LIMIT 1")) {
                ps.setLong(1, battleId);
                ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " WHERE battle_id=? AND phase=? LIMIT 1")) {
                ps.setLong(1, battleId);
                ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /** 校验 battle_id 是否属于锦标赛对局（供观战喝彩接口防任意伪造 battle_id）。 */
    public boolean isTournamentBattle(long battleId) {
        if (battleId == 0L) {
            return false;
        }
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_MATCH_GAME + " WHERE battle_id=? LIMIT 1")) {
                ps.setLong(1, battleId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_MATCH + " WHERE battle_id=? LIMIT 1")) {
                ps.setLong(1, battleId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            return false;
        }
    }

    // ---- 《群仙问道赛》V3 扩展存储：队徽更换 / 零积分热身约战 / 神机竞猜榜 / 真实外观回填 ----

    public TournamentSquadView updateSquadEmblem(long captainId, int emblemId) throws SQLException {
        requireOpen();
        if (!TournamentRules.isValidEmblemId(emblemId)) {
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentEmblemInvalid_VALUE);
        }
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() == TournamentStage.TOURNAMENT_STAGE_SETTLED_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            long squadId = findPlayerSquadIdTx(c, cur.seasonId(), captainId);
            if (squadId == 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            SquadRow sq = readSquadForUpdateTx(c, squadId);
            if (sq == null || sq.state() == TournamentSquadState.TOURNAMENT_SQUAD_STATE_DISBANDED_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            if (sq.captainPlayerId() != captainId) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentNotCaptain_VALUE);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD + " SET emblem_id=?,updated_ms=? WHERE squad_id=?")) {
                ps.setInt(1, emblemId);
                ps.setLong(2, now);
                ps.setLong(3, squadId);
                ps.executeUpdate();
            }
            return toSquadView(readSquadForUpdateTx(c, squadId), loadMembersTx(c, squadId));
        });
    }

    public PrepareMatchOutcome prepareWarmup(long captainId, long targetSquadId,
                                             TournamentLeylineElement leyline,
                                             List<Long> lineupIds, long newMatchId) throws SQLException {
        requireOpen();
        TournamentRuleTable rule = tables.rule();
        if (!rule.getWarmupSparEnabled()) {
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentWarmupUnavailable_VALUE);
        }
        int reqLeyline = (leyline == null || leyline.getNumber() < 0 || leyline.getNumber() > 5)
                ? 0 : leyline.getNumber();
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() != TournamentStage.TOURNAMENT_STAGE_REGISTRATION_VALUE
                    && cur.stage() != TournamentStage.TOURNAMENT_STAGE_QUALIFIER_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            long mySquadId = findPlayerSquadIdTx(c, cur.seasonId(), captainId);
            if (mySquadId == 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            SquadRow mySquad = readSquadForUpdateTx(c, mySquadId);
            if (mySquad.captainPlayerId() != captainId) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentNotCaptain_VALUE);
            }
            if (mySquad.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_DRAFT_VALUE
                    && mySquad.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_REGISTERED_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentWarmupUnavailable_VALUE);
            }
            if (mySquad.memberCount() < cur.battleTeamSize()) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentMemberNotEnough_VALUE);
            }
            if (targetSquadId == mySquadId) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentWarmupUnavailable_VALUE);
            }
            if (targetSquadId != 0L) {
                SquadRow targetSq = readSquadTx(c, targetSquadId);
                if (targetSq == null || targetSq.seasonId() != cur.seasonId()
                        || (targetSq.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_DRAFT_VALUE
                        && targetSq.state() != TournamentSquadState.TOURNAMENT_SQUAD_STATE_REGISTERED_VALUE)
                        || targetSq.memberCount() < cur.battleTeamSize()) {
                    throw new DomainException(MatchErrorTip.match_error.kMatchTournamentWarmupUnavailable_VALUE);
                }
            }
            List<TournamentMemberView> members = loadMembersTx(c, mySquadId);
            List<Long> lineup = resolveLineup(members, lineupIds, cur.battleTeamSize());
            byte[] encodedLineup = TournamentRules.encodeLineup(lineup);

            // 若本队已有进行中/已成局的热身赛，幂等返回
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " WHERE season_id=? AND phase=? AND (squad_a_id=? OR squad_b_id=?) AND state IN(2,3) LIMIT 1 FOR UPDATE")) {
                ps.setInt(1, cur.seasonId());
                ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_WARMUP_VALUE);
                ps.setLong(3, mySquadId);
                ps.setLong(4, mySquadId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        MatchRow active = scanMatch(rs);
                        return new PrepareMatchOutcome(toMatchView(active, loadSquadMapTx(c, active.squadAId(), active.squadBId()), List.of()), null);
                    }
                }
            }

            // 查找可立即撮合的热身对手等待单
            MatchRow paired = null;
            if (targetSquadId != 0L) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " WHERE season_id=? AND phase=? AND state=1 AND squad_a_id=? AND (squad_b_id=? OR squad_b_id=0) ORDER BY match_id ASC LIMIT 1 FOR UPDATE")) {
                    ps.setInt(1, cur.seasonId());
                    ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_WARMUP_VALUE);
                    ps.setLong(3, targetSquadId);
                    ps.setLong(4, mySquadId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            paired = scanMatch(rs);
                        }
                    }
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " WHERE season_id=? AND phase=? AND state=1 AND squad_a_id<>? AND (squad_b_id=? OR squad_b_id=0)"
                                + " ORDER BY CASE WHEN squad_b_id=? THEN 0 ELSE 1 END ASC, match_id ASC LIMIT 1 FOR UPDATE")) {
                    ps.setInt(1, cur.seasonId());
                    ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_WARMUP_VALUE);
                    ps.setLong(3, mySquadId);
                    ps.setLong(4, mySquadId);
                    ps.setLong(5, mySquadId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            paired = scanMatch(rs);
                        }
                    }
                }
            }

            if (paired != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " SET state=?,updated_ms=? WHERE season_id=? AND phase=? AND squad_a_id=? AND state=1 AND match_id<>?")) {
                    ps.setInt(1, TournamentMatchState.TOURNAMENT_MATCH_STATE_FORFEIT_VALUE);
                    ps.setLong(2, now);
                    ps.setInt(3, cur.seasonId());
                    ps.setInt(4, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_WARMUP_VALUE);
                    ps.setLong(5, mySquadId);
                    ps.setLong(6, paired.matchId());
                    ps.executeUpdate();
                }
                int chosenLey = paired.currentLeylineElement() != 0 ? paired.currentLeylineElement() : reqLeyline;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                + " SET squad_b_id=?,lineup_b=?,ready_b=1,state=?,current_leyline_element=?,updated_ms=? WHERE match_id=?")) {
                    ps.setLong(1, mySquadId);
                    ps.setBytes(2, encodedLineup);
                    ps.setInt(3, TournamentMatchState.TOURNAMENT_MATCH_STATE_READY_VALUE);
                    ps.setInt(4, chosenLey);
                    ps.setLong(5, now);
                    ps.setLong(6, paired.matchId());
                    ps.executeUpdate();
                }
                MatchRow updated = readMatchForUpdateTx(c, paired.matchId());
                Map<Long, SquadRow> squads = loadSquadMapTx(c, updated.squadAId(), updated.squadBId());
                SquadRow sqA = squads.get(updated.squadAId());
                TournamentLeylineElement leyEl = TournamentLeylineElement.forNumber(chosenLey);
                LaunchSpec launch = new LaunchSpec(
                        paired.matchId(), cur.seasonId(), TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_WARMUP, 1,
                        rule.getBattleConfigId(), TournamentRules.decodeLineup(paired.lineupA()), lineup, false,
                        1, 1, 0, 0, leyEl == null ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED : leyEl,
                        updated.squadAId(), sqA == null ? "" : sqA.name(), sqA == null ? TournamentRules.DEFAULT_EMBLEM_ID : sqA.emblemId(),
                        mySquadId, mySquad.name(), mySquad.emblemId(),
                        true, false);
                return new PrepareMatchOutcome(toMatchView(updated, squads, List.of()), launch);
            }

            // 若本队已有热身等待单，原地更新目标/阵容/灵脉
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " WHERE season_id=? AND phase=? AND squad_a_id=? AND state=1 LIMIT 1 FOR UPDATE")) {
                ps.setInt(1, cur.seasonId());
                ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_WARMUP_VALUE);
                ps.setLong(3, mySquadId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        MatchRow existing = scanMatch(rs);
                        try (PreparedStatement up = c.prepareStatement(
                                "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                                        + " SET squad_b_id=?,lineup_a=?,current_leyline_element=?,updated_ms=? WHERE match_id=?")) {
                            up.setLong(1, targetSquadId);
                            up.setBytes(2, encodedLineup);
                            up.setInt(3, reqLeyline);
                            up.setLong(4, now);
                            up.setLong(5, existing.matchId());
                            up.executeUpdate();
                        }
                        MatchRow refreshed = readMatchForUpdateTx(c, existing.matchId());
                        return new PrepareMatchOutcome(toMatchView(refreshed, loadSquadMapTx(c, mySquadId, targetSquadId), List.of()), null);
                    }
                }
            }

            if (newMatchId == 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchInternal_VALUE);
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + MatchFeatureTables.TOURNAMENT_MATCH
                            + "(match_id,season_id,phase,round_index,slot_index,squad_a_id,squad_b_id,lineup_a,lineup_b,"
                            + "ready_a,ready_b,state,battle_id,winner_squad_id,outcome,featured_spectate,"
                            + "scheduled_start_ms,deadline_ms,finished_ms,updated_ms,"
                            + "best_of,current_game_index,wins_a,wins_b,current_leyline_element,leyline_sequence,intermission_deadline_ms,"
                            + "is_third_place,mvp_player_id,mvp_player_name,mvp_squad_id,mvp_score,mvp_damage,mvp_healing,mvp_controls,banned_leyline_a,banned_leyline_b) "
                            + "VALUES(?,?,?,1,0,?,?,?,?,1,0,?,0,0,0,0,?,?,0,?,1,1,0,0,?,'',0,0,0,'',0,0,0,0,0,0,0)")) {
                ps.setLong(1, newMatchId);
                ps.setInt(2, cur.seasonId());
                ps.setInt(3, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_WARMUP_VALUE);
                ps.setLong(4, mySquadId);
                ps.setLong(5, targetSquadId);
                ps.setBytes(6, encodedLineup);
                ps.setBytes(7, new byte[0]);
                ps.setInt(8, TournamentMatchState.TOURNAMENT_MATCH_STATE_WAITING_OPPONENT_VALUE);
                ps.setLong(9, now);
                ps.setLong(10, cur.knockoutStartMs());
                ps.setLong(11, now);
                ps.setInt(12, reqLeyline);
                ps.executeUpdate();
            }
            MatchRow created = readMatchForUpdateTx(c, newMatchId);
            return new PrepareMatchOutcome(toMatchView(created, loadSquadMapTx(c, mySquadId, targetSquadId), List.of()), null);
        });
    }

    public PredictOutcome predictMatch(long playerId, String playerName, long matchId, long predictedSquadId) throws SQLException {
        requireOpen();
        if (!tables.rule().getPredictionEnabled()) {
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
        }
        if (playerId == 0L || matchId == 0L || predictedSquadId == 0L) {
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentPredictionTargetInvalid_VALUE);
        }
        String safeName = (playerName == null || playerName.isBlank()) ? "#" + Long.toUnsignedString(playerId) : playerName;
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() != TournamentStage.TOURNAMENT_STAGE_KNOCKOUT_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            MatchRow m = readMatchForUpdateTx(c, matchId);
            if (m == null || m.seasonId() != cur.seasonId()
                    || m.phase() != TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE
                    || (m.state() != TournamentMatchState.TOURNAMENT_MATCH_STATE_WAITING_OPPONENT_VALUE
                    && m.state() != TournamentMatchState.TOURNAMENT_MATCH_STATE_READY_VALUE)
                    || m.squadAId() == 0L || m.squadBId() == 0L) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentPredictionClosed_VALUE);
            }
            if (predictedSquadId != m.squadAId() && predictedSquadId != m.squadBId()) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentPredictionTargetInvalid_VALUE);
            }
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_PREDICTION
                            + " SET predicted_squad_id=?,player_name=?,updated_ms=? WHERE season_id=? AND player_id=? AND match_id=?")) {
                ps.setLong(1, predictedSquadId);
                ps.setString(2, safeName);
                ps.setLong(3, now);
                ps.setInt(4, cur.seasonId());
                ps.setLong(5, playerId);
                ps.setLong(6, matchId);
                updated = ps.executeUpdate();
            }
            if (updated == 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO " + MatchFeatureTables.TOURNAMENT_PREDICTION
                                + "(season_id,player_id,match_id,predicted_squad_id,round_index,is_third_place,player_name,result_state,earned_points,created_ms,updated_ms) "
                                + "VALUES(?,?,?,?,?,?,?,0,0,?,?)")) {
                    ps.setInt(1, cur.seasonId());
                    ps.setLong(2, playerId);
                    ps.setLong(3, matchId);
                    ps.setLong(4, predictedSquadId);
                    ps.setInt(5, m.roundIndex());
                    ps.setBoolean(6, m.isThirdPlace());
                    ps.setString(7, safeName);
                    ps.setLong(8, now);
                    ps.setLong(9, now);
                    ps.executeUpdate();
                }
            }
            int cntA = countPredictionsTx(c, cur.seasonId(), matchId, m.squadAId());
            int cntB = countPredictionsTx(c, cur.seasonId(), matchId, m.squadBId());
            return new PredictOutcome(cntA, cntB);
        });
    }

    public GetTournamentPredictionBoardResponse loadPredictionBoard(int seasonId, long playerId) throws SQLException {
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow season = seasonId == 0 ? ensureSeasonTx(c, now) : readSeasonTx(c, seasonId);
            if (season == null) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            int minHits = tables.rule().getPredictionRewardMinHits() > 0 ? tables.rule().getPredictionRewardMinHits() : 2;
            GetTournamentPredictionBoardResponse.Builder resp = GetTournamentPredictionBoardResponse.newBuilder()
                    .setSeasonId(season.seasonId())
                    .setMinHitsForReward(minHits)
                    .setServerTimeMs(now);

            final class Agg {
                final long pid;
                String name;
                int hits;
                int total;
                int points;
                long latestMs;

                Agg(long pid, String name) {
                    this.pid = pid;
                    this.name = name;
                }
            }
            record PredRow(long matchId, int roundIndex, boolean isThirdPlace, long predictedSquadId,
                           int resultState, int earnedPoints, long updatedMs) {
            }
            Map<Long, Agg> byPlayer = new HashMap<>();
            List<PredRow> myRecs = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT player_id,match_id,predicted_squad_id,round_index,is_third_place,player_name,result_state,earned_points,updated_ms FROM "
                            + MatchFeatureTables.TOURNAMENT_PREDICTION + " WHERE season_id=?")) {
                ps.setInt(1, season.seasonId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long pid = rs.getLong(1);
                        long mid = rs.getLong(2);
                        long predSq = rs.getLong(3);
                        int rIdx = rs.getInt(4);
                        boolean third = rs.getBoolean(5);
                        String pName = rs.getString(6);
                        int rState = rs.getInt(7);
                        int pts = rs.getInt(8);
                        long updMs = rs.getLong(9);
                        Agg agg = byPlayer.computeIfAbsent(pid, k -> new Agg(pid, pName == null ? "" : pName));
                        if (pName != null && !pName.isEmpty()) {
                            agg.name = pName;
                        }
                        agg.total++;
                        if (rState == 1) {
                            agg.hits++;
                            agg.points += pts;
                        }
                        if (updMs > agg.latestMs) {
                            agg.latestMs = updMs;
                        }
                        if (pid == playerId) {
                            myRecs.add(new PredRow(mid, rIdx, third, predSq, rState, pts, updMs));
                        }
                    }
                }
            }

            List<Agg> ranked = new ArrayList<>(byPlayer.values());
            ranked.sort(Comparator.<Agg>comparingInt(a -> a.points).reversed()
                    .thenComparing(Comparator.<Agg>comparingInt(a -> a.hits).reversed())
                    .thenComparingLong(a -> a.latestMs)
                    .thenComparing((a, b) -> Long.compareUnsigned(a.pid, b.pid)));
            for (int i = 0; i < ranked.size(); i++) {
                Agg a = ranked.get(i);
                int rank = i + 1;
                if (a.pid == playerId) {
                    resp.setMyRank(rank).setMyHitCount(a.hits).setMyTotalPoints(a.points);
                }
                if (i < 50) {
                    resp.addTopPredictors(TournamentPredictionRankEntry.newBuilder()
                            .setRank(rank)
                            .setPlayerId(a.pid)
                            .setPlayerName(a.name == null ? "" : a.name)
                            .setHitCount(a.hits)
                            .setTotalPredictions(a.total)
                            .setTotalPoints(a.points)
                            .build());
                }
            }

            if (!myRecs.isEmpty()) {
                Map<Long, MatchRow> matches = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH + " WHERE season_id=? AND phase=?")) {
                    ps.setInt(1, season.seasonId());
                    ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            MatchRow m = scanMatch(rs);
                            matches.put(m.matchId(), m);
                        }
                    }
                }
                Map<Long, String> squadNames = new HashMap<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT squad_id,name FROM " + MatchFeatureTables.TOURNAMENT_SQUAD + " WHERE season_id=?")) {
                    ps.setInt(1, season.seasonId());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            squadNames.put(rs.getLong(1), rs.getString(2));
                        }
                    }
                }
                myRecs.sort(Comparator.comparingInt(PredRow::roundIndex)
                        .thenComparing((a, b) -> Long.compareUnsigned(a.matchId(), b.matchId())));
                for (PredRow r : myRecs) {
                    TournamentPredictionEntryView.Builder eb = TournamentPredictionEntryView.newBuilder()
                            .setMatchId(r.matchId())
                            .setRoundIndex(r.roundIndex())
                            .setIsThirdPlace(r.isThirdPlace())
                            .setPredictedSquadId(r.predictedSquadId())
                            .setResultState(r.resultState())
                            .setEarnedPoints(r.earnedPoints())
                            .setUpdatedMs(r.updatedMs());
                    MatchRow m = matches.get(r.matchId());
                    if (m != null) {
                        eb.setSlotIndex(m.slotIndex())
                                .setSquadAId(m.squadAId())
                                .setSquadAName(squadNames.getOrDefault(m.squadAId(), ""))
                                .setSquadBId(m.squadBId())
                                .setSquadBName(squadNames.getOrDefault(m.squadBId(), ""))
                                .setWinnerSquadId(m.winnerSquadId());
                    }
                    resp.addMyPredictions(eb.build());
                }
            }
            return resp.build();
        });
    }

    /** V3 建房时最佳努力回填场景权威角色名与真实外观，供冠军殿堂与天墉城玉座永久展示获批立绘。 */
    public void syncMemberProfiles(int seasonId, List<BattlePlayerSnapshot> players) {
        if (seasonId == 0 || players == null || players.isEmpty()) {
            return;
        }
        try {
            inTx(c -> {
                for (BattlePlayerSnapshot p : players) {
                    if (p == null || p.getPlayerId() == 0L) {
                        continue;
                    }
                    try (PreparedStatement ps = c.prepareStatement(
                            "UPDATE " + MatchFeatureTables.TOURNAMENT_MEMBER
                                    + " SET player_name=CASE WHEN ?<>'' THEN ? ELSE player_name END,"
                                    + " class_id=CASE WHEN ?>0 THEN ? ELSE class_id END,"
                                    + " gender=CASE WHEN ?>0 THEN ? ELSE gender END,"
                                    + " appearance_id=CASE WHEN ?<>'' THEN ? ELSE appearance_id END"
                                    + " WHERE season_id=? AND player_id=?")) {
                        ps.setString(1, p.getPlayerName());
                        ps.setString(2, p.getPlayerName());
                        ps.setInt(3, p.getClassId());
                        ps.setInt(4, p.getClassId());
                        ps.setInt(5, p.getGender());
                        ps.setInt(6, p.getGender());
                        ps.setString(7, p.getAppearanceId());
                        ps.setString(8, p.getAppearanceId());
                        ps.setInt(9, seasonId);
                        ps.setLong(10, p.getPlayerId());
                        ps.executeUpdate();
                    }
                }
                return null;
            });
        } catch (SQLException ignored) {
            // 最佳努力外观回填，绝不阻断建房主路径
        }
    }

    /** 读取锦标赛对阵双方战队队长 player_id（用于建房快照标记 is_team_captain，供战前 3 Ban 平票优先级使用）。 */
    public Set<Long> loadSquadCaptains(int seasonId, long squadAId, long squadBId) {
        if (seasonId == 0 || (squadAId == 0L && squadBId == 0L)) {
            return Set.of();
        }
        Set<Long> captains = new HashSet<>();
        try {
            inTx(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT captain_player_id FROM " + MatchFeatureTables.TOURNAMENT_SQUAD
                                + " WHERE season_id=? AND squad_id IN (?,?)")) {
                    ps.setInt(1, seasonId);
                    ps.setLong(2, squadAId);
                    ps.setLong(3, squadBId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long cid = rs.getLong(1);
                            if (cid != 0L) {
                                captains.add(cid);
                            }
                        }
                    }
                }
                return null;
            });
        } catch (SQLException ignored) {
            // 最佳努力读取队长标记，绝不阻断建房主路径
        }
        return captains;
    }

    /** V4 模块 H：BO3 淘汰赛首局开战前，参战两队队长各禁 1 条五行灵脉并立即重算 3 局灵脉序列。 */
    public TournamentMatchView banLeyline(long captainId, long matchId, TournamentLeylineElement leyline) throws SQLException {
        requireOpen();
        if (!tables.rule().getLeylineBanEnabled()) {
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
        }
        if (captainId == 0L || matchId == 0L || !TournamentRules.isValidLeylineElement(leyline)) {
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentMatchNotReady_VALUE);
        }
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow cur = ensureSeasonTx(c, now);
            if (cur.stage() != TournamentStage.TOURNAMENT_STAGE_KNOCKOUT_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentStageClosed_VALUE);
            }
            MatchRow m = readMatchForUpdateTx(c, matchId);
            if (m == null || m.seasonId() != cur.seasonId()
                    || m.phase() != TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentMatchNotReady_VALUE);
            }
            if (m.bestOf() < 3 || m.winsA() > 0 || m.winsB() > 0 || m.squadAId() == 0L || m.squadBId() == 0L
                    || m.state() != TournamentMatchState.TOURNAMENT_MATCH_STATE_WAITING_OPPONENT_VALUE) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentMatchNotReady_VALUE);
            }
            SquadRow sqA = readSquadTx(c, m.squadAId());
            SquadRow sqB = readSquadTx(c, m.squadBId());
            if (sqA == null || sqB == null) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            int banA = m.bannedLeylineA();
            int banB = m.bannedLeylineB();
            if (captainId == sqA.captainPlayerId()) {
                // 每队队长限锁 1 次灵脉禁选且必须在未就绪前，防反复探测 SplitMix64 洗选序列或开战后篡改。
                if (banA != 0 || m.readyA()) {
                    throw new DomainException(MatchErrorTip.match_error.kMatchTournamentMatchNotReady_VALUE);
                }
                banA = leyline.getNumber();
            } else if (captainId == sqB.captainPlayerId()) {
                if (banB != 0 || m.readyB()) {
                    throw new DomainException(MatchErrorTip.match_error.kMatchTournamentMatchNotReady_VALUE);
                }
                banB = leyline.getNumber();
            } else {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentNotCaptain_VALUE);
            }
            TournamentLeylineElement banAEnum = TournamentLeylineElement.forNumber(banA);
            TournamentLeylineElement banBEnum = TournamentLeylineElement.forNumber(banB);
            List<TournamentLeylineElement> seq = TournamentRules.drawLeylinesWithBans(
                    m.seasonId(), m.matchId(), m.bestOf(),
                    banAEnum == null ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED : banAEnum,
                    banBEnum == null ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED : banBEnum);
            String seqStr = TournamentRules.encodeLeylineSequence(seq);
            int curLeyline = TournamentRules.leylineForGame(seq, 1).getNumber();
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " SET banned_leyline_a=?,banned_leyline_b=?,leyline_sequence=?,current_leyline_element=?,updated_ms=? WHERE match_id=?")) {
                ps.setInt(1, banA);
                ps.setInt(2, banB);
                ps.setString(3, seqStr);
                ps.setInt(4, curLeyline);
                ps.setLong(5, now);
                ps.setLong(6, m.matchId());
                ps.executeUpdate();
            }
            MatchRow updated = readMatchForUpdateTx(c, m.matchId());
            return toMatchView(updated, Map.of(sqA.squadId(), sqA, sqB.squadId(), sqB), loadGameBattleIdsTx(c, m.matchId()));
        });
    }

    /** V4 模块 I：从指定赛季已完赛的公开淘汰赛中确定性策展 Top 10 本季名局录（零新表，与 Go 逐条同源）。 */
    public GetTournamentHighlightsResponse loadHighlights(int seasonId) throws SQLException {
        long now = clockMs.getAsLong();
        return inTx(c -> {
            SeasonRow season = seasonId == 0 ? ensureSeasonTx(c, now) : readSeasonTx(c, seasonId);
            if (season == null) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentSquadNotFound_VALUE);
            }
            GetTournamentHighlightsResponse.Builder resp = GetTournamentHighlightsResponse.newBuilder()
                    .setSeasonId(season.seasonId())
                    .setServerTimeMs(now);
            if (!tables.rule().getPublicKnockoutReplay()) {
                return resp.build();
            }
            Map<Long, SquadRow> squads = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_SQUAD + " WHERE season_id=?")) {
                ps.setInt(1, season.seasonId());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        SquadRow sq = scanSquad(rs);
                        squads.put(sq.squadId(), sq);
                    }
                }
            }
            List<MatchRow> finished = new ArrayList<>();
            int maxRound = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                            + " WHERE season_id=? AND phase=? AND state=?")) {
                ps.setInt(1, season.seasonId());
                ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
                ps.setInt(3, TournamentMatchState.TOURNAMENT_MATCH_STATE_FINISHED_VALUE);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        MatchRow m = scanMatch(rs);
                        if (m.roundIndex() > maxRound) {
                            maxRound = m.roundIndex();
                        }
                        finished.add(m);
                    }
                }
            }
            record Candidate(MatchRow rec, TournamentRules.HighlightClass cls, List<Long> gameIds) {
            }
            List<Candidate> cands = new ArrayList<>(finished.size());
            for (MatchRow m : finished) {
                List<Long> gameIds = loadGameBattleIdsTx(c, m.matchId());
                if (m.battleId() == 0L && gameIds.isEmpty()) {
                    continue;
                }
                var cls = TournamentRules.classifyHighlightTag(
                        m.isThirdPlace(), m.roundIndex(), maxRound, m.bestOf(), m.winsA(), m.winsB());
                cands.add(new Candidate(m, cls, gameIds));
            }
            cands.sort(Comparator.<Candidate>comparingInt(x -> x.cls().priority())
                    .thenComparing((a, b) -> Integer.compareUnsigned(b.rec().mvpScore(), a.rec().mvpScore()))
                    .thenComparing((a, b) -> Long.compareUnsigned(b.rec().finishedMs(), a.rec().finishedMs()))
                    .thenComparing((a, b) -> Long.compareUnsigned(a.rec().matchId(), b.rec().matchId())));
            int limit = Math.min(10, cands.size());
            for (int i = 0; i < limit; i++) {
                Candidate cand = cands.get(i);
                TournamentHighlightTag tagEnum = TournamentHighlightTag.forNumber(cand.cls().tag());
                resp.addHighlights(TournamentHighlightEntry.newBuilder()
                        .setTag(tagEnum == null ? TournamentHighlightTag.TOURNAMENT_HIGHLIGHT_TAG_UNSPECIFIED : tagEnum)
                        .setTagLabel(cand.cls().label())
                        .setMatch(toMatchView(cand.rec(), squads, cand.gameIds()))
                        .build());
            }
            return resp.build();
        });
    }

    private void settleMatchPredictionsTx(Connection c, SeasonRow season, MatchRow m, long winnerSquadId, long now) throws SQLException {
        if (season == null || m == null || winnerSquadId == 0L
                || m.phase() != TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE) {
            return;
        }
        int weight = TournamentRules.predictionWeightForRound(season.knockoutTeamCount(), m.roundIndex(), m.isThirdPlace());
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + MatchFeatureTables.TOURNAMENT_PREDICTION
                        + " SET result_state=CASE WHEN predicted_squad_id=? THEN 1 ELSE 2 END,"
                        + " earned_points=CASE WHEN predicted_squad_id=? THEN ? ELSE 0 END,"
                        + " updated_ms=? WHERE season_id=? AND match_id=? AND result_state=0")) {
            ps.setLong(1, winnerSquadId);
            ps.setLong(2, winnerSquadId);
            ps.setInt(3, weight);
            ps.setLong(4, now);
            ps.setInt(5, season.seasonId());
            ps.setLong(6, m.matchId());
            ps.executeUpdate();
        }
    }

    private int countPredictionsTx(Connection c, int seasonId, long matchId, long predictedSquadId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM " + MatchFeatureTables.TOURNAMENT_PREDICTION
                        + " WHERE season_id=? AND match_id=? AND predicted_squad_id=?")) {
            ps.setInt(1, seasonId);
            ps.setLong(2, matchId);
            ps.setLong(3, predictedSquadId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private boolean hasOpenThirdPlaceMatchTx(Connection c, int seasonId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM " + MatchFeatureTables.TOURNAMENT_MATCH
                        + " WHERE season_id=? AND phase=? AND is_third_place=1 AND state NOT IN(4,5,6) LIMIT 1")) {
            ps.setInt(1, seasonId);
            ps.setInt(2, TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private String lookupMemberNameTx(Connection c, int seasonId, long playerId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_name FROM " + MatchFeatureTables.TOURNAMENT_MEMBER + " WHERE season_id=? AND player_id=?")) {
            ps.setInt(1, seasonId);
            ps.setLong(2, playerId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String n = rs.getString(1);
                    if (n != null && !n.isEmpty()) {
                        return n;
                    }
                }
            }
        }
        return "#" + Long.toUnsignedString(playerId);
    }

    private Map<Long, SquadRow> loadSquadMapTx(Connection c, long idA, long idB) throws SQLException {
        Map<Long, SquadRow> map = new HashMap<>();
        SquadRow a = readSquadTx(c, idA);
        if (a != null) {
            map.put(idA, a);
        }
        SquadRow b = readSquadTx(c, idB);
        if (b != null) {
            map.put(idB, b);
        }
        return map;
    }

    private void updateQualifierStatsTx(Connection c, long squadId, int pts, int played, int wins, int losses, int draws, int honorBits, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE " + MatchFeatureTables.TOURNAMENT_SQUAD
                        + " SET qualifier_points=?,qualifier_played=?,qualifier_wins=?,qualifier_losses=?,qualifier_draws=?,honor_bits=?,updated_ms=? WHERE squad_id=?")) {
            ps.setInt(1, pts);
            ps.setInt(2, played);
            ps.setInt(3, wins);
            ps.setInt(4, losses);
            ps.setInt(5, draws);
            ps.setInt(6, honorBits);
            ps.setLong(7, now);
            ps.setLong(8, squadId);
            ps.executeUpdate();
        }
    }

    private static List<Long> resolveLineup(List<TournamentMemberView> members, List<Long> requested, int required) {
        if (members.size() < required) {
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentMemberNotEnough_VALUE);
        }
        if (requested == null || requested.isEmpty()) {
            List<Long> starters = new ArrayList<>(required);
            for (TournamentMemberView m : members) {
                if (m.getStarterSlot() > 0 && starters.size() < required) {
                    starters.add(m.getPlayerId());
                }
            }
            if (starters.size() < required) {
                starters.clear();
                for (int i = 0; i < required; i++) {
                    starters.add(members.get(i).getPlayerId());
                }
            }
            return starters;
        }
        if (requested.size() != required) {
            throw new DomainException(MatchErrorTip.match_error.kMatchTournamentLineupInvalid_VALUE);
        }
        Set<Long> valid = new HashSet<>();
        for (TournamentMemberView m : members) {
            valid.add(m.getPlayerId());
        }
        Set<Long> seen = new HashSet<>();
        for (long pid : requested) {
            if (pid == 0L || !valid.contains(pid) || !seen.add(pid)) {
                throw new DomainException(MatchErrorTip.match_error.kMatchTournamentLineupInvalid_VALUE);
            }
        }
        return List.copyOf(requested);
    }

    private static boolean hasSideSurvivors(BattleResultEvent event, int teamIndex) {
        for (BattleSettlementData st : event.getSettlementsList()) {
            if (st.getPlayerTeamIndex() != teamIndex) {
                continue;
            }
            if (!st.getIsDead() && !st.getFled()) {
                return true;
            }
            for (BattlePetSettlementData pet : st.getPetsList()) {
                if (!pet.getIsDead()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static TournamentRules.KnockoutTieMetrics extractTieMetrics(BattleResultEvent event, int teamIndex, long squadId, int seedRank) {
        int units = 0;
        long hpSum = 0L;
        for (BattleSettlementData st : event.getSettlementsList()) {
            if (st.getPlayerTeamIndex() != teamIndex) {
                continue;
            }
            if (!st.getIsDead() && !st.getFled()) {
                units++;
                hpSum += st.getHealth();
            }
            for (BattlePetSettlementData pet : st.getPetsList()) {
                if (!pet.getIsDead()) {
                    units++;
                    hpSum += pet.getHealth();
                }
            }
        }
        return new TournamentRules.KnockoutTieMetrics(squadId, seedRank, units, hpSum);
    }

    private TournamentSeasonView toSeasonView(SeasonRow r, int regCount, boolean salutedToday) {
        TournamentRuleTable rule = tables.rule();
        TournamentStage stage = TournamentStage.forNumber(r.stage());
        TournamentSeasonView.Builder b = TournamentSeasonView.newBuilder()
                .setSeasonId(r.seasonId())
                .setSeasonKey(r.seasonKey())
                .setSeasonName(r.seasonName())
                .setRuleVersion(r.ruleVersion())
                .setStage(stage == null ? TournamentStage.TOURNAMENT_STAGE_UNSPECIFIED : stage)
                .setEnabled(rule.getEnabled())
                .setBattleTeamSize(r.battleTeamSize())
                .setRosterMaxSize(r.rosterMaxSize())
                .setMinLevel(rule.getMinLevel())
                .setQualifierMaxMatches(rule.getQualifierMaxMatches())
                .setParticipationMinMatches(rule.getParticipationMinMatches())
                .setKnockoutMaxTeams(rule.getKnockoutMaxTeams())
                .setKnockoutTeamCount(r.knockoutTeamCount())
                .setRegisteredSquadCount(regCount)
                .setRegistrationStartMs(r.registrationStartMs())
                .setQualifierStartMs(r.qualifierStartMs())
                .setKnockoutStartMs(r.knockoutStartMs())
                .setSettleStartMs(r.settleStartMs())
                .setEndMs(r.endMs())
                .setChampionSquadId(r.championSquadId())
                .setChampionSquadName(r.championSquadName())
                .setChampionMemberSummary(r.championMemberSummary())
                .setChampionSaluteCount(r.championSaluteCount())
                .setSalutedToday(salutedToday)
                .setChampionEmblemId(r.championEmblemId())
                .setThirdPlaceSquadId(r.thirdPlaceSquadId())
                .setThirdPlaceSquadName(r.thirdPlaceSquadName() == null ? "" : r.thirdPlaceSquadName())
                .setThirdPlaceEmblemId(r.thirdPlaceEmblemId())
                .setFinalMvpPlayerId(r.finalMvpPlayerId())
                .setFinalMvpPlayerName(r.finalMvpPlayerName() == null ? "" : r.finalMvpPlayerName())
                .setFinalMvpScore(r.finalMvpScore())
                .setChampionHonorBits(r.championHonorBits());
        if (r.championMembersBlob() != null && r.championMembersBlob().length > 0) {
            try {
                MatchTournamentChampionMembersBlob blob = MatchTournamentChampionMembersBlob.parseFrom(r.championMembersBlob());
                for (MatchTournamentMemberRow mr : blob.getMembersList()) {
                    TournamentMemberRole role = TournamentMemberRole.forNumber(mr.getRole());
                    b.addChampionMembers(TournamentMemberView.newBuilder()
                            .setPlayerId(mr.getPlayerId())
                            .setPlayerName(mr.getPlayerName())
                            .setZoneId(mr.getZoneId())
                            .setRole(role == null ? TournamentMemberRole.TOURNAMENT_MEMBER_ROLE_UNSPECIFIED : role)
                            .setStarterSlot(mr.getStarterSlot())
                            .setJoinedMs(mr.getJoinedMs())
                            .setClassId(mr.getClassId())
                            .setGender(mr.getGender())
                            .setAppearanceId(mr.getAppearanceId())
                            .build());
                }
            } catch (InvalidProtocolBufferException ignored) {
                // 保持防崩，摘要仍可正常展示
            }
        }
        return b.build();
    }

    private static TournamentSquadView toSquadView(SquadRow r, List<TournamentMemberView> members) {
        TournamentSquadState state = TournamentSquadState.forNumber(r.state());
        return TournamentSquadView.newBuilder()
                .setSquadId(r.squadId())
                .setSeasonId(r.seasonId())
                .setName(r.name())
                .setCaptainPlayerId(r.captainPlayerId())
                .setState(state == null ? TournamentSquadState.TOURNAMENT_SQUAD_STATE_UNSPECIFIED : state)
                .setMemberCount(r.memberCount())
                .setQualifierPoints(r.qualifierPoints())
                .setQualifierPlayed(r.qualifierPlayed())
                .setQualifierWins(r.qualifierWins())
                .setQualifierLosses(r.qualifierLosses())
                .setQualifierDraws(r.qualifierDraws())
                .setSeedRank(r.seedRank())
                .setFinalRank(r.finalRank())
                .setCheerCount(r.cheerCount())
                .setRegisteredMs(r.registeredMs())
                .setEmblemId(emblemOrDefault(r.emblemId()))
                .setHonorBits(r.honorBits())
                .addAllMembers(members)
                .build();
    }

    private TournamentMatchView toMatchView(MatchRow m, Map<Long, SquadRow> squads, List<Long> gameBattleIds) {
        TournamentMatchPhase phase = TournamentMatchPhase.forNumber(m.phase());
        TournamentMatchState state = TournamentMatchState.forNumber(m.state());
        eBattleOutcome outcome = eBattleOutcome.forNumber(m.outcome());
        TournamentLeylineElement curLeyline = TournamentLeylineElement.forNumber(m.currentLeylineElement());
        TournamentLeylineElement bannedA = TournamentLeylineElement.forNumber(m.bannedLeylineA());
        TournamentLeylineElement bannedB = TournamentLeylineElement.forNumber(m.bannedLeylineB());
        List<Long> battleIds = gameBattleIds == null ? List.of() : gameBattleIds;
        boolean publicReplay = m.phase() == TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_KNOCKOUT_VALUE
                && tables.rule().getPublicKnockoutReplay()
                && (m.state() == TournamentMatchState.TOURNAMENT_MATCH_STATE_FINISHED_VALUE || m.winsA() > 0 || m.winsB() > 0)
                && (m.battleId() != 0L || !battleIds.isEmpty());
        TournamentMatchView.Builder b = TournamentMatchView.newBuilder()
                .setMatchId(m.matchId())
                .setSeasonId(m.seasonId())
                .setPhase(phase == null ? TournamentMatchPhase.TOURNAMENT_MATCH_PHASE_UNSPECIFIED : phase)
                .setRoundIndex(m.roundIndex())
                .setSlotIndex(m.slotIndex())
                .setSquadAId(m.squadAId())
                .setSquadBId(m.squadBId())
                .setReadyA(m.readyA())
                .setReadyB(m.readyB())
                .setState(state == null ? TournamentMatchState.TOURNAMENT_MATCH_STATE_UNSPECIFIED : state)
                .setBattleId(m.battleId())
                .setWinnerSquadId(m.winnerSquadId())
                .setOutcome(outcome == null ? eBattleOutcome.BATTLE_OUTCOME_ONGOING : outcome)
                .setFeaturedSpectate(m.featuredSpectate())
                .setScheduledStartMs(m.scheduledStartMs())
                .setDeadlineMs(m.deadlineMs())
                .setFinishedMs(m.finishedMs())
                .setBestOf(Math.max(1, m.bestOf()))
                .setCurrentGameIndex(Math.max(1, m.currentGameIndex()))
                .setWinsA(m.winsA())
                .setWinsB(m.winsB())
                .setCurrentLeylineElement(curLeyline == null ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED : curLeyline)
                .addAllLeylineSequence(TournamentRules.decodeLeylineSequence(m.leylineSequence()))
                .setIntermissionDeadlineMs(m.intermissionDeadlineMs())
                .setPublicReplayAvailable(publicReplay)
                .addAllGameBattleIds(battleIds)
                .setIsThirdPlace(m.isThirdPlace())
                .setMvpPlayerId(m.mvpPlayerId())
                .setMvpPlayerName(m.mvpPlayerName() == null ? "" : m.mvpPlayerName())
                .setMvpSquadId(m.mvpSquadId())
                .setMvpScore(m.mvpScore())
                .setMvpDamage(m.mvpDamage())
                .setMvpHealing(m.mvpHealing())
                .setMvpControls(m.mvpControls())
                .setBannedLeylineA(bannedA == null ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED : bannedA)
                .setBannedLeylineB(bannedB == null ? TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED : bannedB);
        SquadRow a = squads.get(m.squadAId());
        if (a != null) {
            b.setSquadAName(a.name()).setSquadASeed(a.seedRank()).setSquadAEmblemId(emblemOrDefault(a.emblemId()));
        }
        SquadRow sqB = squads.get(m.squadBId());
        if (sqB != null) {
            b.setSquadBName(sqB.name()).setSquadBSeed(sqB.seedRank()).setSquadBEmblemId(emblemOrDefault(sqB.emblemId()));
        }
        return b.build();
    }

    private static SeasonRow scanSeason(ResultSet rs) throws SQLException {
        byte[] blob = rs.getBytes("champion_members_blob");
        String thirdName = rs.getString("third_place_squad_name");
        String finalMvpName = rs.getString("final_mvp_player_name");
        return new SeasonRow(
                rs.getInt("season_id"), rs.getString("season_key"), rs.getString("season_name"),
                rs.getInt("rule_version"), rs.getInt("stage"),
                rs.getLong("registration_start_ms"), rs.getLong("qualifier_start_ms"),
                rs.getLong("knockout_start_ms"), rs.getLong("settle_start_ms"), rs.getLong("end_ms"),
                rs.getInt("battle_team_size"), rs.getInt("roster_max_size"), rs.getInt("knockout_team_count"),
                rs.getLong("champion_squad_id"), rs.getString("champion_squad_name"),
                rs.getString("champion_member_summary"), rs.getLong("settled_ms"), rs.getLong("updated_ms"),
                blob == null ? new byte[0] : blob, rs.getLong("champion_salute_count"),
                rs.getInt("champion_emblem_id"), rs.getLong("third_place_squad_id"),
                thirdName == null ? "" : thirdName, rs.getInt("third_place_emblem_id"),
                rs.getLong("final_mvp_player_id"), finalMvpName == null ? "" : finalMvpName,
                rs.getInt("final_mvp_score"), rs.getInt("champion_honor_bits"));
    }

    private static SquadRow scanSquad(ResultSet rs) throws SQLException {
        return new SquadRow(
                rs.getLong("squad_id"), rs.getInt("season_id"), rs.getString("name"), rs.getString("name_norm"),
                rs.getLong("captain_player_id"), rs.getInt("state"), rs.getInt("member_count"),
                rs.getInt("qualifier_points"), rs.getInt("qualifier_played"),
                rs.getInt("qualifier_wins"), rs.getInt("qualifier_losses"), rs.getInt("qualifier_draws"),
                rs.getInt("seed_rank"), rs.getInt("final_rank"), rs.getInt("cheer_count"),
                rs.getLong("registered_ms"), rs.getLong("updated_ms"),
                emblemOrDefault(rs.getInt("emblem_id")), rs.getInt("honor_bits"));
    }

    private static MatchRow scanMatch(ResultSet rs) throws SQLException {
        byte[] la = rs.getBytes("lineup_a");
        byte[] lb = rs.getBytes("lineup_b");
        String leySeq = rs.getString("leyline_sequence");
        String mvpName = rs.getString("mvp_player_name");
        return new MatchRow(
                rs.getLong("match_id"), rs.getInt("season_id"), rs.getInt("phase"),
                rs.getInt("round_index"), rs.getInt("slot_index"),
                rs.getLong("squad_a_id"), rs.getLong("squad_b_id"),
                la == null ? new byte[0] : la, lb == null ? new byte[0] : lb,
                rs.getBoolean("ready_a"), rs.getBoolean("ready_b"), rs.getInt("state"),
                rs.getLong("battle_id"), rs.getLong("winner_squad_id"), rs.getInt("outcome"),
                rs.getBoolean("featured_spectate"), rs.getLong("scheduled_start_ms"),
                rs.getLong("deadline_ms"), rs.getLong("finished_ms"), rs.getLong("updated_ms"),
                rs.getInt("best_of"), rs.getInt("current_game_index"),
                rs.getInt("wins_a"), rs.getInt("wins_b"),
                rs.getInt("current_leyline_element"), leySeq == null ? "" : leySeq,
                rs.getLong("intermission_deadline_ms"),
                rs.getBoolean("is_third_place"), rs.getLong("mvp_player_id"),
                mvpName == null ? "" : mvpName, rs.getLong("mvp_squad_id"),
                rs.getInt("mvp_score"), rs.getLong("mvp_damage"), rs.getLong("mvp_healing"),
                rs.getInt("mvp_controls"),
                rs.getInt("banned_leyline_a"), rs.getInt("banned_leyline_b"));
    }

    private static TournamentPlayerRewardView scanRewardView(ResultSet rs) throws SQLException {
        TournamentRewardTierKind kind = TournamentRewardTierKind.forNumber(rs.getInt("tier_kind"));
        TournamentRewardDeliveryState ds = TournamentRewardDeliveryState.forNumber(rs.getInt("delivery_state"));
        TournamentPlayerRewardView.Builder b = TournamentPlayerRewardView.newBuilder()
                .setRewardId(rs.getLong("reward_id"))
                .setSeasonId(rs.getInt("season_id"))
                .setTierKind(kind == null ? TournamentRewardTierKind.TOURNAMENT_REWARD_TIER_UNSPECIFIED : kind)
                .setTierId(rs.getInt("tier_id"))
                .setFinalRank(rs.getInt("final_rank"))
                .setGoldAmount(rs.getLong("gold_amount"))
                .setTitleCode(rs.getString("title_code"))
                .setBadgeCode(rs.getString("badge_code"))
                .setDeliveryState(ds == null ? TournamentRewardDeliveryState.TOURNAMENT_REWARD_DELIVERY_UNSPECIFIED : ds)
                .setMailId(rs.getLong("mail_id"))
                .setClaimedMs(rs.getLong("claimed_ms"));
        byte[] rawAtt = rs.getBytes("attachments");
        if (rawAtt != null && rawAtt.length > 0) {
            try {
                MailAttachments atts = MailAttachments.parseFrom(rawAtt);
                for (MailItem it : atts.getItemsList()) {
                    if (it.getConfigId() != 0 && it.getCount() != 0) {
                        b.addItems(TournamentRewardItem.newBuilder()
                                .setItemId(it.getConfigId())
                                .setCount(it.getCount())
                                .build());
                    }
                }
            } catch (InvalidProtocolBufferException ignored) {
                // 保持防崩，明细仍返回其他字段
            }
        }
        return b.build();
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> T inTx(SqlWork<T> work) throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            try {
                T out = work.run(c);
                c.commit();
                return out;
            } catch (RuntimeException | SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException suppressed) {
                    e.addSuppressed(suppressed);
                }
                throw e;
            }
        }
    }
}
