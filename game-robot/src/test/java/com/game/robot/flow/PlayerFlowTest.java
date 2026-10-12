package com.game.robot.flow;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.TipInfoMessage;
import com.game.proto.login.LoginResponse;
import com.game.robot.client.RobotException;
import org.junit.jupiter.api.Test;

/** 登录阶段按 error_message 的「存在性」判失败（robot 契约 §3.4 第 3 条）。 */
class PlayerFlowTest {

    @Test
    void 不带_error_message_即成功() {
        LoginResponse ok = LoginResponse.getDefaultInstance();
        assertThatCode(() -> PlayerFlow.requireNoError("登录（48）", ok.hasErrorMessage(), ok.getErrorMessage()))
                .doesNotThrowAnyException();
    }

    @Test
    void 带了_id_为_0_的空_tip_也是违约() {
        LoginResponse emptyTip = LoginResponse.newBuilder().setErrorMessage(TipInfoMessage.getDefaultInstance()).build();
        assertThatThrownBy(() -> PlayerFlow.requireNoError("登录（48）", emptyTip.hasErrorMessage(), emptyTip.getErrorMessage()))
                .isInstanceOf(RobotException.class)
                .hasMessageContaining("id=0")
                .hasMessageContaining("§3.4");
    }

    @Test
    void 业务拒绝带出_tip_与参数() {
        LoginResponse rejected = LoginResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(2000).addParameters("x")).build();
        assertThatThrownBy(() -> PlayerFlow.requireNoError("登录（48）", rejected.hasErrorMessage(), rejected.getErrorMessage()))
                .hasMessageContaining("登录（48） 被拒")
                .hasMessageContaining("2000")
                .hasMessageContaining("[x]");
    }
}
