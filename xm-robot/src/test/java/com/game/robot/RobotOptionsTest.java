package com.game.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.robot.scenario.ExpectJump;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RobotOptionsTest {

    private static final Map<String, String> ENV = Map.of(RobotOptions.PASSWORD_ENV, "dev-secret");
    private static final long NOW = 1_760_000_000_000L;

    @Test
    void 缺省值() throws Exception {
        RobotOptions o = RobotOptions.parse(List.of("smoke"), ENV, NOW);
        assertThat(o.scenario()).isEqualTo(RobotOptions.Scenario.SMOKE);
        assertThat(o.gatewayUrl()).isEqualTo("http://127.0.0.1:18081");
        assertThat(o.zoneId()).isEqualTo(1);
        assertThat(o.accountPrefix()).isEqualTo("robot_java_");
        assertThat(o.count()).isEqualTo(3);
        assertThat(o.runTag()).isEqualTo(Long.toString(NOW, 36));
        assertThat(o.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(o.requestTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(o.enterSceneTimeout()).isEqualTo(Duration.ofSeconds(15));
        assertThat(o.observeTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(o.expectJump()).isEqualTo(ExpectJump.AUTO);
        assertThat(o.password()).isEqualTo("dev-secret");
    }

    @Test
    void 命令行优先于环境变量_两种写法都认() throws Exception {
        Map<String, String> env = Map.of(RobotOptions.PASSWORD_ENV, "p",
                "XM_ROBOT_GATEWAY", "http://env:1/", "XM_ROBOT_ZONE", "7", "XM_ROBOT_OBSERVE_TIMEOUT_MS", "900");
        RobotOptions o = RobotOptions.parse(List.of("--zone=2", "movement", "--gateway", "http://arg:2//",
                "--expect-jump", "CORRECT", "--run-tag", "t1"), env, NOW);
        assertThat(o.scenario()).isEqualTo(RobotOptions.Scenario.MOVEMENT);
        assertThat(o.gatewayUrl()).isEqualTo("http://arg:2");
        assertThat(o.zoneId()).isEqualTo(2);
        assertThat(o.observeTimeout()).isEqualTo(Duration.ofMillis(900));
        assertThat(o.expectJump()).isEqualTo(ExpectJump.CORRECT);
        assertThat(o.runTag()).isEqualTo("t1");
    }

    @Test
    void 口令只从环境变量来_缺失即参数错误() {
        assertThatThrownBy(() -> RobotOptions.parse(List.of("smoke"), Map.of(), NOW))
                .isInstanceOf(UsageException.class)
                .hasMessageContaining(RobotOptions.PASSWORD_ENV);
        assertThatThrownBy(() -> RobotOptions.parse(List.of("smoke", "--password", "x"), ENV, NOW))
                .isInstanceOf(UsageException.class)
                .hasMessageContaining("未知选项");
    }

    @Test
    void currency_缺省期望放行_deny可选_其他取值报错() throws Exception {
        RobotOptions o = RobotOptions.parse(List.of("currency", "--run-tag", "x1"), ENV, NOW);
        assertThat(o.scenario()).isEqualTo(RobotOptions.Scenario.CURRENCY);
        assertThat(o.expectGmAllowed()).isTrue();
        assertThat(RobotOptions.parse(List.of("currency", "--expect-gm", "deny"), ENV, NOW).expectGmAllowed()).isFalse();
        assertThatThrownBy(() -> RobotOptions.parse(List.of("currency", "--expect-gm", "maybe"), ENV, NOW))
                .hasMessageContaining("--expect-gm");
    }

    @Test
    void attribute_账号带at标签_同样认expect_gm() throws Exception {
        RobotOptions o = RobotOptions.parse(List.of("attribute", "--run-tag", "x1", "--expect-gm", "deny"), ENV, NOW);
        assertThat(o.scenario()).isEqualTo(RobotOptions.Scenario.ATTRIBUTE);
        assertThat(o.expectGmAllowed()).isFalse();
        assertThat(com.game.robot.scenario.AttributeScenario.accountName(o.accountPrefix(), o.runTag()))
                .isEqualTo("robot_java_atx1");
    }

    @Test
    void features_账号带feat标签() throws Exception {
        RobotOptions o = RobotOptions.parse(List.of("features", "--run-tag", "x1"), ENV, NOW);
        assertThat(o.scenario()).isEqualTo(RobotOptions.Scenario.FEATURES);
        assertThat(com.game.robot.scenario.FeaturesScenario.accountName(o.accountPrefix(), o.runTag()))
                .isEqualTo("robot_java_featx1");
        assertThat(RobotOptions.usage()).contains("features");
    }

    @Test
    void skill_两个账号带sk标签() throws Exception {
        RobotOptions o = RobotOptions.parse(List.of("skill", "--run-tag", "x1"), ENV, NOW);
        assertThat(o.scenario()).isEqualTo(RobotOptions.Scenario.SKILL);
        assertThat(com.game.robot.scenario.SkillScenario.accountName(o.accountPrefix(), o.runTag(), "a"))
                .isEqualTo("robot_java_skx1a");
        assertThat(RobotOptions.usage()).contains("skill");
    }

    @Test
    void pet_账号带pet标签() throws Exception {
        RobotOptions o = RobotOptions.parse(List.of("pet", "--run-tag", "x1"), ENV, NOW);
        assertThat(o.scenario()).isEqualTo(RobotOptions.Scenario.PET);
        assertThat(com.game.robot.scenario.PetScenario.accountName(o.accountPrefix(), o.runTag()))
                .isEqualTo("robot_java_petx1");
        assertThat(RobotOptions.usage()).contains("pet ");
    }

    @Test
    void toString_不带口令() throws Exception {
        assertThat(RobotOptions.parse(List.of("smoke"), ENV, NOW).toString()).doesNotContain("dev-secret").contains("***");
    }

    @Test
    void 非法参数() {
        assertThatThrownBy(() -> RobotOptions.parse(List.of(), ENV, NOW)).hasMessageContaining("缺少子命令");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("fly"), ENV, NOW)).hasMessageContaining("未知子命令");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("smoke", "--zone", "0"), ENV, NOW)).hasMessageContaining("--zone");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("smoke", "--count", "x"), ENV, NOW)).hasMessageContaining("不是整数");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("smoke", "--gateway", "127.0.0.1:18081"), ENV, NOW))
                .hasMessageContaining("http://");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("movement", "--run-tag", "Bad-Tag"), ENV, NOW))
                .hasMessageContaining("--run-tag");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("movement", "--expect-jump", "maybe"), ENV, NOW))
                .hasMessageContaining("--expect-jump");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("smoke", "--request-timeout-ms", "0"), ENV, NOW))
                .hasMessageContaining("--request-timeout-ms");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("smoke", "--zone"), ENV, NOW)).hasMessageContaining("缺少取值");
    }

    @Test
    void 账号超长时拒绝() {
        String prefix = "robot_" + "x".repeat(60);
        assertThatThrownBy(() -> RobotOptions.parse(List.of("smoke", "--prefix", prefix), ENV, NOW))
                .hasMessageContaining("超过 64");
    }

    @Test
    void battle_缺省管理端口18112_期望dev接口开放_不跑慢用例_账号带bt标签() throws Exception {
        RobotOptions o = RobotOptions.parse(List.of("battle", "--run-tag", "t1"), ENV, NOW);
        assertThat(o.scenario()).isEqualTo(RobotOptions.Scenario.BATTLE);
        assertThat(o.battleAdminUrl()).isEqualTo("http://127.0.0.1:18112");
        assertThat(o.expectDevAllowed()).isTrue();
        assertThat(o.slow()).isFalse();
        assertThat(com.game.robot.scenario.BattleScenario.accountName(o.accountPrefix(), o.runTag(), "a")).isEqualTo("robot_java_btt1_a");
        assertThat(com.game.robot.scenario.BattleEdgeScenario.accountName(o.accountPrefix(), o.runTag(), "a")).isEqualTo("robot_java_bet1_a");
    }

    @Test
    void battle_edge_子命令带连字符_slow可以只写开关也可以带取值() throws Exception {
        RobotOptions bare = RobotOptions.parse(List.of("--slow", "battle-edge"), ENV, NOW);
        assertThat(bare.scenario()).as("开关后面的子命令不被吃掉").isEqualTo(RobotOptions.Scenario.BATTLE_EDGE);
        assertThat(bare.slow()).isTrue();
        assertThat(RobotOptions.parse(List.of("battle-edge", "--slow"), ENV, NOW).slow()).isTrue();
        assertThat(RobotOptions.parse(List.of("battle-edge", "--slow", "false"), ENV, NOW).slow()).isFalse();
        assertThat(RobotOptions.parse(List.of("battle-edge", "--slow=true"), ENV, NOW).slow()).isTrue();
        assertThat(RobotOptions.parse(List.of("battle-edge"), Map.of(RobotOptions.PASSWORD_ENV, "p", "XM_ROBOT_SLOW", "true"), NOW).slow())
                .isTrue();
        assertThatThrownBy(() -> RobotOptions.parse(List.of("battle-edge", "--slow=yes"), ENV, NOW)).hasMessageContaining("--slow");
    }

    @Test
    void battle_expect_dev与管理端口地址() throws Exception {
        RobotOptions o = RobotOptions.parse(List.of("battle", "--expect-dev", "deny", "--battle-admin-url", "http://10.0.0.5:28112/"), ENV, NOW);
        assertThat(o.expectDevAllowed()).isFalse();
        assertThat(o.battleAdminUrl()).isEqualTo("http://10.0.0.5:28112");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("battle", "--expect-dev", "maybe"), ENV, NOW)).hasMessageContaining("--expect-dev");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("battle", "--battle-admin-url", "127.0.0.1:18112"), ENV, NOW))
                .hasMessageContaining("--battle-admin-url");
        assertThat(RobotOptions.usage()).contains("battle-edge", "XM_ROBOT_BATTLE_ADMIN_URL", "--slow");
        assertThat(o.toString()).contains("battleAdminUrl=http://10.0.0.5:28112").doesNotContain("dev-secret");
    }

    @Test
    void battle_settle_scene指标地址可以是逗号分隔的两个节点_原样带给场景_帮助写明双scene切片的用法() throws Exception {
        RobotOptions single = RobotOptions.parse(List.of("battle-settle", "--run-tag", "t1"), ENV, NOW);
        assertThat(single.scenario()).isEqualTo(RobotOptions.Scenario.BATTLE_SETTLE);
        assertThat(single.sceneMetricsUrl()).isEqualTo("http://127.0.0.1:18104");

        RobotOptions two = RobotOptions.parse(List.of("battle-settle", "--scene-metrics-url", "http://127.0.0.1:18104,http://127.0.0.1:18114/"),
                ENV, NOW);
        assertThat(two.sceneMetricsUrl()).as("拆分在场景里做（BattleSettleChecks.metricsUrls），这里只去掉结尾斜杠")
                .isEqualTo("http://127.0.0.1:18104,http://127.0.0.1:18114");
        assertThat(RobotOptions.usage()).contains("XM_SCENE_NODES=2", "逗号分隔", "in_battle", "3023");
    }

    @Test
    void 同一个选项给两次取最后一次_命令行盖过环境变量_编排脚本加的scene指标地址靠这两条让位给调用者() throws Exception {
        // tools/local/battle-crash-window.sh 在第二个 scene 节点活着时自己加一个 --scene-metrics-url，排在调用者（「--」之后）的参数之前
        RobotOptions twice = RobotOptions.parse(List.of("battle-settle", "--scene-metrics-url", "http://127.0.0.1:18104,http://127.0.0.1:18114",
                "--run-tag", "t1", "--scene-metrics-url=http://10.0.0.9:18104"), ENV, NOW);
        assertThat(twice.sceneMetricsUrl()).as("调用者后给的算数").isEqualTo("http://10.0.0.9:18104");

        // 调用者用环境变量指定时，命令行上再给就会盖掉它——所以脚本在 XM_ROBOT_SCENE_METRICS_URL 已设时不加自己的
        Map<String, String> env = Map.of(RobotOptions.PASSWORD_ENV, "p", "XM_ROBOT_SCENE_METRICS_URL", "http://env:2");
        assertThat(RobotOptions.parse(List.of("battle-settle"), env, NOW).sceneMetricsUrl()).isEqualTo("http://env:2");
        assertThat(RobotOptions.parse(List.of("battle-settle", "--scene-metrics-url", "http://arg:1"), env, NOW).sceneMetricsUrl())
                .isEqualTo("http://arg:1");
    }

    @Test
    void 不带子命令的help也打印故障变体的判定版本标记_纯ASCII_编排脚本按它拒绝旧包() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream sink = new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8);

        // 脚本的前置检查就是这样调的：java -jar xm-robot.jar --help（没有子命令、没有口令环境变量）
        assertThat(RobotMain.run(List.of("--help"), Map.of(), sink, sink)).isEqualTo(RobotMain.EXIT_PASS);

        String marker = com.game.robot.scenario.BattleCrashScenario.revisionMarker();
        assertThat(marker).matches("\\[crash-window-rev=[1-9][0-9]*]");
        assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("--crash-window").containsOnlyOnce(marker);
        // 标准输出不是 UTF-8 时（Windows 上不加 -Dstdout.encoding）中文会变样；最坏情形按 US-ASCII 输出，中文全成了「?」，这两个标记原样还在
        java.io.ByteArrayOutputStream ascii = new java.io.ByteArrayOutputStream();
        java.io.PrintStream asciiSink = new java.io.PrintStream(ascii, true, java.nio.charset.StandardCharsets.US_ASCII);
        RobotMain.run(List.of("--help"), Map.of(), asciiSink, asciiSink);
        String garbled = ascii.toString(java.nio.charset.StandardCharsets.US_ASCII);
        assertThat(garbled).doesNotContain("故障变体").contains("--crash-window", marker);
    }

    @Test
    void 匹配类三个子命令_缺省的match管理端口18113_命令行盖过环境变量_地址要带协议() throws Exception {
        RobotOptions smoke = RobotOptions.parse(List.of("battle-smoke", "--run-tag", "t1"), ENV, NOW);
        assertThat(smoke.scenario()).isEqualTo(RobotOptions.Scenario.BATTLE_SMOKE);
        assertThat(smoke.matchAdminUrl()).isEqualTo("http://127.0.0.1:18113");
        assertThat(smoke.battleAdminUrl()).as("xm-battle 的管理端口是另一个").isEqualTo("http://127.0.0.1:18112");
        assertThat(RobotOptions.parse(List.of("match-activity"), ENV, NOW).scenario()).isEqualTo(RobotOptions.Scenario.MATCH_ACTIVITY);
        assertThat(RobotOptions.parse(List.of("match-5v5"), ENV, NOW).scenario()).isEqualTo(RobotOptions.Scenario.MATCH_5V5);

        assertThat(RobotOptions.parse(List.of("match-activity", "--match-admin-url", "http://10.0.0.5:28113/"), ENV, NOW).matchAdminUrl())
                .as("去掉结尾斜杠").isEqualTo("http://10.0.0.5:28113");
        Map<String, String> env = Map.of(RobotOptions.PASSWORD_ENV, "p", "XM_ROBOT_MATCH_ADMIN_URL", "http://env:1");
        assertThat(RobotOptions.parse(List.of("battle-smoke"), env, NOW).matchAdminUrl()).isEqualTo("http://env:1");
        assertThat(RobotOptions.parse(List.of("battle-smoke", "--match-admin-url=http://arg:2"), env, NOW).matchAdminUrl()).isEqualTo("http://arg:2");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("match-5v5", "--match-admin-url", "127.0.0.1:18113"), ENV, NOW))
                .isInstanceOf(UsageException.class).hasMessageContaining("--match-admin-url");
        assertThat(smoke.toString()).contains("matchAdminUrl=http://127.0.0.1:18113").doesNotContain("dev-secret");

        assertThat(RobotOptions.usage()).contains("--match-admin-url <值>", "XM_ROBOT_MATCH_ADMIN_URL", "http://127.0.0.1:18113",
                "  battle-smoke ", "  match-activity ", "  match-5v5 ", "BATTLE_SMOKE_OK", "BATTLE_SMOKE_FAIL step=", "MATCH_ACTIVITY_OK", "MATCH_5V5_OK");
    }

    @Test
    void 匹配类子命令的账号都放得进64个字符_十个5V5账号按最长的那个算() {
        // 前缀 + 标签（bm / ma / m5）+ 16 位 run-tag + _ + 一位后缀
        String prefix = "p".repeat(64 - 2 - 16 - 2);
        String tag = "t".repeat(16);
        for (String sub : List.of("battle-smoke", "match-activity", "match-5v5")) {
            assertThat(catchUsage(List.of(sub, "--prefix", prefix, "--run-tag", tag))).as(sub + " 恰好 64 个字符").isNull();
            assertThat(catchUsage(List.of(sub, "--prefix", prefix + "p", "--run-tag", tag))).as(sub + " 65 个字符").contains("超过 64");
        }
    }

    private static String catchUsage(List<String> args) {
        try {
            RobotOptions.parse(args, ENV, NOW);
            return null;
        } catch (UsageException e) {
            return e.getMessage();
        }
    }

    @Test
    void 每个子命令都在帮助的用法行与说明行里_未知子命令的提示列全_缺少子命令的提示点到匹配类() {
        java.util.Set<String> all = new java.util.TreeSet<>();
        for (RobotOptions.Scenario scenario : RobotOptions.Scenario.values()) {
            all.add(scenario.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        }
        String usage = RobotOptions.usage();

        // 用法行：<a|b|c>
        String first = usage.lines().findFirst().orElseThrow();
        assertThat(first).startsWith("用法：").contains("<").contains(">");
        List<String> inUsageLine = List.of(first.substring(first.indexOf('<') + 1, first.indexOf('>')).split("\\|"));
        assertThat(inUsageLine).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);

        // 说明行：「  名字 说明」，friend / chat 两个共用一行
        java.util.regex.Pattern head = java.util.regex.Pattern.compile("^  ([a-z][a-z0-9-]*(?: / [a-z][a-z0-9-]*)*)\\s");
        List<String> described = new java.util.ArrayList<>();
        usage.lines().forEach(line -> {
            java.util.regex.Matcher m = head.matcher(line);
            if (m.find()) {
                described.addAll(List.of(m.group(1).split(" / ")));
            }
        });
        assertThat(described).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);

        // 未知子命令的提示把全部子命令列出来
        String unknown = catchUsage(List.of("fly"));
        assertThat(unknown).startsWith("未知子命令：fly（只有 ").endsWith("）");
        List<String> listed = List.of(unknown.substring(unknown.indexOf("（只有 ") + 4, unknown.length() - 1).split(" / "));
        assertThat(listed).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(all);

        // 缺少子命令的提示只举几个例子（含批次 6.4 新加的），其余指向 --help
        String missing = catchUsage(List.of());
        assertThat(missing).startsWith("缺少子命令").contains("battle-smoke", "match-activity", "--help");
        for (String example : missing.substring(missing.indexOf('（') + 1, missing.indexOf(" / …")).split(" / ")) {
            assertThat(all).as("「缺少子命令」里举的例子 %s 是真有的子命令", example).contains(example);
        }
    }

    @Test
    void 帮助() {
        assertThatThrownBy(() -> RobotOptions.parse(List.of("--help"), Map.of(), NOW))
                .isInstanceOfSatisfying(UsageException.class, e -> assertThat(e.isHelp()).isTrue());
        assertThat(RobotOptions.usage()).contains("smoke", "movement", "XM_ROBOT_GATEWAY", RobotOptions.PASSWORD_ENV);
    }

    @Test
    void 参数错误退出码为2_帮助为0() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream sink = new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(RobotMain.run(List.of("smoke"), Map.of(), sink, sink)).isEqualTo(RobotMain.EXIT_USAGE);
        assertThat(RobotMain.run(List.of("--help"), Map.of(), sink, sink)).isEqualTo(RobotMain.EXIT_PASS);
    }
}
