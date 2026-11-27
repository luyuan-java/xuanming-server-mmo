package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattlePrepState;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CustomDuelRules;
import com.game.proto.RankedMatchContext;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.match.MatchMode;
import com.game.table.ConfigTables;
import com.game.table.RankedPresetTable;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 战前每方 3 Ban（方案 A）权威闭环测试：表候选池、25 秒窗、队内聚合平票裁决、视角脱敏、对称禁用、A41 复制拦截与切磋纯禁招。 */
final class PrepSkillBanBattleEngineTest {

    private static ConfigTables tables;
    private static TableBattleData data;

    @BeforeAll
    static void loadTables() throws Exception {
        Path tablesDir = Path.of("../../xuanming-server-mmo-cpp/generated/tables").toAbsolutePath().normalize();
        if (!java.nio.file.Files.isDirectory(tablesDir)) {
            tablesDir = Path.of("../xuanming-server-mmo-cpp/generated/tables").toAbsolutePath().normalize();
        }
        tables = ConfigTables.load(tablesDir);
        data = new TableBattleData(tables);
    }

    private static BattlePlayerSnapshot makePlayer(long playerId, long petId, int team, long speed,
                                                   RankedPresetTable petPreset, boolean captain, int... skillIds) {
        BattlePetSnapshot.Builder pet = BattlePetSnapshot.newBuilder()
                .setPetId(petId)
                .setOwnerPlayerId(playerId)
                .setPetTableId(petPreset.getPetTableId())
                .setPetName(petPreset.getName())
                .setLevel(30)
                .setMaxHealth(1200)
                .setMaxMana(400)
                .setPhysicalAttack(120)
                .setMagicAttack(120)
                .setDefense(60)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(1200).setMana(400).setSpeed(speed));
        BattlePlayerSnapshot.Builder player = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(playerId)
                .setTeamIndex(team)
                .setPlayerName("P" + playerId)
                .setLevel(30)
                .setActiveSeasonId(1)
                .setIsTeamCaptain(captain)
                .setMaxHealth(5000)
                .setMaxMana(1500)
                .setPhysicalAttack(300)
                .setMagicAttack(300)
                .setDefense(100)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(5000).setMana(1500).setSpeed(speed))
                .addPets(pet);
        for (int sid : skillIds) {
            player.addSkillTableIds(sid).putSkillRanks(sid, 1);
        }
        return player.build();
    }

    @Test
    void prepThreeSkillBanFullFlowPrivacyCaptainTiebreakAndEnforcement() {
        List<Integer> s1Pool = data.banEligibleSkillIds(1);
        assertThat(s1Pool).hasSize(55);
        assertThat(s1Pool).doesNotContain(10002, 10006, 10010, 10015, 10018, 11011, 11012, 11015);
        assertThat(s1Pool).contains(10003, 10008, 10019, 10041, 15001);
        assertThat(s1Pool).doesNotContain(15101);

        RankedPresetTable petA = tables.rankedPreset().find(201).orElseThrow();
        BattlePlayerSnapshot pA1 = makePlayer(6501L, 96001L, 0, 260L, petA, true, 10002, 10003, 10008, 10019);
        BattlePlayerSnapshot pA2 = makePlayer(6502L, 96002L, 0, 250L, petA, false, 10002, 10003);
        BattlePlayerSnapshot pB1 = makePlayer(6503L, 96003L, 1, 240L, petA, true, 10002, 10003, 10041);

        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9901L)
                .setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(777L)
                .setRankedContext(RankedMatchContext.newBuilder()
                        .setSeasonId(1)
                        .setSeasonKey("s1")
                        .setRuleVersion(2)
                        .setTeamSize(2)
                        .setUseFreePreset(false)
                        .setSkillBanSlotsPerTeam(3)
                        .build())
                .addPlayers(pA1)
                .addPlayers(pA2)
                .addPlayers(pB1)
                .build();

        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        assertThat(engine.isInPrepPhase()).isTrue();
        assertThat(engine.prepDurationSeconds()).isEqualTo(BattleConstants.TACTICAL_PREP_WITH_BAN_DURATION_SECONDS);
        assertThat(engine.prepTimeoutMs()).isEqualTo(BattleConstants.TACTICAL_PREP_WITH_BAN_TIMEOUT_MS);

        // 非候选（基础技能 10002）与重复提交必须被拒
        assertThat(engine.updateBattlePrep(6501L, 0, 0L, false, true, List.of(10002)).errorTip())
                .isEqualTo(ActionChecks.INVALID_PARAMETER);
        assertThat(engine.updateBattlePrep(6501L, 0, 0L, false, true, List.of(10003, 10003)).errorTip())
                .isEqualTo(ActionChecks.INVALID_PARAMETER);

        // A队队长提名 [10003, 10008, 10019]；A队队员提名 [10003, 10012, 10016]
        // 汇总：10003(2票) 第一；槽位1平票 10008(队长) 胜 10012(非队长) 第二；10012(槽位1) 胜 10019(槽位2) 第三
        assertThat(engine.updateBattlePrep(6501L, 0, 0L, true, true, List.of(10003, 10008, 10019)).errorTip()).isZero();
        assertThat(engine.updateBattlePrep(6502L, 0, 0L, true, true, List.of(10003, 10012, 10016)).errorTip()).isZero();

        // 严格同队脱敏：B队和观众均不得看到 A 队提名或生效禁招
        BattlePrepState bPrep = engine.prepStateForViewer(6503L, 12345L);
        assertThat(bPrep.getTeamEffectiveBanSkillIdsList()).isEmpty();
        assertThat(bPrep.getTeamStatesList()).allSatisfy(ts -> assertThat(ts.getTeamIndex()).isEqualTo(1));
        BattlePrepState obsPrep = engine.prepStateForViewer(0L, 12345L);
        assertThat(obsPrep.getTeamEffectiveBanSkillIdsList()).isEmpty();
        assertThat(obsPrep.getTeamStatesList()).isEmpty();
        assertThat(obsPrep.getBanCandidateSkillIdsList()).isEmpty();

        // B队提名 [10003, 15001]（与 A 队撞车 10003）并锁定
        assertThat(engine.updateBattlePrep(6503L, 0, 0L, true, true, List.of(10003, 15001)).errorTip()).isZero();
        assertThat(engine.allPrepPlayersReady()).isTrue();
        assertThat(engine.finalizePrepPhase()).isTrue();

        BattleStateS2C state = engine.buildStateSnapshot();
        assertThat(state.getTeamABannedSkillIdsList()).containsExactly(10003, 10008, 10012);
        assertThat(state.getTeamBBannedSkillIdsList()).containsExactly(10003, 15001);
        assertThat(state.getBannedSkillIdsList()).containsExactly(10003, 10008, 10012, 15001);

        // 双方均无法施放被禁技能 10003（返回 kSkillBannedInBattle），但基础技 10002 可用
        BattleAction cast10003 = BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(10003)
                .setTargetId(6503L)
                .build();
        BattleAction cast10002 = BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(10002)
                .setTargetId(6503L)
                .build();
        assertThat(engine.validateAction(6501L, cast10003)).isEqualTo(ActionChecks.SKILL_BANNED_IN_BATTLE);
        assertThat(engine.validateAction(6503L, cast10003.toBuilder().setTargetId(6501L).build()))
                .isEqualTo(ActionChecks.SKILL_BANNED_IN_BATTLE);
        assertThat(engine.validateAction(6501L, cast10002)).isEqualTo(ActionChecks.SUCCESS);

        // A41 玄镜映法(10041) 对 6502(仅学了 10002 与被禁的 10003) 施放时绝不复制到被禁技能 10003
        BattleAction castCopy = BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(10041)
                .setTargetId(6502L)
                .build();
        assertThat(engine.validateAction(6503L, castCopy)).isEqualTo(ActionChecks.SUCCESS);
        engine.submitAction(6501L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        engine.submitAction(6502L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        engine.submitAction(6503L, castCopy);
        TurnResultS2C r1 = engine.resolveCurrentRound();
        assertThat(r1.getEventsList()).noneMatch(e -> e.getSkillTableId() == 10003);

        // 自定义切磋：免费模板与 3 Ban 互斥必须被拒；自养纯 3 Ban 可正常开局且仅开禁招不开换宠/换印
        CreateBattleRequest badDuel = CreateBattleRequest.newBuilder()
                .setBattleId(9902L)
                .setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_CHALLENGE_VALUE)
                .setSeed(888L)
                .setCustomDuelRules(CustomDuelRules.newBuilder()
                        .setUseFreePreset(true).setSkillBanSlotsPerTeam(3).build())
                .addPlayers(pA1)
                .addPlayers(pB1)
                .build();
        assertThat(TurnBattleEngine.start(badDuel, data)).isInstanceOf(BattleStart.Rejected.class);

        CreateBattleRequest okDuel = badDuel.toBuilder()
                .setBattleId(9903L)
                .setCustomDuelRules(CustomDuelRules.newBuilder()
                        .setUseFreePreset(false).setSkillBanSlotsPerTeam(3).build())
                .build();
        TurnBattleEngine duelEngine = ((BattleStart.Started) TurnBattleEngine.start(okDuel, data)).engine();
        assertThat(duelEngine.isInPrepPhase()).isTrue();
        assertThat(duelEngine.prepDurationSeconds()).isEqualTo(BattleConstants.TACTICAL_PREP_WITH_BAN_DURATION_SECONDS);
        BattlePrepState duelPrep = duelEngine.prepStateForViewer(6501L, 99999L);
        assertThat(duelPrep.getSkillBanSlotsPerTeam()).isEqualTo(3);
        assertThat(duelPrep.getSkillProtectSlotsPerTeam()).isEqualTo(1);
        assertThat(duelPrep.getSelfAvailableSealsList()).isEmpty();
        assertThat(duelPrep.getSelfAvailablePetsList()).isEmpty();
    }

    @Test
    void prepSkillBanV2ProtectCounterAndTeammateEquippedSkills() {
        BattlePlayerSnapshot pA1 = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(7501L)
                .setTeamIndex(0)
                .setIsTeamCaptain(true)
                .setActiveSeasonId(1)
                .setPlayerName("队长A1")
                .setLevel(30)
                .setMaxHealth(5000)
                .setMaxMana(2000)
                .setPhysicalAttack(500)
                .setDefense(100)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(5000).setMana(2000).setSpeed(200))
                .addSkillTableIds(10002)
                .addSkillTableIds(10003)
                .addSkillTableIds(10040)
                .putSkillRanks(10002, 1)
                .putSkillRanks(10003, 1)
                .putSkillRanks(10040, 1)
                .build();
        BattlePlayerSnapshot pA2 = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(7502L)
                .setTeamIndex(0)
                .setIsTeamCaptain(false)
                .setActiveSeasonId(1)
                .setPlayerName("队友A2")
                .setLevel(30)
                .setMaxHealth(5000)
                .setMaxMana(2000)
                .setPhysicalAttack(500)
                .setDefense(100)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(5000).setMana(2000).setSpeed(190))
                .addSkillTableIds(10002)
                .addSkillTableIds(10020)
                .putSkillRanks(10002, 1)
                .putSkillRanks(10020, 1)
                .build();
        BattlePlayerSnapshot pB1 = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(7503L)
                .setTeamIndex(1)
                .setIsTeamCaptain(true)
                .setActiveSeasonId(1)
                .setPlayerName("敌方B1")
                .setLevel(30)
                .setMaxHealth(5000)
                .setMaxMana(2000)
                .setPhysicalAttack(500)
                .setDefense(100)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(5000).setMana(2000).setSpeed(180))
                .addSkillTableIds(10002)
                .addSkillTableIds(10003)
                .addSkillTableIds(10023)
                .addSkillTableIds(10040)
                .putSkillRanks(10002, 1)
                .putSkillRanks(10003, 1)
                .putSkillRanks(10023, 1)
                .putSkillRanks(10040, 1)
                .build();

        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9911L)
                .setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(9911L)
                .setRankedContext(RankedMatchContext.newBuilder()
                        .setSeasonId(1).setSeasonKey("s1").setRankGeneration(1)
                        .setRuleVersion(2).setTeamSize(2).setSkillBanSlotsPerTeam(3).build())
                .addPlayers(pA1)
                .addPlayers(pA2)
                .addPlayers(pB1)
                .build();

        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        BattlePrepState prepA1 = engine.prepStateForViewer(7501L, 99999L);
        assertThat(prepA1.getSkillProtectSlotsPerTeam()).isEqualTo(1);
        // 7501 仅看到队友 7502 携带的 10020（不含自己独有的 10003/10040 或敌方的 10023，也不含豁免技 10002）
        assertThat(prepA1.getTeammateEquippedSkillIdsList()).containsExactly(10020);
        // 观众清空护招与队友技能
        BattlePrepState obsPrep = engine.prepStateForViewer(0L, 99999L);
        assertThat(obsPrep.getSkillProtectSlotsPerTeam()).isEqualTo(1);
        assertThat(obsPrep.getTeamEffectiveProtectedSkillId()).isZero();
        assertThat(obsPrep.getTeammateEquippedSkillIdsList()).isEmpty();

        // 校验：非白名单护招拒、与本人禁招同招拒
        assertThat(engine.updateBattlePrep(7501L, 0, 0L, false, false, List.of(), true, 10002).errorTip())
                .isEqualTo(ActionChecks.INVALID_PARAMETER);
        assertThat(engine.updateBattlePrep(7501L, 0, 0L, false, true, List.of(10003, 10023), false, 0).errorTip())
                .isZero();
        assertThat(engine.updateBattlePrep(7501L, 0, 0L, false, false, List.of(), true, 10003).errorTip())
                .isEqualTo(ActionChecks.INVALID_PARAMETER);

        // 队长 7501 护招 10040，非队长 7502 护招 10020（1:1 队长胜出 -> 10040）
        assertThat(engine.updateBattlePrep(7501L, 0, 0L, true, false, List.of(), true, 10040).errorTip()).isZero();
        var rA2 = engine.updateBattlePrep(7502L, 0, 0L, true, false, List.of(), true, 10020);
        assertThat(rA2.errorTip()).isZero();
        assertThat(rA2.prepState().getTeamEffectiveProtectedSkillId()).isEqualTo(10040);

        // B1 禁 [10023, 10040]（撞车 10023 + 禁了 A 队护招 10040）、护招 10003（反制 A 队禁招 10003）
        assertThat(engine.updateBattlePrep(7503L, 0, 0L, true, true, List.of(10023, 10040), true, 10003).errorTip())
                .isZero();
        assertThat(engine.finalizePrepPhase()).isTrue();

        BattleStateS2C state = engine.buildStateSnapshot();
        assertThat(state.getTeamAProtectedSkillId()).isEqualTo(10040);
        assertThat(state.getTeamBProtectedSkillId()).isEqualTo(10003);
        assertThat(state.getCounteredBanSkillIdsList()).containsExactly(10003, 10040);
        assertThat(state.getBannedSkillIdsList()).containsExactly(10023);

        // 破禁化解的 10003/10040 恢复可放；双方撞车的 10023 仍被禁
        BattleAction cast10003 = BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(10003).setTargetId(7503L).build();
        BattleAction cast10023 = BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(10023).setTargetId(7501L).build();
        assertThat(engine.validateAction(7501L, cast10003)).isEqualTo(ActionChecks.SUCCESS);
        assertThat(engine.validateAction(7503L, cast10023)).isEqualTo(ActionChecks.SKILL_BANNED_IN_BATTLE);
    }

    @Test
    void customDuelRulesEnforceRoundLimitBanControlPolaritySnapshotAndDrawOnCap() {
        var controlSkill = tables.skill().all().stream()
                .filter(s -> s.getBattleModel() == 1 && s.getAbilityKind() == 1 && (s.getCasterMask() & 1) != 0
                        && s.getSeasonId() == 0 && s.getTargetTeam() == 1
                        && s.getBanPoolCategory() >= 1 && s.getBanPoolCategory() <= 4)
                .filter(s -> s.getEffectList().stream().anyMatch(bid -> tables.buff().find(bid)
                        .map(b -> (b.getBattleOp() == BattleEffect.CONTROL && b.getStateKind() >= 1 && b.getStateKind() <= 6)
                                || b.getBuffType() == BattleConstants.BUFF_TYPE_STUN
                                || b.getBuffType() == BattleConstants.BUFF_TYPE_FREEZE
                                || b.getBuffType() == BattleConstants.BUFF_TYPE_SILENCE)
                        .orElse(false)))
                .findFirst().orElseThrow();
        int controlSkillId = controlSkill.getId();

        BattlePlayerSnapshot pA = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(8601L).setTeamIndex(0).setIsTeamCaptain(true).setActiveSeasonId(1)
                .setPlayerName("道友甲").setLevel(30)
                .setMaxHealth(50000).setMaxMana(2000).setPhysicalAttack(100).setDefense(500)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(50000).setMana(2000).setSpeed(200))
                .addSkillTableIds(10002)
                .addSkillTableIds(controlSkillId)
                .putSkillRanks(10002, 1)
                .putSkillRanks(controlSkillId, 1)
                .build();
        BattlePlayerSnapshot pB = pA.toBuilder().setPlayerId(8602L).setTeamIndex(1).setPlayerName("道友乙").build();

        // 1. 非法回合上限被引擎拒绝
        CreateBattleRequest badRounds = CreateBattleRequest.newBuilder()
                .setBattleId(9910L).setBattleConfigId(5).setMatchMode(MatchMode.MATCH_MODE_PVP_CHALLENGE_VALUE)
                .setSeed(777L)
                .setCustomDuelRules(CustomDuelRules.newBuilder().setRoundLimitOverride(99))
                .addPlayers(pA).addPlayers(pB).build();
        assertThat(TurnBattleEngine.start(badRounds, data)).isInstanceOf(BattleStart.Rejected.class);

        // 2. 合法规则：8 回合上限 + 全场禁控 + 物法易势轮换 + 3 Ban
        CustomDuelRules rules = CustomDuelRules.newBuilder()
                .setRoundLimitOverride(8)
                .setBanControlSkills(true)
                .setEnablePolarityRotation(true)
                .setSkillBanSlotsPerTeam(3)
                .build();
        CreateBattleRequest req = badRounds.toBuilder().setBattleId(9911L).setCustomDuelRules(rules).build();
        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();

        // 战前禁招候选池排除所有控制技能（含 controlSkillId）
        BattlePrepState prep = engine.prepStateForViewer(8601L, 99999L);
        assertThat(prep.getBanCandidateSkillIdsList()).doesNotContain(controlSkillId);
        assertThat(engine.finalizePrepPhase()).isTrue();

        // 快照回显 custom_duel_rules 与激活的物法易势极性
        BattleStateS2C snapshot = engine.buildStateSnapshot();
        assertThat(snapshot.getCustomDuelRules()).isEqualTo(rules);
        assertThat(snapshot.getActivePolarity()).isIn(1, 2);

        // 实战中双方（team 0 与 team 1）均禁止施放控制技能，非控制技能 10002 放行
        BattleAction castCtrlA = BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL).setSkillTableId(controlSkillId).setTargetId(8602L).build();
        BattleAction castCtrlB = BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL).setSkillTableId(controlSkillId).setTargetId(8601L).build();
        BattleAction castNormalA = BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL).setSkillTableId(10002).setTargetId(8602L).build();
        assertThat(engine.validateAction(8601L, castCtrlA)).isEqualTo(ActionChecks.SKILL_CANNOT_CAST_IN_STATE);
        assertThat(engine.validateAction(8602L, castCtrlB)).isEqualTo(ActionChecks.SKILL_CANNOT_CAST_IN_STATE);
        assertThat(engine.validateAction(8601L, castNormalA)).isEqualTo(ActionChecks.SUCCESS);

        // 打满 8 回合双方均存活：按自定义切磋上限在第 8 回合权威判平（BATTLE_OUTCOME_DRAW）
        BattleAction defend = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build();
        for (int r = 1; r <= 8; r++) {
            engine.submitAction(8601L, defend);
            engine.submitAction(8602L, defend);
            engine.resolveCurrentRound();
            if (r < 8) {
                assertThat(engine.outcome()).isEqualTo(com.game.proto.eBattleOutcome.BATTLE_OUTCOME_ONGOING);
            }
        }
        assertThat(engine.outcome()).isEqualTo(com.game.proto.eBattleOutcome.BATTLE_OUTCOME_DRAW);
    }
}
