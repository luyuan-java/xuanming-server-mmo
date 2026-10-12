package com.game.match.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.api.rpc.NodeRpcClients.Target;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/**
 * match 侧直连客户端缓存的两件事（{@link NodeClientCache}）：按空闲清掉不再用的引用；带着「记下来的目标」发调用时不顶掉这个地址上现有的引用。
 * 底层缓存用一个按 {@code NodeRpcClients} 的规则记账的替身：同一地址上实例号不同 = 销毁旧引用再建。真 Dubbo 上的表现见
 * {@code RpcFailuresLoopbackTest}。
 */
class NodeClientCacheTest {

    private static final Duration IDLE = Duration.ofSeconds(360);
    private static final Duration TIMEOUT = Duration.ofSeconds(3);
    private static final Target A_OLD = new Target("10.0.0.1", 21200, "inst-old");
    private static final Target A_NEW = new Target("10.0.0.1", 21200, "inst-new");
    private static final Target B = new Target("10.0.0.2", 21200, "inst-b");

    /** 底层缓存的替身：记下每次调用带的目标，并按「同地址换实例就重建」记销毁。服务就是目标地址串。 */
    private static final class RecordingClients implements NodeClientCache.Clients<String> {
        final List<Target> calls = new ArrayList<>();
        final List<Target> evicted = new ArrayList<>();
        /** 因实例号不同而被销毁的引用（来回重建的次数就看它）。 */
        final List<Target> rebuilt = new ArrayList<>();
        final java.util.Map<String, Target> live = new java.util.HashMap<>();
        boolean closed;
        RuntimeException evictError;

        @Override
        public <R> CompletableFuture<R> call(Target target, Duration timeout, Function<String, CompletableFuture<R>> invocation) {
            calls.add(target);
            Target existing = live.put(target.address(), target);
            if (existing != null && !existing.instanceId().equals(target.instanceId())) {
                rebuilt.add(existing);
            }
            return invocation.apply(target.address());
        }

        @Override
        public void evict(Target target) {
            if (evictError != null) {
                throw evictError;
            }
            Target existing = live.get(target.address());
            if (existing != null && existing.instanceId().equals(target.instanceId())) {
                live.remove(target.address());
                evicted.add(target);
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private final RecordingClients clients = new RecordingClients();
    private final AtomicLong nanos = new AtomicLong(1_000_000_000L);
    private final NodeClientCache<String> cache = new NodeClientCache<>("test", clients, IDLE, nanos::get);
    private final NodeCalls<String> calls = cache.calls();

    private static CompletableFuture<String> echo(String service) {
        return CompletableFuture.completedFuture("reply@" + service);
    }

    private void advance(Duration time) {
        nanos.addAndGet(time.toNanos());
    }

    // ================================================================ 发调用

    @Test
    void 调用原样转给底层缓存_应答原样带回() throws Exception {
        assertThat(calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo).get(1, TimeUnit.SECONDS)).isEqualTo("reply@10.0.0.1:21200");

        assertThat(clients.calls).containsExactly(A_NEW);
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.name()).isEqualTo("test");
    }

    @Test
    void 刚从目录读出来的目标_实例号变了就照它重建() {
        calls.call(A_OLD, TIMEOUT, NodeClientCacheTest::echo);
        calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo);

        assertThat(clients.calls).containsExactly(A_OLD, A_NEW);
        assertThat(clients.rebuilt).as("节点原地重启：换到新进程的引用").containsExactly(A_OLD);
    }

    /**
     * 补偿的取消、回滚的销毁带的是先前记下的实例号。节点原地重启之后，别的 gather 已经把这个地址上的引用换成了新实例：
     * 记下来的目标不得把它顶回去（顶回去 = 销毁新引用，其上在途的备战 / 建房全部按传输失败收场，下一次调用再顶一次）。
     */
    @Test
    void 记下来的目标_这个地址上已有引用就用它_不因实例号过时而重建() throws Exception {
        calls.call(A_OLD, TIMEOUT, NodeClientCacheTest::echo);
        calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo);
        clients.rebuilt.clear();

