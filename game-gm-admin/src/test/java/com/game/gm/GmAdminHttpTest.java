package com.game.gm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.game.proto.GmSetPlayerLevelRequest;
import com.game.proto.scene_node.GmExecutePlayerCommandRequest;
import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GmAdminHttpTest {

    private static final String ADMIN_TOKEN = "gm-super-token-0123456789abcdef";
    private static final String AUDIT_ONLY_TOKEN = "gm-audit-only-token-0123456789";
    private static final String BAD_NAME_TOKEN = "gm-bad-name-token-0123456789";

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer gatewayServer;
    private MockMvc mvc;
    private FakeGmGamePort port;

    @BeforeEach
    void setUp() throws Exception {
        gatewayServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gatewayServer.createContext("/admin/zones", exchange -> {
            if (!"gw-secret-key".equals(exchange.getRequestHeaders().getFirst("X-Game-Admin-Token"))
                    || !"ops.admin".equals(exchange.getRequestHeaders().getFirst("X-Game-Operator"))
                    || !"gw-secret-key".equals(exchange.getRequestHeaders().getFirst("X-Admin-Key"))) {
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
                return;
            }
            byte[] body = "[{\"zoneId\":1,\"name\":\"S1\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        gatewayServer.start();

        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:gm_http_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DataSourceTransactionManager tx = new DataSourceTransactionManager(ds);
        try (InputStream in = getClass().getResourceAsStream("/db/gm-schema.sql")) {
            String sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) {
                    jdbc.execute(stmt.strip());
                }
            }
        }
        GmCommandJournal journal = new GmCommandJournal(jdbc, mapper, tx);
        GmAdminProperties props = new GmAdminProperties(
                "http://127.0.0.1:" + gatewayServer.getAddress().getPort(),
                "gw-secret-key", true, true, true, 5000L, false,
                Map.of(
                        ADMIN_TOKEN, new GmAdminProperties.OperatorEntry("ops.admin", List.copyOf(GmAdminAuth.ALL_PERMISSIONS)),
                        AUDIT_ONLY_TOKEN, new GmAdminProperties.OperatorEntry("ops.auditor", List.of("gm:audit:read")),
                        BAD_NAME_TOKEN, new GmAdminProperties.OperatorEntry("bad|name", List.of("gm:audit:read"))));
        GmAdminAuth auth = new GmAdminAuth(props.operators());
        port = new FakeGmGamePort(mapper);
        GmController controller = new GmController(mapper, auth, port, journal, props);
        GmPlayerCommandController playerController = new GmPlayerCommandController(mapper, auth, port, journal);
        mvc = MockMvcBuilders.standaloneSetup(controller, playerController).build();
    }

    @AfterEach
    void tearDown() {
        if (gatewayServer != null) {
            gatewayServer.stop(0);
        }
    }

    @Test
    void 鉴权RBAC与严格用户名和JSON重复键校验() throws Exception {
        mvc.perform(get("/gm/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/gm/overview").header("Authorization", "Bearer " + BAD_NAME_TOKEN))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/gm/players/1001").header("X-Gm-Token", AUDIT_ONLY_TOKEN))
                .andExpect(status().isForbidden());

        // 概览拉取本地回环 Gateway /admin/zones
        mvc.perform(get("/gm/overview").header("Authorization", "Bearer " + ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.mailEnabled").value(true))
                .andExpect(jsonPath("$.result.zonesAvailable").value(true))
                .andExpect(jsonPath("$.result.zones[0].zoneId").value(1))
                .andExpect(jsonPath("$.result.currencies.length()").value(4));

        // 拒绝 JSON 重复键与未知字段
        String dupJson = "{\"clientRequestId\":\"" + UUID.randomUUID() + "\",\"clientRequestId\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(post("/gm/system-mail/drafts").header("X-Gm-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(dupJson))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 个人邮件_活动邮件_玩家GM与战斗剧本审核端到端流程() throws Exception {
        // 1. 活动邮件两阶段拟稿 + 发送
        String campaignBody = """
                {
                  "clientRequestId": "%s",
                  "title": "开服活动奖励",
                  "body": "感谢参与",
                  "reason": "S1活动发奖",
                  "validDays": 7,
                  "currencies": [{"currencyType": 1, "amount": "500"}],
                  "recipientPlayerIds": ["1002", "1001", "1002"]
                }
                """.formatted(UUID.randomUUID());
        String draftResp = mvc.perform(post("/gm/mail-campaigns/drafts").header("X-Gm-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(campaignBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("DRAFT"))
                .andExpect(jsonPath("$.result.request.totalRecipients").value(2))
                .andReturn().getResponse().getContentAsString();
        String campaignCmdId = mapper.readTree(draftResp).path("result").path("id").asText();

        mvc.perform(post("/gm/mail-campaigns/commands/" + campaignCmdId + "/send").header("X-Gm-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("DELIVERED"))
                .andExpect(jsonPath("$.result.result.sealed").value(true));

        // 2. 在线玩家 GM 命令两阶段拟稿 + 发送
        String playerCmdBody = """
                {
                  "clientRequestId": "%s",
                  "playerId": "1001",
                  "operation": "player.level",
                  "arguments": {"level": 60},
                  "reason": "测试调级"
                }
                """.formatted(UUID.randomUUID());
        String pDraft = mvc.perform(post("/gm/player-commands/drafts").header("X-Gm-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(playerCmdBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.operation").value("player.level"))
                .andReturn().getResponse().getContentAsString();
        String pCmdId = mapper.readTree(pDraft).path("result").path("id").asText();

        mvc.perform(post("/gm/player-commands/" + pCmdId + "/send").header("X-Gm-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.status").value("EXECUTED"))
                .andExpect(jsonPath("$.result.result.executed").value(true));

        // 3. 战斗剧本待审列表与审核
        mvc.perform(get("/gm/battle-scripts/pending").header("X-Gm-Token", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.scripts[0].scriptId").value("7001"));

        mvc.perform(post("/gm/battle-scripts/7001/review").header("X-Gm-Token", ADMIN_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedRevision\":1,\"approve\":true,\"reason\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.reviewState").value("BATTLE_SCRIPT_REVIEW_APPROVED"));

        // 4. gm-player-command-v1 签名格式验证（4 段管道符 + 64 位小写十六进制 HMAC）
        GmExecutePlayerCommandRequest raw = GmExecutePlayerCommandRequest.newBuilder()
                .setCommandId(9001L).setPlayerId(1001L).setOperator("ops.admin").setReason("test")
                .setTargetNodeId(3).setExpectedSessionId(7).setExpectedLoginGeneration(11).setIssuedAtMs(1_760_000_000_000L)
                .setSetLevel(GmSetPlayerLevelRequest.newBuilder().setLevel(50))
                .build();
        GmExecutePlayerCommandRequest signed = DubboGmGamePort.signPlayerCommand(
                raw, "0123456789abcdef0123456789abcdef", 1_760_000_000L, "9001");
        String[] parts = signed.getAuth().split("\\|");
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isEqualTo("ops.admin");
        assertThat(parts[1]).isEqualTo("1760000000");
        assertThat(parts[2]).isEqualTo("9001");
        assertThat(parts[3]).matches("[0-9a-f]{64}");
    }

    private static final class FakeGmGamePort implements GmGamePort {
        private final ObjectMapper mapper;

        FakeGmGamePort(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public boolean mailEnabled() {
            return true;
        }

        @Override
        public boolean playerCommandsEnabled() {
            return true;
        }

        @Override
        public boolean battleScriptReviewEnabled() {
            return true;
        }

        @Override
        public ArrayNode currencyTypes() {
            return GmGamePort.defaultCurrencyTypes(mapper);
        }

        @Override
        public boolean supportsCurrency(int currencyType) {
            return GmGamePort.isKnownCurrency(currencyType);
        }

        @Override
        public ObjectNode player(String playerId) {
            ObjectNode out = mapper.createObjectNode().put("playerId", playerId).put("found", true).put("sessionFound", true);
            out.putObject("session").put("state", "SESSION_STATE_ONLINE").put("sceneNodeId", "1");
            return out;
        }

        @Override
        public ObjectNode sendMail(ObjectNode frozenRequest) {
            return mapper.createObjectNode().put("mailId", "5001").put("duplicated", false);
        }

        @Override
        public ObjectNode sendSystemMail(ObjectNode frozenRequest) {
            return mapper.createObjectNode().put("mailId", "5002").put("duplicated", false);
        }

        @Override
        public ObjectNode sendMailCampaign(ObjectNode frozenCampaign) {
            return mapper.createObjectNode().put("mailId", "5003").put("sealed", true);
        }

        @Override
        public ObjectNode sendPlayerCommand(ObjectNode frozenRequest) {
            return mapper.createObjectNode().put("commandId", frozenRequest.path("requestId").asText()).put("executed", true);
        }

        @Override
        public ObjectNode listPendingBattleScripts(int limit) {
            ObjectNode out = mapper.createObjectNode();
            out.putArray("scripts").addObject().put("scriptId", "7001").put("revision", 1);
            return out;
        }

        @Override
        public ObjectNode reviewBattleScript(long scriptId, int revision, boolean approve, String reviewer, String reason) {
            return mapper.createObjectNode()
                    .put("scriptId", Long.toString(scriptId))
                    .put("reviewState", approve ? "BATTLE_SCRIPT_REVIEW_APPROVED" : "BATTLE_SCRIPT_REVIEW_REJECTED")
                    .put("idempotent", false);
        }
    }
}
