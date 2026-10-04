package com.game.gateway.announcement;

import com.game.gateway.store.AnnouncementRow;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.zone.TtlCache;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/announcement}：登录公告（同 mmorpg AnnouncementController）——{@code {"items":[{id,title,content,type,
 * start_time?,end_time?}]}}，只含此刻生效中的，新的在前；时刻是 Unix 秒，没有不输出。运维经 xm-data 增删。
 *
 * <p>生效中的公告缓存 {@link #TTL}（基线每次请求查库）：这是对外的公开接口，不缓存的话一波请求就能占满 gateway 的数据库连接池，
 * 连带区服准入一起失败；起止时刻因此最多晚 1 s 生效。读失败回 500（客户端选服界面拉取失败只打警告）。
 */
@RestController
@RequestMapping("/api")
public class AnnouncementController {

    static final Duration TTL = Duration.ofSeconds(1);

    private final TtlCache<List<Map<String, Object>>> active;

    @Autowired
    public AnnouncementController(GatewayStore store, Clock clock) {
        this(store, clock, System::nanoTime);
    }

    AnnouncementController(GatewayStore store, Clock clock, LongSupplier nanoClock) {
        this.active = new TtlCache<>(() -> store.activeAnnouncements(clock.millis() / 1000).stream()
                .map(AnnouncementController::item).toList(), nanoClock, TTL);
    }

    @GetMapping("/announcement")
    public Map<String, Object> announcements() {
        return Map.of("items", active.get());
    }

    private static Map<String, Object> item(AnnouncementRow a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id());
        m.put("title", a.title());
        m.put("content", a.content());
        m.put("type", a.type());
        if (a.startTime() != null) {
            m.put("start_time", a.startTime());
        }
        if (a.endTime() != null) {
            m.put("end_time", a.endTime());
        }
        return m;
    }
}
