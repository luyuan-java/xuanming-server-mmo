package com.game.robot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.robot.CrashWindowOptions.Phase;
import com.game.robot.CrashWindowOptions.Variant;
import com.game.robot.scenario.BattleCrashScenario;
import com.game.robot.scenario.BattleSettleScenario;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** battle-settle 故障变体（scene-battle-spec §13.8）的附加选项：缺省值、优先级、两种写法、校验、与 {@link RobotOptions} 的共存。 */
class CrashWindowOptionsTest {

    private static final Map<String, String> ENV = Map.of(RobotOptions.PASSWORD_ENV, "dev-secret");
    private static final long NOW = 1_760_000_000_000L;

    @Test
    void 缺省不是故障变体_阶段arm_状态文件在run目录下() throws Exception {
        CrashWindowOptions o = CrashWindowOptions.parse(List.of("battle-settle"), Map.of());

        assertThat(o.variant()).isEqualTo(Variant.NONE);
        assertThat(o.enabled()).as("缺省跑完整的 battle-settle").isFalse();
        assertThat(o.phase()).isEqualTo(Phase.ARM);
        assertThat(o.stateFile()).isEqualTo(Path.of("run", "battle-crash-window.state"));
    }

    @Test
    void 命令行优先于环境变量_两种写法都认_别的选项照常跳过() throws Exception {
        Map<String, String> env = Map.of("XM_ROBOT_CRASH_WINDOW", "scene-after-150", "XM_ROBOT_CRASH_PHASE", "verify",
                "XM_ROBOT_CRASH_STATE", "run/env.state");

        CrashWindowOptions fromEnv = CrashWindowOptions.parse(List.of("battle-settle"), env);
        assertThat(fromEnv.variant()).isEqualTo(Variant.SCENE_AFTER_150);
        assertThat(fromEnv.enabled()).isTrue();
        assertThat(fromEnv.phase()).isEqualTo(Phase.VERIFY);
        assertThat(fromEnv.stateFile()).isEqualTo(Path.of("run/env.state"));

        CrashWindowOptions fromArgs = CrashWindowOptions.parse(List.of("--crash-window=battle-after-store", "battle-settle", "--crash-phase", "arm",
                "--zone", "7", "--slow", "--crash-state", "run/arg.state", "--scene-metrics-url=http://a:1,http://b:2"), env);
        assertThat(fromArgs.variant()).isEqualTo(Variant.BATTLE_AFTER_STORE);
        assertThat(fromArgs.phase()).isEqualTo(Phase.ARM);
        assertThat(fromArgs.stateFile()).as("开关 --slow 不吃掉后面的选项").isEqualTo(Path.of("run/arg.state"));
    }

    @Test
    void 命令行写法是小写加连字符_与枚举互相找得到() {
        assertThat(Variant.SCENE_AFTER_150.wire()).isEqualTo("scene-after-150");
        assertThat(Variant.BATTLE_AFTER_STORE.wire()).isEqualTo("battle-after-store");
        assertThat(Variant.NONE.wire()).isEqualTo("none");
        for (Variant v : Variant.values()) {
            assertThat(Variant.ofWire(v.wire())).isEqualTo(v);
        }
        assertThat(Variant.ofWire("SCENE_AFTER_150")).as("不认枚举名").isNull();
        assertThat(Phase.ofWire("arm")).isEqualTo(Phase.ARM);
        assertThat(Phase.ofWire("verify")).isEqualTo(Phase.VERIFY);
        assertThat(Phase.ofWire("ARM")).isNull();
    }

    @Test
    void 非法取值_报参数错误() {
        assertThatThrownBy(() -> CrashWindowOptions.parse(List.of("battle-settle", "--crash-window", "scene"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--crash-window").hasMessageContaining("scene-after-150");
        assertThatThrownBy(() -> CrashWindowOptions.parse(List.of("battle-settle", "--crash-phase", "check"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("arm / verify");
        assertThatThrownBy(() -> CrashWindowOptions.parse(List.of("battle-settle", "--crash-state"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("缺少取值");
        assertThatThrownBy(() -> CrashWindowOptions.parse(List.of("battle-settle", "--crash-state", "/"), Map.of()))
                .isInstanceOf(UsageException.class).hasMessageContaining("--crash-state");
        assertThatThrownBy(() -> CrashWindowOptions.parse(List.of("battle-settle", "--crash-kill-pid", "1"), Map.of()))
                .as("robot 不杀进程：没有这种选项").isInstanceOf(UsageException.class).hasMessageContaining("未知选项");
    }

    @Test
    void 与RobotOptions共存_同一份命令行两边都解析得过_帮助里列出三个选项() throws Exception {
        List<String> args = List.of("battle-settle", "--crash-window", "battle-after-store", "--crash-phase=verify", "--crash-state", "run/x.state",
                "--run-tag", "t1");

        RobotOptions options = RobotOptions.parse(args, ENV, NOW);
        CrashWindowOptions crash = CrashWindowOptions.parse(args, ENV);

        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.BATTLE_SETTLE);
        assertThat(crash.variant()).isEqualTo(Variant.BATTLE_AFTER_STORE);
        assertThat(crash.phase()).isEqualTo(Phase.VERIFY);
        assertThat(RobotOptions.usage()).contains("--crash-window", "XM_ROBOT_CRASH_WINDOW", "--crash-phase", "--crash-state",
                "run/battle-crash-window.state", "tools/local/battle-crash-window.sh", "scene-after-150", "battle-after-store");
    }

    @Test
    void 故障变体的账号带bc标签_与battle_settle的账号等长_不会撞名() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("battle-settle", "--run-tag", "t1"), ENV, NOW);

        String crashAccount = BattleCrashScenario.accountName(options.accountPrefix(), options.runTag());
        String settleAccount = BattleSettleScenario.accountName(options.accountPrefix(), options.runTag(), "a");

        assertThat(crashAccount).isEqualTo("robot_java_bct1_a");
        assertThat(settleAccount).isEqualTo("robot_java_bst1_a");
        assertThat(crashAccount).as("RobotOptions 按 battle-settle 的账号查长度上限，两者必须等长").hasSameSizeAs(settleAccount);
        assertThat(BattleSettleScenario.accountName(options.accountPrefix(), options.runTag(), "c")).as("第 11 步的探针账号")
                .hasSameSizeAs(settleAccount);
    }

    @Test
    void 命令行上非法的故障变体取值_退出码为2() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream sink = new PrintStream(out, true, StandardCharsets.UTF_8);

        int exit = RobotMain.run(List.of("battle-settle", "--crash-window", "scene"), ENV, sink, sink);

        assertThat(exit).isEqualTo(RobotMain.EXIT_USAGE);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("参数错误", "--crash-window");
    }
}
