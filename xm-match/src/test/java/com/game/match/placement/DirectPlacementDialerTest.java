package com.game.match.placement;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.rpc.NodeRpcClients;
import com.game.common.deadline.Deadline;
import com.game.match.gather.BattleNodes;
import com.game.match.placement.PlacementDialer.Dial;
import com.game.match.placement.PlacementDialer.Kind;
import com.game.match.proto.BattlePlacement;
import com.game.match.testing.FakeBattleNode;
import com.game.match.testing.FakeBattleNodes;
import com.game.match.testing.FakeNodeCalls;
import com.game.proto.BattlePlayerSnapshot;
import com.game.proto.CreateBattleRequest;
import com.game.proto.IssueBattleTicketRequest;
import com.game.proto.IssueBattleTicketResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.rpc.RpcException;
import org.apache.dubbo.rpc.TriRpcStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 按落点记录直拨并分类（match-spec §4.3 第 4–6 行）：按<b>记录里的</b>地址拨、不按目录找；只有「请求没送达 + 同号节点已换实例 + 原地址明确连不上」
 * 才判这一局没了；超时永不判死；分不清的都只是暂不可用。真 Dubbo 的异常形态见 {@code RpcFailuresLoopbackTest}，这里用内存出站口测判定本身。
 * 带硬截止的重载（6.5 的观众 RPC 用）同一套判定，另加「到点一律不判死」；不带截止的重载（179）行为不变。
 */
class DirectPlacementDialerTest {

    private static final long BATTLE = 7_000_000_077L;
    private static final long PLAYER = 1001;
    private static final NodeRpcClients.Target RECORDED = new NodeRpcClients.Target("10.1.1.1", 21200, "inst-a");

    /** 探测原地址的替身：记下每次探测，结论可换（缺省 = 明确连不上）。 */
    private static final class ScriptedProbe implements ConnectProbe {
        final List<String> probes = new ArrayList<>();
        final List<Long> timeouts = new ArrayList<>();
        Result result = Result.REFUSED;
        RuntimeException error;

        @Override
        public Result probe(String host, int port, long timeoutMs) {
            probes.add(host + ":" + port);
            timeouts.add(timeoutMs);
            if (error != null) {
                throw error;
            }
            return result;
        }
    }

    private final FakeNodeCalls<BattleNodeService> calls = new FakeNodeCalls<>();
    private final FakeBattleNodes directory = new FakeBattleNodes();
    private final FakeBattleNode battle = new FakeBattleNode();
    private final ScriptedProbe probe = new ScriptedProbe();
    private DirectPlacementDialer dialer;

    @BeforeEach
    void setUp() {
        calls.register(RECORDED, battle);
        battle.room(CreateBattleRequest.newBuilder().setBattleId(BATTLE).setDeadlineMs(123_456)
                .addPlayers(BattlePlayerSnapshot.newBuilder().setPlayerId(PLAYER)).build());
        dialer = new DirectPlacementDialer(calls, directory, probe);
    }

    private static BattlePlacement placement() {
        return BattlePlacement.newBuilder().setBattleId(BATTLE).setBattleNodeId(1).setBattleInstanceId("inst-a").setRpcHost("10.1.1.1")
                .setRpcPort(21200).setAttempt(1).build();
    }

