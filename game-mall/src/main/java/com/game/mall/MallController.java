package com.game.mall;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestController
public final class MallController {
    private final MallOrderStore store;

    public MallController(MallOrderStore store) { this.store = store; }

    @PostMapping("/api/orders")
    public MallApi.Order create(HttpServletRequest request, @Valid @RequestBody MallApi.CreateOrder body) {
        return store.create(subject(request), body);
    }

    @GetMapping("/api/orders/{orderId}")
    public MallApi.Order get(HttpServletRequest request, @PathVariable("orderId") String orderId) {
        return store.get(subject(request), orderId);
    }

    @PostMapping("/api/orders/list") public java.util.List<MallApi.Order> list(HttpServletRequest request,@RequestBody DeliveryApi.OrderPage page){return store.list(subject(request),page);}

    @PostMapping("/api/orders/{orderId}/cancel")
    public MallApi.Order cancel(HttpServletRequest request, @PathVariable("orderId") String orderId) {
        return store.cancel(subject(request), orderId);
    }

    @PostMapping("/internal/payments")
    public MallApi.Order payment(HttpServletRequest request, @Valid @RequestBody MallApi.Payment body) {
        // 在资产相关的提交入口复核权限，内部转发不能提升已认证调用方的角色。
        if (!"payment".equals(request.getAttribute(MallAuthentication.ROLE))) {
            throw new MallException(401, "支付调用方认证失败");
        }
        return store.recordTrustedPayment(body);
    }

    private static String subject(HttpServletRequest request) { return (String) request.getAttribute(MallAuthentication.SUBJECT); }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(MallException.class)
        ResponseEntity<Map<String, String>> business(MallException failure) {
            return ResponseEntity.status(failure.status()).body(Map.of("error", failure.getMessage()));
        }

        @ExceptionHandler(DataAccessException.class)
        ResponseEntity<Map<String, String>> storage() {
            return ResponseEntity.status(503).body(Map.of("error", "订单存储暂不可用，请使用原业务请求号查询或重试"));
        }
    }
}
