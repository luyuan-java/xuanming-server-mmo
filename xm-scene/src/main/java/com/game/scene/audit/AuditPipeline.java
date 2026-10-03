package com.game.scene.audit;

import com.game.audit.AuditBrokerUnavailableException;
import com.game.audit.AuditKeys;
import com.game.audit.AuditTopicContractException;
import com.game.audit.AuditTopicInitializer;
import com.game.audit.TopicAdmin;
import com.game.audit.TopicSpec;
import com.game.audit.proto.TransactionLogRecord;
import com.game.scene.id.SceneGuids;
import com.game.scene.metrics.SceneMetrics;
import com.game.scene.metrics.SceneMetrics.AuditKind;
import com.game.scene.metrics.SceneMetrics.AuditResult;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.util.List;
import java.util.OptionalLong;
import java.util.Properties;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
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
 * 场景节点里唯一碰 Kafka 的地方：审计记录（资产流水）经一条专用的 {@code scene-audit} 线程发往 Kafka。
 *
 * <ul>
 *   <li>{@link #submitTransaction} 任意线程可调（实际是场景逻辑线程），只把不可变的草稿投进有界队列，从不阻塞、从不抛异常——
 *       审计异常绝不能冒到请求处理器里（钱包已经改了，处理器再失败只会让客户端以为没成）；</li>
 *   <li>发号、序列化、{@code producer.send}（可能因元数据 / 缓冲阻塞到 {@code max.block.ms}）都在审计线程上；
 *       单线程保证了同一玩家的记录按逻辑线程的产生顺序进同一分区；</li>
 *   <li>topic 核对通过之前不发（broker 若开着自动建 topic，会建出默认分区数、契约永久失配），记录走兜底日志；</li>
 *   <li>没被 Kafka 确认的每条记录（队列满 / 发不出号 / 未核对 / 发送失败 / 投递失败 / 停服未发完）都完整写进兜底日志
 *       {@code xm.audit.fallback} 并计数——资产照改，流水不丢。</li>
 * </ul>
 */
public final class AuditPipeline {

    private static final Logger log = LoggerFactory.getLogger(AuditPipeline.class);

    private final Supplier<Producer<String, byte[]>> producerFactory;
    /**
     * 生产者：第一次核对通过时才建（地址解析不了时构造器就会抛，不能让它挡住启动）；进入致命错误状态后丢弃，
     * 下一次核对重建。写在核对线程上、读在审计线程上（volatile；verified 置真之前已赋值）。
     */
    private volatile Producer<String, byte[]> producer;
    private final Supplier<TopicAdmin> adminFactory;
    private final List<TopicSpec> topics;
    private final String transactionTopic;
    private final short replicationFactor;
    private final Duration verifyTimeout;
    private final SceneGuids guids;
    private final SceneMetrics metrics;
    private final AuditFallbackLog fallback = new AuditFallbackLog();
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean verifyQueued = new AtomicBoolean();
    private volatile boolean verified;

    /**
     * @param topics           要核对的全部审计 topic（{@code AuditTopics.all(generation)}）
     * @param transactionTopic 资产流水 topic 名（属于 {@code topics}）
     * @param queueCapacity    审计线程队列上限；满了新记录走兜底日志
     */
    public AuditPipeline(Supplier<Producer<String, byte[]>> producerFactory, Supplier<TopicAdmin> adminFactory,
                         List<TopicSpec> topics,
                         String transactionTopic, short replicationFactor, Duration verifyTimeout, SceneGuids guids,
                         int queueCapacity, SceneMetrics metrics) {
        this.producerFactory = producerFactory;
        this.adminFactory = adminFactory;
        this.topics = List.copyOf(topics);
        this.transactionTopic = transactionTopic;
        this.replicationFactor = replicationFactor;
        this.verifyTimeout = verifyTimeout;
        this.guids = guids;
        this.metrics = metrics;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(queueCapacity),
                new DefaultThreadFactory("scene-audit", true), new ThreadPoolExecutor.AbortPolicy());
        metrics.bindAuditExecutor(executor);
    }

    /**
     * 生产方的 Kafka 生产者：幂等（acks=all，同一会话内重试不重复、不乱序），{@code max.block.ms} 限住审计线程的最长阻塞。
     */
    public static Producer<String, byte[]> kafkaProducer(String bootstrapServers, String clientId, Duration maxBlock) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, "5");
        props.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, Long.toString(maxBlock.toMillis()));
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "120000");
        return new KafkaProducer<>(props, new StringSerializer(), new ByteArraySerializer());
    }

    // ------------------------------------------------------------------ topic 核对

    /**
     * 核对审计 topic（缺就建）。通过后才开始真正发送。连不上 broker 记 WARN 返回 false（之后 {@link #requestVerify} 重试）；
     * 分区契约不符抛 {@link AuditTopicContractException}（启动时调用方据此拒绝启动）。
     */
    public boolean verifyNow() {
        try (TopicAdmin admin = adminFactory.get()) {
            AuditTopicInitializer.ensure(admin, topics, AuditTopicInitializer.Mode.CREATE_AND_VERIFY, replicationFactor,
                    verifyTimeout);
            if (producer == null) {
                producer = producerFactory.get();
            }
            verified = true;
            return true;
        } catch (AuditBrokerUnavailableException | KafkaException e) {
            // 连不上、地址解析不了、生产者建不出来：都是可恢复的，之后重试（契约不符照样抛出）
            log.warn("审计 topic 暂时核对不了（Kafka 不可达），在此之前的资产流水只写兜底日志：{}", e.getMessage());
            return false;
        }
    }

    /**
     * 还没核对通过就在审计线程上再试一次（调度线程每 30 秒调一次，只投递、不阻塞）。已核对通过时立即返回——
     * 生产者进入致命状态被丢弃后，核对标记会被清掉，下一次就在这里重建。
     */
    public void requestVerify() {
        if (verified || !verifyQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    verifyNow();
                } catch (AuditTopicContractException e) {
                    log.error("审计 topic 与契约不符，资产流水继续只写兜底日志，需人工处理：{}", e.getMessage());
                } catch (RuntimeException e) {
                    log.warn("审计 topic 核对失败", e);
                } finally {
                    verifyQueued.set(false);
                }
            });
        } catch (RejectedExecutionException e) {
            verifyQueued.set(false);
        }
    }

    public boolean verified() {
        return verified;
    }

    // ------------------------------------------------------------------ 提交

    /** 交一条资产流水草稿（{@code tx_id} 为 0，发号在审计线程上）。从不阻塞、从不抛异常。 */
    public void submitTransaction(TransactionLogRecord draft) {
        try {
            executor.execute(new TransactionTask(draft));
        } catch (RejectedExecutionException e) {
            lost(draft, AuditResult.QUEUE_FULL);
        } catch (RuntimeException e) {
            log.error("提交资产流水失败", e);
            lost(draft, AuditResult.SEND_ERROR);
        }
    }

    /** 审计线程上：发号、发送；结局在 Kafka 回调里计。 */
    private void send(TransactionLogRecord draft) {
        if (!verified) {
            lost(draft, AuditResult.UNVERIFIED);
            return;
        }
        OptionalLong txId = guids.tryNext();
        if (txId.isEmpty()) {
            lost(draft, AuditResult.NO_ID);
            return;
        }
        TransactionLogRecord record = draft.toBuilder().setTxId(txId.getAsLong()).build();
        ProducerRecord<String, byte[]> message = new ProducerRecord<>(transactionTopic,
                AuditKeys.transactionKey(record.getFromPlayer(), record.getToPlayer()), record.toByteArray());
        // 每条结局只计一次：生产者处于致命状态时 send 先把记录放进缓冲再抛异常，随后后台线程中止批次时回调还会再报一次
        AtomicBoolean settled = new AtomicBoolean();
        Producer<String, byte[]> current = producer;
        try {
            current.send(message, (metadata, error) -> {
                if (!settled.compareAndSet(false, true)) {
                    return;
                }
                if (error == null) {
                    metrics.auditRecord(AuditKind.TRANSACTION, AuditResult.ACKED);
                } else {
                    log.warn("资产流水投递失败 tx_id={}：{}", Long.toUnsignedString(record.getTxId()), error.toString());
                    lost(record, AuditResult.DELIVERY_FAILED);
                }
            });
        } catch (RuntimeException e) {
            if (settled.compareAndSet(false, true)) {
                log.warn("资产流水发送失败 tx_id={}：{}", Long.toUnsignedString(record.getTxId()), e.toString());
                lost(record, AuditResult.SEND_ERROR);
            }
            if (e instanceof KafkaException && !(e instanceof InterruptException)) {
                discardProducer(current, e);
            }
        }
    }

    /**
     * send 同步抛 KafkaException（不是中断）说明生产者已不可用（幂等生产者进入致命状态、已被关闭……），之后每条都会失败：
     * 丢弃它并清掉核对标记，由每 30 秒一次的核对重建（自带冷却，不会每条都重建）。只在审计线程上调用。
     */
    private void discardProducer(Producer<String, byte[]> broken, RuntimeException cause) {
        if (producer != broken) {
            return;
        }
        log.error("Kafka 生产者不可用，丢弃并在下次核对时重建（期间资产流水写兜底日志）：{}", cause.toString());
        verified = false;
        producer = null;
        try {
            broken.close(Duration.ZERO);
        } catch (RuntimeException e) {
            log.warn("关闭不可用的生产者失败：{}", e.toString());
        }
    }

    private void lost(TransactionLogRecord record, AuditResult result) {
        fallback.transaction(record, result);
        metrics.auditRecord(AuditKind.TRANSACTION, result);
    }

    // ------------------------------------------------------------------ 停服

    /**
     * 有界地发完：先让审计线程把队列里的发出去，再关生产者（等在途的确认；超时未确认的回调按投递失败写兜底），
     * 预算用完还排着的逐条写兜底日志。之后才能交还发号租约（否则别的实例可能拿到同一个 worker）。
     */
    public void close(Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        executor.shutdown();
        try {
            executor.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        Producer<String, byte[]> current = producer;
        if (current != null) {
            current.close(Duration.ofNanos(remainingNanos(deadline)));
        }
        if (!executor.isTerminated()) {
            List<Runnable> left = executor.shutdownNow();
            for (Runnable task : left) {
                if (task instanceof TransactionTask t) {
                    lost(t.draft, AuditResult.SHUTDOWN_DROPPED);
                }
            }
            log.error("审计线程在停服预算内没发完，{} 条记录写进兜底日志", left.size());
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    /** 带着草稿的任务：停服时没执行的也能逐条写兜底日志。 */
    private final class TransactionTask implements Runnable {
        final TransactionLogRecord draft;

        TransactionTask(TransactionLogRecord draft) {
            this.draft = draft;
        }

        @Override
        public void run() {
            try {
                send(draft);
            } catch (RuntimeException e) {
                log.error("资产流水处理异常", e);
                lost(draft, AuditResult.SEND_ERROR);
            }
        }
    }
}
