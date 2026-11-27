package com.game.discovery;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 服务发现存活集合理性闸门（决策清单 B1-1，对齐主仓 {@code go/shared/liveview/gate.go}）：
 * <ul>
 *   <li>基线非空而目录返回空活集 → 永远压住（不判死收尾）；</li>
 *   <li>基线节点数 ≥ 4 且本拍缺席超过一半（{@code missing * 2 > baseline}）→ 压住，直到缺席数连续 {@code holdDuration}
 *       保持不变才放行；缺席数变化则从头计时；</li>
 *   <li>复位策略由消费方指定：{@link ResetMode#WHEN_ZERO}（无去抖全量同步，缺席清零才复位，默认 {@code T=60s}）；
 *       {@link ResetMode#WHEN_BELOW_HALF}（成员级已有双拍去抖，缺席不再过半即复位，默认 {@code T=30m}）。</li>
 * </ul>
 * 非线程安全，仅由单控制面 / 清扫线程持有。
 */
public final class LiveViewSanityGate {

    public static final int MIN_BASELINE_FOR_MAJORITY_DROP = 4;
    public static final Duration DEFAULT_SYNC_HOLD = Duration.ofSeconds(60);
    public static final Duration DEFAULT_SWEEP_HOLD = Duration.ofMinutes(30);

    public enum ResetMode {
        /** 缺席清零后复位（scene-manager 全量同步）。 */
        WHEN_ZERO,
        /** 缺席不再过半即复位（带双拍去抖的死 gate 会话清扫）。 */
        WHEN_BELOW_HALF
    }

    public record Decision(boolean held, String reason, int baselineSize, int liveSize, int missingCount) {
        public static final Decision ALLOW = new Decision(false, "ok", 0, 0, 0);
    }

    private final long holdNanos;
    private final ResetMode resetMode;
    private boolean majorityHoldActive;
    private boolean majorityReleased;
    private int lastMissingCount = -1;
    private long stableSinceNanos;

    public LiveViewSanityGate(Duration holdDuration, ResetMode resetMode) {
        Objects.requireNonNull(holdDuration, "holdDuration");
        Objects.requireNonNull(resetMode, "resetMode");
        if (holdDuration.isNegative()) {
            throw new IllegalArgumentException("holdDuration 不能为负: " + holdDuration);
        }
        this.holdNanos = holdDuration.toNanos();
        this.resetMode = resetMode;
    }

    public static LiveViewSanityGate forSceneManagerSync() {
        return new LiveViewSanityGate(DEFAULT_SYNC_HOLD, ResetMode.WHEN_ZERO);
    }

    public static LiveViewSanityGate forDeadGateSweep() {
        return new LiveViewSanityGate(DEFAULT_SWEEP_HOLD, ResetMode.WHEN_BELOW_HALF);
    }

    public void reset() {
        majorityHoldActive = false;
        majorityReleased = false;
        lastMissingCount = -1;
        stableSinceNanos = 0L;
    }

    /** 评估当前一拍存活集是否可信；返回 {@code held=true} 时调用方不得执行判死收尾。 */
    public Decision evaluate(Set<?> baseline, Set<?> live, long nowNanos) {
        int baseSize = baseline == null ? 0 : baseline.size();
        int liveSize = live == null ? 0 : live.size();
        if (baseSize == 0) {
            reset();
            return new Decision(false, "empty_baseline", 0, liveSize, 0);
        }
        int missing = 0;
        for (Object item : baseline) {
            if (live == null || !live.contains(item)) {
                missing++;
            }
        }
        // 规则 1：基线非空而存活集为空 → 一直压住
        if (liveSize == 0) {
            majorityHoldActive = true;
            majorityReleased = false;
            lastMissingCount = -1;
            return new Decision(true, "empty_live_set", baseSize, 0, missing);
        }
        boolean majorityMissing = baseSize >= MIN_BASELINE_FOR_MAJORITY_DROP && (missing * 2 > baseSize);
        if (majorityMissing) {
            if (majorityReleased && missing == lastMissingCount) {
                return new Decision(false, "majority_drop_released", baseSize, liveSize, missing);
            }
            if (!majorityHoldActive || missing != lastMissingCount) {
                majorityHoldActive = true;
                majorityReleased = false;
                lastMissingCount = missing;
                stableSinceNanos = nowNanos;
                return new Decision(true, "majority_drop_holding", baseSize, liveSize, missing);
            }
            if (nowNanos - stableSinceNanos >= holdNanos) {
                majorityReleased = true;
                return new Decision(false, "majority_drop_released", baseSize, liveSize, missing);
            }
            return new Decision(true, "majority_drop_holding", baseSize, liveSize, missing);
        }
        // 规则 2：缺席不再过半时的复位口径
        if (majorityHoldActive) {
            if (resetMode == ResetMode.WHEN_BELOW_HALF || missing == 0) {
                reset();
            } else {
                // WHEN_ZERO 模式：放行前若未清零，继续保持防抖复位状态直到缺席清零；但不再过半时本拍允许放行。
                lastMissingCount = -1;
            }
        }
        return new Decision(false, "ok", baseSize, liveSize, missing);
    }
}
