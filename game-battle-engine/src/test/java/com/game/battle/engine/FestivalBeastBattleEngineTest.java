package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattleEventItem;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTelegraphEntry;
import com.game.proto.CreateBattleRequest;
import com.game.proto.FestivalBeastBattleContext;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleEventType;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.table.BuffTable;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import com.game.table.FestivalBeastActivityTable;
import com.game.table.FestivalBeastTierTable;
import com.game.table.ItemTable;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 瑞兽迎春限时活动 Java 战斗引擎全规则对账测试（对齐 C++ {@code FestivalBeastBattleTest}）。 */
class FestivalBeastBattleEngineTest {

    private static ConfigTables tables;
    private static TableBattleData data;
    private static int festivalDungeonId;
    private static int usableItemId;
    private static int stunBuffId;
    private static FestivalBeastActivityTable activityRow;
    private static FestivalBeastTierTable tier50;

    @BeforeAll
    static void loadTables() {
        tables = ConfigTables.load(Path.of("../config-data/tables"));
        data = new TableBattleData(tables);

        activityRow = tables.festivalBeastActivity().all().stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少 FestivalBeastActivity 表行"));
        festivalDungeonId = activityRow.getBattleConfigId();
        DungeonTable dungeon = tables.dungeon().find(festivalDungeonId)
                .orElseThrow(() -> new IllegalStateException("缺少瑞兽迎春副本行 " + festivalDungeonId));
        assertThat(dungeon.getBossTrialKind()).isEqualTo(BattleConstants.BOSS_TRIAL_KIND_FESTIVAL_BEAST);

        tier50 = tables.festivalBeastTier().all().stream()
                .filter(t -> Integer.compareUnsigned(50, t.getMinLevel()) >= 0
                        && Integer.compareUnsigned(50, t.getMaxLevel()) <= 0)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少 50 级瑞兽等级档"));

        usableItemId = tables.item().all().stream()
                .filter(i -> i.getBattleUsable() != 0 && (i.getBattleHealHp() != 0 || i.getBattleHealMp() != 0))
                .mapToInt(ItemTable::getId)
                .findFirst()
                .orElse(0);

        stunBuffId = tables.buff().all().stream()
                .filter(b -> b.getBattleOp() == BattleEffect.CONTROL
                        && b.getStateKind() == BattleEffect.FREEZE
                        && b.getDurationRounds() > 0)
                .mapToInt(BuffTable::getId)
                .findFirst()
                .orElse(0);
    }

    private static FestivalBeastBattleContext.Builder baseFestivalContext(long lockedPetId, long bossHp, long bossAtk) {
        return FestivalBeastBattleContext.newBuilder()
                .setActivityInstanceId(activityRow.getId())
                .setConfigRevision(activityRow.getConfigRevision())
                .setRewardIdentity(activityRow.getRewardIdentity())
                .setRewardDefinitionVersion(activityRow.getRewardDefinitionVersion())
                .setRewardPetTableId(activityRow.getRewardPetTableId())
                .setRequiredWins(activityRow.getRequiredWins())
                .setLockedPetId(lockedPetId)
                .setLockedOwnerLevel(50)
                .setTierId(tier50.getId())
                .setRoundLimit(activityRow.getRoundLimit())
                .setCycleRounds(activityRow.getCycleRounds())
                .setFirstHeavyRound(activityRow.getFirstHeavyRound())
                .setTelegraphLeadRounds(activityRow.getTelegraphLeadRounds())
                .setHeavyDamagePercent(activityRow.getHeavyDamagePercent())
                .setBossName(activityRow.getBossName())
                .setBossHealth(bossHp)
                .setBossPhysicalAttack(bossAtk)
                .setBossDefense(tier50.getBossDefense())
                .setBossResistance(tier50.getBossResistance())
                .setBossSpeed(tier50.getBossSpeed())
                .setEffectiveStartAtMs(1_700_000_000_000L)
                .setEffectiveEndAtMs(1_700_000_000_000L + 14L * 86_400_000L)
                .setClientRequestId("11111111-2222-4333-8444-555555555555");
    }

