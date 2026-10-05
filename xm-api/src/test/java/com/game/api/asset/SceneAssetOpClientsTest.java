package com.game.api.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.DubboGroups;
import com.game.api.SceneAssetOpService;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.dubbo.config.ProtocolConfig;
import org.apache.dubbo.config.RegistryConfig;
import org.apache.dubbo.config.ServiceConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 资产通道的 Dubbo 传输（真 Triple，提供方与调用方各在自己的 Dubbo 框架模型里，等价于两个进程；调用方鉴权过滤器照常生效，
 * 测试密钥由 surefire 注入 {@code XM_DUBBO_SECRET}）：三个入口路由、单次超时按调用传入、<b>不重试</b>、传输失败 = future 异常完成、
 * 同地址换实例重建、关闭后拒绝。
 */
class SceneAssetOpClientsTest {

    private static final String INSTANCE = "instance-a";

    private final FakeProvider provider = new FakeProvider();
    private IsolatedDubboModule server;
    private int port;
    private SceneAssetOpClients clients;

    /** 按入口回不同的 reason，便于确认路由；debit 的 player_id = 0 时永不完成，= 1 时异常完成。 */
    static final class FakeProvider implements SceneAssetOpService {
        final AtomicInteger debits = new AtomicInteger();
        final AtomicInteger aborts = new AtomicInteger();
        final AtomicInteger credits = new AtomicInteger();

