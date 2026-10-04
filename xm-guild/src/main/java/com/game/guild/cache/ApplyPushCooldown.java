package com.game.guild.cache;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import com.game.guild.rules.GuildLimits;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 入帮申请推送冷却（基线 TryMarkApplyPush，guild_manage_repo.go:679-700；guild-spec §1.11）：同一（帮会, 申请人）在 60 s 内
 * 至多推一次 APPLICATION_RECEIVED，挡住「申请 → 撤回 → 申请」的刷屏。键 {@code xm:guild:{g:<gid>}:apply-push:<pid>}，
 * {@code SET NX PX 60000}，自然过期、不清理。只对<b>新插入</b>的申请调用（guild_manage_logic.go:501；同帮刷新不推）。
 *
 * <p>拿到键 → 可以推；键已存在或 <b>Redis 出错 → 不推</b>（记 INFO；N3：推送只承诺「至多一次」，审批人打开申请页就能看到全部待审，
 * 少一条提示的代价远小于 Redis 抖动期间的刷屏）。Redisson 在响应超时后重发 SET NX 时第二次会回「已存在」——同样落在「不推」，
 * 与「至多一次」一致。
 *
 * <p>线程：{@link #tryMark} 在 guild-worker 上阻塞等待（上界是请求预算），从不抛。
 */
public final class ApplyPushCooldown {

    private static final Logger log = LoggerFactory.getLogger(ApplyPushCooldown.class);

    /** 冷却需要的 Redis 操作（单测可换成假实现）。 */
    @FunctionalInterface
    public interface CooldownRedis {

        /** {@code SET key "1" NX PX ttlMillis}；写入成功为 true，键已存在为 false。 */
        CompletionStage<Boolean> setIfAbsent(String key, long ttlMillis);
    }

    private final CooldownRedis redis;
    private final long cooldownMillis;

    public ApplyPushCooldown(CooldownRedis redis) {
        this(redis, GuildLimits.APPLY_PUSH_COOLDOWN_MS);
    }

    ApplyPushCooldown(CooldownRedis redis, long cooldownMillis) {
        this.redis = redis;
        this.cooldownMillis = cooldownMillis;
    }

    /** Redisson 实现：{@code RBucket.setIfAbsentAsync("1", PX)}，即 {@code SET NX PX}。 */
    public static ApplyPushCooldown redisson(RedissonClient client) {
        return new ApplyPushCooldown((key, ttlMillis) -> {
            try {
                return client.<String>getBucket(key, StringCodec.INSTANCE).setIfAbsentAsync("1", Duration.ofMillis(ttlMillis));
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    /**
     * 试占冷却键。
     *
     * @return true = 本次可以推 APPLICATION_RECEIVED；false = 冷却中或 Redis 不可用（不推）
     */
    public boolean tryMark(long guildId, long playerId, Deadline deadline) {
        String key = RedisKeys.guildApplyPush(guildId, playerId);
        try {
            CompletionStage<Boolean> marked;
            try {
                marked = redis.setIfAbsent(key, cooldownMillis);
            } catch (RuntimeException e) {
                marked = CompletableFuture.failedFuture(e);
            }
            return Boolean.TRUE.equals(deadline.await(marked, "占申请推送冷却 " + key));
        } catch (DependencyException e) {
            log.info("[guild] 申请推送冷却不可用，本次不推 guild={}: {}", Long.toUnsignedString(guildId), GuildCache.rootMessage(e));
            return false;
        }
    }
}
