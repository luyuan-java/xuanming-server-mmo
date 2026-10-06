package com.game.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.RedisProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** 写操作开关与配置约束（data-ops-spec §7.2）：开着写开关缺 {@code XM_DUBBO_SECRET} 拒启；回档配置越界拒启；缺省值。 */
class OpsWriteGateTest {

    private static DataProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("xm.data", DataProperties.class);
    }

    @Test
    void 写开关打开缺Dubbo密钥拒启_关闭时不要求() {
        DataProperties on = bind(Map.of("xm.data.ops.enabled", "true"));
        assertThatThrownBy(() -> DataConfiguration.checkOpsWriteGate(on, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("XM_DUBBO_SECRET");
        assertThatThrownBy(() -> DataConfiguration.checkOpsWriteGate(on, "  ")).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> DataConfiguration.checkOpsWriteGate(on, "secret")).doesNotThrowAnyException();
        assertThatCode(() -> DataConfiguration.checkOpsWriteGate(bind(Map.of()), null)).doesNotThrowAnyException();
    }

    @Test
    void 缺省值同规格_写开关关_沉降30秒_复查10秒_余量300秒_夺权等35秒() {
        DataProperties p = bind(Map.of());
        assertThat(p.ops().enabled()).isFalse();
        assertThat(p.ops().claimWait().toSeconds()).isEqualTo(35);
        assertThat(p.ops().maxPlayersPerJob()).isEqualTo(10_000);
        assertThat(p.ops().minTargetAge().toMinutes()).isEqualTo(5);
        assertThat(p.rollback().guild().settle().toSeconds()).isEqualTo(30);
        assertThat(p.rollback().guild().recheckDelay().toSeconds()).isEqualTo(10);
        assertThat(p.rollback().guild().clockSkewMargin().toSeconds()).isEqualTo(300);
        assertThat(p.rollback().guild().checkBudget().toSeconds()).isEqualTo(120);
    }

    @Test
    void 战斗锁读取等待_缺省5秒_长于Redis单条命令的最坏阻塞_非正拒启() {
        DataProperties p = bind(Map.of());
        assertThat(p.ops().battleLockWait()).isEqualTo(Duration.ofSeconds(5));
        // 缺省的 xm.redis（超时 2 s、重试 1 次、间隔 200 ms）下单条命令最坏 4.2 s：Redis 故障时先拿到它自己的报错，而不是我们的超时
        long redisWorstMs = new RedisProperties(null, null, null, null, null, null, null).worstCaseCommandMillis();
        assertThat(redisWorstMs).isEqualTo(4200);
        assertThat(p.ops().battleLockWait().toMillis()).isGreaterThan(redisWorstMs);

        assertThat(bind(Map.of("xm.data.ops.battle-lock-wait", "800ms")).ops().battleLockWait()).isEqualTo(Duration.ofMillis(800));
        assertThatThrownBy(() -> bind(Map.of("xm.data.ops.battle-lock-wait", "0s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.data.ops.battle-lock-wait", "-1s"))).isInstanceOf(BindException.class);
    }

    @Test
    void 余量与预算越界拒启() {
        assertThatThrownBy(() -> bind(Map.of("xm.data.rollback.guild.clock-skew-margin", "4s")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.data.rollback.guild.clock-skew-margin", "61m")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.data.rollback.guild.check-budget", "2h")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("xm.data.ops.heartbeat", "5s", "xm.data.ops.stale-after", "10s")))
                .isInstanceOf(BindException.class);
    }
}
