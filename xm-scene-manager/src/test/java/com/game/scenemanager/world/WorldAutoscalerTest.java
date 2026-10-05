package com.game.scenemanager.world;

import static com.game.scenemanager.world.PlanFixture.ops;
import static com.game.scenemanager.world.PlanFixture.puts;
import static com.game.scenemanager.world.PlanFixture.removes;
import static com.game.scenemanager.world.PlanFixture.scene;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldPlanOp;
import com.game.scenemanager.world.PlanEvent.AutoscaleAction;
import com.game.scenemanager.world.PlanEvent.AutoscaleOutcome;
import com.game.scenemanager.world.WorldAutoscaler.Load;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 自动扩缩容（scene-channels-spec §4.7、§9.2 WorldAutoscalerTest）：逐条移植 mmorpg world_autoscale_test.go 的 13 个用例与
 * world_autoscale_mirror_test.go 的镜像用例，外加 Java 特有的同节点兄弟 / 同节点余量。
 * 基线缺省阈值：扩 2000、缩 100、最少 1、最多 16、冷却 120 s、排空 300 s。
 */
class WorldAutoscalerTest {

    private static final int CONF = 1;
    private static final int NODE = 10;

    private final PlanFixture f = new PlanFixture();

    WorldAutoscalerTest() {
        f.confs(CONF).props(PlanFixture.props(Map.of("autoscale.enabled", "true")));
        f.autoscaleDue = true;
    }

    /** 在 {@link #NODE} 上铺一组频道及其人数；期望数对齐实际频道数（同基线 seedChannels）。 */
    private void seedChannels(Map<Long, Long> playersByScene) {
        seedChannelsOn(NODE, playersByScene);
    }

    private void seedChannelsOn(int node, Map<Long, Long> playersByScene) {
        List<DirectoryView.Scene> scenes = new ArrayList<>();
        DirectoryView.Node existing = f.nodes.get(node);
        if (existing != null) {
            scenes.addAll(existing.scenes().values());
        }
        int slot = (int) f.channels.values().stream().filter(c -> c.getSceneConfigId() == CONF).count();
        for (Map.Entry<Long, Long> e : new java.util.TreeMap<>(playersByScene).entrySet()) {
            f.active(e.getKey(), CONF, node, slot++);
            scenes.add(scene(e.getKey(), CONF, e.getValue()));
        }
        f.node(node, f.version, scenes.toArray(DirectoryView.Scene[]::new));
        f.desiredMatchesActive();
    }

    private static Map<Long, Long> players(long... sceneAndCount) {
        Map<Long, Long> out = new HashMap<>();
        for (int i = 0; i < sceneAndCount.length; i += 2) {
            out.put(sceneAndCount[i], sceneAndCount[i + 1]);
        }
        return out;
    }

    private int desiredAfter(PlanResult r) {
        f.apply(r);
        return f.desired.get(CONF);
    }

    // ---------------------------------------------------------------- 扩容

    @Test
    void 所有频道都到2000才扩容_扩容与补建同一次写入() {
        seedChannels(players(101, 2000, 102, 2100));

        PlanResult r = f.plan();

        assertThat(r.events()).containsExactly(new PlanEvent.Autoscale(AutoscaleAction.SCALE_OUT, AutoscaleOutcome.OK, CONF));
        assertThat(ops(r, WorldPlanOp.SetDesired.class)).containsExactly(new WorldPlanOp.SetDesired(CONF, 3));
        assertThat(ops(r, WorldPlanOp.SetCooldown.class))
                .containsExactly(new WorldPlanOp.SetCooldown(CONF, PlanFixture.NOW + 120_000));
        assertThat(f.created(r)).singleElement().satisfies(c -> assertThat(c.getSlot()).isEqualTo(2));
        assertThat(desiredAfter(r)).isEqualTo(3);
    }

    @Test
    void 有一个频道没到线就不扩() {
        seedChannels(players(101, 2500, 102, 1200));

        PlanResult r = f.plan();

        assertThat(r.events()).isEmpty();
        assertThat(r.batch().isEmpty()).isTrue();
    }

