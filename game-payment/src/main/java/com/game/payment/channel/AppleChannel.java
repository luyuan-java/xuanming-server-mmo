package com.game.payment.channel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.payment.channel.ChannelTypes.AckKind;
import com.game.payment.channel.ChannelTypes.AckResponse;
import com.game.payment.channel.ChannelTypes.ChannelException;
import com.game.payment.channel.ChannelTypes.ClientParams;
import com.game.payment.channel.ChannelTypes.HistoryPollResult;
import com.game.payment.channel.ChannelTypes.HttpNotificationRequest;
import com.game.payment.channel.ChannelTypes.Notification;
import com.game.payment.channel.ChannelTypes.Order;
import com.game.payment.channel.ChannelTypes.Payment;
import com.game.payment.channel.ChannelTypes.Prepared;
import com.game.payment.channel.ChannelTypes.QueryRequest;
import com.game.payment.channel.ChannelTypes.QueryResult;
import com.game.payment.channel.ChannelTypes.Receipt;
import com.game.payment.channel.ChannelTypes.Ref;
import com.game.payment.channel.ChannelTypes.RefundRequest;
import com.game.payment.channel.ChannelTypes.RefundResult;
import com.game.payment.channel.ChannelTypes.Revocation;
import com.game.payment.channel.ChannelTypes.TradeState;
import com.game.proto.payment.AppleIapParams;
import com.game.proto.payment.PayChannel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * App Store 内购（StoreKit 2 消耗型，对齐 Go {@code apple.go}，含 2026-11-20 ISO-4217 毫单位折算修复）。
 */
public final class AppleChannel implements PaymentChannel, PaymentChannel.ReceiptChannel, PaymentChannel.HistoryPoller {

    private static final String PRODUCTION_API = "https://api.storekit.apple.com";
    private static final String SANDBOX_API = "https://api.storekit-sandbox.apple.com";
    public static final String LEAF_OID = "1.2.840.113635.100.6.11.1";
    public static final String INTERMEDIATE_OID = "1.2.840.113635.100.6.2.1";
    private static final long NOT_FOUND_CODE = 4040010L;
    private static final int HISTORY_MAX_PAGES = 50;
    private static final Duration HISTORY_KEPT = Duration.ofDays(179);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Set<String> ZERO_DECIMAL_CURRENCIES = Set.of(
            "BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW", "PYG", "RWF", "UGX", "UYI", "VND", "VUV", "XAF", "XOF", "XPF");
    private static final Set<String> THREE_DECIMAL_CURRENCIES = Set.of(
            "BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND");

    public record Config(
            String bundleId,
            long appAppleId,
            String issuerId,
            String keyId,
            ECPrivateKey privateKey,
            List<X509Certificate> rootCertificates,
            boolean allowSandbox,
            String productionApi,
            String sandboxApi,
            HttpClient http,
            Clock clock) {}

    private final Config cfg;
    private final String productionApi;
    private final String sandboxApi;
    private final HttpClient http;
    private final Clock clock;

    public AppleChannel(Config cfg) {
        if (isBlank(cfg.bundleId()) || isBlank(cfg.issuerId()) || isBlank(cfg.keyId())) {
            throw new IllegalArgumentException("payment channel: apple needs BundleID, IssuerID and KeyID");
        }
        if (cfg.privateKey() == null) {
            throw new IllegalArgumentException("payment channel: apple needs .p8 EC private key");
        }
        if (cfg.rootCertificates() == null || cfg.rootCertificates().isEmpty()) {
            throw new IllegalArgumentException("payment channel: apple needs Apple root certificates");
        }
        if (cfg.appAppleId() <= 0) {
            throw new IllegalArgumentException("payment channel: apple needs AppAppleID");
        }
        this.cfg = cfg;
        this.productionApi = (isBlank(cfg.productionApi()) ? PRODUCTION_API : cfg.productionApi()).replaceAll("/+$", "");
        this.sandboxApi = (isBlank(cfg.sandboxApi()) ? SANDBOX_API : cfg.sandboxApi()).replaceAll("/+$", "");
        this.http = cfg.http() == null ? ChannelCrypto.newHttpClient() : cfg.http();
        this.clock = cfg.clock() == null ? Clock.systemUTC() : cfg.clock();
    }

    @Override public PayChannel id() { return PayChannel.PAY_CHANNEL_APPLE; }
    @Override public String name() { return ChannelTypes.NAME_APPLE; }
    @Override public boolean supports(String currency) { return currency != null && !currency.isEmpty(); }
    @Override public boolean needsProduct() { return true; }
    @Override public void close(String outTradeNo) {}
    @Override public RefundResult refund(RefundRequest req) { return RefundResult.UNSUPPORTED; }
    @Override public String accountRef(long playerId) { return ""; }

