package com.game.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** travel 三个子命令的附加选项（批次 5.4）：缺省值、优先级、两种写法、停留可以是 0、校验、写错了在建连接之前就按参数错误退出。 */
class TravelOptionsTest {

    private static final Map<String, String> ENV = Map.of(RobotOptions.PASSWORD_ENV, "dev-secret");

    @Test
    void 缺省值_停留35秒_不指定地图() throws Exception {
        TravelOptions o = TravelOptions.parse(List.of("travel"), Map.of());

        assertThat(o.dwell()).as("越过 30 s 的重连租约").isEqualTo(Duration.ofSeconds(35));
        assertThat(o.sceneConfigId()).as("0 = 由目标区挑默认主世界").isZero();
    }

    @Test
    void 命令行优先于环境变量_两种写法都认_别的选项照常跳过() throws Exception {
        Map<String, String> env = Map.of("XM_ROBOT_TRAVEL_DWELL_MS", "1000", "XM_ROBOT_TRAVEL_SCENE_CONFIG", "5");

        TravelOptions fromEnv = TravelOptions.parse(List.of("travel"), env);
        assertThat(fromEnv.dwell()).isEqualTo(Duration.ofSeconds(1));
        assertThat(fromEnv.sceneConfigId()).isEqualTo(5);

        TravelOptions fromArgs = TravelOptions.parse(List.of("--dwell-ms=2500", "travel-long", "--zone", "2", "--visit-zone", "1",
                "--travel-scene-config", "2", "--slow"), env);
        assertThat(fromArgs.dwell()).isEqualTo(Duration.ofMillis(2500));
        assertThat(fromArgs.sceneConfigId()).isEqualTo(2);
    }

    @Test
    void 停留可以是0_这是与别的时长选项不同的地方() throws Exception {
        assertThat(TravelOptions.parse(List.of("travel", "--dwell-ms", "0"), Map.of()).dwell()).isEqualTo(Duration.ZERO);
        assertThat(TravelOptions.parse(List.of("travel"), Map.of("XM_ROBOT_TRAVEL_DWELL_MS", "0")).dwell()).isEqualTo(Duration.ZERO);
        assertThat(TravelOptions.parse(List.of("travel", "--dwell-ms", "600000"), Map.of()).dwell()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void 非法取值_报参数错误() {
        assertThatThrownBy(() -> TravelOptions.parse(List.of("travel", "--dwell-ms", "-1"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--dwell-ms 应在 0–600000 毫秒之间：-1");
        assertThatThrownBy(() -> TravelOptions.parse(List.of("travel", "--dwell-ms", "600001"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--dwell-ms");
        // 规格里写过的「35s」不合选项文法：时长一律是整数毫秒
        assertThatThrownBy(() -> TravelOptions.parse(List.of("travel", "--dwell-ms", "35s"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--dwell-ms 不是整数毫秒：35s");
        assertThatThrownBy(() -> TravelOptions.parse(List.of("travel", "--travel-scene-config", "-2"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--travel-scene-config 应 ≥ 0");
        assertThatThrownBy(() -> TravelOptions.parse(List.of("travel", "--travel-scene-config", "main"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--travel-scene-config 不是整数：main");
        assertThatThrownBy(() -> TravelOptions.parse(List.of("travel", "--travel-scene-config"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("缺少取值");
        assertThatThrownBy(() -> TravelOptions.parse(List.of("travel", "--dwell", "35s"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("未知选项：--dwell");
    }

    @Test
    void 与主选项共存_主选项认得这两个名字_帮助里列出来() throws Exception {
        RobotOptions o = RobotOptions.parse(List.of("travel", "--run-tag", "t1", "--dwell-ms", "0", "--travel-scene-config", "2"), ENV, 0L);

        assertThat(o.scenario()).isEqualTo(RobotOptions.Scenario.TRAVEL);
        assertThat(RobotOptions.usage()).contains("--dwell-ms <值>", "XM_ROBOT_TRAVEL_DWELL_MS", "缺省 35000", "--travel-scene-config <值>",
                "XM_ROBOT_TRAVEL_SCENE_CONFIG");
    }

    @Test
    void travel的附加选项写错了_RobotMain退出码为2_不建任何连接() {
        for (String sub : List.of("travel", "travel-abandon", "travel-long")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PrintStream sink = new PrintStream(out, true, StandardCharsets.UTF_8);

            int exit = RobotMain.run(List.of(sub, "--dwell-ms", "soon"), ENV, sink, sink);

            assertThat(exit).as(sub).isEqualTo(RobotMain.EXIT_USAGE);
            assertThat(out.toString(StandardCharsets.UTF_8)).contains("参数错误：--dwell-ms 不是整数毫秒：soon").doesNotContain("开始 ==");
        }
    }

    @Test
    void 别的子命令不看这两个选项_写错了也不拦() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(out, true, StandardCharsets.UTF_8);

        // movement 第一步就连不上（端口 1 没人监听）：退出码 1 说明它走到了建连，而不是倒在参数上
        int exit = RobotMain.run(List.of("movement", "--dwell-ms", "soon", "--gateway", "http://127.0.0.1:1", "--run-tag", "t1",
                "--connect-timeout-ms", "3000"), ENV, sink, sink);

        assertThat(exit).isEqualTo(RobotMain.EXIT_FAIL);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("== xm-robot movement：").doesNotContain("参数错误");
    }
}
