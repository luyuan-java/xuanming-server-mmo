package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.ChannelKind;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.scenemanager.world.FakeWorldChannelStore;
import com.game.scenemanager.world.NodeAvailability;
import com.game.scenemanager.world.WorldChannelCoordinator;
import com.game.scenemanager.world.WorldChannelMetrics;
import com.game.scenemanager.world.WorldChannelPlanner;
import com.game.scenemanager.world.WorldChannelProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 批次 4T-2B：短重连回落指标（resume_scene_fallback）、滚动升级新版本优先（频道分配与编排）、R7 不超期望数防线。 */
class Batch4T2BSceneManagerParityTest {

    @Test
    void 短重连原场景不在或排空中回落默认并计resume_scene_fallback指标() {
        SceneNodeInfo node = SceneNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(10).setInstanceId("i-10").setLinkHost("127.0.0.1").setLinkPort(9001)
                .addScenes(SceneEntry.newBuilder().setSceneId(1001L).setSceneConfigId(1).setDraining(true)
                        .setKind(ChannelKind.CHANNEL_KIND_WORLD))
                .addScenes(SceneEntry.newBuilder().setSceneId(1002L).setSceneConfigId(1).setDraining(false)
                        .setKind(ChannelKind.CHANNEL_KIND_WORLD))
                .build();
        SceneNodeSource source = zoneId -> List.of(node);
        WorldSceneConfigs configs = new WorldSceneConfigs(1, java.util.Set.of(1));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SceneAssigner assigner = new SceneAssigner(source, configs, ChannelSelector.withoutReservations(source), null, registry);

        // 1. 原场景正常未排空 -> 命中原场景，不计回落
        AssignSceneResponse hit = assigner.assign(AssignSceneRequest.newBuilder()
                .setZoneId(1).setPlayerId(7L).setPreferredSceneNodeId(10).setPreferredSceneId(1002L).build());
        assertThat(hit.getSceneId()).isEqualTo(1002L);
        assertThat(assigner.resumeSceneFallbackCount()).isZero();

        // 2. 原场景排空中 -> 不回原场景、落活跃频道并计回落 1
        AssignSceneResponse drainingFallback = assigner.assign(AssignSceneRequest.newBuilder()
                .setZoneId(1).setPlayerId(7L).setPreferredSceneNodeId(10).setPreferredSceneId(1001L).build());
        assertThat(drainingFallback.getSceneId()).isEqualTo(1002L);
        assertThat(assigner.resumeSceneFallbackCount()).isEqualTo(1L);

        // 3. 原场景已不存在 -> 回落并计 2，Micrometer Counter 同值
        AssignSceneResponse goneFallback = assigner.assign(AssignSceneRequest.newBuilder()
                .setZoneId(1).setPlayerId(7L).setPreferredSceneNodeId(10).setPreferredSceneId(9999L).build());
        assertThat(goneFallback.getSceneId()).isEqualTo(1002L);
        assertThat(assigner.resumeSceneFallbackCount()).isEqualTo(2L);
        assertThat(registry.counter(SceneAssigner.RESUME_SCENE_FALLBACK_METRIC).count()).isEqualTo(2.0);
    }

    @Test
    void 滚动升级新版本优先_选线与频道编排优先落到非外出构建节点() {
        // build-v1 的节点 1 在排空 -> build-v1 为外出构建；同属 build-v1 的节点 2 仍未排空且人数为 0，
        // build-v2 的节点 3 人数为 5：选线和编排必须优先挑选非外出构建节点 3！
        SceneNodeInfo oldDraining = SceneNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(1).setInstanceId("i-1").setLinkHost("127.0.0.1").setLinkPort(9001)
                .setEvacuating(true).setDeploymentBuildId("build-v1")
                .addScenes(SceneEntry.newBuilder().setSceneId(101L).setSceneConfigId(1).setPlayerCount(20)
                        .setDraining(true).setKind(ChannelKind.CHANNEL_KIND_WORLD))
                .build();
        SceneNodeInfo oldStillAlive = SceneNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(2).setInstanceId("i-2").setLinkHost("127.0.0.1").setLinkPort(9002)
                .setEvacuating(false).setDeploymentBuildId("build-v1")
                .addScenes(SceneEntry.newBuilder().setSceneId(102L).setSceneConfigId(1).setPlayerCount(0)
                        .setDraining(false).setKind(ChannelKind.CHANNEL_KIND_WORLD))
                .build();
        SceneNodeInfo newBuild = SceneNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(3).setInstanceId("i-3").setLinkHost("127.0.0.1").setLinkPort(9003)
                .setEvacuating(false).setDeploymentBuildId("build-v2")
                .addScenes(SceneEntry.newBuilder().setSceneId(103L).setSceneConfigId(1).setPlayerCount(5)
                        .setDraining(false).setKind(ChannelKind.CHANNEL_KIND_WORLD))
                .build();
        SceneNodeSource source = zoneId -> List.of(oldDraining, oldStillAlive, newBuild);
        ChannelSelector selector = ChannelSelector.withoutReservations(source);

        var picked = selector.select(1, 1, 0L, 42L);
        assertThat(picked).isPresent();
        assertThat(picked.get().nodeId()).as("新版本优先：跳过外出构建 build-v1 的空节点 2，优先选 build-v2 节点 3").isEqualTo(3);

        // 编排补建同样优先落到 build-v2 节点 3，且总活跃频道数严格受期望数 1 约束（R7）
        FakeWorldChannelStore store = new FakeWorldChannelStore();
        store.registerZone(1);
        AtomicLong ids = new AtomicLong(500);
        WorldChannelProperties props = WorldChannelProperties.defaults();
        WorldChannelPlanner planner = WorldChannelPlanner.fromDirectory(props, List.of(1), () -> OptionalLong.of(ids.incrementAndGet()));
        WorldChannelCoordinator coord = new WorldChannelCoordinator(store, source, planner, props,
                new WorldChannelMetrics(new SimpleMeterRegistry(), List.of(1)), NodeAvailability.ALL, "sm-1:tok", System::nanoTime, 1);
        coord.tick();
        var plan = store.readPlan(1);
        assertThat(plan.channels()).hasSize(1);
        assertThat(plan.channels().getFirst().getNodeId())
                .as("编排补建优先落非外出构建节点 3").isEqualTo(3);
    }
}
