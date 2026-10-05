package com.game.scenemanager.world;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 缺席表（scene-channels-spec §4.6.1）：计划引用了、但目录里看不到的节点 → 首次缺席时刻（单调时钟）。
 * 只在领导者内存里；领导者换人（或本副本重新当选）从零计时——偏晚，安全（D12）。
 *
 * <p>非线程安全：只在控制面 tick 线程上用。
 */
final class AbsenceTracker {

    private final Map<Integer, Long> firstAbsentNanos = new HashMap<>();

    /**
     * 按本拍的观察更新，回「缺席节点 → 已缺席多久（纳秒）」。不再被引用、或重新出现的节点被忘掉。
     *
     * @param referenced 计划记录引用到的节点号
     * @param present    目录里在场的节点号
     */
    Map<Integer, Long> update(Set<Integer> referenced, Set<Integer> present, long nowNanos) {
        firstAbsentNanos.keySet().removeIf(node -> !referenced.contains(node) || present.contains(node));
        Map<Integer, Long> out = new TreeMap<>(Integer::compareUnsigned);
        for (int node : referenced) {
            if (present.contains(node)) {
                continue;
            }
            long since = firstAbsentNanos.computeIfAbsent(node, n -> nowNanos);
            out.put(node, Math.max(0, nowNanos - since));
        }
        return out;
    }

    void clear() {
        firstAbsentNanos.clear();
    }
}
