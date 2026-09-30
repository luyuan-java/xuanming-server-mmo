package com.game.gateway.zone;

/**
 * 区服的运维手工状态，同时也是 {@code /api/server-list} 里 {@code status} 字段的取值（枚举名原样下发）。
 *
 * <p>mmorpg 另有 {@code PREVIEW}（未开放、带开服时间），本批不做。
 */
public enum ZoneStatus {
    /** 开放：允许分配 gate。 */
    OPEN,
    /** 维护：拒绝分配，assign-gate 回 503 {@code zone_maintenance}。 */
    MAINTENANCE,
    /** 关闭：拒绝分配，assign-gate 回 503 {@code zone_closed}。 */
    CLOSED
}
