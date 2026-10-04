package com.game.login.auth;

import com.game.login.token.LoginTokens;
import com.game.login.token.TokenData;
import com.game.proto.login.LoginRequest;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按 {@code LoginRequest.auth_type} 选认证方式，给出通过认证的账号（同 mmorpg {@code resolveAccount} 与 auth 注册表）。
 *
 * <p>契约（mmorpg 登录契约 §4.2）：
 * <ul>
 *   <li>{@code ""} 或 {@code "password"}：只走已启用的口令认证（开发共享密钥或生产 Argon2id，二选一）；都没启用即失败（fail-closed）。</li>
 *   <li>{@code "access_token"}：{@code auth_token} 是此前签发的 access token，账号取令牌里存的账号，请求里的 {@code account} 忽略。</li>
 *   <li>{@code "satoken"} / {@code "wechat"} / {@code "qq"} / {@code "netease"}：配置了才注册（{@link ExternalAuthProvider}），
 *       {@code auth_token} → 账号；没注册的类型失败。</li>
 *   <li>任何失败（含存储 / 外部系统出错）只返回空，不区分原因：调用方统一回 2000 kLoginAccountNotFound。</li>
 *   <li>认证出的账号要能落进 {@code account} 列（{@link ProductionPasswordAuthenticator#validAccount}：非空、无首尾空白、
 *       ≤ 64 码点），否则按认证失败（三方 loginId 过长时不让它在写库时变成「服务不可用」）。</li>
 * </ul>
 * 不可变，线程安全；会阻塞（Redis / MySQL / HTTP / KDF），只在 login 工作线程上调用。
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
    private final PasswordAuthenticator password;
    private final LoginTokens tokens;
    private final Map<String, ExternalAuthProvider> external;

    /**
     * @param password 口令认证（null = 关闭）
     * @param external auth_type → 外部认证（不含 password / access_token）
     */
    public LoginAuthenticator(PasswordAuthenticator password, LoginTokens tokens,
                              Map<String, ExternalAuthProvider> external) {
        if (external.containsKey(AUTH_TYPE_PASSWORD) || external.containsKey(AUTH_TYPE_ACCESS_TOKEN)
                || external.containsKey("")) {
            throw new IllegalArgumentException("外部认证不能占用 password / access_token / 空类型");
        }
        this.password = password;
        this.tokens = tokens;
        this.external = Map.copyOf(external);
    }

    public static LoginAuthenticator withDevPassword(DevPasswordRule rule, LoginTokens tokens) {
        if (rule == null) {
            throw new IllegalArgumentException("rule 不能为空；关闭口令认证请用 passwordDisabled()");
        }
        return new LoginAuthenticator(rule, tokens, Map.of());
    }

    public static LoginAuthenticator passwordDisabled(LoginTokens tokens) {
        return new LoginAuthenticator(null, tokens, Map.of());
    }

    public boolean passwordEnabled() {
        return password != null;
    }

    /** 已注册的外部认证类型（日志用）。 */
    public java.util.Set<String> externalTypes() {
        return external.keySet();
    }

    /** 认证通过返回账号与生效的认证方式；否则返回空。 */
    public Optional<Authenticated> authenticate(LoginRequest request) {
        Optional<Authenticated> result = resolve(request);
        if (result.isPresent() && !ProductionPasswordAuthenticator.validAccount(result.get().account())) {
            log.warn("认证出的账号放不进账号列（空 / 首尾空白 / 超过 64 码点），按认证失败处理 auth_type={} account_len={}",
                    result.get().authType(), result.get().account().length());
            return Optional.empty();
        }
        return result;
    }

    private Optional<Authenticated> resolve(LoginRequest request) {
        String authType = request.getAuthType();
        if (authType.isEmpty() || AUTH_TYPE_PASSWORD.equals(authType)) {
            if (password == null) {
                return Optional.empty();
            }
            return password.authenticate(request.getAccount(), request.getPassword())
                    .map(account -> new Authenticated(account, AUTH_TYPE_PASSWORD));
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
        ExternalAuthProvider provider = external.get(authType);
        if (provider == null) {
            return Optional.empty();
        }
        try {
            return provider.authenticate(request.getAuthToken()).map(account -> new Authenticated(account, authType));
        } catch (RuntimeException e) {
            // 只有本包实现自己抛的 IllegalStateException 打消息（只带类名 / 自己的说明）；其余异常只打类名，消息可能带凭据
            log.warn("外部认证出错，按认证失败处理 auth_type={}: {}", authType,
                    e instanceof IllegalStateException ? e.getMessage() : e.getClass().getName());
            return Optional.empty();
        }
    }
}
