package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.scenemanager.world.FakeWorldChannelStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 选频道 + 软预占（scene-channels-spec §4.11、§9.3 SceneAssignerTest 的预占部分，D7、D20）：预占改变选择、并列规则、同一玩家重复分配不重复计数、
 * 原实例也写预占、{@code reservation-ttl=0} 退回现行为、Lua 异常原样上抛。
 */
class ChannelSelectorTest {

    private static final int ZONE = 1;
    private static final WorldSceneConfigs WORLD = new WorldSceneConfigs(1, Set.of(1, 2));
    private static final Duration TTL = Duration.ofSeconds(10);

    private final List<SceneNodeInfo> nodes = new ArrayList<>();
    private final SceneNodeSource source = zone -> List.copyOf(nodes);
    private final FakeWorldChannelStore store = new FakeWorldChannelStore();
    private final ChannelSelector selector = new ChannelSelector(source, store, TTL);
    private final SceneAssigner assigner = new SceneAssigner(source, WORLD, selector);

    private AssignSceneResponse assign(long playerId) {
        return assigner.assign(AssignSceneRequest.newBuilder().setZoneId(ZONE).setPlayerId(playerId).build());
    }

    @Test
    void 预占让并发进场摊开_而不是在一个上报周期内扎堆() {
        nodes.add(node(1, scene(101, 1, 0), scene(102, 1, 0)));
        nodes.add(node(2, scene(201, 1, 0)));

        Map<Long, Integer> spread = new HashMap<>();
        for (long player = 1; player <= 30; player++) {
            spread.merge(assign(player).getSceneId(), 1, Integer::sum);
        }

        assertThat(spread).containsOnlyKeys(101L, 102L, 201L).allSatisfy((scene, n) -> assertThat(n).isEqualTo(10));
        assertThat(store.reservationCount(ZONE, 101)).isEqualTo(10);
    }

    @Test
    void 并列取节点号小的_再取场景号小的() {
        nodes.add(node(9, scene(901, 1, 3)));
        nodes.add(node(4, scene(405, 1, 3), scene(402, 1, 3)));

        assertThat(assign(42).getSceneId()).isEqualTo(402);
    }

    @Test
    void 负载是目录人数加别人的预占() {
        nodes.add(node(1, scene(101, 1, 5)));
        nodes.add(node(2, scene(201, 1, 0)));
        for (long p = 100; p < 106; p++) {
            store.reserveScene(ZONE, 201, p, TTL);
        }

        assertThat(assign(42).getSceneId()).isEqualTo(101); // 5 < 0 + 6
    }

    @Test
    void 同一玩家重复分配不重复计数也不换频道() {
        nodes.add(node(1, scene(101, 1, 0)));
        nodes.add(node(2, scene(201, 1, 0)));

        long first = assign(42).getSceneId();
        long second = assign(42).getSceneId();

        assertThat(second).isEqualTo(first);
        assertThat(store.reservationCount(ZONE, 101) + store.reservationCount(ZONE, 201)).isEqualTo(1);
    }

    @Test
    void 预占到期后不再计数() {
        nodes.add(node(1, scene(101, 1, 0)));
        nodes.add(node(2, scene(201, 1, 0)));
        assertThat(assign(1).getSceneId()).isEqualTo(101);

        store.nowMs += TTL.toMillis();

        assertThat(assign(2).getSceneId()).isEqualTo(101);
    }

    @Test
    void 原实例直接用_并给它记一条预占() {
        nodes.add(node(1, scene(101, 2, 80)));
        nodes.add(node(2, scene(201, 2, 0)));

        AssignSceneResponse resp = assigner.assign(AssignSceneRequest.newBuilder().setZoneId(ZONE).setPlayerId(42)
                .setPreferredSceneConfigId(2).setPreferredSceneNodeId(1).setPreferredSceneId(101).build());

        assertThat(resp.getSceneId()).isEqualTo(101);
        assertThat(store.reservationCount(ZONE, 101)).isEqualTo(1);
    }

    @Test
    void 预占关闭时只看目录人数_不碰Redis() {
        nodes.add(node(1, scene(101, 1, 0)));
        nodes.add(node(2, scene(201, 1, 0)));
        store.reserveFailure = new IllegalStateException("不该被调用");
        SceneAssigner plain = new SceneAssigner(source, WORLD, new ChannelSelector(source, store, Duration.ZERO));

        for (long p = 1; p <= 3; p++) {
            assertThat(plain.assign(AssignSceneRequest.newBuilder().setZoneId(ZONE).setPlayerId(p).build()).getSceneId())
                    .isEqualTo(101);
        }
    }

    @Test
    void 预占脚本出错原样上抛_不伪装成无场景() {
        nodes.add(node(1, scene(101, 1, 0)));
        store.reserveFailure = new IllegalStateException("NOSCRIPT");

        assertThatThrownBy(() -> assign(42)).isInstanceOf(IllegalStateException.class).hasMessage("NOSCRIPT");
    }

    @Test
    void 读目录的select可排除指定场景_给5_2换频道用() {
        nodes.add(node(1, scene(101, 1, 0), scene(102, 1, 50)));

        assertThat(selector.select(ZONE, 1, 101, 42)).hasValueSatisfying(c -> {
            assertThat(c.nodeId()).isEqualTo(1);
            assertThat(c.scene().getSceneId()).isEqualTo(102);
        });
        assertThat(selector.select(ZONE, 2, 0, 42)).isEmpty();
    }

    @Test
    void 开启预占却没给存储时拒绝构造() {
        assertThatThrownBy(() -> new ChannelSelector(source, null, TTL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChannelSelector(source, store, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SceneNodeInfo node(int nodeId, SceneEntry... scenes) {
        return SceneNodeInfo.newBuilder().setZoneId(ZONE).setNodeId(nodeId).setLinkHost("127.0.0.1")
                .setLinkPort(21000 + nodeId).addAllScenes(List.of(scenes)).build();
    }

    private static SceneEntry scene(long sceneId, int configId, int playerCount) {
        return SceneEntry.newBuilder().setSceneId(sceneId).setSceneConfigId(configId).setPlayerCount(playerCount).build();
    }
}
