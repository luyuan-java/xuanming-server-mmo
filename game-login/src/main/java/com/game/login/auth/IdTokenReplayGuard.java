package com.game.login.auth;

import java.time.Duration;
import java.util.Objects;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * 第三方 OIDC 身份令牌（Apple / Google / Passport）一次性消费登记（对齐 mmorpg {@code IdTokenReplayGuard}）。
 * 验签通过后按令牌 SHA-256 摘要原子占位至 {@code exp + leeway}，二次重放按凭证无效拒绝；存储故障抛出异常由调用方按渠道故障 fail-closed 处理。
 */
@FunctionalInterface
public interface IdTokenReplayGuard {

    /**
     * 原子登记一次令牌消费。
     *
     * @param key 键名（含渠道名与摘要）
     * @param ttl 保留时长（至少 1 秒）
     * @return true 表示首次消费放行；false 表示已被消费（重放拒绝）
     */
    boolean firstUse(String key, Duration ttl);

    /** 基于登录域 Redis {@code SET NX PX} 的实现。 */
    static IdTokenReplayGuard redis(RedissonClient redis) {
        Objects.requireNonNull(redis, "redis");
        return (key, ttl) -> {
            Duration effective = ttl == null || ttl.toMillis() < 1000L ? Duration.ofSeconds(1) : ttl;
            return redis.<String>getBucket(key, StringCodec.INSTANCE).setIfAbsent("1", effective);
        };
    }
}
