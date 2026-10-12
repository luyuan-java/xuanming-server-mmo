package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 生产调度器：包装单线程 Netty EventLoop（battle-logic）。 */
class EventLoopBattleSchedulerTest {

    private final EventLoopGroup group = new NioEventLoopGroup(1, new DefaultThreadFactory("battle-logic-test"));
    private final EventLoopBattleScheduler scheduler = new EventLoopBattleScheduler(group.next());

    @AfterEach
    void shutdown() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    @Test
    void 投递的任务在逻辑线程上执行_测试线程不是逻辑线程() throws Exception {
        assertThat(scheduler.inLoop()).isFalse();
        assertThatThrownBy(scheduler::assertInLoop).isInstanceOf(IllegalStateException.class);

        CountDownLatch done = new CountDownLatch(1);
        List<String> seen = new CopyOnWriteArrayList<>();
        scheduler.execute(() -> {
            seen.add(Thread.currentThread().getName());
            scheduler.assertInLoop();
            done.countDown();
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).singleElement().satisfies(name -> assertThat(name).startsWith("battle-logic-test"));
    }

    @Test
    void 逻辑线程上投递不内联_排在当前任务之后() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        scheduler.execute(() -> {
            scheduler.execute(() -> {
                order.add("posted");
                done.countDown();
            });
            order.add("current");
        });
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(order).containsExactly("current", "posted");
    }

    @Test
    void 延迟任务到期执行_取消后不执行() throws Exception {
        CountDownLatch fired = new CountDownLatch(1);
        AtomicInteger cancelledRuns = new AtomicInteger();
        Cancellable cancelled = scheduler.after(50, cancelledRuns::incrementAndGet);
        cancelled.cancel();
        cancelled.cancel();
        scheduler.after(30, fired::countDown);
        assertThat(fired.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(100);
        assertThat(cancelledRuns).hasValue(0);
    }

    @Test
    void 周期任务抛异常后继续_取消后停止() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch three = new CountDownLatch(3);
        Cancellable every = scheduler.every(10, () -> {
            runs.incrementAndGet();
            three.countDown();
            throw new IllegalStateException("测试：回调抛异常不能停掉计时器");
        });
        assertThat(three.await(5, TimeUnit.SECONDS)).isTrue();

        CountDownLatch cancelledOnLoop = new CountDownLatch(1);
        scheduler.execute(() -> {
            every.cancel();
            cancelledOnLoop.countDown();
        });
        assertThat(cancelledOnLoop.await(5, TimeUnit.SECONDS)).isTrue();
        int afterCancel = runs.get();
        Thread.sleep(80);
        assertThat(runs.get()).as("逻辑线程上取消之后不再执行").isEqualTo(afterCancel);
        assertThatThrownBy(() -> scheduler.every(0, () -> { })).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 投递的任务抛异常被吞掉_逻辑线程继续工作() throws Exception {
        scheduler.execute(() -> {
            throw new IllegalStateException("测试：任务抛异常");
        });
        CountDownLatch alive = new CountDownLatch(1);
        scheduler.execute(alive::countDown);
        assertThat(alive.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(scheduler.pendingTasks()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void 逻辑线程关闭后投递被拒() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        assertThatThrownBy(() -> scheduler.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
    }
}
