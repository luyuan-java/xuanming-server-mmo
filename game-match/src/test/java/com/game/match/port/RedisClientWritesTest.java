package com.game.match.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.ObjectParams;
import org.redisson.api.options.OptionalOptions;
import org.redisson.api.options.OptionalParams;
import org.redisson.client.RedisException;
import org.redisson.client.RedisNoScriptException;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.misc.CompletableFutureWrapper;

/**
 * {@link RedisClientWrites#evalAsync} 的发出链（不连 Redis：{@link RedissonClient} 与脚本句柄是记录调用、按编排应答的替身；单调时钟是
 * 只在桩里推进的计数器，不读真实时间）。钉 mmorpg client-request-ordering.md §6.1 的 NOSCRIPT 一条：Redisson 3.50 的 {@code evalAsync}
 * 遇 NOSCRIPT 会自己 SCRIPT LOAD 再发 EVALSHA，两步各用一份完整超时、中间不看截止，真正执行写的那一条可能在截止之后才发出（越过 gate 放行
 * 同一会话下一条的时刻）。所以客户端写自己发 EVALSHA：遇 NOSCRIPT 先查截止、没过才加载，加载之后再查一次才重发；三次发出都不重发、
 * 超时各按那一刻的剩余现取；其他错误原样失败、不加载不再发。另钉一条：Redisson 放弃等连接由时间轮触发、最多晚一个刻度（上界 100 ms），
 * 所以超时 = 剩余 − 一个刻度，剩余不足一个刻度就不发——否则 match 在截止回包、gate 放行下一条之后，一个刻度之内拿到连接的写照样发出。
 * 各存储「走的是这条链」由各自的存储测试钉。
 */
class RedisClientWritesTest {

    private static final String LUA = "return redis.call('SET', KEYS[1], ARGV[1])";
    /** 第一个键决定 SCRIPT LOAD 发到哪个节点（同 EVALSHA 的路由）。 */
    private static final List<Object> KEYS = List.of("game:{match}:ticket:1001", "game:{match}:queue:3:0");
    /** 配置的命令超时故意远大于用例的剩余预算：三次发出的超时都等于各自那一刻的剩余，「现取」与「沿用第一次」才分得开。 */
    private static final Duration LARGE_COMMAND_TIMEOUT = Duration.ofSeconds(120);

    /** 单调时钟替身（纳秒）：只由桩按编排推进（模拟命令在路上花掉的时间）。 */
    private final AtomicLong clock = new AtomicLong();
    private final LongSupplier nanoClock = clock::get;
    private final RedissonClient redis = mock(RedissonClient.class);
    /** 每次发出现取的句柄参数（{@code getScript(OptionalOptions)}），按发出顺序。 */
    private final List<OptionalOptions> handles = new ArrayList<>();

    @Test
    void 截止已过_什么都不发_按依赖故障失败() {
        CompletableFuture<Long> reply = RedisClientWrites.evalAsync(nanoClock, redis, StringCodec.INSTANCE, Deadline.after(0), LARGE_COMMAND_TIMEOUT,
                LUA, RScript.ReturnType.INTEGER, KEYS, "v");

        assertThatThrownBy(reply::join).cause().isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("没有发出");
        verifyNoInteractions(redis);
    }

    /**
     * 还没到截止、但剩余不足一个定时器刻度（这里 ≤ 50 ms，刻度上界 100 ms）：这时交给 Redisson，它放弃等连接的时刻可能晚于截止，
     * 截止之后才拿到连接的写照样发出——那时 match 已经回包、gate 已经放行同一会话的下一条。所以不发，按依赖故障失败。
     */
    @Test
    void 剩余不足一个定时器刻度_什么都不发_按依赖故障失败() {
        CompletableFuture<Long> reply = RedisClientWrites.evalAsync(nanoClock, redis, StringCodec.INSTANCE, Deadline.after(50), LARGE_COMMAND_TIMEOUT,
                LUA, RScript.ReturnType.INTEGER, KEYS, "v");

        assertThatThrownBy(reply::join).cause().isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("没有发出");
        verifyNoInteractions(redis);
    }

