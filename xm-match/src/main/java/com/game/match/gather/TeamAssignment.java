package com.game.match.gather;

import com.game.match.rating.RatingReader;
import com.game.proto.match.MatchMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 分队（match-spec §2.9；基线 {@code gather.go:627-661} 的 {@code teamAssignment} / {@code teamIndexFor}、{@code rating.go:236-263} 的
 * {@code assignBalancedTeams}）。纯函数：给一份参战名单，回与名单<b>同下标</b>的队号（0 = A 方，1 = B 方），管线把它写进各成员快照的
 * {@code team_index}。
 *
 * <ul>
 *   <li><b>5V5</b>：按评分降序<b>稳定</b>排序（同分按名单顺序），按名次蛇形分 {@code 0,1,1,0,0,1,1,0,0,1}——第 1、4、5、8、9 名一队，
 *       其余一队，两队总分尽量接近。评分是 gather 时重读的（不用票里存的分），读不到的人按缺省 1500 参与（{@link RatingReader} 的约定）。
 *       契约注释里的「前 5 后 5」已经过时。</li>
 *   <li><b>1V1 / 切磋</b>：名单下标就是队号——锚点（发起者）在 0 队。</li>
 *   <li><b>PVE</b>（单人、组队、活动）与其余模式：全员 0 队（怪物侧由 battle 按副本表生成）。</li>
 * </ul>
 * 名单顺序本身不动：它是备战与快照的顺序，同队站位 = 同队内的名单顺序，客户端可见。
 */
public final class TeamAssignment {

    private TeamAssignment() {
    }

    /**
     * 给一份名单分队。
     *
     * @param members     参战名单（站位顺序）
     * @param ratingCenti 各成员的评分 × 100；<b>只有 5V5 用</b>，其余模式可以传空表。表里没有的人按 {@link RatingReader#DEFAULT_CENTI}
     * @return 与 {@code members} 同下标的队号（新数组）
     */
    public static int[] assign(MatchMode mode, List<Long> members, Map<Long, Long> ratingCenti) {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(members, "members");
        if (mode == MatchMode.MATCH_MODE_5V5) {
            return snake(members, ratingCenti == null ? Map.of() : ratingCenti);
        }
        int[] teams = new int[members.size()];
        if (mode == MatchMode.MATCH_MODE_1V1 || mode == MatchMode.MATCH_MODE_PVP_CHALLENGE) {
            for (int i = 0; i < teams.length; i++) {
                teams[i] = i;
            }
        }
        return teams;
    }

    /**
     * 按评分蛇形分队（任意人数；5V5 是 10 人）。名次 {@code rank}（从 0 起）的队号：每两个名次换一次方向，即
     * {@code (rank / 2) % 2 == 0 ? rank % 2 : 1 - rank % 2}。
     */
    static int[] snake(List<Long> members, Map<Long, Long> ratingCenti) {
        List<Integer> order = new ArrayList<>(members.size());
        for (int i = 0; i < members.size(); i++) {
            order.add(i);
        }
        // List.sort 是稳定排序：同分的人保持名单顺序（下标小的在前）
        order.sort(Comparator.comparingLong((Integer index) -> rating(members.get(index), ratingCenti)).reversed());
        int[] teams = new int[members.size()];
        for (int rank = 0; rank < order.size(); rank++) {
            teams[order.get(rank)] = (rank / 2) % 2 == 0 ? rank % 2 : 1 - rank % 2;
        }
        return teams;
    }

    private static long rating(long playerId, Map<Long, Long> ratingCenti) {
        Long centi = ratingCenti.get(playerId);
        return centi == null ? RatingReader.DEFAULT_CENTI : centi;
    }
}
