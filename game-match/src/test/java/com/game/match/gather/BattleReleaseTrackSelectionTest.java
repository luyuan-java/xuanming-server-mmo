package com.game.match.gather;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.BattleNodeInfo;
import com.game.common.release.ReleaseTrack;
import com.game.match.metrics.MatchMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class BattleReleaseTrackSelectionTest {

    @Test
    void allCanaryBattlePrefersCanaryAndFallsBackToStableWhileStableNeverEntersCanary() {
        BattleNodeInfo stable1 = node(1, "s1", "stable");
        BattleNodeInfo canary1 = node(2, "c1", "canary");
        BattleNodeInfo bad = node(3, "bad", "invalid-track");

        var metrics = new MatchMetrics(new SimpleMeterRegistry(), new com.game.match.metrics.MetricLabels(id -> true));
        var mixedNodes = new RedisBattleNodes(dir(List.of(stable1, canary1, bad)), metrics, ignored -> 0);

        // 1. 全员 canary 局 -> 选中 canary battle 节点
        ReleaseTrack allCanary = ReleaseTrack.battleTrackForMembers(List.of(ReleaseTrack.CANARY, ReleaseTrack.CANARY));
        assertThat(mixedNodes.pickRandom(Set.of(), allCanary)).contains(canary1);

        // 2. 混编 / stable 局 -> 仅选 stable，绝不进 canary
        ReleaseTrack mixedTeam = ReleaseTrack.battleTrackForMembers(List.of(ReleaseTrack.CANARY, ReleaseTrack.STABLE));
        assertThat(mixedNodes.pickRandom(Set.of(), mixedTeam)).contains(stable1);
        assertThat(mixedNodes.pickRandom(Set.of(), ReleaseTrack.STABLE)).contains(stable1);

        // 3. 无可用 canary 节点时，canary 局安全退回 stable
        var stableOnly = new RedisBattleNodes(dir(List.of(stable1, bad)), metrics, ignored -> 0);
        assertThat(stableOnly.pickRandom(Set.of(), allCanary)).contains(stable1);
    }

    private static BattleNodeInfo node(int id, String instance, String track) {
        return BattleNodeInfo.newBuilder()
                .setNodeId(id)
                .setInstanceId(instance)
                .setRpcHost("127.0.0.1")
                .setRpcPort(21000 + id)
                .setAccepting(true)
                .setReleaseTrack(track)
                .build();
    }

    private static RedisBattleNodes.Directory dir(List<BattleNodeInfo> entries) {
        return new RedisBattleNodes.Directory() {
            @Override public List<BattleNodeInfo> list() { return entries; }
            @Override public CompletableFuture<Optional<BattleNodeInfo>> find(int nodeId) {
                return CompletableFuture.completedFuture(entries.stream().filter(e -> e.getNodeId() == nodeId).findFirst());
            }
        };
    }
}
