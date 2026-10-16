package com.game.guild.quota;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import com.game.discovery.RedisKeys;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原 Go 公会 ActionQuota 的申请尝试配额：同一玩家跨目标帮共享、首个尝试起固定 60 秒。
 * 重复刷新、随后仓储拒绝或失败都已消耗；单段 Lua 保证计数和过期原子，无 TTL 旧键自行修复。
 *
 * <p>这是背压门，保留原仓 fail-open：Redis 故障只计 error，不代替 SQL 事务内权限和硬上限。
 * 等待取父预算与 250ms 较小者；父请求过期或线程中断必须失败，不能以降级为由继续写库。
 * 网络响应未知时计数可能已生效，重试可能多计一次，不能当成业务幂等凭据。
 */
public final class GuildApplyQuota {
    private static final Logger log = LoggerFactory.getLogger(GuildApplyQuota.class);
    static final long BUDGET_MS = 250;
    static final String INCR_WITH_TTL = """
            local n = redis.call('INCR', KEYS[1])
            if n == 1 or redis.call('PTTL', KEYS[1]) < 0 then
              redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """;

    private final Function<String, CompletionStage<Long>> increment;
    private final RedisKeys keys;
    private final int limit;
    private final Counter allowed;
    private final Counter rejected;
    private final Counter errors;
    private final AtomicLong lastWarningNanos = new AtomicLong();

    /** 异步 Redis 接缝用于确定性测试；生产由 redisson 工厂装配。 */
    public GuildApplyQuota(Function<String, CompletionStage<Long>> increment, RedisKeys keys, int limit,
                           MeterRegistry meters) {
        this.increment = Objects.requireNonNull(increment, "increment");
        this.keys = Objects.requireNonNull(keys, "keys");
        if (limit < 1 || limit > 600) {
            throw new IllegalArgumentException("公会申请每分钟配额必须在 [1, 600] 内");
        }
        this.limit = limit;
        Objects.requireNonNull(meters, "meters");
        allowed = meters.counter("game.guild.apply.quota", "result", "allowed");
        rejected = meters.counter("game.guild.apply.quota", "result", "rejected");
        errors = meters.counter("game.guild.apply.quota", "result", "error");
    }

    public static GuildApplyQuota redisson(RedissonClient redis, RedisKeys keys, int limit, MeterRegistry meters) {
        return new GuildApplyQuota(key -> redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE,
                INCR_WITH_TTL, RScript.ReturnType.INTEGER, List.of(key), "60000"), keys, limit, meters);
    }

    public boolean tryAcquire(long playerId, Deadline deadline) {
        requireActive(deadline);
        if (playerId == 0) {
            throw new IllegalArgumentException("公会申请配额玩家 ID 不能为零");
        }
        CompletionStage<Long> pending = null;
        try {
            pending = Objects.requireNonNull(increment.apply(keys.guildApplyQuota(playerId)), "配额异步结果");
            // 父期限较短时直接等待原 Deadline，不能把剩余纳秒向下取整后提前超时并降级。
            Deadline bounded = deadline.remainingNanos() <= TimeUnit.MILLISECONDS.toNanos(BUDGET_MS)
                    ? deadline : Deadline.after(BUDGET_MS);
            Long count = bounded.await(pending, "公会申请尝试配额");
            requireActive(deadline);
            if (count == null || count <= 0) {
                return unavailable();
            }
            if (count > limit) {
                rejected.increment();
                return false;
            }
            allowed.increment();
            return true;
        } catch (RuntimeException e) {
            if (pending != null) {
                pending.toCompletableFuture().cancel(false);
            }
            requireActive(deadline);
            return unavailable();
        }
    }

    private boolean unavailable() {
        errors.increment();
        long now = System.nanoTime();
        long last = lastWarningNanos.get();
        if ((last == 0 || now - last >= TimeUnit.MINUTES.toNanos(1)) && lastWarningNanos.compareAndSet(last, now)) {
            log.warn("[guild] 申请尝试配额不可用，本次放行；SQL 权限和数量上限仍须复核");
        }
        return true;
    }

    private static void requireActive(Deadline deadline) {
        if (deadline.expired() || Thread.currentThread().isInterrupted()) {
            throw new DependencyException("公会申请请求已过期或被中断");
        }
    }
}
