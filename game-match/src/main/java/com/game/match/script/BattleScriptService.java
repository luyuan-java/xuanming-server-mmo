package com.game.match.script;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.common.token.NetworkProbes;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.feature.pb.MatchBattleScriptLoadoutRow;
import com.game.match.feature.pb.MatchBattleScriptRow;
import com.game.proto.BattleScript;
import com.game.proto.TipInfoMessage;
import com.game.proto.eBattleScriptOrigin;
import com.game.proto.eBattleScriptSide;
import com.game.proto.match.BattleScriptLoadoutEntry;
import com.game.proto.match.BattleScriptReviewReject;
import com.game.proto.match.BattleScriptReviewState;
import com.game.proto.match.DeleteBattleScriptRequest;
import com.game.proto.match.DeleteBattleScriptResponse;
import com.game.proto.match.ListBattleScriptsRequest;
import com.game.proto.match.ListBattleScriptsResponse;
import com.game.proto.match.ListPendingBattleScriptsRequest;
import com.game.proto.match.ListPendingBattleScriptsResponse;
import com.game.proto.match.PendingBattleScript;
import com.game.proto.match.PlayerBattleScriptView;
import com.game.proto.match.ReviewBattleScriptRequest;
import com.game.proto.match.ReviewBattleScriptResponse;
import com.game.proto.match.SetBattleScriptLoadoutRequest;
import com.game.proto.match.SetBattleScriptLoadoutResponse;
import com.game.proto.match.SubmitBattleScriptRequest;
import com.game.proto.match.SubmitBattleScriptResponse;
import com.game.table.MatchErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 战斗剧本客户端（389–392）与运营审核（387–388）业务编排（对齐 Go {@code battlescriptlogic.go}/{@code battlescriptreviewlogic.go}）。 */
public final class BattleScriptService {

    private static final Logger log = LoggerFactory.getLogger(BattleScriptService.class);

    public static final int MAX_SCRIPTS_PER_PLAYER = 10;
    public static final int MAX_LOADOUT_ENTRIES = 32;
    public static final int MAX_PLAYER_SCRIPT_BYTES = 900;
    public static final int MAX_PENDING_PAGE = 50;
    public static final int MAX_REVIEWER_BYTES = 64;
    public static final int MAX_REVIEW_REASON_BYTES = 191;

    private static final int ERR_INTERNAL = MatchErrorTip.match_error.kMatchInternal_VALUE;
    private static final int ERR_INVALID = MatchErrorTip.match_error.kMatchBattleScriptInvalid_VALUE;
    private static final int ERR_NOT_FOUND = MatchErrorTip.match_error.kMatchBattleScriptNotFound_VALUE;
    private static final int ERR_LIMIT = MatchErrorTip.match_error.kMatchBattleScriptLimitReached_VALUE;
    private static final int ERR_REV_CONFLICT = MatchErrorTip.match_error.kMatchBattleScriptRevisionConflict_VALUE;
    private static final int ERR_NOT_APPROVED = MatchErrorTip.match_error.kMatchBattleScriptNotApproved_VALUE;
    private static final int ERR_TOO_LARGE = MatchErrorTip.match_error.kMatchBattleScriptTooLarge_VALUE;
    private static final int ERR_LOADOUT_INVALID = MatchErrorTip.match_error.kMatchBattleScriptLoadoutInvalid_VALUE;
    private static final int ERR_LOADOUT_FULL = MatchErrorTip.match_error.kMatchBattleScriptLoadoutFull_VALUE;

    private final BattleScriptStore store;
    private volatile com.game.common.player.PlayerSessionAuthority.ReadOnlyOwnerCheck ownerCheck;
    private final BattleScriptCatalog catalog;
    private final BattleScriptValidator.Tables tables;
    private final LongSupplier idGenerator;

    public BattleScriptService(BattleScriptStore store, BattleScriptCatalog catalog,
                               BattleScriptValidator.Tables tables, LongSupplier idGenerator) {
        this.store = Objects.requireNonNull(store, "store");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.idGenerator = Objects.requireNonNull(idGenerator, "idGenerator");
    }

