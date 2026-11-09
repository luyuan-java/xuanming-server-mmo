package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.CombatAttributes;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.table.SkillTable;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 通过选择出的行动断言收益规则，不读取候选分值、不执行或推进一局战斗。 */
class AutoClassSkillSelectorTest {
    @Test
    void 单体和群攻按实际存活敌人数选择() {
        var f = new Fixture();
        f.damage(11, 2, 1); f.damage(12, 1.3, 3);
        f.expect(11, 100);
        f.unit(101, 1);
        f.expect(12, 100);
        f.enemy.state().setIsDead(true);
        f.expect(11, 101);
    }

    @Test
    void 通用被动未学错职业坏目标和缺表技能都不入选() {
        var f = new Fixture();
        f.damage(1, 100, 1).setClassId(0);
        f.damage(2, 100, 1).addSkillType(BattleConstants.SKILL_TYPE_BIT_PASSIVE);
        f.damage(3, 100, 1); f.actor.state().removeSkillRanks(3);
        f.damage(4, 100, 1).setClassId(2);
        f.damage(5, 100, 1).setTargetTeam(0);
        f.actor.state().addSkillTableIds(404).putSkillRanks(404, 1);
        assertThat(f.select()).isEmpty();
    }

    @Test
    void 法力冷却沉默眩晕与死亡条件共用正式校验() {
        var f = new Fixture();
        f.damage(10, 2, 1).setManaPercent(.1);
        f.actor.setMana(9); assertThat(f.select()).isEmpty();
        f.actor.setMana(100); f.actor.state().putSkillCooldownRounds(10, 1);
        assertThat(f.select()).isEmpty();
        f.actor.state().clearSkillCooldownRounds();
        f.data.addBuff(31).setBuffType(BattleConstants.BUFF_TYPE_SILENCE);
        f.data.addSkillPermission(BattleConstants.COMBAT_STATE_SILENCE).addSkillType(1000).addSkillType(7005);
        buff(f.actor, 31, 0); assertThat(f.select()).isEmpty();
        f.actor.state().clearBuffs();
        f.data.addBuff(30).setBuffType(BattleConstants.BUFF_TYPE_STUN);
        buff(f.actor, 30, 0); assertThat(f.select()).isEmpty();
        f.actor.state().clearBuffs(); f.actor.state().setIsDead(true);
        assertThat(f.select()).isEmpty();
        f.actor.state().setIsDead(false); f.expect(10, 100);
    }

    @Test
    void 技能伤害必须严格强于普攻() {
        var f = new Fixture();
        f.damage(10, 1, 1);
        assertThat(f.select()).isEmpty();
        f.data.addSkill(10).setAttackMultiplier(1.01);
        f.expect(10, 100);
    }

    @Test
    void 危急治疗优先驱散_三成边界转为驱散优先常规治疗() {
        var f = new Fixture();
        var ally = f.unit(2, 0); ally.setHealth(299);
        f.skill(10, 2).setHealMultiplier(2);
        f.skill(20, 2).setDispelCount(1);
        f.damage(30, 10, 1);
        f.data.addBuff(50).setBuffType(BattleConstants.BUFF_TYPE_POISON); buff(ally, 50, 0);
        f.expect(10, 0);
        ally.setHealth(300); f.expect(20, 2);
        ally.state().clearBuffs(); ally.setHealth(700); f.expect(10, 0);
        ally.setHealth(701); f.expect(30, 100);
    }

    @Test
    void 群疗比较真实缺口而非理论治疗量() {
        var f = new Fixture();
        f.actor.setHealth(600);
        f.unit(2, 0).setHealth(600); f.unit(3, 0).setHealth(600);
        f.skill(10, 2).setHealMultiplier(3.4);
        f.skill(20, 2).setHealMultiplier(2).setMaxTargets(3);
        f.expect(20, 0);
        f.actor.setHealth(990); f.ctx.findActor(3).setHealth(990);
        f.expect(10, 0); // 群疗仅 200+10+10，单疗实际 340。
    }

    @Test
    void 满血和超过气血上限都不会虚构治疗收益() {
        var f = new Fixture();
        f.actor.setHealth(1200);
        f.skill(10, 2).setHealMultiplier(100).setMaxTargets(3);
        f.damage(20, 2, 1);
        f.expect(20, 100);
    }

    @Test
    void 治疗驱散混合技能显式指向有减益者() {
        var f = new Fixture();
        f.unit(2, 0).setHealth(900);
        var poisoned = f.unit(3, 0);
        f.data.addBuff(50).setBuffType(BattleConstants.BUFF_TYPE_POISON); buff(poisoned, 50, 0);
        f.skill(10, 2).setHealMultiplier(.2).setDispelCount(1).setMaxTargets(2);
        f.expect(10, 3); // 目标 0 会首先落到 90% 的队友，不可用次目标减益给它虚增评分。
    }

