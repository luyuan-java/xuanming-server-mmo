package com.game.battle.admin;

import com.game.battle.admission.AdmissionGate;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.battle.rpc.BattleNodeServiceImpl;
import com.game.battle.testing.StubBattleRoomService;
import com.game.common.RunMode;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.DefaultEventLoop;
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
import org.springframework.core.env.Environment;

/**
 * dev gather 接口端到端测试的最小 Spring 上下文（同 {@link DevBattleTestApp} 的做法）：真的内嵌 Tomcat + DispatcherServlet，只装
 * {@link DevGatherController} 与 {@link BattleAdminAuthFilter}。控制面是真的 {@link BattleNodeServiceImpl}（真单线程逻辑 EventLoop、准入闸已开），
 * 房间服务用桩；scene 侧用 {@link DevGatherTest.FakeScenes}。运行模式由属性 {@code test.run-mode} 决定。
 *
 * <p>故意<b>不</b>标 {@code @Configuration}：理由同 {@link DevBattleTestApp}。
 */
@ImportAutoConfiguration({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
        WebMvcAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
@Import(DevGatherController.class)
class DevGatherTestApp {

    static final int NODE_ID = 9;

    /** 可切换的节点（模拟节点没在运行）。 */
    static final class SwitchableNode implements DevGatherController.Node {
        volatile BattleNodeServiceImpl plane;
        volatile boolean running = true;

        @Override
        public Optional<DevGatherController.Running> running() {
            return running && plane != null ? Optional.of(new DevGatherController.Running(plane, NODE_ID)) : Optional.empty();
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
    SimpleMeterRegistry testMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    BattleMetrics testMetrics(SimpleMeterRegistry testMeterRegistry) {
        return new BattleMetrics(testMeterRegistry);
    }

    @Bean
    SwitchableNode devGatherNode(DefaultEventLoop testLogicLoop, StubBattleRoomService stubRooms, BattleMetrics testMetrics) {
        AdmissionGate gate = new AdmissionGate();
        gate.open();
        EventLoopBattleScheduler scheduler = new EventLoopBattleScheduler(testLogicLoop);
        stubRooms.loop = scheduler;
        SwitchableNode node = new SwitchableNode();
        node.plane = new BattleNodeServiceImpl(gate, scheduler, stubRooms, Runnable::run, 8, testMetrics);
        return node;
    }

    @Bean
    DevGatherTest.FakeScenes fakeScenes() {
        return new DevGatherTest.FakeScenes();
    }

    @Bean
    DevGather devGather(DevGatherTest.FakeScenes fakeScenes) {
        return new DevGather(fakeScenes, System::currentTimeMillis);
    }

    @Bean
    RunMode testRunMode(@Value("${test.run-mode}") String runMode) {
        return RunMode.parse(runMode);
    }

    /**
     * 运维令牌缺省是 {@link DevBattleTestApp#TOKEN}；属性 {@code test.admin-token} 设成空串 = 模拟生产上没配 {@code XM_ADMIN_TOKEN}（一律 503）。
     * 直接读 {@link Environment}，不用 {@code @Value}：过滤器注册 bean 在内嵌容器启动时就创建，那时这个最小上下文里还没有占位符解析器。
     */
    @Bean
    FilterRegistrationBean<BattleAdminAuthFilter> battleAdminAuthFilter(Environment environment) {
        String token = environment.getProperty("test.admin-token", DevBattleTestApp.TOKEN);
        FilterRegistrationBean<BattleAdminAuthFilter> registration = new FilterRegistrationBean<>(new BattleAdminAuthFilter(token));
        registration.addUrlPatterns("/admin/*");
        return registration;
    }
}
