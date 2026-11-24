package com.game.scene.equip;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.player.store.state.ClientRequestRecordState;
import com.game.player.store.state.EquipmentFixedAffixState;
import com.game.player.store.state.EquipmentMaterialRewardLedgerState;
import com.game.player.store.state.EquipmentState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BagItemInfo;
import com.game.proto.BattleSettlementData;
import com.game.proto.ClaimEquipmentMaterialRewardRequest;
import com.game.proto.ClaimEquipmentMaterialRewardResponse;
import com.game.proto.CommitEquipmentCraftRequest;
import com.game.proto.CommitEquipmentCraftResponse;
import com.game.proto.EquipFixedAffixInfo;
import com.game.proto.EquipmentCraftChoice;
import com.game.proto.EquipmentCraftMaterial;
import com.game.proto.EquipmentCraftPreview;
import com.game.proto.EquipmentCraftRecipe;
import com.game.proto.EquipmentMaterialRewardSnapshot;
import com.game.proto.GetEquipmentCraftRulesResponse;
import com.game.proto.GetEquipmentMaterialRewardResponse;
import com.game.proto.PreviewEquipmentCraftRequest;
import com.game.proto.PreviewEquipmentCraftResponse;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.attribute.AttributeService;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagFeature;
import com.game.scene.bag.BagService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Bag;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerBags;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerClientRequestLedger.Verdict;
import com.game.scene.player.PlayerLevels;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.table.CommonErrorTip;
import com.game.table.EquipArtifactTable;
import com.game.table.EquipErrorTip.equip_error;
import com.game.table.EquipV1AffixTable;
import com.game.table.ItemTable;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 装备打造 / 首次安装固定词条（404–406）与装备材料自选领取（407–408；对齐 C++ {@code PlayerEquipmentV1System} /
 * {@code PlayerEquipmentMaterialRewardSystem}）。仅在场景逻辑线程调用。
 */
public final class EquipmentCraftService {

    public static final int DAILY_LIMIT = PlayerClientRequestLedger.DAILY_MATERIAL_LIMIT;
    public static final int BATTLE_UNITS = PlayerClientRequestLedger.BATTLE_MATERIAL_UNITS;
    public static final int CRAFTING_MATERIAL_ID = 3001;
    public static final int ENHANCEMENT_STONE_ID = 3002;
    private static final long DAY_MS = 86_400_000L;
    /** UTC+8 05:00 换日偏移（UTC + 3h）。 */
    private static final long SHIFT_MS = 10_800_000L;

    static final int OK = 0;
    static final int INVALID_TABLE_DATA = CommonErrorTip.common_error.kInvalidTableData_VALUE;
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;

    public record CommitResult(CommitEquipmentCraftResponse response, boolean attributesChanged) {
    }

    private record Prepared(int configId, long itemId, EquipmentState resultEquipment,
                            BagType holder, int materialId, int materialCount) {
    }

    private final EquipmentRules rules;
    private final EquipmentDisplay display;
    private final BagService bags;
    private final AttributeService attributes;
    private final SceneMetrics metrics;
    private final SceneClock clock;
    private final int rulesError;
    private final GetEquipmentCraftRulesResponse baseRules;

    public EquipmentCraftService(EquipmentRules rules, BagService bags, AttributeService attributes,
                                 SceneMetrics metrics, SceneClock clock) {
        this.rules = Objects.requireNonNull(rules);
        this.display = new EquipmentDisplay(rules);
        this.bags = Objects.requireNonNull(bags);
        this.attributes = Objects.requireNonNull(attributes);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
        GetEquipmentCraftRulesResponse.Builder built = GetEquipmentCraftRulesResponse.newBuilder();
        this.rulesError = buildBaseRules(rules.tables(), display, built);
        this.baseRules = this.rulesError == OK ? built.build() : GetEquipmentCraftRulesResponse.getDefaultInstance();
    }

    public long nowMillis() {
        return clock.epochMillis();
    }

    /** 404 获取打造配方、固定词条目录、权威指纹与当前 forge_revision。 */
    public GetEquipmentCraftRulesResponse getRules(ScenePlayer player) {
        if (!player.clientRequestLedger().isValid()) {
            return GetEquipmentCraftRulesResponse.newBuilder().setErrorMessage(tip(SERVICE_UNAVAILABLE)).build();
        }
        if (rulesError != OK) {
            return GetEquipmentCraftRulesResponse.newBuilder().setErrorMessage(tip(rulesError)).build();
        }
        return baseRules.toBuilder()
                .setErrorMessage(tip(OK))
                .setForgeRevision(player.clientRequestLedger().forgeRevision())
                .build();
    }

