package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.robot.CrashWindowOptions;
import com.game.robot.CrashWindowOptions.Variant;
import com.game.robot.RobotOptions;
import com.game.robot.scenario.BattleCrashChecks.Stage;
import com.game.robot.scenario.BattleCrashChecks.State;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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
    private static String stopSlice;

    @BeforeAll
    static void 读两份脚本() throws IOException {
        Path dir = Files.isDirectory(Path.of("../tools/local")) ? Path.of("../tools/local") : Path.of("tools/local");
        Assumptions.assumeTrue(Files.isRegularFile(dir.resolve("start-slice.sh")), "找不到 tools/local（单独构建 xm-robot）");
        startSlice = Files.readString(dir.resolve("start-slice.sh"), StandardCharsets.UTF_8);
        crashWindow = Files.readString(dir.resolve("battle-crash-window.sh"), StandardCharsets.UTF_8);
        stopSlice = Files.readString(dir.resolve("stop-slice.sh"), StandardCharsets.UTF_8);
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

    // ------------------------------------------------------------------ 双 zone 切片（批次 6.5，XM_ZONES=2）

    /** {@code 名字=( … )} 数组块的内容（到下一行顶格的右括号）；没有为 null。 */
    private static String block(String script, String name) {
        Matcher m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "=\\(\\n(.*?)^\\)\\n").matcher(script);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void 区2的实例表_端口与别的实例都不相同_不并进区1的节点表与启动清单() throws IOException {
        assertThat(startSlice).contains("\nZONE2_ID=2\nZONE2_SCENE_NODE=\"xm-scene-z2 21010 21110 18115\"\nZONE2_GATE=\"xm-gate-z2 11010 18123\"\n");

        // 区 1 的节点表仍然只有两行（故障变体脚本按「第 1 / 2 行 = 区 1 的两个节点」取用）；启动清单仍是十三项，没有区 2 的实例
        String nodes = block(startSlice, "SCENE_NODES");
        String services = block(startSlice, "SERVICES");
        assertThat(nodes).isNotNull().doesNotContain("z2");
        assertThat(nodes.lines().count()).isEqualTo(2);
        assertThat(services).isNotNull();
        assertThat(matches(services, "^\\s*\"(xm-[a-z0-9-]+)")).hasSize(13).doesNotContain("xm-scene-z2", "xm-gate-z2");

        // 同机多实例：区 2 新占的五个端口互不相同，也不与别的实例相撞。别的实例的端口有两个来源：
        // ① 脚本里写着的（区 1 的节点表、清单里的就绪端口、battle / match 的端口）；
        // ② 只写在各模块 application*.yaml 里、脚本没提的缺省端口（各服务的管理端口 181xx、Dubbo 端口 208xx……）。
        // ② 才是容易撞上的那一种：管理端口是顺着编号分配的，下一个新模块顺手取的号可能正好是区 2 实例占着的；
        // 那样的冲突只在 XM_ZONES=2 时才暴露，单 zone 切片与全量构建都是绿的
        Set<String> taken = new LinkedHashSet<>(matches(nodes, "\\b(\\d{5})\\b"));
        taken.addAll(matches(services, "^\\s*\"xm-[a-z-]+ (\\d+)\""));
        taken.addAll(matches(startSlice, "^(?:BATTLE|MATCH)_[A-Z]+_PORT=(\\d+)$"));
        assertThat(taken).contains("21000", "21101", "18114", "11000", "18081", "18106", "21200", "18113");
        Map<String, List<String>> byFile = modulePorts();
        // 扫描真的扫到了东西（路径或写法变了不能悄悄变成「什么都不比」）：每个起进程的模块都有一份，脚本没提的那些端口都在
        assertThat(byFile.keySet().stream().map(file -> file.substring(0, file.indexOf('/'))).distinct())
                .as("有 application.yaml 的模块").contains("xm-login", "xm-scene-manager", "xm-gate", "xm-scene", "xm-gateway", "xm-data", "xm-friend",
                        "xm-chat", "xm-team", "xm-guild", "xm-trade", "xm-battle", "xm-match");
        Set<String> configured = new LinkedHashSet<>();
        byFile.values().forEach(configured::addAll);
        // 几个锚点：脚本没提、只在 yaml 里的管理端口（字面量），以及 ${环境变量:缺省} 与带前缀的键两种写法
        assertThat(configured).as("各模块 application*.yaml 里的缺省端口").contains("18101", "18103", "18105", "18107", "18111", "20881", "12000", "21100")
                .doesNotContain("0");

        // 区 2 的五个端口取自脚本里的实例表（不在这里另抄一份）：scene 的链路 / 资产通道 / 管理，gate 的客户端 / 管理
        Matcher scene = Pattern.compile("(?m)^ZONE2_SCENE_NODE=\"xm-scene-z2 (\\d+) (\\d+) (\\d+)\"$").matcher(startSlice);
        Matcher gate = Pattern.compile("(?m)^ZONE2_GATE=\"xm-gate-z2 (\\d+) (\\d+)\"$").matcher(startSlice);
        assertThat(scene.find() && gate.find()).as("ZONE2_SCENE_NODE / ZONE2_GATE 两行的写法").isTrue();
        List<String> zone2 = List.of(scene.group(1), scene.group(2), scene.group(3), gate.group(1), gate.group(2));
        assertThat(zone2).doesNotHaveDuplicates().doesNotContainAnyElementsOf(taken);
        for (Map.Entry<String, List<String>> entry : byFile.entrySet()) {
            assertThat(entry.getValue()).as("%s 里的缺省端口不与区 2 的实例（%s）相撞；撞了就给新端口另取一个号，或改 start-slice.sh 的 ZONE2_* 实例表",
                    entry.getKey(), zone2).doesNotContainAnyElementsOf(zone2);
        }
        // 区服目录的两个 HTTP 口就是清单里等 gateway / data 就绪的那两个端口
        assertThat(startSlice).contains("\nGATEWAY_HTTP_PORT=18081\nDATA_MGMT_PORT=18106\n");
        assertThat(services).contains("\"xm-gateway 18081\"").contains("\"xm-data 18106\"");
    }

    /**
     * yaml 文本里配置的端口：键是 {@code port} 或以 {@code -port} 结尾（{@code client-port}、{@code link-port}、{@code asset-rpc-port}……）的行，
     * 值是字面量或 {@code ${环境变量:缺省}} 的缺省值；0（「不配 = 跟随另一个端口」）与注释行不算。
     */
    static List<String> yamlPorts(String yaml) {
        List<String> ports = new ArrayList<>();
        Matcher m = Pattern.compile("(?m)^\\s*(?:[a-z][a-z-]*-)?port:\\s*(?:\\$\\{[A-Z_]+:)?(\\d+)}?\\s*(?:#.*)?$").matcher(yaml);
        while (m.find()) {
            if (!m.group(1).equals("0")) {
                ports.add(m.group(1));
            }
        }
        return ports;
    }

    /** 仓库里每个模块的 {@code src/main/resources/application*.yaml} → 其中配置的端口（键是「模块/文件名」）；没有端口的文件不列。 */
    private static Map<String, List<String>> modulePorts() throws IOException {
        Path root = Files.isDirectory(Path.of("../tools/local")) ? Path.of("..") : Path.of(".");
        Map<String, List<String>> ports = new TreeMap<>();
        try (DirectoryStream<Path> modules = Files.newDirectoryStream(root, Files::isDirectory)) {
            for (Path module : modules) {
                Path resources = module.resolve("src/main/resources");
                if (!Files.isDirectory(resources)) {
                    continue;
                }
                try (DirectoryStream<Path> files = Files.newDirectoryStream(resources, "application*.{yaml,yml}")) {
                    for (Path file : files) {
                        List<String> found = yamlPorts(Files.readString(file, StandardCharsets.UTF_8));
                        if (!found.isEmpty()) {
                            ports.put(module.getFileName() + "/" + file.getFileName(), found);
                        }
                    }
                }
            }
        }
        return ports;
    }

    @Test
    void 读yaml端口的办法本身_字面量与环境变量的缺省值都认_0与注释行不算() {
        String yaml = """
                # 端口可用 SERVER_PORT 覆盖（同机多实例必须各不相同）
                server:
                  port: ${SERVER_PORT:18115}
                management:
                  server:
                    port: 18105
                xm:
                  gate:
                    client-port: 11000
                    advertise-port: ${XM_GATE_ADVERTISE_PORT:0}
                  scene:
                    link-port: 21000   # 行尾注释
                    asset-rpc-port: ${XM_SCENE_ASSET_RPC_PORT:21100}
                    # link-port: 29999
                    report: 12345
                    mode: report_only
                """;
        assertThat(yamlPorts(yaml)).containsExactly("18115", "18105", "11000", "21000", "21100");
        // 新模块顺着编号取了区 2 的 scene 管理端口：这正是用例要拦的那种冲突
        assertThat(yamlPorts("server:\n  port: 18115\n")).containsExactly("18115");
    }

    @Test
    void 区2的scene与gate_命令行只比区1多zone与端口_就绪判断相同() {
        // scene：与区 1 的那几行逐字相同，只多一个 --xm.zone-id（少一层 for 循环，缩进少两格）
        assertThat(function(startSlice, "start_zone2_scene")).isEqualTo("  local instance link rpc mgmt\n"
                + "  read -r instance link rpc mgmt <<<\"$ZONE2_SCENE_NODE\"\n"
                + "  launch \"$instance\" xm-scene --xm.zone-id=\"$ZONE2_ID\" --server.port=\"$mgmt\" --xm.scene.link-port=\"$link\" \\\n"
                + "      --xm.scene.asset-rpc-port=\"$rpc\" --xm.scene.scene-manager-url=\"$XM_SCENE_MANAGER_URL\"\n"
                + "  wait_port \"$link\" \"$instance\"\n"
                + "  wait_port \"$rpc\" \"$instance\"\n"
                + "  wait_world_channels \"$instance\" \"$mgmt\"\n"
                + "  echo \"  $instance 就绪（区 $ZONE2_ID：链路 $link、资产通道 $rpc、管理端口 $mgmt）\"\n");
        // gate：只换 zone 与两个端口，Dubbo 后端地址不给（用缺省 = 与区 1 的 gate 指向同一组服务，同一个 xm-match）
        String gate = function(startSlice, "start_zone2_gate");
        assertThat(gate).isEqualTo("  local instance client mgmt\n"
                + "  read -r instance client mgmt <<<\"$ZONE2_GATE\"\n"
                + "  launch \"$instance\" xm-gate --xm.zone-id=\"$ZONE2_ID\" --xm.gate.client-port=\"$client\" --server.port=\"$mgmt\"\n"
                + "  wait_port \"$client\" \"$instance\"\n"
                + "  echo \"  $instance 就绪（区 $ZONE2_ID：客户端 $client、管理端口 $mgmt）\"\n");
        assertThat(gate).doesNotContain("xm.dubbo");
    }

    @Test
    void XM_ZONES缺省1_只认1和2_区2的实例只在等于2时起_紧跟区1的同类() {
        String validate = "\nXM_ZONES=\"${XM_ZONES:-1}\"\nif [[ \"$XM_ZONES\" != \"1\" && \"$XM_ZONES\" != \"2\" ]]; then\n"
                + "  echo \"XM_ZONES 只能是 1 或 2：$XM_ZONES\" >&2\n  exit 1\nfi\n";
        int loop = startSlice.indexOf("for entry in \"${SERVICES[@]}\"; do");
        assertThat(startSlice.indexOf(validate)).as("取值检查在起任何进程之前").isPositive().isLessThan(loop);
        // 不导出：它只决定脚本起哪些进程，不是进程的配置（导出的话故障变体脚本就得带同一行）
        assertThat(matches(startSlice, "^(export XM_ZONES)")).isEmpty();

        // 区 1 的场景节点（XM_SCENE_NODES 个）之后起区 2 的；xm-gate 就绪之后起区 2 的 gate——全部 Dubbo 后端此时都已起来
        assertThat(startSlice).contains("    start_scene_nodes\n    if [[ \"$XM_ZONES\" == \"2\" ]]; then\n      start_zone2_scene\n    fi\n    continue\n");
        assertThat(startSlice).contains("  echo \"  $name 就绪（端口 $ports）\"\n  if [[ \"$name\" == \"xm-gate\" && \"$XM_ZONES\" == \"2\" ]]; then\n"
                + "    start_zone2_gate\n  fi\ndone\n");
        assertThat(matches(startSlice, "^\\s*(start_zone2_(?:scene|gate))$")).as("各只调一次").containsExactly("start_zone2_scene", "start_zone2_gate");
        // xm-gateway 的命令行两种形态下都不带区服播种参数（中文名经命令行会被代码页弄坏；区 2 由运维接口建）
        assertThat(matches(startSlice, "^[^#\\n]*(seed-zones)")).isEmpty();
        assertThat(matches(startSlice, "^(\\s*launch \"\\$name\" \"\\$name\")$")).hasSize(1);
    }

    @Test
    void 区2在区服目录里的状态跟随XM_ZONES_经运维接口改_等区服列表反映了才报全部就绪() {
        String sync = function(startSlice, "sync_zone2_status");
        assertThat(sync).as("start-slice.sh 里有 sync_zone2_status()").isNotNull();
        // =2：open，404 则建区；=1：maintenance，404 则什么都不做；其余非 200 一律失败；再等区服列表（=2 时还要带负载档：探测看到了区 2 的 gate）
        assertThat(sync).containsSubsequence(
                "if [[ \"$XM_ZONES\" == \"2\" ]]; then\n",
                "want=OPEN\n",
                "action=\"/admin/zones/$ZONE2_ID/open\"\n",
                "if [[ \"$code\" == \"404\" ]]; then\n",
                "action=\"/admin/zones\"\n",
                "code=$(zone_admin_post \"$action\" \"$ZONE2_CREATE_BODY\")\n",
                "else\n",
                "want=MAINTENANCE\n",
                "action=\"/admin/zones/$ZONE2_ID/maintenance\"\n",
                "code=$(zone_admin_post \"$action\" \"$ZONE2_MAINTENANCE_BODY\")\n",
                "if [[ \"$code\" == \"404\" ]]; then\n      return 0\n",
                "if [[ \"$code\" != \"200\" ]]; then\n",
                "return 1\n",
                "until entry=$(zone_list_entry \"$ZONE2_ID\") && [[ \"$entry\" == *\"\\\"status\\\":\\\"$want\\\"\"* ]] \\\n",
                "&& [[ \"$want\" != \"OPEN\" || \"$entry\" == *'\"load_level\":'* ]]; do\n",
                "if (( SECONDS > deadline )); then\n",
                "return 1\n");
        // 建区的请求体与 robot zones 场景经同一个接口发的是同一种形状（zone_id / name / manual_status / sort_order）
        assertThat(startSlice).contains("\nZONE2_CREATE_BODY=\"{\\\"zone_id\\\":$ZONE2_ID,\\\"name\\\":\\\"二区\\\",\\\"manual_status\\\":0,\\\"sort_order\\\":2}\"\n");
        assertThat(matches(startSlice, "^(ZONE2_MAINTENANCE_BODY='\\{\"maintenance_msg\":\"[^\"']+\"}')$")).hasSize(1);

        // 主循环之后、「全部就绪」之前调一次；失败（返回 1）时 set -e 让脚本以非 0 退出，不会报全部就绪
        int call = startSlice.indexOf("\ndone\n\nsync_zone2_status\n\necho \"全部就绪（");
        assertThat(call).isGreaterThan(startSlice.indexOf("for entry in \"${SERVICES[@]}\"; do"));
        assertThat(startSlice).contains("\nset -euo pipefail\n");
        assertThat(startSlice).endsWith("echo \"全部就绪（场景节点 $XM_SCENE_NODES 个、区 $XM_ZONES 个）。停止：tools/local/stop-slice.sh\"\n");

        // 运维调用：令牌与操作人两个头同 robot 的 AdminClient；令牌走标准输入上的 curl 配置，不上命令行、不打印
        String post = function(startSlice, "zone_admin_post");
        assertThat(post).contains("curl -sS -o /dev/null -w '%{http_code}' -K - 2>/dev/null <<EOF || true\n")
                .contains("url = \"http://127.0.0.1:$DATA_MGMT_PORT$path\"\n")
                .contains("header = \"X-Xm-Admin-Token: $token\"\n")
                .contains("header = \"X-Xm-Operator: start-slice\"\n")
                .contains("header = \"Content-Type: application/json\"\n")
                .contains("data-binary = \"$body\"\n")
                .doesNotContain(" -H ");
        assertThat(matches(startSlice, "^\\s*echo [^\\n]*\\$\\{?XM_(ADMIN_TOKEN|GM_ADMIN_SECRET|ASSET_OP_SECRET_GUILD|BATTLE_TOKEN_SECRET|DUBBO_SECRET"
                + "|NODE_LINK_SECRET|GATE_TOKEN_SECRET|MYSQL_PASSWORD|LOGIN_DEV_PASSWORD)")).isEmpty();
        // 区服列表按区号取那一项：GET /api/server-list，zone_id 后面必须是逗号或右括号（2 不会被 20 蒙混）
        assertThat(function(startSlice, "zone_list_entry")).contains("\"http://127.0.0.1:$GATEWAY_HTTP_PORT/api/server-list\"")
                .contains("grep -E \"\\\"zone_id\\\":$1[,}]\"");
    }

    @Test
    void 停止脚本_区2的实例紧挨着区1的同类先停_清单那一行不动() {
        // xm-gate 的 LocalSliceOrderTest 逐项钉着清单那一行（= 启动清单的逆序）；区 2 的两个实例在循环体里带上
        assertThat(stopSlice).contains("\nfor name in xm-match xm-battle xm-gateway xm-gate xm-scene-2 xm-scene xm-data xm-trade xm-guild xm-team xm-chat "
                + "xm-friend xm-login xm-scene-manager; do\n"
                + "  case \"$name\" in\n"
                + "    xm-gate) stop_one xm-gate-z2 ;;\n"
                + "    xm-scene-2) stop_one xm-scene-z2 ;;\n"
                + "  esac\n"
                + "  stop_one \"$name\"\n"
                + "done\n");
        // 实例名与 start-slice.sh 的区 2 实例表一致（PID 文件名 = 实例名）
        assertThat(startSlice).contains("ZONE2_SCENE_NODE=\"xm-scene-z2 ").contains("ZONE2_GATE=\"xm-gate-z2 ");
        // 没有 PID 文件的实例跳过（单 zone 切片上区 2 的两个就是这样）；只对 PID 文件里的进程发信号
        String stopOne = function(stopSlice, "stop_one");
        assertThat(stopOne).isNotNull().containsSubsequence("pidfile=\"run/pids/$name.pid\"\n", "[[ -f \"$pidfile\" ]] || return 0\n", "pid=$(cat \"$pidfile\")\n",
                "kill \"$pid\" 2>/dev/null\n", "kill -9 \"$pid\" 2>/dev/null; }\n", "rm -f \"$pidfile\"\n");
        assertThat(stopSlice).doesNotContain("pkill").doesNotContain("killall").doesNotContain("rm -rf");
    }

    @Test
    void 故障变体只在区1上做_robot要登录别的区的_在动手之前拒绝() {
        // 双 zone 切片上区 2 另有 xm-scene-z2：玩家在区 2 时杀区 1 的 xm-scene 得出的是假结论
        String guard = "ROBOT_ZONE=\"${XM_ROBOT_ZONE:-1}\"\n"
                + "for ((i = 0; i < ${#ROBOT_ARGS[@]}; i++)); do\n"
                + "  case \"${ROBOT_ARGS[$i]}\" in\n"
                + "    --zone=*) ROBOT_ZONE=\"${ROBOT_ARGS[$i]#--zone=}\" ;;\n"
                + "    --zone) ROBOT_ZONE=\"${ROBOT_ARGS[$((i + 1))]:-}\" ;;\n"
                + "  esac\n"
                + "done\n"
                + "if [[ \"$ROBOT_ZONE\" != \"1\" ]]; then\n";
        int at = crashWindow.indexOf(guard);
        int preflightExit = crashWindow.indexOf("if (( problems != 0 )); then\n  exit 2\nfi");
        assertThat(at).as("区号检查在前置检查里：只置 problems，与别的问题一起报").isPositive().isLessThan(preflightExit);
        assertThat(crashWindow.substring(at, preflightExit)).contains("  problems=1\nfi\n").doesNotContain("exit ");
        // 选项名、环境变量名、缺省值与 robot 的一致；robot 认 --zone N 与 --zone=N 两种写法，同一个选项取最后一次
        assertThat(RobotOptions.usage()).contains("--zone <值>").contains("XM_ROBOT_ZONE");
        // 重启用的命令行里没有 zone 参数：重启的是区 1 的节点（配置文件的缺省）
        assertThat(function(crashWindow, "restart_scene_node")).doesNotContain("zone-id");
        assertThat(crashWindow).doesNotContain("ZONE2_").doesNotContain("XM_ZONES=\"");
    }
}
