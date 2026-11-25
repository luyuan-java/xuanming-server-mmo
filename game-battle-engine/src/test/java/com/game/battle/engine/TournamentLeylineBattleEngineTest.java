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
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.TournamentBattleContext;
import com.game.proto.TournamentLeylineElement;
import org.junit.jupiter.api.Test;

/** 群仙问道赛 V2 五行地脉生效与对战快照上下文透传单测（与 C++ TournamentV2LeylinesAndSnapshotPropagation 对齐）。 */
class TournamentLeylineBattleEngineTest {

    private static long runOneAttackDamage(TournamentLeylineElement leyline, long seed) {
        var req = request(3, seed);
        if (leyline != TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED) {
            req.setTournamentContext(TournamentBattleContext.newBuilder()
                    .setSeasonId(1).setMatchId(101).setBestOf(3).setGameIndex(1)
                    .setLeylineElement(leyline)
                    .setSquadAName("太玄剑阁").setSquadBName("青云仙宗"));
        }
        addPlayer(req, PLAYER_A, 0, 10_000, 10_000, 2000, 0, 0, 100)
                .setMaxMana(10_000).setPhysicalAttack(2000).setMagicAttack(2000);
        addPlayer(req, PLAYER_B, 1, 10_000, 10_000, 200, 0, 0, 10)
                .setMaxMana(10_000).setPhysicalAttack(200).setMagicAttack(200);
        var eng = TestBattles.start(req, standard());
        eng.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        eng.resolveCurrentRound();
        var afterB = stateActor(eng.buildStateSnapshot(), PLAYER_B);
        return 10_000L - afterB.getAttributes().getHealth();
    }

    @Test
    void 五行地脉数值增益与快照透传全覆盖() {
        // 1. 快照透传且 stripEngineOnlyState 不剥离 tournament_context
        var ctxReq = request(3, 77);
        ctxReq.setTournamentContext(TournamentBattleContext.newBuilder()
                .setSeasonId(2).setMatchId(888).setBestOf(3).setGameIndex(2)
                .setWinsA(1).setWinsB(0)
                .setLeylineElement(TournamentLeylineElement.TOURNAMENT_LEYLINE_FIRE)
                .setSquadAId(11).setSquadAName("太玄剑阁")
                .setSquadBId(22).setSquadBName("青云仙宗"));
        addPlayer(ctxReq, PLAYER_A, 0, 5000, 5000, 1000, 0, 0, 100);
        addPlayer(ctxReq, PLAYER_B, 1, 5000, 5000, 1000, 0, 0, 50);
        var ctxEng = TestBattles.start(ctxReq, standard());
        var snap = ctxEng.buildStateSnapshot().toBuilder();
        TurnBattleEngine.stripEngineOnlyState(snap);
        assertThat(snap.hasTournamentContext()).isTrue();
        assertThat(snap.getTournamentContext().getBestOf()).isEqualTo(3);
        assertThat(snap.getTournamentContext().getGameIndex()).isEqualTo(2);
        assertThat(snap.getTournamentContext().getLeylineElement())
                .isEqualTo(TournamentLeylineElement.TOURNAMENT_LEYLINE_FIRE);

        // 2. 火炽(+6% 伤害) > 金锐(+4% 伤害) > 无地脉 > 土厚(-5% 伤害且第 1 回合 8% 气血护盾抵消)
        long baseDmg = runOneAttackDamage(TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED, 999L);
        long metalDmg = runOneAttackDamage(TournamentLeylineElement.TOURNAMENT_LEYLINE_METAL, 999L);
        long fireDmg = runOneAttackDamage(TournamentLeylineElement.TOURNAMENT_LEYLINE_FIRE, 999L);
        long earthDmg = runOneAttackDamage(TournamentLeylineElement.TOURNAMENT_LEYLINE_EARTH, 999L);
        assertThat(fireDmg).isGreaterThan(baseDmg);
        assertThat(metalDmg).isGreaterThan(baseDmg);
        assertThat(earthDmg).isLessThan(baseDmg);

        // 3. 水润：每回合开始额外回复 5% 最大法力
        var waterReq = request(3, 101);
        waterReq.setTournamentContext(TournamentBattleContext.newBuilder()
                .setSeasonId(1).setMatchId(102).setBestOf(3).setGameIndex(1)
                .setLeylineElement(TournamentLeylineElement.TOURNAMENT_LEYLINE_WATER));
        var waterA = addPlayer(waterReq, PLAYER_A, 0, 5000, 5000, 500, 0, 0, 100).setMaxMana(1000);
        waterA.getBaseAttributesBuilder().setMana(200);
        addPlayer(waterReq, PLAYER_B, 1, 5000, 5000, 500, 0, 0, 10).setMaxMana(1000);
        var waterEng = TestBattles.start(waterReq, standard());
        waterEng.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        waterEng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        waterEng.resolveCurrentRound();
        assertThat(stateActor(waterEng.buildStateSnapshot(), PLAYER_A).getAttributes().getMana())
                .isGreaterThanOrEqualTo(250L);

        // 4. 木生：治疗技能回复量提升 +10%
        var data = standard();
        data.addSkill(19001)
                .setName("青木回春")
                .setClassId(7)
                .setTargetTeam(3)  // self
                .setMaxTargets(1)
                .setDamageType(1)
                .setHealPerLevel(100)
                .setUnlockLevel(1)
                .setMaxRank(10);
        long baseHeal = runOneHeal(data, TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED);
        long woodHeal = runOneHeal(data, TournamentLeylineElement.TOURNAMENT_LEYLINE_WOOD);
        assertThat(baseHeal).isPositive();
        assertThat(woodHeal).isGreaterThan(baseHeal);
    }

    private static long runOneHeal(MemoryBattleData data, TournamentLeylineElement leyline) {
        var req = request(3, 55);
        if (leyline != TournamentLeylineElement.TOURNAMENT_LEYLINE_UNSPECIFIED) {
            req.setTournamentContext(TournamentBattleContext.newBuilder()
                    .setSeasonId(1).setMatchId(103).setBestOf(3).setGameIndex(1)
                    .setLeylineElement(leyline));
        }
        var a = addPlayer(req, PLAYER_A, 0, 4_000, 10_000, 500, 0, 0, 100)
                .setClassId(7)
                .setMaxMana(5000).setMagicAttack(500)
                .addSkillTableIds(19001).putSkillRanks(19001, 1);
        a.getBaseAttributesBuilder().setMana(5000);
        addPlayer(req, PLAYER_B, 1, 10_000, 10_000, 100, 0, 0, 10);
        var eng = TestBattles.start(req, data);
        eng.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_A, 19001));
        eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        eng.resolveCurrentRound();
        return stateActor(eng.buildStateSnapshot(), PLAYER_A).getAttributes().getHealth() - 4_000L;
    }
}
