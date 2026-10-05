package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.scenemanager.WorldSceneConfigs;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/** 健康组件 {@code worldChannels}（scene-channels-spec §5.2、§9.3 WorldChannelsHealthIndicatorTest）。 */
class WorldChannelsHealthIndicatorTest {

    private static final WorldSceneConfigs WORLD = new WorldSceneConfigs(1, new LinkedHashSet<>(List.of(1, 2)));

    private final FakeWorldChannelStore store = new FakeWorldChannelStore();
    private final Map<Integer, List<SceneNodeInfo>> directory = new TreeMap<>();
    private final WorldChannelsHealthIndicator indicator =
            new WorldChannelsHealthIndicator(store, zone -> directory.getOrDefault(zone, List.of()), WORLD);

    private void node(int zone, int nodeId, SceneEntry... scenes) {
        directory.computeIfAbsent(zone, z -> new ArrayList<>()).add(SceneNodeInfo.newBuilder().setZoneId(zone).setNodeId(nodeId)
                .setLinkHost("127.0.0.1").setLinkPort(21000).addAllScenes(List.of(scenes)).build());
    }

    private static SceneEntry scene(long sceneId, int conf, boolean draining) {
        return SceneEntry.newBuilder().setSceneId(sceneId).setSceneConfigId(conf).setDraining(draining).build();
    }

    private void plan(int zone, long sceneId, int conf, int node, ChannelState state) {
        store.zone(zone).channels.put(sceneId, PlanFixture.channel(sceneId, conf, node, 0, state,
                DrainReason.DRAIN_REASON_UNSPECIFIED, 1, 0));
    }

    @Test
    void 没有活节点的zone不算_UP() {
        store.registerZone(1);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("zonesWithNodes", 0);
    }

    @Test
    void 每张World图都有可分配的频道才UP() {
        store.registerZone(1);
        plan(1, 101, 1, 10, ChannelState.CHANNEL_ACTIVE);
        plan(1, 201, 2, 10, ChannelState.CHANNEL_ACTIVE);
        node(1, 10, scene(101, 1, false), scene(201, 2, false));

        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void 某图只有排空中_没建出来或计划外的场景时DOWN并列出缺的图() {
        store.registerZone(1);
        store.registerZone(2);
        plan(1, 101, 1, 10, ChannelState.CHANNEL_ACTIVE);
        plan(1, 201, 2, 10, ChannelState.CHANNEL_ACTIVE);
        node(1, 10, scene(101, 1, false), scene(201, 2, true), scene(999, 2, false)); // 201 排空中、999 计划外
        plan(2, 301, 1, 10, ChannelState.CHANNEL_DRAINING);
        node(2, 10, scene(301, 1, false));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("mapsWithoutChannel")).isEqualTo(Map.of("1", List.of(2), "2", List.of(1, 2)));
    }

    @Test
    void Redis出错时DOWN() {
        store.zonesFailure = new IllegalStateException("redis down");

        assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
    }
}
