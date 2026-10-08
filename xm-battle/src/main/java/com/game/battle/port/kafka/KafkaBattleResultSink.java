package com.game.battle.port.kafka;

import com.game.audit.AuditBrokerUnavailableException;
import com.game.audit.AuditTopicContractException;
import com.game.audit.AuditTopicInitializer;
import com.game.audit.BattleResultTopics;
import com.game.audit.KafkaTopicAdmin;
import com.game.audit.TopicAdmin;
import com.game.battle.metrics.BattleMetrics;
import com.game.battle.metrics.BattleMetrics.ResultChannel;
import com.game.battle.metrics.BattleMetrics.ResultEvent;
import com.game.battle.metrics.BattleMetrics.ResultOutcome;
import com.game.battle.port.BattleResultSink;
import com.game.battle.port.kafka.BattleResultFallbackLog.Reason;
import com.game.proto.contracts.kafka.BattleResultEvent;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InterruptException;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 对局结果事件的 Kafka 传输（批次 6.4；match-spec §5.4，落实 battle-node-spec Q12；基线 {@code room.cpp:1181-1211} 发 {@code match-results}）。
 * topic {@code xm-battle-result-g<代次>}（规格 {@link BattleResultTopics}：3 分区、保留 7 天），key = battle_id 的无符号十进制，
 * value = 契约 {@code contracts.kafka.BattleResultEvent} 的字节。消费方是 xm-match 的评分（按 battle_id 幂等；4.6 起 xm-guild 用自己的消费组读活动局）。
 * 模式同 scene 的审计管线（{@code AuditPipeline}，architecture.md §4.5）。
 *
 * <p><b>线程</b>：
 * <ul>
 *   <li>{@link #publish} 任意线程可调、<b>线程安全</b>——实际有两条：普通局在 {@code battle-logic} 上、活动局在 {@code battle-outbox} 上
 *       （首发 + 每 10 s 原对象重发，一局至多 31 次）。它只把不可变的事件对象投进有界队列（{@value #QUEUE_CAPACITY}），不阻塞、不抛异常；</li>
 *   <li>序列化、{@code producer.send}（会因元数据 / 缓冲阻塞到 {@code max.block.ms}，所以<b>绝不能</b>在逻辑线程上调——更正 battle-node-spec §10.5）、
 *       没核对通过时的再次核对、丢弃不可用的生产者，都在专用的 {@value #THREAD_NAME} 线程上；单线程保证同一条发布线程交来的事件按交来的次序发出
 *       （同一局的活动重发因此保序）；</li>
 *   <li>投递结局的回调在 Kafka 生产者自己的发送线程上，只计数、写日志。</li>
 * </ul>
 *
 * <p><b>topic 核对</b>：核对通过之前<b>不发</b>（broker 若开着自动建 topic，会建出默认分区数，契约永久失配）。battle 是生产方，只「缺就按规格建、
 * 存在就核对分区数」（{@link AuditTopicInitializer.Mode#CREATE_AND_VERIFY}）；保留期由主人 xm-match 校正。第一次核对由 {@link #start} 在启动线程上
 * 同步做：分区数不符抛 {@link AuditTopicContractException}（调用方拒绝启动）；Kafka 不可达（含地址解析不了）最多等 {@code verifyTimeout}，
 * 告警后照常启动。之后<b>没有定时器</b>：没核对通过时，下一条事件到来且距上次核对已满 {@link #REVERIFY_INTERVAL} 就在发送线程上再核对一次
 * （没有事件要发就不必连 Kafka；Kafka 恢复后的第一条事件就能发出去，不用等下一个定时点）。
 *
 * <p><b>不丢</b>：没被 Kafka 确认的每条事件——队列满、未核对、发送失败、投递失败、停服没发完——都把<b>完整字节</b>写进兜底日志
 * {@value BattleResultFallbackLog#LOGGER}（可回灌），并计 {@code xm_battle_result_events_total{result = fallback / not_verified}}；确认的计
 * {@code sent}。每次 {@code publish} 恰好落在这三者之一。另按通道计 {@code xm_battle_results_total{channel, result = sent / error}}
 * （{@link BattleResultSink} 的契约：plain 只统计普通局，活动局的首发与重发都计 activity）。
 *
 * <p><b>只在真正打完的局发</b>是调用方的义务（match-spec §12.1 坑 10）：Destroy / 停机作废不调，{@code RoomOrigin.DEV} / {@code DEV_GATHER}
 * 的房间不调——否则 dev 接口会变成刷分的口子。本类不知道房间来源，收到什么发什么。
 */
public final class KafkaBattleResultSink implements BattleResultSink, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaBattleResultSink.class);

    /** 发送线程的名字（进程里只有一条）。 */
    public static final String THREAD_NAME = "battle-result-out";
    /** 发送队列的上限（match-spec §5.4）：满了新事件走兜底日志。正在发的那一条不占队列。 */
    public static final int QUEUE_CAPACITY = 1024;
    /** 没核对通过时，两次核对之间的最短间隔（同 scene 审计管线的 30 s）。 */
    static final Duration REVERIFY_INTERVAL = Duration.ofSeconds(30);
    /** {@code producer.send} 的最长阻塞（元数据 / 缓冲；只阻塞发送线程，同 scene 审计的缺省值）。 */
    static final Duration MAX_BLOCK = Duration.ofSeconds(2);
    /** 停机时等「队列发完 + 在途确认回来」的上限；超出的写兜底日志。 */
    static final Duration CLOSE_BUDGET = Duration.ofSeconds(3);
    /** 停机预算用完、打断发送线程之后，再等它把手上那一条写进兜底日志的上限。 */
    static final Duration CLOSE_GRACE = Duration.ofSeconds(1);

    /** 建 Kafka 客户端的口（测试换成 {@code MockProducer} 与假的 {@link TopicAdmin}）。两个方法都可能被调多次，每次返回新的客户端。 */
    public interface Clients {

        /** 新建一个生产者（地址解析不了时抛 {@link KafkaException}，按「暂时不可达」处理）。 */
        Producer<String, byte[]> producer();

        /** 新建一个 topic 管理客户端（一次核对用完即关；建不出来抛 {@link AuditBrokerUnavailableException}）。 */
        TopicAdmin admin();

        /** 真 Kafka：幂等生产者（见 {@link #kafkaProducer}）与 {@link KafkaTopicAdmin}。 */
        static Clients kafka(String bootstrapServers, String clientId) {
            Objects.requireNonNull(bootstrapServers, "bootstrapServers");
            Objects.requireNonNull(clientId, "clientId");
            return new Clients() {
                @Override
                public Producer<String, byte[]> producer() {
                    return kafkaProducer(bootstrapServers, clientId, MAX_BLOCK);
                }

                @Override
                public TopicAdmin admin() {
                    return new KafkaTopicAdmin(bootstrapServers, clientId + "-admin");
                }
            };
        }
    }

    private final Clients clients;
    private final int generation;
    private final String topic;
    private final short replicationFactor;
    private final Duration verifyTimeout;
    private final BattleMetrics metrics;
    private final LongSupplier nanoClock;
    private final BattleResultFallbackLog fallback = new BattleResultFallbackLog();
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean closed = new AtomicBoolean();
    /**
     * 护住「建生产者」与「停机时取走生产者」这两步（都很短，不含任何 Kafka I/O）：停机置位之后不会再建出新的生产者，停机之前建好的一定被停机关掉——
     * 否则停机恰好撞上一次迟迟才通过的核对时，会留下一个没人关的生产者，交给它的那条事件既等不到确认、也没有兜底行。
     */
    private final Object producerLock = new Object();

    /**
     * 生产者：第一次核对通过时才建（地址解析不了时构造器就会抛，不能让它挡住启动）；进入致命错误状态后丢弃，下一次核对重建。
     * 写在核对所在的线程上（启动线程先、发送线程后），读在发送线程与停机线程上（volatile；{@link #verified} 置真之前已赋值）。
     */
    private volatile Producer<String, byte[]> producer;
    private volatile boolean verified;
    /** 上一次核对结束的时刻（单调时钟）；{@link #verifyAttempted} 为假时无意义。 */
    private volatile long lastVerifyNanos;
    private volatile boolean verifyAttempted;

    /**
     * @param clients           Kafka 客户端的工厂（生产 {@link Clients#kafka}）
     * @param generation        topic 代次（{@code xm.battle.result.topic-generation}，与 xm-match 一致）
     * @param replicationFactor 新建 topic 的副本数
     * @param verifyTimeout     一次核对的上限（{@code xm.battle.result.init-timeout}）
     */
    public KafkaBattleResultSink(Clients clients, int generation, short replicationFactor, Duration verifyTimeout,
                                 BattleMetrics metrics) {
        this(clients, generation, replicationFactor, verifyTimeout, metrics, QUEUE_CAPACITY, System::nanoTime);
    }

    /** 测试用：可注入队列上限与单调时钟（纳秒）。 */
    KafkaBattleResultSink(Clients clients, int generation, short replicationFactor, Duration verifyTimeout, BattleMetrics metrics,
                          int queueCapacity, LongSupplier nanoClock) {
        this.clients = Objects.requireNonNull(clients, "clients");
        this.generation = generation;
        this.topic = BattleResultTopics.name(generation);
        this.replicationFactor = replicationFactor;
        this.verifyTimeout = Objects.requireNonNull(verifyTimeout, "verifyTimeout");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("队列上限必须 ≥ 1: " + queueCapacity);
        }
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(queueCapacity), task -> {
            Thread thread = new Thread(task, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 生产方的 Kafka 生产者：幂等（{@code acks = all}，同一会话内重试不重复、不乱序），{@code max.block.ms} 限住发送线程的最长阻塞。
     * 配置同 scene 的审计生产者。
     */
    static Producer<String, byte[]> kafkaProducer(String bootstrapServers, String clientId, Duration maxBlock) {
        return new KafkaProducer<>(producerConfig(bootstrapServers, clientId, maxBlock), new StringSerializer(), new ByteArraySerializer());
    }

    /** 生产者的配置（单列出来便于单测钉住幂等 / acks / 阻塞上限这几项）。 */
    static Properties producerConfig(String bootstrapServers, String clientId, Duration maxBlock) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Long.toString(maxBlock.toMillis()));
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "120000");
        return props;
    }

    // ------------------------------------------------------------------ topic 核对

    /**
     * 启动期核对：在把本对象交给任何调用方<b>之前</b>、启动线程上调一次。Kafka 不可达时最多阻塞 {@code verifyTimeout}，告警后返回
     * （之后由发送线程按 {@link #REVERIFY_INTERVAL} 再试，期间的结果事件写兜底日志）。核对有了结论之后顺带把发送线程建好，
     * 免得第一条结果事件到来时在逻辑线程上建线程。
     *
     * @throws AuditTopicContractException 分区数与契约不符：调用方应拒绝启动（升 {@value BattleResultTopics#GENERATION_ENV} 换新 topic）
     */
    public void start() {
        boolean ok = verifyNow();
        executor.prestartCoreThread();
        log.info("对局结果发送就绪 topic={} 已核对={}{}", topic, ok,
                ok ? "" : "（Kafka 不可达：结果事件先写兜底日志 " + BattleResultFallbackLog.LOGGER + "，有事件要发时每 "
                        + REVERIFY_INTERVAL.toSeconds() + " s 再核对一次）");
    }

    /**
     * 核对 topic（缺就建），通过后才开始真正发送。连不上 broker、地址解析不了、生产者建不出来：记 WARN 返回 false（可恢复）；
     * 分区契约不符抛 {@link AuditTopicContractException}。只在一条线程上调（启动线程先；本对象交出去之后只有发送线程），所以核对本身不加锁——
     * 它最长阻塞 {@code verifyTimeout}，停机不能等它；只有「建生产者」那一步与停机互斥（见 {@link #producerLock}）。停机已开始时即使核对通过也不再建
     * 生产者，返回 false（那条事件写兜底日志）。
     */
    private boolean verifyNow() {
        try (TopicAdmin admin = clients.admin()) {
            BattleResultTopics.ensure(admin, generation, AuditTopicInitializer.Mode.CREATE_AND_VERIFY, replicationFactor, verifyTimeout);
            synchronized (producerLock) {
                if (closed.get()) {
                    return false;
                }
                if (producer == null) {
                    producer = clients.producer();
                }
                verified = true;
            }
            return true;
        } catch (AuditBrokerUnavailableException | KafkaException e) {
            log.warn("对局结果 topic {} 暂时核对不了（Kafka 不可达），在此之前的结果事件只写兜底日志：{}", topic, e.getMessage());
            return false;
        } finally {
            lastVerifyNanos = nanoClock.getAsLong();
            verifyAttempted = true;
        }
    }

    /**
     * 发送线程上：已核对通过立即返回；否则距上次核对满 {@link #REVERIFY_INTERVAL}（或从没核对过）就再核对一次。停机中不再核对
     * （连不上时会把排空预算耗在等 Kafka 上）。
     */
    private boolean ensureVerified() {
        if (verified) {
            return true;
        }
        if (closed.get()) {
            return false;
        }
        if (verifyAttempted && nanoClock.getAsLong() - lastVerifyNanos < REVERIFY_INTERVAL.toNanos()) {
            return false;
        }
        try {
            return verifyNow();
        } catch (AuditTopicContractException e) {
            log.error("对局结果 topic 与契约不符，结果事件继续只写兜底日志，需人工处理：{}", e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("对局结果 topic 核对失败", e);
            return false;
        }
    }

    /** topic 是否已核对通过（通过之前不发）。 */
    public boolean verified() {
        return verified;
    }

    /** 本进程发往的 topic 名（{@code xm-battle-result-g<代次>}）。 */
    public String topic() {
        return topic;
    }

    // ------------------------------------------------------------------ 发布

    /** 交一条结果事件。任意线程可调，线程安全；从不阻塞、从不抛异常。 */
    @Override
    public void publish(BattleResultEvent event, Channel channel) {
        if (event == null) {
            log.error("对局结果事件为空，忽略（调用方的缺陷）");
            return;
        }
        Channel lane = channel == null ? Channel.PLAIN : channel;
        try {
            executor.execute(new SendTask(event, lane));
        } catch (RejectedExecutionException e) {
            lost(event, lane, executor.isShutdown() ? Reason.SHUTDOWN_DROPPED : Reason.QUEUE_FULL);
        } catch (RuntimeException e) {
            log.error("提交对局结果失败 battle_id={}", BattleResultTopics.key(event.getBattleId()), e);
            lost(event, lane, Reason.SEND_ERROR);
        }
    }

    /** 发送线程上：序列化、发送；结局在 Kafka 回调里计。 */
    private void send(BattleResultEvent event, Channel channel) {
        Producer<String, byte[]> current = ensureVerified() ? producer : null;
        if (current == null) {
            lost(event, channel, Reason.NOT_VERIFIED);
            return;
        }
        String key = BattleResultTopics.key(event.getBattleId());
        ProducerRecord<String, byte[]> message = new ProducerRecord<>(topic, key, event.toByteArray());
        // 每条结局只计一次：生产者处于致命状态时 send 先把记录放进缓冲再抛异常，随后后台线程中止批次时回调还会再报一次
        AtomicBoolean settled = new AtomicBoolean();
        try {
            current.send(message, (metadata, error) -> {
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                if (error == null) {
                    sent(channel);
                } else {
                    log.warn("对局结果投递失败 battle_id={}：{}", key, error.toString());
                    lost(event, channel, Reason.DELIVERY_FAILED);
                }
            });
        } catch (RuntimeException e) {
            if (settled.compareAndSet(false, true)) {
                log.warn("对局结果发送失败 battle_id={}：{}", key, e.toString());
                lost(event, channel, Reason.SEND_ERROR);
            }
            if (e instanceof KafkaException && !(e instanceof InterruptException)) {
                discardProducer(current, e);
            }
        }
    }

    /**
     * send 同步抛 KafkaException（不是中断）说明生产者已不可用（幂等生产者进入致命状态……），之后每条都会失败：丢弃它并清掉核对标记，
     * 下一次核对重建（核对自带 {@link #REVERIFY_INTERVAL} 的冷却，不会每条都重建）。等元数据 / 缓冲超时不在此列——真生产者把那类错误交给回调，
     * 生产者本身还能用。只在发送线程上调用。
     */
    private void discardProducer(Producer<String, byte[]> broken, RuntimeException cause) {
        if (producer != broken) {
            return;
        }
        log.error("Kafka 生产者不可用，丢弃并在下次核对时重建（期间对局结果写兜底日志）：{}", cause.toString());
        verified = false;
        producer = null;
        try {
            broken.close(Duration.ZERO);
        } catch (RuntimeException e) {
            log.warn("关闭不可用的生产者失败：{}", e.toString());
        }
    }

    private void sent(Channel channel) {
        metrics.resultEvent(ResultEvent.SENT);
        metrics.result(channelOf(channel), ResultOutcome.SENT);
    }

    private void lost(BattleResultEvent event, Channel channel, Reason reason) {
        fallback.lost(reason, channel, topic, event);
        metrics.resultEvent(reason == Reason.NOT_VERIFIED ? ResultEvent.NOT_VERIFIED : ResultEvent.FALLBACK);
        metrics.result(channelOf(channel), ResultOutcome.ERROR);
    }

    private static ResultChannel channelOf(Channel channel) {
        return channel == Channel.ACTIVITY ? ResultChannel.ACTIVITY : ResultChannel.PLAIN;
    }

    // ------------------------------------------------------------------ 停机

    /** 按 {@link #CLOSE_BUDGET} 有界地发完（Spring 销毁 bean 时调：在 battle 节点停机、{@code battle-outbox} 线程停掉之后）。幂等。 */
    @Override
    public void close() {
        close(CLOSE_BUDGET);
    }

    /**
     * 有界地发完：不再接新事件（之后交来的直接写兜底）→ 等发送线程把队列里的发出去 → 关生产者（等在途的确认；预算用完仍未确认的由回调按投递失败
     * 写兜底）→ 预算用完还排着的逐条写兜底日志；发送线程手上正在发的那一条不在队列里，它被中断或撞上已关闭的生产者后自己写兜底行，
     * 最后再等它 {@link #CLOSE_GRACE}（发送线程是守护线程，进程随后就退出）。幂等。
     */
    void close(Duration budget) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        long deadline = System.nanoTime() + budget.toNanos();
        executor.shutdown();
        awaitSendThread(remainingNanos(deadline));
        Producer<String, byte[]> current;
        synchronized (producerLock) {
            // 置位在前、取走在后：这之后核对再通过也不会建新的生产者（verifyNow 在同一把锁里看 closed）
            current = producer;
        }
        if (current != null) {
            try {
                current.close(Duration.ofNanos(remainingNanos(deadline)));
            } catch (RuntimeException e) {
                log.warn("关闭对局结果的 Kafka 生产者出错（继续停机）：{}", e.toString());
            }
        }
        int dropped = 0;
        if (!executor.isTerminated()) {
            List<Runnable> left = executor.shutdownNow();
            for (Runnable task : left) {
                if (task instanceof SendTask pending) {
                    lost(pending.event, pending.channel, Reason.SHUTDOWN_DROPPED);
                    dropped++;
                }
            }
            boolean stopped = awaitSendThread(CLOSE_GRACE.toNanos());
            log.error("对局结果发送线程在停机预算 {} 内没发完，排着的 {} 条结果事件写进兜底日志，发送线程已停={}", budget, dropped, stopped);
        }
        log.info("对局结果发送已关闭 topic={} 停机时丢进兜底日志={}", topic, dropped);
    }

    /** 等发送线程退出，最多 {@code nanos}；返回它是否已退出。等待被中断时保留中断标记并立即返回。 */
    private boolean awaitSendThread(long nanos) {
        try {
            return executor.awaitTermination(nanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return executor.isTerminated();
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /** 带着事件的任务：停机时没执行的也能逐条写兜底日志。 */
    private final class SendTask implements Runnable {
        final BattleResultEvent event;
        final Channel channel;

        SendTask(BattleResultEvent event, Channel channel) {
            this.event = event;
            this.channel = channel;
        }

        @Override
        public void run() {
            try {
                send(event, channel);
            } catch (RuntimeException e) {
                log.error("对局结果处理异常 battle_id={}", BattleResultTopics.key(event.getBattleId()), e);
                lost(event, channel, Reason.SEND_ERROR);
            }
        }
    }
}
