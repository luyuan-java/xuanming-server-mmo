package com.game.scene.appearance;

import static com.game.scene.world.SceneMessageIds.push;
import static com.game.scene.world.SceneMessageIds.tip;

import com.game.contract.MessageIdRegistry;
import com.game.proto.ActorAppearanceChangedS2C;
import com.game.proto.AppearanceStateNotify;
import com.game.proto.CancelAppearanceTrialResponse;
import com.game.proto.CharacterAppearanceEntry;
import com.game.proto.PlayerAppearanceInfo;
import com.game.proto.StartAppearanceTrialRequest;
import com.game.proto.StartAppearanceTrialResponse;
import com.game.proto.SwitchAppearanceRequest;
import com.game.proto.SwitchAppearanceResponse;
import com.game.scene.audit.AssetAudit;
import com.game.scene.bag.BagService;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerAppearance;
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

    public PlayerAppearanceInfo buildInfo(ScenePlayer player, long nowSec) {
        PlayerAppearance appearance = player.appearance();
        AppearanceTables.Rule rule = tables.rule();
        String permanent = player.appearanceId();
        boolean trialActive = appearance.isTrialActive(nowSec);
        String active = trialActive ? appearance.trialAppearanceId() : permanent;

        long ownedCards = player.bags().bag(BagType.INVENTORY).total(rule.cardItemId());
        int clampedCards = (int) Math.min(ownedCards, Integer.MAX_VALUE);

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
                .setTrialUsedBits(ByteString.copyFrom(appearance.trialUsedBitsCopy()));
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
                    .build());
        }
        return info.build();
    }

    public SwitchAppearanceResponse switchAppearance(SceneWorld world, ScenePlayer player, SwitchAppearanceRequest request) {
        long nowSec = nowSeconds();
        long clientRequestId = request.getClientRequestId();
        String target = request.getAppearanceId();
        if (clientRequestId == 0) {
            return SwitchAppearanceResponse.newBuilder().setErrorMessage(tip(INVALID_PARAMETER)).build();
        }
        PlayerAppearance appearance = player.appearance();
        switch (appearance.checkIdempotency(clientRequestId, PlayerAppearance.KIND_SWITCH, target)) {
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

        AppearanceTables.Rule rule = tables.rule();
        BagService.PreparedTransfer debit = bags.prepareDebit(
                player, Map.of(rule.cardItemId(), (long) rule.cardItemCount()), List.of());
        if (debit.tip() != 0) {
            return SwitchAppearanceResponse.newBuilder().setErrorMessage(tip(APPEARANCE_NO_CARD)).build();
        }
        debit.commit(AssetAudit.Reason.ITEM_DESTROY, clientRequestId);

        String before = player.appearanceId();
        player.setPermanentAppearanceId(target);
        appearance.clearTrial();
        appearance.incrementSwitchSeq();
        appearance.rememberIdempotency(clientRequestId, PlayerAppearance.KIND_SWITCH, target);
        if (world != null) {
            world.requestSave(player);
            broadcastChanged(world, player, target, false, 0);
        }
        log.info("[PlayerAppearance] metric=appearance_switched player_id={} before={} after={} card_item_id={} client_request_id={}",
                Long.toUnsignedString(player.playerId()), before, target, rule.cardItemId(),
                Long.toUnsignedString(clientRequestId));

        return SwitchAppearanceResponse.newBuilder()
                .setErrorMessage(tip(0))
                .setInfo(buildInfo(player, nowSec))
                .setReplayed(false)
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
