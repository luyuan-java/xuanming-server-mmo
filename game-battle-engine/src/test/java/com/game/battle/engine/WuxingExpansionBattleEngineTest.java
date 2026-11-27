package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPet;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.standard;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_ATTACK;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_DAMAGE;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_WUXING_SYNERGY;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleEventItem;
import com.game.proto.BattleStateS2C;
import com.game.proto.TournamentLeylineElement;
import com.game.proto.WuxingTowerBattleContext;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 五行玩法三大扩展 V1（阵法与克制、人宠五行相生合击、五行幻境爬塔/符印/结算）引擎单测。 */
class WuxingExpansionBattleEngineTest {

    @Test
    void 五行阵法基础属性_循环克制_开场护盾与回合回蓝全部按口径生效() {
        // 1. 循环克制：1 天覆 > 2 地载 > 3 风扬 > 4 云垂 > 5 虎翼 > 1 天覆
        assertThat(WuxingExpansionRules.formationCounterBonusPct(1, 2)).isEqualTo(5);
        assertThat(WuxingExpansionRules.formationCounterBonusPct(2, 3)).isEqualTo(5);
        assertThat(WuxingExpansionRules.formationCounterBonusPct(3, 4)).isEqualTo(5);
        assertThat(WuxingExpansionRules.formationCounterBonusPct(4, 5)).isEqualTo(5);
        assertThat(WuxingExpansionRules.formationCounterBonusPct(5, 1)).isEqualTo(5);
        assertThat(WuxingExpansionRules.formationCounterBonusPct(2, 1)).isZero();

        // 2. 非法阵法 ID 拒绝开局
        var badReq = request(3, 7);
        addPlayer(badReq, PLAYER_A, 0, 5000, 5000, 100, 0, 0, 100).setFormationId(99);
        addPlayer(badReq, PLAYER_B, 1, 5000, 5000, 100, 0, 0, 100);
        assertThat(TurnBattleEngine.start(badReq.build(), standard())).isInstanceOf(BattleStart.Rejected.class);

        // 3. 虎翼阵（5，直伤+0%，克天覆）打天覆阵（1，减伤0%）比打无阵法多出纯 +5% 克制直伤
        var reqCounter = request(3, 42);
        addPlayer(reqCounter, PLAYER_A, 0, 10_000, 10_000, 1000, 0, 0, 100)
                .setPhysicalAttack(1000).setFormationId(5);
        addPlayer(reqCounter, PLAYER_B, 1, 10_000, 10_000, 1000, 0, 0, 90)
                .setPhysicalAttack(1000).setFormationId(1);
        var engCounter = TestBattles.start(reqCounter, standard());
        BattleStateS2C init = engCounter.buildStateSnapshot();
        assertThat(init.getTeamAFormationId()).isEqualTo(5);
        assertThat(init.getTeamBFormationId()).isEqualTo(1);

        var reqBase = request(3, 42);
        addPlayer(reqBase, PLAYER_A, 0, 10_000, 10_000, 1000, 0, 0, 100)
                .setPhysicalAttack(1000).setFormationId(5);
        addPlayer(reqBase, PLAYER_B, 1, 10_000, 10_000, 1000, 0, 0, 90)
                .setPhysicalAttack(1000).setFormationId(0);
        var engBase = TestBattles.start(reqBase, standard());

        engCounter.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        engCounter.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        var rCounter = engCounter.resolveCurrentRound();

        engBase.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        engBase.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        var rBase = engBase.resolveCurrentRound();

        long dmgCounter = firstDamageValue(rCounter.getEventsList(), PLAYER_A, PLAYER_B);
        long dmgBase = firstDamageValue(rBase.getEventsList(), PLAYER_A, PLAYER_B);
        assertThat(dmgCounter).isGreaterThan(dmgBase);

        // 4. 地载阵（2）开场 4% 护盾（400）
        var reqDizai = request(3, 7);
        addPlayer(reqDizai, PLAYER_A, 0, 10_000, 10_000, 100, 0, 0, 100).setFormationId(2);
        addPlayer(reqDizai, PLAYER_B, 1, 10_000, 10_000, 100, 0, 0, 100);
        var engDizai = TestBattles.start(reqDizai, standard());
        assertThat(engDizai.tournamentEarthShieldOf(PLAYER_A)).isEqualTo(400L);

        // 5. 风扬阵（3）+6% 速度使低面板速反超先手，云垂阵（4）回合初 3% 回蓝（+300）
        var reqSpdMana = request(3, 9);
        addPlayer(reqSpdMana, PLAYER_A, 0, 10_000, 10_000, 100, 0, 0, 96)
                .setMaxMana(10_000).setFormationId(3);
        var bMana = addPlayer(reqSpdMana, PLAYER_B, 1, 10_000, 10_000, 100, 0, 0, 100)
                .setMaxMana(10_000).setFormationId(4);
        bMana.getBaseAttributesBuilder().setMana(5_000);
        var engSpdMana = TestBattles.start(reqSpdMana, standard());
        engSpdMana.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        engSpdMana.submitAction(PLAYER_B, action(BATTLE_ACTION_ATTACK, PLAYER_A));
        var rSpdMana = engSpdMana.resolveCurrentRound();
        var firstAtk = rSpdMana.getEventsList().stream()
                .filter(e -> e.getEventType() == BATTLE_EVENT_DAMAGE)
                .findFirst().orElseThrow();
        assertThat(firstAtk.getSourceId()).isEqualTo(PLAYER_A);
        assertThat(stateActor(engSpdMana.buildStateSnapshot(), PLAYER_B).getAttributes().getMana())
                .isEqualTo(5_300L);
    }

