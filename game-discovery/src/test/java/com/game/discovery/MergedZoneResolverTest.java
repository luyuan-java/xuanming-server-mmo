package com.game.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 不停服合服读时最终区解析与缓存保旧单测。 */
class MergedZoneResolverTest {

    @Test
    void 链式合服解析最终区与成员集合展开及防环检测() {
        var r = MergedZoneResolver.of(Map.of(2, 1, 3, 2, 5, 4));
        assertThat(r.resolveFinalZone(3)).isEqualTo(1);
        assertThat(r.resolveFinalZone(2)).isEqualTo(1);
        assertThat(r.resolveFinalZone(1)).isEqualTo(1);
        assertThat(r.resolveFinalZone(9)).isEqualTo(9);
        assertThat(r.sameFinalZone(3, 1)).isTrue();
        assertThat(r.sameFinalZone(3, 5)).isFalse();
        assertThat(r.memberZonesOf(2)).containsExactly(1, 2, 3);
        assertThat(r.memberZonesOf(9)).containsExactly(9);

        // 存在环路时构造拒绝
        assertThatThrownBy(() -> MergedZoneResolver.of(Map.of(1, 2, 2, 1)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 缓存启动失败抛异常_刷新失败保留旧快照() {
        AtomicReference<Map<Integer, Integer>> source = new AtomicReference<>(Map.of(2, 1));
        AtomicBoolean fail = new AtomicBoolean(false);
        var cache = MergedZoneResolver.Cache.loadInitial(() -> {
            if (fail.get()) {
                throw new IllegalStateException("db down");
            }
            return source.get();
        });
        assertThat(cache.current().resolveFinalZone(2)).isEqualTo(1);

        fail.set(true);
        assertThat(cache.tryRefresh()).isFalse();
        assertThat(cache.current().resolveFinalZone(2)).isEqualTo(1);

        fail.set(false);
        source.set(Map.of(2, 1, 3, 1));
        assertThat(cache.tryRefresh()).isTrue();
        assertThat(cache.current().memberZonesOf(1)).containsExactly(1, 2, 3);
    }
}
