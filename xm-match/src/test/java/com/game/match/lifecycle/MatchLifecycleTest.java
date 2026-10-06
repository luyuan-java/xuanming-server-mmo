package com.game.match.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.Ordered;

/**
 * 启停次序（match-spec §9.8）：凑单与评分消费等「应用已启动」才起，凑单在前；停机先停凑单（上下文关闭事件，排在 Dubbo 撤导出之前），
 * 再在 {@code SmartLifecycle.stop} 里排空工作池 → 有界等在途 gather → 停评分消费。每一步幂等，停机路径上任何一步出错都不挡后面的步骤。
 * 这里直接驱动回调，钉次序与边界；挂点相对 Dubbo 导出 / 撤导出的真实先后由起整个进程的 {@code MatchRpcLoopbackTest} 钉。
 */
@ExtendWith(OutputCaptureExtension.class)
class MatchLifecycleTest {

    private final List<String> events = new CopyOnWriteArrayList<>();
    private volatile RuntimeException matcherStartError;
    private volatile RuntimeException matcherStopError;
    private volatile RuntimeException consumerStartError;
    private volatile RuntimeException consumerStopError;
    private volatile RuntimeException drainError;
    private volatile RuntimeException awaitError;
    private volatile boolean gathersIdle = true;

    private final MatcherControl matcher = new MatcherControl() {
        @Override
        public void start() {
            events.add("matcher.start");
            throwIf(matcherStartError);
        }

        @Override
        public void stop() {
            events.add("matcher.stop");
            throwIf(matcherStopError);
        }
    };

    private final ResultConsumerControl consumer = new ResultConsumerControl() {
        @Override
        public void start() {
            events.add("consumer.start");
            throwIf(consumerStartError);
        }

        @Override
        public void stop() {
            events.add("consumer.stop");
            throwIf(consumerStopError);
        }
    };

    private final GatherLauncher gathers = new GatherLauncher() {
        @Override
        public CompletableFuture<GatherResult> launch(GatherPlan plan) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int availablePermits() {
            return 0;
        }

        @Override
        public boolean awaitIdle(Duration timeout) {
            events.add("gathers.awaitIdle(" + timeout.toSeconds() + "s)");
            throwIf(awaitError);
            return gathersIdle;
        }
    };

    private final Runnable drainWorkers = () -> {
        events.add("workers.drain");
        throwIf(drainError);
    };

    private static void throwIf(RuntimeException error) {
        if (error != null) {
            throw error;
        }
    }

    private MatchLifecycle lifecycle() {
        return new MatchLifecycle(matcher, consumer, gathers, drainWorkers, MatchLifecycle.GATHER_DRAIN_TIMEOUT);
    }

    private static ApplicationStartedEvent started(GenericApplicationContext context) {
        return new ApplicationStartedEvent(new SpringApplication(), new String[0], context, Duration.ZERO);
    }

    // ================================================================ 启动

    @Test
    void 容器启动生命周期时还不起后台件_等应用已启动事件才起_凑单在评分消费之前() {
        MatchLifecycle lifecycle = lifecycle();
        GenericApplicationContext context = new GenericApplicationContext();
        lifecycle.setApplicationContext(context);

        lifecycle.start();
        assertThat(lifecycle.isRunning()).as("登记为运行中：容器关闭时才会来调 stop").isTrue();
        assertThat(events).as("Dubbo 还没导出：不起凑单").isEmpty();
        assertThat(lifecycle.backgroundStarted()).isFalse();

        lifecycle.onApplicationEvent(started(context));

        assertThat(events).containsExactly("matcher.start", "consumer.start");
        assertThat(lifecycle.backgroundStarted()).isTrue();
    }

