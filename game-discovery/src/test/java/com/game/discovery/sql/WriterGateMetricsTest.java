package com.game.discovery.sql;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.sql.WriterAuthority;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 写者闸门阶段耗时指标绑定单测（对齐主仓 {@code writer_gate_seconds{logical_db, phase}}）。 */
class WriterGateMetricsTest {

    @AfterEach
    void resetObserver() {
        WriterAuthority.setGateObserver(WriterAuthority.GateObserver.NOOP);
    }

    @Test
    void 绑定Micrometer后按logicalDb和phase记录各阶段耗时与12档指数桶() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        WriterAuthority.GateObserver observer = AuthorityDataSourcePolicy.micrometerGateObserver(meters);
        WriterAuthority.setGateObserver(observer);

        observer.observe("game", WriterAuthority.PHASE_BEFORE_BEGIN, TimeUnit.MICROSECONDS.toNanos(400));
        observer.observe("game", WriterAuthority.PHASE_ENTER, TimeUnit.MILLISECONDS.toNanos(2));
        observer.observe("game", WriterAuthority.PHASE_PIN, TimeUnit.MILLISECONDS.toNanos(5));

        Timer before = meters.get("writer.gate").tag("logical_db", "game").tag("phase", "before_begin").timer();
        Timer enter = meters.get("writer.gate").tag("logical_db", "game").tag("phase", "enter").timer();
        Timer pin = meters.get("writer.gate").tag("logical_db", "game").tag("phase", "pin").timer();

        assertThat(before.count()).isEqualTo(1L);
        assertThat(enter.count()).isEqualTo(1L);
        assertThat(pin.count()).isEqualTo(1L);
        assertThat(AuthorityDataSourcePolicy.GATE_BUCKETS).hasSize(12);
        assertThat(enter.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(2.0);
    }
}
