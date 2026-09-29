package com.game.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SnowflakeTest {

    @Test
    void 同一毫秒内序号递增_跨毫秒归零_且带_worker() {
        AtomicLong clock = new AtomicLong(Snowflake.DEFAULT_EPOCH_MS + 1000);
        Snowflake sf = new Snowflake(5, Snowflake.DEFAULT_EPOCH_MS, clock::get);
        long a = sf.nextId();
        long b = sf.nextId();
        assertThat(b).isEqualTo(a + 1);
        assertThat(Snowflake.workerOf(a)).isEqualTo(5);
        clock.incrementAndGet();
        long c = sf.nextId();
        assertThat(c & 0xFFF).isZero();
        assertThat(c).isGreaterThan(b);
    }

    @Test
    void 序号用尽时等到下一毫秒() {
        AtomicLong clock = new AtomicLong(Snowflake.DEFAULT_EPOCH_MS + 1000);
        AtomicLong calls = new AtomicLong();
        Snowflake sf = new Snowflake(1, Snowflake.DEFAULT_EPOCH_MS, () -> {
            // 同一毫秒内被调用足够多次后才推进时钟，模拟序号耗尽后的等待
            return calls.incrementAndGet() > 5000 ? clock.get() + 1 : clock.get();
        });
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 4097; i++) {
            ids.add(sf.nextId());
        }
        assertThat(ids).hasSize(4097);
    }

    @Test
    void 大幅回拨拒绝发号() {
        AtomicLong clock = new AtomicLong(Snowflake.DEFAULT_EPOCH_MS + 1000);
        Snowflake sf = new Snowflake(1, Snowflake.DEFAULT_EPOCH_MS, clock::get);
        sf.nextId();
        clock.addAndGet(-(Snowflake.MAX_BACKWARD_MS + 1));
        assertThatThrownBy(sf::nextId).hasMessageContaining("时钟回拨");
    }

    @Test
    void worker_越界拒绝() {
        assertThatThrownBy(() -> new Snowflake(Snowflake.MAX_WORKER + 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
