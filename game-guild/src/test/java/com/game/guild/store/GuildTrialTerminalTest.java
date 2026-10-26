package com.game.guild.store;

import static org.junit.jupiter.api.Assertions.*;

import com.game.proto.BattleActivityContext;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.match.MatchMode;
import com.game.proto.match.StartActivityBattleRequest;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/** 完整终局与原名单的纯边界；字段残缺不能被隔离为可解除ACTIVE的确定性失败。 */
class GuildTrialTerminalTest {
    private final StartActivityBattleRequest start = StartActivityBattleRequest.newBuilder()
            .setRequestId(101).setBattleConfigId(7).addMemberPlayerIds(3).addMemberPlayerIds(9)
            .setActivityContext(BattleActivityContext.newBuilder().setKindValue(1).setGuildId(11).setActivityId(5)
                    .setPeriodKey(8).setGuildPeriodKey(8).setInitiatorPlayerId(3).setLaunchRequestId(101)).build();

    private BattleResultEvent result() {
        return BattleResultEvent.newBuilder().setBattleId(201).setBattleConfigId(7)
                .setMatchMode(MatchMode.MATCH_MODE_PVE_TEAM_VALUE).setFinishedAtMs(1234).setOutcomeValue(1)
                .setActivityContext(start.getActivityContext())
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(3).addPlayerIds(9)).build();
    }

    @Test void 完整胜负平战报均接受_死亡名单不剔除奖励候选() throws Exception {
        for (int outcome : new int[] {1, 2, 3}) {
            var event = result().toBuilder().setOutcomeValue(outcome).addDeadPlayerIds(3).addFledPlayerIds(9).build();
            assertEquals(Set.of(3L, 9L), GuildActivityStore.terminalRoster(start, event));
        }
    }

    @Test void 缺终局时间或非法结果及原件范围冲突均保持未证明() {
        List<UnaryOperator<BattleResultEvent.Builder>> invalid = List.of(
                b -> b.clearBattleId(), b -> b.clearFinishedAtMs(), b -> b.setOutcomeValue(0), b -> b.setOutcomeValue(4),
                b -> b.setOutcomeValue(-1), b -> b.clearMatchMode(), b -> b.setBattleConfigId(8), b -> b.setWinnerTeamIndex(1),
                b -> b.setActivityContext(b.getActivityContext().toBuilder().setLaunchRequestId(102)),
                b -> b.setActivityContext(b.getActivityContext().toBuilder().setInitiatorPlayerId(9)),
                b -> b.setActivityContext(b.getActivityContext().toBuilder().setGuildPeriodKey(9)));
        for (var change : invalid)
            assertThrows(GuildActivityStore.UnprovenResult.class,
                    () -> GuildActivityStore.terminalRoster(start, change.apply(result().toBuilder()).build()));
    }

    @Test void 己方名单必须唯一完整_死亡与逃跑必须各自无重复且属于原名单() {
        List<UnaryOperator<BattleResultEvent.Builder>> invalid = List.of(
                b -> b.clearTeams(), b -> b.addTeams(b.getTeams(0)),
                b -> b.setTeams(0, b.getTeams(0).toBuilder().clearPlayerIds().addPlayerIds(3)),
                b -> b.setTeams(0, b.getTeams(0).toBuilder().addPlayerIds(3)),
                b -> b.setTeams(0, b.getTeams(0).toBuilder().setPlayerIds(1, 10)),
                b -> b.addFledPlayerIds(0), b -> b.addFledPlayerIds(10),
                b -> b.addFledPlayerIds(3).addFledPlayerIds(3), b -> b.addDeadPlayerIds(0),
                b -> b.addDeadPlayerIds(10), b -> b.addDeadPlayerIds(9).addDeadPlayerIds(9));
        for (var change : invalid)
            assertThrows(GuildActivityStore.UnprovenResult.class,
                    () -> GuildActivityStore.terminalRoster(start, change.apply(result().toBuilder()).build()));
    }
}
