package com.game.guild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.common.token.DubboCallAuth;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@code xm.guild.*} 的缺省值与启动校验（guild-spec §7.12；基线 config.go:16-19）。 */
class GuildPropertiesTest {

    private static GuildProperties budget(Duration requestBudget) {
        return new GuildProperties(requestBudget, null, null, null, null, null, null, null);
    }

    @Test
    void 缺省值同基线() {
        GuildProperties p = budget(null);
        assertThat(p.requestBudget()).isEqualTo(Duration.ofMillis(3500));
        assertThat(p.cacheTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(p.pushTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.onlineLookupTimeout()).isEqualTo(Duration.ofMillis(800));
        assertThat(p.queryTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.queryTimeoutCapSeconds()).isEqualTo(3);
        assertThat(p.workerThreads()).isEqualTo(16);
        assertThat(p.workerQueueCapacity()).isEqualTo(1024);
    }

    @Test
    void 请求预算必须在500到3500毫秒之间_边界可取() {
        GuildProperties smallest = budget(Duration.ofMillis(500));
        assertThat(smallest.requestBudget()).isEqualTo(Duration.ofMillis(500));
        // 没写的两个上限跟着预算收紧，不必手工改
        assertThat(smallest.onlineLookupTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(smallest.queryTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(smallest.queryTimeoutCapSeconds()).isEqualTo(1);
        assertThat(budget(Duration.ofMillis(3500)).requestBudget()).isEqualTo(Duration.ofMillis(3500));
        assertThatThrownBy(() -> budget(Duration.ofMillis(499))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> budget(Duration.ofMillis(3501))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> budget(Duration.ofSeconds(5))).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> budget(Duration.ZERO)).hasMessageContaining("request-budget");
        assertThatThrownBy(() -> budget(Duration.ofMillis(-1))).hasMessageContaining("request-budget");
    }

    @Test
    void 语句超时与在线读上限不能超过请求预算() {
        // 预算 500 ms 向上取整是 1 s：query-timeout 1 s 可以、2 s 不行（缺省 3 s 也不行，要显式调小）
        assertThat(new GuildProperties(Duration.ofMillis(500), null, null, Duration.ofMillis(500), Duration.ofSeconds(1), null,
                null, null).queryTimeoutCapSeconds()).isEqualTo(1);
        assertThatThrownBy(() -> new GuildProperties(Duration.ofMillis(500), null, null, Duration.ofMillis(500),
                Duration.ofSeconds(2), null, null, null)).hasMessageContaining("query-timeout");
        assertThatThrownBy(() -> new GuildProperties(Duration.ofMillis(1000), null, null, Duration.ofMillis(1001), null,
                null, null, null)).hasMessageContaining("online-lookup-timeout");
        // 亚秒的语句超时取整到 1 s
        assertThat(new GuildProperties(null, null, null, null, Duration.ofMillis(300), null, null, null).queryTimeoutCapSeconds())
                .isEqualTo(1);
    }

    @Test
    void 其余项必须为正() {
        assertThatThrownBy(() -> new GuildProperties(null, Duration.ZERO, null, null, null, null, null, null))
                .hasMessageContaining("cache-ttl");
        assertThatThrownBy(() -> new GuildProperties(null, null, Duration.ofMillis(-1), null, null, null, null, null))
                .hasMessageContaining("push-timeout");
        assertThatThrownBy(() -> new GuildProperties(null, null, null, Duration.ZERO, null, null, null, null))
                .hasMessageContaining("online-lookup-timeout");
        assertThatThrownBy(() -> new GuildProperties(null, null, null, null, Duration.ZERO, null, null, null))
                .hasMessageContaining("query-timeout");
        assertThatThrownBy(() -> new GuildProperties(null, null, null, null, null, 0, null, null))
                .hasMessageContaining("worker-threads");
        assertThatThrownBy(() -> new GuildProperties(null, null, null, null, null, null, -1, null))
                .hasMessageContaining("worker-queue-capacity");
    }

    // ================================================================ xm.guild.asset-op.*（guild-economy-spec §0.6、§7.6）

    private static GuildProperties.AssetOp assetOp(Boolean enabled, Duration interval, Integer batch, Integer workers,
                                                   Duration lease, Duration opBudget, Duration maxBackoff, Duration poison,
                                                   Integer minAttempts, Boolean cleanup, Duration cleanupInterval,
                                                   Duration terminal, Duration counter) {
        return new GuildProperties.AssetOp(enabled, interval, batch, workers, lease, opBudget, maxBackoff, poison, minAttempts,
                cleanup, cleanupInterval, terminal, counter);
    }

    private static GuildProperties.AssetOp enabledWith(Duration interval, Integer batch, Integer workers, Duration lease,
                                                       Duration opBudget, Duration maxBackoff, Duration poison) {
        return assetOp(true, interval, batch, workers, lease, opBudget, maxBackoff, poison, null, null, null, null, null);
    }

    @Test
    void 资产通道缺省关闭_缺省值同基线() {
        GuildProperties.AssetOp a = budget(null).assetOp();
        assertThat(a.enabled()).as("整段缺失 = 关闭（fail-closed）").isFalse();
        assertThat(a.reconcileInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(a.reconcileBatch()).isEqualTo(100);
        assertThat(a.workers()).isEqualTo(8);
        assertThat(a.lease()).isEqualTo(Duration.ofSeconds(10));
        assertThat(a.opBudget()).isEqualTo(Duration.ofMillis(2500));
        assertThat(a.maxBackoff()).isEqualTo(Duration.ofSeconds(60));
        assertThat(a.poisonDelay()).isEqualTo(Duration.ofHours(1));
        assertThat(a.ledgerReadMinAttempts()).isEqualTo(3);
        assertThat(a.cleanupEnabled()).isFalse();
        assertThat(a.cleanupInterval()).isEqualTo(Duration.ofMinutes(10));
        assertThat(a.terminalRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(a.counterRetention()).isEqualTo(Duration.ofDays(30));
        assertThat(enabledWith(null, null, null, null, null, null, null).enabled()).isTrue();
    }

    @Test
    void 资产通道开启时校验循环参数区间_边界可取() {
        enabledWith(Duration.ofMillis(200), 1, 1, Duration.ofSeconds(3), Duration.ofSeconds(1), Duration.ofSeconds(1),
                Duration.ofSeconds(60));
        enabledWith(Duration.ofSeconds(60), 1000, 64, Duration.ofSeconds(600), Duration.ofSeconds(10), Duration.ofSeconds(600),
                Duration.ofHours(24));
        assertThatThrownBy(() -> enabledWith(Duration.ofMillis(199), null, null, null, null, null, null))
                .hasMessageContaining("reconcile-interval");
        assertThatThrownBy(() -> enabledWith(null, null, 65, null, null, null, null)).hasMessageContaining("workers");
        assertThatThrownBy(() -> enabledWith(null, 7, 8, null, null, null, null)).hasMessageContaining("reconcile-batch");
        assertThatThrownBy(() -> enabledWith(null, 1001, null, null, null, null, null)).hasMessageContaining("reconcile-batch");
        assertThatThrownBy(() -> enabledWith(null, null, null, null, Duration.ofMillis(999), null, null))
                .hasMessageContaining("op-budget");
        assertThatThrownBy(() -> enabledWith(null, null, null, Duration.ofMillis(4499), null, null, null))
                .hasMessageContaining("lease");
        assertThatThrownBy(() -> enabledWith(null, null, null, null, null, Duration.ofMillis(999), null))
                .hasMessageContaining("max-backoff");
        assertThatThrownBy(() -> enabledWith(null, null, null, null, null, null, Duration.ofSeconds(59)))
                .hasMessageContaining("poison-delay");
        assertThatThrownBy(() -> assetOp(true, null, null, null, null, null, null, null, 0, null, null, null, null))
                .hasMessageContaining("ledger-read-min-attempts");
    }

    @Test
    void 资产通道关闭时不校验循环参数_清理参数只在清理开启时校验_保留期总是校验() {
        // 关闭：越界的循环参数照样能起（与基线「只在对应开关打开时校验」一致）
        assertThat(assetOp(false, Duration.ofMillis(10), 1, 99, Duration.ofSeconds(1), null, null, null, null, null, null, null,
                null).enabled()).isFalse();
        assetOp(false, null, null, null, null, null, null, null, null, false, Duration.ofDays(3), null, null);
        assertThatThrownBy(() -> assetOp(false, null, null, null, null, null, null, null, null, true, Duration.ofDays(3), null,
                null)).hasMessageContaining("cleanup-interval");
        assertThatThrownBy(() -> assetOp(false, null, null, null, null, null, null, null, null, null, null, Duration.ofDays(6),
                null)).hasMessageContaining("terminal-retention");
        assertThatThrownBy(() -> assetOp(false, null, null, null, null, null, null, null, null, null, null, null,
                Duration.ofDays(366))).hasMessageContaining("counter-retention");
        assertThatThrownBy(() -> assetOp(false, Duration.ZERO, null, null, null, null, null, null, null, null, null, null, null))
                .hasMessageContaining("必须为正");
    }

    @Test
    void 缺Dubbo调用鉴权密钥拒绝启动() {
        // GuildConfiguration.dubboCallAuth() 用环境变量 XM_DUBBO_SECRET 的值调用它（guild-spec §7.1）
        assertThatThrownBy(() -> DubboCallAuth.requireFromEnvValue(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> DubboCallAuth.requireFromEnvValue("")).isInstanceOf(IllegalStateException.class);
    }
}
