package com.game.match.gather;

import static com.game.match.gather.GatherFixture.ONE_V_ONE;
import static com.game.match.gather.GatherFixture.QUEUED_TTL_MS;
import static com.game.match.gather.GatherFixture.TARGET_A;
import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.SceneBattleService;
import com.game.api.match.MatchBudgets;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.deadline.Deadline;
import com.game.discovery.battle.BattleRoutings;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.proto.PlayerPresence;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer;
import com.game.match.spectate.SpectateRules;
import com.game.match.spectate.SpectateStore;
import com.game.match.testing.FakeBattleNode;
import com.game.match.testing.FakeNodeCalls;
import com.game.match.testing.FakeObserverDialer;
import com.game.match.testing.InMemorySpectateStore;
import com.game.match.ticket.QueueRef;
import com.game.match.ticket.TicketRef;
import com.game.match.ticket.TicketState;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.CreateBattleRequest;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.TipInfoMessage;
import com.game.proto.match.BattleWatchSummary;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.InvalidProtocolBufferException;
import io.micrometer.core.instrument.Tag;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 跨区 1V1 在开局管线上的不变量（spectate-spec §2.7 的 Z1–Z3、Z11、Z12，§2.8 的同号节点碰撞，§10.3「CrossZoneGatherTest」）。
 *
 * <p><b>拓扑照着本机 {@code XM_ZONES=2} 切片摆</b>：zone 1 与 zone 2 <b>各有一台 1 号 scene</b>（实例与地址不同：21100 / 21110），
 * 各有一台 1 号 gate——节点号按 zone 租约，两个 zone 的第一台都会是 1 号；两台 1 号 gate 发出的第一个会话号也相同
 * （{@code session_id = 节点号 << 17 | 序号}）。所以用例里 A（zone 1）与 B（zone 2）的 {@code session_id}、{@code gate_node_id}、
 * {@code scene_node_id} <b>三个号全部相等</b>，能把两人分开的只有 zone 与实例号：任何只按节点号 / 会话号寻址的代码都会在这里串到另一个 zone。
 * battle 节点全服一份（作用域 0）。
 *
 * <p>管线、补偿、定位器都是真的（{@link GatherFixture}）；scene 用本类的 {@link ZoneScene}（像真 scene 一样拿<b>自己的</b> zone / 节点号 / 实例填快照路由，
 * 实例不符回 {@code NOT_HERE}）。{@link GatherFixture} 自带的那台 zone 1 的 7 号 scene 在这里被摘掉，全程不应收到任何调用。
 *
 * <p>已由 6.4 的用例覆盖、这里不重复的：{@code ScenePreparerTest#跨zone同节点号不串…}（单独的备战定位）、
 * {@code GatherPipelineTest#成员来自不同zone_zone组成记cross}（单台假 scene 改快照里的 zone）、
 * {@code MatcherPortedScenarios}（Z1：队列键不含 zone、跨 zone 照常凑单）。「位置 {@code l} → 本轮跳过、保留排队」是凑单校验的规则（M12），归 matcher 的单测。
 */
class CrossZoneGatherTest {

    private static final long A = 1001;
    private static final long B = 1002;
    /** 观众。 */
    private static final long C = 1003;
    private static final QueueRef QUEUE = new QueueRef(ONE_V_ONE, 1);
    /** 两个 zone 的 1 号 gate 发出的第一个会话号：{@code 1 << 17 | 1}。 */
    private static final int SAME_SESSION = (1 << 17) | 1;
    private static final NodeRpcClients.Target Z1_SCENE_1 = new NodeRpcClients.Target("127.0.0.1", 21100, "scene-z1-inst");
    private static final NodeRpcClients.Target Z2_SCENE_1 = new NodeRpcClients.Target("127.0.0.1", 21110, "scene-z2-inst");
    private static final NodeRpcClients.Target Z3_SCENE_1 = new NodeRpcClients.Target("127.0.0.1", 21120, "scene-z3-inst");

    /**
     * 一个 zone 的 1 号 scene 节点的战斗入口。快照路由照 {@code SceneNode.battleRouting} 的口径填：scene 节点号 / 实例 / zone 取本节点，
     * gate 节点号（恒 1）与实例取本 zone 的那台 gate，会话号恒为 {@link #SAME_SESSION}。
     */
    private static final class ZoneScene implements SceneBattleService {

        final int zoneId;
        final String instanceId;
        /** {@code "prepare:<pid>"} / {@code "cancel:<pid>"}，按到达顺序。 */
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<String> events;
        final Map<Long, Long> frozen = new ConcurrentHashMap<>();
        final Map<Long, String> fingerprints = new ConcurrentHashMap<>();
        /** 这名玩家的备战被明确拒绝（HANDLED + tip 1006，零冻结痕迹）。 */
        volatile long rejects;
        /** 这名玩家的备战结局不明：已冻结，但应答没回来（传输失败）。 */
        volatile long unknown;

        ZoneScene(int zoneId, String instanceId, List<String> events) {
            this.zoneId = zoneId;
            this.instanceId = instanceId;
            this.events = events;
        }

        BattleRouting routing() {
            return BattleRouting.newBuilder().setSessionId(SAME_SESSION).setGateNodeId(1).setGateInstanceId("gate-z" + zoneId + "-inst")
                    .setSceneNodeId(1).setSceneInstanceId(instanceId).setZoneId(zoneId).build();
        }

        @Override
        public CompletableFuture<SceneBattleReply> prepareBattle(SceneBattleCall call) {
            PrepareBattleRequest request;
            try {
                request = PrepareBattleRequest.parseFrom(call.getBody());
            } catch (InvalidProtocolBufferException e) {
                return CompletableFuture.failedFuture(e);
            }
            long playerId = request.getPlayerId();
            calls.add("prepare:" + playerId);
            events.add("scene[z" + zoneId + "].prepare:" + playerId);
            if (!instanceId.equals(call.getTargetInstanceId())) {
                return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_NOT_HERE).build());
            }
            if (playerId == rejects) {
                return CompletableFuture.completedFuture(handled(
                        PrepareBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(1006)).build()));
            }
            frozen.put(playerId, request.getBattleId());
            if (playerId == unknown) {
                return CompletableFuture.failedFuture(new IllegalStateException("连接断开（应答没回来）"));
            }
            String fingerprint = fingerprints.getOrDefault(playerId, "fp-1");
            BattlePlayerSnapshot snapshot = BattlePlayerSnapshot.newBuilder().setPlayerId(playerId).setPlayerName("角色" + playerId + "@z" + zoneId)
                    .setLevel(1).setMaxHealth(100).setTableFingerprint(fingerprint).setRouting(routing()).build();
            return CompletableFuture.completedFuture(handled(
                    PrepareBattleResponse.newBuilder().setSnapshot(snapshot).setTableFingerprint(fingerprint).build()));
        }

        @Override
        public CompletableFuture<SceneBattleReply> cancelBattlePrepare(SceneBattleCall call) {
            CancelBattlePrepareRequest request;
            try {
                request = CancelBattlePrepareRequest.parseFrom(call.getBody());
            } catch (InvalidProtocolBufferException e) {
                return CompletableFuture.failedFuture(e);
            }
            long playerId = request.getPlayerId();
            calls.add("cancel:" + playerId);
            events.add("scene[z" + zoneId + "].cancel:" + playerId);
            if (!instanceId.equals(call.getTargetInstanceId())) {
                return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_NOT_HERE).build());
            }
            frozen.remove(playerId, request.getBattleId());
            return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED).build());
        }

        @Override
        public CompletableFuture<SceneBattleReply> confirmBattle(SceneBattleCall call) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("match 不调 confirmBattle"));
        }

        @Override
        public CompletableFuture<SceneBattleReply> applySettlement(SceneBattleCall call) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("match 不调 applySettlement"));
        }

        private static SceneBattleReply handled(PrepareBattleResponse body) {
            return SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED).setBody(body.toByteString()).build();
        }
    }

    /** 一套两个 zone（外加备用的 zone 3）的台子：每个用例新建一套。 */
    private static final class Rig {

        final GatherFixture f = new GatherFixture();
        final ZoneScene z1 = new ZoneScene(1, "scene-z1-inst", f.events);
        final ZoneScene z2 = new ZoneScene(2, "scene-z2-inst", f.events);
        final ZoneScene z3 = new ZoneScene(3, "scene-z3-inst", f.events);

        Rig() {
            // 摘掉夹具自带的 zone 1 的 7 号节点；两个 zone 各登记一台 1 号（zone 1 的 1 号顶掉 21100 上原来的登记）
            f.sceneNodes.remove(1, 7);
            f.sceneNodes.add(1, 1, "scene-z1-inst", 21100);
            f.sceneNodes.add(2, 1, "scene-z2-inst", 21110);
            f.sceneCalls.register(Z1_SCENE_1, z1);
            f.sceneCalls.register(Z2_SCENE_1, z2);
        }

        /** zone 3 也有一台 1 号 scene（旅行的用例）。 */
        Rig withZone3() {
            f.sceneNodes.add(3, 1, "scene-z3-inst", 21120);
            f.sceneCalls.register(Z3_SCENE_1, z3);
            return this;
        }

        /** 玩家在某个 zone 的游戏里：位置在该 zone 的 1 号 scene，在线目录挂在该 zone 的 1 号 gate（会话号都是 {@link #SAME_SESSION}）。 */
        Rig inZone(long playerId, int zoneId) {
            f.players.presence(playerId, presence(playerId, zoneId));
            f.players.location(playerId, zoneId, 1);
            return this;
        }

        /**
         * 凑单弹组之后的状态：每个人按<b>各自入队时所在的 zone</b> 建票（票里的 zone 只作观测），再被原子弹出。
         *
         * @param zoneByPlayer 名单顺序（第一个是锚点）→ 入队时的 zone
         */
        GatherPlan popped(Map<Long, Integer> zoneByPlayer) {
            List<Long> members = new ArrayList<>(zoneByPlayer.keySet());
            List<TicketRef> refs = new ArrayList<>();
            Map<Long, String> ticketIds = new LinkedHashMap<>();
            zoneByPlayer.forEach((playerId, zoneId) -> {
                inZone(playerId, zoneId);
                f.tickets.enqueue(playerId, GatherFixture.ticketId(playerId), QUEUE, zoneId, 150_000, QUEUED_TTL_MS, GatherFixture.d());
                refs.add(new TicketRef(playerId, GatherFixture.ticketId(playerId)));
                ticketIds.put(playerId, GatherFixture.ticketId(playerId));
            });
            f.tickets.pop(QUEUE, "pop-" + members.get(0), refs, TimeUnit.SECONDS.toMillis(MatchBudgets.matchedTicketTtlSeconds(members.size())),
                    GatherFixture.d());
            f.tickets.calls.clear();
            return GatherPlan.popped(QUEUE, members, ticketIds);
        }

        /** A 在 zone 1、B 在 zone 2 排的队，A 是锚点。 */
        GatherPlan aInZone1bInZone2() {
            Map<Long, Integer> zones = new LinkedHashMap<>();
            zones.put(A, 1);
            zones.put(B, 2);
            return popped(zones);
        }

        List<NodeRpcClients.Target> sceneTargets() {
            return f.sceneCalls.calls.stream().map(FakeNodeCalls.Call::target).toList();
        }

        void assertFixtureSceneUntouched() {
            assertThat(f.scene.calls).as("夹具自带的 zone 1 的 7 号 scene 已摘掉，不该收到任何调用").isEmpty();
        }
    }

    private static PlayerPresence presence(long playerId, int zoneId) {
        return PlayerPresence.newBuilder().setPlayerId(playerId).setZoneId(zoneId).setGateNodeId(1).setGateInstanceId("gate-z" + zoneId + "-inst")
                .setSessionId(SAME_SESSION).setOwnerEpoch(1).build();
    }

    private static String id(long battleId) {
        return Long.toUnsignedString(battleId);
    }

    // ================================================================ Z3：备战按位置记录的 (zone, 节点号)

    @Test
    void 两个zone都有1号scene_备战各自打到自己zone的端点_快照zone是1和2_zone组成记cross() {
        Rig rig = new Rig();
        GatherPlan plan = rig.aInZone1bInZone2();

        GatherResult result = rig.f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        long battleId = result.battleId();
        // 定位：各按自己位置记录的 zone 查目录；备战：各打到自己 zone 的那台 1 号（地址与实例都不同）
        assertThat(rig.f.sceneNodes.lookups).containsExactly("1:1", "2:1");
        assertThat(rig.sceneTargets()).containsExactly(Z1_SCENE_1, Z2_SCENE_1);
        assertThat(rig.z1.calls).as("zone 1 的 1 号只收到 A").containsExactly("prepare:1001");
        assertThat(rig.z2.calls).as("zone 2 的 1 号只收到 B").containsExactly("prepare:1002");
        assertThat(rig.z1.frozen).containsOnlyKeys(A);
        assertThat(rig.z2.frozen).containsOnlyKeys(B);
        rig.assertFixtureSceneUntouched();

        // 建房请求：两份快照的路由原样带过去——三个号相等，zone 与两个实例号不同
        CreateBattleRequest create = rig.f.battleA.creates.get(0);
        assertThat(create.getPlayersList()).extracting(BattlePlayerSnapshot::getPlayerId).containsExactly(A, B);
        assertThat(create.getPlayersList()).extracting(BattlePlayerSnapshot::getTeamIndex).as("锚点 A 在 0 队").containsExactly(0, 1);
        BattleRouting routingA = create.getPlayers(0).getRouting();
        BattleRouting routingB = create.getPlayers(1).getRouting();
        assertThat(routingA).isEqualTo(BattleRouting.newBuilder().setSessionId(SAME_SESSION).setGateNodeId(1).setGateInstanceId("gate-z1-inst")
                .setSceneNodeId(1).setSceneInstanceId("scene-z1-inst").setZoneId(1).build());
        assertThat(routingB).isEqualTo(BattleRouting.newBuilder().setSessionId(SAME_SESSION).setGateNodeId(1).setGateInstanceId("gate-z2-inst")
                .setSceneNodeId(1).setSceneInstanceId("scene-z2-inst").setZoneId(2).build());
        assertThat(routingA.getSessionId()).as("同号碰撞：会话号、gate 节点号、scene 节点号在两个 zone 之间全部相等").isEqualTo(routingB.getSessionId());
        assertThat(rig.f.battleCalls.calls).singleElement().satisfies(call -> assertThat(call.target()).as("battle 节点全服一份").isEqualTo(TARGET_A));

        // Z11：zone 组成按快照的 routing.zone_id 计
        assertThat(rig.f.count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "cross")).isEqualTo(1.0);
        assertThat(rig.f.count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "single")).isZero();
        assertThat(rig.f.meters.find("xm.match.gather.zone.mix").counters()).as("标签只有 mode 与 mix：zone 的取值不进标签").isNotEmpty()
                .allSatisfy(counter -> assertThat(counter.getId().getTags()).extracting(Tag::getKey).containsExactlyInAnyOrder("mode", "mix"));

        // Z1：票据里的 zone 只作观测；两人同一条队列（键里没有 zone）
        assertThat(rig.f.ticket(A).zoneId()).isEqualTo(1);
        assertThat(rig.f.ticket(B).zoneId()).isEqualTo(2);
        assertThat(rig.f.ticket(A).queueKey()).isEqualTo("xm:{match}:queue:3:1").isEqualTo(rig.f.ticket(B).queueKey());
        assertThat(rig.f.ticket(A).state()).isEqualTo(TicketState.READY);
        assertThat(rig.f.ticket(B).state()).isEqualTo(TicketState.READY);
        assertThat(rig.f.ticket(B).battleId()).isEqualTo(battleId);

        // 跨组件次序与单 zone 时相同：全员备战（各在各的 zone）→ 写落点 → 建房 → 补写落点 → 钩子
        assertThat(rig.f.events).containsExactly("hooks.beforePrepare", "scene[z1].prepare:1001", "scene[z2].prepare:1002",
                "placement.write:" + id(battleId) + "#1", "battle[a].create:" + id(battleId), "placement.write:" + id(battleId) + "#1",
                "hooks.onStarted");
        assertThat(rig.f.placements.stored(battleId).orElseThrow().getPlayerNamesList()).as("落点里的名字取各自 zone 的快照，按成员顺序")
                .containsExactly("角色1001@z1", "角色1002@z2");
    }

    @Test
    void zone2的1号scene不在目录_不会退而求其次打到zone1的同号节点_B是no_location的肇事者() {
        Rig rig = new Rig();
        GatherPlan plan = rig.aInZone1bInZone2();
        rig.f.sceneNodes.remove(2, 1);

        GatherResult result = rig.f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NO_LOCATION);
        assertThat(rig.f.sceneNodes.lookups).as("B 按 zone 2 查，查不到就是查不到").containsExactly("1:1", "2:1");
        assertThat(rig.z1.calls).as("zone 1 的 1 号只见过 A：备战、然后解冻；B 的备战没有落到它头上").containsExactly("prepare:1001", "cancel:1001");
        assertThat(rig.z2.calls).isEmpty();
        assertThat(rig.z1.frozen).isEmpty();
        assertThat(rig.f.tickets.ticketOf(B)).as("肇事者删票出局").isEmpty();
        assertThat(rig.f.ticket(A).state()).isEqualTo(TicketState.QUEUED);
        assertThat(rig.f.tickets.queueMembers(QUEUE)).containsExactly("1001");
        assertThat(rig.f.battleA.creates).isEmpty();
    }

    @Test
    void zone2的scene目录读失败_同样是B的no_location_zone1的同号节点不受牵连() {
        Rig rig = new Rig();
        GatherPlan plan = rig.aInZone1bInZone2();
        rig.f.sceneNodes.failZone(2);

        GatherResult result = rig.f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.NO_LOCATION);
        assertThat(rig.z1.calls).containsExactly("prepare:1001", "cancel:1001");
        assertThat(rig.z2.calls).isEmpty();
        assertThat(rig.f.tickets.ticketOf(B)).isEmpty();
        assertThat(rig.f.tickets.queueMembers(QUEUE)).containsExactly("1001");
    }

    // ================================================================ Z3：取消发回备战时的端点

    @Test
    void 建房被拒_两人的取消各发回备战时的端点_此刻位置已换到对方zone的同号节点也不串() {
        Rig rig = new Rig();
        GatherPlan plan = rig.aInZone1bInZone2();
        rig.f.battleA.nextCreate(FakeBattleNode.rejected(1003));
        // 全员备战之后（第二次读 Redis 时间，取 created_at_ms 的那一次）、建房之前：两人的位置记录对调——各自指向「另一个 zone 的 1 号」。
        // 按位置记录重新解析的实现会把 A 的取消发给 zone 2 的 1 号、B 的发给 zone 1 的 1 号（实例对得上、玩家却不在那儿），两人都解不了冻
        AtomicInteger clockReads = new AtomicInteger();
        rig.f.clockPort = d -> {
            if (clockReads.incrementAndGet() == 2) {
                rig.f.players.location(A, 2, 1);
                rig.f.players.location(B, 1, 1);
            }
            return rig.f.clock.nowMs(d);
        };

        GatherResult result = rig.f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.CREATE_REJECTED);
        assertThat(clockReads.get()).as("位置确实是在备战之后、补偿之前对调的").isEqualTo(2);
        assertThat(rig.z1.calls).containsExactly("prepare:1001", "cancel:1001");
        assertThat(rig.z2.calls).containsExactly("prepare:1002", "cancel:1002");
        assertThat(rig.z1.frozen).as("A 在 zone 1 的 1 号上解冻了").isEmpty();
        assertThat(rig.z2.frozen).as("B 在 zone 2 的 1 号上解冻了").isEmpty();
        assertThat(rig.f.sceneCalls.remembered).as("取消经「记下来的目标」发：名单顺序，各回各的端点").extracting(FakeNodeCalls.Call::target)
                .containsExactly(Z1_SCENE_1, Z2_SCENE_1);
        assertThat(rig.f.sceneNodes.lookups).as("只有两次备战各查一次目录：取消不重新定位").containsExactly("1:1", "2:1");
        assertThat(rig.f.players.reads).as("位置记录也只读了两次").containsExactly("location:1001", "location:1002");
        assertThat(rig.f.events).containsExactly("hooks.beforePrepare", "scene[z1].prepare:1001", "scene[z2].prepare:1002",
                "placement.write:" + id(result.battleId()) + "#1", "battle[a].create:" + id(result.battleId()), "scene[z1].cancel:1001",
                "scene[z2].cancel:1002", "placement.delete:" + id(result.battleId()));
        // 没有肇事者：两人按原序回队首，仍是同一条队列
        assertThat(rig.f.tickets.queueMembers(QUEUE)).containsExactly("1001", "1002");
        assertThat(rig.f.ticket(A).state()).isEqualTo(TicketState.QUEUED);
        assertThat(rig.f.ticket(B).state()).isEqualTo(TicketState.QUEUED);
    }

    @Test
    void zone2的备战结局不明_B的取消发回zone2的1号_A的发回zone1的1号_明确拒绝时zone2不收取消() {
        Rig unknown = new Rig();
        GatherPlan plan = unknown.aInZone1bInZone2();
        unknown.z2.unknown = B;

        GatherResult result = unknown.f.pipeline().run(plan);

        assertThat(result.outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(unknown.z1.calls).containsExactly("prepare:1001", "cancel:1001");
        assertThat(unknown.z2.calls).as("结局不明的人也收取消（M14），发到他备战时的那台").containsExactly("prepare:1002", "cancel:1002");
        assertThat(unknown.z2.frozen).as("其实已经冻结上的 B 被解掉了").isEmpty();
        assertThat(unknown.z1.frozen).isEmpty();
        assertThat(unknown.f.tickets.ticketOf(B)).isEmpty();
        assertThat(unknown.f.tickets.queueMembers(QUEUE)).containsExactly("1001");

        Rig rejected = new Rig();
        GatherPlan second = rejected.aInZone1bInZone2();
        rejected.z2.rejects = B;

        assertThat(rejected.f.pipeline().run(second).outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(rejected.z1.calls).containsExactly("prepare:1001", "cancel:1001");
        assertThat(rejected.z2.calls).as("明确拒绝零冻结痕迹：zone 2 的 1 号不收任何取消（A 的取消更不会发到它这里）").containsExactly("prepare:1002");
    }

    // ================================================================ §2.8 第 3 条：排队之后换了 zone

    @Test
    void B排队之后旅行到zone3_gather重读位置_备战打到zone3_票据里的zone不改写() {
        Rig rig = new Rig().withZone3();
        GatherPlan plan = rig.aInZone1bInZone2();
        rig.inZone(B, 3);

        GatherResult result = rig.f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(rig.f.sceneNodes.lookups).containsExactly("1:1", "3:1");
        assertThat(rig.sceneTargets()).containsExactly(Z1_SCENE_1, Z3_SCENE_1);
        assertThat(rig.z2.calls).as("入队时所在的 zone 2 不再相干").isEmpty();
        assertThat(rig.z3.calls).containsExactly("prepare:1002");
        CreateBattleRequest create = rig.f.battleA.creates.get(0);
        assertThat(create.getPlayers(1).getRouting().getZoneId()).isEqualTo(3);
        assertThat(create.getPlayers(1).getRouting().getSceneInstanceId()).isEqualTo("scene-z3-inst");
        assertThat(rig.f.ticket(B).zoneId()).as("票据的 zone 是入队那一刻的观测值，gather 不回写").isEqualTo(2);
        assertThat(rig.f.count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "cross")).isEqualTo(1.0);
    }

    @Test
    void B排队之后旅行到A所在的zone1_两人落在同一台1号scene_zone组成按快照记single_不按票据里的zone() {
        Rig rig = new Rig();
        GatherPlan plan = rig.aInZone1bInZone2();
        rig.inZone(B, 1);

        GatherResult result = rig.f.pipeline().run(plan);

        assertThat(result.ok()).isTrue();
        assertThat(rig.z1.calls).containsExactly("prepare:1001", "prepare:1002");
        assertThat(rig.z2.calls).isEmpty();
        assertThat(rig.f.ticket(A).zoneId()).isEqualTo(1);
        assertThat(rig.f.ticket(B).zoneId()).as("票据还记着 zone 2").isEqualTo(2);
        assertThat(rig.f.count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "single")).as("Z11：按快照的 routing.zone_id")
                .isEqualTo(1.0);
        assertThat(rig.f.count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "cross")).isZero();
    }

    @Test
    void gather时B的位置不是在线_租约_登出墓碑_缺失_都是no_location_B出局_A在zone1解冻后回队首() {
        for (LocationStatus status : new LocationStatus[] {LocationStatus.RECONNECT_LEASE, LocationStatus.LOGGED_OUT, LocationStatus.MISSING}) {
            Rig rig = new Rig();
            GatherPlan plan = rig.aInZone1bInZone2();
            // 跨 zone 传送途中（旧 zone 已交出、新 zone 还没落点）位置就是这几种之一
            rig.f.players.location(B, status);

            GatherResult result = rig.f.pipeline().run(plan);

            assertThat(result.outcome()).as(status.name()).isEqualTo(GatherOutcome.NO_LOCATION);
            assertThat(rig.f.sceneNodes.lookups).as("%s：没有在线的位置记录就不查目录", status).containsExactly("1:1");
            assertThat(rig.z1.calls).as(status.name()).containsExactly("prepare:1001", "cancel:1001");
            assertThat(rig.z2.calls).as("%s：B 入队时所在的 zone 2 的 1 号不收任何调用", status).isEmpty();
            assertThat(rig.z1.frozen).as(status.name()).isEmpty();
            assertThat(rig.f.tickets.ticketOf(B)).as("%s：B 作为肇事者删票", status).isEmpty();
            assertThat(rig.f.ticket(A).state()).as(status.name()).isEqualTo(TicketState.QUEUED);
            assertThat(rig.f.ticket(A).notBeforeMs()).as("%s：有肇事者，幸存者不带退避", status).isZero();
            assertThat(rig.f.tickets.queueMembers(QUEUE)).as(status.name()).containsExactly("1001");
            assertThat(rig.f.battleA.creates).as(status.name()).isEmpty();
            assertThat(rig.f.count("xm.match.gather.zone.mix", "mode", "MATCH_MODE_1V1", "mix", "cross")).as("没走到全员冻结，不计 zone 组成").isZero();
        }
    }

    // ================================================================ Q28：两个 zone 的配表指纹不一致

    @Test
    void 两个zone的配表指纹不同_warn照常开局且不透传指纹_enforce时两人各在自己的zone解冻() {
        Rig warn = new Rig();
        GatherPlan plan = warn.aInZone1bInZone2();
        warn.z1.fingerprints.put(A, "fp-zone1");
        warn.z2.fingerprints.put(B, "fp-zone2");

        GatherResult result = warn.f.pipeline().run(plan);

        assertThat(result.ok()).as("缺省 warn：跨 zone 的指纹不一致不拦开局（跟 6.4 的口径）").isTrue();
        assertThat(warn.f.battleA.creates.get(0).getTableFingerprint()).as("不透传任何一方的指纹").isEmpty();
        assertThat(warn.f.count("xm.match.table.fingerprint.mismatches", "fp_mode", "warn")).isEqualTo(1.0);
        assertThat(warn.z1.calls).containsExactly("prepare:1001");
        assertThat(warn.z2.calls).containsExactly("prepare:1002");

        Rig enforce = new Rig();
        enforce.f.fingerprintMode = FingerprintMode.ENFORCE;
        GatherPlan second = enforce.aInZone1bInZone2();
        enforce.z1.fingerprints.put(A, "fp-zone1");
        enforce.z2.fingerprints.put(B, "fp-zone2");

        GatherResult refused = enforce.f.pipeline().run(second);

        assertThat(refused.outcome()).isEqualTo(GatherOutcome.FINGERPRINT_MISMATCH);
        assertThat(enforce.z1.calls).containsExactly("prepare:1001", "cancel:1001");
        assertThat(enforce.z2.calls).containsExactly("prepare:1002", "cancel:1002");
        assertThat(enforce.z1.frozen).isEmpty();
        assertThat(enforce.z2.frozen).isEmpty();
        assertThat(enforce.f.tickets.ticketOf(B)).as("两人平票，多数派取名单靠前的 A：B 是肇事者").isEmpty();
        assertThat(enforce.f.tickets.queueMembers(QUEUE)).containsExactly("1001");
        assertThat(enforce.f.battleA.creates).isEmpty();
    }

    // ================================================================ Z12：观战不分 zone

    /**
     * 这一条只走到 match 侧 163 的<b>输入</b>为止：开局管线写出的落点与公开的索引成员里没有 zone，另一个 zone 的观众读到的是同一条；
     * 观众的路由由在线目录换算（{@link BattleRoutings#gatePart}），发往落点记录的地址。163 的判定流程本身（{@code WatchBattleService}）
     * 由观战包自己的用例覆盖。
     */
    @Test
    void 参战者都在zone1_观众挂在zone2的1号gate_落点与索引不分zone_观众路由的zone取在线目录是2_发往落点地址() {
        Rig rig = new Rig();
        InMemorySpectateStore spectate = new InMemorySpectateStore(rig.f.clock, rig.f.tickets, rig.f.placements, rig.f.events);
        // 开局成功的钩子把这一场公开（真实现 = 观战包的开局钩子；这里只要「公开」这一个动作）
        rig.f.hooksPort = new GatherHooks() {
            @Override
            public void beforePrepare(List<Long> members) {
                rig.f.hooks.beforePrepare(members);
            }

            @Override
            public void onStarted(BattlePlacement placement) {
                rig.f.hooks.onStarted(placement);
                spectate.publish(placement, Deadline.after(1_000));
            }
        };
        Map<Long, Integer> bothInZone1 = new LinkedHashMap<>();
        bothInZone1.put(A, 1);
        bothInZone1.put(B, 1);
        long battleId = rig.f.pipeline().run(rig.popped(bothInZone1)).battleId();
        // 观众 C：在线目录挂在 zone 2 的 1 号 gate（会话号、gate 节点号与两名参战者全部相同）；位置记录却还指着 zone 1（传送途中两者可以不等）
        rig.f.players.presence(C, presence(C, 2));
        rig.f.players.location(C, 1, 1);

        // 落点记录与摘要里都没有 zone 字段：索引全服一份，不按观众所在的 zone 过滤
        assertThat(BattlePlacement.getDescriptor().getFields()).extracting(FieldDescriptor::getName).noneMatch(name -> name.contains("zone"));
        assertThat(BattleWatchSummary.getDescriptor().getFields()).extracting(FieldDescriptor::getName).noneMatch(name -> name.contains("zone"));
        SpectateStore.Snapshot snapshot = spectate.read(battleId, Deadline.after(1_000));
        assertThat(snapshot.published()).isTrue();
        BattlePlacement placement = ((SpectateStore.Record.Found) snapshot.record()).placement();
        assertThat(spectate.pickRandom(0.0, Deadline.after(1_000))).as("随机观战挑得到它")
                .isEqualTo(new SpectateStore.Pick.Member(SpectateRules.member(battleId), placement.getCreatedAtMs(), rig.f.clock.peekMs()));
        assertThat(SpectateRules.summaryOf(placement).getPlayerNamesList()).containsExactly("角色1001@z1", "角色1002@z1");

        // 观众路由：四个 gate 字段取在线目录，zone 是 gate 所在的 2，不是位置记录的 1，也不是参战者的 1
        BattleRouting routing = BattleRoutings.gatePart(rig.f.players.presence(C, Deadline.after(1_000)).orElseThrow());
        FakeObserverDialer dialer = new FakeObserverDialer(rig.f.events);
        ObserverDialer.Outcome outcome = dialer.add(placement, AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(C)
                .setRouting(routing).setObserverName("account-c").build(), Duration.ofMillis(MatchBudgets.ADD_OBSERVER_TIMEOUT_MS),
                Deadline.after(4_000));

        assertThat(outcome).isEqualTo(new ObserverDialer.Outcome.Replied(0));
        FakeObserverDialer.Call add = dialer.adds().get(0);
        assertThat(add.placement().getRpcHost() + ":" + add.placement().getRpcPort()).as("直拨落点记录的地址：就是参战者建房的那台 battle")
                .isEqualTo(TARGET_A.address());
        assertThat(add.placement().getBattleInstanceId()).isEqualTo(TARGET_A.instanceId());
        assertThat(add.request().getRouting()).isEqualTo(BattleRouting.newBuilder().setSessionId(SAME_SESSION).setGateNodeId(1)
                .setGateInstanceId("gate-z2-inst").setZoneId(2).build());
        BattleRouting participant = rig.f.battleA.creates.get(0).getPlayers(0).getRouting();
        assertThat(add.request().getRouting().getSessionId()).as("与参战者 A 的会话号、gate 节点号都相同").isEqualTo(participant.getSessionId());
        assertThat(add.request().getRouting().getGateNodeId()).isEqualTo(participant.getGateNodeId());
        assertThat(add.request().getRouting().getGateInstanceId()).as("分得开两人的只有 zone 与 gate 实例").isNotEqualTo(participant.getGateInstanceId());
        assertThat(add.request().getRouting().getZoneId()).isNotEqualTo(participant.getZoneId());
        assertThat(rig.f.events).as("先公开、后才能被观战").containsSubsequence("hooks.onStarted", "spectate.publish:" + id(battleId),
                "spectate.read:" + id(battleId), "observer.add:" + id(battleId) + ":1003");
    }
}
