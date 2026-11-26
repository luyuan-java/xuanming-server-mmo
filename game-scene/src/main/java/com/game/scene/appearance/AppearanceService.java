package com.game.scene.appearance;

import static com.game.scene.world.SceneMessageIds.push;
import static com.game.scene.world.SceneMessageIds.tip;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorAppearanceChangedS2C;
import com.game.proto.AppearanceStateNotify;
import com.game.proto.CancelAppearanceTrialResponse;
import com.game.proto.CharacterAppearanceEntry;
import com.game.proto.CharacterAppearanceSeriesProgress;
import com.game.proto.EquipPetCosmeticRequest;
import com.game.proto.EquipPetCosmeticResponse;
import com.game.proto.EquipSkillVfxRequest;
import com.game.proto.EquipSkillVfxResponse;
import com.game.proto.PetCosmeticEntry;
import com.game.proto.PetEquippedCosmetic;
import com.game.proto.PlayerAppearanceInfo;
import com.game.proto.SkillVfxCosmeticEntry;
import com.game.proto.StartAppearanceTrialRequest;
import com.game.proto.StartAppearanceTrialResponse;
import com.game.proto.SwitchAppearanceRequest;
import com.game.proto.SwitchAppearanceResponse;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerAppearance;
import com.game.scene.player.PlayerPets;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.AppearanceErrorTip;
import com.game.table.CommonErrorTip;
import com.google.protobuf.ByteString;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 角色形象切换（商城 10 元角色形象卡）与 1 小时免费试用（bit 位图存储）业务服务。
 *
 * <p>与 C++ {@code PlayerAppearanceSystem} 保持客户端可见契约、错误码、位图格式与幂等语义完全一致。
 * 逻辑线程独占调用；永久形象改动随 {@code PlayerSave} 同事务围栏写回 {@code game.player.appearance_id}。
 */
public final class AppearanceService {

    private static final Logger log = LoggerFactory.getLogger(AppearanceService.class);
    private static final String SERVICE = "SceneAppearanceClientPlayer";

    public static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    public static final int APPEARANCE_INVALID = AppearanceErrorTip.appearance_error.kAppearanceInvalid_VALUE;
    public static final int APPEARANCE_ALREADY_CURRENT = AppearanceErrorTip.appearance_error.kAppearanceAlreadyCurrent_VALUE;
    public static final int APPEARANCE_NO_CARD = AppearanceErrorTip.appearance_error.kAppearanceNoCard_VALUE;
    public static final int APPEARANCE_TRIAL_ALREADY_USED = AppearanceErrorTip.appearance_error.kAppearanceTrialAlreadyUsed_VALUE;
    public static final int APPEARANCE_NO_ACTIVE_TRIAL = AppearanceErrorTip.appearance_error.kAppearanceNoActiveTrial_VALUE;
    public static final int APPEARANCE_FROZEN = AppearanceErrorTip.appearance_error.kAppearanceFrozen_VALUE;
    public static final int APPEARANCE_NOT_CARD_UNLOCKABLE = AppearanceErrorTip.appearance_error.kAppearanceNotCardUnlockable_VALUE;
    public static final int APPEARANCE_ALREADY_OWNED = AppearanceErrorTip.appearance_error.kAppearanceAlreadyOwned_VALUE;
    public static final int APPEARANCE_NOT_OWNED = AppearanceErrorTip.appearance_error.kAppearanceNotOwned_VALUE;
    public static final int PET_NOT_FOUND = com.game.table.PetErrorTip.pet_error.kPetNotFound_VALUE;

    private final AppearanceTables tables;
    private final BagService bags;
    private final SceneClock clock;
    private final int notifyActorAppearanceChangedId;
    private final int notifyAppearanceStateId;

    public AppearanceService(AppearanceTables tables, BagService bags, SceneClock clock, MessageIdRegistry registry) {
        this.tables = tables;
        this.bags = bags;
        this.clock = clock;
        this.notifyActorAppearanceChangedId = registry.requireId(SERVICE, "NotifyActorAppearanceChanged");
        this.notifyAppearanceStateId = registry.requireId(SERVICE, "NotifyAppearanceState");
    }

