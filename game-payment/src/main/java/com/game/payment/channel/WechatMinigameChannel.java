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
import com.game.payment.channel.ChannelTypes.Revocation;
import com.game.payment.store.PaymentKeys;
import com.game.proto.payment.PayChannel;
import com.game.proto.payment.WechatMinigamePayParams;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 微信小游戏虚拟支付 2.0 道具直购（{@code mode=goods}，对齐 Go {@code wechatminigame.go}）。
 * 会话密钥经 AES-256-GCM 加密后暂存意图行并在意图终结时清空；{@code stable_token} 锁外拉取并单飞合并。
 */
public final class WechatMinigameChannel implements PaymentChannel, PaymentChannel.Handshaker {

    private static final String DEFAULT_API_BASE = "https://api.weixin.qq.com";
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Config(
            String appId,
            String appSecret,
            String offerId,
            String appKey,
            int env,
            String zoneId,
            String pushToken,
            String encodingAesKey,
            String apiBase,
            HttpClient http,
            Clock clock) {}

    private final Config cfg;
    private final String zoneId;
    private final String apiBase;
    private final byte[] sealKey;
    private final byte[] aesKey;
    private final HttpClient http;
    private final Clock clock;

    private final ReentrantLock tokenLock = new ReentrantLock();
    private String accessToken = "";
    private Instant tokenExpiry = Instant.EPOCH;
    private CompletableFuture<TokenResult> tokenInflight;

    private record TokenResult(String token, Instant expiry, ChannelException error) {}

