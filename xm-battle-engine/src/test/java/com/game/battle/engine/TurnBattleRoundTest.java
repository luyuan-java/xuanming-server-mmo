package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.countEvents;
import static com.game.battle.engine.TestBattles.firstEvent;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.DUNGEON_CONFIG;
import static com.game.battle.engine.TestTables.MONSTER_ID;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.SKILL_DAMAGE;
import static com.game.battle.engine.TestTables.SKILL_NUKE;
import static com.game.battle.engine.TestTables.standard;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_ATTACK;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_FLEE;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_ATTACK;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_DAMAGE;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_DEATH;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_FLEE;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_MISS;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_ONGOING;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.BattleActorState;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleOutcome;
import com.game.table.MonsterTable;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 回合结算（规格 §3）：确定性、出手序、默认行动、普攻 / 防御 / 逃跑、伤害公式、怪物属性、胜负与回合上限、事件分组。
 *
 * <p>逐条移植基线 {@code turn_battle_engine_test.cpp} 的对应用例，断言都是【C++ 断言】级；方法上方注明基线用例名与行号。
 * 这些用例的逐事件期望值（【派生】/【复核】）在 {@link TurnBattleTraceTest}。
 */
class TurnBattleRoundTest {

    private static final int PVE_SOLO = BattleConstants.MATCH_MODE_PVE_SOLO;
    private static final int PVE_TEAM = BattleConstants.MATCH_MODE_PVE_TEAM;
    private static final int PVP_1V1 = 3;

