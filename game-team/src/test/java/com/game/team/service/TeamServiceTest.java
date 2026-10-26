package com.game.team.service;

import static org.assertj.core.api.Assertions.assertThat;
import static com.game.team.service.TeamMatchFixture.byReason;

import com.game.proto.team.TeamChangeReason;
import com.game.proto.team.TeamMatchState;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 整队开战的服务层用例，缺省档（不连 Redis）：存储后端是内存替身 {@code InMemoryTeamRedis}（七段 Lua 的 Java 等价实现 + 手拨时钟，
 * 对应基线测试的 miniredis），game-match 是 {@code FakeMatchTeamService}。用例本身在 {@link TeamMatchScenarios}；同一批用例在真 Redis 上
 * 由 {@code TeamMatchRedisIntegrationTest} 再跑一遍（带 {@code -Dgame.it.redis}）。
 */
class TeamServiceTest extends TeamMatchScenarios {

    @Test
    void GetMyTeam仅显式regroup触发归队_普通查询与队长查询保持不动() {
        long[] team = fx.team(2);
        long leader = team[1], member = team[2];
        fx.sceneSignals.clear();
        var ordinary = fx.service.getMyTeam(member, com.game.proto.team.GetMyTeamRequest.getDefaultInstance(), TeamMatchFixture.deadline());
        assertThat(ordinary.getTeam().getTeamId()).isEqualTo(team[0]);
        assertThat(fx.sceneSignals).isEmpty();
        var regroup = com.game.proto.team.GetMyTeamRequest.newBuilder().setRegroup(true).build();
        var reply = fx.service.getMyTeam(member, regroup, TeamMatchFixture.deadline());
        assertThat(reply.getTeam().getTeamId()).isEqualTo(team[0]);
        assertThat(fx.sceneSignals).containsExactly(new TeamMatchFixture.SceneSignal(member, true));
        fx.sceneSignals.clear();
        fx.service.getMyTeam(leader, regroup, TeamMatchFixture.deadline());
        assertThat(fx.sceneSignals).isEmpty();
    }

    @Test
    void 收尾拒绝且gather异常完成_211仍恰好计一次未知结局() {
        fx.rejectBackground = true;
        fx.match.gatherReply = request -> CompletableFuture.failedFuture(new IllegalStateException("gather失败"));
        assertRejectedUnknownGather();
    }

    @Test
    void 收尾拒绝且gather同步抛异常_211仍恰好计一次未知结局() {
        fx.rejectBackground = true;
        fx.match.gatherReply = request -> { throw new IllegalStateException("gather同步失败"); };
        assertRejectedUnknownGather();
    }

    private void assertRejectedUnknownGather() {
        long[] team = fx.team(2);
        long version = fx.versionOf(team[0]);
        fx.takePushes();

        var response = fx.start(team[1], team[0]);

        assertThat(response.hasErrorMessage()).isFalse();
        assertThat(response.getTeam().getMatchState()).isEqualTo(TeamMatchState.TEAM_MATCH_STATE_STARTING);
        assertThat(fx.matches("gather_unknown")).isEqualTo(1);
        assertThat(fx.meters.find("game.team.matches").counters().stream()
                .mapToDouble(counter -> counter.count()).sum()).isEqualTo(1);
        assertThat(fx.record(team[0]).getMatchLockToken()).isNotEmpty();
        assertThat(fx.versionOf(team[0])).isEqualTo(version + 1);
        assertThat(fx.match.releases).isEmpty();
        var pushes = fx.takePushes();
        assertThat(byReason(pushes, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_ENDED)).isEmpty();
        assertThat(byReason(pushes, TeamChangeReason.TEAM_CHANGE_REASON_MATCH_FAILED)).isEmpty();
    }

    @Override
    TeamMatchFixture.Backend backend() {
        return TeamMatchFixture.inMemory();
    }
}
