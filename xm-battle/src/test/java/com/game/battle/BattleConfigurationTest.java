package com.game.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.game.battle.admission.AdmissionGate;
import com.game.battle.admission.AdmissionPhase;
import com.game.battle.admin.BattleAdminAuthFilter;
import com.game.battle.admin.DevBattleBackend;
import com.game.battle.port.ActivityResultSink;
import com.game.battle.port.BattleResultSink;
import com.game.battle.port.LoggingBattleResultSink;
import com.game.battle.port.SettlementSink;
import com.game.battle.port.kafka.BattleResultProperties;
import com.game.battle.port.kafka.KafkaBattleResultSink;
import com.game.battle.port.scene.DubboSceneBattleEvents;
import com.game.battle.port.SceneBattleEvents;
import com.game.battle.port.scene.SceneTransport;
import com.game.battle.push.LobbyAnnouncer;
import com.game.battle.push.PresenceLobbyAnnouncer;
import com.game.battle.testing.FakeResultKafka;
import com.game.battle.testing.FakeResultKafka.Sent;
import com.game.battle.testing.ResultFallbackCapture;
import com.game.common.RunMode;
import com.game.common.token.BattleTickets;
import com.game.discovery.RedisProperties;
import com.game.proto.BattleActivityContext;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.eBattleActivityKind;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * 启动门禁与装配（battle-node-spec §6.3、§7.11 第 1–3 步、§13.5；{@code ApplicationContextRunner}，不连 Redis、不开端口：基础设施用
 * {@link FakeBattleInfrastructure}）。秘密都经属性显式给出，盖住开发机上可能已设置的同名环境变量。对局结果的 Kafka 客户端换成
 * {@link FakeResultKafka}（不连 Kafka；装配本身——{@code KafkaBattleResultSink} 的建立、启动期核对、关闭次序——是真的）。
 */
@ExtendWith(OutputCaptureExtension.class)
class BattleConfigurationTest {

    static final String SECRET = "battle-ticket-secret-for-context-tests-0123456789";
    static final String GATE_SECRET = "gate-token-secret-for-context-tests-0123456789";

    private final FakeBattleInfrastructure infra = new FakeBattleInfrastructure();
    private final FakeResultKafka kafka = new FakeResultKafka();