    @Test
    void 无可驱散减益不放驱散_数量收益优先() {
        var f = new Fixture();
        f.skill(10, 2).setDispelCount(1); f.skill(20, 2).setDispelCount(2);
        f.data.addBuff(11).setBuffType(11); buff(f.actor, 11, .2);
        assertThat(f.select()).isEmpty();
        f.data.addBuff(50).setBuffType(50); f.data.addBuff(51).setBuffType(51);
        buff(f.actor, 50, 0); buff(f.actor, 51, 0);
        f.expect(20, 1);
    }

    @Test
    void 自身回蓝须低蓝且扣除本次耗蓝后仍有净收益() {
        var f = new Fixture();
        f.skill(10, 3).setManaPercent(.05).setRestoreManaPercent(.2);
        f.actor.setMana(10); f.expect(10, 1);
        f.actor.setMana(30); f.expect(10, 1);
        f.actor.setMana(31); assertThat(f.select()).isEmpty();
        f.actor.setMana(10);
        f.data.addSkill(10).setManaPercent(.10).setRestoreManaPercent(.10);
        assertThat(f.select()).isEmpty();
        f.actor.state().setMaxMana(0); f.actor.setMana(0);
        f.data.addSkill(10).setManaPercent(0);
        assertThat(f.select()).isEmpty();
    }

    @Test
    void 群体回蓝计入施法者先扣费的缺口但不把耗费算收益() {
        var f = new Fixture();
        f.unit(2, 0).setMana(0);
        f.skill(10, 2).setManaPercent(.2).setRestoreManaPercent(.1).setMaxTargets(2);
        assertThat(f.select()).isEmpty(); // 各补 10，合计恰好抵掉花费 20。
        f.data.addSkill(10).setRestoreManaPercent(.2);
        f.expect(10, 1); // 自己先扣 20 再补 20，队友实际净得 20。
    }

    @Test
    void 常规治疗优先回蓝_回蓝优先属性buff_属性buff优先伤害() {
        var f = new Fixture();
        f.actor.setHealth(600); f.actor.setMana(20);
        f.skill(10, 3).setHealMultiplier(1);
        f.skill(20, 3).setRestoreManaPercent(.2);
        f.data.addBuff(10).setBuffType(10);
        f.skill(30, 3).setBuffPercent(.2).addEffect(10);
        f.damage(40, 10, 1);
        f.expect(10, 0);
        f.actor.setHealth(1000); f.expect(20, 1);
        f.actor.setMana(100); f.expect(30, 1);
        buff(f.actor, 10, .2); f.expect(40, 100);
    }

    @Test
    void 防御增益只在六成及以下且不会刷新等强或较弱的另一行() {
        var f = new Fixture(); f.actor.state().setDefense(1000);
        f.data.addBuff(11).setBuffType(11); f.data.addBuff(12).setBuffType(11);
        f.skill(10, 3).setBuffPercent(.3).addEffect(11);
        f.actor.setHealth(601); assertThat(f.select()).isEmpty();
        f.actor.setHealth(600); f.expect(10, 1);
        buff(f.actor, 12, .5); assertThat(f.select()).isEmpty();
        f.actor.state().getBuffsBuilder(0).setStatPercent(.3);
        assertThat(f.select()).isEmpty();
    }

    @Test
    void 增益净倍率比较保留最强正负项_不会用较弱增益抵消减益() {
        var f = new Fixture();
        f.data.addBuff(10).setBuffType(10); f.data.addBuff(12).setBuffType(10);
        f.skill(10, 3).setBuffPercent(.2).addEffect(10);
        buff(f.actor, 12, .3); buff(f.actor, 12, -.2);
        assertThat(f.select()).isEmpty(); // 当前 +30%-20%，新增 +20% 不改变实际属性。
        f.data.addSkill(10).setBuffPercent(.4); f.expect(10, 1);
    }

    @Test
    void 加速只给能超越对手者_目标号参与同速排序() {
        var f = new Fixture();
        f.actor.state().getAttributesBuilder().setSpeed(400);
        f.enemy.state().getAttributesBuilder().setSpeed(220);
        var ally = f.unit(2, 0); ally.state().getAttributesBuilder().setSpeed(200);
        f.data.addBuff(13).setBuffType(13);
        f.skill(10, 2).setBuffPercent(.1).addEffect(13);
        f.expect(10, 2); // 到 220 时 2 < 100，已经改变出手顺序。
        buff(ally, 13, .1); assertThat(f.select()).isEmpty();
        ally.state().clearBuffs(); ally.state().setActorId(200);
        assertThat(f.select()).isEmpty(); // 同速但 200 > 100，未越过。
        f.data.addSkill(10).setBuffPercent(.2); f.expect(10, 200);
    }

