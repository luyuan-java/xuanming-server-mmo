package com.game.scene.world;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * AOI 服务器压力裁表监控（L625，对齐 mmorpg {@code AoiLoadMonitor}）：
 * <ul>
 *   <li>按模拟步耗时 / 步长的指数滑动平均（EMA）计算负载比；</li>
 *   <li>0.5 以下为 0 压、0.9 及以上为满压 1.0，量化为 0..10 共 11 档（带防抖、每秒最多变档一次）；</li>
 *   <li>暴露低基数 Micrometer 读数：{@code scene_aoi_load_ema_ratio}、{@code scene_aoi_load_pressure_level}、
 *       {@code scene_aoi_load_effective_capacity}。</li>
 * </ul>
 */
public final class AoiLoadMonitor {

    static final double DEFAULT_ALPHA = 0.25;
    static final double LOW_RATIO = 0.5;
    static final double HIGH_RATIO = 0.9;
    static final int MAX_PRESSURE_LEVEL = 10;
    static final long MIN_UPDATE_INTERVAL_MS = 1_000L;
    static final int DEBOUNCE_SAMPLES = 2;

    private final int baseCapacity;
    private final double alpha;

    private boolean initialized;
    private double emaRatio;
    private int pressureLevel;
    private int effectiveCapacity;
    private int pendingLevel;
    private int pendingStreak;
    private long lastLevelChangeMs = Long.MIN_VALUE;

    public AoiLoadMonitor(int baseCapacity, MeterRegistry registry) {
        this(baseCapacity, DEFAULT_ALPHA, registry);
    }

    AoiLoadMonitor(int baseCapacity, double alpha, MeterRegistry registry) {
        if (baseCapacity < 1) {
            throw new IllegalArgumentException("baseCapacity 必须为正数: " + baseCapacity);
        }
        if (alpha <= 0.0 || alpha > 1.0) {
            throw new IllegalArgumentException("EMA alpha 必须在 (0, 1] 内: " + alpha);
        }
        this.baseCapacity = baseCapacity;
        this.alpha = alpha;
        this.effectiveCapacity = baseCapacity;
        if (registry != null) {
            Gauge.builder("scene_aoi_load_ema_ratio", this, AoiLoadMonitor::emaRatio)
                    .description("Scene simulation step duration / interval EMA ratio")
                    .register(registry);
            Gauge.builder("scene_aoi_load_pressure_level", this, AoiLoadMonitor::pressureLevel)
                    .description("AOI load pressure level (0..10)")
                    .register(registry);
            Gauge.builder("scene_aoi_load_effective_capacity", this, AoiLoadMonitor::effectiveCapacity)
                    .description("Current effective AOI interest list capacity")
                    .register(registry);
        }
    }

    /**
     * 记录一次模拟步耗时。若压力档位（与有效容量）发生变化则返回 {@code true}，调用方应同步到 {@link ViewIndex#setEffectiveCapacity}。
     */
    public boolean recordStep(double stepDurationMs, double stepIntervalMs, long nowMs) {
        if (stepIntervalMs <= 0.0) {
            return false;
        }
        double ratio = Math.max(0.0, stepDurationMs / stepIntervalMs);
        if (!initialized) {
            emaRatio = ratio;
            initialized = true;
        } else {
            emaRatio = alpha * ratio + (1.0 - alpha) * emaRatio;
        }
        int targetLevel = quantizeLevel(emaRatio);
        if (targetLevel == pressureLevel) {
            pendingLevel = targetLevel;
            pendingStreak = 0;
            return false;
        }
        if (targetLevel == pendingLevel) {
            pendingStreak++;
        } else {
            pendingLevel = targetLevel;
            pendingStreak = 1;
        }
        if (pendingStreak < DEBOUNCE_SAMPLES) {
            return false;
        }
        if (lastLevelChangeMs != Long.MIN_VALUE && nowMs - lastLevelChangeMs < MIN_UPDATE_INTERVAL_MS) {
            return false;
        }
        pressureLevel = targetLevel;
        pendingStreak = 0;
        lastLevelChangeMs = nowMs;
        effectiveCapacity = computeEffectiveCapacity(baseCapacity, pressureLevel);
        return true;
    }

    static int quantizeLevel(double emaRatio) {
        if (emaRatio <= LOW_RATIO) {
            return 0;
        }
        if (emaRatio >= HIGH_RATIO) {
            return MAX_PRESSURE_LEVEL;
        }
        double norm = (emaRatio - LOW_RATIO) / (HIGH_RATIO - LOW_RATIO);
        return Math.min(MAX_PRESSURE_LEVEL, Math.max(0, (int) Math.round(norm * MAX_PRESSURE_LEVEL)));
    }

    /** 0 档满容；10 档收缩到 max(1, baseCapacity / 5)，中间线性插值。 */
    static int computeEffectiveCapacity(int baseCapacity, int level) {
        int minCap = Math.max(1, baseCapacity / 5);
        if (level <= 0) {
            return baseCapacity;
        }
        if (level >= MAX_PRESSURE_LEVEL) {
            return minCap;
        }
        int span = baseCapacity - minCap;
        return Math.max(minCap, baseCapacity - (int) Math.round(span * (level / (double) MAX_PRESSURE_LEVEL)));
    }

    public double emaRatio() {
        return emaRatio;
    }

    public int pressureLevel() {
        return pressureLevel;
    }

    public int effectiveCapacity() {
        return effectiveCapacity;
    }
}
