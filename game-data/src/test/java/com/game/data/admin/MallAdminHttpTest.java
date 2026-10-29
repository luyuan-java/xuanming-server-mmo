package com.game.data.admin;

import static org.junit.jupiter.api.Assertions.*;

import com.game.data.DataConfiguration;
import com.game.data.DataProperties;
import com.game.data.mall.MallAdminClient;
import com.game.data.mall.MallAdminProperties;
import com.game.data.metrics.DataMetrics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 真实Tomcat和自有HTTP上游验证代理接缝；无SQL、Redis或支付渠道依赖。 */
@SpringBootTest(classes = MallAdminHttpTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.servlet.context-path=/ops", "spring.mvc.servlet.path=/rpc"})
@DirtiesContext
class MallAdminHttpTest {
    private static final String TOKEN = "secret"; // 保留原管理面可使用既有短令牌的行为。
    private static final String SECRET = "test-only-data-mall-signing-key-00000001";
    private static final String SUBJECT = "mall-admin";
    private static final Upstream UPSTREAM = new Upstream();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    @LocalServerPort int port;
    @Autowired SimpleMeterRegistry meters;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("game.data.mall-admin.base-url", () -> UPSTREAM.baseUrl());
        registry.add("game.data.mall-admin.secret", () -> SECRET);
        registry.add("game.data.mall-admin.subject", () -> SUBJECT);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    @EnableConfigurationProperties(MallAdminProperties.class)
    @Import({MallAdminController.class, MallAdminClient.class, Forward.class})
    static class App {
        @Bean SimpleMeterRegistry meters() { return new SimpleMeterRegistry(); }
        @Bean DataMetrics metrics(SimpleMeterRegistry meters) { return new DataMetrics(meters); }
        @Bean FilterRegistrationBean<AdminAuthFilter> filter(DataMetrics metrics) {
            var props = new Binder(new MapConfigurationPropertySource(Map.of("game.data.admin-token", TOKEN)))
                    .bindOrCreate("game.data", DataProperties.class);
            return new DataConfiguration().adminAuthFilter(props, metrics, "/rpc");
        }
    }

    @RestController static class Forward {
        @PostMapping("/public-forward")
        void forward(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
            request.getRequestDispatcher("/rpc/admin/mall/skus/stock").forward(request, response);
        }
    }

    @Test void 双前缀真实管理认证_固定命令原字节签名_静态页面不泄漏签名材料() throws Exception {
        byte[] original = bytes("{\n \"requestId\":\"data-stock-original\",\"skuId\":\"9223372036854775806\","
                + "\"expectedRevision\":\"2\",\"delta\":\"3\",\"title\":\"玄铁\"\n}");
        for (String route : List.of("/admin/mall/skus/stock", "/%61dmin/mall/skus/stock", "/admin;x=1/mall/skus/stock")) {
            assertEquals(401, post(route, original, null, null).statusCode());
        }
        assertEquals(401, post("/admin/mall/skus/create", original, "wrong", "ops").statusCode());
        assertEquals(400, post("/admin/mall/skus/create", original, TOKEN, null).statusCode());
        assertEquals(400, post("/admin/mall/skus/create", original, TOKEN, " ").statusCode());
        assertEquals(401, post("/public-forward", original, null, null).statusCode(), "公开forward不能伪造过滤器认证标记");
        assertEquals(0, UPSTREAM.calls.get());

        byte[] upstreamBody = bytes("{ \"skuId\":9223372036854775806, \"lockedStock\":2, \"priceMinor\":125 }");
        UPSTREAM.response.set(new Reply(409, upstreamBody));
        String firstNonce = null;
        long responseObservationUntil = 0;
        for (String command : List.of("skus/list", "skus/create", "skus/update", "skus/stock", "order-view", "skus/stock")) {
            var response = post("/admin/mall/" + command, original, TOKEN, "运营甲");
            assertEquals(409, response.statusCode());
            assertArrayEquals(upstreamBody, response.body());
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            responseObservationUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            var captured = UPSTREAM.requests.poll(Math.max(0, responseObservationUntil - System.nanoTime()), TimeUnit.NANOSECONDS);
            assertNotNull(captured);
            assertEquals("/mall/rpc/internal/admin/" + command, captured.path());
            assertArrayEquals(original, captured.body());
            assertEquals(SUBJECT, captured.subject());
            assertEquals(UUID.fromString(captured.nonce()).toString(), captured.nonce());
            assertEquals(sign(captured), captured.signature());
            if (firstNonce == null) { firstNonce = captured.nonce(); }
            else { assertNotEquals(firstNonce, captured.nonce()); }
        }
        assertEquals(6, UPSTREAM.calls.get(), "冲突/未知响应不得自动重复提交");
        // 响应正文可能先于服务端finally；与末次抓包共享原2s观察期限，不追加等待预算。
        var counter = meters.find("game.data.admin.requests").tag("op", "mall_catalog").tag("result", "409").counter();
        long remaining;
        while ((counter == null || counter.count() < 6) && (remaining = responseObservationUntil - System.nanoTime()) > 0) {
            TimeUnit.NANOSECONDS.sleep(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(5)));
            counter = meters.find("game.data.admin.requests").tag("op", "mall_catalog").tag("result", "409").counter();
        }
        assertTrue(meters.get("game.data.admin.requests").tag("op", "mall_catalog").tag("result", "409").counter().count() >= 6);

        for (String invalid : List.of("[]", "null", "{} garbage", "{} {}")) {
            assertEquals(400, post("/admin/mall/skus/stock", bytes(invalid), TOKEN, "ops").statusCode());
        }
        assertEquals(413, post("/admin/mall/skus/stock", new byte[16_385], TOKEN, "ops").statusCode());
        assertEquals(400, post("/admin/mall/skus/stock?target=http://elsewhere", original, TOKEN, "ops").statusCode());
        assertEquals(404, post("/admin/mall/skus/delete", original, TOKEN, "ops").statusCode());
        assertEquals(6, UPSTREAM.calls.get());

        for (String asset : List.of("/mall-admin", "/mall-admin.js", "/mall-admin.css")) {
            var response = get(asset);
            assertEquals(200, response.statusCode());
            String content = new String(response.body(), StandardCharsets.UTF_8);
            assertFalse(content.contains(SECRET));
            assertFalse(content.contains("value=\"" + TOKEN + "\""));
            assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            if (asset.equals("/mall-admin")) {
                assertTrue(content.contains("admin-operator"));
                var csp = response.headers().firstValue("Content-Security-Policy").orElseThrow();
                assertTrue(csp.contains("frame-ancestors 'none'"));
                assertFalse(csp.contains("unsafe-inline"));
            }
            if (asset.endsWith(".js")) {
                assertTrue(content.contains("X-Game-Admin-Token"));
                assertTrue(content.contains("X-Game-Operator"));
                assertTrue(content.contains("new TextEncoder()"));
                assertFalse(content.contains("localStorage"));
                assertFalse(content.contains("sessionStorage"));
                assertFalse(content.contains("JSON.parse("));
            }
        }
        assertEquals(6, UPSTREAM.calls.get());
        assertTrue(UPSTREAM.failures.isEmpty(), () -> "自有上游异常：" + UPSTREAM.failures);
    }

    @Test void 私有认证标记只在本次过滤链有效_未配置令牌不影响公开页面() throws Exception {
        var request = new MockHttpServletRequest("POST", "/admin/mall/skus/stock");
        request.setServletPath("/admin/mall/skus/stock");
        request.addHeader(AdminAuthFilter.TOKEN_HEADER, TOKEN);
        request.addHeader(AdminAuthFilter.OPERATOR_HEADER, "ops");
        request.addHeader("X-Game-Admin-Authenticated", "true");
        assertThrows(ResponseStatusException.class, () -> AdminAuthFilter.authenticatedOperator(request));
        var registry = new SimpleMeterRegistry();
        var response = new MockHttpServletResponse();
        var filter = new AdminAuthFilter(TOKEN, new DataMetrics(registry));
        filter.doFilter(request, response, (req, res) -> assertEquals("ops", AdminAuthFilter.authenticatedOperator((HttpServletRequest) req)));
        assertThrows(ResponseStatusException.class, () -> AdminAuthFilter.authenticatedOperator(request));
        assertEquals("mall_catalog", AdminAuthFilter.opOf("/admin/mall/skus/stock"));
        var closed = new MockHttpServletResponse();
        new AdminAuthFilter("", new DataMetrics(registry)).doFilter(request, closed, (req, res) -> fail("未配置令牌不得放行"));
        assertEquals(503, closed.getStatus());
        assertEquals(200, get("/mall-admin").statusCode());
        registry.close();
    }

    private HttpResponse<byte[]> post(String route, byte[] body, String token, String operator) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ops/rpc" + route))
                .timeout(Duration.ofSeconds(12)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) { request.header(AdminAuthFilter.TOKEN_HEADER, token); }
        if (operator != null) { request.header(AdminAuthFilter.OPERATOR_HEADER,
                new String(operator.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1)); }
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> get(String route) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ops/rpc" + route))
                .timeout(Duration.ofSeconds(12)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String sign(Captured captured) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(captured.body()));
        String canonical = String.join("\n", "v1", "admin", captured.subject(), captured.timestamp(), captured.nonce(), "POST", captured.path(), digest);
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(bytes(SECRET), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(bytes(canonical)));
    }

    @AfterAll static void cleanup() throws InterruptedException {
        HTTP.shutdownNow();
        UPSTREAM.server.stop(0);
        UPSTREAM.executor.shutdownNow();
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean httpStopped = HTTP.awaitTermination(Duration.ofNanos(Math.max(1, until - System.nanoTime())));
        boolean serverStopped = UPSTREAM.executor.awaitTermination(Math.max(1, until - System.nanoTime()), TimeUnit.NANOSECONDS);
        assertAll(() -> assertTrue(httpStopped), () -> assertTrue(serverStopped),
                () -> assertTrue(UPSTREAM.failures.isEmpty(), () -> "自有上游异常：" + UPSTREAM.failures));
    }

    private record Reply(int status, byte[] body) {}
    private record Captured(String path, byte[] body, String subject, String timestamp, String nonce, String signature) {}
    private static final class Upstream {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newFixedThreadPool(2);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Reply> response = new AtomicReference<>(new Reply(200, bytes("{}")));
        final LinkedBlockingQueue<Captured> requests = new LinkedBlockingQueue<>();
        final ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
        Upstream() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(executor);
                server.createContext("/", this::handle);
                server.start();
            } catch (IOException | RuntimeException failure) {
                executor.shutdownNow();
                throw new ExceptionInInitializerError(failure);
            }
        }
        String baseUrl() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/mall/rpc/"; }
        void handle(HttpExchange exchange) {
            try (exchange) {
                var headers = exchange.getRequestHeaders();
                calls.incrementAndGet();
                requests.add(new Captured(exchange.getRequestURI().getRawPath(), exchange.getRequestBody().readAllBytes(),
                        headers.getFirst("X-Commerce-Subject"), headers.getFirst("X-Commerce-Timestamp"),
                        headers.getFirst("X-Commerce-Nonce"), headers.getFirst("X-Commerce-Signature")));
                var reply = response.get();
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(reply.status(), reply.body().length);
                exchange.getResponseBody().write(reply.body());
            } catch (Throwable failure) { failures.add(failure); }
        }
    }
}
