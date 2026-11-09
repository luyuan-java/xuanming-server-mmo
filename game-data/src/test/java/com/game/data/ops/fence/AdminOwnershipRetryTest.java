package com.game.data.ops.fence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.data.metrics.DataMetrics;
import com.game.player.store.PlayerStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 重试编排不以真实SQL吞吐量定次数；真实SQL/Redis语义仍由相邻集成测试覆盖。 */
class AdminOwnershipRetryTest {
    @Test
    void 每轮Held重发本轮实际纪元_原预算耗尽时Busy保留最后观察的归属() {
        var store = mock(PlayerStore.class);
        when(store.claimOwnership(eq(1001L), any(Duration.class)))
                .thenReturn(new PlayerStore.ClaimResult.Held(5), new PlayerStore.ClaimResult.Held(6),
                        new PlayerStore.ClaimResult.TimedOut());
        var epochs = new ArrayList<Long>();
        var pauses = new ArrayList<Duration>();
        var meters = new SimpleMeterRegistry();
        try {
            var ownership = new AdminOwnership(store, new TransactionTemplate(mock(PlatformTransactionManager.class)),
                    (player, epoch) -> { assertThat(player).isEqualTo(1001); epochs.add(epoch); },
                    (player, epoch) -> CompletableFuture.completedFuture(true), new DataMetrics(meters));

            var outcome = ownership.claim(1001, true, Duration.ofSeconds(2), pauses::add);

            assertThat(outcome).isEqualTo(new AdminOwnership.Claim.Busy(6));
            assertThat(epochs).containsExactly(5L, 6L);
            assertThat(pauses).hasSize(2);
            assertThat(pauses.get(1)).isGreaterThan(pauses.get(0));
            assertThat(ownership.heldCount()).isZero();
            verify(store, times(3)).claimOwnership(eq(1001L), any(Duration.class));
        } finally {
            meters.close();
        }
    }
}
