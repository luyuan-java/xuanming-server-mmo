package com.game.common.token;

import com.game.proto.match.SignedNetworkProbe;
import com.google.protobuf.ByteString;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.MessageLite;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** 共享客户端探测契约的原始字节签名；域包含末尾 NUL，与 C++/Go 完全一致。 */
public final class NetworkProbes {
    public static final String GRANT = "xuanming.match.probe.grant.v1\0";
    public static final String RECEIPT = "xuanming.match.probe.receipt.v1\0";
    private NetworkProbes() { }

    public static byte[] bytes(MessageLite value) {
        try {
            byte[] bytes = new byte[value.getSerializedSize()];
            CodedOutputStream output = CodedOutputStream.newInstance(bytes);
            output.useDeterministicSerialization();
            value.writeTo(output);
            output.checkNoSpaceLeft();
            return bytes;
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }

    public static SignedNetworkProbe sign(String secret, String domain, MessageLite message) {
        byte[] payload = bytes(message);
        return SignedNetworkProbe.newBuilder().setPayload(ByteString.copyFrom(payload))
                .setSignature(ByteString.copyFrom(mac(secret, domain, payload))).build();
    }

    public static boolean verify(String secret, String domain, SignedNetworkProbe signed) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32 || signed.getPayload().isEmpty()
                || signed.getPayload().size() > 8192 || signed.getSignature().size() != 32) return false;
        return MessageDigest.isEqual(mac(secret, domain, signed.getPayload().toByteArray()), signed.getSignature().toByteArray());
    }

    public static String binding(String instance, String incarnation, long player) {
        if (instance == null || instance.isBlank() || incarnation == null || incarnation.isBlank() || player == 0)
            throw new IllegalArgumentException("探测缺少可信会话身份");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (instance + "\n" + incarnation + "\n" + Long.toUnsignedString(player)).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static byte[] mac(String secret, String domain, byte[] payload) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalArgumentException("探测密钥不足 32 字节");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(domain.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
}
