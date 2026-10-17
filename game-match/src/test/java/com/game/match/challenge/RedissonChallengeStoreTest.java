package com.game.match.challenge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.challenge.ChallengeStore.ChallengeRecord;
import com.game.match.challenge.ChallengeStore.ConsumeResult;
import com.game.match.challenge.ChallengeStore.InviteResult;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.ObjectParams;
import org.redisson.api.options.OptionalOptions;
import org.redisson.api.options.OptionalParams;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.misc.CompletableFutureWrapper;

/**
 * {@link RedissonChallengeStore} 发出写的方式（不连 Redis：{@link RedissonClient} 是记录调用、按序回放应答的替身）。只钉 mmorpg
 * client-request-ordering.md §6.1 那一条：发起（152）与消费（151）是客户端请求的提交点——截止已过不发、不重发、Redisson 的超时（含等连接）
 * = min(配置的命令超时, 剩余预算)；
 * 清理是补偿，不看截止、照旧走带全局重发的句柄。脚本本身的行为由 {@code ChallengeStoreIntegrationTest} 在真 Redis 上钉。
 */
class RedissonChallengeStoreTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));
    private static final long CHALLENGE = 5_001;
    private static final long CHALLENGER = 1_001;
    private static final long TARGET = 2_002;
    /** 被测存储配置的命令超时：故意远大于用例的剩余预算，「封顶到剩余」与「照抄配置值 / 写死 2 s」才分得开。 */
    private static final Duration LARGE_COMMAND_TIMEOUT = Duration.ofSeconds(60);

    /** 一次脚本调用（只记脚本，够判断发的是哪一段）。 */
    private final List<String> evals = new CopyOnWriteArrayList<>();
    private final Deque<Object> replies = new ArrayDeque<>();
    private final RedissonClient redis = mock(RedissonClient.class);
    /** 走全局句柄（{@code getScript(Codec)}：全局超时、带重发）的调用。 */
    private final List<Codec> codecs = new CopyOnWriteArrayList<>();
    /** 按客户端写发出（{@code getScript(OptionalOptions)}）的调用。 */
    private final List<OptionalOptions> clientWrites = new CopyOnWriteArrayList<>();

    private final RedissonChallengeStore store;

    RedissonChallengeStoreTest() {
        RScript script = mock(RScript.class, invocation -> answerEval(invocation.getMethod().getName(), invocation.getRawArguments()));
        when(redis.getScript(any(Codec.class))).thenAnswer(invocation -> {
            codecs.add(invocation.getArgument(0));
            return script;
        });
        when(redis.getScript(any(OptionalOptions.class))).thenAnswer(invocation -> {
            clientWrites.add(invocation.getArgument(0));
            return script;
        });
        store = new RedissonChallengeStore(redis, REDIS_KEYS, LARGE_COMMAND_TIMEOUT);
    }

    private RFuture<Object> answerEval(String method, Object[] raw) {
        if (!method.equals("evalAsync") && !method.equals("evalShaAsync")) {
            return null;
        }
        evals.add((String) raw[1]);
        if (replies.isEmpty()) {
            throw new AssertionError("没有为这次脚本调用准备应答: " + raw[1]);
        }
        return new CompletableFutureWrapper<>(replies.poll());
    }

    private static String sha1(String lua) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(lua.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JDK 必须提供 Redis 脚本缓存使用的 SHA-1", impossible);
        }
    }

    private RedissonChallengeStoreTest reply(Object value) {
        replies.add(value);
        return this;
    }

    private RedissonChallengeStoreTest replyList(Object... items) {
        return reply(new ArrayList<>(Arrays.asList(items)));
    }

    @Test
    void 发起与消费_按客户端写发出_不重发_Redisson超时不超过剩余预算() {
        replyList("ok", "1700000060000").replyList("ok", "1001", "2002", "7", "1700000060000", "1700000001000");
        // 剩余 1.5 s：小于配置的 60 s，也小于全局缺省的 2 s——写死 2 s 或照抄配置值的实现都过不了下面的上界
        Deadline d = Deadline.after(1_500);

        assertThat(store.invite(CHALLENGE, CHALLENGER, TARGET, 7, 60_000, d)).isEqualTo(new InviteResult.Created(1_700_000_060_000L));
        assertThat(store.consume(CHALLENGE, TARGET, "nonce-1", d))
                .isEqualTo(new ConsumeResult.Consumed(new ChallengeRecord(CHALLENGER, TARGET, 7, 1_700_000_060_000L), 1_700_000_001_000L));

        assertThat(evals).as("客户端写按原脚本的 SHA-1 发 EVALSHA，补偿仍发 EVAL").containsExactly(sha1(ChallengeScripts.INVITE), sha1(ChallengeScripts.CONSUME));
        assertThat(codecs).as("一条都不走带全局重发的句柄").isEmpty();
        assertThat(clientWrites).hasSize(2).allSatisfy(options -> {
            ObjectParams params = (ObjectParams) options;
            // 全局 retry-attempts = 1：截止前发出的发起 / 消费会在约 4 s 后被原样重发，那时 gate 已放行同一会话的下一条
            assertThat(params.getRetryAttempts()).as("不重发").isZero();
            // 不重发时 Redisson 用这个超时限制等连接：超过剩余预算，截止前一刻排上的写就可能在截止之后才发出
            assertThat(params.getTimeout()).as("超时封顶到剩余预算").isBetween(1, 1_500);
            assertThat(((OptionalParams) options).getCodec()).as("编解码照旧是 StringCodec").isSameAs(StringCodec.INSTANCE);
        });
    }

    @Test
    void 剩余预算多于配置的命令超时_发起的Redisson超时取配置值_不拖到截止() {
        // 同一个超时也是等响应的超时：剩余还多时照旧按配置值判超时，失败之后调用方还有预算收尾（发起结局不明时的清理）
        RedissonChallengeStore configured = new RedissonChallengeStore(redis, REDIS_KEYS, Duration.ofMillis(700));
        replyList("ok", "1700000060000");

        assertThat(configured.invite(CHALLENGE, CHALLENGER, TARGET, 7, 60_000, Deadline.after(60_000)))
                .isEqualTo(new InviteResult.Created(1_700_000_060_000L));

        assertThat(clientWrites).hasSize(1);
        assertThat(((ObjectParams) clientWrites.get(0)).getTimeout()).as("取配置的 700 ms，不是剩余的约 60 s").isEqualTo(700);
    }

    @Test
    void 截止已过_发起与消费都不发_什么都没写_清理是补偿照发() {
        Deadline expired = Deadline.after(0);

        // 原先不查截止：剩余预算为 0 也照发脚本（只是不再等结果），gate 放行下一条之后才在目标身上占坑 / 把邀请作废
        assertThatThrownBy(() -> store.invite(CHALLENGE, CHALLENGER, TARGET, 7, 60_000, expired)).isInstanceOf(Deadline.DependencyException.class)
                .hasMessageContaining("没有发出");
        assertThatThrownBy(() -> store.consume(CHALLENGE, TARGET, "nonce-1", expired)).isInstanceOf(Deadline.DependencyException.class)
                .hasMessageContaining("没有发出");
        assertThat(evals).as("一条都没发").isEmpty();
        assertThat(clientWrites).isEmpty();

        // 清理（发起后推送失败 / 结局不明时的补偿）不受请求截止约束：照发，按 challenge_id 条件摘占坑，迟到无害
        reply(1L);
        store.delete(CHALLENGE, TARGET, expired);

        assertThat(evals).containsExactly(ChallengeScripts.DELETE);
        assertThat(codecs).containsExactly(StringCodec.INSTANCE);
    }
}
