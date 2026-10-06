package com.game.match.gather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.match.ticket.QueueRef;
import com.game.match.ticket.TicketRef;
import com.game.proto.BattleActivityContext;
import com.game.proto.eBattleActivityKind;
import com.game.proto.match.MatchMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 交给开局管线的 plan（match-spec §3.1 五个入口的表）：哪种入口配哪种失败策略、带不带票据、battle_id 谁发，由工厂钉住；名单顺序（= 站位顺序）原样保留；
 * 结局标签与规格 §11 的 outcome 逐个同名。
 */
class GatherPlanTest {

    private static final BattleActivityContext CONTEXT = BattleActivityContext.newBuilder()
            .setKind(eBattleActivityKind.BATTLE_ACTIVITY_KIND_GUILD_TRIAL).setGuildId(77).setActivityId(3).setPeriodKey(20261006)
            .setInitiatorPlayerId(11).setGuildPeriodKey(20261006).build();

    private static Map<Long, String> tickets(long... players) {
        Map<Long, String> out = new HashMap<>();
        for (long player : players) {
            out.put(player, "t-" + player);
        }
        return out;
    }

    @Test
    void 凑单弹组_模式与副本取自队列_失败回队首_名单顺序原样保留() {
        GatherPlan plan = GatherPlan.popped(new QueueRef(3, 9), List.of(30L, 10L), tickets(10, 30));

        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_1V1);
        assertThat(plan.battleConfigId()).isEqualTo(9);
        assertThat(plan.members()).as("锚点在前：不得被排序").containsExactly(30L, 10L);
        assertThat(plan.onFail()).isEqualTo(FailPolicy.REQUEUE_SURVIVORS);
        assertThat(plan.presetBattleId()).isZero();
        assertThat(plan.activityContext()).isNull();
        assertThat(plan.queue()).isEqualTo(new QueueRef(3, 9));
        assertThat(plan.ticketRefs()).as("票据引用按名单顺序").containsExactly(new TicketRef(30, "t-30"), new TicketRef(10, "t-10"));
        assertThat(new ArrayList<>(plan.tickets().keySet())).containsExactly(30L, 10L);
    }

    @Test
    void 凑单弹组_5V5十人与组队都可以_契约外或不走队列的模式不行() {
        long[] ten = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        List<Long> members = Arrays.stream(ten).boxed().toList();

        assertThat(GatherPlan.popped(new QueueRef(1, 0), members, tickets(ten)).mode()).isEqualTo(MatchMode.MATCH_MODE_5V5);
        assertThat(GatherPlan.popped(new QueueRef(5, 1), List.of(1L, 2L), tickets(1, 2)).mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM);
        assertThatThrownBy(() -> GatherPlan.popped(new QueueRef(99, 0), List.of(1L), tickets(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatherPlan.popped(new QueueRef(4, 1), List.of(1L), tickets(1)))
                .as("PVE_SOLO 不入队，没有队列可回").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatherPlan.popped(new QueueRef(6, 0), List.of(1L, 2L), tickets(1, 2))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 单人副本_一个人一张票_失败只删票() {
        GatherPlan plan = GatherPlan.soloPve(1, 1001, "t-solo");

        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_SOLO);
        assertThat(plan.members()).containsExactly(1001L);
        assertThat(plan.tickets()).containsExactly(Map.entry(1001L, "t-solo"));
        assertThat(plan.onFail()).isEqualTo(FailPolicy.DELETE_ALL);
    }

    @Test
    void 切磋_发起者在前_不带票据() {
        GatherPlan plan = GatherPlan.challenge(0, 2002, 1001);

        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVP_CHALLENGE);
        assertThat(plan.members()).as("[发起者, 应战者]：发起者在 0 队").containsExactly(2002L, 1001L);
        assertThat(plan.tickets()).isEmpty();
        assertThat(plan.ticketRefs()).isEmpty();
        assertThat(plan.onFail()).isEqualTo(FailPolicy.NO_TICKETS);
        assertThatThrownBy(() -> GatherPlan.challenge(0, 7, 7)).as("不能自己打自己").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 整队_名单原序_失败全删票_不预发号() {
        GatherPlan plan = GatherPlan.team(1, List.of(5L, 3L, 4L), tickets(3, 4, 5));

        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM);
        assertThat(plan.members()).as("队长在前，其余按入队序").containsExactly(5L, 3L, 4L);
        assertThat(plan.onFail()).isEqualTo(FailPolicy.DELETE_ALL);
        assertThat(plan.presetBattleId()).isZero();
        assertThat(plan.activityContext()).isNull();
    }

    @Test
    void 活动_带预发的号与上下文_原样透传() {
        GatherPlan plan = GatherPlan.activity(2, List.of(11L, 12L), tickets(11, 12), 9_000_000_001L, CONTEXT);

        assertThat(plan.mode()).isEqualTo(MatchMode.MATCH_MODE_PVE_TEAM);
        assertThat(plan.presetBattleId()).isEqualTo(9_000_000_001L);
        assertThat(plan.activityContext()).isSameAs(CONTEXT);
        assertThat(plan.onFail()).isEqualTo(FailPolicy.DELETE_ALL);
        assertThatThrownBy(() -> GatherPlan.activity(2, List.of(11L), tickets(11), 0, CONTEXT)).as("号必须预发").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatherPlan.activity(2, List.of(11L), tickets(11), 5, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void 名单校验_非空_至多十人_不含0_不重复() {
        assertThatThrownBy(() -> GatherPlan.team(1, List.of(), Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatherPlan.team(1, List.of(1L, 0L), tickets(1, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatherPlan.team(1, List.of(1L, 1L), tickets(1))).isInstanceOf(IllegalArgumentException.class);
        List<Long> eleven = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L);
        assertThatThrownBy(() -> GatherPlan.popped(new QueueRef(1, 0), eleven, tickets(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 票号必须恰好覆盖全部成员() {
        assertThatThrownBy(() -> GatherPlan.team(1, List.of(1L, 2L), tickets(1))).as("少一张").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatherPlan.team(1, List.of(1L), tickets(1, 2))).as("多一张").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatherPlan.team(1, List.of(1L, 2L), tickets(1, 3))).as("张冠李戴").isInstanceOf(IllegalArgumentException.class);
        Map<Long, String> blank = new HashMap<>(tickets(1, 2));
        blank.put(2L, "");
        assertThatThrownBy(() -> GatherPlan.team(1, List.of(1L, 2L), blank)).as("空票号").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatherPlan(MatchMode.MATCH_MODE_PVP_CHALLENGE, 0, List.of(1L, 2L), tickets(1), FailPolicy.NO_TICKETS, 0, null))
                .as("不带票据的入口不得给票号").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 模式必须是已知的具体模式() {
        assertThatThrownBy(() -> new GatherPlan(MatchMode.MATCH_MODE_UNSPECIFIED, 0, List.of(1L), tickets(1), FailPolicy.DELETE_ALL, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatherPlan(MatchMode.UNRECOGNIZED, 0, List.of(1L), tickets(1), FailPolicy.DELETE_ALL, 0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void plan不可变_外面改名单与票号不影响它() {
        List<Long> members = new ArrayList<>(List.of(1L, 2L));
        Map<Long, String> tickets = new LinkedHashMap<>(tickets(1, 2));
        GatherPlan plan = GatherPlan.team(1, members, tickets);
        members.add(3L);
        tickets.put(3L, "t-3");

        assertThat(plan.members()).containsExactly(1L, 2L);
        assertThat(plan.tickets()).hasSize(2);
        assertThatThrownBy(() -> plan.members().add(9L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.tickets().put(9L, "x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void 结局标签与规格的outcome逐个同名() {
        assertThat(Arrays.stream(GatherOutcome.values()).map(GatherOutcome::label)).containsExactly(
                "success", "internal", "overloaded", "no_battle_node", "no_location", "prepare_failed", "fingerprint_mismatch",
                "index_failed", "not_allocatable", "create_rejected", "create_failed", "create_failed_room_alive");
    }

    @Test
    void 只有三种结局归咎于某名成员() {
        assertThat(Arrays.stream(GatherOutcome.values()).filter(GatherOutcome::hasOffender)).containsExactly(
                GatherOutcome.NO_LOCATION, GatherOutcome.PREPARE_FAILED, GatherOutcome.FINGERPRINT_MISMATCH);
    }

    @Test
    void 结果的ok与outcome必须一致_成功必须带号() {
        assertThat(GatherResult.success(77)).isEqualTo(new GatherResult(true, GatherOutcome.SUCCESS, 77));
        assertThat(GatherResult.failed(GatherOutcome.OVERLOADED, 0).ok()).isFalse();
        assertThat(GatherResult.failed(GatherOutcome.CREATE_FAILED, 77).battleId()).as("失败时带上已发出的号，只进日志").isEqualTo(77);
        assertThatThrownBy(() -> new GatherResult(true, GatherOutcome.INTERNAL, 77)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GatherResult(false, GatherOutcome.SUCCESS, 77)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatherResult.success(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GatherResult.failed(GatherOutcome.SUCCESS, 77)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 指纹模式的标签() {
        assertThat(Arrays.stream(FingerprintMode.values()).map(FingerprintMode::label)).containsExactly("off", "warn", "enforce");
    }
}
