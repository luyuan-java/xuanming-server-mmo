package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.proto.CreateBattleResult;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.deadline.Deadline;
import com.game.match.gather.BattleNodes;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.placement.ConnectProbe;
import com.game.match.placement.DirectPlacementDialer;
import com.game.match.placement.PlacementDialer;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.match.testing.FakeBattleNode;
import com.game.match.testing.FakeBattleNodes;
import com.game.match.testing.FakeNodeCalls;
import com.game.match.testing.FakePlacementDialer;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleRouting;
import com.game.proto.CreateBattleRequest;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.Empty;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import com.game.proto.TipInfoMessage;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 观众 RPC 的直拨与结局四分（spectate-spec §4.8、§10.3 的 {@code ObserverDialerTest}；lead 裁决 3）。判据本身在 placement 包的直拨器里
 * （{@code DirectPlacementDialerTest} 钉），这里把<b>真的直拨器</b>接在内存出站口上，钉观众 RPC 这一层：请求原样发往<b>落点记录里的地址</b>；
 * 四种结局各自的判据（建连失败 + 换实例 / 同实例 / 目录缺席 / 目录读失败；超时；连上后断开；battle 在途超限）；超时永不判死；
 * 硬截止之后不发调用、整次直拨不越过它；不重试；永不抛异常；{@code xm_match_observer_rpc_total} 每次调用恰好记一个。
 * 真 Triple 上的形态见 {@code ObserverRpcLoopbackTest}。
 */
class DefaultObserverDialerTest {

    private static final long BATTLE = 7_000_000_163L;
    private static final long OBSERVER = 1001;
    private static final NodeRpcClients.Target RECORDED = new NodeRpcClients.Target("10.1.1.1", 21200, "inst-a");
    /** 直拨器发调用时用的目标：实例段恒为空串（只按地址缓存客户端）。 */
    private static final NodeRpcClients.Target DIALED = new NodeRpcClients.Target("10.1.1.1", 21200, "");
    private static final BattlePlacement PLACEMENT = BattlePlacement.newBuilder().setBattleId(BATTLE).setBattleNodeId(1)
            .setBattleInstanceId("inst-a").setRpcHost("10.1.1.1").setRpcPort(21200).setAttempt(1).setCreatedAtMs(1_800_000_000_000L).build();
    private static final AddObserverRequest ADD = AddObserverRequest.newBuilder().setBattleId(BATTLE).setObserverPlayerId(OBSERVER)
            .setRouting(BattleRouting.newBuilder().setSessionId(7).setGateNodeId(1).setGateInstanceId("gate-inst-z2").setZoneId(2))
            .setObserverName("acc-1001").build();

    /** 可脚本化的假 battle：只关心两个观众方法；每次调用按「先进先出的脚本，用完回到调通」应答。 */
    private static final class ScriptedBattle implements BattleNodeService {
        final List<AddObserverRequest> adds = new CopyOnWriteArrayList<>();
        final List<RemoveObserverRequest> removes = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final List<CompletableFuture<AddObserverResponse>> addScript = new ArrayList<>();
        final List<CompletableFuture<Empty>> removeScript = new ArrayList<>();

        synchronized ScriptedBattle nextAddTip(int tip) {
            addScript.add(CompletableFuture.completedFuture(AddObserverResponse.newBuilder()
                    .setErrorMessage(TipInfoMessage.newBuilder().setId(tip)).build()));
            return this;
        }

        synchronized CompletableFuture<AddObserverResponse> nextAddHangs() {
            CompletableFuture<AddObserverResponse> hanging = new CompletableFuture<>();
            addScript.add(hanging);
            return hanging;
        }

        synchronized CompletableFuture<Empty> nextRemoveHangs() {
            CompletableFuture<Empty> hanging = new CompletableFuture<>();
            removeScript.add(hanging);
            return hanging;
        }

        @Override
        public synchronized CompletableFuture<AddObserverResponse> addObserver(AddObserverRequest request) {
            adds.add(request);
            return addScript.isEmpty() ? CompletableFuture.completedFuture(AddObserverResponse.getDefaultInstance()) : addScript.remove(0);
        }

