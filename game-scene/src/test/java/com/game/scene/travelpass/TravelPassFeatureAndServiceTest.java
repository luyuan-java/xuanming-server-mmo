package com.game.scene.travelpass;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.PlayerState;
import com.game.player.store.state.PlayerTravelPassState;
import com.game.player.store.state.TravelPassSeasonProgressState;
import com.game.proto.BattleSettlementData;
import com.game.proto.ClaimTravelPassRewardRequest;
import com.game.proto.ClaimTravelPassRewardResponse;
import com.game.proto.EquipTravelPassCosmeticRequest;
import com.game.proto.EquipTravelPassCosmeticResponse;
import com.game.proto.GetActivityListRequest;
import com.game.proto.GetActivityListResponse;
import com.game.proto.GetTravelPassStateRequest;
import com.game.proto.GetTravelPassStateResponse;
import com.game.proto.MessageContent;
import com.game.proto.PlayerActivityEntryKind;
import com.game.proto.PlayerActivityInfo;
import com.game.proto.UnlockTravelPassPremiumRequest;
import com.game.proto.UnlockTravelPassPremiumResponse;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.audit.AssetAudit.Reason;
import com.game.scene.battle.BattleFixture;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.PlayerTravelPass;
import com.game.scene.player.Wallet;
import com.game.scene.testing.Contracts;
import com.game.scene.world.ScenePlayer;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.UnknownFieldSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 青云游历手记与活动中心三卡 Java 服务端全链路验收（526–529、190 聚合、战后涨点、幂等、绑定隔离与永不过期）。
 */
class TravelPassFeatureAndServiceTest {

    private static final String SERVICE = TravelPassFeature.SERVICE;

