package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleEventItem;
import com.game.proto.BattleParticipationFact;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattlePrepState;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleSurrenderVoteState;
import com.game.proto.CreateBattleRequest;
import com.game.proto.EquipmentEffects;
import com.game.proto.RankedMatchContext;
import com.game.proto.SeasonSealEffect;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleEventType;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.table.ConfigTables;
import com.game.table.MatchErrorTip;
import com.game.table.RankedPresetTable;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 论剑排位首发 V1 战斗引擎契约测试（零宠物临时宝宝、SplitMix64 同速破平、参与事实、回合上限判平、9 组合与回放一致）。 */
class RankedBattleEngineTest {

    private static ConfigTables tables;
    private static TableBattleData data;

    @BeforeAll
    static void loadTables() {
        tables = ConfigTables.load(Path.of("../config-data/tables"));
        data = new TableBattleData(tables);
    }

    private static RankedMatchContext rankedCtx(int teamSize) {
        return RankedMatchContext.newBuilder()
                .setSeasonId(1)
                .setRuleVersion(1)
                .setTeamSize(teamSize)
                .setUseFreePreset(false)
                .build();
    }

    private static RankedMatchContext freePresetCtx(int teamSize) {
        return RankedMatchContext.newBuilder()
                .setSeasonId(0)
                .setRuleVersion(1)
                .setTeamSize(teamSize)
                .setUseFreePreset(true)
                .build();
    }

    private static BattlePlayerSnapshot playerWithRealPet(long playerId, long petId, int teamIndex, long speed,
                                                          RankedPresetTable charPreset, RankedPresetTable petPreset) {
        BattlePlayerSnapshot base = playerWithTempPet(playerId, teamIndex, speed, charPreset, petPreset);
        BattlePetSnapshot realPet = base.getPets(0).toBuilder()
                .setPetId(petId)
                .setCompetitiveTemporary(false)
                .build();
        return base.toBuilder().setPets(0, realPet).build();
    }

