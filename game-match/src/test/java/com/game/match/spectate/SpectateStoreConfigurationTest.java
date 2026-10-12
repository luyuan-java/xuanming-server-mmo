package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.game.common.deadline.Deadline;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.SpectateStore.Eviction;
import com.game.match.testing.LeaseOnlyRedis;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 观战存储的装配（工作包 W1）：bean 是 Redis 实现、恰好一个；<b>构造时不碰 Redis</b>（进程启动与上下文测试都不依赖 Redis 此刻可达——
 * {@code MatchApplicationContextTest} 用的就是只应答发号租约的替身）；Redis 用不了时每个同步方法按「依赖故障」抛、两个尽力方法静默。
 * 脚本的行为由 {@code RedissonSpectateStoreIntegrationTest} 在真 Redis 上钉，Java 一侧的编解码由 {@code RedissonSpectateStoreTest} 钉。
 */
class SpectateStoreConfigurationTest {

    private static Deadline d() {
        return Deadline.after(1_000);
    }

    @Test
    void 装配出来的是Redis实现_构造时不碰Redis() {
        RedissonClient redis = mock(RedissonClient.class);

        SpectateStore store = new SpectateStoreConfiguration().spectateStore(redis);

        assertThat(store).isInstanceOf(RedissonSpectateStore.class);
        verifyNoInteractions(redis);
    }

    @Test
    void 上下文里恰好一个观战存储_取RedissonClient这一个依赖_起上下文不碰Redis() {
        RedissonClient redis = mock(RedissonClient.class);

        new ApplicationContextRunner().withBean(RedissonClient.class, () -> redis).withUserConfiguration(SpectateStoreConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SpectateStore.class);
                    assertThat(context.getBean(SpectateStore.class)).isInstanceOf(RedissonSpectateStore.class);
                    assertThat(context.getBean("spectateStore")).as("bean 名不变：别的包按类型或按这个名字注入").isSameAs(context.getBean(SpectateStore.class));
                });
        verifyNoInteractions(redis);

        new ApplicationContextRunner().withUserConfiguration(SpectateStoreConfiguration.class)
                .run(context -> assertThat(context).as("没有 RedissonClient 就起不来：依赖是显式的，不悄悄退回占位").hasFailed());
    }

    @Test
    void Redis用不了_每个同步方法都抛依赖异常_不折成没有或空列表() {
        // 只应答发号租约的替身：脚本的异步口回 null、别的口也回 null——对观战存储来说就是「Redis 用不了」
        SpectateStore store = new SpectateStoreConfiguration().spectateStore(new LeaseOnlyRedis().client);
        BattlePlacement placement = BattlePlacement.newBuilder().setBattleId(77).setAttempt(1).setCreatedAtMs(1_900_000_000_000L).build();
        Class<Deadline.DependencyException> failure = Deadline.DependencyException.class;

        assertThatThrownBy(() -> store.entry(1001, d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.acquire(1001, "77:0123456789abcdef", d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.release(1001, "77:0123456789abcdef", d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.marksOf(List.of(1001L), d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.read(77, d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.pickRandom(0.5, d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.evict(new Eviction.Missing(77), d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.publish(placement, d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.list(20, d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.readPlacements(List.of(77L), d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.sweep(d())).isInstanceOf(failure);
        assertThatThrownBy(() -> store.watchableCount(d())).isInstanceOf(failure);
    }

    @Test
    void Redis用不了_两个尽力方法不抛() {
        SpectateStore store = new SpectateStoreConfiguration().spectateStore(new LeaseOnlyRedis().client);

        assertThatCode(() -> store.releaseAsync(1001, "77:0123456789abcdef")).doesNotThrowAnyException();
        assertThatCode(() -> store.evictAsync(List.of(new Eviction.Invalid("junk"), new Eviction.Dead(77, 1)))).doesNotThrowAnyException();
        assertThatCode(() -> store.evictAsync(List.of())).doesNotThrowAnyException();
    }
}
