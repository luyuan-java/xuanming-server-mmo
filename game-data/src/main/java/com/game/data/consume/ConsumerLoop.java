package com.game.data.consume;

import com.game.data.metrics.DataMetrics;
import com.game.data.metrics.DataMetrics.Outcome;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 一个审计 topic 的消费循环（一条专用线程、一个 KafkaConsumer）：
 * <ol>
 *   <li>一次拉取的记录逐条解码（解不出 / 字段非法的跳过并计数，位点照常推进）；</li>
 *   <li>剩下的行在<b>一个</b>库事务里按位点顺序落库（主键幂等，重放无害）；</li>
 *   <li>落库成功才 {@code commitSync} 位点——崩在两者之间只会重放；</li>
 *   <li>可恢复的库故障：暂停全部分区（继续 poll 保住组成员身份）、退避后原批次重试，不提交、不跳过——宁可积压不丢；</li>
 *   <li>数据错误：逐行重试，仍失败的行连同 topic / 分区 / 位点写进毒丸日志 {@value #POISON_LOGGER} 后跳过（行按其 toString；大字段只给字节数，本体按位点从 Kafka 重读），其余照常落库；</li>
 *   <li>重平衡收走了待落库批次里的分区：整批作废（位点没提交，新主人会重新拿到）。</li>
 * </ol>
 * 停止：{@link #stop()} 唤醒轮询，未提交的批次留给下次启动重放。
 *
 * @param <R> 落库的行类型
 */
public final class ConsumerLoop<R> implements Runnable {

    static final String POISON_LOGGER = "game.audit.poison";

    private static final Logger log = LoggerFactory.getLogger(ConsumerLoop.class);
    private static final Logger poison = LoggerFactory.getLogger(POISON_LOGGER);

    /** 一条记录的解码结果：成功给出行，否则给出跳过的结局。 */
    public record Decoded<R>(R row, Outcome skipped) {

        public static <R> Decoded<R> ok(R row) {
            return new Decoded<>(row, null);
        }

        public static <R> Decoded<R> skip(Outcome outcome) {
            return new Decoded<>(null, outcome);
        }
    }

    /** 记录解码（必须无副作用、不抛异常）。 */
    @FunctionalInterface
    public interface Decoder<R> {
        Decoded<R> decode(ConsumerRecord<String, byte[]> record);
    }

    /** 落库：在一个事务里插入全部行，返回新插入的行数（其余是已存在的重复行）。失败抛异常（事务已回滚）。 */
    @FunctionalInterface
    public interface Sink<R> {
        int insert(List<R> rows);
    }

    /** 退避等待（测试可替换）。等待期间要继续 poll，所以由循环传入 poll 动作。 */
    @FunctionalInterface
    interface Backoff {
        void await(Duration delay, Runnable pollWhilePaused);
    }

    private final String name;
    private final Consumer<String, byte[]> consumer;
    private final String topic;
    private final Decoder<R> decoder;
    private final Sink<R> sink;
    private final DataMetrics metrics;
    private final Duration pollTimeout;
    private final Duration retryInitial;
    private final Duration retryMax;
    private final Backoff backoff;
    /** 按分区并行落库并发上限（= 连接池连接数 - 1；1 时退化为单线程整批串行落库）。 */
    private final int dbConcurrency;
    private final java.util.concurrent.Semaphore dbPermits;
    private final java.util.concurrent.ExecutorService partitionPool;
    /** 待落库批次里的分区被收走（重平衡回调里置位，只在本线程读写）。 */
    private final Set<TopicPartition> revokedDuringBatch = new HashSet<>();
    /** 正在为落库重试而暂停消费（重平衡新分到的分区也要跟着暂停）。只在本线程读写。 */
    private boolean pausedForRetry;
    private volatile boolean running = true;

    public ConsumerLoop(String name, Consumer<String, byte[]> consumer, String topic, Decoder<R> decoder, Sink<R> sink,
                        DataMetrics metrics, Duration pollTimeout, Duration retryInitial, Duration retryMax) {
        this(name, consumer, topic, decoder, sink, metrics, pollTimeout, retryInitial, retryMax, 1);
    }

    public ConsumerLoop(String name, Consumer<String, byte[]> consumer, String topic, Decoder<R> decoder, Sink<R> sink,
                        DataMetrics metrics, Duration pollTimeout, Duration retryInitial, Duration retryMax,
                        int dbConcurrency) {
        this(name, consumer, topic, decoder, sink, metrics, pollTimeout, retryInitial, retryMax, dbConcurrency,
                (delay, pollWhilePaused) -> {
                    long until = System.nanoTime() + delay.toNanos();
                    do {
                        pollWhilePaused.run();
                    } while (System.nanoTime() < until);
                });
    }

    ConsumerLoop(String name, Consumer<String, byte[]> consumer, String topic, Decoder<R> decoder, Sink<R> sink,
                 DataMetrics metrics, Duration pollTimeout, Duration retryInitial, Duration retryMax, Backoff backoff) {
        this(name, consumer, topic, decoder, sink, metrics, pollTimeout, retryInitial, retryMax, 1, backoff);
    }

    ConsumerLoop(String name, Consumer<String, byte[]> consumer, String topic, Decoder<R> decoder, Sink<R> sink,
                 DataMetrics metrics, Duration pollTimeout, Duration retryInitial, Duration retryMax,
                 int dbConcurrency, Backoff backoff) {
        this.name = name;
        this.consumer = consumer;
        this.topic = topic;
        this.decoder = decoder;
        this.sink = sink;
        this.metrics = metrics;
        this.pollTimeout = pollTimeout;
        this.retryInitial = retryInitial;
        this.retryMax = retryMax;
        this.dbConcurrency = Math.max(1, dbConcurrency);
        this.dbPermits = new java.util.concurrent.Semaphore(this.dbConcurrency);
        this.partitionPool = this.dbConcurrency > 1
                ? java.util.concurrent.Executors.newFixedThreadPool(this.dbConcurrency, r -> {
                    Thread t = new Thread(r, "audit-flush-" + name);
                    t.setDaemon(true);
                    return t;
                })
                : null;
        this.backoff = backoff;
    }

    @Override
    public void run() {
        consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                revokedDuringBatch.addAll(partitions);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                log.info("{} 分到分区 {}", name, partitions);
                if (pausedForRetry) {
                    consumer.pause(partitions);
                }
            }
        });
        metrics.up(name, true);
        try {
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(pollTimeout);
                updateLag();
                if (!records.isEmpty()) {
                    revokedDuringBatch.clear();
                    process(records);
                }
            }
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
        } catch (RuntimeException e) {
            log.error("{} 消费循环异常退出", name, e);
            throw e;
        } finally {
            metrics.up(name, false);
            if (partitionPool != null) {
                partitionPool.shutdownNow();
            }
            consumer.close(Duration.ofSeconds(5));
        }
    }

    /** 任意线程：请求停止（唤醒阻塞中的 poll）。 */
    public void stop() {
        running = false;
        consumer.wakeup();
    }

    private void process(ConsumerRecords<String, byte[]> records) {
        if (partitionPool != null && records.partitions().size() > 1) {
            processPartitionsInParallel(records);
            return;
        }
        List<R> rows = new ArrayList<>(records.count());
        List<ConsumerRecord<String, byte[]>> sources = new ArrayList<>(records.count());
        Map<TopicPartition, OffsetAndMetadata> next = new HashMap<>();
        Map<Outcome, Long> skipped = new HashMap<>();
        for (ConsumerRecord<String, byte[]> record : records) {
            Decoded<R> decoded = decoder.decode(record);
            if (decoded.row() != null) {
                rows.add(decoded.row());
                sources.add(record);
            } else {
                skipped.merge(decoded.skipped(), 1L, Long::sum);
                log.warn("{} 跳过记录 topic={} partition={} offset={} 原因={}", name, record.topic(), record.partition(),
                        record.offset(), decoded.skipped());
            }
            next.put(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset() + 1));
        }
        Map<TopicPartition, Long> batchStart = new HashMap<>();
        for (TopicPartition tp : records.partitions()) {
            batchStart.put(tp, records.records(tp).get(0).offset());
        }
        if (!rows.isEmpty() && !persist(rows, sources, next.keySet(), batchStart)) {
            return;
        }
        skipped.forEach((outcome, count) -> metrics.record(name, outcome, count));
        try {
            consumer.commitSync(next);
        } catch (WakeupException e) {
            throw e;
        } catch (RuntimeException e) {
            // 行已落库，重放只会按主键去重
            log.warn("{} 提交位点失败（已落库，重放无害）：{}", name, e.toString());
        }
    }

    /**
     * 每分区独立攒批并行落库（实例级落库并发受 {@code dbPermits} = {@code poolSize - 1} 约束）：
     * 落库成功的分区各自提交位点；失败或重平衡收走的分区按旧重试/重放语义回退，绝不丢数据、绝不跨分区相互阻塞。
     */
    private void processPartitionsInParallel(ConsumerRecords<String, byte[]> records) {
        record PartitionSlice<R>(TopicPartition tp, List<R> rows, List<ConsumerRecord<String, byte[]>> sources,
                                 OffsetAndMetadata nextOffset, long startOffset, Map<Outcome, Long> skipped) {}
        List<PartitionSlice<R>> slices = new ArrayList<>(records.partitions().size());
        for (TopicPartition tp : records.partitions()) {
            List<ConsumerRecord<String, byte[]>> partRecs = records.records(tp);
            if (partRecs.isEmpty()) continue;
            List<R> rows = new ArrayList<>(partRecs.size());
            List<ConsumerRecord<String, byte[]>> sources = new ArrayList<>(partRecs.size());
            Map<Outcome, Long> skipped = new HashMap<>();
            long lastOffset = partRecs.get(0).offset();
            for (ConsumerRecord<String, byte[]> rec : partRecs) {
                Decoded<R> decoded = decoder.decode(rec);
                if (decoded.row() != null) {
                    rows.add(decoded.row());
                    sources.add(rec);
                } else {
                    skipped.merge(decoded.skipped(), 1L, Long::sum);
                    log.warn("{} 跳过记录 topic={} partition={} offset={} 原因={}", name, rec.topic(), rec.partition(),
                            rec.offset(), decoded.skipped());
                }
                lastOffset = rec.offset();
            }
            slices.add(new PartitionSlice<>(tp, rows, sources, new OffsetAndMetadata(lastOffset + 1),
                    partRecs.get(0).offset(), skipped));
        }

        Map<TopicPartition, java.util.concurrent.Future<Boolean>> futures = new HashMap<>(slices.size());
        for (var slice : slices) {
            if (slice.rows().isEmpty()) {
                futures.put(slice.tp(), java.util.concurrent.CompletableFuture.completedFuture(Boolean.TRUE));
                continue;
            }
            futures.put(slice.tp(), partitionPool.submit(() -> {
                dbPermits.acquire();
                try {
                    long started = System.nanoTime();
                    try {
                        int inserted = sink.insert(slice.rows());
                        metrics.insertTime(name, System.nanoTime() - started);
                        metrics.record(name, Outcome.INSERTED, inserted);
                        metrics.record(name, Outcome.DUPLICATE, slice.rows().size() - inserted);
                        return Boolean.TRUE;
                    } catch (RuntimeException e) {
                        metrics.dbError(name);
                        if (DbErrors.isDataError(e)) {
                            insertOneByOne(slice.rows(), slice.sources());
                            return Boolean.TRUE;
                        }
                        log.warn("{} 分区 {} 并行落库遇到可恢复故障，转交顺序重试: {}", name, slice.tp(), e.toString());
                        return Boolean.FALSE;
                    }
                } finally {
                    dbPermits.release();
                }
            }));
        }

        Map<TopicPartition, OffsetAndMetadata> committed = new HashMap<>();
        List<PartitionSlice<R>> failedSlices = new ArrayList<>();
        for (var slice : slices) {
            boolean ok = false;
            try {
                ok = Boolean.TRUE.equals(futures.get(slice.tp()).get());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException ee) {
                log.warn("{} 分区 {} 并行落库异常: {}", name, slice.tp(), ee.getCause().toString());
            }
            if (ok && !revokedDuringBatch.contains(slice.tp())) {
                slice.skipped().forEach((outcome, count) -> metrics.record(name, outcome, count));
                committed.put(slice.tp(), slice.nextOffset());
            } else if (!revokedDuringBatch.contains(slice.tp())) {
                failedSlices.add(slice);
            }
        }
        if (!committed.isEmpty()) {
            try {
                consumer.commitSync(committed);
            } catch (WakeupException e) {
                throw e;
            } catch (RuntimeException e) {
                log.warn("{} 并行提交成功分区位点失败（已落库，重放无害）：{}", name, e.toString());
            }
        }
        // 遇到可恢复故障的分区回落原单分区暂停退避重试，保住组成员身份与不丢语义
        for (var slice : failedSlices) {
            if (!running || revokedDuringBatch.contains(slice.tp())) continue;
            if (persist(slice.rows(), slice.sources(), Set.of(slice.tp()), Map.of(slice.tp(), slice.startOffset()))) {
                slice.skipped().forEach((outcome, count) -> metrics.record(name, outcome, count));
                try {
                    consumer.commitSync(Map.of(slice.tp(), slice.nextOffset()));
                } catch (WakeupException e) {
                    throw e;
                } catch (RuntimeException e) {
                    log.warn("{} 分区 {} 重试提交位点失败（已落库）：{}", name, slice.tp(), e.toString());
                }
            }
        }
    }

    /** @return true = 已落库（可以提交）；false = 批次作废（停止或分区被收走），不提交 */
    private boolean persist(List<R> rows, List<ConsumerRecord<String, byte[]>> sources, Set<TopicPartition> partitions,
                            Map<TopicPartition, Long> batchStart) {
        Duration delay = retryInitial;
        boolean paused = false;
        try {
            while (true) {
                long started = System.nanoTime();
                try {
                    int inserted = sink.insert(rows);
                    metrics.insertTime(name, System.nanoTime() - started);
                    metrics.record(name, Outcome.INSERTED, inserted);
                    metrics.record(name, Outcome.DUPLICATE, rows.size() - inserted);
                    return true;
                } catch (RuntimeException e) {
                    metrics.dbError(name);
                    if (DbErrors.isDataError(e)) {
                        try {
                            insertOneByOne(rows, sources);
                            return true;
                        } catch (RuntimeException transientError) {
                            // 逐行阶段遇到可恢复故障：已逐行插入的行重放时按主键去重，整批退避重来
                            log.warn("{} 逐行落库时遇到可恢复故障，{} 后整批重试：{}", name, delay, transientError.toString());
                        }
                    } else {
                        log.warn("{} 落库失败，{} 后重试（不提交位点）：{}", name, delay, e.toString());
                    }
                }
                if (!running) {
                    return false;
                }
                if (!paused) {
                    consumer.pause(consumer.assignment());
                    paused = true;
                    pausedForRetry = true;
                }
                backoff.await(delay, this::pollWhilePaused);
                updateLagWhilePaused(batchStart);
                if (!running || revokedDuringBatch.stream().anyMatch(partitions::contains)) {
                    log.warn("{} 待落库批次的分区被收走或正在停止，整批作废（位点未提交，会被重新消费）", name);
                    return false;
                }
                delay = delay.multipliedBy(2).compareTo(retryMax) > 0 ? retryMax : delay.multipliedBy(2);
            }
        } finally {
            if (paused) {
                pausedForRetry = false;
                // 只恢复仍分配给自己的分区（重平衡收走的分区再 resume 会抛 IllegalStateException）
                Set<TopicPartition> resume = new HashSet<>(consumer.paused());
                resume.retainAll(consumer.assignment());
                consumer.resume(resume);
            }
        }
    }

    /** 暂停期间 poll（保住组成员身份、触发重平衡回调）；万一拉到记录（不该发生）就退回去，留待恢复后重新消费。 */
    private void pollWhilePaused() {
        ConsumerRecords<String, byte[]> unexpected = consumer.poll(pollTimeout);
        for (TopicPartition tp : unexpected.partitions()) {
            consumer.seek(tp, unexpected.records(tp).get(0).offset());
        }
    }

    /** 数据错误：逐行落库，失败的行写毒丸日志后跳过。可恢复故障在逐行阶段出现时整批按失败重来（抛给上层退避）。 */
    private void insertOneByOne(List<R> rows, List<ConsumerRecord<String, byte[]>> sources) {
        int inserted = 0;
        int duplicates = 0;
        int rejected = 0;
        for (int i = 0; i < rows.size(); i++) {
            try {
                int n = sink.insert(List.of(rows.get(i)));
                inserted += n;
                duplicates += 1 - n;
            } catch (RuntimeException e) {
                if (!DbErrors.isDataError(e)) {
                    throw e;
                }
                rejected++;
                ConsumerRecord<String, byte[]> source = sources.get(i);
                poison.error("{} 数据库拒绝的行 topic={} partition={} offset={} row={} 错误={}", name, source.topic(),
                        source.partition(), source.offset(), rows.get(i), e.toString());
            }
        }
        metrics.record(name, Outcome.INSERTED, inserted);
        metrics.record(name, Outcome.DUPLICATE, duplicates);
        metrics.record(name, Outcome.REJECTED, rejected);
    }

    /**
     * 暂停重试期间刷新积压：poll 不拉数据时 currentLag 不更新，而这正是积压在涨的时候。在途未落库的批次也算积压
     * （从批次起点算，其余分区从已提交位点算）。Kafka 也不通时保留旧值。
     */
    private void updateLagWhilePaused(Map<TopicPartition, Long> batchStart) {
        try {
            Set<TopicPartition> assigned = consumer.assignment();
            Duration timeout = pollTimeout.isZero() ? Duration.ofSeconds(1) : pollTimeout;
            Map<TopicPartition, Long> end = consumer.endOffsets(assigned, timeout);
            Map<TopicPartition, OffsetAndMetadata> committed = consumer.committed(assigned, timeout);
            long total = 0;
            for (Map.Entry<TopicPartition, Long> e : end.entrySet()) {
                Long from = batchStart.get(e.getKey());
                if (from == null) {
                    OffsetAndMetadata c = committed.get(e.getKey());
                    from = c != null ? c.offset() : 0L;
                }
                total += Math.max(0, e.getValue() - from);
            }
            metrics.lag(name, total);
        } catch (WakeupException e) {
            throw e;
        } catch (RuntimeException e) {
            log.debug("{} 暂停期间刷新积压失败（保留旧值）：{}", name, e.toString());
        }
    }

    private void updateLag() {
        long total = 0;
        for (TopicPartition tp : consumer.assignment()) {
            OptionalLong lag = consumer.currentLag(tp);
            if (lag.isPresent()) {
                total += lag.getAsLong();
            }
        }
        metrics.lag(name, total);
    }
}
