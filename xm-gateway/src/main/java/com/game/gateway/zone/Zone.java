package com.game.gateway.zone;

/**
 * 一个区服的静态配置（来自 {@code xm.gateway.zones[*]}）。
 *
 * @param zoneId         区服号，必须 &gt; 0 且全局唯一
 * @param name           展示名，不能为空
 * @param status         运维手工状态，必填（不给默认值：漏配时启动失败，而不是悄悄开服）
 * @param recommended    是否推荐（客户端 / robot 自动选区时取第一个推荐区）
 * @param maintenanceMsg 维护公告；只在 {@code MAINTENANCE} / {@code CLOSED} 时随区服列表下发，可为空
 */
public record Zone(int zoneId, String name, ZoneStatus status, boolean recommended, String maintenanceMsg) {
}
