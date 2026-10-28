package com.game.mall;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 随机namespace的真实Tomcat/H2接线；trade回执仅作SQL边界夹具，不冒真实资产或外部出款。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.address=127.0.0.1", "server.servlet.context-path=/mall", "spring.mvc.servlet.path=/rpc",
    "game.run-mode=test", "game.mall.require-tidb=false", "game.sql-authority.require-tidb=false", "game.mall.mock-payment-enabled=true",
    "game.mall.gateway-secret=rmb-http-test-only-gateway-key-000001",
    "game.mall.payment-secret=rmb-http-test-only-payment-key-000002",
    "game.mall.delivery-secret=rmb-http-test-only-delivery-key-00003",
    "game.mall.consignment.notice-ms=0", "game.mall.consignment.sale-ms=600000",
    "game.mall.consignment.cooling-ms=0", "game.mall.consignment.fee-bps=500",
    "spring.datasource.username=sa", "spring.datasource.password=", "spring.sql.init.mode=never",
    "spring.sql.init.schema-locations=classpath:db/migration/V1__official_sku.sql"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({RmbConsignmentHttpTest.ForwardConfiguration.class, MallHttpFlowTest.H2AuthorityConfiguration.class})
class RmbConsignmentHttpTest {
    private static final String PREFIX = "/mall/rpc";
    private static final String DATABASE = "mallrmb" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("game.namespace", () -> DATABASE);
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:" + DATABASE + ";MODE=MySQL;LOCK_TIMEOUT=5000");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @Test void 双前缀角色隔离_原OMS到收入与mock提现_未知正文及forward不能置成功() throws Exception {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        try {
            var escrow = new RmbConsignmentApi.Escrow("1001", "11", "seller-account", 1, "PET", null, "9001", "a".repeat(64), 101, "原宝宝托管");
            byte[] input = bytes(escrow);
            for (String role : List.of("gateway", "payment")) {
                assertEquals(401, call(http, "/internal/consignments/register", role, input).statusCode());
            }
            assertEquals(401, call(http, "/api/forward-rmb", "gateway", input).statusCode());
            assertEquals(0, count("commerce_consignment"));
            for (String invalid : List.of(
                    new String(input, StandardCharsets.UTF_8).replace("\"priceFen\":101", "\"priceFen\":101,\"feeBps\":0"),
                    new String(input, StandardCharsets.UTF_8) + " {}",
                    "{\"listingId\":\"1001\",\"listingId\":\"1002\"}")) {
                assertEquals(400, call(http, "/internal/consignments/register", "delivery", utf8(invalid)).statusCode());
            }
            var signed = request("/internal/consignments/register", "delivery", input);
            var registered = http.send(signed, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, registered.statusCode(), registered.body());
            var detail = call(http, "/internal/consignments/get", "delivery", bytes(new RmbConsignmentApi.Get("1001")));
            assertEquals(200, detail.statusCode(), detail.body());
            assertEquals(json.readTree(registered.body()), json.readTree(detail.body()));
            assertEquals(404, call(http, "/internal/consignments/get", "delivery", bytes(new RmbConsignmentApi.Get("9999"))).statusCode());
            assertEquals(401, call(http, "/internal/consignments/get", "gateway", bytes(new RmbConsignmentApi.Get("1001"))).statusCode());
            assertEquals(409, http.send(signed, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, call(http, "/internal/consignments/register", "delivery", input).statusCode());
            assertEquals(409, call(http, "/internal/consignments/buy", "delivery",
                    bytes(new RmbConsignmentApi.Buy("1001", "22", "seller-account", "self-account-request", "1"))).statusCode());
            var buy = new RmbConsignmentApi.Buy("1001", "22", "original-buyer-account", "http-buy-request", "1");
            var purchased = call(http, "/internal/consignments/buy", "delivery", bytes(buy));
            assertEquals(200, purchased.statusCode(), purchased.body());
            String order = json.readTree(purchased.body()).path("orderId").asText();
            assertEquals(400, call(http, "/internal/consignments/buy", "delivery",
                    utf8(new String(bytes(buy), StandardCharsets.UTF_8).replace("}", ",\"priceFen\":1}"))).statusCode());
            assertEquals(101, json.readTree(purchased.body()).path("amountMinor").asLong());
            assertEquals(0, json.readTree(purchased.body()).path("skuId").asLong());
            assertEquals(409, call(http, "/internal/consignments/buy", "delivery",
                    bytes(new RmbConsignmentApi.Buy("1001", "23", "original-buyer-account", buy.requestId(), "1"))).statusCode());
            assertEquals(404, call(http, "/internal/consignments/orders/get", "delivery", bytes(new RmbConsignmentApi.OrderQuery("new-character-owner", order))).statusCode());
            assertEquals(200, call(http, "/internal/consignments/orders/get", "delivery", bytes(new RmbConsignmentApi.OrderQuery("original-buyer-account", order))).statusCode());
            byte[] payment = bytes(new MockPaymentApi.Pay(order, "http-mock-payment"));
            assertEquals(401, call(http, "/internal/mock/pay", "delivery", payment).statusCode());
            assertEquals(200, call(http, "/internal/mock/pay", "payment", payment).statusCode());
            assertEquals(0, count("commerce_seller_income"));
            var officialPending = call(http, "/internal/deliveries/poll", "delivery", bytes(new DeliveryApi.Poll(20, "")));
            assertEquals(200, officialPending.statusCode(), officialPending.body());
            assertTrue(json.readTree(officialPending.body()).path("entries").isEmpty());
            var pending = call(http, "/internal/consignments/deliveries/poll", "delivery", bytes(new DeliveryApi.Poll(20, "")));
            assertEquals(200, pending.statusCode(), pending.body());
            assertEquals(order, json.readTree(pending.body()).at("/entries/0/intent/orderId").asText());
            assertEquals("9001", json.readTree(pending.body()).at("/entries/0/intent/escrowReference").asText());
            assertEquals("original-buyer-account", json.readTree(pending.body()).at("/entries/0/intent/buyerAccount").asText());
            assertEquals("1", json.readTree(pending.body()).at("/entries/0/intent/buyerOwnerRevision").asText());
            var delivered = bytes(new DeliveryApi.Receipt(order, "7001", "DELIVERED", "b".repeat(64)));
            assertEquals(200, call(http, "/internal/deliveries/receipt", "delivery", delivered).statusCode());
            assertEquals(200, call(http, "/internal/deliveries/receipt", "delivery", delivered).statusCode());
            var income = call(http, "/internal/consignments/incomes", "delivery", bytes(new RmbConsignmentApi.IncomePage("seller-account", "", 20)));
            assertEquals(200, income.statusCode(), income.body());
            assertEquals(6, json.readTree(income.body()).at("/incomes/0/feeFen").asLong());
            assertEquals(95, json.readTree(income.body()).at("/incomes/0/netFen").asLong());
            var withdrawal = call(http, "/internal/consignments/withdrawals/create", "delivery",
                    bytes(new RmbConsignmentApi.Withdraw("seller-account", "http-withdraw-request", 95)));
            assertEquals(200, withdrawal.statusCode(), withdrawal.body());
            String id = json.readTree(withdrawal.body()).path("withdrawalId").asText();
            byte[] payout = bytes(new MockPayoutStore.Pay(id, "http-provider-request"));
            assertEquals(401, call(http, "/internal/mock/withdraw", "delivery", payout).statusCode());
            String declaredSuccess = new String(payout, StandardCharsets.UTF_8).replace("}", ",\"successful\":true,\"amountFen\":1}");
            assertEquals(400, call(http, "/internal/mock/withdraw", "payment", utf8(declaredSuccess)).statusCode());
            assertEquals(400, call(http, "/internal/mock/withdraw", "payment",
                    utf8("{\"withdrawalId\":\"" + id + "\",\"requestId\":12345678}")).statusCode());
            assertEquals(0, count("commerce_mock_payout"));
            var paid = call(http, "/internal/mock/withdraw", "payment", payout);
            assertEquals(200, paid.statusCode(), paid.body());
            assertEquals("PAID", json.readTree(paid.body()).path("state").asText());
            assertEquals("mock", json.readTree(paid.body()).path("channel").asText());
            assertEquals(95, json.readTree(paid.body()).path("amountFen").asLong());
            assertEquals(json.readTree(paid.body()), json.readTree(call(http, "/internal/mock/withdraw", "payment", payout).body()));
            assertEquals(1, count("commerce_seller_income")); assertEquals(1, count("commerce_withdrawal_receipt"));
            assertEquals(1, count("commerce_mock_payout")); assertEquals(1, count("oms_order"));
            var summary = call(http, "/internal/mock/withdrawals/reconcile", "payment", utf8("{}"));
            assertEquals(200, summary.statusCode(), summary.body()); assertEquals(0, json.readTree(summary.body()).path("attempted").asInt());
            assertEquals(400, call(http, "/internal/mock/withdrawals/reconcile", "payment", utf8("{\"successful\":true}")).statusCode());
            assertEquals(404, call(http, "/internal/consignments/unknown", "delivery", utf8("{}")).statusCode());
        } finally { http.shutdownNow(); assertTrue(http.awaitTermination(Duration.ofSeconds(5)), "本例HTTP客户端清理超时"); }
    }

    private byte[] bytes(Object value) throws Exception { return json.writeValueAsBytes(value); }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private HttpResponse<String> call(HttpClient http, String path, String role, byte[] body) throws Exception { return http.send(request(path, role, body), HttpResponse.BodyHandlers.ofString()); }
    private HttpRequest request(String route, String role, byte[] body) {
        String path = PREFIX + route; String timestamp = Long.toString(System.currentTimeMillis()); String nonce = UUID.randomUUID().toString();
        String subject = switch (role) { case "payment" -> "payment-adapter"; case "delivery" -> "trade"; default -> "22"; };
        String secret = switch (role) { case "payment" -> "rmb-http-test-only-payment-key-000002"; case "delivery" -> "rmb-http-test-only-delivery-key-00003"; default -> "rmb-http-test-only-gateway-key-000001"; };
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-Commerce-Subject", subject).header("X-Commerce-Timestamp", timestamp)
                .header("X-Commerce-Nonce", nonce).header("X-Commerce-Signature", MallAuthentication.sign(secret, role, subject, timestamp, nonce, "POST", path, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
    }
    @TestConfiguration(proxyBeanMethods = false) static class ForwardConfiguration { @Bean ForwardController rmbForwardController() { return new ForwardController(); } }
    @RestController @org.springframework.boot.test.context.TestComponent static class ForwardController {
        @PostMapping("/api/forward-rmb") void forward(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            request.getRequestDispatcher("/rpc/internal/consignments/register").forward(request, response);
        }
    }
}
