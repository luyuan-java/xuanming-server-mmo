package com.game.team;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.match.MatchBudgets;
import com.game.team.match.MatchTeamBattle;
import com.game.team.rules.TeamLimits;
import com.game.team.store.TeamStore;
import org.junit.jupiter.api.Test;

/**
 * xm-team 一侧的跨进程数值不等式（match-spec §10.3；基线 {@code go/match/internal/team/store.go:454-461} 只能把 110 写死在注释里，
 * Java 直接引用 xm-api 的 {@link MatchBudgets}，改任何一跳的超时这里就会红）。
 */
class TeamBudgetConstraintTest {

    @Test
    void EndMatch的单调截止盖得住最长的开战锁_还留得出一轮清锁加最大退避() {
        int longestLock = 0;
        for (int n = 1; n <= TeamLimits.CAPACITY; n++) {
            longestLock = Math.max(longestLock, MatchBudgets.teamMatchLockSeconds(n));
        }
        assertThat(longestLock).as("5 人的开战锁").isEqualTo(101).isEqualTo(MatchBudgets.teamMatchLockSeconds(5));
        assertThat(MatchBudgets.teamMatchLockSeconds(5)).isLessThanOrEqualTo(MatchBudgets.TEAM_END_MATCH_DEADLINE_SECONDS);
        assertThat(TeamStore.END_MATCH_MAX_DURATION_MS).as("截止取自 MatchBudgets，不另写一份数字")
                .isEqualTo(MatchBudgets.TEAM_END_MATCH_DEADLINE_SECONDS * 1000L).isEqualTo(110_000);
        // EndMatch 在锁提交之后才启动：Redis 持续故障时循环必须比锁活得久，否则全队停在 STARTING 直到锁自然过期。
        // 余量至少容得下再跑一轮（2 s）加一次最大退避（1 s + 20%）
        double oneMoreRound = TeamStore.END_MATCH_ROUND_TIMEOUT_MS
                + TeamStore.END_MATCH_BACKOFF_MAX_MS * (1 + TeamStore.END_MATCH_BACKOFF_JITTER);
        assertThat((double) TeamStore.END_MATCH_MAX_DURATION_MS).isGreaterThanOrEqualTo(longestLock * 1000L + oneMoreRound);
    }

    @Test
    void 队伍容量等于一队的人数上限_xm_match对超过它的名单一律拒绝() {
        assertThat(TeamLimits.CAPACITY).isEqualTo(MatchBudgets.MAX_TEAM_SIZE).isEqualTo(5);
    }

    @Test
    void 可接受的开战锁时长上限就是EndMatch的截止_gather的调用级超时盖得住gather加补偿的最坏耗时() {
        assertThat(MatchTeamBattle.MAX_LOCK_TTL_SECONDS).isEqualTo(MatchBudgets.TEAM_END_MATCH_DEADLINE_SECONDS);
        for (int n = 1; n <= TeamLimits.CAPACITY; n++) {
            int lock = MatchBudgets.teamMatchLockSeconds(n);
            assertThat(lock).as("%d 人：xm-match 给的锁时长在可接受范围内", n).isBetween(1, MatchTeamBattle.MAX_LOCK_TTL_SECONDS);
            // runTeamGather 的调用级超时 = 锁时长：必须 ≥ gather + 全员补偿的最坏耗时，否则正常的慢 gather 会被判成「结果不明」
            assertThat(lock).as("%d 人", n).isGreaterThanOrEqualTo(MatchBudgets.gatherWorstSeconds(n));
        }
    }

    @Test
    void 每跳超时上限3秒_整请求预算上限3点5秒_都先于gate调team的5秒() {
        assertThat(MatchTeamBattle.HOP_TIMEOUT_MS).isEqualTo(3_000);
        assertThat(TeamProperties.MAX_REQUEST_BUDGET.toMillis()).isEqualTo(3_500).isLessThan(5_000);
        // xm-team 带给 xm-match 的剩余预算不会超过 xm-match 自己的整请求预算（4500 ms）：提供方按附件收口，不会比调用方等得更久
        assertThat(TeamProperties.MAX_REQUEST_BUDGET.toMillis()).isLessThanOrEqualTo(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS);
    }
}
