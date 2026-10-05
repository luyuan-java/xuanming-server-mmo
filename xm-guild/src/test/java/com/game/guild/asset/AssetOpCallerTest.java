package com.game.guild.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.AssetBundle;
import com.game.api.proto.AssetCurrency;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.common.deadline.Deadline;
import com.game.discovery.location.SceneAssetLocator.Failure;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.location.SceneAssetLocator.NoHolder;
import com.game.discovery.location.SceneAssetLocator.Resolution;
import com.game.discovery.location.SceneAssetLocator.ResolveResult;
import com.game.discovery.proto.PlayerLocation;
import com.game.guild.asset.AssetOpCaller.Delivery;
import com.game.guild.metrics.GuildMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 资产调用方（照基线 caller_test.go；guild-economy-spec §11.2 AssetOpCallerTest）：重查到 durable；预算到期停重查返回最后一次；NOT_HERE 换节点重调、
 * 同节点不重调；重定位没人持有时返回 scene 的 NOT_HERE；结局翻转；没人持有时本地 NOT_HERE；定位故障与传输错误透传；不改调用方请求；每次重签。
 */
class AssetOpCallerTest {

    static final String SECRET = "guild-asset-op-unit-test-secret-0123456789";
    static final SceneAssetEndpoint A = new SceneAssetEndpoint(1, 1, "inst-a", "10.0.0.1", 21100);
    static final SceneAssetEndpoint B = new SceneAssetEndpoint(1, 2, "inst-b", "10.0.0.2", 21100);
    static final long PLAYER = 0x8000_0000_0000_0042L;

    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GuildMetrics metrics = new GuildMetrics(meters);
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final Deque<Resolution> resolutions = new ArrayDeque<>();
    private final Map<String, Deque<Object>> answers = new HashMap<>();
    private final List<Sent> sent = Collections.synchronizedList(new ArrayList<>());

    record Sent(SceneAssetEndpoint endpoint, AssetRpc rpc, AssetOpRequest request, Duration timeout) {
    }

    @AfterEach
    void close() {
        timer.shutdownNow();
    }

    private AssetOpCaller caller(List<Long> requery) {
        return new AssetOpCaller(this::resolve, this::call, new AssetOpSigner(SECRET), timer, clock::incrementAndGet, metrics,
                800, requery);
    }

    private CompletableFuture<Resolution> resolve(long playerId) {
        synchronized (resolutions) {
            Resolution r = resolutions.size() > 1 ? resolutions.poll() : resolutions.peek();
            return CompletableFuture.completedFuture(r);
        }
    }

