package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.game.audit.AuditTopicContractException;
import com.game.audit.AuditTopicInitializer.Mode;
import com.game.audit.BattleResultTopics;
import com.game.audit.KafkaTopicAdmin;
import com.game.audit.TopicSpec;
import com.game.match.metrics.MatchMetrics;
import com.game.match.metrics.MetricLabels;
import com.game.match.support.MatchModes;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * 对局结果 topic 连真 Kafka 的集成测试（{@code -Dxm.it.kafka=127.0.0.1:9092}，缺省跳过；match-spec §15.3）：topic 的创建与核对、
 * 分区数不符时拒绝启动、端到端生产一条消费入账一条。
 *
 * <p>本机 broker 不能删 topic，所以用固定的测试代次（{@value #GENERATION} 与预先建成 2 分区的 {@value #MISMATCH_GENERATION}，幂等创建、反复使用，
 * 与本机切片用的代次 1 不相干）；每次运行用随机的战斗号 / 玩家号与唯一的消费组，不互相干扰。topic 里会留着历次运行的消息——新消费组从最早位点
 * 读起，所以断言只看本次运行的战斗号。评分库用 H2（真 MySQL 的事务行为在 {@code RatingStoreSqlTest} 里测）。
 */
@EnabledIfSystemProperty(named = "xm.it.kafka", matches = ".+")
class BattleResultTopicIntegrationTest {

    private static final int GENERATION = 9641;
    private static final int MISMATCH_GENERATION = 9642;
    private static final String BOOTSTRAP = System.getProperty("xm.it.kafka");
    private static final Duration TIMEOUT = Duration.ofSeconds(20);
    private static final Duration WAIT = Duration.ofSeconds(90);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MatchMetrics metrics = new MatchMetrics(meters, new MetricLabels(id -> false));

    private static KafkaProducer<String, byte[]> producer() {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        p.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, "true");
        return new KafkaProducer<>(p, new StringSerializer(), new ByteArraySerializer());
    }

    private static BattleResultEvent result(long battleId, int mode, long playerA, long playerB) {
        return BattleResultEvent.newBuilder().setBattleId(battleId).setMatchMode(mode).setOutcome(eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(playerA))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(playerB))
                .setTotalRounds(4).setFinishedAtMs(System.currentTimeMillis()).build();
    }

    /** 按契约发一条：key = battle_id 的无符号十进制，value = 事件字节。 */
    private static RecordMetadata send(KafkaProducer<String, byte[]> producer, BattleResultEvent event) throws Exception {
        return producer.send(new ProducerRecord<>(BattleResultTopics.name(GENERATION), BattleResultTopics.key(event.getBattleId()), event.toByteArray()))
                .get();
    }

    private BattleResultIngest ingest(int generation, String group, BattleResultConsumer.Handler handler) {
        return new BattleResultIngest(new BattleResultIngest.Settings(true, generation, (short) 1, TIMEOUT),
                () -> new KafkaTopicAdmin(BOOTSTRAP, "xm-match-it-admin"),
                BattleResultIngest.kafkaConsumers(BOOTSTRAP, group, "xm-match-it-" + UUID.randomUUID()), handler, metrics);
    }

    @Test
    void topic的创建与核对_三分区保留七天_幂等_主人把被改动的保留期校正回来() {
        String topic = BattleResultTopics.name(GENERATION);
        assertThat(topic).isEqualTo("xm-battle-result-g9641");
        try (KafkaTopicAdmin admin = new KafkaTopicAdmin(BOOTSTRAP, "xm-match-it-admin")) {
            BattleResultTopics.ensure(admin, GENERATION, Mode.OWN, (short) 1, TIMEOUT);
            BattleResultTopics.ensure(admin, GENERATION, Mode.OWN, (short) 1, TIMEOUT);

            assertThat(admin.partitionCounts(List.of(topic), TIMEOUT)).containsEntry(topic, 3);
            assertThat(admin.configs(topic, List.of("retention.ms", "retention.bytes", "cleanup.policy"), TIMEOUT))
                    .containsEntry("retention.ms", "604800000").containsEntry("retention.bytes", "-1").containsEntry("cleanup.policy", "delete");

            // 有人把保留期改短了：生产方（xm-battle 的口径，只核对分区数）不管它，主人（xm-match）校正回 7 天
            admin.alterConfigs(topic, Map.of("retention.ms", "3600000"), TIMEOUT);
            BattleResultTopics.ensure(admin, GENERATION, Mode.CREATE_AND_VERIFY, (short) 1, TIMEOUT);
            assertThat(admin.configs(topic, List.of("retention.ms"), TIMEOUT)).containsEntry("retention.ms", "3600000");
            BattleResultTopics.ensure(admin, GENERATION, Mode.OWN, (short) 1, TIMEOUT);
            assertThat(admin.configs(topic, List.of("retention.ms"), TIMEOUT)).containsEntry("retention.ms", "604800000");
        }
    }

    @Test
    void 分区数与契约不符_结果消费拒绝启动_不建消费者() {
        String topic = BattleResultTopics.name(MISMATCH_GENERATION);
        try (KafkaTopicAdmin admin = new KafkaTopicAdmin(BOOTSTRAP, "xm-match-it-admin")) {
            // 模拟「broker 自动建出来的 / 别人手工建的」2 分区 topic（已存在视为成功）
            admin.create(List.of(new TopicSpec(topic, 2, Map.of())), (short) 1, TIMEOUT);
            await().atMost(WAIT).until(() -> admin.partitionCounts(List.of(topic), TIMEOUT).containsKey(topic));
            assertThat(admin.partitionCounts(List.of(topic), TIMEOUT)).containsEntry(topic, 2);
        }
        AtomicInteger handled = new AtomicInteger();
        BattleResultIngest ingest = ingest(MISMATCH_GENERATION, "xm-match-it-" + UUID.randomUUID(), event -> handled.incrementAndGet());

        assertThatThrownBy(ingest::start).isInstanceOf(AuditTopicContractException.class)
                .hasMessageContaining(topic).hasMessageContaining("分区数是 2").hasMessageContaining("契约是 3")
                .hasMessageContaining("XM_BATTLE_RESULT_TOPIC_GENERATION");

        assertThat(ingest.isRunning()).isFalse();
        assertThat(ingest.consuming()).isFalse();
        assertThat(handled.get()).isZero();
        ingest.stop();
    }

    @Test
    void 端到端_生产的对局结果被消费并入账一次_重复与坏消息不影响_提交后同组换消费者不重放() throws Exception {
        long base = ThreadLocalRandom.current().nextLong(1L << 40, 1L << 60);
        long battle = base;
        long pveBattle = base + 1;
        long sentinelBattle = base + 2;
        long markerBattle = base + 3;
        long winner = base + 11;
        long loser = base + 12;
        String group = "xm-match-it-" + UUID.randomUUID();

        try (RatingTestDatabase db = RatingTestDatabase.h2(); KafkaProducer<String, byte[]> producer = producer()) {
            RatingStore store = new RatingStore(RatingStore.connections(db.dataSource), configId -> 30, metrics, System::currentTimeMillis);
            // 本次运行那一局的各次投递各得到什么结局（topic 里历次运行留下的消息也会被读到，不看它们）
            List<RatingStore.Outcome> outcomes = new CopyOnWriteArrayList<>();
            AtomicInteger markerSeen = new AtomicInteger();
            BattleResultIngest first = ingest(GENERATION, group, event -> {
                RatingStore.Result applied = store.apply(event);
                if (event.getBattleId() == battle) {
                    outcomes.add(applied.outcome());
                } else if (event.getBattleId() == markerBattle) {
                    markerSeen.incrementAndGet();
                }
            });
            // 先起消费者：topic 不存在时由它（主人）创建；之后才生产
            first.start();
            RecordMetadata sent;
            try {
                BattleResultEvent rated = result(battle, MatchModes.ONE_V_ONE, winner, loser);
                // 坏消息、不计分的 PVE 局、计分局、同一局再发一遍（生产方重试 / 回灌）
                producer.send(new ProducerRecord<>(first.topic(), BattleResultTopics.key(battle), new byte[] {(byte) 0xFF, (byte) 0xFF, 0x01})).get();
                send(producer, result(pveBattle, MatchModes.PVE_TEAM, winner, loser));
                sent = send(producer, rated);
                send(producer, rated);

                // 同一分区再补一条标记：循环处理到它时，前面两条的位点一定已经各自提交过了（逐条处理、逐条提交）
                producer.send(new ProducerRecord<>(first.topic(), sent.partition(), BattleResultTopics.key(markerBattle),
                        result(markerBattle, MatchModes.PVE_SOLO, winner, loser).toByteArray())).get();

                await().atMost(WAIT).until(() -> outcomes.size() == 2 && markerSeen.get() >= 1);

                assertThat(outcomes).as("同一局发了两遍：先入账，再判重复").containsExactly(RatingStore.Outcome.APPLIED, RatingStore.Outcome.DUPLICATE);
                assertThat(duplicates()).isGreaterThanOrEqualTo(1);
                assertThat(sent.topic()).isEqualTo("xm-battle-result-g9641");
                assertThat(db.ratingRow(winner)).as("胜者 +16，只入账一次").hasValueSatisfying(row -> {
                    assertThat(row[0]).isEqualTo(151_600);
                    assertThat(row[1]).isEqualTo(1);
                });
                assertThat(db.ratingRow(loser)).hasValueSatisfying(row -> {
                    assertThat(row[0]).isEqualTo(148_400);
                    assertThat(row[1]).isEqualTo(1);
                });
                assertThat(db.appliedRow(battle)).hasValueSatisfying(row -> {
                    assertThat(row[0]).isEqualTo(MatchModes.ONE_V_ONE);
                    assertThat(row[1]).isEqualTo(1_600);
                });
                assertThat(db.appliedRow(pveBattle)).as("PVE 局不入账").isEmpty();
                assertThat(decodeErrors()).as("坏消息被跳过").isGreaterThanOrEqualTo(1);
                assertThat(first.consuming()).isTrue();
            } finally {
                first.stop();
            }
            assertThat(first.consuming()).isFalse();

            // 同组换一个消费者：位点已提交，本次运行的计分局不会再来。往同一个分区补一条哨兵，读到它就说明那个分区已经读到头了
            List<Long> replayed = new CopyOnWriteArrayList<>();
            BattleResultIngest second = ingest(GENERATION, group, event -> replayed.add(event.getBattleId()));
            second.start();
            try {
                producer.send(new ProducerRecord<>(second.topic(), sent.partition(), BattleResultTopics.key(sentinelBattle),
                        result(sentinelBattle, MatchModes.PVE_SOLO, winner, loser).toByteArray())).get();

                await().atMost(WAIT).until(() -> replayed.contains(sentinelBattle));

                assertThat(replayed).as("已提交的记录不重放").doesNotContain(battle);
            } finally {
                second.stop();
            }
            assertThat(db.games(winner)).isEqualTo(1);
        }
    }

    private double duplicates() {
        return updates("MATCH_MODE_1V1", "duplicate");
    }

    private double decodeErrors() {
        return updates("unknown", "decode_error");
    }

    /** 读 {@code xm_match_rating_updates_total}（本测试自己的注册表）。 */
    private double updates(String mode, String outcome) {
        return meters.get("xm.match.rating.updates").tag("mode", mode).tag("outcome", outcome).counter().count();
    }
}
