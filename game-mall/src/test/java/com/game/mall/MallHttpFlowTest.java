package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.common.GameNamespace;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/** 真Tomcat入口到同一事务库，包含本版自动namespace及schema门禁的完整装配。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "game.namespace=mallhttp", "game.run-mode=test", "game.mall.require-tidb=false", "game.mall.allowed-payment-channels=test-channel",
        "game.mall.gateway-secret=" + MallTestDatabase.GATEWAY_KEY,
        "game.mall.payment-secret=" + MallTestDatabase.PAYMENT_KEY,
        "game.mall.delivery-secret=mall-test-only-delivery-key-0000003",
        "spring.datasource.url=jdbc:h2:mem:mallhttp;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:db/migration/V1__official_sku.sql"})
@DirtiesContext
class MallHttpFlowTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired GameNamespace namespace;
    @Autowired MallSchemaVerifier ready;
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void 真实签名入口_订单取消付款_授权重放与主体隔离() throws Exception {
        assertThat(namespace.value()).isEqualTo("mallhttp");
        ready.verify();
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published) VALUES (1,1,'http-sku',1250,'CNY',10,0,1)");
        jdbc.update("INSERT INTO commerce_sku_grant(sku_id,item_config_id,count_per_unit,title) VALUES(1,10,1,'玄铁')");
        String buyer = "18446744073709551615";
        String body = "{\"requestId\":\"http-request-001\",\"skuId\":1,\"quantity\":2}";
        HttpRequest request = signed("gateway", buyer, "POST", "/api/orders", body, System.currentTimeMillis());
        var first = send(request);
        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode order = json.readTree(first.body());
        String id = order.get("orderId").asText();
        assertThat(order.get("buyerId").asText()).isEqualTo(buyer);
        assertThat(order.get("amountMinor").asLong()).isEqualTo(2500);
        assertThat(order.get("state").asText()).isEqualTo("AWAITING_PAYMENT");
        assertThat(send(request).statusCode()).isEqualTo(409);
        assertThat(json.readTree(call("gateway", buyer, "POST", "/api/orders", body).body()).get("orderId").asText()).isEqualTo(id);
        assertThat(call("gateway", "2", "GET", "/api/orders/" + id, "").statusCode()).isEqualTo(404);
        assertThat(call("gateway", "2", "POST", "/api/orders/" + id + "/cancel", "").statusCode()).isEqualTo(404);
        String orderQuery = "{\"orderId\":\"" + id + "\"}";
        String skuQuery = "{\"afterSkuId\":0,\"limit\":100}";
        HttpRequest paymentView = signed("payment", "payment-adapter", "POST", "/internal/payments/order-view", orderQuery, System.currentTimeMillis());
        var paymentViewed = send(paymentView);
        assertThat(paymentViewed.statusCode()).isEqualTo(200);
        assertThat(json.readTree(paymentViewed.body())).isEqualTo(order);
        assertThat(send(paymentView).statusCode()).isEqualTo(409);
        var skuResponse = call("payment", "payment-adapter", "POST", "/internal/payments/skus", skuQuery);
        assertThat(skuResponse.statusCode()).isEqualTo(200);
        JsonNode skus = json.readTree(skuResponse.body());
        assertThat(skus).isEqualTo(json.readTree(call("gateway", buyer, "POST", "/api/skus/list", skuQuery).body()));
        assertThat(skus.get("skus").size()).isEqualTo(1);
        assertThat(skus.at("/skus/0/skuId").asLong()).isEqualTo(1);
        assertThat(skus.at("/skus/0/available").asLong()).isEqualTo(8);
        assertThat(json.readTree(call("payment", "payment-adapter", "POST", "/internal/payments/skus",
                "{\"afterSkuId\":1,\"limit\":1}").body()).get("skus").isEmpty()).isTrue();
        for (String role : new String[]{"gateway", "delivery"}) {
            String subject = role.equals("delivery") ? "trade" : buyer;
            assertThat(call(role, subject, "POST", "/internal/payments/order-view", orderQuery).statusCode()).isEqualTo(401);
            assertThat(call(role, subject, "POST", "/internal/payments/skus", skuQuery).statusCode()).isEqualTo(401);
        }
        assertThat(call("payment", "wrong-adapter", "POST", "/internal/payments/order-view", orderQuery).statusCode()).isEqualTo(401);
        assertThat(call("payment", "payment-adapter", "POST", "/internal/payments/order-view/extra", orderQuery).statusCode()).isEqualTo(404);
        for (String invalid : new String[]{"{}", "null", "{\"orderId\":null}", "{\"orderId\":\"\"}",
                "{\"orderId\":\"ABCDEF00-0000-0000-0000-000000000001\"}", "{\"orderId\":\"abcdef00-0-0-0-1\"}"}) {
            assertThat(call("payment", "payment-adapter", "POST", "/internal/payments/order-view", invalid).statusCode()).isEqualTo(400);
        }
        assertThat(call("payment", "payment-adapter", "POST", "/internal/payments/order-view",
                "{\"orderId\":\"abcdef00-0000-0000-0000-000000000001\"}").statusCode()).isEqualTo(404);
        for (String invalid : new String[]{"{}", "null", "{\"afterSkuId\":-1,\"limit\":1}",
                "{\"afterSkuId\":0,\"limit\":0}", "{\"afterSkuId\":0,\"limit\":101}",
                "{\"afterSkuId\":9223372036854775808,\"limit\":1}"}) {
            assertThat(call("payment", "payment-adapter", "POST", "/internal/payments/skus", invalid).statusCode()).isEqualTo(400);
        }
        // 查询只消耗既有认证 nonce，不能触碰业务库存、订单、付款或交付状态。
        assertThat(jdbc.queryForObject("SELECT stock FROM pms_sku_stock WHERE id=1", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT lock_stock FROM pms_sku_stock WHERE id=1", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oms_order", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_receipt", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_delivery_intent", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_refund_intent", Integer.class)).isZero();
        assertThat(json.readTree(call("payment", "payment-adapter", "POST", "/internal/payments/order-view", orderQuery).body())).isEqualTo(order);
        String payment = "{\"orderId\":\"" + id + "\",\"channel\":\"test-channel\",\"transactionId\":\"http-pay-001\",\"amountMinor\":2500,\"currency\":\"CNY\"}";
        assertThat(call("gateway", buyer, "POST", "/internal/payments", payment).statusCode()).isEqualTo(401);
        assertThat(call("payment", "payment-adapter", "POST", "/internal/payments", payment).statusCode()).isEqualTo(200);
        assertThat(call("payment", "payment-adapter", "POST", "/internal/payments", payment).statusCode()).isEqualTo(200);
        assertThat(call("gateway", buyer, "POST", "/api/orders/" + id + "/cancel", "").statusCode()).isEqualTo(409);
        assertThat(json.readTree(call("gateway", buyer, "GET", "/api/orders/" + id, "").body()).get("state").asText()).isEqualTo("DELIVERY_PENDING");
        assertThat(json.readTree(call("payment", "payment-adapter", "POST", "/internal/payments/order-view", orderQuery).body()))
                .isEqualTo(json.readTree(call("gateway", buyer, "GET", "/api/orders/" + id, "").body()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_receipt", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM commerce_delivery_intent WHERE order_sn=?", String.class, id)).isEqualTo("PENDING");
        String cancelledId = json.readTree(call("gateway", buyer, "POST", "/api/orders", body.replace("001", "002")).body()).get("orderId").asText();
        assertThat(call("gateway", buyer, "POST", "/api/orders/" + cancelledId + "/cancel", "").statusCode()).isEqualTo(200);
        assertThat(call("gateway", buyer, "POST", "/api/orders/" + cancelledId + "/cancel", "").statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT lock_stock FROM pms_sku_stock WHERE id=1", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT stock FROM pms_sku_stock WHERE id=1", Integer.class)).isEqualTo(8);
        for (String invalid : new String[]{"0", "01", "18446744073709551616", "-1"}) {
            assertThat(call("gateway", invalid, "POST", "/api/orders", body).statusCode()).isEqualTo(401);
        }
        assertThat(send(signed("gateway", buyer, "POST", "/api/orders", body, System.currentTimeMillis() - 600_000)).statusCode()).isEqualTo(401);
        HttpRequest authenticated = signed("gateway", buyer, "POST", "/api/orders", body, System.currentTimeMillis());
        HttpRequest.Builder altered = HttpRequest.newBuilder(authenticated.uri()).POST(HttpRequest.BodyPublishers.ofString(body.replace("001", "003")));
        authenticated.headers().map().forEach((name, values) -> values.forEach(value -> altered.header(name, value)));
        assertThat(send(altered.build()).statusCode()).isEqualTo(401);
        HttpRequest.Builder duplicated = HttpRequest.newBuilder(authenticated.uri()).POST(HttpRequest.BodyPublishers.ofString(body));
        authenticated.headers().map().forEach((name, values) -> values.forEach(value -> duplicated.header(name, value)));
        duplicated.header("X-Commerce-Subject", buyer);
        assertThat(send(duplicated.build()).statusCode()).isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM oms_order", Integer.class)).isEqualTo(2);
    }

    private HttpResponse<String> call(String role, String subject, String method, String path, String body) throws Exception {
        return send(signed(role, subject, method, path, body, System.currentTimeMillis()));
    }

    private HttpRequest signed(String role, String subject, String method, String path, String body, long time) {
        String timestamp = Long.toString(time);
        String nonce = UUID.randomUUID().toString();
        String secret = switch (role) {
            case "payment" -> MallTestDatabase.PAYMENT_KEY;
            case "delivery" -> "mall-test-only-delivery-key-0000003";
            default -> MallTestDatabase.GATEWAY_KEY;
        };
        String signature = MallAuthentication.sign(secret, role, subject, timestamp, nonce, method, path, body.getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Commerce-Subject", subject).header("X-Commerce-Timestamp", timestamp)
                .header("X-Commerce-Nonce", nonce).header("X-Commerce-Signature", signature)
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