    @Test
    void 减速必须改变先后且不会重复已有减速() {
        var f = new Fixture();
        f.actor.state().getAttributesBuilder().setSpeed(200);
        f.enemy.state().getAttributesBuilder().setSpeed(220);
        f.data.addBuff(13).setBuffType(13);
        f.skill(10, 1).setBuffPercent(-.2).addEffect(13);
        f.expect(10, 100);
        buff(f.enemy, 13, -.2); assertThat(f.select()).isEmpty();
        f.enemy.state().clearBuffs(); f.enemy.state().getAttributesBuilder().setSpeed(100);
        assertThat(f.select()).isEmpty();
    }

    @Test
    void 降物攻只选择非零物攻目标_未知属性类型与错误方向没有收益() {
        var f = new Fixture(); f.enemy.state().setPhysicalAttack(0);
        f.data.addBuff(10).setBuffType(10);
        f.skill(10, 1).setBuffPercent(-.15).addEffect(10);
        assertThat(f.select()).isEmpty();
        f.enemy.state().setPhysicalAttack(100); f.expect(10, 100);
        f.data.addSkill(10).setBuffPercent(.15); assertThat(f.select()).isEmpty();
        f.data.addSkill(10).setBuffPercent(-.15); f.data.addBuff(10).setBuffType(999);
        assertThat(f.select()).isEmpty();
    }

    @Test
    void buff免疫按键匹配不因false值虚构收益() {
        var f = new Fixture();
        f.data.addBuff(10).setBuffType(10).putTag("ClassAttack", false);
        f.data.addBuff(99).setBuffType(99).putImmuneTag("ClassAttack", false);
        f.skill(10, 1).setBuffPercent(-.2).addEffect(10);
        buff(f.enemy, 99, 0); assertThat(f.select()).isEmpty();
        f.enemy.state().clearBuffs(); f.expect(10, 100);
    }

    @Test
    void 同收益按耗蓝冷却无符号技能号及目标号稳定决胜() {
        var f = new Fixture();
        f.enemy.state().setActorId(-1L); f.unit(100, 1);
        f.damage(1, 2, 1).setManaPercent(.10);
        f.damage(2, 2, 1).setManaPercent(.05).setCooldownRounds(-1);
        f.damage(-1, 2, 1).setManaPercent(.05).setCooldownRounds(1);
        f.damage(4, 2, 1).setManaPercent(.05).setCooldownRounds(1);
        f.expect(4, 100);
        f.actor.state().clearSkillTableIds().addAllSkillTableIds(List.of(4, -1, 2, 1));
        f.expect(4, 100);
    }

    @Test
    void 气血封顶和防御状态改变实际伤害收益及目标选择() {
        var f = new Fixture();
        f.damage(10, 2, 1); f.damage(20, 1.3, 2);
        f.enemy.setHealth(10); f.unit(101, 1);
        f.expect(10, 101); // 群攻只得 10+140，小于另一人上的单体 210。
        f.ctx.findActor(101).setDefending(true);
        f.expect(10, 101); // 预期值必须与落血一样先减半。
        f.ctx.findActor(101).setHealth(10);
        f.expect(20, 100); // 两个残血合计 20，比单体和普攻都高。
    }

    @Test
    void 防御减半会避开编号较小但已防御的敌人() {
        var f = new Fixture(); f.damage(10, 2, 1); f.unit(101, 1);
        f.expect(10, 100);
        f.enemy.setDefending(true); f.expect(10, 101);
    }

    @Test
    void 治疗收益和法力费用均按uint64比较() {
        var f = new Fixture();
        f.actor.state().setMaxHealth(-1L).setMaxMana(-1L); f.actor.setHealth(1); f.actor.setMana(-1L);
        f.skill(10, 3).setHealMultiplier(1e16);
        f.skill(20, 3).setHealMultiplier(1e17).setManaPercent(.9);
        f.expect(20, 0); // 1e19 的收益不能被视为负数。
        f.skill(30, 3).setHealMultiplier(1e17).setManaPercent(.1);
        f.expect(30, 0); // 同收益时 10% 费用低于高位为 1 的 90% 费用。
    }

    @Test
    void 物法抗性和暴击按各自伤害类型计入期望() {
        var f = new Fixture();
        f.damage(10, 2, 1);
        f.damage(20, 1.3, 1).setDamageType(0);
        f.expect(10, 100);
        f.enemy.state().setCombat(CombatAttributes.newBuilder().setPhysicalResist(50));
        f.expect(20, 100);
        f.enemy.state().clearCombat();
        f.actor.state().setCombat(CombatAttributes.newBuilder().setMagicCritRate(100));
        f.expect(20, 100);
    }

