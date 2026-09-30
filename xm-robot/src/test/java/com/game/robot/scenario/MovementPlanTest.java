package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.robot.scenario.MoveAssertions.InputKind;
import com.game.robot.scenario.MovementScenario.PlannedInput;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 行走计划的前提：66 能对回输入、位移不触发校验、A 始终留在 B 的视野里。 */
class MovementPlanTest {

    private static final Vec3 SPAWN = new Vec3(180, 200, 0);
    private final List<PlannedInput> plan = MovementScenario.planWalk(SPAWN);

    @Test
    void 形状是_Start_三条_Sync_再_Stop() {
        assertThat(plan).extracting(PlannedInput::kind)
                .containsExactly(InputKind.START, InputKind.SYNC, InputKind.SYNC, InputKind.SYNC, InputKind.STOP);
        assertThat(plan.get(0).location()).isEqualTo(SPAWN);
        assertThat(plan.get(plan.size() - 1).velocity()).isEqualTo(Vec3.ZERO);
    }

    @Test
    void 截断后的期望速度两两不同且非零_matchInputs_的前提() {
        Set<Vec3> expected = new HashSet<>();
        for (PlannedInput input : plan.subList(0, plan.size() - 1)) {
            Vec3 v = MoveAssertions.clampSpeed(input.velocity());
            assertThat(v.isZero()).isFalse();
            assertThat(expected.add(v)).as("重复的期望速度 %s", v).isTrue();
        }
        assertThat(plan).anySatisfy(input -> assertThat(input.velocity().length())
                .as("至少一条超速输入，用来检查截断").isGreaterThan(MoveAssertions.MAX_TRUSTED_SPEED));
    }

    @Test
    void 上报位置与速度自洽_单步位移不超过合法上限() {
        double stepSeconds = MovementScenario.STEP.toMillis() / 1000.0;
        for (int i = 1; i < plan.size(); i++) {
            double moved = plan.get(i).location().distance(plan.get(i - 1).location());
            assertThat(moved).isLessThanOrEqualTo(MoveAssertions.MAX_TRUSTED_SPEED * stepSeconds);
            assertThat(plan.get(i).location().y()).isEqualTo(SPAWN.y());
            assertThat(plan.get(i).location().z()).isEqualTo(SPAWN.z());
        }
    }

    @Test
    void 连同外推余量也留在视野半径内() {
        double reach = plan.get(plan.size() - 1).location().distance(SPAWN) + MovementScenario.PATH_OVERSHOOT;
        assertThat(reach).isLessThan(MovementScenario.VIEW_RADIUS);
    }

    @Test
    void 跳跃距离远超合法位移() {
        double legal = MoveAssertions.MAX_TRUSTED_SPEED * MovementScenario.STEP.toMillis() / 1000.0;
        assertThat(MovementScenario.JUMP_DISTANCE).isGreaterThan(legal * 10);
    }

    @Test
    void 账号名() {
        assertThat(MovementScenario.accountName("robot_java_", "abc1", "a")).isEqualTo("robot_java_mvabc1_a");
        assertThat(SmokeScenario.accountName("robot_java_", 1)).isEqualTo("robot_java_0001");
    }
}
