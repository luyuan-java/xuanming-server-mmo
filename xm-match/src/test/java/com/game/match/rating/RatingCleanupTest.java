package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 入账标记的保留期清理（match-spec §5.2「清理」）：7 天之前的分批删，删到不足一批为止；失败留给下个周期。 */
class RatingCleanupTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long SEVEN_DAYS_MS = Duration.ofDays(7).toMillis();

    @Test
    void 一轮清理_截止时刻是七天前_分批删到不足一批为止() {
        List<long[]> asked = new ArrayList<>();
        int[] batches = {3, 3, 1};
        AtomicInteger round = new AtomicInteger();
        try (RatingCleanup cleanup = new RatingCleanup((before, limit) -> {
            asked.add(new long[] {before, limit});
            return batches[round.getAndIncrement()];
        }, () -> NOW, 3, 0)) {

            long deleted = cleanup.purgeOnce();

            assertThat(deleted).isEqualTo(7);
            assertThat(asked).hasSize(3);
            assertThat(asked).allSatisfy(call -> assertThat(call).containsExactly(NOW - SEVEN_DAYS_MS, 3));
        }
    }

    @Test
    void 没有过期的行_只问一次() {
        AtomicInteger calls = new AtomicInteger();
        try (RatingCleanup cleanup = new RatingCleanup((before, limit) -> {
            calls.incrementAndGet();
            return 0;
        }, () -> NOW, 500, 0)) {

            assertThat(cleanup.purgeOnce()).isZero();
            assertThat(calls.get()).isEqualTo(1);
        }
    }

    @Test
    void 正好删满一批_再问一次确认删完() {
        int[] batches = {2, 0};
        AtomicInteger round = new AtomicInteger();
        try (RatingCleanup cleanup = new RatingCleanup((before, limit) -> batches[round.getAndIncrement()], () -> NOW, 2, 0)) {

            assertThat(cleanup.purgeOnce()).isEqualTo(2);
            assertThat(round.get()).isEqualTo(2);
        }
    }

    @Test
    void 删除失败_这一轮抛给调度的保护壳_关闭后不再删() {
        RatingCleanup cleanup = new RatingCleanup((before, limit) -> {
            throw new RatingStore.StoreException("清理入账标记失败", new SQLException("Lock wait timeout exceeded", "HY000", 1205));
        }, () -> NOW, 500, 0);

        assertThatThrownBy(cleanup::purgeOnce).isInstanceOf(RatingStore.StoreException.class);

        AtomicInteger calls = new AtomicInteger();
        RatingCleanup closed = new RatingCleanup((before, limit) -> {
            calls.incrementAndGet();
            return 500;
        }, () -> NOW, 500, 0);
        closed.close();
        assertThat(closed.purgeOnce()).as("停机中：一批都不删").isZero();
        assertThat(calls.get()).isZero();
        cleanup.close();
    }

    @Test
    void 接真的存储_七天前的标记被删_七天内的与评分行留着() {
        try (RatingTestDatabase db = RatingTestDatabase.h2()) {
            MatchMetrics metrics = new MatchMetrics(new SimpleMeterRegistry(), new MetricLabels(id -> false));
            RatingStore store = new RatingStore(RatingStore.connections(db.dataSource), configId -> 30, metrics, () -> NOW);
            for (long battle = 1; battle <= 1_203; battle++) {
                db.putApplied(battle, NOW - SEVEN_DAYS_MS - battle);
            }
            db.putApplied(5_001, NOW - SEVEN_DAYS_MS);
            db.putApplied(5_002, NOW - 1);
            db.putRating(1, 151_600, 1);

            try (RatingCleanup cleanup = new RatingCleanup(store, () -> NOW)) {
                long deleted = cleanup.purgeOnce();

                assertThat(deleted).as("三批：500 + 500 + 203").isEqualTo(1_203);
                assertThat(db.count("match_rating_applied")).isEqualTo(2);
                assertThat(db.appliedRow(5_001)).as("正好七天整的不删（早于才删）").isPresent();
                assertThat(db.appliedRow(5_002)).isPresent();
                assertThat(db.count("match_rating")).as("评分行永不清理").isEqualTo(1);
                assertThat(cleanup.purgeOnce()).isZero();
            }
        }
    }

    @Test
    void 间隔与批量() {
        assertThat(RatingCleanup.RETENTION).isEqualTo(Duration.ofDays(7));
        assertThat(RatingCleanup.INTERVAL).isEqualTo(Duration.ofHours(1));
        assertThat(RatingCleanup.BATCH).isEqualTo(500);
    }
}
