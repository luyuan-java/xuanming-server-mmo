package com.game.common.combatai;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntConsumer;

/**
 * 位图状态互斥与打断矩阵（纯 JDK 21，零第三方依赖，与 C++ {@code combat_ai::StateMutexMatrix} / Go {@code combatai} 对齐）。
 *
 * <ul>
 *   <li>用 64 位 {@code long} 位图取代集合遍历与打断前拷贝，热路径校验为 O(1) 位运算且零堆分配。</li>
 *   <li>规范化规则（{@link StateRule}）显式携带 {@code (action, state, mode, tip)}，新增状态只需加行，不依赖定长槽位。</li>
 *   <li>显式携带 {@code allowed} 与调用方传入的 {@code successTip}（兼容全仓 {@code kSuccess = 1000} 与 {@code 0} 口径）。</li>
 *   <li>两阶段执行：先纯只读校验（含入态边界），全部通过后再依次打断并触发退出钩子，最后入态，拒绝路径零副作用。</li>
 * </ul>
 */
public final class StateMutexMatrix {

    public static final int MAX_MUTEX_STATES = 64;
    /** 无入态占位值（按 uint32 无符号解释为 0xFFFFFFFF）。 */
    public static final int NO_MUTEX_STATE = -1;

    /** 统一互斥模式枚举（0 = 互斥，1 = 允许，2 = 打断；行为表与战斗状态表统一口径）。 */
    public enum StateMode {
        BLOCK(0),
        ALLOW(1),
        INTERRUPT(2);

        private final int code;

        StateMode(int code) {
            this.code = code;
        }

        public int code() {
            return code;
        }

        public static StateMode fromCode(int code) {
            return switch (code) {
                case 0 -> BLOCK;
                case 1 -> ALLOW;
                case 2 -> INTERRUPT;
                default -> null;
            };
        }
    }

    /** 状态退出原因。 */
    public enum StateExitReason {
        /** 显式移除状态。 */
        REMOVED,
        /** 被另一动作按互斥表打断。 */
        INTERRUPTED
    }

    /** 规范化单条互斥规则：显式携带 action 与 state 编号，消除定长槽位下标错位。 */
    public record StateRule(int action, int state, StateMode mode, int tip) {
    }

    /** 兼容旧定长槽位行（第 i 槽隐式对应 state = i）。 */
    public record LegacySlotEntry(int mode, int tip) {
    }

    /** 纯只读评估结果。 */
    public record EvaluateResult(boolean allowed, int tip, long interruptMask) {
    }

    /**
     * 支持多来源叠加（如多个 buff 同时施加沉默 / 眩晕）的引用计数位图。
     * 计数 0 → 1 置位，1 → 0 清位；按 uint16 上限（65535）饱和截断，防溢出回绕导致状态误消失。
     */
    public static final class RefCountedStateBitset {
        private static final int MAX_REF_COUNT = 0xFFFF;
        private long activeMask;
        private final int[] refCounts = new int[MAX_MUTEX_STATES];

        public boolean acquire(int state) {
            if (Integer.compareUnsigned(state, MAX_MUTEX_STATES) >= 0) {
                return false;
            }
            boolean becameActive = refCounts[state] == 0;
            if (refCounts[state] < MAX_REF_COUNT) {
                refCounts[state]++;
            }
            if (becameActive) {
                activeMask |= (1L << state);
            }
            return becameActive;
        }

        public boolean release(int state) {
            if (Integer.compareUnsigned(state, MAX_MUTEX_STATES) >= 0 || refCounts[state] == 0) {
                return false;
            }
            refCounts[state]--;
            if (refCounts[state] == 0) {
                activeMask &= ~(1L << state);
                return true;
            }
            return false;
        }

        /** 强制清零某个状态（例如被驱散或被互斥动作打断），返回原先是否处于激活态。 */
        public boolean clearState(int state) {
            if (Integer.compareUnsigned(state, MAX_MUTEX_STATES) >= 0) {
                return false;
            }
            boolean wasActive = refCounts[state] != 0;
            refCounts[state] = 0;
            activeMask &= ~(1L << state);
            return wasActive;
        }

        public void reset() {
            activeMask = 0L;
            java.util.Arrays.fill(refCounts, 0);
        }

        public boolean has(int state) {
            return Integer.compareUnsigned(state, MAX_MUTEX_STATES) < 0 && (activeMask & (1L << state)) != 0L;
        }

        public int refCount(int state) {
            return Integer.compareUnsigned(state, MAX_MUTEX_STATES) < 0 ? refCounts[state] : 0;
        }

        public long mask() {
            return activeMask;
        }
    }

    /** 状态退出收尾回调。 */
    @FunctionalInterface
    public interface ExitHook<E> {
        void onExit(E entity, StateExitReason reason);
    }

    /** 状态退出钩子注册表：按状态编号 O(1) 派发子系统收尾（脱跟随 / 下马 / 停施法）。 */
    public static final class StateExitHookRegistry<E> {
        @SuppressWarnings("unchecked")
        private final ExitHook<E>[] hooks = (ExitHook<E>[]) new ExitHook<?>[MAX_MUTEX_STATES];

        public void register(int state, ExitHook<E> hook) {
            if (Integer.compareUnsigned(state, MAX_MUTEX_STATES) >= 0) {
                return;
            }
            hooks[state] = hook;
        }

