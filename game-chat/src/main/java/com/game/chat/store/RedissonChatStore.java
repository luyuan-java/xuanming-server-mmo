package com.game.chat.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.OptionalOptions;
import org.redisson.api.options.PlainOptions;
import org.redisson.client.RedisNoScriptException;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;

/**
 * {@link ChatStore} 的 Redisson 实现。脚本与基线逐字同义；每次一条脚本调用，不用 RBatch（批里的 EVALSHA 遇 NOSCRIPT 不会重新加载）。
 *
 * <p>发言路径上的三个写（占位、限速、追加历史）按客户端写发出：{@code retryAttempts = 0}、Redisson 超时取调用方给的剩余预算。
 * gate 在 chat 回包（含截止到点回的 1003）之后就放行同一会话的下一条（mmorpg client-request-ordering.md §6.1），所以这三个写只能在截止之前发出：
 * <ul>
 *   <li>全局句柄（{@code game.redis.retry-attempts = 1}、超时 2 s）下，脚本写出后响应超时会隔 200 ms 原样重发——追加历史会在 chat 已回 1003 之后
 *       多落一条（客户端以为没发出、换来一条重复且乱序的消息），限速会多算到下一条头上。占位的 SET NX 写出之后不会被重发（Redisson 把它列为
 *       不可重发命令），但全局句柄等连接最长 2 s、写失败还会隔 200 ms 换连接再发，同样可能在截止之后才发出。</li>
 *   <li>不重发时 Redisson 用同一个超时限制等连接：取剩余预算后，到截止还没拿到连接的命令不再发出（它的定时器是时间轮，到点可能晚约一个刻度）。</li>
 *   <li>两段脚本不走 {@code evalAsync} 的 NOSCRIPT 自动重载，自己处理（{@link #clientEval}）。</li>
 * </ul>
 * 代价：截止之前、命令还没写到 Redis 的瞬时断连（池里的连接已断、写失败）也不再换连接自动重试，直接按存储故障回 1003，由客户端重试兜底。
 * 已经写进连接的那一条仍可能在回包前后落地（§6.1「截止前已经发出的那一条撤不回来」），与基线「写入 Lua 超时但实际已落库」同一类偶发重复，聊天可接受。
 *
 * <p>完结 / 释放仍走带全局重发的句柄与 {@code evalAsync}：它们按值比对（「值相等才改」），重放无害，也不受请求截止约束。
 */
public final class RedissonChatStore implements ChatStore {

