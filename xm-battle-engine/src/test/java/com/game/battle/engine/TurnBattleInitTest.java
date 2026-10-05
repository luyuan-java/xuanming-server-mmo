package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPet;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.petActor;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.MONSTER_ID;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.standard;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleActorState;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleActionType;
import com.game.proto.eBattleActorType;
import org.junit.jupiter.api.Test;

/**
 * 开局（规格 §1）：外观透传、队伍人数上限、阵位、宝宝入场与局内号、actor_id 命名空间，以及 Java 的开局拒绝原因（D1）。
 *
 * <p>逐条移植基线 {@code turn_battle_engine_test.cpp} 的对应用例，每个方法上方注明基线用例名与行号；断言都是【C++ 断言】级，
 * 标「Java 追加」的是规格要求、基线没有的断言（多为 D1 / D2 的有意差异）。
 */
class TurnBattleInitTest {

    private static final int PVE_SOLO = BattleConstants.MATCH_MODE_PVE_SOLO;
    private static final int PVE_TEAM = BattleConstants.MATCH_MODE_PVE_TEAM;
    private static final int PVP_1V1 = 3;

    // 基线 AppearanceIdentitySurvivesBattleStateSnapshots（test.cpp:247-262）【C++ 断言】
    @Test
    void 外观身份原样进快照() {
        CreateBattleRequest.Builder request = request(9010, PVE_SOLO, 42);
        BattlePlayerSnapshot.Builder player = addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 50, 120);
        player.setAppearanceId("04_mountain_guardian_boy");
        player.setClassId(3);
        player.setGender(1);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 入场、断线恢复与观战共用快照，不能从职业或局内 actor_id 猜外观
        BattleActorState actor = stateActor(engine.buildStateSnapshot(), PLAYER_A);
        assertThat(actor).isNotNull();
        assertThat(actor.getAppearanceId()).isEqualTo("04_mountain_guardian_boy");
        assertThat(actor.getClassId()).isEqualTo(3);
        assertThat(actor.getGender()).isEqualTo(1);
    }

    // 基线 InitializeEnforcesTeamSizeLimit（test.cpp:878-898）【C++ 断言】；Java 追加：拒绝原因是 TEAM_OVERSIZE
    @Test
    void 单队超过5人拒绝开局_恰好5人放行() {
        // 单队 6 人：超上限拒绝建房
        CreateBattleRequest.Builder oversize = request(9026, PVE_TEAM, 1);
        for (long offset = 0; offset < BattleConstants.MAX_BATTLE_TEAM_SIZE + 1; offset++) {
            addPlayer(oversize, PLAYER_A + offset, 0, 1000, 1000, 0, 100, 0, 120);
        }
        BattleStart rejected = TurnBattleEngine.start(oversize.build(), standard());
        assertThat(rejected).isInstanceOf(BattleStart.Rejected.class);
        assertThat(((BattleStart.Rejected) rejected).reason()).isEqualTo(InitRejection.TEAM_OVERSIZE);

        // 单队 5 人：恰在上限，放行
        CreateBattleRequest.Builder full = request(9027, PVE_TEAM, 1);
        for (long offset = 0; offset < BattleConstants.MAX_BATTLE_TEAM_SIZE; offset++) {
            addPlayer(full, PLAYER_A + offset, 0, 1000, 1000, 0, 100, 0, 120);
        }
        assertThat(TurnBattleEngine.start(full.build(), standard())).isInstanceOf(BattleStart.Started.class);
    }

    // 基线 PresentationFormationSlotIncrementsPerTeamInSnapshotOrder（test.cpp:1449-1466）【C++ 断言】
    @Test
    void 阵位按快照顺序在本队内递增_怪物方独立计数() {
        CreateBattleRequest.Builder request = request(9102, PVE_TEAM, 1);
        addPlayer(request, PLAYER_A, 0, 500, 500, 20, 10, 0, 120);
        addPlayer(request, PLAYER_B, 0, 500, 500, 20, 10, 0, 108);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        BattleStateS2C state = engine.buildStateSnapshot();
        BattleActorState actorA = stateActor(state, PLAYER_A);
        BattleActorState actorB = stateActor(state, PLAYER_B);
        BattleActorState monster = stateActor(state, MONSTER_ID);
        assertThat(actorA).isNotNull();
        assertThat(actorB).isNotNull();
        assertThat(monster).isNotNull();
        assertThat(actorA.getFormationSlot()).isZero();
        assertThat(actorB.getFormationSlot()).isEqualTo(1);
        assertThat(monster.getFormationSlot()).as("怪物队从 0 起").isZero();
    }

    // 基线 PetJoinsOwnerTeamAndActsWithoutClientAction（test.cpp:1551-1585）【C++ 断言】；
    // Java 追加：引擎返回的 action_order 恒为空（由节点从 lastActionOrder() 透传，规格 §7.2）
    @Test
    void 宝宝与主人同队_不用提交也会出手() {
        CreateBattleRequest.Builder request = request(9401, PVE_SOLO, 31337);
        BattlePlayerSnapshot.Builder owner = addPlayer(request, PLAYER_A, 0, 1000, 1000, 5, 0, 0, 120);
        // 宝宝比主人快：出手序里应排在主人之前
        addPet(owner, TestTables.PET_A, 400, 400, 4, 360);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        BattleActorState pet = petActor(engine.buildStateSnapshot(), TestTables.PET_A);
        assertThat(pet).isNotNull();
        long petActorId = pet.getActorId();
        assertThat(petActorId).as("第一只宝宝拿局内号段的第 0 号").isEqualTo(BattleConstants.PET_ACTOR_ID_BASE);
        assertThat(pet.getTeamIndex()).isZero();
        assertThat(pet.getOwnerPlayerId()).isEqualTo(PLAYER_A);
        // 宝宝无客户端行动权：标 auto，不进就绪判定 —— 主人一提交行动就能结算
        assertThat(pet.getIsAuto()).isTrue();

        assertThat(engine.submitAction(PLAYER_A, action(eBattleActionType.BATTLE_ACTION_ATTACK, MONSTER_ID))).isTrue();
        var result = engine.resolveCurrentRound();
        assertThat(result.getActionOrderList()).as("Java 追加：引擎不填 action_order").isEmpty();
        var order = engine.lastActionOrder();
        assertThat(order.size()).isGreaterThanOrEqualTo(2);
        assertThat(order.get(0)).as("速度 360 > 120").isEqualTo(petActorId);

        // 宝宝这一回合确实出了手（普攻由默认行动路径代打）
        boolean petAttacked = result.getEventsList().stream()
                .anyMatch(e -> e.getEventType() == com.game.proto.eBattleEventType.BATTLE_EVENT_ATTACK
                        && e.getSourceId() == petActorId);
        assertThat(petAttacked).isTrue();
    }

    // 基线 PetWithSameIdAsItsOwnerDoesNotCollide（test.cpp:1629-1653）【C++ 断言】
    @Test
    void 宝宝与主人同号不撞车_结算按真实pet_id归还() {
        long sameId = 5;
        CreateBattleRequest.Builder request = request(9404, PVE_SOLO, 7);
        BattlePlayerSnapshot.Builder owner = addPlayer(request, sameId, 0, 1000, 1000, 5, 0, 0, 120);
        addPet(owner, sameId, 400, 400, 4, 360);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        BattleStateS2C state = engine.buildStateSnapshot();
        BattleActorState player = stateActor(state, sameId);
        BattleActorState pet = petActor(state, sameId);
        assertThat(player).isNotNull();
        assertThat(pet).isNotNull();
        assertThat(player.getActorType()).isEqualTo(eBattleActorType.BATTLE_ACTOR_TYPE_PLAYER);
        assertThat(pet.getActorId()).isNotEqualTo(player.getActorId());

        // 结算按真实 pet_id 归还，而不是局内号
        assertThat(engine.submitAction(sameId, action(eBattleActionType.BATTLE_ACTION_DEFEND))).isTrue();
        engine.resolveCurrentRound();
        BattleSettlementData settlement = engine.buildSettlement(sameId);
        assertThat(settlement.getPetsCount()).isEqualTo(1);
        assertThat(settlement.getPets(0).getPetId()).isEqualTo(sameId);
    }

    // 基线 PlayerWithLegacyMonsterBaseIdDoesNotShareActorWithMonster（test.cpp:1655-1675）【C++ 断言】
    @Test
    void 第100万号玩家不与0号怪共用actor_id() {
        long millionthPlayer = 1000000;
        CreateBattleRequest.Builder request = request(9405, PVE_SOLO, 7);
        addPlayer(request, millionthPlayer, 0, 1000, 1000, 5, 0, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        BattleStateS2C state = engine.buildStateSnapshot();
        long holders = state.getActorsList().stream().filter(a -> a.getActorId() == millionthPlayer).count();
        assertThat(holders).as("只有玩家自己").isEqualTo(1);
        BattleActorState monster = stateActor(state, MONSTER_ID);
        assertThat(monster).isNotNull();
        assertThat(monster.getActorType()).isEqualTo(eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER);
    }

    // 基线 PlayerIdInEngineLocalNamespaceIsRejected（test.cpp:1677-1683）【C++ 断言】：基线 Initialize 返回 false；
    // Java 得到 Rejected(RESERVED_PLAYER_ID)（D1）
    @Test
    void player_id落在局内号保留段拒绝开局() {
        CreateBattleRequest.Builder request = request(9406, PVE_SOLO, 7);
        addPlayer(request, BattleConstants.ENGINE_LOCAL_ACTOR_ID_FLAG | 42, 0, 1000, 1000, 5, 0, 0, 120);
        BattleStart start = TurnBattleEngine.start(request.build(), standard());
        assertThat(start).isInstanceOf(BattleStart.Rejected.class);
        BattleStart.Rejected rejected = (BattleStart.Rejected) start;
        assertThat(rejected.reason()).isEqualTo(InitRejection.RESERVED_PLAYER_ID);
        assertThat(rejected.reason().baselineLogsError()).isTrue();
    }

    // ---- Java 追加：其余开局拒绝原因（规格 §1.4–§1.6、§10.3；每个场景只触发一种原因） ----

    private static InitRejection rejectionOf(CreateBattleRequest.Builder request) {
        BattleStart start = TurnBattleEngine.start(request.build(), standard());
        assertThat(start).as("应当拒绝开局").isInstanceOf(BattleStart.Rejected.class);
        BattleStart.Rejected rejected = (BattleStart.Rejected) start;
        assertThat(rejected.detail()).isNotNull();
        return rejected.reason();
    }

    // 规格 §1.4 第 2 步（engine.cpp:47）
    @Test
    void battle_id为0或没有玩家拒绝开局() {
        CreateBattleRequest.Builder noBattleId = request(0, PVE_SOLO, 1);
        addPlayer(noBattleId, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        assertThat(rejectionOf(noBattleId)).isEqualTo(InitRejection.MISSING_BATTLE_ID);

        assertThat(rejectionOf(request(9500, PVE_SOLO, 1))).isEqualTo(InitRejection.NO_PLAYERS);
    }

    // 规格 §1.5（engine.cpp:119-131）
    @Test
    void 玩家id为0_队伍越界_重复参战拒绝开局() {
        CreateBattleRequest.Builder zeroId = request(9501, PVE_SOLO, 1);
        addPlayer(zeroId, 0, 0, 1000, 1000, 0, 100, 0, 120);
        assertThat(rejectionOf(zeroId)).isEqualTo(InitRejection.INVALID_PLAYER);

        CreateBattleRequest.Builder badTeam = request(9502, PVP_1V1, 1);
        addPlayer(badTeam, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        addPlayer(badTeam, PLAYER_B, 2, 1000, 1000, 0, 100, 0, 120);
        assertThat(rejectionOf(badTeam)).isEqualTo(InitRejection.INVALID_PLAYER);

        CreateBattleRequest.Builder duplicate = request(9503, PVE_TEAM, 1);
        addPlayer(duplicate, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        addPlayer(duplicate, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        assertThat(rejectionOf(duplicate)).isEqualTo(InitRejection.DUPLICATE_PLAYER);
    }

    // 规格 §1.6（engine.cpp:179-216）
    @Test
    void 宝宝id为0_重复宝宝_归属不符拒绝开局() {
        CreateBattleRequest.Builder zeroPet = request(9504, PVE_SOLO, 1);
        addPet(addPlayer(zeroPet, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120), 0, 400, 400, 4, 360);
        assertThat(rejectionOf(zeroPet)).isEqualTo(InitRejection.PET_ID_ZERO);

        CreateBattleRequest.Builder duplicatePet = request(9505, PVE_TEAM, 1);
        addPet(addPlayer(duplicatePet, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120), TestTables.PET_A, 400, 400, 4, 360);
        addPet(addPlayer(duplicatePet, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120), TestTables.PET_A, 400, 400, 4, 360);
        assertThat(rejectionOf(duplicatePet)).isEqualTo(InitRejection.DUPLICATE_PET);
        assertThat(InitRejection.DUPLICATE_PET.baselineLogsError()).isTrue();

        CreateBattleRequest.Builder wrongOwner = request(9506, PVE_SOLO, 1);
        addPet(addPlayer(wrongOwner, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120), TestTables.PET_A, 400, 400, 4, 360)
                .setOwnerPlayerId(PLAYER_B);
        assertThat(rejectionOf(wrongOwner)).isEqualTo(InitRejection.PET_OWNER_MISMATCH);
        assertThat(InitRejection.PET_OWNER_MISMATCH.baselineLogsError()).isTrue();
    }

    // 规格 §1.6：快照里宝宝的 owner_player_id 为 0 时只认所在快照（engine.cpp:211-217）
    @Test
    void 宝宝owner为0时归属取所在快照() {
        CreateBattleRequest.Builder request = request(9507, PVE_SOLO, 1);
        addPet(addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120), TestTables.PET_A, 400, 400, 4, 360)
                .setOwnerPlayerId(0);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        BattleActorState pet = petActor(engine.buildStateSnapshot(), TestTables.PET_A);
        assertThat(pet).isNotNull();
        assertThat(pet.getOwnerPlayerId()).isEqualTo(PLAYER_A);
    }

    // 规格 §1.4 第 9 步（engine.cpp:97-104）：PVP 只有一方；team 0 记 A 方，其余记 B 方
    @Test
    void 只有一方单位拒绝开局() {
        CreateBattleRequest.Builder oneSided = request(9508, PVP_1V1, 1);
        addPlayer(oneSided, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        addPlayer(oneSided, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120);
        assertThat(rejectionOf(oneSided)).isEqualTo(InitRejection.ONE_SIDED);
        assertThat(InitRejection.ONE_SIDED.baselineLogsError()).isFalse();
    }

    // 规格 §1.3 / §1.7：兜底怪的字段来源与参考等级（怪物等级 = max(1, 所有玩家快照等级)，玩家等级 0 原样保留）
    @Test
    void 兜底怪按玩家人数生成_等级取玩家最高等级() {
        CreateBattleRequest.Builder request = request(9509, PVE_TEAM, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120).setLevel(0);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120).setLevel(17);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        BattleStateS2C state = engine.buildStateSnapshot();
        assertThat(state.getActorsList()).extracting(BattleActorState::getActorId)
                .containsExactly(PLAYER_A, PLAYER_B, MONSTER_ID, MONSTER_ID + 1);
        assertThat(stateActor(state, PLAYER_A).getLevel()).as("玩家等级原样保留，可能为 0").isZero();
        BattleActorState monster = stateActor(state, MONSTER_ID + 1);
        assertThat(monster.getLevel()).isEqualTo(17);
        assertThat(monster.getTeamIndex()).isEqualTo(1);
        assertThat(monster.getName()).isEqualTo("野怪");
        assertThat(monster.getMonsterTableId()).isZero();
        assertThat(monster.getFormationSlot()).isEqualTo(1);
        assertThat(monster.getAttributes().getHealth()).isEqualTo(BattleConstants.MONSTER_DEFAULT_HEALTH);
        assertThat(monster.getMaxHealth()).isEqualTo(BattleConstants.MONSTER_DEFAULT_HEALTH);
        assertThat(monster.getIsAuto()).isFalse();
    }

    // 规格 §1.3：max_health / max_mana 缺失时取当前值（FallbackMax，engine.cpp:27-29）
    @Test
    void 上限缺失时取当前值() {
        CreateBattleRequest.Builder request = request(9510, PVE_SOLO, 1);
        BattlePlayerSnapshot.Builder player = addPlayer(request, PLAYER_A, 0, 640, 0, 0, 100, 0, 120);
        player.getBaseAttributesBuilder().setMana(55);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        BattleActorState actor = stateActor(engine.buildStateSnapshot(), PLAYER_A);
        assertThat(actor.getMaxHealth()).isEqualTo(640);
        assertThat(actor.getMaxMana()).isEqualTo(55);
    }
}
