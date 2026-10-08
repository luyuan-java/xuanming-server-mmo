package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.DubboGroups;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.CreateBattleResult;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.deadline.Deadline;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.placement.ConnectProbe;
import com.game.match.placement.DirectPlacementDialer;
import com.game.match.placement.TcpConnectProbe;
import com.game.match.port.NodeCalls;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.ObserverDialer.Outcome;
import com.game.match.testing.FakeBattleNodes;
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
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.ReferenceConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 观众 RPC 的一条<b>真 Triple 回环</b>（spectate-spec §10.5）：battle 提供方与 match 的调用方各在自己的 Dubbo 框架模型里（等价于两个进程），
 * 调用方鉴权过滤器照常生效（测试密钥由 surefire 注入 {@code XM_DUBBO_SECRET}）。被测的是生产的整条出站链——
 * {@link DefaultObserverDialer} → 真的 {@link DirectPlacementDialer} → 生产用的直连客户端缓存 {@link NodeRpcClients}——在<b>虚拟线程</b>上调
 * （同 163 与 gather 的用法）。钉住：
 * <ul>
 *   <li>带调用方 MAC 的 {@code addObserver} / {@code removeObserver} 调得通，请求逐字段到达，battle 的业务拒绝（1004）原样带回；</li>
 *   <li><b>不带 MAC 的调用被拒</b>、进不到业务代码——对调用方是「结局不明」，不是「没送达」（163 据此保留标记，方向安全）；</li>
 *   <li>对一个<b>没人监听的端口</b>：请求确定没送达 → {@code NotDelivered}；同号节点已换实例且原地址明确连不上 → {@code Dead}；</li>
 *   <li>一个<b>故意挂起的提供方</b>：超时 → {@code Unknown}（请求其实已送达）；硬截止比调用的超时早时，到硬截止就返回。</li>
 * </ul>
 * 判定依赖 Dubbo 的异常形态（{@code RpcFailures}），升级 Dubbo 之后与 {@code RpcFailuresLoopbackTest} 一起先红。缺省执行（不需要外部依赖）。
 *
 * <p>提供方与调用方整个类共用一份、只在全部用例跑完之后关：同一个 JVM 里销毁任何一个带 Triple 协议的 Dubbo 框架模型，
 * 都会把进程内全部 Triple 服务端口关掉（Dubbo 3.3.6 的端口复用服务表是进程级的）。
 */
class ObserverRpcLoopbackTest {

    /** 发往这个 battle_id 的 addObserver / removeObserver 永不应答（故意挂起的提供方）。 */
    private static final long HANGING_BATTLE = 5_000_000_001L;
    /** 假 battle 上有这一间房；别的 battle_id 的 addObserver 回 1004。 */
    private static final long LIVE_BATTLE = 5_000_000_163L;
    private static final long OBSERVER = 1001;

    /** 假 battle 节点：只实现两个观众方法。 */
    static final class ScriptedBattle implements BattleNodeService {
        final List<AddObserverRequest> adds = new CopyOnWriteArrayList<>();
        final List<RemoveObserverRequest> removes = new CopyOnWriteArrayList<>();

        @Override
        public CompletableFuture<AddObserverResponse> addObserver(AddObserverRequest request) {
            adds.add(request);
            if (request.getBattleId() == HANGING_BATTLE) {
                return new CompletableFuture<>();
            }
            if (request.getBattleId() != LIVE_BATTLE) {
                return CompletableFuture.completedFuture(AddObserverResponse.newBuilder()
                        .setErrorMessage(TipInfoMessage.newBuilder().setId(1004)).build());
            }
            return CompletableFuture.completedFuture(AddObserverResponse.getDefaultInstance());
        }

        @Override
        public CompletableFuture<Empty> removeObserver(RemoveObserverRequest request) {
            removes.add(request);
            return request.getBattleId() == HANGING_BATTLE ? new CompletableFuture<>() : CompletableFuture.completedFuture(Empty.getDefaultInstance());
        }

        @Override
        public CompletableFuture<CreateBattleResult> createBattle(CreateBattleRequest request) {
            return CompletableFuture.completedFuture(CreateBattleResult.getDefaultInstance());
        }

