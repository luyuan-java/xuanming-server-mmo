package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.*;
import static com.game.battle.engine.TestTables.*;
import static com.game.proto.eBattleActionType.BATTLE_ACTION_CAPTURE;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleEventType;
import org.junit.jupiter.api.Test;

/** 捕捉沿正式行动与结算链，目标不会同时计入击杀。 */
class TurnBattleCaptureTest {
    private static MemoryBattleData captureData(int tier) {
        var data = standard();
        data.addDungeon(DUNGEON_CONFIG).setAllowPetCapture(1);
        data.setDungeonMonsters(DUNGEON_CONFIG, 91, 91);
        data.addMonster(91).setHealth(1000).setSpeed(1).setCapturePetTableId(1).setCaptureRate(10000);
        data.addPet(1).setUnlockLevel(1).setAcquisitionTier(tier);
        return data;
    }

    private static CreateBattleRequest.Builder captureRequest(int slots) {
        var request = request(BattleConstants.MATCH_MODE_PVE_SOLO, 1);
        addPlayer(request, PLAYER_A, 0, 10000, 10000, 0, 100, 0, 1000).setPetCaptureSlots(slots);
        return request;
    }

    @Test
    void 捕获离场不计击杀且占用容量_结算重复读取不增发() {
        var engine = TestBattles.start(captureRequest(1), captureData(0));
        long target = BattleConstants.MONSTER_ACTOR_ID_BASE;
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_CAPTURE, target))).isTrue();
        var result = engine.resolveCurrentRound();
        assertThat(result.getEventsList()).filteredOn(event -> event.getEventType() == eBattleEventType.BATTLE_EVENT_CAPTURE)
                .singleElement().satisfies(event -> assertThat(event.getSuccess()).isTrue());
        var monster = stateActor(engine.buildStateSnapshot(), target);
        assertThat(monster.getFled()).isTrue();
        assertThat(monster.getIsDead()).isFalse();
        assertThat(monster.getCapturedByPlayerId()).isEqualTo(PLAYER_A);
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_CAPTURE, target + 1))).isFalse();
        var settlement = engine.buildSettlement(PLAYER_A);
        assertThat(settlement.getCapturedPetTableIdsList()).containsExactly(1);
        assertThat(settlement.getDefeatedMonstersList()).isEmpty();
        assertThat(engine.buildSettlement(PLAYER_A)).isEqualTo(settlement);
    }

    @Test
    void 变异神兽未知层级关闭副本与容量不足均拒绝() {
        for (int tier : new int[] {1, 2, 99}) {
            var engine = TestBattles.start(captureRequest(1), captureData(tier));
            assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_CAPTURE, BattleConstants.MONSTER_ACTOR_ID_BASE))).isFalse();
        }
        var data = captureData(0);
        data.addDungeon(DUNGEON_CONFIG).setAllowPetCapture(0);
        var closed = TestBattles.start(captureRequest(1), data);
        assertThat(closed.submitAction(PLAYER_A, action(BATTLE_ACTION_CAPTURE, BattleConstants.MONSTER_ACTOR_ID_BASE))).isFalse();
        var full = TestBattles.start(captureRequest(0), captureData(0));
        assertThat(full.submitAction(PLAYER_A, action(BATTLE_ACTION_CAPTURE, BattleConstants.MONSTER_ACTOR_ID_BASE))).isFalse();
    }

    @Test
    void 未到携带等级不能捕捉() {
        var data = captureData(0);
        data.addPet(1).setUnlockLevel(11);
        var engine = TestBattles.start(captureRequest(1), data);
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_CAPTURE, BattleConstants.MONSTER_ACTOR_ID_BASE))).isFalse();
    }

    @Test
    void 两人同回合捕捉同一目标只归最先成功者() {
        var request = captureRequest(2);
        addPlayer(request, PLAYER_B, 0, 10000, 10000, 0, 100, 0, 500).setPetCaptureSlots(2);
        var engine = TestBattles.start(request, captureData(0));
        long target = BattleConstants.MONSTER_ACTOR_ID_BASE;
        assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_CAPTURE, target))).isFalse(); // 尚待另一位玩家。
        assertThat(engine.submitAction(PLAYER_B, action(BATTLE_ACTION_CAPTURE, target))).isTrue();
        engine.resolveCurrentRound();
        assertThat(engine.buildSettlement(PLAYER_A).getCapturedPetTableIdsList()).containsExactly(1);
        assertThat(engine.buildSettlement(PLAYER_B).getCapturedPetTableIdsList()).isEmpty();
    }
    @Test
    void 活动兜底怪物与玩家对战均不能捕捉() {
        for (boolean activity : new boolean[] {false, true}) {
            var data = captureData(0);
            var request = captureRequest(1);
            if (activity) request.getActivityContextBuilder().setKind(com.game.proto.eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL);
            else data.addMonster(91).setHealth(0);
            var engine = TestBattles.start(request, data);
            assertThat(engine.submitAction(PLAYER_A, action(BATTLE_ACTION_CAPTURE, BattleConstants.MONSTER_ACTOR_ID_BASE))).isFalse();
        }
        var request = captureRequest(1).setMatchMode(3);
        addPlayer(request, PLAYER_B, 1, 1000, 1000, 0, 100, 0, 500);
        var engine = TestBattles.start(request, captureData(0));
        assertThat(engine.validateAction(PLAYER_A, action(BATTLE_ACTION_CAPTURE, PLAYER_B))).isNotEqualTo(ActionChecks.SUCCESS);
    }

}
