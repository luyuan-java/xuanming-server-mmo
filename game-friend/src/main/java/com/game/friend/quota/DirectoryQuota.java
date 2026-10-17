package com.game.friend.quota;

import com.game.discovery.RedisKeys;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.metrics.FriendMetrics.QuotaOutcome;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import org.redisson.api.RedissonClient;

/**
 * 在线目录的翻页配额（基线 logic/online_directory.go）：每人每 60 s 至多 60 页，阈值写死；脚本与发申请配额相同（INCR + EXPIRE 一段 Lua）。
 *
 * <p>与发申请配额相反，这里出故障时 <b>fail-closed</b>（回 1003）：在线目录每页要 SCAN 整个 Redis 库，放开等于把 Redis 的 CPU 交给客户端。
 * 被拒的请求也计数（固定窗口）；调用方先校验输入再计数（非法输入不占额度）。计数写同发申请配额：截止已过不发（回 ERROR），发出时不重发、
 * 超时取剩余预算（{@link FriendRequestQuota#incrOnce}）。
 */
public final class DirectoryQuota {

    public static final int PAGES_PER_MINUTE = 60;

    public enum Result { ALLOWED, REJECTED, ERROR }

    private final BiFunction<String, Duration, CompletionStage<Long>> incr;
    private final RedisKeys redisKeys;
    private final FriendMetrics metrics;

    /**
     * @param incr      对给定键执行 {@link FriendRequestQuota#INCR_WITH_TTL} 的异步调用；第二个参数是这一次调用的超时（= 发出时的剩余预算），
     *                  实现不得重发（测试注入假实现）
     * @param redisKeys 本部署命名空间的键名（计数器键由它生成）
     */
    public DirectoryQuota(BiFunction<String, Duration, CompletionStage<Long>> incr, RedisKeys redisKeys, FriendMetrics metrics) {
        this.incr = incr;
        this.redisKeys = Objects.requireNonNull(redisKeys, "redisKeys");
        this.metrics = metrics;
    }

    public static DirectoryQuota redisson(RedissonClient redis, RedisKeys redisKeys, FriendMetrics metrics) {
        return new DirectoryQuota((key, timeout) -> FriendRequestQuota.incrOnce(redis, key, timeout), redisKeys, metrics);
    }

    public Result tryAcquire(long playerId, Deadline deadline) {
        if (deadline.expired()) {
            // 截止已过不发计数写（发出去会落在 gate 已放行的下一条之后）；fail-closed，同 Redis 故障
            metrics.directoryQuota(QuotaOutcome.ERROR);
            return Result.ERROR;
        }
        Long count;
        try {
            count = deadline.await(incr.apply(redisKeys.friendDirectoryQuota(playerId), FriendRequestQuota.remaining(deadline)),
                    "在线目录配额");
        } catch (DependencyException e) {
            metrics.directoryQuota(QuotaOutcome.ERROR);
            return Result.ERROR;
        }
        if (count == null) {
            metrics.directoryQuota(QuotaOutcome.ERROR);
            return Result.ERROR;
        }
        if (count > PAGES_PER_MINUTE) {
            metrics.directoryQuota(QuotaOutcome.REJECTED);
            return Result.REJECTED;
        }
        metrics.directoryQuota(QuotaOutcome.ALLOWED);
        return Result.ALLOWED;
    }
}
