package com.game.common.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** 逐条照搬基线 go/shared/gameday/gameday_test.go。 */
class GameDayTest {

    /** UTC+8 时区的时刻。 */
    private static Instant at(int y, int m, int d, int hh, int mm, int ss) {
        return LocalDateTime.of(y, m, d, hh, mm, ss).toInstant(GameDay.ZONE);
    }

    private static Instant utc(int y, int m, int d, int hh, int mm, int ss) {
        return LocalDateTime.of(y, m, d, hh, mm, ss).toInstant(ZoneOffset.UTC);
    }

    @Test
    void 日键() {
        assertThat(GameDay.dayKey(at(2026, 9, 16, 4, 59, 59))).as("切点前一秒仍算前一天").isEqualTo(20260915);
        assertThat(GameDay.dayKey(at(2026, 9, 16, 5, 0, 0))).as("切点整算当天").isEqualTo(20260916);
        assertThat(GameDay.dayKey(at(2026, 9, 16, 0, 0, 0))).as("午夜到切点之间算前一天").isEqualTo(20260915);
        assertThat(GameDay.dayKey(at(2026, 10, 1, 4, 0, 0))).as("跨月").isEqualTo(20260930);
        assertThat(GameDay.dayKey(at(2027, 1, 1, 4, 59, 59))).as("跨年").isEqualTo(20261231);
        assertThat(GameDay.dayKey(utc(2026, 9, 15, 21, 0, 0))).as("UTC 入参：UTC 21:00 就是 UTC+8 次日 05:00").isEqualTo(20260916);
        assertThat(GameDay.dayKey(utc(2026, 9, 15, 20, 59, 59))).as("UTC 入参切点前一秒").isEqualTo(20260915);
        assertThat(GameDay.dayKey(at(2026, 9, 21, 4, 59, 59).plusMillis(999))).as("毫秒级切点前").isEqualTo(20260920);
        assertThat(GameDay.dayKey(at(2026, 9, 16, 5, 0, 0).toEpochMilli())).as("毫秒入参").isEqualTo(20260916);
    }

    @Test
    void 周键() {
        // 2026-09-14 是周一
        assertThat(GameDay.weekKey(at(2026, 9, 14, 5, 0, 0))).as("周一切点整进入新一周").isEqualTo(202638);
        assertThat(GameDay.weekKey(at(2026, 9, 14, 4, 59, 59))).as("周一切点前一秒仍属上一周").isEqualTo(202637);
        assertThat(GameDay.weekKey(at(2026, 9, 20, 23, 59, 59))).as("周日深夜属本周").isEqualTo(202638);
        assertThat(GameDay.weekKey(at(2027, 1, 1, 5, 0, 0))).as("跨年周取 ISO 年").isEqualTo(202653);
        assertThat(GameDay.weekKey(at(2027, 1, 4, 5, 0, 0))).as("跨年周之后回到第 1 周").isEqualTo(202701);
        assertThat(GameDay.weekKey(at(2024, 12, 30, 5, 0, 0))).as("ISO 年大于自然年").isEqualTo(202501);
    }

    @Test
    void 周键随时间不减() {
        Instant start = at(2026, 12, 1, 5, 0, 0);
        int prev = GameDay.weekKey(start);
        for (int day = 1; day <= 60; day++) {
            int cur = GameDay.weekKey(start.plus(day, ChronoUnit.DAYS));
            assertThat(cur).as("第 %d 天", day).isGreaterThanOrEqualTo(prev);
            prev = cur;
        }
    }

    @Test
    void 下一个切日时刻() {
        check日(at(2026, 9, 16, 5, 0, 0), at(2026, 9, 17, 5, 0, 0));
        check日(at(2026, 9, 16, 4, 0, 0), at(2026, 9, 16, 5, 0, 0));
        check日(at(2026, 9, 16, 5, 0, 1), at(2026, 9, 17, 5, 0, 0));
        check日(at(2026, 9, 30, 12, 0, 0), at(2026, 10, 1, 5, 0, 0));
        check日(at(2026, 12, 31, 23, 0, 0), at(2027, 1, 1, 5, 0, 0));
        assertThat(GameDay.nextDailyResetMillis(at(2026, 9, 16, 4, 0, 0).toEpochMilli()))
                .isEqualTo(at(2026, 9, 16, 5, 0, 0).toEpochMilli());
    }

    private static void check日(Instant in, Instant want) {
        Instant got = GameDay.nextDailyReset(in);
        assertThat(got).isEqualTo(want);
        assertThat(got).isAfter(in);
        assertThat(GameDay.dayKey(got)).isGreaterThan(GameDay.dayKey(in));
    }

    @Test
    void 下一个切周时刻() {
        check周(at(2026, 9, 16, 12, 0, 0), at(2026, 9, 21, 5, 0, 0));
        check周(at(2026, 9, 14, 5, 0, 0), at(2026, 9, 21, 5, 0, 0));
        check周(at(2026, 9, 14, 4, 59, 59), at(2026, 9, 14, 5, 0, 0));
        check周(at(2026, 9, 20, 23, 0, 0), at(2026, 9, 21, 5, 0, 0));
        check周(at(2026, 12, 30, 12, 0, 0), at(2027, 1, 4, 5, 0, 0));
    }

    private static void check周(Instant in, Instant want) {
        Instant got = GameDay.nextWeeklyReset(in);
        assertThat(got).isEqualTo(want);
        assertThat(got).isAfter(in);
        assertThat(got.atOffset(GameDay.ZONE).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(GameDay.weekKey(got)).isNotEqualTo(GameDay.weekKey(in));
    }

    @Test
    void 周期键() {
        Instant now = at(2026, 9, 16, 12, 0, 0);
        assertThat(GameDay.periodKey(GameDay.PERIOD_NONE, now)).as("不限：键为 0，不占计数行").isEqualTo(OptionalInt.of(0));
        assertThat(GameDay.periodKey(GameDay.PERIOD_DAILY, now)).isEqualTo(OptionalInt.of(20260916));
        assertThat(GameDay.periodKey(GameDay.PERIOD_WEEKLY, now)).isEqualTo(OptionalInt.of(202638));
        assertThat(GameDay.periodKey(3, now)).as("未知周期拒绝").isEmpty();
        assertThat(GameDay.periodKey(-1, now)).as("uint32 大值按未知拒绝").isEmpty();
    }

    @Test
    void 日键与周键数值域不相交() {
        int minDayKey = 19700101;
        int maxWeekKey = 999953;
        for (Instant in : new Instant[] {at(1970, 1, 2, 5, 0, 0), at(2026, 9, 16, 12, 0, 0), at(9999, 12, 31, 12, 0, 0)}) {
            assertThat(GameDay.dayKey(in)).isGreaterThanOrEqualTo(minDayKey);
            assertThat(GameDay.weekKey(in)).isLessThanOrEqualTo(maxWeekKey);
        }
    }
}
