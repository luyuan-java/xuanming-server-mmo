package com.game.gateway.serverlist;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.game.gateway.zone.ZoneHealthProbe.LoadLevel;
import com.game.gateway.zone.ZoneStatus;

/**
 * 区服列表里的一项（客户端契约，形状与 mmorpg Java gateway 的 {@code ZoneInfoDto} 一致，空字段不输出）。
 *
 * @param loadLevel      负载档：只在显示 OPEN 且健康探测不是 UNKNOWN 时下发
 * @param maintenanceMsg 只在显示 MAINTENANCE / CLOSED 时下发
 * @param openTime       PREVIEW 区的开放时刻（Unix 秒），只在手工状态 PREVIEW 且设了时刻时下发
 * @param isNew          创建 7 天内为 true。必须显式命名为 {@code is_new}：Jackson 会把 {@code isNew()} 剥成 {@code new}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ZoneInfo(
        int zoneId,
        String name,
        ZoneStatus status,
        LoadLevel loadLevel,
        String maintenanceMsg,
        Long openTime,
        @JsonProperty("is_new") boolean isNew,
        boolean recommended) {
}
