package com.game.gateway.zone;

import com.game.gateway.store.ZoneManualStatus;

/**
 * 区服列表里的 {@code status}（枚举名原样下发，同 mmorpg ZoneDisplayStatus）：运维手工状态叠加健康探测——
 * 手工非 OPEN 以手工为准；手工 OPEN 而探测到一台 gate 都没有显示 MAINTENANCE；探测未知仍显示 OPEN。
 */
public enum ZoneStatus {
    OPEN,
    MAINTENANCE,
    CLOSED,
    PREVIEW;

    public static ZoneStatus display(ZoneManualStatus manual, ZoneHealthProbe.Health health) {
        return switch (manual) {
            case CLOSED -> CLOSED;
            case MAINTENANCE -> MAINTENANCE;
            case PREVIEW -> PREVIEW;
            case OPEN -> health == ZoneHealthProbe.Health.DOWN ? MAINTENANCE : OPEN;
        };
    }
}
