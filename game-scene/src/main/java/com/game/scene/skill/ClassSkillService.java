package com.game.scene.skill;

import static com.game.scene.world.SceneMessageIds.tip;
import static com.game.table.SkillErrorTip.skill_error.*;

import com.game.battle.engine.ClassSkillRules;
import com.game.battle.engine.SeasonRules;
import com.game.player.store.state.SkillLoadoutSchemeState;
import com.game.proto.ListSkillsResponse;
import com.game.proto.PlayerSkillComp;
import com.game.proto.PlayerSkillListComp;
import com.game.proto.SaveSkillLoadoutRequest;
import com.game.proto.SaveSkillLoadoutResponse;
import com.game.proto.SkillCatalogEntry;
import com.game.proto.SwitchSkillLoadoutRequest;
import com.game.proto.SwitchSkillLoadoutResponse;
import com.game.proto.UpgradeSkillRequest;
import com.game.proto.UpgradeSkillResponse;
import com.game.scene.attribute.AttributeService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.pet.PetService;
import com.game.table.PetErrorTip;
import com.game.table.SeasonErrorTip;
import com.game.proto.SupportSkillPreview;
import com.game.scene.audit.AssetAudit;
import com.game.scene.currency.CurrencyService;
import com.game.scene.player.PlayerLevels;
import com.game.scene.player.PlayerSkills;
import com.game.scene.player.Wallet;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SwitchPhase;
import com.game.table.CommonErrorTip;
import com.game.table.SkillTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 技能书与修习用例。同步在场景线程执行，扣款和存储重数共用整份存档的归属围栏。 */
public final class ClassSkillService {
    private final CurrencyService currency;
    private final AttributeService attributes;
    private final PetService pets;
    private SceneMetrics metrics = SceneMetrics.noop();
    private com.game.scene.world.SceneClock clock = com.game.scene.world.SceneClock.SYSTEM;
    private java.util.function.IntSupplier activeSeason = () -> 0;
    private java.util.function.BooleanSupplier craftUnlockGate = () -> false;

    public ClassSkillService(CurrencyService currency, AttributeService attributes) {
        this(currency, attributes, null);
    }

    public ClassSkillService(CurrencyService currency, AttributeService attributes, PetService pets) {
        this.pets = pets;
        this.currency = currency;
        this.attributes = attributes;
    }

    public ClassSkillService withMetrics(SceneMetrics metrics) {
        this.metrics = metrics == null ? SceneMetrics.noop() : metrics;
        return this;
    }

    public ClassSkillService withClock(com.game.scene.world.SceneClock clock) {
        this.clock = clock == null ? com.game.scene.world.SceneClock.SYSTEM : clock;
        return this;
    }

    private static int unlockedSchemesFor(ScenePlayer player, long nowSec) {
        boolean active = com.game.scene.afkpass.AfkPassRules.isActive(player.afkPassExpiresAt(), nowSec);
        return SkillLoadoutRules.unlockedSchemes(active);
    }

    private long nowSeconds() {
        return Math.floorDiv(clock.epochMillis(), 1000L);
    }

    public ClassSkillService withActiveSeason(java.util.function.IntSupplier activeSeason) {
        this.activeSeason = activeSeason == null ? () -> 0 : activeSeason;
        return this;
    }

    public ClassSkillService withCraftUnlockGate(java.util.function.BooleanSupplier craftUnlockGate) {
        this.craftUnlockGate = craftUnlockGate == null ? () -> false : craftUnlockGate;
        return this;
    }

    static int busy(ScenePlayer player) {
        return player.inBattle() || player.frozen() || player.switchPhase() != SwitchPhase.NONE
                ? kSkillUpgradeBusy_VALUE : 0;
    }

    private int checkWritable(ScenePlayer player) {
        if (player.inBattle()) {
            metrics.battleGateReject(SceneMetrics.BattleGate.SKILL);
            return kSkillUpgradeBusy_VALUE;
        }
        return player.frozen() || player.switchPhase() != SwitchPhase.NONE ? kSkillUpgradeBusy_VALUE : 0;
    }

