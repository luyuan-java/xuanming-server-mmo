package com.game.scenemanager.world;

import com.game.discovery.world.RedissonWorldChannelStore;
import com.game.discovery.world.WorldChannelStore;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.WorldSceneConfigs;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 主世界频道（批次 5.1）的装配：Redis 门面 → 指标 → 控制面（分 zone 领导者，{@code leader-eligible=false} 时不起）→ 健康组件。
 * 数据面（分配时的软预占）在 {@code SceneManagerConfiguration} 里接 {@link WorldChannelStore}。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorldChannelProperties.class)
public class WorldChannelConfiguration {

    @Bean
    public WorldChannelStore worldChannelStore(RedissonClient redis) {
        return new RedissonWorldChannelStore(redis);
    }

    @Bean
    public WorldChannelMetrics worldChannelMetrics(MeterRegistry meterRegistry, WorldSceneConfigs worldConfigs) {
        return new WorldChannelMetrics(meterRegistry, worldConfigs.orderedConfigIds());
    }

    /** 停服时先停控制面线程、属主校验放锁，再还发号租约。 */
    @Bean(destroyMethod = "close")
    public WorldChannelControlPlane worldChannelControlPlane(RedissonClient redis, WorldChannelStore store,
                                                             SceneNodeSource sceneNodeSource, WorldSceneConfigs worldConfigs,
                                                             WorldChannelProperties props, WorldChannelMetrics metrics) {
        return WorldChannelControlPlane.start(redis, store, sceneNodeSource, worldConfigs, props, metrics);
    }

    /** 健康组件 {@code worldChannels}（bean 名去掉 HealthIndicator 后缀即组件名）。 */
    @Bean
    public WorldChannelsHealthIndicator worldChannelsHealthIndicator(WorldChannelStore store, SceneNodeSource sceneNodeSource,
                                                                     WorldSceneConfigs worldConfigs) {
        return new WorldChannelsHealthIndicator(store, sceneNodeSource, worldConfigs);
    }
}
