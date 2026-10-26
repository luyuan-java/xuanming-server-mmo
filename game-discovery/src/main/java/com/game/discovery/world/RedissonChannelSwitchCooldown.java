package com.game.discovery.world;

import com.game.discovery.RedisKeys;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/** 冷却不带节点/zone，跨节点与重连仍有效；缺键为未冷却，Redis 故障向上抛。 */
public final class RedissonChannelSwitchCooldown implements ChannelSwitchCooldown {
    private final RedissonClient redis;
    private final RedisKeys keys;

    public RedissonChannelSwitchCooldown(RedissonClient redis, RedisKeys keys) {
        this.redis = Objects.requireNonNull(redis);
        this.keys = Objects.requireNonNull(keys);
    }

    @Override
    public boolean active(long playerId) {
        requirePlayer(playerId);
        Long result = redis.getScript(StringCodec.INSTANCE).eval(RScript.Mode.READ_WRITE,
                "return redis.call('EXISTS', KEYS[1])", RScript.ReturnType.INTEGER,
                List.of(keys.channelSwitchCooldown(playerId)));
        if (result == null) throw new IllegalStateException("切线冷却读取未确认");
        return result != 0;
    }

    @Override
    public void start(long playerId, Duration ttl) {
        requirePlayer(playerId);
        if (ttl == null || ttl.toMillis() <= 0 || ttl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("切线冷却必须大于零且不超过一天");
        }
        redis.getBucket(keys.channelSwitchCooldown(playerId), StringCodec.INSTANCE).set("1", ttl);
    }

    private static void requirePlayer(long playerId) {
        if (playerId == 0) throw new IllegalArgumentException("切线冷却玩家不能为零");
    }
}