    @Test
    void 只认两种事件_应用已启动与上下文关闭_别的事件与别的上下文的事件都不理() {
        MatchLifecycle lifecycle = lifecycle();
        GenericApplicationContext mine = new GenericApplicationContext();
        GenericApplicationContext other = new GenericApplicationContext();
        lifecycle.setApplicationContext(mine);

        assertThat(lifecycle.supportsEventType(ApplicationStartedEvent.class)).isTrue();
        assertThat(lifecycle.supportsEventType(ContextClosedEvent.class)).isTrue();
        assertThat(lifecycle.supportsEventType(ContextRefreshedEvent.class)).as("刷新完成事件那一刻 Dubbo 可能还没导出").isFalse();
        assertThat(lifecycle.supportsEventType(ApplicationReadyEvent.class)).isFalse();

        lifecycle.onApplicationEvent(started(other));
        lifecycle.onApplicationEvent(new ContextClosedEvent(other));
        assertThat(events).as("子上下文 / 别的上下文的事件").isEmpty();

        lifecycle.onApplicationEvent(started(mine));
        lifecycle.onApplicationEvent(new ContextClosedEvent(other));
        assertThat(events).containsExactly("matcher.start", "consumer.start");
    }

    @Test
    void 应用已启动事件来两次_只启动一次() {
        MatchLifecycle lifecycle = lifecycle();

        lifecycle.startBackground();
        lifecycle.startBackground();

        assertThat(events).containsExactly("matcher.start", "consumer.start");
    }

