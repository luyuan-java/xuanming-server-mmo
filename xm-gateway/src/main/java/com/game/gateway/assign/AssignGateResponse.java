package com.game.gateway.assign;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * {@code POST /api/assign-gate} 应答（客户端契约，形状与 mmorpg Java gateway 的 {@code AssignGateResponse} 一致）。
 *
 * <p>恒为 HTTP 200，业务结果在 {@code code}。JSON 键 snake_case（全局 Jackson 配置）；
 * 空引用字段不输出，{@code code} / {@code gate_port} / {@code token_deadline} 是基本类型，永远输出。
 * {@code byte[]} 由 Jackson 输出为<b>标准 Base64</b>（带填充），Go robot 用 {@code base64.StdEncoding} 解码。
 *
 * <p>可能的取值：
 * <ul>
 *   <li>{@code 0}：准入，{@code gate_ip} / {@code gate_port} / {@code token_payload} / {@code token_signature} /
 *       {@code token_deadline} 齐全；</li>
 *   <li>{@code 100}：登录排队（{@code queue_source="login"}、{@code queue_token}、{@code queue_rank} 从 0 起、{@code queue_total}、
 *       {@code retry_after_ms}），客户端存下令牌改调 {@code /api/queue-status}；</li>
 *   <li>{@code 410 queue_token_expired} / {@code missing_queue_token} / {@code queue_disabled}：排队令牌无效，客户端从 assign-gate 重来；</li>
 *   <li>{@code 404 zone_not_found}；{@code 503 zone_maintenance} / {@code zone_closed} / {@code zone_not_open}（与 mmorpg 同文）；</li>
 *   <li>{@code 500}：{@code no_gate_available} / {@code gate_directory_unavailable} / {@code zone_admission_unavailable} /
 *       {@code queue_unavailable} / {@code internal_error}；</li>
 *   <li>{@code 400 bad_request}：请求体不是合法 JSON（Java 版补充，mmorpg 此时回 Spring 默认的 HTTP 400）。</li>
 * </ul>
 * 限流（100 + {@code queue_source="ratelimit"} / 429）随 3.4c。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AssignGateResponse(
        int code,
        String gateIp,
        int gatePort,
        byte[] tokenPayload,
        byte[] tokenSignature,
        long tokenDeadline,
        String error,
        Long retryAfterMs,
        String queueSource,
        String queueToken,
        Long queueRank,
        Long queueTotal) {

    public static final int CODE_OK = 0;
    public static final int CODE_QUEUEING = 100;
    public static final int CODE_QUEUE_EXPIRED = 410;
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_ZONE_NOT_FOUND = 404;
    public static final int CODE_INTERNAL = 500;
    public static final int CODE_ZONE_UNAVAILABLE = 503;

    public static final String ERR_BAD_REQUEST = "bad_request";
    public static final String ERR_ZONE_NOT_FOUND = "zone_not_found";
    public static final String ERR_ZONE_MAINTENANCE = "zone_maintenance";
    public static final String ERR_ZONE_CLOSED = "zone_closed";
    public static final String ERR_ZONE_NOT_OPEN = "zone_not_open";
    public static final String ERR_ZONE_ADMISSION_UNAVAILABLE = "zone_admission_unavailable";
    public static final String ERR_NO_GATE_AVAILABLE = "no_gate_available";
    public static final String ERR_GATE_DIRECTORY_UNAVAILABLE = "gate_directory_unavailable";
    public static final String ERR_INTERNAL = "internal_error";
    public static final String ERR_QUEUE_TOKEN_EXPIRED = "queue_token_expired";
    public static final String ERR_MISSING_QUEUE_TOKEN = "missing_queue_token";
    public static final String ERR_QUEUE_DISABLED = "queue_disabled";
    public static final String ERR_QUEUE_UNAVAILABLE = "queue_unavailable";
    public static final String QUEUE_SOURCE_LOGIN = "login";

    public static AssignGateResponse admitted(String gateIp, int gatePort, byte[] tokenPayload, byte[] tokenSignature,
                                              long tokenDeadline) {
        return new AssignGateResponse(CODE_OK, gateIp, gatePort, tokenPayload, tokenSignature, tokenDeadline, null, null,
                null, null, null, null);
    }

    public static AssignGateResponse rejected(int code, String error) {
        return new AssignGateResponse(code, null, 0, null, null, 0, error, null, null, null, null, null);
    }

    /** 登录排队中（同基线 loginQueueing）：{@code rank} 从 0 起。 */
    public static AssignGateResponse loginQueueing(String queueToken, long rank, long total, long retryAfterMs) {
        return new AssignGateResponse(CODE_QUEUEING, null, 0, null, null, 0, null, retryAfterMs, QUEUE_SOURCE_LOGIN,
                queueToken, rank, total);
    }
}
