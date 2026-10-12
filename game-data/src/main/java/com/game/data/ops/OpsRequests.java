package com.game.data.ops;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 运维请求的公共校验（data-ops-spec §7.4、§7.6）：幂等键、原因 / 备注文本、无符号号码、请求指纹。校验失败一律
 * {@link OpsException#badRequest}（400 {@code invalid_request}）。
 */
public final class OpsRequests {

    /** 写接口必带的幂等键请求头。 */
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final int MAX_IDEMPOTENCY_KEY = 64;
    /** 原因 / 备注的上限（字符，按码点）。 */
    public static final int MAX_TEXT = 256;

    private OpsRequests() {
    }

    /** 幂等键：必填，1–64 个可见 ASCII（0x21–0x7E）。 */
    public static String idempotencyKey(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_IDEMPOTENCY_KEY
                || raw.chars().anyMatch(ch -> ch < 0x21 || ch > 0x7E)) {
            throw OpsException.badRequest("缺少或非法的 " + IDEMPOTENCY_HEADER + "（1–" + MAX_IDEMPOTENCY_KEY + " 个可见 ASCII 字符）");
        }
        return raw;
    }

    /** 原因：必填，1–256 字符、不全是空白、不含控制字符（它会进审计日志与作业行）。 */
    public static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw OpsException.badRequest("reason 必填（1–" + MAX_TEXT + " 字符、不含控制字符）");
        }
        return text("reason", reason);
    }

    /** 可选文本（备注等）：缺省空串；给了就 ≤ 256 字符、不含控制字符。 */
    public static String text(String name, String value) {
        if (value == null) {
            return "";
        }
        if (value.codePointCount(0, value.length()) > MAX_TEXT || value.codePoints().anyMatch(Character::isISOControl)) {
            throw OpsException.badRequest(name + " 至多 " + MAX_TEXT + " 字符、不含控制字符");
        }
        return value;
    }

    /** 无符号 64 位十进制（玩家号、流水号、快照号……）；0 视为非法时由调用方另判。 */
    public static long u64(String name, String value) {
        if (value == null || value.isEmpty() || value.charAt(0) == '+' || value.charAt(0) == '-') {
            throw OpsException.badRequest(name + " 必须是无符号十进制整数");
        }
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException e) {
            throw OpsException.badRequest(name + " 必须是无符号十进制整数");
        }
    }

    /** 无符号 32 位十进制（配置号、币种）。 */
    public static int u32(String name, String value) {
        if (value == null || value.isEmpty() || value.charAt(0) == '+' || value.charAt(0) == '-') {
            throw OpsException.badRequest(name + " 必须是无符号十进制整数");
        }
        try {
            return Integer.parseUnsignedInt(value);
        } catch (NumberFormatException e) {
            throw OpsException.badRequest(name + " 必须是 0–4294967295 的十进制整数");
        }
    }

    /** 请求指纹：SHA-256(方法 + 空格 + 路径 + 换行 + 规范化请求) 的小写十六进制。同一幂等键必须对应同一指纹。 */
    public static String requestHash(String method, String path, String canonical) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha.digest((method + " " + path + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺 SHA-256", e);
        }
    }
}
