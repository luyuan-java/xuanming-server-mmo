package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.match.QueueState;
import com.game.robot.RobotOptions;
import com.game.robot.scenario.OrderingChecks.Reorder;
import com.game.robot.scenario.OrderingChecks.Verdict;
import com.game.table.GuildErrorTip;
import com.game.table.LoginErrorTip;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** ordering-smoke 里不连服务端就能钉住的部分：每组一轮的判定（与 mmorpg 的 switch 逐条对应）、结果行、账号与子命令、节拍与配额的取值。 */
class OrderingChecksTest {

    private static final int NOT_FOUND = OrderingChecks.TIP_APPLICATION_NOT_FOUND;
    private static final int BUSY = OrderingChecks.TIP_GUILD_BUSY_RETRY;

    @Test
    void O3_加入后取消不在队列里是按序_仍排队是乱序_被凑走不是顺序问题() {
        assertThat(OrderingChecks.queue(QueueState.QUEUE_STATE_NOT_QUEUED)).isEqualTo(Verdict.IN_ORDER);
        assertThat(OrderingChecks.queue(QueueState.QUEUE_STATE_UNSPECIFIED)).isEqualTo(Verdict.IN_ORDER);
        assertThat(OrderingChecks.queue(QueueState.QUEUE_STATE_QUEUED)).isEqualTo(Verdict.REORDERED);
        for (QueueState state : List.of(QueueState.QUEUE_STATE_MATCHED, QueueState.QUEUE_STATE_READY, QueueState.QUEUE_STATE_ENTERING,
                QueueState.UNRECOGNIZED)) {
            assertThat(OrderingChecks.queue(state)).as("%s", state).isEqualTo(Verdict.INCONSISTENT);
        }
    }

    @Test
    void O5_申请只能受理_撤回可受理或申请不存在_两条都可能撞锁回忙() {
        assertThat(OrderingChecks.guildTipsProblem(0, 0)).isNull();
        assertThat(OrderingChecks.guildTipsProblem(0, NOT_FOUND)).isNull();
        assertThat(OrderingChecks.guildTipsProblem(BUSY, 0)).isNull();
        assertThat(OrderingChecks.guildTipsProblem(0, BUSY)).isNull();
        assertThat(OrderingChecks.guildTipsProblem(GuildErrorTip.guild_error.kGuildNotFound_VALUE, 0))
                .startsWith("ApplyJoinGuild 业务拒绝").contains("同一归属区");
        assertThat(OrderingChecks.guildTipsProblem(NOT_FOUND, 0)).as("申请回「申请不存在」不合法").startsWith("ApplyJoinGuild");
        assertThat(OrderingChecks.guildTipsProblem(0, OrderingChecks.TIP_NOT_IN_GUILD)).startsWith("CancelGuildApplication 业务拒绝");
        assertThat(OrderingChecks.guildTipsProblem(OrderingChecks.TIP_RATE_LIMITED, 0)).as("配额码带可读原因").contains("每分钟申请配额");

        assertThat(OrderingChecks.guildConcurrent(BUSY, 0)).isTrue();
        assertThat(OrderingChecks.guildConcurrent(0, BUSY)).isTrue();
        assertThat(OrderingChecks.guildConcurrent(0, NOT_FOUND)).isFalse();
    }

    @Test
    void O5_撤回受理且不在名单是按序_撤回申请不存在且仍在名单是颠倒_其余对不上() {
        assertThat(OrderingChecks.guild(0, false)).isEqualTo(Verdict.IN_ORDER);
        assertThat(OrderingChecks.guild(NOT_FOUND, true)).isEqualTo(Verdict.REORDERED);
        assertThat(OrderingChecks.guild(0, true)).as("撤回受理却还在名单里").isEqualTo(Verdict.INCONSISTENT);
        assertThat(OrderingChecks.guild(NOT_FOUND, false)).as("撤回说申请不存在、名单里也没有").isEqualTo(Verdict.INCONSISTENT);
    }