    public ListBattleScriptsResponse list(long playerId) {
        if (playerId == 0L) {
            return ListBattleScriptsResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            List<MatchBattleScriptRow> records = store.listScripts(playerId);
            List<MatchBattleScriptLoadoutRow> loadout = store.listLoadout(playerId);
            ListBattleScriptsResponse.Builder resp = ListBattleScriptsResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setMaxScripts(MAX_SCRIPTS_PER_PLAYER)
                    .setMaxScriptBytes(MAX_PLAYER_SCRIPT_BYTES)
                    .addAllOfficialScripts(catalog.officialScripts())
                    .addAllLoadout(toLoadoutEntries(loadout));
            for (MatchBattleScriptRow row : records) {
                resp.addMyScripts(toView(row));
            }
            return resp.build();
        } catch (Exception e) {
            log.error("[battle_script] list failed player={}", Long.toUnsignedString(playerId), e);
            return ListBattleScriptsResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public SubmitBattleScriptResponse submit(long playerId, SubmitBattleScriptRequest req) {
        if (playerId == 0L) {
            return SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        if (!req.hasScript()) {
            return SubmitBattleScriptResponse.newBuilder()
                    .setErrorMessage(tip(ERR_INVALID))
                    .setInvalidDetail("script is missing")
                    .build();
        }
        if (req.getScriptId() == 0L && req.getExpectedRevision() != 0) {
            return SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_REV_CONFLICT)).build();
        }
        BattleScript candidate = req.getScript().toBuilder().setScriptId(0).build();
        if (candidate.getSerializedSize() > MAX_PLAYER_SCRIPT_BYTES) {
            return SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_TOO_LARGE)).build();
        }
        BattleScript normalized;
        try {
            normalized = BattleScriptValidator.normalize(candidate, tables);
            BattleScriptValidator.checkSide(normalized, eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER, tables);
        } catch (IllegalArgumentException invalid) {
            return SubmitBattleScriptResponse.newBuilder()
                    .setErrorMessage(tip(ERR_INVALID))
                    .setInvalidDetail(invalid.getMessage() == null ? "invalid script" : invalid.getMessage())
                    .build();
        }
        byte[] content = NetworkProbes.bytes(normalized);
        long newId = 0L;
        if (req.getScriptId() == 0L) {
            try {
                newId = idGenerator.getAsLong();
            } catch (RuntimeException e) {
                log.error("[battle_script] id mint failed player={}", Long.toUnsignedString(playerId), e);
                return SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
            }
            if (newId == 0L) {
                return SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
            }
        }
        try {
            BattleScriptStore.SubmitResult res = store.submitScript(playerId, req.getScriptId(), newId,
                    req.getExpectedRevision(), content, MAX_SCRIPTS_PER_PLAYER);
            return switch (res.status()) {
                case OK -> SubmitBattleScriptResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .setScript(toView(res.row()))
                        .build();
                case LIMIT_REACHED -> SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_LIMIT)).build();
                case NOT_FOUND -> SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_NOT_FOUND)).build();
                case REVISION_CONFLICT -> SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_REV_CONFLICT)).build();
                default -> SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
            };
        } catch (Exception e) {
            log.error("[battle_script] submit failed player={} script={}",
                    Long.toUnsignedString(playerId), Long.toUnsignedString(req.getScriptId()), e);
            return SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public DeleteBattleScriptResponse delete(long playerId, DeleteBattleScriptRequest req) {
        if (playerId == 0L) {
            return DeleteBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        if (req.getScriptId() == 0L) {
            return DeleteBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_NOT_FOUND)).build();
        }
        try {
            BattleScriptStore.Status status = store.deleteScript(playerId, req.getScriptId(), req.getExpectedRevision());
            return switch (status) {
                case OK -> DeleteBattleScriptResponse.newBuilder().setErrorMessage(tip(0)).build();
                case NOT_FOUND -> DeleteBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_NOT_FOUND)).build();
                case REVISION_CONFLICT -> DeleteBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_REV_CONFLICT)).build();
                default -> DeleteBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
            };
        } catch (SQLException e) {
            log.error("[battle_script] delete failed player={} script={}",
                    Long.toUnsignedString(playerId), Long.toUnsignedString(req.getScriptId()), e);
            return DeleteBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public SetBattleScriptLoadoutResponse setLoadout(long playerId, SetBattleScriptLoadoutRequest req) {
        if (playerId == 0L) {
            return SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        if (!req.hasEntry()) {
            return SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_LOADOUT_INVALID)).build();
        }
        BattleScriptLoadoutEntry entry = req.getEntry();
        int matchMode = entry.getMatchMode();
        int configId = entry.getBattleConfigId();
        if (!BattleScriptValidator.isKnownMatchMode(matchMode)
                || (configId != 0 && !tables.hasDungeon(configId))) {
            return SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_LOADOUT_INVALID)).build();
        }
        eBattleScriptOrigin origin = entry.getOrigin();
        int officialId = 0;
        long playerScriptId = 0L;
        switch (origin) {
            case BATTLE_SCRIPT_ORIGIN_UNSPECIFIED -> {
            }
            case BATTLE_SCRIPT_ORIGIN_OFFICIAL -> {
                BattleScript official = catalog.official(entry.getOfficialScriptId()).orElse(null);
                if (official == null) {
                    return SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_LOADOUT_INVALID)).build();
                }
                try {
                    BattleScriptValidator.checkSide(official, eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER, tables);
                } catch (IllegalArgumentException e) {
                    return SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_LOADOUT_INVALID)).build();
                }
                officialId = entry.getOfficialScriptId();
            }
            case BATTLE_SCRIPT_ORIGIN_PLAYER -> {
                if (entry.getPlayerScriptId() == 0L) {
                    return SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_NOT_FOUND)).build();
                }
                if (matchMode != 0 && BattleScriptValidator.isRatedMode(matchMode)) {
                    return SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_LOADOUT_INVALID)).build();
                }
                playerScriptId = entry.getPlayerScriptId();
            }
            default -> {
                return SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_LOADOUT_INVALID)).build();
            }
        }
        try {
            BattleScriptStore.LoadoutResult res = store.setLoadout(playerId, matchMode, configId, origin,
                    officialId, playerScriptId, MAX_LOADOUT_ENTRIES);
            return switch (res.status()) {
                case OK -> SetBattleScriptLoadoutResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .addAllLoadout(toLoadoutEntries(res.rows()))
                        .build();
                case NOT_FOUND -> SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_NOT_FOUND)).build();
                case NOT_APPROVED -> SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_NOT_APPROVED)).build();
                case LOADOUT_FULL -> SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_LOADOUT_FULL)).build();
                default -> SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
            };
        } catch (SQLException e) {
            log.error("[battle_script] setLoadout failed player={}", Long.toUnsignedString(playerId), e);
            return SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    /** 387 MatchInternal.ListPendingBattleScripts（调用方已验签 ops）。 */
    public ListPendingBattleScriptsResponse listPending(ListPendingBattleScriptsRequest req) {
        int limit = req.getLimit();
        if (limit < 1 || limit > MAX_PENDING_PAGE) {
            return ListPendingBattleScriptsResponse.newBuilder()
                    .setReject(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_INVALID)
                    .build();
        }
        try {
            List<MatchBattleScriptRow> rows = store.listPending(limit);
            ListPendingBattleScriptsResponse.Builder resp = ListPendingBattleScriptsResponse.newBuilder()
                    .setReject(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_NONE);
            for (MatchBattleScriptRow row : rows) {
                resp.addScripts(PendingBattleScript.newBuilder()
                        .setScriptId(row.getScriptId())
                        .setOwnerPlayerId(row.getOwnerPlayerId())
                        .setRevision(row.getRevision())
                        .setContent(BattleScript.parseFrom(row.getContent()))
                        .setSubmittedMs(row.getSubmittedMs())
                        .setApprovedRevision(row.getApprovedRevision())
                        .build());
            }
            return resp.build();
        } catch (Exception e) {
            log.error("[battle_script] listPending failed", e);
            return ListPendingBattleScriptsResponse.newBuilder()
                    .setReject(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_INTERNAL)
                    .build();
        }
    }

    /** 388 MatchInternal.ReviewBattleScript（调用方已验签 ops）。 */
    public ReviewBattleScriptResponse review(ReviewBattleScriptRequest req) {
        if (!validReviewText(req.getReviewer(), 1, MAX_REVIEWER_BYTES)
                || !validReviewText(req.getReason(), 0, MAX_REVIEW_REASON_BYTES)
                || req.getScriptId() == 0L
                || req.getRevision() == 0
                || (!req.getApprove() && req.getReason().isEmpty())) {
            return ReviewBattleScriptResponse.newBuilder()
                    .setReject(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_INVALID)
                    .build();
        }
        try {
            BattleScriptStore.Status status = store.reviewScript(
                    req.getScriptId(), req.getRevision(), req.getApprove(), req.getReviewer(), req.getReason(),
                    bytes -> {
                        try {
                            BattleScript parsed = BattleScript.parseFrom(bytes);
                            BattleScript normalized = BattleScriptValidator.normalize(parsed, tables);
                            BattleScriptValidator.checkSide(normalized, eBattleScriptSide.BATTLE_SCRIPT_SIDE_PLAYER, tables);
                        } catch (InvalidProtocolBufferException e) {
                            throw new IllegalArgumentException(e);
                        }
                    });
            return switch (status) {
                case OK -> ReviewBattleScriptResponse.newBuilder()
                        .setReject(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_NONE).build();
                case REVIEW_STALE -> ReviewBattleScriptResponse.newBuilder()
                        .setReject(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_STALE).build();
                case REVIEW_INVALID -> ReviewBattleScriptResponse.newBuilder()
                        .setReject(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_INVALID).build();
                default -> ReviewBattleScriptResponse.newBuilder()
                        .setReject(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_INTERNAL).build();
            };
        } catch (SQLException e) {
            log.error("[battle_script] review failed script={} rev={}",
                    Long.toUnsignedString(req.getScriptId()), req.getRevision(), e);
            return ReviewBattleScriptResponse.newBuilder()
                    .setReject(BattleScriptReviewReject.BATTLE_SCRIPT_REVIEW_REJECT_INTERNAL)
                    .build();
        }
    }

    /** 挂载只读角色归属核对器：角色过户/删除后拒绝对作战脚本列表与配置的访问，冻结期间仍允许读。 */
    public BattleScriptService withReadOnlyOwnerCheck(
            com.game.common.player.PlayerSessionAuthority.ReadOnlyOwnerCheck ownerCheck) {
        this.ownerCheck = ownerCheck;
        return this;
    }

    private boolean isOwnerAllowed(SessionContext s) {
        var check = this.ownerCheck;
        if (check == null) {
            return true;
        }
        if (s == null || s.getPlayerId() == 0L) {
            return false;
        }
        try {
            return check.isCurrentOwner(s.getPlayerId(), s.getAccount(), s.getOwnerRevision());
        } catch (SQLException e) {
            log.error("[battle_script] owner check failed player={}", Long.toUnsignedString(s.getPlayerId()), e);
            return false;
        }
    }

    public List<MatchMethodHandler> clientHandlers() {
        return List.of(
                new MatchMethodHandler() {
                    @Override public String method() { return MatchMethods.LIST_BATTLE_SCRIPTS; }
                    @Override public Reply handle(SessionContext s, ByteString b, Deadline d) throws InvalidProtocolBufferException {
                        ListBattleScriptsRequest.parseFrom(b);
                        if (!isOwnerAllowed(s)) {
                            return Reply.body(ListBattleScriptsResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                        }
                        return Reply.body(list(s == null ? 0L : s.getPlayerId()));
                    }
                    @Override public Reply onOverload() {
                        return Reply.body(ListBattleScriptsResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                    }
                },
                new MatchMethodHandler() {
                    @Override public String method() { return MatchMethods.SUBMIT_BATTLE_SCRIPT; }
                    @Override public Reply handle(SessionContext s, ByteString b, Deadline d) throws InvalidProtocolBufferException {
                        SubmitBattleScriptRequest req = SubmitBattleScriptRequest.parseFrom(b);
                        if (!isOwnerAllowed(s)) {
                            return Reply.body(SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                        }
                        return Reply.body(submit(s == null ? 0L : s.getPlayerId(), req));
                    }
                    @Override public Reply onOverload() {
                        return Reply.body(SubmitBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                    }
                },
                new MatchMethodHandler() {
                    @Override public String method() { return MatchMethods.DELETE_BATTLE_SCRIPT; }
                    @Override public Reply handle(SessionContext s, ByteString b, Deadline d) throws InvalidProtocolBufferException {
                        DeleteBattleScriptRequest req = DeleteBattleScriptRequest.parseFrom(b);
                        if (!isOwnerAllowed(s)) {
                            return Reply.body(DeleteBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                        }
                        return Reply.body(delete(s == null ? 0L : s.getPlayerId(), req));
                    }
                    @Override public Reply onOverload() {
                        return Reply.body(DeleteBattleScriptResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                    }
                },
                new MatchMethodHandler() {
                    @Override public String method() { return MatchMethods.SET_BATTLE_SCRIPT_LOADOUT; }
                    @Override public Reply handle(SessionContext s, ByteString b, Deadline d) throws InvalidProtocolBufferException {
                        SetBattleScriptLoadoutRequest req = SetBattleScriptLoadoutRequest.parseFrom(b);
                        if (!isOwnerAllowed(s)) {
                            return Reply.body(SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                        }
                        return Reply.body(setLoadout(s == null ? 0L : s.getPlayerId(), req));
                    }
                    @Override public Reply onOverload() {
                        return Reply.body(SetBattleScriptLoadoutResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                    }
                });
    }

    private static PlayerBattleScriptView toView(MatchBattleScriptRow row) throws InvalidProtocolBufferException {
        BattleScriptReviewState state = BattleScriptReviewState.forNumber(row.getReviewState());
        return PlayerBattleScriptView.newBuilder()
                .setScriptId(row.getScriptId())
                .setRevision(row.getRevision())
                .setContent(BattleScript.parseFrom(row.getContent()))
                .setReviewState(state == null ? BattleScriptReviewState.BATTLE_SCRIPT_REVIEW_UNSPECIFIED : state)
                .setApprovedRevision(row.getApprovedRevision())
                .setRejectReason(row.getRejectReason())
                .setSubmittedMs(row.getSubmittedMs())
                .build();
    }

    private static List<BattleScriptLoadoutEntry> toLoadoutEntries(List<MatchBattleScriptLoadoutRow> rows) {
        List<BattleScriptLoadoutEntry> out = new ArrayList<>(rows.size());
        for (MatchBattleScriptLoadoutRow r : rows) {
            eBattleScriptOrigin origin = eBattleScriptOrigin.forNumber(r.getOrigin());
            out.add(BattleScriptLoadoutEntry.newBuilder()
                    .setMatchMode(r.getMatchMode())
                    .setBattleConfigId(r.getBattleConfigId())
                    .setOrigin(origin == null ? eBattleScriptOrigin.BATTLE_SCRIPT_ORIGIN_UNSPECIFIED : origin)
                    .setOfficialScriptId(r.getOfficialScriptId())
                    .setPlayerScriptId(r.getPlayerScriptId())
                    .build());
        }
        return out;
    }

    private static boolean validReviewText(String text, int minBytes, int maxBytes) {
        if (text == null) {
            return minBytes == 0;
        }
        int len = text.getBytes(StandardCharsets.UTF_8).length;
        if (len < minBytes || len > maxBytes) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isISOControl(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }
}
