package com.game.scenemanager.world;

import com.game.discovery.world.WorldChannels;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 新频道放在哪个节点、用哪个 slot（scene-channels-spec §4.6.3，纯函数）。
 *
 * <ul>
 *   <li><b>hash</b>：基线 {@code assignNodeByHash(key, sortedNodes)}（mmorpg world_init.go:600-607）：32 位 FNV-1a，输入是 key 的十进制 ASCII，
 *       下标 = 哈希 mod 节点数。Java {@code int} 有符号，必须 {@link Integer#toUnsignedLong} 再取模（"2000" 的哈希 3526271127 ≥ 2³¹，
 *       有符号取模得负下标）。键统一为 {@code conf * 1000 + slot}（D5：基线铺设、补建内改派、懒改派、再平衡各用一种键，B3）；
 *       节点按<b>数值</b>升序（基线 {@code sort.Strings} 字典序，"10" 排在 "9" 前，没有意义，D5）。</li>
 *   <li><b>per-node</b>：该图 ACTIVE 数最少的活节点，并列取节点号小的（无符号）。</li>
 *   <li><b>slot</b>：该图 ACTIVE 记录未用的最小值，上限 {@link WorldChannels#MAX_SLOT}。</li>
 * </ul>
 */
public final class ChannelPlacement {

    private static final int FNV_OFFSET_BASIS = 0x811C9DC5;
    private static final int FNV_PRIME = 16777619;

    private ChannelPlacement() {
    }

    /** 32 位 FNV-1a（同 Go {@code hash/fnv.New32a}），按 ASCII 字节。 */
    public static int fnv1a32(String key) {
        int h = FNV_OFFSET_BASIS;
        for (byte b : key.getBytes(StandardCharsets.US_ASCII)) {
            h ^= b & 0xff;
            h *= FNV_PRIME;
        }
        return h;
    }

    /** hash 模式的落点键 {@code scene_config_id * 1000 + slot}（scene_config_id 按无符号）。 */
    public static long placementKey(int sceneConfigId, int slot) {
        return Integer.toUnsignedLong(sceneConfigId) * WorldChannels.SLOT_STRIDE + slot;
    }

    /**
     * 基线 assignNodeByHash：{@code FNV-1a32(十进制 key)} 无符号取模，选 {@code sortedNodes} 的那个下标。
     *
     * @param sortedNodes 非空，已按 {@link #sortNodes} 排好
     */
    public static int assignNodeByHash(long key, List<Integer> sortedNodes) {
        if (sortedNodes.isEmpty()) {
            throw new IllegalArgumentException("没有可放置的节点");
        }
        long hash = Integer.toUnsignedLong(fnv1a32(Long.toString(key)));
        return sortedNodes.get((int) (hash % sortedNodes.size()));
    }

    /** hash 模式下 (conf, slot) 的落点节点。 */
    public static int hashTarget(int sceneConfigId, int slot, List<Integer> sortedNodes) {
        return assignNodeByHash(placementKey(sceneConfigId, slot), sortedNodes);
    }

    /**
     * per-node 模式的落点：{@code activeByNode} 计数最少的节点（没有计数当 0），并列取 {@code sortedNodes} 里靠前的（即节点号小的）。
     *
     * @param sortedNodes 非空，已按 {@link #sortNodes} 排好
     */
    public static int leastLoadedNode(List<Integer> sortedNodes, Map<Integer, Integer> activeByNode) {
        if (sortedNodes.isEmpty()) {
            throw new IllegalArgumentException("没有可放置的节点");
        }
        int best = sortedNodes.get(0);
        int bestCount = activeByNode.getOrDefault(best, 0);
        for (int i = 1; i < sortedNodes.size(); i++) {
            int node = sortedNodes.get(i);
            int count = activeByNode.getOrDefault(node, 0);
            if (count < bestCount) {
                best = node;
                bestCount = count;
            }
        }
        return best;
    }

    /** 未用的最小 slot（0..{@link WorldChannels#MAX_SLOT}）；全被占用为空（ERROR 停止该图的新建）。 */
    public static OptionalInt minUnusedSlot(Set<Integer> usedSlots) {
        for (int slot = 0; slot <= WorldChannels.MAX_SLOT; slot++) {
            if (!usedSlots.contains(slot)) {
                return OptionalInt.of(slot);
            }
        }
        return OptionalInt.empty();
    }

    /** 节点号按<b>数值</b>无符号升序（基线是字典序，D5）。 */
    public static List<Integer> sortNodes(Collection<Integer> nodes) {
        List<Integer> sorted = new ArrayList<>(nodes);
        sorted.sort(Integer::compareUnsigned);
        return List.copyOf(sorted);
    }
}
