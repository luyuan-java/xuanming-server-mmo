package com.game.data;

import com.game.audit.AuditTopicContractException;
import com.game.audit.AuditProperties;
import com.game.audit.AuditTopicInitializer;
import com.game.audit.AuditTopics;
import com.game.audit.TopicAdmin;
import com.game.data.consume.ConsumerLoop;
import com.game.data.metrics.DataMetrics;
import com.game.data.store.TransactionLogMapper;
import com.game.data.txlog.TransactionLogDecoder;
import com.game.data.txlog.TransactionLogSink;
import java.time.Clock;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * xm-data 的运行时：启动时核对审计 topic（{@link AuditTopicInitializer.Mode#OWN}：缺就建、分区数不符拒绝启动、配置校正到规格），
 * 然后为每个审计 topic 起一条消费线程。第一次核对在启动线程上同步做（契约不符才能拒绝启动）；Kafka 不可达（含地址解析不了）
 * 时最多等 {@code xm.audit.init-timeout} 后照常启动，后台每 30 秒重试核对，通过后再起消费者。
 * 另有一条保留期清理线程。停止发生在数据源销毁之前（SmartLifecycle），未提交的批次留给下次启动重放。
 */
public final class DataNode implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DataNode.class);

    static final String TRANSACTION_LOG = "transaction_log";
    private static final long INIT_RETRY_SECONDS = 30;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final long RESTART_DELAY_MILLIS = 5_000;

    private final AuditProperties audit;
    private final DataProperties props;
    private final TransactionLogMapper transactionLog;
    private final TransactionTemplate tx;
    private final DataMetrics metrics;
    private final Supplier<TopicAdmin> adminFactory;
    private final Clock clock;
    private volatile boolean running;
    private volatile ConsumerLoop<?> transactionLoop;
    private Thread initThread;
    private Thread transactionThread;
    private ScheduledExecutorService retention;

    public DataNode(AuditProperties audit, DataProperties props, TransactionLogMapper transactionLog, TransactionTemplate tx,
                    DataMetrics metrics, Supplier<TopicAdmin> adminFactory, Clock clock) {
        this.audit = audit;
        this.props = props;
        this.transactionLog = transactionLog;
        this.tx = tx;
        this.metrics = metrics;
        this.adminFactory = adminFactory;
        this.clock = clock;
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        metrics.registerConsumer(TRANSACTION_LOG);
        running = true;
        // 第一次核对在启动线程上同步做：分区契约不符直接抛出、进程拒绝启动
        boolean verified = verify();
        initThread = new Thread(() -> {
            boolean ok = verified;
            while (running && !ok) {
                try {
                    Thread.sleep(TimeUnit.SECONDS.toMillis(INIT_RETRY_SECONDS));
                    ok = verify();
                } catch (InterruptedException e) {
                    return;
                } catch (AuditTopicContractException e) {
                    log.error("审计 topic 与契约不符，消费者不启动，需人工处理：{}", e.getMessage());
                    return;
                }
            }
            if (running) {
                startConsumers();
            }
        }, "data-kafka-init");
        initThread.setDaemon(true);
        initThread.start();
        startRetention();
    }

    private boolean verify() {
        try (TopicAdmin admin = adminFactory.get()) {
            AuditTopicInitializer.ensure(admin, AuditTopics.all(audit.topicGeneration()), AuditTopicInitializer.Mode.OWN,
                    audit.replicationFactor(), audit.initTimeout());
            return true;
        } catch (AuditTopicContractException e) {
            throw e;
        } catch (RuntimeException e) {
            // 连不上、地址解析不了（容器 / k8s 服务名尚未注册）等：都可恢复，稍后重试；只有契约不符才拒绝启动
            log.warn("审计 topic 暂时核对不了（Kafka 不可达），{} 秒后重试：{}", INIT_RETRY_SECONDS, e.toString());
            return false;
        }
    }

    private synchronized void startConsumers() {
        if (!running) {
            return;
        }
        String topic = AuditTopics.transactionLog(audit.topicGeneration()).name();
        DataProperties.Consumer settings = props.transactionLog();
        TransactionLogSink sink = new TransactionLogSink(transactionLog, tx, settings.insertChunk(), clock);
        transactionThread = new Thread(() -> {
            // 监督：消费循环意外退出（非停止）时换一个新的 KafkaConsumer 重来；未提交的批次会被重新消费
            while (running) {
                ConsumerLoop<?> loop;
                try {
                    loop = new ConsumerLoop<>(TRANSACTION_LOG, newConsumer(settings), topic, new TransactionLogDecoder(),
                            sink, metrics, POLL_TIMEOUT, props.dbRetry().initial(), props.dbRetry().max());
                } catch (RuntimeException e) {
                    log.error("创建资产流水 KafkaConsumer 失败，{} 毫秒后重试", RESTART_DELAY_MILLIS, e);
                    sleepQuietly(RESTART_DELAY_MILLIS);
                    continue;
                }
                transactionLoop = loop;
                if (!running) {
                    // stop() 可能在发布这个循环之前读的 transactionLoop：自己收尾（run 会订阅后立即退出并关闭消费者）
                    loop.stop();
                }
                try {
                    loop.run();
                } catch (RuntimeException e) {
                    log.error("资产流水消费循环异常，{} 毫秒后重启", RESTART_DELAY_MILLIS, e);
                    sleepQuietly(RESTART_DELAY_MILLIS);
                }
            }
        }, "data-txlog");
        transactionThread.start();
        log.info("资产流水消费者已启动 topic={} group={}", topic, settings.group());
    }

    private Consumer<String, byte[]> newConsumer(DataProperties.Consumer settings) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, audit.bootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, settings.group());
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, "xm-data-" + settings.group());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, Integer.toString(settings.maxPollRecords()));
        p.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, "500");
        return new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer());
    }

    private void startRetention() {
        DataProperties.Retention r = props.retention();
        if (r.transactionLog().isZero()) {
            log.info("资产流水永久保留（xm.data.retention.transaction-log=0）");
            return;
        }
        retention = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "data-retention");
            t.setDaemon(true);
            return t;
        });
        retention.scheduleWithFixedDelay(this::purgeTransactionLog, r.interval().toMillis(), r.interval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /** 分批删掉超过保留期的流水（每批之间歇 100ms，避免长事务与大锁）。 */
    void purgeTransactionLog() {
        DataProperties.Retention r = props.retention();
        long before = clock.millis() - r.transactionLog().toMillis();
        try {
            long total = 0;
            int deleted;
            do {
                deleted = transactionLog.deleteOlderThan(before, r.batch());
                total += deleted;
                if (deleted == r.batch()) {
                    sleepQuietly(100);
                }
            } while (running && deleted == r.batch());
            metrics.retentionDeleted(TRANSACTION_LOG, total);
            if (total > 0) {
                log.info("保留期清理 transaction_log 删除 {} 行（早于 {}）", total, before);
            }
        } catch (RuntimeException e) {
            log.warn("保留期清理失败，下个周期再试：{}", e.toString());
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (initThread != null) {
            initThread.interrupt();
        }
        ConsumerLoop<?> loop = transactionLoop;
        if (loop != null) {
            loop.stop();
        }
        join(transactionThread);
        if (retention != null) {
            retention.shutdownNow();
        }
        log.info("xm-data 已停止");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static void join(Thread thread) {
        if (thread == null) {
            return;
        }
        try {
            thread.join(10_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