    /** 405 预览打造或首次安装固定词条。 */
    public PreviewEquipmentCraftResponse preview(ScenePlayer player, PreviewEquipmentCraftRequest request) {
        if (!player.clientRequestLedger().isValid()) {
            return PreviewEquipmentCraftResponse.newBuilder().setErrorMessage(tip(SERVICE_UNAVAILABLE)).build();
        }
        if (!request.hasChoice() || !request.getUnknownFields().asMap().isEmpty()) {
            return PreviewEquipmentCraftResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        if (rulesError != OK) {
            return PreviewEquipmentCraftResponse.newBuilder().setErrorMessage(tip(rulesError)).build();
        }
        int[] errorOut = new int[1];
        Prepared prepared = prepareChoice(player, request.getChoice(), errorOut);
        if (prepared == null) {
            return PreviewEquipmentCraftResponse.newBuilder().setErrorMessage(tip(errorOut[0])).build();
        }
        int unavailable = checkAvailability(player, prepared, false);
        EquipmentCraftPreview.Builder preview = EquipmentCraftPreview.newBuilder()
                .setChoice(request.getChoice())
                .setRulesFingerprint(baseRules.getRulesFingerprint())
                .setExpectedForgeRevision(player.clientRequestLedger().forgeRevision())
                .setResult(buildProductPreview(prepared.configId(), prepared.itemId(), prepared.resultEquipment()))
                .setMaterial(buildMaterial(player, prepared.materialId(), prepared.materialCount()))
                .setAvailable(unavailable == OK);
        if (unavailable != OK) {
            preview.setUnavailableTip(unavailable);
        }
        return PreviewEquipmentCraftResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setPreview(preview)
                .build();
    }

    /** 406 原子提交打造或首次安装固定词条（幂等重放优先于水位/冻结/战斗闸）。 */
    public CommitResult commit(ScenePlayer player, CommitEquipmentCraftRequest request, BagFeature bagView) {
        long reqId = request.getClientRequestId();
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), SERVICE_UNAVAILABLE, false), false);
        }
        if (reqId == 0 || !request.hasChoice() || !request.getUnknownFields().asMap().isEmpty()) {
            return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), INVALID_PARAMETER, true), false);
        }
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(request.toBuilder().clearClientRequestId().build());
        var check = ledger.check(reqId, PlayerClientRequestLedger.KIND_EQUIPMENT_CRAFT, fingerprint);
        if (check.verdict() == Verdict.REPLAY) {
            ClientRequestRecordState first = check.record();
            return new CommitResult(appliedCraft(player, reqId, first.getEquipmentItemId(), first.getForgeRevision(), true, bagView), false);
        }
        if (check.verdict() == Verdict.MISMATCH) {
            return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), INVALID_PARAMETER, true), false);
        }
        if (request.getExpectedForgeRevision() != ledger.forgeRevision()) {
            return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), equip_error.kEquipEnhanceLevelChanged_VALUE, false), false);
        }
        if (rulesError != OK) {
            return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), rulesError, true), false);
        }
        if (!request.getRulesFingerprint().equals(baseRules.getRulesFingerprint())) {
            return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), INVALID_PARAMETER, true), false);
        }
        int[] errorOut = new int[1];
        Prepared prepared = prepareChoice(player, request.getChoice(), errorOut);
        if (prepared == null) {
            return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), errorOut[0], true), false);
        }
        int unavailable = checkAvailability(player, prepared, true);
        if (unavailable != OK) {
            return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), unavailable, true), false);
        }
        PlayerBags candidate = PlayerBags.restore(player.bags().toState());
        candidate.normalize(bags.tables());
        List<Bag.Drawn> removed = candidate.bag(BagType.INVENTORY).drainPlainExact(
                prepared.materialId(), Integer.toUnsignedLong(prepared.materialCount()));
        List<Bag.Written> gained = List.of();
        long resultItemId = prepared.itemId();
        boolean wornChanged = false;
        if (request.getChoice().getOperation() == EquipmentCraftChoice.Operation.CREATE_ITEM) {
            Bag.AddResult created = bags.prepareEquipmentGrant(player, candidate, Map.of(prepared.configId(), 1L));
            if (!created.ok() || created.written().isEmpty() || created.written().getFirst().guids().isEmpty()) {
                int tipId = created.tip() == Bag.NO_SPACE ? equip_error.kEquipBagFull_VALUE
                        : (created.tip() != OK ? created.tip() : equip_error.kEquipInternalError_VALUE);
                return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), tipId, true), false);
            }
            gained = created.written();
            resultItemId = gained.getFirst().guids().getFirst();
            BagItem createdItem = candidate.bag(BagType.INVENTORY).item(resultItemId);
            if (createdItem == null) {
                return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), equip_error.kEquipInternalError_VALUE, true), false);
            }
            createdItem.equipment(EquipmentState.getDefaultInstance());
        } else {
            BagItem target = candidate.bag(prepared.holder()).item(resultItemId);
            if (target == null) {
                return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), equip_error.kEquipItemNotFound_VALUE, true), false);
            }
            target.equipment(prepared.resultEquipment());
            wornChanged = prepared.holder() == BagType.EQUIPMENT;
        }
        long newRevision = ledger.forgeRevision() + 1L;
        player.bags().commitPrepared(candidate);
        ledger.setForgeRevision(newRevision);
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(reqId)
                .setKind(PlayerClientRequestLedger.KIND_EQUIPMENT_CRAFT)
                .setFingerprint(fingerprint)
                .setEquipmentItemId(resultItemId)
                .setForgeRevision(newRevision));
        bags.auditEquipment(player, removed, gained, AssetAudit.Reason.EQUIP_CRAFT, resultItemId, "{\"operation\":\"equipment_craft\"}");
        if (wornChanged) {
            attributes.equipmentChanged(player);
        }
        return new CommitResult(appliedCraft(player, reqId, resultItemId, newRevision, false, bagView), wornChanged);
    }

    /** 407 查询装备材料自选快照。 */
    public GetEquipmentMaterialRewardResponse getMaterialReward(ScenePlayer player, long nowMs) {
        if (!player.clientRequestLedger().isValid()) {
            return GetEquipmentMaterialRewardResponse.newBuilder().setErrorMessage(tip(SERVICE_UNAVAILABLE)).build();
        }
        EquipmentMaterialRewardSnapshot.Builder snap = EquipmentMaterialRewardSnapshot.newBuilder();
        int err = fillMaterialSnapshot(player, nowMs, snap);
        if (err != OK) {
            return GetEquipmentMaterialRewardResponse.newBuilder().setErrorMessage(tip(err)).build();
        }
        return GetEquipmentMaterialRewardResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setSnapshot(snap)
                .build();
    }

    /** 408 幂等自选领取装备打造材料（3001）与强化石（3002）。 */
    public ClaimEquipmentMaterialRewardResponse claimMaterialReward(ScenePlayer player,
                                                                    ClaimEquipmentMaterialRewardRequest request,
                                                                    BagFeature bagView, long nowMs) {
        long reqId = request.getClientRequestId();
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        long currentRev = ledger.materialReward().getRevision();
        if (!ledger.isValid()) {
            return rejectMaterial(reqId, currentRev, SERVICE_UNAVAILABLE, false);
        }
        if (reqId == 0 || !request.getUnknownFields().asMap().isEmpty()) {
            return rejectMaterial(reqId, currentRev, INVALID_PARAMETER, true);
        }
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(request.toBuilder().clearClientRequestId().build());
        var check = ledger.check(reqId, PlayerClientRequestLedger.KIND_EQUIPMENT_MATERIAL_REWARD, fingerprint);
        if (check.verdict() == Verdict.REPLAY) {
            return appliedMaterial(player, reqId, check.record().getMaterialRewardRevision(), true, bagView, nowMs);
        }
        if (check.verdict() == Verdict.MISMATCH) {
            return rejectMaterial(reqId, currentRev, INVALID_PARAMETER, true);
        }
        if (request.getExpectedRevision() != currentRev) {
            return rejectMaterial(reqId, currentRev, FEATURE_UNAVAILABLE, false);
        }
        EquipmentMaterialRewardSnapshot.Builder snap = EquipmentMaterialRewardSnapshot.newBuilder();
        int snapErr = fillMaterialSnapshot(player, nowMs, snap);
        if (snapErr != OK) {
            return rejectMaterial(reqId, currentRev, snapErr, true);
        }
        long craftingCount = Integer.toUnsignedLong(request.getCraftingMaterialCount());
        long stoneCount = Integer.toUnsignedLong(request.getEnhancementStoneCount());
        long total = craftingCount + stoneCount;
        if (total == 0 || total != Integer.toUnsignedLong(snap.getBatchUnits())) {
            return rejectMaterial(reqId, currentRev, INVALID_PARAMETER, true);
        }
        int writableTip = checkWritable(player, true);
        if (writableTip != OK) {
            return rejectMaterial(reqId, currentRev, writableTip, true);
        }
        if (currentRev == -1L || ledger.nextSeq() == -1L) {
            return rejectMaterial(reqId, currentRev, FEATURE_UNAVAILABLE, true);
        }
        Map<Integer, Long> grants = new LinkedHashMap<>();
        if (craftingCount > 0) {
            grants.put(CRAFTING_MATERIAL_ID, craftingCount);
        }
        if (stoneCount > 0) {
            grants.put(ENHANCEMENT_STONE_ID, stoneCount);
        }
        PlayerBags candidate = PlayerBags.restore(player.bags().toState());
        candidate.normalize(bags.tables());
        Bag.AddResult added = bags.prepareEquipmentGrant(player, candidate, grants);
        if (!added.ok()) {
            int tipId = added.tip() == Bag.NO_SPACE ? equip_error.kEquipBagFull_VALUE : added.tip();
            return rejectMaterial(reqId, currentRev, tipId, true);
        }
        long newRev = currentRev + 1L;
        EquipmentMaterialRewardLedgerState nextState = ledger.materialReward().toBuilder()
                .setAvailableUnits(ledger.materialReward().getAvailableUnits() - total)
                .setRevision(newRev)
                .build();
        player.bags().commitPrepared(candidate);
        ledger.setMaterialReward(nextState);
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(reqId)
                .setKind(PlayerClientRequestLedger.KIND_EQUIPMENT_MATERIAL_REWARD)
                .setFingerprint(fingerprint)
                .setMaterialRewardRevision(newRev)
                .setCraftingMaterialCount(request.getCraftingMaterialCount())
                .setEnhancementStoneCount(request.getEnhancementStoneCount()));
        bags.auditEquipment(player, List.of(), added.written(), AssetAudit.Reason.ITEM_AWARD, reqId,
                "{\"operation\":\"equipment_material_reward\"}");
        return appliedMaterial(player, reqId, newRev, false, bagView, nowMs);
    }

    /** 结算应用同帧累加每日装备材料自选额度（返回本次实际增加的份数 0 或 10）。 */
    public static int creditBattleMaterialReward(ScenePlayer player, BattleSettlementData settlement, long nowMs) {
        if (!isEligibleBattle(settlement)) {
            return 0;
        }
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return 0;
        }
        long[] day = resolveDay(nowMs);
        if (day == null) {
            return 0;
        }
        long start = day[0];
        EquipmentMaterialRewardLedgerState prev = ledger.materialReward();
        // 时钟回拨不重新开放当日额度；仅跨入新游戏日时清零今日已领计数，保留余额
        if (Long.compareUnsigned(start, prev.getDayStartsAtMs()) < 0) {
            return 0;
        }
        int earned = Long.compareUnsigned(start, prev.getDayStartsAtMs()) > 0 ? 0 : prev.getEarnedToday();
        if (Integer.compareUnsigned(earned, DAILY_LIMIT) >= 0) {
            return 0;
        }
        if (Long.compareUnsigned(prev.getAvailableUnits(), -1L - BATTLE_UNITS) > 0) {
            return 0;
        }
        ledger.setMaterialReward(prev.toBuilder()
                .setDayStartsAtMs(start)
                .setEarnedToday(earned + BATTLE_UNITS)
                .setAvailableUnits(prev.getAvailableUnits() + BATTLE_UNITS)
                .build());
        return BATTLE_UNITS;
    }

    public static boolean isEligibleBattle(BattleSettlementData settlement) {
        if (settlement.getBattleId() == 0 || settlement.getPlayerId() == 0 || settlement.getFled()
                || settlement.getTotalRounds() == 0 || Integer.compareUnsigned(settlement.getPlayerTeamIndex(), 1) > 0) {
            return false;
        }
        eBattleOutcome outcome = settlement.getOutcome();
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN
                && outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN
                && outcome != eBattleOutcome.BATTLE_OUTCOME_DRAW) {
            return false;
        }
        return switch (settlement.getMatchMode()) {
            case MatchMode.MATCH_MODE_PVE_SOLO_VALUE, MatchMode.MATCH_MODE_PVE_TEAM_VALUE ->
                    (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && settlement.getPlayerTeamIndex() == 0)
                            || (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN && settlement.getPlayerTeamIndex() == 1);
            case MatchMode.MATCH_MODE_1V1_VALUE, MatchMode.MATCH_MODE_5V5_VALUE, MatchMode.MATCH_MODE_PVP_TEAM_VALUE -> true;
            default -> false;
        };
    }

    /** 与 go/shared/gameday 同口径的 UTC+8 05:00 游戏日起止毫秒；失败返回 null。 */
    public static long[] resolveDay(long nowMs) {
        long rem = Long.remainderUnsigned(nowMs, DAY_MS);
        long offset = Long.remainderUnsigned(rem + SHIFT_MS, DAY_MS);
        if (Long.compareUnsigned(nowMs, offset) < 0) {
            return null;
        }
        long start = nowMs - offset;
        long next = start + DAY_MS;
        if (Long.compareUnsigned(next, start) <= 0) {
            return null;
        }
        return new long[]{start, next};
    }

    // ------------------------------------------------------------------ 内部辅助

    private int checkWritable(ScenePlayer player, boolean countMetric) {
        if (player.frozen()) {
            return equip_error.kEquipFrozen_VALUE;
        }
        if (player.inBattle() || player.battle().recovery() != com.game.scene.battle.PlayerBattle.Recovery.READY) {
            if (countMetric) {
                metrics.battleGateReject(SceneMetrics.BattleGate.EQUIPMENT);
            }
            return equip_error.kEquipInBattle_VALUE;
        }
        return OK;
    }

    private Prepared prepareChoice(ScenePlayer player, EquipmentCraftChoice choice, int[] errorOut) {
        if (!choice.getUnknownFields().asMap().isEmpty()) {
            errorOut[0] = FEATURE_UNAVAILABLE;
            return null;
        }
        EquipmentTables tables = rules.tables();
        if (choice.getOperation() == EquipmentCraftChoice.Operation.CREATE_ITEM) {
            if (choice.getItemId() != 0 || choice.getAffixSlot() != 0 || choice.getAffixId() != 0) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            ItemTable row = tables.item(choice.getConfigId());
            if (row == null || row.getEquipProfile() == 0 || !validTemplate(row, tables)) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            return new Prepared(row.getId(), 0L, EquipmentState.getDefaultInstance(), BagType.INVENTORY,
                    row.getForgeMaterialId(), row.getForgeMaterialCount());
        }
        if (choice.getOperation() != EquipmentCraftChoice.Operation.INSTALL_AFFIX
                || choice.getConfigId() != 0 || choice.getItemId() == 0 || choice.getAffixId() == 0) {
            errorOut[0] = INVALID_PARAMETER;
            return null;
        }
        BagType holder = BagType.INVENTORY;
        BagItem found = player.bags().bag(BagType.INVENTORY).item(choice.getItemId());
        if (found == null) {
            found = player.bags().bag(BagType.EQUIPMENT).item(choice.getItemId());
            holder = BagType.EQUIPMENT;
        }
        if (found == null) {
            errorOut[0] = equip_error.kEquipItemNotFound_VALUE;
            return null;
        }
        ItemTable row = tables.item(found.configId());
        EquipV1AffixTable affix = tables.fixedAffix(choice.getAffixId());
        if (row == null || affix == null || !FixedEquipmentEffects.validAffix(affix, tables)
                || poolFor(row.getEquipProfile(), choice.getAffixSlot()) != affix.getPool()
                || found.size() != 1 || !FixedEquipmentEffects.validSelections(found, row.getEquipProfile(), tables)) {
            errorOut[0] = INVALID_PARAMETER;
            return null;
        }
        EquipmentState current = found.equipment();
        if (!current.getUnknownFields().asMap().isEmpty()) {
            errorOut[0] = FEATURE_UNAVAILABLE;
            return null;
        }
        for (EquipmentFixedAffixState selected : current.getFixedAffixesList()) {
            if (!selected.getUnknownFields().asMap().isEmpty()) {
                errorOut[0] = FEATURE_UNAVAILABLE;
                return null;
            }
            if (selected.getSlot() == choice.getAffixSlot() || selected.getAffixId() == choice.getAffixId()) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
        }
        EquipmentState nextEquip = current.toBuilder()
                .addFixedAffixes(EquipmentFixedAffixState.newBuilder()
                        .setSlot(choice.getAffixSlot())
                        .setAffixId(choice.getAffixId()))
                .build();
        return new Prepared(found.configId(), found.guid(), nextEquip, holder,
                affix.getMaterialId(), affix.getMaterialCount());
    }

    private int checkAvailability(ScenePlayer player, Prepared prepared, boolean countMetric) {
        int writable = checkWritable(player, countMetric);
        if (writable != OK) {
            return writable;
        }
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (ledger.forgeRevision() == -1L || ledger.nextSeq() == -1L) {
            return SERVICE_UNAVAILABLE;
        }
        ItemTable row = rules.tables().item(prepared.configId());
        if (row == null) {
            return SERVICE_UNAVAILABLE;
        }
        if (Integer.compareUnsigned(PlayerLevels.effective(player.level()), row.getEquipLevel()) < 0) {
            return equip_error.kEquipLevelNotEnough_VALUE;
        }
        long needed = Integer.toUnsignedLong(prepared.materialCount());
        Bag inventory = player.bags().bag(BagType.INVENTORY);
        if (bags.tables().hasUnknownItemSchema(prepared.materialId())) {
            return FEATURE_UNAVAILABLE;
        }
        if (inventory.plainQuantity(prepared.materialId()) < needed) {
            return inventory.total(prepared.materialId()) >= needed
                    ? FEATURE_UNAVAILABLE
                    : equip_error.kEquipEnhanceMaterialNotEnough_VALUE;
        }
        if (prepared.itemId() == 0) {
            // 打造新装备需保证扣完材料后人物背包放得下 1 件新装备（且该装备未被禁获）
            if (bags.isItemGainBlocked(player, prepared.configId())) {
                return BagService.REFUSED;
            }
            PlayerBags probe = PlayerBags.restore(player.bags().toState());
            probe.normalize(bags.tables());
            probe.bag(BagType.INVENTORY).drainPlainExact(prepared.materialId(), needed);
            if (probe.bag(BagType.INVENTORY).freeCells() <= 0) {
                return equip_error.kEquipBagFull_VALUE;
            }
        }
        return OK;
    }

    private BagItemInfo buildProductPreview(int configId, long itemId, EquipmentState equipmentState) {
        ItemTable row = rules.tables().item(configId);
        if (row == null) {
            return BagItemInfo.getDefaultInstance();
        }
        BagItemInfo.Builder info = BagItemInfo.newBuilder()
                .setItemId(itemId)
                .setConfigId(configId)
                .setCount(1)
                .setMaxStack(row.getMaxStackSize())
                .setEquipKind(row.getEquipKind());
        PlayerBags tempBags = PlayerBags.restore(com.game.player.store.state.BagState.newBuilder()
                .addItems(com.game.player.store.state.BagItemState.newBuilder()
                        .setItemUuid(itemId == 0 ? 1L : itemId)
                        .setConfigId(configId)
                        .setStackSize(1)
                        .setPos(0)
                        .setBagType(BagType.INVENTORY.code())
                        .setEquipment(equipmentState))
                .build());
        tempBags.normalize(bags.tables());
        BagItem synthetic = tempBags.bag(BagType.INVENTORY).item(itemId == 0 ? 1L : itemId);
        if (synthetic != null) {
            display.item(synthetic, info);
        }
        return info.setItemId(itemId).build();
    }

    private EquipmentCraftMaterial buildMaterial(ScenePlayer player, int materialId, int count) {
        ItemTable mat = rules.tables().item(materialId);
        Bag inv = player.bags().bag(BagType.INVENTORY);
        EquipmentCraftMaterial.Builder b = EquipmentCraftMaterial.newBuilder()
                .setConfigId(materialId)
                .setCount(count)
                .setOwned(inv.total(materialId))
                .setAvailable(inv.plainQuantity(materialId));
        if (mat != null) {
            b.setName(mat.getName()).setIconKey(mat.getIconKey());
        }
        return b.build();
    }

    private int fillMaterialSnapshot(ScenePlayer player, long nowMs, EquipmentMaterialRewardSnapshot.Builder out) {
        long[] day = resolveDay(nowMs);
        if (day == null) {
            return INVALID_PARAMETER;
        }
        ItemTable craftMat = validMaterialItem(rules.tables().item(CRAFTING_MATERIAL_ID));
        ItemTable enhanceStone = validMaterialItem(rules.tables().item(ENHANCEMENT_STONE_ID));
        if (craftMat == null || enhanceStone == null) {
            return INVALID_TABLE_DATA;
        }
        EquipmentMaterialRewardLedgerState state = player.clientRequestLedger().materialReward();
        int earnedToday = state.getDayStartsAtMs() == day[0] ? state.getEarnedToday() : 0;
        int batchUnits = (int) Math.min(Integer.toUnsignedLong(DAILY_LIMIT), state.getAvailableUnits());
        int writable = checkWritable(player, false);
        boolean canClaim = batchUnits > 0 && writable == OK && state.getRevision() != -1L;
        out.setAvailable(true)
                .setDayStartsAtMs(day[0])
                .setNextResetAtMs(day[1])
                .setEarnedToday(earnedToday)
                .setDailyLimit(DAILY_LIMIT)
                .setAvailableUnits(state.getAvailableUnits())
                .setBatchUnits(batchUnits)
                .setRevision(state.getRevision())
                .setCraftingMaterial(buildMaterial(player, craftMat.getId(), 0))
                .setEnhancementStone(buildMaterial(player, enhanceStone.getId(), 0))
                .setCanClaim(canClaim);
        if (batchUnits == 0) {
            out.setUnavailableReason("完成主要战斗可获得材料自选份数，每日最多20份。");
        } else if (writable == equip_error.kEquipInBattle_VALUE) {
            out.setUnavailableReason("战斗中暂不能领取，请在结算完成后重试。");
        } else if (!canClaim) {
            out.setUnavailableReason("当前状态暂不能领取，请稍后重试。");
        }
        return OK;
    }

    private CommitEquipmentCraftResponse appliedCraft(ScenePlayer player, long reqId, long itemId,
                                                      long resultRevision, boolean replayed, BagFeature bagView) {
        return CommitEquipmentCraftResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setClientRequestId(reqId)
                .setOutcome(CommitEquipmentCraftResponse.Outcome.APPLIED)
                .setDurable(isForgeDurable(player, resultRevision))
                .setReplayed(replayed)
                .setItemId(itemId)
                .setResultForgeRevision(resultRevision)
                .setCurrentForgeRevision(player.clientRequestLedger().forgeRevision())
                .setBag(bagView.snapshot(player, BagType.INVENTORY))
                .setEquipment(bagView.snapshot(player, BagType.EQUIPMENT))
                .build();
    }

    private static CommitEquipmentCraftResponse rejectCraft(long reqId, long currentRev, int tipId, boolean known) {
        return CommitEquipmentCraftResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setClientRequestId(reqId)
                .setOutcome(known ? CommitEquipmentCraftResponse.Outcome.REJECTED : CommitEquipmentCraftResponse.Outcome.UNKNOWN)
                .setCurrentForgeRevision(currentRev)
                .build();
    }

    private ClaimEquipmentMaterialRewardResponse appliedMaterial(ScenePlayer player, long reqId, long resultRevision,
                                                                 boolean replayed, BagFeature bagView, long nowMs) {
        EquipmentMaterialRewardSnapshot.Builder snap = EquipmentMaterialRewardSnapshot.newBuilder();
        fillMaterialSnapshot(player, nowMs, snap);
        return ClaimEquipmentMaterialRewardResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setClientRequestId(reqId)
                .setOutcome(ClaimEquipmentMaterialRewardResponse.Outcome.APPLIED)
                .setDurable(isMaterialDurable(player, resultRevision))
                .setReplayed(replayed)
                .setResultRevision(resultRevision)
                .setCurrentRevision(player.clientRequestLedger().materialReward().getRevision())
                .setBag(bagView.snapshot(player, BagType.INVENTORY))
                .setSnapshot(snap)
                .build();
    }

    private static ClaimEquipmentMaterialRewardResponse rejectMaterial(long reqId, long currentRev, int tipId, boolean known) {
        return ClaimEquipmentMaterialRewardResponse.newBuilder()
                .setErrorMessage(tip(tipId))
                .setClientRequestId(reqId)
                .setOutcome(known ? ClaimEquipmentMaterialRewardResponse.Outcome.REJECTED
                        : ClaimEquipmentMaterialRewardResponse.Outcome.UNKNOWN)
                .setCurrentRevision(currentRev)
                .build();
    }

    private static boolean isForgeDurable(ScenePlayer player, long revision) {
        PlayerState persisted = player.persistedState();
        return revision != 0 && persisted != null && persisted.hasClientRequestLedger()
                && Long.compareUnsigned(persisted.getClientRequestLedger().getForgeRevision(), revision) >= 0;
    }

    private static boolean isMaterialDurable(ScenePlayer player, long revision) {
        PlayerState persisted = player.persistedState();
        return revision != 0 && persisted != null && persisted.hasClientRequestLedger()
                && persisted.getClientRequestLedger().hasMaterialReward()
                && Long.compareUnsigned(persisted.getClientRequestLedger().getMaterialReward().getRevision(), revision) >= 0;
    }

    private static int poolFor(int profile, int slot) {
        if (slot < 1 || slot > 2) {
            return 0;
        }
        if (profile == 1) {
            return slot;
        }
        return profile == 2 ? 3 : 0;
    }

    private static ItemTable validMaterialItem(ItemTable row) {
        return row != null && row.getId() != 0 && row.getMaxStackSize() != 0
                && row.getEquipKind() == 0 && row.getBattleUsable() == 0 ? row : null;
    }

    private static boolean validTemplate(ItemTable row, EquipmentTables tables) {
        if (row.getMaxStackSize() != 1 || row.getAffixPool() != 0 || row.getAffixRule() != 0
                || validMaterialItem(tables.item(row.getForgeMaterialId())) == null || row.getForgeMaterialCount() == 0) {
            return false;
        }
        int profile = row.getEquipProfile();
        if (profile == 1) {
            return row.getEquipKind() >= 11 && row.getEquipKind() <= 14 && row.getArtifactId() == 0;
        }
        if (profile == 2) {
            return row.getEquipKind() == 15 && row.getArtifactId() == 0;
        }
        EquipArtifactTable artifact = tables.artifact(row.getArtifactId());
        return profile == 3 && row.getEquipKind() == 16 && FixedEquipmentEffects.validArtifact(artifact);
    }

    private static int buildBaseRules(EquipmentTables tables, EquipmentDisplay display,
                                      GetEquipmentCraftRulesResponse.Builder out) {
        List<ItemTable> recipes = new ArrayList<>();
        for (ItemTable row : tables.items()) {
            if (row.getEquipProfile() == 0) {
                continue;
            }
            if (!validTemplate(row, tables)) {
                return INVALID_TABLE_DATA;
            }
            recipes.add(row);
        }
        recipes.sort(Comparator.comparing(ItemTable::getId, Integer::compareUnsigned));
        List<EquipV1AffixTable> affixes = new ArrayList<>();
        Map<Long, Integer> caps = new HashMap<>();
        for (EquipV1AffixTable row : tables.fixedAffixes()) {
            if (!FixedEquipmentEffects.validAffix(row, tables)) {
                return INVALID_TABLE_DATA;
            }
            long key = (((long) row.getEffect()) << 32) | Integer.toUnsignedLong(row.getEffectParam());
            Integer prev = caps.putIfAbsent(key, row.getSourceCap());
            if (prev != null && prev != row.getSourceCap()) {
                return INVALID_TABLE_DATA;
            }
            affixes.add(row);
        }
        if (recipes.isEmpty() || affixes.isEmpty()) {
            return FEATURE_UNAVAILABLE;
        }
        affixes.sort(Comparator.comparing(EquipV1AffixTable::getId, Integer::compareUnsigned));
        for (ItemTable row : recipes) {
            BagItemInfo.Builder prod = BagItemInfo.newBuilder()
                    .setConfigId(row.getId())
                    .setCount(1)
                    .setMaxStack(row.getMaxStackSize())
                    .setEquipKind(row.getEquipKind())
                    .setName(row.getName())
                    .setDescription(row.getDescription())
                    .setIconKey(row.getIconKey())
                    .setEquipLevel(row.getEquipLevel())
                    .setEquipClass(row.getEquipClass());
            display.fillFixedDisplay(null, row, prod);
            out.addRecipes(EquipmentCraftRecipe.newBuilder()
                    .setProduct(prod)
                    .setMaterialConfigId(row.getForgeMaterialId())
                    .setMaterialCount(row.getForgeMaterialCount()));
        }
        for (EquipV1AffixTable row : affixes) {
            out.addAffixes(EquipmentDisplay.affixInfo(row));
        }
        try {
            ByteArrayOutputStream digestBuf = new ByteArrayOutputStream();
            for (ItemTable row : recipes) digestBuf.write(row.toByteArray());
            for (EquipV1AffixTable row : affixes) digestBuf.write(row.toByteArray());
            for (EquipArtifactTable row : tables.artifacts()) digestBuf.write(row.toByteArray());
            digestBuf.write(out.build().toByteArray());
            out.setRulesFingerprint(PlayerClientRequestLedger.sha256Prefix16(digestBuf.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return OK;
    }
}
