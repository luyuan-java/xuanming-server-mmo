package com.game.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class LeaseGatedSnowflakeTest {

    @Test
    void 租约有效时发号_带租约的_worker() {
        AtomicLong clock = new AtomicLong(Snowflake.DEFAULT_EPOCH_MS + 1000);
        LeaseGatedSnowflake ids = new LeaseGatedSnowflake(new Snowflake(7, Snowflake.DEFAULT_EPOCH_MS, clock::get), () -> true);
        OptionalLong a = ids.tryNext();
        OptionalLong b = ids.tryNext();
        assertThat(a).isPresent();
        assertThat(b).isPresent();
        assertThat(b.getAsLong()).isGreaterThan(a.getAsLong());
        assertThat(Snowflake.workerOf(a.getAsLong())).isEqualTo(7);
        assertThat(ids.leaseValid()).isTrue();
    }

    @Test
    void 租约无效时不发号_恢复后自动恢复() {
        AtomicBoolean valid = new AtomicBoolean(false);
        AtomicLong clock = new AtomicLong(Snowflake.DEFAULT_EPOCH_MS + 1000);
        LeaseGatedSnowflake ids = new LeaseGatedSnowflake(new Snowflake(1, Snowflake.DEFAULT_EPOCH_MS, clock::get), valid::get);
        assertThat(ids.tryNext()).isEmpty();
        assertThat(ids.leaseValid()).isFalse();
        valid.set(true);
        assertThat(ids.tryNext()).isPresent();
    }

    @Test
    void 时钟回拨超出容忍_返回空而不抛() {
        AtomicLong clock = new AtomicLong(Snowflake.DEFAULT_EPOCH_MS + 1000);
        LeaseGatedSnowflake ids = new LeaseGatedSnowflake(new Snowflake(1, Snowflake.DEFAULT_EPOCH_MS, clock::get), () -> true);
        assertThat(ids.tryNext()).isPresent();
        clock.addAndGet(-(Snowflake.MAX_BACKWARD_MS + 10));
        assertThat(ids.tryNext()).isEmpty();
    }

    @Test
    void 发出_0_时拒绝() {
        // worker 0、纪元当毫秒、序号 0 → 0：生产上 worker 来自 [1, 1023] 的租约不会出现，这里只验证防线
        LeaseGatedSnowflake ids = new LeaseGatedSnowflake(
                new Snowflake(0, Snowflake.DEFAULT_EPOCH_MS, () -> Snowflake.DEFAULT_EPOCH_MS), () -> true);
        assertThat(ids.tryNext()).isEmpty();
    }
}