    private static byte[] runSameSeed(long seed) {
        CreateBattleRequest.Builder request = request(9001, PVE_SOLO, seed);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 50, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (int round = 0; round < 10 && engine.outcome() == BATTLE_OUTCOME_ONGOING; round++) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE));
            TurnResultS2C result = engine.resolveCurrentRound();
            for (BattleEventItem event : result.getEventsList()) {
                stream.writeBytes(event.toByteArray());
                stream.write('|');
            }
        }
        stream.writeBytes(engine.buildSettlement(PLAYER_A).toByteArray());
        return stream.toByteArray();
    }

    // 基线 SameSeedSameCommandsProduceIdenticalEventStream（test.cpp:221-245）【C++ 断言】
    @Test
    void 同种子同指令产出逐字节相同的事件流() {
        // 高暴击率逼引擎大量消耗 RNG，任何随机路径分叉都会导致字节流不一致
        assertThat(runSameSeed(42)).isEqualTo(runSameSeed(42));
        assertThat(runSameSeed(20260815)).isEqualTo(runSameSeed(20260815));
    }

    // 基线 TurnOrderIsSpeedDescending（test.cpp:268-282）【C++ 断言】
    @Test
    void 出手序按速度降序() {
        CreateBattleRequest.Builder request = request(9002, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 500, 500, 0, 100, 0, 120);
        addPlayer(request, PLAYER_B, 1, 500, 500, 0, 100, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        assertThat(engine.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A))).isTrue();

        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(result.getEventsCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getEvents(0).getEventType()).isEqualTo(BATTLE_EVENT_ATTACK);
        assertThat(result.getEvents(0).getSourceId()).as("速度 240 先手").isEqualTo(PLAYER_B);
    }

    // 基线 TurnOrderTieBreaksByActorIdAscending（test.cpp:284-297）【C++ 断言】
    @Test
    void 同速按actor_id升序() {
        CreateBattleRequest.Builder request = request(9003, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 500, 500, 0, 100, 0, 120);
        addPlayer(request, PLAYER_B, 1, 500, 500, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A));

        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(result.getEventsCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getEvents(0).getSourceId()).as("同速，小 id 先手").isEqualTo(PLAYER_A);
    }

    // 基线 TimeoutFillsDefaultBasicAttack（test.cpp:303-318）【C++ 断言】
    @Test
    void 超时未提交按默认普攻结算() {
        CreateBattleRequest.Builder request = request(9004, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 玩家不提交任何行动，直接结算（等价回合超时）
        TurnResultS2C result = engine.resolveCurrentRound();

        assertThat(countEvents(result, BATTLE_EVENT_ATTACK)).as("玩家默认普攻 + 怪物普攻").isEqualTo(2);
        assertThat(result.getEventsCount()).isGreaterThanOrEqualTo(1);
        // 玩家速度 120 > 怪物默认速度 60，先手是玩家的默认普攻
        assertThat(result.getEvents(0).getEventType()).isEqualTo(BATTLE_EVENT_ATTACK);
        assertThat(result.getEvents(0).getSourceId()).isEqualTo(PLAYER_A);
        assertThat(result.getEvents(0).getTargetId()).isEqualTo(MONSTER_ID);
    }

    // 基线 KillingAllMonstersWinsSideA（test.cpp:508-525）【C++ 断言】
    @Test
    void 击杀全部怪物判A方胜() {
        CreateBattleRequest.Builder request = request(9012, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_NUKE));
        TurnResultS2C result = engine.resolveCurrentRound();

        assertThat(countEvents(result, BATTLE_EVENT_DEATH)).isEqualTo(1);
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);

        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getOutcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(settlement.getTotalRounds()).isEqualTo(1);
        assertThat(settlement.getIsDead()).isFalse();
        assertThat(settlement.getHealth()).as("怪物先被秒，未能出手").isEqualTo(1000);
    }

    // 基线 MaxRoundsExhaustionDefeatsAttacker（test.cpp:527-546）【C++ 断言】
    @Test
    void 打满回合上限进攻方判负() {
        MemoryBattleData data = standard();
        // DungeonTable.time_limit = 12 秒 → 上限 2 回合
        data.addDungeon(DUNGEON_CONFIG).setTimeLimit(12);

        CreateBattleRequest.Builder request = request(9013, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_ONGOING);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        engine.resolveCurrentRound();
        // 打满 2 回合：进攻方（A 方）判负
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_B_WIN);
        assertThat(engine.buildSettlement(PLAYER_A).getTotalRounds()).isEqualTo(2);
    }

    // 基线 DamageFormulaMatchesRealtimeSemantics（test.cpp:579-601）【C++ 断言】
    @Test
    void 技能伤害与实时公式同口径() {
        CreateBattleRequest.Builder request = request(9015, PVE_SOLO, 1);
        // strength = 4，无暴击；怪物默认 armor = 24、resistance = 0，怪物等级取参战玩家最高等级 10
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE));
        TurnResultS2C result = engine.resolveCurrentRound();

        // 原始 50 × (1 + 4 × 0.1) = 70；受伤比例 1560 / (24 + 1560) ≈ 0.9848；70 × 0.9848 ≈ 68.94 → 向上取整 69
        BattleEventItem damage = firstEvent(result, BATTLE_EVENT_DAMAGE);
        assertThat(damage).isNotNull();
        assertThat(damage.getValue()).isEqualTo(69);
        assertThat(damage.getIsCritical()).isFalse();
        assertThat(damage.getTargetHealthAfter()).isEqualTo(BattleConstants.MONSTER_DEFAULT_HEALTH - 69);

        BattleActorState monster = stateActor(result.getState(), MONSTER_ID);
        assertThat(monster).isNotNull();
        assertThat(monster.getAttributes().getHealth()).isEqualTo(BattleConstants.MONSTER_DEFAULT_HEALTH - 69);
    }

    private static byte[] runFlee(long seed) {
        CreateBattleRequest.Builder request = request(9016, PVE_SOLO, seed);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 600); // 高速，逃跑成功率高
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_FLEE));
        TurnResultS2C result = engine.resolveCurrentRound();

        BattleEventItem flee = firstEvent(result, BATTLE_EVENT_FLEE);
        assertThat(flee).isNotNull();
        // 逃跑结果与单位状态、胜负一致：成功 → 单位离场，A 方无存活 → B 胜
        BattleActorState player = stateActor(result.getState(), PLAYER_A);
        assertThat(player).isNotNull();
        assertThat(player.getFled()).isEqualTo(flee.getSuccess());
        if (flee.getSuccess()) {
            assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_B_WIN);
            assertThat(engine.buildSettlement(PLAYER_A).getFled()).isTrue();
        } else {
            assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_ONGOING);
        }
        return flee.toByteArray();
    }

    // 基线 FleeIsDeterministicAndConsistentWithOutcome（test.cpp:607-640）【C++ 断言】
    @Test
    void 逃跑结果确定且与胜负一致() {
        assertThat(runFlee(7)).isEqualTo(runFlee(7));
        assertThat(runFlee(1234)).isEqualTo(runFlee(1234));
    }

    // 基线 FleeIsRejectedInPvp（test.cpp:642-658）【C++ 断言】
    @Test
    void PVP不能逃跑_超时按默认普攻() {
        CreateBattleRequest.Builder request = request(9017, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 500, 500, 0, 100, 0, 120);
        addPlayer(request, PLAYER_B, 1, 500, 500, 0, 100, 0, 60);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 逃跑提交不落账；超时结算按默认普攻
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_FLEE))).isFalse();
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();

        assertThat(countEvents(result, BATTLE_EVENT_FLEE)).isZero();
        BattleEventItem attack = firstEvent(result, BATTLE_EVENT_ATTACK);
        assertThat(attack).isNotNull();
        assertThat(attack.getSourceId()).as("默认普攻兜底").isEqualTo(PLAYER_A);
    }

    // 基线 MonsterAttributesFromTableEnableMultiRoundAndRewards（test.cpp:902-945）【C++ 断言】
    @Test
    void 怪物属性读表_多回合击杀并发奖() {
        MemoryBattleData data = standard();
        // 表配怪物：200 HP、护甲 60、速度 96（慢于玩家），奖励经验 50 金币 25
        int monsterTableId = 7000;
        MonsterTable.Builder monster = data.addMonster(monsterTableId);
        monster.setHealth(200);
        monster.setStrength(10);
        monster.setArmor(60);
        monster.setSpeed(96);
        monster.setExpReward(50);
        monster.setGoldReward(25);
        data.setDungeonMonsters(DUNGEON_CONFIG, monsterTableId);

        CreateBattleRequest.Builder request = request(9030, PVE_SOLO, 7);
        // 玩家：1000 HP、力量 20（普攻 30，怪物护甲 60 → 30 × 1560 / 1620 ≈ 29）、速度 240（先手）
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, data);

        // 怪物从表读到 200 HP（不是常量 300），证明读表生效
        BattleActorState monsterActor = stateActor(engine.buildStateSnapshot(), MONSTER_ID);
        assertThat(monsterActor).isNotNull();
        assertThat(monsterActor.getAttributes().getHealth()).isEqualTo(200);
        assertThat(monsterActor.getMaxHealth()).isEqualTo(200);

        // 玩家逐回合普攻，战斗应持续多回合（200 / 29 ≈ 7 回合），不再一击秒杀
        int rounds = 0;
        while (engine.outcome() == BATTLE_OUTCOME_ONGOING && rounds < 30) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
            engine.resolveCurrentRound();
            rounds++;
        }
        assertThat(rounds).as("多回合（不是 1 回合秒杀）").isGreaterThanOrEqualTo(3);
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);

        // 结算：击杀怪物累加经验 / 金币
        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getExpGain()).isEqualTo(50);
        assertThat(settlement.getGoldGain()).isEqualTo(25);
    }

    // 基线 PlayerDefeatYieldsNoReward（test.cpp:948-969）【C++ 断言】
    @Test
    void 玩家战败不发奖() {
        MemoryBattleData data = standard();
        int bossTableId = 7001;
        MonsterTable.Builder boss = data.addMonster(bossTableId);
        boss.setHealth(100000);   // 打不死
        boss.setStrength(1000);   // 秒玩家
        boss.setSpeed(1200);      // 先手
        boss.setExpReward(9999);
        boss.setGoldReward(9999);
        data.setDungeonMonsters(DUNGEON_CONFIG, bossTableId);

        CreateBattleRequest.Builder request = request(9031, PVE_SOLO, 7);
        addPlayer(request, PLAYER_A, 0, 50, 50, 5, 0, 0, 12); // 脆弱、后手
        TurnBattleEngine engine = TestBattles.start(request, data);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).as("玩家败").isEqualTo(BATTLE_OUTCOME_SIDE_B_WIN);
        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getExpGain()).as("败方无奖励").isZero();
        assertThat(settlement.getGoldGain()).isZero();
    }

    // 基线 DerivedPhysicalAttackIsAdditiveOnBasicAttack（test.cpp:975-994）【C++ 断言】
    @Test
    void 普攻叠加物伤_不吃法伤() {
        CreateBattleRequest.Builder request = request(9201, PVE_SOLO, 1);
        // 普攻 10 × (1 + 4 × 0.1) + 50 = 64；64 × 1560 / (24 + 1560) ≈ 63.03 → 向上取整 64
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 0, 0, 600);
        snapshot.setPhysicalAttack(50);
        snapshot.setMagicAttack(999); // 普攻只吃物伤，法伤不得混入
        TurnBattleEngine engine = TestBattles.start(request, standard());

        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID))).isTrue();
        TurnResultS2C result = engine.resolveCurrentRound();

        BattleEventItem damage = firstEvent(result, BATTLE_EVENT_DAMAGE);
        assertThat(damage).isNotNull();
        assertThat(damage.getSourceId()).isEqualTo(PLAYER_A);
        assertThat(damage.getValue()).isEqualTo(64);
    }

    // 基线 DerivedMagicAttackIsAdditiveOnSkill（test.cpp:996-1012）【C++ 断言】
    @Test
    void 技能叠加法伤_不吃物伤() {
        CreateBattleRequest.Builder request = request(9202, PVE_SOLO, 1);
        // 50 × (1 + 4 × 0.1) + 20 = 90；90 × 1560 / 1584 ≈ 88.64 → 向上取整 89
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 0, 120);
        snapshot.setMagicAttack(20);
        snapshot.setPhysicalAttack(999); // 技能只吃法伤
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE));
        TurnResultS2C result = engine.resolveCurrentRound();

        BattleEventItem damage = firstEvent(result, BATTLE_EVENT_DAMAGE);
        assertThat(damage).isNotNull();
        assertThat(damage.getValue()).isEqualTo(89);
    }

    private static TurnResultS2C runWithDefense(long defense) {
        CreateBattleRequest.Builder request = request(9203, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 0, 0, 12);
        snapshot.setDefense(defense);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID))).isTrue();
        return engine.resolveCurrentRound();
    }

    // 基线 DerivedDefenseReducesIncomingDamageProportionally（test.cpp:1014-1045）【C++ 断言】
    @Test
    void 防御按比例减伤_最多减六成() {
        // 玩家 speed = 12 < 怪物默认 60：怪物先手。怪物普攻 10 × (1 + 5 × 0.1) = 15；受伤比例 = 1560 / (防御 + 1560)，最低 0.4
        // 防御 0：全额 15
        assertThat(EngineTestSupport.health(runWithDefense(0).getState(), PLAYER_A)).isEqualTo(985);

        // 防御 = 等级系数 1560：减半，7.5 → 向上取整 8
        TurnResultS2C halved = runWithDefense(1560);
        assertThat(EngineTestSupport.health(halved.getState(), PLAYER_A)).isEqualTo(992);
        assertThat(stateActor(halved.getState(), PLAYER_A).getDefense()).isEqualTo(1560);

        // 防御再高也只减六成
        long expected = 1000 - (long) Math.ceil(15.0 * (1.0 - CombatDamageRules.MAX_PASSIVE_REDUCTION));
        assertThat(EngineTestSupport.health(runWithDefense(1000000).getState(), PLAYER_A)).isEqualTo(expected);
    }

    // 基线 PvpDirectDamageIsScaled（test.cpp:1048-1066）【C++ 断言】
    @Test
    void PVP直接伤害乘系数() {
        CreateBattleRequest.Builder request = request(9204, PVP_1V1, 1);
        // A 力量 21：普攻 10 × (1 + 2.1) = 31；B 护甲 0、防御 0、抗性 0、等级 10 → 受伤比例 1
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 21, 0, 0, 120);
        addPlayer(request, PLAYER_B, 1, 1000, 1000, 0, 0, 0, 60);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // submitAction 返回全员就绪状态；A 已提交，仍需等待 B
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B))).isFalse();
        assertThat(engine.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A))).isTrue();
        TurnResultS2C result = engine.resolveCurrentRound();

        BattleEventItem damage = firstEvent(result, BATTLE_EVENT_DAMAGE);
        assertThat(damage).isNotNull();
        assertThat(damage.getSourceId()).as("A 速度快，先出手").isEqualTo(PLAYER_A);
        // 31 × 0.3 = 9.3 → 向上取整 10；PVE 口径（不乘系数）是 31
        assertThat(damage.getValue()).isEqualTo(10);
    }

    // 基线 MonsterRowWithoutStatsFallsBackToDefaults（test.cpp:1096-1128）【C++ 断言】
    @Test
    void 怪物行没有属性时回退默认值() {
        MemoryBattleData data = standard();
        int bareMonster = 7002;
        data.addMonster(bareMonster); // 只有 id，其余字段全 0
        data.setDungeonMonsters(DUNGEON_CONFIG, bareMonster);

        CreateBattleRequest.Builder request = request(9040, PVE_SOLO, 11);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, data);

        BattleActorState monster = stateActor(engine.buildStateSnapshot(), MONSTER_ID);
        assertThat(monster).isNotNull();
        assertThat(monster.getAttributes().getHealth()).isEqualTo(BattleConstants.MONSTER_DEFAULT_HEALTH);
        assertThat(monster.getMaxHealth()).isEqualTo(BattleConstants.MONSTER_DEFAULT_HEALTH);
        assertThat(monster.getAttributes().getStrength()).isEqualTo(BattleConstants.MONSTER_DEFAULT_STRENGTH);
        assertThat(monster.getAttributes().getArmor()).isEqualTo(BattleConstants.MONSTER_DEFAULT_ARMOR);
        assertThat(monster.getAttributes().getSpeed()).isEqualTo(BattleConstants.MONSTER_DEFAULT_SPEED);
        assertThat(monster.getMonsterTableId()).isEqualTo(bareMonster);

        // 玩家普攻 10 × (1 + 2.0) = 30，受伤比例 1560 / (24 + 1560) → 29.55 → 向上取整 30，300 血要 10 刀
        int rounds = 0;
        while (engine.outcome() == BATTLE_OUTCOME_ONGOING && rounds < 30) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
            engine.resolveCurrentRound();
            rounds++;
        }
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(rounds).as("回退怪物 300 血 / 护甲 24，应打满 10 回合而不是开局即结束").isEqualTo(10);
    }

    // 基线 MonsterZeroSpeedInTableFallsBackToDefaultSpeed（test.cpp:1131-1152）【C++ 断言】
    @Test
    void 怪物表速度为0时只回退速度() {
        MemoryBattleData data = standard();
        int slowMonster = 7003;
        MonsterTable.Builder row = data.addMonster(slowMonster);
        row.setHealth(150);
        row.setStrength(8);
        row.setArmor(1);
        data.setDungeonMonsters(DUNGEON_CONFIG, slowMonster);

        CreateBattleRequest.Builder request = request(9041, PVE_SOLO, 12);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, data);

        BattleActorState monster = stateActor(engine.buildStateSnapshot(), MONSTER_ID);
        assertThat(monster).isNotNull();
        assertThat(monster.getAttributes().getHealth()).isEqualTo(150);
        assertThat(monster.getAttributes().getStrength()).isEqualTo(8);
        assertThat(monster.getAttributes().getArmor()).isEqualTo(1);
        assertThat(monster.getAttributes().getSpeed()).isEqualTo(BattleConstants.MONSTER_DEFAULT_SPEED);
    }

    private record Stalemate(int rounds, eBattleOutcome outcome) {
    }

    private static MemoryBattleData tankData(int monsterId) {
        MemoryBattleData data = standard();
        MonsterTable.Builder monster = data.addMonster(monsterId);
        monster.setHealth(100000); // 打不死，只能靠回合上限收场
        monster.setStrength(1);
        monster.setSpeed(12);
        data.setDungeonMonsters(DUNGEON_CONFIG, monsterId);
        return data;
    }

    private static Stalemate playStalemate(MemoryBattleData data, long battleId) {
        CreateBattleRequest.Builder request = request(battleId, PVE_SOLO, 7);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 10, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, data);
        int rounds = 0;
        while (engine.outcome() == BATTLE_OUTCOME_ONGOING && rounds < 60) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
            engine.resolveCurrentRound();
            rounds++;
        }
        return new Stalemate(rounds, engine.outcome());
    }

    // 基线 MaxRoundsFallsBackToDefaultAndAttackerLosesOnTimeout（test.cpp:1352-1398）【C++ 断言】
    @Test
    void 回合上限缺行或为0时回退30回合_打满进攻方判负() {
        int tankMonster = 7010;
        // 副本缺行 → 默认 30 回合
        Stalemate missingRow = playStalemate(tankData(tankMonster), 9060);
        assertThat(missingRow.rounds()).isEqualTo(BattleConstants.DEFAULT_MAX_ROUNDS);
        assertThat(missingRow.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_B_WIN);

        // 有行但 time_limit == 0 → 同样退回默认（`> 0` 守卫）
        MemoryBattleData zeroLimit = tankData(tankMonster);
        zeroLimit.addDungeon(DUNGEON_CONFIG).setTimeLimit(0);
        Stalemate zero = playStalemate(zeroLimit, 9061);
        assertThat(zero.rounds()).isEqualTo(BattleConstants.DEFAULT_MAX_ROUNDS);
        assertThat(zero.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_B_WIN);

        // time_limit = 12 s，回合 6 s → 2 回合
        MemoryBattleData twelve = tankData(tankMonster);
        twelve.addDungeon(DUNGEON_CONFIG).setTimeLimit(12);
        Stalemate two = playStalemate(twelve, 9062);
        assertThat(two.rounds()).isEqualTo(2);
        assertThat(two.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_B_WIN);
    }

    // 基线 PresentationGroupIdSharedWithinActionDistinctAcrossActions（test.cpp:1412-1446）【C++ 断言】
    @Test
    void 同一行动的事件共用group_id_不同行动不同() {
        CreateBattleRequest.Builder request = request(9101, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 120);
        addPlayer(request, PLAYER_B, 1, 1000, 1000, 20, 10, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        assertThat(engine.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A))).isTrue();
        TurnResultS2C result = engine.resolveCurrentRound();

        // 行动序：B（速度 240）先手，A 后手；battle 节点把它填进 TurnResultS2C.action_order
        List<Long> order = engine.lastActionOrder();
        assertThat(order).containsExactly(PLAYER_B, PLAYER_A);

        // 事件流：ATTACK(B→A) DAMAGE(B→A) ATTACK(A→B) DAMAGE(A→B)
        assertThat(result.getEventsCount()).isGreaterThanOrEqualTo(4);
        assertThat(result.getEvents(0).getEventType()).isEqualTo(BATTLE_EVENT_ATTACK);
        assertThat(result.getEvents(1).getEventType()).isEqualTo(BATTLE_EVENT_DAMAGE);
        assertThat(result.getEvents(2).getEventType()).isEqualTo(BATTLE_EVENT_ATTACK);
        assertThat(result.getEvents(3).getEventType()).isEqualTo(BATTLE_EVENT_DAMAGE);
        assertThat(result.getEvents(0).getSourceId()).isEqualTo(PLAYER_B);
        assertThat(result.getEvents(2).getSourceId()).isEqualTo(PLAYER_A);

        assertThat(result.getEvents(0).getGroupId()).isNotZero();
        assertThat(result.getEvents(0).getGroupId()).isEqualTo(result.getEvents(1).getGroupId());
        assertThat(result.getEvents(2).getGroupId()).isEqualTo(result.getEvents(3).getGroupId());
        assertThat(result.getEvents(0).getGroupId()).isNotEqualTo(result.getEvents(2).getGroupId());
        for (BattleEventItem event : result.getEventsList()) {
            assertThat(event.getHitIndex()).isZero();
        }
        assertThat(countEvents(result, BATTLE_EVENT_MISS)).isZero();
        assertThat(countEvents(result, BATTLE_EVENT_DAMAGE)).isEqualTo(2);
    }

    // ---- Java 追加（规格 §3） ----

    // 规格 §3.1：result.round_index 是本次结算的回合号；result.state.round_index 未结束时是下一回合号、已结束时是同一回合号
    @Test
    void 结果回合号与快照回合号的口径() {
        MemoryBattleData data = standard();
        data.addDungeon(DUNGEON_CONFIG).setTimeLimit(12);
        CreateBattleRequest.Builder request = request(9610, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, data);

        TurnResultS2C first = engine.resolveCurrentRound();
        assertThat(first.getBattleId()).isEqualTo(9610);
        assertThat(first.getRoundIndex()).isEqualTo(1);
        assertThat(first.getState().getRoundIndex()).isEqualTo(2);
        assertThat(first.getState().getOutcome()).isEqualTo(BATTLE_OUTCOME_ONGOING);
        assertThat(first.getState().getActionDeadlineMs()).as("由节点回填").isZero();

        TurnResultS2C second = engine.resolveCurrentRound();
        assertThat(second.getRoundIndex()).isEqualTo(2);
        assertThat(second.getState().getRoundIndex()).isEqualTo(2);
        assertThat(second.getState().getOutcome()).isEqualTo(BATTLE_OUTCOME_SIDE_B_WIN);
        assertThat(engine.buildSettlement(PLAYER_A).getTotalRounds()).isEqualTo(2);
    }

    // 规格 §3.8：防御只在出手那一刻生效；快照里 is_defending 恒为 false（快照只在回合之间产生）
    @Test
    void 防御只对出手之后的伤害生效_快照里不留防御标记() {
        // 怪物速度 60 快于玩家 12：怪物先打，玩家后防御 → 这一下不减半
        CreateBattleRequest.Builder request = request(9611, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 12);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();
        BattleEventItem damage = firstEvent(result, BATTLE_EVENT_DAMAGE);
        assertThat(damage.getSourceId()).isEqualTo(MONSTER_ID);
        assertThat(damage.getValue()).as("15 × 1560/1660 = 14.096 → 15，不减半").isEqualTo(15);
        for (BattleActorState actor : result.getState().getActorsList()) {
            assertThat(actor.getIsDefending()).isFalse();
        }
    }

    // 规格 §3.11：A 方玩家全灭但宝宝还活着时战斗继续
    @Test
    void 玩家阵亡但宝宝在场时战斗继续() {
        CreateBattleRequest.Builder request = request(9612, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder owner = addPlayer(request, PLAYER_A, 0, 0, 100, 0, 0, 0, 12);
        TestBattles.addPet(owner, TestTables.PET_A, 400, 400, 4, 360);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        // 快照带进来的「0 血但活着」的玩家：被普攻打到（伤害为 0）或被毒 tick 到都会判死（规格 §3.6、§5.6、§11.1 第 12 条）。
        // 怪物的普攻目标随种子而定，所以再挂一层毒，保证本回合末一定判死
        assertThat(engine.addBuffForTest(PLAYER_A, TestTables.BUFF_POISON, MONSTER_ID)).isTrue();

        TurnResultS2C result = engine.resolveCurrentRound();
        BattleStateS2C state = result.getState();
        assertThat(stateActor(state, PLAYER_A).getIsDead()).isTrue();
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_ONGOING);
        assertThat(state.getPendingActorIdsList()).as("没有需要提交的玩家").isEmpty();
        assertThat(engine.allPlayersReady()).as("空真").isTrue();
    }
}
