package com.game.battle.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.battle.room.Cancellable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** 测试用虚拟时间调度器自身的行为（房间组件测试都建在它上面，先把它钉住）。 */
class ManualBattleSchedulerTest {

    private static final long T0 = 1_700_000_000_000L;
    private final ManualBattleScheduler scheduler = new ManualBattleScheduler(T0);
    private final List<String> log = new ArrayList<>();

    @Test
    void execute不内联_runPending时按序执行_含执行中新投递的() {
        scheduler.execute(() -> {
            log.add("a");
            scheduler.execute(() -> log.add("c"));
        });
        scheduler.execute(() -> log.add("b"));
        assertThat(log).isEmpty();
        assertThat(scheduler.pendingTasks()).isEqualTo(2);

        assertThat(scheduler.runPending()).isEqualTo(3);
        assertThat(log).containsExactly("a", "b", "c");
    }

    @Test
    void 计时器按到期顺序执行_同刻按登记先后_墙钟同步推进() {
        scheduler.after(6000, () -> log.add("round@" + (scheduler.clock().epochMillis() - T0)));
        scheduler.after(2000, () -> log.add("short@" + (scheduler.nowMs() - T0)));
        scheduler.after(6000, () -> log.add("second@" + (scheduler.nowMs() - T0)));

        scheduler.advance(5999);
        assertThat(log).containsExactly("short@2000");
        scheduler.advance(1);
        assertThat(log).containsExactly("short@2000", "round@6000", "second@6000");
        assertThat(scheduler.nowMs()).isEqualTo(T0 + 6000);
    }

    @Test
    void 周期任务首次在一个周期之后_取消后不再执行() {
        Cancellable every = scheduler.every(10_000, () -> log.add("resend@" + (scheduler.nowMs() - T0)));
        scheduler.advance(35_000);
        assertThat(log).containsExactly("resend@10000", "resend@20000", "resend@30000");

        every.cancel();
        scheduler.advance(100_000);
        assertThat(log).hasSize(3);
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 回调里取消另一个同刻到期的计时器_被取消的不执行() {
        Cancellable[] second = new Cancellable[1];
        scheduler.after(1000, () -> {
            log.add("first");
            second[0].cancel();
        });
        second[0] = scheduler.after(1000, () -> log.add("second"));
        scheduler.advance(1000);
        assertThat(log).containsExactly("first");
    }

    @Test
    void 计时器回调里投递的任务在下一个计时器之前执行() {
        scheduler.after(1000, () -> {
            log.add("timer1");
            scheduler.execute(() -> log.add("posted"));
        });
        scheduler.after(1000, () -> log.add("timer2"));
        scheduler.advance(1000);
        assertThat(log).containsExactly("timer1", "posted", "timer2");
    }

    @Test
    void 线程断言与关闭() {
        scheduler.assertInLoop();
        scheduler.setInLoop(false);
        assertThatThrownBy(scheduler::assertInLoop).isInstanceOf(IllegalStateException.class);

        scheduler.shutdown();
        assertThatThrownBy(() -> scheduler.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
        assertThatThrownBy(() -> scheduler.after(1, () -> { })).isInstanceOf(RejectedExecutionException.class);
    }
}
