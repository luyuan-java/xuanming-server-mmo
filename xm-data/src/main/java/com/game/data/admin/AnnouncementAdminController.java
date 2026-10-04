package com.game.data.admin;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.game.gateway.store.AnnouncementRow;
import com.game.gateway.store.GatewayStore;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维：登录公告（同 mmorpg AdminAnnouncementController）。{@code GET /admin/announcements} 全部；
 * {@code POST /admin/announcements {title, content?, type?, start_time?, end_time?}} 新建（总是插入——请求里的 id 忽略，
 * 基线带 id 时会覆盖已有公告）；{@code DELETE /admin/announcements/{id}} 恒 204。时刻 Unix 秒；客户端经 xm-gateway
 * {@code /api/announcement} 只看到生效中的。鉴权与审计见 {@link AdminAuthFilter}。
 */
@RestController
public class AnnouncementAdminController {

    public static final String PATH = "/admin/announcements";

    private static final Logger audit = LoggerFactory.getLogger(AdminAuthFilter.AUDIT_LOGGER);

    private final GatewayStore store;

    public AnnouncementAdminController(GatewayStore store) {
        this.store = store;
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnnouncementBody(String title, String content, String type, Long startTime, Long endTime) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record AnnouncementView(long id, String title, String content, String type, Long startTime, Long endTime,
                                   long createdAt) {

        static AnnouncementView of(AnnouncementRow a) {
            return new AnnouncementView(a.id(), a.title(), a.content(), a.type(), a.startTime(), a.endTime(),
                    a.createdAt());
        }
    }

    @GetMapping(PATH)
    public List<AnnouncementView> list() {
        return store.announcements().stream().map(AnnouncementView::of).toList();
    }

    @PostMapping(PATH)
    public AnnouncementView create(@RequestBody AnnouncementBody body, HttpServletRequest request) {
        AnnouncementRow stored = ZoneAdminController.checked(() -> store.createAnnouncement(body.title(), body.content(),
                body.type(), body.startTime(), body.endTime()));
        audit.warn("announcement create id={} type={} operator={}", stored.id(), stored.type(),
                ZoneAdminController.operator(request));
        return AnnouncementView.of(stored);
    }

    @DeleteMapping(PATH + "/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") long id, HttpServletRequest request) {
        store.deleteAnnouncement(id);
        audit.warn("announcement delete id={} operator={}", id, ZoneAdminController.operator(request));
        return ResponseEntity.noContent().build();
    }
}
