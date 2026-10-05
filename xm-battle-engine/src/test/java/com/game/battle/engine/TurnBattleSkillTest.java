package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.countEvents;
import static com.game.battle.engine.TestBattles.firstEvent;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.BUFF_SILENCE;
import static com.game.battle.engine.TestTables.COOLDOWN_GROUP;
import static com.game.battle.engine.TestTables.MONSTER_ID;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.SILENCE_RESTRICTION;
import static com.game.battle.engine.TestTables.SKILL_DAMAGE;
import static com.game.battle.engine.TestTables.SKILL_NUKE;
import static com.game.battle.engine.TestTables.SKILL_PASSIVE_ONLY;
import static com.game.battle.engine.TestTables.SKILL_POISON;
import static com.game.battle.engine.TestTables.SUCCESS;
import static com.game.battle.engine.TestTables.standard;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_ATTACK;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_DAMAGE;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_MANA;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_SKILL;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_ONGOING;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.combat.CombatDamageRules;
import com.game.proto.BattleActorState;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.table.BagErrorTip;
import com.game.table.CommonErrorTip;
import com.game.table.SkillErrorTip;
import com.game.table.SkillTable;
import com.game.table.Skillcost_resource;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 技能（规格 §4）：冷却、沉默许可、耗蓝、物理 / 法术选攻、可施放过滤、校验链顺序、AOE 与单体目标。
 *
 * <p>前 5 个方法逐条移植基线 {@code turn_battle_engine_test.cpp}（【C++ 断言】），其后标「Java 追加」的用例按规格 §4 的条文写成，
 * 期望值是按规格手算的（伤害值注明了算式）。
 */
class TurnBattleSkillTest {

    private static final int PVE_SOLO = BattleConstants.MATCH_MODE_PVE_SOLO;
    private static final int PVE_TEAM = BattleConstants.MATCH_MODE_PVE_TEAM;
    private static final int INVALID_TABLE_ID = CommonErrorTip.common_error.kInvalidTableId_VALUE;
    private static final int INVALID_TABLE_DATA = CommonErrorTip.common_error.kInvalidTableData_VALUE;
    private static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final int INVALID_TARGET_ID = SkillErrorTip.skill_error.kSkillInvalidTargetId_VALUE;
    private static final int COOLDOWN_NOT_READY = SkillErrorTip.skill_error.kSkillCooldownNotReady_VALUE;
    private static final int CANNOT_CAST = SkillErrorTip.skill_error.kSkillCannotBeCastInCurrentState_VALUE;
    private static final int STUN_RESTRICTION = SkillErrorTip.skill_error.kSkillCannotBeCastStunRestriction_VALUE;

