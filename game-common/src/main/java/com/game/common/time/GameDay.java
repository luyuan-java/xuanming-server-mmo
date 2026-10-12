package com.game.common.time;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.OptionalInt;

/**
 * 全服统一的「游戏日」与「游戏周」（基线 go/shared/gameday/gameday.go）：UTC+8、每日 05:00 切日；周按 ISO 周编号、周一 05:00 切周。
 * 只回答「这个时刻属于哪一天 / 哪一周、下一次切点是什么时候」。每日次数、每周限购、活动个人次数都按同一个切点重置——
 * 切点各写一份，迟早有一处写成 00:00 或写成本地时区。
 *
 * <p>边界（故意做得很窄，同基线）：
 * <ul>
 *   <li>不读墙钟：所有方法以时刻入参。调用方一次请求只取一次 now，周期键与各时间戳用同一个值，否则跨切点的请求会把「占用次数」与
 *       「展示的重置时刻」算进两个不同的周期；</li>
 *   <li>固定时区（{@link ZoneOffset}，不依赖 tzdata），没有夏令时；</li>
 *   <li>05:00 与 UTC+8 是全服契约而不是策划数值：改它会让已落库的周期键整体错位，所以不进配表。</li>
 * </ul>
 * 键用 {@code int}：日键 8 位（YYYYMMDD）、周键 6 位（ISO 周年 × 100 + 周号），都远小于 2^31，与基线的 uint32 同值。
 */
public final class GameDay {

    /** 切日的小时数（{@link #ZONE} 时区）。 */
    public static final int RESET_HOUR = 5;
    /** 游戏日所在的固定时区。 */
    public static final ZoneOffset ZONE = ZoneOffset.ofHours(8);

    /** 限购周期：不限（不占计数行），与配表 GuildShop.limit_period 的数值一致。 */
    public static final int PERIOD_NONE = 0;
    /** 限购周期：每游戏日。 */
    public static final int PERIOD_DAILY = 1;
    /** 限购周期：每游戏周。 */
    public static final int PERIOD_WEEKLY = 2;

    private GameDay() {
    }

    /** 把时刻平移到「05:00 即零点」的坐标系：平移后的自然日 / ISO 周就是游戏日 / 游戏周。 */
    private static LocalDate shiftedDate(Instant t) {
        return t.atOffset(ZONE).minusHours(RESET_HOUR).toLocalDate();
    }

    /** t 所属游戏日的 8 位键 YYYYMMDD（如 20260916）。05:00 之前算前一天。 */
    public static int dayKey(Instant t) {
        LocalDate d = shiftedDate(t);
        return d.getYear() * 10000 + d.getMonthValue() * 100 + d.getDayOfMonth();
    }

    public static int dayKey(long epochMillis) {
        return dayKey(Instant.ofEpochMilli(epochMillis));
    }

    /**
     * t 所属游戏周的 6 位键 YYYYWW（如 202638）。年份必须取 ISO 周所属的年，不能取自然年：2027-01-01 属于 2026 年第 53 周，键是 202653；
     * 写成自然年会得到 202753，比下一周的 202701 还大——键不再随时间单调，按「键 ≤ 截止键」做范围删除的清理任务会误判。
     */
    public static int weekKey(Instant t) {
        LocalDate d = shiftedDate(t);
        return d.get(IsoFields.WEEK_BASED_YEAR) * 100 + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    public static int weekKey(long epochMillis) {
        return weekKey(Instant.ofEpochMilli(epochMillis));
    }

    /** 严格晚于 t 的下一个切日时刻（{@link #ZONE} 的 05:00）。t 恰在 05:00:00 时返回次日 05:00。 */
    public static Instant nextDailyReset(Instant t) {
        return shiftedDate(t).plusDays(1).atTime(RESET_HOUR, 0).toInstant(ZONE);
    }

    public static long nextDailyResetMillis(long epochMillis) {
        return nextDailyReset(Instant.ofEpochMilli(epochMillis)).toEpochMilli();
    }

    /** 严格晚于 t 的下一个切周时刻（{@link #ZONE} 的周一 05:00）。t 恰在周一 05:00:00 时返回下周一。 */
    public static Instant nextWeeklyReset(Instant t) {
        LocalDate d = shiftedDate(t);
        int daysSinceMonday = d.getDayOfWeek().getValue() - 1;
        return d.plusDays(7 - daysSinceMonday).atTime(RESET_HOUR, 0).toInstant(ZONE);
    }

    public static long nextWeeklyResetMillis(long epochMillis) {
        return nextWeeklyReset(Instant.ofEpochMilli(epochMillis)).toEpochMilli();
    }

    /**
     * 按限购周期取周期键：{@link #PERIOD_NONE} → 0（不占计数行）；{@link #PERIOD_DAILY} → {@link #dayKey}；{@link #PERIOD_WEEKLY} →
     * {@link #weekKey}；其它值（含按无符号看的大值）→ 空，调用方应当作配置错误拒绝。日键（8 位）与周键（6 位）数值域不相交，
     * 清理任务靠这一点分别删日键行与周键行。
     */
    public static OptionalInt periodKey(int limitPeriod, Instant t) {
        return switch (limitPeriod) {
            case PERIOD_NONE -> OptionalInt.of(0);
            case PERIOD_DAILY -> OptionalInt.of(dayKey(t));
            case PERIOD_WEEKLY -> OptionalInt.of(weekKey(t));
            default -> OptionalInt.empty();
        };
    }

    public static OptionalInt periodKey(int limitPeriod, long epochMillis) {
        return periodKey(limitPeriod, Instant.ofEpochMilli(epochMillis));
    }
}
