package com.game.scene.trial;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.battle.engine.TrialJournalRules;
import com.game.player.store.state.ClientRequestRecordState;
import com.game.player.store.state.EquipmentState;
import com.game.player.store.state.PlayerTrialJournalState;
import com.game.player.store.state.SeasonTrialJournalArchiveState;
import com.game.player.store.state.TrialJournalCoopRecordState;
import com.game.player.store.state.TrialJournalEntryRecordState;
import com.game.player.store.state.TrialJournalLoadoutSnapshotState;
import com.game.player.store.state.TrialJournalSoloRecordState;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleTrialJournalFacts;
import com.game.proto.ClaimTrialJournalMilestoneRequest;
import com.game.proto.ClaimTrialJournalMilestoneResponse;
import com.game.proto.GetTrialJournalResponse;
import com.game.proto.RedeemBoundArtifactVoucherRequest;
import com.game.proto.RedeemBoundArtifactVoucherResponse;
import com.game.proto.TrialJournalChapterView;
import com.game.proto.TrialJournalCoopRecord;
import com.game.proto.TrialJournalEntryView;
import com.game.proto.TrialJournalLoadoutSnapshot;
import com.game.proto.TrialJournalMilestoneView;
import com.game.proto.TrialJournalSealView;
import com.game.proto.TrialJournalSnapshot;
import com.game.proto.TrialJournalSoloRecord;
import com.game.proto.TrialRedeemableArtifactOption;
import com.game.scene.appearance.AppearanceService;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Bag;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerBags;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.CommonErrorTip;
import com.game.table.TrialJournalEntryTable;
import com.game.table.TrialJournalErrorTip;
import com.game.table.TrialJournalMilestoneTable;
import com.game.table.TrialJournalSealTable;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 五行试炼志 V2 核心服务（对齐 C++ {@code PlayerTrialJournalSystem}）：
 * <ul>
 *   <li>永久卷一/卷二采用紧凑 bytes 位图（保留未识别高位字节），卷三按 {@code season_id} 隔离存放；</li>
 *   <li>战斗结算权威推进战法印、单人/合战最快回合与普通首领首通困难准入；</li>
 *   <li>一次性里程碑发奖（仅自用重铸砂 3403、自用法宝拓印 3404、M901 专属永久形象 {@code 20_star_formation_master_girl}）
 *       与拓印兑换 8 件仅自用 V1 法宝，均带 {@code client_request_id} 幂等与严格背包原子预检。</li>
 * </ul>
 */
public final class TrialJournalService {

    private static final Logger log = LoggerFactory.getLogger(TrialJournalService.class);

    public static final int OK = 0;
    public static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    public static final int UNAVAILABLE = TrialJournalErrorTip.trial_journal_error.kTrialJournalUnavailable_VALUE;
    public static final int FROZEN = TrialJournalErrorTip.trial_journal_error.kTrialJournalFrozen_VALUE;
    public static final int IN_BATTLE = TrialJournalErrorTip.trial_journal_error.kTrialJournalInBattle_VALUE;
    public static final int MILESTONE_NOT_FOUND = TrialJournalErrorTip.trial_journal_error.kTrialJournalMilestoneNotFound_VALUE;
    public static final int MILESTONE_NOT_REACHED = TrialJournalErrorTip.trial_journal_error.kTrialJournalMilestoneNotReached_VALUE;
    public static final int MILESTONE_ALREADY_CLAIMED = TrialJournalErrorTip.trial_journal_error.kTrialJournalMilestoneAlreadyClaimed_VALUE;
    public static final int BAG_FULL = TrialJournalErrorTip.trial_journal_error.kTrialJournalBagFull_VALUE;
    public static final int VOUCHER_NOT_ENOUGH = TrialJournalErrorTip.trial_journal_error.kTrialJournalVoucherNotEnough_VALUE;
    public static final int ARTIFACT_INVALID = TrialJournalErrorTip.trial_journal_error.kTrialJournalArtifactInvalid_VALUE;

    /** 单局战斗结算推进试炼志后的增量，供 {@code BattleResultEntryState} 字段 21–24 回填。 */
    public record SettlementDelta(int entryId, List<Integer> newlyLitSealIds,
                                  boolean newSoloBest, boolean newCoopBest, boolean changed) {
        public static final SettlementDelta NONE = new SettlementDelta(0, List.of(), false, false, false);
    }

    public record ClaimOutcome(ClaimTrialJournalMilestoneResponse response, boolean changed) {
    }

    public record RedeemOutcome(RedeemBoundArtifactVoucherResponse response, boolean changed) {
    }

    private final TrialJournalTables tables;
    private final BagService bags;
    private final AppearanceService appearance;
    private final SceneClock clock;
    private final SceneMetrics metrics;
    private final IntSupplier activeSeasonSupplier;

    public TrialJournalService(TrialJournalTables tables, BagService bags, AppearanceService appearance,
                               SceneClock clock, SceneMetrics metrics, IntSupplier activeSeasonSupplier) {
        this.tables = Objects.requireNonNull(tables, "tables");
        this.bags = Objects.requireNonNull(bags, "bags");
        this.appearance = appearance;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.activeSeasonSupplier = activeSeasonSupplier == null ? () -> 0 : activeSeasonSupplier;
    }

    public TrialJournalTables tables() {
        return tables;
    }

    public long nowMillis() {
        return clock.epochMillis();
    }

