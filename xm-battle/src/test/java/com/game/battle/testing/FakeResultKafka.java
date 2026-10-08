package com.game.battle.testing;

import com.game.audit.AuditBrokerUnavailableException;
import com.game.audit.TopicAdmin;
import com.game.audit.TopicSpec;
import com.game.battle.port.kafka.KafkaBattleResultSink;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 对局结果 Kafka 传输的假客户端（{@link KafkaBattleResultSink.Clients}；match-spec §5.4、§15.2）：一个内存里的「broker」
 * （topic → 分区数，可设为连不上）加记录型的 {@code MockProducer}。不连任何网络。线程安全（被测对象在自己的发送线程上调，测试线程读）。
 *
 * <p>用法：
 * <pre>{@code
 * FakeResultKafka kafka = new FakeResultKafka();
 * KafkaBattleResultSink sink = new KafkaBattleResultSink(kafka, 1, (short) 1, Duration.ofSeconds(1), metrics);
 * sink.start();                       // 假 broker 上建出 xm-battle-result-g1（3 分区）
 * sink.publish(event);
 * await().until(() -> kafka.sent().size() == 1);
 * kafka.sent().get(0).key();          // battle_id 的无符号十进制
 * }</pre>
 *
 * <p>可编排的故障：{@link #unreachable}（管理操作一律「连不上」）、{@link #adminFactoryFails} / {@link #producerFactoryFails}（客户端建不出来，
 * 模拟地址解析不了）、{@link #holdSends()}（生产者的 {@code send} 卡住，模拟等元数据）、{@link #holdAdmin()}（核对卡住）、
 * {@link #autoComplete}{@code = false}（投递结局由测试经 {@link #producer(int)} 的 {@code completeNext / errorNext} 给出），
 * 以及直接设某个生产者的 {@code sendException}。
 *
 * <p>起整个 Spring 上下文的测试把 {@link Beans} 加进配置类：{@code BattleConfiguration} 见到 {@code Clients} bean 就不建真 Kafka 客户端。
 */
public final class FakeResultKafka implements KafkaBattleResultSink.Clients {

    /** 生产者收下的一条消息。 */
    public record Sent(String topic, String key, byte[] value) {

        /** 把 value 解回结果事件（解不出来即断言失败）。 */
        public BattleResultEvent event() {
            try {
                return BattleResultEvent.parseFrom(value);
            } catch (InvalidProtocolBufferException e) {
                throw new AssertionError("value 不是 BattleResultEvent 的字节", e);
            }
        }
    }

    /** 一次建 topic 的调用。 */
    public record Created(String topic, int partitions, Map<String, String> configs, short replicationFactor) {
    }

    /** 管理操作一律抛「连不上」（{@link AuditBrokerUnavailableException}）。 */
    public volatile boolean unreachable;
    /** {@link #admin()} 本身抛「连不上」（真实现在地址解析不了时如此）。 */
    public volatile boolean adminFactoryFails;
    /** {@link #producer()} 抛 {@link KafkaException}（真生产者在地址解析不了时构造器如此）。 */
    public volatile boolean producerFactoryFails;
    /** 新建的生产者是否自动确认；false 时由测试对 {@link #producer(int)} 调 {@code completeNext / errorNext}。建生产者时取值。 */
    public volatile boolean autoComplete = true;

    private final Map<String, Integer> topics = new ConcurrentHashMap<>();
    private final List<Created> created = new CopyOnWriteArrayList<>();
    private final List<String> adminCalls = new CopyOnWriteArrayList<>();
    private final List<RecordingProducer> producers = new CopyOnWriteArrayList<>();
    private final List<String> sendThreads = new CopyOnWriteArrayList<>();
    private volatile CountDownLatch sendGate;
    private volatile CountDownLatch adminGate;
    private volatile boolean adminGateIgnoresInterrupt;

    // ------------------------------------------------------------------ broker

    /** 预置一个已存在的 topic（分区数可以故意与契约不符）。 */
    public FakeResultKafka topic(String name, int partitions) {
        topics.put(name, partitions);
        return this;
    }

    /** broker 上这个 topic 的分区数；不存在为 null。 */
    public Integer partitionsOf(String name) {
        return topics.get(name);
    }

    /** 经管理客户端建过的 topic（按调用次序）。 */
    public List<Created> created() {
        return List.copyOf(created);
    }

    /**
     * 管理客户端上发生过的调用（按次序）：{@code open}、{@code partitions}、{@code create}、{@code configs}、{@code alter}、{@code close}。
     * battle 只是生产方，{@code configs} / {@code alter} 不该出现。
     */
    public List<String> adminCalls() {
        return List.copyOf(adminCalls);
    }

    /** 建过几个管理客户端（= 核对过几次）。 */
    public int adminsOpened() {
        return (int) adminCalls.stream().filter("open"::equals).count();
    }

    /** 让之后的核对卡在第一次管理操作上，直到 {@link #release()}；被打断时同真实现一样抛「连不上」。 */
    public void holdAdmin() {
        adminGateIgnoresInterrupt = false;
        adminGate = new CountDownLatch(1);
    }

    /** 同 {@link #holdAdmin()}，但<b>不理会打断</b>——用来排出「停机已经走完、核对才姗姗来迟地通过」的时序。 */
    public void holdAdminIgnoringInterrupt() {
        adminGateIgnoresInterrupt = true;
        adminGate = new CountDownLatch(1);
    }

    // ------------------------------------------------------------------ 生产者

    /** 建过几个生产者。 */
    public int producersMade() {
        return producers.size();
    }

    /** 第 {@code index} 个建出来的生产者（从 0 起）。 */
    public MockProducer<String, byte[]> producer(int index) {
        return producers.get(index);
    }

    /** 全部生产者收下的消息（按生产者的建立次序、各自的收下次序）。 */
    public List<Sent> sent() {
        List<Sent> out = new ArrayList<>();
        for (RecordingProducer producer : producers) {
            for (ProducerRecord<String, byte[]> record : producer.history()) {
                out.add(new Sent(record.topic(), record.key(), record.value()));
            }
        }
        return out;
    }

    /** 每次进入生产者 {@code send} 时所在线程的名字（含后来卡住或抛了异常的调用）。 */
    public List<String> sendThreads() {
        return List.copyOf(sendThreads);
    }

    /** 让之后每次 {@code send} 一进来就卡住，直到 {@link #release()}；被打断时同真生产者一样抛 {@link InterruptException}。 */
    public void holdSends() {
        sendGate = new CountDownLatch(1);
    }

    /** 放开 {@link #holdSends()} 与 {@link #holdAdmin()}（没卡着时无害）。 */
    public void release() {
        CountDownLatch send = sendGate;
        sendGate = null;
        if (send != null) {
            send.countDown();
        }
        CountDownLatch admin = adminGate;
        adminGate = null;
        if (admin != null) {
            admin.countDown();
        }
    }

    // ------------------------------------------------------------------ Clients

    @Override
    public Producer<String, byte[]> producer() {
        if (producerFactoryFails) {
            throw new KafkaException("Failed to construct kafka producer",
                    new ConfigException("No resolvable bootstrap urls given in bootstrap.servers"));
        }
        RecordingProducer producer = new RecordingProducer(autoComplete);
        producers.add(producer);
        return producer;
    }

    @Override
    public TopicAdmin admin() {
        if (adminFactoryFails) {
            throw new AuditBrokerUnavailableException("创建 Kafka 管理客户端失败: No resolvable bootstrap urls", null);
        }
        adminCalls.add("open");
        return new Admin();
    }

    /** 假的 topic 管理：读写上面那份内存 broker。 */
    private final class Admin implements TopicAdmin {

        private void reach() {
            CountDownLatch gate = adminGate;
            if (gate != null) {
                if (adminGateIgnoresInterrupt) {
                    awaitUninterruptibly(gate);
                } else {
                    try {
                        gate.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AuditBrokerUnavailableException("查询 topic 被中断", e);
                    }
                }
            }
            if (unreachable) {
                throw new AuditBrokerUnavailableException("连不上", null);
            }
        }

        @Override
        public Map<String, Integer> partitionCounts(Collection<String> names, Duration timeout) {
            adminCalls.add("partitions");
            reach();
            Map<String, Integer> out = new HashMap<>();
            for (String name : names) {
                Integer partitions = topics.get(name);
                if (partitions != null) {
                    out.put(name, partitions);
                }
            }
            return out;
        }

        @Override
        public void create(Collection<TopicSpec> specs, short replicationFactor, Duration timeout) {
            adminCalls.add("create");
            reach();
            for (TopicSpec spec : specs) {
                created.add(new Created(spec.name(), spec.partitions(), spec.configs(), replicationFactor));
                topics.putIfAbsent(spec.name(), spec.partitions());
            }
        }

        @Override
        public Map<String, String> configs(String topic, Collection<String> keys, Duration timeout) {
            adminCalls.add("configs");
            reach();
            return Map.of();
        }

        @Override
        public void alterConfigs(String topic, Map<String, String> configs, Duration timeout) {
            adminCalls.add("alter");
            reach();
        }

        @Override
        public void close() {
            adminCalls.add("close");
        }
    }

    /** 记录型生产者：记下每次 {@code send} 所在的线程，可卡住；关闭时同真生产者一样把还没确认的以异常回调掉。 */
    private final class RecordingProducer extends MockProducer<String, byte[]> {

        RecordingProducer(boolean autoComplete) {
            super(autoComplete, new StringSerializer(), new ByteArraySerializer());
        }

        /** 不加 synchronized：卡住期间不能占着 MockProducer 的锁（测试线程还要读 history、给投递结局）。 */
        @Override
        public Future<RecordMetadata> send(ProducerRecord<String, byte[]> record, Callback callback) {
            sendThreads.add(Thread.currentThread().getName());
            CountDownLatch gate = sendGate;
            if (gate != null) {
                try {
                    gate.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new InterruptException(e);
                }
            }
            return super.send(record, callback);
        }

        @Override
        public void close(Duration timeout) {
            // 真生产者关闭（超时后强关）时，还没确认的批次以异常回调掉；MockProducer 不会，这里补上
            while (errorNext(new KafkaException("Producer is closed forcefully."))) {
                // 逐条回调
            }
            super.close(timeout);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch gate) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        try {
            while (true) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return;
                }
                try {
                    if (gate.await(left, TimeUnit.NANOSECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 起整个 Spring 上下文的测试用：{@code @SpringBootTest(classes = {BattleApplication.class, FakeResultKafka.Beans.class})}，
     * 然后 {@code @Autowired FakeResultKafka} 读生产者收到的消息。{@code @TestConfiguration} 不会被 {@code BattleApplication} 的组件扫描捡走。
     */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Beans {

        @Bean
        public FakeResultKafka fakeResultKafka() {
            return new FakeResultKafka();
        }
    }
}
