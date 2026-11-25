package com.game.match.tournament;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.proto.TipInfoMessage;
import com.game.proto.content_guard.CheckTextResponse;
import com.game.proto.content_guard.TextScene;
import com.game.proto.content_guard.TextVerdict;
import com.game.proto.match.CheerTournamentSquadRequest;
import com.game.proto.match.CheerTournamentSquadResponse;
import com.game.proto.match.ClaimTournamentRewardRequest;
import com.game.proto.match.ClaimTournamentRewardResponse;
import com.game.proto.match.CreateTournamentSquadRequest;
import com.game.proto.match.CreateTournamentSquadResponse;
import com.game.proto.match.GetTournamentOverviewRequest;
import com.game.proto.match.GetTournamentOverviewResponse;
import com.game.proto.match.InviteTournamentMemberRequest;
import com.game.proto.match.InviteTournamentMemberResponse;
import com.game.proto.match.LeaveTournamentSquadRequest;
import com.game.proto.match.LeaveTournamentSquadResponse;
import com.game.proto.match.ListTournamentStandingsRequest;
import com.game.proto.match.ListTournamentStandingsResponse;
import com.game.proto.match.RegisterTournamentSquadRequest;
import com.game.proto.match.RegisterTournamentSquadResponse;
import com.game.proto.match.RespondTournamentInviteRequest;
import com.game.proto.match.RespondTournamentInviteResponse;
import com.game.proto.match.StartTournamentMatchRequest;
import com.game.proto.match.StartTournamentMatchResponse;
import com.game.proto.match.TournamentInviteView;
import com.game.proto.match.TournamentMatchState;
import com.game.proto.match.TournamentPlayerRewardView;
import com.game.proto.match.TournamentSquadView;
import com.game.proto.match.UpdateTournamentLineupRequest;
import com.game.proto.match.UpdateTournamentLineupResponse;
import com.game.table.MatchErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 全服赛季战队锦标赛《群仙问道赛》V1 客户端 11 条协议服务（465..475，与 Go 版 TournamentLogic 逐条对齐）。 */
public final class TournamentService {

    private static final Logger log = LoggerFactory.getLogger(TournamentService.class);
    private static final int ERR_INTERNAL = MatchErrorTip.match_error.kMatchInternal_VALUE;

    @FunctionalInterface
    public interface MatchLauncher {
        /** 为双方已就绪的锦标赛对局建立战斗房间，返回 battle_id（0 表示当前未立即可开或未接线）。 */
        long launch(TournamentStore.LaunchSpec spec);
    }

    /** 战队名 UGC 内容安全送检策略（Strategy 模式）：生产接 game-content-guard，null 表示未启用。 */
    @FunctionalInterface
    public interface TextGuard {
        CheckTextResponse check(TextScene scene, long playerId, String text);
    }

    /** 赛季奖励个人邮件投递策略（Strategy 模式）：生产接 game-mail MailAdminService（sender_kind=6），事务外幂等投递，返回 mail_id。 */
    @FunctionalInterface
    public interface RewardMailSender {
        long send(long playerId, TournamentStore.ClaimableReward reward, String title, String body) throws Exception;
    }

    private final TournamentStore store;
    private final LongSupplier idGenerator;
    private final MatchLauncher launcher;
    private final TextGuard textGuard;
    private final RewardMailSender mailSender;

    public TournamentService(TournamentStore store, LongSupplier idGenerator, MatchLauncher launcher) {
        this(store, idGenerator, launcher, null, null);
    }

    public TournamentService(TournamentStore store, LongSupplier idGenerator, MatchLauncher launcher,
                             TextGuard textGuard, RewardMailSender mailSender) {
        this.store = Objects.requireNonNull(store, "store");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
        this.launcher = launcher;
        this.textGuard = textGuard;
        this.mailSender = mailSender;
    }

