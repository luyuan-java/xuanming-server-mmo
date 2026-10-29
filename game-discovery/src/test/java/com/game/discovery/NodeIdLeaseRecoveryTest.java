package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.game.common.GameNamespace;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.redisson.client.codec.Codec;

/** 手动时钟与响应脚本驱动真实租约状态机；不睡眠、不启动后台线程。 */
class NodeIdLeaseRecoveryTest {
    private static final long SECOND = 1_000_000_000L;
    private static final Duration TTL = Duration.ofSeconds(15);
    private static final RedisKeys KEYS = new RedisKeys(GameNamespace.of("leasecases"));

    private static final class Fixture {
        final RedissonClient redis = mock(RedissonClient.class);
        final ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        final ScheduledFuture<?> task = mock(ScheduledFuture.class);
        final Deque<Supplier<Object>> replies = new ArrayDeque<>();
        final List<String> events = new ArrayList<>();
        final RBucket<Object> bucket;
        final RAtomicLong counter = mock(RAtomicLong.class);
        long now = 100 * SECOND;
        long minted = 40;
        boolean failIncrement;
        boolean incrementBeforeFailure;
        boolean canAcquire = true;
        int acquisitions;
        final NodeIdLease lease;

        @SuppressWarnings("unchecked")
        Fixture(boolean recoverable) {
            bucket = mock(RBucket.class);
            when(redis.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
            when(bucket.setIfAbsent(any(), any(Duration.class))).thenAnswer(call -> { acquisitions++; return canAcquire; });
            when(counter.incrementAndGet()).thenAnswer(call -> {
                if (failIncrement) {
                    if (incrementBeforeFailure) minted++;
                    throw new IllegalStateException("INCR response unknown");
                }
                return ++minted;
            });
            when(redis.getAtomicLong(anyString())).thenReturn(counter);
            RScript script = mock(RScript.class, call -> call.getMethod().getName().equals("eval")
                    ? replies.remove().get() : null);
            when(redis.getScript(any(Codec.class))).thenReturn(script);
            when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any(TimeUnit.class))).thenAnswer(call -> task);
            if (recoverable) lease = NodeIdLease.acquireRecoverable(redis, KEYS, scheduler, NodeTypes.SCENE, 3, 7, 7,
                    "original", TTL, new NodeIdLease.RecoveryListener() {
                        public void onSuspended() { events.add("suspended"); }
                        public void onResumed() { events.add("resumed"); }
                        public void onRegained(long epoch) { events.add("regained:" + epoch); }
                        public void onTaken() { events.add("taken"); }
                    }, () -> now);
            else lease = NodeIdLease.acquire(redis, KEYS, scheduler, NodeTypes.SCENE, 3, 7, 7,
                    "original", TTL, () -> events.add("lost"), () -> now);
        }
        void reply(long value) { replies.add(() -> value); }
        void failure() { replies.add(() -> { throw new IllegalStateException("Redis unavailable"); }); }
    }

    @Test void 满TTL未知挂起只通知一次_原持有者续上按原代次恢复() {
        var f = new Fixture(true);
        f.now += 15 * SECOND;
        f.failure(); f.lease.renew();
        assertThat(f.lease.isSuspended()).isTrue();
        assertThat(f.lease.isLost()).isFalse();
        assertThat(f.lease.isValid()).isFalse();
        f.failure(); f.lease.renew();
        assertThat(f.events).containsExactly("suspended");
        f.reply(1); f.lease.renew();
        assertThat(f.events).containsExactly("suspended", "resumed");
        assertThat(f.lease.leaseEpoch()).isEqualTo(41);
        assertThat(f.lease.isValid()).isTrue();
        verify(f.task, never()).cancel(anyBoolean());
    }

    @Test void 短暂失败仅暂停有效性_未挂起不发恢复回调() {
        var f = new Fixture(true);
        f.now += 10 * SECOND;
        f.failure(); f.lease.renew();
        assertThat(f.lease.isValid()).isFalse();
        assertThat(f.lease.isSuspended()).isFalse();
        f.reply(1); f.lease.renew();
        assertThat(f.lease.isValid()).isTrue();
        assertThat(f.events).isEmpty();
        assertThat(f.lease.leaseEpoch()).isEqualTo(41);
    }

    @Test void 空租约只复占原号_新代次确认后恢复且不发普通resumed() {
        var f = new Fixture(true);
        f.reply(0); f.reply(1); f.lease.renew();
        assertThat(f.lease.nodeId()).isEqualTo(7);
        assertThat(f.acquisitions).isEqualTo(2);
        verify(f.redis, times(2)).getBucket(org.mockito.ArgumentMatchers.eq(KEYS.nodeId(NodeTypes.SCENE, 3, 7)), any(Codec.class));
        assertThat(f.lease.leaseEpoch()).isEqualTo(42);
        assertThat(f.events).containsExactly("suspended", "regained:42");
        assertThat(f.lease.isValid()).isTrue();
        assertThat(f.replies).isEmpty();
    }

    @Test void 空键复占竞争失败永久Taken_后续不再碰Redis() {
        var f = new Fixture(true);
        f.canAcquire = false;
        f.reply(0); f.lease.renew();
        f.reply(1); f.lease.renew();
        assertThat(f.events).containsExactly("suspended", "taken");
        assertThat(f.lease.isLost()).isTrue();
        assertThat(f.lease.isValid()).isFalse();
        assertThat(f.lease.leaseEpoch()).isEqualTo(41);
        assertThat(f.replies).hasSize(1);
        verify(f.task).cancel(false);
    }

    @Test void INCR失败且释放也失败_即使下次续上本实例也必须领新代次() {
        var f = new Fixture(true);
        f.failIncrement = true;
        f.incrementBeforeFailure = true; // Redis实际INCR成功，但响应丢失。
        f.reply(0); f.failure(); f.lease.renew(); // 失败的 INCR 后，释放原号也失败。
        assertThat(f.lease.isSuspended()).isTrue();
        assertThat(f.lease.isValid()).isFalse();
        assertThat(f.lease.leaseEpoch()).isEqualTo(41);
        assertThat(f.events).containsExactly("suspended");
        f.failIncrement = false;
        f.reply(1); f.reply(1); f.lease.renew();
        assertThat(f.lease.leaseEpoch()).isEqualTo(43);
        assertThat(f.events).containsExactly("suspended", "regained:43");
        assertThat(f.lease.isValid()).isTrue();
        assertThat(f.replies).isEmpty();
    }

    @Test void INCR失败释放成功_下轮空键复占仍必须领新代次() {
        var f = new Fixture(true);
        f.failIncrement = true;
        f.reply(0); f.reply(1); f.lease.renew();
        assertThat(f.lease.isValid()).isFalse();
        assertThat(f.lease.leaseEpoch()).isEqualTo(41);
        f.failIncrement = false;
        f.reply(0); f.reply(1); f.lease.renew();
        assertThat(f.acquisitions).isEqualTo(3);
        assertThat(f.events).containsExactly("suspended", "regained:42");
        assertThat(f.lease.isValid()).isTrue();
    }

    @Test void INCR往返间原号被抢_新代次不能发布且进入Taken() {
        var f = new Fixture(true);
        f.reply(0); f.reply(-1); f.lease.renew();
        assertThat(f.minted).isEqualTo(42);
        assertThat(f.lease.leaseEpoch()).isEqualTo(41);
        assertThat(f.lease.isValid()).isFalse();
        assertThat(f.lease.isLost()).isTrue();
        assertThat(f.events).containsExactly("suspended", "taken");
    }

    @Test void INCR后确认键已过期_保持挂起到再次复占_不发布中间代次() {
        var f = new Fixture(true);
        f.reply(0); f.reply(0); f.lease.renew();
        assertThat(f.lease.leaseEpoch()).isEqualTo(41);
        assertThat(f.lease.isSuspended()).isTrue();
        assertThat(f.lease.isValid()).isFalse();
        f.reply(0); f.reply(1); f.lease.renew();
        assertThat(f.lease.leaseEpoch()).isEqualTo(43);
        assertThat(f.events).containsExactly("suspended", "regained:43");
    }

    @Test void 复占往返耗尽本地有效窗口不能把回包时刻当新起点() {
        var f = new Fixture(true);
        f.reply(0);
        f.replies.add(() -> { f.now += 10 * SECOND; return 1L; });
        f.lease.renew();
        assertThat(f.lease.leaseEpoch()).isEqualTo(42);
        assertThat(f.lease.isValid()).isFalse();
        f.reply(1); f.lease.renew();
        assertThat(f.lease.isValid()).isTrue();
    }

    @Test void 旧模式仍把空键与其他持有者及超TTL错误映射永久丢失() {
        for (long response : new long[]{0, -1}) {
            var f = new Fixture(false);
            f.reply(response); f.lease.renew(); f.lease.renew();
            assertThat(f.events).containsExactly("lost");
            assertThat(f.acquisitions).isEqualTo(1);
            assertThat(f.lease.isLost()).isTrue();
            assertThat(f.lease.isValid()).isFalse();
        }
        var f = new Fixture(false);
        f.now += 15 * SECOND + 1;
        f.failure(); f.lease.renew();
        assertThat(f.events).containsExactly("lost");
        assertThat(f.lease.isLost()).isTrue();
    }
}