    /**
     * 第一次 EVALSHA 在路上花了 50 s 才回 NOSCRIPT，SCRIPT LOAD 又花了 5 s：后两次发出的超时必须按那一刻的剩余现取。
     * 沿用第一次取下的超时（Redisson 自动回退的做法），截止前一刻排上的重发可以在截止之后才真正发出。
     */
    @Test
    void NOSCRIPT时截止未过_加载到第一个键所在节点_再用当时的剩余预算作超时重发一次() {
        Recorder script = script(new Step(50_000, noScript()), new Step(5_000, sha1(LUA)), new Step(0, 1L));

        Long reply = RedisClientWrites.<Long>evalAsync(nanoClock, redis, StringCodec.INSTANCE, Deadline.after(60_000), LARGE_COMMAND_TIMEOUT, LUA,
                RScript.ReturnType.INTEGER, KEYS, "v").join();

        assertThat(reply).isEqualTo(1L);
        assertThat(script.methods).containsExactly("evalShaAsync", "scriptLoadAsync", "evalShaAsync");
        assertThat(script.args.get(0)[1]).as("按脚本正文的 SHA-1 发 EVALSHA（与 Redis 的算法一致）").isEqualTo(sha1(LUA));
        assertThat(script.args.get(1)).as("加载到执行这条写的节点").containsExactly(KEYS.get(0), LUA);
        assertThat(script.args.get(2)[1]).isEqualTo(sha1(LUA));
        assertThat(script.args.get(2)[3]).isEqualTo(KEYS);
        assertThat(handles).hasSize(3).allSatisfy(options -> {
            assertThat(((ObjectParams) options).getRetryAttempts()).as("三次发出都不重发").isZero();
            assertThat(((OptionalParams) options).getCodec()).isSameAs(StringCodec.INSTANCE);
        });
        int first = timeoutOf(0);
        // 上界 59 900：剩余至多 60 s，再扣一个定时器刻度（100 ms），放弃等连接才必定落在截止之前；不扣刻度的实现会取到 59 999
        assertThat(first).as("第一次发出：剩余约 60 s（小于配置的 120 s）扣掉一个定时器刻度").isBetween(59_000, 59_900);
        assertThat(timeoutOf(1)).as("SCRIPT LOAD：按此刻的剩余，少了第一次在路上的 50 s").isEqualTo(first - 50_000);
        assertThat(timeoutOf(2)).as("重发：再少 SCRIPT LOAD 的 5 s").isEqualTo(first - 55_000);
        verify(redis, never()).getScript(any(Codec.class));
    }

    /** NOSCRIPT 时脚本没有执行：截止已过就到此为止，按依赖故障失败（调用方当「没有提交」）。 */
    @Test
    void NOSCRIPT时截止已过_不加载也不再发_按依赖故障失败() {
        Recorder script = script(new Step(61_000, noScript()));

        CompletableFuture<Long> reply = RedisClientWrites.evalAsync(nanoClock, redis, StringCodec.INSTANCE, Deadline.after(60_000), LARGE_COMMAND_TIMEOUT,
                LUA, RScript.ReturnType.INTEGER, KEYS, "v");

        assertThatThrownBy(reply::join).cause().isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("NOSCRIPT")
                .hasCauseInstanceOf(RedisNoScriptException.class);
        assertThat(script.methods).as("只有第一次发出").containsExactly("evalShaAsync");
        assertThat(handles).hasSize(1);
    }

    /** 回 NOSCRIPT 时还没到截止、但剩余已不足一个定时器刻度：同样不加载、不再发（加载之后重发前的检查是同一条规则）。 */
    @Test
    void NOSCRIPT时剩余不足一个定时器刻度_不加载也不再发_按依赖故障失败() {
        // 预算 60 s，第一次发出在路上花了 59.95 s：剩余约 50 ms，还没到截止
        Recorder script = script(new Step(59_950, noScript()));

        CompletableFuture<Long> reply = RedisClientWrites.evalAsync(nanoClock, redis, StringCodec.INSTANCE, Deadline.after(60_000), LARGE_COMMAND_TIMEOUT,
                LUA, RScript.ReturnType.INTEGER, KEYS, "v");

        assertThatThrownBy(reply::join).cause().isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("NOSCRIPT")
                .hasCauseInstanceOf(RedisNoScriptException.class);
        assertThat(script.methods).as("只有第一次发出").containsExactly("evalShaAsync");
        assertThat(handles).hasSize(1);
    }

