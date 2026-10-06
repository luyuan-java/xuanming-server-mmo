package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.robot.RobotOptions;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** rollback 场景里不连服务端就能钉住的部分：子命令、账号名、用法说明。 */
class RollbackScenarioTest {

    @Test
    void 子命令与账号名() throws Exception {
        RobotOptions options = RobotOptions.parse(List.of("rollback", "--run-tag", "x1"),
                Map.of(RobotOptions.PASSWORD_ENV, "p"), 0);
        assertThat(options.scenario()).isEqualTo(RobotOptions.Scenario.ROLLBACK);
        assertThat(RollbackScenario.accountName(options.accountPrefix(), options.runTag())).isEqualTo("robot_java_rbx1");
        assertThat(RobotOptions.usage()).contains("|rollback|", "  rollback  ");
    }
}
