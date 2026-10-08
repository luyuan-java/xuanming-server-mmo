package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.eBattleOutcome;
import com.game.robot.RobotOptions;
import com.game.robot.client.RobotClient;
import com.game.robot.scenario.FakeMatchWorld.Fault;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * match-5v5 场景：账号命名，以及对着本机假服务端（{@link FakeMatchWorld}）整条跑一遍编排（十个账号入队、同一局、蛇形分队、打完、评分）。
 * 假服务端只钉 robot 这一侧，真服务端的行为要到本机切片上才验证得了。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class Match5v5ScenarioTest {

    private FakeMatchWorld world;

    @BeforeEach
    void start() throws IOException {
        world = new FakeMatchWorld();
    }

    @AfterEach
    void stop() {
        world.close();
    }

    private Match5v5Scenario scenario(String runTag) {
        RobotClient client = world.newClient();
        return new Match5v5Scenario(client, world.flow(client), world.registry(), world.admin(), "robot_java_", runTag, FakeMatchWorld.TIMEOUT,
                FakeMatchWorld.FAST);
    }

    private static List<String> failed(CheckReport report) {
        return report.items().stream().filter(i -> !i.passed()).map(i -> i.name() + "：" + i.detail()).toList();
    }

    @Test
    void 十个账号等长_互不相同_下标越界是调用方的错() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("match-5v5", "--run-tag", "x1"), Map.of(RobotOptions.PASSWORD_ENV, "p"), 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.MATCH_5V5);
        assertThat(Match5v5Scenario.PLAYERS).isEqualTo(10).isEqualTo(BattleSmokeChecks.SNAKE_5V5.size());
        Set<String> accounts = new HashSet<>();
        for (int i = 0; i < Match5v5Scenario.PLAYERS; i++) {
            String account = Match5v5Scenario.accountName(options.accountPrefix(), options.runTag(), i);
            assertThat(account).hasSameSizeAs(Match5v5Scenario.accountName(options.accountPrefix(), options.runTag(), 0));
            accounts.add(account);
        }
        assertThat(accounts).hasSize(10).contains("robot_java_m5x1_0", "robot_java_m5x1_9");
        assertThatThrownBy(() -> Match5v5Scenario.accountName("p", "t", 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Match5v5Scenario.accountName("p", "t", -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 五步整条跑通_十人同一局_蛇形分队_评分落账() {
        Match5v5Scenario scenario = scenario("ok");

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("match-5v5")).isEmpty();
        assertThat(report.passed()).isTrue();
        List<String> names = report.items().stream().map(CheckReport.Item::name).toList();
        for (int step = 2; step <= 5; step++) {
            String prefix = "第 " + step + " 步";
            assertThat(names).as(prefix).anyMatch(n -> n.startsWith(prefix));
        }
        assertThat(scenario.resultLine()).matches("MATCH_5V5_OK battle_id=\\d+ outcome=1 rounds=" + FakeMatchWorld.PVP_ROUNDS);
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.name()).startsWith("第 5 步");
            assertThat(item.detail()).isEqualTo("0 队 Δ = +16.00");
        });
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        assertThat(world.receivedCount(ids.joinQueue())).as("每人只排一次").isEqualTo(10);
        assertThat(world.queuedTickets()).isZero();
    }

    @Test
    void 平局的5V5_每人局数加1_评分不动() {
        world.pvpOutcome = eBattleOutcome.BATTLE_OUTCOME_DRAW;
        Match5v5Scenario scenario = scenario("draw");

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("match-5v5")).isEmpty();
        assertThat(scenario.resultLine()).matches("MATCH_5V5_OK battle_id=\\d+ outcome=3 rounds=\\d+");
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.name()).startsWith("第 5 步");
            assertThat(item.detail()).isEqualTo("0 队 Δ = 0.00");
        });
    }

    @Test
    void 服务端按前5后5分队_第3步失败_评分也对不上蛇形() {
        world.faults.add(Fault.BLOCK_TEAMS_5V5);
        Match5v5Scenario scenario = scenario("f1");

        CheckReport report = scenario.run();

        assertThat(failed(report)).hasSize(2);
        assertThat(failed(report).get(0)).startsWith("第 3 步 评分相同").contains("[0, 0, 0, 0, 0, 1, 1, 1, 1, 1]", "期望 [0, 1, 1, 0, 0, 1, 1, 0, 0, 1]");
        assertThat(failed(report).get(1)).startsWith("第 5 步");
        assertThat(scenario.resultLine()).startsWith("MATCH_5V5_FAIL step=3-teams reason=第 3 步");
    }
}
