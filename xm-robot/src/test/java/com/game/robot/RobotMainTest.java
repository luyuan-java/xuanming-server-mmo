package com.game.robot;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 入口把批次 6.4 的三个匹配类子命令与 team 接到了各自的场景上，并在报告之后另起一行写结果行（{@code XXX_OK …} / {@code XXX_FAIL step=… reason=…}）。
 * 这里对着一个没人监听的端口跑：第一步就连不上，验证的是接线、退出码与结果行的位置，不是场景本身。
 */
class RobotMainTest {

    private record Run(int exit, String out) {

        /** 最后一个非空行。 */
        String lastLine() {
            List<String> lines = out.lines().filter(l -> !l.isBlank()).toList();
            return lines.get(lines.size() - 1);
        }
    }

    private static Run run(String subcommand, int closedPort) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        String nowhere = "http://127.0.0.1:" + closedPort;
        // 令牌从环境变量给：不去读工作目录下的 run/xm-admin-token，结果与在哪个目录跑无关
        Map<String, String> env = Map.of(RobotOptions.PASSWORD_ENV, "p", "XM_ADMIN_TOKEN", "tok");
        int exit = RobotMain.run(List.of(subcommand, "--gateway", nowhere, "--match-admin-url", nowhere, "--run-tag", "t1",
                "--connect-timeout-ms", "3000", "--request-timeout-ms", "3000"), env, sink, sink);
        return new Run(exit, bytes.toString(StandardCharsets.UTF_8));
    }

    private static int closedPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void 匹配类三个子命令与team_连不上服务端时退出码1_报告之后的最后一行是结果行() throws Exception {
        int port = closedPort();

        Run smoke = run("battle-smoke", port);
        assertThat(smoke.exit()).isEqualTo(RobotMain.EXIT_FAIL);
        assertThat(smoke.out()).contains("== xm-robot battle-smoke：robot_java_bmt1_a 等", "match-admin=http://127.0.0.1:" + port, "结论：失败");
        assertThat(smoke.lastLine()).as("先抓 xm-match 的指标：管理端口不通在登录之前就说清楚")
                .startsWith("BATTLE_SMOKE_FAIL step=1-login reason=流程中断：").contains("xm-match");

        Run activity = run("match-activity", port);
        assertThat(activity.exit()).isEqualTo(RobotMain.EXIT_FAIL);
        assertThat(activity.out()).contains("== xm-robot match-activity：robot_java_mat1_a 等");
        assertThat(activity.lastLine()).startsWith("MATCH_ACTIVITY_FAIL step=1-login reason=流程中断：").contains("assign-gate");

        Run fiveVsFive = run("match-5v5", port);
        assertThat(fiveVsFive.exit()).isEqualTo(RobotMain.EXIT_FAIL);
        assertThat(fiveVsFive.out()).contains("== xm-robot match-5v5：robot_java_m5t1_0 等 10 个账号");
        assertThat(fiveVsFive.lastLine()).startsWith("MATCH_5V5_FAIL step=1-login reason=流程中断：").contains("assign-gate");

        Run team = run("team", port);
        assertThat(team.exit()).isEqualTo(RobotMain.EXIT_FAIL);
        assertThat(team.lastLine()).startsWith("TEAM_SMOKE_FAIL step=login reason=流程中断：").contains("assign-gate");
    }

    @Test
    void 别的子命令没有结果行_报告的最后一行仍是结论() throws Exception {
        // movement 第一步同样连不上；它不是匹配类场景，不写 XXX_OK / XXX_FAIL
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        int exit = RobotMain.run(List.of("movement", "--gateway", "http://127.0.0.1:" + closedPort(), "--run-tag", "t1",
                "--connect-timeout-ms", "3000"), Map.of(RobotOptions.PASSWORD_ENV, "p"), sink, sink);
        Run run = new Run(exit, bytes.toString(StandardCharsets.UTF_8));
        assertThat(run.exit()).isEqualTo(RobotMain.EXIT_FAIL);
        assertThat(run.lastLine()).startsWith("结论：失败");
        assertThat(run.out()).doesNotContain("_FAIL step=").doesNotContain("_OK");
    }
}
