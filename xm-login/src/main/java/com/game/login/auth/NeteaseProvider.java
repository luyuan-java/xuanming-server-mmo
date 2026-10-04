package com.game.login.auth;

import java.util.Optional;

/** 网易登录（同 mmorpg {@code NeteaseProvider}）：两版都是占位，恒失败（接入网易账号体系时再实现）。 */
public final class NeteaseProvider implements ExternalAuthProvider {

    @Override
    public Optional<String> authenticate(String authToken) {
        return Optional.empty();
    }
}
