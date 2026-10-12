package com.game.match.rating;

import com.game.match.support.MatchModes;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;

/**
 * 对局结果 → Elo 评分变化的规则（纯函数；match-spec §5.1、§5.2 第 5 步、§5.5；基线 {@code rating.go:94-101}、{@code :327-341}、{@code :390-528}）。
 * 不碰库、不记指标、不打日志：{@link RatingStore} 在一笔事务里读出赛前评分后调这里算，单测也直接钉这里。
 *
 * <p>规则（逐条照搬基线）：
 * <ol>
 *   <li><b>只计 1V1 / 5V5</b>（{@link MatchModes#rated}）；PVE、切磋、活动局不计分。</li>
 *   <li><b>结局</b>只认 A 胜（S<sub>A</sub> = 1）、B 胜（0）、平局（0.5）；其余（进行中、契约里没有的值）不计分。</li>
 *   <li><b>队伍</b>必须恰好两条 {@code teams}，队号只能是 0 / 1，按队号归并后两边都非空；否则不计分。</li>
 *   <li><b>回合打满按平局</b>：胜负结果且 {@code total_rounds ≥ 阈值}（阈值 > 0）时 S<sub>A</sub> 改成 0.5。引擎打满回合一律判 B 胜，那是 PVE 的
 *       「进攻方判负」漏到了 PVP；最后一回合真把人打死也落在这里（与打满不可区分），照基线保守按平局。</li>
 *   <li><b>Elo</b>：{@code E = 1 / (1 + 10^((B − A) / 400))}，A、B 是两队的平均分（<b>按名单原样求</b>，同一个人在名单里出现两次就算两次）；
 *       {@code Δ_A = 32 × (S_A − E)}，{@code Δ_B = −Δ_A}，同队每人同一个增量。</li>
 *   <li><b>写分</b>：{@code max(0, 当前 + Δ)} 后保留两位小数。基线是 Lua 的 {@code string.format("%.2f", x)}——C printf 对 double 的<b>精确二进制值</b>
 *       舍入、恰好打平时取偶；这里用 {@code new BigDecimal(double).setScale(2, HALF_EVEN)}，逐位一致。</li>
 *   <li><b>同一玩家在事件里出现多次</b>（坏数据）：每人只入账一次，取<b>第一次出现</b>的队别（A 队先于 B 队）。</li>
 * </ol>
 *
 * <p>评分的单位一律是 centi（× 100 的整数，1500.00 = 150000）；只有本类内部换成 double 做 Elo 运算。
 */
public final class EloRules {

    /** Elo 系数：一局最多变动 32 分。 */
    public static final double K = 32;

    private static final double SCORE_WIN = 1.0;
    private static final double SCORE_LOSS = 0.0;
    private static final double SCORE_DRAW = 0.5;

    private EloRules() {
    }

    /** 一局为什么不计分。 */
    public enum Ignore {
        /** 模式不是 1V1 / 5V5。 */
        MODE_NOT_RATED,
        /** 结局不是 A 胜 / B 胜 / 平局。 */
        OUTCOME_NOT_SCORED,
        /** 不是恰好两支非空队伍，或队号不是 0 / 1。 */
        TEAMS_MALFORMED
    }

    /** 一条对局结果的计分判定。 */
    public sealed interface Verdict {

        /** 不计分：不碰库。 */
        record Ignored(Ignore reason) implements Verdict {
        }

        /**
         * 计分。
         *
         * @param scoreA       A 队得分：1 / 0 / 0.5
         * @param roundCapDraw 是不是因为「回合打满」从胜负改成了平局
         * @param teamA        A 队（队号 0）名单，事件里的原样顺序（可能含重复）
         * @param teamB        B 队（队号 1）名单
         */
        record Scored(double scoreA, boolean roundCapDraw, List<Long> teamA, List<Long> teamB) implements Verdict {

            public Scored {
                teamA = List.copyOf(teamA);
                teamB = List.copyOf(teamB);
            }

            /**
             * 要入账的玩家 → 是否按 A 队结算：按首次出现去重（A 队名单在前），所以同时出现在两队的人算 A 队。迭代顺序 = 首次出现的顺序。
             */
            public Map<Long, Boolean> sides() {
                Map<Long, Boolean> out = new LinkedHashMap<>();
                for (Long pid : teamA) {
                    out.putIfAbsent(pid, Boolean.TRUE);
                }
                for (Long pid : teamB) {
                    out.putIfAbsent(pid, Boolean.FALSE);
                }
                return out;
            }
        }
    }

    /**
     * 一局的入账结果。
     *
     * @param averageA   A 队赛前平均分（评分点）
     * @param averageB   B 队赛前平均分
     * @param deltaA     A 队每人的增量（评分点；B 队是它的相反数）
     * @param nextCenti  每名要入账的玩家 → 赛后评分（centi）；迭代顺序 = 首次出现的顺序
     */
    public record Settlement(double averageA, double averageB, double deltaA, Map<Long, Long> nextCenti) {

        public Settlement {
            nextCenti = Collections.unmodifiableMap(new LinkedHashMap<>(nextCenti));
        }

        /** Δ_A 的两位小数定点值（审计列 {@code delta_a_centi}；入账不读它）。 */
        public long deltaACenti() {
            return toCenti(deltaA);
        }
    }

