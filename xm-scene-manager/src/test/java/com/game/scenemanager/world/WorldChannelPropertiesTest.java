package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.scenemanager.world.WorldChannelProperties.Coverage;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/** {@code xm.scene-manager.world.*} 的缺省值、绑定与启动校验（scene-channels-spec §5.2、§4.7、Q8）。 */
class WorldChannelPropertiesTest {

    /** 按 Spring Boot 的真实绑定规则（kebab-case、Duration 文本、map 键）绑定；键不带前缀。 */
    static WorldChannelProperties bind(Map<String, Object> props) {
        Map<String, Object> full = new HashMap<>();
        props.forEach((k, v) -> full.put("xm.scene-manager.world." + k, v));
        return new Binder(new MapConfigurationPropertySource(full))
                .bindOrCreate("xm.scene-manager.world", WorldChannelProperties.class);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    @Test
    void 缺省值同基线() {
        WorldChannelProperties p = bind(Map.of());

        assertThat(p.channelCount()).isEqualTo(1);
        assertThat(p.channelCountByConfig()).isEmpty();
        assertThat(p.coverage()).isEqualTo(Coverage.PER_NODE);
        assertThat(p.tick()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.deadNodeGrace()).isEqualTo(Duration.ofSeconds(20));
        assertThat(p.leaderLockTtl()).isEqualTo(Duration.ofSeconds(30));
        assertThat(p.leaderEligible()).isTrue();
        assertThat(p.cleanupOrphans()).isTrue();
        assertThat(p.reservationTtl()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.loginOwnerClaimWait()).isEqualTo(Duration.ofSeconds(3));
        assertThat(p.rebalance().maxMigrationsPerTick()).isEqualTo(10);
        assertThat(p.rebalance().interval()).isEqualTo(Duration.ofSeconds(300));
        WorldChannelProperties.Autoscale a = p.autoscale();
        assertThat(a.enabled()).isFalse();
        assertThat(a.checkInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(a.scaleOutPlayers()).isEqualTo(2000);
        assertThat(a.scaleInPlayers()).isEqualTo(100);
        assertThat(a.minChannels()).isEqualTo(1);
        assertThat(a.maxChannels()).isEqualTo(16);
        assertThat(a.cooldown()).isEqualTo(Duration.ofSeconds(120));
        assertThat(a.drainTimeout()).isEqualTo(Duration.ofSeconds(300));
        assertThat(WorldChannelProperties.defaults()).isEqualTo(p);
    }

    @Test
    void 配置键按kebab_case绑定_覆盖模式与按图覆盖() {
        WorldChannelProperties p = bind(Map.of(
                "channel-count", "2",
                "channel-count-by-config.[1]", "16",
                "channel-count-by-config.[7]", "0",
                "coverage", "hash",
                "tick", "2s",
                "reservation-ttl", "0",
                "rebalance.max-migrations-per-tick", "-3",
                "autoscale.enabled", "true",
                "autoscale.drain-timeout", "0s"));

        assertThat(p.channelCount()).isEqualTo(2);
        assertThat(p.channelCountByConfig()).containsExactly(Map.entry(1, 16)); // 值 ≤0 的覆盖项忽略（config.go:339-341）
        assertThat(p.seedFor(1)).isEqualTo(16);
        assertThat(p.seedFor(7)).isEqualTo(2);
        assertThat(p.coverage()).isEqualTo(Coverage.HASH);
        assertThat(p.tick()).isEqualTo(Duration.ofSeconds(2));
        assertThat(p.reservationTtl()).isZero();
        assertThat(p.rebalance().maxMigrationsPerTick()).isEqualTo(10); // <0 当 10（world_rebalance.go:127-133）
        assertThat(p.autoscale().enabled()).isTrue();
        assertThat(p.autoscale().drainTimeout()).isEqualTo(Duration.ofSeconds(300)); // ≤0 当 300
    }

    @Test
    void 种子小于1当1_超过999拒启() {
        assertThat(bind(Map.of("channel-count", "0")).channelCount()).isEqualTo(1);
        assertThatThrownBy(() -> bind(Map.of("channel-count", "1000")))
                .isInstanceOf(BindException.class)
                .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("channel-count"));
        assertThatThrownBy(() -> bind(Map.of("channel-count-by-config.[3]", "1000")))
                .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("channel-count-by-config.3"));
    }

    @Test
    void 预占TTL短于login的归属夺取等待时拒启_Q8() {
        assertThatThrownBy(() -> bind(Map.of("reservation-ttl", "2s")))
                .isInstanceOf(BindException.class)
                .satisfies(e -> assertThat(rootCause(e))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("reservation-ttl")
                        .hasMessageContaining("owner-claim-wait"));
        assertThatThrownBy(() -> bind(Map.of("reservation-ttl", "5s", "login-owner-claim-wait", "6s")))
                .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("PT6S"));
        // 相等可以；0 = 关闭预占不校验
        assertThat(bind(Map.of("reservation-ttl", "3s")).reservationTtl()).isEqualTo(Duration.ofSeconds(3));
        assertThat(bind(Map.of("reservation-ttl", "0s", "login-owner-claim-wait", "9s")).reservationTtl()).isZero();
    }

    @Test
    void 开启扩缩容时校验宽带与上下限() {
        assertThatThrownBy(() -> bind(Map.of("autoscale.enabled", "true", "autoscale.scale-in-players", "2000")))
                .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("scale-in-players"));
        assertThatThrownBy(() -> bind(Map.of("autoscale.enabled", "true", "autoscale.max-channels", "1000")))
                .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("max-channels"));
        assertThatThrownBy(() -> bind(Map.of("autoscale.enabled", "true", "autoscale.min-channels", "5",
                "autoscale.max-channels", "4")))
                .satisfies(e -> assertThat(rootCause(e)).hasMessageContaining("min-channels"));
        // 关闭时不校验（基线也不校验）
        assertThat(bind(Map.of("autoscale.scale-in-players", "2000")).autoscale().enabled()).isFalse();
    }

    @Test
    void 期望数钳制同基线setDesired() {
        WorldChannelProperties.Autoscale a = bind(Map.of("autoscale.min-channels", "0", "autoscale.max-channels", "4"))
                .autoscale();
        assertThat(a.minChannels()).isEqualTo(1); // 硬下限 1（config.go:316-318）
        assertThat(a.clamp(0)).isEqualTo(1);
        assertThat(a.clamp(3)).isEqualTo(3);
        assertThat(a.clamp(9)).isEqualTo(4);
        WorldChannelProperties.Autoscale unlimited = bind(Map.of("autoscale.max-channels", "0")).autoscale();
        assertThat(unlimited.clamp(5000)).isEqualTo(999); // 0 = 不限，但受 slot 上限约束
        assertThat(unlimited.effectiveMaxChannels()).isEqualTo(999);
    }

    @Test
    void 负的时长拒启() {
        assertThatThrownBy(() -> bind(Map.of("dead-node-grace", "-1s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("tick", "0s"))).isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("leader-lock-ttl", "500ms"))).isInstanceOf(BindException.class);
    }
}