    public WechatMinigameChannel(Config cfg) {
        if (isBlank(cfg.appId()) || isBlank(cfg.appSecret()) || isBlank(cfg.offerId()) || isBlank(cfg.appKey())) {
            throw new IllegalArgumentException("payment channel: wechat_minigame needs AppID, AppSecret, OfferID and AppKey");
        }
        if (isBlank(cfg.pushToken())) {
            throw new IllegalArgumentException("payment channel: wechat_minigame needs PushToken");
        }
        if (cfg.env() < 0 || cfg.env() > 1) {
            throw new IllegalArgumentException("payment channel: wechat_minigame Env must be 0 or 1");
        }
        this.cfg = cfg;
        this.zoneId = isBlank(cfg.zoneId()) ? "1" : cfg.zoneId();
        this.apiBase = (isBlank(cfg.apiBase()) ? DEFAULT_API_BASE : cfg.apiBase()).replaceAll("/+$", "");
        // 派生 AES-256 密封密钥：与 Go wechat_minigame.go 域分隔串逐字节一致
        this.sealKey = PaymentKeys.sha256(
                String.join(":", "payment", "wechat-minigame", "session-key-seal", "v1\n") + cfg.appSecret());
        if (!isBlank(cfg.encodingAesKey())) {
            byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(cfg.encodingAesKey() + "=");
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("payment channel: invalid EncodingAESKey", e);
            }
            if (decoded.length != 32) {
                throw new IllegalArgumentException("payment channel: EncodingAESKey must decode to 32 bytes");
            }
            this.aesKey = decoded;
        } else {
            this.aesKey = null;
        }
        this.http = cfg.http() == null ? ChannelCrypto.newHttpClient() : cfg.http();
        this.clock = cfg.clock() == null ? Clock.systemUTC() : cfg.clock();
    }

    @Override public PayChannel id() { return PayChannel.PAY_CHANNEL_WECHAT_MINIGAME; }
    @Override public String name() { return ChannelTypes.NAME_WECHAT_MINIGAME; }
    @Override public boolean supports(String currency) { return "CNY".equals(currency); }
    @Override public boolean needsProduct() { return true; }
    @Override public void close(String outTradeNo) {}
    @Override public RefundResult refund(RefundRequest req) { return RefundResult.UNSUPPORTED; }

    @Override
    public Prepared prepare(Order order, Ref cached) throws ChannelException {
        if (isBlank(order.loginCode())) {
            throw new ChannelException(ChannelException.Kind.LOGIN_REQUIRED, "missing mini-game login code");
        }
        if (isBlank(order.productId()) || !"CNY".equals(order.currency())) {
            throw new ChannelException(ChannelException.Kind.OTHER, "wechat_minigame needs productId and CNY");
        }
        SessionPair session = code2Session(order.loginCode());
        String platform = "ios".equals(order.deviceOs()) ? "ios" : "android";
        Map<String, Object> signObj = new LinkedHashMap<>();
        signObj.put("mode", "goods");
        signObj.put("offerId", cfg.offerId());
        signObj.put("buyQuantity", 1);
        signObj.put("env", cfg.env());
        signObj.put("currencyType", "CNY");
        signObj.put("platform", platform);
        signObj.put("zoneId", zoneId);
        signObj.put("productId", order.productId());
        signObj.put("goodsPrice", order.amountMinor());
        signObj.put("outTradeNo", order.outTradeNo());
        signObj.put("attach", "");
        String signData = writeJsonString(signObj);
        String sealed = seal(session.sessionKey());
        WechatMinigamePayParams params = WechatMinigamePayParams.newBuilder()
                .setSignData(signData)
                .setPaySig(ChannelCrypto.hmacSha256Hex(cfg.appKey().getBytes(StandardCharsets.UTF_8),
                        "requestMidasPaymentGameItem&" + signData))
                .setSignature(ChannelCrypto.hmacSha256Hex(session.sessionKey().getBytes(StandardCharsets.UTF_8), signData))
                .build();
        return new Prepared(new Ref(sealed, clock.instant()), session.openId(), ClientParams.ofWechatMinigame(params));
    }

    private record SessionPair(String openId, String sessionKey) {}

    private SessionPair code2Session(String code) throws ChannelException {
        String url = apiBase + "/sns/jscode2session?appid=" + enc(cfg.appId())
                + "&secret=" + enc(cfg.appSecret())
                + "&js_code=" + enc(code)
                + "&grant_type=authorization_code";
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6)).GET().build();
        ChannelCrypto.HttpResult res = ChannelCrypto.send(http, req);
        JsonNode out = readJson(res.body(), "wechat_minigame code2session unreadable");
        int errCode = out.path("errcode").asInt(0);
        if (errCode == 40029 || errCode == 40226 || errCode == 40163) {
            throw new ChannelException(ChannelException.Kind.LOGIN_REQUIRED, "invalid wx.login code");
        }
        String openId = out.path("openid").asText("");
        String sessionKey = out.path("session_key").asText("");
        if (errCode != 0 || openId.isEmpty() || sessionKey.isEmpty()) {
            throw new ChannelException(name(), "code2session", res.status(), Integer.toString(errCode));
        }
        return new SessionPair(openId, sessionKey);
    }

    @Override
    public QueryResult query(QueryRequest req) throws ChannelException {
        if (isBlank(req.payerId()) || req.ref() == null || !req.ref().isPresent()) {
            throw new ChannelException(ChannelException.Kind.QUERY_UNSUPPORTED, "missing openid or session_key");
        }
        String sessionKey;
        try {
            sessionKey = unseal(req.ref().value());
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ChannelException(ChannelException.Kind.QUERY_UNSUPPORTED, "cannot unseal session_key", e);
        }
        Map<String, Object> bodyObj = new LinkedHashMap<>();
        bodyObj.put("openid", req.payerId());
        bodyObj.put("offer_id", cfg.offerId());
        bodyObj.put("ts", clock.instant().getEpochSecond());
        bodyObj.put("zone_id", zoneId);
        bodyObj.put("env", cfg.env());
        bodyObj.put("out_trade_no", req.outTradeNo());
        bodyObj.put("biz_id", 2);
        String bodyStr = writeJsonString(bodyObj);
        byte[] bodyBytes = bodyStr.getBytes(StandardCharsets.UTF_8);
        String uriPath = "/wxa/game/queryorderinfo";
        for (int attempt = 0; attempt < 2; attempt++) {
            String tok = accessToken();
            String sig = ChannelCrypto.hmacSha256Hex(sessionKey.getBytes(StandardCharsets.UTF_8), bodyStr);
            String paySig = ChannelCrypto.hmacSha256Hex(cfg.appKey().getBytes(StandardCharsets.UTF_8), uriPath + "&" + bodyStr);
            String url = apiBase + uriPath + "?access_token=" + enc(tok)
                    + "&signature=" + enc(sig) + "&sig_method=hmac_sha256&pay_sig=" + enc(paySig);
            HttpRequest httpReq = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(bodyBytes))
                    .build();
            ChannelCrypto.HttpResult res = ChannelCrypto.send(http, httpReq);
            JsonNode out = readJson(res.body(), "wechat_minigame query unreadable");
            int errCode = out.path("errcode").asInt(0);
            if ((errCode == 40001 || errCode == 42001) && attempt == 0) {
                invalidateToken(tok);
                continue;
            }
            if (errCode == 90016) {
                throw new ChannelException(ChannelException.Kind.QUERY_UNSUPPORTED, "wechat_minigame session_key expired");
            }
            if (errCode == 90019) {
                return QueryResult.notPaid();
            }
            if (errCode != 0) {
                throw new ChannelException(name(), "query", res.status(), Integer.toString(errCode));
            }
            if (out.path("pay_state").asInt(0) != 2) {
                return QueryResult.notPaid();
            }
            if (!req.outTradeNo().equals(out.path("out_trade_no").asText("")) || req.amountMinor() <= 0) {
                throw new ChannelException(ChannelException.Kind.OTHER, "wechat_minigame query order mismatch");
            }
            long finish = out.path("pay_finish_time").asLong(0L);
            Instant paidAt = finish > 0 ? Instant.ofEpochSecond(finish) : clock.instant();
            Payment p = new Payment(req.outTradeNo(), req.outTradeNo(), "", req.amountMinor(), "CNY", paidAt,
                    out.path("product_id").asText(""), false, 0L, "", "", out.path("transaction_id").asText(""),
                    false, cfg.env() == 1, "", "");
            return QueryResult.paid(p);
        }
        throw new ChannelException(ChannelException.Kind.OTHER, "wechat_minigame query retry exhausted");
    }

    @Override
    public AckResponse handshake(HttpNotificationRequest req) {
        if (!"GET".equalsIgnoreCase(req.method())) {
            return null;
        }
        String expected = ChannelCrypto.sortedSha1Hex(cfg.pushToken(), req.query("timestamp"), req.query("nonce"));
        if (!ChannelCrypto.constantTimeEqualsHex(req.query("signature"), expected)) {
            return new AckResponse(401, "text/plain; charset=utf-8", "");
        }
        return new AckResponse(200, "text/plain; charset=utf-8", req.query("echostr"));
    }

    @Override
    public Notification parseNotification(HttpNotificationRequest req) throws ChannelException {
        byte[] body = req.body() == null ? new byte[0] : req.body();
        String ts = req.query("timestamp");
        String nonce = req.query("nonce");
        if ("aes".equals(req.query("encrypt_type"))) {
            if (aesKey == null) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "aes push not configured");
            }
            JsonNode env;
            try {
                env = JSON.readTree(body);
            } catch (IOException e) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid encrypted envelope", e);
            }
            String encStr = env.path("Encrypt").asText("");
            String expected = ChannelCrypto.sortedSha1Hex(cfg.pushToken(), ts, nonce, encStr);
            if (encStr.isEmpty() || !ChannelCrypto.constantTimeEqualsHex(req.query("msg_signature"), expected)) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "wechat_minigame msg_signature mismatch");
            }
            body = decryptPush(encStr);
        } else {
            String expected = ChannelCrypto.sortedSha1Hex(cfg.pushToken(), ts, nonce);
            if (!ChannelCrypto.constantTimeEqualsHex(req.query("signature"), expected)) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "wechat_minigame signature mismatch");
            }
        }
        JsonNode push;
        try {
            push = JSON.readTree(body);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid wechat_minigame push JSON", e);
        }
        String event = push.path("Event").asText("");
        if (event.isEmpty()) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "missing Event");
        }
        if (!"minigame_game_pay_goods_deliver_notify".equals(event) && !"minigame_pay_refund_succ_notify".equals(event)) {
            return Notification.EMPTY;
        }
        JsonNode mg = push.path("MiniGame");
        String payloadStr = mg.path("Payload").asText("");
        String payEventSig = mg.path("PayEventSig").asText("");
        String expectedSig = ChannelCrypto.hmacSha256Hex(cfg.appKey().getBytes(StandardCharsets.UTF_8), event + "&" + payloadStr);
        if (!ChannelCrypto.constantTimeEqualsHex(payEventSig, expectedSig)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "PayEventSig mismatch");
        }
        if (mg.path("IsMock").asBoolean(false)) {
            return Notification.EMPTY;
        }
        JsonNode payload;
        try {
            payload = JSON.readTree(payloadStr);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid MiniGame Payload", e);
        }
        if ("minigame_pay_refund_succ_notify".equals(event)) {
            String outTradeNo = payload.path("OutTradeNo").asText("");
            int env = payload.path("Env").asInt(-1);
            if (outTradeNo.isEmpty() || env != cfg.env()) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "refund payload invalid");
            }
            return new Notification(null, List.of(new Revocation(
                    outTradeNo, outTradeNo, "", true, clock.instant(),
                    "REFUND_SOURCE_" + payload.path("RefundSource").asInt(0), null)), List.of());
        }
        String outTradeNo = payload.path("OutTradeNo").asText("");
        int env = payload.path("Env").asInt(-1);
        JsonNode goods = payload.path("GoodsInfo");
        long origPrice = goods.path("OrigPrice").asLong(0L);
        if (outTradeNo.isEmpty() || env != cfg.env() || origPrice <= 0) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "goods payload invalid");
        }
        long qty = goods.path("Quantity").asLong(1L);
        if (qty <= 0) {
            qty = 1L;
        }
        String anomaly = qty != 1L ? "wechat_minigame goods quantity is " + qty : "";
        long total = Math.multiplyExact(origPrice, qty);
        Payment p = new Payment(outTradeNo, outTradeNo, "", total, "CNY", clock.instant(),
                goods.path("ProductId").asText(""), false, 0L, "", "",
                payload.path("WeChatPayInfo").path("TransactionId").asText(""),
                false, env == 1, "", anomaly);
        return new Notification(p, List.of(), List.of());
    }

    @Override
    public AckResponse ack(AckKind kind) {
        if (kind == AckKind.OK) {
            return new AckResponse(200, "application/json", "{\"ErrCode\":0,\"ErrMsg\":\"Success\"}");
        }
        return new AckResponse(200, "application/json", "{\"ErrCode\":99999,\"ErrMsg\":\"internal error\"}");
    }

    // ---- 加密与 token 单飞 ----

    String seal(String plain) {
        byte[] nonce = ChannelCrypto.randomBytes(12);
        byte[] cipher = ChannelCrypto.aesGcmEncrypt(sealKey, nonce, plain.getBytes(StandardCharsets.UTF_8), null);
        byte[] combined = new byte[nonce.length + cipher.length];
        System.arraycopy(nonce, 0, combined, 0, nonce.length);
        System.arraycopy(cipher, 0, combined, nonce.length, cipher.length);
        return Base64.getEncoder().encodeToString(combined);
    }

    String unseal(String sealed) throws GeneralSecurityException {
        byte[] raw = Base64.getDecoder().decode(sealed);
        if (raw.length < 12 + 16) {
            throw new IllegalArgumentException("sealed session key too short");
        }
        byte[] nonce = Arrays.copyOfRange(raw, 0, 12);
        byte[] cipher = Arrays.copyOfRange(raw, 12, raw.length);
        return new String(ChannelCrypto.aesGcmDecrypt(sealKey, nonce, cipher, null), StandardCharsets.UTF_8);
    }

    private byte[] decryptPush(String encryptedBase64) throws ChannelException {
        byte[] sealed;
        try {
            sealed = Base64.getDecoder().decode(encryptedBase64);
        } catch (IllegalArgumentException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "bad base64 Encrypt", e);
        }
        if (sealed.length == 0 || sealed.length % 16 != 0) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "bad Encrypt block size");
        }
        byte[] plain;
        try {
            plain = ChannelCrypto.aesCbcDecryptNoPadding(aesKey, Arrays.copyOf(aesKey, 16), sealed);
        } catch (GeneralSecurityException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "aes decrypt failed", e);
        }
        int pad = plain[plain.length - 1] & 0xFF;
        if (pad < 1 || pad > 32 || pad > plain.length) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "bad PKCS7 pad");
        }
        for (int i = plain.length - pad; i < plain.length; i++) {
            if ((plain[i] & 0xFF) != pad) {
                throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "bad PKCS7 pad bytes");
            }
        }
        int unpaddedLen = plain.length - pad;
        if (unpaddedLen < 20) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "decrypted push too short");
        }
        int msgLen = ByteBuffer.wrap(plain, 16, 4).getInt();
        if (msgLen < 0 || 20 + msgLen > unpaddedLen) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "decrypted push bad length");
        }
        String tailAppId = new String(plain, 20 + msgLen, unpaddedLen - (20 + msgLen), StandardCharsets.UTF_8);
        if (!cfg.appId().equals(tailAppId)) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "decrypted push appId mismatch");
        }
        return Arrays.copyOfRange(plain, 20, 20 + msgLen);
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
            return awaitToken(waitOn);
        }
        TokenResult res;
        try {
            res = fetchStableToken();
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

    private TokenResult fetchStableToken() throws ChannelException {
        Instant now = clock.instant();
        byte[] body = writeJsonString(Map.of(
                "grant_type", "client_credential", "appid", cfg.appId(), "secret", cfg.appSecret()))
                .getBytes(StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(apiBase + "/cgi-bin/stable_token"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        ChannelCrypto.HttpResult res = ChannelCrypto.send(http, req);
        JsonNode out = readJson(res.body(), "stable_token unreadable");
        int errCode = out.path("errcode").asInt(0);
        String tok = out.path("access_token").asText("");
        long expiresIn = out.path("expires_in").asLong(0L);
        if (errCode != 0 || tok.isEmpty() || expiresIn <= 0) {
            throw new ChannelException(name(), "stable_token", res.status(), Integer.toString(errCode));
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

    private static String awaitToken(CompletableFuture<TokenResult> f) throws ChannelException {
        try {
            TokenResult r = f.get(10, TimeUnit.SECONDS);
            if (r.error() != null) throw r.error();
            return r.token();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException(ChannelException.Kind.OTHER, "stable_token interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "stable_token wait failed", e);
        }
    }

    private static String writeJsonString(Object o) throws ChannelException {
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, "json encode failed", e);
        }
    }

    private static JsonNode readJson(byte[] raw, String msg) throws ChannelException {
        try {
            return JSON.readTree(raw);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER, msg, e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
