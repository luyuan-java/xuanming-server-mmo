package com.game.data.admin;

import com.game.data.ops.OpsException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 运维面业务失败的统一应答体：{@code {"code": "...", "message": "...", ...details}}（data-ops-spec §7.7）。只处理 {@link OpsException}。 */
@RestControllerAdvice
public class OpsErrorAdvice {

    @ExceptionHandler(OpsException.class)
    public ResponseEntity<Map<String, Object>> onOps(OpsException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        e.details().forEach(body::putIfAbsent);
        return ResponseEntity.status(e.status()).body(body);
    }
}
