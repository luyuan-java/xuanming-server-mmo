package com.game.scene.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.scene.world.PlayerSnapshots;
import org.junit.jupiter.api.Test;

/** 覆盖 L966：进退场审计快照独立开关与按 player_id 稳定抽样（缺省全开全量）。 */
class AuditSnapshotPolicyTest {

    @Test
    void 缺省全开全量_进退场各自开关生效_非法采样率拒绝() {
        assertThat(AuditSnapshotPolicy.DEFAULT.shouldCapture(PlayerSnapshots.Cause.LOGIN, 1L)).isTrue();
        assertThat(AuditSnapshotPolicy.DEFAULT.shouldCapture(PlayerSnapshots.Cause.LOGOUT, 1L)).isTrue();

        AuditSnapshotPolicy logoutOnly = new AuditSnapshotPolicy(false, true, 1000);
        assertThat(logoutOnly.shouldCapture(PlayerSnapshots.Cause.LOGIN, 42L)).isFalse();
        assertThat(logoutOnly.shouldCapture(PlayerSnapshots.Cause.LOGOUT, 42L)).isTrue();

        AuditSnapshotPolicy loginOnly = new AuditSnapshotPolicy(true, false, 1000);
        assertThat(loginOnly.shouldCapture(PlayerSnapshots.Cause.LOGIN, 42L)).isTrue();
        assertThat(loginOnly.shouldCapture(PlayerSnapshots.Cause.LOGOUT, 42L)).isFalse();

        AuditSnapshotPolicy zeroSample = new AuditSnapshotPolicy(true, true, 0);
        assertThat(zeroSample.shouldCapture(PlayerSnapshots.Cause.LOGIN, 42L)).isFalse();

        assertThatThrownBy(() -> new AuditSnapshotPolicy(true, true, -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditSnapshotPolicy(true, true, 1001))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 按playerId稳定抽样且比例符合预期() {
        AuditSnapshotPolicy half = new AuditSnapshotPolicy(true, true, 500);
        int hits = 0;
        for (long pid = 1; pid <= 2_000; pid++) {
            boolean first = half.shouldCapture(PlayerSnapshots.Cause.LOGIN, pid);
            boolean repeat = half.shouldCapture(PlayerSnapshots.Cause.LOGIN, pid);
            boolean logout = half.shouldCapture(PlayerSnapshots.Cause.LOGOUT, pid);
            assertThat(repeat).as("同一 playerId 重复判定必须稳定").isEqualTo(first);
            assertThat(logout).as("同一 playerId 进退场抽样桶一致").isEqualTo(first);
            if (first) {
                hits++;
            }
        }
        assertThat(hits).isBetween(900, 1100);
    }
}