    /** 任意普通首领首通标志位，或任意永久普通格通关印已点亮（对齐 C++ {@code HasClearedAnyNormalBossTrial}）。 */
    public boolean hasClearedAnyNormalBossTrial(ScenePlayer player) {
        PlayerTrialJournalState state = player.trialJournal().state();
        if (state.getNormalBossTrialCleared()) {
            return true;
        }
        return countEffectiveSeals(state, 0).anyNormalBreakthroughCleared;
    }

    /** 战斗结算应用同帧调用：按冻结的 {@code trial_facts} 推进战法印、最快回合与普通首领首通解锁。 */
    public SettlementDelta applyBattleCredit(ScenePlayer player, BattleSettlementData settlement, long nowMs) {
        PlayerTrialJournalState.Builder candidate = player.trialJournal().state().toBuilder();
        boolean changed = false;
        if (isNormalBossTrialVictory(settlement) && !candidate.getNormalBossTrialCleared()) {
            candidate.setNormalBossTrialCleared(true);
            changed = true;
        }
        if (!TrialJournalRules.isValidTrialSettlement(settlement)) {
            if (changed) {
                bumpRevision(candidate);
                player.trialJournal().replace(candidate.build());
            }
            return new SettlementDelta(0, List.of(), false, false, changed);
        }

        BattleTrialJournalFacts facts = settlement.getTrialFacts();
        TrialJournalEntryTable entry = tables.entry(facts.getJournalEntryId());
        if (entry == null) {
            if (changed) {
                bumpRevision(candidate);
                player.trialJournal().replace(candidate.build());
            }
            return new SettlementDelta(0, List.of(), false, false, changed);
        }
        // 赛季卷严格按赛季号匹配，拒收过季迟到结算污染当前或历史季档。
        if (entry.getSeasonId() != 0) {
            int activeSeasonId = activeSeasonSupplier.getAsInt();
            if (facts.getSeasonId() != entry.getSeasonId() || activeSeasonId != entry.getSeasonId()) {
                if (changed) {
                    bumpRevision(candidate);
                    player.trialJournal().replace(candidate.build());
                }
                return new SettlementDelta(0, List.of(), false, false, changed);
            }
        }

        List<Integer> newlyLit = new ArrayList<>();
        for (TrialJournalSealTable seal : tables.sealsForEntry(entry.getId())) {
            if (!seal.getEnabled()) {
                continue;
            }
            if (!TrialJournalRules.evaluateSealCondition(seal.getConditionType(), seal.getConditionParam(), settlement)) {
                continue;
            }
            if (markSealLit(candidate, seal.getId(), entry.getSeasonId())) {
                newlyLit.add(seal.getId());
                changed = true;
            }
        }

        boolean newSoloBest = false;
        boolean newCoopBest = false;
        if ((entry.getSourceKind() == 1 || entry.getSourceKind() == 2)
                && TrialJournalRules.isPlayerVictory(settlement.getOutcome(), settlement.getPlayerTeamIndex())) {
            long achievedAtMs = settlement.getFinishedAtMs() != 0L ? settlement.getFinishedAtMs() : nowMs;
            int totalRounds = settlement.getTotalRounds();
            if (TrialJournalRules.isSoloTrial(facts)) {
                TrialJournalEntryRecordState existing = findEntryRecord(candidate, entry.getId(), facts.getTrialRuleVersion());
                int oldRounds = existing != null ? existing.getSoloBest().getBestRounds() : 0;
                if (oldRounds == 0 || Integer.compareUnsigned(totalRounds, oldRounds) < 0) {
                    TrialJournalEntryRecordState.Builder rec = findOrAddEntryRecord(candidate, entry.getId(), facts.getTrialRuleVersion());
                    TrialJournalLoadoutSnapshotState.Builder loadout = TrialJournalLoadoutSnapshotState.newBuilder()
                            .setPetTableId(facts.getUsedPetTableId())
                            .addAllArtifactEffectIds(TrialJournalRules.normalizeArtifactEffectIds(facts.getUsedArtifactEffectIdsList()))
                            .setSeasonSealId(facts.getUsedSeasonSealId());
                    rec.setSoloBest(TrialJournalSoloRecordState.newBuilder()
                            .setBestRounds(totalRounds)
                            .setAchievedAtMs(achievedAtMs)
                            .setLoadout(loadout));
                    newSoloBest = true;
                    changed = true;
                }
            } else if (TrialJournalRules.isCoopTrial(facts)) {
                TrialJournalEntryRecordState existing = findEntryRecord(candidate, entry.getId(), facts.getTrialRuleVersion());
                int oldRounds = existing != null ? existing.getCoopBest().getBestRounds() : 0;
                if (oldRounds == 0 || Integer.compareUnsigned(totalRounds, oldRounds) < 0) {
                    TrialJournalEntryRecordState.Builder rec = findOrAddEntryRecord(candidate, entry.getId(), facts.getTrialRuleVersion());
                    TrialJournalCoopRecordState.Builder coop = TrialJournalCoopRecordState.newBuilder()
                            .setBestRounds(totalRounds)
                            .setPartySize(facts.getInitialHumanCount())
                            .setAchievedAtMs(achievedAtMs)
                            .setPetTableId(facts.getUsedPetTableId());
                    int maxNames = Math.min(facts.getTeammateNamesCount(), TrialJournalRules.MAX_COOP_TEAMMATES);
                    for (int i = 0; i < maxNames; i++) {
                        String name = facts.getTeammateNames(i);
                        if (!name.isEmpty()) {
                            coop.addTeammateNames(name);
                        }
                    }
                    rec.setCoopBest(coop);
                    newCoopBest = true;
                    changed = true;
                }
            }
        }

        if (changed) {
            bumpRevision(candidate);
            player.trialJournal().replace(candidate.build());
        }
        return new SettlementDelta(entry.getId(), List.copyOf(newlyLit), newSoloBest, newCoopBest, changed);
    }

