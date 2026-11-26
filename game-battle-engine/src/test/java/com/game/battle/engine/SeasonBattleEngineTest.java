package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.standard;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_ATTACK;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_ARTIFACT;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.EquipmentEffects;
import com.game.proto.SeasonSealEffect;
import com.game.table.SkillTable;
import org.junit.jupiter.api.Test;

/** 赛季技能归属复核、单槽赛季印触发与状态脱敏引擎单测。 */
class SeasonBattleEngineTest {

    @Test
    void 赛季技能只在activeSeasonId匹配时入战_切季或为0时剥离() {
        var data = standard();
        data.addSkill(15001)
                .setName("破阵裂锋")
                .setBattleModel(1)
                .setCasterMask(1)
                .setAbilityKind(1)
                .setTargetTeam(1)
                .setMaxTargets(3)
                .setUnlockLevel(1)
                .setMaxRank(10)
                .setSeasonId(1);

        // 1. active_season_id = 0（非本季）：剥离赛季技能
        var reqOff = request(3, 1);
        addPlayer(reqOff, PLAYER_A, 0, 1000, 1000, 0, 0, 0, 100)
                .setActiveSeasonId(0)
                .putSkillRanks(15001, 5)
                .addSkillTableIds(15001);
        addPlayer(reqOff, PLAYER_B, 1, 1000, 1000, 0, 0, 0, 10);
        var engOff = TestBattles.start(reqOff, data);
        var actorOff = stateActor(engOff.buildStateSnapshot(), PLAYER_A);
        assertThat(actorOff.getSkillTableIdsList()).doesNotContain(15001);
        assertThat(actorOff.getSkillRanksMap()).doesNotContainKey(15001);

        // 2. active_season_id = 1（当季）：保留赛季技能
        var reqOn = request(3, 1);
        addPlayer(reqOn, PLAYER_A, 0, 1000, 1000, 0, 0, 0, 100)
                .setActiveSeasonId(1)
                .putSkillRanks(15001, 5)
                .addSkillTableIds(15001);
        addPlayer(reqOn, PLAYER_B, 1, 1000, 1000, 0, 0, 0, 10);
        var engOn = TestBattles.start(reqOn, data);
        var actorOn = stateActor(engOn.buildStateSnapshot(), PLAYER_A);
        assertThat(actorOn.getSkillTableIdsList()).contains(15001);
        assertThat(actorOn.getSkillRanksMap()).containsEntry(15001, 5);
    }

