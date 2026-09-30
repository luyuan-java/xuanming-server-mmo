package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.robot.flow.PlayerFlow;
import com.game.robot.flow.Timings;
import org.junit.jupiter.api.Test;

class CheckReportTest {

    @Test
    void 一条检查都没有不算通过() {
        CheckReport report = new CheckReport();
        assertThat(report.passed()).isFalse();
        assertThat(report.render("空")).contains("结论：失败（没有完成任何检查）");
    }

    @Test
    void 全部通过才通过_汇总逐条列出依据与观察记录() {
        CheckReport report = new CheckReport();
        report.pass("A 登录进场", "player_id=1", "robot 契约 §4");
        report.note("跳跃被纠偏");
        assertThat(report.passed()).isTrue();
        String text = report.render("movement");
        assertThat(text).contains("== movement ==", "[通过] A 登录进场：player_id=1  〔robot 契约 §4〕",
                "观察记录", "跳跃被纠偏", "结论：通过（1 项检查全部通过）");

        report.fail("停下后静默", "又收到 2 条", "movement §6.1");
        assertThat(report.passed()).isFalse();
        assertThat(report.failures()).isEqualTo(1);
        assertThat(report.render("movement")).contains("[失败] 停下后静默：又收到 2 条", "结论：失败（1 / 2 项未通过）");
    }

    @Test
    void 冒烟每账号一行_没走到的步骤记横线() {
        Timings timings = new Timings();
        timings.recordMillis(PlayerFlow.STEP_ASSIGN, 12);
        timings.recordMillis(PlayerFlow.STEP_LOGIN, 30);
        String row = SmokeScenario.formatRow(new SmokeScenario.AccountResult("robot_java_0001", 1, 2, 1, 3, false,
                timings, null));
        assertThat(row).startsWith("robot_java_0001  通过").contains("分配gate=12", "登录=30", "建角=-", "总计=-");
        String failed = SmokeScenario.formatRow(new SmokeScenario.AccountResult("robot_java_0002", 0, 0, 0, 0, false,
                new Timings(), "登录超时"));
        assertThat(failed).contains("失败");
    }
}
