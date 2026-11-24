package com.game.payment.channel;

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
import com.game.proto.payment.DouyinMinigamePayParams;
import com.game.proto.payment.PayChannel;
import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 抖音小游戏虚拟支付道具直购（{@code goodType=2}，仅安卓，对齐 Go {@code douyin.go}）。
 */
public final class DouyinMinigameChannel implements PaymentChannel, PaymentChannel.Handshaker {

    private static final ObjectMapper JSON = new ObjectMapper();

    public record Config(String appId, String pushToken, String zoneId, String goodName) {}

    private final Config cfg;
    private final String zoneId;

    public DouyinMinigameChannel(Config cfg) {
        if (cfg.appId() == null || cfg.appId().isBlank() || cfg.pushToken() == null || cfg.pushToken().isBlank()) {
            throw new IllegalArgumentException("payment channel: douyin_minigame needs AppID and PushToken");
        }
        this.cfg = cfg;
        this.zoneId = cfg.zoneId() == null || cfg.zoneId().isEmpty() ? "1" : cfg.zoneId();
    }

    @Override public PayChannel id() { return PayChannel.PAY_CHANNEL_DOUYIN_MINIGAME; }
    @Override public String name() { return ChannelTypes.NAME_DOUYIN_MINIGAME; }
    @Override public boolean supports(String currency) { return "CNY".equals(currency); }
    @Override public boolean needsProduct() { return false; }

    @Override
    public Prepared prepare(Order order, Ref cached) throws ChannelException {
        if (!"CNY".equals(order.currency())) {
            throw new ChannelException(ChannelException.Kind.OTHER, "douyin_minigame only takes CNY");
        }
        String name = (order.title() != null && !order.title().isEmpty()) ? order.title() : cfg.goodName();
        if (name == null || name.isEmpty()) {
            name = "游戏道具";
        } else if (name.codePointCount(0, name.length()) > 10) {
            name = name.substring(0, name.offsetByCodePoints(0, 10));
        }
        return new Prepared(ClientParams.ofDouyin(DouyinMinigamePayParams.newBuilder()
                .setMode("game")
                .setEnv(0)
                .setCurrencyType("CNY")
                .setPlatform("android")
                .setBuyQuantity(1)
                .setZoneId(zoneId)
                .setCustomId(order.outTradeNo())
                .setExtraInfo("")
                .setGoodType(2)
                .setOrderAmount(order.amountMinor())
                .setGoodName(name)
                .build()));
    }

    @Override
    public QueryResult query(QueryRequest req) throws ChannelException {
        throw new ChannelException(ChannelException.Kind.QUERY_UNSUPPORTED, "douyin_minigame cannot be queried");
    }

    @Override public void close(String outTradeNo) {}

    @Override
    public RefundResult refund(RefundRequest req) {
        return RefundResult.UNSUPPORTED;
    }

    @Override
    public AckResponse handshake(HttpNotificationRequest req) {
        if (!"GET".equalsIgnoreCase(req.method())) {
            return null;
        }
        String echostr = req.query("echostr");
        String expected = ChannelCrypto.sortedSha1Hex(cfg.pushToken(), req.query("timestamp"), req.query("nonce"), req.query("msg"));
        if (echostr.isEmpty() || !ChannelCrypto.constantTimeEqualsHex(req.query("signature"), expected)) {
            return new AckResponse(401, "text/plain; charset=utf-8", "");
        }
        return new AckResponse(200, "text/plain; charset=utf-8", echostr);
    }

    @Override
    public Notification parseNotification(HttpNotificationRequest req) throws ChannelException {
        JsonNode env;
        try {
            env = JSON.readTree(req.body() == null ? new byte[0] : req.body());
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid douyin JSON", e);
        }
        String ts = env.path("timestamp").asText("");
        String nonce = env.path("nonce").asText("");
        String msgStr = env.path("msg").asText("");
        String sig = env.path("signature").asText("");
        String type = env.path("type").asText("");
        String expected = ChannelCrypto.sortedSha1Hex(cfg.pushToken(), ts, nonce, msgStr);
        if (ts.isEmpty() || nonce.isEmpty() || msgStr.isEmpty() || !ChannelCrypto.constantTimeEqualsHex(sig, expected)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "douyin signature mismatch");
        }
        if (!type.isEmpty() && !"payment".equals(type)) {
            return Notification.EMPTY;
        }
        JsonNode msg;
        try {
            msg = JSON.readTree(msgStr);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid douyin msg", e);
        }
        String cpOrderNo = msg.path("cp_orderno").asText("");
        long amountCent = msg.path("amount_cent").asLong(0L);
        if (!cfg.appId().equals(msg.path("appid").asText("")) || cpOrderNo.isEmpty() || amountCent <= 0) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "douyin msg identity or amount invalid");
        }
        String currency = msg.path("currency").asText("");
        if (currency.isEmpty()) {
            currency = "CNY";
        }
        Instant paidAt = parseUnixText(ts);
        Payment p = new Payment(cpOrderNo, cpOrderNo, "", amountCent, currency, paidAt,
                "", false, 0L, "", "", msg.path("order_no_channel").asText(""), false, false, "", "");
        return new Notification(p, List.of(), List.of());
    }

    @Override
    public AckResponse ack(AckKind kind) {
        if (kind == AckKind.OK) {
            return new AckResponse(200, "application/json", "{\"err_no\":0,\"err_tips\":\"success\"}");
        }
        return new AckResponse(500, "application/json", "{\"err_no\":1,\"err_tips\":\"failed\"}");
    }

    private static Instant parseUnixText(String text) {
        try {
            long v = Long.parseLong(text);
            if (v <= 0) return null;
            return v > 1_000_000_000_000L ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
