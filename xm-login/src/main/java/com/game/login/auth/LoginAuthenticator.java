package com.game.login.auth;

import com.game.login.token.LoginTokens;
import com.game.login.token.TokenData;
import com.game.proto.login.LoginRequest;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按 {@code LoginRequest.auth_type} 选认证方式，给出通过认证的账号。
 *
 * <p>契约（mmorpg 登录契约 §4.2）：
 * <ul>
 *   <li>{@code ""} 或 {@code "password"}：只走已启用的口令认证；没有启用（非 dev 模式）即失败（fail-closed）。</li>
 *   <li>{@code "access_token"}：{@code auth_token} 是此前签发的 access token，账号取令牌里存的账号，请求里的 {@code account} 忽略；
 *       令牌存储故障按认证失败处理（fail-closed）。</li>
 *   <li>其它类型（{@code satoken}、第三方）：随路线图 3.2，目前一律失败。</li>
 *   <li>失败只返回空，不区分原因：调用方统一回 2000 kLoginAccountNotFound，不向客户端泄露是账号还是口令不对。</li>
 * </ul>
 * 不可变，线程安全；access token 认证会读 Redis（阻塞），只在 login 工作线程上调用。
 */
public final class LoginAuthenticator {

    private static final Logger log = LoggerFactory.getLogger(LoginAuthenticator.class);

    public static final String AUTH_TYPE_PASSWORD = "password";
    public static final String AUTH_TYPE_ACCESS_TOKEN = "access_token";

    /**
     * 认证通过的结果。
     *
     * @param authType 生效的认证方式（空串已归一成 {@code password}）
     */
    public record Authenticated(String account, String authType) {

        /** access token 登录不再签新令牌（基线：避免令牌搅动）。 */
        public boolean issuesTokens() {
            return !AUTH_TYPE_ACCESS_TOKEN.equals(authType);
        }
    }

    /** 为 null 表示口令认证未启用。 */
    private final DevPasswordRule devPassword;
    private final LoginTokens tokens;

    private LoginAuthenticator(DevPasswordRule devPassword, LoginTokens tokens) {
        this.devPassword = devPassword;
        this.tokens = tokens;
    }

    public static LoginAuthenticator withDevPassword(DevPasswordRule rule, LoginTokens tokens) {
        if (rule == null) {
            throw new IllegalArgumentException("rule 不能为空；关闭口令认证请用 passwordDisabled()");
        }
        return new LoginAuthenticator(rule, tokens);
    }

    public static LoginAuthenticator passwordDisabled(LoginTokens tokens) {
        return new LoginAuthenticator(null, tokens);
    }

    public boolean passwordEnabled() {
        return devPassword != null;
    }

    /** 认证通过返回账号与生效的认证方式；否则返回空。 */
    public Optional<Authenticated> authenticate(LoginRequest request) {
        String authType = request.getAuthType();
        if (authType.isEmpty() || AUTH_TYPE_PASSWORD.equals(authType)) {
            if (devPassword != null && devPassword.accepts(request.getAccount(), request.getPassword())) {
                return Optional.of(new Authenticated(request.getAccount(), AUTH_TYPE_PASSWORD));
            }
            return Optional.empty();
        }
        if (AUTH_TYPE_ACCESS_TOKEN.equals(authType)) {
            Optional<TokenData> data;
            try {
                data = tokens.validateAccess(request.getAuthToken());
            } catch (RuntimeException e) {
                log.warn("access token 校验时令牌存储出错，按认证失败处理: {}", e.toString());
                return Optional.empty();
            }
            return data.map(d -> new Authenticated(d.account(), AUTH_TYPE_ACCESS_TOKEN));
        }
        return Optional.empty();
    }
}
