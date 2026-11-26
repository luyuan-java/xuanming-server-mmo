package com.game.scene.season;

import static com.game.scene.world.SceneMessageIds.tip;
import static com.game.table.SeasonErrorTip.season_error.*;

import com.game.battle.engine.SeasonRules;
import com.game.player.store.state.ClientRequestRecordState;
import com.game.player.store.state.PlayerSeasonSealRecordState;
import com.game.proto.BattleSettlementData;
import com.game.proto.CatchupSeasonSealRequest;
import com.game.proto.CatchupSeasonSealResponse;
import com.game.proto.EquipSeasonSealRequest;
import com.game.proto.EquipSeasonSealResponse;
import com.game.proto.EquipmentEffects;
import com.game.proto.GetSeasonStateResponse;
import com.game.proto.SeasonHonorView;
import com.game.proto.SeasonSealEffect;
import com.game.proto.SeasonSealOption;
import com.game.proto.SeasonSkillSummary;
import com.game.proto.SeasonStateSnapshot;
import com.game.proto.SeasonWeeklyAffixView;
import com.game.proto.SetRetroHonorTargetRequest;
import com.game.proto.SetRetroHonorTargetResponse;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.attribute.AttributeService;
import com.game.scene.audit.AssetAudit;
import com.game.scene.currency.CurrencyService;
import com.game.scene.equip.EquipmentCraftService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerClientRequestLedger.Verdict;
import com.game.scene.player.PlayerLevels;
import com.game.scene.player.PlayerSeason;
import com.game.scene.player.Wallet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;
import com.game.table.SeasonRuleTable;
import com.game.table.SeasonSealTable;
import com.game.table.SkillTable;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 赛季技能与单槽赛季印场景侧服务（453–455、569；对齐 C++ {@code PlayerSeasonSystem} V4）。
 * 仅在场景逻辑线程调用。
 */
public final class SeasonService {

    private static final Logger log = LoggerFactory.getLogger(SeasonService.class);
    static final int OK = 0;
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;

    public record EquipOutcome(EquipSeasonSealResponse response, boolean changed) {
    }

    public record CatchupOutcome(CatchupSeasonSealResponse response, boolean changed) {
    }

    public record RetroHonorTargetOutcome(SetRetroHonorTargetResponse response, boolean changed) {
    }

    public record BattleCreditOutcome(int seasonId, boolean pointsIncreased,
                                      int pointsAdded, int progressAfter, int maxPoints,
                                      boolean honorNewlyUnlocked,
                                      int retroHonorSeasonId, int retroHonorPointsAdded,
                                      int retroHonorProgressAfter, int retroHonorMaxPoints,
                                      boolean retroHonorNewlyUnlocked) {
        public static final BattleCreditOutcome NONE =
                new BattleCreditOutcome(0, false, 0, 0, 0, false, 0, 0, 0, 0, false);
    }

    private record EffectiveProgress(int progress, int battlePoints, int catchupPoints) {
        static final EffectiveProgress ZERO = new EffectiveProgress(0, 0, 0);
    }

    private final SeasonTables tables;
    private final CurrencyService currency;
    private final AttributeService attributes;
    private final SceneClock clock;
    private final SceneMetrics metrics;

