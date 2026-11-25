package com.game.scene.battle;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.player.store.state.BagItemState;
import com.game.player.store.state.BagState;
import com.game.player.store.state.BattleResultEntryState;
import com.game.player.store.state.BattleResultHistoryState;
import com.game.player.store.state.ClientRequestLedgerState;
import com.game.player.store.state.EquipmentFixedAffixState;
import com.game.player.store.state.EquipmentMaterialRewardLedgerState;
import com.game.player.store.state.EquipmentState;
import com.game.player.store.state.PlayerState;
import com.game.proto.BattleSettlementData;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.RankedMatchContext;
import com.game.proto.RankedMemberLoadout;
import com.game.proto.RankedPresetSelection;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.scene.audit.AssetAudit;
import com.game.scene.audit.GainAnomalyDetector;
import com.game.scene.bag.BagFeature;
import com.game.scene.bag.BagService;
import com.game.scene.bag.BagTables;
import com.game.scene.currency.CurrencyService;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.pet.PetService;
import com.game.scene.pet.PetTables;
import com.game.scene.player.Bag;
import com.game.scene.player.BagItem;
import com.game.scene.player.BagType;
import com.game.scene.player.ItemGuids;
import com.game.scene.player.PlayerBags;
import com.game.scene.player.PlayerBattleResults;
import com.game.scene.player.PlayerClientRequestLedger;
import com.game.scene.world.SceneClock;
import com.game.scene.world.ScenePlayer;
import com.game.scene.world.WorldTestAccess;
import com.game.table.AssetErrorTip;
import com.game.table.CommonErrorTip;
import com.game.table.ConfigTables;
import com.google.protobuf.UnknownFieldSet;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 核心玩法 V1 定稿收口回归测试：
 * <ul>
 *   <li>第 11 代持久字段 4 个新增允许扩张类型的领域 {@code restore -> modify -> toState} 未知字段往返证据；</li>
 *   <li>{@code BagItem.bound} 绑定分堆隔离、整理不跨绑定合堆、优先扣绑定、纯可交易扣减与资产托管绑定拦截；</li>
 *   <li>起步首宠灵狐一次性发放（新号发并出战、重复进入不重发、已有宠老号只置位）；</li>
 *   <li>正式人宠会武走本人真实养成 vs 免费预设娱乐场走 3×3 预设 + 困难首领试炼准入门禁与普通首通持久解锁。</li>
 * </ul>
 */
class CoreGameplayLoopParityTest {

    private static ConfigTables config;
    private static BagTables bagTables;
    private static PetTables petTables;

    @BeforeAll
    static void loadTables() {
        config = ConfigTables.load(Path.of("../config-data/tables"));
        bagTables = BagTables.from(config);
        petTables = PetTables.from(config);
    }

    @Test
    void 第十一代四个新增持久消息在领域修改往返中完整保留未知字段() {
        UnknownFieldSet future = UnknownFieldSet.newBuilder()
                .addField(999, UnknownFieldSet.Field.newBuilder().addVarint(77L).build())
                .build();

        // 1. BagItemState + 2. EquipmentFixedAffixState
        EquipmentState equip = EquipmentState.newBuilder()
                .addFixedAffixes(EquipmentFixedAffixState.newBuilder()
                        .setSlot(1).setAffixId(101).setUnknownFields(future).build())
                .build();
        BagItemState storedItem = BagItemState.newBuilder()
                .setItemUuid(5001L).setConfigId(3001).setStackSize(1).setPos(0).setBagType(0)
                .setAcquireSeq(1L).setBound(true).setEquipment(equip).setUnknownFields(future).build();
        PlayerBags bags = PlayerBags.restore(BagState.newBuilder().addItems(storedItem).build());
        bags.normalize(bagTables);
        BagItem restoredItem = bags.bag(BagType.INVENTORY).item(5001L);
        assertThat(restoredItem.bound()).isTrue();
        BagItemState roundTrippedItem = bags.toState().getItems(0);
        assertThat(roundTrippedItem.getBound()).isTrue();
        assertThat(roundTrippedItem.getUnknownFields().getField(999).getVarintList()).containsExactly(77L);
        assertThat(roundTrippedItem.getEquipment().getFixedAffixes(0).getUnknownFields().getField(999).getVarintList())
                .containsExactly(77L);

        // 3. EquipmentMaterialRewardLedgerState
        EquipmentMaterialRewardLedgerState rawLedger = EquipmentMaterialRewardLedgerState.newBuilder()
                .setAvailableUnits(10).setRevision(2).setUnknownFields(future).build();
        PlayerClientRequestLedger reqLedger = PlayerClientRequestLedger.restore(
                ClientRequestLedgerState.newBuilder().setMaterialReward(rawLedger).build());
        reqLedger.setMaterialReward(reqLedger.materialReward().toBuilder().setAvailableUnits(20).build());
        assertThat(reqLedger.toState().getMaterialReward().getAvailableUnits()).isEqualTo(20);
        assertThat(reqLedger.toState().getMaterialReward().getUnknownFields().getField(999).getVarintList())
                .containsExactly(77L);

        // 4. BattleResultEntryState
        BattleResultEntryState rawResult = BattleResultEntryState.newBuilder()
                .setResultSeq(1).setBattleId(8001L).setOutcome(1).setUnknownFields(future).build();
        PlayerBattleResults results = PlayerBattleResults.restore(
                BattleResultHistoryState.newBuilder().setLastResultSeq(1).addEntries(rawResult).build());
        results.markSeen(List.of(8001L), Set.of());
        BattleResultEntryState acked = results.toState().getEntries(0);
        assertThat(acked.getSeen()).isTrue();
        assertThat(acked.getUnknownFields().getField(999).getVarintList()).containsExactly(77L);
    }

