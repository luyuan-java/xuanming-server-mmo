package com.game.match.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 直连客户端缓存的定时清扫（{@link NodeClientSweeper}）：每个缓存每轮清一次；某个缓存出错（哪怕是 Error）不影响别的、也不让调度停转；
 * 在自己的守护线程上跑；启停幂等。
 */
class NodeClientSweeperTest {

    /** 记下被清了几次、在哪条线程上；可以设成每次都抛。 */
    private static final class CountingSweep implements IdleSweep {
        final String name;
        final AtomicInteger sweeps = new AtomicInteger();
        final Set<String> threads = ConcurrentHashMap.newKeySet();
        volatile Throwable error;
        volatile int evictedPerSweep;

        CountingSweep(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int sweepIdle() {
            sweeps.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            Throwable t = error;
            if (t instanceof Error fatal) {
                throw fatal;
            }
            if (t instanceof RuntimeException failure) {
                throw failure;
            }
            return evictedPerSweep;
        }
    }

    private static void awaitSweeps(CountingSweep sweep, int atLeast) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (sweep.sweeps.get() < atLeast && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(sweep.sweeps.get()).as("%s 在时限内被清扫的次数", sweep.name).isGreaterThanOrEqualTo(atLeast);
    }

    @Test
    void 一轮把每个缓存各清一次_返回清掉的总数() {
        CountingSweep scene = new CountingSweep("scene");
        CountingSweep battle = new CountingSweep("battle");
        scene.evictedPerSweep = 2;
        battle.evictedPerSweep = 3;
        NodeClientSweeper sweeper = new NodeClientSweeper(List.of(scene, battle), Duration.ofSeconds(60));

        assertThat(sweeper.sweepOnce()).isEqualTo(5);

        assertThat(scene.sweeps.get()).isEqualTo(1);
        assertThat(battle.sweeps.get()).isEqualTo(1);
        assertThat(sweeper.isRunning()).as("没调 start：没有线程").isFalse();
    }

    @Test
    void 一个缓存出错不影响排在它后面的_也不抛出来() {
        CountingSweep broken = new CountingSweep("broken");
        CountingSweep fatal = new CountingSweep("fatal");
        CountingSweep healthy = new CountingSweep("healthy");
        broken.error = new IllegalStateException("注入的故障");
        fatal.error = new StackOverflowError("注入的 Error");
        healthy.evictedPerSweep = 1;
        NodeClientSweeper sweeper = new NodeClientSweeper(List.of(broken, fatal, healthy), Duration.ofSeconds(60));

        assertThat(sweeper.sweepOnce()).isEqualTo(1);

        assertThat(healthy.sweeps.get()).isEqualTo(1);
    }

    @Test
    void 启动之后在自己的守护线程上按间隔反复清_出错的一轮之后下一轮照常_关闭后停下() throws Exception {
        CountingSweep broken = new CountingSweep("broken");
        CountingSweep healthy = new CountingSweep("healthy");
        broken.error = new IllegalStateException("注入的故障");
        NodeClientSweeper sweeper = new NodeClientSweeper(List.of(broken, healthy), Duration.ofMillis(10));
        try {
            sweeper.start();
            sweeper.start();
            assertThat(sweeper.isRunning()).isTrue();

            awaitSweeps(healthy, 3);
            awaitSweeps(broken, 3);

            assertThat(healthy.threads).as("不占调用方的线程").containsExactly(NodeClientSweeper.THREAD_NAME);
        } finally {
            sweeper.close();
        }
        assertThat(sweeper.isRunning()).isFalse();
        int after = healthy.sweeps.get();
        Thread.sleep(80);
        assertThat(healthy.sweeps.get()).as("关闭之后不再清（最多放过关闭那一刻正在跑的一轮）").isLessThanOrEqualTo(after + 1);

        sweeper.close();
        sweeper.start();
        assertThat(sweeper.isRunning()).as("关闭之后不可再启动").isFalse();
    }

    @Test
    void 间隔必须为正() {
        assertThatThrownBy(() -> new NodeClientSweeper(List.of(), Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
    }
}
