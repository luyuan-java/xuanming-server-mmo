package com.game.data.consume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class AuditProbeBudgetTest {
    @Test void 共用剩余预算_最后不足一毫秒也不重获完整超时() {
        AtomicLong clock = new AtomicLong();
        var budget = new AuditProbeBudget(Duration.ofSeconds(1), clock::get);
        assertThat(budget.timeoutMillis()).isEqualTo(1000);
        clock.set(999_999_999);
        assertThat(budget.timeoutMillis()).isEqualTo(1);
        assertThat(budget.remainingNanos()).isEqualTo(1);
        clock.incrementAndGet();
        assertThatThrownBy(budget::remainingNanos).hasMessage("BUDGET");
    }

    @Test void nanoTime符号绕回按差值计算() {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 5);
        var budget = new AuditProbeBudget(Duration.ofSeconds(1), clock::get);
        clock.set(Long.MIN_VALUE + 4);
        assertThat(budget.remainingNanos()).isEqualTo(1_000_000_000L - 10);
    }

    @Test void 已中断线程不得再发新请求_中断标志不清掉() {
        var budget = new AuditProbeBudget(Duration.ofSeconds(1), () -> 0L);
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(budget::remainingNanos).hasMessage("INTERRUPTED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }

    @Test void 默认配置持续探测且覆盖两目标预算() {
        var properties = new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("game.data.audit-probe", AuditProbeProperties.class);
        assertThat(properties).isEqualTo(new AuditProbeProperties(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(90)));
    }

    @Test void 错误配置不能制造无界请求或长期陈值() {
        assertThatThrownBy(() -> new AuditProbeProperties(Duration.ZERO, Duration.ofSeconds(10), Duration.ofSeconds(90)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditProbeProperties(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditProbeProperties(Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(40)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
