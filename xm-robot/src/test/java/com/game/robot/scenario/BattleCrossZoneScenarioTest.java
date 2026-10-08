package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.eBattleOutcome;
import com.game.robot.RobotOptions;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.MatchAdminClient;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RobotClient;
import com.game.robot.scenario.FakeMatchWorld.Fault;
import java.io.IOException;
import java.time.Duration;
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
 * battle-cross-zone 场景对着本机假服务端（{@link FakeMatchWorld}：两个区各一个 gate 端口，背后是同一个匹配与战斗）整条跑：编排走得通；
 * 假服务端故意做错一处时，对应那一步的检查失败、结果行指向那一步。假服务端只钉 robot 这一侧——两个真区之间的寻址要到双 zone 切片上才验证得了。
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class BattleCrossZoneScenarioTest {

    private static final Pattern OK_LINE = Pattern.compile("CROSS_ZONE_MATCH_OK battle_id=(\\d+) zone_a=1 zone_b=2 a_turns=(\\d+) b_turns=(\\d+)"
            + " a_direct_turns=(\\d+) b_direct_turns=(\\d+) observer_zone=2 c_spectate_turns=(\\d+) second_battle_id=(\\d+)");
    /** 等大厅 150 的上限：只有「不推大厅 150」的用例会耗满它。 */
    private static final Duration LOBBY_END = Duration.ofSeconds(2);

    private FakeMatchWorld world;

    @BeforeEach
    void start() throws IOException {
        world = new FakeMatchWorld();
    }

    @AfterEach
    void stop() {
        world.close();
    }

    private BattleCrossZoneScenario scenario(String runTag, MatchAdminClient admin) {
        return scenario(runTag, admin, world.newClient(2));
    }

    private BattleCrossZoneScenario scenario(String runTag, MatchAdminClient admin, RobotClient visitClient) {
        RobotClient homeClient = world.newClient(1);
        return new BattleCrossZoneScenario(homeClient, world.flow(homeClient), visitClient, world.flow(visitClient),
                new GatewayHttp(world.baseUrl(), FakeMatchWorld.TIMEOUT), world.registry(), admin, "robot_java_", runTag, 1, 2, FakeMatchWorld.TIMEOUT,
                FakeMatchWorld.FAST, FakeMatchWorld.FAST_SPECTATE, LOBBY_END);
    }

    private static List<String> failed(CheckReport report) {
        return report.items().stream().filter(i -> !i.passed()).map(i -> i.name() + "：" + i.detail()).toList();
    }

    private static List<String> names(CheckReport report) {
        return report.items().stream().map(CheckReport.Item::name).toList();
    }

    @Test
    void 两个区的人凑进同一局_带跨区观众_连打两局_结果行与基线同形() {
        BattleCrossZoneScenario scenario = scenario("ok", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-cross-zone")).isEmpty();
        assertThat(report.passed()).isTrue();
        List<String> names = names(report);
        for (int step : List.of(0, 1, 3, 4, 5, 6, 7, 8, 9, 10, 11)) {
            String prefix = "Z" + step + " ";
            assertThat(names).as(prefix).anyMatch(n -> n.startsWith(prefix));
        }
        assertThat(names).as("第二局不带观众：没有第二条 163 的检查").filteredOn(n -> n.contains("发 163(battle_id)")).hasSize(1);
        assertThat(names).filteredOn(n -> n.startsWith("Z9 第二局 ")).as("第二局重复排队、公告、直连、打完、大厅 150、评分").hasSizeGreaterThanOrEqualTo(9);

        Matcher line = OK_LINE.matcher(scenario.resultLine());
        assertThat(line.matches()).as(scenario.resultLine()).isTrue();
        long first = Long.parseUnsignedLong(line.group(1));
        long second = Long.parseUnsignedLong(line.group(7));
        assertThat(List.of(first, second)).as("两局各不相同、都非 0，按无符号十进制输出").doesNotHaveDuplicates().doesNotContain(0L).allMatch(id -> id < 0);
        assertThat(line.group(2)).as("A 在直连上收到的 139 条数；direct 字段同值").isEqualTo("1").isEqualTo(line.group(4));
        assertThat(line.group(3)).isEqualTo("1").isEqualTo(line.group(5));
        assertThat(line.group(6)).as("C 在直连上收到的 158 条数").isEqualTo("1");
        assertThat(scenario.resultLine()).doesNotContain("-");

        // 请求确实发了、没有多发
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        assertThat(world.receivedCount(ids.joinQueue())).as("157：两局各两条（假服务端的锁在大厅 150 之前就放了，第二局不用重试）").isEqualTo(4);
        assertThat(world.receivedCount(ids.watchBattle())).as("163：只有第一局的 C").isEqualTo(1);
        assertThat(world.eventually(() -> world.receivedCount(ids.cancelQueue()) == 2)).as("收尾给 A、B 各发一条 148").isTrue();
        assertThat(world.lockedPlayers()).isZero();
        assertThat(world.queuedTickets()).isZero();
        assertThat(world.observerCount()).isZero();
        assertThat(report.notes()).anyMatch(n -> n.contains("（区 1）") && n.contains("（区 2）")).anyMatch(n -> n.contains("X16"));
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.name()).startsWith("Z1 区 1 与区 2 的 gate 端点不同");
            assertThat(item.detail()).isEqualTo("区 1 → 127.0.0.1:" + world.gatePort(1) + "，区 2 → 127.0.0.1:" + world.gatePort(2));
        });
    }

    @Test
    void 平局_两局的评分都不动_局数各加2_照样通过() {
        world.pvpOutcome = eBattleOutcome.BATTLE_OUTCOME_DRAW;
        BattleCrossZoneScenario scenario = scenario("draw", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-cross-zone")).isEmpty();
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.name()).startsWith("Z9 第二局 收到 150 后");
            assertThat(item.detail()).isEqualTo("A rating=1500.00 games=1 → rating=1500.00 games=2；B rating=1500.00 games=1 → rating=1500.00 games=2");
        });
    }

    @Test
    void B队胜_第一局A减16B加16_第二局赛前不同分时只核对方向与上界() {
        world.pvpOutcome = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
        BattleCrossZoneScenario scenario = scenario("bwin", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("battle-cross-zone")).isEmpty();
        assertThat(report.items()).anySatisfy(item -> {
            assertThat(item.name()).startsWith("Z8 收到 150 后");
            assertThat(item.detail()).isEqualTo("A rating=1500.00 games=0 → rating=1484.00 games=1；B rating=1500.00 games=0 → rating=1516.00 games=1");
        });
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_OK ");
    }

    @Test
    void 只有一个区开着_Z0就失败_结果行是preflight_写明需要双zone切片_一个号都不登录() {
        world.closeZone(2);
        BattleCrossZoneScenario scenario = scenario("z0", world.admin());

        CheckReport report = scenario.run();

        assertThat(report.passed()).isFalse();
        assertThat(failed(report).get(0)).startsWith("Z0 区服列表里区 1、区 2 都是 OPEN").contains("区 2 的状态是 MAINTENANCE，不是 OPEN", "XM_ZONES=2");
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_FAIL step=preflight reason=Z0 区服列表里");
        assertThat(world.receivedCount(MessageIds.resolve(world.registry()).login())).as("没有跳过去硬跑").isZero();
    }

    @Test
    void 没有运维令牌_登录之前就失败_结果行是z8_admin_token_不静默跳过评分() {
        BattleCrossZoneScenario scenario = scenario("tok", new MatchAdminClient(world.baseUrl(), null, FakeMatchWorld.TIMEOUT));

        CheckReport report = scenario.run();

        assertThat(failed(report)).hasSize(2);
        assertThat(failed(report).get(0)).startsWith("Z8 读评分需要运维令牌").contains("XM_ADMIN_TOKEN");
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_FAIL step=z8-admin-token reason=Z8 读评分需要运维令牌");
        assertThat(report.items()).as("Z0 照常通过").anyMatch(i -> i.passed() && i.name().startsWith("Z0 "));
        assertThat(world.receivedCount(MessageIds.resolve(world.registry()).login())).isZero();
    }

    @Test
    void 两个客户端其实进的是同一个区_Z1的端点检查失败_指标里也没有跨区的局() {
        // 「另一个区」的客户端指错了：它 assign-gate 要的也是一区
        BattleCrossZoneScenario scenario = scenario("same", world.admin(), world.newClient(1));

        CheckReport report = scenario.run();

        assertThat(failed(report)).hasSize(2);
        assertThat(failed(report).get(0)).startsWith("Z1 区 1 与区 2 的 gate 端点不同").contains("区 2 → 127.0.0.1:" + world.gatePort(1));
        assertThat(failed(report).get(1)).isEqualTo("Z10 指标 xm_match_gather_zone_mix_total{mix=\"cross\"} 本轮至少 + 2：0.0 → 0.0");
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_FAIL step=z1-login ");
    }

    @Test
    void 两张票签自不同的节点实例_Z4中止_收尾照样给两人发148() {
        world.faults.add(Fault.DUEL_TICKETS_DIFFERENT_INSTANCE);
        BattleCrossZoneScenario scenario = scenario("inst", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report).get(0)).startsWith("Z4 两侧 battle_id 相同且非 0；两张票的 host:port、battle_node_id、battle_instance_id 相同")
                .contains("两张票不是同一个节点实例签的：A node=7 instance=fake-battle，B node=7 instance=fake-battle-other");
        assertThat(failed(report).get(1)).startsWith("流程中断");
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_FAIL step=z4-announce ");
        assertThat(names(report)).noneMatch(n -> n.startsWith("Z5 "));
        MatchSupport.Ids ids = MatchSupport.Ids.resolve(world.registry());
        assertThat(world.eventually(() -> world.receivedCount(ids.cancelQueue()) == 2)).as("失败路径也收尾").isTrue();
    }

    @Test
    void 后入队的B被放进0队_Z4的阵营与两局的评分都对不上() {
        world.faults.add(Fault.SWAP_DUEL_SIDES);
        BattleCrossZoneScenario scenario = scenario("swap", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report).get(0)).startsWith("Z4 143 的阵营里 A（先受理，是锚点）在 0 队、B 在 1 队").contains("[1, 0]");
        assertThat(failed(report).get(1)).startsWith("Z8 收到 150 后").contains("期望 +16.00");
        assertThat(failed(report)).anyMatch(f -> f.startsWith("Z9 第二局 143 的阵营里"));
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_FAIL step=z4-announce ");
    }

    @Test
    void 结算落地后大厅上没有150_Z7等满上限后失败_两局都记() {
        world.faults.add(Fault.NO_LOBBY_END);
        BattleCrossZoneScenario scenario = scenario("lobby", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).hasSize(2);
        assertThat(failed(report).get(0)).startsWith("Z7 两侧的大厅连接各收到这一局的 150").contains("A（区 1）：直连 150 之后 2 s 内大厅上没有 battle_id=");
        assertThat(failed(report).get(1)).startsWith("Z9 第二局 两侧的大厅连接各收到这一局的 150");
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_FAIL step=z7-lobby-end ");
    }

    @Test
    void 跨区的局被记成同区_只有Z10的指标咬住() {
        world.faults.add(Fault.CROSS_ZONE_COUNTED_SINGLE);
        BattleCrossZoneScenario scenario = scenario("mix", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).singleElement().isEqualTo("Z10 指标 xm_match_gather_zone_mix_total{mix=\"cross\"} 本轮至少 + 2：0.0 → 0.0");
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_FAIL step=z10-metrics ");
    }

    @Test
    void 上一局的ready票没被清掉_第二局再排回16001_Z9中止并给出排查提示() {
        world.faults.add(Fault.KEEP_READY_TICKET);
        BattleCrossZoneScenario scenario = scenario("ready", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report).get(0)).startsWith("Z9 第二局 A 发 157").contains("error_code=16001", "（16001：上一局的 ready 票据没有被自愈清掉");
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_FAIL step=z9-second-queue ");
        assertThat(report.items()).as("第一局完整通过").anyMatch(i -> i.passed() && i.name().startsWith("Z8 收到 150 后"));
    }

    @Test
    void 观众看到了技能冷却_Z5的首帧检查失败() {
        world.faults.add(Fault.OBSERVER_SEES_COOLDOWNS);
        BattleCrossZoneScenario scenario = scenario("cd", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report)).hasSize(2);
        assertThat(failed(report).get(0)).startsWith("Z5 C 直连后握手应答紧跟 161 {observer_count = 1}").contains("技能冷却没有清空");
        assertThat(failed(report).get(1)).startsWith("Z6 C 的直连上 ≥ 1 条 158");
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_FAIL step=z5-direct-watch ");
    }

    @Test
    void 观战成功的应答带了空的error_message_Z5中止() {
        world.faults.add(Fault.WATCH_OK_WITH_EMPTY_TIP);
        BattleCrossZoneScenario scenario = scenario("tip", world.admin());

        CheckReport report = scenario.run();

        assertThat(failed(report).get(0)).startsWith("Z5 区 2 的 C 发 163(battle_id) → 应答 battle_id 正确、没有 error_message")
                .contains("不得带 error_message 字段");
        assertThat(scenario.resultLine()).startsWith("CROSS_ZONE_MATCH_FAIL step=z5-direct-watch ");
    }

    @Test
    void 账号带xz标签_三个账号等长_两个区相同时构造即拒绝_选项里的visit_zone缺省2() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("battle-cross-zone", "--run-tag", "x1"), Map.of(RobotOptions.PASSWORD_ENV, "p"), 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.BATTLE_CROSS_ZONE);
        assertThat(options.zoneId()).isEqualTo(1);
        assertThat(options.visitZoneId()).isEqualTo(2);
        assertThat(BattleCrossZoneScenario.accountName(options.accountPrefix(), options.runTag(), "a")).isEqualTo("robot_java_xzx1_a");
        assertThat(BattleCrossZoneScenario.accountName("p_", "t", "c")).hasSameSizeAs(BattleCrossZoneScenario.accountName("p_", "t", "a"));
        assertThat(BattleCrossZoneScenario.accountName("p_", "t", "a")).isNotEqualTo(BattleSmokeScenario.accountName("p_", "t", "a"))
                .isNotEqualTo(CrossNodeScenario.accountName("p_", "t", "a"));
        assertThat(BattleCrossZoneScenario.LOBBY_END_TIMEOUT).isEqualTo(Duration.ofSeconds(25));

        RobotClient client = world.newClient(1);
        assertThatThrownBy(() -> new BattleCrossZoneScenario(client, world.flow(client), client, world.flow(client),
                new GatewayHttp(world.baseUrl(), FakeMatchWorld.TIMEOUT), world.registry(), world.admin(), "p_", "t", 2, 2, FakeMatchWorld.TIMEOUT))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("两个区不能相同");
    }
}
