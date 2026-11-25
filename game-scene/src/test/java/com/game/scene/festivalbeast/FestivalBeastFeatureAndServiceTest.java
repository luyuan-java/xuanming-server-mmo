package com.game.scene.festivalbeast;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetPetInstance;
import com.game.api.proto.AssetStream;
import com.game.player.store.state.PetEntry;
import com.game.player.store.state.PlayerFestivalBeastState;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleSettlementData;
import com.game.proto.ClaimFestivalBeastRewardRequest;
import com.game.proto.ClaimFestivalBeastRewardResponse;
import com.game.proto.FestivalBeastActionBlockCode;
import com.game.proto.FestivalBeastBattleContext;
import com.game.proto.FestivalBeastClaimOutcome;
import com.game.proto.FestivalBeastLaunchState;
import com.game.proto.FestivalBeastPhase;
import com.game.proto.GetActivityListRequest;
import com.game.proto.GetActivityListResponse;
import com.game.proto.GetFestivalBeastDetailRequest;
import com.game.proto.GetFestivalBeastDetailResponse;
import com.game.proto.GetRecentBattleResultsRequest;
import com.game.proto.GetRecentBattleResultsResponse;
import com.game.proto.MessageContent;
import com.game.proto.PlayerActivityEntryKind;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.StartFestivalBeastChallengeRequest;
import com.game.proto.StartFestivalBeastChallengeResponse;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.MatchMode;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.scene.asset.AssetOpAuth;
import com.game.scene.asset.AssetOpService;
import com.game.scene.battle.BattleFixture;
import com.game.scene.player.PlayerFestivalBeast;
import com.game.scene.player.PlayerPets.Pet;
import com.game.scene.player.Wallet;
import com.game.scene.testing.Contracts;
import com.game.scene.world.PlayerRepository.ProgressResult;
import com.game.scene.world.ScenePlayer;
import com.game.table.FestivalBeastActivityTable;
import com.game.table.FestivalBeastErrorTip.festival_beast_error;
import com.game.table.PetErrorTip.pet_error;
import com.game.table.PetTable;
import com.google.protobuf.UnknownFieldSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 瑞兽迎春限时活动全闭环回归测试（规则、476–478 RPC、锁宠复核、隔离结算、永久领奖、专属赠送格与防单宠交易）。 */
class FestivalBeastFeatureAndServiceTest {

    private static final String SERVICE = "SceneActivityClientPlayer";
    private static final String REQ_LAUNCH_1 = "11111111-1111-4111-8111-111111111111";
    private static final String REQ_LAUNCH_2 = "22222222-2222-4222-8222-222222222222";
    private static final String REQ_CLAIM_1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    private static final String REQ_CLAIM_2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
    private static final long OPEN_START_MS = 1_769_904_000_000L; // 2026-02-01 00:00:00 UTC
    private static final long OPEN_END_MS = OPEN_START_MS + FestivalBeastRules.REQUIRED_OPEN_DURATION_MS;
    private static final String ASSET_SECRET = "festival-beast-test-secret-key-32b";

    private static FestivalBeastActivityTable realActivityRow() {
        return BattleFixture.CONFIG.festivalBeastActivity().all().stream()
                .filter(r -> r.getId() == 202601)
                .findFirst()
                .orElseThrow();
    }

    private static FestivalBeastTables scheduledTables(long startMs, long endMs) {
        FestivalBeastActivityTable base = realActivityRow();
        FestivalBeastActivityTable scheduled = base.toBuilder()
                .setEnabled(true)
                .setEffectiveStartAtMs(startMs)
                .setEffectiveEndAtMs(endMs)
                .build();
        Map<Integer, com.game.table.DungeonTable> dungeons = new java.util.HashMap<>();
        BattleFixture.CONFIG.dungeon().all().forEach(d -> dungeons.put(d.getId(), d));
        Map<Integer, PetTable> pets = new java.util.HashMap<>();
        BattleFixture.CONFIG.pet().all().forEach(p -> pets.put(p.getId(), p));
        return new FestivalBeastTables(
                List.of(scheduled),
                List.copyOf(BattleFixture.CONFIG.festivalBeastTier().all()),
                dungeons,
                pets);
    }

