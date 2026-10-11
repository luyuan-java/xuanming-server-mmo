package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.contract.MessageIdRegistry;
import com.game.robot.RobotOptions;
import com.game.robot.TravelOptions;
import com.game.robot.client.GatewayHttp;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RobotClient;
import com.game.robot.scenario.FakeTravelWorld.Fault;
import com.game.robot.scenario.FakeTravelWorld.TravelReply;
import com.game.table.ConfigTables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
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
 * travel 三个子命令对着本机假服务端（{@link FakeTravelWorld}：两个区各一台假 gate，背后同一份 login + scene 状态）整条跑：编排走得通、
 * 结果行与基线同形；假服务端故意做错一处时（124 先于应答、79 早于 124、一次传送两条 124、不核对持票者、不核对票据的区、连发 226 的
 * 应答配反、没有 GO-5、REDIRECTED 之后照常应答），对应那一步失败、结果行指向那一步。假服务端只钉探针这一侧——服务端的真实行为要到
 * 双 zone 切片上才验证得了。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class TravelScenarioTest {

    private static final Map<String, String> ENV = Map.of(RobotOptions.PASSWORD_ENV, "dev-secret");
    /**
     * 调快的节奏：相邻请求 5 ms、同步应答 3 s、一跳 5 s、等 23 3 s、晚关 300 ms、静默 200 ms、租约之后等 2.5 s、旁人 2 s、落位 50 ms、
     * 登出之后等离场落定 400 ms。
     */
    private static final TravelScenario.Tempo FAST = new TravelScenario.Tempo(Duration.ofMillis(5), Duration.ofSeconds(3), Duration.ofSeconds(5),
            Duration.ofSeconds(3), Duration.ofMillis(300), Duration.ofMillis(200), Duration.ofMillis(2500), Duration.ofSeconds(2),
            Duration.ofMillis(50), Duration.ofMillis(400));
    private static final Pattern OK_LINE = Pattern.compile("TRAVEL_SMOKE_OK player_id=(\\d+) home_zone=1 visit_zone=2"
            + " home_gate=127\\.0\\.0\\.1:(\\d+) visit_gate=127\\.0\\.0\\.1:(\\d+) back_gate=127\\.0\\.0\\.1:(\\d+) gold_home=11 gold_back=18"
            + " move_ack=true ticket_binding=true reject_tip=(\\d+) reject_map_tip=(\\d+) hops=3 reconnect_gate=127\\.0\\.0\\.1:(\\d+)");

    private FakeTravelWorld world;

    @BeforeEach
    void start() throws IOException {
        world = new FakeTravelWorld();
    }

    @AfterEach
    void stop() {
        world.close();
    }

    private TravelScenario scenario(String subcommand, String runTag, String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of(subcommand, "--run-tag", runTag, "--dwell-ms", "0"));
        args.addAll(List.of(extra));
        RobotOptions options = RobotOptions.parse(args, ENV, 0L);
        RobotClient home = world.newClient(options.zoneId());
        RobotClient visit = world.newClient(options.visitZoneId());
        return new TravelScenario(home, world.flow(home), visit, world.flow(visit), new GatewayHttp(world.baseUrl(), FakeTravelWorld.TIMEOUT),
                world.registry(), options, TravelOptions.parse(args, ENV), FAST);
    }

    private TravelScenario roundTrip(String runTag) throws Exception {
        return scenario("travel", runTag, "--travel-scene-config", Integer.toString(FakeTravelWorld.OTHER_CONF));
    }

    private static List<String> failed(CheckReport report) {
        return report.items().stream().filter(i -> !i.passed()).map(i -> i.name() + "：" + i.detail()).toList();
    }

    private static List<String> names(CheckReport report) {
        return report.items().stream().map(CheckReport.Item::name).toList();
    }

    // ---------------------------------------------------------------- 通过的路径

    @Test
    void travel_T0到T11全过_结果行与基线同形_外加hops与reconnect_gate() throws Exception {
        TravelScenario scenario = roundTrip("ok");

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("travel")).isEmpty();
        assertThat(report.passed()).isTrue();
        List<String> names = names(report);
        for (String step : List.of("T0 ", "T1 ", "T2 ", "T3 ", "T4 ", "T5 ", "T5a ", "T6 ", "T7 ", "T7b ", "T8 ", "T8b ", "T9 ", "T10 ")) {
            assertThat(names).as(step).anyMatch(n -> n.startsWith(step));
        }
        assertThat(names).anyMatch(n -> n.startsWith("T11 留底：124 共 3 条"));

        Matcher line = OK_LINE.matcher(scenario.resultLine());
        assertThat(line.matches()).as(scenario.resultLine()).isTrue();
        int home = world.gatePort(FakeTravelWorld.HOME);
        int visit = world.gatePort(FakeTravelWorld.VISIT);
        assertThat(Long.parseUnsignedLong(line.group(1))).as("角色号按无符号十进制").isNegative();
        assertThat(List.of(line.group(2), line.group(3), line.group(4), line.group(7)))
                .containsExactly(Integer.toString(home), Integer.toString(visit), Integer.toString(home), Integer.toString(visit));
        assertThat(line.group(5)).isEqualTo(Integer.toString(TravelScenario.TIP_ZONE_INVALID));
        assertThat(line.group(6)).isEqualTo(Integer.toString(TravelScenario.TIP_MAP_INVALID));

        assertThat(world.creates()).as("只有 A、B 首登各建一次角；严格重登绝不建角").isEqualTo(2);
        int travel = world.registry().requireId(TravelScenario.TRAVEL_SERVICE, TravelScenario.TRAVEL_METHOD);
        assertThat(world.receivedCount(travel)).as("226：T2 三条、T3 一条、T4 一条、T5 一条、T9 两条").isEqualTo(8);
        assertThat(world.gold(TravelScenario.accountName("robot_java_", "ok", "a"))).isEqualTo(18);

        // T9：假服务端先回第二条的 13000、后回第一条的 {0}——按到达次序配对就会判反，场景按信封 id 配对才过得了
        List<TravelReply> replies = world.travelReplies();
        TravelReply first = replies.get(replies.size() - 1);
        TravelReply second = replies.get(replies.size() - 2);
        assertThat(second.tip()).isEqualTo(TravelScenario.TIP_IN_TRANSFER);
        assertThat(first.tip()).isZero();
        assertThat(first.requestId()).as("先回的 13000 属于后发的那条").isLessThan(second.requestId());
        assertThat(report.notes()).anyMatch(n -> n.contains("第二条的 " + TravelScenario.TIP_IN_TRANSFER + " 先到"));
        assertThat(report.notes()).anyMatch(n -> n.contains("移动探针") && n.contains("收到 137"));
    }

    @Test
    void travel_abandon_两个号拿到124都不跟随_一个经出发区落地_一个经访客区落在目标图() throws Exception {
        TravelScenario scenario = scenario("travel-abandon", "ab", "--travel-scene-config", Integer.toString(FakeTravelWorld.OTHER_CONF));

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("travel-abandon")).isEmpty();
        assertThat(scenario.resultLine()).matches("TRAVEL_ABANDON_OK player_a=\\d+ player_b=\\d+ home_zone=1 visit_zone=2 pending_scene_config="
                + FakeTravelWorld.OTHER_CONF + " gold_home=11");
        assertThat(world.creates()).isEqualTo(2);
        assertThat(names(report)).anyMatch(n -> n.startsWith("B 落在 226 请求的那张地图"));
    }

    @Test
    void travel_abandon_没给目标图时取World表的第二张世界地图() throws Exception {
        List<Integer> worlds = TeamScenario.worldConfigs(ConfigTables.load(tableDir()));
        world.worlds = worlds;
        TravelScenario scenario = scenario("travel-abandon", "ab2", "--table-dir", tableDir().toString());

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("travel-abandon")).isEmpty();
        assertThat(report.notes()).anyMatch(n -> n.startsWith("目标图取 World 表的第二张世界地图 " + worlds.get(1)));
        assertThat(scenario.resultLine()).contains(" pending_scene_config=" + worlds.get(1) + " ");
    }

    @Test
    void travel_abandon_没给目标图且读不到World表_preflight失败() throws Exception {
        TravelScenario scenario = scenario("travel-abandon", "ab3", "--table-dir", "no-such-dir");

        CheckReport report = scenario.run();

        assertThat(report.passed()).isFalse();
        assertThat(scenario.resultLine()).startsWith("TRAVEL_ABANDON_FAIL step=preflight reason=流程中断：travel-abandon 要一张非默认的地图");
        assertThat(world.creates()).isZero();
    }

    @Test
    void travel_long_访客区断线越过租约后经出发区重登_落出发区没有124() throws Exception {
        TravelScenario scenario = scenario("travel-long", "lg");

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("travel-long")).isEmpty();
        Matcher line = Pattern.compile("TRAVEL_LONG_OK player_id=\\d+ home_zone=1 visit_zone=2 visit_gate=127\\.0\\.0\\.1:(\\d+)"
                + " back_gate=127\\.0\\.0\\.1:(\\d+) waited_ms=(\\d+)").matcher(scenario.resultLine());
        assertThat(line.matches()).as(scenario.resultLine()).isTrue();
        assertThat(line.group(1)).isEqualTo(Integer.toString(world.gatePort(FakeTravelWorld.VISIT)));
        assertThat(line.group(2)).isEqualTo(Integer.toString(world.gatePort(FakeTravelWorld.HOME)));
        assertThat(Long.parseLong(line.group(3))).isGreaterThanOrEqualTo(FAST.leaseWait().toMillis());
    }

    @Test
    void 出发区不止一台gate_abandon的A重登换了一台_照常通过_只记观察() throws Exception {
        // assign-gate(出发区) 轮流给：A 登录第一台、A 重登第二台、B 登录第一台
        int second = world.addGate(FakeTravelWorld.HOME);
        TravelScenario scenario = scenario("travel-abandon", "mg", "--travel-scene-config", Integer.toString(FakeTravelWorld.OTHER_CONF));

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("travel-abandon")).isEmpty();
        assertThat(report.notes()).anyMatch(n -> n.startsWith("A 重登连的 gate 127.0.0.1:" + second) && n.contains("不止一台 gate"));
    }

    @Test
    void 出发区不止一台gate_long重登换了一台_照常通过_结果行写实际连的gate() throws Exception {
        int second = world.addGate(FakeTravelWorld.HOME);
        TravelScenario scenario = scenario("travel-long", "mg");

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("travel-long")).isEmpty();
        assertThat(scenario.resultLine()).contains(" back_gate=127.0.0.1:" + second + " ");
        assertThat(report.notes()).anyMatch(n -> n.startsWith("重登连的 gate 127.0.0.1:" + second));
    }

    // ---------------------------------------------------------------- T10：登出之后离场是异步的

    @Test
    void 离场落定得慢于发17_T10先等离场落定再经访客区登录_没有124() throws Exception {
        // 17 之后 150 ms 才清位置记录：不等就去访客区登录，访客区按规格走 GO-5（读到的仍是出发区在线）；场景先等 400 ms
        world.leaveLag = Duration.ofMillis(150);
        TravelScenario scenario = roundTrip("lag");

        CheckReport report = scenario.run();

        assertThat(failed(report)).as(report.render("travel")).isEmpty();
        assertThat(names(report)).anyMatch(n -> n.startsWith("T10 登出（等 " + FAST.leaveSettle().toMillis() + " ms 离场落定）后经访客区入口登录"));
    }

    @Test
    void 离场迟迟不落定_T10收到GO5的124_失败详情点明可能是离场没落定() throws Exception {
        world.leaveLag = Duration.ofSeconds(30);
        TravelScenario scenario = roundTrip("stuck");

        CheckReport report = scenario.run();

        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=t10-leave-enter-visit reason=T10 登出");
        assertThat(failed(report)).anyMatch(f -> f.startsWith("T10 ") && f.contains("走了 GO-5") && f.contains("登出墓碑") && f.contains("竞态"));
    }

    // ---------------------------------------------------------------- 前置

    @Test
    void 访客区不是OPEN_preflight失败_一个号都不登录() throws Exception {
        world.gateway().status(FakeTravelWorld.VISIT, "MAINTENANCE");
        TravelScenario scenario = roundTrip("pf");

        CheckReport report = scenario.run();

        assertThat(report.passed()).isFalse();
        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=preflight reason=").contains("XM_ZONES=2");
        assertThat(world.gateway().assignCalls(FakeTravelWorld.HOME) + world.gateway().assignCalls(FakeTravelWorld.VISIT)).isZero();
    }

    // ---------------------------------------------------------------- 假服务端做错一处

    @Test
    void 一次传送推了两条124_T5失败_留底也报恰好一条() throws Exception {
        world.faults.add(Fault.DOUBLE_REDIRECT);
        TravelScenario scenario = roundTrip("dbl");

        CheckReport report = scenario.run();

        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=t5-travel reason=T5 旧连接上恰好一条 124");
        assertThat(failed(report)).anyMatch(f -> f.startsWith("T11 留底：") && f.contains("期望恰好 1 条"));
    }

    @Test
    void 跟随124之前就推了79_留底判79早于124() throws Exception {
        world.faults.add(Fault.SCENE_BEFORE_REDIRECT);
        TravelScenario scenario = roundTrip("early");

        CheckReport report = scenario.run();

        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=t11-summary reason=T11 留底：");
        assertThat(failed(report)).singleElement().asString().contains("79 早于 124", "T5 去程");
    }

    @Test
    void 先推124后回226的应答_T5判应答必须先于124() throws Exception {
        world.faults.add(Fault.REDIRECT_BEFORE_REPLY);
        TravelScenario scenario = roundTrip("order");

        CheckReport report = scenario.run();

        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=t5-travel reason=T5 226 应答 {0} 先于 124");
        assertThat(failed(report)).anyMatch(f -> f.startsWith("T9 第一条的应答 {0} 先于 124"));
    }

    @Test
    void login不核对持票者_T8的2011失败() throws Exception {
        world.faults.add(Fault.NO_TICKET_BINDING);
        TravelScenario scenario = roundTrip("bind");

        CheckReport report = scenario.run();

        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=t8-ticket-holder reason=T8 26 EnterGame(B 的角色) → "
                + TravelScenario.TIP_TICKET_HOLDER);
        assertThat(failed(report)).anyMatch(f -> f.contains("login 没有核对持票者"));
    }

    @Test
    void gate不核对票据的区_T8b的WRONG_ZONE失败() throws Exception {
        world.faults.add(Fault.NO_ZONE_CHECK);
        TravelScenario scenario = roundTrip("zone");

        CheckReport report = scenario.run();

        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=t8b-wrong-zone reason=");
        assertThat(failed(report)).singleElement().asString().contains("success=true");
    }

    @Test
    void 连发两条226的应答按id配反了_T9失败() throws Exception {
        world.faults.add(Fault.SWAPPED_DOUBLE_REPLY);
        TravelScenario scenario = roundTrip("swap");

        CheckReport report = scenario.run();

        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=t9-double-travel reason=T9 连发两条 226");
        assertThat(failed(report)).anyMatch(f -> f.startsWith("T9 第二条") && f.endsWith("error_message.id=0"));
    }

    @Test
    void 没有GO5_经出发区重连就地进场_T7中止() throws Exception {
        world.faults.add(Fault.NO_GO5);
        TravelScenario scenario = roundTrip("go5");

        CheckReport report = scenario.run();

        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=t7-reconnect-home reason=T7 经出发区入口重连");
        assertThat(names(report)).noneMatch(n -> n.startsWith("T8 "));
    }

    @Test
    void 推出124之后旧会话照常应答_T5a失败() throws Exception {
        world.faults.add(Fault.REDIRECTED_STILL_ANSWERS);
        TravelScenario scenario = roundTrip("linger");

        CheckReport report = scenario.run();

        assertThat(scenario.resultLine()).startsWith("TRAVEL_SMOKE_FAIL step=t5a-old-connection reason=T5a");
        assertThat(failed(report)).singleElement().asString().contains("收到了 77 的应答");
    }

    // ---------------------------------------------------------------- 子模式、消息号、tip 与节奏的约定

    @Test
    void 子模式与标记的对应_标记是大写字母与下划线_不是travel的子命令不认() {
        assertThat(TravelScenario.Mode.of(RobotOptions.Scenario.TRAVEL).marker()).isEqualTo("TRAVEL_SMOKE");
        assertThat(TravelScenario.Mode.of(RobotOptions.Scenario.TRAVEL_ABANDON).marker()).isEqualTo("TRAVEL_ABANDON");
        assertThat(TravelScenario.Mode.of(RobotOptions.Scenario.TRAVEL_LONG).marker()).isEqualTo("TRAVEL_LONG");
        for (TravelScenario.Mode mode : TravelScenario.Mode.values()) {
            assertThat(mode.marker()).matches("[A-Z0-9_]+");
        }
        assertThatThrownBy(() -> TravelScenario.Mode.of(RobotOptions.Scenario.TEAM)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 两个区相同时构造即拒绝_226与124的消息号按名字解析得到_tip取自生成的表() throws Exception {
        MessageIdRegistry registry = MessageIdRegistry.loadFromClasspath();
        RobotOptions sameZone = RobotOptions.parse(List.of("smoke", "--zone", "2"), ENV, 0L);
        assertThatThrownBy(() -> new TravelScenario(null, null, null, null, null, registry, sameZone, TravelOptions.parse(List.of(), ENV)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("两个区不能相同");

        assertThat(registry.byId(registry.requireId(TravelScenario.TRAVEL_SERVICE, TravelScenario.TRAVEL_METHOD)).orElseThrow()
                .requestPrototype().getDescriptorForType().getName()).isEqualTo("TravelToZoneRequest");
        assertThat(TravelScenario.accountName("p_", "x9", "b")).isEqualTo("p_tvx9_b");
        assertThat(List.of(TravelScenario.TIP_ZONE_INVALID, TravelScenario.TIP_MAP_INVALID, TravelScenario.TIP_IN_TEAM,
                TravelScenario.TIP_TRAVEL_FAILED, TravelScenario.TIP_IN_TRANSFER, TravelScenario.TIP_TICKET_HOLDER))
                .as("与契约的 tip 号一致（这里写数字只为钉住生成的表没被改动）").containsExactly(3024, 3007, 3026, 3027, 13000, 2011);
        assertThat(Integer.toUnsignedString(TravelScenario.MISSING_MAP)).isEqualTo("4000000000");
        assertThat(MessageIds.resolve(registry).notifyEnterScene()).isPositive();
    }

    @Test
    void 标准节奏_请求间隔1点1秒_一跳60秒_等79的上限不低于一跳() {
        TravelScenario.Tempo standard = TravelScenario.Tempo.standard(Duration.ofSeconds(2));
        assertThat(standard.requestSpacing()).isEqualTo(Duration.ofMillis(1100));
        assertThat(standard.syncReply()).isEqualTo(Duration.ofSeconds(10));
        assertThat(standard.hop()).isEqualTo(TravelScenario.HOP_TIMEOUT).isEqualTo(Duration.ofSeconds(60));
        assertThat(standard.lateClose()).isEqualTo(Duration.ofSeconds(3));
        assertThat(standard.leaseWait()).as("越过 30 s 的重连租约").isGreaterThan(Duration.ofSeconds(30));
        assertThat(standard.leaveSettle()).as("T10 登出之后等离场落定").isEqualTo(Duration.ofSeconds(3));
        assertThat(standard.observe()).as("观察上限至少 3 s").isEqualTo(Duration.ofSeconds(3));
        assertThat(TravelScenario.Tempo.standard(Duration.ofSeconds(7)).observe()).isEqualTo(Duration.ofSeconds(7));
    }

    /** 同步来的配置表目录（测试在模块目录下跑；从仓库根跑时取根下的）。 */
    private static Path tableDir() {
        return Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables") : Path.of("config-data/tables");
    }
}
