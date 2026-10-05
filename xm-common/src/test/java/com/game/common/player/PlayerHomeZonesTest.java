package com.game.common.player;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.common.player.PlayerProfiles.Profile;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * 归属区 = player.zone_id（team D5、guild D3、trade T3）。原 xm-team PlayerTableHomeZonesTest 的用例上移到这里
 * （基线 homezone_test / service_test.go:305 TestDataServiceHomeZone、trade home_zone_test.go 的 Java 对应）。
 */
class PlayerHomeZonesTest {

    /** 大于 Long.MAX_VALUE 的玩家号（uint64 位模式）。 */
    private static final long BIG = 0x8000_0000_0000_0001L;

    private static Profile profile(long id, int zone) {
        return new Profile(id, "n", 1, 1, 0, "", zone);
    }

    @Test
    void 缺行或zone为0都是缺项_交给调用方回归属区未确认() {
        HomeZones zones = new PlayerHomeZones((ids, d) -> Map.of(1L, profile(1, 3), 2L, profile(2, 0)),
                Duration.ofMillis(1500));
        Map<Long, Integer> got = zones.homeZones(List.of(1L, 2L, 3L), Deadline.after(3500));
        assertThat(got).containsExactly(Map.entry(1L, 3));
    }

    @Test
    void 查询失败原样抛出_交给调用方按故障处理() {
        HomeZones down = new PlayerHomeZones((ids, d) -> {
            throw new DependencyException("读玩家资料失败");
        });
        assertThatThrownBy(() -> down.homeZones(List.of(1L), Deadline.after(3500))).isInstanceOf(DependencyException.class);
        assertThatThrownBy(() -> down.homeZoneOf(1L, Deadline.after(3500))).isInstanceOf(DependencyException.class);
    }

    @Test
    void 查询预算取单次超时与请求剩余预算的较小者() {
        AtomicLong seen = new AtomicLong();
        HomeZones zones = new PlayerHomeZones((ids, d) -> {
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

    @Test
    void 缺省单次超时是1500毫秒() {
        assertThat(PlayerHomeZones.DEFAULT_TIMEOUT).isEqualTo(Duration.ofMillis(1500));
        AtomicLong seen = new AtomicLong();
        new PlayerHomeZones((ids, d) -> {
            seen.set(d.remainingMillis());
            return Map.of();
        }).homeZones(List.of(1L), Deadline.after(60_000));
        assertThat(seen.get()).isBetween(1000L, 1500L);
    }

    @Test
    void 单次超时必须为正() {
        assertThatThrownBy(() -> new PlayerHomeZones((ids, d) -> Map.of(), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PlayerHomeZones((ids, d) -> Map.of(), Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 单人查询_缺项与0都按0_无符号号与区原样() {
        List<List<Long>> asked = new ArrayList<>();
        HomeZones zones = new PlayerHomeZones((ids, d) -> {
            asked.add(ids);
            return Map.of(BIG, profile(BIG, -1), 2L, profile(2, 0));
        });
        assertThat(zones.homeZoneOf(BIG, Deadline.after(3500))).as("zone_id 是 uint32 位模式").isEqualTo(-1);
        assertThat(zones.homeZoneOf(2L, Deadline.after(3500))).as("zone_id = 0 → 未知").isZero();
        assertThat(zones.homeZoneOf(3L, Deadline.after(3500))).as("没有行 → 未知").isZero();
        assertThat(asked).containsExactly(List.of(BIG), List.of(2L), List.of(3L));
        HomeZones nullMap = (ids, d) -> null;
        assertThat(nullMap.homeZoneOf(1L, Deadline.after(3500))).as("实现返回 null 也按未知").isZero();
    }
}
