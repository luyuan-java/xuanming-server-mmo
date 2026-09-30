package com.game.login.auth;

import com.game.proto.login.LoginRequest;
import java.util.Optional;

/**
 * 按 {@code LoginRequest.auth_type} 选认证方式，给出通过认证的账号。
 *
 * <p>契约（mmorpg 登录契约 §4.2）：
 * <ul>
 *   <li>{@code ""} 或 {@code "password"}：只走已启用的口令认证；没有启用（非 dev 模式）即失败（fail-closed）。</li>
 *   <li>其它类型（{@code access_token}、{@code satoken}、第三方）：本批未实现，一律失败。
 *       机器人在 access_token 失败后会在同一连接上回退到口令登录，所以不影响冒烟链路。</li>
 *   <li>失败只返回空，不区分原因：调用方统一回 2000 kLoginAccountNotFound，不向客户端泄露是账号还是口令不对。</li>
 * </ul>
 * 不可变，线程安全。
 */
public final class LoginAuthenticator {

    public static final String AUTH_TYPE_PASSWORD = "password";

    /** 为 null 表示口令认证未启用。 */
    private final DevPasswordRule devPassword;

    private LoginAuthenticator(DevPasswordRule devPassword) {
        this.devPassword = devPassword;
    }

    public static LoginAuthenticator withDevPassword(DevPasswordRule rule) {
        if (rule == null) {
            throw new IllegalArgumentException("rule 不能为空；关闭口令认证请用 passwordDisabled()");
        }
        return new LoginAuthenticator(rule);
    }

    public static LoginAuthenticator passwordDisabled() {
        return new LoginAuthenticator(null);
    }

    public boolean passwordEnabled() {
        return devPassword != null;
    }

    /** 认证通过返回账号（即请求里的 {@code account} 原样）；否则返回空。 */
    public Optional<String> authenticate(LoginRequest request) {
        String authType = request.getAuthType();
        if (authType.isEmpty() || AUTH_TYPE_PASSWORD.equals(authType)) {
            if (devPassword != null && devPassword.accepts(request.getAccount(), request.getPassword())) {
                return Optional.of(request.getAccount());
            }
            return Optional.empty();
        }
        return Optional.empty();
    }
}
