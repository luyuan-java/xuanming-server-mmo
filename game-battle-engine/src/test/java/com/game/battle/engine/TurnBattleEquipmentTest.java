package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.*;
import static com.game.proto.eBattleActionType.*;
import static com.game.proto.eBattleEventType.*;
import static com.game.proto.eBattleHitKind.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CombatAttributes;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleHitKind;
import com.game.proto.eBattleOutcome;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 装备战斗规则的端到端领域用例；旧 Trace / Wire / RNG 金样不改。 */
class TurnBattleEquipmentTest {
    private record Fixture(CreateBattleRequest.Builder request, MemoryBattleData data,
                           BattlePlayerSnapshot.Builder a, BattlePlayerSnapshot.Builder b) { }
    private record Round(TurnBattleEngine engine, TurnResultS2C result) { }

    /** 物攻 90 + 基础 10，PvP ×0.3 = 30；不同速度保证顺序不吃随机数。 */
    private static Fixture fixture() {
        var req = request(3, 42);
        var a = addPlayer(req, PLAYER_A, 0, 10_000, 10_000, 0, 0, 0, 100).setPhysicalAttack(90).setMagicAttack(90);
        var b = addPlayer(req, PLAYER_B, 1, 10_000, 10_000, 0, 0, 0, 10).setPhysicalAttack(90).setMagicAttack(90);
        return new Fixture(req, standard(), a, b);
    }
    private static Round round(Fixture f) { return round(f, action(BATTLE_ACTION_ATTACK, PLAYER_B)); }
    private static Round round(Fixture f, BattleAction a) {
        var engine = TestBattles.start(f.request, f.data);
        assertThat(engine.validateAction(PLAYER_A, a)).isEqualTo(SUCCESS);
        assertThat(engine.validateAction(PLAYER_B, action(BATTLE_ACTION_DEFEND))).isEqualTo(SUCCESS);
        engine.submitAction(PLAYER_A, a);
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        return new Round(engine, engine.resolveCurrentRound());
    }
    private static List<BattleEventItem> damage(TurnResultS2C result) {
        return result.getEventsList().stream().filter(e -> e.getEventType() == BATTLE_EVENT_DAMAGE).toList();
    }

    @Test
    void 仅玩家复制全部15字段_presence缺失保持缺失_宝宝怪物不继承() {
        var req = request(BattleConstants.MATCH_MODE_PVE_SOLO, 1);
        var a = addPlayer(req, PLAYER_A, 0, 1000, 1000, 0, 0, 7, 100);
        var combat = CombatAttributes.newBuilder();
        assertThat(CombatAttributes.getDescriptor().getFields()).hasSize(15);
        for (var field : CombatAttributes.getDescriptor().getFields()) combat.setField(field, (long) field.getNumber() * 3);
        a.setCombat(combat);
        TestBattles.addPet(a, PET_A, 1000, 1000, 0, 90);
        var engine = TestBattles.start(req, standard());
        var state = engine.buildStateSnapshot();
        assertThat(stateActor(state, PLAYER_A).getCombat()).isEqualTo(combat.build());
        assertThat(stateActor(state, PLAYER_A).getAttributes().getCritchance()).isEqualTo(7);
        assertThat(state.getActorsList()).filteredOn(u -> u.getActorType() != eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER)
                .noneMatch(BattleActorState::hasCombat);
        a.clearCombat();
        assertThat(stateActor(TestBattles.start(req, standard()).buildStateSnapshot(), PLAYER_A).hasCombat()).isFalse();
    }

