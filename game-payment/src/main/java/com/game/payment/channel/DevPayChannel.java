package com.game.payment.channel;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.payment.channel.ChannelTypes.AckKind;
import com.game.payment.channel.ChannelTypes.AckResponse;
import com.game.payment.channel.ChannelTypes.ChannelException;
import com.game.payment.channel.ChannelTypes.ClientParams;
import com.game.payment.channel.ChannelTypes.HttpNotificationRequest;
import com.game.payment.channel.ChannelTypes.Notification;
import com.game.payment.channel.ChannelTypes.Order;
import com.game.payment.channel.ChannelTypes.Payment;
import com.game.payment.channel.ChannelTypes.Prepared;
import com.game.payment.channel.ChannelTypes.QueryRequest;
import com.game.payment.channel.ChannelTypes.QueryResult;
import com.game.payment.channel.ChannelTypes.Ref;
import com.game.payment.channel.ChannelTypes.RefundRequest;
import com.game.payment.channel.ChannelTypes.RefundResult;
import com.game.payment.channel.ChannelTypes.RefundState;
import com.game.payment.channel.ChannelTypes.Revocation;
import com.game.proto.payment.DevPayParams;
import com.game.proto.payment.PayChannel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * 本地联调渠道（{@code devpay}，仅允许在 {@code dev}/{@code test} 模式启用）：
 * 使用 {@code X-Devpay-Signature = hex(HMAC-SHA256(secret, body))} 模拟付款、重复付款（{@code seq > 0}）与渠道侧撤销（{@code revoke = true}）。
 */
public final class DevPayChannel implements PaymentChannel {

    public static final String SIGNATURE_HEADER = "X-Devpay-Signature";
    private static final HexFormat HEX = HexFormat.of();
    private static final ObjectMapper STRICT_JSON = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    private final byte[] secret;
    private final Clock clock;

    public DevPayChannel(String secret, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("payment channel: devpay secret must be at least 32 bytes");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public static String signHex(byte[] secret, byte[] body) {
        return HEX.formatHex(ChannelCrypto.hmacSha256(secret, body));
    }

    @Override public PayChannel id() { return PayChannel.PAY_CHANNEL_DEVPAY; }
    @Override public String name() { return ChannelTypes.NAME_DEVPAY; }
    @Override public boolean supports(String currency) { return currency != null && !currency.isEmpty(); }
    @Override public boolean needsProduct() { return false; }

    @Override
    public Prepared prepare(Order order, Ref cached) {
        return new Prepared(ClientParams.ofDevPay(DevPayParams.newBuilder().setOutTradeNo(order.outTradeNo()).build()));
    }

    @Override
    public QueryResult query(QueryRequest req) {
        return QueryResult.notPaid();
    }

    @Override
    public void close(String outTradeNo) {}

    @Override
    public RefundResult refund(RefundRequest req) {
        return new RefundResult(RefundState.SUCCEEDED, "devpay-" + req.outRefundNo(), clock.instant(), "");
    }

    @Override
    public Notification parseNotification(HttpNotificationRequest req) throws ChannelException {
        byte[] body = req.body() == null ? new byte[0] : req.body();
        String sigHeader = req.header(SIGNATURE_HEADER);
        byte[] got;
        try {
            got = HEX.parseHex(sigHeader);
        } catch (IllegalArgumentException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "bad devpay signature hex", e);
        }
        byte[] expected = ChannelCrypto.hmacSha256(secret, body);
        if (!MessageDigest.isEqual(got, expected)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "devpay signature mismatch");
        }
        DevNotice notice;
        try {
            notice = STRICT_JSON.readValue(body, DevNotice.class);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid devpay body", e);
        }
        if (notice.out_trade_no == null || notice.out_trade_no.isEmpty()
                || (!notice.revoke && (notice.amount_minor <= 0 || notice.currency == null || notice.currency.isEmpty()))) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "incomplete devpay notice");
        }
        String txnId = "devpay-" + notice.out_trade_no + (notice.seq > 0 ? "-" + notice.seq : "");
        Instant now = clock.instant();
        if (notice.revoke) {
            return new Notification(null, List.of(new Revocation(txnId, "", "", true, now, "devpay", null)), List.of());
        }
        Payment p = new Payment(txnId, notice.out_trade_no, "", notice.amount_minor, notice.currency, now,
                "", false, 0L, "", "", "", false, false, "", "");
        return new Notification(p, List.of(), List.of());
    }

    @Override
    public AckResponse ack(AckKind kind) {
        return switch (kind) {
            case OK -> new AckResponse(200, "text/plain; charset=utf-8", "ok");
            case REJECTED -> new AckResponse(401, "text/plain; charset=utf-8", "");
            case RETRY -> new AckResponse(503, "text/plain; charset=utf-8", "");
        };
    }

    private static final class DevNotice {
        public String out_trade_no;
        public long amount_minor;
        public String currency;
        public int seq;
        public boolean revoke;
    }
}
