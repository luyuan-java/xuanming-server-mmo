package com.game.discovery.world;

import com.game.api.proto.WorldChannel;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * 领导者一拍的计划快照（scene-channels-spec §4.6.1）：一段只读 Lua 一次取回 {@code GET ver}、{@code HGETALL ch / desired / cooldown} 与
 * Redis {@code TIME}，彼此一致。写入时以 {@link #version()} 作 CAS 的期望值（{@link WorldPlanBatch}）。
 *
 * <p>三张表都拷贝成按键<b>无符号升序</b>迭代的不可修改视图（构造时传什么 Map 都行），规划器据此得到确定的遍历顺序。
 *
 * @param version         计划版本号（不存在为 0）
 * @param nowMs           快照时刻的 Redis {@code TIME}（毫秒）；领导者的一切「时刻」（排空超时、冷却、created_ms）都以它为准
 * @param channels        scene_id → 频道记录
 * @param desired         conf → 期望频道数：只收能解析成 int 的值（含 ≤ 0；「≥ 1 才算合法、否则按配置播种」由规划器判，同基线
 *                        {@code DesiredWorldChannelCount}，world_autoscale.go:73-99）；解析不了的字段跳过并告警
 * @param cooldownUntilMs conf → 冷却到期毫秒（Redis TIME）；值解析不了的按 {@link Long#MAX_VALUE}（视为一直在冷却，fail-closed）
 */
public record WorldPlanSnapshot(long version, long nowMs, Map<Long, WorldChannel> channels,
                                Map<Integer, Integer> desired, Map<Integer, Long> cooldownUntilMs) {

    public WorldPlanSnapshot {
        channels = freeze(channels, new TreeMap<>(Long::compareUnsigned));
        desired = freeze(desired, new TreeMap<>(Integer::compareUnsigned));
        cooldownUntilMs = freeze(cooldownUntilMs, new TreeMap<>(Integer::compareUnsigned));
    }

    private static <K, V> Map<K, V> freeze(Map<K, V> source, TreeMap<K, V> target) {
        target.putAll(source);
        return Collections.unmodifiableSortedMap(target);
    }
}