        @Override
        public CompletableFuture<Empty> destroyBattle(DestroyBattleRequest request) {
            return CompletableFuture.completedFuture(Empty.getDefaultInstance());
        }

        @Override
        public CompletableFuture<IssueBattleTicketResponse> issueBattleTicket(IssueBattleTicketRequest request) {
            return CompletableFuture.completedFuture(IssueBattleTicketResponse.getDefaultInstance());
        }
    }

    private static final ScriptedBattle battle = new ScriptedBattle();
    private static IsolatedDubboModule server;
    private static IsolatedDubboModule anonymousModel;
    private static int port;
    private static int deadPort;
    /** 生产用的直连客户端缓存（带调用方 MAC）。 */
    private static NodeRpcClients<BattleNodeService> clients;
    /** 去掉调用方 MAC 过滤器的直连引用（冒充不知道密钥的调用方）。 */
    private static BattleNodeService anonymous;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final FakeBattleNodes directory = new FakeBattleNodes();
    private int addsBefore;
    private int removesBefore;

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeAll
    static void startShared() throws Exception {
        port = freePort();
        deadPort = freePort();
        // 与 xm-battle 的导出参数相同：register = false、group battle-node、tri
        server = IsolatedDubboModule.create("xm-match-test-observer-battle");
        ProtocolConfig protocol = new ProtocolConfig("tri", port);
        protocol.setHost("127.0.0.1");
        ServiceConfig<BattleNodeService> service = new ServiceConfig<>(server.module());
        service.setInterface(BattleNodeService.class);
        service.setRef(battle);
        service.setGroup(DubboGroups.BATTLE_NODE);
        service.setRegister(false);
        service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        service.setProtocol(protocol);
        service.export();

        clients = new NodeRpcClients<>("xm-match-test-observer-caller", BattleNodeService.class, DubboGroups.BATTLE_NODE, Duration.ofSeconds(3),
                "test-match-observer-connect");

        anonymousModel = IsolatedDubboModule.create("xm-match-test-observer-anonymous");
        ReferenceConfig<BattleNodeService> reference = new ReferenceConfig<>(anonymousModel.module());
        reference.setInterface(BattleNodeService.class);
        reference.setGroup(DubboGroups.BATTLE_NODE);
        reference.setUrl("tri://127.0.0.1:" + port);
        reference.setRetries(0);
        reference.setCheck(false);
        reference.setTimeout(5_000);
        reference.setFilter("-xmAuthConsumer");
        anonymous = reference.get();
        // 一个 Dubbo 模型上的第一次调用会在调用线程上同步加载负载均衡扩展（synchronized 里等资源加载）。放在这里的平台线程上做掉：
        // 落到用例的虚拟线程上会把它钉在载体线程上。生产路径没有这个问题——直连客户端缓存的第一次调用发生在它自己的建连线程上。
        try {
            anonymous.removeObserver(RemoveObserverRequest.getDefaultInstance()).get(30, TimeUnit.SECONDS);
            throw new AssertionError("不带 MAC 的调用不该调得通");
        } catch (ExecutionException expected) {
            // 鉴权被拒：预期
        }

        // 先把两个地址的客户端建出来（直拨器的直连目标实例段恒为空串，照它建，之后不会再重建）：
        // 首次对连不上的地址建连要等到拒绝连接返回（Windows 上约 2 s），不该算进各用例自己的预算
        clients.call(new NodeRpcClients.Target("127.0.0.1", port, ""), Duration.ofSeconds(15),
                node -> node.removeObserver(RemoveObserverRequest.newBuilder().setBattleId(LIVE_BATTLE).setObserverPlayerId(1).build()))
                .get(30, TimeUnit.SECONDS);
        try {
            clients.call(new NodeRpcClients.Target("127.0.0.1", deadPort, ""), Duration.ofSeconds(15),
                    node -> node.removeObserver(RemoveObserverRequest.getDefaultInstance())).get(30, TimeUnit.SECONDS);
            throw new AssertionError("没人监听的端口不该调得通");
        } catch (ExecutionException expected) {
            // 建连失败：预期
        }
    }

