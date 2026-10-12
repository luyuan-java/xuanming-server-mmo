package com.game.gateway.store;

import java.util.Optional;

/** 区服的运维手工状态（{@code zone_config.manual_status}，数值同 mmorpg ManualZoneStatus）。 */
public enum ZoneManualStatus {
    /** 开放：允许分配 gate。 */
    OPEN(0),
    /** 维护：拒绝分配（assign-gate 503 {@code zone_maintenance}）。 */
    MAINTENANCE(1),
    /** 关闭：拒绝分配（503 {@code zone_closed}）。 */
    CLOSED(2),
    /** 未开放（预告，带开放时刻）：拒绝分配（503 {@code zone_not_open}）。 */
    PREVIEW(3);

    private final int code;

    ZoneManualStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** 库里的数值 → 状态；不认识的数值为空（写入时拒绝，读到时调用方按不可进处理）。 */
    public static Optional<ZoneManualStatus> fromCode(int code) {
        for (ZoneManualStatus status : values()) {
            if (status.code == code) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    /**
     * 读路径用：不认识的数值按 {@link #CLOSED}（fail-closed——基线 fromCode 对未知码回 OPEN，库里写错状态码等于开服）。
     */
    public static ZoneManualStatus fromCodeOrClosed(int code) {
        return fromCode(code).orElse(CLOSED);
    }
}