    private static <T extends com.google.protobuf.Message> T rpc(
            BattleFixture f, ScenePlayer p, String service, String method, com.google.protobuf.Message req,
            com.google.protobuf.Parser<T> parser) throws Exception {
        int id = Contracts.REGISTRY.requireId(service, method);
        long reqId = f.request(p, id, req);
        MessageContent reply = f.pushes(p, id).stream()
                .filter(m -> m.getId() == reqId).findFirst().orElseThrow();
        return parser.parseFrom(reply.getSerializedMessage());
    }

    @Test
    void 正式配表完整性_14天整窗与五阶段裁定() {
        FestivalBeastActivityTable base = realActivityRow();
        assertThat(base.getEnabled()).isFalse();
        assertThat(base.getActivityName()).isEqualTo("瑞兽迎春");
        assertThat(base.getBossName()).isEqualTo("春禧兽");
        PetTable rewardPet = BattleFixture.CONFIG.pet().all().stream()
                .filter(p -> p.getId() == base.getRewardPetTableId()).findFirst().orElseThrow();
        assertThat(FestivalBeastRules.isValidRewardPetRow(rewardPet)).isTrue();
        assertThat(FestivalBeastRules.validateTiers(List.copyOf(BattleFixture.CONFIG.festivalBeastTier().all()))).isTrue();

        var dungeon = BattleFixture.CONFIG.dungeon().all().stream()
                .filter(d -> d.getId() == base.getBattleConfigId()).findFirst().orElseThrow();
        // 开启但非 14×24h 整窗必须拒绝
        FestivalBeastActivityTable badDuration = base.toBuilder()
                .setEnabled(true)
                .setEffectiveStartAtMs(OPEN_START_MS)
                .setEffectiveEndAtMs(OPEN_START_MS + 13L * FestivalBeastRules.DAY_MS)
                .build();
        assertThat(FestivalBeastRules.validateActivityRow(
                badDuration, dungeon, rewardPet, List.copyOf(BattleFixture.CONFIG.festivalBeastTier().all()))).isFalse();

        // 严格 14×24h 合法
        FestivalBeastActivityTable valid = base.toBuilder()
                .setEnabled(true)
                .setEffectiveStartAtMs(OPEN_START_MS)
                .setEffectiveEndAtMs(OPEN_END_MS)
                .build();
        assertThat(FestivalBeastRules.validateActivityRow(
                valid, dungeon, rewardPet, List.copyOf(BattleFixture.CONFIG.festivalBeastTier().all()))).isTrue();

        assertThat(FestivalBeastRules.resolvePhase(false, OPEN_START_MS, OPEN_END_MS, 7, OPEN_START_MS))
                .isEqualTo(FestivalBeastPhase.FESTIVAL_BEAST_PHASE_UNSCHEDULED);
        assertThat(FestivalBeastRules.resolvePhase(true, OPEN_START_MS, OPEN_END_MS, 7, OPEN_START_MS - 1L))
                .isEqualTo(FestivalBeastPhase.FESTIVAL_BEAST_PHASE_UPCOMING);
        assertThat(FestivalBeastRules.resolvePhase(true, OPEN_START_MS, OPEN_END_MS, 7, OPEN_START_MS))
                .isEqualTo(FestivalBeastPhase.FESTIVAL_BEAST_PHASE_OPEN);
        assertThat(FestivalBeastRules.resolvePhase(true, OPEN_START_MS, OPEN_END_MS, 7, OPEN_END_MS))
                .isEqualTo(FestivalBeastPhase.FESTIVAL_BEAST_PHASE_CLAIM_SHOWCASE);
        assertThat(FestivalBeastRules.resolvePhase(true, OPEN_START_MS, OPEN_END_MS, 7,
                OPEN_END_MS + 7L * FestivalBeastRules.DAY_MS))
                .isEqualTo(FestivalBeastPhase.FESTIVAL_BEAST_PHASE_HISTORY);
        assertThat(FestivalBeastRules.parseUuid128("00000000-0000-0000-0000-000000000000")).isNull();
        assertThat(FestivalBeastRules.parseUuid128(REQ_LAUNCH_1)).isNotNull();
    }

