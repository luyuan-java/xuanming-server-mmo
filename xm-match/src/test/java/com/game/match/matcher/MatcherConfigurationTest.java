package com.game.match.matcher;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.match.MatchInstance;
import com.game.match.MatchProperties;
import com.game.match.gather.BattleNodes;
import com.game.match.gather.GatherLauncher;
import com.game.match.gather.GatherPlan;
import com.game.match.id.MatchIds;
import com.game.match.lifecycle.MatcherControl;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.port.PlayerStatusReader;
import com.game.match.testing.FakeBattleNodes;
import com.game.match.testing.FakeGatherLauncher;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.TicketState;
import com.game.match.ticket.TicketStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.UnsatisfiedDependencyException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 凑单的装配（{@link MatcherConfiguration}）：
 * <ul>
 *   <li>它给进程的是<b>凑单的启停口</b> {@link MatcherControl}，bean 自己不启停——上下文刷新完凑单还没在跑（启动第 8 步由 {@code MatchLifecycle} 在
 *       Dubbo 导出之后调），上下文关闭也不替它停；</li>
 *   <li>经启停口启动之后凑单循环就真的在跑：往存储里放两个排队的人，不做任何手动驱动，开局管线的替身就收到这一组；</li>
 *   <li>票据存储、开局管线、battle 目录三个协作件是硬依赖：缺任何一个上下文起不来（凑单没接上线的进程会照收排队、永不成局）。</li>
 * </ul>
 */
class MatcherConfigurationTest {

    private static final QueueRef Q1V1 = new QueueRef(3, 0);

    private final InMemoryTicketStore store = new InMemoryTicketStore();
    private final FakeGatherLauncher gather = new FakeGatherLauncher();
    private final FakeBattleNodes nodes = new FakeBattleNodes().add(FakeBattleNodes.node(1, "battle-inst-1", 21200));
    private final FakePlayerStatus players = new FakePlayerStatus();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MetricLabels labels = new MetricLabels(id -> id == 1);
    private final MatchMetrics metrics = new MatchMetrics(meters, labels);

    /** 凑单之外的基础设施 bean（生产里由 MatchConfiguration 提供）；间隔调成 10 ms 让用例跑得快。 */
    private ApplicationContextRunner base() {
        return new ApplicationContextRunner()
                .withUserConfiguration(MatcherConfiguration.class)
                .withBean(MatchProperties.class, () -> new MatchProperties(null, null, new MatchProperties.Matcher(Duration.ofMillis(10), null), null,
                        null, null, null, null, null, null, null, null))
                .withBean(MatchInstance.class, () -> new MatchInstance("inst-ctx"))
                .withBean(MatchIds.class, () -> new MatchIds(new Snowflake(9), () -> true, () -> false))
                .withBean(MetricLabels.class, () -> labels)
                .withBean(MatchMetrics.class, () -> metrics)
                .withBean(PlayerStatusReader.class, () -> players);
    }

    private ApplicationContextRunner wired() {
        return base().withBean(TicketStore.class, () -> store).withBean(GatherLauncher.class, () -> gather).withBean(BattleNodes.class, () -> nodes);
    }

    private double okRounds() {
        return meters.get("xm.match.matcher.rounds").tags("result", "ok").counter().count();
    }

