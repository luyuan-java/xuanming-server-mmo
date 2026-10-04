package com.game.gateway.assign;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/assign-gate}：客户端连 gate 之前的第一跳。恒 HTTP 200，业务码在应答体（见 {@link AssignGateResponse}）；
 * 请求体解析失败与未预期异常由 {@link AssignGateExceptionHandler} 兜成同样的形状。两条路径都按结局计数（{@link AssignGateMetrics}）。
 */
@RestController
@RequestMapping("/api")
public class AssignGateController {

    private final AssignGateService service;
    private final AssignGateMetrics metrics;

    public AssignGateController(AssignGateService service, AssignGateMetrics metrics) {
        this.service = service;
        this.metrics = metrics;
    }

    @PostMapping("/assign-gate")
    public AssignGateResponse assignGate(@RequestBody AssignGateRequest request) {
        AssignGateResponse response = service.assign(request.zoneId(), request.queueToken());
        metrics.record(response);
        return response;
    }

    /** 排队轮询：形状同 assign-gate 的应答（100 继续排、0 带 gate 令牌、410 从 assign-gate 重来）。 */
    @PostMapping("/queue-status")
    public AssignGateResponse queueStatus(@RequestBody QueueStatusRequest request) {
        AssignGateResponse response = service.queueStatus(request.zoneId(), request.queueToken());
        metrics.recordQueueStatus(response);
        return response;
    }
}
