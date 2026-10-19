package com.game.login.auth;

import java.util.Optional;

/**
 * 外部凭据认证（auth_type 为 {@code satoken} / {@code wechat} / {@code qq} / {@code netease}；同 mmorpg {@code auth.Provider}）：
 * {@code auth_token} → 账号。请求里的 {@code account} 一律忽略。
 */
public interface ExternalAuthProvider {

    /**
     * 认证通过返回账号；凭据无效为空（登录回 2000）。渠道或本服务渠道配置的故障（HTTP 失败 / 非 2xx / 坏应答、渠道侧错误码、
     * 缺必需的 unionid）抛 {@link AuthProviderUnavailableException}（登录回故障码 kLoginAuthProviderUnavailable）；
     * 其余异常（如 Sa-Token 的 Redis 出错）调用方仍按认证失败处理、回 2000。
     * 可阻塞（Redis / HTTP），只在 login 工作线程上调用。实现不得把凭据写进日志或异常消息。
     */
    Optional<String> authenticate(String authToken);
}
