package com.game.battle.admin;

import com.game.battle.admission.AdmissionGate;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.battle.rpc.BattleNodeServiceImpl;
import com.game.battle.testing.StubBattleRoomService;
import com.game.common.RunMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.DefaultEventLoop;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ThreadFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * dev 管理接口端到端测试的最小 Spring 上下文：真的内嵌 Tomcat + DispatcherServlet，只装 {@link DevBattleController} 与
 * {@link BattleAdminAuthFilter}（同生产只注册在 {@code /admin/*}，令牌 {@value #TOKEN}）。控制面是真的 {@link BattleNodeServiceImpl}
 * （真单线程逻辑 EventLoop、准入闸已开），房间服务用桩，路由读用 {@link FakeLookups}。运行模式由属性 {@code test.run-mode} 决定。
 *
 * <p>故意<b>不</b>标 {@code @Configuration}（按 lite 模式处理 {@code @Bean} 与 {@code @Import}）：否则整进程测试
 * （{@code BattleApplicationIntegrationTest}）的组件扫描会把它扫进去；也不能标 {@code @TestConfiguration}——只给它时 Spring Boot 会转而去找
 * {@code BattleApplication}，装出整个进程。
 */
@ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
@Import(DevBattleController.class)
class DevBattleTestApp {

    static final String TOKEN = "battle-admin-token";
    static final String OPERATOR = "xm-robot";

    /** 可切换的控制面入口（模拟节点没在运行、换一个准入闸已关的控制面）。 */
    static final class SwitchableBackend implements DevBattleBackend {
        volatile BattleNodeServiceImpl plane;
        volatile boolean running = true;

        @Override
        public Optional<BattleNodeServiceImpl> controlPlane() {
            return running ? Optional.ofNullable(plane) : Optional.empty();
        }
    }

    @Bean
    StubBattleRoomService stubRooms() {
        return new StubBattleRoomService();
    }

    @Bean(destroyMethod = "shutdownGracefully")
    DefaultEventLoop testLogicLoop() {
        return new DefaultEventLoop((ThreadFactory) r -> new Thread(r, "test-battle-logic"));
    }

    @Bean
    AdmissionGate testAdmission() {
        AdmissionGate gate = new AdmissionGate();
        gate.open();
        return gate;
    }

    @Bean
    BattleMetrics testMetrics() {
        return new BattleMetrics(new SimpleMeterRegistry());
    }

    @Bean
    SwitchableBackend devBattleBackend(AdmissionGate testAdmission, DefaultEventLoop testLogicLoop, StubBattleRoomService stubRooms,
                                       BattleMetrics testMetrics) {
        EventLoopBattleScheduler scheduler = new EventLoopBattleScheduler(testLogicLoop);
        stubRooms.loop = scheduler;
        SwitchableBackend backend = new SwitchableBackend();
        backend.plane = new BattleNodeServiceImpl(testAdmission, scheduler, stubRooms, Runnable::run, 8, testMetrics);
        return backend;
    }

    @Bean
    FakeLookups fakeLookups() {
        return new FakeLookups();
    }

    @Bean
    DevRoutingResolver devRoutingResolver(FakeLookups fakeLookups) {
        return new DevRoutingResolver(fakeLookups);
    }

    @Bean
    RunMode testRunMode(@Value("${test.run-mode}") String runMode) {
        return RunMode.parse(runMode);
    }

    @Bean
    FilterRegistrationBean<BattleAdminAuthFilter> battleAdminAuthFilter() {
        FilterRegistrationBean<BattleAdminAuthFilter> registration = new FilterRegistrationBean<>(new BattleAdminAuthFilter(TOKEN));
        registration.addUrlPatterns("/admin/*");
        return registration;
    }

    /** 发一次原始 HTTP 请求（路径原样发出）。 */
    static HttpResponse<byte[]> post(int port, String rawPath, String token, String operator, byte[] body)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + rawPath))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", DevBattleController.CONTENT_TYPE)
                .header("Accept", DevBattleController.CONTENT_TYPE + ", text/plain")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (token != null) {
            request.header(BattleAdminAuthFilter.TOKEN_HEADER, token);
        }
        if (operator != null) {
            request.header(BattleAdminAuthFilter.OPERATOR_HEADER, operator);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    static HttpResponse<byte[]> post(int port, String path, byte[] body) throws IOException, InterruptedException {
        return post(port, path, TOKEN, OPERATOR, body);
    }
}
