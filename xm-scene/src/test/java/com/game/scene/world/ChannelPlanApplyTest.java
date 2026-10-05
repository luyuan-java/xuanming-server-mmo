package com.game.scene.world;

import static com.game.scene.world.SceneWorldTest.enterFrame;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.SceneEntry;
import com.game.api.proto.WorldChannel;
import com.game.proto.EnterSceneS2C;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.testing.Contracts;
import com.game.scene.testing.FakePlayerRepository;
import com.game.scene.testing.FakeSceneTables;
import com.game.scene.testing.ManualClock;
import com.game.scene.testing.RecordingSink;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 场景节点按频道计划建 / 标排空 / 改回承载中（批次 5.1，scene-channels-spec §4.10.2、§9.4 ChannelPlanApplyTest）：
 * 带号建场景幂等；拒 0 号、非世界图、同号异图、未知种类与状态；同版本重投忽略；排空中改回承载中；计划外本地场景按排空处理；
 * 节点重启后按计划同号重建。全部在本线程上直接调（等同逻辑线程），配表是假的两张图（1 = 默认大世界，2）。
 */
class ChannelPlanApplyTest {

    static final int NODE = 3;
    /** scene_id ≥ 2^63：一律按无符号处理。 */
    static final long A = 0x8000_0000_0000_0A01L;
    static final long B = 0x8000_0000_0000_0A02L;
    static final long C = 0x8000_0000_0000_0A03L;

    private RecordingSink sink;
    private FakePlayerRepository repo;
    private SimpleMeterRegistry meters;
    private SceneWorld world;

    @BeforeEach
    void setUp() {
        sink = new RecordingSink();
        repo = new FakePlayerRepository();
        meters = new SimpleMeterRegistry();
        world = newWorld();
    }

    private SceneWorld newWorld() {
        return new SceneWorld(new FakeSceneTables(), Contracts.IDS, sink, repo, new AtomicLong(1000)::incrementAndGet,
                new ManualClock(), new SceneMetrics(meters));
    }

    static WorldChannel active(long sceneId, int configId) {
        return WorldChannel.newBuilder().setSceneId(sceneId).setSceneConfigId(configId).setNodeId(NODE)
                .setState(ChannelState.CHANNEL_ACTIVE).setKind(ChannelKind.CHANNEL_KIND_WORLD).build();
    }

    static WorldChannel draining(long sceneId, int configId) {
        return active(sceneId, configId).toBuilder().setState(ChannelState.CHANNEL_DRAINING)
                .setDrainReason(DrainReason.DRAIN_SCALE_IN).build();
    }

    @Test
    void ACTIVE记录按计划的scene_id建_同版本重投忽略_新版本按scene_id幂等() {
        assertThat(world.applyChannelPlan(5, List.of(active(A, 1), active(B, 1), active(C, 2)))).isTrue();

        assertThat(world.sceneEntries()).containsExactly(entry(A, 1, false), entry(B, 1, false), entry(C, 2, false));
        assertThat(world.appliedPlanVersion()).isEqualTo(5);
        Scene a = world.sceneById(A);
        assertThat(a.info().getSceneId()).isEqualTo(A);
        assertThat(a.info().getSceneConfigId()).isEqualTo(1);
        assertThat(a.info().getMirrorConfigId()).isZero();
        assertThat(a.info().getCreatorsCount()).isZero();

        assertThat(world.applyChannelPlan(5, List.of())).as("同版本重投：什么也不做（不会把场景当孤儿）").isFalse();
        assertThat(world.sceneEntries()).hasSize(3);

        assertThat(world.applyChannelPlan(6, List.of(active(A, 1), active(B, 1), active(C, 2)))).isTrue();
        assertThat(world.sceneById(A)).as("按 scene_id 幂等：同一个实例").isSameAs(a);
        assertThat(world.appliedPlanVersion()).isEqualTo(6);
        assertThat(counter("xm.scene.channel.plan.applies", "applied")).isEqualTo(2);
        assertThat(gauge("active")).isEqualTo(3);
        assertThat(gauge("draining")).isZero();
    }

    @Test
    void 进场的79带计划发的scene_id() throws Exception {
        world.applyChannelPlan(1, List.of(active(A, 1)));
        repo.putNewPlayer(1001, 1);

        world.onPlayerEnter(1, enterFrame(11, 1001, A, 1));
        repo.completeAll();

        EnterSceneS2C enter = EnterSceneS2C.parseFrom(sink.to(1, 11).get(0).getSerializedMessage());
        assertThat(enter.getSceneInfo().getSceneId()).isEqualTo(A);
        assertThat(enter.getSceneInfo().getSceneConfigId()).isEqualTo(1);
    }

    @Test
    void 拒绝零号_非世界图_同号异图_非主世界种类_未知状态_计rejected_不动本地() {
        world.applyChannelPlan(1, List.of(active(A, 1)));
        Scene a = world.sceneById(A);

        world.applyChannelPlan(2, List.of(
                active(0, 1),                                   // scene_id 0（补基线 gRPC 入口缺的零号检查，B12）
                active(B, 0),                                   // conf 0
                active(C, 9),                                   // 不是本节点 World 表里的图
                active(A, 2),                                   // 本地同号异图
                active(0x8000_0000_0000_0A05L, 1).toBuilder().setKindValue(7).build(),   // 5.3 之后的别的种类
                active(0x8000_0000_0000_0A06L, 1).toBuilder().setState(ChannelState.CHANNEL_STATE_UNSPECIFIED).build()));

        assertThat(world.sceneEntries()).as("一个也没建；被拒的同号记录不算「计划里没有」，本地不动")
                .containsExactly(entry(A, 1, false));
        assertThat(world.sceneById(A)).isSameAs(a);
        assertThat(counter("xm.scene.channel.plan.applies", "rejected")).isEqualTo(6);
        assertThat(counter("xm.scene.channel.plan.applies", "applied")).isEqualTo(2);
        assertThat(world.appliedPlanVersion()).as("有拒绝也记版本：节点已看到这次改写").isEqualTo(2);
    }

