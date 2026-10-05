package com.game.data;

import com.game.audit.AuditTopicContractException;
import com.game.audit.AuditProperties;
import com.game.audit.AuditTopicInitializer;
import com.game.audit.AuditTopics;
import com.game.audit.TopicAdmin;
import com.game.data.consume.ConsumerLoop;
import com.game.data.consume.ConsumerLoop.Decoder;
import com.game.data.consume.ConsumerLoop.Sink;
import com.game.data.metrics.DataMetrics;
import com.game.data.snapshot.PlayerSnapshotDecoder;
import com.game.data.snapshot.PlayerSnapshotSink;
import com.game.data.snapshot.SnapshotCauses;
import com.game.data.store.PlayerSnapshotMapper;
import com.game.data.store.TransactionLogMapper;
import com.game.data.txlog.TransactionLogDecoder;
import com.game.data.txlog.TransactionLogSink;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * 然后为每个审计 topic 起一条消费线程（资产流水、玩家快照）。第一次核对在启动线程上同步做（契约不符才能拒绝启动）；
 * Kafka 不可达（含地址解析不了）时最多等 {@code xm.audit.init-timeout} 后照常启动，后台每 30 秒重试核对，通过后再起消费者。
 * 另有一条保留期清理线程。停止发生在数据源销毁之前（SmartLifecycle），未提交的批次留给下次启动重放。
 */
