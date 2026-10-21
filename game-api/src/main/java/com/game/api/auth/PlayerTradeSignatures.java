package com.game.api.auth;

import com.game.api.PlayerTradeService.*;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 独立密钥、版本、caller、方法、全部 typed 正文及回复绑定；不依赖通用 Dubbo method/time MAC。 */
public final class PlayerTradeSignatures {
    private final SecretKeySpec key;
    public PlayerTradeSignatures(String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32)
            throw new IllegalArgumentException("角色交易独立密钥至少32字节");
        key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }
    public Auth sign(Command command, long now, String nonce) {
        return new Auth("trade", now, nonce, mac(request(command, now, nonce)));
    }
    public boolean verify(Command command, Auth auth, long now) {
        return command != null && command.operation() != null && auth != null && "trade".equals(auth.caller())
            && auth.nonce() != null && auth.nonce().matches("[0-9a-f]{32}") && auth.timestampMs() >= 0
            && auth.timestampMs() >= now - 60_000 && auth.timestampMs() <= now + 60_000
            && command.expiresMs() > now && command.expiresMs() <= now + 5_000
            && equal(mac(request(command, auth.timestampMs(), auth.nonce())), auth.signature());
    }
    public Reply signReply(Command command, Auth auth, Reply reply) {
        return new Reply(reply.code(), reply.listingId(), reply.txId(), reply.playerId(), reply.sellerAccount(),
            reply.buyerAccount(), reply.ownerRevision(), reply.ownerEpoch(), reply.state(), reply.homeZone(), reply.title(),
            mac(response(command, auth, reply)));
    }
    public boolean verifyReply(Command command, Auth auth, Reply reply) {
        return reply != null && equal(mac(response(command, auth, reply)), reply.signature());
    }
    private static byte[] request(Command c, long time, String nonce) {
        return bytes("player-trade/v1", "trade", "execute", time, nonce, c.operation().name(), c.listingId(), c.txId(),
            c.playerId(), c.sellerAccount(), c.buyerAccount(), c.ownerRevision(), c.expiresMs());
    }
    private static byte[] response(Command c, Auth a, Reply r) {
        return bytes("player-trade/reply/v1", HexFormat.of().formatHex(request(c, a.timestampMs(), a.nonce())),
            r.code(), r.listingId(), r.txId(), r.playerId(), r.sellerAccount(), r.buyerAccount(), r.ownerRevision(),
            r.ownerEpoch(), r.state(), r.homeZone(), r.title());
    }
    private static byte[] bytes(Object... values) {
        try {
            var buffer = new ByteArrayOutputStream(); var out = new DataOutputStream(buffer);
            for (Object v : values) {
                if (v instanceof Long n) { out.writeByte(1); out.writeLong(n); }
                else if (v instanceof Integer n) { out.writeByte(2); out.writeInt(n); }
                else { out.writeByte(3); byte[] raw = v == null ? new byte[0] : ((String) v).getBytes(StandardCharsets.UTF_8);
                    out.writeInt(raw.length); out.write(raw); }
            }
            return buffer.toByteArray();
        } catch (java.io.IOException impossible) { throw new IllegalStateException(impossible); }
    }
    private String mac(byte[] bytes) {
        try { var mac = Mac.getInstance("HmacSHA256"); mac.init(key); return HexFormat.of().formatHex(mac.doFinal(bytes)); }
        catch (java.security.GeneralSecurityException impossible) { throw new IllegalStateException(impossible); }
    }
    private static boolean equal(String expected, String actual) {
        return actual != null && actual.matches("[0-9a-f]{64}")
            && MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), actual.getBytes(StandardCharsets.US_ASCII));
    }
}
