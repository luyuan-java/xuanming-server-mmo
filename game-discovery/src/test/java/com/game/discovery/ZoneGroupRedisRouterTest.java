package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

class ZoneGroupRedisRouterTest {

    @Test
    void routesByZoneGroupEnforcesSingleZoneScopeAndDetectsRevisionRegression() {
        RedissonClient fallback = mock(RedissonClient.class);
        RedissonClient groupA = mock(RedissonClient.class);
        RedissonClient groupB = mock(RedissonClient.class);

        // zone 1..10 -> 组 1，zone 11..20 -> 组 2
        var multiZone = new ZoneGroupRedisRouter(fallback, z -> z <= 10 ? 1 : 2, Map.of(1, groupA, 2, groupB), 0);
        assertThat(multiZone.clientForZone(3)).isSameAs(groupA);
        assertThat(multiZone.clientForZone(15)).isSameAs(groupB);

        // 单 zone 作用域：允许本 zone，拒绝碰其他 zone 控制状态
        var singleZone = new ZoneGroupRedisRouter(fallback, z -> 1, Map.of(1, groupA), 7);
        assertThat(singleZone.clientForZone(7)).isSameAs(groupA);
        assertThatThrownBy(() -> singleZone.clientForZone(8))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zone=7");

        // 分钟级抖动全量重拉间隔在 [60s, 120s]
        assertThat(ZoneGroupRedisRouter.nextFullResyncDelay(0.0)).isEqualTo(Duration.ofSeconds(60));
        assertThat(ZoneGroupRedisRouter.nextFullResyncDelay(1.0)).isEqualTo(Duration.ofSeconds(120));

        // 注册中心修订号倒退守卫：单调推进不换纪元，倒退立即递增纪元
        var guard = new ZoneGroupRedisRouter.RegistryEpochGuard();
        assertThat(guard.observeRevision(100L)).isFalse();
        assertThat(guard.observeRevision(150L)).isFalse();
        assertThat(guard.epoch()).isEqualTo(1L);
        assertThat(guard.observeRevision(42L)).isTrue();
        assertThat(guard.epoch()).isEqualTo(2L);
    }
}
