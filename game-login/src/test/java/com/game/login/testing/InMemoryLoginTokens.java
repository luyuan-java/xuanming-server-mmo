package com.game.login.testing;

import com.game.login.token.LoginTokens;
import com.game.login.token.TokenData;
import com.game.login.token.TokenPair;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * 内存版令牌（测试用）：令牌按序号生成；可设为「存储故障」让每个操作都抛异常。
 * refresh 按重放宽限窗口为 0 的语义建模（严格一次性）；窗口内重放的语义由 {@code RedisLoginTokensIntegrationTest} 覆盖。
 * 截止与 Redis 版同一个位置：认出旧 refresh 之后、作废与签发之前看一次，已过就什么都不动、抛
 * {@link LoginTokens.RotationAbandoned}。
 */
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
    public synchronized Optional<TokenPair> refresh(String refreshToken, BooleanSupplier pastDeadline) {
        check();
        TokenData data = refresh.get(refreshToken);
        if (data == null) {
            return Optional.empty();
        }
        if (pastDeadline.getAsBoolean()) {
            throw new LoginTokens.RotationAbandoned("请求截止已过，没有轮换（测试）");
        }
        refresh.remove(refreshToken);
        return Optional.of(issue(data.account(), data.authType(), data.deviceId()));
    }

    private void check() {
        if (broken) {
            throw new IllegalStateException("令牌存储不可达（测试注入）");
        }
    }
}
