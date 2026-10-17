package com.game.chat.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.ObjectParams;
import org.redisson.api.options.OptionalOptions;
import org.redisson.api.options.PlainOptions;
import org.redisson.client.RedisNoScriptException;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.misc.CompletableFutureWrapper;

/**
 * {@link RedissonChatStore} 发出写的方式（不连 Redis：{@link RedissonClient} 是记录句柄参数与发出命令的替身，单调时钟可拨）。只钉
 * mmorpg client-request-ordering.md §6.1 那一条：发言路径上的三个写（占位、限速、写历史）按客户端写发出——不重发、Redisson 超时取调用方给的
 * 剩余预算，两段脚本遇 NOSCRIPT 时重发前再查截止；完结 / 释放照旧走带全局重发的句柄。
 * 脚本本身的行为由 {@code RedissonChatStoreIntegrationTest} 在真 Redis 上钉。
 */
class RedissonChatStoreTest {

    private final RedissonClient redis = mock(RedissonClient.class);
    /** 按客户端写发出（{@code getScript(OptionalOptions)} / {@code getBucket(PlainOptions)}）的句柄参数。 */
    private final List<ObjectParams> clientWrites = new CopyOnWriteArrayList<>();
    /** 走全局句柄（{@code getScript(Codec)}：全局超时、带重发）的调用。 */
    private final List<Codec> retrying = new CopyOnWriteArrayList<>();
    /** 脚本句柄上依次发出的命令：{@code "evalSha <sha>"} / {@code "load <脚本正文>"} / {@code "eval"}。 */
    private final List<String> scriptCalls = new CopyOnWriteArrayList<>();
    /** 依次作为 {@code evalShaAsync} 的失败；取空了就成功（回 1）。 */
    private final Deque<Throwable> evalShaFailures = new ConcurrentLinkedDeque<>();
    /** {@code scriptLoadAsync} 被调用时执行：测试借它拨时钟（SCRIPT LOAD 花掉的时间）。 */
    private volatile Runnable onScriptLoad = () -> { };
    private final AtomicLong nanos = new AtomicLong();
    private final RedissonChatStore store = new RedissonChatStore(redis, nanos::get);

