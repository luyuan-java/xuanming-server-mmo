package com.game.scene.currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.game.scene.metrics.SceneMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GainBlockSyncTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final List<GlobalGainBlocks> loaded = new CopyOnWriteArrayList<>();
    private final AtomicReference<Set<Integer>> remote = new AtomicReference<>(Set.of());
    private final AtomicReference<RuntimeException> failure = new AtomicReference<>();
    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicLong now = new AtomicLong(1_000_000_000L);
    private GainBlockSync sync;

    private GainBlockSync sync(GainBlockSync.Source source) {
        sync = new GainBlockSync(source, loaded::add, new SceneMetrics(meters), now::get);
        return sync;
    }

    private Set<Integer> load() {
        loads.incrementAndGet();
        RuntimeException e = failure.get();
        if (e != null) {
            throw e;
        }
        return remote.get();
    }

    @AfterEach
    void stop() {
        if (sync != null) {
            sync.stop();
        }
    }

    @Test
    void 启动时同步读一次并交出_读不到直接抛() {
        remote.set(Set.of(1));
        GainBlockSync s = sync(this::load);
        assertThat(s.syncAgeSeconds()).as("还没成功同步过：NaN，不误触告警").isNaN();
        GlobalGainBlocks first = s.loadNow();

        assertThat(first.blocksCurrency(1)).isTrue();
        assertThat(loaded).containsExactly(first);
        assertThat(sync.entries()).isEqualTo(1);

        failure.set(new IllegalStateException("Redis 不可达"));
        assertThatThrownBy(() -> sync.loadNow()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 重读失败沿用上次并计数_同步时长随之增长_恢复后换上新名单() {
        remote.set(Set.of(1));
        GainBlockSync s = sync(this::load);
        s.loadNow();

        failure.set(new IllegalStateException("Redis 不可达"));
        now.addAndGet(30_000_000_000L);
        s.refreshQuietly();
        s.refreshQuietly();

        assertThat(loaded).hasSize(1);
        assertThat(meters.get("xm.scene.gain.block.sync.failures").counter().count()).isEqualTo(2);
        assertThat(s.syncAgeSeconds()).isEqualTo(30.0);

        failure.set(null);
        remote.set(Set.of(1, 2));
        s.refreshQuietly();
        assertThat(loaded.get(loaded.size() - 1).currencies()).containsExactlyInAnyOrder(1, 2);
        assertThat(s.syncAgeSeconds()).isZero();
    }

    @Test
    void 变更通知触发重读_重读期间来的通知合并成再读一次() throws Exception {
        CountDownLatch inLoad = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GainBlockSync s = sync(() -> {
            if (loads.incrementAndGet() == 2) {
                inLoad.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return remote.get();
        });
        s.loadNow();
        s.start(Duration.ofHours(1));

        s.requestRefresh();
        assertThat(inLoad.await(5, TimeUnit.SECONDS)).isTrue();
        remote.set(Set.of(7));
        s.requestRefresh();
        s.requestRefresh();
        s.requestRefresh();
        release.countDown();

        await().atMost(Duration.ofSeconds(5)).until(() -> loads.get() == 3);
        await().atMost(Duration.ofSeconds(5)).until(() -> loaded.size() == 3);
        Thread.sleep(200);
        assertThat(loads.get()).as("重读期间的 3 次通知合并成 1 次").isEqualTo(3);
        assertThat(loaded.get(2).currencies()).containsExactly(7);
    }

    @Test
    void 名单字段非法的跳过() {
        assertThat(RedisGainBlockSource.parse(Set.of("1", "x", "-3", "2147483648", "0"))).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void 停止中断卡在Redis上的重读_不等它读完_也不算同步失败() throws Exception {
        CountDownLatch inLoad = new CountDownLatch(1);
        GainBlockSync s = sync(() -> {
            if (loads.incrementAndGet() == 1) {
                return Set.of();
            }
            inLoad.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("被中断", e);
            }
            return Set.of();
        });
        s.loadNow();
        s.start(Duration.ofHours(1));
        s.requestRefresh();
        assertThat(inLoad.await(5, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        s.stop();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        Thread.sleep(200);
        assertThat(meters.find("xm.scene.gain.block.sync.failures").counter()).isNull();
        s.requestRefresh();
        assertThat(loads.get()).as("停了之后的通知直接忽略").isEqualTo(2);
    }
}
