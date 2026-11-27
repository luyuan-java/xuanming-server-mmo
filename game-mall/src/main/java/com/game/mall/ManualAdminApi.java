package com.game.mall;

import java.util.List;

/** 官方商城与人民币寄售的人工单（MANUAL）查询与受控处置契约（对齐主仓 {@code ManualAdminApi} 与 jubaozhai-market.md §16.1）。 */
public final class ManualAdminApi {
    private ManualAdminApi() {}

    /** 查询人工待办分页参数；各游标均可省略或为空串，limit 缺省 50、上限 100。 */
    public record ListQuery(String afterOrderId, String afterListingId, String afterRefundCursor, int limit) {}

    /** 状态为 MANUAL(7) 的订单诊断视图（含官方交付/扣回与寄售/冻结收入上下文）。 */
    public record ManualOrder(
            String orderId,
            String buyerId,
            long skuId,
            int quantity,
            long amountMinor,
            String currency,
            int paymentRevision,
            String deliveryStatus,
            Integer reversalRevision,
            String reversalStatus,
            String consignmentListingId,
            String consignmentState,
            Long incomeNetFen,
            Long incomeReservedFen,
            Long incomePaidFen,
            Long incomeForfeitedFen,
            Long incomeAvailableMs) {}

    /** 状态为 MANUAL 的退款待办诊断视图。 */
    public record ManualRefund(
            String orderId,
            String channel,
            String transactionId,
            long amountMinor,
            String currency,
            MallApi.RefundReason reason,
            String closeReason,
            int paymentRevision,
            String paymentFact) {}

    /** 状态为 MANUAL 的寄售单与对应卖家收入视图。 */
    public record ManualConsignment(
            String listingId,
            String sellerPlayerId,
            long marketZone,
            String assetKind,
            String title,
            long priceFen,
            String currentOrderId,
            String orderState,
            Long incomeNetFen,
            Long incomeReservedFen,
            Long incomePaidFen,
            Long incomeForfeitedFen,
            Long incomeAvailableMs) {}

    /** 三类人工待办的分页汇总。 */
    public record Summary(
            List<ManualOrder> orders,
            List<ManualRefund> refunds,
            String nextRefundCursor,
            List<ManualConsignment> consignments) {}

    /**
     * 处置官方订单（skuId &gt; 0, status = 7 MANUAL）。
     * action 取值：RETRY_REVERSAL / MARK_REVERSED / MARK_DELIVERED / CREATE_REFUND。
     */
    public record ResolveOrder(String requestId, String orderId, String action, String reason) {}

    /**
     * 处置人工退款待办（commerce_refund_intent.status = MANUAL）。
     * action 取值：RETRY_REFUND / MARK_REFUNDED / MARK_REVOKED。
     */
    public record ResolveRefund(
            String requestId,
            String orderId,
            String channel,
            String paymentTransactionId,
            String action,
            String externalRefundId,
            String reason) {}

    /**
     * 处置人工寄售单（commerce_consignment.state = MANUAL）及冻结卖家收入（jubaozhai-market.md §16.1）。
     * action 取值：
     * - RETRY_DELIVERY：仅限绑定付款仍 PAID 且交付无终局（MANUAL / REVOKED），清除 MANUAL 回执、交付待办 -&gt; PENDING、寄售 -&gt; SOLD、订单 7 -&gt; 1；
     * - RETURN_TO_SELLER：仅限尚未生成卖家收入且绑定付款已被渠道撤销（REVOKED），付款仍 PAID 时拒绝（改用 RETRY_DELIVERY）；
     * - UNFREEZE_INCOME：解冻冻结卖家收入，寄售 MANUAL -&gt; SOLD、订单 7 -&gt; 2；
     * - FORFEIT_INCOME：无在途提现预留时没收剩余净额，寄售 MANUAL -&gt; FORFEITED、订单 7 -&gt; 9。
     */
    public record ResolveConsignment(String requestId, String listingId, String action, String reason) {}

    /** 人工单处置结果，按 (actor, requestId) 幂等持久化在 commerce_admin_command。 */
    public record Resolution(
            String targetType,
            String targetId,
            String action,
            String resultingState,
            String reason,
            long resolvedAtMs) {}
}
