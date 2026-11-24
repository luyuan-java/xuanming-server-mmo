package com.game.match.ranked;

import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.match.dispatch.MatchMethodHandler;
import com.game.match.dispatch.MatchMethods;
import com.game.match.feature.pb.MatchRankedPlayerSeasonRow;
import com.game.match.feature.pb.MatchRankedRewardRow;
import com.game.match.feature.pb.MatchRankedSeasonRow;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.GetRankedBattleResultRequest;
import com.game.proto.match.GetRankedBattleResultResponse;
import com.game.proto.match.GetRankedProfileRequest;
import com.game.proto.match.GetRankedProfileResponse;
import com.game.proto.match.RankedBattleSettlementView;
import com.game.proto.match.RankedMatchRatingStatus;
import com.game.proto.match.SaveRankedPresetRequest;
import com.game.proto.match.SaveRankedPresetResponse;
import com.game.proto.match.UpdateRankedCosmeticRequest;
import com.game.proto.match.UpdateRankedCosmeticResponse;
import com.game.table.MatchErrorTip;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 论剑排位客户端四条协议服务（448 GetRankedProfile / 449 SaveRankedPreset / 450 UpdateRankedCosmetic / 451 GetRankedBattleResult）。 */
public final class RankedService {

    private static final Logger log = LoggerFactory.getLogger(RankedService.class);

    private static final int ERR_INTERNAL = MatchErrorTip.match_error.kMatchInternal_VALUE;
    private static final int ERR_SETTLING = MatchErrorTip.match_error.kMatchRankedSeasonSettling_VALUE;
    private static final int ERR_PRESET_INVALID = MatchErrorTip.match_error.kMatchRankedPresetInvalid_VALUE;
    private static final int ERR_RULE_MISMATCH = MatchErrorTip.match_error.kMatchRankedRuleVersionMismatch_VALUE;

    private final RankedStore store;
    private final RankedTables tables;
    private final LongSupplier clockMs;

    public RankedService(RankedStore store, RankedTables tables, LongSupplier clockMs) {
        this.store = Objects.requireNonNull(store, "store");
        this.tables = Objects.requireNonNull(tables, "tables");
        this.clockMs = clockMs == null ? System::currentTimeMillis : clockMs;
    }