    private long nowSeconds() {
        return Math.floorDiv(clock.epochMillis(), 1000L);
    }

    /** 进场清理离线期间已到期的试用状态。 */
    public void initializeOnLoad(ScenePlayer player) {
        long nowSec = nowSeconds();
        PlayerAppearance appearance = player.appearance();
        if (!appearance.trialAppearanceId().isEmpty() && !appearance.isTrialActive(nowSec)) {
            log.info("[PlayerAppearance] metric=appearance_trial_expired_on_load player_id={} trial={}",
                    Long.toUnsignedString(player.playerId()), appearance.trialAppearanceId());
            appearance.clearTrial();
        }
    }

    public PlayerAppearanceInfo buildInfo(ScenePlayer player) {
        return buildInfo(player, nowSeconds());
    }

    private boolean isOwned(ScenePlayer player, AppearanceTables.Entry entry) {
        return player.appearanceId().equals(entry.appearanceId())
                || player.appearance().isOwned(entry.bitIndex());
    }

    private byte[] effectiveOwnedBits(ScenePlayer player) {
        byte[] bits = player.appearance().ownedBitsCopy();
        AppearanceTables.Entry perm = tables.findAny(player.appearanceId());
        if (perm != null) {
            bits = PlayerAppearance.setBit(bits, perm.bitIndex());
        }
        return bits;
    }