    private Dial<IssueBattleTicketResponse> dial(BattlePlacement placement) {
        return dialer.dial(placement, Duration.ofSeconds(3),
                node -> node.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(BATTLE).setPlayerId(PLAYER).build()));
    }

    private static Dial.Unavailable<?> unavailable(Dial<?> dial) {
        assertThat(dial).isInstanceOf(Dial.Unavailable.class);
        return (Dial.Unavailable<?>) dial;
    }

    @Test
    void 调通_按记录里的地址拨_直连目标的实例段恒为空串_应答原样带回_不读目录() {
        // 目录里的 1 号已经是别的实例、别的地址：直拨不看它
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21999));

        Dial<IssueBattleTicketResponse> dial = dial(placement());

        assertThat(dial).isInstanceOf(Dial.Replied.class);
        IssueBattleTicketResponse reply = ((Dial.Replied<IssueBattleTicketResponse>) dial).reply();
        assertThat(reply.getAssignment().getBattleId()).isEqualTo(BATTLE);
        assertThat(reply.getAssignment().getExpireAtMs()).isEqualTo(123_456);
        assertThat(calls.calls).singleElement().satisfies(call -> {
            assertThat(call.target()).as("battle 的请求不带实例号，实例段只是客户端缓存的键：固定成空串，同一地址永远是同一个客户端")
                    .isEqualTo(new NodeRpcClients.Target("10.1.1.1", 21200, ""));
            assertThat(call.timeout()).isEqualTo(Duration.ofSeconds(3));
        });
        assertThat(directory.lookups).isEmpty();
        assertThat(probe.probes).as("调通了不探测").isEmpty();
    }

    @Test
    void 两条记录同一地址不同实例号_用的是同一个直连目标_不会来回重建客户端() {
        dial(placement());
        dial(placement().toBuilder().setBattleInstanceId("inst-b").build());
        dial(placement());

        assertThat(calls.calls).extracting(FakeNodeCalls.Call::target).as("客户端缓存按目标的实例段判断要不要销毁重建")
                .containsOnly(new NodeRpcClients.Target("10.1.1.1", 21200, ""));
    }

    @Test
    void battle的拒签也是调通_原样带回() {
        battle.nextIssue(IssueBattleTicketResponse.newBuilder().setErrorMessage(com.game.proto.TipInfoMessage.newBuilder().setId(1005)).build());

        Dial<IssueBattleTicketResponse> dial = dial(placement());

        assertThat(((Dial.Replied<IssueBattleTicketResponse>) dial).reply().getErrorMessage().getId()).isEqualTo(1005);
    }

    @Test
    void 建连失败_目录里同号节点已换实例_原地址明确连不上_这一局确实没了() {
        calls.unreachable(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        Dial<IssueBattleTicketResponse> dial = dial(placement());

        assertThat(dial).isInstanceOf(Dial.RoomGone.class);
        assertThat(directory.lookups).as("按记录的节点号与实例号比对").containsExactly(BattleNodes.key(1, "inst-a"));
        assertThat(probe.probes).as("探测的是记录里的地址").containsExactly("10.1.1.1:21200");
        assertThat(probe.timeouts).as("探测上限 300 ms").containsExactly(DirectPlacementDialer.PROBE_TIMEOUT_MS);
        assertThat(battle.issues).isEmpty();
    }

    /**
     * 「请求没送达」比「原地址连不上」宽：连接刚断、还没重连上时也是没送达，而原进程可能还活着（丢了租约、号被别的进程接手的那种）。
     * 原地址连得上就不能判死：客户端永久放弃的可能是一场还在的战斗。
     */
    @Test
    void 请求没送达且同号节点已换实例_但原地址连得上_不判死_只是暂不可用() {
        calls.failWith(RECORDED, () -> TriRpcStatus.UNAVAILABLE.withDescription("upstream 10.1.1.1:21200 is unavailable").asException());
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21999));
        probe.result = ConnectProbe.Result.CONNECTED;

        Dial.Unavailable<?> result = unavailable(dial(placement()));

        assertThat(result.kind()).isEqualTo(Kind.NOT_DELIVERED);
        assertThat(probe.probes).hasSize(1);
    }

    @Test
    void 探测没有结论或探测自己抛异常_同样不判死() {
        calls.unreachable(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        probe.result = ConnectProbe.Result.INCONCLUSIVE;
        assertThat(unavailable(dial(placement())).kind()).as("探测超时：丢包与主机被隔离也是超时").isEqualTo(Kind.NOT_DELIVERED);

        probe.error = new IllegalStateException("注入的故障: 探测出错");
        assertThat(unavailable(dial(placement())).kind()).isEqualTo(Kind.NOT_DELIVERED);
        assertThat(probe.probes).hasSize(2);
    }

    @Test
    void 探测不超出这次直拨余下的预算() {
        calls.unreachable(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        dialer.dial(placement(), Duration.ofMillis(120),
                node -> node.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(BATTLE).setPlayerId(PLAYER).build()));

        assertThat(probe.timeouts).singleElement().satisfies(timeoutMs -> assertThat(timeoutMs).as("直拨预算 120 ms，探测只能用余下的")
                .isLessThanOrEqualTo(120L));
    }

    @Test
    void 建连失败但目录里还是同一个实例_目录里没有这个号_目录读失败_都只是暂不可用() {
        calls.unreachable(RECORDED);

        directory.add(FakeBattleNodes.node(1, "inst-a", 21200));
        assertThat(unavailable(dial(placement())).kind()).as("同实例：目录最多滞后 15 s").isEqualTo(Kind.NOT_DELIVERED);

        directory.remove(1);
        assertThat(unavailable(dial(placement())).kind()).as("不在册：丢了租约的进程可能还活着").isEqualTo(Kind.NOT_DELIVERED);

        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));
        directory.readFailed = true;
        assertThat(unavailable(dial(placement())).kind()).as("目录读失败：不能证明任何事").isEqualTo(Kind.NOT_DELIVERED);
        assertThat(directory.lookups).hasSize(3);
        assertThat(probe.probes).as("目录这一条证据不成立就不必探测").isEmpty();
    }

    @Test
    void 超时哪怕同号已换实例_也只是暂不可用_根本不读目录() {
        calls.timeout(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        Dial.Unavailable<?> result = unavailable(dial(placement()));

        assertThat(result.kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(directory.lookups).as("超时永不判死").isEmpty();
        assertThat(probe.probes).isEmpty();
    }

    @Test
    void battle永不应答_本地等到点按超时() {
        calls.register(RECORDED, new HangingIssue(battle));
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        long started = System.nanoTime();
        Dial<IssueBattleTicketResponse> dial = dialer.dial(placement(), Duration.ofMillis(80),
                node -> node.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(BATTLE).setPlayerId(PLAYER).build()));

        assertThat(unavailable(dial).kind()).isEqualTo(Kind.TIMEOUT);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(70L, 3_000L);
        assertThat(directory.lookups).isEmpty();
    }

    @Test
    void 连上之后的失败_对端回错_鉴权失败_都是OTHER_不读目录() {
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        calls.failWith(RECORDED, () -> TriRpcStatus.CANCELLED.asException());
        assertThat(unavailable(dial(placement())).kind()).isEqualTo(Kind.OTHER);

        calls.failWith(RECORDED, () -> new RpcException(RpcException.FORBIDDEN_EXCEPTION, "调用方鉴权失败"));
        assertThat(unavailable(dial(placement())).kind()).isEqualTo(Kind.OTHER);

        calls.failWith(RECORDED, () -> TriRpcStatus.UNAVAILABLE.withDescription("UNAVAILABLE : upstream 10.9.9.9:1 is unavailable").asException());
        assertThat(unavailable(dial(placement())).kind()).as("对端回的 UNAVAILABLE 不是建连失败").isEqualTo(Kind.OTHER);

        calls.failWith(RECORDED, () -> TriRpcStatus.UNAVAILABLE.withDescription("upstream 10.9.9.9:1 is unavailable").asException());
        assertThat(unavailable(dial(placement())).kind()).as("整句文案相同，但句中的地址不是这一次拨的地址：是对端在转述它自己的上游").isEqualTo(Kind.OTHER);

        assertThat(directory.lookups).isEmpty();
        assertThat(probe.probes).isEmpty();
    }

    @Test
    void 发包前连接不在与集群层没有可用提供方_同样算建连失败_换了实例就判没了() {
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        calls.failWith(RECORDED, () -> TriRpcStatus.UNAVAILABLE.withDescription("upstream 10.1.1.1:21200 is unavailable").asException());
        assertThat(dial(placement())).isInstanceOf(Dial.RoomGone.class);

        calls.failWith(RECORDED, () -> new RpcException(RpcException.NO_INVOKER_AVAILABLE_AFTER_FILTER, "No provider available"));
        assertThat(dial(placement())).isInstanceOf(Dial.RoomGone.class);
    }

    @Test
    void 记录里没有实例号_建连失败也无从比对_不判死() {
        BattlePlacement noInstance = placement().toBuilder().clearBattleInstanceId().build();
        calls.unreachable(new NodeRpcClients.Target("10.1.1.1", 21200, ""));
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        Dial.Unavailable<?> result = unavailable(dial(noInstance));

        assertThat(result.kind()).isEqualTo(Kind.NOT_DELIVERED);
        assertThat(directory.lookups).isEmpty();
    }

    @Test
    void 记录里的地址不合法_不发调用_OTHER() {
        Dial.Unavailable<?> noPort = unavailable(dial(placement().toBuilder().setRpcPort(0).build()));
        Dial.Unavailable<?> noHost = unavailable(dial(placement().toBuilder().setRpcHost("").build()));
        Dial.Unavailable<?> hugePort = unavailable(dial(placement().toBuilder().setRpcPort(-1).build()));

        assertThat(noPort.kind()).isEqualTo(Kind.OTHER);
        assertThat(noHost.kind()).isEqualTo(Kind.OTHER);
        assertThat(hugePort.kind()).isEqualTo(Kind.OTHER);
        assertThat(calls.calls).isEmpty();
        assertThat(directory.lookups).isEmpty();
    }

    @Test
    void 应答为null_调用函数自己抛异常_都不抛出来_OTHER() {
        Dial<Object> nullReply = dialer.dial(placement(), Duration.ofSeconds(3), node -> CompletableFuture.completedFuture(null));
        Dial<Object> thrown = dialer.dial(placement(), Duration.ofSeconds(3), node -> {
            throw new IllegalStateException("调用方的 bug");
        });

        assertThat(unavailable(nullReply).kind()).isEqualTo(Kind.OTHER);
        assertThat(unavailable(thrown).kind()).isEqualTo(Kind.OTHER);
    }

    // ================================================================ 带硬截止的重载（6.5 的观众 RPC；lead 裁决 3）

    private Dial<IssueBattleTicketResponse> dial(BattlePlacement placement, Duration timeout, Deadline hardStop) {
        return dialer.dial(placement, timeout, hardStop,
                node -> node.issueBattleTicket(IssueBattleTicketRequest.newBuilder().setBattleId(BATTLE).setPlayerId(PLAYER).build()));
    }

    @Test
    void 硬截止充裕_与不带截止的重载同一套判定_调通_判死_同实例_超时() {
        Dial<IssueBattleTicketResponse> replied = dial(placement(), Duration.ofSeconds(3), Deadline.after(10_000));
        assertThat(replied).isInstanceOf(Dial.Replied.class);
        assertThat(calls.calls).singleElement().satisfies(call -> {
            assertThat(call.target()).isEqualTo(new NodeRpcClients.Target("10.1.1.1", 21200, ""));
            assertThat(call.timeout()).as("截止的剩余比超时长：超时原样").isEqualTo(Duration.ofSeconds(3));
        });
        assertThat(directory.lookups).isEmpty();

        calls.unreachable(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));
        assertThat(dial(placement(), Duration.ofSeconds(3), Deadline.after(10_000))).as("三条证据都在截止之前拿齐").isInstanceOf(Dial.RoomGone.class);
        assertThat(directory.deadlineLookups).as("目录读走带截止的重载").hasSize(1);
        assertThat(probe.timeouts).containsExactly(DirectPlacementDialer.PROBE_TIMEOUT_MS);

        directory.set(FakeBattleNodes.node(1, "inst-a", 21200));
        assertThat(unavailable(dial(placement(), Duration.ofSeconds(3), Deadline.after(10_000))).kind()).isEqualTo(Kind.NOT_DELIVERED);

        calls.timeout(RECORDED);
        directory.set(FakeBattleNodes.node(1, "inst-successor", 21200));
        assertThat(unavailable(dial(placement(), Duration.ofSeconds(3), Deadline.after(10_000))).kind()).as("超时永不判死").isEqualTo(Kind.TIMEOUT);
        assertThat(directory.lookups).as("超时不读目录：只有前两次建连失败各读了一次").hasSize(2);
    }

    @Test
    void 硬截止进来时已过_不发调用_不读目录_不探测_按没送达返回() {
        calls.unreachable(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        Dial.Unavailable<?> result = unavailable(dial(placement(), Duration.ofSeconds(3), Deadline.after(0)));

        assertThat(result.kind()).as("请求确定没有发出：是没送达，不是超时").isEqualTo(Kind.NOT_DELIVERED);
        assertThat(calls.calls).as("没有时间了就不发").isEmpty();
        assertThat(battle.issues).isEmpty();
        assertThat(directory.lookups).isEmpty();
        assertThat(probe.probes).isEmpty();
    }

    @Test
    void 交给出站口的超时不超过硬截止的剩余() {
        dial(placement(), Duration.ofSeconds(3), Deadline.after(400));

        assertThat(calls.calls).singleElement().satisfies(call -> assertThat(call.timeout().toMillis()).as("min(3 s, 截止的剩余)").isBetween(1L, 400L));
    }

    @Test
    void battle永不应答_等到硬截止就返回_不等满超时也不另加本地余量_按超时不判死() {
        calls.register(RECORDED, new HangingIssue(battle));
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        long started = System.nanoTime();
        Dial<IssueBattleTicketResponse> dial = dial(placement(), Duration.ofSeconds(3), Deadline.after(150));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(unavailable(dial).kind()).as("请求可能已经送达：结局不明").isEqualTo(Kind.TIMEOUT);
        assertThat(elapsedMs).as("超时给的是 3 s（不带截止的重载会等 3.25 s），硬截止 150 ms 到点就回").isBetween(100L, 2_800L);
        assertThat(directory.lookups).isEmpty();
        assertThat(probe.probes).isEmpty();
    }

    /**
     * 「到点不判死」：建连失败、目录里同号已换实例、探测也会回「明确连不上」——三条证据本来都能成立，但目录读把硬截止耗尽了，
     * 第三条来不及取，就只能回暂不可用。时间不够永远不会被当成「这一局没了」。
     */
    @Test
    void 请求没送达_目录读把硬截止耗尽_来不及探测_不判死() {
        calls.unreachable(RECORDED);
        List<Long> remainingAtLookup = new ArrayList<>();
        BattleNodes slowDirectory = new BattleNodes() {
            @Override
            public java.util.Optional<com.game.api.proto.BattleNodeInfo> pickRandom(java.util.Set<String> excludeKeys) {
                throw new UnsupportedOperationException();
            }

            @Override
            public Census census() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Lookup lookup(int nodeId, String instanceId) {
                throw new AssertionError("带硬截止的直拨不该走不带截止的目录读");
            }

            @Override
            public Lookup lookup(int nodeId, String instanceId, Deadline d) {
                remainingAtLookup.add(d.remainingMillis());
                while (!d.expired()) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
                return Lookup.OTHER_INSTANCE; // 压着截止才读出来
            }
        };
        dialer = new DirectPlacementDialer(calls, slowDirectory, probe);
        probe.result = ConnectProbe.Result.REFUSED;

        Dial.Unavailable<?> result = unavailable(dial(placement(), Duration.ofSeconds(3), Deadline.after(400)));

        assertThat(result.kind()).isEqualTo(Kind.NOT_DELIVERED);
        // 机器极忙时硬截止可能在读目录之前就到了（那就连目录都不读，同样不判死）：所以是「至多一次」
        assertThat(remainingAtLookup).as("目录读拿到的就是整次直拨的硬截止").hasSizeLessThanOrEqualTo(1)
                .allSatisfy(ms -> assertThat(ms).isBetween(1L, 400L));
        assertThat(probe.probes).as("没有预算了就不探测——哪怕探测会回「明确连不上」").isEmpty();
    }

    @Test
    void 请求没送达时硬截止已到_连目录都不读_不判死() {
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));
        Deadline hardStop = Deadline.after(80);
        // 出站口压着截止才报「连不上」（真实情形：建连超时恰好与硬截止同时到）
        com.game.match.port.NodeCalls<BattleNodeService> lateRefusal = new com.game.match.port.NodeCalls<>() {
            @Override
            public <R> CompletableFuture<R> call(NodeRpcClients.Target target, Duration timeout,
                                                 java.util.function.Function<BattleNodeService, CompletableFuture<R>> invocation) {
                while (!hardStop.expired()) {
                    java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
                }
                return CompletableFuture.failedFuture(new java.net.ConnectException("注入的故障: 连接被拒绝"));
            }
        };
        dialer = new DirectPlacementDialer(lateRefusal, directory, probe);

        Dial.Unavailable<?> result = unavailable(dial(placement(), Duration.ofSeconds(3), hardStop));

        assertThat(result.kind()).isEqualTo(Kind.NOT_DELIVERED);
        assertThat(directory.lookups).as("截止已到：不读目录").isEmpty();
        assertThat(probe.probes).isEmpty();
    }

    @Test
    void 探测的上限被硬截止的剩余夹住() {
        calls.unreachable(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        // 探测自己的上限放到 60 s、这次调用的超时 30 s：三者里最小的是硬截止的剩余（5 s），留足余量不靠窄时间窗
        dialer = new DirectPlacementDialer(calls, directory, probe, 60_000);

        Dial<IssueBattleTicketResponse> dial = dial(placement(), Duration.ofSeconds(30), Deadline.after(5_000));

        assertThat(dial).as("截止之内三条证据拿齐：照样判死").isInstanceOf(Dial.RoomGone.class);
        assertThat(probe.timeouts).singleElement().satisfies(timeoutMs -> assertThat(timeoutMs)
                .as("min(探测上限 60 s, 这次直拨余下的预算 ≈ 30 s, 硬截止的剩余 ≤ 5 s)").isBetween(1L, 5_000L));
    }

    @Test
    void 不带硬截止的重载行为不变_179的目录读仍走不带截止的那个_探测上限仍是300毫秒() {
        calls.unreachable(RECORDED);
        directory.add(FakeBattleNodes.node(1, "inst-successor", 21200));

        Dial<IssueBattleTicketResponse> dial = dial(placement());

        assertThat(dial).isInstanceOf(Dial.RoomGone.class);
        assertThat(directory.lookups).containsExactly(BattleNodes.key(1, "inst-a"));
        assertThat(directory.deadlineLookups).as("179 不给截止：目录读固定等 1 s 的那个重载").isEmpty();
        assertThat(probe.timeouts).containsExactly(DirectPlacementDialer.PROBE_TIMEOUT_MS);
        assertThat(DirectPlacementDialer.LOCAL_WAIT_GRACE_MS).as("179 的本地余量没有动").isEqualTo(250);
    }

    @Test
    void 硬截止为null是调用方的错() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> dial(placement(), Duration.ofSeconds(3), null))
                .isInstanceOf(NullPointerException.class).hasMessage("hardStop");
        assertThat(calls.calls).isEmpty();
    }

    /** 补签永不应答的 battle（其余方法转给真的假节点）。 */
    private record HangingIssue(BattleNodeService delegate) implements BattleNodeService {

        @Override
        public CompletableFuture<IssueBattleTicketResponse> issueBattleTicket(IssueBattleTicketRequest request) {
            return new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<com.game.api.proto.CreateBattleResult> createBattle(CreateBattleRequest request) {
            return delegate.createBattle(request);
        }

        @Override
        public CompletableFuture<com.game.proto.Empty> destroyBattle(com.game.proto.DestroyBattleRequest request) {
            return delegate.destroyBattle(request);
        }

        @Override
        public CompletableFuture<com.game.proto.AddObserverResponse> addObserver(com.game.proto.AddObserverRequest request) {
            return delegate.addObserver(request);
        }

        @Override
        public CompletableFuture<com.game.proto.Empty> removeObserver(com.game.proto.RemoveObserverRequest request) {
            return delegate.removeObserver(request);
        }
    }
}
