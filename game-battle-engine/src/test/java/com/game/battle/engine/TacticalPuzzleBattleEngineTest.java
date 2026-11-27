package com.game.battle.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleBuffEntry;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.RankedMatchContext;
import com.game.proto.TacticalPuzzleBattleContext;
import com.game.proto.TacticalPuzzleEnemySpec;
import com.game.proto.TacticalPuzzleSpec;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleOutcome;
import com.game.table.BuffTable;
import com.game.table.DungeonTable;
import com.game.table.MonsterTable;
import java.util.List;
import org.junit.jupiter.api.Test;

class TacticalPuzzleBattleEngineTest {

    private static final long HERO_A = 10001L;

    private static MemoryBattleData buildData() {
        MemoryBattleData data = new MemoryBattleData();
        data.addDungeon(TacticalPuzzleRules.WORKSHOP_DUNGEON_ID)
                .setBossTrialKind(BattleConstants.BOSS_TRIAL_KIND_TACTICAL_PUZZLE)
                .setDifficulty(1)
                .setRoundLimit(6);
        data.setDungeonMonsters(TacticalPuzzleRules.WORKSHOP_DUNGEON_ID, 311);
        data.addMonster(311).setHealth(1000).setSpeed(100).setStrength(10);
        data.addMonster(312).setHealth(1000).setSpeed(100).setStrength(10);
        data.addBuff(TacticalPuzzleRules.SHIELD_BUFF_ID)
                .setBattleOp(BattleEffect.SHIELD)
                .setDurationRounds(2);
        data.addBuff(TacticalPuzzleRules.DEBUFF_POISON_BUFF_ID)
                .setBattleOp(BattleEffect.PERIODIC)
                .setDurationRounds(3)
                .setTickCount(3)
                .setTickOnApply(1);
        data.addBuff(TacticalPuzzleRules.BUFF_DEF_UP_BUFF_ID)
                .setBattleOp(BattleEffect.MODIFIER)
                .setModifierStat(3)
                .setRatio(0.25)
                .setDurationRounds(5);
        data.addBuff(TacticalPuzzleRules.BUFF_REFLECT_BUFF_ID)
                .setBattleOp(BattleEffect.REFLECT)
                .setDamageFilter(1)
                .setSecondaryRatio(0.25)
                .setDurationRounds(3);
        return data;
    }

    private static CreateBattleRequest validRequest() {
        TacticalPuzzleSpec spec = TacticalPuzzleSpec.newBuilder()
                .setRoundLimit(2)
                .setCharacterPresetId(101)
                .setPetPresetId(0)
                .setFormationId(3)
                .setPlayerInitialHpPct(50)
                .setPlayerInitialMpPct(60)
                .setPlayerInitialShieldPct(20)
                .setPlayerInitialDebuffKind(3)
                .addEnemies(TacticalPuzzleEnemySpec.newBuilder()
                        .setMonsterTableId(311)
                        .setInitialHpPct(40)
                        .setInitialShieldPct(30)
                        .setSpeedDeltaPct(-20)
                        .setInitialBuffKind(1)
                        .build())
                .addEnemies(TacticalPuzzleEnemySpec.newBuilder()
                        .setMonsterTableId(312)
                        .setInitialHpPct(120)
                        .setInitialShieldPct(0)
                        .setSpeedDeltaPct(20)
                        .setInitialBuffKind(4)
                        .build())
                .build();
        return CreateBattleRequest.newBuilder()
                .setBattleId(95001L)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setBattleConfigId(TacticalPuzzleRules.WORKSHOP_DUNGEON_ID)
                .setSeed(TacticalPuzzleRules.deterministicSeed(88001L, 2))
                .setRankedContext(RankedMatchContext.newBuilder().setUseFreePreset(true).setTeamSize(1).build())
                .addPlayers(BattlePlayerSnapshot.newBuilder()
                        .setPlayerId(HERO_A)
                        .setPlayerName("破局者")
                        .setTeamIndex(0)
                        .setLevel(40)
                        .setMaxHealth(2000)
                        .setMaxMana(1000)
                        .setPhysicalAttack(100)
                        .setBaseAttributes(BaseAttributesComp.newBuilder()
                                .setHealth(2000).setMana(1000).setSpeed(200).build())
                        .build())
                .setTacticalPuzzleContext(TacticalPuzzleBattleContext.newBuilder()
                        .setPuzzleId(88001L)
                        .setSpecRevision(2)
                        .setIsAuthorVerify(true)
                        .setPuzzleTitle("二回合破盾反杀")
                        .setAuthorPlayerId(HERO_A)
                        .setAuthorName("破局者")
                        .setSpec(spec)
                        .build())
                .build();
    }

