package com.game.guild.rank;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;

/**
 * {@link GuildRankRedis} 的 Redisson 实现（guild-spec §5.9，D16）。
 *
 * <ul>
 *   <li>每个动作一段 Lua、单条 {@code evalAsync}（遇 NOSCRIPT 自动重载），不用 RBatch / MULTI；键全部经 KEYS 传入（Cluster 下同槽，
 *       键共用 hash tag {@code {rank}}）。</li>
 *   <li>编解码一律 ByteArrayCodec，参数是 ASCII 字节；分数以 Redis 回的原串交给调用方解析（不在 Lua 里转 number：
 *       Lua 数字回到客户端会被截成整数）。</li>
 *   <li>Redisson 在响应超时后会重发同一段 EVAL：抢锁对「已是本令牌」回 true，续期 / 释放比较令牌，入榜 / 清榜 / 临时键 ZADD
 *       都是幂等写；换榜重发时临时键已被 RENAME 掉，ZCARD 核对不符回 -1（第一次其实已经成功——调用方据此按失败处理会拒启，
 *       再启动一次即可；这只在 Redis 响应超时的极端情况下发生）。</li>
 * </ul>
 */
public final class RedissonGuildRankRedis implements GuildRankRedis {

    /** KEYS[1] = 锁；ARGV[1] = 令牌，ARGV[2] = TTL 毫秒。 */
    static final String TRY_LOCK = """
            local holder = redis.call('GET', KEYS[1])
            if holder == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            if holder then return 0 end
            redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
            return 1
            """;

    /** KEYS[1] = 锁；ARGV[1] = 令牌，ARGV[2] = TTL 毫秒。 */
    static final String RENEW_LOCK = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              return 1
            end
            return 0
            """;

    /** KEYS[1] = 锁；ARGV[1] = 令牌（基线 releaseRankLockScript，guild_repo.go:176-182）。 */
    static final String UNLOCK = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    /**
     * KEYS[1] = 锁，KEYS[2] = 全服榜，KEYS[3] = 区索引，KEYS[4] = 区榜（可缺）；
     * ARGV[1] = 令牌，ARGV[2] = 分数，ARGV[3] = 成员，ARGV[4] = 区索引成员（可缺）。
     */
    static final String ADD = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
            if KEYS[4] then
              redis.call('ZADD', KEYS[4], ARGV[2], ARGV[3])
              redis.call('SADD', KEYS[3], ARGV[4])
            end
            return 1
            """;

