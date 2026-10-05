package com.game.data.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.game.data.DataProperties;
import com.game.data.metrics.DataMetrics;
import com.game.data.query.TransactionLogQueryService;
import com.game.data.recall.RecallPlanner;
import com.game.data.snapshot.SnapshotAdminService;
import com.game.data.snapshot.SnapshotDiffService;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 在真的内嵌 Tomcat 上发「绕过形」路径：{@code /admin;x=1/...}、{@code /%61dmin/...} 等经容器规范化后仍是 /admin/**，
 * 必须照样要令牌（手工构造的 Mock 请求复现不了容器的解码与 ;参数 处理）。
 */
@SpringBootTest(classes = AdminEndpointSecurityTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "server.address=127.0.0.1")
class AdminEndpointSecurityTest {

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class, JacksonAutoConfiguration.class})
    // gate 排空运维接口：上下文里没有 Redis 客户端也能起来（Redis 懒加载，第一次调用这些接口时才建）
    @Import({AuditQueryController.class, GateDrainAdminController.class, SnapshotAdminController.class,
            PlayerOpsAdminController.class, ItemAdminController.class, RecallAdminController.class, OpsErrorAdvice.class})
    static class App {

        @Bean
        TransactionLogQueryService transactionLogQueryService() {
            return new TransactionLogQueryService(mock(TransactionLogMapper.class), Duration.ofDays(7));
        }

        @Bean
        SnapshotAdminService snapshotAdminService() {
            return mock(SnapshotAdminService.class);
        }

        @Bean
        SnapshotDiffService snapshotDiffService() {
            return mock(SnapshotDiffService.class);
        }

        @Bean
        RecallPlanner recallPlanner() {
            return mock(RecallPlanner.class);
        }

        @Bean
        DataProperties dataProperties() {
            return new Binder(new MapConfigurationPropertySource(Map.of())).bindOrCreate("xm.data", DataProperties.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        PlayerSnapshotMapper playerSnapshotMapper() {
            return mock(PlayerSnapshotMapper.class);
        }

        @Bean
        FilterRegistrationBean<AdminAuthFilter> adminAuthFilter() {
            FilterRegistrationBean<AdminAuthFilter> registration = new FilterRegistrationBean<>(
                    new AdminAuthFilter("secret", new DataMetrics(new SimpleMeterRegistry())));
            registration.addUrlPatterns("/admin/*");
            return registration;
        }
    }

    @LocalServerPort
    int port;

    private int status(String rawPath, boolean withToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath)).GET();
        if (withToken) {
            request.header(AdminAuthFilter.TOKEN_HEADER, "secret").header(AdminAuthFilter.OPERATOR_HEADER, "ops");
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void 绕过形路径没有令牌一律401() throws Exception {
        assertThat(status("/admin/transaction-log?player=1", false)).isEqualTo(401);
        assertThat(status("/admin;x=1/transaction-log?player=1", false)).isEqualTo(401);
        assertThat(status("/%61dmin/transaction-log?player=1", false)).isEqualTo(401);
        assertThat(status("/adm%69n/transaction-log?player=1", false)).isEqualTo(401);
        assertThat(status("/admin/transaction-log;y=2?player=1", false)).isEqualTo(401);
        assertThat(status("/admin/player-snapshots?player=1", false)).isEqualTo(401);
        assertThat(status("/%61dmin/player-snapshots?player=1", false)).isEqualTo(401);
        assertThat(status("/%61dmin/gates/1", false)).as("gate 排空运维接口同样要令牌").isEqualTo(401);
        // 批次 7.2a 的运维面（T-J1 的鉴权部分）
        assertThat(status("/admin/player-snapshots/1", false)).isEqualTo(401);
        assertThat(status("/%61dmin/players/1/snapshot-diff?atMs=1", false)).isEqualTo(401);
        assertThat(status("/admin;x=1/items/1/trace", false)).isEqualTo(401);
        assertThat(post("/admin/recalls", false)).isEqualTo(401);
        assertThat(post("/admin/player-snapshots", false)).isEqualTo(401);
    }

    @Test
    void 带令牌放行() throws Exception {
        assertThat(status("/admin/transaction-log?player=1", true)).isEqualTo(200);
        assertThat(status("/admin/player-snapshots?player=1", true)).isEqualTo(200);
        assertThat(status("/admin/items/1/trace", true)).isEqualTo(200);
        assertThat(post("/admin/recalls", true)).as("过了鉴权，请求体校验回 400").isEqualTo(400);
        assertThat(post("/admin/player-snapshots", true)).as("过了鉴权，缺幂等键回 400").isEqualTo(400);
    }

    private int post(String rawPath, boolean withToken) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{}"));
        if (withToken) {
            request.header(AdminAuthFilter.TOKEN_HEADER, "secret").header(AdminAuthFilter.OPERATOR_HEADER, "ops");
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
