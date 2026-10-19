package com.game.data.tools.playerbundle;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.redisson.Redisson;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNode;
import org.redisson.api.redisnode.RedisNodes;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.config.Config;

/**
 * {@link PlayerRedis} 的生产实现（Redisson 单机连接，与 game-data 的 {@code game.redis} 同口径）。
 *
 * <p>读：每把键一条只读 Lua（{@link RScript.Mode#READ_ONLY}），脚本里只有 TYPE / PTTL / GET / HGETALL / ZRANGE，不 SCAN、不写；
 * 值全走 {@link ByteArrayCodec}（原样字节，protobuf 值不经字符串转码）。一把键一条脚本而不是一条脚本读全部：这些键的 hash tag 各不相同，
 * 集群部署下跨槽的多键脚本会被拒绝。
 */
final class RedissonPlayerRedis implements PlayerRedis {

    /** zset 最多读多少个成员（排障用的玩家级 zset 很小；超出的不导出）。 */
    static final int ZSET_LIMIT = 200;

    private static final String READ_ONE = """
            local t = redis.call('TYPE', KEYS[1])['ok']
            local ttl = redis.call('PTTL', KEYS[1])
            if t == 'string' then return {t, ttl, redis.call('GET', KEYS[1])} end
            if t == 'hash' then return {t, ttl, redis.call('HGETALL', KEYS[1])} end
            if t == 'zset' then return {t, ttl, redis.call('ZRANGE', KEYS[1], 0, tonumber(ARGV[1]), 'WITHSCORES')} end
            return {t, ttl}
            """;

    private final RedissonClient redis;

    private RedissonPlayerRedis(RedissonClient redis) {
        this.redis = redis;
    }

    /**
     * @param address  {@code redis://host:port}
     * @param password 可为 null / 空
     */
    static RedissonPlayerRedis connect(String address, int database, String password) {
        Config config = new Config();
        config.useSingleServer().setAddress(address).setDatabase(database)
                .setPassword(password == null || password.isEmpty() ? null : password)
                .setConnectionMinimumIdleSize(1).setConnectionPoolSize(2).setTimeout(5000).setRetryAttempts(1);
        return new RedissonPlayerRedis(Redisson.create(config));
    }

    @Override
    public List<RawEntry> read(List<String> keys) {
        List<RawEntry> out = new ArrayList<>(keys.size());
        byte[] lastIndex = Integer.toString(ZSET_LIMIT - 1).getBytes(StandardCharsets.US_ASCII);
        for (String key : keys) {
            try {
                List<Object> reply = redis.getScript(ByteArrayCodec.INSTANCE).eval(RScript.Mode.READ_ONLY, READ_ONE,
                        RScript.ReturnType.MULTI, List.of(key), (Object) lastIndex);
                out.add(decode(key, reply));
            } catch (RuntimeException e) {
                out.add(RawEntry.failed(key, e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
        }
        return out;
    }

    /** 把脚本回复 {@code {type, pttl, payload?}} 还原成 {@link RawEntry}。 */
    static RawEntry decode(String key, List<Object> reply) {
        String type = text(reply.get(0));
        long pttl = ((Number) reply.get(1)).longValue();
        Object payload = reply.size() > 2 ? reply.get(2) : null;
        return switch (type) {
            case "string" -> new RawEntry(key, type, pttl, (byte[]) payload, null, null, null);
            case "hash" -> {
                Map<String, byte[]> hash = new LinkedHashMap<>();
                List<?> flat = (List<?>) payload;
                for (int i = 0; i + 1 < flat.size(); i += 2) {
                    hash.put(text(flat.get(i)), (byte[]) flat.get(i + 1));
                }
                yield new RawEntry(key, type, pttl, null, hash, null, null);
            }
            case "zset" -> {
                List<ZMember> members = new ArrayList<>();
                List<?> flat = (List<?>) payload;
                for (int i = 0; i + 1 < flat.size(); i += 2) {
                    members.add(new ZMember(text(flat.get(i)), Double.parseDouble(text(flat.get(i + 1)))));
                }
                yield new RawEntry(key, type, pttl, null, null, members, null);
            }
            default -> new RawEntry(key, type, pttl, null, null, null, null);
        };
    }

    private static String text(Object value) {
        return value instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
    }

    @Override
    public Map<String, String> infoServer() {
        return redis.getRedisNodes(RedisNodes.SINGLE).getInstance().info(RedisNode.InfoSection.SERVER);
    }

    @Override
    public long delete(List<String> keys) {
        return keys.isEmpty() ? 0 : redis.getKeys().delete(keys.toArray(String[]::new));
    }

    @Override
    public void close() {
        redis.shutdown();
    }
}