    // 基线 CooldownConvertsToRoundsAndBlocksResubmission（test.cpp:324-348）【C++ 断言】
    @Test
    void 冷却换算成回合_冷却中提交不落账() {
        CreateBattleRequest.Builder request = request(9005, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 第 1 回合：技能可用
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE))).isTrue();
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(countEvents(result, BATTLE_EVENT_SKILL)).isEqualTo(1);

        // 第 2 回合：冷却中，提交不落账（就绪保持 false），改普攻可就绪
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE))).isFalse();
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID))).isTrue();
        result = engine.resolveCurrentRound();
        assertThat(countEvents(result, BATTLE_EVENT_SKILL)).isZero();

        // 第 3 回合：冷却结束，技能恢复可用
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE))).isTrue();
        result = engine.resolveCurrentRound();
        assertThat(countEvents(result, BATTLE_EVENT_SKILL)).isEqualTo(1);
    }

    // 基线 SilenceBlocksGeneralSkillButAllowsBasicAttack（test.cpp:487-502）【C++ 断言】；
    // Java 追加：validateAction 回出许可行的格值 7005（规格 §13.4）
    @Test
    void 沉默拦下普通施放技能_普攻不受影响() {
        CreateBattleRequest.Builder request = request(9011, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        // 沉默必须在局内挂上：战前快照里的控制类 buff 一律剔除，从快照带入的沉默不会生效
        assertThat(engine.addBuffForTest(PLAYER_A, BUFF_SILENCE, MONSTER_ID)).isTrue();

        // 沉默中：普通施放类技能被许可表拦下，不落账
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE))).isFalse();
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE)))
                .as("Java 追加").isEqualTo(SILENCE_RESTRICTION);
        // 普攻不走技能许可，可就绪
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID))).isTrue();
    }

    // 基线 PhysicalSkillUsesPhysicalAttackWithMultiplier（test.cpp:1069-1094）【C++ 断言】
    @Test
    void 物理技能吃物伤并乘攻击倍率() {
        int skillPhysical = 105;
        MemoryBattleData data = standard();
        SkillTable.Builder skill = data.addSkill(skillPhysical);
        skill.addTargetingMode(1);
        skill.addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL);
        skill.setDamageType(CombatDamageRules.PHYSICAL_DAMAGE);
        skill.setAttackMultiplier(2.0);
        data.setSkillDamage(skillPhysical, 50.0);

        CreateBattleRequest.Builder request = request(9205, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 0, 120);
        snapshot.setPhysicalAttack(30);
        snapshot.setMagicAttack(999); // 物理技能不得吃法伤
        snapshot.addSkillTableIds(skillPhysical);
        TurnBattleEngine engine = TestBattles.start(request, data);

        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, skillPhysical))).isTrue();
        TurnResultS2C result = engine.resolveCurrentRound();

        BattleEventItem damage = firstEvent(result, BATTLE_EVENT_DAMAGE);
        assertThat(damage).isNotNull();
        // 50 × (1 + 4 × 0.1) + 30 × 2 = 130；130 × 1560 / (24 + 1560) ≈ 128.03 → 向上取整 129
        assertThat(damage.getValue()).isEqualTo(129);
    }

    // 基线 PresentationSkillManaCostEmitsManaEventInSkillGroup（test.cpp:1470-1509）【C++ 断言】
    @Test
    void 耗蓝技能产出MANA事件且与SKILL同组() {
        MemoryBattleData data = standard();
        data.addSkill(SKILL_DAMAGE).addCostResource(Skillcost_resource.newBuilder()
                .setCostResourceId(BattleConstants.SKILL_COST_RESOURCE_MANA)
                .setCostResourceCost(30));

        CreateBattleRequest.Builder request = request(9103, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 20, 10, 0, 240);
        snapshot.setMaxMana(100);
        snapshot.getBaseAttributesBuilder().setMana(100);
        TurnBattleEngine engine = TestBattles.start(request, data);

        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE))).isTrue();
        TurnResultS2C result = engine.resolveCurrentRound();

        BattleEventItem skillEvent = firstEvent(result, BATTLE_EVENT_SKILL);
        BattleEventItem manaEvent = firstEvent(result, BATTLE_EVENT_MANA);
        assertThat(skillEvent).isNotNull();
        assertThat(manaEvent).isNotNull();
        assertThat(manaEvent.getSourceId()).isEqualTo(PLAYER_A);
        assertThat(manaEvent.getTargetId()).isEqualTo(PLAYER_A);
        assertThat(manaEvent.getSkillTableId()).isEqualTo(SKILL_DAMAGE);
        assertThat(manaEvent.getValue()).isEqualTo(30);
        assertThat(manaEvent.getTargetManaAfter()).isEqualTo(70);
        assertThat(manaEvent.getGroupId()).isEqualTo(skillEvent.getGroupId());
        assertThat(countEvents(result, BATTLE_EVENT_MANA)).isEqualTo(1);

        BattleActorState actorA = stateActor(engine.buildStateSnapshot(), PLAYER_A);
        assertThat(actorA).isNotNull();
        assertThat(actorA.getAttributes().getMana()).isEqualTo(70);

        // 第二回合改用无耗蓝的挂毒技能：不再产出 MANA
        if (engine.outcome() == BATTLE_OUTCOME_ONGOING) {
            assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_POISON))).isTrue();
            TurnResultS2C second = engine.resolveCurrentRound();
            assertThat(countEvents(second, BATTLE_EVENT_MANA)).isZero();
        }
    }

    // 基线 PassiveSkillIsNotCastableAndNotListedOnActor（test.cpp:2098-2120）【C++ 断言】
    @Test
    void 被动技能不可施放也不进技能列表() {
        MemoryBattleData data = standard();
        data.addSkill(SKILL_PASSIVE_ONLY).addSkillType(BattleConstants.SKILL_TYPE_BIT_PASSIVE);

        CreateBattleRequest.Builder request = request(9309, PVE_SOLO, 7);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 500);
        snapshot.addSkillTableIds(SKILL_PASSIVE_ONLY);
        TurnBattleEngine engine = TestBattles.start(request, data);

        BattleActorState actor = stateActor(engine.buildStateSnapshot(), PLAYER_A);
        assertThat(actor).isNotNull();
        assertThat(actor.getSkillTableIdsList()).doesNotContain(SKILL_PASSIVE_ONLY);
        // 不在列表里 = 校验链按「未持有」拒绝
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_A, SKILL_PASSIVE_ONLY)))
                .isEqualTo(INVALID_PARAMETER);
    }

    // ---- Java 追加（规格 §4） ----

    // 规格 §4.1 / §1.5：入场过滤只留「表里有行、且可施放」的技能，保持快照顺序、不去重
    @Test
    void 入场技能过滤保持快照顺序且不去重() {
        MemoryBattleData data = standard();
        data.addSkill(402).addSkillType(BattleConstants.SKILL_TYPE_BIT_TOGGLE);
        data.addSkill(403).addSkillType(BattleConstants.SKILL_TYPE_BIT_CHANNEL);
        data.addSkill(404).addSkillType(11);
        CreateBattleRequest.Builder request = request(9620, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        snapshot.addSkillTableIds(402).addSkillTableIds(9999).addSkillTableIds(403).addSkillTableIds(404)
                .addSkillTableIds(SKILL_DAMAGE);
        TurnBattleEngine engine = TestBattles.start(request, data);

        assertThat(stateActor(engine.buildStateSnapshot(), PLAYER_A).getSkillTableIdsList())
                .containsExactly(101, 102, 103, 104, 404, 101);
    }

    // 规格 §4.2：校验链顺序固定，命中第一条即返回
    @Test
    void 技能校验链按固定顺序回码() {
        MemoryBattleData data = standard();
        // 105：与 101 同冷却组；106：耗蓝 50；107：玩家不持有；108：类型位号 6 超出沉默许可行宽
        data.addSkill(105).addTargetingMode(1).addSkillType(1).setCooldownId(COOLDOWN_GROUP);
        data.addSkill(106).addTargetingMode(1).addSkillType(1).addCostResource(Skillcost_resource.newBuilder()
                .setCostResourceId(BattleConstants.SKILL_COST_RESOURCE_MANA).setCostResourceCost(50));
        data.addSkill(107).addTargetingMode(1).addSkillType(1);
        data.addSkill(108).addTargetingMode(1).addSkillType(6);
        data.addBuff(250).setBuffType(BattleConstants.BUFF_TYPE_STUN).setDuration(12.0);
        CreateBattleRequest.Builder request = request(9621, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder snapshot = addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        snapshot.addSkillTableIds(105).addSkillTableIds(106).addSkillTableIds(108);
        snapshot.setMaxMana(100);
        snapshot.getBaseAttributesBuilder().setMana(40);
        TurnBattleEngine engine = TestBattles.start(request, data);

        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 9999))).isEqualTo(INVALID_TABLE_ID);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 0))).isEqualTo(INVALID_TABLE_ID);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 107))).isEqualTo(INVALID_PARAMETER);
        // 零目标闸：targeting_mode 非空且目标为 0
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, 0, SKILL_DAMAGE))).isEqualTo(INVALID_TARGET_ID);
        // 指向性：目标不存在
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, 999999, SKILL_DAMAGE))).isEqualTo(INVALID_TARGET_ID);
        // 指向性不查阵营，也不禁止选自己（规格 §4.3，基线行为 N1）
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_A, SKILL_DAMAGE))).isEqualTo(SUCCESS);
        // 耗蓝：50 > 40
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 106))).isEqualTo(CANNOT_CAST);

        // 放出 101 后，同冷却组的 105 也在冷却中
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE));
        engine.resolveCurrentRound();
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE))).isEqualTo(COOLDOWN_NOT_READY);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 105))).isEqualTo(COOLDOWN_NOT_READY);
        // 目标校验在冷却之前
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, 0, 105))).isEqualTo(INVALID_TARGET_ID);

        // 沉默中：许可行宽 6，位号 6 越界 → 1002（规格 §4.5）；冷却仍在 CheckBuff 之前
        assertThat(engine.addBuffForTest(PLAYER_A, BUFF_SILENCE, MONSTER_ID)).isTrue();
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 108))).isEqualTo(INVALID_TABLE_DATA);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 105))).isEqualTo(COOLDOWN_NOT_READY);

        // 眩晕：CheckState 排在一切技能检查之前（连不存在的技能也回 7006），普攻、防御同样被拒
        assertThat(engine.addBuffForTest(PLAYER_A, 250, MONSTER_ID)).isTrue();
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 9999))).isEqualTo(STUN_RESTRICTION);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID))).isEqualTo(STUN_RESTRICTION);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_DEFEND))).isEqualTo(STUN_RESTRICTION);
    }

    // 规格 §4.5：许可行缺失回 1002（与实时侧缺行回 1001 不同），格值为 0 也回 1002
    @Test
    void 沉默许可行缺失或格值为0回1002() {
        // 只有技能 101 与沉默 buff、没有许可行的一套表
        MemoryBattleData noPermission = new MemoryBattleData();
        noPermission.addSkill(SKILL_DAMAGE).addTargetingMode(1).addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL);
        noPermission.addBuff(BUFF_SILENCE).setBuffType(BattleConstants.BUFF_TYPE_SILENCE).setDuration(12.0);
        CreateBattleRequest.Builder first = request(9622, PVE_SOLO, 1);
        addPlayer(first, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(first, noPermission);
        assertThat(stateActor(engine.buildStateSnapshot(), PLAYER_A).getSkillTableIdsList()).containsExactly(SKILL_DAMAGE);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE))).isEqualTo(SUCCESS);
        assertThat(engine.addBuffForTest(PLAYER_A, BUFF_SILENCE, MONSTER_ID)).isTrue();
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE))).isEqualTo(INVALID_TABLE_DATA);

        // 格值 0：「没填」而不是 tip 码
        MemoryBattleData zeroCell = standard();
        zeroCell.addSkillPermission(BattleConstants.COMBAT_STATE_SILENCE).setSkillType(BattleConstants.SKILL_TYPE_BIT_ACTIVATE, 0);
        zeroCell.addSkill(109).addTargetingMode(1).addSkillType(BattleConstants.SKILL_TYPE_BIT_ACTIVATE);
        zeroCell.addSkill(110).addTargetingMode(1).addSkillType(BattleConstants.SKILL_TYPE_BIT_BASIC_ATTACK);
        CreateBattleRequest.Builder second = request(9623, PVE_SOLO, 1);
        addPlayer(second, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120).addSkillTableIds(109).addSkillTableIds(110);
        TurnBattleEngine silenced = TestBattles.start(second, zeroCell);
        assertThat(silenced.addBuffForTest(PLAYER_A, BUFF_SILENCE, MONSTER_ID)).isTrue();
        assertThat(silenced.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 109))).isEqualTo(INVALID_TABLE_DATA);
        assertThat(silenced.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 110))).as("格值 1000 放行").isEqualTo(SUCCESS);
    }

    // 规格 §4.4：冷却衰减只减不删，{skill: 0} 一直留在快照里；500 ms 换成 1 回合，施放当回合末就衰减到 0，等于没有冷却
    @Test
    void 冷却衰减到0不删条目_500毫秒冷却等于没有冷却() {
        MemoryBattleData data = standard();
        data.addSkill(111).addTargetingMode(1).addSkillType(1).setCooldownId(12);
        data.setCooldownMs(12, 500);
        data.setSkillDamage(111, 1.0);
        CreateBattleRequest.Builder request = request(9624, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120).addSkillTableIds(111);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE));
        TurnResultS2C first = engine.resolveCurrentRound();
        assertThat(stateActor(first.getState(), PLAYER_A).getSkillCooldownRoundsMap()).containsExactly(java.util.Map.entry(101, 1));

        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 111))).isTrue();
        TurnResultS2C second = engine.resolveCurrentRound();
        assertThat(stateActor(second.getState(), PLAYER_A).getSkillCooldownRoundsMap())
                .containsOnly(java.util.Map.entry(101, 0), java.util.Map.entry(111, 0));

        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, 111))).isEqualTo(SUCCESS);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE))).isEqualTo(SUCCESS);
    }

    // 规格 §4.3 / §4.7：AOE 技能只要目标非 0 就通过校验；出手时按插入序打全体存活敌方，hit_index 0..n-1，不耗随机数
    @Test
    void AOE技能按插入序打全体敌方_hit_index递增() {
        MemoryBattleData data = standard();
        data.addSkill(112).addTargetingMode(2).addSkillType(1);
        data.setSkillDamage(112, 50.0);
        CreateBattleRequest.Builder request = request(9625, PVE_TEAM, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 240).addSkillTableIds(112);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, data);

        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, 999999, 112))).isEqualTo(SUCCESS);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, 0, 112))).isEqualTo(INVALID_TARGET_ID);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, 999999, 112));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();

        // 50 × 1560 / 1584 = 49.24 → 50；两只兜底怪各 300 血
        assertThat(EngineTestSupport.describeAll(result).subList(0, 4)).containsExactly(
                "g1 SKILL 5001->M0 skill112",
                "g1 DAMAGE 5001->M0 skill112 v50 hp250",
                "g1 h1 DAMAGE 5001->M1 skill112 v50 hp250",
                "g2 DEFEND 5002->5002");
        assertThat(engine.rngDrawsForTest()).as("只有两只怪各选一次目标").isEqualTo(2);
    }

    // 规格 §4.3：AOE 的判定与校验不同源 —— targeting_mode [1, 2] 按指向性校验、按 AOE 出手（真表技能 1 的形状）
    @Test
    void 指向性加AOE位号的技能按指向性校验_按AOE出手() {
        MemoryBattleData data = standard();
        data.addSkill(113).addTargetingMode(1).addTargetingMode(2).addSkillType(1);
        data.setSkillDamage(113, 50.0);
        CreateBattleRequest.Builder request = request(9626, PVE_TEAM, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 240).addSkillTableIds(113);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, data);

        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, 999999, 113))).isEqualTo(INVALID_TARGET_ID);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_B, 113))).as("阵营不限").isEqualTo(SUCCESS);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID + 1, 113));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(EngineTestSupport.describeAll(result).subList(0, 3)).containsExactly(
                "g1 SKILL 5001->M0 skill113",
                "g1 DAMAGE 5001->M0 skill113 v50 hp250",
                "g1 h1 DAMAGE 5001->M1 skill113 v50 hp250");
    }

    // 规格 §4.7 / §11.1 第 1 条：单体技能不查阵营，可以打队友（基线行为 N1，照搬）
    @Test
    void 单体技能可以打队友() {
        CreateBattleRequest.Builder request = request(9627, PVE_TEAM, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 240);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_NUKE))).isFalse();
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();
        // 1000 × 1560 / (100 + 1560) = 939.76 → 940；B 在 A 之后才防御，不减半
        assertThat(EngineTestSupport.describeAll(result).subList(0, 2)).containsExactly(
                "g1 SKILL 5001->5002 skill103",
                "g1 DAMAGE 5001->5002 skill103 v940 hp60");
    }

    // 规格 §4.7 第 1 步与 R3：名义目标在出手前死了，重验失败，整个技能降级为普攻，事件落在同一组
    @Test
    void 名义目标先死时技能降级为普攻() {
        MemoryBattleData data = standard();
        data.addMonster(7801).setHealth(50).setStrength(1).setSpeed(12);
        data.addMonster(7802).setHealth(50).setStrength(1).setSpeed(12);
        data.setDungeonMonsters(TestTables.DUNGEON_CONFIG, 7801, 7802);
        CreateBattleRequest.Builder request = request(9628, PVE_TEAM, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 240);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, data);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID + 1, SKILL_NUKE));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_SKILL, MONSTER_ID + 1, SKILL_DAMAGE));
        TurnResultS2C result = engine.resolveCurrentRound();

        List<String> events = EngineTestSupport.describeAll(result);
        // A 秒掉 M1（1000 × 1 封顶 50）；B 的技能降级：普攻 M1 失效 → 唯一候选 M0（耗 1 抽），10 点
        assertThat(events.subList(0, 5)).containsExactly(
                "g1 SKILL 5001->M1 skill103",
                "g1 DAMAGE 5001->M1 skill103 v50",
                "g1 DEATH M1->M1",
                "g2 ATTACK 5002->M0",
                "g2 DAMAGE 5002->M0 v10 hp40");
        assertThat(countEvents(result, BATTLE_EVENT_SKILL)).isEqualTo(1);
        BattleStateS2C state = result.getState();
        assertThat(stateActor(state, PLAYER_B).getSkillCooldownRoundsMap()).as("降级的技能不开冷却").isEmpty();
        // 抽数：B 降级普攻重选 1 次 + M0 选目标 1 次
        assertThat(engine.rngDrawsForTest()).isEqualTo(2);
    }
}
