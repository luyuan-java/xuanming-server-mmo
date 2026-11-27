package com.game.common.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.net.ConnectionAdmissionController.AdmitOutcome;
import com.game.common.net.ConnectionAdmissionController.PreAuthEntry;
import com.game.common.net.ConnectionAdmissionController.RejectReason;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 边缘连接准入策略与总控单测（覆盖主仓 {@code connection_admission_test.cpp} 同名场景）：
 * 默认推导与环境变量拒启、判定顺序（关源->总量->未验签->全局速率->来源限速）、承压先淘汰后线性早拒、
 * 事件循环卡顿不淘汰/推迟超时收割、全局速率拒绝触发 10s 来源限速、池头有序收割且跳过已在关连接。
 */
class ConnectionAdmissionControllerTest {

    @Test
    void 策略默认推导与非法覆写拒启() {
        var p20k = ConnectionAdmissionPolicy.defaults(20_000, 10_000L);
        assertThat(p20k.acceptRatePerSecond()).isEqualTo(200);
        assertThat(p20k.acceptBurst()).isEqualTo(20_000);
        assertThat(p20k.preAuthBudget()).isEqualTo(2_000);
        assertThat(p20k.preAuthEarlyDropStart()).isEqualTo(1_000);
        assertThat(p20k.preAuthEvictableAgeMs()).isEqualTo(3_000L);

        var p5k = ConnectionAdmissionPolicy.defaults(5_000, 10_000L);
        assertThat(p5k.acceptRatePerSecond()).isEqualTo(100);
        assertThat(p5k.acceptBurst()).isEqualTo(5_000);
        assertThat(p5k.preAuthBudget()).isEqualTo(1_000);
        assertThat(p5k.preAuthEarlyDropStart()).isEqualTo(500);

        // 只设预算时早拒起点自动取一半
        var custom = ConnectionAdmissionPolicy.fromEnv("GATE_", 5_000, 10_000L,
                Map.of("GATE_PREAUTH_BUDGET", "400", "GATE_PREAUTH_EVICTABLE_AGE_MS", "2500")::get);
        assertThat(custom.preAuthBudget()).isEqualTo(400);
        assertThat(custom.preAuthEarlyDropStart()).isEqualTo(200);
        assertThat(custom.preAuthEvictableAgeMs()).isEqualTo(2_500L);

        // 0、非数字、起点大于预算、可淘汰年龄 >= 握手期限一律拒启
        assertThatThrownBy(() -> ConnectionAdmissionPolicy.fromEnv("GATE_", 5_000, 10_000L,
                Map.of("GATE_ACCEPT_RATE_PER_SECOND", "0")::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectionAdmissionPolicy.fromEnv("GATE_", 5_000, 10_000L,
                Map.of("GATE_ACCEPT_BURST", "+100")::get)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectionAdmissionPolicy.fromEnv("GATE_", 5_000, 10_000L,
                Map.of("GATE_PREAUTH_BUDGET", "100", "GATE_PREAUTH_EARLY_DROP_START", "101")::get))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConnectionAdmissionPolicy.fromEnv("GATE_", 5_000, 10_000L,
                Map.of("GATE_PREAUTH_EVICTABLE_AGE_MS", "10000")::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 判定顺序_关源先于总量_满员不耗速率_全局拒绝不淘汰() throws Exception {
        AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        AtomicInteger verified = new AtomicInteger(0);
        LoopStallDetector stall = new LoopStallDetector();
        stall.recordBeat(clock.get());
        // max=4, rate=1/s, burst=2, budget=4, earlyStart=2, evictAge=3000ms
        var policy = ConnectionAdmissionPolicy.of(4, 10_000L, 1, 2, 4, 2, 3_000L, null, null);
        var ctrl = new ConnectionAdmissionController<String>(policy, verified::get, clock::get, () -> 0.99, stall);
        InetAddress ip = InetAddress.getByName("198.51.100.10");

        // 1. 满员拒绝不消耗速率名额
        verified.set(4);
        assertThat(ctrl.tryAdmit(ip, "c-cap").rejectReason()).isEqualTo(RejectReason.CAPACITY);
        assertThat(ctrl.tryAdmit(ip, "c-cap2").rejectReason()).isEqualTo(RejectReason.CAPACITY);

        // 2. 释放容量后，burst=2 的两个名额完好无损
        verified.set(0);
        AdmitOutcome<String> a1 = ctrl.tryAdmit(ip, "c1");
        AdmitOutcome<String> a2 = ctrl.tryAdmit(ip, "c2");
        assertThat(a1.admitted()).isTrue();
        assertThat(a2.admitted()).isTrue();

        // 3. 推进 4 秒使 c1 超过可淘汰年龄，但紧接着连发请求耗尽 burst -> 第 3 条被 ACCEPT_RATE 拒，不得淘汰 c1
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        stall.recordBeat(clock.get());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        stall.recordBeat(clock.get());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        stall.recordBeat(clock.get());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        stall.recordBeat(clock.get());
        // 4s 内 GCRA 补满 burst=2：先消费 2 条（第 1 条淘汰 c1，第 2 条淘汰 c2），第 3 条命全局速率拒绝，绝不淘汰 c3
        AdmitOutcome<String> a3 = ctrl.tryAdmit(ip, "c3");
        assertThat(a3.admitted()).isTrue();
        assertThat(a3.evicted().attachment()).isEqualTo("c1");
        AdmitOutcome<String> a4 = ctrl.tryAdmit(ip, "c4");
        assertThat(a4.admitted()).isTrue();
        assertThat(a4.evicted().attachment()).isEqualTo("c2");
        assertThat(ctrl.preAuthCount()).isEqualTo(2);

        // 此时速率已空，立即再发被 ACCEPT_RATE 拒，且未淘汰 c3/c4
        AdmitOutcome<String> rRate = ctrl.tryAdmit(ip, "c5");
        assertThat(rRate.admitted()).isFalse();
        assertThat(rRate.rejectReason()).isEqualTo(RejectReason.ACCEPT_RATE);
        assertThat(ctrl.preAuthCount()).isEqualTo(2);

        // 4. 关源优先级高于总量
        verified.set(4);
        ctrl.setShuttingDown(true);
        assertThat(ctrl.tryAdmit(ip, "c6").rejectReason()).isEqualTo(RejectReason.SHUTTING_DOWN);
    }

    @Test
    void 未验签先淘汰后线性早拒_卡顿不淘汰_跳过已在关连接() throws Exception {
        AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        double[] nextRand = {0.5};
        LoopStallDetector stall = new LoopStallDetector();
        stall.recordBeat(clock.get());
        // budget=10, earlyStart=5, evictAge=3000ms
        var policy = ConnectionAdmissionPolicy.of(100, 10_000L, 1000, 1000, 10, 5, 3_000L, null, null);
        var ctrl = new ConnectionAdmissionController<String>(policy, () -> 0, clock::get, () -> nextRand[0], stall);
        InetAddress ip = InetAddress.getByName("198.51.100.11");

        List<PreAuthEntry<String>> entries = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            entries.add(ctrl.tryAdmit(ip, "c" + i).entry());
        }
        // 6 条都在 0ms（未满 3s），n=6 >= S=5，线性早拒概率 = (6 - 5)/(10 - 5) = 0.2
        nextRand[0] = 0.19;
        assertThat(ctrl.tryAdmit(ip, "drop").rejectReason()).isEqualTo(RejectReason.PREAUTH_EARLY_DROP);
        nextRand[0] = 0.20;
        assertThat(ctrl.tryAdmit(ip, "pass").admitted()).isTrue(); // 现在 n=7

        // 推进 4 秒且未打节拍 -> 事件循环视为卡顿，不得淘汰 c0；填满至 budget=10 后返回 PREAUTH_BUDGET
        clock.addAndGet(TimeUnit.SECONDS.toNanos(4));
        nextRand[0] = 0.99;
        for (int i = 0; i < 3; i++) {
            AdmitOutcome<String> o = ctrl.tryAdmit(ip, "fill" + i);
            assertThat(o.admitted()).isTrue();
            assertThat(o.evicted()).isNull();
        }
        assertThat(ctrl.tryAdmit(ip, "full").rejectReason()).isEqualTo(RejectReason.PREAUTH_BUDGET);

        // 恢复连续两拍健康节拍（第一拍本身迟到仍算刚卡过，第二拍正常恢复）
        stall.recordBeat(clock.get());
        assertThat(stall.isStalled(clock.get())).isTrue();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        stall.recordBeat(clock.get());
        assertThat(stall.isStalled(clock.get())).isFalse();

        // 标记 c0 正在关闭 -> 淘汰跳过 c0 直接淘汰 c1
        entries.get(0).markClosing();
        AdmitOutcome<String> evict = ctrl.tryAdmit(ip, "new-conn");
        assertThat(evict.admitted()).isTrue();
        assertThat(evict.evicted().attachment()).isEqualTo("c1");
        assertThat(ctrl.preAuthEvictedTotal()).isEqualTo(1);
    }

    @Test
    void 全局速率拒绝激活一个握手期限的公网来源限速() throws Exception {
        AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(50));
        LoopStallDetector stall = new LoopStallDetector();
        stall.recordBeat(clock.get());
        // 全局 rate=10/s burst=2，来源 rate=1/s burst=1（未承压时不生效）
        var policy = ConnectionAdmissionPolicy.of(100, 10_000L, 10, 2, 100, 50, 3_000L, 1.0, 1.0);
        var ctrl = new ConnectionAdmissionController<String>(policy, () -> 0, clock::get, () -> 0.99, stall);
        InetAddress pubIp = InetAddress.getByName("203.0.113.7");

        // 未承压时消耗 burst=2，第 3 次触发全局速率拒绝 -> 激活 10s 来源限速
        assertThat(ctrl.tryAdmit(pubIp, "a").admitted()).isTrue();
        assertThat(ctrl.tryAdmit(pubIp, "b").admitted()).isTrue();
        assertThat(ctrl.tryAdmit(pubIp, "c").rejectReason()).isEqualTo(RejectReason.ACCEPT_RATE);
        assertThat(ctrl.isSourceRateActive()).isTrue();

        // 推进 200ms 全局补回 2 个名额，但来源桶 burst=1：第 1 条通过，第 2 条被 SOURCE_RATE 拒
        clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(200));
        assertThat(ctrl.tryAdmit(pubIp, "d").admitted()).isTrue();
        assertThat(ctrl.tryAdmit(pubIp, "e").rejectReason()).isEqualTo(RejectReason.SOURCE_RATE);

        // 推进超过 10s 握手期限后退出承压，来源限速恢复关闭
        clock.addAndGet(TimeUnit.SECONDS.toNanos(11));
        assertThat(ctrl.isSourceRateActive()).isFalse();
    }

