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
                              @Positive long skuId, @Min(1) @Max(10000) int quantity,
                              @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(min=1,max=191) String buyerAccount,
                              @NotBlank String buyerOwnerRevision) { }
    public record OwnedOrder(String orderId, String buyerAccount, String buyerOwnerRevision) { }

    /** 仅支付角色可跨买家查单，买家身份与金额仍由原订单返回。 */
    public record OrderQuery(@NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String orderId) { }

    /** 调用方必须已核验渠道事实；本地mock还须匹配独立原件，签名不代表真实支付渠道已接通。 */
    public record Payment(@NotBlank @Pattern(regexp = "[0-9a-f-]{36}") String orderId,
                          @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,31}") String channel,
                          @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String transactionId,
                          @Positive long amountMinor,
                          @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,15}") String currency) { }

    /** buyerId 是完整 uint64 的规范十进制字符串，不借用上游有符号 memberId。 */
    public record Order(String orderId, String buyerId, long skuId, int quantity, long amountMinor,
                        String currency, String state, long paymentDeadlineMs) { }
}
