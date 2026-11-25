package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleTrialJournalFacts;
import com.game.proto.CreateBattleRequest;
import com.game.proto.EquipmentArtifactSource;
import com.game.proto.EquipmentEffects;
import com.game.proto.SeasonSealEffect;
import com.game.proto.TrialBattleContext;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleOutcome;
import com.game.table.ConfigTables;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** 五行试炼志 V2 局内战法事实冻结与纯规则评估测试。 */
class TrialJournalBattleEngineTest {

    private static final ConfigTables CONFIG = ConfigTables.load(Path.of("../config-data/tables"));
    private static final BattleData DATA = new TableBattleData(CONFIG);

    private static BattlePlayerSnapshot strongPlayer(long id, String name, int team, int petTableId) {
        return BattlePlayerSnapshot.newBuilder()
                .setPlayerId(id)
                .setPlayerName(name)
                .setTeamIndex(team)
                .setLevel(60)
                .setClassId(1)
                .setMaxHealth(500_000L)
                .setMaxMana(10_000L)
                .setPhysicalAttack(60_000L)
                .setMagicAttack(60_000L)
                .setDefense(2_000L)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(500_000L)
                        .setMana(10_000L)
                        .setStrength(2_000L)
                        .setSpeed(500L))
                .addPets(BattlePetSnapshot.newBuilder()
                        .setPetId(id * 100 + 1)
                        .setPetTableId(petTableId)
                        .setCombatPetTableId(petTableId)
                        .setLevel(60)
                        .setMaxHealth(100_000L)
                        .setMaxMana(2_000L)
                        .setPhysicalAttack(10_000L)
                        .setBaseAttributes(BaseAttributesComp.newBuilder()
                                .setHealth(100_000L)
                                .setMana(2_000L)
                                .setSpeed(400L)))
                .setEquipmentEffects(EquipmentEffects.newBuilder()
                        .addArtifacts(EquipmentArtifactSource.newBuilder()
                                .setItemId(9002L).setEffectId(5).setBonusBps(1000).setPerRoundLimit(1).setBattleLimit(3))
                        .addArtifacts(EquipmentArtifactSource.newBuilder()
                                .setItemId(9001L).setEffectId(1).setBonusBps(1500).setPerRoundLimit(1).setBattleLimit(3))
                        .setSeasonSeal(SeasonSealEffect.newBuilder()
                                .setSeasonId(1).setSealId(101).setEffectId(8).setBonusBps(1200).setPerRoundLimit(1).setBattleLimit(3)))
                .build();
    }

    @Test
    void 试炼首领局冻结权威战法事实与阵容快照并正确评估战法印() {
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(88001L)
                .setBattleConfigId(101) // 普通·回春首领试炼
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_TEAM)
                .setSeed(77L)
                .addPlayers(strongPlayer(2002L, "队友乙", 0, 2))
                .addPlayers(strongPlayer(2001L, "队长甲", 0, 1))
                .setTrialContext(TrialBattleContext.newBuilder()
                        .setJournalEntryId(101)
                        .setTrialRuleVersion(1)
                        .setSeasonId(0)
                        .setGuardMonsterTableId(0))
                .build();

        BattleStart started = TurnBattleEngine.start(req, DATA);
        assertThat(started).isInstanceOf(BattleStart.Started.class);
        TurnBattleEngine engine = ((BattleStart.Started) started).engine();

        // 连续回合推进直至击杀首领与护卫（输出极高，1-2 回合清场，回春护卫零治疗）
        for (int i = 0; i < 6 && engine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; i++) {
            engine.resolveCurrentRound();
        }
        assertThat(engine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);

        BattleSettlementData settlement = engine.buildSettlement(2001L);
        assertThat(settlement.hasTrialFacts()).isTrue();
        BattleTrialJournalFacts facts = settlement.getTrialFacts();
        assertThat(facts.getJournalEntryId()).isEqualTo(101);
        assertThat(facts.getTrialRuleVersion()).isEqualTo(1);
        assertThat(facts.getInitialHumanCount()).isEqualTo(2);
        assertThat(facts.getTeammateNamesList()).containsExactly("队友乙");
        assertThat(facts.getSelfOrPetEverDied()).isFalse();
        assertThat(facts.getBossGuardHealTotal()).isZero();
        assertThat(facts.getTeamDealtPhysicalDirectDamage()).isTrue();
        assertThat(facts.getUsedPetTableId()).isEqualTo(1);
        assertThat(facts.getUsedArtifactEffectIdsList()).containsExactly(1, 5);
        assertThat(facts.getUsedSeasonSealId()).isEqualTo(101);

        assertThat(TrialJournalRules.isValidTrialSettlement(settlement)).isTrue();
        assertThat(TrialJournalRules.isCoopTrial(facts)).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_CLEAR, 0, settlement)).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_SPEED_CLEAR, 7, settlement)).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_FLAWLESS_SELF_AND_PET, 0, settlement)).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_NO_BOSS_GUARD_HEAL, 0, settlement)).isTrue();
    }

    @Test
    void 护卫救援胜局冻结guardSurvived与人宠无倒地判定() {
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(88002L)
                .setBattleConfigId(109) // 护卫救援
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setSeed(99L)
                .addPlayers(strongPlayer(3001L, "单骑", 0, 1))
                .setTrialContext(TrialBattleContext.newBuilder()
                        .setJournalEntryId(301)
                        .setTrialRuleVersion(1)
                        .setSeasonId(1))
                .build();

        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, DATA)).engine();
        for (int i = 0; i < 6 && engine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; i++) {
            engine.resolveCurrentRound();
        }
        assertThat(engine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        BattleSettlementData settlement = engine.buildSettlement(3001L);
        assertThat(TrialJournalRules.isSoloTrial(settlement.getTrialFacts())).isTrue();
        assertThat(settlement.getTrialFacts().getGuardSurvived()).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_GUARD_RESCUED, 0, settlement)).isTrue();
    }

    @Test
    void 非试炼局不写入trialFacts() {
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(88003L)
                .setBattleConfigId(1)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setSeed(11L)
                .addPlayers(strongPlayer(4001L, "普通", 0, 1))
                .build();
        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, DATA)).engine();
        engine.submitAction(4001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).build());
        engine.resolveCurrentRound();
        assertThat(engine.buildSettlement(4001L).hasTrialFacts()).isFalse();
    }
}
