package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.robot.CrashWindowOptions;
import com.game.robot.CrashWindowOptions.Variant;
import com.game.robot.RobotOptions;
import com.game.robot.scenario.BattleCrashChecks.Stage;
import com.game.robot.scenario.BattleCrashChecks.State;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code tools/local/battle-crash-window.sh}（故障变体的编排脚本）不走样：它「按 start-slice.sh 的方式」单独重启一个进程，所以重启用的函数、
 * 端口表、命令行、环境变量必须与 {@code start-slice.sh} 逐字相同——以后有人改了 start-slice.sh 而没有同步这里，本测试就会失败。
 * 另外钉住它与 robot 之间的约定（状态文件的断点信号、缺省路径、两次调用的命令行、判定版本标记、双 scene 切片的指标地址），以及脚本自己
 * 容易写错的两处：放后台的必须是 JVM 本身（否则杀不到它）、旧的 robot 包在前置里被拒绝。脚本本身要活的切片才跑得起来，这里只做文本层面的核对。
 */
class SliceScriptsTest {

    private static String startSlice;
    private static String crashWindow;

    @BeforeAll
    static void 读两份脚本() throws IOException {
        Path dir = Files.isDirectory(Path.of("../tools/local")) ? Path.of("../tools/local") : Path.of("tools/local");
        Assumptions.assumeTrue(Files.isRegularFile(dir.resolve("start-slice.sh")), "找不到 tools/local（单独构建 xm-robot）");
        startSlice = Files.readString(dir.resolve("start-slice.sh"), StandardCharsets.UTF_8);
        crashWindow = Files.readString(dir.resolve("battle-crash-window.sh"), StandardCharsets.UTF_8);
    }

