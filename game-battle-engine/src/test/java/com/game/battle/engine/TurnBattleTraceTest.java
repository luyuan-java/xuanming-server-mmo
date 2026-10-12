package com.game.battle.engine;

import static com.game.battle.engine.EngineTestSupport.describeAll;
import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.BUFF_IMMUNE_POISON;
import static com.game.battle.engine.TestTables.BUFF_POISON;
import static com.game.battle.engine.TestTables.BUFF_REGEN;
import static com.game.battle.engine.TestTables.DUNGEON_CONFIG;
import static com.game.battle.engine.TestTables.DUNGEON_WITH_DROP;
import static com.game.battle.engine.TestTables.ITEM_POTION;
import static com.game.battle.engine.TestTables.MONSTER_ID;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.SKILL_DAMAGE;
import static com.game.battle.engine.TestTables.SKILL_DISPEL;
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
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_DRAW;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_ONGOING;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleItemEntry;
import com.game.proto.BattleMonsterDefeat;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.table.CommonErrorTip;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * 逐事件轨迹（规格 §13.5）：Java 回放基线。
 *
 * <p>期望值<strong>照抄规格</strong>，每条注明级别：【复核】= 手算 + 草稿复算器两种手段同值；【派生】= 只由草稿复算器得出。
 * 两级都没有在 C++ 里实跑过（规格 §12.3 Q1，用户决定暂不改 mmorpg），所以这里只起回归作用，PARITY 注明「跨语言金样待 mmorpg」。
 * 规格记法里省略的字段（另一方的 ATTACK、DAMAGE 的 source / target、mp 等）按规格的语义补全，补全处都能由同一行的其它值推出。
 *
 * <p>事件写成 {@link EngineTestSupport#describe} 的紧凑形式：只写非零字段，hp0 与 mp0 不出现（proto3 不上线零值）。
 * 「抽数」用 {@code rngDrawsForTest()} 核对（规格 §8.4 的消耗账）。
 */
class TurnBattleTraceTest {

    private static final int PVE_SOLO = BattleConstants.MATCH_MODE_PVE_SOLO;
    private static final int PVE_TEAM = BattleConstants.MATCH_MODE_PVE_TEAM;
    private static final int PVP_1V1 = 3;
    private static final int ENTITY_INVALID = CommonErrorTip.common_error.kThisEntityIsInvalid_VALUE;

    private static List<String> monsterSwing(int group, long health) {
        return List.of("g" + group + " ATTACK M0->5001", "g" + group + " DAMAGE M0->5001 v15 hp" + health);
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }

    // ---- G-SS42 / G-SS0815：SameSeed（test.cpp:221-245）的完整轨迹 ----

    private static TurnBattleEngine sameSeedEngine(long seed) {
        CreateBattleRequest.Builder request = request(9001, PVE_SOLO, seed);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 50, 120);
        return TestBattles.start(request, standard());
    }

    private static TurnResultS2C sameSeedRound(TurnBattleEngine engine) {
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE));
        return engine.resolveCurrentRound();
    }

    // G-SS42【派生】：种子 42，共 11 抽，5 回合；冷却中的回合提交被拒、走默认普攻
    @Test
    void 轨迹_同种子技能连发_种子42() {
        TurnBattleEngine engine = sameSeedEngine(42);
        assertThat(describeAll(sameSeedRound(engine))).containsExactlyElementsOf(concat(List.of(
                "g1 SKILL 5001->M0 skill101",
                "g1 DAMAGE 5001->M0 skill101 v69 hp231"), monsterSwing(2, 985)));
        assertThat(describeAll(sameSeedRound(engine))).containsExactlyElementsOf(concat(List.of(
                "g1 ATTACK 5001->M0",
                "g1 DAMAGE 5001->M0 v28 crit hp203"), monsterSwing(2, 970)));
        assertThat(describeAll(sameSeedRound(engine))).containsExactlyElementsOf(concat(List.of(
                "g1 SKILL 5001->M0 skill101",
                "g1 DAMAGE 5001->M0 skill101 v138 crit hp65"), monsterSwing(2, 955)));
        assertThat(describeAll(sameSeedRound(engine))).containsExactlyElementsOf(concat(List.of(
                "g1 ATTACK 5001->M0",
                "g1 DAMAGE 5001->M0 v28 crit hp37"), monsterSwing(2, 940)));
        // 封顶到剩余气血
        assertThat(describeAll(sameSeedRound(engine))).containsExactly(
                "g1 SKILL 5001->M0 skill101",
                "g1 DAMAGE 5001->M0 skill101 v37 crit",
                "g1 DEATH M0->M0");
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(engine.rngDrawsForTest()).isEqualTo(11);
        // 兜底怪不记击杀、不掷掉落，经验金币为 0
        assertThat(engine.buildSettlement(PLAYER_A)).isEqualTo(BattleSettlementData.newBuilder()
                .setBattleId(9001)
                .setPlayerId(PLAYER_A)
                .setOutcome(BATTLE_OUTCOME_SIDE_A_WIN)
                .setHealth(940)
                .setTotalRounds(5)
                .build());
    }

    // G-SS0815【派生】：种子 20260815，6 抽，3 回合，A 剩 970
    @Test
    void 轨迹_同种子技能连发_种子20260815() {
        TurnBattleEngine engine = sameSeedEngine(20260815);
        assertThat(describeAll(sameSeedRound(engine))).containsExactlyElementsOf(concat(List.of(
                "g1 SKILL 5001->M0 skill101",
                "g1 DAMAGE 5001->M0 skill101 v138 crit hp162"), monsterSwing(2, 985)));
        assertThat(describeAll(sameSeedRound(engine))).containsExactlyElementsOf(concat(List.of(
                "g1 ATTACK 5001->M0",
                "g1 DAMAGE 5001->M0 v28 crit hp134"), monsterSwing(2, 970)));
        assertThat(describeAll(sameSeedRound(engine))).containsExactly(
                "g1 SKILL 5001->M0 skill101",
                "g1 DAMAGE 5001->M0 skill101 v134 crit",
                "g1 DEATH M0->M0");
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(engine.rngDrawsForTest()).isEqualTo(6);
        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getHealth()).isEqualTo(970);
        assertThat(settlement.getTotalRounds()).isEqualTo(3);
    }

    // ---- G-AUTO：AutoModeMatchesManual（test.cpp:815-845）的完整轨迹【派生】 ----

    /**
     * 每回合 3 抽（A 选目标、A 暴击、怪选目标）；普攻 10 × 1.4 × 1560/1584 = 13.79 → 14，暴击 28；
     * 怪物 15 × 1560/1660 = 14.10 → 15。10 回合后仍是 ONGOING，A 剩 850。
     */
    private static void checkAutoTrace(long seed, boolean useAuto, int[] damages, boolean[] crits) {
        CreateBattleRequest.Builder request = request(9024, PVE_SOLO, seed);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 50, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        if (useAuto) {
            assertThat(engine.setActorAuto(PLAYER_A, true)).isEqualTo(SUCCESS);
        }
        long monsterHealth = BattleConstants.MONSTER_DEFAULT_HEALTH;
        for (int round = 1; round <= 10; round++) {
            if (!useAuto) {
                engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, 0));
            }
            TurnResultS2C result = engine.resolveCurrentRound();
            monsterHealth -= damages[round - 1];
            String damage = "g1 DAMAGE 5001->M0 v" + damages[round - 1] + (crits[round - 1] ? " crit" : "") + " hp" + monsterHealth;
            assertThat(describeAll(result)).as("种子 %d 第 %d 回合", seed, round).containsExactlyElementsOf(concat(
                    List.of("g1 ATTACK 5001->M0", damage), monsterSwing(2, 1000 - 15L * round)));
        }
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_ONGOING);
        assertThat(engine.rngDrawsForTest()).isEqualTo(30);
        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getHealth()).isEqualTo(850);
        assertThat(settlement.getTotalRounds()).isEqualTo(10);
    }

    // G-AUTO 种子 42【派生】：14, 14, 28c, 28c, 14, 14, 28c, 28c, 28c, 14，怪剩 90
    @ParameterizedTest(name = "挂机 = {0}")
    @ValueSource(booleans = {true, false})
    void 轨迹_挂机等同手动_种子42(boolean useAuto) {
        checkAutoTrace(42, useAuto,
                new int[] {14, 14, 28, 28, 14, 14, 28, 28, 28, 14},
                new boolean[] {false, false, true, true, false, false, true, true, true, false});
    }

    // G-AUTO 种子 20260831【派生】：14, 14, 14, 14, 28c, 14, 14, 28c, 28c, 28c，怪剩 104
    @ParameterizedTest(name = "挂机 = {0}")
    @ValueSource(booleans = {true, false})
    void 轨迹_挂机等同手动_种子20260831(boolean useAuto) {
        checkAutoTrace(20260831, useAuto,
                new int[] {14, 14, 14, 14, 28, 14, 14, 28, 28, 28},
                new boolean[] {false, false, false, false, true, false, false, true, true, true});
    }

    // ---- G-FLEE：FleeIsDeterministic（test.cpp:607-640）【派生】 ----

    // 两个种子都只有 g1 FLEE 成功（Rand01 = 0.754 / 0.9472 都 < 0.95）；怪物找不到敌人：占用 g2、没有事件、不耗 RNG；共 1 抽。
    // 种子 1234 离阈值只差 0.0028，能有效抓出逃跑公式或 rand01 的错误
    @ParameterizedTest(name = "种子 {0}")
    @ValueSource(longs = {7, 1234})
    void 轨迹_逃跑成功(long seed) {
        CreateBattleRequest.Builder request = request(9016, PVE_SOLO, seed);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 600);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_FLEE));
        TurnResultS2C result = engine.resolveCurrentRound();

        assertThat(describeAll(result)).containsExactly("g1 FLEE 5001->5001 success");
        assertThat(engine.lastActionOrder()).containsExactly(PLAYER_A, MONSTER_ID);
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_B_WIN);
        assertThat(engine.rngDrawsForTest()).isEqualTo(1);
        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getFled()).isTrue();
        assertThat(settlement.getTotalRounds()).isEqualTo(1);
        assertThat(settlement.getHealth()).isEqualTo(1000);
    }

    // ---- G-SCAN：SetActorAutoRejects 第三段（test.cpp:788-812），种子 1 就成功【派生】 ----

    @Test
    void 轨迹_组队逃跑扫种子_种子1() {
        CreateBattleRequest.Builder request = request(9023, PVE_TEAM, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 600);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_FLEE));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();

        assertThat(engine.lastActionOrder()).containsExactly(PLAYER_A, PLAYER_B, MONSTER_ID, MONSTER_ID + 1);
        // 怪物普攻 15 × 1560/1660 × 0.5 = 7.05 → 8
        assertThat(describeAll(result)).containsExactly(
                "g1 FLEE 5001->5001 success",
                "g2 DEFEND 5002->5002",
                "g3 ATTACK M0->5002",
                "g3 DAMAGE M0->5002 v8 hp992",
                "g4 ATTACK M1->5002",
                "g4 DAMAGE M1->5002 v8 hp984");
        assertThat(engine.rngDrawsForTest()).isEqualTo(3);
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_ONGOING);
        assertThat(engine.setActorAuto(PLAYER_A, true)).isEqualTo(ENTITY_INVALID);
    }

    // ---- G-DEAD33：DeadPlayerOnWinningTeam（test.cpp:1263-1312），种子 33【复核】 ----

    @Test
    void 轨迹_胜方阵亡玩家_种子33() {
        MemoryBattleData data = standard();
        data.addMonster(7007).setHealth(400).setStrength(20).setSpeed(12).setExpReward(60).setGoldReward(30);
        data.setDungeonMonsters(DUNGEON_CONFIG, 7007);
        CreateBattleRequest.Builder request = request(9044, PVE_TEAM, 33);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 360);
        addPlayer(request, PLAYER_B, 0, 1, 1, 0, 0, 0, 24);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C first = engine.resolveCurrentRound();
        assertThat(engine.lastActionOrder()).containsExactly(PLAYER_A, PLAYER_B, MONSTER_ID);
        // RandIndex(2) 的第 1 抽为奇数，选中 B；30 × 0.5 = 15，封顶为 1
        assertThat(describeAll(first)).containsExactly(
                "g1 DEFEND 5001->5001",
                "g2 DEFEND 5002->5002",
                "g3 ATTACK M0->5002",
                "g3 DAMAGE M0->5002 v1",
                "g3 DEATH 5002->5002");

        // 之后 A 每回合打 30（第 15 回合打 10 把怪打死），怪每回合还 30
        int rounds = 1;
        while (engine.outcome() == BATTLE_OUTCOME_ONGOING && rounds < 31) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
            engine.resolveCurrentRound();
            rounds++;
        }
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getTotalRounds()).isEqualTo(15);
        assertThat(settlement.getHealth()).isEqualTo(610);
        assertThat(settlement.getDefeatedMonstersList()).containsExactly(
                BattleMonsterDefeat.newBuilder().setMonsterConfigId(7007).setCount(1).build());
        assertThat(engine.rngDrawsForTest()).as("第 1 回合 1 抽 + 第 2–14 回合怪物各 1 抽（本稿外按 §8.4 手算）").isEqualTo(14);
    }

    // ---- G-FLED21：FledPlayerOnWinningTeam（test.cpp:1210-1259），种子 21【复核】 ----

    @Test
    void 轨迹_胜方逃跑玩家_种子21() {
        MemoryBattleData data = standard();
        data.addMonster(7006).setHealth(150).setStrength(1).setSpeed(12).setExpReward(40).setGoldReward(20);
        data.setDungeonMonsters(DUNGEON_CONFIG, 7006);
        CreateBattleRequest.Builder request = request(9043, PVE_TEAM, 21);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 360);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 10, 0, 1440);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_FLEE));
        TurnResultS2C first = engine.resolveCurrentRound();
        assertThat(engine.lastActionOrder()).containsExactly(PLAYER_B, PLAYER_A, MONSTER_ID);
        // 逃跑 Rand01 = 0.2854；怪物 11 × 1560/1570 × 0.5 = 5.47 → 6
        assertThat(describeAll(first)).containsExactly(
                "g1 FLEE 5002->5002 success",
                "g2 DEFEND 5001->5001",
                "g3 ATTACK M0->5001",
                "g3 DAMAGE M0->5001 v6 hp994");

        while (engine.outcome() == BATTLE_OUTCOME_ONGOING && engine.buildStateSnapshot().getRoundIndex() <= 21) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
            engine.resolveCurrentRound();
        }
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        BattleSettlementData settlementA = engine.buildSettlement(PLAYER_A);
        assertThat(settlementA.getTotalRounds()).isEqualTo(6);
        assertThat(settlementA.getHealth()).isEqualTo(950);
        assertThat(settlementA.getDefeatedMonstersList()).extracting(BattleMonsterDefeat::getMonsterConfigId).containsExactly(7006);
        assertThat(engine.buildSettlement(PLAYER_B).getHealth()).isEqualTo(1000);
    }

    // ---- buff 与道具轨迹【复核】 ----

    private static TurnBattleEngine soloEngine(long battleId, MemoryBattleData data) {
        CreateBattleRequest.Builder request = request(battleId, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        return TestBattles.start(request, data);
    }

    // G-POISON（PoisonTicksAndExpires，test.cpp:354-383）【复核】
    @Test
    void 轨迹_毒的挂载tick与到期() {
        TurnBattleEngine engine = soloEngine(9006, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_POISON));
        TurnResultS2C first = engine.resolveCurrentRound();
        assertThat(describeAll(first)).containsExactly(
                "g1 SKILL 5001->M0 skill102",
                "g1 BUFF_ADD 5001->M0 buff201 v1",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v15 hp985",
                "g3 BUFF_TICK 5001->M0 buff201 v10 hp290");
        assertThat(stateActor(first.getState(), MONSTER_ID).getBuffsList()).as("实例 1").containsExactly(
                BattleBuffEntry.newBuilder().setBuffId(1).setBuffTableId(BUFF_POISON).setLayer(1).setRemainRounds(1)
                        .setCasterId(PLAYER_A).build());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        assertThat(describeAll(engine.resolveCurrentRound())).containsExactly(
                "g1 DEFEND 5001->5001",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v8 hp977",
                "g3 BUFF_TICK 5001->M0 buff201 v10 hp280",
                "g3 BUFF_REMOVE 5001->M0 buff201");

        // 怪身上已经没有 buff：没有 g3
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        assertThat(describeAll(engine.resolveCurrentRound())).containsExactly(
                "g1 DEFEND 5001->5001",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v8 hp969");
        assertThat(engine.rngDrawsForTest()).isEqualTo(3);
    }

    // G-DISPEL（DispelSkill，test.cpp:407-426）的第 2 回合【复核】：BUFF_REMOVE 的 source 是毒的施法者
    @Test
    void 轨迹_驱散() {
        TurnBattleEngine engine = soloEngine(9008, standard());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_POISON));
        engine.resolveCurrentRound();
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DISPEL));
        assertThat(describeAll(engine.resolveCurrentRound())).containsExactly(
                "g1 SKILL 5001->M0 skill104",
                "g1 BUFF_REMOVE 5001->M0 buff201",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v15 hp970");
    }

    // G-IMMUNE（ImmuneTagBlocksPoison，test.cpp:428-450）【复核】：B 的回合末组 g3 被占用但没有事件；实例号计数器开局即被推到 901
    @Test
    void 轨迹_免疫() {
        CreateBattleRequest.Builder request = request(9009, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 500, 500, 0, 100, 0, 120);
        BattlePlayerSnapshot.Builder defender = addPlayer(request, PLAYER_B, 1, 500, 500, 0, 100, 0, 60);
        defender.addBuffs(BattleBuffEntry.newBuilder().setBuffId(900).setBuffTableId(BUFF_IMMUNE_POISON).setLayer(1).setRemainRounds(0));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_POISON));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        assertThat(describeAll(engine.resolveCurrentRound())).containsExactly(
                "g1 SKILL 5001->5002 skill102",
                "g2 DEFEND 5002->5002");
        assertThat(engine.rngDrawsForTest()).isZero();

        assertThat(engine.addBuffForTest(PLAYER_A, BUFF_POISON, PLAYER_B)).isTrue();
        assertThat(stateActor(engine.buildStateSnapshot(), PLAYER_A).getBuffs(0).getBuffId()).isEqualTo(901);
    }

    // G-DRAW（SimultaneousPoisonDeath，test.cpp:548-573）【复核】：死亡清空 buff 不出 BUFF_REMOVE
    @Test
    void 轨迹_同回合毒死平局() {
        CreateBattleRequest.Builder request = request(9014, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 15, 15, 0, 100, 0, 120);
        addPlayer(request, PLAYER_B, 1, 15, 15, 0, 100, 0, 60);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_POISON));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_SKILL, PLAYER_A, SKILL_POISON));
        TurnResultS2C first = engine.resolveCurrentRound();
        assertThat(describeAll(first)).containsExactly(
                "g1 SKILL 5001->5002 skill102",
                "g1 BUFF_ADD 5001->5002 buff201 v1",
                "g2 SKILL 5002->5001 skill102",
                "g2 BUFF_ADD 5002->5001 buff201 v1",
                "g3 BUFF_TICK 5002->5001 buff201 v10 hp5",
                "g4 BUFF_TICK 5001->5002 buff201 v10 hp5");
        assertThat(stateActor(first.getState(), PLAYER_B).getBuffs(0).getBuffId()).as("实例 1").isEqualTo(1);
        assertThat(stateActor(first.getState(), PLAYER_A).getBuffs(0).getBuffId()).as("实例 2").isEqualTo(2);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C second = engine.resolveCurrentRound();
        assertThat(describeAll(second)).containsExactly(
                "g1 DEFEND 5001->5001",
                "g2 DEFEND 5002->5002",
                "g3 BUFF_TICK 5002->5001 buff201 v5",
                "g3 DEATH 5001->5001",
                "g4 BUFF_TICK 5001->5002 buff201 v5",
                "g4 DEATH 5002->5002");
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_DRAW);
        assertThat(stateActor(second.getState(), PLAYER_A).getBuffsList()).isEmpty();
        assertThat(stateActor(second.getState(), PLAYER_B).getBuffsList()).isEmpty();
        assertThat(engine.rngDrawsForTest()).isZero();
    }

    // G-ITEM（ItemHeals，test.cpp:660-701）【复核】
    @Test
    void 轨迹_道具回血() {
        CreateBattleRequest.Builder request = request(9018, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 50, 200, 0, 100, 0, 120)
                .addItems(BattleItemEntry.newBuilder().setItemTableId(ITEM_POTION).setCount(2));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_A, 0, ITEM_POTION));
        assertThat(describeAll(engine.resolveCurrentRound())).containsExactly(
                "g1 ITEM 5001->5001 item301 v100 hp150",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v15 hp135");
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ITEM, PLAYER_A, 0, ITEM_POTION));
        assertThat(describeAll(engine.resolveCurrentRound())).containsExactly(
                "g1 ITEM 5001->5001 item301 v65 hp200",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v15 hp185");
    }

    // G-MANA（ManaCost，test.cpp:1470-1509）【复核】：150 × 1560/1584 = 147.73 → 148；怪物 15 × 1560/1570 = 14.90 → 15
    @Test
    void 轨迹_技能耗蓝() {
        MemoryBattleData data = standard();
        data.addSkill(SKILL_DAMAGE).addCostResource(com.game.table.Skillcost_resource.newBuilder()
                .setCostResourceId(BattleConstants.SKILL_COST_RESOURCE_MANA).setCostResourceCost(30));
        CreateBattleRequest.Builder request = request(9103, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 240);
        snapshot.setMaxMana(100);
        snapshot.getBaseAttributesBuilder().setMana(100);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(describeAll(result)).containsExactly(
                "g1 SKILL 5001->M0 skill101",
                "g1 MANA 5001->5001 skill101 v30 hp1000 mp70",
                "g1 DAMAGE 5001->M0 skill101 v148 hp152",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v15 hp985 mp70");
        assertThat(stateActor(result.getState(), PLAYER_A).getSkillCooldownRoundsMap()).isEqualTo(Map.of(SKILL_DAMAGE, 1));
    }

    // G-REGEN（SnapshotRegen，test.cpp:452-481）【复核】：BUFF_TICK 的 source 是快照条目的 caster_id = 0；终血 117
    @Test
    void 轨迹_快照回血buff() {
        CreateBattleRequest.Builder request = request(9010, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 100, 200, 0, 100, 0, 120)
                .addBuffs(BattleBuffEntry.newBuilder().setBuffId(901).setBuffTableId(BUFF_REGEN).setLayer(1).setRemainRounds(2));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(describeAll(result)).containsExactly(
                "g1 DEFEND 5001->5001",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v8 hp92",
                "g3 BUFF_TICK 0->5001 buff240 v25 hp117");
        assertThat(EngineTestSupport.health(result.getState(), PLAYER_A)).isEqualTo(117);
    }

    // G-DROP（VictoryRollsDrops，test.cpp:1884-1904，种子 42）【复核】：第 1 抽 RandIndex(1) 选普攻目标；
    // 判出 SIDE_A_WIN 后 RollDrops 用第 2 抽 0.6390 × 10000 = 6390.3 < 10000，掉落
    @Test
    void 轨迹_胜利掉落() {
        CreateBattleRequest.Builder request = request(9301, PVE_SOLO, 42).setBattleConfigId(DUNGEON_WITH_DROP);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 500, 0, 0, 500);
        TurnBattleEngine engine = TestBattles.start(request, withDrops());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK));
        // 510 封顶为 1
        assertThat(describeAll(engine.resolveCurrentRound())).containsExactly(
                "g1 ATTACK 5001->M0",
                "g1 DAMAGE 5001->M0 v1",
                "g1 DEATH M0->M0");
        assertThat(engine.rngDrawsForTest()).isEqualTo(2);
        assertThat(engine.buildSettlement(PLAYER_A)).isEqualTo(BattleSettlementData.newBuilder()
                .setBattleId(9301)
                .setPlayerId(PLAYER_A)
                .setOutcome(BATTLE_OUTCOME_SIDE_A_WIN)
                .setHealth(1000)
                .setExpGain(7)
                .setGoldGain(3)
                .addItemsGained(BattleItemEntry.newBuilder().setItemTableId(ITEM_POTION).setCount(2))
                .setTotalRounds(1)
                .addDefeatedMonsters(BattleMonsterDefeat.newBuilder().setMonsterConfigId(TestTables.MONSTER_WITH_DROP).setCount(1))
                .build());
    }

    // ---- 其它【派生】结果 ----

    // G-TIMEOUT（TimeoutFillsDefaultBasicAttack，test.cpp:303-318）【派生】：A→M0 14，M0→A 15；2 抽
    @Test
    void 轨迹_超时默认普攻() {
        CreateBattleRequest.Builder request = request(9004, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        assertThat(describeAll(engine.resolveCurrentRound())).containsExactly(
                "g1 ATTACK 5001->M0",
                "g1 DAMAGE 5001->M0 v14 hp286",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v15 hp985");
        assertThat(engine.rngDrawsForTest()).isEqualTo(2);
    }

    // G-KILLALL（KillingAllMonstersWinsSideA，test.cpp:508-525）【派生】：984.85 封顶为 300
    @Test
    void 轨迹_一击清场() {
        TurnBattleEngine engine = soloEngine(9012, standard());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_NUKE));
        assertThat(describeAll(engine.resolveCurrentRound())).containsExactly(
                "g1 SKILL 5001->M0 skill103",
                "g1 DAMAGE 5001->M0 skill103 v300",
                "g1 DEATH M0->M0");
        assertThat(engine.rngDrawsForTest()).isZero();
    }

    // G-PRES（PresentationGroupId，test.cpp:1412-1446）【派生】：30 × 1560/1570 × 0.3 = 8.94 → 9；组号正好是 1 和 2
    @Test
    void 轨迹_表现分组() {
        CreateBattleRequest.Builder request = request(9101, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 120);
        addPlayer(request, PLAYER_B, 1, 1000, 1000, 20, 10, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A));
        assertThat(describeAll(engine.resolveCurrentRound())).containsExactly(
                "g1 ATTACK 5002->5001",
                "g1 DAMAGE 5002->5001 v9 hp991",
                "g2 ATTACK 5001->5002",
                "g2 DAMAGE 5001->5002 v9 hp991");
        assertThat(engine.rngDrawsForTest()).isZero();
    }

    private static TurnBattleEngine tankEngine(long battleId) {
        MemoryBattleData data = standard();
        data.addMonster(7010).setHealth(100000).setStrength(1).setSpeed(12);
        data.setDungeonMonsters(DUNGEON_CONFIG, 7010);
        CreateBattleRequest.Builder request = request(battleId, PVE_SOLO, 7);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 10, 0, 240);
        return TestBattles.start(request, data);
    }

    // G-STALE（MaxRoundsFallsBack 第一段，test.cpp:1379-1383）【派生】：30 回合，每回合 11 × 1560/1570 × 0.5 = 5.47 → 6，A 剩 820
    @Test
    void 轨迹_打满30回合() {
        TurnBattleEngine engine = tankEngine(9060);
        int rounds = 0;
        while (engine.outcome() == BATTLE_OUTCOME_ONGOING && rounds < 60) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
            engine.resolveCurrentRound();
            rounds++;
        }
        assertThat(rounds).isEqualTo(30);
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_B_WIN);
        BattleSettlementData settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getHealth()).isEqualTo(820);
        assertThat(settlement.getTotalRounds()).isEqualTo(30);
    }

    // G-FALLBACK（MonsterRowWithoutStats，test.cpp:1096-1128）【派生】：A 剩 865（怪物出手 9 次，每次 15）
    @Test
    void 轨迹_怪物行无属性回退默认值() {
        MemoryBattleData data = standard();
        data.addMonster(7002);
        data.setDungeonMonsters(DUNGEON_CONFIG, 7002);
        CreateBattleRequest.Builder request = request(9040, PVE_SOLO, 11);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, data);
        int rounds = 0;
        while (engine.outcome() == BATTLE_OUTCOME_ONGOING && rounds < 30) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
            engine.resolveCurrentRound();
            rounds++;
        }
        assertThat(rounds).isEqualTo(10);
        assertThat(engine.buildSettlement(PLAYER_A).getHealth()).isEqualTo(865);
    }

    // G-MONTAB（MonsterAttributesFromTable，test.cpp:902-945）【派生】：正好 7 回合（每刀 29，6 × 29 = 174 < 200）
    @Test
    void 轨迹_怪物属性读表正好7回合() {
        MemoryBattleData data = standard();
        data.addMonster(7000).setHealth(200).setStrength(10).setArmor(60).setSpeed(96).setExpReward(50).setGoldReward(25);
        data.setDungeonMonsters(DUNGEON_CONFIG, 7000);
        CreateBattleRequest.Builder request = request(9030, PVE_SOLO, 7);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 240);
        TurnBattleEngine engine = TestBattles.start(request, data);
        int rounds = 0;
        while (engine.outcome() == BATTLE_OUTCOME_ONGOING && rounds < 30) {
            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID));
            engine.resolveCurrentRound();
            rounds++;
        }
        assertThat(rounds).isEqualTo(7);
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
    }

    // ---- G-TIE（C++ 没有覆盖，Java 必须补上）【派生】 ----

    // 与兜底怪同速：按无符号比较 5001 < 2^63 + 2^32，玩家先手；误用有符号比较时怪物会先出手，伤害变成 15
    @Test
    void 轨迹_同速时actor_id按无符号升序() {
        CreateBattleRequest.Builder request = TestBattles.request(PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 60);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(engine.lastActionOrder()).containsExactly(PLAYER_A, MONSTER_ID);
        assertThat(describeAll(result)).containsExactly(
                "g1 DEFEND 5001->5001",
                "g2 ATTACK M0->5001",
                "g2 DAMAGE M0->5001 v8 hp992");
    }
}
