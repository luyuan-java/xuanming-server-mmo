package com.game.match.gather;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.match.rating.RatingReader;
import com.game.proto.match.MatchMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 分队（match-spec §2.9、§15.1）：5V5 按评分降序稳定排序后蛇形 {@code 0,1,1,0,0,1,1,0,0,1}；1V1 / 切磋按下标；PVE 全员 0 队。
 * 对照基线 {@code TestAssignBalancedTeamsSnake}、{@code TestGather5v5SnakeTeamsReachCreateBattle} 的末尾两条（{@code rating_match_test.go:452}、{@code :487}）。
 */
class TeamAssignmentTest {

    private static final List<Long> TEN = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);

    /** 成员 1 最高（2000.00），依次少 100 分。 */
    private static Map<Long, Long> descending() {
        Map<Long, Long> ratings = new HashMap<>();
        for (int i = 0; i < TEN.size(); i++) {
            ratings.put(TEN.get(i), (2000L - 100L * i) * 100);
        }
        return ratings;
    }

    @Test
    void 五对五_按评分降序蛇形_第1_4_5_8_9名一队_两队总分只差一个步长() {
        Map<Long, Long> ratings = descending();

        int[] teams = TeamAssignment.assign(MatchMode.MATCH_MODE_5V5, TEN, ratings);

        assertThat(teams).containsExactly(0, 1, 1, 0, 0, 1, 1, 0, 0, 1);
        long[] sum = new long[2];
        for (int i = 0; i < TEN.size(); i++) {
            sum[teams[i]] += ratings.get(TEN.get(i));
        }
        assertThat(sum[0]).as("0 队：2000 + 1700 + 1600 + 1300 + 1200").isEqualTo(780_000);
        assertThat(sum[1]).as("1 队：1900 + 1800 + 1500 + 1400 + 1100（前 5 后 5 会是 8500 / 7000）").isEqualTo(770_000);
    }

    @Test
    void 五对五_名单乱序时队号跟评分走_不跟下标走_返回值与名单同下标() {
        List<Long> shuffled = List.of(10L, 1L, 9L, 2L, 8L, 3L, 7L, 4L, 6L, 5L);

        int[] teams = TeamAssignment.assign(MatchMode.MATCH_MODE_5V5, shuffled, descending());

        Map<Long, Integer> byPlayer = new HashMap<>();
        for (int i = 0; i < shuffled.size(); i++) {
            byPlayer.put(shuffled.get(i), teams[i]);
        }
        assertThat(byPlayer).containsExactlyInAnyOrderEntriesOf(
                Map.of(1L, 0, 2L, 1, 3L, 1, 4L, 0, 5L, 0, 6L, 1, 7L, 1, 8L, 0, 9L, 0, 10L, 1));
    }

    @Test
    void 五对五_全员同分_按名单顺序蛇形_第一位在0队() {
        Map<Long, Long> same = new HashMap<>();
        TEN.forEach(pid -> same.put(pid, RatingReader.DEFAULT_CENTI));

        assertThat(TeamAssignment.assign(MatchMode.MATCH_MODE_5V5, TEN, same)).containsExactly(0, 1, 1, 0, 0, 1, 1, 0, 0, 1);
    }

    @Test
    void 五对五_评分表里没有的人按缺省1500参与_同分仍按名单顺序() {
        // 只有 7 号有分且高于缺省：他第 1 名进 0 队；其余 9 人同为 1500，按名单顺序排在第 2..10 名
        int[] teams = TeamAssignment.assign(MatchMode.MATCH_MODE_5V5, TEN, Map.of(7L, 180_000L));

        // 名次：7, 1, 2, 3, 4, 5, 6, 8, 9, 10 → 队号 0, 1, 1, 0, 0, 1, 1, 0, 0, 1
        assertThat(teams).containsExactly(1, 1, 0, 0, 1, 1, 0, 0, 0, 1);
        // 空表与 null 等价于全员缺省分
        assertThat(TeamAssignment.assign(MatchMode.MATCH_MODE_5V5, TEN, Map.of())).containsExactly(0, 1, 1, 0, 0, 1, 1, 0, 0, 1);
        assertThat(TeamAssignment.assign(MatchMode.MATCH_MODE_5V5, TEN, null)).containsExactly(0, 1, 1, 0, 0, 1, 1, 0, 0, 1);
    }

    @Test
    void 五对五_只差一分也按评分排_高分在前() {
        // 名单第二位比第一位高 0.01 分：他是第 1 名（0 队），名单第一位是第 2 名（1 队）
        int[] teams = TeamAssignment.assign(MatchMode.MATCH_MODE_5V5, List.of(1L, 2L), Map.of(1L, 150_000L, 2L, 150_001L));

        assertThat(teams).containsExactly(1, 0);
    }

    @Test
    void 一对一与切磋_名单下标就是队号_锚点在0队_评分不参与() {
        Map<Long, Long> secondIsStronger = Map.of(1L, 100_000L, 2L, 200_000L);

        assertThat(TeamAssignment.assign(MatchMode.MATCH_MODE_1V1, List.of(1L, 2L), secondIsStronger)).containsExactly(0, 1);
        assertThat(TeamAssignment.assign(MatchMode.MATCH_MODE_PVP_CHALLENGE, List.of(9L, 8L), Map.of())).containsExactly(0, 1);
    }

    @Test
    void PVE_单人_组队_全员0队() {
        assertThat(TeamAssignment.assign(MatchMode.MATCH_MODE_PVE_SOLO, List.of(1L), Map.of())).containsExactly(0);
        assertThat(TeamAssignment.assign(MatchMode.MATCH_MODE_PVE_TEAM, List.of(1L, 2L, 3L), descending())).containsExactly(0, 0, 0);
        assertThat(TeamAssignment.assign(MatchMode.MATCH_MODE_PVE_TEAM, List.of(1L, 2L, 3L, 4L, 5L), Map.of())).containsExactly(0, 0, 0, 0, 0);
    }

    @Test
    void 不排队的模式也不会分出1队_3V3全员0队() {
        assertThat(TeamAssignment.assign(MatchMode.MATCH_MODE_3V3, List.of(1L, 2L, 3L, 4L, 5L, 6L), Map.of())).containsExactly(0, 0, 0, 0, 0, 0);
    }
}
