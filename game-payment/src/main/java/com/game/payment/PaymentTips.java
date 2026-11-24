package com.game.payment;

import com.game.proto.TipInfoMessage;
import com.game.table.CommonErrorTip;
import com.game.table.PaymentErrorTip;

/**
 * 支付服务错误码常量（与 {@code payment_error_tip.proto} / {@code common_error_tip.proto} 同值）。
 */
public final class PaymentTips {

    private PaymentTips() {}

    public static final int OK = 0;
    public static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    public static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;

    public static final int CHANNEL_UNAVAILABLE = PaymentErrorTip.payment_error.kPaymentChannelUnavailable_VALUE;
    public static final int ORDER_NOT_FOUND = PaymentErrorTip.payment_error.kPaymentOrderNotFound_VALUE;
    public static final int ORDER_NOT_PAYABLE = PaymentErrorTip.payment_error.kPaymentOrderNotPayable_VALUE;
    public static final int ORDER_EXPIRED = PaymentErrorTip.payment_error.kPaymentOrderExpired_VALUE;
    public static final int CHANNEL_MISMATCH = PaymentErrorTip.payment_error.kPaymentChannelMismatch_VALUE;
    public static final int CURRENCY_UNSUPPORTED = PaymentErrorTip.payment_error.kPaymentCurrencyUnsupported_VALUE;
    public static final int BUSY = PaymentErrorTip.payment_error.kPaymentBusy_VALUE;
    public static final int LOGIN_REQUIRED = PaymentErrorTip.payment_error.kPaymentLoginRequired_VALUE;
    public static final int REAL_NAME_REQUIRED = PaymentErrorTip.payment_error.kPaymentRealNameRequired_VALUE;
    public static final int MINOR_NOT_ALLOWED = PaymentErrorTip.payment_error.kPaymentMinorNotAllowed_VALUE;
    public static final int MINOR_SINGLE_LIMIT = PaymentErrorTip.payment_error.kPaymentMinorSingleLimit_VALUE;
    public static final int MINOR_DAILY_LIMIT = PaymentErrorTip.payment_error.kPaymentMinorDailyLimit_VALUE;
    public static final int MINOR_MONTHLY_LIMIT = PaymentErrorTip.payment_error.kPaymentMinorMonthlyLimit_VALUE;

    public static TipInfoMessage tip(int id) {
        return TipInfoMessage.newBuilder().setId(id).build();
    }
}
