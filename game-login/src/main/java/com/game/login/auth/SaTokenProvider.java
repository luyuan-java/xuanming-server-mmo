package com.game.login.auth;

import java.util.Optional;
import java.util.regex.Pattern;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * Sa-Token 认证（同 mmorpg {@code SaTokenProvider}）：在 Sa-Token 认证服务所用的 Redis 上读
 * {@code {tokenName}:{loginType}:token:{token}}，取到的 loginId 直接当账号（不加前缀，同基线）。
 * Redis 由调用方单独配置（与本进程的业务 Redis 分开）。不把令牌写进日志（基线在 Info 日志里打原始令牌，属凭据泄露，不照抄）。
 * 令牌只接受可打印 ASCII（不含空白与冒号），其余直接无效：客户端任意文本不会拼进 Redis 键。
 */
public final class SaTokenProvider implements ExternalAuthProvider {

    private static final Pattern TOKEN = Pattern.compile("[\\x21-\\x39\\x3B-\\x7E]{1,256}");

    private final RedissonClient redis;
    private final String tokenName;
    private final String loginType;

    /**
     * @param tokenName Sa-Token 的键前缀（缺省 satoken）
     * @param loginType Sa-Token 的登录类型（缺省 login）
     */
    public SaTokenProvider(RedissonClient redis, String tokenName, String loginType) {
        this.redis = redis;
        this.tokenName = tokenName;
        this.loginType = loginType;
    }

    @Override
    public Optional<String> authenticate(String authToken) {
        if (authToken == null || !TOKEN.matcher(authToken).matches()) {
            return Optional.empty();
        }
        String loginId;
        try {
            loginId = redis.<String>getBucket(tokenName + ":" + loginType + ":token:" + authToken, StringCodec.INSTANCE)
                    .get();
        } catch (RuntimeException e) {
            // 不带原异常：Redisson 的异常消息里有命令参数（令牌）
            throw new IllegalStateException("Sa-Token Redis 查询失败: " + e.getClass().getSimpleName());
        }
        return loginId == null || loginId.isEmpty() ? Optional.empty() : Optional.of(loginId);
    }
}
