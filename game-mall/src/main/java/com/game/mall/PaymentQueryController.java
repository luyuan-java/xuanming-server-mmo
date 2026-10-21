package com.game.mall;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 支付适配器的只读接缝；当前 SKU 仅含官方物品，不表示钻石充值或管理后台已经接入。 */
@RestController
public final class PaymentQueryController {
    private final MallOrderStore orders;
    private final DeliveryStore deliveries;

    public PaymentQueryController(MallOrderStore orders, DeliveryStore deliveries) {
        this.orders = orders;
        this.deliveries = deliveries;
    }

    @PostMapping("/internal/payments/order-view")
    public MallApi.Order order(HttpServletRequest request, @Valid @RequestBody MallApi.OrderQuery query) {
        requirePayment(request);
        return orders.paymentOrderView(query.orderId());
    }

    @PostMapping("/internal/payments/skus")
    public DeliveryApi.Skus skus(HttpServletRequest request, @RequestBody DeliveryApi.SkuPage page) {
        requirePayment(request);
        return deliveries.skus(page);
    }

    private static void requirePayment(HttpServletRequest request) {
        // 真实 servlet forward 不应把已认证的网关或交付调用方提升为支付角色。
        if (!"payment".equals(request.getAttribute(MallAuthentication.ROLE))) {
            throw new MallException(401, "支付查询调用方认证失败");
        }
    }
}
