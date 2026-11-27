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
                                .setSeasonId(1).setSealId(101).setEffectId(7).setBonusBps(1200).setPerRoundLimit(1).setBattleLimit(3)))
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

    @Test
    void 卷四三境明雷风眼引风与阳钟阴鼓和涨退潮机制事实正确冻结() {
        // 1) 113 风眼引风：R1 预告 TELEGRAPH_KIND_WILD_WIND_GUST(10)，R2 防御化解无折损，击杀后 COND_WIND_DEFEND 成立
        CreateBattleRequest windReq = CreateBattleRequest.newBuilder()
                .setBattleId(88101L)
                .setBattleConfigId(113)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setSeed(101L)
                .addPlayers(strongPlayer(5001L, "风行者", 0, 1).toBuilder()
                        .setPhysicalAttack(10_000L).setMagicAttack(10_000L).build())
                .setTrialContext(TrialBattleContext.newBuilder()
                        .setJournalEntryId(401)
                        .setTrialRuleVersion(1)
                        .setSeasonId(0))
                .build();
        TurnBattleEngine windEngine = ((BattleStart.Started) TurnBattleEngine.start(windReq, DATA)).engine();
        assertThat(windEngine.buildStateSnapshot().getTelegraphsList())
                .anyMatch(t -> t.getIntentKind() == BattleConstants.TELEGRAPH_KIND_WILD_WIND_GUST
                        && t.getExecuteRound() == 2);
        // R1/R2 均防御以稳定化解 R2 风袭
        windEngine.submitAction(5001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        windEngine.resolveCurrentRound();
        windEngine.submitAction(5001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        windEngine.resolveCurrentRound();
        for (int i = 0; i < 12 && windEngine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; i++) {
            windEngine.resolveCurrentRound();
        }
        assertThat(windEngine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        BattleSettlementData windSettlement = windEngine.buildSettlement(5001L);
        assertThat(windSettlement.getTrialFacts().getBossWindHitUnprotectedDeath()).isFalse();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_WIND_DEFEND, 0, windSettlement)).isTrue();

        // 2) 114 水镜钟鸣：R1 阳钟物理直伤 + R3 阴鼓法术直伤 -> COND_BELL_HARMONY 成立
        var magicSkill = CONFIG.skill().all().stream()
                .filter(s -> s.getBattleModel() == 1 && s.getAbilityKind() == 1 && (s.getCasterMask() & 1) != 0
                        && s.getSeasonId() == 0 && s.getTargetTeam() == 1
                        && s.getDamageType() == com.game.common.combat.CombatDamageRules.MAGIC_DAMAGE
                        && s.getEffectList().stream().anyMatch(bid -> CONFIG.buff().find(bid)
                                .map(b -> b.getBattleOp() == BattleEffect.DAMAGE).orElse(false)))
                .findFirst().orElseThrow();
        BattlePlayerSnapshot bellPlayer = strongPlayer(5002L, "听钟人", 0, 1).toBuilder()
                .setPhysicalAttack(1_500L)
                .setMagicAttack(60_000L)
                .clearPets()
                .addSkillTableIds(magicSkill.getId())
                .putSkillRanks(magicSkill.getId(), 1)
                .build();
        CreateBattleRequest bellReq = CreateBattleRequest.newBuilder()
                .setBattleId(88102L)
                .setBattleConfigId(114)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setSeed(102L)
                .addPlayers(bellPlayer)
                .setTrialContext(TrialBattleContext.newBuilder()
                        .setJournalEntryId(402)
                        .setTrialRuleVersion(1)
                        .setSeasonId(0))
                .build();
        TurnBattleEngine bellEngine = ((BattleStart.Started) TurnBattleEngine.start(bellReq, DATA)).engine();
        long bellBossId = bellEngine.buildStateSnapshot().getActorsList().stream()
                .filter(a -> a.getTeamIndex() == 1 && a.getIsBoss()).findFirst().orElseThrow().getActorId();
        // R1（阳钟）：普攻物理直伤
        bellEngine.submitAction(5002L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(bellBossId).build());
        bellEngine.resolveCurrentRound();
        // R2（阳钟）：防御拖入 R3 阴鼓窗口
        bellEngine.submitAction(5002L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        bellEngine.resolveCurrentRound();
        // R3（阴鼓）：施放法术直伤并收尾
        assertThat(BattleConstants.isWildBellYangRound(3)).isFalse();
        bellEngine.submitAction(5002L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(magicSkill.getId()).setTargetId(bellBossId).build());
        bellEngine.resolveCurrentRound();
        for (int i = 0; i < 12 && bellEngine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; i++) {
            bellEngine.submitAction(5002L, BattleAction.newBuilder()
                    .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                    .setSkillTableId(magicSkill.getId()).setTargetId(bellBossId).build());
            bellEngine.resolveCurrentRound();
        }
        assertThat(bellEngine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        BattleSettlementData bellSettlement = bellEngine.buildSettlement(5002L);
        assertThat(bellSettlement.getTrialFacts().getDealtYangBellPhysicalDamage()).isTrue();
        assertThat(bellSettlement.getTrialFacts().getDealtYinDrumMagicDamage()).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_BELL_HARMONY, 0, bellSettlement)).isTrue();

        // 3) 115 涨退潮：前 2 回合防御度过涨潮，R3 退潮窗口一举击杀 -> finished_in_ebb_tide = true
        BattlePlayerSnapshot tidePlayer = strongPlayer(5003L, "踏浪人", 0, 1).toBuilder()
                .clearPets().setPhysicalAttack(200_000L).build();
        CreateBattleRequest tideReq = CreateBattleRequest.newBuilder()
                .setBattleId(88103L)
                .setBattleConfigId(115)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setSeed(103L)
                .addPlayers(tidePlayer)
                .setTrialContext(TrialBattleContext.newBuilder()
                        .setJournalEntryId(403)
                        .setTrialRuleVersion(1)
                        .setSeasonId(0))
                .build();
        TurnBattleEngine tideEngine = ((BattleStart.Started) TurnBattleEngine.start(tideReq, DATA)).engine();
        tideEngine.submitAction(5003L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        tideEngine.resolveCurrentRound();
        tideEngine.submitAction(5003L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        tideEngine.resolveCurrentRound();
        // R3/R4 均为退潮窗口，强攻清场
        for (int i = 0; i < 2 && tideEngine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; i++) {
            tideEngine.resolveCurrentRound();
        }
        assertThat(tideEngine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        BattleSettlementData tideSettlement = tideEngine.buildSettlement(5003L);
        assertThat(tideSettlement.getTrialFacts().getFinishedInEbbTide()).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_TIDE_FINISH, 0, tideSettlement)).isTrue();
    }

    @Test
    void 试炼誓约四项约束全部落地且非法掩码与非首领局被拒() {
        var controlSkill = CONFIG.skill().all().stream()
                .filter(s -> s.getBattleModel() == 1 && s.getAbilityKind() == 1 && (s.getCasterMask() & 1) != 0
                        && s.getSeasonId() == 0 && s.getTargetTeam() == 1)
                .filter(s -> s.getEffectList().stream().anyMatch(bid -> CONFIG.buff().find(bid)
                        .map(b -> (b.getBattleOp() == BattleEffect.CONTROL && b.getStateKind() >= 1 && b.getStateKind() <= 6)
                                || b.getBuffType() == BattleConstants.BUFF_TYPE_STUN
                                || b.getBuffType() == BattleConstants.BUFF_TYPE_FREEZE
                                || b.getBuffType() == BattleConstants.BUFF_TYPE_SILENCE)
                        .orElse(false)))
                .findFirst().orElseThrow();

        int baseRounds = CONFIG.dungeon().find(101).orElseThrow().getRoundLimit();
        BattlePlayerSnapshot p = strongPlayer(6001L, "孤剑誓约", 0, 1).toBuilder()
                .addSkillTableIds(controlSkill.getId())
                .putSkillRanks(controlSkill.getId(), 1)
                .build();
        CreateBattleRequest vowReq = CreateBattleRequest.newBuilder()
                .setBattleId(88201L)
                .setBattleConfigId(101)
                .setMatchMode(BattleConstants.MATCH_MODE_PVE_SOLO)
                .setSeed(201L)
                .addPlayers(p)
                .setTrialContext(TrialBattleContext.newBuilder()
                        .setJournalEntryId(101)
                        .setTrialRuleVersion(1)
                        .setSeasonId(0)
                        .setChallengeVowMask(TrialJournalRules.VOW_MASK_ALL))
                .build();
        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(vowReq, DATA)).engine();
        var state = engine.buildStateSnapshot();
        assertThat(state.getTrialVowMask()).isEqualTo(TrialJournalRules.VOW_MASK_ALL);
        assertThat(state.getRoundLimit()).isEqualTo(TrialJournalRules.tightenRoundLimit(baseRounds));
        // 无宠孤剑：不生成玩家灵宠
        assertThat(state.getActorsList()).noneMatch(a -> a.getActorType() == com.game.proto.eBattleActorType.BATTLE_ACTOR_TYPE_PET);
        // 禁控硬破：控制技能被拒收
        long bossId = state.getActorsList().stream().filter(a -> a.getTeamIndex() == 1).findFirst().orElseThrow().getActorId();
        int tip = engine.validateAction(6001L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(controlSkill.getId())
                .setTargetId(bossId).build());
        assertThat(tip).isEqualTo(ActionChecks.SKILL_CANNOT_CAST_IN_STATE);

        for (int i = 0; i < 6 && engine.outcome() == eBattleOutcome.BATTLE_OUTCOME_ONGOING; i++) {
            engine.resolveCurrentRound();
        }
        assertThat(engine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        BattleTrialJournalFacts facts = engine.buildSettlement(6001L).getTrialFacts();
        assertThat(facts.getChallengeVowMask()).isEqualTo(TrialJournalRules.VOW_MASK_ALL);
        assertThat(facts.getUsedPetTableId()).isZero();
        // 单宝轻装：仅保留首位法宝（effectId=5）
        assertThat(facts.getUsedArtifactEffectIdsList()).containsExactly(5);

        // 非法高位掩码与护卫救援局拒收
        CreateBattleRequest badMask = vowReq.toBuilder().setBattleId(88202L)
                .setTrialContext(vowReq.getTrialContext().toBuilder().setChallengeVowMask(0x10)).build();
        assertThat(TurnBattleEngine.start(badMask, DATA)).isInstanceOf(BattleStart.Rejected.class);

        CreateBattleRequest rescueVow = vowReq.toBuilder().setBattleId(88203L).setBattleConfigId(109).build();
        assertThat(TurnBattleEngine.start(rescueVow, DATA)).isInstanceOf(BattleStart.Rejected.class);
    }

    @Test
    void 试炼志V4_施放当季免费赛季技与触发R1至R8技印共鸣写入权威事实并按掩码判定() {
        // S2 玄脉之季：玄脉灵幕(15103) + 玄脉护印(203, effect=5, R6 首盾回蓝 param=250)
        BattlePlayerSnapshot pA = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(3001L).setPlayerName("玄脉试剑").setTeamIndex(0)
                .setActiveSeasonId(2)
                .setLevel(30).setClassId(1)
                .setMaxHealth(20_000L).setMaxMana(2_000L)
                .setPhysicalAttack(1_000L).setMagicAttack(1_000L).setDefense(200L)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(20_000L).setMana(1_000L).setSpeed(500L))
                .addSkillTableIds(15103).putSkillRanks(15103, 1)
                .setEquipmentEffects(EquipmentEffects.newBuilder()
                        .setSeasonSeal(SeasonSealEffect.newBuilder()
                                .setSeasonId(2).setSealId(203).setEffectId(5)
                                .setBonusBps(1200).setPerRoundLimit(1).setBattleLimit(3)
                                .setResonanceSkillId(15103).setResonanceKind(6).setResonanceParam(250)))
                .build();
        BattlePlayerSnapshot pB = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(3002L).setPlayerName("对手").setTeamIndex(1)
                .setLevel(30).setClassId(1)
                .setMaxHealth(20_000L).setMaxMana(2_000L)
                .setPhysicalAttack(100L).setMagicAttack(100L).setDefense(200L)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(20_000L).setMana(2_000L).setSpeed(100L))
                .build();
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(88401L).setBattleConfigId(5)
                .setMatchMode(3).setSeed(29L)
                .addPlayers(pA).addPlayers(pB)
                .setTrialContext(TrialBattleContext.newBuilder()
                        .setJournalEntryId(3023).setTrialRuleVersion(1).setSeasonId(2))
                .build();

        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, DATA)).engine();
        engine.submitAction(3001L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setTargetId(3001L).setSkillTableId(15103).build());
        engine.submitAction(3002L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        engine.resolveCurrentRound();

        BattleSettlementData settle = engine.buildSettlement(3001L).toBuilder()
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).build();
        assertThat(settle.hasTrialFacts()).isTrue();
        BattleTrialJournalFacts facts = settle.getTrialFacts();
        assertThat(facts.getSelfCastSeasonSkill()).isTrue();
        assertThat(facts.getSelfTriggeredSeasonResonance()).isTrue();
        assertThat(facts.getSelfSeasonResonanceMask()).isEqualTo(1 << 5);
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_SEASON_SKILL_CAST, 0, settle)).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_SEASON_RESONANCE, 0, settle)).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_SEASON_RESONANCE, 51, settle)).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_SEASON_RESONANCE, 21, settle)).isFalse();
    }
}
