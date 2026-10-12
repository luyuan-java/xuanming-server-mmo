package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPet;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.countEvents;
import static com.game.battle.engine.TestBattles.firstEvent;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.DUNGEON_CONFIG;
import static com.game.battle.engine.TestTables.DUNGEON_WITH_DROP;
import static com.game.battle.engine.TestTables.ITEM_MANA_POTION;
import static com.game.battle.engine.TestTables.ITEM_NOT_BATTLE_USABLE;
import static com.game.battle.engine.TestTables.ITEM_POTION;
import static com.game.battle.engine.TestTables.MONSTER_ID;
import static com.game.battle.engine.TestTables.MONSTER_WITH_DROP;
import static com.game.battle.engine.TestTables.PET_A;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.PLAYER_C;
import static com.game.battle.engine.TestTables.SKILL_NUKE;
import static com.game.battle.engine.TestTables.SKILL_POISON;
import static com.game.battle.engine.TestTables.SUCCESS;
import static com.game.battle.engine.TestTables.standard;
import static com.game.battle.engine.TestTables.withDrops;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_ATTACK;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_FLEE;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_ITEM;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_DEATH;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_ITEM;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_ONGOING;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleActorState;
import com.game.proto.BattleEventItem;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActorType;
import com.game.table.BagErrorTip;
import com.game.table.CommonErrorTip;
import com.game.table.SkillErrorTip;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 道具、击杀簿、掉落与结算（规格 §6）。
 *
 * <p>前 16 个方法逐条移植基线 {@code turn_battle_engine_test.cpp}（【C++ 断言】），方法上方注明基线用例名与行号；
 * 其后标「Java 追加」的用例按规格 §6 的条文手算。
 */
class TurnBattleSettlementTest {

    private static final int PVE_SOLO = BattleConstants.MATCH_MODE_PVE_SOLO;
    private static final int PVE_TEAM = BattleConstants.MATCH_MODE_PVE_TEAM;
    private static final int PVP_1V1 = 3;
    private static final int INVALID_TABLE_ID = CommonErrorTip.common_error.kInvalidTableId_VALUE;
    private static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final int INVALID_TARGET_ID = SkillErrorTip.skill_error.kSkillInvalidTargetId_VALUE;
    private static final int INVALID_TARGET = SkillErrorTip.skill_error.kSkillInvalidTarget_VALUE;
    private static final int INSUFFICIENT_ITEMS = BagErrorTip.bag_error.kBagInsufficientItems_VALUE;

    private static BattleItemEntry item(int itemTableId, long count) {
        return BattleItemEntry.newBuilder().setItemTableId(itemTableId).setCount(count).build();
    }

    private static BattleMonsterDefeat defeat(int monsterConfigId) {
        return BattleMonsterDefeat.newBuilder().setMonsterConfigId(monsterConfigId).setCount(1).build();
    }

