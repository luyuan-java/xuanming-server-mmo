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
 * refresh 按重放宽限窗口为 0 的语义建模（严格一次性：只有家族的 head 能轮换，已轮换的旧 refresh 一律无效）；
 * 不建模宽限重放与重用吊销，那两组语义由 {@code RedisLoginTokensIntegrationTest} 覆盖。
 * access 只看自己与家族是否在（同 Redis 版，不看 head）；内存版不吊销，家族一直在。
 * 截止与 Redis 版同一个位置：认出旧 refresh 之后、作废与签发之前看一次，已过就什么都不动、抛
 * {@link LoginTokens.RotationAbandoned}。
 */
public final class InMemoryLoginTokens implements LoginTokens {

    public final Map<String, TokenData> access = new HashMap<>();
    public final Map<String, TokenData> refresh = new HashMap<>();
    public boolean broken;
    public int issued;
    private int seq;
    private final Map<String, String> familyAccounts = new HashMap<>();
    private final Map<String, String> familyHeads = new HashMap<>();

    private String nextFamily() {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                java.nio.ByteBuffer.allocate(16).putLong(0).putLong(seq + 1L).array());
    }

    private boolean familyValid(TokenData data) {
        return data.familyId() != null && !data.familyId().isEmpty()
                && data.account().equals(familyAccounts.get(data.familyId()));
    }

    private TokenPair issueFamily(String account, String authType, String deviceId, String familyId) {
        TokenData data = new TokenData(account, authType, deviceId, 1000, familyId);
        String a = "access-" + (++seq);
        String r = "refresh-" + seq;
        access.put(a, data);
        refresh.put(r, data);
        familyAccounts.put(familyId, account);
        familyHeads.put(familyId, r);
        issued++;
        return new TokenPair(a, r, 1000 + 7200, 1000 + 720 * 3600);
    }

    @Override
    public synchronized TokenPair issue(String account, String authType, String deviceId) {
        check();
        return issueFamily(account, authType, deviceId, nextFamily());
    }

    @Override
    public synchronized Optional<TokenData> validateAccess(String accessToken) {
        check();
        return Optional.ofNullable(access.get(accessToken)).filter(this::familyValid);
    }

    @Override
    public synchronized Optional<TokenPair> refresh(String refreshToken, BooleanSupplier pastDeadline) {
        check();
        TokenData data = refresh.get(refreshToken);
        if (data == null || !familyValid(data) || !refreshToken.equals(familyHeads.get(data.familyId()))) {
            return Optional.empty();
        }
        if (pastDeadline.getAsBoolean()) {
            throw new LoginTokens.RotationAbandoned("请求截止已过，没有轮换（测试）");
        }
        refresh.remove(refreshToken);
        return Optional.of(issueFamily(data.account(), data.authType(), data.deviceId(), data.familyId()));
    }

    private void check() {
        if (broken) {
            throw new IllegalStateException("令牌存储不可达（测试注入）");
        }
    }
}
