package com.game.match.spectate;

import com.game.api.match.MatchBudgets;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.proto.eSpectateTier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SpectateTierStore} 的 Redis 实现：键经 {@link RedisKeys} 生成、都在 {@code {match}} 槽。异步发出、在调用线程上等到截止（不持锁，可以在虚拟线程上调）。
 * 档位用一段 Lua 比较后设置；TTL = 落点记录的 TTL（升档时刷新一次无害：它只比战斗活得长）。赛事局索引是一个 ZSET，分数 = 到期毫秒，时间取 Redis 的。
 */
public final class RedissonSpectateTierStore implements SpectateTierStore {

    /** KEYS[1] = 档位键；ARGV = 目标档位、TTL 毫秒。回写之后的档位。 */
    static final String RAISE = """
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local target = tonumber(ARGV[1])
            if target > current then
              redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
              return target
            end
            return current
            """;

    /** KEYS[1] = 赛事局索引；ARGV = battle_id、TTL 毫秒。分数是到期毫秒（Redis 时间）；顺手清掉到期的成员，索引不会越攒越长。 */
    static final String MARK_FEATURED = """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now)
            redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[1])
            return 1
            """;

    /** KEYS[1] = 赛事局索引；ARGV = battle_id。 */
    static final String UNMARK_FEATURED = "return redis.call('ZREM', KEYS[1], ARGV[1])";

    /** KEYS[1] = 赛事局索引；ARGV = 条数上限。没到期的成员，到期晚（后标记）的在前。 */
    static final String ACTIVE_FEATURED = """
            local time = redis.call('TIME')
            local now = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
            return redis.call('ZREVRANGEBYSCORE', KEYS[1], '+inf', string.format('(%.0f', now), 'LIMIT', 0, tonumber(ARGV[1]))
            """;

    private static final Logger log = LoggerFactory.getLogger(RedissonSpectateTierStore.class);
    private static final Duration TTL = Duration.ofSeconds(MatchBudgets.PLACEMENT_TTL_SECONDS);

    private final RedissonClient redis;
    private final RedisKeys keys;

    public RedissonSpectateTierStore(RedissonClient redis, RedisKeys keys) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.keys = Objects.requireNonNull(keys, "keys");
    }

    @Override
    public eSpectateTier tier(long battleId, Deadline d) {
        String value = await("读观战档位", d, () -> redis.<String>getBucket(keys.matchSpectateTier(battleId), StringCodec.INSTANCE).getAsync());
        return value == null ? eSpectateTier.SPECTATE_TIER_DIRECT : tierOf(Long.parseLong(value));
    }

    @Override
    public eSpectateTier raise(long battleId, eSpectateTier target, Deadline d) {
        Long value = await("升观战档位", d, () -> redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, RAISE, RScript.ReturnType.INTEGER,
                List.of(keys.matchSpectateTier(battleId)), Integer.toString(target.getNumber()), Long.toString(TTL.toMillis())));
        if (value == null) {
            throw new Deadline.DependencyException("升观战档位得到空回复");
        }
        return tierOf(value);
    }

    @Override
    public void setFeatured(long battleId, boolean featured, Deadline d) {
        List<Object> key = List.of(keys.matchSpectateFeatured());
        String member = SpectateRules.member(battleId);
        if (featured) {
            await("写赛事局标记", d, () -> redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, MARK_FEATURED, RScript.ReturnType.INTEGER,
                    key, member, Long.toString(TTL.toMillis())));
        } else {
            await("删赛事局标记", d, () -> redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, UNMARK_FEATURED, RScript.ReturnType.INTEGER,
                    key, member));
        }
    }

    @Override
    public List<Long> activeFeatured(int limit, Deadline d) {
        List<Object> reply = await("读赛事局索引", d, () -> redis.getScript(StringCodec.INSTANCE).<List<Object>>evalAsync(RScript.Mode.READ_WRITE, ACTIVE_FEATURED,
                RScript.ReturnType.MULTI, List.of(keys.matchSpectateFeatured()), Integer.toString(limit)));
        if (reply == null) {
            throw new Deadline.DependencyException("读赛事局索引得到空回复");
        }
        List<Long> featured = new ArrayList<>(reply.size());
        for (Object member : reply) {
            OptionalLong battleId = SpectateRules.parseMember(String.valueOf(member));
            if (battleId.isPresent()) {
                featured.add(battleId.getAsLong());
            } else {
                // 索引只有运营入口写：非法成员只记日志，随到期清理
                log.warn("[spectate] 赛事局索引里有非法成员 '{}'，跳过", member);
            }
        }
        return featured;
    }

    private static eSpectateTier tierOf(long value) {
        eSpectateTier tier = value >= 0 && value <= Integer.MAX_VALUE ? eSpectateTier.forNumber((int) value) : null;
        if (tier == null) {
            throw new Deadline.DependencyException("观战档位的值不认识: " + value);
        }
        return tier;
    }

    /** 截止已过不发；发不出去、异常完成、等超时一律 {@link Deadline.DependencyException}。 */
    private static <T> T await(String what, Deadline d, Supplier<? extends CompletionStage<T>> call) {
        if (d.expired()) {
            throw new Deadline.DependencyException(what + " 之前预算已用完（没有发出）");
        }
        CompletionStage<T> stage;
        try {
            stage = call.get();
        } catch (RuntimeException e) {
            throw new Deadline.DependencyException(what + " 发不出去", e);
        }
        return d.await(stage, what);
    }
}
