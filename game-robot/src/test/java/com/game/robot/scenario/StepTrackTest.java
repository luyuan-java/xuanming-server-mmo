package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 步骤记账与结果行：通过 / 失败两种形状、失败归到哪一步、压成一行。 */
class StepTrackTest {

    @Test
    void 全部通过_OK行带字段_没有字段时只有标记() {
        CheckReport report = new CheckReport();
        StepTrack steps = new StepTrack("BATTLE_SMOKE");
        steps.step("1-login", report);
        report.pass("第 1 步", "", "");

        assertThat(steps.line(report, "battle_id=7 a_turns=2")).isEqualTo("BATTLE_SMOKE_OK battle_id=7 a_turns=2");
        assertThat(steps.line(report, "")).isEqualTo("BATTLE_SMOKE_OK");
    }

    @Test
    void 失败行取第一条失败的检查_步骤是它所在的那一步_不是最后进入的那一步() {
        CheckReport report = new CheckReport();
        StepTrack steps = new StepTrack("TEAM_SMOKE");
        steps.step("s1-create", report);
        report.pass("S1 建队", "", "");
        steps.step("s7-team-battle", report);
        report.pass("S7 受理", "", "");
        report.fail("S7 B 收到 MATCH_STARTED", "10 s 内没收到", "ref");
        steps.step("s8-member-in-battle", report);
        report.fail("S8 4025", "tip=0", "ref");
        steps.step("s9-disband", report);
        report.pass("S9 解散", "", "");

        assertThat(steps.line(report, "team_id=1")).isEqualTo("TEAM_SMOKE_FAIL step=s7-team-battle reason=S7 B 收到 MATCH_STARTED：10 s 内没收到");
        assertThat(steps.stepOf(0)).isEqualTo("s1-create");
        assertThat(steps.stepOf(1)).isEqualTo("s7-team-battle");
        assertThat(steps.stepOf(2)).isEqualTo("s7-team-battle");
        assertThat(steps.stepOf(3)).isEqualTo("s8-member-in-battle");
        assertThat(steps.stepOf(4)).isEqualTo("s9-disband");
        assertThat(steps.current()).isEqualTo("s9-disband");
    }

    @Test
    void 一步里没有任何检查时_下一步的第一条检查归下一步() {
        CheckReport report = new CheckReport();
        StepTrack steps = new StepTrack("X");
        steps.step("a", report);
        steps.step("b", report);
        report.fail("坏了", "", "");

        assertThat(steps.line(report, "")).isEqualTo("X_FAIL step=b reason=坏了");
    }

    @Test
    void 还没进入任何一步就失败是start_一条检查都没有是none() {
        CheckReport early = new CheckReport();
        StepTrack steps = new StepTrack("MATCH_5V5");
        assertThat(steps.current()).isEqualTo(StepTrack.BEFORE_FIRST_STEP);
        early.fail("流程中断", "解析消息号失败", "");
        assertThat(steps.line(early, "x=1")).isEqualTo("MATCH_5V5_FAIL step=start reason=流程中断：解析消息号失败");

        assertThat(new StepTrack("MATCH_5V5").line(new CheckReport(), "x=1")).isEqualTo("MATCH_5V5_FAIL step=none reason=没有完成任何检查");
    }

    @Test
    void reason压成一行_换行与连续空白折成一个空格_超长截断() {
        CheckReport report = new CheckReport();
        StepTrack steps = new StepTrack("MATCH_ACTIVITY");
        steps.step("4-start", report);
        report.fail("受理", "第一行\n\t第二行   第三行", "");
        assertThat(steps.line(report, "")).isEqualTo("MATCH_ACTIVITY_FAIL step=4-start reason=受理：第一行 第二行 第三行").doesNotContain("\n");

        String longText = "长".repeat(StepTrack.MAX_REASON_CHARS + 50);
        assertThat(StepTrack.oneLine(longText)).hasSize(StepTrack.MAX_REASON_CHARS + 1).endsWith("…");
        assertThat(StepTrack.oneLine("  短  ")).isEqualTo("短");
    }

    @Test
    void 标记与步骤号只能是ASCII的约定字符_脚本按子串匹配() {
        assertThatThrownBy(() -> new StepTrack("battle_smoke")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StepTrack("战斗")).isInstanceOf(IllegalArgumentException.class);
        StepTrack steps = new StepTrack("OK_1");
        assertThatThrownBy(() -> steps.step("第 1 步", new CheckReport())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> steps.step("S7", new CheckReport())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> steps.step("", new CheckReport())).isInstanceOf(IllegalArgumentException.class);
    }
}
