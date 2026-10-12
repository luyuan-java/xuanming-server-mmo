package com.game.gateway.store;

/**
 * {@code zone_config} 的一行。
 *
 * @param zoneId         区服号（&gt; 0）
 * @param name           展示名（1–64 字符）
 * @param manualStatus   运维手工状态的数值（{@link ZoneManualStatus}）
 * @param capacity       负载档的分母
 * @param maintenanceMsg 维护 / 关闭时下发的文案（不为 null）
 * @param openTime       PREVIEW 区的开放时刻（Unix 秒），没有为 null
 * @param recommended    推荐区
 * @param sortOrder      区服列表按它升序
 * @param createdAt      创建时刻（Unix 毫秒）
 * @param updatedAt      最后修改时刻（Unix 毫秒）
 */
public record ZoneRow(int zoneId, String name, int manualStatus, int capacity, String maintenanceMsg, Long openTime,
                      boolean recommended, int sortOrder, long createdAt, long updatedAt) {

    public ZoneManualStatus status() {
        return ZoneManualStatus.fromCodeOrClosed(manualStatus);
    }
}
