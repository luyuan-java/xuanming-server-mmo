package com.game.login.auth;

import com.game.login.support.GoSpaces;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * 开发口令认证规则（与 mmorpg {@code DevelopmentPasswordProvider} 同义）：受控的开发 / 机器人登录入口，不是生产口令实现。
 *
 * <p>通过条件（全部满足）：
 * <ol>
 *   <li>账号非空，且首尾没有空白（Go {@code strings.TrimSpace} 口径，见 {@link GoSpaces}）；</li>
 *   <li>账号以白名单前缀之一开头（默认 {@code robot_} / {@code dev_}）；</li>
 *   <li>账号不超过 {@value #MAX_ACCOUNT_CHARS} 个码点——Java 版独有：{@code account.account} 列是 VARCHAR(64)，
 *       超长账号在这里按认证失败拒绝，而不是等写库时报错变成「服务不可用」；</li>
 *   <li>口令与共享密钥相等。比较的是两者的 SHA-256 摘要，用 {@link MessageDigest#isEqual} 常数时间比较，
 *       耗时与口令内容和长度都无关。</li>
 * </ol>
 *
 * <p>不可变，线程安全。共享密钥只保存摘要，不保留明文。
 */
public final class DevPasswordRule {

    public static final int MAX_ACCOUNT_CHARS = 64;

    private final byte[] secretDigest;
    private final List<String> accountPrefixes;

    /**
     * @throws IllegalArgumentException 密钥为空，或前缀列表为空 / 含空白前缀（与 Go 同样在构造时拒绝）
     */
    public DevPasswordRule(String sharedSecret, List<String> accountPrefixes) {
        if (sharedSecret == null || sharedSecret.isEmpty()) {
            throw new IllegalArgumentException("开发口令认证需要非空的共享密钥");
        }
        List<String> prefixes = new ArrayList<>();
        for (String prefix : accountPrefixes) {
            String trimmed = prefix == null ? "" : GoSpaces.trim(prefix);
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("开发口令认证的账号前缀里有空值");
            }
            prefixes.add(trimmed);
        }
        if (prefixes.isEmpty()) {
            throw new IllegalArgumentException("开发口令认证至少需要一个账号前缀");
        }
        this.secretDigest = sha256(sharedSecret);
        this.accountPrefixes = List.copyOf(prefixes);
    }

    public boolean accepts(String account, String password) {
        if (account == null || account.isEmpty() || !account.equals(GoSpaces.trim(account))) {
            return false;
        }
        if (account.codePointCount(0, account.length()) > MAX_ACCOUNT_CHARS) {
            return false;
        }
        boolean allowedPrefix = false;
        for (String prefix : accountPrefixes) {
            if (account.startsWith(prefix)) {
                allowedPrefix = true;
                break;
            }
        }
        // 前缀不符也照样算一次摘要比较，让两种失败的耗时一致。
        boolean passwordOk = MessageDigest.isEqual(secretDigest, sha256(password == null ? "" : password));
        return allowedPrefix && passwordOk;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
