package com.game.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.api.proto.GateNodeInfo;
import com.game.common.token.GateTokens;
import com.game.gateway.assign.AssignGateController;
import com.game.gateway.gate.GateSource;
import com.game.gateway.serverlist.ServerListController;
import com.game.proto.GateTokenPayload;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

/**
 * HTTP 契约测试：按 Go robot 的解析方式核对 JSON 键名（snake_case）、类型、业务码，以及签出的令牌能被 gate 侧验过。
 * 走真实装配（{@link GatewayConfiguration} + application.yaml 的 Jackson 配置），只把 gate 目录换成替身，不需要 Redis。
 */
@WebMvcTest(controllers = {AssignGateController.class, ServerListController.class})
@Import(GatewayConfiguration.class)
@TestPropertySource(properties = {
        GatewayConfiguration.TOKEN_SECRET_ENV + "=" + GatewayHttpApiTest.SECRET,
        "xm.gateway.zones[0].zone-id=1",
        "xm.gateway.zones[0].name=一区",
        "xm.gateway.zones[0].status=OPEN",
        "xm.gateway.zones[0].recommended=true",
        "xm.gateway.zones[0].maintenance-msg=开放区不该下发公告",
        "xm.gateway.zones[1].zone-id=2",
        "xm.gateway.zones[1].name=二区",
        "xm.gateway.zones[1].status=MAINTENANCE",
        "xm.gateway.zones[1].maintenance-msg=停服维护中",
        "xm.gateway.zones[2].zone-id=3",
        "xm.gateway.zones[2].name=三区",
        "xm.gateway.zones[2].status=CLOSED",
})
class GatewayHttpApiTest {

    static final String SECRET = "http-test-secret";

    private static final GateTokens TOKENS = GateTokens.ofUtf8(SECRET);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Go {@code base64.StdEncoding}：标准字母表、必须带填充。 */
    private static final String STD_BASE64 = "^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private GateSource gateSource;

    private static GateNodeInfo gate(int nodeId, int playerCount, boolean draining) {
        return GateNodeInfo.newBuilder()
                .setZoneId(1)
                .setNodeId(nodeId)
                .setInstanceId("inst-" + nodeId)
                .setClientHost("10.0.0." + nodeId)
                .setClientPort(11000 + nodeId)
                .setPlayerCount(playerCount)
                .setDraining(draining)
                .build();
    }

