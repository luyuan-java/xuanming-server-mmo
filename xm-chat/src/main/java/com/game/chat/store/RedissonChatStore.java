package com.game.chat.store;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;

/**
 * {@link ChatStore} 的 Redisson 实现。脚本与基线逐字同义；单条 {@code evalAsync}（遇 NOSCRIPT 自动重载），不用 RBatch。
 *
 * <p>Redisson 在响应超时后会重发同一段 EVAL：占位 / 完结 / 释放都是「值相等才改」或幂等的；限速多计一次偏严一次；
 * 追加历史重发会多一条重复消息——与基线「写入 Lua 超时但实际已落库」同一类偶发重复，聊天可接受（重复比丢失可接受）。
 */
public final class RedissonChatStore implements ChatStore {

    static final String MARK_DONE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
              return 1
            end
            return 0
            """;

    static final String RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    static final String INCR_WITH_TTL = """
            local n = redis.call('INCR', KEYS[1])
            if n == 1 or redis.call('TTL', KEYS[1]) == -1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """;

    static final String APPEND = """
            redis.call('LPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]) - 1)
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """;

    private final RedissonClient redis;

    public RedissonChatStore(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public CompletionStage<Boolean> claim(String key, String value, long ttlSeconds) {
        return redis.<String>getBucket(key, StringCodec.INSTANCE).setIfAbsentAsync(value, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public CompletionStage<String> get(String key) {
        return redis.<String>getBucket(key, StringCodec.INSTANCE).getAsync();
    }

    @Override
    public CompletionStage<Boolean> markDone(String key, String expected, String done, long ttlSeconds) {
        return redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, MARK_DONE,
                RScript.ReturnType.INTEGER, List.of(key), expected, done, Long.toString(ttlSeconds)).thenApply(r -> r != null && r == 1L);
    }

    @Override
    public CompletionStage<Boolean> release(String key, String expected) {
        return redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, RELEASE,
                RScript.ReturnType.INTEGER, List.of(key), expected).thenApply(r -> r != null && r > 0);
    }

    @Override
    public CompletionStage<Long> incrWithTtl(String key, long ttlSeconds) {
        return redis.getScript(StringCodec.INSTANCE).evalAsync(RScript.Mode.READ_WRITE, INCR_WITH_TTL,
                RScript.ReturnType.INTEGER, List.of(key), Long.toString(ttlSeconds));
    }

    @Override
    public CompletionStage<Void> append(String key, byte[] message, int maxEntries, long ttlSeconds) {
        return redis.getScript(ByteArrayCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, APPEND,
                        RScript.ReturnType.INTEGER, List.of(key), message, ascii(Integer.toString(maxEntries)),
                        ascii(Long.toString(ttlSeconds)))
                .thenApply(r -> null);
    }

    @Override
    public CompletionStage<List<byte[]>> range(String key, int limit) {
        return redis.<byte[]>getList(key, ByteArrayCodec.INSTANCE).rangeAsync(0, limit - 1);
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
