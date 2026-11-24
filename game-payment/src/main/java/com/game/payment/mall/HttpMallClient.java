package com.game.payment.mall;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.payment.store.PaymentKeys;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 商城 HTTP 客户端实现（v1 HMAC-SHA256 签名，与 {@code game-mall} 和 Go {@code mall.Client} 逐字节一致）。
 */
public final class HttpMallClient implements MallClient {

    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String baseUrl;
    private final String subject;
    private final byte[] secretBytes;
    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Clock clock;

    public HttpMallClient(String baseUrl, String subject, String secret, Duration timeout, Clock clock) {
        URI uri = parseBaseUrl(baseUrl);
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("mall: payment subject is empty");
        }
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("mall: payment secret must be at least 32 bytes");
        }
        this.baseUrl = uri.toString().replaceAll("/+$", "");
        this.subject = subject;
        this.secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        this.timeout = timeout == null || timeout.isZero() || timeout.isNegative() ? Duration.ofMillis(2500) : timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.json = new ObjectMapper();
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    private static URI parseBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("mall: base URL is empty");
        }
        URI u = URI.create(raw);
        String scheme = u.getScheme();
        String path = u.getRawPath();
        if (u.getHost() == null || u.getHost().isEmpty()
                || (!"http".equals(scheme) && !"https".equals(scheme))
                || u.getUserInfo() != null || u.getRawQuery() != null || u.getRawFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))) {
            throw new IllegalArgumentException("mall: base URL must be scheme://host[:port] without path, query or user info");
        }
        return u;
    }

    /** 商城 v1 签名：{@code HMAC-SHA256(secret, "v1\nrole\nsubject\nts\nnonce\nMETHOD\npath\nhex(sha256(body))")}。 */
    public static String sign(byte[] secret, String role, String subject, String timestamp, String nonce,
                              String method, String path, byte[] body) {
        String bodyHash = HEX.formatHex(PaymentKeys.sha256(body == null ? new byte[0] : body));
        String canonical = String.join("\n", "v1", role, subject, timestamp, nonce, method, path, bodyHash);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    @Override
    public OrderView orderView(String orderId) throws MallClientException {
        JsonNode root = post("/internal/payments/order-view", Map.of("orderId", orderId), true);
        String id = root.path("orderId").asText("");
        if (!Objects.equals(id, orderId)) {
            throw new MallClientException(MallClientException.Kind.TRANSPORT, 200, "mall: order view answers another order", null);
        }
        return new OrderView(
                id,
                root.path("buyerId").asText(""),
                root.path("skuId").asLong(0L),
                root.path("quantity").asInt(0),
                root.path("amountMinor").asLong(0L),
                root.path("currency").asText(""),
                root.path("state").asText(""),
                root.path("paymentDeadlineMs").asLong(0L));
    }

    @Override
    public PaymentOutcome recordPayment(PaymentReport payment) throws MallClientException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", payment.orderId());
        body.put("channel", payment.channel());
        body.put("transactionId", payment.transactionId());
        body.put("amountMinor", payment.amountMinor());
        body.put("currency", payment.currency());
        JsonNode root = post("/internal/payments", body, true);
        // 兼容新版 {order:{orderId,state}, refundReason} 与旧版直返 Order 视图
        JsonNode orderNode = root.has("order") && root.get("order").isObject() ? root.get("order") : root;
        String returnedOrderId = orderNode.path("orderId").asText("");
        String state = orderNode.path("state").asText("");
        if (!Objects.equals(returnedOrderId, payment.orderId()) || state.isEmpty()) {
            throw new MallClientException(MallClientException.Kind.TRANSPORT, 200, "mall: payment answer lacks order", null);
        }
        String refundReason = "";
        JsonNode reasonNode = root.get("refundReason");
        if (reasonNode != null && !reasonNode.isNull()) {
            refundReason = reasonNode.asText("");
            if (refundReason.isEmpty()) {
                throw new MallClientException(MallClientException.Kind.TRANSPORT, 200, "mall: empty refundReason", null);
            }
        }
        return new PaymentOutcome(state, refundReason);
    }

    @Override
    public PendingRefundPage pendingRefunds(String cursor) throws MallClientException {
        Object body = (cursor == null || cursor.isEmpty()) ? null : Map.of("cursor", cursor);
        JsonNode root = post("/internal/refunds/poll", body, true);
        List<PendingRefund> entries = new ArrayList<>();
        String nextCursor = "";
        JsonNode arr = root.isArray() ? root : root.path("entries");
        if (arr.isArray()) {
            for (JsonNode n : arr) {
                entries.add(new PendingRefund(
                        n.path("orderId").asText(""),
                        n.path("channel").asText(""),
                        n.path("transactionId").asText(""),
                        n.path("amountMinor").asLong(0L),
                        n.path("currency").asText(""),
                        n.path("reason").asText("")));
            }
        }
        if (root.isObject() && root.hasNonNull("nextCursor")) {
            nextCursor = root.get("nextCursor").asText("");
        }
        return new PendingRefundPage(entries, nextCursor);
    }

    @Override
    public void reportRefund(RefundReceipt refund) throws MallClientException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", refund.orderId());
        body.put("channel", refund.channel());
        body.put("paymentTransactionId", refund.paymentTransactionId());
        body.put("transactionId", refund.transactionId());
        body.put("amountMinor", refund.amountMinor());
        body.put("currency", refund.currency());
        post("/internal/refunds/receipt", body, false);
    }

    @Override
    public void closeRefund(RefundClosure closure) throws MallClientException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", closure.orderId());
        body.put("channel", closure.channel());
        body.put("paymentTransactionId", closure.paymentTransactionId());
        body.put("reason", closure.reason());
        post("/internal/refunds/close", body, false);
    }

    @Override
    public int pushRevision(RevisionReport revision) throws MallClientException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", revision.orderId());
        body.put("channel", revision.channel());
        body.put("transactionId", revision.transactionId());
        body.put("revision", revision.revision());
        body.put("fact", revision.fact());
        JsonNode root = post("/internal/payments/revision", body, true);
        if (!root.has("revision") || !root.get("revision").isIntegralNumber()) {
            throw new MallClientException(MallClientException.Kind.TRANSPORT, 200, "mall: revision answer has no revision", null);
        }
        return root.get("revision").asInt();
    }

    private JsonNode post(String path, Object input, boolean parseJson) throws MallClientException {
        byte[] body;
        try {
            body = input == null ? new byte[0] : json.writeValueAsBytes(input);
        } catch (IOException e) {
            throw new MallClientException(MallClientException.Kind.TRANSPORT, 0, "mall: encode failed", e);
        }
        byte[] nonceRaw = new byte[16];
        RANDOM.nextBytes(nonceRaw);
        String nonce = HEX.formatHex(nonceRaw);
        String stamp = Long.toString(clock.millis());
        String sig = sign(secretBytes, "payment", subject, stamp, nonce, "POST", path, body);

        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("X-Commerce-Subject", subject)
                .header("X-Commerce-Timestamp", stamp)
                .header("X-Commerce-Nonce", nonce)
                .header("X-Commerce-Signature", sig)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MallClientException(MallClientException.Kind.TRANSPORT, 0, "mall: interrupted", e);
        } catch (IOException e) {
            throw new MallClientException(MallClientException.Kind.TRANSPORT, 0, "mall: request failed", e);
        }
        byte[] raw;
        try (InputStream in = resp.body()) {
            raw = in.readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new MallClientException(MallClientException.Kind.TRANSPORT, resp.statusCode(), "mall: read body failed", e);
        }
        if (raw.length > MAX_BODY_BYTES) {
            throw new MallClientException(MallClientException.Kind.TRANSPORT, resp.statusCode(), "mall: answer is too large", null);
        }
        int code = resp.statusCode();
        switch (code) {
            case 200 -> {
                if (!parseJson) {
                    return null;
                }
                try {
                    return json.readTree(raw);
                } catch (IOException e) {
                    throw new MallClientException(MallClientException.Kind.TRANSPORT, 200, "mall: answer is not readable", e);
                }
            }
            case 400 -> throw new MallClientException(MallClientException.Kind.INVALID, 400, "mall: invalid (400)", null);
            case 404 -> throw new MallClientException(MallClientException.Kind.NOT_FOUND, 404, "mall: not found (404)", null);
            case 409 -> throw new MallClientException(MallClientException.Kind.CONFLICT, 409, "mall: conflict (409)", null);
            default -> throw new MallClientException(MallClientException.Kind.HTTP_STATUS, code, "mall: HTTP " + code, null);
        }
    }
}
