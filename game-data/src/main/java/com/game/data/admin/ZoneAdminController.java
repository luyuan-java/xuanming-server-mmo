package com.game.data.admin;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.game.common.zone.ZoneIdJsonDeserializer;
import com.game.common.zone.ZoneIds;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.game.gateway.store.GatewayStore;
import com.game.gateway.store.ZoneManualStatus;
import com.game.gateway.store.ZoneRow;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 运维：区服目录（同 mmorpg AdminZoneController 的接口与语义；Java 版放在 game-data 的运维面上——管理端口只绑本机、
 * 令牌 + 操作人 + 审计，见 {@link AdminAuthFilter}——而不是对外的 gateway 端口）。game-gateway 的准入 / 区服列表约 1 s 内看到改动。
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

    /** 请求体（未给的列取缺省值：状态 0 OPEN、容量 45000、文案空、不推荐、排序 0）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ZoneBody(@JsonDeserialize(using = ZoneIdJsonDeserializer.class) Long zoneId, String name, Integer manualStatus, Integer capacity, String maintenanceMsg,
                           Long openTime, Boolean recommended, Integer sortOrder) {

        ZoneRow toRow(int zoneId) {
            return new ZoneRow(zoneId, name, manualStatus == null ? ZoneManualStatus.OPEN.code() : manualStatus,
                    capacity == null ? ZoneRow.DEFAULT_CAPACITY : capacity, maintenanceMsg == null ? "" : maintenanceMsg, openTime,
                    recommended != null && recommended, sortOrder == null ? 0 : sortOrder, 0, 0);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MaintenanceBody(String maintenanceMsg) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RoleCapacityBody(Integer roleCapacity) {
    }

    /** 应答（列名同表，snake_case）。 */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ZoneView(long zoneId, String name, int manualStatus, int capacity, String maintenanceMsg, Long openTime,
                           boolean recommended, int sortOrder, boolean createRoleClosed, int roleCapacity,
                           long createdAt, long updatedAt) {

        static ZoneView of(ZoneRow z) {
            return new ZoneView(Integer.toUnsignedLong(z.zoneId()), z.name(), z.manualStatus(), z.capacity(), z.maintenanceMsg(), z.openTime(),
                    z.recommended(), z.sortOrder(), z.createRoleClosed(), z.roleCapacity(), z.createdAt(), z.updatedAt());
        }
    }

    @GetMapping(PATH)
    public List<ZoneView> list() {
        return store.zones().stream().map(ZoneView::of).toList();
    }

    @GetMapping(PATH + "/{zoneId}")
    public ResponseEntity<ZoneView> get(@PathVariable("zoneId") long zoneId) {
        return okOrNotFound(store.zone(requiredZone(zoneId)));
    }

    @PostMapping(PATH)
    public ResponseEntity<?> create(@RequestBody ZoneBody body, HttpServletRequest request) {
        if (body == null || body.zoneId() == null || body.name() == null) {
            return problem(HttpStatus.BAD_REQUEST, "missing_required_field", "zone_id", "zone_id 与 name 必填");
        }
        if (body.zoneId() <= 0 || body.zoneId() > ZoneIds.MAX) {
            return problem(HttpStatus.BAD_REQUEST, "invalid_zone_id", "zone_id", "zone_id 须在 1–4294967295");
        }
        int bits = ZoneIds.requiredBits(body.zoneId());
        ZoneRow target = body.toRow(bits);
        Optional<ZoneRow> existing = store.zone(bits);
        if (existing.isPresent()) {
            ZoneRow current = existing.get();
            if (sameSettings(current, target)) {
                return withEtag(current);
            }
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "区服已存在且配置不同，拒绝覆写");
            pd.setProperty("reason_code", "zone_already_exists");
            pd.setProperty("field", "zone_id");
            pd.setProperty("existing", ZoneView.of(current));
            return ResponseEntity.status(HttpStatus.CONFLICT).eTag(etag(current)).body(pd);
        }
        ZoneRow stored = checked(() -> store.upsertZone(target));
        audit.warn("zone create zone_id={} status={} operator={}", Integer.toUnsignedLong(stored.zoneId()), stored.manualStatus(),
                operator(request));
        return withEtag(stored);
    }

    @PutMapping(PATH + "/{zoneId}")
    public ResponseEntity<?> update(@PathVariable("zoneId") long zoneId,
                                    @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                    @RequestBody(required = false) ZoneBody body,
                                    HttpServletRequest request) {
        int bits = requiredZone(zoneId);
        if (body == null) {
            return problem(HttpStatus.BAD_REQUEST, "missing_body", "body", "请求体必填");
        }
        if (body.zoneId() != null && body.zoneId() != zoneId) {
            return problem(HttpStatus.BAD_REQUEST, "zone_id_mismatch", "zone_id", "请求体 zone_id 与路径不一致");
        }
        if (body.name() == null || body.name().isBlank()) {
            return problem(HttpStatus.BAD_REQUEST, "missing_required_field", "name", "name 必填");
        }
        if (body.manualStatus() == null) {
            return problem(HttpStatus.BAD_REQUEST, "missing_required_field", "manual_status", "PUT 整行替换必须显式提供 manual_status");
        }
        if (body.capacity() == null) {
            return problem(HttpStatus.BAD_REQUEST, "missing_required_field", "capacity", "PUT 整行替换必须显式提供 capacity");
        }
        if (ZoneManualStatus.fromCode(body.manualStatus()).isEmpty()) {
            return problem(HttpStatus.BAD_REQUEST, "invalid_manual_status", "manual_status", "manual_status 只能是 0–3");
        }
        if (body.capacity() < 0) {
            return problem(HttpStatus.BAD_REQUEST, "invalid_capacity", "capacity", "capacity 不能为负");
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            return problem(HttpStatus.PRECONDITION_REQUIRED, "missing_if_match", HttpHeaders.IF_MATCH, "PUT 整行替换必须带 If-Match 版本头");
        }
        Optional<ZoneRow> currentOpt = store.zone(bits);
        if (currentOpt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String currentEtag = etag(currentOpt.get());
        if (!matchesEtag(ifMatch, currentEtag)) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.PRECONDITION_FAILED, "区服版本已变化，请重新读取后重试");
            pd.setProperty("reason_code", "etag_mismatch");
            pd.setProperty("field", HttpHeaders.IF_MATCH);
            pd.setProperty("current_etag", currentEtag);
            return ResponseEntity.status(HttpStatus.PRECONDITION_FAILED).eTag(currentEtag).body(pd);
        }
        Optional<ZoneRow> stored = checked(() -> store.updateZone(bits, body.toRow(bits)));
        audit.warn("zone update zone_id={} found={} operator={}", zoneId, stored.isPresent(), operator(request));
        return okOrNotFound(stored);
    }

    @DeleteMapping(PATH + "/{zoneId}")
    public ResponseEntity<Void> delete(@PathVariable("zoneId") long zoneId, HttpServletRequest request) {
        boolean deleted = store.deleteZone(requiredZone(zoneId));
        audit.warn("zone delete zone_id={} deleted={} operator={}", zoneId, deleted, operator(request));
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping(PATH + "/{zoneId}/maintenance")
    public ResponseEntity<ZoneView> maintenance(@PathVariable("zoneId") long zoneId,
                                                @RequestBody(required = false) MaintenanceBody body,
                                                HttpServletRequest request) {
        String msg = body == null ? null : body.maintenanceMsg();
        Optional<ZoneRow> stored = checked(() -> store.setZoneStatus(requiredZone(zoneId), ZoneManualStatus.MAINTENANCE, msg));
        audit.warn("zone maintenance zone_id={} found={} operator={}", zoneId, stored.isPresent(), operator(request));
        return okOrNotFound(stored);
    }

    @PostMapping(PATH + "/{zoneId}/open")
    public ResponseEntity<ZoneView> open(@PathVariable("zoneId") long zoneId, HttpServletRequest request) {
        Optional<ZoneRow> stored = store.setZoneStatus(requiredZone(zoneId), ZoneManualStatus.OPEN, "");
        audit.warn("zone open zone_id={} found={} operator={}", zoneId, stored.isPresent(), operator(request));
        return okOrNotFound(stored);
    }

    @PostMapping(PATH + "/{zoneId}/create-role/close")
    public ResponseEntity<ZoneView> closeCreateRole(@PathVariable("zoneId") long zoneId, HttpServletRequest request) {
        Optional<ZoneRow> stored = store.setCreateRoleClosed(requiredZone(zoneId), true);
        audit.warn("zone create_role close zone_id={} found={} operator={}", zoneId, stored.isPresent(), operator(request));
        return okOrNotFound(stored);
    }

    @PostMapping(PATH + "/{zoneId}/create-role/open")
    public ResponseEntity<ZoneView> openCreateRole(@PathVariable("zoneId") long zoneId, HttpServletRequest request) {
        Optional<ZoneRow> stored = store.setCreateRoleClosed(requiredZone(zoneId), false);
        audit.warn("zone create_role open zone_id={} found={} operator={}", zoneId, stored.isPresent(), operator(request));
        return okOrNotFound(stored);
    }

    @PutMapping(PATH + "/{zoneId}/role-capacity")
    public ResponseEntity<ZoneView> setRoleCapacity(@PathVariable("zoneId") long zoneId,
                                                    @RequestBody RoleCapacityBody body,
                                                    HttpServletRequest request) {
        if (body == null || body.roleCapacity() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role_capacity 必填");
        }
        Optional<ZoneRow> stored = checked(() -> store.setRoleCapacity(requiredZone(zoneId), body.roleCapacity()));
        audit.warn("zone role_capacity zone_id={} role_capacity={} found={} operator={}",
                zoneId, body.roleCapacity(), stored.isPresent(), operator(request));
        return okOrNotFound(stored);
    }

    private static ResponseEntity<ZoneView> okOrNotFound(Optional<ZoneRow> zone) {
        return zone.map(ZoneAdminController::withEtag).orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<ZoneView> withEtag(ZoneRow row) {
        return ResponseEntity.ok().eTag(etag(row)).body(ZoneView.of(row));
    }

    static String etag(ZoneRow row) {
        return "\"v" + row.updatedAt() + "\"";
    }

    private static boolean matchesEtag(String ifMatch, String currentEtag) {
        String trimmed = ifMatch.trim();
        if ("*".equals(trimmed)) {
            return true;
        }
        for (String token : trimmed.split(",")) {
            String candidate = token.trim();
            if (candidate.startsWith("W/")) {
                candidate = candidate.substring(2).trim();
            }
            if (currentEtag.equals(candidate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameSettings(ZoneRow a, ZoneRow b) {
        return a.zoneId() == b.zoneId()
                && Objects.equals(a.name(), b.name())
                && a.manualStatus() == b.manualStatus()
                && a.capacity() == b.capacity()
                && Objects.equals(a.maintenanceMsg(), b.maintenanceMsg())
                && Objects.equals(a.openTime(), b.openTime())
                && a.recommended() == b.recommended()
                && a.sortOrder() == b.sortOrder();
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String reasonCode, String field, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setProperty("reason_code", reasonCode);
        pd.setProperty("field", field);
        return ResponseEntity.status(status).body(pd);
    }

    /** 存储层的参数校验失败回 400（带原因）。 */
    static int requiredZone(long value) {
        return checked(() -> ZoneIds.requiredBits(value));
    }

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