    @Test
    void 种类缺省按主世界收() {
        world.applyChannelPlan(1, List.of(active(A, 1).toBuilder().clearKind().build()));

        assertThat(world.sceneEntries()).containsExactly(entry(A, 1, false));
    }

    @Test
    void DRAINING记录_本地有就标排空_本地没有不建_孤儿图也照样排空() {
        world.applyChannelPlan(1, List.of(active(A, 1), active(B, 1)));
        repo.putNewPlayer(1001, 1);
        world.onPlayerEnter(1, enterFrame(11, 1001, A, 1));
        repo.completeAll();
        Scene a = world.sceneById(A);
        Scene b = world.sceneById(B);

        // 只有 A 有人：B 标排空后空着、随即销毁；A 的人被改派走后也销毁。C（图 9，本节点表里没有）只是 DRAINING 记录，本地没有，不建
        world.applyChannelPlan(2, List.of(draining(A, 1), active(0x8000_0000_0000_0A07L, 1), draining(C, 9)));

        assertThat(world.sceneById(B)).as("空的排空频道应用后立即销毁").isNull();
        assertThat(world.sceneById(A)).as("A 的人改派到新频道后 A 也空了").isNull();
        assertThat(world.sceneById(C)).isNull();
        assertThat(world.playerById(1001).scene().sceneId()).isEqualTo(0x8000_0000_0000_0A07L);
        assertThat(a.draining()).isTrue();
        assertThat(b.draining()).isTrue();
    }

    @Test
    void 排空中有人且没有改派目标时留着_目录带draining_计划改回ACTIVE就回滚为承载中() {
        world.applyChannelPlan(1, List.of(active(A, 1)));
        repo.putNewPlayer(1001, 1);
        world.onPlayerEnter(1, enterFrame(11, 1001, A, 1));
        repo.completeAll();

        world.applyChannelPlan(2, List.of(draining(A, 1)));
        assertThat(world.sceneEntries()).as("同图没有兄弟、默认大世界就是这张图：原地不动，目录报排空")
                .containsExactly(entry(A, 1, true).toBuilder().setPlayerCount(1).build());
        assertThat(gauge("active")).isZero();
        assertThat(gauge("draining")).isEqualTo(1);

        world.applyChannelPlan(3, List.of(active(A, 1)));   // 缩容排空超时回滚（D10）
        assertThat(world.sceneEntries()).containsExactly(entry(A, 1, false).toBuilder().setPlayerCount(1).build());
        assertThat(world.playerById(1001).scene().sceneId()).isEqualTo(A);
        assertThat(gauge("active")).isEqualTo(1);
        assertThat(gauge("draining")).isZero();
    }

    @Test
    void 本地有_计划里没有_按排空处理() {
        Scene local = world.createScene(1);   // 计划外自建（滚动升级前的旧号、或频道已被删）
        repo.putNewPlayer(1001, 1);
        world.onPlayerEnter(1, enterFrame(11, 1001, local.sceneId(), 1));
        repo.completeAll();

        world.applyChannelPlan(1, List.of(active(A, 1)));

        assertThat(local.draining()).isTrue();
        assertThat(world.sceneById(local.sceneId())).as("人改派走后销毁").isNull();
        assertThat(world.playerById(1001).scene().sceneId()).isEqualTo(A);
        assertThat(world.sceneEntries()).containsExactly(entry(A, 1, false).toBuilder().setPlayerCount(1).build());
    }

    @Test
    void 版本号回退_告警后照样按当前计划应用() {
        world.applyChannelPlan(9, List.of(active(A, 1)));

        assertThat(world.applyChannelPlan(3, List.of(active(B, 2)))).isTrue();

        assertThat(world.sceneEntries()).containsExactly(entry(B, 2, false));
        assertThat(world.appliedPlanVersion()).isEqualTo(3);
    }

    @Test
    void 节点重启_按计划同号重建ACTIVE_不建DRAINING() {
        List<WorldChannel> plan = List.of(active(A, 1), draining(B, 1), active(C, 2));
        world.applyChannelPlan(4, plan);
        world.shutdown();

        SceneWorld restarted = newWorld();
        restarted.applyChannelPlan(4, plan);

        assertThat(restarted.sceneEntries()).containsExactly(entry(A, 1, false), entry(C, 2, false));
        assertThat(restarted.appliedPlanVersion()).isEqualTo(4);
    }

    @Test
    void 版本0且没有记录_不当新版本应用() {
        Scene local = world.createScene(1);

        assertThat(world.applyChannelPlan(0, List.of())).as("Redis 里还没有计划：与初始已应用版本相同，什么也不做").isFalse();

        assertThat(local.draining()).isFalse();
    }

    // ------------------------------------------------------------------ 工具

    static SceneEntry entry(long sceneId, int configId, boolean draining) {
        return SceneEntry.newBuilder().setSceneId(sceneId).setSceneConfigId(configId).setDraining(draining).build();
    }

    private double counter(String name, String result) {
        return meters.get(name).tag("result", result).counter().count();
    }

    private double gauge(String state) {
        return meters.get("xm.scene.channels").tag("state", state).gauge().value();
    }
}
