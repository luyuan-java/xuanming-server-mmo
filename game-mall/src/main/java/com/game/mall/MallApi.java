package com.game.mall;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** 仅供已认证调用方的商城 HTTP 契约；不能以本接口取代游戏资产协议。 */
public final class MallApi {
    private MallApi() { }

    public record CreateOrder(@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{8,128}") String requestId,
                              @Positive long skuId, @Min(1) @Max(10000) int quantity) { }

    /** 调用方必须已核验真实渠道付款；签名认证并不代表这里已经实现支付渠道。 */
    public record Payment(@NotBlank @Pattern(regexp = "[0-9a-f-]{36}") String orderId,
                          @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,31}") String channel,
                          @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String transactionId,
                          @Positive long amountMinor,
                          @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,15}") String currency) { }

    /** buyerId 是完整 uint64 的规范十进制字符串，不借用上游有符号 memberId。 */
    public record Order(String orderId, String buyerId, long skuId, int quantity, long amountMinor,
                        String currency, String state, long paymentDeadlineMs) { }
}
