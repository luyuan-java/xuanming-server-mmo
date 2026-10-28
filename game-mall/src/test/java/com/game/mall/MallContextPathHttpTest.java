package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 同时使用真实context与DispatcherServlet前缀，覆盖角色识别及内部forward的防御。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.servlet.context-path=/mall", "spring.mvc.servlet.path=/rpc",
        "game.namespace=mallcontext", "game.run-mode=test", "game.mall.require-tidb=false", "game.sql-authority.require-tidb=false", "game.mall.allowed-payment-channels=test-channel",
        "game.mall.gateway-secret=" + MallTestDatabase.GATEWAY_KEY,
        "game.mall.payment-secret=" + MallTestDatabase.PAYMENT_KEY,
        "game.mall.delivery-secret=mall-test-only-delivery-key-0000003",
        "spring.datasource.url=jdbc:h2:mem:mallcontext;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=never", "spring.sql.init.schema-locations=classpath:db/migration/V1__official_sku.sql"})
@Import(MallHttpFlowTest.H2AuthorityConfiguration.class)
@DirtiesContext
class MallContextPathHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 非空context及servlet前缀下网关不能伪造付款且支付密钥正常可用() throws Exception {
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published) VALUES (1,1,'context-sku',1250,'CNY',10,0,1)");
        jdbc.update("INSERT INTO commerce_sku_grant(sku_id,item_config_id,count_per_unit,title) VALUES(1,10,1,'玄铁')");
        String buyer = "18446744073709551615";
        var created = call("gateway", buyer, "POST", "/api/orders", "{\"requestId\":\"context-request-001\",\"skuId\":1,\"quantity\":1,\"buyerAccount\":\"fixture-account\",\"buyerOwnerRevision\":\"1\"}");
        assertThat(created.statusCode()).isEqualTo(200);
        String id = json.readTree(created.body()).get("orderId").asText();
        String payment = "{\"orderId\":\"" + id + "\",\"channel\":\"test-channel\",\"transactionId\":\"context-pay-001\",\"amountMinor\":1250,\"currency\":\"CNY\"}";
        String orderQuery = "{\"orderId\":\"" + id + "\"}";
        String skuQuery = "{\"afterSkuId\":0,\"limit\":1}";
        var queried = call("payment", "payment-adapter", "POST", "/internal/payments/order-view", orderQuery);
        assertThat(queried.statusCode()).isEqualTo(200);
        assertThat(json.readTree(queried.body())).isEqualTo(json.readTree(created.body()));
        var skus = call("payment", "payment-adapter", "POST", "/internal/payments/skus", skuQuery);
        assertThat(skus.statusCode()).isEqualTo(200);
        assertThat(json.readTree(skus.body()).at("/skus/0/skuId").asLong()).isEqualTo(1);
        for (String route : new String[]{"/internal/payments/order-view", "/internal/payments/skus"}) {
            String query = route.endsWith("order-view") ? orderQuery : skuQuery;
            assertThat(call("gateway", buyer, "POST", route, query).statusCode()).isEqualTo(401);
            assertThat(call("delivery", "trade", "POST", route, query).statusCode()).isEqualTo(401);
            assertThat(call("payment", "payment-adapter", "POST", route, query, route).statusCode()).isEqualTo(401);
            assertThat(call("payment", "payment-adapter", "POST", route + "/extra", query).statusCode()).isEqualTo(404);
        }
        assertThat(call("gateway", buyer, "POST", "/api/forward-order-view", orderQuery).statusCode()).isEqualTo(401);
        assertThat(call("gateway", buyer, "POST", "/api/forward-skus", skuQuery).statusCode()).isEqualTo(401);
        assertThat(call("gateway", buyer, "POST", "/internal/payments", payment).statusCode()).isIn(401, 403);
        assertThat(call("gateway", buyer, "POST", "/api/forward-payment", payment).statusCode()).isIn(401, 403);
        // 选择角色只看业务路径，但验签必须继续绑定部署前缀与完整 URI。
        assertThat(call("payment", "payment-adapter", "POST", "/internal/payments", payment, "/internal/payments").statusCode()).isEqualTo(401);
        assertThat(call("payment", "payment-adapter", "POST", "/internal/payments", payment, "/mall/internal/payments").statusCode()).isEqualTo(401);
        assertThat(call("gateway", buyer, "POST", "/internal/unknown", "{}").statusCode()).isEqualTo(404);
        assertThat(json.readTree(owned("gateway", buyer, id, false).body()).get("state").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_receipt", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_delivery_intent", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_refund_intent", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oms_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT stock FROM pms_sku_stock WHERE id=1", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT lock_stock FROM pms_sku_stock WHERE id=1", Integer.class)).isEqualTo(1);
        assertThat(call("payment", "payment-adapter", "POST", "/internal/payments", payment).statusCode()).isEqualTo(200);
        assertThat(json.readTree(owned("gateway", buyer, id, false).body()).get("state").asText()).isEqualTo("DELIVERY_PENDING");
    }

    private HttpResponse<String> call(String role, String subject, String method, String route, String body) throws Exception {
        return call(role, subject, method, route, body, "/mall/rpc" + route);
    }

    private HttpResponse<String> call(String role, String subject, String method, String route, String body, String signedUri) throws Exception {
        String rawUri = "/mall/rpc" + route;
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String secret = switch (role) {
            case "payment" -> MallTestDatabase.PAYMENT_KEY;
            case "delivery" -> "mall-test-only-delivery-key-0000003";
            default -> MallTestDatabase.GATEWAY_KEY;
        };
        String signature = MallAuthentication.sign(secret, role, subject, timestamp, nonce, method, signedUri, body.getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawUri))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Commerce-Subject", subject).header("X-Commerce-Timestamp", timestamp)
                .header("X-Commerce-Nonce", nonce).header("X-Commerce-Signature", signature)
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ForwardConfiguration {
        @Bean ForwardProbe forwardProbe() { return new ForwardProbe(); }
    }

    /** 仅测试的真实服务器forward入口；支付Controller仍应拒绝已认证的网关角色。 */
    @RestController
    static class ForwardProbe {
        @PostMapping("/api/forward-payment")
        void forward(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            request.getRequestDispatcher("/rpc/internal/payments").forward(request, response);
        }

        @PostMapping("/api/forward-order-view")
        void forwardOrder(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            request.getRequestDispatcher("/rpc/internal/payments/order-view").forward(request, response);
        }

        @PostMapping("/api/forward-skus")
        void forwardSkus(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            request.getRequestDispatcher("/rpc/internal/payments/skus").forward(request, response);
        }
    }
    private HttpResponse<String> owned(String role, String buyer, String order, boolean cancel) throws Exception {
        return call(role, buyer, "POST", cancel ? "/api/orders/cancel" : "/api/orders/query",
            json.writeValueAsString(new MallApi.OwnedOrder(order, "fixture-account", "1")));
    }
}