    static int stop(ClassSkillRules.UpgradeStop stop) {
        return switch (stop) {
            case NONE -> 0;
            case MAX_RANK -> kSkillMaxRankReached_VALUE;
            case LEVEL_LIMIT -> kSkillLevelNotEnough_VALUE;
            case GOLD -> kSkillGoldNotEnough_VALUE;
        };
    }

    /** 显式客户端投影，绝不把内部持久未知字段或未来技能传出。 */
    public static ListSkillsResponse book(ScenePlayer player) {
        return book(player, 0);
    }

    /** 显式客户端投影：按当季 activeSeasonId 自动并入本季免费技能，不写入永久存档。 */
    public static ListSkillsResponse book(ScenePlayer player, int activeSeasonId) {
        return book(player, activeSeasonId, false);
    }

    public static ListSkillsResponse book(ScenePlayer player, int activeSeasonId, boolean craftGateEnabled) {
        return book(player, activeSeasonId, craftGateEnabled, Math.floorDiv(System.currentTimeMillis(), 1000L));
    }

    public static ListSkillsResponse book(ScenePlayer player, int activeSeasonId, boolean craftGateEnabled, long nowSec) {
        var catalog = player.skillCatalog();
        var owned = player.learnedSkills();
        int level = PlayerLevels.effective(player.level());
        long balance = player.wallet().spendableGoldWithBind();
        var list = PlayerSkillListComp.newBuilder();
        for (int id : player.skills()) {
            SkillTable row = catalog.row(id);
            if (row != null && row.getSeasonId() != 0) continue;
            var entry = PlayerSkillComp.newBuilder().setSkillTableId(id);
            if (ClassSkillCatalog.ranked(row)) entry.setRank(owned.rank(id));
            list.addSkillList(entry);
        }
        var rows = new java.util.ArrayList<SkillTable>();
        for (SkillTable row : catalog.classRows(player.classId())) {
            if (row.getSeasonId() != 0 && !SeasonRules.isSkillActiveInSeason(row.getSeasonId(), activeSeasonId)) {
                continue;
            }
            rows.add(row);
        }
        // 当季免费赛季技能按 skill_table_id 升序并入已拥有列表（不落库）
        for (SkillTable row : rows) {
            if (row.getSeasonId() == 0 || !ClassSkillCatalog.forSubject(row, 1)) continue;
            int effRank = SeasonRules.effectiveSeasonalSkillRank(level, row.getUnlockLevel(), row.getMaxRank());
            if (effRank > 0) {
                list.addSkillList(PlayerSkillComp.newBuilder().setSkillTableId(row.getId()).setRank(effRank));
            }
        }
        var result = ListSkillsResponse.newBuilder().setErrorMessage(tip(0)).setSkillList(list)
                .setClassId(player.classId()).setPlayerLevel(level).setCurrencyBalance(balance)
                .setLoadout(SkillLoadoutRules.buildSnapshot(catalog, player.classId(), level, owned,
                        unlockedSchemesFor(player, nowSec)));
        Set<Integer> equippedNonActive = SkillLoadoutRules.collectEquippedNonActiveSkills(
                catalog, player.classId(), level, owned);
        var previews = new java.util.HashMap<Integer, SupportSkillPreview>();
        CharacterSupport.build(player).getPreviewsList().forEach(preview -> previews.put(preview.getSkillTableId(), preview));
        int busyTip = busy(player);
        for (SkillTable row : rows) {
            int rank = row.getSeasonId() != 0
                    ? SeasonRules.effectiveSeasonalSkillRank(level, row.getUnlockLevel(), row.getMaxRank())
                    : owned.rank(row.getId());
            boolean craftRequired = craftGateEnabled && row.getSeasonId() == 0 && row.getScrollId() > 0;
            boolean unlockedByBook = player.craft().isSkillBookUnlocked(row.getId());
            boolean equipped = rank > 0 && (row.getSeasonId() != 0 || row.getBattleModel() != 1
                    || row.getAbilityKind() == 1 || equippedNonActive.contains(row.getId()));
            var view = catalogEntry(catalog, row, rank, level, balance, busyTip, false, craftRequired, unlockedByBook, equipped);
            var preview = previews.get(row.getId());
            if (preview != null) view.setSupportPreview(preview);
            result.addCatalog(view);
        }
        return result.build();
    }

    public static ListSkillsResponse book(ScenePlayer player, long petId) {
        return book(player, petId, 0, false);
    }

