package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

/**
 * {@link NodeIdLease#isValid()} 的界（不连 Redis）：Redis 用替身，单调时钟手动拨动，续期由测试直接驱动。
 */
class NodeIdLeaseTest {

    private static final Duration TTL = Duration.ofSeconds(15);
    private static final long SEC = 1_000_000_000L;

    private final long[] now = {1_000 * SEC};
    /** 每次续期 eval 的结果：返回值或要抛的异常；执行时可拨动时钟模拟往返耗时。 */
    private final Deque<Supplier<Object>> evalResults = new ArrayDeque<>();
    private final AtomicInteger lostCalls = new AtomicInteger();
    private final long[] epochValue = {0};
    private RedissonClient redis;
    private ScheduledExecutorService scheduler;
    private NodeIdLease lease;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        RedissonClient redis = mock(RedissonClient.class);
        RBucket<Object> bucket = mock(RBucket.class);
        when(redis.getBucket(anyString(), any(Codec.class))).thenReturn(bucket);
        when(bucket.setIfAbsent(any(), any(Duration.class))).thenReturn(true);
        RScript script = mock(RScript.class, invocation -> invocation.getMethod().getName().equals("eval")
                ? evalResults.remove().get() : null);
        when(redis.getScript(any(Codec.class))).thenReturn(script);
        RAtomicLong epochCounter = mock(RAtomicLong.class);
        when(epochCounter.incrementAndGet()).thenAnswer(inv -> ++epochValue[0]);
        when(redis.getAtomicLong(anyString())).thenReturn(epochCounter);
        this.redis = redis;

        scheduler = mock(ScheduledExecutorService.class);
        when(scheduler.scheduleAtFixedRate(any(), anyLong(), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> mock(ScheduledFuture.class));

        lease = NodeIdLease.acquire(redis, scheduler, NodeTypes.LOGIN, 0, 0, 3, "me", TTL, lostCalls::incrementAndGet,
                () -> now[0]);
    }

    @Test
    void 每次占号领一个严格递增的防护代次() {
        assertThat(lease.leaseEpoch()).isEqualTo(1);
        NodeIdLease again = NodeIdLease.acquire(redis, scheduler, NodeTypes.LOGIN, 0, 0, 3, "me-2", TTL, () -> { },
                () -> now[0]);
        assertThat(again.leaseEpoch()).isEqualTo(2);
    }

    @Test
    void 领不到防护代次_放弃这个号并启动失败() {
        RAtomicLong broken = mock(RAtomicLong.class);
        when(broken.incrementAndGet()).thenThrow(new IllegalStateException("Redis 断开"));
        when(redis.getAtomicLong(anyString())).thenReturn(broken);
        evalResults.add(() -> 1L);

        assertThatThrownBy(() -> NodeIdLease.acquire(redis, scheduler, NodeTypes.LOGIN, 0, 0, 3, "me-3", TTL, () -> { },
                () -> now[0])).isInstanceOf(IllegalStateException.class).hasMessageContaining("防护代次");
        assertThat(evalResults).as("已执行释放脚本（删掉仍属于本实例的租约键）").isEmpty();
    }

    private void advance(long nanos) {
        now[0] += nanos;
    }

    @Test
    void 新租约有效_满三分之二TTL未续期即无效但未丢失() {
        assertThat(lease.isValid()).isTrue();

        advance(10 * SEC - 1);
        assertThat(lease.isValid()).isTrue();

        advance(1);
        assertThat(lease.isValid()).as("距上次成功续期已满 2/3 TTL").isFalse();
        assertThat(lease.isLost()).isFalse();
        assertThat(lostCalls).hasValue(0);
    }

    @Test
    void 续期成功恢复有效_有效期从发出续期命令之前算起() {
        advance(10 * SEC);
        assertThat(lease.isValid()).isFalse();

        // 续期往返耗时 3s：有效期起点是发出前，而不是收到应答后。
        evalResults.add(() -> {
            advance(3 * SEC);
            return 1L;
        });
        lease.renew();
        assertThat(lease.isValid()).isTrue();

        advance(7 * SEC - 1);
        assertThat(lease.isValid()).isTrue();
        advance(1);
        assertThat(lease.isValid()).as("发出续期后 10s（不是收到应答后 10s）").isFalse();
    }

    @Test
    void 续期失败但未超TTL_暂时无效_下一轮成功后恢复() {
        advance(5 * SEC);
        evalResults.add(() -> {
            throw new IllegalStateException("Redis 超时");
        });
        lease.renew();
        assertThat(lease.isLost()).isFalse();

        advance(5 * SEC);
        assertThat(lease.isValid()).isFalse();

        evalResults.add(() -> 1L);
        lease.renew();
        assertThat(lease.isValid()).isTrue();
        assertThat(lostCalls).hasValue(0);
    }

    @Test
    void 号被他人占用_丢失且永久无效() {
        evalResults.add(() -> 0L);
        lease.renew();

        assertThat(lease.isLost()).isTrue();
        assertThat(lease.isValid()).isFalse();
        assertThat(lostCalls).hasValue(1);

        lease.renew();
        assertThat(lostCalls).as("丢失回调只调一次，丢失后不再续期").hasValue(1);
    }

    @Test
    void 续期失败超过TTL_判丢失() {
        advance(TTL.toNanos() + 1);
        evalResults.add(() -> {
            throw new IllegalStateException("Redis 不可达");
        });
        lease.renew();

        assertThat(lease.isLost()).isTrue();
        assertThat(lease.isValid()).isFalse();
        assertThat(lostCalls).hasValue(1);
    }
}
