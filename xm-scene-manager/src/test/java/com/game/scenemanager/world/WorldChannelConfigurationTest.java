package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.game.discovery.world.WorldChannelStore;
import com.game.scenemanager.SceneNodeSource;
import com.game.scenemanager.WorldSceneConfigs;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 装配（不起 Dubbo、不连 Redis）：{@code xm.scene-manager.world.*} 绑定、Q8 启动校验让上下文起不来、{@code leader-eligible=false} 不占租约不起线程、
 * 健康组件与指标 bean 在场。
 */
class WorldChannelConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(WorldChannelConfiguration.class)
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withBean(SceneNodeSource.class, () -> zone -> List.of())
            .withBean(WorldSceneConfigs.class, () -> new WorldSceneConfigs(1, new LinkedHashSet<>(List.of(1, 2))))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("xm.scene-manager.world.leader-eligible=false");

    @Test
    void 只做数据面时不起控制面_其余bean在场() {
        runner.withPropertyValues("xm.scene-manager.world.channel-count-by-config.[1]=16",
                        "xm.scene-manager.world.coverage=per-node")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(WorldChannelControlPlane.class).coordinator()).isNull();
                    assertThat(ctx).hasSingleBean(WorldChannelStore.class);
                    assertThat(ctx).hasSingleBean(WorldChannelMetrics.class);
                    assertThat(ctx).hasBean("worldChannelsHealthIndicator");
                    WorldChannelProperties props = ctx.getBean(WorldChannelProperties.class);
                    assertThat(props.seedFor(1)).isEqualTo(16);
                    assertThat(props.reservationTtl()).isEqualTo(Duration.ofSeconds(10));
                    assertThat(ctx.getBean(MeterRegistry.class).get(WorldChannelMetrics.CHANNELS)
                            .tags("scene_config", "other", "state", "draining").gauge().value()).isZero();
                });
    }

    @Test
    void 预占TTL短于login归属夺取等待时上下文起不来_Q8() {
        runner.withPropertyValues("xm.scene-manager.world.reservation-ttl=2s",
                        "xm.scene-manager.world.login-owner-claim-wait=3s")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("owner-claim-wait");
                });
    }
}
