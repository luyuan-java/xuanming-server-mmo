package com.game.data.ops;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * 运维面的业务失败：HTTP 状态 + 应答体里的 {@code code} 字符串（data-ops-spec §7.7：取基线常量名去掉前缀，便于两版运维对照；
 * 不是客户端契约）。由 {@code com.game.data.admin.OpsErrorAdvice} 统一转成 {@code {"code": .., "message": .., ...details}}。
 */
public final class OpsException extends RuntimeException {

    public static final String INVALID_REQUEST = "invalid_request";
    public static final String ID_UNAVAILABLE = "id_unavailable";
    public static final String IDEMPOTENCY_CONFLICT = "idempotency_conflict";
    public static final String SNAPSHOT_NOT_FOUND = "snapshot_not_found";
    public static final String PLAYER_NOT_FOUND = "player_not_found";
    public static final String STATE_INVALID = "state_invalid";
    public static final String RESULT_TRUNCATED = "result_truncated";
    public static final String SNAPSHOT_DB_ERROR = "snapshot_db_error";
    public static final String NOT_IMPLEMENTED = "not_implemented";
    // 批次 7.2b（作业框架、栅栏与回档，§7.7）
    public static final String OPS_DISABLED = "ops_disabled";
    public static final String OPS_BUSY = "ops_busy";
    public static final String JOB_NOT_FOUND = "job_not_found";
    public static final String ZONE_OPEN = "zone_open";
    public static final String ZONE_NOT_FOUND = "zone_not_found";
    public static final String PLAN_TOO_LARGE = "plan_too_large";

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public OpsException(HttpStatus status, String code, String message) {
        this(status, code, message, Map.of(), null);
    }

    public OpsException(HttpStatus status, String code, String message, Map<String, Object> details, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public static OpsException badRequest(String message) {
        return new OpsException(HttpStatus.BAD_REQUEST, INVALID_REQUEST, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    /** 附加字段（并进应答体，键不与 code / message 重名）。 */
    public Map<String, Object> details() {
        return details;
    }
}
