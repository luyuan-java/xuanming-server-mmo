package com.game.guild.cache;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * {@link GuildCache.CacheRedis} 的 Redisson 实现（脚本语义同 xm-friend {@code RedissonFriendCacheRedis}，参数改成字节）。
 *
 * <ul>
 *   <li>编解码一律 ByteArrayCodec：快照是任意字节（含 0x00、非法 UTF-8），StringCodec 会把它弄坏；数字参数写 ASCII 十进制
 *       （同 {@code PlayerPresenceDirectory} 的脚本先例）。</li>
 *   <li>三段 Lua 各是一次原子调用，用单条 {@code evalAsync}（遇 NOSCRIPT 自动重载），不用 RBatch（批里的脚本遇到 NOSCRIPT 不会重新加载）。</li>
 *   <li>Redisson 在响应超时后会重发同一段 EVAL：取代次是「有就读、没有才写」，重发无害；回填写的是同一份值；失效把服务器 {@code TIME}
 *       拼进代次，重发时代次已不同——不会把后来的写者设的代次改回读者手里的旧值（ABA）。</li>
 *   <li>同一实体的数据键与代次键共用 hash tag（{@code RedisKeys.guildSnapshot / guildOfPlayer} + {@code cacheGeneration}），Cluster 下同槽。</li>
 * </ul>
 */
public final class RedissonGuildCacheRedis implements GuildCache.CacheRedis {

    /** KEYS[1] = 代次键；ARGV[1] = 候选代次，ARGV[2] = TTL 毫秒。缺失时写入候选值，返回当前代次。 */
    static final String GENERATION = """
            local generation = redis.call('GET', KEYS[1])
            if not generation then
              generation = ARGV[1]
              redis.call('SET', KEYS[1], generation, 'PX', ARGV[2])
            end
            return generation
            """;

    /**
     * KEYS[1] = 代次键，KEYS[2] = 数据键；ARGV[1] = 读库前看到的代次，ARGV[2] = 值，ARGV[3] = 数据 TTL 毫秒。代次缺失也算不符。
     * 回填成功时把代次键续到 2 × 数据 TTL：读者建的候选代次只带一次回填够用的短 TTL（回源发现帮会不存在时几秒就消失，
     * 客户端拿随机 guild_id 刷 GetGuild 不会在 Redis 里攒下一小时的孤儿键），真正有数据时代次才活得比数据键久。
     */
    static final String FILL = """
            local generation = redis.call('GET', KEYS[1])
            if (not generation) or generation ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[2], 'PX', ARGV[3])
            redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[3]) * 2)
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

    public RedissonGuildCacheRedis(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public CompletionStage<byte[]> get(String key) {
        try {
            return redis.<byte[]>getBucket(key, ByteArrayCodec.INSTANCE).getAsync();
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<Map<String, byte[]>> getAll(List<String> keys) {
        try {
            return redis.getBuckets(ByteArrayCodec.INSTANCE).<byte[]>getAsync(keys.toArray(String[]::new));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<byte[]> generation(String generationKey, byte[] candidate, long ttlMillis) {
        return eval(GENERATION, RScript.ReturnType.VALUE, List.<Object>of(generationKey), candidate, ascii(ttlMillis));
    }

    @Override
    public CompletionStage<Boolean> fill(String generationKey, String dataKey, byte[] expectedGeneration, byte[] payload,
                                         long ttlMillis) {
        return this.<Long>eval(FILL, RScript.ReturnType.INTEGER, List.<Object>of(generationKey, dataKey), expectedGeneration,
                        payload, ascii(ttlMillis))
                .thenApply(r -> r != null && r == 1L);
    }

    @Override
    public CompletionStage<Void> invalidate(String generationKey, String dataKey, byte[] newGeneration,
                                            long generationTtlMillis) {
        return this.<Long>eval(INVALIDATE, RScript.ReturnType.INTEGER, List.<Object>of(generationKey, dataKey), newGeneration,
                        ascii(generationTtlMillis))
                .thenApply(r -> null);
    }

    private <R> CompletionStage<R> eval(String lua, RScript.ReturnType type, List<Object> keys, Object... args) {
        try {
            return redis.getScript(ByteArrayCodec.INSTANCE).<R>evalAsync(RScript.Mode.READ_WRITE, lua, type, keys, args);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static byte[] ascii(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }
}
