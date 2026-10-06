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
import com.game.battle.port.scene.DubboSceneBattleEvents;
import com.game.battle.port.SceneBattleEvents;
import com.game.battle.port.scene.SceneTransport;
import com.game.battle.push.LobbyAnnouncer;
import com.game.battle.push.PresenceLobbyAnnouncer;
import com.game.common.RunMode;
import com.game.common.token.BattleTickets;
import com.game.discovery.RedisProperties;
import com.game.proto.BattleRouting;
import com.game.proto.BattleSettlementData;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

/**
 * 启动门禁与装配（battle-node-spec §6.3、§7.11 第 1–3 步、§13.5；{@code ApplicationContextRunner}，不连 Redis、不开端口：基础设施用
 * {@link FakeBattleInfrastructure}）。秘密都经属性显式给出，盖住开发机上可能已设置的同名环境变量。
 */
@ExtendWith(OutputCaptureExtension.class)
class BattleConfigurationTest {

    static final String SECRET = "battle-ticket-secret-for-context-tests-0123456789";
    static final String GATE_SECRET = "gate-token-secret-for-context-tests-0123456789";

    private final FakeBattleInfrastructure infra = new FakeBattleInfrastructure();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BattleConfiguration.class)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
            .withBean(BattleInfrastructure.class, () -> infra)
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
    void 三个出站端口都接到SceneTransport_结算端口背后是真的发件箱_结果发布端口仍是日志实现() {
        prod(SECRET).run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(SceneTransport.class);
            SceneTransport transport = ctx.getBean(SceneTransport.class);
            assertThat(ctx.getBean(SceneBattleEvents.class)).isSameAs(transport.sceneEvents());
            assertThat(ctx.getBean(ActivityResultSink.class)).isSameAs(transport.activityResults());
            assertThat(ctx.getBean(BattleResultSink.class)).isInstanceOf(LoggingBattleResultSink.class);
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
}
