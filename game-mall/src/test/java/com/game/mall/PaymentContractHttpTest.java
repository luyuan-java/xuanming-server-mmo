package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;

/** 验证 game-mall 与 game-payment(HttpMallClient) 的 6 条内部 HTTP 契约端到端对齐。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "game.namespace=mallpaycontract",
        "game.run-mode=test",
        "game.mall.require-tidb=false",
        "game.sql-authority.require-tidb=false",
        "game.mall.allowed-payment-channels=alipay,apple",
        "game.mall.gateway-secret=" + MallTestDatabase.GATEWAY_KEY,
        "game.mall.payment-secret=" + MallTestDatabase.PAYMENT_KEY,
        "game.mall.delivery-secret=mall-test-only-delivery-key-0000003",
        "spring.datasource.url=jdbc:h2:mem:mallpaycontract;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=never"
})
@Import(MallHttpFlowTest.H2AuthorityConfiguration.class)
@DirtiesContext
class PaymentContractHttpTest {

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    @Test
    void 支付异常退款游标_转人工与付款修订全链路对齐HttpMallClient契约() throws Exception {
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published) VALUES (10,10,'pay-sku',600,'CNY',20,0,1)");
        jdbc.update("INSERT INTO commerce_sku_grant(sku_id,item_config_id,count_per_unit,title) VALUES(10,10,1,'灵石包')");

        try (HttpClient http = HttpClient.newHttpClient()) {
            // 1. 下单
            String buyer = "10001";
            String o1 = createOrder(http, buyer, "req-pay-001", 10);

            // 2. 币种不符 & 金额不符：均返回 200 + {order, refundReason}，不绑定原单
            JsonNode currMis = json.readTree(call(http, "payment", "payment-adapter", "/internal/payments",
                    "{\"orderId\":\"" + o1 + "\",\"channel\":\"alipay\",\"transactionId\":\"tx-usd-1\",\"amountMinor\":600,\"currency\":\"USD\"}").body());
            assertThat(currMis.at("/order/orderId").asText()).isEqualTo(o1);
            assertThat(currMis.at("/order/state").asText()).isEqualTo("AWAITING_PAYMENT");
            assertThat(currMis.get("refundReason").asText()).isEqualTo("CURRENCY_MISMATCH");

            JsonNode amtMis = json.readTree(call(http, "payment", "payment-adapter", "/internal/payments",
                    "{\"orderId\":\"" + o1 + "\",\"channel\":\"alipay\",\"transactionId\":\"tx-amt-1\",\"amountMinor\":500,\"currency\":\"CNY\"}").body());
            assertThat(amtMis.at("/order/state").asText()).isEqualTo("AWAITING_PAYMENT");
            assertThat(amtMis.get("refundReason").asText()).isEqualTo("AMOUNT_MISMATCH");

            // 3. 正常付款返回 {order:{state:DELIVERY_PENDING}, refundReason:null}
            JsonNode paid = json.readTree(call(http, "payment", "payment-adapter", "/internal/payments",
                    "{\"orderId\":\"" + o1 + "\",\"channel\":\"alipay\",\"transactionId\":\"tx-ok-1\",\"amountMinor\":600,\"currency\":\"CNY\"}").body());
            assertThat(paid.at("/order/state").asText()).isEqualTo("DELIVERY_PENDING");
            assertThat(paid.get("refundReason").isNull()).isTrue();

            // 4. 重复付款返回 200 + DUPLICATE_PAYMENT，订单保持 DELIVERY_PENDING
            JsonNode dup = json.readTree(call(http, "payment", "payment-adapter", "/internal/payments",
                    "{\"orderId\":\"" + o1 + "\",\"channel\":\"apple\",\"transactionId\":\"tx-dup-1\",\"amountMinor\":600,\"currency\":\"CNY\"}").body());
            assertThat(dup.at("/order/state").asText()).isEqualTo("DELIVERY_PENDING");
            assertThat(dup.get("refundReason").asText()).isEqualTo("DUPLICATE_PAYMENT");

            // 5. 轮询退款待办：返回 {entries:[{..., reason}], nextCursor}，支持游标分页
            JsonNode page1 = json.readTree(call(http, "payment", "payment-adapter", "/internal/refunds/poll",
                    "{\"limit\":2,\"cursor\":\"\"}").body());
            assertThat(page1.get("entries")).hasSize(2);
            String cursor = page1.get("nextCursor").asText();
            assertThat(cursor).startsWith("r1:" + o1 + ":");
            JsonNode page2 = json.readTree(call(http, "payment", "payment-adapter", "/internal/refunds/poll",
                    "{\"limit\":2,\"cursor\":\"" + cursor + "\"}").body());
            assertThat(page2.get("entries")).hasSize(1);
            assertThat(page2.get("nextCursor").asText()).isEmpty();

            // 6. 对金额不符异常付款提交退款回执（带 paymentTransactionId），不影响原单 DELIVERY_PENDING
            var rec = call(http, "payment", "payment-adapter", "/internal/refunds/receipt",
                    "{\"orderId\":\"" + o1 + "\",\"channel\":\"alipay\",\"paymentTransactionId\":\"tx-amt-1\","
                            + "\"transactionId\":\"rf-amt-1\",\"amountMinor\":500,\"currency\":\"CNY\"}");
            assertThat(rec.statusCode()).isEqualTo(200);

            // 7. 对苹果重复付款调用 /internal/refunds/close 转人工（渠道无开发者退款接口）
            var closed = call(http, "payment", "payment-adapter", "/internal/refunds/close",
                    "{\"orderId\":\"" + o1 + "\",\"channel\":\"apple\",\"paymentTransactionId\":\"tx-dup-1\",\"reason\":\"NO_DEV_REFUND_API\"}");
            assertThat(closed.statusCode()).isEqualTo(200);
            // 幂等重放仍回 200
            assertThat(call(http, "payment", "payment-adapter", "/internal/refunds/close",
                    "{\"orderId\":\"" + o1 + "\",\"channel\":\"apple\",\"paymentTransactionId\":\"tx-dup-1\",\"reason\":\"NO_DEV_REFUND_API\"}").statusCode())
                    .isEqualTo(200);

            // 8. 对原单绑定付款推送渠道撤销修订（POST /internal/payments/revision，rev=1 REVOKED）-> REVERSAL_PENDING
            var rev1 = call(http, "payment", "payment-adapter", "/internal/payments/revision",
                    "{\"orderId\":\"" + o1 + "\",\"channel\":\"alipay\",\"transactionId\":\"tx-ok-1\",\"revision\":1,\"fact\":\"REVOKED\"}");
            assertThat(rev1.statusCode()).isEqualTo(200);
            JsonNode rev1Body = json.readTree(rev1.body());
            assertThat(rev1Body.get("revision").asInt()).isEqualTo(1);
            assertThat(rev1Body.get("fact").asText()).isEqualTo("REVOKED");
            assertThat(rev1Body.get("orderState").asText()).isEqualTo("REVERSAL_PENDING");
            // 过时/重放修订原样返回当前值 200
            JsonNode revReplay = json.readTree(call(http, "payment", "payment-adapter", "/internal/payments/revision",
                    "{\"orderId\":\"" + o1 + "\",\"channel\":\"alipay\",\"transactionId\":\"tx-ok-1\",\"revision\":1,\"fact\":\"REVOKED\"}").body());
            assertThat(revReplay.get("revision").asInt()).isEqualTo(1);

            // 9. 查单视图反映最新状态与 paymentRevision=1
            JsonNode view = json.readTree(call(http, "payment", "payment-adapter", "/internal/payments/order-view",
                    "{\"orderId\":\"" + o1 + "\"}").body());
            assertThat(view.get("state").asText()).isEqualTo("REVERSAL_PENDING");
            assertThat(view.get("paymentRevision").asInt()).isEqualTo(1);
        }
    }

    private String createOrder(HttpClient http, String buyer, String reqId, long skuId) throws Exception {
        String body = "{\"requestId\":\"" + reqId + "\",\"skuId\":" + skuId
                + ",\"quantity\":1,\"buyerAccount\":\"acc1\",\"buyerOwnerRevision\":\"1\"}";
        HttpResponse<String> resp = call(http, "gateway", buyer, "/api/orders", body);
        assertThat(resp.statusCode()).isEqualTo(200);
        return json.readTree(resp.body()).get("orderId").asText();
    }

    private HttpResponse<String> call(HttpClient http, String role, String subject, String path, String body) throws Exception {
        String ts = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String secret = switch (role) {
            case "payment" -> MallTestDatabase.PAYMENT_KEY;
            case "delivery" -> "mall-test-only-delivery-key-0000003";
            default -> MallTestDatabase.GATEWAY_KEY;
        };
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String sig = MallAuthentication.sign(secret, role, subject, ts, nonce, "POST", path, bytes);
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .header("X-Commerce-Subject", subject)
                .header("X-Commerce-Timestamp", ts)
                .header("X-Commerce-Nonce", nonce)
                .header("X-Commerce-Signature", sig)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