    @Test
    void 凑单启动失败_异常原样抛出拒绝启动_评分消费不起_随后的停机只停起过的() {
        matcherStartError = new IllegalStateException("调度器起不来");
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();

        assertThatThrownBy(lifecycle::startBackground).isSameAs(matcherStartError);
        lifecycle.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()));
        lifecycle.stop();

        assertThat(events).containsExactly("matcher.start", "matcher.stop", "workers.drain", "gathers.awaitIdle(10s)");
    }

    @Test
    void 评分消费启动失败_异常原样抛出_停机时两样都停() {
        consumerStartError = new IllegalStateException("代次非法");
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();

        assertThatThrownBy(lifecycle::startBackground).isSameAs(consumerStartError);
        lifecycle.stop();

        assertThat(events).containsExactly("matcher.start", "consumer.start", "matcher.stop", "workers.drain", "gathers.awaitIdle(10s)",
                "consumer.stop");
    }

    // ================================================================ 停机

    @Test
    void 停机次序_关闭事件里只停凑单_stop里排空工作池再等gather再停评分消费() {
        MatchLifecycle lifecycle = lifecycle();
        GenericApplicationContext context = new GenericApplicationContext();
        lifecycle.setApplicationContext(context);
        lifecycle.start();
        lifecycle.onApplicationEvent(started(context));
        events.clear();

        lifecycle.onApplicationEvent(new ContextClosedEvent(context));
        assertThat(events).as("第 1 步：Dubbo 撤导出之前只停凑单；评分消费与工作池还在").containsExactly("matcher.stop");
        assertThat(lifecycle.isRunning()).isTrue();

        lifecycle.stop();
        assertThat(events).containsExactly("matcher.stop", "workers.drain", "gathers.awaitIdle(10s)", "consumer.stop");
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void 每一步都只做一次_关闭事件与stop重复到达也一样() {
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();
        lifecycle.startBackground();
        events.clear();

        lifecycle.stopMatcher();
        lifecycle.stopMatcher();
        lifecycle.stop();
        lifecycle.stop();
        lifecycle.stopMatcher();

        assertThat(events).containsExactly("matcher.stop", "workers.drain", "gathers.awaitIdle(10s)", "consumer.stop");
    }

    @Test
    void 没收到关闭事件就被stop_先补停凑单再走后面的步骤() {
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();
        lifecycle.startBackground();
        events.clear();

        lifecycle.stop();

        assertThat(events).containsExactly("matcher.stop", "workers.drain", "gathers.awaitIdle(10s)", "consumer.stop");
    }

    @Test
    void 后台件从没起过_启动中途失败_停机不去停它们_但仍排空工作池与等gather() {
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();

        lifecycle.stopMatcher();
        lifecycle.stop();

        assertThat(events).containsExactly("workers.drain", "gathers.awaitIdle(10s)");
    }

    @Test
    void 停机开始之后再来启动事件_不再起凑单() {
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();

        lifecycle.stopMatcher();
        lifecycle.startBackground();

        assertThat(events).isEmpty();
        assertThat(lifecycle.backgroundStarted()).isFalse();
    }

    @Test
    void 在途gather等不完_告警后放弃_照常停评分消费(CapturedOutput output) {
        gathersIdle = false;
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();
        lifecycle.startBackground();
        events.clear();

        lifecycle.stop();

        assertThat(events).containsExactly("matcher.stop", "workers.drain", "gathers.awaitIdle(10s)", "consumer.stop");
        assertThat(output.getOut()).contains("仍有在途 gather，放弃等待").contains("matched TTL 自愈");
    }

    @Test
    void 停机路径上任何一步抛异常_只记日志_后面的步骤照做(CapturedOutput output) {
        matcherStopError = new IllegalStateException("凑单停不下来");
        drainError = new IllegalStateException("工作池出错");
        awaitError = new IllegalStateException("等待出错");
        consumerStopError = new IllegalStateException("消费者停不下来");
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();
        lifecycle.startBackground();
        events.clear();

        lifecycle.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()));
        lifecycle.stop();

        assertThat(events).containsExactly("matcher.stop", "workers.drain", "gathers.awaitIdle(10s)", "consumer.stop");
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(output.getOut()).contains("停凑单出错").contains("排空 match-worker 出错").contains("等在途 gather 出错").contains("停评分消费出错");
    }

    @Test
    void 开局管线没接入_停机不等gather_其余照做() {
        MatchLifecycle lifecycle = new MatchLifecycle(matcher, consumer, null, drainWorkers, Duration.ofSeconds(10));
        lifecycle.start();
        lifecycle.startBackground();
        events.clear();

        lifecycle.stop();

        assertThat(events).containsExactly("matcher.stop", "workers.drain", "consumer.stop");
    }

    @Test
    void 等gather的上限取构造时给的值_生产是10秒() {
        MatchLifecycle lifecycle = new MatchLifecycle(matcher, consumer, gathers, drainWorkers, Duration.ofSeconds(3));
        lifecycle.start();

        lifecycle.stop();

        assertThat(events).contains("gathers.awaitIdle(3s)");
        assertThat(MatchLifecycle.GATHER_DRAIN_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }

    // ================================================================ 挂点的次序

    @Test
    void 监听器的次序排在Dubbo的监听器之前_生命周期相位最高_最后启动最先停止() {
        MatchLifecycle lifecycle = lifecycle();

        assertThat(lifecycle.getOrder()).as("Dubbo 的 DubboDeployApplicationListener 是 LOWEST_PRECEDENCE").isLessThan(Ordered.LOWEST_PRECEDENCE);
        assertThat(lifecycle.getOrder()).isEqualTo(MatchLifecycle.LISTENER_ORDER);
        assertThat(lifecycle.getPhase()).isEqualTo(SmartLifecycle.DEFAULT_PHASE).isEqualTo(Integer.MAX_VALUE);
        assertThat(lifecycle.isAutoStartup()).isTrue();
    }

    // ================================================================ 未接入时的替身

    @Test
    void 未接入的替身_启动时各告警一次_停止什么都不做(CapturedOutput output) {
        MatchLifecycle lifecycle = new MatchLifecycle(MatchLifecycle.matcherNotReady(), MatchLifecycle.consumerNotReady(), null, drainWorkers,
                Duration.ofSeconds(10));
        lifecycle.start();

        lifecycle.startBackground();
        lifecycle.stopMatcher();
        lifecycle.stop();

        assertThat(output.getOut()).contains("凑单尚未接入").contains("评分消费尚未接入");
        assertThat(events).containsExactly("workers.drain");
    }
}
