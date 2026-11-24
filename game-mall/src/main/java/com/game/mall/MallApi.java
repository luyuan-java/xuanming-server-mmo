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
                              @jakarta.validation.constraints.NotNull @jakarta.validation.constraints.Size(min = 1, max = 191) String buyerAccount,
                              @NotBlank String buyerOwnerRevision) { }

    public record OwnedOrder(String orderId, String buyerAccount, String buyerOwnerRevision) { }

    /** 仅支付角色可跨买家查单，买家身份与金额仍由原订单返回。 */
    public record OrderQuery(@NotBlank @Pattern(regexp = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") String orderId) { }

    /** 调用方必须已核验渠道事实；amountMinor/currency 是渠道实收值，不符时仍记入异常付款并排入退款。 */
    public record Payment(@NotBlank @Pattern(regexp = "[0-9a-f-]{36}") String orderId,
                          @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{1,31}") String channel,
                          @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,128}") String transactionId,
                          @Positive long amountMinor,
                          @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{2,15}") String currency) { }

    /** POST /internal/payments 应答：refundReason 为 null 表示正常绑单付款，非 null 表示已排入退款待办。 */
    public record PaymentOutcome(Order order, RefundReason refundReason) { }

    /** 退款原因：前两种退订单绑定的那笔，后三种为不绑单的异常付款。 */
    public enum RefundReason {
        CANCELLED_LATE(true),
        DELIVERY_REJECTED(true),
        DUPLICATE_PAYMENT(false),
        AMOUNT_MISMATCH(false),
        CURRENCY_MISMATCH(false);

        private final boolean ordersOwnPayment;

        RefundReason(boolean ordersOwnPayment) {
            this.ordersOwnPayment = ordersOwnPayment;
        }

        public boolean refundsOrdersOwnPayment() {
            return ordersOwnPayment;
        }

        static RefundReason stored(String value) {
            for (RefundReason reason : values()) {
                if (reason.name().equals(value)) {
                    return reason;
                }
            }
            throw new MallException(503, "退款原因无法识别");
        }
    }

    /** 待退款条目（/internal/refunds/poll）：包含被退付款交易号、实收金额币种及退款原因。 */
    public record PendingRefund(String orderId, String channel, String transactionId, long amountMinor,
                                String currency, RefundReason reason) { }

    /** 渠道退款回执（/internal/refunds/receipt）：paymentTransactionId 指明被退的那笔付款交易号。 */
    public record Refund(String orderId, String channel, String paymentTransactionId, String transactionId,
                         long amountMinor, String currency) { }

    /** 退款转人工（/internal/refunds/close）：渠道不支持开发者退款或明确失败时终结待办。 */
    public record RefundClosure(String orderId, String channel, String paymentTransactionId, String reason) { }

    /** 渠道付款修订（/internal/payments/revision）：奇数=REVOKED，正偶数=PAID。 */
    public record Revision(String orderId, String channel, String transactionId, int revision, String fact) { }

    /** 修订应答：返回商城当前记录的修订号、事实与订单最新状态。 */
    public record RevisionOutcome(int revision, String fact, String orderState) { }

    /** buyerId 是完整 uint64 的规范十进制字符串，不借用上游有符号 memberId。 */
    public record Order(String orderId, String buyerId, long skuId, int quantity, long amountMinor,
                        String currency, String state, long paymentDeadlineMs, int paymentRevision) {
        public Order(String orderId, String buyerId, long skuId, int quantity, long amountMinor,
                     String currency, String state, long paymentDeadlineMs) {
            this(orderId, buyerId, skuId, quantity, amountMinor, currency, state, paymentDeadlineMs, 0);
        }
    }
}
