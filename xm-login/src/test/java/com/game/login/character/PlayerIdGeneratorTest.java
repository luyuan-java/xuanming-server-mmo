package com.game.login.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.id.Snowflake;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PlayerIdGeneratorTest {

    @Test
    void 发号带上租约给的worker且不重复() {
        long[] clock = {Snowflake.DEFAULT_EPOCH_MS + 1_000};
        PlayerIdGenerator ids = new PlayerIdGenerator(new Snowflake(37, Snowflake.DEFAULT_EPOCH_MS, () -> clock[0]++),
                () -> true);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long id = ids.nextId();
            assertThat(Snowflake.workerOf(id)).isEqualTo(37);
            assertThat(seen.add(id)).isTrue();
        }
    }

    @Test
    void 租约无效时拒绝发号_恢复有效后继续发号() {
        // 生产中 valid 来自 NodeIdLease::isValid：丢失，或续期滞后超过 2/3 TTL（未丢失）都为假。
        AtomicBoolean valid = new AtomicBoolean(true);
        PlayerIdGenerator ids = new PlayerIdGenerator(new Snowflake(1), valid::get);
        assertThat(ids.nextId()).isPositive();

        valid.set(false);
        assertThatThrownBy(ids::nextId).isInstanceOf(IllegalStateException.class).hasMessageContaining("租约无效");

        valid.set(true);
        assertThat(ids.nextId()).isPositive();
    }
}
