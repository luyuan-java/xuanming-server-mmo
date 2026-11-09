package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static com.game.proto.eBattleEventType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActorType;
import com.game.table.SkillTable;
import com.game.table.Skillcost_resource;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 职业执行的行为回归：校验与选择无随机副作用，费用、事件、目标和回合编排必须一起核对。 */
class TurnBattleClassSkillTest {
    private static final int CLASS = 7;
    private static final int SKILL = 700;

    @Test
    void 开局按职业等级与表上限夹有效重数_不从技能列表发放重数() {
        var data = new MemoryBattleData();
        skill(data, SKILL, 1).setUnlockLevel(10).setMaxRank(9);
        skill(data, 701, 1).setClassId(8);
        skill(data, 702, 1);
        skill(data, 703, 1).setUnlockLevel(13);
        skill(data, 704, 1).setPassiveStat("outgoingHeal");
        data.addSkill(705);
        var request = TestBattles.request(0, 1);
        var player = player(request, 1, 0, 100);
        player.setLevel(12).addAllSkillTableIds(List.of(701, 702, 703, 704, 705, 999))
                .putSkillRanks(SKILL, -1).putSkillRanks(701, 1).putSkillRanks(703, 1)
                .putSkillRanks(704, 1).putSkillRanks(705, 1).putSkillRanks(999, 1);
        player(request, 2, 1, 100);
        var engine = TestBattles.start(request, data);
        var state = stateActor(engine.buildStateSnapshot(), 1);
        assertThat(state.getSkillRanksMap()).containsOnlyKeys(SKILL, 704);
        assertThat(state.getSkillRanksOrThrow(SKILL)).isEqualTo(2);
        assertThat(state.getSkillTableIdsList()).containsExactly(SKILL, 705);
        assertThat(player.getSkillRanksOrThrow(SKILL)).isEqualTo(-1);
        assertThat(engine.rngDrawsForTest()).isZero();
    }

