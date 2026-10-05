package com.game.scene.world;

import static com.game.scene.world.ChannelPlanApplyTest.active;
import static com.game.scene.world.ChannelPlanApplyTest.draining;
import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.SceneEntry;
import com.game.proto.SceneInfoComp;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.team.TeamFollow;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import com.game.table.SceneErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 镜像 / 副本实例与 5.1 主世界频道逻辑的隔离（批次 5.3，dungeon-mirror-spec §9.2 R2–R4、§12.2 第 7 条「5.1 回归」）：
 * 目录条目带种类与源；应用计划不把实例当孤儿排空、计划里与实例同号的记录被拒；排空改派 / 进场重定向 / 只带地图的 63 只在主世界频道里选，
 * 身在实例里只带当前地图必须离开实例；{@code xm.scene.channels} 只计主世界频道。
 * 实例经低层登记入口 {@link SceneWorld#addScene(SceneInfoComp, SceneKind, long)} 放进来（建实例的业务入口与它的校验另测）。
 * 配表是假的两张世界地图（1 = 默认大世界，2）；副本地图用 17（不是世界地图）。
 */
class InstanceChannelIsolationTest {

    /** scene_id ≥ 2^63：一律按无符号处理。 */
    private static final long A = 0x8000_0000_0000_0B01L;
    private static final long B = 0x8000_0000_0000_0B02L;
    private static final long C = 0x8000_0000_0000_0B03L;
    private static final long MIRROR = 0x8000_0000_0000_0B11L;
    private static final long DUNGEON = 0x8000_0000_0000_0B21L;
    private static final int DUNGEON_MAP = 17;
    private static final int ENTER_FAILED = SceneErrorTip.scene_error.kEnterSceneFailed_VALUE;

    private RecordingSink sink;
    private FakePlayerRepository repo;
    private SimpleMeterRegistry meters;
    private SceneWorld world;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        repo = new FakePlayerRepository();
        meters = new SimpleMeterRegistry();
        world = newWorld(CrossNodeSwitch.DISABLED);
    }

    private SceneWorld newWorld(CrossNodeSwitch crossNode) {
        return new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, new AtomicLong(1000)::incrementAndGet,
                new ManualClock(), new SceneMetrics(meters), PlayerInitializer.NONE, PlayerSnapshots.NONE,
                PlayerLocations.NONE, TeamFollow.NONE, crossNode);
    }

    /** 启用跨节点换图（选目标不会被调到：这里只看本地解析出的去向）。 */
    private static CrossNodeSwitch remoteEnabled() {
        return new CrossNodeSwitch(3, (player, from, wantId, wantConf, done) -> {
            throw new AssertionError("只解析去向，不选目标");
        }, owned -> { }, Duration.ofSeconds(4), Duration.ofSeconds(30));
    }

    private Scene mirror(SceneWorld w, long sceneId, int conf, long source, long creator) {
        return w.addScene(SceneInfoComp.newBuilder().setSceneConfigId(conf).setSceneId(sceneId).setMirrorConfigId(1)
                .putCreators(creator, true).build(), SceneKind.MIRROR, source);
    }

    private Scene dungeon(SceneWorld w, long sceneId) {
        return w.addScene(SceneInfoComp.newBuilder().setSceneConfigId(DUNGEON_MAP).setSceneId(sceneId).setDungeonConfigId(1)
                .build(), SceneKind.DUNGEON, 0);
    }

    private void enter(long playerId, long sceneId) {
        repo.putNewPlayer(playerId, 1);
        world.onPlayerEnter(1, enterFrame((int) playerId, playerId, sceneId, 1));
        repo.completeAll();
        assertThat(world.playerById(playerId).scene().sceneId()).isEqualTo(sceneId);
    }

    private double channels(String state) {
        return meters.get("xm.scene.channels").tag("state", state).gauge().value();
    }

    // ------------------------------------------------------------------ 目录与种类

    @Test
    void 目录条目带种类与镜像源_频道为WORLD_副本没有源() {
        world.applyChannelPlan(1, List.of(active(A, 1)));
        mirror(world, MIRROR, 1, A, 42);
        dungeon(world, DUNGEON);

        assertThat(world.sceneEntries()).containsExactly(
                SceneEntry.newBuilder().setSceneId(A).setSceneConfigId(1).setKind(ChannelKind.CHANNEL_KIND_WORLD).build(),
                SceneEntry.newBuilder().setSceneId(MIRROR).setSceneConfigId(1).setKind(ChannelKind.CHANNEL_KIND_MIRROR)
                        .setSourceSceneId(A).build(),
                SceneEntry.newBuilder().setSceneId(DUNGEON).setSceneConfigId(DUNGEON_MAP)
                        .setKind(ChannelKind.CHANNEL_KIND_DUNGEON).build());
        Scene m = world.sceneById(MIRROR);
        assertThat(m.kind()).isEqualTo(SceneKind.MIRROR);
        assertThat(m.isWorldChannel()).isFalse();
        assertThat(m.sourceSceneId()).isEqualTo(A);
        assertThat(m.info().getCreatorsMap()).containsExactly(java.util.Map.entry(42L, true));
        assertThat(world.sceneById(A).isWorldChannel()).isTrue();
    }

    @Test
    void 种类与契约取值互转_UNSPECIFIED当WORLD_不认识的为空() {
        for (SceneKind kind : SceneKind.values()) {
            assertThat(SceneKind.of(kind.channelKind())).contains(kind);
        }
        assertThat(SceneKind.of(ChannelKind.CHANNEL_KIND_UNSPECIFIED)).contains(SceneKind.WORLD);
        assertThat(SceneKind.of(ChannelKind.UNRECOGNIZED)).isEmpty();
        assertThat(SceneKind.WORLD.isInstance()).isFalse();
        assertThat(SceneKind.MIRROR.isInstance()).isTrue();
        assertThat(SceneKind.DUNGEON.isInstance()).isTrue();
    }

    @Test
    void 低层登记拒绝本地重号与种类和源不配() {
        world.applyChannelPlan(1, List.of(active(A, 1)));
        mirror(world, MIRROR, 1, A, 42);

        assertThatThrownBy(() -> mirror(world, MIRROR, 1, A, 43)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> mirror(world, A, 1, A, 43)).as("与频道同号").isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> mirror(world, C, 1, 0, 43)).as("镜像缺源").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> world.addScene(SceneInfoComp.newBuilder().setSceneConfigId(DUNGEON_MAP).setSceneId(C).build(),
                SceneKind.DUNGEON, A)).as("副本带源").isInstanceOf(IllegalArgumentException.class);
        assertThat(world.sceneById(MIRROR).info().getCreatorsMap()).containsOnlyKeys(42L);
        assertThat(world.sceneById(C)).isNull();
    }

    // ------------------------------------------------------------------ R2：应用计划

    @Test
    void 应用计划不把实例当孤儿排空_R2() {
        world.applyChannelPlan(1, List.of(active(A, 1), active(B, 1)));
        mirror(world, MIRROR, 1, A, 42);
        dungeon(world, DUNGEON);

        world.applyChannelPlan(2, List.of(active(A, 1), active(B, 1)));
        world.applyChannelPlan(3, List.of(active(A, 1)));   // B 计划外 → 排空（频道照旧）

        assertThat(world.sceneById(MIRROR).draining()).isFalse();
        assertThat(world.sceneById(DUNGEON).draining()).isFalse();
        assertThat(world.sceneById(B)).as("计划外的空频道照样排空并销毁").isNull();
        assertThat(world.sceneEntries()).extracting(SceneEntry::getSceneId).containsExactly(A, MIRROR, DUNGEON);
    }

    @Test
    void 计划里出现与本地实例同号的记录_两种状态都拒绝_不动实例() {
        world.applyChannelPlan(1, List.of(active(A, 1)));
        Scene m = mirror(world, MIRROR, 1, A, 42);

        world.applyChannelPlan(2, List.of(active(A, 1), active(MIRROR, 1), draining(DUNGEON, 1)));
        dungeon(world, DUNGEON);
        world.applyChannelPlan(3, List.of(active(A, 1), draining(MIRROR, 1), active(DUNGEON, 1)));

        assertThat(world.sceneById(MIRROR)).isSameAs(m);
        assertThat(m.draining()).isFalse();
        assertThat(m.kind()).isEqualTo(SceneKind.MIRROR);
        assertThat(world.sceneById(DUNGEON).draining()).isFalse();
        assertThat(meters.get("xm.scene.channel.plan.applies").tag("result", "rejected").counter().count())
                .as("v2 的 MIRROR、v3 的 MIRROR 与 DUNGEON（v2 时本地还没有副本，DRAINING 记录照常忽略）").isEqualTo(3);
    }

    // ------------------------------------------------------------------ R3：本地选频道

    @Test
    void 排空改派只落主世界频道_同图镜像人更少也不选_R3() {
        world.applyChannelPlan(1, List.of(active(A, 1), active(B, 1)));
        mirror(world, MIRROR, 1, B, 42);   // 0 人，比 B 闲
        enter(1001, A);
        enter(1002, B);
        enter(1003, B);

        world.applyChannelPlan(2, List.of(draining(A, 1), active(B, 1)));

        assertThat(world.playerById(1001).scene().sceneId()).isEqualTo(B);
        assertThat(world.sceneById(MIRROR).playerCount()).isZero();
        assertThat(world.sceneById(A)).isNull();
    }

    @Test
    void 进场目标在排空时重定向只落主世界频道_R3() {
        world.applyChannelPlan(1, List.of(active(A, 1), active(B, 1)));
        mirror(world, MIRROR, 1, B, 42);
        enter(1002, B);
        repo.putNewPlayer(1001, 1);
        world.onPlayerEnter(1, enterFrame(11, 1001, A, 1));   // 分配时 A 还承载中
        world.applyChannelPlan(2, List.of(draining(A, 1), active(B, 1)));   // 加载回来前 A 转排空

        repo.completeAll();

        assertThat(world.playerById(1001).scene().sceneId()).isEqualTo(B);
    }

    @Test
    void 只有镜像的源图上排空改派不进镜像_回落默认大世界() {
        world.applyChannelPlan(1, List.of(active(A, 2), active(C, 1)));
        mirror(world, MIRROR, 2, A, 42);   // 图 2 只剩源频道 A 与它的镜像
        enter(1001, A);

        world.applyChannelPlan(2, List.of(draining(A, 2), active(C, 1)));

        assertThat(world.playerById(1001).scene().sceneId()).as("默认大世界（图 1）的频道").isEqualTo(C);
    }

    @Test
    void 主世界频道里只带当前地图_镜像不参与比较_并列留原地() {
        world.applyChannelPlan(1, List.of(active(A, 1)));
        mirror(world, MIRROR, 1, A, 42);
        enter(1001, A);

        assertThat(world.resolveSwitchTarget(world.sceneById(A), 0, 1))
                .isEqualTo(new SwitchTarget.Local(world.sceneById(A)));
        assertThat(world.resolveSwitchTarget(world.sceneById(A), 0, 2)).as("图 2 本节点没有频道，跨节点没装配")
                .isEqualTo(new SwitchTarget.Reject(ENTER_FAILED));
    }

    @Test
    void 身在镜像里只带当前地图_必去主世界频道_人数并列也不留在镜像_R3() {
        world.applyChannelPlan(1, List.of(active(A, 1), active(B, 1)));
        Scene m = mirror(world, MIRROR, 1, A, 1001);
        enter(1001, MIRROR);
        enter(1002, A);
        enter(1003, A);
        enter(1004, B);

        SwitchTarget target = world.resolveSwitchTarget(m, 0, 1);

        assertThat(target).isEqualTo(new SwitchTarget.Local(world.sceneById(B)));
    }

    @Test
    void 身在镜像里只带当前地图_本节点该图没有主世界频道_跨节点没装配回3023_装配了走远端() {
        world.applyChannelPlan(1, List.of(active(A, 1)));
        Scene m = mirror(world, MIRROR, 1, A, 1001);
        world.applyChannelPlan(2, List.of(draining(A, 1)));   // 源转排空（级联由 5.3 的节点本地逻辑处理，这里只看去向）

        assertThat(world.resolveSwitchTarget(m, 0, 1)).isEqualTo(new SwitchTarget.Reject(ENTER_FAILED));

        SceneWorld remote = newWorld(remoteEnabled());
        Scene m2 = mirror(remote, MIRROR, 1, 0x8000_0000_0000_0BFFL, 1001);
        assertThat(remote.resolveSwitchTarget(m2, 0, 1)).isEqualTo(new SwitchTarget.Remote());
    }

    @Test
    void 身在副本里只带副本地图_不留在副本_副本地图不是世界地图直接拒绝() {
        world.applyChannelPlan(1, List.of(active(A, 1)));
        Scene d = dungeon(world, DUNGEON);
        enter(1001, DUNGEON);

        assertThat(world.resolveSwitchTarget(d, 0, DUNGEON_MAP)).isEqualTo(new SwitchTarget.Reject(ENTER_FAILED));
        SceneWorld remote = newWorld(remoteEnabled());
        Scene d2 = dungeon(remote, DUNGEON);
        assertThat(remote.resolveSwitchTarget(d2, 0, DUNGEON_MAP)).as("不为非世界地图跨节点选频道")
                .isEqualTo(new SwitchTarget.Reject(ENTER_FAILED));
        assertThat(world.resolveSwitchTarget(d, 0, 1)).as("只带世界地图：去该图的频道")
                .isEqualTo(new SwitchTarget.Local(world.sceneById(A)));
    }

    @Test
    void 只带别的地图不选同图镜像_显式号可以进本地实例() {
        world.applyChannelPlan(1, List.of(active(A, 1), active(B, 2)));
        Scene m = mirror(world, MIRROR, 2, B, 42);
        enter(1002, B);
        enter(1001, A);

        assertThat(world.resolveSwitchTarget(world.sceneById(A), 0, 2))
                .isEqualTo(new SwitchTarget.Local(world.sceneById(B)));
        assertThat(world.resolveSwitchTarget(world.sceneById(A), MIRROR, 0)).as("按号加入镜像，同基线不查 creators")
                .isEqualTo(new SwitchTarget.Local(m));
    }

    // ------------------------------------------------------------------ R4：频道指标

    @Test
    void 频道指标只计主世界频道_R4() {
        world.applyChannelPlan(1, List.of(active(A, 1), active(B, 1)));
        mirror(world, MIRROR, 1, A, 42);
        dungeon(world, DUNGEON);

        assertThat(channels("active")).isEqualTo(2);
        assertThat(channels("draining")).isZero();

        enter(1001, B);
        world.applyChannelPlan(2, List.of(active(A, 1), draining(B, 1)));
        assertThat(channels("active")).isEqualTo(1);
        assertThat(channels("draining")).as("B 的人改派到 A 后 B 销毁").isZero();
    }
}
