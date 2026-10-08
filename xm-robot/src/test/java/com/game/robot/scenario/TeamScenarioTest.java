package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.team.TeamApplicationView;
import com.game.proto.team.TeamMemberView;
import com.game.proto.team.TeamOutgoingInviteView;
import com.game.proto.team.TeamResponse;
import com.game.proto.team.TeamView;
import com.game.proto.TipInfoMessage;
import com.game.robot.RobotOptions;
import com.game.table.ConfigTables;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** team 场景里不连服务端就能钉住的部分：选图、视图判定、无符号输出、账号与子命令、请求间隔与 gate 限频的关系。 */
class TeamScenarioTest {

    /** 大于 Long.MAX_VALUE 的玩家号（无符号 uint64），输出与比较都不能按有符号处理。 */
    private static final long BIG = 0x8000_0000_0000_0001L;

    @Test
    void 换图目标取第一个A和B都不在的地图_没有时为0() {
        assertThat(TeamScenario.pickSceneConfig(List.of(1, 2, 3), 1, 1)).isEqualTo(2);
        assertThat(TeamScenario.pickSceneConfig(List.of(1, 2, 3), 1, 2)).isEqualTo(3);
        assertThat(TeamScenario.pickSceneConfig(List.of(1, 2), 1, 2)).isZero();
        assertThat(TeamScenario.pickSceneConfig(List.of(), 1, 2)).isZero();
    }

    @Test
    void 同步来的World表至少有一张A和B初始地图以外的世界地图() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables"))
                ? Path.of("../config-data/tables")
                : Path.of("config-data/tables");
        List<Integer> worlds = TeamScenario.worldConfigs(ConfigTables.load(dir));
        // 新号都落默认主世界（第一行）：S6 要能选出第二张
        assertThat(worlds).hasSizeGreaterThanOrEqualTo(2).doesNotHaveDuplicates().doesNotContain(0);
        assertThat(TeamScenario.pickSceneConfig(worlds, worlds.get(0), worlds.get(0))).isEqualTo(worlds.get(1));
    }

    @Test
    void 成员按集合语义计数_恰好一次才算在队() {
        TeamView view = TeamView.newBuilder()
                .addMembers(TeamMemberView.newBuilder().setPlayerId(7).setJoinSeq(1))
                .addMembers(TeamMemberView.newBuilder().setPlayerId(BIG).setJoinSeq(2))
                .addMembers(TeamMemberView.newBuilder().setPlayerId(BIG).setJoinSeq(3))
                .build();
        assertThat(TeamScenario.countMember(view, 7)).isEqualTo(1);
        assertThat(TeamScenario.onlyMember(view, 7).getJoinSeq()).isEqualTo(1);
        assertThat(TeamScenario.countMember(view, BIG)).isEqualTo(2);
        assertThat(TeamScenario.onlyMember(view, BIG)).as("出现两次是服务端缺陷，不算在队").isNull();
        assertThat(TeamScenario.onlyMember(view, 8)).isNull();
    }

    @Test
    void 申请与待处理邀请按玩家号查找() {
        TeamView view = TeamView.newBuilder()
                .addApplications(TeamApplicationView.newBuilder().setPlayer(TeamMemberView.newBuilder().setPlayerId(BIG)))
                .addPendingInvites(TeamOutgoingInviteView.newBuilder().setInvitee(TeamMemberView.newBuilder().setPlayerId(9)))
                .build();
        assertThat(TeamScenario.hasApplication(view, BIG)).isTrue();
        assertThat(TeamScenario.hasApplication(view, 9)).isFalse();
        assertThat(TeamScenario.hasPendingInvite(view, 9)).isTrue();
        assertThat(TeamScenario.hasPendingInvite(view, BIG)).isFalse();
    }

    @Test
    void 摘要里的号按无符号十进制_区分有没有视图() {
        TeamResponse withView = TeamResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(4004).addParameters(Long.toUnsignedString(BIG)))
                .setTeam(TeamView.newBuilder().setTeamId(BIG).setLeaderId(BIG).setVersion(3).setMembershipEpoch(-1L)
                        .addMembers(TeamMemberView.newBuilder().setPlayerId(BIG).setJoinSeq(1)))
                .build();
        String text = TeamScenario.describe(withView);
        assertThat(text).contains("tip=4004", "parameters=[9223372036854775809]", "team_id=9223372036854775809",
                "epoch=18446744073709551615", "members=[9223372036854775809#1]").doesNotContain("-");
        assertThat(TeamScenario.describe(TeamResponse.getDefaultInstance())).isEqualTo("tip=0 无视图");
    }

    @Test
    void team子命令_账号带tm标签_四个账号等长() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("team", "--run-tag", "x1"),
                Map.of(RobotOptions.PASSWORD_ENV, "p"), 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.TEAM);
        assertThat(TeamScenario.accountName(options.accountPrefix(), options.runTag(), "a")).isEqualTo("robot_java_tmx1_a");
        assertThat(TeamScenario.accountName(options.accountPrefix(), options.runTag(), "e")).hasSize(
                TeamScenario.accountName(options.accountPrefix(), options.runTag(), "a").length());
        assertThat(RobotOptions.usage()).contains("|team>", "  team ");
    }

    @Test
    void 结果行的标记同基线_帮助里写明开战段与结果行() {
        assertThat(TeamScenario.MARKER).isEqualTo("TEAM_SMOKE");
        assertThat(RobotOptions.usage()).contains("TEAM_SMOKE_OK", "TEAM_SMOKE_FAIL step=", "4026[B]", "4025[B]", "MATCH_ENDED");
    }

    @Test
    void 相邻请求间隔让每个消息号每秒不超过3条() {
        // gate 缺省限频每秒 3 条（GetMyTeam / ListMyInvites 5 条）；同一机器人任意 1 秒内最多 ceil(1000 / 间隔) 条
        long perSecond = (1000 + TeamScenario.REQUEST_SPACING.toMillis() - 1) / TeamScenario.REQUEST_SPACING.toMillis();
        assertThat(perSecond).isLessThanOrEqualTo(3);
    }
}
