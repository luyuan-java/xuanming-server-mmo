package com.game.common.token;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * gate 到在线服务的会话身份非对称签名（Ed25519）：
 * <ul>
 *   <li>仅 {@code game-gate} 持有私钥（{@link #PRIVATE_KEY_ENV}），负责对 {@code SessionContext} 身份声明签名；</li>
 *   <li>login 与全部在线服务只持公钥列表（{@link #PUBLIC_KEYS_ENV}），无法签发，任一验证方被攻破均无法冒充 gate；</li>
 *   <li>公钥列表支持新旧并列（逗号/分号/空白分隔），满足不停服轮换；</li>
 *   <li>gate 启动时用私钥签发探针并对照公钥列表自检，配对失败立即拒启（fail-closed）。</li>
 * </ul>
 */
public final class GateSessionIdentityAuth {

    public static final String PRIVATE_KEY_ENV = "GAME_GATE_IDENTITY_PRIVATE_KEY";
    public static final String PUBLIC_KEYS_ENV = "GAME_GATE_IDENTITY_PUBLIC_KEYS";
    public static final String REQUIRED_ENV = "GAME_GATE_IDENTITY_REQUIRED";

    public static final String TIMESTAMP_KEY = "x-gate-id-ts";
    public static final String SIGNATURE_KEY = "x-gate-id-sig";

    /** 默认允许时钟偏差（秒），与 DubboCallAuth 同口径。 */
    public static final long MAX_SKEW_SECONDS = 60L;

    /** RFC 8410 Ed25519 PKCS#8 固定前缀（后接 32 字节原始私钥种子）。 */
    private static final byte[] ED25519_PKCS8_PREFIX = HexFormat.of().parseHex("302e020100300506032b657004220420");
    /** RFC 8410 Ed25519 X.509 SubjectPublicKeyInfo 固定前缀（后接 32 字节原始公钥）。 */
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final byte[] PAIR_CHECK_PROBE = "gate-ed25519-keypair-probe-v1".getBytes(StandardCharsets.UTF_8);

    public enum Verdict {
        OK,
        MISSING,
        EXPIRED,
        INVALID
    }

    private final PrivateKey privateKey;
    private final List<PublicKey> publicKeys;
    private final long maxSkewSeconds;

    private GateSessionIdentityAuth(PrivateKey privateKey, List<PublicKey> publicKeys, long maxSkewSeconds) {
        this.privateKey = privateKey;
        this.publicKeys = List.copyOf(publicKeys);
        this.maxSkewSeconds = maxSkewSeconds;
    }

    /** 构造 gate 签名端：必须提供有效私钥；若同时提供公钥列表，启动时强制核对私钥与其中至少一把公钥配对。 */
    public static GateSessionIdentityAuth forSigner(String privateKeySpec, String publicKeysSpec) {
        if (privateKeySpec == null || privateKeySpec.isBlank()) {
            throw new IllegalStateException("gate 会话身份私钥未配置（" + PRIVATE_KEY_ENV + "）");
        }
        PrivateKey priv = parsePrivateKey(privateKeySpec);
        List<PublicKey> pubs = (publicKeysSpec == null || publicKeysSpec.isBlank())
                ? List.of() : parsePublicKeys(publicKeysSpec);
        if (!pubs.isEmpty()) {
            verifyKeyPairMatch(priv, pubs);
        }
        return new GateSessionIdentityAuth(priv, pubs, MAX_SKEW_SECONDS);
    }

    /** 构造后端验证端：仅持有公钥列表（零私钥），支持轮换期多把公钥并列。 */
    public static GateSessionIdentityAuth forVerifier(String publicKeysSpec) {
        if (publicKeysSpec == null || publicKeysSpec.isBlank()) {
            throw new IllegalStateException("gate 会话身份公钥列表未配置（" + PUBLIC_KEYS_ENV + "）");
        }
        List<PublicKey> pubs = parsePublicKeys(publicKeysSpec);
        return new GateSessionIdentityAuth(null, pubs, MAX_SKEW_SECONDS);
    }

    /** 直接由 JDK KeyPair 构造（测试与运维工具）。 */
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
            return gen.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("JDK 不支持 Ed25519 密钥生成", e);
        }
    }

    public static String encodePrivateKeyBase64(PrivateKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static String encodePublicKeyBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public boolean canSign() {
        return privateKey != null;
    }

    public int publicKeyCount() {
        return publicKeys.size();
    }

    /** 使用 gate 独有 Ed25519 私钥签名规范化载荷，返回 Base64 编码签名。验证方调用一律抛 UnsupportedOperationException。 */
    public String sign(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        if (privateKey == null) {
            throw new UnsupportedOperationException("仅持有公钥的验证方不能签发 gate 会话身份声明");
        }
        return Base64.getEncoder().encodeToString(signRaw(privateKey, payload));
    }

    /** 验签：先校验时间窗，再用公钥列表中任一把有效公钥核验 Ed25519 签名。 */
    public Verdict verify(byte[] payload, String timestampHeader, String signatureHeader, long nowEpochSeconds) {
        Objects.requireNonNull(payload, "payload");
        if (publicKeys.isEmpty()) {
            return Verdict.INVALID;
        }
        if (timestampHeader == null || timestampHeader.isBlank()
                || signatureHeader == null || signatureHeader.isBlank()) {
            return Verdict.MISSING;
        }
        long ts;
        try {
            ts = Long.parseLong(timestampHeader.strip());
        } catch (NumberFormatException e) {
            return Verdict.INVALID;
        }
        if (Math.abs(nowEpochSeconds - ts) > maxSkewSeconds) {
            return Verdict.EXPIRED;
        }
        byte[] sigBytes;
        try {
            sigBytes = Base64.getDecoder().decode(signatureHeader.strip());
        } catch (IllegalArgumentException e) {
            return Verdict.INVALID;
        }
        if (sigBytes.length != 64) {
            return Verdict.INVALID;
        }
        for (PublicKey pub : publicKeys) {
            if (verifyRaw(pub, payload, sigBytes)) {
                return Verdict.OK;
            }
        }
        return Verdict.INVALID;
    }

    private static void verifyKeyPairMatch(PrivateKey priv, List<PublicKey> pubs) {
        byte[] sig = signRaw(priv, PAIR_CHECK_PROBE);
        for (PublicKey pub : pubs) {
            if (verifyRaw(pub, PAIR_CHECK_PROBE, sig)) {
                return;
            }
        }
        throw new IllegalStateException("gate Ed25519 私钥与配置的公钥列表不配对，拒绝启动");
    }

    private static byte[] signRaw(PrivateKey priv, byte[] payload) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(priv);
            sig.update(payload);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 签名失败", e);
        }
    }

    private static boolean verifyRaw(PublicKey pub, byte[] payload, byte[] signature) {
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(pub);
            sig.update(payload);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static PrivateKey parsePrivateKey(String raw) {
        byte[] decoded = decodeKeyBytes(raw, "私钥");
        byte[] pkcs8 = decoded.length == 32 ? concat(ED25519_PKCS8_PREFIX, decoded) : decoded;
        try {
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("解析 gate Ed25519 私钥失败", e);
        }
    }

    static List<PublicKey> parsePublicKeys(String spec) {
        String normalized = stripPemEnvelope(spec);
        // 允许多段 PEM 或逗号/分号/换行/空白分隔的 Base64/Hex 公钥
        List<String> tokens = splitKeyTokens(spec, normalized);
        if (tokens.isEmpty()) {
            throw new IllegalStateException("gate Ed25519 公钥列表为空");
        }
        List<PublicKey> keys = new ArrayList<>(tokens.size());
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            for (String token : tokens) {
                byte[] decoded = decodeKeyBytes(token, "公钥");
                byte[] x509 = decoded.length == 32 ? concat(ED25519_X509_PREFIX, decoded) : decoded;
                keys.add(kf.generatePublic(new X509EncodedKeySpec(x509)));
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("解析 gate Ed25519 公钥失败", e);
        }
        return keys;
    }

    private static List<String> splitKeyTokens(String original, String stripped) {
        if (original.contains("-----BEGIN")) {
            List<String> pemParts = new ArrayList<>();
            int idx = 0;
            while (true) {
                int begin = original.indexOf("-----BEGIN", idx);
                if (begin < 0) {
                    break;
                }
                int end = original.indexOf("-----END", begin);
                if (end < 0) {
                    throw new IllegalStateException("PEM 公钥缺少 END 标记");
                }
                int endLine = original.indexOf("-----", end + 8);
                int tail = endLine < 0 ? original.length() : endLine + 5;
                pemParts.add(original.substring(begin, tail));
                idx = tail;
            }
            return pemParts;
        }
        List<String> out = new ArrayList<>();
        for (String part : stripped.split("[,;\\s]+")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static byte[] decodeKeyBytes(String input, String label) {
        String cleaned = stripPemEnvelope(input).replaceAll("\\s+", "");
        if (cleaned.isEmpty()) {
            throw new IllegalStateException("gate Ed25519 " + label + "为空");
        }
        // 64 位十六进制按 32 字节原始 Ed25519 种子/公钥解析
        if (cleaned.length() == 64 && cleaned.matches("[0-9a-fA-F]+")) {
            return HexFormat.of().parseHex(cleaned);
        }
        try {
            return Base64.getDecoder().decode(cleaned);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("gate Ed25519 " + label + "既不是合法 Base64 也不是 64 位 Hex", e);
        }
    }

    private static String stripPemEnvelope(String input) {
        if (input == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(input.length());
        for (String line : input.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("-----BEGIN") || trimmed.startsWith("-----END")) {
                continue;
            }
            sb.append(trimmed).append('\n');
        }
        return sb.toString().strip();
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