    @Override
    public Prepared prepare(Order order, Ref cached) throws ChannelException {
        if (isBlank(order.productId())) {
            throw new ChannelException(ChannelException.Kind.OTHER, "apple needs productId");
        }
        return new Prepared(ClientParams.ofApple(AppleIapParams.newBuilder()
                .setProductId(order.productId())
                .setAppAccountToken(order.orderId())
                .build()));
    }

    @Override
    public QueryResult query(QueryRequest req) throws ChannelException {
        throw new ChannelException(ChannelException.Kind.QUERY_UNSUPPORTED, "apple cannot be queried by out_trade_no");
    }

    @Override
    public QueryResult verify(Receipt receipt) throws ChannelException {
        JsonNode txn = fetchTransaction(receipt.transactionId());
        if (txn == null) {
            return QueryResult.closed();
        }
        return classify(txn);
    }

    /**
     * StoreKit 2 固定毫单位（×1000）价格按 ISO-4217 小数位折算成最小货币单位：
     * 0 位小数（JPY/KRW/VND 等）÷1000，3 位小数（BHD/KWD/OMR 等）÷1，常规 2 位小数（CNY/USD/EUR 等）÷10。
     */
    public static long milliunitsToMinor(long priceMilliunits, String currency) {
        if (priceMilliunits <= 0) {
            return 0L;
        }
        String cur = currency == null ? "" : currency.strip().toUpperCase(Locale.ROOT);
        if (ZERO_DECIMAL_CURRENCIES.contains(cur)) {
            return priceMilliunits / 1000L;
        }
        if (THREE_DECIMAL_CURRENCIES.contains(cur)) {
            return priceMilliunits;
        }
        return priceMilliunits / 10L;
    }

    private JsonNode fetchTransaction(String transactionId) throws ChannelException {
        if (transactionId == null || transactionId.isEmpty() || transactionId.length() > 64
                || !ChannelCrypto.allDigits(transactionId)) {
            throw new ChannelException(ChannelException.Kind.OTHER, "apple transactionId must be numeric");
        }
        String path = "/inApps/v1/transactions/" + URLEncoder.encode(transactionId, StandardCharsets.UTF_8);
        List<String> bases = cfg.allowSandbox() ? List.of(productionApi, sandboxApi) : List.of(productionApi);
        for (String base : bases) {
            ChannelCrypto.HttpResult res = apiCall(base, "GET", path, null);
            if (res.status() == 404 && errorCode(res.body()) == NOT_FOUND_CODE) {
                continue;
            }
            if (res.status() != 200) {
                throw new ChannelException(name(), "transaction", res.status(), Long.toString(errorCode(res.body())));
            }
            String signed;
            try {
                signed = JSON.readTree(res.body()).path("signedTransactionInfo").asText("");
            } catch (IOException e) {
                throw new ChannelException(ChannelException.Kind.OTHER, "apple transaction answer unreadable", e);
            }
            if (signed.isEmpty()) {
                throw new ChannelException(ChannelException.Kind.OTHER, "apple transaction lacks signedTransactionInfo");
            }
            return verifyJws(signed);
        }
        return null;
    }

    private QueryResult classify(JsonNode txn) throws ChannelException {
        if (!cfg.bundleId().equals(txn.path("bundleId").asText(""))) {
            throw new ChannelException(ChannelException.Kind.OTHER, "apple transaction bundleId mismatch");
        }
        String env = txn.path("environment").asText("");
        if ("Sandbox".equals(env)) {
            if (!cfg.allowSandbox()) {
                return QueryResult.closed();
            }
        } else if (!"Production".equals(env)) {
            throw new ChannelException(ChannelException.Kind.OTHER, "apple transaction unknown environment");
        }
        String token = txn.path("appAccountToken").asText("");
        String type = txn.path("type").asText("");
        long qty = txn.path("quantity").asLong(0L);
        String anomaly = "";
        if (!"Consumable".equals(type)) {
            anomaly = "apple product type " + type + " is not a consumable";
        } else if (qty != 1L) {
            anomaly = "apple transaction quantity is " + qty;
        } else if (token.isEmpty()) {
            anomaly = "apple transaction carries no appAccountToken";
        }
        String storeCur = txn.path("currency").asText("");
        long storeAmt = milliunitsToMinor(txn.path("price").asLong(0L), storeCur);
        Payment p = new Payment(
                txn.path("transactionId").asText(""),
                "",
                token.toLowerCase(Locale.ROOT),
                0L,
                "",
                Instant.ofEpochMilli(txn.path("purchaseDate").asLong(0L)),
                txn.path("productId").asText(""),
                true,
                storeAmt,
                storeCur,
                "",
                "",
                false,
                "Sandbox".equals(env),
                "",
                anomaly);
        if (txn.path("revocationDate").asLong(0L) > 0L) {
            return QueryResult.refundedOutside(p);
        }
        return QueryResult.paid(p);
    }