    @Test
    void 加载之后截止已过_不再发_按依赖故障失败() {
        Recorder script = script(new Step(10_000, noScript()), new Step(51_000, sha1(LUA)));

        CompletableFuture<Long> reply = RedisClientWrites.evalAsync(nanoClock, redis, StringCodec.INSTANCE, Deadline.after(60_000), LARGE_COMMAND_TIMEOUT,
                LUA, RScript.ReturnType.INTEGER, KEYS, "v");

        assertThatThrownBy(reply::join).cause().isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("重新加载脚本之后")
                .hasCauseInstanceOf(RedisNoScriptException.class);
        assertThat(script.methods).as("SCRIPT LOAD 不改业务数据；真正的写没有再发").containsExactly("evalShaAsync", "scriptLoadAsync");
    }

    @Test
    void 其他错误原样失败_不加载也不再发() {
        RedisException boom = new RedisException("ERR boom");
        Recorder script = script(new Step(0, boom));

        CompletableFuture<Long> reply = RedisClientWrites.evalAsync(nanoClock, redis, StringCodec.INSTANCE, Deadline.after(60_000), LARGE_COMMAND_TIMEOUT,
                LUA, RScript.ReturnType.INTEGER, KEYS, "v");

        assertThatThrownBy(reply::join).hasCauseReference(boom);
        assertThat(script.methods).containsExactly("evalShaAsync");
    }

    /** 加载之后恰好又切主、重发又遇 NOSCRIPT：就此失败，不打转（每多转一圈都是又一次可能越过截止的发出）。 */
    @Test
    void 重发又遇NOSCRIPT_就此失败_不再加载() {
        RedisNoScriptException again = noScript();
        Recorder script = script(new Step(0, noScript()), new Step(0, sha1(LUA)), new Step(0, again));

        CompletableFuture<Long> reply = RedisClientWrites.evalAsync(nanoClock, redis, StringCodec.INSTANCE, Deadline.after(60_000), LARGE_COMMAND_TIMEOUT,
                LUA, RScript.ReturnType.INTEGER, KEYS, "v");

        assertThatThrownBy(reply::join).hasCauseReference(again);
        assertThat(script.methods).containsExactly("evalShaAsync", "scriptLoadAsync", "evalShaAsync");
    }

    // ================================================================ 记录型桩

    /** 一次发出的编排：桩被调到时先把时钟推进 {@code advanceMs}（这条命令在路上花的时间），再给出应答（值，或异常 = 失败的 future）。 */
    private record Step(long advanceMs, Object reply) {
    }

    /** 脚本句柄：每次 {@code getScript(OptionalOptions)} 记下句柄参数、回同一个记录型桩。 */
    private Recorder script(Step... steps) {
        Recorder recorder = new Recorder(steps);
        RScript script = mock(RScript.class, recorder);
        when(redis.getScript(any(OptionalOptions.class))).thenAnswer(invocation -> {
            handles.add(invocation.getArgument(0));
            return script;
        });
        return recorder;
    }

    private int timeoutOf(int index) {
        return ((ObjectParams) handles.get(index)).getTimeout();
    }

    /** 按编排应答 EVALSHA 与 SCRIPT LOAD；记下每次调用的方法名与实参（可变参数已展开）。别的口回 null（被测代码会以「没有拿到 future」失败）。 */
    private final class Recorder implements Answer<Object> {
        final List<String> methods = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        private final Deque<Step> steps;

        Recorder(Step... steps) {
            this.steps = new ArrayDeque<>(List.of(steps));
        }

        @Override
        public Object answer(InvocationOnMock invocation) {
            String name = invocation.getMethod().getName();
            methods.add(name);
            args.add(invocation.getArguments());
            if (!name.equals("evalShaAsync") && !name.equals("scriptLoadAsync")) {
                return null;
            }
            if (steps.isEmpty()) {
                throw new AssertionError("未编排的发出: " + name);
            }
            Step step = steps.removeFirst();
            clock.addAndGet(TimeUnit.MILLISECONDS.toNanos(step.advanceMs()));
            return future(step.reply());
        }
    }

    private static RFuture<Object> future(Object reply) {
        CompletableFuture<Object> f = new CompletableFuture<>();
        if (reply instanceof Throwable error) {
            f.completeExceptionally(error);
        } else {
            f.complete(reply);
        }
        return new CompletableFutureWrapper<Object>(f);
    }

    private static RedisNoScriptException noScript() {
        return new RedisNoScriptException("NOSCRIPT No matching script. Please use EVAL.");
    }

    private static String sha1(String lua) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(lua.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JDK 必须提供 Redis 脚本缓存使用的 SHA-1", impossible);
        }
    }
}
