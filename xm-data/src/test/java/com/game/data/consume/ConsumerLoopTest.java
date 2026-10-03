package com.game.data.consume;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.audit.proto.AssetKind;
import com.game.audit.proto.TransactionLogRecord;
import com.game.data.metrics.DataMetrics;
import com.game.data.txlog.TransactionLogDecoder;
import com.game.data.txlog.TransactionLogRow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

class ConsumerLoopTest {

    private static final String TOPIC = "xm-transaction-log-g1";
    private static final String NAME = "transaction_log";
    private static final TopicPartition TP0 = new TopicPartition(TOPIC, 0);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final DataMetrics metrics = new DataMetrics(meters);
    private final RecordingConsumer consumer = new RecordingConsumer();

    /** 记下每次提交的位点；关闭后仍可查询。 */
    private static final class RecordingConsumer extends MockConsumer<String, byte[]> {
        final List<Map<TopicPartition, OffsetAndMetadata>> commits = new ArrayList<>();

        RecordingConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            commits.add(Map.copyOf(offsets));
            super.commitSync(offsets);
        }
    }

    ConsumerLoopTest() {
        metrics.registerConsumer(NAME);
    }

    private ConsumerLoop<TransactionLogRow> loop(ConsumerLoop.Sink<TransactionLogRow> sink) {
        // 退避：只做一次暂停中的 poll（推进脚本），不真的睡
        return new ConsumerLoop<>(NAME, consumer, TOPIC, new TransactionLogDecoder(), sink, metrics, Duration.ZERO,
                Duration.ofMillis(1), Duration.ofMillis(4), (delay, pollWhilePaused) -> pollWhilePaused.run());
    }

    private static ConsumerRecord<String, byte[]> record(long offset, byte[] value) {
        return new ConsumerRecord<>(TOPIC, 0, offset, "1001", value);
    }

    private static byte[] tx(long txId) {
        return TransactionLogRecord.newBuilder().setTxId(txId).setTimeMs(1).setKind(AssetKind.ASSET_CURRENCY)
                .setToPlayer(1001).setCurrencyDelta(5).setZoneId(1).build().toByteArray();
    }

    private void assign(ConsumerRecord<String, byte[]>... records) {
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of(TP0));
            consumer.updateBeginningOffsets(Map.of(TP0, 0L));
            for (ConsumerRecord<String, byte[]> r : records) {
                consumer.addRecord(r);
            }
        });
    }

    private double outcome(String outcome) {
        return meters.get("xm.data.kafka.records").tag("consumer", NAME).tag("outcome", outcome).counter().count();
    }

    @Test
    void 解码失败与非法记录跳过_其余一个事务落库_成功后才提交下一个位点() {
        List<List<TransactionLogRow>> batches = new ArrayList<>();
        ConsumerLoop<TransactionLogRow> loop = loop(rows -> {
            batches.add(List.copyOf(rows));
            return rows.size();
        });
        assign(record(0, tx(11)), record(1, new byte[] {1, 2, 3}), record(2, tx(0)), record(3, tx(12)));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(batches).hasSize(1);
        assertThat(batches.get(0)).extracting(TransactionLogRow::txId).containsExactly(11L, 12L);
        assertThat(consumer.commits).containsExactly(Map.of(TP0, new OffsetAndMetadata(4)));
        assertThat(outcome("inserted")).isEqualTo(2);
        assertThat(outcome("decode_error")).isEqualTo(1);
        assertThat(outcome("invalid")).isEqualTo(1);
    }

    @Test
    void 重复行计duplicate() {
        ConsumerLoop<TransactionLogRow> loop = loop(rows -> rows.size() - 1);
        assign(record(0, tx(11)), record(1, tx(12)));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(outcome("inserted")).isEqualTo(1);
        assertThat(outcome("duplicate")).isEqualTo(1);
    }

    @Test
    void 可恢复的库故障_暂停不提交_退避重试成功后才提交并恢复() {
        AtomicInteger attempts = new AtomicInteger();
        List<Boolean> pausedDuringRetry = new ArrayList<>();
        ConsumerLoop<TransactionLogRow> loop = loop(rows -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("库不可达");
            }
            return rows.size();
        });
        assign(record(0, tx(11)));
        consumer.schedulePollTask(() -> pausedDuringRetry.add(!consumer.paused().isEmpty()));
        consumer.schedulePollTask(() -> pausedDuringRetry.add(!consumer.paused().isEmpty()));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(attempts.get()).isEqualTo(3);
        assertThat(pausedDuringRetry).containsExactly(true, true);
        assertThat(consumer.paused()).isEmpty();
        assertThat(consumer.commits).containsExactly(Map.of(TP0, new OffsetAndMetadata(1)));
        assertThat(meters.get("xm.data.db.insert.errors").tag("consumer", NAME).counter().count()).isEqualTo(2);
    }

    @Test
    void 数据错误_逐行隔离_坏行写毒丸跳过_其余落库并提交() {
        List<Long> stored = new ArrayList<>();
        ConsumerLoop<TransactionLogRow> loop = loop(rows -> {
            if (rows.stream().anyMatch(r -> r.txId() == 12)) {
                throw new RuntimeException(new SQLException("Data too long", "22001"));
            }
            rows.forEach(r -> stored.add(r.txId()));
            return rows.size();
        });
        assign(record(0, tx(11)), record(1, tx(12)), record(2, tx(13)));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(stored).containsExactly(11L, 13L);
        assertThat(outcome("rejected")).isEqualTo(1);
        assertThat(outcome("inserted")).isEqualTo(2);
        assertThat(consumer.commits).containsExactly(Map.of(TP0, new OffsetAndMetadata(3)));
    }

    @Test
    void 重试期间待落库批次的分区被收走_整批作废不提交() {
        // 只有第一次失败：若分区被收走后还继续重试，第二次就会成功并提交，测试会失败
        AtomicInteger attempts = new AtomicInteger();
        ConsumerLoop<TransactionLogRow> loop = loop(rows -> {
            if (attempts.incrementAndGet() == 1) {
                throw new RuntimeException("库不可达");
            }
            return rows.size();
        });
        assign(record(0, tx(11)));
        consumer.schedulePollTask(() -> consumer.rebalance(List.of()));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(attempts.get()).as("分区被收走后不再重试").isEqualTo(1);
        assertThat(consumer.commits).isEmpty();
        assertThat(outcome("inserted")).isZero();
    }

    @Test
    void 停止时还在重试_不提交_留给下次重放() {
        ConsumerLoop<TransactionLogRow> loop = loop(rows -> {
            throw new RuntimeException("库不可达");
        });
        assign(record(0, tx(11)));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(consumer.commits).isEmpty();
        assertThat(meters.get("xm.data.kafka.consumer.up").tag("consumer", NAME).gauge().value()).isZero();
    }

    @Test
    void 暂停重试期间刷新积压_在途批次也算() {
        AtomicInteger attempts = new AtomicInteger();
        List<Double> lagDuringRetry = new ArrayList<>();
        ConsumerLoop<TransactionLogRow> loop = loop(rows -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("库不可达");
            }
            return rows.size();
        });
        assign(record(0, tx(11)));
        consumer.schedulePollTask(() -> consumer.updateEndOffsets(Map.of(TP0, 6L)));
        consumer.schedulePollTask(() -> lagDuringRetry.add(
                meters.get("xm.data.kafka.consumer.lag").tag("consumer", NAME).gauge().value()));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(lagDuringRetry).containsExactly(6.0);
    }
}
