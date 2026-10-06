package com.game.battle.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.entry;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.game.battle.metrics.BattleMetrics;
import com.game.battle.outbox.OutboxMetrics.ActivityEvent;
import com.game.battle.port.BattleResultSink;
import com.game.battle.port.BattleResultSink.Channel;
import com.game.battle.port.LoggingBattleResultSink;
import com.game.battle.room.EventLoopBattleScheduler;
import com.game.battle.testing.CallJournal;
import com.game.battle.testing.FakeActivityStore;
import com.game.battle.testing.ManualBattleScheduler;
import com.game.battle.testing.Scripted;
import com.game.discovery.battle.BattleRedis;
import com.game.proto.BattleActivityContext;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActivityKind;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.DefaultEventLoop;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

/**
 * 活动结果持久通道的 battle 侧（scene-battle-spec §7.17、§13.5；审计 OBX-2 与 OBX-12 的回归）：手动调度器 + 带内存模型的假 Redis 端口 +
 * 记录型发布端口，共用一份带全局序号的调用记录。
 *
 * <p>§13.5 表里「{@code kind = NONE} 不进通道、不认识的 kind 进通道」的判定在房间层：{@code room.ResultRoutingTest} 的
 * {@code 普通局只发一次普通结果}、{@code 活动局走活动通道并回显上下文}、{@code 不认识的kind也按活动局处理}（本通道只在 {@code kind ≠ NONE} 时被调）。
 *
 * <p><b>线程所有权</b>（D25，与结算发件箱共用 {@code battle-outbox}）：悬着的落库 / 探测一律经 {@link #handBack} 放行——放行那一刻什么都没生效、
 * 结局只是在发件箱线程上排了一个任务；另有一条真线程用例按线程名断言落库、探测、首发与重发都发生在发件箱线程上（评审 R63B-3）。
 */
class ActivityResultOutboxTest {

    private static final long T0 = 1_800_000_000_000L;
    private static final long B = 88_001;
    private static final long ROUND_MS = BattleRedis.ACTIVITY_RETRY_INTERVAL.toMillis();

    /** 记录型发布端口：记下事件对象、通道与全局序号；可让它抛异常。线程安全（真线程用例里由发件箱线程调、测试线程读）。 */
    private final class RecordingPublisher implements BattleResultSink {
        final List<BattleResultEvent> events = new CopyOnWriteArrayList<>();
        final List<Channel> channels = new CopyOnWriteArrayList<>();
        volatile RuntimeException thrown;

        @Override
        public void publish(BattleResultEvent event, Channel channel) {
            journal.add("publish:" + Long.toUnsignedString(event.getBattleId()) + "@" + channel.name().toLowerCase(Locale.ROOT));
            events.add(event);
            channels.add(channel);
            if (thrown != null) {
                throw thrown;
            }
        }
    }

    private final ManualBattleScheduler scheduler = new ManualBattleScheduler(T0);
    private final CallJournal journal = new CallJournal();
    private final FakeActivityStore store = new FakeActivityStore(journal);
    private final RecordingPublisher publisher = new RecordingPublisher();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(registry);
    private final ActivityResultOutbox outbox = new ActivityResultOutbox(scheduler, store, publisher, metrics, scheduler::nowMs);

    // ------------------------------------------------------------------ 夹具

