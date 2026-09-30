package com.game.common.token;

import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** HMAC-SHA256 → 64 字节小写 hex（gate 令牌与节点链路鉴权共用）。无状态，线程安全（每次调用新建 {@link Mac}）。 */
final class HmacSha256Hex {

    static final String ALGORITHM = "HmacSHA256";

    private HmacSha256Hex() {
    }

    /** @throws IllegalArgumentException 密钥为空（{@code what} 写进异常信息，说明是哪把密钥） */
    static SecretKeySpec key(byte[] secret, String what) {
        if (secret == null || secret.length == 0) {
            throw new IllegalArgumentException(what + "不能为空");
        }
        return new SecretKeySpec(secret.clone(), ALGORITHM);
    }

    static String hex(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(data));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
