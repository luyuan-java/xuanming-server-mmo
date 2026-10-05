package com.game.scenemanager.world;

import static com.game.scenemanager.world.PlanFixture.ops;
import static com.game.scenemanager.world.PlanFixture.puts;
import static com.game.scenemanager.world.PlanFixture.removes;
import static com.game.scenemanager.world.PlanFixture.scene;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelKind;
import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldPlanOp;
import com.game.scenemanager.world.PlanEvent.AutoscaleAction;
import com.game.scenemanager.world.PlanEvent.AutoscaleOutcome;
import com.game.scenemanager.world.PlanEvent.MigrationOutcome;
import com.game.scenemanager.world.PlanEvent.MigrationReason;
import com.game.scenemanager.world.PlanResult.ChannelCounts;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 规划器 P1–P7（scene-channels-spec §4.6、§9.2 WorldChannelPlannerTest）。对照基线
 * TestInitWorldScenes_{Idempotent_NoDuplicateRedisEntries,MultipleChannels,MultipleChannels_Idempotent,PerConfIdOverride}、
 * TestChannelCountFor_{PerConfIdOverride,ClampsToOne}、TestDesiredWorldChannelCount_RedisWinsOverConfig、
 * TestOrphanCleanup_LeavesValidConfUntouched（mmorpg logic_test.go、world_autoscale_test.go、orphan_cleanup_autoscale_test.go）。
 */
class WorldChannelPlannerTest {

    private final PlanFixture f = new PlanFixture();

    private static Map<Integer, Long> countByConf(List<WorldChannel> channels) {
        return channels.stream().collect(Collectors.groupingBy(WorldChannel::getSceneConfigId, Collectors.counting()));
    }

    // ---------------------------------------------------------------- P5 补建与播种

    @Test
    void 首拍_每图按种子铺一个并播种期望数() {
        f.node(10, 0);

        PlanResult r = f.plan();

        List<WorldChannel> created = puts(r);
        assertThat(created).hasSize(2);
        assertThat(created).extracting(WorldChannel::getSceneConfigId).containsExactlyInAnyOrder(1, 2);
        WorldChannel c = created.get(0);
        assertThat(c.getNodeId()).isEqualTo(10);
        assertThat(c.getSlot()).isZero();
        assertThat(c.getState()).isEqualTo(ChannelState.CHANNEL_ACTIVE);
        assertThat(c.getKind()).isEqualTo(ChannelKind.CHANNEL_KIND_WORLD);
        assertThat(c.getCreatedMs()).isEqualTo(PlanFixture.NOW);
        assertThat(c.getStateSinceMs()).isEqualTo(PlanFixture.NOW);
        assertThat(c.getPlanVersion()).isEqualTo(PlanFixture.NOW);
        assertThat(ops(r, WorldPlanOp.SeedDesired.class))
                .containsExactlyInAnyOrder(new WorldPlanOp.SeedDesired(1, 1), new WorldPlanOp.SeedDesired(2, 1));
        assertThat(r.noLease()).isFalse();
        assertThat(r.noNodes()).isFalse();
        assertThat(r.counts().get(1)).isEqualTo(new ChannelCounts(1, 0, 0));
    }

    @Test
    void 已满足时什么也不写_幂等() {
        f.node(10, 0);
        f.apply(f.plan());
        f.nodeHostingPlan(10);

        PlanResult second = f.plan();

        assertThat(second.batch().isEmpty()).isTrue();
        assertThat(second.channels()).hasSize(2);
    }

    @Test
    void 期望数_Redis合法值优先于配置_播过种后改配置不生效() {
        f.props(PlanFixture.props(Map.of("channel-count", "4"))).desired(1, 7).node(10, 0);

        PlanResult r = f.plan();

        assertThat(countByConf(puts(r))).containsEntry(1, 7L).containsEntry(2, 4L);
        // 图 1 已有字段，不再播种；图 2 首次播种用配置
        assertThat(ops(r, WorldPlanOp.SeedDesired.class)).containsExactly(new WorldPlanOp.SeedDesired(2, 4));
    }

