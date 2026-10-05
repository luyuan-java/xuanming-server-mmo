package com.game.data.admin;

import com.game.data.ops.OpsException;
import com.game.data.ops.OpsRequests;
import com.game.data.snapshot.SnapshotAdminService;
import com.game.data.snapshot.SnapshotAdminService.CreateRequest;
import com.game.data.snapshot.SnapshotCauses;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维快照（data-ops-spec §3.4、§3.5）：手工快照（96 / 103 / 105 的 Java 对应；基线的「事件快照」并入 GM_MANUAL，事件写进 note）
 * 与单份快照详情（97 / 115）。列表在 {@link AuditQueryController}。鉴权见 {@link AdminAuthFilter}；操作人一律取请求头，不在请求体里自报。
 */
@RestController
public class SnapshotAdminController {

    public static final String DETAIL_PATH = AuditQueryController.PLAYER_SNAPSHOTS_PATH + "/{snapshotId}";

    private final SnapshotAdminService service;

    public SnapshotAdminController(SnapshotAdminService service) {
        this.service = service;
    }

    /** 请求体：{@code {"player":"<u64>","cause":"GM_MANUAL|PRE_MAINTENANCE","note":"...","reason":"..."}}；cause 缺省 GM_MANUAL。 */
    public record CreateBody(String player, String cause, String note, String reason) {
    }

    /**
     * 拍一份手工快照（同步，不夺权、不踢人）。必带 {@code Idempotency-Key} 与 reason。应答见 {@link SnapshotAdminService#create}：
     * {@code timeMs} 是内容时刻（已落盘状态的写入时刻），{@code online=true} 时内容可能落后内存至多一个存盘周期。
     */
    @PostMapping(AuditQueryController.PLAYER_SNAPSHOTS_PATH)
    public Map<String, Object> create(@RequestBody(required = false) CreateBody body,
                                      @RequestHeader(name = OpsRequests.IDEMPOTENCY_HEADER, required = false) String key,
                                      HttpServletRequest request) {
        String idempotencyKey = OpsRequests.idempotencyKey(key);
        if (body == null) {
            throw OpsException.badRequest("缺少请求体");
        }
        long player = OpsRequests.u64("player", body.player());
        int cause = body.cause() == null ? SnapshotCauses.GM_MANUAL : SnapshotCauses.parse(body.cause());
        CreateRequest req = new CreateRequest(player, cause, OpsRequests.text("note", body.note()),
                OpsRequests.reason(body.reason()));
        String operator = AdminAuthFilter.operator(request.getHeader(AdminAuthFilter.OPERATOR_HEADER));
        return service.create(req, operator, idempotencyKey).body();
    }

    /** 单份快照：元数据 + 操作人 / 备注；{@code includeState=true} 时附 JSON 化的 player_state（含未知字段的原样字节）。 */
    @GetMapping(DETAIL_PATH)
    public Map<String, Object> detail(@PathVariable("snapshotId") String snapshotId,
                                      @RequestParam(name = "includeState", defaultValue = "false") boolean includeState) {
        return service.detail(OpsRequests.u64("snapshotId", snapshotId), includeState);
    }
}