    @Test
    void 职业目标校验区分敌我自己_不存在与错阵营分码() {
        var f = fixture(1);
        var ally = unit(3, 0, 20, 100, 1);
        f.ctx.units().add(ally);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 0, SKILL))).isEqualTo(ActionChecks.SUCCESS);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 3, SKILL))).isEqualTo(ActionChecks.SKILL_INVALID_TARGET);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 999, SKILL))).isEqualTo(ActionChecks.SKILL_INVALID_TARGET_ID);
        f.row.setTargetTeam(2);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 3, SKILL))).isEqualTo(ActionChecks.SUCCESS);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 2, SKILL))).isEqualTo(ActionChecks.SKILL_INVALID_TARGET);
        f.row.setTargetTeam(3);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 1, SKILL))).isEqualTo(ActionChecks.SUCCESS);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 3, SKILL))).isEqualTo(ActionChecks.SKILL_INVALID_TARGET);
        f.row.setTargetTeam(9);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 0, SKILL))).isEqualTo(ActionChecks.INVALID_TABLE_DATA);
        assertThat(f.ctx.rng().draws()).isZero();
    }

    @Test
    void 指定阵亡或逃离主目标校验允许_出手补位仍不接受错阵营() {
        var f = fixture(1);
        f.enemy.state().setIsDead(true);
        var replacement = unit(3, 1, 80, 100, 1);
        f.ctx.units().add(replacement);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 2, SKILL))).isEqualTo(ActionChecks.SUCCESS);
        assertThat(f.execution.selectTargets(f.caster, f.row.build(), 2)).containsExactly(replacement);
        f.enemy.state().setIsDead(false).setFled(true);
        assertThat(f.execution.selectTargets(f.caster, f.row.build(), 2)).containsExactly(replacement);
        f.enemy.state().setTeamIndex(0);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 2, SKILL))).isEqualTo(ActionChecks.SKILL_INVALID_TARGET);
    }

    @Test
    void 治疗目标主目标优先_再按血比阵位和无符号编号_包含宝宝() {
        var f = fixture(2);
        f.row.setHealPerLevel(1).setMaxTargets(4);
        var low = unit(5, 0, 10, 100, 9);
        var early = unit(6, 0, 50, 100, 0);
        var highId = unit(-1, 0, 50, 100, 0);
        highId.state().setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PET);
        f.ctx.units().addAll(List.of(highId, low, early));
        assertThat(f.execution.selectTargets(f.caster, f.row.build(), 1)).containsExactly(f.caster, low, early, highId);
        assertThat(f.execution.selectTargets(f.caster, f.row.build(), 0)).containsExactly(low, early, highId, f.caster);
        assertThat(f.ctx.rng().draws()).isZero();
    }

    @Test
    void 无治疗时不按血量排序_零目标上限至少一个_负位模式按uint32() {
        var f = fixture(1);
        var second = unit(3, 1, 1, 100, 1);
        f.ctx.units().add(second);
        f.row.setMaxTargets(0);
        assertThat(f.execution.selectTargets(f.caster, f.row.build(), 0)).containsExactly(f.enemy);
        f.row.setMaxTargets(-1);
        assertThat(f.execution.selectTargets(f.caster, f.row.build(), 0)).containsExactly(f.enemy, second);
        f.row.setTargetTeam(3);
        assertThat(f.execution.selectTargets(f.caster, f.row.build(), 2)).containsExactly(f.caster);
    }

    @Test
    void 职业技能必须有匹配职业的非零重数() {
        var f = fixture(1);
        f.caster.state().clearSkillRanks();
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 2, SKILL))).isEqualTo(ActionChecks.INVALID_PARAMETER);
        f.caster.state().putSkillRanks(SKILL, 1).setClassId(8);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 2, SKILL))).isEqualTo(ActionChecks.INVALID_PARAMETER);
    }

    @Test
    void 职业耗蓝取最大法力比例向上取整_忽略通用资源费用() {
        var f = fixture(2);
        f.row.setManaPercent(.101).addCostResource(Skillcost_resource.newBuilder().setCostResourceId(1).setCostResourceCost(999));
        f.caster.state().setMaxMana(101);
        f.caster.setMana(10);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 1, SKILL))).isEqualTo(ActionChecks.SKILL_CANNOT_CAST_IN_STATE);
        f.caster.setMana(11);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 1, SKILL))).isEqualTo(ActionChecks.SUCCESS);
        assertThat(f.ctx.rng().draws()).isZero();
    }

    @Test
    void 职业技能自身冷却与通用共享组互不污染() {
        var f = fixture(1);
        f.row.setCooldownId(9);
        skill(f.data, 701, 1).setCooldownId(9);
        f.data.addSkill(702).setCooldownId(9);
        f.data.addSkill(703).setCooldownId(9);
        f.caster.state().addSkillTableIds(702).putSkillCooldownRounds(701, 4);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 2, SKILL))).isEqualTo(ActionChecks.SUCCESS);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 2, 702))).isEqualTo(ActionChecks.SUCCESS);
        f.caster.state().putSkillCooldownRounds(703, 4);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 2, 702))).isEqualTo(ActionChecks.SKILL_COOLDOWN_NOT_READY);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 2, SKILL))).isEqualTo(ActionChecks.SUCCESS);
        f.caster.state().putSkillCooldownRounds(SKILL, 1);
        assertThat(f.checks.checkActionPrerequisites(f.caster, action(BATTLE_ACTION_SKILL, 2, SKILL))).isEqualTo(ActionChecks.SKILL_COOLDOWN_NOT_READY);
    }

    @Test
    void 多目标一次扣蓝_技能费用治疗回蓝事件有序且带命中序号() {
        var f = fixture(2);
        f.row.setMaxTargets(2).setManaPercent(.1).setHealPerLevel(1).setRestoreManaPercent(.1).setCooldownRounds(3);
        f.caster.setHealth(50);
        var ally = unit(3, 0, 20, 100, 1);
        ally.setMana(95);
        f.ctx.units().add(ally);
        assertThat(f.execute(3)).isEqualTo(ClassSkillExecution.Outcome.EXECUTED);
        assertThat(f.events.getEventsList()).extracting(e -> e.getEventType())
                .containsExactly(BATTLE_EVENT_SKILL, BATTLE_EVENT_MANA, BATTLE_EVENT_HEAL, BATTLE_EVENT_MANA, BATTLE_EVENT_HEAL, BATTLE_EVENT_MANA);
        assertThat(f.events.getEventsList()).extracting(e -> e.getTargetId()).containsExactly(3L, 1L, 3L, 3L, 1L, 1L);
        assertThat(f.events.getEventsList()).extracting(e -> e.getValue()).containsExactly(0L, 10L, 10L, 5L, 10L, 10L);
        assertThat(f.events.getEventsList()).extracting(e -> e.getHitIndex()).containsExactly(0, 0, 0, 0, 1, 1);
        assertThat(f.events.getEvents(1).getManaDelta()).isEqualTo(-10);
        assertThat(f.events.getEvents(3).getManaDelta()).isEqualTo(5);
        assertThat(f.events.getEvents(5).getTargetManaAfter()).isEqualTo(100);
        assertThat(f.caster.state().getSkillCooldownRoundsOrThrow(SKILL)).isEqualTo(4);
        assertThat(f.ctx.rng().draws()).isZero();
        assertThat(f.ctx.events().append(BATTLE_EVENT_DEFEND, 1, 1).getHitIndex()).isZero();
    }

    @Test
    void 满血满蓝仍发零治疗零回蓝_没有费用不伪造扣蓝事件() {
        var f = fixture(3);
        f.row.setHealPerLevel(1).setRestoreManaPercent(.5);
        f.execute(0);
        assertThat(f.events.getEventsList()).extracting(e -> e.getEventType()).containsExactly(BATTLE_EVENT_SKILL, BATTLE_EVENT_HEAL, BATTLE_EVENT_MANA);
        assertThat(f.events.getEvents(1).getValue()).isZero();
        assertThat(f.events.getEvents(2).getValue()).isZero();
        assertThat(f.events.getEvents(2).hasManaDelta()).isTrue();
        assertThat(f.events.getEvents(2).getManaDelta()).isZero();
    }

    @Test
    void 出手复核失败友方作废_敌方请求普攻降级_两者不提前扣款或抽数() {
        var f = fixture(2);
        f.row.setManaPercent(.5).setCooldownRounds(2);
        f.caster.setMana(1);
        assertThat(f.execute(0)).isEqualTo(ClassSkillExecution.Outcome.SKIPPED);
        f.row.setTargetTeam(1);
        assertThat(f.execute(0)).isEqualTo(ClassSkillExecution.Outcome.FALLBACK_ATTACK);
        assertThat(f.events.getEventsCount()).isZero();
        assertThat(f.caster.mana()).isEqualTo(1);
        assertThat(f.caster.state().getSkillCooldownRoundsMap()).isEmpty();
        assertThat(f.ctx.rng().draws()).isZero();
    }

    @Test
    void 无存活目标不扣蓝不冷却不发事件() {
        var f = fixture(1);
        f.row.setManaPercent(.5).setCooldownRounds(2);
        f.enemy.state().setIsDead(true);
        assertThat(f.execute(0)).isEqualTo(ClassSkillExecution.Outcome.SKIPPED);
        assertThat(f.caster.mana()).isEqualTo(100);
        assertThat(f.events.getEventsCount()).isZero();
        assertThat(f.caster.state().getSkillCooldownRoundsMap()).isEmpty();
        assertThat(f.ctx.rng().draws()).isZero();
    }

    @Test
    void 重数倍率用于最终伤害_只在真实暴击判定时抽随机数() {
        var f = fixture(1);
        f.data.setSkillDamage(SKILL, 100);
        f.caster.state().putSkillRanks(SKILL, 6);
        f.enemy.state().setMaxHealth(1000);
        f.enemy.setHealth(1000);
        f.execute(2);
        assertThat(f.events.getEvents(1).getEventType()).isEqualTo(BATTLE_EVENT_DAMAGE);
        // 100 × 1.1 的浮点尾数按现有落伤害 ceil 口径得到 111。
        assertThat(f.events.getEvents(1).getValue()).isEqualTo(111);
        assertThat(f.ctx.rng().draws()).isZero();
        f.caster.state().getAttributesBuilder().setCritchance(100);
        f.execute(2);
        assertThat(f.ctx.rng().draws()).isEqualTo(1);
        assertThat(f.events.getEvents(f.events.getEventsCount() - 1).getIsCritical()).isTrue();
    }

    @Test
    void 致死后不附带治疗回蓝或buff() {
        var f = fixture(1);
        f.data.setSkillDamage(SKILL, 1000);
        f.row.setHealPerLevel(10).setRestoreManaPercent(1).addEffect(10);
        f.data.addBuff(10).setBuffType(10).setDuration(60);
        f.execute(2);
        assertThat(f.events.getEventsList()).extracting(e -> e.getEventType()).containsExactly(BATTLE_EVENT_SKILL, BATTLE_EVENT_DAMAGE, BATTLE_EVENT_DEATH);
        assertThat(f.enemy.isDead()).isTrue();
        assertThat(f.enemy.state().getBuffsCount()).isZero();
    }

    @Test
    void 自身回蓝按扣费后的缺口_支持重数_不超过上限() {
        var f = fixture(3);
        f.row.setManaPercent(.15).setRestoreManaPercent(.1);
        f.caster.state().putSkillRanks(SKILL, 11);
        f.execute(0);
        assertThat(f.events.getEvents(1).getValue()).isEqualTo(15);
        assertThat(f.events.getEvents(2).getValue()).isEqualTo(12);
        assertThat(f.caster.mana()).isEqualTo(97);
    }

    @Test
    void 挂buff保存辅助重数后的幅度与本回合标记() {
        var f = fixture(3);
        f.row.setBuffPercent(.2).addEffect(10);
        f.data.addBuff(10).setBuffType(10).setDuration(60);
        f.caster.state().putSkillRanks(SKILL, 6);
        f.execute(0);
        assertThat(f.caster.state().getBuffs(0).getStatPercent()).isCloseTo(.22, within(1e-15));
        assertThat(f.caster.state().getBuffs(0).getAppliedRound()).isEqualTo(1);
    }

    @Test
    void 职业冷却回合数在整回合后准确衰减() {
        var data = new MemoryBattleData();
        skill(data, SKILL, 3).setCooldownRounds(2);
        var request = TestBattles.request(0, 1);
        player(request, 1, 0, 100);
        player(request, 2, 1, 10);
        var engine = TestBattles.start(request, data);
        assertAcceptedAwaiting(engine, 1, action(BATTLE_ACTION_SKILL, 1, SKILL), 2);
        assertThat(engine.submitAction(2, action(BATTLE_ACTION_DEFEND))).isTrue();
        engine.resolveCurrentRound();
        for (int remaining = 2; remaining > 0; remaining--) {
            assertThat(stateActor(engine.buildStateSnapshot(), 1).getSkillCooldownRoundsOrThrow(SKILL)).isEqualTo(remaining);
            assertThat(engine.validateAction(1, action(BATTLE_ACTION_SKILL, 1, SKILL))).isEqualTo(ActionChecks.SKILL_COOLDOWN_NOT_READY);
            assertAcceptedAwaiting(engine, 1, action(BATTLE_ACTION_DEFEND), 2);
            assertThat(engine.submitAction(2, action(BATTLE_ACTION_DEFEND))).isTrue();
            engine.resolveCurrentRound();
        }
        assertThat(engine.validateAction(1, action(BATTLE_ACTION_SKILL, 1, SKILL))).isEqualTo(ActionChecks.SUCCESS);
    }

    @Test
    void 合法职业先手覆盖速度_通用技能不读取initiative() {
        var data = new MemoryBattleData();
        skill(data, SKILL, 3).setInitiative(1);
        var request = TestBattles.request(0, 1);
        player(request, 1, 0, 1);
        player(request, 2, 1, 100);
        var engine = TestBattles.start(request, data);
        assertAcceptedAwaiting(engine, 1, action(BATTLE_ACTION_SKILL, 1, SKILL), 2);
        assertThat(engine.submitAction(2, action(BATTLE_ACTION_DEFEND))).isTrue();
        engine.resolveCurrentRound();
        assertThat(engine.lastActionOrder()).containsExactly(1L, 2L);
        data.addSkill(SKILL).setClassId(0);
        assertAcceptedAwaiting(engine, 1, action(BATTLE_ACTION_SKILL, 1, SKILL), 2);
        assertThat(engine.submitAction(2, action(BATTLE_ACTION_DEFEND))).isTrue();
        engine.resolveCurrentRound();
        assertThat(engine.lastActionOrder()).containsExactly(2L, 1L);
    }

    @Test
    void 提交后条件变化会失去先手_友方技能失败不改成攻击() {
        var data = new MemoryBattleData();
        var row = skill(data, SKILL, 3).setInitiative(5);
        var request = TestBattles.request(0, 1);
        player(request, 1, 0, 1);
        player(request, 2, 1, 100);
        var engine = TestBattles.start(request, data);
        assertAcceptedAwaiting(engine, 1, action(BATTLE_ACTION_SKILL, 1, SKILL), 2);
        assertThat(engine.submitAction(2, action(BATTLE_ACTION_DEFEND))).isTrue();
        row.setManaPercent(2);
        var result = engine.resolveCurrentRound();
        assertThat(engine.lastActionOrder()).containsExactly(2L, 1L);
        assertThat(result.getEventsList()).noneMatch(e -> e.getSourceId() == 1);
        assertThat(engine.rngDrawsForTest()).isZero();
    }

    @Test
    void 无法用signed显示的增减蓝饱和而value保留uint64() {
        assertThat(EventLog.signedManaDelta(Long.MAX_VALUE, true)).isEqualTo(Long.MAX_VALUE);
        assertThat(EventLog.signedManaDelta(Long.MAX_VALUE, false)).isEqualTo(-Long.MAX_VALUE);
        assertThat(EventLog.signedManaDelta(Long.MIN_VALUE, true)).isEqualTo(Long.MAX_VALUE);
        assertThat(EventLog.signedManaDelta(-1, false)).isEqualTo(Long.MIN_VALUE);
        var f = fixture(3);
        f.caster.state().setMaxMana(-1L);
        f.caster.setMana(0);
        f.row.setRestoreManaPercent(1);
        f.execute(0);
        assertThat(f.events.getEvents(1).getValue()).isEqualTo(-1L);
        assertThat(f.events.getEvents(1).getManaDelta()).isEqualTo(Long.MAX_VALUE);
        assertThat(f.caster.mana()).isEqualTo(-1L);
    }

    @Test
    void 职业治疗先截断实际回复量_高位气血不丢低位_超上限不回绕() {
        var f = fixture(3);
        f.row.setHealPerLevel(.199);
        long before = (1L << 53) + 1;
        f.caster.state().setMaxHealth(before + 5);
        f.caster.setHealth(before);
        f.execute(0);
        assertThat(f.events.getEvents(1).getValue()).isEqualTo(1);
        assertThat(f.caster.health()).isEqualTo(before + 1);
        f.caster.setHealth(before + 6);
        f.execute(0);
        assertThat(f.events.getEvents(3).getValue()).isZero();
        assertThat(f.caster.health()).isEqualTo(before + 6);
        for (double raw : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThat(f.caster.landedHeal(raw)).isZero();
        }
        f.caster.state().setMaxHealth(-1L);
        f.caster.setHealth(0);
        assertThat(f.caster.landedHeal(Double.MAX_VALUE)).isEqualTo(-1L);
        f.caster.state().setIsDead(true);
        assertThat(f.caster.landedHeal(10)).isZero();
    }

    @Test
    void 挂机玩家有真实职业重数才选择职业技能_选择不增加随机抽数() {
        var data = new MemoryBattleData();
        skill(data, SKILL, 1);
        data.setSkillDamage(SKILL, 100);
        var request = TestBattles.request(0, 1);
        player(request, 1, 0, 100);
        player(request, 2, 1, 1);
        var engine = TestBattles.start(request, data);
        assertThat(engine.setActorAuto(1, true)).isEqualTo(ActionChecks.SUCCESS);
        assertThat(engine.submitAction(2, action(BATTLE_ACTION_DEFEND))).isTrue();
        var result = engine.resolveCurrentRound();
        assertThat(result.getEventsList()).anyMatch(e -> e.getSourceId() == 1 && e.getEventType() == BATTLE_EVENT_SKILL && e.getSkillTableId() == SKILL);
        assertThat(engine.rngDrawsForTest()).isZero();
    }

    @Test
    void 挂机也保留已经提交的手动动作() {
        var data = new MemoryBattleData();
        skill(data, SKILL, 1);
        data.setSkillDamage(SKILL, 100);
        var request = TestBattles.request(0, 1);
        player(request, 1, 0, 100);
        player(request, 2, 1, 1);
        var engine = TestBattles.start(request, data);
        assertAcceptedAwaiting(engine, 1, action(BATTLE_ACTION_DEFEND), 2);
        assertThat(engine.setActorAuto(1, true)).isEqualTo(ActionChecks.SUCCESS);
        assertThat(engine.submitAction(2, action(BATTLE_ACTION_DEFEND))).isTrue();
        var result = engine.resolveCurrentRound();
        assertThat(result.getEventsList()).extracting(e -> e.getEventType()).containsExactly(BATTLE_EVENT_DEFEND, BATTLE_EVENT_DEFEND);
        assertThat(engine.rngDrawsForTest()).isZero();
    }

    @Test
    void 非挂机玩家超时仍普攻_即使有可用职业技能() {
        var data = new MemoryBattleData();
        skill(data, SKILL, 1);
        data.setSkillDamage(SKILL, 100);
        var request = TestBattles.request(0, 1);
        player(request, 1, 0, 100);
        player(request, 2, 1, 1);
        var engine = TestBattles.start(request, data);
        assertAcceptedAwaiting(engine, 2, action(BATTLE_ACTION_DEFEND), 1);
        var result = engine.resolveCurrentRound();
        assertThat(result.getEventsList()).noneMatch(e -> e.getEventType() == BATTLE_EVENT_SKILL);
        assertThat(result.getEventsList()).anyMatch(e -> e.getSourceId() == 1 && e.getEventType() == BATTLE_EVENT_ATTACK);
        assertThat(engine.rngDrawsForTest()).isEqualTo(1);
    }

    @Test
    void 宝宝继续按旧技能列表施放_怪物继续默认普攻() {
        var data = new MemoryBattleData();
        data.addSkill(701);
        data.setSkillDamage(701, 10);
        skill(data, SKILL, 1);
        data.setSkillDamage(SKILL, 1000);
        var request = TestBattles.request(BattleConstants.MATCH_MODE_PVE_SOLO, 1);
        var owner = player(request, 1, 0, 100);
        TestBattles.addPet(owner, 3, 1000, 1000, 0, 90).addSkillTableIds(701);
        var engine = TestBattles.start(request, data);
        assertThat(engine.submitAction(1, action(BATTLE_ACTION_DEFEND))).isTrue();
        var result = engine.resolveCurrentRound();
        assertThat(result.getEventsList()).filteredOn(e -> e.getEventType() == BATTLE_EVENT_SKILL)
                .extracting(e -> e.getSkillTableId()).containsExactly(701);
        assertThat(result.getEventsList()).anyMatch(e -> e.getSourceId() == TestTables.MONSTER_ID && e.getEventType() == BATTLE_EVENT_ATTACK);
    }

    /** submitAction 返回全员就绪，不是本次接受；快照只剩对手待提交证明动作已保存。 */
    private static void assertAcceptedAwaiting(TurnBattleEngine engine, long actorId,
                                                BattleAction action, long waitingActorId) {
        assertThat(engine.validateAction(actorId, action)).isEqualTo(ActionChecks.SUCCESS);
        assertThat(engine.submitAction(actorId, action)).isFalse();
        assertThat(engine.buildStateSnapshot().getPendingActorIdsList()).containsExactly(waitingActorId);
    }

    private static SkillTable.Builder skill(MemoryBattleData data, int id, int targetTeam) {
        return data.addSkill(id).setClassId(CLASS).setMaxRank(100).setUnlockLevel(1).setTargetTeam(targetTeam)
                .setMaxTargets(1).setDamageType(1);
    }

    private static BattlePlayerSnapshot.Builder player(CreateBattleRequest.Builder request, long id, int team, long speed) {
        var player = TestBattles.addPlayer(request, id, team, 1000, 1000, 0, 0, 0, speed);
        player.clearSkillTableIds().addSkillTableIds(SKILL).setClassId(CLASS).putSkillRanks(SKILL, 1).setMaxMana(100);
        player.getBaseAttributesBuilder().setMana(100);
        return player;
    }

    private static BattleUnit unit(long id, int team, long health, long maxHealth, int slot) {
        return new BattleUnit(BattleActorState.newBuilder().setActorId(id).setActorType(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER)
                .setTeamIndex(team).setClassId(CLASS).setLevel(10).setMaxHealth(maxHealth).setMaxMana(100).setFormationSlot(slot)
                .setAttributes(BaseAttributesComp.newBuilder().setHealth(health).setMana(100)));
    }

    private static Fixture fixture(int targetTeam) {
        var data = new MemoryBattleData();
        var row = skill(data, SKILL, targetTeam);
        var ctx = new BattleContext(data, BattleConstants.MATCH_MODE_PVE_SOLO, 17);
        var caster = unit(1, 0, 100, 100, 0);
        caster.state().addSkillTableIds(SKILL).putSkillRanks(SKILL, 1);
        var enemy = unit(2, 1, 100, 100, 0);
        ctx.units().addAll(List.of(caster, enemy));
        var checks = new ActionChecks(ctx, new ItemLedger(ctx, CreateBattleRequest.getDefaultInstance()));
        var execution = new ClassSkillExecution(ctx, new BuffEngine(ctx), checks);
        var events = TurnResultS2C.newBuilder();
        ctx.events().startRound(events);
        ctx.events().beginGroup();
        return new Fixture(data, row, ctx, caster, enemy, checks, execution, events);
    }

    private record Fixture(MemoryBattleData data, SkillTable.Builder row, BattleContext ctx, BattleUnit caster,
                           BattleUnit enemy, ActionChecks checks, ClassSkillExecution execution, TurnResultS2C.Builder events) {
        ClassSkillExecution.Outcome execute(long target) {
            return execution.execute(caster, action(BATTLE_ACTION_SKILL, target, SKILL), row.build());
        }
    }
}