    /** 顶格定义的 shell 函数体（{@code 名字() {} 到下一行顶格的 {@code }}）；没有这个函数为 null。 */
    private static String function(String script, String name) {
        Matcher m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n(.*?)^}\\n").matcher(script);
        return m.find() ? m.group(1) : null;
    }

    private static List<String> matches(String script, String regex) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile(regex, Pattern.MULTILINE).matcher(script);
        while (m.find()) {
            found.add(m.group(m.groupCount() == 0 ? 0 : 1));
        }
        return found;
    }

    @Test
    void 重启用的四个函数与start_slice逐字相同() {
        for (String name : List.of("launch", "wait_port", "wait_world_channels", "wait_battle_ready")) {
            String original = function(startSlice, name);
            assertThat(original).as("start-slice.sh 里有 %s()", name).isNotNull();
            assertThat(function(crashWindow, name)).as("battle-crash-window.sh 的 %s() 与 start-slice.sh 逐字相同", name).isEqualTo(original);
        }
    }

    @Test
    void 端口表与场景节点表与start_slice相同() {
        List<String> ports = matches(startSlice, "^(BATTLE_[A-Z]+_PORT=\\d+)$");
        assertThat(ports).containsExactly("BATTLE_RPC_PORT=21200", "BATTLE_CLIENT_PORT=12000", "BATTLE_MGMT_PORT=18112");
        assertThat(matches(crashWindow, "^(BATTLE_[A-Z]+_PORT=\\d+)$")).isEqualTo(ports);

        Pattern nodes = Pattern.compile("(?ms)^SCENE_NODES=\\(\\n(.*?)^\\)\\n");
        Matcher original = nodes.matcher(startSlice);
        Matcher copy = nodes.matcher(crashWindow);
        assertThat(original.find()).isTrue();
        assertThat(copy.find()).isTrue();
        assertThat(copy.group(1)).isEqualTo(original.group(1));
        assertThat(original.group(1)).as("第一行是 xm-scene：脚本重启的就是它").startsWith("  \"xm-scene   21000 21100 18104\"\n");
    }

    @Test
    void 重启scene与battle的命令行和就绪判断与start_slice相同() {
        String sceneLaunch = "    launch \"$instance\" xm-scene --server.port=\"$mgmt\" --xm.scene.link-port=\"$link\" \\\n"
                + "        --xm.scene.asset-rpc-port=\"$rpc\" --xm.scene.scene-manager-url=\"$XM_SCENE_MANAGER_URL\"\n"
                + "    wait_port \"$link\" \"$instance\"\n"
                + "    wait_port \"$rpc\" \"$instance\"\n"
                + "    wait_world_channels \"$instance\" \"$mgmt\"\n";
        assertThat(function(startSlice, "start_scene_nodes")).as("start-slice.sh 起 scene 的那几行").contains(sceneLaunch);
        // 本脚本里少一层 for 循环，缩进少两格
        assertThat(function(crashWindow, "restart_scene_node")).contains(sceneLaunch.replace("\n    ", "\n  ").replaceFirst("^    ", "  "));
        assertThat(startSlice).contains("XM_SCENE_MANAGER_URL=\"${XM_SCENE_MANAGER_URL:-tri://127.0.0.1:20882}\"");
        assertThat(crashWindow).contains("XM_SCENE_MANAGER_URL=\"${XM_SCENE_MANAGER_URL:-tri://127.0.0.1:20882}\"");

        assertThat(startSlice).contains("    launch xm-battle xm-battle\n    wait_battle_ready\n");
        assertThat(function(crashWindow, "restart_battle")).startsWith("  launch xm-battle xm-battle\n  wait_battle_ready\n");
    }

    @Test
    void start_slice导出的每个环境变量本脚本都带上_缺省值相同() {
        // 带缺省值的：整行相同
        List<String> defaulted = matches(startSlice, "^(export (XM_[A-Z_]+)=\"\\$\\{XM_[A-Z_]+:-[^}]*}\")$");
        assertThat(defaulted).as("start-slice.sh 里带缺省值导出的变量").hasSizeGreaterThanOrEqualTo(10);
        for (String line : defaulted) {
            assertThat(crashWindow).as("本脚本有同一行 %s", line).contains("\n" + line + "\n");
        }
        // 没设就生成、写进 run/ 的秘密：本脚本从同一个文件读回
        Set<String> exported = new LinkedHashSet<>(matches(startSlice, "^export (XM_[A-Z_]+)"));
        Set<String> withDefault = new LinkedHashSet<>(matches(startSlice, "^export (XM_[A-Z_]+)=\"\\$\\{"));
        Set<String> generated = new LinkedHashSet<>(exported);
        generated.removeAll(withDefault);
        assertThat(generated).containsExactly("XM_ADMIN_TOKEN", "XM_GM_ADMIN_SECRET", "XM_ASSET_OP_SECRET_GUILD", "XM_BATTLE_TOKEN_SECRET");
        Map<String, String> files = Map.of("XM_ADMIN_TOKEN", "run/xm-admin-token", "XM_GM_ADMIN_SECRET", "run/xm-gm-admin-secret",
                "XM_ASSET_OP_SECRET_GUILD", "run/xm-asset-op-secret-guild", "XM_BATTLE_TOKEN_SECRET", "run/xm-battle-token-secret");
        for (String name : generated) {
            String file = files.get(name);
            assertThat(startSlice).as("start-slice.sh 把 %s 写进 %s", name, file).contains("printf \"%s\" \"$" + name + "\" > " + file);
            assertThat(crashWindow).contains("load_secret " + name + " " + file + " || exit 2");
        }
    }

    @Test
    void 必需的环境变量与start_slice相同() {
        List<String> required = matches(startSlice, "^: \"\\$\\{(XM_[A-Z_]+):\\?");
        assertThat(required).containsExactly("XM_MYSQL_PASSWORD", "XM_GATE_TOKEN_SECRET", "XM_LOGIN_DEV_PASSWORD", "XM_NODE_LINK_SECRET",
                "XM_DUBBO_SECRET");
        assertThat(crashWindow).contains("for required in " + String.join(" ", required) + "; do");
    }

    @Test
    void 断点信号_缺省状态文件_robot的命令行与robot这边对得上() throws Exception {
        // 断点 = 状态文件里有整行 stage=armed（arm 看完结局后改成 observed）
        State armed = new State(Variant.BATTLE_AFTER_STORE, Stage.ARMED, "robot_java_bct1_a", 7, 8, 0, 30, 1, 1, 0, "");
        assertThat("\n" + armed.encode()).contains("\nstage=armed\n");
        assertThat("\n" + armed.observed(5, "rescued").encode()).contains("\nstage=observed\n");
        assertThat(crashWindow).contains("*$'\\nstage=armed\\n'*").contains("*$'\\nstage=observed\\n'*");
        // 脚本报告里读的两个键
        assertThat(armed.encode()).contains("\nbattle_id=8\n").contains("\naccount=robot_java_bct1_a\n");
        assertThat(crashWindow).contains("$(state_field battle_id)").contains("$(state_field account)");

        Path defaultState = CrashWindowOptions.parse(List.of("battle-settle"), Map.of()).stateFile();
        assertThat(crashWindow).contains("STATE=\"" + defaultState.toString().replace('\\', '/') + "\"");
        assertThat(crashWindow).contains("-jar \"$ROBOT_JAR\" battle-settle --crash-window \"$VARIANT\" --crash-phase \"$phase\" --crash-state \"$STATE\"");
        // 两次调用都是「先把命令行放进 ROBOT_CMD、再直接执行数组」：arm 放后台，verify 在前台
        assertThat(crashWindow).contains("\nrobot_cmd arm\n\"${ROBOT_CMD[@]}\" > \"$ARM_LOG\" 2>&1 &\nROBOT_PID=$!\n")
                .contains("\nrobot_cmd verify\n\"${ROBOT_CMD[@]}\" > \"$VERIFY_LOG\" 2>&1 || verify_status=$?\n");

        // 脚本认的变体 = robot 认的变体（去掉 none）
        List<String> wires = new ArrayList<>();
        for (Variant v : Variant.values()) {
            if (v != Variant.NONE) {
                wires.add(v.wire());
            }
        }
        assertThat(crashWindow).contains("    " + String.join("|", wires) + ")\n");
    }

    @Test
    void 脚本只对PID文件里的目标进程发kill_9_不按名字杀_不动数据() {
        List<String> kills = matches(crashWindow, "^\\s*(kill -9 [^\\n]*)$");
        assertThat(kills).as("只有断点处的两处 kill -9，对象都是预先从 run/pids/ 读出的目标进程号")
                .containsOnly("kill -9 \"$target_pid\" 2>/dev/null || true").hasSize(2);
        assertThat(crashWindow).contains("TARGET_PID=$(cat \"run/pids/$TARGET.pid\")").contains("kill_at_breakpoint \"$ROBOT_PID\" \"$TARGET_PID\"");
        assertThat(crashWindow).doesNotContain("pkill").doesNotContain("killall").doesNotContain("//IM").doesNotContain("rm -rf")
                .doesNotContain("FLUSH").doesNotContain("DROP ").doesNotContain("redis-cli").doesNotContain("mysql ");
        // 秘密只进环境，不打印
        assertThat(matches(crashWindow, "^\\s*echo [^\\n]*\\$\\{?XM_(ADMIN_TOKEN|GM_ADMIN_SECRET|ASSET_OP_SECRET_GUILD|BATTLE_TOKEN_SECRET|DUBBO_SECRET"
                + "|NODE_LINK_SECRET|GATE_TOKEN_SECRET|MYSQL_PASSWORD|LOGIN_DEV_PASSWORD)")).isEmpty();
    }

    @Test
    void 前置检查都在动手之前_dry_run在前置之后_杀进程之前() {
        int preflightExit = crashWindow.indexOf("if (( problems != 0 )); then\n  exit 2\nfi");
        int dryRunExit = crashWindow.indexOf("echo \"--dry-run：前置满足。没有跑 robot，没有杀进程。\"\n  exit 0");
        int firstRobot = crashWindow.indexOf("\nrobot_cmd arm\n");
        int firstKillCall = crashWindow.indexOf("kill_at_breakpoint \"$ROBOT_PID\"");
        int restart = crashWindow.indexOf("\n  restart_scene_node\n");

        assertThat(preflightExit).isPositive();
        assertThat(dryRunExit).isGreaterThan(preflightExit);
        assertThat(firstRobot).isGreaterThan(dryRunExit);
        assertThat(firstKillCall).isGreaterThan(firstRobot);
        assertThat(restart).isGreaterThan(firstKillCall);
    }

    // ------------------------------------------------------------------ 旧的 robot 包（评审 R-1）

    /** 脚本对 robot 帮助文本做的两道判断（照脚本的写法：子串匹配），返回它会报的问题；空 = 放行。 */
    private static List<String> helpProblems(String robotHelp, int scriptRevision) {
        List<String> problems = new ArrayList<>();
        if (!robotHelp.contains("--crash-window")) {
            problems.add("不认 --crash-window");
        } else if (!robotHelp.contains("[crash-window-rev=" + scriptRevision + "]")) {
            problems.add("判定版本不符");
        }
        return problems;
    }

    @Test
    void 旧的robot包_认crash_window但判定过期的_按帮助里的判定版本拒绝() {
        // 脚本里的版本号 = robot 的判定版本（改判定要两边一起动；只改一边这里就失败）
        List<String> revisions = matches(crashWindow, "^ROBOT_CRASH_REVISION=(\\d+)$");
        assertThat(revisions).as("脚本里恰好一处 ROBOT_CRASH_REVISION=N").containsExactly(Integer.toString(BattleCrashChecks.STATE_VERSION));
        int scriptRevision = Integer.parseInt(revisions.get(0));

        // 脚本的两道判断就是下面这两个子串匹配（helpProblems 照它写）；判定版本那一道排在「认不认选项」之后
        String optionCheck = "if [[ \"$robot_help\" != *\"--crash-window\"* ]]; then";
        String revisionCheck = "elif [[ \"$robot_help\" != *\"[crash-window-rev=$ROBOT_CRASH_REVISION]\"* ]]; then";
        assertThat(crashWindow).contains("robot_help=$(java ${XM_ROBOT_JAVA_OPTS:-} -jar \"$ROBOT_JAR\" --help 2>/dev/null || true)\n  " + optionCheck);
        assertThat(crashWindow.indexOf(revisionCheck)).isGreaterThan(crashWindow.indexOf(optionCheck));

        // robot 这边：帮助里有这个标记，纯 ASCII、带定界符（标准输出编码不定时中文是乱码，标记不能受影响）
        String marker = BattleCrashChecks.revisionMarker();
        String help = RobotOptions.usage();
        assertThat(marker).isEqualTo("[crash-window-rev=" + BattleCrashChecks.STATE_VERSION + "]").matches("\\p{ASCII}+");
        assertThat(BattleCrashScenario.revisionMarker()).isEqualTo(marker);
        assertThat(help).containsOnlyOnce(marker);
        assertThat(helpProblems(help, scriptRevision)).as("本版 robot 的帮助过得了脚本的两道判断").isEmpty();

        // 旧包一：根本不认故障变体（帮助里没有这三个选项）
        assertThat(helpProblems("用法：java -jar xm-robot.jar <smoke|movement> [选项]\n  --gateway <值>\n", scriptRevision)).containsExactly("不认 --crash-window");
        // 旧包二（2026-10-06 的实例）：帮助里有 --crash-window，但没有判定版本这一行——原来的检查只看前者，会放行
        String staleHelp = help.lines().filter(line -> !line.contains(marker)).reduce("", (a, b) -> a + b + "\n");
        assertThat(staleHelp).contains("--crash-window").doesNotContain("crash-window-rev");
        assertThat(helpProblems(staleHelp, scriptRevision)).containsExactly("判定版本不符");
        // 判定又改过一版（robot 新、脚本旧，或反过来）：两头都拒绝；2 不会被 21 蒙混
        assertThat(helpProblems(help, scriptRevision + 1)).containsExactly("判定版本不符");
        assertThat(helpProblems(help.replace(marker, "[crash-window-rev=" + scriptRevision + "1]"), scriptRevision)).containsExactly("判定版本不符");
    }

    @Test
    void 旧的robot包_jar比源码旧的_查不了新旧的_都在前置里拒绝() {
        // 改了源码 / pom 没重新打包：jar 比它们旧。find 自己失败（if ! …）按「查不了」拒绝，不当成「不旧」放行
        String staleCheck = "  if ! robot_newer=$(find xm-robot/src/main xm-robot/pom.xml -type f -newer \"$ROBOT_JAR\" 2>/dev/null); then\n";
        int stale = crashWindow.indexOf(staleCheck);
        int preflightExit = crashWindow.indexOf("if (( problems != 0 )); then\n  exit 2\nfi");
        assertThat(stale).as("jar 新旧检查").isPositive().isLessThan(preflightExit);
        String block = crashWindow.substring(stale, crashWindow.indexOf("  unset robot_newer\n", stale));
        assertThat(block).as("查不了、jar 旧两种情形各置一次 problems").containsSubsequence("find 失败", "problems=1", "elif [[ -n \"$robot_newer\" ]]; then",
                "比源码旧", "problems=1");
        assertThat(matches(block, "^\\s*(problems=1)$")).hasSize(2);
        // 判定版本那一道同样只置 problems、不提前退出：所有问题一次报完，然后在同一处 exit 2
        int revisionCheck = crashWindow.indexOf("elif [[ \"$robot_help\" != *\"[crash-window-rev=$ROBOT_CRASH_REVISION]\"* ]]; then");
        assertThat(revisionCheck).isPositive().isLessThan(stale);
        assertThat(crashWindow.substring(revisionCheck, stale)).contains("problems=1").doesNotContain("exit ");
    }

    // ------------------------------------------------------------------ robot 的进程号（评审 R-3）

    @Test
    void 放到后台跑的都是进程本身_不是包着函数的子shell_ROBOT_PID才杀得到JVM() {
        // 「函数 &」的 $! 是子 shell，java 是它的子进程：kill $! 只杀掉子 shell，JVM 被过继后继续跑、之后还会写状态文件。
        // bash 5.3 在函数体最后一条是简单命令（且前面还有别的命令）时会省掉这次 fork，但那是随版本与写法变的优化；直接把命令放后台才在哪一版都对
        List<String> background = matches(crashWindow, "^\\s*(\\S[^\\n]*\\S)\\s+&$");
        assertThat(background).as("脚本里放后台的命令：launch 起服务端进程、arm 阶段的 robot").containsExactly(
                "java -jar \"$jar\" \"$@\" > \"run/logs/$instance.log\" 2>&1", "\"${ROBOT_CMD[@]}\" > \"$ARM_LOG\" 2>&1");
        assertThat(function(crashWindow, "run_robot")).as("不再有把 java 包起来的 run_robot()").isNull();
        // ROBOT_CMD 的第一个词是 java：数组直接执行时 $! 就是 JVM
        assertThat(function(crashWindow, "robot_cmd")).contains("\n  ROBOT_CMD=(java ${XM_ROBOT_JAVA_OPTS:-} -jar \"$ROBOT_JAR\" battle-settle ");
        assertThat(matches(crashWindow, "^\\s*(ROBOT_CMD=\\([^\\n]*)$")).hasSize(1);
    }

    @Test
    void 等断点超时或中途退出时_先结束robot并等它退出_再删掉没人按它杀过进程的断点() {
        String stop = function(crashWindow, "stop_robot");
        assertThat(stop).as("stop_robot：发信号 → 轮询到进程号不在 → wait 收尸；没退出就返回 1")
                .containsSubsequence("ROBOT_PID=\"\"\n", "kill \"$pid\" 2>/dev/null || true\n", "while kill -0 \"$pid\" 2>/dev/null; do\n", "return 1\n",
                        "wait \"$pid\" 2>/dev/null || true\n");
        assertThat(function(crashWindow, "discard_unused_breakpoint")).as("只在没有按断点杀过进程时删")
                .isEqualTo("  if (( KILLED == 0 )); then\n    rm -f \"$STATE\" \"$STATE.tmp\"\n  fi\n");

        // 超时分支：robot 确实退出了才删（没退出的话它之后还会写，删了也白删）
        int timeout = crashWindow.indexOf("  if (( breakpoint == 2 )); then\n");
        assertThat(timeout).isPositive();
        String timeoutBranch = crashWindow.substring(timeout, crashWindow.indexOf("\n  else\n", timeout));
        assertThat(timeoutBranch).containsSubsequence("没有杀任何进程", "    if stop_robot; then\n      discard_unused_breakpoint\n    fi");
        // 中途退出（被打断、confirm_dead 失败经 set -e 退出……）：on_exit 同样先停 robot
        assertThat(function(crashWindow, "on_exit")).startsWith("  if [[ -n \"$ROBOT_PID\" ]]; then\n")
                .contains("    if stop_robot; then\n      discard_unused_breakpoint\n    fi\n");
        assertThat(matches(crashWindow, "^[^#\\n]*(kill \"\\$ROBOT_PID\")")).as("注释之外没有别处直接对 robot 的进程号发信号（都经 stop_robot）").isEmpty();

        // KILLED 在 kill -9 之前置位：两处 kill -9 的上一行都是 KILLED=1（脚本恰好在两者之间被打断时，on_exit 不会把真断点删掉）
        String killer = function(crashWindow, "kill_at_breakpoint");
        assertThat(matches(killer, "^\\s*KILLED=1\\n\\s*(kill -9 \"\\$target_pid\" 2>/dev/null \\|\\| true)$")).hasSize(2);
        assertThat(matches(killer, "^\\s*(kill -9 [^\\n]*)$")).hasSize(2);
    }

    // ------------------------------------------------------------------ 双 scene 切片的指标地址（评审 R-2 的脚本一侧）

    @Test
    void 第二个scene节点在跑时_两个管理端口都传给robot_排在调用者的参数之前() {
        // 两个节点的管理端口取自与 start-slice.sh 相同的节点表（第 4 列）
        assertThat(crashWindow).contains("read -r SCENE_INSTANCE SCENE_LINK_PORT SCENE_RPC_PORT SCENE_MGMT_PORT <<<\"${SCENE_NODES[0]}\"\n"
                + "read -r SECOND_SCENE_INSTANCE _ _ SECOND_SCENE_MGMT_PORT <<<\"${SCENE_NODES[1]}\"\n");
        Matcher nodes = Pattern.compile("(?ms)^SCENE_NODES=\\(\\n\\s*\"(\\S+)\\s+\\d+\\s+\\d+\\s+(\\d+)\"\\n\\s*\"(\\S+)\\s+\\d+\\s+\\d+\\s+(\\d+)\"\\n\\)\\n")
                .matcher(crashWindow);
        assertThat(nodes.find()).isTrue();
        String both = "http://127.0.0.1:" + nodes.group(2) + ",http://127.0.0.1:" + nodes.group(4);
        assertThat(both).isEqualTo("http://127.0.0.1:18104,http://127.0.0.1:18114");
        assertThat(BattleSettleChecks.metricsUrls(both)).as("robot 把它拆成两个地址，按节点之和判定").hasSize(2);

        // 只在第二个节点活着、调用者又没有用环境变量指定时才给
        assertThat(crashWindow).contains("SCENE_METRICS_ARGS=()\n"
                + "if [[ -z \"${XM_ROBOT_SCENE_METRICS_URL:-}\" ]] && alive \"$SECOND_SCENE_INSTANCE\"; then\n"
                + "  SCENE_METRICS_ARGS=(--scene-metrics-url \"http://127.0.0.1:$SCENE_MGMT_PORT,http://127.0.0.1:$SECOND_SCENE_MGMT_PORT\")\n"
                + "fi\n");
        assertThat(RobotOptions.usage()).as("选项名与环境变量名和 robot 的一致").contains("--scene-metrics-url <值>").contains("XM_ROBOT_SCENE_METRICS_URL");
        // 排在「--」之后的参数之前：robot 对同一个选项取最后一次（RobotOptionsTest 钉住），调用者显式给的仍然算数
        String cmd = function(crashWindow, "robot_cmd");
        assertThat(cmd).contains("${SCENE_METRICS_ARGS[@]+\"${SCENE_METRICS_ARGS[@]}\"} ${ROBOT_ARGS[@]+\"${ROBOT_ARGS[@]}\"})\n");
        assertThat(cmd.indexOf("SCENE_METRICS_ARGS")).isLessThan(cmd.indexOf("ROBOT_ARGS"));
        // 赋值在前置检查通过之后、第一次跑 robot 之前
        int assigned = crashWindow.indexOf("\nSCENE_METRICS_ARGS=()\n");
        assertThat(assigned).isGreaterThan(crashWindow.indexOf("if (( problems != 0 )); then\n  exit 2\nfi")).isLessThan(crashWindow.indexOf("\nrobot_cmd arm\n"));
    }
}
