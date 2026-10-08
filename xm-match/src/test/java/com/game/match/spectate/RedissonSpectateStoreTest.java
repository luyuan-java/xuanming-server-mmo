package com.game.match.spectate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.game.common.deadline.Deadline;
import com.game.discovery.RedisKeys;
import com.game.match.proto.BattlePlacement;
import com.game.match.spectate.SpectateStore.Acquire;
import com.game.match.spectate.SpectateStore.Entry;
import com.game.match.spectate.SpectateStore.Eviction;
import com.game.match.spectate.SpectateStore.Listed;
import com.game.match.spectate.SpectateStore.Pick;
import com.game.match.spectate.SpectateStore.Record;
import com.game.match.spectate.SpectateStore.Scored;
import com.game.match.spectate.SpectateStore.Snapshot;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.redisson.api.BatchResult;
import org.redisson.api.RBatch;
import org.redisson.api.RFuture;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.Codec;
import org.redisson.connection.CRC16;
import org.redisson.misc.CompletableFutureWrapper;

/**
 * {@link RedissonSpectateStore} 的 Java 一侧（不连 Redis：{@link RedissonClient} 是记录调用、按脚本回放应答的替身）：每个方法发的是哪一段脚本、
 * KEYS 全部来自 {@link RedisKeys} 且同槽、ARGV 的写法、READ_WRITE 模式；应答怎么解成接口的返回值；形状不对的应答、Redis 报错、等到截止，
 * 一律是 {@link Deadline.DependencyException}；截止已过不发；两个尽力方法永不抛。脚本本身的行为由 {@code RedissonSpectateStoreIntegrationTest} 在真 Redis 上钉。
 */
class RedissonSpectateStoreTest {

    private static final long PLAYER = Long.MIN_VALUE + 1001; // ≥ 2^63：键与成员必须是无符号十进制
    private static final long BATTLE = Long.MIN_VALUE + 77;
    private static final String PLAYER_TEXT = "9223372036854776809";
    private static final String BATTLE_TEXT = "9223372036854775885";
    private static final String TICKET_KEY = "xm:{match}:ticket:" + PLAYER_TEXT;
    private static final String MARK_KEY = "xm:{match}:watching:" + PLAYER_TEXT;
    private static final String PLACEMENT_KEY = "xm:{match}:battle:" + BATTLE_TEXT;
    private static final String INDEX_KEY = "xm:{match}:watchable";
    private static final String MARK = BATTLE_TEXT + ":0123456789abcdef";

    /** 一次脚本调用。 */
    private record Eval(RScript.Mode mode, String lua, RScript.ReturnType type, List<Object> keys, List<String> args, boolean piped) {
    }

    private final List<Eval> evals = new CopyOnWriteArrayList<>();
    private final Deque<Supplier<RFuture<Object>>> replies = new ArrayDeque<>();
    private final RedissonClient redis = mock(RedissonClient.class);
    private final RBatch pipeline = mock(RBatch.class);
    private final List<Codec> codecs = new CopyOnWriteArrayList<>();
    private volatile RFuture<BatchResult<?>> pipelineResult = new CompletableFutureWrapper<>((BatchResult<?>) null);
    private int pipelinesExecuted;

    private final RedissonSpectateStore store;

    RedissonSpectateStoreTest() {
        RScript script = mock(RScript.class, invocation -> answerEval(invocation.getMethod().getName(), invocation.getRawArguments(), false));
        when(redis.getScript(any(Codec.class))).thenAnswer(invocation -> {
            codecs.add(invocation.getArgument(0));
            return script;
        });
        RScript piped = mock(RScript.class, invocation -> answerEval(invocation.getMethod().getName(), invocation.getRawArguments(), true));
        when(redis.createBatch()).thenReturn(pipeline);
        when(pipeline.getScript(any(Codec.class))).thenAnswer(invocation -> {
            codecs.add(invocation.getArgument(0));
            return piped;
        });
        when(pipeline.executeAsync()).thenAnswer(invocation -> {
            pipelinesExecuted++;
            return pipelineResult;
        });
        store = new RedissonSpectateStore(redis);
    }

