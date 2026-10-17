package com.game.match.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.common.GameNamespace;
import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.team.GroupTickets;
import com.game.match.ticket.TicketStore.GroupMember;
import com.game.match.ticket.TicketStore.HealMode;
import com.game.match.ticket.TicketStore.JoinResult;
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
 * {@link RedissonTicketStore} 发出写的方式（不连 Redis：{@link RedissonClient} 是记录调用、按序回放应答的替身）。只钉 mmorpg
 * client-request-ordering.md §6.1 那一条：客户端请求路径上的写（157 建票 / 入队 / 自愈、148 取消、211 整队开战的整组建票）按客户端写发出——
 * 截止已过不发、不重发、Redisson 的超时（含等连接）= min(配置的命令超时, 剩余预算 − 一个定时器刻度)；补偿与后台路径的写照旧走带全局重发的句柄。
 * NOSCRIPT 之后的加载与重发由 {@code RedisClientWritesTest} 钉；脚本本身的行为由 {@code RedissonTicketStoreIntegrationTest} 在真 Redis 上钉。
 */
class RedissonTicketStoreTest {

    private static final RedisKeys REDIS_KEYS = new RedisKeys(GameNamespace.of("game"));
    private static final long PLAYER = 1001;
    private static final QueueRef Q_1V1 = new QueueRef(3, 0);
    private static final long QUEUED_TTL_MS = 21_600_000;
    /**
     * 被测存储配置的命令超时（{@code game.redis.timeout-ms}）：故意远大于各用例的剩余预算，「封顶到剩余」与「照抄配置值 / 写死 2 s」才分得开。
     * 「剩余多于配置值时取配置值」另由 {@link #剩余预算多于配置的命令超时_客户端写的Redisson超时取配置值_不拖到截止} 用一个小配置值的存储钉。
     */
    private static final Duration LARGE_COMMAND_TIMEOUT = Duration.ofSeconds(60);

    /** 一次脚本调用（只记脚本，够判断发的是哪一段）。 */
    private final List<String> evals = new CopyOnWriteArrayList<>();
    private final Deque<Object> replies = new ArrayDeque<>();
    private final RedissonClient redis = mock(RedissonClient.class);
    /** 走全局句柄（{@code getScript(Codec)}：全局超时、带重发）的调用。 */
    private final List<Codec> codecs = new CopyOnWriteArrayList<>();
    /** 按客户端写发出（{@code getScript(OptionalOptions)}）的调用。 */
    private final List<OptionalOptions> clientWrites = new CopyOnWriteArrayList<>();

    private final RedissonTicketStore store;

    RedissonTicketStoreTest() {
        RScript script = mock(RScript.class, invocation -> answerEval(invocation.getMethod().getName(), invocation.getRawArguments()));
        when(redis.getScript(any(Codec.class))).thenAnswer(invocation -> {
            codecs.add(invocation.getArgument(0));
            return script;
        });
        when(redis.getScript(any(OptionalOptions.class))).thenAnswer(invocation -> {
            clientWrites.add(invocation.getArgument(0));
            return script;
        });
        store = new RedissonTicketStore(redis, REDIS_KEYS, LARGE_COMMAND_TIMEOUT);
    }

    private RFuture<Object> answerEval(String method, Object[] raw) {
        if (!method.equals("evalAsync") && !method.equals("evalShaAsync")) {
            return null;
        }
        evals.add((String) raw[1]);
        if (replies.isEmpty()) {
            throw new AssertionError("没有为这次脚本调用准备应答: " + raw[1]);
        }
        Object reply = replies.poll();
        if (reply instanceof Throwable error) {
            // 编排成失败的应答（Redis 报错 / 响应超时）
            return new CompletableFutureWrapper<>(error);
        }
        return new CompletableFutureWrapper<>(reply);
    }