    public static ListSkillsResponse book(ScenePlayer player, long petId, int activeSeasonId) {
        return book(player, petId, activeSeasonId, false);
    }

    public static ListSkillsResponse book(ScenePlayer player, long petId, int activeSeasonId, boolean craftGateEnabled) {
        if (petId == 0) return book(player, activeSeasonId, craftGateEnabled);
        var result = ListSkillsResponse.newBuilder().setPetId(petId);
        var pet = player.pets().find(petId);
        if (pet == null) return result.setErrorMessage(tip(PetErrorTip.pet_error.kPetNotFound_VALUE)).build();
        var catalog = player.skillCatalog();
        var species = catalog.species(pet.petTableId());
        if (species == null) return result.setErrorMessage(tip(PetErrorTip.pet_error.kPetTableRowMissing_VALUE)).build();
        int level = ClassSkillCatalog.petLevel(pet, species);
        long balance = player.wallet().spendableGoldWithBind();
        result.setPlayerLevel(level).setCurrencyBalance(balance).setErrorMessage(tip(0));
        var owned = result.getSkillListBuilder();
        catalog.petSkills(pet, species).forEach((id, rank) -> owned.addSkillList(PlayerSkillComp.newBuilder()
                .setSkillTableId(id).setRank(ClassSkillCatalog.petStoredRank(pet, species, id))));
        int blocked = busy(player);
        if (blocked == 0 && PlayerLevels.effective(player.level()) < level) blocked = PetErrorTip.pet_error.kPetOwnerLevelNotEnough_VALUE;
        for (var row : catalog.petRows()) {
            if (row.getSeasonId() != 0 && !SeasonRules.isSkillActiveInSeason(row.getSeasonId(), activeSeasonId)) continue;
            boolean craftRequired = craftGateEnabled && row.getSeasonId() == 0 && row.getScrollId() > 0;
            boolean unlockedByBook = player.craft().isSkillBookUnlocked(row.getId());
            result.addCatalog(catalogEntry(catalog, row, ClassSkillCatalog.petStoredRank(pet, species, row.getId()),
                    level, balance, blocked, true, craftRequired, unlockedByBook, true));
        }
        return result.build();
    }

    private static SkillCatalogEntry.Builder catalogEntry(ClassSkillCatalog catalog, SkillTable row, int rank,
            int level, long balance, int blocked, boolean pet, boolean craftRequired, boolean unlockedByBook,
            boolean equipped) {
        int limit = ClassSkillRules.rankLimit(level, row.getUnlockLevel(), row.getMaxRank());
        boolean seasonal = row.getSeasonId() != 0;
        boolean full = Integer.compareUnsigned(rank, row.getMaxRank()) >= 0;
        long cost = (!seasonal && !full) ? ClassSkillRules.upgradeCost(rank + 1, row.getUnlockLevel()) : 0;
        boolean blockedByBook = craftRequired && !unlockedByBook && rank == 0;
        int blockTip = seasonal ? SeasonErrorTip.season_error.kSeasonSkillNoUpgrade_VALUE
                : blocked != 0 ? blocked
                : blockedByBook ? kSkillCraftBookRequired_VALUE
                : full ? kSkillMaxRankReached_VALUE
                : Integer.compareUnsigned(rank, limit) >= 0 ? kSkillLevelNotEnough_VALUE
                : Long.compareUnsigned(cost, balance) > 0 ? kSkillGoldNotEnough_VALUE : 0;
        return SkillCatalogEntry.newBuilder().setSkillTableId(row.getId()).setName(row.getName())
                .setDescription(row.getDescription()).setIconKey(row.getIconKey()).setClassId(row.getClassId())
                .setCategory(row.getCategory()).setPassive(ClassSkillCatalog.passive(row))
                .setTargetTeam(row.getTargetTeam()).setMaxTargets(pet ? row.getPetMaxTargets() : row.getMaxTargets())
                .setUnlockLevel(row.getUnlockLevel()).setMaxRank(row.getMaxRank()).setRank(rank).setRankLimit(limit)
                .setManaPercent(row.getManaPercent()).setCooldownRounds(row.getCooldownRounds()).setNextRankCost(cost)
                .setUpgradeBlockTipId(blockTip).setDurationRounds(catalog.durationRounds(row)).setAllowDeadTarget(row.getAllowDeadTarget() != 0)
                .setAbilityKind(row.getAbilityKind()).setCasterMask(row.getCasterMask()).setActionTags(row.getActionTags())
                .setSeasonId(row.getSeasonId())
                .setScrollId(row.getScrollId())
                .setRarityTier(row.getRarityTier())
                .setCraftUnlockRequired(craftRequired)
                .setUnlockedByBook(unlockedByBook)
                .setSecondaryTargetPolicy(row.getSecondaryTargetPolicy())
                .setExclusiveGroup(row.getExclusiveGroup())
                .setEquipped(equipped);
    }

