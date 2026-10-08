package com.game.match.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.game.match.MatchProperties;
import com.game.match.rating.EloRules.Ignore;
import com.game.match.rating.EloRules.Settlement;
import com.game.match.rating.EloRules.Verdict;
import com.game.match.support.MatchModes;
import com.game.proto.contracts.kafka.BattleResultEvent;
import com.game.proto.contracts.kafka.BattleResultTeam;
import com.game.proto.eBattleOutcome;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * Elo 规则（纯函数；match-spec §5.1、§5.2 第 5 步、§5.5、§15.1 的 RatingRulesTest 里评分那一半——容差在凑单包、蛇形分队在 gather 包）。
 * 对照基线 {@code rating_match_test.go:369}、{@code :435} 与 {@code rating_review_fix_test.go:160} 的数值。
 */
class EloRulesTest {

    private static final int A_WIN = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN_VALUE;
    private static final int B_WIN = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN_VALUE;
    private static final int DRAW = eBattleOutcome.BATTLE_OUTCOME_DRAW_VALUE;
    private static final IntUnaryOperator CAP_30 = configId -> 30;

    static BattleResultEvent.Builder event(int mode, int outcome, List<Long> teamA, List<Long> teamB) {
        return BattleResultEvent.newBuilder().setBattleId(9001).setMatchMode(mode).setOutcomeValue(outcome)
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(0).addAllPlayerIds(teamA))
                .addTeams(BattleResultTeam.newBuilder().setTeamIndex(1).addAllPlayerIds(teamB));
    }

    private static Verdict.Scored scored(BattleResultEvent.Builder event) {
        return scored(event, CAP_30);
    }

    private static Verdict.Scored scored(BattleResultEvent.Builder event, IntUnaryOperator caps) {
        Verdict verdict = EloRules.judge(event.build(), caps);
        assertThat(verdict).isInstanceOf(Verdict.Scored.class);
        return (Verdict.Scored) verdict;
    }

    private static Ignore ignored(BattleResultEvent.Builder event) {
        Verdict verdict = EloRules.judge(event.build(), CAP_30);
        assertThat(verdict).isInstanceOf(Verdict.Ignored.class);
        return ((Verdict.Ignored) verdict).reason();
    }

    // ================================================================ 计不计分

    @Test
    void 只计1V1与5V5_其余模式一律不计分() {
        assertThat(scored(event(MatchModes.ONE_V_ONE, A_WIN, List.of(1L), List.of(2L))).scoreA()).isEqualTo(1.0);
        assertThat(scored(event(MatchModes.FIVE_V_FIVE, A_WIN, List.of(1L), List.of(2L))).scoreA()).isEqualTo(1.0);

        for (int mode : new int[] {MatchModes.UNSPECIFIED, MatchModes.THREE_V_THREE, MatchModes.PVE_SOLO, MatchModes.PVE_TEAM,
                MatchModes.PVP_CHALLENGE, 99, -1}) {
            assertThat(ignored(event(mode, A_WIN, List.of(1L), List.of(2L)))).as("mode=%d", mode).isEqualTo(Ignore.MODE_NOT_RATED);
        }
    }

    @Test
    void 结局只认A胜B胜平局_其余不计分() {
        assertThat(scored(event(MatchModes.ONE_V_ONE, A_WIN, List.of(1L), List.of(2L))).scoreA()).isEqualTo(1.0);
        assertThat(scored(event(MatchModes.ONE_V_ONE, B_WIN, List.of(1L), List.of(2L))).scoreA()).isEqualTo(0.0);
        assertThat(scored(event(MatchModes.ONE_V_ONE, DRAW, List.of(1L), List.of(2L))).scoreA()).isEqualTo(0.5);

        assertThat(ignored(event(MatchModes.ONE_V_ONE, eBattleOutcome.BATTLE_OUTCOME_ONGOING_VALUE, List.of(1L), List.of(2L))))
                .isEqualTo(Ignore.OUTCOME_NOT_SCORED);
        assertThat(ignored(event(MatchModes.ONE_V_ONE, 7, List.of(1L), List.of(2L)))).as("契约里没有的结局值").isEqualTo(Ignore.OUTCOME_NOT_SCORED);
    }

    @Test
    void 必须恰好两支非空队伍且队号只能是0和1() {
        BattleResultTeam.Builder a = BattleResultTeam.newBuilder().setTeamIndex(0).addPlayerIds(1);
        BattleResultTeam.Builder b = BattleResultTeam.newBuilder().setTeamIndex(1).addPlayerIds(2);
        BattleResultEvent.Builder base = BattleResultEvent.newBuilder().setMatchMode(MatchModes.ONE_V_ONE).setOutcomeValue(A_WIN);

        assertThat(ignored(base.clone())).as("没有队伍").isEqualTo(Ignore.TEAMS_MALFORMED);
        assertThat(ignored(base.clone().addTeams(a))).as("只有一支").isEqualTo(Ignore.TEAMS_MALFORMED);
        assertThat(ignored(base.clone().addTeams(a).addTeams(b).addTeams(b))).as("三支").isEqualTo(Ignore.TEAMS_MALFORMED);
        assertThat(ignored(base.clone().addTeams(a).addTeams(BattleResultTeam.newBuilder().setTeamIndex(2).addPlayerIds(2))))
                .as("队号 2").isEqualTo(Ignore.TEAMS_MALFORMED);
        assertThat(ignored(base.clone().addTeams(a).addTeams(a))).as("两条都是队号 0：B 队为空").isEqualTo(Ignore.TEAMS_MALFORMED);
        assertThat(ignored(base.clone().addTeams(a).addTeams(BattleResultTeam.newBuilder().setTeamIndex(1))))
                .as("B 队没有人").isEqualTo(Ignore.TEAMS_MALFORMED);
        assertThat(ignored(base.clone().addTeams(BattleResultTeam.newBuilder().setTeamIndex(0)).addTeams(b)))
                .as("A 队没有人").isEqualTo(Ignore.TEAMS_MALFORMED);

        Verdict.Scored reversed = scored(base.clone().addTeams(b).addTeams(a));
        assertThat(reversed.teamA()).as("按队号归队，不按出现的先后").containsExactly(1L);
        assertThat(reversed.teamB()).containsExactly(2L);
    }

    @Test
    void 判定顺序_模式先于结局先于队伍形态() {
        BattleResultEvent.Builder pveBroken = BattleResultEvent.newBuilder().setMatchMode(MatchModes.PVE_TEAM)
                .setOutcomeValue(eBattleOutcome.BATTLE_OUTCOME_ONGOING_VALUE);
        BattleResultEvent.Builder ratedOngoingNoTeams = BattleResultEvent.newBuilder().setMatchMode(MatchModes.ONE_V_ONE)
                .setOutcomeValue(eBattleOutcome.BATTLE_OUTCOME_ONGOING_VALUE);

        assertThat(ignored(pveBroken)).isEqualTo(Ignore.MODE_NOT_RATED);
        assertThat(ignored(ratedOngoingNoTeams)).isEqualTo(Ignore.OUTCOME_NOT_SCORED);
        assertThat(ignored(BattleResultEvent.newBuilder())).as("全默认值的空消息").isEqualTo(Ignore.MODE_NOT_RATED);
    }

    // ================================================================ 回合打满按平局

    @Test
    void 回合打满的胜负结果按平局_未打满照胜负_本来就是平局不算改判() {
        Verdict.Scored capped = scored(event(MatchModes.ONE_V_ONE, B_WIN, List.of(1L), List.of(2L)).setTotalRounds(30));
        Verdict.Scored beyond = scored(event(MatchModes.FIVE_V_FIVE, A_WIN, List.of(1L), List.of(2L)).setTotalRounds(31));
        Verdict.Scored below = scored(event(MatchModes.ONE_V_ONE, B_WIN, List.of(1L), List.of(2L)).setTotalRounds(29));
        Verdict.Scored draw = scored(event(MatchModes.ONE_V_ONE, DRAW, List.of(1L), List.of(2L)).setTotalRounds(30));

        assertThat(capped.scoreA()).isEqualTo(0.5);
        assertThat(capped.roundCapDraw()).isTrue();
        assertThat(beyond.scoreA()).as("A 胜打满也按平局").isEqualTo(0.5);
        assertThat(beyond.roundCapDraw()).isTrue();
        assertThat(below.scoreA()).isEqualTo(0.0);
        assertThat(below.roundCapDraw()).isFalse();
        assertThat(draw.scoreA()).isEqualTo(0.5);
        assertThat(draw.roundCapDraw()).as("本来就是平局，不计入改判").isFalse();
    }

    @Test
    void 阈值按副本覆盖_覆盖值为0视为没配_总阈值为0关闭判定() {
        MatchProperties.Rating configured = new MatchProperties.Rating(null, null, 30, Map.of(7, 300, 8, 0), null);
        MatchProperties.Rating disabled = new MatchProperties.Rating(null, null, 0, Map.of(), null);
        BattleResultEvent.Builder thirtyRounds = event(MatchModes.ONE_V_ONE, B_WIN, List.of(1L), List.of(2L)).setTotalRounds(30);

        assertThat(scored(thirtyRounds.clone().setBattleConfigId(0), configured::drawRoundCapFor).roundCapDraw()).isTrue();
        assertThat(scored(thirtyRounds.clone().setBattleConfigId(7), configured::drawRoundCapFor).roundCapDraw())
                .as("副本 7 的阈值是 300：30 回合不算打满").isFalse();
        assertThat(scored(thirtyRounds.clone().setBattleConfigId(7).setTotalRounds(300), configured::drawRoundCapFor).roundCapDraw()).isTrue();
        assertThat(scored(thirtyRounds.clone().setBattleConfigId(8), configured::drawRoundCapFor).roundCapDraw())
                .as("覆盖值 0 = 没配，回到总阈值 30").isTrue();
        assertThat(scored(thirtyRounds.clone().setBattleConfigId(-1), configured::drawRoundCapFor).roundCapDraw())
                .as("≥ 2^31 的副本号没有覆盖，按总阈值").isTrue();

        Verdict.Scored off = scored(thirtyRounds.clone(), disabled::drawRoundCapFor);
        assertThat(off.roundCapDraw()).as("阈值 0 = 关闭这条判定").isFalse();
        assertThat(off.scoreA()).isEqualTo(0.0);
    }

    @Test
    void 回合数是uint32_按无符号比较() {
        Verdict.Scored huge = scored(event(MatchModes.ONE_V_ONE, A_WIN, List.of(1L), List.of(2L)).setTotalRounds(-5));

        assertThat(huge.roundCapDraw()).as("位模式为负的回合数是一个很大的无符号数").isTrue();
    }

    // ================================================================ Elo 数值

    @Test
    void 期望得分_同分一半_高400分是十一分之十_两边互补() {
        assertThat(EloRules.expected(1500, 1500)).isEqualTo(0.5);
        assertThat(EloRules.expected(1900, 1500)).isCloseTo(10.0 / 11.0, within(1e-12));
        assertThat(EloRules.expected(1600, 1400) + EloRules.expected(1400, 1600)).isCloseTo(1.0, within(1e-12));
        assertThat(EloRules.expected(1600, 1400)).isCloseTo(0.759746926647958, within(1e-12));
    }

    @Test
    void 两个新号_胜者加16败者减16() {
        Settlement aWins = EloRules.settle(scored(event(MatchModes.ONE_V_ONE, A_WIN, List.of(1L), List.of(2L))), Map.of(1L, 150_000L, 2L, 150_000L));
        Settlement bWins = EloRules.settle(scored(event(MatchModes.ONE_V_ONE, B_WIN, List.of(1L), List.of(2L))), Map.of(1L, 150_000L, 2L, 150_000L));
        Settlement draw = EloRules.settle(scored(event(MatchModes.ONE_V_ONE, DRAW, List.of(1L), List.of(2L))), Map.of(1L, 150_000L, 2L, 150_000L));

        assertThat(aWins.deltaA()).isEqualTo(16.0);
        assertThat(aWins.deltaACenti()).isEqualTo(1_600);
        assertThat(aWins.nextCenti()).containsExactly(Map.entry(1L, 151_600L), Map.entry(2L, 148_400L));
        assertThat(bWins.deltaACenti()).isEqualTo(-1_600);
        assertThat(bWins.nextCenti()).containsExactly(Map.entry(1L, 148_400L), Map.entry(2L, 151_600L));
        assertThat(draw.deltaA()).isEqualTo(0.0);
        assertThat(draw.nextCenti()).containsExactly(Map.entry(1L, 150_000L), Map.entry(2L, 150_000L));
    }

    @Test
    void 分差200的三种结局_数值逐分钉住() {
        Map<Long, Long> pre = Map.of(1L, 160_000L, 2L, 140_000L);

        Settlement highWins = EloRules.settle(scored(event(MatchModes.ONE_V_ONE, A_WIN, List.of(1L), List.of(2L))), pre);
        Settlement highLoses = EloRules.settle(scored(event(MatchModes.ONE_V_ONE, B_WIN, List.of(1L), List.of(2L))), pre);
        Settlement draw = EloRules.settle(scored(event(MatchModes.ONE_V_ONE, DRAW, List.of(1L), List.of(2L))), pre);

        // E(1600, 1400) = 0.759746926647958
        assertThat(highWins.deltaA()).isCloseTo(7.688098347265344, within(1e-9));
        assertThat(highWins.nextCenti()).containsExactly(Map.entry(1L, 160_769L), Map.entry(2L, 139_231L));
        assertThat(highLoses.deltaA()).isCloseTo(-24.311901652734656, within(1e-9));
        assertThat(highLoses.nextCenti()).containsExactly(Map.entry(1L, 157_569L), Map.entry(2L, 142_431L));
        assertThat(draw.deltaA()).as("高分方平局即失分，但少于真输").isCloseTo(-8.311901652734656, within(1e-9));
        assertThat(draw.nextCenti()).containsExactly(Map.entry(1L, 159_169L), Map.entry(2L, 140_831L));
        assertThat(draw.averageA()).isEqualTo(1600.0);
        assertThat(draw.averageB()).isEqualTo(1400.0);
    }

    @Test
    void 平局_1516对1484_高分方失分() {
        Settlement draw = EloRules.settle(scored(event(MatchModes.ONE_V_ONE, DRAW, List.of(1L), List.of(2L))), Map.of(1L, 151_600L, 2L, 148_400L));

        assertThat(draw.deltaA()).isCloseTo(-1.4695015, within(1e-6));
        assertThat(draw.nextCenti()).containsExactly(Map.entry(1L, 151_453L), Map.entry(2L, 148_547L));
        assertThat(draw.deltaACenti()).isEqualTo(-147);
    }

    @Test
    void 五对五用队伍平均分_同队每人同一个增量() {
        // A 队 1600 / 1400（均 1500）对 B 队 1500 / 1500，B 胜 → A 队每人 −16、B 队每人 +16
        Verdict.Scored scored = scored(event(MatchModes.FIVE_V_FIVE, B_WIN, List.of(12101L, 12102L), List.of(12103L, 12104L)));

        Settlement settlement = EloRules.settle(scored, Map.of(12101L, 160_000L, 12102L, 140_000L, 12103L, 150_000L, 12104L, 150_000L));

        assertThat(settlement.averageA()).isEqualTo(1500.0);
        assertThat(settlement.averageB()).isEqualTo(1500.0);
        assertThat(settlement.nextCenti()).containsExactly(Map.entry(12101L, 158_400L), Map.entry(12102L, 138_400L),
                Map.entry(12103L, 151_600L), Map.entry(12104L, 151_600L));
    }

    @Test
    void 下限是0_不会出现负分() {
        assertThat(EloRules.nextCenti(1_000, -16)).isZero();
        assertThat(EloRules.nextCenti(0, -0.01)).isZero();
        assertThat(EloRules.nextCenti(1_600, -16)).isZero();
        assertThat(EloRules.nextCenti(1_601, -16)).isEqualTo(1);

        Settlement settlement = EloRules.settle(scored(event(MatchModes.ONE_V_ONE, B_WIN, List.of(1L), List.of(2L))), Map.of(1L, 500L, 2L, 500L));
        assertThat(settlement.nextCenti()).as("5.00 − 16 夹到 0；对手照加 16").containsExactly(Map.entry(1L, 0L), Map.entry(2L, 2_100L));
    }

    @Test
    void 两位小数_对double的精确值打平取偶_与C的printf逐位一致() {
        // 恰好打平（二进制能精确表示的 x.xx5）：取偶，不是四舍五入
        assertThat(EloRules.toCenti(0.125)).isEqualTo(12);
        assertThat(EloRules.toCenti(0.375)).isEqualTo(38);
        assertThat(EloRules.toCenti(0.625)).isEqualTo(62);
        assertThat(EloRules.toCenti(0.875)).isEqualTo(88);
        assertThat(EloRules.nextCenti(150_000, 0.125)).as("1500.125 → 1500.12").isEqualTo(150_012);
        assertThat(EloRules.nextCenti(150_000, 0.375)).as("1500.375 → 1500.38").isEqualTo(150_038);
        assertThat(EloRules.nextCenti(150_000, -0.125)).as("1499.875 → 1499.88").isEqualTo(149_988);
        // 十进制看着打平、二进制其实略低于一半：按精确值舍去（printf("%.2f", 2.675) = 2.67、1.005 = 1.00）
        assertThat(EloRules.toCenti(2.675)).isEqualTo(267);
        assertThat(EloRules.toCenti(1.005)).isEqualTo(100);
        // 普通情形
        assertThat(EloRules.toCenti(1516.0)).isEqualTo(151_600);
        assertThat(EloRules.toCenti(1591.688098347265)).isEqualTo(159_169);
        assertThat(EloRules.toCenti(-1.4695015)).isEqualTo(-147);
    }

    @Test
    void centi与评分点互换不丢精度() {
        for (long centi : new long[] {0, 1, 99, 150_000, 150_025, 151_453, 299_999, 1_234_567}) {
            assertThat(EloRules.toCenti(EloRules.toPoints(centi))).as("centi=%d", centi).isEqualTo(centi);
            assertThat(EloRules.nextCenti(centi, 0)).isEqualTo(centi);
        }
    }

    // ================================================================ 重复的玩家号

    @Test
    void 名单里重复的玩家_平均分按名单原样算_入账只算一次取首次出现的队别() {
        // 同队重复：A 队 [1, 1]（1600）对 B 队 [2]（1400）
        Verdict.Scored sameTeam = scored(event(MatchModes.ONE_V_ONE, A_WIN, List.of(1L, 1L), List.of(2L)));
        // 跨队重复：玩家 2 同时在两队 → 算 A 队
        Verdict.Scored crossTeam = scored(event(MatchModes.FIVE_V_FIVE, A_WIN, List.of(1L, 2L), List.of(2L, 3L)));

        assertThat(sameTeam.sides()).containsExactly(Map.entry(1L, true), Map.entry(2L, false));
        assertThat(EloRules.teamAverage(List.of(1L, 1L, 3L), Map.of(1L, 160_000L, 3L, 130_000L)))
                .as("重复的人在平均分里算两次：(1600 + 1600 + 1300) / 3").isEqualTo(1500.0);
        Settlement same = EloRules.settle(sameTeam, Map.of(1L, 160_000L, 2L, 140_000L));
        assertThat(same.nextCenti()).as("玩家 1 只入账一次").containsExactly(Map.entry(1L, 160_769L), Map.entry(2L, 139_231L));

        assertThat(crossTeam.sides()).as("A 队名单在前：玩家 2 取 A 队").containsExactly(Map.entry(1L, true), Map.entry(2L, true), Map.entry(3L, false));
        Settlement cross = EloRules.settle(crossTeam, Map.of(1L, 150_000L, 2L, 150_000L, 3L, 150_000L));
        assertThat(cross.nextCenti()).containsExactly(Map.entry(1L, 151_600L), Map.entry(2L, 151_600L), Map.entry(3L, 148_400L));
    }

    @Test
    void 赛前评分缺人_直接报错而不是悄悄当成1500() {
        Verdict.Scored scored = scored(event(MatchModes.ONE_V_ONE, A_WIN, List.of(1L), List.of(Long.MIN_VALUE + 2)));

        assertThatThrownBy(() -> EloRules.settle(scored, Map.of(1L, 150_000L)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("9223372036854775810");
    }

    @Test
    void 名单是事件的快照_之后改不动() {
        Verdict.Scored scored = scored(event(MatchModes.ONE_V_ONE, A_WIN, List.of(1L), List.of(2L)));

        assertThatThrownBy(() -> scored.teamA().add(9L)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> EloRules.settle(scored, Map.of(1L, 150_000L, 2L, 150_000L)).nextCenti().put(9L, 1L))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
