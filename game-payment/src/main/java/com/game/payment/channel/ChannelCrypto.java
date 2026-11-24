package com.game.payment.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.game.payment.channel.ChannelTypes.ChannelException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 渠道共用加解密、JWT/JWS、PEM/DER 解析、金额折算与受限 HTTP 客户端工具（纯 JDK 21 JCA/JCE，零新增三方库）。
 */
public final class ChannelCrypto {

    public static final ZoneOffset CHINA_ZONE = ZoneOffset.ofHours(8);
    public static final int MAX_RESPONSE_BYTES = 1 << 20;
    private static final long MAX_YUAN_FEN = 9_000_000_000_000_000L;
    private static final HexFormat HEX = HexFormat.of();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();

    private ChannelCrypto() {}

    // ---- 金额与字符串 ----

    public static String formatYuan(long fen) {
        return (fen / 100) + "." + String.format(Locale.ROOT, "%02d", fen % 100);
    }

    public static long parseYuan(String text) {
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("empty yuan amount");
        }
        int dot = text.indexOf('.');
        String whole = dot < 0 ? text : text.substring(0, dot);
        String frac = dot < 0 ? "" : text.substring(dot + 1);
        if (whole.isEmpty() || whole.length() > 16 || !allDigits(whole)
                || (dot >= 0 && (frac.isEmpty() || frac.length() > 2 || !allDigits(frac)))) {
            throw new IllegalArgumentException("invalid yuan amount: " + text);
        }
        long yuan = Long.parseLong(whole);
        while (frac.length() < 2) {
            frac += "0";
        }
        long cents = Long.parseLong(frac);
        if (yuan > (MAX_YUAN_FEN - cents) / 100) {
            throw new IllegalArgumentException("yuan amount out of range: " + text);
        }
        return yuan * 100 + cents;
    }

    public static boolean allDigits(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    // ---- 哈希 / MAC / 随机串 ----

    public static byte[] hmacSha256(byte[] key, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(message);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    public static String hmacSha256Hex(byte[] key, String message) {
        return HEX.formatHex(hmacSha256(key, message.getBytes(StandardCharsets.UTF_8)));
    }

    public static String sha256Hex(String text) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sortedSha1Hex(String... values) {
        String[] copy = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            copy[i] = values[i] == null ? "" : values[i];
        }
        Arrays.sort(copy);
        StringBuilder sb = new StringBuilder();
        for (String v : copy) {
            sb.append(v);
        }
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-1").digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean constantTimeEqualsHex(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        return MessageDigest.isEqual(
                a.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                b.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
    }

    public static String randomNonce32() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return HEX.formatHex(raw);
    }

    // ---- RSA / EC / X.509 密钥解析 ----

    public static RSAPrivateKey parseRsaPrivateKey(String text) {
        byte[] der = pemOrBase64Der(text, "PRIVATE KEY", "RSA PRIVATE KEY");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            try {
                PrivateKey pk = kf.generatePrivate(new PKCS8EncodedKeySpec(der));
                if (pk instanceof RSAPrivateKey rsa) {
                    return rsa;
                }
            } catch (GeneralSecurityException ignored) {
                // 回落 PKCS#1
            }
            return (RSAPrivateKey) kf.generatePrivate(pkcs1PrivateToSpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("payment channel: invalid RSA private key", e);
        }
    }

    public static RSAPublicKey parseRsaPublicKey(String text) {
        byte[] der = pemOrBase64Der(text, "PUBLIC KEY", "RSA PUBLIC KEY");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            try {
                PublicKey pk = kf.generatePublic(new X509EncodedKeySpec(der));
                if (pk instanceof RSAPublicKey rsa) {
                    return rsa;
                }
            } catch (GeneralSecurityException ignored) {
                // 回落 PKCS#1 RSAPublicKey
            }
            return (RSAPublicKey) kf.generatePublic(pkcs1PublicToSpec(der));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("payment channel: invalid RSA public key", e);
        }
    }

    public static ECPrivateKey parseEcPrivateKey(String text) {
        byte[] der = pemOrBase64Der(text, "PRIVATE KEY");
        try {
            PrivateKey pk = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (pk instanceof ECPrivateKey ec) {
                return ec;
            }
            throw new IllegalArgumentException("payment channel: private key is not ECDSA");
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("payment channel: invalid EC PKCS#8 private key", e);
        }
    }

    public static List<X509Certificate> parseCertificates(String pemText) {
        if (pemText == null || pemText.isBlank()) {
            throw new IllegalArgumentException("payment channel: certificate PEM is empty");
        }
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> out = new ArrayList<>();
            int idx = 0;
            while (true) {
                int begin = pemText.indexOf("-----BEGIN CERTIFICATE-----", idx);
                if (begin < 0) {
                    break;
                }
                int end = pemText.indexOf("-----END CERTIFICATE-----", begin);
                if (end < 0) {
                    break;
                }
                String block = pemText.substring(begin, end + "-----END CERTIFICATE-----".length());
                X509Certificate cert = (X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(block.getBytes(StandardCharsets.US_ASCII)));
                out.add(cert);
                idx = end + "-----END CERTIFICATE-----".length();
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("payment channel: no certificate in PEM");
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("payment channel: certificate PEM cannot be parsed", e);
        }
    }

    private static byte[] pemOrBase64Der(String text, String... allowedTypes) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("payment channel: key is empty");
        }
        String trimmed = text.strip();
        if (trimmed.startsWith("-----BEGIN ")) {
            int firstNl = trimmed.indexOf('\n');
            if (firstNl < 0) {
                throw new IllegalArgumentException("payment channel: malformed PEM");
            }
            String header = trimmed.substring(0, firstNl).strip();
            String type = header.substring("-----BEGIN ".length(), header.length() - "-----".length());
            boolean ok = false;
            for (String allowed : allowedTypes) {
                if (allowed.equals(type)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) {
                throw new IllegalArgumentException("payment channel: unexpected PEM type " + type);
            }
            String footer = "-----END " + type + "-----";
            int endIdx = trimmed.indexOf(footer);
            if (endIdx < 0) {
                throw new IllegalArgumentException("payment channel: missing PEM footer");
            }
            String body = trimmed.substring(firstNl + 1, endIdx).replaceAll("\\s+", "");
            return Base64.getDecoder().decode(body);
        }
        return Base64.getDecoder().decode(trimmed.replaceAll("\\s+", ""));
    }

    // 极简 ASN.1 DER 读取器，仅用于兼容支付宝/OpenSSL PKCS#1 裸 RSA 密钥。
    private static RSAPrivateCrtKeySpec pkcs1PrivateToSpec(byte[] der) {
        DerReader seq = new DerReader(der).readSequence();
        seq.readBigInt(); // version
        BigInteger n = seq.readBigInt();
        BigInteger e = seq.readBigInt();
        BigInteger d = seq.readBigInt();
        BigInteger p = seq.readBigInt();
        BigInteger q = seq.readBigInt();
        BigInteger dp = seq.readBigInt();
        BigInteger dq = seq.readBigInt();
        BigInteger qi = seq.readBigInt();
        return new RSAPrivateCrtKeySpec(n, e, d, p, q, dp, dq, qi);
    }

    private static RSAPublicKeySpec pkcs1PublicToSpec(byte[] der) {
        DerReader seq = new DerReader(der).readSequence();
        return new RSAPublicKeySpec(seq.readBigInt(), seq.readBigInt());
    }

    private static final class DerReader {
        private final byte[] buf;
        private int pos;

        DerReader(byte[] buf) {
            this.buf = buf;
        }

        DerReader readSequence() {
            if (pos >= buf.length || buf[pos++] != 0x30) {
                throw new IllegalArgumentException("expected ASN.1 SEQUENCE");
            }
            int len = readLength();
            byte[] slice = Arrays.copyOfRange(buf, pos, pos + len);
            pos += len;
            return new DerReader(slice);
        }

        BigInteger readBigInt() {
            if (pos >= buf.length || buf[pos++] != 0x02) {
                throw new IllegalArgumentException("expected ASN.1 INTEGER");
            }
            int len = readLength();
            byte[] val = Arrays.copyOfRange(buf, pos, pos + len);
            pos += len;
            return new BigInteger(val);
        }

        private int readLength() {
            int b = buf[pos++] & 0xFF;
            if ((b & 0x80) == 0) {
                return b;
            }
            int count = b & 0x7F;
            if (count == 0 || count > 4 || pos + count > buf.length) {
                throw new IllegalArgumentException("invalid DER length");
            }
            int len = 0;
            for (int i = 0; i < count; i++) {
                len = (len << 8) | (buf[pos++] & 0xFF);
            }
            return len;
        }
    }

    // ---- RSA / ES256 / JWS / AES ----

    public static String signRsaSha256Base64(RSAPrivateKey key, byte[] message) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initSign(key);
            sig.update(message);
            return Base64.getEncoder().encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("RSA sign failed", e);
        }
    }

    public static boolean verifyRsaSha256Base64(RSAPublicKey key, byte[] message, String sigBase64) {
        try {
            byte[] sigBytes = Base64.getDecoder().decode(sigBase64);
            return verifyRsaSha256Raw(key, message, sigBytes);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean verifyRsaSha256Raw(RSAPublicKey key, byte[] message, byte[] sigBytes) {
        try {
            Signature sig = Signature.getInstance("SHA256withRSA");
            sig.initVerify(key);
            sig.update(message);
            return sig.verify(sigBytes);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public static byte[] signEs256Raw(ECPrivateKey key, byte[] message) {
        try {
            // JDK 11+ 标准 IEEE P1363 固定 64 字节 R||S 格式，与 JWS ES256 完全匹配。
            Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
            sig.initSign(key);
            sig.update(message);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("ES256 sign failed", e);
        }
    }

    public static boolean verifyEs256Raw(ECPublicKey key, byte[] message, byte[] signature64) {
        if (signature64 == null || signature64.length != 64) {
            return false;
        }
        try {
            Signature sig = Signature.getInstance("SHA256withECDSAinP1363Format");
            sig.initVerify(key);
            sig.update(message);
            return sig.verify(signature64);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    public static String signJwtEs256(Map<String, ?> header, Map<String, ?> claims, ECPrivateKey key) {
        return signJwt(header, claims, input -> signEs256Raw(key, input));
    }

    public static String signJwtRs256(Map<String, ?> header, Map<String, ?> claims, RSAPrivateKey key) {
        return signJwt(header, claims, input -> {
            try {
                Signature sig = Signature.getInstance("SHA256withRSA");
                sig.initSign(key);
                sig.update(input);
                return sig.sign();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("RS256 sign failed", e);
            }
        });
    }

    @FunctionalInterface
    private interface Signer {
        byte[] sign(byte[] input);
    }

    private static String signJwt(Map<String, ?> header, Map<String, ?> claims, Signer signer) {
        try {
            Base64.Encoder url = Base64.getUrlEncoder().withoutPadding();
            String h = url.encodeToString(JSON.writeValueAsBytes(header));
            String p = url.encodeToString(JSON.writeValueAsBytes(claims));
            String input = h + "." + p;
            byte[] sig = signer.sign(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + url.encodeToString(sig);
        } catch (IOException e) {
            throw new IllegalStateException("JWT encode failed", e);
        }
    }

    public record JwsParts(byte[] header, byte[] payload, byte[] signingInput, byte[] signature) {}

    public static JwsParts splitJws(String token) throws ChannelException {
        if (token == null) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "null JWS");
        }
        String[] segs = token.split("\\.", -1);
        if (segs.length != 3) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "not compact JWS");
        }
        try {
            Base64.Decoder dec = Base64.getUrlDecoder();
            return new JwsParts(
                    dec.decode(segs[0]),
                    dec.decode(segs[1]),
                    (segs[0] + "." + segs[1]).getBytes(StandardCharsets.US_ASCII),
                    dec.decode(segs[2]));
        } catch (IllegalArgumentException e) {
            throw new ChannelException(ChannelException.Kind.BAD_SIGNATURE, "invalid JWS base64url", e);
        }
    }

    public static byte[] aesGcmEncrypt(byte[] key32, byte[] nonce12, byte[] plain, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(128, nonce12));
            if (aad != null && aad.length > 0) {
                c.updateAAD(aad);
            }
            return c.doFinal(plain);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encrypt failed", e);
        }
    }

    public static byte[] aesGcmDecrypt(byte[] key32, byte[] nonce12, byte[] ciphertext, byte[] aad) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key32, "AES"), new GCMParameterSpec(128, nonce12));
        if (aad != null && aad.length > 0) {
            c.updateAAD(aad);
        }
        return c.doFinal(ciphertext);
    }

    public static byte[] aesCbcDecryptNoPadding(byte[] key32, byte[] iv16, byte[] ciphertext) throws GeneralSecurityException {
        Cipher c = Cipher.getInstance("AES/CBC/NoPadding");
        c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key32, "AES"), new IvParameterSpec(iv16));
        return c.doFinal(ciphertext);
    }

    public static byte[] randomBytes(int n) {
        byte[] out = new byte[n];
        RANDOM.nextBytes(out);
        return out;
    }

    // ---- 受限 HTTP 客户端 ----

    public static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public record HttpResult(int status, Map<String, List<String>> headers, byte[] body) {
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (name.equalsIgnoreCase(e.getKey()) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return "";
        }
    }

    /** 发一次渠道 HTTP 请求：传输失败时不回显含敏感参数的完整 URL，只保留方法与主机名。 */
    public static HttpResult send(HttpClient client, HttpRequest request) throws ChannelException {
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ChannelException(ChannelException.Kind.OTHER,
                    "payment channel: " + request.method() + " " + hostOf(request.uri()) + " interrupted", e);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER,
                    "payment channel: " + request.method() + " " + hostOf(request.uri()) + " failed", e);
        }
        try (InputStream in = resp.body()) {
            byte[] raw = in.readNBytes(MAX_RESPONSE_BYTES + 1);
            if (raw.length > MAX_RESPONSE_BYTES) {
                throw new ChannelException(ChannelException.Kind.OTHER, "payment channel: answer is too large");
            }
            return new HttpResult(resp.statusCode(), resp.headers().map(), raw);
        } catch (IOException e) {
            throw new ChannelException(ChannelException.Kind.OTHER,
                    "payment channel: reading answer from " + hostOf(request.uri()) + " failed", e);
        }
    }

    private static String hostOf(URI uri) {
        return uri == null || uri.getHost() == null ? "unknown" : uri.getHost();
    }
}
