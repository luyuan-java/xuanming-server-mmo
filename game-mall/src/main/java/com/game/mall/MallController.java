package com.game.mall;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import java.io.IOException;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class MallController {
    private final MallOrderStore store;
    private final ObjectMapper json;
    public MallController(MallOrderStore store, ObjectMapper json) {
        this.store = store; this.json = json.copy();
        this.json.coercionConfigFor(LogicalType.Textual)
            .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }
    @PostMapping("/api/orders") public MallApi.Order create(HttpServletRequest request) {
        return store.create(subject(request), input(request, MallApi.CreateOrder.class));
    }
    @GetMapping("/api/orders/{orderId}") public MallApi.Order get(HttpServletRequest request, @PathVariable("orderId") String orderId) { return store.get(subject(request), orderId); }
    @PostMapping("/api/orders/query") public MallApi.Order query(HttpServletRequest request){return store.get(subject(request),input(request,MallApi.OwnedOrder.class));}
    @PostMapping("/api/orders/list") public java.util.List<MallApi.Order> list(HttpServletRequest request){return store.list(subject(request),input(request,DeliveryApi.OrderPage.class));}
    @PostMapping("/api/orders/cancel") public MallApi.Order cancel(HttpServletRequest request){return store.cancel(subject(request),input(request,MallApi.OwnedOrder.class));}
    @PostMapping("/api/orders/{orderId}/cancel") public MallApi.Order cancel(HttpServletRequest request, @PathVariable("orderId") String orderId) { return store.cancel(subject(request), orderId); }
    @PostMapping("/internal/payments") public MallApi.Order payment(HttpServletRequest request, @Valid @RequestBody MallApi.Payment body) {
        // 经济权限在提交入口再次检查，不能让容器路由前缀或未来路径适配改变信任边界。
        if (!"payment".equals(request.getAttribute(MallAuthentication.ROLE))) { throw new MallException(401, "支付调用方认证失败"); }
        return store.recordTrustedPayment(body);
    }
    private String subject(HttpServletRequest request) { return (String) request.getAttribute(MallAuthentication.SUBJECT); }
    private <T> T input(HttpServletRequest request, Class<T> type) {
        if (!"gateway".equals(request.getAttribute(MallAuthentication.ROLE))) { throw new MallException(401, "订单调用方认证失败"); }
        try {
            return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readValue(request.getInputStream());
        } catch (IOException invalid) { throw new MallException(400, "订单命令必须包含完整且唯一的原身份字段"); }
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(MallException.class) ResponseEntity<Map<String, String>> business(MallException error) {
            return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
        }
        @ExceptionHandler(DataAccessException.class)
        ResponseEntity<Map<String, String>> storage() {
            return ResponseEntity.status(503).body(Map.of("error", "订单存储暂不可用，请使用原业务请求号查询或重试"));
        }
    }
}