    @Test
    void 连击期望会提高普攻基线() {
        var f = new Fixture(); f.damage(10, 1.4, 1); f.expect(10, 100);
        f.actor.state().setCombat(CombatAttributes.newBuilder().setComboRate(100));
        assertThat(f.select()).isEmpty();
    }

    @Test
    void 属性和重数及装备技能等级影响评分() {
        var f = new Fixture();
        f.damage(10, 1.2, 1); f.damage(20, 1.3, 1); f.expect(20, 100);
        f.actor.state().putSkillRanks(10, 10); f.expect(10, 100);
        f.actor.state().putSkillRanks(10, 1);
        f.data.setSkillDamageFunction(10, level -> level / 2); f.expect(20, 100);
        f.actor.state().setCombat(CombatAttributes.newBuilder().setSkillLevelBonus(40)); f.expect(10, 100);
        f.data.addBuff(10).setBuffType(10); buff(f.actor, 10, -.9);
        f.data.addSkill(20).setDamageType(0).setAttackMultiplier(3); f.expect(20, 100);
    }

    @Test
    void 死亡逃离目标均不参与评分_无敌人时仍可自疗() {
        var f = new Fixture(); f.damage(10, 10, 1);
        f.enemy.state().setFled(true); assertThat(f.select()).isEmpty();
        f.skill(20, 3).setHealMultiplier(1); f.actor.setHealth(500); f.expect(20, 0);
        f.actor.setHealth(1000);
        var dead = f.unit(2, 0); dead.setHealth(1); dead.state().setIsDead(true);
        f.data.addSkill(20).setTargetTeam(2); assertThat(f.select()).isEmpty();
    }

    @Test
    void 重复选择不改变状态或消费战斗随机数() {
        var f = new Fixture(); f.damage(10, 2, 1);
        f.actor.state().getAttributesBuilder().setCritchance(50);
        f.actor.state().setCombat(CombatAttributes.newBuilder().setComboRate(25));
        var before = f.ctx.units().stream().map(BattleUnit::snapshot).toList();
        for (int repeat = 0; repeat < 10; repeat++) f.expect(10, 100);
        assertThat(f.ctx.rng().draws()).isZero();
        assertThat(f.ctx.units().stream().map(BattleUnit::snapshot).toList()).isEqualTo(before);
    }

    private static void buff(BattleUnit unit, int id, double percent) {
        unit.state().addBuffs(BattleBuffEntry.newBuilder().setBuffId(unit.state().getBuffsCount() + 1)
                .setBuffTableId(id).setLayer(1).setRemainRounds(3).setStatPercent(percent));
    }

    private static final class Fixture {
        final MemoryBattleData data = new MemoryBattleData();
        final BattleContext ctx = new BattleContext(data, 4, 123);
        final ActionChecks checks = new ActionChecks(ctx, new ItemLedger(ctx, CreateBattleRequest.getDefaultInstance()));
        final BuffEngine buffs = new BuffEngine(ctx);
        final ClassSkillExecution execution = new ClassSkillExecution(ctx, buffs, checks);
        final AutoClassSkillSelector selector = new AutoClassSkillSelector(ctx, checks, execution, buffs);
        final BattleUnit actor = unit(1, 0);
        final BattleUnit enemy = unit(100, 1);

        BattleUnit unit(long id, int team) {
            var result = new BattleUnit(BattleActorState.newBuilder().setActorId(id).setTeamIndex(team)
                    .setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER).setClassId(1).setLevel(20)
                    .setMaxHealth(1000).setMaxMana(100).setPhysicalAttack(100).setMagicAttack(100)
                    .setFormationSlot(ctx.units().size()).setIsAuto(true)
                    .setAttributes(BaseAttributesComp.newBuilder().setHealth(1000).setMana(100).setSpeed(100)));
            ctx.units().add(result);
            return result;
        }

        SkillTable.Builder skill(int id, int team) {
            actor.state().addSkillTableIds(id).putSkillRanks(id, 1);
            return data.addSkill(id).setClassId(1).setUnlockLevel(1).setMaxRank(10)
                    .setTargetTeam(team).setMaxTargets(1).addSkillType(1);
        }

        SkillTable.Builder damage(int id, double multiplier, int targets) {
            data.setSkillDamage(id, 10);
            return skill(id, 1).setDamageType(1).setAttackMultiplier(multiplier).setMaxTargets(targets);
        }

        java.util.Optional<BattleAction> select() { return selector.select(actor); }

        void expect(int id, long target) {
            assertThat(select()).contains(BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                    .setSkillTableId(id).setTargetId(target).build());
        }
    }
}
