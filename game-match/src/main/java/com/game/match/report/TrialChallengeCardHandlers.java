package com.game.match.report;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.CreateTrialChallengeCardRequest;
import com.game.proto.match.CreateTrialChallengeCardResponse;
import com.game.proto.match.GetSharedTrialReplayRequest;
import com.game.proto.match.GetSharedTrialReplayResponse;
import com.game.proto.match.GetTrialChallengeCardRequest;
import com.game.proto.match.GetTrialChallengeCardResponse;
import com.game.proto.match.TrialChallengeCardView;
import com.game.table.MatchErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 五行试炼志 V3 好友约战帖与受控 PVE 录像播放客户端处理器（570–572，对齐 Go {@code trialchallengecardlogic.go}）。
 * 身份仅认 gate 注入的 {@code SessionContext.player_id}，缺失即 fail-closed。
 */
public final class TrialChallengeCardHandlers {

    private static final Logger log = LoggerFactory.getLogger(TrialChallengeCardHandlers.class);
    private static final int ERR_INTERNAL = MatchErrorTip.match_error.kMatchInternal_VALUE;
    private static final int ERR_NOT_FOUND = MatchErrorTip.match_error.kMatchBattleReportNotFound_VALUE;

    private TrialChallengeCardHandlers() {
    }

    public static MatchMethodHandler createHandler(TrialChallengeCardStore store, LongSupplier nextCardId) {
        Objects.requireNonNull(store, "store");
        Objects.requireNonNull(nextCardId, "nextCardId");
        return new MatchMethodHandler() {
            @Override
            public String method() {
                return MatchMethods.CREATE_TRIAL_CHALLENGE_CARD;
            }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline)
                    throws InvalidProtocolBufferException {
                CreateTrialChallengeCardRequest req = CreateTrialChallengeCardRequest.parseFrom(body);
                long playerId = session == null ? 0L : session.getPlayerId();
                if (playerId == 0L) {
                    return Reply.body(CreateTrialChallengeCardResponse.newBuilder()
                            .setErrorMessage(tip(ERR_INTERNAL)).build());
                }
                if (req.getBattleId() == 0L) {
                    return Reply.body(CreateTrialChallengeCardResponse.newBuilder()
                            .setErrorMessage(tip(ERR_NOT_FOUND)).build());
                }
                try {
                    Optional<TrialChallengeCardView> card = store.createCard(playerId, req.getBattleId(), nextCardId);
                    if (card.isEmpty()) {
                        return Reply.body(CreateTrialChallengeCardResponse.newBuilder()
                                .setErrorMessage(tip(ERR_NOT_FOUND)).build());
                    }
                    return Reply.body(CreateTrialChallengeCardResponse.newBuilder()
                            .setErrorMessage(tip(0))
                            .setCard(card.get())
                            .build());
                } catch (SQLException | RuntimeException e) {
                    log.error("[trial_card] CreateTrialChallengeCard failed player={} battle={}",
                            Long.toUnsignedString(playerId), Long.toUnsignedString(req.getBattleId()), e);
                    return Reply.body(CreateTrialChallengeCardResponse.newBuilder()
                            .setErrorMessage(tip(ERR_INTERNAL)).build());
                }
            }

            @Override
            public Reply onOverload() {
                return Reply.body(CreateTrialChallengeCardResponse.newBuilder()
                        .setErrorMessage(tip(ERR_INTERNAL)).build());
            }
        };
    }

    public static MatchMethodHandler getHandler(TrialChallengeCardStore store) {
        Objects.requireNonNull(store, "store");
        return new MatchMethodHandler() {
            @Override
            public String method() {
                return MatchMethods.GET_TRIAL_CHALLENGE_CARD;
            }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline)
                    throws InvalidProtocolBufferException {
                GetTrialChallengeCardRequest req = GetTrialChallengeCardRequest.parseFrom(body);
                long playerId = session == null ? 0L : session.getPlayerId();
                if (playerId == 0L) {
                    return Reply.body(GetTrialChallengeCardResponse.newBuilder()
                            .setErrorMessage(tip(ERR_INTERNAL)).build());
                }
                if (req.getCardId() == 0L) {
                    return Reply.body(GetTrialChallengeCardResponse.newBuilder()
                            .setErrorMessage(tip(ERR_NOT_FOUND)).build());
                }
                try {
                    Optional<TrialChallengeCardView> card = store.getCard(req.getCardId());
                    if (card.isEmpty()) {
                        return Reply.body(GetTrialChallengeCardResponse.newBuilder()
                                .setErrorMessage(tip(ERR_NOT_FOUND)).build());
                    }
                    return Reply.body(GetTrialChallengeCardResponse.newBuilder()
                            .setErrorMessage(tip(0))
                            .setCard(card.get())
                            .build());
                } catch (SQLException e) {
                    log.error("[trial_card] GetTrialChallengeCard failed player={} card={}",
                            Long.toUnsignedString(playerId), Long.toUnsignedString(req.getCardId()), e);
                    return Reply.body(GetTrialChallengeCardResponse.newBuilder()
                            .setErrorMessage(tip(ERR_INTERNAL)).build());
                }
            }

            @Override
            public Reply onOverload() {
                return Reply.body(GetTrialChallengeCardResponse.newBuilder()
                        .setErrorMessage(tip(ERR_INTERNAL)).build());
            }
        };
    }

    public static MatchMethodHandler sharedReplayHandler(TrialChallengeCardStore store) {
        Objects.requireNonNull(store, "store");
        return new MatchMethodHandler() {
            @Override
            public String method() {
                return MatchMethods.GET_SHARED_TRIAL_REPLAY;
            }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline)
                    throws InvalidProtocolBufferException {
                GetSharedTrialReplayRequest req = GetSharedTrialReplayRequest.parseFrom(body);
                long playerId = session == null ? 0L : session.getPlayerId();
                if (playerId == 0L) {
                    return Reply.body(GetSharedTrialReplayResponse.newBuilder()
                            .setErrorMessage(tip(ERR_INTERNAL)).build());
                }
                if (req.getCardId() == 0L) {
                    return Reply.body(GetSharedTrialReplayResponse.newBuilder()
                            .setErrorMessage(tip(ERR_NOT_FOUND)).build());
                }
                try {
                    Optional<BattleReplayEnvelope> replay = store.getSharedReplay(req.getCardId());
                    if (replay.isEmpty()) {
                        return Reply.body(GetSharedTrialReplayResponse.newBuilder()
                                .setErrorMessage(tip(ERR_NOT_FOUND)).build());
                    }
                    return Reply.body(GetSharedTrialReplayResponse.newBuilder()
                            .setErrorMessage(tip(0))
                            .setReplay(replay.get())
                            .build());
                } catch (SQLException e) {
                    log.error("[trial_card] GetSharedTrialReplay failed player={} card={}",
                            Long.toUnsignedString(playerId), Long.toUnsignedString(req.getCardId()), e);
                    return Reply.body(GetSharedTrialReplayResponse.newBuilder()
                            .setErrorMessage(tip(ERR_INTERNAL)).build());
                }
            }

            @Override
            public Reply onOverload() {
                return Reply.body(GetSharedTrialReplayResponse.newBuilder()
                        .setErrorMessage(tip(ERR_INTERNAL)).build());
            }
        };
    }

    private static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }
}
