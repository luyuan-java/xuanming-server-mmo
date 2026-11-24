package com.game.payment.channel;

import com.game.proto.payment.AlipayAppPayParams;
import com.game.proto.payment.AppleIapParams;
import com.game.proto.payment.DevPayParams;
import com.game.proto.payment.DouyinMinigamePayParams;
import com.game.proto.payment.GooglePlayParams;
import com.game.proto.payment.PayChannel;
import com.game.proto.payment.WechatAppPayParams;
import com.game.proto.payment.WechatMinigamePayParams;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 支付渠道协议层的不可变值对象与异常类型（对齐 Go {@code payment/internal/channel}）。
 */
public final class ChannelTypes {

    private ChannelTypes() {}

    public static final String NAME_DEVPAY = "devpay";
    public static final String NAME_WECHAT_PAY = "wechat_pay";
    public static final String NAME_ALIPAY = "alipay";
    public static final String NAME_WECHAT_MINIGAME = "wechat_minigame";
    public static final String NAME_DOUYIN_MINIGAME = "douyin_minigame";
    public static final String NAME_APPLE = "apple";
    public static final String NAME_GOOGLE = "google";

    /** 渠道枚举 → 商城回执与配置里的固定渠道名。 */
    public static String nameOf(PayChannel id) {
        if (id == null) {
            return "";
        }
        return switch (id) {
            case PAY_CHANNEL_DEVPAY -> NAME_DEVPAY;
            case PAY_CHANNEL_WECHAT_PAY -> NAME_WECHAT_PAY;
            case PAY_CHANNEL_ALIPAY -> NAME_ALIPAY;
            case PAY_CHANNEL_WECHAT_MINIGAME -> NAME_WECHAT_MINIGAME;
            case PAY_CHANNEL_DOUYIN_MINIGAME -> NAME_DOUYIN_MINIGAME;
            case PAY_CHANNEL_APPLE -> NAME_APPLE;
            case PAY_CHANNEL_GOOGLE -> NAME_GOOGLE;
            default -> "";
        };
    }

