package com.game.friend.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.discovery.RedisKeys;
import com.game.friend.metrics.FriendMetrics;
import com.game.common.deadline.Deadline;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.ObjectParams;
import org.redisson.api.options.OptionalOptions;
import org.redisson.client.RedisNoScriptException;
import org.redisson.client.codec.Codec;
import org.redisson.misc.CompletableFutureWrapper;

class FriendRequestQuotaTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));
    /** 计数脚本经 EVALSHA 发出时替身记下的那一条。 */
    private static final String EVAL_SHA = "evalSha " + FriendRequestQuota.INCR_WITH_TTL_SHA;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FriendMetrics metrics = new FriendMetrics(registry);

    private double count(String outcome) {
        return registry.get("game.friend.request.quota").tag("outcome", outcome).counter().count();
    }

    private double directoryCount(String outcome) {
        return registry.get("game.friend.directory.quota").tag("outcome", outcome).counter().count();
    }

    @Test
    void 第limit次恰好用完仍放行_超过才拒_键带玩家号() {
        AtomicLong n = new AtomicLong();
        List<String> keys = new ArrayList<>();
        FriendRequestQuota quota = new FriendRequestQuota((key, timeout) -> {
            keys.add(key);
            return CompletableFuture.completedFuture(n.incrementAndGet());
        }, REDIS_KEYS, 2, metrics);
        assertThat(quota.tryAcquire(-1L, Deadline.after(1000))).isTrue();
        assertThat(quota.tryAcquire(-1L, Deadline.after(1000))).isTrue();
        assertThat(quota.tryAcquire(-1L, Deadline.after(1000))).isFalse();
        assertThat(keys).containsOnly(REDIS_KEYS.friendRequestQuota(-1L));
        assertThat(keys.get(0)).isEqualTo("game:friend:{18446744073709551615}:quota");
        assertThat(count("allowed")).isEqualTo(2);
        assertThat(count("rejected")).isEqualTo(1);
    }

    /** 计数器键只跟注入的 RedisKeys 走：同一个 JVM 里 game 与 nstest 两套各数各的（发申请配额与在线目录配额）。 */
    @Test
    void 计数器键跟注入的命名空间走() {
        RedisKeys nstest = new RedisKeys(GameNamespace.of("nstest"));
        List<String> keys = new ArrayList<>();
        new FriendRequestQuota((key, timeout) -> {
            keys.add(key);
            return CompletableFuture.completedFuture(1L);
        }, nstest, 2, metrics).tryAcquire(-1L, Deadline.after(1000));
        new FriendRequestQuota((key, timeout) -> {
            keys.add(key);
            return CompletableFuture.completedFuture(1L);
        }, REDIS_KEYS, 2, metrics).tryAcquire(-1L, Deadline.after(1000));
        new DirectoryQuota((key, timeout) -> {
            keys.add(key);
            return CompletableFuture.completedFuture(1L);
        }, nstest, metrics).tryAcquire(7, Deadline.after(1000));

        assertThat(keys).containsExactly("nstest:friend:{18446744073709551615}:quota",
                "game:friend:{18446744073709551615}:quota", nstest.friendDirectoryQuota(7));
        assertThat(keys.get(2)).startsWith("nstest:");
    }

    @Test
    void Redis出错或超时放行并计error_limit为0放行不计() {
        FriendRequestQuota failing = new FriendRequestQuota(
                (key, timeout) -> CompletableFuture.failedFuture(new IllegalStateException("down")), REDIS_KEYS, 10, metrics);
        assertThat(failing.tryAcquire(1, Deadline.after(1000))).isTrue();
        FriendRequestQuota hanging = new FriendRequestQuota((key, timeout) -> new CompletableFuture<>(), REDIS_KEYS, 10, metrics);
        assertThat(hanging.tryAcquire(1, Deadline.after(20))).isTrue();
        assertThat(count("error")).isEqualTo(2);

        FriendRequestQuota unlimited = new FriendRequestQuota((key, timeout) -> {
            throw new AssertionError("不该调用");
        }, REDIS_KEYS, 0, metrics);
        assertThat(unlimited.tryAcquire(1, Deadline.after(1000))).isTrue();
        assertThat(count("allowed") + count("rejected")).isZero();
    }

    /**
     * 截止已过：两种配额都不发计数写（mmorpg client-request-ordering.md §6.1——截止之后才发出的写会落在 gate 已放行的同一会话下一条请求之后）。
     * 发申请配额按「超过预算」fail-open，在线目录配额 fail-closed，都计 error。
     */
    @Test
    void 截止已过_不发计数写_发申请放行_在线目录回ERROR() {
        Deadline expired = Deadline.after(0);
        FriendRequestQuota request = new FriendRequestQuota((key, timeout) -> {
            throw new AssertionError("截止之后不该发出计数写");
        }, REDIS_KEYS, 10, metrics);
        DirectoryQuota directory = new DirectoryQuota((key, timeout) -> {
            throw new AssertionError("截止之后不该发出计数写");
        }, REDIS_KEYS, metrics);

        assertThat(request.tryAcquire(1, expired)).isTrue();
        assertThat(directory.tryAcquire(1, expired)).isEqualTo(DirectoryQuota.Result.ERROR);
        assertThat(count("error")).isEqualTo(1);
        assertThat(directoryCount("error")).isEqualTo(1);
    }

    /**
     * 截止给 700 ms：明显小于全局 {@code game.redis.timeout-ms}（缺省 2000），实现退回全局超时（2000）或没设超时（0）都会落在区间外。
     */
    @Test
    void 计数写的超时取发出时的剩余预算() {
        List<Duration> timeouts = new ArrayList<>();
        new FriendRequestQuota((key, timeout) -> {
            timeouts.add(timeout);
            return CompletableFuture.completedFuture(1L);
        }, REDIS_KEYS, 10, metrics).tryAcquire(1, Deadline.after(700));
        new DirectoryQuota((key, timeout) -> {
            timeouts.add(timeout);
            return CompletableFuture.completedFuture(1L);
        }, REDIS_KEYS, metrics).tryAcquire(1, Deadline.after(700));

        assertThat(timeouts).hasSize(2).allSatisfy(timeout -> assertThat(timeout.toMillis()).isBetween(1L, 700L));
    }

    /**
     * Redisson 实现：两种配额的计数写都按客户端写发出（{@code getScript(OptionalOptions)}：不重发、Redisson 超时取剩余预算），直接发 EVALSHA，
     * 一条都不走带全局重发的 {@code getScript(Codec)}。全局 retry-attempts = 1 时截止前发出的 INCR 约 2.2 s 后会被原样重发，
     * 那时 gate 可能已放行同一会话的下一条。截止给 700 ms，理由同上一条。
     */
    @Test
    void Redisson实现_计数写不重发_超时不超过剩余预算() {
        RecordingRedis redis = new RecordingRedis();

        assertThat(FriendRequestQuota.redisson(redis.client, REDIS_KEYS, 10, metrics).tryAcquire(1, Deadline.after(700))).isTrue();
        assertThat(DirectoryQuota.redisson(redis.client, REDIS_KEYS, metrics).tryAcquire(1, Deadline.after(700)))
                .isEqualTo(DirectoryQuota.Result.ALLOWED);

        assertThat(redis.retrying).as("一条都不走带全局重发的句柄").isEmpty();
        assertThat(redis.clientWrites).hasSize(2).allSatisfy(params -> {
            assertThat(params.getRetryAttempts()).as("不重发").isZero();
            assertThat(params.getTimeout()).as("超时取剩余预算").isBetween(1, 700);
        });
        assertThat(redis.scriptCalls).as("直接发 EVALSHA，不走 evalAsync 的 NOSCRIPT 自动重载")
                .containsExactly(EVAL_SHA, EVAL_SHA);
    }

    /**
     * Redis 重启 / 主从切换 / SCRIPT FLUSH 之后第一次调用回 NOSCRIPT：截止之前 SCRIPT LOAD、再发一次 EVALSHA，三步都按客户端写发出，
     * 重发那条的超时取那一刻的剩余预算（Redisson 自己的重载链会给第二条 EVALSHA 一份完整的超时、中间不看截止）。
     */
    @Test
    void Redisson实现_遇NOSCRIPT_截止前加载并重发EVALSHA_超时取当时的剩余预算() {
        RecordingRedis redis = new RecordingRedis();
        AtomicLong nanos = new AtomicLong();
        redis.evalShaFailures.add(noScript());
        redis.onScriptLoad = () -> nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(400)); // SCRIPT LOAD 花了 400 ms

        assertThat(FriendRequestQuota.incrOnce(redis.client, "k", Duration.ofMillis(1_000), nanos::get).toCompletableFuture().join())
                .isEqualTo(1L);

        assertThat(redis.scriptCalls).containsExactly(EVAL_SHA, "load " + FriendRequestQuota.INCR_WITH_TTL, EVAL_SHA);
        assertThat(redis.retrying).as("一条都不走带全局重发的句柄").isEmpty();
        assertThat(redis.clientWrites.stream().map(ObjectParams::getRetryAttempts).toList()).containsOnly(0);
        assertThat(redis.clientWrites.stream().map(ObjectParams::getTimeout).toList()).containsExactly(1_000, 1_000, 600);
    }

    /** SCRIPT LOAD 回来时截止已过：真正计数的那条 EVALSHA 不再发出，以超时失败（发申请配额照 Redis 故障放行，在线目录回 ERROR）。 */
    @Test
    void Redisson实现_遇NOSCRIPT_加载完已过截止_不重发EVALSHA_以超时失败() {
        RecordingRedis redis = new RecordingRedis();
        AtomicLong nanos = new AtomicLong();
        redis.evalShaFailures.add(noScript());
        redis.onScriptLoad = () -> nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_000)); // SCRIPT LOAD 拖到了截止

        assertThatThrownBy(() -> FriendRequestQuota.incrOnce(redis.client, "k", Duration.ofMillis(1_000), nanos::get)
                .toCompletableFuture().join()).hasRootCauseInstanceOf(TimeoutException.class);
        assertThat(redis.scriptCalls).as("截止之后不再发出计数的 EVALSHA")
                .containsExactly(EVAL_SHA, "load " + FriendRequestQuota.INCR_WITH_TTL);
    }

    @Test
    void Redisson实现_遇NOSCRIPT以外的错误_不加载不重发_原样失败() {
        RecordingRedis redis = new RecordingRedis();
        redis.evalShaFailures.add(new IllegalStateException("连接已断"));

        assertThatThrownBy(() -> FriendRequestQuota.incrOnce(redis.client, "k", Duration.ofMillis(1_000), () -> 0L)
                .toCompletableFuture().join()).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(redis.scriptCalls).containsExactly(EVAL_SHA);
    }

    private static RedisNoScriptException noScript() {
        return new RedisNoScriptException("NOSCRIPT No matching script. Please use EVAL.");
    }

    /** 记录型 {@link RedissonClient} 替身：记下句柄参数与脚本句柄上依次发出的命令；{@code evalShaAsync} 依次取 {@link #evalShaFailures}，取空了回 1。 */
    private static final class RecordingRedis {

        final RedissonClient client = mock(RedissonClient.class);
        /** 按客户端写发出（{@code getScript(OptionalOptions)}）的句柄参数。 */
        final List<ObjectParams> clientWrites = new CopyOnWriteArrayList<>();
        /** 走全局句柄（{@code getScript(Codec)}：全局超时、带重发）的调用。 */
        final List<Codec> retrying = new CopyOnWriteArrayList<>();
        /** {@code "evalSha <sha>"} / {@code "load <脚本正文>"} / {@code "eval"}。 */
        final List<String> scriptCalls = new CopyOnWriteArrayList<>();
        final Deque<Throwable> evalShaFailures = new ConcurrentLinkedDeque<>();
        /** {@code scriptLoadAsync} 被调用时执行：测试借它拨时钟（SCRIPT LOAD 花掉的时间）。 */
        volatile Runnable onScriptLoad = () -> { };

        RecordingRedis() {
            RScript script = mock(RScript.class, invocation -> switch (invocation.getMethod().getName()) {
                case "evalAsync" -> {
                    scriptCalls.add("eval");
                    yield new CompletableFutureWrapper<Long>(1L);
                }
                case "evalShaAsync" -> {
                    scriptCalls.add("evalSha " + invocation.<String>getArgument(1));
                    Throwable failure = evalShaFailures.poll();
                    yield failure == null ? new CompletableFutureWrapper<Long>(1L) : new CompletableFutureWrapper<Long>(failure);
                }
                case "scriptLoadAsync" -> {
                    scriptCalls.add("load " + invocation.<String>getArgument(0));
                    onScriptLoad.run();
                    yield new CompletableFutureWrapper<String>(FriendRequestQuota.INCR_WITH_TTL_SHA);
                }
                default -> null;
            });
            when(client.getScript(any(OptionalOptions.class))).thenAnswer(invocation -> {
                clientWrites.add(invocation.getArgument(0));
                return script;
            });
            when(client.getScript(any(Codec.class))).thenAnswer(invocation -> {
                retrying.add(invocation.getArgument(0));
                return script;
            });
        }
    }
}