        @Override
        public synchronized CompletableFuture<Empty> removeObserver(RemoveObserverRequest request) {
            removes.add(request);
            threads.add(Thread.currentThread().getName() + (Thread.currentThread().isVirtual() ? "|virtual" : "|platform"));
            return removeScript.isEmpty() ? CompletableFuture.completedFuture(Empty.getDefaultInstance()) : removeScript.remove(0);
        }

        @Override
        public CompletableFuture<CreateBattleResult> createBattle(CreateBattleRequest request) {
            throw new AssertionError("观众 RPC 不建房");
        }

        @Override
        public CompletableFuture<Empty> destroyBattle(DestroyBattleRequest request) {
            throw new AssertionError("观众 RPC 不销毁房间");
        }

        @Override
        public CompletableFuture<IssueBattleTicketResponse> issueBattleTicket(IssueBattleTicketRequest request) {
            throw new AssertionError("观众 RPC 不补签");
        }
    }

    /** 探测原地址的替身（缺省 = 明确连不上）。 */
    private static final class ScriptedProbe implements ConnectProbe {
        final List<String> probes = new CopyOnWriteArrayList<>();
        volatile Result result = Result.REFUSED;

        @Override
        public Result probe(String host, int port, long timeoutMs) {
            probes.add(host + ":" + port);
            return result;
        }
    }

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final FakeNodeCalls<BattleNodeService> calls = new FakeNodeCalls<>();
    private final FakeBattleNodes directory = new FakeBattleNodes();
    private final ScriptedProbe probe = new ScriptedProbe();
    private final ScriptedBattle battle = new ScriptedBattle();
    private DefaultObserverDialer dialer;

    @BeforeEach
    void setUp() {
        calls.register(RECORDED, battle);
        dialer = new DefaultObserverDialer(new DirectPlacementDialer(calls, directory, probe), metrics);
    }

    private static Deadline in(long millis) {
        return Deadline.after(millis);
    }

    private static Duration threeSeconds() {
        return Duration.ofSeconds(3);
    }

    private double count(String method, String result) {
        return meters.get("xm.match.observer.rpc").tag("method", method).tag("result", result).counter().count();
    }

    private double total() {
        return meters.get("xm.match.observer.rpc").counters().stream().mapToDouble(counter -> counter.count()).sum();
    }

    // ================================================================ 调通