    public PlayerAppearanceInfo buildInfo(ScenePlayer player, long nowSec) {
        PlayerAppearance appearance = player.appearance();
        AppearanceTables.Rule rule = tables.rule();
        String permanent = player.appearanceId();
        boolean trialActive = appearance.isTrialActive(nowSec);
        String active = trialActive ? appearance.trialAppearanceId() : permanent;

        long ownedCards = player.bags().bag(BagType.INVENTORY).total(rule.cardItemId());
        int clampedCards = (int) Math.min(ownedCards, Integer.MAX_VALUE);
        byte[] ownedBits = effectiveOwnedBits(player);

        PlayerAppearanceInfo.Builder info = PlayerAppearanceInfo.newBuilder()
                .setPermanentAppearanceId(permanent)
                .setActiveAppearanceId(active)
                .setServerNow(nowSec)
                .setCardItemId(rule.cardItemId())
                .setCardItemCount(rule.cardItemCount())
                .setOwnedCardCount(clampedCards)
                .setTrialDurationSeconds(rule.trialDurationSeconds())
                .setMallSkuId(rule.mallSkuId())
                .setMallPriceFen(rule.mallPriceFen())
                .setTrialUsedBits(ByteString.copyFrom(appearance.trialUsedBitsCopy()))
                .setOwnedBits(ByteString.copyFrom(ownedBits))
                .setPetCosmeticOwnedBits(ByteString.copyFrom(appearance.petCosmeticOwnedBitsCopy()))
                .setSkillVfxOwnedBits(ByteString.copyFrom(appearance.skillVfxOwnedBitsCopy()));
        if (trialActive) {
            info.setTrialAppearanceId(appearance.trialAppearanceId())
                    .setTrialExpiresAt(appearance.trialExpiresAt());
        }
        for (AppearanceTables.Entry entry : tables.enabledEntries()) {
            info.addAppearances(CharacterAppearanceEntry.newBuilder()
                    .setConfigId(entry.configId())
                    .setAppearanceId(entry.appearanceId())
                    .setName(entry.name())
                    .setSortOrder(entry.sortOrder())
                    .setTrialUsed(appearance.isTrialUsed(entry.bitIndex()))
                    .setOwned(isOwned(player, entry))
                    .setCardUnlockable(entry.cardUnlockable())
                    .setSeriesId(entry.seriesId())
                    .build());
        }
        for (AppearanceTables.Series series : tables.enabledSeries()) {
            int total = 0;
            int owned = 0;
            CharacterAppearanceSeriesProgress.Builder progress = CharacterAppearanceSeriesProgress.newBuilder()
                    .setSeriesId(series.seriesId())
                    .setSeriesKey(series.seriesKey())
                    .setName(series.name())
                    .setSortOrder(series.sortOrder())
                    .setCompletionBadgeName(series.completionBadgeName())
                    .setEpilogueTitle(series.epilogueTitle());
            for (AppearanceTables.Entry entry : tables.enabledEntries()) {
                if (entry.seriesId() != series.seriesId()) {
                    continue;
                }
                total++;
                if (isOwned(player, entry)) {
                    owned++;
                }
                progress.addMemberAppearanceIds(entry.appearanceId());
            }
            if (total > 0) {
                progress.setOwnedCount(owned)
                        .setTotalCount(total)
                        .setCompleted(owned >= total);
                info.addSeriesProgress(progress.build());
            }
        }
        for (AppearanceTables.PetCosmetic petCosmetic : tables.enabledPetCosmetics()) {
            info.addPetCosmetics(PetCosmeticEntry.newBuilder()
                    .setConfigId(petCosmetic.configId())
                    .setCosmeticKey(petCosmetic.cosmeticKey())
                    .setName(petCosmetic.name())
                    .setTargetPetTableId(petCosmetic.targetPetTableId())
                    .setVisualPetTableId(petCosmetic.visualPetTableId())
                    .setVisualArtKey(petCosmetic.visualArtKey())
                    .setSortOrder(petCosmetic.sortOrder())
                    .setOwned(appearance.isPetCosmeticOwned(petCosmetic.bitIndex()))
                    .setMallSkuId(petCosmetic.mallSkuId())
                    .setAcquireHint(petCosmetic.acquireHint())
                    .setDesc(petCosmetic.desc())
                    .build());
        }
        for (PlayerPets.Pet pet : player.pets().pets()) {
            int cosmeticId = resolveEffectivePetCosmeticId(player, pet);
            if (cosmeticId != 0) {
                info.addEquippedPetCosmetics(PetEquippedCosmetic.newBuilder()
                        .setPetId(pet.petId())
                        .setCosmeticId(cosmeticId)
                        .build());
            }
        }
        for (AppearanceTables.SkillVfxCosmetic skillVfx : tables.enabledSkillVfxCosmetics()) {
            info.addSkillVfxCosmetics(SkillVfxCosmeticEntry.newBuilder()
                    .setConfigId(skillVfx.configId())
                    .setVfxKey(skillVfx.vfxKey())
                    .setName(skillVfx.name())
                    .setSkillTableId(skillVfx.skillTableId())
                    .setFxId(skillVfx.fxId())
                    .setProjectileFxId(skillVfx.projectileFxId())
                    .setTelegraphFxId(skillVfx.telegraphFxId())
                    .setTintHex(skillVfx.tintHex())
                    .setSortOrder(skillVfx.sortOrder())
                    .setOwned(appearance.isSkillVfxOwned(skillVfx.bitIndex()))
                    .setMallSkuId(skillVfx.mallSkuId())
                    .setAcquireHint(skillVfx.acquireHint())
                    .setDesc(skillVfx.desc())
                    .build());
        }
        appearance.equippedSkillVfxView().forEach((skillId, cosmeticId) -> {
            AppearanceTables.SkillVfxCosmetic entry = tables.findEnabledSkillVfxCosmetic(cosmeticId);
            if (entry != null && entry.skillTableId() == skillId
                    && appearance.isSkillVfxOwned(entry.bitIndex())) {
                info.putEquippedSkillVfx(skillId, cosmeticId);
            }
        });
        return info.build();
    }

