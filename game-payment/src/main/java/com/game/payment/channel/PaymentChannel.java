package com.game.payment.channel;

import com.game.payment.channel.ChannelTypes.AckKind;
import com.game.payment.channel.ChannelTypes.AckResponse;
import com.game.payment.channel.ChannelTypes.ChannelException;
import com.game.payment.channel.ChannelTypes.HistoryPollResult;
import com.game.payment.channel.ChannelTypes.HttpNotificationRequest;
import com.game.payment.channel.ChannelTypes.Notification;
import com.game.payment.channel.ChannelTypes.Order;
import com.game.payment.channel.ChannelTypes.Prepared;
import com.game.payment.channel.ChannelTypes.QueryRequest;
import com.game.payment.channel.ChannelTypes.QueryResult;
import com.game.payment.channel.ChannelTypes.Receipt;
import com.game.payment.channel.ChannelTypes.Ref;
import com.game.payment.channel.ChannelTypes.RefundRequest;
import com.game.payment.channel.ChannelTypes.RefundResult;
import com.game.proto.payment.PayChannel;
import java.time.Instant;

/**
 * 支付渠道策略接口（Strategy 模式）：仅处理渠道协议（下单、查单、关单、退款、回调验签），不接触数据库或商城。
 * 全部实现必须线程安全。
 */
public interface PaymentChannel {

    PayChannel id();

    String name();

    boolean supports(String currency);

    boolean needsProduct();

    Prepared prepare(Order order, Ref cached) throws ChannelException;

    QueryResult query(QueryRequest req) throws ChannelException;

    void close(String outTradeNo) throws ChannelException;

    RefundResult refund(RefundRequest req) throws ChannelException;

    Notification parseNotification(HttpNotificationRequest req) throws ChannelException;

    AckResponse ack(AckKind kind);

    /** 商店渠道（App Store / Google Play）：凭客户端上报交易号或令牌向商店服务端核实。 */
    interface ReceiptChannel {
        QueryResult verify(Receipt receipt) throws ChannelException;

        String accountRef(long playerId);
    }

    /** 记账后需向渠道做收尾动作的渠道（如 Google Play consume）。 */
    interface Finalizer {
        void finalizePurchase(String productId, String purchaseToken) throws ChannelException;
    }

    /** 支持按时间窗游标补拉历史事件的渠道（App Store 通知历史、Google Play 作废列表）。 */
    interface HistoryPoller {
        HistoryPollResult pollHistory(Instant since) throws ChannelException;
    }

    /** 支持 GET 回调地址配置握手的渠道（微信小游戏 / 抖音小游戏）。 */
    interface Handshaker {
        /** 返回非 null 表示由本渠道处理并写出应答；返回 null 表示不处理。 */
        AckResponse handshake(HttpNotificationRequest req);
    }
}
