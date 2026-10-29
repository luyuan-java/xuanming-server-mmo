package com.game.discovery.world;

import com.game.discovery.RedisKeys;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/** 目录字节原样保存；发布键与领导锁、计划版本共用 zone hash tag。 */
public final class RedissonWorldChannelDirectoryStore implements WorldChannelDirectoryStore {
    static final String PUBLISH = """
            if redis.call('EXISTS',KEYS[4])==1 then return 0 end
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            if (redis.call('GET', KEYS[2]) or '0') ~= ARGV[2] then return 0 end
            redis.call('SET', KEYS[3], ARGV[3], 'PX', ARGV[4])
            return 1
            """;
    private final RedissonClient redis;
    private final RedisKeys keys;

    public RedissonWorldChannelDirectoryStore(RedissonClient redis, RedisKeys keys) {
        this.redis = Objects.requireNonNull(redis);
        this.keys = Objects.requireNonNull(keys);
    }

    @Override
    public byte[] read(int zoneId, int configId) {
        requireIds(zoneId, configId);
        // READ_WRITE 强制访问主节点，避免刚发布/过期时读副本旧值。
        return redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "if redis.call('EXISTS',KEYS[2])==1 then return nil end; return redis.call('GET', KEYS[1])", RScript.ReturnType.VALUE,
                List.of(keys.worldChannelDirectory(zoneId, configId),keys.worldRetirement(zoneId)));
    }

    @Override
    public boolean publish(int zoneId, String leaderToken, long planVersion, int configId, byte[] payload, Duration ttl) {
        requireIds(zoneId, configId);
        if (leaderToken == null || leaderToken.isBlank() || planVersion < 0 || payload == null || ttl == null || ttl.toMillis() <= 0) {
            throw new IllegalArgumentException("目录发布需要令牌、非负版本、内容和正 TTL");
        }
        Long result = redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_WRITE, PUBLISH,
                RScript.ReturnType.INTEGER,
                List.of(keys.worldLeader(zoneId), keys.worldPlanVersion(zoneId), keys.worldChannelDirectory(zoneId, configId),keys.worldRetirement(zoneId)),
                bytes(leaderToken), bytes(Long.toString(planVersion)), payload, bytes(Long.toString(ttl.toMillis())));
        if (result == null || result != 0L && result != 1L) throw new IllegalStateException("目录发布没有确认");
        return result == 1L;
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static void requireIds(int zoneId, int configId) {
        if (zoneId == 0 || configId == 0) throw new IllegalArgumentException("目录 zone/config 不能为零");
    }
}
