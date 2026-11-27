package com.game.match.global;

import com.game.match.matcher.Tolerance;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 人数档真人 PVP 计分组排（{@code MATCH_MODE_PVP_TEAM}，每侧 1..5 人，2V2..5V5 四个独立池）的纯规则与撮合器
 * （对齐主仓 {@code docs/design/global-match-hierarchical-pools.md} 开头「人数档真人 PVP 与计分组排」）：
 * <ul>
 *   <li><b>队伍分</b>：成员平均分 + {@code (size - 1) * teamBonusCenti}（默认每多一人 +25 分 = 2500 centi，可配、进全局策略指纹）；
 *       只用于凑人、分边与提交点复核，Elo 结算仍用成员原分。</li>
 *   <li><b>不可拆单位选人</b>：候选按人数分桶（1..N），枚举 2N 的整数组合拆分（每份 <= N 且包含锚点），同桶取排队最早；
 *       计分时在包含锚点的容差窗口内择优成局，先来先成局优先于分边质量。</li>
 *   <li><b>车队对等</b>：两侧最大车队人数差 <= 1 立即可成；更大差值要求吃亏一侧每个单位均满足
 *       {@code 1 + (nowMs - enqueuedMs) / premadeStepMs >= diff}（默认步长 10 秒，可配，不进策略指纹）。</li>
 *   <li><b>分边</b>：枚举选中单位的全部等人数两分法，优先最小化两侧最大车队人数差，其次最小化两侧
 *       {@code sum(teamRating * size)} 之差。</li>
 *   <li><b>提交点复核</b>：计分排队局（1V1 / 5V5 / PVP_TEAM）复核组内队伍分极差 <= 组内最长等待者的容差。</li>
 * </ul>
 */
public final class PvpTeamMatchPlanner {

    /** 缺省队伍分加成：每多 1 名队友 +25.00 分（2500 centi）。 */
    public static final long DEFAULT_TEAM_BONUS_CENTI = 2_500L;
    /** 缺省车队对等放宽步长：每等 10 秒放宽 1 档人数差。 */
    public static final long DEFAULT_PREMADE_STEP_MS = 10_000L;

    /** 一个不可拆分的排队单位（单排 size=1 或整队 size=2..N）。 */
    public record Unit(String unitId, int size, long baseAvgRatingCenti, long enqueuedMs, List<Long> playerIds) {
        public Unit {
            Objects.requireNonNull(unitId, "unitId");
            Objects.requireNonNull(playerIds, "playerIds");
            if (size < 1 || size > 5 || playerIds.size() != size) {
                throw new IllegalArgumentException("PVP 排队单位人数非法: size=" + size + " players=" + playerIds.size());
            }
            playerIds = List.copyOf(playerIds);
        }

        /** 成员平均分 + 每多一人 bonus（只用于凑人/分边/提交点复核，不用于 Elo）。 */
        public long teamRatingCenti(long teamBonusCenti) {
            return baseAvgRatingCenti + (size - 1L) * Math.max(0L, teamBonusCenti);
        }
    }

    /** 撮合与分边输出：两侧人数均为 perSide，含每个成员的显式 team_index（0/1）。 */
    public record MatchPlan(List<Unit> side0, List<Unit> side1, int premadeDiff, long weightedRatingDiffCenti) {
        public Map<Long, Integer> memberTeams() {
            Map<Long, Integer> out = new LinkedHashMap<>();
            for (Unit u : side0) {
                for (long pid : u.playerIds()) {
                    out.put(pid, 0);
                }
            }
            for (Unit u : side1) {
                for (long pid : u.playerIds()) {
                    out.put(pid, 1);
                }
            }
            return out;
        }
    }

    @FunctionalInterface
    public interface ToleranceCurve {
        long toleranceCenti(long waitMillis);
    }

    private PvpTeamMatchPlanner() {
    }

