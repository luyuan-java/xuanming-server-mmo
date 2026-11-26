package com.game.match.jianghu;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.proto.common.base.CurrencyType;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.JianghuDuelBattleContext;
import com.game.proto.TipInfoMessage;
import com.game.proto.chat.PublishSystemWorldNoticeRequest;
import com.game.proto.chat.SystemWorldNoticeTemplate;
import com.game.proto.eBattleOutcome;
import com.game.proto.mail.MailAttachments;
import com.game.proto.mail.MailCurrency;
import com.game.proto.mail.SendMailRequest;
import com.game.proto.match.ConfirmJianghuDuelMemberRequest;
import com.game.proto.match.ConfirmJianghuDuelMemberResponse;
import com.game.proto.match.CreateJianghuDuelInviteRequest;
import com.game.proto.match.CreateJianghuDuelInviteResponse;
import com.game.proto.match.DismissJianghuGrudgeRequest;
import com.game.proto.match.DismissJianghuGrudgeResponse;
import com.game.proto.match.JianghuDuelInviteState;
import com.game.proto.match.JianghuDuelInviteView;
import com.game.proto.match.JianghuDuelMemberView;
import com.game.proto.match.JianghuGrudgeBattleLogEntry;
import com.game.proto.match.JianghuGrudgeEntry;
import com.game.proto.match.JianghuWritTier;
import com.game.proto.match.ListJianghuGrudgesRequest;
import com.game.proto.match.ListJianghuGrudgesResponse;
import com.game.proto.match.MatchMode;
import com.game.proto.match.RespondJianghuDuelInviteRequest;
import com.game.proto.match.RespondJianghuDuelInviteResponse;
import com.game.table.CommonErrorTip;
import com.game.table.JianghuErrorTip;
import com.game.table.JianghuRuleTable;
import com.game.table.MatchErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 江湖恩怨与天墉生死状一期 match 侧领域服务（Java 双版对账，行为与 Go {@code go/match/internal/logic/jianghu_duel_logic.go} 严格一致）。
 *
 * <p>核心契约：
 * <ul>
 *   <li>1V1 单人直发目标确认；2-5V2-5 两阶段全员 30 秒确认，任意队员拒绝/超时/变动整单 0 扣费作废；</li>
 *   <li>严守拉黑拦截、闭关免战开关（全员互为好友可绕过）、同目标 5 分钟冷却与单日 20 次未成局上限（成局返还次数）；</li>
 *   <li>战果不触碰任何排位 Elo，按 {@code (player_id, owner_revision, peer_player_id)} 隔离个人恩怨录（至多 50 宿敌 × 20 战报），
 *       支持宿敌 ≥3 连胜 / 终结连胜高光全服播报与单向一笔勾销（再战自动重现）；</li>
 *   <li>异常作废 / 0 回合局普通战书 500 银两（{@link CurrencyType#CURRENCY_TYPE_GOLD} = 0）严格按 {@code duel_invite_id} 单次幂等邮件退还。</li>
 * </ul>
 */
public final class JianghuDuelService {

    public static final int MAX_TEAM_SIZE = 5;
    public static final int MAX_RIVALS = 50;
    public static final int MAX_LOGS_PER_RIVAL = 20;

    public record SocialRelationCheck(boolean hasAnyBlock, boolean allAreFriends) {
    }

    public record EligibilityCheck(boolean selfRefusesStrangers, boolean targetRefusesStrangers, boolean payerSufficientFee) {
    }

    public record GuildSummary(long guildId, String guildName) {
        public static final GuildSummary NONE = new GuildSummary(0L, "");
    }

    /** 跨服务只读与副作用港口（遵循 Actor/边界隔离，绝不共享可变状态）。 */
    public interface Ports {
        default Optional<List<Long>> authoritativeTeamOf(long captainPlayerId) {
            return Optional.empty();
        }

        default boolean isPlayerOnline(long playerId) {
            return true;
        }

        default boolean isPlayerInBattle(long playerId) {
            return false;
        }

        default SocialRelationCheck checkSocialRelations(List<Long> sideA, List<Long> sideB) {
            return new SocialRelationCheck(false, false);
        }

        default EligibilityCheck checkSceneEligibility(List<Long> sideA, List<Long> sideB, long payerId, JianghuWritTier tier) {
            return new EligibilityCheck(false, false, true);
        }

        default String displayName(long playerId) {
            return "道友" + Long.toUnsignedString(playerId);
        }

        default GuildSummary pureGuildOf(List<Long> members) {
            return GuildSummary.NONE;
        }

        default long launchBattle(JianghuDuelInviteView invite, JianghuDuelBattleContext duelContext) {
            return 0L;
        }

        default void publishWorldNotice(PublishSystemWorldNoticeRequest request) {
        }

        default void sendSilverRefundMail(SendMailRequest request) {
        }
    }

    private record RuleSnapshot(
            boolean enabled,
            long silverWritCost,
            int goldWritItemId,
            long inviteTtlMs,
            long teamConfirmMs,
            long sameTargetCooldownMs,
            int dailyUnmatchedLimit,
            int duelRoundLimit,
            int streakHighlightThreshold) {
        static RuleSnapshot from(JianghuRuleTable row) {
            if (row == null) {
                return new RuleSnapshot(true, 500L, 3415, 60_000L, 30_000L, 300_000L, 20, 30, 3);
            }
            return new RuleSnapshot(
                    row.getEnabled() != 0,
                    row.getSilverWritCost() > 0 ? row.getSilverWritCost() : 500L,
                    row.getGoldWritItemId() > 0 ? row.getGoldWritItemId() : 3415,
                    row.getInviteTtlSec() > 0 ? row.getInviteTtlSec() * 1000L : 60_000L,
                    row.getTeamConfirmSec() > 0 ? row.getTeamConfirmSec() * 1000L : 30_000L,
                    row.getSameTargetCooldownSec() > 0 ? row.getSameTargetCooldownSec() * 1000L : 300_000L,
                    row.getDailyUnmatchedInviteLimit() > 0 ? row.getDailyUnmatchedInviteLimit() : 20,
                    row.getDuelRoundLimit() > 0 ? row.getDuelRoundLimit() : 30,
                    row.getStreakHighlightThreshold() > 0 ? row.getStreakHighlightThreshold() : 3);
        }
    }

    private static final class InviteRecord {
        long duelInviteId;
        JianghuWritTier writTier;
        int teamSize;
        JianghuDuelInviteState state;
        long challengerCaptainId;
        long targetCaptainId;
        long sideAGuildId;
        String sideAGuildName;
        long sideBGuildId;
        String sideBGuildName;
        final List<JianghuDuelMemberView.Builder> challengers = new ArrayList<>();
        final List<JianghuDuelMemberView.Builder> targets = new ArrayList<>();
        long createdAtMs;
        long expiresAtMs;
        long confirmDeadlineMs;
        long battleId;

        JianghuDuelInviteView toView(long nowMs) {
            JianghuDuelInviteView.Builder b = JianghuDuelInviteView.newBuilder()
                    .setDuelInviteId(duelInviteId)
                    .setWritTier(writTier)
                    .setTeamSize(teamSize)
                    .setState(state)
                    .setChallengerCaptainId(challengerCaptainId)
                    .setChallengerCaptainName(captainName(challengers, challengerCaptainId))
                    .setTargetCaptainId(targetCaptainId)
                    .setTargetCaptainName(captainName(targets, targetCaptainId))
                    .setSideAGuildId(sideAGuildId)
                    .setSideAGuildName(sideAGuildName == null ? "" : sideAGuildName)
                    .setSideBGuildId(sideBGuildId)
                    .setSideBGuildName(sideBGuildName == null ? "" : sideBGuildName)
                    .setCreatedAtMs(createdAtMs)
                    .setExpiresAtMs(expiresAtMs)
                    .setConfirmDeadlineMs(confirmDeadlineMs)
                    .setBattleId(battleId)
                    .setServerTimeMs(nowMs);
            for (JianghuDuelMemberView.Builder m : challengers) {
                b.addChallengerMembers(m.build());
            }
            for (JianghuDuelMemberView.Builder m : targets) {
                b.addTargetMembers(m.build());
            }
            return b.build();
        }

        private static String captainName(List<JianghuDuelMemberView.Builder> list, long captainId) {
            for (JianghuDuelMemberView.Builder m : list) {
                if (m.getPlayerId() == captainId && !m.getName().isEmpty()) {
                    return m.getName();
                }
            }
            return "道友" + Long.toUnsignedString(captainId);
        }
    }

    private record GrudgeKey(long playerId, long ownerRevision, long peerPlayerId) {
    }

    private static final class GrudgeState {
        final long peerPlayerId;
        String peerName;
        int wins;
        int losses;
        int draws;
        int winStreak;
        int loseStreak;
        long lastBattleMs;
        boolean dismissed;
        final List<JianghuGrudgeBattleLogEntry> logs = new ArrayList<>();

        GrudgeState(long peerPlayerId, String peerName) {
            this.peerPlayerId = peerPlayerId;
            this.peerName = peerName;
        }
    }

    private record CooldownKey(long challengerId, long targetId) {
    }

    private static final class DailyCounter {
        int dayKey;
        int unmatchedCount;
    }

    private final Supplier<JianghuRuleTable> ruleSupplier;
    private final Ports ports;
    private final LongSupplier clockMs;
    private final LongSupplier idGenerator;
    private final Object stateLock = new Object();
    private final Map<Long, InviteRecord> invites = new HashMap<>();
    private final Map<CooldownKey, Long> sameTargetCooldownUntil = new HashMap<>();
    private final Map<Long, DailyCounter> dailyCounters = new HashMap<>();
    private final Map<GrudgeKey, GrudgeState> grudges = new HashMap<>();
    private final Set<Long> appliedBattleIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> refundedInviteIds = ConcurrentHashMap.newKeySet();

    public JianghuDuelService(Supplier<JianghuRuleTable> ruleSupplier, Ports ports,
                              LongSupplier clockMs, LongSupplier idGenerator) {
        this.ruleSupplier = Objects.requireNonNull(ruleSupplier, "ruleSupplier");
        this.ports = Objects.requireNonNull(ports, "ports");
        this.clockMs = Objects.requireNonNull(clockMs, "clockMs");
        this.idGenerator = idGenerator != null ? idGenerator : new AtomicLong(1000L)::incrementAndGet;
    }

    /** UTC+8 每日 05:00 游戏日键 YYYYMMDD（与 Go {@code JianghuGameDayKey} 对齐）。 */
    public static int gameDayKey(long nowMs) {
        ZonedDateTime dt = Instant.ofEpochMilli(nowMs).atOffset(ZoneOffset.ofHours(8)).minusHours(5).toZonedDateTime();
        return dt.getYear() * 10000 + dt.getMonthValue() * 100 + dt.getDayOfMonth();
    }

    public CreateJianghuDuelInviteResponse createInvite(SessionContext session, CreateJianghuDuelInviteRequest req) {
        RuleSnapshot rule = RuleSnapshot.from(ruleSupplier.get());
        if (!rule.enabled()) {
            return CreateJianghuDuelInviteResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuDuelDisabled_VALUE)).build();
        }
        long callerId = session != null ? session.getPlayerId() : 0L;
        if (callerId == 0L) {
            return CreateJianghuDuelInviteResponse.newBuilder()
                    .setErrorMessage(tip(CommonErrorTip.common_error.kServiceUnavailable_VALUE)).build();
        }
        long targetId = req.getTargetPlayerId();
        if (targetId == 0L || targetId == callerId) {
            return CreateJianghuDuelInviteResponse.newBuilder()
                    .setErrorMessage(tip(MatchErrorTip.match_error.kMatchChallengeSelf_VALUE)).build();
        }
        JianghuWritTier tier = req.getWritTier();
        if (tier != JianghuWritTier.JIANGHU_WRIT_TIER_SILVER && tier != JianghuWritTier.JIANGHU_WRIT_TIER_GOLD_ITEM) {
            return CreateJianghuDuelInviteResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInsufficientWritOrSilver_VALUE)).build();
        }

        List<Long> sideA = resolveSide(callerId, req.getChallengerMemberIdsList());
        List<Long> sideB = resolveSide(targetId, req.getTargetMemberIdsList());
        if (sideA == null || sideB == null || sideA.size() != sideB.size() || sideA.isEmpty() || sideA.size() > MAX_TEAM_SIZE) {
            return CreateJianghuDuelInviteResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuTeamSizeMismatch_VALUE)).build();
        }
        Set<Long> overlapCheck = new HashSet<>(sideA);
        for (long b : sideB) {
            if (!overlapCheck.add(b)) {
                return CreateJianghuDuelInviteResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuTeamSizeMismatch_VALUE)).build();
            }
        }
        for (long pid : overlapCheck) {
            if (ports.isPlayerInBattle(pid)) {
                return CreateJianghuDuelInviteResponse.newBuilder()
                        .setErrorMessage(tip(MatchErrorTip.match_error.kMatchChallengeTargetBusy_VALUE)).build();
            }
            if (!ports.isPlayerOnline(pid)) {
                return CreateJianghuDuelInviteResponse.newBuilder()
                        .setErrorMessage(tip(MatchErrorTip.match_error.kMatchChallengeTargetOffline_VALUE)).build();
            }
        }

        SocialRelationCheck rel = ports.checkSocialRelations(sideA, sideB);
        if (rel.hasAnyBlock()) {
            return CreateJianghuDuelInviteResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuBlockedByPeer_VALUE)).build();
        }
        EligibilityCheck elig = ports.checkSceneEligibility(sideA, sideB, callerId, tier);
        if (!elig.payerSufficientFee()) {
            return CreateJianghuDuelInviteResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInsufficientWritOrSilver_VALUE)).build();
        }
        if (elig.selfRefusesStrangers() && !rel.allAreFriends()) {
            return CreateJianghuDuelInviteResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuSelfRefusesStrangers_VALUE)).build();
        }
        if (elig.targetRefusesStrangers() && !rel.allAreFriends()) {
            return CreateJianghuDuelInviteResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuTargetRefusesStrangers_VALUE)).build();
        }

        GuildSummary guildA = ports.pureGuildOf(sideA);
        GuildSummary guildB = ports.pureGuildOf(sideB);
        long nowMs = clockMs.getAsLong();

        synchronized (stateLock) {
            CooldownKey cdKey = new CooldownKey(callerId, targetId);
            if (sameTargetCooldownUntil.getOrDefault(cdKey, 0L) > nowMs) {
                return CreateJianghuDuelInviteResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuSameTargetCooldown_VALUE)).build();
            }
            int today = gameDayKey(nowMs);
            DailyCounter dc = dailyCounters.computeIfAbsent(callerId, k -> new DailyCounter());
            if (dc.dayKey == today && dc.unmatchedCount >= rule.dailyUnmatchedLimit()) {
                return CreateJianghuDuelInviteResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuDailyInviteLimitReached_VALUE)).build();
            }
            for (InviteRecord existing : invites.values()) {
                if (existing.targetCaptainId == targetId
                        && (existing.state == JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_PENDING_TARGET
                                || existing.state == JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_TARGET_TEAM)
                        && existing.expiresAtMs > nowMs) {
                    return CreateJianghuDuelInviteResponse.newBuilder()
                            .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInvitePendingOrExpired_VALUE)).build();
                }
            }

            InviteRecord rec = new InviteRecord();
            rec.duelInviteId = idGenerator.getAsLong();
            rec.writTier = tier;
            rec.teamSize = sideA.size();
            rec.challengerCaptainId = callerId;
            rec.targetCaptainId = targetId;
            rec.sideAGuildId = guildA.guildId();
            rec.sideAGuildName = guildA.guildName();
            rec.sideBGuildId = guildB.guildId();
            rec.sideBGuildName = guildB.guildName();
            rec.createdAtMs = nowMs;
            for (long pid : sideA) {
                rec.challengers.add(JianghuDuelMemberView.newBuilder()
                        .setPlayerId(pid).setName(ports.displayName(pid)).setConfirmed(pid == callerId));
            }
            for (long pid : sideB) {
                rec.targets.add(JianghuDuelMemberView.newBuilder()
                        .setPlayerId(pid).setName(ports.displayName(pid)).setConfirmed(false));
            }
            if (rec.teamSize == 1) {
                rec.state = JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_PENDING_TARGET;
                rec.expiresAtMs = nowMs + rule.inviteTtlMs();
            } else {
                rec.state = JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_CHALLENGER_TEAM;
                rec.confirmDeadlineMs = nowMs + rule.teamConfirmMs();
                rec.expiresAtMs = rec.confirmDeadlineMs + rule.inviteTtlMs();
            }
            invites.put(rec.duelInviteId, rec);
            sameTargetCooldownUntil.put(cdKey, nowMs + rule.sameTargetCooldownMs());
            if (dc.dayKey == today) {
                dc.unmatchedCount++;
            } else {
                dc.dayKey = today;
                dc.unmatchedCount = 1;
            }
            return CreateJianghuDuelInviteResponse.newBuilder().setInvite(rec.toView(nowMs)).build();
        }
    }

    public ConfirmJianghuDuelMemberResponse confirmMember(SessionContext session, ConfirmJianghuDuelMemberRequest req) {
        RuleSnapshot rule = RuleSnapshot.from(ruleSupplier.get());
        if (!rule.enabled()) {
            return ConfirmJianghuDuelMemberResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuDuelDisabled_VALUE)).build();
        }
        long callerId = session != null ? session.getPlayerId() : 0L;
        long nowMs = clockMs.getAsLong();
        synchronized (stateLock) {
            InviteRecord rec = invites.get(req.getDuelInviteId());
            if (rec == null || (rec.state != JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_CHALLENGER_TEAM
                    && rec.state != JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_TARGET_TEAM)) {
                return ConfirmJianghuDuelMemberResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInvitePendingOrExpired_VALUE)).build();
            }
            if (rec.confirmDeadlineMs > 0 && nowMs > rec.confirmDeadlineMs) {
                rec.state = JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_ABORTED;
                return ConfirmJianghuDuelMemberResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInvitePendingOrExpired_VALUE))
                        .setInvite(rec.toView(nowMs)).build();
            }
            List<JianghuDuelMemberView.Builder> activeSide =
                    rec.state == JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_CHALLENGER_TEAM
                            ? rec.challengers : rec.targets;
            JianghuDuelMemberView.Builder member = null;
            for (JianghuDuelMemberView.Builder m : activeSide) {
                if (m.getPlayerId() == callerId) {
                    member = m;
                    break;
                }
            }
            if (member == null) {
                return ConfirmJianghuDuelMemberResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuNotInviteParticipant_VALUE)).build();
            }
            if (!req.getAgree()) {
                rec.state = JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_ABORTED;
                return ConfirmJianghuDuelMemberResponse.newBuilder().setInvite(rec.toView(nowMs)).build();
            }
            member.setConfirmed(true);
            boolean allConfirmed = activeSide.stream().allMatch(JianghuDuelMemberView.Builder::getConfirmed);
            if (!allConfirmed) {
                return ConfirmJianghuDuelMemberResponse.newBuilder().setInvite(rec.toView(nowMs)).build();
            }
            if (rec.state == JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_CHALLENGER_TEAM) {
                rec.state = JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_PENDING_TARGET;
                rec.confirmDeadlineMs = 0L;
                rec.expiresAtMs = nowMs + rule.inviteTtlMs();
                return ConfirmJianghuDuelMemberResponse.newBuilder().setInvite(rec.toView(nowMs)).build();
            }
            return finalizeMatchLocked(rec, rule, nowMs);
        }
    }

    public RespondJianghuDuelInviteResponse respondInvite(SessionContext session, RespondJianghuDuelInviteRequest req) {
        RuleSnapshot rule = RuleSnapshot.from(ruleSupplier.get());
        if (!rule.enabled()) {
            return RespondJianghuDuelInviteResponse.newBuilder()
                    .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuDuelDisabled_VALUE)).build();
        }
        long callerId = session != null ? session.getPlayerId() : 0L;
        long nowMs = clockMs.getAsLong();
        synchronized (stateLock) {
            InviteRecord rec = invites.get(req.getDuelInviteId());
            if (rec == null || rec.state != JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_PENDING_TARGET) {
                return RespondJianghuDuelInviteResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInvitePendingOrExpired_VALUE)).build();
            }
            if (rec.targetCaptainId != callerId) {
                return RespondJianghuDuelInviteResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuNotInviteParticipant_VALUE)).build();
            }
            if (rec.expiresAtMs > 0 && nowMs > rec.expiresAtMs) {
                rec.state = JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_ABORTED;
                return RespondJianghuDuelInviteResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuInvitePendingOrExpired_VALUE))
                        .setInvite(rec.toView(nowMs)).build();
            }
            if (!req.getAccept()) {
                rec.state = JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_DECLINED;
                return RespondJianghuDuelInviteResponse.newBuilder().setInvite(rec.toView(nowMs)).build();
            }
            for (JianghuDuelMemberView.Builder m : rec.targets) {
                if (m.getPlayerId() == callerId) {
                    m.setConfirmed(true);
                }
            }
            if (rec.teamSize > 1) {
                rec.state = JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_TARGET_TEAM;
                rec.confirmDeadlineMs = nowMs + rule.teamConfirmMs();
                rec.expiresAtMs = rec.confirmDeadlineMs;
                return RespondJianghuDuelInviteResponse.newBuilder().setInvite(rec.toView(nowMs)).build();
            }
            ConfirmJianghuDuelMemberResponse matched = finalizeMatchLocked(rec, rule, nowMs);
            RespondJianghuDuelInviteResponse.Builder out = RespondJianghuDuelInviteResponse.newBuilder();
            if (matched.hasErrorMessage()) {
                out.setErrorMessage(matched.getErrorMessage());
            }
            if (matched.hasInvite()) {
                out.setInvite(matched.getInvite());
            }
            return out.build();
        }
    }

    public ListJianghuGrudgesResponse listGrudges(SessionContext session) {
        long playerId = session != null ? session.getPlayerId() : 0L;
        long ownerRev = session != null ? session.getOwnerRevision() : 0L;
        long nowMs = clockMs.getAsLong();
        ListJianghuGrudgesResponse.Builder out = ListJianghuGrudgesResponse.newBuilder().setServerTimeMs(nowMs);
        synchronized (stateLock) {
            List<GrudgeState> mine = new ArrayList<>();
            for (Map.Entry<GrudgeKey, GrudgeState> e : grudges.entrySet()) {
                if (e.getKey().playerId() == playerId && e.getKey().ownerRevision() == ownerRev && !e.getValue().dismissed) {
                    mine.add(e.getValue());
                }
            }
            mine.sort(Comparator.comparingLong((GrudgeState g) -> g.lastBattleMs).reversed());
            for (GrudgeState g : mine) {
                out.addGrudges(JianghuGrudgeEntry.newBuilder()
                        .setPeerPlayerId(g.peerPlayerId)
                        .setPeerName(g.peerName)
                        .setWins(g.wins)
                        .setLosses(g.losses)
                        .setDraws(g.draws)
                        .setWinStreak(g.winStreak)
                        .setLoseStreak(g.loseStreak)
                        .setLastBattleMs(g.lastBattleMs)
                        .setPeerOnline(ports.isPlayerOnline(g.peerPlayerId))
                        .addAllRecentLogs(g.logs)
                        .build());
            }
            for (InviteRecord r : invites.values()) {
                if ((r.state == JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_CHALLENGER_TEAM
                        || r.state == JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_PENDING_TARGET
                        || r.state == JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_AWAITING_TARGET_TEAM)
                        && r.expiresAtMs > nowMs && containsPlayer(r, playerId)) {
                    out.addActiveInvites(r.toView(nowMs));
                }
            }
        }
        return out.build();
    }

    public DismissJianghuGrudgeResponse dismissGrudge(SessionContext session, DismissJianghuGrudgeRequest req) {
        long playerId = session != null ? session.getPlayerId() : 0L;
        long ownerRev = session != null ? session.getOwnerRevision() : 0L;
        synchronized (stateLock) {
            GrudgeState state = grudges.get(new GrudgeKey(playerId, ownerRev, req.getPeerPlayerId()));
            if (state == null || state.dismissed) {
                return DismissJianghuGrudgeResponse.newBuilder()
                        .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuGrudgeNotFound_VALUE)).build();
            }
            state.dismissed = true;
            return DismissJianghuGrudgeResponse.getDefaultInstance();
        }
    }

    /** 消费天墉生死状终局事件（绝不触碰排位 Elo）：VOID/0 回合幂等退 500 银两；正常完赛更新双方队长恩怨录并按阈值播报高光。 */
    public boolean applyBattleResult(BattleResultEvent event, Map<Long, Long> ownerRevs) {
        if (event == null || event.getBattleId() == 0L
                || event.getMatchMode() != MatchMode.MATCH_MODE_JIANGHU_DUEL_VALUE
                || !event.hasJianghuDuelContext()) {
            return false;
        }
        JianghuDuelBattleContext ctx = event.getJianghuDuelContext();
        RuleSnapshot rule = RuleSnapshot.from(ruleSupplier.get());
        if (event.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_VOID || event.getTotalRounds() == 0) {
            if (ctx.getWritTier() == JianghuWritTier.JIANGHU_WRIT_TIER_SILVER_VALUE) {
                refundSilverOnce(ctx.getDuelInviteId(), ctx.getChallengerCaptainId(), rule.silverWritCost());
            }
            return false;
        }
        if (!appliedBattleIds.add(event.getBattleId())) {
            return false;
        }
        long capA = ctx.getChallengerCaptainId();
        long capB = ctx.getTargetCaptainId();
        long revA = ownerRevs != null ? ownerRevs.getOrDefault(capA, 0L) : 0L;
        long revB = ownerRevs != null ? ownerRevs.getOrDefault(capB, 0L) : 0L;
        int outA;
        int outB;
        boolean draw = false;
        String winnerName = ctx.getChallengerCaptainName();
        String loserName = ctx.getTargetCaptainName();
        switch (event.getOutcome()) {
            case BATTLE_OUTCOME_SIDE_A_WIN -> {
                outA = 1;
                outB = 2;
            }
            case BATTLE_OUTCOME_SIDE_B_WIN -> {
                outA = 2;
                outB = 1;
                winnerName = ctx.getTargetCaptainName();
                loserName = ctx.getChallengerCaptainName();
            }
            case BATTLE_OUTCOME_DRAW -> {
                outA = 3;
                outB = 3;
                draw = true;
            }
            default -> {
                return false;
            }
        }
        int winnerNewStreak = 0;
        int loserPrevStreak = 0;
        long finishedMs = event.getFinishedAtMs() != 0L ? event.getFinishedAtMs() : clockMs.getAsLong();
        synchronized (stateLock) {
            int[] resA = updateSideGrudgeLocked(capA, revA, capB, ctx.getTargetCaptainName(), outA, event, ctx, finishedMs);
            int[] resB = updateSideGrudgeLocked(capB, revB, capA, ctx.getChallengerCaptainName(), outB, event, ctx, finishedMs);
            if (!draw) {
                winnerNewStreak = outA == 1 ? resA[0] : resB[0];
                loserPrevStreak = outA == 2 ? resA[1] : resB[1];
            }
        }
        if (draw) {
            ports.publishWorldNotice(PublishSystemWorldNoticeRequest.newBuilder()
                    .setNoticeId("jh_duel:" + Long.toUnsignedString(event.getBattleId()) + ":end")
                    .setTemplateId(SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_DRAW)
                    .setBattleId(event.getBattleId())
                    .setActorAName(ctx.getChallengerCaptainName())
                    .setActorBName(ctx.getTargetCaptainName())
                    .setTotalRounds(event.getTotalRounds())
                    .setTeamSize(ctx.getTeamSize())
                    .build());
            return true;
        }
        SystemWorldNoticeTemplate endTmpl = ctx.getWritTier() == JianghuWritTier.JIANGHU_WRIT_TIER_GOLD_ITEM_VALUE
                ? SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_WIN_GOLD
                : SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_WIN_SILVER;
        ports.publishWorldNotice(PublishSystemWorldNoticeRequest.newBuilder()
                .setNoticeId("jh_duel:" + Long.toUnsignedString(event.getBattleId()) + ":end")
                .setTemplateId(endTmpl)
                .setBattleId(event.getBattleId())
                .setActorAName(winnerName)
                .setActorBName(loserName)
                .setTotalRounds(event.getTotalRounds())
                .setTeamSize(ctx.getTeamSize())
                .build());
        if (winnerNewStreak >= rule.streakHighlightThreshold()) {
            ports.publishWorldNotice(PublishSystemWorldNoticeRequest.newBuilder()
                    .setNoticeId("jh_duel:" + Long.toUnsignedString(event.getBattleId()) + ":streak")
                    .setTemplateId(SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_STREAK_MILESTONE)
                    .setBattleId(event.getBattleId())
                    .setActorAName(winnerName)
                    .setActorBName(loserName)
                    .setStreakCount(winnerNewStreak)
                    .setTeamSize(ctx.getTeamSize())
                    .build());
        }
        if (loserPrevStreak >= rule.streakHighlightThreshold()) {
            ports.publishWorldNotice(PublishSystemWorldNoticeRequest.newBuilder()
                    .setNoticeId("jh_duel:" + Long.toUnsignedString(event.getBattleId()) + ":streak_break")
                    .setTemplateId(SystemWorldNoticeTemplate.SYSTEM_WORLD_NOTICE_JIANGHU_DUEL_STREAK_BROKEN)
                    .setBattleId(event.getBattleId())
                    .setActorAName(winnerName)
                    .setActorBName(loserName)
                    .setStreakCount(loserPrevStreak)
                    .setTeamSize(ctx.getTeamSize())
                    .build());
        }
        return true;
    }

    /** 构造 7 个注册到 {@link com.game.match.dispatch.MatchDispatcher} 的客户端/推送占位处理器。 */
    public List<MatchMethodHandler> clientHandlers() {
        return List.of(
                handler(MatchMethods.CREATE_JIANGHU_DUEL_INVITE,
                        (s, b) -> createInvite(s, CreateJianghuDuelInviteRequest.parseFrom(b)),
                        () -> CreateJianghuDuelInviteResponse.newBuilder()
                                .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuServiceBusy_VALUE)).build()),
                handler(MatchMethods.CONFIRM_JIANGHU_DUEL_MEMBER,
                        (s, b) -> confirmMember(s, ConfirmJianghuDuelMemberRequest.parseFrom(b)),
                        () -> ConfirmJianghuDuelMemberResponse.newBuilder()
                                .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuServiceBusy_VALUE)).build()),
                handler(MatchMethods.RESPOND_JIANGHU_DUEL_INVITE,
                        (s, b) -> respondInvite(s, RespondJianghuDuelInviteRequest.parseFrom(b)),
                        () -> RespondJianghuDuelInviteResponse.newBuilder()
                                .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuServiceBusy_VALUE)).build()),
                handler(MatchMethods.LIST_JIANGHU_GRUDGES,
                        (s, b) -> {
                            ListJianghuGrudgesRequest.parseFrom(b);
                            return listGrudges(s);
                        },
                        () -> ListJianghuGrudgesResponse.newBuilder()
                                .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuServiceBusy_VALUE)).build()),
                handler(MatchMethods.DISMISS_JIANGHU_GRUDGE,
                        (s, b) -> dismissGrudge(s, DismissJianghuGrudgeRequest.parseFrom(b)),
                        () -> DismissJianghuGrudgeResponse.newBuilder()
                                .setErrorMessage(tip(JianghuErrorTip.jianghu_error.kJianghuServiceBusy_VALUE)).build()),
                inlineNoopHandler(MatchMethods.NOTIFY_JIANGHU_DUEL_INVITE),
                inlineNoopHandler(MatchMethods.NOTIFY_JIANGHU_DUEL_STATUS));
    }

    private ConfirmJianghuDuelMemberResponse finalizeMatchLocked(InviteRecord rec, RuleSnapshot rule, long nowMs) {
        JianghuDuelInviteView preView = rec.toView(nowMs);
        JianghuDuelBattleContext duelCtx = JianghuDuelBattleContext.newBuilder()
                .setDuelInviteId(rec.duelInviteId)
                .setWritTier(rec.writTier.getNumber())
                .setChallengerCaptainId(rec.challengerCaptainId)
                .setChallengerCaptainName(preView.getChallengerCaptainName())
                .setTargetCaptainId(rec.targetCaptainId)
                .setTargetCaptainName(preView.getTargetCaptainName())
                .setSideAGuildId(rec.sideAGuildId)
                .setSideAGuildName(rec.sideAGuildName == null ? "" : rec.sideAGuildName)
                .setSideBGuildId(rec.sideBGuildId)
                .setSideBGuildName(rec.sideBGuildName == null ? "" : rec.sideBGuildName)
                .setRoundLimitOverride(rule.duelRoundLimit())
                .setTeamSize(rec.teamSize)
                .build();
        long battleId = ports.launchBattle(preView, duelCtx);
        rec.battleId = battleId;
        rec.state = JianghuDuelInviteState.JIANGHU_DUEL_INVITE_STATE_MATCHED;
        rec.confirmDeadlineMs = 0L;
        DailyCounter dc = dailyCounters.get(rec.challengerCaptainId);
        if (dc != null && dc.dayKey == gameDayKey(nowMs) && dc.unmatchedCount > 0) {
            dc.unmatchedCount--;
        }
        return ConfirmJianghuDuelMemberResponse.newBuilder().setInvite(rec.toView(nowMs)).build();
    }

    private int[] updateSideGrudgeLocked(long playerId, long ownerRev, long peerId, String peerName, int outcomeSelf,
                                         BattleResultEvent event, JianghuDuelBattleContext ctx, long finishedMs) {
        GrudgeKey key = new GrudgeKey(playerId, ownerRev, peerId);
        GrudgeState state = grudges.computeIfAbsent(key, k -> new GrudgeState(peerId, peerName));
        state.peerName = peerName;
        state.lastBattleMs = finishedMs;
        state.dismissed = false;
        int prevWin = state.winStreak;
        switch (outcomeSelf) {
            case 1 -> {
                state.wins++;
                state.winStreak++;
                state.loseStreak = 0;
            }
            case 2 -> {
                state.losses++;
                state.winStreak = 0;
                state.loseStreak++;
            }
            case 3 -> state.draws++;
            default -> { }
        }
        state.logs.add(0, JianghuGrudgeBattleLogEntry.newBuilder()
                .setBattleId(event.getBattleId())
                .setOutcomeForSelf(outcomeSelf)
                .setTotalRounds(event.getTotalRounds())
                .setWritTier(JianghuWritTier.forNumber(ctx.getWritTier()))
                .setTeamSize(ctx.getTeamSize())
                .setFinishedAtMs(finishedMs)
                .build());
        if (state.logs.size() > MAX_LOGS_PER_RIVAL) {
            state.logs.subList(MAX_LOGS_PER_RIVAL, state.logs.size()).clear();
        }
        trimRivalsLocked(playerId, ownerRev);
        return new int[] {state.winStreak, prevWin};
    }

    private void trimRivalsLocked(long playerId, long ownerRev) {
        List<Map.Entry<GrudgeKey, GrudgeState>> list = new ArrayList<>();
        for (Map.Entry<GrudgeKey, GrudgeState> e : grudges.entrySet()) {
            if (e.getKey().playerId() == playerId && e.getKey().ownerRevision() == ownerRev) {
                list.add(e);
            }
        }
        if (list.size() <= MAX_RIVALS) {
            return;
        }
        list.sort(Comparator.comparingLong((Map.Entry<GrudgeKey, GrudgeState> e) -> e.getValue().lastBattleMs).reversed());
        for (int i = MAX_RIVALS; i < list.size(); i++) {
            grudges.remove(list.get(i).getKey());
        }
    }

    private void refundSilverOnce(long duelInviteId, long payerId, long silverCost) {
        if (duelInviteId == 0L || payerId == 0L || silverCost <= 0L) {
            return;
        }
        if (!refundedInviteIds.add(duelInviteId)) {
            return;
        }
        long nowMs = clockMs.getAsLong();
        ports.sendSilverRefundMail(SendMailRequest.newBuilder()
                .setSenderKind(6)
                .setRequestId(duelInviteId)
                .setRecipientPlayerId(payerId)
                .setTitle("天墉生死状战书银两退还")
                .setBody("您发起的天墉生死状未能正常开战或作废，已全额退还普通战书银两，请查收附件。")
                .setAttachments(MailAttachments.newBuilder()
                        .addCurrencies(MailCurrency.newBuilder()
                                .setCurrencyType(CurrencyType.CURRENCY_TYPE_GOLD_VALUE)
                                .setAmount(silverCost)
                                .build())
                        .build())
                .setExpireMs(nowMs + 30L * 24 * 3600 * 1000)
                .build());
    }

    private List<Long> resolveSide(long captainId, List<Long> requested) {
        Optional<List<Long>> authTeam = ports.authoritativeTeamOf(captainId);
        if (authTeam.isEmpty()) {
            if (requested.size() > 1 || (requested.size() == 1 && requested.get(0) != captainId)) {
                return null;
            }
            return List.of(captainId);
        }
        LinkedHashSet<Long> normalized = new LinkedHashSet<>();
        normalized.add(captainId);
        normalized.addAll(authTeam.get());
        if (!requested.isEmpty()) {
            Set<Long> reqSet = new HashSet<>(requested);
            reqSet.add(captainId);
            if (!reqSet.equals(normalized)) {
                return null;
            }
        }
        return new ArrayList<>(normalized);
    }

    private static boolean containsPlayer(InviteRecord rec, long playerId) {
        if (rec.challengerCaptainId == playerId || rec.targetCaptainId == playerId) {
            return true;
        }
        for (JianghuDuelMemberView.Builder m : rec.challengers) {
            if (m.getPlayerId() == playerId) {
                return true;
            }
        }
        for (JianghuDuelMemberView.Builder m : rec.targets) {
            if (m.getPlayerId() == playerId) {
                return true;
            }
        }
        return false;
    }

    private static TipInfoMessage tip(int tipId) {
        return TipInfoMessage.newBuilder().setId(tipId).build();
    }

    @FunctionalInterface
    private interface ProtoAction {
        com.google.protobuf.MessageLite run(SessionContext session, ByteString body) throws InvalidProtocolBufferException;
    }

    private static MatchMethodHandler handler(String method, ProtoAction action,
                                              Supplier<com.google.protobuf.MessageLite> overloadReply) {
        return new MatchMethodHandler() {
            @Override
            public String method() {
                return method;
            }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline) throws InvalidProtocolBufferException {
                return Reply.body(action.run(session, body));
            }

            @Override
            public Reply onOverload() {
                return Reply.body(overloadReply.get());
            }
        };
    }

    private static MatchMethodHandler inlineNoopHandler(String method) {
        return new MatchMethodHandler() {
            @Override
            public String method() {
                return method;
            }

            @Override
            public boolean inline() {
                return true;
            }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline) {
                return Reply.empty();
            }

            @Override
            public Reply onOverload() {
                return Reply.empty();
            }
        };
    }
}
