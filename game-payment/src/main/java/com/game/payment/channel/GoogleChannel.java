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
import com.game.payment.channel.ChannelTypes.RefundState;
import com.game.payment.channel.ChannelTypes.Revocation;
import com.game.proto.payment.GooglePlayParams;
import com.game.proto.payment.PayChannel;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Google Play 一次性消耗型商品（对齐 Go {@code google.go}）：
 * <ul>
 *   <li>服务账号 OAuth2 令牌锁外拉取并单飞合并，401 自动清缓存重试一次；</li>
 *   <li>Pub/Sub OIDC 公钥 JWKS 锁外拉取 + 单飞合并 + 60 秒最小刷新冷却（2026-11-20 审计修复，防随机 kid 触发 JWKS DoS）；</li>
 *   <li>支持 ProductPurchaseV2 核实、consume 收尾、按 orderId 退款、作废购买历史游标补漏（窗口减半推进）。</li>
 * </ul>
 */
public final class GoogleChannel implements PaymentChannel, PaymentChannel.ReceiptChannel,
        PaymentChannel.Finalizer, PaymentChannel.HistoryPoller {

    private static final String DEFAULT_API_BASE = "https://androidpublisher.googleapis.com";
    private static final String DEFAULT_TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String DEFAULT_CERTS_URL = "https://www.googleapis.com/oauth2/v3/certs";
    private static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";
    public static final Duration CERTS_MIN_REFETCH = Duration.ofMinutes(1);
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Config(
            String packageName,
            String clientEmail,
            RSAPrivateKey privateKey,
            String pushAudience,
            String pushServiceAccount,
            String apiBase,
            String tokenUrl,
            String certsUrl,
            HttpClient http,
            Clock clock) {}

    private final Config cfg;
    private final String apiBase;
    private final String tokenUrl;
    private final String certsUrl;
    private final HttpClient http;
    private final Clock clock;

    private final ReentrantLock tokenLock = new ReentrantLock();
    private String accessToken = "";
    private Instant tokenExpiry = Instant.EPOCH;
    private CompletableFuture<TokenResult> tokenInflight;

    private final ReentrantLock certsLock = new ReentrantLock();
    private Map<String, RSAPublicKey> certs = Map.of();
    private Instant certsExpiry = Instant.EPOCH;
    private Instant certsFetchedAt = Instant.EPOCH;
    private CompletableFuture<CertsResult> certsInflight;

    private record TokenResult(String token, Instant expiry, ChannelException error) {}
    private record CertsResult(Map<String, RSAPublicKey> keys, ChannelException error) {}

    public GoogleChannel(Config cfg) {
        if (isBlank(cfg.packageName()) || isBlank(cfg.clientEmail()) || cfg.privateKey() == null) {
            throw new IllegalArgumentException("payment channel: google needs PackageName and service account");
        }
        if (isBlank(cfg.pushAudience()) || isBlank(cfg.pushServiceAccount())) {
            throw new IllegalArgumentException("payment channel: google needs PushAudience and PushServiceAccount");
        }
        this.cfg = cfg;
        this.apiBase = (isBlank(cfg.apiBase()) ? DEFAULT_API_BASE : cfg.apiBase()).replaceAll("/+$", "");
        this.tokenUrl = isBlank(cfg.tokenUrl()) ? DEFAULT_TOKEN_URL : cfg.tokenUrl();
        this.certsUrl = isBlank(cfg.certsUrl()) ? DEFAULT_CERTS_URL : cfg.certsUrl();
        this.http = cfg.http() == null ? ChannelCrypto.newHttpClient() : cfg.http();
        this.clock = cfg.clock() == null ? Clock.systemUTC() : cfg.clock();
    }

    public static String transactionId(String purchaseToken) {
        return ChannelCrypto.sha256Hex(purchaseToken);
    }

    @Override public PayChannel id() { return PayChannel.PAY_CHANNEL_GOOGLE; }
    @Override public String name() { return ChannelTypes.NAME_GOOGLE; }
    @Override public boolean supports(String currency) { return currency != null && !currency.isEmpty(); }
    @Override public boolean needsProduct() { return true; }
    @Override public void close(String outTradeNo) {}

    @Override
    public String accountRef(long playerId) {
        return ChannelCrypto.sha256Hex("account:" + Long.toUnsignedString(playerId));
    }

    @Override
    public Prepared prepare(Order order, Ref cached) throws ChannelException {
        if (isBlank(order.productId())) {
            throw new ChannelException(ChannelException.Kind.OTHER, "google needs productId");
        }
        return new Prepared(ClientParams.ofGoogle(GooglePlayParams.newBuilder()
                .setProductId(order.productId())
                .setObfuscatedAccountId(accountRef(order.playerId()))
                .setObfuscatedProfileId(order.orderId())
                .build()));
    }

    @Override
    public QueryResult query(QueryRequest req) throws ChannelException {
        throw new ChannelException(ChannelException.Kind.QUERY_UNSUPPORTED, "google cannot be queried by out_trade_no");
    }

    @Override
    public QueryResult verify(Receipt receipt) throws ChannelException {
        PurchaseFetch fetched;
        try {
            fetched = fetchPurchase(receipt.purchaseToken());
        } catch (ChannelException e) {
            if (e.kind() == ChannelException.Kind.CALL_ERROR
                    && (e.status() == 400 || e.status() == 404 || e.status() == 410)) {
                return QueryResult.closed();
            }
            throw e;
        }
        JsonNode items = fetched.root().path("productLineItem");
        if (!items.isArray() || items.size() != 1) {
            throw new ChannelException(ChannelException.Kind.OTHER, "google purchase must carry 1 productLineItem");
        }
        JsonNode line = items.get(0);
        String lineProductId = line.path("productId").asText("");
        if (!isBlank(receipt.productId()) && !lineProductId.equals(receipt.productId())) {
            return QueryResult.closed();
        }
        JsonNode details = line.path("productOfferDetails");
        Instant paidAt = clock.instant();
        String completed = fetched.root().path("purchaseCompletionTime").asText("");
        if (!completed.isEmpty()) {
            try {
                paidAt = Instant.parse(completed);
            } catch (DateTimeParseException ignored) {
                // fallback clock
            }
        }
        String orderId = fetched.root().path("obfuscatedExternalProfileId").asText("").toLowerCase(Locale.ROOT);
        long qty = details.path("quantity").asLong(0L);
        String anomaly = "";
        if (qty > 1L) {
            anomaly = "google purchase quantity is " + qty;
        }
        if (orderId.isEmpty()) {
            anomaly = "google purchase carries no obfuscatedExternalProfileId";
        }
        boolean needsFinalize = !"CONSUMPTION_STATE_CONSUMED".equals(details.path("consumptionState").asText(""));
        boolean isTest = fetched.root().has("testPurchaseContext") && !fetched.root().get("testPurchaseContext").isNull();
        Payment p = new Payment(
                transactionId(receipt.purchaseToken()),
                "",
                orderId,
                0L,
                "",
                paidAt,
                lineProductId,
                true,
                0L,
                "",
                receipt.purchaseToken(),
                fetched.root().path("orderId").asText(""),
                needsFinalize,
                isTest,
                fetched.root().path("obfuscatedExternalAccountId").asText(""),
                anomaly);
        String state = fetched.root().path("purchaseStateContext").path("purchaseState").asText("");
        return switch (state) {
            case "PURCHASED" -> {
                long refundable = details.path("refundableQuantity").asLong(qty);
                if (qty > 0 && refundable < qty) {
                    yield QueryResult.refundedOutside(p);
                }
                yield QueryResult.paid(p);
            }
            case "PENDING" -> QueryResult.notPaid();
            case "CANCELLED" -> QueryResult.closed(p);
            default -> throw new ChannelException(ChannelException.Kind.OTHER, "google unknown purchaseState: " + state);
        };
    }

    @Override
    public void finalizePurchase(String productId, String purchaseToken) throws ChannelException {
        String path = "/purchases/products/" + enc(productId) + "/tokens/" + enc(purchaseToken) + ":consume";
        ChannelCrypto.HttpResult res = apiCall("POST", path, null);
        if (res.status() / 100 == 2) {
            return;
        }
        try {
            JsonNode bought = fetchPurchase(purchaseToken).root();
            JsonNode items = bought.path("productLineItem");
            if (items.isArray() && items.size() == 1
                    && "CONSUMPTION_STATE_CONSUMED".equals(
                    items.get(0).path("productOfferDetails").path("consumptionState").asText(""))) {
                return;
            }
        } catch (ChannelException ignored) {
            // report original consume error
        }
        throw new ChannelException(name(), "consume", res.status(), "");
    }

    @Override
    public RefundResult refund(RefundRequest req) throws ChannelException {
        if (isBlank(req.channelOrderId())) {
            return new RefundResult(RefundState.FAILED, "", null, "NO_ORDER_ID");
        }
        try {
            JsonNode bought = fetchPurchase(req.channelToken()).root();
            if ("CANCELLED".equals(bought.path("purchaseStateContext").path("purchaseState").asText(""))) {
                return new RefundResult(RefundState.SUCCEEDED, req.outRefundNo(), clock.instant(), "");
            }
        } catch (ChannelException ignored) {
            // proceed to refund endpoint
        }
        ChannelCrypto.HttpResult res = apiCall("POST", "/orders/" + enc(req.channelOrderId()) + ":refund?revoke=false", null);
        if (res.status() / 100 == 2) {
            return new RefundResult(RefundState.SUCCEEDED, req.outRefundNo(), clock.instant(), "");
        }
        if (res.status() == 429 || res.status() >= 500 || res.status() == 401) {
            throw new ChannelException(name(), "refund", res.status(), "");
        }
        return new RefundResult(RefundState.FAILED, "", null, "HTTP_" + res.status());
    }

    @Override
    public Notification parseNotification(HttpNotificationRequest req) throws ChannelException {
        verifyPushToken(req.header("Authorization"));
        JsonNode env;
        try {
            env = JSON.readTree(req.body() == null ? new byte[0] : req.body());
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid pubsub envelope", e);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(env.path("message").path("data").asText(""));
        } catch (IllegalArgumentException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid pubsub data base64", e);
        }
        JsonNode note;
        try {
            note = JSON.readTree(decoded);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid DeveloperNotification JSON", e);
        }
        if (!cfg.packageName().equals(note.path("packageName").asText(""))) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "google packageName mismatch");
        }
        Instant at = clock.instant();
        String eventMs = note.path("eventTimeMillis").asText("");
        if (!eventMs.isEmpty()) {
            try {
                long ms = Long.parseLong(eventMs);
                if (ms > 0) at = Instant.ofEpochMilli(ms);
            } catch (NumberFormatException ignored) {
                // keep clock
            }
        }
        JsonNode oneTime = note.get("oneTimeProductNotification");
        if (oneTime != null && oneTime.isObject() && !oneTime.path("purchaseToken").asText("").isEmpty()) {
            return new Notification(null, List.of(), List.of(
                    new Receipt("", oneTime.path("purchaseToken").asText(""), oneTime.path("sku").asText(""))));
        }
        JsonNode voided = note.get("voidedPurchaseNotification");
        if (voided != null && voided.isObject()
                && !voided.path("purchaseToken").asText("").isEmpty()
                && voided.path("productType").asInt(0) == 2) {
            return new Notification(null, List.of(new Revocation(
                    transactionId(voided.path("purchaseToken").asText("")), "", "", true, at,
                    "VOIDED_REFUND_TYPE_" + voided.path("refundType").asInt(0), null)), List.of());
        }
        return Notification.EMPTY;
    }

    @Override
    public HistoryPollResult pollHistory(Instant since) throws ChannelException {
        Instant now = clock.instant();
        Instant floor = now.minus(Duration.ofDays(29));
        Instant start = since.isBefore(floor) ? floor : since;
        Instant until = now.minus(Duration.ofMinutes(1));
        if (!until.isAfter(start)) {
            return new HistoryPollResult(List.of(), start);
        }
        while (true) {
            WindowResult wr = voidedWindow(start, until);
            if (wr.complete()) {
                return new HistoryPollResult(wr.notes(), until);
            }
            Duration half = Duration.between(start, until).dividedBy(2);
            if (half.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new ChannelException(ChannelException.Kind.OTHER, "google voided purchases too dense within 1s");
            }
            until = start.plus(half);
        }
    }

    private record WindowResult(List<Notification> notes, boolean complete) {}

    private WindowResult voidedWindow(Instant since, Instant until) throws ChannelException {
        List<Notification> out = new ArrayList<>();
        String pageToken = "";
        for (int page = 0; page < 50; page++) {
            String query = pageToken.isEmpty()
                    ? "startTime=" + since.toEpochMilli() + "&endTime=" + until.toEpochMilli()
                      + "&maxResults=1000&type=0&includeQuantityBasedPartialRefund=true"
                    : "token=" + enc(pageToken) + "&maxResults=1000";
            ChannelCrypto.HttpResult res = apiCall("GET", "/purchases/voidedpurchases?" + query, null);
            if (res.status() != 200) {
                throw new ChannelException(name(), "voided_purchases", res.status(), "");
            }
            JsonNode list;
            try {
                list = JSON.readTree(res.body());
            } catch (IOException e) {
                throw new ChannelException(ChannelException.Kind.OTHER, "google voided list unreadable", e);
            }
            for (JsonNode v : list.path("voidedPurchases")) {
                Instant at = until;
                String msStr = v.path("voidedTimeMillis").asText("");
                if (!msStr.isEmpty()) {
                    try {
                        long ms = Long.parseLong(msStr);
                        if (ms > 0) at = Instant.ofEpochMilli(ms);
                    } catch (NumberFormatException ignored) {
                        // fallback until
                    }
                }
                String tok = v.path("purchaseToken").asText("");
                if (!tok.isEmpty()) {
                    out.add(new Notification(null, List.of(new Revocation(
                            transactionId(tok), "", "", true, at, "VOIDED", null)), List.of()));
                }
            }
            String next = list.path("tokenPagination").path("nextPageToken").asText("");
            if (next.isEmpty()) {
                return new WindowResult(out, true);
            }
            pageToken = next;
        }
        return new WindowResult(List.of(), false);
    }

    @Override
    public AckResponse ack(AckKind kind) {
        return new AckResponse(kind == AckKind.OK ? 204 : 500, "text/plain", "");
    }

    // ---- OIDC 验签 + JWKS 锁外单飞与 60s 冷却 ----

    private void verifyPushToken(String authorization) throws ChannelException {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "missing bearer token");
        }
        String token = authorization.substring(7);
        ChannelCrypto.JwsParts parts = ChannelCrypto.splitJws(token);
        JsonNode header;
        JsonNode claims;
        try {
            header = JSON.readTree(parts.header());
            claims = JSON.readTree(parts.payload());
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid OIDC JWT JSON", e);
        }
        String alg = header.path("alg").asText("");
        String kid = header.path("kid").asText("");
        if (!"RS256".equals(alg) || kid.isEmpty()) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "OIDC token not RS256 with kid");
        }
        RSAPublicKey key = certKey(kid);
        if (key == null) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "OIDC unknown kid");
        }
        if (!ChannelCrypto.verifyRsaSha256Raw(key, parts.signingInput(), parts.signature())) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "OIDC RS256 signature rejected");
        }
        String iss = claims.path("iss").asText("");
        if (!"https://accounts.google.com".equals(iss) && !"accounts.google.com".equals(iss)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "OIDC foreign issuer");
        }
        if (!cfg.pushAudience().equals(claims.path("aud").asText(""))) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "OIDC audience mismatch");
        }
        if (!cfg.pushServiceAccount().equals(claims.path("email").asText("")) || !claims.path("email_verified").asBoolean(false)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "OIDC push service account mismatch");
        }
        if (clock.instant().getEpochSecond() >= claims.path("exp").asLong(0L)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "OIDC token expired");
        }
    }

    /**
     * 锁外拉取 JWKS + 同批未命中单飞合并 + 距上次成功拉取不足 60 秒遇未知 kid 直接返 null 防刷新 DoS。
     */
    RSAPublicKey certKey(String kid) throws ChannelException {
        Instant now = clock.instant();
        CompletableFuture<CertsResult> waitOn = null;
        CompletableFuture<CertsResult> leader = null;
        certsLock.lock();
        try {
            if (now.isBefore(certsExpiry)) {
                RSAPublicKey hit = certs.get(kid);
                if (hit != null) {
                    return hit;
                }
                if (!certsFetchedAt.equals(Instant.EPOCH)
                        && Duration.between(certsFetchedAt, now).compareTo(CERTS_MIN_REFETCH) < 0) {
                    return null;
                }
            }
            if (certsInflight != null) {
                waitOn = certsInflight;
            } else {
                leader = new CompletableFuture<>();
                certsInflight = leader;
            }
        } finally {
            certsLock.unlock();
        }
        if (waitOn != null) {
            try {
                CertsResult r = waitOn.get(10, TimeUnit.SECONDS);
                if (r.error() != null) throw r.error();
                return r.keys().get(kid);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ChannelException(ChannelException.Kind.TEMP_UNAVAILABLE, "google certs wait interrupted", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new ChannelException(ChannelException.Kind.TEMP_UNAVAILABLE, "google certs wait failed", e);
            }
        }
        CertsResult fetched;
        try {
            fetched = new CertsResult(fetchCerts(), null);
        } catch (ChannelException e) {
            fetched = new CertsResult(Map.of(),
                    new ChannelException(ChannelException.Kind.TEMP_UNAVAILABLE, "google OIDC certs unavailable: " + e.getMessage(), e));
        } catch (RuntimeException e) {
            fetched = new CertsResult(Map.of(),
                    new ChannelException(ChannelException.Kind.TEMP_UNAVAILABLE, "google OIDC certs unavailable", e));
        }
        certsLock.lock();
        try {
            certsInflight = null;
            if (fetched.error() == null) {
                Instant fetchedAt = clock.instant();
                certs = fetched.keys();
                certsFetchedAt = fetchedAt;
                certsExpiry = fetchedAt.plus(Duration.ofHours(1));
            }
            leader.complete(fetched);
        } finally {
            certsLock.unlock();
        }
        if (fetched.error() != null) {
            throw fetched.error();
        }
        return fetched.keys().get(kid);
    }

    private Map<String, RSAPublicKey> fetchCerts() throws ChannelException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(certsUrl)).timeout(Duration.ofSeconds(10)).GET().build();
        ChannelCrypto.HttpResult res = ChannelCrypto.send(http, req);
        if (res.status() != 200) {
            throw new ChannelException(name(), "certs", res.status(), "");
        }
        JsonNode root;
        try {
            root = JSON.readTree(res.body());
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "google certs unreadable", e);
        }
        Map<String, RSAPublicKey> out = new HashMap<>();
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            for (JsonNode k : root.path("keys")) {
                if (!"RSA".equals(k.path("kty").asText(""))) continue;
                String kid = k.path("kid").asText("");
                if (kid.isEmpty()) continue;
                try {
                    byte[] nBytes = Base64.getUrlDecoder().decode(k.path("n").asText(""));
                    byte[] eBytes = Base64.getUrlDecoder().decode(k.path("e").asText(""));
                    if (eBytes.length == 0 || eBytes.length > 4) continue;
                    RSAPublicKey pub = (RSAPublicKey) kf.generatePublic(
                            new RSAPublicKeySpec(new BigInteger(1, nBytes), new BigInteger(1, eBytes)));
                    out.put(kid, pub);
                } catch (IllegalArgumentException | GeneralSecurityException ignored) {
                    // skip malformed JWK entry
                }
            }
        } catch (GeneralSecurityException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "RSA KeyFactory unavailable", e);
        }
        if (out.isEmpty()) {
            throw new ChannelException(ChannelException.Kind.OTHER, "google certs contain no usable RSA keys");
        }
        return Map.copyOf(out);
    }

    // ---- OAuth2 token & androidpublisher API ----

    private record PurchaseFetch(JsonNode root, int status) {}

    private PurchaseFetch fetchPurchase(String purchaseToken) throws ChannelException {
        if (purchaseToken == null || purchaseToken.isEmpty() || purchaseToken.length() > 1024) {
            throw new ChannelException(ChannelException.Kind.OTHER, "google purchaseToken invalid");
        }
        ChannelCrypto.HttpResult res = apiCall("GET", "/purchases/productsv2/tokens/" + enc(purchaseToken), null);
        if (res.status() != 200) {
            throw new ChannelException(name(), "purchase", res.status(), "");
        }
        try {
            return new PurchaseFetch(JSON.readTree(res.body()), res.status());
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "google purchase answer unreadable", e);
        }
    }

    private ChannelCrypto.HttpResult apiCall(String method, String path, byte[] body) throws ChannelException {
        String fullUrl = apiBase + "/androidpublisher/v3/applications/" + enc(cfg.packageName()) + path;
        for (int attempt = 0; attempt < 2; attempt++) {
            String tok = accessToken();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(fullUrl))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "Bearer " + tok)
                    .header("Accept", "application/json");
            if (body != null) {
                rb.header("Content-Type", "application/json");
                rb.method(method, HttpRequest.BodyPublishers.ofByteArray(body));
            } else {
                rb.method(method, HttpRequest.BodyPublishers.noBody());
            }
            ChannelCrypto.HttpResult res = ChannelCrypto.send(http, rb.build());
            if (res.status() == 401 && attempt == 0) {
                invalidateToken(tok);
                continue;
            }
            return res;
        }
        throw new ChannelException(ChannelException.Kind.OTHER, "google api retry exhausted");
    }

    private String accessToken() throws ChannelException {
        Instant now = clock.instant();
        CompletableFuture<TokenResult> waitOn = null;
        CompletableFuture<TokenResult> leader = null;
        tokenLock.lock();
        try {
            if (!accessToken.isEmpty() && now.isBefore(tokenExpiry.minus(Duration.ofMinutes(5)))) {
                return accessToken;
            }
            if (tokenInflight != null) {
                waitOn = tokenInflight;
            } else {
                leader = new CompletableFuture<>();
                tokenInflight = leader;
            }
        } finally {
            tokenLock.unlock();
        }
        if (waitOn != null) {
            try {
                TokenResult r = waitOn.get(10, TimeUnit.SECONDS);
                if (r.error() != null) throw r.error();
                return r.token();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ChannelException(ChannelException.Kind.OTHER, "google token interrupted", e);
            } catch (ExecutionException | TimeoutException e) {
                throw new ChannelException(ChannelException.Kind.OTHER, "google token wait failed", e);
            }
        }
        TokenResult res;
        try {
            res = fetchToken();
        } catch (ChannelException e) {
            res = new TokenResult("", Instant.EPOCH, e);
        } catch (RuntimeException e) {
            res = new TokenResult("", Instant.EPOCH, new ChannelException(ChannelException.Kind.OTHER, e.getMessage(), e));
        }
        tokenLock.lock();
        try {
            tokenInflight = null;
            if (res.error() == null) {
                accessToken = res.token();
                tokenExpiry = res.expiry();
            }
            leader.complete(res);
        } finally {
            tokenLock.unlock();
        }
        if (res.error() != null) {
            throw res.error();
        }
        return res.token();
    }

    private TokenResult fetchToken() throws ChannelException {
        Instant now = clock.instant();
        String assertion = ChannelCrypto.signJwtRs256(
                Map.of("alg", "RS256", "typ", "JWT"),
                Map.of("iss", cfg.clientEmail(), "scope", SCOPE, "aud", tokenUrl,
                        "iat", now.getEpochSecond(), "exp", now.plus(Duration.ofHours(1)).getEpochSecond()),
                cfg.privateKey());
        String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:jwt-bearer") + "&assertion=" + enc(assertion);
        HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        ChannelCrypto.HttpResult res = ChannelCrypto.send(http, req);
        if (res.status() != 200) {
            throw new ChannelException(name(), "token", res.status(), "");
        }
        JsonNode out;
        try {
            out = JSON.readTree(res.body());
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "google token unreadable", e);
        }
        String tok = out.path("access_token").asText("");
        long expiresIn = out.path("expires_in").asLong(0L);
        if (tok.isEmpty() || expiresIn <= 0) {
            throw new ChannelException(ChannelException.Kind.OTHER, "google token answer unusable");
        }
        return new TokenResult(tok, now.plusSeconds(expiresIn), null);
    }

    private void invalidateToken(String bad) {
        tokenLock.lock();
        try {
            if (accessToken.equals(bad)) {
                accessToken = "";
                tokenExpiry = Instant.EPOCH;
            }
        } finally {
            tokenLock.unlock();
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
