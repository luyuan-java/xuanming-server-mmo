package com.game.api.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 匹配开局管线的时限表与跨进程不等式（match-spec §3.4、§10.3、§15.1）。数值与基线逐个相同：对照 {@code go/match/internal/logic/ticket_cas_test.go:77-171}
 * （{@code TestMatchedTicketTTLFormula}、{@code TestMatchedTicketTTLCoversD82RetryPath}、{@code TestMatcher5v5MatchedTTLCoversWorstCaseGather}）、
 * {@code gather_spectate_index_test.go:232}、{@code team_battle_test.go:39}。改任何一跳超时，这里的整张表都会变——那是故意的。
 */
class MatchBudgetsTest {

    /** scene 备战锁在备战期限之外多留的秒数（基线 pb.h:111；Java 在 BattleRedis 里，xm-api 够不到，这里按规格值钉）。 */
    private static final int SCENE_LOCK_MARGIN_SECONDS = 60;
    /** battle 确认补发的停表时刻（battle-node-spec §4.8、§10.4）。 */
    private static final int BATTLE_CONFIRM_WINDOW_SECONDS = 180;

    @Test
    void 各跳超时与基线相同_备战与取消保持3秒() {
        assertThat(MatchBudgets.PREPARE_BATTLE_TIMEOUT_MS).isEqualTo(3_000);
        assertThat(MatchBudgets.CANCEL_PREPARE_TIMEOUT_MS).isEqualTo(3_000);
        assertThat(MatchBudgets.CREATE_BATTLE_TIMEOUT_MS).isEqualTo(5_000);
        assertThat(MatchBudgets.DESTROY_BATTLE_TIMEOUT_MS).isEqualTo(3_000);
        assertThat(MatchBudgets.ISSUE_TICKET_TIMEOUT_MS).isEqualTo(3_000);
        assertThat(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS).isEqualTo(3_000);
        assertThat(MatchBudgets.ADD_OBSERVER_TIMEOUT_MS).isEqualTo(3_000);
        assertThat(MatchBudgets.TICKET_ROLLBACK_BUDGET_MS).isEqualTo(3_000);
        assertThat(MatchBudgets.PLACEMENT_WRITE_WORST_MS).isEqualTo(6_100);
        assertThat(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS).isEqualTo(4_500);
    }

    @Test
    void 建房阶段最坏耗时是两轮落点写入加建房_22点2秒() {
        assertThat(MatchBudgets.GATHER_CREATE_STAGE_WORST_MS).isEqualTo(22_200);
        assertThat(MatchBudgets.GATHER_CREATE_STAGE_WORST_MS)
                .as("首选节点一次 + 换节点重试一次，每次都是先写落点再建房")
                .isEqualTo(2 * (MatchBudgets.PLACEMENT_WRITE_WORST_MS + MatchBudgets.CREATE_BATTLE_TIMEOUT_MS));
    }

    @Test
    void matched票据TTL_1到5人与10人是42_48_54_60_66_96() {
        assertThat(MatchBudgets.matchedTicketTtlSeconds(1)).isEqualTo(42);
        assertThat(MatchBudgets.matchedTicketTtlSeconds(2)).isEqualTo(48);
        assertThat(MatchBudgets.matchedTicketTtlSeconds(3)).isEqualTo(54);
        assertThat(MatchBudgets.matchedTicketTtlSeconds(4)).isEqualTo(60);
        assertThat(MatchBudgets.matchedTicketTtlSeconds(5)).isEqualTo(66);
        assertThat(MatchBudgets.matchedTicketTtlSeconds(10)).isEqualTo(96);
    }

    @Test
    void matched票据TTL每多一人多6秒_下限30永远不起作用() {
        for (int n = 2; n <= MatchBudgets.MAX_GATHER_PLAYERS; n++) {
            assertThat(MatchBudgets.matchedTicketTtlSeconds(n) - MatchBudgets.matchedTicketTtlSeconds(n - 1)).as("n=%d", n).isEqualTo(6);
        }
        assertThat(MatchBudgets.matchedTicketTtlSeconds(1)).isGreaterThan(MatchBudgets.MATCHED_TTL_FLOOR_SECONDS);
    }

    @Test
    void matched票据TTL盖得住串行gather的最坏耗时_含换节点重试() {
        for (int n = 1; n <= MatchBudgets.MAX_GATHER_PLAYERS; n++) {
            long worstMs = n * (MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS + MatchBudgets.PREPARE_BATTLE_TIMEOUT_MS)
                    + 2 * (MatchBudgets.PLACEMENT_WRITE_WORST_MS + MatchBudgets.CREATE_BATTLE_TIMEOUT_MS)
                    + MatchBudgets.DESTROY_BATTLE_TIMEOUT_MS;
            assertThat(MatchBudgets.matchedTicketTtlSeconds(n) * 1000L).as("n=%d：最坏耗时向上取整到秒，再加恰好 10 s 余量", n)
                    .isGreaterThanOrEqualTo(worstMs + 10_000)
                    .isLessThan(worstMs + 11_000);
        }
    }

