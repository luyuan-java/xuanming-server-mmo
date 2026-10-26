package com.game.scenemanager.world;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ChannelSwitchPropertiesTest {
    @Test
    void 缺省绑定与零值_冷却10_刷新2_繁忙60_人数跟扩容阈值() {
        var props = ChannelSwitchProperties.defaults();
        assertThat(props.effectiveCooldownSeconds()).isEqualTo(10);
        assertThat(props.refreshInterval()).isEqualTo(Duration.ofSeconds(2));
        assertThat(props.effectiveBusyPercent()).isEqualTo(60);
        assertThat(props.effectiveMaxPlayers(WorldChannelProperties.defaults())).isEqualTo(2000);
        assertThat(props.directoryTtl(Duration.ofSeconds(5))).isEqualTo(Duration.ofSeconds(11));
        var source = new MapConfigurationPropertySource(Map.of("game.scene-manager.channel-switch.disabled", "true",
                "game.scene-manager.channel-switch.cooldown-seconds", "-1"));
        var bound = new Binder(source).bind("game.scene-manager.channel-switch", Bindable.of(ChannelSwitchProperties.class)).get();
        assertThat(bound.disabled()).isTrue();
        assertThat(bound.effectiveCooldownSeconds()).isZero();
        assertThat(bound.refreshInterval()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void 政策边界_负数关闭_最大一天_人数uint32_比例钳制() {
        var off = new ChannelSwitchProperties(true, -1, -1, -1, -1);
        assertThat(off.effectiveCooldownSeconds()).isZero();
        assertThat(off.refreshInterval()).isZero();
        assertThat(off.effectiveBusyPercent()).isEqualTo(1);
        assertThat(off.effectiveMaxPlayers(PlanFixture.props(Map.of("autoscale.scale-out-players", "0")))).isEqualTo(2000);
        var high = new ChannelSwitchProperties(false, Long.MAX_VALUE, Long.MAX_VALUE, 101, Long.MAX_VALUE);
        assertThat(high.effectiveCooldownSeconds()).isEqualTo(86400);
        assertThat(high.refreshInterval()).isEqualTo(Duration.ofDays(1));
        assertThat(high.effectiveBusyPercent()).isEqualTo(100);
        assertThat(high.effectiveMaxPlayers(WorldChannelProperties.defaults())).isEqualTo(0xffff_ffffL);
        assertThat(new ChannelSwitchProperties(false, 0, 400, 0, 0).effectiveMaxPlayers(WorldChannelProperties.defaults())).isEqualTo(400);
        assertThat(off.effectiveMaxPlayers(PlanFixture.props(Map.of("autoscale.scale-out-players", "300")))).isEqualTo(300);
    }
}
