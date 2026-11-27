package com.game.common.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DeterministicApertureSubsetTest {

    @Test
    void balancesEveryTargetExactlyAcrossAllPeersAndNeverOmitsTargets() {
        // 遍历多种 (peers, targets, subsetSize) 组合，按格精确验证每台目标分得的份额完全一致且大于 0
        int[][] cases = {
                {6, 12, 4},
                {5, 13, 3},
                {3, 10, 2},
                {2, 9, 2},   // peers * size < targets：必须自动扩大弧覆盖全部目标
                {10, 8, 4},
                {7, 17, 4}
        };
        for (int[] c : cases) {
            int peers = c[0];
            int targets = c[1];
            int size = c[2];
            long[] hits = new long[targets];
            Set<Integer> covered = new HashSet<>();
            for (int r = 0; r < peers; r++) {
                DeterministicApertureSubset.Aperture ap = DeterministicApertureSubset.Aperture.of(r, peers, targets, size);
                for (int offset = 0; offset < ap.width(); offset++) {
                    final int pickOffset = offset;
                    int idx = ap.pick(ignored -> pickOffset);
                    hits[idx]++;
                }
                covered.addAll(ap.targetIndices());
            }
            assertThat(covered).hasSize(targets);
            long expected = hits[0];
            assertThat(expected).isGreaterThan(0);
            for (int t = 1; t < targets; t++) {
                assertThat(hits[t]).as("peers=%d targets=%d size=%d target=%d", peers, targets, size, t)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    void narrowsSubsetAndExpandsWhenTargetsExceedPeerCapacity() {
        List<String> gates = List.of("gate-a", "gate-b", "gate-c", "gate-d", "gate-e", "gate-f");
        List<String> providers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            providers.add(String.format("svc-%02d", i));
        }

        Set<String> union = new HashSet<>();
        for (String gate : gates) {
            List<String> subset = DeterministicApertureSubset.selectSubset(gate, gates, providers, s -> s, 4);
            assertThat(subset.size()).isBetween(4, 5);
            assertThat(subset.size()).isLessThan(providers.size());
            union.addAll(subset);
        }
        assertThat(union).containsExactlyInAnyOrderElementsOf(providers);

        // 2 台 gate 连 9 台提供者，k=2 时每台自动覆盖多于 2 台，确保全服无遗漏
        List<String> twoGates = List.of("g1", "g2");
        List<String> nineTargets = providers.subList(0, 9);
        Set<String> covered = new HashSet<>();
        for (String g : twoGates) {
            List<String> sub = DeterministicApertureSubset.selectSubset(g, twoGates, nineTargets, s -> s, 2);
            assertThat(sub.size()).isGreaterThanOrEqualTo(4);
            covered.addAll(sub);
        }
        assertThat(covered).containsExactlyInAnyOrderElementsOf(nineTargets);
    }

    @Test
    void validatesSubsetSizeAndHandlesUnknownSelfInPeers() {
        assertThat(DeterministicApertureSubset.validateSubsetSize(0)).isZero();
        assertThat(DeterministicApertureSubset.validateSubsetSize(2)).isEqualTo(2);
        assertThatThrownBy(() -> DeterministicApertureSubset.validateSubsetSize(1))
                .isInstanceOf(IllegalArgumentException.class);

        // 目录还没见到自己时，peerRank 自动把自己算进去
        var rank = DeterministicApertureSubset.peerRank(List.of("gate-b", "gate-c"), "gate-a");
        assertThat(rank.rank()).isZero();
        assertThat(rank.peers()).isEqualTo(3);
    }
}
