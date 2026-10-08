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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // ================================================================ 启动线程与关停线程同时在场（刚启动完就收到 SIGTERM）

    private static void awaitRelease(CountDownLatch release) {
        try {
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("测试没有放行");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Thread daemon(String name, Runnable body) {
        return Thread.ofPlatform().name(name).daemon(true).start(body);
    }

    /** 等线程走到「不再前进」的地方：卡在锁上 / 等待中，或者已经跑完。 */
    private static boolean awaitParkedOrDone(Thread thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.BLOCKED || state == Thread.State.WAITING || state == Thread.State.TERMINATED) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(5);
        }
        return false;
    }

    @Test
    void 启动线程正在起凑单时停机到达_停凑单排在它起完之后_停机结束时凑单是停着的() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AtomicBoolean matcherRunning = new AtomicBoolean();
        MatcherControl slowStart = new MatcherControl() {
            @Override
            public void start() {
                events.add("matcher.start:enter");
                startEntered.countDown();
                awaitRelease(releaseStart);
                matcherRunning.set(true);
                events.add("matcher.start:exit");
            }

            @Override
            public void stop() {
                matcherRunning.set(false);
                events.add("matcher.stop");
            }
        };
        MatchLifecycle lifecycle = new MatchLifecycle(slowStart, consumer, gathers, drainWorkers, MatchLifecycle.GATHER_DRAIN_TIMEOUT);
        lifecycle.start();

        ContextClosedEvent closed = new ContextClosedEvent(new GenericApplicationContext());
        Thread startup = daemon("test-startup", lifecycle::startBackground);
        assertThat(startEntered.await(10, TimeUnit.SECONDS)).isTrue();
        Thread shutdown = daemon("test-shutdown", () -> {
            lifecycle.onApplicationEvent(closed);
            lifecycle.stop();
        });
        // 关停线程此刻要么排在启动线程后面等着，要么（没有串行化的写法）已经把「还没起完」的凑单停了个空
        assertThat(awaitParkedOrDone(shutdown)).as("关停线程已经走到停凑单这一步").isTrue();
        releaseStart.countDown();
        startup.join(10_000);
        shutdown.join(10_000);

        assertThat(startup.isAlive()).isFalse();
        assertThat(shutdown.isAlive()).isFalse();
        assertThat(matcherRunning).as("停机结束后凑单必须是停着的：否则它会在 Dubbo 撤导出、工作池排空期间继续弹组").isFalse();
        assertThat(events).as("停在起完之后").containsSubsequence("matcher.start:enter", "matcher.start:exit", "matcher.stop");
        assertThat(events).filteredOn("matcher.stop"::equals).hasSize(1);
        if (events.contains("consumer.start")) {
            assertThat(events).as("评分消费若抢在停机前起来了，也得被停掉").containsSubsequence("consumer.start", "consumer.stop");
        }
        assertThat(events).contains("workers.drain", "gathers.awaitIdle(10s)");
    }

    @Test
    void 评分消费的启动卡在核对topic时停机到达_停机各步不等它_它起完之后被补停() throws Exception {
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AtomicBoolean consumerRunning = new AtomicBoolean();
        ResultConsumerControl slowStart = new ResultConsumerControl() {
            @Override
            public void start() {
                events.add("consumer.start:enter");
                startEntered.countDown();
                awaitRelease(releaseStart);
                consumerRunning.set(true);
                events.add("consumer.start:exit");
            }

            @Override
            public void stop() {
                consumerRunning.set(false);
                events.add("consumer.stop");
            }
        };
        MatchLifecycle lifecycle = new MatchLifecycle(matcher, slowStart, gathers, drainWorkers, MatchLifecycle.GATHER_DRAIN_TIMEOUT);
        lifecycle.start();

        ContextClosedEvent closed = new ContextClosedEvent(new GenericApplicationContext());
        Thread startup = daemon("test-startup", lifecycle::startBackground);
        assertThat(startEntered.await(10, TimeUnit.SECONDS)).isTrue();
        Thread shutdown = daemon("test-shutdown", () -> {
            lifecycle.onApplicationEvent(closed);
            lifecycle.stop();
        });
        shutdown.join(10_000);

        assertThat(shutdown.isAlive()).as("评分消费的启动最长要同步等一个 init-timeout：停凑单、撤导出、排空都不许被它挡住").isFalse();
        assertThat(events).containsSubsequence("matcher.start", "consumer.start:enter", "matcher.stop", "workers.drain", "gathers.awaitIdle(10s)");
        assertThat(lifecycle.isRunning()).isFalse();

        releaseStart.countDown();
        startup.join(10_000);

        assertThat(startup.isAlive()).isFalse();
        assertThat(consumerRunning).as("停机的第 5 步在它起来之前就走过了（停了个空）：启动线程起完后自己补停").isFalse();
        assertThat(events.get(events.size() - 1)).isEqualTo("consumer.stop");
        assertThat(events).containsSubsequence("consumer.start:exit", "consumer.stop");
    }

    @Test
    void 评分消费起的途中整个停机序列已经跑完_起完后补停一次_不打就绪日志(CapturedOutput output) {
        MatchLifecycle[] holder = new MatchLifecycle[1];
        ResultConsumerControl closingWhileStarting = new ResultConsumerControl() {
            @Override
            public void start() {
                events.add("consumer.start");
                // 评分消费正在起的时候整个停机序列跑完了（同一条线程上模拟：停机的第 5 步停了个空）
                holder[0].stopMatcher();
                holder[0].stop();
            }

            @Override
            public void stop() {
                events.add("consumer.stop");
            }
        };
        holder[0] = new MatchLifecycle(matcher, closingWhileStarting, gathers, drainWorkers, MatchLifecycle.GATHER_DRAIN_TIMEOUT);
        holder[0].start();

        holder[0].startBackground();

        assertThat(events).containsExactly("matcher.start", "consumer.start", "matcher.stop", "workers.drain", "gathers.awaitIdle(10s)",
                "consumer.stop", "consumer.stop");
        assertThat(output.getOut()).as("进程正在停：不打就绪日志（切片脚本拿它当就绪判据）").doesNotContain("match 已就绪");
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
    void 凑单_评分消费_开局管线_排空工作池四样缺一不可_构造即拒() {
        Duration timeout = Duration.ofSeconds(10);

        assertThatThrownBy(() -> new MatchLifecycle(null, consumer, gathers, drainWorkers, timeout))
                .isInstanceOf(NullPointerException.class).hasMessage("matcher");
        assertThatThrownBy(() -> new MatchLifecycle(matcher, null, gathers, drainWorkers, timeout))
                .isInstanceOf(NullPointerException.class).hasMessage("consumer");
        assertThatThrownBy(() -> new MatchLifecycle(matcher, consumer, null, drainWorkers, timeout))
                .as("没有开局管线的进程不许起：停机时也就没有「不等 gather」这条路").isInstanceOf(NullPointerException.class).hasMessage("gathers");
        assertThatThrownBy(() -> new MatchLifecycle(matcher, consumer, gathers, null, timeout))
                .isInstanceOf(NullPointerException.class).hasMessage("drainWorkers");
        assertThat(events).isEmpty();
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
}
