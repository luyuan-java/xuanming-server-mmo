package com.game.gateway.zone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ZoneCatalogTest {

    private static Zone zone(int id, ZoneStatus status) {
        return new Zone(id, "区" + id, status, false, null);
    }

    @Test
    void 按区服号查找_保持配置顺序() {
        ZoneCatalog catalog = new ZoneCatalog(List.of(zone(3, ZoneStatus.OPEN), zone(1, ZoneStatus.CLOSED)));

        assertThat(catalog.find(1)).map(Zone::status).contains(ZoneStatus.CLOSED);
        assertThat(catalog.find(2)).isEmpty();
        assertThat(catalog.find(0)).isEmpty();
        assertThat(catalog.all()).extracting(Zone::zoneId).containsExactly(3, 1);
    }

    @Test
    void 配置不合法时拒绝构造() {
        assertThatThrownBy(() -> new ZoneCatalog(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZoneCatalog(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ZoneCatalog(List.of(zone(0, ZoneStatus.OPEN))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("大于 0");
        assertThatThrownBy(() -> new ZoneCatalog(List.of(zone(1, ZoneStatus.OPEN), zone(1, ZoneStatus.CLOSED))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("重复");
        assertThatThrownBy(() -> new ZoneCatalog(List.of(new Zone(1, " ", ZoneStatus.OPEN, false, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("名字");
        assertThatThrownBy(() -> new ZoneCatalog(List.of(new Zone(1, "一区", null, false, null))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("状态");
        List<Zone> withNull = new ArrayList<>();
        withNull.add(null);
        assertThatThrownBy(() -> new ZoneCatalog(withNull)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 构造后与传入列表隔离且不可改() {
        List<Zone> source = new ArrayList<>(List.of(zone(1, ZoneStatus.OPEN)));
        ZoneCatalog catalog = new ZoneCatalog(source);
        source.add(zone(2, ZoneStatus.OPEN));

        assertThat(catalog.all()).hasSize(1);
        assertThatThrownBy(() -> catalog.all().add(zone(3, ZoneStatus.OPEN)))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
