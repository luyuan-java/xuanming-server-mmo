package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.robot.client.GatewayHttp;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RobotClient;
import com.game.robot.scenario.FakeTravelWorld.Fault;
import com.game.table.ConfigTables;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * reconnect 整条跑在 {@link FakeTravelWorld}（两个区各一台假 gate，背后同一份 login + scene 状态；地图与出生点取同步来的 World / BaseScene 表）上：
 * 重点是批次 5.4 加的跨区重连（经另一个区的入口重连 → GO-5 的 124 → 跟随回原处）与它中断时的收口——只记一条失败、第 4 步
 * （干净登出后按首登落默认主世界）照常跑。假服务端只钉探针这一侧，服务端行为要到双 zone 切片上才验证得了。
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReconnectScenarioTest {

    private static final Duration OBSERVE = Duration.ofSeconds(2);

    private FakeTravelWorld world;
    private Path tableDir;

    @BeforeEach
    void start() throws IOException {
        world = new FakeTravelWorld();
        tableDir = Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        ConfigTables tables = ConfigTables.load(tableDir);
        List<Integer> worlds = TeamScenario.worldConfigs(tables);
        Map<Integer, Vec3> spawns = new HashMap<>();
        for (int conf : worlds) {
            spawns.put(conf, ReconnectScenario.spawnOf(tables, conf));
        }
        world.worlds = worlds;
        world.spawns = spawns;
    }

    @AfterEach
    void stop() {
        world.close();
    }

    private ReconnectScenario scenario(CrossZoneGate.Mode mode, String runTag) {
        RobotClient home = world.newClient(FakeTravelWorld.HOME);
        RobotClient visit = world.newClient(FakeTravelWorld.VISIT);
        CrossZoneGate.Visit cross = new CrossZoneGate.Visit(mode, new GatewayHttp(world.baseUrl(), FakeTravelWorld.TIMEOUT), visit,
                world.flow(visit), FakeTravelWorld.HOME, FakeTravelWorld.VISIT);
        return new ReconnectScenario(world.flow(home), cross, MessageIds.resolve(world.registry()), world.registry(), tableDir, "robot_java_",
                runTag, FakeTravelWorld.TIMEOUT, OBSERVE);
    }

    private static List<String> failed(CheckReport report) {
        return report.items().stream().filter(i -> !i.passed()).map(i -> i.name() + "：" + i.detail()).toList();
    }

    private static List<String> passedNames(CheckReport report) {
        return report.items().stream().filter(CheckReport.Item::passed).map(CheckReport.Item::name).toList();
    }

    @Test
    void 跨区重连经访客区入口_GO5一条124跟随回原实例原位置_第4步照常() {
        CheckReport report = scenario(CrossZoneGate.Mode.REQUIRE, "ok").run();

        assertThat(failed(report)).as(report.render("reconnect")).isEmpty();
        assertThat(passedNames(report)).contains("跨区重连：跟随 124 后回到原场景实例", "跨区重连：回到原位置",
                "LeaveGame 后再进游戏按首登落默认主世界", "换了地图落出生点");
        assertThat(passedNames(report)).anyMatch(n -> n.startsWith("跨区重连：全程恰好一条 124"));
    }

    @Test
    void 跨区重连中断_跟随时握手被拒_只记一条失败_第4步照常跑() {
        world.faults.add(Fault.REJECT_REDIRECT_TICKET);

        CheckReport report = scenario(CrossZoneGate.Mode.REQUIRE, "cut").run();

        assertThat(failed(report)).as(report.render("reconnect")).singleElement().asString()
                .startsWith("跨区重连中断：").contains("token not for this gate");
        assertThat(passedNames(report)).as("中断之后经本区入口重新进游戏，第 4 步接着跑")
                .contains("LeaveGame 后再进游戏按首登落默认主世界", "换了地图落出生点");
        assertThat(report.items()).noneMatch(i -> i.name().equals("流程中断"));
    }

    @Test
    void cross_zone_skip_跨区重连不跑_只记观察_其余照常() {
        CheckReport report = scenario(CrossZoneGate.Mode.SKIP, "skip").run();

        assertThat(failed(report)).as(report.render("reconnect")).isEmpty();
        assertThat(report.notes()).anyMatch(n -> n.startsWith("跨区重连没跑：--cross-zone skip"));
        assertThat(passedNames(report)).contains("LeaveGame 后再进游戏按首登落默认主世界").noneMatch(n -> n.startsWith("跨区重连"));
    }
}
