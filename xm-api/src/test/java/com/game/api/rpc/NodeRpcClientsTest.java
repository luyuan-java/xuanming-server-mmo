package com.game.api.rpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.DubboGroups;
import com.game.api.SceneBattleService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.proto.SceneBattleCall;
import com.game.api.proto.SceneBattleReply;
import com.game.api.proto.SceneBattleStatus;
import com.game.api.proto.SettlementDisposition;
import com.google.protobuf.ByteString;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 按节点直连的通用客户端缓存（scene-battle-spec Q20）在真 Triple 上（提供方与调用方各在自己的 Dubbo 框架模型里，等价于两个进程；调用方鉴权过滤器照常生效，
 * 测试密钥由 surefire 注入 {@code XM_DUBBO_SECRET}）：按 group 路由到 {@link SceneBattleService} 的四个方法、单次超时按调用传入且不重试、
 * 同地址换实例重建、预算为 0 不发包、关闭后拒绝。
 */
class NodeRpcClientsTest {

    private final FakeScene provider = new FakeScene();
    private IsolatedDubboModule server;
    private int port;
    private NodeRpcClients<SceneBattleService> clients;

    /** 按方法回不同的状态 / 处置；player_id = 0 的备战永不完成。 */
    static final class FakeScene implements SceneBattleService {
        final AtomicInteger prepares = new AtomicInteger();

        @Override
        public CompletableFuture<SceneBattleReply> prepareBattle(SceneBattleCall call) {
            prepares.incrementAndGet();
            if (call.getPlayerId() == 0) {
                return new CompletableFuture<>();
            }
            return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED)
                    .setBody(ByteString.copyFromUtf8(call.getTargetInstanceId())).build());
        }

        @Override
        public CompletableFuture<SceneBattleReply> cancelBattlePrepare(SceneBattleCall call) {
            return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_NOT_HERE).build());
        }

        @Override
        public CompletableFuture<SceneBattleReply> confirmBattle(SceneBattleCall call) {
            return CompletableFuture.completedFuture(SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_OVERLOADED).build());
        }

        @Override
        public CompletableFuture<SceneBattleReply> applySettlement(SceneBattleCall call) {
            return CompletableFuture.supplyAsync(() -> SceneBattleReply.newBuilder().setStatus(SceneBattleStatus.SCENE_BATTLE_HANDLED)
                    .setSettlement(SettlementDisposition.SETTLEMENT_APPLIED).build(), CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS));
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 与 scene 的导出参数相同（register = false、group scene-battle、tri）。 */
    static IsolatedDubboModule export(SceneBattleService ref, int port) {
        IsolatedDubboModule dubbo = IsolatedDubboModule.create("xm-api-test-scene-battle");
        try {
            ProtocolConfig protocol = new ProtocolConfig("tri", port);
            protocol.setHost("127.0.0.1");
            ServiceConfig<SceneBattleService> service = new ServiceConfig<>(dubbo.module());
            service.setInterface(SceneBattleService.class);
            service.setRef(ref);
            service.setGroup(DubboGroups.SCENE_BATTLE);
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

    @BeforeEach
    void setUp() throws IOException {
        port = freePort();
        server = export(provider, port);
        clients = new NodeRpcClients<>("xm-api-test-caller", SceneBattleService.class, DubboGroups.SCENE_BATTLE, Duration.ofSeconds(5),
                "test-node-rpc-connect");
    }

    @AfterEach
    void tearDown() {
        clients.close();
        server.close();
    }

    private NodeRpcClients.Target target(String instance) {
        return new NodeRpcClients.Target("127.0.0.1", port, instance);
    }

    private static SceneBattleCall call(long playerId, String instance) {
        return SceneBattleCall.newBuilder().setPlayerId(playerId).setTargetInstanceId(instance).build();
    }

    @Test
    void 四个方法按group路由_应答原样带回_同地址只建一个客户端() throws Exception {
        Duration timeout = Duration.ofSeconds(5);
        SceneBattleReply prepared = clients.call(target("a"), timeout, s -> s.prepareBattle(call(7, "a"))).get(10, TimeUnit.SECONDS);
        assertThat(prepared.getStatus()).isEqualTo(SceneBattleStatus.SCENE_BATTLE_HANDLED);
        assertThat(prepared.getBody().toStringUtf8()).isEqualTo("a");
        assertThat(clients.call(target("a"), timeout, s -> s.cancelBattlePrepare(call(7, "a"))).get(10, TimeUnit.SECONDS).getStatus())
                .isEqualTo(SceneBattleStatus.SCENE_BATTLE_NOT_HERE);
        assertThat(clients.call(target("a"), timeout, s -> s.confirmBattle(call(7, "a"))).get(10, TimeUnit.SECONDS).getStatus())
                .isEqualTo(SceneBattleStatus.SCENE_BATTLE_OVERLOADED);
        assertThat(clients.call(target("a"), timeout, s -> s.applySettlement(call(7, "a"))).get(10, TimeUnit.SECONDS).getSettlement())
                .isEqualTo(SettlementDisposition.SETTLEMENT_APPLIED);
        assertThat(clients.size()).isEqualTo(1);
    }

    @Test
    void 单次超时按调用传入_超时后不重试() throws Exception {
        clients.call(target("a"), Duration.ofSeconds(5), s -> s.prepareBattle(call(7, "a"))).get(10, TimeUnit.SECONDS);
        long started = System.nanoTime();
        CompletableFuture<SceneBattleReply> hanging = clients.call(target("a"), Duration.ofMillis(300), s -> s.prepareBattle(call(0, "a")));
        assertThatThrownBy(() -> hanging.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMs).as("按这次传入的 300 ms 超时，而不是引用缺省的 5 s").isBetween(250L, 1_500L);
        Thread.sleep(800);
        assertThat(provider.prepares.get()).as("retries = 0：超时后不重发").isEqualTo(2);
    }

    @Test
    void 同地址换实例重建_retainOnly与evict清掉不在目录里的() throws Exception {
        SceneBattleService first = clients.clientAsync(target("a")).get(10, TimeUnit.SECONDS);
        assertThat(clients.clientAsync(target("a")).get(10, TimeUnit.SECONDS)).isSameAs(first);
        SceneBattleService second = clients.clientAsync(target("b")).get(10, TimeUnit.SECONDS);
        assertThat(second).isNotSameAs(first);
        assertThat(clients.size()).isEqualTo(1);
        clients.evict(target("a"));
        assertThat(clients.size()).as("实例不符的 evict 不动").isEqualTo(1);
        clients.retainOnly(List.of(target("c")));
        assertThat(clients.size()).isZero();
    }

    @Test
    void 预算为0不发包_关闭后一律异常完成() throws Exception {
        assertThatThrownBy(() -> clients.call(target("a"), Duration.ZERO, s -> s.prepareBattle(call(7, "a"))).get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        assertThat(provider.prepares.get()).isZero();
        clients.close();
        assertThatThrownBy(() -> clients.call(target("a"), Duration.ofSeconds(1), s -> s.prepareBattle(call(7, "a"))).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class);
        clients.close();
    }

    @Test
    void 地址拼写_IPv6加方括号_端口校验() {
        assertThat(new NodeRpcClients.Target("::1", 21100, "x").address()).isEqualTo("[::1]:21100");
        assertThat(new NodeRpcClients.Target("10.0.0.2", 21100, null).instanceId()).isEmpty();
        assertThatThrownBy(() -> new NodeRpcClients.Target("h", 0, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new NodeRpcClients.Target(" ", 1, "x")).isInstanceOf(IllegalArgumentException.class);
    }
}