    private static BattlePlayerSnapshot playerWithTempPet(long playerId, int teamIndex, long speed,
                                                          RankedPresetTable charPreset, RankedPresetTable petPreset) {
        BattlePetSnapshot.Builder pet = BattlePetSnapshot.newBuilder()
                .setPetId(0L)
                .setCompetitiveTemporary(true)
                .setOwnerPlayerId(playerId)
                .setPetTableId(petPreset.getPetTableId())
                .setCombatPetTableId(petPreset.getPetTableId())
                .setPetName("默认宝宝")
                .setLevel(30)
                .setMaxHealth(900)
                .setMaxMana(400)
                .setPhysicalAttack(180)
                .setMagicAttack(180)
                .setDefense(100)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(900).setMana(400).setSpeed(speed).build());
        for (int sid : petPreset.getSkillIdsList()) {
            pet.putSkillRanks(sid, 1);
            tables.skill().find(sid).ifPresent(row -> {
                if (BattleRules.isTurnBattleCastableSkill(row)) pet.addSkillTableIds(sid);
            });
        }
        BattlePlayerSnapshot.Builder p = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(playerId)
                .setPlayerName("P" + playerId)
                .setTeamIndex(teamIndex)
                .setClassId(1)
                .setLevel(30)
                .setMaxHealth(1200)
                .setMaxMana(600)
                .setPhysicalAttack(260)
                .setMagicAttack(260)
                .setDefense(120)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(1200).setMana(600).setSpeed(speed).build())
                .addPets(pet);
        for (int sid : charPreset.getSkillIdsList()) {
            p.putSkillRanks(sid, 1);
            tables.skill().find(sid).ifPresent(row -> {
                if (BattleRules.isTurnBattleCastableSkill(row)) p.addSkillTableIds(sid);
            });
        }
        return p.build();
    }

    @Test
    void 零宠物临时竞技宝宝仅免费预设娱乐场允许且正式排位拒绝临时宠() {
        RankedPresetTable charPreset = tables.rankedPreset().find(101).orElseThrow();
        RankedPresetTable petPreset = tables.rankedPreset().find(201).orElseThrow();
        BattlePlayerSnapshot p1 = playerWithTempPet(5001L, 0, 200L, charPreset, petPreset);
        BattlePlayerSnapshot p2 = playerWithTempPet(5002L, 1, 200L, charPreset, petPreset);

        // 非免费预设普通局拒绝 competitive_temporary=true 临时宝宝
        CreateBattleRequest nonRankedReq = CreateBattleRequest.newBuilder()
                .setBattleId(9001L)
                .setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_1V1_VALUE)
                .setSeed(42L)
                .addPlayers(p1)
                .addPlayers(p2)
                .build();
        assertThat(TurnBattleEngine.start(nonRankedReq, data)).isInstanceOf(BattleStart.Rejected.class);

        // 正式排位(use_free_preset=false)同样拒绝临时竞技宝宝，必须走本人真实宝宝
        CreateBattleRequest formalRankedWithTempPet = nonRankedReq.toBuilder()
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setRankedContext(rankedCtx(1))
                .build();
        assertThat(TurnBattleEngine.start(formalRankedWithTempPet, data)).isInstanceOf(BattleStart.Rejected.class);

        // 正式排位带真实宝宝正常开局，结算标 competitive_ranked=true 且 use_free_preset=false
        CreateBattleRequest formalRankedReal = CreateBattleRequest.newBuilder()
                .setBattleId(9002L)
                .setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(42L)
                .setRankedContext(rankedCtx(1))
                .addPlayers(playerWithRealPet(5001L, 80001L, 0, 200L, charPreset, petPreset))
                .addPlayers(playerWithRealPet(5002L, 80002L, 1, 200L, charPreset, petPreset))
                .build();
        BattleStart formalStarted = TurnBattleEngine.start(formalRankedReal, data);
        assertThat(formalStarted).isInstanceOf(BattleStart.Started.class);
        BattleSettlementData formalSettlement = ((BattleStart.Started) formalStarted).engine().buildSettlement(5001L);
        assertThat(formalSettlement.getCompetitiveRanked()).isTrue();
        assertThat(formalSettlement.getUseFreePreset()).isFalse();
        assertThat(formalSettlement.getSeasonId()).isEqualTo(1);

        // 免费预设娱乐场(use_free_preset=true)允许 pet_id=0 临时宝宝，分配局内 actor_id，且结算标 use_free_preset=true 且不回写该临时宝宝
        CreateBattleRequest freePresetReq = nonRankedReq.toBuilder()
                .setMatchMode(MatchMode.MATCH_MODE_POLARITY_5V5_VALUE)
                .setRankedContext(freePresetCtx(1))
                .build();
        BattleStart started = TurnBattleEngine.start(freePresetReq, data);
        assertThat(started).isInstanceOf(BattleStart.Started.class);
        TurnBattleEngine engine = ((BattleStart.Started) started).engine();
        BattleStateS2C state = engine.buildStateSnapshot();
        List<BattleActorState> pets = state.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET)
                .toList();
        assertThat(pets).hasSize(2);
        for (BattleActorState petActor : pets) {
            assertThat(petActor.getCompetitiveTemporary()).isTrue();
            assertThat(petActor.getPetId()).isZero();
            assertThat(petActor.getActorId() & BattleConstants.ENGINE_LOCAL_ACTOR_ID_FLAG).isNotZero();
        }
        BattleSettlementData s1 = engine.buildSettlement(5001L);
        assertThat(s1.getCompetitiveRanked()).isFalse();
        assertThat(s1.getUseFreePreset()).isTrue();
        assertThat(s1.getSeasonId()).isZero();
        assertThat(s1.getPetsList()).isEmpty();
    }

    @Test
    void 镜像同速按SplitMix64确定性破平_两侧均能先手且回放逐字节一致() {
        RankedPresetTable charPreset = tables.rankedPreset().find(101).orElseThrow();
        RankedPresetTable petPreset = tables.rankedPreset().find(203).orElseThrow();
        Set<Long> firstMovers = new HashSet<>();
        for (long seed = 1L; seed <= 30L; seed++) {
            CreateBattleRequest req = CreateBattleRequest.newBuilder()
                    .setBattleId(9100L + seed)
                    .setBattleConfigId(5)
                    .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                    .setSeed(seed)
                    .setRankedContext(rankedCtx(1))
                    .addPlayers(playerWithRealPet(5001L, 81001L, 0, 220L, charPreset, petPreset))
                    .addPlayers(playerWithRealPet(5002L, 81002L, 1, 220L, charPreset, petPreset))
                    .build();
            TurnBattleEngine e1 = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
            TurnBattleEngine e2 = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
            TurnResultS2C r1 = e1.resolveCurrentRound();
            TurnResultS2C r2 = e2.resolveCurrentRound();
            assertThat(r1).isEqualTo(r2);
            assertThat(e1.lastActionOrder()).isEqualTo(e2.lastActionOrder());
            long firstPlayer = e1.lastActionOrder().stream()
                    .filter(id -> id == 5001L || id == 5002L)
                    .findFirst().orElseThrow();
            firstMovers.add(firstPlayer);
        }
        assertThat(firstMovers).containsExactlyInAnyOrder(5001L, 5002L);
    }

    @Test
    void 有效参与事实区分手动_显式自动_未进场挂机与未获出手倒地() {
        RankedPresetTable charPreset = tables.rankedPreset().find(101).orElseThrow();
        RankedPresetTable petPreset = tables.rankedPreset().find(201).orElseThrow();
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9201L)
                .setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(77L)
                .setRankedContext(rankedCtx(1))
                .addPlayers(playerWithRealPet(5001L, 82001L, 0, 260L, charPreset, petPreset))
                .addPlayers(playerWithRealPet(5002L, 82002L, 1, 180L, charPreset, petPreset))
                .build();
        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        // P1 进场并手动出招；P2 从未进场、从未手动或显式开启自动
        engine.markPlayerEntered(5001L);
        engine.submitAction(5001L, BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(5002L).build());
        engine.resolveCurrentRound();

        BattleParticipationFact f1 = engine.buildParticipationFact(5001L);
        BattleParticipationFact f2 = engine.buildParticipationFact(5002L);
        assertThat(f1.getEnteredRoom()).isTrue();
        assertThat(f1.getManualActionAccepted()).isTrue();
        assertThat(engine.didPlayerParticipate(5001L)).isTrue();
        assertThat(f2.getEnteredRoom()).isFalse();
        assertThat(f2.getManualActionAccepted()).isFalse();
        assertThat(engine.didPlayerParticipate(5002L)).isFalse();
        assertThat(engine.buildSettlement(5001L).getParticipated()).isTrue();
        assertThat(engine.buildSettlement(5002L).getParticipated()).isFalse();

        // 显式自动 + 进场 => 有效参与
        TurnBattleEngine autoEngine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        autoEngine.markPlayerEntered(5002L);
        assertThat(autoEngine.setActorAuto(5002L, true)).isEqualTo(ActionChecks.SUCCESS);
        autoEngine.resolveCurrentRound();
        assertThat(autoEngine.didPlayerParticipate(5002L)).isTrue();
    }

    @Test
    void 免费预设九种人宠搭配均可正常开局与运转() {
        List<RankedPresetTable> chars = tables.rankedPreset().all().stream().filter(r -> r.getRoleKind() == 1).toList();
        List<RankedPresetTable> pets = tables.rankedPreset().all().stream().filter(r -> r.getRoleKind() == 2).toList();
        assertThat(chars).hasSize(3);
        assertThat(pets).hasSize(3);
        long id = 9300L;
        for (RankedPresetTable c : chars) {
            for (RankedPresetTable p : pets) {
                id++;
                CreateBattleRequest req = CreateBattleRequest.newBuilder()
                        .setBattleId(id)
                        .setBattleConfigId(5)
                        .setMatchMode(MatchMode.MATCH_MODE_POLARITY_5V5_VALUE)
                        .setSeed(id)
                        .setRankedContext(freePresetCtx(1))
                        .addPlayers(playerWithTempPet(6001L, 0, 210L, c, p))
                        .addPlayers(playerWithTempPet(6002L, 1, 200L, c, p))
                        .build();
                BattleStart started = TurnBattleEngine.start(req, data);
                assertThat(started).isInstanceOf(BattleStart.Started.class);
                TurnBattleEngine engine = ((BattleStart.Started) started).engine();
                engine.markPlayerEntered(6001L);
                engine.setActorAuto(6001L, true);
                engine.markPlayerEntered(6002L);
                engine.setActorAuto(6002L, true);
                TurnResultS2C turn = engine.resolveCurrentRound();
                assertThat(turn.getEventsCount()).isGreaterThan(0);
            }
        }
    }

    @Test
    void 排位回合上限判平区别于非排位判负() {
        MemoryBattleData mem = TestTables.standard();
        mem.addDungeon(5).setId(5).setTimeLimit(12); // 12s / 6s = 2 回合上限
        BattlePlayerSnapshot a = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(5001L).setTeamIndex(0).setLevel(30).setMaxHealth(100_000)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(100_000).setSpeed(200).build()).build();
        BattlePlayerSnapshot b = BattlePlayerSnapshot.newBuilder()
                .setPlayerId(5002L).setTeamIndex(1).setLevel(30).setMaxHealth(100_000)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(100_000).setSpeed(190).build()).build();
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9401L).setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(1L).setRankedContext(rankedCtx(1))
                .addPlayers(a).addPlayers(b).build();
        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, mem)).engine();
        engine.resolveCurrentRound();
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_DRAW);
    }

    @Test
    void 竞技PVP多目标技能目标不满时显式主目标触发20百分比凝锋加伤() {
        RankedPresetTable charPreset = tables.rankedPreset().find(101).orElseThrow();
        RankedPresetTable petPreset = tables.rankedPreset().find(201).orElseThrow();
        BattlePlayerSnapshot p1 = playerWithRealPet(5001L, 85001L, 0, 260L, charPreset, petPreset);
        BattlePlayerSnapshot p2Base = playerWithRealPet(5002L, 85002L, 1, 180L, charPreset, petPreset);
        BattlePlayerSnapshot p2 = p2Base.toBuilder()
                .setDefense(100)
                .setPets(0, p2Base.getPets(0).toBuilder().setDefense(100).build())
                .build();
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9501L).setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(77L).setRankedContext(rankedCtx(1))
                .addPlayers(p1).addPlayers(p2).build();
        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        long enemyPetActorId = engine.buildStateSnapshot().getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET && a.getTeamIndex() == 1)
                .findFirst().orElseThrow().getActorId();
        BattleAction cast = BattleAction.newBuilder()
                .setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(10002).setTargetId(5002L).build();
        BattleAction atk1 = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_ATTACK).setTargetId(5001L).build();
        engine.submitAction(5001L, cast);
        engine.submitAction(5002L, atk1);
        for (BattleActorState actor : engine.buildStateSnapshot().getActorsList()) {
            if (actor.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET) {
                engine.submitOwnedAction(actor.getOwnerPlayerId(), actor.getActorId(),
                        BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_ATTACK)
                                .setTargetId(actor.getTeamIndex() == 0 ? 5002L : 5001L).build());
            }
        }
        TurnResultS2C res = engine.resolveCurrentRound();
        long primaryDmg = 0, secondaryDmg = 0;
        boolean primaryMarked = false, secondaryMarked = false;
        for (BattleEventItem ev : res.getEventsList()) {
            if (ev.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE
                    && ev.getSourceId() == 5001L && ev.getSkillTableId() == 10002) {
                if (ev.getTargetId() == 5002L) {
                    primaryDmg = ev.getValue();
                    primaryMarked = ev.getIsPrimaryFocus();
                } else if (ev.getTargetId() == enemyPetActorId) {
                    secondaryDmg = ev.getValue();
                    secondaryMarked = ev.getIsPrimaryFocus();
                }
            }
        }
        assertThat(primaryDmg).isGreaterThan(secondaryDmg);
        assertThat(secondaryDmg).isPositive();
        assertThat(primaryMarked).isTrue();
        assertThat(secondaryMarked).isFalse();
        assertThat((double) primaryDmg).isCloseTo(secondaryDmg * 1.20, org.assertj.core.data.Offset.offset(2.0));
    }

    @Test
    void 战意渐盛从第13回合起逐回合增伤10百分比并衰减治疗10百分比() {
        RankedPresetTable healPreset = tables.rankedPreset().find(103).orElseThrow();
        BattlePlayerSnapshot p1 = playerWithRealPet(5001L, 86001L, 0, 220L, healPreset, tables.rankedPreset().find(203).orElseThrow())
                .toBuilder().clearPets().setMaxHealth(20_000)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(10_000).setMana(2000).setSpeed(220).build()).build();
        BattlePlayerSnapshot p2 = playerWithRealPet(5002L, 86002L, 1, 200L, healPreset, tables.rankedPreset().find(203).orElseThrow())
                .toBuilder().clearPets().setMaxHealth(20_000)
                .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(20_000).setMana(2000).setSpeed(200).build()).build();
        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9502L).setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(88L).setRankedContext(rankedCtx(1))
                .addPlayers(p1).addPlayers(p2).build();
        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        BattleStateS2C s1 = engine.buildStateSnapshot();
        assertThat(s1.getRoundLimit()).isEqualTo(20);
        assertThat(s1.getEscalationStartRound()).isEqualTo(13);
        assertThat(s1.getEscalationStacks()).isZero();
        assertThat(s1.getSurrenderUnlockRound()).isEqualTo(5);

        BattleAction healAct = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_SKILL)
                .setSkillTableId(10010).setTargetId(5001L).build();
        BattleAction atkAct = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_ATTACK)
                .setTargetId(5001L).build();
        BattleAction defAct = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build();
        engine.submitAction(5001L, healAct);
        engine.submitAction(5002L, atkAct);
        TurnResultS2C r1 = engine.resolveCurrentRound();
        long r1Heal = r1.getEventsList().stream()
                .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_HEAL && e.getSourceId() == 5001L)
                .findFirst().orElseThrow().getValue();
        long r1Dmg = r1.getEventsList().stream()
                .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE && e.getSourceId() == 5002L)
                .findFirst().orElseThrow().getValue();

        for (int r = 2; r <= 12; r++) {
            engine.submitAction(5001L, defAct);
            engine.submitAction(5002L, defAct);
            engine.resolveCurrentRound();
        }
        BattleStateS2C s13 = engine.buildStateSnapshot();
        assertThat(s13.getRoundIndex()).isEqualTo(13);
        assertThat(s13.getEscalationStacks()).isEqualTo(1);
        engine.submitAction(5001L, healAct);
        engine.submitAction(5002L, atkAct);
        TurnResultS2C r13 = engine.resolveCurrentRound();
        long r13Heal = r13.getEventsList().stream()
                .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_HEAL && e.getSourceId() == 5001L)
                .findFirst().orElseThrow().getValue();
        long r13Dmg = r13.getEventsList().stream()
                .filter(e -> e.getEventType() == eBattleEventType.BATTLE_EVENT_DAMAGE && e.getSourceId() == 5002L)
                .findFirst().orElseThrow().getValue();
        assertThat(r13Heal).isLessThan(r1Heal);
        assertThat(r13Dmg).isGreaterThan(r1Dmg);
    }

    @Test
    void PVP投降第5回合解锁_否决冷却3回合_挂机队友剔除表决门槛且敌方观众隔离() {
        BattleAction defAct = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build();
        CreateBattleRequest.Builder req3v3 = CreateBattleRequest.newBuilder()
                .setBattleId(9503L).setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(92L).setRankedContext(rankedCtx(3));
        for (long pid : List.of(101L, 102L, 103L, 201L, 202L, 203L)) {
            req3v3.addPlayers(BattlePlayerSnapshot.newBuilder()
                    .setPlayerId(pid).setTeamIndex(pid < 200L ? 0 : 1).setLevel(30)
                    .setMaxHealth(50_000).setPhysicalAttack(100).setDefense(100)
                    .setBaseAttributes(BaseAttributesComp.newBuilder().setHealth(50_000).setSpeed(200).build()));
        }
        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req3v3.build(), data)).engine();
        // R1 过早投降被拒
        assertThat(engine.submitSurrenderVote(101L, true).errorTip())
                .isEqualTo(MatchErrorTip.match_error.kMatchBattleSurrenderTooEarly_VALUE);

        for (int r = 1; r <= 4; r++) {
            for (long pid : List.of(101L, 102L, 103L, 201L, 202L, 203L)) {
                engine.submitAction(pid, defAct);
            }
            engine.resolveCurrentRound();
        }
        var initVote = engine.submitSurrenderVote(101L, true);
        assertThat(initVote.errorTip()).isZero();
        assertThat(initVote.voteState().getActive()).isTrue();
        assertThat(initVote.voteState().getRequiredAgrees()).isEqualTo(3);
        assertThat(engine.submitSurrenderVote(101L, true).errorTip())
                .isEqualTo(MatchErrorTip.match_error.kMatchBattleSurrenderAlreadyVoted_VALUE);

        // 同队可见，敌方与观众不可见
        assertThat(engine.surrenderVoteForViewer(102L)).isNotNull();
        assertThat(engine.surrenderVoteForViewer(201L)).isNull();
        assertThat(engine.surrenderVoteForViewer(0L)).isNull();

        // 102 否决 -> 冷却 3 回合至 R8
        var rejected = engine.submitSurrenderVote(102L, false);
        assertThat(rejected.errorTip()).isZero();
        assertThat(rejected.voteState().getRejected()).isTrue();
        assertThat(rejected.voteState().getNextAllowedRound()).isEqualTo(8);
        assertThat(engine.submitSurrenderVote(101L, true).errorTip())
                .isEqualTo(MatchErrorTip.match_error.kMatchBattleSurrenderCooldown_VALUE);

        // R5..R7：103 连续 3 回合未手动 -> R8 仅需 101+102 两票即可通过
        for (int r = 5; r <= 7; r++) {
            for (long pid : List.of(101L, 102L, 201L, 202L, 203L)) {
                engine.submitAction(pid, defAct);
            }
            engine.resolveCurrentRound();
        }
        var secondVote = engine.submitSurrenderVote(101L, true);
        assertThat(secondVote.errorTip()).isZero();
        assertThat(secondVote.voteState().getRequiredAgrees()).isEqualTo(2);
        var passed = engine.submitSurrenderVote(102L, true);
        assertThat(passed.errorTip()).isZero();
        assertThat(passed.voteState().getPassed()).isTrue();
        assertThat(engine.outcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN);
        BattleSettlementData s101 = engine.buildSettlement(101L);
        assertThat(s101.getSurrendered()).isTrue();
        assertThat(s101.getFled()).isFalse();
        assertThat(s101.getTotalRounds()).isEqualTo(7);
        assertThat(engine.buildSettlement(201L).getSurrendered()).isFalse();
    }

    private static SeasonSealEffect makeSeal(int seasonId, int sealId, int effectId, String name) {
        return SeasonSealEffect.newBuilder()
                .setSeasonId(seasonId)
                .setSealId(sealId)
                .setEffectId(effectId)
                .setName(name)
                .setBonusBps(1000)
                .setPerRoundLimit(1)
                .setBattleLimit(effectId == 7 ? 2 : 1)
                .build();
    }

    @Test
    void 论剑V3战前15秒明牌变阵_换印换宠与同队盲选隔离及亮牌后生效() {
        RankedPresetTable charPreset = tables.rankedPreset().find(101).orElseThrow();
        RankedPresetTable petA = tables.rankedPreset().find(201).orElseThrow();
        RankedPresetTable petB = tables.rankedPreset().find(203).orElseThrow();

        SeasonSealEffect sealDefMana = makeSeal(1, 1001, 7, "玄清回灵印");
        SeasonSealEffect sealAntiCtrl = makeSeal(1, 1002, 8, "定心明神印");

        BattlePlayerSnapshot p1Base = playerWithRealPet(5101L, 91001L, 0, 220L, charPreset, petA);
        BattlePetSnapshot p1Standby = playerWithRealPet(5101L, 91002L, 0, 260L, charPreset, petB)
                .getPets(0).toBuilder().setPetName("疾风备宠").build();
        BattlePlayerSnapshot p1 = p1Base.toBuilder()
                .setActiveSeasonId(1)
                .setEquipmentEffects(EquipmentEffects.newBuilder().setSeasonSeal(sealAntiCtrl))
                .addAvailableSeasonSeals(sealDefMana)
                .addAvailableSeasonSeals(sealAntiCtrl)
                .addStandbyPets(p1Standby)
                .build();

        BattlePlayerSnapshot p2 = playerWithRealPet(5102L, 92001L, 1, 200L, charPreset, petA).toBuilder()
                .setActiveSeasonId(1)
                .addAvailableSeasonSeals(sealAntiCtrl)
                .build();

        CreateBattleRequest req = CreateBattleRequest.newBuilder()
                .setBattleId(9601L)
                .setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(101L)
                .setRankedContext(rankedCtx(1))
                .addPlayers(p1)
                .addPlayers(p2)
                .build();

        TurnBattleEngine engine = ((BattleStart.Started) TurnBattleEngine.start(req, data)).engine();
        assertThat(engine.isInPrepPhase()).isTrue();
        assertThat(engine.allPrepPlayersReady()).isFalse();
        assertThat(engine.allPlayersReady()).isFalse();

        // 变阵期内行动、挂机与投降一律返回 FEATURE_UNAVAILABLE，pending_actor_ids 为空
        BattleAction defAct = BattleAction.newBuilder().setActionType(eBattleActionType.BATTLE_ACTION_DEFEND).build();
        assertThat(engine.validateAction(5101L, defAct)).isEqualTo(ActionChecks.FEATURE_UNAVAILABLE);
        assertThat(engine.validateOwnedAction(5101L, 5101L, defAct)).isEqualTo(ActionChecks.FEATURE_UNAVAILABLE);
        assertThat(engine.setActorAuto(5101L, true)).isEqualTo(ActionChecks.FEATURE_UNAVAILABLE);
        assertThat(engine.submitSurrenderVote(5101L, true).errorTip()).isEqualTo(ActionChecks.FEATURE_UNAVAILABLE);
        BattleStateS2C prepSnap = engine.buildStateSnapshot();
        assertThat(prepSnap.getPrepState().getActive()).isTrue();
        assertThat(prepSnap.getPrepState().getDurationSeconds()).isEqualTo(BattleConstants.TACTICAL_PREP_DURATION_SECONDS);
        assertThat(prepSnap.getPendingActorIdsList()).isEmpty();

        long petActorIdBefore = prepSnap.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET && a.getOwnerPlayerId() == 5101L)
                .findFirst().orElseThrow().getActorId();
        int petSlotBefore = prepSnap.getActorsList().stream()
                .filter(a -> a.getActorId() == petActorIdBefore)
                .findFirst().orElseThrow().getFormationSlot();

        // P1 改选回灵印(1001) + 备宠(91002) 并锁定；未锁定前对手和观众不可见 P1 选择
        var p1Res = engine.updateBattlePrep(5101L, 1001, 91002L, true);
        assertThat(p1Res.errorTip()).isZero();
        assertThat(p1Res.prepState().getSelfAvailableSealsList()).hasSize(2);
        assertThat(p1Res.prepState().getSelfAvailablePetsList()).hasSize(2);
        assertThat(p1Res.prepState().getTeamStatesList()).hasSize(1);
        assertThat(p1Res.prepState().getTeamStates(0).getSelectedSealId()).isEqualTo(1001);
        assertThat(p1Res.prepState().getTeamStates(0).getSelectedPetId()).isEqualTo(91002L);
        assertThat(p1Res.prepState().getTeamStates(0).getReady()).isTrue();

        // 锁定后再改动被拒，幂等重发原方案成功
        assertThat(engine.updateBattlePrep(5101L, 1002, 0L, true).errorTip())
                .isEqualTo(ActionChecks.FEATURE_UNAVAILABLE);
        assertThat(engine.updateBattlePrep(5101L, 1001, 91002L, true).errorTip()).isZero();

        // 对手视角不含 P1 的 team_states，观众视角仅含倒计时
        BattlePrepState p2View = engine.prepStateForViewer(5102L, 12345L);
        assertThat(p2View.getTeamStatesList()).extracting(s -> s.getPlayerId()).containsExactly(5102L);
        BattlePrepState obsView = engine.prepStateForViewer(0L, 12345L);
        assertThat(obsView.getActive()).isTrue();
        assertThat(obsView.getDeadlineMs()).isEqualTo(12345L);
        assertThat(obsView.getSelfAvailableSealsList()).isEmpty();
        assertThat(obsView.getSelfAvailablePetsList()).isEmpty();
        assertThat(obsView.getTeamStatesList()).isEmpty();

        // P2 锁定 -> 全员锁定 -> 亮牌
        assertThat(engine.allPrepPlayersReady()).isFalse();
        assertThat(engine.updateBattlePrep(5102L, 0, 0L, true).errorTip()).isZero();
        assertThat(engine.allPrepPlayersReady()).isTrue();
        assertThat(engine.finalizePrepPhase()).isTrue();
        assertThat(engine.isInPrepPhase()).isFalse();
        assertThat(engine.finalizePrepPhase()).isFalse();

        BattleStateS2C revealed = engine.buildStateSnapshot();
        assertThat(revealed.hasPrepState()).isFalse();
        assertThat(revealed.getPendingActorIdsList()).contains(5101L, 5102L);
        BattleActorState swappedPet = revealed.getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_PET && a.getOwnerPlayerId() == 5101L)
                .findFirst().orElseThrow();
        assertThat(swappedPet.getActorId()).isEqualTo(petActorIdBefore);
        assertThat(swappedPet.getFormationSlot()).isEqualTo(petSlotBefore);
        assertThat(swappedPet.getPetId()).isEqualTo(91002L);
        assertThat(swappedPet.getName()).isEqualTo("疾风备宠");

        // 换上的回灵赛季印(1001, effect_id=7)在亮牌后首回合防御可真实触发回蓝事件
        BattleUnit p1Unit = engine.findUnitForTest(5101L);
        p1Unit.setMana(100L);
        assertThat(engine.validateAction(5101L, defAct)).isEqualTo(ActionChecks.SUCCESS);
        engine.submitAction(5101L, defAct);
        engine.submitAction(5102L, defAct);
        TurnResultS2C r1 = engine.resolveCurrentRound();
        assertThat(r1.getEventsList())
                .anyMatch(ev -> ev.getEventType() == eBattleEventType.BATTLE_EVENT_ARTIFACT
                        && ev.getSourceId() == 5101L
                        && ev.getSeasonSealId() == 1001);
        assertThat(engine.buildSettlement(5101L).getPetsList())
                .extracting(p -> p.getPetId()).containsExactly(91002L);
    }

    @Test
    void 论剑V3战前变阵_免费预设与无备选项自动跳过且非法备宠或错季印开局拒收() {
        RankedPresetTable charPreset = tables.rankedPreset().find(101).orElseThrow();
        RankedPresetTable petA = tables.rankedPreset().find(201).orElseThrow();
        SeasonSealEffect s1 = makeSeal(1, 1001, 7, "一季印");
        SeasonSealEffect s2WrongSeason = makeSeal(2, 2001, 7, "二季印");

        // 1. 正式排位但全员都无备选项 -> 不开启变阵期
        CreateBattleRequest noChoiceReq = CreateBattleRequest.newBuilder()
                .setBattleId(9602L).setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_PVP_TEAM_VALUE)
                .setSeed(102L).setRankedContext(rankedCtx(1))
                .addPlayers(playerWithRealPet(5201L, 93001L, 0, 200L, charPreset, petA))
                .addPlayers(playerWithRealPet(5202L, 93002L, 1, 200L, charPreset, petA))
                .build();
        TurnBattleEngine noChoice = ((BattleStart.Started) TurnBattleEngine.start(noChoiceReq, data)).engine();
        assertThat(noChoice.isInPrepPhase()).isFalse();

        // 2. 免费预设娱乐场即使带候选印也不进入变阵期
        CreateBattleRequest freePresetReq = CreateBattleRequest.newBuilder()
                .setBattleId(9603L).setBattleConfigId(5)
                .setMatchMode(MatchMode.MATCH_MODE_POLARITY_5V5_VALUE)
                .setSeed(103L).setRankedContext(freePresetCtx(1))
                .addPlayers(playerWithTempPet(5201L, 0, 200L, charPreset, petA).toBuilder()
                        .addAvailableSeasonSeals(s1).addAvailableSeasonSeals(makeSeal(1, 1002, 8, "二印")))
                .addPlayers(playerWithTempPet(5202L, 1, 200L, charPreset, petA))
                .build();
        TurnBattleEngine freePreset = ((BattleStart.Started) TurnBattleEngine.start(freePresetReq, data)).engine();
        assertThat(freePreset.isInPrepPhase()).isFalse();

        // 3. 候选赛季印赛季归属不匹配 -> 开局拒收
        CreateBattleRequest wrongSeasonReq = noChoiceReq.toBuilder()
                .setBattleId(9604L)
                .setPlayers(0, noChoiceReq.getPlayers(0).toBuilder()
                        .setActiveSeasonId(1)
                        .addAvailableSeasonSeals(s2WrongSeason))
                .build();
        assertThat(TurnBattleEngine.start(wrongSeasonReq, data)).isInstanceOf(BattleStart.Rejected.class);

        // 4. 备宠与场上已有 pet_id 跨场重复 -> 开局拒收
        CreateBattleRequest dupStandbyReq = noChoiceReq.toBuilder()
                .setBattleId(9605L)
                .setPlayers(0, noChoiceReq.getPlayers(0).toBuilder()
                        .addStandbyPets(noChoiceReq.getPlayers(1).getPets(0).toBuilder().setOwnerPlayerId(5201L)))
                .build();
        assertThat(TurnBattleEngine.start(dupStandbyReq, data)).isInstanceOf(BattleStart.Rejected.class);
    }
}