    /** 计算一个单位的队伍分（centi）。 */
    public static long teamRatingCenti(long baseAvgRatingCenti, int size, long teamBonusCenti) {
        if (size < 1 || size > 5) {
            throw new IllegalArgumentException("无效单位人数: " + size);
        }
        return baseAvgRatingCenti + (size - 1L) * Math.max(0L, teamBonusCenti);
    }

    /**
     * 校验车队对等：两侧最大车队人数差 <= 1 立即可成；更大差值要求吃亏一侧每个单位均满足
     * {@code 1 + max(0, nowMs - enqueuedMs) / premadeStepMs >= diff}。
     */
    public static boolean premadeParitySatisfied(List<Unit> side0, List<Unit> side1, long nowMs, long premadeStepMs) {
        int max0 = side0.stream().mapToInt(Unit::size).max().orElse(0);
        int max1 = side1.stream().mapToInt(Unit::size).max().orElse(0);
        int diff = Math.abs(max0 - max1);
        if (diff <= 1) {
            return true;
        }
        long step = Math.max(1L, premadeStepMs);
        List<Unit> disadvantaged = max0 < max1 ? side0 : side1;
        for (Unit u : disadvantaged) {
            long waitMs = Math.max(0L, nowMs - u.enqueuedMs());
            long allowedDiff = 1L + (waitMs / step);
            if (allowedDiff < diff) {
                return false;
            }
        }
        return true;
    }

    /**
     * 提交点复核（计分排队局 1V1 / 5V5 / PVP_TEAM）：组内所有单位的队伍分极差不得超过组内最长等待者的容差。
     */
    public static boolean commitSpreadValid(List<Unit> units, long nowMs, long teamBonusCenti, ToleranceCurve curve) {
        if (units == null || units.size() < 2) {
            return true;
        }
        long minRating = Long.MAX_VALUE;
        long maxRating = Long.MIN_VALUE;
        long maxWaitMs = 0L;
        for (Unit u : units) {
            long tr = u.teamRatingCenti(teamBonusCenti);
            if (tr < minRating) minRating = tr;
            if (tr > maxRating) maxRating = tr;
            long wait = Math.max(0L, nowMs - u.enqueuedMs());
            if (wait > maxWaitMs) maxWaitMs = wait;
        }
        long allowed = curve.toleranceCenti(maxWaitMs);
        if (allowed == Tolerance.UNBOUNDED) {
            return true;
        }
        return (maxRating - minRating) <= allowed;
    }

