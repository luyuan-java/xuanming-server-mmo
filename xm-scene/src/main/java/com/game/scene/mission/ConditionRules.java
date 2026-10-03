package com.game.scene.mission;

import com.game.scene.mission.MissionTables.ConditionDef;
import java.util.List;

/**
 * 条件判定（基线 condition_util + MissionSystem::UpdateProgressIfConditionMatches），纯函数。
 * 表里的数值都是 uint32：进度、目标、阈值一律按无符号比较，Java 里用 long 承载。
 */
final class ConditionRules {

    private ConditionRules() {
    }

    /** 生效目标：任务行的覆盖值（&gt; 0）优先，否则用条件的 target_count。 */
    static long effectiveTarget(ConditionDef condition, int targetOverride) {
        return targetOverride != 0 ? Integer.toUnsignedLong(targetOverride) : Integer.toUnsignedLong(condition.targetCount());
    }

    /** 条件是否达成（基线 IsFulfilled）：比较符 0 &gt;=、1 &gt;、2 &lt;=、3 &lt;、4 ==，其余一律不达成。 */
    static boolean isFulfilled(ConditionDef condition, long progress, int targetOverride) {
        if (condition == null) {
            return false;
        }
        long target = effectiveTarget(condition, targetOverride);
        return switch (condition.comparisonOp()) {
            case 0 -> progress >= target;
            case 1 -> progress > target;
            case 2 -> progress <= target;
            case 3 -> progress < target;
            case 4 -> progress == target;
            default -> false;
        };
    }

    /**
     * 事件参数是否命中条件（基线 MatchesEventSlots）：第 K 个非空条件列表要包含事件的第 K 个参数（列表内任一），
     * 全部非空列表都要命中；全空的条件命中任何事件。
     */
    static boolean matchesEventSlots(ConditionDef condition, List<Integer> eventIds) {
        for (int k = 1; k <= 4; k++) {
            List<Integer> slot = condition.slot(k);
            if (slot.isEmpty()) {
                continue;
            }
            if (eventIds.size() < k || !slot.contains(eventIds.get(k - 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 一个事实推进一个条件格后的新进度（基线 UpdateProgressIfConditionMatches）；不推进返回 -1。
     *
     * <ul>
     *   <li>已达成、类别不符、参数不命中 → 不推进。等级类别（6）特殊：condition1 非空时量要达到其中任一阈值，condition2–4 非空一律不命中。</li>
     *   <li>持有型（quantity_type == 1 或等级类别）直接覆盖成事实的量（可以变小，不按目标封顶）；累计型累加并封顶到目标
     *       （比较符为 &gt; 时封顶到目标 + 1）。两者都不超过 uint32 上限。</li>
     *   <li>值没变也算不推进。</li>
     * </ul>
     */
    static long advance(ConditionDef condition, long current, int targetOverride, MissionFact fact) {
        if (isFulfilled(condition, current, targetOverride) || fact.category() != condition.category()) {
            return -1;
        }
        if (condition.category() == MissionTables.CATEGORY_LEVEL) {
            if (!condition.condition1().isEmpty() && !reachesAnyThreshold(condition.condition1(), fact.amount())) {
                return -1;
            }
            if (!condition.condition2().isEmpty() || !condition.condition3().isEmpty() || !condition.condition4().isEmpty()) {
                return -1;
            }
        } else if (!matchesEventSlots(condition, fact.ids())) {
            return -1;
        }
        long target = effectiveTarget(condition, targetOverride);
        boolean owned = condition.quantityType() == 1 || condition.category() == MissionTables.CATEGORY_LEVEL;
        long next;
        if (owned) {
            next = Math.min(fact.amount(), MissionTables.MAX_U32);
        } else {
            long cap = condition.comparisonOp() == 1 ? target + 1 : target;
            next = Math.min(Math.min(current + fact.amount(), cap), MissionTables.MAX_U32);
        }
        return next == current ? -1 : next;
    }

    private static boolean reachesAnyThreshold(List<Integer> thresholds, long amount) {
        for (int threshold : thresholds) {
            if (amount >= Integer.toUnsignedLong(threshold)) {
                return true;
            }
        }
        return false;
    }
}
