package com.game.gateway.zone;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.GateNodeInfo;
import com.game.api.proto.SceneNodeInfo;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneHealthProbe.Health;
import com.game.gateway.zone.ZoneHealthProbe.LoadLevel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 健康状态（无 gate DOWN / 有 gate 无 scene DEGRADED / 都有 HEALTHY）、负载档阈值、失败保留上一份、过期变 UNKNOWN。 */
class ZoneHealthProbeTest {

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final AtomicBoolean broken = new AtomicBoolean();

    private static ZoneRow zone(int id, int capacity) {
        return new ZoneRow(id, "区" + id, 0, capacity, "", null, false, id, 0, 0);
    }

    private static GateNodeInfo gate(int players) {
        return GateNodeInfo.newBuilder().setPlayerCount(players).build();
    }

    private ZoneHealthProbe probe(Map<Integer, List<GateNodeInfo>> gates, Map<Integer, List<SceneNodeInfo>> scenes) {
        return new ZoneHealthProbe(() -> {
            if (broken.get()) {
                throw new IllegalStateException("MySQL 不可达");
            }
            return List.of(zone(1, 100), zone(2, 100), zone(3, 100), zone(4, 0));
        }, z -> gates.getOrDefault(z, List.of()), z -> scenes.getOrDefault(z, List.of()), now::get,
                Duration.ofSeconds(15));
    }

    @Test
    void 健康状态与负载档() {
        ZoneHealthProbe probe = probe(Map.of(2, List.of(gate(30), gate(19)), 3, List.of(gate(80)), 4, List.of(gate(9))),
                Map.of(3, List.of(SceneNodeInfo.getDefaultInstance()), 4, List.of(SceneNodeInfo.getDefaultInstance())));
        assertThat(probe.health(1)).as("从没探测过").isEqualTo(Health.UNKNOWN);
        probe.probe();
        assertThat(probe.health(1)).isEqualTo(Health.DOWN);
        assertThat(probe.health(2)).isEqualTo(Health.DEGRADED);
        assertThat(probe.health(3)).isEqualTo(Health.HEALTHY);
        assertThat(probe.loadLevel(2)).contains(LoadLevel.SMOOTH);
        assertThat(probe.loadLevel(3)).contains(LoadLevel.FULL);
        assertThat(probe.loadLevel(4)).as("capacity ≤ 0").contains(LoadLevel.SMOOTH);
        assertThat(probe.health(99)).as("不在目录里").isEqualTo(Health.UNKNOWN);
        assertThat(ZoneHealthProbe.loadLevel(50, 100)).isEqualTo(LoadLevel.BUSY);
        assertThat(ZoneHealthProbe.loadLevel(79, 100)).isEqualTo(LoadLevel.BUSY);
        assertThat(ZoneHealthProbe.loadLevel(80, 100)).isEqualTo(LoadLevel.FULL);
    }

    @Test
    void 探测失败保留上一份_超过TTL变UNKNOWN() {
        ZoneHealthProbe probe = probe(Map.of(), Map.of());
        probe.probe();
        assertThat(probe.health(1)).isEqualTo(Health.DOWN);
        broken.set(true);
        now.addAndGet(10_000);
        probe.probe();
        assertThat(probe.health(1)).as("上一份还没过期").isEqualTo(Health.DOWN);
        now.addAndGet(5_001);
        assertThat(probe.health(1)).isEqualTo(Health.UNKNOWN);
        assertThat(probe.loadLevel(1)).isEmpty();
    }
}