    /**
     * 判定一条结果计不计分、A 队得几分。
     *
     * @param drawRoundCapFor 副本号（{@code battle_config_id} 的位模式）→「回合打满按平局」的阈值；0 = 不做这条判定
     */
    public static Verdict judge(BattleResultEvent event, IntUnaryOperator drawRoundCapFor) {
        if (!MatchModes.rated(event.getMatchMode())) {
            return new Verdict.Ignored(Ignore.MODE_NOT_RATED);
        }
        double scoreA;
        switch (event.getOutcomeValue()) {
            case eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN_VALUE -> scoreA = SCORE_WIN;
            case eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN_VALUE -> scoreA = SCORE_LOSS;
            case eBattleOutcome.BATTLE_OUTCOME_DRAW_VALUE -> scoreA = SCORE_DRAW;
            default -> {
                return new Verdict.Ignored(Ignore.OUTCOME_NOT_SCORED);
            }
        }
        if (event.getTeamsCount() != 2) {
            return new Verdict.Ignored(Ignore.TEAMS_MALFORMED);
        }
        List<Long> teamA = new ArrayList<>();
        List<Long> teamB = new ArrayList<>();
        for (BattleResultTeam team : event.getTeamsList()) {
            if (team.getTeamIndex() == 0) {
                teamA.addAll(team.getPlayerIdsList());
            } else if (team.getTeamIndex() == 1) {
                teamB.addAll(team.getPlayerIdsList());
            } else {
                return new Verdict.Ignored(Ignore.TEAMS_MALFORMED);
            }
        }
        if (teamA.isEmpty() || teamB.isEmpty()) {
            return new Verdict.Ignored(Ignore.TEAMS_MALFORMED);
        }
        boolean roundCapDraw = false;
        if (scoreA != SCORE_DRAW) {
            int cap = drawRoundCapFor.applyAsInt(event.getBattleConfigId());
            // total_rounds 是 uint32：按无符号比
            if (cap > 0 && Integer.compareUnsigned(event.getTotalRounds(), cap) >= 0) {
                scoreA = SCORE_DRAW;
                roundCapDraw = true;
            }
        }
        return new Verdict.Scored(scoreA, roundCapDraw, teamA, teamB);
    }

    /** A 对 B 的期望得分：{@code 1 / (1 + 10^((B − A) / 400))}（评分点）。 */
    public static double expected(double ratingA, double ratingB) {
        return 1 / (1 + Math.pow(10, (ratingB - ratingA) / 400));
    }

    /**
     * 队伍平均分（评分点）：按名单顺序累加再除以人数，名单里重复的人算多次（同基线 {@code teamAverageRating}）。
     *
     * @param preCenti 赛前评分（centi）；名单里的每个人都必须在里面
     */
    public static double teamAverage(List<Long> team, Map<Long, Long> preCenti) {
        double sum = 0;
        for (Long pid : team) {
            sum += toPoints(require(preCenti, pid));
        }
        return sum / team.size();
    }

    /** A 队每人的增量（评分点）：{@code 32 × (S_A − E(avgA, avgB))}。 */
    public static double deltaA(double scoreA, double averageA, double averageB) {
        return K * (scoreA - expected(averageA, averageB));
    }

    /**
     * 一名玩家的赛后评分（centi）：{@code max(0, 当前 + Δ)} 保留两位小数，对 double 的精确值按 HALF_EVEN 舍入（= C printf {@code %.2f}）。
     *
     * @param currentCenti 赛前评分（centi）
     * @param delta        本局增量（评分点）
     */
    public static long nextCenti(long currentCenti, double delta) {
        double next = toPoints(currentCenti) + delta;
        if (next < 0) {
            next = 0;
        }
        return toCenti(next);
    }

    /**
     * 按赛前评分算出整局的入账结果：两队平均分 → Δ_A → 每名（去重后的）玩家的赛后评分。
     *
     * @param preCenti 赛前评分（centi）：必须覆盖两队名单里的每一个人（没有行的新号由调用方先补成缺省值）
     */
    public static Settlement settle(Verdict.Scored scored, Map<Long, Long> preCenti) {
        double averageA = teamAverage(scored.teamA(), preCenti);
        double averageB = teamAverage(scored.teamB(), preCenti);
        double deltaA = deltaA(scored.scoreA(), averageA, averageB);
        double deltaB = -deltaA;
        Map<Long, Long> next = new LinkedHashMap<>();
        scored.sides().forEach((pid, sideA) -> next.put(pid, nextCenti(require(preCenti, pid), sideA ? deltaA : deltaB)));
        return new Settlement(averageA, averageB, deltaA, next);
    }

    /** centi → 评分点。整数除以 100.0 是正确舍入的，等于基线把 {@code "1500.25"} 这样的两位小数串解析成 double。 */
    static double toPoints(long centi) {
        return centi / 100.0;
    }

    /** 评分点 → centi：double 的精确十进制值保留两位、打平取偶。 */
    static long toCenti(double points) {
        return new BigDecimal(points).setScale(2, RoundingMode.HALF_EVEN).unscaledValue().longValueExact();
    }

    private static long require(Map<Long, Long> preCenti, Long pid) {
        Long centi = preCenti.get(pid);
        if (centi == null) {
            throw new IllegalArgumentException("缺玩家 " + Long.toUnsignedString(pid) + " 的赛前评分");
        }
        return centi;
    }
}
