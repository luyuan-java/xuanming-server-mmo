package com.game.team.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.common.deadline.Deadline.DependencyException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.OptionalOptions;
import org.redisson.api.options.OptionalParams;
import org.redisson.client.RedisException;
import org.redisson.client.RedisNoScriptException;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.Codec;
import org.redisson.misc.CompletableFutureWrapper;

/**
 * {@link RedissonTeamRedis} 的句柄选择与写的发出（不连 Redis；客户端请求按会话保序，mmorpg client-request-ordering.md §6.1）：写脚本走不重发、
 * Redisson 超时取剩余预算的句柄——全局 {@code retry-attempts = 1} 会在 2 s 响应超时后原样重发 EVAL，截止前发出的写落到 gate 放行的
 * 下一条之后；NOSCRIPT 之后的重新加载与再发出由 {@link RedissonTeamRedis} 自己做、每一步都重查截止（Redisson 的自动回退沿用第一次的超时、
 * 不看截止）。读脚本仍走全局配置的句柄。真脚本上的行为见 {@code TeamScriptIntegrationTest}。
 */
class RedissonTeamRedisTest {

    private static final List<Object> KEYS = List.of("{team}:rec:1", "{team}:info:1");

    private final RedissonClient client = mock(RedissonClient.class);

    RedissonTeamRedisTest() {
        when(client.getScript(any(Codec.class))).thenReturn(mock(RScript.class));
    }

    @Test
    void 写脚本走不重发的句柄_Redisson超时取剩余预算() {
        Recorder script = writeHandle(1L);
        Object reply = new RedissonTeamRedis(client).evalWrite(TeamScript.COMMIT, KEYS, List.of(new byte[] {1}), Deadline.after(1_200))
                .toCompletableFuture().join();

        assertThat(reply).isEqualTo(1L);
        assertThat(script.methods).as("按 SHA 发 EVALSHA，一次就够").containsExactly("evalShaAsync");
        assertThat(script.args.get(0)[1]).as("SHA-1 与 Redis 的算法一致").isEqualTo(sha1(TeamScript.COMMIT.lua()));
        assertThat(script.args.get(0)[3]).isEqualTo(KEYS);
        ArgumentCaptor<OptionalOptions> captured = ArgumentCaptor.forClass(OptionalOptions.class);
        verify(client).getScript(captured.capture());
        OptionalParams options = (OptionalParams) captured.getValue();
        assertThat(options.getRetryAttempts()).as("响应超时后不得原样重发").isZero();
        assertThat(options.getTimeout()).as("等连接与等响应都不越过截止（全局是 2 s）").isBetween(1, 1_200);
        assertThat(options.getCodec()).as("pb 是任意字节").isSameAs(ByteArrayCodec.INSTANCE);
        verify(client, never()).getScript(any(Codec.class));
    }

    /**
     * NOSCRIPT（Redis 重启、主从切换、SCRIPT FLUSH 之后）：先 SCRIPT LOAD 到同一节点（按第一个键路由），再重新发出；三次发出各自重新取句柄，
     * 都不重发、超时都不超过当时的剩余预算。交给 Redisson 自动回退的话，后两步沿用第一次的超时、各自重新计时，可以越过截止好几秒。
     */
    @Test
    void 写脚本遇到NOSCRIPT_重新加载后再发出_每一步都不重发且不越过截止() {
        Recorder script = writeHandle(noScript(), 1L);
        Object reply = new RedissonTeamRedis(client).evalWrite(TeamScript.COMMIT, KEYS, List.of(new byte[] {1}), Deadline.after(60_000))
                .toCompletableFuture().join();

        assertThat(reply).isEqualTo(1L);
        assertThat(script.methods).containsExactly("evalShaAsync", "scriptLoadAsync", "evalShaAsync");
        assertThat(script.args.get(1)).as("加载到执行这条写的节点").containsExactly(KEYS.get(0), TeamScript.COMMIT.lua());
        assertThat(script.args.get(2)[1]).isEqualTo(sha1(TeamScript.COMMIT.lua()));
        ArgumentCaptor<OptionalOptions> captured = ArgumentCaptor.forClass(OptionalOptions.class);
        verify(client, times(3)).getScript(captured.capture());
        assertThat(captured.getAllValues()).allSatisfy(o -> {
            OptionalParams options = (OptionalParams) o;
            assertThat(options.getRetryAttempts()).isZero();
            assertThat(options.getTimeout()).isBetween(1, 60_000);
        });
    }

