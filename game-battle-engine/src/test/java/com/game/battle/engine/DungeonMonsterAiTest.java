package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.battle.engine.DungeonMonsterAi.BossPhaseId;
import com.game.battle.engine.DungeonMonsterAi.Candidate;
import com.game.battle.engine.DungeonMonsterAi.Controller;
import com.game.battle.engine.DungeonMonsterAi.DecisionInput;
import com.game.battle.engine.DungeonMonsterAi.Profile;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleEventType;
import com.game.proto.eBattleOutcome;
import com.game.table.Skillcost_resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 副本怪物智能 AI（HFSM 阶段 + 共享行为树 + 数据训练定点效用打分）集成与确定性回放测试，对齐 C++ dungeon_monster_ai_test.cpp。 */
class DungeonMonsterAiTest {

    private static final int DUNGEON_ID = 41;
    private static final int BOSS_TABLE_ID = 201;
    private static final long BOSS_ACTOR_ID = BattleConstants.MONSTER_ACTOR_ID_BASE;
    private static final long TANK_PLAYER_ID = 9101L;
    private static final long LOW_HP_PLAYER_ID = 9102L;
    private static final int BOSS_SLAM_SKILL_ID = 9701;
    private static final int COSTLY_SKILL_ID = 9702;

    private static MemoryBattleData makeDungeonData() {
        MemoryBattleData data = new MemoryBattleData();

        data.addSkill(BOSS_SLAM_SKILL_ID)
                .addTargetingMode(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL);
        data.setSkillDamage(BOSS_SLAM_SKILL_ID, 40.0);

        data.addSkill(COSTLY_SKILL_ID)
                .addTargetingMode(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL)
                .addCostResource(Skillcost_resource.newBuilder()
                        .setCostResourceId(BattleConstants.SKILL_COST_RESOURCE_MANA)
                        .setCostResourceCost(20));

        data.addMonster(BOSS_TABLE_ID)
                .setHealth(2000)
                .setStrength(25)
                .setSpeed(50);

        data.addDungeon(DUNGEON_ID);
        data.setDungeonMonsters(DUNGEON_ID, BOSS_TABLE_ID);
        return data;
    }

