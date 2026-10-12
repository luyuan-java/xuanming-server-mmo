package com.game.gateway.serverlist;

import com.game.gateway.store.ZoneManualStatus;
import com.game.gateway.store.ZoneRow;
import com.game.gateway.zone.ZoneDirectory;
import com.game.gateway.zone.ZoneHealthProbe;
import com.game.gateway.zone.ZoneHealthProbe.Health;
import com.game.gateway.zone.ZoneHealthProbe.LoadLevel;
import com.game.gateway.zone.ZoneStatus;
import java.time.Clock;
import java.time.Duration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/server-list}：区服列表（同 mmorpg ServerListService）。按 sort_order 升序；显示状态 = 手工状态叠加健康探测
 * （{@link ZoneStatus#display}）；负载档、维护文案、开放时刻、新区按 {@link ZoneInfo} 的规则下发。
 * robot 只在 {@code zone_id: 0} 时调用，只读 {@code zones[].zone_id} 与 {@code zones[].recommended}。
 *
 * <p>区服表读失败时回 500（Spring 默认错误应答，基线同为未处理异常）。
 */
@RestController
@RequestMapping("/api")
public class ServerListController {

    static final Duration NEW_ZONE_WINDOW = Duration.ofDays(7);

    private final ZoneDirectory zones;
    private final ZoneHealthProbe probe;
    private final Clock clock;

    public ServerListController(ZoneDirectory zones, ZoneHealthProbe probe, Clock clock) {
        this.zones = zones;
        this.probe = probe;
        this.clock = clock;
    }

    @GetMapping("/server-list")
    public ServerListResponse serverList() {
        long now = clock.millis();
        return new ServerListResponse(zones.zones().stream().map(zone -> info(zone, now)).toList());
    }

    private ZoneInfo info(ZoneRow zone, long nowMs) {
        ZoneManualStatus manual = zone.status();
        Health health = probe.health(zone.zoneId());
        ZoneStatus status = ZoneStatus.display(manual, health);
        LoadLevel load = status == ZoneStatus.OPEN && health != Health.UNKNOWN
                ? probe.loadLevel(zone.zoneId()).orElse(null) : null;
        String msg = status == ZoneStatus.MAINTENANCE || status == ZoneStatus.CLOSED ? zone.maintenanceMsg() : null;
        Long openTime = manual == ZoneManualStatus.PREVIEW ? zone.openTime() : null;
        boolean isNew = zone.createdAt() > nowMs - NEW_ZONE_WINDOW.toMillis();
        return new ZoneInfo(zone.zoneId(), zone.name(), status, load, msg, openTime, isNew, zone.recommended());
    }
}