    private JsonNode call(RequestBuilder request) throws Exception {
        String body = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    private JsonNode assignGate(String body) throws Exception {
        return call(post("/api/assign-gate").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static Set<String> keys(JsonNode node) {
        return node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
    }

    private static byte[] stdBase64(JsonNode node) {
        assertThat(node.isTextual()).as("[]byte 字段必须是 JSON 字符串").isTrue();
        assertThat(node.asText()).matches(STD_BASE64);
        return Base64.getDecoder().decode(node.asText());
    }

    // ---------------------------------------------------------------- assign-gate：准入

    @Test
    void 准入_键名类型与robot解析结构一致_令牌可被选中的gate验过() throws Exception {
        when(gateSource.listGates(1)).thenReturn(List.of(gate(4, 3, false)));
        long before = Instant.now().getEpochSecond();

        JsonNode resp = assignGate("{\"zone_id\":1}");

        long after = Instant.now().getEpochSecond();
        assertThat(keys(resp)).containsExactlyInAnyOrder(
                "code", "gate_ip", "gate_port", "token_payload", "token_signature", "token_deadline");
        assertThat(resp.get("code").isInt()).isTrue();
        assertThat(resp.get("code").intValue()).isZero();
        assertThat(resp.get("gate_ip").isTextual()).isTrue();
        assertThat(resp.get("gate_ip").asText()).isEqualTo("10.0.0.4");
        assertThat(resp.get("gate_port").isInt()).isTrue();
        assertThat(resp.get("gate_port").intValue()).isEqualTo(11004);
        assertThat(resp.get("token_deadline").isIntegralNumber()).isTrue();
        long deadline = resp.get("token_deadline").longValue();
        assertThat(deadline).isBetween(before + 600, after + 600);

        byte[] payload = stdBase64(resp.get("token_payload"));
        byte[] signature = stdBase64(resp.get("token_signature"));
        assertThat(new String(signature, StandardCharsets.US_ASCII)).matches("[0-9a-f]{64}");

        GateTokenPayload parsed = GateTokenPayload.parseFrom(payload);
        assertThat(parsed.getGateNodeId()).isEqualTo(4);
        assertThat(parsed.getZoneId()).isEqualTo(1);
        assertThat(parsed.getTargetZoneId()).isEqualTo(1);
        assertThat(parsed.getExpireTimestamp()).isEqualTo(deadline);
        assertThat(parsed.getHmacSessionKey().size()).isEqualTo(32);

        GateTokens.Verdict verdict = TOKENS.verify(ByteString.copyFrom(payload), ByteString.copyFrom(signature), 4, 1, after);
        assertThat(verdict.ok()).isTrue();
    }

    @Test
    void 按人数再按节点号挑gate_排空的不选() throws Exception {
        when(gateSource.listGates(1)).thenReturn(List.of(
                gate(5, 3, false), gate(2, 1, true), gate(4, 1, false), gate(3, 1, false)));

        JsonNode resp = assignGate("{\"zone_id\":1}");

        assertThat(resp.get("code").intValue()).isZero();
        assertThat(resp.get("gate_ip").asText()).isEqualTo("10.0.0.3");
        assertThat(resp.get("gate_port").intValue()).isEqualTo(11003);
        ByteString payload = ByteString.copyFrom(stdBase64(resp.get("token_payload")));
        ByteString signature = ByteString.copyFrom(stdBase64(resp.get("token_signature")));
        long now = Instant.now().getEpochSecond();
        assertThat(TOKENS.verify(payload, signature, 3, 1, now).ok()).isTrue();
        assertThat(TOKENS.verify(payload, signature, 4, 1, now).failure()).isEqualTo(GateTokens.Failure.WRONG_GATE);
    }

    @Test
    void 全部排空时仍然分配() throws Exception {
        when(gateSource.listGates(1)).thenReturn(List.of(gate(2, 5, true), gate(1, 9, true)));

        JsonNode resp = assignGate("{\"zone_id\":1}");

        assertThat(resp.get("code").intValue()).isZero();
        assertThat(resp.get("gate_ip").asText()).isEqualTo("10.0.0.2");
    }

    @Test
    void 可选字段与未知字段不影响准入() throws Exception {
        when(gateSource.listGates(1)).thenReturn(List.of(gate(1, 0, false)));

        JsonNode resp = assignGate("{\"zone_id\":1,\"account\":\"robot_0001\",\"device_id\":\"dev\","
                + "\"queue_token\":\"q\",\"unknown\":{\"x\":1}}");

        assertThat(resp.get("code").intValue()).isZero();
    }

    // ---------------------------------------------------------------- assign-gate：拒绝

    private static void assertRejected(JsonNode resp, int code, String error) {
        assertThat(keys(resp)).containsExactlyInAnyOrder("code", "gate_port", "token_deadline", "error");
        assertThat(resp.get("code").isInt()).isTrue();
        assertThat(resp.get("code").intValue()).isEqualTo(code);
        assertThat(resp.get("error").asText()).isEqualTo(error);
        assertThat(resp.get("gate_port").intValue()).isZero();
        assertThat(resp.get("token_deadline").longValue()).isZero();
    }

    @Test
    void 未知区服_404_且不读gate目录() throws Exception {
        assertRejected(assignGate("{\"zone_id\":99}"), 404, "zone_not_found");
        assertRejected(assignGate("{\"zone_id\":0}"), 404, "zone_not_found");
        assertRejected(assignGate("{}"), 404, "zone_not_found");
        verifyNoInteractions(gateSource);
    }

    @Test
    void 维护与关闭_503_文案与mmorpg一致_且不读gate目录() throws Exception {
        assertRejected(assignGate("{\"zone_id\":2}"), 503, "zone_maintenance");
        assertRejected(assignGate("{\"zone_id\":3}"), 503, "zone_closed");
        verifyNoInteractions(gateSource);
    }

    @Test
    void 没有可用gate_500() throws Exception {
        when(gateSource.listGates(1)).thenReturn(List.of());
        assertRejected(assignGate("{\"zone_id\":1}"), 500, "no_gate_available");

        GateNodeInfo noEndpoint = gate(1, 0, false).toBuilder().setClientHost("").build();
        when(gateSource.listGates(1)).thenReturn(List.of(noEndpoint));
        assertRejected(assignGate("{\"zone_id\":1}"), 500, "no_gate_available");
    }

    @Test
    void gate目录不可达_500_不签令牌() throws Exception {
        when(gateSource.listGates(anyInt())).thenThrow(new IllegalStateException("redis down"));

        assertRejected(assignGate("{\"zone_id\":1}"), 500, "gate_directory_unavailable");
    }

    @Test
    void 请求体不合法_仍回HTTP200与业务码() throws Exception {
        assertRejected(assignGate("{"), 400, "bad_request");
        assertRejected(assignGate("{\"zone_id\":\"abc\"}"), 400, "bad_request");
        assertRejected(call(post("/api/assign-gate").contentType(MediaType.TEXT_PLAIN).content("zone_id=1")),
                400, "bad_request");
        verifyNoInteractions(gateSource);
    }

    // ---------------------------------------------------------------- server-list

    @Test
    void 区服列表_键名类型与顺序() throws Exception {
        JsonNode resp = call(get("/api/server-list"));

        assertThat(keys(resp)).containsExactly("zones");
        JsonNode zones = resp.get("zones");
        assertThat(zones.isArray()).isTrue();
        assertThat(zones.size()).isEqualTo(3);

        JsonNode open = zones.get(0);
        assertThat(keys(open)).containsExactlyInAnyOrder("zone_id", "name", "status", "is_new", "recommended");
        assertThat(open.get("zone_id").isInt()).isTrue();
        assertThat(open.get("zone_id").intValue()).isEqualTo(1);
        assertThat(open.get("name").asText()).isEqualTo("一区");
        assertThat(open.get("status").asText()).isEqualTo("OPEN");
        assertThat(open.get("is_new").isBoolean()).isTrue();
        assertThat(open.get("is_new").booleanValue()).isFalse();
        assertThat(open.get("recommended").isBoolean()).isTrue();
        assertThat(open.get("recommended").booleanValue()).isTrue();

        JsonNode maintenance = zones.get(1);
        assertThat(keys(maintenance)).containsExactlyInAnyOrder(
                "zone_id", "name", "status", "maintenance_msg", "is_new", "recommended");
        assertThat(maintenance.get("zone_id").intValue()).isEqualTo(2);
        assertThat(maintenance.get("status").asText()).isEqualTo("MAINTENANCE");
        assertThat(maintenance.get("maintenance_msg").asText()).isEqualTo("停服维护中");
        assertThat(maintenance.get("recommended").booleanValue()).isFalse();

        JsonNode closed = zones.get(2);
        assertThat(keys(closed)).containsExactlyInAnyOrder("zone_id", "name", "status", "is_new", "recommended");
        assertThat(closed.get("zone_id").intValue()).isEqualTo(3);
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
    }
}
