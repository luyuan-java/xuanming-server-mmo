package com.game.match.challenge;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.port.RedisClientWrites;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

/**
 * {@link ChallengeStore} 的 Redis 实现（match-spec §6.3）。键：记录 {@code game:{match}:challenge:<id>}（HASH）、占坑
 * {@code game:{match}:challenge-target:<pid>}（STRING，值 = challenge_id）、墓碑 {@code game:{match}:challenge-done:<id>}（HASH，60 s），
 * 全部经 {@link RedisKeys} 生成、同一个 hash tag。脚本见 {@link ChallengeScripts}。
 *
 * <p>一律用 Redisson 的异步 API 再在请求截止内等（不持锁阻塞）；单条脚本调用，按读写模式发出（主库）。读与清理是 {@code evalAsync}（遇到 NOSCRIPT
 * 由 Redisson 自动重新加载）；发起与消费经 {@link RedisClientWrites#evalAsync} 发 {@code EVALSHA}，遇 NOSCRIPT 先查截止再加载、重发。
 * 编解码用 StringCodec：字段都是 ASCII 十进制 / UUID。
 *
 * <p><b>发起与消费是客户端请求（152 / 151）的提交点</b>（mmorpg client-request-ordering.md §6.1）：截止已过就不发（什么都没写，抛
 * {@link Deadline.DependencyException}）；发出时不重发、等连接不越过截止（{@link RedisClientWrites}）。gate 在 match 回包或 5 s 超时之后就放行
 * 同一会话的下一条，迟到的发起会在目标身上留下一个邀请寿命（缺省 60 s）的占坑（其间对他发起都回「对方已有待处理的切磋邀请」），迟到的消费会把邀请作废而不开局。
 * 两段脚本照旧可重放（按 challenge_id / nonce 认出自己），只是不再由 Redisson 重放。<b>清理</b>（{@link #delete}）是补偿：不看截止、照常带重发，
 * 截止已过也发出去（只是不再等结果）；它按 challenge_id 条件摘占坑，迟到无害。读不改状态，照旧。
 */
public final class RedissonChallengeStore implements ChallengeStore {

    /** 消费墓碑的寿命：只需盖过 Redis 客户端的重发窗口（几秒），取与邀请缺省寿命相同的 60 s（match-spec §9.4 键表）。 */
    static final long TOMBSTONE_TTL_MS = 60_000;

    private final RedissonClient redis;
    private final RedisKeys redisKeys;
    /** 配置的单条命令超时（{@code game.redis.timeout-ms}）：发起 / 消费的 Redisson 超时取它与剩余预算的较小者（{@link RedisClientWrites}）。 */
    private final Duration commandTimeout;

    /** 命令超时取 {@code game.redis.timeout-ms} 的缺省值：只给直接 new 的测试用，生产装配走下一个构造器。 */
    public RedissonChallengeStore(RedissonClient redis, RedisKeys redisKeys) {
        this(redis, redisKeys, RedisClientWrites.DEFAULT_COMMAND_TIMEOUT);
    }

    /** @param commandTimeout 配置的单条命令超时（{@link RedisClientWrites#commandTimeout}），必须为正 */
    public RedissonChallengeStore(RedissonClient redis, RedisKeys redisKeys, Duration commandTimeout) {
        this.redis = Objects.requireNonNull(redis, "redis");
        this.redisKeys = Objects.requireNonNull(redisKeys, "redisKeys");
        this.commandTimeout = RedisClientWrites.requireCommandTimeout(commandTimeout);
    }

    @Override
    public InviteResult invite(long challengeId, long challengerId, long targetId, int configId, long ttlMs, Deadline d) {
        requireIds(challengeId, targetId);
        if (challengerId == 0) {
            throw new IllegalArgumentException("发起者不能为 0");
        }
        if (ttlMs < 1) {
            throw new IllegalArgumentException("邀请的 TTL 必须 ≥ 1 ms: " + ttlMs);
        }
        List<Object> reply = clientWrite("发起切磋", d, ChallengeScripts.INVITE,
                List.of(redisKeys.matchChallenge(challengeId), redisKeys.matchChallengeTarget(targetId)),
                Long.toUnsignedString(challengeId), Long.toUnsignedString(challengerId), Long.toUnsignedString(targetId),
                Integer.toUnsignedString(configId), Long.toString(ttlMs));
        String tag = tagOf(reply, "发起切磋");
        if (ChallengeScripts.PENDING.equals(tag)) {
            return new InviteResult.Pending();
        }
        if (ChallengeScripts.OK.equals(tag) && reply.size() >= 2) {
            long expiresAtMs = unsignedOrZero(text(reply.get(1)));
            if (expiresAtMs != 0) {
                return new InviteResult.Created(expiresAtMs);
            }
        }
        throw new Deadline.DependencyException("发起切磋的脚本返回了不认识的结果: " + reply);
    }

