package com.game.scene.equip;

import static com.game.scene.world.SceneMessageIds.tip;

import com.game.player.store.state.ClientRequestRecordState;
import com.game.player.store.state.EquipmentFixedAffixState;
import com.game.player.store.state.EquipmentGemState;
import com.game.player.store.state.EquipmentMaterialRewardLedgerState;
import com.game.player.store.state.EquipmentState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BagItemInfo;
import com.game.proto.BattleSettlementData;
import com.game.proto.ClaimEquipmentMaterialRewardRequest;
import com.game.proto.ClaimEquipmentMaterialRewardResponse;
import com.game.proto.CommitEquipmentCraftRequest;
import com.game.proto.CommitEquipmentCraftResponse;
import com.game.proto.CraftMasteryInfo;
import com.game.proto.EquipAffixQualityInfo;
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
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.player.Bag;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerBags;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerClientRequestLedger.Verdict;
import com.game.scene.player.PlayerLevels;
import com.game.scene.player.Wallet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.SwitchPhase;
import com.game.table.CommonErrorTip;
import com.game.table.EquipAffixQualityTable;
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
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/**
 * 装备打造 / 首次安装固定词条 / 重铸换条 / 品相打磨（404–406）与装备材料自选领取（407–408；对齐 C++
 * {@code PlayerEquipmentV1System} / {@code PlayerEquipmentMaterialRewardSystem}）。仅在场景逻辑线程调用。
 */
public final class EquipmentCraftService {

    public static final int DAILY_LIMIT = PlayerClientRequestLedger.DAILY_MATERIAL_LIMIT;
    public static final int MAX_CLAIM_BATCH_UNITS = PlayerClientRequestLedger.MAX_CLAIM_BATCH_UNITS;
    public static final int MIN_CLAIM_STEP_UNITS = PlayerClientRequestLedger.MIN_CLAIM_STEP_UNITS;
    public static final int BATTLE_UNITS = PlayerClientRequestLedger.BATTLE_MATERIAL_UNITS;
    public static final int BONUS_UNITS = PlayerClientRequestLedger.BONUS_MATERIAL_UNITS;
    public static final int LIFETIME_LIMIT = PlayerClientRequestLedger.LIFETIME_MATERIAL_LIMIT;
    public static final int TRADABLE_LIFETIME_LIMIT = PlayerClientRequestLedger.TRADABLE_LIFETIME_LIMIT;
    public static final int WEEKLY_LIMIT = PlayerClientRequestLedger.WEEKLY_MATERIAL_LIMIT;
    public static final int PVP_MIN_ROUNDS_FOR_LOSS = 3;
    public static final int PVP_MIN_MANUAL_ROUNDS_FOR_LOSS = 2;
    public static final int CRAFTING_MATERIAL_ID = 3001;
    public static final int ENHANCEMENT_STONE_ID = 3002;
    private static final long DAY_MS = 86_400_000L;
    private static final long WEEK_MS = 7L * DAY_MS;
    /** UTC+8 05:00 换日偏移（UTC + 3h）。 */
    private static final long SHIFT_MS = 10_800_000L;
    /** 1970-01-04 21:00:00 UTC = 1970-01-05 05:00:00 UTC+8（首个完整周一 05:00 切周基准）。 */
    private static final long FIRST_MONDAY_05_UTC8_MS = 3L * DAY_MS + 21L * 3_600_000L;

    /** 单局战斗材料额度增量与防挂机裁决明细（供结算记录与 BattleEndS2C 共用）。 */
    public record BattleCreditDelta(int totalUnitsAdded, int tradableUnitsAdded, boolean pvpLossIneligible) {
        public static final BattleCreditDelta NONE = new BattleCreditDelta(0, 0, false);
    }

    private record BattleEvaluation(boolean eligible, boolean isWin, boolean pvpLossIneligible,
                                    int baseUnits, int bonusUnits) {
        static final BattleEvaluation INELIGIBLE = new BattleEvaluation(false, false, false, 0, 0);
    }

    static final int OK = 0;
    static final int INVALID_TABLE_DATA = CommonErrorTip.common_error.kInvalidTableData_VALUE;
    static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;

    public record CommitResult(CommitEquipmentCraftResponse response, boolean attributesChanged,
                               boolean mutated, ScenePlayer creditedProxyMaster) {
        public CommitResult(CommitEquipmentCraftResponse response, boolean attributesChanged) {
            this(response, attributesChanged, false, null);
        }
    }

    private static final class Prepared {
        int configId;
        long itemId;
        boolean originalBound;
        boolean resultBound;
        EquipmentState resultEquipment = EquipmentState.getDefaultInstance();
        BagType holder = BagType.INVENTORY;
        final Map<Integer, Long> consumed = new LinkedHashMap<>();
        int materialId;
        int materialCount;
        int extraMaterialId;
        int extraMaterialCount;
        long baseGoldCost;
        long proxyFeeGold;
        long proxyMasterPlayerId;
        String proxyMasterName = "";
        int effectiveMasteryTier = EquipCraftQualityRules.MASTERY_APPRENTICE;
        int targetAffixIndex = -1;
        boolean polishExempt;
        boolean rerollQualityOnReforge;
        boolean tradableOnly;
        boolean willBindItem;
        boolean bindConfirmBlocked;
    }

    private final EquipmentRules rules;
    private final EquipmentDisplay display;
    private final BagService bags;
    private final CurrencyService currency;
    private final AttributeService attributes;
    private final SceneMetrics metrics;
    private final SceneClock clock;
    private final int rulesError;
    private final GetEquipmentCraftRulesResponse baseRules;
    private IntUnaryOperator qualityRoll;

    public EquipmentCraftService(EquipmentRules rules, BagService bags, AttributeService attributes,
                                 SceneMetrics metrics, SceneClock clock) {
        this(rules, bags, null, attributes, metrics, clock);
    }

    public EquipmentCraftService(EquipmentRules rules, BagService bags, CurrencyService currency,
                                 AttributeService attributes, SceneMetrics metrics, SceneClock clock) {
        this.rules = Objects.requireNonNull(rules);
        this.display = new EquipmentDisplay(rules);
        this.bags = Objects.requireNonNull(bags);
        this.currency = currency;
        this.attributes = Objects.requireNonNull(attributes);
        this.metrics = Objects.requireNonNull(metrics);
        this.clock = Objects.requireNonNull(clock);
        GetEquipmentCraftRulesResponse.Builder built = GetEquipmentCraftRulesResponse.newBuilder();
        this.rulesError = buildBaseRules(rules.tables(), display, built);
        this.baseRules = this.rulesError == OK ? built.build() : GetEquipmentCraftRulesResponse.getDefaultInstance();
    }

    /** 仅测试注入确定性品相掷骰函数（传 null 恢复默认随机源）。 */
    public void setQualityRollForTest(IntUnaryOperator qualityRoll) {
        this.qualityRoll = qualityRoll;
    }

    public long nowMillis() {
        return clock.epochMillis();
    }

    public static CraftMasteryInfo buildForgeMastery(ScenePlayer player) {
        int exp = player.craft().forgeMasteryExp();
        int tier = EquipCraftQualityRules.masteryTierForExp(exp);
        return CraftMasteryInfo.newBuilder()
                .setTier(tier)
                .setTierName(EquipCraftQualityRules.masteryTierName(tier))
                .setExp(exp)
                .setNextTierExp(EquipCraftQualityRules.masteryNextExp(exp))
                .setTopQualityRateBps(EquipCraftQualityRules.forgeTopQualityRateBps(tier))
                .build();
    }

    public static String playerDisplayName(ScenePlayer player) {
        String name = player.name();
        if (name != null && !name.isEmpty()) {
            return name;
        }
        return "宗师#" + Long.toUnsignedString(player.playerId());
    }