    @Test
    void 最长matched票据TTL就是10人那一档() {
        assertThat(MatchBudgets.MAX_GATHER_PLAYERS).isEqualTo(10);
        assertThat(MatchBudgets.MAX_MATCHED_TTL_SECONDS).isEqualTo(96).isEqualTo(MatchBudgets.matchedTicketTtlSeconds(MatchBudgets.MAX_GATHER_PLAYERS));
    }

    @Test
    void 补偿续期是要取消的人数乘3加10() {
        assertThat(MatchBudgets.compensationTtlSeconds(0)).isEqualTo(10);
        assertThat(MatchBudgets.compensationTtlSeconds(1)).isEqualTo(13);
        assertThat(MatchBudgets.compensationTtlSeconds(2)).isEqualTo(16);
        assertThat(MatchBudgets.compensationTtlSeconds(3)).isEqualTo(19);
        assertThat(MatchBudgets.compensationTtlSeconds(4)).isEqualTo(22);
        assertThat(MatchBudgets.compensationTtlSeconds(5)).isEqualTo(25);
        assertThat(MatchBudgets.compensationTtlSeconds(10)).isEqualTo(40);
    }

    @Test
    void 开战锁是matched加全员补偿加10_1到5人是65_74_83_92_101() {
        assertThat(MatchBudgets.teamMatchLockSeconds(1)).isEqualTo(65);
        assertThat(MatchBudgets.teamMatchLockSeconds(2)).isEqualTo(74);
        assertThat(MatchBudgets.teamMatchLockSeconds(3)).isEqualTo(83);
        assertThat(MatchBudgets.teamMatchLockSeconds(4)).isEqualTo(92);
        assertThat(MatchBudgets.teamMatchLockSeconds(5)).isEqualTo(101);
    }

    @Test
    void gather加补偿的最坏耗时_5人91秒_开战锁与长挂调用的超时盖得住它() {
        assertThat(MatchBudgets.gatherWorstSeconds(5)).isEqualTo(91);
        for (int n = 1; n <= MatchBudgets.MAX_TEAM_SIZE; n++) {
            assertThat(MatchBudgets.teamMatchLockSeconds(n)).as("n=%d", n)
                    .isEqualTo(MatchBudgets.matchedTicketTtlSeconds(n) + MatchBudgets.compensationTtlSeconds(n) + 10)
                    .isGreaterThanOrEqualTo(MatchBudgets.gatherWorstSeconds(n));
        }
    }

    @Test
    void 跨进程不等式_确认补发窗口_EndMatch截止_落点TTL() {
        assertThat(MatchBudgets.MAX_MATCHED_TTL_SECONDS + SCENE_LOCK_MARGIN_SECONDS)
                .as("battle 确认补发窗口 ≥ 最长 matched TTL + scene 锁余量（180 ≥ 96 + 60）")
                .isLessThanOrEqualTo(BATTLE_CONFIRM_WINDOW_SECONDS);
        assertThat(MatchBudgets.TEAM_END_MATCH_DEADLINE_SECONDS).isEqualTo(110);
        assertThat(MatchBudgets.teamMatchLockSeconds(MatchBudgets.MAX_TEAM_SIZE))
                .as("xm-team EndMatch 单调截止 ≥ 最长开战锁（110 ≥ 101）")
                .isLessThanOrEqualTo(MatchBudgets.TEAM_END_MATCH_DEADLINE_SECONDS);
        assertThat(MatchBudgets.BATTLE_MAX_DURATION_SECONDS).isEqualTo(300);
        assertThat(MatchBudgets.PLACEMENT_TTL_SECONDS).as("落点记录 TTL ≥ 战斗最长时限（360 ≥ 300）").isEqualTo(360)
                .isGreaterThanOrEqualTo(MatchBudgets.BATTLE_MAX_DURATION_SECONDS);
    }

    // ---------------------------------------------------------------- 观战（spectate-spec §5.1 的常量、§5.3 的六条不等式）

    /** gate 调 match 的 Dubbo 超时（match-spec §9.2）：163 必须先于它给出 in-band 应答。 */
    private static final long GATE_TO_MATCH_TIMEOUT_MS = 5_000;
    /** 停机时排空 match-worker 的上限（xm-match 的 {@code MatchDispatchConfiguration.DRAIN_TIMEOUT}；xm-api 够不到，按规格值钉）。 */
    private static final long WORKER_DRAIN_TIMEOUT_MS = 10_000;

    @Test
    void 观战常量_数值与基线相同() {
        assertThat(MatchBudgets.WATCHING_TTL_SECONDS).isEqualTo(360);
        assertThat(MatchBudgets.SPECTATE_STALE_MS).isEqualTo(360_000);
        assertThat(MatchBudgets.WATCHABLE_LIST_DEFAULT).isEqualTo(20);
        assertThat(MatchBudgets.WATCHABLE_LIST_MAX).isEqualTo(50);
        assertThat(MatchBudgets.RANDOM_WATCH_ROUNDS).isEqualTo(2);
        assertThat(MatchBudgets.RANDOM_PICK_TRIES).isEqualTo(3);
        assertThat(MatchBudgets.WATCH_REWATCH_RESERVE_MS).isEqualTo(1_200);
        assertThat(MatchBudgets.WATCH_ADD_RESERVE_MS).isEqualTo(200);
        assertThat(MatchBudgets.WATCH_ADD_MIN_BUDGET_MS).isEqualTo(1_000);
        assertThat(MatchBudgets.SPECTATE_DRAIN_TIMEOUT_MS).isEqualTo(5_000);
    }

