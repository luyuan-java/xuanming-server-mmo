package com.game.match.placement;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.BattleNodeService;
import com.game.api.rpc.NodeRpcClients;
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
