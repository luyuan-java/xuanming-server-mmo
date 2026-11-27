package com.game.scene.festivalbeast;

import static com.game.scene.world.SceneMessageIds.push;
import static com.game.scene.world.SceneMessageIds.tip;

import com.game.player.store.state.EquipmentMaterialRewardLedgerState;
import com.game.player.store.state.FestivalBeastPendingLaunchState;
import com.game.player.store.state.FestivalBeastPeriodProgressState;
import com.game.player.store.state.FestivalBeastClaimReceiptState;
import com.game.player.store.state.FestivalBeastPermanentEligibilityState;
import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PlayerFestivalBeastState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattleSettlementData;
import com.game.proto.ClaimFestivalBeastRewardRequest;
import com.game.proto.ClaimFestivalBeastRewardResponse;
import com.game.proto.FestivalBeastActionBlockCode;
import com.game.proto.FestivalBeastBattleContext;
import com.game.proto.FestivalBeastClaimOutcome;
import com.game.proto.FestivalBeastLaunchState;
import com.game.proto.FestivalBeastMaterialQuotaMode;
import com.game.proto.FestivalBeastMaterialSummary;
import com.game.proto.FestivalBeastPendingLaunchInfo;
import com.game.proto.FestivalBeastPhase;
import com.game.proto.GetFestivalBeastDetailRequest;
import com.game.proto.GetFestivalBeastDetailResponse;
import com.game.proto.PetListChangedS2C;
import com.game.proto.PlayerActivityCategory;
import com.game.proto.PlayerActivityEntryKind;
import com.game.proto.PlayerActivityInfo;
import com.game.proto.PlayerActivityRewardType;
import com.game.proto.PlayerActivityStatus;
import com.game.proto.StartFestivalBeastChallengeRequest;
import com.game.proto.StartFestivalBeastChallengeResponse;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.scene.equip.EquipmentCraftService;
import com.game.scene.pet.PetService;
import com.game.scene.player.PlayerPets;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.SceneWorld;
import com.game.scene.world.SwitchPhase;
import com.game.table.CommonErrorTip.common_error;
import com.game.table.FestivalBeastActivityTable;
import com.game.table.FestivalBeastErrorTip.festival_beast_error;
import com.game.table.FestivalBeastTierTable;
import com.game.table.PetErrorTip.pet_error;
import com.game.table.PetTable;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 瑞兽迎春限时活动核心服务（仅在场景逻辑线程调用；对齐 C++ {@code PlayerFestivalBeastSystem}）：
 * <ul>
 *   <li>476 {@code GetFestivalBeastDetail}：权威详情、永久待领资格、跨设备在途恢复态与全游戏材料摘要；</li>
 *   <li>477 {@code StartFestivalBeastChallenge}：单人携宠锁宠开局、先持久在途意图再经 Match 权威准入、同 UUID 幂等重放；</li>
 *   <li>478 {@code ClaimFestivalBeastReward}：永久资格领奖（不受活动结束/历史态/改表影响）、同 UUID 重放、异 UUID 并发收敛、专属赠送格、落盘确认 durable；</li>
 *   <li>备战锁宠复核、备战取消/判废清理、结算增量与活动大厅目录入口。</li>
 * </ul>
 */
public final class FestivalBeastService {

    private static final Logger log = LoggerFactory.getLogger(FestivalBeastService.class);

    static final int OK = 0;
    static final int INVALID_PARAMETER = common_error.kInvalidParameter_VALUE;
    static final int NOT_OPEN = festival_beast_error.kFestivalBeastNotOpen_VALUE;
    static final int CONFIG_INVALID = festival_beast_error.kFestivalBeastConfigInvalid_VALUE;
    static final int CONFIG_REVISION_MISMATCH = festival_beast_error.kFestivalBeastConfigRevisionMismatch_VALUE;
    static final int PET_REQUIRED = festival_beast_error.kFestivalBeastPetRequired_VALUE;
    static final int PET_DEAD = festival_beast_error.kFestivalBeastPetDead_VALUE;
    static final int PET_CHANGED = festival_beast_error.kFestivalBeastPetChanged_VALUE;
    static final int LAUNCH_IN_FLIGHT = festival_beast_error.kFestivalBeastLaunchInFlight_VALUE;
    static final int IN_BATTLE = festival_beast_error.kFestivalBeastInBattle_VALUE;
    static final int FROZEN = festival_beast_error.kFestivalBeastFrozen_VALUE;
    static final int NOT_ELIGIBLE = festival_beast_error.kFestivalBeastNotEligible_VALUE;
    static final int EXCLUSIVE_SLOT_OCCUPIED = festival_beast_error.kFestivalBeastExclusiveSlotOccupied_VALUE;
    static final int REQUEST_CONFLICT = festival_beast_error.kFestivalBeastRequestConflict_VALUE;
    static final int SERVICE_UNAVAILABLE = festival_beast_error.kFestivalBeastServiceUnavailable_VALUE;

    @FunctionalInterface
    public interface LaunchCallback {
        void onReply(boolean ok, StartActivityBattleResponse reply);
    }

    @FunctionalInterface
    public interface LaunchSender {
        boolean send(StartActivityBattleRequest request, LaunchCallback callback);
    }

    /** 单局瑞兽迎春结算进度增量（供近期战果与 BattleEndS2C 共用）。 */
    public record SettlementDelta(
            boolean active,
            int activityInstanceId,
            String rewardIdentity,
            int winsBefore,
            int winsAfter,
            int requiredWins,
            boolean newlyEligible,
            boolean changed) {
        public static final SettlementDelta NONE = new SettlementDelta(false, 0, "", 0, 0, 0, false, false);
    }

    private record ActiveConfigView(
            boolean found,
            boolean multipleEnabled,
            boolean valid,
            FestivalBeastActivityTable row) {
    }

    private record PetCheckResult(int tipId, long petId) {
    }

    private record MatchRejectMapping(int tipId, String reason) {
    }

    private final FestivalBeastTables tables;
    private final PetService pets;
    private final SceneClock clock;
    private final int notifyPetListChanged;
    private LaunchSender launchSender;

    public FestivalBeastService(FestivalBeastTables tables, PetService pets, SceneClock clock,
                                int notifyPetListChanged) {
        this.tables = Objects.requireNonNull(tables, "tables");
        this.pets = Objects.requireNonNull(pets, "pets");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.notifyPetListChanged = notifyPetListChanged;
    }

    public FestivalBeastService withLaunchSender(LaunchSender launchSender) {
        this.launchSender = launchSender;
        return this;
    }

    public long nowMillis() {
        return clock.epochMillis();
    }

    // ================================================================== 材料摘要与活动目录

