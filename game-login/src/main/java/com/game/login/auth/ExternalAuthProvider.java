package com.game.login.auth;

import java.util.Optional;

/**
 * 外部凭据认证（auth_type 为 {@code satoken} / {@code wechat} / {@code qq} / {@code netease}；同 mmorpg {@code auth.Provider}）：
 * {@code auth_token} → 账号。请求里的 {@code account} 一律忽略。
 */
public interface ExternalAuthProvider {

    /**
     * 认证通过返回账号；凭据无效为空。外部系统不可达等故障抛异常（调用方按认证失败处理，同样回 2000）。
     * 可阻塞（Redis / HTTP），只在 login 工作线程上调用。实现不得把凭据写进日志。
     */
    Optional<String> authenticate(String authToken);
}
