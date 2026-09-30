package com.game.login.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoginWorkerPoolTest {

    @Test
    void 绑定线程池指标_队列满照常拒绝() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(1);
        try (LoginWorkerPool pool = new LoginWorkerPool(1, 1, Duration.ofSeconds(5))) {
            pool.bindTo(meters);
            pool.execute(() -> {
                running.countDown();
                awaitQuietly(release);
            });
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            pool.execute(() -> { });

            assertThat(meters.get("executor.queued").tag("name", LoginWorkerPool.METRICS_NAME).gauge().value())
                    .isEqualTo(1);
            assertThat(meters.get("executor.active").tag("name", LoginWorkerPool.METRICS_NAME).gauge().value())
                    .isEqualTo(1);
            assertThatThrownBy(() -> pool.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
            release.countDown();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
