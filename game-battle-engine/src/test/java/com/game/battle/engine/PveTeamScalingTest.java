package com.game.battle.engine;

import static com.game.battle.engine.TestBattles.*;
import static com.game.battle.engine.TestTables.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleActorState;
import com.game.proto.BattleActivityContext;
import com.game.proto.CreateBattleRequest;
import com.game.proto.eBattleActorType;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 对齐 C++ PVE 人数档：循环组、十只上限、宠物/活动隔离与单人原始阵容。 */
class PveTeamScalingTest {
    private static CreateBattleRequest.Builder players(int count) {
        var request = request(BattleConstants.MATCH_MODE_PVE_TEAM, 1);
        for (int i = 0; i < count; i++) {
            addPlayer(request, PLAYER_A + i, 0, 1000, 1000, 1, 100, 0, 120);
        }
        return request;
    }

    private static List<BattleActorState> monsters(CreateBattleRequest.Builder request, MemoryBattleData data) {
        return start(request, data).buildStateSnapshot().getActorsList().stream()
                .filter(a -> a.getActorType() == eBattleActorType.BATTLE_ACTOR_TYPE_MONSTER).toList();
    }

    private static MemoryBattleData group(int perPlayer) {
        var data = standard();
        data.setDungeonMonsters(DUNGEON_CONFIG, 7001, 7002);
        data.setDungeonMonstersPerPlayer(DUNGEON_CONFIG, perPlayer);
        return data;
    }

    @ParameterizedTest
    @CsvSource({"1,2", "2,4", "3,6", "4,8", "5,10"})
    void 每名进攻方玩家一整组_阵位与种类按顺序(int players, int expected) {
        var actors = monsters(players(players), group(2));
        assertThat(actors).hasSize(expected);
        for (int i = 0; i < expected; i++) {
            assertThat(actors.get(i).getMonsterTableId()).isEqualTo(7001 + i % 2);
            assertThat(actors.get(i).getFormationSlot()).isEqualTo(i);
            assertThat(actors.get(i).getActorId()).isEqualTo(MONSTER_ID + i);
        }
    }

    @Test
    void 无符号超大配置先截断再乘_最多十只() {
        assertThat(monsters(players(5), group(-1))).hasSize(10);
        assertThat(monsters(players(5), group(3))).hasSize(10);
    }

    @Test
    void 宝宝不增加人数_防守方玩家不参与放大() {
        var request = players(2);
        request.getPlayersBuilder(1).setTeamIndex(1);
        addPet(request.getPlayersBuilder(0), PET_A, 300, 300, 2, 60);
        assertThat(monsters(request, group(2))).extracting(BattleActorState::getMonsterTableId).containsExactly(7001, 7002);
    }

    @ParameterizedTest
    @CsvSource({"1", "99"})
    void 已知和未知非零活动保持固定阵容(int kind) {
        var request = players(3).setActivityContext(BattleActivityContext.newBuilder().setKindValue(kind));
        assertThat(monsters(request, group(2))).extracting(BattleActorState::getMonsterTableId).containsExactly(7001, 7002);
    }

    @Test
    void 零配置保持固定阵容_空组仍按快照数兜底() {
        assertThat(monsters(players(3), group(0))).extracting(BattleActorState::getMonsterTableId).containsExactly(7001, 7002);
        var empty = standard();
        empty.setDungeonMonstersPerPlayer(DUNGEON_CONFIG, 2);
        assertThat(monsters(players(3), empty)).extracting(BattleActorState::getMonsterTableId).containsExactly(0, 0, 0);
    }

    @ParameterizedTest
    @CsvSource({"0,2000", "8,2000", "10,2500", "15,3750", "20,5000"})
    void 自动回合演出窗口按上一回合出手数放宽(int actors, long expectedMs) {
        assertThat(BattleConstants.autoRoundIntervalMsFor(actors)).isEqualTo(expectedMs);
    }
}