    @Test
    void 战后涨点规则_防挂机与演武隔离_先玩后买与严禁绑石抵扣() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1001);
        long nowMs = f.clock.epochMillis();

        // 1. 常规 PVE 胜 +20；高难 PVE 胜 +30；PVP 胜 +30；合格 PVP 负 +15 -> 合计 95 点（达成第 1..3 档）
        assertThat(f.travelPass.creditBattlePoints(player, battle(1L, player.playerId(),
                MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 1, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, true, false, 3, 2, true, 0), nowMs)).isTrue();
        assertThat(f.travelPass.creditBattlePoints(player, battle(2L, player.playerId(),
                MatchMode.MATCH_MODE_PVE_TEAM_VALUE, 2, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, true, false, 4, 3, true, 0), nowMs)).isTrue();
        assertThat(f.travelPass.creditBattlePoints(player, battle(3L, player.playerId(),
                MatchMode.MATCH_MODE_1V1_VALUE, 1, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, true, false, 5, 4, true, 0), nowMs)).isTrue();
        assertThat(f.travelPass.creditBattlePoints(player, battle(4L, player.playerId(),
                MatchMode.MATCH_MODE_5V5_VALUE, 1, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, true, false, 3, 2, true, 0), nowMs)).isTrue();

        // 逃跑、挂机 PVP 负局、归元城演武木桩隔离局（boss_trial_kind=9）一律 0 点
        assertThat(f.travelPass.creditBattlePoints(player, battle(5L, player.playerId(),
                MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 1, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, true, true, 3, 2, true, 0), nowMs)).isFalse();
        assertThat(f.travelPass.creditBattlePoints(player, battle(6L, player.playerId(),
                MatchMode.MATCH_MODE_1V1_VALUE, 1, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, true, false, 2, 1, true, 0), nowMs)).isFalse();
        assertThat(f.travelPass.creditBattlePoints(player, battle(7L, player.playerId(),
                MatchMode.MATCH_MODE_PVE_SOLO_VALUE, 1, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, true, false, 3, 2, true, 9), nowMs)).isFalse();

        GetTravelPassStateResponse stateResp = call(f, player, "GetTravelPassState",
                GetTravelPassStateRequest.getDefaultInstance(), GetTravelPassStateResponse.parser());
        assertThat(stateResp.getErrorMessage().getId()).isZero();
        var snap = stateResp.getSnapshot();
        assertThat(snap.getAvailable()).isTrue();
        assertThat(snap.getPassId()).isEqualTo(1);
        assertThat(snap.getProgressPoints()).isEqualTo(95);
        assertThat(snap.getClaimableFreeCount()).isEqualTo(3);
        assertThat(snap.getClaimablePremiumCount()).isZero();
        assertThat(snap.getRetroactivePremiumCount()).isEqualTo(3);

        // 2. 只有绑定灵石 500、真实灵石 0 -> 解锁典藏必须被拒（绝不拿绑定灵石抵扣）
        assertThat(f.currency.add(player, Wallet.BOUND_DIAMOND, 500L, Reason.GM_GRANT).ok()).isTrue();
        UnlockTravelPassPremiumRequest unlockReq = UnlockTravelPassPremiumRequest.newBuilder()
                .setClientRequestId(8001L)
                .setPassId(snap.getPassId())
                .setExpectedCurrencyType(snap.getPremiumCurrencyType())
                .setExpectedPrice(snap.getPremiumPrice())
                .setExpectedConfigRevision(snap.getConfigRevision())
                .build();
        UnlockTravelPassPremiumResponse bindReject = call(f, player, "UnlockTravelPassPremium",
                unlockReq, UnlockTravelPassPremiumResponse.parser());
        assertThat(bindReject.getErrorMessage().getId()).isEqualTo(TravelPassService.CURRENCY_NOT_ENOUGH);
        assertThat(player.wallet().balance(Wallet.BOUND_DIAMOND)).isEqualTo(500L);

        // 3. 充值 200 真实灵石 -> 先玩后买解锁典藏，同键重放幂等，换参拒回
        assertThat(f.currency.add(player, Wallet.DIAMOND, 200L, Reason.GM_GRANT).ok()).isTrue();
        UnlockTravelPassPremiumResponse unlocked = call(f, player, "UnlockTravelPassPremium",
                unlockReq, UnlockTravelPassPremiumResponse.parser());
        assertThat(unlocked.getErrorMessage().getId()).isZero();
        assertThat(unlocked.getReplayed()).isFalse();
        assertThat(unlocked.getSnapshot().getPremiumUnlocked()).isTrue();
        assertThat(unlocked.getSnapshot().getClaimablePremiumCount()).isEqualTo(3);
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(200L - snap.getPremiumPrice());
        assertThat(player.wallet().balance(Wallet.BOUND_DIAMOND)).isEqualTo(500L);

        UnlockTravelPassPremiumResponse replayedUnlock = call(f, player, "UnlockTravelPassPremium",
                unlockReq, UnlockTravelPassPremiumResponse.parser());
        assertThat(replayedUnlock.getErrorMessage().getId()).isZero();
        assertThat(replayedUnlock.getReplayed()).isTrue();
        assertThat(player.wallet().balance(Wallet.DIAMOND)).isEqualTo(200L - snap.getPremiumPrice());

        UnlockTravelPassPremiumResponse mismatchUnlock = call(f, player, "UnlockTravelPassPremium",
                unlockReq.toBuilder().setExpectedPrice(snap.getPremiumPrice() + 1).build(),
                UnlockTravelPassPremiumResponse.parser());
        assertThat(mismatchUnlock.getErrorMessage().getId()).isEqualTo(TravelPassService.INVALID_PARAMETER);

        // 4. 一键领取全部已达成双轨奖励（6 档）：所有物品强制 bound=true，同键重放幂等
        ClaimTravelPassRewardRequest claimAll = ClaimTravelPassRewardRequest.newBuilder()
                .setClientRequestId(8002L)
                .setPassId(snap.getPassId())
                .setTierIndex(0)
                .build();
        ClaimTravelPassRewardResponse claimed = call(f, player, "ClaimTravelPassReward",
                claimAll, ClaimTravelPassRewardResponse.parser());
        assertThat(claimed.getErrorMessage().getId()).isZero();
        assertThat(claimed.getReplayed()).isFalse();
        assertThat(claimed.getClaimedCount()).isEqualTo(6);
        assertThat(claimed.getSnapshot().getClaimableFreeCount()).isZero();
        assertThat(claimed.getSnapshot().getClaimablePremiumCount()).isZero();
        assertThat(claimed.getSnapshot().getUnlockedCosmeticKeysList()).contains("frame_travel_qingyun_s1");
        assertThat(player.bags().bag(BagType.INVENTORY).items()).isNotEmpty().allMatch(BagItem::bound);

        ClaimTravelPassRewardResponse replayedClaim = call(f, player, "ClaimTravelPassReward",
                claimAll, ClaimTravelPassRewardResponse.parser());
        assertThat(replayedClaim.getErrorMessage().getId()).isZero();
        assertThat(replayedClaim.getReplayed()).isTrue();
        assertThat(replayedClaim.getClaimedCount()).isEqualTo(6);

        // 5. 佩戴已拥有的纪念头像框成功；佩戴未拥有的称谓被拒；卸下成功
        EquipTravelPassCosmeticResponse equipOk = call(f, player, "EquipTravelPassCosmetic",
                EquipTravelPassCosmeticRequest.newBuilder()
                        .setCosmeticSlot(PlayerTravelPass.COSMETIC_SLOT_FRAME)
                        .setCosmeticKey("frame_travel_qingyun_s1")
                        .build(),
                EquipTravelPassCosmeticResponse.parser());
        assertThat(equipOk.getErrorMessage().getId()).isZero();
        assertThat(equipOk.getSnapshot().getEquippedFrameKey()).isEqualTo("frame_travel_qingyun_s1");

        EquipTravelPassCosmeticResponse unowned = call(f, player, "EquipTravelPassCosmetic",
                EquipTravelPassCosmeticRequest.newBuilder()
                        .setCosmeticSlot(PlayerTravelPass.COSMETIC_SLOT_TITLE)
                        .setCosmeticKey("title_travel_qingyun_s1")
                        .build(),
                EquipTravelPassCosmeticResponse.parser());
        assertThat(unowned.getErrorMessage().getId()).isEqualTo(TravelPassService.COSMETIC_NOT_OWNED);

        EquipTravelPassCosmeticResponse unequipOk = call(f, player, "EquipTravelPassCosmetic",
                EquipTravelPassCosmeticRequest.newBuilder()
                        .setCosmeticSlot(PlayerTravelPass.COSMETIC_SLOT_FRAME)
                        .setCosmeticKey("")
                        .build(),
                EquipTravelPassCosmeticResponse.parser());
        assertThat(unequipOk.getErrorMessage().getId()).isZero();
        assertThat(unequipOk.getSnapshot().getEquippedFrameKey()).isEmpty();
    }

    @Test
    void 活动目录聚合三张卡片_种草卡恒无红点_未购典藏不造假红点() throws Exception {
        BattleFixture f = new BattleFixture();
        ScenePlayer player = f.enter(11, 1002);

        // 0 游历点：游历手记无红点，两张体验卡恒无红点
        GetActivityListResponse list0 = call(f, player, "GetActivityList",
                GetActivityListRequest.getDefaultInstance(), GetActivityListResponse.parser());
        assertThat(list0.getErrorMessage().getId()).isZero();
        PlayerActivityInfo tp0 = findActivity(list0.getActivitiesList(), TravelPassService.ACTIVITY_ID_TRAVEL_PASS);
        PlayerActivityInfo appCard = findActivity(list0.getActivitiesList(), TravelPassService.ACTIVITY_ID_APPEARANCE_SHOWCASE);
        PlayerActivityInfo shopCard = findActivity(list0.getActivitiesList(), TravelPassService.ACTIVITY_ID_SPIRIT_SHOP_SHOWCASE);
        assertThat(tp0.getEntryKind()).isEqualTo(PlayerActivityEntryKind.PLAYER_ACTIVITY_ENTRY_TRAVEL_PASS);
        assertThat(tp0.getHasClaimableReward()).isFalse();
        assertThat(appCard.getEntryKind()).isEqualTo(PlayerActivityEntryKind.PLAYER_ACTIVITY_ENTRY_APPEARANCE_SHOWCASE);
        assertThat(appCard.getIconKey()).isEqualTo("UI/Ugui/TravelPassV1/showcase_appearance_icon");
        assertThat(appCard.getHasClaimableReward()).isFalse();
        assertThat(shopCard.getEntryKind()).isEqualTo(PlayerActivityEntryKind.PLAYER_ACTIVITY_ENTRY_SPIRIT_SHOP_SHOWCASE);
        assertThat(shopCard.getIconKey()).isEqualTo("UI/Ugui/TravelPassV1/showcase_spirit_shop_icon");
        assertThat(shopCard.getHasClaimableReward()).isFalse();

        // 达成第 1 档（30 点）：免费轨可领 -> 游历手记亮红点；领完免费轨后即便典藏未买也必须灭红点
        player.travelPass().findOrAddSeason(1).setProgressPoints(30);
        GetActivityListResponse list1 = call(f, player, "GetActivityList",
                GetActivityListRequest.getDefaultInstance(), GetActivityListResponse.parser());
        assertThat(findActivity(list1.getActivitiesList(), TravelPassService.ACTIVITY_ID_TRAVEL_PASS)
                .getHasClaimableReward()).isTrue();

        ClaimTravelPassRewardResponse claimFree1 = call(f, player, "ClaimTravelPassReward",
                ClaimTravelPassRewardRequest.newBuilder()
                        .setClientRequestId(8011L).setPassId(1).setTierIndex(1).setPremiumTrack(false).build(),
                ClaimTravelPassRewardResponse.parser());
        assertThat(claimFree1.getErrorMessage().getId()).isZero();

        GetActivityListResponse list2 = call(f, player, "GetActivityList",
                GetActivityListRequest.getDefaultInstance(), GetActivityListResponse.parser());
        assertThat(findActivity(list2.getActivitiesList(), TravelPassService.ACTIVITY_ID_TRAVEL_PASS)
                .getHasClaimableReward()).isFalse();
    }

    @Test
    void 持久态未知字段往返保留与畸形存档failClosed() {
        UnknownFieldSet unknownSeason = UnknownFieldSet.newBuilder()
                .addField(99, UnknownFieldSet.Field.newBuilder().addVarint(12345L).build())
                .build();
        UnknownFieldSet unknownRoot = UnknownFieldSet.newBuilder()
                .addField(98, UnknownFieldSet.Field.newBuilder().addVarint(67890L).build())
                .build();
        PlayerTravelPassState raw = PlayerTravelPassState.newBuilder()
                .addSeasons(TravelPassSeasonProgressState.newBuilder()
                        .setPassId(1)
                        .setProgressPoints(60)
                        .setPremiumUnlocked(true)
                        .addUnlockedCosmeticKeys("frame_travel_qingyun_s1")
                        .setUnknownFields(unknownSeason))
                .setEquippedFrameKey("frame_travel_qingyun_s1")
                .setPassRevision(3L)
                .setUnknownFields(unknownRoot)
                .build();
        PlayerTravelPass restored = PlayerTravelPass.restore(raw);
        assertThat(restored.isValid()).isTrue();
        restored.setEquippedFrameKey("");
        PlayerTravelPassState saved = restored.toState();
        assertThat(saved.getUnknownFields().hasField(98)).isTrue();
        assertThat(saved.getSeasons(0).getUnknownFields().hasField(99)).isTrue();

        // 畸形存档（重复 pass_id）fail-closed 保留原态
        PlayerTravelPassState bad = PlayerTravelPassState.newBuilder()
                .addSeasons(TravelPassSeasonProgressState.newBuilder().setPassId(1))
                .addSeasons(TravelPassSeasonProgressState.newBuilder().setPassId(1))
                .build();
        PlayerTravelPass invalid = PlayerTravelPass.restore(bad);
        assertThat(invalid.isValid()).isFalse();
        assertThat(invalid.toState()).isEqualTo(bad);
    }

    @Test
    void 重叠启用期次failClosed双双排除且典藏价货币仅限真实灵石() {
        var real = BattleFixture.CONFIG;
        var baseSeason = real.travelPassSeason().all().getFirst();
        var s1 = baseSeason.toBuilder().setId(101).setEnabled(1).setStartsAtMs(1_000L).setEndsAtMs(10_000L)
                .setPremiumCurrencyType(Wallet.DIAMOND).build();
        var s2 = baseSeason.toBuilder().setId(102).setEnabled(1).setStartsAtMs(5_000L).setEndsAtMs(20_000L)
                .setPremiumCurrencyType(Wallet.DIAMOND).build();
        var sBadCur = baseSeason.toBuilder().setId(103).setEnabled(1).setStartsAtMs(30_000L).setEndsAtMs(40_000L)
                .setPremiumCurrencyType(Wallet.BOUND_GOLD).build();
        var baseTier = real.travelPassTier().all().getFirst();
        var tiers = List.of(
                baseTier.toBuilder().setId(9101).setPassId(101).setTierIndex(1).build(),
                baseTier.toBuilder().setId(9102).setPassId(102).setTierIndex(1).build(),
                baseTier.toBuilder().setId(9103).setPassId(103).setTierIndex(1).build());
        TravelPassTables parsed = new TravelPassTables(List.of(s1, s2, sBadCur), tiers, real.item().all());
        assertThat(parsed.season(101)).as("时间窗重叠的启用期次 A 必须排除").isNull();
        assertThat(parsed.season(102)).as("时间窗重叠的启用期次 B 必须排除").isNull();
        assertThat(parsed.season(103)).as("绑定银两定价的典藏手记必须拒绝加载").isNull();
        assertThat(parsed.enabledSeasons()).isEmpty();
    }

    private static BattleSettlementData battle(long battleId, long playerId, int matchMode, int difficulty,
                                               eBattleOutcome outcome, boolean participated, boolean fled,
                                               int totalRounds, int manualRounds, boolean effectiveContrib,
                                               int bossTrialKind) {
        return BattleSettlementData.newBuilder()
                .setBattleId(battleId)
                .setPlayerId(playerId)
                .setMatchMode(matchMode)
                .setBattleDifficulty(difficulty)
                .setOutcome(outcome)
                .setPlayerTeamIndex(0)
                .setParticipated(participated)
                .setFled(fled)
                .setTotalRounds(totalRounds)
                .setManualActionRounds(manualRounds)
                .setEffectiveCombatContribution(effectiveContrib)
                .setBossTrialKind(bossTrialKind)
                .build();
    }

    private static PlayerActivityInfo findActivity(List<PlayerActivityInfo> list, int id) {
        return list.stream().filter(a -> a.getActivityId() == id).findFirst().orElseThrow();
    }

    private static <R extends Message> R call(BattleFixture f, ScenePlayer player, String method, Message req,
                                              com.google.protobuf.Parser<R> parser) throws InvalidProtocolBufferException {
        int msgId = Contracts.REGISTRY.requireId(SERVICE, method);
        long reqId = f.request(player, msgId, req);
        MessageContent reply = f.pushes(player, msgId).stream()
                .filter(m -> m.getId() == reqId).findFirst().orElseThrow();
        return parser.parseFrom(reply.getSerializedMessage());
    }
}