    public UpgradeSkillResponse upgrade(ScenePlayer player, UpgradeSkillRequest request) {
        if (request.getPetId() != 0) return upgradePet(player, request);
        if (!ClassSkillRules.isValidUpgradeCount(request.getCount()))
            return rejected(player, kSkillUpgradeCountInvalid_VALUE);
        SkillTable row = player.skillCatalog().row(request.getSkillTableId());
        if (row == null) return rejected(player, CommonErrorTip.common_error.kInvalidTableId_VALUE);
        if (row.getSeasonId() != 0) return rejected(player, SeasonErrorTip.season_error.kSeasonSkillNoUpgrade_VALUE);
        if (!ClassSkillCatalog.characterCanLearn(row, player.classId()))
            return rejected(player, kSkillNotInClass_VALUE);
        int busy = checkWritable(player);
        if (busy != 0) return rejected(player, busy);
        int oldRank = player.learnedSkills().rank(row.getId());
        if (oldRank == 0 && craftUnlockGate.getAsBoolean() && row.getScrollId() > 0
                && !player.craft().isSkillBookUnlocked(row.getId())) {
            return rejected(player, kSkillCraftBookRequired_VALUE);
        }
        if (oldRank != request.getExpectedRank()) return rejected(player, kSkillRankChanged_VALUE);
        int limit = ClassSkillRules.rankLimit(PlayerLevels.effective(player.level()), row.getUnlockLevel(), row.getMaxRank());
        var plan = ClassSkillRules.planUpgrade(oldRank, row.getMaxRank(), limit, row.getUnlockLevel(),
                player.wallet().spendableGoldWithBind(), request.getCount());
        if (plan.steps() == 0) return rejected(player, stop(plan.stop()));

        PlayerSkills candidate = PlayerSkills.restore(player.learnedSkills().toState());
        candidate.setRank(row.getId(), plan.finalRank());
        if (oldRank == 0) {
            SkillLoadoutRules.tryAutoSlotNewlyLearned(player.skillCatalog(), candidate, row);
        }
        var payment = currency.prepareGoldPreferBindDeduction(player, plan.totalCost());
        if (payment.tipId() != 0) return rejected(player, payment.tipId());
        payment.commitSkills(candidate, AssetAudit.Reason.SKILL_UPGRADE, Integer.toUnsignedLong(row.getId()));
        if (ClassSkillCatalog.passive(row)) attributes.skillsChanged(player);
        return UpgradeSkillResponse.newBuilder().setErrorMessage(tip(0))
                .setState(book(player, activeSeason.getAsInt(), craftUnlockGate.getAsBoolean()))
                .setUpgradedCount(plan.steps()).setStopTipId(plan.steps() < request.getCount() ? stop(plan.stop()) : 0).build();
    }