    @Test
    void add调通_请求原样发往落点记录里的地址_不按目录找_回Replied0_只发一次() {
        // 目录里的 1 号已经是别的实例、别的地址：直拨不看它（battle 丢了租约仍活着时照样能登记观众）
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21999));

        Outcome outcome = dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300));

        assertThat(outcome).isEqualTo(new Outcome.Replied(0));
        assertThat(battle.adds).as("请求逐字段原样到达：battle_id、观众、路由（zone 2 / gate 1 / 实例 / 会话）、observer_name").containsExactly(ADD);
        assertThat(calls.calls).singleElement().satisfies(call -> {
            assertThat(call.target()).isEqualTo(DIALED);
            assertThat(call.timeout()).isEqualTo(threeSeconds());
        });
        assertThat(directory.lookups).as("调通了不读目录").isEmpty();
        assertThat(probe.probes).isEmpty();
        assertThat(count("add", "replied")).isEqualTo(1.0);
        assertThat(total()).as("一次调用恰好记一个").isEqualTo(1.0);
    }

    @Test
    void add_battle的业务拒绝也是调通_tip原样带回_1004_1005_1008_1003() {
        battle.nextAddTip(1004).nextAddTip(1005).nextAddTip(1008).nextAddTip(1003);

        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("房间不存在：163 懒剔除的唯一信号").isEqualTo(new Outcome.Replied(1004));
        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("观众是参战者 / 参数").isEqualTo(new Outcome.Replied(1005));
        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("观众已满").isEqualTo(new Outcome.Replied(1008));
        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("签不出票").isEqualTo(new Outcome.Replied(1003));

        assertThat(count("add", "replied")).as("battle 的业务拒绝也算调通").isEqualTo(4.0);
        assertThat(total()).isEqualTo(4.0);
        assertThat(directory.lookups).isEmpty();
    }

    @Test
    void remove调通_请求带落点的battle_id_观众与reason_应答是Empty所以tip恒为0() {
        Outcome outcome = dialer.remove(PLACEMENT, OBSERVER, SpectateRules.REASON_ENTER_GATHER, threeSeconds(), in(3_000));

        assertThat(outcome).isEqualTo(new Outcome.Replied(0));
        assertThat(battle.removes).containsExactly(RemoveObserverRequest.newBuilder().setBattleId(BATTLE).setObserverPlayerId(OBSERVER)
                .setReason("enter_gather").build());
        assertThat(calls.calls).singleElement().satisfies(call -> assertThat(call.target()).isEqualTo(DIALED));
        assertThat(count("remove", "replied")).isEqualTo(1.0);
        assertThat(total()).isEqualTo(1.0);
    }

    // ================================================================ 建连失败的三种去向

    @Test
    void 建连失败_同号节点已换实例_原地址明确连不上_三条齐了才是Dead() {
        calls.unreachable(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21999));

        Outcome add = dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300));
        Outcome remove = dialer.remove(PLACEMENT, OBSERVER, SpectateRules.REASON_REWATCH, threeSeconds(), in(3_000));

        assertThat(add).isEqualTo(new Outcome.Dead());
        assertThat(remove).isEqualTo(new Outcome.Dead());
        assertThat(directory.lookups).as("按记录里的实例号比目录").containsExactly(BattleNodes.key(1, "inst-a"), BattleNodes.key(1, "inst-a"));
        assertThat(directory.deadlineLookups).as("目录读带着硬截止（裁决 3）：两次都是带截止的重载").hasSize(2).allSatisfy(remaining -> assertThat(remaining).isPositive());
        assertThat(probe.probes).containsExactly("10.1.1.1:21200", "10.1.1.1:21200");
        assertThat(battle.adds).as("请求确定没有送达").isEmpty();
        assertThat(count("add", "dead")).isEqualTo(1.0);
        assertThat(count("remove", "dead")).isEqualTo(1.0);
    }

    @Test
    void 建连失败_但没有换实例的正面证据_同一实例_目录缺席_目录读失败_都只是NotDelivered() {
        calls.unreachable(RECORDED);

        // 目录里没有这个号（进程丢了租约但可能还活着、条目刚过期）
        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("目录缺席").isInstanceOf(Outcome.NotDelivered.class);
        // 还是记录里的那个实例（目录最多滞后 15 s）
        directory.add(FakeBattleNodes.node(1, "inst-a", 21200));
        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("同一实例").isInstanceOf(Outcome.NotDelivered.class);
        // 目录读失败
        directory.readFailed = true;
        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("目录读失败").isInstanceOf(Outcome.NotDelivered.class);

        assertThat(probe.probes).as("没有「同号换实例」就不探测").isEmpty();
        assertThat(battle.adds).isEmpty();
        assertThat(count("add", "not_delivered")).isEqualTo(3.0);
        assertThat(count("add", "dead")).isZero();
        assertThat(total()).isEqualTo(3.0);
    }

    @Test
    void 建连失败且同号换了实例_但原地址连得上或探测没有结论_不判死_NotDelivered() {
        calls.unreachable(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21999));

        probe.result = ConnectProbe.Result.CONNECTED;
        Outcome connectable = dialer.remove(PLACEMENT, OBSERVER, SpectateRules.REASON_ENTER_GATHER, threeSeconds(), in(3_000));
        probe.result = ConnectProbe.Result.INCONCLUSIVE;
        Outcome inconclusive = dialer.remove(PLACEMENT, OBSERVER, SpectateRules.REASON_ENTER_GATHER, threeSeconds(), in(3_000));

        assertThat(connectable).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(inconclusive).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(probe.probes).hasSize(2);
        assertThat(count("remove", "not_delivered")).isEqualTo(2.0);
        assertThat(count("remove", "dead")).isZero();
    }

    // ================================================================ 结局不明

    @Test
    void 超时永不判死_哪怕同号已换实例_回Unknown_不读目录也不探测() {
        calls.timeout(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21999));

        Outcome add = dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300));
        Outcome remove = dialer.remove(PLACEMENT, OBSERVER, SpectateRules.REASON_REWATCH, threeSeconds(), in(3_300));

        assertThat(add).as("超时：battle 可能已经登记了这名观众").isInstanceOf(Outcome.Unknown.class);
        assertThat(remove).isInstanceOf(Outcome.Unknown.class);
        assertThat(directory.lookups).as("只有「请求确定没送达」才去读目录").isEmpty();
        assertThat(probe.probes).isEmpty();
        assertThat(count("add", "unknown")).isEqualTo(1.0);
        assertThat(count("remove", "unknown")).isEqualTo(1.0);
        assertThat(count("add", "dead")).isZero();
    }

    @Test
    void 连上之后断开_调用方鉴权失败_battle在途超限的异常完成_都是Unknown() {
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21999));

        calls.failWith(RECORDED, () -> TriRpcStatus.CANCELLED.asException());
        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("连上之后断开").isInstanceOf(Outcome.Unknown.class);

        calls.failWith(RECORDED, () -> new RpcException(RpcException.FORBIDDEN_EXCEPTION, "调用方鉴权失败"));
        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("调用方鉴权失败").isInstanceOf(Outcome.Unknown.class);

        // xm-battle 控制面在途已满时让 future 异常完成（BattleNodeServiceImpl.submit）：经 Triple 到调用方是对端回的错误
        calls.failWith(RECORDED, () -> new RejectedExecutionException("battle control plane overloaded (max in-flight 64)"));
        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("battle 在途超限").isInstanceOf(Outcome.Unknown.class);

        calls.failWith(RECORDED, () -> TriRpcStatus.UNAVAILABLE.withDescription("upstream 10.9.9.9:1 is unavailable").asException());
        assertThat(dialer.add(PLACEMENT, ADD, threeSeconds(), in(4_300))).as("对端转述它自己上游的 UNAVAILABLE 不是建连失败")
                .isInstanceOf(Outcome.Unknown.class);

        assertThat(directory.lookups).as("都不参与判死").isEmpty();
        assertThat(count("add", "unknown")).isEqualTo(4.0);
        assertThat(total()).isEqualTo(4.0);
    }

    @Test
    void battle迟迟不应答_等到硬截止就返回Unknown_不越过硬截止再等本地余量() {
        CompletableFuture<AddObserverResponse> hanging = battle.nextAddHangs();
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21999));

        // 超时故意给得很长（30 s），与硬截止（1 s）拉开距离：断言不依赖窄的时间窗。硬截止给 1 s 而不是几百毫秒：
        // 从创建截止到发调用之间即使卡住一下，也不会被判成「硬截止已到，没有发出调用」（NotDelivered）那一支
        long startedNanos = System.nanoTime();
        Outcome outcome = dialer.add(PLACEMENT, ADD, Duration.ofSeconds(30), in(1_000));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertThat(outcome).as("到点一律不判死：请求已经发出，结局不明").isInstanceOf(Outcome.Unknown.class);
        assertThat(elapsedMillis).as("等到硬截止（1 s）为止，不是调用方给的 30 s 超时，也不再另加本地余量").isBetween(990L, 20_000L);
        assertThat(battle.adds).as("请求确实发出去了").hasSize(1);
        assertThat(calls.calls).singleElement().satisfies(call -> assertThat(call.timeout().toMillis())
                .as("交给出站口的超时已按硬截止的剩余收短").isBetween(1L, 1_000L));
        assertThat(directory.lookups).isEmpty();
        assertThat(count("add", "unknown")).isEqualTo(1.0);
        hanging.complete(AddObserverResponse.getDefaultInstance());
    }

    // ================================================================ 没有时间了：不发调用

    @Test
    void 硬截止已过_不发调用_NotDelivered_不是Unknown() {
        Deadline passed = Deadline.after(0);

        Outcome add = dialer.add(PLACEMENT, ADD, threeSeconds(), passed);
        Outcome remove = dialer.remove(PLACEMENT, OBSERVER, SpectateRules.REASON_ENTER_GATHER, threeSeconds(), passed);

        assertThat(add).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(remove).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(calls.calls).as("什么都没有发出").isEmpty();
        assertThat(battle.adds).isEmpty();
        assertThat(battle.removes).isEmpty();
        assertThat(count("add", "not_delivered")).isEqualTo(1.0);
        assertThat(count("remove", "not_delivered")).isEqualTo(1.0);
    }

    @Test
    void 调用方给的超时为0_硬截止还没到_同样不发调用_NotDelivered_否则163会白白保留一个标记() {
        Outcome zero = dialer.add(PLACEMENT, ADD, Duration.ZERO, in(4_000));
        Outcome negative = dialer.add(PLACEMENT, ADD, Duration.ofMillis(-5), in(4_000));
        Outcome missing = dialer.remove(PLACEMENT, OBSERVER, SpectateRules.REASON_REWATCH, null, in(4_000));

        assertThat(zero).as("直拨器对 0 超时是「不发包、按超时收场」；照翻会成为结局不明，而请求确定没有发出").isInstanceOf(Outcome.NotDelivered.class);
        assertThat(negative).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(missing).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(calls.calls).isEmpty();
        assertThat(count("add", "not_delivered")).isEqualTo(2.0);
        assertThat(count("remove", "not_delivered")).isEqualTo(1.0);
        assertThat(count("add", "unknown")).isZero();
    }

    // ================================================================ 与直拨器的衔接

    @Test
    void 用的是带硬截止的重载_超时与硬截止原样交给直拨器_直拨结果四种各翻一种() {
        FakePlacementDialer placementDialer = new FakePlacementDialer(new FakeBattleNode());
        DefaultObserverDialer wrapped = new DefaultObserverDialer(placementDialer, metrics);
        Deadline hardStop = in(2_000);

        // 假 battle 上没有这间房 → addObserver 回 1004
        assertThat(wrapped.add(PLACEMENT, ADD, Duration.ofMillis(1_800), hardStop)).isEqualTo(new Outcome.Replied(1004));
        placementDialer.roomGone();
        assertThat(wrapped.add(PLACEMENT, ADD, Duration.ofMillis(1_800), hardStop)).isEqualTo(new Outcome.Dead());
        placementDialer.unavailable(PlacementDialer.Kind.NOT_DELIVERED);
        assertThat(wrapped.add(PLACEMENT, ADD, Duration.ofMillis(1_800), hardStop)).isInstanceOf(Outcome.NotDelivered.class);
        placementDialer.unavailable(PlacementDialer.Kind.TIMEOUT);
        assertThat(wrapped.add(PLACEMENT, ADD, Duration.ofMillis(1_800), hardStop)).isInstanceOf(Outcome.Unknown.class);
        placementDialer.unavailable(PlacementDialer.Kind.OTHER);
        assertThat(wrapped.remove(PLACEMENT, OBSERVER, SpectateRules.REASON_REWATCH, Duration.ofMillis(1_800), hardStop))
                .isInstanceOf(Outcome.Unknown.class);

        assertThat(placementDialer.dials).hasSize(5).allSatisfy(dialed -> {
            assertThat(dialed.placement()).isEqualTo(PLACEMENT);
            assertThat(dialed.timeout()).isEqualTo(Duration.ofMillis(1_800));
            assertThat(dialed.hardStop()).as("不带硬截止的旧重载（179 用）这里是 null").isSameAs(hardStop);
        });
        assertThat(count("add", "replied")).isEqualTo(1.0);
        assertThat(count("add", "dead")).isEqualTo(1.0);
        assertThat(count("add", "not_delivered")).isEqualTo(1.0);
        assertThat(count("add", "unknown")).isEqualTo(1.0);
        assertThat(count("remove", "unknown")).isEqualTo(1.0);
    }

    @Test
    void 直拨器违反约定抛了异常_不往外抛_按结局不明() {
        PlacementDialer broken = new PlacementDialer() {
            @Override
            public <R> Dial<R> dial(BattlePlacement placement, Duration timeout, Function<BattleNodeService, CompletableFuture<R>> call) {
                throw new AssertionError("观众 RPC 不用不带硬截止的重载");
            }

            @Override
            public <R> Dial<R> dial(BattlePlacement placement, Duration timeout, Deadline hardStop,
                                    Function<BattleNodeService, CompletableFuture<R>> call) {
                throw new IllegalStateException("注入的故障: 直拨器抛出");
            }
        };
        DefaultObserverDialer wrapped = new DefaultObserverDialer(broken, metrics);

        Outcome add = wrapped.add(PLACEMENT, ADD, threeSeconds(), in(4_300));
        Outcome remove = wrapped.remove(PLACEMENT, OBSERVER, SpectateRules.REASON_REWATCH, threeSeconds(), in(3_000));

        assertThat(add).as("不知道请求发没发出去：按结局不明（163 保留标记）").isInstanceOf(Outcome.Unknown.class);
        assertThat(remove).isInstanceOf(Outcome.Unknown.class);
        assertThat(count("add", "unknown")).isEqualTo(1.0);
        assertThat(count("remove", "unknown")).isEqualTo(1.0);
    }

    // ================================================================ removeAsync

    @Test
    void removeAsync_发出即返回_在自己的虚拟线程上跑_battle应答之后future才完成_超时固定3秒() throws Exception {
        CompletableFuture<Empty> hanging = battle.nextRemoveHangs();

        long startedNanos = System.nanoTime();
        CompletableFuture<Outcome> future = dialer.removeAsync(PLACEMENT, OBSERVER, SpectateRules.REASON_CONCURRENT_QUEUE);
        long returnedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertThat(returnedMillis).as("不等 battle：battle 此刻还没有应答").isLessThan(2_000);
        for (int i = 0; i < 2_000 && battle.removes.isEmpty(); i++) {
            Thread.sleep(5);
        }
        assertThat(battle.removes).as("调用已经发出").containsExactly(RemoveObserverRequest.newBuilder().setBattleId(BATTLE)
                .setObserverPlayerId(OBSERVER).setReason("concurrent_queue").build());
        assertThat(future).as("battle 还没应答").isNotDone();
        assertThat(battle.threads).singleElement().satisfies(thread -> assertThat(thread).startsWith("match-spectate-evict-").endsWith("|virtual"));
        assertThat(calls.calls).singleElement().satisfies(call -> assertThat(call.timeout().toMillis())
                .as("固定 3 s（MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS），不随 163 的剩余预算收短").isBetween(2_000L, 3_000L));

        hanging.complete(Empty.getDefaultInstance());

        assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(new Outcome.Replied(0));
        assertThat(count("remove", "replied")).isEqualTo(1.0);
        assertThat(total()).isEqualTo(1.0);
    }

    @Test
    void removeAsync_没调通也正常完成_永不异常完成() throws Exception {
        calls.unreachable(RECORDED);
        Outcome notDelivered = dialer.removeAsync(PLACEMENT, OBSERVER, SpectateRules.REASON_CONCURRENT_QUEUE).get(10, TimeUnit.SECONDS);
        calls.timeout(RECORDED);
        Outcome unknown = dialer.removeAsync(PLACEMENT, OBSERVER, SpectateRules.REASON_CONCURRENT_QUEUE).get(10, TimeUnit.SECONDS);

        assertThat(notDelivered).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(unknown).isInstanceOf(Outcome.Unknown.class);
        assertThat(count("remove", "not_delivered")).isEqualTo(1.0);
        assertThat(count("remove", "unknown")).isEqualTo(1.0);
    }

    @Test
    void removeAsync_起不了线程_不抛_future以NotDelivered完成_调用没有发出() throws Exception {
        Executor refusing = task -> {
            throw new RejectedExecutionException("注入的故障: 执行器拒收");
        };
        DefaultObserverDialer wrapped = new DefaultObserverDialer(new DirectPlacementDialer(calls, directory, probe), metrics, refusing);

        CompletableFuture<Outcome> future = wrapped.removeAsync(PLACEMENT, OBSERVER, SpectateRules.REASON_CONCURRENT_QUEUE);

        assertThat(future).isDone();
        assertThat(future.get(1, TimeUnit.SECONDS)).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(calls.calls).isEmpty();
        assertThat(count("remove", "not_delivered")).isEqualTo(1.0);
    }

    @Test
    void removeAsync_回调挂在结果上_在直拨的线程上被调_拿到的就是这一次的结局() throws Exception {
        AtomicReference<Outcome> seen = new AtomicReference<>();
        CompletableFuture<Void> callback = dialer.removeAsync(PLACEMENT, OBSERVER, SpectateRules.REASON_CONCURRENT_QUEUE).thenAccept(seen::set);

        callback.get(10, TimeUnit.SECONDS);

        assertThat(seen.get()).isEqualTo(new Outcome.Replied(0));
        assertThat(battle.removes).hasSize(1);
    }
}