    /**
     * 为锚点在同池候选里选出完整一局并完成分边。
     *
     * @param perSide        每侧人数（1..5，共 2*perSide 人）
     * @param anchor         锚点单位（必在成局内）
     * @param candidates     同池候选单位（可含锚点自身，内部会自动排除）
     * @param nowMs          当前时刻
     * @param teamBonusCenti 队伍分每多一人加成（centi）
     * @param premadeStepMs  车队对等步长（毫秒）
     * @param curve          容差曲线（传 {@code w -> Tolerance.UNBOUNDED} 表示不限分差）
     */
    public static Optional<MatchPlan> planMatch(int perSide, Unit anchor, List<Unit> candidates,
                                                long nowMs, long teamBonusCenti, long premadeStepMs,
                                                ToleranceCurve curve) {
        if (perSide < 1 || perSide > 5 || anchor == null || anchor.size() > perSide) {
            return Optional.empty();
        }
        long anchorRating = anchor.teamRatingCenti(teamBonusCenti);
        long anchorTolerance = curve.toleranceCenti(Math.max(0L, nowMs - anchor.enqueuedMs()));

        List<Unit> peers = candidates.stream()
                .filter(u -> !u.unitId().equals(anchor.unitId()) && u.size() <= perSide)
                .sorted(Comparator.comparingLong(Unit::enqueuedMs).thenComparing(Unit::unitId))
                .toList();

        // 构造要尝试的评分窗口（宽度 = anchorTolerance 且包含 anchorRating）
        List<long[]> windows = buildRatingWindows(anchorRating, anchorTolerance, peers, teamBonusCenti);
        // 枚举总人数 = 2*perSide、每份 <= perSide 且至少含一份 anchor.size() 的拆分
        List<int[]> partitions = enumeratePartitions(2 * perSide, perSide, anchor.size());

        MatchPlan bestAcrossWindows = null;
        long bestLatestEnqueued = Long.MAX_VALUE;

        for (long[] win : windows) {
            long low = win[0], high = win[1];
            // 按人数分桶，每桶按排队最早有序
            Map<Integer, List<Unit>> buckets = new HashMap<>();
            for (Unit u : peers) {
                long tr = u.teamRatingCenti(teamBonusCenti);
                if (tr >= low && tr <= high) {
                    buckets.computeIfAbsent(u.size(), k -> new ArrayList<>()).add(u);
                }
            }
            for (int[] part : partitions) {
                List<Unit> picked = pickEarliestForPartition(anchor, part, buckets);
                if (picked == null) {
                    continue;
                }
                if (!commitSpreadValid(picked, nowMs, teamBonusCenti, curve)) {
                    continue;
                }
                Optional<MatchPlan> split = assignSides(perSide, picked, nowMs, teamBonusCenti, premadeStepMs);
                if (split.isEmpty()) {
                    continue;
                }
                // 先来先成局优先于分边质量：先比组内最晚入队时刻（最早凑齐者胜），再比车队差与分差
                long latestEnq = picked.stream().mapToLong(Unit::enqueuedMs).max().orElse(Long.MAX_VALUE);
                if (bestAcrossWindows == null
                        || latestEnq < bestLatestEnqueued
                        || (latestEnq == bestLatestEnqueued && isBetterSplit(split.get(), bestAcrossWindows))) {
                    bestAcrossWindows = split.get();
                    bestLatestEnqueued = latestEnq;
                }
            }
        }
        return Optional.ofNullable(bestAcrossWindows);
    }

    /** 枚举所有两分法（两侧人数均为 perSide），校验车队对等，取 (premadeDiff, weightedRatingDiffCenti) 最小者。 */
    public static Optional<MatchPlan> assignSides(int perSide, List<Unit> units, long nowMs,
                                                  long teamBonusCenti, long premadeStepMs) {
        int total = units.stream().mapToInt(Unit::size).sum();
        if (total != 2 * perSide) {
            return Optional.empty();
        }
        MatchPlan[] best = new MatchPlan[1];
        List<Unit> side0 = new ArrayList<>();
        List<Unit> side1 = new ArrayList<>();
        // 固定第一个单位在 side0 去重镜像分法
        side0.add(units.getFirst());
        searchSplits(units, 1, perSide, units.getFirst().size(), 0, side0, side1, nowMs, teamBonusCenti, premadeStepMs, best);
        return Optional.ofNullable(best[0]);
    }

    private static void searchSplits(List<Unit> units, int idx, int perSide, int sum0, int sum1,
                                     List<Unit> side0, List<Unit> side1, long nowMs,
                                     long teamBonusCenti, long premadeStepMs, MatchPlan[] best) {
        if (sum0 > perSide || sum1 > perSide) {
            return;
        }
        if (idx == units.size()) {
            if (sum0 == perSide && sum1 == perSide && premadeParitySatisfied(side0, side1, nowMs, premadeStepMs)) {
                int max0 = side0.stream().mapToInt(Unit::size).max().orElse(0);
                int max1 = side1.stream().mapToInt(Unit::size).max().orElse(0);
                int diff = Math.abs(max0 - max1);
                long w0 = side0.stream().mapToLong(u -> u.teamRatingCenti(teamBonusCenti) * u.size()).sum();
                long w1 = side1.stream().mapToLong(u -> u.teamRatingCenti(teamBonusCenti) * u.size()).sum();
                MatchPlan candidate = new MatchPlan(List.copyOf(side0), List.copyOf(side1), diff, Math.abs(w0 - w1));
                if (best[0] == null || isBetterSplit(candidate, best[0])) {
                    best[0] = candidate;
                }
            }
            return;
        }
        Unit cur = units.get(idx);
        if (sum0 + cur.size() <= perSide) {
            side0.add(cur);
            searchSplits(units, idx + 1, perSide, sum0 + cur.size(), sum1, side0, side1, nowMs, teamBonusCenti, premadeStepMs, best);
            side0.removeLast();
        }
        if (sum1 + cur.size() <= perSide) {
            side1.add(cur);
            searchSplits(units, idx + 1, perSide, sum0, sum1 + cur.size(), side0, side1, nowMs, teamBonusCenti, premadeStepMs, best);
            side1.removeLast();
        }
    }

