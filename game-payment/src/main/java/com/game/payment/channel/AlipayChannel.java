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
import com.game.proto.payment.AlipayAppPayParams;
import com.game.proto.payment.PayChannel;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 支付宝开放平台 RSA2 公钥模式 APP 支付（对齐 Go {@code alipay.go}）。
 */
public final class AlipayChannel implements PaymentChannel {

    private static final String DEFAULT_GATEWAY = "https://openapi.alipay.com/gateway.do";
    private static final DateTimeFormatter ALIPAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Config(
            String appId,
            RSAPrivateKey appKey,
            RSAPublicKey alipayKey,
            String sellerId,
            String gateway,
            String notifyUrl,
            String subject,
            HttpClient http,
            Clock clock) {}

    private final Config cfg;
    private final String gateway;
    private final HttpClient http;
    private final Clock clock;

    public AlipayChannel(Config cfg) {
        if (!ChannelCrypto.allDigits(cfg.appId())) {
            throw new IllegalArgumentException("payment channel: alipay AppID must be numeric");
        }
        if (cfg.appKey() == null || cfg.alipayKey() == null) {
            throw new IllegalArgumentException("payment channel: alipay needs app private key and Alipay public key");
        }
        if (isBlank(cfg.notifyUrl()) || cfg.notifyUrl().contains("?") || cfg.notifyUrl().contains("#")) {
            throw new IllegalArgumentException("payment channel: alipay notify URL is invalid");
        }
        if (isBlank(cfg.subject()) || cfg.subject().codePointCount(0, cfg.subject().length()) > 256
                || cfg.subject().contains("/") || cfg.subject().contains("=") || cfg.subject().contains("&")) {
            throw new IllegalArgumentException("payment channel: alipay subject invalid");
        }
        if (cfg.sellerId() != null && !cfg.sellerId().isEmpty() && !ChannelCrypto.allDigits(cfg.sellerId())) {
            throw new IllegalArgumentException("payment channel: alipay SellerID must be numeric");
        }
        this.cfg = cfg;
        this.gateway = isBlank(cfg.gateway()) ? DEFAULT_GATEWAY : cfg.gateway();
        this.http = cfg.http() == null ? ChannelCrypto.newHttpClient() : cfg.http();
        this.clock = cfg.clock() == null ? Clock.systemUTC() : cfg.clock();
    }

    @Override public PayChannel id() { return PayChannel.PAY_CHANNEL_ALIPAY; }
    @Override public String name() { return ChannelTypes.NAME_ALIPAY; }
    @Override public boolean supports(String currency) { return "CNY".equals(currency); }
    @Override public boolean needsProduct() { return false; }