    private static CreateBattleRequest makeDungeonRequest(long tankHp, long lowHp, long playerAtk) {
        CreateBattleRequest.Builder req = CreateBattleRequest.newBuilder()
                .setBattleId(88001L)
                .setBattleConfigId(DUNGEON_ID)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_TEAM)
                .setSeed(20261120L);
        addPlayer(req, TANK_PLAYER_ID, tankHp, 120, playerAtk);
        addPlayer(req, LOW_HP_PLAYER_ID, lowHp, 150, playerAtk);
        return req.build();
    }

    private static void addPlayer(CreateBattleRequest.Builder req, long playerId, long hp, long speed, long atk) {
        BattlePlayerSnapshot.Builder snap = req.addPlayersBuilder()
                .setPlayerId(playerId)
                .setPlayerName("hero")
                .setLevel(30)
                .setTeamIndex(0)
                .setMaxHealth(1000)
                .setMaxMana(100)
                .setPhysicalAttack(atk);
        BaseAttributesComp.Builder attr = snap.getBaseAttributesBuilder();
        attr.setHealth(hp).setMana(100).setSpeed(speed).setStrength(10);
    }

    private static TurnBattleEngine startEngine(CreateBattleRequest req, BattleData data, Map<Integer, Profile> profiles) {
        BattleStart started = TurnBattleEngine.start(req, data, profiles);
        assertThat(started).isInstanceOf(BattleStart.Started.class);
        return ((BattleStart.Started) started).engine();
    }

    @Test
    void 训练后Boss优先用零耗蓝技能集火残血高速目标并过滤耗蓝技能() {
        MemoryBattleData data = makeDungeonData();
        Profile profile = DungeonMonsterAi.buildTrainedDefaultProfile()
                .withMonsterSkillIds(List.of(BOSS_SLAM_SKILL_ID, COSTLY_SKILL_ID));

        TurnBattleEngine engine = startEngine(makeDungeonRequest(1000, 250, 50), data, Map.of(BOSS_TABLE_ID, profile));
        assertThat(engine.monsterAiPhaseOf(BOSS_ACTOR_ID)).contains(BossPhaseId.OPENING);

        BattleStateS2C initSnap = engine.buildStateSnapshot();
        BattleActorState bossState = initSnap.getActorsList().stream()
                .filter(a -> a.getActorId() == BOSS_ACTOR_ID)
                .findFirst()
                .orElseThrow();
        assertThat(bossState.getSkillTableIdsList()).containsExactly(BOSS_SLAM_SKILL_ID);

        BattleAction defend = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build();
        engine.submitAction(TANK_PLAYER_ID, defend);
        engine.submitAction(LOW_HP_PLAYER_ID, defend);

        TurnResultS2C turn = engine.resolveCurrentRound();
        boolean bossUsedSkillOnLowHp = turn.getEventsList().stream().anyMatch(ev ->
                ev.getSourceId() == BOSS_ACTOR_ID
                        && ev.getEventType() == eBattleEventType.BATTLE_EVENT_SKILL
                        && ev.getSkillTableId() == BOSS_SLAM_SKILL_ID
                        && ev.getTargetId() == LOW_HP_PLAYER_ID);
        assertThat(bossUsedSkillOnLowHp).as("训练后的副本 Boss 应优先用零耗蓝技能集火残血高速目标").isTrue();
    }

    @Test
    void Boss分层阶段切换保留Combat父态且绝境触发带冷却的防御自保() {
        Profile profile = DungeonMonsterAi.buildTrainedDefaultProfile()
                .withThresholds(6000, 3000, 2500, 3);
        Controller controller = new Controller(profile);
        assertThat(controller.currentPhase()).isEqualTo(BossPhaseId.OPENING);
        assertThat(controller.combatEnterCount()).isEqualTo(1);
        assertThat(controller.combatExitCount()).isZero();

        BattleAction atk = BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK)
                .setTargetId(TANK_PLAYER_ID)
                .build();
        Candidate cand = new Candidate(atk, 0, new int[] {5000, 10000, 5000, 0, 0, 10000});

        // Round 1: 50% HP -> Opening -> Enrage，共享父态 Combat 不退出不重入。
        BattleAction a1 = controller.decideAction(new DecisionInput(1, 5000, true, true, List.of(cand))).orElseThrow();
        assertThat(a1.getActionType()).isEqualTo(eBattleActionType.BATTLE_ACTION_ATTACK);
        assertThat(controller.currentPhase()).isEqualTo(BossPhaseId.ENRAGE);
        assertThat(controller.combatEnterCount()).isEqualTo(1);
        assertThat(controller.combatExitCount()).isZero();

        // Round 2: 20% HP -> Enrage -> Desperate，行为树高优分支触发 DEFEND。
        BattleAction a2 = controller.decideAction(new DecisionInput(2, 2000, true, true, List.of(cand))).orElseThrow();
        assertThat(a2.getActionType()).isEqualTo(eBattleActionType.BATTLE_ACTION_DEFEND);
        assertThat(controller.currentPhase()).isEqualTo(BossPhaseId.DESPERATE);
        assertThat(controller.combatEnterCount()).isEqualTo(1);
        assertThat(controller.combatExitCount()).isZero();

        // Round 3: 仍在 3 回合防御冷却内，回落到效用打分分支出击。
        BattleAction a3 = controller.decideAction(new DecisionInput(3, 2000, true, true, List.of(cand))).orElseThrow();
        assertThat(a3.getActionType()).isEqualTo(eBattleActionType.BATTLE_ACTION_ATTACK);

        // Round 5: 冷却结束 (2 + 3 = 5)，再次触发防御自保。
        BattleAction a5 = controller.decideAction(new DecisionInput(5, 2000, true, true, List.of(cand))).orElseThrow();
        assertThat(a5.getActionType()).isEqualTo(eBattleActionType.BATTLE_ACTION_DEFEND);

        // 阵亡事件冒泡至 ROOT -> DEAD，退出 Combat 一次。
        controller.notifyDead();
        assertThat(controller.currentPhase()).isEqualTo(BossPhaseId.DEAD);
        assertThat(controller.isInCombat()).isFalse();
        assertThat(controller.combatExitCount()).isEqualTo(1);
    }

    @Test
    void 相同种子与AI配置产出逐字节一致的多回合结果() {
        MemoryBattleData data = makeDungeonData();
        Profile profile = DungeonMonsterAi.buildTrainedDefaultProfile()
                .withMonsterSkillIds(List.of(BOSS_SLAM_SKILL_ID));

        List<String> first = runThreeRounds(data, profile);
        List<String> second = runThreeRounds(data, profile);
        assertThat(first).isNotEmpty().isEqualTo(second);
    }

    private static List<String> runThreeRounds(MemoryBattleData data, Profile profile) {
        TurnBattleEngine engine = startEngine(makeDungeonRequest(1000, 600, 180), data, Map.of(0, profile));
        List<String> hexRounds = new ArrayList<>();
        for (int r = 0; r < 3 && engine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; r++) {
            BattleAction atk = BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK)
                    .setTargetId(BOSS_ACTOR_ID)
                    .build();
            engine.submitAction(TANK_PLAYER_ID, atk);
            engine.submitAction(LOW_HP_PLAYER_ID, atk);
            hexRounds.add(EngineTestSupport.hex(EngineTestSupport.deterministicBytes(engine.resolveCurrentRound())));
        }
        return hexRounds;
    }

    @Test
    void 未配置AI的对局保持存量默认怪物行为与RNG完全不变() {
        MemoryBattleData data = makeDungeonData();
        CreateBattleRequest req = makeDungeonRequest(1000, 500, 50);
        TurnBattleEngine a = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        TurnBattleEngine b = ((BattleStart.Started) TurnBattleEngine.start(req, data, Map.of())).engine();
        assertThat(a.monsterAiPhaseOf(BOSS_ACTOR_ID)).isEmpty();

        BattleAction defend = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build();
        a.submitAction(TANK_PLAYER_ID, defend);
        a.submitAction(LOW_HP_PLAYER_ID, defend);
        b.submitAction(TANK_PLAYER_ID, defend);
        b.submitAction(LOW_HP_PLAYER_ID, defend);

        assertThat(EngineTestSupport.deterministicBytes(a.resolveCurrentRound()))
                .isEqualTo(EngineTestSupport.deterministicBytes(b.resolveCurrentRound()));
    }
}
