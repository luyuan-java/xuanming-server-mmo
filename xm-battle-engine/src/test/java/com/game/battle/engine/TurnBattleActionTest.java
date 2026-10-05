package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPet;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.firstEvent;
import static com.game.battle.engine.TestBattles.petActor;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.MONSTER_ID;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.PLAYER_C;
import static com.game.battle.engine.TestTables.SKILL_DAMAGE;
import static com.game.battle.engine.TestTables.SKILL_NUKE;
import static com.game.battle.engine.TestTables.SUCCESS;
import static com.game.battle.engine.TestTables.standard;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_ATTACK;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_FLEE;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_NONE;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_ATTACK;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_DEFEND;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_FLEE;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_ONGOING;
import static com.game.proto.eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.BattleAction;
import com.game.proto.BattleActorState;
import com.game.proto.BattleEventItem;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleStateS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import com.game.table.CommonErrorTip;
import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 行动提交、校验、挂机与待行动名单（规格 §2、§7.1 的 pending_actor_ids）。
 *
 * <p>逐条移植基线 {@code turn_battle_engine_test.cpp} 的对应用例（【C++ 断言】）。挂机开关成功码按 D2 取 1000（基线为 0），
 * 涉及处都在注释里写明。标「Java 追加」的断言来自规格 §2 / §11.4，基线没有对应用例。
 */
class TurnBattleActionTest {

    private static final int PVE_SOLO = BattleConstants.MATCH_MODE_PVE_SOLO;
    private static final int PVE_TEAM = BattleConstants.MATCH_MODE_PVE_TEAM;
    private static final int PVP_1V1 = 3;
    private static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    private static final int ENTITY_INVALID = CommonErrorTip.common_error.kThisEntityIsInvalid_VALUE;

    // 基线 StateSnapshotTracksPendingActorsAndRoundIndex（test.cpp:707-726）【C++ 断言】
    @Test
    void 快照的待行动名单与回合号随提交和结算变化() {
        CreateBattleRequest.Builder request = request(9019, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        BattleStateS2C state = engine.buildStateSnapshot();
        assertThat(state.getRoundIndex()).isEqualTo(1);
        assertThat(state.getPendingActorIdsList()).containsExactly(PLAYER_A);

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        state = engine.buildStateSnapshot();
        assertThat(state.getPendingActorIdsCount()).isZero();

        engine.resolveCurrentRound();
        state = engine.buildStateSnapshot();
        assertThat(state.getRoundIndex()).isEqualTo(2);
        assertThat(state.getPendingActorIdsCount()).as("新回合重新待行动").isEqualTo(1);
    }

    // 基线 AutoActorCountsAsReadyAndActsWithDefaultAttack（test.cpp:732-753）【C++ 断言】；挂机成功码 1000（D2，基线 0）
    @Test
    void 挂机玩家视为就绪并按默认普攻出手() {
        CreateBattleRequest.Builder request = request(9020, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 未提交且未挂机：不就绪；开挂机后无需提交即就绪（提前结算的判据）
        assertThat(engine.allPlayersReady()).isFalse();
        assertThat(engine.setActorAuto(PLAYER_A, true)).isEqualTo(SUCCESS);
        assertThat(engine.allPlayersReady()).isTrue();

        // 结算走默认普攻代打：玩家速度 120 > 怪物默认 60，首事件是玩家对怪的普攻
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(result.getEventsCount()).isGreaterThanOrEqualTo(1);
        assertThat(result.getEvents(0).getEventType()).isEqualTo(BATTLE_EVENT_ATTACK);
        assertThat(result.getEvents(0).getSourceId()).isEqualTo(PLAYER_A);
        assertThat(result.getEvents(0).getTargetId()).isEqualTo(MONSTER_ID);

        // 关闭挂机：回到待提交状态
        assertThat(engine.setActorAuto(PLAYER_A, false)).isEqualTo(SUCCESS);
        assertThat(engine.allPlayersReady()).isFalse();
    }

    // 基线 SetActorAutoRejectsMonsterDeadAndFledActors（test.cpp:755-813）【C++ 断言】；挂机成功码 1000（D2，基线 0）
    @Test
    void 挂机开关拒绝怪物_不存在_已死与已逃的单位() {
        // 怪物 / 不存在的单位：参数错误
        {
            CreateBattleRequest.Builder request = request(9021, PVE_SOLO, 1);
            addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
            TurnBattleEngine engine = TestBattles.start(request, standard());
            assertThat(engine.setActorAuto(MONSTER_ID, true)).isEqualTo(INVALID_PARAMETER);
            assertThat(engine.setActorAuto(999999, true)).isEqualTo(INVALID_PARAMETER);
        }

        // 死亡玩家：1v2 秒掉 B 后战斗仍进行中，B 被拒，存活的 C 可开
        {
            CreateBattleRequest.Builder request = request(9022, PVP_1V1, 1);
            addPlayer(request, PLAYER_A, 0, 500, 500, 0, 100, 0, 120);
            // 无甲、250 血：PVP 下 nuke 1000 × 0.3 = 300，仍一击可杀
            addPlayer(request, PLAYER_B, 1, 250, 250, 0, 0, 0, 60);
            addPlayer(request, PLAYER_C, 1, 500, 500, 0, 100, 0, 60);
            TurnBattleEngine engine = TestBattles.start(request, standard());

            engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, PLAYER_B, SKILL_NUKE));
            engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
            engine.submitAction(PLAYER_C, action(BATTLE_ACTION_DEFEND));
            engine.resolveCurrentRound();

            assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_ONGOING);
            assertThat(engine.setActorAuto(PLAYER_B, true)).isEqualTo(ENTITY_INVALID);
            assertThat(engine.setActorAuto(PLAYER_C, true)).isEqualTo(SUCCESS);
        }

