package com.game.battle.engine;

import static com.game.battle.engine.EngineTestSupport.describeAll;
import static com.game.battle.engine.TestBattles.action;
import static com.game.battle.engine.TestBattles.addPlayer;
import static com.game.battle.engine.TestBattles.countEvents;
import static com.game.battle.engine.TestBattles.request;
import static com.game.battle.engine.TestBattles.stateActor;
import static com.game.battle.engine.TestTables.BUFF_IMMUNE_POISON;
import static com.game.battle.engine.TestTables.BUFF_POISON;
import static com.game.battle.engine.TestTables.BUFF_SILENCE;
import static com.game.battle.engine.TestTables.MONSTER_ID;
import static com.game.battle.engine.TestTables.PLAYER_A;
import static com.game.battle.engine.TestTables.PLAYER_B;
import static com.game.battle.engine.TestTables.SKILL_NUKE;
import static com.game.battle.engine.TestTables.standard;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_DEFEND;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_SKILL;
import static com.game.proto.eBattleEventType.BATTLE_EVENT_BUFF_ADD;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleBuffEntry;
import com.game.proto.CreateBattleRequest;
import com.game.proto.TurnResultS2C;
import org.junit.jupiter.api.Test;

/**
 * 测试钩子（规格 §13.8）：包私有的 {@code addBuffForTest} 对应基线 {@code turn_battle_engine_test.cpp:26-38} 的友元
 * {@code TurnBattleEngineDeathTestAccess::AddBuff} —— 找不到单位返回 false；找到就以深度 0 走完整的挂载流程
 * （免疫、驱散、叠层、子 buff 都照常），产出的事件丢弃，不耗随机数。期望值按基线源码手算（Java 追加）。
 */
class TurnBattleHookTest {

    private static final int PVE_SOLO = BattleConstants.MATCH_MODE_PVE_SOLO;
    private static final int PVP_1V1 = 3;

    private static TurnBattleEngine soloEngine() {
        CreateBattleRequest.Builder request = request(9650, PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 1000, 1000, 0, 100, 0, 120);
        return TestBattles.start(request, standard());
    }

    @Test
    void 找不到单位返回false且不改状态() {
        TurnBattleEngine engine = soloEngine();
        byte[] before = EngineTestSupport.deterministicBytes(engine.buildStateSnapshot());
        assertThat(engine.addBuffForTest(424242, BUFF_POISON, PLAYER_A)).isFalse();
        assertThat(engine.addBuffForTest(0, BUFF_POISON, PLAYER_A)).isFalse();
        assertThat(EngineTestSupport.deterministicBytes(engine.buildStateSnapshot())).isEqualTo(before);
    }

    @Test
    void 挂载的条目可见_事件被丢弃_消耗实例号_不耗随机数() {
        TurnBattleEngine engine = soloEngine();
        assertThat(engine.addBuffForTest(MONSTER_ID, BUFF_POISON, PLAYER_A)).isTrue();
        assertThat(engine.addBuffForTest(PLAYER_A, BUFF_SILENCE, MONSTER_ID)).isTrue();
        assertThat(engine.rngDrawsForTest()).isZero();
        assertThat(stateActor(engine.buildStateSnapshot(), MONSTER_ID).getBuffsList()).containsExactly(
                BattleBuffEntry.newBuilder().setBuffId(1).setBuffTableId(BUFF_POISON).setLayer(1).setRemainRounds(2)
                        .setCasterId(PLAYER_A).build());
        assertThat(stateActor(engine.buildStateSnapshot(), PLAYER_A).getBuffsList()).containsExactly(
                BattleBuffEntry.newBuilder().setBuffId(2).setBuffTableId(BUFF_SILENCE).setLayer(1).setRemainRounds(2)
                        .setCasterId(MONSTER_ID).build());

        // 钩子当时产出的 BUFF_ADD 不进任何一回合的事件流；回合末的毒 tick 照常
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_DEFEND));
        TurnResultS2C result = engine.resolveCurrentRound();
        assertThat(countEvents(result, BATTLE_EVENT_BUFF_ADD)).isZero();
        assertThat(describeAll(result)).contains("g4 BUFF_TICK 5001->M0 buff201 v10 hp290");
    }

    @Test
    void 钩子走完整挂载流程_叠层与免疫照常() {
        CreateBattleRequest.Builder request = request(9651, PVP_1V1, 1);
        addPlayer(request, PLAYER_A, 0, 500, 500, 0, 100, 0, 120);
        addPlayer(request, PLAYER_B, 1, 500, 500, 0, 100, 0, 60)
                .addBuffs(BattleBuffEntry.newBuilder().setBuffId(900).setBuffTableId(BUFF_IMMUNE_POISON).setLayer(1));
        TurnBattleEngine engine = TestBattles.start(request, standard());

        // 免疫挡下：找得到单位，所以仍返回 true，但不落地
        assertThat(engine.addBuffForTest(PLAYER_B, BUFF_POISON, PLAYER_A)).isTrue();
        assertThat(stateActor(engine.buildStateSnapshot(), PLAYER_B).getBuffsList())
                .extracting(BattleBuffEntry::getBuffTableId).containsExactly(BUFF_IMMUNE_POISON);

        // 同表同施法者：叠层，不新建条目
        assertThat(engine.addBuffForTest(PLAYER_A, BUFF_POISON, PLAYER_B)).isTrue();
        assertThat(engine.addBuffForTest(PLAYER_A, BUFF_POISON, PLAYER_B)).isTrue();
        assertThat(stateActor(engine.buildStateSnapshot(), PLAYER_A).getBuffsList()).containsExactly(
                BattleBuffEntry.newBuilder().setBuffId(901).setBuffTableId(BUFF_POISON).setLayer(2).setRemainRounds(2)
                        .setCasterId(PLAYER_B).build());
    }

    @Test
    void 挂在已死单位身上无效() {
        TurnBattleEngine engine = soloEngine();
        engine.submitAction(PLAYER_A, action(BATTLE_ACTION_SKILL, MONSTER_ID, SKILL_NUKE));
        engine.resolveCurrentRound();
        assertThat(stateActor(engine.buildStateSnapshot(), MONSTER_ID).getIsDead()).isTrue();

        assertThat(engine.addBuffForTest(MONSTER_ID, BUFF_POISON, PLAYER_A)).as("单位还在 actors 里").isTrue();
        assertThat(stateActor(engine.buildStateSnapshot(), MONSTER_ID).getBuffsList()).isEmpty();
    }
}
