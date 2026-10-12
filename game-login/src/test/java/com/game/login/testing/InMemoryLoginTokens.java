package com.game.login.testing;

import com.game.login.token.LoginTokens;
import com.game.login.token.TokenData;
import com.game.login.token.TokenPair;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** 内存版令牌（测试用）：令牌按序号生成；可设为「存储故障」让每个操作都抛异常。 */
public final class InMemoryLoginTokens implements LoginTokens {

    public final Map<String, TokenData> access = new HashMap<>();
    public final Map<String, TokenData> refresh = new HashMap<>();
    public boolean broken;
    public int issued;
    private int seq;

    @Override
    public synchronized TokenPair issue(String account, String authType, String deviceId) {
        check();
        TokenData data = new TokenData(account, authType, deviceId, 1000);
        String a = "access-" + (++seq);
        String r = "refresh-" + seq;
        access.put(a, data);
        refresh.put(r, data);
        issued++;
        return new TokenPair(a, r, 1000 + 7200, 1000 + 720 * 3600);
    }

    @Override
    public synchronized Optional<TokenData> validateAccess(String accessToken) {
        check();
        return Optional.ofNullable(access.get(accessToken));
    }

    @Override
    public synchronized Optional<TokenPair> refresh(String refreshToken) {
        check();
        TokenData data = refresh.remove(refreshToken);
        if (data == null) {
            return Optional.empty();
        }
        return Optional.of(issue(data.account(), data.authType(), data.deviceId()));
    }

    private void check() {
        if (broken) {
            throw new IllegalStateException("令牌存储不可达（测试注入）");
        }
    }
}
