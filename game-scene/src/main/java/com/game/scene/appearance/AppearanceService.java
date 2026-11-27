package com.game.scene.appearance;

import static com.game.scene.world.SceneMessageIds.push;
import static com.game.scene.world.SceneMessageIds.tip;

import com.game.contract.MessageIdRegistry;
import com.game.player.store.state.ClientRequestRecordState;
import com.game.proto.ActorAppearanceChangedS2C;
import com.game.proto.AppearanceStateNotify;
import com.game.proto.BuyAppearanceSeriesBundleRequest;
import com.game.proto.BuyAppearanceSeriesBundleResponse;
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
import com.game.scene.afkpass.AfkPassRules;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.currency.CurrencyService;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerAppearance;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.player.PlayerPets;
import com.game.scene.player.Wallet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.table.AppearanceErrorTip;
import com.game.table.CommonErrorTip;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Comparator;
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
    public static final int APPEARANCE_UNAVAILABLE = AppearanceErrorTip.appearance_error.kAppearanceUnavailable_VALUE;
    public static final int SERIES_BUNDLE_UNAVAILABLE = AppearanceErrorTip.appearance_error.kAppearanceSeriesBundleUnavailable_VALUE;
    public static final int SERIES_ALREADY_COMPLETED = AppearanceErrorTip.appearance_error.kAppearanceSeriesAlreadyCompleted_VALUE;
    public static final int SERIES_BUNDLE_PRICE_CHANGED = AppearanceErrorTip.appearance_error.kAppearanceSeriesBundlePriceChanged_VALUE;
    public static final int SERIES_BUNDLE_CURRENCY_NOT_ENOUGH = AppearanceErrorTip.appearance_error.kAppearanceSeriesBundleCurrencyNotEnough_VALUE;
    public static final int PET_NOT_FOUND = com.game.table.PetErrorTip.pet_error.kPetNotFound_VALUE;

    private final AppearanceTables tables;
    private final BagService bags;
    private final SceneClock clock;
    private final int notifyActorAppearanceChangedId;
    private final int notifyAppearanceStateId;
    private volatile CurrencyService currency;

    public AppearanceService withCurrency(CurrencyService currency) {
        this.currency = currency;
        return this;
    }

    public static long computeSeriesBundleDiscountDiamond(int missingCount, long unitPriceDiamond, int discountBps) {
        if (missingCount <= 0 || unitPriceDiamond <= 0L || discountBps <= 0 || discountBps >= 10_000) {
            return 0L;
        }
        long original = Math.multiplyExact((long) missingCount, unitPriceDiamond);
        return Math.max(1L, (original * discountBps + 9_999L) / 10_000L);
    }

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
            List<String> missingUnlockable = new ArrayList<>();
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
                } else if (entry.cardUnlockable()) {
                    missingUnlockable.add(entry.appearanceId());
                }
                progress.addMemberAppearanceIds(entry.appearanceId());
            }
            if (total > 0) {
                boolean completed = owned >= total;
                progress.setOwnedCount(owned)
                        .setTotalCount(total)
                        .setCompleted(completed);
                if (!completed && !missingUnlockable.isEmpty()) {
                    missingUnlockable.sort(String::compareTo);
                    long disc = computeSeriesBundleDiscountDiamond(
                            missingUnlockable.size(), series.unitPriceDiamond(), series.bundleDiscountBps());
                    if (disc > 0L) {
                        progress.setMissingUnlockableCount(missingUnlockable.size())
                                .setBundleOriginalDiamond((long) missingUnlockable.size() * series.unitPriceDiamond())
                                .setBundleDiscountDiamond(disc)
                                .setBundleDiscountBps(series.bundleDiscountBps())
                                .addAllMissingAppearanceIds(missingUnlockable);
                    }
                }
                info.addSeriesProgress(progress.build());
            }
        }
        boolean lightCardActive = AfkPassRules.isActive(player.afkPassExpiresAt(), nowSec);
        int trialQuota = AfkPassRules.weeklyFreeTrialQuota(lightCardActive);
        int trialUsed = lightCardActive
                ? Math.min(trialQuota, appearance.lightCardTrialUsedForWeek(AfkPassRules.utc8Monday05WeekStartMs(nowSec * 1000L)))
                : 0;
        info.setLightCardTrialQuota(trialQuota)
                .setLightCardTrialUsed(trialUsed)
                .setDiamondBalance(player.wallet().balance(Wallet.DIAMOND))
                .setBindDiamondBalance(player.wallet().balance(Wallet.BOUND_DIAMOND));
        for (AppearanceTables.PetCosmetic petCosmetic : tables.enabledPetCosmetics()) {
            int voucherOwned = petCosmetic.cardItemId() > 0
                    ? (int) Math.min(player.bags().bag(BagType.INVENTORY).total(petCosmetic.cardItemId()), Integer.MAX_VALUE)
                    : 0;
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
                    .setCardItemId(petCosmetic.cardItemId())
                    .setCardItemCount(petCosmetic.cardItemCount())
                    .setOwnedCardCount(voucherOwned)
                    .setSpiritShopGoodsId(petCosmetic.spiritShopGoodsId())
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
            int voucherOwned = skillVfx.cardItemId() > 0
                    ? (int) Math.min(player.bags().bag(BagType.INVENTORY).total(skillVfx.cardItemId()), Integer.MAX_VALUE)
                    : 0;
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
                    .setCardItemId(skillVfx.cardItemId())
                    .setCardItemCount(skillVfx.cardItemCount())
                    .setOwnedCardCount(voucherOwned)
                    .setSpiritShopGoodsId(skillVfx.spiritShopGoodsId())
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
            BagService.PreparedTransfer debit = bags.prepareSelfConsume(
                    player, Map.of(rule.cardItemId(), (long) rule.cardItemCount()));
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
        long clientRequestId = request.getClientRequestId();
        if (clientRequestId == 0 || request.getPetId() == 0) {
            return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        String receiptTarget = Long.toUnsignedString(request.getPetId()) + ":"
                + Integer.toUnsignedString(request.getCosmeticId()) + ":" + request.getExpectFreeEquip();
        switch (player.appearance().checkIdempotency(clientRequestId, PlayerAppearance.KIND_EQUIP_PET_COSMETIC, receiptTarget)) {
            case REPLAY -> {
                return EquipPetCosmeticResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .setInfo(buildInfo(player, nowSec))
                        .setPetId(request.getPetId())
                        .setEquippedCosmeticId(request.getCosmeticId())
                        .setReplayed(true)
                        .build();
            }
            case MISMATCH -> {
                return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
            }
            case NEW -> { }
        }
        if (player.frozen()) {
            return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(APPEARANCE_FROZEN)).build();
        }
        PlayerPets.Pet pet = player.pets().find(request.getPetId());
        if (pet == null) {
            return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(PET_NOT_FOUND)).build();
        }
        int cosmeticId = request.getCosmeticId();
        boolean consumeVoucher = false;
        AppearanceTables.PetCosmetic targetEntry = null;
        if (cosmeticId != 0) {
            targetEntry = tables.findEnabledPetCosmetic(cosmeticId);
            if (targetEntry == null) {
                return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(APPEARANCE_INVALID)).build();
            }
            if (targetEntry.targetPetTableId() != 0 && targetEntry.targetPetTableId() != pet.petTableId()) {
                return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(APPEARANCE_INVALID)).build();
            }
            if (!player.appearance().isPetCosmeticOwned(targetEntry.bitIndex())) {
                if (request.getExpectFreeEquip()) {
                    return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NOT_OWNED)).build();
                }
                if (targetEntry.cardItemId() <= 0 || targetEntry.cardItemCount() <= 0) {
                    return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NOT_CARD_UNLOCKABLE)).build();
                }
                BagService.PreparedTransfer debit = bags.prepareSelfConsume(
                        player, Map.of(targetEntry.cardItemId(), (long) targetEntry.cardItemCount()));
                if (debit.tip() != 0) {
                    return EquipPetCosmeticResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NO_CARD)).build();
                }
                debit.commit(AssetAudit.Reason.ITEM_DESTROY, clientRequestId);
                player.appearance().markPetCosmeticOwned(targetEntry.bitIndex());
                consumeVoucher = true;
            }
        }
        if (consumeVoucher) {
            pet.setCosmeticId(cosmeticId);
            player.appearance().rememberIdempotency(clientRequestId, PlayerAppearance.KIND_EQUIP_PET_COSMETIC, receiptTarget);
            if (world != null) {
                world.requestSave(player);
            }
            log.info("[PlayerAppearance] metric=pet_cosmetic_unlocked player_id={} pet_id={} cosmetic_id={} card_item_id={} client_request_id={}",
                    Long.toUnsignedString(player.playerId()), Long.toUnsignedString(pet.petId()), cosmeticId,
                    targetEntry.cardItemId(), Long.toUnsignedString(clientRequestId));
            PlayerAppearanceInfo info = buildInfo(player, nowSec);
            if (world != null) {
                world.sendTo(player, push(notifyAppearanceStateId, AppearanceStateNotify.newBuilder()
                        .setInfo(info).setTrialExpired(false).build()));
            }
            return EquipPetCosmeticResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setInfo(info)
                    .setPetId(pet.petId())
                    .setEquippedCosmeticId(cosmeticId)
                    .setReplayed(false)
                    .build();
        }
        boolean replayed = (pet.cosmeticId() == cosmeticId);
        if (!replayed) {
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
                .setReplayed(replayed)
                .build();
    }

    public EquipSkillVfxResponse equipSkillVfx(SceneWorld world, ScenePlayer player, EquipSkillVfxRequest request) {
        long nowSec = nowSeconds();
        long clientRequestId = request.getClientRequestId();
        if (clientRequestId == 0 || request.getSkillTableId() == 0) {
            return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        String receiptTarget = Integer.toUnsignedString(request.getSkillTableId()) + ":"
                + Integer.toUnsignedString(request.getCosmeticId()) + ":" + request.getExpectFreeEquip();
        switch (player.appearance().checkIdempotency(clientRequestId, PlayerAppearance.KIND_EQUIP_SKILL_VFX, receiptTarget)) {
            case REPLAY -> {
                return EquipSkillVfxResponse.newBuilder()
                        .setErrorMessage(tip(0))
                        .setInfo(buildInfo(player, nowSec))
                        .setSkillTableId(request.getSkillTableId())
                        .setEquippedCosmeticId(request.getCosmeticId())
                        .setReplayed(true)
                        .build();
            }
            case MISMATCH -> {
                return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
            }
            case NEW -> { }
        }
        if (player.frozen()) {
            return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(APPEARANCE_FROZEN)).build();
        }
        int skillTableId = request.getSkillTableId();
        int cosmeticId = request.getCosmeticId();
        boolean consumeVoucher = false;
        AppearanceTables.SkillVfxCosmetic targetEntry = null;
        if (cosmeticId != 0) {
            targetEntry = tables.findEnabledSkillVfxCosmetic(cosmeticId);
            if (targetEntry == null || targetEntry.skillTableId() != skillTableId) {
                return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(APPEARANCE_INVALID)).build();
            }
            if (!player.appearance().isSkillVfxOwned(targetEntry.bitIndex())) {
                if (request.getExpectFreeEquip()) {
                    return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NOT_OWNED)).build();
                }
                if (targetEntry.cardItemId() <= 0 || targetEntry.cardItemCount() <= 0) {
                    return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NOT_CARD_UNLOCKABLE)).build();
                }
                BagService.PreparedTransfer debit = bags.prepareSelfConsume(
                        player, Map.of(targetEntry.cardItemId(), (long) targetEntry.cardItemCount()));
                if (debit.tip() != 0) {
                    return EquipSkillVfxResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NO_CARD)).build();
                }
                debit.commit(AssetAudit.Reason.ITEM_DESTROY, clientRequestId);
                player.appearance().markSkillVfxOwned(targetEntry.bitIndex());
                consumeVoucher = true;
            }
        }
        if (consumeVoucher) {
            player.appearance().setEquippedSkillVfx(skillTableId, cosmeticId);
            player.appearance().rememberIdempotency(clientRequestId, PlayerAppearance.KIND_EQUIP_SKILL_VFX, receiptTarget);
            if (world != null) {
                world.requestSave(player);
            }
            log.info("[PlayerAppearance] metric=skill_vfx_unlocked player_id={} skill_table_id={} cosmetic_id={} card_item_id={} client_request_id={}",
                    Long.toUnsignedString(player.playerId()), Integer.toUnsignedString(skillTableId), cosmeticId,
                    targetEntry.cardItemId(), Long.toUnsignedString(clientRequestId));
            PlayerAppearanceInfo info = buildInfo(player, nowSec);
            if (world != null) {
                world.sendTo(player, push(notifyAppearanceStateId, AppearanceStateNotify.newBuilder()
                        .setInfo(info).setTrialExpired(false).build()));
            }
            return EquipSkillVfxResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setInfo(info)
                    .setSkillTableId(skillTableId)
                    .setEquippedCosmeticId(cosmeticId)
                    .setReplayed(false)
                    .build();
        }
        boolean replayed = (player.appearance().equippedSkillVfx(skillTableId) == cosmeticId);
        if (!replayed) {
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
                .setReplayed(replayed)
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
        long expiresAt = nowSec + tables.rule().trialDurationSeconds();
        if (appearance.isTrialUsed(entry.bitIndex())) {
            boolean lightCardActive = AfkPassRules.isActive(player.afkPassExpiresAt(), nowSec);
            long weekStartMs = AfkPassRules.utc8Monday05WeekStartMs(nowSec * 1000L);
            if (!lightCardActive || appearance.lightCardTrialUsedForWeek(weekStartMs) >= AfkPassRules.LIGHT_CARD_WEEKLY_FREE_TRIAL_QUOTA) {
                return StartAppearanceTrialResponse.newBuilder().setErrorMessage(tip(APPEARANCE_TRIAL_ALREADY_USED)).build();
            }
            appearance.startLightCardExtraTrial(target, expiresAt, weekStartMs);
        } else {
            appearance.startTrial(target, entry.bitIndex(), expiresAt);
        }
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

    /** 592 BuyAppearanceSeriesBundle：Steam 式按缺口 8.5 折一键补齐收藏系列缺失角色（仅扣真实灵石 + 幂等 + 报价防呆）。 */
    public BuyAppearanceSeriesBundleResponse buySeriesBundle(SceneWorld world, ScenePlayer player,
                                                             BuyAppearanceSeriesBundleRequest req) {
        long nowSec = nowSeconds();
        long reqId = req.getClientRequestId();
        int seriesId = req.getSeriesId();
        if (reqId == 0L || seriesId == 0) {
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        PlayerClientRequestLedger ledger = player.clientRequestLedger();
        if (!ledger.isValid()) {
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(APPEARANCE_UNAVAILABLE)).build();
        }
        ByteString fingerprint = PlayerClientRequestLedger.fingerprint(req.toBuilder().clearClientRequestId().build());
        var check = ledger.check(reqId, PlayerClientRequestLedger.KIND_BUY_APPEARANCE_SERIES_BUNDLE, fingerprint);
        if (check.verdict() == PlayerClientRequestLedger.Verdict.MISMATCH) {
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        if (check.verdict() == PlayerClientRequestLedger.Verdict.REPLAY) {
            return BuyAppearanceSeriesBundleResponse.newBuilder()
                    .setErrorMessage(tip(0))
                    .setInfo(buildInfo(player, nowSec))
                    .setReplayed(true)
                    .setUnlockedCount(check.record().getAppearanceBundleUnlockedCount())
                    .setSpentDiamond(check.record().getAppearanceBundleSpentDiamond())
                    .build();
        }
        if (player.frozen()) {
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(APPEARANCE_FROZEN)).build();
        }
        CurrencyService currencySvc = this.currency;
        if (currencySvc == null) {
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(APPEARANCE_UNAVAILABLE)).build();
        }
        AppearanceTables.Series series = null;
        for (AppearanceTables.Series s : tables.enabledSeries()) {
            if (s.seriesId() == seriesId) {
                series = s;
                break;
            }
        }
        if (series == null || series.bundleDiscountBps() <= 0 || series.bundleDiscountBps() >= 10_000
                || series.unitPriceDiamond() <= 0L) {
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(SERIES_BUNDLE_UNAVAILABLE)).build();
        }

        int total = 0;
        int owned = 0;
        List<AppearanceTables.Entry> missing = new ArrayList<>();
        for (AppearanceTables.Entry entry : tables.enabledEntries()) {
            if (entry.seriesId() != seriesId) {
                continue;
            }
            total++;
            if (isOwned(player, entry)) {
                owned++;
            } else if (entry.cardUnlockable()) {
                missing.add(entry);
            }
        }
        if (total == 0) {
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(SERIES_BUNDLE_UNAVAILABLE)).build();
        }
        if (owned >= total) {
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(SERIES_ALREADY_COMPLETED)).build();
        }
        if (missing.isEmpty()) {
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(SERIES_BUNDLE_UNAVAILABLE)).build();
        }
        missing.sort(Comparator.comparing(AppearanceTables.Entry::appearanceId));
        List<String> actualIds = missing.stream().map(AppearanceTables.Entry::appearanceId).toList();
        long discountPrice = computeSeriesBundleDiscountDiamond(missing.size(), series.unitPriceDiamond(), series.bundleDiscountBps());
        List<String> expectedIds = new ArrayList<>(req.getExpectedMissingAppearanceIdsList());
        expectedIds.sort(String::compareTo);
        if (!actualIds.equals(expectedIds) || req.getExpectedPriceDiamond() != discountPrice) {
            return BuyAppearanceSeriesBundleResponse.newBuilder()
                    .setErrorMessage(tip(SERIES_BUNDLE_PRICE_CHANGED))
                    .setInfo(buildInfo(player, nowSec))
                    .build();
        }
        if (Long.compareUnsigned(player.wallet().balance(Wallet.DIAMOND), discountPrice) < 0) {
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(SERIES_BUNDLE_CURRENCY_NOT_ENOUGH)).build();
        }

        Wallet.Change deducted = currencySvc.deduct(player, Wallet.DIAMOND, discountPrice,
                AssetAudit.Reason.APPEARANCE_SERIES_BUNDLE, reqId);
        if (!deducted.ok()) {
            int tipId = deducted.tipId() == Wallet.FROZEN ? APPEARANCE_FROZEN : SERIES_BUNDLE_CURRENCY_NOT_ENOUGH;
            return BuyAppearanceSeriesBundleResponse.newBuilder().setErrorMessage(tip(tipId)).build();
        }
        PlayerAppearance appearance = player.appearance();
        AppearanceTables.Entry perm = tables.findAny(player.appearanceId());
        if (perm != null) {
            appearance.markOwned(perm.bitIndex());
        }
        for (AppearanceTables.Entry entry : missing) {
            appearance.markOwned(entry.bitIndex());
        }
        ledger.remember(ClientRequestRecordState.newBuilder()
                .setKey(reqId)
                .setKind(PlayerClientRequestLedger.KIND_BUY_APPEARANCE_SERIES_BUNDLE)
                .setFingerprint(fingerprint)
                .setAppearanceBundleUnlockedCount(missing.size())
                .setAppearanceBundleSpentDiamond(discountPrice));
        PlayerAppearanceInfo info = buildInfo(player, nowSec);
        if (world != null) {
            world.requestKeyProgressSave(player);
            world.sendTo(player, push(notifyAppearanceStateId, AppearanceStateNotify.newBuilder()
                    .setInfo(info).setTrialExpired(false).build()));
        }
        log.info("[PlayerAppearance] metric=appearance_series_bundle_bought player_id={} series_id={} unlocked={} spent_diamond={} client_request_id={}",
                Long.toUnsignedString(player.playerId()), seriesId, missing.size(), discountPrice, Long.toUnsignedString(reqId));
        return BuyAppearanceSeriesBundleResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setInfo(info)
                .setReplayed(false)
                .setUnlockedCount(missing.size())
                .setSpentDiamond(discountPrice)
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
