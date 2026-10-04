package com.game.gateway.assign;

import com.game.gateway.ratelimit.RateLimitDecision;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * assign-gate 与排队轮询的结局计数（{@code xm.gateway.assign.gate{code, reason}}、{@code xm.gateway.queue.status{code, reason}}，
 * 见 architecture.md §11）。
 *
 * <p>每个 {@code POST /api/assign-gate} / {@code POST /api/queue-status} 恰好计一次，包括请求体不合法、未预期异常被
 * {@link AssignGateExceptionHandler} 兜成业务码的情形。标签只取 {@link AssignGateResponse} 里的常量（业务码 + {@code error} 文案，
 * 成功为 {@code ok}、登录排队中为 {@code queueing}、限流排队为 {@code ratelimit}），不带 zone / 账号 / 设备 / IP。已知的组合启动时预先注册，
 * 未出过的结局也以 0 出现在抓取结果里。
 *
 * <p>线程安全：Servlet 请求线程并发调用。
 */
public final class AssignGateMetrics {

    static final String NAME = "xm.gateway.assign.gate";
    static final String QUEUE_STATUS_NAME = "xm.gateway.queue.status";
    static final String REASON_OK = "ok";
    static final String REASON_QUEUEING = "queueing";
    static final String REASON_RATELIMIT = "ratelimit";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<Key, Counter> counters = new ConcurrentHashMap<>();

    public AssignGateMetrics(MeterRegistry registry) {
        this.registry = registry;
        List<Key> assignKnown = List.of(
                new Key(NAME, AssignGateResponse.CODE_OK, REASON_OK),
                new Key(NAME, AssignGateResponse.CODE_QUEUEING, REASON_QUEUEING),
                new Key(NAME, AssignGateResponse.CODE_QUEUEING, REASON_RATELIMIT),
                new Key(NAME, AssignGateResponse.CODE_RATE_LIMITED, RateLimitDecision.IP_RATE_LIMIT),
                new Key(NAME, AssignGateResponse.CODE_RATE_LIMITED, RateLimitDecision.ACCOUNT_COOLDOWN),
                new Key(NAME, AssignGateResponse.CODE_QUEUE_EXPIRED, AssignGateResponse.ERR_QUEUE_TOKEN_EXPIRED),
                new Key(NAME, AssignGateResponse.CODE_BAD_REQUEST, AssignGateResponse.ERR_BAD_REQUEST),
                new Key(NAME, AssignGateResponse.CODE_ZONE_NOT_FOUND, AssignGateResponse.ERR_ZONE_NOT_FOUND),
                new Key(NAME, AssignGateResponse.CODE_ZONE_UNAVAILABLE, AssignGateResponse.ERR_ZONE_MAINTENANCE),
                new Key(NAME, AssignGateResponse.CODE_ZONE_UNAVAILABLE, AssignGateResponse.ERR_ZONE_CLOSED),
                new Key(NAME, AssignGateResponse.CODE_ZONE_UNAVAILABLE, AssignGateResponse.ERR_ZONE_NOT_OPEN),
                new Key(NAME, AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_ZONE_ADMISSION_UNAVAILABLE),
                new Key(NAME, AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_NO_GATE_AVAILABLE),
                new Key(NAME, AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_GATE_DIRECTORY_UNAVAILABLE),
                new Key(NAME, AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_QUEUE_UNAVAILABLE),
                new Key(NAME, AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_INTERNAL));
        List<Key> queueKnown = List.of(
                new Key(QUEUE_STATUS_NAME, AssignGateResponse.CODE_OK, REASON_OK),
                new Key(QUEUE_STATUS_NAME, AssignGateResponse.CODE_QUEUEING, REASON_QUEUEING),
                new Key(QUEUE_STATUS_NAME, AssignGateResponse.CODE_QUEUE_EXPIRED, AssignGateResponse.ERR_QUEUE_TOKEN_EXPIRED),
                new Key(QUEUE_STATUS_NAME, AssignGateResponse.CODE_QUEUE_EXPIRED, AssignGateResponse.ERR_MISSING_QUEUE_TOKEN),
                new Key(QUEUE_STATUS_NAME, AssignGateResponse.CODE_QUEUE_EXPIRED, AssignGateResponse.ERR_QUEUE_DISABLED),
                new Key(QUEUE_STATUS_NAME, AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_QUEUE_UNAVAILABLE),
                new Key(QUEUE_STATUS_NAME, AssignGateResponse.CODE_BAD_REQUEST, AssignGateResponse.ERR_BAD_REQUEST),
                new Key(QUEUE_STATUS_NAME, AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_INTERNAL));
        assignKnown.forEach(this::counter);
        queueKnown.forEach(this::counter);
    }

    /** 记一次 assign-gate 的结局。 */
    public void record(AssignGateResponse response) {
        counter(new Key(NAME, response.code(), reason(response))).increment();
    }

    /** 记一次排队轮询（{@code /api/queue-status}）的结局。 */
    public void recordQueueStatus(AssignGateResponse response) {
        counter(new Key(QUEUE_STATUS_NAME, response.code(), reason(response))).increment();
    }

    private static String reason(AssignGateResponse response) {
        if (response.error() != null) {
            return response.error();
        }
        if (response.code() != AssignGateResponse.CODE_QUEUEING) {
            return REASON_OK;
        }
        return AssignGateResponse.QUEUE_SOURCE_RATELIMIT.equals(response.queueSource()) ? REASON_RATELIMIT : REASON_QUEUEING;
    }

    private Counter counter(Key key) {
        return counters.computeIfAbsent(key, k -> Counter.builder(k.name())
                .description(k.name().equals(NAME) ? "assign-gate 的结局（HTTP 恒 200，按应答体的业务码与 error 分类）"
                        : "排队轮询 /api/queue-status 的结局（HTTP 恒 200，按应答体的业务码与 error 分类）")
                .tag("code", Integer.toString(k.code()))
                .tag("reason", k.reason())
                .register(registry));
    }

    private record Key(String name, int code, String reason) {
    }
}
