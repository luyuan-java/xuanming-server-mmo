package com.game.battle.room;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.match.MatchBudgets;
import com.game.discovery.battle.BattleRedis;
import org.junit.jupiter.api.Test;

/**
 * 跨批次的数值依赖（基线 {@code room.cpp:257-268}；battle-node-spec §4.8、§10.4；scene-battle-spec §7.2 常量表；match-spec §10.3）：确认补发窗口必须
 * ≥ match 最长 matched TTL + scene 备战期锁余量。两边都直接引用对方的常量（改常量时这条不等式跟着失败）：锁余量是 6.3 的
 * {@link BattleRedis#LOCK_EXTRA_TTL_SEC}，matched TTL 的上限是 6.4 的 {@link MatchBudgets#MAX_MATCHED_TTL_SECONDS}（match 下发给 scene 的
 * {@code prepare_deadline_ms} 用的就是同一组公式）。另钉一条比规格的标称不等式（180 ≥ 156）更严的：最后一次<b>实际</b>补发的时刻
 * （计次实现下是 170 s）同样不早于锁最晚过期的时刻。
 */
class ConfirmWindowConstraintTest {

    /**
     * match 的 matched TTL（{@code queue.go:367-380}）的最大值：10 人组的 96 s（1 / 2 / 5 / 10 人组分别 42 / 48 / 66 / 96）。
     * 取 match 自己的常量，不在这里抄数。
     */
    private static final long MATCH_MAX_MATCHED_TTL_MS = MatchBudgets.MAX_MATCHED_TTL_SECONDS * 1000L;
    /** scene 备战期锁的额外 TTL（{@code kLockExtraTtlSec}，{@code pb.h:111}）：scene 写锁时用的就是这个常量。 */
    private static final long SCENE_LOCK_EXTRA_TTL_MS = BattleRedis.LOCK_EXTRA_TTL_SEC * 1000;

    @Test
    void match的matchedTTL上限就是公式对最大组的取值_基线96秒() {
        assertThat(MatchBudgets.MAX_MATCHED_TTL_SECONDS).as("基线 10 人组").isEqualTo(96);
        assertThat(MatchBudgets.matchedTicketTtlSeconds(MatchBudgets.MAX_GATHER_PLAYERS))
                .as("常量与公式不能各走各的：改了任何一跳的超时，上限常量必须跟着改，下面两条不等式才是在核对真实的最大值")
                .isEqualTo(MatchBudgets.MAX_MATCHED_TTL_SECONDS);
        for (int players = 1; players <= MatchBudgets.MAX_GATHER_PLAYERS; players++) {
            assertThat(MatchBudgets.matchedTicketTtlSeconds(players)).as("%d 人组", players)
                    .isLessThanOrEqualTo(MatchBudgets.MAX_MATCHED_TTL_SECONDS);
        }
    }

    @Test
    void 补发窗口覆盖最长matchedTTL加锁余量() {
        assertThat(BattleRedis.LOCK_EXTRA_TTL_SEC).as("基线 kLockExtraTtlSec").isEqualTo(60);
        assertThat(RoomConstants.CONFIRM_RESEND_WINDOW_MS).isEqualTo(180_000);
        assertThat(RoomConstants.CONFIRM_RESEND_WINDOW_MS).isGreaterThanOrEqualTo(MATCH_MAX_MATCHED_TTL_MS + SCENE_LOCK_EXTRA_TTL_MS);
    }

    @Test
    void 最后一次实际补发不早于锁最晚过期的时刻() {
        // 计次实现（N19）：首发在 t = 0，补发在 +10 … +170 s，+180 s 那次只停表、不发——标称窗口 180 s 比最后一条真正发出去的确认晚一个周期。
        // 锁最晚在 gather 起点 + 96 + 60 = 156 s 过期（建房晚于 gather 起点）；要盖住它的是最后一次实际补发的时刻，不是标称窗口：
        // 只比标称窗口时，锁余量调到 75–84 s 上面那条不等式照过，而最后一次补发已早于锁过期（锁还在、确认却不再来）
        long lastResendAtMs = RoomConstants.CONFIRM_RESENDS * RoomConstants.CONFIRM_RESEND_INTERVAL_MS;
        assertThat(lastResendAtMs).as("同 ConfirmResendTest「10 到 170 秒补 17 次」").isEqualTo(170_000);
        assertThat(lastResendAtMs).isGreaterThanOrEqualTo(MATCH_MAX_MATCHED_TTL_MS + SCENE_LOCK_EXTRA_TTL_MS);
    }

    @Test
    void 计次与基线窗口等价_首发加17次补发() {
        assertThat(RoomConstants.CONFIRM_RESENDS).isEqualTo(17);
        assertThat(RoomConstants.CONFIRM_RESEND_INTERVAL_MS).isEqualTo(10_000);
        assertThat(RoomConstants.DEFAULT_DEADLINE_MS).as("(30 + 2) × 6000").isEqualTo(192_000);
        assertThat(RoomConstants.MAX_OBSERVERS_PER_ROOM).isEqualTo(20);
    }
}
