package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.contract.MessageIdRegistry;
import com.game.robot.CrashWindowOptions;
import com.game.robot.CrashWindowOptions.Phase;
import com.game.robot.CrashWindowOptions.Variant;
import com.game.robot.client.BattleAdminClient;
import com.game.robot.client.MessageIds;
import com.game.robot.client.RobotClient;
import com.game.robot.flow.PlayerFlow;
import com.game.robot.scenario.BattleCrashChecks.Landing;
import com.game.robot.scenario.BattleCrashChecks.Stage;
import com.game.robot.scenario.BattleCrashChecks.State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 故障变体与 battle-settle 两个场景里不连服务端就走得到的部分：用同步来的消息号表构造得出来（服务名 / 方法名写错在这里就失败）、
 * 没有运维令牌 / 状态文件不对（别的变体、阶段没走完、gold_gain = 0 见证不了「恰好一次」、battle-after-store 的 arm 结局不是 rescued）时
 * 在第一步就停下并给出带步骤号的汇总行、arm 一开始就删掉上一轮的状态文件。
 * 需要活的切片的部分（断点、kill、rescue、重登）由 {@code tools/local/battle-crash-window.sh} 在本机切片上跑。
 */
class BattleCrashScenarioTest {

    private static final MessageIdRegistry REGISTRY = MessageIdRegistry.loadFromClasspath();
    private static final Duration SHORT = Duration.ofSeconds(2);
    /** 一个没有人听的本机端口上的「gateway」：assign-gate 立刻连接失败，场景按流程中断收场。 */
    private static final String NO_GATEWAY = "http://127.0.0.1:1";

    private static Path tableDir() {
        return Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables") : Path.of("config-data/tables");
    }

    private static BattleAdminClient admin(String token) {
        return new BattleAdminClient("http://127.0.0.1:1", token, SHORT);
    }

    private static BattleCrashScenario crash(RobotClient client, String token, Variant variant, Phase phase, Path stateFile) {
        PlayerFlow flow = client == null ? null : new PlayerFlow(client, "dev-secret", SHORT, SHORT);
        return new BattleCrashScenario(client, flow, REGISTRY, admin(token), "http://127.0.0.1:1", "robot_java_", "t1",
                new CrashWindowOptions(variant, phase, stateFile), SHORT);
    }

    private static State armed(Variant variant) {
        return new State(variant, Stage.ARMED, "robot_java_bct1_a", 7, 1_760_000_000_000_001L, 100, 30, 1_760_000_100_000L, 1_760_000_050_000L, 0,
                "");
    }

    /**
     * verify 阶段期望读到的那种状态文件：scene-after-150 停在断点（armed）；battle-after-store 的 arm 已看完结局（observed，带 {@code outcome}）。
     *
     * @param outcome 只对 battle-after-store 有意义
     */
    private static State readyForVerify(Variant variant, long goldGain, String outcome) {
        State armed = new State(variant, Stage.ARMED, "robot_java_bct1_a", 7, 1_760_000_000_000_001L, 100, goldGain, 1_760_000_100_000L,
                1_760_000_050_000L, 0, "");
        return variant == Variant.BATTLE_AFTER_STORE ? armed.observed(1_760_000_111_500L, outcome) : armed;
    }

    @Test
    void 没有运维令牌_在连任何服务之前就停下_汇总行带变体_阶段与步骤(@TempDir Path dir) {
        for (Variant variant : new Variant[] {Variant.SCENE_AFTER_150, Variant.BATTLE_AFTER_STORE}) {
            for (Phase phase : Phase.values()) {
                CheckReport report = crash(null, null, variant, phase, dir.resolve("s.state")).run();

                assertThat(report.passed()).isFalse();
                assertThat(report.items()).singleElement().satisfies(item -> assertThat(item.detail()).contains("没有运维令牌"));
                assertThat(report.notes()).last().isEqualTo("BATTLE_CRASH_FAIL variant=" + variant.wire() + " phase=" + phase.wire() + " step=流程");
            }
        }
    }

    @Test
    void verify_没有状态文件_停在state一步_提示先跑arm(@TempDir Path dir) {
        CheckReport report = crash(null, "token", Variant.SCENE_AFTER_150, Phase.VERIFY, dir.resolve("missing.state")).run();

        assertThat(report.passed()).isFalse();
        assertThat(report.items()).singleElement().satisfies(item -> assertThat(item.detail()).contains("--crash-phase arm"));
        assertThat(report.notes()).last().isEqualTo("BATTLE_CRASH_FAIL variant=scene-after-150 phase=verify step=state");
    }