    @Test
    void 人宠五行相生仅在第3_6_9回合触发合击事件与状态标记() {
        var req = request(3, 13);
        // 门派 1（金）+ 宠物表 ID 3（水）：金生水
        var a = addPlayer(req, PLAYER_A, 0, 20_000, 20_000, 100, 0, 0, 100).setClassId(1);
        addPet(a, 5001L, 10_000, 10_000, 100, 90, 100).setPetTableId(3);
        addPlayer(req, PLAYER_B, 1, 20_000, 20_000, 100, 0, 0, 50);

        var eng = TestBattles.start(req, standard());
        long petActorId = eng.buildStateSnapshot().getActorsList().stream()
                .filter(act -> act.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET)
                .findFirst().orElseThrow().getActorId();

        // 回合 1、2 不触发
        for (int round = 1; round <= 2; round++) {
            eng.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
            eng.submitAction(petActorId, action(BATTLE_ACTION_DEFEND));
            eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            var res = eng.resolveCurrentRound();
            assertThat(res.getEventsList())
                    .noneMatch(e -> e.getEventType() == BATTLE_EVENT_WUXING_SYNERGY);
            assertThat(eng.buildStateSnapshot().getWuxingSynergyActiveThisRound()).isFalse();
        }

        // 回合 3 触发金生水合击事件（value = 10）且快照标记 wuxing_synergy_active_this_round = true
        eng.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        eng.submitAction(petActorId, action(BATTLE_ACTION_DEFEND));
        eng.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        var r3 = eng.resolveCurrentRound();
        assertThat(r3.getEventsList())
                .filteredOn(e -> e.getEventType() == BATTLE_EVENT_WUXING_SYNERGY)
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getSourceId()).isEqualTo(PLAYER_A);
                    assertThat(e.getTargetId()).isEqualTo(petActorId);
                    assertThat(e.getValue()).isEqualTo(WuxingExpansionRules.SYNERGY_BONUS_PCT);
                });
        assertThat(eng.buildStateSnapshot().getWuxingSynergyActiveThisRound()).isTrue();
    }

    @Test
    void 五行幻境校验_层级缩扩_符印生效与塔结算填充() {
        // 1. 非 PVE_SOLO、非法层数、非法符印均拒绝开局
        var badMode = request(3, 1);
        addPlayer(badMode, PLAYER_A, 0, 5000, 5000, 100, 0, 0, 100)
                .setTowerContext(WuxingTowerBattleContext.newBuilder().setWeekKey(100).setRunId(1).setFloor(1));
        addPlayer(badMode, PLAYER_B, 1, 5000, 5000, 100, 0, 0, 100);
        assertThat(TurnBattleEngine.start(badMode.build(), standard())).isInstanceOf(BattleStart.Rejected.class);

        var badFloor = request(BattleConstants.MATCH_MODE_PVE_SOLO, 1);
        addPlayer(badFloor, PLAYER_A, 0, 5000, 5000, 100, 0, 0, 100)
                .setTowerContext(WuxingTowerBattleContext.newBuilder().setWeekKey(100).setRunId(1).setFloor(11));
        assertThat(TurnBattleEngine.start(badFloor.build(), standard())).isInstanceOf(BattleStart.Rejected.class);

        var badSigil = request(BattleConstants.MATCH_MODE_PVE_SOLO, 1);
        addPlayer(badSigil, PLAYER_A, 0, 5000, 5000, 100, 0, 0, 100)
                .setTowerContext(WuxingTowerBattleContext.newBuilder()
                        .setWeekKey(100).setRunId(1).setFloor(1).addActiveSigilIds(999));
        assertThat(TurnBattleEngine.start(badSigil.build(), standard())).isInstanceOf(BattleStart.Rejected.class);

        // 2. 第 1 层 vs 第 5 层怪物缩放 + 符印 501（12% 开场护盾）、203（回合初 5% 回血）、502（反伤 12%）
        var reqF1 = request(BattleConstants.MATCH_MODE_PVE_SOLO, 11);
        addPlayer(reqF1, PLAYER_A, 0, 8_000, 10_000, 5000, 0, 0, 100)
                .setPhysicalAttack(5000)
                .setTowerContext(WuxingTowerBattleContext.newBuilder()
                        .setWeekKey(100).setRunId(7).setFloor(1)
                        .setLeylineElement(TournamentLeylineElement.TOURNAMENT_LEYLINE_METAL)
                        .addActiveSigilIds(501)
                        .addActiveSigilIds(203)
                        .addActiveSigilIds(502));
        var engF1 = TestBattles.start(reqF1, standard());

        var reqF5 = request(BattleConstants.MATCH_MODE_PVE_SOLO, 11);
        addPlayer(reqF5, PLAYER_A, 0, 10_000, 10_000, 5000, 0, 0, 100)
                .setPhysicalAttack(5000)
                .setTowerContext(WuxingTowerBattleContext.newBuilder()
                        .setWeekKey(100).setRunId(7).setFloor(5)
                        .setLeylineElement(TournamentLeylineElement.TOURNAMENT_LEYLINE_EARTH));
        var engF5 = TestBattles.start(reqF5, standard());

        var f1Snap = engF1.buildStateSnapshot();
        var f5Snap = engF5.buildStateSnapshot();
        assertThat(f1Snap.hasTowerContext()).isTrue();
        assertThat(f1Snap.getTowerContext().getRunId()).isEqualTo(7);
        // 501 厚土壁垒开场赋予 12% 最大气血护盾（1200）
        assertThat(engF1.tournamentEarthShieldOf(PLAYER_A)).isEqualTo(1_200L);
        // 第 5 层怪物血量显著高于第 1 层（F1=85%，F5=127%）
        long f1MonsterHp = f1Snap.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER)
                .findFirst().orElseThrow().getMaxHealth();
        long f5MonsterHp = f5Snap.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER)
                .findFirst().orElseThrow().getMaxHealth();
        assertThat(f5MonsterHp).isGreaterThan(f1MonsterHp);

        // 玩家在 F1 先防御一回合验证 203 回血（8000 -> 8500）且 501 护盾挡下怪物伤害
        engF1.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        engF1.resolveCurrentRound();
        assertThat(stateActor(engF1.buildStateSnapshot(), PLAYER_A).getAttributes().getHealth())
                .isEqualTo(8_500L);

        // 连续攻击直到击杀全部怪物
        for (int i = 0; i < 10 && engF1.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; i++) {
            long targetMonster = engF1.buildStateSnapshot().getActorsList().stream()
                    .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER
                            && a.getAttributes().getHealth() > 0)
                    .findFirst().orElseThrow().getActorId();
            engF1.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, targetMonster));
            engF1.resolveCurrentRound();
        }
        assertThat(engF1.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        var settlement = engF1.buildSettlement(PLAYER_A);
        assertThat(settlement.hasTowerSettlement()).isTrue();
        assertThat(settlement.getTowerSettlement().getWeekKey()).isEqualTo(100);
        assertThat(settlement.getTowerSettlement().getRunId()).isEqualTo(7);
        assertThat(settlement.getTowerSettlement().getFloor()).isEqualTo(1);
        assertThat(settlement.getTowerSettlement().getTotalRounds()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void 五行幻境符印三选一确定性去重且前三层不出高阶符印与周键周一早5点切周() {
        List<Integer> offerA = WuxingExpansionRules.rollTowerSigilOffer(100, 3, 2, Set.of(101, 201));
        List<Integer> offerB = WuxingExpansionRules.rollTowerSigilOffer(100, 3, 2, Set.of(101, 201));
        assertThat(offerA).isEqualTo(offerB).hasSize(3).doesNotHaveDuplicates().doesNotContain(101, 201);
        for (int id : offerA) {
            assertThat(WuxingExpansionRules.findTowerSigil(id).rarity()).isLessThan(3);
        }

        // 与 C++ ComputeWuxingTowerWeekKey 逐位对齐（+69h 偏移切周边界）
        long beforeReset = Instant.parse("2026-11-23T02:59:59Z").toEpochMilli();
        long atReset = Instant.parse("2026-11-23T03:00:00Z").toEpochMilli();
        int wkBefore = WuxingExpansionRules.computeWuxingTowerWeekKey(beforeReset);
        int wkAt = WuxingExpansionRules.computeWuxingTowerWeekKey(atReset);
        assertThat(wkAt).isEqualTo(wkBefore + 1);
    }

    private static long firstDamageValue(List<BattleEventItem> events, long sourceId, long targetId) {
        return events.stream()
                .filter(e -> e.getEventType() == BATTLE_EVENT_DAMAGE
                        && e.getSourceId() == sourceId
                        && e.getTargetId() == targetId)
                .mapToLong(BattleEventItem::getValue)
                .findFirst()
                .orElseThrow();
    }
}
