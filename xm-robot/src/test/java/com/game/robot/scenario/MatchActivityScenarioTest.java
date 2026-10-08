package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.ActivityBattleReject;
import com.game.proto.match.StartActivityBattleRequest;
import com.game.proto.match.StartActivityBattleResponse;
import com.game.robot.RobotOptions;
import com.game.robot.client.MatchAdminClient;
import com.game.robot.client.RobotClient;
import com.game.robot.scenario.FakeMatchWorld.Fault;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * match-activity 场景：请求的形状与拒绝的判据（纯函数），以及对着本机假服务端（{@link FakeMatchWorld}）整条跑一遍编排。
 * 假服务端只钉 robot 这一侧，真服务端的行为要到本机切片上才验证得了。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MatchActivityScenarioTest {

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

    private MatchActivityScenario scenario(String runTag, MatchAdminClient admin) {
        RobotClient client = world.newClient();
        return new MatchActivityScenario(client, world.flow(client), world.registry(), admin, "robot_java_", runTag, FakeMatchWorld.TIMEOUT,
                FakeMatchWorld.FAST);
    }

    private static List<String> failed(CheckReport report) {
        return report.items().stream().filter(i -> !i.passed()).map(i -> i.name() + "：" + i.detail()).toList();
    }

    // ---------------------------------------------------------------- 纯函数

    @Test
    void 请求_名单按给定顺序_上下文是历练_帮会与活动是不存在的非0值_两个期键取游戏日() {
        StartActivityBattleRequest request = MatchActivityScenario.request(1, List.of(BIG, 7L), BIG, 20261008);

        assertThat(request.getBattleConfigId()).isEqualTo(1);
        assertThat(request.getMemberPlayerIdsList()).containsExactly(BIG, 7L);
        BattleActivityContext context = request.getActivityContext();
        assertThat(context.getKind()).isEqualTo(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL);
        assertThat(context.getInitiatorPlayerId()).isEqualTo(BIG).isEqualTo(request.getMemberPlayerIds(0));
        assertThat(context.getGuildId()).isEqualTo(MatchActivityScenario.GHOST_GUILD_ID).isNotZero();
        assertThat(context.getActivityId()).isEqualTo(MatchActivityScenario.GHOST_ACTIVITY_ID).isPositive();
        assertThat(context.getPeriodKey()).isEqualTo(20261008).isEqualTo(context.getGuildPeriodKey());
        // 「发起人不在首位」的那条请求只差这一个字段
        StartActivityBattleRequest wrong = MatchActivityScenario.request(1, List.of(BIG, 7L), 7L, 20261008);
        assertThat(wrong.getActivityContext().getInitiatorPlayerId()).isEqualTo(7L).isNotEqualTo(wrong.getMemberPlayerIds(0));
        assertThat(wrong.toBuilder().setActivityContext(wrong.getActivityContext().toBuilder().setInitiatorPlayerId(BIG)).build()).isEqualTo(request);
    }

    @Test
    void 拒绝的判据_原因与肇事者都要对上_且没有发battle_id() {
        StartActivityBattleResponse offline = StartActivityBattleResponse.newBuilder()
                .setReject(ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE).setOffenderPlayerId(BIG).build();
        assertThat(MatchActivityScenario.rejectProblem(offline, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE, BIG)).isNull();
        assertThat(MatchActivityScenario.rejectProblem(offline, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE, BIG))
                .contains("reject=ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE", "期望 reject=ACTIVITY_BATTLE_REJECT_MEMBER_IN_BATTLE");
        assertThat(MatchActivityScenario.rejectProblem(offline, ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE, 7))
                .contains("offender=9223372036854775809", "期望 reject=ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE offender=7");
        assertThat(MatchActivityScenario.rejectProblem(offline.toBuilder().setBattleId(5).build(),
                ActivityBattleReject.ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE, BIG)).as("拒绝了却发了 battle_id").contains("battle_id=5");
        // 全默认的应答（0 字节）= NONE / 0 / 0：不能被当成任何一种拒绝
        assertThat(MatchActivityScenario.rejectProblem(StartActivityBattleResponse.getDefaultInstance(),
                ActivityBattleReject.ACTIVITY_BATTLE_REJECT_INVALID_ARGUMENT, 0)).contains("reject=ACTIVITY_BATTLE_REJECT_NONE");
    }

    @Test
    void 账号带ma标签_子命令带连字符() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("match-activity", "--run-tag", "x1"), Map.of(RobotOptions.PASSWORD_ENV, "p"), 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.MATCH_ACTIVITY);
        assertThat(MatchActivityScenario.accountName(options.accountPrefix(), options.runTag(), "a")).isEqualTo("robot_java_max1_a");
        assertThat(MatchActivityScenario.accountName("p_", "t", "c")).hasSameSizeAs(MatchActivityScenario.accountName("p_", "t", "a"));
    }

    // ---------------------------------------------------------------- 对着假服务端

    @Test
    void 六步整条跑通_结果行带battle_id与两个玩家号() {
        MatchActivityScenario scenario = scenario("ok", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("match-activity")).isEmpty();
        assertThat(report.passed()).isTrue();
        List<String> names = report.items().stream().map(CheckReport.Item::name).toList();
        for (int step = 2; step <= 6; step++) {
            String prefix = "第 " + step + " 步";
            assertThat(names).as(prefix).anyMatch(n -> n.startsWith(prefix));
        }
        assertThat(scenario.resultLine()).matches("MATCH_ACTIVITY_OK battle_id=\\d+ player_a=\\d+ player_b=\\d+").doesNotContain("=0 ");
        assertThat(world.eventually(() -> world.lockedPlayers() == 0)).as("两人把这一局打完，结算随后落地").isTrue();
    }

    /**
     * 评审 ROBOT-1：gate 撤在线目录是异步的，C 的登出要排在 A、B 两次进场之前，给它留出落地的时间——紧挨着第 3 步登出的话，
     * 预检可能把 C 当在线放行并真的开出一局。假服务端按建角的先后发号，所以「C 的号最小」就是「C 最先进场」。
     */
    @Test
    void 充当已登出账号的C最先进场并登出_然后才是A与B() {
        MatchActivityScenario scenario = scenario("ord", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("match-activity")).isEmpty();
        Matcher ids = Pattern.compile("A=(\\d+) B=(\\d+) C（已登出）=(\\d+) ").matcher(String.join("\n", report.notes()));
        assertThat(ids.find()).as("报告的备注里有三个玩家号：%s", report.notes()).isTrue();
        long a = Long.parseUnsignedLong(ids.group(1));
        long b = Long.parseUnsignedLong(ids.group(2));
        long c = Long.parseUnsignedLong(ids.group(3));
        assertThat(Long.compareUnsigned(c, a)).as("C（%s）先于 A（%s）建角进场", ids.group(3), ids.group(1)).isNegative();
        assertThat(Long.compareUnsigned(a, b)).as("A 先于 B").isNegative();
    }

    @Test
    void 服务端把不在线的成员报成没准备好_第3步失败_其余步骤照常() {
        world.faults.add(Fault.ACTIVITY_OFFLINE_AS_NOT_READY);
        MatchActivityScenario scenario = scenario("f1", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("第 3 步 名单 [A, C]")
                .contains("reject=ACTIVITY_BATTLE_REJECT_MEMBER_NOT_READY", "期望 reject=ACTIVITY_BATTLE_REJECT_MEMBER_OFFLINE");
        assertThat(scenario.resultLine()).startsWith("MATCH_ACTIVITY_FAIL step=3-member-offline reason=第 3 步");
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 6 步"));
    }

    @Test
    void 运维令牌不对_第2步的管理口调用401_流程中断并写明原因() {
        MatchActivityScenario scenario = scenario("f2", new MatchAdminClient(world.baseUrl(), "wrong-token", FakeMatchWorld.TIMEOUT));

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("流程中断").contains("返回 401", "令牌不对");
        assertThat(scenario.resultLine()).startsWith("MATCH_ACTIVITY_FAIL step=2-invalid-argument reason=流程中断");
    }
}