        public void unregister(int state) {
            if (Integer.compareUnsigned(state, MAX_MUTEX_STATES) >= 0) {
                return;
            }
            hooks[state] = null;
        }

        public void clear() {
            java.util.Arrays.fill(hooks, null);
        }

        public void notifyExited(E entity, int state, StateExitReason reason) {
            if (Integer.compareUnsigned(state, MAX_MUTEX_STATES) >= 0) {
                return;
            }
            ExitHook<E> hook = hooks[state];
            if (hook != null) {
                hook.onExit(entity, reason);
            }
        }
    }

    private static final class ActionPolicy {
        long blockMask;
        long interruptMask;
        final int[] blockTips = new int[MAX_MUTEX_STATES];
    }

    private final Map<Integer, ActionPolicy> policies = new HashMap<>();

    public void clear() {
        policies.clear();
    }

    /** 显式注册某动作（即使全部允许也能通过 {@link #hasAction(int)} 校验）。 */
    public void ensureAction(int action) {
        policies.computeIfAbsent(action, ignored -> new ActionPolicy());
    }

    /** 按显式 {@code (action, state)} 写入单条规则，不依赖槽位位置。 */
    public boolean setRule(int action, int state, StateMode mode, int tip) {
        if (Integer.compareUnsigned(state, MAX_MUTEX_STATES) >= 0 || mode == null) {
            return false;
        }
        ActionPolicy policy = policies.computeIfAbsent(action, ignored -> new ActionPolicy());
        long bit = 1L << state;
        policy.blockMask &= ~bit;
        policy.interruptMask &= ~bit;
        policy.blockTips[state] = 0;

        switch (mode) {
            case BLOCK -> {
                policy.blockMask |= bit;
                policy.blockTips[state] = tip;
            }
            case ALLOW -> {
                // 允许态无需置位
            }
            case INTERRUPT -> policy.interruptMask |= bit;
        }
        return true;
    }

    /** 批量装载规范化规则列表；返回非法规则条数（0 表示全部合法）。 */
    public int loadRules(Iterable<StateRule> rules) {
        Objects.requireNonNull(rules, "rules");
        int invalidCount = 0;
        for (StateRule rule : rules) {
            if (rule == null || !setRule(rule.action(), rule.state(), rule.mode(), rule.tip())) {
                invalidCount++;
            }
        }
        return invalidCount;
    }

    /** 兼容旧定长槽位行：第 i 槽对应 state = i。返回越界或非法 mode 的槽位数。 */
    public int loadLegacyActionRow(int action, List<LegacySlotEntry> slots) {
        Objects.requireNonNull(slots, "slots");
        ensureAction(action);
        int invalidCount = 0;
        for (int i = 0; i < slots.size(); i++) {
            LegacySlotEntry slot = slots.get(i);
            StateMode mode = slot == null ? null : StateMode.fromCode(slot.mode());
            if (i >= MAX_MUTEX_STATES || mode == null) {
                invalidCount++;
                continue;
            }
            setRule(action, i, mode, slot.tip());
        }
        return invalidCount;
    }

    public boolean hasAction(int action) {
        return policies.containsKey(action);
    }

    public int actionCount() {
        return policies.size();
    }

    /**
     * 纯只读判定：O(1) 位运算，零堆分配，零副作用。
     * 存在多个互斥状态时，按最小状态编号（最低位）稳定返回对应 tip。
     */
    public EvaluateResult evaluate(long activeMask, int action, int successTip, int unknownActionTip) {
        ActionPolicy policy = policies.get(action);
        if (policy == null) {
            return new EvaluateResult(false, unknownActionTip, 0L);
        }
        long blocked = activeMask & policy.blockMask;
        if (blocked != 0L) {
            int firstBlocked = Long.numberOfTrailingZeros(blocked);
            return new EvaluateResult(false, policy.blockTips[firstBlocked], 0L);
        }
        return new EvaluateResult(true, successTip, activeMask & policy.interruptMask);
    }

    public int canPerform(long activeMask, int action, int successTip, int unknownActionTip) {
        return evaluate(activeMask, action, successTip, unknownActionTip).tip();
    }

    /** 按升序遍历位掩码里的每一个置位状态编号（纯整数循环，零分配）。 */
    public static void forEachBit(long mask, IntConsumer consumer) {
        Objects.requireNonNull(consumer, "consumer");
        long m = mask;
        while (m != 0L) {
            int state = Long.numberOfTrailingZeros(m);
            m &= (m - 1L);
            consumer.accept(state);
        }
    }

    /**
     * 两阶段原语：先纯校验（含入态边界），全部通过后才依次执行打断回调与入态回调。
     */
    public int tryPerform(long activeMask, int action, int enterState, int maxValidState,
                          int successTip, int unknownActionTip, int invalidStateTip,
                          IntConsumer onInterrupt, IntConsumer onEnter) {
        if (enterState != NO_MUTEX_STATE
                && (Integer.compareUnsigned(enterState, maxValidState) >= 0
                || Integer.compareUnsigned(enterState, MAX_MUTEX_STATES) >= 0)) {
            return invalidStateTip;
        }
        EvaluateResult eval = evaluate(activeMask, action, successTip, unknownActionTip);
        if (!eval.allowed()) {
            return eval.tip();
        }
        if (onInterrupt != null) {
            forEachBit(eval.interruptMask(), onInterrupt);
        }
        if (enterState != NO_MUTEX_STATE && onEnter != null) {
            onEnter.accept(enterState);
        }
        return successTip;
    }
}
