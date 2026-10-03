package com.game.data.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.game.data.metrics.DataMetrics;
import com.game.data.store.TransactionLogMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
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
    @Import(AuditQueryController.class)
    static class App {

        @Bean
        TransactionLogMapper transactionLogMapper() {
            return mock(TransactionLogMapper.class);
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
    }

    @Test
    void 带令牌放行() throws Exception {
        assertThat(status("/admin/transaction-log?player=1", true)).isEqualTo(200);
    }
}
