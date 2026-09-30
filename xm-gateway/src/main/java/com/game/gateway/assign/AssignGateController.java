package com.game.gateway.assign;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/assign-gate}：客户端连 gate 之前的第一跳。恒 HTTP 200，业务码在应答体（见 {@link AssignGateResponse}）；
 * 请求体解析失败与未预期异常由 {@link AssignGateExceptionHandler} 兜成同样的形状。
 */
@RestController
@RequestMapping("/api")
public class AssignGateController {

    private final AssignGateService service;

    public AssignGateController(AssignGateService service) {
        this.service = service;
    }

    @PostMapping("/assign-gate")
    public AssignGateResponse assignGate(@RequestBody AssignGateRequest request) {
        return service.assign(request.zoneId());
    }
}