    /** 同生产的装配：<b>不</b>提供 Kafka 客户端的替换口（{@code BattleConfiguration} 自己建真 Kafka 客户端）。只有末尾两条用例用它。 */
    private final ApplicationContextRunner realKafkaClients = new ApplicationContextRunner()
            .withUserConfiguration(BattleConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withBean(BattleInfrastructure.class, () -> infra)
            .withPropertyValues(
                    "xm.advertise-host=127.0.0.1",
                    "xm.table-dir=../config-data/tables",
                    "XM_DUBBO_SECRET=dubbo-secret-for-context-tests",
                    "XM_ADMIN_TOKEN=",
                    "xm.run-mode=prod",
                    BattleConfiguration.TICKET_SECRET_ENV + "=" + SECRET,
                    BattleConfiguration.GATE_SECRET_ENV + "=" + GATE_SECRET);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BattleConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withBean(BattleInfrastructure.class, () -> infra)
            .withBean(KafkaBattleResultSink.Clients.class, () -> kafka)
            .withPropertyValues(
                    "xm.advertise-host=127.0.0.1",
                    "xm.table-dir=../config-data/tables",
                    "XM_DUBBO_SECRET=dubbo-secret-for-context-tests",
                    "XM_ADMIN_TOKEN=",
                    BattleConfiguration.GATE_SECRET_ENV + "=" + GATE_SECRET);

    private ApplicationContextRunner prod(String secret) {
        return runner.withPropertyValues("xm.run-mode=prod", BattleConfiguration.TICKET_SECRET_ENV + "=" + secret);
    }

    private ApplicationContextRunner dev(String secret) {
        return runner.withPropertyValues("xm.run-mode=dev", BattleConfiguration.TICKET_SECRET_ENV + "=" + secret);
    }

    @Test
    void 配置齐全_装配完整_节点按生命周期启动并开闸() {
        prod(SECRET).run(ctx -> {
            assertThat(ctx).hasNotFailed()
                    .hasSingleBean(BattleNode.class)
                    .hasSingleBean(BattleTickets.class)
                    .hasSingleBean(BattleTables.class)
                    .hasSingleBean(DevBattleBackend.class)
                    .hasSingleBean(FilterRegistrationBean.class);
            assertThat(ctx.getBean(RunMode.class)).isEqualTo(RunMode.PROD);
            assertThat(ctx.getBean(LobbyAnnouncer.class)).isInstanceOf(PresenceLobbyAnnouncer.class);
            assertThat(ctx.getBean(SceneBattleEvents.class)).isInstanceOf(DubboSceneBattleEvents.class);
            BattleNode node = ctx.getBean(BattleNode.class);
            assertThat(node.isRunning()).isTrue();
            assertThat(ctx.getBean(AdmissionGate.class).phase()).isEqualTo(AdmissionPhase.OPEN);
            assertThat(ctx.getBean(DevBattleBackend.class).controlPlane()).isPresent();
            BattleTables tables = ctx.getBean(BattleTables.class);
            assertThat(tables.fingerprint()).isNotBlank();
            assertThat(infra.roomDeps.tableFingerprint()).isEqualTo(tables.fingerprint());
            assertThat(infra.published).singleElement().satisfies(info -> {
                assertThat(info.getAccepting()).isTrue();
                assertThat(info.getTableFingerprint()).isEqualTo(tables.fingerprint());
            });
            assertThat(infra.events).contains("rpc.export:127.0.0.1:21200");
        });
        assertThat(infra.events).as("上下文关闭时节点按顺序停机").endsWith("rpc.close", "lease.close");
    }

    @Test
    void 客户端通告地址与控制面通告地址分开配_票据与目录client_host取前者_Dubbo与rpc_host取后者(CapturedOutput output) {
        prod(SECRET).withPropertyValues("xm.advertise-host=xm-battle", "xm.battle.client-advertise-host=battle.example.com").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            BattleIdentity identity = ctx.getBean(BattleNode.class).identity().orElseThrow();
            assertThat(identity.advertiseHost()).isEqualTo("battle.example.com");
            assertThat(infra.events).contains("rpc.export:xm-battle:21200");
            assertThat(infra.published).singleElement().satisfies(info -> {
                assertThat(info.getRpcHost()).isEqualTo("xm-battle");
                assertThat(info.getClientHost()).isEqualTo("battle.example.com");
                assertThat(info.getClientPort()).isEqualTo(12000);
            });
        });
        assertThat(output.getOut()).contains("advertise=battle.example.com:12000").contains("rpc=xm-battle:");
    }

    @Test
    void 启动日志打出七张战斗表的行数与指纹(CapturedOutput output) {
        prod(SECRET).run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(output.getOut()).contains("battle 战斗表加载完成: skill=").contains("item=")
                .contains("table_fingerprint=").contains("battle 节点已就绪");
    }

    @Test
    void prod缺票据密钥_拒启() {
        runner.withPropertyValues("xm.run-mode=prod", BattleConfiguration.TICKET_SECRET_ENV + "=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("XM_BATTLE_TOKEN_SECRET 未配置");
        });
        assertThat(infra.events).as("门禁在任何端口打开之前").isEmpty();
    }

    @Test
    void dev缺票据密钥仍拒启_纯空白视同未配() {
        dev("   ").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("任何运行模式都必填");
        });
    }

    @Test
    void prod密钥太短_拒启() {
        prod("short-secret").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("不足 32 字节");
        });
    }

    @Test
    void prod密钥与gate相同_去首尾空白后比较_拒启() {
        prod("  " + GATE_SECRET + "\t").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("与 XM_GATE_TOKEN_SECRET 相同");
        });
    }

    @Test
    void gate密钥不可见时不比() {
        runner.withPropertyValues("xm.run-mode=prod", BattleConfiguration.TICKET_SECRET_ENV + "=" + GATE_SECRET,
                BattleConfiguration.GATE_SECRET_ENV + "=").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void dev密钥太短或与gate相同_只告警照常启动(CapturedOutput output) {
        dev("short-secret").run(ctx -> assertThat(ctx).hasNotFailed());
        dev(GATE_SECRET).run(ctx -> assertThat(ctx).hasNotFailed());
        assertThat(output.getOut()).contains("不足 32 字节").contains("与 XM_GATE_TOKEN_SECRET 相同").contains("只因 run_mode=dev 才放行");
    }

    @Test
    void 运行模式写错按prod判定并告警(CapturedOutput output) {
        runner.withPropertyValues("xm.run-mode=develop", BattleConfiguration.TICKET_SECRET_ENV + "=short-secret").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("run_mode=prod");
        });
        assertThat(output.getOut()).contains("取值不认识");
    }

    @Test
    void prod下max_connections为0_拒启_dev允许() {
        prod(SECRET).withPropertyValues("xm.battle.max-connections=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("max-connections = 0");
        });
        dev(SECRET).withPropertyValues("xm.battle.max-connections=0").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 缺XM_DUBBO_SECRET_拒启() {
        prod(SECRET).withPropertyValues("XM_DUBBO_SECRET=").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("XM_DUBBO_SECRET");
        });
        assertThat(infra.events).isEmpty();
    }

    @Test
    void 指纹模式写错_拒启_合法值大小写不敏感() {
        prod(SECRET).withPropertyValues("xm.battle.table-fingerprint-mode=strict").run(ctx -> assertThat(ctx).hasFailed());
        prod(SECRET).withPropertyValues("xm.battle.table-fingerprint-mode=enforce").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(infra.roomDeps.fingerprintMode()).isEqualTo(com.game.battle.room.FingerprintMode.ENFORCE);
        });
    }

    @Test
    void 握手期限越界_拒启() {
        prod(SECRET).withPropertyValues("xm.battle.handshake-timeout=61s").run(ctx -> assertThat(ctx).hasFailed());
        prod(SECRET).withPropertyValues("xm.battle.handshake-timeout=0s").run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void 配表目录不对_拒启() {
        prod(SECRET).withPropertyValues("xm.table-dir=does-not-exist").run(ctx -> assertThat(ctx).hasFailed());
        assertThat(infra.events).isEmpty();
    }

    // ---------------------------------------------------------------- 6.3：battle → scene 的真实传输（scene-battle-spec §7.15–§7.17、§8）

    private static final BattleRouting ROUTING = BattleRouting.newBuilder().setZoneId(1).setSceneNodeId(3).setSceneInstanceId("scene-a")
            .build();

    private static BattleSettlementData settlement(long battleId) {
        return BattleSettlementData.newBuilder().setBattleId(battleId).setPlayerId(9001).setGoldGain(10).build();
    }

    private static Counter outboxEvent(MeterRegistry meters, String event) {
        return meters.get("xm.battle.settlement.outbox").tag("event", event).counter();
    }

    @Test
    void 三个出站端口都接到SceneTransport_结算端口背后是真的发件箱_结果发布端口是Kafka生产方() {
        prod(SECRET).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(SceneTransport.class);
            SceneTransport transport = ctx.getBean(SceneTransport.class);
            assertThat(ctx.getBean(SceneBattleEvents.class)).isSameAs(transport.sceneEvents());
            assertThat(ctx.getBean(ActivityResultSink.class)).isSameAs(transport.activityResults());
            assertThat(ctx.getBean(BattleResultSink.class)).isInstanceOf(KafkaBattleResultSink.class);
            assertThat(infra.roomDeps.results()).as("房间拿到的普通结果端口就是这个 bean").isSameAs(ctx.getBean(BattleResultSink.class));
            assertThat(infra.roomDeps.settlements()).as("房间拿到的就是这个 bean").isSameAs(ctx.getBean(SettlementSink.class));
            assertThat(infra.roomDeps.activityResults()).isSameAs(transport.activityResults());
            assertThat(infra.roomDeps.sceneEvents()).isSameAs(transport.sceneEvents());
            MeterRegistry meters = ctx.getBean(MeterRegistry.class);

            ctx.getBean(SettlementSink.class).dispatch(ROUTING, 9001, settlement(77001));

            // Redisson 是 mock，落库必然失败：发件箱按 not_durable 处理、定位也失败——这些只有真的 SettlementOutbox 才会计
            assertThat(meters.get("xm.battle.scene.events").tag("kind", "settlement").tag("result", "sent").counter().count()).isEqualTo(1);
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(outboxEvent(meters, "not_durable").count()).isEqualTo(1);
                assertThat(outboxEvent(meters, "locate_error").count()).isEqualTo(1);
                assertThat(transport.settlements().inFlight()).isZero();
            });
            assertThat(outboxEvent(meters, "stored").count()).isZero();
            assertThat(outboxEvent(meters, "already_settled").count()).as("新取值随装配预建").isZero();
            assertThat(outboxEvent(meters, "fields_overflow").count()).isZero();
        });
    }

    @Test
    void scene_rpc_timeout不大于Redis单条命令最坏耗时_拒启_消息带键名_发生在任何端口打开之前() {
        prod(SECRET).withPropertyValues("xm.battle.scene-rpc-timeout=4s").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("xm.battle.scene-rpc-timeout").hasMessageContaining("4200 ms");
        });
        prod(SECRET).withPropertyValues("xm.battle.scene-rpc-timeout=4200ms").run(ctx -> assertThat(ctx).as("相等也不行").hasFailed());
        assertThat(infra.events).as("门禁在任何端口打开之前").isEmpty();

        prod(SECRET).withPropertyValues("xm.battle.scene-rpc-timeout=4201ms").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void scene_rpc_timeout的门槛跟着Redis配置走() {
        ApplicationContextRunner slowRedis = prod(SECRET).withBean(RedisProperties.class,
                () -> new RedisProperties(null, null, null, null, 3000, 1, 200));

        slowRedis.run(ctx -> {
            assertThat(ctx).as("缺省 5 s 不大于 (1 + 1) × 3000 + 200").hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("xm.battle.scene-rpc-timeout")
                    .hasMessageContaining("6200 ms");
        });
        assertThat(infra.events).isEmpty();
        slowRedis.withPropertyValues("xm.battle.scene-rpc-timeout=7s").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void scene_rpc_timeout或outbox_drain_timeout取值非法_拒启() {
        prod(SECRET).withPropertyValues("xm.battle.scene-rpc-timeout=0s").run(ctx -> assertThat(ctx).hasFailed());
        prod(SECRET).withPropertyValues("xm.battle.outbox-drain-timeout=-1s").run(ctx -> assertThat(ctx).hasFailed());
        assertThat(infra.events).isEmpty();
        prod(SECRET).withPropertyValues("xm.battle.outbox-drain-timeout=0s").run(ctx -> assertThat(ctx).as("0 = 停机不等").hasNotFailed());
    }

    /**
     * 这里只钉<b>次序</b>：节点先停、传输后关，且上下文销毁时真的调了 {@code SceneTransport.close}。Redisson 是 mock，落库在发件箱线程上当场失败，
     * 节点停机途中交出的那份结算早在传输关闭之前就走完了——所以本用例对「关之前<b>等</b>在途的落库」（有界排空）没有判别力，那一段接线
     * （先 {@code drainAndClose(outbox-drain-timeout)}、后停 {@code battle-outbox}）由 {@code SceneTransportTest} 用悬着的落库钉住：
     * {@code 关闭传输_先等在途的落库回来_…} 与 {@code 排空上限配成0_关闭不等在途的落库_…}。
     */
    @Test
    void 上下文关闭_节点先停_传输后关_节点停机途中交出的结算仍进发件箱_传输关闭之后的只打日志(CapturedOutput output) {
        AtomicReference<SceneTransport> closedTransport = new AtomicReference<>();
        AtomicReference<Counter> notDurable = new AtomicReference<>();
        prod(SECRET).run(ctx -> {
            SceneTransport transport = ctx.getBean(SceneTransport.class);
            SettlementSink sink = ctx.getBean(SettlementSink.class);
            closedTransport.set(transport);
            notDurable.set(outboxEvent(ctx.getBean(MeterRegistry.class), "not_durable"));
            // 节点停机途中（反导出控制面那一步）房间交出最后一份结算
            infra.onRpcClose = () -> sink.dispatch(ROUTING, 9001, settlement(77001));
        });

        assertThat(infra.events).endsWith("rpc.close", "lease.close");
        assertThat(notDurable.get().count()).as("节点停机时发件箱还开着（传输若先关，这份结算只会打日志、不计数）").isEqualTo(1);
        assertThat(closedTransport.get().settlements().inFlight()).isZero();
        assertThat(output.getOut()).as("上下文销毁时调了 SceneTransport.close").contains("battle → scene 传输已关闭");
        assertThat(output.getOut()).doesNotContain("结算发件箱已关闭");

        closedTransport.get().settlementSink().dispatch(ROUTING, 9001, settlement(77002));
        assertThat(output.getOut()).as("传输关闭时把结算发件箱置为已关闭：之后的结算在调用线程上当场拒掉（不是靠线程已停才进不去）")
                .contains("结算发件箱已关闭").contains("battle_id=77002");
        assertThat(notDurable.get().count()).as("传输关闭之后的结算只打日志，不再落库").isEqualTo(1);
        assertThat(closedTransport.get().settlements().inFlight()).isZero();
    }

    @Test
    void 运维令牌没配_过滤器回503() {
        prod(SECRET).run(ctx -> {
            @SuppressWarnings("unchecked")
            FilterRegistrationBean<BattleAdminAuthFilter> registration = ctx.getBean(FilterRegistrationBean.class);
            assertThat(registration.getFilter().tokenConfigured()).isFalse();
            assertThat(registration.getUrlPatterns()).containsExactly("/admin/*");
        });
    }

    // ---------------------------------------------------------------- 6.4：对局结果的 Kafka 生产（match-spec §5.4）

    private static final String RESULT_TOPIC = "xm-battle-result-g1";

    private static BattleResultEvent result(long battleId) {
        return BattleResultEvent.newBuilder().setBattleId(battleId).setMatchMode(1).setTotalRounds(7).setFinishedAtMs(1_800_000_000_000L)
                .build();
    }

    private static BattleResultEvent activityResult(long battleId) {
        return result(battleId).toBuilder().setMatchMode(5).setActivityContext(BattleActivityContext.newBuilder()
                .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(66).setActivityId(3)).build();
    }

    private static double results(MeterRegistry meters, String channel, String result) {
        return meters.get("xm.battle.results").tag("channel", channel).tag("result", result).counter().count();
    }

    private static double resultEvents(MeterRegistry meters, String result) {
        return meters.get("xm.battle.result.events").tag("result", result).counter().count();
    }

    @Test
    void 对局结果_启动时核对出三分区的topic_缺省代次1_房间与活动结果通道共用同一个Kafka生产方_按通道计数_上下文关闭时关生产者() {
        prod(SECRET).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(BattleResultSink.class).hasSingleBean(KafkaBattleResultSink.class);
            KafkaBattleResultSink sink = ctx.getBean(KafkaBattleResultSink.class);
            assertThat(sink.topic()).isEqualTo(RESULT_TOPIC);
            assertThat(sink.verified()).isTrue();
            assertThat(ctx.getBean(BattleResultProperties.class)).isEqualTo(BattleResultProperties.defaults());
            assertThat(kafka.created()).singleElement().satisfies(created -> {
                assertThat(created.topic()).isEqualTo(RESULT_TOPIC);
                assertThat(created.partitions()).isEqualTo(3);
                assertThat(created.replicationFactor()).isEqualTo((short) 1);
            });
            MeterRegistry meters = ctx.getBean(MeterRegistry.class);

            infra.roomDeps.results().publish(result(77101));
            // 活动局：Redisson 是 mock，持久副本落不了库 → 活动结果通道只发布一次；走的是同一个生产方，按 activity 计
            infra.roomDeps.activityResults().dispatch(activityResult(77102));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(kafka.sent()).extracting(Sent::key).containsExactlyInAnyOrder("77101", "77102");
                assertThat(results(meters, "plain", "sent")).isEqualTo(1);
                assertThat(results(meters, "activity", "sent")).isEqualTo(1);
                assertThat(resultEvents(meters, "sent")).isEqualTo(2);
            });
            assertThat(kafka.sent()).allSatisfy(message -> assertThat(message.topic()).isEqualTo(RESULT_TOPIC));
            assertThat(kafka.sent().stream().filter(message -> message.key().equals("77102")).findFirst().orElseThrow().event())
                    .isEqualTo(activityResult(77102));
            assertThat(kafka.sendThreads()).containsOnly("battle-result-out");
            assertThat(results(meters, "plain", "logged") + results(meters, "activity", "logged")).as("不再是只记日志").isZero();
            assertThat(kafka.producer(0).closed()).isFalse();
        });
        assertThat(kafka.producer(0).closed()).as("上下文销毁时关生产者").isTrue();
    }

    @Test
    void 对局结果的topic代次与副本数取配置() {
        prod(SECRET).withPropertyValues("xm.battle.result.topic-generation=5", "xm.battle.result.replication-factor=3").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(KafkaBattleResultSink.class).topic()).isEqualTo("xm-battle-result-g5");
            assertThat(kafka.created()).singleElement().satisfies(created -> {
                assertThat(created.topic()).isEqualTo("xm-battle-result-g5");
                assertThat(created.replicationFactor()).isEqualTo((short) 3);
            });
            assertThat(kafka.partitionsOf(RESULT_TOPIC)).as("缺省代次的 topic 没有被碰").isNull();
        });
    }

    @Test
    void 对局结果topic的分区数与契约不符_拒启_报错指向它自己的代次变量_发生在任何端口打开之前_升代次后能启动() {
        kafka.topic(RESULT_TOPIC, 1);

        prod(SECRET).run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("拒绝启动").rootCause()
                    .hasMessageContaining(RESULT_TOPIC).hasMessageContaining("分区数是 1").hasMessageContaining("契约是 3")
                    .hasMessageContaining("XM_BATTLE_RESULT_TOPIC_GENERATION");
        });
        assertThat(infra.events).as("门禁在任何端口打开之前").isEmpty();
        assertThat(kafka.producersMade()).isZero();

        prod(SECRET).withPropertyValues("xm.battle.result.topic-generation=2").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(KafkaBattleResultSink.class).topic()).isEqualTo("xm-battle-result-g2");
        });
        assertThat(kafka.partitionsOf(RESULT_TOPIC)).as("旧代次原样留着，不去改它的分区数").isEqualTo(1);
    }

    @Test
    void Kafka不可达_不拒启_节点照常开闸_结果事件完整写进兜底日志(CapturedOutput output) {
        kafka.unreachable = true;
        try (ResultFallbackCapture fallback = ResultFallbackCapture.start()) {
            prod(SECRET).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                assertThat(ctx.getBean(BattleNode.class).isRunning()).isTrue();
                assertThat(ctx.getBean(AdmissionGate.class).phase()).isEqualTo(AdmissionPhase.OPEN);
                assertThat(ctx.getBean(KafkaBattleResultSink.class).verified()).isFalse();
                MeterRegistry meters = ctx.getBean(MeterRegistry.class);

                infra.roomDeps.results().publish(result(77201));

                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(resultEvents(meters, "not_verified")).isEqualTo(1));
                assertThat(fallback.lines()).singleElement().satisfies(line -> {
                    assertThat(line.reason()).isEqualTo("not_verified");
                    assertThat(line.topic()).isEqualTo(RESULT_TOPIC);
                    assertThat(line.key()).isEqualTo("77201");
                    assertThat(line.event()).isEqualTo(result(77201));
                });
                assertThat(results(meters, "plain", "error")).isEqualTo(1);
                assertThat(kafka.producersMade()).isZero();
            });
        }
        assertThat(output.getOut()).contains("对局结果经 Kafka 发布").contains("已核对=false");
    }

    @Test
    void xm_battle_result配置非法_拒启_消息带键名_还没碰Kafka() {
        prod(SECRET).withPropertyValues("xm.battle.result.topic-generation=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("xm.battle.result.topic-generation");
        });
        prod(SECRET).withPropertyValues("xm.battle.result.bootstrap-servers=  ").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("xm.battle.result.bootstrap-servers");
        });
        prod(SECRET).withPropertyValues("xm.battle.result.replication-factor=0").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("xm.battle.result.replication-factor");
        });
        prod(SECRET).withPropertyValues("xm.battle.result.init-timeout=0s").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("xm.battle.result.init-timeout");
        });
        assertThat(infra.events).as("门禁在任何端口打开之前").isEmpty();
        assertThat(kafka.adminsOpened()).isZero();
    }

    @Test
    void 测试自己提供结果发布端口时_缺省的Kafka实现让位_不碰Kafka() {
        BattleResultSink own = new LoggingBattleResultSink(new com.game.battle.metrics.BattleMetrics(new SimpleMeterRegistry()));

        prod(SECRET).withBean(BattleResultSink.class, () -> own).run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(KafkaBattleResultSink.class);
            assertThat(ctx.getBean(BattleResultSink.class)).isSameAs(own);
            assertThat(infra.roomDeps.results()).isSameAs(own);
        });

        assertThat(kafka.adminsOpened()).isZero();
        assertThat(kafka.producersMade()).isZero();
    }

    @Test
    void 上下文关闭_节点先停_传输再关_对局结果发送最后关_节点停机途中交出的结果仍发得出去(CapturedOutput output) {
        prod(SECRET).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            // 节点停机途中（反导出控制面那一步）房间交出最后一份结果
            infra.onRpcClose = () -> infra.roomDeps.results().publish(result(77301));
        });

        assertThat(infra.events).endsWith("rpc.close", "lease.close");
        assertThat(kafka.sent()).as("节点停机时结果发送还开着；关闭时先把队列发完").extracting(Sent::key).containsExactly("77301");
        assertThat(kafka.producer(0).closed()).isTrue();
        String out = output.getOut();
        assertThat(out).contains("battle → scene 传输已关闭").contains("对局结果发送已关闭 topic=" + RESULT_TOPIC);
        assertThat(out.indexOf("battle → scene 传输已关闭")).as("活动结果通道所在的 battle-outbox 线程先停，之后才关生产者")
                .isLessThan(out.indexOf("对局结果发送已关闭 topic=" + RESULT_TOPIC));
    }

    @Test
    void 不提供替换口时用真Kafka客户端_地址写错按不可达处理_照常启动_客户端id带通告地址与控制面端口(CapturedOutput output) {
        // 没有端口的地址在解析阶段就被真客户端拒掉：不做 DNS、不连网络，也不等 init-timeout
        realKafkaClients.withPropertyValues("xm.battle.result.bootstrap-servers=没有端口的地址").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(BattleNode.class).isRunning()).isTrue();
            KafkaBattleResultSink sink = ctx.getBean(KafkaBattleResultSink.class);
            assertThat(sink.verified()).isFalse();
            assertThat(sink.topic()).isEqualTo(RESULT_TOPIC);
        });

        assertThat(kafka.adminsOpened()).as("这条用例没有走假 Kafka").isZero();
        assertThat(output.getOut()).contains("对局结果经 Kafka 发布 kafka=没有端口的地址 topic=" + RESULT_TOPIC
                + " client_id=xm-battle-result-127.0.0.1-21200 已核对=false");
    }

    /**
     * 生产装配连真 Kafka（{@code -Dxm.it.kafka=127.0.0.1:9092}，缺省跳过）：{@code BattleConfiguration} 自己建的幂等生产者与 topic 管理，
     * 房间交来的结果真的落到 topic 上。用 xm-battle 测试专用的代次（同 {@code KafkaBattleResultSinkIntegrationTest}），唯一的 battle_id 与消费组。
     */
    @Test
    @EnabledIfSystemProperty(named = "xm.it.kafka", matches = ".+")
    void 真Kafka_生产装配核对通过_房间交来的结果落到带代次的topic上_key是battle_id_字节原样() {
        String bootstrap = System.getProperty("xm.it.kafka");
        long battleId = System.currentTimeMillis() * 1000 + 777;
        BattleResultEvent event = result(battleId);

        realKafkaClients.withPropertyValues("xm.battle.result.bootstrap-servers=" + bootstrap, "xm.battle.result.topic-generation=9671",
                "xm.battle.result.init-timeout=20s").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            KafkaBattleResultSink sink = ctx.getBean(KafkaBattleResultSink.class);
            assertThat(sink.topic()).isEqualTo("xm-battle-result-g9671");
            assertThat(sink.verified()).isTrue();
            MeterRegistry meters = ctx.getBean(MeterRegistry.class);

            infra.roomDeps.results().publish(event);

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(resultEvents(meters, "sent")).isEqualTo(1));
            assertThat(results(meters, "plain", "sent")).isEqualTo(1);
        });
        assertThat(kafka.adminsOpened()).as("这条用例没有走假 Kafka").isZero();

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "xm-it-battle-config-" + battleId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, byte[]>> own = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer())) {
            consumer.subscribe(List.of("xm-battle-result-g9671"));
            while (own.isEmpty() && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(500))) {
                    if (Long.toUnsignedString(battleId).equals(record.key())) {
                        own.add(record);
                    }
                }
            }
        }
        assertThat(own).singleElement().satisfies(record -> assertThat(record.value()).isEqualTo(event.toByteArray()));
    }
}
