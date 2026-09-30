package com.game.gateway.zone;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 区服目录：配置里的区服列表，构造时一次性校验，之后只读、线程安全。
 *
 * <p>列表顺序即区服列表的展示顺序。配置不合法（空列表、区服号 &lt;= 0 或重复、缺名字 / 状态）直接抛
 * {@link IllegalArgumentException}，让进程启动失败，而不是带着残缺的区服表对外服务。
 */
public final class ZoneCatalog {

    private final List<Zone> zones;
    private final Map<Integer, Zone> byId;

    public ZoneCatalog(List<Zone> configured) {
        if (configured == null || configured.isEmpty()) {
            throw new IllegalArgumentException("未配置任何区服（xm.gateway.zones）");
        }
        Map<Integer, Zone> index = new LinkedHashMap<>();
        for (Zone zone : configured) {
            validate(zone);
            if (index.putIfAbsent(zone.zoneId(), zone) != null) {
                throw new IllegalArgumentException("区服号重复：zone_id=" + zone.zoneId());
            }
        }
        this.zones = Collections.unmodifiableList(new ArrayList<>(index.values()));
        this.byId = Map.copyOf(index);
    }

    private static void validate(Zone zone) {
        if (zone == null) {
            throw new IllegalArgumentException("区服配置里有空条目");
        }
        if (zone.zoneId() <= 0) {
            throw new IllegalArgumentException("区服号必须大于 0：zone_id=" + zone.zoneId());
        }
        if (zone.name() == null || zone.name().isBlank()) {
            throw new IllegalArgumentException("区服缺少名字：zone_id=" + zone.zoneId());
        }
        if (zone.status() == null) {
            throw new IllegalArgumentException("区服缺少状态（OPEN / MAINTENANCE / CLOSED）：zone_id=" + zone.zoneId());
        }
    }

    public Optional<Zone> find(int zoneId) {
        return Optional.ofNullable(byId.get(zoneId));
    }

    /** 按配置顺序的全部区服（不可变）。 */
    public List<Zone> all() {
        return zones;
    }
}
