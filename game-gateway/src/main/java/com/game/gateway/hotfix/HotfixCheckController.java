package com.game.gateway.hotfix;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 与原版相同的热更检查入口；成功 HTTP 200，缺字段或非法版本 HTTP 400。 */
@RestController
public class HotfixCheckController {
    private final HotfixCheckService service;

    public HotfixCheckController(HotfixCheckService service) {
        this.service = service;
    }

    @PostMapping("/api/hotfix-check")
    public HotfixCheckResponse check(@RequestBody HotfixCheckRequest request) {
        return service.check(request);
    }
}
