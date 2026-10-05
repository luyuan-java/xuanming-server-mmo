package com.game.scenemanager.world;

import static com.game.scenemanager.world.PlanFixture.puts;
import static com.game.scenemanager.world.PlanFixture.removes;
import static com.game.scenemanager.world.PlanFixture.scene;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ChannelState;
import com.game.api.proto.DrainReason;
import com.game.api.proto.WorldChannel;
import com.game.discovery.world.WorldPlanSnapshot;
import com.game.scenemanager.world.PlanEvent.MigrationOutcome;
import com.game.scenemanager.world.PlanEvent.MigrationReason;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 择机迁移（scene-channels-spec §4.8、§9.2 WorldRebalancePlannerTest）：移植 mmorpg logic_test.go:2400-2537 的 TestPlanRebalance_* 里适用的用例。
 * {@code LiveRoleFlipWithPlayersFailsClosed}、{@code NonWorldHostingNotConsidered} 依赖节点角色，5.1 没有角色（D15），不适用；
 * {@code UrgentWhenCurrentNodeNotInLivePool} 在 Java 是「死节点重铺」（P2 + P5）。
 *
 * <p>节点 [10, 20, 30]；conf 1 的落点：slot 0 → 键 1000 → 1000 mod 3 = 1 → 节点 20；slot 1 → 1001 mod 3 = 0 → 节点 10。
 */
class WorldRebalancePlannerTest {

    private static final int CONF = 1;

    private final PlanFixture f = new PlanFixture();

    WorldRebalancePlannerTest() {
        f.confs(CONF).props(PlanFixture.props(Map.of("coverage", "hash"))).desired(CONF, 1);
        f.rebalanceDue = true;
        f.node(20, 0).node(30, 0);
    }

    /** slot 0 的频道放在节点 10（落点是 20），节点 10 已建出、人数 {@code players}。 */
    private void misplaced(long players) {
        f.active(555, CONF, 10, 0);
        f.node(10, 0, scene(555, CONF, players));
        f.reservations.put(555L, 0L);
    }

    @Test
    void 落点已对齐不迁() {
        f.active(777, CONF, 20, 0);
        f.node(20, 0, scene(777, CONF, 0)).node(10, 0);
        f.reservations.put(777L, 0L);

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.betterHomePending()).isZero();
    }

    @Test
    void 空频道落点不对_同一次写入里新记录同slot加旧记录排空() {
        misplaced(0);

        PlanResult r = f.plan();

        assertThat(puts(r)).hasSize(2);
        assertThat(f.created(r)).singleElement().satisfies(c -> {
            assertThat(c.getNodeId()).isEqualTo(20);
            assertThat(c.getSlot()).isZero();
            assertThat(c.getState()).isEqualTo(ChannelState.CHANNEL_ACTIVE);
            assertThat(c.getSceneId()).isNotEqualTo(555); // 换节点 = 新号（D4）
        });
        assertThat(puts(r)).filteredOn(c -> c.getSceneId() == 555).singleElement().satisfies(c -> {
            assertThat(c.getState()).isEqualTo(ChannelState.CHANNEL_DRAINING);
            assertThat(c.getDrainReason()).isEqualTo(DrainReason.DRAIN_REBALANCE);
        });
        assertThat(r.events()).containsExactly(
                new PlanEvent.Migration(MigrationReason.BETTER_HOME, MigrationOutcome.PLANNED, CONF));
        assertThat(r.betterHomePending()).isZero();
    }

    @Test
    void 有人的频道即使落点不对也不迁() {
        misplaced(7);

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.betterHomePending()).isZero();
    }

    @Test
    void 有未到期预占或预占数读不到时不迁() {
        misplaced(0);
        f.reservations.put(555L, 1L);
        assertThat(f.plan().batch().isEmpty()).isTrue();

        f.reservations.clear();
        PlanResult r = f.plan();
        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.betterHomePending()).isEqualTo(1);
    }

    @Test
    void 预算为0时关闭() {
        f.props(PlanFixture.props(Map.of("coverage", "hash", "rebalance.max-migrations-per-tick", "0")));
        misplaced(0);

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.betterHomePending()).isEqualTo(1);
    }

    @Test
    void 按计划条数计预算_剩下的计积压() {
        f.props(PlanFixture.props(Map.of("coverage", "hash", "rebalance.max-migrations-per-tick", "1"))).desired(CONF, 2);
        // slot 0 落点 20、slot 1 落点 10：两条都放在节点 30 上
        f.active(555, CONF, 30, 0).active(556, CONF, 30, 1);
        f.node(30, 0, scene(555, CONF, 0), scene(556, CONF, 0)).node(10, 0);
        f.reservations.put(555L, 0L);
        f.reservations.put(556L, 0L);

        PlanResult r = f.plan();

        assertThat(r.events()).hasSize(1);
        assertThat(f.created(r)).singleElement().satisfies(c -> assertThat(c.getNodeId()).isEqualTo(20));
        assertThat(r.betterHomePending()).isEqualTo(1);
    }

    @Test
    void 未到期时只计积压不迁() {
        misplaced(0);
        f.rebalanceDue = false;

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.betterHomePending()).isEqualTo(1);
    }

    @Test
    void 镜像源不迁() {
        misplaced(0);
        f.mirrors = sceneId -> true;

        assertThat(f.plan().batch().isEmpty()).isTrue();
    }

    @Test
    void 发号租约无效时不迁() {
        misplaced(0);
        f.leaseValid = false;

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.noLease()).isTrue();
    }

    @Test
    void per_node模式不做择机迁移() {
        f.props(PlanFixture.props(Map.of()));
        misplaced(0);
        f.node(20, 0, scene(9001, CONF, 0)).node(30, 0, scene(9002, CONF, 0));
        f.active(9001, CONF, 20, 1).active(9002, CONF, 30, 2);
        f.desired(CONF, 3);

        PlanResult r = f.plan();

        assertThat(r.batch().isEmpty()).isTrue();
        assertThat(r.betterHomePending()).isZero();
    }

    @Test
    void 当前节点已死_宽限期后删记录并在落点用新号重铺() {
        f.active(555, CONF, 100, 0).absent(100, Duration.ofSeconds(30)).node(10, 0);

        PlanResult r = f.plan();

        assertThat(removes(r)).containsExactly(555L);
        assertThat(f.created(r)).singleElement().satisfies(c -> {
            assertThat(c.getNodeId()).isEqualTo(20); // slot 0 的落点
            assertThat(c.getSlot()).isZero();
        });
        assertThat(r.events()).contains(new PlanEvent.Migration(MigrationReason.NODE_GONE, MigrationOutcome.DONE, CONF));
    }

    @Test
    void 探测只按快照列出可能的候选() {
        misplaced(0);
        f.active(777, CONF, 20, 1); // 落点是 10，但 20 上没建出来 → 不是候选
        WorldPlanSnapshot snapshot = f.input().snapshot();

        List<Long> probe = f.planner().rebalanceProbe(snapshot, new DirectoryView(f.nodes), List.of(30, 20, 10));

        assertThat(probe).containsExactly(555L);
    }

    @Test
    void 新建的频道直接放在落点_补建后再平衡不搬_修B3() {
        f.desired(CONF, 3).node(10, 0);

        PlanResult r = f.plan();

        List<WorldChannel> created = f.created(r);
        assertThat(created).hasSize(3);
        assertThat(created).allSatisfy(c -> assertThat(c.getNodeId())
                .isEqualTo(ChannelPlacement.hashTarget(CONF, c.getSlot(), List.of(10, 20, 30))));
        assertThat(r.events()).isEmpty();
    }
}
