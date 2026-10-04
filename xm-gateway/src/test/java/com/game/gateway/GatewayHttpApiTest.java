package com.game.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.api.proto.GateNodeInfo;
import com.game.common.token.GateTokens;
import com.game.api.AccountLoginService;
import com.game.gateway.assign.AssignGateController;
import com.game.gateway.login.LoginController;
import com.game.proto.AccountSimplePlayer;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.AccountSimplePlayerWrapper;
import com.game.proto.login.LoginResponse;
import com.game.proto.login.RefreshTokenResponse;
import java.util.concurrent.CompletableFuture;
import com.game.gateway.gate.GateSource;
import com.game.gateway.serverlist.ServerListController;
import com.game.gateway.announcement.AnnouncementController;
import com.game.gateway.store.AnnouncementRow;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.ratelimit.RateLimitDecision;
import com.game.gateway.ratelimit.RateLimiter;
import com.game.gateway.zone.ZoneHealthProbe;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import com.game.proto.GateTokenPayload;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * HTTP 契约测试：按 Go robot 的解析方式核对 JSON 键名（snake_case）、类型、业务码，以及签出的令牌能被 gate 侧验过。
 * 走真实装配（{@link GatewayConfiguration} + application.yaml 的 Jackson 配置），只把 gate 目录换成替身，不需要 Redis。
 */
@WebMvcTest(controllers = {AssignGateController.class, ServerListController.class, LoginController.class,
        AnnouncementController.class})
@Import({GatewayConfiguration.class, GatewayHttpApiTest.Meters.class})
@TestPropertySource(properties = {
        GatewayConfiguration.TOKEN_SECRET_ENV + "=" + GatewayHttpApiTest.SECRET,
})
class GatewayHttpApiTest {

    static final String SECRET = "http-test-secret";

    private static final GateTokens TOKENS = GateTokens.ofUtf8(SECRET);
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Go {@code base64.StdEncoding}：标准字母表、必须带填充。 */
    private static final String STD_BASE64 = "^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$";

    /** WebMvcTest 切片不带指标自动配置：给一个内存注册表，顺便用来核对 assign-gate 的结局计数。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class Meters {
        @Bean
        SimpleMeterRegistry simpleMeterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SimpleMeterRegistry meters;

    @MockitoBean
    private GateSource gateSource;

    @MockitoBean
    private AccountLoginService accountLogin;

    /** 登录排队的存储要 Redis；本测试排队关闭，不会真的调到它。 */
    @MockitoBean
    private org.redisson.api.RedissonClient redisson;

    @MockitoBean
    private GatewayStore store;

    @MockitoBean
    private ZoneHealthProbe probe;

    /** 开服限流：缺省放行；限流用例按需改判定（判定逻辑本身见 RateLimiterTest）。 */
    @MockitoBean
    private RateLimiter limiter;

    /** 一区开放推荐、二区维护、三区关闭、四区预告（新区、带开放时刻）；健康探测缺省未知。 */
    @BeforeEach
    void zones() {
        long now = System.currentTimeMillis();
        when(store.zones()).thenReturn(List.of(
                new ZoneRow(1, "一区", 0, 100, "开放区不该下发公告", null, true, 1, 0, 0),
                new ZoneRow(2, "二区", 1, 5000, "停服维护中", null, false, 2, 0, 0),
                new ZoneRow(3, "三区", 2, 5000, "", null, false, 3, 0, 0),
                new ZoneRow(4, "四区", 3, 5000, "", 1_900_000_000L, false, 4, now, now)));
        when(probe.health(anyInt())).thenReturn(ZoneHealthProbe.Health.UNKNOWN);
        when(probe.loadLevel(anyInt())).thenReturn(Optional.empty());
        when(limiter.check(anyInt(), any(), any(), any())).thenReturn(RateLimitDecision.pass());
    }

