package com.game.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.payment.channel.AlipayChannel;
import com.game.payment.channel.AppleChannel;
import com.game.payment.channel.ChannelCrypto;
import com.game.payment.channel.ChannelTypes.AckResponse;
import com.game.payment.channel.ChannelTypes.ChannelException;
import com.game.payment.channel.ChannelTypes.HttpNotificationRequest;
import com.game.payment.channel.ChannelTypes.Notification;
import com.game.payment.channel.ChannelTypes.Order;
import com.game.payment.channel.ChannelTypes.Prepared;
import com.game.payment.channel.ChannelTypes.QueryRequest;
import com.game.payment.channel.ChannelTypes.QueryResult;
import com.game.payment.channel.ChannelTypes.Ref;
import com.game.payment.channel.ChannelTypes.TradeState;
import com.game.payment.channel.DouyinMinigameChannel;
import com.game.payment.channel.GoogleChannel;
import com.game.payment.channel.WechatMinigameChannel;
import com.game.payment.channel.WechatPayChannel;
import com.game.payment.mall.HttpMallClient;
import com.game.payment.mall.MallClient;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * 7 渠道协议验签/加解密/审计加固与商城 HMAC-SHA256 本地 HTTP 回环测试。
 */
class PaymentChannelsAndMallHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void 商城HMAC_v1客户端验签与全部端点回环() throws Exception {
        String secret = "mall-payment-secret-at-least-32-bytes-long!";
        AtomicInteger verifiedCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] reqBody = ex.getRequestBody().readAllBytes();
            String sub = ex.getRequestHeaders().getFirst("X-Commerce-Subject");
            String ts = ex.getRequestHeaders().getFirst("X-Commerce-Timestamp");
            String nonce = ex.getRequestHeaders().getFirst("X-Commerce-Nonce");
            String sig = ex.getRequestHeaders().getFirst("X-Commerce-Signature");
            String expected = HttpMallClient.sign(secret.getBytes(StandardCharsets.UTF_8),
                    "payment", sub, ts, nonce, ex.getRequestMethod(), ex.getRequestURI().getRawPath(), reqBody);
            assertThat(sig).isEqualTo(expected);
            verifiedCount.incrementAndGet();
            String resp = switch (ex.getRequestURI().getRawPath()) {
                case "/internal/payments/order-view" ->
                        "{\"orderId\":\"11111111-1111-1111-1111-111111111111\",\"buyerId\":\"42\",\"skuId\":7,\"quantity\":1,\"amountMinor\":600,\"currency\":\"CNY\",\"state\":\"AWAITING_PAYMENT\",\"paymentDeadlineMs\":9999999999999}";
                case "/internal/payments" ->
                        "{\"order\":{\"orderId\":\"11111111-1111-1111-1111-111111111111\",\"state\":\"DELIVERY_PENDING\"},\"refundReason\":null}";
                case "/internal/payments/revision" -> "{\"revision\":1}";
                case "/internal/refunds/poll" -> "{\"entries\":[],\"nextCursor\":\"\"}";
                default -> "{}";
            };
            byte[] out = resp.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(out); }
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            HttpMallClient client = new HttpMallClient(base, "payment-adapter", secret, Duration.ofSeconds(2), Clock.systemUTC());
            MallClient.OrderView ov = client.orderView("11111111-1111-1111-1111-111111111111");
            assertThat(ov.buyerId()).isEqualTo("42");
            assertThat(ov.amountMinor()).isEqualTo(600L);
            MallClient.PaymentOutcome po = client.recordPayment(new MallClient.PaymentReport(
                    "11111111-1111-1111-1111-111111111111", "devpay", "txn-1", 600L, "CNY"));
            assertThat(po.orderState()).isEqualTo("DELIVERY_PENDING");
            assertThat(po.hasRefundReason()).isFalse();
            assertThat(client.pushRevision(new MallClient.RevisionReport(
                    "11111111-1111-1111-1111-111111111111", "devpay", "txn-1", 1, "REVOKED"))).isEqualTo(1);
            assertThat(client.pendingRefunds("").entries()).isEmpty();
            assertThat(verifiedCount.get()).isEqualTo(4);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 支付宝RSA2签名查单原始节点验签与回调解析() throws Exception {
        KeyPair appPair = rsa2048();
        KeyPair aliPair = rsa2048();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/gateway.do", ex -> {
            ex.getRequestBody().readAllBytes();
            String node = "{\"code\":\"10000\",\"out_trade_no\":\"0123456789abcdef0123456789abcdef\",\"trade_no\":\"202611230001\",\"trade_status\":\"TRADE_SUCCESS\",\"total_amount\":\"6.10\",\"send_pay_date\":\"2026-11-23 12:00:00\"}";
            String sign = ChannelCrypto.signRsaSha256Base64((RSAPrivateKey) aliPair.getPrivate(), node.getBytes(StandardCharsets.UTF_8));
            byte[] resp = ("{\"alipay_trade_query_response\":" + node + ",\"sign\":\"" + sign + "\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, resp.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(resp); }
        });
        server.start();
        try {
            AlipayChannel alipay = new AlipayChannel(new AlipayChannel.Config(
                    "202600001", (RSAPrivateKey) appPair.getPrivate(), (RSAPublicKey) aliPair.getPublic(),
                    "2088123456", "http://127.0.0.1:" + server.getAddress().getPort() + "/gateway.do",
                    "https://pay.example.com/notify/alipay", "游戏商品", null, Clock.systemUTC()));
            Prepared prep = alipay.prepare(new Order(
                    "01234567-89ab-cdef-0123-456789abcdef", "0123456789abcdef0123456789abcdef",
                    1001L, 610L, "CNY", Instant.now().plusSeconds(300), "", "月卡", "", ""), Ref.EMPTY);
            assertThat(prep.params().alipay().getOrderString()).contains("alipay.trade.app.pay");

            QueryResult qr = alipay.query(new QueryRequest("0123456789abcdef0123456789abcdef", "", Ref.EMPTY, 610L, "CNY"));
            assertThat(qr.state()).isEqualTo(TradeState.PAID);
            assertThat(qr.payment().amountMinor()).isEqualTo(610L);

            Map<String, String> form = new LinkedHashMap<>();
            form.put("app_id", "202600001");
            form.put("seller_id", "2088123456");
            form.put("trade_no", "202611230001");
            form.put("out_trade_no", "0123456789abcdef0123456789abcdef");
            form.put("trade_status", "TRADE_SUCCESS");
            form.put("total_amount", "6.10");
            form.put("gmt_payment", "2026-11-23 12:00:00");
            form.put("sign_type", "RSA2");
            String content = AlipayChannel.signContent(form, Set.of("sign", "sign_type"));
            form.put("sign", ChannelCrypto.signRsaSha256Base64((RSAPrivateKey) aliPair.getPrivate(), content.getBytes(StandardCharsets.UTF_8)));
            StringBuilder encoded = new StringBuilder();
            form.forEach((k, v) -> {
                if (!encoded.isEmpty()) encoded.append('&');
                encoded.append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8));
            });
            Notification note = alipay.parseNotification(new HttpNotificationRequest(
                    "POST", Map.of(), Map.of(), encoded.toString().getBytes(StandardCharsets.UTF_8)));
            assertThat(note.payment().transactionId()).isEqualTo("202611230001");
            assertThat(note.payment().amountMinor()).isEqualTo(610L);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void 微信支付APIv3回调验签与AEAD解密() throws Exception {
        KeyPair merchantPair = rsa2048();
        KeyPair platformPair = rsa2048();
        byte[] apiV3Key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        Instant now = Instant.parse("2026-11-23T04:00:00Z");
        WechatPayChannel wx = new WechatPayChannel(new WechatPayChannel.Config(
                "wx_app_1", "mch_1", "SERIAL1", (RSAPrivateKey) merchantPair.getPrivate(), apiV3Key,
                "PUB_KEY_ID_001", (RSAPublicKey) platformPair.getPublic(), "https://pay.example.com/notify/wechat_pay",
                "游戏商品", "", null, Clock.fixed(now, ZoneOffset.UTC)));

        String plain = "{\"appid\":\"wx_app_1\",\"mchid\":\"mch_1\",\"out_trade_no\":\"0123456789abcdef0123456789abcdef\","
                + "\"transaction_id\":\"4200001234\",\"trade_state\":\"SUCCESS\",\"success_time\":\"2026-11-23T12:00:00+08:00\","
                + "\"amount\":{\"total\":600,\"currency\":\"CNY\"}}";
        String nonce = "123456789012";
        String aad = "transaction";
        String cipherB64 = Base64.getEncoder().encodeToString(ChannelCrypto.aesGcmEncrypt(
                apiV3Key, nonce.getBytes(StandardCharsets.UTF_8), plain.getBytes(StandardCharsets.UTF_8), aad.getBytes(StandardCharsets.UTF_8)));
        byte[] body = ("{\"event_type\":\"TRANSACTION.SUCCESS\",\"resource\":{\"algorithm\":\"AEAD_AES_256_GCM\","
                + "\"nonce\":\"" + nonce + "\",\"associated_data\":\"" + aad + "\",\"ciphertext\":\"" + cipherB64 + "\"}}")
                .getBytes(StandardCharsets.UTF_8);
        String ts = Long.toString(now.getEpochSecond());
        String hdrNonce = "nonce_abc";
        String sigMsg = ts + "\n" + hdrNonce + "\n" + new String(body, StandardCharsets.UTF_8) + "\n";
        String sig = ChannelCrypto.signRsaSha256Base64((RSAPrivateKey) platformPair.getPrivate(), sigMsg.getBytes(StandardCharsets.UTF_8));

        Notification note = wx.parseNotification(new HttpNotificationRequest("POST", Map.of(
                "Wechatpay-Serial", "PUB_KEY_ID_001",
                "Wechatpay-Signature-Type", "WECHATPAY2-SHA256-RSA2048",
                "Wechatpay-Timestamp", ts,
                "Wechatpay-Nonce", hdrNonce,
                "Wechatpay-Signature", sig), Map.of(), body));
        assertThat(note.payment().transactionId()).isEqualTo("4200001234");
        assertThat(note.payment().amountMinor()).isEqualTo(600L);
    }

    @Test
    void 微信小游戏安全模式AES推送解密与抖音小游戏握手() throws Exception {
        byte[] rawAesKey = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        String encodingAesKey = Base64.getEncoder().encodeToString(rawAesKey).substring(0, 43);
        WechatMinigameChannel wm = new WechatMinigameChannel(new WechatMinigameChannel.Config(
                "wx_mini_1", "app_secret_1", "offer_1", "app_key_1", 0, "1", "push_tok", encodingAesKey,
                null, null, Clock.systemUTC()));

        // GET 握手
        String ts = "1700000000";
        String nonce = "n123";
        String hsSig = ChannelCrypto.sortedSha1Hex("push_tok", ts, nonce);
        AckResponse hs = wm.handshake(new HttpNotificationRequest("GET", Map.of(),
                Map.of("timestamp", ts, "nonce", nonce, "signature", hsSig, "echostr", "hello_wx"), new byte[0]));
        assertThat(hs.status()).isEqualTo(200);
        assertThat(hs.body()).isEqualTo("hello_wx");

        // AES-CBC 32字节块安全模式发货推送
        String payload = "{\"Env\":0,\"OutTradeNo\":\"0123456789abcdef0123456789abcdef\",\"GoodsInfo\":{\"ProductId\":\"sku_6\",\"Quantity\":1,\"OrigPrice\":600},\"WeChatPayInfo\":{\"TransactionId\":\"wx_p1\"}}";
        String payEventSig = ChannelCrypto.hmacSha256Hex("app_key_1".getBytes(StandardCharsets.UTF_8),
                "minigame_game_pay_goods_deliver_notify&" + payload);
        String pushJson = JSON.writeValueAsString(Map.of(
                "Event", "minigame_game_pay_goods_deliver_notify",
                "MiniGame", Map.of("Payload", payload, "PayEventSig", payEventSig, "IsMock", false)));
        byte[] msgBytes = pushJson.getBytes(StandardCharsets.UTF_8);
        byte[] appBytes = "wx_mini_1".getBytes(StandardCharsets.UTF_8);
        int rawLen = 16 + 4 + msgBytes.length + appBytes.length;
        int pad = 32 - (rawLen % 32);
        byte[] plain = new byte[rawLen + pad];
        ByteBuffer.wrap(plain, 16, 4).putInt(msgBytes.length);
        System.arraycopy(msgBytes, 0, plain, 20, msgBytes.length);
        System.arraycopy(appBytes, 0, plain, 20 + msgBytes.length, appBytes.length);
        for (int i = rawLen; i < plain.length; i++) plain[i] = (byte) pad;
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(rawAesKey, "AES"), new IvParameterSpec(rawAesKey, 0, 16));
        String encB64 = Base64.getEncoder().encodeToString(c.doFinal(plain));
        String msgSig = ChannelCrypto.sortedSha1Hex("push_tok", ts, nonce, encB64);

        Notification note = wm.parseNotification(new HttpNotificationRequest("POST", Map.of(),
                Map.of("encrypt_type", "aes", "timestamp", ts, "nonce", nonce, "msg_signature", msgSig),
                ("{\"Encrypt\":\"" + encB64 + "\"}").getBytes(StandardCharsets.UTF_8)));
        assertThat(note.payment().amountMinor()).isEqualTo(600L);
        assertThat(note.payment().productId()).isEqualTo("sku_6");

        // 抖音小游戏握手
        DouyinMinigameChannel dy = new DouyinMinigameChannel(new DouyinMinigameChannel.Config("dy_1", "dy_tok", "1", "元宝"));
        String dySig = ChannelCrypto.sortedSha1Hex("dy_tok", ts, nonce, "m1");
        AckResponse dyHs = dy.handshake(new HttpNotificationRequest("GET", Map.of(),
                Map.of("timestamp", ts, "nonce", nonce, "msg", "m1", "signature", dySig, "echostr", "ok_dy"), new byte[0]));
        assertThat(dyHs.status()).isEqualTo(200);
        assertThat(dyHs.body()).isEqualTo("ok_dy");
    }

    @Test
    void Apple_ISO4217毫单位小数位折算正确() {
        assertThat(AppleChannel.milliunitsToMinor(6000L, "CNY")).isEqualTo(600L);   // 2 decimals: ÷10
        assertThat(AppleChannel.milliunitsToMinor(4990L, "USD")).isEqualTo(499L);   // 2 decimals: ÷10
        assertThat(AppleChannel.milliunitsToMinor(160000L, "JPY")).isEqualTo(160L); // 0 decimals: ÷1000
        assertThat(AppleChannel.milliunitsToMinor(1200000L, "KRW")).isEqualTo(1200L);
        assertThat(AppleChannel.milliunitsToMinor(1500L, "KWD")).isEqualTo(1500L);  // 3 decimals: ÷1
        assertThat(AppleChannel.milliunitsToMinor(0L, "USD")).isEqualTo(0L);
    }

    @Test
    void Google_JWKS锁外拉取与60秒冷却防止未知kid刷新DoS() throws Exception {
        KeyPair svcPair = rsa2048();
        KeyPair oidcPair = rsa2048();
        RSAPublicKey oidcPub = (RSAPublicKey) oidcPair.getPublic();
        AtomicInteger certsFetches = new AtomicInteger();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/certs", ex -> {
            certsFetches.incrementAndGet();
            String n = Base64.getUrlEncoder().withoutPadding().encodeToString(unsignedBytes(oidcPub.getModulus()));
            String e = Base64.getUrlEncoder().withoutPadding().encodeToString(unsignedBytes(oidcPub.getPublicExponent()));
            byte[] body = JSON.writeValueAsBytes(Map.of("keys", List.of(Map.of(
                    "kid", "kid-1", "kty", "RSA", "alg", "RS256", "n", n, "e", e))));
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(body); }
        });
        server.start();
        try {
            Instant[] nowRef = {Instant.parse("2026-11-23T04:00:00Z")};
            Clock clock = new Clock() {
                @Override public ZoneId getZone() { return ZoneOffset.UTC; }
                @Override public Clock withZone(ZoneId zone) { return this; }
                @Override public Instant instant() { return nowRef[0]; }
            };
            GoogleChannel g = new GoogleChannel(new GoogleChannel.Config(
                    "com.example.game", "svc@example.iam.gserviceaccount.com", (RSAPrivateKey) svcPair.getPrivate(),
                    "https://pay.example.com/notify/google", "push@example.iam.gserviceaccount.com",
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/token",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/certs",
                    null, clock));

            // 合法 OIDC 推送触发第 1 次 JWKS 拉取
            String jwt = ChannelCrypto.signJwtRs256(
                    Map.of("alg", "RS256", "kid", "kid-1", "typ", "JWT"),
                    Map.of("iss", "https://accounts.google.com", "aud", "https://pay.example.com/notify/google",
                            "email", "push@example.iam.gserviceaccount.com", "email_verified", true,
                            "exp", nowRef[0].getEpochSecond() + 300),
                    (RSAPrivateKey) oidcPair.getPrivate());
            String devNote = Base64.getEncoder().encodeToString(
                    "{\"packageName\":\"com.example.game\",\"eventTimeMillis\":\"1700000000000\",\"oneTimeProductNotification\":{\"notificationType\":1,\"purchaseToken\":\"ptok_1\",\"sku\":\"sku_6\"}}"
                            .getBytes(StandardCharsets.UTF_8));
            Notification note = g.parseNotification(new HttpNotificationRequest("POST",
                    Map.of("Authorization", "Bearer " + jwt), Map.of(),
                    ("{\"message\":{\"data\":\"" + devNote + "\"}}").getBytes(StandardCharsets.UTF_8)));
            assertThat(note.receipts()).hasSize(1);
            assertThat(note.receipts().get(0).purchaseToken()).isEqualTo("ptok_1");
            assertThat(certsFetches.get()).isEqualTo(1);

            // 30 秒内多次未知随机 kid 请求直接拒签，绝不再拉 JWKS（DoS 冷却生效）
            nowRef[0] = nowRef[0].plusSeconds(30);
            for (int i = 0; i < 5; i++) {
                String badJwt = ChannelCrypto.signJwtRs256(
                        Map.of("alg", "RS256", "kid", "random-attack-" + i, "typ", "JWT"),
                        Map.of("iss", "https://accounts.google.com", "exp", nowRef[0].getEpochSecond() + 300),
                        (RSAPrivateKey) oidcPair.getPrivate());
                assertThatThrownBy(() -> g.parseNotification(new HttpNotificationRequest("POST",
                        Map.of("Authorization", "Bearer " + badJwt), Map.of(), "{}".getBytes(StandardCharsets.UTF_8))))
                        .isInstanceOf(ChannelException.class)
                        .satisfies(ex -> assertThat(((ChannelException) ex).kind()).isEqualTo(ChannelException.Kind.BAD_SIGNATURE));
            }
            assertThat(certsFetches.get()).isEqualTo(1);

            // 过 61 秒冷却后遇未知 kid 允许重新拉取一次
            nowRef[0] = nowRef[0].plusSeconds(31);
            String rotatedJwt = ChannelCrypto.signJwtRs256(
                    Map.of("alg", "RS256", "kid", "kid-new", "typ", "JWT"),
                    Map.of("iss", "https://accounts.google.com", "exp", nowRef[0].getEpochSecond() + 300),
                    (RSAPrivateKey) oidcPair.getPrivate());
            assertThatThrownBy(() -> g.parseNotification(new HttpNotificationRequest("POST",
                    Map.of("Authorization", "Bearer " + rotatedJwt), Map.of(), "{}".getBytes(StandardCharsets.UTF_8))))
                    .isInstanceOf(ChannelException.class);
            assertThat(certsFetches.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    private static KeyPair rsa2048() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        return gen.generateKeyPair();
    }

    private static byte[] unsignedBytes(java.math.BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0) {
            byte[] trimmed = new byte[b.length - 1];
            System.arraycopy(b, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return b;
    }
}