    public GetTournamentOverviewResponse getOverview(long playerId, GetTournamentOverviewRequest req) {
        if (playerId == 0L) {
            return GetTournamentOverviewResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            return store.loadOverview(playerId, req.getIncludeHistory()).toBuilder().setErrorMessage(tip(0)).build();
        } catch (TournamentStore.DomainException e) {
            return GetTournamentOverviewResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] getOverview failed player={}", Long.toUnsignedString(playerId), e);
            return GetTournamentOverviewResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public CreateTournamentSquadResponse createSquad(long playerId, int zoneId, CreateTournamentSquadRequest req) {
        if (playerId == 0L) {
            return CreateTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        // UGC 队名先在 SQL 事务外经内容安全审核（与 Go 版 TournamentLogic 对齐）。
        if (textGuard != null) {
            try {
                CheckTextResponse checked = textGuard.check(TextScene.TEXT_SCENE_GUILD_NAME, playerId, req.getSquadName());
                if (checked == null || checked.getVerdict() != TextVerdict.TEXT_VERDICT_PASS) {
                    return CreateTournamentSquadResponse.newBuilder()
                            .setErrorMessage(tip(MatchErrorTip.match_error.kMatchTournamentSquadNameInvalid_VALUE))
                            .build();
                }
            } catch (RuntimeException e) {
                log.error("[tournament] createSquad content guard failed player={}: {}", Long.toUnsignedString(playerId), e.getMessage());
                return CreateTournamentSquadResponse.newBuilder()
                        .setErrorMessage(tip(MatchErrorTip.match_error.kMatchTournamentSquadNameInvalid_VALUE))
                        .build();
            }
        }
        long squadId = idGenerator.getAsLong();
        if (squadId == 0L) {
            return CreateTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            TournamentSquadView sq = store.createSquad(playerId, "", zoneId, req.getSquadName(), squadId);
            return CreateTournamentSquadResponse.newBuilder().setErrorMessage(tip(0)).setSquad(sq).build();
        } catch (TournamentStore.DomainException e) {
            return CreateTournamentSquadResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] createSquad failed player={}", Long.toUnsignedString(playerId), e);
            return CreateTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public InviteTournamentMemberResponse inviteMember(long playerId, InviteTournamentMemberRequest req) {
        if (playerId == 0L) {
            return InviteTournamentMemberResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            TournamentInviteView inv = store.inviteMember(playerId, req.getTargetPlayerId(), "");
            return InviteTournamentMemberResponse.newBuilder().setErrorMessage(tip(0)).setInvite(inv).build();
        } catch (TournamentStore.DomainException e) {
            return InviteTournamentMemberResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] inviteMember failed player={}", Long.toUnsignedString(playerId), e);
            return InviteTournamentMemberResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public RespondTournamentInviteResponse respondInvite(long playerId, int zoneId, RespondTournamentInviteRequest req) {
        if (playerId == 0L) {
            return RespondTournamentInviteResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            TournamentSquadView sq = store.respondInvite(playerId, "", zoneId, req.getSquadId(), req.getAccept());
            RespondTournamentInviteResponse.Builder b = RespondTournamentInviteResponse.newBuilder().setErrorMessage(tip(0));
            if (sq != null) {
                b.setSquad(sq);
            }
            return b.build();
        } catch (TournamentStore.DomainException e) {
            return RespondTournamentInviteResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] respondInvite failed player={}", Long.toUnsignedString(playerId), e);
            return RespondTournamentInviteResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public LeaveTournamentSquadResponse leaveSquad(long playerId, LeaveTournamentSquadRequest req) {
        if (playerId == 0L) {
            return LeaveTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            TournamentStore.LeaveOutcome res = store.leaveSquad(playerId, req.getTargetPlayerId());
            LeaveTournamentSquadResponse.Builder b = LeaveTournamentSquadResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setSquadDisbanded(res.disbanded());
            if (res.squad() != null) {
                b.setSquad(res.squad());
            }
            return b.build();
        } catch (TournamentStore.DomainException e) {
            return LeaveTournamentSquadResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] leaveSquad failed player={}", Long.toUnsignedString(playerId), e);
            return LeaveTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public UpdateTournamentLineupResponse updateLineup(long playerId, UpdateTournamentLineupRequest req) {
        if (playerId == 0L) {
            return UpdateTournamentLineupResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            TournamentSquadView sq = store.updateLineup(playerId, req.getStarterPlayerIdsList());
            return UpdateTournamentLineupResponse.newBuilder().setErrorMessage(tip(0)).setSquad(sq).build();
        } catch (TournamentStore.DomainException e) {
            return UpdateTournamentLineupResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] updateLineup failed player={}", Long.toUnsignedString(playerId), e);
            return UpdateTournamentLineupResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public RegisterTournamentSquadResponse registerSquad(long playerId) {
        if (playerId == 0L) {
            return RegisterTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            TournamentSquadView sq = store.registerSquad(playerId);
            return RegisterTournamentSquadResponse.newBuilder().setErrorMessage(tip(0)).setSquad(sq).build();
        } catch (TournamentStore.DomainException e) {
            return RegisterTournamentSquadResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] registerSquad failed player={}", Long.toUnsignedString(playerId), e);
            return RegisterTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public ListTournamentStandingsResponse listStandings(long playerId, ListTournamentStandingsRequest req) {
        try {
            return store.listStandings(req.getSeasonId(), playerId, req.getPage(), req.getPageSize())
                    .toBuilder().setErrorMessage(tip(0)).build();
        } catch (TournamentStore.DomainException e) {
            return ListTournamentStandingsResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] listStandings failed season={}", req.getSeasonId(), e);
            return ListTournamentStandingsResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public StartTournamentMatchResponse startMatch(long playerId, StartTournamentMatchRequest req) {
        if (playerId == 0L) {
            return StartTournamentMatchResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        long newMatchId = req.getMatchId() == 0L ? idGenerator.getAsLong() : 0L;
        try {
            TournamentStore.PrepareMatchOutcome outcome = store.prepareMatch(
                    playerId, req.getMatchId(), req.getLineupPlayerIdsList(), newMatchId);
            if (outcome.launch() == null) {
                return StartTournamentMatchResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .setMatch(outcome.match())
                        .build();
            }
            if (launcher != null) {
                long battleId = launcher.launch(outcome.launch());
                if (battleId == 0L) {
                    try {
                        store.bindMatchBattle(outcome.launch().matchId(), 0L, false);
                    } catch (SQLException rollbackErr) {
                        log.warn("[tournament] rollback ready state failed match={}: {}",
                                Long.toUnsignedString(outcome.launch().matchId()), rollbackErr.getMessage());
                    }
                    return StartTournamentMatchResponse.newBuilder()
                            .setErrorMessage(tip(MatchErrorTip.match_error.kMatchTournamentMatchNotReady_VALUE))
                            .setMatch(outcome.match())
                            .build();
                }
                store.bindMatchBattle(outcome.launch().matchId(), battleId, outcome.launch().featured());
                return StartTournamentMatchResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .setMatch(outcome.match().toBuilder()
                                .setBattleId(battleId)
                                .setState(TournamentMatchState.TOURNAMENT_MATCH_STATE_IN_BATTLE)
                                .setFeaturedSpectate(outcome.launch().featured())
                                .build())
                        .setBattleId(battleId)
                        .build();
            }
            return StartTournamentMatchResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setMatch(outcome.match())
                    .build();
        } catch (TournamentStore.DomainException e) {
            return StartTournamentMatchResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] startMatch failed player={}", Long.toUnsignedString(playerId), e);
            return StartTournamentMatchResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public CheerTournamentSquadResponse cheerSquad(long playerId, CheerTournamentSquadRequest req) {
        if (playerId == 0L) {
            return CheerTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            int count = store.cheerSquad(playerId, req.getSquadId());
            return CheerTournamentSquadResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setSquadId(req.getSquadId())
                    .setSquadCheerCount(count)
                    .build();
        } catch (TournamentStore.DomainException e) {
            return CheerTournamentSquadResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] cheerSquad failed player={}", Long.toUnsignedString(playerId), e);
            return CheerTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public ClaimTournamentRewardResponse claimReward(long playerId, ClaimTournamentRewardRequest req) {
        if (playerId == 0L) {
            return ClaimTournamentRewardResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            if (mailSender == null) {
                List<TournamentPlayerRewardView> claimed = store.claimRewards(playerId, req.getSeasonId());
                return ClaimTournamentRewardResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .addAllClaimedRewards(claimed)
                        .build();
            }
            // 事务外逐条以 reward_id 为幂等键发个人邮件，成功拿到 mail_id 后再落账 CLAIMED（与 Go 版逐条对齐）。
            List<TournamentStore.ClaimableReward> pending = store.claimableRewards(playerId, req.getSeasonId());
            if (pending.isEmpty()) {
                return ClaimTournamentRewardResponse.newBuilder()
                        .setErrorMessage(tip(MatchErrorTip.match_error.kMatchTournamentRewardUnavailable_VALUE))
                        .build();
            }
            List<TournamentPlayerRewardView> claimed = new java.util.ArrayList<>(pending.size());
            for (TournamentStore.ClaimableReward r : pending) {
                String title = "群仙问道赛赛季奖励";
                String body = "本赛季群仙问道赛奖励已结算，请查收附件。";
                var tierOpt = store.tables().rewardById(r.tierId());
                if (tierOpt.isPresent()) {
                    if (!tierOpt.get().getMailTitle().isEmpty()) {
                        title = tierOpt.get().getMailTitle();
                    }
                    if (!tierOpt.get().getMailBody().isEmpty()) {
                        body = tierOpt.get().getMailBody();
                    }
                }
                long mailId;
                try {
                    mailId = mailSender.send(playerId, r, title, body);
                } catch (Exception mailErr) {
                    log.error("[tournament] send reward mail failed player={} reward={}: {}",
                            Long.toUnsignedString(playerId), Long.toUnsignedString(r.rewardId()), mailErr.getMessage());
                    return ClaimTournamentRewardResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
                }
                if (mailId == 0L) {
                    log.error("[tournament] send reward mail returned empty mail_id player={} reward={}",
                            Long.toUnsignedString(playerId), Long.toUnsignedString(r.rewardId()));
                    return ClaimTournamentRewardResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
                }
                claimed.add(store.markRewardClaimed(r.seasonId(), playerId, r.tierKind(), mailId));
            }
            return ClaimTournamentRewardResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .addAllClaimedRewards(claimed)
                    .build();
        } catch (TournamentStore.DomainException e) {
            return ClaimTournamentRewardResponse.newBuilder().setErrorMessage(tip(e.tipId())).build();
        } catch (SQLException e) {
            log.error("[tournament] claimReward failed player={}", Long.toUnsignedString(playerId), e);
            return ClaimTournamentRewardResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public List<MatchMethodHandler> clientHandlers() {
        return List.of(
                handler(MatchMethods.GET_TOURNAMENT_OVERVIEW,
                        (s, b) -> getOverview(pid(s), GetTournamentOverviewRequest.parseFrom(b)),
                        () -> GetTournamentOverviewResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.CREATE_TOURNAMENT_SQUAD,
                        (s, b) -> createSquad(pid(s), zone(s), CreateTournamentSquadRequest.parseFrom(b)),
                        () -> CreateTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.INVITE_TOURNAMENT_MEMBER,
                        (s, b) -> inviteMember(pid(s), InviteTournamentMemberRequest.parseFrom(b)),
                        () -> InviteTournamentMemberResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.RESPOND_TOURNAMENT_INVITE,
                        (s, b) -> respondInvite(pid(s), zone(s), RespondTournamentInviteRequest.parseFrom(b)),
                        () -> RespondTournamentInviteResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.LEAVE_TOURNAMENT_SQUAD,
                        (s, b) -> leaveSquad(pid(s), LeaveTournamentSquadRequest.parseFrom(b)),
                        () -> LeaveTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.UPDATE_TOURNAMENT_LINEUP,
                        (s, b) -> updateLineup(pid(s), UpdateTournamentLineupRequest.parseFrom(b)),
                        () -> UpdateTournamentLineupResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.REGISTER_TOURNAMENT_SQUAD,
                        (s, b) -> {
                            RegisterTournamentSquadRequest.parseFrom(b);
                            return registerSquad(pid(s));
                        },
                        () -> RegisterTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.LIST_TOURNAMENT_STANDINGS,
                        (s, b) -> listStandings(pid(s), ListTournamentStandingsRequest.parseFrom(b)),
                        () -> ListTournamentStandingsResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.START_TOURNAMENT_MATCH,
                        (s, b) -> startMatch(pid(s), StartTournamentMatchRequest.parseFrom(b)),
                        () -> StartTournamentMatchResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.CHEER_TOURNAMENT_SQUAD,
                        (s, b) -> cheerSquad(pid(s), CheerTournamentSquadRequest.parseFrom(b)),
                        () -> CheerTournamentSquadResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.CLAIM_TOURNAMENT_REWARD,
                        (s, b) -> claimReward(pid(s), ClaimTournamentRewardRequest.parseFrom(b)),
                        () -> ClaimTournamentRewardResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build())
        );
    }

    @FunctionalInterface
    private interface BodyHandler {
        com.google.protobuf.Message handle(SessionContext s, ByteString b) throws InvalidProtocolBufferException;
    }

    private static MatchMethodHandler handler(String method, BodyHandler fn, java.util.function.Supplier<com.google.protobuf.Message> overload) {
        return new MatchMethodHandler() {
            @Override
            public String method() {
                return method;
            }

            @Override
            public Reply handle(SessionContext s, ByteString b, Deadline d) throws InvalidProtocolBufferException {
                return Reply.body(fn.handle(s, b));
            }

            @Override
            public Reply onOverload() {
                return Reply.body(overload.get());
            }
        };
    }

    private static long pid(SessionContext s) {
        return s == null ? 0L : s.getPlayerId();
    }

    private static int zone(SessionContext s) {
        return s == null ? 0 : s.getZoneId();
    }

    private static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }
}