        @Override
        public CompletableFuture<AssetOpResponse> debit(AssetOpRequest request) {
            debits.incrementAndGet();
            if (request.getPlayerId() == 0) {
                return new CompletableFuture<>();
            }
            if (request.getPlayerId() == 1) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("scene asset op overloaded"));
            }
            return CompletableFuture.completedFuture(answer(1, request));
        }

        @Override
        public CompletableFuture<AssetOpResponse> abortDebit(AssetOpRequest request) {
            aborts.incrementAndGet();
            return CompletableFuture.completedFuture(answer(2, request));
        }

        @Override
        public CompletableFuture<AssetOpResponse> credit(AssetOpRequest request) {
            credits.incrementAndGet();
            // 异步完成（别的线程）：Dubbo 在 future 完成时才回写
            return CompletableFuture.supplyAsync(() -> answer(3, request),
                    CompletableFuture.delayedExecutor(20, TimeUnit.MILLISECONDS));
        }

        private static AssetOpResponse answer(int reason, AssetOpRequest request) {
            return AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).setReason(reason)
                    .setDurable(request.getSeq() % 2 == 0).setPartial(request.getSeq() == 3).build();
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** 与 xm-scene 的 SceneAssetRpcServer 同样的导出参数（register = false、group scene-asset、tri）。 */
    static IsolatedDubboModule export(SceneAssetOpService ref, int port) {
        IsolatedDubboModule dubbo = IsolatedDubboModule.create("xm-api-test-provider");
        ProtocolConfig protocol = new ProtocolConfig("tri", port);
        protocol.setHost("127.0.0.1");
        ServiceConfig<SceneAssetOpService> service = new ServiceConfig<>(dubbo.module());
        service.setInterface(SceneAssetOpService.class);
        service.setRef(ref);
        service.setGroup(DubboGroups.SCENE_ASSET);
        service.setRegister(false);
        service.setRegistry(new RegistryConfig(RegistryConfig.NO_AVAILABLE));
        service.setProtocol(protocol);
        service.export();
        return dubbo;
    }

    @BeforeEach
    void setUp() throws IOException {
        port = freePort();
        server = export(provider, port);
        clients = new SceneAssetOpClients("xm-api-test-client");
    }

    @AfterEach
    void tearDown() {
        clients.close();
        server.close();
    }

    private SceneAssetEndpoint endpoint(String instance) {
        return new SceneAssetEndpoint(1, 7, instance, "127.0.0.1", port);
    }

    private static AssetOpRequest request(long playerId, long seq) {
        return AssetOpRequest.newBuilder().setPlayerId(playerId).setSeq(seq).setStreamEpoch(1).build();
    }

    @Test
    void 三个入口各自路由_应答原样带回() throws Exception {
        Duration timeout = Duration.ofSeconds(5);
        AssetOpResponse debit = clients.call(endpoint(INSTANCE), AssetRpc.DEBIT, request(42, 2), timeout).get(10, TimeUnit.SECONDS);
        assertThat(debit).isEqualTo(AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).setReason(1)
                .setDurable(true).build());
        assertThat(clients.call(endpoint(INSTANCE), AssetRpc.ABORT_DEBIT, request(42, 5), timeout).get(10, TimeUnit.SECONDS)
                .getReason()).isEqualTo(2);
        AssetOpResponse credit = clients.call(endpoint(INSTANCE), AssetRpc.CREDIT, request(42, 3), timeout)
                .get(10, TimeUnit.SECONDS);
        assertThat(credit.getReason()).isEqualTo(3);
        assertThat(credit.getPartial()).isTrue();
        assertThat(provider.debits.get()).isEqualTo(1);
        assertThat(provider.aborts.get()).isEqualTo(1);
        assertThat(provider.credits.get()).isEqualTo(1);
        assertThat(clients.size()).as("同一地址只建一个客户端").isEqualTo(1);
    }

    @Test
    void 单次超时按调用传入_超时后不重试() throws Exception {
        clients.call(endpoint(INSTANCE), AssetRpc.CREDIT, request(42, 1), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);
        long started = System.nanoTime();
        CompletableFuture<AssetOpResponse> hanging = clients.call(endpoint(INSTANCE), AssetRpc.DEBIT, request(0, 1),
                Duration.ofMillis(300));
        assertThatThrownBy(() -> hanging.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMs).as("按这次传入的 300 ms 超时，而不是引用缺省的 800 ms").isBetween(250L, 750L);
        Thread.sleep(1_200);
        assertThat(provider.debits.get()).as("retries = 0：超时后不重发").isEqualTo(1);
    }

    @Test
    void 提供方异常完成_调用异常完成且不重试() {
        CompletableFuture<AssetOpResponse> failed = clients.call(endpoint(INSTANCE), AssetRpc.DEBIT, request(1, 1),
                Duration.ofSeconds(5));
        assertThatThrownBy(() -> failed.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(provider.debits.get()).isEqualTo(1);
    }

    @Test
    void 连不上的地址_异常完成而不是抛出_也不阻塞调用线程() throws Exception {
        SceneAssetEndpoint nowhere = new SceneAssetEndpoint(1, 8, "x", "127.0.0.1", freePort());
        long started = System.nanoTime();
        CompletableFuture<AssetOpResponse> future = clients.call(nowhere, AssetRpc.DEBIT, request(42, 1), Duration.ofMillis(500));
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("建连在连接线程上，调用线程立即返回")
                .isLessThan(200);
        assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("按这次的预算放弃（+ 兜底余量）")
                .isLessThan(1_500);
    }

    @Test
    void 对端重启_断连期间快速失败_恢复后自动重连() throws Exception {
        Duration timeout = Duration.ofMillis(800);
        // 第一次调用还要建连（新客户端引用 + 握手），满载机器上可能超过 800 ms：只给这一次宽预算，本用例考的是断连与重连
        assertThat(clients.call(endpoint(INSTANCE), AssetRpc.DEBIT, request(42, 2), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS)
                .getReason()).isEqualTo(1);
        server.close();
        long started = System.nanoTime();
        CompletableFuture<AssetOpResponse> down = clients.call(endpoint(INSTANCE), AssetRpc.DEBIT, request(42, 2), timeout);
        assertThatThrownBy(() -> down.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1_500);
        server = export(provider, port);
        AssetOpResponse recovered = null;
        for (int i = 0; i < 50 && recovered == null; i++) {
            try {
                recovered = clients.call(endpoint(INSTANCE), AssetRpc.DEBIT, request(42, 2), timeout).get(10, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                Thread.sleep(200);
            }
        }
        assertThat(recovered).as("Dubbo 在后台重连，不用重建客户端").isNotNull();
        assertThat(clients.size()).isEqualTo(1);
    }

    @Test
    void 预算用完不发包() {
        assertThat(clients.call(endpoint(INSTANCE), AssetRpc.DEBIT, request(42, 1), Duration.ZERO)).isCompletedExceptionally();
        assertThat(provider.debits.get()).isZero();
        assertThat(clients.size()).isZero();
    }

    @Test
    void 同地址换实例重建_retainOnly与evict清掉不在目录里的() throws Exception {
        Duration timeout = Duration.ofSeconds(5);
        SceneAssetOpService first = clients.clientAsync(endpoint(INSTANCE)).get(10, TimeUnit.SECONDS);
        assertThat(clients.clientAsync(endpoint(INSTANCE)).get(10, TimeUnit.SECONDS)).isSameAs(first);
        SceneAssetOpService second = clients.clientAsync(endpoint("instance-b")).get(10, TimeUnit.SECONDS);
        assertThat(second).isNotSameAs(first);
        assertThat(clients.size()).isEqualTo(1);
        assertThat(clients.call(endpoint("instance-b"), AssetRpc.DEBIT, request(42, 2), timeout).get(10, TimeUnit.SECONDS)
                .getReason()).as("重建后照常可用").isEqualTo(1);

        clients.evict(endpoint(INSTANCE));
        assertThat(clients.size()).as("实例不符的 evict 不动").isEqualTo(1);
        clients.retainOnly(List.of(endpoint("instance-b")));
        assertThat(clients.size()).isEqualTo(1);
        clients.retainOnly(List.of(endpoint("instance-c")));
        assertThat(clients.size()).isZero();
        clients.clientAsync(endpoint(INSTANCE)).get(10, TimeUnit.SECONDS);
        clients.evict(endpoint(INSTANCE));
        assertThat(clients.size()).isZero();
    }

    @Test
    void 关闭后调用一律异常完成() throws Exception {
        clients.call(endpoint(INSTANCE), AssetRpc.DEBIT, request(42, 2), Duration.ofSeconds(5)).get(10, TimeUnit.SECONDS);
        clients.close();
        clients.close();
        assertThat(clients.call(endpoint(INSTANCE), AssetRpc.DEBIT, request(42, 2), Duration.ofSeconds(1)))
                .isCompletedExceptionally();
        assertThat(clients.size()).isZero();
    }
}
