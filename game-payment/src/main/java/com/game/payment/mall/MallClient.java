package com.game.payment.mall;

import java.util.List;

/**
 * 支付服务以 {@code payment} 角色调用商城（{@code game-mall}）的客户端接口。
 */
public interface MallClient {

    String ORDER_STATE_AWAITING_PAYMENT = "AWAITING_PAYMENT";
    String FACT_PAID = "PAID";
    String FACT_REVOKED = "REVOKED";

    /** 商城订单只读视图（{@code /internal/payments/order-view}）。 */
    record OrderView(
            String orderId,
            String buyerId,
            long skuId,
            int quantity,
            long amountMinor,
            String currency,
            String state,
            long paymentDeadlineMs) {}

    /** 报给商城的付款回执（{@code /internal/payments}）。 */
    record PaymentReport(
            String orderId,
            String channel,
            String transactionId,
            long amountMinor,
            String currency) {}

    /** 付款回执结论；{@code refundReason} 为空表示已绑定订单，非空表示商城已排队退款。 */
    record PaymentOutcome(String orderState, String refundReason) {
        public boolean hasRefundReason() {
            return refundReason != null && !refundReason.isEmpty();
        }
    }

    /** 商城待退款项（{@code /internal/refunds/poll}）。 */
    record PendingRefund(
            String orderId,
            String channel,
            String transactionId,
            long amountMinor,
            String currency,
            String reason) {}

    /** 待退款分页应答。 */
    record PendingRefundPage(List<PendingRefund> entries, String nextCursor) {}

    /** 渠道退款回执（{@code /internal/refunds/receipt}）。 */
    record RefundReceipt(
            String orderId,
            String channel,
            String paymentTransactionId,
            String transactionId,
            long amountMinor,
            String currency) {}

    /** 无法自动退款时的转人工闭环（{@code /internal/refunds/close}）。 */
    record RefundClosure(String orderId, String channel, String paymentTransactionId, String reason) {}

    /** 渠道事实修订同步（{@code /internal/payments/revision}）。 */
    record RevisionReport(String orderId, String channel, String transactionId, int revision, String fact) {}

    /** 退款原因是否针对订单自身绑定付款（取消后迟到 / 交付被拒）。 */
    static boolean refundsOrdersOwnPayment(String reason) {
        return "CANCELLED_LATE".equals(reason) || "DELIVERY_REJECTED".equals(reason);
    }

    OrderView orderView(String orderId) throws MallClientException;

    PaymentOutcome recordPayment(PaymentReport payment) throws MallClientException;

    PendingRefundPage pendingRefunds(String cursor) throws MallClientException;

    void reportRefund(RefundReceipt refund) throws MallClientException;

    void closeRefund(RefundClosure closure) throws MallClientException;

    int pushRevision(RevisionReport revision) throws MallClientException;

    /** 商城调用异常（区分 400 非法、404 订单不存在、409 冲突、其他 HTTP 状态与传输失败）。 */
    final class MallClientException extends Exception {
        public enum Kind { INVALID, NOT_FOUND, CONFLICT, HTTP_STATUS, TRANSPORT }

        private final Kind kind;
        private final int status;

        public MallClientException(Kind kind, int status, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
            this.status = status;
        }

        public Kind kind() {
            return kind;
        }

        public int status() {
            return status;
        }
    }
}
