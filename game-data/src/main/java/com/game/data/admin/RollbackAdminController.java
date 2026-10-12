package com.game.data.admin;

import com.game.data.ops.OpsRequests;
import com.game.data.rollback.RollbackRequest;
import com.game.data.rollback.RollbackService;
import com.game.data.snapshot.ZoneSnapshotService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维：回档（data-ops-spec §4；对应 99 / 100 / 101 / 112）与整区维护前快照（§3.4）。都是作业：受理后回 202 {@code {jobId}}，
 * 进度与结果经 {@link OpsJobAdminController} 查；回档 {@code dryRun=true} 同步回 200（只读，不需要幂等键与写开关）。
 * 鉴权见 {@link AdminAuthFilter}；操作人一律取请求头，不在请求体里自报；{@code reason} 一律必填。
 */
@RestController
public class RollbackAdminController {

    public static final String PATH = RollbackRequest.PATH;
    public static final String ZONE_SNAPSHOTS_PATH = ZoneSnapshotService.PATH;

    private final RollbackService rollbacks;
    private final ZoneSnapshotService zoneSnapshots;

    public RollbackAdminController(RollbackService rollbacks, ZoneSnapshotService zoneSnapshots) {
        this.rollbacks = rollbacks;
        this.zoneSnapshots = zoneSnapshots;
    }

    @PostMapping(PATH)
    public ResponseEntity<Map<String, Object>> rollback(@RequestBody(required = false) RollbackRequest.Body body,
                                                        @RequestHeader(name = OpsRequests.IDEMPOTENCY_HEADER,
                                                                required = false) String key,
                                                        HttpServletRequest request) {
        String operator = AdminAuthFilter.operator(request.getHeader(AdminAuthFilter.OPERATOR_HEADER));
        Map<String, Object> out = rollbacks.handle(body, operator, key);
        boolean dryRun = Boolean.TRUE.equals(out.get("dryRun"));
        return ResponseEntity.status(dryRun ? HttpStatus.OK : HttpStatus.ACCEPTED).body(out);
    }

    @PostMapping(ZONE_SNAPSHOTS_PATH)
    public ResponseEntity<Map<String, Object>> zoneSnapshots(@RequestBody(required = false) ZoneSnapshotService.Body body,
                                                             @RequestHeader(name = OpsRequests.IDEMPOTENCY_HEADER,
                                                                     required = false) String key,
                                                             HttpServletRequest request) {
        String operator = AdminAuthFilter.operator(request.getHeader(AdminAuthFilter.OPERATOR_HEADER));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(zoneSnapshots.submit(body, operator, key));
    }
}