    /** 构建全游戏统一装备材料账摘要（零写操作，与 407 同源）。 */
    public static FestivalBeastMaterialSummary buildMaterialSummary(ScenePlayer player, long nowMs) {
        FestivalBeastMaterialSummary.Builder out = FestivalBeastMaterialSummary.newBuilder();
        if (player == null || !player.clientRequestLedger().isValid()) {
            return out.build();
        }
        long[] day = EquipmentCraftService.resolveDay(nowMs);
        long[] week = EquipmentCraftService.resolveWeek(nowMs);
        if (day == null || week == null) {
            return out.build();
        }
        EquipmentMaterialRewardLedgerState state = player.clientRequestLedger().materialReward();
        int lifetimeEarned = state.getMigratedToV2() ? state.getLifetimeEarned() : 0;
        long availableUnits = state.getAvailableUnits();
        long availableTradable = state.getMigratedToV2()
                ? Math.min(state.getAvailableTradableUnits(), availableUnits)
                : 0L;
        boolean weeklyPhase = Integer.compareUnsigned(lifetimeEarned, EquipmentCraftService.LIFETIME_LIMIT) >= 0;
        EquipmentCraftService.Stage2WeekState stage2 = weeklyPhase
                ? EquipmentCraftService.resolveStage2WeekState(state, week[0])
                : new EquipmentCraftService.Stage2WeekState(0, EquipmentCraftService.WEEKLY_LIMIT, false);
        int earnedThisWeek = stage2.earned();
        int weeklyLimit = stage2.poolCap();
        int earnedToday = state.getDayStartsAtMs() == day[0] ? state.getEarnedToday() : 0;
        int earnableRemaining;
        if (weeklyPhase) {
            out.setQuotaMode(FestivalBeastMaterialQuotaMode.FESTIVAL_MATERIAL_QUOTA_STAGE2_WEEKLY);
            earnableRemaining = Integer.compareUnsigned(weeklyLimit, earnedThisWeek) > 0
                    ? (weeklyLimit - earnedThisWeek)
                    : 0;
        } else {
            out.setQuotaMode(FestivalBeastMaterialQuotaMode.FESTIVAL_MATERIAL_QUOTA_STAGE1_LIFETIME);
            earnableRemaining = Integer.compareUnsigned(EquipmentCraftService.LIFETIME_LIMIT, lifetimeEarned) > 0
                    ? (EquipmentCraftService.LIFETIME_LIMIT - lifetimeEarned)
                    : 0;
        }
        int expectedWin = (int) Math.min(Integer.toUnsignedLong(EquipmentCraftService.BATTLE_UNITS),
                Integer.toUnsignedLong(earnableRemaining));
        return out.setAvailableUnits(availableUnits)
                .setAvailableTradableUnits(availableTradable)
                .setEarnedToday(earnedToday)
                .setDailyLimit(EquipmentCraftService.DAILY_LIMIT)
                .setDayStartsAtMs(day[0])
                .setNextDayResetAtMs(day[1])
                .setWeeklyPhaseActive(weeklyPhase)
                .setLifetimeEarned(lifetimeEarned)
                .setLifetimeLimit(EquipmentCraftService.LIFETIME_LIMIT)
                .setEarnedThisWeek(earnedThisWeek)
                .setWeeklyLimit(weeklyLimit)
                .setWeekStartsAtMs(week[0])
                .setNextWeekResetAtMs(week[1])
                .setEarnableRemainingUnits(earnableRemaining)
                .setExpectedWinUnits(expectedWin)
                .build();
    }

    /** 若当前有开放/预告/展示期活动或玩家存在未领/已领永久神兽记录，则返回大厅活动入口，否则返回 null。 */
    public PlayerActivityInfo buildCatalogEntry(ScenePlayer player, long nowMs) {
        GetFestivalBeastDetailResponse detail = getDetail(player, GetFestivalBeastDetailRequest.getDefaultInstance(), nowMs);
        if (detail.getErrorMessage().getId() != OK) {
            return null;
        }
        boolean hasUnclaimed = detail.getHasPermanentEligibility() && !detail.getRewardClaimed();
        if (detail.getPhase() == FestivalBeastPhase.FESTIVAL_BEAST_PHASE_UNSCHEDULED
                && !hasUnclaimed && !detail.getRewardClaimed()) {
            return null;
        }
        PlayerActivityStatus status = switch (detail.getPhase()) {
            case FESTIVAL_BEAST_PHASE_UPCOMING -> PlayerActivityStatus.PLAYER_ACTIVITY_UPCOMING;
            case FESTIVAL_BEAST_PHASE_OPEN -> PlayerActivityStatus.PLAYER_ACTIVITY_OPEN;
            case FESTIVAL_BEAST_PHASE_CLAIM_SHOWCASE, FESTIVAL_BEAST_PHASE_HISTORY -> PlayerActivityStatus.PLAYER_ACTIVITY_ENDED;
            default -> PlayerActivityStatus.PLAYER_ACTIVITY_UNSCHEDULED;
        };
        return PlayerActivityInfo.newBuilder()
                .setActivityId(detail.getActivityInstanceId())
                .setName(detail.getActivityName())
                .setDescription("单人携出战宝宝挑战" + detail.getBossName() + "，累计获胜"
                        + Integer.toUnsignedString(detail.getRequiredWins()) + "次免费领取专属神兽")
                .setIconKey(detail.getIconKey())
                .setStartsAtMs(detail.getEffectiveStartAtMs())
                .setEndsAtMs(detail.getEffectiveEndAtMs())
                .setCanParticipate(detail.getCanStartChallenge() || detail.getCanClaimReward())
                .setUnavailableReason(detail.getCanClaimReward() ? "" : detail.getStartBlockReason())
                .setDefinitionKey(detail.getRewardIdentity())
                .setConfigurationRevision(Integer.toUnsignedLong(detail.getConfigRevision()))
                .setCategory(PlayerActivityCategory.PLAYER_ACTIVITY_CATEGORY_LIMITED)
                .addRewardTypes(PlayerActivityRewardType.PLAYER_ACTIVITY_REWARD_PET)
                .addRewardTypes(PlayerActivityRewardType.PLAYER_ACTIVITY_REWARD_ITEM)
                .setEntryKind(PlayerActivityEntryKind.PLAYER_ACTIVITY_ENTRY_FESTIVAL_BEAST)
                .setCompletionCountKnown(true)
                .setCompletedCount(detail.getCurrentWins())
                .setCompletionLimit(detail.getRequiredWins())
                .setCompletionPeriod("本期累计")
                .setHasClaimableReward(detail.getCanClaimReward())
                .setStatus(status)
                .build();
    }

    // ================================================================== 476 查询详情

