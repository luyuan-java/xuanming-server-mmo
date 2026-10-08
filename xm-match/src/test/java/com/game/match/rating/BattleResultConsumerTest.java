package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.support.MatchModes;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * 对局结果消费循环（{@code MockConsumer}；match-spec §5.3、§15.2）：坏消息跳过并提交；入账成功才提交；可恢复故障暂停、重试原记录、不跳过、不提交；
 * 数据错误写毒丸后跳过；重平衡收走分区时丢弃没提交的记录；key 不一致只打日志。
 *
 * <p>用例都在测试线程上直接调 {@code loop.run()}，能否返回全靠循环自己走到脚本里排的 {@code loop::stop}。循环的重试 / 暂停逻辑一旦回归
 * （例如暂停期间不再 poll，排好的 stop 永远轮不到），就不是失败而是空转到整个 fork 超时（30 分钟），CI 上看不出是哪一条。所以类上加<b>抢占式</b>超时：
 * 缺省的同线程模式只是到点中断测试线程，而空转的循环（{@code MockConsumer.poll(Duration.ZERO)}、处理器抛异常、记日志）不响应中断，照样挂着；
 * {@code SEPARATE_THREAD} 把用例体放到另一条线程上跑，到点直接判这条用例失败。
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class BattleResultConsumerTest {

    private static final String TOPIC = "xm-battle-result-g1";
    private static final TopicPartition TP0 = new TopicPartition(TOPIC, 0);
    private static final TopicPartition TP1 = new TopicPartition(TOPIC, 1);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));
    private final RecordingConsumer consumer = new RecordingConsumer();
    /** 每次退避的时长，按发生顺序。 */
    private final List<Duration> delays = new ArrayList<>();

    /** 记下每次提交的位点；关闭后仍可查询。可以让下一次提交失败。 */
    private static final class RecordingConsumer extends MockConsumer<String, byte[]> {
        final List<Map<TopicPartition, OffsetAndMetadata>> commits = new ArrayList<>();
        KafkaException failNextCommit;

        RecordingConsumer() {
            super(OffsetResetStrategy.EARLIEST);
        }

        @Override
        public synchronized void commitSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
            if (failNextCommit != null) {
                KafkaException e = failNextCommit;
                failNextCommit = null;
                throw e;
            }
            commits.add(Map.copyOf(offsets));
            super.commitSync(offsets);
        }
    }

    private BattleResultConsumer loop(BattleResultConsumer.Handler handler) {
        // 退避：记下时长，只做一次暂停中的 poll（推进脚本），不真的睡
        return new BattleResultConsumer(consumer, TOPIC, handler, metrics, Duration.ZERO, Duration.ofMillis(1), Duration.ofMillis(4),
                (delay, pollWhilePaused) -> {
                    delays.add(delay);
                    pollWhilePaused.run();
                });
    }

    private static byte[] result(long battleId) {
        return BattleResultEvent.newBuilder().setBattleId(battleId).setMatchMode(MatchModes.ONE_V_ONE)
                .setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(battleId * 10 + 1))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(battleId * 10 + 2))
                .setTotalRounds(5).build().toByteArray();
    }

    private static ConsumerRecord<String, byte[]> record(int partition, long offset, String key, byte[] value) {
        return new ConsumerRecord<>(TOPIC, partition, offset, key, value);
    }

    private static ConsumerRecord<String, byte[]> record(long offset, long battleId) {
        return record(0, offset, Long.toUnsignedString(battleId), result(battleId));
    }

    @SafeVarargs
    private void assign(List<TopicPartition> partitions, ConsumerRecord<String, byte[]>... records) {
        consumer.schedulePollTask(() -> {
            consumer.rebalance(partitions);
            Map<TopicPartition, Long> beginning = new java.util.HashMap<>();
            partitions.forEach(tp -> beginning.put(tp, 0L));
            consumer.updateBeginningOffsets(beginning);
            for (ConsumerRecord<String, byte[]> r : records) {
                consumer.addRecord(r);
            }
        });
    }

    /** 排 n 次什么都不做的 poll（退避期间每次重试之前循环会 poll 一次，脚本要给它留出位置）。 */
    private void idlePolls(int n) {
        for (int i = 0; i < n; i++) {
            consumer.schedulePollTask(() -> { });
        }
    }

    private static Map<TopicPartition, OffsetAndMetadata> committed(TopicPartition tp, long offset) {
        return Map.of(tp, new OffsetAndMetadata(offset));
    }

    private double decodeErrors() {
        return meters.get("xm.match.rating.updates").tag("mode", "unknown").tag("outcome", "decode_error").counter().count();
    }

    /** {@code outcome="rejected"}：被数据库判为数据错误而永久跳过的局数。 */
    private double rejected(String mode) {
        return meters.get("xm.match.rating.updates").tag("mode", mode).tag("outcome", "rejected").counter().count();
    }

    private double pausedGauge() {
        return meters.get("xm.match.rating.consumer.paused").gauge().value();
    }

    private static RuntimeException unreachable() {
        return new RatingStore.StoreException("对局结果入账失败", new SQLException("Communications link failure", "08S01"));
    }

    // ================================================================ 正常路径

    @Test
    void 逐条入账_每条成功后立即提交它的下一个位点() {
        List<Long> handled = new ArrayList<>();
        List<Integer> commitsSeenByHandler = new ArrayList<>();
        BattleResultConsumer loop = loop(event -> {
            commitsSeenByHandler.add(consumer.commits.size());
            handled.add(event.getBattleId());
        });
        assign(List.of(TP0), record(0, 9001), record(1, 9002), record(2, 9003));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).containsExactly(9001L, 9002L, 9003L);
        assertThat(commitsSeenByHandler).as("处理第 n 条时，前 n−1 条已各自提交").containsExactly(0, 1, 2);
        assertThat(consumer.commits).containsExactly(committed(TP0, 1), committed(TP0, 2), committed(TP0, 3));
        assertThat(delays).isEmpty();
        assertThat(pausedGauge()).isZero();
        assertThat(consumer.closed()).isTrue();
    }

    @Test
    void 事件原样交给入账_字段不丢() {
        List<BattleResultEvent> handled = new ArrayList<>();
        BattleResultConsumer loop = loop(handled::add);
        long battleId = Long.MIN_VALUE + 77;
        assign(List.of(TP0), record(0, 0, Long.toUnsignedString(battleId), result(battleId)));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).hasSize(1);
        assertThat(handled.get(0).getBattleId()).isEqualTo(battleId);
        assertThat(handled.get(0).getMatchMode()).isEqualTo(MatchModes.ONE_V_ONE);
        assertThat(handled.get(0).getOutcome()).isEqualTo(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN);
        assertThat(handled.get(0).getTotalRounds()).isEqualTo(5);
        assertThat(handled.get(0).getTeamsCount()).isEqualTo(2);
    }

    // ================================================================ 坏消息与 key

    @Test
    void 解不出的消息与空消息体_跳过并提交_不交给入账_其余照常() {
        List<Long> handled = new ArrayList<>();
        BattleResultConsumer loop = loop(event -> handled.add(event.getBattleId()));
        assign(List.of(TP0), record(0, 9001), record(0, 1, "9002", new byte[] {(byte) 0xFF, (byte) 0xFF, 0x01}),
                record(0, 2, "9003", null), record(3, 9004));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).containsExactly(9001L, 9004L);
        assertThat(consumer.commits).as("坏消息的位点照常推进").containsExactly(committed(TP0, 1), committed(TP0, 2), committed(TP0, 3), committed(TP0, 4));
        assertThat(decodeErrors()).isEqualTo(2);
        assertThat(delays).as("坏消息不触发重试").isEmpty();
    }

    @Test
    void 长度为零的消息体是合法的空事件_照常交给入账去判不计分() {
        List<BattleResultEvent> handled = new ArrayList<>();
        BattleResultConsumer loop = loop(handled::add);
        assign(List.of(TP0), record(0, 0, "", new byte[0]));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).containsExactly(BattleResultEvent.getDefaultInstance());
        assertThat(decodeErrors()).isZero();
        assertThat(consumer.commits).containsExactly(committed(TP0, 1));
    }

    @Test
    void key与battle_id不一致_只打日志_以消息体为准照常入账并提交() {
        List<Long> handled = new ArrayList<>();
        BattleResultConsumer loop = loop(event -> handled.add(event.getBattleId()));
        assign(List.of(TP0), record(0, 0, "12345", result(9001)), record(0, 1, null, result(9002)), record(0, 2, "", result(9003)));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).containsExactly(9001L, 9002L, 9003L);
        assertThat(consumer.commits).hasSize(3);
        assertThat(decodeErrors()).isZero();
    }

    // ================================================================ 可恢复故障

    @Test
    void 可恢复的库故障_暂停不提交_退避重试原记录_成功后才提交并恢复_后面的记录不越过它() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> handled = new ArrayList<>();
        List<Boolean> pausedDuringRetry = new ArrayList<>();
        List<Double> gaugeDuringRetry = new ArrayList<>();
        List<Integer> commitsDuringRetry = new ArrayList<>();
        BattleResultConsumer loop = loop(event -> {
            handled.add(event.getBattleId());
            if (event.getBattleId() == 9001 && attempts.incrementAndGet() < 3) {
                throw unreachable();
            }
        });
        assign(List.of(TP0), record(0, 9001), record(1, 9002));
        for (int i = 0; i < 2; i++) {
            consumer.schedulePollTask(() -> {
                pausedDuringRetry.add(consumer.paused().contains(TP0));
                gaugeDuringRetry.add(pausedGauge());
                commitsDuringRetry.add(consumer.commits.size());
            });
        }
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).as("重试的是原记录；9002 排在它后面").containsExactly(9001L, 9001L, 9001L, 9002L);
        assertThat(pausedDuringRetry).containsExactly(true, true);
        assertThat(gaugeDuringRetry).containsExactly(1.0, 1.0);
        assertThat(commitsDuringRetry).as("重试期间不提交").containsExactly(0, 0);
        assertThat(delays).as("退避 1 ms 起、每次翻倍").containsExactly(Duration.ofMillis(1), Duration.ofMillis(2));
        assertThat(consumer.commits).containsExactly(committed(TP0, 1), committed(TP0, 2));
        assertThat(consumer.paused()).isEmpty();
        assertThat(pausedGauge()).isZero();
    }

    @Test
    void 退避翻倍到上限为止() {
        AtomicInteger attempts = new AtomicInteger();
        BattleResultConsumer loop = loop(event -> {
            if (attempts.incrementAndGet() <= 5) {
                throw new IllegalStateException("不是 SQL 错误的故障也按可恢复处理");
            }
        });
        assign(List.of(TP0), record(0, 9001));
        idlePolls(5);
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(attempts.get()).isEqualTo(6);
        assertThat(delays).containsExactly(Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofMillis(4), Duration.ofMillis(4), Duration.ofMillis(4));
        assertThat(consumer.commits).containsExactly(committed(TP0, 1));
    }

    @Test
    void 每条记录的退避从头算起() {
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        BattleResultConsumer loop = loop(event -> {
            AtomicInteger attempts = event.getBattleId() == 9001 ? first : second;
            if (attempts.incrementAndGet() <= 2) {
                throw unreachable();
            }
        });
        assign(List.of(TP0), record(0, 9001), record(1, 9002));
        idlePolls(4);
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(delays).containsExactly(Duration.ofMillis(1), Duration.ofMillis(2), Duration.ofMillis(1), Duration.ofMillis(2));
        assertThat(consumer.commits).containsExactly(committed(TP0, 1), committed(TP0, 2));
    }

    @Test
    void 数据错误_写毒丸后跳过并提交_不重试_其余照常() {
        List<Long> handled = new ArrayList<>();
        BattleResultConsumer loop = loop(event -> {
            handled.add(event.getBattleId());
            if (event.getBattleId() == 9002) {
                throw new RatingStore.StoreException("对局结果入账失败", new SQLException("Out of range value for column 'games'", "22003", 1264));
            }
        });
        assign(List.of(TP0), record(0, 9001), record(1, 9002), record(2, 9003));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).as("9002 只试一次").containsExactly(9001L, 9002L, 9003L);
        assertThat(delays).isEmpty();
        assertThat(consumer.commits).containsExactly(committed(TP0, 1), committed(TP0, 2), committed(TP0, 3));
        assertThat(pausedGauge()).isZero();
        assertThat(rejected("MATCH_MODE_1V1")).as("被永久丢弃的这一局有自己的计数（告警用），模式取事件里的").isEqualTo(1);
        assertThat(rejected("MATCH_MODE_5V5")).isZero();
        assertThat(decodeErrors()).isZero();
    }

    @Test
    void 可恢复故障重试多少次都不计rejected_解不出的消息也不计() {
        AtomicInteger attempts = new AtomicInteger();
        BattleResultConsumer loop = loop(event -> {
            if (attempts.incrementAndGet() <= 3) {
                throw unreachable();
            }
        });
        assign(List.of(TP0), record(0, 9001), record(0, 1, "bad", new byte[] {(byte) 0xFF}));
        idlePolls(3);
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(attempts.get()).as("9001 失败三次后入账").isEqualTo(4);
        assertThat(consumer.commits).containsExactly(committed(TP0, 1), committed(TP0, 2));
        assertThat(decodeErrors()).isEqualTo(1);
        assertThat(rejected("MATCH_MODE_1V1")).as("库抖动不是丢数据；解不出的另有 decode_error").isZero();
    }

    // ================================================================ 重平衡与停止

    @Test
    void 重试期间分区被收走_放弃这条与同分区的后续记录_都不提交() {
        // 只有第一次失败：若分区被收走后还继续重试，第二次就会成功并提交，测试会失败
        AtomicInteger attempts = new AtomicInteger();
        List<Long> handled = new ArrayList<>();
        BattleResultConsumer loop = loop(event -> {
            handled.add(event.getBattleId());
            if (attempts.incrementAndGet() == 1) {
                throw unreachable();
            }
        });
        assign(List.of(TP0), record(0, 9001), record(1, 9002));
        consumer.schedulePollTask(() -> consumer.rebalance(List.of()));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).as("分区被收走后不再重试，也不处理本批里它剩下的记录").containsExactly(9001L);
        assertThat(consumer.commits).isEmpty();
        assertThat(pausedGauge()).isZero();
    }

    @Test
    void 重试期间别的分区被收走_自己这条继续重试直到成功() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> handled = new ArrayList<>();
        BattleResultConsumer loop = loop(event -> {
            handled.add(event.getBattleId());
            if (attempts.incrementAndGet() == 1) {
                throw unreachable();
            }
        });
        assign(List.of(TP0, TP1), record(0, 9001));
        // 第一次暂停中的 poll：TP1 被收走，TP0 还在
        consumer.schedulePollTask(() -> consumer.rebalance(List.of(TP0)));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).containsExactly(9001L, 9001L);
        assertThat(consumer.commits).containsExactly(committed(TP0, 1));
        // 只恢复仍归自己的分区；被收走的 TP1 不去碰它（真的 KafkaConsumer 对未分配的分区 resume 会抛异常）
        assertThat(consumer.paused()).doesNotContain(TP0);
        assertThat(consumer.assignment()).containsExactly(TP0);
    }

    @Test
    void 重试期间分区被收走又分回来_放弃手上这条_新分到的分区先暂停_恢复后从已提交位点重新拿到() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> handled = new ArrayList<>();
        List<Boolean> reassignedPaused = new ArrayList<>();
        BattleResultConsumer loop = loop(event -> {
            handled.add(event.getBattleId());
            if (attempts.incrementAndGet() == 1) {
                throw unreachable();
            }
        });
        assign(List.of(TP0), record(0, 9001));
        // 暂停中的 poll：先收走再分回来（整组重平衡）；位点没提交，broker 会从 0 重新投递这条
        consumer.schedulePollTask(() -> {
            consumer.rebalance(List.of());
            consumer.rebalance(List.of(TP0));
            reassignedPaused.add(consumer.paused().contains(TP0));
            consumer.updateBeginningOffsets(Map.of(TP0, 0L));
            consumer.addRecord(record(0, 9001));
        });
        // 恢复之后的第一次正常 poll 拿到重新投递的那条
        idlePolls(1);
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(reassignedPaused).as("重试暂停期间新分到的分区也跟着暂停").containsExactly(true);
        assertThat(handled).as("手上那条被放弃（没有在旧的归属下重试），重新投递后入账").containsExactly(9001L, 9001L);
        assertThat(delays).hasSize(1);
        assertThat(consumer.commits).containsExactly(committed(TP0, 1));
        assertThat(consumer.paused()).as("放弃之后恢复消费").isEmpty();
    }

    @Test
    void 停止时还在重试_不提交_留给下次重放() {
        BattleResultConsumer loop = loop(event -> {
            throw unreachable();
        });
        assign(List.of(TP0), record(0, 9001), record(1, 9002));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(consumer.commits).isEmpty();
        assertThat(delays).hasSize(1);
        assertThat(pausedGauge()).as("退出时暂停标志归零").isZero();
        assertThat(consumer.closed()).isTrue();
    }

    @Test
    void 两个分区的记录各自按位点提交() {
        List<Long> handled = new ArrayList<>();
        BattleResultConsumer loop = loop(event -> handled.add(event.getBattleId()));
        assign(List.of(TP0, TP1), record(0, 9001), record(1, 9002), record(1, 0, "9101", result(9101)));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).containsExactlyInAnyOrder(9001L, 9002L, 9101L);
        assertThat(handled.indexOf(9001L)).as("同一分区内保序").isLessThan(handled.indexOf(9002L));
        assertThat(consumer.commits).containsExactlyInAnyOrder(committed(TP0, 1), committed(TP0, 2), committed(TP1, 1));
    }

    @Test
    void 提交位点失败_只记日志_后面的记录照常处理与提交() {
        List<Long> handled = new ArrayList<>();
        BattleResultConsumer loop = loop(event -> handled.add(event.getBattleId()));
        consumer.failNextCommit = new KafkaException("协调者暂时不可用");
        assign(List.of(TP0), record(0, 9001), record(1, 9002));
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(handled).containsExactly(9001L, 9002L);
        assertThat(consumer.commits).as("第一条的提交失败了；第二条的提交覆盖它").containsExactly(committed(TP0, 2));
    }

    @Test
    void 没有记录时空转_停止后关闭消费者() {
        AtomicInteger calls = new AtomicInteger();
        BattleResultConsumer loop = loop(event -> calls.incrementAndGet());
        assign(List.of(TP0));
        consumer.schedulePollTask(() -> { });
        consumer.schedulePollTask(loop::stop);

        loop.run();

        assertThat(calls.get()).isZero();
        assertThat(consumer.commits).isEmpty();
        assertThat(consumer.closed()).isTrue();
    }
}
