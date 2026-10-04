package com.game.gateway.assign;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * assign-gate 的结局计数（{@code xm.gateway.assign.gate{code, reason}}，见 architecture.md §11）。
 *
 * <p>每个 {@code POST /api/assign-gate} 恰好计一次，包括请求体不合法、未预期异常被 {@link AssignGateExceptionHandler}
 * 兜成业务码的情形。标签只取 {@link AssignGateResponse} 里的常量（业务码 + {@code error} 文案，成功为 {@code ok}），
 * 不带 zone / 账号 / 设备 / IP。已知的组合启动时预先注册，未出过的结局也以 0 出现在抓取结果里。
 *
 * <p>线程安全：Servlet 请求线程并发调用。
 */
public final class AssignGateMetrics {

    static final String NAME = "xm.gateway.assign.gate";
    static final String REASON_OK = "ok";

    private final MeterRegistry registry;
    private final ConcurrentHashMap<Key, Counter> counters = new ConcurrentHashMap<>();

    public AssignGateMetrics(MeterRegistry registry) {
        this.registry = registry;
        List<Key> known = List.of(
                new Key(AssignGateResponse.CODE_OK, REASON_OK),
                new Key(AssignGateResponse.CODE_BAD_REQUEST, AssignGateResponse.ERR_BAD_REQUEST),
                new Key(AssignGateResponse.CODE_ZONE_NOT_FOUND, AssignGateResponse.ERR_ZONE_NOT_FOUND),
                new Key(AssignGateResponse.CODE_ZONE_UNAVAILABLE, AssignGateResponse.ERR_ZONE_MAINTENANCE),
                new Key(AssignGateResponse.CODE_ZONE_UNAVAILABLE, AssignGateResponse.ERR_ZONE_CLOSED),
                new Key(AssignGateResponse.CODE_ZONE_UNAVAILABLE, AssignGateResponse.ERR_ZONE_NOT_OPEN),
                new Key(AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_ZONE_ADMISSION_UNAVAILABLE),
                new Key(AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_NO_GATE_AVAILABLE),
                new Key(AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_GATE_DIRECTORY_UNAVAILABLE),
                new Key(AssignGateResponse.CODE_INTERNAL, AssignGateResponse.ERR_INTERNAL));
        for (Key key : known) {
            counter(key);
        }
    }

    /** 记一次 assign-gate 的结局。 */
    public void record(AssignGateResponse response) {
        String reason = response.error() == null ? REASON_OK : response.error();
        counter(new Key(response.code(), reason)).increment();
    }

    private Counter counter(Key key) {
        return counters.computeIfAbsent(key, k -> Counter.builder(NAME)
                .description("assign-gate 的结局（HTTP 恒 200，按应答体的业务码与 error 分类）")
                .tag("code", Integer.toString(k.code()))
                .tag("reason", k.reason())
                .register(registry));
    }

    private record Key(int code, String reason) {
    }
}
