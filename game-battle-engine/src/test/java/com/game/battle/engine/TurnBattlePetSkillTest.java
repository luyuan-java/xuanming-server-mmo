package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPet;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.petActor;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestTables.BUFF_SILENCE;
import static com.game.battle.engine.TestTables.PET_A;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.PLAYER_C;
import static com.game.battle.engine.TestTables.SKILL_DAMAGE;
import static com.game.battle.engine.TestTables.SKILL_PASSIVE_ONLY;
import static com.game.battle.engine.TestTables.SKILL_POISON;
import static com.game.battle.engine.TestTables.standard;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_ATTACK;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_DAMAGE;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_MANA;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_SKILL;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleEventItem;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.table.Skillcost_resource;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 宝宝自动技能沿用正式技能校验、扣蓝和事件路径；客户端仍没有宝宝行动权。 */
class TurnBattlePetSkillTest {
    private static final long PET_ACTOR = BattleConstants.PET_ACTOR_ID_BASE;

    private static CreateBattleRequest.Builder battle(long mana, int... skills) {
        CreateBattleRequest.Builder request = request(9780, 3, 19);
        var owner = addPlayer(request, PLAYER_A, 0, 10_000, 10_000, 0, 100, 0, 100);
        var pet = addPet(owner, PET_A, 1_000, 1_000, 0, 200);
        pet.setMaxMana(100);
        pet.getBaseAttributesBuilder().setMana(mana);
        for (int skill : skills) {
            pet.addSkillTableIds(skill);
        }
        // 插入序故意与 actor_id 数字顺序相反，选目标不可偷偷排序或抽随机数。
        addPlayer(request, PLAYER_C, 1, 10_000, 10_000, 0, 100, 0, 90);
        addPlayer(request, PLAYER_B, 1, 10_000, 10_000, 0, 100, 0, 80);
        return request;
    }

    private static TurnResultS2C round(TurnBattleEngine engine) {
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        engine.submitAction(PLAYER_C, action(BATTLE_ACTION_DEFEND));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        return engine.resolveCurrentRound();
    }

    private static List<BattleEventItem> petEvents(TurnResultS2C result) {
        return result.getEventsList().stream().filter(event -> event.getSourceId() == PET_ACTOR).toList();
    }

    private static MemoryBattleData costingSkill() {
        MemoryBattleData data = standard();
        data.addSkill(SKILL_DAMAGE).addCostResource(Skillcost_resource.newBuilder()
                .setCostResourceId(BattleConstants.SKILL_COST_RESOURCE_MANA).setCostResourceCost(30));
        return data;
    }

    @Test
    void 自动施法目标按插入序_扣蓝伤害事件同组且不额外抽随机数() {
        TurnBattleEngine engine = TestBattles.start(battle(100, SKILL_DAMAGE), costingSkill());
        TurnResultS2C result = round(engine);
        var events = petEvents(result);
        BattleEventItem skill = events.stream().filter(e -> e.getEventType() == BATTLE_EVENT_SKILL).findFirst().orElseThrow();
        assertThat(skill.getSkillTableId()).isEqualTo(SKILL_DAMAGE);
        assertThat(skill.getTargetId()).isEqualTo(PLAYER_C);
        assertThat(events).filteredOn(e -> e.getEventType() == BATTLE_EVENT_MANA).singleElement().satisfies(event -> {
            assertThat(event.getValue()).isEqualTo(30);
            assertThat(event.getTargetManaAfter()).isEqualTo(70);
            assertThat(event.getGroupId()).isEqualTo(skill.getGroupId());
        });
        assertThat(events).filteredOn(e -> e.getEventType() == BATTLE_EVENT_DAMAGE).singleElement().satisfies(event -> {
            assertThat(event.getTargetId()).isEqualTo(PLAYER_C);
            assertThat(event.getGroupId()).isEqualTo(skill.getGroupId());
        });
        assertThat(petActor(engine.buildStateSnapshot(), PET_A).getAttributes().getMana()).isEqualTo(70);
        assertThat(engine.rngDrawsForTest()).isZero();
    }

