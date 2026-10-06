package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.data.rollback.BattleLockGate;
import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.context.support.GenericApplicationContext;

/**
 * 回档前战斗锁闸的生产接线（{@link DataConfiguration#battleLockGate}）：Redis 客户端是懒加载 bean，装配期不许取（取 = 连接）；
 * 读锁时才取，取不到按读不到处理（fail-closed），读的是 xm-discovery 的战斗锁键；等待上限接的是 {@code xm.data.ops.battle-lock-wait}
 * （键的绑定与校验在 {@code OpsWriteGateTest}，闸本身的到点在 {@code rollback/BattleLockGateTest}，这里钉住两者之间的接线）。
 * 真 Redis 上的往返在 {@code rollback/BattleLockGateRedisIntegrationTest}。
 */
class BattleLockWiringTest {

    private static DataProperties props() {
        return propsOf(Map.of());
    }

    private static DataProperties propsOf(Map<String, String> settings) {
        return new Binder(new MapConfigurationPropertySource(settings)).bindOrCreate("xm.data", DataProperties.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RedissonClient> provider() {
        return mock(ObjectProvider.class);
    }

    @Test
    void 装配期不取Redis客户端_读锁时才取_取不到按读不到处理不抛出() {
        ObjectProvider<RedissonClient> redis = provider();

        BattleLockGate gate = new DataConfiguration().battleLockGate(redis, props());

        verifyNoInteractions(redis);

        when(redis.getObject()).thenThrow(new BeanCreationException("redissonClient", "Unable to connect to Redis server"));
        BattleLockGate.Result r = gate.check(List.of(7L, 8L), () -> { });

        verify(redis, times(1)).getObject();
        assertThat(r.unknown()).containsExactly(7L, 8L);
        assertThat(r.inBattle()).isEmpty();
        assertThat(r.outcome(7)).isEqualTo("battle_lock_unknown");
        assertThat(r.error()).contains("Unable to connect to Redis server");
    }

    @Test
    void Spring装配这颗bean时不建Redis客户端_第一次读锁才建() {
        AtomicInteger created = new AtomicInteger();
        RedissonClient client = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(client.getKeys()).thenReturn(keys);
        when(keys.countExistsAsync(RedisKeys.battleLock(7))).thenReturn(new CompletableFutureWrapper<Long>(1L));
        // 只装配要看的这一颗：整个 DataConfiguration 要库 / Kafka，这里把它当普通 bean，按容器调 @Bean 方法的同一种方式
        //（工厂方法 + 按类型注入参数）建出 battleLockGate，并让容器把全部单例实例化一遍
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            // 与生产同形：Redis 客户端是懒加载 bean（xm-discovery 的 RedisAutoConfiguration），实例化 = 连接
            context.registerBean("redissonClient", RedissonClient.class, () -> {
                created.incrementAndGet();
                return client;
            }, definition -> definition.setLazyInit(true));
            context.registerBean(DataProperties.class, BattleLockWiringTest::props);
            context.registerBean("dataConfiguration", DataConfiguration.class);
            RootBeanDefinition gateDefinition = new RootBeanDefinition(BattleLockGate.class);
            gateDefinition.setFactoryBeanName("dataConfiguration");
            gateDefinition.setFactoryMethodName("battleLockGate");
            gateDefinition.setAutowireMode(AbstractBeanDefinition.AUTOWIRE_CONSTRUCTOR);
            context.registerBeanDefinition("battleLockGate", gateDefinition);
            context.refresh();

            BattleLockGate gate = context.getBean(BattleLockGate.class);
            assertThat(created).as("装配期（含容器启动时实例化全部单例）不取 Redis 客户端").hasValue(0);

            BattleLockGate.Result r = gate.check(List.of(7L), () -> { });

            assertThat(created).hasValue(1);
            assertThat(r.inBattle()).containsExactly(7L);
        }
    }

    @Test
    void 逐人EXISTS战斗锁键_锁在的in_battle_其余可写_任何一键读失败整批读不到() {
        ObjectProvider<RedissonClient> redis = provider();
        RedissonClient client = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(redis.getObject()).thenReturn(client);
        when(client.getKeys()).thenReturn(keys);
        when(keys.countExistsAsync(RedisKeys.battleLock(7))).thenReturn(new CompletableFutureWrapper<Long>(1L));
        when(keys.countExistsAsync(RedisKeys.battleLock(8))).thenReturn(new CompletableFutureWrapper<Long>(0L));
        BattleLockGate gate = new DataConfiguration().battleLockGate(redis, props());

        BattleLockGate.Result r = gate.check(List.of(7L, 8L), () -> { });

        assertThat(r.inBattle()).containsExactly(7L);
        assertThat(r.unknown()).isEmpty();
        assertThat(r.outcome(8)).isNull();
        verify(keys).countExistsAsync("xm:battle:{7}:lock");
        verify(keys).countExistsAsync("xm:battle:{8}:lock");

        // 一个键读失败：existsAll 整体异常完成 → 这一批谁也不当成「不在战」
        when(keys.countExistsAsync(RedisKeys.battleLock(8)))
                .thenReturn(new CompletableFutureWrapper<Long>(new IllegalStateException("Redis 超时")));
        BattleLockGate.Result failed = gate.check(List.of(7L, 8L), () -> { });
        assertThat(failed.unknown()).containsExactly(7L, 8L);
        assertThat(failed.inBattle()).isEmpty();
        assertThat(failed.error()).contains("Redis 超时");
    }

    @Test
    void 等待上限取自配置键battle_lock_wait_Redis一直不应答时按这个时长到点_按读不到处理() {
        ObjectProvider<RedissonClient> redis = provider();
        RedissonClient client = mock(RedissonClient.class);
        RKeys keys = mock(RKeys.class);
        when(redis.getObject()).thenReturn(client);
        when(client.getKeys()).thenReturn(keys);
        // EXISTS 发出去之后一直没有应答（连接卡住）：只有闸自己的等待上限能让这次检查结束
        CompletableFuture<Long> never = new CompletableFuture<>();
        when(keys.countExistsAsync(RedisKeys.battleLock(7))).thenReturn(new CompletableFutureWrapper<Long>(never));
        // 230 ms：与缺省 5 s、以及 xm.data 里别的时长（claim-wait 35 s、heartbeat 5 s、job-timeout 30 min……）都对不上
        DataProperties props = propsOf(Map.of("xm.data.ops.battle-lock-wait", "230ms"));
        assertThat(props.ops().battleLockWait()).isEqualTo(Duration.ofMillis(230));

        BattleLockGate gate = new DataConfiguration().battleLockGate(redis, props);
        long start = System.nanoTime();
        // 接成别的时长时不陪它等满（最长的是 30 min）：3 s 没回来就判失败
        BattleLockGate.Result r = assertTimeoutPreemptively(Duration.ofSeconds(3), () -> gate.check(List.of(7L), () -> { }),
                "战斗锁闸没有按 xm.data.ops.battle-lock-wait（230 ms）到点");
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(r.unknown()).containsExactly(7L);
        assertThat(r.inBattle()).isEmpty();
        assertThat(r.outcome(7)).isEqualTo("battle_lock_unknown");
        // 错误文本带闸实际用的毫秒数：就是配置的 230，不是 5000 或别的
        assertThat(r.error()).contains("超时").containsPattern("(?<!\\d)230 ms");
        assertThat(elapsedMs).as("等满配置的时长才放弃").isGreaterThanOrEqualTo(200L);
        verify(keys).countExistsAsync("xm:battle:{7}:lock");
    }
}