    @Test
    void setupScalingBuffsRoundLimitAndRejections() {
        MemoryBattleData data = buildData();
        CreateBattleRequest req = validRequest();

        BattleStart started = TurnBattleEngine.start(req, data);
        TurnBattleEngine engine = assertInstanceOf(BattleStart.Started.class, started).engine();
        BattleStateS2C state = engine.buildStateSnapshot();
        assertEquals(2, state.getRoundLimit());
        assertEquals(3, state.getTeamAFormationId());
        assertTrue(state.hasTacticalPuzzleContext());
        assertEquals(88001L, state.getTacticalPuzzleContext().getPuzzleId());
        assertEquals(3, state.getActorsCount());

        BattleActorState hero = null;
        BattleActorState mon1 = null;
        BattleActorState mon2 = null;
        for (BattleActorState a : state.getActorsList()) {
            if (a.getActorId() == HERO_A) hero = a;
            else if (a.getMonsterTableId() == 311) mon1 = a;
            else if (a.getMonsterTableId() == 312) mon2 = a;
        }
        assertNotNull(hero);
        assertNotNull(mon1);
        assertNotNull(mon2);
        assertEquals(1000L, hero.getAttributes().getHealth());
        assertEquals(600L, hero.getAttributes().getMana());
        assertEquals(400L, mon1.getAttributes().getHealth());
        assertEquals(80L, mon1.getAttributes().getSpeed());
        assertEquals(1200L, mon2.getMaxHealth());
        assertEquals(1200L, mon2.getAttributes().getHealth());
        assertEquals(120L, mon2.getAttributes().getSpeed());

        assertTrue(hasBuff(hero.getBuffsList(), TacticalPuzzleRules.SHIELD_BUFF_ID, 400L));
        assertTrue(hasBuff(hero.getBuffsList(), TacticalPuzzleRules.DEBUFF_POISON_BUFF_ID, -1L));
        assertTrue(hasBuff(mon1.getBuffsList(), TacticalPuzzleRules.SHIELD_BUFF_ID, 300L));
        assertTrue(hasBuff(mon1.getBuffsList(), TacticalPuzzleRules.BUFF_DEF_UP_BUFF_ID, -1L));
        assertTrue(hasBuff(mon2.getBuffsList(), TacticalPuzzleRules.BUFF_REFLECT_BUFF_ID, -1L));

        BattleAction defend = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build();
        engine.submitAction(HERO_A, defend);
        engine.resolveCurrentRound();
        assertEquals(eBattleOutcome.BATTLE_OUTCOME_ONGOING, engine.outcome());
        engine.submitAction(HERO_A, defend);
        engine.resolveCurrentRound();
        assertEquals(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, engine.outcome());

        BattleSettlementData settle = engine.buildSettlement(HERO_A);
        assertTrue(settle.hasTacticalPuzzleContext());
        assertEquals(88001L, settle.getTacticalPuzzleContext().getPuzzleId());
        assertEquals(0L, settle.getExpGain());
        assertEquals(0L, settle.getGoldGain());

        CreateBattleRequest badRound = req.toBuilder()
                .setTacticalPuzzleContext(req.getTacticalPuzzleContext().toBuilder()
                        .setSpec(req.getTacticalPuzzleContext().getSpec().toBuilder().setRoundLimit(7)))
                .build();
        assertInstanceOf(BattleStart.Rejected.class, TurnBattleEngine.start(badRound, data));

        CreateBattleRequest noPreset = req.toBuilder()
                .setRankedContext(RankedMatchContext.newBuilder().setUseFreePreset(false))
                .build();
        assertInstanceOf(BattleStart.Rejected.class, TurnBattleEngine.start(noPreset, data));
    }

    private static boolean hasBuff(List<BattleBuffEntry> buffs, int tableId, long expectedShield) {
        for (BattleBuffEntry b : buffs) {
            if (b.getBuffTableId() == tableId && (expectedShield < 0L || b.getRemainingCapacity() == expectedShield)) {
                return true;
            }
        }
        return false;
    }
}
