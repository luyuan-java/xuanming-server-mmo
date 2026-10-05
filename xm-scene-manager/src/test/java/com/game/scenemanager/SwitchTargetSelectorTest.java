package com.game.scenemanager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.SceneEntry;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SelectSwitchTargetRequest;
import com.game.scenemanager.SwitchTargetSelector.Result;
import com.game.scenemanager.SwitchTargetSelector.Selection;
import com.game.scenemanager.world.FakeWorldChannelStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 在线换图选跨节点目标（批次 5.2，scene-handoff-spec §5.4、§10.5）：显式场景号只认目录里的那一个实例（不存在 / 排空 / 歧义拒绝、配置不符参数错、
 * 不回落按地图挑）；只带地图时排除源场景、按「人数 + 预占」挑并写预占；参数错不读目录；基础设施异常原样上抛。
 */
class SwitchTargetSelectorTest {

    private static final int ZONE = 1;
    private static final long PLAYER = 42;
    /** 世界地图：1（默认）、2；3 是副本类配置，不在 World 表里。 */
    private static final WorldSceneConfigs WORLD = new WorldSceneConfigs(1, Set.of(1, 2));
    private static final Duration TTL = Duration.ofSeconds(10);

    private final List<SceneNodeInfo> nodes = new ArrayList<>();
    private int directoryReads;
    private RuntimeException directoryFailure;
    private final SceneNodeSource source = zone -> {
        directoryReads++;
        if (directoryFailure != null) {
            throw directoryFailure;
        }
        return List.copyOf(nodes);
    };
    private final FakeWorldChannelStore store = new FakeWorldChannelStore();
    private final SwitchTargetSelector selector =
            new SwitchTargetSelector(source, WORLD, new ChannelSelector(source, store, TTL));

    // ---------- 显式场景号 ----------

    @Test
    void 显式场景号在别的节点上_回该节点并给它记预占() {
        nodes.add(node(1, scene(101, 1, 0)));
        nodes.add(node(2, scene(201, 2, 80)));

        Selection s = explicit(201, 0);

        assertChosen(s, 2, 201, 2);
        assertThat(store.reservationCount(ZONE, 201)).isEqualTo(1);
    }

    @Test
    void 显式场景号带了相符的地图_照常选中() {
        nodes.add(node(2, scene(201, 2, 0)));

        assertChosen(explicit(201, 2), 2, 201, 2);
    }