    /** 502 保存人物战法构筑方案（3 被动 + 1 追击 + 1 开场，乐观锁 + 幂等回执）。 */
    public SaveSkillLoadoutResponse saveLoadout(ScenePlayer player, SaveSkillLoadoutRequest request) {
        String reqId = request.getRequestId();
        if (!SkillLoadoutRules.validRequestId(reqId)) {
            return failSaveLoadout(player, reqId, CommonErrorTip.common_error.kInvalidParameter_VALUE);
        }
        PlayerSkills owned = player.learnedSkills();
        if (owned.loadoutLastRequestId().equals(reqId)) {
            boolean same = owned.loadoutLastBaseVersion() == request.getStateVersion()
                    && owned.loadoutLastSchemeId() == request.getSchemeId();
            if (!same) {
                return failSaveLoadout(player, reqId, CommonErrorTip.common_error.kInvalidParameter_VALUE);
            }
            return SaveSkillLoadoutResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setRequestId(reqId)
                    .setReplayed(true)
                    .setState(bookForService(player))
                    .build();
        }
        int busy = checkWritable(player);
        if (busy != 0) {
            return failSaveLoadout(player, reqId, busy);
        }
        long currentVersion = SkillLoadoutRules.version(owned.loadoutStateVersion());
        if (request.getStateVersion() != currentVersion || owned.loadoutStateVersion() == -1L) {
            return failSaveLoadout(player, reqId, kSkillLoadoutVersionChanged_VALUE);
        }
        List<Integer> passiveIds = new ArrayList<>(request.getPassiveSkillIdsList());
        List<Integer> pursuitIds = new ArrayList<>(request.getPursuitSkillIdsList());
        List<Integer> openingIds = new ArrayList<>(request.getOpeningSkillIdsList());
        int unlocked = unlockedSchemesFor(player, nowSeconds());
        int err = SkillLoadoutRules.validateScheme(request.getSchemeId(), request.getRoleTag(),
                passiveIds, pursuitIds, openingIds,
                player.skillCatalog(), player.classId(), PlayerLevels.effective(player.level()), owned, unlocked);
        if (err != 0) {
            return failSaveLoadout(player, reqId, err);
        }
        passiveIds.sort(Integer::compareUnsigned);
        pursuitIds.sort(Integer::compareUnsigned);
        openingIds.sort(Integer::compareUnsigned);
        SkillLoadoutSchemeState existing = owned.loadoutScheme(request.getSchemeId());
        SkillLoadoutSchemeState updated = (existing == null
                ? SkillLoadoutSchemeState.newBuilder().setSchemeId(request.getSchemeId())
                : existing.toBuilder().clearPassiveSkillIds().clearPursuitSkillIds().clearOpeningSkillIds())
                .setRoleTag(request.getRoleTag())
                .addAllPassiveSkillIds(passiveIds)
                .addAllPursuitSkillIds(pursuitIds)
                .addAllOpeningSkillIds(openingIds)
                .build();
        owned.putLoadoutScheme(updated);
        owned.commitLoadout(request.getSchemeId(), currentVersion + 1, reqId, request.getStateVersion(), request.getSchemeId());
        attributes.skillsChanged(player);
        return SaveSkillLoadoutResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setRequestId(reqId)
                .setState(bookForService(player))
                .build();
    }

    /** 503 一键切换人物战法构筑流派方案（1..3，乐观锁 + 幂等回执）。 */
    public SwitchSkillLoadoutResponse switchLoadout(ScenePlayer player, SwitchSkillLoadoutRequest request) {
        String reqId = request.getRequestId();
        if (!SkillLoadoutRules.validRequestId(reqId)) {
            return failSwitchLoadout(player, reqId, CommonErrorTip.common_error.kInvalidParameter_VALUE);
        }
        if (!SkillLoadoutRules.validSchemeId(request.getSchemeId(), unlockedSchemesFor(player, nowSeconds()))) {
            return failSwitchLoadout(player, reqId, kSkillLoadoutSchemeInvalid_VALUE);
        }
        PlayerSkills owned = player.learnedSkills();
        if (owned.loadoutLastRequestId().equals(reqId)) {
            boolean same = owned.loadoutLastBaseVersion() == request.getStateVersion()
                    && owned.loadoutLastSchemeId() == request.getSchemeId();
            if (!same) {
                return failSwitchLoadout(player, reqId, CommonErrorTip.common_error.kInvalidParameter_VALUE);
            }
            return SwitchSkillLoadoutResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setRequestId(reqId)
                    .setReplayed(true)
                    .setState(bookForService(player))
                    .build();
        }
        int busy = checkWritable(player);
        if (busy != 0) {
            return failSwitchLoadout(player, reqId, busy);
        }
        long currentVersion = SkillLoadoutRules.version(owned.loadoutStateVersion());
        if (request.getStateVersion() != currentVersion || owned.loadoutStateVersion() == -1L) {
            return failSwitchLoadout(player, reqId, kSkillLoadoutVersionChanged_VALUE);
        }
        if (owned.loadoutScheme(request.getSchemeId()) == null) {
            owned.putLoadoutScheme(SkillLoadoutSchemeState.newBuilder().setSchemeId(request.getSchemeId()).build());
        }
        owned.commitLoadout(request.getSchemeId(), currentVersion + 1, reqId, request.getStateVersion(), request.getSchemeId());
        attributes.skillsChanged(player);
        return SwitchSkillLoadoutResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setRequestId(reqId)
                .setState(bookForService(player))
                .build();
    }