        String reply = calls.callRemembered(A_OLD, TIMEOUT, NodeClientCacheTest::echo).get(1, TimeUnit.SECONDS);
        calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo);
        calls.callRemembered(A_OLD, TIMEOUT, NodeClientCacheTest::echo);

        assertThat(reply).as("请求照样发到这个地址上").isEqualTo("reply@10.0.0.1:21200");
        assertThat(clients.calls).as("记下来的那两次用的是现有的新实例引用").containsExactly(A_OLD, A_NEW, A_NEW, A_NEW, A_NEW);
        assertThat(clients.rebuilt).as("没有任何来回重建").isEmpty();
    }

    @Test
    void 记下来的目标_这个地址上还没有引用时照它建_之后目录给了新实例照常换() {
        calls.callRemembered(A_OLD, TIMEOUT, NodeClientCacheTest::echo);
        calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo);
        calls.callRemembered(B, TIMEOUT, NodeClientCacheTest::echo);

        assertThat(clients.calls).containsExactly(A_OLD, A_NEW, B);
        assertThat(clients.rebuilt).containsExactly(A_OLD);
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void 底层调用抛异常或返回失败的future_原样给调用方_锁照常放开() {
        assertThatThrownBy(() -> calls.call(A_NEW, TIMEOUT, service -> {
            throw new IllegalStateException("调用方的 bug");
        })).isInstanceOf(IllegalStateException.class);
        CompletableFuture<String> failed = calls.call(A_NEW, TIMEOUT, service -> CompletableFuture.failedFuture(new IllegalStateException("连不上")));

        assertThat(failed).isCompletedExceptionally();
        assertThat(cache.sweepIdle()).as("锁没有被占着").isZero();
        assertThatThrownBy(() -> calls.call(null, TIMEOUT, NodeClientCacheTest::echo)).isInstanceOf(NullPointerException.class);
    }

    // ================================================================ 空闲清扫

    @Test
    void 空闲超过阈值的地址被清掉_还在用的留着() {
        calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo);
        calls.call(B, TIMEOUT, NodeClientCacheTest::echo);
        advance(IDLE.minusSeconds(1));
        calls.call(B, TIMEOUT, NodeClientCacheTest::echo);
        assertThat(cache.sweepIdle()).as("差 1 s 才到阈值").isZero();

        advance(Duration.ofSeconds(1));
        assertThat(cache.sweepIdle()).isEqualTo(1);

        assertThat(clients.evicted).as("节点下线 / 换地址之后不再留着它的引用反复重连").containsExactly(A_NEW);
        assertThat(cache.size()).isEqualTo(1);

        advance(IDLE);
        assertThat(cache.sweepIdle()).isEqualTo(1);
        assertThat(clients.evicted).containsExactly(A_NEW, B);
        assertThat(cache.size()).isZero();
        assertThat(cache.sweepIdle()).as("没有可清的").isZero();
    }

    @Test
    void 清扫按底层缓存里此刻的实例号销毁_记下来的调用也算在用() {
        calls.call(A_OLD, TIMEOUT, NodeClientCacheTest::echo);
        calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo);
        advance(IDLE.minusSeconds(1));
        calls.callRemembered(A_OLD, TIMEOUT, NodeClientCacheTest::echo);
        advance(Duration.ofSeconds(2));
        assertThat(cache.sweepIdle()).as("刚用记下来的目标发过调用：不空闲").isZero();

        advance(IDLE);
        assertThat(cache.sweepIdle()).isEqualTo(1);
        assertThat(clients.evicted).as("底层按 地址 + 实例 销毁：必须给它现在挂着的那个实例").containsExactly(A_NEW);
    }

    @Test
    void 清掉之后再用_重新建_照常调通() throws Exception {
        calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo);
        advance(IDLE);
        assertThat(cache.sweepIdle()).isEqualTo(1);

        assertThat(calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo).get(1, TimeUnit.SECONDS)).isEqualTo("reply@10.0.0.1:21200");

        assertThat(cache.size()).isEqualTo(1);
        assertThat(clients.live).containsKey("10.0.0.1:21200");
    }

    @Test
    void 销毁出错只记日志_条目照样摘掉_别的地址照清() {
        calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo);
        calls.call(B, TIMEOUT, NodeClientCacheTest::echo);
        advance(IDLE);
        clients.evictError = new IllegalStateException("注入的故障: 销毁出错");

        assertThat(cache.sweepIdle()).as("不抛异常").isEqualTo(2);

        assertThat(cache.size()).isZero();
    }

    /**
     * 「登记 + 发起调用」与「判空闲 + 销毁」互斥：清扫不会销毁一个正在被取用的引用。这里让一次调用停在底层缓存里，
     * 同时发起清扫——清扫必须等它出来，出来之后那个地址刚被用过，不再空闲。
     */
    @Test
    void 清扫与发起调用互斥_不会销毁正在被取用的引用() throws Exception {
        calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo);
        advance(IDLE);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread caller = Thread.ofPlatform().start(() -> calls.call(A_NEW, TIMEOUT, service -> {
            inside.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return echo(service);
        }));
        assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Integer> swept = CompletableFuture.supplyAsync(cache::sweepIdle);

        Thread.sleep(150);
        assertThat(swept).as("调用还在底层缓存里：清扫等着").isNotDone();
        release.countDown();
        caller.join(10_000);

        assertThat(swept.get(10, TimeUnit.SECONDS)).as("那个地址刚被用过").isZero();
        assertThat(clients.evicted).isEmpty();
    }

    // ================================================================ 关闭与参数

    @Test
    void 关闭连同底层缓存一起关() {
        calls.call(A_NEW, TIMEOUT, NodeClientCacheTest::echo);

        cache.close();

        assertThat(clients.closed).isTrue();
        assertThat(cache.size()).isZero();
    }

    @Test
    void 空闲阈值必须为正() {
        assertThatThrownBy(() -> new NodeClientCache<>("bad", clients, Duration.ZERO, nanos::get)).isInstanceOf(IllegalArgumentException.class);
    }
}
