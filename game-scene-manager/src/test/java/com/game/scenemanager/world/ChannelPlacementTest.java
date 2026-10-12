package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** 放置策略（scene-channels-spec §1.5.5 黄金值、§4.6.3、§9.2 ChannelPlacementTest；基线 TestAssignNodeByHash_Deterministic）。 */
class ChannelPlacementTest {

    /** §1.5.5 的黄金值（与 Go hash/fnv.New32a 逐字节一致；含 ≥ 2³¹ 的 "2000"、"16000"）。 */
    @ParameterizedTest
    @CsvSource({
            "1000, 580373188, 0, 1, 0",
            "1001, 597150807, 1, 0, 3",
            "1002, 613928426, 0, 2, 2",
            "1003, 630706045, 1, 1, 1",
            "1015, 597297902, 0, 2, 2",
            "2000, 3526271127, 1, 0, 3",
            "16000, 3950372026, 0, 1, 2",
            "16015, 1417201820, 0, 2, 0"})
    void FNV1a黄金值与无符号取模(long key, long hash, int mod2, int mod3, int mod4) {
        assertThat(Integer.toUnsignedLong(ChannelPlacement.fnv1a32(Long.toString(key)))).isEqualTo(hash);
        assertThat(ChannelPlacement.assignNodeByHash(key, List.of(0, 1))).isEqualTo(mod2);
        assertThat(ChannelPlacement.assignNodeByHash(key, List.of(0, 1, 2))).isEqualTo(mod3);
        assertThat(ChannelPlacement.assignNodeByHash(key, List.of(0, 1, 2, 3))).isEqualTo(mod4);
    }

    @Test
    void 落点键是conf乘1000加slot_同一输入同一结果() {
        assertThat(ChannelPlacement.placementKey(16, 15)).isEqualTo(16015);
        assertThat(ChannelPlacement.placementKey(-1, 998)).isEqualTo(4_294_967_295L * 1000 + 998); // conf 按无符号
        List<Integer> nodes = List.of(10, 20, 30);
        assertThat(ChannelPlacement.hashTarget(2, 0, nodes)).isEqualTo(ChannelPlacement.hashTarget(2, 0, nodes))
                .isEqualTo(nodes.get(0)); // "2000" mod 3 = 0
        assertThatThrownBy(() -> ChannelPlacement.assignNodeByHash(1, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 节点按数值无符号升序_不是字典序() {
        assertThat(ChannelPlacement.sortNodes(List.of(10, 9, 100, 2))).containsExactly(2, 9, 10, 100);
        assertThat(ChannelPlacement.sortNodes(List.of(-1, 1))).containsExactly(1, -1); // 0xFFFFFFFF 最大
    }

    @Test
    void per_node放在ACTIVE最少的节点_并列取节点号小的() {
        List<Integer> nodes = ChannelPlacement.sortNodes(List.of(9, 10, 3));
        assertThat(ChannelPlacement.leastLoadedNode(nodes, Map.of())).isEqualTo(3);
        assertThat(ChannelPlacement.leastLoadedNode(nodes, Map.of(3, 1))).isEqualTo(9);
        assertThat(ChannelPlacement.leastLoadedNode(nodes, Map.of(3, 1, 9, 1))).isEqualTo(10);
        assertThat(ChannelPlacement.leastLoadedNode(nodes, Map.of(3, 2, 9, 1, 10, 1))).isEqualTo(9);
    }

    @Test
    void slot取未用的最小值_上限998() {
        assertThat(ChannelPlacement.minUnusedSlot(Set.of())).hasValue(0);
        assertThat(ChannelPlacement.minUnusedSlot(Set.of(0, 2))).hasValue(1);
        Set<Integer> full = new HashSet<>();
        for (int i = 0; i <= 997; i++) {
            full.add(i);
        }
        assertThat(ChannelPlacement.minUnusedSlot(full)).hasValue(998);
        full.add(998);
        assertThat(ChannelPlacement.minUnusedSlot(full)).isEmpty();
    }
}
