package com.game.scene.world;

import static com.game.scene.world.MovementRules.TeleportVerdict.*;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 规格 §11.4 S1–S3：距离优先；单调时钟仅比较差值，含 long 回绕。 */
class MovementRulesTest {
    @Test
    void S1_远处且未到期是过期上报() {
        assertThat(MovementRules.judgeAfterTeleport(3.000001, 99, 100)).isEqualTo(STALE);
        assertThat(MovementRules.judgeAfterTeleport(50, -101, -100)).isEqualTo(STALE);
    }

    @Test
    void S2_恰好三米先判落位_到期前后皆如此() {
        for (long now : new long[] {99, 100, 101}) {
            assertThat(MovementRules.judgeAfterTeleport(3.0, now, 100)).isEqualTo(SETTLED);
            assertThat(MovementRules.judgeAfterTeleport(0, now, 100)).isEqualTo(SETTLED);
        }
    }

    @Test
    void S3_远处恰好截止已到期_截止回绕不改变先后() {
        assertThat(MovementRules.judgeAfterTeleport(50, 100, 100)).isEqualTo(EXPIRED);
        long now = Long.MAX_VALUE - 50;
        long deadline = now + 100;
        assertThat(deadline).isNegative();
        assertThat(MovementRules.judgeAfterTeleport(50, now, deadline)).isEqualTo(STALE);
        assertThat(MovementRules.judgeAfterTeleport(50, deadline - 1, deadline)).isEqualTo(STALE);
        assertThat(MovementRules.judgeAfterTeleport(50, deadline, deadline)).isEqualTo(EXPIRED);
        assertThat(MovementRules.judgeAfterTeleport(50, deadline + 1, deadline)).isEqualTo(EXPIRED);
        assertThat(MovementRules.TELEPORT_SETTLE_RADIUS).isEqualTo(3.0);
        assertThat(MovementRules.TELEPORT_SETTLE_TIMEOUT.toSeconds()).isEqualTo(3);
    }
}