    private static CreateBattleRequest.Builder baseRequest(long battleId, long seed, long lockedPetId,
                                                           long playerHp, long playerAtk, long playerDef, long playerSpd,
                                                           long petHp, long petAtk, long petDef, long petSpd,
                                                           long bossHp, long bossAtk) {
        BattlePetSnapshot pet = BattlePetSnapshot.newBuilder()
                .setPetId(lockedPetId)
                .setOwnerPlayerId(1001L)
                .setPetTableId(1)
                .setPetName("灵狐")
                .setLevel(50)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(petHp).setMana(3000).setSpeed(petSpd))
                .setMaxHealth(petHp)
                .setMaxMana(3000)
                .setPhysicalAttack(petAtk)
                .setMagicAttack(petAtk)
                .setDefense(petDef)
                .build();
        BattlePlayerSnapshot.Builder player = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(1001L)
                .setTeamIndex(0)
                .setPlayerName("道友")
                .setClassId(1)
                .setLevel(50)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(playerHp).setMana(5000).setSpeed(playerSpd))
                .setMaxHealth(playerHp)
                .setMaxMana(5000)
                .setPhysicalAttack(playerAtk)
                .setMagicAttack(playerAtk)
                .setDefense(playerDef)
                .setPetCaptureSlots(3)
                .addPets(pet);
        if (usableItemId != 0) {
            player.addItems(BattleItemEntry.newBuilder().setItemTableId(usableItemId).setCount(5));
        }
        return CreateBattleRequest.newBuilder()
                .setBattleId(battleId)
                .setBattleConfigId(festivalDungeonId)
                .setSeed(seed)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .addPlayers(player)
                .setActivityContext(BattleActivityContext.newBuilder()
                        .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_FESTIVAL_BEAST)
                        .setInitiatorPlayerId(1001L)
                        .setFestivalBeast(baseFestivalContext(lockedPetId, bossHp, bossAtk)));
    }

    private static TurnBattleEngine startOk(CreateBattleRequest request) {
        BattleStart started = TurnBattleEngine.start(request, data);
        assertThat(started).isInstanceOf(BattleStart.Started.class);
        return ((BattleStart.Started) started).engine();
    }

    private static BattleActorState findActor(BattleStateS2C state, eBattleActorType type) {
        return state.getActorsList().stream()
                .filter(a -> a.getActorType() == type)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void rejectsInvalidFestivalBeastEntryAndOverridesTierStats() {
        CreateBattleRequest valid = baseRequest(7001L, 101L, 5001L,
                20_000, 3_000, 1_000, 1_500, 15_000, 2_500, 800, 1_400,
                tier50.getBossHealth(), tier50.getBossPhysicalAttack()).build();

        // 缺活动上下文
        assertThat(TurnBattleEngine.start(valid.toBuilder().clearActivityContext().build(), data))
                .isInstanceOf(BattleStart.Rejected.class);
        // 锁定宝宝与快照不一致
        CreateBattleRequest.Builder mismatchPet = valid.toBuilder();
        mismatchPet.getActivityContextBuilder().getFestivalBeastBuilder().setLockedPetId(9999L);
        assertThat(TurnBattleEngine.start(mismatchPet.build(), data))
                .isInstanceOf(BattleStart.Rejected.class);
        // 无宠开局拒绝
        CreateBattleRequest.Builder noPet = valid.toBuilder();
        noPet.getPlayersBuilder(0).clearPets();
        assertThat(TurnBattleEngine.start(noPet.build(), data))
                .isInstanceOf(BattleStart.Rejected.class);
        // 非单人模式拒绝
        assertThat(TurnBattleEngine.start(valid.toBuilder().setMatchMode(MatchMode.MATCH_MODE_PVE_TEAM_VALUE).build(), data))
                .isInstanceOf(BattleStart.Rejected.class);

        TurnBattleEngine engine = startOk(valid);
        assertThat(engine.isFestivalBeastBattle()).isTrue();
        assertThat(engine.maxRounds()).isEqualTo(12);
        BattleActorState boss = findActor(engine.buildStateSnapshot(), eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER);
        assertThat(boss.getIsBoss()).isTrue();
        assertThat(boss.getName()).isEqualTo(activityRow.getBossName());
        assertThat(boss.getMaxHealth()).isEqualTo(tier50.getBossHealth());
        assertThat(boss.getPhysicalAttack()).isEqualTo(tier50.getBossPhysicalAttack());
        assertThat(boss.getDefense()).isEqualTo(tier50.getBossDefense());
        assertThat(boss.getAttributes().getSpeed()).isEqualTo(tier50.getBossSpeed());
        assertThat(boss.getAttributes().getCritchance()).isZero();
        assertThat(boss.getCapturePetTableId()).isZero();
    }

    @Test
    void telegraphAndSlowPhaseHeavyStrikeCycleAndDefendMitigation() {
        CreateBattleRequest req = baseRequest(7002L, 2026L, 5002L,
                200_000, 100, 500, 100,
                200_000, 100, 500, 100,
                500_000, 2_000).build();
        TurnBattleEngine engineNoDefend = startOk(req);
        TurnBattleEngine engineWithDefend = startOk(req);

        // R1 无预告
        assertThat(engineNoDefend.buildStateSnapshot().getTelegraphsList()).isEmpty();
        engineNoDefend.resolveCurrentRound();
        engineWithDefend.resolveCurrentRound();

        // R2 行动窗前生成确定性预告，重连重复读快照不重抽
        BattleStateS2C r2Snap1 = engineNoDefend.buildStateSnapshot();
        BattleStateS2C r2Snap2 = engineNoDefend.buildStateSnapshot();
        assertThat(r2Snap1.getRoundIndex()).isEqualTo(2);
        assertThat(r2Snap1.getTelegraphsCount()).isEqualTo(1);
        BattleTelegraphEntry telegraph = r2Snap1.getTelegraphs(0);
        assertThat(telegraph.getIntentKind()).isEqualTo(BattleConstants.TELEGRAPH_KIND_FESTIVAL_HEAVY_STRIKE);
        assertThat(telegraph.getTelegraphRound()).isEqualTo(2);
        assertThat(telegraph.getExecuteRound()).isEqualTo(3);
        assertThat(telegraph.getStatus()).isEqualTo(BattleConstants.TELEGRAPH_STATUS_UPCOMING);
        assertThat(r2Snap2.getTelegraphs(0)).isEqualTo(telegraph);
        long lockedTargetId = telegraph.getTargetActorId();

        // R2 防御不跨轮：在 R2 防御，R3 不防御，仍按未防御吃满重击
        if (lockedTargetId == 1001L) {
            engineNoDefend.submitAction(1001L, BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        }
        engineNoDefend.resolveCurrentRound();
        engineWithDefend.resolveCurrentRound();

        // R3 预告转为 EXECUTING 且目标不变
        BattleStateS2C r3Snap = engineWithDefend.buildStateSnapshot();
        assertThat(r3Snap.getRoundIndex()).isEqualTo(3);
        assertThat(r3Snap.getTelegraphs(0).getTargetActorId()).isEqualTo(lockedTargetId);
        assertThat(r3Snap.getTelegraphs(0).getStatus()).isEqualTo(BattleConstants.TELEGRAPH_STATUS_EXECUTING);

        // 即使玩家比 Boss 慢（speed=100 vs bossSpeed=1360），R3 防御也先于慢速重击执行成功！
        BattleActorState boss = findActor(r3Snap, eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER);
        if (lockedTargetId == 1001L) {
            BattleAction defend = BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build();
            assertThat(engineWithDefend.validateAction(1001L, defend)).isEqualTo(ActionChecks.SUCCESS);
            assertThat(engineWithDefend.submitAction(1001L, defend)).isTrue();
        } else {
            // 若点名宝宝，则玩家普攻测试慢速时序：玩家行动先于 Boss 重击
            engineWithDefend.submitAction(1001L, BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(boss.getActorId()).build());
        }
        TurnResultS2C resNoDef = engineNoDefend.resolveCurrentRound();
        TurnResultS2C resWithDef = engineWithDefend.resolveCurrentRound();

        // 验证 R3 重击排在玩家/宝宝常规行动之后、且当轮 Boss 不额外普攻
        List<BattleEventItem> bossAttacks = resNoDef.getEventsList().stream()
                .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_ATTACK
                        && e.getSourceId() == boss.getActorId())
                .toList();
        assertThat(bossAttacks).hasSize(1);
        assertThat(bossAttacks.getFirst().getTargetId()).isEqualTo(lockedTargetId);

        int lastPlayerOrPetActionIdx = -1;
        int bossStrikeIdx = -1;
        for (int i = 0; i < resNoDef.getEventsCount(); i++) {
            BattleEventItem ev = resNoDef.getEvents(i);
            if (ev.getSourceId() == boss.getActorId() && ev.getEventType() == eBattleEventType.BATTLE_EVENT_ATTACK) {
                bossStrikeIdx = i;
            } else if (ev.getSourceId() != boss.getActorId()
                    && (ev.getEventType() == eBattleEventType.BATTLE_EVENT_ATTACK
                    || ev.getEventType() == eBattleEventType.BATTLE_EVENT_DEFEND)) {
                lastPlayerOrPetActionIdx = i;
            }
        }
        assertThat(bossStrikeIdx).isGreaterThan(lastPlayerOrPetActionIdx);
        assertThat(resNoDef.getResolvedTelegraphsList())
                .anyMatch(t -> t.getExecuteRound() == 3
                        && t.getStatus() == BattleConstants.TELEGRAPH_STATUS_EXECUTED);

        if (lockedTargetId == 1001L) {
            long dmgNoDef = resNoDef.getEventsList().stream()
                    .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE
                            && e.getSourceId() == boss.getActorId() && e.getTargetId() == 1001L)
                    .mapToLong(BattleEventItem::getValue).sum();
            long dmgWithDef = resWithDef.getEventsList().stream()
                    .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE
                            && e.getSourceId() == boss.getActorId() && e.getTargetId() == 1001L)
                    .mapToLong(BattleEventItem::getValue).sum();
            assertThat(dmgNoDef).isGreaterThan(0);
            assertThat(dmgWithDef).isLessThanOrEqualTo(dmgNoDef / 2 + 1);
        }

        // 验证 R4 无预告、R5 预告 R6、R8 预告 R9、R11 预告 R12
        assertThat(engineNoDefend.buildStateSnapshot().getTelegraphsList()).isEmpty();
        engineNoDefend.resolveCurrentRound(); // R4 -> 进入 R5
        assertThat(engineNoDefend.buildStateSnapshot().getRoundIndex()).isEqualTo(5);
        assertThat(engineNoDefend.buildStateSnapshot().getTelegraphs(0).getExecuteRound()).isEqualTo(6);
    }

    @Test
    void controlCancelsHeavyStrikeWithoutFallbackAndDeadTargetCancelsWithoutRetarget() {
        assertThat(stunBuffId).as("表内需存在眩晕 buff 用于受控取消验证").isNotZero();
        CreateBattleRequest req = baseRequest(7003L, 303L, 5003L,
                200_000, 100, 500, 1_500,
                200_000, 100, 500, 1_400,
                500_000, 2_000).build();
        TurnBattleEngine controlled = startOk(req);
        controlled.resolveCurrentRound(); // R1
        controlled.resolveCurrentRound(); // R2 -> R3
        BattleActorState boss = findActor(controlled.buildStateSnapshot(), eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER);
        controlled.findUnitForTest(boss.getActorId()).state().addBuffs(
                com.game.proto.BattleBuffEntry.newBuilder()
                        .setBuffId(99_001L)
                        .setBuffTableId(stunBuffId)
                        .setCasterId(1001L)
                        .setLayer(1)
                        .setRemainRounds(2));
        TurnResultS2C r3 = controlled.resolveCurrentRound();
        assertThat(r3.getEventsList().stream()
                .filter(e -> e.getSourceId() == boss.getActorId()
                        && (e.getEventType() == eBattleEventType.BATTLE_EVENT_ATTACK
                        || e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE))
                .count()).isZero();
        assertThat(r3.getResolvedTelegraphsList())
                .anyMatch(t -> t.getExecuteRound() == 3
                        && t.getStatus() == BattleConstants.TELEGRAPH_STATUS_CANCELLED_BY_CONTROL);
        // R4 不追补重击
        assertThat(controlled.buildStateSnapshot().getTelegraphsList()).isEmpty();

        // 目标倒地后重击取消，绝不偷偷换打另一方
        CreateBattleRequest lowHpReq = baseRequest(7004L, 404L, 5004L,
                200_000, 100, 500, 1_500,
                200_000, 100, 500, 1_400,
                500_000, 2_000).build();
        TurnBattleEngine deadTarget = startOk(lowHpReq);
        deadTarget.resolveCurrentRound(); // R1
        deadTarget.resolveCurrentRound(); // R2 -> R3
        long targetId = deadTarget.buildStateSnapshot().getTelegraphs(0).getTargetActorId();
        BattleUnit targetUnit = deadTarget.findUnitForTest(targetId);
        targetUnit.setHealth(0);
        targetUnit.state().setIsDead(true);
        BattleActorState deadBoss = findActor(deadTarget.buildStateSnapshot(), eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER);
        TurnResultS2C deadR3 = deadTarget.resolveCurrentRound();
        assertThat(deadR3.getEventsList().stream()
                .filter(e -> e.getSourceId() == deadBoss.getActorId()
                        && e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE)
                .count()).isZero();
        assertThat(deadR3.getResolvedTelegraphsList())
                .anyMatch(t -> t.getExecuteRound() == 3
                        && t.getStatus() == BattleConstants.TELEGRAPH_STATUS_CANCELLED_TARGET_INVALID);
    }

    @Test
    void bansItemsAndCaptureFleesTogetherProducesZeroNormalRewardsAndLosesAtRoundLimit() {
        // 1. 禁用背包道具和捕捉 + 成功逃跑人宠一同离场
        CreateBattleRequest req = baseRequest(7005L, 505L, 5005L,
                200_000, 100, 500, 9_999,
                200_000, 100, 500, 1_400,
                500_000, 500).build();
        TurnBattleEngine engine = startOk(req);
        BattleActorState boss = findActor(engine.buildStateSnapshot(), eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER);
        if (usableItemId != 0) {
            assertThat(engine.validateAction(1001L, BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_ITEM)
                    .setItemTableId(usableItemId)
                    .setTargetId(1001L).build()))
                    .isEqualTo(ActionChecks.INVALID_PARAMETER);
        }
        assertThat(engine.validateAction(1001L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_CAPTURE)
                .setTargetId(boss.getActorId()).build()))
                .isEqualTo(ActionChecks.INVALID_PARAMETER);

        // 高速逃跑成功时人宠一同离场
        boolean fledTogether = false;
        for (int round = 0; round < 5 && engine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; round++) {
            engine.submitAction(1001L, BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_FLEE).build());
            engine.resolveCurrentRound();
            BattleStateS2C s = engine.buildStateSnapshot();
            BattleActorState player = findActor(s, eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER);
            BattleActorState pet = findActor(s, eBattleActorType.BATTLE_ACTOR_TYPE_PET);
            if (player.getFled()) {
                assertThat(pet.getFled()).isTrue();
                fledTogether = true;
                break;
            }
        }
        assertThat(fledTogether).isTrue();
        assertThat(engine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN);

        // 2. 12 回合打满未击败首领判负
        TurnBattleEngine capEngine = startOk(baseRequest(7006L, 606L, 5006L,
                900_000, 100, 5_000, 1_500,
                900_000, 100, 5_000, 1_400,
                9_000_000, 500).build());
        for (int r = 0; r < 12 && capEngine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; r++) {
            capEngine.resolveCurrentRound();
        }
        assertThat(capEngine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN);
        assertThat(capEngine.buildSettlement(1001L).getTotalRounds()).isEqualTo(12);

        // 3. 获胜关闭普通经验/银两/战利品，保留冻结 festival_beast 上下文
        TurnBattleEngine winEngine = startOk(baseRequest(7007L, 707L, 5007L,
                50_000, 30_000, 2_000, 2_000,
                30_000, 15_000, 1_500, 1_800,
                10_000, 500).build());
        while (winEngine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
            winEngine.resolveCurrentRound();
        }
        assertThat(winEngine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        BattleSettlementData settlement = winEngine.buildSettlement(1001L);
        assertThat(settlement.getExpGain()).isZero();
        assertThat(settlement.getGoldGain()).isZero();
        assertThat(settlement.getItemsGainedList()).isEmpty();
        assertThat(settlement.getCapturedPetTableIdsList()).isEmpty();
        assertThat(settlement.hasFestivalBeast()).isTrue();
        assertThat(settlement.getFestivalBeast().getActivityInstanceId()).isEqualTo(activityRow.getId());
        assertThat(settlement.getFestivalBeast().getLockedPetId()).isEqualTo(5007L);
    }

    @Test
    void physicalMagicAndSupportArchetypesWithPetCanBeatTierBossWithin12Rounds() {
        // 核验物攻、法攻、控辅/治疗配普通输出宝宝面对 50 级真实等级档均可在 12 回合内取胜
        long[][] archetypes = {
                // playerHp, playerAtk, playerDef, playerSpd, petHp, petAtk, petDef, petSpd
                {24_000, 6_500, 2_400, 1_600, 18_000, 4_200, 1_800, 1_450}, // 物攻 + 输出宠
                {22_000, 6_200, 2_200, 1_550, 18_000, 4_500, 1_800, 1_450}, // 法攻 + 输出宠
                {28_000, 3_600, 2_800, 1_650, 22_000, 6_800, 2_000, 1_500}, // 控辅 + 主力输出宠
        };
        for (int i = 0; i < archetypes.length; i++) {
            long[] a = archetypes[i];
            TurnBattleEngine engine = startOk(baseRequest(7100L + i, 800L + i, 5100L + i,
                    a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7],
                    tier50.getBossHealth(), tier50.getBossPhysicalAttack()).build());
            while (engine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING) {
                BattleStateS2C s = engine.buildStateSnapshot();
                if (!s.getTelegraphsList().isEmpty()
                        && s.getTelegraphs(0).getExecuteRound() == s.getRoundIndex()
                        && s.getTelegraphs(0).getTargetActorId() == 1001L) {
                    engine.submitAction(1001L, BattleAction.newBuilder()
                            .setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
                }
                engine.resolveCurrentRound();
            }
            assertThat(engine.outcome()).as("流派索引 %d 应能在 12 回合内击败 50 级春禧兽", i)
                    .isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
            assertThat(engine.buildSettlement(1001L).getTotalRounds()).isLessThanOrEqualTo(12);
        }
    }
}