    static final String MARK_DONE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[3])
              return 1
            end
            return 0
            """;

    static final String RELEASE = """
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('DEL', KEYS[1])
            end
            return 0
            """;

    static final String INCR_WITH_TTL = """
            local n = redis.call('INCR', KEYS[1])
            if n == 1 or redis.call('TTL', KEYS[1]) == -1 then
              redis.call('EXPIRE', KEYS[1], ARGV[1])
            end
            return n
            """;

    static final String APPEND = """
            redis.call('LPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]) - 1)
            redis.call('EXPIRE', KEYS[1], ARGV[3])
            return 1
            """;

    /** 两段客户端写脚本的 SHA1（Redis 脚本缓存的键，与 SCRIPT LOAD 的返回值相同）。 */
    static final String INCR_WITH_TTL_SHA = sha1Hex(INCR_WITH_TTL);
    static final String APPEND_SHA = sha1Hex(APPEND);

    private final RedissonClient redis;
    private final LongSupplier nanoClock;

    public RedissonChatStore(RedissonClient redis) {
        this(redis, System::nanoTime);
    }

    /** 包内可见：测试注入单调时钟，钉住 NOSCRIPT 之后重发前的截止检查。 */
    RedissonChatStore(RedissonClient redis, LongSupplier nanoClock) {
        this.redis = redis;
        this.nanoClock = nanoClock;
    }

    @Override
    public CompletionStage<Boolean> claim(String key, String value, long ttlSeconds, Duration timeout) {
        PlainOptions once = PlainOptions.name(key).codec(StringCodec.INSTANCE).retryAttempts(0).timeout(timeout);
        return redis.<String>getBucket(once).setIfAbsentAsync(value, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public CompletionStage<String> get(String key) {
        return redis.<String>getBucket(key, StringCodec.INSTANCE).getAsync();
    }

    @Override
    public CompletionStage<Boolean> markDone(String key, String expected, String done, long ttlSeconds) {
        return redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, MARK_DONE,
                RScript.ReturnType.INTEGER, List.of(key), expected, done, Long.toString(ttlSeconds)).thenApply(r -> r != null && r == 1L);
    }

    @Override
    public CompletionStage<Boolean> release(String key, String expected) {
        return redis.getScript(StringCodec.INSTANCE).<Long>evalAsync(RScript.Mode.READ_WRITE, RELEASE,
                RScript.ReturnType.INTEGER, List.of(key), expected).thenApply(r -> r != null && r > 0);
    }

    @Override
    public CompletionStage<Long> incrWithTtl(String key, long ttlSeconds, Duration timeout) {
        return clientEval(StringCodec.INSTANCE, INCR_WITH_TTL, INCR_WITH_TTL_SHA, timeout, List.of(key), Long.toString(ttlSeconds));
    }

    @Override
    public CompletionStage<Void> append(String key, byte[] message, int maxEntries, long ttlSeconds, Duration timeout) {
        return clientEval(ByteArrayCodec.INSTANCE, APPEND, APPEND_SHA, timeout, List.of(key), message,
                        ascii(Integer.toString(maxEntries)), ascii(Long.toString(ttlSeconds)))
                .thenApply(r -> null);
    }

    @Override
    public CompletionStage<List<byte[]>> range(String key, int limit) {
        return redis.<byte[]>getList(key, ByteArrayCodec.INSTANCE).rangeAsync(0, limit - 1);
    }

    /** 客户端写的脚本句柄：不重发，Redisson 超时（含等连接）= 调用方给的剩余预算。 */
    static OptionalOptions clientWrite(Codec codec, Duration timeout) {
        return OptionalOptions.defaults().codec(codec).retryAttempts(0).timeout(timeout);
    }

    /**
     * 发言路径上的一次脚本写（限速、写历史）：直接发 EVALSHA，经 {@link #clientWrite} 句柄（不重发、超时 = {@code timeout}）。
     *
     * <p>为什么不用 {@code evalAsync}：Redisson 缺省开着脚本缓存，{@code evalAsync} 先发 EVALSHA，遇 NOSCRIPT 就自己 SCRIPT LOAD 再发一条 EVALSHA，
     * 两步各用一份完整的超时、中间不看请求截止——真正执行写的是第二条，最坏在截止之后才发出，那时 chat 已回 1003、gate 已放行下一条。
     * Redis 重启、主从切换、SCRIPT FLUSH 之后每段脚本的第一次调用都走这条链，而这正是连接在重建、最容易拖到截止的时候。
     * 这里自己处理 NOSCRIPT：SCRIPT LOAD 没有业务副作用，重发的 EVALSHA 才是写；两者发出前各查一次截止（{@code timeout} 从进入本方法起算），
     * 过了就以 {@link TimeoutException} 失败、不再发出（ChatService 照存储故障处理：释放占位、回 1003）。
     */
    private CompletionStage<Long> clientEval(Codec codec, String script, String sha, Duration timeout, List<Object> keys,
                                             Object... args) {
        long untilNanos = nanoClock.getAsLong() + timeout.toNanos();
        return evalSha(codec, sha, timeout, keys, args).exceptionallyCompose(error -> {
            if (!(unwrap(error) instanceof RedisNoScriptException)) {
                return CompletableFuture.failedFuture(error);
            }
            return beforeDeadline(untilNanos, left -> redis.getScript(clientWrite(codec, left)).scriptLoadAsync(script))
                    .thenCompose(loaded -> beforeDeadline(untilNanos, left -> evalSha(codec, sha, left, keys, args)));
        });
    }

    private CompletableFuture<Long> evalSha(Codec codec, String sha, Duration timeout, List<Object> keys, Object... args) {
        return redis.getScript(clientWrite(codec, timeout)).<Long>evalShaAsync(RScript.Mode.READ_WRITE, sha,
                RScript.ReturnType.INTEGER, keys, args).toCompletableFuture();
    }

    /** 截止之前才调用 {@code send}，交给它此刻的剩余预算（至少 1 ms：Redisson 的超时取 0 会退回全局值）；截止已过就不调用、以超时失败。 */
    private <T> CompletableFuture<T> beforeDeadline(long untilNanos, Function<Duration, ? extends CompletionStage<T>> send) {
        long leftNanos = untilNanos - nanoClock.getAsLong();
        if (leftNanos <= 0) {
            return CompletableFuture.failedFuture(new TimeoutException("超过请求预算（NOSCRIPT 之后），不再加载、重发脚本"));
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

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
