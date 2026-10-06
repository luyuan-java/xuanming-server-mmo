package com.game.match.admin;

import com.game.common.RunMode;
import com.game.common.id.Snowflake;
import com.game.match.MatchProperties;
import com.game.match.activity.ActivityBattleService;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakeMemberPrecheck;
import com.game.match.testing.InMemoryTicketStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * dev 活动开战接口端到端测试的最小 Spring 上下文：真的内嵌 Tomcat + DispatcherServlet，只装 {@link DevActivityBattleController}；
 * 服务层是真的 {@link ActivityBattleService}，接在替身的预检、内存票据存储与假的开局管线上。运行模式由属性 {@code test.run-mode} 决定。
 * 管理口的令牌过滤器不在这个上下文里（它属于进程装配，有自己的测试）；这里钉的是控制器自己的那几条状态。
 *
 * <p>故意<b>不</b>标 {@code @Configuration}：xm-match 的整上下文测试会组件扫描 {@code com.game.match} 下的测试类，标了会被扫进别人的上下文。
 */
@ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
@Import(DevActivityBattleController.class)
class DevActivityBattleTestApp {

    static final String OPERATOR = "xm-robot";

    @Bean
    InMemoryTicketStore testTickets() {
        return new InMemoryTicketStore();
    }

    @Bean
    FakeMemberPrecheck testPrecheck() {
        return new FakeMemberPrecheck();
    }

    @Bean
    FakeGatherLauncher testGather() {
        return new FakeGatherLauncher();
    }

    @Bean
    SimpleMeterRegistry testMeters() {
        return new SimpleMeterRegistry();
    }

    @Bean
    MatchMetrics testMetrics(SimpleMeterRegistry testMeters) {
        return new MatchMetrics(testMeters, new MetricLabels(id -> false));
    }

    @Bean
    ActivityBattleService testActivityBattles(FakeMemberPrecheck precheck, InMemoryTicketStore tickets, FakeGatherLauncher gather, MatchMetrics metrics) {
        return new ActivityBattleService(precheck, tickets, gather, new MatchIds(new Snowflake(5), () -> true, () -> false), metrics);
    }

    @Bean
    RunMode testRunMode(@Value("${test.run-mode}") String runMode) {
        return RunMode.parse(runMode);
    }

    @Bean
    MatchProperties testMatchProperties() {
        return new MatchProperties(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 发一次原始 HTTP 请求；{@code operator} 为 null 时不带操作人头。 */
    static HttpResponse<byte[]> post(int port, String operator, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + DevActivityBattleController.PATH))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", DevActivityBattleController.CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (operator != null) {
            request.header(DevActivityBattleController.OPERATOR_HEADER, operator);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
