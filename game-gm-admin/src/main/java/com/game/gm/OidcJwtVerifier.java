package com.game.gm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 零新增第三方依赖的后台管理员 OIDC JWT 验签器（支持 RS256 / ES256 / EdDSA）：
 * <ul>
 *   <li>核对 {@code iss}、{@code aud}、{@code exp}、{@code nbf} 与 12 小时会话硬上限（{@code now - iat <= 43200s}）；</li>
 *   <li>操作人标识直接取外部身份提供方 {@code sub}（严格校验合法用户名字符集）；</li>
 *   <li>每次认证按 IdP 分组声明（{@code groups} / {@code roles}）整体替换权限集，不在 Java 版自建口令库。</li>
 * </ul>
 */
public final class OidcJwtVerifier {

    /** 会话最长硬上限：12 小时（对齐 admin-identity-and-dual-control.md）。 */
    public static final long MAX_SESSION_AGE_SECONDS = 12L * 3600L;
    private static final long CLOCK_SKEW_SECONDS = 30L;
    private static final byte[] ED25519_X509_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");

    private final ObjectMapper json;
    private final String issuer;
    private final String audience;
    private final Map<String, PublicKey> keysByKid;
    private final Map<String, Set<String>> groupPermissions;
    private final Clock clock;

    public OidcJwtVerifier(
            ObjectMapper json,
            String issuer,
            String audience,
            Map<String, PublicKey> keysByKid,
            Map<String, Set<String>> groupPermissions,
            Clock clock) {
        this.json = Objects.requireNonNull(json, "json");
        this.issuer = Objects.requireNonNull(issuer, "issuer").strip();
        this.audience = Objects.requireNonNull(audience, "audience").strip();
        if (this.issuer.isEmpty() || this.audience.isEmpty()) {
            throw new IllegalArgumentException("OIDC issuer 与 audience 不能为空");
        }
        this.keysByKid = Map.copyOf(Objects.requireNonNull(keysByKid, "keysByKid"));
        if (this.keysByKid.isEmpty()) {
            throw new IllegalArgumentException("OIDC JWKS 公钥集不能为空");
        }
        this.groupPermissions = Map.copyOf(groupPermissions == null ? Map.of() : groupPermissions);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 从标准 JWKS JSON（含 RSA / EC P-256 / OKP Ed25519）构造公钥表。 */
    public static Map<String, PublicKey> parseJwks(ObjectMapper json, String jwksJson) {
        try {
            JsonNode root = json.readTree(jwksJson);
            JsonNode keys = root.path("keys");
            if (!keys.isArray() || keys.isEmpty()) {
                throw new IllegalArgumentException("JWKS 缺少 keys 数组");
            }
            Map<String, PublicKey> out = new HashMap<>();
            for (JsonNode k : keys) {
                String kid = k.path("kid").asText("");
                String kty = k.path("kty").asText("");
                PublicKey pub = switch (kty) {
                    case "RSA" -> rsaKey(k);
                    case "EC" -> ecP256Key(k);
                    case "OKP" -> ed25519Key(k);
                    default -> throw new IllegalArgumentException("不支持的 JWKS kty: " + kty);
                };
                out.put(kid, pub);
            }
            return out;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 OIDC JWKS 失败: " + e.getMessage(), e);
        }
    }

    public GmAdminAuth.Principal verify(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少 OIDC 令牌");
        }
        String[] parts = jwt.strip().split("\\.");
        if (parts.length != 3) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC 令牌格式非法");
        }
        try {
            JsonNode header = json.readTree(b64UrlDecode(parts[0]));
            JsonNode claims = json.readTree(b64UrlDecode(parts[1]));
            byte[] signatureBytes = b64UrlDecode(parts[2]);

            String alg = header.path("alg").asText("");
            String kid = header.path("kid").asText("");
            PublicKey pub = keysByKid.get(kid);
            if (pub == null && keysByKid.size() == 1 && kid.isEmpty()) {
                pub = keysByKid.values().iterator().next();
            }
            if (pub == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未找到匹配的 OIDC 公钥 kid");
            }

            byte[] signingInput = (parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII);
            if (!verifySignature(alg, pub, signingInput, signatureBytes)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC 令牌验签失败");
            }

            long nowSec = clock.instant().getEpochSecond();
            if (!issuer.equals(claims.path("iss").asText(""))) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC iss 不匹配");
            }
            if (!matchesAudience(claims.get("aud"), audience)) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC aud 不匹配");
            }
            long exp = claims.path("exp").asLong(0L);
            if (exp <= 0L || nowSec > exp + CLOCK_SKEW_SECONDS) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC 令牌已过期");
            }
            long nbf = claims.path("nbf").asLong(0L);
            if (nbf > 0L && nowSec + CLOCK_SKEW_SECONDS < nbf) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC 令牌尚未生效");
            }
            long iat = claims.path("iat").asLong(0L);
            if (iat <= 0L || nowSec - iat > MAX_SESSION_AGE_SECONDS + CLOCK_SKEW_SECONDS) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC 会话超过 12 小时硬上限，请重新登录");
            }

            String sub = claims.path("sub").asText("").strip();
            GmAdminAuth.validateUsername(sub);

            Set<String> perms = new LinkedHashSet<>();
            collectGroupPermissions(claims.get("groups"), perms);
            collectGroupPermissions(claims.get("roles"), perms);
            return new GmAdminAuth.Principal(sub, Set.copyOf(perms));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "OIDC 令牌校验失败");
        }
    }

    private void collectGroupPermissions(JsonNode node, Set<String> out) {
        if (node == null || !node.isArray()) {
            return;
        }
        for (JsonNode g : node) {
            if (g.isTextual()) {
                Set<String> mapped = groupPermissions.get(g.asText().strip());
                if (mapped != null) {
                    out.addAll(mapped);
                }
            }
        }
    }

    private static boolean matchesAudience(JsonNode aud, String expected) {
        if (aud == null || aud.isNull()) {
            return false;
        }
        if (aud.isTextual()) {
            return expected.equals(aud.asText());
        }
        if (aud.isArray()) {
            for (JsonNode item : aud) {
                if (item.isTextual() && expected.equals(item.asText())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean verifySignature(String alg, PublicKey pub, byte[] input, byte[] sigBytes)
            throws GeneralSecurityException {
        String jcaAlg = switch (alg) {
            case "RS256" -> "SHA256withRSA";
            case "ES256" -> "SHA256withECDSAinP1363Format";
            case "EdDSA" -> "Ed25519";
            default -> null;
        };
        if (jcaAlg == null) {
            return false;
        }
        Signature sig = Signature.getInstance(jcaAlg);
        sig.initVerify(pub);
        sig.update(input);
        return sig.verify(sigBytes);
    }

    private static PublicKey rsaKey(JsonNode k) throws GeneralSecurityException {
        BigInteger n = new BigInteger(1, b64UrlDecode(k.path("n").asText("")));
        BigInteger e = new BigInteger(1, b64UrlDecode(k.path("e").asText("")));
        return KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(n, e));
    }

    private static PublicKey ecP256Key(JsonNode k) throws GeneralSecurityException {
        BigInteger x = new BigInteger(1, b64UrlDecode(k.path("x").asText("")));
        BigInteger y = new BigInteger(1, b64UrlDecode(k.path("y").asText("")));
        AlgorithmParameters params = AlgorithmParameters.getInstance("EC");
        params.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec spec = params.getParameterSpec(ECParameterSpec.class);
        return KeyFactory.getInstance("EC").generatePublic(new ECPublicKeySpec(new ECPoint(x, y), spec));
    }

    private static PublicKey ed25519Key(JsonNode k) throws GeneralSecurityException {
        byte[] raw = b64UrlDecode(k.path("x").asText(""));
        if (raw.length != 32) {
            throw new IllegalArgumentException("Ed25519 JWK x 长度非法");
        }
        byte[] x509 = new byte[ED25519_X509_PREFIX.length + raw.length];
        System.arraycopy(ED25519_X509_PREFIX, 0, x509, 0, ED25519_X509_PREFIX.length);
        System.arraycopy(raw, 0, x509, ED25519_X509_PREFIX.length, raw.length);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(x509));
    }

    private static byte[] b64UrlDecode(String s) {
        return Base64.getUrlDecoder().decode(s);
    }
}
