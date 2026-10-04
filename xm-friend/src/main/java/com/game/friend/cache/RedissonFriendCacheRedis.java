package com.game.friend.cache;

import java.util.List;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * {@link FriendCache.CacheRedis} 的 Redisson 实现。三段 Lua 各是一次原子调用；用单条 {@code evalAsync}（遇 NOSCRIPT 自动重载），
 * 不用 RBatch。
 *
 * <p>Redisson 在响应超时后会重发同一段 EVAL（EVAL 不在它的不重试名单里）：取代次是「有就读、没有才写」，重发无害；回填写的是同一份值；
 * 失效把服务器 {@code TIME} 拼进代次，重发时代次已不同——不会把后来的写者设的代次改回读者手里的旧值（ABA）。
 */
public final class RedissonFriendCacheRedis implements FriendCache.CacheRedis {

    /** KEYS[1] = 代次键；ARGV[1] = 候选代次，ARGV[2] = TTL 毫秒。缺失时写入候选值，返回当前代次。 */
    static final String GENERATION = """
            local generation = redis.call('GET', KEYS[1])
            if not generation then
              generation = ARGV[1]
              redis.call('SET', KEYS[1], generation, 'PX', ARGV[2])
            end
            return generation
            """;

    /** KEYS[1] = 代次键，KEYS[2] = 数据键；ARGV[1] = 读到的代次，ARGV[2] = 值，ARGV[3] = TTL 毫秒。代次缺失也算不符。 */
    static final String FILL = """
            local generation = redis.call('GET', KEYS[1])
            if (not generation) or generation ~= ARGV[1] then return 0 end
            if tonumber(ARGV[3]) > 0 then
              redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            else
              redis.call('SET', KEYS[2], ARGV[2])
            end
            return 1
            """;

    /** KEYS[1] = 代次键，KEYS[2] = 数据键；ARGV[1] = 调用方唯一值，ARGV[2] = 代次键 TTL 毫秒。代次 = 唯一值 + 服务器时间。 */
    static final String INVALIDATE = """
            local now = redis.call('TIME')
            redis.call('SET', KEYS[1], ARGV[1] .. ':' .. now[1] .. '.' .. now[2], 'PX', ARGV[2])
            redis.call('DEL', KEYS[2])
            return 1
            """;

    private final RedissonClient redis;

    public RedissonFriendCacheRedis(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public CompletionStage<String> get(String key) {
        return redis.<String>getBucket(key, StringCodec.INSTANCE).getAsync();
    }

    @Override
    public CompletionStage<String> generation(String generationKey, String candidate, long ttlMillis) {
        return script().evalAsync(RScript.Mode.READ_WRITE, GENERATION, RScript.ReturnType.VALUE,
                List.of(generationKey), candidate, Long.toString(ttlMillis));
    }

    @Override
    public CompletionStage<Boolean> fill(String generationKey, String dataKey, String expectedGeneration, String payload,
                                         long ttlMillis) {
        return script().<Long>evalAsync(RScript.Mode.READ_WRITE, FILL, RScript.ReturnType.INTEGER,
                        List.of(generationKey, dataKey), expectedGeneration, payload, Long.toString(ttlMillis))
                .thenApply(r -> r != null && r == 1L);
    }

    @Override
    public CompletionStage<Void> invalidate(String generationKey, String dataKey, String newGeneration,
                                            long generationTtlMillis) {
        return script().<Long>evalAsync(RScript.Mode.READ_WRITE, INVALIDATE, RScript.ReturnType.INTEGER,
                        List.of(generationKey, dataKey), newGeneration, Long.toString(generationTtlMillis))
                .thenApply(r -> null);
    }

    private RScript script() {
        return redis.getScript(StringCodec.INSTANCE);
    }
}
