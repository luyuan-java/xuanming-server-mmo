package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTelegraphEntry;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TrialBattleContext;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleEventType;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.table.ConfigTables;
import com.game.table.SkillTable;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 首领试炼 V2 全机制 Java 引擎对账测试（聚火溅射、化劲破绽与 V1 防线、断疗反噬/气馁、护势联防崩势、双卫同阵、策应卫与确定退出）。 */
class BossTrialV2BattleEngineTest {

    private static ConfigTables tables;
    private static TableBattleData data;
    private static int multiTargetPhysSkillId;
    private static int multiTargetMagicSkillId;
    private static int dispelBuffSkillId;
    private static int antiHealSkillId;

    @BeforeAll
    static void loadTables() {
        tables = ConfigTables.load(Path.of("../config-data/tables"));
        data = new TableBattleData(tables);

        for (SkillTable s : tables.skill().all()) {
            if (s.getBattleModel() != 1 || s.getAbilityKind() != 1 || (s.getCasterMask() & 1) == 0) {
                continue;
            }
            if (s.getTargetTeam() == 1 && s.getMaxTargets() > 1) {
                boolean hasDmg = false;
                boolean hasDispelBuff = false;
                boolean hasAntiHeal = false;
                for (int effId : s.getEffectList()) {
                    var b = tables.buff().find(effId);
                    if (b.isEmpty()) continue;
                    if (b.get().getBattleOp() == BattleEffect.DAMAGE) hasDmg = true;
                    if (b.get().getBattleOp() == BattleEffect.DISPEL_BUFF) hasDispelBuff = true;
                    if (b.get().getBattleOp() == BattleEffect.MODIFIER
                            && b.get().getModifierStat() == BattleEffect.IN_HEAL
                            && b.get().getRatio() < 0) hasAntiHeal = true;
                }
                if (hasDmg && s.getDamageType() == 1 && multiTargetPhysSkillId == 0) {
                    multiTargetPhysSkillId = s.getId();
                }
                if (hasDmg && s.getDamageType() == 0 && multiTargetMagicSkillId == 0) {
                    multiTargetMagicSkillId = s.getId();
                }
                if (hasDispelBuff && dispelBuffSkillId == 0) {
                    dispelBuffSkillId = s.getId();
                }
                if (hasAntiHeal && antiHealSkillId == 0) {
                    antiHealSkillId = s.getId();
                }
            }
        }
        assertThat(multiTargetPhysSkillId).isPositive();
        assertThat(multiTargetMagicSkillId).isPositive();
        assertThat(dispelBuffSkillId).isPositive();
        assertThat(antiHealSkillId).isPositive();
    }

    private static BattlePlayerSnapshot player(long id, long hp, long speed, long physAtk, long magicAtk, int... skills) {
        BattlePlayerSnapshot.Builder b = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(id)
                .setPlayerName("P" + id)
                .setTeamIndex(0)
                .setLevel(60)
                .setClassId(1)
                .setMaxHealth(hp)
                .setMaxMana(10_000L)
                .setPhysicalAttack(physAtk)
                .setMagicAttack(magicAtk)
                .setDefense(200L)
                .setBaseAttributes(BaseAttributesComp.newBuilder()
                        .setHealth(hp).setMana(10_000L).setSpeed(speed).setStrength(50).setArmor(20).build());
        for (int sid : skills) {
            b.addSkillTableIds(sid).putSkillRanks(sid, 1);
        }
        return b.build();
    }

