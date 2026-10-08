package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.eBattleOutcome;
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
 * battle-smoke 场景对着本机假服务端（{@link FakeMatchWorld}）整条跑：编排本身（先后次序、mark、等待条件、过渡态重试、收尾）走得通；
 * 假服务端故意做错一处时，对应那一步的检查失败、结果行指向那一步。假服务端只钉 robot 这一侧——真服务端的行为要到本机切片上才验证得了。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class BattleSmokeScenarioTest {

    private static final Pattern OK_LINE = Pattern.compile(
            "BATTLE_SMOKE_OK battle_id=(\\d+) a_turns=(\\d+) a_direct_turns=(\\d+) pvp_battle_id=(\\d+) challenge_battle_id=(\\d+)");

    private FakeMatchWorld world;

    @BeforeEach
    void start() throws IOException {
        world = new FakeMatchWorld();
    }

    @AfterEach
    void stop() {
        world.close();
    }

    private BattleSmokeScenario scenario(String runTag, MatchAdminClient admin) {
        RobotClient client = world.newClient();
        return new BattleSmokeScenario(client, world.flow(client), world.registry(), admin, "robot_java_", runTag, FakeMatchWorld.TIMEOUT,
                FakeMatchWorld.FAST);
    }

    private static List<String> failed(CheckReport report) {
        return report.items().stream().filter(i -> !i.passed()).map(i -> i.name() + "：" + i.detail()).toList();
    }

    @Test
    void 十二步整条跑通_结果行带三局各自的battle_id_收尾后没有残留的锁与排队票() {
        BattleSmokeScenario scenario = scenario("ok", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(report.passed()).isTrue();
        // 每一步都真的跑到了（不是一条都没查就「通过」）
        List<String> names = report.items().stream().map(CheckReport.Item::name).toList();
        for (int step = 1; step <= 11; step++) {
            String prefix = "第 " + step + " 步";
            assertThat(names).as(prefix).anyMatch(n -> n.startsWith(prefix));
        }
        Matcher line = OK_LINE.matcher(scenario.resultLine());
        assertThat(line.matches()).as(scenario.resultLine()).isTrue();
        long solo = Long.parseUnsignedLong(line.group(1));
        long duel = Long.parseUnsignedLong(line.group(4));
        long challenge = Long.parseUnsignedLong(line.group(5));
        assertThat(List.of(solo, duel, challenge)).as("三局各不相同、都非 0，按无符号十进制输出（假服务端的号在 uint64 上半区）")
                .doesNotHaveDuplicates().doesNotContain(0L).allMatch(id -> id < 0);
        assertThat(line.group(2)).as("PVE 第一局在直连上收到的 139 条数").isEqualTo("1").isEqualTo(line.group(3));
        assertThat(scenario.resultLine()).doesNotContain("-");

        // 请求确实发了、没有多发：163 / 164 各一条；148 = 第 3 步两条 + 收尾 A、B 各一条
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        assertThat(world.receivedCount(ids.watchBattle())).isEqualTo(1);
        assertThat(world.receivedCount(ids.listWatchable())).isEqualTo(1);
        // 收尾的两条 148 发出后连接随即关闭，服务端稍后才处理到
        assertThat(world.eventually(() -> world.receivedCount(ids.cancelQueue()) == 4)).as("148 共 " + world.receivedCount(ids.cancelQueue()) + " 条")
                .isTrue();
        assertThat(world.receivedCount(ids.requestTicket())).as("179：A、C、C（不存在的局）、A（旧局）").isEqualTo(4);
        assertThat(world.lockedPlayers()).as("每一局都打完并结算落地").isZero();
        assertThat(world.queuedTickets()).isZero();
    }

    @Test
    void 平局的1V1_评分不动但局数加1_照样通过() {
        world.pvpOutcome = eBattleOutcome.BATTLE_OUTCOME_DRAW;
        BattleSmokeScenario scenario = scenario("draw", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(report.items()).anySatisfy(item -> assertThat(item.name()).startsWith("第 8 步").contains("Δ 都是 0"));
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_OK ");
    }

    @Test
    void B队胜的1V1_A减16_B加16_照样通过() {
        world.pvpOutcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
        BattleSmokeScenario scenario = scenario("bwin", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-smoke")).isEmpty();
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.name()).startsWith("第 8 步").contains("|Δ| = 16");
            assertThat(item.detail()).contains("rating=1500.00 games=0 → rating=1484.00 games=1", "rating=1500.00 games=0 → rating=1516.00 games=1");
        });
    }

    @Test
    void 取消排队居然回了包_第3步失败_其余步骤照常跑完() {
        world.faults.add(Fault.REPLY_TO_CANCEL);
        BattleSmokeScenario scenario = scenario("f1", world.admin());

        CheckReport report = scenario.run();

        assertThat(report.passed()).isFalse();
        assertThat(failed(report)).hasSize(2).allMatch(f -> f.startsWith("第 3 步 148(") && f.contains("收到了 148 的回包"));
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=3-queue-cancel reason=第 3 步 148(\"stale\")");
        assertThat(report.items()).as("后面的阶段没有被这一步拖垮").anyMatch(i -> i.passed() && i.name().startsWith("第 10 步"));
    }

    @Test
    void 大厅上先143后177_第4步失败() {
        world.faults.add(Fault.START_BEFORE_ASSIGNED);
        BattleSmokeScenario scenario = scenario("f2", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("第 4 步 大厅上先 177 后 143");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=4-pve-solo ");
    }

    @Test
    void 补签的票与177不同_第5步失败_随后退回177的票继续打() {
        world.faults.add(Fault.REISSUE_DIFFERENT_TICKET);
        BattleSmokeScenario scenario = scenario("f3", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("第 5 步 A 发 179").contains("逐字节相同");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=5-reissue ");
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 6 步 挂机打到 150"));
    }

    @Test
    void 文案用了全角逗号_第6步的逐字节核对失败() {
        world.faults.add(Fault.FULL_WIDTH_COMMA);
        BattleSmokeScenario scenario = scenario("f4", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("第 6 步 战斗中").contains("逐字节", "战斗尚未结束，无法排队");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=6-direct-fight ");
    }

    @Test
    void 上一局的ready票没被清掉_再排回16001_第7步失败() {
        world.faults.add(Fault.KEEP_READY_TICKET);
        BattleSmokeScenario scenario = scenario("f5", world.admin());

        CheckReport report = scenario.run();

        assertThat(report.passed()).isFalse();
        assertThat(failed(report).get(0)).startsWith("第 7 步 打完立即再排：不得出现 16001").contains("error_code=16001");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=7-requeue ");
        // 这一阶段中断，后面的阶段各自记账（这里 A 的票还挡着 1V1；切磋不看票据，照常通过）
        assertThat(report.items()).anyMatch(i -> !i.passed() && i.name().startsWith("流程中断：PVE_SOLO"));
        assertThat(report.items()).anyMatch(i -> i.passed() && i.name().startsWith("第 9 步 C 下线后 A 挑战 C"));
        assertThat(report.items()).anyMatch(i -> !i.passed() && i.name().contains("xm_match_gathers_total"));
    }

    @Test
    void 一对一把后入队的人放进0队_第8步的分队与评分都对不上() {
        world.faults.add(Fault.SWAP_DUEL_SIDES);
        BattleSmokeScenario scenario = scenario("f6", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).hasSize(2);
        assertThat(failed(report).get(0)).startsWith("第 8 步 143 的 actors 里 A（锚点）在 0 队").contains("[1, 0]");
        assertThat(failed(report).get(1)).startsWith("第 8 步 收到 150 后").contains("期望 +16.00");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=8-pvp-rating ");
    }

    @Test
    void 拒绝切磋时应答者也收到了154_第9步失败() {
        world.faults.add(Fault.DECLINE_PUSH_TO_RESPONDER);
        BattleSmokeScenario scenario = scenario("f7", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().asString().startsWith("第 9 步 拒绝只推发起者").contains("B 也收到了");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=9-challenge ");
    }

    @Test
    void 没有运维令牌_登录之前就中止_不向服务端发任何请求() {
        BattleSmokeScenario scenario = scenario("f8", new MatchAdminClient(world.baseUrl(), null, FakeMatchWorld.TIMEOUT));

        CheckReport report = scenario.run();

        assertThat(report.passed()).isFalse();
        assertThat(failed(report)).singleElement().asString().startsWith("流程中断").contains("XM_ADMIN_TOKEN");
        assertThat(scenario.resultLine()).startsWith("BATTLE_SMOKE_FAIL step=1-login reason=流程中断：没有运维令牌");
        assertThat(world.receivedCount(MatchSupport.Ids.resolve(world.registry()).queueStatus())).isZero();
    }

    @Test
    void 账号带bm标签_三个账号等长_不与battle_settle的bs撞() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("battle-smoke", "--run-tag", "x1"), Map.of(RobotOptions.PASSWORD_ENV, "p"), 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.BATTLE_SMOKE);
        assertThat(BattleSmokeScenario.accountName(options.accountPrefix(), options.runTag(), "a")).isEqualTo("robot_java_bmx1_a");
        assertThat(BattleSmokeScenario.accountName("p_", "t", "c")).hasSameSizeAs(BattleSmokeScenario.accountName("p_", "t", "a"));
        assertThat(BattleSmokeScenario.accountName("p_", "t", "a")).isNotEqualTo(BattleSettleScenario.accountName("p_", "t", "a"));
        assertThat(BattleSmokeScenario.EXPECTED_GATHERS).as("PVE 两局 + 1V1 + 切磋").isEqualTo(4);
    }
}
