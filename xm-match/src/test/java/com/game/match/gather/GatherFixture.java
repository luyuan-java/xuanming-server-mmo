package com.game.match.gather;

import com.game.api.BattleNodeService;
import com.game.api.SceneBattleService;
import com.game.api.match.MatchBudgets;
import com.game.api.proto.BattleNodeInfo;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.deadline.Deadline;
import com.game.common.id.Snowflake;
import com.game.discovery.location.SceneAssetLocator;
import com.game.match.id.MatchIds;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.placement.PlacementStore;
import com.game.match.port.RedisClock;
import com.game.match.testing.FakeBattleNode;
import com.game.match.testing.FakeBattleNodes;
import com.game.match.testing.FakeNodeCalls;
import com.game.match.testing.FakePlayerStatus;
import com.game.match.testing.FakeSceneBattle;
import com.game.match.testing.FakeSceneNodes;
import com.game.match.testing.FixedRatingReader;
import com.game.match.testing.InMemoryPlacementStore;
import com.game.match.testing.InMemoryTicketStore;
import com.game.match.testing.ManualRedisClock;
import com.game.match.testing.RecordingGatherHooks;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.Ticket;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketStore;
import com.game.proto.BattleActivityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 开局管线组件测试的台子：把 C0 的全部替身接成一条完整的管线——一个 scene 节点（zone 1 的 7 号，{@code scene-inst-a}）、battle 节点 A
 * （1 号，{@code inst-a}，已登记进目录；B 是 2 号，要用时 {@link #addBattleB()}）、内存票据与落点、手拨的 Redis 时间。
 * {@code events} 是 scene / battle / 落点 / 钩子共用的一条事件序列，断言跨组件的先后顺序用它；票据的调用序列在 {@code tickets.calls}。
 *
 * <p>公开字段里以 {@code Port} 结尾的是「交给管线的那一个」，缺省就是对应的替身；测试要注入「第 N 次调用失败」这类替身表达不了的行为时换掉它，
 * 然后再调 {@link #pipeline()}。
 */
final class GatherFixture {

    static final long SEED = 0x5EED_5EED_5EEDL;
    static final long QUEUED_TTL_MS = TimeUnit.HOURS.toMillis(6);
    static final long READY_TTL_MS = 60_000;
    static final int ONE_V_ONE = 3;
    static final int FIVE_V_FIVE = 1;
    static final int PVE_TEAM = 5;
    static final int PVE_SOLO = 4;
    static final NodeRpcClients.Target SCENE_TARGET = new NodeRpcClients.Target("127.0.0.1", 21100, "scene-inst-a");
    static final BattleNodeInfo NODE_A = FakeBattleNodes.node(1, "inst-a", 21200);
    static final BattleNodeInfo NODE_B = FakeBattleNodes.node(2, "inst-b", 21201);
    static final NodeRpcClients.Target TARGET_A = new NodeRpcClients.Target("127.0.0.1", 21200, "inst-a");
    static final NodeRpcClients.Target TARGET_B = new NodeRpcClients.Target("127.0.0.1", 21201, "inst-b");

    final List<String> events = new CopyOnWriteArrayList<>();
    final ManualRedisClock clock = new ManualRedisClock();
    final InMemoryTicketStore tickets = new InMemoryTicketStore(clock);
    final FakePlayerStatus players = new FakePlayerStatus();
    final FakeSceneNodes sceneNodes = new FakeSceneNodes();
    final FakeNodeCalls<SceneBattleService> sceneCalls = new FakeNodeCalls<>();
    final FakeSceneBattle scene = new FakeSceneBattle("scene-inst-a", events);
    final FakeBattleNodes battleNodes = new FakeBattleNodes();
    final FakeNodeCalls<BattleNodeService> battleCalls = new FakeNodeCalls<>();
    final FakeBattleNode battleA = new FakeBattleNode("a", events);
    final FakeBattleNode battleB = new FakeBattleNode("b", events);
    final InMemoryPlacementStore placements = new InMemoryPlacementStore(events);
    final RecordingGatherHooks hooks = new RecordingGatherHooks(events);
    final FixedRatingReader ratings = new FixedRatingReader();
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> id == 1));

    // ---- 可调：改完再调 pipeline()
    volatile boolean leaseValid = true;
    FingerprintMode fingerprintMode = FingerprintMode.WARN;
    LongSupplier seeds = () -> SEED;
    RedisClock clockPort = clock;
    PlacementStore placementPort = placements;
    GatherHooks hooksPort = hooks;
    BattleNodes battleNodesPort = battleNodes;
    TicketStore ticketPort = tickets;
    GatherPipeline.Timeouts timeouts = GatherPipeline.Timeouts.PRODUCTION;
    Duration sceneTimeout = Duration.ofMillis(MatchBudgets.PREPARE_BATTLE_TIMEOUT_MS);
    long requeueBackoffMs = 2_000;

    GatherFixture() {
        sceneNodes.add(1, 7, "scene-inst-a", 21100);
        sceneCalls.register(SCENE_TARGET, scene);
        battleNodes.add(NODE_A);
        battleCalls.register(TARGET_A, battleA);
    }

    /** 把 battle 节点 B 也登记进目录（换节点重试的用例）。 */
    GatherFixture addBattleB() {
        battleNodes.add(NODE_B);
        battleCalls.register(TARGET_B, battleB);
        return this;
    }

    ScenePreparer scenePreparer() {
        return new ScenePreparer(new SceneAssetLocator(players::holderAsync, sceneNodes, null), sceneCalls, sceneTimeout, sceneTimeout);
    }

    /** 按当前的可调项组一条管线。 */
    GatherPipeline pipeline() {
        ScenePreparer scenes = scenePreparer();
        Compensation compensation = new Compensation(ticketPort, scenes, placementPort, metrics, QUEUED_TTL_MS, requeueBackoffMs);
        MatchIds ids = new MatchIds(new Snowflake(7), () -> leaseValid, () -> false);
        return new GatherPipeline(new GatherPipeline.Parts(ids, battleNodesPort, clockPort, hooksPort, ratings, scenes, battleCalls, placementPort,
                ticketPort, compensation, metrics), fingerprintMode, READY_TTL_MS, seeds, timeouts);
    }

    static Deadline d() {
        return Deadline.after(2_000);
    }

    static String ticketId(long playerId) {
        return "t-" + playerId;
    }

    /** 这些玩家在线：位置在 zone 1 的 7 号 scene 节点。 */
    GatherFixture online(long... playerIds) {
        for (long playerId : playerIds) {
            players.online(playerId, 1, 7);
        }
        return this;
    }

    private static Map<Long, String> ticketsOf(long... playerIds) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (long playerId : playerIds) {
            out.put(playerId, ticketId(playerId));
        }
        return out;
    }

    private static List<Long> listOf(long... playerIds) {
        List<Long> out = new ArrayList<>(playerIds.length);
        for (long playerId : playerIds) {
            out.add(playerId);
        }
        return out;
    }

    /**
     * 凑单弹组之后的状态：这些人依次入队、再被原子弹出（票是 matched、TTL 按人数、人已不在队列里），返回交给 gather 的 plan。
     * 全员先置为在线。
     */
    GatherPlan popped(int mode, int configId, long... playerIds) {
        online(playerIds);
        QueueRef queue = new QueueRef(mode, configId);
        List<TicketRef> refs = new ArrayList<>();
        for (long playerId : playerIds) {
            tickets.enqueue(playerId, ticketId(playerId), queue, 1, 150_000, QUEUED_TTL_MS, d());
            refs.add(new TicketRef(playerId, ticketId(playerId)));
        }
        tickets.pop(queue, "pop-" + playerIds[0], refs, TimeUnit.SECONDS.toMillis(MatchBudgets.matchedTicketTtlSeconds(playerIds.length)), d());
        tickets.calls.clear();
        return GatherPlan.popped(queue, listOf(playerIds), ticketsOf(playerIds));
    }

    /** PVE_SOLO：建票即 matched（42 s）。 */
    GatherPlan solo(long playerId) {
        online(playerId);
        tickets.createMatched(playerId, ticketId(playerId), PVE_SOLO, 1, 1, 150_000, 42_000, d());
        tickets.calls.clear();
        return GatherPlan.soloPve(1, playerId, ticketId(playerId));
    }

    /** 整队开战：原子建全员 matched 票。 */
    GatherPlan team(long... roster) {
        online(roster);
        createGroup(77, roster);
        return GatherPlan.team(1, listOf(roster), ticketsOf(roster));
    }

    /** 活动开战：预发的 battle_id 与活动上下文。 */
    GatherPlan activity(long presetBattleId, BattleActivityContext context, long... roster) {
        online(roster);
        createGroup(0, roster);
        return GatherPlan.activity(1, listOf(roster), ticketsOf(roster), presetBattleId, context);
    }

    private void createGroup(long teamId, long... roster) {
        List<TicketStore.GroupMember> members = new ArrayList<>();
        for (long playerId : roster) {
            members.add(new TicketStore.GroupMember(playerId, ticketId(playerId), 1));
        }
        tickets.createGroup(members, PVE_TEAM, 1, teamId, TimeUnit.SECONDS.toMillis(MatchBudgets.matchedTicketTtlSeconds(roster.length)), d());
        tickets.calls.clear();
    }

    /** 切磋：两人在线、没有票。 */
    GatherPlan challenge(long challengerId, long responderId) {
        online(challengerId, responderId);
        return GatherPlan.challenge(0, challengerId, responderId);
    }

    Ticket ticket(long playerId) {
        return tickets.ticketOf(playerId).orElseThrow(() -> new AssertionError("玩家没有票: " + playerId));
    }

    double count(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }
}