    /** 456：查询完整五行试炼志权威快照。 */
    public GetTrialJournalResponse getJournal(ScenePlayer player, long nowMs) {
        return GetTrialJournalResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setSnapshot(buildSnapshot(player, nowMs))
                .build();
    }

    /** 457：领取一次性试炼志里程碑（幂等重放先于状态校验；满包零副作用）。 */
    public ClaimOutcome claimMilestone(SceneWorld world, ScenePlayer player,
                                       ClaimTrialJournalMilestoneRequest request, long nowMs) {
        long clientRequestId = request.getClientRequestId();
        int milestoneId = request.getMilestoneId();
        if (clientRequestId == 0L || milestoneId == 0) {
            return new ClaimOutcome(claimError(player, nowMs, INVALID_PARAMETER), false);
        }

        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(
                request.toBuilder().clearClientRequestId().build());
        PlayerClientRequestLedger.CheckResult dedup = ledger.check(
                clientRequestId, PlayerClientRequestLedger.KIND_CLAIM_TRIAL_JOURNAL_MILESTONE, fingerprint);
        if (dedup.verdict() == PlayerClientRequestLedger.Verdict.REPLAY) {
            ClientRequestRecordState prev = dedup.record();
            ClaimTrialJournalMilestoneResponse.Builder rep = ClaimTrialJournalMilestoneResponse.newBuilder()
                    .setErrorMessage(tip(OK))
                    .setReplayed(true)
                    .setGrantedSandCount(prev.getTrialMilestoneSandCount())
                    .setGrantedVoucherCount(prev.getTrialMilestoneVoucherCount())
                    .setSnapshot(buildSnapshot(player, nowMs));
            TrialJournalMilestoneTable row = tables.milestone(prev.getTrialMilestoneId());
            if (row != null) {
                if (!row.getGrantAppearanceId().isEmpty()) {
                    rep.setGrantedAppearanceId(row.getGrantAppearanceId());
                }
                if (row.getGrantPetCosmeticId() != 0) {
                    rep.setGrantedPetCosmeticId(row.getGrantPetCosmeticId());
                }
                if (row.getGrantSkillVfxId() != 0) {
                    rep.setGrantedSkillVfxId(row.getGrantSkillVfxId());
                }
            }
            return new ClaimOutcome(rep.build(), false);
        }
        if (dedup.verdict() == PlayerClientRequestLedger.Verdict.MISMATCH) {
            return new ClaimOutcome(claimError(player, nowMs, INVALID_PARAMETER), false);
        }

        if (!ledger.isValid()) {
            return new ClaimOutcome(claimError(player, nowMs, UNAVAILABLE), false);
        }
        int writableErr = checkWritable(player, true);
        if (writableErr != OK) {
            return new ClaimOutcome(claimError(player, nowMs, writableErr), false);
        }

        int activeSeasonId = activeSeasonSupplier.getAsInt();
        TrialJournalMilestoneTable milestone = tables.milestone(milestoneId);
        if (milestone == null || !milestone.getEnabled()
                || !isSeasonRowVisible(milestone.getSeasonId(), activeSeasonId)) {
            return new ClaimOutcome(claimError(player, nowMs, MILESTONE_NOT_FOUND), false);
        }
        PlayerTrialJournalState current = player.trialJournal().state();
        if (isMilestoneClaimed(current, milestone.getId(), milestone.getSeasonId())) {
            return new ClaimOutcome(claimError(player, nowMs, MILESTONE_ALREADY_CLAIMED), false);
        }
        EffectiveSealCounts counts = countEffectiveSeals(current, activeSeasonId);
        if (Integer.compareUnsigned(counts.forScope(milestone.getScopeChapterId()), milestone.getRequiredSeals()) < 0) {
            return new ClaimOutcome(claimError(player, nowMs, MILESTONE_NOT_REACHED), false);
        }
        if (!tables.isValidExclusiveAppearance(milestone.getGrantAppearanceId())
                || !tables.isValidExclusivePetCosmetic(milestone.getGrantPetCosmeticId())
                || !tables.isValidExclusiveSkillVfx(milestone.getGrantSkillVfxId())) {
            return new ClaimOutcome(claimError(player, nowMs, UNAVAILABLE), false);
        }

        Map<Integer, Long> gainedCounts = new TreeMap<>(Integer::compareUnsigned);
        if (milestone.getBoundSandCount() > 0) {
            gainedCounts.merge(TrialJournalRules.BOUND_REFORGE_SAND_ITEM_ID,
                    Integer.toUnsignedLong(milestone.getBoundSandCount()), Long::sum);
        }
        if (milestone.getBoundArtifactVoucherCount() > 0) {
            gainedCounts.merge(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID,
                    Integer.toUnsignedLong(milestone.getBoundArtifactVoucherCount()), Long::sum);
        }

        PlayerBags candidateBags = null;
        Bag.AddResult added = null;
        if (!gainedCounts.isEmpty()) {
            candidateBags = PlayerBags.restore(player.bags().toState());
            candidateBags.normalize(bags.tables());
            added = bags.prepareEquipmentGrant(player, candidateBags, gainedCounts, true);
            if (!added.ok()) {
                int err = added.tip() == BagService.REFUSED ? INVALID_PARAMETER : BAG_FULL;
                return new ClaimOutcome(claimError(player, nowMs, err), false);
            }
        }

        PlayerTrialJournalState.Builder nextJournal = current.toBuilder();
        if (!markMilestoneClaimed(nextJournal, milestone.getId(), milestone.getSeasonId())) {
            return new ClaimOutcome(claimError(player, nowMs, MILESTONE_ALREADY_CLAIMED), false);
        }
        bumpRevision(nextJournal);

        if (!milestone.getGrantAppearanceId().isEmpty()) {
            if (appearance == null) {
                return new ClaimOutcome(claimError(player, nowMs, UNAVAILABLE), false);
            }
            int grantErr = appearance.grantAppearance(world, player, milestone.getGrantAppearanceId());
            if (grantErr != OK) {
                return new ClaimOutcome(claimError(player, nowMs, grantErr), false);
            }
        }
        if (milestone.getGrantPetCosmeticId() != 0) {
            if (appearance == null) {
                return new ClaimOutcome(claimError(player, nowMs, UNAVAILABLE), false);
            }
            int grantErr = appearance.grantPetCosmetic(world, player, milestone.getGrantPetCosmeticId());
            if (grantErr != OK) {
                return new ClaimOutcome(claimError(player, nowMs, grantErr), false);
            }
        }
        if (milestone.getGrantSkillVfxId() != 0) {
            if (appearance == null) {
                return new ClaimOutcome(claimError(player, nowMs, UNAVAILABLE), false);
            }
            int grantErr = appearance.grantSkillVfx(world, player, milestone.getGrantSkillVfxId());
            if (grantErr != OK) {
                return new ClaimOutcome(claimError(player, nowMs, grantErr), false);
            }
        }

        if (candidateBags != null) {
            player.bags().commitPrepared(candidateBags);
            bags.auditEquipment(player, List.of(), added.written(), AssetAudit.Reason.TRIAL_JOURNAL_MILESTONE,
                    clientRequestId, "{\"operation\":\"trial_journal_milestone\"}");
        }
        player.trialJournal().replace(nextJournal.build());
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(clientRequestId)
                .setKind(PlayerClientRequestLedger.KIND_CLAIM_TRIAL_JOURNAL_MILESTONE)
                .setFingerprint(fingerprint)
                .setTrialMilestoneId(milestone.getId())
                .setTrialMilestoneSandCount(milestone.getBoundSandCount())
                .setTrialMilestoneVoucherCount(milestone.getBoundArtifactVoucherCount()));

