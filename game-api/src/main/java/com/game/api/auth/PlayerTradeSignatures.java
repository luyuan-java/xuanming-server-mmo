package com.game.api.auth;

import com.game.api.proto.PlayerTradeAuth;
import com.game.api.proto.PlayerTradeCommand;
import com.game.api.proto.PlayerTradeReply;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.Message;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 独立密钥、caller、方法及确定性完整 protobuf；响应绑定原请求，不能拼接另一笔交易的回复。 */
public final class PlayerTradeSignatures {
    private final SecretKeySpec key;
    public PlayerTradeSignatures(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("角色交易独立密钥至少32字节");
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
    public PlayerTradeAuth sign(PlayerTradeCommand command, long now, String nonce) {
        return PlayerTradeAuth.newBuilder().setCaller("trade").setTimestampMs(now).setNonce(nonce)
            .setSignature(mac(request(command, now, nonce))).build();
    }
    public boolean verify(PlayerTradeCommand command, PlayerTradeAuth auth, long now) {
        return command != null && command.getOperationValue() > 0 && command.getOperationValue() <= 5
            && auth != null && "trade".equals(auth.getCaller()) && auth.getNonce().matches("[0-9a-f]{32}")
            && auth.getTimestampMs() > 0 && auth.getTimestampMs() >= now - 60_000
            && auth.getTimestampMs() <= now + 60_000
            && command.getExpiresMs() > now && command.getExpiresMs() <= now + 5_000
            && equal(mac(request(command, auth.getTimestampMs(), auth.getNonce())), auth.getSignature());
    }
    public PlayerTradeReply signReply(PlayerTradeCommand command, PlayerTradeAuth auth, PlayerTradeReply reply) {
        return reply.toBuilder().setSignature(mac(response(command, auth, reply))).build();
    }
    public boolean verifyReply(PlayerTradeCommand command, PlayerTradeAuth auth, PlayerTradeReply reply) {
        return reply != null && equal(mac(response(command, auth, reply)), reply.getSignature());
    }
    private static String request(PlayerTradeCommand c, long time, String nonce) {
        return String.join("\n", "player-trade/v1", "trade", "execute", Long.toString(time), nonce, digest(c));
    }
    private static String response(PlayerTradeCommand c, PlayerTradeAuth a, PlayerTradeReply r) {
        return String.join("\n", "player-trade/reply/v1", request(c, a.getTimestampMs(), a.getNonce()),
            digest(r.toBuilder().clearSignature().build()));
    }
    private static String digest(Message input) {
        try {
            byte[] bytes = new byte[input.getSerializedSize()];
            var coded = CodedOutputStream.newInstance(bytes); coded.useDeterministicSerialization();
            input.writeTo(coded); coded.checkNoSpaceLeft();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.io.IOException | java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
    private String mac(String value) {
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean equal(String expected, String actual) {
        return actual != null && actual.matches("[0-9a-f]{64}")
            && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
