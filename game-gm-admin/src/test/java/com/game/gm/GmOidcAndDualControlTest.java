package com.game.gm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

/** 覆盖 2026-11-26 后台管理员 OIDC JWT 验签（含 12h 会话硬上限、生产禁静态口令）与双人复核全状态机。 */
class GmOidcAndDualControlTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void OIDC_RS256与EdDSA验签_分组整体映射权限_超过12小时硬上限被拒_生产模式拒绝静态口令() throws Exception {
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(2048);
        KeyPair rsa = rsaGen.generateKeyPair();
        RSAPublicKey rsaPub = (RSAPublicKey) rsa.getPublic();

        KeyPair ed = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] edX509 = ed.getPublic().getEncoded();
        byte[] edRaw32 = new byte[32];
        System.arraycopy(edX509, edX509.length - 32, edRaw32, 0, 32);

        String jwks = json.writeValueAsString(Map.of("keys", List.of(
                Map.of("kid", "rsa-1", "kty", "RSA",
                        "n", b64Url(unsignedBytes(rsaPub.getModulus())),
                        "e", b64Url(unsignedBytes(rsaPub.getPublicExponent()))),
                Map.of("kid", "ed-1", "kty", "OKP", "crv", "Ed25519", "x", b64Url(edRaw32))
        )));

        long nowSec = 1_700_000_000L;
        Clock clock = Clock.fixed(Instant.ofEpochSecond(nowSec), ZoneOffset.UTC);
        OidcJwtVerifier verifier = new OidcJwtVerifier(
                json, "https://idp.example.com", "game-gm-admin",
                OidcJwtVerifier.parseJwks(json, jwks),
                Map.of("gm-approvers", Set.of("gm:mail:approve", "gm:trade:approve")),
                clock);

        // 1. 合法 RS256 JWT 通过验签并按 groups 整体映射权限
        String validJwt = signJwt("RS256", "rsa-1", rsa.getPrivate(), Map.of(
                "iss", "https://idp.example.com",
                "aud", "game-gm-admin",
                "sub", "ops-alice",
                "iat", nowSec - 600,
                "exp", nowSec + 3600,
                "groups", List.of("gm-approvers")));
        GmAdminAuth prodAuth = new GmAdminAuth(
                Map.of("local-static-token", new GmAdminProperties.OperatorEntry("local-admin", List.of("*"))),
                verifier, false);

        MockHttpServletRequest oidcReq = new MockHttpServletRequest();
        oidcReq.addHeader("Authorization", "Bearer " + validJwt);
        GmAdminAuth.Principal principal = prodAuth.authenticate(oidcReq);
        assertThat(principal.username()).isEqualTo("ops-alice");
        assertThat(principal.permissions()).containsExactlyInAnyOrder("gm:mail:approve", "gm:trade:approve");

        // 2. 合法 EdDSA JWT 同样通过
        String edJwt = signJwt("EdDSA", "ed-1", ed.getPrivate(), Map.of(
                "iss", "https://idp.example.com", "aud", "game-gm-admin", "sub", "ops-bob",
                "iat", nowSec - 60, "exp", nowSec + 1800, "roles", List.of("gm-approvers")));
        assertThat(verifier.verify(edJwt).username()).isEqualTo("ops-bob");

        // 3. 超过 12 小时硬上限的会话（即便 exp 未到）被拒绝
        String staleSessionJwt = signJwt("RS256", "rsa-1", rsa.getPrivate(), Map.of(
                "iss", "https://idp.example.com", "aud", "game-gm-admin", "sub", "ops-alice",
                "iat", nowSec - (13L * 3600L), "exp", nowSec + 3600, "groups", List.of("gm-approvers")));
        assertThatThrownBy(() -> verifier.verify(staleSessionJwt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("12 小时");

        // 4. 生产模式禁用静态口令
        MockHttpServletRequest staticReq = new MockHttpServletRequest();
        staticReq.addHeader("X-Gm-Token", "local-static-token");
        assertThatThrownBy(() -> prodAuth.authenticate(staticReq))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("OIDC");
    }

    @Test
    void 双人复核禁止自批_未批准禁发_第二人批准后发送自动携带AdminApproval_支持24h过期与驳回撤回() throws Exception {
        SingleConnectionDataSource ds = new SingleConnectionDataSource(
                "jdbc:h2:mem:gm_dual_control;MODE=MySQL;DB_CLOSE_DELAY=-1", true);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        String schema = new String(
                getClass().getResourceAsStream("/db/gm-schema.sql").readAllBytes(), StandardCharsets.UTF_8);
        for (String stmt : schema.split(";")) {
            if (!stmt.isBlank()) {
                jdbc.execute(stmt);
            }
        }

        AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);
        Clock clock = new Clock() {
            @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(nowMs.get()); }
        };
        AtomicLong seq = new AtomicLong(1000L);
        GmCommandJournal journal = new GmCommandJournal(
                jdbc, json, new DataSourceTransactionManager(ds), clock, seq::incrementAndGet)
                .withDualControlRequired(true);

        ObjectNode mailInput = json.createObjectNode()
                .put("title", "补偿").put("body", "内容").put("validDays", 7);
        ObjectNode draft = journal.prepare("ops-alice", "req-1", "10086", "补偿发放", mailInput);
        String id = draft.path("id").asText();
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");

        // 1. 未经第二人批准不能直接发送
        assertThatThrownBy(() -> journal.send(id, "ops-alice", req -> json.createObjectNode()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("第二名管理员批准");

        // 2. 发起人不能自批
        assertThatThrownBy(() -> journal.approve(id, "ops-alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("禁止自批");

        // 3. 第二人 ops-bob 批准 -> APPROVED，且重复批准被拒绝（只记首次批准人）
        ObjectNode approved = journal.approve(id, "ops-bob");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("approver").asText()).isEqualTo("ops-bob");
        assertThatThrownBy(() -> journal.approve(id, "ops-charlie"))
                .isInstanceOf(ResponseStatusException.class);

        // 4. 发送时冻结载荷自动携带完整 approval 记录（approvalId == id, proposer=ops-alice, approver=ops-bob）
        AtomicReference<ObjectNode> sentPayload = new AtomicReference<>();
        ObjectNode delivered = journal.send(id, "ops-alice", payload -> {
            sentPayload.set(payload);
            return json.createObjectNode().put("mailId", "9001");
        });
        assertThat(delivered.path("status").asText()).isEqualTo("DELIVERED");
        assertThat(sentPayload.get().path("approval").path("approvalId").asText()).isEqualTo(id);
        assertThat(sentPayload.get().path("approval").path("proposer").asText()).isEqualTo("ops-alice");
        assertThat(sentPayload.get().path("approval").path("approver").asText()).isEqualTo("ops-bob");
        assertThat(sentPayload.get().path("approval").path("approvedAtMs").asLong()).isGreaterThan(0L);

        // 5. 驳回与撤回状态机
        ObjectNode draft2 = journal.prepare("ops-alice", "req-2", "10086", "测试驳回", mailInput);
        ObjectNode rejected = journal.reject(draft2.path("id").asText(), "ops-bob", "金额有误");
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED_APPROVAL");

        ObjectNode draft3 = journal.prepare("ops-alice", "req-3", "10086", "测试撤回", mailInput);
        ObjectNode withdrawn = journal.withdraw(draft3.path("id").asText(), "ops-alice");
        assertThat(withdrawn.path("status").asText()).isEqualTo("WITHDRAWN");

        // 6. 超过 24 小时申请拒绝批准
        ObjectNode draft4 = journal.prepare("ops-alice", "req-4", "10086", "测试过期", mailInput);
        nowMs.addAndGet(GmCommandJournal.APPROVAL_EXPIRY_MS + 1000L);
        assertThatThrownBy(() -> journal.approve(draft4.path("id").asText(), "ops-bob"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("24 小时");
        ds.destroy();
    }

    private String signJwt(String alg, String kid, java.security.PrivateKey key, Map<String, Object> claims)
            throws Exception {
        String h = b64Url(json.writeValueAsBytes(Map.of("alg", alg, "typ", "JWT", "kid", kid)));
        String p = b64Url(json.writeValueAsBytes(claims));
        String input = h + "." + p;
        Signature sig = Signature.getInstance("RS256".equals(alg) ? "SHA256withRSA" : "Ed25519");
        sig.initSign(key);
        sig.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + b64Url(sig.sign());
    }

    private static byte[] unsignedBytes(java.math.BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            byte[] trimmed = new byte[raw.length - 1];
            System.arraycopy(raw, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return raw;
    }

    private static String b64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
