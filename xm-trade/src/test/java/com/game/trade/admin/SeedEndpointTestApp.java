package com.game.trade.admin;

import com.game.common.RunMode;
import com.game.proto.trade.SeedListingRequest;
import com.game.proto.trade.SeedListingResponse;
import com.game.trade.TradeProperties;
import com.game.trade.service.SeedListingService;
import com.game.trade.service.TradeServiceFixture;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 播种接口端到端测试的最小 Spring 上下文：真的内嵌 Tomcat + DispatcherServlet，只装 {@link SeedListingController} 与
 * {@link TradeAdminAuthFilter}（与生产同样只注册在 {@code /admin/*}，令牌 {@value #TOKEN}），服务层用假存储 / 假归属区 / 假发号
 * （{@link TradeServiceFixture}）。运行模式由属性 {@code test.run-mode} 决定。
 */
@Configuration(proxyBeanMethods = false)
@ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
@Import(SeedListingController.class)
class SeedEndpointTestApp {

    static final String TOKEN = "secret-token";
    static final String OPERATOR = "xm-robot";

    @Bean
    TradeServiceFixture tradeServiceFixture() {
        return new TradeServiceFixture();
    }

    @Bean
    SeedListingService seedListingService(TradeServiceFixture fixture, @Value("${test.run-mode}") String runMode) {
        return fixture.seeds(RunMode.parse(runMode));
    }

    @Bean
    TradeProperties tradeProperties() {
        return new TradeProperties(new TradeProperties.Market("zone", null, null, null, null), null, null, null, null);
    }

    @Bean
    FilterRegistrationBean<TradeAdminAuthFilter> tradeAdminAuthFilter(TradeServiceFixture fixture) {
        FilterRegistrationBean<TradeAdminAuthFilter> registration =
                new FilterRegistrationBean<>(new TradeAdminAuthFilter(TOKEN, fixture.metrics));
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    /** 发一次原始 HTTP 请求（路径原样发出，不经 URI 规范化）。 */
    static HttpResponse<byte[]> post(int port, String rawPath, String token, String operator, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", SeedListingController.CONTENT_TYPE)
                .header("Accept", SeedListingController.CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            request.header(TradeAdminAuthFilter.TOKEN_HEADER, token);
        }
        if (operator != null) {
            request.header(TradeAdminAuthFilter.OPERATOR_HEADER, operator);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static HttpResponse<byte[]> seed(int port, SeedListingRequest request) throws IOException, InterruptedException {
        return post(port, SeedListingController.PATH, TOKEN, OPERATOR, request.toByteArray());
    }

    static SeedListingResponse parse(HttpResponse<byte[]> response) throws IOException {
        return SeedListingResponse.parseFrom(response.body());
    }
}
