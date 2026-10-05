package com.game.scene.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.asset.AssetRpc;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.AssetOpResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 资产通道提供方（不经 Dubbo，guild-economy-spec §11.2「scene 提供方」）：未就绪回失败 future；超过在途上限回过载并计数、不进逻辑线程；
 * 逻辑线程关闭后失败；回写不在逻辑线程上；回写池停了退回完成线程；每次调用恰好释放一次名额、恰好计一次指标。
 */
class SceneAssetOpProviderTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final SceneMetrics metrics = new SceneMetrics(meters);
    private final ExecutorService logicThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-scene-logic"));
    private final ExecutorService replyThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-asset-reply"));
    private final AssetChannelFixture fixture = new AssetChannelFixture();

    @AfterEach
    void tearDown() {
        logicThread.shutdownNow();
        replyThread.shutdownNow();
    }

    private double count(AssetRpc rpc, AssetOpResult result) {
        return meters.get("xm.scene.asset.ops").tag("rpc", rpc.wireName()).tag("outcome", result.name().toLowerCase())
                .counter().count();
    }

    private AssetOpEndpoint endpoint() {
        return new AssetOpEndpoint(logicThread, fixture.service);
    }

    @Test
    void 未就绪_回失败future_计error() {
        SceneAssetOpProvider provider = new SceneAssetOpProvider(() -> null, 4, replyThread, metrics);
        CompletableFuture<AssetOpResponse> reply = provider.debit(AssetChannelFixture.debit(AssetChannelFixture.PLAYER, 1, 10));
        assertThat(reply).isCompletedExceptionally();
        assertThat(provider.inFlight()).isZero();
        assertThat(count(AssetRpc.DEBIT, AssetOpResult.ERROR)).isEqualTo(1);
    }

    @Test
    void 结局经逻辑线程算出_回写在回写线程上_指标按结局计() throws Exception {
        SceneAssetOpProvider provider = new SceneAssetOpProvider(this::endpoint, 4, replyThread, metrics);
        AtomicReference<String> completedOn = new AtomicReference<>();
        CompletableFuture<AssetOpResponse> reply = provider.debit(
                fixture.signed(AssetRpc.DEBIT, AssetChannelFixture.debit(AssetChannelFixture.PLAYER, 1, 30)));
        // 挂在返回 future 上的后续（Dubbo 的序列化与回写就是这样挂的）跑在完成它的线程上
        CompletableFuture<Void> observed = reply.thenAccept(r -> completedOn.set(Thread.currentThread().getName()));
        AssetOpResponse response = reply.get(5, TimeUnit.SECONDS);
        observed.get(5, TimeUnit.SECONDS);
        assertThat(response.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(completedOn.get()).as("不在场景逻辑线程上回写").isEqualTo("test-asset-reply");
        assertThat(count(AssetRpc.DEBIT, AssetOpResult.APPLIED)).isEqualTo(1);

        assertThat(provider.credit(fixture.signed(AssetRpc.CREDIT, AssetChannelFixture.debit(42, 2, 1)))
                .get(5, TimeUnit.SECONDS).getOutcome()).as("流方向不符：信封级 UNKNOWN").isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
        assertThat(provider.abortDebit(fixture.signed(AssetRpc.ABORT_DEBIT, AssetChannelFixture.debit(42, 3, 1)))
                .get(5, TimeUnit.SECONDS).getOutcome()).as("不在本节点").isEqualTo(AssetOutcome.ASSET_OUTCOME_NOT_HERE);
        assertThat(count(AssetRpc.CREDIT, AssetOpResult.UNKNOWN)).isEqualTo(1);
        assertThat(count(AssetRpc.ABORT_DEBIT, AssetOpResult.NOT_HERE)).isEqualTo(1);
        assertThat(provider.inFlight()).isZero();
    }

    @Test
    void 超过在途上限_直接回过载_不进逻辑线程_名额释放后恢复() throws Exception {
        Queue<Runnable> parked = new ArrayDeque<>();
        AssetOpEndpoint slow = new AssetOpEndpoint(parked::add, fixture.service);
        SceneAssetOpProvider provider = new SceneAssetOpProvider(() -> slow, 2, replyThread, metrics);
        CompletableFuture<AssetOpResponse> first = provider.debit(AssetChannelFixture.debit(0, 1, 1));
        CompletableFuture<AssetOpResponse> second = provider.debit(AssetChannelFixture.debit(0, 2, 1));
        assertThat(provider.inFlight()).isEqualTo(2);

        CompletableFuture<AssetOpResponse> third = provider.credit(AssetChannelFixture.debit(0, 3, 1));
        assertThat(third).isCompletedExceptionally();
        assertThatThrownBy(third::join).hasCauseInstanceOf(RejectedExecutionException.class);
        assertThat(parked).as("过载的那条没进逻辑线程").hasSize(2);
        assertThat(count(AssetRpc.CREDIT, AssetOpResult.OVERLOADED)).isEqualTo(1);

        parked.poll().run();
        assertThat(first.get(5, TimeUnit.SECONDS).getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
        assertThat(provider.inFlight()).isEqualTo(1);
        CompletableFuture<AssetOpResponse> fourth = provider.debit(AssetChannelFixture.debit(0, 4, 1));
        assertThat(fourth).as("名额释放后照常受理").isNotCompletedExceptionally();
        while (!parked.isEmpty()) {
            parked.poll().run();
        }
        second.get(5, TimeUnit.SECONDS);
        fourth.get(5, TimeUnit.SECONDS);
        assertThat(provider.inFlight()).isZero();
    }

    @Test
    void 逻辑线程已停_回失败future_名额释放() {
        logicThread.shutdown();
        SceneAssetOpProvider provider = new SceneAssetOpProvider(this::endpoint, 1, replyThread, metrics);
        for (int i = 0; i < 3; i++) {
            CompletableFuture<AssetOpResponse> reply = provider.debit(AssetChannelFixture.debit(AssetChannelFixture.PLAYER, 1, 1));
            assertThatThrownBy(() -> reply.get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(RejectedExecutionException.class);
        }
        assertThat(provider.inFlight()).isZero();
        assertThat(count(AssetRpc.DEBIT, AssetOpResult.ERROR)).isEqualTo(3);
        assertThat(count(AssetRpc.DEBIT, AssetOpResult.OVERLOADED)).as("名额每次都还回来了").isZero();
    }

    @Test
    void 回写池已停_退回完成线程直接回写_名额照样释放() throws Exception {
        replyThread.shutdown();
        SceneAssetOpProvider provider = new SceneAssetOpProvider(this::endpoint, 1, replyThread, metrics);
        AssetOpResponse response = provider.debit(AssetChannelFixture.debit(0, 1, 1)).get(5, TimeUnit.SECONDS);
        assertThat(response.getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_UNKNOWN);
        assertThat(provider.inFlight()).isZero();
        assertThat(count(AssetRpc.DEBIT, AssetOpResult.UNKNOWN)).isEqualTo(1);
    }

    @Test
    void 结局到指标的映射() {
        for (AssetOutcome outcome : AssetOutcome.values()) {
            if (outcome == AssetOutcome.UNRECOGNIZED) {
                continue;
            }
            AssetOpResult result = SceneAssetOpProvider.resultOf(AssetOpResponse.newBuilder().setOutcome(outcome).build());
            assertThat(result.name()).isEqualTo(outcome.name().substring("ASSET_OUTCOME_".length()));
        }
        assertThat(SceneAssetOpProvider.resultOf(AssetOpResponse.newBuilder().setOutcomeValue(99).build()))
                .isEqualTo(AssetOpResult.UNKNOWN);
        assertThatThrownBy(() -> new SceneAssetOpProvider(() -> null, 0, replyThread, metrics))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
