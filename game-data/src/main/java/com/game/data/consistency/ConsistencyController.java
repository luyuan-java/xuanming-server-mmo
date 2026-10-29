package com.game.data.consistency;

import com.game.data.admin.AdminAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** 同步一次性响应；断线不产生可查询的持久run，CLI必须返回UNKNOWN。 */
@RestController
public final class ConsistencyController {
    public static final String PATH="/admin/consistency/check";
    private final ConsistencyService service;
    public ConsistencyController(ConsistencyService service){this.service=service;}
    @PostMapping(PATH)
    public ResponseEntity<ConsistencyReport> check(@RequestBody(required=false) ConsistencyService.Request body,
            HttpServletRequest request) {
        AdminAuthFilter.authenticatedOperator(request);
        try {
            ConsistencyReport report=service.run(body);
            return ResponseEntity.ok().header("X-Game-Consistency-Exit",Integer.toString(report.exitCode())).body(report);
        } catch(IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"巡检检查项或预算非法");
        }
    }
}
