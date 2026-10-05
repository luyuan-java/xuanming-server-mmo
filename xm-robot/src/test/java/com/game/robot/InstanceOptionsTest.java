package com.game.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.robot.scenario.DungeonScenario;
import com.game.robot.scenario.MirrorScenario;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** mirror / dungeon 子命令（批次 5.3，dungeon-mirror-spec §12.6）的附加选项：缺省值、优先级、两种写法、校验、与 {@link RobotOptions} 的共存。 */
class InstanceOptionsTest {

    private static final Map<String, String> ENV = Map.of(RobotOptions.PASSWORD_ENV, "dev-secret");
    private static final long NOW = 1_760_000_000_000L;

    @Test
    void 缺省值_Mirror第一行_等90秒_strict_scene管理端口18104_scene_manager管理端口18102() throws Exception {
        InstanceOptions o = InstanceOptions.parse(List.of("mirror"), Map.of());

        assertThat(o.mirrorConfigId()).isEqualTo(1);
        assertThat(o.instanceWait()).isEqualTo(Duration.ofSeconds(90));
        assertThat(o.strictMirrorValidation()).isTrue();
        assertThat(o.sceneAdminUrl()).isEqualTo("http://127.0.0.1:18104");
        assertThat(o.sceneManagerMetricsUrl()).isEqualTo("http://127.0.0.1:18102");
    }

    @Test
    void 命令行优先于环境变量_两种写法都认_地址去掉结尾斜杠() throws Exception {
        Map<String, String> env = Map.of("XM_ROBOT_MIRROR_CONFIG_ID", "2", "XM_ROBOT_INSTANCE_WAIT_MS", "20000",
                "XM_ROBOT_EXPECT_MIRROR_VALIDATION", "lenient", "XM_ROBOT_SCENE_ADMIN_URL", "http://env:1/");

        InstanceOptions fromEnv = InstanceOptions.parse(List.of("mirror"), env);
        assertThat(fromEnv.mirrorConfigId()).isEqualTo(2);
        assertThat(fromEnv.instanceWait()).isEqualTo(Duration.ofSeconds(20));
        assertThat(fromEnv.strictMirrorValidation()).isFalse();
        assertThat(fromEnv.sceneAdminUrl()).isEqualTo("http://env:1");

        InstanceOptions fromArgs = InstanceOptions.parse(List.of("--mirror-config-id=1", "mirror", "--instance-wait-ms", "15000",
                "--expect-mirror-validation", "strict", "--scene-admin-url", "http://arg:2//",
                "--scene-manager-metrics-url=https://sm:3/", "--zone", "7"), env);
        assertThat(fromArgs.mirrorConfigId()).isEqualTo(1);
        assertThat(fromArgs.instanceWait()).isEqualTo(Duration.ofSeconds(15));
        assertThat(fromArgs.strictMirrorValidation()).isTrue();
        assertThat(fromArgs.sceneAdminUrl()).isEqualTo("http://arg:2");
        assertThat(fromArgs.sceneManagerMetricsUrl()).as("别的选项照常跳过").isEqualTo("https://sm:3");
    }

    @Test
    void 开关选项后面的子命令不被吃掉() throws Exception {
        InstanceOptions o = InstanceOptions.parse(List.of("--slow", "mirror", "--mirror-config-id", "2"), Map.of());

        assertThat(o.mirrorConfigId()).isEqualTo(2);
    }

    @Test
    void 非法取值_报参数错误() {
        assertThatThrownBy(() -> InstanceOptions.parse(List.of("mirror", "--mirror-config-id", "0"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--mirror-config-id");
        assertThatThrownBy(() -> InstanceOptions.parse(List.of("mirror", "--mirror-config-id", "x"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--mirror-config-id");
        assertThatThrownBy(() -> InstanceOptions.parse(List.of("mirror", "--instance-wait-ms", "0"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--instance-wait-ms");
        assertThatThrownBy(() -> InstanceOptions.parse(List.of("mirror", "--instance-wait-ms", "600001"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--instance-wait-ms");
        assertThatThrownBy(() -> InstanceOptions.parse(List.of("mirror", "--expect-mirror-validation", "loose"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("strict / lenient");
        assertThatThrownBy(() -> InstanceOptions.parse(List.of("dungeon", "--scene-admin-url", "127.0.0.1:18104"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--scene-admin-url");
        assertThatThrownBy(() -> InstanceOptions.parse(List.of("dungeon", "--scene-manager-metrics-url"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("缺少取值");
        assertThatThrownBy(() -> InstanceOptions.parse(List.of("dungeon", "--no-such-option", "1"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("未知选项");
    }

    @Test
    void 子命令mirror与dungeon_账号带mr与dg标签_帮助里有附加选项() throws Exception {
        RobotOptions mirror = RobotOptions.parse(List.of("mirror", "--run-tag", "t1", "--mirror-config-id", "2"), ENV, NOW);
        RobotOptions dungeon = RobotOptions.parse(List.of("dungeon", "--run-tag", "t1", "--expect-dev", "deny"), ENV, NOW);

        assertThat(mirror.scenario()).isEqualTo(RobotOptions.Scenario.MIRROR);
        assertThat(dungeon.scenario()).isEqualTo(RobotOptions.Scenario.DUNGEON);
        assertThat(dungeon.expectDevAllowed()).isFalse();
        assertThat(MirrorScenario.accountName(mirror.accountPrefix(), mirror.runTag(), "a")).isEqualTo("robot_java_mrt1_a");
        assertThat(DungeonScenario.accountName(dungeon.accountPrefix(), dungeon.runTag(), "b")).isEqualTo("robot_java_dgt1_b");
        assertThat(RobotOptions.usage()).contains("|mirror|dungeon|", "  mirror    ", "  dungeon   ", "--mirror-config-id",
                "--instance-wait-ms", "--expect-mirror-validation", "XM_ROBOT_SCENE_ADMIN_URL", "XM_ROBOT_SCENE_MANAGER_METRICS_URL");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("mirrors"), ENV, NOW))
                .isInstanceOf(UsageException.class).hasMessageContaining("mirror / dungeon");
    }

    @Test
    void 账号超长时拒绝_按最长的角色算() throws Exception {
        // 前缀 + mr / dg + 标签 + _x：44 + 2 + 16 + 2 = 64 刚好放得下，再长一个字符就拒
        String prefix = "p".repeat(44);
        assertThat(RobotOptions.parse(List.of("mirror", "--prefix", prefix, "--run-tag", "abcdefghijklmnop"), ENV, NOW).scenario())
                .isEqualTo(RobotOptions.Scenario.MIRROR);
        assertThatThrownBy(() -> RobotOptions.parse(List.of("mirror", "--prefix", prefix + "p", "--run-tag", "abcdefghijklmnop"),
                ENV, NOW)).isInstanceOf(UsageException.class).hasMessageContaining("超过");
        assertThatThrownBy(() -> RobotOptions.parse(List.of("dungeon", "--prefix", prefix + "p", "--run-tag", "abcdefghijklmnop"),
                ENV, NOW)).isInstanceOf(UsageException.class).hasMessageContaining("超过");
    }

    @Test
    void mirror的附加选项错了_RobotMain退出码为2() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.PrintStream sink = new java.io.PrintStream(out, true, java.nio.charset.StandardCharsets.UTF_8);

        int exit = RobotMain.run(List.of("mirror", "--mirror-config-id", "0"), ENV, sink, sink);

        assertThat(exit).isEqualTo(RobotMain.EXIT_USAGE);
        assertThat(out.toString(java.nio.charset.StandardCharsets.UTF_8)).contains("--mirror-config-id");
    }
}
