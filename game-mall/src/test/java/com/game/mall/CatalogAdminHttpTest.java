package com.game.mall;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 真实Tomcat双前缀、签名、控制器和事务库；H2不代替真实TiDB或完整管理后台验收。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1", "server.servlet.context-path=/mall", "spring.mvc.servlet.path=/rpc",
        "game.namespace=malladminhttp", "game.run-mode=test", "game.mall.require-tidb=false",
        "game.mall.gateway-secret=" + MallTestDatabase.GATEWAY_KEY,
        "game.mall.payment-secret=" + MallTestDatabase.PAYMENT_KEY,
        "game.mall.delivery-secret=" + CatalogAdminHttpTest.DELIVERY_KEY,
        "game.mall.admin-secret=" + CatalogAdminHttpTest.ADMIN_KEY,
        "spring.datasource.url=jdbc:h2:mem:malladminhttp;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=always", "spring.sql.init.schema-locations=classpath:db/migration/V1__official_sku.sql"})
@DirtiesContext
class CatalogAdminHttpTest {
    static final String DELIVERY_KEY = "mall-catalog-test-delivery-key-only-0003";
    static final String ADMIN_KEY = "mall-catalog-test-management-key-only-0004";
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MallProperties properties;
    @Autowired CatalogAdminStore catalogAdminStore;
    @Autowired RmbConsignmentStore consignmentStore;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void 两个正式Repository可被代理_保留持久化异常转换() {
        assertTrue(AopUtils.isCglibProxy(catalogAdminStore));
        assertTrue(AopUtils.isCglibProxy(consignmentStore));
        assertNotNull(catalogAdminStore.list(new DeliveryApi.SkuPage(0, 1)));
    }