    @Override
    public void delete(long challengeId, long targetId, Deadline d) {
        requireIds(challengeId, targetId);
        CompletableFuture<Object> call = start("清理切磋记录", () -> redis.getScript(StringCodec.INSTANCE)
                .<Object>evalAsync(RScript.Mode.READ_WRITE, ChallengeScripts.DELETE, RScript.ReturnType.INTEGER,
                        List.<Object>of(redisKeys.matchChallenge(challengeId), redisKeys.matchChallengeTarget(targetId)),
                        Long.toUnsignedString(challengeId))
                .toCompletableFuture());
        d.await(call, "清理切磋记录");
    }

    @Override
    public Optional<ChallengeRecord> read(long challengeId, Deadline d) {
        if (challengeId == 0) {
            return Optional.empty();
        }
        CompletableFuture<Map<String, String>> call = start("读切磋记录", () -> redis
                .<String, String>getMap(redisKeys.matchChallenge(challengeId), StringCodec.INSTANCE).readAllMapAsync().toCompletableFuture());
        Map<String, String> fields = d.await(call, "读切磋记录");
        if (fields == null || fields.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(record(fields.get(ChallengeScripts.FIELD_CHALLENGER), fields.get(ChallengeScripts.FIELD_TARGET),
                fields.get(ChallengeScripts.FIELD_CONFIG), fields.get(ChallengeScripts.FIELD_EXPIRES_AT)));
    }

    @Override
    public ConsumeResult consume(long challengeId, long responderId, String nonce, Deadline d) {
        requireIds(challengeId, responderId);
        if (nonce == null || nonce.isEmpty()) {
            throw new IllegalArgumentException("消费切磋记录必须带请求 nonce");
        }
        List<Object> reply = clientWrite("消费切磋记录", d, ChallengeScripts.CONSUME,
                List.of(redisKeys.matchChallenge(challengeId), redisKeys.matchChallengeTarget(responderId),
                        redisKeys.matchChallengeDone(challengeId)),
                Long.toUnsignedString(challengeId), Long.toUnsignedString(responderId), nonce, Long.toString(TOMBSTONE_TTL_MS));
        String tag = tagOf(reply, "消费切磋记录");
        if (ChallengeScripts.GONE.equals(tag)) {
            return new ConsumeResult.Gone();
        }
        if (ChallengeScripts.NOT_TARGET.equals(tag)) {
            return new ConsumeResult.NotTarget();
        }
        if (ChallengeScripts.OK.equals(tag) && reply.size() >= 6) {
            long nowMs = unsignedOrZero(text(reply.get(5)));
            if (nowMs != 0) {
                return new ConsumeResult.Consumed(record(text(reply.get(1)), text(reply.get(2)), text(reply.get(3)), text(reply.get(4))), nowMs);
            }
        }
        throw new Deadline.DependencyException("消费切磋记录的脚本返回了不认识的结果: " + reply);
    }

    // ---------------------------------------------------------------- 内部

    /** 客户端请求的提交点（发起 / 消费）：截止已过不发；发出时不重发、Redisson 超时取配置值与剩余预算的较小者、自己处理 NOSCRIPT（见类注释）。 */
    private List<Object> clientWrite(String what, Deadline d, String lua, List<String> keys, String... args) {
        if (d.expired()) {
            throw new Deadline.DependencyException(what + " 之前请求预算已用完（没有发出）");
        }
        CompletableFuture<List<Object>> call = start(what, () -> RedisClientWrites.<List<Object>>evalAsync(redis, StringCodec.INSTANCE, d, commandTimeout,
                lua, RScript.ReturnType.MULTI, List.<Object>copyOf(keys), (Object[]) args));
        List<Object> reply = d.await(call, what);
        if (reply == null || reply.isEmpty()) {
            throw new Deadline.DependencyException(what + " 的脚本返回了空结果");
        }
        return reply;
    }

    /** 发起一次异步调用；同步抛出的异常（客户端已关闭等）也变成失败的 future，走同一条「失败」路径。 */
    private static <T> CompletableFuture<T> start(String what, Supplier<CompletableFuture<T>> call) {
        try {
            CompletableFuture<T> future = call.get();
            return future != null ? future : CompletableFuture.failedFuture(new IllegalStateException(what + " 没有返回 future"));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static String tagOf(List<Object> reply, String what) {
        String tag = text(reply.get(0));
        if (tag == null) {
            throw new Deadline.DependencyException(what + " 的脚本返回了不认识的结果: " + reply);
        }
        return tag;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static ChallengeRecord record(String challenger, String target, String config, String expiresAt) {
        return new ChallengeRecord(unsignedOrZero(challenger), unsignedOrZero(target), unsignedIntOrZero(config), unsignedOrZero(expiresAt));
    }

    /** 同基线 {@code strconv.ParseUint} 忽略错误的口径：不是合法无符号十进制的字段按 0。 */
    static long unsignedOrZero(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseUnsignedLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static int unsignedIntOrZero(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseUnsignedInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void requireIds(long challengeId, long playerId) {
        if (challengeId == 0) {
            throw new IllegalArgumentException("challenge_id 不能为 0");
        }
        if (playerId == 0) {
            throw new IllegalArgumentException("玩家号不能为 0");
        }
    }
}
