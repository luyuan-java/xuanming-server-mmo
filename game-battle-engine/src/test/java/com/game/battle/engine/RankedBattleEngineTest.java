package com.game.battle.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BaseAttributesComp;
import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleParticipationFact;
import com.game.proto.BattlePetSnapshot;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.RankedMatchContext;
import com.game.proto.TurnResultS2C;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.MatchMode;
import com.game.table.ConfigTables;
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
}
