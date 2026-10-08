package com.game.match.gather;

import static com.game.match.gather.GatherFixture.ONE_V_ONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.BattleNodeInfo;
import com.game.common.deadline.Deadline;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.proto.BattlePlacement;
import com.game.match.ticket.ForwardingTicketStore;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.core.instrument.distribution.pause.PauseDetector;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 开局管线的入口（match-spec §9.3、§9.6 第 0 步、M13）：每次 gather 一个虚拟线程；全局在途上限；拿不到许可 → {@code overloaded}，没有发号 / 选节点 /
 * 冻结这些副作用，票据仍按入口的策略处置；future 永不异常完成；每次结束记一次计数与耗时；停机时可以有界地等在途的 gather。
 */
class VirtualThreadGatherLauncherTest {

    private static final long A = 1001;
    private static final long B = 1002;
    private static final QueueRef QUEUE_1V1 = new QueueRef(ONE_V_ONE, 0);

    private final GatherFixture f = new GatherFixture();

    private static GatherResult get(CompletableFuture<GatherResult> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    /** 在 beforePrepare 里把 gather 卡住、等测试放行的钩子；顺带记下跑它的线程。 */
    private static final class Gate implements GatherHooks {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<Thread> threads = new CopyOnWriteArrayList<>();

        @Override
        public void beforePrepare(List<Long> members) {
            threads.add(Thread.currentThread());
            entered.countDown();
            try {
                if (!release.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("测试没有放行");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void onStarted(BattlePlacement placement) {
        }
    }

    @Test
    void 一次gather跑在自己的虚拟线程上_launch不等它_结束后记一次success计数与耗时() throws Exception {
        Gate gate = new Gate();
        f.hooksPort = gate;
        VirtualThreadGatherLauncher launcher = new VirtualThreadGatherLauncher(f.pipeline(), f.metrics, 4);
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);

        CompletableFuture<GatherResult> future = launcher.launch(plan);

        assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(future).as("launch 不阻塞：gather 还卡在钩子里").isNotDone();
        Thread thread = gate.threads.get(0);
        assertThat(thread.isVirtual()).isTrue();
        assertThat(thread.getName()).startsWith("match-gather-");
        assertThat(thread).isNotSameAs(Thread.currentThread());
        assertThat(launcher.availablePermits()).isEqualTo(3);
        assertThat(launcher.inflight()).isEqualTo(1);
        assertThat(f.meters.get("xm.match.gathers.inflight").gauge().value()).isEqualTo(1.0);
        assertThat(launcher.awaitIdle(Duration.ofMillis(100))).as("还有在途的 gather").isFalse();

        gate.release.countDown();
        GatherResult result = get(future);

        assertThat(result.ok()).isTrue();
        assertThat(f.ticket(A).state()).isEqualTo(TicketState.READY);
        assertThat(launcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(launcher.availablePermits()).isEqualTo(4);
        assertThat(f.meters.get("xm.match.gathers.inflight").gauge().value()).isZero();
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_1V1", "outcome", "success")).isEqualTo(1.0);
        assertThat(f.meters.get("xm.match.gather").tags("mode", "MATCH_MODE_1V1", "outcome", "success").timer().count()).isEqualTo(1);
    }

    @Test
    void 失败的gather_future正常完成并带结局_计数记在对应的outcome上() throws Exception {
        VirtualThreadGatherLauncher launcher = new VirtualThreadGatherLauncher(f.pipeline(), f.metrics, 4);
        GatherPlan plan = f.solo(A);
        f.scene.prepareTip(A, 1006);

        GatherResult result = get(launcher.launch(plan));

        assertThat(result.ok()).isFalse();
        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.tickets.ticketOf(A)).as("future 完成时补偿已经做完").isEmpty();
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_PVE_SOLO", "outcome", "prepare_failed")).isEqualTo(1.0);
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_PVE_SOLO", "outcome", "success")).isZero();
        assertThat(launcher.availablePermits()).isEqualTo(4);
    }

    @Test
    void 许可用完时overloaded_没有发号选节点冻结这些副作用_票据按入口策略处置_不占许可() throws Exception {
        Gate gate = new Gate();
        f.hooksPort = gate;
        VirtualThreadGatherLauncher launcher = new VirtualThreadGatherLauncher(f.pipeline(), f.metrics, 1);
        GatherPlan held = f.popped(ONE_V_ONE, 0, A, B);
        GatherPlan queued = f.popped(ONE_V_ONE, 0, 2001, 2002);
        GatherPlan solo = f.solo(3001);
        GatherPlan challenge = f.challenge(4001, 4002);
        CompletableFuture<GatherResult> first = launcher.launch(held);
        assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(launcher.availablePermits()).isZero();
        int picksBefore = f.battleNodes.picks.size();

        GatherResult second = get(launcher.launch(queued));
        GatherResult third = get(launcher.launch(solo));
        GatherResult fourth = get(launcher.launch(challenge));

        for (GatherResult result : List.of(second, third, fourth)) {
            assertThat(result.ok()).isFalse();
            assertThat(result.outcome()).isEqualTo(GatherOutcome.OVERLOADED);
            assertThat(result.battleId()).as("没有发号").isZero();
        }
        assertThat(f.battleNodes.picks).as("没有选节点").hasSize(picksBefore);
        assertThat(f.scene.calls).as("被卡住的那一局还没开始备战；过载的三局没有备战任何人").isEmpty();
        assertThat(gate.threads).as("过载的不调钩子").hasSize(1);
        assertThat(f.placements.writes).isEmpty();
        // 票据：凑单全员按原序回队首并带退避；PVE_SOLO 删票；切磋没有票
        assertThat(f.tickets.queueMembers(QUEUE_1V1)).containsExactly("2001", "2002");
        assertThat(f.ticket(2001).state()).isEqualTo(TicketState.QUEUED);
        assertThat(f.ticket(2001).notBeforeMs()).isEqualTo(f.clock.peekMs() + 2_000);
        assertThat(f.tickets.ticketOf(3001)).isEmpty();
        assertThat(f.ticket(A).state()).as("在途那一局的票不受影响").isEqualTo(TicketState.MATCHED);
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_1V1", "outcome", "overloaded")).isEqualTo(1.0);
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_PVE_SOLO", "outcome", "overloaded")).isEqualTo(1.0);
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_PVP_CHALLENGE", "outcome", "overloaded")).isEqualTo(1.0);
        assertThat(launcher.availablePermits()).as("过载的不占、也不多还许可").isZero();
        assertThat(launcher.inflight()).isEqualTo(1);

        gate.release.countDown();
        assertThat(get(first).ok()).isTrue();
        assertThat(launcher.availablePermits()).isEqualTo(1);
        // 许可还回来之后又能开局
        assertThat(get(launcher.launch(f.solo(5001))).ok()).isTrue();
        assertThat(launcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void 管线里的协作者抛异常_future也不异常完成_许可照还() throws Exception {
        f.battleNodesPort = new BattleNodes() {
            @Override
            public Optional<BattleNodeInfo> pickRandom(Set<String> excludeKeys) {
                throw new IllegalStateException("违反约定");
            }

            @Override
            public Census census() {
                return new Census(0, 0, true);
            }

            @Override
            public Lookup lookup(int nodeId, String instanceId) {
                return Lookup.ERROR;
            }

            @Override
            public Lookup lookup(int nodeId, String instanceId, com.game.common.deadline.Deadline d) {
                return Lookup.ERROR;
            }
        };
        VirtualThreadGatherLauncher launcher = new VirtualThreadGatherLauncher(f.pipeline(), f.metrics, 2);

        CompletableFuture<GatherResult> future = launcher.launch(f.solo(A));
        GatherResult result = get(future);

        assertThat(future).isCompleted().isNotCompletedExceptionally();
        assertThat(result.outcome()).isEqualTo(GatherOutcome.INTERNAL);
        assertThat(launcher.availablePermits()).isEqualTo(2);
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_PVE_SOLO", "outcome", "internal")).isEqualTo(1.0);
    }

    // ---------------------------------------------------------------- Error（NoClassDefFoundError、StackOverflowError、断言失败……）也得收场

    /** 目录读口：{@code broken} 为真时选节点抛 Error，其余照常委托给夹具的目录。 */
    private BattleNodes directoryThrowingError(AtomicBoolean broken) {
        return new BattleNodes() {
            @Override
            public Optional<BattleNodeInfo> pickRandom(Set<String> excludeKeys) {
                if (broken.get()) {
                    throw new AssertionError("注入的故障: 管线里抛出 Error");
                }
                return f.battleNodes.pickRandom(excludeKeys);
            }

            @Override
            public Census census() {
                return f.battleNodes.census();
            }

            @Override
            public Lookup lookup(int nodeId, String instanceId) {
                return f.battleNodes.lookup(nodeId, instanceId);
            }

            @Override
            public Lookup lookup(int nodeId, String instanceId, com.game.common.deadline.Deadline d) {
                return f.battleNodes.lookup(nodeId, instanceId, d);
            }
        };
    }

    @Test
    void 管线抛出Error_许可照还_future以internal完成_在途计数归零_唯一的许可没有漏掉() throws Exception {
        AtomicBoolean broken = new AtomicBoolean(true);
        f.battleNodesPort = directoryThrowingError(broken);
        VirtualThreadGatherLauncher launcher = new VirtualThreadGatherLauncher(f.pipeline(), f.metrics, 1);

        CompletableFuture<GatherResult> future = launcher.launch(f.solo(A));
        GatherResult result = get(future);

        assertThat(future).as("契约：永不异常完成、一定会完成").isCompleted().isNotCompletedExceptionally();
        assertThat(result.outcome()).isEqualTo(GatherOutcome.INTERNAL);
        assertThat(result.battleId()).isZero();
        assertThat(launcher.availablePermits()).as("许可在 future 完成之前就还了").isEqualTo(1);
        assertThat(launcher.awaitIdle(Duration.ofSeconds(5))).as("在途计数已减：停机不会白等满 10 s").isTrue();
        assertThat(launcher.inflight()).isZero();
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_PVE_SOLO", "outcome", "internal")).isEqualTo(1.0);

        broken.set(false);
        assertThat(get(launcher.launch(f.solo(B))).ok()).as("上限只有 1：许可要是漏了，这一局就是 overloaded").isTrue();
        assertThat(launcher.availablePermits()).isEqualTo(1);
    }

    @Test
    void 过载收尾里抛出Error_future以overloaded完成_不多还许可_在途的那一局不受影响() throws Exception {
        Gate gate = new Gate();
        f.hooksPort = gate;
        f.ticketPort = new ForwardingTicketStore(f.tickets) {
            @Override
            public int deleteGroup(List<TicketRef> tickets, Deadline d) {
                if (tickets.stream().anyMatch(ticket -> ticket.playerId() == 3001)) {
                    throw new AssertionError("注入的故障: 过载收尾里抛出 Error");
                }
                return super.deleteGroup(tickets, d);
            }
        };
        VirtualThreadGatherLauncher launcher = new VirtualThreadGatherLauncher(f.pipeline(), f.metrics, 1);
        CompletableFuture<GatherResult> held = launcher.launch(f.solo(A));
        assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(launcher.availablePermits()).isZero();

        CompletableFuture<GatherResult> overloaded = launcher.launch(f.solo(3001));
        GatherResult result = get(overloaded);

        assertThat(overloaded).isCompleted().isNotCompletedExceptionally();
        assertThat(result.outcome()).isEqualTo(GatherOutcome.OVERLOADED);
        assertThat(launcher.availablePermits()).as("没拿到许可的那一局不许多还一个").isZero();
        assertThat(launcher.inflight()).isEqualTo(1);
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_PVE_SOLO", "outcome", "overloaded")).isEqualTo(1.0);

        gate.release.countDown();
        assertThat(get(held).ok()).isTrue();
        assertThat(launcher.awaitIdle(Duration.ofSeconds(5))).as("两条线程的在途计数都减了").isTrue();
        assertThat(launcher.availablePermits()).isEqualTo(1);
    }

    @Test
    void 记指标抛出Error_future照样完成_在途计数照样减() throws Exception {
        // 「gather 耗时」这个 Timer 第一次使用时才注册：让注册抛 Error
        SimpleMeterRegistry broken = new SimpleMeterRegistry() {
            @Override
            protected Timer newTimer(Meter.Id id, DistributionStatisticConfig config, PauseDetector pauseDetector) {
                if (id.getName().equals("xm.match.gather")) {
                    throw new AssertionError("注入的故障: 记指标抛出 Error");
                }
                return super.newTimer(id, config, pauseDetector);
            }
        };
        VirtualThreadGatherLauncher launcher = new VirtualThreadGatherLauncher(f.pipeline(), new MatchMetrics(broken, new MetricLabels(id -> id == 1)), 1);

        CompletableFuture<GatherResult> future = launcher.launch(f.solo(A));
        GatherResult result = get(future);

        assertThat(result.ok()).as("这一局本身是成功的，结果照实给").isTrue();
        assertThat(f.ticket(A).state()).isEqualTo(TicketState.READY);
        assertThat(launcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(launcher.availablePermits()).isEqualTo(1);
    }

    @Test
    void 挂在future上的回调抛异常_不影响许可与在途计数() throws Exception {
        Gate gate = new Gate();
        f.hooksPort = gate;
        VirtualThreadGatherLauncher launcher = new VirtualThreadGatherLauncher(f.pipeline(), f.metrics, 1);
        AtomicReference<String> callbackThread = new AtomicReference<>();
        CompletableFuture<GatherResult> future = launcher.launch(f.solo(A));
        assertThat(gate.entered.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> dependent = future.thenAccept(result -> {
            callbackThread.set(Thread.currentThread().getName());
            throw new IllegalStateException("回调自己的 bug");
        });

        gate.release.countDown();
        // 先等回调、再取结果：对还没完成的 future 调 get() 的线程被唤醒后会帮着跑它的后续（CompletableFuture 的 postComplete），
        // 先 get(future) 的话回调就可能跑在测试线程上（2026-10-08 在 CI 的 Integration 上偶发；同 6.3 的 SceneBattleProviderTest）
        assertThatThrownBy(() -> dependent.get(5, TimeUnit.SECONDS)).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(get(future).ok()).isTrue();

        assertThat(callbackThread.get()).as("回调在 gather 的线程上跑：所以不得阻塞").startsWith("match-gather-");
        assertThat(launcher.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(launcher.availablePermits()).isEqualTo(1);
    }

    @Test
    void 并发launch_在途数不超过上限_每一局不是成功就是overloaded_全部结束后许可归位() throws Exception {
        int max = 4;
        VirtualThreadGatherLauncher launcher = new VirtualThreadGatherLauncher(f.pipeline(), f.metrics, max);
        List<GatherPlan> plans = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            plans.add(f.solo(10_000 + i));
        }
        List<CompletableFuture<GatherResult>> futures = new CopyOnWriteArrayList<>();
        List<Thread> launchers = new ArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger maxInflightSeen = new AtomicInteger();
        for (int t = 0; t < 6; t++) {
            int slice = t;
            Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int i = slice; i < plans.size(); i += 6) {
                    futures.add(launcher.launch(plans.get(i)));
                    maxInflightSeen.accumulateAndGet(launcher.inflight(), Math::max);
                }
            });
            launchers.add(thread);
        }
        start.countDown();
        for (Thread thread : launchers) {
            thread.join(10_000);
        }
        assertThat(maxInflightSeen.get()).as("在途数不超过上限").isLessThanOrEqualTo(max);

        int success = 0;
        int overloaded = 0;
        for (CompletableFuture<GatherResult> future : futures) {
            GatherResult result = get(future);
            if (result.ok()) {
                success++;
            } else {
                assertThat(result.outcome()).isEqualTo(GatherOutcome.OVERLOADED);
                overloaded++;
            }
        }
        assertThat(futures).hasSize(60);
        assertThat(success + overloaded).isEqualTo(60);
        assertThat(success).isPositive();
        assertThat(launcher.awaitIdle(Duration.ofSeconds(10))).isTrue();
        assertThat(launcher.availablePermits()).isEqualTo(max);
        assertThat(f.battleA.creates).as("成功的每一局恰好建一次房").hasSize(success);
        assertThat(f.tickets.ticketCount()).as("成功的票是 ready，过载的已删").isEqualTo(success);
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_PVE_SOLO", "outcome", "success")).isEqualTo((double) success);
        assertThat(f.count("xm.match.gathers", "mode", "MATCH_MODE_PVE_SOLO", "outcome", "overloaded")).isEqualTo((double) overloaded);
    }

    @Test
    void 没有在途gather时awaitIdle立即为真_在途上限必须为正() {
        VirtualThreadGatherLauncher launcher = new VirtualThreadGatherLauncher(f.pipeline(), f.metrics, 3);

        assertThat(launcher.awaitIdle(Duration.ZERO)).isTrue();
        assertThat(launcher.availablePermits()).isEqualTo(3);
        assertThatThrownBy(() -> new VirtualThreadGatherLauncher(f.pipeline(), f.metrics, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
