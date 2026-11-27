package com.game.friend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.discovery.presence.PlayerPushes;
import com.game.friend.cache.FriendCache;
import com.game.friend.cache.InMemoryCacheRedis;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.quota.FriendRequestQuota;
import com.game.friend.service.FriendService;
import com.game.friend.store.FriendStore;
import com.game.proto.friend.BatchCheckSocialRelationsRequest;
import com.game.proto.friend.BatchCheckSocialRelationsResponse;
import com.game.table.CommonErrorTip;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@link FriendInternalServiceProvider} 内部批量关系校验与过载保护回归。 */
final class FriendInternalServiceProviderTest {

    @Test
    void 卸载到工作池执行批量关系校验并在池满时返回1003不抛未捕异常() throws Exception {
        FriendStore store = mock(FriendStore.class);
        when(store.batchCheckSocialRelations(eq(List.of(101L, 102L)), eq(List.of(201L, 202L)), anyInt(), any()))
                .thenReturn(new FriendStore.SocialRelationSummary(true, false));

        FriendMetrics metrics = new FriendMetrics(new SimpleMeterRegistry());
        RedisKeys keys = new RedisKeys(GameNamespace.of("game"));
        FriendCache cache = new FriendCache(new InMemoryCacheRedis(), keys, Duration.ofMinutes(5), metrics);
        FriendRequestQuota quota = new FriendRequestQuota(
                (key, timeout) -> CompletableFuture.completedFuture(1L), keys, 100, metrics);
        FriendService service = new FriendService(
                store, cache, keys, quota,
                (ids, d) -> Map.of(),
                ids -> CompletableFuture.completedFuture(
                        new com.game.discovery.presence.PlayerPresenceDirectory.StrictLookup(Map.of(), ids.size(), 0)),
                (to, msg) -> CompletableFuture.completedFuture(PlayerPushes.Outcome.SENT),
                metrics, 200, Duration.ofSeconds(1), () -> 123_456L, 235);

        AtomicInteger executed = new AtomicInteger();
        FriendInternalServiceProvider provider = new FriendInternalServiceProvider(service, r -> {
            executed.incrementAndGet();
            r.run();
        }, 2000L);

        BatchCheckSocialRelationsRequest req = BatchCheckSocialRelationsRequest.newBuilder()
                .addSideAPlayerIds(101L).addSideAPlayerIds(102L)
                .addSideBPlayerIds(201L).addSideBPlayerIds(202L)
                .build();
        BatchCheckSocialRelationsResponse resp = provider.batchCheckSocialRelations(req).join();
        assertThat(executed.get()).isEqualTo(1);
        assertThat(resp.getErrorMessage().getId()).isZero();
        assertThat(resp.getHasAnyBlock()).isTrue();
        assertThat(resp.getAllAreFriends()).isFalse();

        // 队列打满时应回 in-band 1003（SERVICE_UNAVAILABLE）
        FriendInternalServiceProvider fullProvider = new FriendInternalServiceProvider(service, r -> {
            throw new RejectedExecutionException("full");
        }, 2000L);
        BatchCheckSocialRelationsResponse overloaded = fullProvider.batchCheckSocialRelations(req).join();
        assertThat(overloaded.getErrorMessage().getId())
                .isEqualTo(CommonErrorTip.common_error.kServiceUnavailable_VALUE);
    }
}
