package com.game.friend.quota;

import com.game.discovery.RedisKeys;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.metrics.FriendMetrics.QuotaOutcome;
import com.game.friend.support.Deadline;
import com.game.friend.support.Deadline.DependencyException;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 发好友申请的每分钟配额（基线 rate_quota.go）：固定 60 s 窗口、每人 {@code limit} 次，限的是<b>尝试</b>次数——之后因已申请 / 拉黑被拒、
 * 甚至存储故障的那一次也已消耗。{@code count > limit} 才拒（第 limit 次恰好用完、仍放行）。
 *
 * <p><b>fail-open</b>（放行并计 {@code error}）：Redis 出错、超过预算、返回值不对。配额是防刷不是防作弊，硬上限都在 MySQL 里 fail-closed；
 * Redis 抖一下就让全服加不了好友代价不可接受。{@code limit == 0} 放行且不计指标。
 *
 * <p>INCR 与 EXPIRE 必须是一段 Lua：分两次调用时中间崩溃会留下永不过期的计数器，该玩家从此发不出申请；{@code TTL == -1} 分支
 * 自愈存量的无 TTL 计数器。Redisson 响应超时重发同一段 EVAL 时多计一次（偏严一次，可以接受）。
 */
public final class FriendRequestQuota {

    private static final Logger log = LoggerFactory.getLogger(FriendRequestQuota.class);

    /** 窗口固定 60 s，不可配（同基线）。 */
    static final String WINDOW_SECONDS = "60";

    static final String INCR_WITH_TTL = """
            local n = redis.call('INCR', KEYS[1])
            if n == 1 or redis.call('TTL', KEYS[1]) == -1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """;

    private final Function<String, CompletionStage<Long>> incr;
    private final int limit;
    private final FriendMetrics metrics;

    /** @param incr 对给定键执行 {@link #INCR_WITH_TTL} 的异步调用（测试注入假实现） */
    public FriendRequestQuota(Function<String, CompletionStage<Long>> incr, int limit, FriendMetrics metrics) {
        this.incr = incr;
        this.limit = limit;
        this.metrics = metrics;
    }

    public static FriendRequestQuota redisson(RedissonClient redis, int limit, FriendMetrics metrics) {
        return new FriendRequestQuota(key -> redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE,
                INCR_WITH_TTL, RScript.ReturnType.INTEGER, List.of(key), WINDOW_SECONDS), limit, metrics);
    }

    /** 消耗一次配额；返回是否放行（工作线程上调用，等待上界是请求预算）。 */
    public boolean tryAcquire(long playerId, Deadline deadline) {
        if (limit <= 0) {
            return true;
        }
        Long count;
        try {
            count = deadline.await(incr.apply(RedisKeys.friendRequestQuota(playerId)), "好友申请配额");
        } catch (DependencyException e) {
            metrics.quota(QuotaOutcome.ERROR);
            log.warn("[friend] 好友申请配额不可用，本次放行（fail-open） player={}: {}", Long.toUnsignedString(playerId),
                    e.getCause() == null ? e.getMessage() : e.getCause().toString());
            return true;
        }
        if (count == null) {
            metrics.quota(QuotaOutcome.ERROR);
            log.warn("[friend] 好友申请配额返回空值，本次放行 player={}", Long.toUnsignedString(playerId));
            return true;
        }
        if (count > limit) {
            metrics.quota(QuotaOutcome.REJECTED);
            return false;
        }
        metrics.quota(QuotaOutcome.ALLOWED);
        return true;
    }
}