    private static BattleResultEvent event(long battleId) {
        return BattleResultEvent.newBuilder().setBattleId(battleId).setTotalRounds(4).setWinnerTeamIndex(0)
                .addFledPlayerIds(11).addDeadPlayerIds(12)
                .setActivityContext(BattleActivityContext.newBuilder().setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL)
                        .setGuildId(66).setActivityId(3))
                .build();
    }

    private void dispatch(BattleResultEvent event) {
        outbox.dispatch(event);
        scheduler.runPending();
    }

    private void round() {
        scheduler.advance(ROUND_MS);
    }

    private void rounds(int n) {
        for (int i = 0; i < n; i++) {
            round();
        }
    }

    /** 计过数的通道事件（取值 → 次数；没计过的不出现）。 */
    private Map<String, Integer> events() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (ActivityEvent event : ActivityEvent.values()) {
            String label = event.name().toLowerCase(Locale.ROOT);
            int count = (int) registry.get(OutboxMetrics.ACTIVITY_OUTBOX).tag("event", label).counter().count();
            if (count != 0) {
                out.put(label, count);
            }
        }
        return out;
    }

    /** 被测类发起的端口调用（落库、探测、发布；不含假件自己记的「落库结局交回」）。 */
    private List<String> portCalls() {
        return journal.entries().stream().filter(e -> !e.startsWith("activity-store-done:")).toList();
    }

    /**
     * 放行一次悬着的落库 / 探测并钉住线程所有权（D25）：它的结局<b>只是在发件箱线程上排了一个任务</b>——放行的那一刻（完成 future 的线程，
     * 生产上是 Redisson 的回调线程）没有发布、没有改名单、没有计数、没有动定时器，跑到那个任务才生效。返回放行交回的值。
     */
    private <T> T handBack(Scripted.Pending<T> pending) {
        assertThat(scheduler.pendingTasks()).as("放行之前发件箱线程上没有排着的任务").isZero();
        List<String> calls = portCalls();
        int registered = outbox.size();
        Map<String, Integer> counted = events();
        int timers = scheduler.scheduledCount();

        T value = pending.release();

        assertThat(scheduler.pendingTasks()).as("结局交回发件箱线程：排了恰好一个任务（%s）", pending.label()).isEqualTo(1);
        assertThat(portCalls()).as("回调线程上不发布、不碰任何端口（%s）", pending.label()).isEqualTo(calls);
        assertThat(outbox.size()).as("回调线程上不改名单（%s）", pending.label()).isEqualTo(registered);
        assertThat(events()).as("回调线程上不计数（%s）", pending.label()).isEqualTo(counted);
        assertThat(scheduler.scheduledCount()).as("回调线程上不开表不停表（%s）", pending.label()).isEqualTo(timers);
        scheduler.runPending();
        return value;
    }

    /** 真线程用例：等到第 {@code n} 次悬着的调用出现（它是在发件箱线程上发起的）。 */
    private static <T> Scripted.Pending<T> awaitHeld(Scripted<T> scripted, int n) {
        await().atMost(Duration.ofSeconds(10)).until(() -> scripted.pending.size() >= n);
        return scripted.pending.get(n - 1);
    }

    // ================================================================== 落库与首发

    @Test
    void dispatch只把任务交给发件箱线程() {
        outbox.dispatch(event(B));

        assertThat(journal.entries()).isEmpty();
        assertThat(scheduler.pendingTasks()).isEqualTo(1);
    }

    @Test
    void 落库完成之前不发布_成功后按活动通道发布同一份字节_登记并开表() {
        BattleResultEvent event = event(B);
        store.stores.hold();

        dispatch(event);

        assertThat(journal.entries()).containsExactly("activity-store:" + B);
        assertThat(publisher.events).isEmpty();
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();

        assertThat(handBack(store.stores.last())).isEqualTo(1L);

        assertThat(journal.entries()).as("全局序号：落库发起 → 落库结局 → 发布").containsExactly("activity-store:" + B,
                "activity-store-done:" + B + "=1", "publish:" + B + "@activity");
        assertThat(publisher.events).singleElement().isSameAs(event);
        assertThat(publisher.events.get(0).toByteArray()).as("发布的与落库的是同一份字节").isEqualTo(store.copy(B))
                .isEqualTo(event.toByteArray());
        assertThat(publisher.channels).containsExactly(Channel.ACTIVITY);
        assertThat(outbox.size()).isEqualTo(1);
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        assertThat(events()).containsOnly(entry("stored", 1));
    }

    @Test
    void 落库失败_只发布一次_不登记_计not_durable_之后不探测不重发() {
        store.stores.failWith(new TimeoutException("Redis 响应超时"));

        dispatch(event(B));

        assertThat(journal.entries()).containsExactly("activity-store:" + B, "activity-store-done:" + B + "=error",
                "publish:" + B + "@activity");
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(events()).containsOnly(entry("not_durable", 1));

        scheduler.advance(10 * ROUND_MS);
        assertThat(publisher.events).as("只发一次").hasSize(1);
        assertThat(journal.count("activity-exists:")).isZero();
    }

    @Test
    void 落库端口同步抛异常_没有结论_空future_都按not_durable只发一次() {
        store.stores.throwing(new IllegalStateException("Redis 不可用"));
        dispatch(event(B));
        store.stores.replyWith(null);
        dispatch(event(B + 1));
        store.stores.returnNullFuture();
        dispatch(event(B + 2));

        assertThat(events()).containsOnly(entry("not_durable", 3));
        assertThat(journal.starting("publish:")).containsExactly("publish:" + B + "@activity", "publish:" + (B + 1) + "@activity",
                "publish:" + (B + 2) + "@activity");
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 序列化失败_不落库不发布_计serialize_failed() {
        BattleResultEvent broken = mock(BattleResultEvent.class);
        when(broken.getBattleId()).thenReturn(B);
        when(broken.toByteArray()).thenThrow(new IllegalStateException("序列化失败（测试）"));

        dispatch(broken);

        assertThat(journal.entries()).isEmpty();
        assertThat(events()).containsOnly(entry("serialize_failed", 1));
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 发布端口抛异常_被吞掉_条目照常登记_下一轮照常重发() {
        publisher.thrown = new IllegalStateException("发布端口出错（测试）");

        assertThatCode(() -> dispatch(event(B))).doesNotThrowAnyException();
        assertThat(outbox.size()).isEqualTo(1);
        assertThat(scheduler.scheduledCount()).isEqualTo(1);

        assertThatCode(this::round).doesNotThrowAnyException();
        assertThat(publisher.events).hasSize(2);
        assertThat(events()).containsOnly(entry("stored", 1), entry("resend", 1));
    }

    // ================================================================== 每 10 s 一轮

    @Test
    void 探测_副本已被消费方删掉_摘除计acked_不重发_当场停表() {
        dispatch(event(B));
        store.consume(B);
        journal.clear();

        round();

        assertThat(journal.entries()).containsExactly("activity-exists:" + B);
        assertThat(outbox.size()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("acked", 1));
        assertThat(publisher.events).hasSize(1);
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 探测_副本还在_重发原字节_仍走活动通道_只在重发时计次() {
        BattleResultEvent event = event(B);
        dispatch(event);
        journal.clear();

        rounds(2);

        assertThat(journal.entries()).containsExactly("activity-exists:" + B, "publish:" + B + "@activity", "activity-exists:" + B,
                "publish:" + B + "@activity");
        assertThat(publisher.events).hasSize(3).allSatisfy(e -> assertThat(e).isSameAs(event));
        assertThat(publisher.events.get(2).toByteArray()).as("重发原字节").isEqualTo(store.copy(B));
        assertThat(publisher.channels).containsOnly(Channel.ACTIVITY);
        assertThat(events()).containsOnly(entry("stored", 1), entry("resend", 2));
        assertThat(outbox.size()).isEqualTo(1);
    }

    @Test
    void 重发30次之后_第31轮用尽_摘除_副本留在Redis() {
        dispatch(event(B));

        rounds(30);
        assertThat(publisher.events).as("首发 1 + 重发 30").hasSize(31);
        assertThat(outbox.size()).isEqualTo(1);
        assertThat(events()).containsOnly(entry("stored", 1), entry("resend", 30));
        journal.clear();

        round();

        assertThat(journal.entries()).as("第 31 轮只探测、不重发").containsExactly("activity-exists:" + B);
        assertThat(publisher.events).hasSize(31);
        assertThat(outbox.size()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("resend", 30), entry("exhausted", 1));
        assertThat(store.present(B)).as("留给消费方的巡检器").isTrue();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 最后一轮恰好已销账_算acked不算用尽() {
        dispatch(event(B));
        rounds(30);
        store.consume(B);

        round();

        assertThat(events()).containsOnly(entry("stored", 1), entry("resend", 30), entry("acked", 1));
        assertThat(outbox.size()).isZero();
    }

    @Test
    void 探测出错或没有结论_本轮跳过_不计次_不重发_之后仍有完整的30次重发() {
        dispatch(event(B));

        store.probes.failWith(new IllegalStateException("LOADING Redis is loading the dataset in memory"));
        rounds(2);
        store.probes.replyWith(null);
        round();
        store.probes.throwing(new IllegalStateException("探测端口抛异常"));
        round();
        store.probes.returnNullFuture();
        round();

        assertThat(events()).containsOnly(entry("stored", 1), entry("probe_error", 5));
        assertThat(publisher.events).as("出错的轮不重发").hasSize(1);
        assertThat(outbox.size()).isEqualTo(1);

        store.probes.auto();
        rounds(30);
        assertThat(publisher.events).as("出错的 5 轮没有消耗次数").hasSize(31);
        assertThat(outbox.size()).isEqualTo(1);
        round();
        assertThat(events()).containsOnly(entry("stored", 1), entry("probe_error", 5), entry("resend", 30), entry("exhausted", 1));
    }

    @Test
    void 一直出错_登记超过OUTBOX_MAX_AGE_按用尽摘除计expired() {
        dispatch(event(B));
        store.probes.failWith(new IllegalStateException("WRONGTYPE"));

        scheduler.advance(BattleRedis.OUTBOX_MAX_AGE.toMillis());
        assertThat(outbox.size()).as("恰好 10 min 还不算超龄").isEqualTo(1);
        assertThat(events()).containsOnly(entry("stored", 1), entry("probe_error", 60));

        round();

        assertThat(outbox.size()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("probe_error", 60), entry("expired", 1));
        assertThat(journal.count("activity-exists:")).as("超龄那一轮不再探测").isEqualTo(60);
        assertThat(publisher.events).hasSize(1);
        assertThat(store.present(B)).isTrue();
        assertThat(scheduler.scheduledCount()).isZero();
    }

    @Test
    void 探测在途时下一轮不发第二次探测_回来之后照常重发() {
        dispatch(event(B));
        store.probes.hold();

        rounds(3);
        assertThat(journal.count("activity-exists:")).as("probing 防重叠").isEqualTo(1);
        assertThat(publisher.events).hasSize(1);

        assertThat(handBack(store.probes.last())).as("副本还在").isTrue();
        assertThat(publisher.events).hasSize(2);
        assertThat(events()).containsOnly(entry("stored", 1), entry("resend", 1));

        round();
        assertThat(journal.count("activity-exists:")).isEqualTo(2);
    }

    @Test
    void 定时器_第一条入队时开_多条共用_清空后停_再入队重新开() {
        assertThat(scheduler.scheduledCount()).isZero();
        dispatch(event(B));
        dispatch(event(B + 1));
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        assertThat(outbox.size()).isEqualTo(2);

        store.consume(B);
        round();
        assertThat(outbox.size()).isEqualTo(1);
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        assertThat(journal.starting("publish:")).as("各条各自重发").endsWith("publish:" + (B + 1) + "@activity");

        store.consume(B + 1);
        round();
        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();

        dispatch(event(B + 2));
        assertThat(scheduler.scheduledCount()).isEqualTo(1);
        round();
        assertThat(events()).containsOnly(entry("stored", 3), entry("acked", 2), entry("resend", 2));
    }

    @Test
    void 同一局重入_旧条目悬着的探测回来时被身份比较丢弃() {
        dispatch(event(B));
        store.probes.hold();
        round();

        store.probes.auto();
        dispatch(event(B));
        assertThat(outbox.size()).isEqualTo(1);
        store.probes.pending.get(0).complete(false);
        assertThat(scheduler.pendingTasks()).as("旧探测的结局同样先交回发件箱线程，身份比较在那里做").isEqualTo(1);
        scheduler.runPending();

        assertThat(outbox.size()).as("旧条目的「已销账」不能摘掉新条目").isEqualTo(1);
        assertThat(events()).containsOnly(entry("stored", 2));
    }

    // ================================================================== 线程所有权（D25）

    @Test
    void 探测的结局是已销账_同样先交回发件箱线程_跑到才摘除停表() {
        dispatch(event(B));
        store.probes.hold();
        round();
        store.consume(B);

        assertThat(handBack(store.probes.last())).as("副本已被消费方删掉").isFalse();

        assertThat(outbox.size()).isZero();
        assertThat(scheduler.scheduledCount()).isZero();
        assertThat(events()).containsOnly(entry("stored", 1), entry("acked", 1));
        assertThat(publisher.events).as("不重发").hasSize(1);
    }

    @Test
    void 真发件箱线程_落库与探测的结局由别的线程交回_落库探测首发重发仍全部在发件箱线程上() throws Exception {
        DefaultEventLoop loop = new DefaultEventLoop(new DefaultThreadFactory("test-battle-outbox"));
        try {
            ActivityResultOutbox real = new ActivityResultOutbox(new EventLoopBattleScheduler(loop), store, publisher, metrics,
                    () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
            Duration patience = Duration.ofSeconds(10);
            store.stores.hold();
            store.probes.hold();

            // 落库与两次探测的结局都在测试线程上交回（生产上是 Redisson 的回调线程）
            real.dispatch(event(B));
            awaitHeld(store.stores, 1).release();
            await().atMost(patience).until(() -> journal.count("publish:") == 1);
            loop.execute(real::tick);
            awaitHeld(store.probes, 1).release();
            await().atMost(patience).until(() -> journal.count("publish:") == 2);
            store.consume(B);
            loop.execute(real::tick);
            awaitHeld(store.probes, 2).release();
            await().atMost(patience).until(() -> loop.submit((Callable<Integer>) real::size).get(5, TimeUnit.SECONDS) == 0);

            assertThat(journal.entries()).containsExactly("activity-store:" + B, "activity-store-done:" + B + "=1",
                    "publish:" + B + "@activity", "activity-exists:" + B, "publish:" + B + "@activity", "activity-exists:" + B);
            assertThat(journal.threadsOf("activity-store-done:")).as("结局确实是在别的线程（测试线程）上交回的")
                    .containsExactly(Thread.currentThread().getName());
            for (String port : List.of("activity-store:", "activity-exists:", "publish:")) {
                assertThat(journal.threadsOf(port)).as("%s 只在发件箱线程上发起（首发与重发没有留在回调线程上）", port).isNotEmpty()
                        .allSatisfy(thread -> assertThat(thread).startsWith("test-battle-outbox"));
            }
            assertThat(events()).containsOnly(entry("stored", 1), entry("resend", 1), entry("acked", 1));
        } finally {
            loop.shutdownGracefully(0, 100, TimeUnit.MILLISECONDS).await(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void 发件箱线程已停_dispatch不抛异常_迟到的结局被丢弃() {
        store.stores.hold();
        dispatch(event(B));
        scheduler.shutdown();

        assertThatCode(() -> outbox.dispatch(event(B + 1))).doesNotThrowAnyException();
        assertThatCode(() -> store.stores.last().release()).doesNotThrowAnyException();

        assertThat(journal.count("activity-store:")).as("线程已停之后的那一局没有落库").isEqualTo(1);
        assertThat(publisher.events).isEmpty();
    }

    // ================================================================== 计数通道（OBX-12）

    @Test
    void 经缺省发布端口_首发与每次重发都计channel_activity_plain不动() {
        SimpleMeterRegistry battleRegistry = new SimpleMeterRegistry();
        BattleMetrics battleMetrics = new BattleMetrics(battleRegistry);
        ActivityResultOutbox real = new ActivityResultOutbox(scheduler, store, new LoggingBattleResultSink(battleMetrics), metrics,
                scheduler::nowMs);

        real.dispatch(event(B));
        scheduler.runPending();
        rounds(3);

        assertThat(battleRegistry.get("xm.battle.results").tag("channel", "activity").tag("result", "logged").counter().count())
                .as("首发 1 + 重发 3").isEqualTo(4);
        assertThat(battleRegistry.get("xm.battle.results").tag("channel", "plain").tag("result", "logged").counter().count())
                .as("活动局不混进普通局的计数").isZero();
    }
}