    @Test
    void 绑定物品分堆隔离_整理不跨绑定合并_优先扣绑定且资产通道严拒绑定() {
        AtomicLong seq = new AtomicLong(10_000L);
        ItemGuids guids = count -> {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) out[i] = seq.incrementAndGet();
            return out;
        };
        SceneMetrics metrics = new SceneMetrics(new SimpleMeterRegistry());
        GainAnomalyDetector anomalies = new GainAnomalyDetector(GainAnomalyDetector.Threshold.OFF,
                Map.of(), Map.of(), SceneClock.SYSTEM, metrics);
        BagService bagService = new BagService(bagTables, guids, AssetAudit.log(), anomalies, metrics);
        ScenePlayer player = WorldTestAccess.player(101L, 1, 30, "甲", 1, List.of(), "acc1", PlayerState.getDefaultInstance());
        bagService.initializeOnLoad(player);

        // 3001 为可叠加打造材料：同配置先入可交易 5 个，再入绑定 3 个 -> 必须分占 2 个独立堆
        assertThat(bagService.addItems(player, BagType.INVENTORY, Map.of(3001, 5L), false, AssetAudit.Reason.GM_GRANT, 0, "").ok()).isTrue();
        assertThat(bagService.addItems(player, BagType.INVENTORY, Map.of(3001, 3L), true, AssetAudit.Reason.GM_GRANT, 0, "").ok()).isTrue();
        Bag inv = player.bags().bag(BagType.INVENTORY);
        assertThat(inv.items()).hasSize(2);
        assertThat(inv.plainQuantity(3001)).isEqualTo(8L);
        assertThat(inv.plainTradableQuantity(3001)).isEqualTo(5L);

        // 整理不得把绑定堆与可交易堆合并成同一堆
        bagService.sortByPlayer(player, BagType.INVENTORY);
        assertThat(inv.items()).hasSize(2);
        BagFeature feature = new BagFeature(bagService);
        assertThat(feature.snapshot(player, BagType.INVENTORY).getItemsList().stream().filter(i -> i.getBound()).count())
                .isEqualTo(1L);

        // 资产通道扣可交易 6 个失败（可交易只有 5 个，不能动绑定堆）；扣 5 个成功且只留 3 个绑定
        assertThat(bagService.prepareDebit(player, Map.of(3001, 6L), List.of()).tip())
                .isEqualTo(AssetErrorTip.asset_error.kAssetInvalidBundle_VALUE);
        var debitTradable = bagService.prepareDebit(player, Map.of(3001, 5L), List.of());
        assertThat(debitTradable.tip()).isZero();
        debitTradable.commit(AssetAudit.Reason.TRADE, 77L);
        inv = player.bags().bag(BagType.INVENTORY);
        assertThat(inv.plainTradableQuantity(3001)).isZero();
        assertThat(inv.plainQuantity(3001)).isEqualTo(3L);

        // 再补 4 个可交易后，常规消耗 4 个优先扣完 3 个绑定再扣 1 个可交易（consumedBound=true）
        assertThat(bagService.addItems(player, BagType.INVENTORY, Map.of(3001, 4L), false, AssetAudit.Reason.GM_GRANT, 0, "").ok()).isTrue();
        Bag.DrainOutcome outcome = inv.drainPlainExact(3001, 4L, false);
        assertThat(outcome.consumedBound()).isTrue();
        assertThat(inv.plainTradableQuantity(3001)).isEqualTo(3L);
        assertThat(inv.plainQuantity(3001)).isEqualTo(3L);

