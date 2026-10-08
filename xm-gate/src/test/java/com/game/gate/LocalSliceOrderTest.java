package com.game.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.DubboGroups;
import com.game.gate.session.MessageRoutes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/**
 * 本机切片脚本（{@code tools/local/start-slice.sh} / {@code stop-slice.sh}）里与 gate 的后端有关的次序（批次 6.4 接 xm-match 时补的钉子）。
 *
 * <p>硬约束来自 {@link BackendReconnectTest}：gate 对后端的静态直连引用在建引用时没连上，下一次重连排在 60 s 之后——所以切片里<b>每个后端都必须
 * 先于 gate 启动</b>，否则脚本报「全部就绪」之后的头一分钟里那个后端的消息一律信封 1003。以前的后端都是这样排的，没有人钉过；xm-match
 * 按规格草案本该排在最后（xm-battle 之后），那就晚于 gate 了，所以这里把这条约束写成测试。xm-team 同理（整队开战调 xm-match）。
 *
 * <p>只做文本层面的核对，不执行脚本（脚本要整套依赖与全部 jar 才跑得起来）。脚本与故障变体脚本之间的一致性由 xm-robot 的
 * {@code SliceScriptsTest} 钉。找不到 {@code tools/local}（单独拿 xm-gate 模块构建）时跳过。
 */
class LocalSliceOrderTest {

    private static String startSlice;
    private static String stopSlice;
    /** start-slice.sh 的 SERVICES 清单：每项的模块名，按启动次序。 */
    private static List<String> startOrder;
    /** SERVICES 清单里每项的就绪端口串（没有列端口的为空串），与 {@link #startOrder} 同下标。 */
    private static List<String> startPorts;

    @BeforeAll
    static void 读两份脚本() throws IOException {
        Path dir = Files.isDirectory(Path.of("../tools/local")) ? Path.of("../tools/local") : Path.of("tools/local");
        Assumptions.assumeTrue(Files.isRegularFile(dir.resolve("start-slice.sh")), "找不到 tools/local（单独构建 xm-gate）");
        startSlice = Files.readString(dir.resolve("start-slice.sh"), StandardCharsets.UTF_8);
        stopSlice = Files.readString(dir.resolve("stop-slice.sh"), StandardCharsets.UTF_8);

        Matcher block = Pattern.compile("(?ms)^SERVICES=\\(\\n(.*?)^\\)\\n").matcher(startSlice);
        assertThat(block.find()).as("start-slice.sh 里有 SERVICES=( … )").isTrue();
        startOrder = new ArrayList<>();
        startPorts = new ArrayList<>();
        // 每项形如 "模块名 端口…"（引号里），行尾可以有注释；纯注释行不是清单项
        Matcher entry = Pattern.compile("(?m)^\\s*\"(xm-[a-z-]+)((?: \\d+)*)\"").matcher(block.group(1));
        while (entry.find()) {
            startOrder.add(entry.group(1));
            startPorts.add(entry.group(2).trim());
        }
    }

    @Test
    void 清单解析得对_十三个进程_gate与gateway在后段_battle最后() {
        assertThat(startOrder).containsExactly("xm-scene-manager", "xm-login", "xm-friend", "xm-chat", "xm-match", "xm-team", "xm-guild",
                "xm-trade", "xm-data", "xm-scene", "xm-gate", "xm-gateway", "xm-battle");
        assertThat(startOrder).doesNotHaveDuplicates();
    }

    @Test
    void gate的每个后端都先于gate启动_就绪端口与gate直连的端口是同一个() {
        Properties gateYaml = yaml("application.yaml");
        int gate = startOrder.indexOf("xm-gate");
        assertThat(gate).as("xm-gate 在启动清单里").isNotNegative();
        // 路由表指向的每个域（login 与六个后端）：进程模块名 = xm-<域>
        for (String group : MessageRoutes.SERVICE_BACKENDS.values()) {
            String module = "xm-" + group;
            int at = startOrder.indexOf(module);
            assertThat(at).as("%s 在启动清单里", module).isNotNegative();
            assertThat(at).as("%s 必须先于 xm-gate 启动：gate 的直连引用建引用时没连上，60 s 后才重连（BackendReconnectTest）", module)
                    .isLessThan(gate);

            String url = gateYaml.getProperty("xm.dubbo." + group + "-url");
            assertThat(url).as("gate 的 application.yaml 里 %s 的直连地址", group).startsWith("tri://127.0.0.1:");
            String port = url.substring("tri://127.0.0.1:".length());
            // 脚本等的就绪端口就是 gate 要连的那个 Dubbo 端口：等到它可连，gate 启动时的首次建连才连得上
            String waited = group.equals(DubboGroups.MATCH) ? matchRpcPort() : startPorts.get(at);
            assertThat(waited).as("start-slice.sh 等 %s 就绪的端口", module).isEqualTo(port);
        }
    }

