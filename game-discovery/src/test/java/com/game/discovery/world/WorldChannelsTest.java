package com.game.discovery.world;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.discovery.NodeTypes;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorldChannelsTest {

    @Test
    void 常量_slot_键位宽与发号租约() {
        assertThat(WorldChannels.MAX_SLOT).isEqualTo(998);
        assertThat(WorldChannels.MAX_CHANNELS_PER_MAP).isEqualTo(999);
        assertThat(WorldChannels.MAX_SLOT).isLessThan(WorldChannels.SLOT_STRIDE);
        assertThat(WorldChannels.ID_LEASE_NODE_TYPE).isEqualTo(NodeTypes.SCENE_MANAGER).isEqualTo("scene-manager");
        assertThat(WorldChannels.ID_LEASE_ZONE).isZero();
        assertThat(WorldChannels.ID_LEASE_MIN_ID).isEqualTo(1);
        assertThat(WorldChannels.ID_LEASE_MAX_ID).isEqualTo(1023);
    }

    @Test
    void 预占_TTL_必须覆盖归属夺取等待_0_表示关闭() {
        WorldChannels.requireReservationTtlCovers(Duration.ofSeconds(10), Duration.ofSeconds(3));
        WorldChannels.requireReservationTtlCovers(Duration.ofSeconds(3), Duration.ofSeconds(3));
        WorldChannels.requireReservationTtlCovers(Duration.ZERO, Duration.ofSeconds(3));
        assertThatThrownBy(() -> WorldChannels.requireReservationTtlCovers(Duration.ofSeconds(2), Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservation-ttl").hasMessageContaining("owner-claim-wait");
        assertThatThrownBy(() -> WorldChannels.requireReservationTtlCovers(Duration.ofSeconds(-1), Duration.ofSeconds(3)))
                .isInstanceOf(IllegalStateException.class);
    }
}
