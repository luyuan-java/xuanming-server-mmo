package com.game.data.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.game.common.id.Snowflake;
import com.game.data.testing.TestIds;
import com.game.data.testing.TestIds.FakeLease;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** T-N1：号源只在租约有效时发号；租约无效 / 丢失时为空（写接口据此回 503）；丢失后换新租约；停机交还。 */
class OpsIdsTest {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
    }

    @Test
    void 领到租约前不发号_领到后发的号带着租约的worker() {
        FakeLease lease = new FakeLease(37);
        OpsIds ids = new OpsIds(onLost -> lease, scheduler, () -> Snowflake.DEFAULT_EPOCH_MS + 1_000, Duration.ofMillis(10));
        assertThat(ids.tryNext()).as("还没开始申领").isEmpty();

        ids.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> ids.workerId().isPresent());

        long id = ids.tryNext().orElseThrow();
        assertThat(id).isPositive();
        assertThat((id >>> Snowflake.SEQUENCE_BITS) & Snowflake.MAX_WORKER).as("雪花里的 worker 段").isEqualTo(37);
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            assertThat(seen.add(ids.tryNext().orElseThrow())).isTrue();
        }
        assertThat(ids.isRunning()).isTrue();
    }

    @Test
    void 租约续期滞后_不发号_恢复后照常() {
        FakeLease lease = new FakeLease(5);
        OpsIds ids = TestIds.ready(lease);

        lease.valid.set(false);
        assertThat(ids.tryNext()).isEmpty();
        assertThat(ids.workerId()).isEmpty();

        lease.valid.set(true);
        assertThat(ids.tryNext()).isPresent();
        ids.close();
    }

    @Test
    void 申领失败按间隔重试_租约丢失后停发并换一份新租约_停机交还() {
        FakeLease first = new FakeLease(1);
        FakeLease second = new FakeLease(2);
        Queue<FakeLease> leases = new ArrayDeque<>();
        leases.add(first);
        leases.add(second);
        AtomicInteger attempts = new AtomicInteger();
        OpsIds ids = new OpsIds(onLost -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("Redis 不可达");
            }
            FakeLease next = leases.remove();
            next.onLost = onLost;
            return next;
        }, scheduler, System::currentTimeMillis, Duration.ofMillis(10));

        ids.start();
        await().atMost(Duration.ofSeconds(5)).until(() -> ids.workerId().isPresent());
        assertThat(attempts.get()).as("第一次失败后重试").isEqualTo(2);
        assertThat(ids.workerId().getAsInt()).isEqualTo(1);

        first.lose();
        OptionalLong during = ids.tryNext();
        await().atMost(Duration.ofSeconds(5)).until(() -> ids.workerId().isPresent());
        assertThat(ids.workerId().getAsInt()).as("换了一份新租约").isEqualTo(2);
        assertThat(during.isEmpty() || ((during.getAsLong() >>> Snowflake.SEQUENCE_BITS) & Snowflake.MAX_WORKER) == 2)
                .as("丢失之后绝不再用旧 worker 发号").isTrue();

        ids.stop();
        assertThat(second.closed).as("停机交还当前租约").isTrue();
        assertThat(ids.isRunning()).isFalse();
        assertThat(ids.tryNext()).isEmpty();
        assertThat(ids.getPhase()).as("比 Web 服务器更晚停").isLessThan(Integer.MAX_VALUE - 2048);
    }
}
