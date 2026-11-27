package com.game.discovery.workshard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.common.workshard.WorkShard;
import com.game.discovery.RedisKeys;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

class RedisWorkShardMembershipTest {

    @SuppressWarnings("unchecked")
    @Test
    void stable副本注册并按活跃集合切分64片_canary不入表且在有stable时不占片() {
        RedissonClient redis = mock(RedissonClient.class);
        RScoredSortedSet<String> set = mock(RScoredSortedSet.class);
        RedisKeys keys = new RedisKeys(GameNamespace.of("game"));
        when(redis.<String>getScoredSortedSet(keys.workShardMembers("trade", 0), StringCodec.INSTANCE)).thenReturn(set);
        AtomicLong clock = new AtomicLong(100_000L);
        when(set.valueRange(100_000L, false, Double.POSITIVE_INFINITY, true))
                .thenReturn(List.of("trade-1", "trade-2"));

        try (var stable = new RedisWorkShardMembership(redis, keys, "trade", 0, "trade-1", "stable", clock::get);
             var canary = new RedisWorkShardMembership(redis, keys, "trade", 0, "trade-canary", "canary", clock::get)) {
            stable.refresh();
            canary.refresh();

            verify(set).add(100_000L + RedisWorkShardMembership.LEASE_TTL_MS, "trade-1");
            verify(set, never()).add(anyDouble(), eq("trade-canary"));

            WorkShard.SqlFilter stableFilter = stable.membership().sqlFilter("trade_commerce", "commerce_id");
            assertThat(stableFilter.isAll()).isFalse();
            assertThat(stableFilter.isNone()).isFalse();
            assertThat(stableFilter.sql()).startsWith("MOD(commerce_id,64) IN(");

            // 有存活 stable 时 canary 不占片
            assertThat(canary.membership().sqlFilter("trade_commerce", "commerce_id").isNone()).isTrue();

            // 过期超 30s 后双方都保活回落全片
            clock.addAndGet(WorkShard.DEFAULT_STALE_AFTER_MS + 1);
            assertThat(stable.membership().sqlFilter("trade_commerce", "commerce_id").isAll()).isTrue();
            assertThat(canary.membership().sqlFilter("trade_commerce", "commerce_id").isAll()).isTrue();
        }
        verify(set).remove("trade-1");
        verify(set, never()).remove("trade-canary");
        verify(set, never()).add(anyDouble(), eq(""));
    }
}
