package com.game.match.id;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.id.Snowflake;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 发号器（match-spec §9.1、§9.8；lead 裁决 2）：租约有效才发号；battle_id 与 challenge_id 同源不重号、随时间递增；
 * 「续期滞后」与「真正丢失」是两个独立的信号。
 */
class MatchIdsTest {

    private final AtomicBoolean valid = new AtomicBoolean(true);
    private final AtomicBoolean lost = new AtomicBoolean(false);
    private final AtomicLong clockMs = new AtomicLong(Snowflake.DEFAULT_EPOCH_MS + 1_000_000);
    private final MatchIds ids = new MatchIds(new Snowflake(37, Snowflake.DEFAULT_EPOCH_MS, clockMs::get), valid::get, lost::get);

    @Test
    void 租约有效_两种号同源_互不重号_都不是0() {
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            long battleId = ids.nextBattleId().orElseThrow();
            long challengeId = ids.nextChallengeId().orElseThrow();
            assertThat(battleId).isNotZero();
            assertThat(challengeId).isNotZero();
            assertThat(seen.add(battleId)).as("battle_id 重号").isTrue();
            assertThat(seen.add(challengeId)).as("challenge_id 与 battle_id 重号").isTrue();
            assertThat(Snowflake.workerOf(battleId)).isEqualTo(37);
        }
    }

    @Test
    void battle_id随时间递增_时间在高位_scene按它当局序() {
        long first = ids.nextBattleId().orElseThrow();
        long sameMs = ids.nextBattleId().orElseThrow();
        clockMs.addAndGet(1);
        long nextMs = ids.nextBattleId().orElseThrow();
        clockMs.addAndGet(60_000);
        long later = ids.nextBattleId().orElseThrow();

        assertThat(Long.compareUnsigned(sameMs, first)).isPositive();
        assertThat(Long.compareUnsigned(nextMs, sameMs)).isPositive();
        assertThat(Long.compareUnsigned(later, nextMs)).isPositive();
        assertThat(later - nextMs).as("隔 60 s 的两个号差出整个时间段：时间戳在序号与 worker 之上")
                .isGreaterThan(60_000L << (Snowflake.WORKER_BITS + Snowflake.SEQUENCE_BITS - 1));
    }

    @Test
    void 续期滞后_停止发号_恢复后自动恢复_这期间不算丢失() {
        valid.set(false);

        assertThat(ids.leaseValid()).isFalse();
        assertThat(ids.leaseLost()).as("续期滞后不是丢失：会自愈").isFalse();
        assertThat(ids.nextBattleId()).as("绝不拿 0 或自造号顶替").isEmpty();
        assertThat(ids.nextChallengeId()).isEmpty();

        valid.set(true);
        assertThat(ids.leaseValid()).isTrue();
        assertThat(ids.nextBattleId()).isPresent();
    }

    @Test
    void 租约真正丢失_两个信号都报_不发号() {
        valid.set(false);
        lost.set(true);

        assertThat(ids.leaseLost()).isTrue();
        assertThat(ids.leaseValid()).isFalse();
        assertThat(ids.nextBattleId()).isEmpty();
        assertThat(ids.nextChallengeId()).isEmpty();
    }

    @Test
    void 时钟回拨超出容忍_发不出号而不是抛异常() {
        ids.nextBattleId().orElseThrow();
        clockMs.addAndGet(-600_000);

        assertThat(ids.nextBattleId()).isEmpty();
        assertThat(ids.leaseValid()).as("租约本身没问题").isTrue();
    }
}