    @AfterAll
    static void stopShared() {
        // 销毁任何一个 Dubbo 框架模型都会关掉进程内全部 Triple 端口：只能在整个类跑完之后关
        if (clients != null) {
            clients.close();
        }
        if (anonymousModel != null) {
            anonymousModel.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void baseline() {
        addsBefore = battle.adds.size();
        removesBefore = battle.removes.size();
    }

    /** 这条用例里对端收到的 addObserver。 */
    private List<AddObserverRequest> added() {
        return List.copyOf(battle.adds.subList(addsBefore, battle.adds.size()));
    }

    private List<RemoveObserverRequest> removed() {
        return List.copyOf(battle.removes.subList(removesBefore, battle.removes.size()));
    }

    /** 生产的整条出站链：观众直拨 → 真的直拨器 → 带 MAC 的直连客户端缓存。探测原地址用真的 TCP 探测。 */
    private DefaultObserverDialer dialer() {
        return new DefaultObserverDialer(new DirectPlacementDialer(clients::call, directory, new TcpConnectProbe()), metrics);
    }

    private static BattlePlacement placement(long battleId, int rpcPort, String instanceId) {
        return BattlePlacement.newBuilder().setBattleId(battleId).setBattleNodeId(9).setBattleInstanceId(instanceId).setRpcHost("127.0.0.1")
                .setRpcPort(rpcPort).setAttempt(1).setCreatedAtMs(1_800_000_000_000L).build();
    }

    private static AddObserverRequest add(long battleId) {
        return AddObserverRequest.newBuilder().setBattleId(battleId).setObserverPlayerId(OBSERVER)
                .setRouting(BattleRouting.newBuilder().setSessionId(4242).setGateNodeId(1).setGateInstanceId("gate-inst-z2").setZoneId(2))
                .setObserverName("acc-1001").build();
    }

    private double count(String method, String result) {
        return meters.get("xm.match.observer.rpc").tag("method", method).tag("result", result).counter().count();
    }

    /** 在虚拟线程上跑一段阻塞代码并取回结果（163 与 gather 都是在虚拟线程上调观众 RPC 的）。 */
    private static <T> T onVirtualThread(Callable<T> body) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().name("match-spectate-test").start(() -> {
            try {
                result.set(body.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        assertThat(thread.join(Duration.ofSeconds(60))).as("虚拟线程在时限内结束").isTrue();
        if (error.get() != null) {
            throw new AssertionError("虚拟线程里的代码抛出了异常", error.get());
        }
        return result.get();
    }

    // ================================================================ 带 MAC：调得通

    @Test
    void 带MAC的addObserver_请求逐字段到达battle_登记成功回Replied0_房间不在时battle的1004原样带回() throws Exception {
        DefaultObserverDialer dialer = dialer();

        Outcome registered = onVirtualThread(
                () -> dialer.add(placement(LIVE_BATTLE, port, "inst-a"), add(LIVE_BATTLE), Duration.ofSeconds(3), Deadline.after(4_300)));
        Outcome roomMissing = onVirtualThread(
                () -> dialer.add(placement(77, port, "inst-a"), add(77), Duration.ofSeconds(3), Deadline.after(4_300)));

        assertThat(registered).isEqualTo(new Outcome.Replied(0));
        assertThat(roomMissing).as("battle 的业务拒绝在应答里，Dubbo 层成功").isEqualTo(new Outcome.Replied(1004));
        assertThat(added()).as("路由（zone 2 的 1 号 gate、实例、会话）与 observer_name 经 Triple 原样到达").containsExactly(add(LIVE_BATTLE), add(77));
        assertThat(directory.lookups).as("调通了不读目录").isEmpty();
        assertThat(count("add", "replied")).isEqualTo(2.0);
    }

    @Test
    void 带MAC的removeObserver_调得通_reason原样到达_同步的与异步发出的都是() throws Exception {
        DefaultObserverDialer dialer = dialer();
        BattlePlacement placement = placement(LIVE_BATTLE, port, "inst-a");

        Outcome sync = onVirtualThread(
                () -> dialer.remove(placement, OBSERVER, SpectateRules.REASON_ENTER_GATHER, Duration.ofSeconds(3), Deadline.after(3_000)));
        Outcome async = dialer.removeAsync(placement, OBSERVER, SpectateRules.REASON_CONCURRENT_QUEUE).get(30, TimeUnit.SECONDS);

        assertThat(sync).isEqualTo(new Outcome.Replied(0));
        assertThat(async).isEqualTo(new Outcome.Replied(0));
        assertThat(removed()).containsExactly(
                RemoveObserverRequest.newBuilder().setBattleId(LIVE_BATTLE).setObserverPlayerId(OBSERVER).setReason("enter_gather").build(),
                RemoveObserverRequest.newBuilder().setBattleId(LIVE_BATTLE).setObserverPlayerId(OBSERVER).setReason("concurrent_queue").build());
        assertThat(count("remove", "replied")).isEqualTo(2.0);
    }

    // ================================================================ 缺 MAC：被拒

    @Test
    void 不带调用方MAC的调用被拒_进不到业务代码_对调用方是结局不明而不是没送达() throws Exception {
        // 同一条出站链，只把最底下的直连引用换成去掉了 MAC 过滤器的那个
        NodeCalls<BattleNodeService> withoutMac = new NodeCalls<>() {
            @Override
            public <R> CompletableFuture<R> call(NodeRpcClients.Target target, Duration timeout,
                                                 java.util.function.Function<BattleNodeService, CompletableFuture<R>> invocation) {
                return invocation.apply(anonymous);
            }
        };
        DefaultObserverDialer dialer = new DefaultObserverDialer(new DirectPlacementDialer(withoutMac, directory, new TcpConnectProbe()), metrics);
        BattlePlacement placement = placement(LIVE_BATTLE, port, "inst-a");

        Outcome add = onVirtualThread(() -> dialer.add(placement, add(LIVE_BATTLE), Duration.ofSeconds(3), Deadline.after(4_300)));
        Outcome remove = onVirtualThread(
                () -> dialer.remove(placement, OBSERVER, SpectateRules.REASON_REWATCH, Duration.ofSeconds(3), Deadline.after(3_000)));

        assertThat(add).as("对端回的传输层错误：%s", add).isInstanceOf(Outcome.Unknown.class);
        assertThat(remove).as("%s", remove).isInstanceOf(Outcome.Unknown.class);
        assertThat(added()).as("鉴权没过：业务代码一行都没有执行").isEmpty();
        assertThat(removed()).isEmpty();
        assertThat(directory.lookups).as("连得上、被对端拒：不参与判死").isEmpty();
        assertThat(count("add", "unknown")).isEqualTo(1.0);
        assertThat(count("remove", "unknown")).isEqualTo(1.0);
    }

    // ================================================================ 没人监听的端口

    @Test
    void 没人监听的端口_请求确定没有送达_目录缺席或还是同一实例_NotDelivered_不等超时() throws Exception {
        DefaultObserverDialer dialer = dialer();
        BattlePlacement placement = placement(LIVE_BATTLE, deadPort, "inst-dead");

        // 超时故意给得很长（10 s）：「没等超时」的断言才不依赖窄的时间窗
        long startedNanos = System.nanoTime();
        Outcome absent = onVirtualThread(() -> dialer.add(placement, add(LIVE_BATTLE), Duration.ofSeconds(10), Deadline.after(12_000)));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        directory.add(FakeBattleNodes.node(9, "inst-dead", deadPort));
        Outcome sameInstance = onVirtualThread(
                () -> dialer.remove(placement, OBSERVER, SpectateRules.REASON_ENTER_GATHER, Duration.ofSeconds(3), Deadline.after(3_000)));

        assertThat(absent).as("建连失败，目录里没有 9 号：%s", absent).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(sameInstance).as("9 号还是记录里的那个实例：%s", sameInstance).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(elapsedMillis).as("发包之前就发现连接不在，不用等到 10 s 超时").isLessThan(6_000);
        assertThat(added()).isEmpty();
        assertThat(directory.deadlineLookups).as("没送达之后读目录带着硬截止").hasSize(2);
        assertThat(count("add", "not_delivered")).isEqualTo(1.0);
        assertThat(count("remove", "not_delivered")).isEqualTo(1.0);
    }

    @Test
    void 没人监听的端口_同号节点已换实例_原地址明确连不上_Dead_探测没有结论则仍是NotDelivered() throws Exception {
        directory.add(FakeBattleNodes.node(9, "inst-successor", port));
        BattlePlacement placement = placement(LIVE_BATTLE, deadPort, "inst-dead");
        // 探测用给定的结论：真的 TCP 探测对没人监听的端口在有的系统上要等 2 s 才报拒绝，生产的 300 ms 上限下那是「没有结论」
        List<String> probed = new CopyOnWriteArrayList<>();
        AtomicReference<ConnectProbe.Result> verdict = new AtomicReference<>(ConnectProbe.Result.REFUSED);
        ConnectProbe probe = (host, probedPort, timeoutMs) -> {
            probed.add(host + ":" + probedPort);
            return verdict.get();
        };
        DefaultObserverDialer dialer = new DefaultObserverDialer(new DirectPlacementDialer(clients::call, directory, probe), metrics);

        Outcome dead = onVirtualThread(() -> dialer.add(placement, add(LIVE_BATTLE), Duration.ofSeconds(3), Deadline.after(4_300)));
        verdict.set(ConnectProbe.Result.INCONCLUSIVE);
        Outcome unsure = onVirtualThread(() -> dialer.add(placement, add(LIVE_BATTLE), Duration.ofSeconds(3), Deadline.after(4_300)));

        assertThat(dead).as("没送达 + 同号换实例 + 原地址明确连不上：%s", dead).isEqualTo(new Outcome.Dead());
        assertThat(unsure).as("探测没有结论：不判死 %s", unsure).isInstanceOf(Outcome.NotDelivered.class);
        assertThat(probed).as("探测的是落点记录里的原地址").containsExactly("127.0.0.1:" + deadPort, "127.0.0.1:" + deadPort);
        assertThat(added()).as("两次请求都没有到达任何 battle（包括接手了 9 号的那个进程）").isEmpty();
        assertThat(count("add", "dead")).isEqualTo(1.0);
        assertThat(count("add", "not_delivered")).isEqualTo(1.0);
    }

    // ================================================================ 故意挂起的提供方

    @Test
    void 提供方挂起不应答_超时_Unknown_请求其实已送达_哪怕同号已换实例也不判死() throws Exception {
        directory.add(FakeBattleNodes.node(9, "inst-successor", port));
        DefaultObserverDialer dialer = dialer();
        BattlePlacement placement = placement(HANGING_BATTLE, port, "inst-a");

        long startedNanos = System.nanoTime();
        Outcome add = onVirtualThread(() -> dialer.add(placement, add(HANGING_BATTLE), Duration.ofMillis(500), Deadline.after(4_300)));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertThat(add).as("%s", add).isInstanceOf(Outcome.Unknown.class);
        assertThat(elapsedMillis).as("等满这一跳的超时（500 ms）才放弃；上界只防无限等").isBetween(400L, 10_000L);
        assertThat(added()).as("对端确实收到了这次调用：超时不能当作没送达（163 据此保留标记）").hasSize(1);
        assertThat(directory.lookups).as("超时不读目录、不参与判死").isEmpty();
        assertThat(count("add", "unknown")).isEqualTo(1.0);
        assertThat(count("add", "dead")).isZero();
    }

    @Test
    void 硬截止比这一跳的超时早_到硬截止就返回Unknown_不等满这一跳的超时() throws Exception {
        DefaultObserverDialer dialer = dialer();
        BattlePlacement placement = placement(HANGING_BATTLE, port, "inst-a");

        // 超时故意给得很长（10 s），与硬截止（600 ms）拉开距离：断言不依赖窄的时间窗
        long startedNanos = System.nanoTime();
        Outcome remove = onVirtualThread(
                () -> dialer.remove(placement, OBSERVER, SpectateRules.REASON_ENTER_GATHER, Duration.ofSeconds(10), Deadline.after(600)));
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);

        assertThat(remove).as("%s", remove).isInstanceOf(Outcome.Unknown.class);
        assertThat(elapsedMillis).as("夹在硬截止（600 ms）附近，不是调用方给的 10 s；下界：不早于硬截止就放弃").isBetween(500L, 6_000L);
        assertThat(removed()).hasSize(1);
        assertThat(count("remove", "unknown")).isEqualTo(1.0);
    }
}
