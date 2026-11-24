package com.game.scene.afkpass;

import java.util.OptionalLong;

/**
 * 挂机月卡纯规则内核（对齐 C++ {@code afk_pass_rules.h} 与 {@code docs/design/afk-monthly-card.md}）。
 * 无副作用、不依赖时钟或 IO，全量以 Unix 秒计数。
 */
public final class AfkPassRules {

    public static final long SECONDS_PER_DAY = 86_400L;
    /** GM 设定剩余时长的上限（366 天）。 */
    public static final long GM_MAX_REMAINING_SECONDS = 366L * SECONDS_PER_DAY;
    /** 规则表周期天数合法上限。 */
    public static final int MAX_PERIOD_DAYS = 366;
    /** 规则表叠加剩余天数合法上限。 */
    public static final int MAX_REMAINING_DAYS = 3660;
    /** 规则表价格合法上限。 */
    public static final long MAX_PRICE = 1_000_000_000L;
    /** 规则表提醒提前小时合法上限。 */
    public static final int MAX_REMIND_BEFORE_HOURS = 720;
    /** Redis 投影 TTL 缓冲（120 秒）：避免与到期判定时钟边界竞争。 */
    public static final long PROJECTION_TTL_BUFFER_SECONDS = 120L;
    /** 离线托管期间定期刷新 Redis 投影的周期（60 秒）。 */
    public static final long PROJECTION_REFRESH_PERIOD_SECONDS = 60L;

    private AfkPassRules() {
    }

    /** 此刻月卡是否处于有效期内（严格大于 nowUnixSec）。 */
    public static boolean isActive(long expiresAtSec, long nowUnixSec) {
        return Long.compareUnsigned(expiresAtSec, nowUnixSec) > 0;
    }

    /** 距离到期的剩余秒数；已到期或未开通返回 0。 */
    public static long remainingSeconds(long expiresAtSec, long nowUnixSec) {
        return isActive(expiresAtSec, nowUnixSec) ? (expiresAtSec - nowUnixSec) : 0L;
    }

    /**
     * 开通 / 续费后的新到期时刻：{@code max(now, currentExpiresAt) + periodDays * 86400}。
     * 若新剩余时长超过 {@code maxRemainingDays * 86400}（或参数非法/溢出），返回 {@link OptionalLong#empty()}。
     */
    public static OptionalLong nextExpiry(long currentExpiresAtSec, long nowUnixSec, int periodDays, int maxRemainingDays) {
        if (periodDays <= 0 || maxRemainingDays < periodDays || nowUnixSec < 0) {
            return OptionalLong.empty();
        }
        long base = isActive(currentExpiresAtSec, nowUnixSec) ? currentExpiresAtSec : nowUnixSec;
        long add = periodDays * SECONDS_PER_DAY;
        long maxRemaining = maxRemainingDays * SECONDS_PER_DAY;
        if (base > Long.MAX_VALUE - add) {
            return OptionalLong.empty();
        }
        long next = base + add;
        if (next - nowUnixSec > maxRemaining) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(next);
    }

    /** GM 设定到期时刻：0 = 清除，否则 nowUnixSec + remainingSeconds。 */
    public static long gmExpiry(long nowUnixSec, long remainingSeconds) {
        if (remainingSeconds <= 0) {
            return 0L;
        }
        return nowUnixSec + Math.min(remainingSeconds, GM_MAX_REMAINING_SECONDS);
    }

    /** 该到期值写入 Redis 投影时应执行 SET（true）还是 DEL（false）。 */
    public static boolean projectionIsSet(long expiresAtSec, long nowUnixSec) {
        return isActive(expiresAtSec, nowUnixSec);
    }

    /** 有效月卡的 Redis 投影 TTL（剩余秒数 + 120s 缓冲）。 */
    public static long projectionTtlSeconds(long expiresAtSec, long nowUnixSec) {
        long rem = remainingSeconds(expiresAtSec, nowUnixSec);
        return rem <= 0 ? 0L : rem + PROJECTION_TTL_BUFFER_SECONDS;
    }
}
