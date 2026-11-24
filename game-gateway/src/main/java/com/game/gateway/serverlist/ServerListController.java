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
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/server-list}：区服列表（同 mmorpg ServerListService）。按 sort_order 升序；显示状态 = 手工状态叠加健康探测
 * （{@link ZoneStatus#display}）；{@code recommended} 由服务端计算（只保留 OPEN ∧ 未暂停创角 ∧ 非 FULL 的运维推荐区，
 * 全部不满足时自动补推负载率最低的 OPEN ∧ 未暂停创角区，并列按 sort_order 靠前，负载全未知时不补推）。
 */
@RestController
@RequestMapping("/api")
public class ServerListController {

    static final Duration NEW_ZONE_WINDOW = Duration.ofDays(7);

    /** 区服列表同源的开放区与可创角区统计（供无区可建角告警指标使用）。 */
    public record CapacityCounts(double openZones, double acceptingNewPlayerZones) {
        static final CapacityCounts UNAVAILABLE = new CapacityCounts(Double.NaN, Double.NaN);
    }

    private record Entry(ZoneRow zone, ZoneStatus status, LoadLevel load, OptionalDouble loadRatio,
                         String msg, Long openTime, boolean isNew, boolean recommended) {
    }

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
        List<ZoneRow> raw = zones.zones();
        List<Entry> entries = new ArrayList<>(raw.size());
        boolean anyRecommended = false;
        for (ZoneRow zone : raw) {
            ZoneManualStatus manual = zone.status();
            Health health = probe.health(zone.zoneId());
            ZoneStatus status = ZoneStatus.display(manual, health);
            LoadLevel load = status == ZoneStatus.OPEN && health != Health.UNKNOWN
                    ? probe.loadLevel(zone.zoneId()).orElse(null) : null;
            OptionalDouble ratio = status == ZoneStatus.OPEN && health != Health.UNKNOWN
                    ? probe.loadRatio(zone.zoneId()) : OptionalDouble.empty();
            String msg = status == ZoneStatus.MAINTENANCE || status == ZoneStatus.CLOSED ? zone.maintenanceMsg() : null;
            Long openTime = manual == ZoneManualStatus.PREVIEW ? zone.openTime() : null;
            boolean isNew = zone.createdAt() > now - NEW_ZONE_WINDOW.toMillis();
            boolean rec = zone.recommended() && status == ZoneStatus.OPEN
                    && !zone.createRoleClosed() && load != LoadLevel.FULL;
            if (rec) {
                anyRecommended = true;
            }
            entries.add(new Entry(zone, status, load, ratio, msg, openTime, isNew, rec));
        }

        int fallbackIdx = -1;
        if (!anyRecommended) {
            double bestRatio = Double.POSITIVE_INFINITY;
            for (int i = 0; i < entries.size(); i++) {
                Entry e = entries.get(i);
                if (e.status() == ZoneStatus.OPEN && !e.zone().createRoleClosed() && e.loadRatio().isPresent()) {
                    double r = e.loadRatio().getAsDouble();
                    if (r < bestRatio) {
                        bestRatio = r;
                        fallbackIdx = i;
                    }
                }
            }
        }

        List<ZoneInfo> out = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            boolean rec = e.recommended() || i == fallbackIdx;
            out.add(new ZoneInfo(Integer.toUnsignedLong(e.zone().zoneId()), e.zone().name(), e.status(),
                    e.load(), e.msg(), e.openTime(), e.isNew(), rec, e.zone().createRoleClosed()));
        }
        return new ServerListResponse(out);
    }

    /** 与区服列表同源的开放区数 / 可创角区数；区服表读不到时返回 NaN（不误触发告警）。 */
    public CapacityCounts capacityCounts() {
        return capacityCounts(zones, probe);
    }

    /** 与区服列表同源的开放区数 / 可创角区数静态入口，供配置类注册指标且不耦合 MVC 控制器。 */
    public static CapacityCounts capacityCounts(ZoneDirectory zones, ZoneHealthProbe probe) {
        try {
            int open = 0;
            int accepting = 0;
            for (ZoneRow zone : zones.zones()) {
                ZoneStatus status = ZoneStatus.display(zone.status(), probe.health(zone.zoneId()));
                if (status == ZoneStatus.OPEN) {
                    open++;
                    if (!zone.createRoleClosed()) {
                        accepting++;
                    }
                }
            }
            return new CapacityCounts(open, accepting);
        } catch (RuntimeException e) {
            return CapacityCounts.UNAVAILABLE;
        }
    }
}
