package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.proto.SceneChannelDirectory;
import com.game.proto.SceneChannelInfo;
import com.game.proto.SceneChannelState;
import com.game.proto.WorldMapHeatLevel;
import com.game.proto.WorldMapOverview;
import com.game.proto.WorldTideSnapshot;
import com.game.scenemanager.ChannelSelector;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;

/** M1（world-map-play-v2）Java 侧对账测试：Fill-First 软上限选线、迟滞带校验、双层潮汐与世界地图总览。 */
class WorldMapOverviewTest {

    @Test
    void fillFirst优先填满首线再溢出_全线达软上限回退最少人() {
        List<SceneNodeInfo> nodes = List.of(
                SceneNodeInfo.newBuilder().setZoneId(1).setNodeId(1).setLinkHost("127.0.0.1").setLinkPort(21001)
                        .addScenes(SceneEntry.newBuilder().setSceneId(101).setSceneConfigId(2).setPlayerCount(2))
                        .addScenes(SceneEntry.newBuilder().setSceneId(102).setSceneConfigId(2).setPlayerCount(0))
                        .build()
        );
        FakeWorldChannelStore store = new FakeWorldChannelStore();
        ChannelSelector fillFirst = new ChannelSelector(z -> nodes, store, Duration.ofSeconds(10), 3);

        // 101 已有 2 人 (<3)，102 空着 -> 仍聚进 101
        assertThat(fillFirst.select(1, 2, 0, 5001).orElseThrow().scene().getSceneId()).isEqualTo(101L);
        // 101 达到 2+1=3 人 (>=3) -> 溢出到 102
        assertThat(fillFirst.select(1, 2, 0, 5002).orElseThrow().scene().getSceneId()).isEqualTo(102L);
        // 不开预占路径同样生效
        ChannelSelector noResv = new ChannelSelector(z -> nodes, null, Duration.ZERO, 3);
        assertThat(noResv.select(1, 2, 0, 5003).orElseThrow().scene().getSceneId()).isEqualTo(101L);

        // 全线 >= 软上限时回退至人数最少线
        List<SceneNodeInfo> fullNodes = List.of(
                SceneNodeInfo.newBuilder().setZoneId(1).setNodeId(1).setLinkHost("127.0.0.1").setLinkPort(21001)
                        .addScenes(SceneEntry.newBuilder().setSceneId(101).setSceneConfigId(2).setPlayerCount(5))
                        .addScenes(SceneEntry.newBuilder().setSceneId(102).setSceneConfigId(2).setPlayerCount(3))
                        .build()
        );
        ChannelSelector fallback = new ChannelSelector(z -> fullNodes, store, Duration.ofSeconds(10), 3);
        assertThat(fallback.select(1, 2, 0, 5004).orElseThrow().scene().getSceneId()).isEqualTo(102L);
    }

    @Test
    void fillFirst与自动缩容迟滞带校验() {
        assertThat(WorldChannelProperties.defaults().effectiveSoftFillThreshold()).isEqualTo(200);
        assertThat(WorldChannelPropertiesTest.bind(Map.of("pick-policy", "least-loaded"))
                .effectiveSoftFillThreshold()).isZero();
        assertThatThrownBy(() -> WorldChannelPropertiesTest.bind(Map.of(
                "autoscale.enabled", "true",
                "autoscale.scale-in-players", "100",
                "soft-fill-threshold", "150")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> WorldChannelPropertiesTest.bind(Map.of(
                "autoscale.enabled", "true",
                "autoscale.scale-out-players", "2000",
                "soft-fill-threshold", "2000")))
                .isInstanceOf(BindException.class);
    }

    @Test
    void 灵潮与东八区05点今日旺地确定性轮换与总览构建() {
        List<Integer> candidates = List.of(2, 5);
        WorldTideSnapshot t0 = WorldMapOverviewBuilder.worldTideAt(0, candidates, 3600, 2400);
        assertThat(t0.getActive()).isTrue();
        assertThat(t0.getSceneConfigId()).isEqualTo(2);
        assertThat(t0.getEndsAtMs()).isEqualTo(2_400_000L);
        assertThat(t0.getNextStartsAtMs()).isEqualTo(3_600_000L);

        assertThat(WorldMapOverviewBuilder.worldTideAt(2400, candidates, 3600, 2400).getActive()).isFalse();
        assertThat(WorldMapOverviewBuilder.worldTideAt(3600, candidates, 3600, 2400).getSceneConfigId()).isEqualTo(5);

        long beforeReset = Instant.parse("2026-11-22T20:59:59Z").getEpochSecond();
        long atReset = Instant.parse("2026-11-22T21:00:00Z").getEpochSecond();
        assertThat(WorldMapOverviewBuilder.dailyHotspotAt(beforeReset, candidates))
                .isNotEqualTo(WorldMapOverviewBuilder.dailyHotspotAt(atReset, candidates));

        Map<Integer, SceneChannelDirectory> dirs = Map.of(
                1, dir(1, channel(10001, 1, 10, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH)),
                2, dir(2, channel(20001, 1, 3, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH)),
                3, dir(3, channel(30001, 1, 50, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH)),
                5, dir(5, channel(50001, 1, 0, SceneChannelState.SCENE_CHANNEL_STATE_SMOOTH))
        );
        long nowMs = (3600L + 600L) * 1000L;
        WorldMapOverview overview = WorldMapOverviewBuilder.build(1, WorldChannelProperties.WorldMapPlay.defaults(), dirs, nowMs);
        assertThat(overview.getZoneId()).isEqualTo(1);
        assertThat(overview.getTide().getActive()).isTrue();
        assertThat(overview.getTide().getSceneConfigId()).isEqualTo(5);
        assertThat(overview.getTide().getRecommendedSceneId()).isEqualTo(50001L);
        assertThat(overview.getMapsList()).extracting(e -> e.getSceneConfigId()).containsExactly(1, 2, 5);
        assertThat(overview.getMaps(0).getHeatLevel()).isEqualTo(WorldMapHeatLevel.WORLD_MAP_HEAT_LEVEL_CROWDED);
        assertThat(overview.getMaps(1).getHeatLevel()).isEqualTo(WorldMapHeatLevel.WORLD_MAP_HEAT_LEVEL_ACTIVE);
        assertThat(overview.getMaps(2).getHeatLevel()).isEqualTo(WorldMapHeatLevel.WORLD_MAP_HEAT_LEVEL_QUIET);
        assertThat(overview.getMaps(2).getIsTideMap()).isTrue();
    }

    private static SceneChannelDirectory dir(int confId, SceneChannelInfo... channels) {
        return SceneChannelDirectory.newBuilder().setZoneId(1).setSceneConfigId(confId)
                .addAllChannels(List.of(channels)).build();
    }

    private static SceneChannelInfo channel(long sceneId, int no, int players, SceneChannelState state) {
        return SceneChannelInfo.newBuilder().setSceneId(sceneId).setChannelNo(no)
                .setPlayerCount(players).setState(state).build();
    }
}
