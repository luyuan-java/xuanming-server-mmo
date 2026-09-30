package com.game.gateway.serverlist;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.game.gateway.zone.Zone;
import com.game.gateway.zone.ZoneStatus;

/**
 * 区服列表里的一项（客户端契约，形状与 mmorpg Java gateway 的 {@code ZoneInfoDto} 一致，空字段不输出）。
 *
 * <p>本批不输出 {@code load_level}（mmorpg 来自健康探测）与 {@code open_time}（只属于 PREVIEW 状态）；
 * {@code is_new} 恒为 false（本版区服没有创建时间）。
 *
 * @param isNew 必须显式命名为 {@code is_new}：Jackson 会把 {@code isNew()} 剥成 {@code new}，而客户端字段只能叫 is_new
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ZoneInfo(
        int zoneId,
        String name,
        ZoneStatus status,
        String maintenanceMsg,
        @JsonProperty("is_new") boolean isNew,
        boolean recommended) {

    /** 维护公告只在不可进（维护 / 关闭）时下发，与 mmorpg 一致。 */
    static ZoneInfo of(Zone zone) {
        boolean unavailable = zone.status() == ZoneStatus.MAINTENANCE || zone.status() == ZoneStatus.CLOSED;
        String msg = unavailable && zone.maintenanceMsg() != null && !zone.maintenanceMsg().isBlank()
                ? zone.maintenanceMsg()
                : null;
        return new ZoneInfo(zone.zoneId(), zone.name(), zone.status(), msg, false, zone.recommended());
    }
}
