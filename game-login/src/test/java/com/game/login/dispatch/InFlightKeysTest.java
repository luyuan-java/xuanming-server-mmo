package com.game.login.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** 在途闸门的进程内模式与多实例 Redis 模式（SET NX PX + Lua 令牌核对释放）。 */
class InFlightKeysTest {

    private static final RedisKeys KEYS = new RedisKeys(GameNamespace.of("game"));

    private static RedissonClient fakeRedis(Map<String, String> backing) {
        RedissonClient redis = mock(RedissonClient.class);
        RScript script = mock(RScript.class);
        when(redis.getScript(StringCodec.INSTANCE)).thenReturn(script);
        when(script.eval(eq(RScript.Mode.READ_WRITE), eq(InFlightKeys.ACQUIRE_SCRIPT),
                eq(RScript.ReturnType.INTEGER), anyList(), any(), any()))
                .thenAnswer(inv -> {
                    List<Object> keys = inv.getArgument(3);
                    String key = (String) keys.get(0);
                    String token = inv.getArgument(4);
                    String ttlMs = inv.getArgument(5);
                    assertThat(Long.parseLong(ttlMs)).isPositive();
                    return backing.putIfAbsent(key, token) == null ? 1L : 0L;
                });
        when(script.eval(eq(RScript.Mode.READ_WRITE), eq(InFlightKeys.RELEASE_SCRIPT),
                eq(RScript.ReturnType.INTEGER), anyList(), any()))
                .thenAnswer(inv -> {
                    List<Object> keys = inv.getArgument(3);
                    String key = (String) keys.get(0);
                    String token = inv.getArgument(4);
                    return backing.remove(key, token) ? 1L : 0L;
                });
        return redis;
    }

    @Test
    void 无参构造仅本进程互斥_释放后可再次占用() {
        var gate = new InFlightKeys<String>();
        assertThat(gate.tryAcquire("acc1")).isTrue();
        assertThat(gate.isHeld("acc1")).isTrue();
        assertThat(gate.tryAcquire("acc1")).isFalse();
        gate.release("acc1");
        assertThat(gate.isHeld("acc1")).isFalse();
        assertThat(gate.tryAcquire("acc1")).isTrue();
    }

    @Test
    void 多实例共享Redis互斥_且令牌不匹配时不误删新持有者的锁() {
        var backing = new ConcurrentHashMap<String, String>();
        var redis = fakeRedis(backing);
        var instanceA = InFlightKeys.<Long>distributed(redis, id -> KEYS.loginInFlight("enter", Long.toUnsignedString(id)), Duration.ofSeconds(15));
        var instanceB = InFlightKeys.<Long>distributed(redis, id -> KEYS.loginInFlight("enter", Long.toUnsignedString(id)), Duration.ofSeconds(15));

        assertThat(instanceA.tryAcquire(42L)).isTrue();
        assertThat(backing).containsKey("game:login:inflight:enter:42");
        assertThat(instanceB.tryAcquire(42L)).as("另一实例不可重入同一玩家").isFalse();
        assertThat(instanceB.isHeld(42L)).isFalse();

        // 模拟 A 的锁已过期、B 随后持有了新令牌：A 迟到的 release 不得删掉 B 的锁
        backing.remove("game:login:inflight:enter:42");
        assertThat(instanceB.tryAcquire(42L)).isTrue();
        String bToken = backing.get("game:login:inflight:enter:42");
        instanceA.release(42L);
        assertThat(backing).containsEntry("game:login:inflight:enter:42", bToken);

        // B 自己释放后，锁才被删除，A 可重新占用
        instanceB.release(42L);
        assertThat(backing).doesNotContainKey("game:login:inflight:enter:42");
        assertThat(instanceA.tryAcquire(42L)).isTrue();
        instanceA.release(42L);
        assertThat(backing).isEmpty();
    }

    @Test
    void Redis故障时加锁降级为本进程互斥_释放不抛异常() {
        RedissonClient broken = mock(RedissonClient.class);
        RScript script = mock(RScript.class, inv -> {
            throw new RuntimeException("redis timeout");
        });
        when(broken.getScript(StringCodec.INSTANCE)).thenReturn(script);

        var gate = InFlightKeys.<String>distributed(broken, acc -> KEYS.loginInFlight("account", acc), Duration.ofSeconds(15));
        assertThat(gate.tryAcquire("acc1")).isTrue();
        assertThat(gate.tryAcquire("acc1")).as("降级后仍守住本进程并发").isFalse();
        gate.release("acc1");
        assertThat(gate.isHeld("acc1")).isFalse();
    }

    @Test
    void 传入空Redis退化为本进程互斥_非正TTL拒绝构造() {
        var fallback = InFlightKeys.<String>distributed(null, acc -> acc, Duration.ofSeconds(15));
        assertThat(fallback.tryAcquire("k")).isTrue();
        fallback.release("k");

        assertThatThrownBy(() -> InFlightKeys.distributed(mock(RedissonClient.class), acc -> "k", Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