    @Test
    void 到达频道上限后不再扩_只告警() {
        f.props(PlanFixture.props(Map.of("autoscale.enabled", "true", "autoscale.max-channels", "2")));
        seedChannels(players(101, 3000, 102, 3000));

        PlanResult r = f.plan();

        assertThat(r.events()).containsExactly(
                new PlanEvent.Autoscale(AutoscaleAction.SCALE_OUT, AutoscaleOutcome.MAX_REACHED, CONF));
        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.anomalies()).anySatisfy(a -> assertThat(a).contains("扩容到顶"));
    }

    // ---------------------------------------------------------------- 缩容

    @Test
    void 人少的频道转排空_期望数减一_打冷却() {
        seedChannels(players(101, 1500, 102, 40));

        PlanResult r = f.plan();

        assertThat(r.events()).containsExactly(new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.OK, CONF));
        assertThat(puts(r)).singleElement().satisfies(c -> {
            assertThat(c.getSceneId()).isEqualTo(102);
            assertThat(c.getState()).isEqualTo(ChannelState.CHANNEL_DRAINING);
            assertThat(c.getDrainReason()).isEqualTo(DrainReason.DRAIN_SCALE_IN);
            assertThat(c.getStateSinceMs()).isEqualTo(PlanFixture.NOW);
        });
        assertThat(ops(r, WorldPlanOp.SetDesired.class)).containsExactly(new WorldPlanOp.SetDesired(CONF, 1));
        assertThat(ops(r, WorldPlanOp.SetCooldown.class)).hasSize(1);
        assertThat(removes(r)).isEmpty(); // 先摘路由（转排空），收尾等节点销毁后
    }

    @Test
    void 只剩一个频道时再空也不缩() {
        seedChannels(players(101, 0));

        PlanResult r = f.plan();

        assertThat(r.events()).isEmpty();
        assertThat(r.batch().isEmpty()).isTrue();
    }

    @Test
    void 最少频道数配成0也钳回1() {
        f.props(PlanFixture.props(Map.of("autoscale.enabled", "true", "autoscale.min-channels", "0")));
        seedChannels(players(101, 5));

        PlanResult r = f.plan();

        assertThat(r.events()).isEmpty();
        assertThat(f.props.autoscale().clamp(0)).isEqualTo(1);
    }

    @Test
    void 其余频道装不下就不缩() {
        seedChannels(players(101, 1990, 102, 90));

        PlanResult r = f.plan();

        assertThat(r.events()).containsExactly(
                new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.NO_VICTIM, CONF));
        assertThat(r.batch().isEmpty()).isTrue();
    }

    @Test
    void 余量边界_刚好装下与差一个() {
        WorldChannel any = WorldChannel.getDefaultInstance();
        Load victim = new Load(any.toBuilder().setSceneId(2).build(), 100);
        assertThat(WorldAutoscaler.hasHeadroomFor(List.of(victim, new Load(any.toBuilder().setSceneId(1).build(), 1900)),
                victim, 2000)).isTrue();
        assertThat(WorldAutoscaler.hasHeadroomFor(List.of(victim, new Load(any.toBuilder().setSceneId(1).build(), 1901)),
                victim, 2000)).isFalse();
    }

    // ---------------------------------------------------------------- 收敛

    @Test
    void 排空中的频道还有人时保持排空() {
        seedChannels(players(101, 1500, 102, 40));
        f.apply(f.plan()); // 开始排空 102（版本 → 1）
        f.node(NODE, f.version, scene(101, CONF, 1500), PlanFixture.drainingScene(102, CONF, 12));
        f.autoscaleDue = false;

        PlanResult r = f.plan();

        assertThat(removes(r)).isEmpty();
        assertThat(f.channels.get(102L).getState()).isEqualTo(ChannelState.CHANNEL_DRAINING);
    }

    @Test
    void 人走干净之后下一拍收尾() {
        seedChannels(players(101, 1500, 102, 40));
        f.apply(f.plan());
        f.node(NODE, f.version, scene(101, CONF, 1540)); // 节点已应用、102 已销毁
        f.autoscaleDue = false;

        PlanResult r = f.plan();

        assertThat(removes(r)).containsExactly(102L);
        assertThat(r.events()).containsExactly(
                new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.DRAINED, CONF));
        assertThat(r.removed()).extracting(WorldChannel::getSceneId).containsExactly(102L); // 5.3 镜像级联的钩子
    }

    @Test
    void 期望数以Redis为准_改配置不覆盖() {
        f.props(PlanFixture.props(Map.of("autoscale.enabled", "true", "channel-count", "4")));
        f.node(NODE, 0);
        f.autoscaleDue = false;
        PlanResult first = f.plan();
        assertThat(ops(first, WorldPlanOp.SeedDesired.class)).containsExactly(new WorldPlanOp.SeedDesired(CONF, 4));
        f.apply(first);
        f.desired.put(CONF, 7); // 伸缩过的值

        f.props(PlanFixture.props(Map.of("autoscale.enabled", "true", "channel-count", "2")));
        f.nodeHostingPlan(NODE);
        PlanResult second = f.plan();

        assertThat(f.created(second)).hasSize(3); // 4 → 7
        assertThat(ops(second, WorldPlanOp.SeedDesired.class)).isEmpty();
    }

    @Test
    void 冷却窗口内不做任何伸缩决策() {
        seedChannels(players(101, 3000, 102, 3000));
        PlanResult first = f.plan();
        assertThat(first.events()).hasSize(1);
        f.apply(first);
        // 新频道建出来了也满了：冷却期内不能再扩
        f.node(NODE, f.version, scene(101, CONF, 3000), scene(102, CONF, 3000), scene(1000, CONF, 3000));
        f.now += 60_000;

        assertThat(f.plan().events()).isEmpty();

        f.now += 61_000; // 过了 120 s
        assertThat(f.plan().events()).containsExactly(
                new PlanEvent.Autoscale(AutoscaleAction.SCALE_OUT, AutoscaleOutcome.OK, CONF));
    }

    @Test
    void 关闭时不做决策() {
        f.props(PlanFixture.props(Map.of()));
        seedChannels(players(101, 3000, 102, 40));

        PlanResult r = f.plan();

        assertThat(r.events()).isEmpty();
        assertThat(r.batch().isEmpty()).isTrue();
    }

    @Test
    void 未到决策节拍时不做决策() {
        seedChannels(players(101, 3000, 102, 3000));
        f.autoscaleDue = false;

        assertThat(f.plan().events()).isEmpty();
    }

    // ---------------------------------------------------------------- 镜像（world_autoscale_mirror_test.go）

    @Test
    void 最闲的频道是镜像源时跳过它_缩下一个() {
        seedChannels(players(101, 10, 102, 20, 103, 900));
        f.mirrors = sceneId -> sceneId == 101;

        PlanResult r = f.plan();

        assertThat(puts(r)).singleElement().satisfies(c -> assertThat(c.getSceneId()).isEqualTo(102));
    }

    @Test
    void 够闲的频道都是镜像源时不缩() {
        seedChannels(players(101, 10, 102, 20, 103, 900));
        f.mirrors = sceneId -> sceneId == 101 || sceneId == 102;

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.events()).containsExactly(
                new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.NO_VICTIM, CONF));
    }

    @Test
    void 镜像查询失败时按是处理_不缩() {
        seedChannels(players(101, 10, 103, 900));
        f.mirrors = sceneId -> {
            throw new IllegalStateException("mirror query failed");
        };

        assertThat(f.plan().batch().isEmpty()).isTrue();
    }

    // ---------------------------------------------------------------- Java 特有（D9）

    @Test
    void per_node_牺牲者没有同节点兄弟时不缩_不删节点上某图的最后一个() {
        seedChannelsOn(10, players(101, 1500));
        seedChannelsOn(11, players(102, 40));

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.events()).containsExactly(
                new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.NO_VICTIM, CONF));
    }

    @Test
    void 余量只算同节点兄弟() {
        // 102（90 人）的全图余量 10 + 1500 + 1500 够（基线会缩它），但同节点兄弟 101 只剩 10 个位置；103 / 104 不到缩容线
        seedChannelsOn(10, players(101, 1990, 102, 90));
        seedChannelsOn(11, players(103, 500, 104, 500));

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.events()).containsExactly(
                new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.NO_VICTIM, CONF));

        // 同节点余量够时照常缩
        f.node(10, f.version, scene(101, CONF, 1900), scene(102, CONF, 90));
        assertThat(puts(f.plan())).singleElement().satisfies(c -> assertThat(c.getSceneId()).isEqualTo(102));
    }

    @Test
    void hash模式_0人的牺牲者可以没有同节点兄弟() {
        f.props(PlanFixture.props(Map.of("autoscale.enabled", "true", "coverage", "hash")));
        f.rebalanceDue = false;
        seedChannelsOn(10, players(101, 1500));
        seedChannelsOn(11, players(102, 0));

        PlanResult r = f.plan();

        assertThat(r.events()).contains(new PlanEvent.Autoscale(AutoscaleAction.SCALE_IN, AutoscaleOutcome.OK, CONF));
        assertThat(puts(r)).anySatisfy(c -> {
            assertThat(c.getSceneId()).isEqualTo(102);
            assertThat(c.getState()).isEqualTo(ChannelState.CHANNEL_DRAINING);
        });
    }

    @Test
    void per_node覆盖让ACTIVE数超过期望数时_扩容以ACTIVE数为基数_真的补出一个() {
        seedChannelsOn(10, players(101, 3000));
        seedChannelsOn(20, players(102, 3000));
        seedChannelsOn(30, players(103, 3000));
        f.desired(CONF, 1); // 期望 1，覆盖铺了 3 个

        PlanResult r = f.plan();

        assertThat(ops(r, WorldPlanOp.SetDesired.class)).containsExactly(new WorldPlanOp.SetDesired(CONF, 4));
        assertThat(f.created(r)).singleElement().satisfies(c -> assertThat(c.getNodeId()).isEqualTo(10));
    }

    @Test
    void per_node覆盖让ACTIVE数超过期望数时_缩容后期望数等于剩下的ACTIVE数() {
        seedChannelsOn(10, players(101, 1500, 102, 40));
        seedChannelsOn(20, players(103, 1500));
        f.desired(CONF, 2); // 期望 2，实际 3

        PlanResult r = f.plan();

        assertThat(puts(r)).singleElement().satisfies(c -> assertThat(c.getSceneId()).isEqualTo(102));
        assertThat(ops(r, WorldPlanOp.SetDesired.class)).isEmpty(); // max(2, 3) − 1 = 2，与原值相同不写
    }

    @Test
    void 没建出来或节点上在排空的频道不进负载集() {
        f.active(101, CONF, NODE, 0).active(102, CONF, NODE, 1).active(103, CONF, NODE, 2).desiredMatchesActive();
        // 102 没建出来、103 在节点上排空：负载集只剩 101，不缩
        f.node(NODE, 0, scene(101, CONF, 5), PlanFixture.drainingScene(103, CONF, 0));

        assertThat(f.plan().events()).isEmpty();
    }
}
