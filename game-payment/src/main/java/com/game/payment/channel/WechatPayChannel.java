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
import com.game.payment.channel.ChannelTypes.RefundState;
import com.game.payment.channel.ChannelTypes.Revocation;
import com.game.proto.payment.PayChannel;
import com.game.proto.payment.WechatAppPayParams;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 微信支付 APIv3 直连商户 APP 支付（公钥验签模式 {@code PUB_KEY_ID_}，对齐 Go {@code wechatpay.go}）。
 */
public final class WechatPayChannel implements PaymentChannel {

    private static final String DEFAULT_ENDPOINT = "https://api.mch.weixin.qq.com";
    private static final Duration PREPAY_REUSE = Duration.ofMinutes(110);
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Pattern PUBLIC_KEY_ID_PATTERN = Pattern.compile("^PUB_KEY_ID_[0-9A-Za-z_]{1,64}$");
    private static final Pattern ERROR_CODE_PATTERN = Pattern.compile("^[A-Z0-9_]{1,64}$");
    private static final Pattern REFUND_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Config(
            String appId,
            String mchId,
            String merchantSerialNo,
            RSAPrivateKey merchantKey,
            byte[] apiV3Key,
            String platformKeyId,
            RSAPublicKey platformKey,
            String notifyUrl,
            String description,
            String endpoint,
            HttpClient http,
            Clock clock) {}

    private final Config cfg;
    private final String endpoint;
    private final HttpClient http;
    private final Clock clock;

    public WechatPayChannel(Config cfg) {
        if (isBlank(cfg.appId()) || isBlank(cfg.mchId()) || isBlank(cfg.merchantSerialNo())) {
            throw new IllegalArgumentException("payment channel: wechat_pay needs AppID, MchID and MerchantSerialNo");
        }
        if (cfg.merchantKey() == null || cfg.platformKey() == null) {
            throw new IllegalArgumentException("payment channel: wechat_pay needs merchant private key and platform public key");
        }
        if (cfg.apiV3Key() == null || cfg.apiV3Key().length != 32) {
            throw new IllegalArgumentException("payment channel: wechat_pay APIv3 key must be 32 bytes");
        }
        if (cfg.platformKeyId() == null || !PUBLIC_KEY_ID_PATTERN.matcher(cfg.platformKeyId()).matches()) {
            throw new IllegalArgumentException("payment channel: wechat_pay PlatformPublicKeyID must be PUB_KEY_ID_...");
        }
        if (isBlank(cfg.notifyUrl()) || cfg.notifyUrl().contains("?") || cfg.notifyUrl().contains("#")) {
            throw new IllegalArgumentException("payment channel: wechat_pay notify URL is invalid");
        }
        if (isBlank(cfg.description()) || cfg.description().codePointCount(0, cfg.description().length()) > 127) {
            throw new IllegalArgumentException("payment channel: wechat_pay description must be 1-127 characters");
        }
        this.cfg = cfg;
        this.endpoint = (isBlank(cfg.endpoint()) ? DEFAULT_ENDPOINT : cfg.endpoint()).replaceAll("/+$", "");
        this.http = cfg.http() == null ? ChannelCrypto.newHttpClient() : cfg.http();
        this.clock = cfg.clock() == null ? Clock.systemUTC() : cfg.clock();
    }

    @Override public PayChannel id() { return PayChannel.PAY_CHANNEL_WECHAT_PAY; }
    @Override public String name() { return ChannelTypes.NAME_WECHAT_PAY; }
    @Override public boolean supports(String currency) { return "CNY".equals(currency); }
    @Override public boolean needsProduct() { return false; }

    @Override
    public Prepared prepare(Order order, Ref cached) throws ChannelException {
        if (!supports(order.currency())) {
            throw new ChannelException(ChannelException.Kind.OTHER, "wechat_pay only takes CNY");
        }
        Instant now = clock.instant();
        String prepayId = cached != null ? cached.value() : "";
        Ref fresh = Ref.EMPTY;
        if (prepayId == null || prepayId.isEmpty() || cached.at() == null
                || now.isBefore(cached.at()) || Duration.between(cached.at(), now).compareTo(PREPAY_REUSE) >= 0) {
            prepayId = prepay(order);
            fresh = new Ref(prepayId, now);
        }
        String nonce = ChannelCrypto.randomNonce32();
        String ts = Long.toString(now.getEpochSecond());
        String signMsg = cfg.appId() + "\n" + ts + "\n" + nonce + "\n" + prepayId + "\n";
        String sig = ChannelCrypto.signRsaSha256Base64(cfg.merchantKey(), signMsg.getBytes(StandardCharsets.UTF_8));
        WechatAppPayParams params = WechatAppPayParams.newBuilder()
                .setAppId(cfg.appId())
                .setPartnerId(cfg.mchId())
                .setPrepayId(prepayId)
                .setPackageValue("Sign=WXPay")
                .setNonceStr(nonce)
                .setTimestamp(ts)
                .setSign(sig)
                .build();
        return new Prepared(fresh, "", ClientParams.ofWechat(params));
    }

