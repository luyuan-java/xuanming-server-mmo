package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.ChannelKind;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ZoneControlPlaneAndBootstrapTest {

    @Test
    void onDemandBootstrapExcludesEvacuatingAndDuplicateNodeIdsAndAssignsOnceBootstrapped() {
        SceneNodeInfo evacuating = SceneNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(1).setInstanceId("i-evac").setLinkHost("10.0.0.1").setLinkPort(9001).setEvacuating(true).build();
        SceneNodeInfo dupA = SceneNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(2).setInstanceId("i-dup-a").setLinkHost("10.0.0.2").setLinkPort(9002).build();
        SceneNodeInfo dupB = SceneNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(2).setInstanceId("i-dup-b").setLinkHost("10.0.0.3").setLinkPort(9003).build();
        SceneNodeInfo healthy = SceneNodeInfo.newBuilder()
                .setZoneId(1).setNodeId(3).setInstanceId("i-ok").setLinkHost("10.0.0.4").setLinkPort(9004).build();

        List<SceneNodeInfo> directory = new ArrayList<>(List.of(evacuating, dupA, dupB, healthy));
        SceneNodeSource source = zoneId -> List.copyOf(directory);
        AtomicInteger provisions = new AtomicInteger();

        OnDemandWorldBootstrap bootstrap = new OnDemandWorldBootstrap(
                source,
                (z, n) -> n.getNodeId() == 3,
                (z, mapId, eligible) -> {
                    provisions.incrementAndGet();
                    assertThat(eligible).extracting(SceneNodeInfo::getNodeId).containsExactly(3);
                    directory.set(3, healthy.toBuilder().addScenes(SceneEntry.newBuilder()
                            .setSceneId(5001L).setSceneConfigId(mapId).setKind(ChannelKind.CHANNEL_KIND_WORLD).build()).build());
                    return true;
                });

        WorldSceneConfigs configs = new WorldSceneConfigs(101, Set.of(101, 102));
        SceneAssigner assigner = new SceneAssigner(source, configs, ChannelSelector.withoutReservations(source), bootstrap);

        AssignSceneResponse resp = assigner.assign(AssignSceneRequest.newBuilder()
                .setZoneId(1).setPlayerId(10001L).setPreferredSceneConfigId(102).build());
        assertThat(resp.getTipId()).isZero();
        assertThat(resp.getSceneNodeId()).isEqualTo(3);
        assertThat(resp.getSceneId()).isEqualTo(5001L);
        assertThat(resp.getSceneConfigId()).isEqualTo(102);
        assertThat(provisions.get()).isEqualTo(1);
    }
}