    @SuppressWarnings("unchecked")
    RedissonChatStoreTest() {
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
                yield new CompletableFutureWrapper<String>("loaded");
            }
            default -> null;
        });
        when(redis.getScript(any(OptionalOptions.class))).thenAnswer(invocation -> {
            clientWrites.add(invocation.getArgument(0));
            return script;
        });
        when(redis.getScript(any(Codec.class))).thenAnswer(invocation -> {
            retrying.add(invocation.getArgument(0));
            return script;
        });
        RBucket<Object> bucket = mock(RBucket.class);
        when(bucket.setIfAbsentAsync(any(), any(Duration.class))).thenReturn(new CompletableFutureWrapper<>(true));
        when(redis.getBucket(any(PlainOptions.class))).thenAnswer(invocation -> {
            clientWrites.add(invocation.getArgument(0));
            return bucket;
        });
    }

    @Test
    void 发言路径的三个写_不重发_超时取调用方给的剩余预算() {
        Duration remaining = Duration.ofMillis(1_234);

        assertThat(store.claim("k", "pending:a", 60, remaining).toCompletableFuture().join()).isTrue();
        assertThat(store.incrWithTtl("rl", 1, remaining).toCompletableFuture().join()).isEqualTo(1L);
        store.append("log", new byte[] {1}, 200, 600, remaining).toCompletableFuture().join();

        assertThat(retrying).as("一条都不走带全局重发的句柄").isEmpty();
        assertThat(clientWrites).hasSize(3).allSatisfy(params -> {
            // 全局 retry-attempts = 1：截止前发出的脚本约 2.2 s 后会被原样重发，那时 chat 已回 1003、gate 已放行同一会话的下一条
            assertThat(params.getRetryAttempts()).as("不重发").isZero();
            // 不重发时 Redisson 用这个超时限制等连接：取剩余预算，到截止还没拿到连接的写不再发出
            assertThat(params.getTimeout()).as("超时 = 剩余预算").isEqualTo(1_234);
        });
        assertThat(scriptCalls).as("两段脚本直接发 EVALSHA，不走 evalAsync 的 NOSCRIPT 自动重载")
                .containsExactly("evalSha " + RedissonChatStore.INCR_WITH_TTL_SHA, "evalSha " + RedissonChatStore.APPEND_SHA);
    }

    /**
     * Redis 重启 / 主从切换 / SCRIPT FLUSH 之后第一次调用回 NOSCRIPT：截止之前 SCRIPT LOAD、再发一次 EVALSHA，三步都按客户端写发出，
     * 重发那条的超时取那一刻的剩余预算（Redisson 自己的重载链会给第二条 EVALSHA 一份完整的超时、中间不看截止）。
     */
    @Test
    void 遇NOSCRIPT_截止前加载并重发EVALSHA_超时取当时的剩余预算() {
        evalShaFailures.add(noScript());
        onScriptLoad = () -> nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(400)); // SCRIPT LOAD 花了 400 ms

        assertThat(store.incrWithTtl("rl", 1, Duration.ofMillis(1_000)).toCompletableFuture().join()).isEqualTo(1L);

        String evalSha = "evalSha " + RedissonChatStore.INCR_WITH_TTL_SHA;
        assertThat(scriptCalls).containsExactly(evalSha, "load " + RedissonChatStore.INCR_WITH_TTL, evalSha);
        assertThat(retrying).as("一条都不走带全局重发的句柄").isEmpty();
        assertThat(clientWrites.stream().map(ObjectParams::getRetryAttempts).toList()).containsOnly(0);
        assertThat(clientWrites.stream().map(ObjectParams::getTimeout).toList()).containsExactly(1_000, 1_000, 600);
    }

    /** SCRIPT LOAD 回来时截止已过：真正写历史的那条 EVALSHA 不再发出，以超时失败（ChatService 照存储故障释放占位、回 1003）。 */
    @Test
    void 遇NOSCRIPT_加载完已过截止_不重发EVALSHA_以超时失败() {
        evalShaFailures.add(noScript());
        onScriptLoad = () -> nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(1_000)); // SCRIPT LOAD 拖到了截止

        assertThatThrownBy(() -> store.append("log", new byte[] {1}, 200, 600, Duration.ofMillis(1_000)).toCompletableFuture().join())
                .hasRootCauseInstanceOf(TimeoutException.class);
        assertThat(scriptCalls).as("截止之后不再发出写历史的 EVALSHA")
                .containsExactly("evalSha " + RedissonChatStore.APPEND_SHA, "load " + RedissonChatStore.APPEND);
    }

    @Test
    void 遇NOSCRIPT以外的错误_不加载不重发_原样失败() {
        evalShaFailures.add(new IllegalStateException("连接已断"));

        assertThatThrownBy(() -> store.incrWithTtl("rl", 1, Duration.ofMillis(1_000)).toCompletableFuture().join())
                .hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(scriptCalls).containsExactly("evalSha " + RedissonChatStore.INCR_WITH_TTL_SHA);
    }

    @Test
    void 完结与释放_照旧走带全局重发的句柄() {
        assertThat(store.markDone("k", "pending:a", "done", 60).toCompletableFuture().join()).isTrue();
        assertThat(store.release("k", "pending:a").toCompletableFuture().join()).isTrue();

        assertThat(clientWrites).as("按值比对、可重放，也不受请求截止约束").isEmpty();
        assertThat(retrying).containsExactly(StringCodec.INSTANCE, StringCodec.INSTANCE);
        assertThat(scriptCalls).containsExactly("eval", "eval");
    }

    private static RedisNoScriptException noScript() {
        return new RedisNoScriptException("NOSCRIPT No matching script. Please use EVAL.");
    }
}
