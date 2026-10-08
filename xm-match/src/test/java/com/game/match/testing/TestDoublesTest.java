package com.game.match.testing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.BattleNodeService;
import com.game.api.SceneBattleService;
import com.game.api.proto.BattleAdmission;
import com.game.api.proto.BattleNodeInfo;
import com.game.api.proto.CreateBattleResult;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.deadline.Deadline;
import com.game.discovery.location.PlayerLocationDirectory.LocationStatus;
import com.game.discovery.location.SceneAssetLocator;
import com.game.discovery.presence.PlayerPushes;
import com.game.match.gather.BattleNodes;
import com.game.match.gather.GatherOutcome;
import com.game.match.gather.GatherPlan;
import com.game.match.gather.GatherResult;
import com.game.match.placement.PlacementDialer;
import com.game.match.placement.PlacementStore;
import com.game.match.precheck.MemberPrecheck;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer;
import com.game.match.spectate.SpectateRules;
import com.game.match.rating.RatingReader;
import com.game.match.ticket.TicketHealing;
import com.game.proto.AddObserverRequest;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.BattleRouting;
import com.game.proto.CancelBattlePrepareRequest;
import com.game.proto.CreateBattleRequest;
import com.game.proto.CreateBattleResponse;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.MessageContent;
import com.game.proto.PrepareBattleRequest;
import com.game.proto.PrepareBattleResponse;
import com.game.proto.match.ChallengeResultS2C;
import java.net.ConnectException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * 其余测试替身各自的行为（票据存储见 {@link InMemoryTicketStoreTest}，观战存储见 {@link InMemorySpectateStoreTest}）：每个替身的缺省行为、脚本化、故障注入与调用记录各钉一例——
 * 别的包的组件测试建在它们上面，替身自己错了，上面的用例就都白测。
 */
class TestDoublesTest {

    private static Deadline d() {
        return Deadline.after(1000);
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(2, TimeUnit.SECONDS);
    }

    // ================================================================ 时钟、评分、自愈、预检

    @Test
    void 手拨时钟_拨多少走多少_记读的次数_可注入失败() {
        ManualRedisClock clock = new ManualRedisClock(1_000);

        assertThat(clock.nowMs(d())).isEqualTo(1_000);
        clock.advanceSeconds(45).advanceMs(7);
        assertThat(clock.nowMs(d())).isEqualTo(46_007);
        assertThat(clock.peekMs()).isEqualTo(46_007);
        assertThat(clock.reads()).as("peek 不计").isEqualTo(2);
        clock.faults.failNext("nowMs");
        assertThatThrownBy(() -> clock.nowMs(d())).isInstanceOf(Deadline.DependencyException.class);
        assertThat(clock.set(5).nowMs(d())).isEqualTo(5);
    }

    @Test
    void 固定评分_没预置的是缺省1500_批量覆盖每个入参_记下读了谁() {
        FixedRatingReader ratings = new FixedRatingReader().set(1001, 162_500);

        assertThat(ratings.loadCentiOrDefault(1001)).isEqualTo(162_500);
        assertThat(ratings.loadCentiOrDefault(1002)).isEqualTo(RatingReader.DEFAULT_CENTI).isEqualTo(150_000);
        assertThat(ratings.loadAllCentiOrDefault(List.of(1002L, 1001L))).containsExactly(Map.entry(1002L, 150_000L), Map.entry(1001L, 162_500L));
        assertThat(ratings.loads).containsExactly(List.of(1001L), List.of(1002L), List.of(1002L, 1001L));
    }