    @SuppressWarnings("unchecked")
    private Object answerEval(String method, Object[] raw, boolean piped) {
        if (!method.equals("evalAsync")) {
            return null;
        }
        List<String> args = new ArrayList<>();
        for (Object value : (Object[]) raw[4]) {
            args.add(new String((byte[]) value, StandardCharsets.ISO_8859_1));
        }
        evals.add(new Eval((RScript.Mode) raw[0], (String) raw[1], (RScript.ReturnType) raw[2], List.copyOf((List<Object>) raw[3]), args, piped));
        Supplier<RFuture<Object>> reply = replies.poll();
        if (reply == null) {
            throw new AssertionError("没有为这次脚本调用准备应答: " + evals.get(evals.size() - 1));
        }
        return reply.get();
    }

    // ---------------------------------------------------------------- 准备应答

    private RedissonSpectateStoreTest reply(Object value) {
        replies.add(() -> new CompletableFutureWrapper<>(value));
        return this;
    }

    private RedissonSpectateStoreTest replyList(Object... items) {
        return reply(new ArrayList<>(Arrays.asList(items)));
    }

    private RedissonSpectateStoreTest fail(Throwable error) {
        replies.add(() -> new CompletableFutureWrapper<>(error));
        return this;
    }

    private static byte[] b(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static Deadline d() {
        return Deadline.after(2_000);
    }

    private static BattlePlacement placement(int attempt) {
        return BattlePlacement.newBuilder().setBattleId(BATTLE).setBattleNodeId(1).setBattleInstanceId("inst").setRpcHost("127.0.0.1").setRpcPort(21200)
                .setAttempt(attempt).setMode(3).setBattleConfigId(7).addPlayerNames("甲").setCreatedAtMs(1_900_000_000_123L)
                .setDeadlineMs(1_900_000_300_000L).build();
    }

    private Eval only() {
        assertThat(evals).hasSize(1);
        return evals.get(0);
    }

    private static int slotOf(Object key) {
        String text = key.toString();
        int open = text.indexOf('{');
        int close = open < 0 ? -1 : text.indexOf('}', open + 1);
        String tagged = open >= 0 && close > open + 1 ? text.substring(open + 1, close) : text;
        return CRC16.crc16(tagged.getBytes(StandardCharsets.UTF_8)) % 16384;
    }

    // ================================================================ 发出去的是什么

    @Test
    void 键的写法钉住_与RedisKeys一致() {
        assertThat(RedisKeys.matchTicket(PLAYER)).isEqualTo(TICKET_KEY);
        assertThat(RedisKeys.matchWatching(PLAYER)).isEqualTo(MARK_KEY);
        assertThat(RedisKeys.matchBattlePlacement(BATTLE)).isEqualTo(PLACEMENT_KEY);
        assertThat(RedisKeys.matchWatchable()).isEqualTo(INDEX_KEY);
        assertThat(SpectateRules.member(BATTLE)).isEqualTo(BATTLE_TEXT);
    }

    @Test
    void 观战标记的四个方法_各发哪一段脚本_KEYS与ARGV() {
        replyList(1L, 1L, b(MARK)).reply(0L).reply(1L).replyList(2L, b(MARK));

        Entry entry = store.entry(PLAYER, d());
        Acquire acquired = store.acquire(PLAYER, MARK, d());
        boolean released = store.release(PLAYER, MARK, d());
        Map<Long, String> marks = store.marksOf(List.of(5L, PLAYER, 5L), d());

        assertThat(entry).isEqualTo(new Entry(true, Optional.of(MARK)));
        assertThat(acquired).isEqualTo(Acquire.OK);
        assertThat(released).isTrue();
        assertThat(marks).as("应答里的下标 2 = KEYS 里第 2 把键 = 去重之后的第 2 名玩家").containsExactly(Map.entry(PLAYER, MARK));
        assertThat(evals).containsExactly(
                new Eval(RScript.Mode.READ_WRITE, SpectateScripts.ENTRY, RScript.ReturnType.MULTI, List.of(TICKET_KEY, MARK_KEY), List.of(), false),
                new Eval(RScript.Mode.READ_WRITE, SpectateScripts.ACQUIRE, RScript.ReturnType.INTEGER, List.of(TICKET_KEY, MARK_KEY),
                        List.of(MARK, "360000"), false),
                new Eval(RScript.Mode.READ_WRITE, SpectateScripts.RELEASE, RScript.ReturnType.INTEGER, List.of(MARK_KEY), List.of(MARK), false),
                new Eval(RScript.Mode.READ_WRITE, SpectateScripts.MARKS, RScript.ReturnType.MULTI, List.of("xm:{match}:watching:5", MARK_KEY),
                        List.of(), false));
    }

    @Test
    void 落点与索引的方法_各发哪一段脚本_KEYS与ARGV() {
        BattlePlacement placement = placement(2);
        replyList(1_900_000_000_500L, 1L, 1L, b("2"), placement.toByteArray()) // read
                .replyList(1_900_000_000_600L, b("12345"), b("1900000000001")) // pickRandom
                .reply(1L) // publish
                .replyList(1_900_000_000_700L, b(BATTLE_TEXT), b("1900000000123"), b("junk"), b("1.5")) // list
                .replyList(0L, b(""), b(""), 1L, b("2"), placement.toByteArray()) // readPlacements
                .reply(3L); // sweep

        Snapshot snapshot = store.read(BATTLE, d());
        Pick pick = store.pickRandom(0.25, d());
        boolean published = store.publish(placement, d());
        Listed listed = store.list(20, d());
        Map<Long, Record> records = store.readPlacements(List.of(9L, BATTLE, 9L), d());
        long swept = store.sweep(d());

        assertThat(snapshot).isEqualTo(new Snapshot(true, new Record.Found(placement), 1_900_000_000_500L));
        assertThat(pick).isEqualTo(new Pick.Member("12345", 1_900_000_000_001L, 1_900_000_000_600L));
        assertThat(published).isTrue();
        assertThat(listed).isEqualTo(new Listed(List.of(new Scored(BATTLE_TEXT, 1_900_000_000_123L), new Scored("junk", 1)), 1_900_000_000_700L));
        assertThat(records).containsExactly(Map.entry(9L, new Record.Absent()), Map.entry(BATTLE, new Record.Found(placement)));
        assertThat(swept).isEqualTo(3);
        assertThat(evals).containsExactly(
                new Eval(RScript.Mode.READ_WRITE, SpectateScripts.READ, RScript.ReturnType.MULTI, List.of(INDEX_KEY, PLACEMENT_KEY),
                        List.of(BATTLE_TEXT), false),
                new Eval(RScript.Mode.READ_WRITE, SpectateScripts.PICK, RScript.ReturnType.MULTI, List.of(INDEX_KEY), List.of("0.25", "360000"), false),
                new Eval(RScript.Mode.READ_WRITE, SpectateScripts.PUBLISH, RScript.ReturnType.INTEGER, List.of(INDEX_KEY, PLACEMENT_KEY),
                        List.of("2", BATTLE_TEXT, "1900000000123"), false),
                new Eval(RScript.Mode.READ_WRITE, SpectateScripts.LIST, RScript.ReturnType.MULTI, List.of(INDEX_KEY), List.of("20"), false),
                new Eval(RScript.Mode.READ_WRITE, SpectateScripts.RECORDS, RScript.ReturnType.MULTI, List.of("xm:{match}:battle:9", PLACEMENT_KEY),
                        List.of(), false),
                new Eval(RScript.Mode.READ_WRITE, SpectateScripts.SWEEP, RScript.ReturnType.INTEGER, List.of(INDEX_KEY), List.of("360000"), false));
    }

    @Test
    void 四种剔除_模式_成员与第三个参数_非法成员不带落点键() {
        reply(1L).reply(0L).reply(1L).reply(0L);

        assertThat(store.evict(new Eviction.Invalid("not-a-battle"), d())).isTrue();
        assertThat(store.evict(new Eviction.Missing(BATTLE), d())).isFalse();
        assertThat(store.evict(new Eviction.Dead(BATTLE, 2), d())).isTrue();
        assertThat(store.evict(new Eviction.Stale(BATTLE, 1_899_999_640_000L), d())).isFalse();

        assertThat(evals).extracting(Eval::lua).containsOnly(SpectateScripts.EVICT);
        assertThat(evals).extracting(Eval::type).containsOnly(RScript.ReturnType.INTEGER);
        assertThat(evals).extracting(Eval::keys).containsExactly(List.of(INDEX_KEY), List.of(INDEX_KEY, PLACEMENT_KEY), List.of(INDEX_KEY, PLACEMENT_KEY),
                List.of(INDEX_KEY, PLACEMENT_KEY));
        assertThat(evals).extracting(Eval::args).containsExactly(List.of("invalid", "not-a-battle"), List.of("missing", BATTLE_TEXT),
                List.of("dead", BATTLE_TEXT, "2"), List.of("stale", BATTLE_TEXT, "1899999640000"));
    }

    @Test
    void 每次脚本调用_ByteArray编解码_读写模式_KEYS全部同槽() {
        BattlePlacement placement = placement(1);
        replyList(0L, 0L, b("")).reply(0L).reply(0L).replyList().replyList(1L, 0L, 0L, b(""), b("")).replyList(1L).reply(0L).reply(0L)
                .replyList(1L).replyList(0L, b(""), b("")).reply(0L);

        store.entry(PLAYER, d());
        store.acquire(PLAYER, MARK, d());
        store.release(PLAYER, MARK, d());
        store.marksOf(List.of(PLAYER, 5L), d());
        store.read(BATTLE, d());
        store.pickRandom(0.0, d());
        store.evict(new Eviction.Dead(BATTLE, 1), d());
        store.publish(placement, d());
        store.list(1, d());
        store.readPlacements(List.of(BATTLE), d());
        store.sweep(d());

        assertThat(evals).hasSize(11);
        assertThat(codecs).hasSize(11).allSatisfy(codec -> assertThat(codec).isSameAs(ByteArrayCodec.INSTANCE));
        int slot = slotOf(INDEX_KEY);
        for (Eval eval : evals) {
            assertThat(eval.mode()).as("只读脚本也读主库").isEqualTo(RScript.Mode.READ_WRITE);
            assertThat(eval.keys()).isNotEmpty().allSatisfy(key -> {
                assertThat(key).isInstanceOf(String.class);
                assertThat(key.toString()).startsWith("xm:{match}:");
                assertThat(slotOf(key)).as("多键脚本上 Cluster 必须同槽: %s", key).isEqualTo(slot);
            });
        }
    }

    // ================================================================ 应答怎么解

    @Test
    void 入口读_没有标记是空_空串的标记是有_标记按UTF8解() {
        replyList(0L, 0L, b("")).replyList(0L, 1L, b("")).replyList(1L, 1L, b("脏值:値"));

        assertThat(store.entry(PLAYER, d())).isEqualTo(new Entry(false, Optional.empty()));
        assertThat(store.entry(PLAYER, d())).as("有标记标志说了算，不拿空串当没有").isEqualTo(new Entry(false, Optional.of("")));
        assertThat(store.entry(PLAYER, d())).isEqualTo(new Entry(true, Optional.of("脏值:値")));
    }

    @Test
    void 抢标记的三个返回码_删标记的两个() {
        reply(0L).reply(1L).reply(2L).reply(1L).reply(0L);

        assertThat(store.acquire(PLAYER, MARK, d())).isEqualTo(Acquire.OK);
        assertThat(store.acquire(PLAYER, MARK, d())).isEqualTo(Acquire.QUEUED);
        assertThat(store.acquire(PLAYER, MARK, d())).isEqualTo(Acquire.BUSY);
        assertThat(store.release(PLAYER, "脏值:値", d())).isTrue();
        assertThat(store.release(PLAYER, "", d())).as("空串的脏标记也按原串发出去").isFalse();

        assertThat(evals.get(3).args()).as("标记值按 UTF-8 发").containsExactly(new String(b("脏值:値"), StandardCharsets.ISO_8859_1));
        assertThat(evals.get(4).args()).containsExactly("");
    }

    @Test
    void 读快照_落点的三种状态与损坏的记录() {
        BattlePlacement placement = placement(1);
        replyList(10L, 0L, 0L, b(""), b("")) // 不在
                .replyList(11L, 1L, 2L, b(""), b("")) // 键不是 HASH
                .replyList(12L, 0L, 1L, b("2"), placement.toByteArray()) // a 字段与消息里的 attempt 不一致
                .replyList(13L, 1L, 1L, b("1"), b("")) // 缺 pb
                .replyList(14L, 0L, 1L, b(""), placement.toByteArray()) // 缺 a
                .replyList(15L, 0L, 1L, b("1"), placement.toBuilder().setBattleId(5).build().toByteArray()) // battle_id 与键不符
                .replyList(16L, 1L, 1L, b("1"), placement.toByteArray());

        assertThat(store.read(BATTLE, d())).isEqualTo(new Snapshot(false, new Record.Absent(), 10L));
        Snapshot wrongType = store.read(BATTLE, d());
        assertThat(wrongType.published()).isTrue();
        assertThat(wrongType.record()).isInstanceOf(Record.Corrupt.class);
        assertThat(((Record.Corrupt) wrongType.record()).why()).contains("不是 HASH").contains(BATTLE_TEXT);
        for (int i = 0; i < 4; i++) {
            Snapshot corrupt = store.read(BATTLE, d());
            assertThat(corrupt.record()).as("第 %d 种损坏", i + 1).isInstanceOf(Record.Corrupt.class);
            assertThat(((Record.Corrupt) corrupt.record()).why()).contains(BATTLE_TEXT);
            assertThat(corrupt.redisNowMs()).isEqualTo(12L + i);
        }
        assertThat(store.read(BATTLE, d())).isEqualTo(new Snapshot(true, new Record.Found(placement), 16L));
    }

    @Test
    void 选场_只有now是没有可看的_成员按字节原样_分数向下取整_无穷收到两端() {
        byte[] binary = {(byte) 0xFF, (byte) 0xFE, 'x'};
        replyList(100L).replyList(101L, binary, b("1900000000000.75")).replyList(102L, b("m"), b("inf")).replyList(103L, b("m"), b("-inf"))
                .replyList(104L, b("m"), b("-1.5")).replyList(105L, b("m"), b("1e+30")).reply(1L);

        assertThat(store.pickRandom(0.5, d())).isEqualTo(new Pick.None(100L));
        Pick.Member picked = (Pick.Member) store.pickRandom(0.5, d());
        assertThat(picked.member().getBytes(StandardCharsets.ISO_8859_1)).as("不是合法 UTF-8 的成员也不失真").isEqualTo(binary);
        assertThat(picked.score()).isEqualTo(1_900_000_000_000L);
        assertThat(((Pick.Member) store.pickRandom(0.5, d())).score()).isEqualTo(Long.MAX_VALUE);
        assertThat(((Pick.Member) store.pickRandom(0.5, d())).score()).isEqualTo(Long.MIN_VALUE);
        assertThat(((Pick.Member) store.pickRandom(0.5, d())).score()).as("与整数分界比大小时结论同 Redis 按 double 比的一致").isEqualTo(-2);
        assertThat(((Pick.Member) store.pickRandom(0.5, d())).score()).as("超出 long 的分数收到最大值").isEqualTo(Long.MAX_VALUE);

        store.evict(new Eviction.Invalid(picked.member()), d());
        assertThat(evals.get(6).args().get(1).getBytes(StandardCharsets.ISO_8859_1)).as("读到什么就交回什么：摘的是原来那串字节").isEqualTo(binary);
    }

    @Test
    void 随机数原样写成十进制_科学计数法也照发_越界是调用方的错() {
        replyList(1L).replyList(1L).replyList(1L);

        store.pickRandom(0.0, d());
        store.pickRandom(Math.nextDown(1.0), d());
        store.pickRandom(Double.MIN_VALUE, d());

        assertThat(evals).extracting(eval -> eval.args().get(0)).containsExactly("0.0", "0.9999999999999999", "4.9E-324");
        assertThatThrownBy(() -> store.pickRandom(1.0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.pickRandom(-0.1, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.pickRandom(Double.NaN, d())).isInstanceOf(IllegalArgumentException.class);
        assertThat(evals).as("越界的没有发出去").hasSize(3);
    }

    @Test
    void 调用方给的带大码点的成员按UTF8发出_不被替换成问号() {
        reply(1L);

        store.evict(new Eviction.Invalid("垃圾"), d());

        assertThat(only().args().get(1).getBytes(StandardCharsets.ISO_8859_1)).isEqualTo("垃圾".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 空名单不发命令_回空表_哪怕截止已过() {
        Deadline expired = Deadline.after(0);

        assertThat(store.marksOf(List.of(), expired)).isEmpty();
        assertThat(store.readPlacements(List.of(), expired)).isEmpty();
        store.evictAsync(List.of());

        verifyNoInteractions(redis);
    }

    @Test
    void 索引的大小是一条普通的ZCARD_读的是索引键() {
        @SuppressWarnings("unchecked")
        RScoredSortedSet<Object> index = mock(RScoredSortedSet.class);
        doReturn(index).when(redis).getScoredSortedSet(anyString(), any(Codec.class));
        when(index.sizeAsync()).thenReturn(new CompletableFutureWrapper<>(7), new CompletableFutureWrapper<>(new IllegalStateException("WRONGTYPE")));

        assertThat(store.watchableCount(d())).isEqualTo(7);
        assertThatThrownBy(() -> store.watchableCount(d())).isInstanceOf(Deadline.DependencyException.class);

        org.mockito.Mockito.verify(redis, org.mockito.Mockito.times(2)).getScoredSortedSet(INDEX_KEY, ByteArrayCodec.INSTANCE);
        assertThat(evals).isEmpty();
    }

    // ================================================================ 截止、故障、形状不对的应答

    @Test
    void 截止已过_每个同步方法都不碰Redis_直接抛依赖异常() {
        Deadline expired = Deadline.after(0);
        Class<Deadline.DependencyException> failure = Deadline.DependencyException.class;

        assertThatThrownBy(() -> store.entry(PLAYER, expired)).isInstanceOf(failure).hasMessageContaining("没有发出");
        assertThatThrownBy(() -> store.acquire(PLAYER, MARK, expired)).isInstanceOf(failure).hasMessageContaining("没有发出");
        assertThatThrownBy(() -> store.release(PLAYER, MARK, expired)).isInstanceOf(failure);
        assertThatThrownBy(() -> store.marksOf(List.of(PLAYER), expired)).isInstanceOf(failure);
        assertThatThrownBy(() -> store.read(BATTLE, expired)).isInstanceOf(failure);
        assertThatThrownBy(() -> store.pickRandom(0.5, expired)).isInstanceOf(failure);
        assertThatThrownBy(() -> store.evict(new Eviction.Missing(BATTLE), expired)).isInstanceOf(failure);
        assertThatThrownBy(() -> store.publish(placement(1), expired)).isInstanceOf(failure);
        assertThatThrownBy(() -> store.list(20, expired)).isInstanceOf(failure);
        assertThatThrownBy(() -> store.readPlacements(List.of(BATTLE), expired)).isInstanceOf(failure);
        assertThatThrownBy(() -> store.sweep(expired)).isInstanceOf(failure);
        assertThatThrownBy(() -> store.watchableCount(expired)).isInstanceOf(failure);

        verifyNoInteractions(redis);
    }

    @Test
    void Redis报错_发不出去_空应答_都是依赖异常_原因挂在cause上() {
        IllegalStateException boom = new IllegalStateException("ERR 脚本报错");
        fail(boom);
        replies.add(() -> {
            throw new IllegalStateException("客户端已关闭");
        });
        reply(null);
        replies.add(() -> null);

        assertThatThrownBy(() -> store.list(20, d())).isInstanceOf(Deadline.DependencyException.class).hasCause(boom);
        assertThatThrownBy(() -> store.acquire(PLAYER, MARK, d())).isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("发不出去")
                .hasRootCauseMessage("客户端已关闭");
        assertThatThrownBy(() -> store.sweep(d())).isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("空回复");
        assertThatThrownBy(() -> store.entry(PLAYER, d())).isInstanceOf(Deadline.DependencyException.class).hasMessageContaining("没有返回 future");
    }

    @Test
    void 等到截止还没有应答_抛依赖异常_不早于截止也不无限等() {
        CompletableFuture<Object> never = new CompletableFuture<>();
        replies.add(() -> new CompletableFutureWrapper<>(never));

        long started = System.nanoTime();
        // 截止给 1 s：从创建截止到发出命令之间即使卡住几百毫秒，也不会被判成「之前预算已用完（没有发出）」那一支
        assertThatThrownBy(() -> store.acquire(PLAYER, MARK, Deadline.after(1_000))).isInstanceOf(Deadline.DependencyException.class)
                .hasMessageContaining("超过请求预算");
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMs).as("不早于截止；上界只防无限等").isBetween(990L, 20_000L);
        assertThat(never).as("不取消在途的命令：它可能已经执行，结局不明由调用方收场").isNotCancelled();
        assertThat(evals).as("放弃等待的那一刻存储自己不发别的命令（当场的那次释放是调用方的事）").hasSize(1);

        // 在途的命令随后有了结局——Redisson 重发的那一遍成功了，标记被写回，而调用方的释放早已发过：存储按值再释放一次
        reply(1L);
        never.complete(SpectateScripts.ACQUIRE_OK);

        assertThat(evals).as("迟到的重发写回的标记不留给 TTL").hasSize(2);
        assertThat(evals.get(1))
                .isEqualTo(new Eval(RScript.Mode.READ_WRITE, SpectateScripts.RELEASE, RScript.ReturnType.INTEGER, List.of(MARK_KEY), List.of(MARK), false));
        assertThat(replies).isEmpty();
    }

    @Test
    void 抢标记的等待被中断_同样算放弃_在途的命令以失败收场之后也按值释放一次() {
        CompletableFuture<Object> inflight = new CompletableFuture<>();
        replies.add(() -> new CompletableFutureWrapper<>(inflight));

        // 预先置上中断标志：等待当场以「被中断」收场，命令仍在路上（不必真的等到截止）
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> store.acquire(PLAYER, MARK, Deadline.after(30_000))).isInstanceOf(Deadline.DependencyException.class)
                    .hasMessageContaining("被中断");
        } finally {
            Thread.interrupted(); // 清掉中断标志，别带给后面的断言与别的用例
        }
        assertThat(evals).hasSize(1);

        // 客户端最终判这条命令失败（例如重发的那一遍也响应超时）——它仍可能已经在 Redis 上执行过：照样按值释放一次（按值删，多删无害）
        reply(0L);
        inflight.completeExceptionally(new IllegalStateException("Redis 响应超时"));

        assertThat(evals).hasSize(2);
        assertThat(evals.get(1))
                .isEqualTo(new Eval(RScript.Mode.READ_WRITE, SpectateScripts.RELEASE, RScript.ReturnType.INTEGER, List.of(MARK_KEY), List.of(MARK), false));
    }

    @Test
    void 放弃之后的补释放只属于抢标记_而且只在命令还在路上时_别的失败与别的方法都不补发() {
        // 抢标记当场失败（Redis 报错）：没有在途的命令，调用方的那次释放已经排在它后面——存储不补发
        fail(new IllegalStateException("ERR 脚本报错"));
        assertThatThrownBy(() -> store.acquire(PLAYER, MARK, d())).isInstanceOf(Deadline.DependencyException.class);
        assertThat(evals).hasSize(1);

        // 删标记的等待被放弃：按值删本来就挡得住迟到的副本，不需要收尾
        CompletableFuture<Object> inflight = new CompletableFuture<>();
        replies.add(() -> new CompletableFutureWrapper<>(inflight));
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> store.release(PLAYER, MARK, Deadline.after(30_000))).isInstanceOf(Deadline.DependencyException.class);
        } finally {
            Thread.interrupted();
        }
        inflight.complete(1L);

        assertThat(evals).as("一条抢标记、一条删标记，没有第三条").hasSize(2);
    }