    @Test
    void 物理与法术必杀分流_满率仍掷一次_未知类型不吃装备() {
        for (int type : new int[] {0, 1, 99}) {
            for (boolean physical : List.of(false, true)) {
                var f = fixture();
                f.a.getCombatBuilder().setPhysicalCritRate(physical ? 100 : 0).setMagicCritRate(physical ? 0 : 100);
                f.data.addSkill(SKILL_DAMAGE).setDamageType(type).setAttackMultiplier(1);
                f.data.setSkillDamage(SKILL_DAMAGE, 10);
                var r = round(f, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_DAMAGE));
                boolean critical = type == (physical ? 1 : 0);
                assertThat(damage(r.result)).singleElement().satisfies(e -> {
                    assertThat(e.getIsCritical()).isEqualTo(critical);
                    assertThat(e.getValue()).isEqualTo(type == 99 ? 3 : critical ? 60 : 30);
                });
                assertThat(r.engine.rngDrawsForTest()).isEqualTo(critical ? 1 : 0);
            }
        }
        var basic = fixture();
        basic.a.getCombatBuilder().setPhysicalCritRate(80);
        basic.a.getBaseAttributesBuilder().setCritchance(20);
        assertThat(damage(round(basic).result)).singleElement().satisfies(e -> assertThat(e.getValue()).isEqualTo(60));
    }

    @Test
    void 抗性按伤害类型分流_基础抗性叠加_防御减伤封顶保持60() {
        for (int type : new int[] {0, 1, 99}) {
            var f = fixture();
            f.b.getBaseAttributesBuilder().setResistance(10);
            f.b.getCombatBuilder().setPhysicalResist(40).setMagicResist(20);
            f.data.addSkill(SKILL_DAMAGE).setDamageType(type).setAttackMultiplier(1);
            f.data.setSkillDamage(SKILL_DAMAGE, 10);
            assertThat(damage(round(f, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_DAMAGE)).result))
                    .singleElement().satisfies(e -> assertThat(e.getValue()).isEqualTo(type == 1 ? 15 : type == 0 ? 21 : 3));
        }
        var f = fixture();
        f.b.setDefense(-1L).getCombatBuilder().setPhysicalResist(-1L);
        assertThat(damage(round(f).result)).singleElement().satisfies(e -> assertThat(e.getValue()).isEqualTo(12));
    }

    @Test
    void 连击只追加一段且同组递增段号_追加独立必杀_减半后向上取整() {
        var f = fixture();
        f.a.getCombatBuilder().setComboRate(100).setPhysicalCritRate(100);
        var r = round(f);
        assertThat(damage(r.result)).extracting(BattleEventItem::getValue).containsExactly(60L, 30L);
        assertThat(damage(r.result)).extracting(BattleEventItem::getHitKind).containsExactly(BATTLE_HIT_NORMAL, BATTLE_HIT_COMBO);
        assertThat(damage(r.result)).extracting(BattleEventItem::getGroupId).containsExactly(1, 1);
        assertThat(damage(r.result)).extracting(BattleEventItem::getHitIndex).containsExactly(0, 1);
        assertThat(r.engine.rngDrawsForTest()).isEqualTo(2);
        var odd = fixture();
        odd.a.setPhysicalAttack(91).getCombatBuilder().setComboRate(100);
        assertThat(damage(round(odd).result)).extracting(BattleEventItem::getValue).containsExactly(31L, 16L);
        var tiny = fixture();
        tiny.a.setPhysicalAttack(0).getCombatBuilder().setComboRate(100);
        tiny.b.setDefense(-1L);
        assertThat(damage(round(tiny).result)).extracting(BattleEventItem::getValue).containsExactly(2L, 1L);
    }

    @Test
    void 首段致死不再判连击反震反击_不白耗概率随机数() {
        var f = fixture();
        f.b.getBaseAttributesBuilder().setHealth(1);
        f.a.getCombatBuilder().setComboRate(50);
        f.b.getCombatBuilder().setReflectRate(50).setCounterRate(50);
        var r = round(f);
        assertThat(damage(r.result)).hasSize(1);
        assertThat(r.engine.rngDrawsForTest()).isZero();
        assertThat(r.result.getState().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
    }

    @Test
    void 已提交技能失效降级普攻仍连击_有效技能不触发三种普攻效果() {
        var f = fixture();
        f.a.getCombatBuilder().setComboRate(100);
        f.b.getCombatBuilder().setCounterRate(100).setReflectRate(100);
        var engine = TestBattles.start(f.request, f.data);
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_DAMAGE))).isEqualTo(SUCCESS);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_DAMAGE));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        engine.addBuffForTest(PLAYER_A, BUFF_SILENCE, PLAYER_A);
        assertThat(damage(engine.resolveCurrentRound())).extracting(BattleEventItem::getHitKind)
                .containsExactly(BATTLE_HIT_NORMAL, BATTLE_HIT_REFLECT, BATTLE_HIT_COMBO, BATTLE_HIT_REFLECT, BATTLE_HIT_COUNTER);
        var skill = round(f, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_DAMAGE));
        assertThat(damage(skill.result)).singleElement().satisfies(e -> assertThat(e.getHitKind()).isEqualTo(BATTLE_HIT_NORMAL));
    }

    @Test
    void 反震按每段实扣的一半向上取整_同组同段_不再过攻击者的减伤() {
        var f = fixture();
        f.a.setPhysicalAttack(91).setDefense(-1L).getCombatBuilder().setComboRate(100).setPhysicalResist(100);
        f.b.getCombatBuilder().setReflectRate(100);
        var r = round(f);
        assertThat(damage(r.result)).extracting(BattleEventItem::getValue).containsExactly(31L, 16L, 16L, 8L);
        assertThat(damage(r.result)).extracting(BattleEventItem::getHitIndex).containsExactly(0, 0, 1, 1);
        assertThat(damage(r.result)).extracting(BattleEventItem::getGroupId).containsExactly(1, 1, 1, 1);
        assertThat(damage(r.result).get(1).getSourceId()).isEqualTo(PLAYER_B);
        assertThat(damage(r.result).get(1).getTargetId()).isEqualTo(PLAYER_A);
        assertThat(damage(r.result).get(1).getIsCritical()).isFalse();
    }

    @Test
    void 反震击杀出手者终止连击反击_进入死亡和胜负() {
        var f = fixture();
        f.a.getBaseAttributesBuilder().setHealth(5);
        f.a.getCombatBuilder().setComboRate(50);
        f.b.getCombatBuilder().setReflectRate(100).setCounterRate(50);
        var r = round(f);
        assertThat(damage(r.result)).extracting(BattleEventItem::getValue).containsExactly(30L, 5L);
        assertThat(stateActor(r.result.getState(), PLAYER_A).getIsDead()).isTrue();
        assertThat(r.result.getState().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN);
        assertThat(r.engine.rngDrawsForTest()).isZero();
    }

    @Test
    void 防御先降低实扣_反震按实扣而非原伤害计算() {
        var f = fixture();
        f.a.getCombatBuilder().setReflectRate(100);
        f.b.setDefense(-1L).getCombatBuilder().setPhysicalResist(100);
        var engine = TestBattles.start(f.request, f.data);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A));
        assertThat(damage(engine.resolveCurrentRound())).extracting(BattleEventItem::getValue).containsExactly(15L, 8L);
    }

    @Test
    void 反震击杀怪物归属受击玩家_计击杀和结算() {
        var data = withDrops();
        data.addMonster(MONSTER_WITH_DROP).setHealth(5).setStrength(50).setSpeed(1000);
        var req = request(BattleConstants.MATCH_MODE_PVE_SOLO, 42).setBattleConfigId(DUNGEON_WITH_DROP);
        addPlayer(req, PLAYER_A, 0, 1000, 1000, 0, 0, 0, 100).getCombatBuilder().setReflectRate(100);
        var engine = TestBattles.start(req, data);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        var result = engine.resolveCurrentRound();
        assertThat(result.getState().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(engine.buildSettlement(PLAYER_A).getDefeatedMonstersList()).singleElement().satisfies(defeat -> {
            assertThat(defeat.getMonsterConfigId()).isEqualTo(MONSTER_WITH_DROP);
            assertThat(defeat.getCount()).isEqualTo(1);
        });
    }

    @Test
    void 反击另开组且不连锁连击反击反震_可独立必杀() {
        var f = fixture();
        f.a.getCombatBuilder().setCounterRate(100).setReflectRate(100);
        f.b.getCombatBuilder().setCounterRate(100).setComboRate(100).setPhysicalCritRate(100);
        var r = round(f);
        assertThat(damage(r.result)).extracting(BattleEventItem::getValue).containsExactly(30L, 60L);
        assertThat(damage(r.result)).extracting(BattleEventItem::getHitKind).containsExactly(BATTLE_HIT_NORMAL, BATTLE_HIT_COUNTER);
        assertThat(damage(r.result)).extracting(BattleEventItem::getGroupId).containsExactly(1, 2);
        assertThat(damage(r.result)).extracting(BattleEventItem::getHitIndex).containsExactly(0, 0);
        assertThat(r.result.getEventsList()).filteredOn(e -> e.getEventType() == BATTLE_EVENT_ATTACK)
                .extracting(BattleEventItem::getHitKind).containsExactly(BATTLE_HIT_NORMAL, BATTLE_HIT_COUNTER);
        assertThat(r.engine.rngDrawsForTest()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {30, 52})
    void 眩晕或冰冻不能反击且不耗反击骰(int type) {
        var f = fixture();
        f.data.addBuff(800).setBuffType(type).setInfiniteDuration(1);
        f.b.getCombatBuilder().setCounterRate(50);
        var engine = TestBattles.start(f.request, f.data);
        engine.addBuffForTest(PLAYER_B, 800, PLAYER_B);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        var result = engine.resolveCurrentRound();
        assertThat(damage(result)).hasSize(1);
        assertThat(engine.rngDrawsForTest()).isZero();
    }

    @Test
    void 反击致死进入死亡与胜负() {
        var f = fixture();
        f.a.getBaseAttributesBuilder().setHealth(5);
        f.b.getCombatBuilder().setCounterRate(100);
        var r = round(f);
        assertThat(damage(r.result)).extracting(BattleEventItem::getValue).containsExactly(30L, 5L);
        assertThat(stateActor(r.result.getState(), PLAYER_A).getIsDead()).isTrue();
        assertThat(r.result.getState().getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN);
    }

    @Test
    void 技能等级加成只改表达式参数_多目标只求值一次_原等级不变() {
        var f = fixture();
        f.a.getCombatBuilder().setSkillLevelBonus(7);
        f.data.addSkill(SKILL_DAMAGE).clearTargetingMode().addTargetingMode(2);
        var levels = new ArrayList<Double>();
        f.data.setSkillDamageFunction(SKILL_DAMAGE, level -> { levels.add(level); return level; });
        addPlayer(f.request, PLAYER_C, 1, 1000, 1000, 0, 0, 0, 1);
        var r = round(f, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_DAMAGE));
        assertThat(levels).containsExactly(17.0);
        assertThat(stateActor(r.result.getState(), PLAYER_A).getLevel()).isEqualTo(10);
        assertThat(damage(r.result).stream().filter(e -> e.getSourceId() == PLAYER_A).toList())
                .extracting(BattleEventItem::getValue).containsExactly(33L, 33L);
    }

    @Test
    void 新增掷骰严格按必杀反震连击续段反击顺序消费() {
        for (long seed = 1; seed <= 32; seed++) {
            var f = fixture(); f.request.setSeed(seed);
            f.a.getCombatBuilder().setPhysicalCritRate(37).setComboRate(53);
            f.b.getCombatBuilder().setPhysicalCritRate(29).setReflectRate(41).setCounterRate(61);
            var oracle = new BattleRandom(seed);
            var expected = new ArrayList<String>();
            boolean critical = oracle.rand01() < .37;
            long dealt = critical ? 60 : 30;
            expected.add("1:0:NORMAL:" + dealt + ":" + critical);
            if (oracle.rand01() * 100 < 41) expected.add("1:0:REFLECT:" + (dealt / 2) + ":false");
            if (oracle.rand01() * 100 < 53) {
                critical = oracle.rand01() < .37;
                dealt = critical ? 30 : 15;
                expected.add("1:1:COMBO:" + dealt + ":" + critical);
                if (oracle.rand01() * 100 < 41) expected.add("1:1:REFLECT:" + ((dealt + 1) / 2) + ":false");
            }
            if (oracle.rand01() * 100 < 61) {
                critical = oracle.rand01() < .29;
                expected.add("2:0:COUNTER:" + (critical ? 60 : 30) + ":" + critical);
            }
            var r = round(f);
            assertThat(damage(r.result)).extracting(e -> e.getGroupId() + ":" + e.getHitIndex() + ":"
                    + e.getHitKind().name().replace("BATTLE_HIT_", "") + ":" + e.getValue() + ":" + e.getIsCritical())
                    .as("seed=%s", seed).containsExactlyElementsOf(expected);
            assertThat(r.engine.rngDrawsForTest()).isEqualTo(oracle.draws());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 7, 42})
    void 全零combat有无presence不改旧事件字节与RNG序列(long seed) {
        var f = fixture(); f.request.setSeed(seed);
        f.a.getBaseAttributesBuilder().setCritchance(50);
        var original = TestBattles.start(f.request, f.data);
        f.a.setCombat(CombatAttributes.getDefaultInstance()); f.b.setCombat(CombatAttributes.getDefaultInstance());
        var zero = TestBattles.start(f.request, f.data);
        for (int round = 0; round < 5; round++) {
            for (var engine : List.of(original, zero)) {
                engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
                engine.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A));
            }
            var before = original.resolveCurrentRound();
            var after = zero.resolveCurrentRound();
            assertThat(after.getEventsList()).extracting(e -> EngineTestSupport.hex(e.toByteArray()))
                    .containsExactlyElementsOf(before.getEventsList().stream().map(e -> EngineTestSupport.hex(e.toByteArray())).toList());
            assertThat(after.getEventsList()).allMatch(e -> e.getHitKind() == BATTLE_HIT_NORMAL && e.getEventType() != BATTLE_EVENT_RESIST);
            var stripped = after.getState().toBuilder();
            TurnBattleEngine.stripEngineOnlyState(stripped);
            assertThat(stripped.build()).isEqualTo(before.getState());
            assertThat(zero.rngDrawsForTest()).isEqualTo(original.rngDrawsForTest());
        }
    }
}
