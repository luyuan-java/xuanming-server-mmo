package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleScript;
import com.game.proto.BattleScriptCondition;
import com.game.proto.BattleScriptRole;
import com.game.proto.BattleScriptRule;
import com.game.proto.BattleScriptTarget;
import com.game.proto.BattleScriptTargetFilter;
import com.game.proto.BattleTeamScript;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleEventType;
import com.game.proto.eBattleScriptCondition;
import com.game.proto.eBattleScriptTargetFilter;
import com.game.proto.eBattleScriptTargetOrder;
import com.game.proto.eBattleScriptTargetSide;
import com.game.table.Skillcost_resource;
import org.junit.jupiter.api.Test;

/** 战斗剧本开局复核、怪物技能派生、角色位认领（含手动行动认领）、筛选排序与确定性求值测试（对齐 C++ battle_script_test）。 */
class BattleScriptEvaluatorTest {

    private static final int DUNGEON_ID = 55;
    private static final int BOSS_ID = 301;
    private static final int FREE_SKILL_ID = 9801;
    private static final int COSTLY_SKILL_ID = 9802;
    private static final int POISON_BUFF_ID = 7701;

    private static MemoryBattleData makeData() {
        MemoryBattleData data = new MemoryBattleData();
        data.addSkill(FREE_SKILL_ID)
                .addTargetingMode(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL);
        data.setSkillDamage(FREE_SKILL_ID, 45.0);

        data.addSkill(COSTLY_SKILL_ID)
                .addTargetingMode(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL)
                .addCostResource(Skillcost_resource.newBuilder()
                        .setCostResourceId(BattleConstants.SKILL_COST_RESOURCE_MANA)
                        .setCostResourceCost(25));
        data.setSkillDamage(COSTLY_SKILL_ID, 60.0);

        data.addBuff(POISON_BUFF_ID)
                .setBuffType(BattleConstants.BUFF_TYPE_POISON);

        data.addMonster(BOSS_ID)
                .setHealth(3000)
                .setStrength(20)
                .setSpeed(50);

        data.addDungeon(DUNGEON_ID);
        data.setDungeonMonsters(DUNGEON_ID, BOSS_ID, BOSS_ID);
        return data;
    }

