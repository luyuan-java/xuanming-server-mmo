package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class AssignGateClientTest {

    private static String b64(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 请求体与_Go_robot_首次请求逐字节相同() {
        assertThat(AssignGateClient.requestBody(1)).isEqualTo("{\"zone_id\":1}");
    }

    @Test
    void 解析准入应答_snake_case_与标准_Base64() throws Exception {
        String body = "{\"code\":0,\"gate_ip\":\"127.0.0.1\",\"gate_port\":11000,\"token_payload\":\"" + b64("payload-bytes")
                + "\",\"token_signature\":\"" + b64("sig") + "\",\"token_deadline\":1760000000}";
        GateAssignment gate = AssignGateClient.parse(200, body);
        assertThat(gate.gateIp()).isEqualTo("127.0.0.1");
        assertThat(gate.gatePort()).isEqualTo(11000);
        assertThat(new String(gate.tokenPayload(), StandardCharsets.UTF_8)).isEqualTo("payload-bytes");
        assertThat(new String(gate.tokenSignature(), StandardCharsets.UTF_8)).isEqualTo("sig");
        assertThat(gate.tokenDeadline()).isEqualTo(1_760_000_000L);
        assertThat(gate.toString()).doesNotContain("payload-bytes");
    }

    @Test
    void 令牌缺席时为空数组_调用方据此跳过握手() throws Exception {
        GateAssignment gate = AssignGateClient.parse(200, "{\"code\":0,\"gate_ip\":\"h\",\"gate_port\":1}");
        assertThat(gate.tokenPayload()).isEmpty();
        assertThat(gate.tokenSignature()).isEmpty();
        assertThat(gate.tokenDeadline()).isZero();
    }

    @Test
    void 业务拒绝带出_code_与_error() {
        assertThatThrownBy(() -> AssignGateClient.parse(200,
                "{\"code\":500,\"gate_port\":0,\"token_deadline\":0,\"error\":\"no_gate_available\"}"))
                .isInstanceOf(RobotException.class)
                .hasMessageContaining("code=500")
                .hasMessageContaining("no_gate_available");
    }

    @Test
    void 非_200_一律失败() {
        assertThatThrownBy(() -> AssignGateClient.parse(400, "{\"code\":0}"))
                .isInstanceOf(RobotException.class)
                .hasMessageContaining("HTTP 400");
    }

    @Test
    void 形状不符的应答() {
        assertThatThrownBy(() -> AssignGateClient.parse(200, "not json")).hasMessageContaining("不是合法 JSON");
        assertThatThrownBy(() -> AssignGateClient.parse(200, "[1]")).hasMessageContaining("不是 JSON 对象");
        assertThatThrownBy(() -> AssignGateClient.parse(200, "{\"gate_ip\":\"h\"}")).hasMessageContaining("缺整数 code");
        assertThatThrownBy(() -> AssignGateClient.parse(200, "{\"code\":0,\"gate_port\":1}")).hasMessageContaining("gate_ip");
        // 驼峰键匹配不上（契约：键名下划线必须一致）。
        assertThatThrownBy(() -> AssignGateClient.parse(200, "{\"code\":0,\"gateIp\":\"h\",\"gatePort\":1}"))
                .hasMessageContaining("gate_ip");
        // 端口是字符串会让 Go robot 解析失败，这里同样拒绝。
        assertThatThrownBy(() -> AssignGateClient.parse(200, "{\"code\":0,\"gate_ip\":\"h\",\"gate_port\":\"11000\"}"))
                .hasMessageContaining("gate_port");
        assertThatThrownBy(() -> AssignGateClient.parse(200, "{\"code\":0,\"gate_ip\":\"h\",\"gate_port\":70000}"))
                .hasMessageContaining("gate_port");
        assertThatThrownBy(() -> AssignGateClient.parse(200,
                "{\"code\":0,\"gate_ip\":\"h\",\"gate_port\":1,\"token_payload\":\"%%%\"}"))
                .hasMessageContaining("不是标准 Base64");
        assertThatThrownBy(() -> AssignGateClient.parse(200,
                "{\"code\":0,\"gate_ip\":\"h\",\"gate_port\":1,\"token_payload\":[1,2]}"))
                .hasMessageContaining("token_payload");
    }
}