    private ListSkillsResponse bookForService(ScenePlayer player) {
        return book(player, activeSeason.getAsInt(), craftUnlockGate.getAsBoolean(), nowSeconds());
    }

    private SaveSkillLoadoutResponse failSaveLoadout(ScenePlayer player, String reqId, int error) {
        return SaveSkillLoadoutResponse.newBuilder()
                .setErrorMessage(tip(error))
                .setRequestId(reqId)
                .setState(bookForService(player))
                .build();
    }

    private SwitchSkillLoadoutResponse failSwitchLoadout(ScenePlayer player, String reqId, int error) {
        return SwitchSkillLoadoutResponse.newBuilder()
                .setErrorMessage(tip(error))
                .setRequestId(reqId)
                .setState(bookForService(player))
                .build();
    }

    private UpgradeSkillResponse upgradePet(ScenePlayer player, UpgradeSkillRequest request) {
        var response = UpgradeSkillResponse.newBuilder();
        long petId = request.getPetId();
        var pet = player.pets().find(petId);
        var catalog = player.skillCatalog();
        var species = pet == null ? null : catalog.species(pet.petTableId());
        var row = catalog.row(request.getSkillTableId());
        int error;
        if (!ClassSkillRules.isValidUpgradeCount(request.getCount())) error = kSkillUpgradeCountInvalid_VALUE;
        else if (pet == null) error = PetErrorTip.pet_error.kPetNotFound_VALUE;
        else if (species == null || pets == null) error = CommonErrorTip.common_error.kInvalidTableData_VALUE;
        else if (row == null) error = CommonErrorTip.common_error.kInvalidTableId_VALUE;
        else if (row.getSeasonId() != 0) error = SeasonErrorTip.season_error.kSeasonSkillNoUpgrade_VALUE;
        else if (!ClassSkillCatalog.forSubject(row, 2)) error = kSkillNotInClass_VALUE;
        else if (busy(player) != 0) error = kSkillUpgradeBusy_VALUE;
        else {
            int level = ClassSkillCatalog.petLevel(pet, species);
            int rank = ClassSkillCatalog.petStoredRank(pet, species, row.getId());
            if (PlayerLevels.effective(player.level()) < level) error = PetErrorTip.pet_error.kPetOwnerLevelNotEnough_VALUE;
            else if (rank == 0 && craftUnlockGate.getAsBoolean() && row.getScrollId() > 0
                    && !player.craft().isSkillBookUnlocked(row.getId())) error = kSkillCraftBookRequired_VALUE;
            else if (rank != request.getExpectedRank()) error = kSkillRankChanged_VALUE;
            else {
                int limit = ClassSkillRules.rankLimit(level, row.getUnlockLevel(), row.getMaxRank());
                var plan = ClassSkillRules.planUpgrade(rank, row.getMaxRank(), limit, row.getUnlockLevel(),
                        player.wallet().spendableGoldWithBind(), request.getCount());
                error = plan.steps() == 0 ? stop(plan.stop()) : pets.commitSkillUpgrade(player, petId, row.getId(), plan.finalRank(), plan.totalCost());
                if (error == 0) response.setUpgradedCount(plan.steps()).setStopTipId(plan.steps() < request.getCount() ? stop(plan.stop()) : 0);
            }
        }
        var state = book(player, petId, activeSeason.getAsInt(), craftUnlockGate.getAsBoolean());
        if (state.getErrorMessage().getId() == 0) response.setState(state);
        return response.setErrorMessage(tip(error)).build();
    }

    private UpgradeSkillResponse rejected(ScenePlayer player, int error) {
        return UpgradeSkillResponse.newBuilder().setErrorMessage(tip(error))
                .setState(book(player, activeSeason.getAsInt(), craftUnlockGate.getAsBoolean())).build();
    }
}
