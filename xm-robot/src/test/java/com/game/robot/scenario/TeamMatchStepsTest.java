package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.TipInfoMessage;
import com.game.robot.client.RobotClient;
import com.game.robot.client.RobotException;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import com.game.robot.scenario.FakeMatchWorld.Fault;
import com.game.robot.scenario.TeamMatchSteps.StartVerdict;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * team 场景的开战段（开战拒绝码、S7、S8）：211 应答的判读（纯函数），以及对着本机假服务端（{@link FakeMatchWorld}）把三段整条跑一遍。
 * 假服务端只钉 robot 这一侧——xm-team / xm-match 的真实行为要到本机切片上才验证得了。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TeamMatchStepsTest {

    /** uint64 上半区的玩家号。 */
    private static final long BIG = 0x8000_0000_0000_0001L;

    private FakeMatchWorld world;

    @BeforeEach
    void start() throws IOException {
        world = new FakeMatchWorld();
    }

    @AfterEach
    void stop() {
        world.close();
    }

    /** 两个已进场的机器人组成的队伍（A 是队长），外加跑开战段要的报告与步骤记账。 */
    private final class Squad implements AutoCloseable {
        final CheckReport report = new CheckReport();
        final StepTrack track = new StepTrack(TeamScenario.MARKER);
        final MatchSupport.Bot a;
        final MatchSupport.Bot b;
        final long tid;
        final TeamMatchSteps steps;

        Squad(String runTag) throws RobotException {
            RobotClient client = world.newClient();
            PlayerFlow flow = world.flow(client);
            a = new MatchSupport.Bot("A", flow.enter("robot_java_tm" + runTag + "_a", new Timings()), FakeMatchWorld.TIMEOUT, FakeMatchWorld.FAST);
            b = new MatchSupport.Bot("B", flow.enter("robot_java_tm" + runTag + "_b", new Timings()), FakeMatchWorld.TIMEOUT, FakeMatchWorld.FAST);
            tid = world.formTeam(a.id(), b.id());
            steps = new TeamMatchSteps(client, world.registry(), report, track, FakeMatchWorld.TIMEOUT, FakeMatchWorld.FAST);
        }

        List<String> failed() {
            return report.items().stream().filter(i -> !i.passed()).map(i -> i.name() + "：" + i.detail()).toList();
        }

        @Override
        public void close() {
            steps.close();
            a.connection().close();
            b.connection().close();
        }
    }

    // ---------------------------------------------------------------- 纯函数

    @Test
    void 期望4025某人时一次211应答的判读_对上即止_撞到别人按过渡态重试_受理或别的码立即失败() {
        String b = Long.toUnsignedString(BIG);
        assertThat(TeamMatchSteps.memberInBattleVerdict(4025, List.of(b), BIG)).isEqualTo(StartVerdict.MATCHED);
        assertThat(TeamMatchSteps.memberInBattleVerdict(4025, List.of(b, "多余的参数"), BIG)).isEqualTo(StartVerdict.MATCHED);
        // 上一场结算尚未落地：先撞到的是队长自己，或某人的 ready 票还没清
        assertThat(TeamMatchSteps.memberInBattleVerdict(4025, List.of("7"), BIG)).isEqualTo(StartVerdict.RETRY);
        assertThat(TeamMatchSteps.memberInBattleVerdict(4026, List.of(b), BIG)).isEqualTo(StartVerdict.RETRY);
        assertThat(TeamMatchSteps.memberInBattleVerdict(4025, List.of(), BIG)).as("4025 却没带肇事者").isEqualTo(StartVerdict.RETRY);
        // 玩家号按无符号十进制：有符号的写法（负数）不算对上
        assertThat(TeamMatchSteps.memberInBattleVerdict(4025, List.of(Long.toString(BIG)), BIG)).isEqualTo(StartVerdict.RETRY);
        // 被受理（B 明明在战斗中）与不相干的码：不是过渡态，重试只会把问题盖住
        assertThat(TeamMatchSteps.memberInBattleVerdict(0, List.of(), BIG)).isEqualTo(StartVerdict.FAILED);
        assertThat(TeamMatchSteps.memberInBattleVerdict(4027, List.of(), BIG)).isEqualTo(StartVerdict.FAILED);
        assertThat(TeamMatchSteps.memberInBattleVerdict(4023, List.of(), BIG)).as("开战锁还在").isEqualTo(StartVerdict.FAILED);
        assertThat(TeamMatchSteps.memberInBattleVerdict(4030, List.of(b), BIG)).as("内部错误（如 xm-match 不可达）").isEqualTo(StartVerdict.FAILED);
    }

    @Test
    void 开战的过渡态只有队员在战斗中与队员没准备好两个码() {
        assertThat(TeamMatchSteps.transientStartTip(4025)).isTrue();
        assertThat(TeamMatchSteps.transientStartTip(4026)).isTrue();
        for (int tip : new int[] {0, 4018, 4023, 4024, 4027, 4028, 4029, 4030, 16000}) {
            assertThat(TeamMatchSteps.transientStartTip(tip)).as("tip %s", tip).isFalse();
        }
        assertThat(List.of(TeamMatchSteps.TIP_NOT_LEADER, TeamMatchSteps.TIP_MEMBER_IN_BATTLE, TeamMatchSteps.TIP_MEMBER_NOT_READY,
                TeamMatchSteps.TIP_DUNGEON_NOT_OPEN)).as("码取自导表枚举").containsExactly(4018, 4025, 4026, 4027);
    }

    @Test
    void 肇事者参数是玩家号的无符号十进制_取第一项() {
        TipInfoMessage tip = TipInfoMessage.newBuilder().setId(4026).addParameters(Long.toUnsignedString(BIG)).build();
        assertThat(TeamMatchSteps.firstParameterIs(tip, BIG)).isTrue();
        assertThat(TeamMatchSteps.firstParameterIs(tip, 7)).isFalse();
        assertThat(TeamMatchSteps.firstParameterIs(TipInfoMessage.newBuilder().setId(4026).build(), BIG)).as("没带参数").isFalse();
        assertThat(TeamMatchSteps.firstParameterIs(TipInfoMessage.newBuilder().addParameters("x").addParameters(Long.toUnsignedString(BIG)).build(),
                BIG)).as("只看 parameters[0]").isFalse();
    }

    @Test
    void 开战请求带副本号与期望的队伍号_整队开战用Dungeon1_未开放的副本用2() {
        assertThat(TeamMatchSteps.startRequest(TeamMatchSteps.BATTLE_CONFIG_ID, BIG).getBattleConfigId()).isEqualTo(1);
        assertThat(TeamMatchSteps.startRequest(TeamMatchSteps.BATTLE_CONFIG_ID, BIG).getExpectedTeamId()).isEqualTo(BIG);
        assertThat(TeamMatchSteps.BATTLE_CONFIG_NOT_OPEN).isEqualTo(2).isNotEqualTo(TeamMatchSteps.BATTLE_CONFIG_ID);
    }

    // ---------------------------------------------------------------- 对着假服务端

    @Test
    void 三段整条跑通_拒绝码_整队开战_战斗中拒绝() throws Exception {
        try (Squad squad = new Squad("ok")) {
            squad.steps.run(squad.a, squad.b, squad.tid);

            assertThat(squad.failed()).as(squad.report.render("team 开战段")).isEmpty();
            List<String> names = squad.report.items().stream().map(CheckReport.Item::name).toList();
            assertThat(names).anyMatch(n -> n.startsWith("非队长 B 开战 → 4018")).anyMatch(n -> n.startsWith("A 发 211(2)"))
                    .anyMatch(n -> n.startsWith("B 持有 1V1 排队票时 A 发 211 → 4026")).anyMatch(n -> n.startsWith("B 发 148 取消排队后"))
                    .anyMatch(n -> n.startsWith("S7 B 收到 213 MATCH_STARTED")).anyMatch(n -> n.startsWith("S7 A 收到 213 MATCH_ENDED"))
                    .anyMatch(n -> n.startsWith("S7 B 收到 213 MATCH_ENDED")).anyMatch(n -> n.startsWith("S7 发起人 A 没有收到 MATCH_STARTED"))
                    .anyMatch(n -> n.startsWith("S8 B 在战斗中")).anyMatch(n -> n.startsWith("S8 B 开自动把这一局打到 150"));
            assertThat(squad.steps.teamBattleId()).as("S7 那一局的 battle_id").isNotZero();
            assertThat(squad.track.line(squad.report, "battle_id=" + Long.toUnsignedString(squad.steps.teamBattleId())))
                    .matches("TEAM_SMOKE_OK battle_id=\\d+");
            assertThat(world.queuedTickets()).as("B 的 1V1 票已取消").isZero();
            assertThat(world.eventually(() -> world.lockedPlayers() == 0)).as("两局都打完，结算随后落地").isTrue();
        }
    }

    @Test
    void 队长上一场的结算比队员晚落地_S8先撞到4025队长_按过渡态重试到4025队员() throws Exception {
        try (Squad squad = new Squad("slow")) {
            // S7 打完后 B 的锁先放（B 的单人 PVE 得以开局），A 的锁再晚 1.5 s：这段时间 A 发 211 得到的是 4025[A]
            world.slowSettlePlayer = squad.a.id();
            world.slowSettleExtraMs = 1500;
            int startTeamMatch = world.registry().requireId("ClientPlayerTeam", "StartTeamMatch");

            squad.steps.run(squad.a, squad.b, squad.tid);

            assertThat(squad.failed()).as(squad.report.render("team 开战段")).isEmpty();
            // 211 的条数：拒绝码 3 条 + S7 1 条 + S8 至少 2 条（先 4025[A]、后 4025[B]）
            assertThat(world.receivedCount(startTeamMatch)).isGreaterThanOrEqualTo(6);
        }
    }

    @Test
    void 发起人也收到了MATCH_STARTED_S7的最后一条检查失败_结果行指向S7() throws Exception {
        world.faults.add(Fault.TEAM_STARTED_TO_LEADER);
        try (Squad squad = new Squad("f1")) {
            squad.steps.run(squad.a, squad.b, squad.tid);

            assertThat(squad.failed()).singleElement().asString().startsWith("S7 发起人 A 没有收到 MATCH_STARTED").contains("A 也收到了");
            assertThat(squad.track.line(squad.report, "")).startsWith("TEAM_SMOKE_FAIL step=s7-team-battle reason=S7 发起人 A");
        }
    }

    @Test
    void 肇事者写成了有符号十进制_4026与4025的参数都对不上_S8重试到上限后失败() throws Exception {
        world.faults.add(Fault.TEAM_SIGNED_OFFENDER);
        try (Squad squad = new Squad("f2")) {
            squad.steps.run(squad.a, squad.b, squad.tid);

            assertThat(squad.failed()).hasSize(2);
            assertThat(squad.failed().get(0)).startsWith("B 持有 1V1 排队票时 A 发 211 → 4026").contains("tip=4026", "parameters=[-");
            assertThat(squad.failed().get(1)).startsWith("S8 B 在战斗中").contains("tip=4025", "parameters=[-");
            assertThat(squad.track.line(squad.report, "")).startsWith("TEAM_SMOKE_FAIL step=match-rejects reason=B 持有 1V1 排队票时");
            // S7 不受影响；S8 失败之后 B 仍把自己那一局打完（不留一个 300 s 的房间）
            assertThat(squad.report.items()).anyMatch(i -> i.passed() && i.name().startsWith("S7 都开自动"));
            assertThat(squad.report.items()).anyMatch(i -> i.passed() && i.name().startsWith("S8 B 开自动把这一局打到 150"));
        }
    }

    @Test
    void 队伍不存在时三段各自记失败_互不拖累_不抛出() throws Exception {
        try (Squad squad = new Squad("f3")) {
            // 用一个假服务端不认识的队伍号：211 仍按「调用者所在的队伍」处理，视图里的 team_id 与期望的不符
            squad.steps.run(squad.a, squad.b, squad.tid + 100);

            assertThat(squad.report.passed()).isFalse();
            assertThat(squad.failed()).anyMatch(f -> f.startsWith("非队长 B 开战 → 4018")).anyMatch(f -> f.startsWith("A 发 211(2)"));
            assertThat(squad.track.line(squad.report, "")).startsWith("TEAM_SMOKE_FAIL step=match-rejects ");
        }
    }
}