    public GetFestivalBeastDetailResponse getDetail(ScenePlayer player, GetFestivalBeastDetailRequest request, long nowMs) {
        PlayerFestivalBeastState state = player.festivalBeast().state();
        GetFestivalBeastDetailResponse.Builder resp = GetFestivalBeastDetailResponse.newBuilder()
                .setErrorMessage(tip(OK))
                .setServerNowMs(nowMs)
                .setStateRevision(state.getStateRevision())
                .setMaterialSummary(buildMaterialSummary(player, nowMs));

        ActiveConfigView cfg = resolveConfig(request.getActivityInstanceId(), state);
        FestivalBeastPermanentEligibilityState unclaimedAny = findAnyUnclaimedEligibility(state);
        if (!cfg.found()) {
            resp.setPhase(unclaimedAny != null
                            ? FestivalBeastPhase.FESTIVAL_BEAST_PHASE_HISTORY
                            : FestivalBeastPhase.FESTIVAL_BEAST_PHASE_UNSCHEDULED)
                    .setCanStartChallenge(false)
                    .setStartBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_NOT_OPEN)
                    .setStartBlockReason("瑞兽迎春活动当前未排期");
            if (unclaimedAny != null) {
                resp.setRewardIdentity(unclaimedAny.getRewardIdentity())
                        .setRewardDefinitionVersion(unclaimedAny.getRewardDefinitionVersion())
                        .setRewardPetTableId(unclaimedAny.getRewardPetTableId())
                        .setHasPermanentEligibility(true)
                        .setEligibilitySourceInstanceId(unclaimedAny.getSourceActivityInstanceId())
                        .setEligibilityEarnedAtMs(unclaimedAny.getEarnedAtMs());
                PetTable petRow = tables.pet(unclaimedAny.getRewardPetTableId());
                if (FestivalBeastRules.isValidRewardPetRow(petRow)) {
                    resp.setRewardPetModelId(petRow.getModelId())
                            .setRewardPetName(petRow.getName())
                            .setBossName(petRow.getName())
                            .setActivityName("瑞兽迎春");
                    boolean writable = !player.frozen();
                    boolean inBattle = player.inBattle();
                    boolean hasSlot = pets.hasExclusiveGiftSlot(player.pets(), petRow.getId());
                    resp.setCanClaimReward(writable && !inBattle && hasSlot);
                    if (!writable) {
                        resp.setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_FROZEN)
                                .setClaimBlockReason("当前角色状态暂时不能领取，请稍后重试");
                    } else if (inBattle) {
                        resp.setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_IN_BATTLE)
                                .setClaimBlockReason("战斗中暂不能领取神兽，请在结算后重试");
                    } else if (!hasSlot) {
                        resp.setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_EXCLUSIVE_SLOT_OCCUPIED)
                                .setClaimBlockReason("已拥有该专属神兽，无需重复领取");
                    }
                } else {
                    resp.setCanClaimReward(false)
                            .setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_CONFIG_INVALID)
                            .setClaimBlockReason("神兽配置或资源引用不完整，暂不可发放");
                }
            } else {
                resp.setCanClaimReward(false)
                        .setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_NOT_ELIGIBLE)
                        .setClaimBlockReason("尚未满足领取条件");
            }
            return resp.build();
        }

        FestivalBeastActivityTable row = cfg.row();
        FestivalBeastPhase phase = FestivalBeastRules.resolvePhase(
                row.getEnabled(), row.getEffectiveStartAtMs(), row.getEffectiveEndAtMs(),
                row.getClaimShowcaseDays(), nowMs);
        resp.setPhase(phase)
                .setActivityInstanceId(row.getId())
                .setConfigRevision(row.getConfigRevision())
                .setActivityName(row.getActivityName())
                .setBossName(row.getBossName())
                .setBannerAssetKey(row.getBannerAssetKey())
                .setIconKey(row.getIconKey())
                .setEffectiveStartAtMs(row.getEffectiveStartAtMs())
                .setEffectiveEndAtMs(row.getEffectiveEndAtMs())
                .setClaimShowcaseEndAtMs(FestivalBeastRules.claimShowcaseEndAtMs(
                        row.getEffectiveEndAtMs(), row.getClaimShowcaseDays()))
                .setRequiredWins(row.getRequiredWins())
                .setRoundLimit(row.getRoundLimit())
                .setHeavyDamagePercent(row.getHeavyDamagePercent());

        int ownerLevel = cappedOwnerLevel(player);
        FestivalBeastTierTable tier = FestivalBeastRules.findTierForLevel(tables.tiers(), ownerLevel);
        if (tier != null) {
            resp.setMatchedTierId(tier.getId());
        }

        FestivalBeastPeriodProgressState period = findPeriodProgress(state, row.getId());
        if (period != null) {
            resp.setCurrentWins(Math.min(period.getWins(), row.getRequiredWins()));
        }

        FestivalBeastPermanentEligibilityState elig = findEligibility(state, row.getRewardIdentity());
        FestivalBeastClaimReceiptState receipt = findClaimByIdentity(state, row.getRewardIdentity());
        int effectiveRewardVersion = receipt != null
                ? receipt.getRewardDefinitionVersion()
                : (elig != null ? elig.getRewardDefinitionVersion() : row.getRewardDefinitionVersion());
        int effectiveRewardPetId = receipt != null
                ? receipt.getRewardPetTableId()
                : (elig != null ? elig.getRewardPetTableId() : row.getRewardPetTableId());
        resp.setRewardIdentity(row.getRewardIdentity())
                .setRewardDefinitionVersion(effectiveRewardVersion)
                .setRewardPetTableId(effectiveRewardPetId);
        PetTable petRow = tables.pet(effectiveRewardPetId);
        if (petRow != null) {
            resp.setRewardPetModelId(petRow.getModelId())
                    .setRewardPetName(petRow.getName());
        }
        if (elig != null) {
            resp.setHasPermanentEligibility(true)
                    .setEligibilitySourceInstanceId(elig.getSourceActivityInstanceId())
                    .setEligibilityEarnedAtMs(elig.getEarnedAtMs());
        }
        if (receipt != null) {
            resp.setHasPermanentEligibility(true)
                    .setRewardClaimed(true)
                    .setRewardClaimDurable(isClaimDurable(player, receipt))
                    .setClaimedPetId(receipt.getPetId())
                    .setClaimedAtMs(receipt.getClaimedAtMs())
                    .setClaimRequestId(receipt.getRequestId());
        }
        if (state.hasPendingLaunch() && !state.getPendingLaunch().getRequestId().isEmpty()) {
            resp.setPendingLaunch(toPendingLaunchInfo(state.getPendingLaunch()));
        }

        // 1. 权威裁定是否可发起挑战
        boolean writable = !player.frozen();
        boolean inBattle = player.inBattle();
        boolean sceneBusy = player.switchPhase() != SwitchPhase.NONE;
        if (!cfg.valid()) {
            resp.setCanStartChallenge(false)
                    .setStartBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_CONFIG_INVALID)
                    .setStartBlockReason("瑞兽迎春活动配置不完整，暂未开放");
        } else if (phase != FestivalBeastPhase.FESTIVAL_BEAST_PHASE_OPEN) {
            resp.setCanStartChallenge(false)
                    .setStartBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_NOT_OPEN)
                    .setStartBlockReason(phase == FestivalBeastPhase.FESTIVAL_BEAST_PHASE_UPCOMING
                            ? "活动尚未开始，请等待开放时间"
                            : "本期活动挑战已结束");
        } else if (!writable || sceneBusy) {
            resp.setCanStartChallenge(false)
                    .setStartBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_FROZEN)
                    .setStartBlockReason("当前角色状态暂时不能发起挑战，请稍后重试");
        } else if (inBattle) {
            resp.setCanStartChallenge(false)
                    .setStartBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_IN_BATTLE)
                    .setStartBlockReason("当前正在战斗中，请在结算完成后再试");
        } else if (hasActivePendingLaunch(state)) {
            resp.setCanStartChallenge(false)
                    .setStartBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_LAUNCH_IN_FLIGHT)
                    .setStartBlockReason("正在进入瑞兽迎春战斗或结果确认中，请稍候");
        } else {
            PetCheckResult petCheck = validateActivePetForBattle(player, 0L);
            if (petCheck.tipId() != OK) {
                resp.setCanStartChallenge(false);
                if (petCheck.tipId() == PET_DEAD) {
                    resp.setStartBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_PET_DEAD)
                            .setStartBlockReason("当前出战宝宝已倒地，请先恢复气血或更换出战宝宝");
                } else {
                    resp.setStartBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_PET_REQUIRED)
                            .setStartBlockReason("请先出战一只合法存活宝宝后再挑战");
                }
            } else {
                resp.setCanStartChallenge(true)
                        .setStartBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_NONE);
            }
        }

        // 2. 权威裁定是否可领取神兽（永久资格优先，不受活动结束或历史期限制）
        if (receipt != null) {
            resp.setCanClaimReward(false);
            if (resp.getRewardClaimDurable()) {
                resp.setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_ALREADY_CLAIMED)
                        .setClaimBlockReason("已领取该专属神兽，同一角色终身限领一只");
            } else {
                resp.setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_CLAIM_CONFIRMING)
                        .setClaimBlockReason("正在确认神兽发放结果，请稍候或重试原请求");
            }
        } else if (elig == null) {
            resp.setCanClaimReward(false)
                    .setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_NOT_ELIGIBLE)
                    .setClaimBlockReason("本期累计获胜 " + Integer.toUnsignedString(row.getRequiredWins())
                            + " 次后可免费领取" + row.getBossName() + "（当前 "
                            + Integer.toUnsignedString(resp.getCurrentWins()) + "/"
                            + Integer.toUnsignedString(row.getRequiredWins()) + "）");
        } else if (!FestivalBeastRules.isValidRewardPetRow(tables.pet(effectiveRewardPetId))) {
            resp.setCanClaimReward(false)
                    .setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_CONFIG_INVALID)
                    .setClaimBlockReason("正式神兽配置或资源引用不完整，暂不可发放");
        } else if (!writable) {
            resp.setCanClaimReward(false)
                    .setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_FROZEN)
                    .setClaimBlockReason("当前角色状态暂时不能领取，请稍后重试");
        } else if (inBattle) {
            resp.setCanClaimReward(false)
                    .setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_IN_BATTLE)
                    .setClaimBlockReason("战斗中暂不能领取神兽，请在结算完成后重试");
        } else if (!pets.hasExclusiveGiftSlot(player.pets(), effectiveRewardPetId)) {
            resp.setCanClaimReward(false)
                    .setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_EXCLUSIVE_SLOT_OCCUPIED)
                    .setClaimBlockReason("已拥有该专属神兽，无需重复领取");
        } else {
            resp.setCanClaimReward(true)
                    .setClaimBlockCode(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_NONE);
        }
        return resp.build();
    }

    // ================================================================== 477 发起单人挑战

    public StartFestivalBeastChallengeResponse startChallenge(SceneWorld world, ScenePlayer player,
                                                              StartFestivalBeastChallengeRequest request, long nowMs) {
        FestivalBeastRules.Uuid128 uuid = FestivalBeastRules.parseUuid128(request.getClientRequestId());
        if (uuid == null || request.getActivityInstanceId() == 0 || request.getConfigRevision() == 0) {
            return StartFestivalBeastChallengeResponse.newBuilder()
                    .setErrorMessage(tip(INVALID_PARAMETER))
                    .build();
        }
        String reqId = request.getClientRequestId();
        PlayerFestivalBeastState state = player.festivalBeast().state();
        StartFestivalBeastChallengeResponse.Builder resp = StartFestivalBeastChallengeResponse.newBuilder()
                .setClientRequestId(reqId);

        // 1. 同请求重放：结果未知/在途时复用原请求重试，绝不另起新局。
        if (state.hasPendingLaunch() && reqId.equals(state.getPendingLaunch().getRequestId())) {
            FestivalBeastPendingLaunchState pending = state.getPendingLaunch();
            if (pending.getActivityInstanceId() != request.getActivityInstanceId()
                    || pending.getConfigRevision() != request.getConfigRevision()) {
                return resp.setErrorMessage(tip(REQUEST_CONFLICT)).build();
            }
            resp.setReplayed(true)
                    .setLockedPetId(pending.getLockedPetId())
                    .setBattleId(pending.getBattleId())
                    .setMatchedTierId(pending.getMatchedTierId())
                    .setAdmittedAtMs(pending.getAdmittedAtMs())
                    .setRejectReason(pending.getRejectReason())
                    .setLaunchState(toLaunchState(pending.getLaunchState()));

            boolean shouldResend = pending.getLaunchState() == FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_UNKNOWN_VALUE
                    || (pending.getLaunchState() == FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_LAUNCHING_VALUE
                    && Long.compareUnsigned(nowMs, pending.getRequestedAtMs() + FestivalBeastRules.LAUNCH_RPC_DEADLINE_MS) > 0);
            if (!shouldResend) {
                int tipId = pending.getLaunchState() == FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_REJECTED_VALUE
                        && pending.getRejectTipId() != 0
                        ? pending.getRejectTipId()
                        : OK;
                return resp.setErrorMessage(tip(tipId)).build();
            }

            ActiveConfigView cfg = resolveConfig(pending.getActivityInstanceId(), state);
            FestivalBeastTierTable tier = FestivalBeastRules.findTierForLevel(tables.tiers(), pending.getLockedOwnerLevel());
            if (!cfg.found() || !cfg.valid() || tier == null) {
                return resp.setErrorMessage(tip(SERVICE_UNAVAILABLE)).build();
            }
            StartActivityBattleRequest matchReq = buildMatchRequest(
                    player.playerId(), uuid, cfg.row(), tier,
                    pending.getLockedPetId(), pending.getLockedOwnerLevel(), pending.getRequestId());
            if (sendLaunch(world, player, reqId, matchReq)) {
                PlayerFestivalBeastState cur = player.festivalBeast().state();
                if (cur.hasPendingLaunch() && reqId.equals(cur.getPendingLaunch().getRequestId())
                        && cur.getPendingLaunch().getLaunchState() == FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_UNKNOWN_VALUE) {
                    player.festivalBeast().replace(cur.toBuilder()
                            .setPendingLaunch(cur.getPendingLaunch().toBuilder()
                                    .setLaunchState(FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_LAUNCHING_VALUE)
                                    .setRequestedAtMs(nowMs))
                            .build());
                }
                return fillLatestLaunchResponse(player, reqId, resp, true).build();
            }
            return resp.setErrorMessage(tip(OK)).build();
        }

        // 2. 已有不同请求的在途/未知开局：结果未知时禁止重复开局。
        if (hasActivePendingLaunch(state)) {
            return resp.setErrorMessage(tip(LAUNCH_IN_FLIGHT)).build();
        }

        // 3. 校验活动配置、时间窗与玩家可写/非战斗状态。
        ActiveConfigView cfg = resolveConfig(request.getActivityInstanceId(), state);
        if (!cfg.found() || !cfg.valid()) {
            return resp.setErrorMessage(tip(CONFIG_INVALID)).build();
        }
        if (cfg.row().getConfigRevision() != request.getConfigRevision()) {
            return resp.setErrorMessage(tip(CONFIG_REVISION_MISMATCH)).build();
        }
        FestivalBeastPhase phase = FestivalBeastRules.resolvePhase(
                cfg.row().getEnabled(), cfg.row().getEffectiveStartAtMs(), cfg.row().getEffectiveEndAtMs(),
                cfg.row().getClaimShowcaseDays(), nowMs);
        if (phase != FestivalBeastPhase.FESTIVAL_BEAST_PHASE_OPEN) {
            return resp.setErrorMessage(tip(NOT_OPEN)).build();
        }
        if (player.frozen() || player.switchPhase() != SwitchPhase.NONE) {
            return resp.setErrorMessage(tip(FROZEN)).build();
        }
        if (player.inBattle()) {
            return resp.setErrorMessage(tip(IN_BATTLE)).build();
        }
        PetCheckResult petCheck = validateActivePetForBattle(player, 0L);
        if (petCheck.tipId() != OK) {
            return resp.setErrorMessage(tip(petCheck.tipId())).build();
        }
        int ownerLevel = cappedOwnerLevel(player);
        FestivalBeastTierTable tier = FestivalBeastRules.findTierForLevel(tables.tiers(), ownerLevel);
        if (tier == null) {
            return resp.setErrorMessage(tip(CONFIG_INVALID)).build();
        }

        // 4. 先持久化在途开局意图，再发 Match 准入。
        FestivalBeastPendingLaunchState pending = FestivalBeastPendingLaunchState.newBuilder()
                .setRequestId(reqId)
                .setActivityInstanceId(cfg.row().getId())
                .setConfigRevision(cfg.row().getConfigRevision())
                .setRewardIdentity(cfg.row().getRewardIdentity())
                .setLockedPetId(petCheck.petId())
                .setLockedOwnerLevel(ownerLevel)
                .setMatchedTierId(tier.getId())
                .setBattleConfigId(cfg.row().getBattleConfigId())
                .setLaunchState(FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_LAUNCHING_VALUE)
                .setRequestedAtMs(nowMs)
                .build();
        player.festivalBeast().replace(state.toBuilder()
                .setPendingLaunch(pending)
                .setStateRevision(state.getStateRevision() + 1L)
                .build());
        requestSave(world, player);

        StartActivityBattleRequest matchReq = buildMatchRequest(
                player.playerId(), uuid, cfg.row(), tier, petCheck.petId(), ownerLevel, reqId);
        if (!sendLaunch(world, player, reqId, matchReq)) {
            PlayerFestivalBeastState after = player.festivalBeast().state();
            player.festivalBeast().replace(after.toBuilder()
                    .clearPendingLaunch()
                    .setStateRevision(after.getStateRevision() + 1L)
                    .build());
            return resp.setErrorMessage(tip(SERVICE_UNAVAILABLE))
                    .setLaunchState(FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_REJECTED)
                    .setRejectReason("活动开局服务暂不可用，请稍后重试")
                    .build();
        }
        return fillLatestLaunchResponse(player, reqId, resp, false).build();
    }

    // ================================================================== 478 领取永久神兽奖励

    public ClaimFestivalBeastRewardResponse claimReward(SceneWorld world, ScenePlayer player,
                                                        ClaimFestivalBeastRewardRequest request, long nowMs) {
        FestivalBeastRules.Uuid128 uuid = FestivalBeastRules.parseUuid128(request.getClientRequestId());
        String rewardId = request.getRewardIdentity();
        if (uuid == null || rewardId.isEmpty() || rewardId.length() > 64) {
            return ClaimFestivalBeastRewardResponse.newBuilder()
                    .setErrorMessage(tip(INVALID_PARAMETER))
                    .setOutcome(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_REJECTED)
                    .build();
        }
        String reqId = request.getClientRequestId();
        PlayerFestivalBeastState state = player.festivalBeast().state();
        ClaimFestivalBeastRewardResponse.Builder resp = ClaimFestivalBeastRewardResponse.newBuilder()
                .setClientRequestId(reqId)
                .setRewardIdentity(rewardId);

        // 1. 同请求 UUID 重放优先（历史期或改表后仍可确认原结果）。
        FestivalBeastClaimReceiptState sameReq = findClaimByRequest(state, reqId);
        if (sameReq != null) {
            if (!sameReq.getRewardIdentity().equals(rewardId)) {
                return resp.setErrorMessage(tip(REQUEST_CONFLICT))
                        .setOutcome(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_REJECTED)
                        .build();
            }
            boolean durable = isClaimDurable(player, sameReq);
            if (!durable) {
                requestSave(world, player);
            }
            return resp.setErrorMessage(tip(OK))
                    .setOutcome(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_APPLIED)
                    .setReplayed(true)
                    .setDurable(durable)
                    .setRewardDefinitionVersion(sameReq.getRewardDefinitionVersion())
                    .setRewardPetTableId(sameReq.getRewardPetTableId())
                    .setPetId(sameReq.getPetId())
                    .setClaimedAtMs(sameReq.getClaimedAtMs())
                    .build();
        }

        // 2. 不同请求并发领取同一种 reward_identity：收敛返回已领取的同一只 pet_id，绝不铸第二只。
        FestivalBeastClaimReceiptState existing = findClaimByIdentity(state, rewardId);
        if (existing != null) {
            boolean durable = isClaimDurable(player, existing);
            if (!durable) {
                requestSave(world, player);
            }
            return resp.setErrorMessage(tip(OK))
                    .setOutcome(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_ALREADY_CLAIMED)
                    .setReplayed(false)
                    .setDurable(durable)
                    .setClientRequestId(existing.getRequestId())
                    .setRewardDefinitionVersion(existing.getRewardDefinitionVersion())
                    .setRewardPetTableId(existing.getRewardPetTableId())
                    .setPetId(existing.getPetId())
                    .setClaimedAtMs(existing.getClaimedAtMs())
                    .build();
        }

        // 3. 校验永久资格（读取达成时冻结的奖励定义与版本，换表不影响已赚取权益）。
        FestivalBeastPermanentEligibilityState elig = findEligibility(state, rewardId);
        if (elig == null) {
            return resp.setErrorMessage(tip(NOT_ELIGIBLE))
                    .setOutcome(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_REJECTED)
                    .build();
        }
        if (player.frozen()) {
            return resp.setErrorMessage(tip(FROZEN))
                    .setOutcome(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_REJECTED)
                    .build();
        }
        if (player.inBattle()) {
            return resp.setErrorMessage(tip(IN_BATTLE))
                    .setOutcome(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_REJECTED)
                    .build();
        }
        PetTable petRow = tables.pet(elig.getRewardPetTableId());
        if (!FestivalBeastRules.isValidRewardPetRow(petRow)) {
            return resp.setErrorMessage(tip(CONFIG_INVALID))
                    .setOutcome(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_REJECTED)
                    .build();
        }
        if (!pets.hasExclusiveGiftSlot(player.pets(), elig.getRewardPetTableId())) {
            return resp.setErrorMessage(tip(EXCLUSIVE_SLOT_OCCUPIED))
                    .setOutcome(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_REJECTED)
                    .build();
        }

        // 4. 先预备完整宠物实例并分配永久 pet_id；号源失败直接返回，绝不消耗资格或写入回执。
        PetService.PreparedActivityPet prepared = pets.prepareExclusiveActivityPet(player, elig.getRewardPetTableId());
        if (prepared.tip() != OK) {
            FestivalBeastClaimOutcome outcome = prepared.tip() == pet_error.kPetIdGenerateFailed_VALUE
                    ? FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_UNKNOWN
                    : FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_REJECTED;
            return resp.setErrorMessage(tip(prepared.tip()))
                    .setOutcome(outcome)
                    .build();
        }

        // 5. 同帧原子提交：宠物实例 + 终身领取回执一起写入玩家状态并触发立即资产存盘。
        Pet granted = prepared.pet();
        int frozenVersion = elig.getRewardDefinitionVersion();
        int frozenPetTableId = elig.getRewardPetTableId();
        pets.commitPreparedActivityPet(player, granted);
        FestivalBeastClaimReceiptState receipt = FestivalBeastClaimReceiptState.newBuilder()
                .setRewardIdentity(rewardId)
                .setRequestId(reqId)
                .setRewardDefinitionVersion(frozenVersion)
                .setRewardPetTableId(frozenPetTableId)
                .setPetId(granted.petId())
                .setClaimedAtMs(nowMs)
                .build();
        player.festivalBeast().replace(state.toBuilder()
                .addClaimReceipts(receipt)
                .setStateRevision(state.getStateRevision() + 1L)
                .build());
        requestSave(world, player);

        boolean durable = isClaimDurable(player, receipt);
        if (durable && world != null && notifyPetListChanged > 0) {
            world.sendTo(player, push(notifyPetListChanged,
                    PetListChangedS2C.newBuilder().setPets(pets.buildList(player)).build()));
        }
        log.info("[FestivalBeast] 领取专属神兽 player={} reward_identity={} pet_id={} durable={}",
                Long.toUnsignedString(player.playerId()), rewardId, Long.toUnsignedString(granted.petId()), durable);
        return resp.setErrorMessage(tip(OK))
                .setOutcome(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_APPLIED)
                .setReplayed(false)
                .setDurable(durable)
                .setRewardDefinitionVersion(frozenVersion)
                .setRewardPetTableId(frozenPetTableId)
                .setPetId(granted.petId())
                .setClaimedAtMs(nowMs)
                .build();
    }

    // ================================================================== 备战锁宠复核、取消清理与结算

    /** 备战生成快照前二次复核出战宝宝身份（防预检后换宠、收回或倒地开局）。 */
    public int verifyPrepareBattlePet(SceneWorld world, ScenePlayer player, FestivalBeastBattleContext ctx) {
        if (ctx == null || ctx.getLockedPetId() == 0L) {
            return CONFIG_INVALID;
        }
        PetCheckResult check = validateActivePetForBattle(player, ctx.getLockedPetId());
        if (check.tipId() != OK) {
            PlayerFestivalBeastState state = player.festivalBeast().state();
            if (state.hasPendingLaunch()
                    && !ctx.getClientRequestId().isEmpty()
                    && state.getPendingLaunch().getRequestId().equals(ctx.getClientRequestId())) {
                player.festivalBeast().replace(state.toBuilder()
                        .setPendingLaunch(state.getPendingLaunch().toBuilder()
                                .setLaunchState(FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_REJECTED_VALUE)
                                .setBattleId(0L)
                                .setRejectTipId(check.tipId())
                                .setRejectReason("出战宝宝状态已变化，本次开局已取消"))
                        .setStateRevision(state.getStateRevision() + 1L)
                        .build());
                requestSave(world, player);
            }
            return check.tipId();
        }
        return OK;
    }

    /** 备战取消或战斗判废时清理匹配的在途开局意图。 */
    public void onBattlePrepareCancelledOrVoid(SceneWorld world, ScenePlayer player, long battleId) {
        if (player == null || battleId == 0L) {
            return;
        }
        PlayerFestivalBeastState state = player.festivalBeast().state();
        if (!state.hasPendingLaunch() || state.getPendingLaunch().getBattleId() != battleId) {
            return;
        }
        player.festivalBeast().replace(state.toBuilder()
                .clearPendingLaunch()
                .setStateRevision(state.getStateRevision() + 1L)
                .build());
        requestSave(world, player);
    }

    /** 应用单局瑞兽迎春战斗结算（胜场推进、满 3 胜生成永久待领资格并冻结定义，负/逃/废零清零）。 */
    public SettlementDelta applyBattleSettlement(ScenePlayer player, BattleSettlementData settlement, long nowMs) {
        if (player == null || settlement == null
                || !settlement.hasFestivalBeast()
                || settlement.getFestivalBeast().getActivityInstanceId() == 0) {
            return SettlementDelta.NONE;
        }
        FestivalBeastBattleContext fb = settlement.getFestivalBeast();
        if (fb.getRewardIdentity().isEmpty() || fb.getRequiredWins() == 0) {
            return SettlementDelta.NONE;
        }
        PlayerFestivalBeastState.Builder candidate = player.festivalBeast().state().toBuilder();
        boolean changed = false;

        // 结算到达时清理匹配的在途开局状态。
        if (candidate.hasPendingLaunch()
                && (candidate.getPendingLaunch().getBattleId() == settlement.getBattleId()
                || (!fb.getClientRequestId().isEmpty()
                && candidate.getPendingLaunch().getRequestId().equals(fb.getClientRequestId())))) {
            candidate.clearPendingLaunch();
            changed = true;
        }

        int periodIdx = findPeriodProgressIndex(candidate, fb.getActivityInstanceId());
        int winsBefore = periodIdx >= 0
                ? Math.min(candidate.getPeriodProgress(periodIdx).getWins(), fb.getRequiredWins())
                : 0;
        int winsAfter = winsBefore;
        boolean newlyEligible = false;

        boolean playerWon = !settlement.getFled()
                && ((settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN && settlement.getPlayerTeamIndex() == 0)
                || (settlement.getOutcome() == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN && settlement.getPlayerTeamIndex() == 1));
        if (playerWon) {
            winsAfter = Math.min(winsBefore + 1, fb.getRequiredWins());
            if (periodIdx < 0) {
                candidate.addPeriodProgress(FestivalBeastPeriodProgressState.newBuilder()
                        .setActivityInstanceId(fb.getActivityInstanceId())
                        .setRewardIdentity(fb.getRewardIdentity())
                        .setWins(winsAfter)
                        .setRequiredWins(fb.getRequiredWins())
                        .setLastWinAtMs(nowMs));
                changed = true;
            } else if (winsAfter > winsBefore) {
                FestivalBeastPeriodProgressState.Builder p = candidate.getPeriodProgressBuilder(periodIdx);
                p.setWins(winsAfter)
                        .setRequiredWins(fb.getRequiredWins())
                        .setLastWinAtMs(nowMs)
                        .setRewardIdentity(fb.getRewardIdentity());
                changed = true;
            }
            if (Integer.compareUnsigned(winsAfter, fb.getRequiredWins()) >= 0
                    && findEligibility(candidate.build(), fb.getRewardIdentity()) == null) {
                candidate.addEligibilities(FestivalBeastPermanentEligibilityState.newBuilder()
                        .setRewardIdentity(fb.getRewardIdentity())
                        .setRewardDefinitionVersion(fb.getRewardDefinitionVersion())
                        .setRewardPetTableId(fb.getRewardPetTableId())
                        .setSourceActivityInstanceId(fb.getActivityInstanceId())
                        .setEarnedAtMs(nowMs)
                        .setEarnedBattleId(settlement.getBattleId()));
                newlyEligible = true;
                changed = true;
            }
        }

        if (changed) {
            candidate.setStateRevision(candidate.getStateRevision() + 1L);
            player.festivalBeast().replace(candidate.build());
        }
        return new SettlementDelta(
                true, fb.getActivityInstanceId(), fb.getRewardIdentity(),
                winsBefore, winsAfter, fb.getRequiredWins(), newlyEligible, changed);
    }

    // ================================================================== 内部逻辑

    private boolean sendLaunch(SceneWorld world, ScenePlayer player, String requestId, StartActivityBattleRequest matchReq) {
        LaunchSender sender = this.launchSender;
        if (sender == null) {
            log.warn("[FestivalBeast] 未装配 MatchInternalService 开局发送器 player={}",
                    Long.toUnsignedString(player.playerId()));
            return false;
        }
        long playerId = player.playerId();
        return sender.send(matchReq, (ok, reply) -> handleLaunchReply(world, player, playerId, requestId, ok, reply));
    }

    private void handleLaunchReply(SceneWorld world, ScenePlayer originPlayer, long playerId,
                                   String requestId, boolean callOk, StartActivityBattleResponse reply) {
        ScenePlayer player = world != null ? world.playerById(playerId) : originPlayer;
        if (player == null) {
            return;
        }
        PlayerFestivalBeastState state = player.festivalBeast().state();
        if (!state.hasPendingLaunch() || !requestId.equals(state.getPendingLaunch().getRequestId())) {
            return;
        }
        long nowMs = clock.epochMillis();
        FestivalBeastPendingLaunchState.Builder pending = state.getPendingLaunch().toBuilder();
        if (!callOk || reply == null) {
            pending.setLaunchState(FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_UNKNOWN_VALUE);
        } else if (reply.getReject() == ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE && reply.getBattleId() != 0L) {
            pending.setLaunchState(FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_ADMITTED_VALUE)
                    .setBattleId(reply.getBattleId())
                    .setAdmittedAtMs(reply.getAdmittedAtMs() != 0L ? reply.getAdmittedAtMs() : nowMs)
                    .clearRejectTipId()
                    .clearRejectReason();
        } else {
            MatchRejectMapping mapped = mapMatchReject(reply.getReject());
            pending.setLaunchState(FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_REJECTED_VALUE)
                    .setBattleId(0L)
                    .setRejectTipId(mapped.tipId())
                    .setRejectReason(mapped.reason());
        }
        player.festivalBeast().replace(state.toBuilder()
                .setPendingLaunch(pending)
                .setStateRevision(state.getStateRevision() + 1L)
                .build());
        requestSave(world, player);
    }

    private StartFestivalBeastChallengeResponse.Builder fillLatestLaunchResponse(
            ScenePlayer player, String requestId, StartFestivalBeastChallengeResponse.Builder resp, boolean replayed) {
        PlayerFestivalBeastState cur = player.festivalBeast().state();
        if (cur.hasPendingLaunch() && requestId.equals(cur.getPendingLaunch().getRequestId())) {
            FestivalBeastPendingLaunchState latest = cur.getPendingLaunch();
            resp.setReplayed(replayed)
                    .setLaunchState(toLaunchState(latest.getLaunchState()))
                    .setLockedPetId(latest.getLockedPetId())
                    .setBattleId(latest.getBattleId())
                    .setMatchedTierId(latest.getMatchedTierId())
                    .setAdmittedAtMs(latest.getAdmittedAtMs())
                    .setRejectReason(latest.getRejectReason());
            int tipId = latest.getLaunchState() == FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_REJECTED_VALUE
                    && latest.getRejectTipId() != 0
                    ? latest.getRejectTipId()
                    : OK;
            resp.setErrorMessage(tip(tipId));
        } else {
            resp.setErrorMessage(tip(OK))
                    .setLaunchState(FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_LAUNCHING);
        }
        return resp;
    }

    private static StartActivityBattleRequest buildMatchRequest(long playerId, FestivalBeastRules.Uuid128 uuid,
                                                                FestivalBeastActivityTable row,
                                                                FestivalBeastTierTable tier,
                                                                long lockedPetId, int lockedOwnerLevel,
                                                                String clientRequestId) {
        FestivalBeastBattleContext fb = FestivalBeastBattleContext.newBuilder()
                .setActivityInstanceId(row.getId())
                .setConfigRevision(row.getConfigRevision())
                .setRewardIdentity(row.getRewardIdentity())
                .setRewardDefinitionVersion(row.getRewardDefinitionVersion())
                .setRewardPetTableId(row.getRewardPetTableId())
                .setRequiredWins(row.getRequiredWins())
                .setLockedPetId(lockedPetId)
                .setLockedOwnerLevel(lockedOwnerLevel)
                .setTierId(tier.getId())
                .setRoundLimit(row.getRoundLimit())
                .setCycleRounds(row.getCycleRounds())
                .setFirstHeavyRound(row.getFirstHeavyRound())
                .setTelegraphLeadRounds(row.getTelegraphLeadRounds())
                .setHeavyDamagePercent(row.getHeavyDamagePercent())
                .setBossName(row.getBossName())
                .setBossHealth(tier.getBossHealth())
                .setBossPhysicalAttack(tier.getBossPhysicalAttack())
                .setBossDefense(tier.getBossDefense())
                .setBossResistance(tier.getBossResistance())
                .setBossSpeed(tier.getBossSpeed())
                .setEffectiveStartAtMs(row.getEffectiveStartAtMs())
                .setEffectiveEndAtMs(row.getEffectiveEndAtMs())
                .setClientRequestId(clientRequestId)
                .build();
        return StartActivityBattleRequest.newBuilder()
                .setBattleConfigId(row.getBattleConfigId())
                .addMemberPlayerIds(playerId)
                .setRequestHigh(uuid.high())
                .setRequestLow(uuid.low())
                .setActivityContext(BattleActivityContext.newBuilder()
                        .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_FESTIVAL_BEAST)
                        .setInitiatorPlayerId(playerId)
                        .setFestivalBeast(fb))
                .build();
    }

    private PetCheckResult validateActivePetForBattle(ScenePlayer player, long requiredLockedPetId) {
        PlayerPets playerPets = player.pets();
        long activeId = playerPets.activePetId();
        if (activeId == 0L) {
            return new PetCheckResult(requiredLockedPetId != 0L ? PET_CHANGED : PET_REQUIRED, 0L);
        }
        if (requiredLockedPetId != 0L && activeId != requiredLockedPetId) {
            return new PetCheckResult(PET_CHANGED, 0L);
        }
        Pet activePet = playerPets.find(activeId);
        if (activePet == null) {
            return new PetCheckResult(requiredLockedPetId != 0L ? PET_CHANGED : PET_REQUIRED, 0L);
        }
        PetTable row = tables.pet(activePet.petTableId());
        if (row == null) {
            return new PetCheckResult(CONFIG_INVALID, 0L);
        }
        long ownerLevel = Integer.toUnsignedLong(cappedOwnerLevel(player));
        long rawPetLevel = Math.max(1L, activePet.level());
        long petEffectiveLevel = row.getLevelCap() > 0
                ? Math.min(rawPetLevel, Integer.toUnsignedLong(row.getLevelCap()))
                : rawPetLevel;
        if (ownerLevel < petEffectiveLevel) {
            return new PetCheckResult(requiredLockedPetId != 0L ? PET_CHANGED : pet_error.kPetOwnerLevelNotEnough_VALUE, 0L);
        }
        if (activePet.health() <= 0L) {
            return new PetCheckResult(PET_DEAD, 0L);
        }
        return new PetCheckResult(OK, activeId);
    }

    private ActiveConfigView resolveConfig(int requestedInstanceId, PlayerFestivalBeastState state) {
        int enabledCount = 0;
        FestivalBeastActivityTable enabledRow = null;
        for (FestivalBeastActivityTable item : tables.activities()) {
            if (item.getEnabled()) {
                enabledCount++;
                if (enabledRow == null) {
                    enabledRow = item;
                }
            }
        }
        boolean multipleEnabled = enabledCount > 1;
        FestivalBeastActivityTable chosen = null;
        if (requestedInstanceId != 0) {
            chosen = tables.activity(requestedInstanceId);
        } else if (enabledRow != null) {
            chosen = enabledRow;
        } else if (state != null) {
            FestivalBeastPermanentEligibilityState unclaimed = findAnyUnclaimedEligibility(state);
            if (unclaimed != null) {
                chosen = tables.activity(unclaimed.getSourceActivityInstanceId());
            }
            if (chosen == null && state.getEligibilitiesCount() > 0) {
                chosen = tables.activity(state.getEligibilities(state.getEligibilitiesCount() - 1).getSourceActivityInstanceId());
            }
            if (chosen == null && !tables.activities().isEmpty()) {
                chosen = tables.activities().getLast();
            }
        } else if (!tables.activities().isEmpty()) {
            chosen = tables.activities().getLast();
        }
        if (chosen == null) {
            return new ActiveConfigView(false, multipleEnabled, false, null);
        }
        boolean valid = !multipleEnabled && FestivalBeastRules.validateActivityRow(
                chosen,
                tables.dungeon(chosen.getBattleConfigId()),
                tables.pet(chosen.getRewardPetTableId()),
                tables.tiers());
        return new ActiveConfigView(true, multipleEnabled, valid, chosen);
    }

    private static boolean isClaimDurable(ScenePlayer player, FestivalBeastClaimReceiptState receipt) {
        if (player == null || player.frozen() || receipt.getPetId() == 0L || receipt.getRewardIdentity().isEmpty()) {
            return false;
        }
        PlayerState saved = player.persistedState();
        if (saved == null || !saved.hasFestivalBeast() || !saved.hasPets()) {
            return false;
        }
        boolean hasReceipt = false;
        for (FestivalBeastClaimReceiptState savedReceipt : saved.getFestivalBeast().getClaimReceiptsList()) {
            if (savedReceipt.getRewardIdentity().equals(receipt.getRewardIdentity())
                    && savedReceipt.getRequestId().equals(receipt.getRequestId())
                    && savedReceipt.getPetId() == receipt.getPetId()) {
                hasReceipt = true;
                break;
            }
        }
        if (!hasReceipt) {
            return false;
        }
        for (PetEntry savedPet : saved.getPets().getPetsList()) {
            if (savedPet.getPetId() == receipt.getPetId()
                    && savedPet.getPetTableId() == receipt.getRewardPetTableId()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasActivePendingLaunch(PlayerFestivalBeastState state) {
        if (!state.hasPendingLaunch() || state.getPendingLaunch().getRequestId().isEmpty()) {
            return false;
        }
        int st = state.getPendingLaunch().getLaunchState();
        return st == FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_LAUNCHING_VALUE
                || st == FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_ADMITTED_VALUE
                || st == FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_UNKNOWN_VALUE;
    }

    private static FestivalBeastPendingLaunchInfo toPendingLaunchInfo(FestivalBeastPendingLaunchState src) {
        return FestivalBeastPendingLaunchInfo.newBuilder()
                .setClientRequestId(src.getRequestId())
                .setActivityInstanceId(src.getActivityInstanceId())
                .setConfigRevision(src.getConfigRevision())
                .setLockedPetId(src.getLockedPetId())
                .setMatchedTierId(src.getMatchedTierId())
                .setLaunchState(toLaunchState(src.getLaunchState()))
                .setBattleId(src.getBattleId())
                .setRequestedAtMs(src.getRequestedAtMs())
                .setAdmittedAtMs(src.getAdmittedAtMs())
                .setRejectTipId(src.getRejectTipId())
                .setRejectReason(src.getRejectReason())
                .build();
    }

    private static FestivalBeastLaunchState toLaunchState(int value) {
        FestivalBeastLaunchState st = FestivalBeastLaunchState.forNumber(value);
        return st == null ? FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_UNSPECIFIED : st;
    }

    private static MatchRejectMapping mapMatchReject(ActivityBattleReject reject) {
        return switch (reject) {
            case ACTIVITY_BATTLE_REJECT_OUTSIDE_WINDOW ->
                    new MatchRejectMapping(NOT_OPEN, "瑞兽迎春活动当前未开放挑战");
            case ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE ->
                    new MatchRejectMapping(IN_BATTLE, "当前已在战斗中，请在结算后重试");
            case ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY ->
                    new MatchRejectMapping(LAUNCH_IN_FLIGHT, "当前存在未完成的匹配或备战请求，请稍后再试");
            case ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT ->
                    new MatchRejectMapping(CONFIG_INVALID, "挑战参数校验未通过，请刷新活动界面后重试");
            default ->
                    new MatchRejectMapping(SERVICE_UNAVAILABLE, "活动开局服务繁忙，请稍后重试");
        };
    }

    private static int cappedOwnerLevel(ScenePlayer player) {
        long raw = Integer.toUnsignedLong(player.level());
        if (raw < FestivalBeastRules.REQUIRED_MIN_LEVEL) {
            return FestivalBeastRules.REQUIRED_MIN_LEVEL;
        }
        return (int) Math.min(raw, FestivalBeastRules.REQUIRED_MAX_LEVEL);
    }

    private static FestivalBeastPeriodProgressState findPeriodProgress(PlayerFestivalBeastState state, int instanceId) {
        for (FestivalBeastPeriodProgressState p : state.getPeriodProgressList()) {
            if (p.getActivityInstanceId() == instanceId) {
                return p;
            }
        }
        return null;
    }

    private static int findPeriodProgressIndex(PlayerFestivalBeastState.Builder state, int instanceId) {
        for (int i = 0; i < state.getPeriodProgressCount(); i++) {
            if (state.getPeriodProgress(i).getActivityInstanceId() == instanceId) {
                return i;
            }
        }
        return -1;
    }

    private static FestivalBeastPermanentEligibilityState findEligibility(PlayerFestivalBeastState state, String rewardIdentity) {
        for (FestivalBeastPermanentEligibilityState e : state.getEligibilitiesList()) {
            if (e.getRewardIdentity().equals(rewardIdentity)) {
                return e;
            }
        }
        return null;
    }

    private static FestivalBeastClaimReceiptState findClaimByIdentity(PlayerFestivalBeastState state, String rewardIdentity) {
        for (FestivalBeastClaimReceiptState r : state.getClaimReceiptsList()) {
            if (r.getRewardIdentity().equals(rewardIdentity)) {
                return r;
            }
        }
        return null;
    }

    private static FestivalBeastClaimReceiptState findClaimByRequest(PlayerFestivalBeastState state, String requestId) {
        for (FestivalBeastClaimReceiptState r : state.getClaimReceiptsList()) {
            if (r.getRequestId().equals(requestId)) {
                return r;
            }
        }
        return null;
    }

    private static FestivalBeastPermanentEligibilityState findAnyUnclaimedEligibility(PlayerFestivalBeastState state) {
        for (FestivalBeastPermanentEligibilityState e : state.getEligibilitiesList()) {
            if (findClaimByIdentity(state, e.getRewardIdentity()) == null) {
                return e;
            }
        }
        return null;
    }

    private static void requestSave(SceneWorld world, ScenePlayer player) {
        if (world != null && !player.frozen()) {
            world.requestKeyProgressSave(player);
        }
    }
}