    private static boolean isBetterSplit(MatchPlan a, MatchPlan b) {
        if (a.premadeDiff() != b.premadeDiff()) {
            return a.premadeDiff() < b.premadeDiff();
        }
        return a.weightedRatingDiffCenti() < b.weightedRatingDiffCenti();
    }

    private static List<long[]> buildRatingWindows(long anchorRating, long tolerance, List<Unit> peers, long teamBonusCenti) {
        if (tolerance == Tolerance.UNBOUNDED) {
            return List.of(new long[] {Long.MIN_VALUE, Long.MAX_VALUE});
        }
        // 包含 anchorRating 且宽度为 tolerance 的窗口：以每个落在 [anchor-tol, anchor] 内的候选分（及 anchor-tol、anchor）作左端点
        java.util.TreeSet<Long> lefts = new java.util.TreeSet<>();
        lefts.add(anchorRating - tolerance);
        lefts.add(anchorRating);
        for (Unit u : peers) {
            long r = u.teamRatingCenti(teamBonusCenti);
            if (r >= anchorRating - tolerance && r <= anchorRating) {
                lefts.add(r);
            }
        }
        List<long[]> out = new ArrayList<>(lefts.size());
        for (long l : lefts) {
            long h = l > Long.MAX_VALUE - tolerance ? Long.MAX_VALUE : l + tolerance;
            if (anchorRating >= l && anchorRating <= h) {
                out.add(new long[] {l, h});
            }
        }
        return out;
    }

    static List<int[]> enumeratePartitions(int total, int maxPart, int requiredPart) {
        List<int[]> results = new ArrayList<>();
        List<Integer> cur = new ArrayList<>();
        cur.add(requiredPart);
        dfsPartitions(total - requiredPart, maxPart, cur, results);
        return results;
    }

    private static void dfsPartitions(int rem, int maxPart, List<Integer> cur, List<int[]> out) {
        if (rem == 0) {
            int[] arr = new int[cur.size()];
            for (int i = 0; i < cur.size(); i++) arr[i] = cur.get(i);
            out.add(arr);
            return;
        }
        for (int p = Math.min(rem, maxPart); p >= 1; p--) {
            cur.add(p);
            dfsPartitions(rem - p, p, cur, out);
            cur.removeLast();
        }
    }

    private static List<Unit> pickEarliestForPartition(Unit anchor, int[] part, Map<Integer, List<Unit>> buckets) {
        // part[0] 已由 anchor 占住，从下标 1 起按桶顺序取最早排队单位
        Map<Integer, Integer> used = new HashMap<>();
        List<Unit> picked = new ArrayList<>(part.length);
        picked.add(anchor);
        for (int i = 1; i < part.length; i++) {
            int sz = part[i];
            int idx = used.getOrDefault(sz, 0);
            List<Unit> list = buckets.get(sz);
            if (list == null || idx >= list.size()) {
                return null;
            }
            picked.add(list.get(idx));
            used.put(sz, idx + 1);
        }
        return picked;
    }
}
