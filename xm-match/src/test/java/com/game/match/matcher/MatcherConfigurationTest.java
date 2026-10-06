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
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 凑单的装配（{@link MatcherConfiguration}）：票据存储、开局管线、battle 目录三个协作件齐全时，上下文一刷新凑单循环就真的在跑——
 * 往存储里放两个排队的人，不做任何手动驱动，开局管线的替身就收到这一组；上下文关闭时循环先停。缺任何一个协作件时给的是没接上线的占位实例：
 * 上下文照样起得来（并行开发阶段的进程骨架靠这一条），但它不运行，并说得清缺什么。
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

    @Test
    void 三个协作件齐全_上下文一起来凑单循环就在跑_关上下文时先停() {
        MatcherRunner[] held = new MatcherRunner[1];
        base().withBean(TicketStore.class, () -> store).withBean(GatherLauncher.class, () -> gather).withBean(BattleNodes.class, () -> nodes)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    MatcherRunner runner = context.getBean(MatcherRunner.class);
                    held[0] = runner;
                    assertThat(runner.wired()).isTrue();
                    assertThat(runner.missing()).isEmpty();
                    assertThat(runner.isRunning()).as("随上下文自动启动").isTrue();

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
                });

        assertThat(held[0]).isNotNull();
        assertThat(held[0].isRunning()).as("上下文关闭：凑单循环已停").isFalse();
    }

    private double okRounds() {
        return meters.get("xm.match.matcher.rounds").tags("result", "ok").counter().count();
    }

    @Test
    void 缺票据存储_给占位实例_上下文照样起得来_凑单不运行() {
        base().withBean(GatherLauncher.class, () -> gather).withBean(BattleNodes.class, () -> nodes).run(context -> {
            assertThat(context).hasNotFailed();
            MatcherRunner runner = context.getBean(MatcherRunner.class);
            assertThat(runner.wired()).isFalse();
            assertThat(runner.missing()).containsExactly("TicketStore");
            assertThat(runner.isRunning()).isFalse();
        });
    }

    @Test
    void 缺开局管线或battle目录_同样是占位实例() {
        base().withBean(TicketStore.class, () -> store).withBean(BattleNodes.class, () -> nodes).run(context -> {
            assertThat(context).hasNotFailed();
            MatcherRunner runner = context.getBean(MatcherRunner.class);
            assertThat(runner.missing()).containsExactly("GatherLauncher");
            assertThat(runner.isRunning()).isFalse();
        });
        base().withBean(TicketStore.class, () -> store).withBean(GatherLauncher.class, () -> gather).run(context -> {
            assertThat(context).hasNotFailed();
            MatcherRunner runner = context.getBean(MatcherRunner.class);
            assertThat(runner.missing()).containsExactly("BattleNodes");
            assertThat(runner.isRunning()).isFalse();
        });
    }

    @Test
    void 三个都缺_骨架阶段_占位实例列出全部三个_没有线程在碰存储() {
        base().run(context -> {
            assertThat(context).hasNotFailed();
            MatcherRunner runner = context.getBean(MatcherRunner.class);
            assertThat(runner.wired()).isFalse();
            assertThat(runner.missing()).containsExactly("TicketStore", "GatherLauncher", "BattleNodes");
            assertThat(runner.isRunning()).isFalse();
            Thread.sleep(50);
            assertThat(meters.get("xm.match.matcher.rounds").tags("result", "ok").counter().count()).as("一轮都没跑").isEqualTo(0.0);
            assertThat(meters.get("xm.match.matcher.rounds").tags("result", "paused_no_battle").counter().count()).isEqualTo(0.0);
        });
    }
}
