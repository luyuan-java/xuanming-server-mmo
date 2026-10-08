package com.game.match.placement;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.DubboGroups;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.CreateBattleResult;
import com.game.api.rpc.NodeRpcClients;
import com.game.match.gather.BattleNodes;
import com.game.match.placement.PlacementDialer.Dial;
import com.game.match.placement.RpcFailures.Kind;
import com.game.match.port.NodeClientCache;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.FakeBattleNodes;
import com.game.proto.AddObserverRequest;
import com.game.proto.AddObserverResponse;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.CreateBattleRequest;
import com.game.proto.DestroyBattleRequest;
import com.game.proto.Empty;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import com.game.proto.RemoveObserverRequest;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * 传输失败的三分类在<b>真 Triple</b> 上的形态（lead 裁决问题 4；match-spec §4.3）：battle 提供方与 match 的调用方各在自己的 Dubbo 框架模型里
 * （等价于两个进程，调用方鉴权过滤器照常生效；测试密钥由 surefire 注入），经生产用的 {@link NodeRpcClients} 直连。钉住三件事——
 * 连一个没人监听的端口是 {@code NOT_SENT}、慢应答是 {@code TIMEOUT}、对端回错是 {@code OTHER}——以及两条最容易判错的边界：
 * 对端回的 UNAVAILABLE（哪怕文案相同、哪怕是 future 异常完成）不是 {@code NOT_SENT}；调用在途时对端断开不是 {@code NOT_SENT}。
 * {@code NOT_SENT} 现在还决定 gather 的建房能不能不经销毁就解冻（lead 裁决 2026-10-08），所以建房这一跳也在这里对着刚关掉的端口钉一遍，
 * 并核对 Dubbo 文案里的地址与 {@code Target.address()} 写法一致（{@link RpcFailures#classify(Throwable, String)} 靠它）。
 *
 * <p>另钉 match 侧客户端缓存（{@link NodeClientCache}）在真 Dubbo 上的两件事：底层缓存按实例号重建时旧引用上在途的调用确实会被掐断
 * （这是「记下来的目标不走重建」与「补签直拨另用一份缓存」的前提），以及清掉空闲引用之后再用能重新建起来。
 *
 * <p>这些判定依赖 Dubbo 的异常形态：升级 Dubbo 之后这条测试先红，再去改 {@link RpcFailures}。缺省执行（不需要外部依赖）。
 *
 * <p><b>用例次序是有意固定的</b>：同一个 JVM 里销毁任何一个带 Triple 协议的 Dubbo 框架模型，都会把进程内<b>全部</b> Triple 服务端口关掉
 * （Dubbo 3.3.6 的端口复用服务表是进程级的）。所以要关掉对端的两条用例排在最后，各起自己的提供方；其余用例共用一个提供方。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RpcFailuresLoopbackTest {

    /** 会关掉对端（连带关掉共用提供方）的用例的次序：排在所有没标次序的用例之后。 */
    private static final int LAST_BUT_ONE = Integer.MAX_VALUE - 1;
    private static final int LAST = Integer.MAX_VALUE;

    /**
     * 假 battle 节点：补签按 battle_id 脚本化（1 = 永不应答，2 = future 异常完成，3 = 同步抛出，4 = 同步抛「同文案、别人的地址」的 UNAVAILABLE，
     * 5 = future 以「同文案、<b>就是本提供方的地址</b>」的 UNAVAILABLE 异常完成，6 = 同步抛同上，其余 = 正常）。
     */
    static final class ScriptedBattle implements BattleNodeService {
        final AtomicInteger issues = new AtomicInteger();
        final AtomicInteger creates = new AtomicInteger();

        @Override
        public CompletableFuture<IssueBattleTicketResponse> issueBattleTicket(IssueBattleTicketRequest request) {
            issues.incrementAndGet();
            long script = request.getBattleId();
            if (script == 1) {
                return new CompletableFuture<>();
            }
            if (script == 2) {
                return CompletableFuture.failedFuture(new IllegalStateException("提供方 future 异常完成"));
            }
            if (script == 3) {
                throw new IllegalStateException("提供方同步抛出");
            }
            if (script == 4) {
                throw TriRpcStatus.UNAVAILABLE.withDescription("upstream 127.0.0.1:1 is unavailable").asException();
            }
            if (script == 5) {
                return CompletableFuture.failedFuture(
                        TriRpcStatus.UNAVAILABLE.withDescription("upstream 127.0.0.1:" + port + " is unavailable").asException());
            }
            if (script == 6) {
                throw TriRpcStatus.UNAVAILABLE.withDescription("upstream 127.0.0.1:" + port + " is unavailable").asException();
            }
            return CompletableFuture.completedFuture(IssueBattleTicketResponse.newBuilder()
                    .setAssignment(BattleAssignedS2C.newBuilder().setBattleId(script).setHost("battle.example").setPort(12000)).build());
        }

        @Override
        public CompletableFuture<CreateBattleResult> createBattle(CreateBattleRequest request) {
            creates.incrementAndGet();
            return CompletableFuture.completedFuture(CreateBattleResult.getDefaultInstance());
        }

        @Override
        public CompletableFuture<Empty> destroyBattle(DestroyBattleRequest request) {
            return CompletableFuture.completedFuture(Empty.getDefaultInstance());
        }

        @Override
        public CompletableFuture<AddObserverResponse> addObserver(AddObserverRequest request) {
            return CompletableFuture.completedFuture(AddObserverResponse.getDefaultInstance());
        }

        @Override
        public CompletableFuture<Empty> removeObserver(RemoveObserverRequest request) {
            return CompletableFuture.completedFuture(Empty.getDefaultInstance());
        }
    }

    // 提供方与调用方整个类共用一份（建 / 拆 Dubbo 模型各要一两秒）；要把对端关掉的两条用例另起自己的提供方
    private static final ScriptedBattle battle = new ScriptedBattle();
    private static IsolatedDubboModule server;
    private static int port;
    private static int deadPort;
    private static NodeRpcClients<BattleNodeService> clients;
    /** match 侧的缓存外壳（包着另一份真的底层缓存；空闲阈值 50 ms，只在用例里手动清）。 */
    private static NodeRpcClients<BattleNodeService> cachedClients;
    private static NodeClientCache<BattleNodeService> cache;
    /** 本条用例开始时共用提供方已收到的补签数：断言「这条用例里对端收到了几次」用差值。 */
    private int issuesBefore;

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 与 xm-battle 的导出参数相同（register = false、group battle-node、tri）。 */
    static IsolatedDubboModule export(BattleNodeService ref, int port) {
        IsolatedDubboModule dubbo = IsolatedDubboModule.create("xm-match-test-battle-node");
        try {
            ProtocolConfig protocol = new ProtocolConfig("tri", port);
            protocol.setHost("127.0.0.1");
            ServiceConfig<BattleNodeService> service = new ServiceConfig<>(dubbo.module());
            service.setInterface(BattleNodeService.class);
            service.setRef(ref);
            service.setGroup(DubboGroups.BATTLE_NODE);
            service.setRegister(false);
            service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
            service.setProtocol(protocol);
            service.export();
            return dubbo;
        } catch (RuntimeException e) {
            dubbo.close();
            throw e;
        }
    }

    @BeforeAll
    static void startShared() throws IOException {
        port = freePort();
        deadPort = freePort();
        server = export(battle, port);
        clients = new NodeRpcClients<>("xm-match-test-caller", BattleNodeService.class, DubboGroups.BATTLE_NODE, Duration.ofSeconds(5),
                "test-match-battle-connect");
        cachedClients = new NodeRpcClients<>("xm-match-test-cached-caller", BattleNodeService.class, DubboGroups.BATTLE_NODE, Duration.ofSeconds(5),
                "test-match-cached-connect");
        cache = new NodeClientCache<>("test-cache", cachedClients, Duration.ofMillis(50));
    }

    @AfterAll
    static void stopShared() {
        // 销毁任何一个 Dubbo 框架模型都会关掉进程内全部 Triple 端口：这几样只能在整个类跑完之后关
        if (cache != null) {
            cache.close();
        }
        if (clients != null) {
            clients.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @BeforeEach
    void baseline() {
        issuesBefore = battle.issues.get();
    }

    /** 这条用例里共用提供方收到的补签数。 */
    private int issued() {
        return battle.issues.get() - issuesBefore;
    }

    private static NodeRpcClients.Target live() {
        return new NodeRpcClients.Target("127.0.0.1", port, "inst-a");
    }

    private static NodeRpcClients.Target dead() {
        return new NodeRpcClients.Target("127.0.0.1", deadPort, "inst-dead");
    }

    private static IssueBattleTicketRequest issue(long script) {
        return IssueBattleTicketRequest.newBuilder().setBattleId(script).setPlayerId(7).build();
    }

    private CompletableFuture<IssueBattleTicketResponse> call(NodeRpcClients.Target target, Duration timeout, long script) {
        return clients.call(target, timeout, node -> node.issueBattleTicket(issue(script)));
    }

    /** 等调用失败并取出它的异常（等待本身有上限，不会挂住测试进程）。 */
    private static Throwable failureOf(CompletableFuture<?> future) throws Exception {
        try {
            Object value = future.get(30, TimeUnit.SECONDS);
            throw new AssertionError("期望调用失败，却得到了应答: " + value);
        } catch (ExecutionException e) {
            return e.getCause();
        }
    }

    private BattlePlacement placement(NodeRpcClients.Target target, int nodeId) {
        return BattlePlacement.newBuilder().setBattleId(900).setBattleNodeId(nodeId).setBattleInstanceId(target.instanceId())
                .setRpcHost(target.host()).setRpcPort(target.port()).setAttempt(1).build();
    }

    // ================================================================ 三种失败

    @Test
    void 调通时应答原样带回() throws Exception {
        IssueBattleTicketResponse reply = call(live(), Duration.ofSeconds(5), 77).get(30, TimeUnit.SECONDS);

        assertThat(reply.getAssignment().getBattleId()).isEqualTo(77);
        assertThat(reply.getAssignment().getHost()).isEqualTo("battle.example");
    }

    @Test
    void 连一个没人监听的端口_NOT_SENT_请求确定没有送达() throws Exception {
        // 第一次要等建连失败（Windows 上拒绝连接约 2 s 才返回），给足预算；建完客户端之后的调用立即失败
        Throwable first = failureOf(call(dead(), Duration.ofSeconds(15), 77));
        long started = System.nanoTime();
        Throwable second = failureOf(call(dead(), Duration.ofSeconds(3), 77));
        long secondElapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(RpcFailures.classify(first)).as("首次：%s", first).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(second)).as("再次：%s", second).isEqualTo(Kind.NOT_SENT);
        // Dubbo 3.3.6 实测：首次是 StatusRpcException UNAVAILABLE「upstream <地址> is unavailable」（发包前连接不在）；
        // 建完客户端之后是集群层的 RpcException「No provider available」（这个直连地址已被标成不可用，选路阶段就失败）
        assertThat(first.toString()).as("首次的形态").contains("upstream " + dead().address() + " is unavailable");
        assertThat(RpcFailures.classify(first, dead().address())).as("核对地址也认：Dubbo 文案里的地址与 Target.address() 写法一致 %s", first)
                .isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(first, live().address())).as("同一句文案，地址不是这一次的目标就不算 %s", first).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(second, dead().address())).as("再次（核对地址）：%s", second).isEqualTo(Kind.NOT_SENT);
        assertThat(secondElapsedMs).as("发包之前就发现连接不在，不等超时").isLessThan(2_000);
        assertThat(issued()).isZero();
    }

    @Test
    void 慢应答_TIMEOUT_请求其实已送达() throws Exception {
        long started = System.nanoTime();
        Throwable error = failureOf(call(live(), Duration.ofMillis(400), 1));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(RpcFailures.classify(error)).as("%s", error).isEqualTo(Kind.TIMEOUT);
        assertThat(elapsedMs).isBetween(300L, 5_000L);
        assertThat(issued()).as("对端确实收到了这次调用：超时不能当作没送达").isEqualTo(1);
    }

    @Test
    void 剩余预算为0_不发包_按TIMEOUT() throws Exception {
        Throwable error = failureOf(call(live(), Duration.ZERO, 77));

        assertThat(RpcFailures.classify(error)).isEqualTo(Kind.TIMEOUT);
        assertThat(issued()).isZero();
    }

    @Test
    void 对端回错_OTHER_future异常完成与同步抛出都是() throws Exception {
        Throwable failedFuture = failureOf(call(live(), Duration.ofSeconds(5), 2));
        Throwable thrown = failureOf(call(live(), Duration.ofSeconds(5), 3));

        assertThat(RpcFailures.classify(failedFuture)).as("%s", failedFuture).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(thrown)).as("%s", thrown).isEqualTo(Kind.OTHER);
        assertThat(issued()).isEqualTo(2);
    }

    @Test
    void 对端回的UNAVAILABLE哪怕文案与本地的相同_也是OTHER_不参与判死() throws Exception {
        Throwable error = failureOf(call(live(), Duration.ofSeconds(5), 4));

        assertThat(error.toString()).as("对端的状态确实是 UNAVAILABLE").contains("UNAVAILABLE").contains("is unavailable");
        assertThat(RpcFailures.classify(error)).as("%s", error).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(error, live().address())).as("核对地址：句中是别人的地址 %s", error).isEqualTo(Kind.OTHER);
    }

    /**
     * 最坏的情形：对端回的 UNAVAILABLE 不但文案相同，句中的地址还恰好就是调用方拨的这个地址（核对地址也拦不住），并且是以 future 异常完成
     * 的方式回的。请求明明已经送达、对端已经执行——判成「没送达」的话 gather 会不经销毁就解冻全员。靠的是线上往返之后描述不再是整句。
     */
    @Test
    void 对端回的UNAVAILABLE文案相同且地址就是本次的目标_future异常完成与同步抛出_都不是NOT_SENT() throws Exception {
        Throwable failedFuture = failureOf(call(live(), Duration.ofSeconds(5), 5));
        Throwable thrown = failureOf(call(live(), Duration.ofSeconds(5), 6));

        assertThat(failedFuture.toString()).as("对端回的确实是那句文案").contains("upstream 127.0.0.1:" + port + " is unavailable");
        assertThat(thrown.toString()).contains("upstream 127.0.0.1:" + port + " is unavailable");
        assertThat(RpcFailures.classify(failedFuture, live().address())).as("future 异常完成：%s", failedFuture).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(thrown, live().address())).as("同步抛出：%s", thrown).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(failedFuture)).isEqualTo(Kind.OTHER);
        assertThat(RpcFailures.classify(thrown)).isEqualTo(Kind.OTHER);
        assertThat(issued()).as("两次请求都到了对端").isEqualTo(2);
    }

    // ================================================================ 对端进程退出

    @Test
    @Order(LAST_BUT_ONE)
    void 曾经连上_对端进程退出之后_NOT_SENT_过一会儿集群层的失败同样是() throws Exception {
        ScriptedBattle own = new ScriptedBattle();
        int ownPort = freePort();
        IsolatedDubboModule ownServer = export(own, ownPort);
        NodeRpcClients.Target target = new NodeRpcClients.Target("127.0.0.1", ownPort, "inst-own");
        try {
            call(target, Duration.ofSeconds(5), 77).get(30, TimeUnit.SECONDS);
        } finally {
            ownServer.close();
        }

        Throwable rightAfter = failureOf(call(target, Duration.ofSeconds(3), 77));
        // 重连几轮失败之后，集群层把这个直连地址标成不可用，失败的形态会变
        Thread.sleep(2_500);
        Throwable later = failureOf(call(target, Duration.ofSeconds(3), 77));

        // gather 的建房这一跳：对着刚关掉的端口发，请求确定没有送达（据此不经销毁就按「没建房」补偿）
        Throwable create = failureOf(clients.call(target, Duration.ofSeconds(5), node -> node.createBattle(CreateBattleRequest.getDefaultInstance())));

        assertThat(RpcFailures.classify(rightAfter)).as("刚断开：%s", rightAfter).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(later)).as("2.5 s 之后：%s", later).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(rightAfter, target.address())).as("刚断开（核对地址）：%s", rightAfter).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(later, target.address())).as("2.5 s 之后（核对地址）：%s", later).isEqualTo(Kind.NOT_SENT);
        assertThat(RpcFailures.classify(create, target.address())).as("建房：%s", create).isEqualTo(Kind.NOT_SENT);
        assertThat(own.issues.get()).as("关闭之后没有请求到达对端").isEqualTo(1);
        assertThat(own.creates.get()).as("建房请求没有到达对端").isZero();
    }

    @Test
    @Order(LAST)
    void 调用在途时对端断开_OTHER_请求已经送达_不能当作没送达() throws Exception {
        ScriptedBattle own = new ScriptedBattle();
        int ownPort = freePort();
        IsolatedDubboModule ownServer = export(own, ownPort);
        NodeRpcClients.Target target = new NodeRpcClients.Target("127.0.0.1", ownPort, "inst-own");
        CompletableFuture<IssueBattleTicketResponse> hanging;
        try {
            hanging = call(target, Duration.ofSeconds(20), 1);
            for (int i = 0; i < 200 && own.issues.get() == 0; i++) {
                Thread.sleep(50);
            }
            assertThat(own.issues.get()).as("调用已经到了对端").isEqualTo(1);
        } finally {
            ownServer.close();
        }

        long started = System.nanoTime();
        Throwable error = failureOf(hanging);

        assertThat(RpcFailures.classify(error)).as("请求已送达，绝不能判成没送达：%s", error).isNotEqualTo(Kind.NOT_SENT);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("对端断开时在途调用立即失败，不用等到超时").isLessThan(10_000);
    }

    // ================================================================ 经直拨器（在虚拟线程上，同 gather / 6.5 的用法）

    /** 在虚拟线程上跑一段阻塞代码并取回结果。 */
    private static <T> T onVirtualThread(java.util.concurrent.Callable<T> body) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread thread = Thread.ofVirtual().name("test-dial").start(() -> {
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

    @Test
    void 直拨器_调通_建连失败且同号换实例且原地址连不上判房间没了_建连失败但同实例或不在册只是暂不可用() throws Exception {
        FakeBattleNodes directory = new FakeBattleNodes();
        // 真的 TCP 探测；上限放到 5 s：有的环境对没人监听的端口建连要等内核重试几次才报拒绝（生产缺省 300 ms；通常被拒绝是立即的）
        DirectPlacementDialer dialer = new DirectPlacementDialer(clients::call, directory, new TcpConnectProbe(), TcpConnectProbe.MAX_TIMEOUT_MS);

        Dial<IssueBattleTicketResponse> replied = onVirtualThread(
                () -> dialer.dial(placement(live(), 1), Duration.ofSeconds(5), node -> node.issueBattleTicket(issue(77))));
        assertThat(replied).isInstanceOf(Dial.Replied.class);
        assertThat(((Dial.Replied<IssueBattleTicketResponse>) replied).reply().getAssignment().getBattleId()).isEqualTo(77);
        assertThat(directory.lookups).as("调通了就不读目录").isEmpty();

        // 先把连不上的那个地址的客户端建出来（首次建连要等拒绝连接返回）。直拨器的直连目标实例段恒为空串：照它建，后面才不会再重建
        failureOf(call(new NodeRpcClients.Target("127.0.0.1", deadPort, ""), Duration.ofSeconds(15), 77));

        // 目录里没有 9 号：不能证明房间没了
        Dial<IssueBattleTicketResponse> absent = onVirtualThread(
                () -> dialer.dial(placement(dead(), 9), Duration.ofSeconds(3), node -> node.issueBattleTicket(issue(77))));
        assertThat(absent).isInstanceOfSatisfying(Dial.Unavailable.class, u -> assertThat(u.kind()).isEqualTo(PlacementDialer.Kind.NOT_DELIVERED));

        // 9 号还是记录里的那个实例（目录滞后）：同样不判死
        directory.add(FakeBattleNodes.node(9, "inst-dead", deadPort));
        Dial<IssueBattleTicketResponse> same = onVirtualThread(
                () -> dialer.dial(placement(dead(), 9), Duration.ofSeconds(3), node -> node.issueBattleTicket(issue(77))));
        assertThat(same).isInstanceOfSatisfying(Dial.Unavailable.class, u -> assertThat(u.kind()).isEqualTo(PlacementDialer.Kind.NOT_DELIVERED));

        // 9 号已被别的进程接手，原地址（没人监听）明确连不上：证据齐了。预算给足，探测要等到拒绝返回
        directory.set(FakeBattleNodes.node(9, "inst-successor", port));
        Dial<IssueBattleTicketResponse> gone = onVirtualThread(
                () -> dialer.dial(placement(dead(), 9), Duration.ofSeconds(10), node -> node.issueBattleTicket(issue(77))));
        assertThat(gone).isInstanceOf(Dial.RoomGone.class);
        assertThat(directory.lookups).containsExactly(BattleNodes.key(9, "inst-dead"), BattleNodes.key(9, "inst-dead"), BattleNodes.key(9, "inst-dead"));

        // 同样是「没送达 + 同号换实例」，但探测没有结论（超时、预算不够）：不判死
        DirectPlacementDialer impatient = new DirectPlacementDialer(clients::call, directory, (host, probedPort, timeoutMs) -> {
            assertThat(host).isEqualTo("127.0.0.1");
            assertThat(probedPort).isEqualTo(deadPort);
            return ConnectProbe.Result.INCONCLUSIVE;
        });
        Dial<IssueBattleTicketResponse> unsure = onVirtualThread(
                () -> impatient.dial(placement(dead(), 9), Duration.ofSeconds(3), node -> node.issueBattleTicket(issue(77))));
        assertThat(unsure).isInstanceOfSatisfying(Dial.Unavailable.class, u -> assertThat(u.kind()).isEqualTo(PlacementDialer.Kind.NOT_DELIVERED));
    }

    @Test
    void 直拨器_超时哪怕同号已换实例也只是暂不可用_对端回错同样不读目录() throws Exception {
        FakeBattleNodes directory = new FakeBattleNodes();
        directory.add(FakeBattleNodes.node(1, "inst-successor", port));
        DirectPlacementDialer dialer = new DirectPlacementDialer(clients::call, directory, (host, probedPort, timeoutMs) -> {
            throw new AssertionError("超时与对端回错都不该走到探测");
        });

        Dial<IssueBattleTicketResponse> slow = onVirtualThread(
                () -> dialer.dial(placement(live(), 1), Duration.ofMillis(400), node -> node.issueBattleTicket(issue(1))));
        Dial<IssueBattleTicketResponse> rejected = onVirtualThread(
                () -> dialer.dial(placement(live(), 1), Duration.ofSeconds(5), node -> node.issueBattleTicket(issue(3))));

        assertThat(slow).isInstanceOfSatisfying(Dial.Unavailable.class, u -> assertThat(u.kind()).isEqualTo(PlacementDialer.Kind.TIMEOUT));
        assertThat(rejected).isInstanceOfSatisfying(Dial.Unavailable.class, u -> assertThat(u.kind()).isEqualTo(PlacementDialer.Kind.OTHER));
        assertThat(directory.lookups).as("只有建连失败才读目录：超时不参与判死").isEmpty();
    }

    // ================================================================ match 侧的客户端缓存（NodeClientCache）在真 Dubbo 上

    private static CompletableFuture<IssueBattleTicketResponse> viaCache(NodeRpcClients.Target target, boolean remembered, Duration timeout,
                                                                         long script) {
        return remembered ? cache.calls().callRemembered(target, timeout, node -> node.issueBattleTicket(issue(script)))
                : cache.calls().call(target, timeout, node -> node.issueBattleTicket(issue(script)));
    }

    /**
     * 底层缓存的键只有地址：同一地址上实例号不同就销毁旧引用再建，<b>旧引用上在途的调用当场失败</b>。所以带着先前记下的实例号发调用
     * （补偿的取消、回滚的销毁）不能走这条路——节点原地重启之后，它会把别的 gather 正在用的新引用顶掉。
     */
    @Test
    void 记下来的目标不重建引用_在途调用不受影响_照实例号重建则会掐断在途调用() throws Exception {
        NodeRpcClients.Target current = new NodeRpcClients.Target("127.0.0.1", port, "inst-current");
        NodeRpcClients.Target stale = new NodeRpcClients.Target("127.0.0.1", port, "inst-stale");
        CompletableFuture<IssueBattleTicketResponse> inFlight = viaCache(current, false, Duration.ofSeconds(30), 1);
        for (int i = 0; i < 200 && issued() == 0; i++) {
            Thread.sleep(50);
        }
        assertThat(issued()).as("在途的调用已经到了对端").isEqualTo(1);

        // 记下来的旧实例号：用这个地址上现有的引用
        IssueBattleTicketResponse reply = viaCache(stale, true, Duration.ofSeconds(5), 77).get(30, TimeUnit.SECONDS);

        assertThat(reply.getAssignment().getBattleId()).as("请求照样到了占着这个地址的进程").isEqualTo(77);
        Thread.sleep(300);
        assertThat(inFlight).as("没有重建引用：在途的调用还在等").isNotDone();
        assertThat(cachedClients.size()).isEqualTo(1);

        // 对照：同一个旧实例号走「刚从目录读出来」的那条路 → 底层照它重建 → 在途的调用被掐断
        long started = System.nanoTime();
        viaCache(stale, false, Duration.ofSeconds(5), 77).get(30, TimeUnit.SECONDS);
        Throwable killed = failureOf(inFlight);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("旧引用一销毁，其上在途的调用当场失败，不是等到 30 s 超时").isLessThan(15_000);
        assertThat(RpcFailures.classify(killed, current.address())).as("被掐断的调用已经送达过对端，不能判成没送达：%s", killed).isNotEqualTo(Kind.NOT_SENT);
    }

    @Test
    void 空闲的引用被清掉_底层缓存里也没了_再用时重新建_照常调通() throws Exception {
        NodeRpcClients.Target target = new NodeRpcClients.Target("127.0.0.1", port, "inst-sweep");
        assertThat(viaCache(target, false, Duration.ofSeconds(5), 77).get(30, TimeUnit.SECONDS).getAssignment().getBattleId()).isEqualTo(77);
        assertThat(cachedClients.size()).isEqualTo(1);

        Thread.sleep(200);
        int evicted = cache.sweepIdle();

        assertThat(evicted).as("空闲超过 50 ms").isEqualTo(1);
        assertThat(cache.size()).isZero();
        assertThat(cachedClients.size()).as("底层的引用确实销毁了：不再对这个地址保持连接与重连").isZero();
        assertThat(viaCache(target, false, Duration.ofSeconds(5), 78).get(30, TimeUnit.SECONDS).getAssignment().getBattleId()).isEqualTo(78);
        assertThat(cachedClients.size()).isEqualTo(1);
    }
}
