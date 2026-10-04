package com.game.gateway.queue;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 排队令牌（{@code queue_token}，对客户端不透明，只原样回传）：{@code base64url(v1.<queue_id>.<zone>.<过期秒>)} + "." +
 * {@code base64url(HMAC-SHA256)}。签名密钥由 gate 令牌密钥派生（{@code HMAC(gate 密钥, "xm-login-queue")}，与 gate 令牌分域：
 * 拿排队令牌冒充 gate 令牌、或反过来都验不过）。令牌里带排队号与区，不需要「令牌 → 排队号」的索引键。
 *
 * <p>与基线（JSON 体 + hex 签名、按令牌查索引键）形状不同——客户端只透传，不解析。无状态，线程安全。
 */
public final class QueueTokens {

    private static final String VERSION = "v1";
    private static final Pattern QUEUE_ID = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final int MAX_TOKEN_CHARS = 256;

    private final SecretKeySpec key;

    /** @param gateSecret gate 令牌密钥（{@code XM_GATE_TOKEN_SECRET}） */
    public QueueTokens(byte[] gateSecret) {
        if (gateSecret.length == 0) {
            throw new IllegalArgumentException("排队令牌需要非空的 gate 令牌密钥");
        }
        this.key = new SecretKeySpec(hmac(new SecretKeySpec(gateSecret, "HmacSHA256"),
                "xm-login-queue".getBytes(StandardCharsets.US_ASCII)), "HmacSHA256");
    }

    /** 验过的令牌内容。 */
    public record Claims(String queueId, int zoneId, long expireEpochSec) {
    }

    public static String newQueueId() {
        return UUID.randomUUID().toString();
    }

    public String sign(String queueId, int zoneId, long expireEpochSec) {
        byte[] body = (VERSION + "." + queueId + "." + zoneId + "." + expireEpochSec).getBytes(StandardCharsets.US_ASCII);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return b64.encodeToString(body) + "." + b64.encodeToString(hmac(key, body));
    }

    /**
     * 验签并解析；签名不对、格式不对、已过期（{@code now > expire}）都为空。签名比较常数时间。
     */
    public Optional<Claims> verify(String token, long nowEpochSec) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_CHARS) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.') || dot == token.length() - 1) {
            return Optional.empty();
        }
        byte[] body;
        byte[] signature;
        try {
            Base64.Decoder b64 = Base64.getUrlDecoder();
            body = b64.decode(token.substring(0, dot));
            signature = b64.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!MessageDigest.isEqual(signature, hmac(key, body))) {
            return Optional.empty();
        }
        String[] parts = new String(body, StandardCharsets.US_ASCII).split(Pattern.quote("."), -1);
        if (parts.length != 4 || !VERSION.equals(parts[0]) || !QUEUE_ID.matcher(parts[1]).matches()) {
            return Optional.empty();
        }
        try {
            int zoneId = Integer.parseInt(parts[2]);
            long expire = Long.parseLong(parts[3]);
            if (zoneId <= 0 || nowEpochSec > expire) {
                return Optional.empty();
            }
            return Optional.of(new Claims(parts[1], zoneId, expire));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 不可用", e);
        }
    }
}
