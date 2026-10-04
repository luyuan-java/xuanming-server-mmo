package com.game.data.admin;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.ZoneManualStatus;
import com.game.gateway.store.ZoneRow;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运维：区服目录（同 mmorpg AdminZoneController 的接口与语义；Java 版放在 xm-data 的运维面上——管理端口只绑本机、
 * 令牌 + 操作人 + 审计，见 {@link AdminAuthFilter}——而不是对外的 gateway 端口）。xm-gateway 的准入 / 区服列表约 1 s 内看到改动。
 * JSON 键 snake_case（同基线）；时刻是整数：{@code open_time} Unix 秒，{@code created_at / updated_at} Unix 毫秒。
 * <ul>
 *   <li>{@code GET /admin/zones}、{@code GET /admin/zones/{id}}（404）；</li>
 *   <li>{@code POST /admin/zones}：创建，已存在则覆盖全部业务列（保留 created_at）；缺 zone_id / name → 400；</li>
 *   <li>{@code PUT /admin/zones/{id}}：覆盖已存在区服的业务列，不存在 404、绝不插入；缺 name → 400；</li>
 *   <li>{@code DELETE /admin/zones/{id}}：204 / 404；</li>
 *   <li>{@code POST /admin/zones/{id}/maintenance {maintenance_msg?}}：置维护，没给文案保留原文案；
 *       {@code POST /admin/zones/{id}/open}：置开放并清空文案；都 404 若不存在。</li>
 * </ul>
 * 参数不合法（状态值不在 0–3、名字 / 文案超长）回 400——基线对未知状态码按 OPEN 读，库里写错等于开服。
 */
@RestController
public class ZoneAdminController {

    public static final String PATH = "/admin/zones";

    private static final Logger audit = LoggerFactory.getLogger(AdminAuthFilter.AUDIT_LOGGER);

    private final GatewayStore store;

    public ZoneAdminController(GatewayStore store) {
        this.store = store;
    }

    /** 请求体（未给的列取缺省值：状态 0 OPEN、容量 5000、文案空、不推荐、排序 0）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ZoneBody(Long zoneId, String name, Integer manualStatus, Integer capacity, String maintenanceMsg,
                           Long openTime, Boolean recommended, Integer sortOrder) {

        ZoneRow toRow(int zoneId) {
            return new ZoneRow(zoneId, name, manualStatus == null ? ZoneManualStatus.OPEN.code() : manualStatus,
                    capacity == null ? 5000 : capacity, maintenanceMsg == null ? "" : maintenanceMsg, openTime,
                    recommended != null && recommended, sortOrder == null ? 0 : sortOrder, 0, 0);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MaintenanceBody(String maintenanceMsg) {
    }

    /** 应答（列名同表，snake_case）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ZoneView(int zoneId, String name, int manualStatus, int capacity, String maintenanceMsg, Long openTime,
                           boolean recommended, int sortOrder, long createdAt, long updatedAt) {

        static ZoneView of(ZoneRow z) {
            return new ZoneView(z.zoneId(), z.name(), z.manualStatus(), z.capacity(), z.maintenanceMsg(), z.openTime(),
                    z.recommended(), z.sortOrder(), z.createdAt(), z.updatedAt());
        }
    }

    @GetMapping(PATH)
    public List<ZoneView> list() {
        return store.zones().stream().map(ZoneView::of).toList();
    }

    @GetMapping(PATH + "/{zoneId}")
    public ResponseEntity<ZoneView> get(@PathVariable("zoneId") int zoneId) {
        return okOrNotFound(store.zone(zoneId));
    }

    @PostMapping(PATH)
    public ZoneView create(@RequestBody ZoneBody body, HttpServletRequest request) {
        if (body.zoneId() == null || body.name() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zone_id 与 name 必填");
        }
        if (body.zoneId() <= 0 || body.zoneId() > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zone_id 须在 1–2147483647");
        }
        ZoneRow stored = checked(() -> store.upsertZone(body.toRow(body.zoneId().intValue())));
        audit.warn("zone upsert zone_id={} status={} operator={}", stored.zoneId(), stored.manualStatus(),
                operator(request));
        return ZoneView.of(stored);
    }

    @PutMapping(PATH + "/{zoneId}")
    public ResponseEntity<ZoneView> update(@PathVariable("zoneId") int zoneId, @RequestBody ZoneBody body,
                                           HttpServletRequest request) {
        if (body.name() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name 必填");
        }
        Optional<ZoneRow> stored = checked(() -> store.updateZone(zoneId, body.toRow(zoneId)));
        audit.warn("zone update zone_id={} found={} operator={}", zoneId, stored.isPresent(), operator(request));
        return okOrNotFound(stored);
    }

    @DeleteMapping(PATH + "/{zoneId}")
    public ResponseEntity<Void> delete(@PathVariable("zoneId") int zoneId, HttpServletRequest request) {
        boolean deleted = store.deleteZone(zoneId);
        audit.warn("zone delete zone_id={} deleted={} operator={}", zoneId, deleted, operator(request));
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping(PATH + "/{zoneId}/maintenance")
    public ResponseEntity<ZoneView> maintenance(@PathVariable("zoneId") int zoneId,
                                                @RequestBody(required = false) MaintenanceBody body,
                                                HttpServletRequest request) {
        String msg = body == null ? null : body.maintenanceMsg();
        Optional<ZoneRow> stored = checked(() -> store.setZoneStatus(zoneId, ZoneManualStatus.MAINTENANCE, msg));
        audit.warn("zone maintenance zone_id={} found={} operator={}", zoneId, stored.isPresent(), operator(request));
        return okOrNotFound(stored);
    }

    @PostMapping(PATH + "/{zoneId}/open")
    public ResponseEntity<ZoneView> open(@PathVariable("zoneId") int zoneId, HttpServletRequest request) {
        Optional<ZoneRow> stored = store.setZoneStatus(zoneId, ZoneManualStatus.OPEN, "");
        audit.warn("zone open zone_id={} found={} operator={}", zoneId, stored.isPresent(), operator(request));
        return okOrNotFound(stored);
    }

    private static ResponseEntity<ZoneView> okOrNotFound(Optional<ZoneRow> zone) {
        return zone.map(z -> ResponseEntity.ok(ZoneView.of(z))).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 存储层的参数校验失败回 400（带原因）。 */
    static <T> T checked(Supplier<T> call) {
        try {
            return call.get();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        }
    }

    static String operator(HttpServletRequest request) {
        return AdminAuthFilter.operator(request.getHeader(AdminAuthFilter.OPERATOR_HEADER));
    }
}