        // 唯一实例（绑定装备）被资产托管通道严拒
        int equipConfigId = config.item().all().stream()
                .filter(r -> r.getMaxStackSize() == 1 && r.getEquipKind() > 0)
                .findFirst().orElseThrow().getId();
        var addedBoundEquip = bagService.addItems(player, BagType.INVENTORY, Map.of(equipConfigId, 1L), true,
                AssetAudit.Reason.GM_GRANT, 0, "");
        assertThat(addedBoundEquip.ok()).isTrue();
        long boundEquipGuid = addedBoundEquip.written().getFirst().guids().getFirst();
        assertThat(bagService.prepareDebit(player, Map.of(), List.of(boundEquipGuid)).tip())
                .isEqualTo(AssetErrorTip.asset_error.kAssetInvalidBundle_VALUE);
    }

    @Test
    void 起步灵狐新号一次性发放并出战_老号已有宠仅置位不补发() {
        AtomicLong seq = new AtomicLong(20_000L);
        ItemGuids guids = count -> {
            long[] out = new long[count];
            for (int i = 0; i < count; i++) out[i] = seq.incrementAndGet();
            return out;
        };
        SceneMetrics metrics = new SceneMetrics(new SimpleMeterRegistry());
        CurrencyService currency = new CurrencyService(AssetAudit.log());
        PetService petService = new PetService(petTables, currency, guids, SceneClock.SYSTEM, new Random(7), metrics);

        // 新号无宠：首次发 1 只灵狐(id=1)并自动出战；再调用或召回后永不重发
        ScenePlayer newbie = WorldTestAccess.player(201L, 1, 1, "新人", 1, List.of(), "acc1", PlayerState.getDefaultInstance());
        petService.initializeOnLoad(newbie);
        assertThat(petService.ensureStarterPetGranted(newbie)).isEqualTo(PetService.StarterGrantResult.GRANTED);
        assertThat(newbie.pets().size()).isEqualTo(1);
        assertThat(newbie.pets().pets().getFirst().petTableId()).isEqualTo(PetService.STARTER_PET_TABLE_ID);
        assertThat(newbie.pets().activePetId()).isEqualTo(newbie.pets().pets().getFirst().petId());
        assertThat(newbie.pets().starterPetGranted()).isTrue();
        assertThat(petService.ensureStarterPetGranted(newbie)).isEqualTo(PetService.StarterGrantResult.SKIPPED);
        // 召回后重登也不补发
        newbie.pets().setActivePetId(0);
        assertThat(petService.ensureStarterPetGranted(newbie)).isEqualTo(PetService.StarterGrantResult.SKIPPED);
        assertThat(newbie.pets().size()).isEqualTo(1);

        // 已有宠老号：保留原宠，仅置位 starter_pet_granted
        ScenePlayer veteran = WorldTestAccess.player(202L, 1, 30, "老号", 1, List.of(), "acc2", PlayerState.getDefaultInstance());
        petService.initializeOnLoad(veteran);
        assertThat(petService.grant(veteran, 2).tip()).isZero();
        assertThat(petService.ensureStarterPetGranted(veteran)).isEqualTo(PetService.StarterGrantResult.MARKED_EXISTING);
        assertThat(veteran.pets().size()).isEqualTo(1);
        assertThat(veteran.pets().pets().getFirst().petTableId()).isEqualTo(2);
        assertThat(veteran.pets().starterPetGranted()).isTrue();
    }

    @Test
    void 正式会武走本人真实养成_免费预设走隔离快照_困难首领试炼由普通首通持久解锁() {
        BattleFixture f = new BattleFixture();
        SceneBattleTables battleTables = SceneBattleTables.from(config);
        ScenePlayer p = f.enter(11, 301L);
        // 先让角色 0 血验证正式会武 / 首领试炼 / 免费预设均不受场外 0 血阻断
        p.attributes().setHealth(0);
        p.attributes().setMana(0);

        // 1. 困难首领试炼(Dungeon 102 与 V2 双卫同阵 Dungeon 112) 未首通任意普通首领时均被 1006 拦截
        PrepareBattleRequest hardReq = f.prepareRequest(301L, 9001L).toBuilder()
                .setBattleConfigId(102)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .build();
        CompletableFuture<PrepareBattleResponse> hardBlockedFut = f.battle.prepare(hardReq);
        f.drain();
        assertThat(hardBlockedFut.join().getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kFeatureUnavailable_VALUE);

        PrepareBattleRequest dualGuardHardReq = f.prepareRequest(301L, 9012L).toBuilder()
                .setBattleConfigId(112)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .build();
        CompletableFuture<PrepareBattleResponse> dualBlockedFut = f.battle.prepare(dualGuardHardReq);
        f.drain();
        assertThat(dualBlockedFut.join().getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kFeatureUnavailable_VALUE);

        // 2. 通关普通首领试炼(Dungeon 101: boss_trial_kind=1, difficulty=1) -> 持久解锁困难门禁
        BattleSettlementData normalWin = BattleSettlementData.newBuilder()
                .setBattleId(9000L)
                .setPlayerId(301L)
                .setBattleConfigId(101)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setPlayerTeamIndex(0)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .setTotalRounds(4)
                .setHealth(100)
                .setMana(50)
                .build();
        assertThat(f.settlements.apply(f.world, p, normalWin).outcome())
                .isEqualTo(BattleSettlementService.Outcome.APPLIED);
        assertThat(p.trialJournal().normalBossTrialCleared()).isTrue();
        assertThat(p.trialJournal().toState().getNormalBossTrialCleared()).isTrue();
        f.completeSaves(com.game.scene.world.PlayerRepository.ProgressResult.SAVED);
        assertThat(p.persistedState().getTrialJournal().getNormalBossTrialCleared()).isTrue();

        // 现可正常备战困难首领试炼(Dungeon 102 与双卫同阵 Dungeon 112)，且快照满血满蓝无耗材
        p.attributes().setHealth(0);
        CompletableFuture<PrepareBattleResponse> hardOkFut = f.battle.prepare(hardReq);
        f.drain();
        PrepareBattleResponse hardOk = hardOkFut.join();
        assertThat(hardOk.getErrorMessage().getId()).isZero();
        assertThat(hardOk.getSnapshot().getBaseAttributes().getHealth()).isEqualTo(hardOk.getSnapshot().getMaxHealth());
        assertThat(hardOk.getSnapshot().getItemsList()).isEmpty();
        f.cancel(301L, 9001L);
        f.drain();

        p.attributes().setHealth(0);
        CompletableFuture<PrepareBattleResponse> dualOkFut = f.battle.prepare(dualGuardHardReq);
        f.drain();
        PrepareBattleResponse dualOk = dualOkFut.join();
        assertThat(dualOk.getErrorMessage().getId()).isZero();
        assertThat(dualOk.getSnapshot().getBaseAttributes().getHealth()).isEqualTo(dualOk.getSnapshot().getMaxHealth());
        f.cancel(301L, 9012L);
        f.drain();

        int activeRuleVersion = battleTables.ranked().rule().getRuleVersion();
        // 3. 正式人宠会武(PVP_TEAM, season_id=1, use_free_preset=false)：使用本人真实等级/职业快照，不走 30 级免费预设
        PrepareBattleRequest formalRankedReq = f.prepareRequest(301L, 9002L).toBuilder()
                .setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setRankedContext(RankedMatchContext.newBuilder()
                        .setSeasonId(1).setRuleVersion(activeRuleVersion).setTeamSize(1)
                        .setConfigFingerprint(battleTables.ranked().configFingerprint())
                        .setUseFreePreset(false))
                .build();
        CompletableFuture<PrepareBattleResponse> formalFut = f.battle.prepare(formalRankedReq);
        f.drain();
        PrepareBattleResponse formalResp = formalFut.join();
        assertThat(formalResp.getErrorMessage().getId()).isZero();
        assertThat(formalResp.getSnapshot().getLevel()).isEqualTo(p.level());
        assertThat(formalResp.getSnapshot().getPetsList()).allSatisfy(pet -> assertThat(pet.getCompetitiveTemporary()).isFalse());
        f.cancel(301L, 9002L);
        f.drain();

        // 4. 免费预设娱乐场(use_free_preset=true, season_id=0)：使用 30 级免费预设与临时竞技宝宝
        PrepareBattleRequest freePresetReq = f.prepareRequest(301L, 9003L).toBuilder()
                .setBattleConfigId(110)
                .setMatchMode(MatchMode.MATCH_MODE_POLARITY_5V5_VALUE)
                .setRankedContext(RankedMatchContext.newBuilder()
                        .setSeasonId(0).setRuleVersion(activeRuleVersion).setTeamSize(5)
                        .setConfigFingerprint(battleTables.ranked().configFingerprint())
                        .setUseFreePreset(true)
                        .addMembers(RankedMemberLoadout.newBuilder()
                                .setPlayerId(301L)
                                .setPresets(RankedPresetSelection.newBuilder().setCharacterPresetId(101).setPetPresetId(201))))
                .build();
        CompletableFuture<PrepareBattleResponse> freeFut = f.battle.prepare(freePresetReq);
        f.drain();
        PrepareBattleResponse freeResp = freeFut.join();
        assertThat(freeResp.getErrorMessage().getId()).isZero();
        assertThat(freeResp.getSnapshot().getLevel()).isEqualTo(30);
        assertThat(freeResp.getSnapshot().getPetsCount()).isEqualTo(1);
        assertThat(freeResp.getSnapshot().getPets(0).getCompetitiveTemporary()).isTrue();
    }
}
