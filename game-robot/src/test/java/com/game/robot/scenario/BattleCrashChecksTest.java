package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.eBattleOutcome;
import com.game.robot.CrashWindowOptions.Phase;
import com.game.robot.CrashWindowOptions.Variant;
import com.game.robot.client.RobotException;
import com.game.robot.scenario.BattleCrashChecks.Landing;
import com.game.robot.scenario.BattleCrashChecks.Replay;
import com.game.robot.scenario.BattleCrashChecks.Stage;
import com.game.robot.scenario.BattleCrashChecks.State;
import com.game.table.ConfigTables;
import com.game.table.DungeonTable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** battle-settle 故障变体的纯函数件（scene-battle-spec §13.8「故障变体」）：状态文件、恰好一次与 rescue 的判定、汇总行。 */
class BattleCrashChecksTest {

    /** 大于 Long.MAX_VALUE 的号（uint64）：文件里必须是无符号十进制。 */
    private static final long BIG = 0x8000_0000_0000_0001L;
    private static final long DEADLINE = 1_760_000_100_000L;
    private static final long GRACE = 10_000;
    private static final long TOLERANCE = 1_000;

    private static State armed(Variant variant) {
        return new State(variant, Stage.ARMED, "robot_java_bct1_a", BIG, 1_760_000_000_000_007L, 500, 30, DEADLINE, 1_760_000_050_000L, 0, "");
    }

    // ------------------------------------------------------------------ 状态文件

    @Test
    void 状态文件_逐行键值_编排脚本按整行匹配stage() {
        String text = armed(Variant.BATTLE_AFTER_STORE).encode();

        assertThat(text.lines().filter(line -> !line.startsWith("#"))).containsExactly(
                "version=2",
                "variant=battle-after-store",
                "stage=armed",
                "account=robot_java_bct1_a",
                "player_id=9223372036854775809",
                "battle_id=1760000000000007",
                "gold_before=500",
                "gold_gain=30",
                "deadline_ms=1760000100000",
                "breakpoint_at_ms=1760000050000",
                "lobby_end_at_ms=0",
                "outcome=");
        assertThat(text).as("LF 换行、以换行结尾、没有回车（脚本用 grep '^stage=armed$'）").endsWith("\n").doesNotContain("\r");
        assertThat(armed(Variant.SCENE_AFTER_150).encode()).contains("\nvariant=scene-after-150\n");
    }

    @Test
    void 状态文件_编码再解码逐字段相同_观察后的结局也带上() throws Exception {
        State armed = armed(Variant.SCENE_AFTER_150);
        assertThat(State.decode(armed.encode())).isEqualTo(armed);

        State observed = armed(Variant.BATTLE_AFTER_STORE).observed(DEADLINE + GRACE + 1_500, Landing.RESCUED.wire());
        State back = State.decode(observed.encode());
        assertThat(back).isEqualTo(observed);
        assertThat(back.stage()).isEqualTo(Stage.OBSERVED);
        assertThat(back.outcome()).isEqualTo("rescued");
        assertThat(back.lobbyEndAtMs()).isEqualTo(DEADLINE + GRACE + 1_500);
        assertThat(back.playerId()).as("uint64 不丢高位").isEqualTo(BIG);
        assertThat(back.breakpointAtMs()).as("断点时刻不随观察改写").isEqualTo(armed.breakpointAtMs());
    }

    @Test
    void 状态文件_解码容忍回车_空行_注释与不认识的键_账号里可以有等号() throws Exception {
        String text = armed(Variant.SCENE_AFTER_150).encode().replace("account=robot_java_bct1_a", "account=dev=x_a").replace("\n", "\r\n")
                + "\r\n# 以后加的字段\r\nfuture_field=1\r\n";

        State state = State.decode(text);

        assertThat(state.account()).isEqualTo("dev=x_a");
        assertThat(state.variant()).isEqualTo(Variant.SCENE_AFTER_150);
        assertThat(state.goldGain()).isEqualTo(30);
    }

