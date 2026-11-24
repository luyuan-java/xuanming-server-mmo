package com.game.mall;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 渠道修订付款的入口（payment 角色，契约见 {@link MallApi.Revision} 与 {@link PaymentRevisionStore}）。 */
@RestController
public final class PaymentRevisionController {
    private final PaymentRevisionStore store;

    public PaymentRevisionController(PaymentRevisionStore store) {
        this.store = store;
    }

    @PostMapping("/internal/payments/revision")
    public MallApi.RevisionOutcome revision(HttpServletRequest request, @RequestBody MallApi.Revision input) {
        if (!"payment".equals(request.getAttribute(MallAuthentication.ROLE))) {
            throw new MallException(401, "支付调用方认证失败");
        }
        return store.record(input);
    }
}
