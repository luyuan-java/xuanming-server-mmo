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
 * 批次 6.5 加的观战两步（spectate-spec §4.11、lead 裁决 4）——观战清扫随凑单一起启动；停机时「等在途的 163」与排空工作池<b>并行</b>、
 * 随后停清扫——由文件末尾「观战」一节钉；前面的用例钉的仍是 6.4 那几步彼此的次序，观战两个口的事件另记在 {@code spectate} 里，不掺进去。
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

    /** 等在途 163 的上限：测试里收短（生产 5 s）。 */
    private static final Duration WATCH_DRAIN = Duration.ofMillis(300);
    /** 观战两个口的事件（见类注释）。 */
    private final List<String> spectate = new CopyOnWriteArrayList<>();

    private final InflightWatches watches = timeout -> {
        spectate.add("watches.awaitIdle(" + timeout.toMillis() + "ms)");
        return true;
    };

    private final SweeperControl sweeper = new SweeperControl() {
        @Override
        public void start() {
            spectate.add("sweeper.start");
        }

        @Override
        public void stop() {
            spectate.add("sweeper.stop");
        }
    };

    private static void throwIf(RuntimeException error) {
        if (error != null) {
            throw error;
        }
    }

    private MatchLifecycle lifecycle() {
        return new MatchLifecycle(matcher, consumer, gathers, drainWorkers, MatchLifecycle.GATHER_DRAIN_TIMEOUT, watches, WATCH_DRAIN, sweeper);
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
        MatchLifecycle lifecycle = new MatchLifecycle(slowStart, consumer, gathers, drainWorkers, MatchLifecycle.GATHER_DRAIN_TIMEOUT, watches, WATCH_DRAIN, sweeper);
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
        MatchLifecycle lifecycle = new MatchLifecycle(matcher, slowStart, gathers, drainWorkers, MatchLifecycle.GATHER_DRAIN_TIMEOUT, watches, WATCH_DRAIN, sweeper);
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
        holder[0] = new MatchLifecycle(matcher, closingWhileStarting, gathers, drainWorkers, MatchLifecycle.GATHER_DRAIN_TIMEOUT, watches, WATCH_DRAIN, sweeper);
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

        assertThatThrownBy(() -> new MatchLifecycle(null, consumer, gathers, drainWorkers, timeout, watches, WATCH_DRAIN, sweeper))
                .isInstanceOf(NullPointerException.class).hasMessage("matcher");
        assertThatThrownBy(() -> new MatchLifecycle(matcher, null, gathers, drainWorkers, timeout, watches, WATCH_DRAIN, sweeper))
                .isInstanceOf(NullPointerException.class).hasMessage("consumer");
        assertThatThrownBy(() -> new MatchLifecycle(matcher, consumer, null, drainWorkers, timeout, watches, WATCH_DRAIN, sweeper))
                .as("没有开局管线的进程不许起：停机时也就没有「不等 gather」这条路").isInstanceOf(NullPointerException.class).hasMessage("gathers");
        assertThatThrownBy(() -> new MatchLifecycle(matcher, consumer, gathers, null, timeout, watches, WATCH_DRAIN, sweeper))
                .isInstanceOf(NullPointerException.class).hasMessage("drainWorkers");
        assertThat(events).isEmpty();
    }

    @Test
    void 等gather的上限取构造时给的值_生产是10秒() {
        MatchLifecycle lifecycle = new MatchLifecycle(matcher, consumer, gathers, drainWorkers, Duration.ofSeconds(3), watches, WATCH_DRAIN, sweeper);
        lifecycle.start();

        lifecycle.stop();

        assertThat(events).contains("gathers.awaitIdle(3s)");
        assertThat(MatchLifecycle.GATHER_DRAIN_TIMEOUT).isEqualTo(Duration.ofSeconds(10));
    }

    // ================================================================ 观战（批次 6.5）：清扫的启停、停机时并行等在途的 163

    /** 把六个协作者的事件记进同一条序列的夹具（观战一节的用例要看它们之间的相对次序）。 */
    private static final class Probe {
        final List<String> log = new CopyOnWriteArrayList<>();
        volatile RuntimeException sweeperStartError;
        volatile RuntimeException sweeperStopError;
        /** 等在途 163 的行为；缺省立即回 true。 */
        volatile InflightWatches awaiting = timeout -> true;
        /** 排空工作池时额外做的事（在记下 begin 之后、end 之前）。 */
        volatile Runnable draining = () -> { };

        final MatcherControl matcher = new MatcherControl() {
            @Override
            public void start() {
                log.add("matcher.start");
            }

            @Override
            public void stop() {
                log.add("matcher.stop");
            }
        };
        final ResultConsumerControl consumer = new ResultConsumerControl() {
            @Override
            public void start() {
                log.add("consumer.start");
            }

            @Override
            public void stop() {
                log.add("consumer.stop");
            }
        };
        final GatherLauncher gathers = new GatherLauncher() {
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
                log.add("gathers.awaitIdle");
                return true;
            }
        };
        final Runnable drainWorkers = () -> {
            log.add("workers.drain:begin");
            draining.run();
            log.add("workers.drain:end");
        };
        final InflightWatches watches = timeout -> {
            log.add("watches.await:begin");
            try {
                return awaiting.awaitIdle(timeout);
            } finally {
                log.add("watches.await:end");
            }
        };
        final SweeperControl sweeper = new SweeperControl() {
            @Override
            public void start() {
                log.add("sweeper.start");
                throwIf(sweeperStartError);
            }

            @Override
            public void stop() {
                log.add("sweeper.stop");
                throwIf(sweeperStopError);
            }
        };

        MatchLifecycle lifecycle(Duration watchDrain) {
            return new MatchLifecycle(matcher, consumer, gathers, drainWorkers, MatchLifecycle.GATHER_DRAIN_TIMEOUT, watches, watchDrain, sweeper);
        }

        int at(String event) {
            int index = log.indexOf(event);
            assertThat(index).as("事件 %s 应该发生过，实际序列 %s", event, log).isNotNegative();
            return index;
        }
    }

    @Test
    void 观战清扫随应用已启动事件启动_排在凑单之后评分消费之前_只起一次() {
        Probe probe = new Probe();
        MatchLifecycle lifecycle = probe.lifecycle(WATCH_DRAIN);

        lifecycle.start();
        assertThat(probe.log).as("SmartLifecycle.start 只登记：清扫器自己不带启停，Dubbo 导出之前不碰 Redis").isEmpty();
        lifecycle.startBackground();
        lifecycle.startBackground();

        assertThat(probe.log).containsExactly("matcher.start", "sweeper.start", "consumer.start");
    }

    @Test
    void 停机第3步_等在途163与排空工作池并行_两件都结束才停清扫_再等gather_再停评分消费() throws Exception {
        Probe probe = new Probe();
        CountDownLatch watchesWaiting = new CountDownLatch(1);
        CountDownLatch draining = new CountDownLatch(1);
        AtomicBoolean drainSawWatches = new AtomicBoolean();
        AtomicBoolean watchesSawDrain = new AtomicBoolean();
        // 串行的实现过不了这两道闸：排空工作池要等到「等 163」已经开始，「等 163」要等到排空已经开始
        probe.draining = () -> {
            draining.countDown();
            drainSawWatches.set(awaitQuietly(watchesWaiting));
        };
        probe.awaiting = timeout -> {
            watchesWaiting.countDown();
            watchesSawDrain.set(awaitQuietly(draining));
            return true;
        };
        MatchLifecycle lifecycle = probe.lifecycle(Duration.ofSeconds(30));
        lifecycle.start();
        lifecycle.startBackground();
        probe.log.clear();

        lifecycle.onApplicationEvent(new ContextClosedEvent(new GenericApplicationContext()));
        assertThat(probe.log).as("第 1 步只停凑单").containsExactly("matcher.stop");
        lifecycle.stop();

        assertThat(drainSawWatches).as("排空工作池期间「等在途 163」已经在进行").isTrue();
        assertThat(watchesSawDrain).as("「等在途 163」期间排空工作池已经在进行").isTrue();
        assertThat(probe.log).containsExactlyInAnyOrder("matcher.stop", "workers.drain:begin", "workers.drain:end", "watches.await:begin",
                "watches.await:end", "sweeper.stop", "gathers.awaitIdle", "consumer.stop");
        assertThat(probe.at("matcher.stop")).isLessThan(probe.at("workers.drain:begin")).isLessThan(probe.at("watches.await:begin"));
        assertThat(probe.at("sweeper.stop")).as("两件都结束之后才停清扫")
                .isGreaterThan(probe.at("workers.drain:end")).isGreaterThan(probe.at("watches.await:end"));
        assertThat(probe.log.subList(probe.at("sweeper.stop"), probe.log.size()))
                .containsExactly("sweeper.stop", "gathers.awaitIdle", "consumer.stop");
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void 一次163挂在登记观众上_停机不早于等它的上限到点_到点就放弃_后面的步骤照做(CapturedOutput output) {
        Probe probe = new Probe();
        // 守约的实现：等满上限仍有在途，回 false
        probe.awaiting = timeout -> {
            sleepQuietly(timeout);
            return false;
        };
        MatchLifecycle lifecycle = probe.lifecycle(WATCH_DRAIN);
        lifecycle.start();
        lifecycle.startBackground();
        probe.log.clear();

        long startedNanos = System.nanoTime();
        lifecycle.stop();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertThat(elapsedMs).as("工作池早就排空了，停机仍要等在途的 163 到它的上限（%s）", WATCH_DRAIN).isGreaterThanOrEqualTo(WATCH_DRAIN.toMillis() - 20);
        assertThat(elapsedMs).as("也不无限等").isLessThan(10_000);
        assertThat(probe.at("sweeper.stop")).isGreaterThan(probe.at("watches.await:end"));
        assertThat(probe.log.subList(probe.at("sweeper.stop"), probe.log.size())).containsExactly("sweeper.stop", "gathers.awaitIdle", "consumer.stop");
        assertThat(output.getOut()).contains("仍有在途的 163 观战，放弃等待").contains("观战标记留到 TTL");
    }

    @Test
    void 等在途163的实现不守约_一直不返回_停机在上限加余量之后放弃它_不被拖住(CapturedOutput output) throws Exception {
        Probe probe = new Probe();
        CountDownLatch never = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        probe.awaiting = timeout -> {
            try {
                never.await();
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return false;
        };
        MatchLifecycle lifecycle = probe.lifecycle(WATCH_DRAIN);
        lifecycle.start();
        lifecycle.startBackground();
        probe.log.clear();

        long startedNanos = System.nanoTime();
        lifecycle.stop();
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        try {
            assertThat(elapsedMs).as("等到上限 + 余量（%s + %s）", WATCH_DRAIN, MatchLifecycle.WATCH_DRAIN_GRACE)
                    .isGreaterThanOrEqualTo(WATCH_DRAIN.plus(MatchLifecycle.WATCH_DRAIN_GRACE).toMillis() - 20).isLessThan(15_000);
            assertThat(interrupted.await(5, TimeUnit.SECONDS)).as("放弃时打断辅助线程").isTrue();
            assertThat(probe.log).contains("sweeper.stop", "gathers.awaitIdle", "consumer.stop");
            assertThat(probe.at("gathers.awaitIdle")).isGreaterThan(probe.at("sweeper.stop"));
            assertThat(output.getOut()).contains("实现违反约定").contains("放弃等待，继续停机");
            assertThat(lifecycle.isRunning()).isFalse();
        } finally {
            never.countDown();
        }
    }

    @Test
    void 等在途163抛异常_停清扫抛异常_只记日志_后面的步骤照做(CapturedOutput output) {
        Probe probe = new Probe();
        probe.awaiting = timeout -> {
            throw new IllegalStateException("执行器已经坏了");
        };
        probe.sweeperStopError = new IllegalStateException("清扫器停不下来");
        MatchLifecycle lifecycle = probe.lifecycle(WATCH_DRAIN);
        lifecycle.start();
        lifecycle.startBackground();
        probe.log.clear();

        lifecycle.stop();

        assertThat(probe.log.subList(probe.at("sweeper.stop"), probe.log.size())).containsExactly("sweeper.stop", "gathers.awaitIdle", "consumer.stop");
        assertThat(output.getOut()).contains("等在途的 163 观战出错").contains("停观战清扫出错");
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @Test
    void 观战清扫启动失败_异常原样抛出拒绝启动_评分消费不起_随后的停机把凑单与清扫都停掉() {
        Probe probe = new Probe();
        probe.sweeperStartError = new IllegalStateException("定时器起不来");
        MatchLifecycle lifecycle = probe.lifecycle(WATCH_DRAIN);
        lifecycle.start();

        assertThatThrownBy(lifecycle::startBackground).isSameAs(probe.sweeperStartError);
        lifecycle.stop();

        assertThat(probe.log).startsWith("matcher.start", "sweeper.start", "matcher.stop").doesNotContain("consumer.start", "consumer.stop");
        assertThat(probe.log).as("起过（哪怕没起成）就要停：stop 幂等").contains("sweeper.stop", "gathers.awaitIdle");
    }

    @Test
    void 后台件从没起过_停机不去停清扫_但照样等在途的163() {
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();

        lifecycle.stop();
        lifecycle.stop();

        assertThat(spectate).as("清扫没起过就不停；等 163 只做一次，上限取构造时给的值").containsExactly("watches.awaitIdle(300ms)");
        assertThat(events).containsExactly("workers.drain", "gathers.awaitIdle(10s)");
    }

    @Test
    void 正常启停一轮_清扫起一次停一次_163等一次() {
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();
        lifecycle.startBackground();
        lifecycle.stopMatcher();
        lifecycle.stop();
        lifecycle.stop();

        assertThat(spectate).containsExactly("sweeper.start", "watches.awaitIdle(300ms)", "sweeper.stop");
    }

    @Test
    void 停机开始之后再来启动事件_清扫也不再起() {
        MatchLifecycle lifecycle = lifecycle();
        lifecycle.start();

        lifecycle.stopMatcher();
        lifecycle.startBackground();
        lifecycle.stop();

        assertThat(spectate).as("没有 sweeper.start，自然也没有 sweeper.stop").containsExactly("watches.awaitIdle(300ms)");
    }

    @Test
    void 观战的在途口_清扫口_等待上限缺一不可_上限必须为正() {
        Duration timeout = Duration.ofSeconds(10);

        assertThatThrownBy(() -> new MatchLifecycle(matcher, consumer, gathers, drainWorkers, timeout, null, WATCH_DRAIN, sweeper))
                .isInstanceOf(NullPointerException.class).hasMessage("watches");
        assertThatThrownBy(() -> new MatchLifecycle(matcher, consumer, gathers, drainWorkers, timeout, watches, null, sweeper))
                .isInstanceOf(NullPointerException.class).hasMessage("watchDrainTimeout");
        assertThatThrownBy(() -> new MatchLifecycle(matcher, consumer, gathers, drainWorkers, timeout, watches, WATCH_DRAIN, null))
                .isInstanceOf(NullPointerException.class).hasMessage("sweeper");
        assertThatThrownBy(() -> new MatchLifecycle(matcher, consumer, gathers, drainWorkers, timeout, watches, Duration.ZERO, sweeper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MatchLifecycle(matcher, consumer, gathers, drainWorkers, timeout, watches, Duration.ofMillis(-1), sweeper))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(MatchLifecycle.WATCH_DRAIN_GRACE).isEqualTo(Duration.ofSeconds(1));
        assertThat(com.game.api.match.MatchBudgets.SPECTATE_DRAIN_TIMEOUT_MS + MatchLifecycle.WATCH_DRAIN_GRACE.toMillis())
                .as("生产的上限 5 s 加余量 1 s，仍不超过排空工作池的 10 s：并行之后第 3 步不比 6.4 长").isLessThanOrEqualTo(10_000);
    }

    private static boolean awaitQuietly(CountDownLatch latch) {
        try {
            return latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void sleepQuietly(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
