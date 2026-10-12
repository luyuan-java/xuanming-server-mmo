package com.game.gateway.ratelimit;

import java.util.Comparator;
import java.util.List;

/**
 * 分波开放（同 mmorpg WaveSchedule）：从起点起，当前生效的是 {@code offsetSec ≤ 已过秒数} 的最后一步；
 * 那一步的放开名单含 -1 或含该区即开放。分波关闭或没有步骤时一律开放；起点之前一律不开放。无状态，线程安全。
 */
public final class WaveSchedule {

    private final boolean enabled;
    private final long startEpochSec;
    private final List<RateLimitSettings.WaveStep> steps;

    public WaveSchedule(RateLimitSettings.Wave wave) {
        this.enabled = wave.enabled();
        this.startEpochSec = wave.startEpochSec() == null ? 0 : wave.startEpochSec();
        this.steps = wave.schedule().stream().sorted(Comparator.comparingLong(RateLimitSettings.WaveStep::offsetSec))
                .toList();
    }

    public boolean isOpen(int zoneId, long nowEpochSec) {
        if (!enabled || steps.isEmpty()) {
            return true;
        }
        long elapsed = nowEpochSec - startEpochSec;
        RateLimitSettings.WaveStep current = null;
        for (RateLimitSettings.WaveStep step : steps) {
            if (step.offsetSec() <= elapsed) {
                current = step;
            } else {
                break;
            }
        }
        return current != null && allows(current, zoneId);
    }

    /** 距这个区开放还要多少秒；已开放为 0；以后的步骤都不放它为 3600（同基线）。 */
    public long secondsUntilOpen(int zoneId, long nowEpochSec) {
        if (isOpen(zoneId, nowEpochSec)) {
            return 0;
        }
        for (RateLimitSettings.WaveStep step : steps) {
            long stepStart = startEpochSec + step.offsetSec();
            if (stepStart > nowEpochSec && allows(step, zoneId)) {
                return stepStart - nowEpochSec;
            }
        }
        return 3600;
    }

    private static boolean allows(RateLimitSettings.WaveStep step, int zoneId) {
        return step.allowZones().contains(-1L) || step.allowZones().contains((long) zoneId);
    }
}
