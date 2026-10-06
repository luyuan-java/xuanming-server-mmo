package com.game.data.rollback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.game.audit.AuditKeys;
import com.game.audit.AuditTopicInitializer;
import com.game.audit.AuditTopicInitializer.Mode;
import com.game.audit.AuditTopics;
import com.game.audit.KafkaTopicAdmin;
import com.game.audit.proto.PlayerSnapshotRecord;
import com.game.audit.proto.SnapshotCause;
import com.game.data.consume.ConsumerLoop;
import com.game.data.ops.pb.OpsJobPlayerRow;
import com.game.data.ops.pb.OpsJobRow;
import com.game.data.ops.pb.OpsJobStatus;
import com.game.data.snapshot.PlayerSnapshotDecoder;
import com.game.data.snapshot.PlayerSnapshotRow;
import com.game.data.snapshot.PlayerSnapshotSink;
import com.game.data.snapshot.SnapshotCauses;
import com.game.data.store.PersistedPlayer;
import com.game.data.store.PlayerSnapshotEntry;
import com.game.player.store.state.CurrencyState;
import com.game.player.store.state.PlayerState;
import com.google.protobuf.ByteString;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * §12.5 的回档往返（缺省跳过：{@code -Dxm.it.kafka=127.0.0.1:9092}；库是 H2 或 {@code -Dxm.it.mysql} 的真 MySQL）：scene 发出的快照
 * 经<b>真 broker</b>、生产的消费循环与落库 Sink 进 {@code player_snapshot}，再由生产的回档受理 / 作业按 {@code targetTimeMs} 选中、写回。
 * 用与 {@code KafkaAuditPipelineTest} 相同的固定测试代次（本机 broker 不能删 topic）；消费组先把位点提交到当前末尾，只读本次运行发的记录，
 * 不把历次运行留在 topic 里的快照（含 1 MB 的大块头）再灌一遍。
 */
@EnabledIfSystemProperty(named = "xm.it.kafka", matches = ".+")
class RollbackKafkaRoundTripIntegrationTest {

    private static final int GENERATION = 9001;
    private static final String BOOTSTRAP = System.getProperty("xm.it.kafka");

    private RollbackHarness h;

    @AfterEach
    void tearDown() throws Exception {
        if (h != null) {
            h.close();
        }
    }