    @Test
    void 形状不对的应答_一律依赖异常_不猜() {
        BattlePlacement placement = placement(1);
        Class<Deadline.DependencyException> failure = Deadline.DependencyException.class;

        replyList(1L, 1L);
        assertThatThrownBy(() -> store.entry(PLAYER, d())).as("少一项").isInstanceOf(failure);
        replyList(2L, 0L, b(""));
        assertThatThrownBy(() -> store.entry(PLAYER, d())).as("有票标志不是 0 / 1").isInstanceOf(failure);
        replyList(0L, 1L, 5L);
        assertThatThrownBy(() -> store.entry(PLAYER, d())).as("标记不是字节串").isInstanceOf(failure);
        reply(7L);
        assertThatThrownBy(() -> store.acquire(PLAYER, MARK, d())).as("不认识的返回码").isInstanceOf(failure).hasMessageContaining("7");
        replyList(1L);
        assertThatThrownBy(() -> store.marksOf(List.of(PLAYER), d())).as("不成对").isInstanceOf(failure);
        replyList(2L, b(MARK));
        assertThatThrownBy(() -> store.marksOf(List.of(PLAYER), d())).as("下标越界").isInstanceOf(failure);
        replyList(0L, b(MARK));
        assertThatThrownBy(() -> store.marksOf(List.of(PLAYER), d())).as("下标从 1 起").isInstanceOf(failure);
        replyList(1L, 0L, 1L, b("1"));
        assertThatThrownBy(() -> store.read(BATTLE, d())).as("少 pb 一项").isInstanceOf(failure);
        replyList(1L, 0L, 9L, b("1"), placement.toByteArray());
        assertThatThrownBy(() -> store.read(BATTLE, d())).as("不认识的落点状态").isInstanceOf(failure);
        replyList(b("不是数字"), 0L, 0L, b(""), b(""));
        assertThatThrownBy(() -> store.read(BATTLE, d())).as("now 不是整数").isInstanceOf(failure);
        replyList(1L, b("m"));
        assertThatThrownBy(() -> store.pickRandom(0.5, d())).as("有成员没有分数").isInstanceOf(failure);
        replyList(1L, b("m"), b("不是分数"));
        assertThatThrownBy(() -> store.pickRandom(0.5, d())).isInstanceOf(failure);
        replyList(1L, b("m"), b("nan"));
        assertThatThrownBy(() -> store.pickRandom(0.5, d())).isInstanceOf(failure);
        replyList();
        assertThatThrownBy(() -> store.pickRandom(0.5, d())).as("连 now 都没有").isInstanceOf(failure);
        replyList(1L, b("m"));
        assertThatThrownBy(() -> store.list(20, d())).as("成员与分数不成对").isInstanceOf(failure);
        replyList(0L, b(""), b(""));
        assertThatThrownBy(() -> store.readPlacements(List.of(BATTLE, 5L), d())).as("两把键只回了一条").isInstanceOf(failure);
        reply(-1L);
        assertThatThrownBy(() -> store.sweep(d())).as("摘掉的条数为负").isInstanceOf(failure);

        assertThat(replies).as("每个准备好的应答都被用掉了").isEmpty();
    }

