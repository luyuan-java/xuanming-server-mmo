package com.game.match.puzzle;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.proto.BattleReplayEnvelope;
import com.game.proto.RankedMatchContext;
import com.game.proto.RankedMemberLoadout;
import com.game.proto.RankedPresetSelection;
import com.game.proto.TacticalPuzzleBattleContext;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.DeleteTacticalPuzzleRequest;
import com.game.proto.match.DeleteTacticalPuzzleResponse;
import com.game.proto.match.GetTacticalPuzzleRequest;
import com.game.proto.match.GetTacticalPuzzleResponse;
import com.game.proto.match.GetTacticalPuzzleSolutionReplayRequest;
import com.game.proto.match.GetTacticalPuzzleSolutionReplayResponse;
import com.game.proto.match.LikeTacticalPuzzleRequest;
import com.game.proto.match.LikeTacticalPuzzleResponse;
import com.game.proto.match.ListTacticalPuzzlesRequest;
import com.game.proto.match.ListTacticalPuzzlesResponse;
import com.game.proto.match.SaveTacticalPuzzleDraftRequest;
import com.game.proto.match.SaveTacticalPuzzleDraftResponse;
import com.game.proto.match.StartTacticalPuzzleBattleRequest;
import com.game.proto.match.StartTacticalPuzzleBattleResponse;
import com.game.proto.match.TacticalPuzzleSummaryView;
import com.game.table.MatchErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 万象残局坊 V1（575..581）客户端 RPC 服务与处理器工厂（对齐 Go {@code tactical_puzzle_logic.go}）。
 * 身份仅认 gate 注入的 {@code SessionContext.player_id}；出题文本必经 {@link TextGuard}（fail-closed）。
 */
public final class TacticalPuzzleService {

    private static final Logger log = LoggerFactory.getLogger(TacticalPuzzleService.class);
    private static final int ERR_INTERNAL = MatchErrorTip.match_error.kMatchInternal_VALUE;
    private static final int ERR_SPEC_INVALID = MatchErrorTip.match_error.kMatchPuzzleSpecInvalid_VALUE;
    private static final int ERR_TEXT_REJECTED = MatchErrorTip.match_error.kMatchPuzzleTextRejected_VALUE;

    public enum TextVerdict {
        PASS,
        REJECT,
        UNAVAILABLE
    }

    @FunctionalInterface
    public interface TextGuard {
        TextVerdict check(long playerId, String text);
    }

    @FunctionalInterface
    public interface BattleLauncher {
        /** 发起单人残局战斗，返回分配的 battle_id（0 表示开战失败）。 */
        long launch(long playerId, TacticalPuzzleBattleContext puzzleCtx, RankedMatchContext freePresetCtx, String requestId);
    }

    @FunctionalInterface
    public interface RankedPresetMetaProvider {
        RankedPresetMeta current();
    }

    public record RankedPresetMeta(int ruleVersion, String configFingerprint) {
    }

    private final TacticalPuzzleStore store;
    private final LongSupplier nextId;
    private final TextGuard textGuard;
    private final BattleLauncher launcher;
    private final RankedPresetMetaProvider presetMetaProvider;

    public TacticalPuzzleService(
            TacticalPuzzleStore store,
            LongSupplier nextId,
            TextGuard textGuard,
            BattleLauncher launcher,
            RankedPresetMetaProvider presetMetaProvider) {
        this.store = Objects.requireNonNull(store, "store");
        this.nextId = Objects.requireNonNull(nextId, "nextId");
        this.textGuard = textGuard;
        this.launcher = launcher;
        this.presetMetaProvider = presetMetaProvider != null ? presetMetaProvider : () -> new RankedPresetMeta(1, "");
    }

