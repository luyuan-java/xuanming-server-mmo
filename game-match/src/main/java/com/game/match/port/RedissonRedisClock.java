package com.game.match.port;

import com.game.common.deadline.Deadline;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * {@link RedisClock} 的生产实现：一段 Lua 取 {@code TIME} 并换算成毫秒（秒 × 1000 + 微秒 / 1000，结果远小于 2^53，Lua 的 double 精确）。
 * 用异步 API 再在截止内等（不在 {@code synchronized} 里阻塞，可以在虚拟线程上调）。按读写模式发出：主从部署下固定读主库的时钟，
 * 与票据脚本（都在主库上执行）同源。
 */
public final class RedissonRedisClock implements RedisClock {

    static final String TIME_LUA = "local t = redis.call('TIME') return t[1] * 1000 + math.floor(t[2] / 1000)";

    private final RedissonClient redis;

    public RedissonRedisClock(RedissonClient redis) {
        this.redis = Objects.requireNonNull(redis, "redis");
    }

    @Override
    public long nowMs(Deadline d) {
        CompletableFuture<Long> read;
        try {
            read = redis.getScript(StringCodec.INSTANCE)
                    .<Long>evalAsync(RScript.Mode.READ_WRITE, TIME_LUA, RScript.ReturnType.INTEGER, List.of())
                    .toCompletableFuture();
        } catch (RuntimeException e) {
            throw new Deadline.DependencyException("读 Redis 时间失败", e);
        }
        Long now = d.await(read, "读 Redis 时间");
        if (now == null || now <= 0) {
            throw new Deadline.DependencyException("Redis 时间读数非法: " + now);
        }
        return now;
    }
}