    private int resolveEffectivePetCosmeticId(ScenePlayer player, PlayerPets.Pet pet) {
        int cosmeticId = pet.cosmeticId();
        if (cosmeticId <= 0) {
            return 0;
        }
        AppearanceTables.PetCosmetic entry = tables.findEnabledPetCosmetic(cosmeticId);
        if (entry == null || !player.appearance().isPetCosmeticOwned(entry.bitIndex())) {
            return 0;
        }
        if (entry.targetPetTableId() != 0 && entry.targetPetTableId() != pet.petTableId()) {
            return 0;
        }
        return cosmeticId;
    }

    public SwitchAppearanceResponse switchAppearance(SceneWorld world, ScenePlayer player, SwitchAppearanceRequest request) {
        long nowSec = nowSeconds();
        long clientRequestId = request.getClientRequestId();
        String target = request.getAppearanceId();
        boolean expectFreeSwitch = request.getExpectFreeSwitch();
        int receiptKind = expectFreeSwitch ? PlayerAppearance.KIND_FREE_SWITCH : PlayerAppearance.KIND_SWITCH;
        if (clientRequestId == 0) {
            return SwitchAppearanceResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        PlayerAppearance appearance = player.appearance();
        switch (appearance.checkIdempotency(clientRequestId, receiptKind, target)) {
            case REPLAY -> {
                return SwitchAppearanceResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .setInfo(buildInfo(player, nowSec))
                        .setReplayed(true)
                        .build();
            }
            case MISMATCH -> {
                return SwitchAppearanceResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
            }
            case NEW -> { }
        }
        if (player.frozen()) {
            return SwitchAppearanceResponse.newBuilder().setErrorMessage(tip(APPEARANCE_FROZEN)).build();
        }
        AppearanceTables.Entry entry = tables.findEnabled(target);
        if (entry == null) {
            return SwitchAppearanceResponse.newBuilder().setErrorMessage(tip(APPEARANCE_INVALID)).build();
        }
        if (player.appearanceId().equals(target)) {
            return SwitchAppearanceResponse.newBuilder().setErrorMessage(tip(APPEARANCE_ALREADY_CURRENT)).build();
        }

        boolean alreadyOwned = isOwned(player, entry);
        boolean consumeCard = false;
        AppearanceTables.Rule rule = tables.rule();
        if (!alreadyOwned) {
            if (expectFreeSwitch) {
                return SwitchAppearanceResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NOT_OWNED)).build();
            }
            if (!entry.cardUnlockable()) {
                return SwitchAppearanceResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NOT_CARD_UNLOCKABLE)).build();
            }
            BagService.PreparedTransfer debit = bags.prepareDebit(
                    player, Map.of(rule.cardItemId(), (long) rule.cardItemCount()), List.of());
            if (debit.tip() != 0) {
                return SwitchAppearanceResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NO_CARD)).build();
            }
            debit.commit(AssetAudit.Reason.ITEM_DESTROY, clientRequestId);
            consumeCard = true;
        }

        String before = player.appearanceId();
        AppearanceTables.Entry beforeEntry = tables.findAny(before);
        if (beforeEntry != null) {
            appearance.markOwned(beforeEntry.bitIndex());
        }
        appearance.markOwned(entry.bitIndex());
        player.setPermanentAppearanceId(target);
        appearance.clearTrial();
        appearance.incrementSwitchSeq();
        appearance.rememberIdempotency(clientRequestId, receiptKind, target);
        if (world != null) {
            world.requestSave(player);
            broadcastChanged(world, player, target, false, 0);
        }
        log.info("[PlayerAppearance] metric=appearance_switched player_id={} before={} after={} free_switch={} card_item_id={} client_request_id={}",
                Long.toUnsignedString(player.playerId()), before, target, !consumeCard,
                consumeCard ? rule.cardItemId() : 0, Long.toUnsignedString(clientRequestId));

        return SwitchAppearanceResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setInfo(buildInfo(player, nowSec))
                .setReplayed(false)
                .build();
    }

    /** 服务端内部权威授形入口（供排位/副本/活动解锁调用）：置位永久拥有位图并推状态通知，不动当前永久或试用外观。 */
    public int grantAppearance(SceneWorld world, ScenePlayer player, String targetAppearanceId) {
        if (player.frozen()) {
            return APPEARANCE_FROZEN;
        }
        AppearanceTables.Entry entry = tables.findEnabled(targetAppearanceId);
        if (entry == null) {
            return APPEARANCE_INVALID;
        }
        if (isOwned(player, entry)) {
            return 0;
        }
        player.appearance().markOwned(entry.bitIndex());
        long nowSec = nowSeconds();
        if (world != null) {
            world.requestSave(player);
            AppearanceStateNotify notify = AppearanceStateNotify.newBuilder()
                    .setInfo(buildInfo(player, nowSec))
                    .setTrialExpired(false)
                    .build();
            world.sendTo(player, push(notifyAppearanceStateId, notify));
        }
        log.info("[PlayerAppearance] metric=appearance_granted player_id={} appearance_id={} config_id={}",
                Long.toUnsignedString(player.playerId()), targetAppearanceId, entry.configId());
        return 0;
    }

    /** 服务端内部权威授形入口（灵宠幻化）：置位永久拥有位图并推衣柜快照，绝不自动替玩家穿戴。 */
    public int grantPetCosmetic(SceneWorld world, ScenePlayer player, int cosmeticId) {
        if (player.frozen()) {
            return APPEARANCE_FROZEN;
        }
        AppearanceTables.PetCosmetic entry = tables.findEnabledPetCosmetic(cosmeticId);
        if (entry == null) {
            return APPEARANCE_INVALID;
        }
        if (player.appearance().isPetCosmeticOwned(entry.bitIndex())) {
            return 0;
        }
        player.appearance().markPetCosmeticOwned(entry.bitIndex());
        long nowSec = nowSeconds();
        if (world != null) {
            world.requestSave(player);
            world.sendTo(player, push(notifyAppearanceStateId, AppearanceStateNotify.newBuilder()
                    .setInfo(buildInfo(player, nowSec))
                    .setTrialExpired(false)
                    .build()));
        }
        log.info("[PlayerAppearance] metric=pet_cosmetic_granted player_id={} cosmetic_id={}",
                Long.toUnsignedString(player.playerId()), cosmeticId);
        return 0;
    }

    /** 服务端内部权威授形入口（技能特效）：置位永久拥有位图并推衣柜快照，绝不自动替玩家启用。 */
    public int grantSkillVfx(SceneWorld world, ScenePlayer player, int cosmeticId) {
        if (player.frozen()) {
            return APPEARANCE_FROZEN;
        }
        AppearanceTables.SkillVfxCosmetic entry = tables.findEnabledSkillVfxCosmetic(cosmeticId);
        if (entry == null) {
            return APPEARANCE_INVALID;
        }
        if (player.appearance().isSkillVfxOwned(entry.bitIndex())) {
            return 0;
        }
        player.appearance().markSkillVfxOwned(entry.bitIndex());
        long nowSec = nowSeconds();
        if (world != null) {
            world.requestSave(player);
            world.sendTo(player, push(notifyAppearanceStateId, AppearanceStateNotify.newBuilder()
                    .setInfo(buildInfo(player, nowSec))
                    .setTrialExpired(false)
                    .build()));
        }
        log.info("[PlayerAppearance] metric=skill_vfx_granted player_id={} cosmetic_id={}",
                Long.toUnsignedString(player.playerId()), cosmeticId);
        return 0;
    }

    public EquipPetCosmeticResponse equipPetCosmetic(SceneWorld world, ScenePlayer player, EquipPetCosmeticRequest request) {
        long nowSec = nowSeconds();
        if (request.getClientRequestId() == 0 || request.getPetId() == 0) {
            return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        if (player.frozen()) {
            return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(APPEARANCE_FROZEN)).build();
        }
        PlayerPets.Pet pet = player.pets().find(request.getPetId());
        if (pet == null) {
            return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(PET_NOT_FOUND)).build();
        }
        int cosmeticId = request.getCosmeticId();
        if (cosmeticId != 0) {
            AppearanceTables.PetCosmetic entry = tables.findEnabledPetCosmetic(cosmeticId);
            if (entry == null) {
                return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(APPEARANCE_INVALID)).build();
            }
            if (entry.targetPetTableId() != 0 && entry.targetPetTableId() != pet.petTableId()) {
                return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(APPEARANCE_INVALID)).build();
            }
            if (!player.appearance().isPetCosmeticOwned(entry.bitIndex())) {
                return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NOT_OWNED)).build();
            }
        }
        if (pet.cosmeticId() != cosmeticId) {
            pet.setCosmeticId(cosmeticId);
            if (world != null) {
                world.requestSave(player);
            }
            log.info("[PlayerAppearance] metric=pet_cosmetic_updated player_id={} pet_id={} cosmetic_id={}",
                    Long.toUnsignedString(player.playerId()), Long.toUnsignedString(pet.petId()), cosmeticId);
        }
        return EquipPetCosmeticResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setInfo(buildInfo(player, nowSec))
                .setPetId(pet.petId())
                .setEquippedCosmeticId(cosmeticId)
                .build();
    }

    public EquipSkillVfxResponse equipSkillVfx(SceneWorld world, ScenePlayer player, EquipSkillVfxRequest request) {
        long nowSec = nowSeconds();
        if (request.getClientRequestId() == 0 || request.getSkillTableId() == 0) {
            return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        if (player.frozen()) {
            return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(APPEARANCE_FROZEN)).build();
        }
        int skillTableId = request.getSkillTableId();
        int cosmeticId = request.getCosmeticId();
        if (cosmeticId != 0) {
            AppearanceTables.SkillVfxCosmetic entry = tables.findEnabledSkillVfxCosmetic(cosmeticId);
            if (entry == null || entry.skillTableId() != skillTableId) {
                return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(APPEARANCE_INVALID)).build();
            }
            if (!player.appearance().isSkillVfxOwned(entry.bitIndex())) {
                return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NOT_OWNED)).build();
            }
        }
        if (player.appearance().equippedSkillVfx(skillTableId) != cosmeticId) {
            player.appearance().setEquippedSkillVfx(skillTableId, cosmeticId);
            if (world != null) {
                world.requestSave(player);
            }
            log.info("[PlayerAppearance] metric=skill_vfx_updated player_id={} skill_table_id={} cosmetic_id={}",
                    Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(skillTableId), cosmeticId);
        }
        return EquipSkillVfxResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setInfo(buildInfo(player, nowSec))
                .setSkillTableId(skillTableId)
                .setEquippedCosmeticId(cosmeticId)
                .build();
    }

    public StartAppearanceTrialResponse startTrial(SceneWorld world, ScenePlayer player, StartAppearanceTrialRequest request) {
        long nowSec = nowSeconds();
        long clientRequestId = request.getClientRequestId();
        String target = request.getAppearanceId();
        if (clientRequestId == 0) {
            return StartAppearanceTrialResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        PlayerAppearance appearance = player.appearance();
        switch (appearance.checkIdempotency(clientRequestId, PlayerAppearance.KIND_START_TRIAL, target)) {
            case REPLAY -> {
                return StartAppearanceTrialResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .setInfo(buildInfo(player, nowSec))
                        .setReplayed(true)
                        .build();
            }
            case MISMATCH -> {
                return StartAppearanceTrialResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
            }
            case NEW -> { }
        }
        if (player.frozen()) {
            return StartAppearanceTrialResponse.newBuilder().setErrorMessage(tip(APPEARANCE_FROZEN)).build();
        }
        AppearanceTables.Entry entry = tables.findEnabled(target);
        if (entry == null) {
            return StartAppearanceTrialResponse.newBuilder().setErrorMessage(tip(APPEARANCE_INVALID)).build();
        }
        if (player.appearanceId().equals(target)) {
            return StartAppearanceTrialResponse.newBuilder().setErrorMessage(tip(APPEARANCE_ALREADY_CURRENT)).build();
        }
        if (isOwned(player, entry)) {
            return StartAppearanceTrialResponse.newBuilder().setErrorMessage(tip(APPEARANCE_ALREADY_OWNED)).build();
        }
        if (appearance.isTrialUsed(entry.bitIndex())) {
            return StartAppearanceTrialResponse.newBuilder().setErrorMessage(tip(APPEARANCE_TRIAL_ALREADY_USED)).build();
        }

        long expiresAt = nowSec + tables.rule().trialDurationSeconds();
        appearance.startTrial(target, entry.bitIndex(), expiresAt);
        appearance.rememberIdempotency(clientRequestId, PlayerAppearance.KIND_START_TRIAL, target);
        if (world != null) {
            world.requestSave(player);
            broadcastChanged(world, player, target, true, expiresAt);
        }
        log.info("[PlayerAppearance] metric=appearance_trial_started player_id={} appearance_id={} config_id={} expires_at={} client_request_id={}",
                Long.toUnsignedString(player.playerId()), target, entry.configId(), expiresAt,
                Long.toUnsignedString(clientRequestId));

        return StartAppearanceTrialResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setInfo(buildInfo(player, nowSec))
                .setReplayed(false)
                .build();
    }

    public CancelAppearanceTrialResponse cancelTrial(SceneWorld world, ScenePlayer player) {
        long nowSec = nowSeconds();
        if (player.frozen()) {
            return CancelAppearanceTrialResponse.newBuilder().setErrorMessage(tip(APPEARANCE_FROZEN)).build();
        }
        PlayerAppearance appearance = player.appearance();
        if (!appearance.isTrialActive(nowSec)) {
            return CancelAppearanceTrialResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NO_ACTIVE_TRIAL)).build();
        }
        String cancelled = appearance.trialAppearanceId();
        appearance.clearTrial();
        if (world != null) {
            world.requestSave(player);
            broadcastChanged(world, player, player.appearanceId(), false, 0);
        }
        log.info("[PlayerAppearance] metric=appearance_trial_cancelled player_id={} trial={} reverted_to={}",
                Long.toUnsignedString(player.playerId()), cancelled, player.appearanceId());

        return CancelAppearanceTrialResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setInfo(buildInfo(player, nowSec))
                .build();
    }

    /** 1 秒节拍扫描所有在场玩家，到期即自动还原永久形象、存盘并广播/推送。 */
    public void tickExpiries(SceneWorld world) {
        long nowSec = nowSeconds();
        for (ScenePlayer player : world.playersSnapshot()) {
            PlayerAppearance appearance = player.appearance();
            if (appearance.trialAppearanceId().isEmpty() || appearance.isTrialActive(nowSec) || player.frozen()) {
                continue;
            }
            String expired = appearance.trialAppearanceId();
            appearance.clearTrial();
            world.requestSave(player);
            broadcastChanged(world, player, player.appearanceId(), false, 0);
            AppearanceStateNotify notify = AppearanceStateNotify.newBuilder()
                    .setInfo(buildInfo(player, nowSec))
                    .setTrialExpired(true)
                    .build();
            world.sendTo(player, push(notifyAppearanceStateId, notify));
            log.info("[PlayerAppearance] metric=appearance_trial_expired player_id={} trial={} reverted_to={}",
                    Long.toUnsignedString(player.playerId()), expired, player.appearanceId());
        }
    }

    private void broadcastChanged(SceneWorld world, ScenePlayer player, String effectiveAppearanceId,
                                  boolean isTrial, long trialExpiresAt) {
        ActorAppearanceChangedS2C event = ActorAppearanceChangedS2C.newBuilder()
                .setEntity(player.entity())
                .setPlayerId(player.playerId())
                .setAppearanceId(effectiveAppearanceId)
                .setIsTrial(isTrial)
                .setTrialExpiresAt(isTrial ? trialExpiresAt : 0)
                .build();
        world.broadcastToSelfAndWatchers(player, push(notifyActorAppearanceChangedId, event));
    }
}