    /** 渠道名 → 渠道枚举；不认识返回 {@code null}。 */
    public static PayChannel byName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case NAME_DEVPAY -> PayChannel.PAY_CHANNEL_DEVPAY;
            case NAME_WECHAT_PAY -> PayChannel.PAY_CHANNEL_WECHAT_PAY;
            case NAME_ALIPAY -> PayChannel.PAY_CHANNEL_ALIPAY;
            case NAME_WECHAT_MINIGAME -> PayChannel.PAY_CHANNEL_WECHAT_MINIGAME;
            case NAME_DOUYIN_MINIGAME -> PayChannel.PAY_CHANNEL_DOUYIN_MINIGAME;
            case NAME_APPLE -> PayChannel.PAY_CHANNEL_APPLE;
            case NAME_GOOGLE -> PayChannel.PAY_CHANNEL_GOOGLE;
            default -> null;
        };
    }

    public record Order(
            String orderId,
            String outTradeNo,
            long playerId,
            long amountMinor,
            String currency,
            Instant expireAt,
            String productId,
            String title,
            String loginCode,
            String deviceOs) {}

    public record Ref(String value, Instant at) {
        public static final Ref EMPTY = new Ref("", null);
        public boolean isPresent() {
            return value != null && !value.isEmpty();
        }
    }

    public record ClientParams(
            WechatAppPayParams wechat,
            AlipayAppPayParams alipay,
            DevPayParams devPay,
            WechatMinigamePayParams wechatMinigame,
            DouyinMinigamePayParams douyin,
            AppleIapParams apple,
            GooglePlayParams google) {
        public static final ClientParams EMPTY = new ClientParams(null, null, null, null, null, null, null);
        public static ClientParams ofWechat(WechatAppPayParams p) { return new ClientParams(p, null, null, null, null, null, null); }
        public static ClientParams ofAlipay(AlipayAppPayParams p) { return new ClientParams(null, p, null, null, null, null, null); }
        public static ClientParams ofDevPay(DevPayParams p) { return new ClientParams(null, null, p, null, null, null, null); }
        public static ClientParams ofWechatMinigame(WechatMinigamePayParams p) { return new ClientParams(null, null, null, p, null, null, null); }
        public static ClientParams ofDouyin(DouyinMinigamePayParams p) { return new ClientParams(null, null, null, null, p, null, null); }
        public static ClientParams ofApple(AppleIapParams p) { return new ClientParams(null, null, null, null, null, p, null); }
        public static ClientParams ofGoogle(GooglePlayParams p) { return new ClientParams(null, null, null, null, null, null, p); }
    }

    public record Prepared(Ref ref, String payerId, ClientParams params) {
        public Prepared(ClientParams params) {
            this(Ref.EMPTY, "", params);
        }
    }

    public record QueryRequest(String outTradeNo, String payerId, Ref ref, long amountMinor, String currency) {}

    public record Payment(
            String transactionId,
            String outTradeNo,
            String orderId,
            long amountMinor,
            String currency,
            Instant paidAt,
            String productId,
            boolean storePriced,
            long storeAmountMinor,
            String storeCurrency,
            String channelToken,
            String channelOrderId,
            boolean needsFinalize,
            boolean test,
            String accountRef,
            String anomaly) {

        public Payment withAnomaly(String newAnomaly) {
            return new Payment(transactionId, outTradeNo, orderId, amountMinor, currency, paidAt, productId,
                    storePriced, storeAmountMinor, storeCurrency, channelToken, channelOrderId, needsFinalize,
                    test, accountRef, newAnomaly);
        }
    }

    public enum TradeState {
        NOT_PAID,
        PAID,
        CLOSED,
        REFUNDED_OUTSIDE
    }

    public record QueryResult(TradeState state, Payment payment) {
        public static QueryResult notPaid() { return new QueryResult(TradeState.NOT_PAID, null); }
        public static QueryResult closed() { return new QueryResult(TradeState.CLOSED, null); }
        public static QueryResult closed(Payment p) { return new QueryResult(TradeState.CLOSED, p); }
        public static QueryResult paid(Payment p) { return new QueryResult(TradeState.PAID, p); }
        public static QueryResult refundedOutside(Payment p) { return new QueryResult(TradeState.REFUNDED_OUTSIDE, p); }
    }

    public record RefundRequest(
            String outTradeNo,
            String transactionId,
            String outRefundNo,
            long amountMinor,
            String currency,
            String reason,
            String channelToken,
            String channelOrderId) {}

    public enum RefundState {
        PROCESSING,
        SUCCEEDED,
        FAILED
    }

    public record RefundResult(RefundState state, String refundId, Instant at, String detail) {
        public static final RefundResult UNSUPPORTED = new RefundResult(RefundState.FAILED, "", null, "NO_REFUND_API");
    }

    public record Revocation(
            String transactionId,
            String outTradeNo,
            String outRefundNo,
            boolean revoked,
            Instant observedAt,
            String detail,
            Payment payment) {}

    public record Receipt(String transactionId, String purchaseToken, String productId) {}

    public record Notification(Payment payment, List<Revocation> revocations, List<Receipt> receipts) {
        public static final Notification EMPTY = new Notification(null, List.of(), List.of());

        public Notification {
            revocations = revocations == null ? List.of() : List.copyOf(revocations);
            receipts = receipts == null ? List.of() : List.copyOf(receipts);
        }
    }

    public enum AckKind {
        OK,
        RETRY,
        REJECTED
    }

    public record AckResponse(int status, String contentType, String body) {}

    /** 回调 HTTP 请求抽象（不把渠道层直接耦合到 Servlet API）。 */
    public record HttpNotificationRequest(
            String method,
            Map<String, String> headers,
            Map<String, String> queryParams,
            byte[] body) {

        public String header(String name) {
            if (headers == null || name == null) {
                return "";
            }
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (name.equalsIgnoreCase(e.getKey())) {
                    return e.getValue() == null ? "" : e.getValue();
                }
            }
            return "";
        }

        public String query(String name) {
            if (queryParams == null || name == null) {
                return "";
            }
            String v = queryParams.get(name);
            return v == null ? "" : v;
        }
    }

    /** 历史补漏分页结论。 */
    public record HistoryPollResult(List<Notification> notifications, Instant through) {}

    /** 渠道协议层异常分类。 */
    public static class ChannelException extends Exception {
        public enum Kind {
            BAD_SIGNATURE,
            TEMP_UNAVAILABLE,
            ORDER_PAID,
            QUERY_UNSUPPORTED,
            LOGIN_REQUIRED,
            CALL_ERROR,
            OTHER
        }

        private final Kind kind;
        private final String channelName;
        private final String op;
        private final int status;
        private final String code;

        public ChannelException(Kind kind, String message) {
            this(kind, "", "", 0, "", message, null);
        }

        public ChannelException(Kind kind, String message, Throwable cause) {
            this(kind, "", "", 0, "", message, cause);
        }

        public ChannelException(String channelName, String op, int status, String code) {
            this(Kind.CALL_ERROR, channelName, op, status, code == null ? "" : code,
                    "payment channel: " + channelName + " " + op + " failed (http " + status + ", code " + code + ")", null);
        }

        private ChannelException(Kind kind, String channelName, String op, int status, String code, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
            this.channelName = channelName;
            this.op = op;
            this.status = status;
            this.code = code;
        }

        public Kind kind() { return kind; }
        public String channelName() { return channelName; }
        public String op() { return op; }
        public int status() { return status; }
        public String code() { return code; }
    }
}
