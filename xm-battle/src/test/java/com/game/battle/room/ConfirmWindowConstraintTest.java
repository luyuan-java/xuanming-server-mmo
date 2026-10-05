package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 跨批次的数值依赖（基线 {@code room.cpp:257-268}；battle-node-spec §4.8、§10.4）：确认补发窗口必须 ≥ match 最长 matched TTL + scene 备战期锁余量。
 * 6.3 / 6.4 落地后改成直接引用对方的常量。
 */
class ConfirmWindowConstraintTest {

    /** match 的 matched TTL（{@code queue.go:367-380}）对 10 人组的值：96 s（1 / 2 / 5 / 10 人组分别 42 / 48 / 66 / 96）。 */
    private static final long MATCH_MAX_MATCHED_TTL_MS = 96_000;
    /** scene 备战期锁的额外 TTL（{@code kLockExtraTtlSec}，{@code pb.h:111}）。 */
    private static final long SCENE_LOCK_EXTRA_TTL_MS = 60_000;

    @Test
    void 补发窗口覆盖最长matchedTTL加锁余量() {
        assertThat(RoomConstants.CONFIRM_RESEND_WINDOW_MS).isEqualTo(180_000);
        assertThat(RoomConstants.CONFIRM_RESEND_WINDOW_MS).isGreaterThanOrEqualTo(MATCH_MAX_MATCHED_TTL_MS + SCENE_LOCK_EXTRA_TTL_MS);
    }

    @Test
    void 计次与基线窗口等价_首发加17次补发() {
        assertThat(RoomConstants.CONFIRM_RESENDS).isEqualTo(17);
        assertThat(RoomConstants.CONFIRM_RESEND_INTERVAL_MS).isEqualTo(10_000);
        assertThat(RoomConstants.DEFAULT_DEADLINE_MS).as("(30 + 2) × 6000").isEqualTo(192_000);
        assertThat(RoomConstants.MAX_OBSERVERS_PER_ROOM).isEqualTo(20);
    }
}
