package com.game.battle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.game.battle.admission.AdmissionGate;
import com.game.battle.admission.AdmissionPhase;
import com.game.battle.admin.BattleAdminAuthFilter;
import com.game.battle.admin.DevBattleBackend;
import com.game.battle.port.scene.DubboSceneBattleEvents;
import com.game.battle.port.SceneBattleEvents;
import com.game.battle.push.LobbyAnnouncer;
import com.game.battle.push.PresenceLobbyAnnouncer;
import com.game.common.RunMode;
import com.game.common.token.BattleTickets;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
