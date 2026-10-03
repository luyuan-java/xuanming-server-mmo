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