    @Test
    void 显式场景号不在目录里_回无场景且不预占() {
        nodes.add(node(2, scene(201, 2, 0)));

        Selection s = explicit(999, 0);

        assertRejected(s, Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
        assertThat(store.reservationCount(ZONE, 999)).isZero();
    }

    @Test
    void 显式场景号找不到时_不回落按地图挑() {
        nodes.add(node(2, scene(201, 2, 0)));

        assertRejected(explicit(999, 2), Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
        assertThat(store.reservationCount(ZONE, 201)).isZero();
    }

    @Test
    void 显式场景号在排空中_回无场景并计排空() {
        nodes.add(node(2, draining(201, 2, 0), scene(202, 2, 0)));

        Selection s = explicit(201, 2);

        assertRejected(s, Result.DRAINING, SceneAssigner.TIP_NO_SCENE);
        assertThat(store.reservationCount(ZONE, 201) + store.reservationCount(ZONE, 202)).isZero();
    }

    @Test
    void 显式场景号与期望地图不符_回参数错() {
        nodes.add(node(2, scene(201, 2, 0)));

        Selection s = explicit(201, 1);

        assertRejected(s, Result.BAD_REQUEST, SceneAssigner.TIP_BAD_REQUEST);
        assertThat(s.response().getTipId()).isEqualTo(3005);
        assertThat(store.reservationCount(ZONE, 201)).isZero();
    }

    @Test
    void 显式场景号所在节点条目不完整或串zone_当作不存在() {
        nodes.add(node(2, scene(201, 1, 0)).toBuilder().setLinkHost("").build());
        nodes.add(node(3, scene(301, 1, 0)).toBuilder().setZoneId(2).build());

        assertRejected(explicit(201, 0), Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
        assertRejected(explicit(301, 0), Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
    }

    @Test
    void 同一场景号出现在两个节点上_身份歧义拒绝而不是任选其一() {
        nodes.add(node(2, scene(201, 1, 0)));
        nodes.add(node(3, scene(201, 1, 0)));

        assertRejected(explicit(201, 0), Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
        assertThat(store.reservationCount(ZONE, 201)).isZero();
    }

    @Test
    void 显式场景号可以是副本类配置_只要在目录里() {
        nodes.add(node(2, scene(301, 3, 0)));

        assertChosen(explicit(301, 3), 2, 301, 3);
    }

    @Test
    void 场景号按无符号原样匹配() {
        long huge = 0x8000_0000_0000_0001L;
        nodes.add(node(2, scene(huge, 1, 0)));

        assertChosen(explicit(huge, 1), 2, huge, 1);
    }

    // ---------- 只带地图 ----------

    @Test
    void 只带地图时_排除源场景即使它人最少() {
        nodes.add(node(1, scene(101, 1, 0)));
        nodes.add(node(2, scene(201, 1, 30)));

        Selection s = byMap(1, 1, 101);

        assertChosen(s, 2, 201, 1);
        assertThat(store.reservationCount(ZONE, 201)).isEqualTo(1);
        assertThat(store.reservationCount(ZONE, 101)).isZero();
    }

    @Test
    void 只带地图时_按目录人数加预占挑() {
        nodes.add(node(1, scene(101, 2, 5)));
        nodes.add(node(2, scene(201, 2, 0)));
        for (long p = 100; p < 106; p++) {
            store.reserveScene(ZONE, 201, p, TTL);
        }

        assertChosen(byMap(2, 3, 301), 1, 101, 2); // 5 < 0 + 6
        assertThat(store.reservationCount(ZONE, 101)).isEqualTo(1);
    }

    @Test
    void 只带地图时_并列取节点号小的再取场景号小的() {
        nodes.add(node(9, scene(901, 2, 3)));
        nodes.add(node(4, scene(405, 2, 3), scene(402, 2, 3)));

        assertChosen(byMap(2, 1, 101), 4, 402, 2);
    }

    @Test
    void 只带地图时_排空中的频道不选() {
        nodes.add(node(1, draining(101, 2, 0)));
        nodes.add(node(2, scene(201, 2, 40)));

        assertChosen(byMap(2, 3, 301), 2, 201, 2);
    }

    @Test
    void 只带地图时_可以选中源节点上的另一个频道() {
        nodes.add(node(1, scene(101, 1, 0), scene(102, 1, 1)));
        nodes.add(node(2, scene(201, 1, 9)));

        assertChosen(byMap(1, 1, 101), 1, 102, 1);
    }

    @Test
    void 只带地图时_唯一的频道就是源场景_回无场景() {
        nodes.add(node(1, scene(101, 1, 0)));
        nodes.add(node(2, draining(201, 1, 0)));

        assertRejected(byMap(1, 1, 101), Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
    }

    @Test
    void 只带地图时_目录里没有这张图_回无场景() {
        nodes.add(node(1, scene(101, 1, 0)));

        assertRejected(byMap(2, 1, 101), Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
    }

    @Test
    void 只带地图且不是世界地图_回无场景且不读目录() {
        nodes.add(node(2, scene(301, 3, 0)));

        assertRejected(byMap(3, 1, 101), Result.NOT_FOUND, SceneAssigner.TIP_NO_SCENE);
        assertThat(directoryReads).isZero();
        assertThat(store.reservationCount(ZONE, 301)).isZero();
    }

    @Test
    void 预占关闭时只按目录人数选_不碰预占存储() {
        nodes.add(node(1, scene(101, 1, 0)));
        nodes.add(node(2, scene(201, 1, 3)));
        nodes.add(node(3, scene(301, 1, 7)));
        store.reserveFailure = new IllegalStateException("不该被调用");
        SwitchTargetSelector plain = new SwitchTargetSelector(source, WORLD, ChannelSelector.withoutReservations(source));

        assertChosen(plain.select(request(0, 1, 1, 101)), 2, 201, 1);
        assertChosen(plain.select(request(301, 0, 1, 101)), 3, 301, 1);
    }

    // ---------- 参数错 ----------

    @Test
    void zone为0时_回参数错且不读目录() {
        Selection s = selector.select(request(201, 0, 1, 101).toBuilder().setZoneId(0).build());

        assertRejected(s, Result.BAD_REQUEST, SceneAssigner.TIP_BAD_REQUEST);
        assertThat(directoryReads).isZero();
    }

    @Test
    void player为0时_回参数错且不读目录() {
        Selection s = selector.select(request(0, 1, 1, 101).toBuilder().setPlayerId(0).build());

        assertRejected(s, Result.BAD_REQUEST, SceneAssigner.TIP_BAD_REQUEST);
        assertThat(directoryReads).isZero();
    }

    @Test
    void 期望场景号与地图都为0时_回参数错且不读目录() {
        assertRejected(selector.select(request(0, 0, 1, 101)), Result.BAD_REQUEST, SceneAssigner.TIP_BAD_REQUEST);
        assertThat(directoryReads).isZero();
    }

    // ---------- 基础设施异常 ----------

    @Test
    void 目录读失败时_异常原样抛出而不是伪装成无场景() {
        directoryFailure = new IllegalStateException("redis down");

        assertThatThrownBy(() -> explicit(201, 0)).isInstanceOf(IllegalStateException.class).hasMessage("redis down");
        assertThatThrownBy(() -> byMap(1, 1, 101)).isInstanceOf(IllegalStateException.class).hasMessage("redis down");
    }

    @Test
    void 预占失败时_异常原样抛出() {
        nodes.add(node(1, scene(101, 1, 0)));
        nodes.add(node(2, scene(201, 1, 0)));
        store.reserveFailure = new IllegalStateException("NOSCRIPT");

        assertThatThrownBy(() -> explicit(201, 0)).isInstanceOf(IllegalStateException.class).hasMessage("NOSCRIPT");
        assertThatThrownBy(() -> byMap(1, 1, 101)).isInstanceOf(IllegalStateException.class).hasMessage("NOSCRIPT");
    }

    @Test
    void 按请求的zone读目录() {
        List<Integer> zones = new ArrayList<>();
        SwitchTargetSelector recording = new SwitchTargetSelector(zone -> {
            zones.add(zone);
            return List.of();
        }, WORLD, ChannelSelector.withoutReservations(zone -> List.of()));

        recording.select(request(0, 1, 1, 101).toBuilder().setZoneId(7).build());

        assertThat(zones).containsExactly(7);
    }

    // ---------- helpers ----------

    private Selection explicit(long wantSceneId, int wantConfigId) {
        return selector.select(request(wantSceneId, wantConfigId, 1, 101));
    }

    private Selection byMap(int wantConfigId, int fromNodeId, long fromSceneId) {
        return selector.select(request(0, wantConfigId, fromNodeId, fromSceneId));
    }

    private static SelectSwitchTargetRequest request(long wantSceneId, int wantConfigId, int fromNodeId, long fromSceneId) {
        return SelectSwitchTargetRequest.newBuilder()
                .setZoneId(ZONE)
                .setPlayerId(PLAYER)
                .setFromSceneNodeId(fromNodeId)
                .setFromSceneId(fromSceneId)
                .setWantSceneId(wantSceneId)
                .setWantSceneConfigId(wantConfigId)
                .build();
    }

    private static void assertChosen(Selection s, int nodeId, long sceneId, int configId) {
        assertThat(s.result()).isEqualTo(Result.OK);
        assertThat(s.response().getTipId()).isZero();
        assertThat(s.response().getSceneNodeId()).isEqualTo(nodeId);
        assertThat(s.response().getSceneId()).isEqualTo(sceneId);
        assertThat(s.response().getSceneConfigId()).isEqualTo(configId);
    }

    private static void assertRejected(Selection s, Result result, int tipId) {
        assertThat(s.result()).isEqualTo(result);
        assertThat(s.response().getTipId()).isEqualTo(tipId);
        assertThat(s.response().getSceneNodeId()).isZero();
        assertThat(s.response().getSceneId()).isZero();
        assertThat(s.response().getSceneConfigId()).isZero();
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

    private static SceneEntry draining(long sceneId, int configId, int playerCount) {
        return scene(sceneId, configId, playerCount).toBuilder().setDraining(true).build();
    }
}