    @Test
    void 独立管理签名经真实双前缀入口_命令重放冻结订单与越权拒绝() throws Exception {
        assertEquals("mall-admin", properties.adminSubject());
        var originalTerms = new CatalogAdminApi.Terms(100, "http-managed", 125, "CNY", true, 10, 2, "旧玄铁");
        var creation = new CatalogAdminApi.Create("http-admin-create-001", 101, originalTerms, 8);
        String body = json.writeValueAsString(creation);
        for (String role : new String[]{"gateway", "payment", "delivery"}) {
            String subject = role.equals("gateway") ? "1" : role.equals("payment") ? "payment-adapter" : "trade";
            assertEquals(401, call(role, subject, "/internal/admin/skus/create", body).statusCode());
        }
        assertEquals(401, call("admin", "other-admin", "/internal/admin/skus/create", body).statusCode());
        assertEquals(401, send(signed("admin", "mall-admin", "/internal/admin/skus/create", body,
                "/internal/admin/skus/create")).statusCode(), "签名必须包含完整部署前缀");
        assertEquals(404, call("admin", "mall-admin", "/internal/admin/orders/update", "{}").statusCode());
        assertEquals(0, count("commerce_admin_command"));

        HttpRequest first = signed("admin", "mall-admin", "/internal/admin/skus/create", body);
        var created = send(first);
        assertEquals(200, created.statusCode(), created.body());
        JsonNode snapshot = json.readTree(created.body());
        assertEquals(101, snapshot.path("skuId").asLong());
        assertEquals(1, snapshot.path("revision").asLong());
        assertEquals(8, snapshot.path("stock").asInt());
        assertEquals(409, send(first).statusCode(), "认证nonce仍不可重放");
        assertEquals(snapshot, json.readTree(call("admin", "mall-admin", "/internal/admin/skus/create", body).body()));
        assertEquals(1, count("commerce_admin_command"));
        assertEquals(409, call("admin", "mall-admin", "/internal/admin/skus/create",
                json.writeValueAsString(new CatalogAdminApi.Create(creation.requestId(), 101, originalTerms, 9))).statusCode());

        var orderResponse = call("gateway", "1", "/api/orders",
                json.writeValueAsString(new MallApi.CreateOrder("http-admin-order-001", 101, 2, "fixture-account", "1")));
        assertEquals(200, orderResponse.statusCode(), orderResponse.body());
        JsonNode order = json.readTree(orderResponse.body());
        String orderId = order.path("orderId").asText();
        assertEquals(250, order.path("amountMinor").asLong());
        var grant = jdbc.queryForMap("SELECT * FROM commerce_order_grant WHERE order_sn=?", orderId);
        assertEquals(10, ((Number) grant.get("item_config_id")).intValue());
        assertEquals(2, ((Number) grant.get("count_per_unit")).intValue());
        assertEquals("旧玄铁", grant.get("title"));

        var terms = new CatalogAdminApi.Terms(200, "http-new-terms", 999, "USD", true, 20, 3, "新玄铁");
        var update = call("admin", "mall-admin", "/internal/admin/skus/update",
                json.writeValueAsString(new CatalogAdminApi.Update("http-admin-update-001", 101, 1, terms)));
        assertEquals(200, update.statusCode(), update.body());
        assertEquals(8, json.readTree(update.body()).path("stock").asInt());
        assertEquals(2, json.readTree(update.body()).path("lockedStock").asInt());
        var adjustment = new CatalogAdminApi.AdjustStock("http-admin-stock-001", 101, 2, -3);
        var adjusted = call("admin", "mall-admin", "/internal/admin/skus/stock", json.writeValueAsString(adjustment));
        assertEquals(200, adjusted.statusCode(), adjusted.body());
        assertEquals(5, json.readTree(adjusted.body()).path("stock").asInt());
        assertEquals(2, json.readTree(adjusted.body()).path("lockedStock").asInt());
        assertEquals(3, json.readTree(adjusted.body()).path("revision").asLong());
        assertEquals(409, call("admin", "mall-admin", "/internal/admin/skus/stock",
                json.writeValueAsString(new CatalogAdminApi.AdjustStock("http-admin-stock-bad", 101, 3, -4))).statusCode());
        assertEquals(401, call("gateway", "1", "/api/forward-admin",
                json.writeValueAsString(new CatalogAdminApi.AdjustStock("http-admin-forward", 101, 3, 1))).statusCode());
        assertEquals(grant, jdbc.queryForMap("SELECT * FROM commerce_order_grant WHERE order_sn=?", orderId));
        assertEquals(order, json.readTree(call("payment", "payment-adapter", "/internal/payments/order-view",
                json.writeValueAsString(new MallApi.OrderQuery(orderId))).body()));
        var adminOrder = call("admin", "mall-admin", "/internal/admin/order-view",
                json.writeValueAsString(new MallApi.OrderQuery(orderId)));
        assertEquals(200, adminOrder.statusCode(), adminOrder.body());
        assertEquals(order, json.readTree(adminOrder.body()));
        assertEquals(401, call("admin", "mall-admin", "/internal/payments/order-view",
                json.writeValueAsString(new MallApi.OrderQuery(orderId))).statusCode(), "管理角色不能借只读接口取得payment权限");
        assertEquals(401, call("payment", "payment-adapter", "/internal/admin/order-view",
                json.writeValueAsString(new MallApi.OrderQuery(orderId))).statusCode());

        // 只模拟既有缺映射目录输入，管理列表必须能看到它，才能经受限命令修复。
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published)"
                + " VALUES(102,300,'unmapped',250,'CNY',4,0,0)");
        var listed = call("admin", "mall-admin", "/internal/admin/skus/list", "{\"afterSkuId\":0,\"limit\":100}");
        assertEquals(200, listed.statusCode(), listed.body());
        JsonNode catalog = json.readTree(listed.body()).path("skus");
        assertEquals(2, catalog.size());
        assertEquals(102, catalog.get(1).path("skuId").asLong());
        assertFalse(catalog.get(1).path("published").asBoolean());
        assertTrue(catalog.get(1).path("title").isNull());
        assertEquals(400, call("admin", "mall-admin", "/internal/admin/skus/list", "{\"afterSkuId\":0,\"limit\":101}").statusCode());
        var publicCatalog = call("gateway", "1", "/api/skus/list", "{\"afterSkuId\":0,\"limit\":100}");
        assertEquals(200, publicCatalog.statusCode(), publicCatalog.body());
        assertEquals(1, json.readTree(publicCatalog.body()).path("skus").size());
        assertEquals(3, count("commerce_admin_command"));
        assertEquals(1, count("oms_order"));
        assertEquals(0, count("commerce_payment_receipt"));
        assertEquals(0, count("commerce_delivery_intent"));
        assertEquals(0, count("commerce_refund_intent"));
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private HttpResponse<String> call(String role, String subject, String path, String body) throws Exception {
        return send(signed(role, subject, path, body));
    }
    private HttpRequest signed(String role, String subject, String path, String body) {
        return signed(role, subject, path, body, "/mall/rpc" + path);
    }
    private HttpRequest signed(String role, String subject, String path, String body, String signedUri) {
        String key = switch (role) {
            case "payment" -> MallTestDatabase.PAYMENT_KEY;
            case "delivery" -> DELIVERY_KEY;
            case "admin" -> ADMIN_KEY;
            default -> MallTestDatabase.GATEWAY_KEY;
        };
        String timestamp = Long.toString(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        byte[] content = body.getBytes(StandardCharsets.UTF_8);
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mall/rpc" + path))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("X-Commerce-Subject", subject).header("X-Commerce-Timestamp", timestamp)
                .header("X-Commerce-Nonce", nonce).header("X-Commerce-Signature",
                        MallAuthentication.sign(key, role, subject, timestamp, nonce, "POST", signedUri, content))
                .POST(HttpRequest.BodyPublishers.ofByteArray(content)).build();
    }
    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ForwardConfiguration {
        @Bean ForwardProbe catalogForwardProbe() { return new ForwardProbe(); }
    }
    @RestController
    static class ForwardProbe {
        @PostMapping("/api/forward-admin")
        void forward(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            request.getRequestDispatcher("/rpc/internal/admin/skus/stock").forward(request, response);
        }
    }
}