    @Test
    void 默认未排期不开放_开放态锁宠重放与备战二次复核换宠拒绝() throws Exception {
        // 1. 默认未排期时 RPC 476 返回 UNSCHEDULED，大厅 190 不暴露未排期入口
        BattleFixture unscheduled = new BattleFixture();
        ScenePlayer p0 = unscheduled.enter(11, 3001L);
        GetFestivalBeastDetailResponse d0 = rpc(unscheduled, p0, SERVICE, "GetFestivalBeastDetail",
                GetFestivalBeastDetailRequest.getDefaultInstance(), GetFestivalBeastDetailResponse.parser());
        assertThat(d0.getErrorMessage().getId()).isZero();
        assertThat(d0.getPhase()).isEqualTo(FestivalBeastPhase.FESTIVAL_BEAST_PHASE_UNSCHEDULED);
        assertThat(d0.getCanStartChallenge()).isFalse();
        GetActivityListResponse list0 = rpc(unscheduled, p0, SERVICE, "GetActivityList",
                GetActivityListRequest.getDefaultInstance(), GetActivityListResponse.parser());
        assertThat(list0.getActivitiesList())
                .noneMatch(a -> a.getEntryKind() == PlayerActivityEntryKind.PLAYER_ACTIVITY_ENTRY_FESTIVAL_BEAST);

        // 2. 排期开放态：无宠/倒地拒、低血存活可入、锁宠开局 + 幂等重放 + 备战复核换宠拦截
        BattleFixture f = new BattleFixture();
        f.clock.setEpochMillis(OPEN_START_MS + 3_600_000L);
        FestivalBeastService svc = new FestivalBeastService(
                scheduledTables(OPEN_START_MS, OPEN_END_MS), f.pets, f.clock, f.petListChangedId);
        f.settlements.withFestivalBeastService(svc);
        f.battle.withFestivalBeastService(svc);
        ScenePlayer p = f.enter(11, 3002L);
        p.setLevel(50);

        // 无出战宝宝 → PET_REQUIRED
        GetFestivalBeastDetailResponse noPet = svc.getDetail(p, GetFestivalBeastDetailRequest.getDefaultInstance(), f.clock.epochMillis());
        assertThat(noPet.getPhase()).isEqualTo(FestivalBeastPhase.FESTIVAL_BEAST_PHASE_OPEN);
        assertThat(noPet.getCanStartChallenge()).isFalse();
        assertThat(noPet.getStartBlockCode()).isEqualTo(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_PET_REQUIRED);
        assertThat(svc.buildCatalogEntry(p, f.clock.epochMillis()).getEntryKind())
                .isEqualTo(PlayerActivityEntryKind.PLAYER_ACTIVITY_ENTRY_FESTIVAL_BEAST);

        // 发放两只普通宠，第一只出战但 0 血倒地 → PET_DEAD；1 血存活 → 允许进入
        long pet1Id = f.pets.grant(p, 1).petId();
        long pet2Id = f.pets.grant(p, 2).petId();
        assertThat(f.pets.summon(p, pet1Id)).isZero();
        Pet pet1 = p.pets().find(pet1Id);
        pet1.setHealth(0L);
        GetFestivalBeastDetailResponse deadPet = svc.getDetail(p, GetFestivalBeastDetailRequest.getDefaultInstance(), f.clock.epochMillis());
        assertThat(deadPet.getCanStartChallenge()).isFalse();
        assertThat(deadPet.getStartBlockCode()).isEqualTo(FestivalBeastActionBlockCode.FESTIVAL_BEAST_BLOCK_PET_DEAD);

        pet1.setHealth(1L);
        p.attributes().setHealth(1L);
        GetFestivalBeastDetailResponse lowHp = svc.getDetail(p, GetFestivalBeastDetailRequest.getDefaultInstance(), f.clock.epochMillis());
        assertThat(lowHp.getCanStartChallenge()).isTrue();
        assertThat(lowHp.getMatchedTierId()).isPositive();

        // 发起挑战：先锁 pet1 并持久化 pending_launch，Match 批准 battle_id=88001
        AtomicInteger matchCalls = new AtomicInteger();
        List<BattleActivityContext> sentContexts = new ArrayList<>();
        svc.withLaunchSender((req, cb) -> {
            matchCalls.incrementAndGet();
            sentContexts.add(req.getActivityContext());
            cb.onReply(true, StartActivityBattleResponse.newBuilder()
                    .setReject(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_NONE)
                    .setBattleId(88001L)
                    .setAdmittedAtMs(f.clock.epochMillis())
                    .build());
            return true;
        });
        StartFestivalBeastChallengeRequest startReq = StartFestivalBeastChallengeRequest.newBuilder()
                .setClientRequestId(REQ_LAUNCH_1)
                .setActivityInstanceId(202601)
                .setConfigRevision(1)
                .build();
        StartFestivalBeastChallengeResponse startResp = svc.startChallenge(f.world, p, startReq, f.clock.epochMillis());
        assertThat(startResp.getErrorMessage().getId()).isZero();
        assertThat(startResp.getLaunchState()).isEqualTo(FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_ADMITTED);
        assertThat(startResp.getLockedPetId()).isEqualTo(pet1Id);
        assertThat(startResp.getBattleId()).isEqualTo(88001L);
        assertThat(matchCalls.get()).isEqualTo(1);

        // 同 UUID 重放不重发 Match；不同 UUID 在途被拒 kFestivalBeastLaunchInFlight
        StartFestivalBeastChallengeResponse replayed = svc.startChallenge(f.world, p, startReq, f.clock.epochMillis());
        assertThat(replayed.getReplayed()).isTrue();
        assertThat(replayed.getBattleId()).isEqualTo(88001L);
        assertThat(matchCalls.get()).isEqualTo(1);

        StartFestivalBeastChallengeResponse dup = svc.startChallenge(f.world, p,
                startReq.toBuilder().setClientRequestId(REQ_LAUNCH_2).build(), f.clock.epochMillis());
        assertThat(dup.getErrorMessage().getId()).isEqualTo(festival_beast_error.kFestivalBeastLaunchInFlight_VALUE);

        // 备战生成快照前恶意换宠 pet2 → 备战被拒 kFestivalBeastPetChanged，并标记 pending_launch=REJECTED
        assertThat(f.pets.summon(p, pet2Id)).isZero();
        PrepareBattleRequest badPrepare = PrepareBattleRequest.newBuilder()
                .setPlayerId(p.playerId())
                .setBattleId(88001L)
                .setBattleNodeId(BattleFixture.BATTLE_NODE)
                .setBattleConfigId(realActivityRow().getBattleConfigId())
                .setDeadlineMs(f.deadline())
                .setPrepareDeadlineMs(f.prepareDeadline())
                .setActivityContext(sentContexts.getFirst())
                .build();
        var badReplyFuture = f.battle.prepare(badPrepare);
        f.drain();
        PrepareBattleResponse badReply = BattleFixture.done(badReplyFuture);
        assertThat(badReply.getErrorMessage().getId()).isEqualTo(festival_beast_error.kFestivalBeastPetChanged_VALUE);
        assertThat(p.festivalBeast().state().getPendingLaunch().getLaunchState())
                .isEqualTo(FestivalBeastLaunchState.FESTIVAL_BEAST_LAUNCH_REJECTED_VALUE);

        // 换回原宠 pet1 并重新备战 → 局内满血蓝、零道具、零捕捉位（不修改局外 1 血残血状态）
        assertThat(f.pets.summon(p, pet1Id)).isZero();
        var goodReplyFuture = f.battle.prepare(badPrepare);
        f.drain();
        PrepareBattleResponse goodReply = BattleFixture.done(goodReplyFuture);
        assertThat(goodReply.getErrorMessage().getId()).isZero();
        assertThat(goodReply.getSnapshot().getBaseAttributes().getHealth())
                .isEqualTo(goodReply.getSnapshot().getMaxHealth());
        assertThat(goodReply.getSnapshot().getPetsCount()).isEqualTo(1);
        assertThat(goodReply.getSnapshot().getPets(0).getPetId()).isEqualTo(pet1Id);
        assertThat(goodReply.getSnapshot().getPets(0).getBaseAttributes().getHealth())
                .isEqualTo(goodReply.getSnapshot().getPets(0).getMaxHealth());
        assertThat(goodReply.getSnapshot().getItemsList()).isEmpty();
        assertThat(goodReply.getSnapshot().getPetCaptureSlots()).isZero();
        // 局外气血保持 1 不被污染
        assertThat(p.attributes().health()).isEqualTo(1L);
        assertThat(pet1.health()).isEqualTo(1L);
    }