    @Test
    void 观战不等式一_标记TTL不短于战斗最长时限_标记到期时那一场必已收尾() {
        assertThat(MatchBudgets.WATCHING_TTL_SECONDS).isEqualTo(MatchBudgets.PLACEMENT_TTL_SECONDS)
                .isGreaterThanOrEqualTo(MatchBudgets.BATTLE_MAX_DURATION_SECONDS);
    }

    @Test
    void 观战不等式二_过期分界就是落点TTL_读路径与清扫同一口径() {
        assertThat(MatchBudgets.SPECTATE_STALE_MS).isEqualTo(MatchBudgets.PLACEMENT_TTL_SECONDS * 1000L);
    }

    @Test
    void 观战不等式三_开局清退每人的上限就是matched票据TTL公式里的那一项_加了登记观众的常量之后时限表不变() {
        // 公式里每人的一项是「清退 3 s + 备战 3 s」：清退每人的最坏耗时不得超过 3 s，所以这个常量不能改大
        assertThat(MatchBudgets.matchedTicketTtlSeconds(2) - MatchBudgets.matchedTicketTtlSeconds(1))
                .isEqualTo((int) ((MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS + MatchBudgets.PREPARE_BATTLE_TIMEOUT_MS) / 1000));
        assertThat(MatchBudgets.ADD_OBSERVER_TIMEOUT_MS).as("Add 与 Remove 同一个上限（基线都是 3 s）").isEqualTo(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS);
        assertThat(MatchBudgets.matchedTicketTtlSeconds(1)).isEqualTo(42);
        assertThat(MatchBudgets.matchedTicketTtlSeconds(2)).isEqualTo(48);
        assertThat(MatchBudgets.matchedTicketTtlSeconds(5)).isEqualTo(66);
        assertThat(MatchBudgets.matchedTicketTtlSeconds(10)).isEqualTo(96);
    }

    @Test
    void 观战不等式四_163的预算小于gate调match的超时_match先回in_band() {
        assertThat(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS).isLessThan(GATE_TO_MATCH_TIMEOUT_MS);
    }

    @Test
    void 观战不等式五_换场预留加登记观众的最低预算不超过整请求预算_各预留之间自洽() {
        assertThat(MatchBudgets.WATCH_REWATCH_RESERVE_MS + MatchBudgets.WATCH_ADD_MIN_BUDGET_MS)
                .as("换场前的门槛（2.2 s）").isEqualTo(2_200).isLessThanOrEqualTo(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS);
        assertThat(MatchBudgets.WATCH_REWATCH_RESERVE_MS)
                .as("换场的 Remove 用到硬截止才返回，剩下的预算仍够发 AddObserver").isGreaterThanOrEqualTo(MatchBudgets.WATCH_ADD_MIN_BUDGET_MS);
        assertThat(MatchBudgets.WATCH_ADD_RESERVE_MS)
                .as("过了 Add 的门槛，Add 自己至少有 0.8 s").isLessThan(MatchBudgets.WATCH_ADD_MIN_BUDGET_MS);
        assertThat(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS - MatchBudgets.WATCH_REWATCH_RESERVE_MS)
                .as("预算充裕时换场的 Remove 能用满它自己的 3 s").isGreaterThanOrEqualTo(MatchBudgets.REMOVE_OBSERVER_TIMEOUT_MS);
    }

    @Test
    void 观战不等式六_建房窗口就是gather从写落点到最后一次建房的最坏耗时() {
        assertThat(MatchBudgets.GATHER_CREATE_STAGE_WORST_MS).isEqualTo(22_200);
    }

    @Test
    void 停机时等在途163的上限_盖得住一次163的预算_又不超过排空工作池的上限_两者并行不加长停机() {
        assertThat(MatchBudgets.SPECTATE_DRAIN_TIMEOUT_MS).isEqualTo(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS + 500)
                .isGreaterThan(MatchBudgets.DEFAULT_REQUEST_BUDGET_MS).isLessThanOrEqualTo(WORKER_DRAIN_TIMEOUT_MS);
    }

    @Test
    void 人数越界一律拒绝_不给出静默的错值() {
        assertThatThrownBy(() -> MatchBudgets.matchedTicketTtlSeconds(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchBudgets.matchedTicketTtlSeconds(11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchBudgets.compensationTtlSeconds(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchBudgets.compensationTtlSeconds(11)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchBudgets.teamMatchLockSeconds(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchBudgets.teamMatchLockSeconds(6)).as("整队至多 5 人").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MatchBudgets.gatherWorstSeconds(0)).isInstanceOf(IllegalArgumentException.class);
    }
}
