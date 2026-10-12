package com.game.scenemanager.world;

import com.game.discovery.world.RedissonWorldChannelStore;
import com.game.discovery.world.WorldChannelStore;
import com.game.scenemanager.SceneIdAllocator;
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

    /**
     * 停服时先停控制面线程、属主校验放锁；发号租约属于 {@link SceneIdAllocator} bean（每个副本都申领，批次 5.3 R5），
     * 它被这里依赖，Spring 先销毁本 bean 再还租约。
     */
    @Bean(destroyMethod = "close")
    public WorldChannelControlPlane worldChannelControlPlane(WorldChannelStore store, SceneNodeSource sceneNodeSource,
                                                             WorldSceneConfigs worldConfigs, WorldChannelProperties props,
                                                             WorldChannelMetrics metrics, SceneIdAllocator sceneIdAllocator) {
        return WorldChannelControlPlane.start(store, sceneNodeSource, worldConfigs, props, metrics, sceneIdAllocator);
    }

    /** 健康组件 {@code worldChannels}（bean 名去掉 HealthIndicator 后缀即组件名）。 */
    @Bean
    public WorldChannelsHealthIndicator worldChannelsHealthIndicator(WorldChannelStore store, SceneNodeSource sceneNodeSource,
                                                                     WorldSceneConfigs worldConfigs) {
        return new WorldChannelsHealthIndicator(store, sceneNodeSource, worldConfigs);
    }
}
