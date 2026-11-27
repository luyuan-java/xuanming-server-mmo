package com.game.common.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReleaseTrackTest {

    private record Node(String id, String track) {
    }

    @Test
    void parseDefaultsToStableAndRejectsUnknownFailFast() {
        assertThat(ReleaseTrack.parse(null)).isEqualTo(ReleaseTrack.STABLE);
        assertThat(ReleaseTrack.parse("")).isEqualTo(ReleaseTrack.STABLE);
        assertThat(ReleaseTrack.parse("stable")).isEqualTo(ReleaseTrack.STABLE);
        assertThat(ReleaseTrack.parse("CANARY")).isEqualTo(ReleaseTrack.CANARY);
        assertThat(ReleaseTrack.STABLE.runsGlobalBackgroundWork()).isTrue();
        assertThat(ReleaseTrack.CANARY.runsGlobalBackgroundWork()).isFalse();

        assertThatThrownBy(() -> ReleaseTrack.parse("beta"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ReleaseTrack.ENV_NAME);
    }

    @Test
    void routingRuleCanaryFallsBackToStableAndStableNeverEntersCanary() {
        List<Node> mixed = List.of(
                new Node("s1", ""),
                new Node("s2", "stable"),
                new Node("c1", "canary"),
                new Node("bad", "gray-v2"));
        List<String> unknown = new ArrayList<>();

        // stable 调用方：仅选 stable，绝不进 canary，跳过未知轨道
        List<Node> forStable = ReleaseTrack.filterCandidates(ReleaseTrack.STABLE, mixed, Node::track, n -> unknown.add(n.id()));
        assertThat(forStable).extracting(Node::id).containsExactly("s1", "s2");
        assertThat(unknown).containsExactly("bad");

        // canary 调用方：有 canary 节点时仅选 canary
        List<Node> forCanary = ReleaseTrack.filterCandidates(ReleaseTrack.CANARY, mixed, Node::track, null);
        assertThat(forCanary).extracting(Node::id).containsExactly("c1");

        // canary 调用方：无 canary 实例时退回 stable
        List<Node> stableOnly = List.of(new Node("s1", "stable"), new Node("s2", null));
        List<Node> fallback = ReleaseTrack.filterCandidates(ReleaseTrack.CANARY, stableOnly, Node::track, null);
        assertThat(fallback).extracting(Node::id).containsExactly("s1", "s2");
    }

    @Test
    void battleTrackRequiresAllMembersCanary() {
        assertThat(ReleaseTrack.battleTrackForMembers(List.of())).isEqualTo(ReleaseTrack.STABLE);
        assertThat(ReleaseTrack.battleTrackForMembers(List.of(ReleaseTrack.CANARY, ReleaseTrack.CANARY)))
                .isEqualTo(ReleaseTrack.CANARY);
        assertThat(ReleaseTrack.battleTrackForMembers(List.of(ReleaseTrack.CANARY, ReleaseTrack.STABLE)))
                .isEqualTo(ReleaseTrack.STABLE);
    }
}
