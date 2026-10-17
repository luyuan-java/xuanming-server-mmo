package com.game.discovery.asset;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.asset.AssetOpSignatures;
import com.game.api.asset.AssetRpc;
import com.game.api.asset.SceneAssetEndpoint;
import com.game.api.proto.AssetOpRequest;
import com.game.api.proto.AssetOpResponse;
import com.game.api.proto.AssetOutcome;
import com.game.api.proto.AssetStream;
import com.game.common.deadline.Deadline;
import com.game.discovery.location.SceneAssetLocator.Found;
import com.game.discovery.proto.PlayerLocation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ReliableAssetDeliveryTest {
    private static final String SECRET = "mail-asset-delivery-test-only-secret-0123456789";
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final SceneAssetEndpoint endpoint = new SceneAssetEndpoint(1, 1, "test-scene", "127.0.0.1", 21100);
    private final AssetOpRequest request = AssetOpRequest.newBuilder().setPlayerId(0x8000000000000001L)
            .setStream(AssetStream.ASSET_STREAM_SYSTEM_CREDIT).setStreamEpoch(1).setSeq(1).setCorrelationId(99).setTxType(2).build();

    @AfterEach void close() { timer.shutdownNow(); }

    private ReliableAssetDelivery delivery(ReliableAssetDelivery.Transport transport) {
        return new ReliableAssetDelivery(player -> CompletableFuture.completedFuture(new Found(
                PlayerLocation.newBuilder().setPlayerId(player).setZoneId(1).setSceneNodeId(1).build(), endpoint)), transport,
                (rpc, unsigned, now) -> AssetOpSignatures.sign(rpc, unsigned, AssetOpSignatures.CALLER_MAIL, SECRET, now),
                timer, clock::incrementAndGet, new ReliableAssetDelivery.Observer() { }, 50, List.of(0L, 0L));
    }

    @Test
    void 同一操作重签重查直到SQL持久终局才能完成() throws Exception {
        var sent = new ArrayList<AssetOpRequest>();
        var delivery = delivery((target, rpc, signed, timeout) -> {
            sent.add(signed);
            return CompletableFuture.completedFuture(AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED)
                    .setDurable(sent.size() == 2).build());
        });
        var result = delivery.deliver(AssetRpc.CREDIT, request, Deadline.after(1000)).get(1, TimeUnit.SECONDS);
        assertThat(result.completed()).isTrue();
        assertThat(sent).hasSize(2);
        for (var signed : sent) {
            assertThat(signed.toBuilder().clearAuth().build()).isEqualTo(request);
            assertThat(AssetOpSignatures.signatureMatches(AssetRpc.CREDIT.wireName(), signed, SECRET)).isTrue();
        }
        assertThat(sent.get(1).getAuth().getTimestampMs()).isGreaterThan(sent.getFirst().getAuth().getTimestampMs());
        assertThat(request.hasAuth()).isFalse();
    }

    @Test
    void 未知和部分到账即便有响应也不能宣告完成() throws Exception {
        for (AssetOpResponse response : List.of(AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_UNKNOWN).build(),
                AssetOpResponse.newBuilder().setOutcome(AssetOutcome.ASSET_OUTCOME_APPLIED).setDurable(true).setPartial(true).build())) {
            var result = delivery((target, rpc, signed, timeout) -> CompletableFuture.completedFuture(response))
                    .deliver(AssetRpc.CREDIT, request, Deadline.after(1000)).get(1, TimeUnit.SECONDS);
            assertThat(result.completed()).isFalse();
            assertThat(result.response()).isSameAs(response);
        }
    }

    @Test
    void 传输结果未知在预算内失败关闭而不改操作() throws Exception {
        var result = delivery((target, rpc, signed, timeout) -> new CompletableFuture<>())
                .deliver(AssetRpc.CREDIT, request, Deadline.after(25)).get(1, TimeUnit.SECONDS);
        assertThat(result.answered()).isFalse();
        assertThat(result.completed()).isFalse();
        assertThat(result.error()).isNotNull();
    }

    @Test
    void 同操作终局翻转保留最新答复并交人工() throws Exception {
        var calls = new AtomicLong();
        var result = delivery((target, rpc, signed, timeout) -> CompletableFuture.completedFuture(AssetOpResponse.newBuilder()
                .setOutcome(calls.incrementAndGet() == 1 ? AssetOutcome.ASSET_OUTCOME_APPLIED : AssetOutcome.ASSET_OUTCOME_REJECTED)
                .setDurable(false).build())).deliver(AssetRpc.CREDIT, request, Deadline.after(1000)).get(1, TimeUnit.SECONDS);
        assertThat(result.error()).isInstanceOf(ReliableAssetDelivery.OutcomeFlipException.class);
        assertThat(result.response().getOutcome()).isEqualTo(AssetOutcome.ASSET_OUTCOME_REJECTED);
        assertThat(result.completed()).isFalse();
    }
}