    @Test
    void 匹配进程先于它的两个调用方启动_并且等到凑单与评分消费起来才算就绪() {
        // xm-team 的整队开战（MatchTeamService）与 xm-gate 的 MatchService 都静态直连 xm-match
        assertThat(startOrder.indexOf("xm-match")).isLessThan(startOrder.indexOf("xm-team")).isLessThan(startOrder.indexOf("xm-gate"));
        assertThat(matchRpcPort()).as("与 xm-match application.yaml 的 XM_MATCH_RPC_PORT 缺省一致").isEqualTo("20888");
        assertThat(startSlice).contains("\nMATCH_MGMT_PORT=18113\n");

        // 主循环对 xm-match 走专门的就绪判断，不是只等端口
        assertThat(startSlice).contains("  if [[ \"$name\" == \"xm-match\" ]]; then\n    launch xm-match xm-match\n    wait_match_ready\n");
        String ready = function(startSlice, "wait_match_ready");
        assertThat(ready).as("start-slice.sh 里有 wait_match_ready()").isNotNull();
        // 先等 Dubbo 端口，再等就绪日志；期间进程退出立即失败（凑单 / 评分消费起不来时进程在端口打开之后才退出）
        assertThat(ready).containsSubsequence(
                "wait_port \"$MATCH_RPC_PORT\" \"$name\"\n",
                "until grep -q \"match 已就绪\" \"run/logs/$name.log\" 2>/dev/null; do\n",
                "if ! kill -0 \"$(cat \"run/pids/$name.pid\")\" 2>/dev/null; then\n",
                "return 1\n",
                "if (( SECONDS > deadline )); then\n",
                "return 1\n");
        assertThat(ready).as("就绪判断不看 Kafka、不看 xm-battle（它排在后面）").doesNotContain("9092").doesNotContain("BATTLE_");
    }

    @Test
    void 对局结果topic的代次_缺省1_显式导出给battle与match两个进程() {
        assertThat(startSlice).contains("\nexport XM_BATTLE_RESULT_TOPIC_GENERATION=\"${XM_BATTLE_RESULT_TOPIC_GENERATION:-1}\"\n");
        // 导出发生在主循环之前：两个进程都继承得到
        assertThat(startSlice.indexOf("export XM_BATTLE_RESULT_TOPIC_GENERATION="))
                .isLessThan(startSlice.indexOf("for entry in \"${SERVICES[@]}\"; do"));
    }

    @Test
    void 停止次序_匹配最先_战斗其次_其余按启动的逆序_启动清单里的进程一个不漏() {
        Matcher loop = Pattern.compile("(?m)^for name in ([a-z0-9 -]+); do$").matcher(stopSlice);
        assertThat(loop.find()).as("stop-slice.sh 的停止清单").isTrue();
        List<String> stopOrder = List.of(loop.group(1).trim().split(" +"));

        // xm-match 是 xm-battle 与 xm-scene 的调用方：先停它，后面的进程停下时没有开到一半的局
        assertThat(stopOrder).startsWith("xm-match", "xm-battle");
        // 其余：启动清单去掉这两个之后的逆序；xm-scene 展开成 xm-scene-2（第二个节点，可能不存在）在前、xm-scene 在后
        List<String> rest = new ArrayList<>(startOrder);
        rest.remove("xm-match");
        rest.remove("xm-battle");
        List<String> expected = new ArrayList<>(List.of("xm-match", "xm-battle"));
        for (int i = rest.size() - 1; i >= 0; i--) {
            if (rest.get(i).equals("xm-scene")) {
                expected.add("xm-scene-2");
            }
            expected.add(rest.get(i));
        }
        assertThat(stopOrder).containsExactlyElementsOf(expected);
    }

    // ================================================================ 工具

    /** {@code MATCH_RPC_PORT=NNNN} 那一行的端口。 */
    private static String matchRpcPort() {
        Matcher m = Pattern.compile("(?m)^MATCH_RPC_PORT=(\\d+)$").matcher(startSlice);
        assertThat(m.find()).as("start-slice.sh 里有 MATCH_RPC_PORT=端口").isTrue();
        return m.group(1);
    }

    /** 顶格定义的 shell 函数体（{@code 名字() {} 到下一行顶格的 {@code }}）；没有这个函数为 null。 */
    private static String function(String script, String name) {
        Matcher m = Pattern.compile("(?ms)^" + Pattern.quote(name) + "\\(\\) \\{\\n(.*?)^}\\n").matcher(script);
        return m.find() ? m.group(1) : null;
    }

    private static Properties yaml(String name) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(name));
        Properties properties = factory.getObject();
        assertThat(properties).as(name).isNotNull();
        return properties;
    }
}