    public List<MatchMethodHandler> clientHandlers() {
        return List.of(
                handler(MatchMethods.LIST_TACTICAL_PUZZLES, this::handleList,
                        () -> ListTacticalPuzzlesResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.GET_TACTICAL_PUZZLE, this::handleGet,
                        () -> GetTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.SAVE_TACTICAL_PUZZLE_DRAFT, this::handleSaveDraft,
                        () -> SaveTacticalPuzzleDraftResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.DELETE_TACTICAL_PUZZLE, this::handleDelete,
                        () -> DeleteTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.START_TACTICAL_PUZZLE_BATTLE, this::handleStartBattle,
                        () -> StartTacticalPuzzleBattleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.LIKE_TACTICAL_PUZZLE, this::handleLike,
                        () -> LikeTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build()),
                handler(MatchMethods.GET_TACTICAL_PUZZLE_SOLUTION_REPLAY, this::handleGetSolutionReplay,
                        () -> GetTacticalPuzzleSolutionReplayResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build())
        );
    }

    private MatchMethodHandler.Reply handleList(SessionContext session, ByteString body) throws InvalidProtocolBufferException {
        ListTacticalPuzzlesRequest req = ListTacticalPuzzlesRequest.parseFrom(body);
        long playerId = sessionPlayerId(session);
        if (playerId == 0L) {
            return MatchMethodHandler.Reply.body(ListTacticalPuzzlesResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
        try {
            TacticalPuzzleStore.ListPage page = store.listPuzzles(
                    playerId, req.getTab(), req.getTagFilterMask(), req.getOffset(), req.getLimit());
            return MatchMethodHandler.Reply.body(ListTacticalPuzzlesResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .addAllPuzzles(page.items())
                    .setTotalCount(page.totalCount())
                    .setProfile(page.profile())
                    .build());
        } catch (SQLException e) {
            log.error("[tactical_puzzle] ListTacticalPuzzles failed player={}", Long.toUnsignedString(playerId), e);
            return MatchMethodHandler.Reply.body(ListTacticalPuzzlesResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
    }

    private MatchMethodHandler.Reply handleGet(SessionContext session, ByteString body) throws InvalidProtocolBufferException {
        GetTacticalPuzzleRequest req = GetTacticalPuzzleRequest.parseFrom(body);
        long playerId = sessionPlayerId(session);
        if (playerId == 0L) {
            return MatchMethodHandler.Reply.body(GetTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
        try {
            TacticalPuzzleSummaryView view = store.getPuzzle(playerId, req.getPuzzleId());
            return MatchMethodHandler.Reply.body(GetTacticalPuzzleResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setPuzzle(view)
                    .build());
        } catch (TacticalPuzzleStore.StoreException e) {
            return MatchMethodHandler.Reply.body(GetTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(e.errorTip())).build());
        } catch (SQLException e) {
            log.error("[tactical_puzzle] GetTacticalPuzzle failed player={} puzzle={}",
                    Long.toUnsignedString(playerId), Long.toUnsignedString(req.getPuzzleId()), e);
            return MatchMethodHandler.Reply.body(GetTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
    }

    private MatchMethodHandler.Reply handleSaveDraft(SessionContext session, ByteString body) throws InvalidProtocolBufferException {
        SaveTacticalPuzzleDraftRequest req = SaveTacticalPuzzleDraftRequest.parseFrom(body);
        long playerId = sessionPlayerId(session);
        if (playerId == 0L) {
            return MatchMethodHandler.Reply.body(SaveTacticalPuzzleDraftResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
        TacticalPuzzleSpecValidator.NormalizedText text =
                TacticalPuzzleSpecValidator.normalizeAndValidateText(req.getTitle(), req.getHintText());
        if (text == null) {
            return MatchMethodHandler.Reply.body(SaveTacticalPuzzleDraftResponse.newBuilder().setErrorMessage(tip(ERR_TEXT_REJECTED)).build());
        }
        if (!TacticalPuzzleSpecValidator.isValidTagMask(req.getTagMask())
                || !req.hasSpec()
                || !TacticalPuzzleSpecValidator.isValidSpec(req.getSpec())) {
            return MatchMethodHandler.Reply.body(SaveTacticalPuzzleDraftResponse.newBuilder().setErrorMessage(tip(ERR_SPEC_INVALID)).build());
        }
        int titleGuard = checkText(playerId, text.title());
        if (titleGuard != 0) {
            return MatchMethodHandler.Reply.body(SaveTacticalPuzzleDraftResponse.newBuilder().setErrorMessage(tip(titleGuard)).build());
        }
        if (!text.hint().isEmpty()) {
            int hintGuard = checkText(playerId, text.hint());
            if (hintGuard != 0) {
                return MatchMethodHandler.Reply.body(SaveTacticalPuzzleDraftResponse.newBuilder().setErrorMessage(tip(hintGuard)).build());
            }
        }
        String authorName = "道友" + Long.toUnsignedString(playerId);
        try {
            TacticalPuzzleSummaryView saved = store.saveDraft(
                    playerId, authorName, req.getPuzzleId(), text.title(), text.hint(),
                    req.getTagMask(), req.getSpec(), nextId);
            return MatchMethodHandler.Reply.body(SaveTacticalPuzzleDraftResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setPuzzle(saved)
                    .build());
        } catch (TacticalPuzzleStore.StoreException e) {
            return MatchMethodHandler.Reply.body(SaveTacticalPuzzleDraftResponse.newBuilder().setErrorMessage(tip(e.errorTip())).build());
        } catch (SQLException e) {
            log.error("[tactical_puzzle] SaveTacticalPuzzleDraft failed player={} puzzle={}",
                    Long.toUnsignedString(playerId), Long.toUnsignedString(req.getPuzzleId()), e);
            return MatchMethodHandler.Reply.body(SaveTacticalPuzzleDraftResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
    }

    private MatchMethodHandler.Reply handleDelete(SessionContext session, ByteString body) throws InvalidProtocolBufferException {
        DeleteTacticalPuzzleRequest req = DeleteTacticalPuzzleRequest.parseFrom(body);
        long playerId = sessionPlayerId(session);
        if (playerId == 0L) {
            return MatchMethodHandler.Reply.body(DeleteTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
        try {
            store.deletePuzzle(playerId, req.getPuzzleId());
            return MatchMethodHandler.Reply.body(DeleteTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(0)).build());
        } catch (TacticalPuzzleStore.StoreException e) {
            return MatchMethodHandler.Reply.body(DeleteTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(e.errorTip())).build());
        } catch (SQLException e) {
            log.error("[tactical_puzzle] DeleteTacticalPuzzle failed player={} puzzle={}",
                    Long.toUnsignedString(playerId), Long.toUnsignedString(req.getPuzzleId()), e);
            return MatchMethodHandler.Reply.body(DeleteTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
    }

    private MatchMethodHandler.Reply handleStartBattle(SessionContext session, ByteString body) throws InvalidProtocolBufferException {
        StartTacticalPuzzleBattleRequest req = StartTacticalPuzzleBattleRequest.parseFrom(body);
        long playerId = sessionPlayerId(session);
        if (playerId == 0L || launcher == null) {
            return MatchMethodHandler.Reply.body(StartTacticalPuzzleBattleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
        try {
            TacticalPuzzleBattleContext pctx = store.prepareLaunch(playerId, req.getPuzzleId(), req.getIsAuthorVerify());
            RankedPresetMeta meta = presetMetaProvider.current();
            RankedMatchContext rankedCtx = RankedMatchContext.newBuilder()
                    .setSeasonId(0)
                    .setRuleVersion(meta.ruleVersion())
                    .setTeamSize(1)
                    .setConfigFingerprint(meta.configFingerprint() == null ? "" : meta.configFingerprint())
                    .addMembers(RankedMemberLoadout.newBuilder()
                            .setPlayerId(playerId)
                            .setPresets(RankedPresetSelection.newBuilder()
                                    .setCharacterPresetId(pctx.getSpec().getCharacterPresetId())
                                    .setPetPresetId(pctx.getSpec().getPetPresetId())
                                    .build())
                            .build())
                    .setUseFreePreset(true)
                    .build();
            long battleId = launcher.launch(playerId, pctx, rankedCtx, req.getRequestId());
            if (battleId == 0L) {
                return MatchMethodHandler.Reply.body(StartTacticalPuzzleBattleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
            }
            return MatchMethodHandler.Reply.body(StartTacticalPuzzleBattleResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setBattleId(battleId)
                    .setSpecRevision(pctx.getSpecRevision())
                    .build());
        } catch (TacticalPuzzleStore.StoreException e) {
            return MatchMethodHandler.Reply.body(StartTacticalPuzzleBattleResponse.newBuilder().setErrorMessage(tip(e.errorTip())).build());
        } catch (SQLException | RuntimeException e) {
            log.error("[tactical_puzzle] StartTacticalPuzzleBattle failed player={} puzzle={}",
                    Long.toUnsignedString(playerId), Long.toUnsignedString(req.getPuzzleId()), e);
            return MatchMethodHandler.Reply.body(StartTacticalPuzzleBattleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
    }

    private MatchMethodHandler.Reply handleLike(SessionContext session, ByteString body) throws InvalidProtocolBufferException {
        LikeTacticalPuzzleRequest req = LikeTacticalPuzzleRequest.parseFrom(body);
        long playerId = sessionPlayerId(session);
        if (playerId == 0L) {
            return MatchMethodHandler.Reply.body(LikeTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
        try {
            TacticalPuzzleStore.LikeResult res = store.likePuzzle(playerId, req.getPuzzleId());
            return MatchMethodHandler.Reply.body(LikeTacticalPuzzleResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setLikeCount(res.likeCount())
                    .setSelfLiked(res.selfLiked())
                    .build());
        } catch (TacticalPuzzleStore.StoreException e) {
            return MatchMethodHandler.Reply.body(LikeTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(e.errorTip())).build());
        } catch (SQLException e) {
            log.error("[tactical_puzzle] LikeTacticalPuzzle failed player={} puzzle={}",
                    Long.toUnsignedString(playerId), Long.toUnsignedString(req.getPuzzleId()), e);
            return MatchMethodHandler.Reply.body(LikeTacticalPuzzleResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
    }

    private MatchMethodHandler.Reply handleGetSolutionReplay(SessionContext session, ByteString body) throws InvalidProtocolBufferException {
        GetTacticalPuzzleSolutionReplayRequest req = GetTacticalPuzzleSolutionReplayRequest.parseFrom(body);
        long playerId = sessionPlayerId(session);
        if (playerId == 0L) {
            return MatchMethodHandler.Reply.body(GetTacticalPuzzleSolutionReplayResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
        try {
            BattleReplayEnvelope replay = store.getSolutionReplay(playerId, req.getPuzzleId());
            return MatchMethodHandler.Reply.body(GetTacticalPuzzleSolutionReplayResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setReplay(replay)
                    .build());
        } catch (TacticalPuzzleStore.StoreException e) {
            return MatchMethodHandler.Reply.body(GetTacticalPuzzleSolutionReplayResponse.newBuilder().setErrorMessage(tip(e.errorTip())).build());
        } catch (SQLException e) {
            log.error("[tactical_puzzle] GetTacticalPuzzleSolutionReplay failed player={} puzzle={}",
                    Long.toUnsignedString(playerId), Long.toUnsignedString(req.getPuzzleId()), e);
            return MatchMethodHandler.Reply.body(GetTacticalPuzzleSolutionReplayResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
        }
    }

    private int checkText(long playerId, String text) {
        if (textGuard == null || text == null || text.isEmpty()) {
            return 0;
        }
        TextVerdict verdict = textGuard.check(playerId, text);
        if (verdict == TextVerdict.UNAVAILABLE) {
            return ERR_INTERNAL;
        }
        if (verdict != TextVerdict.PASS) {
            return ERR_TEXT_REJECTED;
        }
        return 0;
    }

    private static long sessionPlayerId(SessionContext session) {
        return session == null ? 0L : session.getPlayerId();
    }

    private static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }

    @FunctionalInterface
    private interface BodyHandler {
        MatchMethodHandler.Reply handle(SessionContext session, ByteString body) throws InvalidProtocolBufferException;
    }

    private static MatchMethodHandler handler(String method, BodyHandler fn, java.util.function.Supplier<com.google.protobuf.MessageLite> overload) {
        return new MatchMethodHandler() {
            @Override
            public String method() {
                return method;
            }

            @Override
            public Reply handle(SessionContext session, ByteString body, Deadline deadline)
                    throws InvalidProtocolBufferException {
                return fn.handle(session, body);
            }

            @Override
            public Reply onOverload() {
                return Reply.body(overload.get());
            }
        };
    }

    public static int workshopDungeonId() {
        return TacticalPuzzleSpecValidator.WORKSHOP_DUNGEON_ID;
    }
}
