package com.game.payment.store;

import com.game.proto.payment.PayChannel;
import com.game.proto.payment.PayIntentState;
import com.game.proto.payment.PayIntentView;

/**
 * 支付服务的库表记录模型与状态枚举常量（与 {@code proto/payment/payment_table.proto} 同值）。
 */
public final class PaymentRecords {

    private PaymentRecords() {}

    // PayIntentState（与 proto/payment/payment.proto 同值）
    public static final int INTENT_CREATED = PayIntentState.PAY_INTENT_STATE_CREATED_VALUE;
    public static final int INTENT_PAID = PayIntentState.PAY_INTENT_STATE_PAID_VALUE;
    public static final int INTENT_CLOSED = PayIntentState.PAY_INTENT_STATE_CLOSED_VALUE;

    // PayMallState（与 payment_table.proto 同值）
    public static final int MALL_PENDING = 1;
    public static final int MALL_BOUND = 2;
    public static final int MALL_REFUND_QUEUED = 3;
    public static final int MALL_REJECTED = 4;
    public static final int MALL_ORPHAN = 5;

    // PayRefundState
    public static final int REFUND_NONE = 0;
    public static final int REFUND_REQUESTED = 1;
    public static final int REFUND_SUCCEEDED = 2;
    public static final int REFUND_REPORTED = 3;
    public static final int REFUND_FAILED = 4;

    // PayFinalizeState
    public static final int FINALIZE_NONE = 0;
    public static final int FINALIZE_PENDING = 1;
    public static final int FINALIZE_DONE = 2;
    public static final int FINALIZE_FAILED = 3;

    /** 奇数修订号表示渠道已撤销，偶数表示有效付款。 */
    public static boolean isRevoked(int factRevision) {
        return (factRevision & 1) == 1;
    }

    /** 支付意图库表行（{@code payment_intent}）。 */
    public record IntentRecord(
            long orderHi,
            long orderLo,
            String orderId,
            long playerId,
            int channel,
            long amountMinor,
            String currency,
            int state,
            long expireMs,
            long nextCheckMs,
            int checkCount,
            long checkedMs,
            long createdMs,
            long updatedMs,
            String channelRef,
            long channelRefMs,
            long skuId,
            String payerId,
            long spenderHi,
            long spenderLo,
            long paidMs,
            int minorAge) {

        public boolean isMinor() {
            return spenderHi != 0L || spenderLo != 0L;
        }

        public PayIntentView toView() {
            PayChannel ch = PayChannel.forNumber(channel);
            PayIntentState st = PayIntentState.forNumber(state);
            return PayIntentView.newBuilder()
                    .setOrderId(orderId)
                    .setChannel(ch == null ? PayChannel.PAY_CHANNEL_UNSPECIFIED : ch)
                    .setState(st == null ? PayIntentState.PAY_INTENT_STATE_UNSPECIFIED : st)
                    .setAmountMinor(amountMinor)
                    .setCurrency(currency)
                    .setExpireMs(expireMs)
                    .build();
        }

        public IntentRecord withSpender(long hi, long lo, int age) {
            return new IntentRecord(orderHi, orderLo, orderId, playerId, channel, amountMinor, currency, state,
                    expireMs, nextCheckMs, checkCount, checkedMs, createdMs, updatedMs, channelRef, channelRefMs,
                    skuId, payerId, hi, lo, paidMs, age);
        }
    }

    /** 渠道付款事实库表行（{@code payment_txn}）。 */
    public record TxnRecord(
            int channel,
            long txnHi,
            long txnLo,
            String transactionId,
            long orderHi,
            long orderLo,
            String outTradeNo,
            long playerId,
            long paidAmountMinor,
            String paidCurrency,
            long paidMs,
            int mallState,
            String mallRefundReason,
            int mallAttempts,
            long nextAttemptMs,
            String mallLastError,
            int refundState,
            String outRefundNo,
            String refundId,
            long refundMs,
            int refundAttempts,
            long refundNextMs,
            String refundLastError,
            long createdMs,
            long updatedMs,
            int factRevision,
            long factObservedMs,
            int mallRevision,
            int mallDue,
            String channelToken,
            String channelOrderId,
            long storeAmountMinor,
            String storeCurrency,
            int finalizeState,
            long finalizeNextMs,
            String productId) {

        public TxnRecord withOrphan(String reason) {
            return new TxnRecord(channel, txnHi, txnLo, transactionId, orderHi, orderLo, outTradeNo, playerId,
                    paidAmountMinor, paidCurrency, paidMs, MALL_ORPHAN, mallRefundReason, mallAttempts, nextAttemptMs,
                    reason, refundState, outRefundNo, refundId, refundMs, refundAttempts, refundNextMs, refundLastError,
                    createdMs, updatedMs, factRevision, factObservedMs, mallRevision, 0, channelToken, channelOrderId,
                    storeAmountMinor, storeCurrency, FINALIZE_NONE, 0L, productId);
        }

        public TxnRecord withRefundId(String newRefundId) {
            return new TxnRecord(channel, txnHi, txnLo, transactionId, orderHi, orderLo, outTradeNo, playerId,
                    paidAmountMinor, paidCurrency, paidMs, mallState, mallRefundReason, mallAttempts, nextAttemptMs,
                    mallLastError, refundState, outRefundNo, newRefundId, refundMs, refundAttempts, refundNextMs,
                    refundLastError, createdMs, updatedMs, factRevision, factObservedMs, mallRevision, mallDue,
                    channelToken, channelOrderId, storeAmountMinor, storeCurrency, finalizeState, finalizeNextMs, productId);
        }
    }

    /** 幂等插入结论。 */
    public record InsertOutcome<T>(T record, boolean inserted) {}

    /** 未成年人单月/单日额度窗口（单位：分，起点为北京时间本月 1 日 0 点、本日 0 点 Unix 毫秒）。 */
    public record SpendLimitWindow(long monthlyFen, long dailyFen, long monthStartMs, long dayStartMs) {}

    /** 额度复核拒绝类型。 */
    public enum SpendRejection {
        NONE,
        MONTHLY_LIMIT,
        DAILY_LIMIT
    }

    /** 未成年人预占建意图结论。 */
    public record ReserveOutcome(IntentRecord record, boolean inserted, SpendRejection rejection) {}

    /** 未成年人付款原子落账与意图转已付结论。 */
    public record RecordMinorPaymentOutcome(TxnRecord stored, boolean inserted, boolean wasClosed, boolean overLimit) {}

    /** 商城同步推进结论。 */
    public record MallSync(int state, String refundReason, int revision, boolean keepSyncing, String lastError) {}

    /** 退款进度更新载体。 */
    public record RefundProgress(int state, String outRefundNo, String refundId, long refundMs, long nextMs, String lastError) {}

    /** 游标领取结论。 */
    public record CursorClaim(long cursorMs, boolean claimed) {}
}