    /** NOSCRIPT 时脚本没有执行；截止已过就到此为止——不加载、不再发出，按依赖故障失败（调用方当「没有提交」）。 */
    @Test
    void NOSCRIPT时截止已过_不重新加载也不再发出_按依赖故障失败() {
        Recorder script = writeHandle(noScript());
        CompletableFuture<Object> reply = new RedissonTeamRedis(client)
                .evalWrite(TeamScript.COMMIT, KEYS, List.of(new byte[] {1}), Deadline.after(0)).toCompletableFuture();

        assertThat(reply).isCompletedExceptionally();
        assertThatThrownBy(reply::join).cause().isInstanceOf(DependencyException.class).hasMessageContaining("NOSCRIPT");
        assertThat(script.methods).as("只有第一次发出").containsExactly("evalShaAsync");
    }

    @Test
    void 写脚本遇到其他错误_原样失败_不重新加载也不再发出() {
        RedisException boom = new RedisException("ERR boom");
        Recorder script = writeHandle(boom);
        CompletableFuture<Object> reply = new RedissonTeamRedis(client)
                .evalWrite(TeamScript.COMMIT, KEYS, List.of(new byte[] {1}), Deadline.after(60_000)).toCompletableFuture();

        assertThatThrownBy(reply::join).hasCauseReference(boom);
        assertThat(script.methods).containsExactly("evalShaAsync");
    }

    @Test
    void 剩余预算不足1毫秒时Redisson超时取1毫秒_不退回全局配置() {
        OptionalParams options = (OptionalParams) RedissonTeamRedis.writeOptions(Deadline.after(0));
        assertThat(options.getTimeout()).as("0 会被 Redisson 当成「没设」而用全局 2 s").isEqualTo(1);
        assertThat(options.getRetryAttempts()).isZero();
    }

    @Test
    void 读脚本仍走全局配置的句柄() {
        new RedissonTeamRedis(client).eval(TeamScript.READ, List.of("k1", "k2"), List.of());

        verify(client).getScript(ByteArrayCodec.INSTANCE);
        verify(client, never()).getScript(any(OptionalOptions.class));
    }

    // ================================================================ 记录型桩

    /** 写句柄：按顺序应答 EVALSHA（值或异常），SCRIPT LOAD 回脚本的 SHA；记下每次调用的方法名与实参（可变参数已展开）。 */
    private Recorder writeHandle(Object... evalShaReplies) {
        Recorder recorder = new Recorder(evalShaReplies);
        when(client.getScript(any(OptionalOptions.class))).thenReturn(mock(RScript.class, recorder));
        return recorder;
    }

    private static final class Recorder implements Answer<Object> {
        final List<String> methods = new ArrayList<>();
        final List<Object[]> args = new ArrayList<>();
        private final Deque<Object> evalShaReplies;

        Recorder(Object... evalShaReplies) {
            this.evalShaReplies = new ArrayDeque<>(List.of(evalShaReplies));
        }

        @Override
        public Object answer(InvocationOnMock invocation) {
            String name = invocation.getMethod().getName();
            Object[] arguments = invocation.getArguments();
            methods.add(name);
            args.add(arguments);
            return switch (name) {
                case "evalShaAsync" -> future(evalShaReplies.isEmpty() ? new AssertionError("未编排的 EVALSHA") : evalShaReplies.removeFirst());
                case "scriptLoadAsync" -> future(sha1((String) arguments[arguments.length - 1]));
                default -> null;
            };
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
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
