package com.game.match.report;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.BattleReportSummary;
import com.game.proto.match.GetBattleReportRequest;
import com.game.proto.match.GetBattleReportResponse;
import com.game.proto.match.ListBattleReportsRequest;
import com.game.proto.match.ListBattleReportsResponse;
import com.game.table.MatchErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 客户端战报查询处理器（379 ListBattleReports / 380 GetBattleReport）。 */
public final class BattleReportHandlers {

    private static final Logger log = LoggerFactory.getLogger(BattleReportHandlers.class);
    private static final int ERR_INTERNAL = MatchErrorTip.match_error.kMatchInternal_VALUE;
    private static final int ERR_NOT_FOUND = MatchErrorTip.match_error.kMatchBattleReportNotFound_VALUE;

    private BattleReportHandlers() {
    }

    public static MatchMethodHandler listHandler(BattleReportStore store) {
        return listHandler(store, null);
    }

    public static MatchMethodHandler listHandler(
            BattleReportStore store,
            com.game.common.player.PlayerSessionAuthority.ReadOnlyOwnerCheck ownerCheck) {
        Objects.requireNonNull(store, "store");
        return new MatchMethodHandler() {
            @Override public String method() { return MatchMethods.LIST_BATTLE_REPORTS; }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline)
                    throws InvalidProtocolBufferException {
                ListBattleReportsRequest.parseFrom(body);
                long playerId = session == null ? 0L : session.getPlayerId();
                if (playerId == 0L) {
                    return Reply.body(ListBattleReportsResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                }
                try {
                    if (ownerCheck != null && !ownerCheck.isCurrentOwner(
                            playerId, session.getAccount(), session.getOwnerRevision())) {
                        return Reply.body(ListBattleReportsResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                    }
                    List<BattleReportSummary> reports = store.listReports(playerId);
                    return Reply.body(ListBattleReportsResponse.newBuilder()
                            .setErrorMessage(tip(0))
                            .addAllReports(reports)
                            .build());
                } catch (SQLException e) {
                    log.error("[battle_report] list failed player={}", Long.toUnsignedString(playerId), e);
                    return Reply.body(ListBattleReportsResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                }
            }

            @Override
            public Reply onOverload() {
                return Reply.body(ListBattleReportsResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
            }
        };
    }

    public static MatchMethodHandler getHandler(BattleReportStore store) {
        return getHandler(store, null);
    }

    public static MatchMethodHandler getHandler(
            BattleReportStore store,
            com.game.common.player.PlayerSessionAuthority.ReadOnlyOwnerCheck ownerCheck) {
        Objects.requireNonNull(store, "store");
        return new MatchMethodHandler() {
            @Override public String method() { return MatchMethods.GET_BATTLE_REPORT; }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline)
                    throws InvalidProtocolBufferException {
                GetBattleReportRequest req = GetBattleReportRequest.parseFrom(body);
                long playerId = session == null ? 0L : session.getPlayerId();
                if (playerId == 0L) {
                    return Reply.body(GetBattleReportResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                }
                if (req.getBattleId() == 0L) {
                    return Reply.body(GetBattleReportResponse.newBuilder().setErrorMessage(tip(ERR_NOT_FOUND)).build());
                }
                try {
                    if (ownerCheck != null && !ownerCheck.isCurrentOwner(
                            playerId, session.getAccount(), session.getOwnerRevision())) {
                        return Reply.body(GetBattleReportResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                    }
                    Optional<BattleReplayEnvelope> found = store.getReport(playerId, req.getBattleId());
                    if (found.isEmpty()) {
                        return Reply.body(GetBattleReportResponse.newBuilder().setErrorMessage(tip(ERR_NOT_FOUND)).build());
                    }
                    return Reply.body(GetBattleReportResponse.newBuilder()
                            .setErrorMessage(tip(0))
                            .setReplay(found.get())
                            .build());
                } catch (SQLException e) {
                    log.error("[battle_report] get failed player={} battle={}",
                            Long.toUnsignedString(playerId), Long.toUnsignedString(req.getBattleId()), e);
                    return Reply.body(GetBattleReportResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                }
            }

            @Override
            public Reply onOverload() {
                return Reply.body(GetBattleReportResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
            }
        };
    }

    private static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }
}