    private static KafkaConsumer<String, byte[]> consumer(String group) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, group);
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        p.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");
        return new KafkaConsumer<>(p, new StringDeserializer(), new ByteArrayDeserializer());
    }

    /** 把这个消费组在 {@code topic} 各分区的位点提交到当前末尾：之后用它订阅只会读到此后新发的记录。 */
    private static void skipHistory(String group, String topic) {
        try (KafkaConsumer<String, byte[]> c = consumer(group)) {
            List<TopicPartition> partitions = c.partitionsFor(topic, Duration.ofSeconds(20)).stream()
                    .map(info -> new TopicPartition(topic, info.partition())).toList();
            assertThat(partitions).isNotEmpty();
            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>();
            c.endOffsets(partitions, Duration.ofSeconds(20)).forEach((tp, end) -> offsets.put(tp, new OffsetAndMetadata(end)));
            c.commitSync(offsets, Duration.ofSeconds(20));
        }
    }

    private static PlayerSnapshotRecord record(long snapshotId, long player, long timeMs, SnapshotCause cause, int level,
                                               PlayerState state) {
        return PlayerSnapshotRecord.newBuilder().setSnapshotId(snapshotId).setPlayerId(player).setTimeMs(timeMs)
                .setCause(cause).setZoneId(1).setOwnerEpoch(3).setLevel(level).setSceneConfigId(2002).setPosX(10.5)
                .setPosY(0).setPosZ(-2.5).setPlayerState(ByteString.copyFrom(state.toByteArray())).build();
    }

    private static PlayerState gold(long amount) {
        return PlayerState.newBuilder().setCurrency(CurrencyState.newBuilder().addBalances(amount)).build();
    }

    @Test
    void scene的快照经Kafka落库后_回档按时刻选中目标之前最近的那份LOGOUT_写回的玩法数据与快照字节一致_封禁保留现档() throws Exception {
        String topic = AuditTopics.playerSnapshot(GENERATION).name();
        try (KafkaTopicAdmin admin = new KafkaTopicAdmin(BOOTSTRAP, "xm-it-admin")) {
            AuditTopicInitializer.ensure(admin, AuditTopics.all(GENERATION), Mode.OWN, (short) 1, Duration.ofSeconds(20));
        }
        long now = System.currentTimeMillis();
        long base = now * 1000;
        long player = ThreadLocalRandom.current().nextLong(1L << 40, 1L << 50);
        String group = "xm-it-rollback-" + base;
        skipHistory(group, topic);

        h = new RollbackHarness(Map.of());
        // 现档：金币 1500、钻石 20、封禁了金币（快照之后 GM 封的，回档不解除处罚）
        PlayerState current = RollbackJobSqlTest.currentState();
        h.player(player, 1, current.toByteArray(), now - 5000, 3, false);

        // scene 发的三份快照：更早的 LOGIN、要选中的 LOGOUT、目标时刻之后的 LOGOUT
        PlayerState atLogout = RollbackJobSqlTest.snapshotState();
        long loginTime = now - 300_000;
        long logoutTime = now - 120_000;
        long laterLogoutTime = now - 20_000;
        PlayerSnapshotRecord login = record(base + 1, player, loginTime, SnapshotCause.SNAPSHOT_LOGIN, 4, gold(1));
        PlayerSnapshotRecord logout = record(base + 2, player, logoutTime, SnapshotCause.SNAPSHOT_LOGOUT, 5, atLogout);
        PlayerSnapshotRecord laterLogout = record(base + 3, player, laterLogoutTime, SnapshotCause.SNAPSHOT_LOGOUT, 6,
                gold(9999));
        Properties pp = new Properties();
        pp.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP);
        pp.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, byte[]> producer =
                     new KafkaProducer<>(pp, new StringSerializer(), new ByteArraySerializer())) {
            for (PlayerSnapshotRecord r : List.of(login, logout, laterLogout)) {
                // 同 xm-scene：按玩家号分区（同一玩家的快照有序）
                producer.send(new ProducerRecord<>(topic, AuditKeys.playerKey(player), r.toByteArray())).get();
            }
        }

        // 生产的消费循环 + 落库 Sink，落到回档要读的同一个库
        h.metrics.registerConsumer("it-rollback");
        ConsumerLoop<PlayerSnapshotRow> loop = new ConsumerLoop<>("it-rollback", consumer(group), topic,
                new PlayerSnapshotDecoder(), new PlayerSnapshotSink(h.db.snapshots, h.db.tx(), 10, Clock.systemUTC()), h.metrics,
                Duration.ofMillis(200), Duration.ofMillis(100), Duration.ofSeconds(1));
        Thread thread = new Thread(loop, "it-rollback-consumer");
        thread.start();
        try {
            await().atMost(Duration.ofSeconds(60)).until(() -> h.db.snapshots.listByPlayer(player, 0, Long.MAX_VALUE, null,
                    false, 10).size() == 3);
        } finally {
            loop.stop();
            thread.join(10_000);
        }
        PlayerSnapshotEntry landed = h.db.snapshots.findById(base + 2);
        assertThat(landed.getCause()).isEqualTo(SnapshotCauses.LOGOUT);
        assertThat(landed.getTimeMs()).isEqualTo(logoutTime);
        assertThat(landed.getPlayerState()).isEqualTo(atLogout.toByteArray());
        assertThat(landed.getOperator()).as("经 Kafka 落库的没有操作人").isEmpty();
        assertThat(landed.getIngestedAt()).isGreaterThanOrEqualTo(now);

        // 预演：目标时刻 = 60 s 前 → 选中的是 120 s 前那份 LOGOUT（不是更新的那份，也不是更早的 LOGIN）
        long target = now - 60_000;
        RollbackRequest.Body dryRun = new RollbackRequest.Body("players", List.of(Long.toUnsignedString(player)), null, null,
                null, target, null, null, false, false, "预演", true);
        JsonNode plan = h.json.valueToTree(h.rollbacks.handle(dryRun, "ops", null));
        assertThat(plan.at("/players/0/snapshot/snapshotId").asText()).isEqualTo(Long.toUnsignedString(base + 2));
        assertThat(plan.at("/players/0/snapshot/causeName").asText()).isEqualTo("LOGOUT");

        OpsJobRow job = h.await(h.submit(RollbackHarness.players(List.of(Long.toUnsignedString(player)), null, target, null,
                null, false), "k-" + base));

        assertThat(job.getStatus()).isEqualTo(OpsJobStatus.OPS_JOB_SUCCEEDED);
        OpsJobPlayerRow detail = h.jobs.players(job.getJobId(), 0, 10).get(0);
        assertThat(detail.getOutcome()).isEqualTo("RESTORED");
        assertThat(detail.getPlannedSnapshotId()).isEqualTo(base + 2);
        assertThat(detail.getPlannedSnapshotMs()).isEqualTo(logoutTime);
        // 写回的玩法数据：除了保留下来的封禁名单，与 scene 发的那份快照逐字节相同
        PersistedPlayer persisted = h.db.players.find(player);
        PlayerState restored = PlayerState.parseFrom(persisted.stateBytes());
        assertThat(restored.getCurrency().getBlockedTypesList()).isEqualTo(current.getCurrency().getBlockedTypesList())
                .containsExactly(0);
        assertThat(restored.toBuilder().setCurrency(restored.getCurrency().toBuilder().clearBlockedTypes()).build()
                .toByteArray()).isEqualTo(logout.getPlayerState().toByteArray());
        // player 行的等级 / 场景 / 坐标也取那份快照的
        assertThat(persisted.getLevel()).isEqualTo(5);
        assertThat(persisted.getSceneConfigId()).isEqualTo(2002);
        assertThat(List.of(persisted.getPosX(), persisted.getPosY(), persisted.getPosZ())).containsExactly(10.5, 0.0, -2.5);
        // 安全快照里是被覆盖之前的现档
        assertThat(h.db.snapshots.findById(detail.getPreSnapshotId()).getPlayerState()).isEqualTo(current.toByteArray());
    }
}
