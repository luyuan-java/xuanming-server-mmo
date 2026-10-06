package com.game.data.ops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.data.metrics.DataMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * 启停次序（data-ops-spec §7.4「停服」）：Spring 按生命周期阶段<b>从小到大启动、从大到小停止</b>。阶段值：Web 服务器（优雅停机
 * {@code DEFAULT_PHASE − 1024}、启停 {@code DEFAULT_PHASE − 2048}）&gt; {@link OpsJobRunner}（{@code OpsIds.PHASE + 1}）&gt;
 * {@link OpsIds}（{@code DEFAULT_PHASE − 4096}）。所以停机时 Web 服务器先停（不再受理）→ 作业执行器 → 最后交还发号租约。
 *
 * <p>不只比阶段值：起一个真的内嵌 Web 服务器，把两个只记账的生命周期 bean 放在 {@link OpsJobRunner} / {@link OpsIds} 实际报出的阶段上，
 * 记下它们被启动 / 停止的那一刻 Web 端口还通不通。
 */
class OpsLifecycleOrderTest {

    /** 只记账：被启动 / 停止时记一行「名字.动作 web=端口此刻是否在听」。 */
    private static final class Recorder implements SmartLifecycle {
        private final String name;
        private final int phase;
        private final List<String> log;
        private final BooleanSupplier webListening;
        private volatile boolean running;

        Recorder(String name, int phase, List<String> log, BooleanSupplier webListening) {
            this.name = name;
            this.phase = phase;
            this.log = log;
            this.webListening = webListening;
        }

        @Override
        public void start() {
            running = true;
            log.add(name + ".start web=" + webListening.getAsBoolean());
        }

        @Override
        public void stop() {
            running = false;
            log.add(name + ".stop web=" + webListening.getAsBoolean());
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public int getPhase() {
            return phase;
        }
    }

    private static boolean canConnect(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** 一个此刻空闲的本机端口（先占后放）。 */
    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    @Test
    void 停机时Web服务器先停_作业执行器其次_发号租约最后_启动次序相反() throws Exception {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        // 真实对象只用来报阶段值（不 start）：号源永远领不到、执行器不碰库
        OpsIds ids = new OpsIds(onLost -> {
            throw new IllegalStateException("本用例不申领租约");
        }, scheduler, System::currentTimeMillis, Duration.ofMinutes(1));
        OpsJobRunner runner = new OpsJobRunner(null, null, new ObjectMapper(), new DataMetrics(new SimpleMeterRegistry()),
                Clock.systemUTC(), "phase-test", Duration.ofSeconds(5), Duration.ofSeconds(60), Duration.ofMinutes(30),
                scheduler);
        AnnotationConfigServletWebServerApplicationContext context = new AnnotationConfigServletWebServerApplicationContext();
        try {
            assertThat(ids.getPhase()).isEqualTo(SmartLifecycle.DEFAULT_PHASE - 4096);
            assertThat(runner.getPhase()).isEqualTo(ids.getPhase() + 1);
            // Web 服务器的两个生命周期 bean（启停比优雅停机低 1024）都比作业执行器的阶段大
            int webStartStopPhase = WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 1024;
            assertThat(webStartStopPhase).isGreaterThan(runner.getPhase());

            List<String> log = new CopyOnWriteArrayList<>();
            // 端口事先挑好、直接探它：不能在 Web 服务器启动之前问 WebServer.getPort()——内嵌 Tomcat 这时没有连接器，
            // 一问就会临时造一个缺省的 8080 连接器并启动它
            int port = freePort();
            BooleanSupplier webListening = () -> canConnect(port);
            context.registerBean("webServerFactory", ServletWebServerFactory.class, () -> {
                TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(port);
                factory.setAddress(InetAddress.getLoopbackAddress());
                return factory;
            });
            context.registerBean("atOpsIdsPhase", SmartLifecycle.class,
                    () -> new Recorder("ids", ids.getPhase(), log, webListening));
            context.registerBean("atOpsJobRunnerPhase", SmartLifecycle.class,
                    () -> new Recorder("runner", runner.getPhase(), log, webListening));

            context.refresh();
            // 启动：发号租约 → 作业执行器 → Web 服务器（两者启动时端口都还没开始听）
            assertThat(log).containsExactly("ids.start web=false", "runner.start web=false");
            assertThat(webListening.getAsBoolean()).as("启动完成后 Web 端口在听").isTrue();

            context.close();
            // 停机：Web 服务器已经停了（端口不通）才轮到作业执行器，最后才是发号租约
            assertThat(log).containsExactly("ids.start web=false", "runner.start web=false", "runner.stop web=false",
                    "ids.stop web=false");
        } finally {
            context.close();
            runner.stop();
            ids.close();
            scheduler.shutdownNow();
        }
    }
}