        // 已逃玩家：扫种子找一局「A 首回合逃跑成功、B 仍在场」（成功率封顶 0.95，32 枚种子内必现）
        {
            boolean verified = false;
            for (long seed = 1; seed <= 32 && !verified; seed++) {
                CreateBattleRequest.Builder request = request(9023, PVE_TEAM, seed);
                addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 600); // 高速，逃跑成功率高
                addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120);
                TurnBattleEngine engine = TestBattles.start(request, standard());

                engine.submitAction(PLAYER_A, action(BATTLE_ACTION_FLEE));
                engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
                TurnResultS2C result = engine.resolveCurrentRound();
                BattleEventItem flee = firstEvent(result, BATTLE_EVENT_FLEE);
                assertThat(flee).isNotNull();
                if (!flee.getSuccess()) {
                    continue;
                }
                assertThat(engine.outcome()).as("B 还在，战斗未结束").isEqualTo(BATTLE_OUTCOME_ONGOING);
                assertThat(engine.setActorAuto(PLAYER_A, true)).isEqualTo(ENTITY_INVALID);
                verified = true;
            }
            assertThat(verified).isTrue();
        }
    }

    private static byte[] runAutoOrManual(long seed, boolean useAuto) {
        CreateBattleRequest.Builder request = request(9024, PVE_SOLO, seed);
        // 高暴击逼引擎大量消耗 RNG，任何随机路径分叉都会导致字节流不一致
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 4, 100, 50, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        if (useAuto) {
            assertThat(engine.setActorAuto(PLAYER_A, true)).isEqualTo(SUCCESS); // D2：基线为 0
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (int round = 0; round < 10 && engine.outcome() == BATTLE_OUTCOME_ONGOING; round++) {
            if (!useAuto) {
                engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, 0));
            }
            TurnResultS2C result = engine.resolveCurrentRound();
            for (BattleEventItem event : result.getEventsList()) {
                stream.writeBytes(event.toByteArray());
                stream.write('|');
            }
        }
        stream.writeBytes(engine.buildSettlement(PLAYER_A).toByteArray());
        return stream.toByteArray();
    }

    // 基线 AutoModeMatchesManualDefaultAttackEventStream（test.cpp:815-845）【C++ 断言】
    @Test
    void 挂机与手动提交ATTACK0的事件流与结算逐字节相同() {
        assertThat(runAutoOrManual(42, true)).isEqualTo(runAutoOrManual(42, false));
        assertThat(runAutoOrManual(20260831, true)).isEqualTo(runAutoOrManual(20260831, false));
    }

    // 基线 SnapshotMarksAutoActorAndExcludesFromPending（test.cpp:847-872）【C++ 断言】；挂机成功码 1000（D2，基线 0）
    @Test
    void 快照标记挂机单位并从待行动名单排除() {
        CreateBattleRequest.Builder request = request(9025, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 500, 500, 0, 100, 0, 120);
        addPlayer(request, PLAYER_B, 1, 500, 500, 0, 100, 0, 60);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        assertThat(engine.buildStateSnapshot().getPendingActorIdsCount()).isEqualTo(2);

        // A 开挂机：快照携带 is_auto，待行动名单只剩手动的 B
        assertThat(engine.setActorAuto(PLAYER_A, true)).isEqualTo(SUCCESS);
        BattleStateS2C state = engine.buildStateSnapshot();
        BattleActorState autoActor = stateActor(state, PLAYER_A);
        assertThat(autoActor).isNotNull();
        assertThat(autoActor.getIsAuto()).isTrue();
        BattleActorState manualActor = stateActor(state, PLAYER_B);
        assertThat(manualActor).isNotNull();
        assertThat(manualActor.getIsAuto()).isFalse();
        assertThat(state.getPendingActorIdsList()).containsExactly(PLAYER_B);

        // 关闭挂机：重回待行动名单
        assertThat(engine.setActorAuto(PLAYER_A, false)).isEqualTo(SUCCESS);
        assertThat(engine.buildStateSnapshot().getPendingActorIdsCount()).isEqualTo(2);
    }

    // 基线 PetIsNotControllableByClient（test.cpp:1587-1606）【C++ 断言】：基线断言挂机开关返回值 != 0；
    // Java 追加：精确为 1005（宝宝不是 PLAYER，规格 §2.3 第 2 步）
    @Test
    void 宝宝不能被客户端操控() {
        CreateBattleRequest.Builder request = request(9402, PVE_SOLO, 4242);
        BattlePlayerSnapshot.Builder owner = addPlayer(request, PLAYER_A, 0, 1000, 1000, 5, 0, 0, 120);
        addPet(owner, TestTables.PET_A, 400, 400, 4, 360);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        BattleActorState petBefore = petActor(engine.buildStateSnapshot(), TestTables.PET_A);
        assertThat(petBefore).isNotNull();
        long petActorId = petBefore.getActorId();

        // 对宝宝提交行动不落账（submitAction 只收 PLAYER），也不能给它开关自动战斗
        assertThat(engine.submitAction(petActorId, action(BATTLE_ACTION_DEFEND))).isFalse();
        int code = engine.setActorAuto(petActorId, false);
        assertThat(code).isNotEqualTo(SUCCESS);
        assertThat(code).as("Java 追加").isEqualTo(INVALID_PARAMETER);

        BattleActorState pet = petActor(engine.buildStateSnapshot(), TestTables.PET_A);
        assertThat(pet).isNotNull();
        assertThat(pet.getIsAuto()).isTrue();
    }

    // ---- Java 追加（规格 §2、§11.4） ----

    // 规格 §2.1：最后一次合法提交生效；之后的不合法提交不清掉已收下的行动，函数仍返回就绪态（基线行为，§11.1 第 3 条）
    @Test
    void 最后一次合法提交生效_不合法的重提交不清掉旧行动() {
        CreateBattleRequest.Builder request = request(9600, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, MONSTER_ID))).isTrue();
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND))).isTrue();
        // 不存在的技能：1001，不落账，但先前的 DEFEND 仍在
        BattleAction invalid = action(BATTLE_ACTION_SKILL, MONSTER_ID, 9999);
        assertThat(engine.validateAction(PLAYER_A, invalid)).isEqualTo(CommonErrorTip.common_error.kInvalidTableId_VALUE);
        assertThat(engine.submitAction(PLAYER_A, invalid)).isTrue();
        assertThat(engine.buildStateSnapshot().getPendingActorIdsCount()).isZero();

        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(result.getEvents(0).getEventType()).isEqualTo(BATTLE_EVENT_DEFEND);
        assertThat(result.getEvents(0).getSourceId()).isEqualTo(PLAYER_A);
    }

    // 规格 §2.5：NONE 与未知的 action_type 一律 1005（Java 用 getActionTypeValue 分派，与 C++ 开放枚举一致）
    @Test
    void NONE与未知行动类型回1005() {
        CreateBattleRequest.Builder request = request(9601, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_NONE))).isEqualTo(INVALID_PARAMETER);
        BattleAction unknown = BattleAction.newBuilder().setActionTypeValue(99).setTargetId(MONSTER_ID).build();
        assertThat(engine.validateAction(PLAYER_A, unknown)).isEqualTo(INVALID_PARAMETER);
        assertThat(engine.submitAction(PLAYER_A, unknown)).isFalse();
        assertThat(engine.buildStateSnapshot().getPendingActorIdsList()).containsExactly(PLAYER_A);
    }

    // 规格 §2.2 / §2.1：对宝宝、怪物、不存在的 id 校验回 1005、提交不收但返回就绪态
    @Test
    void 对非玩家单位提交不收_返回就绪态() {
        CreateBattleRequest.Builder request = request(9602, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        assertThat(engine.validateAction(MONSTER_ID, action(BATTLE_ACTION_ATTACK, PLAYER_A))).isEqualTo(INVALID_PARAMETER);
        assertThat(engine.validateAction(424242, action(BATTLE_ACTION_DEFEND))).isEqualTo(INVALID_PARAMETER);
        assertThat(engine.submitAction(MONSTER_ID, action(BATTLE_ACTION_DEFEND))).isFalse();
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        // 玩家已就绪后，对不存在的 id 提交照样返回就绪态
        assertThat(engine.submitAction(424242, action(BATTLE_ACTION_DEFEND))).isTrue();
    }

    // 规格 §2.5 与 §3.6：普攻提交不校验目标（指向队友也收），出手时重选敌方
    @Test
    void 普攻指向队友也能提交_出手时重选敌方() {
        CreateBattleRequest.Builder request = request(9603, PVE_TEAM, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 240);
        addPlayer(request, PLAYER_B, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());

        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B))).isEqualTo(SUCCESS);
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_ATTACK, PLAYER_B));
        engine.submitAction(PLAYER_B, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();
        BattleEventItem attack = firstEvent(result, BATTLE_EVENT_ATTACK);
        assertThat(attack.getSourceId()).isEqualTo(PLAYER_A);
        assertThat(attack.getTargetId()).as("同队目标在出手时重选").isIn(MONSTER_ID, MONSTER_ID + 1);
    }

    // 规格 §11.4：战斗结束后 submit / validate / setActorAuto 分别为 false / 1005 / 1005；再结算只有 battle_id、round_index、state
    @Test
    void 战斗结束后拒绝一切行动_再结算没有事件也不耗随机数() {
        CreateBattleRequest.Builder request = request(9604, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_NUKE));
        engine.resolveCurrentRound();
        assertThat(engine.outcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        List<Long> orderAfterLastRound = engine.lastActionOrder();
        long drawsAfterLastRound = engine.rngDrawsForTest();

        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND))).isFalse();
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_DEFEND))).isEqualTo(INVALID_PARAMETER);
        assertThat(engine.setActorAuto(PLAYER_A, true)).isEqualTo(INVALID_PARAMETER);

        TurnResultS2C again = engine.resolveCurrentRound();
        assertThat(again.getBattleId()).isEqualTo(9604);
        assertThat(again.getRoundIndex()).isEqualTo(1);
        assertThat(again.getEventsList()).isEmpty();
        assertThat(again.getState().getRoundIndex()).as("已结束时快照的回合号不再前进").isEqualTo(1);
        assertThat(again.getState().getOutcome()).isEqualTo(BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(again.getState().getPendingActorIdsList()).isEmpty();
        assertThat(engine.lastActionOrder()).isEqualTo(orderAfterLastRound);
        assertThat(engine.rngDrawsForTest()).isEqualTo(drawsAfterLastRound);
        assertThat(engine.buildSettlement(PLAYER_A).getTotalRounds()).isEqualTo(1);
    }

    // 规格 §10.3：lastActionOrder() 是不可变副本；出手序包含回合中途被跳过的单位（engine.h:62-65）
    @Test
    void 出手序是不可变副本_包含中途被跳过的单位() {
        CreateBattleRequest.Builder request = request(9605, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        assertThat(engine.lastActionOrder()).as("还没结算过").isEmpty();

        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_NUKE));
        engine.resolveCurrentRound();
        List<Long> order = engine.lastActionOrder();
        assertThat(order).as("怪物被秒、没出手，仍在出手序里").containsExactly(PLAYER_A, MONSTER_ID);
        assertThatThrownBy(() -> order.add(1L)).isInstanceOf(UnsupportedOperationException.class);
        assertThat(engine.lastActionOrder()).containsExactly(PLAYER_A, MONSTER_ID);
    }

    // 规格 §2.3：挂机开关不耗随机数、不触发结算；校验与快照同样零副作用
    @Test
    void 挂机开关与校验不耗随机数() {
        CreateBattleRequest.Builder request = request(9606, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 50, 120);
        TurnBattleEngine engine = TestBattles.start(request, standard());
        assertThat(engine.setActorAuto(PLAYER_A, true)).isEqualTo(SUCCESS);
        assertThat(engine.setActorAuto(PLAYER_A, false)).isEqualTo(SUCCESS);
        engine.validateAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE));
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_DAMAGE));
        engine.buildStateSnapshot();
        engine.buildSettlement(PLAYER_A);
        engine.selfItems(PLAYER_A);
        assertThat(engine.rngDrawsForTest()).isZero();
        assertThat(engine.buildStateSnapshot().getRoundIndex()).isEqualTo(1);
    }
}