    @Test
    void 按图覆盖种子() {
        f.props(PlanFixture.props(Map.of("channel-count-by-config.[1]", "16"))).node(10, 0);

        PlanResult r = f.plan();

        assertThat(countByConf(puts(r))).containsEntry(1, 16L).containsEntry(2, 1L);
        assertThat(puts(r).stream().filter(c -> c.getSceneConfigId() == 1).map(WorldChannel::getSlot).toList())
                .containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.range(0, 16).boxed().toList());
        assertThat(ops(r, WorldPlanOp.SeedDesired.class)).contains(new WorldPlanOp.SeedDesired(1, 16));
    }

    @Test
    void Redis里的期望数不合法时用种子且不覆盖() {
        f.desired(1, 0).node(10, 0);

        PlanResult r = f.plan();

        assertThat(countByConf(puts(r))).containsEntry(1, 1L);
        assertThat(ops(r, WorldPlanOp.SeedDesired.class)).containsExactly(new WorldPlanOp.SeedDesired(2, 1));
        assertThat(ops(r, WorldPlanOp.SetDesired.class)).isEmpty();
    }

    @Test
    void 补建只增不减_多出期望数的频道不删() {
        f.active(101, 1, 10, 0).active(102, 1, 10, 1).active(103, 1, 10, 2).active(201, 2, 10, 0)
                .desired(1, 1).desired(2, 1).nodeHostingPlan(10);

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
    }

    @Test
    void slot取该图ACTIVE记录未用的最小值() {
        f.active(101, 1, 10, 0).active(103, 1, 10, 2).active(201, 2, 10, 0).desired(1, 3).desired(2, 1)
                .nodeHostingPlan(10);

        List<WorldChannel> created = f.created(f.plan());

        assertThat(created).singleElement().satisfies(c -> {
            assertThat(c.getSceneConfigId()).isEqualTo(1);
            assertThat(c.getSlot()).isEqualTo(1);
        });
    }

    @Test
    void 排空中的记录不占ACTIVE数也不占slot() {
        f.active(101, 1, 10, 1).draining(102, 1, 10, 0, DrainReason.DRAIN_SCALE_IN, 0, PlanFixture.NOW)
                .active(201, 2, 10, 0).desired(1, 2).desired(2, 1)
                .node(10, 0, scene(101, 1, 0), scene(102, 1, 3), scene(201, 2, 0));

        List<WorldChannel> created = f.created(f.plan());

        assertThat(created).singleElement().satisfies(c -> assertThat(c.getSlot()).isZero());
    }

    // ---------------------------------------------------------------- 放置

    @Test
    void per_node_新节点加入时每张图在它上面补一个() {
        f.active(101, 1, 10, 0).active(201, 2, 10, 0).desired(1, 1).desired(2, 1).nodeHostingPlan(10).node(20, 0);

        List<WorldChannel> created = f.created(f.plan());

        assertThat(created).hasSize(2).allSatisfy(c -> {
            assertThat(c.getNodeId()).isEqualTo(20);
            assertThat(c.getSlot()).isEqualTo(1);
        });
    }

    @Test
    void per_node_补足时放在该图ACTIVE最少的节点_节点号按数值排() {
        // 9 与 10：字典序 "10" < "9"，数值序 9 < 10
        f.confs(1).desired(1, 5).node(10, 0).node(9, 0);

        List<WorldChannel> created = f.created(f.plan());

        assertThat(created).extracting(WorldChannel::getNodeId).containsExactly(9, 10, 9, 10, 9);
        assertThat(created).extracting(WorldChannel::getSlot).containsExactly(0, 1, 2, 3, 4);
    }

    @Test
    void per_node_覆盖可以让总数超过期望数() {
        f.confs(1).desired(1, 1).node(10, 0).node(20, 0).node(30, 0);

        assertThat(f.created(f.plan())).extracting(WorldChannel::getNodeId).containsExactly(10, 20, 30);
    }

    @Test
    void hash_落点按FNV_conf乘1000加slot无符号取模() {
        f.props(PlanFixture.props(Map.of("coverage", "hash"))).confs(1).desired(1, 3)
                .node(1, 0).node(2, 0).node(3, 0);

        List<WorldChannel> created = f.created(f.plan());

        // 1000 mod 3 = 1、1001 mod 3 = 0、1002 mod 3 = 2（§1.5.5 黄金值）→ 节点 [1,2,3] 的下标
        assertThat(created).extracting(WorldChannel::getSlot).containsExactly(0, 1, 2);
        assertThat(created).extracting(WorldChannel::getNodeId).containsExactly(2, 1, 3);
    }

    @Test
    void hash_不做覆盖() {
        f.props(PlanFixture.props(Map.of("coverage", "hash"))).confs(1).desired(1, 1)
                .node(1, 0).node(2, 0).node(3, 0);

        assertThat(f.created(f.plan())).hasSize(1);
    }

    // ---------------------------------------------------------------- P2 死节点

    @Test
    void 死节点_宽限期内不动且记录计入ACTIVE数() {
        f.confs(1).active(101, 1, 10, 0).active(102, 1, 20, 1).desired(1, 2).nodeHostingPlan(10)
                .absent(20, Duration.ofSeconds(19));

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.nodeGonePending()).isEqualTo(1);
    }

    @Test
    void 死节点_满宽限期删记录并在活节点用新号重铺() {
        f.confs(1).active(101, 1, 10, 0).active(102, 1, 20, 1).desired(1, 2).nodeHostingPlan(10)
                .absent(20, Duration.ofSeconds(20));

        PlanResult r = f.plan();

        assertThat(removes(r)).containsExactly(102L);
        assertThat(f.created(r)).singleElement().satisfies(c -> {
            assertThat(c.getNodeId()).isEqualTo(10);
            assertThat(c.getSceneId()).isEqualTo(1000);
            assertThat(c.getSlot()).isEqualTo(1);
        });
        assertThat(r.events()).contains(
                new PlanEvent.Migration(MigrationReason.NODE_GONE, MigrationOutcome.PLANNED, 1),
                new PlanEvent.Migration(MigrationReason.NODE_GONE, MigrationOutcome.DONE, 1));
        assertThat(r.removed()).extracting(WorldChannel::getSceneId).containsExactly(102L);
    }

    @Test
    void 死节点_任何状态的记录都删() {
        f.confs(1).active(101, 1, 10, 0).draining(102, 1, 20, 1, DrainReason.DRAIN_SCALE_IN, 0, PlanFixture.NOW)
                .desired(1, 1).nodeHostingPlan(10).absent(20, Duration.ofMinutes(5));

        PlanResult r = f.plan();

        assertThat(removes(r)).containsExactly(102L);
        assertThat(r.events()).containsExactly(
                new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.DRAINED, 1));
    }

    @Test
    void zone里没有活节点_宽限期后删光记录_期望数保留() {
        f.confs(1).active(101, 1, 10, 0).desired(1, 3).absent(10, Duration.ofMinutes(1));

        PlanResult r = f.plan();

        assertThat(removes(r)).containsExactly(101L);
        assertThat(ops(r, WorldPlanOp.RemoveDesired.class)).isEmpty();
        assertThat(r.noNodes()).isTrue();
    }

    // ---------------------------------------------------------------- P1 孤儿

    @Test
    void 孤儿conf_ACTIVE转排空_期望数与冷却字段删除_有效conf不动() {
        f.active(101, 1, 10, 0).active(201, 2, 10, 0).active(901, 99, 10, 0)
                .desired(1, 1).desired(2, 1).desired(99, 4);
        f.cooldown.put(99, PlanFixture.NOW + 1000);
        f.nodeHostingPlan(10);

        PlanResult r = f.plan();

        assertThat(puts(r)).singleElement().satisfies(c -> {
            assertThat(c.getSceneId()).isEqualTo(901);
            assertThat(c.getState()).isEqualTo(ChannelState.CHANNEL_DRAINING);
            assertThat(c.getDrainReason()).isEqualTo(DrainReason.DRAIN_ORPHAN);
            assertThat(c.getStateSinceMs()).isEqualTo(PlanFixture.NOW);
            assertThat(c.getPlanVersion()).isEqualTo(PlanFixture.NOW);
        });
        assertThat(ops(r, WorldPlanOp.RemoveDesired.class)).containsExactly(new WorldPlanOp.RemoveDesired(99));
        assertThat(ops(r, WorldPlanOp.RemoveCooldown.class)).containsExactly(new WorldPlanOp.RemoveCooldown(99));
        assertThat(r.counts().get(PlanResult.OTHER_CONFIG)).isEqualTo(new ChannelCounts(0, 1, 0));
    }

    @Test
    void 关闭孤儿清理时孤儿频道不动() {
        f.props(PlanFixture.props(Map.of("cleanup-orphans", "false")))
                .active(101, 1, 10, 0).active(201, 2, 10, 0).active(901, 99, 10, 0).desired(1, 1).desired(2, 1).desired(99, 1)
                .nodeHostingPlan(10);

        assertThat(f.plan().batch().isEmpty()).isTrue();
    }

    // ---------------------------------------------------------------- P3 排空推进

    @Test
    void 排空收尾_节点还没应用到这次改写时不删() {
        f.confs(1).active(101, 1, 10, 0).desired(1, 1)
                .draining(102, 1, 10, 1, DrainReason.DRAIN_SCALE_IN, 5, PlanFixture.NOW)
                .node(10, 4, scene(101, 1, 0)); // 场景已不在，但节点只应用到 4

        assertThat(f.plan().batch().isEmpty()).isTrue();
    }

    @Test
    void 排空收尾_节点已应用且场景已消失才删() {
        f.confs(1).active(101, 1, 10, 0).desired(1, 1)
                .draining(102, 1, 10, 1, DrainReason.DRAIN_REBALANCE, 5, PlanFixture.NOW)
                .node(10, 5, scene(101, 1, 0));

        PlanResult r = f.plan();

        assertThat(removes(r)).containsExactly(102L);
        assertThat(r.events()).containsExactly(
                new PlanEvent.Migration(MigrationReason.BETTER_HOME, MigrationOutcome.DONE, 1));
    }

    @Test
    void 排空收尾_场景还在_不删() {
        f.confs(1).active(101, 1, 10, 0).desired(1, 1)
                .draining(102, 1, 10, 1, DrainReason.DRAIN_SCALE_IN, 5, PlanFixture.NOW)
                .node(10, 9, scene(101, 1, 0), PlanFixture.drainingScene(102, 1, 12));

        assertThat(f.plan().batch().isEmpty()).isTrue();
    }

    @Test
    void 排空收尾_节点缺席时不收尾() {
        f.confs(1).active(101, 1, 10, 0).desired(1, 1)
                .draining(102, 1, 20, 0, DrainReason.DRAIN_SCALE_IN, 5, PlanFixture.NOW)
                .nodeHostingPlan(10).absent(20, Duration.ofSeconds(1));

        assertThat(f.plan().batch().isEmpty()).isTrue();
    }

    @Test
    void 排空超时_缩容排空回滚ACTIVE_期望数加一() {
        long since = PlanFixture.NOW - Duration.ofSeconds(300).toMillis();
        f.confs(1).active(101, 1, 10, 0).desired(1, 1)
                .draining(102, 1, 10, 1, DrainReason.DRAIN_SCALE_IN, 3, since)
                .node(10, 3, scene(101, 1, 0), PlanFixture.drainingScene(102, 1, 40));

        PlanResult r = f.plan();

        assertThat(puts(r)).singleElement().satisfies(c -> {
            assertThat(c.getSceneId()).isEqualTo(102);
            assertThat(c.getState()).isEqualTo(ChannelState.CHANNEL_ACTIVE);
            assertThat(c.getDrainReason()).isEqualTo(DrainReason.DRAIN_REASON_UNSPECIFIED);
            assertThat(c.getStateSinceMs()).isEqualTo(PlanFixture.NOW);
            assertThat(c.getSlot()).isEqualTo(1);
        });
        assertThat(ops(r, WorldPlanOp.SetDesired.class)).containsExactly(new WorldPlanOp.SetDesired(1, 2));
        assertThat(r.events()).containsExactly(
                new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.REVERTED, 1));
    }

    @Test
    void 排空超时回滚时slot已被占用_换未用的最小slot() {
        long since = PlanFixture.NOW - Duration.ofSeconds(301).toMillis();
        f.confs(1).active(101, 1, 10, 0).active(103, 1, 10, 1).desired(1, 2)
                .draining(102, 1, 10, 1, DrainReason.DRAIN_SCALE_IN, 3, since)
                .node(10, 3, scene(101, 1, 0), scene(103, 1, 0), PlanFixture.drainingScene(102, 1, 40));

        PlanResult r = f.plan();

        assertThat(puts(r)).singleElement().satisfies(c -> assertThat(c.getSlot()).isEqualTo(2));
    }

    @Test
    void 排空超时_孤儿与迁移原因只告警保持排空() {
        long since = PlanFixture.NOW - Duration.ofSeconds(600).toMillis();
        f.confs(1).active(101, 1, 10, 0).desired(1, 1)
                .draining(102, 1, 10, 1, DrainReason.DRAIN_REBALANCE, 3, since)
                .draining(901, 99, 10, 0, DrainReason.DRAIN_ORPHAN, 3, since)
                .node(10, 3, scene(101, 1, 0), PlanFixture.drainingScene(102, 1, 1), PlanFixture.drainingScene(901, 99, 1));

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.anomalies()).hasSize(2).allSatisfy(a -> assertThat(a).contains("排空超时"));
    }

    // ---------------------------------------------------------------- 租约、无节点、版本

    @Test
    void 发号租约无效_零新建_其它步骤照做() {
        f.leaseValid = false;
        f.confs(1).active(101, 1, 10, 0).desired(1, 3)
                .draining(102, 1, 10, 1, DrainReason.DRAIN_SCALE_IN, 5, PlanFixture.NOW)
                .node(10, 5, scene(101, 1, 0));

        PlanResult r = f.plan();

        assertThat(r.noLease()).isTrue();
        assertThat(removes(r)).containsExactly(102L);
        assertThat(f.created(r)).isEmpty();
    }

    @Test
    void 无活节点_不补建_也不单独为播种写入() {
        PlanResult r = f.plan();

        assertThat(r.noNodes()).isTrue();
        assertThat(r.batch().isEmpty()).isTrue();
    }

    @Test
    void 被NodeAvailability排除的节点不接新频道() {
        f.confs(1).desired(1, 2).node(10, 0).node(20, 0);
        f.live = List.of(20);

        assertThat(f.created(f.plan())).extracting(WorldChannel::getNodeId).containsExactly(20, 20);
    }

    @Test
    void 写入批次里改写的记录plan_version都是写入后的版本_期望ver加一与快照时刻取大() {
        long v = PlanFixture.NOW + 7;  // 版本号早已是毫秒量级、且领先于本拍时刻：加一
        f.version = v;
        f.active(901, 99, 10, 0).node(10, v, scene(901, 99, 0));

        PlanResult r = f.plan();

        assertThat(r.batch().expectedVersion()).isEqualTo(v);
        assertThat(r.batch().writtenVersion()).isEqualTo(v + 1);
        assertThat(puts(r)).hasSize(3).allSatisfy(c -> assertThat(c.getPlanVersion()).isEqualTo(v + 1));
        assertThat(r.channels().values()).allSatisfy(c -> assertThat(c.getPlanVersion()).isEqualTo(v + 1));
    }

    @Test
    void 版本号落后于快照时刻时_写入后的版本取快照的_Redis_TIME_丢写重写不撞号() {
        f.version = 7;  // 例如 Redis 丢写后退回的旧值
        f.active(901, 99, 10, 0).node(10, 7, scene(901, 99, 0));

        PlanResult r = f.plan();

        assertThat(r.batch().expectedVersion()).isEqualTo(7);
        assertThat(r.batch().writtenVersion()).isEqualTo(PlanFixture.NOW);
        assertThat(puts(r)).hasSize(3).allSatisfy(c -> assertThat(c.getPlanVersion()).isEqualTo(PlanFixture.NOW));
    }

    @Test
    void 过期冷却字段只搭便车清理() {
        f.confs(1).active(101, 1, 10, 0).desired(1, 1).nodeHostingPlan(10);
        f.cooldown.put(1, PlanFixture.NOW - 1);

        assertThat(f.plan().batch().isEmpty()).isTrue();

        f.node(20, 0); // 有实际改动（覆盖新节点）时顺手清掉
        assertThat(ops(f.plan(), WorldPlanOp.RemoveCooldown.class)).containsExactly(new WorldPlanOp.RemoveCooldown(1));
    }

    @Test
    void 频道数统计_missing是节点已应用但没建出来的ACTIVE() {
        f.confs(1).active(101, 1, 10, 0).active(102, 1, 10, 1).desired(1, 2)
                .node(10, 0, scene(101, 1, 0)); // 版本 0 已应用，102 却不在

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.counts().get(1)).isEqualTo(new ChannelCounts(2, 0, 1));
    }

    @Test
    void 坏记录不改写只告警_不让整拍失败() {
        f.confs(1).desired(1, 1).node(10, 0);
        f.channels.put(55L, WorldChannel.newBuilder().setSceneId(55).setSceneConfigId(99).setNodeId(10)
                .setState(ChannelState.CHANNEL_STATE_UNSPECIFIED).build());

        PlanResult r = f.plan();

        assertThat(r.anomalies()).anySatisfy(a -> assertThat(a).contains("坏的频道记录"));
        assertThat(f.created(r)).hasSize(1);
        assertThat(removes(r)).isEmpty();
    }
}
