package com.game.battle.port.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.Level;
import com.game.audit.AuditBrokerUnavailableException;
import com.game.audit.AuditTopicContractException;
import com.game.audit.TopicAdmin;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.port.BattleResultSink.Channel;
import com.game.battle.testing.FakeResultKafka;
import com.game.battle.testing.FakeResultKafka.Created;
import com.game.battle.testing.FakeResultKafka.Sent;
import com.game.battle.testing.ResultFallbackCapture;
import com.game.battle.testing.ResultFallbackCapture.Line;
import com.game.proto.BattleActivityContext;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleActivityKind;
import com.game.proto.eBattleOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.LongStream;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 对局结果的 Kafka 传输（match-spec §5.4、§15.2；{@code MockProducer} + 假的 topic 管理，不连 Kafka）：消息的 topic / key / value、
 * 线程（{@code send} 只在 {@code battle-result-out} 上，{@code publish} 从不阻塞调用线程，两条发布线程并发不丢不乱）、topic 核对
 * （缺就建、分区数不符拒绝、通过之前不发、30 s 冷却后由下一条事件触发再核对）、以及「没被 Kafka 确认的每一条都完整进兜底日志」的五个出口
 * （队列满 / 未核对 / 发送失败 / 投递失败 / 停服没发完）。连真 Kafka 的那几条在 {@code KafkaBattleResultSinkIntegrationTest}。
 */
class KafkaBattleResultSinkTest {

    private static final int GENERATION = 7;
    private static final String TOPIC = "xm-battle-result-g7";
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final long REVERIFY_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BattleMetrics metrics = new BattleMetrics(registry);
    private final FakeResultKafka kafka = new FakeResultKafka();
    /** 注入的单调时钟（纳秒）：只用来推「距上次核对多久」。 */
    private final AtomicLong nanos = new AtomicLong(TimeUnit.HOURS.toNanos(1));
    private final ResultFallbackCapture fallback = ResultFallbackCapture.start();
    private KafkaBattleResultSink sink;

    private KafkaBattleResultSink sink(int queueCapacity) {
        sink = new KafkaBattleResultSink(kafka, GENERATION, (short) 2, Duration.ofSeconds(1), metrics, queueCapacity, nanos::get);
        return sink;
    }

    /** 建好并通过启动期核对的 sink。 */
    private KafkaBattleResultSink started(int queueCapacity) {
        sink(queueCapacity).start();
        return sink;
    }

    @AfterEach
    void tearDown() {
        kafka.release();
        if (sink != null) {
            sink.close(Duration.ofMillis(200));
        }
        fallback.close();
    }

    // ------------------------------------------------------------------ 夹具

