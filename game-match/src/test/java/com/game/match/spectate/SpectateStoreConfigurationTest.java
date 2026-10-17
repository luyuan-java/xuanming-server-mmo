package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.discovery.RedisProperties;
import com.game.discovery.namespace.GameNamespaceAutoConfiguration;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.SpectateStore.Eviction;
import com.game.match.testing.LeaseOnlyRedis;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.ObjectParams;
import org.redisson.api.options.OptionalOptions;
import org.redisson.misc.CompletableFutureWrapper;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 观战存储的装配（工作包 W1）：bean 是 Redis 实现、恰好一个；<b>构造时不碰 Redis</b>（进程启动与上下文测试都不依赖 Redis 此刻可达——
 * {@code MatchApplicationContextTest} 用的就是只应答发号租约的替身）；Redis 用不了时每个同步方法按「依赖故障」抛、两个尽力方法静默。
 * 脚本的行为由 {@code RedissonSpectateStoreIntegrationTest} 在真 Redis 上钉，Java 一侧的编解码由 {@code RedissonSpectateStoreTest} 钉。
 */
class SpectateStoreConfigurationTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));
    /** {@code game.redis.*} 全取缺省。 */
    private static final RedisProperties REDIS_PROPS = new RedisProperties(null, null, null, null, null, null, null);

    private static Deadline d() {
        return Deadline.after(1_000);
    }

    @Test
    void 装配出来的是Redis实现_构造时不碰Redis() {
        RedissonClient redis = mock(RedissonClient.class);

        SpectateStore store = new SpectateStoreConfiguration().spectateStore(redis, REDIS_KEYS, REDIS_PROPS);

        assertThat(store).isInstanceOf(RedissonSpectateStore.class);
        verifyNoInteractions(redis);
    }

    @Test
    void 抢标记的Redisson超时以配置的game_redis_timeout_ms封顶_不用缺省值() {
        // 抢标记是 163 的提交点（RedisClientWrites）：超时 = min(配置值, 剩余预算)。装配若漏传配置值、退回缺省 2 s，这条会失败
        RedissonClient redis = mock(RedissonClient.class);
        List<OptionalOptions> clientWrites = new CopyOnWriteArrayList<>();
        RScript script = mock(RScript.class, invocation -> invocation.getMethod().getName().equals("evalShaAsync")
                ? new CompletableFutureWrapper<>(SpectateScripts.ACQUIRE_OK) : null);
        when(redis.getScript(any(OptionalOptions.class))).thenAnswer(invocation -> {
            clientWrites.add(invocation.getArgument(0));
            return script;
        });
        SpectateStore store = new SpectateStoreConfiguration().spectateStore(redis, REDIS_KEYS,
                new RedisProperties(null, null, null, null, 700, null, null));

        assertThat(store.acquire(1001, "77:0123456789abcdef", Deadline.after(60_000))).isEqualTo(SpectateStore.Acquire.OK);

        assertThat(clientWrites).hasSize(1);
        assertThat(((ObjectParams) clientWrites.get(0)).getTimeout()).as("剩余预算远多于配置值：取配置的 700 ms").isEqualTo(700);
    }

    @Test
    void 上下文里恰好一个观战存储_取RedissonClient_RedisKeys与RedisProperties三个依赖_起上下文不碰Redis() {
        RedissonClient redis = mock(RedissonClient.class);

        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GameNamespaceAutoConfiguration.class))
                .withBean(RedissonClient.class, () -> redis).withBean(RedisProperties.class, () -> REDIS_PROPS)
                .withUserConfiguration(SpectateStoreConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(SpectateStore.class);
                    assertThat(context.getBean(SpectateStore.class)).isInstanceOf(RedissonSpectateStore.class);
                    assertThat(context.getBean("spectateStore")).as("bean 名不变：别的包按类型或按这个名字注入").isSameAs(context.getBean(SpectateStore.class));
                });
        verifyNoInteractions(redis);

        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GameNamespaceAutoConfiguration.class))
                .withBean(RedisProperties.class, () -> REDIS_PROPS).withUserConfiguration(SpectateStoreConfiguration.class)
                .run(context -> assertThat(context).as("没有 RedissonClient 就起不来：依赖是显式的，不悄悄退回占位").hasFailed());
        new ApplicationContextRunner().withBean(RedissonClient.class, () -> redis).withBean(RedisProperties.class, () -> REDIS_PROPS)
                .withUserConfiguration(SpectateStoreConfiguration.class)
                .run(context -> assertThat(context).as("没有 RedisKeys（命名空间）也起不来：键前缀只经注入，没有缺省实例").hasFailed());
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(GameNamespaceAutoConfiguration.class))
                .withBean(RedissonClient.class, () -> redis).withUserConfiguration(SpectateStoreConfiguration.class)
                .run(context -> assertThat(context).as("没有 RedisProperties 也起不来：客户端写的命令超时只取配置值，不悄悄退回缺省").hasFailed());
    }

    @Test
    void Redis用不了_每个同步方法都抛依赖异常_不折成没有或空列表() {
        // 只应答发号租约的替身：脚本的异步口回 null、别的口也回 null——对观战存储来说就是「Redis 用不了」
        SpectateStore store = new SpectateStoreConfiguration().spectateStore(new LeaseOnlyRedis().client, REDIS_KEYS, REDIS_PROPS);
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
        SpectateStore store = new SpectateStoreConfiguration().spectateStore(new LeaseOnlyRedis().client, REDIS_KEYS, REDIS_PROPS);

        assertThatCode(() -> store.releaseAsync(1001, "77:0123456789abcdef")).doesNotThrowAnyException();
        assertThatCode(() -> store.evictAsync(List.of(new Eviction.Invalid("junk"), new Eviction.Dead(77, 1)))).doesNotThrowAnyException();
        assertThatCode(() -> store.evictAsync(List.of())).doesNotThrowAnyException();
    }
}