    public GetRankedProfileResponse getProfile(long playerId, GetRankedProfileRequest req) {
        if (playerId == 0L) {
            return GetRankedProfileResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            RankedStore.ProfileData data = store.loadProfile(playerId, req.getIncludeHistory());
            MatchRankedSeasonRow cur = data.currentSeason();
            GetRankedProfileResponse.Builder resp = GetRankedProfileResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setCurrentSeason(store.toSeasonInfo(cur))
                    .setCurrentProgress(store.toProgressSummary(data.currentProgress(), cur.getSeasonKey()))
                    .setSelectedPresets(store.toPresetSelection(data.prefs()))
                    .setCosmeticLoadout(store.toCosmeticLoadout(data.prefs(), data.entitlements()))
                    .addAllPresets(tables.presetViews())
                    .setServerTimeMs(clockMs.getAsLong());
            for (MatchRankedRewardRow r : data.entitlements()) {
                resp.addEntitlements(RankedStore.toEntitlement(r));
            }
            if (req.getIncludeHistory()) {
                Map<Integer, String> keysById = new HashMap<>();
                for (MatchRankedSeasonRow past : data.pastSeasons()) {
                    keysById.put(past.getSeasonId(), past.getSeasonKey());
                    resp.addPastSeasons(store.toSeasonInfo(past));
                }
                for (MatchRankedPlayerSeasonRow hist : data.history()) {
                    String key = keysById.getOrDefault(hist.getSeasonId(), "s" + hist.getSeasonId());
                    resp.addHistory(store.toProgressSummary(hist, key));
                }
            }
            return resp.build();
        } catch (SQLException e) {
            log.error("[ranked] getProfile failed player={}", Long.toUnsignedString(playerId), e);
            return GetRankedProfileResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public SaveRankedPresetResponse savePreset(long playerId, SaveRankedPresetRequest req) {
        if (playerId == 0L) {
            return SaveRankedPresetResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            RankedStore.PrefsWriteResult res = store.savePreset(playerId,
                    req.getCharacterPresetId(), req.getPetPresetId(), req.getRuleVersion());
            return switch (res.status()) {
                case OK -> SaveRankedPresetResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .setSelectedPresets(store.toPresetSelection(res.row()))
                        .setRuleVersion(res.ruleVersion())
                        .build();
                case SEASON_SETTLING -> SaveRankedPresetResponse.newBuilder()
                        .setErrorMessage(tip(ERR_SETTLING)).setRuleVersion(res.ruleVersion()).build();
                case RULE_VERSION_MISMATCH -> SaveRankedPresetResponse.newBuilder()
                        .setErrorMessage(tip(ERR_RULE_MISMATCH)).setRuleVersion(res.ruleVersion()).build();
                case PRESET_INVALID -> SaveRankedPresetResponse.newBuilder()
                        .setErrorMessage(tip(ERR_PRESET_INVALID)).setRuleVersion(res.ruleVersion()).build();
            };
        } catch (SQLException e) {
            log.error("[ranked] savePreset failed player={}", Long.toUnsignedString(playerId), e);
            return SaveRankedPresetResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public UpdateRankedCosmeticResponse updateCosmetic(long playerId, UpdateRankedCosmeticRequest req) {
        if (playerId == 0L) {
            return UpdateRankedCosmeticResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        if (!req.hasLoadout()) {
            return UpdateRankedCosmeticResponse.newBuilder().setErrorMessage(tip(ERR_PRESET_INVALID)).build();
        }
        try {
            RankedStore.PrefsWriteResult res = store.updateCosmetic(playerId, req.getLoadout());
            if (res.status() != RankedStore.WriteStatus.OK) {
                return UpdateRankedCosmeticResponse.newBuilder().setErrorMessage(tip(ERR_PRESET_INVALID)).build();
            }
            RankedStore.ProfileData data = store.loadProfile(playerId, false);
            return UpdateRankedCosmeticResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setLoadout(store.toCosmeticLoadout(res.row(), data.entitlements()))
                    .build();
        } catch (SQLException e) {
            log.error("[ranked] updateCosmetic failed player={}", Long.toUnsignedString(playerId), e);
            return UpdateRankedCosmeticResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public GetRankedBattleResultResponse getBattleResult(long playerId, GetRankedBattleResultRequest req) {
        if (playerId == 0L) {
            return GetRankedBattleResultResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
        try {
            Optional<RankedBattleSettlementView> found = store.getBattleResult(playerId, req.getBattleId());
            if (found.isEmpty()) {
                if (req.getBattleId() != 0L) {
                    return GetRankedBattleResultResponse.newBuilder()
                            .setErrorMessage(tip(0))
                            .setResult(RankedBattleSettlementView.newBuilder()
                                    .setBattleId(req.getBattleId())
                                    .setStatus(RankedMatchRatingStatus.RANKED_MATCH_RATING_PENDING)
                                    .build())
                            .build();
                }
                return GetRankedBattleResultResponse.newBuilder().setErrorMessage(tip(0)).build();
            }
            return GetRankedBattleResultResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setResult(found.get())
                    .build();
        } catch (SQLException e) {
            log.error("[ranked] getBattleResult failed player={} battle={}",
                    Long.toUnsignedString(playerId), Long.toUnsignedString(req.getBattleId()), e);
            return GetRankedBattleResultResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build();
        }
    }

    public List<MatchMethodHandler> clientHandlers() {
        return List.of(
                new MatchMethodHandler() {
                    @Override public String method() { return MatchMethods.GET_RANKED_PROFILE; }
                    @Override public Reply handle(SessionContext s, ByteString b, Deadline d) throws InvalidProtocolBufferException {
                        return Reply.body(getProfile(s == null ? 0L : s.getPlayerId(), GetRankedProfileRequest.parseFrom(b)));
                    }
                    @Override public Reply onOverload() {
                        return Reply.body(GetRankedProfileResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                    }
                },
                new MatchMethodHandler() {
                    @Override public String method() { return MatchMethods.SAVE_RANKED_PRESET; }
                    @Override public Reply handle(SessionContext s, ByteString b, Deadline d) throws InvalidProtocolBufferException {
                        return Reply.body(savePreset(s == null ? 0L : s.getPlayerId(), SaveRankedPresetRequest.parseFrom(b)));
                    }
                    @Override public Reply onOverload() {
                        return Reply.body(SaveRankedPresetResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                    }
                },
                new MatchMethodHandler() {
                    @Override public String method() { return MatchMethods.UPDATE_RANKED_COSMETIC; }
                    @Override public Reply handle(SessionContext s, ByteString b, Deadline d) throws InvalidProtocolBufferException {
                        return Reply.body(updateCosmetic(s == null ? 0L : s.getPlayerId(), UpdateRankedCosmeticRequest.parseFrom(b)));
                    }
                    @Override public Reply onOverload() {
                        return Reply.body(UpdateRankedCosmeticResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                    }
                },
                new MatchMethodHandler() {
                    @Override public String method() { return MatchMethods.GET_RANKED_BATTLE_RESULT; }
                    @Override public Reply handle(SessionContext s, ByteString b, Deadline d) throws InvalidProtocolBufferException {
                        return Reply.body(getBattleResult(s == null ? 0L : s.getPlayerId(), GetRankedBattleResultRequest.parseFrom(b)));
                    }
                    @Override public Reply onOverload() {
                        return Reply.body(GetRankedBattleResultResponse.newBuilder().setErrorMessage(tip(ERR_INTERNAL)).build());
                    }
                });
    }

    private static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }
}