    @Test
    void 状态文件_缺键_坏数字_不认识的版本与枚举都拒读_消息指明是哪一项() {
        String good = armed(Variant.SCENE_AFTER_150).encode();

        assertThatThrownBy(() -> State.decode(good.replace("gold_gain=30\n", ""))).isInstanceOf(RobotException.class).hasMessageContaining("gold_gain");
        assertThatThrownBy(() -> State.decode(good.replace("gold_gain=30", "gold_gain=x"))).hasMessageContaining("gold_gain");
        assertThatThrownBy(() -> State.decode(good.replace("gold_before=500", "gold_before=-1"))).hasMessageContaining("gold_before");
        assertThatThrownBy(() -> State.decode(good.replace("player_id=9223372036854775809", "player_id=-5"))).hasMessageContaining("player_id");
        assertThatThrownBy(() -> State.decode(good.replace("battle_id=1760000000000007", "battle_id=0"))).hasMessageContaining("battle_id");
        assertThatThrownBy(() -> State.decode(good.replace("version=2", "version=3"))).hasMessageContaining("version=3");
        // 判定版本 1 的 robot（写断点不看胜负与 gold_gain）留下的文件：本版拒读，arm 与 verify 混用新旧两个包时停在 state 步
        assertThatThrownBy(() -> State.decode(good.replace("version=2", "version=1"))).isInstanceOf(RobotException.class)
                .hasMessageContaining("version=1").hasMessageContaining("只认 2");
        assertThatThrownBy(() -> State.decode(good.replace("variant=scene-after-150", "variant=none"))).hasMessageContaining("variant");
        assertThatThrownBy(() -> State.decode(good.replace("variant=scene-after-150", "variant=gate-after-x"))).hasMessageContaining("gate-after-x");
        assertThatThrownBy(() -> State.decode(good.replace("stage=armed", "stage=ARMED"))).hasMessageContaining("stage");
        assertThatThrownBy(() -> State.decode(good.replace("account=robot_java_bct1_a", "account="))).hasMessageContaining("account");
        assertThatThrownBy(() -> State.decode(good + "这一行没有等号\n")).hasMessageContaining("键=值");
        assertThatThrownBy(() -> State.decode("")).hasMessageContaining("version");
    }