    /** KEYS[1] = 锁，KEYS[2..] = 要清的榜；ARGV[1] = 令牌，ARGV[2] = 成员。 */
    static final String REMOVE = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            for i = 2, #KEYS do
              redis.call('ZREM', KEYS[i], ARGV[2])
            end
            return 1
            """;

    /** KEYS[1] = 临时键；ARGV[1] = TTL 毫秒，ARGV[2..] = 分数, 成员, 分数, 成员…。 */
    static final String ADD_TEMP = """
            local added = redis.call('ZADD', KEYS[1], unpack(ARGV, 2))
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
            return added
            """;

    /**
     * KEYS = 锁, 全服榜, 区索引, 全服临时键, 旧区榜 × nOld, (区临时键, 区正式键) × nNew；
     * ARGV = 令牌, nOld, nNew, 全服预期数, 区索引成员 × nNew, 区预期数 × nNew。
     */
    static final String SWAP = """
            if redis.call('GET', KEYS[1]) ~= ARGV[1] then return 0 end
            local nOld = tonumber(ARGV[2])
            local nNew = tonumber(ARGV[3])
            local base = 4 + nOld
            if redis.call('ZCARD', KEYS[4]) ~= tonumber(ARGV[4]) then return -1 end
            for i = 1, nNew do
              if redis.call('ZCARD', KEYS[base + 2 * i - 1]) ~= tonumber(ARGV[4 + nNew + i]) then return -1 end
            end
            redis.call('DEL', KEYS[2])
            for i = 1, nOld do
              redis.call('DEL', KEYS[4 + i])
            end
            if redis.call('EXISTS', KEYS[4]) == 1 then
              redis.call('RENAME', KEYS[4], KEYS[2])
              redis.call('PERSIST', KEYS[2])
            end
            for i = 1, nNew do
              local tmp = KEYS[base + 2 * i - 1]
              local formal = KEYS[base + 2 * i]
              redis.call('DEL', formal)
              if redis.call('EXISTS', tmp) == 1 then
                redis.call('RENAME', tmp, formal)
                redis.call('PERSIST', formal)
              end
            end
            redis.call('DEL', KEYS[3])
            for i = 1, nNew do
              redis.call('SADD', KEYS[3], ARGV[4 + i])
            end
            return 1
            """;

    /** KEYS[1] = 榜；ARGV[1] = 起始下标（可远大于榜长），ARGV[2] = 页长（0 = 只要总数）。返回 {总数, 成员, 分数, 成员, 分数…}。 */
    static final String PAGE = """
            local total = redis.call('ZCARD', KEYS[1])
            local size = tonumber(ARGV[2])
            if size == 0 then return {total} end
            local start = tonumber(ARGV[1])
            if start >= total then return {total} end
            local stop = start + size - 1
            if stop > total - 1 then stop = total - 1 end
            local rows = redis.call('ZREVRANGE', KEYS[1], start, stop, 'WITHSCORES')
            table.insert(rows, 1, total)
            return rows
            """;

    /** KEYS[1] = 榜；ARGV[1] = 成员。不在榜上回空表，否则 {ZREVRANK, ZSCORE 原串}（同一段脚本，中间不会被删，修 §9.1 第 8 条）。 */
    static final String RANK = """
            local rank = redis.call('ZREVRANK', KEYS[1], ARGV[1])
            if not rank then return {} end
            return {rank, redis.call('ZSCORE', KEYS[1], ARGV[1])}
            """;

    private final RedissonClient redis;

    public RedissonGuildRankRedis(RedissonClient redis) {
        this.redis = redis;
    }

    @Override
    public CompletionStage<Boolean> tryLock(String lockKey, String token, long ttlMillis) {
        return flag(eval(RScript.Mode.READ_WRITE, TRY_LOCK, RScript.ReturnType.INTEGER, List.of(lockKey),
                ascii(token), ascii(ttlMillis)));
    }

    @Override
    public CompletionStage<Boolean> renewLock(String lockKey, String token, long ttlMillis) {
        return flag(eval(RScript.Mode.READ_WRITE, RENEW_LOCK, RScript.ReturnType.INTEGER, List.of(lockKey),
                ascii(token), ascii(ttlMillis)));
    }

    @Override
    public CompletionStage<Boolean> unlock(String lockKey, String token) {
        return flag(eval(RScript.Mode.READ_WRITE, UNLOCK, RScript.ReturnType.INTEGER, List.of(lockKey), ascii(token)));
    }

    @Override
    public CompletionStage<Boolean> add(String lockKey, String token, String allKey, String zonesKey, String zoneKey,
                                        String zoneMember, String score, String member) {
        List<String> keys = new ArrayList<>(List.of(lockKey, allKey, zonesKey));
        List<byte[]> args = new ArrayList<>(List.of(ascii(token), ascii(score), ascii(member)));
        if (zoneKey != null) {
            keys.add(zoneKey);
            args.add(ascii(zoneMember));
        }
        return flag(eval(RScript.Mode.READ_WRITE, ADD, RScript.ReturnType.INTEGER, keys, args.toArray()));
    }

    @Override
    public CompletionStage<Set<String>> members(String setKey) {
        try {
            return redis.<byte[]>getSet(setKey, ByteArrayCodec.INSTANCE).readAllAsync().thenApply(raw -> {
                Set<String> members = new LinkedHashSet<>();
                for (byte[] member : raw) {
                    members.add(new String(member, StandardCharsets.US_ASCII));
                }
                return members;
            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<Boolean> remove(String lockKey, String token, List<String> rankKeys, String member) {
        List<String> keys = new ArrayList<>(rankKeys.size() + 1);
        keys.add(lockKey);
        keys.addAll(rankKeys);
        return flag(eval(RScript.Mode.READ_WRITE, REMOVE, RScript.ReturnType.INTEGER, keys, ascii(token), ascii(member)));
    }

    @Override
    public CompletionStage<Long> addTemp(String tmpKey, List<String> scoreMemberPairs, long ttlMillis) {
        Object[] args = new Object[scoreMemberPairs.size() + 1];
        args[0] = ascii(ttlMillis);
        for (int i = 0; i < scoreMemberPairs.size(); i++) {
            args[i + 1] = ascii(scoreMemberPairs.get(i));
        }
        return eval(RScript.Mode.READ_WRITE, ADD_TEMP, RScript.ReturnType.INTEGER, List.of(tmpKey), args);
    }

    @Override
    public CompletionStage<Long> swap(SwapPlan plan) {
        List<String> keys = new ArrayList<>();
        keys.add(plan.lockKey());
        keys.add(plan.allKey());
        keys.add(plan.zonesKey());
        keys.add(plan.tmpAllKey());
        keys.addAll(plan.oldZoneKeys());
        List<byte[]> args = new ArrayList<>();
        args.add(ascii(plan.token()));
        args.add(ascii(plan.oldZoneKeys().size()));
        args.add(ascii(plan.zones().size()));
        args.add(ascii(plan.expectedAll()));
        for (ZoneSwap zone : plan.zones()) {
            keys.add(zone.tmpKey());
            keys.add(zone.formalKey());
            args.add(ascii(zone.zoneMember()));
        }
        for (ZoneSwap zone : plan.zones()) {
            args.add(ascii(zone.expected()));
        }
        return eval(RScript.Mode.READ_WRITE, SWAP, RScript.ReturnType.INTEGER, keys, args.toArray());
    }

    @Override
    public CompletionStage<Long> delete(List<String> keys) {
        if (keys.isEmpty()) {
            return CompletableFuture.completedFuture(0L);
        }
        try {
            return redis.getKeys().deleteAsync(keys.toArray(String[]::new));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    @Override
    public CompletionStage<RawPage> page(String key, String start, long size) {
        return this.<List<Object>>eval(RScript.Mode.READ_ONLY, PAGE, RScript.ReturnType.MULTI, List.of(key),
                ascii(start), ascii(size)).thenApply(reply -> {
            if (reply == null || reply.isEmpty()) {
                throw new IllegalStateException("排行分页脚本回包为空 key=" + key);
            }
            long total = integer(reply.get(0), key);
            List<String> members = new ArrayList<>();
            List<String> scores = new ArrayList<>();
            for (int i = 1; i + 1 < reply.size(); i += 2) {
                members.add(text(reply.get(i), key));
                scores.add(text(reply.get(i + 1), key));
            }
            if (reply.size() % 2 == 0) {
                throw new IllegalStateException("排行分页脚本回包长度不对 key=" + key + " size=" + reply.size());
            }
            return new RawPage(total, members, scores);
        });
    }

    @Override
    public CompletionStage<RawRank> rank(String key, String member) {
        return this.<List<Object>>eval(RScript.Mode.READ_ONLY, RANK, RScript.ReturnType.MULTI, List.of(key),
                ascii(member)).thenApply(reply -> {
            if (reply == null || reply.isEmpty()) {
                return null;
            }
            if (reply.size() != 2) {
                throw new IllegalStateException("排行名次脚本回包长度不对 key=" + key + " size=" + reply.size());
            }
            return new RawRank(integer(reply.get(0), key), text(reply.get(1), key));
        });
    }

    private <R> CompletionStage<R> eval(RScript.Mode mode, String lua, RScript.ReturnType type, List<String> keys,
                                        Object... args) {
        try {
            return redis.getScript(ByteArrayCodec.INSTANCE).<R>evalAsync(mode, lua, type, new ArrayList<Object>(keys), args);
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static CompletionStage<Boolean> flag(CompletionStage<Long> reply) {
        return reply.thenApply(r -> r != null && r == 1L);
    }

    private static long integer(Object value, String key) {
        if (value instanceof Long number) {
            return number;
        }
        throw new IllegalStateException("排行脚本回包的整数项类型不对 key=" + key + ": " + typeOf(value));
    }

    private static String text(Object value, String key) {
        if (value instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.US_ASCII);
        }
        throw new IllegalStateException("排行脚本回包的字符串项类型不对 key=" + key + ": " + typeOf(value));
    }

    private static String typeOf(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName();
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] ascii(long value) {
        return Long.toString(value).getBytes(StandardCharsets.US_ASCII);
    }
}
