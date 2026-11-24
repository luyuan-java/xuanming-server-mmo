package com.game.gateway.store;

/**
 * {@code zone_config} 的一行。
 *
 * @param zoneId         区服号（uint32 位模式；0 非法，上半区按负 int 保存）
 * @param name           展示名（1–64 字符）
 * @param manualStatus   运维手工状态的数值（{@link ZoneManualStatus}）
 * @param capacity       负载档的分母
 * @param maintenanceMsg 维护 / 关闭时下发的文案（不为 null）
 * @param openTime       PREVIEW 区的开放时刻（Unix 秒），没有为 null
 * @param recommended      推荐区
 * @param sortOrder        区服列表按它升序
 * @param createRoleClosed 是否暂停创建新角色（只经独立开关或角色上限守卫修改）
 * @param roleCapacity     本区注册角色软上限；0 = 不设上限
 * @param createdAt        创建时刻（Unix 毫秒）
 * @param updatedAt        最后修改时刻（Unix 毫秒）
 */
public record ZoneRow(int zoneId, String name, int manualStatus, int capacity, String maintenanceMsg, Long openTime,
                      boolean recommended, int sortOrder, boolean createRoleClosed, int roleCapacity,
                      long createdAt, long updatedAt) {

    /** 单 zone 缺省配置容量（对齐权威主仓 U-2：45000）。 */
    public static final int DEFAULT_CAPACITY = 45_000;

    /** 兼容既有 10 参构造（默认开启创角、不设角色上限）。 */
    public ZoneRow(int zoneId, String name, int manualStatus, int capacity, String maintenanceMsg, Long openTime,
                   boolean recommended, int sortOrder, long createdAt, long updatedAt) {
        this(zoneId, name, manualStatus, capacity, maintenanceMsg, openTime, recommended, sortOrder, false, 0,
                createdAt, updatedAt);
    }

    public ZoneManualStatus status() {
        return ZoneManualStatus.fromCodeOrClosed(manualStatus);
    }
}