    @Test
    void 三胜永久资格_负逃不回退_隔离零污染_跨期满格专属领奖与防单宠交易() throws Exception {
        BattleFixture f = new BattleFixture();
        f.clock.setEpochMillis(OPEN_START_MS + 7_200_000L);
        FestivalBeastService svc = new FestivalBeastService(
                scheduledTables(OPEN_START_MS, OPEN_END_MS), f.pets, f.clock, f.petListChangedId);
        f.settlements.withFestivalBeastService(svc);
        f.battle.withFestivalBeastService(svc);
        ScenePlayer p = f.enter(11, 3003L);
        p.setLevel(50);

        // 填满普通携带格（测试结束后验证专属神兽不受普通格满阻断）
        int normalCap = (int) f.pets.transferCapacity();
        long activePetId = 0L;
        for (int i = 0; i < normalCap; i++) {
            long id = f.pets.grant(p, (i % 3) + 1).petId();
            assertThat(id).isPositive();
            if (i == 0) {
                activePetId = id;
            }
        }
        assertThat(f.pets.hasNormalPetSlot(p.pets())).isFalse();
        assertThat(f.pets.summon(p, activePetId)).isZero();
        p.attributes().setHealth(123L);
        p.pets().find(activePetId).setHealth(77L);
        long goldBefore = p.wallet().balance(Wallet.GOLD);

        FestivalBeastActivityTable row = realActivityRow();
        FestivalBeastBattleContext fbCtx = FestivalBeastBattleContext.newBuilder()
                .setActivityInstanceId(row.getId())
                .setConfigRevision(row.getConfigRevision())
                .setRewardIdentity(row.getRewardIdentity())
                .setRewardDefinitionVersion(row.getRewardDefinitionVersion())
                .setRewardPetTableId(row.getRewardPetTableId())
                .setRequiredWins(row.getRequiredWins())
                .setLockedPetId(activePetId)
                .setLockedOwnerLevel(50)
                .setTierId(5)
                .setRoundLimit(row.getRoundLimit())
                .setCycleRounds(row.getCycleRounds())
                .setFirstHeavyRound(row.getFirstHeavyRound())
                .setTelegraphLeadRounds(row.getTelegraphLeadRounds())
                .setHeavyDamagePercent(row.getHeavyDamagePercent())
                .setBossName(row.getBossName())
                .setBossHealth(10000L)
                .setBossPhysicalAttack(500L)
                .setBossDefense(200L)
                .setBossResistance(10)
                .setBossSpeed(250L)
                .setEffectiveStartAtMs(OPEN_START_MS)
                .setEffectiveEndAtMs(OPEN_END_MS)
                .build();

        // 1. 第 1 胜
        deliverFestivalSettlement(f, p, 91001L, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, false, fbCtx);
        assertThat(p.festivalBeast().state().getPeriodProgress(0).getWins()).isEqualTo(1);
        assertThat(p.clientRequestLedger().materialReward().getAvailableUnits()).isEqualTo(10L);
        // 局外血量/宠血/金币零污染
        assertThat(p.attributes().health()).isEqualTo(123L);
        assertThat(p.pets().find(activePetId).health()).isEqualTo(77L);
        assertThat(p.wallet().balance(Wallet.GOLD)).isEqualTo(goldBefore);

        // 2. 失败 + 逃跑：不加胜场，不清空已有 1 胜，不加材料
        deliverFestivalSettlement(f, p, 91002L, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, false, fbCtx);
        deliverFestivalSettlement(f, p, 91003L, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, true, fbCtx);
        assertThat(p.festivalBeast().state().getPeriodProgress(0).getWins()).isEqualTo(1);
        assertThat(p.clientRequestLedger().materialReward().getAvailableUnits()).isEqualTo(10L);

        // 3. 第 2 胜、第 3 胜 → 产生永久待领资格 + BattleEndS2C + GetRecentBattleResults 字段齐全
        deliverFestivalSettlement(f, p, 91004L, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, false, fbCtx);
        deliverFestivalSettlement(f, p, 91005L, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, false, fbCtx);
        assertThat(p.festivalBeast().state().getPeriodProgress(0).getWins()).isEqualTo(3);
        assertThat(p.festivalBeast().state().getEligibilitiesCount()).isEqualTo(1);
        assertThat(p.clientRequestLedger().materialReward().getAvailableUnits()).isEqualTo(30L);

        // 核对第 3 胜的 BattleEndS2C.festival_beast_settlement
        List<MessageContent> battleEnds = f.pushes(p, f.battleEndId);
        BattleEndS2C thirdEnd = BattleEndS2C.parseFrom(battleEnds.getLast().getSerializedMessage());
        assertThat(thirdEnd.hasFestivalBeastSettlement()).isTrue();
        assertThat(thirdEnd.getFestivalBeastSettlement().getWinsBefore()).isEqualTo(2);
        assertThat(thirdEnd.getFestivalBeastSettlement().getWinsAfter()).isEqualTo(3);
        assertThat(thirdEnd.getFestivalBeastSettlement().getNewlyEligible()).isTrue();
        assertThat(thirdEnd.getFestivalBeastSettlement().getMaterialUnitsAdded()).isEqualTo(10);
        assertThat(thirdEnd.getFestivalBeastSettlement().getMaterialAvailableUnitsAfter()).isEqualTo(30L);

        // 核对近期战果 371 也带出瑞兽增量
        GetRecentBattleResultsResponse recent = rpc(f, p, "SceneBattleResultClientPlayer", "GetRecentBattleResults",
                GetRecentBattleResultsRequest.getDefaultInstance(), GetRecentBattleResultsResponse.parser());
        assertThat(recent.getResults(0).getFestivalActivityInstanceId()).isEqualTo(row.getId());
        assertThat(recent.getResults(0).getFestivalWinsAfter()).isEqualTo(3);
        assertThat(recent.getResults(0).getFestivalNewlyEligible()).isTrue();

        // 4. 时间推进到活动结束并超过 7 天展示期（HISTORY 态）：永久资格仍可领，且无视普通宠物格已满！
        f.clock.setEpochMillis(OPEN_END_MS + 10L * FestivalBeastRules.DAY_MS);
        GetFestivalBeastDetailResponse historyDetail = svc.getDetail(
                p, GetFestivalBeastDetailRequest.getDefaultInstance(), f.clock.epochMillis());
        assertThat(historyDetail.getPhase()).isEqualTo(FestivalBeastPhase.FESTIVAL_BEAST_PHASE_HISTORY);
        assertThat(historyDetail.getCanStartChallenge()).isFalse();
        assertThat(historyDetail.getCanClaimReward()).isTrue();

        ClaimFestivalBeastRewardRequest claim1 = ClaimFestivalBeastRewardRequest.newBuilder()
                .setClientRequestId(REQ_CLAIM_1)
                .setRewardIdentity(row.getRewardIdentity())
                .build();
        ClaimFestivalBeastRewardResponse c1 = svc.claimReward(f.world, p, claim1, f.clock.epochMillis());
        assertThat(c1.getErrorMessage().getId()).isZero();
        assertThat(c1.getOutcome()).isEqualTo(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_APPLIED);
        assertThat(c1.getDurable()).isFalse(); // 尚未落盘确认
        long beastPetId = c1.getPetId();
        assertThat(beastPetId).isPositive();
        assertThat(c1.getRewardPetTableId()).isEqualTo(row.getRewardPetTableId());

        // 落盘确认后同 UUID 重放返回 durable=true 且不铸第二只
        f.completeSaves(ProgressResult.SAVED);
        ClaimFestivalBeastRewardResponse c1Replay = svc.claimReward(f.world, p, claim1, f.clock.epochMillis());
        assertThat(c1Replay.getReplayed()).isTrue();
        assertThat(c1Replay.getDurable()).isTrue();
        assertThat(c1Replay.getPetId()).isEqualTo(beastPetId);

        // 不同 UUID 重复领奖收敛为 ALREADY_CLAIMED 且返回同一 pet_id
        ClaimFestivalBeastRewardResponse c2 = svc.claimReward(f.world, p,
                claim1.toBuilder().setClientRequestId(REQ_CLAIM_2).build(), f.clock.epochMillis());
        assertThat(c2.getOutcome()).isEqualTo(FestivalBeastClaimOutcome.FESTIVAL_BEAST_CLAIM_ALREADY_CLAIMED);
        assertThat(c2.getPetId()).isEqualTo(beastPetId);

        // 专属神兽不占普通格、商店和捕捉不出售/不允许捕捉专属神兽
        assertThat(f.pets.countNormalPets(p.pets())).isEqualTo(normalCap);
        assertThat(f.pets.countSpeciesPets(p.pets(), row.getRewardPetTableId())).isEqualTo(1L);
        assertThat(f.pets.shop(p).getOffersList()).noneMatch(o -> o.getPetTableId() == row.getRewardPetTableId());
        assertThat(f.pets.prepareCapturedPets(p, List.of(row.getRewardPetTableId())).tip()).isNotZero();

        // 5. AssetOpService 禁止将春禧兽单宠交易/托管转出或转入（kPetNotTransferable = 26018）
        AssetOpService assetOps = new AssetOpService(
                f.world, f.currency, f.bags, new AssetOpAuth(c -> ASSET_SECRET), f.clock, f.sceneMetrics,
                f.pets::transferCapacity)
                .withPetRules(f.pets::isTransferableSpecies, f.pets::hasNormalPetSlot);
        AssetOpRequest debitReq = AssetOpSignatures.sign(
                AssetRpc.DEBIT,
                AssetOpRequest.newBuilder()
                        .setPlayerId(p.playerId())
                        .setStream(AssetStream.ASSET_STREAM_TRADE_DEBIT)
                        .setStreamEpoch(1_769_999_000_000L)
                        .setSeq(1L)
                        .setTxType(3)
                        .setCorrelationId(7001L)
                        .setBundle(AssetBundle.newBuilder().setPetId(beastPetId))
                        .build(),
                AssetOpSignatures.CALLER_TRADE, ASSET_SECRET, f.clock.epochMillis());
        AssetOpResponse debitResp = assetOps.handle(AssetRpc.DEBIT, debitReq);
        assertThat(debitResp.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(debitResp.getReason()).isEqualTo(pet_error.kPetNotTransferable_VALUE);

        PetEntry beastProto = p.pets().toState().getPetsList().stream()
                .filter(entry -> entry.getPetId() == beastPetId).findFirst().orElseThrow();
        AssetOpRequest creditReq = AssetOpSignatures.sign(
                AssetRpc.CREDIT,
                AssetOpRequest.newBuilder()
                        .setPlayerId(p.playerId())
                        .setStream(AssetStream.ASSET_STREAM_TRADE_CREDIT)
                        .setStreamEpoch(1_769_999_000_000L)
                        .setSeq(1L)
                        .setTxType(1)
                        .setCorrelationId(7002L)
                        .setBundle(AssetBundle.newBuilder().setPetInstance(AssetPetInstance.newBuilder()
                                .setPetId(beastPetId + 999L)
                                .setPetTableId(row.getRewardPetTableId())
                                .setName(beastProto.getName())
                                .setLevel(beastProto.getLevel())
                                .setInstanceProto(beastProto.toBuilder().setPetId(beastPetId + 999L).build().toByteString())))
                        .build(),
                AssetOpSignatures.CALLER_TRADE, ASSET_SECRET, f.clock.epochMillis());
        AssetOpResponse creditResp = assetOps.handle(AssetRpc.CREDIT, creditReq);
        assertThat(creditResp.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(creditResp.getReason()).isEqualTo(pet_error.kPetNotTransferable_VALUE);

        // 6. PlayerFestivalBeast 未知字段往返保留回归保护
        UnknownFieldSet unknown = UnknownFieldSet.newBuilder()
                .addField(999, UnknownFieldSet.Field.newBuilder().addVarint(42L).build())
                .build();
        PlayerFestivalBeast holder = PlayerFestivalBeast.restore(
                p.festivalBeast().toState().toBuilder().setUnknownFields(unknown).build());
        holder.replace(holder.state().toBuilder().setStateRevision(holder.state().getStateRevision() + 1L).build());
        assertThat(holder.toState().getUnknownFields().getField(999).getVarintList()).containsExactly(42L);
    }

    private static void deliverFestivalSettlement(BattleFixture f, ScenePlayer p, long battleId,
                                                  eBattleOutcome outcome, boolean fled,
                                                  FestivalBeastBattleContext fbCtx) {
        f.fighting(p.playerId(), battleId);
        BattleSettlementData settlement = BattleFixture.settlement(p.playerId(), battleId, 0L)
                .setBattleConfigId(realActivityRow().getBattleConfigId())
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setOutcome(outcome)
                .setFled(fled)
                .setTotalRounds(6)
                .setBossTrialKind(FestivalBeastRules.BOSS_TRIAL_KIND_FESTIVAL_BEAST)
                .setBattleDifficulty(1)
                .setHealth(1L)
                .setMana(0L)
                .setGoldGain(9999L) // 验证隔离结算忽略金币/气血回写
                .setFinishedAtMs(f.clock.epochMillis())
                .setFestivalBeast(fbCtx)
                .build();
        f.deliver(settlement);
        f.drain();
        f.completeSaves(ProgressResult.SAVED);
    }
}
