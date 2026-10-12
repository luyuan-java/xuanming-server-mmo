package com.game.trade.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.id.Snowflake;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** listing_id 发号器（trade-spec §5.7、§9.2）：租约有效才发号、发出的号唯一且恒 &lt; 2^63、带上 worker、0 与时钟回拨都拒绝。 */
class ListingIdsTest {

    @Test
    void 租约有效时发唯一的正号_带worker_租约失效即拒绝_恢复后自动恢复() {
        AtomicBoolean valid = new AtomicBoolean(true);
        ListingIds ids = new ListingIds(new Snowflake(37), valid::get);

        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            long id = ids.nextId();
            assertThat(id).as("雪花符号位恒 0：robot 用 MaxInt64 当「不存在」仍安全").isPositive();
            assertThat(Snowflake.workerOf(id)).isEqualTo(37);
            assertThat(seen.add(id)).isTrue();
        }

        valid.set(false);
        assertThatThrownBy(ids::nextId).isInstanceOf(IllegalStateException.class).hasMessageContaining("租约无效");
        valid.set(true);
        assertThat(ids.nextId()).isPositive();
    }

    @Test
    void 发出0或时钟回拨都拒绝() {
        // worker 0、序号 0、时间恰为纪元 → 0
        ListingIds zero = new ListingIds(new Snowflake(0, 1_000L, () -> 1_000L), () -> true);
        assertThatThrownBy(zero::nextId).isInstanceOf(IllegalStateException.class).hasMessageContaining("0");

        AtomicLong clock = new AtomicLong(10_000L);
        ListingIds backwards = new ListingIds(new Snowflake(1, 1_000L, clock::get), () -> true);
        backwards.nextId();
        clock.set(9_000L);
        assertThatThrownBy(backwards::nextId).isInstanceOf(IllegalStateException.class).hasMessageContaining("时钟回拨");
    }
}
