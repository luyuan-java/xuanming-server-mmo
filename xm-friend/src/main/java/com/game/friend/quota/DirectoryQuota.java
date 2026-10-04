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

/**
 * 在线目录的翻页配额（基线 logic/online_directory.go）：每人每 60 s 至多 60 页，阈值写死；脚本与发申请配额相同（INCR + EXPIRE 一段 Lua）。
 *
 * <p>与发申请配额相反，这里出故障时 <b>fail-closed</b>（回 1003）：在线目录每页要 SCAN 整个 Redis 库，放开等于把 Redis 的 CPU 交给客户端。
 * 被拒的请求也计数（固定窗口）；调用方先校验输入再计数（非法输入不占额度）。
 */
public final class DirectoryQuota {

    public static final int PAGES_PER_MINUTE = 60;

    public enum Result { ALLOWED, REJECTED, ERROR }

    private final Function<String, CompletionStage<Long>> incr;
    private final FriendMetrics metrics;

    public DirectoryQuota(Function<String, CompletionStage<Long>> incr, FriendMetrics metrics) {
        this.incr = incr;
        this.metrics = metrics;
    }

    public static DirectoryQuota redisson(RedissonClient redis, FriendMetrics metrics) {
        return new DirectoryQuota(key -> redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE,
                FriendRequestQuota.INCR_WITH_TTL, RScript.ReturnType.INTEGER, List.of(key), FriendRequestQuota.WINDOW_SECONDS),
                metrics);
    }

    public Result tryAcquire(long playerId, Deadline deadline) {
        Long count;
        try {
            count = deadline.await(incr.apply(RedisKeys.friendDirectoryQuota(playerId)), "在线目录配额");
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
