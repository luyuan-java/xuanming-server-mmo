package com.game.friend.quota;

import com.game.discovery.RedisKeys;
import com.game.friend.metrics.FriendMetrics;
import com.game.friend.metrics.FriendMetrics.QuotaOutcome;
import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.OptionalOptions;
import org.redisson.client.RedisNoScriptException;
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
 * 自愈存量的无 TTL 计数器。
 *
 * <p>计数写是客户端请求路径上的写：截止已过不发出，发出时不重发、Redisson 超时取剩余预算（{@link #incrOnce}）。
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

    /** {@link #INCR_WITH_TTL} 的 SHA1（Redis 脚本缓存的键，与 SCRIPT LOAD 的返回值相同）。 */
    static final String INCR_WITH_TTL_SHA = sha1Hex(INCR_WITH_TTL);

    private final BiFunction<String, Duration, CompletionStage<Long>> incr;
    private final RedisKeys redisKeys;
    private final int limit;
    private final FriendMetrics metrics;

    /**
     * @param incr      对给定键执行 {@link #INCR_WITH_TTL} 的异步调用；第二个参数是这一次调用的超时（= 发出时的剩余预算），实现不得重发
     *                  （测试注入假实现）
     * @param redisKeys 本部署命名空间的键名（计数器键由它生成）
     */
    public FriendRequestQuota(BiFunction<String, Duration, CompletionStage<Long>> incr, RedisKeys redisKeys, int limit,
                              FriendMetrics metrics) {
        this.incr = incr;
        this.redisKeys = Objects.requireNonNull(redisKeys, "redisKeys");
        this.limit = limit;
        this.metrics = metrics;
    }

    public static FriendRequestQuota redisson(RedissonClient redis, RedisKeys redisKeys, int limit, FriendMetrics metrics) {
        return new FriendRequestQuota((key, timeout) -> incrOnce(redis, key, timeout), redisKeys, limit, metrics);
    }

    /**
     * 客户端请求路径上的一次计数写（发申请配额与在线目录配额共用）：不重发，Redisson 超时取调用方给的剩余预算。
     *
     * <p>为什么：gate 对同一会话的后端请求逐条发出，friend 回包或 gate 的 5 s 调用超时之后就放行下一条（mmorpg client-request-ordering.md
     * §6.1）。全局 {@code game.redis.retry-attempts = 1} 下，截止前发出的 INCR 响应超时（2 s）后隔 200 ms 原样重发，会落在同一会话的
     * 下一条请求之后、把它的计数多算一次。不重发时 Redisson 用同一个超时限制等连接，取剩余预算后到截止还没拿到连接的命令不再发出
     * （它的定时器是时间轮，到点可能晚约一个刻度）。
     *
     * <p>不用 {@code evalAsync}：Redisson 缺省开着脚本缓存，{@code evalAsync} 先发 EVALSHA，遇 NOSCRIPT 就自己 SCRIPT LOAD 再发一条 EVALSHA，
     * 两步各用一份完整的超时、中间不看请求截止——真正计数的是第二条，最坏在截止之后才发出。Redis 重启、主从切换、SCRIPT FLUSH 之后第一次调用
     * 都走这条链，而这正是连接在重建、最容易拖到截止的时候。这里直接发 EVALSHA、自己处理 NOSCRIPT：SCRIPT LOAD 没有业务副作用，重发的 EVALSHA
     * 才是写；两者发出前各查一次截止（{@code timeout} 从进入本方法起算），过了就以 {@link TimeoutException} 失败、不再发出（调用方照 Redis 故障处理：
     * 发申请配额 fail-open，在线目录配额回 1003）。
     *
     * <p>代价：截止之前、命令还没写到 Redis 的瞬时断连（池里的连接已断、写失败）也不再换连接自动重试，同样按 Redis 故障处理（发申请照样放行，
     * 在线目录回 1003、由客户端重试兜底）。
     */
    static CompletionStage<Long> incrOnce(RedissonClient redis, String key, Duration timeout) {
        return incrOnce(redis, key, timeout, System::nanoTime);
    }

    /** 同 {@link #incrOnce(RedissonClient, String, Duration)}；单调时钟可注入（测试钉住 NOSCRIPT 之后重发前的截止检查）。 */
    static CompletionStage<Long> incrOnce(RedissonClient redis, String key, Duration timeout, LongSupplier nanoClock) {
        long untilNanos = nanoClock.getAsLong() + timeout.toNanos();
        return evalSha(redis, key, timeout).exceptionallyCompose(error -> {
            if (!(unwrap(error) instanceof RedisNoScriptException)) {
                return CompletableFuture.failedFuture(error);
            }
            return beforeDeadline(untilNanos, nanoClock, left -> redis.getScript(clientWrite(left)).scriptLoadAsync(INCR_WITH_TTL))
                    .thenCompose(loaded -> beforeDeadline(untilNanos, nanoClock, left -> evalSha(redis, key, left)));
        });
    }

    /** 客户端写的脚本句柄：不重发，Redisson 超时（含等连接）= 给定的剩余预算。 */
    private static OptionalOptions clientWrite(Duration timeout) {
        return OptionalOptions.defaults().codec(StringCodec.INSTANCE).retryAttempts(0).timeout(timeout);
    }

    private static CompletableFuture<Long> evalSha(RedissonClient redis, String key, Duration timeout) {
        return redis.getScript(clientWrite(timeout)).<Long>evalShaAsync(RScript.Mode.READ_WRITE, INCR_WITH_TTL_SHA,
                RScript.ReturnType.INTEGER, List.of(key), WINDOW_SECONDS).toCompletableFuture();
    }

    /** 截止之前才调用 {@code send}，交给它此刻的剩余预算（至少 1 ms：Redisson 的超时取 0 会退回全局值）；截止已过就不调用、以超时失败。 */
    private static <T> CompletableFuture<T> beforeDeadline(long untilNanos, LongSupplier nanoClock,
                                                          Function<Duration, ? extends CompletionStage<T>> send) {
        long leftNanos = untilNanos - nanoClock.getAsLong();
        if (leftNanos <= 0) {
            return CompletableFuture.failedFuture(new TimeoutException("超过请求预算（NOSCRIPT 之后），不再加载、重发计数脚本"));
        }
        return send.apply(Duration.ofMillis(Math.max(1, TimeUnit.NANOSECONDS.toMillis(leftNanos)))).toCompletableFuture();
    }

    private static Throwable unwrap(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    static String sha1Hex(String script) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(script.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JDK 缺少 SHA-1", e); // 每个 JDK 都必须提供 SHA-1，走不到
        }
    }

    /** 本次计数写的超时：此刻的剩余预算（至少 1 ms）；剩余预算一直在变，每次发出前现取。 */
    static Duration remaining(Deadline deadline) {
        return Duration.ofMillis(Math.max(1, deadline.remainingMillis()));
    }

    /** 消耗一次配额；返回是否放行（工作线程上调用，等待上界是请求预算）。 */
    public boolean tryAcquire(long playerId, Deadline deadline) {
        if (limit <= 0) {
            return true;
        }
        if (deadline.expired()) {
            // 截止已过不发计数写（发出去会落在 gate 已放行的下一条之后）；按「超过预算」fail-open，随后的存储步骤自己会因预算用完失败
            metrics.quota(QuotaOutcome.ERROR);
            log.warn("[friend] 好友申请配额之前请求预算已用完，不计数、本次放行 player={}", Long.toUnsignedString(playerId));
            return true;
        }
        Long count;
        try {
            count = deadline.await(incr.apply(redisKeys.friendRequestQuota(playerId), remaining(deadline)), "好友申请配额");
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