    @Test
    void 状态文件_构造时拒绝会破坏逐行格式的值() {
        assertThatThrownBy(() -> new State(Variant.NONE, Stage.ARMED, "a", 1, 2, 0, 0, 0, 0, 0, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new State(Variant.SCENE_AFTER_150, Stage.ARMED, "a\nstage=observed", 1, 2, 0, 0, 0, 0, 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new State(Variant.SCENE_AFTER_150, Stage.ARMED, "", 1, 2, 0, 0, 0, 0, 0, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new State(Variant.SCENE_AFTER_150, Stage.ARMED, "a", 1, 2, 0, 0, 0, 0, 0, "x\ny"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 状态文件_原子写_目录不存在时建出来_不留临时文件_覆盖上一次的内容(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("run").resolve("battle-crash-window.state");
        State armed = armed(Variant.BATTLE_AFTER_STORE);

        BattleCrashChecks.write(file, armed);

        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(armed.encode());
        try (Stream<Path> siblings = Files.list(file.getParent())) {
            assertThat(siblings.map(p -> p.getFileName().toString())).as("临时文件已改名成正式文件").containsExactly("battle-crash-window.state");
        }
        assertThat(BattleCrashChecks.read(file)).isEqualTo(armed);

        State observed = armed.observed(DEADLINE + GRACE + 2_000, "rescued");
        BattleCrashChecks.write(file, observed);
        assertThat(BattleCrashChecks.read(file)).isEqualTo(observed);
    }

    @Test
    void 状态文件_没有文件时提示先跑arm_删除是幂等的(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("none.state");

        assertThatThrownBy(() -> BattleCrashChecks.read(file)).isInstanceOf(RobotException.class).hasMessageContaining("--crash-phase arm");
        BattleCrashChecks.delete(file);
        BattleCrashChecks.write(file, armed(Variant.SCENE_AFTER_150));
        BattleCrashChecks.delete(file);
        assertThat(Files.exists(file)).as("上一轮的状态文件不能被这一轮的脚本当成断点").isFalse();
        BattleCrashChecks.delete(file);
    }

    // ------------------------------------------------------------------ 见证量：打赢且 gold_gain > 0 才能写断点

    @Test
    void 见证量_只有打赢且gold_gain大于0的一局才见证得了恰好一次() {
        assertThat(BattleCrashChecks.witnessProblem(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 30)).isNull();
        assertThat(BattleCrashChecks.witnessProblem(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 1)).isNull();
        // 打赢但本人阵亡 / 怪没有金币奖励：引擎不结金币
        assertThat(BattleCrashChecks.witnessProblem(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 0)).contains("gold_gain=0").contains("见证不了")
                .doesNotContain("没有打赢");
        assertThat(BattleCrashChecks.witnessProblem(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, 0)).contains("没有打赢")
                .contains("outcome=BATTLE_OUTCOME_SIDE_B_WIN").contains("gold_gain=0");
        assertThat(BattleCrashChecks.witnessProblem(eBattleOutcome.BATTLE_OUTCOME_DRAW, 0)).contains("没有打赢").contains("outcome=BATTLE_OUTCOME_DRAW");
        assertThat(BattleCrashChecks.witnessProblem(eBattleOutcome.BATTLE_OUTCOME_ONGOING, 0)).contains("没有打赢");
        assertThat(BattleCrashChecks.witnessProblem(eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, 30)).as("没打赢却带金币：不当成合格的一局").contains("没有打赢");

        assertThat(BattleCrashChecks.goldWitnessProblem(1)).isNull();
        assertThat(BattleCrashChecks.goldWitnessProblem(0)).startsWith("gold_gain=0：").contains("整笔丢失");
        assertThat(BattleCrashChecks.goldWitnessProblem(-1)).startsWith("gold_gain=-1：");
    }

    @Test
    void 见证量_gold_gain为0时整笔丢失与已落盘的现象相同_大于0才分得开() {
        // scene 重启后重登：0 条 150、金币没变。gold_gain = 0 → 两条判据都「通过」，结论却可能是整笔丢失
        assertThat(BattleCrashChecks.replayOf(0)).isEqualTo(Replay.DURABLE);
        assertThat(BattleCrashChecks.goldProblem(500, 0, 500)).isNull();
        assertThat(BattleCrashChecks.goldWitnessProblem(0)).as("所以这样的一局在写断点之前、读状态文件时都被拒绝").isNotNull();
        // gold_gain > 0：同样的现象被金币判据报成「没有到账」
        assertThat(BattleCrashChecks.goldProblem(500, 30, 500)).contains("没有到账");
    }

    @Test
    void 断点状态_只能由见证得了恰好一次的一局产生_否则抛出_不留断点文件(@TempDir Path dir) throws Exception {
        State sceneArmed = BattleCrashChecks.armedAt(Variant.SCENE_AFTER_150, "robot_java_bct1_a", BIG, 1_760_000_000_000_007L, 500,
                eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 30, DEADLINE, 1_760_000_050_000L, 1_760_000_050_000L);
        assertThat(sceneArmed).isEqualTo(new State(Variant.SCENE_AFTER_150, Stage.ARMED, "robot_java_bct1_a", BIG, 1_760_000_000_000_007L, 500, 30,
                DEADLINE, 1_760_000_050_000L, 1_760_000_050_000L, ""));
        assertThat(BattleCrashChecks.armedAt(Variant.BATTLE_AFTER_STORE, "robot_java_bct1_a", BIG, 1_760_000_000_000_007L, 500,
                eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 30, DEADLINE, 1_760_000_050_000L, 0)).isEqualTo(armed(Variant.BATTLE_AFTER_STORE));

        // 场景里的写法就是 write(file, armedAt(...))：armedAt 抛出时文件不出现，编排脚本等不到断点、不杀进程
        Path file = dir.resolve("battle-crash-window.state");
        for (Variant variant : new Variant[] {Variant.SCENE_AFTER_150, Variant.BATTLE_AFTER_STORE}) {
            assertThatThrownBy(() -> BattleCrashChecks.write(file, BattleCrashChecks.armedAt(variant, "robot_java_bct1_a", BIG,
                    1_760_000_000_000_007L, 500, eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN, 0, DEADLINE, 1_760_000_050_000L, 0)))
                    .isInstanceOf(RobotException.class).hasMessageContaining("不写断点").hasMessageContaining("gold_gain=0");
            assertThatThrownBy(() -> BattleCrashChecks.write(file, BattleCrashChecks.armedAt(variant, "robot_java_bct1_a", BIG,
                    1_760_000_000_000_007L, 500, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, 0, DEADLINE, 1_760_000_050_000L, 0)))
                    .isInstanceOf(RobotException.class).hasMessageContaining("不写断点").hasMessageContaining("没有打赢");
            assertThatThrownBy(() -> BattleCrashChecks.write(file, BattleCrashChecks.armedAt(variant, "robot_java_bct1_a", BIG,
                    1_760_000_000_000_007L, 500, eBattleOutcome.BATTLE_OUTCOME_DRAW, 0, DEADLINE, 1_760_000_050_000L, 0)))
                    .isInstanceOf(RobotException.class).hasMessageContaining("BATTLE_OUTCOME_DRAW");
        }
        assertThat(Files.exists(file)).isFalse();
    }

    // ------------------------------------------------------------------ scene-after-150：恰好一次

    @Test
    void 重登后150的条数_0是已持久_1是进场恢复重放_2条以上是重复() {
        assertThat(BattleCrashChecks.replayOf(0)).isEqualTo(Replay.DURABLE);
        assertThat(BattleCrashChecks.replayOf(1)).isEqualTo(Replay.RECOVERED);
        assertThat(BattleCrashChecks.replayOf(2)).isEqualTo(Replay.DUPLICATED);
        assertThat(BattleCrashChecks.replayOf(5)).isEqualTo(Replay.DUPLICATED);
        assertThat(Replay.RECOVERED.wire()).isEqualTo("recovered");
    }

    @Test
    void 金币恰好增一次_没到账与到账两次分开报() {
        assertThat(BattleCrashChecks.goldProblem(500, 30, 530)).isNull();
        assertThat(BattleCrashChecks.goldProblem(500, 30, 500)).contains("没有到账").contains("500 → 500");
        assertThat(BattleCrashChecks.goldProblem(500, 30, 560)).contains("到账两次");
        assertThat(BattleCrashChecks.goldProblem(500, 30, 545)).contains("期望 530").doesNotContain("到账两次");
        assertThat(BattleCrashChecks.goldProblem(500, 0, 500)).as("gold_gain = 0：不变就是符合").isNull();
        assertThat(BattleCrashChecks.goldProblem(500, 0, 501)).as("gold_gain = 0 时不能报成没到账 / 到账两次").contains("期望 500")
                .doesNotContain("没有到账").doesNotContain("到账两次");
    }

    // ------------------------------------------------------------------ battle-after-store：rescue

    @Test
    void 到账时刻_期限加宽限之后才算rescue_之前是别的路径_没到是丢了() {
        long graceEnd = DEADLINE + GRACE;

        assertThat(BattleCrashChecks.landingOf(graceEnd + 1_800, DEADLINE, GRACE, TOLERANCE)).isEqualTo(Landing.RESCUED);
        assertThat(BattleCrashChecks.landingOf(graceEnd, DEADLINE, GRACE, TOLERANCE)).isEqualTo(Landing.RESCUED);
        assertThat(BattleCrashChecks.landingOf(graceEnd - TOLERANCE, DEADLINE, GRACE, TOLERANCE)).as("误差余量之内仍算").isEqualTo(Landing.RESCUED);
        assertThat(BattleCrashChecks.landingOf(graceEnd - TOLERANCE - 1, DEADLINE, GRACE, TOLERANCE)).isEqualTo(Landing.EARLY);
        assertThat(BattleCrashChecks.landingOf(DEADLINE - 60_000, DEADLINE, GRACE, TOLERANCE)).as("battle 的重投在期限前就送到了").isEqualTo(Landing.EARLY);
        assertThat(BattleCrashChecks.landingOf(0, DEADLINE, GRACE, TOLERANCE)).isEqualTo(Landing.MISSING);
        assertThat(Landing.RESCUED.wire()).isEqualTo("rescued");
    }

    @Test
    void arm记下的结局_只有rescued能让verify接着往下核对_early与missing分开说明() {
        assertThat(BattleCrashChecks.observedProblem("rescued")).isNull();
        assertThat(BattleCrashChecks.observedProblem(Landing.RESCUED.wire())).isNull();

        assertThat(BattleCrashChecks.observedProblem("early")).startsWith("outcome=early：").contains("期限 + 10 s 之前").contains("没有走到 rescue");
        assertThat(BattleCrashChecks.observedProblem("missing")).startsWith("outcome=missing：").contains("没有到账");
        // 以后给 Landing 加取值时必须在这里表态：除 rescued 之外的结局一律不能让 verify 报 OK
        for (Landing landing : Landing.values()) {
            if (landing != Landing.RESCUED) {
                assertThat(BattleCrashChecks.observedProblem(landing.wire())).as(landing.wire()).isNotNull();
            }
        }
        // 手改过的文件 / scene-after-150 的结局名 / 大小写不符：都不认
        assertThat(BattleCrashChecks.observedProblem("")).startsWith("outcome=：").contains("rescued / early / missing");
        assertThat(BattleCrashChecks.observedProblem("RESCUED")).startsWith("outcome=RESCUED：");
        assertThat(BattleCrashChecks.observedProblem(Replay.DURABLE.wire())).startsWith("outcome=durable：");
        assertThat(BattleCrashChecks.observedProblem(" rescued")).isNotNull();
    }

    @Test
    void 杀进程与解封的时间窗排得开_只用robot自己的常量() {
        // 这里只核对 robot 自己几个常量之间排不排得开；它们与服务端真值（FIGHTING 判废宽限、重投间隔、归属租约……）是否一致由
        // UpstreamConstantsTest 读服务端源码钉住——本用例原来叫「与 scene 的宽限一致」，却只拿字面值 10 s 相比，服务端改了它照样通过
        // 战斗打完（至少提前 STORE_MARGIN）→ 等 HOLD_SETTLE 确认窗口 → 写断点 → 脚本杀进程、robot 探到 battle 已死 → 解封，
        // 都要在「期限 + 宽限 − RESCUE_HEADROOM」之前做完：留给「脚本杀进程 + 探死」的时间至少 5 s
        Duration window = BattleCrashScenario.STORE_MARGIN.plus(BattleSettleScenario.FIGHTING_GRACE).minus(BattleCrashScenario.RESCUE_HEADROOM)
                .minus(BattleCrashScenario.HOLD_SETTLE);
        assertThat(window).as("打完到「必须探到 battle 已死」之间的余量").isGreaterThanOrEqualTo(Duration.ofSeconds(5));
        assertThat(BattleCrashScenario.RESCUE_HEADROOM).as("解封要赶在期限 + 宽限之前：余量为正、又小于宽限本身")
                .isPositive().isLessThan(BattleSettleScenario.FIGHTING_GRACE);
        assertThat(BattleCrashScenario.STORE_DEADLINE).as("期限要长过确认窗口与余量，战斗才有时间打").isGreaterThan(BattleCrashScenario.STORE_MARGIN.multipliedBy(2));
        assertThat(BattleCrashScenario.ARRIVAL_TOLERANCE).as("rescued / early 的分界只让出一点取时误差").isLessThan(BattleCrashScenario.RESCUE_HEADROOM);
    }

    @Test
    void 窗口是否撑开_三样证据缺一不可_gold_gain为0撑不开() {
        assertThat(BattleCrashChecks.holdProblem(1, 1, false, 30)).isNull();
        assertThat(BattleCrashChecks.holdProblem(3, 2, false, 30)).as("别的局也在落库、被延后：≥ 1 即可").isNull();
        assertThat(BattleCrashChecks.holdProblem(1, 1, false, 0)).contains("gold_gain=0");
        assertThat(BattleCrashChecks.holdProblem(1, 1, true, 30)).contains("大厅已经收到 150");
        assertThat(BattleCrashChecks.holdProblem(0, 1, false, 30)).contains("stored").contains("没有落库");
        assertThat(BattleCrashChecks.holdProblem(1, 0, false, 30)).contains("deferred");
    }

    @Test
    void 下行的墙钟时刻_由到达时的nanoTime换算() {
        long nowNanos = 5_000_000_000L;
        long nowMillis = 1_760_000_123_000L;

        assertThat(BattleCrashChecks.wallClockMillis(nowNanos, nowNanos, nowMillis)).isEqualTo(nowMillis);
        assertThat(BattleCrashChecks.wallClockMillis(nowNanos - 2_500_000_000L, nowNanos, nowMillis)).as("2.5 s 之前到的").isEqualTo(nowMillis - 2_500);
    }

    // ------------------------------------------------------------------ 汇总行

    @Test
    void 汇总行_成功带battle_id与结局_失败带步骤() {
        assertThat(BattleCrashChecks.summaryLine(true, Variant.SCENE_AFTER_150, Phase.VERIFY, BIG, "recovered", List.of()))
                .isEqualTo("BATTLE_CRASH_OK variant=scene-after-150 phase=verify battle_id=9223372036854775809 outcome=recovered");
        assertThat(BattleCrashChecks.summaryLine(true, Variant.SCENE_AFTER_150, Phase.ARM, 7, "", List.of()))
                .isEqualTo("BATTLE_CRASH_OK variant=scene-after-150 phase=arm battle_id=7 outcome=-");
        assertThat(BattleCrashChecks.summaryLine(false, Variant.BATTLE_AFTER_STORE, Phase.ARM, 7, "missing", List.of("rescue")))
                .isEqualTo("BATTLE_CRASH_FAIL variant=battle-after-store phase=arm step=rescue");
        assertThat(BattleCrashChecks.summaryLine(false, Variant.BATTLE_AFTER_STORE, Phase.ARM, 7, "", List.of("hold", "流程")))
                .isEqualTo("BATTLE_CRASH_FAIL variant=battle-after-store phase=arm step=hold,流程");
        assertThat(BattleCrashChecks.summaryLine(true, Variant.BATTLE_AFTER_STORE, Phase.VERIFY, 7, "rescued", List.of("released")))
                .as("登记了失败步骤就不是 OK").startsWith("BATTLE_CRASH_FAIL");
        assertThat(BattleCrashChecks.summaryLine(false, Variant.BATTLE_AFTER_STORE, Phase.VERIFY, 7, "", List.of())).endsWith("step=?");
    }

    // ------------------------------------------------------------------ 前提：同步来的配置表

    @Test
    void 同步来的表里Dungeon1的怪有金币奖励_封金币才撑得开battle_after_store的窗口() {
        Path dir = Files.isDirectory(Path.of("../config-data/tables")) ? Path.of("../config-data/tables") : Path.of("config-data/tables");
        ConfigTables tables = ConfigTables.load(dir);
        DungeonTable dungeon = tables.dungeon().find(BattleSettleScenario.DUNGEON_1).orElseThrow();

        long gold = 0;
        for (int monsterId : dungeon.getMonsterList()) {
            if (monsterId != 0) {
                gold += tables.monster().find(monsterId).orElseThrow().getGoldReward();
            }
        }

        // gold_gain = 击败的怪的 gold_reward 之和（TurnBattleEngine）；为 0 时 scene 的金币先行那一步不会失败，结算不会被延后
        assertThat(dungeon.getMonsterList()).contains(BattleSettleScenario.MONSTER_1);
        assertThat(gold).isPositive();
    }
}
