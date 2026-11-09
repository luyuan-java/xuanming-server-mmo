package com.game.battle.engine;

import com.game.common.combat.CombatDamageRules;
import com.game.common.math.Unsigned;
import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.CombatAttributes;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleEventType;
import com.game.table.SkillTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ClassSkillCombatTest {
    @Test
    void 无职业幅度时所有有效属性保留uint64原位和快照() {
        var ctx = new BattleContext(new MemoryBattleData(), 4, 17);
        for (long base : new long[]{0, 1, (1L << 53) + 1, Long.MAX_VALUE, Long.MIN_VALUE, -1L}) {
            var actor = unit(1, 0, base, base, base);
            var before = actor.snapshot();
            assertThat(ctx.effectivePhysicalAttack(actor)).isEqualTo(base);
            assertThat(ctx.effectiveDefense(actor)).isEqualTo(base);
            assertThat(ctx.effectiveSpeed(actor)).isEqualTo(base);
            assertThat(actor.snapshot()).isEqualTo(before);
        }
        assertThat(ctx.rng().draws()).isZero();
    }

    @Test
    void 各属性同向只取最强_正负共同生效_层数不放大_下限一成() {
        var data = new MemoryBattleData();
        data.addBuff(10).setBuffType(10);
        data.addBuff(11).setBuffType(11);
        data.addBuff(13).setBuffType(13);
        data.addBuff(99).setBuffType(12);
        var actor = unit(1, 0, 1000, 800, 100);
        add(actor, 10, .4); add(actor, 10, .2); add(actor, 10, -.25); add(actor, 10, -.1);
        add(actor, 11, -.95); add(actor, 13, .5);
        add(actor, 99, 100); add(actor, 404, 100);
        actor.state().getBuffsBuilder(0).setLayer(100);
        var combat = new ClassSkillCombat(data);
        assertThat(combat.effectivePhysicalAttack(actor)).isEqualTo(1150);
        assertThat(combat.effectiveDefense(actor)).isEqualTo(80);
        assertThat(combat.effectiveSpeed(actor)).isEqualTo(150);
        assertThat(combat.statMultiplier(actor, ClassSkillCombat.Stat.PHYSICAL_ATTACK, .6)).isCloseTo(1.35, within(1e-15));
        assertThat(actor.physicalAttack()).isEqualTo(1000);
        assertThat(actor.defense()).isEqualTo(800);
        assertThat(actor.speed()).isEqualTo(100);
    }

    @Test
    void 零幅度与NaN不污染其他同向项_合成非数按固定源取下限() {
        var data = new MemoryBattleData();
        data.addBuff(10).setBuffType(10);
        var actor = unit(1, 0, 101, 0, 0);
        var combat = new ClassSkillCombat(data);
        add(actor, 10, 0); add(actor, 10, Double.NaN);
        assertThat(combat.effectivePhysicalAttack(actor)).isEqualTo(101);
        add(actor, 10, .25);
        assertThat(combat.effectivePhysicalAttack(actor)).isEqualTo(126);
        add(actor, 10, Double.POSITIVE_INFINITY); add(actor, 10, Double.NEGATIVE_INFINITY);
        assertThat(combat.effectivePhysicalAttack(actor)).isEqualTo(10);
    }

    @Test
    void 敌方最高速度按有效值与无符号比较_忽略友方死亡逃跑() {
        var data = new MemoryBattleData(); data.addBuff(13).setBuffType(13);
        var ctx = new BattleContext(data, 4, 1);
        var actor = unit(1, 0, 0, 0, 1);
        var fast = unit(2, 1, 0, 0, 100); add(fast, 13, -.5);
        var slower = unit(3, 1, 0, 0, 60);
        var friendly = unit(4, 0, 0, 0, -1L);
        var dead = unit(5, 1, 0, 0, -1L); dead.state().setIsDead(true);
        var fled = unit(6, 1, 0, 0, -1L); fled.state().setFled(true);
        ctx.units().addAll(java.util.List.of(actor, fast, slower, friendly, dead, fled));
        assertThat(ctx.maxAliveEnemySpeed(actor)).isEqualTo(60);
        slower.state().getAttributesBuilder().setSpeed(Long.MIN_VALUE);
        assertThat(ctx.maxAliveEnemySpeed(actor)).isEqualTo(Long.MIN_VALUE);
        assertThat(ctx.rng().draws()).isZero();
    }

    @Test
    void 治疗被动按无符号技能号稳定求和_忽略缺行与其他被动() {
        var data = new MemoryBattleData();
        data.addSkill(1).setPassiveStat("outgoingHeal").setPassivePercent(1e16);
        data.addSkill(2).setPassiveStat("outgoingHeal").setPassivePercent(-1e16);
        data.addSkill(-1).setPassiveStat("outgoingHeal").setPassivePercent(1);
        data.addSkill(3).setPassiveStat("magicAttack").setPassivePercent(100);
        var actor = unit(1, 0, 0, 0, 0);
        actor.state().putSkillRanks(-1, 1).putSkillRanks(2, 1).putSkillRanks(1, 0)
                .putSkillRanks(3, 10).putSkillRanks(404, 10);
        var before = actor.snapshot();
        assertThat(new ClassSkillCombat(data).outgoingHealBonus(actor)).isEqualTo(1);
        assertThat(actor.snapshot()).isEqualTo(before);
    }

    @Test
    void 治疗按法攻等级重数与被动相乘_残血加成严格小于阈值() {
        var data = new MemoryBattleData();
        data.addSkill(7).setPassiveStat("outgoingHeal").setPassivePercent(.1);
        var ctx = new BattleContext(data, 4, 9);
        var caster = unit(1, 0, 0, 0, 0);
        caster.state().setMagicAttack(100).setLevel(10).putSkillRanks(7, 3);
        var target = unit(2, 0, 0, 0, 0); target.setHealth(299);
        var skill = SkillTable.newBuilder().setHealMultiplier(2).setHealPerLevel(3)
                .setExecuteHealThreshold(.3).setExecuteHealBonus(.5).build();
        assertThat(ctx.outgoingHealBonus(caster)).isCloseTo(.11, within(1e-15));
        assertThat(ctx.classSkillHealAmount(caster, skill, 2, target)).isCloseTo(390.609, within(1e-10));
        target.setHealth(300);
        assertThat(ctx.classSkillHealAmount(caster, skill, 2, target)).isCloseTo(260.406, within(1e-10));
        target.state().setMaxHealth(0);
        assertThat(ClassSkillCombat.healthRatio(target)).isEqualTo(1);
        assertThat(ctx.classSkillHealAmount(caster, skill, 2, target)).isCloseTo(260.406, within(1e-10));
        target.state().setMaxHealth(Long.MIN_VALUE); target.setHealth(1L << 62);
        assertThat(ClassSkillCombat.healthRatio(target)).isEqualTo(.5);
        assertThat(ctx.rng().draws()).isZero();
    }

    @Test
    void 有效防御进入所有直接伤害公式_仍共享六成减伤封顶和PVP系数() {
        var data = new MemoryBattleData(); data.addBuff(11).setBuffType(11);
        var caster = unit(1, 0, 0, 0, 0);
        var target = unit(2, 1, 0, 480, 0); target.state().setLevel(1);
        var ctx = new BattleContext(data, 4, 1);
        assertThat(ctx.damageBeforeCriticalRoll(caster, target, 100, 0, 1, 1)).isEqualTo(50);
        add(target, 11, 1);
        for (int type : new int[]{0, 1, 99}) {
            assertThat(ctx.damageBeforeCriticalRoll(caster, target, 100, 0, 1, type)).isEqualTo(40);
            assertThat(new BattleContext(data, 3, 1).damageBeforeCriticalRoll(caster, target, 100, 0, 1, type)).isEqualTo(12);
        }
        assertThat(target.defense()).isEqualTo(480);
        assertThat(target.health()).isEqualTo(1000);
        assertThat(ctx.rng().draws()).isZero();
    }

    @Test
    void 暴击概率按伤害类型分流_期望伤害不掷骰不落血() {
        var ctx = new BattleContext(new MemoryBattleData(), 4, 1);
        var caster = unit(1, 0, 0, 0, 0);
        caster.state().getAttributesBuilder().setCritchance(20);
        caster.state().setCombat(CombatAttributes.newBuilder().setPhysicalCritRate(30).setMagicCritRate(80));
        var target = unit(2, 1, 0, 0, 0);
        assertThat(ctx.criticalChance(caster, 1)).isEqualTo(.5);
        assertThat(ctx.criticalChance(caster, 0)).isEqualTo(1);
        assertThat(ctx.criticalChance(caster, 99)).isEqualTo(.2);
        assertThat(ctx.expectedDamage(caster, target, 100, 0, 1, 1)).isEqualTo(150);
        assertThat(ctx.expectedDamage(caster, target, 100, 0, 1, 0)).isEqualTo(200);
        assertThat(ctx.rng().draws()).isZero();
        assertThat(target.health()).isEqualTo(1000);
    }

    @Test
    void 无职业buff时拆分前后伤害逐位相同且后续随机序列不移动() {
        for (int mode : new int[]{3, 4}) for (int type : new int[]{0, 1, 99})
            for (long crit : new long[]{0, 25, 100, -1L}) for (long seed : new long[]{0, 123}) {
                var ctx = new BattleContext(new MemoryBattleData(), mode, seed);
                var oracle = new BattleRandom(seed);
                var caster = unit(1, 0, 123, 0, 0);
                caster.state().getAttributesBuilder().setStrength(7).setCritchance(crit);
                var target = unit(2, 1, 0, 37, 0);
                target.state().setLevel(13).setCombat(CombatAttributes.newBuilder().setPhysicalResist(3).setMagicResist(7));
                target.state().getAttributesBuilder().setArmor(19).setResistance(5);
                // 冻结拆分前公式及掷骰条件，覆盖0/中间/100%与uint64高半区概率。
                double chance = Math.min(1.0, Unsigned.toDouble(crit) / 100.0);
                double expected = CombatDamageRules.damageBeforeCritical(17.25, 7, 123, 1.2, 19, 37,
                        EquipmentCombatRules.resistance(5, target.combat(), type), 13);
                if (mode != 4) expected *= .3;
                boolean critical = chance > 0 && oracle.rand01() < chance;
                if (critical) expected *= 2;
                var actual = ctx.calculateFinalDamage(caster, target, 17.25, 123, 1.2, type);
                assertThat(Double.doubleToRawLongBits(actual.value())).isEqualTo(Double.doubleToRawLongBits(expected));
                assertThat(actual.critical()).isEqualTo(critical);
                assertThat(ctx.rng().draws()).isEqualTo(crit == 0 ? 0 : 1);
                assertThat(ctx.rng().rand01()).isEqualTo(oracle.rand01());
            }
    }

    @Test
    void 职业幅度跳过施加回合的持续递减_普通零幅度仍按旧时序到期() {
        var data = new MemoryBattleData();
        data.addBuff(10).setBuffType(10).setDuration(12).setMaxLayer(1);
        var ctx = new BattleContext(data, 4, 1);
        var actor = unit(1, 0, 100, 0, 0); ctx.units().add(actor);
        var buffs = new BuffEngine(ctx);
        var events = TurnResultS2C.newBuilder(); ctx.events().startRound(events);
        buffs.addBuffToActor(actor, 10, 1, 0, .2);
        buffs.addBuffToActor(actor, 10, 99, 0);
        assertThat(actor.state().getBuffs(0).getAppliedRound()).isEqualTo(1);
        assertThat(actor.state().getBuffs(1).getAppliedRound()).isZero();
        buffs.tickAtRoundEnd();
        assertThat(actor.state().getBuffsList()).extracting(BattleBuffEntry::getRemainRounds).containsExactly(2, 1);
        ctx.advanceRound(); buffs.tickAtRoundEnd();
        assertThat(actor.state().getBuffsCount()).isEqualTo(1);
        assertThat(actor.state().getBuffs(0).getRemainRounds()).isEqualTo(1);
        ctx.advanceRound(); buffs.tickAtRoundEnd();
        assertThat(actor.state().getBuffsList()).isEmpty();
        assertThat(events.getEventsList()).extracting(event -> event.getEventType()).containsExactly(
                eBattleEventType.BATTLE_EVENT_BUFF_ADD, eBattleEventType.BATTLE_EVENT_BUFF_ADD,
                eBattleEventType.BATTLE_EVENT_BUFF_REMOVE, eBattleEventType.BATTLE_EVENT_BUFF_REMOVE);
        assertThat(events.getEvents(2).getSourceId()).isEqualTo(99);
        assertThat(events.getEvents(3).getSourceId()).isEqualTo(1);
        assertThat(ctx.rng().draws()).isZero();
    }

    @Test
    void 重挂保留绝对幅度更强者_弱值也刷新持续_施法者与实例号不变() {
        var data = new MemoryBattleData();
        data.addBuff(10).setBuffType(10).setDuration(12).setNoCaster(1).setMaxLayer(2);
        var ctx = new BattleContext(data, 4, 1);
        var actor = unit(1, 0, 100, 0, 0); ctx.units().add(actor);
        var buffs = new BuffEngine(ctx);
        buffs.addBuffToActor(actor, 10, 9, 0, .4);
        long instance = actor.state().getBuffs(0).getBuffId();
        ctx.advanceRound(); buffs.tickAtRoundEnd();
        assertThat(actor.state().getBuffs(0).getRemainRounds()).isEqualTo(1);
        buffs.addBuffToActor(actor, 10, 8, 0, .2);
        assertThat(actor.state().getBuffs(0).getStatPercent()).isEqualTo(.4);
        assertThat(actor.state().getBuffs(0).getAppliedRound()).isEqualTo(2);
        buffs.tickAtRoundEnd();
        assertThat(actor.state().getBuffs(0).getRemainRounds()).isEqualTo(2);
        buffs.addBuffToActor(actor, 10, 7, 0, -.4);
        assertThat(actor.state().getBuffs(0).getStatPercent()).isEqualTo(.4);
        buffs.addBuffToActor(actor, 10, 6, 0, -.6);
        assertThat(actor.state().getBuffsCount()).isEqualTo(1);
        assertThat(actor.state().getBuffs(0).getStatPercent()).isEqualTo(-.6);
        assertThat(actor.state().getBuffs(0).getLayer()).isEqualTo(2);
        assertThat(actor.state().getBuffs(0).getCasterId()).isEqualTo(9);
        assertThat(actor.state().getBuffs(0).getBuffId()).isEqualTo(instance);
        // 普通入口不擦除职业幅度，也不把施加回合改成当前回合。
        ctx.advanceRound(); buffs.addBuffToActor(actor, 10, 5, 0);
        assertThat(actor.state().getBuffs(0).getStatPercent()).isEqualTo(-.6);
        assertThat(actor.state().getBuffs(0).getAppliedRound()).isEqualTo(2);
        buffs.tickAtRoundEnd();
        assertThat(actor.state().getBuffs(0).getRemainRounds()).isEqualTo(1);
        assertThat(ctx.rng().draws()).isZero();
    }

    @Test
    void 职业幅度不沿子buff传播_瞬时父条目仍当场移除() {
        var data = new MemoryBattleData();
        data.addBuff(10).setBuffType(10).addSubBuff(11).addTargetSubBuff(13);
        data.addBuff(11).setBuffType(11).setDuration(12);
        data.addBuff(13).setBuffType(13).setDuration(12);
        var ctx = new BattleContext(data, 4, 1);
        var target = unit(1, 0, 100, 100, 100);
        var caster = unit(2, 0, 100, 100, 100); ctx.units().addAll(java.util.List.of(target, caster));
        var events = TurnResultS2C.newBuilder(); ctx.events().startRound(events);
        new BuffEngine(ctx).addBuffToActor(target, 10, 2, 0, .5);
        assertThat(target.state().getBuffsList()).extracting(BattleBuffEntry::getBuffTableId).containsExactly(11);
        assertThat(caster.state().getBuffsList()).extracting(BattleBuffEntry::getBuffTableId).containsExactly(13);
        assertThat(target.state().getBuffs(0).getStatPercent()).isZero();
        assertThat(target.state().getBuffs(0).getAppliedRound()).isZero();
        assertThat(caster.state().getBuffs(0).getStatPercent()).isZero();
        assertThat(caster.state().getBuffs(0).getAppliedRound()).isZero();
        assertThat(ctx.effectiveDefense(target)).isEqualTo(100);
        assertThat(ctx.effectiveSpeed(caster)).isEqualTo(100);
        assertThat(events.getEventsList()).extracting(event -> event.getBuffTableId()).containsExactly(10, 11, 13, 10);
        assertThat(events.getEvents(3).getEventType()).isEqualTo(eBattleEventType.BATTLE_EVENT_BUFF_REMOVE);
    }

    @Test
    void 职业施加回合仍结算周期效果_毒与回血不吃属性和治疗被动() {
        var data = new MemoryBattleData();
        data.addBuff(50).setBuffType(50).setDuration(12).setInterval(6).addIntervalEffect(10);
        data.addBuff(40).setBuffType(40).setDuration(12).setInterval(6);
        data.setBuffRegen(40, 7);
        data.addSkill(7).setPassiveStat("outgoingHeal").setPassivePercent(100);
        var ctx = new BattleContext(data, 3, 1);
        var actor = unit(1, 0, 100_000, 100_000, 0);
        actor.state().getAttributesBuilder().setCritchance(100).setResistance(100);
        actor.state().putSkillRanks(7, 10);
        actor.setHealth(500); ctx.units().add(actor);
        var buffs = new BuffEngine(ctx);
        var events = TurnResultS2C.newBuilder(); ctx.events().startRound(events);
        buffs.addBuffToActor(actor, 50, 1, 0, -.5);
        buffs.addBuffToActor(actor, 40, 1, 0, .5);
        buffs.tickAtRoundEnd();
        assertThat(actor.health()).isEqualTo(497);
        assertThat(actor.state().getBuffsList()).extracting(BattleBuffEntry::getRemainRounds).containsExactly(2, 2);
        assertThat(events.getEventsList().stream().filter(event -> event.getEventType() == eBattleEventType.BATTLE_EVENT_BUFF_TICK)
                .map(event -> event.getValue()).toList()).containsExactly(10L, 7L);
        assertThat(ctx.rng().draws()).isZero();
    }

    @Test
    void 净化按挂载顺序匹配负幅度标签与异常类型_数量按uint32() {
        var data = new MemoryBattleData();
        data.addBuff(10).setBuffType(10);
        data.addBuff(20).setBuffType(10).putTag("Debuff", false);
        data.addBuff(21).setBuffType(10).putTag("debuff", true);
        for (int type : new int[]{50, 51, 30, 31, 52}) data.addBuff(type).setBuffType(type);
        var ctx = new BattleContext(data, 4, 1);
        var actor = unit(1, 0, 0, 0, 0);
        add(actor, 10, .5); add(actor, 404, -.1); add(actor, 20, 0); add(actor, 21, 0);
        for (int type : new int[]{50, 51, 30, 31, 52}) add(actor, type, 0);
        add(actor, 405, 0);
        var before = actor.snapshot();
        var events = TurnResultS2C.newBuilder(); ctx.events().startRound(events);
        var buffs = new BuffEngine(ctx);
        assertThat(buffs.dispelDebuffs(actor, 0)).isZero();
        assertThat(actor.snapshot()).isEqualTo(before);
        assertThat(buffs.dispelDebuffs(actor, 2)).isEqualTo(2);
        assertThat(events.getEventsList()).extracting(event -> event.getBuffTableId()).containsExactly(404, 20);
        assertThat(buffs.dispelDebuffs(actor, -1)).isEqualTo(5);
        assertThat(events.getEventsList()).extracting(event -> event.getBuffTableId()).containsExactly(404, 20, 50, 51, 30, 31, 52);
        assertThat(events.getEventsList()).allMatch(event -> event.getEventType() == eBattleEventType.BATTLE_EVENT_BUFF_REMOVE);
        assertThat(actor.state().getBuffsList()).extracting(BattleBuffEntry::getBuffTableId).containsExactly(10, 21, 405);
        assertThat(ctx.rng().draws()).isZero();
    }

    @Test
    void 外部快照清除局内幅度与施加回合_保留原有清洗和到期规则() {
        var data = new MemoryBattleData();
        data.addBuff(10).setBuffType(10).setDuration(12);
        data.addBuff(11).setBuffType(11).setInfiniteDuration(1);
        data.addBuff(30).setBuffType(30).setDuration(12);
        data.addBuff(12).setBuffType(12);
        var ctx = new BattleContext(data, 4, 1);
        var actor = unit(1, 0, 100, 100, 0); ctx.units().add(actor);
        for (int id : new int[]{10, 11, 30, 12, 404}) {
            actor.state().addBuffs(BattleBuffEntry.newBuilder().setBuffId(id).setBuffTableId(id)
                    .setLayer(9).setRemainRounds(99).setCasterId(id == 11 ? 1 : 99)
                    .setStatPercent(.5).setAppliedRound(1));
        }
        var buffs = new BuffEngine(ctx); buffs.sanitizeSnapshotBuffs(actor);
        assertThat(actor.state().getBuffsList()).extracting(BattleBuffEntry::getBuffTableId).containsExactly(10, 11);
        assertThat(actor.state().getBuffsList()).allMatch(entry -> entry.getStatPercent() == 0 && entry.getAppliedRound() == 0);
        assertThat(actor.state().getBuffsList()).extracting(BattleBuffEntry::getLayer).containsExactly(9, 9);
        assertThat(actor.state().getBuffsList()).extracting(BattleBuffEntry::getCasterId).containsExactly(0L, 1L);
        assertThat(actor.state().getBuffsList()).extracting(BattleBuffEntry::getRemainRounds).containsExactly(2, 99);
        assertThat(ctx.effectivePhysicalAttack(actor)).isEqualTo(100);
        assertThat(ctx.effectiveDefense(actor)).isEqualTo(100);
        buffs.tickAtRoundEnd();
        assertThat(actor.state().getBuffsList()).extracting(BattleBuffEntry::getRemainRounds).containsExactly(1, 98);
    }

    static BattleUnit unit(long id, int team, long attack, long defense, long speed) {
        return new BattleUnit(BattleActorState.newBuilder().setActorId(id).setTeamIndex(team).setLevel(10)
                .setPhysicalAttack(attack).setDefense(defense).setMaxHealth(1000)
                .setAttributes(BaseAttributesComp.newBuilder().setHealth(1000).setSpeed(speed)));
    }

    private static void add(BattleUnit actor, int id, double percent) {
        actor.state().addBuffs(BattleBuffEntry.newBuilder().setBuffTableId(id).setStatPercent(percent));
    }
}