    @Test
    void 冷却期间普攻_冷却结束继续施法() {
        TurnBattleEngine engine = TestBattles.start(battle(100, SKILL_DAMAGE), costingSkill());
        assertThat(petEvents(round(engine))).anyMatch(e -> e.getEventType() == BATTLE_EVENT_SKILL);
        assertThat(petEvents(round(engine))).noneMatch(e -> e.getEventType() == BATTLE_EVENT_SKILL)
                .anyMatch(e -> e.getEventType() == BATTLE_EVENT_ATTACK);
        assertThat(petEvents(round(engine))).anyMatch(e -> e.getEventType() == BATTLE_EVENT_SKILL);
        assertThat(petActor(engine.buildStateSnapshot(), PET_A).getAttributes().getMana()).isEqualTo(40);
    }

    @Test
    void 蓝不足和无技能的旧宝宝均走原普攻且不扣蓝() {
        for (int[] skills : List.of(new int[] {SKILL_DAMAGE}, new int[0])) {
            TurnBattleEngine engine = TestBattles.start(battle(20, skills), costingSkill());
            assertThat(petEvents(round(engine))).noneMatch(e -> e.getEventType() == BATTLE_EVENT_SKILL
                    || e.getEventType() == BATTLE_EVENT_MANA).anyMatch(e -> e.getEventType() == BATTLE_EVENT_ATTACK);
            assertThat(petActor(engine.buildStateSnapshot(), PET_A).getAttributes().getMana()).isEqualTo(20);
        }
    }

    @Test
    void 按快照顺序跳过蓝不足技能_冷却也可选下一技能() {
        TurnBattleEngine engine = TestBattles.start(battle(100, SKILL_DAMAGE, SKILL_POISON), costingSkill());
        assertThat(petEvents(round(engine))).filteredOn(e -> e.getEventType() == BATTLE_EVENT_SKILL)
                .extracting(BattleEventItem::getSkillTableId).containsExactly(SKILL_DAMAGE);
        assertThat(petEvents(round(engine))).filteredOn(e -> e.getEventType() == BATTLE_EVENT_SKILL)
                .extracting(BattleEventItem::getSkillTableId).containsExactly(SKILL_POISON);
        TurnBattleEngine poor = TestBattles.start(battle(20, SKILL_DAMAGE, SKILL_POISON), costingSkill());
        assertThat(petEvents(round(poor))).filteredOn(e -> e.getEventType() == BATTLE_EVENT_SKILL)
                .extracting(BattleEventItem::getSkillTableId).containsExactly(SKILL_POISON);
    }

    @Test
    void 沉默复用许可表回退普攻() {
        TurnBattleEngine engine = TestBattles.start(battle(100, SKILL_DAMAGE), costingSkill());
        assertThat(engine.addBuffForTest(PET_ACTOR, BUFF_SILENCE, PLAYER_C)).isTrue();
        assertThat(petEvents(round(engine))).noneMatch(e -> e.getEventType() == BATTLE_EVENT_SKILL)
                .anyMatch(e -> e.getEventType() == BATTLE_EVENT_ATTACK);
    }

    @Test
    void 入场过滤缺表和被动技能_保留顺序和重复项() {
        MemoryBattleData data = standard();
        data.addSkill(SKILL_PASSIVE_ONLY).addSkillType(BattleConstants.SKILL_TYPE_BIT_PASSIVE);
        TurnBattleEngine engine = TestBattles.start(battle(100, 9999, SKILL_PASSIVE_ONLY, SKILL_DAMAGE,
                SKILL_POISON, SKILL_DAMAGE), data);
        assertThat(petActor(engine.buildStateSnapshot(), PET_A).getSkillTableIdsList())
                .containsExactly(SKILL_DAMAGE, SKILL_POISON, SKILL_DAMAGE);
    }

    @Test
    void 客户端仍不能给宝宝提交行动或关闭自动行动() {
        TurnBattleEngine engine = TestBattles.start(battle(100, SKILL_DAMAGE), costingSkill());
        assertThat(engine.validateAction(PET_ACTOR, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_DAMAGE)))
                .isEqualTo(ActionChecks.INVALID_PARAMETER);
        assertThat(engine.submitAction(PET_ACTOR, action(BATTLE_ACTION_DEFEND))).isFalse();
        assertThat(engine.setActorAuto(PET_ACTOR, false)).isEqualTo(ActionChecks.INVALID_PARAMETER);
        assertThat(petEvents(round(engine))).anyMatch(e -> e.getEventType() == BATTLE_EVENT_SKILL);
    }
}
