package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleEventType;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.table.ConfigTables;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 统一经济与首领试炼（101–108）/ 护卫救援（109）/ 物法易势 5V5（110）引擎契约测试。 */
class UnifiedEconomyBattleEngineTest {

    private static ConfigTables tables;
    private static TableBattleData data;

    @BeforeAll
    static void loadTables() {
        tables = ConfigTables.load(Path.of("../config-data/tables"));
        data = new TableBattleData(tables);
    }

    private static BattlePlayerSnapshot player(long id, int team, long hp, long speed, long physAtk, long magicAtk) {
        return BattlePlayerSnapshot.newBuilder()
                .setPlayerId(id)
                .setPlayerName("P" + id)
                .setTeamIndex(team)
                .setLevel(40)
                .setClassId(1)
                .setMaxHealth(hp)
                .setMaxMana(1000)
                .setPhysicalAttack(physAtk)
                .setMagicAttack(magicAtk)
                .setDefense(100)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(hp).setMana(1000).setSpeed(speed).setStrength(20).setArmor(20).build())
                .build();
    }

    @Test
    void 首领试炼按人数线性放大血量不加怪数_预告蓄力重击与受控取消不回退普攻() {
        // 101 普通破阵·回春：2 人入场仍只刷 1 首领 + 1 护卫，且血量为单人 2 倍
        CreateBattleRequest soloReq = CreateBattleRequest.newBuilder()
                .setBattleId(9501L).setBattleConfigId(101).setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setSeed(101L).addPlayers(player(1001L, 0, 50_000, 200, 300, 100)).build();
        CreateBattleRequest duoReq = CreateBattleRequest.newBuilder()
                .setBattleId(9502L).setBattleConfigId(101).setMatchMode(MatchMode.MATCH_MODE_PVE_TEAM_VALUE)
                .setSeed(101L)
                .addPlayers(player(1001L, 0, 50_000, 200, 300, 100))
                .addPlayers(player(1002L, 0, 50_000, 190, 300, 100))
                .build();

        TurnBattleEngine solo = ((BattleStart.Started) TurnBattleEngine.start(soloReq, data)).engine();
        TurnBattleEngine duo = ((BattleStart.Started) TurnBattleEngine.start(duoReq, data)).engine();

        List<BattleActorState> soloMonsters = solo.buildStateSnapshot().getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER).toList();
        List<BattleActorState> duoMonsters = duo.buildStateSnapshot().getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER).toList();
        assertThat(soloMonsters).hasSize(2);
        assertThat(duoMonsters).hasSize(2);
        assertThat(soloMonsters).anyMatch(BattleActorState::getIsBoss);
        assertThat(duoMonsters.get(0).getMaxHealth()).isEqualTo(soloMonsters.get(0).getMaxHealth() * 2L);

        // R1 开局即发布首领蓄力慢重击预告（R2 兑现）
        BattleStateS2C r1State = solo.buildStateSnapshot();
        assertThat(r1State.getTelegraphsList()).anySatisfy(t -> {
            assertThat(t.getIntentKind()).isEqualTo(BattleConstants.TELEGRAPH_KIND_SLOW_CLEAVE);
            assertThat(t.getExecuteRound()).isEqualTo(2);
            assertThat(t.getStatus()).isEqualTo(BattleConstants.TELEGRAPH_STATUS_UPCOMING);
        });

        // 结算 R1 进入 R2，给首领挂眩晕（BUFF_TYPE_STUN）以验证 R2 蓄力重击受控取消且不回退普攻
        solo.submitAction(1001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        solo.resolveCurrentRound();
        BattleStateS2C r2Before = solo.buildStateSnapshot();
        assertThat(r2Before.getRoundIndex()).isEqualTo(2);
        assertThat(r2Before.getTelegraphsList()).anySatisfy(t -> {
            assertThat(t.getIntentKind()).isEqualTo(BattleConstants.TELEGRAPH_KIND_SLOW_CLEAVE);
            assertThat(t.getStatus()).isEqualTo(BattleConstants.TELEGRAPH_STATUS_EXECUTING);
        });

        long bossId = soloMonsters.stream().filter(BattleActorState::getIsBoss).findFirst().orElseThrow().getActorId();
        int freezeBuffId = tables.buff().all().stream()
                .filter(b -> b.getBattleOp() == BattleEffect.CONTROL && b.getStateKind() == BattleEffect.FREEZE)
                .map(com.game.table.BuffTable::getId).findFirst().orElseThrow();
        BattleUnit bossUnit = solo.findUnitForTest(bossId);
        bossUnit.state().addBuffs(BattleBuffEntry.newBuilder()
                .setBuffId(999_001L).setBuffTableId(freezeBuffId).setLayer(1).setRemainRounds(2).setCasterId(1001L));

        solo.submitAction(1001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        TurnResultS2C r2Result = solo.resolveCurrentRound();
        // 首领既没有发慢重击也没有回退普攻
        assertThat(r2Result.getEventsList())
                .noneMatch(e -> e.getSourceId() == bossId && e.getEventType() == eBattleEventType.BATTLE_EVENT_ATTACK);

        // 结算字段 26–29 冻结准确
        BattleSettlementData settlement = solo.buildSettlement(1001L);
        assertThat(settlement.getManualActionRounds()).isEqualTo(2);
        assertThat(settlement.getBattleDifficulty()).isEqualTo(1);
        assertThat(settlement.getBossTrialKind()).isEqualTo(BattleConstants.BOSS_TRIAL_KIND_BREAK_REJUV);
    }

    @Test
    void 护势可被驱散且灵障护盾先于血量吸收并计有效贡献() {
        // 103 破阵·护势：R1 护卫施加物理护势，30% 减伤；驱散后归零
        CreateBattleRequest stanceReq = CreateBattleRequest.newBuilder()
                .setBattleId(9511L).setBattleConfigId(103).setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setSeed(103L).addPlayers(player(2001L, 0, 50_000, 200, 500, 500)).build();
        TurnBattleEngine stanceEngine = ((BattleStart.Started) TurnBattleEngine.start(stanceReq, data)).engine();
        stanceEngine.resolveCurrentRound(); // R1 护卫施展物理护势
        BattleUnit stanceBoss = stanceEngine.findUnitForTest(stanceEngine.buildStateSnapshot().getActorsList().stream()
                .filter(BattleActorState::getIsBoss).findFirst().orElseThrow().getActorId());
        assertThat(stanceEngine.encounterForTest().bossStanceType()).isEqualTo(1);
        stanceEngine.encounterForTest().tryDispelBossStance(stanceEngine.findUnitForTest(2001L), stanceBoss);
        assertThat(stanceEngine.encounterForTest().bossStanceType()).isZero();
        assertThat(stanceEngine.encounterForTest().guardStanceDispelled()).isTrue();
        assertThat(stanceEngine.buildSettlement(2001L).getEffectiveCombatContribution()).isTrue();

        // 105 破阵·灵障：R1 护卫附加灵障护盾；R2 玩家攻击首领时护盾先吸收并触发 SHIELD 事件
        CreateBattleRequest barrierReq = CreateBattleRequest.newBuilder()
                .setBattleId(9512L).setBattleConfigId(105).setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setSeed(105L).addPlayers(player(2002L, 0, 50_000, 200, 500, 500)).build();
        TurnBattleEngine barrierEngine = ((BattleStart.Started) TurnBattleEngine.start(barrierReq, data)).engine();
        barrierEngine.resolveCurrentRound();
        long initialShield = barrierEngine.encounterForTest().bossBarrierShield();
        assertThat(initialShield).isGreaterThan(0L);
        long barrierBossId = barrierEngine.buildStateSnapshot().getActorsList().stream()
                .filter(BattleActorState::getIsBoss).findFirst().orElseThrow().getActorId();
        barrierEngine.submitAction(2002L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(barrierBossId).build());
        TurnResultS2C r2 = barrierEngine.resolveCurrentRound();
        assertThat(r2.getEventsList()).anyMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_SHIELD
                && e.getAbsorbed() > 0L);
        assertThat(barrierEngine.encounterForTest().bossBarrierShield()).isLessThan(initialShield);
        assertThat(barrierEngine.buildSettlement(2002L).getEffectiveCombatContribution()).isTrue();
    }

    @Test
    void 护卫救援将护卫置于玩家侧并默认防御_护卫阵亡即刻判负() {
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9521L).setBattleConfigId(109).setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setSeed(109L).addPlayers(player(3001L, 0, 50_000, 200, 300, 100)).build();
        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        BattleStateS2C state = engine.buildStateSnapshot();
        long guardId = state.getProtectedGuardActorId();
        assertThat(guardId).isNotZero();
        BattleActorState guardState = state.getActorsList().stream()
                .filter(a -> a.getActorId() == guardId).findFirst().orElseThrow();
        assertThat(guardState.getTeamIndex()).isZero();
        assertThat(guardState.getIsBoss()).isFalse();

        // R1 护卫自动防御
        TurnResultS2C r1 = engine.resolveCurrentRound();
        assertThat(r1.getEventsList()).anyMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_DEFEND
                && e.getSourceId() == guardId);

        // 护卫阵亡 -> 即便玩家存活仍立刻判 B 胜（挑战失败）
        BattleUnit guardUnit = engine.findUnitForTest(guardId);
        guardUnit.setHealth(0L);
        guardUnit.state().setIsDead(true);
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN);
    }

    @Test
    void 物法易势5V5每两回合换势_强势类型直伤115且满16回合判平() {
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9531L).setBattleConfigId(110).setMatchMode(MatchMode.MATCH_MODE_POLARITY_5V5_VALUE)
                .setSeed(110L)
                .addPlayers(player(4001L, 0, 500_000, 200, 1000, 1000))
                .addPlayers(player(4002L, 1, 500_000, 190, 1000, 1000))
                .build();
        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        BattleStateS2C r1 = engine.buildStateSnapshot();
        int p1 = r1.getActivePolarity();
        assertThat(p1).isIn(1, 2);
        assertThat(r1.getNextPolaritySwitchRound()).isEqualTo(3);

        // 验证同势直接伤害 ×115/100，异势保持不变（1=物理，2=法术；表伤害类型 1=物理，0=法术）
        int strongDamageType = p1 == 1 ? com.game.common.combat.CombatDamageRules.PHYSICAL_DAMAGE
                : com.game.common.combat.CombatDamageRules.MAGIC_DAMAGE;
        int weakDamageType = p1 == 1 ? com.game.common.combat.CombatDamageRules.MAGIC_DAMAGE
                : com.game.common.combat.CombatDamageRules.PHYSICAL_DAMAGE;
        long boosted = engine.encounterForTest().applyDamageModifiers(
                engine.findUnitForTest(4001L), engine.findUnitForTest(4002L), 1000L, strongDamageType, true);
        long unboosted = engine.encounterForTest().applyDamageModifiers(
                engine.findUnitForTest(4001L), engine.findUnitForTest(4002L), 1000L, weakDamageType, true);
        assertThat(boosted).isEqualTo(1150L);
        assertThat(unboosted).isEqualTo(1000L);

        // R2 同势，R3 换势（下次换势为 R5）
        engine.resolveCurrentRound(); // R1 -> R2
        assertThat(engine.buildStateSnapshot().getActivePolarity()).isEqualTo(p1);
        engine.resolveCurrentRound(); // R2 -> R3
        BattleStateS2C r3 = engine.buildStateSnapshot();
        assertThat(r3.getActivePolarity()).isEqualTo(p1 == 1 ? 2 : 1);
        assertThat(r3.getNextPolaritySwitchRound()).isEqualTo(5);

        // 打满 16 回合双方存活判平局
        for (int round = 3; round <= 16 && engine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; round++) {
            engine.resolveCurrentRound();
        }
        assertThat(engine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
        BattleSettlementData s = engine.buildSettlement(4001L);
        assertThat(s.getTotalRounds()).isEqualTo(16);
        assertThat(s.getBossTrialKind()).isEqualTo(BattleConstants.BOSS_TRIAL_KIND_POLARITY_5V5);
    }
}