    @Test
    void 握手期限只收割池头到期项_跳过已在关记录_卡顿推迟收割() throws Exception {
        AtomicLong clock = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        LoopStallDetector stall = new LoopStallDetector();
        stall.recordBeat(clock.get());
        var policy = ConnectionAdmissionPolicy.defaults(100, 10_000L);
        var ctrl = new ConnectionAdmissionController<String>(policy, () -> 0, clock::get, () -> 0.99, stall);
        InetAddress ip = InetAddress.getByName("10.0.0.1");

        PreAuthEntry<String> e1 = ctrl.tryAdmit(ip, "e1").entry();
        PreAuthEntry<String> e2 = ctrl.tryAdmit(ip, "e2").entry();
        e2.markClosing(); // 已因验签失败在关闭：收割只删记录、不计超时
        clock.addAndGet(TimeUnit.SECONDS.toNanos(6));
        stall.recordBeat(clock.get());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        stall.recordBeat(clock.get());
        PreAuthEntry<String> e3 = ctrl.tryAdmit(ip, "e3").entry(); // t=107s，尚未到期

        // 推进到 t=111s 但跳 4s 未打拍 -> 卡顿推迟收割，一个都不关
        clock.addAndGet(TimeUnit.SECONDS.toNanos(4));
        List<String> reaped = new ArrayList<>();
        assertThat(ctrl.sweepExpired(e -> reaped.add(e.attachment()))).isZero();
        assertThat(ctrl.preAuthCount()).isEqualTo(3);

        // 补两拍恢复健康 -> 只收割池头的 e1（计 1 次超时）与删掉 e2（不计超时），保留 e3
        stall.recordBeat(clock.get());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        stall.recordBeat(clock.get());
        assertThat(ctrl.sweepExpired(e -> reaped.add(e.attachment()))).isEqualTo(1);
        assertThat(reaped).containsExactly("e1");
        assertThat(ctrl.preAuthReapedTotal()).isEqualTo(1);
        assertThat(ctrl.preAuthCount()).isEqualTo(1);
        assertThat(ctrl.releasePreAuth(e3)).isTrue();
        assertThat(ctrl.preAuthCount()).isZero();
    }
}