    // ================================================================ 两个尽力方法

    @Test
    void 异步删标记_发出一条按值删的脚本_异常完成与发不出去都不抛() {
        reply(1L);
        fail(new IllegalStateException("超时"));
        replies.add(() -> {
            throw new IllegalStateException("客户端已关闭");
        });

        assertThatCode(() -> store.releaseAsync(PLAYER, MARK)).doesNotThrowAnyException();
        assertThatCode(() -> store.releaseAsync(PLAYER, MARK)).doesNotThrowAnyException();
        assertThatCode(() -> store.releaseAsync(PLAYER, MARK)).doesNotThrowAnyException();

        assertThat(evals).hasSize(3).allSatisfy(eval -> assertThat(eval)
                .isEqualTo(new Eval(RScript.Mode.READ_WRITE, SpectateScripts.RELEASE, RScript.ReturnType.INTEGER, List.of(MARK_KEY), List.of(MARK), false)));
        assertThatThrownBy(() -> store.releaseAsync(PLAYER, null)).as("null 是调用方的错").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 异步剔除_整批进一个管道_一次执行_管道失败或建不出来都不抛() {
        List<Eviction> batch = List.of(new Eviction.Invalid("junk"), new Eviction.Missing(BATTLE), new Eviction.Stale(BATTLE, 5));
        reply(1L).reply(1L).reply(1L);

        assertThatCode(() -> store.evictAsync(batch)).doesNotThrowAnyException();

        assertThat(evals).hasSize(3).allSatisfy(eval -> {
            assertThat(eval.piped()).as("走管道，不是三条各占一条连接的命令").isTrue();
            assertThat(eval.lua()).isEqualTo(SpectateScripts.EVICT);
            assertThat(eval.mode()).isEqualTo(RScript.Mode.READ_WRITE);
        });
        assertThat(evals).extracting(Eval::args).containsExactly(List.of("invalid", "junk"), List.of("missing", BATTLE_TEXT),
                List.of("stale", BATTLE_TEXT, "5"));
        assertThat(pipelinesExecuted).isEqualTo(1);

        pipelineResult = new CompletableFutureWrapper<>(new IllegalStateException("管道超时"));
        reply(1L);
        assertThatCode(() -> store.evictAsync(List.of(new Eviction.Invalid("junk")))).doesNotThrowAnyException();
        assertThat(pipelinesExecuted).isEqualTo(2);

        when(redis.createBatch()).thenThrow(new IllegalStateException("客户端已关闭"));
        assertThatCode(() -> store.evictAsync(batch)).doesNotThrowAnyException();
        assertThat(pipelinesExecuted).isEqualTo(2);
    }

    // ================================================================ 构造与入参

    @Test
    void 构造不碰Redis_索引键必须以生产键开头() {
        RedissonClient untouched = mock(RedissonClient.class);

        new RedissonSpectateStore(untouched);
        new RedissonSpectateStore(untouched, INDEX_KEY + ":it:abc");

        verifyNoInteractions(untouched);
        assertThatThrownBy(() -> new RedissonSpectateStore(untouched, "xm:{other}:watchable")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedissonSpectateStore(untouched, "watchable")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedissonSpectateStore(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 入参不合法是调用方的错_发出之前就抛() {
        assertThatThrownBy(() -> store.acquire(PLAYER, "", d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.acquire(PLAYER, null, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.release(PLAYER, null, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.list(0, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.list(-1, d())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.evict(null, d())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> store.publish(null, d())).isInstanceOf(NullPointerException.class);

        verifyNoInteractions(redis);
    }
}
