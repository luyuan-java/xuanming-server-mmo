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
}
