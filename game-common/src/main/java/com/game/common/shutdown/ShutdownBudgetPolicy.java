package com.game.common.shutdown;

import java.time.Duration;
import java.util.Objects;

/**
 * 全服务优雅停机预算与战斗服计划排空宽限期校验（对齐无感重启收尾批规则）：
 * <ul>
 *   <li>进程强退时限 {@code forceExitTimeout >= deregisterWait + maxRpcTimeout + backgroundDrain + margin}；</li>
 *   <li>Pod 宽限期 {@code podGracePeriod >= preStopWait + forceExitTimeout + margin}；</li>
 *   <li>战斗服 Pod 宽限期由同一组参数推导：{@code matchDeadline + normalShutdown + margin}。</li>
 * </ul>
 */
public final class ShutdownBudgetPolicy {

    private ShutdownBudgetPolicy() {
    }

    public record ServiceBudget(Duration deregisterWait,
                                Duration maxRpcTimeout,
                                Duration backgroundDrain,
                                Duration margin,
                                Duration forceExitTimeout,
                                Duration preStopWait,
                                Duration podGracePeriod) {
        public ServiceBudget {
            requireNonNegative(deregisterWait, "deregisterWait");
            requireNonNegative(maxRpcTimeout, "maxRpcTimeout");
            requireNonNegative(backgroundDrain, "backgroundDrain");
            requireNonNegative(margin, "margin");
            requireNonNegative(forceExitTimeout, "forceExitTimeout");
            requireNonNegative(preStopWait, "preStopWait");
            requireNonNegative(podGracePeriod, "podGracePeriod");
        }

        public Duration minimumForceExitTimeout() {
            return deregisterWait.plus(maxRpcTimeout).plus(backgroundDrain).plus(margin);
        }

        public Duration minimumPodGracePeriod() {
            return preStopWait.plus(forceExitTimeout).plus(margin);
        }

        public boolean isValid() {
            return forceExitTimeout.compareTo(minimumForceExitTimeout()) >= 0
                    && podGracePeriod.compareTo(minimumPodGracePeriod()) >= 0;
        }

        public void validateOrThrow(String serviceName) {
            Duration minForce = minimumForceExitTimeout();
            if (forceExitTimeout.compareTo(minForce) < 0) {
                throw new IllegalArgumentException(serviceName + " forceExitTimeout=" + forceExitTimeout
                        + " 小于下界(deregisterWait+maxRpcTimeout+backgroundDrain+margin)=" + minForce);
            }
            Duration minPod = minimumPodGracePeriod();
            if (podGracePeriod.compareTo(minPod) < 0) {
                throw new IllegalArgumentException(serviceName + " podGracePeriod=" + podGracePeriod
                        + " 小于下界(preStopWait+forceExitTimeout+margin)=" + minPod);
            }
        }
    }

    /** 战斗服计划排空预算：排空预算与 Pod 宽限期出自同一组参数。 */
    public record BattleDrainBudget(Duration matchDeadline, Duration normalShutdown, Duration margin) {
        public BattleDrainBudget {
            requireNonNegative(matchDeadline, "matchDeadline");
            requireNonNegative(normalShutdown, "normalShutdown");
            requireNonNegative(margin, "margin");
        }

        /** 战斗服计划排空阶段允许等待在途房间的上限（等于单局成局期限）。 */
        public Duration roomDrainBudget() {
            return matchDeadline;
        }

        /** Pod terminationGracePeriodSeconds = 一局成局期限 + 普通停机所需 + 余量。 */
        public Duration podGracePeriod() {
            return matchDeadline.plus(normalShutdown).plus(margin);
        }
    }

    private static void requireNonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " 不能为负: " + value);
        }
    }
}
