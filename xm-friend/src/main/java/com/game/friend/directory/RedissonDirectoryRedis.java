package com.game.friend.directory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;

/**
 * {@link OnlineDirectory.DirectoryRedis} 的 Redisson 实现。Redisson 的公开 API 不暴露 SCAN 游标，所以在一段只读 Lua 里执行
 * {@code SCAN <cursor> MATCH <pattern> COUNT 64}（单条命令，脚本只是把游标原样带出来）；条目用 MGET（ByteArrayCodec）。
 */
public final class RedissonDirectoryRedis implements OnlineDirectory.DirectoryRedis {

    static final String SCAN = "return redis.call('SCAN', ARGV[1], 'MATCH', ARGV[2], 'COUNT', ARGV[3])";

    private final RedissonClient redis;
    private final String pattern;

    /** @param keyPrefix 在线目录键前缀（{@code xm:presence:}），匹配 {@code <前缀>*} */
    public RedissonDirectoryRedis(RedissonClient redis, String keyPrefix) {
        this.redis = redis;
        this.pattern = keyPrefix + "*";
    }

    @Override
    public CompletionStage<OnlineDirectory.ScanPage> scan(long cursor) {
        return redis.getScript(StringCodec.INSTANCE).<List<Object>>evalAsync(RScript.Mode.READ_ONLY, SCAN,
                        RScript.ReturnType.MULTI, List.of(), Long.toUnsignedString(cursor), pattern,
                        Integer.toString(OnlineDirectory.SCAN_COUNT))
                .thenApply(RedissonDirectoryRedis::toPage);
    }

    static OnlineDirectory.ScanPage toPage(List<Object> reply) {
        if (reply == null || reply.size() != 2) {
            throw new IllegalStateException("SCAN 返回形状不对: " + reply);
        }
        long next = Long.parseUnsignedLong(String.valueOf(reply.get(0)));
        List<String> keys = new ArrayList<>();
        if (reply.get(1) instanceof List<?> list) {
            for (Object key : list) {
                keys.add(String.valueOf(key));
            }
        } else {
            throw new IllegalStateException("SCAN 返回的键列表形状不对: " + reply.get(1));
        }
        return new OnlineDirectory.ScanPage(next, keys);
    }

    @Override
    public CompletionStage<Map<String, byte[]>> mget(List<String> keys) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return redis.getBuckets(ByteArrayCodec.INSTANCE).<byte[]>getAsync(keys.toArray(String[]::new))
                .thenApply(HashMap::new);
    }
}
