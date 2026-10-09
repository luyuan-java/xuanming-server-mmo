package com.game.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 入口把 travel 三个子命令接到了 {@code TravelScenario} 上（批次 5.4）。对着一个没人监听的端口跑，验证的是接线、退出码与结果行的位置：
 * 不管场景停在哪一步（现在是骨架的 {@code not-implemented}，步骤落地之后是连不上 gateway 的那一步），最后一行都得是
 * {@code <标记>_FAIL step=…}，不能没有结果行、更不能是通过。
 */
class RobotMainTravelTest {

    private record Run(int exit, String out) {

        /** 最后一个非空行。 */
        String lastLine() {
            List<String> lines = out.lines().filter(l -> !l.isBlank()).toList();
            return lines.get(lines.size() - 1);
        }
    }

    private static Run run(int closedPort, String... subcommandAndOptions) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of(subcommandAndOptions));
        args.addAll(List.of("--gateway", "http://127.0.0.1:" + closedPort, "--run-tag", "t1", "--connect-timeout-ms", "3000",
                "--request-timeout-ms", "3000"));
        int exit = RobotMain.run(args, Map.of(RobotOptions.PASSWORD_ENV, "p"), sink, sink);
        return new Run(exit, bytes.toString(StandardCharsets.UTF_8));
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void travel三个子命令_服务端不在时退出码1_标题带两个区与附加选项_最后一行是各自标记的FAIL() throws Exception {
        int port = closedPort();

        Run travel = run(port, "travel", "--dwell-ms", "0", "--travel-scene-config", "2");
        assertThat(travel.exit()).isEqualTo(RobotMain.EXIT_FAIL);
        assertThat(travel.out()).contains("== xm-robot travel：robot_java_tvt1_a 等，gateway=http://127.0.0.1:" + port
                + " zone=1 visit-zone=2 dwell-ms=0 travel-scene-config=2 开始 ==", "结论：失败");
        assertThat(travel.lastLine()).startsWith("TRAVEL_SMOKE_FAIL step=").contains(" reason=");
        assertThat(travel.out()).doesNotContain("TRAVEL_SMOKE_OK");

        Run abandon = run(port, "travel-abandon");
        assertThat(abandon.exit()).isEqualTo(RobotMain.EXIT_FAIL);
        assertThat(abandon.out()).contains("== xm-robot travel-abandon：robot_java_tvt1_", "dwell-ms=35000 travel-scene-config=0");
        assertThat(abandon.lastLine()).startsWith("TRAVEL_ABANDON_FAIL step=");

        // 反向：从区 2 出发
        Run longStay = run(port, "travel-long", "--zone", "2", "--visit-zone", "1");
        assertThat(longStay.exit()).isEqualTo(RobotMain.EXIT_FAIL);
        assertThat(longStay.out()).contains("== xm-robot travel-long：", "zone=2 visit-zone=1");
        assertThat(longStay.lastLine()).startsWith("TRAVEL_LONG_FAIL step=");
    }

    @Test
    void travel两个区相同_参数错误退出码2_不建任何连接() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        int exit = RobotMain.run(List.of("travel", "--zone", "2"), Map.of(RobotOptions.PASSWORD_ENV, "p"), sink, sink);

        assertThat(exit).isEqualTo(RobotMain.EXIT_USAGE);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("参数错误：travel 要两个不同的区：--visit-zone 与 --zone 都是 2")
                .doesNotContain("开始 ==");
    }

    @Test
    void team带cross_zone_require而两个区相同_参数错误退出码2() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(bytes, true, StandardCharsets.UTF_8);

        int exit = RobotMain.run(List.of("team", "--zone", "2", "--cross-zone", "require"), Map.of(RobotOptions.PASSWORD_ENV, "p"), sink, sink);

        assertThat(exit).isEqualTo(RobotMain.EXIT_USAGE);
        assertThat(bytes.toString(StandardCharsets.UTF_8)).contains("参数错误：team --cross-zone require 要两个不同的区").doesNotContain("开始 ==");
    }
}