    @Test
    void verify_状态文件属于另一个变体_停在state一步_不去登录(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("s.state");
        BattleCrashChecks.write(file, armed(Variant.BATTLE_AFTER_STORE));

        // flow 为 null：真走到登录会抛空指针并多记一条「流程中断：relogin」
        CheckReport report = crash(null, "token", Variant.SCENE_AFTER_150, Phase.VERIFY, file).run();

        assertThat(report.items()).singleElement().satisfies(item -> {
            assertThat(item.passed()).isFalse();
            assertThat(item.detail()).contains("variant=battle-after-store", "stage=armed", "battle_id=1760000000000001");
        });
        assertThat(report.notes()).last().isEqualTo("BATTLE_CRASH_FAIL variant=scene-after-150 phase=verify step=state");
    }

    @Test
    void verify_battle_after_store要求arm已看完rescue的结局_只到断点的状态文件不认(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("s.state");
        BattleCrashChecks.write(file, armed(Variant.BATTLE_AFTER_STORE));

        CheckReport report = crash(null, "token", Variant.BATTLE_AFTER_STORE, Phase.VERIFY, file).run();

        assertThat(report.items()).singleElement().satisfies(item -> {
            assertThat(item.passed()).isFalse();
            assertThat(item.detail()).contains("stage=armed", "没有看完 rescue 的结局");
        });
        assertThat(report.notes()).last().isEqualTo("BATTLE_CRASH_FAIL variant=battle-after-store phase=verify step=state");
    }

    @Test
    void verify_状态文件的gold_gain为0_金币见证不了恰好一次_停在state一步_不去登录(@TempDir Path dir) throws Exception {
        // gold_gain = 0 时「结算整笔丢失」与「已落盘、不重发」的现象完全相同（0 条 150、金币不变）：这样的状态文件不能拿来下结论
        for (Variant variant : new Variant[] {Variant.SCENE_AFTER_150, Variant.BATTLE_AFTER_STORE}) {
            Path file = dir.resolve(variant.wire() + ".state");
            BattleCrashChecks.write(file, readyForVerify(variant, 0, Landing.RESCUED.wire()));

            // flow 为 null：真走到登录会抛空指针，汇总行就是 step=relogin 而不是 step=state
            CheckReport report = crash(null, "token", variant, Phase.VERIFY, file).run();

            assertThat(report.passed()).as(variant.wire()).isFalse();
            assertThat(report.items()).filteredOn(item -> !item.passed()).as("%s：只有 gold_gain 这一条失败，没有「流程中断：relogin」", variant.wire())
                    .singleElement().satisfies(item -> {
                        assertThat(item.name()).contains("gold_gain > 0");
                        assertThat(item.detail()).contains("gold_gain=0", "见证不了");
                    });
            // 变体与阶段那一条通过；battle-after-store 的结局（rescued）那一条也照常判、通过
            assertThat(report.items()).filteredOn(CheckReport.Item::passed).hasSize(variant == Variant.BATTLE_AFTER_STORE ? 2 : 1);
            assertThat(report.notes()).last().isEqualTo("BATTLE_CRASH_FAIL variant=" + variant.wire() + " phase=verify step=state");
        }
    }

    @Test
    void verify_battle_after_store_arm记下的结局不是rescued_停在state一步_不去登录(@TempDir Path dir) throws Exception {
        // early = 150 在期限 + 10 s 之前就到了（没走 rescue）；missing = 一直没到；空串 = 手改过的文件。verify 的三步在 early 时都会过，不能据此报 OK
        for (String outcome : List.of(Landing.EARLY.wire(), Landing.MISSING.wire(), "")) {
            Path file = dir.resolve("s-" + outcome + ".state");
            BattleCrashChecks.write(file, readyForVerify(Variant.BATTLE_AFTER_STORE, 30, outcome));

            CheckReport report = crash(null, "token", Variant.BATTLE_AFTER_STORE, Phase.VERIFY, file).run();

            assertThat(report.passed()).as("outcome=%s", outcome).isFalse();
            assertThat(report.items()).filteredOn(item -> !item.passed()).as("outcome=%s：只有结局这一条失败，没有「流程中断：relogin」", outcome)
                    .singleElement().satisfies(item -> {
                        assertThat(item.name()).contains("rescued");
                        assertThat(item.detail()).contains("outcome=" + outcome);
                    });
            assertThat(report.notes()).last().isEqualTo("BATTLE_CRASH_FAIL variant=battle-after-store phase=verify step=state");
        }
    }