    private String prepay(Order order) throws ChannelException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("appid", cfg.appId());
        body.put("mchid", cfg.mchId());
        body.put("description", cfg.description());
        body.put("out_trade_no", order.outTradeNo());
        body.put("time_expire", order.expireAt().atOffset(ChannelCrypto.CHINA_ZONE).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        body.put("notify_url", cfg.notifyUrl());
        body.put("amount", Map.of("total", order.amountMinor(), "currency", "CNY"));
        byte[] rawReq = writeJson(body);
        ChannelCrypto.HttpResult res = doRequest("POST", "/v3/pay/transactions/app", rawReq);
        if (res.status() == 200) {
            JsonNode out = readJson(res.body(), "wechat_pay prepay answer unreadable");
            String id = out.path("prepay_id").asText("");
            if (id.isEmpty() || id.length() > 64) {
                throw new ChannelException(ChannelException.Kind.OTHER, "wechat_pay prepay answer lacks prepay_id");
            }
            return id;
        }
        String code = errorCode(res.body());
        if ("ORDERPAID".equals(code)) {
            throw new ChannelException(ChannelException.Kind.ORDER_PAID, "wechat_pay ORDERPAID");
        }
        throw new ChannelException(name(), "prepay", res.status(), code);
    }

    @Override
    public QueryResult query(QueryRequest req) throws ChannelException {
        String path = "/v3/pay/transactions/out-trade-no/" + urlPart(req.outTradeNo()) + "?mchid=" + urlPart(cfg.mchId());
        ChannelCrypto.HttpResult res = doRequest("GET", path, null);
        if (res.status() == 404 && "ORDER_NOT_EXIST".equals(errorCode(res.body()))) {
            return QueryResult.notPaid();
        }
        if (res.status() != 200) {
            throw new ChannelException(name(), "query", res.status(), errorCode(res.body()));
        }
        JsonNode txn = readJson(res.body(), "wechat_pay query answer unreadable");
        if (!cfg.appId().equals(txn.path("appid").asText(""))
                || !cfg.mchId().equals(txn.path("mchid").asText(""))
                || !req.outTradeNo().equals(txn.path("out_trade_no").asText(""))) {
            throw new ChannelException(ChannelException.Kind.OTHER, "wechat_pay query mismatch");
        }
        String state = txn.path("trade_state").asText("");
        return switch (state) {
            case "SUCCESS" -> QueryResult.paid(toPayment(txn));
            case "REFUND" -> QueryResult.refundedOutside(toPayment(txn));
            case "NOTPAY", "USERPAYING" -> QueryResult.notPaid();
            case "CLOSED", "REVOKED", "PAYERROR" -> QueryResult.closed();
            default -> throw new ChannelException(ChannelException.Kind.OTHER, "wechat_pay unknown trade_state: " + state);
        };
    }

    @Override
    public void close(String outTradeNo) throws ChannelException {
        byte[] body = writeJson(Map.of("mchid", cfg.mchId()));
        ChannelCrypto.HttpResult res = doRequest("POST", "/v3/pay/transactions/out-trade-no/" + urlPart(outTradeNo) + "/close", body);
        String code = errorCode(res.body());
        if (res.status() == 204 || res.status() == 200
                || "ORDER_NOT_EXIST".equals(code) || "ORDER_CLOSED".equals(code) || "ORDERPAID".equals(code)) {
            return;
        }
        throw new ChannelException(name(), "close", res.status(), code);
    }