    /** 404 获取打造配方、固定词条目录、5 档品相公示表、权威指纹、forge_revision 与铸器熟练度。 */
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
                .setForgeMastery(buildForgeMastery(player))
                .build();
    }

    /** 405 预览打造、首次装条、重铸换条或品相打磨。 */
    public PreviewEquipmentCraftResponse preview(ScenePlayer player, PreviewEquipmentCraftRequest request) {
        return preview(player, request, null);
    }

    public PreviewEquipmentCraftResponse preview(ScenePlayer player, PreviewEquipmentCraftRequest request,
                                                 SceneWorld world) {
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
        Prepared prepared = prepareChoice(player, request.getChoice(), world, errorOut);
        if (prepared == null) {
            return PreviewEquipmentCraftResponse.newBuilder().setErrorMessage(tip(errorOut[0])).build();
        }
        EquipmentState previewEquipment = prepared.resultEquipment;
        if (request.getChoice().getOperation() == EquipmentCraftChoice.Operation.POLISH_AFFIX
                && prepared.targetAffixIndex >= 0) {
            EquipmentFixedAffixState cur = previewEquipment.getFixedAffixes(prepared.targetAffixIndex);
            if (request.getChoice().getUseGuaranteeCharm()
                    || Integer.compareUnsigned(cur.getPolishPityCount() + 1, EquipCraftQualityRules.POLISH_PITY_LIMIT) >= 0) {
                EquipmentState.Builder eb = previewEquipment.toBuilder();
                eb.getFixedAffixesBuilder(prepared.targetAffixIndex)
                        .setQualityTier(EquipCraftQualityRules.MAX_QUALITY_TIER)
                        .setPolishPityCount(0);
                previewEquipment = eb.build();
            }
        }
        int unavailable = checkAvailability(player, prepared, false);
        EquipmentCraftPreview.Builder preview = EquipmentCraftPreview.newBuilder()
                .setChoice(request.getChoice())
                .setRulesFingerprint(baseRules.getRulesFingerprint())
                .setExpectedForgeRevision(player.clientRequestLedger().forgeRevision())
                .setResult(buildProductPreview(prepared.configId, prepared.itemId,
                        prepared.resultBound, previewEquipment))
                .setGoldCost(prepared.baseGoldCost)
                .setProxyFeeGold(prepared.proxyFeeGold)
                .setWillBindItem(prepared.willBindItem)
                .setAvailable(unavailable == OK);
        if (prepared.materialId != 0 && prepared.materialCount != 0) {
            preview.setMaterial(buildMaterial(player, prepared.materialId, prepared.materialCount, prepared.tradableOnly));
        }
        if (prepared.extraMaterialId != 0 && prepared.extraMaterialCount != 0) {
            preview.setExtraMaterial(buildMaterial(player, prepared.extraMaterialId, prepared.extraMaterialCount, prepared.tradableOnly));
        }
        if (!prepared.proxyMasterName.isEmpty()) {
            preview.setProxyMasterName(prepared.proxyMasterName);
        }
        if (unavailable != OK) {
            preview.setUnavailableTip(unavailable);
        }
        return PreviewEquipmentCraftResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setPreview(preview)
                .build();
    }

    /** 406 原子提交打造、首次装条、重铸换条或品相打磨（幂等重放优先于水位/冻结/战斗闸）。 */
    public CommitResult commit(ScenePlayer player, CommitEquipmentCraftRequest request, BagFeature bagView) {
        return commit(player, request, bagView, null);
    }

    public CommitResult commit(ScenePlayer player, CommitEquipmentCraftRequest request, BagFeature bagView,
                               SceneWorld world) {
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
            return new CommitResult(appliedCraft(player, reqId, first.getEquipmentItemId(), first.getForgeRevision(),
                    first.getEquipmentQualityTier(), first.getEquipmentPolishPityCount(),
                    first.getEquipmentGuaranteedByPity(), true, bagView), false);
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
        Prepared prepared = prepareChoice(player, request.getChoice(), world, errorOut);
        if (prepared == null) {
            return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), errorOut[0], true), false);
        }
        int unavailable = checkAvailability(player, prepared, true);
        if (unavailable != OK) {
            return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), unavailable, true), false);
        }

        // 计算品相掷骰、保底进度与宗师署名（全部先在局部变量算妥，无半写）。
        EquipmentCraftChoice.Operation op = request.getChoice().getOperation();
        EquipmentState.Builder nextEquip = prepared.resultEquipment.toBuilder();
        int resultQualityTier = 0;
        int resultPolishPityCount = 0;
        boolean guaranteedByPity = false;
        if ((op == EquipmentCraftChoice.Operation.INSTALL_AFFIX || prepared.rerollQualityOnReforge)
                && prepared.targetAffixIndex >= 0) {
            int tier = prepared.polishExempt
                    ? EquipCraftQualityRules.MAX_QUALITY_TIER
                    : rollQualityForMastery(prepared.effectiveMasteryTier);
            nextEquip.getFixedAffixesBuilder(prepared.targetAffixIndex)
                    .setQualityTier(tier)
                    .setPolishPityCount(0);
            resultQualityTier = tier;
            resultPolishPityCount = 0;
            guaranteedByPity = false;
        } else if (op == EquipmentCraftChoice.Operation.REFORGE_AFFIX && prepared.targetAffixIndex >= 0) {
            EquipmentFixedAffixState target = nextEquip.getFixedAffixes(prepared.targetAffixIndex);
            resultQualityTier = EquipCraftQualityRules.effectiveQualityTier(target.getQualityTier(), prepared.polishExempt);
            resultPolishPityCount = target.getPolishPityCount();
        } else if (op == EquipmentCraftChoice.Operation.POLISH_AFFIX && prepared.targetAffixIndex >= 0) {
            EquipmentFixedAffixState.Builder target = nextEquip.getFixedAffixesBuilder(prepared.targetAffixIndex);
            int currentTier = EquipCraftQualityRules.effectiveQualityTier(target.getQualityTier(), false);
            int rolledTier = rollQualityForMastery(prepared.effectiveMasteryTier);
            EquipCraftQualityRules.PolishResult polished = EquipCraftQualityRules.resolvePolish(
                    currentTier, target.getPolishPityCount(), rolledTier, request.getChoice().getUseGuaranteeCharm());
            target.setQualityTier(polished.newTier()).setPolishPityCount(polished.newPityCount());
            resultQualityTier = polished.newTier();
            resultPolishPityCount = polished.newPityCount();
            guaranteedByPity = polished.guaranteedByPity();
        }

        // 宗师（本人或主城原子代打宗师）产出极品非豁免词条时永久刻上宗师署名（0 战力加成）。
        if ((op == EquipmentCraftChoice.Operation.INSTALL_AFFIX || op == EquipmentCraftChoice.Operation.POLISH_AFFIX)
                && !prepared.polishExempt
                && resultQualityTier == EquipCraftQualityRules.MAX_QUALITY_TIER
                && prepared.effectiveMasteryTier == EquipCraftQualityRules.MASTERY_MASTER) {
            long signerId = prepared.proxyMasterPlayerId != 0L ? prepared.proxyMasterPlayerId : player.playerId();
            String signerName = prepared.proxyMasterPlayerId != 0L ? prepared.proxyMasterName : playerDisplayName(player);
            nextEquip.setCrafterPlayerId(signerId).setCrafterName(signerName);
        }

        long totalGold = prepared.baseGoldCost + prepared.proxyFeeGold;
        long nowSec = clock.epochMillis() / 1000L;
        ScenePlayer proxyMaster = null;
        long proxyNetGold = 0L;
        if (prepared.proxyMasterPlayerId != 0L) {
            proxyMaster = resolveValidProxyForgeMaster(player, prepared.proxyMasterPlayerId, world, prepared.proxyFeeGold, nowSec);
            if (proxyMaster == null) {
                return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(),
                        equip_error.kEquipProxyMasterUnavailable_VALUE, true), false);
            }
            proxyNetGold = EquipCraftQualityRules.proxyNetGold(prepared.proxyFeeGold);
        }

        boolean allowBindForBaseFee = op != EquipmentCraftChoice.Operation.UNSEAL_TO_TRADABLE
                && prepared.resultBound;
        long strictGold = prepared.proxyFeeGold + (allowBindForBaseFee ? 0L : prepared.baseGoldCost);
        long preferBindGold = allowBindForBaseFee ? prepared.baseGoldCost : 0L;
        CurrencyService.PreparedDeduction payment = null;
        if (strictGold > 0L || preferBindGold > 0L) {
            if (currency == null || strictGold < 0L || preferBindGold < 0L) {
                return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(),
                        equip_error.kEquipCraftGoldNotEnough_VALUE, true), false);
            }
            payment = currency.prepareSplitGoldDeduction(player, strictGold, preferBindGold);
            if (payment.tipId() != 0) {
                return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(),
                        equip_error.kEquipCraftGoldNotEnough_VALUE, true), false);
            }
        }

        PlayerBags candidate = PlayerBags.restore(player.bags().toState());
        candidate.normalize(bags.tables());
        Bag candInv = candidate.bag(BagType.INVENTORY);
        List<Bag.Drawn> removed = new ArrayList<>();
        boolean consumedBound = false;
        for (Map.Entry<Integer, Long> entry : prepared.consumed.entrySet()) {
            if (entry.getValue() <= 0L) {
                continue;
            }
            Bag.DrainOutcome outcome = candInv.drainPlainExact(entry.getKey(), entry.getValue(), prepared.tradableOnly);
            removed.addAll(outcome.drawn());
            consumedBound |= outcome.consumedBound();
        }
        List<Bag.Written> gained = List.of();
        long resultItemId = prepared.itemId;
        boolean wornChanged = false;
        if (op == EquipmentCraftChoice.Operation.CREATE_ITEM) {
            Bag.AddResult created = bags.prepareEquipmentGrant(player, candidate, Map.of(prepared.configId, 1L), consumedBound);
            if (!created.ok() || created.written().isEmpty() || created.written().getFirst().guids().isEmpty()) {
                int tipId = created.tip() == Bag.NO_SPACE ? equip_error.kEquipBagFull_VALUE
                        : (created.tip() != OK ? created.tip() : equip_error.kEquipInternalError_VALUE);
                return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), tipId, true), false);
            }
            gained = created.written();
            resultItemId = gained.getFirst().guids().getFirst();
            BagItem createdItem = candInv.item(resultItemId);
            if (createdItem == null) {
                return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), equip_error.kEquipInternalError_VALUE, true), false);
            }
            createdItem.equipment(EquipmentState.getDefaultInstance());
        } else {
            BagItem target = candidate.bag(prepared.holder).item(resultItemId);
            if (target == null) {
                return new CommitResult(rejectCraft(reqId, ledger.forgeRevision(), equip_error.kEquipItemNotFound_VALUE, true), false);
            }
            target.equipment(nextEquip.build());
            if (op == EquipmentCraftChoice.Operation.UPGRADE_TIER) {
                target.setConfigId(prepared.configId);
            } else if (op == EquipmentCraftChoice.Operation.UNSEAL_TO_TRADABLE) {
                target.setBound(false);
            } else if (op == EquipmentCraftChoice.Operation.INHERIT_ENHANCE) {
                target.setBound(true);
            } else if (consumedBound && !target.bound()) {
                target.setBound(true);
            }
            wornChanged = prepared.holder == BagType.EQUIPMENT;
        }

        AssetAudit.Reason goldReason = (op == EquipmentCraftChoice.Operation.POLISH_AFFIX
                || op == EquipmentCraftChoice.Operation.REFORGE_AFFIX
                || op == EquipmentCraftChoice.Operation.UPGRADE_TIER)
                ? AssetAudit.Reason.EQUIP_POLISH_REFORGE
                : AssetAudit.Reason.CRAFT_PROXY_FEE;
        AssetAudit.Reason itemReason = (op == EquipmentCraftChoice.Operation.POLISH_AFFIX
                || op == EquipmentCraftChoice.Operation.REFORGE_AFFIX
                || op == EquipmentCraftChoice.Operation.UPGRADE_TIER)
                ? AssetAudit.Reason.EQUIP_POLISH_REFORGE
                : AssetAudit.Reason.EQUIP_CRAFT;

        long newRevision = ledger.forgeRevision() + 1L;
        if (payment != null) {
            payment.commit(candidate, goldReason, reqId, "{\"operation\":\"equipment_craft\"}");
        } else {
            player.bags().commitPrepared(candidate);
        }
        if (proxyMaster != null && proxyNetGold > 0L && currency != null) {
            currency.add(proxyMaster, Wallet.GOLD, proxyNetGold, AssetAudit.Reason.CRAFT_PROXY_FEE, reqId, nowSec);
        }
        // 本人打造/装条/打磨自然涨 1 点铸器熟练度（无体力、无日清、只增不减；代打、重铸、解封、继承与无损升档不涨）。
        if (prepared.proxyMasterPlayerId == 0L
                && op != EquipmentCraftChoice.Operation.REFORGE_AFFIX
                && op != EquipmentCraftChoice.Operation.UNSEAL_TO_TRADABLE
                && op != EquipmentCraftChoice.Operation.INHERIT_ENHANCE
                && op != EquipmentCraftChoice.Operation.UPGRADE_TIER) {
            int curExp = player.craft().forgeMasteryExp();
            if (curExp != -1) {
                player.craft().replace(player.craft().state().toBuilder().setForgeMasteryExp(curExp + 1).build());
            }
        }
        ledger.setForgeRevision(newRevision);
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(reqId)
                .setKind(PlayerClientRequestLedger.KIND_EQUIPMENT_CRAFT)
                .setFingerprint(fingerprint)
                .setEquipmentItemId(resultItemId)
                .setForgeRevision(newRevision)
                .setEquipmentQualityTier(resultQualityTier)
                .setEquipmentPolishPityCount(resultPolishPityCount)
                .setEquipmentGuaranteedByPity(guaranteedByPity));
        bags.auditEquipment(player, removed, gained, itemReason, resultItemId, "{\"operation\":\"equipment_craft\"}");
        if (wornChanged) {
            attributes.equipmentChanged(player);
        }
        return new CommitResult(appliedCraft(player, reqId, resultItemId, newRevision,
                resultQualityTier, resultPolishPityCount, guaranteedByPity, false, bagView),
                wornChanged, true, proxyMaster);
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
        return claimMaterialReward(player, request, bagView, nowMs, null);
    }

    public ClaimEquipmentMaterialRewardResponse claimMaterialReward(ScenePlayer player,
                                                                    ClaimEquipmentMaterialRewardRequest request,
                                                                    BagFeature bagView, long nowMs,
                                                                    SceneWorld world) {
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
        EquipmentMaterialRewardLedgerState curState = ledger.materialReward();
        long availTradable = curState.getMigratedToV2()
                ? Math.min(curState.getAvailableTradableUnits(), curState.getAvailableUnits())
                : 0L;
        long batchTradable = Math.min(total, availTradable);
        // 账号级阶段一 60 份硬防线（fail-closed）：存在未由账号表确认的阶段一可交易份数时，
        // 禁止先铸造可交易材料，先触发异步同步并回 SERVICE_UNAVAILABLE，杜绝删角/双角竞态超发。
        boolean enforceAccountQuota = world != null && world.repository().supportsAccountStage1TradableSync();
        if (enforceAccountQuota && batchTradable > 0 && curState.getMigratedToV2()
                && Integer.compareUnsigned(curState.getTradableEarnedTotal(), curState.getStage1TradableConfirmed()) > 0) {
            world.syncAccountStage1Tradable(player, nowMs);
            return rejectMaterial(reqId, currentRev, SERVICE_UNAVAILABLE, false)
                    .toBuilder().setSnapshot(snap).build();
        }
        long craftingTradable = Math.min(craftingCount, batchTradable);
        long craftingBound = craftingCount - craftingTradable;
        long stoneTradable = Math.min(stoneCount, batchTradable - craftingTradable);
        long stoneBound = stoneCount - stoneTradable;

        Map<Integer, Long> tradableGrants = new LinkedHashMap<>();
        if (craftingTradable > 0) {
            tradableGrants.put(CRAFTING_MATERIAL_ID, craftingTradable);
        }
        if (stoneTradable > 0) {
            tradableGrants.put(ENHANCEMENT_STONE_ID, stoneTradable);
        }
        Map<Integer, Long> boundGrants = new LinkedHashMap<>();
        if (craftingBound > 0) {
            boundGrants.put(CRAFTING_MATERIAL_ID, craftingBound);
        }
        if (stoneBound > 0) {
            boundGrants.put(ENHANCEMENT_STONE_ID, stoneBound);
        }
        PlayerBags candidate = PlayerBags.restore(player.bags().toState());
        candidate.normalize(bags.tables());
        List<Bag.Written> allWritten = new ArrayList<>();
        if (!tradableGrants.isEmpty()) {
            Bag.AddResult addedTradable = bags.prepareEquipmentGrant(player, candidate, tradableGrants, false);
            if (!addedTradable.ok()) {
                int tipId = addedTradable.tip() == Bag.NO_SPACE ? equip_error.kEquipBagFull_VALUE : addedTradable.tip();
                return rejectMaterial(reqId, currentRev, tipId, true);
            }
            allWritten.addAll(addedTradable.written());
        }
        if (!boundGrants.isEmpty()) {
            Bag.AddResult addedBound = bags.prepareEquipmentGrant(player, candidate, boundGrants, true);
            if (!addedBound.ok()) {
                int tipId = addedBound.tip() == Bag.NO_SPACE ? equip_error.kEquipBagFull_VALUE : addedBound.tip();
                return rejectMaterial(reqId, currentRev, tipId, true);
            }
            allWritten.addAll(addedBound.written());
        }
        long newRev = currentRev + 1L;
        EquipmentMaterialRewardLedgerState nextState = curState.toBuilder()
                .setMigratedToV2(true)
                .setAvailableUnits(curState.getAvailableUnits() - total)
                .setAvailableTradableUnits(availTradable - batchTradable)
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
                .setEnhancementStoneCount(request.getEnhancementStoneCount())
                .setCraftingTradableCount((int) craftingTradable)
                .setEnhancementTradableCount((int) stoneTradable));
        bags.auditEquipment(player, List.of(), allWritten, AssetAudit.Reason.ITEM_AWARD, reqId,
                "{\"operation\":\"equipment_material_reward\"}");
        return appliedMaterial(player, reqId, newRev, false, bagView, nowMs);
    }

    /** 结算应用同帧累加两阶段装备材料自选额度，返回本次实际新增总份数（0/5/10/15）。 */
    public static int creditBattleMaterialReward(ScenePlayer player, BattleSettlementData settlement, long nowMs) {
        return creditBattleMaterialRewardDelta(player, settlement, nowMs).totalUnitsAdded();
    }

    /**
     * 结算应用同帧累加两阶段装备材料自选额度：
     * 第一阶段首套 660 份，胜局每满 10 份含至多 1 份可交易（跨角色/删角账号级硬上限 60 份）；
     * 第二阶段每周 150 份，零氪全绑定自用，持有有效仙途轻享卡（{@code afkPassExpiresAt > nowSec}）时每场有效胜局将其中 1 份解锁为市场可交易产出。
     */
    public static BattleCreditDelta creditBattleMaterialRewardDelta(ScenePlayer player, BattleSettlementData settlement, long nowMs) {
        boolean lightCardActive = Long.compareUnsigned(player.afkPassExpiresAt(), Long.divideUnsigned(nowMs, 1000L)) > 0;
        return creditBattleMaterialRewardDelta(player, settlement, nowMs, lightCardActive, player.knownAccountStage1TradableUsed());
    }

    public static BattleCreditDelta creditBattleMaterialRewardDelta(ScenePlayer player, BattleSettlementData settlement,
                                                                    long nowMs, boolean lightCardActive,
                                                                    int accountStage1TradableUsed) {
        BattleEvaluation eval = evaluateBattle(settlement);
        if (!eval.eligible()) {
            return new BattleCreditDelta(0, 0, eval.pvpLossIneligible());
        }
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return BattleCreditDelta.NONE;
        }
        long[] day = resolveDay(nowMs);
        long[] week = resolveWeek(nowMs);
        if (day == null || week == null) {
            return BattleCreditDelta.NONE;
        }
        long weekStart = week[0];
        EquipmentMaterialRewardLedgerState prev = ledger.materialReward();
        int prevLifetime = prev.getMigratedToV2() ? prev.getLifetimeEarned() : 0;
        int prevTradableTotal = prev.getMigratedToV2() ? prev.getTradableEarnedTotal() : 0;
        long prevAvailableTradable = prev.getMigratedToV2() ? prev.getAvailableTradableUnits() : 0L;
        int grossUnits = eval.baseUnits() + eval.bonusUnits();

        int addTotal;
        int addTradable = 0;
        EquipmentMaterialRewardLedgerState.Builder next = prev.toBuilder();

        if (Integer.compareUnsigned(prevLifetime, LIFETIME_LIMIT) < 0) {
            int stage1Remain = LIFETIME_LIMIT - prevLifetime;
            addTotal = Math.min(grossUnits, stage1Remain);
            int effectiveAccountUsed = Math.max(prevTradableTotal, Math.min(Math.max(0, accountStage1TradableUsed), TRADABLE_LIFETIME_LIMIT));
            if (eval.isWin() && addTotal >= BATTLE_UNITS
                    && Integer.compareUnsigned(effectiveAccountUsed, TRADABLE_LIFETIME_LIMIT) < 0) {
                addTradable = 1;
            }
            if (Long.compareUnsigned(prev.getAvailableUnits(), -1L - Integer.toUnsignedLong(addTotal)) > 0) {
                return BattleCreditDelta.NONE;
            }
            next.setMigratedToV2(true)
                    .setLifetimeEarned(prevLifetime + addTotal)
                    .setTradableEarnedTotal(prevTradableTotal + addTradable)
                    .setAvailableUnits(prev.getAvailableUnits() + Integer.toUnsignedLong(addTotal))
                    .setAvailableTradableUnits(prevAvailableTradable + Integer.toUnsignedLong(addTradable));
        } else {
            if (Long.compareUnsigned(weekStart, prev.getWeekStartsAtMs()) < 0) {
                return BattleCreditDelta.NONE;
            }
            int earnedWeek = Long.compareUnsigned(weekStart, prev.getWeekStartsAtMs()) > 0 ? 0 : prev.getEarnedThisWeek();
            if (Integer.compareUnsigned(earnedWeek, WEEKLY_LIMIT) >= 0) {
                return BattleCreditDelta.NONE;
            }
            addTotal = Math.min(grossUnits, WEEKLY_LIMIT - earnedWeek);
            addTradable = (lightCardActive && eval.isWin() && addTotal >= BATTLE_UNITS) ? 1 : 0;
            if (Long.compareUnsigned(prev.getAvailableUnits(), -1L - Integer.toUnsignedLong(addTotal)) > 0) {
                return BattleCreditDelta.NONE;
            }
            next.setMigratedToV2(true)
                    .setWeekStartsAtMs(weekStart)
                    .setEarnedThisWeek(earnedWeek + addTotal)
                    .setAvailableUnits(prev.getAvailableUnits() + Integer.toUnsignedLong(addTotal));
            if (addTradable > 0) {
                next.setAvailableTradableUnits(prevAvailableTradable + Integer.toUnsignedLong(addTradable));
            }
        }
        EquipmentMaterialRewardLedgerState built = next.build();
        if (!PlayerClientRequestLedger.isMaterialRewardValid(built)) {
            return BattleCreditDelta.NONE;
        }
        ledger.setMaterialReward(built);
        return new BattleCreditDelta(addTotal, addTradable, false);
    }

    public static boolean isEligibleBattle(BattleSettlementData settlement) {
        return evaluateBattle(settlement).eligible();
    }

    private static BattleEvaluation evaluateBattle(BattleSettlementData settlement) {
        if (settlement.getBattleId() == 0 || settlement.getPlayerId() == 0 || settlement.getFled()
                || settlement.getTotalRounds() == 0 || Integer.compareUnsigned(settlement.getPlayerTeamIndex(), 1) > 0
                || com.game.battle.engine.BattleConstants.isNpcIsolatedEncounterKind(settlement.getBossTrialKind())) {
            return BattleEvaluation.INELIGIBLE;
        }
        eBattleOutcome outcome = settlement.getOutcome();
        if (outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN
                && outcome != eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN
                && outcome != eBattleOutcome.BATTLE_OUTCOME_DRAW) {
            return BattleEvaluation.INELIGIBLE;
        }
        boolean playerWon = (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && settlement.getPlayerTeamIndex() == 0)
                || (outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN && settlement.getPlayerTeamIndex() == 1);
        return switch (settlement.getMatchMode()) {
            case MatchMode.MATCH_MODE_PVE_SOLO_VALUE, MatchMode.MATCH_MODE_PVE_TEAM_VALUE -> {
                if (!playerWon) {
                    yield BattleEvaluation.INELIGIBLE;
                }
                int bonus = Integer.compareUnsigned(settlement.getBattleDifficulty(), 2) >= 0 ? BONUS_UNITS : 0;
                yield new BattleEvaluation(true, true, false, BATTLE_UNITS, bonus);
            }
            case MatchMode.MATCH_MODE_1V1_VALUE, MatchMode.MATCH_MODE_5V5_VALUE,
                    MatchMode.MATCH_MODE_PVP_TEAM_VALUE, MatchMode.MATCH_MODE_POLARITY_5V5_VALUE,
                    MatchMode.MATCH_MODE_GUILD_WAR_PVP_VALUE, MatchMode.MATCH_MODE_SEASON_TOURNAMENT_VALUE -> {
                if (settlement.getCompetitiveRanked() && !settlement.getParticipated()) {
                    yield new BattleEvaluation(false, false, !playerWon, 0, 0);
                }
                if (playerWon) {
                    yield new BattleEvaluation(true, true, false, BATTLE_UNITS, BONUS_UNITS);
                }
                if (Integer.compareUnsigned(settlement.getTotalRounds(), PVP_MIN_ROUNDS_FOR_LOSS) < 0
                        || Integer.compareUnsigned(settlement.getManualActionRounds(), PVP_MIN_MANUAL_ROUNDS_FOR_LOSS) < 0
                        || !settlement.getEffectiveCombatContribution()) {
                    yield new BattleEvaluation(false, false, true, 0, 0);
                }
                yield new BattleEvaluation(true, false, false, BATTLE_UNITS, 0);
            }
            case MatchMode.MATCH_MODE_GUILD_WAR_SIEGE_VALUE -> {
                if (!playerWon) {
                    yield BattleEvaluation.INELIGIBLE;
                }
                yield new BattleEvaluation(true, true, false, BATTLE_UNITS, 0);
            }
            default -> BattleEvaluation.INELIGIBLE;
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

    /** UTC+8 每周一 05:00（即 UTC 周日 21:00）切周起止毫秒；失败返回 null。 */
    public static long[] resolveWeek(long nowMs) {
        if (Long.compareUnsigned(nowMs, FIRST_MONDAY_05_UTC8_MS) < 0) {
            return null;
        }
        long offset = Long.remainderUnsigned(nowMs - FIRST_MONDAY_05_UTC8_MS, WEEK_MS);
        long start = nowMs - offset;
        long next = start + WEEK_MS;
        if (Long.compareUnsigned(next, start) <= 0) {
            return null;
        }
        return new long[]{start, next};
    }

    // ------------------------------------------------------------------ 内部辅助

    private int rollBelow(int bound) {
        if (bound <= 1) {
            return 0;
        }
        if (qualityRoll != null) {
            return Integer.remainderUnsigned(qualityRoll.applyAsInt(bound), bound);
        }
        return ThreadLocalRandom.current().nextInt(bound);
    }

    private int rollQualityForMastery(int masteryTier) {
        int[] weights = rules.tables().qualityWeightsForMastery(masteryTier);
        return EquipCraftQualityRules.rollQualityTier(weights, rollBelow(EquipCraftQualityRules.QUALITY_WEIGHT_TOTAL_BPS));
    }

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

    private ScenePlayer resolveValidProxyForgeMaster(ScenePlayer buyer, long proxyMasterId, SceneWorld world,
                                                     Long expectedFeeGold, long nowSec) {
        if (proxyMasterId == 0L || proxyMasterId == buyer.playerId() || world == null || currency == null) {
            return null;
        }
        ScenePlayer master = world.playerById(proxyMasterId);
        if (master == null || master.frozen() || master.switchPhase() != SwitchPhase.NONE) {
            return null;
        }
        var craftState = master.craft().state();
        if (!craftState.getProxyStallOpen()
                || EquipCraftQualityRules.masteryTierForExp(craftState.getForgeMasteryExp()) < EquipCraftQualityRules.MASTERY_MASTER
                || !EquipCraftQualityRules.validProxyFee(craftState.getProxyForgeFeeGold())) {
            return null;
        }
        if (expectedFeeGold != null && craftState.getProxyForgeFeeGold() != expectedFeeGold) {
            return null;
        }
        long netFee = EquipCraftQualityRules.proxyNetGold(craftState.getProxyForgeFeeGold());
        if (netFee > 0L && currency.checkAdd(master, Wallet.GOLD, netFee, nowSec) != 0) {
            return null;
        }
        return master;
    }

    private int resolveProxyMaster(ScenePlayer buyer, long proxyMasterId, SceneWorld world, Prepared prepared) {
        if (proxyMasterId == 0L) {
            prepared.effectiveMasteryTier = EquipCraftQualityRules.masteryTierForExp(buyer.craft().forgeMasteryExp());
            return OK;
        }
        long nowSec = clock.epochMillis() / 1000L;
        ScenePlayer master = resolveValidProxyForgeMaster(buyer, proxyMasterId, world, null, nowSec);
        if (master == null) {
            return equip_error.kEquipProxyMasterUnavailable_VALUE;
        }
        prepared.proxyMasterPlayerId = proxyMasterId;
        prepared.proxyFeeGold = master.craft().state().getProxyForgeFeeGold();
        prepared.proxyMasterName = playerDisplayName(master);
        prepared.effectiveMasteryTier = EquipCraftQualityRules.MASTERY_MASTER;
        return OK;
    }

    private static void planBoundPolicy(Bag inventory, EquipmentCraftChoice choice, boolean isCreateNew, Prepared prepared) {
        boolean allTradableEnough = true;
        boolean anyBoundPresent = false;
        for (Map.Entry<Integer, Long> entry : prepared.consumed.entrySet()) {
            long count = entry.getValue();
            if (count <= 0L) {
                continue;
            }
            int configId = entry.getKey();
            if (inventory.plainTradableQuantity(configId) < count) {
                allTradableEnough = false;
            }
            if (inventory.plainQuantity(configId) > inventory.plainTradableQuantity(configId)) {
                anyBoundPresent = true;
            }
        }
        if (isCreateNew) {
            if (!choice.getAllowBoundMaterials() && allTradableEnough) {
                prepared.tradableOnly = true;
                prepared.willBindItem = false;
                prepared.resultBound = false;
                return;
            }
            prepared.tradableOnly = false;
            prepared.willBindItem = anyBoundPresent;
            prepared.resultBound = prepared.willBindItem;
            return;
        }
        if (prepared.originalBound) {
            prepared.tradableOnly = false;
            prepared.willBindItem = false;
            prepared.resultBound = true;
            return;
        }
        // 既有可交易装备：可交易材料充足时一律强制 TRADEABLE_ONLY（哪怕包里另有绑定堆或透传 allow_bound_materials=true，
        // 也绝不误耗绑定堆染毒纯 T 装备）；仅当可交易料不足时，未确认拦 kEquipBindConfirmRequired，确认后由提交段按实际
        // consumedBound 决定是否转绑。
        if (allTradableEnough) {
            prepared.tradableOnly = true;
            prepared.willBindItem = false;
            prepared.resultBound = false;
            return;
        }
        if (!choice.getAllowBoundMaterials()) {
            prepared.tradableOnly = true;
            prepared.willBindItem = true;
            prepared.bindConfirmBlocked = true;
            prepared.resultBound = true;
            return;
        }
        prepared.tradableOnly = false;
        prepared.willBindItem = anyBoundPresent;
        prepared.resultBound = prepared.willBindItem;
    }

    private static BagItem locateEquipment(ScenePlayer player, long itemId, BagType[] holderOut) {
        BagItem found = player.bags().bag(BagType.INVENTORY).item(itemId);
        if (found != null) {
            holderOut[0] = BagType.INVENTORY;
            return found;
        }
        found = player.bags().bag(BagType.EQUIPMENT).item(itemId);
        if (found != null) {
            holderOut[0] = BagType.EQUIPMENT;
            return found;
        }
        return null;
    }

    private Prepared prepareChoice(ScenePlayer player, EquipmentCraftChoice choice, SceneWorld world, int[] errorOut) {
        if (!choice.getUnknownFields().asMap().isEmpty()) {
            errorOut[0] = FEATURE_UNAVAILABLE;
            return null;
        }
        EquipmentTables tables = rules.tables();
        Bag inventory = player.bags().bag(BagType.INVENTORY);
        Prepared prepared = new Prepared();

        if (choice.getOperation() == EquipmentCraftChoice.Operation.CREATE_ITEM) {
            if (choice.getItemId() != 0 || choice.getAffixSlot() != 0 || choice.getAffixId() != 0
                    || choice.getUseGuaranteeCharm() || choice.hasExpectedPolishPityCount()) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            ItemTable row = tables.item(choice.getConfigId());
            if (row == null || row.getEquipProfile() == 0 || !validTemplate(row, tables)) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            int proxyErr = resolveProxyMaster(player, choice.getProxyMasterPlayerId(), world, prepared);
            if (proxyErr != OK) {
                errorOut[0] = proxyErr;
                return null;
            }
            prepared.configId = row.getId();
            prepared.itemId = 0L;
            prepared.resultEquipment = EquipmentState.getDefaultInstance();
            prepared.holder = BagType.INVENTORY;
            prepared.materialId = row.getForgeMaterialId();
            prepared.materialCount = row.getForgeMaterialCount();
            prepared.consumed.merge(prepared.materialId, Integer.toUnsignedLong(prepared.materialCount), Long::sum);
            planBoundPolicy(inventory, choice, true, prepared);
            return prepared;
        }

        if (choice.getOperation() == EquipmentCraftChoice.Operation.INSTALL_AFFIX) {
            if (choice.getConfigId() != 0 || choice.getItemId() == 0 || choice.getAffixId() == 0
                    || choice.getUseGuaranteeCharm() || choice.hasExpectedPolishPityCount()) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            BagType[] holderOut = new BagType[1];
            BagItem found = locateEquipment(player, choice.getItemId(), holderOut);
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
            int proxyErr = resolveProxyMaster(player, choice.getProxyMasterPlayerId(), world, prepared);
            if (proxyErr != OK) {
                errorOut[0] = proxyErr;
                return null;
            }
            EquipmentState nextEquip = current.toBuilder()
                    .addFixedAffixes(EquipmentFixedAffixState.newBuilder()
                            .setSlot(choice.getAffixSlot())
                            .setAffixId(choice.getAffixId()))
                    .build();
            prepared.configId = found.configId();
            prepared.itemId = found.guid();
            prepared.originalBound = found.bound();
            prepared.resultEquipment = nextEquip;
            prepared.holder = holderOut[0];
            prepared.targetAffixIndex = nextEquip.getFixedAffixesCount() - 1;
            prepared.polishExempt = EquipCraftQualityRules.isPolishExempt(affix.getEffect());
            prepared.materialId = affix.getMaterialId();
            prepared.materialCount = affix.getMaterialCount();
            prepared.consumed.merge(prepared.materialId, Integer.toUnsignedLong(prepared.materialCount), Long::sum);
            planBoundPolicy(inventory, choice, false, prepared);
            return prepared;
        }

        if (choice.getOperation() == EquipmentCraftChoice.Operation.REFORGE_AFFIX) {
            if (choice.getConfigId() != 0 || choice.getItemId() == 0 || choice.getAffixId() == 0
                    || choice.getUseGuaranteeCharm() || choice.getProxyMasterPlayerId() != 0L
                    || choice.hasExpectedPolishPityCount()) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            BagType[] holderOut = new BagType[1];
            BagItem found = locateEquipment(player, choice.getItemId(), holderOut);
            if (found == null) {
                errorOut[0] = equip_error.kEquipItemNotFound_VALUE;
                return null;
            }
            ItemTable row = tables.item(found.configId());
            EquipV1AffixTable newAffix = tables.fixedAffix(choice.getAffixId());
            if (row == null || newAffix == null || !FixedEquipmentEffects.validAffix(newAffix, tables)
                    || poolFor(row.getEquipProfile(), choice.getAffixSlot()) != newAffix.getPool()
                    || found.size() != 1 || !FixedEquipmentEffects.validSelections(found, row.getEquipProfile(), tables)) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            EquipmentState current = found.equipment();
            if (!current.getUnknownFields().asMap().isEmpty()) {
                errorOut[0] = FEATURE_UNAVAILABLE;
                return null;
            }
            int foundIndex = -1;
            for (int i = 0; i < current.getFixedAffixesCount(); i++) {
                EquipmentFixedAffixState selected = current.getFixedAffixes(i);
                if (!selected.getUnknownFields().asMap().isEmpty()) {
                    errorOut[0] = FEATURE_UNAVAILABLE;
                    return null;
                }
                if (selected.getSlot() == choice.getAffixSlot()) {
                    if (selected.getAffixId() == choice.getAffixId()) {
                        errorOut[0] = equip_error.kEquipAffixReforgeSame_VALUE;
                        return null;
                    }
                    foundIndex = i;
                } else if (selected.getAffixId() == choice.getAffixId()) {
                    errorOut[0] = INVALID_PARAMETER;
                    return null;
                }
            }
            if (foundIndex < 0) {
                errorOut[0] = equip_error.kEquipAffixSlotEmpty_VALUE;
                return null;
            }
            EquipmentState.Builder eb = current.toBuilder();
            EquipmentFixedAffixState.Builder target = eb.getFixedAffixesBuilder(foundIndex);
            EquipV1AffixTable oldAffix = tables.fixedAffix(target.getAffixId());
            boolean oldExempt = oldAffix != null && EquipCraftQualityRules.isPolishExempt(oldAffix.getEffect());
            boolean newExempt = EquipCraftQualityRules.isPolishExempt(newAffix.getEffect());
            target.setAffixId(choice.getAffixId());
            if (newExempt) {
                target.setQualityTier(EquipCraftQualityRules.MAX_QUALITY_TIER).setPolishPityCount(0);
            } else if (oldExempt) {
                // 防止先装免随机 G02 速度再重铸成普通词条白嫖极品；提交时按普通首装规则掷品相。
                prepared.rerollQualityOnReforge = true;
                prepared.effectiveMasteryTier = EquipCraftQualityRules.masteryTierForExp(player.craft().forgeMasteryExp());
            }
            // 非豁免 -> 非豁免重铸：100% 原样保留 quality_tier 与 polish_pity_count。
            prepared.configId = found.configId();
            prepared.itemId = found.guid();
            prepared.originalBound = found.bound();
            prepared.resultEquipment = eb.build();
            prepared.holder = holderOut[0];
            prepared.targetAffixIndex = foundIndex;
            prepared.polishExempt = newExempt;
            prepared.materialId = EquipCraftQualityRules.REFORGE_SAND_CONFIG_ID;
            prepared.materialCount = EquipCraftQualityRules.REFORGE_SAND_COUNT;
            prepared.baseGoldCost = EquipCraftQualityRules.REFORGE_GOLD_COST;
            prepared.consumed.merge(prepared.materialId, Integer.toUnsignedLong(prepared.materialCount), Long::sum);
            planBoundPolicy(inventory, choice, false, prepared);
            return prepared;
        }

        if (choice.getOperation() == EquipmentCraftChoice.Operation.POLISH_AFFIX) {
            if (choice.getConfigId() != 0 || choice.getItemId() == 0 || choice.getAffixId() != 0) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            BagType[] holderOut = new BagType[1];
            BagItem found = locateEquipment(player, choice.getItemId(), holderOut);
            if (found == null) {
                errorOut[0] = equip_error.kEquipItemNotFound_VALUE;
                return null;
            }
            ItemTable row = tables.item(found.configId());
            if (row == null || poolFor(row.getEquipProfile(), choice.getAffixSlot()) == 0
                    || found.size() != 1 || !FixedEquipmentEffects.validSelections(found, row.getEquipProfile(), tables)) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            EquipmentState current = found.equipment();
            if (!current.getUnknownFields().asMap().isEmpty()) {
                errorOut[0] = FEATURE_UNAVAILABLE;
                return null;
            }
            int foundIndex = -1;
            for (int i = 0; i < current.getFixedAffixesCount(); i++) {
                EquipmentFixedAffixState selected = current.getFixedAffixes(i);
                if (!selected.getUnknownFields().asMap().isEmpty()) {
                    errorOut[0] = FEATURE_UNAVAILABLE;
                    return null;
                }
                if (selected.getSlot() == choice.getAffixSlot()) {
                    foundIndex = i;
                    break;
                }
            }
            if (foundIndex < 0) {
                errorOut[0] = equip_error.kEquipAffixSlotEmpty_VALUE;
                return null;
            }
            EquipmentFixedAffixState curAffixState = current.getFixedAffixes(foundIndex);
            EquipV1AffixTable affix = tables.fixedAffix(curAffixState.getAffixId());
            if (affix == null) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            if (EquipCraftQualityRules.isPolishExempt(affix.getEffect())) {
                errorOut[0] = equip_error.kEquipAffixPolishExempt_VALUE;
                return null;
            }
            int currentTier = EquipCraftQualityRules.effectiveQualityTier(curAffixState.getQualityTier(), false);
            if (currentTier >= EquipCraftQualityRules.MAX_QUALITY_TIER) {
                errorOut[0] = equip_error.kEquipAffixAlreadyMaxQuality_VALUE;
                return null;
            }
            if (choice.hasExpectedPolishPityCount()
                    && choice.getExpectedPolishPityCount() != curAffixState.getPolishPityCount()) {
                errorOut[0] = equip_error.kEquipPolishPityChanged_VALUE;
                return null;
            }
            int proxyErr = resolveProxyMaster(player, choice.getProxyMasterPlayerId(), world, prepared);
            if (proxyErr != OK) {
                errorOut[0] = proxyErr;
                return null;
            }
            prepared.configId = found.configId();
            prepared.itemId = found.guid();
            prepared.originalBound = found.bound();
            prepared.resultEquipment = current;
            prepared.holder = holderOut[0];
            prepared.targetAffixIndex = foundIndex;
            prepared.materialId = EquipCraftQualityRules.POLISH_SAND_CONFIG_ID;
            prepared.materialCount = EquipCraftQualityRules.POLISH_SAND_COUNT;
            prepared.baseGoldCost = EquipCraftQualityRules.POLISH_GOLD_COST;
            prepared.consumed.merge(prepared.materialId, Integer.toUnsignedLong(prepared.materialCount), Long::sum);
            if (choice.getUseGuaranteeCharm()) {
                prepared.extraMaterialId = EquipCraftQualityRules.GUARANTEE_CHARM_CONFIG_ID;
                prepared.extraMaterialCount = 1;
                prepared.consumed.merge(prepared.extraMaterialId, 1L, Long::sum);
            }
            planBoundPolicy(inventory, choice, false, prepared);
            return prepared;
        }

        if (choice.getOperation() == EquipmentCraftChoice.Operation.UNSEAL_TO_TRADABLE) {
            if (choice.getConfigId() != 0 || choice.getItemId() == 0 || choice.getAffixSlot() != 0
                    || choice.getAffixId() != 0 || choice.getSourceItemId() != 0
                    || choice.getUseGuaranteeCharm() || choice.getProxyMasterPlayerId() != 0L
                    || choice.hasExpectedPolishPityCount()) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            if (tables.item(EquipCraftQualityRules.UNSEAL_TALISMAN_CONFIG_ID) == null) {
                errorOut[0] = INVALID_TABLE_DATA;
                return null;
            }
            BagType[] holderOut = new BagType[1];
            BagItem found = locateEquipment(player, choice.getItemId(), holderOut);
            if (found == null) {
                errorOut[0] = equip_error.kEquipItemNotFound_VALUE;
                return null;
            }
            ItemTable row = tables.item(found.configId());
            EquipmentState current = found.equipment();
            if (row == null || row.getEquipKind() == 0 || found.size() != 1 || current == null) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            if (!current.getUnknownFields().asMap().isEmpty()) {
                errorOut[0] = FEATURE_UNAVAILABLE;
                return null;
            }
            if (!found.bound()) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            // 要求先取下所有宝石，防止剥离过程误销毁玩家宝石。
            for (EquipmentGemState gem : current.getGemsList()) {
                if (gem.getGemConfigId() != 0) {
                    errorOut[0] = INVALID_PARAMETER;
                    return null;
                }
            }
            EquipmentState cleared = current.toBuilder()
                    .setEnhanceLevel(0)
                    .setEnhancePityCount(0)
                    .clearFixedAffixes()
                    .clearCrafterName()
                    .setCrafterPlayerId(0L)
                    .build();
            prepared.configId = found.configId();
            prepared.itemId = found.guid();
            prepared.originalBound = found.bound();
            prepared.resultBound = false;
            prepared.resultEquipment = cleared;
            prepared.holder = holderOut[0];
            prepared.materialId = EquipCraftQualityRules.UNSEAL_TALISMAN_CONFIG_ID;
            prepared.materialCount = 1;
            prepared.consumed.merge(prepared.materialId, 1L, Long::sum);
            prepared.tradableOnly = false;
            prepared.willBindItem = false;
            return prepared;
        }

        if (choice.getOperation() == EquipmentCraftChoice.Operation.INHERIT_ENHANCE) {
            if (choice.getConfigId() != 0 || choice.getItemId() == 0 || choice.getSourceItemId() == 0
                    || choice.getSourceItemId() == choice.getItemId() || choice.getAffixSlot() != 0
                    || choice.getAffixId() != 0 || choice.getUseGuaranteeCharm()
                    || choice.getProxyMasterPlayerId() != 0L || choice.hasExpectedPolishPityCount()) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            if (tables.item(EquipCraftQualityRules.INHERIT_TALISMAN_CONFIG_ID) == null) {
                errorOut[0] = INVALID_TABLE_DATA;
                return null;
            }
            BagType[] holderOut = new BagType[1];
            BagItem found = locateEquipment(player, choice.getItemId(), holderOut);
            if (found == null) {
                errorOut[0] = equip_error.kEquipItemNotFound_VALUE;
                return null;
            }
            BagType[] sourceHolderOut = new BagType[1];
            BagItem sourceItem = locateEquipment(player, choice.getSourceItemId(), sourceHolderOut);
            if (sourceItem == null) {
                errorOut[0] = equip_error.kEquipItemNotFound_VALUE;
                return null;
            }
            ItemTable targetRow = tables.item(found.configId());
            ItemTable sourceRow = tables.item(sourceItem.configId());
            EquipmentState targetEquip = found.equipment();
            EquipmentState sourceEquip = sourceItem.equipment();
            if (targetRow == null || sourceRow == null || targetRow.getEquipKind() == 0
                    || targetRow.getEquipKind() != sourceRow.getEquipKind()
                    || targetRow.getEquipProfile() != sourceRow.getEquipProfile()
                    || targetRow.getEquipLevel() != sourceRow.getEquipLevel()
                    || found.size() != 1 || sourceItem.size() != 1
                    || targetEquip == null || sourceEquip == null) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            if (!targetEquip.getUnknownFields().asMap().isEmpty()
                    || !sourceEquip.getUnknownFields().asMap().isEmpty()) {
                errorOut[0] = FEATURE_UNAVAILABLE;
                return null;
            }
            int sourceLevel = sourceEquip.getEnhanceLevel();
            int targetLevel = targetEquip.getEnhanceLevel();
            if (sourceLevel == 0 || Integer.compareUnsigned(sourceLevel, targetLevel) <= 0) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            EquipmentState inherited = targetEquip.toBuilder()
                    .setEnhanceLevel(sourceLevel)
                    .setEnhancePityCount(sourceEquip.getEnhancePityCount())
                    .build();
            prepared.configId = found.configId();
            prepared.itemId = found.guid();
            prepared.originalBound = found.bound();
            prepared.resultBound = true;
            prepared.resultEquipment = inherited;
            prepared.holder = holderOut[0];
            // 目标副套装备强制绑定仅自用，避免通过流派副套继承复制/洗白多件可交易高强成品。
            prepared.willBindItem = !found.bound();
            prepared.materialId = EquipCraftQualityRules.INHERIT_TALISMAN_CONFIG_ID;
            prepared.materialCount = 1;
            prepared.consumed.merge(prepared.materialId, 1L, Long::sum);
            prepared.tradableOnly = false;
            return prepared;
        }

        if (choice.getOperation() == EquipmentCraftChoice.Operation.UPGRADE_TIER) {
            if (choice.getConfigId() == 0 || choice.getItemId() == 0 || choice.getSourceItemId() != 0L
                    || choice.getAffixSlot() != 0 || choice.getAffixId() != 0 || choice.getUseGuaranteeCharm()
                    || choice.getProxyMasterPlayerId() != 0L || choice.hasExpectedPolishPityCount()) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            BagType[] holderOut = new BagType[1];
            BagItem found = locateEquipment(player, choice.getItemId(), holderOut);
            if (found == null) {
                errorOut[0] = equip_error.kEquipItemNotFound_VALUE;
                return null;
            }
            ItemTable sourceRow = tables.item(found.configId());
            ItemTable targetRow = tables.item(choice.getConfigId());
            EquipmentState currentEquip = found.equipment();
            if (sourceRow == null || targetRow == null
                    || !validTemplate(sourceRow, tables) || !validTemplate(targetRow, tables)
                    || !EquipCraftQualityRules.canUpgradeTier(
                            sourceRow.getEquipProfile(), sourceRow.getEquipKind(),
                            sourceRow.getEquipLevel(), sourceRow.getEquipClass(),
                            targetRow.getEquipProfile(), targetRow.getEquipKind(),
                            targetRow.getEquipLevel(), targetRow.getEquipClass())
                    || found.size() != 1 || currentEquip == null
                    || !FixedEquipmentEffects.validSelections(found, sourceRow.getEquipProfile(), tables)) {
                errorOut[0] = INVALID_PARAMETER;
                return null;
            }
            if (!currentEquip.getUnknownFields().asMap().isEmpty()) {
                errorOut[0] = FEATURE_UNAVAILABLE;
                return null;
            }
            prepared.configId = targetRow.getId();
            prepared.itemId = found.guid();
            prepared.originalBound = found.bound();
            prepared.resultBound = found.bound();
            prepared.resultEquipment = currentEquip;
            prepared.holder = holderOut[0];
            prepared.baseGoldCost = EquipCraftQualityRules.upgradeTierGoldCost(
                    sourceRow.getEquipProfile(), sourceRow.getEquipLevel(), targetRow.getEquipLevel());
            prepared.materialId = 0;
            prepared.materialCount = 0;
            prepared.tradableOnly = false;
            prepared.willBindItem = false;
            return prepared;
        }

        errorOut[0] = INVALID_PARAMETER;
        return null;
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
        ItemTable row = rules.tables().item(prepared.configId);
        if (row == null) {
            return SERVICE_UNAVAILABLE;
        }
        if (Integer.compareUnsigned(PlayerLevels.effective(player.level()), row.getEquipLevel()) < 0) {
            return equip_error.kEquipLevelNotEnough_VALUE;
        }
        Bag inventory = player.bags().bag(BagType.INVENTORY);
        for (Map.Entry<Integer, Long> entry : prepared.consumed.entrySet()) {
            int configId = entry.getKey();
            long needed = entry.getValue();
            if (needed <= 0L) {
                continue;
            }
            if (bags.tables().hasUnknownItemSchema(configId)) {
                return FEATURE_UNAVAILABLE;
            }
            if (inventory.plainQuantity(configId) < needed) {
                return inventory.total(configId) >= needed
                        ? FEATURE_UNAVAILABLE
                        : equip_error.kEquipEnhanceMaterialNotEnough_VALUE;
            }
        }
        if (prepared.bindConfirmBlocked) {
            return equip_error.kEquipBindConfirmRequired_VALUE;
        }
        boolean allowBindForBaseFee = prepared.resultBound;
        long strictGold = prepared.proxyFeeGold + (allowBindForBaseFee ? 0L : prepared.baseGoldCost);
        long preferBindGold = allowBindForBaseFee ? prepared.baseGoldCost : 0L;
        if (strictGold > 0L || preferBindGold > 0L) {
            if (strictGold < 0L || preferBindGold < 0L
                    || !player.wallet().canAffordSplitGold(strictGold, preferBindGold)) {
                return equip_error.kEquipCraftGoldNotEnough_VALUE;
            }
        }
        if (prepared.itemId == 0L) {
            if (bags.isItemGainBlocked(player, prepared.configId)) {
                return BagService.REFUSED;
            }
            PlayerBags probe = PlayerBags.restore(player.bags().toState());
            probe.normalize(bags.tables());
            Bag probeInv = probe.bag(BagType.INVENTORY);
            for (Map.Entry<Integer, Long> entry : prepared.consumed.entrySet()) {
                if (entry.getValue() > 0L) {
                    probeInv.drainPlainExact(entry.getKey(), entry.getValue(), prepared.tradableOnly);
                }
            }
            if (probeInv.freeCells() <= 0) {
                return equip_error.kEquipBagFull_VALUE;
            }
        }
        return OK;
    }

    private BagItemInfo buildProductPreview(int configId, long itemId, boolean bound, EquipmentState equipmentState) {
        ItemTable row = rules.tables().item(configId);
        if (row == null) {
            return BagItemInfo.getDefaultInstance();
        }
        BagItemInfo.Builder info = BagItemInfo.newBuilder()
                .setItemId(itemId)
                .setConfigId(configId)
                .setCount(1)
                .setMaxStack(row.getMaxStackSize())
                .setEquipKind(row.getEquipKind())
                .setBound(bound);
        PlayerBags tempBags = PlayerBags.restore(com.game.player.store.state.BagState.newBuilder()
                .addItems(com.game.player.store.state.BagItemState.newBuilder()
                        .setItemUuid(itemId == 0 ? 1L : itemId)
                        .setConfigId(configId)
                        .setStackSize(1)
                        .setPos(0)
                        .setBagType(BagType.INVENTORY.code())
                        .setBound(bound)
                        .setEquipment(equipmentState))
                .build());
        tempBags.normalize(bags.tables());
        BagItem synthetic = tempBags.bag(BagType.INVENTORY).item(itemId == 0 ? 1L : itemId);
        if (synthetic != null) {
            display.item(synthetic, info);
        }
        return info.setItemId(itemId).setBound(bound).build();
    }

    private EquipmentCraftMaterial buildMaterial(ScenePlayer player, int materialId, int count, boolean tradableOnly) {
        ItemTable mat = rules.tables().item(materialId);
        Bag inv = player.bags().bag(BagType.INVENTORY);
        EquipmentCraftMaterial.Builder b = EquipmentCraftMaterial.newBuilder()
                .setConfigId(materialId)
                .setCount(count)
                .setOwned(inv.total(materialId))
                .setAvailable(tradableOnly ? inv.plainTradableQuantity(materialId) : inv.plainQuantity(materialId));
        if (mat != null) {
            b.setName(mat.getName()).setIconKey(mat.getIconKey());
        }
        return b.build();
    }

    private int fillMaterialSnapshot(ScenePlayer player, long nowMs, EquipmentMaterialRewardSnapshot.Builder out) {
        long[] day = resolveDay(nowMs);
        long[] week = resolveWeek(nowMs);
        if (day == null || week == null) {
            return INVALID_PARAMETER;
        }
        ItemTable craftMat = validMaterialItem(rules.tables().item(CRAFTING_MATERIAL_ID));
        ItemTable enhanceStone = validMaterialItem(rules.tables().item(ENHANCEMENT_STONE_ID));
        if (craftMat == null || enhanceStone == null) {
            return INVALID_TABLE_DATA;
        }
        EquipmentMaterialRewardLedgerState state = player.clientRequestLedger().materialReward();
        int lifetimeEarned = state.getMigratedToV2() ? state.getLifetimeEarned() : 0;
        int tradableEarnedTotal = player.knownAccountStage1TradableUsed();
        long availableUnits = state.getAvailableUnits();
        long availableTradable = state.getMigratedToV2()
                ? Math.min(state.getAvailableTradableUnits(), availableUnits)
                : 0L;
        int batchUnits = (int) Math.min(Integer.toUnsignedLong(MAX_CLAIM_BATCH_UNITS), availableUnits);
        int batchTradable = (int) Math.min(Integer.toUnsignedLong(batchUnits), availableTradable);
        boolean weeklyPhase = Integer.compareUnsigned(lifetimeEarned, LIFETIME_LIMIT) >= 0;
        boolean lightCardActive = Long.compareUnsigned(player.afkPassExpiresAt(), Long.divideUnsigned(nowMs, 1000L)) > 0;
        int earnedThisWeek = (weeklyPhase && state.getWeekStartsAtMs() == week[0]) ? state.getEarnedThisWeek() : 0;
        int earnedToday = state.getDayStartsAtMs() == day[0] ? state.getEarnedToday() : 0;
        int writable = checkWritable(player, false);
        boolean canClaim = batchUnits > 0 && writable == OK && state.getRevision() != -1L;
        out.setAvailable(true)
                .setDayStartsAtMs(day[0])
                .setNextResetAtMs(day[1])
                .setEarnedToday(earnedToday)
                .setDailyLimit(DAILY_LIMIT)
                .setAvailableUnits(availableUnits)
                .setBatchUnits(batchUnits)
                .setRevision(state.getRevision())
                .setLifetimeEarned(lifetimeEarned)
                .setLifetimeLimit(LIFETIME_LIMIT)
                .setTradableEarnedTotal(tradableEarnedTotal)
                .setTradableLifetimeLimit(TRADABLE_LIFETIME_LIMIT)
                .setAvailableTradableUnits(availableTradable)
                .setBatchTradableUnits(batchTradable)
                .setWeeklyPhaseActive(weeklyPhase)
                .setWeekStartsAtMs(week[0])
                .setNextWeekResetAtMs(week[1])
                .setEarnedThisWeek(earnedThisWeek)
                .setWeeklyLimit(WEEKLY_LIMIT)
                .setCraftingMaterial(buildMaterial(player, craftMat.getId(), 0, false))
                .setEnhancementStone(buildMaterial(player, enhanceStone.getId(), 0, false))
                .setCanClaim(canClaim);
        if (batchUnits == 0) {
            out.setUnavailableReason(weeklyPhase
                    ? (lightCardActive
                    ? "完成主要战斗可获得本周材料自选份数（每周上限150份，仙途轻享卡生效中：胜局含1份可交易）。"
                    : "完成主要战斗可获得本周材料自选份数（每周上限150份，全绑定；开通仙途轻享卡可解锁胜局可交易产出）。")
                    : "完成主要战斗可获得首套材料自选份数（首套总池660份，无每日限制）。");
        } else if (writable == equip_error.kEquipInBattle_VALUE) {
            out.setUnavailableReason("战斗中暂不能领取，请在结算完成后重试。");
        } else if (!canClaim) {
            out.setUnavailableReason("当前状态暂不能领取，请稍后重试。");
        }
        return OK;
    }

    private CommitEquipmentCraftResponse appliedCraft(ScenePlayer player, long reqId, long itemId,
                                                      long resultRevision, int resultQualityTier,
                                                      int resultPolishPityCount, boolean guaranteedByPity,
                                                      boolean replayed, BagFeature bagView) {
        return CommitEquipmentCraftResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setClientRequestId(reqId)
                .setOutcome(CommitEquipmentCraftResponse.Outcome.APPLIED)
                .setDurable(isForgeDurable(player, resultRevision))
                .setReplayed(replayed)
                .setItemId(itemId)
                .setResultForgeRevision(resultRevision)
                .setCurrentForgeRevision(player.clientRequestLedger().forgeRevision())
                .setResultQualityTier(resultQualityTier)
                .setResultPolishPityCount(resultPolishPityCount)
                .setGuaranteedByPity(guaranteedByPity)
                .setForgeMastery(buildForgeMastery(player))
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
        if (!tables.validateQualityTable()) {
            return INVALID_TABLE_DATA;
        }
        if (validMaterialItem(tables.item(EquipCraftQualityRules.POLISH_SAND_CONFIG_ID)) == null
                || validMaterialItem(tables.item(EquipCraftQualityRules.GUARANTEE_CHARM_CONFIG_ID)) == null) {
            return INVALID_TABLE_DATA;
        }
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
        for (int tier = EquipCraftQualityRules.MIN_QUALITY_TIER; tier <= EquipCraftQualityRules.MAX_QUALITY_TIER; tier++) {
            EquipAffixQualityTable q = tables.affixQuality(tier);
            out.addQualityTiers(EquipAffixQualityInfo.newBuilder()
                    .setQualityTier(q.getId())
                    .setName(q.getName())
                    .setRatioPct(q.getRatioPct())
                    .setApprenticeRateBps(q.getApprenticeWeight())
                    .setArtisanRateBps(q.getArtisanWeight())
                    .setMasterRateBps(q.getMasterWeight()));
        }
        try {
            ByteArrayOutputStream digestBuf = new ByteArrayOutputStream();
            for (ItemTable row : recipes) digestBuf.write(row.toByteArray());
            for (EquipV1AffixTable row : affixes) digestBuf.write(row.toByteArray());
            for (EquipArtifactTable row : tables.artifacts()) digestBuf.write(row.toByteArray());
            for (int tier = EquipCraftQualityRules.MIN_QUALITY_TIER; tier <= EquipCraftQualityRules.MAX_QUALITY_TIER; tier++) {
                digestBuf.write(tables.affixQuality(tier).toByteArray());
            }
            digestBuf.write(out.build().toByteArray());
            out.setRulesFingerprint(PlayerClientRequestLedger.sha256Prefix16(digestBuf.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return OK;
    }
}