    @Test
    void verify_状态文件是旧判定版本的robot写的_停在state一步_不去登录(@TempDir Path dir) throws Exception {
        // arm 用了旧包（判定版本 1：写断点不看胜负与 gold_gain）、verify 用新包：内容再「合格」也不认，否则旧 arm 放过的一局会被新 verify 报成 OK
        for (Variant variant : new Variant[] {Variant.SCENE_AFTER_150, Variant.BATTLE_AFTER_STORE}) {
            Path file = dir.resolve(variant.wire() + ".state");
            String current = readyForVerify(variant, 30, Landing.RESCUED.wire()).encode();
            assertThat(current).contains("\nversion=" + BattleCrashChecks.STATE_VERSION + "\n");
            Files.writeString(file, current.replace("\nversion=" + BattleCrashChecks.STATE_VERSION + "\n", "\nversion=1\n"));

            CheckReport report = crash(null, "token", variant, Phase.VERIFY, file).run();

            assertThat(report.items()).as(variant.wire()).singleElement().satisfies(item -> {
                assertThat(item.passed()).isFalse();
                assertThat(item.name()).isEqualTo("流程中断：state");
                assertThat(item.detail()).contains("version=1", "只认 " + BattleCrashChecks.STATE_VERSION, "同一个 robot 包");
            });
            assertThat(report.notes()).last().isEqualTo("BATTLE_CRASH_FAIL variant=" + variant.wire() + " phase=verify step=state");
        }
    }

    @Test
    void verify_状态文件合格时过得了state一步_走到重登才因为没有flow而中断(@TempDir Path dir) throws Exception {
        // 正对照：上面几条「停在 state 一步」不是因为 state 步无论如何都失败
        for (Variant variant : new Variant[] {Variant.SCENE_AFTER_150, Variant.BATTLE_AFTER_STORE}) {
            Path file = dir.resolve(variant.wire() + ".state");
            BattleCrashChecks.write(file, readyForVerify(variant, 30, Landing.RESCUED.wire()));

            CheckReport report = crash(null, "token", variant, Phase.VERIFY, file).run();

            assertThat(report.items()).filteredOn(item -> !item.passed()).as(variant.wire()).singleElement()
                    .satisfies(item -> assertThat(item.name()).isEqualTo("流程中断：relogin"));
            assertThat(report.items()).filteredOn(CheckReport.Item::passed).as("%s：state 步的检查都通过", variant.wire())
                    .hasSize(variant == Variant.BATTLE_AFTER_STORE ? 3 : 2);
            assertThat(report.notes()).last().isEqualTo("BATTLE_CRASH_FAIL variant=" + variant.wire() + " phase=verify step=relogin");
        }
    }

    @Test
    void arm_一开始就删掉上一轮的状态文件_登录不上时不留断点_编排脚本就不会杀进程(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("s.state");
        BattleCrashChecks.write(file, armed(Variant.SCENE_AFTER_150));
        MessageIds ids = MessageIds.resolve(REGISTRY);

        CheckReport report;
        try (RobotClient client = new RobotClient(NO_GATEWAY, 1, ids, SHORT, SHORT)) {
            report = crash(client, "token", Variant.SCENE_AFTER_150, Phase.ARM, file).run();
        }

        assertThat(Files.exists(file)).as("上一轮的断点文件已删，这一轮没走到断点就不会再有").isFalse();
        assertThat(report.passed()).isFalse();
        assertThat(report.items()).singleElement().satisfies(item -> assertThat(item.name()).isEqualTo("流程中断：login"));
        assertThat(report.notes()).last().isEqualTo("BATTLE_CRASH_FAIL variant=scene-after-150 phase=arm step=login");
    }

    @Test
    void 不是故障变体时拒绝构造() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> crash(null, "token", Variant.NONE, Phase.ARM, Path.of("run/x.state")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("--crash-window none");
    }

    @Test
    void battle_settle_消息号都解析得出来_没有运维令牌时汇总行带步骤号() {
        // 构造器里 requireId 的每个「服务 + 方法」都要在同步来的 message_id.txt 里（含第 12 步新加的 CreateTeam / GetMyTeam / DisbandTeam）
        BattleSettleScenario scenario = new BattleSettleScenario(null, null, MessageIds.resolve(REGISTRY), REGISTRY, admin(null), tableDir(),
                "http://127.0.0.1:18104,http://127.0.0.1:18114", "robot_java_", "t1", true, false, SHORT, SHORT);

        CheckReport report = scenario.run();

        assertThat(scenario.accountA()).isEqualTo("robot_java_bst1_a");
        assertThat(report.passed()).isFalse();
        assertThat(report.items()).singleElement().satisfies(item -> assertThat(item.detail()).contains("没有运维令牌"));
        assertThat(report.notes()).last().isEqualTo("BATTLE_SETTLE_FAIL step=流程");
    }
}
