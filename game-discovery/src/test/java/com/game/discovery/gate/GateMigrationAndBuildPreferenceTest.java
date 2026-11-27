package com.game.discovery.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.BuildGenerationSelector;
import com.game.discovery.world.WorldLeaderLock;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 覆盖排空迁移目标筛选（不回落、新一代构建优先、余量估算）及选主重试间隔解耦。 */
class GateMigrationAndBuildPreferenceTest {

    private static GateNodeInfo gate(int nodeId, boolean draining, int players, int max, String buildId) {
        return GateNodeInfo.newBuilder()
                .setZoneId(1)
                .setNodeId(nodeId)
                .setClientHost("10.0.0." + nodeId)
                .setClientPort(7000 + nodeId)
                .setDraining(draining)
                .setPlayerCount(players)
                .setMaxConnections(max)
                .setDeploymentBuildId(buildId)
                .build();
    }

    @Test
    void 组合构建标识与工作负载修订区分同镜像滚动重启() {
        assertThat(BuildGenerationSelector.composeBuildId("v1.2.0", "rev-abc")).isEqualTo("v1.2.0@rev-abc");
        assertThat(BuildGenerationSelector.composeBuildId("v1.2.0", "rev-def"))
                .isNotEqualTo(BuildGenerationSelector.composeBuildId("v1.2.0", "rev-abc"));
    }

    @Test
    void 迁移选目标剔除自身排空满载不回落且优先新一代构建() {
        GateNodeInfo g1 = gate(1, true, 100, 1000, "img1@r1");
        GateNodeInfo g2 = gate(2, false, 100, 1000, "img1@r1"); // 同旧代健康
        GateNodeInfo g3 = gate(3, false, 150, 1000, "img1@r2"); // 新一代健康
        GateNodeInfo g4 = gate(4, false, 900, 1000, "img1@r2"); // 新一代但达 90% 水位满载

        // 优先挑非外出构建的新一代健康节点（g3），跳过满载 g4 与旧代 g2
        assertThat(GatePicker.eligibleForMigration(1, 1, List.of(g1, g2, g3, g4)))
                .extracting(GateNodeInfo::getNodeId)
                .containsExactly(3);

        // 没有新一代时回退到同旧代健康节点 g2
        assertThat(GatePicker.eligibleForMigration(1, 1, List.of(g1, g2, g4)))
                .extracting(GateNodeInfo::getNodeId)
                .containsExactly(2);

        // 全在排空或满载：绝不回落，返回空
        assertThat(GatePicker.pickForMigration(1, 1, List.of(g1, g4), null)).isEmpty();

        // 排空余量估算（90% 水位 900 - 已连）
        assertThat(GatePicker.remainingMigrationHeadroom(1, 1, List.of(g1, g2, g3, g4)))
                .isEqualTo((900 - 100) + (900 - 150));
    }

    @Test
    void 选主重试间隔与TTL脱钩且分线目录TTL同源推导() {
        assertThat(WorldLeaderLock.DEFAULT_RETRY_INTERVAL).isEqualTo(Duration.ofSeconds(2));
        assertThat(WorldLeaderLock.deriveChannelDirectoryTtl(Duration.ofSeconds(2), Duration.ofSeconds(2)))
                .isEqualTo(Duration.ofSeconds(15));
        assertThat(WorldLeaderLock.deriveChannelDirectoryTtl(Duration.ofSeconds(5), Duration.ofSeconds(4)))
                .isEqualTo(Duration.ofSeconds(22));
    }
}
