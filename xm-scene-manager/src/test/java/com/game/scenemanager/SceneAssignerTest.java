package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.AssignSceneRequest;
import com.game.api.proto.AssignSceneResponse;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SceneAssignerTest {

    private static final int ZONE = 1;
    /** 世界地图：1（默认）、2；3 是副本类配置，不在 World 表里。 */
    private static final WorldSceneConfigs WORLD = new WorldSceneConfigs(1, Set.of(1, 2));

    private final FakeSource source = new FakeSource();
    private final SceneAssigner assigner = new SceneAssigner(source, WORLD);

    @Test
    void 期望地图有节点承载时_进期望地图人数最少的场景() {
        source.add(node(1, scene(101, 1, 0), scene(102, 2, 30)));
        source.add(node(2, scene(201, 2, 10)));

        AssignSceneResponse resp = assign(2);

        assertSuccess(resp, 2, 201, 2);
    }

    @Test
    void 期望地图没有节点承载时_回落默认世界地图() {
        source.add(node(1, scene(101, 1, 5)));

        assertSuccess(assign(2), 1, 101, 1);
    }

    @Test
    void 不带期望地图时_进默认世界地图() {
        source.add(node(1, scene(101, 1, 5), scene(102, 2, 0)));

        assertSuccess(assign(0), 1, 101, 1);
    }

    @Test
    void 期望地图不是世界地图时_即使有节点承载也回落默认() {
        source.add(node(1, scene(101, 1, 50), scene(301, 3, 0)));

        assertSuccess(assign(3), 1, 101, 1);
    }

    @Test
    void 带原场景实例且还在_直接用它不看人数() {
        source.add(node(1, scene(101, 2, 80)));
        source.add(node(2, scene(201, 2, 0)));

        assertSuccess(assign(2, 1, 101), 1, 101, 2);
    }

    @Test
    void 原场景实例已不在_按原地图选人数最少的() {
        source.add(node(1, scene(102, 2, 9)));
        source.add(node(2, scene(201, 2, 3)));

        assertSuccess(assign(2, 1, 101), 2, 201, 2);
    }

    @Test
    void 原场景号对上但节点号对不上_不算原实例() {
        source.add(node(1, scene(101, 1, 9)));
        source.add(node(2, scene(201, 1, 0)));

        assertSuccess(assign(1, 2, 101), 2, 201, 1);
    }

    @Test
    void 原实例所在节点条目不完整_不用它() {
        source.add(node(1, scene(101, 2, 0)).toBuilder().setLinkHost("").build());
        source.add(node(2, scene(201, 2, 5)));

        assertSuccess(assign(2, 1, 101), 2, 201, 2);
    }

    @Test
    void 人数并列时取节点号小的() {
        source.add(node(9, scene(901, 1, 3)));
        source.add(node(4, scene(401, 1, 3)));
        source.add(node(7, scene(701, 1, 3)));

        assertSuccess(assign(0), 4, 401, 1);
    }

    @Test
    void 人数和节点号都并列时取场景号小的() {
        source.add(node(4, scene(405, 1, 3), scene(402, 1, 3), scene(409, 1, 3)));

        assertSuccess(assign(0), 4, 402, 1);
    }

    @Test
    void 人数优先于节点号() {
        source.add(node(1, scene(101, 1, 8)));
        source.add(node(2, scene(201, 1, 7)));

        assertSuccess(assign(0), 2, 201, 1);
    }

    @Test
    void 场景号按无符号比较() {
        // 雪花号是 uint64：最高位为 1 的值在 Java long 里是负数，但按协议它更大。
        long huge = 0x8000_0000_0000_0001L;
        source.add(node(1, scene(huge, 1, 0), scene(5, 1, 0)));

        assertSuccess(assign(0), 1, 5, 1);
    }

    @Test
    void 目录为空时_返回无场景tip() {
        AssignSceneResponse resp = assign(0);

        assertThat(resp.getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE).isEqualTo(3000);
        assertThat(resp.getSceneNodeId()).isZero();
        assertThat(resp.getSceneId()).isZero();
    }

    @Test
    void 只有非默认地图的场景时_不带期望地图返回无场景tip() {
        source.add(node(1, scene(102, 2, 0)));

        assertThat(assign(0).getTipId()).isEqualTo(SceneAssigner.TIP_NO_SCENE);
    }

    @Test
    void 坏条目被跳过() {
        source.add(node(0, scene(1, 1, 0)));                                       // 节点号 0
        source.add(node(2, scene(201, 1, 0)).toBuilder().setZoneId(2).build());    // 串 zone
        source.add(node(3, scene(301, 1, 0)).toBuilder().setLinkHost("").build()); // 无链路地址
        source.add(node(4, scene(401, 1, 0)).toBuilder().setLinkPort(0).build());  // 无链路端口
        source.add(node(5, scene(0, 1, 0), scene(501, 1, 99)));                     // 场景号 0 的场景

        assertSuccess(assign(0), 5, 501, 1);
    }

    @Test
    void zone为0时_返回参数错误且不读目录() {
        AssignSceneResponse resp = assigner.assign(AssignSceneRequest.newBuilder().setPlayerId(42).build());

        assertThat(resp.getTipId()).isEqualTo(SceneAssigner.TIP_BAD_REQUEST).isEqualTo(3005);
        assertThat(source.calls).isZero();
    }

    @Test
    void 按请求的zone读目录() {
        source.add(node(1, scene(101, 1, 0)));

        assigner.assign(AssignSceneRequest.newBuilder().setZoneId(ZONE).build());

        assertThat(source.requestedZones).containsExactly(ZONE);
    }

    @Test
    void 目录读失败时_异常原样抛出而不是伪装成无场景() {
        SceneAssigner failing = new SceneAssigner(zone -> {
            throw new IllegalStateException("redis down");
        }, WORLD);

        assertThatThrownBy(() -> failing.assign(AssignSceneRequest.newBuilder().setZoneId(ZONE).build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("redis down");
    }

    // ---------- helpers ----------

    private AssignSceneResponse assign(int preferredConfigId) {
        return assigner.assign(AssignSceneRequest.newBuilder()
                .setZoneId(ZONE)
                .setPlayerId(42)
                .setPreferredSceneConfigId(preferredConfigId)
                .build());
    }

    private AssignSceneResponse assign(int preferredConfigId, int preferredNodeId, long preferredSceneId) {
        return assigner.assign(AssignSceneRequest.newBuilder()
                .setZoneId(ZONE)
                .setPlayerId(42)
                .setPreferredSceneConfigId(preferredConfigId)
                .setPreferredSceneNodeId(preferredNodeId)
                .setPreferredSceneId(preferredSceneId)
                .build());
    }

    private static void assertSuccess(AssignSceneResponse resp, int nodeId, long sceneId, int configId) {
        assertThat(resp.getTipId()).isZero();
        assertThat(resp.getSceneNodeId()).isEqualTo(nodeId);
        assertThat(resp.getSceneId()).isEqualTo(sceneId);
        assertThat(resp.getSceneConfigId()).isEqualTo(configId);
    }

    private static SceneNodeInfo node(int nodeId, SceneEntry... scenes) {
        return SceneNodeInfo.newBuilder()
                .setZoneId(ZONE)
                .setNodeId(nodeId)
                .setInstanceId("instance-" + nodeId)
                .setLinkHost("127.0.0.1")
                .setLinkPort(21000 + nodeId)
                .addAllScenes(List.of(scenes))
                .build();
    }

    private static SceneEntry scene(long sceneId, int configId, int playerCount) {
        return SceneEntry.newBuilder()
                .setSceneId(sceneId)
                .setSceneConfigId(configId)
                .setPlayerCount(playerCount)
                .build();
    }

    /** 内存假目录：按 zone 返回预置节点，并记录被读的 zone。 */
    private static final class FakeSource implements SceneNodeSource {

        private final Map<Integer, List<SceneNodeInfo>> byZone = new HashMap<>();
        private final List<Integer> requestedZones = new ArrayList<>();
        private int calls;

        void add(SceneNodeInfo node) {
            // 串 zone 的坏条目也放在请求的 zone 下，模拟上报方写错 zone_id 的情况。
            byZone.computeIfAbsent(ZONE, z -> new ArrayList<>()).add(node);
        }

        @Override
        public List<SceneNodeInfo> list(int zoneId) {
            calls++;
            requestedZones.add(zoneId);
            return List.copyOf(byZone.getOrDefault(zoneId, List.of()));
        }
    }
}
