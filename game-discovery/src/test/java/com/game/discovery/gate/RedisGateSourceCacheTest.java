package com.game.discovery.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.proto.GateNodeInfo;
import com.game.discovery.drain.GateDrainMarks;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 按 zone 1 秒单飞短缓存回归（对齐主仓缺口清单第 31 条 DrainMarkCache）。 */
class RedisGateSourceCacheTest {

    private static GateNodeInfo gate(int nodeId, String instanceId) {
        return GateNodeInfo.newBuilder()
                .setZoneId(1)
                .setNodeId(nodeId)
                .setInstanceId(instanceId)
                .setClientHost("127.0.0.1")
                .setClientPort(11000 + nodeId)
                .build();
    }

    @Test
    void 一秒内命中缓存_过期后刷新_空列表同样缓存() {
        AtomicLong nanos = new AtomicLong(10_000_000_000L);
        AtomicInteger dirCalls = new AtomicInteger();
        AtomicInteger drainCalls = new AtomicInteger();
        List<GateNodeInfo> current = new ArrayList<>(List.of(gate(1, "inst-1")));

        RedisGateSource source = new RedisGateSource(
                zone -> {
                    dirCalls.incrementAndGet();
                    return List.copyOf(current);
                },
                (zone, nodes) -> {
                    drainCalls.incrementAndGet();
                    return Map.of(1, new GateDrainMarks.Mark(100L, "inst-1", "100:inst-1"));
                },
                Duration.ofSeconds(1),
                nanos::get);

        List<GateNodeInfo> first = source.listGates(1);
        assertThat(first).singleElement().satisfies(g -> assertThat(g.getDraining()).isTrue());
        assertThat(dirCalls.get()).isEqualTo(1);
        assertThat(drainCalls.get()).isEqualTo(1);

        // 999ms 内命中缓存，不回源 Redis
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(999));
        assertThat(source.listGates(1)).isEqualTo(first);
        assertThat(dirCalls.get()).isEqualTo(1);
        assertThat(drainCalls.get()).isEqualTo(1);

        // 跨过 1 秒 TTL 后重新拉取，空结果同样缓存一个周期
        current.clear();
        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(2));
        assertThat(source.listGates(1)).isEmpty();
        assertThat(dirCalls.get()).isEqualTo(2);
        assertThat(drainCalls.get()).isEqualTo(1);

        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(500));
        assertThat(source.listGates(1)).isEmpty();
        assertThat(dirCalls.get()).as("空列表在一个周期内命中缓存").isEqualTo(2);
    }

    @Test
    void 目录异常不缓存_排空标记异常按未排空放行() {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicInteger dirCalls = new AtomicInteger();

        RedisGateSource failDir = new RedisGateSource(
                zone -> {
                    if (dirCalls.incrementAndGet() == 1) {
                        throw new IllegalStateException("redis down");
                    }
                    return List.of(gate(2, "inst-2"));
                },
                (zone, nodes) -> Map.of(),
                Duration.ofSeconds(1),
                nanos::get);

        assertThatThrownBy(() -> failDir.listGates(1)).isInstanceOf(IllegalStateException.class);
        // 紧接着同一纳秒再读：失败未缓存，立刻恢复
        assertThat(failDir.listGates(1)).hasSize(1);
        assertThat(dirCalls.get()).isEqualTo(2);

        RedisGateSource failDrain = new RedisGateSource(
                zone -> List.of(gate(3, "inst-3")),
                (zone, nodes) -> {
                    throw new IllegalStateException("mget timeout");
                },
                Duration.ofSeconds(1),
                nanos::get);
        List<GateNodeInfo> gates = failDrain.listGates(1);
        assertThat(gates).singleElement().satisfies(g -> assertThat(g.getDraining()).isFalse());
    }

    @Test
    void 同zone并发过期单飞只查一次Redis() throws Exception {
        AtomicLong nanos = new AtomicLong(1_000_000_000L);
        AtomicInteger dirCalls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        RedisGateSource source = new RedisGateSource(
                zone -> {
                    dirCalls.incrementAndGet();
                    entered.countDown();
                    try {
                        release.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return List.of(gate(5, "inst-5"));
                },
                (zone, nodes) -> Map.of(),
                Duration.ofSeconds(1),
                nanos::get);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<List<GateNodeInfo>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> source.listGates(7)));
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(50);
            release.countDown();
            for (Future<List<GateNodeInfo>> f : futures) {
                assertThat(f.get(5, TimeUnit.SECONDS)).hasSize(1);
            }
            assertThat(dirCalls.get()).as("同 zone 8 并发过期只回源 1 次").isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
