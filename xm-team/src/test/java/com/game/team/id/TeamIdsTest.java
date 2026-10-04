package com.game.team.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.id.Snowflake;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** team_id 发号（team-spec §6.9）：worker 来自租约，租约无效时拒绝发号（CreateTeam 回 4030）。 */
class TeamIdsTest {

    @Test
    void 发号带上租约给的worker且不重复非0() {
        long[] clock = {Snowflake.DEFAULT_EPOCH_MS + 1_000};
        TeamIds ids = new TeamIds(new Snowflake(513, Snowflake.DEFAULT_EPOCH_MS, () -> clock[0]++), () -> true);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            long id = ids.nextId();
            assertThat(id).isNotZero();
            assertThat(Snowflake.workerOf(id)).isEqualTo(513);
            assertThat(seen.add(id)).isTrue();
        }
    }

    @Test
    void 租约无效时拒绝发号_恢复后继续() {
        AtomicBoolean valid = new AtomicBoolean(true);
        TeamIds ids = new TeamIds(new Snowflake(1), valid::get);
        assertThat(ids.nextId()).isPositive();
        valid.set(false);
        assertThatThrownBy(ids::nextId).isInstanceOf(IllegalStateException.class).hasMessageContaining("租约无效");
        valid.set(true);
        assertThat(ids.nextId()).isPositive();
    }
}