    // 基线 ItemHealsAndConsumptionGoesIntoSettlement（test.cpp:660-701）【C++ 断言】
    @Test
    void 道具回血_消耗记进结算() {
        CreateBattleRequest.Builder request = request(9018, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 50, 200, 0, 100, 0, 120);
        snapshot.addItems(item(ITEM_POTION, 2));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 第 1 次使用：50 → 150，回 100
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_A, 0, ITEM_POTION))).isTrue();
        TurnResultS2C result = engine.resolveCurrentRound();
        BattleEventItem itemEvent = firstEvent(result, BATTLE_EVENT_ITEM);
        assertThat(itemEvent).isNotNull();
        assertThat(itemEvent.getItemTableId()).isEqualTo(ITEM_POTION);
        assertThat(itemEvent.getValue()).isEqualTo(100);
        assertThat(itemEvent.getTargetHealthAfter()).isEqualTo(150);

        // 第 2 次使用：回满封顶 200。第 1 回合怪物普攻会打掉一些，所以实际回复量 = 200 − 回合初血量
        BattleActorState afterFirstRound = stateActor(result.getState(), PLAYER_A);
        assertThat(afterFirstRound).isNotNull();
        long healthBeforeSecond = afterFirstRound.getAttributes().getHealth();
        assertThat(healthBeforeSecond).isGreaterThan(100);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_A, 0, ITEM_POTION));
        result = engine.resolveCurrentRound();
        BattleEventItem secondItemEvent = firstEvent(result, BATTLE_EVENT_ITEM);
        assertThat(secondItemEvent).isNotNull();
        assertThat(secondItemEvent.getValue()).isEqualTo(200 - healthBeforeSecond);
        assertThat(secondItemEvent.getTargetHealthAfter()).isEqualTo(200);

        // 副本已用尽：第 3 次提交不落账
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_A, 0, ITEM_POTION))).isFalse();

        // 结算账本：消耗 2 个，交由 scene 按实际持有校验扣除
        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getItemsConsumedList()).containsExactly(item(ITEM_POTION, 2));
    }

    // 基线 RewardsAccumulateAcrossAllMonstersInGroup（test.cpp:1155-1206）【C++ 断言】
    @Test
    void 多怪副本按击杀逐只累加奖励() {
        MemoryBattleData data = standard();
        int monsterX = 7004;
        int monsterY = 7005;
        data.addMonster(monsterX).setHealth(60).setStrength(1).setSpeed(36).setExpReward(10).setGoldReward(5);
        data.addMonster(monsterY).setHealth(60).setStrength(1).setSpeed(24).setExpReward(15).setGoldReward(7);
        data.setDungeonMonsters(DUNGEON_CONFIG, monsterX, monsterY);

        CreateBattleRequest.Builder request = request(9042, PVE_SOLO, 13);
        // 玩家力量 20 → 普攻 10 × (1 + 2.0) = 30（怪物护甲 0、受伤比例 1），每只 2 刀
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, data);

        int rounds = 0;
        while (engine.outcome() == BATTLE_OUTCOME_ONGOING && rounds < 30) {
            long target = 0;
            for (BattleActorState actor : engine.buildStateSnapshot().getActorsList()) {
                if (actor.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER && !actor.getIsDead()) {
                    target = actor.getActorId();
                    break;
                }
            }
            assertThat(target).isNotZero();
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, target));
            engine.resolveCurrentRound();
            rounds++;
        }
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(rounds).as("2 只 × 2 刀，确定性").isEqualTo(4);

        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getExpGain()).isEqualTo(25);
        assertThat(settlement.getGoldGain()).isEqualTo(12);
        assertThat(settlement.getDefeatedMonstersList()).containsExactly(defeat(monsterX), defeat(monsterY));
        assertThat(engine.buildSettlement(PLAYER_A).getDefeatedMonstersCount()).isEqualTo(2);
    }

    // 基线 FledPlayerOnWinningTeamGetsNoReward（test.cpp:1210-1259）【C++ 断言】
    @Test
    void 胜方里逃跑的玩家不发奖() {
        MemoryBattleData data = standard();
        int rewardMonster = 7006;
        // 力量 1 每下只打几点，不会误杀；速度 12 最慢：B 的逃跑成功率被夹到上限 0.95
        data.addMonster(rewardMonster).setHealth(150).setStrength(1).setSpeed(12).setExpReward(40).setGoldReward(20);
        data.setDungeonMonsters(DUNGEON_CONFIG, rewardMonster);

        CreateBattleRequest.Builder request = request(9043, PVE_TEAM, 21);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 360);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 10, 0, 1440);
        TurnBattleEngine engine = TestBattles.start(request, data);

        // 第一阶段：A 防御拖时间，B 反复逃跑直到成功（0.95 / 次，种子固定 → 确定性）
        boolean fled = false;
        for (int round = 0; round < 5 && !fled; round++) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
            engine.submitAction(PLAYER_B, action(BATTLE_ACTION_FLEE));
            engine.resolveCurrentRound();
            BattleActorState actorB = stateActor(engine.buildStateSnapshot(), PLAYER_B);
            assertThat(actorB).isNotNull();
            fled = actorB.getFled();
        }
        assertThat(fled).as("B 应已逃离战斗").isTrue();
        assertThat(engine.outcome()).as("A 还在，战斗不该结束").isEqualTo(BATTLE_OUTCOME_ONGOING);

        // 第二阶段：A 独自打死怪物
        for (int round = 0; round < 20 && engine.outcome() == BATTLE_OUTCOME_ONGOING; round++) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
            engine.resolveCurrentRound();
        }
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);

        BattleSettlementData settlementA = engine.buildSettlement(PLAYER_A);
        assertThat(settlementA.getFled()).isFalse();
        assertThat(settlementA.getExpGain()).isEqualTo(40);
        assertThat(settlementA.getGoldGain()).isEqualTo(20);

        BattleSettlementData settlementB = engine.buildSettlement(PLAYER_B);
        assertThat(settlementB.getFled()).isTrue();
        assertThat(settlementB.getDefeatedMonstersCount()).isZero();
        assertThat(settlementA.getDefeatedMonstersList()).containsExactly(defeat(rewardMonster));
        assertThat(settlementB.getExpGain()).as("逃跑玩家不能吃队伍的胜利奖励").isZero();
        assertThat(settlementB.getGoldGain()).isZero();
    }

    // 基线 DeadPlayerOnWinningTeamGetsNoReward（test.cpp:1263-1312）【C++ 断言】
    @Test
    void 胜方里阵亡的玩家不发奖() {
        MemoryBattleData data = standard();
        int bruiserMonster = 7007;
        // 400 血够 A 慢慢磨；普攻 10 × (1 + 2.0) = 30，足以一击带走 1 血的 B
        data.addMonster(bruiserMonster).setHealth(400).setStrength(20).setSpeed(12).setExpReward(60).setGoldReward(30);
        data.setDungeonMonsters(DUNGEON_CONFIG, bruiserMonster);

        CreateBattleRequest.Builder request = request(9044, PVE_TEAM, 33);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 360);
        addPlayer(request, PLAYER_B, 0, 1, 1, 0, 0, 0, 24);
        TurnBattleEngine engine = TestBattles.start(request, data);

        // 第一阶段：A 防御拖回合，等怪物随机选到 B 把他打死（种子固定 → 确定性）
        boolean dead = false;
        for (int round = 0; round < 15 && !dead && engine.outcome() == BATTLE_OUTCOME_ONGOING; round++) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
            engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            engine.resolveCurrentRound();
            BattleActorState actorB = stateActor(engine.buildStateSnapshot(), PLAYER_B);
            assertThat(actorB).isNotNull();
            dead = actorB.getIsDead();
        }
        assertThat(dead).as("B 应已阵亡").isTrue();
        assertThat(engine.outcome()).as("A 还活着，战斗不该结束").isEqualTo(BATTLE_OUTCOME_ONGOING);

        // 第二阶段：A 独自打死怪物
        for (int round = 0; round < 30 && engine.outcome() == BATTLE_OUTCOME_ONGOING; round++) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
            engine.resolveCurrentRound();
        }
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);

        BattleSettlementData settlementA = engine.buildSettlement(PLAYER_A);
        assertThat(settlementA.getIsDead()).isFalse();
        assertThat(settlementA.getExpGain()).isEqualTo(60);
        assertThat(settlementA.getGoldGain()).isEqualTo(30);

        BattleSettlementData settlementB = engine.buildSettlement(PLAYER_B);
        assertThat(settlementB.getIsDead()).isTrue();
        assertThat(settlementB.getDefeatedMonstersCount()).isZero();
        assertThat(settlementA.getDefeatedMonstersList()).containsExactly(defeat(bruiserMonster));
        assertThat(settlementB.getExpGain()).as("阵亡玩家不吃队伍的胜利奖励").isZero();
        assertThat(settlementB.getGoldGain()).isZero();
    }

    // 基线 EveryQualifyingTeamMemberGetsFullReward（test.cpp:1316-1348）【C++ 断言】
    @Test
    void 组队每个合格成员各得全额奖励() {
        MemoryBattleData data = standard();
        int sharedMonster = 7009;
        data.addMonster(sharedMonster).setHealth(120).setStrength(1).setSpeed(12).setExpReward(80).setGoldReward(40);
        data.setDungeonMonsters(DUNGEON_CONFIG, sharedMonster);

        CreateBattleRequest.Builder request = request(9055, PVE_TEAM, 7);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 360);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 20, 10, 0, 300);
        TurnBattleEngine engine = TestBattles.start(request, data);

        int rounds = 0;
        while (engine.outcome() == BATTLE_OUTCOME_ONGOING && rounds < 30) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
            engine.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
            engine.resolveCurrentRound();
            rounds++;
        }
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);

        BattleSettlementData settlementA = engine.buildSettlement(PLAYER_A);
        BattleSettlementData settlementB = engine.buildSettlement(PLAYER_B);
        assertThat(settlementA.getExpGain()).isEqualTo(80);
        assertThat(settlementA.getGoldGain()).isEqualTo(40);
        assertThat(settlementB.getExpGain()).as("队友不该被平分掉奖励").isEqualTo(80);
        assertThat(settlementB.getGoldGain()).isEqualTo(40);
    }

    // 基线 PetFinalStateGoesIntoOwnerSettlement（test.cpp:1608-1623）【C++ 断言】
    @Test
    void 宝宝终值随主人结算() {
        CreateBattleRequest.Builder request = request(9403, PVE_SOLO, 55);
        BattlePlayerSnapshot.Builder owner = addPlayer(request, PLAYER_A, 0, 1000, 1000, 5, 0, 0, 120);
        addPet(owner, PET_A, 400, 400, 4, 360);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID))).isTrue();
        engine.resolveCurrentRound();

        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getPetsCount()).isEqualTo(1);
        assertThat(settlement.getPets(0).getPetId()).isEqualTo(PET_A);
        assertThat(settlement.getPets(0).getHealth()).isLessThanOrEqualTo(400);
        assertThat(settlement.getPets(0).getIsDead()).isFalse();
    }

    private static MemoryBattleData twoWeakMonsters(int xId, int yId, int xExp, int yExp, int xGold, int yGold) {
        MemoryBattleData data = standard();
        data.addMonster(xId).setHealth(8).setStrength(1).setSpeed(12).setExpReward(xExp).setGoldReward(xGold);
        data.addMonster(yId).setHealth(8).setStrength(1).setSpeed(12).setExpReward(yExp).setGoldReward(yGold);
        data.setDungeonMonsters(DUNGEON_CONFIG, xId, yId);
        return data;
    }

    // 基线 SettlementKeepsActualKillOrderAndRepeatedReadIsStable（test.cpp:1686-1717）【C++ 断言】
    @Test
    void 击杀簿按实际击杀顺序_重复读取字节相同() {
        int xId = 7701;
        int yId = 7702;
        MemoryBattleData data = twoWeakMonsters(xId, yId, 10, 15, 5, 7);
        CreateBattleRequest.Builder request = request(9701, PVE_SOLO, 7);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, data);
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID + 1))).isTrue();
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_ONGOING);
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID))).isTrue();
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);

        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getDefeatedMonstersList()).containsExactly(defeat(yId), defeat(xId));
        assertThat(settlement.getGoldGain()).isEqualTo(12);
        assertThat(settlement.getExpGain()).isEqualTo(25);
        assertThat(engine.buildSettlement(PLAYER_A).toByteArray()).isEqualTo(settlement.toByteArray());
    }

    // 基线 SettlementKeepsPoisonAndBurnKillBeforeLaterSkillKill（test.cpp:1719-1752）【C++ 断言】
    @Test
    void 毒与灼烧的击杀按施加者记账且保持击杀顺序() {
        for (int buffType : new int[] {BattleConstants.BUFF_TYPE_POISON, BattleConstants.BUFF_TYPE_BURN}) {
            int xId = 7711;
            int yId = 7712;
            MemoryBattleData data = twoWeakMonsters(xId, yId, 0, 0, 3, 3);
            data.addBuff(TestTables.BUFF_POISON).setBuffType(buffType);
            CreateBattleRequest.Builder request = request(9702, PVE_SOLO, 7);
            addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 240);
            TurnBattleEngine engine = TestBattles.start(request, data);
            assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID + 1, SKILL_POISON))).isTrue();
            TurnResultS2C first = engine.resolveCurrentRound();
            assertThat(countEvents(first, BATTLE_EVENT_DEATH)).as("buff_type=%d", buffType).isEqualTo(1);
            assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_NUKE))).isTrue();
            engine.resolveCurrentRound();
            assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);

            BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
            assertThat(settlement.getDefeatedMonstersList()).as("buff_type=%d", buffType)
                    .extracting(BattleMonsterDefeat::getMonsterConfigId).containsExactly(yId, xId);
            assertThat(settlement.getGoldGain()).isEqualTo(6);
            assertThat(engine.buildSettlement(PLAYER_A).toByteArray()).isEqualTo(settlement.toByteArray());
        }
    }

    // 基线 SettlementDoesNotCreditUnknownOrMonsterSourceDeath（test.cpp:1754-1784）【C++ 断言】
    @Test
    void 来源为0或怪物的死亡不记击杀() {
        for (long sourceId : new long[] {0, MONSTER_ID}) {
            int xId = 7721;
            int yId = 7722;
            MemoryBattleData data = twoWeakMonsters(xId, yId, 0, 0, 3, 3);
            CreateBattleRequest.Builder request = request(9703, PVE_SOLO, 7);
            addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 240);
            TurnBattleEngine engine = TestBattles.start(request, data);
            assertThat(engine.addBuffForTest(MONSTER_ID + 1, TestTables.BUFF_POISON, sourceId)).isTrue();
            assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND))).isTrue();
            TurnResultS2C first = engine.resolveCurrentRound();
            assertThat(countEvents(first, BATTLE_EVENT_DEATH)).as("source=%s", Long.toUnsignedString(sourceId)).isEqualTo(1);
            assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID))).isTrue();
            engine.resolveCurrentRound();
            assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);

            BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
            assertThat(settlement.getDefeatedMonstersList()).containsExactly(defeat(xId));
            assertThat(settlement.getGoldGain()).isEqualTo(3);
        }
    }

    // 基线 VictoryRollsMonsterDropsIntoSettlement（test.cpp:1884-1904）【C++ 断言】
    @Test
    void 胜利时掷掉落进结算_重复读取一致() {
        CreateBattleRequest.Builder request = request(9301, PVE_SOLO, 42).setBattleConfigId(DUNGEON_WITH_DROP);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 500, 0, 0, 500);
        TurnBattleEngine engine = TestBattles.start(request, withDrops());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK));
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);

        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getItemsGainedList()).containsExactly(item(ITEM_POTION, 2));
        // 掉落只在终局摇一次：重复读取结算必须完全一致（节点侧 outbox 会重投，读多次）
        BattleSettlementData again = engine.buildSettlement(PLAYER_A);
        assertThat(again.getItemsGainedList()).containsExactly(item(ITEM_POTION, 2));
    }

    // 基线 DropRateZeroNeverDrops（test.cpp:1906-1920）【C++ 断言】
    @Test
    void 掉率为0永不掉落() {
        MemoryBattleData data = withDrops();
        data.addMonster(MONSTER_WITH_DROP).getDropBuilder(0).setDropRate(0);

        CreateBattleRequest.Builder request = request(9302, PVE_SOLO, 42).setBattleConfigId(DUNGEON_WITH_DROP);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 500, 0, 0, 500);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK));
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(engine.buildSettlement(PLAYER_A).getItemsGainedCount()).isZero();
    }

    // 基线 ItemEffectComesFromItemTableAndManaPotionRestoresMana（test.cpp:1922-1945）【C++ 断言】
    @Test
    void 道具效果读表_回蓝药回蓝() {
        MemoryBattleData data = standard();
        data.addItem(ITEM_MANA_POTION).setBattleUsable(1).setBattleHealMp(30);

        CreateBattleRequest.Builder request = request(9303, PVE_SOLO, 7);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 500);
        snapshot.getBaseAttributesBuilder().setMana(10);
        snapshot.setMaxMana(100);
        snapshot.addItems(item(ITEM_MANA_POTION, 1));
        TurnBattleEngine engine = TestBattles.start(request, data);

        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_A, 0, ITEM_MANA_POTION))).isTrue();
        TurnResultS2C result = engine.resolveCurrentRound();
        BattleEventItem itemEvent = firstEvent(result, BATTLE_EVENT_ITEM);
        assertThat(itemEvent).isNotNull();
        assertThat(itemEvent.getValue()).as("纯回蓝药：value 取回蓝量").isEqualTo(30);
        assertThat(itemEvent.getTargetManaAfter()).as("10 + 30").isEqualTo(40);
    }

    // 基线 ValidateActionRejectsNonBattleItemAndUnknownItem（test.cpp:1947-1969）【C++ 断言】
    @Test
    void 校验拒绝非战斗道具与未知道具() {
        MemoryBattleData data = standard();
        data.addItem(ITEM_NOT_BATTLE_USABLE).setBattleUsable(0); // 不是战斗消耗品

        CreateBattleRequest.Builder request = request(9304, PVE_SOLO, 7);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 500);
        snapshot.addItems(item(ITEM_NOT_BATTLE_USABLE, 5));
        TurnBattleEngine engine = TestBattles.start(request, data);

        // 表里没有这个 id
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, 9999))).isEqualTo(INVALID_TABLE_ID);
        // 表里有但不可战斗使用
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, ITEM_NOT_BATTLE_USABLE))).isEqualTo(INVALID_PARAMETER);
        // 校验不过的行动不落账：单人局因此不会就绪
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, ITEM_NOT_BATTLE_USABLE))).isFalse();
    }

    // 基线 ItemCanTargetTeammateButNotEnemy（test.cpp:1971-2007）【C++ 断言】
    @Test
    void 道具可以给队友用_不能给敌方用() {
        CreateBattleRequest.Builder request = request(9305, PVE_TEAM, 7);
        BattlePlayerSnapshot.Builder healer = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 500);
        addPlayer(request, PLAYER_B, 0, 50, 1000, 0, 100, 0, 10);
        healer.addItems(item(ITEM_POTION, 2));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 给队友用药：合法
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_B, 0, ITEM_POTION))).isEqualTo(SUCCESS);
        // 给敌方用药：拒绝
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, BattleConstants.MONSTER_ACTOR_ID_BASE, 0, ITEM_POTION)))
                .isEqualTo(INVALID_TARGET);
        // 不存在的目标：拒绝
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 123456, 0, ITEM_POTION))).isEqualTo(INVALID_TARGET_ID);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_B, 0, ITEM_POTION));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();
        BattleEventItem itemEvent = firstEvent(result, BATTLE_EVENT_ITEM);
        assertThat(itemEvent).isNotNull();
        assertThat(itemEvent.getSourceId()).isEqualTo(PLAYER_A);
        assertThat(itemEvent.getTargetId()).as("药落在队友身上").isEqualTo(PLAYER_B);
        assertThat(itemEvent.getValue()).isEqualTo(100);
        // 消耗记在用药者账上，队友账本为空
        assertThat(engine.buildSettlement(PLAYER_A).getItemsConsumedCount()).isEqualTo(1);
        assertThat(engine.buildSettlement(PLAYER_A).getItemsConsumed(0).getCount()).isEqualTo(1);
        assertThat(engine.buildSettlement(PLAYER_B).getItemsConsumedCount()).isZero();
    }

    // 基线 PvpItemUseIsCappedPerBattle（test.cpp:2009-2031）【C++ 断言】
    @Test
    void PVP每场用药有上限() {
        CreateBattleRequest.Builder request = request(9306, PVP_1V1, 7);
        BattlePlayerSnapshot.Builder attacker = addPlayer(request, PLAYER_A, 0, 1000, 5000, 0, 100, 0, 500);
        addPlayer(request, PLAYER_B, 1, 1000, 5000, 0, 100, 0, 10);
        attacker.addItems(item(ITEM_POTION, BattleConstants.MAX_ITEM_USES_PER_BATTLE_PVP + 3));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        for (int used = 0; used < BattleConstants.MAX_ITEM_USES_PER_BATTLE_PVP; used++) {
            assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, ITEM_POTION)))
                    .as("第 %d 次用药应当放行", used).isEqualTo(SUCCESS);
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, ITEM_POTION));
            engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            engine.resolveCurrentRound();
        }
        // 副本里还有药，但 PVP 限次拒绝
        assertThat(engine.selfItems(PLAYER_A)).isNotEmpty();
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, ITEM_POTION))).isEqualTo(INVALID_PARAMETER);
    }

    // 基线 SelfItemsReflectsRemainingCopyAndDropsExhaustedEntries（test.cpp:2033-2054）【C++ 断言】
    @Test
    void 剩余道具反映副本余量_用光的条目不下发() {
        CreateBattleRequest.Builder request = request(9307, PVE_SOLO, 7);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 100, 1000, 0, 100, 0, 500);
        snapshot.addItems(item(ITEM_POTION, 1));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        List<BattleItemEntry> before = engine.selfItems(PLAYER_A);
        assertThat(before).containsExactly(item(ITEM_POTION, 1));

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, ITEM_POTION));
        engine.resolveCurrentRound();

        // 用光的条目不再下发（客户端按「没有这一项」处理）
        assertThat(engine.selfItems(PLAYER_A)).isEmpty();
        // 不在本局的玩家：空表
        assertThat(engine.selfItems(PLAYER_C)).isEmpty();
    }

    // ---- Java 追加（规格 §6） ----

    // 规格 §6.3 / D6：selfItems 去掉 count 为 0 的条目，按 item_table_id 升序，同 id 保持快照顺序（稳定排序）；
    // 原请求保持不可变，道具余量只扣在引擎自己的账本上
    @Test
    void 剩余道具按id升序稳定排序() {
        CreateBattleRequest.Builder request = request(9640, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 100, 1000, 0, 100, 0, 500);
        snapshot.addItems(item(ITEM_MANA_POTION, 1));
        snapshot.addItems(item(ITEM_POTION, 2));
        snapshot.addItems(item(ITEM_NOT_BATTLE_USABLE, 0));
        snapshot.addItems(item(ITEM_POTION, 3));
        CreateBattleRequest built = request.build();
        BattleStart start = TurnBattleEngine.start(built, standard());
        assertThat(start).isInstanceOf(BattleStart.Started.class);
        TurnBattleEngine engine = ((BattleStart.Started) start).engine();

        assertThat(engine.selfItems(PLAYER_A)).containsExactly(item(ITEM_POTION, 2), item(ITEM_POTION, 3), item(ITEM_MANA_POTION, 1));

        // 扣的是第一个 count > 0 的同 id 条目
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, ITEM_POTION));
        engine.resolveCurrentRound();
        assertThat(engine.selfItems(PLAYER_A)).containsExactly(item(ITEM_POTION, 1), item(ITEM_POTION, 3), item(ITEM_MANA_POTION, 1));
        assertThat(built.getPlayers(0).getItems(1).getCount()).as("原请求不被改写").isEqualTo(2);
    }

    // 规格 §6.1：道具校验链（1001 / 1005 / 1005 / 6007）；满血用药照样扣药、出 value = 0 的事件（§11.1 第 9 条）
    @Test
    void 道具校验链_满血用药照样消耗() {
        MemoryBattleData data = standard();
        data.addItem(304).setBattleUsable(1);                 // 两列效果都是 0
        data.addItem(305).setBattleUsable(1).setBattleHealHp(50); // 本人没有这个道具
        CreateBattleRequest.Builder request = request(9641, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 500);
        snapshot.addItems(item(304, 3)).addItems(item(ITEM_MANA_POTION, 0)).addItems(item(ITEM_POTION, 1));
        data.addItem(ITEM_MANA_POTION).setBattleUsable(1).setBattleHealMp(30);
        TurnBattleEngine engine = TestBattles.start(request, data);

        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, 304))).isEqualTo(INVALID_PARAMETER);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, 305))).isEqualTo(INSUFFICIENT_ITEMS);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, 0, 0, ITEM_MANA_POTION))).as("count 为 0").isEqualTo(INSUFFICIENT_ITEMS);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_A, 0, ITEM_POTION))).isEqualTo(SUCCESS);

        // 满血用药（玩家速度 500 先手）：药照样扣，事件 value = 0
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_A, 0, ITEM_POTION));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(EngineTestSupport.describe(result.getEvents(0))).isEqualTo("g1 ITEM 5001->5001 item301 hp1000");
        assertThat(engine.buildSettlement(PLAYER_A).getItemsConsumedList()).containsExactly(item(ITEM_POTION, 1));
        assertThat(engine.selfItems(PLAYER_A)).containsExactly(item(304, 3));
    }

    // 规格 §6.2 第 1 步：提交时合法、出手时目标已死的道具改为对自己用，从不降级为普攻
    @Test
    void 道具目标出手前阵亡时改为对自己用() {
        CreateBattleRequest.Builder request = request(9642, PVE_TEAM, 1);
        BattlePlayerSnapshot.Builder healer = addPlayer(request, PLAYER_A, 0, 500, 1000, 0, 100, 0, 10);
        healer.addItems(item(ITEM_POTION, 1));
        addPlayer(request, PLAYER_B, 0, 50, 1000, 0, 100, 0, 5);
        addPlayer(request, PLAYER_C, 0, 1000, 1000, 0, 100, 0, 600);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_B, 0, ITEM_POTION));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        // C 先手用单体技能秒掉队友 B（单体技能不查阵营，基线行为 N1）
        engine.submitAction(PLAYER_C, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_NUKE));
        TurnResultS2C result = engine.resolveCurrentRound();

        assertThat(stateActor(result.getState(), PLAYER_B).getIsDead()).isTrue();
        BattleEventItem itemEvent = firstEvent(result, BATTLE_EVENT_ITEM);
        assertThat(itemEvent).isNotNull();
        assertThat(itemEvent.getSourceId()).isEqualTo(PLAYER_A);
        assertThat(itemEvent.getTargetId()).isEqualTo(PLAYER_A);
        assertThat(itemEvent.getItemTableId()).isEqualTo(ITEM_POTION);
        assertThat(itemEvent.getValue()).isEqualTo(100);
        assertThat(result.getEventsList()).noneMatch(e -> e.getSourceId() == PLAYER_A
                && e.getEventType() == com.game.proto.eBattleEventType.BATTLE_EVENT_ATTACK);
        assertThat(engine.buildSettlement(PLAYER_A).getItemsConsumedList()).containsExactly(item(ITEM_POTION, 1));
    }

    // 规格 §6.6 第 3 步：不在本局的玩家只带 battle_id / player_id / outcome / total_rounds
    @Test
    void 不在本局的玩家结算只带公共字段() {
        CreateBattleRequest.Builder request = request(9643, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        engine.resolveCurrentRound();

        assertThat(engine.buildSettlement(424242)).isEqualTo(BattleSettlementData.newBuilder()
                .setBattleId(9643)
                .setPlayerId(424242)
                .setOutcome(BATTLE_OUTCOME_ONGOING)
                .setTotalRounds(1)
                .build());
    }

    // 规格 §3.10 / §11.4：兜底怪（monster_table_id 0）被杀不记击杀、不掉落、无经验；表行存在但 health 为 0 的怪用默认属性，击杀照样记账
    @Test
    void 兜底怪击杀不记账_表行health为0的怪击杀记账() {
        // 兜底怪
        CreateBattleRequest.Builder fallback = request(9644, PVE_SOLO, 1);
        addPlayer(fallback, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine fallbackEngine = TestBattles.start(fallback, standard());
        fallbackEngine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_NUKE));
        fallbackEngine.resolveCurrentRound();
        assertThat(fallbackEngine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        BattleSettlementData fallbackSettlement = fallbackEngine.buildSettlement(PLAYER_A);
        assertThat(fallbackSettlement.getDefeatedMonstersList()).isEmpty();
        assertThat(fallbackSettlement.getExpGain()).isZero();
        assertThat(fallbackSettlement.getItemsGainedList()).isEmpty();

        // 表行 health 0：属性取默认值，monster_table_id 仍是表 id
        MemoryBattleData data = standard();
        data.addMonster(7900).setExpReward(9).setGoldReward(4).setSpeed(999);
        data.setDungeonMonsters(DUNGEON_CONFIG, 7900);
        CreateBattleRequest.Builder zeroHealth = request(9645, PVE_SOLO, 1);
        addPlayer(zeroHealth, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(zeroHealth, data);
        BattleActorState monster = stateActor(engine.buildStateSnapshot(), MONSTER_ID);
        assertThat(monster.getMonsterTableId()).isEqualTo(7900);
        assertThat(monster.getAttributes().getSpeed()).as("走默认值，不读表的 999").isEqualTo(BattleConstants.MONSTER_DEFAULT_SPEED);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_NUKE));
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getDefeatedMonstersList()).containsExactly(defeat(7900));
        assertThat(settlement.getExpGain()).isEqualTo(9);
        assertThat(settlement.getGoldGain()).isEqualTo(4);
    }

    // 规格 §3.10：宝宝的击杀记在主人名下；结算带宝宝终值
    @Test
    void 宝宝的击杀记在主人名下() {
        MemoryBattleData data = standard();
        data.addMonster(7901).setHealth(8).setStrength(1).setSpeed(12).setExpReward(11).setGoldReward(6);
        data.setDungeonMonsters(DUNGEON_CONFIG, 7901);
        CreateBattleRequest.Builder request = request(9646, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder owner = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        addPet(owner, PET_A, 400, 400, 20, 360);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();
        // 宝宝先手：普攻 10 × (1 + 2.0) = 30 ≥ 8，一刀秒
        assertThat(EngineTestSupport.describeAll(result)).startsWith(
                "g1 ATTACK P0->M0",
                "g1 DAMAGE P0->M0 v8",
                "g1 DEATH M0->M0");
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getDefeatedMonstersList()).containsExactly(defeat(7901));
        assertThat(settlement.getExpGain()).isEqualTo(11);
        assertThat(settlement.getPetsCount()).isEqualTo(1);
        assertThat(settlement.getPets(0).getPetId()).isEqualTo(PET_A);
        assertThat(settlement.getPets(0).getHealth()).isEqualTo(400);
        assertThat(settlement.getPlayerTeamIndex()).isZero();
        assertThat(settlement.getHealth()).isEqualTo(1000);
    }
}
