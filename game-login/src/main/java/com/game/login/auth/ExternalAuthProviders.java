package com.game.login.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import org.redisson.api.RedissonClient;

/**
 * 按配置注册的外部认证（auth_type → 认证）与它们持有的资源（Sa-Token 的 Redis 连接）。进程停时关掉。
 */
public final class ExternalAuthProviders implements AutoCloseable {

    private final Map<String, ExternalAuthProvider> providers = new LinkedHashMap<>();
    private RedissonClient satokenRedis;

    public ExternalAuthProviders satoken(RedissonClient redis, String tokenName, String loginType) {
        this.satokenRedis = redis;
        providers.put("satoken", new SaTokenProvider(redis, tokenName, loginType));
        return this;
    }

    public ExternalAuthProviders add(String authType, ExternalAuthProvider provider) {
        providers.put(authType, provider);
        return this;
    }

    public Map<String, ExternalAuthProvider> asMap() {
        return Map.copyOf(providers);
    }

    @Override
    public void close() {
        for (ExternalAuthProvider provider : providers.values()) {
            if (provider instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // 停机释放三方 HTTP 客户端，忽略个别关闭异常
                }
            }
        }
        if (satokenRedis != null) {
            satokenRedis.shutdown();
        }
    }
}