    @Override
    public Notification parseNotification(HttpNotificationRequest req) throws ChannelException {
        String signedPayload;
        try {
            signedPayload = JSON.readTree(req.body() == null ? new byte[0] : req.body()).path("signedPayload").asText("");
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid apple notify JSON", e);
        }
        if (signedPayload.isEmpty()) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "missing signedPayload");
        }
        return decodeNotification(signedPayload);
    }

    Notification decodeNotification(String signedPayload) throws ChannelException {
        JsonNode note = verifyJws(signedPayload);
        JsonNode data = note.get("data");
        if (data == null || data.isNull() || !data.isObject()) {
            return Notification.EMPTY;
        }
        if (!cfg.bundleId().equals(data.path("bundleId").asText(""))) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple notify bundleId mismatch");
        }
        String env = data.path("environment").asText("");
        if ("Production".equals(env)) {
            if (data.path("appAppleId").asLong(0L) != cfg.appAppleId()) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple notify appAppleId mismatch");
            }
        } else if ("Sandbox".equals(env)) {
            if (!cfg.allowSandbox()) {
                return Notification.EMPTY;
            }
        } else {
            return Notification.EMPTY;
        }
        String nType = note.path("notificationType").asText("");
        if (!"ONE_TIME_CHARGE".equals(nType) && !"REFUND".equals(nType) && !"REFUND_REVERSED".equals(nType)) {
            return Notification.EMPTY;
        }
        String signedTxn = data.path("signedTransactionInfo").asText("");
        if (signedTxn.isEmpty()) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple notify lacks signedTransactionInfo");
        }
        JsonNode txn = verifyJws(signedTxn);
        QueryResult classified;
        try {
            classified = classify(txn);
        } catch (ChannelException e) {
            return Notification.EMPTY;
        }
        if (classified.payment() == null) {
            return Notification.EMPTY;
        }
        Instant observedAt = Instant.ofEpochMilli(note.path("signedDate").asLong(0L));
        String txnId = txn.path("transactionId").asText("");
        return switch (nType) {
            case "ONE_TIME_CHARGE" -> classified.state() == TradeState.PAID
                    ? new Notification(classified.payment(), List.of(), List.of()) : Notification.EMPTY;
            case "REFUND" -> new Notification(null, List.of(new Revocation(
                    txnId, "", "", true, observedAt, "REFUND", classified.payment())), List.of());
            default -> new Notification(null, List.of(new Revocation(
                    txnId, "", "", false, observedAt, "REFUND_REVERSED", classified.payment())), List.of());
        };
    }

    /** 验签 App Store JWS：alg=ES256、x5c 恰好 3 张证书、叶/中间含 Apple OID、链到已配置根、signedDate 有效。 */
    JsonNode verifyJws(String token) throws ChannelException {
        ChannelCrypto.JwsParts parts = ChannelCrypto.splitJws(token);
        JsonNode header;
        JsonNode payload;
        try {
            header = JSON.readTree(parts.header());
            payload = JSON.readTree(parts.payload());
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple JWS JSON unreadable", e);
        }
        if (!"ES256".equals(header.path("alg").asText(""))) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple JWS alg != ES256");
        }
        JsonNode x5c = header.get("x5c");
        if (x5c == null || !x5c.isArray() || x5c.size() != 3) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple JWS x5c must have 3 certs");
        }
        long signedDateMs = payload.path("signedDate").asLong(0L);
        if (signedDateMs <= 0) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple JWS missing signedDate");
        }
        Date at = new Date(signedDateMs);
        X509Certificate[] certs = new X509Certificate[3];
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (int i = 0; i < 3; i++) {
                byte[] der = Base64.getDecoder().decode(x5c.get(i).asText(""));
                certs[i] = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(der));
            }
        } catch (IllegalArgumentException | GeneralSecurityException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple JWS cert parse failed", e);
        }
        X509Certificate leaf = certs[0];
        X509Certificate intermediate = certs[1];
        if (intermediate.getBasicConstraints() < 0) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple intermediate not CA");
        }
        boolean trusted = false;
        for (X509Certificate root : cfg.rootCertificates()) {
            try {
                intermediate.verify(root.getPublicKey());
                root.checkValidity(at);
                trusted = true;
                break;
            } catch (GeneralSecurityException ignored) {
                // try next configured root
            }
        }
        if (!trusted) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple cert chain untrusted");
        }
        try {
            leaf.verify(intermediate.getPublicKey());
            leaf.checkValidity(at);
            intermediate.checkValidity(at);
        } catch (GeneralSecurityException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple leaf/intermediate invalid", e);
        }
        if (!hasExtension(leaf, LEAF_OID) || !hasExtension(intermediate, INTERMEDIATE_OID)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple certs lack required OIDs");
        }
        if (!(leaf.getPublicKey() instanceof ECPublicKey ecPub)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple leaf key is not ECDSA");
        }
        if (!ChannelCrypto.verifyEs256Raw(ecPub, parts.signingInput(), parts.signature())) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "apple ES256 signature rejected");
        }
        return payload;
    }

    private static boolean hasExtension(X509Certificate cert, String oid) {
        return cert.getExtensionValue(oid) != null;
    }

    @Override
    public HistoryPollResult pollHistory(Instant since) throws ChannelException {
        Instant now = clock.instant();
        Instant floor = now.minus(HISTORY_KEPT);
        Instant start = since.isBefore(floor) ? floor : since;
        Instant until = now.minus(Duration.ofMinutes(1));
        if (!until.isAfter(start)) {
            return new HistoryPollResult(List.of(), start);
        }
        while (true) {
            WindowResult wr = historyWindow(start, until);
            if (wr.complete()) {
                return new HistoryPollResult(wr.notes(), until);
            }
            Duration half = Duration.between(start, until).dividedBy(2);
            if (half.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new ChannelException(ChannelException.Kind.OTHER, "apple notification history too dense within 1s");
            }
            until = start.plus(half);
        }
    }

    private record WindowResult(List<Notification> notes, boolean complete) {}

    private WindowResult historyWindow(Instant since, Instant until) throws ChannelException {
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(Map.of("startDate", since.toEpochMilli(), "endDate", until.toEpochMilli()));
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "apple history body encode failed", e);
        }
        List<Notification> out = new ArrayList<>();
        String pagination = "";
        for (int page = 0; page < HISTORY_MAX_PAGES; page++) {
            String path = "/inApps/v1/notifications/history"
                    + (pagination.isEmpty() ? "" : "?paginationToken=" + URLEncoder.encode(pagination, StandardCharsets.UTF_8));
            ChannelCrypto.HttpResult res = apiCall(productionApi, "POST", path, body);
            if (res.status() != 200) {
                throw new ChannelException(name(), "notification_history", res.status(), Long.toString(errorCode(res.body())));
            }
            JsonNode root;
            try {
                root = JSON.readTree(res.body());
            } catch (IOException e) {
                throw new ChannelException(ChannelException.Kind.OTHER, "apple history unreadable", e);
            }
            for (JsonNode item : root.path("notificationHistory")) {
                Notification note = decodeNotification(item.path("signedPayload").asText(""));
                if (note.payment() != null || !note.revocations().isEmpty()) {
                    out.add(note);
                }
            }
            boolean hasMore = root.path("hasMore").asBoolean(false);
            String nextToken = root.path("paginationToken").asText("");
            if (!hasMore || nextToken.isEmpty()) {
                return new WindowResult(out, true);
            }
            pagination = nextToken;
        }
        return new WindowResult(List.of(), false);
    }

    @Override
    public AckResponse ack(AckKind kind) {
        return new AckResponse(kind == AckKind.OK ? 200 : 500, "text/plain", "");
    }

    private ChannelCrypto.HttpResult apiCall(String base, String method, String path, byte[] body) throws ChannelException {
        long nowSec = clock.instant().getEpochSecond();
        String jwt = ChannelCrypto.signJwtEs256(
                Map.of("alg", "ES256", "kid", cfg.keyId(), "typ", "JWT"),
                Map.of("iss", cfg.issuerId(), "iat", nowSec, "exp", nowSec + 300,
                        "aud", "appstoreconnect-v1", "bid", cfg.bundleId()),
                cfg.privateKey());
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(6))
                .header("Authorization", "Bearer " + jwt)
                .header("Accept", "application/json");
        if (body != null) {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return ChannelCrypto.send(http, rb.build());
    }

    private static long errorCode(byte[] raw) {
        try {
            return JSON.readTree(raw).path("errorCode").asLong(0L);
        } catch (IOException e) {
            return 0L;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
