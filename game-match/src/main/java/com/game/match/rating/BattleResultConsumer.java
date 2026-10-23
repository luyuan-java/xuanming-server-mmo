package com.game.match.rating;

import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MatchMetrics.RatingOutcome;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.google.protobuf.InvalidProtocolBufferException;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
 * 对局结果 topic 的消费循环（match-spec §5.3；基线 {@code consumer.go:102-152}）：一条专用线程、一个 KafkaConsumer，
 * 把 {@code contracts.kafka.BattleResultEvent} 逐条交给评分入账。模式同 game-data 的 {@code ConsumerLoop}（不能直接依赖那个模块），
 * 区别是这里<b>逐条</b>处理、逐条提交（同基线）：
 * <ol>
 *   <li>全球模式解不出的消息写毒丸日志 {@value #POISON_LOGGER}，关闭本轮并保留原位点；显式旧兼容模式才跳过并提交；</li>
 *   <li>key 与 payload 里的 battle_id 不一致只打日志，以 payload 为准；</li>
 *   <li>入账<b>成功才</b> {@code commitSync}（已入账、重复、不计分都算成功）——崩在两者之间只会重放，入账按 battle_id 幂等；</li>
 *   <li>全球模式的数据错误（含 SQLState 22 / 23）也暂停重试原记录，修正原因前不提交；显式旧兼容模式才拒绝并跳过；</li>
 *   <li><b>其余故障一律可恢复、不跳过</b>（有意差异 M19；基线重试 3 次后跳过）：暂停全部分区（继续 poll 保住组成员身份）、退避
 *       1 s → 30 s 后重试<b>原记录</b>，不提交位点；期间 {@code game_match_rating_consumer_paused = 1}（评分整体停更，排队照常）；</li>
 *   <li>重试期间这条记录所在的分区被重平衡收走：放弃它与本批里同分区的后续记录（位点没提交，新主人会重新拿到）。</li>
 * </ol>
 * 停止：{@link #stop()} 唤醒轮询；没提交的记录留给下次启动重放。活动局的结果会被 battle 原字节重发几十次，每条都在入账那一步判成不计分。
 *
 * <p>线程：{@link #run()} 独占传入的消费者（KafkaConsumer 不是线程安全的）；只有 {@link #stop()} 可以从别的线程调。
 */
public final class BattleResultConsumer implements Runnable {
    public enum FailurePolicy { SQL_CONFIRMED, LEGACY_REJECT_POISON }
    private final FailurePolicy failurePolicy;

    /** 毒丸日志：需要人工排查的消息（附受限长度的 Base64）。 */
    static final String POISON_LOGGER = "game.match.rating.poison";
    /** 毒丸日志里消息体最多带多少字节（一条 5V5 结果不到 300 字节）。 */
    static final int POISON_MAX_BYTES = 4096;

    private static final Logger log = LoggerFactory.getLogger(BattleResultConsumer.class);
    private static final Logger poison = LoggerFactory.getLogger(POISON_LOGGER);

    /**
     * 入账一条结果（生产为 {@code ratingStore::apply}）：正常返回 = 终态（已入账 / 重复 / 不计分，各自的指标由实现记）；
     * 抛异常 = 没入账；全球模式保留原记录，只有显式旧兼容模式才按数据错误分类跳过。
     */
    @FunctionalInterface
    public interface Handler {
        void handle(BattleResultEvent event);

        /** 旧评分实现只关心事件；来源归档实现覆盖此入口，不能重序列化代替收到的原件。 */
        default void handle(Received received) { handle(received.event()); }
    }

    /** 消费线程固定的来源证据；ByteString 隔离 Kafka 可变缓冲。 */
    public record Received(BattleResultEvent event, com.google.protobuf.ByteString original,
                           String topic, int partition, long offset) {
        public Received {
            Objects.requireNonNull(event, "event");Objects.requireNonNull(original, "original");
            if(topic==null||topic.isBlank()||partition<0||offset<0)throw new IllegalArgumentException("结果来源位点无效");
        }
    }

    @FunctionalInterface
    public interface SourceHandler extends Handler {
        @Override void handle(Received received);
        @Override default void handle(BattleResultEvent event) {
            throw new IllegalArgumentException("结果来源处理必须携带收到的原始字节与位点");
        }
    }

    /** 退避等待（测试可替换）。等待期间要继续 poll，所以由循环传入 poll 动作。 */
    @FunctionalInterface
    interface Backoff {
        void await(Duration delay, Runnable pollWhilePaused);
    }

    private final Consumer<String, byte[]> consumer;
    private final String topic;
    private final Handler handler;
    private final MatchMetrics metrics;
    private final Duration pollTimeout;
    private final Duration retryInitial;
    private final Duration retryMax;
    private final Backoff backoff;
    /** 本批处理期间被收走的分区（重平衡回调里置位，只在本线程读写）。 */
    private final Set<TopicPartition> revoked = new HashSet<>();
    /** 正在为入账重试而暂停消费（重平衡新分到的分区也要跟着暂停）。只在本线程读写。 */
    private boolean pausedForRetry;
    private volatile boolean running = true;

    public BattleResultConsumer(Consumer<String, byte[]> consumer, String topic, Handler handler, MatchMetrics metrics,
                                Duration pollTimeout, Duration retryInitial, Duration retryMax) {
        this(consumer, topic, handler, metrics, pollTimeout, retryInitial, retryMax, FailurePolicy.SQL_CONFIRMED);
    }

    /** 仅装配层显式选择旧兼容策略；公开入口仍默认等 SQL 确认后才推进位点。 */
    BattleResultConsumer(Consumer<String, byte[]> consumer, String topic, Handler handler, MatchMetrics metrics,
                         Duration pollTimeout, Duration retryInitial, Duration retryMax, FailurePolicy policy) {
        this(consumer, topic, handler, metrics, pollTimeout, retryInitial, retryMax, (delay, pollWhilePaused) -> {
            long until = System.nanoTime() + delay.toNanos();
            do {
                pollWhilePaused.run();
            } while (System.nanoTime() < until);
        },policy);
    }

    BattleResultConsumer(Consumer<String, byte[]> consumer, String topic, Handler handler, MatchMetrics metrics,
                         Duration pollTimeout, Duration retryInitial, Duration retryMax, Backoff backoff) {
        this(consumer,topic,handler,metrics,pollTimeout,retryInitial,retryMax,backoff,FailurePolicy.SQL_CONFIRMED);
    }
    BattleResultConsumer(Consumer<String, byte[]> consumer,String topic,Handler handler,MatchMetrics metrics,
                         Duration pollTimeout,Duration retryInitial,Duration retryMax,Backoff backoff,FailurePolicy policy) {
        this.failurePolicy=Objects.requireNonNull(policy);
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.pollTimeout = pollTimeout;
        this.retryInitial = retryInitial;
        this.retryMax = retryMax;
        this.backoff = backoff;
    }

    @Override
    public void run() {
        consumer.subscribe(List.of(topic), new ConsumerRebalanceListener() {
            @Override
            public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
                revoked.addAll(partitions);
            }

            @Override
            public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
                log.info("[rating] 对局结果消费者分到分区 {}", partitions);
                if (pausedForRetry) {
                    consumer.pause(partitions);
                }
            }
        });
        try {
            while (running) {
                ConsumerRecords<String, byte[]> records = consumer.poll(pollTimeout);
                if (!records.isEmpty()) {
                    revoked.clear();
                    process(records);
                }
            }
        } catch (WakeupException e) {
            if (running) {
                throw e;
            }
        } catch (RuntimeException e) {
            log.error("[rating] 对局结果消费循环异常退出", e);
            throw e;
        } finally {
            metrics.ratingConsumerPaused(false);
            consumer.close(Duration.ofSeconds(5));
        }
    }

    /** 任意线程：请求停止（唤醒阻塞中的 poll）。 */
    public void stop() {
        running = false;
        consumer.wakeup();
    }

    /** 按分区、按位点顺序逐条处理；每条成功后立即提交它的下一个位点。 */
    private void process(ConsumerRecords<String, byte[]> records) {
        for (TopicPartition partition : records.partitions()) {
            for (ConsumerRecord<String, byte[]> record : records.records(partition)) {
                if (!running) {
                    return;
                }
                if (revoked.contains(partition)) {
                    // 这个分区已不归自己：本批里它剩下的记录交给新主人（位点没提交）
                    break;
                }
                if (!handle(partition, record)) {
                    if (!running) {
                        return;
                    }
                    break;
                }
                commit(partition, record.offset() + 1);
            }
        }
    }

    /** @return true = 这条已有终态（入账 / 重复 / 不计分 / 跳过），可以提交；false = 放弃（正在停止或分区被收走），不提交 */
    private boolean handle(TopicPartition partition, ConsumerRecord<String, byte[]> record) {
        BattleResultEvent event;
        try {
            if (record.value() == null) {
                throw new InvalidProtocolBufferException("消息体为空（墓碑）");
            }
            event = BattleResultEvent.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            poison.error("对局结果解不出 topic={} partition={} offset={} key={} bytes={} 错误={}", record.topic(), record.partition(),
                    record.offset(), record.key(), encoded(record.value()), e.getMessage());
            metrics.ratingUpdate(-1, RatingOutcome.DECODE_ERROR);
            if(failurePolicy==FailurePolicy.SQL_CONFIRMED)throw new IllegalStateException("全球评级消息损坏，保留原位点等待恢复",e);
            return true;
        }
        String battle = Long.toUnsignedString(event.getBattleId());
        Received received=new Received(event,com.google.protobuf.ByteString.copyFrom(record.value()),
                record.topic(),record.partition(),record.offset());
        String key = record.key();
        if (key != null && !key.isEmpty() && !key.equals(battle)) {
            // key 的契约是 battle_id；不一致只记日志，以 payload 为准
            log.error("[rating] 对局结果 key={} 与 battle_id={} 不一致 partition={} offset={}", key, battle, record.partition(), record.offset());
        }
        Duration delay = retryInitial;
        boolean paused = false;
        try {
            while (true) {
                try {
                    handler.handle(received);
                    return true;
                } catch (RuntimeException e) {
                    if (failurePolicy==FailurePolicy.LEGACY_REJECT_POISON&&RatingSqlErrors.isDataError(e)) {
                        poison.error("对局结果被数据库拒绝，跳过 topic={} partition={} offset={} battle={} bytes={} 错误={}", record.topic(),
                                record.partition(), record.offset(), battle, encoded(record.value()), String.valueOf(rootMessage(e)));
                        // 永久丢弃要有自己的计数：入账那一步记的 error 与可恢复故障每次重试记的是同一个标签，库抖动时分不出来
                        metrics.ratingUpdate(event.getMatchMode(), RatingOutcome.REJECTED);
                        return true;
                    }
                    log.warn("[rating] 对局结果入账失败，{} 后重试（不提交位点、不跳过） battle={} partition={} offset={}: {}", delay, battle,
                            record.partition(), record.offset(), e.toString());
                }
                if (!running) {
                    return false;
                }
                if (!paused) {
                    consumer.pause(consumer.assignment());
                    paused = true;
                    pausedForRetry = true;
                    metrics.ratingConsumerPaused(true);
                }
                backoff.await(delay, this::pollWhilePaused);
                if (!running || revoked.contains(partition)) {
                    log.warn("[rating] 待入账的对局结果所在分区被收走或正在停止，放弃（位点未提交，会被重新消费） battle={} partition={} offset={}",
                            battle, record.partition(), record.offset());
                    return false;
                }
                delay = delay.multipliedBy(2).compareTo(retryMax) > 0 ? retryMax : delay.multipliedBy(2);
            }
        } finally {
            if (paused) {
                pausedForRetry = false;
                metrics.ratingConsumerPaused(false);
                // 只恢复仍分配给自己的分区（重平衡收走的分区再 resume 会抛 IllegalStateException）
                Set<TopicPartition> resume = new HashSet<>(consumer.paused());
                resume.retainAll(consumer.assignment());
                consumer.resume(resume);
            }
        }
    }

    private void commit(TopicPartition partition, long nextOffset) {
        try {
            consumer.commitSync(Map.of(partition, new OffsetAndMetadata(nextOffset)));
        } catch (WakeupException e) {
            throw e;
        } catch (RuntimeException e) {
            // 这条已有终态；提交失败只影响重放的起点，重放按 battle_id 去重
            log.warn("[rating] 提交对局结果位点失败（重放无害） partition={} offset={}: {}", partition.partition(), nextOffset, e.toString());
        }
    }

    /** 暂停期间 poll（保住组成员身份、触发重平衡回调）；万一拉到记录（不该发生）就退回去，留待恢复后重新消费。 */
    private void pollWhilePaused() {
        ConsumerRecords<String, byte[]> unexpected = consumer.poll(pollTimeout);
        for (TopicPartition partition : unexpected.partitions()) {
            consumer.seek(partition, unexpected.records(partition).get(0).offset());
        }
    }

    private static String encoded(byte[] value) {
        if (value == null) {
            return "<null>";
        }
        if (value.length > POISON_MAX_BYTES) {
            return "<" + value.length + " 字节，超过上限不记录；按位点从 Kafka 重读>";
        }
        return Base64.getEncoder().encodeToString(value);
    }

    private static String rootMessage(Throwable error) {
        Throwable sql = RatingSqlErrors.sqlCause(error);
        return sql == null ? error.toString() : sql.toString();
    }
}