    @Override
    public RefundResult refund(RefundRequest req) throws ChannelException {
        ChannelCrypto.HttpResult q = doRequest("GET", "/v3/refund/domestic/refunds/" + urlPart(req.outRefundNo()), null);
        if (q.status() == 200) {
            return parseRefundResult(q.body(), req);
        }
        if (!(q.status() == 404 && "RESOURCE_NOT_EXISTS".equals(errorCode(q.body())))) {
            throw new ChannelException(name(), "refund_query", q.status(), errorCode(q.body()));
        }
        if (!"CNY".equals(req.currency())) {
            return new RefundResult(RefundState.FAILED, "", null, "CURRENCY");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("transaction_id", req.transactionId());
        body.put("out_refund_no", req.outRefundNo());
        if (req.reason() != null && !req.reason().isEmpty()) {
            body.put("reason", req.reason());
        }
        body.put("amount", Map.of("refund", req.amountMinor(), "total", req.amountMinor(), "currency", "CNY"));
        ChannelCrypto.HttpResult r = doRequest("POST", "/v3/refund/domestic/refunds", writeJson(body));
        if (r.status() == 200) {
            return parseRefundResult(r.body(), req);
        }
        String code = errorCode(r.body());
        if (r.status() == 429 || r.status() >= 500 || r.status() == 401) {
            throw new ChannelException(name(), "refund", r.status(), code);
        }
        if ("NOT_ENOUGH".equals(code)) {
            return new RefundResult(RefundState.PROCESSING, "", null, code);
        }
        return new RefundResult(RefundState.FAILED, "", null, code);
    }

    private RefundResult parseRefundResult(byte[] raw, RefundRequest req) throws ChannelException {
        JsonNode node = readJson(raw, "wechat_pay refund answer unreadable");
        if (!req.outRefundNo().equals(node.path("out_refund_no").asText(""))) {
            throw new ChannelException(ChannelException.Kind.OTHER, "wechat_pay refund out_refund_no mismatch");
        }
        String status = node.path("status").asText("");
        return switch (status) {
            case "SUCCESS" -> {
                String refundId = node.path("refund_id").asText("");
                long amt = node.path("amount").path("refund").asLong(0L);
                if (!REFUND_ID_PATTERN.matcher(refundId).matches() || amt != req.amountMinor()) {
                    throw new ChannelException(ChannelException.Kind.OTHER, "wechat_pay refund id or amount mismatch");
                }
                Instant at = parseOptionalRfc3339(node.path("success_time").asText(""));
                yield new RefundResult(RefundState.SUCCEEDED, refundId, at, "");
            }
            case "PROCESSING" -> new RefundResult(RefundState.PROCESSING, "", null, "PROCESSING");
            case "ABNORMAL", "CLOSED" -> new RefundResult(RefundState.FAILED, "", null, status);
            default -> throw new ChannelException(ChannelException.Kind.OTHER, "wechat_pay unknown refund status");
        };
    }

    @Override
    public Notification parseNotification(HttpNotificationRequest req) throws ChannelException {
        byte[] body = req.body() == null ? new byte[0] : req.body();
        verifyHeaders(req.header("Wechatpay-Serial"), req.header("Wechatpay-Signature-Type"),
                req.header("Wechatpay-Timestamp"), req.header("Wechatpay-Nonce"),
                req.header("Wechatpay-Signature"), body);
        JsonNode env;
        try {
            env = JSON.readTree(body);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid wechat_pay notify JSON", e);
        }
        String eventType = env.path("event_type").asText("");
        if (!"TRANSACTION.SUCCESS".equals(eventType) && !"REFUND.SUCCESS".equals(eventType)) {
            return Notification.EMPTY;
        }
        JsonNode res = env.path("resource");
        if (!"AEAD_AES_256_GCM".equals(res.path("algorithm").asText(""))) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "unexpected wechat_pay algorithm");
        }
        byte[] plain;
        try {
            byte[] cipherBytes = Base64.getDecoder().decode(res.path("ciphertext").asText(""));
            byte[] nonce = res.path("nonce").asText("").getBytes(StandardCharsets.UTF_8);
            byte[] aad = res.path("associated_data").asText("").getBytes(StandardCharsets.UTF_8);
            if (nonce.length != 12) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "bad nonce length");
            }
            plain = ChannelCrypto.aesGcmDecrypt(cfg.apiV3Key(), nonce, cipherBytes, aad);
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "wechat_pay decrypt failed", e);
        }
        if ("REFUND.SUCCESS".equals(eventType)) {
            JsonNode ref;
            try {
                ref = JSON.readTree(plain);
            } catch (IOException e) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid refund resource", e);
            }
            if (!cfg.mchId().equals(ref.path("mchid").asText("")) || ref.path("transaction_id").asText("").isEmpty()) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "refund mchid or txn missing");
            }
            if (!"SUCCESS".equals(ref.path("refund_status").asText(""))) {
                return Notification.EMPTY;
            }
            Instant at = parseOptionalRfc3339(ref.path("success_time").asText(""));
            if (at == null) {
                at = clock.instant();
            }
            return new Notification(null, List.of(new Revocation(
                    ref.path("transaction_id").asText(""),
                    ref.path("out_trade_no").asText(""),
                    ref.path("out_refund_no").asText(""),
                    true, at, "REFUND.SUCCESS", null)), List.of());
        }
        JsonNode txn;
        try {
            txn = JSON.readTree(plain);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid txn resource", e);
        }
        if (!cfg.appId().equals(txn.path("appid").asText(""))
                || !cfg.mchId().equals(txn.path("mchid").asText(""))
                || !"SUCCESS".equals(txn.path("trade_state").asText(""))) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "wechat_pay txn identity mismatch");
        }
        try {
            return new Notification(toPayment(txn), List.of(), List.of());
        } catch (ChannelException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, e.getMessage(), e);
        }
    }

    @Override
    public AckResponse ack(AckKind kind) {
        if (kind == AckKind.OK) {
            return new AckResponse(204, "", "");
        }
        int status = kind == AckKind.REJECTED ? 401 : 500;
        return new AckResponse(status, "application/json", "{\"code\":\"FAIL\",\"message\":\"failed\"}");
    }

    private ChannelCrypto.HttpResult doRequest(String method, String pathAndQuery, byte[] body) throws ChannelException {
        String nonce = ChannelCrypto.randomNonce32();
        String ts = Long.toString(clock.instant().getEpochSecond());
        String bodyStr = body == null ? "" : new String(body, StandardCharsets.UTF_8);
        String canonical = method + "\n" + pathAndQuery + "\n" + ts + "\n" + nonce + "\n" + bodyStr + "\n";
        String sig = ChannelCrypto.signRsaSha256Base64(cfg.merchantKey(), canonical.getBytes(StandardCharsets.UTF_8));
        String auth = String.format("WECHATPAY2-SHA256-RSA2048 mchid=\"%s\",nonce_str=\"%s\",signature=\"%s\",timestamp=\"%s\",serial_no=\"%s\"",
                cfg.mchId(), nonce, sig, ts, cfg.merchantSerialNo());

        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(endpoint + pathAndQuery))
                .timeout(Duration.ofSeconds(6))
                .header("Authorization", auth)
                .header("Accept", "application/json")
                .header("User-Agent", "mmorpg-payment/1")
                .header("Wechatpay-Serial", cfg.platformKeyId());
        if (body != null) {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        ChannelCrypto.HttpResult res = ChannelCrypto.send(http, rb.build());
        if (res.status() / 100 == 2 && res.body().length > 0) {
            verifyHeaders(res.header("Wechatpay-Serial"), res.header("Wechatpay-Signature-Type"),
                    res.header("Wechatpay-Timestamp"), res.header("Wechatpay-Nonce"),
                    res.header("Wechatpay-Signature"), res.body());
        }
        return res;
    }

    private void verifyHeaders(String serial, String sigType, String timestamp, String nonce, String signature, byte[] body)
            throws ChannelException {
        if (!cfg.platformKeyId().equals(serial)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "unknown Wechatpay-Serial");
        }
        if (sigType != null && !sigType.isEmpty() && !"WECHATPAY2-SHA256-RSA2048".equals(sigType)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "unsupported Wechatpay-Signature-Type");
        }
        if (isBlank(timestamp) || isBlank(nonce) || isBlank(signature)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "missing Wechatpay signature headers");
        }
        long sec;
        try {
            sec = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid Wechatpay timestamp", e);
        }
        Duration skew = Duration.between(Instant.ofEpochSecond(sec), clock.instant()).abs();
        if (skew.compareTo(CLOCK_SKEW) > 0) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "Wechatpay timestamp outside skew");
        }
        String msg = timestamp + "\n" + nonce + "\n" + new String(body, StandardCharsets.UTF_8) + "\n";
        if (!ChannelCrypto.verifyRsaSha256Base64(cfg.platformKey(), msg.getBytes(StandardCharsets.UTF_8), signature)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "Wechatpay signature rejected");
        }
    }

    private Payment toPayment(JsonNode txn) throws ChannelException {
        String txnId = txn.path("transaction_id").asText("");
        long total = txn.path("amount").path("total").asLong(0L);
        String currency = txn.path("amount").path("currency").asText("");
        if (txnId.isEmpty() || total <= 0 || !"CNY".equals(currency)) {
            throw new ChannelException(ChannelException.Kind.OTHER, "wechat_pay transaction lacks fields");
        }
        String st = txn.path("success_time").asText("");
        Instant paidAt = clock.instant();
        if (!st.isEmpty()) {
            Instant parsed = parseOptionalRfc3339(st);
            if (parsed == null) {
                throw new ChannelException(ChannelException.Kind.OTHER, "wechat_pay success_time invalid");
            }
            paidAt = parsed;
        }
        return new Payment(txnId, txn.path("out_trade_no").asText(""), "", total, "CNY", paidAt,
                "", false, 0L, "", "", "", false, false, "", "");
    }

    private static Instant parseOptionalRfc3339(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String errorCode(byte[] raw) {
        try {
            String code = JSON.readTree(raw).path("code").asText("");
            return ERROR_CODE_PATTERN.matcher(code).matches() ? code : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static byte[] writeJson(Object obj) throws ChannelException {
        try {
            return JSON.writeValueAsBytes(obj);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "json encode failed", e);
        }
    }

    private static JsonNode readJson(byte[] raw, String err) throws ChannelException {
        try {
            return JSON.readTree(raw);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, err, e);
        }
    }

    private static String urlPart(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