    private double assignOutcomes(int code, String reason) {
        return meters.get("xm.gateway.assign.gate").tag("code", Integer.toString(code)).tag("reason", reason)
                .counter().count();
    }

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
        double admittedBefore = assignOutcomes(0, "ok");

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
        // 同一测试类的各用例共用一个缓存的上下文（同一个注册表），只比增量。
        assertThat(assignOutcomes(0, "ok") - admittedBefore).isEqualTo(1);
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
        double before = assignOutcomes(404, "zone_not_found");
        assertRejected(assignGate("{\"zone_id\":99}"), 404, "zone_not_found");
        assertRejected(assignGate("{\"zone_id\":0}"), 404, "zone_not_found");
        assertRejected(assignGate("{}"), 404, "zone_not_found");
        verifyNoInteractions(gateSource);
        assertThat(assignOutcomes(404, "zone_not_found") - before).as("标签不带 zone_id，未知区服不会造出新序列").isEqualTo(3);
        assertThat(meters.find("xm.gateway.assign.gate").counters()).as("只有预先注册的已知结局").hasSize(16);
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
        double before = assignOutcomes(400, "bad_request");
        assertRejected(assignGate("{"), 400, "bad_request");
        assertRejected(assignGate("{\"zone_id\":\"abc\"}"), 400, "bad_request");
        assertRejected(call(post("/api/assign-gate").contentType(MediaType.TEXT_PLAIN).content("zone_id=1")),
                400, "bad_request");
        verifyNoInteractions(gateSource);
        assertThat(assignOutcomes(400, "bad_request") - before).as("异常处理器兜底的路径也计数").isEqualTo(3);
    }

    // ---------------------------------------------------------------- server-list

    @Test
    void 区服列表_键名类型与顺序() throws Exception {
        JsonNode resp = call(get("/api/server-list"));

        assertThat(keys(resp)).containsExactly("zones");
        JsonNode zones = resp.get("zones");
        assertThat(zones.isArray()).isTrue();
        assertThat(zones.size()).isEqualTo(4);

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
        assertThat(keys(closed)).as("同基线：关闭区文案为空也下发空串").containsExactlyInAnyOrder(
                "zone_id", "name", "status", "maintenance_msg", "is_new", "recommended");
        assertThat(closed.get("zone_id").intValue()).isEqualTo(3);
        assertThat(closed.get("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.get("maintenance_msg").asText()).isEmpty();

        JsonNode preview = zones.get(3);
        assertThat(keys(preview)).containsExactlyInAnyOrder("zone_id", "name", "status", "open_time", "is_new",
                "recommended");
        assertThat(preview.get("status").asText()).isEqualTo("PREVIEW");
        assertThat(preview.get("open_time").isIntegralNumber()).isTrue();
        assertThat(preview.get("open_time").longValue()).isEqualTo(1_900_000_000L);
        assertThat(preview.get("is_new").booleanValue()).as("7 天内创建").isTrue();
    }

    @Test
    void 区服列表_叠加健康探测_无gate显示维护_健康时下发负载档() throws Exception {
        when(probe.health(1)).thenReturn(ZoneHealthProbe.Health.HEALTHY);
        when(probe.loadLevel(1)).thenReturn(Optional.of(ZoneHealthProbe.LoadLevel.BUSY));
        JsonNode open = call(get("/api/server-list")).get("zones").get(0);
        assertThat(open.get("status").asText()).isEqualTo("OPEN");
        assertThat(open.get("load_level").asText()).isEqualTo("BUSY");

        when(probe.health(1)).thenReturn(ZoneHealthProbe.Health.DOWN);
        JsonNode down = call(get("/api/server-list")).get("zones").get(0);
        assertThat(down.get("status").asText()).isEqualTo("MAINTENANCE");
        assertThat(down.has("load_level")).isFalse();
        assertThat(down.get("maintenance_msg").asText()).isEqualTo("开放区不该下发公告");

        when(probe.health(2)).thenReturn(ZoneHealthProbe.Health.HEALTHY);
        when(probe.loadLevel(2)).thenReturn(Optional.of(ZoneHealthProbe.LoadLevel.FULL));
        JsonNode maintenance = call(get("/api/server-list")).get("zones").get(1);
        assertThat(maintenance.has("load_level")).as("只在显示 OPEN 时下发").isFalse();
    }

    @Test
    void 排队轮询_缺令牌410_排队关闭410_键名snake_case() throws Exception {
        JsonNode missing = call(post("/api/queue-status").contentType(MediaType.APPLICATION_JSON).content("{\"zone_id\":1}"));
        assertThat(missing.get("code").intValue()).isEqualTo(410);
        assertThat(missing.get("error").asText()).isEqualTo("missing_queue_token");
        JsonNode disabled = call(post("/api/queue-status").contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone_id\":1,\"queue_token\":\"t\"}"));
        assertThat(disabled.get("code").intValue()).isEqualTo(410);
        assertThat(disabled.get("error").asText()).isEqualTo("queue_disabled");
        assertThat(keys(disabled)).doesNotContain("queue_source", "queue_rank", "queue_total", "retry_after_ms");
    }

    @Test
    void 预告区_503_zone_not_open_且不读gate目录() throws Exception {
        assertRejected(assignGate("{\"zone_id\":4}"), 503, "zone_not_open");
        verifyNoInteractions(gateSource);
    }

    // ---------------------------------------------------------------- 登录公告

    @Test
    void 登录公告_只含生效中的_时刻为Unix秒_没有不输出() throws Exception {
        when(store.activeAnnouncements(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of(
                new AnnouncementRow(9, "维护通知", "今晚维护", "maintenance", 1_800_000_000L, 1_800_003_600L, 5),
                new AnnouncementRow(3, "欢迎", null, "notice", null, null, 1)));
        JsonNode resp = call(get("/api/announcement"));
        assertThat(keys(resp)).containsExactly("items");
        JsonNode first = resp.get("items").get(0);
        assertThat(keys(first)).containsExactlyInAnyOrder("id", "title", "content", "type", "start_time", "end_time");
        assertThat(first.get("id").longValue()).isEqualTo(9);
        assertThat(first.get("start_time").longValue()).isEqualTo(1_800_000_000L);
        JsonNode second = resp.get("items").get(1);
        assertThat(keys(second)).containsExactlyInAnyOrder("id", "title", "content", "type");
        assertThat(second.get("content").isNull()).isTrue();
    }


    // ---------------------------------------------------------------- HTTP 登录 / 刷新令牌

    /** 控制器返回 future：先确认进入异步，再取异步派发后的应答体。 */
    private JsonNode callAsync(RequestBuilder request) throws Exception {
        var started = mvc.perform(request).andExpect(MockMvcResultMatchers.request().asyncStarted()).andReturn();
        String body = mvc.perform(MockMvcRequestBuilders.asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return JSON.readTree(body);
    }

    @Test
    void 登录成功_键名与robot解析结构一致_没有值的字段不出现() throws Exception {
        when(accountLogin.login(org.mockito.ArgumentMatchers.any())).thenReturn(CompletableFuture.completedFuture(
                LoginResponse.newBuilder()
                        .addPlayers(AccountSimplePlayerWrapper.newBuilder().setPlayer(AccountSimplePlayer.newBuilder()
                                .setPlayerId(11).setName("道友aaaaaa")))
                        .setAccessToken("a".repeat(43)).setRefreshToken("r".repeat(43))
                        .setAccessTokenExpire(100).setRefreshTokenExpire(200).build()));

        JsonNode resp = callAsync(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone_id\":1,\"account\":\"robot_0001\",\"password\":\"p\",\"device_id\":\"d\"}"));

        assertThat(keys(resp)).containsExactlyInAnyOrder("code", "players", "access_token", "refresh_token",
                "access_token_expire", "refresh_token_expire");
        assertThat(resp.get("code").intValue()).isZero();
        assertThat(resp.get("access_token").asText()).hasSize(43);
        assertThat(resp.get("access_token_expire").isIntegralNumber()).isTrue();
        assertThat(keys(resp.get("players").get(0))).containsExactlyInAnyOrder("player_id", "name", "level");
        assertThat(resp.get("players").get(0).get("player_id").longValue()).isEqualTo(11);
    }

    @Test
    void 登录被拒是401_未知区是500_刷新空令牌是401且不调login() throws Exception {
        when(accountLogin.login(org.mockito.ArgumentMatchers.any())).thenReturn(CompletableFuture.completedFuture(
                LoginResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(2000)).build()));
        JsonNode rejected = callAsync(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone_id\":1,\"account\":\"robot_0001\",\"password\":\"bad\"}"));
        assertThat(keys(rejected)).containsExactlyInAnyOrder("code", "message");
        assertThat(rejected.get("code").intValue()).isEqualTo(401);

        JsonNode unknownZone = callAsync(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone_id\":9,\"account\":\"robot_0001\",\"password\":\"p\"}"));
        assertThat(unknownZone.get("code").intValue()).isEqualTo(500);

        JsonNode empty = callAsync(post("/api/refresh-token").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(empty.get("code").intValue()).isEqualTo(401);
        assertThat(empty.get("message").asText()).isEqualTo("empty_refresh_token");
        org.mockito.Mockito.verify(accountLogin, org.mockito.Mockito.never())
                .refreshToken(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 刷新成功带回新的一对() throws Exception {
        when(accountLogin.refreshToken(org.mockito.ArgumentMatchers.any())).thenReturn(CompletableFuture.completedFuture(
                RefreshTokenResponse.newBuilder().setAccessToken("a2").setRefreshToken("r2")
                        .setAccessTokenExpire(1).setRefreshTokenExpire(2).build()));
        JsonNode resp = callAsync(post("/api/refresh-token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"r1\"}"));
        assertThat(keys(resp)).containsExactlyInAnyOrder("code", "access_token", "refresh_token", "access_token_expire",
                "refresh_token_expire");
        assertThat(resp.get("refresh_token").asText()).isEqualTo("r2");
    }

    // ---------------------------------------------------------------- 开服限流

    @Test
    void 限流_assign排队100带ratelimit来源_429_IP取对端不信伪造的XFF_准入在前() throws Exception {
        double queuedBefore = assignOutcomes(100, "ratelimit");
        double deniedBefore = assignOutcomes(429, "IP_RATE_LIMIT");
        when(limiter.check(eq(1), eq("127.0.0.1"), eq("acc"), eq(RateLimiter.Scope.ASSIGN)))
                .thenReturn(RateLimitDecision.queue(1500, 7), RateLimitDecision.deny(RateLimitDecision.IP_RATE_LIMIT));
        RequestBuilder request = post("/api/assign-gate").contentType(MediaType.APPLICATION_JSON)
                .header("X-Forwarded-For", "6.6.6.6").content("{\"zone_id\":1,\"account\":\"acc\"}");

        JsonNode queued = call(request);
        assertThat(keys(queued)).as("gate_port / token_deadline 是基本类型，恒输出（见 AssignGateResponse）")
                .containsExactlyInAnyOrder("code", "gate_port", "token_deadline", "retry_after_ms", "queue_source", "queue_pos");
        assertThat(queued.get("code").intValue()).isEqualTo(100);
        assertThat(queued.get("queue_source").asText()).isEqualTo("ratelimit");
        assertThat(queued.get("retry_after_ms").longValue()).isEqualTo(1500);
        assertThat(queued.get("queue_pos").longValue()).isEqualTo(7);

        JsonNode denied = call(request);
        assertRejected(denied, 429, "IP_RATE_LIMIT");
        assertThat(assignOutcomes(100, "ratelimit") - queuedBefore).isEqualTo(1);
        assertThat(assignOutcomes(429, "IP_RATE_LIMIT") - deniedBefore).isEqualTo(1);
        verifyNoInteractions(gateSource);

        assertRejected(assignGate("{\"zone_id\":9}"), 404, "zone_not_found");
        org.mockito.Mockito.verify(limiter, org.mockito.Mockito.never()).check(eq(9), any(), any(), any());
    }

    @Test
    void 限流_登录排队100带QUEUEING_429_三方令牌当冷却身份_刷新不限流() throws Exception {
        when(limiter.check(eq(1), eq("127.0.0.1"), eq("robot_0001"), eq(RateLimiter.Scope.LOGIN)))
                .thenReturn(RateLimitDecision.queue(2000, 3));
        JsonNode queued = callAsync(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone_id\":1,\"account\":\"robot_0001\",\"password\":\"p\"}"));
        assertThat(keys(queued)).containsExactlyInAnyOrder("code", "message", "retry_after_ms", "queue_pos");
        assertThat(queued.get("code").intValue()).isEqualTo(100);
        assertThat(queued.get("message").asText()).isEqualTo("QUEUEING");
        assertThat(queued.get("retry_after_ms").longValue()).isEqualTo(2000);
        assertThat(queued.get("queue_pos").longValue()).isEqualTo(3);

        when(limiter.check(eq(1), eq("127.0.0.1"), eq("token:oauth-code"), eq(RateLimiter.Scope.LOGIN)))
                .thenReturn(RateLimitDecision.deny(RateLimitDecision.ACCOUNT_COOLDOWN));
        JsonNode denied = callAsync(post("/api/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"zone_id\":1,\"auth_type\":\"wechat\",\"auth_token\":\"oauth-code\"}"));
        assertThat(keys(denied)).containsExactlyInAnyOrder("code", "message");
        assertThat(denied.get("code").intValue()).isEqualTo(429);
        assertThat(denied.get("message").asText()).isEqualTo("ACCOUNT_COOLDOWN");
        org.mockito.Mockito.verify(accountLogin, org.mockito.Mockito.never()).login(any());

        when(accountLogin.refreshToken(any())).thenReturn(CompletableFuture.completedFuture(
                RefreshTokenResponse.newBuilder().setAccessToken("a2").setRefreshToken("r2").build()));
        callAsync(post("/api/refresh-token").contentType(MediaType.APPLICATION_JSON).content("{\"refresh_token\":\"r1\"}"));
        call(post("/api/queue-status").contentType(MediaType.APPLICATION_JSON).content("{\"zone_id\":1}"));
        org.mockito.Mockito.verify(limiter, org.mockito.Mockito.times(2)).check(anyInt(), any(), any(), any());
    }
}
