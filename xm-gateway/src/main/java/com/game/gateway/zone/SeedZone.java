package com.game.gateway.zone;

import com.game.gateway.store.ZoneManualStatus;
import com.game.gateway.store.ZoneRow;

/**
 * 启动时播种的区服（{@code xm.gateway.seed-zones[*]}）：库里没有才插入，已有的一律不动（之后由 xm-data 运维接口管理）。
 * 同基线 schema.sql 播种默认区。
 *
 * @param zoneId         区服号（&gt; 0）
 * @param name           展示名
 * @param status         手工状态，必填（漏配时启动失败，而不是悄悄开服）
 * @param recommended    推荐区
 * @param capacity       负载档分母，缺省 5000
 * @param sortOrder      区服列表顺序，缺省 0
 * @param maintenanceMsg 维护文案，缺省空
 * @param openTime       PREVIEW 区的开放时刻（Unix 秒），缺省无
 */
public record SeedZone(int zoneId, String name, ZoneManualStatus status, boolean recommended, Integer capacity,
                       Integer sortOrder, String maintenanceMsg, Long openTime) {

    public SeedZone {
        if (zoneId <= 0) {
            throw new IllegalArgumentException("xm.gateway.seed-zones：区服号必须大于 0：zone_id=" + zoneId);
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("xm.gateway.seed-zones：区服缺少名字：zone_id=" + zoneId);
        }
        if (status == null) {
            throw new IllegalArgumentException("xm.gateway.seed-zones：区服缺少状态（OPEN / MAINTENANCE / CLOSED / PREVIEW）：zone_id="
                    + zoneId);
        }
        capacity = capacity == null ? 5000 : capacity;
        sortOrder = sortOrder == null ? 0 : sortOrder;
        maintenanceMsg = maintenanceMsg == null ? "" : maintenanceMsg;
    }

    public ZoneRow toRow() {
        return new ZoneRow(zoneId, name, status.code(), capacity, maintenanceMsg, openTime, recommended, sortOrder, 0, 0);
    }
}
