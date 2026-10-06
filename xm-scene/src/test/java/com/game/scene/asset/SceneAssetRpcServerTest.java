package com.game.scene.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.asset.AssetRpc;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.asset.SceneAssetOpClients;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.rpc.SceneRpcServer;
import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 资产通道跨进程传输的 scene 侧（导出由 {@link SceneRpcServer} 做，6.3 起同一端口另导出战斗入口，这里只导出资产通道）（真 Dubbo Triple：提供方与调用方各在自己的 Dubbo 框架模型里，等价于两个进程；调用方鉴权过滤器照常生效，
 * 测试密钥由 surefire 注入 {@code XM_DUBBO_SECRET}）：导出参数（register = false、group、tri）、签名请求经 Dubbo 到逻辑线程扣款、
 * 验签失败的答复照常回传、过载是传输失败、端口被占导出失败、关闭后连不上。基线 {@code assetop/scene_smoke_test.go} 的 Java 对应
 * （帮会侧的调用方循环随 xm-guild 另测）。
 */
class SceneAssetRpcServerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ExecutorService logicThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-scene-logic"));
    private final ExecutorService replyThread = Executors.newFixedThreadPool(2, r -> new Thread(r, "test-asset-reply"));
    private final AssetChannelFixture fixture = new AssetChannelFixture();
    private SceneRpcServer server;
    private SceneAssetOpClients clients;
    private int port;

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        port = freePort();
        clients = new SceneAssetOpClients("xm-scene-test-client");
    }

    @AfterEach
    void tearDown() {
        clients.close();
        if (server != null) {
            server.close();
        }
        logicThread.shutdownNow();
        replyThread.shutdownNow();
    }

    private SceneAssetOpProvider provider(int maxInFlight) {
        AssetOpEndpoint endpoint = new AssetOpEndpoint(logicThread, fixture.service);
        return new SceneAssetOpProvider(() -> endpoint, maxInFlight, replyThread, SceneMetrics.noop());
    }

    private SceneAssetEndpoint endpoint() {
        return new SceneAssetEndpoint(1, 3, "inst-a", "127.0.0.1", port);
    }

    private long gold() throws Exception {
        return logicThread.submit(() -> fixture.player.wallet().balance(AssetChannelFixture.GOLD)).get(5, TimeUnit.SECONDS);
    }

    @Test
    void 导出参数_不进注册中心_按group直连() {
        server = SceneRpcServer.export(provider(8), null, "127.0.0.1", port);
        assertThat(server.port()).isEqualTo(port);
        assertThat(server.exportedUrls()).anySatisfy(url -> assertThat(url)
                .startsWith("tri://")
                .contains(":" + port + "/com.game.api.SceneAssetOpService")
                .contains("register=false")
                .contains("group=scene-asset"));
    }

    @Test
    void 签名请求经Dubbo到逻辑线程扣款_同seq重放只读答复() throws Exception {
        server = SceneRpcServer.export(provider(8), null, "127.0.0.1", port);
        AssetOpRequest request = AssetChannelFixture.debit(AssetChannelFixture.PLAYER, 1, 30);
        AssetOpResponse first = clients.call(endpoint(), AssetRpc.DEBIT, fixture.signed(AssetRpc.DEBIT, request), TIMEOUT)
                .get(10, TimeUnit.SECONDS);
        assertThat(first.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(first.getDurable()).as("不在调用里等落盘").isFalse();
        assertThat(gold()).isEqualTo(70);

        // 调用方用同一请求重查（每次现签）：只读答复原结局，不重扣
        AssetOpResponse again = clients.call(endpoint(), AssetRpc.DEBIT, fixture.signed(AssetRpc.DEBIT, request), TIMEOUT)
                .get(10, TimeUnit.SECONDS);
        assertThat(again.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(gold()).isEqualTo(70);

        AssetOpResponse poor = clients.call(endpoint(), AssetRpc.DEBIT,
                fixture.signed(AssetRpc.DEBIT, AssetChannelFixture.debit(AssetChannelFixture.PLAYER, 2, 1000)), TIMEOUT)
                .get(10, TimeUnit.SECONDS);
        assertThat(poor.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(poor.getReason()).isEqualTo(27000);
    }

    @Test
    void 验签失败经Dubbo照常答复UNKNOWN_27008_不记账() throws Exception {
        server = SceneRpcServer.export(provider(8), null, "127.0.0.1", port);
        AssetOpRequest signed = fixture.signed(AssetRpc.DEBIT, AssetChannelFixture.debit(AssetChannelFixture.PLAYER, 1, 30));
        AssetOpRequest tampered = signed.toBuilder().setBundle(AssetChannelFixture.debit(0, 0, 31).getBundle()).build();
        AssetOpResponse response = clients.call(endpoint(), AssetRpc.DEBIT, tampered, TIMEOUT).get(10, TimeUnit.SECONDS);
        assertThat(response.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
        assertThat(response.getReason()).isEqualTo(27008);
        AssetOpResponse wrongRpc = clients.call(endpoint(), AssetRpc.CREDIT, signed, TIMEOUT).get(10, TimeUnit.SECONDS);
        assertThat(wrongRpc.getOutcome()).as("拿扣款的签名调发放：流方向先拒").isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
        assertThat(gold()).isEqualTo(100);
    }

    @Test
    void 过载是传输失败_调用方future异常完成() throws Exception {
        Queue<Runnable> parked = new ArrayDeque<>();
        AssetOpEndpoint parkedEndpoint = new AssetOpEndpoint(task -> {
            synchronized (parked) {
                parked.add(task);
            }
        }, fixture.service);
        server = SceneRpcServer.export(new SceneAssetOpProvider(() -> parkedEndpoint, 1, replyThread,
                SceneMetrics.noop()), null, "127.0.0.1", port);
        CompletableFuture<AssetOpResponse> holding = clients.call(endpoint(), AssetRpc.DEBIT,
                AssetChannelFixture.debit(0, 1, 1), TIMEOUT);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            synchronized (parked) {
                if (!parked.isEmpty()) {
                    break;
                }
            }
            Thread.sleep(10);
        }
        CompletableFuture<AssetOpResponse> overloaded = clients.call(endpoint(), AssetRpc.DEBIT,
                AssetChannelFixture.debit(0, 2, 1), TIMEOUT);
        assertThatThrownBy(() -> overloaded.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
        Runnable task;
        synchronized (parked) {
            task = parked.poll();
        }
        task.run();
        assertThat(holding.get(10, TimeUnit.SECONDS).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
    }

    @Test
    void 端口被占_导出失败() throws IOException {
        try (ServerSocket busy = new ServerSocket(0)) {
            int taken = busy.getLocalPort();
            assertThatThrownBy(() -> SceneRpcServer.export(provider(8), null, "127.0.0.1", taken))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining(Integer.toString(taken));
        }
        assertThatThrownBy(() -> SceneRpcServer.export(provider(8), null, "127.0.0.1", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 关闭后调用方连不上_是传输失败() throws Exception {
        server = SceneRpcServer.export(provider(8), null, "127.0.0.1", port);
        assertThat(clients.call(endpoint(), AssetRpc.DEBIT, AssetChannelFixture.debit(0, 1, 1), TIMEOUT)
                .get(10, TimeUnit.SECONDS).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
        server.close();
        server.close();
        server = null;
        CompletableFuture<AssetOpResponse> after = clients.call(endpoint(), AssetRpc.DEBIT,
                AssetChannelFixture.debit(0, 2, 1), Duration.ofMillis(800));
        assertThatThrownBy(() -> after.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class);
    }
}
