package com.game.match.gather;

import static com.game.match.gather.GatherFixture.FIVE_V_FIVE;
import static com.game.match.gather.GatherFixture.ONE_V_ONE;
import static com.game.match.gather.GatherFixture.PVE_TEAM;
import static com.game.match.gather.GatherFixture.QUEUED_TTL_MS;
import static com.game.match.gather.GatherFixture.READY_TTL_MS;
import static com.game.match.gather.GatherFixture.SEED;
import static com.game.match.gather.GatherFixture.TARGET_A;
import static com.game.match.gather.GatherFixture.TARGET_B;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.SceneBattleStatus;
import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.match.placement.PlacementStore;
import com.game.match.port.RedisClock;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.FakeBattleNode;
import com.game.match.testing.FakeBattleNodes;
import com.game.match.testing.FakeSceneBattle;
import com.game.match.testing.ManualRedisClock;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.eBattleActivityKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 开局管线（match-spec §3.2、§3.3 补偿矩阵、§9.6、§15.2）：成功路径的每个字段与次序；补偿矩阵每个出口各一例（肇事者、解冻集合、回队首或删票、
 * 落点记录删或留、destroy 发给谁）；换节点的四种情形；M14（备战结局不明的人也收取消）、M15（明确拒绝不发 destroy）；五个入口各自的票据策略。
 * 对照基线 {@code gather_not_allocatable_test.go:131-309}、{@code gather_create_reject_test.go:43-94}（Java 断言<b>不发</b> destroy）、
 * {@code gather_spectate_index_test.go:264-463}、{@code gather_fingerprint_test.go:122-237}。
 *
 * <p>直接同步调 {@link GatherPipeline#run}（在途许可与虚拟线程由 {@code VirtualThreadGatherLauncherTest} 测）。
 */
class GatherPipelineTest {

    private static final long A = 1001;
    private static final long B = 1002;
    private static final long T0 = ManualRedisClock.DEFAULT_START_MS;
    private static final QueueRef QUEUE_1V1 = new QueueRef(ONE_V_ONE, 0);

    private final GatherFixture f = new GatherFixture();

    private static String id(long battleId) {
        return Long.toUnsignedString(battleId);
    }

    /** 两人都回到了队首（按原序）、票是 queued、TTL 恢复成 6 h。 */
    private void assertRequeuedInOrder(QueueRef queue, long... playerIds) {
        List<String> expected = new ArrayList<>();
        for (long playerId : playerIds) {
            expected.add(Long.toString(playerId));
            Ticket ticket = f.ticket(playerId);
            assertThat(ticket.state()).as("player %d 的票", playerId).isEqualTo(TicketState.QUEUED);
            assertThat(ticket.ticketId()).isEqualTo(GatherFixture.ticketId(playerId));
            assertThat(f.tickets.ttlMs(playerId)).isEqualTo(QUEUED_TTL_MS);
        }
        assertThat(f.tickets.queueMembers(queue)).containsExactlyElementsOf(expected);
        assertThat(f.tickets.indexed(queue)).isTrue();
    }

    private void assertNoBattleSideEffects() {
        assertThat(f.battleA.creates).isEmpty();
        assertThat(f.battleA.destroys).isEmpty();
        assertThat(f.placements.writes).isEmpty();
        assertThat(f.placements.deletes).isEmpty();
        assertThat(f.hooks.started).isEmpty();
    }

    // ================================================================ 成功路径

    @Test
    void 凑单1V1成功_备战与建房的每个字段_落点先于建房_票据置ready_钩子与次序() {
        // 第一次读时间给期限，第二次（全员备战之后）给 created_at_ms
        AtomicInteger clockReads = new AtomicInteger();
        f.clockPort = d -> T0 + 1_234L * clockReads.getAndIncrement();
        f.scene.name(A, "甲").name(B, "乙");
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(result.outcome()).isEqualTo(GatherOutcome.SUCCESS);
        long battleId = result.battleId();
        assertThat(battleId).isNotZero();
        assertThat(clockReads.get()).as("两个期限取同一次读数，created_at_ms 另读一次").isEqualTo(2);

        // 备战：逐人串行、名单顺序；期限 = 读数 + 300 s / + matched TTL(2) = 48 s；battle_node_id 是首选节点
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002");
        for (FakeSceneBattle.Call call : f.scene.calls) {
            PrepareBattleRequest prepare = call.prepare();
            assertThat(prepare.getBattleId()).isEqualTo(battleId);
            assertThat(prepare.getBattleNodeId()).isEqualTo(1);
            assertThat(prepare.getDeadlineMs()).isEqualTo(T0 + 300_000);
            assertThat(prepare.getPrepareDeadlineMs()).isEqualTo(T0 + 48_000);
            assertThat(call.targetInstanceId()).isEqualTo("scene-inst-a");
        }
        assertThat(f.sceneCalls.calls).allSatisfy(call -> assertThat(call.timeout()).isEqualTo(Duration.ofSeconds(3)));

        // 建房请求
        assertThat(f.battleA.creates).hasSize(1);
        CreateBattleRequest create = f.battleA.creates.get(0);
        assertThat(create.getBattleId()).isEqualTo(battleId);
        assertThat(create.getBattleConfigId()).isZero();
        assertThat(create.getMatchMode()).isEqualTo(ONE_V_ONE);
        assertThat(create.getSeed()).isEqualTo(SEED);
        assertThat(create.getDeadlineMs()).isEqualTo(T0 + 300_000);
        assertThat(create.getCreatedAtMs()).isEqualTo(T0 + 1_234);
        assertThat(create.getTableFingerprint()).as("全员一致的指纹透传").isEqualTo("fp-1");
        assertThat(create.hasActivityContext()).isFalse();
        assertThat(create.getPlayersList()).extracting(BattlePlayerSnapshot::getPlayerId).containsExactly(A, B);
        assertThat(create.getPlayersList()).extracting(BattlePlayerSnapshot::getTeamIndex).as("锚点在 0 队").containsExactly(0, 1);
        assertThat(f.battleCalls.calls).singleElement().satisfies(call -> {
            assertThat(call.target()).isEqualTo(TARGET_A);
            assertThat(call.timeout()).isEqualTo(Duration.ofSeconds(5));
        });
        assertThat(f.battleA.destroys).isEmpty();
        assertThat(f.battleNodes.picks).containsExactly(Set.of());

        // 落点记录：建房之前写一次，成功后同值补写一次
        BattlePlacement expected = BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(1).setBattleInstanceId("inst-a")
                .setRpcHost("127.0.0.1").setRpcPort(21200).setAttempt(1).setMode(ONE_V_ONE).setBattleConfigId(0).addPlayerNames("甲")
                .addPlayerNames("乙").setCreatedAtMs(T0 + 1_234).setDeadlineMs(T0 + 300_000).build();
        assertThat(f.placements.writes).containsExactly(expected, expected);
        assertThat(f.placements.stored(battleId)).contains(expected);
        assertThat(f.placements.deletes).isEmpty();

        // 票据：ready + battle_id + 60 s
        for (long playerId : new long[] {A, B}) {
            Ticket ticket = f.ticket(playerId);
            assertThat(ticket.state()).isEqualTo(TicketState.READY);
            assertThat(ticket.battleId()).isEqualTo(battleId);
            assertThat(f.tickets.ttlMs(playerId)).isEqualTo(READY_TTL_MS);
        }
        assertThat(f.tickets.calls).containsExactly("markReady(1001)", "markReady(1002)");
        assertThat(f.tickets.queueMembers(QUEUE_1V1)).isEmpty();

        // 钩子与跨组件次序
        assertThat(f.hooks.beforePrepare).containsExactly(List.of(A, B));
        assertThat(f.hooks.started).containsExactly(expected);
        assertThat(f.events).containsExactly("hooks.beforePrepare", "scene.prepare:1001", "scene.prepare:1002",
                "placement.write:" + id(battleId) + "#1", "battle[a].create:" + id(battleId), "placement.write:" + id(battleId) + "#1",
                "hooks.onStarted");
        assertThat(f.scene.frozen()).as("开局成功，不解冻").containsOnlyKeys(A, B);
        assertThat(f.ratings.loads).as("1V1 不重读评分").isEmpty();
        assertThat(f.count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "single")).isEqualTo(1.0);
        assertThat(f.count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "cross")).isZero();
    }

    @Test
    void 每局的battle_id都不同且递增_时间在高位() {
        long first = f.pipeline().run(f.popped(ONE_V_ONE, 0, A, B)).battleId();
        long second = f.pipeline().run(f.popped(ONE_V_ONE, 0, 1003, 1004)).battleId();

        assertThat(Long.compareUnsigned(second, first)).isPositive();
    }

    @Test
    void PVE_SOLO成功_一人0队_备战期限42秒_票据置ready() {
        GatherResult result = f.pipeline().run(f.solo(A));

        assertThat(result.ok()).isTrue();
        PrepareBattleRequest prepare = f.scene.calls.get(0).prepare();
        assertThat(prepare.getPrepareDeadlineMs() - prepare.getDeadlineMs()).isEqualTo(42_000 - 300_000);
        assertThat(prepare.getPrepareDeadlineMs()).isEqualTo(T0 + 42_000);
        CreateBattleRequest create = f.battleA.creates.get(0);
        assertThat(create.getMatchMode()).isEqualTo(GatherFixture.PVE_SOLO);
        assertThat(create.getBattleConfigId()).isEqualTo(1);
        assertThat(create.getPlayersList()).extracting(BattlePlayerSnapshot::getTeamIndex).containsExactly(0);
        assertThat(f.ticket(A).state()).isEqualTo(TicketState.READY);
        assertThat(f.placements.stored(result.battleId()).orElseThrow().getMode()).isEqualTo(GatherFixture.PVE_SOLO);
    }

    @Test
    void 切磋成功_发起者0队应战者1队_完全不碰票据() {
        GatherResult result = f.pipeline().run(f.challenge(A, B));

        assertThat(result.ok()).isTrue();
        CreateBattleRequest create = f.battleA.creates.get(0);
        assertThat(create.getMatchMode()).isEqualTo(6);
        assertThat(create.getPlayersList()).extracting(BattlePlayerSnapshot::getPlayerId).containsExactly(A, B);
        assertThat(create.getPlayersList()).extracting(BattlePlayerSnapshot::getTeamIndex).containsExactly(0, 1);
        assertThat(f.tickets.calls).as("切磋的参战者没有票据").isEmpty();
        assertThat(f.tickets.ticketCount()).isZero();
    }

    @Test
    void 五对五成功_gather时重读评分后蛇形分队_名单顺序不变_备战期限96秒() {
        long[] members = {2001, 2002, 2003, 2004, 2005, 2006, 2007, 2008, 2009, 2010};
        List<Long> order = new ArrayList<>();
        for (int i = 0; i < members.length; i++) {
            // 评分打散：名单第 i 位的分数 = 1000 + (137 × i mod 900)
            f.ratings.set(members[i], (1000L + (137L * i) % 900) * 100);
            order.add(members[i]);
        }
        int[] want = TeamAssignment.assign(com.game.proto.match.MatchMode.MATCH_MODE_5V5, order,
                f.ratings.loadAllCentiOrDefault(order));
        f.ratings.loads.clear();

        GatherResult result = f.pipeline().run(f.popped(FIVE_V_FIVE, 7, members));

        assertThat(result.ok()).isTrue();
        assertThat(f.ratings.loads).as("重读一次全员的评分（不用票里存的 1500）").containsExactly(order);
        CreateBattleRequest create = f.battleA.creates.get(0);
        assertThat(create.getPlayersList()).extracting(BattlePlayerSnapshot::getPlayerId).containsExactlyElementsOf(order);
        List<Integer> teams = create.getPlayersList().stream().map(BattlePlayerSnapshot::getTeamIndex).toList();
        assertThat(teams).containsExactly(want[0], want[1], want[2], want[3], want[4], want[5], want[6], want[7], want[8], want[9]);
        assertThat(teams.stream().filter(t -> t == 0).count()).isEqualTo(5);
        assertThat(teams).as("评分各不相同时不是按下标前 5 后 5").isNotEqualTo(List.of(0, 0, 0, 0, 0, 1, 1, 1, 1, 1));
        assertThat(f.scene.calls.get(0).prepare().getPrepareDeadlineMs()).isEqualTo(T0 + 96_000);
        assertThat(create.getBattleConfigId()).isEqualTo(7);
    }

    @Test
    void 五对五_评分全读不到时按名单顺序蛇形() {
        long[] members = {2001, 2002, 2003, 2004, 2005, 2006, 2007, 2008, 2009, 2010};

        f.pipeline().run(f.popped(FIVE_V_FIVE, 0, members));

        assertThat(f.battleA.creates.get(0).getPlayersList()).extracting(BattlePlayerSnapshot::getTeamIndex)
                .containsExactly(0, 1, 1, 0, 0, 1, 1, 0, 0, 1);
    }

    @Test
    void 活动开战_用预发的battle_id_不再发号_上下文原样透传_全员0队() {
        BattleActivityContext context = BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                .setGuildId(555).setActivityId(3).setPeriodKey(20261006).setInitiatorPlayerId(A).setGuildPeriodKey(20261006).build();
        // 发号租约无效：预发的号不受影响（管线不再发号）
        f.leaseValid = false;

        GatherResult result = f.pipeline().run(f.activity(8_888_888L, context, A, B));

        assertThat(result.ok()).isTrue();
        assertThat(result.battleId()).isEqualTo(8_888_888L);
        CreateBattleRequest create = f.battleA.creates.get(0);
        assertThat(create.getBattleId()).isEqualTo(8_888_888L);
        assertThat(create.getActivityContext()).isEqualTo(context);
        assertThat(create.getMatchMode()).isEqualTo(PVE_TEAM);
        assertThat(create.getPlayersList()).extracting(BattlePlayerSnapshot::getTeamIndex).containsExactly(0, 0);
        assertThat(f.scene.calls).allSatisfy(call -> assertThat(call.battleId()).isEqualTo(8_888_888L));
        assertThat(f.ticket(A).battleId()).isEqualTo(8_888_888L);
        assertThat(f.placements.stored(8_888_888L)).isPresent();
    }

    @Test
    void 整队开战成功_名单原序_全员0队_票据置ready() {
        GatherResult result = f.pipeline().run(f.team(B, A, 1003));

        assertThat(result.ok()).isTrue();
        assertThat(f.battleA.creates.get(0).getPlayersList()).extracting(BattlePlayerSnapshot::getPlayerId).containsExactly(B, A, 1003L);
        assertThat(f.battleA.creates.get(0).getPlayersList()).extracting(BattlePlayerSnapshot::getTeamIndex).containsExactly(0, 0, 0);
        assertThat(f.scene.calls.get(0).prepare().getPrepareDeadlineMs()).isEqualTo(T0 + 54_000);
        assertThat(f.ticket(1003).state()).isEqualTo(TicketState.READY);
    }

    @Test
    void 成员来自不同zone_zone组成记cross() {
        f.scene.zone(B, 2);

        assertThat(f.pipeline().run(f.popped(ONE_V_ONE, 0, A, B)).ok()).isTrue();

        assertThat(f.count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "cross")).isEqualTo(1.0);
        assertThat(f.count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "single")).isZero();
    }

    @Test
    void 两个钩子都抛异常_不影响开局() {
        f.hooks.throwOnBeforePrepare = true;
        f.hooks.throwOnStarted = true;

        GatherResult result = f.pipeline().run(f.popped(ONE_V_ONE, 0, A, B));

        assertThat(result.ok()).isTrue();
        assertThat(f.hooks.beforePrepare).hasSize(1);
        assertThat(f.hooks.started).hasSize(1);
        assertThat(f.ticket(A).state()).isEqualTo(TicketState.READY);
    }

    @Test
    void 票据置ready失败只记日志_不影响开局_其余人的票照常推进() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.tickets.faults.failNext("markReady");

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(f.ticket(A).state()).as("A 的写失败了，票留在 matched 到期自灭").isEqualTo(TicketState.MATCHED);
        assertThat(f.ticket(B).state()).isEqualTo(TicketState.READY);
        assertThat(f.scene.frozen()).containsOnlyKeys(A, B);
        assertThat(f.hooks.started).hasSize(1);
    }

    @Test
    void 票已被新票替换_ready的CAS不命中_不影响开局也不写脏新票() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        // A 的 matched 票过期后重排了一张新票
        f.tickets.delete(new TicketRef(A, GatherFixture.ticketId(A)), GatherFixture.d());
        f.tickets.enqueue(A, "t-new", QUEUE_1V1, 1, 150_000, QUEUED_TTL_MS, GatherFixture.d());

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(f.ticket(A).ticketId()).isEqualTo("t-new");
        assertThat(f.ticket(A).state()).as("迟到的 gather 写不脏新票").isEqualTo(TicketState.QUEUED);
        assertThat(f.ticket(B).state()).isEqualTo(TicketState.READY);
    }

    @Test
    void 成功后的补写失败只记日志_开局照常_钩子照调() {
        AtomicInteger writes = new AtomicInteger();
        f.placementPort = new DelegatingPlacements(f.placements) {
            @Override
            public boolean write(BattlePlacement placement) {
                boolean ok = super.write(placement);
                return writes.incrementAndGet() != 2 && ok;
            }
        };

        GatherResult result = f.pipeline().run(f.popped(ONE_V_ONE, 0, A, B));

        assertThat(result.ok()).isTrue();
        assertThat(writes.get()).isEqualTo(2);
        assertThat(f.hooks.started).hasSize(1);
        assertThat(f.placements.deletes).isEmpty();
    }

    // ================================================================ 第 1–2 步的失败：还没冻结任何人

    @Test
    void 发号失败_internal_没选节点没备战_凑单全员按原序回队首并带2秒退避() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.leaseValid = false;

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.ok()).isFalse();
        assertThat(result.outcome()).isEqualTo(GatherOutcome.INTERNAL);
        assertThat(result.battleId()).as("不许拿 0 之外的自造号顶替").isZero();
        assertThat(f.battleNodes.picks).isEmpty();
        assertThat(f.scene.calls).isEmpty();
        assertThat(f.hooks.beforePrepare).isEmpty();
        assertNoBattleSideEffects();
        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.ticket(A).notBeforeMs()).as("无肇事者：回队首的票 2 s 内不参与凑单").isEqualTo(T0 + 2_000);
        assertThat(f.ticket(B).notBeforeMs()).isEqualTo(T0 + 2_000);
        assertThat(f.tickets.calls).containsExactly("extendMatched([1001, 1002])", "requeueFront(3:0,[1001, 1002])");
        assertThat(f.count("xm.match.requeued", "reason", "gather_no_offender")).isEqualTo(2.0);
        assertThat(f.count("xm.match.requeued", "reason", "gather_offender")).isZero();
    }

    @Test
    void 没有可分配的battle节点_no_battle_node_已发号但没读时间没备战_全员回队首() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleNodes.set(GatherFixture.NODE_A.toBuilder().setAccepting(false).build());

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NO_BATTLE_NODE);
        assertThat(result.battleId()).isNotZero();
        assertThat(f.clock.reads()).as("期限在选到节点之后才算").isZero();
        assertThat(f.scene.calls).isEmpty();
        assertNoBattleSideEffects();
        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.ticket(A).notBeforeMs()).isEqualTo(T0 + 2_000);
    }

    @Test
    void battle目录读失败_同样是no_battle_node() {
        GatherPlan plan = f.solo(A);
        f.battleNodes.readFailed = true;

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NO_BATTLE_NODE);
        assertThat(f.tickets.ticketOf(A)).as("PVE_SOLO 失败删票").isEmpty();
        assertThat(f.tickets.calls).containsExactly("deleteGroup([1001])");
    }

    @Test
    void 读Redis时间失败_internal_此时还没冻结任何人() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.clock.faults.failNext("nowMs");

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.INTERNAL);
        assertThat(result.battleId()).isNotZero();
        assertThat(f.scene.calls).isEmpty();
        assertNoBattleSideEffects();
        assertRequeuedInOrder(QUEUE_1V1, A, B);
    }

    // ================================================================ 第 3 步的失败：肇事者 + 解冻

    @Test
    void 第二人没有位置_no_location_他是肇事者删票_已冻结的第一人解冻并回队首_不带退避() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.players.location(B, LocationStatus.MISSING);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NO_LOCATION);
        assertThat(result.battleId()).isNotZero();
        assertThat(f.events).containsExactly("hooks.beforePrepare", "scene.prepare:1001", "scene.cancel:1001");
        assertThat(f.scene.calls.get(1).cancel().getBattleId()).isEqualTo(result.battleId());
        assertThat(f.scene.frozen()).isEmpty();
        assertThat(f.tickets.ticketOf(B)).as("肇事者删票出局").isEmpty();
        assertRequeuedInOrder(QUEUE_1V1, A);
        assertThat(f.ticket(A).notBeforeMs()).as("有肇事者：幸存者不带退避").isZero();
        assertThat(f.tickets.calls).as("续期 → （取消）→ 删肇事者 → 回队首").containsExactly("extendMatched([1001, 1002])", "delete(1002)",
                "requeueFront(3:0,[1001])");
        assertNoBattleSideEffects();
        assertThat(f.count("xm.match.requeued", "reason", "gather_offender")).isEqualTo(1.0);
        assertThat(f.count("xm.match.requeued", "reason", "gather_no_offender")).isZero();
    }

    @Test
    void 位置是重连租约的成员_同样no_location() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.players.disconnected(A);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NO_LOCATION);
        assertThat(f.scene.calls).as("第一人就失败：后面的人不再备战").isEmpty();
        assertThat(f.tickets.ticketOf(A)).isEmpty();
        assertRequeuedInOrder(QUEUE_1V1, B);
    }

    @Test
    void scene明确拒绝第二人_prepare_failed_他不收取消_第一人收() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.scene.prepareTip(B, 1006);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.events).as("tip ≠ 0 零冻结痕迹：不给 B 发取消").containsExactly("hooks.beforePrepare", "scene.prepare:1001",
                "scene.prepare:1002", "scene.cancel:1001");
        assertThat(f.tickets.ticketOf(B)).isEmpty();
        assertRequeuedInOrder(QUEUE_1V1, A);
        assertNoBattleSideEffects();
    }

    @Test
    void NOT_HERE与OVERLOADED_都判肇事者且不收取消() {
        for (SceneBattleStatus status : List.of(SceneBattleStatus.SCENE_BATTLE_NOT_HERE, SceneBattleStatus.SCENE_BATTLE_OVERLOADED)) {
            GatherFixture fixture = new GatherFixture();
            GatherPlan plan = fixture.popped(ONE_V_ONE, 0, A, B);
            fixture.scene.prepareStatus(B, status);

            GatherResult result = fixture.pipeline().run(plan);

            assertThat(result.outcome()).as(status.name()).isEqualTo(GatherOutcome.PREPARE_FAILED);
            assertThat(fixture.scene.calls).extracting(FakeSceneBattle.Call::describe).as(status.name())
                    .containsExactly("prepare:1001", "prepare:1002", "cancel:1001");
            assertThat(fixture.tickets.ticketOf(B)).isEmpty();
            assertThat(fixture.ticket(A).state()).isEqualTo(TicketState.QUEUED);
        }
    }

    @Test
    void M14_备战传输失败的人也收取消_其实已冻结的他被解掉_续期按两人算() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.scene.prepareFails(B, () -> new TimeoutException("慢"), true);
        // 让回队首失败，好看到续期后的 TTL：(已冻结 1 + 结局不明 1) × 3 + 10 = 16 s
        f.tickets.faults.failNext("requeueFront");

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.events).containsExactly("hooks.beforePrepare", "scene.prepare:1001", "scene.prepare:1002", "scene.cancel:1001",
                "scene.cancel:1002");
        assertThat(f.scene.frozen()).as("结局不明的人也被解冻").isEmpty();
        assertThat(f.tickets.ticketOf(B)).as("他仍是肇事者").isEmpty();
        assertThat(f.ticket(A).state()).as("回队首失败：票留在 matched").isEqualTo(TicketState.MATCHED);
        assertThat(f.tickets.ttlMs(A)).isEqualTo(16_000);
        assertThat(f.tickets.queueMembers(QUEUE_1V1)).isEmpty();
    }

    @Test
    void M14_状态缺失UNSPECIFIED按结局不明_也收取消() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.scene.prepareStatus(B, SceneBattleStatus.SCENE_BATTLE_STATUS_UNSPECIFIED);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002", "cancel:1001",
                "cancel:1002");
    }

    @Test
    void M14_备战超时按结局不明_超时的那位收取消_冻结被解掉() {
        f.sceneTimeout = Duration.ofMillis(80);
        GatherPlan plan = f.solo(A);
        f.scene.prepareHangs(A);

        long started = System.nanoTime();
        GatherResult result = f.pipeline().run(plan);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000);
        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "cancel:1001");
        assertThat(f.scene.frozen()).isEmpty();
        assertThat(f.tickets.ticketOf(A)).isEmpty();
    }

    @Test
    void 取消发回备战时的scene节点_位置已变成重连租约也照发() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        // A 备战成功之后掉线（Java 断线即移除实体，位置变成重连租约）：掉线的时刻放在 B 的备战脚本里，正好在 A 备战之后、补偿之前
        f.scene.prepareFails(B, () -> {
            f.players.disconnected(A);
            return new IllegalStateException("连接断开");
        }, false);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.players.location(A, GatherFixture.d()).status()).isEqualTo(LocationStatus.RECONNECT_LEASE);
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002", "cancel:1001",
                "cancel:1002");
        assertThat(f.sceneCalls.calls.get(2).target()).as("A 的取消发往他备战时的端点").isEqualTo(GatherFixture.SCENE_TARGET);
        assertThat(f.sceneNodes.lookups).as("只有两次备战各定位一次：取消不重新定位").containsExactly("1:7", "1:7");
        assertThat(f.scene.frozen()).as("按位置重新解析会找不到持有者而漏发，A 就解不掉了").isEmpty();
    }

    @Test
    void 取消失败只记日志_后面的人照常取消_票据照常处置() {
        GatherPlan plan = f.popped(FIVE_V_FIVE, 0, 2001, 2002, 2003, 2004, 2005, 2006, 2007, 2008, 2009, 2010);
        f.scene.prepareTip(2004, 1006);
        f.scene.cancelFails(2001, () -> new IllegalStateException("断连"));

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:2001", "prepare:2002", "prepare:2003",
                "prepare:2004", "cancel:2001", "cancel:2002", "cancel:2003");
        assertThat(f.scene.frozen()).as("2001 的取消失败：留给 scene 的 reaper").containsOnlyKeys(2001L);
        assertThat(f.tickets.ticketOf(2004)).isEmpty();
        assertThat(f.tickets.queueMembers(new QueueRef(FIVE_V_FIVE, 0))).as("其余 9 人按原相对顺序回队首")
                .containsExactly("2001", "2002", "2003", "2005", "2006", "2007", "2008", "2009", "2010");
    }

    @Test
    void 补偿前的续期_TTL是要发取消的人数乘3加10秒() {
        GatherPlan plan = f.popped(FIVE_V_FIVE, 0, 2001, 2002, 2003, 2004, 2005, 2006, 2007, 2008, 2009, 2010);
        f.scene.prepareTip(2004, 1006);
        f.tickets.faults.failNext("requeueFront");

        f.pipeline().run(plan);

        // 已冻结 3 人（2001–2003），2004 是明确拒绝、不算：3 × 3 + 10 = 19 s
        assertThat(f.tickets.ttlMs(2001)).isEqualTo(19_000);
        assertThat(f.tickets.ttlMs(2010)).as("还没轮到备战的人的票同样续期").isEqualTo(19_000);
    }

    // ================================================================ 指纹

    @Test
    void 指纹不一致_enforce_fingerprint_mismatch_全员解冻_少数派删票_幸存者回队首() {
        f.fingerprintMode = FingerprintMode.ENFORCE;
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.scene.fingerprint(A, "fp-A").fingerprint(B, "fp-B");

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.FINGERPRINT_MISMATCH);
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002", "cancel:1001",
                "cancel:1002");
        assertThat(f.scene.frozen()).isEmpty();
        assertThat(f.tickets.ticketOf(B)).as("两人平票，多数派取名单靠前的 A：B 是肇事者").isEmpty();
        assertRequeuedInOrder(QUEUE_1V1, A);
        assertNoBattleSideEffects();
        assertThat(f.count("xm.match.table.fingerprint.mismatches", "fp_mode", "enforce")).isEqualTo(1.0);
        assertThat(f.count("xm.match.table.fingerprint.mismatches", "fp_mode", "warn")).isZero();
    }

    @Test
    void 指纹不一致_warn_照常开局_不透传任何一方的指纹_计一次() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.scene.fingerprint(A, "fp-A").fingerprint(B, "fp-B");

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(f.battleA.creates.get(0).getTableFingerprint()).isEmpty();
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002");
        assertThat(f.count("xm.match.table.fingerprint.mismatches", "fp_mode", "warn")).isEqualTo(1.0);
        assertThat(f.count("xm.match.table.fingerprint.mismatches", "fp_mode", "enforce")).isZero();
    }

    @Test
    void 部分成员没回报指纹_warn下同样算不一致_不透传() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.scene.fingerprint(B, "");

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(f.battleA.creates.get(0).getTableFingerprint()).isEmpty();
        assertThat(f.count("xm.match.table.fingerprint.mismatches", "fp_mode", "warn")).isEqualTo(1.0);
    }

    @Test
    void 指纹闸off_一致也不透传_不一致也不计数() {
        f.fingerprintMode = FingerprintMode.OFF;
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(f.battleA.creates.get(0).getTableFingerprint()).isEmpty();
        assertThat(f.count("xm.match.table.fingerprint.mismatches", "fp_mode", "warn")).isZero();
        assertThat(f.count("xm.match.table.fingerprint.mismatches", "fp_mode", "enforce")).isZero();
    }

    // ================================================================ 第 4 步：种子、落点

    @Test
    void 种子生成失败_internal_全员解冻_没有肇事者_全员回队首带退避_没写过落点() {
        f.seeds = () -> {
            throw new IllegalStateException("熵源不可用");
        };
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.INTERNAL);
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002", "cancel:1001",
                "cancel:1002");
        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.ticket(A).notBeforeMs()).isEqualTo(T0 + 2_000);
        assertNoBattleSideEffects();
    }

    @Test
    void 全员备战后读created_at的时间失败_internal_全员解冻() {
        AtomicInteger reads = new AtomicInteger();
        RedisClock failingSecondRead = d -> {
            if (reads.incrementAndGet() == 2) {
                throw new Deadline.DependencyException("注入的故障: 第二次读时间");
            }
            return T0;
        };
        f.clockPort = failingSecondRead;
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.INTERNAL);
        assertThat(f.scene.frozen()).isEmpty();
        assertNoBattleSideEffects();
        assertRequeuedInOrder(QUEUE_1V1, A, B);
    }

    @Test
    void 落点记录写不进去_index_failed_不建房_全员解冻回队首_补偿之后删记录() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.placements.failWrites = 1;

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.INDEX_FAILED);
        long battleId = result.battleId();
        assertThat(f.battleA.creates).as("写不进去就不建房").isEmpty();
        assertThat(f.battleA.destroys).isEmpty();
        assertThat(f.events).containsExactly("hooks.beforePrepare", "scene.prepare:1001", "scene.prepare:1002",
                "placement.write-failed:" + id(battleId) + "#1", "scene.cancel:1001", "scene.cancel:1002", "placement.delete:" + id(battleId));
        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.ticket(A).notBeforeMs()).as("无肇事者").isEqualTo(T0 + 2_000);
        assertThat(f.hooks.started).isEmpty();
    }

    // ================================================================ 第 4.2–4.3 步：建房的各种结局

    @Test
    void 首选节点不可分配_换节点重试一次成功_记录改写到实际建房的节点_不发destroy() {
        f.addBattleB();
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleA.nextCreate(FakeBattleNode.notAllocatable("closed"));

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        long battleId = result.battleId();
        assertThat(f.battleA.creates).hasSize(1);
        assertThat(f.battleB.creates).hasSize(1);
        assertThat(f.battleB.creates.get(0)).as("重试沿用同一个请求").isEqualTo(f.battleA.creates.get(0));
        assertThat(f.battleA.destroys).isEmpty();
        assertThat(f.battleB.destroys).isEmpty();
        assertThat(f.battleNodes.picks).as("按 (节点号, 实例) 排除首选节点").containsExactly(Set.of(), Set.of("1#inst-a"));
        assertThat(f.battleCalls.calls).extracting(c -> c.target()).containsExactly(TARGET_A, TARGET_B);
        assertThat(f.placements.writes).extracting(BattlePlacement::getAttempt).containsExactly(1, 2, 2);
        BattlePlacement stored = f.placements.stored(battleId).orElseThrow();
        assertThat(stored.getBattleNodeId()).as("落点指向实际建房的节点，不是首选节点").isEqualTo(2);
        assertThat(stored.getBattleInstanceId()).isEqualTo("inst-b");
        assertThat(stored.getRpcPort()).isEqualTo(21201);
        assertThat(stored.getAttempt()).isEqualTo(2);
        assertThat(f.events).containsExactly("hooks.beforePrepare", "scene.prepare:1001", "scene.prepare:1002",
                "placement.write:" + id(battleId) + "#1", "battle[a].create:" + id(battleId), "placement.write:" + id(battleId) + "#2",
                "battle[b].create:" + id(battleId), "placement.write:" + id(battleId) + "#2", "hooks.onStarted");
        assertThat(f.hooks.started).containsExactly(stored);
        assertThat(f.scene.calls.get(0).prepare().getBattleNodeId()).as("备战里的节点号是首选节点（换节点后只作日志）").isEqualTo(1);
        assertThat(f.ticket(A).state()).isEqualTo(TicketState.READY);
        assertThat(f.scene.frozen()).containsOnlyKeys(A, B);
        assertThat(f.battleB.rooms()).containsKey(battleId);
        assertThat(f.battleA.rooms()).isEmpty();
    }

    @Test
    void 两个节点都不可分配_not_allocatable_零destroy_全员解冻回队首_删记录() {
        f.addBattleB();
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleA.nextCreate(FakeBattleNode.notAllocatable("closed"));
        f.battleB.nextCreate(FakeBattleNode.notAllocatable("overloaded"));

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NOT_ALLOCATABLE);
        assertThat(f.battleA.creates).hasSize(1);
        assertThat(f.battleB.creates).hasSize(1);
        assertThat(f.battleA.destroys).as("节点级拒绝保证没建房").isEmpty();
        assertThat(f.battleB.destroys).isEmpty();
        assertThat(f.scene.frozen()).isEmpty();
        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.ticket(B).notBeforeMs()).isEqualTo(T0 + 2_000);
        assertThat(f.placements.deletes).containsExactly(result.battleId());
        assertThat(f.placements.stored(result.battleId())).isEmpty();
        assertThat(f.hooks.started).isEmpty();
    }

    @Test
    void 只有一个节点且不可分配_没有可换的_不回头再试同一个_切磋不碰票据() {
        GatherPlan plan = f.challenge(A, B);
        f.battleA.nextCreate(FakeBattleNode.notAllocatable("closed"));

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NOT_ALLOCATABLE);
        assertThat(f.battleA.creates).as("没有其他节点可换，不得回头再试同一个节点").hasSize(1);
        assertThat(f.battleA.destroys).isEmpty();
        assertThat(f.battleNodes.picks).containsExactly(Set.of(), Set.of("1#inst-a"));
        assertThat(f.placements.writes).extracting(BattlePlacement::getAttempt).containsExactly(1);
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002", "cancel:1001",
                "cancel:1002");
        assertThat(f.tickets.calls).as("切磋从未入队，补偿不得凭空写票").isEmpty();
        assertThat(f.tickets.ticketCount()).isZero();
        assertThat(f.placements.deletes).containsExactly(result.battleId());
    }

    @Test
    void 三个节点都不可分配_只重试一次_共两次建房() {
        f.addBattleB();
        FakeBattleNode battleC = new FakeBattleNode("c", f.events);
        f.battleNodes.add(FakeBattleNodes.node(3, "inst-c", 21202));
        f.battleCalls.register(new com.game.api.rpc.NodeRpcClients.Target("127.0.0.1", 21202, "inst-c"), battleC);
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleA.nextCreate(FakeBattleNode.notAllocatable("closed"));
        f.battleB.nextCreate(FakeBattleNode.notAllocatable("closed"));

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NOT_ALLOCATABLE);
        assertThat(f.battleA.creates.size() + f.battleB.creates.size() + battleC.creates.size()).isEqualTo(2);
        assertThat(battleC.creates).as("不会把整个池试一遍").isEmpty();
        assertThat(f.battleNodes.picks).hasSize(2);
    }

    @Test
    void 重试节点结局不明_只destroy重试节点_确认销毁后create_failed() {
        f.addBattleB();
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleA.nextCreate(FakeBattleNode.notAllocatable("closed"));
        f.battleB.nextCreateFails(() -> new TimeoutException("DEADLINE_EXCEEDED"), true);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_FAILED);
        long battleId = result.battleId();
        assertThat(f.battleA.destroys).as("首选节点是节点级拒绝，无副作用，不碰它").isEmpty();
        assertThat(f.battleB.destroys).singleElement().satisfies(destroy -> {
            assertThat(destroy.getBattleId()).isEqualTo(battleId);
            assertThat(destroy.getReason()).isEqualTo("gather_rollback");
        });
        assertThat(f.battleCalls.calls.get(2).target()).isEqualTo(TARGET_B);
        assertThat(f.battleCalls.calls.get(2).timeout()).as("destroy 3 s").isEqualTo(Duration.ofSeconds(3));
        assertThat(f.battleB.rooms()).as("其实已建成的房间被拆掉了").isEmpty();
        assertThat(f.events).containsSubsequence("battle[b].create:" + id(battleId), "battle[b].destroy:" + id(battleId), "scene.cancel:1001",
                "scene.cancel:1002", "placement.delete:" + id(battleId));
        assertRequeuedInOrder(QUEUE_1V1, A, B);
    }

    @Test
    void 换节点前改写落点记录失败_index_failed_不再建房() {
        f.addBattleB();
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleA.nextCreate(FakeBattleNode.notAllocatable("closed"));
        f.placements.failWriteOnAttempt = 2;

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.INDEX_FAILED);
        assertThat(f.battleB.creates).as("记录没改指过去就不在重试节点上建房").isEmpty();
        assertThat(f.battleA.destroys).isEmpty();
        assertThat(f.battleB.destroys).isEmpty();
        assertThat(f.scene.frozen()).isEmpty();
        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.placements.deletes).containsExactly(result.battleId());
        assertThat(f.placements.stored(result.battleId())).as("首写的记录在补偿之后删掉").isEmpty();
    }

    @Test
    void M15_battle明确拒绝建房_create_rejected_不发destroy_也不换节点_直接补偿() {
        f.addBattleB();
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleA.nextCreate(FakeBattleNode.rejected(1003));

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_REJECTED);
        assertThat(f.battleA.destroys).as("明确拒绝保证零副作用：不发 destroy（修基线 F-g2）").isEmpty();
        assertThat(f.battleB.creates).as("只有节点级拒绝才换节点").isEmpty();
        assertThat(f.battleNodes.picks).hasSize(1);
        assertThat(f.scene.frozen()).isEmpty();
        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.ticket(A).notBeforeMs()).as("无肇事者").isEqualTo(T0 + 2_000);
        assertThat(f.placements.deletes).containsExactly(result.battleId());
    }

    @Test
    void M15_明确拒绝且destroy本来会失败_也不会走进房间可能活着的分支() {
        GatherPlan plan = f.team(A, B);
        f.battleA.nextCreate(FakeBattleNode.rejected(1006));
        f.battleA.nextDestroyFails(() -> new IllegalStateException("断连"));

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_REJECTED);
        assertThat(f.battleA.destroys).isEmpty();
        assertThat(f.scene.frozen()).as("基线在这里会不解冻；Java 直接补偿").isEmpty();
        assertThat(f.tickets.ticketCount()).as("整队失败全员删票").isZero();
    }

    @Test
    void 准入字段缺失_按可能已建房_先destroy成功再补偿_create_failed() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleA.nextCreate(FakeBattleNode.unspecified());

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_FAILED);
        assertThat(f.battleA.destroys).hasSize(1);
        assertThat(f.events).containsSubsequence("battle[a].create:" + id(result.battleId()), "battle[a].destroy:" + id(result.battleId()),
                "scene.cancel:1001");
        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.placements.deletes).containsExactly(result.battleId());
    }

    @Test
    void 建房传输失败且房间其实已建成_destroy成功才解冻_create_failed() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleA.nextCreateFails(() -> new IllegalStateException("连接断开"), true);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_FAILED);
        assertThat(f.battleA.rooms()).isEmpty();
        assertThat(f.events).as("先 destroy，后解冻").containsSubsequence("battle[a].destroy:" + id(result.battleId()), "scene.cancel:1001",
                "scene.cancel:1002");
        assertThat(f.scene.frozen()).isEmpty();
        assertRequeuedInOrder(QUEUE_1V1, A, B);
    }

    @Test
    void 建房永不应答_到点按结局不明_destroy成功后create_failed() {
        f.timeouts = new GatherPipeline.Timeouts(Duration.ofMillis(80), Duration.ofMillis(80));
        GatherPlan plan = f.solo(A);
        f.battleA.nextCreateHangs();

        long started = System.nanoTime();
        GatherResult result = f.pipeline().run(plan);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(5_000);
        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_FAILED);
        assertThat(f.battleA.destroys).hasSize(1);
        assertThat(f.battleA.rooms()).isEmpty();
        assertThat(f.tickets.ticketOf(A)).isEmpty();
        assertThat(f.scene.frozen()).isEmpty();
    }

    @Test
    void 建房结局不明且destroy也失败_create_failed_room_alive_不解冻_不动票据_保留落点记录() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleA.nextCreateFails(() -> new TimeoutException("DEADLINE_EXCEEDED"), true);
        f.battleA.nextDestroyFails(() -> new IllegalStateException("断连"));

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.ok()).isFalse();
        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_FAILED_ROOM_ALIVE);
        long battleId = result.battleId();
        assertThat(f.battleA.destroys).hasSize(1);
        assertThat(f.battleA.rooms()).as("房间真的还活着").containsKey(battleId);
        // 不解冻
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002");
        assertThat(f.scene.frozen()).containsOnlyKeys(A, B);
        // 不动票据：没有续期、没有删、没有回队首、也没有置 ready
        assertThat(f.tickets.calls).isEmpty();
        for (long playerId : new long[] {A, B}) {
            assertThat(f.ticket(playerId).state()).isEqualTo(TicketState.MATCHED);
            assertThat(f.tickets.ttlMs(playerId)).as("留在 matched 等 TTL（48 s）").isEqualTo(48_000);
        }
        assertThat(f.tickets.queueMembers(QUEUE_1V1)).isEmpty();
        // 保留落点记录：房间若真活着，丢票补签靠它回到本局
        assertThat(f.placements.deletes).isEmpty();
        assertThat(f.placements.stored(battleId)).isPresent();
        assertThat(f.hooks.started).as("失败路径不调 onStarted").isEmpty();
        assertThat(f.events).doesNotContain("scene.cancel:1001", "scene.cancel:1002", "placement.delete:" + id(battleId));
    }

    @Test
    void 房间可能活着这个出口对整队与切磋同样不解冻不动票() {
        GatherPlan plan = f.team(A, B);
        f.battleA.nextCreate(FakeBattleNode.unspecified());
        f.battleA.nextDestroyFails(() -> new TimeoutException("慢"));

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_FAILED_ROOM_ALIVE);
        assertThat(f.tickets.calls).isEmpty();
        assertThat(f.ticket(A).state()).isEqualTo(TicketState.MATCHED);
        assertThat(f.scene.frozen()).containsOnlyKeys(A, B);
        assertThat(f.placements.stored(result.battleId())).isPresent();
    }

    // ================================================================ 各入口的票据策略

    @Test
    void 整队开战失败_全员按票号删票_不回队列_不续期() {
        GatherPlan plan = f.team(A, B, 1003);
        f.scene.prepareTip(B, 1006);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.tickets.calls).as("不是凑单入口：不续期、不回队首").containsExactly("deleteGroup([1001, 1002, 1003])");
        assertThat(f.tickets.ticketCount()).as("没出错的人也删票：整队不可拆分").isZero();
        assertThat(f.tickets.queueMembers(new QueueRef(PVE_TEAM, 1))).isEmpty();
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002", "cancel:1001");
    }

    @Test
    void 活动开战失败_全员删票_预发的号原样回在结果里() {
        BattleActivityContext context = BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                .setGuildId(555).setInitiatorPlayerId(A).build();
        GatherPlan plan = f.activity(8_888_888L, context, A, B);
        f.battleA.nextCreate(FakeBattleNode.rejected(1002));

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_REJECTED);
        assertThat(result.battleId()).isEqualTo(8_888_888L);
        assertThat(f.tickets.ticketCount()).isZero();
        assertThat(f.tickets.calls).containsExactly("deleteGroup([1001, 1002])");
        assertThat(f.placements.deletes).containsExactly(8_888_888L);
    }

    @Test
    void PVE_SOLO失败_只删票_不推送不回队() {
        GatherPlan plan = f.solo(A);
        f.scene.prepareTip(A, 1006);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.tickets.ticketOf(A)).isEmpty();
        assertThat(f.tickets.calls).containsExactly("deleteGroup([1001])");
    }

    @Test
    void 切磋失败_解冻已冻结的人_不碰任何票据_哪怕他另有一张排队票() {
        GatherPlan plan = f.challenge(A, B);
        // A 正在 1V1 排队（切磋入口不检查排队票据，照搬基线）
        f.tickets.enqueue(A, "t-queue", QUEUE_1V1, 1, 150_000, QUEUED_TTL_MS, GatherFixture.d());
        f.tickets.calls.clear();
        f.scene.prepareTip(B, 1006);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002", "cancel:1001");
        assertThat(f.tickets.calls).isEmpty();
        assertThat(f.ticket(A).ticketId()).isEqualTo("t-queue");
        assertThat(f.ticket(A).state()).isEqualTo(TicketState.QUEUED);
    }

    @Test
    void 退避关闭时_无肇事者回队首的票不带not_before() {
        f.requeueBackoffMs = 0;
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.battleA.nextCreate(FakeBattleNode.rejected(1003));

        f.pipeline().run(plan);

        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.ticket(A).notBeforeMs()).isZero();
        assertThat(f.ticket(B).notBeforeMs()).isZero();
    }

    @Test
    void 回队首时队列里已有别人_幸存者排在原有成员之前且保持原相对顺序() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.tickets.enqueue(3001, "t-3001", QUEUE_1V1, 1, 150_000, QUEUED_TTL_MS, GatherFixture.d());
        f.battleA.nextCreate(FakeBattleNode.rejected(1003));

        f.pipeline().run(plan);

        assertThat(f.tickets.queueMembers(QUEUE_1V1)).containsExactly("1001", "1002", "3001");
    }

    @Test
    void 补偿里的票据写全部失败_不抛_结局照原样返回_解冻照做() {
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);
        f.scene.prepareTip(B, 1006);
        f.tickets.faults.failAlways("extendMatched").failAlways("delete").failAlways("requeueFront");

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(f.scene.frozen()).isEmpty();
        assertThat(f.ticket(A).state()).as("票据留在 matched，到期自灭").isEqualTo(TicketState.MATCHED);
        assertThat(f.ticket(B).state()).isEqualTo(TicketState.MATCHED);
        assertThat(f.tickets.calls).as("每一步都试过，失败只记日志").containsExactly("extendMatched([1001, 1002])", "delete(1002)",
                "requeueFront(3:0,[1001])");
    }

    // ================================================================ 意外异常的收敛

    @Test
    void 建房之前出现意外异常_按内部错误补偿_全员解冻_票据照策略处置() {
        f.placementPort = new DelegatingPlacements(f.placements) {
            @Override
            public boolean write(BattlePlacement placement) {
                throw new IllegalStateException("违反约定：write 不该抛");
            }
        };
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.INTERNAL);
        assertThat(result.battleId()).isNotZero();
        assertThat(f.battleA.creates).isEmpty();
        assertThat(f.scene.frozen()).isEmpty();
        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.placements.deletes).as("写可能已落盘：照删").containsExactly(result.battleId());
    }

    @Test
    void 房间已建成之后出现意外异常_仍按开局成功_不解冻() {
        AtomicInteger writes = new AtomicInteger();
        f.placementPort = new DelegatingPlacements(f.placements) {
            @Override
            public boolean write(BattlePlacement placement) {
                if (writes.incrementAndGet() == 2) {
                    throw new IllegalStateException("违反约定：补写时抛异常");
                }
                return super.write(placement);
            }
        };
        GatherPlan plan = f.popped(ONE_V_ONE, 0, A, B);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(f.battleA.rooms()).containsKey(result.battleId());
        assertThat(f.scene.frozen()).containsOnlyKeys(A, B);
        assertThat(f.scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "prepare:1002");
        assertThat(f.placements.deletes).isEmpty();
        assertThat(f.ticket(A).state()).isEqualTo(TicketState.READY);
    }

    @Test
    void 选节点时目录实现抛异常_没冻结任何人_按内部错误处置票据() {
        f.battleNodesPort = new BattleNodes() {
            @Override
            public java.util.Optional<com.game.api.proto.BattleNodeInfo> pickRandom(Set<String> excludeKeys) {
                throw new IllegalStateException("违反约定：pickRandom 不该抛");
            }

            @Override
            public Census census() {
                return new Census(0, 0, true);
            }

            @Override
            public Lookup lookup(int nodeId, String instanceId) {
                return Lookup.ERROR;
            }
        };
        GatherPlan plan = f.solo(A);

        GatherResult result = f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.INTERNAL);
        assertThat(f.scene.calls).isEmpty();
        assertThat(f.tickets.ticketOf(A)).isEmpty();
    }

    // ================================================================ 过载的收尾（许可本身由启动器测）

    @Test
    void 过载收尾_不发号不选节点不备战_凑单全员回队首带退避_其余入口删票_切磋无事() {
        GatherPipeline pipeline = f.pipeline();
        GatherPlan queued = f.popped(ONE_V_ONE, 0, A, B);
        GatherPlan solo = f.solo(1003);
        GatherPlan challenge = f.challenge(1004, 1005);

        GatherResult first = pipeline.overloaded(queued);
        GatherResult second = pipeline.overloaded(solo);
        GatherResult third = pipeline.overloaded(challenge);

        for (GatherResult result : List.of(first, second, third)) {
            assertThat(result.ok()).isFalse();
            assertThat(result.outcome()).isEqualTo(GatherOutcome.OVERLOADED);
            assertThat(result.battleId()).isZero();
        }
        assertThat(f.battleNodes.picks).isEmpty();
        assertThat(f.clock.reads()).isZero();
        assertThat(f.events).as("没有钩子、没有备战、没有落点").isEmpty();
        assertRequeuedInOrder(QUEUE_1V1, A, B);
        assertThat(f.ticket(A).notBeforeMs()).isEqualTo(T0 + 2_000);
        assertThat(f.tickets.ticketOf(1003)).isEmpty();
        assertThat(f.tickets.calls).containsExactly("extendMatched([1001, 1002])", "requeueFront(3:0,[1001, 1002])", "deleteGroup([1003])");
    }

    /** 落点存储的转发壳：测试覆写个别方法来表达「第 N 次调用失败 / 抛异常」。 */
    private static class DelegatingPlacements implements PlacementStore {

        private final PlacementStore delegate;

        DelegatingPlacements(PlacementStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean write(BattlePlacement placement) {
            return delegate.write(placement);
        }

        @Override
        public void delete(long battleId) {
            delegate.delete(battleId);
        }

        @Override
        public Read read(long battleId, Deadline d) {
            return delegate.read(battleId, d);
        }
    }
}
