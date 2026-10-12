package com.game.login.auth;

import java.util.Optional;

/**
 * 口令认证（auth_type 为空或 {@code password}）。开发共享密钥（{@link DevPasswordRule}）与生产 Argon2id
 * （{@link ProductionPasswordAuthenticator}）二选一，互斥。
 */
public interface PasswordAuthenticator {

    /**
     * 认证通过返回账号的规范值（生产实现取库里的值，不回显客户端输入）；失败为空，不区分原因。
     * 可阻塞（查库、算 KDF），只在 login 工作线程上调用。
     */
    Optional<String> authenticate(String account, String password);
}
