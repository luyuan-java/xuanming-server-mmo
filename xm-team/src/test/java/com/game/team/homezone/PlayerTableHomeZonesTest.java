package com.game.team.homezone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.common.player.PlayerProfiles.Profile;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** home zone = player.zone_id（D5；基线 homezone_test / service_test.go:305 TestDataServiceHomeZone 的 Java 对应）。 */
class PlayerTableHomeZonesTest {

    private static Profile profile(long id, int zone) {
        return new Profile(id, "n", 1, 1, 0, "", zone);
    }

    @Test
    void 缺行或zone为0都是缺项_交给调用方回4019() {
        HomeZones zones = new PlayerTableHomeZones((ids, d) -> Map.of(1L, profile(1, 3), 2L, profile(2, 0)),
                Duration.ofMillis(1500));
        Map<Long, Integer> got = zones.homeZones(List.of(1L, 2L, 3L), Deadline.after(3500));
        assertThat(got).containsExactly(Map.entry(1L, 3));
    }

    @Test
    void 查询失败原样抛出_交给调用方回4030() {
        HomeZones down = new PlayerTableHomeZones((ids, d) -> {
            throw new DependencyException("读玩家资料失败");
        }, Duration.ofMillis(1500));
        assertThatThrownBy(() -> down.homeZones(List.of(1L), Deadline.after(3500))).isInstanceOf(DependencyException.class);
    }

    @Test
    void 查询预算取单次超时与请求剩余预算的较小者() {
        AtomicLong seen = new AtomicLong();
        HomeZones zones = new PlayerTableHomeZones((ids, d) -> {
            seen.set(d.remainingMillis());
            return Map.of();
        }, Duration.ofMillis(1500));
        zones.homeZones(List.of(1L), Deadline.after(3500));
        assertThat(seen.get()).isBetween(1000L, 1500L);
        zones.homeZones(List.of(1L), Deadline.after(300));
        assertThat(seen.get()).isLessThanOrEqualTo(300L);
        zones.homeZones(List.of(1L), Deadline.after(0));
        assertThat(seen.get()).as("请求预算已用完：交给严格读按「超过请求预算」抛出").isZero();
    }
}