    @Test
    void 给进程的是凑单启停口_bean自己不启停_上下文刷新完没在跑_关上下文也不替它停() throws Exception {
        MatcherRunner[] held = new MatcherRunner[1];
        wired().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(MatcherControl.class);
            MatcherRunner runner = context.getBean(MatcherRunner.class);
            held[0] = runner;
            assertThat(context.getBean(MatcherControl.class)).as("启停口就是调度器本身").isSameAs(runner);
            assertThat(runner.isRunning()).as("上下文刷新完：还没人调启动第 8 步").isFalse();
            Thread.sleep(60);
            assertThat(okRounds()).as("一轮都没跑").isZero();
            assertThat(store.calls).as("没有线程在碰存储").isEmpty();

            runner.start();
            assertThat(runner.isRunning()).isTrue();
        });
        try {
            assertThat(held[0].isRunning()).as("上下文关闭不停它：停机第 1 步由 MatchLifecycle 在撤 Dubbo 导出之前调").isTrue();
        } finally {
            held[0].stop();
        }
        assertThat(held[0].isRunning()).isFalse();
    }

    @Test
    void 经启停口启动之后凑单循环真的在跑_两个排队的人被凑成一组交给开局管线() {
        wired().run(context -> {
            MatcherControl control = context.getBean(MatcherControl.class);
            control.start();
            try {
                players.online(1001, 1, 7).online(1002, 1, 7);
                store.enqueue(1001, "t-1001", Q1V1, 1, 150_000, 21_600_000, Deadline.after(1000));
                store.enqueue(1002, "t-1002", Q1V1, 1, 150_000, 21_600_000, Deadline.after(1000));

                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (gather.plans.isEmpty() && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertThat(gather.plans).as("没有任何手动驱动：是调度线程自己凑出来的").hasSize(1);
                GatherPlan plan = gather.plans.get(0);
                assertThat(plan.members()).containsExactly(1001L, 1002L);
                assertThat(store.ticketOf(1001).orElseThrow().state()).isEqualTo(TicketState.MATCHED);
                assertThat(store.ticketOf(1002).orElseThrow().state()).isEqualTo(TicketState.MATCHED);
                assertThat(store.calls).as("弹组之前先抢了这条队列的凑单锁").contains("tryLockQueue(3:0)");
                // 后续的轮次接着干活：弹空的队列被剔出注册集、锁放掉、轮次指标在走
                while ((okRounds() < 1 || store.indexed(Q1V1) || store.lockHolder(Q1V1).isPresent()) && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertThat(okRounds()).isGreaterThanOrEqualTo(1.0);
                assertThat(store.indexed(Q1V1)).as("空队列已被后续的一轮剔除").isFalse();
                assertThat(store.lockHolder(Q1V1)).isEmpty();
            } finally {
                control.stop();
            }
            assertThat(context.getBean(MatcherRunner.class).isRunning()).isFalse();
        });
    }

    /**
     * 停机时等「凑单锁 TTL + 余量」，等不到就中断凑单线程。一条队列在锁 TTL 之外最多还有一次弹组（结局不明时同标记重发一次）：
     * 余量装不下这两次尝试的话，中断会落在弹组的等待里，留下「弹出了却没人开局」的票。改任何一个常量都要过这条。
     */
    @Test
    void 停机余量装得下弹组的两次尝试() {
        assertThat(MatcherConfiguration.STOP_MARGIN.toMillis()).isGreaterThanOrEqualTo(2 * QueueMatcher.POP_BUDGET_MS);
        assertThat(QueueMatcher.POP_BUDGET_MS).as("一次尝试至少覆盖 Redis 客户端的一次响应超时（缺省 2 s）").isGreaterThanOrEqualTo(2_000);
    }

    @Test
    void 缺票据存储_拒绝启动() {
        base().withBean(GatherLauncher.class, () -> gather).withBean(BattleNodes.class, () -> nodes).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasMessageContaining("matcherRunner").hasStackTraceContaining(TicketStore.class.getName());
        });
    }

    @Test
    void 缺开局管线或battle目录_同样拒绝启动() {
        base().withBean(TicketStore.class, () -> store).withBean(BattleNodes.class, () -> nodes).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasStackTraceContaining(GatherLauncher.class.getName());
        });
        base().withBean(TicketStore.class, () -> store).withBean(GatherLauncher.class, () -> gather).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).isInstanceOf(UnsatisfiedDependencyException.class)
                    .hasStackTraceContaining(BattleNodes.class.getName());
        });
    }
}