public final class DataNode implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(DataNode.class);

    static final String TRANSACTION_LOG = "transaction_log";
    static final String PLAYER_SNAPSHOT = "player_snapshot";
    /** 运维 / 安全快照的保留期清理（指标 table 标签；同一张表按原因分两类清）。 */
    static final String PLAYER_SNAPSHOT_GM = "player_snapshot_gm";
    private static final long INIT_RETRY_SECONDS = 30;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final long RESTART_DELAY_MILLIS = 5_000;

    private final AuditProperties audit;
    private final DataProperties props;
    private final TransactionLogMapper transactionLog;
    private final PlayerSnapshotMapper playerSnapshot;
    private final TransactionTemplate tx;
    private final DataMetrics metrics;
    private final Supplier<TopicAdmin> adminFactory;
    private final Clock clock;
    private volatile boolean running;
    private final List<Worker> workers = new ArrayList<>();
    private Thread initThread;
    private ScheduledExecutorService retention;

    /** 一条消费线程与它当前的消费循环（监督重启时换新循环）。 */
    private static final class Worker {
        Thread thread;
        volatile ConsumerLoop<?> loop;
    }

    public DataNode(AuditProperties audit, DataProperties props, TransactionLogMapper transactionLog,
                    PlayerSnapshotMapper playerSnapshot, TransactionTemplate tx, DataMetrics metrics,
                    Supplier<TopicAdmin> adminFactory, Clock clock) {
        this.audit = audit;
        this.props = props;
        this.transactionLog = transactionLog;
        this.playerSnapshot = playerSnapshot;
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
        metrics.registerConsumer(PLAYER_SNAPSHOT);
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
        int generation = audit.topicGeneration();
        DataProperties.TransactionLogConsumer txSettings = props.transactionLog();
        startConsumer(TRANSACTION_LOG, "data-txlog", AuditTopics.transactionLog(generation).name(), txSettings,
                new TransactionLogDecoder(), new TransactionLogSink(transactionLog, tx, txSettings.insertChunk(), clock));
        DataProperties.PlayerSnapshotConsumer snapshotSettings = props.playerSnapshot();
        startConsumer(PLAYER_SNAPSHOT, "data-snapshot", AuditTopics.playerSnapshot(generation).name(), snapshotSettings,
                new PlayerSnapshotDecoder(),
                new PlayerSnapshotSink(playerSnapshot, tx, snapshotSettings.insertChunk(), clock));
    }

    private <R> void startConsumer(String name, String threadName, String topic, DataProperties.ConsumerSettings settings,
                                   Decoder<R> decoder, Sink<R> sink) {
        Worker worker = new Worker();
        worker.thread = new Thread(() -> {
            // 监督：消费循环意外退出（非停止）时换一个新的 KafkaConsumer 重来；未提交的批次会被重新消费
            while (running) {
                ConsumerLoop<R> loop;
                try {
                    loop = new ConsumerLoop<>(name, newConsumer(settings), topic, decoder, sink, metrics, POLL_TIMEOUT,
                            props.dbRetry().initial(), props.dbRetry().max());
                } catch (RuntimeException e) {
                    log.error("创建 {} 的 KafkaConsumer 失败，{} 毫秒后重试", name, RESTART_DELAY_MILLIS, e);
                    sleepQuietly(RESTART_DELAY_MILLIS);
                    continue;
                }
                worker.loop = loop;
                if (!running) {
                    // stop() 可能在发布这个循环之前读的 worker.loop：自己收尾（run 会订阅后立即退出并关闭消费者）
                    loop.stop();
                }
                try {
                    loop.run();
                } catch (RuntimeException e) {
                    log.error("{} 消费循环异常，{} 毫秒后重启", name, RESTART_DELAY_MILLIS, e);
                    sleepQuietly(RESTART_DELAY_MILLIS);
                }
            }
        }, threadName);
        workers.add(worker);
        worker.thread.start();
        log.info("{} 消费者已启动 topic={} group={}", name, topic, settings.group());
    }

    private Consumer<String, byte[]> newConsumer(DataProperties.ConsumerSettings settings) {
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
        }
        if (r.playerSnapshot().isZero()) {
            log.info("上下线快照永久保留（xm.data.retention.player-snapshot=0）");
        }
        if (r.gmSnapshot().isZero()) {
            log.info("运维 / 安全快照永久保留（xm.data.retention.gm-snapshot=0）");
        }
        if (r.transactionLog().isZero() && r.playerSnapshot().isZero() && r.gmSnapshot().isZero()) {
            return;
        }
        retention = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread t = new Thread(runnable, "data-retention");
            t.setDaemon(true);
            return t;
        });
        retention.scheduleWithFixedDelay(this::purgeExpired, r.interval().toMillis(), r.interval().toMillis(),
                TimeUnit.MILLISECONDS);
    }

    /**
     * 各表各自按保留期清理（保留期为 0 的不动）。快照按原因分两类（data-ops-spec §3.7）：上下线 / 周期快照按
     * {@code player-snapshot}，运维 / 安全快照按 {@code gm-snapshot}；不在两类里的原因从不清理。
     */
    void purgeExpired() {
        DataProperties.Retention r = props.retention();
        purge(TRANSACTION_LOG, r.transactionLog(), transactionLog::deleteOlderThan);
        purge(PLAYER_SNAPSHOT, r.playerSnapshot(),
                (before, limit) -> playerSnapshot.deleteOlderThan(before, SnapshotCauses.ROUTINE_RETENTION, limit));
        purge(PLAYER_SNAPSHOT_GM, r.gmSnapshot(),
                (before, limit) -> playerSnapshot.deleteOlderThan(before, SnapshotCauses.GM_RETENTION, limit));
    }

    @FunctionalInterface
    private interface BatchDelete {
        int deleteOlderThan(long before, int limit);
    }

    /** 分批删掉超过保留期的行（每批之间歇 100ms，避免长事务与大锁）。 */
    private void purge(String table, Duration keep, BatchDelete delete) {
        if (keep.isZero()) {
            return;
        }
        int batch = props.retention().batch();
        long before = clock.millis() - keep.toMillis();
        try {
            long total = 0;
            int deleted;
            do {
                deleted = delete.deleteOlderThan(before, batch);
                total += deleted;
                if (deleted == batch) {
                    sleepQuietly(100);
                }
            } while (running && deleted == batch);
            metrics.retentionDeleted(table, total);
            if (total > 0) {
                log.info("保留期清理 {} 删除 {} 行（早于 {}）", table, total, before);
            }
        } catch (RuntimeException e) {
            log.warn("保留期清理 {} 失败，下个周期再试：{}", table, e.toString());
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
        // 先让所有循环一起开始收尾，再逐个等线程退出（总耗时取最慢的一条，而不是相加）
        for (Worker worker : workers) {
            ConsumerLoop<?> loop = worker.loop;
            if (loop != null) {
                loop.stop();
            }
        }
        for (Worker worker : workers) {
            join(worker.thread);
        }
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