    /** 一份字段都有值的普通局结果（1V1，A 胜）。 */
    private static BattleResultEvent event(long battleId) {
        return BattleResultEvent.newBuilder().setBattleId(battleId).setMatchMode(1).setBattleConfigId(3)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN).setWinnerTeamIndex(0)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(9001))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(9002))
                .setTotalRounds(12).setFinishedAtMs(1_800_000_000_123L).addDeadPlayerIds(9002).build();
    }

    private static BattleResultEvent activityEvent(long battleId) {
        return event(battleId).toBuilder().setMatchMode(5).setActivityContext(BattleActivityContext.newBuilder()
                .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(66).setActivityId(3)).build();
    }

    private double events(String result) {
        return registry.get("xm.battle.result.events").tag("result", result).counter().count();
    }

    private double results(String channel, String result) {
        return registry.get("xm.battle.results").tag("channel", channel).tag("result", result).counter().count();
    }

    private static List<String> keys(List<Sent> sent) {
        return sent.stream().map(Sent::key).toList();
    }

    /** 在一条指定名字的线程上跑完 {@code body}（2 s 内跑不完即失败：{@code publish} 不许阻塞调用线程）。 */
    private static void onThread(String name, Runnable body) throws Exception {
        FutureTask<Void> task = new FutureTask<>(body, null);
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.start();
        task.get(2, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ topic 核对

    @Test
    void 启动核对_topic不存在就按规格建_三分区保留七天_副本数照配置_只核对不校正配置_管理客户端用完即关() {
        started(10);

        assertThat(sink.topic()).isEqualTo(TOPIC);
        assertThat(sink.verified()).isTrue();
        assertThat(kafka.partitionsOf(TOPIC)).isEqualTo(3);
        assertThat(kafka.created()).containsExactly(new Created(TOPIC, 3,
                Map.of("retention.ms", "604800000", "retention.bytes", "-1", "cleanup.policy", "delete"), (short) 2));
        assertThat(kafka.adminCalls()).as("battle 只是生产方：建完读回分区数，不读不改 topic 配置（保留期归 xm-match 校正）")
                .containsExactly("open", "partitions", "create", "partitions", "close");
        assertThat(kafka.producersMade()).as("核对通过才建生产者").isEqualTo(1);
    }

    @Test
    void 启动核对_topic已存在且分区数相符_不重建() {
        kafka.topic(TOPIC, 3);

        started(10);

        assertThat(sink.verified()).isTrue();
        assertThat(kafka.created()).isEmpty();
        assertThat(kafka.adminCalls()).containsExactly("open", "partitions", "close");
    }

    @Test
    void 启动核对_分区数与契约不符_抛契约异常_报错指向对局结果的代次变量_不建生产者() {
        kafka.topic(TOPIC, 1);
        KafkaBattleResultSink mismatched = sink(10);

        assertThatThrownBy(mismatched::start).isInstanceOf(AuditTopicContractException.class)
                .hasMessageContaining(TOPIC).hasMessageContaining("分区数是 1").hasMessageContaining("契约是 3")
                .hasMessageContaining("XM_BATTLE_RESULT_TOPIC_GENERATION");

        assertThat(mismatched.verified()).isFalse();
        assertThat(kafka.producersMade()).isZero();
        assertThat(kafka.created()).isEmpty();
        assertThat(kafka.adminCalls()).as("抛出之前管理客户端也关了").containsExactly("open", "partitions", "close");
    }

    @Test
    void 核对通过之前不发_Kafka不可达时启动不抛_事件写兜底计not_verified_30秒内不重复核对_满30秒后的下一条事件触发核对并发出() {
        kafka.unreachable = true;
        sink(10).start();
        assertThat(sink.verified()).isFalse();
        assertThat(kafka.adminsOpened()).isEqualTo(1);
        assertThat(kafka.producersMade()).as("没核对通过就不建生产者（地址解析不了时构造器会抛）").isZero();

        sink.publish(event(101));
        await().atMost(WAIT).until(() -> events("not_verified") == 1);
        Line line = fallback.lines().get(0);
        assertThat(line.reason()).isEqualTo("not_verified");
        assertThat(line.key()).isEqualTo("101");
        assertThat(line.event()).isEqualTo(event(101));
        assertThat(results("plain", "error")).isEqualTo(1);
        assertThat(kafka.adminsOpened()).as("刚核对过，冷却中").isEqualTo(1);

        kafka.unreachable = false;
        nanos.addAndGet(REVERIFY_NANOS - 1);
        sink.publish(event(102));
        await().atMost(WAIT).until(() -> events("not_verified") == 2);
        assertThat(kafka.adminsOpened()).as("差 1 ns 满 30 s：Kafka 已恢复也不核对").isEqualTo(1);
        assertThat(kafka.sent()).isEmpty();

        nanos.addAndGet(1);
        sink.publish(event(103));
        await().atMost(WAIT).until(() -> kafka.sent().size() == 1);
        assertThat(kafka.sent().get(0).key()).as("触发核对的那一条自己就发出去了，不用等下一条").isEqualTo("103");
        assertThat(kafka.adminsOpened()).isEqualTo(2);
        assertThat(sink.verified()).isTrue();
        await().atMost(WAIT).until(() -> events("sent") == 1);
        assertThat(events("not_verified")).isEqualTo(2);
        assertThat(events("fallback")).isZero();
        assertThat(fallback.size()).isEqualTo(2);
    }

    @Test
    void 再次核对仍连不上_冷却从这次核对重新计时() {
        kafka.unreachable = true;
        sink(10).start();

        nanos.addAndGet(REVERIFY_NANOS);
        sink.publish(event(111));
        await().atMost(WAIT).until(() -> events("not_verified") == 1);
        assertThat(kafka.adminsOpened()).isEqualTo(2);

        nanos.addAndGet(REVERIFY_NANOS - 1);
        sink.publish(event(112));
        await().atMost(WAIT).until(() -> events("not_verified") == 2);
        assertThat(kafka.adminsOpened()).as("距第二次核对还不满 30 s").isEqualTo(2);
        assertThat(kafka.producersMade()).isZero();
    }

    @Test
    void 运行中再次核对发现分区数不符_不抛不崩_事件继续写兜底() {
        kafka.unreachable = true;
        sink(10).start();
        kafka.unreachable = false;
        kafka.topic(TOPIC, 1);

        nanos.addAndGet(REVERIFY_NANOS);
        sink.publish(event(121));
        await().atMost(WAIT).until(() -> events("not_verified") == 1);
        assertThat(kafka.adminsOpened()).isEqualTo(2);
        assertThat(sink.verified()).isFalse();
        assertThat(kafka.producersMade()).isZero();

        // 发送线程还活着：换成相符的分区数、过了冷却，下一条照常发
        kafka.topic(TOPIC, 3);
        nanos.addAndGet(REVERIFY_NANOS);
        sink.publish(event(122));
        await().atMost(WAIT).until(() -> kafka.sent().size() == 1);
        assertThat(kafka.sent().get(0).key()).isEqualTo("122");
    }

    @Test
    void 管理客户端或生产者建不出来_按暂时不可达处理_启动不抛_恢复后照常发() {
        kafka.adminFactoryFails = true;
        assertThatCode(() -> sink(10).start()).doesNotThrowAnyException();
        assertThat(sink.verified()).isFalse();

        kafka.adminFactoryFails = false;
        kafka.producerFactoryFails = true;
        nanos.addAndGet(REVERIFY_NANOS);
        sink.publish(event(131));
        await().atMost(WAIT).until(() -> events("not_verified") == 1);
        assertThat(kafka.partitionsOf(TOPIC)).as("topic 核对其实过了，卡在建生产者").isEqualTo(3);
        assertThat(sink.verified()).isFalse();

        kafka.producerFactoryFails = false;
        nanos.addAndGet(REVERIFY_NANOS);
        sink.publish(event(132));
        await().atMost(WAIT).until(() -> kafka.sent().size() == 1);
        assertThat(kafka.sent().get(0).key()).isEqualTo("132");
        assertThat(kafka.producersMade()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 消息形状与线程

    @Test
    void key是battle_id的无符号十进制_value是事件的完整字节_发往带代次的topic_确认后计sent() {
        started(10);
        BattleResultEvent event = event(-3L);

        sink.publish(event);

        await().atMost(WAIT).until(() -> events("sent") == 1);
        Sent sent = kafka.sent().get(0);
        assertThat(sent.topic()).isEqualTo("xm-battle-result-g7");
        assertThat(sent.key()).as("battle_id 最高位为 1 时不能出现负号").isEqualTo("18446744073709551613");
        assertThat(sent.value()).isEqualTo(event.toByteArray());
        assertThat(sent.event()).isEqualTo(event);
        assertThat(results("plain", "sent")).isEqualTo(1);
        assertThat(results("activity", "sent")).isZero();
        assertThat(results("plain", "error")).isZero();
        assertThat(events("fallback")).isZero();
        assertThat(events("not_verified")).isZero();
        assertThat(fallback.size()).isZero();
    }

    @Test
    void send只在battle_result_out线程上调_从battle_logic与battle_outbox发布都不在调用线程上碰生产者() throws Exception {
        started(10);

        onThread("battle-logic", () -> sink.publish(event(201)));
        onThread("battle-outbox", () -> sink.publish(activityEvent(202), Channel.ACTIVITY));

        await().atMost(WAIT).until(() -> kafka.sent().size() == 2);
        assertThat(kafka.sendThreads()).containsExactly("battle-result-out", "battle-result-out");
        assertThat(KafkaBattleResultSink.THREAD_NAME).isEqualTo("battle-result-out");
        assertThat(keys(kafka.sent())).containsExactly("201", "202");
    }

    @Test
    void 发送线程卡在send里_publish照样立即返回_放开后按交来的次序发出() throws Exception {
        started(10);
        kafka.holdSends();

        // onThread 2 s 内等不到返回即失败：send 这时卡着（假生产者最多卡 30 s）
        onThread("battle-logic", () -> {
            sink.publish(event(211));
            sink.publish(event(212));
            sink.publish(event(213));
        });

        await().atMost(WAIT).until(() -> kafka.sendThreads().size() == 1);
        assertThat(kafka.sendThreads()).containsExactly("battle-result-out");
        assertThat(kafka.sent()).as("第一条还卡在 send 里").isEmpty();
        assertThat(events("sent")).isZero();

        kafka.release();
        await().atMost(WAIT).until(() -> events("sent") == 3);
        assertThat(keys(kafka.sent())).containsExactly("211", "212", "213");
        assertThat(fallback.size()).isZero();
    }

    @Test
    void 两条线程并发发布_不丢不重_各自的次序保持_按通道分开计数() throws Exception {
        int perThread = 400;
        started(KafkaBattleResultSink.QUEUE_CAPACITY);
        CountDownLatch go = new CountDownLatch(1);
        FutureTask<Void> logic = new FutureTask<>(() -> {
            go.await();
            for (long id = 1; id <= perThread; id++) {
                sink.publish(event(id));
            }
            return null;
        });
        FutureTask<Void> outbox = new FutureTask<>(() -> {
            go.await();
            for (long id = 1001; id <= 1000 + perThread; id++) {
                sink.publish(activityEvent(id), Channel.ACTIVITY);
            }
            return null;
        });
        new Thread(logic, "battle-logic").start();
        new Thread(outbox, "battle-outbox").start();

        go.countDown();
        logic.get(10, TimeUnit.SECONDS);
        outbox.get(10, TimeUnit.SECONDS);

        await().atMost(Duration.ofSeconds(10)).until(() -> events("sent") == 2 * perThread);
        List<Sent> sent = kafka.sent();
        assertThat(sent).hasSize(2 * perThread);
        assertThat(sent.stream().map(Sent::key).filter(key -> Long.parseLong(key) <= 1000).toList())
                .as("battle-logic 交来的 400 条：一条不少、没有重复、次序不变")
                .containsExactlyElementsOf(LongStream.rangeClosed(1, perThread).mapToObj(Long::toString).toList());
        assertThat(sent.stream().map(Sent::key).filter(key -> Long.parseLong(key) > 1000).toList())
                .as("battle-outbox 交来的 400 条：一条不少、没有重复、次序不变")
                .containsExactlyElementsOf(LongStream.rangeClosed(1001, 1000 + perThread).mapToObj(Long::toString).toList());
        assertThat(sent).allSatisfy(message -> {
            long id = Long.parseLong(message.key());
            assertThat(message.event()).as("value 与 key 是同一条事件（没有串）").isEqualTo(id <= 1000 ? event(id) : activityEvent(id));
        });
        assertThat(kafka.sendThreads()).hasSize(2 * perThread).containsOnly("battle-result-out");
        assertThat(results("plain", "sent")).isEqualTo(perThread);
        assertThat(results("activity", "sent")).isEqualTo(perThread);
        assertThat(results("plain", "error") + results("activity", "error")).isZero();
        assertThat(fallback.size()).isZero();
    }

    @Test
    void 同一个事件对象重发多次_活动通道_每次都发_字节相同_都计activity() {
        started(10);
        BattleResultEvent event = activityEvent(301);

        sink.publish(event, Channel.ACTIVITY);
        sink.publish(event, Channel.ACTIVITY);
        sink.publish(event, Channel.ACTIVITY);

        await().atMost(WAIT).until(() -> events("sent") == 3);
        assertThat(kafka.sent()).hasSize(3).allSatisfy(message -> {
            assertThat(message.key()).isEqualTo("301");
            assertThat(message.value()).isEqualTo(event.toByteArray());
        });
        assertThat(results("activity", "sent")).isEqualTo(3);
        assertThat(results("plain", "sent")).isZero();
    }

    @Test
    void 空事件忽略不抛_通道为空按普通局() {
        started(10);

        assertThatCode(() -> sink.publish(null, Channel.PLAIN)).doesNotThrowAnyException();
        sink.publish(event(311), null);

        await().atMost(WAIT).until(() -> events("sent") == 1);
        assertThat(keys(kafka.sent())).containsExactly("311");
        assertThat(results("plain", "sent")).isEqualTo(1);
        assertThat(fallback.size()).isZero();
    }

    // ------------------------------------------------------------------ 兜底日志的五个出口

    @Test
    void 队列满_新事件当场写兜底日志_完整字节能解回原事件_计fallback_不抛不阻塞_排着的放开后照发() throws Exception {
        started(2);
        kafka.holdSends();
        sink.publish(event(401));
        await().atMost(WAIT).until(() -> kafka.sendThreads().size() == 1);
        sink.publish(event(402));
        sink.publish(event(403));
        BattleResultEvent overflow = event(-404L);

        onThread("battle-logic", () -> sink.publish(overflow));

        assertThat(fallback.lines()).as("在调用线程上当场写下").hasSize(1);
        Line line = fallback.lines().get(0);
        assertThat(line.level()).isEqualTo(Level.WARN);
        assertThat(line.reason()).isEqualTo("queue_full");
        assertThat(line.channel()).isEqualTo("plain");
        assertThat(line.topic()).isEqualTo(TOPIC);
        assertThat(line.key()).isEqualTo(Long.toUnsignedString(-404L));
        assertThat(line.payload()).as("完整字节：回灌就是把它原样发到 topic").isEqualTo(overflow.toByteArray());
        assertThat(line.bytes()).isEqualTo(overflow.getSerializedSize());
        assertThat(line.event()).isEqualTo(overflow);
        assertThat(events("fallback")).isEqualTo(1);
        assertThat(results("plain", "error")).isEqualTo(1);

        kafka.release();
        await().atMost(WAIT).until(() -> events("sent") == 3);
        assertThat(keys(kafka.sent())).containsExactly("401", "402", "403");
        assertThat(fallback.size()).isEqualTo(1);
    }

    @Test
    void 生产用的队列上限是规格的1024_正在发的那条不占队列_第1026条才溢出() {
        assertThat(KafkaBattleResultSink.QUEUE_CAPACITY).isEqualTo(1024);
        sink = new KafkaBattleResultSink(kafka, GENERATION, (short) 1, Duration.ofSeconds(1), metrics);
        sink.start();
        kafka.holdSends();
        sink.publish(event(1));
        await().atMost(WAIT).until(() -> kafka.sendThreads().size() == 1);

        for (long id = 2; id <= 1025; id++) {
            sink.publish(event(id));
        }
        assertThat(fallback.size()).as("1 条在发 + 1024 条排队").isZero();
        sink.publish(event(1026));

        assertThat(fallback.lines()).singleElement().satisfies(line -> {
            assertThat(line.reason()).isEqualTo("queue_full");
            assertThat(line.key()).isEqualTo("1026");
        });
        kafka.release();
        await().atMost(Duration.ofSeconds(10)).until(() -> events("sent") == 1025);
        assertThat(kafka.sent().get(1024).key()).isEqualTo("1025");
        assertThat(events("fallback")).isEqualTo(1);
    }

    @Test
    void 投递失败_回调报错_写兜底计fallback_生产者照用() {
        kafka.autoComplete = false;
        started(10);
        sink.publish(activityEvent(411), Channel.ACTIVITY);
        await().atMost(WAIT).until(() -> kafka.sent().size() == 1);

        kafka.producer(0).errorNext(new TimeoutException("Expiring 1 record(s) for xm-battle-result-g7-0"));

        assertThat(fallback.lines()).hasSize(1);
        Line line = fallback.lines().get(0);
        assertThat(line.reason()).isEqualTo("delivery_failed");
        assertThat(line.channel()).isEqualTo("activity");
        assertThat(line.event()).isEqualTo(activityEvent(411));
        assertThat(events("fallback")).isEqualTo(1);
        assertThat(results("activity", "error")).isEqualTo(1);
        assertThat(sink.verified()).as("投递失败不是生产者坏了").isTrue();
        assertThat(kafka.producer(0).closed()).isFalse();

        sink.publish(event(412));
        await().atMost(WAIT).until(() -> kafka.sent().size() == 2);
        kafka.producer(0).completeNext();
        assertThat(events("sent")).isEqualTo(1);
        assertThat(kafka.producersMade()).isEqualTo(1);
    }

    @Test
    void send同步抛KafkaException_这条写兜底_丢弃生产者_冷却后的下一次核对重建() {
        started(10);
        MockProducer<String, byte[]> broken = kafka.producer(0);
        broken.sendException = new KafkaException(
                "Cannot perform send because at least one previous transactional or idempotent request has failed");

        sink.publish(event(421));
        await().atMost(WAIT).until(() -> events("fallback") == 1);
        assertThat(fallback.lines().get(0).reason()).isEqualTo("send_error");
        assertThat(fallback.lines().get(0).event()).isEqualTo(event(421));
        await().atMost(WAIT).until(() -> !sink.verified() && broken.closed());

        sink.publish(event(422));
        await().atMost(WAIT).until(() -> events("not_verified") == 1);
        assertThat(kafka.producersMade()).as("距上次核对不满 30 s，不会每条都重建").isEqualTo(1);

        nanos.addAndGet(REVERIFY_NANOS);
        sink.publish(event(423));
        await().atMost(WAIT).until(() -> events("sent") == 1);
        assertThat(kafka.producersMade()).isEqualTo(2);
        assertThat(kafka.producer(1).history()).singleElement().satisfies(record -> assertThat(record.key()).isEqualTo("423"));
        assertThat(events("fallback")).isEqualTo(1);
        assertThat(fallback.size()).isEqualTo(2);
    }

    @Test
    void send先经回调报了错又同步抛出_同一条只写一行兜底只计一次() {
        // 幂等生产者进入致命状态时的真实表现：记录先进缓冲、send 抛异常，后台线程中止批次时回调再报一次
        MockProducer<String, byte[]> twice = new MockProducer<>(true, new StringSerializer(), new ByteArraySerializer()) {
            @Override
            public Future<RecordMetadata> send(ProducerRecord<String, byte[]> record, Callback callback) {
                callback.onCompletion(null, new KafkaException("批次被中止"));
                throw new KafkaException("生产者处于致命状态");
            }
        };
        sink = new KafkaBattleResultSink(new KafkaBattleResultSink.Clients() {
            @Override
            public Producer<String, byte[]> producer() {
                return twice;
            }

            @Override
            public TopicAdmin admin() {
                return kafka.admin();
            }
        }, GENERATION, (short) 1, Duration.ofSeconds(1), metrics, 10, nanos::get);
        sink.start();

        sink.publish(event(431));

        await().atMost(WAIT).until(() -> !sink.verified() && twice.closed());
        assertThat(fallback.lines()).hasSize(1);
        assertThat(fallback.lines().get(0).key()).isEqualTo("431");
        assertThat(events("fallback")).isEqualTo(1);
        assertThat(results("plain", "error")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 停机

    @Test
    void 停机_等队列里的发完再关生产者_之后交来的事件写兜底_重复关闭无害() throws Exception {
        started(10);
        kafka.holdSends();
        sink.publish(event(501));
        sink.publish(event(502));
        sink.publish(event(503));
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            kafka.release();
        }, "test-release");
        releaser.start();

        sink.close(Duration.ofSeconds(5));

        assertThat(keys(kafka.sent())).as("关闭返回时队列里的三条都已交给生产者").containsExactly("501", "502", "503");
        assertThat(events("sent")).isEqualTo(3);
        assertThat(kafka.producer(0).closed()).isTrue();
        assertThat(fallback.size()).isZero();

        sink.publish(event(504));
        assertThat(fallback.lines()).hasSize(1);
        assertThat(fallback.lines().get(0).reason()).isEqualTo("shutdown_dropped");
        assertThat(fallback.lines().get(0).event()).isEqualTo(event(504));
        assertThat(events("fallback")).isEqualTo(1);

        assertThatCode(() -> sink.close(Duration.ofSeconds(5))).doesNotThrowAnyException();
        assertThatCode(sink::close).doesNotThrowAnyException();
        releaser.join(2_000);
    }

    @Test
    void 停机_预算内没发完_排着的逐条写兜底_正卡在send里的那条被打断后也写兜底_一条不丢() {
        started(10);
        kafka.holdSends();
        sink.publish(event(511));
        await().atMost(WAIT).until(() -> kafka.sendThreads().size() == 1);
        sink.publish(event(512));
        sink.publish(event(513), Channel.ACTIVITY);

        sink.close(Duration.ofMillis(200));

        List<Line> lines = fallback.lines();
        assertThat(lines).as("关闭返回时三条都已落进兜底日志").hasSize(3);
        assertThat(lines.stream().filter(l -> l.reason().equals("shutdown_dropped")).map(Line::key).toList())
                .containsExactly("512", "513");
        assertThat(lines.stream().filter(l -> l.reason().equals("send_error")).map(Line::key).toList())
                .as("正在发的那一条不在队列里：被打断后由发送线程自己写").containsExactly("511");
        assertThat(lines.stream().filter(l -> l.key().equals("513")).findFirst().orElseThrow().channel()).isEqualTo("activity");
        assertThat(kafka.sent()).isEmpty();
        assertThat(events("fallback")).isEqualTo(3);
        assertThat(events("sent")).isZero();
        assertThat(kafka.producer(0).closed()).isTrue();
    }

    @Test
    void 停机_已交给生产者但还没确认的_关闭生产者时由回调写兜底() {
        kafka.autoComplete = false;
        started(10);
        sink.publish(event(521));
        sink.publish(event(522));
        await().atMost(WAIT).until(() -> kafka.sent().size() == 2);

        sink.close(Duration.ofMillis(200));

        assertThat(fallback.lines()).hasSize(2).allSatisfy(line -> assertThat(line.reason()).isEqualTo("delivery_failed"));
        assertThat(fallback.lines().stream().map(Line::key).toList()).containsExactly("521", "522");
        assertThat(events("fallback")).isEqualTo(2);
        assertThat(events("sent")).isZero();
    }

    @Test
    void 停机撞上迟迟才通过的核对_不再建生产者_那条事件写兜底而不是交给没人关的生产者() {
        kafka.unreachable = true;
        sink(10).start();
        kafka.unreachable = false;
        nanos.addAndGet(REVERIFY_NANOS);
        kafka.holdAdminIgnoringInterrupt();
        sink.publish(event(531));
        await().atMost(WAIT).until(() -> kafka.adminsOpened() == 2 && kafka.adminCalls().get(kafka.adminCalls().size() - 1).equals("partitions"));

        // 预算 100 ms + 打断后再等 1 s：核对还卡着，停机先走完
        sink.close(Duration.ofMillis(100));
        assertThat(kafka.producersMade()).isZero();
        assertThat(fallback.size()).isZero();

        kafka.release();

        await().atMost(WAIT).until(() -> events("not_verified") == 1);
        assertThat(kafka.partitionsOf(TOPIC)).as("核对本身是通过的").isEqualTo(3);
        assertThat(kafka.producersMade()).as("停机之后不再建生产者").isZero();
        assertThat(sink.verified()).isFalse();
        assertThat(fallback.lines()).singleElement().satisfies(line -> {
            assertThat(line.reason()).isEqualTo("not_verified");
            assertThat(line.event()).isEqualTo(event(531));
        });
    }

    @Test
    void 从未核对通过就停机_不再连Kafka_立即返回() {
        kafka.unreachable = true;
        sink(10).start();
        nanos.addAndGet(REVERIFY_NANOS);

        long startedAt = System.nanoTime();
        sink.close(Duration.ofSeconds(5));

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)).isLessThan(2_000);
        assertThat(kafka.adminsOpened()).isEqualTo(1);
        sink.publish(event(541));
        assertThat(fallback.lines()).singleElement().satisfies(line -> assertThat(line.reason()).isEqualTo("shutdown_dropped"));
        assertThat(kafka.adminsOpened()).as("关闭之后交来的事件不触发核对").isEqualTo(1);
    }

    // ------------------------------------------------------------------ 真客户端的装配（不连网络）

    @Test
    void 生产者配置_幂等_acks_all_send最多阻塞两秒_Kafka自己的配置校验通过() {
        Properties props = KafkaBattleResultSink.producerConfig("127.0.0.1:9092", "xm-battle-result-test", KafkaBattleResultSink.MAX_BLOCK);
        assertThat(props.getProperty(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG)).isEqualTo("127.0.0.1:9092");
        assertThat(props.getProperty(ProducerConfig.CLIENT_ID_CONFIG)).isEqualTo("xm-battle-result-test");

        Properties withSerializers = new Properties();
        withSerializers.putAll(props);
        withSerializers.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        withSerializers.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        // ProducerConfig 构造时做幂等相关的交叉校验（acks 必须 all、在途 ≤ 5、重试 > 0），不符即抛
        ProducerConfig config = new ProducerConfig(withSerializers);

        assertThat(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(config.getString(ProducerConfig.ACKS_CONFIG)).as("all 的规范形").isEqualTo("-1");
        assertThat(config.getInt(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION)).isLessThanOrEqualTo(5);
        assertThat(config.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(2_000);
        assertThat(config.getInt(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG)).isEqualTo(120_000);
    }

    @Test
    void 真客户端_地址写错建不出来_管理客户端抛不可达_生产者抛KafkaException_启动不抛且事件写兜底() {
        // 没有端口的地址在解析阶段就被拒，不做 DNS、不连网络
        KafkaBattleResultSink.Clients real = KafkaBattleResultSink.Clients.kafka("没有端口的地址", "xm-battle-result-test");
        assertThatThrownBy(real::admin).isInstanceOf(AuditBrokerUnavailableException.class);
        assertThatThrownBy(real::producer).isInstanceOf(KafkaException.class);

        sink = new KafkaBattleResultSink(real, GENERATION, (short) 1, Duration.ofSeconds(1), metrics, 10, nanos::get);
        assertThatCode(sink::start).doesNotThrowAnyException();
        assertThat(sink.verified()).isFalse();

        sink.publish(event(601));
        await().atMost(WAIT).until(() -> events("not_verified") == 1);
        assertThat(fallback.lines().get(0).event()).isEqualTo(event(601));
    }
}