        log.info("[PlayerTrialJournal] 里程碑已领取 player={} milestone_id={} bound_sand={} bound_voucher={} appearance={} pet_cosmetic={} skill_vfx={}",
                Long.toUnsignedString(player.playerId()), milestone.getId(),
                milestone.getBoundSandCount(), milestone.getBoundArtifactVoucherCount(),
                milestone.getGrantAppearanceId(), milestone.getGrantPetCosmeticId(),
                milestone.getGrantSkillVfxId());

        ClaimTrialJournalMilestoneResponse.Builder response = ClaimTrialJournalMilestoneResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setGrantedSandCount(milestone.getBoundSandCount())
                .setGrantedVoucherCount(milestone.getBoundArtifactVoucherCount())
                .setSnapshot(buildSnapshot(player, nowMs));
        if (!milestone.getGrantAppearanceId().isEmpty()) {
            response.setGrantedAppearanceId(milestone.getGrantAppearanceId());
        }
        if (milestone.getGrantPetCosmeticId() != 0) {
            response.setGrantedPetCosmeticId(milestone.getGrantPetCosmeticId());
        }
        if (milestone.getGrantSkillVfxId() != 0) {
            response.setGrantedSkillVfxId(milestone.getGrantSkillVfxId());
        }
        return new ClaimOutcome(response.build(), true);
    }

    /** 458：消耗 1 张自用法宝拓印(3404) 兑换指定 V1 仅自用法宝（允许同名双件，严格原子预检与幂等重放）。 */
    public RedeemOutcome redeemArtifactVoucher(SceneWorld world, ScenePlayer player,
                                               RedeemBoundArtifactVoucherRequest request, long nowMs) {
        long clientRequestId = request.getClientRequestId();
        int artifactItemConfigId = request.getArtifactItemConfigId();
        if (clientRequestId == 0L || artifactItemConfigId == 0) {
            return new RedeemOutcome(redeemError(player, nowMs, INVALID_PARAMETER), false);
        }

        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(
                request.toBuilder().clearClientRequestId().build());
        PlayerClientRequestLedger.CheckResult dedup = ledger.check(
                clientRequestId, PlayerClientRequestLedger.KIND_REDEEM_BOUND_ARTIFACT_VOUCHER, fingerprint);
        if (dedup.verdict() == PlayerClientRequestLedger.Verdict.REPLAY) {
            ClientRequestRecordState prev = dedup.record();
            return new RedeemOutcome(RedeemBoundArtifactVoucherResponse.newBuilder()
                    .setErrorMessage(tip(OK))
                    .setReplayed(true)
                    .setCreatedItemId(prev.getRedeemedArtifactItemId())
                    .setArtifactItemConfigId(prev.getRedeemedArtifactConfigId())
                    .setSnapshot(buildSnapshot(player, nowMs))
                    .build(), false);
        }
        if (dedup.verdict() == PlayerClientRequestLedger.Verdict.MISMATCH) {
            return new RedeemOutcome(redeemError(player, nowMs, INVALID_PARAMETER), false);
        }

        if (!ledger.isValid()) {
            return new RedeemOutcome(redeemError(player, nowMs, UNAVAILABLE), false);
        }
        int writableErr = checkWritable(player, true);
        if (writableErr != OK) {
            return new RedeemOutcome(redeemError(player, nowMs, writableErr), false);
        }

        TrialJournalTables.RedeemableArtifact target = tables.redeemableArtifact(artifactItemConfigId);
        if (target == null) {
            return new RedeemOutcome(redeemError(player, nowMs, ARTIFACT_INVALID), false);
        }
        if (player.bags().bag(BagType.INVENTORY).total(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID) < 1L) {
            return new RedeemOutcome(redeemError(player, nowMs, VOUCHER_NOT_ENOUGH), false);
        }

        PlayerBags candidateBags = PlayerBags.restore(player.bags().toState());
        candidateBags.normalize(bags.tables());
        Bag inv = candidateBags.bag(BagType.INVENTORY);
        List<Bag.Drawn> removed = inv.drainClamped(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID, 1L);
        long drainedTotal = removed.stream().mapToLong(Bag.Drawn::count).sum();
        if (drainedTotal != 1L) {
            return new RedeemOutcome(redeemError(player, nowMs, VOUCHER_NOT_ENOUGH), false);
        }
        Bag.AddResult added = bags.prepareEquipmentGrant(player, candidateBags, Map.of(artifactItemConfigId, 1L), true);
        if (!added.ok() || added.written().isEmpty() || added.written().getFirst().guids().isEmpty()) {
            int err = added.tip() == BagService.REFUSED ? UNAVAILABLE : BAG_FULL;
            return new RedeemOutcome(redeemError(player, nowMs, err), false);
        }
        long createdItemId = added.written().getFirst().guids().getFirst();
        BagItem createdItem = inv.item(createdItemId);
        if (createdItem == null) {
            return new RedeemOutcome(redeemError(player, nowMs, UNAVAILABLE), false);
        }
        createdItem.equipment(EquipmentState.getDefaultInstance());

        player.bags().commitPrepared(candidateBags);
        PlayerTrialJournalState.Builder nextJournal = player.trialJournal().state().toBuilder();
        bumpRevision(nextJournal);
        player.trialJournal().replace(nextJournal.build());
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(clientRequestId)
                .setKind(PlayerClientRequestLedger.KIND_REDEEM_BOUND_ARTIFACT_VOUCHER)
                .setFingerprint(fingerprint)
                .setRedeemedArtifactItemId(createdItemId)
                .setRedeemedArtifactConfigId(artifactItemConfigId));
        bags.auditEquipment(player, removed, added.written(), AssetAudit.Reason.TRIAL_ARTIFACT_REDEEM,
                clientRequestId, "{\"operation\":\"trial_artifact_redeem\"}");

        log.info("[PlayerTrialJournal] 拓印兑换仅自用法宝 player={} config_id={} artifact_id={} item_id={}",
                Long.toUnsignedString(player.playerId()), artifactItemConfigId,
                target.artifact().getId(), Long.toUnsignedString(createdItemId));

        return new RedeemOutcome(RedeemBoundArtifactVoucherResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setCreatedItemId(createdItemId)
                .setArtifactItemConfigId(artifactItemConfigId)
                .setSnapshot(buildSnapshot(player, nowMs))
                .build(), true);
    }

    /** 构建试炼志权威快照（对齐 C++ {@code FillSnapshot}）。 */
    public TrialJournalSnapshot buildSnapshot(ScenePlayer player, long nowMs) {
        PlayerTrialJournalState state = player.trialJournal().state();
        int activeSeasonId = activeSeasonSupplier.getAsInt();
        EffectiveSealCounts counts = countEffectiveSeals(state, activeSeasonId);
        boolean hardBossTrialUnlocked = state.getNormalBossTrialCleared() || counts.anyNormalBreakthroughCleared;

        Map<Integer, List<TrialJournalEntryTable>> entriesByChapter = new TreeMap<>(Integer::compareUnsigned);
        for (TrialJournalEntryTable entry : tables.entries()) {
            if (!entry.getEnabled() || !isSeasonRowVisible(entry.getSeasonId(), activeSeasonId)) {
                continue;
            }
            entriesByChapter.computeIfAbsent(entry.getChapterId(), k -> new ArrayList<>()).add(entry);
        }

        int writableErr = checkWritable(player, false);
        TrialJournalSnapshot.Builder out = TrialJournalSnapshot.newBuilder()
                .setServerNowMs(nowMs)
                .setStateRevision(state.getStateRevision())
                .setTotalPermanentLitSeals(counts.permanentLit)
                .setTotalPermanentSeals(counts.permanentTotal)
                .setHardBossTrialUnlocked(hardBossTrialUnlocked)
                .setVoucherBalance(player.bags().bag(BagType.INVENTORY).total(TrialJournalRules.BOUND_ARTIFACT_VOUCHER_ITEM_ID))
                .setBoundSandBalance(player.bags().bag(BagType.INVENTORY).total(TrialJournalRules.BOUND_REFORGE_SAND_ITEM_ID))
                .setCanOperate(writableErr == OK);
        if (writableErr == IN_BATTLE) {
            out.setActionUnavailableReason("战斗中暂不能领取里程碑或兑换法宝，请在结算后重试");
        } else if (writableErr != OK) {
            out.setActionUnavailableReason("当前角色状态暂时不能操作试炼志，请稍后再试");
        }
        if (entriesByChapter.isEmpty()) {
            return out.setAvailable(false).setUnavailableReason("试炼志暂不可用").build();
        }
        out.setAvailable(true);

        for (var chEntry : entriesByChapter.entrySet()) {
            int chapterId = chEntry.getKey();
            List<TrialJournalEntryTable> chapterEntries = chEntry.getValue();
            chapterEntries.sort(Comparator.comparingInt(TrialJournalEntryTable::getSortOrder)
                    .thenComparingInt(TrialJournalEntryTable::getId));
            TrialJournalChapterView.Builder chView = out.addChaptersBuilder()
                    .setChapterId(chapterId)
                    .setSeasonId(chapterEntries.getFirst().getSeasonId())
                    .setChapterName(chapterEntries.getFirst().getChapterName())
                    .setLitSealCount(counts.litByChapter.getOrDefault(chapterId, 0))
                    .setTotalSealCount(counts.totalByChapter.getOrDefault(chapterId, 0));

            for (TrialJournalEntryTable entry : chapterEntries) {
                TrialJournalEntryView.Builder ev = chView.addEntriesBuilder()
                        .setEntryId(entry.getId())
                        .setChapterId(entry.getChapterId())
                        .setSeasonId(entry.getSeasonId())
                        .setSourceKind(entry.getSourceKind())
                        .setDifficulty(entry.getDifficulty())
                        .setVariantKey(entry.getVariantKey())
                        .setRuleVersion(entry.getRuleVersion())
                        .setRuleVersionName(entry.getRuleVersionName())
                        .setSortOrder(entry.getSortOrder())
                        .setDisplayName(entry.getDisplayName())
                        .setIconKey(entry.getIconKey())
                        .setDesc(entry.getDesc())
                        .setJumpTarget(entry.getJumpTarget());

                int entryLit = 0;
                int entryTotal = 0;
                for (TrialJournalSealTable seal : tables.sealsForEntry(entry.getId())) {
                    if (!seal.getEnabled()) {
                        continue;
                    }
                    entryTotal++;
                    boolean lit = isSealLit(state, seal.getId(), entry.getSeasonId());
                    if (lit) {
                        entryLit++;
                    }
                    ev.addSeals(TrialJournalSealView.newBuilder()
                            .setSealId(seal.getId())
                            .setSealSlotKind(seal.getSealSlotKind())
                            .setConditionType(seal.getConditionType())
                            .setConditionParam(seal.getConditionParam())
                            .setTitle(seal.getTitle())
                            .setDesc(seal.getDesc())
                            .setLit(lit));
                }
                ev.setLitSealCount(entryLit).setTotalSealCount(entryTotal);

                TrialJournalEntryRecordState record = findEntryRecord(state, entry.getId(), entry.getRuleVersion());
                boolean hasSolo = record != null && record.getSoloBest().getBestRounds() > 0;
                boolean hasCoop = record != null && record.getCoopBest().getBestRounds() > 0;
                ev.setSoloRecorded(hasSolo);
                if (hasSolo) {
                    ev.setSoloBest(toSoloView(record.getSoloBest()));
                }
                ev.setCoopRecorded(hasCoop);
                if (hasCoop) {
                    ev.setCoopBest(toCoopView(record.getCoopBest()));
                }
                ev.setHasLegacyRecordOnly(!hasSolo && !hasCoop
                        && hasAnyLegacyEntryRecord(state, entry.getId(), entry.getRuleVersion()));

                if (writableErr == IN_BATTLE) {
                    ev.setCanEnter(false).setEnterBlockedReason("当前正在战斗中，暂不能前往试炼");
                } else if (entry.getUnlockMode() == TrialJournalRules.UNLOCK_MODE_REQUIRE_ANY_NORMAL_BREAKTHROUGH_CLEAR
                        && !hardBossTrialUnlocked) {
                    ev.setCanEnter(false).setEnterBlockedReason("本人普通首领试炼通关一次后开启困难");
                } else {
                    ev.setCanEnter(writableErr == OK);
                    if (writableErr != OK) {
                        ev.setEnterBlockedReason("当前状态暂不可前往试炼");
                    }
                }
            }
        }

        List<TrialJournalMilestoneTable> visibleMilestones = new ArrayList<>();
        for (TrialJournalMilestoneTable m : tables.milestones()) {
            if (m.getEnabled() && isSeasonRowVisible(m.getSeasonId(), activeSeasonId)) {
                visibleMilestones.add(m);
            }
        }
        visibleMilestones.sort(Comparator.comparingInt(TrialJournalMilestoneTable::getScopeChapterId)
                .thenComparingInt(TrialJournalMilestoneTable::getRequiredSeals)
                .thenComparingInt(TrialJournalMilestoneTable::getId));
        for (TrialJournalMilestoneTable m : visibleMilestones) {
            int curSeals = counts.forScope(m.getScopeChapterId());
            boolean reached = Integer.compareUnsigned(curSeals, m.getRequiredSeals()) >= 0;
            boolean claimed = isMilestoneClaimed(state, m.getId(), m.getSeasonId());
            out.addMilestones(TrialJournalMilestoneView.newBuilder()
                    .setMilestoneId(m.getId())
                    .setScopeChapterId(m.getScopeChapterId())
                    .setSeasonId(m.getSeasonId())
                    .setRequiredSeals(m.getRequiredSeals())
                    .setCurrentSeals(curSeals)
                    .setTitle(m.getTitle())
                    .setDesc(m.getDesc())
                    .setBoundSandCount(m.getBoundSandCount())
                    .setBoundArtifactVoucherCount(m.getBoundArtifactVoucherCount())
                    .setBadgeKey(m.getBadgeKey())
                    .setBadgeName(m.getBadgeName())
                    .setCommentaryKey(m.getCommentaryKey())
                    .setCommentaryTitle(m.getCommentaryTitle())
                    .setCommentaryBody(m.getCommentaryBody())
                    .setGrantAppearanceId(m.getGrantAppearanceId())
                    .setGrantAppearanceName(tables.appearanceDisplayName(m.getGrantAppearanceId()))
                    .setGrantPetCosmeticId(m.getGrantPetCosmeticId())
                    .setGrantPetCosmeticName(tables.petCosmeticDisplayName(m.getGrantPetCosmeticId()))
                    .setGrantSkillVfxId(m.getGrantSkillVfxId())
                    .setGrantSkillVfxName(tables.skillVfxDisplayName(m.getGrantSkillVfxId()))
                    .setReached(reached)
                    .setClaimed(claimed)
                    .setClaimable(reached && !claimed && writableErr == OK));
        }

        for (TrialJournalTables.RedeemableArtifact ra : tables.redeemableArtifacts()) {
            long owned = player.bags().bag(BagType.INVENTORY).total(ra.item().getId())
                    + player.bags().bag(BagType.EQUIPMENT).total(ra.item().getId());
            out.addRedeemableArtifacts(TrialRedeemableArtifactOption.newBuilder()
                    .setItemConfigId(ra.item().getId())
                    .setArtifactEffectId(ra.artifact().getId())
                    .setName(ra.artifact().getName())
                    .setDescription(ra.artifact().getDescription())
                    .setIconKey(ra.item().getIconKey())
                    .setOwnedCount((int) Math.min(owned, Integer.MAX_VALUE)));
        }
        return out.build();
    }

    // ------------------------------------------------------------------ 内部工具

    private int checkWritable(ScenePlayer player, boolean countBattleGate) {
        if (player.frozen()) {
            return FROZEN;
        }
        if (player.inBattle()) {
            if (countBattleGate) {
                metrics.battleGateReject(SceneMetrics.BattleGate.TRIAL_JOURNAL);
            }
            return IN_BATTLE;
        }
        return OK;
    }

    private ClaimTrialJournalMilestoneResponse claimError(ScenePlayer player, long nowMs, int tipId) {
        return ClaimTrialJournalMilestoneResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setSnapshot(buildSnapshot(player, nowMs))
                .build();
    }

    private RedeemBoundArtifactVoucherResponse redeemError(ScenePlayer player, long nowMs, int tipId) {
        return RedeemBoundArtifactVoucherResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setSnapshot(buildSnapshot(player, nowMs))
                .build();
    }

    private static boolean isSeasonRowVisible(int rowSeasonId, int activeSeasonId) {
        return rowSeasonId == 0 || (activeSeasonId != 0 && rowSeasonId == activeSeasonId);
    }

    private static boolean isNormalBossTrialVictory(BattleSettlementData s) {
        return s != null && s.getBattleId() != 0L && s.getPlayerId() != 0L && !s.getFled()
                && s.getTotalRounds() > 0
                && s.getBossTrialKind() >= 1 && s.getBossTrialKind() <= 4
                && s.getBattleDifficulty() == 1
                && TrialJournalRules.isPlayerVictory(s.getOutcome(), s.getPlayerTeamIndex());
    }

    private static void bumpRevision(PlayerTrialJournalState.Builder state) {
        if (state.getStateRevision() != -1L) {
            state.setStateRevision(state.getStateRevision() + 1L);
        }
    }

    private static boolean testPackedBit(ByteString bits, int bitIndex) {
        if (bitIndex < 0) {
            return false;
        }
        int byteIdx = bitIndex >>> 3;
        return byteIdx < bits.size() && ((bits.byteAt(byteIdx) & (1 << (bitIndex & 7))) != 0);
    }

    private static ByteString setPackedBit(ByteString bits, int bitIndex) {
        int byteIdx = bitIndex >>> 3;
        byte[] arr = new byte[Math.max(bits.size(), byteIdx + 1)];
        bits.copyTo(arr, 0);
        arr[byteIdx] |= (byte) (1 << (bitIndex & 7));
        return ByteString.copyFrom(arr);
    }

    private boolean isSealLit(PlayerTrialJournalState state, int sealId, int entrySeasonId) {
        if (entrySeasonId != 0) {
            for (SeasonTrialJournalArchiveState archive : state.getSeasonRecordsList()) {
                if (archive.getSeasonId() == entrySeasonId) {
                    return archive.getLitSealIdsList().contains(sealId);
                }
            }
            return false;
        }
        return testPackedBit(state.getLitSealBits(), tables.sealBitIndex(sealId));
    }

    private boolean markSealLit(PlayerTrialJournalState.Builder state, int sealId, int entrySeasonId) {
        if (entrySeasonId != 0) {
            SeasonTrialJournalArchiveState.Builder archive = findOrAddSeasonArchive(state, entrySeasonId);
            if (archive.getLitSealIdsList().contains(sealId)) {
                return false;
            }
            archive.addLitSealIds(sealId);
            return true;
        }
        int bitIdx = tables.sealBitIndex(sealId);
        if (bitIdx < 0 || testPackedBit(state.getLitSealBits(), bitIdx)) {
            return false;
        }
        state.setLitSealBits(setPackedBit(state.getLitSealBits(), bitIdx));
        return true;
    }

    private boolean isMilestoneClaimed(PlayerTrialJournalState state, int milestoneId, int milestoneSeasonId) {
        if (milestoneSeasonId != 0) {
            for (SeasonTrialJournalArchiveState archive : state.getSeasonRecordsList()) {
                if (archive.getSeasonId() == milestoneSeasonId) {
                    return archive.getClaimedMilestoneIdsList().contains(milestoneId);
                }
            }
            return false;
        }
        return testPackedBit(state.getClaimedMilestoneBits(), tables.milestoneBitIndex(milestoneId));
    }

    private boolean markMilestoneClaimed(PlayerTrialJournalState.Builder state, int milestoneId, int milestoneSeasonId) {
        if (milestoneSeasonId != 0) {
            SeasonTrialJournalArchiveState.Builder archive = findOrAddSeasonArchive(state, milestoneSeasonId);
            if (archive.getClaimedMilestoneIdsList().contains(milestoneId)) {
                return false;
            }
            archive.addClaimedMilestoneIds(milestoneId);
            return true;
        }
        int bitIdx = tables.milestoneBitIndex(milestoneId);
        if (bitIdx < 0 || testPackedBit(state.getClaimedMilestoneBits(), bitIdx)) {
            return false;
        }
        state.setClaimedMilestoneBits(setPackedBit(state.getClaimedMilestoneBits(), bitIdx));
        return true;
    }

    private static SeasonTrialJournalArchiveState.Builder findOrAddSeasonArchive(
            PlayerTrialJournalState.Builder state, int seasonId) {
        for (int i = 0; i < state.getSeasonRecordsCount(); i++) {
            if (state.getSeasonRecords(i).getSeasonId() == seasonId) {
                return state.getSeasonRecordsBuilder(i);
            }
        }
        return state.addSeasonRecordsBuilder().setSeasonId(seasonId);
    }

    private static TrialJournalEntryRecordState findEntryRecord(
            com.game.player.store.state.PlayerTrialJournalStateOrBuilder state, int entryId, int ruleVersion) {
        for (TrialJournalEntryRecordState r : state.getRecordsList()) {
            if (r.getEntryId() == entryId && r.getRuleVersion() == ruleVersion) {
                return r;
            }
        }
        return null;
    }

    private static TrialJournalEntryRecordState.Builder findOrAddEntryRecord(
            PlayerTrialJournalState.Builder state, int entryId, int ruleVersion) {
        for (int i = 0; i < state.getRecordsCount(); i++) {
            TrialJournalEntryRecordState r = state.getRecords(i);
            if (r.getEntryId() == entryId && r.getRuleVersion() == ruleVersion) {
                return state.getRecordsBuilder(i);
            }
        }
        return state.addRecordsBuilder().setEntryId(entryId).setRuleVersion(ruleVersion);
    }

    private static boolean hasAnyLegacyEntryRecord(PlayerTrialJournalState state, int entryId, int currentRuleVersion) {
        for (TrialJournalEntryRecordState r : state.getRecordsList()) {
            if (r.getEntryId() == entryId && r.getRuleVersion() != currentRuleVersion
                    && (r.getSoloBest().getBestRounds() > 0 || r.getCoopBest().getBestRounds() > 0)) {
                return true;
            }
        }
        return false;
    }

    private static TrialJournalSoloRecord toSoloView(TrialJournalSoloRecordState s) {
        TrialJournalLoadoutSnapshotState lo = s.getLoadout();
        return TrialJournalSoloRecord.newBuilder()
                .setBestRounds(s.getBestRounds())
                .setAchievedAtMs(s.getAchievedAtMs())
                .setLoadout(TrialJournalLoadoutSnapshot.newBuilder()
                        .setPetTableId(lo.getPetTableId())
                        .addAllArtifactEffectIds(lo.getArtifactEffectIdsList())
                        .setSeasonSealId(lo.getSeasonSealId()))
                .build();
    }

    private static TrialJournalCoopRecord toCoopView(TrialJournalCoopRecordState c) {
        return TrialJournalCoopRecord.newBuilder()
                .setBestRounds(c.getBestRounds())
                .setPartySize(c.getPartySize())
                .setAchievedAtMs(c.getAchievedAtMs())
                .setPetTableId(c.getPetTableId())
                .addAllTeammateNames(c.getTeammateNamesList())
                .build();
    }

    private static final class EffectiveSealCounts {
        final Map<Integer, Integer> litByChapter = new TreeMap<>(Integer::compareUnsigned);
        final Map<Integer, Integer> totalByChapter = new TreeMap<>(Integer::compareUnsigned);
        int permanentLit;
        int permanentTotal;
        boolean anyNormalBreakthroughCleared;

        int forScope(int scopeChapterId) {
            return scopeChapterId == TrialJournalRules.SCOPE_ALL_PERMANENT
                    ? permanentLit
                    : litByChapter.getOrDefault(scopeChapterId, 0);
        }
    }

    private EffectiveSealCounts countEffectiveSeals(PlayerTrialJournalState state, int activeSeasonId) {
        EffectiveSealCounts counts = new EffectiveSealCounts();
        for (TrialJournalEntryTable entry : tables.entries()) {
            if (!entry.getEnabled() || !isSeasonRowVisible(entry.getSeasonId(), activeSeasonId)) {
                continue;
            }
            for (TrialJournalSealTable seal : tables.sealsForEntry(entry.getId())) {
                if (!seal.getEnabled()) {
                    continue;
                }
                boolean lit = isSealLit(state, seal.getId(), entry.getSeasonId());
                counts.totalByChapter.merge(entry.getChapterId(), 1, Integer::sum);
                if (lit) {
                    counts.litByChapter.merge(entry.getChapterId(), 1, Integer::sum);
                }
                if (entry.getSeasonId() == 0) {
                    counts.permanentTotal++;
                    if (lit) {
                        counts.permanentLit++;
                    }
                }
                if (lit && entry.getSeasonId() == 0 && entry.getDifficulty() == 1
                        && seal.getConditionType() == TrialJournalRules.COND_CLEAR) {
                    counts.anyNormalBreakthroughCleared = true;
                }
            }
        }
        return counts;
    }
}