    @Override
    public Prepared prepare(Order order, Ref cached) throws ChannelException {
        if (!supports(order.currency())) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay only takes CNY");
        }
        Map<String, Object> biz = new LinkedHashMap<>();
        biz.put("out_trade_no", order.outTradeNo());
        biz.put("total_amount", ChannelCrypto.formatYuan(order.amountMinor()));
        biz.put("subject", cfg.subject());
        biz.put("product_code", "QUICK_MSECURITY_PAY");
        biz.put("time_expire", order.expireAt().atOffset(ChannelCrypto.CHINA_ZONE).format(ALIPAY_TIME));
        Map<String, String> params = commonParams("alipay.trade.app.pay", writeJson(biz));
        params.put("notify_url", cfg.notifyUrl());
        signParams(params);
        return new Prepared(ClientParams.ofAlipay(AlipayAppPayParams.newBuilder()
                .setOrderString(encodeParams(params)).build()));
    }

    @Override
    public QueryResult query(QueryRequest req) throws ChannelException {
        JsonNode ans = callGateway("alipay.trade.query", Map.of("out_trade_no", req.outTradeNo()));
        String code = ans.path("code").asText("");
        String subCode = ans.path("sub_code").asText("");
        if ("40004".equals(code) && "ACQ.TRADE_NOT_EXIST".equals(subCode)) {
            return QueryResult.notPaid();
        }
        if (!"10000".equals(code)) {
            throw new ChannelException(name(), "query", 200, code + "/" + subCode);
        }
        if (!req.outTradeNo().equals(ans.path("out_trade_no").asText(""))) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay query order mismatch");
        }
        String status = ans.path("trade_status").asText("");
        return switch (status) {
            case "WAIT_BUYER_PAY" -> QueryResult.notPaid();
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> QueryResult.paid(toPayment(
                    ans.path("trade_no").asText(""), ans.path("out_trade_no").asText(""),
                    ans.path("total_amount").asText(""), ans.path("send_pay_date").asText("")));
            case "TRADE_CLOSED" -> {
                String sendPayDate = ans.path("send_pay_date").asText("");
                if (sendPayDate.isEmpty()) {
                    yield QueryResult.closed();
                }
                yield QueryResult.refundedOutside(toPayment(
                        ans.path("trade_no").asText(""), ans.path("out_trade_no").asText(""),
                        ans.path("total_amount").asText(""), sendPayDate));
            }
            default -> throw new ChannelException(ChannelException.Kind.OTHER, "alipay unknown trade_status: " + status);
        };
    }

    @Override
    public void close(String outTradeNo) throws ChannelException {
        JsonNode ans = callGateway("alipay.trade.close", Map.of("out_trade_no", outTradeNo));
        String code = ans.path("code").asText("");
        String subCode = ans.path("sub_code").asText("");
        if ("10000".equals(code)
                || ("40004".equals(code) && ("ACQ.TRADE_NOT_EXIST".equals(subCode) || "ACQ.TRADE_STATUS_ERROR".equals(subCode)))) {
            return;
        }
        throw new ChannelException(name(), "close", 200, code + "/" + subCode);
    }

    @Override
    public RefundResult refund(RefundRequest req) throws ChannelException {
        Map<String, Object> qBiz = new LinkedHashMap<>();
        qBiz.put("out_trade_no", req.outTradeNo());
        qBiz.put("out_request_no", req.outRefundNo());
        qBiz.put("query_options", List.of("gmt_refund_pay"));
        JsonNode q = callGateway("alipay.trade.fastpay.refund.query", qBiz);
        String qCode = q.path("code").asText("");
        if (!"10000".equals(qCode)) {
            throw new ChannelException(name(), "refund_query", 200, qCode + "/" + q.path("sub_code").asText(""));
        }
        if ("REFUND_SUCCESS".equals(q.path("refund_status").asText(""))) {
            long refunded;
            try {
                refunded = ChannelCrypto.parseYuan(q.path("refund_amount").asText(""));
            } catch (IllegalArgumentException e) {
                throw new ChannelException(ChannelException.Kind.OTHER, "alipay refund amount invalid", e);
            }
            if (refunded != req.amountMinor()) {
                throw new ChannelException(ChannelException.Kind.OTHER, "alipay refund amount mismatch");
            }
            return new RefundResult(RefundState.SUCCEEDED, req.outRefundNo(),
                    parseOptionalAlipayTime(q.path("gmt_refund_pay").asText("")), "");
        }
        if (!"CNY".equals(req.currency())) {
            return new RefundResult(RefundState.FAILED, "", null, "CURRENCY");
        }
        Map<String, Object> rBiz = new LinkedHashMap<>();
        rBiz.put("out_trade_no", req.outTradeNo());
        rBiz.put("refund_amount", ChannelCrypto.formatYuan(req.amountMinor()));
        rBiz.put("out_request_no", req.outRefundNo());
        rBiz.put("refund_reason", req.reason() == null ? "" : req.reason());
        JsonNode ans = callGateway("alipay.trade.refund", rBiz);
        String code = ans.path("code").asText("");
        String subCode = ans.path("sub_code").asText("");
        if ("10000".equals(code) && "Y".equals(ans.path("fund_change").asText(""))) {
            return new RefundResult(RefundState.SUCCEEDED, req.outRefundNo(),
                    parseOptionalAlipayTime(ans.path("gmt_refund_pay").asText("")), "");
        }
        if ("10000".equals(code)) {
            return new RefundResult(RefundState.PROCESSING, "", null, "FUND_CHANGE_N");
        }
        if ("20000".equals(code) || "ACQ.SYSTEM_ERROR".equals(subCode)) {
            throw new ChannelException(name(), "refund", 200, code + "/" + subCode);
        }
        if ("ACQ.SELLER_BALANCE_NOT_ENOUGH".equals(subCode)) {
            return new RefundResult(RefundState.PROCESSING, "", null, subCode);
        }
        return new RefundResult(RefundState.FAILED, "", null, code + "/" + subCode);
    }

    @Override
    public Notification parseNotification(HttpNotificationRequest req) throws ChannelException {
        String form = req.body() == null ? "" : new String(req.body(), StandardCharsets.UTF_8);
        Map<String, String> params = parseFormSingleValues(form);
        if (!"RSA2".equals(params.get("sign_type")) || isBlank(params.get("sign"))) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "missing RSA2 sign");
        }
        String content = signContent(params, Set.of("sign", "sign_type"));
        if (!ChannelCrypto.verifyRsaSha256Base64(cfg.alipayKey(), content.getBytes(StandardCharsets.UTF_8), params.get("sign"))) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "alipay notify signature rejected");
        }
        if (!cfg.appId().equals(params.get("app_id"))
                || (!isBlank(cfg.sellerId()) && !cfg.sellerId().equals(params.get("seller_id")))) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "alipay app_id or seller_id mismatch");
        }
        Payment payment = null;
        String status = params.getOrDefault("trade_status", "");
        switch (status) {
            case "TRADE_SUCCESS", "TRADE_FINISHED" -> {
                try {
                    payment = toPayment(params.getOrDefault("trade_no", ""), params.getOrDefault("out_trade_no", ""),
                            params.getOrDefault("total_amount", ""), params.getOrDefault("gmt_payment", ""));
                } catch (ChannelException e) {
                    throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, e.getMessage(), e);
                }
            }
            case "TRADE_CLOSED" -> { /* 全额退款引起的关闭由下方 refund_fee 分支产生撤销 */ }
            default -> { return Notification.EMPTY; }
        }
        List<Revocation> revocations = List.of();
        String refundFeeText = params.get("refund_fee");
        String tradeNo = params.getOrDefault("trade_no", "");
        if (!isBlank(refundFeeText) && !tradeNo.isEmpty()) {
            try {
                if (ChannelCrypto.parseYuan(refundFeeText) > 0) {
                    Instant at = parseOptionalAlipayTime(params.getOrDefault("gmt_refund", ""));
                    if (at == null) {
                        at = clock.instant();
                    }
                    revocations = List.of(new Revocation(tradeNo, params.getOrDefault("out_trade_no", ""), "",
                            true, at, "REFUND", null));
                }
            } catch (IllegalArgumentException ignored) {
                // 非正或非法 refund_fee 不产生撤销
            }
        }
        return new Notification(payment, revocations, List.of());
    }

    @Override
    public AckResponse ack(AckKind kind) {
        return new AckResponse(200, "text/plain; charset=utf-8", kind == AckKind.OK ? "success" : "fail");
    }

    // ---- 网关调用与原始应答节点验签 ----

    private JsonNode callGateway(String method, Object biz) throws ChannelException {
        Map<String, String> params = commonParams(method, writeJson(biz));
        signParams(params);
        String body = encodeParams(params);
        HttpRequest req = HttpRequest.newBuilder(URI.create(gateway))
                .timeout(Duration.ofSeconds(6))
                .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        ChannelCrypto.HttpResult res = ChannelCrypto.send(http, req);
        if (res.status() != 200) {
            throw new ChannelException(name(), method, res.status(), "");
        }
        String rawNode = verifyAnswerAndExtractNode(method, new String(res.body(), StandardCharsets.UTF_8));
        try {
            return JSON.readTree(rawNode);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay response node unreadable", e);
        }
    }

    /**
     * 按支付宝官方文档/SDK 规范定位 {@code "<method>_response"} 原始 JSON 对象字节并验签（支持 {@code \/} 替换回落），
     * 防止重新序列化或键重排导致验签偏差，同时拒绝重复节点名。
     */
    String verifyAnswerAndExtractNode(String method, String rawJson) throws ChannelException {
        String nodeName = method.replace('.', '_') + "_response";
        String token = "\"" + nodeName + "\"";
        int first = rawJson.indexOf(token);
        if (first < 0) {
            try {
                JsonNode root = JSON.readTree(rawJson);
                if (root.has("error_response")) {
                    JsonNode err = root.get("error_response");
                    throw new ChannelException(name(), method, 200,
                            err.path("code").asText("") + "/" + err.path("sub_code").asText(""));
                }
            } catch (IOException ignored) {
                // fall through
            }
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay answer has no response node");
        }
        if (rawJson.indexOf(token, first + token.length()) >= 0) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay answer repeats response node");
        }
        String rawNode = extractJsonObjectAfterKey(rawJson, first + token.length());
        if (rawNode == null) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay response node malformed");
        }
        String signature;
        try {
            signature = JSON.readTree(rawJson).path("sign").asText("");
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay answer is not JSON", e);
        }
        if (signature.isEmpty()) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay answer is not signed");
        }
        if (ChannelCrypto.verifyRsaSha256Base64(cfg.alipayKey(), rawNode.getBytes(StandardCharsets.UTF_8), signature)) {
            return rawNode;
        }
        if (rawNode.contains("\\/")
                && ChannelCrypto.verifyRsaSha256Base64(cfg.alipayKey(),
                rawNode.replace("\\/", "/").getBytes(StandardCharsets.UTF_8), signature)) {
            return rawNode;
        }
        throw new ChannelException(ChannelException.Kind.OTHER, "alipay answer signature rejected");
    }

    private static String extractJsonObjectAfterKey(String s, int from) {
        int i = from;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != ':') return null;
        i++;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        if (i >= s.length() || s.charAt(i) != '{') return null;
        int start = i;
        int depth = 0;
        boolean inStr = false;
        boolean escaped = false;
        for (; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inStr = false;
                }
            } else {
                if (c == '"') {
                    inStr = true;
                } else if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return s.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }

    private Map<String, String> commonParams(String method, String bizContent) {
        Map<String, String> m = new TreeMap<>();
        m.put("app_id", cfg.appId());
        m.put("method", method);
        m.put("format", "JSON");
        m.put("charset", "utf-8");
        m.put("sign_type", "RSA2");
        m.put("timestamp", clock.instant().atOffset(ChannelCrypto.CHINA_ZONE).format(ALIPAY_TIME));
        m.put("version", "1.0");
        m.put("biz_content", bizContent);
        return m;
    }

    private void signParams(Map<String, String> params) {
        String content = signContent(params, Set.of("sign"));
        params.put("sign", ChannelCrypto.signRsaSha256Base64(cfg.appKey(), content.getBytes(StandardCharsets.UTF_8)));
    }

    public static String signContent(Map<String, String> params, Set<String> exclude) {
        TreeMap<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getKey() != null && !e.getKey().isEmpty() && e.getValue() != null && !e.getValue().isEmpty()
                    && !exclude.contains(e.getKey())) {
                sorted.put(e.getKey(), e.getValue());
            }
        }
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (!first) sb.append('&');
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static String encodeParams(Map<String, String> params) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : sorted.entrySet()) {
            if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null || e.getValue().isEmpty()) {
                continue;
            }
            if (!first) sb.append('&');
            first = false;
            sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private static Map<String, String> parseFormSingleValues(String body) throws ChannelException {
        Map<String, String> out = new LinkedHashMap<>();
        if (body.isEmpty()) {
            return out;
        }
        for (String pair : body.split("&", -1)) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String kRaw = eq < 0 ? pair : pair.substring(0, eq);
            String vRaw = eq < 0 ? "" : pair.substring(eq + 1);
            String k;
            String v;
            try {
                k = URLDecoder.decode(kRaw, StandardCharsets.UTF_8);
                v = URLDecoder.decode(vRaw, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "bad form encoding", e);
            }
            if (out.put(k, v) != null) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "duplicate form key");
            }
        }
        return out;
    }

    private Payment toPayment(String tradeNo, String outTradeNo, String totalAmount, String paidAtText) throws ChannelException {
        if (isBlank(tradeNo) || isBlank(outTradeNo)) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay lacks trade_no or out_trade_no");
        }
        long amount;
        try {
            amount = ChannelCrypto.parseYuan(totalAmount);
        } catch (IllegalArgumentException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay total_amount invalid", e);
        }
        if (amount <= 0) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay total_amount must be positive");
        }
        Instant at = clock.instant();
        if (!isBlank(paidAtText)) {
            Instant parsed = parseOptionalAlipayTime(paidAtText);
            if (parsed == null) {
                throw new ChannelException(ChannelException.Kind.OTHER, "alipay payment time invalid");
            }
            at = parsed;
        }
        return new Payment(tradeNo, outTradeNo, "", amount, "CNY", at,
                "", false, 0L, "", "", "", false, false, "", "");
    }

    private static Instant parseOptionalAlipayTime(String text) {
        if (isBlank(text)) return null;
        try {
            return LocalDateTime.parse(text, ALIPAY_TIME).toInstant(ChannelCrypto.CHINA_ZONE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String writeJson(Object obj) throws ChannelException {
        try {
            return JSON.writeValueAsString(obj);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "alipay json encode failed", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