    @Test
    void 赛季印聚息援印防御回蓝与固阵守印减伤触发且公开快照脱敏() {
        var req = request(3, 42);
        var a = addPlayer(req, PLAYER_A, 0, 10_000, 10_000, 0, 0, 0, 100)
                .setMaxMana(10_000).setPhysicalAttack(2000).setMagicAttack(2000);
        var b = addPlayer(req, PLAYER_B, 1, 10_000, 5_000, 0, 0, 0, 10)
                .setMaxMana(10_000)
                .setPhysicalAttack(100).setMagicAttack(100);
        // A 佩戴 104 聚息援印（防御回入战最大法力 4%，每回合 1 次，每场 3 次）
        a.setEquipmentEffects(EquipmentEffects.newBuilder()
                .setSeasonMaxHealthBps(300)
                .setSeasonDefenseBps(300)
                .setSeasonSeal(SeasonSealEffect.newBuilder()
                        .setSeasonId(1).setSealId(104).setEffectId(7)
                        .setBonusBps(400).setPerRoundLimit(1).setBattleLimit(3)
                        .setName("聚息援印")));
        a.getBaseAttributesBuilder().setMana(5_000);
        // B 佩戴 103 固阵守印（前 2 回合首次受敌方扣血直伤减少 10%，上限入战最大气血 3%，每场 1 次）
        b.setEquipmentEffects(EquipmentEffects.newBuilder()
                .setSeasonSeal(SeasonSealEffect.newBuilder()
                        .setSeasonId(1).setSealId(103).setEffectId(6)
                        .setBonusBps(1000).setCapBps(300).setActiveRounds(2).setBattleLimit(1)
                        .setName("固阵守印")));

        var engine = TestBattles.start(req, standard());
        // 公开快照必须脱敏 season_seal_state
        var pub = engine.buildStateSnapshot().toBuilder();
        TurnBattleEngine.stripEngineOnlyState(pub);
        assertThat(stateActor(pub.build(), PLAYER_A).hasSeasonSealState()).isFalse();
        assertThat(stateActor(pub.build(), PLAYER_B).hasSeasonSealState()).isFalse();

        // 回合 1：A 防御（触发 104 聚息援印回蓝 400），B 普攻 A（不触发 B 的 103）
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A));
        var r1 = engine.resolveCurrentRound();
        assertThat(r1.getEventsList())
                .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT && e.getSeasonSealId() == 104)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getArtifactItemId()).isZero();
                    assertThat(e.getArtifactEffectId()).isEqualTo(7);
                    assertThat(e.getValue()).isEqualTo(400L);
                });

        // 回合 2：A 普攻 B（触发 B 的 103 固阵守印减伤）
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        var r2 = engine.resolveCurrentRound();
        assertThat(r2.getEventsList())
                .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT && e.getSeasonSealId() == 103)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getArtifactItemId()).isZero();
                    assertThat(e.getArtifactEffectId()).isEqualTo(6);
                    assertThat(e.getValue()).isPositive();
                });
    }

    @Test
    void 赛季印血防轻属性超500万分比硬上限拒绝开局() {
        var req = request(3, 1);
        addPlayer(req, PLAYER_A, 0, 1000, 1000, 0, 0, 0, 100)
                .setEquipmentEffects(EquipmentEffects.newBuilder().setSeasonMaxHealthBps(501));
        addPlayer(req, PLAYER_B, 1, 1000, 1000, 0, 0, 0, 10);
        assertThat(TurnBattleEngine.start(req.build(), standard()))
                .isInstanceOf(BattleStart.Rejected.class);
    }

    @Test
    void 全员对称赛季周环境词缀四项生效且分歧或灵脉存在时退回0() {
        var data = standard();
        data.addSkill(15999)
                .setName("测试护盾")
                .setBattleModel(1)
                .setCasterMask(1)
                .setAbilityKind(1)
                .setTargetTeam(2)
                .setMaxTargets(1)
                .setUnlockLevel(1)
                .setMaxRank(10)
                .addEffect(159990);
        data.addBuff(159990)
                .setBattleOp(BattleEffect.SHIELD)
                .setDurationRounds(2)
                .setMaxLayer(1)
                .setRefreshPolicy(2)
                .setFlatValue(1000.0);

        // 1. 双方词缀不一致 fail-closed 退回 0
        var reqMismatch = request(3, 7);
        addPlayer(reqMismatch, PLAYER_A, 0, 10_000, 5_000, 0, 0, 0, 100)
                .setMaxMana(10_000).setSeasonWeeklyAffixId(SeasonRules.WEEKLY_AFFIX_SPIRIT_RECOVERY);
        addPlayer(reqMismatch, PLAYER_B, 1, 10_000, 5_000, 0, 0, 0, 10)
                .setMaxMana(10_000).setSeasonWeeklyAffixId(SeasonRules.WEEKLY_AFFIX_OPENING_GUARD);
        assertThat(TestBattles.start(reqMismatch, data).buildStateSnapshot().getSeasonWeeklyAffixId()).isZero();

        // 2. 灵息充盈周 (2)：双方防御均额外回复 3% 最大法力（+300）
        var reqMana = request(3, 7);
        var manaA = addPlayer(reqMana, PLAYER_A, 0, 10_000, 10_000, 0, 0, 0, 100)
                .setMaxMana(10_000).setSeasonWeeklyAffixId(SeasonRules.WEEKLY_AFFIX_SPIRIT_RECOVERY);
        manaA.getBaseAttributesBuilder().setMana(5_000);
        var manaB = addPlayer(reqMana, PLAYER_B, 1, 10_000, 10_000, 0, 0, 0, 10)
                .setMaxMana(10_000).setSeasonWeeklyAffixId(SeasonRules.WEEKLY_AFFIX_SPIRIT_RECOVERY);
        manaB.getBaseAttributesBuilder().setMana(5_000);
        var manaEng = TestBattles.start(reqMana, data);
        assertThat(manaEng.buildStateSnapshot().getSeasonWeeklyAffixId())
                .isEqualTo(SeasonRules.WEEKLY_AFFIX_SPIRIT_RECOVERY);
        manaEng.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        manaEng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        manaEng.resolveCurrentRound();
        assertThat(stateActor(manaEng.buildStateSnapshot(), PLAYER_A).getAttributes().getMana()).isEqualTo(5_300L);
        assertThat(stateActor(manaEng.buildStateSnapshot(), PLAYER_B).getAttributes().getMana()).isEqualTo(5_300L);

        // 3. 锦标赛五行灵脉存在时压制周词缀
        reqMana.getTournamentContextBuilder()
                .setMatchId(101L)
                .setLeylineElement(com.game.proto.TournamentLeylineElement.TOURNAMENT_LEYLINE_WATER);
        assertThat(TestBattles.start(reqMana, data).buildStateSnapshot().getSeasonWeeklyAffixId()).isZero();

        // 4. 先机守势周 (3) 首回合减伤 vs 锋芒竞速周 (4) 无盾增伤
        java.util.function.IntToLongFunction round1Damage = affixId -> {
            var r = request(3, 19);
            addPlayer(r, PLAYER_A, 0, 10_000, 10_000, 0, 0, 0, 100)
                    .setPhysicalAttack(2000).setSeasonWeeklyAffixId(affixId);
            addPlayer(r, PLAYER_B, 1, 10_000, 10_000, 0, 0, 0, 10)
                    .setPhysicalAttack(2000).setSeasonWeeklyAffixId(affixId);
            var e = TestBattles.start(r, data);
            e.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
            e.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A));
            e.resolveCurrentRound();
            return 10_000L - stateActor(e.buildStateSnapshot(), PLAYER_B).getAttributes().getHealth();
        };
        long baseDmg = round1Damage.applyAsLong(SeasonRules.WEEKLY_AFFIX_NONE);
        long guardDmg = round1Damage.applyAsLong(SeasonRules.WEEKLY_AFFIX_OPENING_GUARD);
        long sharpDmg = round1Damage.applyAsLong(SeasonRules.WEEKLY_AFFIX_SHARP_TEMPO);
        assertThat(guardDmg).isLessThan(baseDmg);
        assertThat(sharpDmg).isGreaterThan(baseDmg);

        // 5. 护盾过载周 (1)：技能护盾量 +15%
        java.util.function.IntToLongFunction shieldCapacity = affixId -> {
            var r = request(3, 23);
            addPlayer(r, PLAYER_A, 0, 10_000, 10_000, 0, 0, 0, 100)
                    .addSkillTableIds(15999).putSkillRanks(15999, 1)
                    .setSeasonWeeklyAffixId(affixId);
            addPlayer(r, PLAYER_B, 1, 10_000, 10_000, 0, 0, 0, 10)
                    .setSeasonWeeklyAffixId(affixId);
            var e = TestBattles.start(r, data);
            e.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL, PLAYER_A, 15999));
            e.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            e.resolveCurrentRound();
            return stateActor(e.buildStateSnapshot(), PLAYER_A).getBuffsList().stream()
                    .filter(b -> b.getBuffTableId() == 159990)
                    .mapToLong(com.game.proto.BattleBuffEntry::getRemainingCapacity)
                    .sum();
        };
        long baseShield = shieldCapacity.applyAsLong(SeasonRules.WEEKLY_AFFIX_NONE);
        long overloadShield = shieldCapacity.applyAsLong(SeasonRules.WEEKLY_AFFIX_SHIELD_OVERLOAD);
        assertThat(baseShield).isPositive();
        assertThat(overloadShield).isGreaterThan(baseShield);
    }

    @Test
    void 第一赛季四组技印共鸣与战后赛季印功劳摘要双服同口径() {
        var data = standard();
        // 常规可削护盾技能 96099（由 B 先手施放，不依赖外部快照注入）
        data.addBuff(96002)
                .setBattleOp(BattleEffect.SHIELD)
                .setDurationRounds(2).setMaxLayer(1).setRefreshPolicy(2)
                .setEffectPriority(20).setFlatValue(50_000.0);
        data.addSkill(96099)
                .setBattleModel(1).setAbilityKind(1).setCasterMask(1)
                .setTargetTeam(2).setMaxTargets(1).setPetMaxTargets(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL)
                .setUnlockLevel(1).setMaxRank(10)
                .addEffect(96002);

        // 15001 破阵裂锋（物理伤害）
        data.addBuff(96010).setBattleOp(BattleEffect.DAMAGE).setEffectPriority(10);
        data.addSkill(SeasonRules.SKILL_POZHEN_LIEFENG)
                .setBattleModel(1).setAbilityKind(1).setCasterMask(1)
                .setTargetTeam(1).setMaxTargets(1).setPetMaxTargets(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL)
                .setDamageType(com.game.common.combat.CombatDamageRules.PHYSICAL_DAMAGE)
                .setAttackMultiplier(1.0).setManaPercent(0.06)
                .setSeasonId(1).setUnlockLevel(1).setMaxRank(10)
                .addEffect(96010);
        data.setSkillDamage(SeasonRules.SKILL_POZHEN_LIEFENG, 1000.0);

        // 15002 破阵灵雷（法术伤害 + 2 回合减防 Modifier）
        data.addBuff(96020).setBattleOp(BattleEffect.DAMAGE).setEffectPriority(10);
        data.addBuff(96021)
                .setBattleOp(BattleEffect.MODIFIER)
                .setModifierStat(BattleEffect.DEFENSE)
                .setRatio(-0.15)
                .setDurationRounds(2);
        data.addSkill(SeasonRules.SKILL_POZHEN_LINGLEI)
                .setBattleModel(1).setAbilityKind(1).setCasterMask(1)
                .setTargetTeam(1).setMaxTargets(1).setPetMaxTargets(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL)
                .setDamageType(com.game.common.combat.CombatDamageRules.MAGIC_DAMAGE)
                .setAttackMultiplier(1.0).setManaPercent(0.06)
                .setSeasonId(1).setUnlockLevel(1).setMaxRank(10)
                .addEffect(96020).addEffect(96021);
        data.setSkillDamage(SeasonRules.SKILL_POZHEN_LINGLEI, 1000.0);

        // 15003 固阵灵障（固定 1000 护盾）
        data.addBuff(96030)
                .setBattleOp(BattleEffect.SHIELD)
                .setDurationRounds(2).setMaxLayer(1).setRefreshPolicy(2)
                .setEffectPriority(20).setFlatValue(1000.0);
        data.addSkill(SeasonRules.SKILL_GUZHEN_LINGZHANG)
                .setBattleModel(1).setAbilityKind(1).setCasterMask(1)
                .setTargetTeam(2).setMaxTargets(1).setPetMaxTargets(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL)
                .setSeasonId(1).setUnlockLevel(1).setMaxRank(10)
                .addEffect(96030);

        // 15004 破阵蓄势（5% 法力消耗 = 500，半价 = 250）
        data.addBuff(96040)
                .setBattleOp(BattleEffect.MODIFIER)
                .setModifierStat(BattleEffect.PHYSICAL_ATTACK)
                .setRatio(0.15).setDurationRounds(2);
        data.addSkill(SeasonRules.SKILL_POZHEN_XUSHI)
                .setBattleModel(1).setAbilityKind(1).setCasterMask(1)
                .setTargetTeam(2).setMaxTargets(1).setPetMaxTargets(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL)
                .setManaPercent(0.05)
                .setSeasonId(1).setUnlockLevel(1).setMaxRank(10)
                .addEffect(96040);

        // 1. 101 破阵锐印 × 15001 破阵裂锋：厚盾未击碎不返蓝；精确碎盾返还 30% 耗蓝（600 -> +180）
        {
            var reqThick = request(3, 101);
            var a1 = addPlayer(reqThick, PLAYER_A, 0, 20_000, 20_000, 0, 0, 0, 100)
                    .setMaxMana(10_000).setPhysicalAttack(2000).setMagicAttack(2000)
                    .setActiveSeasonId(1)
                    .addSkillTableIds(SeasonRules.SKILL_POZHEN_LIEFENG)
                    .putSkillRanks(SeasonRules.SKILL_POZHEN_LIEFENG, 1)
                    .setEquipmentEffects(EquipmentEffects.newBuilder()
                            .setSeasonSeal(SeasonSealEffect.newBuilder()
                                    .setSeasonId(1).setSealId(SeasonRules.SEAL_POZHEN_RUIYIN)
                                    .setEffectId(1).setBonusBps(1000).setPerRoundLimit(1)
                                    .setResonanceSkillId(SeasonRules.SKILL_POZHEN_LIEFENG)
                                    .setResonanceKind(SeasonRules.RESONANCE_SHARP_MANA_REFUND)
                                    .setResonanceParam(30)));
            a1.getBaseAttributesBuilder().setMana(10_000);
            var b1 = addPlayer(reqThick, PLAYER_B, 1, 20_000, 20_000, 0, 0, 0, 200)
                    .setMaxMana(10_000).setPhysicalAttack(2000).setMagicAttack(2000)
                    .addSkillTableIds(96099)
                    .putSkillRanks(96099, 1);
            b1.getBaseAttributesBuilder().setMana(10_000);

            data.addBuff(96002).setFlatValue(50_000.0);
            var noShatterEng = TestBattles.start(reqThick, data);
            noShatterEng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL,
                    PLAYER_B, SeasonRules.SKILL_POZHEN_LIEFENG));
            assertThat(noShatterEng.submitAction(PLAYER_B, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL,
                    PLAYER_B, 96099))).isTrue();
            var rNoShatter = noShatterEng.resolveCurrentRound();
            long directAbsorbed = rNoShatter.getEventsList().stream()
                    .filter(e -> e.getEventType() == com.game.proto.eBattleEventType.BATTLE_EVENT_DAMAGE
                            && e.getSourceId() == PLAYER_A)
                    .mapToLong(com.game.proto.BattleEventItem::getAbsorbed).findFirst().orElse(0L);
            long sealStripped = rNoShatter.getEventsList().stream()
                    .filter(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getSeasonSealId() == SeasonRules.SEAL_POZHEN_RUIYIN
                            && e.getArtifactEffectId() == 1)
                    .mapToLong(com.game.proto.BattleEventItem::getValue).findFirst().orElse(0L);
            assertThat(directAbsorbed).isPositive();
            assertThat(sealStripped).isPositive();
            assertThat(rNoShatter.getEventsList())
                    .noneMatch(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getArtifactEffectId() == SeasonRules.RESONANCE_EVENT_SHARP_MANA_REFUND);
            assertThat(stateActor(rNoShatter.getState(), PLAYER_A).getAttributes().getMana()).isEqualTo(9_400L);
            var noShatterSettle = noShatterEng.buildSettlement(PLAYER_A);
            assertThat(noShatterSettle.getFinalSeasonSealId()).isEqualTo(SeasonRules.SEAL_POZHEN_RUIYIN);
            assertThat(noShatterSettle.getSeasonSealContributionValue()).isEqualTo(sealStripped);
            assertThat(noShatterSettle.getSeasonSealTriggerCount()).isEqualTo(1);
            assertThat(noShatterSettle.getSeasonSealResonanceCount()).isZero();

            long exactCap = directAbsorbed + Math.min(sealStripped, 10L);
            data.addBuff(96002).setFlatValue((double) exactCap);
            var shatterEng = TestBattles.start(reqThick, data);
            shatterEng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL,
                    PLAYER_B, SeasonRules.SKILL_POZHEN_LIEFENG));
            assertThat(shatterEng.submitAction(PLAYER_B, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL,
                    PLAYER_B, 96099))).isTrue();
            var rShatter = shatterEng.resolveCurrentRound();
            assertThat(rShatter.getEventsList())
                    .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getSeasonSealId() == SeasonRules.SEAL_POZHEN_RUIYIN
                            && e.getArtifactEffectId() == SeasonRules.RESONANCE_EVENT_SHARP_MANA_REFUND)
                    .singleElement()
                    .extracting(com.game.proto.BattleEventItem::getValue)
                    .isEqualTo(180L);
            assertThat(stateActor(rShatter.getState(), PLAYER_A).getAttributes().getMana()).isEqualTo(9_580L);
            var shatterSettle = shatterEng.buildSettlement(PLAYER_A);
            assertThat(shatterSettle.getSeasonSealTriggerCount()).isEqualTo(1);
            assertThat(shatterSettle.getSeasonSealResonanceCount()).isEqualTo(1);
            assertThat(shatterSettle.getSeasonSealContributionValue()).isEqualTo(Math.min(sealStripped, 10L));
        }

        // 2. 102 破阵玄印 × 15002 破阵灵雷：破盾后减防持续回合 2 -> 3 + 共鸣事件 102
        {
            data.addBuff(96002).setFlatValue(50_000.0);
            var reqMystic = request(3, 102);
            var a2 = addPlayer(reqMystic, PLAYER_A, 0, 20_000, 20_000, 0, 0, 0, 100)
                    .setMaxMana(10_000).setPhysicalAttack(2000).setMagicAttack(2000)
                    .setActiveSeasonId(1)
                    .addSkillTableIds(SeasonRules.SKILL_POZHEN_LINGLEI)
                    .putSkillRanks(SeasonRules.SKILL_POZHEN_LINGLEI, 1)
                    .setEquipmentEffects(EquipmentEffects.newBuilder()
                            .setSeasonSeal(SeasonSealEffect.newBuilder()
                                    .setSeasonId(1).setSealId(SeasonRules.SEAL_POZHEN_XUANYIN)
                                    .setEffectId(2).setBonusBps(1000).setPerRoundLimit(1)
                                    .setResonanceSkillId(SeasonRules.SKILL_POZHEN_LINGLEI)
                                    .setResonanceKind(SeasonRules.RESONANCE_BREAK_EXTEND_DEBUFF)
                                    .setResonanceParam(1)));
            a2.getBaseAttributesBuilder().setMana(10_000);
            var b2 = addPlayer(reqMystic, PLAYER_B, 1, 20_000, 20_000, 0, 0, 0, 200)
                    .setMaxMana(10_000)
                    .addSkillTableIds(96099)
                    .putSkillRanks(96099, 1);
            b2.getBaseAttributesBuilder().setMana(10_000);

            var eng = TestBattles.start(reqMystic, data);
            eng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL,
                    PLAYER_B, SeasonRules.SKILL_POZHEN_LINGLEI));
            assertThat(eng.submitAction(PLAYER_B, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL,
                    PLAYER_B, 96099))).isTrue();
            var turn = eng.resolveCurrentRound();
            int remain = stateActor(turn.getState(), PLAYER_B).getBuffsList().stream()
                    .filter(b -> b.getBuffTableId() == 96021)
                    .mapToInt(com.game.proto.BattleBuffEntry::getRemainRounds).findFirst().orElse(0);
            assertThat(remain).isEqualTo(3);
            assertThat(turn.getEventsList())
                    .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getSeasonSealId() == SeasonRules.SEAL_POZHEN_XUANYIN
                            && e.getArtifactEffectId() == SeasonRules.RESONANCE_EVENT_MYSTIC_EXTEND_DEBUFF)
                    .singleElement()
                    .extracting(com.game.proto.BattleEventItem::getValue)
                    .isEqualTo(1L);
            var settle = eng.buildSettlement(PLAYER_A);
            assertThat(settle.getFinalSeasonSealId()).isEqualTo(SeasonRules.SEAL_POZHEN_XUANYIN);
            assertThat(settle.getSeasonSealContributionValue()).isPositive();
            assertThat(settle.getSeasonSealResonanceCount()).isEqualTo(1);
        }

        // 3. 103 固阵守印 × 15003 固阵灵障：R1 触发减伤 -> R2 固阵灵障护盾 1000 -> 1100（+100）
        {
            var reqGuard = request(3, 103);
            var a3 = addPlayer(reqGuard, PLAYER_A, 0, 20_000, 20_000, 0, 0, 0, 100)
                    .setMaxMana(10_000).setActiveSeasonId(1)
                    .addSkillTableIds(SeasonRules.SKILL_GUZHEN_LINGZHANG)
                    .putSkillRanks(SeasonRules.SKILL_GUZHEN_LINGZHANG, 1)
                    .setEquipmentEffects(EquipmentEffects.newBuilder()
                            .setSeasonSeal(SeasonSealEffect.newBuilder()
                                    .setSeasonId(1).setSealId(SeasonRules.SEAL_GUZHEN_SHOUYIN)
                                    .setEffectId(6).setBonusBps(1000).setCapBps(300)
                                    .setActiveRounds(2).setBattleLimit(1)
                                    .setResonanceSkillId(SeasonRules.SKILL_GUZHEN_LINGZHANG)
                                    .setResonanceKind(SeasonRules.RESONANCE_GUARD_SHIELD_BOOST)
                                    .setResonanceParam(10)));
            a3.getBaseAttributesBuilder().setMana(10_000);
            var b3 = addPlayer(reqGuard, PLAYER_B, 1, 20_000, 20_000, 0, 0, 0, 10)
                    .setMaxMana(10_000).setPhysicalAttack(2000);
            b3.getBaseAttributesBuilder().setMana(10_000);

            var eng = TestBattles.start(reqGuard, data);
            eng.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A));
            var r1 = eng.resolveCurrentRound();
            long mitigated = r1.getEventsList().stream()
                    .filter(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getSeasonSealId() == SeasonRules.SEAL_GUZHEN_SHOUYIN
                            && e.getArtifactEffectId() == 6)
                    .mapToLong(com.game.proto.BattleEventItem::getValue).findFirst().orElse(0L);
            assertThat(mitigated).isPositive();

            eng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL,
                    PLAYER_A, SeasonRules.SKILL_GUZHEN_LINGZHANG));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            var r2 = eng.resolveCurrentRound();
            long shieldCap = stateActor(r2.getState(), PLAYER_A).getBuffsList().stream()
                    .filter(b -> b.getBuffTableId() == 96030)
                    .mapToLong(com.game.proto.BattleBuffEntry::getRemainingCapacity).sum();
            assertThat(shieldCap).isEqualTo(1_100L);
            assertThat(r2.getEventsList())
                    .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getSeasonSealId() == SeasonRules.SEAL_GUZHEN_SHOUYIN
                            && e.getArtifactEffectId() == SeasonRules.RESONANCE_EVENT_GUARD_SHIELD_BOOST)
                    .singleElement()
                    .extracting(com.game.proto.BattleEventItem::getValue)
                    .isEqualTo(100L);
            var settle = eng.buildSettlement(PLAYER_A);
            assertThat(settle.getFinalSeasonSealId()).isEqualTo(SeasonRules.SEAL_GUZHEN_SHOUYIN);
            assertThat(settle.getSeasonSealContributionValue()).isEqualTo(mitigated + 100L);
            assertThat(settle.getSeasonSealTriggerCount()).isEqualTo(1);
            assertThat(settle.getSeasonSealResonanceCount()).isEqualTo(1);
        }

        // 4. 104 聚息援印 × 15004 破阵蓄势：R1 防御回蓝 400 -> R2 破阵蓄势耗蓝减半(500->250)，R3 恢复全价 500
        {
            var reqBreath = request(3, 104);
            var a = addPlayer(reqBreath, PLAYER_A, 0, 20_000, 20_000, 0, 0, 0, 100)
                    .setMaxMana(10_000).setActiveSeasonId(1)
                    .addSkillTableIds(SeasonRules.SKILL_POZHEN_XUSHI)
                    .putSkillRanks(SeasonRules.SKILL_POZHEN_XUSHI, 1)
                    .setEquipmentEffects(EquipmentEffects.newBuilder()
                            .setSeasonSeal(SeasonSealEffect.newBuilder()
                                    .setSeasonId(1).setSealId(SeasonRules.SEAL_JUXI_YUANYIN)
                                    .setEffectId(7).setBonusBps(400).setPerRoundLimit(1).setBattleLimit(3)
                                    .setResonanceSkillId(SeasonRules.SKILL_POZHEN_XUSHI)
                                    .setResonanceKind(SeasonRules.RESONANCE_DEFEND_MANA_DISCOUNT)
                                    .setResonanceParam(50)));
            a.getBaseAttributesBuilder().setMana(5_000);
            addPlayer(reqBreath, PLAYER_B, 1, 20_000, 20_000, 0, 0, 0, 10).setMaxMana(10_000);

            var eng = TestBattles.start(reqBreath, data);
            eng.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            var r1 = eng.resolveCurrentRound();
            assertThat(stateActor(r1.getState(), PLAYER_A).getAttributes().getMana()).isEqualTo(5_400L);

            eng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL,
                    PLAYER_A, SeasonRules.SKILL_POZHEN_XUSHI));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            var r2 = eng.resolveCurrentRound();
            assertThat(stateActor(r2.getState(), PLAYER_A).getAttributes().getMana()).isEqualTo(5_150L);
            assertThat(r2.getEventsList())
                    .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getSeasonSealId() == SeasonRules.SEAL_JUXI_YUANYIN
                            && e.getArtifactEffectId() == SeasonRules.RESONANCE_EVENT_BREATH_HALF_MANA)
                    .singleElement()
                    .extracting(com.game.proto.BattleEventItem::getValue)
                    .isEqualTo(250L);

            eng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL,
                    PLAYER_A, SeasonRules.SKILL_POZHEN_XUSHI));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            var r3 = eng.resolveCurrentRound();
            assertThat(stateActor(r3.getState(), PLAYER_A).getAttributes().getMana()).isEqualTo(4_650L);

            var settle = eng.buildSettlement(PLAYER_A);
            assertThat(settle.getFinalSeasonSealId()).isEqualTo(SeasonRules.SEAL_JUXI_YUANYIN);
            assertThat(settle.getSeasonSealContributionValue()).isEqualTo(650L);
            assertThat(settle.getSeasonSealTriggerCount()).isEqualTo(1);
            assertThat(settle.getSeasonSealResonanceCount()).isEqualTo(1);
        }
    }

    @Test
    void 赛季印记触发护盾增幅不越界并正确记录事件() {
        MemoryBattleData data = new MemoryBattleData();
        data.addSkill(97001).setBattleModel(1).setAbilityKind(1).setCasterMask(1)
                .setTargetTeam(2).setMaxTargets(1).setPetMaxTargets(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL)
                .setUnlockLevel(1).setMaxRank(10)
                .addEffect(97002);
        data.addBuff(97002).setBattleOp(BattleEffect.SHIELD).setValueBasis(1).setFlatValue(1_000.0).setDurationRounds(2);

        var req = request(3, 205);
        var a = addPlayer(req, PLAYER_A, 0, 20_000, 20_000, 0, 0, 0, 100)
                .setMaxMana(10_000).setActiveSeasonId(1)
                .addSkillTableIds(97001).putSkillRanks(97001, 1)
                .setEquipmentEffects(EquipmentEffects.newBuilder()
                        .setSeasonSeal(SeasonSealEffect.newBuilder()
                                .setSeasonId(1).setSealId(205)
                                .setEffectId(5).setBonusBps(1000).setPerRoundLimit(1).setBattleLimit(2)));
        a.getBaseAttributesBuilder().setMana(10_000);
        addPlayer(req, PLAYER_B, 1, 20_000, 20_000, 0, 0, 0, 10).setMaxMana(10_000);

        var eng = TestBattles.start(req, data);
        eng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL, PLAYER_A, 97001));
        eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        var r1 = eng.resolveCurrentRound();

        long shieldCap = stateActor(r1.getState(), PLAYER_A).getBuffsList().stream()
                .filter(b -> b.getBuffTableId() == 97002)
                .mapToLong(com.game.proto.BattleBuffEntry::getRemainingCapacity).sum();
        assertThat(shieldCap).isEqualTo(1_100L);
        assertThat(r1.getEventsList())
                .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                        && e.getSeasonSealId() == 205
                        && e.getArtifactEffectId() == 5)
                .singleElement()
                .extracting(com.game.proto.BattleEventItem::getValue)
                .isEqualTo(100L);
    }

    @Test
    void 赛季V4表驱动共鸣R5至R8_禁控印与零共鸣热更回滚() {
        var data = standard();
        // 15103 治疗技（1000 治疗，6% 蓝耗 = 600）
        data.addBuff(98010).setBattleOp(BattleEffect.HEAL).setFlatValue(1000.0);
        data.addSkill(15103).setBattleModel(1).setAbilityKind(1).setCasterMask(1)
                .setTargetTeam(2).setMaxTargets(1).setPetMaxTargets(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL)
                .setManaPercent(0.06).setSeasonId(2).setUnlockLevel(1).setMaxRank(10)
                .addEffect(98010);
        // 15104 护盾技（1000 护盾，6% 蓝耗 = 600）
        data.addBuff(98020).setBattleOp(BattleEffect.SHIELD).setDurationRounds(2)
                .setMaxLayer(1).setRefreshPolicy(2).setEffectPriority(20).setFlatValue(1000.0);
        data.addSkill(15104).setBattleModel(1).setAbilityKind(1).setCasterMask(1)
                .setTargetTeam(2).setMaxTargets(1).setPetMaxTargets(1)
                .addSkillType(BattleConstants.SKILL_TYPE_BIT_GENERAL)
                .setManaPercent(0.06).setSeasonId(2).setUnlockLevel(1).setMaxRank(10)
                .addEffect(98020);

        // 0. 严禁赛季印携带 effect_id=3 或 8
        var badReq = request(3, 401);
        addPlayer(badReq, PLAYER_A, 0, 10_000, 10_000, 0, 0, 0, 100)
                .setEquipmentEffects(EquipmentEffects.newBuilder()
                        .setSeasonSeal(SeasonSealEffect.newBuilder()
                                .setSeasonId(2).setSealId(299).setEffectId(3)
                                .setBonusBps(500).setPerRoundLimit(1).setBattleLimit(1)));
        addPlayer(badReq, PLAYER_B, 1, 10_000, 10_000, 0, 0, 0, 10);
        assertThat(TurnBattleEngine.start(badReq.build(), data)).isInstanceOf(BattleStart.Rejected.class);

        // 1. R5 急救增疗返蓝 (effect_id=4, kind=5, param=30%)
        {
            var req = request(3, 402);
            var a = addPlayer(req, PLAYER_A, 0, 20_000, 20_000, 0, 0, 0, 100)
                    .setMaxMana(10_000).setActiveSeasonId(2)
                    .addSkillTableIds(15103).putSkillRanks(15103, 1)
                    .setEquipmentEffects(EquipmentEffects.newBuilder()
                            .setSeasonSeal(SeasonSealEffect.newBuilder()
                                    .setSeasonId(2).setSealId(203).setEffectId(4)
                                    .setBonusBps(1000).setHealthThresholdBps(5000)
                                    .setPerRoundLimit(1).setBattleLimit(2)
                                    .setResonanceSkillId(15103)
                                    .setResonanceKind(SeasonRules.RESONANCE_EMERGENCY_HEAL_REFUND)
                                    .setResonanceParam(30)));
            a.getBaseAttributesBuilder().setHealth(5_000).setMana(10_000);
            addPlayer(req, PLAYER_B, 1, 20_000, 20_000, 0, 0, 0, 10).setMaxMana(10_000);
            var eng = TestBattles.start(req, data);
            eng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL, PLAYER_A, 15103));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            var r1 = eng.resolveCurrentRound();
            assertThat(stateActor(r1.getState(), PLAYER_A).getAttributes().getHealth()).isEqualTo(6_100L);
            assertThat(stateActor(r1.getState(), PLAYER_A).getAttributes().getMana()).isEqualTo(9_580L);
            assertThat(r1.getEventsList())
                    .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getArtifactEffectId() == SeasonRules.RESONANCE_EVENT_EMERGENCY_HEAL_REFUND)
                    .singleElement().extracting(com.game.proto.BattleEventItem::getValue).isEqualTo(180L);
        }

        // 2. R6 首盾增容回蓝 (effect_id=5, kind=6, param=250 bps = 250 mana)
        {
            var req = request(3, 403);
            var a = addPlayer(req, PLAYER_A, 0, 20_000, 20_000, 0, 0, 0, 100)
                    .setMaxMana(10_000).setActiveSeasonId(2)
                    .addSkillTableIds(15104).putSkillRanks(15104, 1)
                    .setEquipmentEffects(EquipmentEffects.newBuilder()
                            .setSeasonSeal(SeasonSealEffect.newBuilder()
                                    .setSeasonId(2).setSealId(204).setEffectId(5)
                                    .setBonusBps(1000).setPerRoundLimit(1).setBattleLimit(2)
                                    .setResonanceSkillId(15104)
                                    .setResonanceKind(SeasonRules.RESONANCE_FIRST_SHIELD_MANA)
                                    .setResonanceParam(250)));
            a.getBaseAttributesBuilder().setMana(5_000);
            addPlayer(req, PLAYER_B, 1, 20_000, 20_000, 0, 0, 0, 10).setMaxMana(10_000);
            var eng = TestBattles.start(req, data);
            eng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL, PLAYER_A, 15104));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            var r1 = eng.resolveCurrentRound();
            assertThat(stateActor(r1.getState(), PLAYER_A).getAttributes().getMana()).isEqualTo(4_650L);
            assertThat(r1.getEventsList())
                    .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getArtifactEffectId() == SeasonRules.RESONANCE_EVENT_FIRST_SHIELD_MANA)
                    .singleElement().extracting(com.game.proto.BattleEventItem::getValue).isEqualTo(250L);
        }

        // 3. R7 先手受击减伤 -> 下回合指定治疗技 +12% (effect_id=6, kind=7, param=12)
        {
            var req = request(3, 404);
            var a = addPlayer(req, PLAYER_A, 0, 20_000, 20_000, 0, 0, 0, 100)
                    .setMaxMana(10_000).setActiveSeasonId(2)
                    .addSkillTableIds(15103).putSkillRanks(15103, 1)
                    .setEquipmentEffects(EquipmentEffects.newBuilder()
                            .setSeasonSeal(SeasonSealEffect.newBuilder()
                                    .setSeasonId(2).setSealId(303).setEffectId(6)
                                    .setBonusBps(1000).setCapBps(300).setActiveRounds(2).setBattleLimit(1)
                                    .setResonanceSkillId(15103)
                                    .setResonanceKind(SeasonRules.RESONANCE_GUARD_HEAL_BOOST)
                                    .setResonanceParam(12)));
            a.getBaseAttributesBuilder().setHealth(10_000).setMana(10_000);
            addPlayer(req, PLAYER_B, 1, 20_000, 20_000, 0, 0, 0, 10).setMaxMana(10_000).setPhysicalAttack(2000);
            var eng = TestBattles.start(req, data);
            eng.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A));
            eng.resolveCurrentRound();
            long hpBefore = stateActor(eng.buildStateSnapshot(), PLAYER_A).getAttributes().getHealth();
            eng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL, PLAYER_A, 15103));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            var r2 = eng.resolveCurrentRound();
            assertThat(stateActor(r2.getState(), PLAYER_A).getAttributes().getHealth() - hpBefore).isEqualTo(1_120L);
            assertThat(r2.getEventsList())
                    .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getArtifactEffectId() == SeasonRules.RESONANCE_EVENT_GUARD_HEAL_BOOST)
                    .singleElement().extracting(com.game.proto.BattleEventItem::getValue).isEqualTo(120L);
        }

        // 4. R8 防御回蓝 -> 下回合指定护盾技 +10% (effect_id=7, kind=8, param=10) + resonance_kind=0 紧急回滚
        {
            var req = request(3, 405);
            var a = addPlayer(req, PLAYER_A, 0, 20_000, 20_000, 0, 0, 0, 100)
                    .setMaxMana(10_000).setActiveSeasonId(2)
                    .addSkillTableIds(15104).putSkillRanks(15104, 1)
                    .setEquipmentEffects(EquipmentEffects.newBuilder()
                            .setSeasonSeal(SeasonSealEffect.newBuilder()
                                    .setSeasonId(2).setSealId(604).setEffectId(7)
                                    .setBonusBps(400).setPerRoundLimit(1).setBattleLimit(3)
                                    .setResonanceSkillId(15104)
                                    .setResonanceKind(SeasonRules.RESONANCE_DEFEND_SHIELD_BOOST)
                                    .setResonanceParam(10)));
            a.getBaseAttributesBuilder().setMana(5_000);
            addPlayer(req, PLAYER_B, 1, 20_000, 20_000, 0, 0, 0, 10).setMaxMana(10_000);
            var eng = TestBattles.start(req, data);
            eng.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            eng.resolveCurrentRound();
            eng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL, PLAYER_A, 15104));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            var r2 = eng.resolveCurrentRound();
            long shieldCap = stateActor(r2.getState(), PLAYER_A).getBuffsList().stream()
                    .filter(b -> b.getBuffTableId() == 98020)
                    .mapToLong(com.game.proto.BattleBuffEntry::getRemainingCapacity).sum();
            assertThat(shieldCap).isEqualTo(1_100L);
            assertThat(r2.getEventsList())
                    .filteredOn(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT
                            && e.getArtifactEffectId() == SeasonRules.RESONANCE_EVENT_DEFEND_SHIELD_BOOST)
                    .singleElement().extracting(com.game.proto.BattleEventItem::getValue).isEqualTo(100L);

            // resonance_kind=0 紧急关闭：仍允许开局且不触发共鸣
            req.getPlayersBuilder(0).getEquipmentEffectsBuilder().getSeasonSealBuilder()
                    .setResonanceKind(SeasonRules.RESONANCE_NONE);
            var offEng = TestBattles.start(req, data);
            offEng.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
            offEng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            offEng.resolveCurrentRound();
            offEng.submitAction(PLAYER_A, action(com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL, PLAYER_A, 15104));
            offEng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            var r2Off = offEng.resolveCurrentRound();
            long shieldOff = stateActor(r2Off.getState(), PLAYER_A).getBuffsList().stream()
                    .filter(b -> b.getBuffTableId() == 98020)
                    .mapToLong(com.game.proto.BattleBuffEntry::getRemainingCapacity).sum();
            assertThat(shieldOff).isEqualTo(1_000L);
            assertThat(r2Off.getEventsList())
                    .noneMatch(e -> e.getEventType() == BATTLE_EVENT_ARTIFACT && e.getArtifactEffectId() >= 101);
        }
    }
}
