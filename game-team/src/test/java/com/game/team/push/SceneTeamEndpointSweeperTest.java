package com.game.team.push;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.DubboGroups;
import com.game.api.SceneTeamService;
import com.game.api.asset.IsolatedDubboModule;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.SceneNodeInfo;
import com.game.api.proto.SceneTeamRefresh;
import com.game.api.proto.SceneTeamRefreshReply;
import com.game.api.rpc.NodeRpcClients;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.junit.jupiter.api.Test;

class SceneTeamEndpointSweeperTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static SceneAssetEndpoint endpoint(int zone, int node, String instance) {
        return new SceneAssetEndpoint(zone, node, instance, "127.0.0." + node, 21100);
    }
    private static SceneNodeInfo info(SceneAssetEndpoint endpoint) {
        return SceneNodeInfo.newBuilder().setZoneId(endpoint.zoneId()).setNodeId(endpoint.nodeId())
                .setInstanceId(endpoint.instanceId()).setRpcHost(endpoint.host()).setRpcPort(endpoint.port()).build();
    }
    private static final class RecordingClients implements SceneTeamEndpointSweeper.Clients {
        final List<NodeRpcClients.Target> evicted = new ArrayList<>();
        @Override public <R> CompletableFuture<R> call(NodeRpcClients.Target target, Duration timeout,
                Function<SceneTeamService, CompletableFuture<R>> invocation) { return CompletableFuture.completedFuture(null); }
        @Override public void evict(NodeRpcClients.Target target) { evicted.add(target); }
    }
    private static void track(SceneTeamEndpointSweeper sweeper, SceneAssetEndpoint endpoint) {
        sweeper.call(endpoint, TIMEOUT, service -> CompletableFuture.completedFuture(null));
    }

    @Test
    void 消失换实例与撤下RPC均回收_失败区保留_每区只读一次() {
        var clients = new RecordingClients();
        Map<Integer, Integer> reads = new HashMap<>();
        var a = endpoint(1, 1, "a"); var b = endpoint(1, 2, "b"); var c = endpoint(1, 3, "c");
        var d = endpoint(1, 4, "d"); var failure = endpoint(2, 5, "e");
        try (var sweeper = new SceneTeamEndpointSweeper(zone -> {
            reads.merge(zone, 1, Integer::sum);
            if (zone == 2) throw new IllegalStateException("目录不可读");
            return List.of(info(a), info(b).toBuilder().setInstanceId("b2").build(), info(c).toBuilder().setRpcPort(0).build());
        }, clients)) {
            for (var target : List.of(a, b, c, d, failure)) track(sweeper, target);
            assertThat(sweeper.sweep()).isEqualTo(3);
            assertThat(clients.evicted).extracting(NodeRpcClients.Target::instanceId).containsExactlyInAnyOrder("b", "c", "d");
            assertThat(reads).containsExactlyInAnyOrderEntriesOf(Map.of(1, 1, 2, 1));
            assertThat(sweeper.sweep()).isZero();
        }
    }

    @Test
    void 目录读取期间目标被重用_旧快照不销毁新调用_关闭后不再发调用() {
        var clients = new RecordingClients();
        var a = endpoint(1, 1, "a");
        AtomicReference<SceneTeamEndpointSweeper> holder = new AtomicReference<>();
        var sweeper = new SceneTeamEndpointSweeper(zone -> {
            track(holder.get(), a);
            return List.of();
        }, clients);
        holder.set(sweeper);
        track(sweeper, a);
        assertThat(sweeper.sweep()).isZero();
        assertThat(clients.evicted).isEmpty();
        sweeper.close(); sweeper.close();
        assertThat(sweeper.call(a, TIMEOUT, service -> CompletableFuture.completedFuture(null))).isCompletedExceptionally();
        assertThat(sweeper.sweep()).isZero();
    }

    @Test
    void 真Triple引用从缓存移除_随后同地址可重建并调用() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        var endpoint = new SceneAssetEndpoint(1, 1, "instance", "127.0.0.1", port);
        var server = IsolatedDubboModule.create("game-team-sweep-test-provider");
        try (var clients = new NodeRpcClients<SceneTeamService>("game-team-sweep-test-client", SceneTeamService.class,
                DubboGroups.SCENE_TEAM, TIMEOUT, "team-sweep-test-connect")) {
            ProtocolConfig protocol = new ProtocolConfig("tri", port); protocol.setHost("127.0.0.1");
            ServiceConfig<SceneTeamService> service = new ServiceConfig<>(server.module());
            service.setInterface(SceneTeamService.class);
            service.setRef(request -> CompletableFuture.completedFuture(SceneTeamRefreshReply.newBuilder().setAccepted(true).build()));
            service.setGroup(DubboGroups.SCENE_TEAM); service.setRegister(false);
            service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE)); service.setProtocol(protocol); service.export();
            AtomicReference<List<SceneNodeInfo>> directory = new AtomicReference<>(List.of(info(endpoint)));
            try (var sweeper = new SceneTeamEndpointSweeper(zone -> directory.get(), clients)) {
                SceneTeamRefresh request = SceneTeamRefresh.newBuilder().setPlayerId(1).setTargetInstanceId("instance").build();
                assertThat(sweeper.call(endpoint, TIMEOUT, peer -> peer.refresh(request)).get(10, TimeUnit.SECONDS).getAccepted()).isTrue();
                assertThat(clients.size()).isEqualTo(1);
                assertThat(sweeper.sweep()).isZero();
                directory.set(List.of());
                assertThat(sweeper.sweep()).isEqualTo(1);
                assertThat(clients.size()).as("真实 NodeRpcClients 已移除旧引用").isZero();
                directory.set(List.of(info(endpoint)));
                assertThat(sweeper.call(endpoint, TIMEOUT, peer -> peer.refresh(request)).get(10, TimeUnit.SECONDS).getAccepted()).isTrue();
                assertThat(clients.size()).isEqualTo(1);
            }
        } finally { server.close(); }
    }
}