    private static long findMonsterId(BattleStateS2C state, int tableId, boolean isBoss) {
        return state.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER
                        && (isBoss ? a.getIsBoss() : a.getMonsterTableId() == tableId))
                .findFirst().orElseThrow().getActorId();
    }

    @Test
    void 聚火100与溅射60_防御化劲触发破绽35与V1目标倒地绝不送破绽() {
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9801L)
                .setBattleConfigId(101)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setSeed(1001L)
                .addPlayers(player(1001L, 80_000L, 900L, 400L, 400L, multiTargetPhysSkillId))
                .setTrialContext(TrialBattleContext.newBuilder().setJournalEntryId(101).setTrialRuleVersion(2))
                .build();
        TurnBattleEngine eng = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        BattleStateS2C s1 = eng.buildStateSnapshot();
        assertThat(s1.getBossTrialKind()).isEqualTo(BattleConstants.BOSS_TRIAL_KIND_BREAK_REJUV);
        assertThat(s1.getBossPhase()).isEqualTo(1);
        assertThat(eng.isBossTrialBattle()).isTrue();

        long bossId = findMonsterId(s1, 101, true);
        long guardId = findMonsterId(s1, 102, false);

        // R1 使用多目标物理技能主点首领：首领无溅射减伤，副目标护卫带 is_splash_reduced=true
        eng.submitAction(1001L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(multiTargetPhysSkillId)
                .setTargetId(bossId).build());
        TurnResultS2C r1 = eng.resolveCurrentRound();
        BattleEventItem bossHit = r1.getEventsList().stream()
                .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE && e.getTargetId() == bossId)
                .findFirst().orElseThrow();
        BattleEventItem guardHit = r1.getEventsList().stream()
                .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE && e.getTargetId() == guardId)
                .findFirst().orElseThrow();
        assertThat(bossHit.getIsSplashReduced()).isFalse();
        assertThat(guardHit.getIsSplashReduced()).isTrue();
        assertThat(guardHit.getValue()).isLessThan(bossHit.getValue());

        // R2 首领慢速重劈兑现，玩家选择防御 -> 化劲破绽 +35%（持续至 R3 末）
        eng.submitAction(1001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        TurnResultS2C r2 = eng.resolveCurrentRound();
        assertThat(r2.getEventsList()).anyMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_CLEAVE_PARRY_EXPOSE
                && e.getValue() == BattleConstants.BOSS_EXPOSED_FULL_PCT);
        assertThat(r2.getState().getBossExposedPct()).isEqualTo(BattleConstants.BOSS_EXPOSED_FULL_PCT);
        assertThat(r2.getState().getBossExposedUntilRound()).isEqualTo(3);

        // V1 防线：2 人局中被重劈点名目标先逃跑/倒地，绝不能触发破绽
        CreateBattleRequest v1Req = CreateBattleRequest.newBuilder()
                .setBattleId(9802L)
                .setBattleConfigId(101)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_TEAM_VALUE)
                .setSeed(1002L)
                .addPlayers(player(2001L, 80_000L, 900L, 200L, 200L))
                .addPlayers(player(2002L, 80_000L, 880L, 200L, 200L))
                .build();
        TurnBattleEngine v1Eng = ((BattleStart.Started) TurnBattleEngine.start(v1Req, data)).engine();
        BattleTelegraphEntry cleave = v1Eng.buildStateSnapshot().getTelegraphsList().stream()
                .filter(t -> t.getIntentKind() == BattleConstants.TELEGRAPH_KIND_SLOW_CLEAVE).findFirst().orElseThrow();
        long targetedPlayer = cleave.getTargetActorId();
        v1Eng.submitAction(2001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        v1Eng.submitAction(2002L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        v1Eng.resolveCurrentRound(); // R1 -> R2
        // R2 被点名玩家在慢速重劈前确定退出
        v1Eng.submitAction(targetedPlayer, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_FLEE).build());
        long otherPlayer = targetedPlayer == 2001L ? 2002L : 2001L;
        v1Eng.submitAction(otherPlayer, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        TurnResultS2C v1R2 = v1Eng.resolveCurrentRound();
        assertThat(v1R2.getEventsList())
                .noneMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                        && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_CLEAVE_PARRY_EXPOSE);
        assertThat(v1R2.getState().getBossExposedPct()).isZero();
    }

    @Test
    void 回春早杀触发6反噬破绽与困难断脉气馁压制净化() {
        // 1. 普通回春 101：R2 发出回春预告后立即击杀回春护卫 -> 触发 6% 断疗反噬 + 破绽 35%
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9811L)
                .setBattleConfigId(101)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setSeed(2001L)
                .addPlayers(player(1011L, 80_000L, 950L, 25_000L, 200L))
                .build();
        TurnBattleEngine eng = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        eng.submitAction(1011L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        eng.resolveCurrentRound(); // R1 -> R2（R2 开局发布回春祈愈预告，R3 兑现）
        assertThat(eng.buildStateSnapshot().getTelegraphsList())
                .anyMatch(t -> t.getIntentKind() == BattleConstants.TELEGRAPH_KIND_GUARD_HEAL);
        long bossId = findMonsterId(eng.buildStateSnapshot(), 101, true);
        long guardId = findMonsterId(eng.buildStateSnapshot(), 102, false);
        long bossHpBefore = eng.findUnitForTest(bossId).health();
        eng.submitAction(1011L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(guardId).build());
        TurnResultS2C r2 = eng.resolveCurrentRound();
        assertThat(r2.getEventsList()).anyMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_HEAL_BACKLASH);
        assertThat(eng.findUnitForTest(bossId).health()).isLessThan(bossHpBefore);
        assertThat(r2.getState().getBossExposedPct()).isEqualTo(BattleConstants.BOSS_EXPOSED_FULL_PCT);

        // 2. 困难回春 102：R1 发布回春预告（R2 兑现），R2 首领携带断脉减疗(100350)压制 -> 触发气馁(-15%物攻)且不净化
        CreateBattleRequest hardReq = CreateBattleRequest.newBuilder()
                .setBattleId(9812L)
                .setBattleConfigId(102)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setSeed(2002L)
                .addPlayers(player(1012L, 80_000L, 950L, 300L, 300L, antiHealSkillId))
                .build();
        TurnBattleEngine hardEng = ((BattleStart.Started) TurnBattleEngine.start(hardReq, data)).engine();
        long hardBossId = findMonsterId(hardEng.buildStateSnapshot(), 101, true);
        hardEng.submitAction(1012L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        hardEng.resolveCurrentRound(); // R1 -> R2
        int antiHealBuffId = tables.skill().find(antiHealSkillId).orElseThrow().getEffect(0);
        hardEng.findUnitForTest(hardBossId).state().addBuffs(com.game.proto.BattleBuffEntry.newBuilder()
                .setBuffId(998_001L).setBuffTableId(antiHealBuffId).setLayer(1).setRemainRounds(2).setCasterId(1012L));
        hardEng.submitAction(1012L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        TurnResultS2C hardR2 = hardEng.resolveCurrentRound();
        assertThat(hardR2.getEventsList()).anyMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_ANTI_HEAL_DISHEARTEN);
        assertThat(hardR2.getEventsList())
                .noneMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                        && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_REJUV_CLEANSE);
    }

    @Test
    void 护势联防阵庇护全体与轻反制蓄势_A34驱散触发崩势双破绽与困难顺势应变() {
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9821L)
                .setBattleConfigId(104) // 困难·护势
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setSeed(3001L)
                .addPlayers(player(1021L, 120_000L, 950L, 500L, 800L, multiTargetMagicSkillId, dispelBuffSkillId))
                .build();
        TurnBattleEngine eng = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        long bossId = findMonsterId(eng.buildStateSnapshot(), 101, true);

        // R1 困难护势切物理阵，同时顺手 +1 蓄势
        eng.submitAction(1021L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        TurnResultS2C r1 = eng.resolveCurrentRound();
        assertThat(r1.getState().getBossStanceType()).isEqualTo(1);
        assertThat(r1.getState().getBossStanceRemainRounds()).isEqualTo(2);
        assertThat(r1.getState().getBossMomentumStacks()).isEqualTo(1);

        // R2 玩家物理普攻撞物防阵 -> 触发轻反制蓄势；随后 R2 慢速重劈消耗蓄势
        eng.submitAction(1021L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(bossId).build());
        TurnResultS2C r2 = eng.resolveCurrentRound();
        assertThat(r2.getEventsList()).anyMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_MOMENTUM_GAIN);
        assertThat(r2.getState().getBossMomentumStacks()).isZero(); // 重劈后清空

        // R3 用法术技能主攻，令前 2 回合法伤 > 物伤，测试 R4 顺势应变切法防阵(2)
        eng.submitAction(1021L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(multiTargetMagicSkillId).setTargetId(bossId).build());
        eng.resolveCurrentRound(); // R3 -> R4
        BattleTelegraphEntry r4Stance = eng.buildStateSnapshot().getTelegraphsList().stream()
                .filter(t -> t.getIntentKind() == BattleConstants.TELEGRAPH_KIND_GUARD_STANCE_MAGIC)
                .findFirst().orElseThrow();
        assertThat(r4Stance.getExecuteRound()).isEqualTo(4);

        // R4 护卫展开法防阵 -> R5 用驱散技能(A34)破阵 -> 触发崩势破盾(3)并清零蓄势、首领与护卫双双破绽 35%
        eng.submitAction(1021L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        TurnResultS2C r4 = eng.resolveCurrentRound();
        assertThat(r4.getState().getBossStanceType()).isEqualTo(2);
        eng.submitAction(1021L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(dispelBuffSkillId).setTargetId(bossId).build());
        TurnResultS2C r5 = eng.resolveCurrentRound();
        assertThat(r5.getEventsList()).anyMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_STANCE_BREAK);
        assertThat(r5.getState().getBossStanceType()).isZero();
        assertThat(r5.getState().getBossExposedPct()).isEqualTo(BattleConstants.BOSS_EXPOSED_FULL_PCT);
    }

    @Test
    void 困难双卫同阵112_75血量压缩与3人裂地双劈半破绽20_二阶段决战与确定退出及试炼印13_14() {
        // 1. 单人双卫同阵 112：3 怪（首领 101 + 回春 102 + 护势 103），护卫血量压缩至 75%，R1 错峰仅开护势与重劈预告
        BattlePlayerSnapshot soloWithPet = player(3001L, 120_000L, 950L, 400L, 400L).toBuilder()
                .addPets(BattlePetSnapshot.newBuilder()
                        .setPetId(300101L).setPetTableId(1).setLevel(60).setMaxHealth(30_000L).setMaxMana(1000L)
                        .setPhysicalAttack(200L)
                        .setBaseAttributes(BaseAttributesComp.newBuilder()
                                .setHealth(30_000L).setMana(1000L).setSpeed(800L).build()))
                .build();
        CreateBattleRequest soloReq = CreateBattleRequest.newBuilder()
                .setBattleId(9831L)
                .setBattleConfigId(112)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_SOLO_VALUE)
                .setSeed(4001L)
                .addPlayers(soloWithPet)
                .build();
        TurnBattleEngine soloEng = ((BattleStart.Started) TurnBattleEngine.start(soloReq, data)).engine();
        BattleStateS2C soloState = soloEng.buildStateSnapshot();
        assertThat(soloState.getBossTrialKind()).isEqualTo(BattleConstants.BOSS_TRIAL_KIND_BREAK_DUAL_GUARD);
        assertThat(soloState.getBattleDifficulty()).isEqualTo(2);
        List<BattleActorState> soloMonsters = soloState.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER).toList();
        assertThat(soloMonsters).hasSize(3);
        long baseRejuvHp = tables.monster().find(102).orElseThrow().getHealth();
        BattleActorState rejuvState = soloMonsters.stream().filter(m -> m.getMonsterTableId() == 102).findFirst().orElseThrow();
        assertThat(rejuvState.getMaxHealth()).isEqualTo((baseRejuvHp * 75L) / 100L);

        // 确定退出：玩家选择逃跑必定成功，人宠原子离场
        soloEng.submitAction(3001L, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_FLEE).build());
        TurnResultS2C fleeRes = soloEng.resolveCurrentRound();
        assertThat(fleeRes.getEventsList().stream()
                .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_FLEE && e.getSuccess()).count())
                .isEqualTo(2L);
        assertThat(soloEng.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN);

        // 2. 3 人双卫同阵 112：追加破军卫 109（共 4 怪），R1 裂地双劈点名 2 人，R2 仅 1 人防御 -> 轻破绽 +20%
        CreateBattleRequest trioReq = CreateBattleRequest.newBuilder()
                .setBattleId(9832L)
                .setBattleConfigId(112)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_TEAM_VALUE)
                .setSeed(4002L)
                .addPlayers(player(3101L, 120_000L, 950L, 400L, 400L))
                .addPlayers(player(3102L, 120_000L, 940L, 400L, 400L))
                .addPlayers(player(3103L, 120_000L, 930L, 400L, 400L))
                .setTrialContext(TrialBattleContext.newBuilder().setJournalEntryId(105).setTrialRuleVersion(1))
                .build();
        TurnBattleEngine trioEng = ((BattleStart.Started) TurnBattleEngine.start(trioReq, data)).engine();
        BattleStateS2C trioS1 = trioEng.buildStateSnapshot();
        List<BattleActorState> trioMonsters = trioS1.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER).toList();
        assertThat(trioMonsters).hasSize(4);
        assertThat(trioMonsters).anyMatch(m -> m.getMonsterTableId() == BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_BREAKER);
        BattleTelegraphEntry dualCleave = trioS1.getTelegraphsList().stream()
                .filter(t -> t.getIntentKind() == BattleConstants.TELEGRAPH_KIND_SLOW_CLEAVE).findFirst().orElseThrow();
        assertThat(dualCleave.getTargetActorIdsCount()).isEqualTo(2);
        long firstMarked = dualCleave.getTargetActorIds(0);
        long secondMarked = dualCleave.getTargetActorIds(1);

        // R1 全员防御过回合
        for (long pid : List.of(3101L, 3102L, 3103L)) {
            trioEng.submitAction(pid, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        }
        trioEng.resolveCurrentRound(); // R1 -> R2
        // R2 仅 firstMarked 防御，secondMarked 普攻 -> 1/2 化解得 +20% 轻破绽（V3）
        long trioBossId = findMonsterId(trioS1, 101, true);
        trioEng.submitAction(firstMarked, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        trioEng.submitAction(secondMarked, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(trioBossId).build());
        for (long pid : List.of(3101L, 3102L, 3103L)) {
            if (pid != firstMarked && pid != secondMarked) {
                trioEng.submitAction(pid, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
            }
        }
        TurnResultS2C trioR2 = trioEng.resolveCurrentRound();
        assertThat(trioR2.getState().getBossExposedPct()).isEqualTo(BattleConstants.BOSS_EXPOSED_PARTIAL_PCT);

        // 将首领血量压至 25%（即便 R3 回春护卫回复 10% 仍 <= 50%）-> 回合末切二阶段「决战姿态」，并验证新条件 13 & 14 判定口径
        BattleUnit bossUnit = trioEng.findUnitForTest(trioBossId);
        bossUnit.setHealth(bossUnit.maxHealth() / 4L);
        for (long pid : List.of(3101L, 3102L, 3103L)) {
            trioEng.submitAction(pid, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        }
        TurnResultS2C trioR3 = trioEng.resolveCurrentRound();
        assertThat(trioR3.getState().getBossPhase()).isEqualTo(2);
        assertThat(trioR3.getEventsList()).anyMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_PHASE_TWO_ENRAGE);

        // 战法印条件 13 (双卫连环双破) & 14 (化劲宗师) 纯规则校验
        BattleSettlementData mockWin = BattleSettlementData.newBuilder()
                .setBattleId(9832L).setPlayerId(firstMarked).setPlayerTeamIndex(0)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setTotalRounds(6)
                .setTrialFacts(trioEng.buildSettlement(firstMarked).getTrialFacts().toBuilder()
                        .setGuardStanceDispelled(true)
                        .setBossGuardHealTotal(0L))
                .build();
        assertThat(TrialJournalRules.isValidConditionType(TrialJournalRules.COND_DUAL_GUARD_BREAK)).isTrue();
        assertThat(TrialJournalRules.isValidConditionType(TrialJournalRules.COND_PERFECT_CLEAVE_PARRY)).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_DUAL_GUARD_BREAK, 0, mockWin)).isTrue();
        assertThat(TrialJournalRules.evaluateSealCondition(TrialJournalRules.COND_PERFECT_CLEAVE_PARRY, 0, mockWin)).isTrue();
    }

    @Test
    void 四人单卫局精简精英阵_破军卫破甲与鼓噪卫蓄势及阵亡4震荡() {
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9841L)
                .setBattleConfigId(101)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_TEAM_VALUE)
                .setSeed(5001L)
                .addPlayers(player(4101L, 120_000L, 950L, 30_000L, 200L))
                .addPlayers(player(4102L, 120_000L, 940L, 200L, 200L))
                .addPlayers(player(4103L, 120_000L, 930L, 200L, 200L))
                .addPlayers(player(4104L, 120_000L, 920L, 200L, 200L))
                .build();
        TurnBattleEngine eng = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        BattleStateS2C s1 = eng.buildStateSnapshot();
        List<BattleActorState> monsters = s1.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER).toList();
        assertThat(monsters).hasSize(4); // 101 + 102 + 109 + 110
        long bossId = findMonsterId(s1, 101, true);
        long drummerId = findMonsterId(s1, BattleConstants.BOSS_TRIAL_MONSTER_SUPPORT_DRUMMER, false);

        // R1 全员防御：破军卫普攻触发破甲(7)，鼓噪卫回合末为首领叠 +1 蓄势(6)
        for (long pid : List.of(4101L, 4102L, 4103L, 4104L)) {
            eng.submitAction(pid, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        }
        TurnResultS2C r1 = eng.resolveCurrentRound();
        assertThat(r1.getEventsList()).anyMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_BREAKER_SUNDER);
        assertThat(r1.getState().getBossMomentumStacks()).isEqualTo(1);

        // R2 击杀鼓噪卫 -> 蓄势清零 + 首领承受 4% 震荡伤害(4)
        long bossHpBefore = eng.findUnitForTest(bossId).health();
        eng.submitAction(4101L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(drummerId).build());
        for (long pid : List.of(4102L, 4103L, 4104L)) {
            eng.submitAction(pid, BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build());
        }
        TurnResultS2C r2 = eng.resolveCurrentRound();
        assertThat(r2.getEventsList()).anyMatch(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_BOSS_MECHANIC
                && e.getMechanicKind() == BattleConstants.BOSS_MECHANIC_SUPPORT_SHOCK);
        assertThat(r2.getState().getBossMomentumStacks()).isZero();
        assertThat(eng.findUnitForTest(bossId).health()).isLessThan(bossHpBefore);
    }
}