    @Test
    void O6_互相看不到是按序_互为好友是颠倒_单边残留对不上() {
        assertThat(OrderingChecks.friend(false, false)).isEqualTo(Verdict.IN_ORDER);
        assertThat(OrderingChecks.friend(true, true)).isEqualTo(Verdict.REORDERED);
        assertThat(OrderingChecks.friend(true, false)).isEqualTo(Verdict.INCONSISTENT);
        assertThat(OrderingChecks.friend(false, true)).isEqualTo(Verdict.INCONSISTENT);
    }

    @Test
    void O7_进游戏受理或在途是按序_未登录是颠倒_其余是前置问题() {
        assertThat(OrderingChecks.login(0)).isEqualTo(Verdict.IN_ORDER);
        assertThat(OrderingChecks.login(LoginErrorTip.login_error.kLoginInProgress_VALUE)).as("2005 只在会话已登录之后回").isEqualTo(Verdict.IN_ORDER);
        assertThat(OrderingChecks.login(LoginErrorTip.login_error.kLoginSessionNotFound_VALUE)).isEqualTo(Verdict.REORDERED);
        assertThat(OrderingChecks.login(LoginErrorTip.login_error.kLoginEnterGameGuid_VALUE)).isEqualTo(Verdict.INCONSISTENT);
        assertThat(OrderingChecks.login(LoginErrorTip.login_error.kLoginAccountNotFound_VALUE)).isEqualTo(Verdict.INCONSISTENT);
    }

    @Test
    void 结果行_乱序按执行顺序汇总成一行_通过行的号按无符号() {
        String line = OrderingChecks.reorderLine(List.of(new Reorder("O5-order", "2/5 轮乱序"), new Reorder("O3-order", "1/20 轮乱序")));
        assertThat(line).isEqualTo("ORDERING_SMOKE_FAIL step=O5-order,O3-order reason=O5-order: 2/5 轮乱序；O3-order: 1/20 轮乱序");
        assertThatThrownBy(() -> OrderingChecks.reorderLine(List.of())).isInstanceOf(IllegalArgumentException.class);

        assertThat(OrderingChecks.failLine("O6-status", "单边\n残留")).isEqualTo("ORDERING_SMOKE_FAIL step=O6-status reason=单边 残留");
        assertThat(OrderingChecks.okLine(-1L, 20)).isEqualTo("ORDERING_SMOKE_OK player_id=18446744073709551615 rounds=20");
        assertThat(OrderingChecks.reorderReason(2, 5, List.of(1, 4), "执行顺序被颠倒")).startsWith("2/5 轮执行顺序被颠倒，乱序轮次=[1, 4]。");
    }

    @Test
    void ordering_smoke子命令_账号带od标签_后缀至多两个字符() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("ordering-smoke", "--run-tag", "x1"), Map.of(RobotOptions.PASSWORD_ENV, "p"), 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.ORDERING_SMOKE);
        assertThat(OrderingScenario.accountName(options.accountPrefix(), options.runTag(), "gl")).isEqualTo("robot_java_odx1_gl");
        assertThat(OrderingScenario.accountName(options.accountPrefix(), options.runTag(), "q")).isEqualTo("robot_java_odx1_q");
        assertThat(RobotOptions.usage()).contains("|ordering-smoke|", "  ordering-smoke ", "ORDERING_SMOKE_OK");
    }

    @Test
    void 节拍与轮数_同号窗口宽于gate的1秒且不超过最严的3条_申请配额容得下一分钟两次运行() {
        assertThat(OrderingScenario.SAME_ID_WINDOW).isGreaterThan(Duration.ofSeconds(1));
        assertThat(OrderingScenario.SAME_ID_MAX_IN_WINDOW).isLessThanOrEqualTo(3);
        assertThat(OrderingScenario.ROUND_SPACING).isGreaterThan(Duration.ofSeconds(1));
        // 帮会 / 好友申请每人每分钟 10 次：每轮 1 次申请，同一 --run-tag 一分钟内连跑两次不超额
        assertThat(OrderingScenario.GUILD_ROUNDS * 2).isLessThanOrEqualTo(10);
        assertThat(OrderingScenario.FRIEND_ROUNDS * 2).isLessThanOrEqualTo(10);
    }
}