    private static String sha1(String lua) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(lua.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError("JDK 必须提供 Redis 脚本缓存使用的 SHA-1", impossible);
        }
    }

    private RedissonTicketStoreTest reply(Object value) {
        replies.add(value);
        return this;
    }

    private RedissonTicketStoreTest replyList(Object... items) {
        return reply(new ArrayList<>(Arrays.asList(items)));
    }

    private static Deadline d() {
        return Deadline.after(2_000);
    }

    private static Ticket readyTicket() {
        return new Ticket("t-old", 3, 0, TicketState.READY, 1_700_000_000_000L, 1, "", 150_000, 0, 77, 0);
    }

    /** 整队开战的名单（两人、各带本次票号）。 */
    private static List<GroupMember> group() {
        return List.of(new GroupMember(PLAYER, "g-1", 1), new GroupMember(PLAYER + 1, "g-2", 1));
    }

    @Test
    void 客户端路径的四个写_按客户端写发出_不重发_Redisson超时不超过剩余预算() {
        replyList(0L, "1700000000001").replyList(0L, "1700000000002").reply(1L).reply(1L);
        // 剩余 1.5 s：小于配置的 60 s，也小于全局缺省的 2 s——写死 2 s 或照抄配置值的实现都过不了下面的上界
        Deadline d = Deadline.after(1_500);

        assertThat(store.enqueue(PLAYER, "t-1", Q_1V1, 1, 150_000, QUEUED_TTL_MS, d)).isInstanceOf(JoinResult.Created.class);
        assertThat(store.createMatched(PLAYER, "t-2", 1, 7, 1, 150_000, 42_000, d)).isInstanceOf(JoinResult.Created.class);
        assertThat(store.cancel(PLAYER, "t-1", Q_1V1, d)).isTrue();
        assertThat(store.heal(PLAYER, readyTicket(), HealMode.READY, d)).isTrue();

        assertThat(evals).as("客户端写按原脚本的 SHA-1 发 EVALSHA，补偿仍发 EVAL")
                .containsExactly(sha1(TicketScripts.JOIN), sha1(TicketScripts.JOIN), sha1(TicketScripts.CANCEL), sha1(TicketScripts.HEAL));
        assertThat(codecs).as("一条都不走带全局重发的句柄").isEmpty();
        assertThat(clientWrites).hasSize(4).allSatisfy(options -> {
            ObjectParams params = (ObjectParams) options;
            // 全局 retry-attempts = 1：截止前发出的写会在约 4 s 后被原样重发，那时 gate 已放行同一会话的下一条
            assertThat(params.getRetryAttempts()).as("不重发").isZero();
            // 不重发时 Redisson 用这个超时限制等连接：超过剩余预算，截止前一刻排上的写就可能在截止之后才发出
            assertThat(params.getTimeout()).as("超时封顶到剩余预算").isBetween(1, 1_500);
            assertThat(((OptionalParams) options).getCodec()).as("编解码照旧是 StringCodec").isSameAs(StringCodec.INSTANCE);
        });
    }

    @Test
    void 剩余预算多于配置的命令超时_客户端写的Redisson超时取配置值_不拖到截止() {
        // 同一个超时也是等响应的超时：直接取剩余预算的话，建票响应慢时要等到截止才失败，157 用同一个截止的回滚（按票号撤掉可能已建成的票）
        // 就一条也发不出去。剩余还多时照旧按配置值判超时，给回滚留下预算
        RedissonTicketStore configured = new RedissonTicketStore(redis, REDIS_KEYS, Duration.ofMillis(700));
        replyList(0L, "1700000000001").reply(1L);

        assertThat(configured.enqueue(PLAYER, "t-1", Q_1V1, 1, 150_000, QUEUED_TTL_MS, Deadline.after(60_000))).isInstanceOf(JoinResult.Created.class);
        assertThat(configured.cancel(PLAYER, "t-1", Q_1V1, Deadline.after(60_000))).isTrue();

        assertThat(clientWrites).hasSize(2).allSatisfy(options -> {
            assertThat(((ObjectParams) options).getRetryAttempts()).as("不重发").isZero();
            assertThat(((ObjectParams) options).getTimeout()).as("取配置的 700 ms，不是剩余的约 60 s").isEqualTo(700);
        });
    }

    @Test
    void 配置的命令超时必须为正() {
        // OptionalOptions.timeout 取 0 会退回全局超时，等于没封顶
        assertThatThrownBy(() -> new RedissonTicketStore(redis, REDIS_KEYS, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedissonTicketStore(redis, REDIS_KEYS, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 补偿与gather的写_照旧走带全局重发的句柄() {
        reply(1L).reply(1L);

        assertThat(store.delete(new TicketRef(PLAYER, "t-1"), d())).isTrue();
        assertThat(store.markReady(new TicketRef(PLAYER, "t-1"), 77, 60_000, d())).isTrue();

        assertThat(evals).containsExactly(TicketScripts.DEL, TicketScripts.READY);
        assertThat(clientWrites).as("它们按票号 CAS、可重放，重发只会更稳，也不受请求截止约束").isEmpty();
        assertThat(codecs).containsExactly(StringCodec.INSTANCE, StringCodec.INSTANCE);
    }

    @Test
    void 截止已过_客户端路径的写一条都不发_什么都没写() {
        Deadline expired = Deadline.after(0);
        Class<Deadline.DependencyException> failure = Deadline.DependencyException.class;

        assertThatThrownBy(() -> store.enqueue(PLAYER, "t-1", Q_1V1, 1, 150_000, QUEUED_TTL_MS, expired)).isInstanceOf(failure)
                .hasMessageContaining("没有发出");
        assertThatThrownBy(() -> store.createMatched(PLAYER, "t-2", 1, 7, 1, 150_000, 42_000, expired)).isInstanceOf(failure)
                .hasMessageContaining("没有发出");
        assertThatThrownBy(() -> store.cancel(PLAYER, "t-1", Q_1V1, expired)).isInstanceOf(failure).hasMessageContaining("没有发出");
        assertThatThrownBy(() -> store.heal(PLAYER, readyTicket(), HealMode.READY, expired)).isInstanceOf(failure).hasMessageContaining("没有发出");

        verifyNoInteractions(redis);
    }

    @Test
    void 整组建票是211整队开战的提交点_按客户端写发出_不重发_Redisson超时不超过剩余预算() {
        // 原先走带全局重发的句柄：截止前发出的这条约 2.2 s 后被原样重发，既可能越过 gate 放行同一会话的下一条，
        // 也可能落在 GroupTickets 收到依赖故障、当场按票号回滚之后，留下整组 matched 孤儿票
        reply(0L);

        // 剩余 10 s：小于配置的 60 s、大于全局缺省的 2 s。预算放宽到 10 s，慢机器上也走得到发出（不靠真实时间卡点）
        assertThat(store.createGroup(group(), 5, 1, 900, 48_000, Deadline.after(10_000))).as("全员建成").isEmpty();

        assertThat(evals).as("按原脚本的 SHA-1 发 EVALSHA").containsExactly(sha1(TicketScripts.CREATE_GROUP));
        assertThat(codecs).as("不走带全局重发的句柄").isEmpty();
        assertThat(clientWrites).hasSize(1).allSatisfy(options -> {
            assertThat(((ObjectParams) options).getRetryAttempts()).as("不重发").isZero();
            // 上界 9 900 = 剩余至多 10 s 再扣一个定时器刻度（100 ms）：等连接的放弃必定在截止之前触发，挡住照抄配置值与不扣刻度的实现；
            // 下界挡住写死全局 2 s 的实现（只要从建 Deadline 到发出不超过约 7.9 s）
            assertThat(((ObjectParams) options).getTimeout()).as("超时封顶到剩余预算并留出一个刻度").isBetween(2_001, 9_900);
            assertThat(((OptionalParams) options).getCodec()).as("编解码照旧是 StringCodec").isSameAs(StringCodec.INSTANCE);
        });
    }

    @Test
    void 截止已过_整组建票不发_什么都没写() {
        assertThatThrownBy(() -> store.createGroup(group(), 5, 1, 900, 48_000, Deadline.after(0))).isInstanceOf(Deadline.DependencyException.class)
                .hasMessageContaining("没有发出");

        verifyNoInteractions(redis);
    }

    @Test
    void 整组建票结局不明_GroupTickets按票号回滚_建票按客户端写_回滚是补偿照旧带重发() {
        // 建票的 EVALSHA 失败（响应超时：脚本可能已执行）→ 当场按本次票号逐个删。建票不重发，回滚之后不会再冒出一遍建票；
        // 回滚是补偿：按票号 CAS、可重放，带重发只会更稳，迟到也删不到别人的票，所以不改走客户端写
        reply(new IllegalStateException("Redis 响应超时")).reply(1L).reply(1L);

        GroupTickets.Outcome outcome = new GroupTickets(store).create(1, 900, group(), Deadline.after(10_000), "整队开战");

        assertThat(outcome).isInstanceOf(GroupTickets.Outcome.RolledBack.class);
        assertThat(evals).containsExactly(sha1(TicketScripts.CREATE_GROUP), TicketScripts.DEL, TicketScripts.DEL);
        assertThat(clientWrites).as("只有建票按客户端写发出").hasSize(1)
                .allSatisfy(options -> assertThat(((ObjectParams) options).getRetryAttempts()).isZero());
        assertThat(codecs).as("两张票的回滚都走带全局重发的句柄").containsExactly(StringCodec.INSTANCE, StringCodec.INSTANCE);
    }
}