    private CompletableFuture<AssetOpResponse> call(SceneAssetEndpoint endpoint, AssetRpc rpc, AssetOpRequest request,
                                                    Duration timeout) {
        sent.add(new Sent(endpoint, rpc, request, timeout));
        Deque<Object> queue = answers.get(endpoint.address());
        Object next;
        synchronized (answers) {
            next = queue == null || queue.isEmpty() ? new IllegalStateException("no scripted answer") : queue.size() > 1
                    ? queue.poll() : queue.peek();
        }
        if (next instanceof Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
        return CompletableFuture.completedFuture((AssetOpResponse) next);
    }

    private void script(SceneAssetEndpoint endpoint, Object... replies) {
        answers.put(endpoint.address(), new ArrayDeque<>(List.of(replies)));
    }

    private static Found found(SceneAssetEndpoint endpoint) {
        return new Found(PlayerLocation.newBuilder().setPlayerId(PLAYER).setZoneId(1).setSceneNodeId(endpoint.nodeId()).build(),
                endpoint);
    }

    static AssetOpResponse answer(AssetOutcome outcome, boolean durable) {
        return AssetOpResponse.newBuilder().setOutcome(outcome).setDurable(durable).build();
    }

    static AssetOpRequest request() {
        return AssetOpRequest.newBuilder().setPlayerId(PLAYER).setStream(AssetStream.ASSET_STREAM_GUILD_DEBIT).setSeq(7)
                .setStreamEpoch(1_700_000_000_000L).setCorrelationId(0x9000_0000_0000_0001L).setTxType(24)
                .setBundle(AssetBundle.newBuilder().addCurrencies(AssetCurrency.newBuilder().setCurrencyType(0).setAmount(100)))
                .build();
    }

    private double count(String name, String... tags) {
        return meters.get(name).tags(tags).counter().count();
    }

    // ================================================================

    @Test
    void 重查等到durable_每次发包都现签_不改调用方请求() {
        resolutions.add(found(A));
        script(A, answer(AssetOutcome.ASSET_OUTCOME_APPLIED, false), answer(AssetOutcome.ASSET_OUTCOME_APPLIED, false),
                answer(AssetOutcome.ASSET_OUTCOME_APPLIED, true));
        AssetOpRequest req = request();
        Delivery d = caller(List.of(5L, 5L, 5L)).deliver(AssetRpc.DEBIT, req, Deadline.after(2_000)).join();

        assertThat(d.answered()).isTrue();
        assertThat(d.result().outcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(d.result().durable()).isTrue();
        assertThat(d.result().local()).isFalse();
        assertThat(sent).hasSize(3);
        // 每次发包都现签：caller = guild、时间戳逐次推进、签名都对得上同一个 rpc
        List<Long> stamps = new ArrayList<>();
        for (Sent s : sent) {
            assertThat(s.request().getAuth().getCaller()).isEqualTo(AssetOpSignatures.CALLER_GUILD);
            assertThat(AssetOpSignatures.signatureMatches(AssetRpc.DEBIT.wireName(), s.request(), SECRET)).isTrue();
            assertThat(AssetOpSignatures.signatureMatches(AssetRpc.CREDIT.wireName(), s.request(), SECRET)).isFalse();
            assertThat(s.timeout()).isLessThanOrEqualTo(Duration.ofMillis(800));
            stamps.add(s.request().getAuth().getTimestampMs());
        }
        assertThat(stamps).doesNotHaveDuplicates();
        assertThat(req.hasAuth()).as("不改调用方请求").isFalse();
        assertThat(count("xm.guild.assetop.requery", "rpc", "debit", "result", "durable")).isEqualTo(1);
        assertThat(count("xm.guild.assetop.rpc", "stream", "guild_debit", "rpc", "debit", "outcome", "applied")).isEqualTo(3);
    }

    @Test
    void 预算到期就停止重查_返回最后一次未durable的结果() {
        resolutions.add(found(A));
        script(A, answer(AssetOutcome.ASSET_OUTCOME_REJECTED, false));
        Delivery d = caller(List.of(100L, 200L, 400L)).deliver(AssetRpc.DEBIT, request(), Deadline.after(250)).join();

        assertThat(d.answered()).isTrue();
        assertThat(d.result().outcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(d.result().durable()).isFalse();
        // 首投 + 第一次重查（100 ms 之后还有预算），第二次重查要等 200 ms、预算不够就不睡了
        assertThat(sent).hasSize(2);
        assertThat(count("xm.guild.assetop.requery", "rpc", "debit", "result", "timeout")).isEqualTo(1);
        assertThat(AssetOpDecisions.decide(d.result(), d.error())).isEqualTo(AssetOpAction.AWAIT_DURABLE);
    }

    @Test
    void NOT_HERE_换了节点才重调() {
        resolutions.add(found(A));
        resolutions.add(found(B));
        script(A, answer(AssetOutcome.ASSET_OUTCOME_NOT_HERE, false));
        script(B, answer(AssetOutcome.ASSET_OUTCOME_APPLIED, true));
        Delivery d = caller(List.of(5L)).deliver(AssetRpc.CREDIT, request(), Deadline.after(2_000)).join();

        assertThat(d.result().outcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(sent).extracting(Sent::endpoint).containsExactly(A, B);
    }

    @Test
    void 定位受投递预算约束_Redis卡住时按定位超时返回_不发调用() {
        AssetOpCaller stuck = new AssetOpCaller(playerId -> new CompletableFuture<>(), this::call, new AssetOpSigner(SECRET), timer,
                clock::incrementAndGet, metrics, 800, List.of(5L));
        long start = System.nanoTime();
        Delivery d = stuck.deliver(AssetRpc.CREDIT, request(), Deadline.after(200)).join();
        long tookMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(d.noAnswer()).isTrue();
        assertThat(d.error()).hasMessageContaining("定位");
        assertThat(tookMs).as("不超过预算太多").isLessThan(1_000);
        assertThat(sent).isEmpty();
        assertThat(stuck.deliver(AssetRpc.CREDIT, request(), Deadline.after(0)).join().noAnswer()).as("预算已用完不发 Redis 读")
                .isTrue();
    }

    @Test
    void NOT_HERE_同节点不重调_返回scene的NOT_HERE() {
        resolutions.add(found(A));
        script(A, answer(AssetOutcome.ASSET_OUTCOME_NOT_HERE, false));
        Delivery d = caller(List.of(5L)).deliver(AssetRpc.CREDIT, request(), Deadline.after(2_000)).join();

        assertThat(d.result().outcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_NOT_HERE);
        assertThat(d.result().local()).isFalse();
        assertThat(sent).hasSize(1);
    }

    @Test
    void NOT_HERE_重定位没人持有或出错_返回scene的NOT_HERE_不当本地() {
        for (Resolution second : List.<Resolution>of(new NoHolder(ResolveResult.NOT_ONLINE), new Failure("redis down"))) {
            resolutions.clear();
            sent.clear();
            resolutions.add(found(A));
            resolutions.add(second);
            script(A, answer(AssetOutcome.ASSET_OUTCOME_NOT_HERE, false));
            Delivery d = caller(List.of(5L)).deliver(AssetRpc.DEBIT, request(), Deadline.after(2_000)).join();
            assertThat(d.answered()).isTrue();
            assertThat(d.result().outcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_NOT_HERE);
            assertThat(d.result().local()).as("scene 的 NOT_HERE 不触发离线读账本").isFalse();
            assertThat(sent).hasSize(1);
        }
    }

    @Test
    void 结局翻转_报AssetOutcomeFlipException_结果是最新那次() {
        resolutions.add(found(A));
        script(A, answer(AssetOutcome.ASSET_OUTCOME_APPLIED, false), answer(AssetOutcome.ASSET_OUTCOME_REJECTED, true));
        Delivery d = caller(List.of(5L, 5L)).deliver(AssetRpc.DEBIT, request(), Deadline.after(2_000)).join();

        assertThat(d.error()).isInstanceOf(AssetOutcomeFlipException.class);
        assertThat(d.result().outcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(AssetOpDecisions.decide(d.result(), d.error())).isEqualTo(AssetOpAction.ALERT);
        assertThat(count("xm.guild.assetop.outcome.flip", "stream", "guild_debit")).isEqualTo(1);
    }

    @Test
    void 重查拿到非终结结局或出错_按没等到处理_不算翻转() {
        resolutions.add(found(A));
        script(A, answer(AssetOutcome.ASSET_OUTCOME_APPLIED, false), answer(AssetOutcome.ASSET_OUTCOME_RETRY, false));
        Delivery d = caller(List.of(5L, 5L)).deliver(AssetRpc.DEBIT, request(), Deadline.after(2_000)).join();
        assertThat(d.answered()).isTrue();
        assertThat(d.result().outcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(d.result().durable()).isFalse();
        assertThat(count("xm.guild.assetop.requery", "rpc", "debit", "result", "timeout")).isEqualTo(1);

        sent.clear();
        script(A, answer(AssetOutcome.ASSET_OUTCOME_APPLIED, false), new IllegalStateException("conn reset"));
        d = caller(List.of(5L, 5L)).deliver(AssetRpc.DEBIT, request(), Deadline.after(2_000)).join();
        assertThat(d.answered()).isTrue();
        assertThat(d.result().outcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_APPLIED);
        assertThat(count("xm.guild.assetop.requery", "rpc", "debit", "result", "error")).isEqualTo(1);
        assertThat(count("xm.guild.assetop.outcome.flip", "stream", "guild_debit")).isZero();
    }

    @Test
    void 没人持有时本地合成NOT_HERE_不发包() {
        resolutions.add(new NoHolder(ResolveResult.LEASE));
        Delivery d = caller(List.of()).deliver(AssetRpc.DEBIT, request(), Deadline.after(2_000)).join();
        assertThat(d.answered()).isTrue();
        assertThat(d.result().outcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_NOT_HERE);
        assertThat(d.result().local()).isTrue();
        assertThat(sent).isEmpty();
        assertThat(count("xm.guild.assetop.rpc", "stream", "guild_debit", "rpc", "debit", "outcome", "no_location")).isEqualTo(1);
    }

    @Test
    void 定位故障与传输错误透传为没拿到答复() {
        resolutions.add(new Failure("corrupt location"));
        Delivery d = caller(List.of()).deliver(AssetRpc.DEBIT, request(), Deadline.after(2_000)).join();
        assertThat(d.noAnswer()).isTrue();
        assertThat(d.error()).isInstanceOf(AssetDeliveryException.class);
        assertThat(sent).isEmpty();

        resolutions.clear();
        resolutions.add(found(A));
        script(A, new IllegalStateException("overloaded"));
        d = caller(List.of()).deliver(AssetRpc.DEBIT, request(), Deadline.after(2_000)).join();
        assertThat(d.noAnswer()).isTrue();
        assertThat(d.error()).isInstanceOf(AssetDeliveryException.class);
        assertThat(AssetOpDecisions.decide(d.result(), d.error())).isEqualTo(AssetOpAction.RETRY);
        assertThat(count("xm.guild.assetop.rpc", "stream", "guild_debit", "rpc", "debit", "outcome", "error")).isEqualTo(1);
    }

    @Test
    void 预算已用完不发包_单次超时不超过剩余预算() throws Exception {
        resolutions.add(found(A));
        script(A, answer(AssetOutcome.ASSET_OUTCOME_APPLIED, true));
        Deadline expired = Deadline.after(0);
        Thread.sleep(2);
        Delivery d = caller(List.of()).deliver(AssetRpc.DEBIT, request(), expired).join();
        assertThat(d.noAnswer()).isTrue();
        assertThat(sent).isEmpty();

        caller(List.of()).deliver(AssetRpc.DEBIT, request(), Deadline.after(300)).join();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().timeout()).isLessThanOrEqualTo(Duration.ofMillis(300));
    }

    @Test
    void 部分发放计partial() {
        resolutions.add(found(A));
        script(A, AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).setDurable(true).setPartial(true)
                .setReason(AssetOpDecisions.REASON_PARTIAL_APPLIED).build());
        Delivery d = caller(List.of()).deliver(AssetRpc.CREDIT, request(), Deadline.after(2_000)).join();
        assertThat(d.result().partial()).isTrue();
        assertThat(count("xm.guild.assetop.partial", "stream", "guild_debit")).isEqualTo(1);
    }
}
