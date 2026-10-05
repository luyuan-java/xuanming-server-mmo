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
 * 同地址换实例重建、关闭后拒绝、对端停机后按短重连间隔连回。
 */
class SceneAssetOpClientsTest {

    private static final String INSTANCE = "instance-a";
    /**
     * 对端停机多久再在同一端口重新导出：要盖过 Dubbo 断连后的<b>第一次</b>重连——断连 1 s 后发起，单次至多 connect.timeout（3 s）。
     * Windows 上连接被拒要重试 SYN 约 2 s，这一次重连常常拖到对端重新监听、碰巧连上，所以旧用例只在 Windows 上通过；
     * 停机盖过这个窗口，第一次重连在任何平台上都必然落空，恢复只能靠之后的周期重连（{@link SceneAssetOpClients#RECONNECT_INTERVAL}）。
     */
    private static final Duration PEER_DOWN = Duration.ofMillis(4_500);
    /**
     * 对端恢复后必须在多久内连回：周期重连间隔（1 s）+ 一次建连（≤ 3 s）+ 集群目录的可用性复查周期（1 s），给慢机器留足余量；
     * 必须远小于 Dubbo 缺省的 60 s 重连间隔——缺省配置下下面两个重连用例必然失败。
     */
    private static final Duration RECOVERY_DEADLINE = Duration.ofSeconds(20);

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
        try {
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
        } catch (RuntimeException e) {
            dubbo.close();
            throw e;
        }
    }

    /**
     * 在同一端口重新导出（模拟对端重启）。端口是临时端口段里挑的，停机期间可能被别的连接短暂占作本地端口，绑定失败就按截止时间重试，
     * 不让「端口一时没放出来」冒充「客户端没重连」。
     */
    static IsolatedDubboModule reexport(SceneAssetOpService ref, int port) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            try {
                return export(ref, port);
            } catch (RuntimeException e) {
                if (System.nanoTime() - deadline > 0) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
    }

    /** 截止时间内反复调用，直到拿到应答（true）；到期仍没连回返回 false。 */
    private boolean recoveredWithin(SceneAssetEndpoint target, Duration within) throws Exception {
        long deadline = System.nanoTime() + within.toNanos();
        while (System.nanoTime() - deadline < 0) {
            try {
                // 每次调用至多 800 ms + 兜底余量就异常完成，get 的 10 s 只是防卡死
                clients.call(target, AssetRpc.DEBIT, request(42, 2), Duration.ofMillis(800)).get(10, TimeUnit.SECONDS);
                return true;
            } catch (ExecutionException e) {
                Thread.sleep(100);
            }
        }
        return false;
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
        long downAt = System.nanoTime();
        CompletableFuture<AssetOpResponse> down = clients.call(endpoint(INSTANCE), AssetRpc.DEBIT, request(42, 2), timeout);
        assertThatThrownBy(() -> down.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - downAt)).as("断连期间快速失败").isLessThan(1_500);
        // 停机盖过 Dubbo 断连后的第一次重连，让它在任何平台上都落空
        long downMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - downAt);
        Thread.sleep(Math.max(0, PEER_DOWN.toMillis() - downMs));
        server = reexport(provider, port);
        assertThat(recoveredWithin(endpoint(INSTANCE), RECOVERY_DEADLINE))
                .as("对端恢复后 %s 内由 Dubbo 在后台周期重连连回（缺省 60 s 一次），不用重建客户端", RECOVERY_DEADLINE).isTrue();
        assertThat(clients.size()).isEqualTo(1);
    }

    @Test
    void 建客户端时对端没起来_起来后自动连上() throws Exception {
        server.close();
        // check = false：连不上也照样建好，留下一条第一次建连就失败的连接——缺省配置下它的下一次重连在 60 s 之后
        clients.clientAsync(endpoint(INSTANCE)).get(10, TimeUnit.SECONDS);
        server = reexport(provider, port);
        assertThat(recoveredWithin(endpoint(INSTANCE), RECOVERY_DEADLINE))
                .as("对端起来后 %s 内由 Dubbo 在后台周期重连连上", RECOVERY_DEADLINE).isTrue();
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
