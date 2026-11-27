package com.game.scene.rewardedad;

import com.game.player.store.state.RewardedAdLedgerState;
import com.game.scene.player.Wallet;

/**
 * 零拒审激励视频广告纯规则层（对齐 C++ {@code rewarded_ad_rules.h} 与 {@code docs/design/rewarded-ad-system.md}）。
 */
public final class RewardedAdRules {

    public static final long DAY_MS = 86_400_000L;
    public static final long WEEK_MS = 7L * DAY_MS;
    /** UTC+8 05:00 切日偏移（与装备材料自选/周软上限同源）。 */
    public static final long SHIFT_MS = 10_800_000L;
    /** 1970-01-05 05:00:00 UTC+8 = 1970-01-04 21:00:00 UTC（每周一 05:00 切周基准）。 */
    public static final long FIRST_MONDAY_05_UTC8_MS = 334_800_000L;
    public static final int MAX_DAILY_LIMIT = 20;
    public static final int MAX_SESSION_TTL_SEC = 3600;
    public static final long MAX_REWARD_AMOUNT = 100_000L;
    /** bits 0..3: 微信小游戏 / 抖音小游戏 / iOS App / Android App。 */
    public static final int ALL_PLATFORM_MASK = 0x0F;

    public record SlotConfig(
            int slotId,
            boolean enabled,
            int minLevel,
            int dailyLimit,
            int cooldownSec,
            int minWatchSec,
            int sessionTtlSec,
            int currencyType,
            long rewardAmount,
            boolean requireSsv,
            int platformMask) {
    }

    public enum SessionVerdict {
        OK,
        INVALID,
        TOO_SHORT,
        EXPIRED
    }

    private RewardedAdRules() {
    }

    /** 解析 nowMs 所属游戏日区间 [dayStartMs, nextDayMs)，切点 UTC+8 每日 05:00；异常/溢出返回 null。 */
    public static long[] resolveGameDay(long nowMs) {
        if (nowMs < 0L || nowMs > Long.MAX_VALUE - SHIFT_MS - DAY_MS) {
            return null;
        }
        long shifted = nowMs + SHIFT_MS;
        long dayIndex = shifted / DAY_MS;
        long dayStart = dayIndex == 0L ? 0L : (dayIndex * DAY_MS - SHIFT_MS);
        long nextDay = (dayIndex + 1L) * DAY_MS - SHIFT_MS;
        return new long[] { dayStart, nextDay };
    }

    /** 解析 nowMs 所属游戏周区间 [weekStartMs, nextWeekMs)，切点 UTC+8 每周一 05:00；异常/溢出返回 null。 */
    public static long[] resolveGameWeek(long nowMs) {
        if (nowMs < 0L || Long.compareUnsigned(nowMs, FIRST_MONDAY_05_UTC8_MS) < 0) {
            return null;
        }
        long offset = Long.remainderUnsigned(nowMs - FIRST_MONDAY_05_UTC8_MS, WEEK_MS);
        long weekStart = nowMs - offset;
        long nextWeek = weekStart + WEEK_MS;
        if (Long.compareUnsigned(nextWeek, weekStart) <= 0) {
            return null;
        }
        return new long[] { weekStart, nextWeek };
    }

    public static int platformBit(int clientPlatform) {
        if (clientPlatform < 1 || clientPlatform > 4) {
            return 0;
        }
        return 1 << (clientPlatform - 1);
    }

    public static boolean isPlatformAllowed(int clientPlatform, int platformMask) {
        int bit = platformBit(clientPlatform);
        return bit != 0 && (platformMask & bit) != 0;
    }

    /**
     * 强校验配置表行：激励视频只允许发放物理不可交易的 {@link Wallet#BOUND_GOLD} (3)，
     * 严禁误配成流通银两 (0) 或真灵石 (1)。返回 null 表示合法，否则为错误原因。
     */
    public static String validateSlotConfig(SlotConfig cfg) {
        if (cfg == null || cfg.slotId() == 0) {
            return "slot id is 0";
        }
        if (cfg.dailyLimit() <= 0 || Integer.compareUnsigned(cfg.dailyLimit(), MAX_DAILY_LIMIT) > 0) {
            return "daily_limit out of range 1..20";
        }
        if (cfg.minWatchSec() <= 0 || cfg.sessionTtlSec() <= 0
                || Integer.compareUnsigned(cfg.minWatchSec(), cfg.sessionTtlSec()) > 0
                || Integer.compareUnsigned(cfg.sessionTtlSec(), MAX_SESSION_TTL_SEC) > 0) {
            return "invalid min_watch_sec or session_ttl_sec";
        }
        if (cfg.currencyType() != Wallet.BOUND_GOLD) {
            return "rewarded ad slot must grant CURRENCY_TYPE_BIND_GOLD (3) only";
        }
        if (cfg.rewardAmount() <= 0L || Long.compareUnsigned(cfg.rewardAmount(), MAX_REWARD_AMOUNT) > 0) {
            return "reward_amount out of range 1..100000";
        }
        if ((cfg.platformMask() & ~ALL_PLATFORM_MASK) != 0) {
            return "platform_mask has unknown bits";
        }
        return null;
    }

    /** 抗时钟回拨的今日已领次数查询：只有 currentDayStartMs 严格前进才视为新的一天。 */
    public static int effectiveClaimedToday(RewardedAdLedgerState ledger, long currentDayStartMs) {
        if (Long.compareUnsigned(currentDayStartMs, ledger.getDayStartsAtMs()) > 0) {
            return 0;
        }
        return ledger.getClaimedToday();
    }

    /** 推进日账本（仅前进不清倒退）。 */
    public static void advanceDayIfNeeded(RewardedAdLedgerState.Builder ledger, long currentDayStartMs) {
        if (Long.compareUnsigned(currentDayStartMs, ledger.getDayStartsAtMs()) > 0) {
            ledger.setDayStartsAtMs(currentDayStartMs);
            ledger.setClaimedToday(0);
        }
    }

    public static long cooldownUntilMs(RewardedAdLedgerState ledger, int cooldownSec) {
        long lastClaim = ledger.getLastClaimAtMs();
        if (lastClaim == 0L || cooldownSec <= 0) {
            return 0L;
        }
        long deltaMs = Integer.toUnsignedLong(cooldownSec) * 1000L;
        if (lastClaim < 0L || lastClaim > Long.MAX_VALUE - deltaMs) {
            return Long.MAX_VALUE;
        }
        return lastClaim + deltaMs;
    }

    public static SessionVerdict verifyActiveSession(RewardedAdLedgerState ledger, int slotId,
                                                     long adSessionId, long nowMs,
                                                     int minWatchSec, int sessionTtlSec) {
        long issuedMs = ledger.getActiveSessionIssuedMs();
        if (adSessionId == 0L || ledger.getActiveSessionId() != adSessionId
                || ledger.getActiveSessionSlotId() != slotId || issuedMs == 0L) {
            return SessionVerdict.INVALID;
        }
        if (Long.compareUnsigned(nowMs, issuedMs) < 0) {
            return SessionVerdict.TOO_SHORT;
        }
        long elapsedMs = nowMs - issuedMs;
        if (Long.compareUnsigned(elapsedMs, Integer.toUnsignedLong(sessionTtlSec) * 1000L) > 0) {
            return SessionVerdict.EXPIRED;
        }
        if (Long.compareUnsigned(elapsedMs, Integer.toUnsignedLong(minWatchSec) * 1000L) < 0) {
            return SessionVerdict.TOO_SHORT;
        }
        return SessionVerdict.OK;
    }
}
