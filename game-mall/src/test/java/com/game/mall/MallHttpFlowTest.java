package com.game.mall;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.common.GameNamespace;
import com.game.common.sql.AuthorityDataSource;
import com.game.common.sql.WriterAuthority;
import com.zaxxer.hikari.HikariDataSource;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;

/** 真Tomcat入口到同一H2事务库；外部authority资格由接口替身隔离，真实SQL包装仍运行。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "game.namespace=mallhttp", "game.run-mode=test", "game.mall.require-tidb=false", "game.sql-authority.require-tidb=false", "game.mall.allowed-payment-channels=test-channel",
        "game.mall.gateway-secret=" + MallTestDatabase.GATEWAY_KEY,
        "game.mall.payment-secret=" + MallTestDatabase.PAYMENT_KEY,
        "game.mall.delivery-secret=mall-test-only-delivery-key-0000003",
        "spring.datasource.url=jdbc:h2:mem:mallhttp;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.datasource.username=sa", "spring.datasource.password=",
        "spring.sql.init.mode=never", "spring.sql.init.schema-locations=classpath:db/migration/V1__official_sku.sql"})
@Import(MallHttpFlowTest.H2AuthorityConfiguration.class)
@DirtiesContext
class MallHttpFlowTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired GameNamespace namespace;
    @Autowired MallSchemaVerifier ready;
    @Autowired DataSource dataSource;
    @Autowired H2AuthorityFixture h2Fixture;
    final HttpClient http = HttpClient.newHttpClient();

    /** 五个本地HTTP回归共用；不生成grant，不声明真实PD/I3或NORMAL启动资格。 */
    @TestConfiguration(proxyBeanMethods = false)
    static class H2AuthorityConfiguration {
        private static final String FIXTURE = "mallH2AuthorityFixture";

        @Bean
        static BeanFactoryPostProcessor h2AuthorityDataSourceDependency() {
            return factory -> {
                var definition = factory.getBeanDefinition("dataSource");
                var dependencies = new LinkedHashSet<String>();
                if (definition.getDependsOn() != null) dependencies.addAll(List.of(definition.getDependsOn()));
                dependencies.add(FIXTURE);
                definition.setDependsOn(dependencies.toArray(String[]::new));
            };
        }

        @Bean(name = FIXTURE, destroyMethod = "close")
        H2AuthorityFixture h2AuthorityFixture(DataSourceProperties source, Environment environment) throws SQLException {
            return new H2AuthorityFixture(source, environment);
        }

        @Bean
        @Primary
        WriterAuthority h2WriterAuthority(H2AuthorityFixture fixture) { return fixture.authority; }

        @Bean
        SmartInitializingSingleton h2AuthorityWrapperCheck(DataSource source, H2AuthorityFixture fixture) {
            return () -> fixture.assertWrapped(source);
        }
    }

    /** raw连接只负责正式V1准备及保活；dataSource dependsOn本bean，销毁时池先关闭。 */
    static final class H2AuthorityFixture implements AutoCloseable {
        private final Connection anchor;
        private final String physicalUrl;
        private final AtomicBoolean sealed = new AtomicBoolean();
        final WriterAuthority authority;

        H2AuthorityFixture(DataSourceProperties source, Environment environment) throws SQLException {
            if (!"test".equals(environment.getRequiredProperty("game.run-mode"))
                    || environment.getProperty("game.sql-authority.require-tidb", Boolean.class, true)
                    || !"never".equals(environment.getRequiredProperty("spring.sql.init.mode"))) {
                throw new IllegalStateException("H2接口替身仅用于显式test且NORMAL不自动执行DDL的HTTP回归");
            }
            String url = source.getUrl();
            if (url == null || !url.startsWith("jdbc:h2:mem:") || !"sa".equalsIgnoreCase(source.getUsername())) {
                throw new IllegalStateException("HTTP夹具只接受本地H2内存库和显式SA主体");
            }
            Connection opened = DriverManager.getConnection(url, source.getUsername(), source.getPassword());
            try {
                requireRawH2(opened);
                var schema = new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__official_sku.sql"));
                schema.setSqlScriptEncoding(StandardCharsets.UTF_8.name());
                schema.populate(opened);
                physicalUrl = opened.getMetaData().getURL();
                anchor = opened;
                authority = Mockito.mock(WriterAuthority.class, invocation -> {
                    String method = invocation.getMethod().getName();
                    if (method.equals("verifyPhysical") || method.equals("begin")) {
                        Connection raw = invocation.getArgument(0);
                        requireRawH2(raw);
                        if (sealed.get() || !raw.getAutoCommit() || !physicalUrl.equals(raw.getMetaData().getURL())) {
                            throw new SQLException("H2接口替身拒绝已seal、旧事务或另一内存库连接");
                        }
                        // 真实包装负责commit/rollback；外部授权替身仍须真正开始H2事务。
                        if (method.equals("begin")) raw.setAutoCommit(false);
                        return null;
                    }
                    if (method.equals("seal")) { sealed.set(true); return null; }
                    if (method.equals("isSealed")) return sealed.get();
                    return Mockito.RETURNS_DEFAULTS.answer(invocation);
                });
            } catch (SQLException | RuntimeException | Error failure) {
                try { opened.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                throw failure;
            }
        }

        private static void requireRawH2(Connection raw) throws SQLException {
            if (Mockito.mockingDetails(raw).isMock() || raw.isClosed()
                    || !"H2".equals(raw.getMetaData().getDatabaseProductName())
                    || !"SA".equalsIgnoreCase(raw.getMetaData().getUserName())) {
                throw new SQLException("外部授权接口替身必须收到真实SA H2物理连接");
            }
        }

        void assertWrapped(DataSource source) {
            assertThat(source).isInstanceOf(HikariDataSource.class);
            assertThat(((HikariDataSource) source).getDataSource()).isInstanceOf(AuthorityDataSource.class);
            var calls = Mockito.mockingDetails(authority).getInvocations();
            assertThat(calls.stream().filter(call -> call.getMethod().getName().equals("verifyPhysical")).count())
                    .as("真实物理包装必须调用外部资格接口").isPositive();
            // begin为包内方法；只读Mockito实际调用记录，不反射调用或扩生产可见性。
            assertThat(calls.stream().filter(call -> call.getMethod().getName().equals("begin")).count())
                    .as("真实事务包装必须经过begin接缝").isPositive();
        }

        @Override public void close() throws SQLException { anchor.close(); }
    }

    @Test
    void 真实签名入口_订单取消付款_授权重放与主体隔离() throws Exception {
        assertThat(namespace.value()).isEqualTo("mallhttp");
        ready.verify();
        jdbc.update("INSERT INTO pms_sku_stock(id,product_id,sku_code,price_minor,currency,stock,lock_stock,published) VALUES (1,1,'http-sku',1250,'CNY',10,0,1)");
        jdbc.update("INSERT INTO commerce_sku_grant(sku_id,item_config_id,count_per_unit,title) VALUES(1,10,1,'玄铁')");
        String buyer = "18446744073709551615";
        String body = "{\"requestId\":\"http-request-001\",\"skuId\":1,\"quantity\":2,\"buyerAccount\":\"fixture-account\",\"buyerOwnerRevision\":\"1\"}";
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
        assertThat(owned("gateway", buyer, id, true).statusCode()).isEqualTo(409);
        assertThat(json.readTree(owned("gateway", buyer, id, false).body()).get("state").asText()).isEqualTo("DELIVERY_PENDING");
        assertThat(json.readTree(call("payment", "payment-adapter", "POST", "/internal/payments/order-view", orderQuery).body()))
                .isEqualTo(json.readTree(owned("gateway", buyer, id, false).body()));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM commerce_payment_receipt", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM commerce_delivery_intent WHERE order_sn=?", String.class, id)).isEqualTo("PENDING");
        String cancelledId = json.readTree(call("gateway", buyer, "POST", "/api/orders", body.replace("001", "002")).body()).get("orderId").asText();
        assertThat(owned("gateway", buyer, cancelledId, true).statusCode()).isEqualTo(200);
        assertThat(owned("gateway", buyer, cancelledId, true).statusCode()).isEqualTo(200);
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
        h2Fixture.assertWrapped(dataSource);
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
    private HttpResponse<String> owned(String role, String buyer, String order, boolean cancel) throws Exception {
        return call(role, buyer, "POST", cancel ? "/api/orders/cancel" : "/api/orders/query",
            json.writeValueAsString(new MallApi.OwnedOrder(order, "fixture-account", "1")));
    }
}
