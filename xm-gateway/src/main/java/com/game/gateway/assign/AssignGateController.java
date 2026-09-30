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
        AssignGateResponse response = service.assign(request.zoneId());
        metrics.record(response);
        return response;
    }
}