    private static BattlePlayerSnapshot player(long id, int team, int classId, long hp, long maxHp, long speed) {
        return BattlePlayerSnapshot.newBuilder()
                .setPlayerId(id)
                .setPlayerName("p" + id)
                .setLevel(30)
                .setTeamIndex(team)
                .setClassId(classId)
                .setMaxHealth(maxHp)
                .setMaxMana(100)
                .setPhysicalAttack(20)
                .setMagicAttack(20)
                .setDefense(10)
                .addSkillTableIds(FREE_SKILL_ID)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(hp)
                        .setMana(100)
                        .setSpeed(speed)
                        .setStrength(15)
                        .setArmor(10))
                .build();
    }

    @Test
    void 比例交叉相乘无符号饱和且上限为零视为满血() {
        assertThat(BattleScriptEvaluator.ratioBelowPermyriad(499, 1000, 5000)).isTrue();
        assertThat(BattleScriptEvaluator.ratioBelowPermyriad(500, 1000, 5000)).isFalse();
        assertThat(BattleScriptEvaluator.ratioBelowPermyriad(0, 0, 5000)).isFalse();
        // 饱和防溢：2^63 * 10000 饱和到 UINT64_MAX，与 UINT64_MAX 比较为 false
        assertThat(BattleScriptEvaluator.ratioBelowPermyriad(Long.MIN_VALUE, Long.MIN_VALUE, 10_000)).isFalse();

        assertThat(BattleScriptEvaluator.ratioLess(1, 3, 1, 2)).isTrue();
        assertThat(BattleScriptEvaluator.ratioLess(1, 2, 1, 2)).isFalse();
        assertThat(BattleScriptEvaluator.ratioLess(5, 10, 0, 0)).isTrue();  // max=0 视为 1/1
        assertThat(BattleScriptEvaluator.ratioLess(0, 0, 5, 10)).isFalse();
    }

    @Test
    void 非法剧本开局忽略并记入拒绝列表_不影响正常对局() {
        MemoryBattleData data = makeData();
        // 怪物方剧本引用耗蓝技能 -> CheckBattleScriptSide 拒绝
        BattleScript badMonsterScript = BattleScript.newBuilder()
                .setScriptId(99)
                .setSchemaVersion(1)
                .addRoles(BattleScriptRole.newBuilder()
                        .setRoleId(1)
                        .setOrder(1)
                        .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER)
                        .addRules(BattleScriptRule.newBuilder()
                                .setPriority(1)
                                .setAction(eBattleActionType.BATTLE_ACTION_SKILL)
                                .setSkillId(COSTLY_SKILL_ID)
                                .setTarget(BattleScriptTarget.newBuilder()
                                        .setSide(eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY))))
                .build();
        // schema_version 非法 + 重复 team_index
        BattleScript badVersion = BattleScript.newBuilder().setScriptId(100).setSchemaVersion(2).build();
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(90001L)
                .setBattleConfigId(DUNGEON_ID)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setSeed(42L)
                .addPlayers(player(1001L, 0, 1, 1000, 1000, 100))
                .addTeamScripts(BattleTeamScript.newBuilder().setTeamIndex(1).setScript(badMonsterScript))
                .addTeamScripts(BattleTeamScript.newBuilder().setTeamIndex(0).setScript(badVersion))
                .addTeamScripts(BattleTeamScript.newBuilder().setTeamIndex(0).setScript(badVersion))
                .build();

        BattleStart started = TurnBattleEngine.start(req, data);
        assertThat(started).isInstanceOf(BattleStart.Started.class);
        TurnBattleEngine engine = ((BattleStart.Started) started).engine();
        assertThat(engine.scriptRejections()).hasSize(3);
        assertThat(engine.scriptRejections().get(0)).contains("costs mana");
        assertThat(engine.scriptRejections().get(1)).contains("unsupported schema_version");
        assertThat(engine.scriptRejections().get(2)).contains("more than one script");
    }

    @Test
    void 怪物剧本派生零耗蓝技能并按气血比例最低选敌() {
        MemoryBattleData data = makeData();
        BattleScript monsterScript = BattleScript.newBuilder()
                .setScriptId(10)
                .setSchemaVersion(1)
                .addRoles(BattleScriptRole.newBuilder()
                        .setRoleId(1)
                        .setOrder(1)
                        .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER)
                        .addRules(BattleScriptRule.newBuilder()
                                .setPriority(1)
                                .addConditions(BattleScriptCondition.newBuilder()
                                        .setKind(eBattleScriptCondition.BATTLE_SCRIPT_COND_ROUND_EVERY)
                                        .setP1(1)
                                        .setP2(1))
                                .setAction(eBattleActionType.BATTLE_ACTION_SKILL)
                                .setSkillId(FREE_SKILL_ID)
                                .setTarget(BattleScriptTarget.newBuilder()
                                        .setSide(eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY)
                                        .setOrder(eBattleScriptTargetOrder.BATTLE_SCRIPT_ORDER_HP_RATIO_LOWEST))))
                .build();

        // 1001 血量 400/500 (80%), 1002 血量 500/1000 (50%, 血量更高但比例更低)
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(90002L)
                .setBattleConfigId(DUNGEON_ID)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_TEAM)
                .setSeed(777L)
                .addPlayers(player(1001L, 0, 1, 400, 500, 100))
                .addPlayers(player(1002L, 0, 2, 500, 1000, 90))
                .addTeamScripts(BattleTeamScript.newBuilder().setTeamIndex(1).setScript(monsterScript))
                .build();

        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        assertThat(engine.scriptRejections()).isEmpty();
        TurnResultS2C turn = engine.resolveCurrentRound();

        // 两只怪物均派生了 FREE_SKILL_ID 且集火气血比例最低的 1002
        long monsterSkillHitsOn1002 = turn.getEventsList().stream()
                .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_SKILL
                        && e.getSkillTableId() == FREE_SKILL_ID
                        && e.getTargetId() == 1002L)
                .count();
        assertThat(monsterSkillHitsOn1002).isEqualTo(2L);
    }

    @Test
    void 角色位按order与认领表联动_手动提交目标也能被队友剧本集火() {
        MemoryBattleData data = makeData();
        long secondMonsterId = BattleConstants.MONSTER_ACTOR_ID_BASE + 1;

        // 角色位 1（职业 1）：未手动则普攻阵位第一；角色位 2（职业 2）：专打被角色位 1 本回合认领的目标（CLAIMED_BY_ROLE=1）
        BattleScript playerScript = BattleScript.newBuilder()
                .setScriptId(20)
                .setSchemaVersion(1)
                .addRoles(BattleScriptRole.newBuilder()
                        .setRoleId(2)
                        .setOrder(2)
                        .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER)
                        .setClassId(2)
                        .addRules(BattleScriptRule.newBuilder()
                                .setPriority(1)
                                .setAction(eBattleActionType.BATTLE_ACTION_SKILL)
                                .setSkillId(FREE_SKILL_ID)
                                .setTarget(BattleScriptTarget.newBuilder()
                                        .setSide(eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY)
                                        .addFilters(BattleScriptTargetFilter.newBuilder()
                                                .setKind(eBattleScriptTargetFilter.BATTLE_SCRIPT_FILTER_CLAIMED_BY_ROLE)
                                                .setP1(1)))))
                .addRoles(BattleScriptRole.newBuilder()
                        .setRoleId(1)
                        .setOrder(1)
                        .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER)
                        .setClassId(1)
                        .addRules(BattleScriptRule.newBuilder()
                                .setPriority(1)
                                .setAction(eBattleActionType.BATTLE_ACTION_ATTACK)
                                .setTarget(BattleScriptTarget.newBuilder()
                                        .setSide(eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY))))
                .build();

        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(90003L)
                .setBattleConfigId(DUNGEON_ID)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_TEAM)
                .setSeed(999L)
                .addPlayers(player(2001L, 0, 1, 1000, 1000, 120))
                .addPlayers(player(2002L, 0, 2, 1000, 1000, 110))
                .addTeamScripts(BattleTeamScript.newBuilder().setTeamIndex(0).setScript(playerScript))
                .build();

        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        assertThat(engine.scriptRejections()).isEmpty();
        // 2001 手动点选第二只怪物 -> 认领表记下 (role=1, secondMonsterId)，2002 未提交行动 -> 剧本跟打 secondMonsterId！
        assertThat(engine.submitAction(2001L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK)
                .setTargetId(secondMonsterId)
                .build())).isFalse();

        TurnResultS2C turn = engine.resolveCurrentRound();
        BattleEventItem cast = turn.getEventsList().stream()
                .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_SKILL
                        && e.getSourceId() == 2002L)
                .findFirst()
                .orElseThrow();
        assertThat(cast.getSkillTableId()).isEqualTo(FREE_SKILL_ID);
        assertThat(cast.getTargetId()).isEqualTo(secondMonsterId);
    }

    @Test
    void 剧本不消耗随机数_与无剧本相同行动结果逐字节一致() {
        MemoryBattleData data = makeData();
        BattleScript attackFirst = BattleScript.newBuilder()
                .setScriptId(30)
                .setSchemaVersion(1)
                .addRoles(BattleScriptRole.newBuilder()
                        .setRoleId(1)
                        .setOrder(1)
                        .addRules(BattleScriptRule.newBuilder()
                                .setPriority(1)
                                .setAction(eBattleActionType.BATTLE_ACTION_ATTACK)
                                .setTarget(BattleScriptTarget.newBuilder()
                                        .setSide(eBattleScriptTargetSide.BATTLE_SCRIPT_TARGET_ENEMY)
                                        .setOrder(eBattleScriptTargetOrder.BATTLE_SCRIPT_ORDER_FORMATION))))
                .build();

        CreateBattleRequest base = CreateBattleRequest.newBuilder()
                .setBattleId(90004L)
                .setBattleConfigId(DUNGEON_ID)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setSeed(123456L)
                .addPlayers(player(3001L, 0, 1, 1000, 1000, 100))
                .build();
        CreateBattleRequest withScript = base.toBuilder()
                .addTeamScripts(BattleTeamScript.newBuilder().setTeamIndex(0).setScript(attackFirst))
                .build();

        TurnBattleEngine plain = ((BattleStart.Started) TurnBattleEngine.start(base, data)).engine();
        TurnBattleEngine scripted = ((BattleStart.Started) TurnBattleEngine.start(withScript, data)).engine();
        assertThat(scripted.resolveCurrentRound().toByteArray()).isEqualTo(plain.resolveCurrentRound().toByteArray());
    }
}
