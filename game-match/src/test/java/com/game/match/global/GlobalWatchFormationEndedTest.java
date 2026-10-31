package com.game.match.global;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.match.MatchBudgets;
import com.game.match.global.pb.GlobalFormationRow;
import org.junit.jupiter.api.Test;

/** 观战原件不带证明关闭的成局判据（{@link GlobalWatchAuthority#formationEnded}）：与竞争 ABORT 同一分界。真 SQL 上的关闭见 {@code GlobalMatchStoreIntegrationTest}。 */
class GlobalWatchFormationEndedTest {

    private static final long DEADLINE = 1_800_000_000_000L;
    private static final long GRACE = MatchBudgets.TERMINAL_OUTCOME_GRACE_MS;

    private static GlobalFormationRow formation(int state, long deadline) {
        return GlobalFormationRow.newBuilder().setBattleId(1).setState(state).setDeadlineMs(deadline).build();
    }

    @Test
    void 已确认中止_不看时间() {
        assertThat(GlobalWatchAuthority.formationEnded(formation(5, DEADLINE), DEADLINE - 1)).isTrue();
    }

    @Test
    void 已建房_到期限加宽限才算结束() {
        assertThat(GlobalWatchAuthority.formationEnded(formation(4, DEADLINE), DEADLINE - 1)).isFalse();
        assertThat(GlobalWatchAuthority.formationEnded(formation(4, DEADLINE), DEADLINE)).as("期限刚到：房间可能还在提交终局").isFalse();
        assertThat(GlobalWatchAuthority.formationEnded(formation(4, DEADLINE), DEADLINE + GRACE - 1)).isFalse();
        assertThat(GlobalWatchAuthority.formationEnded(formation(4, DEADLINE), DEADLINE + GRACE)).isTrue();
    }

    @Test
    void 没有期限证明不了什么() {
        assertThat(GlobalWatchAuthority.formationEnded(formation(4, 0), DEADLINE)).isFalse();
    }
}
