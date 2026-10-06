package com.game.match.testing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

/**
 * 只应答「发号租约」用到的那几条命令的 {@link RedissonClient} 替身：给起整个 Spring 上下文、但不想连 Redis 的装配测试用
 * （{@code MatchConfiguration} 在启动时唯一真的碰 Redis 的地方就是申领 {@code NodeTypes.MATCH} 的租约；各目录与读口都是用到才读）。
 *
 * <ul>
 *   <li>占号（{@code SET NX}）→ 成功；领防护代次（{@code INCR}）→ 1；</li>
 *   <li>续期 / 释放脚本（同步 {@code eval}）→ 1（租约一直有效）；{@link #loseLease()} 之后续期回 0（下一次续期时租约被判丢失）。</li>
 * </ul>
 * 其余调用回 Mockito 的缺省值（null / 0）：装配阶段走不到；业务读写请用各自的内存替身，不要经这个对象。
 *
 * <pre>
 * LeaseOnlyRedis redis = new LeaseOnlyRedis();
 * // @TestConfiguration 里： @Bean RedissonClient redissonClient() { return redis.client; }
 * assertThat(redis.leaseAcquisitions()).isEqualTo(1);
 * </pre>
 */
public final class LeaseOnlyRedis {

    /** 交给被测装配的客户端。 */
    public final RedissonClient client = mock(RedissonClient.class);
    private final AtomicInteger acquisitions = new AtomicInteger();
    private final AtomicInteger scriptEvals = new AtomicInteger();
    private final AtomicBoolean lost = new AtomicBoolean();

    @SuppressWarnings("unchecked")
    public LeaseOnlyRedis() {
        RBucket<Object> bucket = mock(RBucket.class, invocation -> {
            if (invocation.getMethod().getName().equals("setIfAbsent")) {
                acquisitions.incrementAndGet();
                return Boolean.TRUE;
            }
            return null;
        });
        doReturn(bucket).when(client).getBucket(anyString(), any(Codec.class));
        RAtomicLong epoch = mock(RAtomicLong.class);
        when(epoch.incrementAndGet()).thenReturn(1L);
        when(client.getAtomicLong(anyString())).thenReturn(epoch);
        RScript script = mock(RScript.class, invocation -> {
            if (invocation.getMethod().getName().equals("eval")) {
                scriptEvals.incrementAndGet();
                return lost.get() ? 0L : 1L;
            }
            return null;
        });
        when(client.getScript(any(Codec.class))).thenReturn(script);
    }

    /** 占号的次数（每个上下文启动一次）。 */
    public int leaseAcquisitions() {
        return acquisitions.get();
    }

    /** 续期 / 释放脚本执行的次数。 */
    public int scriptEvals() {
        return scriptEvals.get();
    }

    /** 之后的续期都回「号已不属于本实例」：下一次续期（TTL 的 1/3 之后）租约被判丢失。 */
    public LeaseOnlyRedis loseLease() {
        lost.set(true);
        return this;
    }
}
