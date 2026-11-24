package com.game.match.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.api.proto.BattleNodeInfo;
import com.game.api.proto.SessionContext;
import com.game.common.deadline.Deadline;
import com.game.discovery.NodeDirectory;
import com.game.match.MatchProperties;
import com.game.match.port.PlayerStatusReader;
import com.game.proto.match.GetNetworkProbePlanResponse;
import com.game.table.MatchErrorTip;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 探测目标机房内按玩家 HRW 分散与无合格机房回 16023 校验。 */
class NetworkProbeTargetHrwTest {

    private static BattleNodeInfo node(int nodeId, int region, String instanceId, boolean accepting) {
        return BattleNodeInfo.newBuilder()
                .setNodeId(nodeId)
                .setPhysicalRegion(region)
                .setInstanceId(instanceId)
                .setClientHost("10.0." + region + "." + nodeId)
                .setClientPort(12000 + nodeId)
                .setAccepting(accepting)
                .build();
    }

    @Test
    void 单台行为不变_多台均摊稳定且增删节点仅迁移少数玩家() {
        BattleNodeInfo n1 = node(1, 1, "inst-a", true);
        BattleNodeInfo n2 = node(2, 1, "inst-b", true);
        BattleNodeInfo n3 = node(3, 1, "inst-c", true);
        BattleNodeInfo n4 = node(4, 1, "inst-d", true);
        BattleNodeInfo draining = node(5, 1, "inst-draining", false);

        // 单台行为不变
        for (long p = 1; p <= 20; p++) {
            List<BattleNodeInfo> only = GlobalMatchService.selectProbeTargets(p, List.of(n1, draining));
            assertThat(only).extracting(BattleNodeInfo::getInstanceId).containsExactly("inst-a");
        }

        // 多台对 400 名玩家均匀分散且与输入顺序无关
        List<BattleNodeInfo> three = List.of(n1, n2, n3, draining);
        List<BattleNodeInfo> reversed = List.of(n3, n2, n1, draining);
        Map<String, Integer> counts = new HashMap<>();
        Map<Long, String> before = new HashMap<>();
        for (long p = 1; p <= 400; p++) {
            String pick = GlobalMatchService.selectProbeTargets(p, three).getFirst().getInstanceId();
            String pickRev = GlobalMatchService.selectProbeTargets(p, reversed).getFirst().getInstanceId();
            assertThat(pick).isEqualTo(pickRev);
            counts.merge(pick, 1, Integer::sum);
            before.put(p, pick);
        }
        assertThat(counts.keySet()).containsExactlyInAnyOrder("inst-a", "inst-b", "inst-c");
        counts.values().forEach(c -> assertThat(c).isBetween(80, 200));

        // 扩容新增第 4 台：只有切到 inst-d 的玩家移动，未中 inst-d 的玩家保持原台
        List<BattleNodeInfo> four = List.of(n1, n2, n3, n4);
        int movedToNew = 0;
        for (long p = 1; p <= 400; p++) {
            String after = GlobalMatchService.selectProbeTargets(p, four).getFirst().getInstanceId();
            if (!after.equals(before.get(p))) {
                assertThat(after).isEqualTo("inst-d");
                movedToNew++;
            }
        }
        assertThat(movedToNew).isBetween(50, 160);
    }

    @Test
    @SuppressWarnings("unchecked")
    void 目录可读但无合格机房回16023_目录故障仍向上抛错() {
        GlobalMatchStore store = mock(GlobalMatchStore.class);
        when(store.now()).thenReturn(1_000_000L);
        GlobalMatchProperties cfg = mock(GlobalMatchProperties.class);
        when(cfg.profileTtlSeconds()).thenReturn(300);
        NodeDirectory<BattleNodeInfo> dir = mock(NodeDirectory.class);
        PlayerStatusReader players = mock(PlayerStatusReader.class);
        var presence = com.game.discovery.proto.PlayerPresence.newBuilder()
                .setGateInstanceId("gate-1").setSessionId(9).build();
        when(players.presence(any(Long.class), any())).thenReturn(Optional.of(presence));
        GlobalMatchService service = new GlobalMatchService(
                store, cfg, mock(MatchProperties.class), dir, null, null, null, null, players, null, null);
        SessionContext session = SessionContext.newBuilder()
                .setPlayerId(101).setSessionId(9).setAccount("acc-1").setOwnerRevision(1).setGateInstanceId("gate-1").build();

        // 目录可读但没有 accepting 节点 -> 业务码 16023
        when(dir.list(anyInt())).thenReturn(List.of(node(1, 1, "inst-a", false)));
        GetNetworkProbePlanResponse resp = service.plan(session, Deadline.after(3000));
        assertThat(resp.hasErrorMessage()).isTrue();
        assertThat(resp.getErrorMessage().getId())
                .isEqualTo(MatchErrorTip.match_error.kMatchNoRegionWithinLatency_VALUE);
        assertThat(resp.getTargetsCount()).isZero();

        // 目录不可用 -> 抛出异常交给上游折算故障码
        when(dir.list(anyInt())).thenThrow(new IllegalStateException("redis down"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.plan(session, Deadline.after(3000)))
                .isInstanceOf(IllegalStateException.class);
    }
}