    @Test
    void 假自愈_缺省Free_可预置在途与失败_记查的次序() {
        FakeTicketHealing healing = new FakeTicketHealing().inFlight(1002, "t-1002").failFor(1003);

        assertThat(healing.healOrBlock(1001, d())).isEqualTo(new TicketHealing.Free());
        assertThat(healing.healOrBlock(1002, d())).isEqualTo(new TicketHealing.InFlight("t-1002"));
        assertThatThrownBy(() -> healing.healOrBlock(1003, d())).isInstanceOf(Deadline.DependencyException.class);
        healing.faults.failNext("healOrBlock");
        assertThatThrownBy(() -> healing.healOrBlock(1001, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThat(healing.free(1002).healOrBlock(1002, d())).isEqualTo(new TicketHealing.Free());
        assertThat(healing.calls).containsExactly(1001L, 1002L, 1003L, 1001L, 1002L);
    }

    @Test
    void 假预检_缺省通过并带每人的zone_可预置结论() {
        FakeMemberPrecheck precheck = new FakeMemberPrecheck().zone(1002, 7);

        MemberPrecheck.Result ok = precheck.check(List.of(1001L, 1002L), d());
        MemberPrecheck.Result failed = precheck.fail(MemberPrecheck.Reason.LOCK_READ_FAILED, 1002).check(List.of(1001L, 1002L), d());

        assertThat(ok.passed()).isTrue();
        assertThat(ok.zones()).containsExactly(Map.entry(1001L, 1), Map.entry(1002L, 7));
        assertThat(failed).isEqualTo(MemberPrecheck.Result.failed(MemberPrecheck.Reason.LOCK_READ_FAILED, 1002));
        assertThat(failed.reason().fault()).isTrue();
        assertThat(MemberPrecheck.Reason.IN_BATTLE.fault()).isFalse();
        assertThat(precheck.pass().check(List.of(5L), d()).passed()).isTrue();
        assertThat(precheck.rosters).containsExactly(List.of(1001L, 1002L), List.of(1001L, 1002L), List.of(5L));
        assertThatThrownBy(() -> new MemberPrecheck.Result(MemberPrecheck.Reason.OK, 9, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemberPrecheck.Result(MemberPrecheck.Reason.OFFLINE, 9, Map.of(9L, 1))).isInstanceOf(IllegalArgumentException.class);
    }

    // ================================================================ 玩家状态

    @Test
    void 假玩家状态_没预置的人没有锁不在线没有位置() {
        FakePlayerStatus players = new FakePlayerStatus();

        assertThat(players.inBattle(1001, d())).isFalse();
        assertThat(players.presence(1001, d())).isEmpty();
        assertThat(players.location(1001, d()).status()).isEqualTo(LocationStatus.MISSING);
        assertThat(players.reads).containsExactly("lock:1001", "presence:1001", "location:1001");
    }

    @Test
    void 假玩家状态_在线_掉线_登出_战斗锁() {
        FakePlayerStatus players = new FakePlayerStatus().online(1001, 2, 7).online(1002, 1, 3).online(1003, 1, 3).inBattle(1001, true);

        assertThat(players.presence(1001, d()).orElseThrow().getZoneId()).isEqualTo(2);
        assertThat(players.location(1001, d()).location().getSceneNodeId()).isEqualTo(7);
        assertThat(players.location(1001, d()).location().getZoneId()).isEqualTo(2);
        assertThat(players.inBattle(1001, d())).isTrue();

        players.disconnected(1002).loggedOut(1003);
        assertThat(players.presence(1002, d())).as("断线即没有在线目录条目").isEmpty();
        assertThat(players.location(1002, d()).status()).isEqualTo(LocationStatus.RECONNECT_LEASE);
        assertThat(players.location(1003, d()).status()).isEqualTo(LocationStatus.LOGGED_OUT);
        assertThatThrownBy(() -> players.location(1004, LocationStatus.ERROR)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 假玩家状态_三样读各自可以失败_互不牵连_异步读把故障报成ERROR状态() throws Exception {
        FakePlayerStatus players = new FakePlayerStatus().online(1001, 1, 7).failLock(1001).failPresence(1002).failLocation(1003);

        assertThatThrownBy(() -> players.inBattle(1001, d())).isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("读战斗锁");
        assertThat(players.presence(1001, d())).as("同一个人的别的读不受影响").isPresent();
        assertThatThrownBy(() -> players.presence(1002, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> players.location(1003, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThat(get(players.holderAsync(1003)).status()).isEqualTo(LocationStatus.ERROR);
        assertThat(get(players.holderAsync(1001)).status()).isEqualTo(LocationStatus.ONLINE);
        assertThat(players.heal(1001).inBattle(1001, d())).isFalse();
    }

    @Test
    void 假scene目录配假玩家状态_真的定位器按zone与节点号找到直连地址_同号不同zone不串() throws Exception {
        FakePlayerStatus players = new FakePlayerStatus().online(1001, 1, 7).online(1002, 2, 7).online(1003, 1, 8).disconnected(1004);
        FakeSceneNodes sceneNodes = new FakeSceneNodes().add(1, 7, "scene-z1", 21100).add(2, 7, "scene-z2", 21101);
        SceneAssetLocator locator = new SceneAssetLocator(players::holderAsync, sceneNodes, null);

        SceneAssetLocator.Found z1 = (SceneAssetLocator.Found) get(locator.resolveAsync(1001));
        SceneAssetLocator.Found z2 = (SceneAssetLocator.Found) get(locator.resolveAsync(1002));

        assertThat(z1.endpoint().instanceId()).isEqualTo("scene-z1");
        assertThat(z2.endpoint().instanceId()).as("zone 2 的 7 号节点是另一个进程").isEqualTo("scene-z2");
        assertThat(get(locator.resolveAsync(1003)).result()).as("目录里没有 zone 1 的 8 号").isEqualTo(SceneAssetLocator.ResolveResult.NODE_UNKNOWN);
        assertThat(get(locator.resolveAsync(1004)).result()).isEqualTo(SceneAssetLocator.ResolveResult.LEASE);
        assertThat(sceneNodes.lookups).containsExactly("1:7", "2:7", "1:8");
        assertThat(sceneNodes.targetOf(2, 7)).isEqualTo(new NodeRpcClients.Target("127.0.0.1", 21101, "scene-z2"));
        sceneNodes.failZone(1);
        assertThat(get(locator.resolveAsync(1001)).result()).isEqualTo(SceneAssetLocator.ResolveResult.ERROR);
    }

    // ================================================================ gather 的替身

    @Test
    void 假gather_缺省立即成功_活动用预设的号_记下plan() throws Exception {
        FakeGatherLauncher gather = new FakeGatherLauncher();

        GatherResult first = get(gather.launch(GatherPlan.challenge(0, 1001, 1002)));
        GatherResult second = get(gather.launch(GatherPlan.soloPve(1, 1001, "t")));
        GatherResult activity = get(gather.launch(GatherPlan.activity(1, List.of(5L), Map.of(5L, "t"), 424242,
                com.game.proto.BattleActivityContext.getDefaultInstance())));

        assertThat(first).isEqualTo(GatherResult.success(9001));
        assertThat(second.battleId()).isEqualTo(9002);
        assertThat(activity.battleId()).isEqualTo(424242);
        assertThat(gather.plans).hasSize(3);
        assertThat(gather.plans.get(0).members()).containsExactly(1001L, 1002L);
        assertThat(gather.availablePermits()).isEqualTo(256);
        assertThat(gather.awaitIdle(Duration.ofSeconds(1))).isTrue();
    }

    @Test
    void 假gather_脚本结果先进先出_挂起后由测试完成_许可为0即过载() throws Exception {
        FakeGatherLauncher gather = new FakeGatherLauncher()
                .nextResult(GatherResult.failed(GatherOutcome.PREPARE_FAILED, 0))
                .nextResult(GatherResult.failed(GatherOutcome.CREATE_FAILED_ROOM_ALIVE, 55));

        assertThat(get(gather.launch(GatherPlan.challenge(0, 1, 2))).outcome()).isEqualTo(GatherOutcome.PREPARE_FAILED);
        assertThat(get(gather.launch(GatherPlan.challenge(0, 1, 2))).battleId()).isEqualTo(55);
        assertThat(get(gather.launch(GatherPlan.challenge(0, 1, 2))).ok()).as("脚本用完回到缺省").isTrue();

        gather.hold();
        CompletableFuture<GatherResult> pending = gather.launch(GatherPlan.challenge(0, 3, 4));
        assertThat(pending).isNotDone();
        gather.complete(3, GatherResult.success(77));
        assertThat(get(pending).battleId()).isEqualTo(77);

        gather.release().permits(0);
        assertThat(gather.availablePermits()).isZero();
        assertThat(get(gather.launch(GatherPlan.challenge(0, 5, 6)))).isEqualTo(GatherResult.failed(GatherOutcome.OVERLOADED, 0));
        assertThat(gather.idle(false).awaitIdle(Duration.ZERO)).isFalse();
    }

    @Test
    void 假battle目录_确定性地选登记最早的可分配节点_按节点号加实例排除() {
        FakeBattleNodes nodes = new FakeBattleNodes()
                .add(FakeBattleNodes.node(1, "inst-a", 21200))
                .add(FakeBattleNodes.node(2, "inst-b", 21201))
                .add(FakeBattleNodes.node(3, "inst-c", 21202).toBuilder().setAccepting(false).build());

        BattleNodeInfo first = nodes.pickRandom(Set.of()).orElseThrow();
        BattleNodeInfo second = nodes.pickRandom(Set.of(BattleNodes.key(first))).orElseThrow();

        assertThat(first.getNodeId()).isEqualTo(1);
        assertThat(second.getNodeId()).isEqualTo(2);
        assertThat(nodes.pickRandom(Set.of("1#inst-a", "2#inst-b"))).as("3 号关闸中，不选").isEmpty();
        assertThat(nodes.pickRandom(Set.of("1#inst-OLD"))).as("同号新实例是另一个可以尝试的节点").map(BattleNodeInfo::getNodeId).contains(1);
        assertThat(nodes.census()).isEqualTo(new BattleNodes.Census(2, 1, false));
        assertThat(nodes.census().nothingAllocatable()).isFalse();
        assertThat(nodes.picks).containsExactly(Set.of(), Set.of("1#inst-a"), Set.of("1#inst-a", "2#inst-b"), Set.of("1#inst-OLD"));
        assertThat(BattleNodes.key(7, null)).isEqualTo("7#");
    }

    @Test
    void 假battle目录_判死用的查询与读失败() {
        FakeBattleNodes nodes = new FakeBattleNodes().add(FakeBattleNodes.node(1, "inst-a", 21200));

        assertThat(nodes.lookup(1, "inst-a")).isEqualTo(BattleNodes.Lookup.SAME_INSTANCE);
        assertThat(nodes.lookup(1, "inst-old")).isEqualTo(BattleNodes.Lookup.OTHER_INSTANCE);
        assertThat(nodes.lookup(9, "inst-a")).isEqualTo(BattleNodes.Lookup.ABSENT);
        nodes.set(FakeBattleNodes.node(1, "inst-new", 21200));
        assertThat(nodes.lookup(1, "inst-a")).as("同号换了实例").isEqualTo(BattleNodes.Lookup.OTHER_INSTANCE);
        nodes.remove(1);
        assertThat(nodes.census()).isEqualTo(new BattleNodes.Census(0, 0, false));
        assertThat(nodes.census().nothingAllocatable()).isTrue();

        nodes.readFailed = true;
        assertThat(nodes.lookup(1, "inst-a")).isEqualTo(BattleNodes.Lookup.ERROR);
        assertThat(nodes.pickRandom(Set.of())).isEmpty();
        assertThat(nodes.census()).isEqualTo(new BattleNodes.Census(0, 0, true));
        assertThat(nodes.lookups).containsExactly("1#inst-a", "1#inst-old", "9#inst-a", "1#inst-a", "1#inst-a");
        assertThatThrownBy(() -> new FakeBattleNodes().add(FakeBattleNodes.node(1, "a", 1)).add(FakeBattleNodes.node(1, "b", 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 假battle目录_带截止的查询_截止已过回ERROR_其余同不带截止的_两种都记下() {
        FakeBattleNodes nodes = new FakeBattleNodes().add(FakeBattleNodes.node(1, "inst-new", 21200));

        assertThat(nodes.lookup(1, "inst-a", Deadline.after(5_000))).isEqualTo(BattleNodes.Lookup.OTHER_INSTANCE);
        assertThat(nodes.lookup(1, "inst-new", Deadline.after(5_000))).isEqualTo(BattleNodes.Lookup.SAME_INSTANCE);
        assertThat(nodes.lookup(1, "inst-a", Deadline.after(0))).as("截止已过：不能证明任何事").isEqualTo(BattleNodes.Lookup.ERROR);
        nodes.lookup(1, "inst-a");

        assertThat(nodes.lookups).containsExactly("1#inst-a", "1#inst-new", "1#inst-a", "1#inst-a");
        assertThat(nodes.deadlineLookups).as("只有带截止的三次；记的是当时的剩余毫秒").hasSize(3);
        assertThat(nodes.deadlineLookups.get(0)).isBetween(4_000L, 5_000L);
        assertThat(nodes.deadlineLookups.get(2)).isZero();
    }

    @Test
    void 钩子替身_记参数与事件_可以抛异常() {
        List<String> events = new CopyOnWriteArrayList<>();
        RecordingGatherHooks hooks = new RecordingGatherHooks(events);
        BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(77).setAttempt(1).build();

        hooks.beforePrepare(List.of(1L, 2L));
        hooks.onStarted(placement);
        hooks.throwOnBeforePrepare = true;

        assertThat(hooks.beforePrepare).containsExactly(List.of(1L, 2L));
        assertThat(hooks.started).containsExactly(placement);
        assertThat(events).containsExactly("hooks.beforePrepare", "hooks.onStarted");
        assertThatThrownBy(() -> hooks.beforePrepare(List.of(3L))).isInstanceOf(IllegalStateException.class);
        assertThat(hooks.beforePrepare).as("抛之前已经记下").hasSize(2);
    }

    // ================================================================ 落点

    private static BattlePlacement placement(long battleId, int attempt, String instance) {
        return BattlePlacement.newBuilder().setBattleId(battleId).setAttempt(attempt).setBattleNodeId(attempt).setBattleInstanceId(instance)
                .setRpcHost("127.0.0.1").setRpcPort(21200).build();
    }

    @Test
    void 内存落点_单调写_迟到的旧写盖不掉新写_同值可以补写() {
        InMemoryPlacementStore placements = new InMemoryPlacementStore();

        assertThat(placements.write(placement(77, 1, "a"))).isTrue();
        assertThat(placements.write(placement(77, 2, "b"))).isTrue();
        assertThat(placements.write(placement(77, 1, "a"))).as("迟到落盘的首写：返回 true 但不覆盖").isTrue();
        assertThat(placements.stored(77).orElseThrow().getBattleInstanceId()).isEqualTo("b");
        assertThat(placements.write(placement(77, 2, "b2"))).as("同一个 attempt 的补写覆盖").isTrue();
        assertThat(placements.stored(77).orElseThrow().getBattleInstanceId()).isEqualTo("b2");

        assertThat(placements.read(77, d())).isEqualTo(new PlacementStore.Read.Found(placement(77, 2, "b2")));
        assertThat(placements.read(78, d())).isInstanceOf(PlacementStore.Read.Absent.class);
        assertThat(placements.writes).extracting(BattlePlacement::getAttempt).containsExactly(1, 2, 1, 2);
        assertThat(placements.events).containsExactly("placement.write:77#1", "placement.write:77#2", "placement.write:77#1", "placement.write:77#2");
    }

    @Test
    void 内存落点_写失败不落盘_删除_损坏与读失败() {
        InMemoryPlacementStore placements = new InMemoryPlacementStore();
        placements.failWrites = 1;

        assertThat(placements.write(placement(77, 1, "a"))).isFalse();
        assertThat(placements.stored(77)).isEmpty();
        assertThat(placements.write(placement(77, 1, "a"))).as("只失败一次").isTrue();
        placements.failWriteOnAttempt = 2;
        assertThat(placements.write(placement(77, 2, "b"))).as("换节点改写失败").isFalse();
        assertThat(placements.stored(77).orElseThrow().getAttempt()).isEqualTo(1);

        placements.delete(77);
        assertThat(placements.read(77, d())).isInstanceOf(PlacementStore.Read.Absent.class);
        assertThat(placements.deletes).containsExactly(77L);
        assertThat(placements.events).containsExactly("placement.write-failed:77#1", "placement.write:77#1", "placement.write-failed:77#2",
                "placement.delete:77");

        placements.put(placement(88, 1, "a")).corrupt(88);
        assertThat(placements.read(88, d())).as("损坏不折成不存在").isInstanceOf(PlacementStore.Read.Failed.class);
        placements.readFailed = true;
        assertThat(placements.read(99, d())).isInstanceOf(PlacementStore.Read.Failed.class);
        assertThatThrownBy(() -> placements.write(BattlePlacement.getDefaultInstance())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 假直拨_缺省调通把调用交给假节点_可预置判死与三种没调通() {
        FakeBattleNode battle = new FakeBattleNode();
        battle.room(CreateBattleRequest.newBuilder().setBattleId(77).addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(1001)).build());
        FakePlacementDialer dialer = new FakePlacementDialer(battle);
        IssueBattleTicketRequest request = IssueBattleTicketRequest.newBuilder().setBattleId(77).setPlayerId(1001).build();

        PlacementDialer.Dial<IssueBattleTicketResponse> replied = dialer.dial(placement(77, 1, "a"), Duration.ofSeconds(3), n -> n.issueBattleTicket(request));
        PlacementDialer.Dial<IssueBattleTicketResponse> gone = dialer.roomGone().dial(placement(77, 1, "a"), Duration.ofSeconds(3), n -> n.issueBattleTicket(request));
        PlacementDialer.Dial<IssueBattleTicketResponse> timeout = dialer.unavailable(PlacementDialer.Kind.TIMEOUT)
                .dial(placement(77, 1, "a"), Duration.ofSeconds(3), n -> n.issueBattleTicket(request));

        assertThat(((PlacementDialer.Dial.Replied<IssueBattleTicketResponse>) replied).reply().getAssignment().getBattleId()).isEqualTo(77);
        assertThat(gone).isInstanceOf(PlacementDialer.Dial.RoomGone.class);
        assertThat(((PlacementDialer.Dial.Unavailable<IssueBattleTicketResponse>) timeout).kind()).isEqualTo(PlacementDialer.Kind.TIMEOUT);
        assertThat(battle.issues).as("没调通时假节点不会被调用").hasSize(1);
        assertThat(dialer.dials).hasSize(3);
        assertThat(dialer.dials.get(0).timeout()).isEqualTo(Duration.ofSeconds(3));

        battle.nextIssueFails(() -> new IllegalStateException("对端回错"));
        assertThat(dialer.reachable().dial(placement(77, 1, "a"), Duration.ofSeconds(3), n -> n.issueBattleTicket(request)))
                .isInstanceOfSatisfying(PlacementDialer.Dial.Unavailable.class, u -> assertThat(u.kind()).isEqualTo(PlacementDialer.Kind.OTHER));
    }

    @Test
    void 假直拨_带硬截止的重载_记下截止_已过就不调假节点回没送达_不带截止的记null() {
        FakeBattleNode battle = new FakeBattleNode();
        battle.room(CreateBattleRequest.newBuilder().setBattleId(77).addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(1001)).build());
        FakePlacementDialer dialer = new FakePlacementDialer(battle);
        IssueBattleTicketRequest request = IssueBattleTicketRequest.newBuilder().setBattleId(77).setPlayerId(1001).build();
        Deadline hardStop = Deadline.after(5_000);

        PlacementDialer.Dial<IssueBattleTicketResponse> replied = dialer.dial(placement(77, 1, "a"), Duration.ofSeconds(3), hardStop,
                n -> n.issueBattleTicket(request));
        PlacementDialer.Dial<IssueBattleTicketResponse> late = dialer.dial(placement(77, 1, "a"), Duration.ofSeconds(3), Deadline.after(0),
                n -> n.issueBattleTicket(request));
        dialer.dial(placement(77, 1, "a"), Duration.ofSeconds(3), n -> n.issueBattleTicket(request));
        PlacementDialer.Dial<IssueBattleTicketResponse> gone = dialer.roomGone().dial(placement(77, 1, "a"), Duration.ofSeconds(3), hardStop,
                n -> n.issueBattleTicket(request));

        assertThat(replied).isInstanceOf(PlacementDialer.Dial.Replied.class);
        assertThat(((PlacementDialer.Dial.Unavailable<IssueBattleTicketResponse>) late).kind()).isEqualTo(PlacementDialer.Kind.NOT_DELIVERED);
        assertThat(gone).isInstanceOf(PlacementDialer.Dial.RoomGone.class);
        assertThat(battle.issues).as("截止已过的那次与判死的那次都没有调到假节点").hasSize(2);
        assertThat(dialer.dials).hasSize(4);
        assertThat(dialer.dials.get(0).hardStop()).isSameAs(hardStop);
        assertThat(dialer.dials.get(1).hardStop().expired()).isTrue();
        assertThat(dialer.dials.get(2).hardStop()).as("不带硬截止的重载（179）").isNull();
    }

    // ================================================================ 观众 RPC 的替身（批次 6.5）

    private static AddObserverRequest addRequest(long battleId, long observerId) {
        return AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(observerId)
                .setRouting(BattleRouting.newBuilder().setZoneId(2).setGateNodeId(1).setGateInstanceId("gate-z2").setSessionId(9)).setObserverName("acc")
                .build();
    }

    @Test
    void 假观众RPC_缺省都调通_记下次序_落点_超时_硬截止_reason与完整的登记请求() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        FakeObserverDialer dialer = new FakeObserverDialer(events);
        BattlePlacement old = placement(76, 1, "a");
        BattlePlacement target = placement(77, 2, "b");

        ObserverDialer.Outcome removed = dialer.remove(old, 1001, SpectateRules.REASON_REWATCH, Duration.ofMillis(1_800), Deadline.after(1_800));
        ObserverDialer.Outcome added = dialer.add(target, addRequest(77, 1001), Duration.ofSeconds(3), Deadline.after(4_000));
        ObserverDialer.Outcome async = dialer.removeAsync(target, 1001, SpectateRules.REASON_CONCURRENT_QUEUE).get(1, TimeUnit.SECONDS);

        assertThat(List.of(removed, added, async)).containsOnly(new ObserverDialer.Outcome.Replied(0));
        assertThat(dialer.calls).extracting(FakeObserverDialer.Call::kind)
                .containsExactly(FakeObserverDialer.Kind.REMOVE, FakeObserverDialer.Kind.ADD, FakeObserverDialer.Kind.REMOVE_ASYNC);
        assertThat(events).containsExactly("observer.remove:76:1001:rewatch", "observer.add:77:1001", "observer.removeAsync:77:1001:concurrent_queue");
        FakeObserverDialer.Call remove = dialer.removes().get(0);
        assertThat(remove.placement()).isSameAs(old);
        assertThat(remove.battleId()).isEqualTo(76);
        assertThat(remove.observerId()).isEqualTo(1001);
        assertThat(remove.reason()).isEqualTo("rewatch");
        assertThat(remove.timeout()).isEqualTo(Duration.ofMillis(1_800));
        assertThat(remove.hardStopRemainingMs()).isBetween(1_000L, 1_800L);
        FakeObserverDialer.Call add = dialer.adds().get(0);
        assertThat(add.request().getRouting().getZoneId()).isEqualTo(2);
        assertThat(add.request().getObserverName()).isEqualTo("acc");
        assertThat(add.reason()).isNull();
        assertThat(add.observerId()).isEqualTo(1001);
        assertThat(dialer.removes()).extracting(FakeObserverDialer.Call::kind)
                .containsExactly(FakeObserverDialer.Kind.REMOVE, FakeObserverDialer.Kind.REMOVE_ASYNC);
        assertThat(dialer.removes().get(1).hardStopRemainingMs()).as("异步的不带硬截止").isEqualTo(-1);
    }

    @Test
    void 假观众RPC_结局可以排队也可以按战斗固定_固定的优先_清退的同步与异步共用一个队列() throws Exception {
        FakeObserverDialer dialer = new FakeObserverDialer();
        BattlePlacement p77 = placement(77, 1, "a");
        BattlePlacement p78 = placement(78, 1, "a");
        dialer.nextAdd(new ObserverDialer.Outcome.Replied(1004), new ObserverDialer.Outcome.Unknown("超时"));
        dialer.onAdd(78, new ObserverDialer.Outcome.Dead());
        dialer.nextRemove(new ObserverDialer.Outcome.NotDelivered("连不上"), new ObserverDialer.Outcome.Unknown("断开"));

        assertThat(dialer.add(p78, addRequest(78, 1001), Duration.ofSeconds(3), Deadline.after(3_000))).as("按战斗固定的优先，不消耗队列")
                .isInstanceOf(ObserverDialer.Outcome.Dead.class);
        assertThat(dialer.add(p77, addRequest(77, 1001), Duration.ofSeconds(3), Deadline.after(3_000))).isEqualTo(new ObserverDialer.Outcome.Replied(1004));
        assertThat(dialer.add(p77, addRequest(77, 1001), Duration.ofSeconds(3), Deadline.after(3_000))).isInstanceOf(ObserverDialer.Outcome.Unknown.class);
        assertThat(dialer.add(p77, addRequest(77, 1001), Duration.ofSeconds(3), Deadline.after(3_000))).as("队列用完回到缺省")
                .isEqualTo(new ObserverDialer.Outcome.Replied(0));
        assertThat(dialer.clearAdd(78).add(p78, addRequest(78, 1001), Duration.ofSeconds(3), Deadline.after(3_000)))
                .isEqualTo(new ObserverDialer.Outcome.Replied(0));

        assertThat(dialer.remove(p77, 1001, "rewatch", Duration.ofSeconds(3), Deadline.after(3_000))).isInstanceOf(ObserverDialer.Outcome.NotDelivered.class);
        assertThat(dialer.removeAsync(p77, 1001, "concurrent_queue").get(1, TimeUnit.SECONDS)).isInstanceOf(ObserverDialer.Outcome.Unknown.class);
        dialer.onRemove(77, new ObserverDialer.Outcome.Dead());
        assertThat(dialer.remove(p77, 1001, "enter_gather", Duration.ofSeconds(3), Deadline.after(3_000))).isInstanceOf(ObserverDialer.Outcome.Dead.class);
        assertThat(dialer.clearRemove(77).remove(p77, 1001, "enter_gather", Duration.ofSeconds(3), Deadline.after(3_000)))
                .isEqualTo(new ObserverDialer.Outcome.Replied(0));
    }

    @Test
    void 假观众RPC_硬截止已过_记下调用但不算发出_回没送达_不消耗脚本也不走测试缝() {
        FakeObserverDialer dialer = new FakeObserverDialer();
        dialer.nextAdd(new ObserverDialer.Outcome.Replied(1008));
        List<String> hooked = new CopyOnWriteArrayList<>();
        dialer.beforeAdd = call -> hooked.add("add");
        dialer.beforeRemove = call -> hooked.add("remove");
        BattlePlacement placement = placement(77, 1, "a");

        ObserverDialer.Outcome add = dialer.add(placement, addRequest(77, 1001), Duration.ofSeconds(3), Deadline.after(0));
        ObserverDialer.Outcome remove = dialer.remove(placement, 1001, "rewatch", Duration.ofSeconds(3), Deadline.after(0));

        assertThat(add).isInstanceOf(ObserverDialer.Outcome.NotDelivered.class);
        assertThat(remove).isInstanceOf(ObserverDialer.Outcome.NotDelivered.class);
        assertThat(dialer.calls).hasSize(2).allSatisfy(call -> assertThat(call.hardStopRemainingMs()).isZero());
        assertThat(hooked).isEmpty();
        assertThat(dialer.add(placement, addRequest(77, 1001), Duration.ofSeconds(3), Deadline.after(3_000))).as("脚本还在")
                .isEqualTo(new ObserverDialer.Outcome.Replied(1008));
        assertThat(hooked).containsExactly("add");
    }

    @Test
    void 假观众RPC_测试缝在记下调用之后给出结局之前_挂起的调用等到超时与硬截止里先到的那个回结局不明() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        FakeObserverDialer dialer = new FakeObserverDialer(events);
        BattlePlacement placement = placement(77, 1, "a");
        dialer.beforeAdd = call -> events.add("世界变了:" + call.battleId());
        dialer.beforeRemove = call -> events.add("清退途中:" + call.reason());

        dialer.add(placement, addRequest(77, 1001), Duration.ofSeconds(3), Deadline.after(3_000));
        dialer.remove(placement, 1001, "rewatch", Duration.ofSeconds(3), Deadline.after(3_000));
        assertThat(events).containsExactly("observer.add:77:1001", "世界变了:77", "observer.remove:77:1001:rewatch", "清退途中:rewatch");

        dialer.hangAdd().hangRemove();
        long started = System.nanoTime();
        ObserverDialer.Outcome byTimeout = dialer.add(placement, addRequest(77, 1001), Duration.ofMillis(120), Deadline.after(10_000));
        // 硬截止给 1 s：从创建截止到进替身之间即使卡住几百毫秒，也不会被判成「硬截止已到，没有发出调用」（NotDelivered）那一支
        ObserverDialer.Outcome byHardStop = dialer.remove(placement, 1001, "rewatch", Duration.ofSeconds(30), Deadline.after(1_000));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(byTimeout).isInstanceOf(ObserverDialer.Outcome.Unknown.class);
        assertThat(byHardStop).isInstanceOf(ObserverDialer.Outcome.Unknown.class);
        assertThat(elapsedMs).as("先等 120 ms 的超时，再等 1 s 的硬截止，不是 30 s").isBetween(1_100L, 20_000L);

        CompletableFuture<ObserverDialer.Outcome> blocked = CompletableFuture.supplyAsync(
                () -> dialer.add(placement, addRequest(77, 1001), Duration.ofSeconds(20), Deadline.after(20_000)));
        dialer.releaseAdd().releaseRemove();
        assertThat(blocked.get(5, TimeUnit.SECONDS)).as("放行之后给出脚本里的结局").isEqualTo(new ObserverDialer.Outcome.Replied(0));
        assertThat(dialer.remove(placement, 1001, "rewatch", Duration.ofSeconds(3), Deadline.after(3_000))).isEqualTo(new ObserverDialer.Outcome.Replied(0));
    }

    @Test
    void 假观众RPC_异步清退可以挂着不完成_调用照样立刻返回_放行后按发起时定好的结局完成() throws Exception {
        FakeObserverDialer dialer = new FakeObserverDialer();
        BattlePlacement placement = placement(77, 1, "a");
        dialer.hangRemoveAsync().nextRemove(new ObserverDialer.Outcome.Unknown("慢"));

        CompletableFuture<ObserverDialer.Outcome> first = dialer.removeAsync(placement, 1001, "concurrent_queue");
        CompletableFuture<ObserverDialer.Outcome> second = dialer.removeAsync(placement, 1002, "concurrent_queue");

        assertThat(first).isNotDone();
        assertThat(second).isNotDone();
        assertThat(dialer.calls).as("发出即返回：调用已经记下").hasSize(2);
        dialer.releaseRemoveAsync();
        assertThat(first.get(1, TimeUnit.SECONDS)).isInstanceOf(ObserverDialer.Outcome.Unknown.class);
        assertThat(second.get(1, TimeUnit.SECONDS)).isEqualTo(new ObserverDialer.Outcome.Replied(0));
        assertThat(dialer.removeAsync(placement, 1003, "concurrent_queue")).as("放行之后恢复成立即完成").isDone().isNotCompletedExceptionally();
    }

    // ================================================================ 推送

    @Test
    void 推送替身_记下谁收到什么与先后_缺省SENT_可预置结局与异常() throws Exception {
        RecordingPushes pushes = new RecordingPushes().outcome(1002, PlayerPushes.Outcome.OFFLINE).failFor(1003);
        MessageContent result = MessageContent.newBuilder().setMessageId(154)
                .setSerializedMessage(ChallengeResultS2C.newBuilder().setChallengeId(9).setAccepted(true).setResponderId(1002).build().toByteString()).build();

        assertThat(get(pushes.push(1001, result).toCompletableFuture())).isEqualTo(PlayerPushes.Outcome.SENT);
        assertThat(get(pushes.push(1002, result).toCompletableFuture())).isEqualTo(PlayerPushes.Outcome.OFFLINE);
        CompletableFuture<PlayerPushes.Outcome> failed = pushes.push(1003, result).toCompletableFuture();

        assertThat(failed).isCompletedExceptionally();
        assertThat(pushes.sent).extracting(RecordingPushes.Pushed::playerId).as("先后次序").containsExactly(1001L, 1002L, 1003L);
        assertThat(pushes.sent.get(0).messageId()).isEqualTo(154);
        assertThat(ChallengeResultS2C.parseFrom(pushes.sent.get(0).body()).getResponderId()).isEqualTo(1002);
        assertThat(pushes.sent.get(0).content().getId()).as("推送的 id 为 0").isZero();
        assertThat(pushes.sentTo(1002)).hasSize(1);

        pushes.hold();
        CompletableFuture<PlayerPushes.Outcome> pending = pushes.push(1001, result).toCompletableFuture();
        assertThat(pending).isNotDone();
        pushes.complete(3, PlayerPushes.Outcome.GATE_UNREACHABLE);
        assertThat(get(pending)).isEqualTo(PlayerPushes.Outcome.GATE_UNREACHABLE);
    }

    // ================================================================ 假 scene、假 battle、假直连

    private static SceneBattleCall prepareCall(String instance, long playerId, long battleId) {
        return SceneBattleCall.newBuilder().setTargetInstanceId(instance).setPlayerId(playerId)
                .setBody(PrepareBattleRequest.newBuilder().setPlayerId(playerId).setBattleId(battleId).setDeadlineMs(5).setPrepareDeadlineMs(4).build()
                        .toByteString()).build();
    }

    private static SceneBattleCall cancelCall(String instance, long playerId, long battleId) {
        return SceneBattleCall.newBuilder().setTargetInstanceId(instance).setPlayerId(playerId)
                .setBody(CancelBattlePrepareRequest.newBuilder().setPlayerId(playerId).setBattleId(battleId).build().toByteString()).build();
    }

    @Test
    void 假scene_缺省备战成功并冻结_回带routing与指纹的快照_取消解冻() throws Exception {
        FakeSceneBattle scene = new FakeSceneBattle("scene-a").name(1001, "甲").fingerprint(1001, "fp-9").zone(1001, 3);

        SceneBattleReply reply = get(scene.prepareBattle(prepareCall("scene-a", 1001, 77)));
        PrepareBattleResponse body = PrepareBattleResponse.parseFrom(reply.getBody());

        assertThat(reply.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(body.getErrorMessage().getId()).isZero();
        assertThat(body.getSnapshot().getPlayerId()).isEqualTo(1001);
        assertThat(body.getSnapshot().getPlayerName()).isEqualTo("甲");
        assertThat(body.getTableFingerprint()).isEqualTo("fp-9");
        assertThat(body.getSnapshot().getRouting().getGateInstanceId()).isNotEmpty();
        assertThat(body.getSnapshot().getRouting().getSceneInstanceId()).isEqualTo("scene-a");
        assertThat(body.getSnapshot().getRouting().getZoneId()).isEqualTo(3);
        assertThat(scene.frozen()).containsExactly(Map.entry(1001L, 77L));

        assertThat(get(scene.cancelBattlePrepare(cancelCall("scene-a", 1001, 999))).getStatus()).as("battle_id 不符：忽略，仍回成功")
                .isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(scene.frozen()).hasSize(1);
        get(scene.cancelBattlePrepare(cancelCall("scene-a", 1001, 77)));
        assertThat(scene.frozen()).isEmpty();
        assertThat(scene.calls).extracting(FakeSceneBattle.Call::describe).containsExactly("prepare:1001", "cancel:1001", "cancel:1001");
        assertThat(scene.calls.get(0).prepare().getPrepareDeadlineMs()).isEqualTo(4);
        assertThat(scene.events).containsExactly("scene.prepare:1001", "scene.cancel:1001", "scene.cancel:1001");
    }

    @Test
    void 假scene_实例不符回NOT_HERE_已有别的战斗回1006_都不冻结() throws Exception {
        FakeSceneBattle scene = new FakeSceneBattle("scene-a");

        assertThat(get(scene.prepareBattle(prepareCall("scene-OLD", 1001, 77))).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_NOT_HERE);
        assertThat(scene.frozen()).isEmpty();
        get(scene.prepareBattle(prepareCall("scene-a", 1001, 77)));
        SceneBattleReply second = get(scene.prepareBattle(prepareCall("scene-a", 1001, 88)));

        assertThat(PrepareBattleResponse.parseFrom(second.getBody()).getErrorMessage().getId()).isEqualTo(1006);
        assertThat(scene.frozen()).as("还是第一局的冻结").containsExactly(Map.entry(1001L, 77L));
        assertThat(get(scene.cancelBattlePrepare(cancelCall("scene-OLD", 1001, 77))).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_NOT_HERE);
        assertThat(scene.frozen()).hasSize(1);
    }

    @Test
    void 假scene_脚本化的各种备战结局_哪些留下冻结() throws Exception {
        FakeSceneBattle scene = new FakeSceneBattle("scene-a")
                .prepareTip(1, 1006)
                .prepareStatus(2, SceneBattleStatus.SCENE_BATTLE_OVERLOADED)
                .prepareStatus(3, SceneBattleStatus.SCENE_BATTLE_STATUS_UNSPECIFIED)
                .prepareFails(4, () -> new TimeoutException("慢"), true)
                .prepareFails(5, () -> new ConnectException("拒绝"), false)
                .prepareHangs(6)
                .prepareWithoutSnapshot(7);

        assertThat(PrepareBattleResponse.parseFrom(get(scene.prepareBattle(prepareCall("scene-a", 1, 77))).getBody()).getErrorMessage().getId()).isEqualTo(1006);
        assertThat(get(scene.prepareBattle(prepareCall("scene-a", 2, 77))).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_OVERLOADED);
        assertThat(get(scene.prepareBattle(prepareCall("scene-a", 3, 77))).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_STATUS_UNSPECIFIED);
        assertThatThrownBy(() -> get(scene.prepareBattle(prepareCall("scene-a", 4, 77)))).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        assertThatThrownBy(() -> get(scene.prepareBattle(prepareCall("scene-a", 5, 77)))).hasCauseInstanceOf(ConnectException.class);
        assertThat(scene.prepareBattle(prepareCall("scene-a", 6, 77))).isNotDone();
        SceneBattleReply noSnapshot = get(scene.prepareBattle(prepareCall("scene-a", 7, 77)));

        assertThat(PrepareBattleResponse.parseFrom(noSnapshot.getBody()).hasSnapshot()).isFalse();
        assertThat(scene.frozen()).as("只有「生效了但应答没回来」「挂起」「没有快照」留下冻结：这三种都要补发取消").containsOnlyKeys(4L, 6L, 7L);
        scene.prepareOk(1);
        assertThat(get(scene.prepareBattle(prepareCall("scene-a", 1, 77))).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(scene.frozen()).containsKey(1L);
    }

    @Test
    void 假scene_取消失败时冻结不解除_留给reaper() throws Exception {
        FakeSceneBattle scene = new FakeSceneBattle("scene-a").cancelFails(1, () -> new IllegalStateException("断连"))
                .cancelStatus(2, SceneBattleStatus.SCENE_BATTLE_OVERLOADED);
        get(scene.prepareBattle(prepareCall("scene-a", 1, 77)));
        get(scene.prepareBattle(prepareCall("scene-a", 2, 77)));

        assertThatThrownBy(() -> get(scene.cancelBattlePrepare(cancelCall("scene-a", 1, 77)))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(get(scene.cancelBattlePrepare(cancelCall("scene-a", 2, 77))).getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_OVERLOADED);
        assertThat(scene.frozen()).containsOnlyKeys(1L, 2L);
        assertThat(scene.confirmBattle(SceneBattleCall.getDefaultInstance())).as("match 不该调确认").isCompletedExceptionally();
    }

    private static CreateBattleRequest createRequest(long battleId, long... players) {
        CreateBattleRequest.Builder request = CreateBattleRequest.newBuilder().setBattleId(battleId).setMatchMode(3).setDeadlineMs(123_456);
        for (long player : players) {
            request.addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(player));
        }
        return request.build();
    }

    @Test
    void 假battle_缺省建成_房间入表_销毁出表_补签按名单() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        FakeBattleNode battle = new FakeBattleNode("a", events);

        CreateBattleResult created = get(battle.createBattle(createRequest(77, 1001, 1002)));
        IssueBattleTicketResponse member = get(battle.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(77).setPlayerId(1002).build()));
        IssueBattleTicketResponse stranger = get(battle.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(77).setPlayerId(9).build()));

        assertThat(created.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(CreateBattleResponse.parseFrom(created.getResponse()).getErrorMessage().getId()).isZero();
        assertThat(battle.rooms()).containsOnlyKeys(77L);
        assertThat(member.getAssignment().getBattleId()).isEqualTo(77);
        assertThat(member.getAssignment().getExpireAtMs()).isEqualTo(123_456);
        assertThat(stranger.getErrorMessage().getId()).as("不是成员：1005，不带 parameters").isEqualTo(1005);
        assertThat(stranger.getErrorMessage().getParametersList()).isEmpty();
        assertThat(stranger.hasAssignment()).isFalse();

        get(battle.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(77).setReason("gather_rollback").build()));
        assertThat(battle.rooms()).isEmpty();
        assertThat(battle.destroys).singleElement().satisfies(r -> assertThat(r.getReason()).isEqualTo("gather_rollback"));
        assertThat(get(battle.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(77).setPlayerId(1002).build())).getErrorMessage().getId())
                .as("房间不在了").isEqualTo(1005);
        assertThat(events).containsExactly("battle[a].create:77", "battle[a].issue:77:1002", "battle[a].issue:77:9", "battle[a].destroy:77",
                "battle[a].issue:77:1002");
    }

    @Test
    void 假battle_脚本化的建房结局_只有建成的才有房间() throws Exception {
        FakeBattleNode battle = new FakeBattleNode()
                .nextCreate(FakeBattleNode.notAllocatable("closed"))
                .nextCreate(FakeBattleNode.rejected(1006))
                .nextCreate(FakeBattleNode.unspecified())
                .nextCreateFails(() -> new TimeoutException("慢"), true)
                .nextCreateFails(() -> new ConnectException("拒绝"), false);

        CreateBattleResult refused = get(battle.createBattle(createRequest(1, 5)));
        CreateBattleResult rejected = get(battle.createBattle(createRequest(2, 5)));
        CreateBattleResult unspecified = get(battle.createBattle(createRequest(3, 5)));
        assertThatThrownBy(() -> get(battle.createBattle(createRequest(4, 5)))).hasCauseInstanceOf(TimeoutException.class);
        assertThatThrownBy(() -> get(battle.createBattle(createRequest(5, 5)))).hasCauseInstanceOf(ConnectException.class);
        CreateBattleResult afterScript = get(battle.createBattle(createRequest(6, 5)));

        assertThat(refused.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_NOT_ALLOCATABLE);
        assertThat(refused.getReason()).isEqualTo("closed");
        assertThat(rejected.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(CreateBattleResponse.parseFrom(rejected.getResponse()).getErrorMessage().getId()).isEqualTo(1006);
        assertThat(unspecified.getAdmission()).isEqualTo(BattleAdmission.BATTLE_ADMISSION_UNSPECIFIED);
        assertThat(afterScript.getAdmission()).as("脚本用完回到缺省").isEqualTo(BattleAdmission.BATTLE_ADMISSION_ADMITTED);
        assertThat(battle.rooms()).as("节点级拒绝 / 明确拒绝 / 字段缺失 / 没送达都没有房间；「生效了但超时」有").containsOnlyKeys(4L, 6L);
        assertThat(battle.creates).hasSize(6);
    }

    @Test
    void 假battle_销毁失败时房间还在_挂起的建房永不应答() throws Exception {
        FakeBattleNode battle = new FakeBattleNode().nextCreateHangs().nextDestroyFails(() -> new IllegalStateException("断连"));

        CompletableFuture<CreateBattleResult> hanging = battle.createBattle(createRequest(77, 5));
        assertThat(hanging).isNotDone();
        assertThat(battle.rooms()).containsKey(77L);
        assertThatThrownBy(() -> get(battle.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(77).build()))).hasCauseInstanceOf(IllegalStateException.class);
        assertThat(battle.rooms()).as("销毁失败：房间可能还活着").containsKey(77L);
        get(battle.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(77).build()));
        assertThat(battle.rooms()).isEmpty();
        get(battle.destroyBattle(DestroyBattleRequest.newBuilder().setBattleId(424242).build()));
        assertThat(battle.destroys).as("销毁不存在的房间幂等成功").hasSize(3);
    }

    @Test
    void 假直连_按地址路由到假服务_记去向与超时_没登记的地址连不上() throws Exception {
        FakeBattleNode a = new FakeBattleNode();
        FakeBattleNode b = new FakeBattleNode();
        NodeRpcClients.Target targetA = new NodeRpcClients.Target("127.0.0.1", 21200, "inst-a");
        NodeRpcClients.Target targetB = new NodeRpcClients.Target("127.0.0.1", 21201, "inst-b");
        FakeNodeCalls<BattleNodeService> calls = new FakeNodeCalls<BattleNodeService>().register(targetA, a).register(targetB, b);

        get(calls.call(targetB, Duration.ofSeconds(5), node -> node.createBattle(createRequest(77, 5))));

        assertThat(a.creates).isEmpty();
        assertThat(b.creates).hasSize(1);
        assertThat(calls.calls).containsExactly(new FakeNodeCalls.Call(targetB, Duration.ofSeconds(5)));
        NodeRpcClients.Target nowhere = new NodeRpcClients.Target("127.0.0.1", 9, "x");
        assertThatThrownBy(() -> get(calls.call(nowhere, Duration.ofSeconds(1), node -> node.createBattle(createRequest(1, 5)))))
                .hasCauseInstanceOf(ConnectException.class);
    }

    @Test
    void 假直连_三种传输失败_假服务不被调用_预算为0不发() throws Exception {
        FakeSceneBattle scene = new FakeSceneBattle("scene-a");
        NodeRpcClients.Target target = new NodeRpcClients.Target("127.0.0.1", 21100, "scene-a");
        FakeNodeCalls<SceneBattleService> calls = new FakeNodeCalls<SceneBattleService>().register(target, scene);

        calls.unreachable(target);
        assertThatThrownBy(() -> get(calls.call(target, Duration.ofSeconds(3), s -> s.prepareBattle(prepareCall("scene-a", 1, 77)))))
                .hasCauseInstanceOf(ConnectException.class);
        calls.timeout(target);
        assertThatThrownBy(() -> get(calls.call(target, Duration.ofSeconds(3), s -> s.prepareBattle(prepareCall("scene-a", 1, 77)))))
                .hasCauseInstanceOf(TimeoutException.class);
        calls.failWith(target, () -> new IllegalStateException("鉴权失败"));
        assertThatThrownBy(() -> get(calls.call(target, Duration.ofSeconds(3), s -> s.prepareBattle(prepareCall("scene-a", 1, 77)))))
                .hasCauseInstanceOf(IllegalStateException.class);
        assertThat(scene.calls).as("传输失败时请求没有到假服务").isEmpty();

        calls.heal(target);
        assertThatThrownBy(() -> get(calls.call(target, Duration.ZERO, s -> s.prepareBattle(prepareCall("scene-a", 1, 77)))))
                .as("剩余预算为 0：不发包").hasCauseInstanceOf(TimeoutException.class);
        assertThat(scene.calls).isEmpty();
        assertThat(get(calls.call(target, Duration.ofSeconds(3), s -> s.prepareBattle(prepareCall("scene-a", 1, 77)))).getStatus())
                .isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(calls.calls).hasSize(5);
    }

    @Test
    void 故障注入_一次性的按次消耗_可叠加_可给任意异常() {
        Faults faults = new Faults().failNext("op").failNext("op", new IllegalStateException("自定义"));

        assertThatThrownBy(() -> faults.check("op")).isInstanceOf(Deadline.DependencyException.class);
        assertThatThrownBy(() -> faults.check("op")).isInstanceOf(IllegalStateException.class).hasMessage("自定义");
        faults.check("op");
        faults.check("别的操作");
        faults.failAlways("op");
        assertThatThrownBy(() -> faults.check("op")).isInstanceOf(Deadline.DependencyException.class);
        faults.clearAll().check("op");
    }
}