    public SeasonService(SeasonTables tables, CurrencyService currency, AttributeService attributes,
                         SceneClock clock, SceneMetrics metrics) {
        this.tables = Objects.requireNonNull(tables);
        this.currency = Objects.requireNonNull(currency);
        this.attributes = Objects.requireNonNull(attributes);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public long nowMillis() {
        return clock.epochMillis();
    }

    public SeasonTables tables() {
        return tables;
    }

    public int activeSeasonId() {
        return activeSeasonId(nowMillis());
    }

    public int activeSeasonId(long nowMs) {
        SeasonRuleTable rule = tables.activeSeason(nowMs);
        return rule == null ? 0 : rule.getId();
    }

    public int activeWeeklyAffixId(long nowMs) {
        SeasonTables.ActiveSeason active = tables.resolveActive(nowMs);
        if (active == null) {
            return SeasonRules.WEEKLY_AFFIX_NONE;
        }
        int affixSeed = active.resolved().cyclePoolSize() > 0
                ? active.resolved().seasonTerm() : active.rule().getId();
        return SeasonRules.weeklyAffixAt(affixSeed, active.resolved().termStartMs(), nowMs);
    }

    /** 向角色 {@link EquipmentEffects} 注入当季轻属性（气血/防御 ≤ 500 bps）与单槽赛季印机制。 */
    public void collectEffects(ScenePlayer player, long nowMs, EquipmentEffects.Builder out) {
        out.clearSeasonSeal().setSeasonMaxHealthBps(0).setSeasonDefenseBps(0);
        if (player == null) {
            return;
        }
        SeasonTables.ActiveSeason active = tables.resolveActive(nowMs);
        if (active == null) {
            return;
        }
        SeasonRuleTable rule = active.rule();
        PlayerSeasonSealRecordState record = player.season().record(rule.getId());
        int sealId = effectiveSealId(rule, record);
        SeasonSealTable seal = tables.validSealForSeason(rule.getId(), sealId);
        if (seal == null) {
            return;
        }
        int progress = readEffectiveTermProgress(record, active.resolved().seasonTerm(), rule.getSealMaxPoints()).progress();
        out.setSeasonMaxHealthBps(SeasonRules.scaledBonusBps(progress, rule.getSealMaxPoints(), rule.getMaxHealthBpsCap()));
        out.setSeasonDefenseBps(SeasonRules.scaledBonusBps(progress, rule.getSealMaxPoints(), rule.getDefenseBpsCap()));
        out.setSeasonSeal(toSealEffect(rule.getId(), seal));
    }

    /** 收集当季全部合法赛季印快照（按 seal_id 无符号升序，供论剑 V3 战前 15 秒明牌变阵临场切换）。 */
    public List<SeasonSealEffect> collectAvailableSeals(long nowMs) {
        SeasonRuleTable rule = tables.activeSeason(nowMs);
        if (rule == null) {
            return List.of();
        }
        List<SeasonSealTable> validSeals = tables.validSealsOf(rule.getId());
        if (validSeals.isEmpty()) {
            return List.of();
        }
        List<SeasonSealEffect> out = new ArrayList<>(validSeals.size());
        for (SeasonSealTable seal : validSeals) {
            out.add(toSealEffect(rule.getId(), seal));
        }
        return out;
    }

    private static SeasonSealEffect toSealEffect(int seasonId, SeasonSealTable seal) {
        return SeasonSealEffect.newBuilder()
                .setSeasonId(seasonId)
                .setSealId(seal.getId())
                .setEffectId(seal.getEffectId())
                .setBonusBps(seal.getBonusBps())
                .setCapBps(seal.getCapBps())
                .setPerRoundLimit(seal.getPerRoundLimit())
                .setBattleLimit(seal.getBattleLimit())
                .setActiveRounds(seal.getActiveRounds())
                .setHealthThresholdBps(seal.getHealthThresholdBps())
                .setName(seal.getName())
                .setResonanceSkillId(seal.getResonanceSkillId())
                .setResonanceKind(seal.getResonanceKind())
                .setResonanceParam(seal.getResonanceParam())
                .build();
    }

    /** 453 纯只读查询当前赛季状态、赛季印列表与本季免费技能。 */
    public GetSeasonStateResponse getState(ScenePlayer player, long nowMs) {
        return GetSeasonStateResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setState(buildSnapshot(player, nowMs))
                .build();
    }

    /** 454 战外免费切换当前赛季印，立即重算属性。 */
    public EquipOutcome equipSeal(ScenePlayer player, EquipSeasonSealRequest request, long nowMs) {
        SeasonTables.ActiveSeason active = tables.resolveActive(nowMs);
        if (active == null) {
            return new EquipOutcome(equipReply(player, nowMs, kSeasonNotOpen_VALUE), false);
        }
        SeasonRuleTable rule = active.rule();
        if (request.getSeasonId() == 0 || request.getSeasonId() != rule.getId()) {
            return new EquipOutcome(equipReply(player, nowMs, kSeasonMismatch_VALUE), false);
        }
        if (tables.validSealForSeason(rule.getId(), request.getSealId()) == null) {
            return new EquipOutcome(equipReply(player, nowMs, kSeasonSealInvalid_VALUE), false);
        }
        int writableErr = checkWritable(player, true);
        if (writableErr != OK) {
            return new EquipOutcome(equipReply(player, nowMs, writableErr), false);
        }
        PlayerSeasonSealRecordState existing = player.season().record(rule.getId());
        if (effectiveSealId(rule, existing) == request.getSealId()) {
            return new EquipOutcome(equipReply(player, nowMs, kSeasonSealAlreadyEquipped_VALUE), false);
        }
        PlayerSeasonSealRecordState.Builder next = syncRecordForTerm(player.season(), rule, active.resolved().seasonTerm());
        next.setEquippedSealId(request.getSealId());
        if (next.getRevision() != -1L) {
            next.setRevision(next.getRevision() + 1L);
        }
        player.season().putRecord(next.build());
        attributes.equipmentChanged(player);
        log.info("season_seal_equipped player={} season_id={} seal_id={}",
                Long.toUnsignedString(player.playerId()), rule.getId(), request.getSealId());
        return new EquipOutcome(equipReply(player, nowMs, OK), true);
    }

    /** 455 幂等小额追赶赛季印进度（重放查账优先于冻结/战斗闸；绝不溢出至往季追忆）。 */
    public CatchupOutcome catchup(ScenePlayer player, CatchupSeasonSealRequest request, long nowMs) {
        long reqId = request.getClientRequestId();
        if (reqId == 0) {
            return new CatchupOutcome(catchupReply(player, nowMs, INVALID_PARAMETER, false, 0, 0), false);
        }
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return new CatchupOutcome(catchupReply(player, nowMs, SERVICE_UNAVAILABLE, false, 0, 0), false);
        }
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(request.toBuilder().clearClientRequestId().build());
        var check = ledger.check(reqId, PlayerClientRequestLedger.KIND_CATCHUP_SEASON_SEAL, fingerprint);
        if (check.verdict() == Verdict.REPLAY) {
            ClientRequestRecordState first = check.record();
            return new CatchupOutcome(catchupReply(player, nowMs, OK, true,
                    first.getSeasonCatchupPoints(), first.getSeasonCatchupSpent()), false);
        }
        if (check.verdict() == Verdict.MISMATCH) {
            return new CatchupOutcome(catchupReply(player, nowMs, INVALID_PARAMETER, false, 0, 0), false);
        }
        SeasonTables.ActiveSeason active = tables.resolveActive(nowMs);
        if (active == null) {
            return new CatchupOutcome(catchupReply(player, nowMs, kSeasonNotOpen_VALUE, false, 0, 0), false);
        }
        SeasonRuleTable rule = active.rule();
        if (request.getSeasonId() == 0 || request.getSeasonId() != rule.getId()) {
            return new CatchupOutcome(catchupReply(player, nowMs, kSeasonMismatch_VALUE, false, 0, 0), false);
        }
        if (rule.getCatchupPricePerPoint() == 0) {
            return new CatchupOutcome(catchupReply(player, nowMs, kSeasonCatchupDisabled_VALUE, false, 0, 0), false);
        }
        int writableErr = checkWritable(player, true);
        if (writableErr != OK) {
            return new CatchupOutcome(catchupReply(player, nowMs, writableErr, false, 0, 0), false);
        }
        PlayerSeasonSealRecordState existing = player.season().record(rule.getId());
        int currentPoints = readEffectiveTermProgress(existing, active.resolved().seasonTerm(), rule.getSealMaxPoints()).progress();
        if (Integer.compareUnsigned(currentPoints, rule.getSealMaxPoints()) >= 0) {
            return new CatchupOutcome(catchupReply(player, nowMs, kSeasonSealAlreadyMax_VALUE, false, 0, 0), false);
        }
        SeasonRules.CatchupPlan plan = SeasonRules.computeCatchupPlan(
                currentPoints, rule.getSealMaxPoints(), request.getPointsToBuy(), rule.getCatchupPricePerPoint());
        if (plan == null
                || request.getExpectedCurrencyType() != rule.getCatchupCurrencyType()
                || request.getExpectedTotalPrice() != plan.totalPrice()) {
            return new CatchupOutcome(catchupReply(player, nowMs, kSeasonCatchupPriceChanged_VALUE, false, 0, 0), false);
        }
        if (player.wallet().balance(rule.getCatchupCurrencyType()) < plan.totalPrice()) {
            return new CatchupOutcome(catchupReply(player, nowMs, kSeasonCatchupCurrencyNotEnough_VALUE, false, 0, 0), false);
        }
        Wallet.Change deducted = currency.deduct(player, rule.getCatchupCurrencyType(), plan.totalPrice(),
                AssetAudit.Reason.SEASON_SEAL_CATCHUP, reqId);
        if (!deducted.ok()) {
            return new CatchupOutcome(catchupReply(player, nowMs, deducted.tipId(), false, 0, 0), false);
        }
        PlayerSeasonSealRecordState.Builder next = syncRecordForTerm(player.season(), rule, active.resolved().seasonTerm());
        if (next.getEquippedSealId() == 0) {
            next.setEquippedSealId(rule.getDefaultSealId());
        }
        int afterPoints = currentPoints + plan.pointsToBuy();
        next.setProgressPoints(afterPoints);
        next.setCatchupPoints(next.getCatchupPoints() + plan.pointsToBuy());
        if (Integer.compareUnsigned(afterPoints, rule.getSealMaxPoints()) >= 0) {
            player.season().setHonorBit(rule.getId());
            player.season().removeRetroHonorProgress(rule.getId());
            if (player.season().retroHonorTargetSeasonId() == rule.getId()) {
                player.season().setRetroHonorTargetSeasonId(0);
            }
        }
        if (next.getRevision() != -1L) {
            next.setRevision(next.getRevision() + 1L);
        }
        player.season().putRecord(next.build());
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(reqId)
                .setKind(PlayerClientRequestLedger.KIND_CATCHUP_SEASON_SEAL)
                .setFingerprint(fingerprint)
                .setSeasonCatchupPoints(plan.pointsToBuy())
                .setSeasonCatchupSpent(plan.totalPrice()));
        attributes.equipmentChanged(player);
        log.info("season_seal_catchup player={} season_id={} added={} progress={}/{} spent={}",
                Long.toUnsignedString(player.playerId()), rule.getId(), plan.pointsToBuy(),
                next.getProgressPoints(), rule.getSealMaxPoints(), plan.totalPrice());
        return new CatchupOutcome(catchupReply(player, nowMs, OK, false, plan.pointsToBuy(), plan.totalPrice()), true);
    }

    /** 569 设定/清除「往季徽章追忆」指定目标（0=自动顺延首个未解锁往季；纯免费零收费）。 */
    public RetroHonorTargetOutcome setRetroHonorTarget(ScenePlayer player, SetRetroHonorTargetRequest request, long nowMs) {
        int writableErr = checkWritable(player, true);
        if (writableErr != OK) {
            return new RetroHonorTargetOutcome(retroTargetReply(player, nowMs, writableErr), false);
        }
        syncLegacyHonorsToBitmap(player.season());
        int targetSeasonId = request.getSeasonId();
        if (targetSeasonId == 0) {
            player.season().setRetroHonorTargetSeasonId(0);
            return new RetroHonorTargetOutcome(retroTargetReply(player, nowMs, OK), true);
        }
        SeasonRuleTable targetRule = tables.ruleById(targetSeasonId);
        SeasonRuleTable activeRule = tables.activeSeason(nowMs);
        if (targetRule == null || targetRule.getSealMaxPoints() == 0
                || (activeRule != null && activeRule.getId() == targetSeasonId)) {
            return new RetroHonorTargetOutcome(retroTargetReply(player, nowMs, kSeasonHonorTargetInvalid_VALUE), false);
        }
        if (isSeasonHonorUnlocked(player.season(), targetSeasonId)) {
            return new RetroHonorTargetOutcome(retroTargetReply(player, nowMs, kSeasonHonorAlreadyUnlocked_VALUE), false);
        }
        player.season().setRetroHonorTargetSeasonId(targetSeasonId);
        log.info("season_retro_honor_target_updated player={} target_season_id={}",
                Long.toUnsignedString(player.playerId()), targetSeasonId);
        return new RetroHonorTargetOutcome(retroTargetReply(player, nowMs, OK), true);
    }

    /** 结算应用同帧原子累计本季进度点，满级后免费溢出至「往季徽章追忆」。 */
    public BattleCreditOutcome creditBattlePoints(ScenePlayer player, BattleSettlementData settlement, long nowMs) {
        if (!isEligibleBattle(settlement)) {
            return BattleCreditOutcome.NONE;
        }
        SeasonTables.ActiveSeason active = tables.resolveActive(nowMs);
        if (active == null) {
            return BattleCreditOutcome.NONE;
        }
        SeasonRuleTable rule = active.rule();
        int gain = battlePointsFor(rule, settlement);
        if (gain == 0) {
            return new BattleCreditOutcome(rule.getId(), false, 0, 0, 0, false, 0, 0, 0, 0, false);
        }
        PlayerSeason ps = player.season();
        PlayerSeasonSealRecordState.Builder next = syncRecordForTerm(ps, rule, active.resolved().seasonTerm());
        if (next.getEquippedSealId() == 0) {
            next.setEquippedSealId(rule.getDefaultSealId());
        }
        int maxPoints = rule.getSealMaxPoints();
        int before = Math.min(next.getProgressPoints(), maxPoints);
        boolean wasAlreadyUnlocked = isSeasonHonorUnlocked(ps, rule.getId());
        int usedForCurrent = before < maxPoints ? Math.min(gain, maxPoints - before) : 0;
        int after = before + usedForCurrent;
        boolean currentHonorNewlyUnlocked = false;
        boolean pointsIncreased = false;
        if (usedForCurrent > 0) {
            next.setProgressPoints(after);
            next.setBattlePoints(next.getBattlePoints() + usedForCurrent);
            pointsIncreased = true;
            if (after >= maxPoints) {
                boolean newlySet = ps.setHonorBit(rule.getId());
                currentHonorNewlyUnlocked = newlySet && !wasAlreadyUnlocked;
                ps.removeRetroHonorProgress(rule.getId());
                if (ps.retroHonorTargetSeasonId() == rule.getId()) {
                    ps.setRetroHonorTargetSeasonId(0);
                }
            }
        }
        ps.putRecord(next.build());
        if (usedForCurrent > 0) {
            attributes.equipmentChanged(player);
        }

        int retroSeasonId = 0;
        int retroAdded = 0;
        int retroAfter = 0;
        int retroMax = 0;
        boolean retroUnlocked = false;
        int overflow = gain - usedForCurrent;
        if (overflow > 0) {
            int retroTargetId = resolveRetroHonorTarget(ps, rule.getId(), null);
            if (retroTargetId != 0) {
                SeasonRuleTable targetRule = tables.ruleById(retroTargetId);
                if (targetRule != null && targetRule.getSealMaxPoints() > 0) {
                    int targetMax = targetRule.getSealMaxPoints();
                    int prevRetro = Math.min(ps.retroHonorProgress(retroTargetId), targetMax);
                    int add = prevRetro < targetMax ? Math.min(overflow, targetMax - prevRetro) : 0;
                    int afterRetro = prevRetro + add;
                    if (add > 0) {
                        pointsIncreased = true;
                        retroSeasonId = retroTargetId;
                        retroAdded = add;
                        retroAfter = afterRetro;
                        retroMax = targetMax;
                        if (afterRetro >= targetMax) {
                            retroUnlocked = ps.setHonorBit(retroTargetId);
                            ps.removeRetroHonorProgress(retroTargetId);
                            if (ps.retroHonorTargetSeasonId() == retroTargetId) {
                                ps.setRetroHonorTargetSeasonId(0);
                            }
                        } else {
                            ps.putRetroHonorProgress(retroTargetId, afterRetro);
                        }
                    }
                }
            }
        }
        return new BattleCreditOutcome(rule.getId(), pointsIncreased, usedForCurrent, after, maxPoints,
                currentHonorNewlyUnlocked, retroSeasonId, retroAdded, retroAfter, retroMax, retroUnlocked);
    }

    public static boolean isEligibleBattle(BattleSettlementData settlement) {
        if (settlement.getBattleId() == 0 || settlement.getPlayerId() == 0 || settlement.getFled()
                || settlement.getTotalRounds() == 0 || Integer.compareUnsigned(settlement.getPlayerTeamIndex(), 1) > 0
                || com.game.battle.engine.BattleConstants.isNpcIsolatedEncounterKind(settlement.getBossTrialKind())) {
            return false;
        }
        eBattleOutcome outcome = settlement.getOutcome();
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN
                && outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN
                && outcome != eBattleOutcome.BATTLE_OUTCOME_DRAW) {
            return false;
        }
        return switch (settlement.getMatchMode()) {
            case MatchMode.MATCH_MODE_PVE_SOLO_VALUE, MatchMode.MATCH_MODE_PVE_TEAM_VALUE -> playerWon(settlement);
            case MatchMode.MATCH_MODE_1V1_VALUE, MatchMode.MATCH_MODE_5V5_VALUE, MatchMode.MATCH_MODE_PVP_TEAM_VALUE -> {
                if (!settlement.getCompetitiveRanked()) {
                    yield true;
                }
                if (!settlement.getParticipated()) {
                    yield false;
                }
                if (playerWon(settlement)) {
                    yield true;
                }
                // 与装备材料总账及 C++ PlayerSeasonSystem 同口径：正式排位败局/平局须满足防挂机门槛。
                yield Integer.compareUnsigned(settlement.getTotalRounds(), EquipmentCraftService.PVP_MIN_ROUNDS_FOR_LOSS) >= 0
                        && Integer.compareUnsigned(settlement.getManualActionRounds(), EquipmentCraftService.PVP_MIN_MANUAL_ROUNDS_FOR_LOSS) >= 0
                        && settlement.getEffectiveCombatContribution();
            }
            default -> false;
        };
    }

    private static boolean playerWon(BattleSettlementData settlement) {
        return (settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && settlement.getPlayerTeamIndex() == 0)
                || (settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN && settlement.getPlayerTeamIndex() == 1);
    }

    private static int battlePointsFor(SeasonRuleTable rule, BattleSettlementData settlement) {
        return switch (settlement.getMatchMode()) {
            case MatchMode.MATCH_MODE_PVE_SOLO_VALUE, MatchMode.MATCH_MODE_PVE_TEAM_VALUE ->
                    playerWon(settlement) ? rule.getPveWinPoints() : 0;
            case MatchMode.MATCH_MODE_1V1_VALUE, MatchMode.MATCH_MODE_5V5_VALUE, MatchMode.MATCH_MODE_PVP_TEAM_VALUE ->
                    playerWon(settlement) ? rule.getPvpWinPoints() : rule.getPvpOtherPoints();
            default -> 0;
        };
    }

    private boolean isSeasonHonorUnlocked(PlayerSeason ps, int seasonId) {
        if (seasonId == 0) {
            return false;
        }
        if (ps.isHonorBitSet(seasonId)) {
            return true;
        }
        SeasonRuleTable rule = tables.ruleById(seasonId);
        if (rule == null || rule.getSealMaxPoints() == 0) {
            return false;
        }
        PlayerSeasonSealRecordState rec = ps.record(seasonId);
        return rec != null && Integer.compareUnsigned(rec.getProgressPoints(), rule.getSealMaxPoints()) >= 0;
    }

    private void syncLegacyHonorsToBitmap(PlayerSeason ps) {
        for (int sid : ps.seasonIds()) {
            SeasonRuleTable rule = tables.ruleById(sid);
            PlayerSeasonSealRecordState rec = ps.record(sid);
            if (rule != null && rule.getSealMaxPoints() > 0 && rec != null
                    && Integer.compareUnsigned(rec.getProgressPoints(), rule.getSealMaxPoints()) >= 0) {
                ps.setHonorBit(sid);
                ps.removeRetroHonorProgress(sid);
                if (ps.retroHonorTargetSeasonId() == sid) {
                    ps.setRetroHonorTargetSeasonId(0);
                }
            }
        }
    }

    private static EffectiveProgress readEffectiveTermProgress(PlayerSeasonSealRecordState rec,
                                                               int currentTerm, int maxPoints) {
        if (rec == null || maxPoints == 0) {
            return EffectiveProgress.ZERO;
        }
        boolean sameTerm = rec.getLastTerm() == currentTerm || (rec.getLastTerm() == 0 && currentTerm == 1);
        if (!sameTerm) {
            return EffectiveProgress.ZERO;
        }
        int progress = Math.min(rec.getProgressPoints(), maxPoints);
        int battlePts = Math.min(rec.getBattlePoints(), progress);
        int catchupPts = Math.min(rec.getCatchupPoints(), progress);
        return new EffectiveProgress(progress, battlePts, catchupPts);
    }

    private PlayerSeasonSealRecordState.Builder syncRecordForTerm(PlayerSeason ps, SeasonRuleTable rule, int currentTerm) {
        syncLegacyHonorsToBitmap(ps);
        PlayerSeasonSealRecordState existing = ps.record(rule.getId());
        PlayerSeasonSealRecordState.Builder builder = existing == null
                ? PlayerSeasonSealRecordState.newBuilder().setSeasonId(rule.getId())
                : existing.toBuilder();
        if (builder.getLastTerm() == 0 && currentTerm == 1) {
            builder.setLastTerm(1);
        } else if (builder.getLastTerm() != currentTerm) {
            builder.setProgressPoints(0)
                    .setBattlePoints(0)
                    .setCatchupPoints(0)
                    .setLastTerm(currentTerm);
        }
        return builder;
    }

    private int resolveRetroHonorTarget(PlayerSeason ps, int activeSeasonId, boolean[] outExplicit) {
        if (outExplicit != null && outExplicit.length > 0) {
            outExplicit[0] = false;
        }
        int explicitId = ps.retroHonorTargetSeasonId();
        if (explicitId != 0 && explicitId != activeSeasonId) {
            SeasonRuleTable rule = tables.ruleById(explicitId);
            if (rule != null && rule.getSealMaxPoints() > 0 && !isSeasonHonorUnlocked(ps, explicitId)) {
                if (outExplicit != null && outExplicit.length > 0) {
                    outExplicit[0] = true;
                }
                return explicitId;
            }
        }
        for (int sid : tables.allRuleIdsAsc()) {
            SeasonRuleTable rule = tables.ruleById(sid);
            if (sid != activeSeasonId && rule != null && rule.getSealMaxPoints() > 0 && !isSeasonHonorUnlocked(ps, sid)) {
                return sid;
            }
        }
        return 0;
    }

    private int effectiveSealId(SeasonRuleTable rule, PlayerSeasonSealRecordState record) {
        if (record != null && record.getEquippedSealId() != 0
                && tables.validSealForSeason(rule.getId(), record.getEquippedSealId()) != null) {
            return record.getEquippedSealId();
        }
        return rule.getDefaultSealId();
    }

    private int checkWritable(ScenePlayer player, boolean countMetric) {
        if (player.frozen()) {
            return kSeasonFrozen_VALUE;
        }
        if (player.inBattle() || player.battle().recovery() != com.game.scene.battle.PlayerBattle.Recovery.READY) {
            if (countMetric) {
                metrics.battleGateReject(SceneMetrics.BattleGate.SEASON);
            }
            return kSeasonInBattle_VALUE;
        }
        return OK;
    }

    private EquipSeasonSealResponse equipReply(ScenePlayer player, long nowMs, int tipId) {
        return EquipSeasonSealResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setState(buildSnapshot(player, nowMs))
                .build();
    }

    private CatchupSeasonSealResponse catchupReply(ScenePlayer player, long nowMs, int tipId,
                                                   boolean replayed, int addedPoints, long spentCurrency) {
        return CatchupSeasonSealResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setState(buildSnapshot(player, nowMs))
                .setReplayed(replayed)
                .setAddedPoints(addedPoints)
                .setSpentCurrency(spentCurrency)
                .build();
    }

    private SetRetroHonorTargetResponse retroTargetReply(ScenePlayer player, long nowMs, int tipId) {
        return SetRetroHonorTargetResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setState(buildSnapshot(player, nowMs))
                .build();
    }

    private SeasonStateSnapshot buildSnapshot(ScenePlayer player, long nowMs) {
        SeasonStateSnapshot.Builder out = SeasonStateSnapshot.newBuilder().setServerNowMs(nowMs);
        SeasonTables.ActiveSeason active = tables.resolveActive(nowMs);
        int activeSeasonId = active == null ? 0 : active.rule().getId();
        boolean[] retroExplicit = new boolean[1];
        int retroTargetId = resolveRetroHonorTarget(player.season(), activeSeasonId, retroExplicit);
        out.setRetroHonorTargetSeasonId(retroTargetId)
                .setRetroHonorTargetExplicit(retroExplicit[0]);
        if (active == null) {
            fillHonors(player, 0, 0, retroTargetId, out);
            return out.setAvailable(false).setUnavailableReason("当前暂无进行中的赛季").build();
        }
        SeasonRuleTable rule = active.rule();
        SeasonRules.ResolvedSeason resolved = active.resolved();
        fillHonors(player, rule.getId(), resolved.seasonTerm(), retroTargetId, out);
        PlayerSeasonSealRecordState record = player.season().record(rule.getId());
        int equippedId = effectiveSealId(rule, record);
        EffectiveProgress eff = readEffectiveTermProgress(record, resolved.seasonTerm(), rule.getSealMaxPoints());
        int progress = eff.progress();
        int battlePts = eff.battlePoints();
        int catchupPts = eff.catchupPoints();
        int healthBps = SeasonRules.scaledBonusBps(progress, rule.getSealMaxPoints(), rule.getMaxHealthBpsCap());
        int defenseBps = SeasonRules.scaledBonusBps(progress, rule.getSealMaxPoints(), rule.getDefenseBpsCap());
        SeasonRules.CatchupPlan fullPlan = SeasonRules.computeCatchupPlan(
                progress, rule.getSealMaxPoints(), 0, rule.getCatchupPricePerPoint());
        int writableErr = checkWritable(player, false);

        out.setAvailable(true)
                .setSeasonId(rule.getId())
                .setSeasonName(rule.getName())
                .setSeasonDesc(rule.getDesc())
                .setStartsAtMs(resolved.termStartMs())
                .setEndsAtMs(resolved.termEndMs())
                .setSeasonTerm(resolved.seasonTerm())
                .setCycleIndex(resolved.cycleIndex())
                .setCyclePoolSize(resolved.cyclePoolSize())
                .setEquippedSealId(equippedId)
                .setProgressPoints(progress)
                .setMaxPoints(rule.getSealMaxPoints())
                .setBattlePoints(battlePts)
                .setCatchupPoints(catchupPts)
                .setCurrentMaxHealthBps(healthBps)
                .setCapMaxHealthBps(rule.getMaxHealthBpsCap())
                .setCurrentDefenseBps(defenseBps)
                .setCapDefenseBps(rule.getDefenseBpsCap())
                .setPveWinPoints(rule.getPveWinPoints())
                .setPvpWinPoints(rule.getPvpWinPoints())
                .setPvpOtherPoints(rule.getPvpOtherPoints())
                .setCatchupCurrencyType(rule.getCatchupCurrencyType())
                .setCatchupPricePerPoint(rule.getCatchupPricePerPoint())
                .setCatchupRemainingTotalPrice(fullPlan == null ? 0L : fullPlan.totalPrice())
                .setCurrencyBalance(player.wallet().balance(rule.getCatchupCurrencyType()))
                .setRevision(record == null ? 0L : record.getRevision())
                .setCanSwitchSeal(writableErr == OK)
                .setCanCatchup(writableErr == OK && fullPlan != null);
        if (writableErr == kSeasonInBattle_VALUE) {
            out.setActionUnavailableReason("战斗中暂不能更换或追赶赛季印，请在结算后重试");
        } else if (writableErr != OK) {
            out.setActionUnavailableReason("当前角色状态暂时不能操作赛季印，请稍后再试");
        }
        List<SeasonSealTable> validSeals = tables.validSealsOf(rule.getId());
        for (SeasonSealTable seal : validSeals) {
            out.addSeals(SeasonSealOption.newBuilder()
                    .setSealId(seal.getId())
                    .setName(seal.getName())
                    .setDescription(seal.getDescription())
                    .setIconKey(seal.getIconKey())
                    .setEffectId(seal.getEffectId())
                    .setBonusBps(seal.getBonusBps())
                    .setCapBps(seal.getCapBps())
                    .setPerRoundLimit(seal.getPerRoundLimit())
                    .setBattleLimit(seal.getBattleLimit())
                    .setActiveRounds(seal.getActiveRounds())
                    .setHealthThresholdBps(seal.getHealthThresholdBps())
                    .setEquipped(seal.getId() == equippedId)
                    .setResonanceSkillId(seal.getResonanceSkillId())
                    .setResonanceKind(seal.getResonanceKind())
                    .setResonanceParam(seal.getResonanceParam())
                    .setResonanceDesc(seal.getResonanceDesc()));
        }
        fillWeeklyAffix(active, nowMs, validSeals, out);
        int level = PlayerLevels.effective(player.level());
        for (SkillTable skill : tables.seasonSkillsOf(rule.getId())) {
            int effRank = SeasonRules.effectiveSeasonalSkillRank(level, skill.getUnlockLevel(), skill.getMaxRank());
            out.addSeasonSkills(SeasonSkillSummary.newBuilder()
                    .setSkillTableId(skill.getId())
                    .setName(skill.getName())
                    .setDescription(skill.getDescription())
                    .setIconKey(skill.getIconKey())
                    .setCategory(skill.getCategory())
                    .setTargetTeam(skill.getTargetTeam())
                    .setMaxTargets(skill.getMaxTargets())
                    .setManaPercent(skill.getManaPercent())
                    .setCooldownRounds(skill.getCooldownRounds())
                    .setUnlockLevel(skill.getUnlockLevel())
                    .setMaxRank(skill.getMaxRank())
                    .setEffectiveRank(effRank)
                    .setUnlocked(effRank > 0)
                    .setAbilityKind(skill.getAbilityKind())
                    .setCasterMask(skill.getCasterMask())
                    .setActionTags(skill.getActionTags()));
        }
        return out.build();
    }

    private void fillHonors(ScenePlayer player, int activeSeasonId, int activeTerm,
                            int retroTargetSeasonId, SeasonStateSnapshot.Builder out) {
        PlayerSeason ps = player.season();
        for (int sid : tables.allRuleIdsAsc()) {
            SeasonRuleTable rule = tables.ruleById(sid);
            if (rule == null || rule.getSealMaxPoints() == 0) {
                continue;
            }
            boolean unlocked = isSeasonHonorUnlocked(ps, sid);
            boolean isCurrent = sid == activeSeasonId;
            int retroPts = unlocked ? 0 : Math.min(ps.retroHonorProgress(sid), rule.getSealMaxPoints());
            int progress;
            if (unlocked) {
                progress = rule.getSealMaxPoints();
            } else if (isCurrent) {
                progress = readEffectiveTermProgress(ps.record(sid), activeTerm, rule.getSealMaxPoints()).progress();
            } else {
                progress = retroPts;
            }
            out.addHonors(SeasonHonorView.newBuilder()
                    .setSeasonId(sid)
                    .setSeasonName(rule.getName())
                    .setHonorTitleName(rule.getHonorTitleName())
                    .setHonorBadgeIconKey(rule.getHonorBadgeIconKey())
                    .setProgressPoints(progress)
                    .setMaxPoints(rule.getSealMaxPoints())
                    .setUnlocked(unlocked)
                    .setIsCurrentSeason(isCurrent)
                    .setIsRetroTarget(!unlocked && sid == retroTargetSeasonId)
                    .setRetroProgressPoints(retroPts));
        }
    }

    private static void fillWeeklyAffix(SeasonTables.ActiveSeason active, long nowMs, List<SeasonSealTable> validSeals,
                                        SeasonStateSnapshot.Builder out) {
        int affixSeed = active.resolved().cyclePoolSize() > 0
                ? active.resolved().seasonTerm() : active.rule().getId();
        int weekIndex = SeasonRules.computeSeasonWeekIndex(active.resolved().termStartMs(), nowMs);
        int affixId = SeasonRules.weeklyAffixForWeek(affixSeed, weekIndex);
        if (!SeasonRules.isValidWeeklyAffixId(affixId)) {
            return;
        }
        SeasonWeeklyAffixView.Builder affix = SeasonWeeklyAffixView.newBuilder()
                .setWeekIndex(weekIndex)
                .setAffixId(affixId);
        switch (affixId) {
            case SeasonRules.WEEKLY_AFFIX_SHIELD_OVERLOAD -> affix
                    .setName("护盾过载周")
                    .setDescription("全员对称：本季常规对局中双方技能施加的护盾量额外 +15%。")
                    .setStrategyTip("推荐搭配破盾赛季印与破甲/护盾技能抢占节奏。");
            case SeasonRules.WEEKLY_AFFIX_SPIRIT_RECOVERY -> affix
                    .setName("灵息充盈周")
                    .setDescription("全员对称：本季常规对局中双方执行普通防御额外回复 3% 最大法力。")
                    .setStrategyTip("推荐搭配聚息援印或固阵守印打稳守蓄蓝反击。");
            case SeasonRules.WEEKLY_AFFIX_OPENING_GUARD -> affix
                    .setName("先机守势周")
                    .setDescription("全员对称：本季常规对局前 2 回合双方受到的直伤降低 8%。")
                    .setStrategyTip("前两回合爆发更克制，推荐蓄势增益或固阵守印后发制人。");
            case SeasonRules.WEEKLY_AFFIX_SHARP_TEMPO -> affix
                    .setName("锋芒竞速周")
                    .setDescription("全员对称：本季常规对局中攻击无护盾目标的直伤提升 5%。")
                    .setStrategyTip("先破盾再集火收益更高，推荐破阵锐印/玄印联动群攻技。");
            default -> {
            }
        }
        for (SeasonSealTable seal : validSeals) {
            if (SeasonRules.isSealEffectRecommendedForAffix(affixId, seal.getEffectId())) {
                affix.addRecommendedSealIds(seal.getId());
            }
        }
        out.setWeeklyAffix(affix);
    }
}
