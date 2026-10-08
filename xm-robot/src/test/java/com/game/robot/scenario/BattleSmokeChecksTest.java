package com.game.robot.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.proto.BattleActorState;
import com.game.proto.BattleEndS2C;
import com.game.proto.BattleSettlementData;
import com.game.proto.BattleStateS2C;
import com.game.proto.TipInfoMessage;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.QueueState;
import com.game.robot.client.MatchAdminClient.Rating;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 匹配各场景的纯判据（match-spec §15.5）：评分 Δ（胜负且不满 30 回合 |Δ| = 16，平局或打满 0）、157 的受理 / 拒绝形状、tip 文案逐字节、
 * 服务端时刻的窗口、分队、切磋推送。每条都给出「对的过、错的不过」两面。
 */
class BattleSmokeChecksTest {

    private static final eBattleOutcome A_WIN = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
    private static final eBattleOutcome B_WIN = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
    private static final eBattleOutcome DRAW = eBattleOutcome.BATTLE_OUTCOME_DRAW;
    /** uint64 上半区的玩家号。 */
    private static final long BIG = 0x8000_0000_0000_0001L;

    private static Rating rating(long pid, long centi, long games) {
        return new Rating(pid, centi, games);
    }

    // ---------------------------------------------------------------- 评分

    @Test
    void 评分增量_胜负且不满30回合是正负16_平局或打满是0() {
        assertThat(BattleSmokeChecks.evenDeltaCenti(A_WIN, 1)).isEqualTo(1_600);
        assertThat(BattleSmokeChecks.evenDeltaCenti(A_WIN, 29)).isEqualTo(1_600);
        assertThat(BattleSmokeChecks.evenDeltaCenti(B_WIN, 29)).isEqualTo(-1_600);
        assertThat(BattleSmokeChecks.evenDeltaCenti(A_WIN, 30)).as("打满 30 回合按平局").isZero();
        assertThat(BattleSmokeChecks.evenDeltaCenti(B_WIN, 30)).as("引擎打满一律判 B 胜，评分按平局").isZero();
        assertThat(BattleSmokeChecks.evenDeltaCenti(B_WIN, 300)).isZero();
        assertThat(BattleSmokeChecks.evenDeltaCenti(DRAW, 1)).isZero();
        assertThat(BattleSmokeChecks.RATING_DRAW_ROUND_CAP).isEqualTo(30);
        assertThatThrownBy(() -> BattleSmokeChecks.evenDeltaCenti(eBattleOutcome.BATTLE_OUTCOME_ONGOING, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(BattleSmokeChecks.rated(A_WIN)).isTrue();
        assertThat(BattleSmokeChecks.rated(DRAW)).isTrue();
        assertThat(BattleSmokeChecks.rated(eBattleOutcome.BATTLE_OUTCOME_ONGOING)).isFalse();
        assertThat(BattleSmokeChecks.rated(eBattleOutcome.UNRECOGNIZED)).isFalse();
    }

    @Test
    void 两个新号1V1_A胜且不满30回合_A加16_B减16_games各加1() {
        List<Rating> before = List.of(rating(1, 150_000, 0), rating(BIG, 150_000, 0));
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 151_600, 1), rating(BIG, 148_400, 1)), List.of(0, 1), A_WIN, 5))
                .isNull();
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 148_400, 1), rating(BIG, 151_600, 1)), List.of(0, 1), B_WIN, 5))
                .isNull();
    }

    @Test
    void 平局或打满30回合_两人的增量都是0_但games照样各加1() {
        List<Rating> before = List.of(rating(1, 150_000, 3), rating(2, 150_000, 7));
        List<Rating> unchanged = List.of(rating(1, 150_000, 4), rating(2, 150_000, 8));
        assertThat(BattleSmokeChecks.ratingProblem(before, unchanged, List.of(0, 1), DRAW, 5)).isNull();
        assertThat(BattleSmokeChecks.ratingProblem(before, unchanged, List.of(0, 1), B_WIN, 30)).isNull();
        assertThat(BattleSmokeChecks.ratingProblem(before, unchanged, List.of(0, 1), A_WIN, 31)).isNull();

        // 打满了却照胜负加了分
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 148_400, 4), rating(2, 151_600, 8)), List.of(0, 1), B_WIN, 30))
                .contains("Δ = -16.00", "期望 0.00", "Δ = +16.00");
        // 不满 30 回合的胜负局却没动分
        assertThat(BattleSmokeChecks.ratingProblem(before, unchanged, List.of(0, 1), A_WIN, 29)).contains("期望 +16.00", "期望 -16.00");
    }

    @Test
    void 没入账_只入了一边_加反了_幅度不对_都报出来() {
        List<Rating> before = List.of(rating(1, 150_000, 0), rating(2, 150_000, 0));
        assertThat(BattleSmokeChecks.ratingProblem(before, before, List.of(0, 1), A_WIN, 5)).as("10 s 内没入账")
                .contains("玩家 1（0 队） games 0 → 0（期望 + 1）", "玩家 2（1 队） games 0 → 0（期望 + 1）");
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 151_600, 1), rating(2, 150_000, 0)), List.of(0, 1), A_WIN, 5))
                .as("只入了胜方").doesNotContain("玩家 1").contains("玩家 2（1 队） games 0 → 0", "Δ = 0.00（期望 -16.00）");
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 148_400, 1), rating(2, 151_600, 1)), List.of(0, 1), A_WIN, 5))
                .as("胜负加反了").contains("玩家 1（0 队） 评分 1500.00 → 1484.00，Δ = -16.00（期望 +16.00）");
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 153_200, 1), rating(2, 146_800, 1)), List.of(0, 1), A_WIN, 5))
                .as("K 用成了 64").contains("Δ = +32.00（期望 +16.00）");
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 151_600, 2), rating(2, 148_400, 2)), List.of(0, 1), A_WIN, 5))
                .as("同一局入账两次").contains("games 0 → 2");
        assertThat(BattleSmokeChecks.ratingProblem(before, before, List.of(0, 1), eBattleOutcome.BATTLE_OUTCOME_ONGOING, 5)).contains("不会入账");
    }

    @Test
    void 两队的次序由队号决定_锚点在1队时期望跟着反过来() {
        List<Rating> before = List.of(rating(1, 150_000, 0), rating(2, 150_000, 0));
        List<Rating> after = List.of(rating(1, 148_400, 1), rating(2, 151_600, 1));
        assertThat(BattleSmokeChecks.ratingProblem(before, after, List.of(1, 0), A_WIN, 5)).isNull();
        assertThat(BattleSmokeChecks.ratingProblem(before, after, List.of(0, 1), A_WIN, 5)).isNotNull();
    }

    @Test
    void 五对五_赛前同分_0队每人加16_1队每人减16_有一个人不对就报他() {
        List<Rating> before = new ArrayList<>();
        List<Rating> after = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int team = BattleSmokeChecks.SNAKE_5V5.get(i);
            before.add(rating(100 + i, 150_000, 0));
            after.add(rating(100 + i, team == 0 ? 151_600 : 148_400, 1));
        }
        assertThat(BattleSmokeChecks.ratingProblem(before, after, BattleSmokeChecks.SNAKE_5V5, A_WIN, 12)).isNull();

        after.set(4, rating(104, 148_400, 1));
        assertThat(BattleSmokeChecks.ratingProblem(before, after, BattleSmokeChecks.SNAKE_5V5, A_WIN, 12))
                .contains("玩家 104（0 队）", "期望 +16.00").doesNotContain("玩家 103", "玩家 105");
    }

    @Test
    void 赛前不同分_复用旧账号_只核对方向_成对与上界() {
        // A 1600、B 1500：A 胜 Δ ≈ +11.52，B −11.52
        List<Rating> before = List.of(rating(1, 160_000, 4), rating(2, 150_000, 9));
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 161_152, 5), rating(2, 148_848, 10)), List.of(0, 1), A_WIN, 5)).isNull();
        // 各自四舍五入差 0.01 不算问题
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 161_152, 5), rating(2, 148_847, 10)), List.of(0, 1), A_WIN, 5)).isNull();
        // 平局：高分方掉一点（|Δ| ≤ 16）
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 159_552, 5), rating(2, 150_448, 10)), List.of(0, 1), DRAW, 5)).isNull();

        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 158_000, 5), rating(2, 152_000, 10)), List.of(0, 1), A_WIN, 5))
                .as("胜方掉分").contains("方向或幅度不对");
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 164_000, 5), rating(2, 146_000, 10)), List.of(0, 1), A_WIN, 5))
                .as("超过 K = 32").contains("方向或幅度不对");
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 161_152, 5), rating(2, 149_000, 10)), List.of(0, 1), A_WIN, 5))
                .as("两边不成对").contains("不成对");
        assertThat(BattleSmokeChecks.ratingProblem(before, List.of(rating(1, 161_152, 5), rating(2, 148_848, 9)), List.of(0, 1), A_WIN, 5))
                .contains("games 9 → 9");
        // 触到下限 0 的人不参与增量比较
        List<Rating> floor = List.of(rating(1, 160_000, 0), rating(2, 500, 0));
        assertThat(BattleSmokeChecks.ratingProblem(floor, List.of(rating(1, 160_010, 1), rating(2, 0, 1)), List.of(0, 1), A_WIN, 5)).isNull();
    }

    @Test
    void 名单不等长_队号不是0或1_赛前赛后次序不一致_是调用方的错() {
        List<Rating> one = List.of(rating(1, 150_000, 0));
        assertThatThrownBy(() -> BattleSmokeChecks.ratingProblem(one, List.of(), List.of(0), A_WIN, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BattleSmokeChecks.ratingProblem(List.of(), List.of(), List.of(), A_WIN, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BattleSmokeChecks.ratingProblem(one, one, List.of(2), A_WIN, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BattleSmokeChecks.ratingProblem(one, List.of(rating(9, 150_000, 1)), List.of(0), A_WIN, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void centi的显示_带符号的两位小数() {
        assertThat(BattleSmokeChecks.centi(1_600)).isEqualTo("+16.00");
        assertThat(BattleSmokeChecks.centi(-1_600)).isEqualTo("-16.00");
        assertThat(BattleSmokeChecks.centi(0)).isEqualTo("0.00");
        assertThat(BattleSmokeChecks.centi(5)).isEqualTo("+0.05");
        assertThat(BattleSmokeChecks.centi(-1_152)).isEqualTo("-11.52");
    }

    // ---------------------------------------------------------------- 排队应答

    @Test
    void 票号是36位小写UUID形状() {
        assertThat(BattleSmokeChecks.isQueueTicket("3f2b8c1e-7a4d-4e0b-9c55-0a1b2c3d4e5f")).isTrue();
        assertThat(BattleSmokeChecks.isQueueTicket("3F2B8C1E-7A4D-4E0B-9C55-0A1B2C3D4E5F")).as("大写").isFalse();
        assertThat(BattleSmokeChecks.isQueueTicket("3f2b8c1e7a4d4e0b9c550a1b2c3d4e5f")).as("32 位、没有连字符").isFalse();
        assertThat(BattleSmokeChecks.isQueueTicket("")).isFalse();
        assertThat(BattleSmokeChecks.isQueueTicket("stale")).isFalse();
    }

    @Test
    void 受理的157_error_code为0_不带error_message_票号合形状() {
        String ticket = "3f2b8c1e-7a4d-4e0b-9c55-0a1b2c3d4e5f";
        JoinQueueResponse ok = JoinQueueResponse.newBuilder().setQueueTicket(ticket).build();
        assertThat(BattleSmokeChecks.accepted(ok)).isTrue();
        assertThat(BattleSmokeChecks.joinAcceptedProblem(ok)).isNull();

        JoinQueueResponse emptyTip = ok.toBuilder().setErrorMessage(TipInfoMessage.getDefaultInstance()).build();
        assertThat(BattleSmokeChecks.accepted(emptyTip)).as("带了一个空的 error_message 也算带了").isFalse();
        assertThat(BattleSmokeChecks.joinAcceptedProblem(emptyTip)).contains("不得带 error_message");
        assertThat(BattleSmokeChecks.joinAcceptedProblem(ok.toBuilder().setQueueTicket("").build())).contains("queue_ticket");
        JoinQueueResponse rejected = JoinQueueResponse.newBuilder().setErrorCode(16000)
                .setErrorMessage(TipInfoMessage.newBuilder().setId(16000).addParameters("战斗尚未结束,无法排队")).build();
        assertThat(BattleSmokeChecks.accepted(rejected)).isFalse();
        assertThat(BattleSmokeChecks.joinAcceptedProblem(rejected)).contains("期望受理", "error_code=16000", "tip=16000");
    }

    @Test
    void 拒绝的157_error_code与tip同值_parameters恰好一项且逐字节_票号对上() {
        JoinQueueResponse inBattle = JoinQueueResponse.newBuilder().setErrorCode(16000)
                .setErrorMessage(TipInfoMessage.newBuilder().setId(16000).addParameters("战斗尚未结束,无法排队")).build();
        assertThat(BattleSmokeChecks.joinRejectProblem(inBattle, BattleSmokeChecks.TIP_IN_BATTLE, BattleSmokeChecks.TEXT_IN_BATTLE, "")).isNull();

        // 全角逗号：看着一样，字节不同
        JoinQueueResponse fullWidth = inBattle.toBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(16000).addParameters("战斗尚未结束，无法排队"))
                .build();
        assertThat(BattleSmokeChecks.joinRejectProblem(fullWidth, 16000, BattleSmokeChecks.TEXT_IN_BATTLE, "")).contains("逐字节");
        // error_code 与 error_message.id 不同值
        assertThat(BattleSmokeChecks.joinRejectProblem(inBattle.toBuilder().setErrorCode(1).build(), 16000, BattleSmokeChecks.TEXT_IN_BATTLE, ""))
                .contains("error_code=1（期望 16000）", "不同值");
        // 多了一项 / 没有 parameters
        assertThat(BattleSmokeChecks.joinRejectProblem(inBattle.toBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(16000)
                .addParameters("战斗尚未结束,无法排队").addParameters("x")).build(), 16000, BattleSmokeChecks.TEXT_IN_BATTLE, "")).contains("恰好一项");
        assertThat(BattleSmokeChecks.joinRejectProblem(inBattle.toBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(16000)).build(), 16000,
                BattleSmokeChecks.TEXT_IN_BATTLE, "")).contains("恰好一项");
        // 不该带票号的带了
        assertThat(BattleSmokeChecks.joinRejectProblem(inBattle.toBuilder().setQueueTicket("t").build(), 16000, BattleSmokeChecks.TEXT_IN_BATTLE, ""))
                .contains("queue_ticket=「t」");

        // 16001 带现有票号
        JoinQueueResponse queued = JoinQueueResponse.newBuilder().setErrorCode(16001).setQueueTicket("T")
                .setErrorMessage(TipInfoMessage.newBuilder().setId(16001).addParameters("已在匹配队列中")).build();
        assertThat(BattleSmokeChecks.joinRejectProblem(queued, BattleSmokeChecks.TIP_ALREADY_QUEUED, BattleSmokeChecks.TEXT_ALREADY_QUEUED, "T")).isNull();
        assertThat(BattleSmokeChecks.joinRejectProblem(queued, 16001, BattleSmokeChecks.TEXT_ALREADY_QUEUED, "other")).contains("期望「other」");
        assertThat(BattleSmokeChecks.joinRejectProblem(queued, 16000, BattleSmokeChecks.TEXT_IN_BATTLE, "T")).contains("期望 16000");
    }

    @Test
    void tip的码取自导表枚举_文案的逗号是半角() {
        assertThat(List.of(BattleSmokeChecks.TIP_IN_BATTLE, BattleSmokeChecks.TIP_ALREADY_QUEUED, BattleSmokeChecks.TIP_MODE_NOT_OPEN,
                BattleSmokeChecks.TIP_TEAM_SIZE_NOT_CONFIGURED)).containsExactly(16000, 16001, 16002, 16003);
        assertThat(List.of(BattleSmokeChecks.TIP_CHALLENGE_SELF, BattleSmokeChecks.TIP_CHALLENGE_TARGET_OFFLINE,
                BattleSmokeChecks.TIP_CHALLENGE_TARGET_BUSY, BattleSmokeChecks.TIP_CHALLENGE_SELF_BUSY, BattleSmokeChecks.TIP_CHALLENGE_PENDING,
                BattleSmokeChecks.TIP_CHALLENGE_EXPIRED, BattleSmokeChecks.TIP_CHALLENGE_NOT_TARGET))
                .containsExactly(16007, 16008, 16009, 16010, 16011, 16012, 16013);
        assertThat(BattleSmokeChecks.TIP_INVALID_PARAMETER).isEqualTo(1005);
        for (String text : List.of(BattleSmokeChecks.TEXT_IN_BATTLE, BattleSmokeChecks.TEXT_CHALLENGE_SELF_BUSY)) {
            assertThat(text).contains(",").doesNotContain("，");
        }
        // 逐字节：UTF-8 下「战斗尚未结束,无法排队」= 6 个汉字 + 1 个 ASCII 逗号 + 4 个汉字
        assertThat(BattleSmokeChecks.TEXT_IN_BATTLE.getBytes(StandardCharsets.UTF_8)).hasSize(6 * 3 + 1 + 4 * 3);
    }

    @Test
    void 单条tip的核对_码与文案_battle透传的码只核对id() {
        TipInfoMessage gone = TipInfoMessage.newBuilder().setId(1005).addParameters("该战斗不存在或已结束").build();
        assertThat(BattleSmokeChecks.tipProblem(gone, 1005, BattleSmokeChecks.TEXT_BATTLE_GONE)).isNull();
        assertThat(BattleSmokeChecks.tipProblem(gone, 1005, null)).isNull();
        assertThat(BattleSmokeChecks.tipProblem(TipInfoMessage.newBuilder().setId(1005).build(), 1005, null)).isNull();
        assertThat(BattleSmokeChecks.tipProblem(TipInfoMessage.newBuilder().setId(1005).build(), 1005, BattleSmokeChecks.TEXT_BATTLE_GONE))
                .contains("恰好一项");
        assertThat(BattleSmokeChecks.tipProblem(TipInfoMessage.newBuilder().setId(1003).addParameters("战斗服务暂不可用").build(), 1005, null))
                .contains("tip=1003（期望 1005）", "战斗服务暂不可用");
        assertThat(BattleSmokeChecks.tipProblem(TipInfoMessage.getDefaultInstance(), 16008, BattleSmokeChecks.TEXT_CHALLENGE_TARGET_OFFLINE))
                .contains("tip=0（期望 16008）");
    }

    @Test
    void 应答的摘要() {
        assertThat(BattleSmokeChecks.describe(JoinQueueResponse.newBuilder().setErrorCode(16001).setQueueTicket("T")
                .setErrorMessage(TipInfoMessage.newBuilder().setId(16001).addParameters("已在匹配队列中")).build()))
                .isEqualTo("error_code=16001 tip=16001 parameters=[已在匹配队列中] queue_ticket=T");
        assertThat(BattleSmokeChecks.describe(JoinQueueResponse.getDefaultInstance())).isEqualTo("error_code=0 tip=0");
        assertThat(BattleSmokeChecks.describe(GetQueueStatusResponse.newBuilder().setState(QueueState.QUEUE_STATE_QUEUED).setQueuedSeconds(3).build()))
                .isEqualTo("state=QUEUE_STATE_QUEUED estimated_wait_seconds=0 queued_seconds=3");
    }

    // ---------------------------------------------------------------- 时刻

    @Test
    void 服务端时刻落在发起加TTL减5秒到收到加TTL加5秒之间() {
        long sent = 1_760_000_000_000L;
        long received = sent + 800;
        long ttl = BattleSmokeChecks.BATTLE_DURATION_MS;
        assertThat(ttl).isEqualTo(300_000);
        assertThat(BattleSmokeChecks.deadlineProblem(sent + 300 + ttl, sent, received, ttl)).as("gather 起点在发起之后 300 ms").isNull();
        assertThat(BattleSmokeChecks.deadlineProblem(sent + ttl - 5_000, sent, received, ttl)).as("下界含").isNull();
        assertThat(BattleSmokeChecks.deadlineProblem(received + ttl + 5_000, sent, received, ttl)).as("上界含").isNull();
        assertThat(BattleSmokeChecks.deadlineProblem(sent + ttl - 5_001, sent, received, ttl)).contains("不在", "相对发起 294999 ms");
        assertThat(BattleSmokeChecks.deadlineProblem(received + ttl + 5_001, sent, received, ttl)).isNotNull();
        assertThat(BattleSmokeChecks.deadlineProblem(sent / 1000, sent, received, ttl)).as("写成了秒").isNotNull();
        assertThat(BattleSmokeChecks.deadlineProblem(0, sent, received, ttl)).as("没填").isNotNull();
        // 156 的有效期是 60 s：把 300 s 的值拿来就不对
        assertThat(BattleSmokeChecks.deadlineProblem(sent + ttl, sent, received, BattleSmokeChecks.CHALLENGE_TTL_MS)).isNotNull();
        assertThat(BattleSmokeChecks.deadlineProblem(sent + 60_000, sent, received, BattleSmokeChecks.CHALLENGE_TTL_MS)).isNull();
    }

    // ---------------------------------------------------------------- 分队

    private static BattleStateS2C state(long[] actors, int[] teams) {
        BattleStateS2C.Builder state = BattleStateS2C.newBuilder();
        for (int i = 0; i < actors.length; i++) {
            state.addActors(BattleActorState.newBuilder().setActorId(actors[i]).setTeamIndex(teams[i]));
        }
        return state.build();
    }

    @Test
    void 队号按actor_id找_玩家号按无符号的位模式比较_不在名单里为空() {
        BattleStateS2C state = state(new long[] {7, BIG}, new int[] {0, 1});
        assertThat(BattleSmokeChecks.teamOf(state, 7)).hasValue(0);
        assertThat(BattleSmokeChecks.teamOf(state, BIG)).hasValue(1);
        assertThat(BattleSmokeChecks.teamOf(state, 8)).isEmpty();
    }

    @Test
    void 一对一_锚点在0队另一位在1队_反了或缺席都报() {
        List<Long> order = List.of(7L, BIG);
        assertThat(BattleSmokeChecks.sidesProblem(state(new long[] {7, BIG}, new int[] {0, 1}), order, List.of(0, 1))).isNull();
        // actors 的排列次序无关紧要，看的是各人的队号
        assertThat(BattleSmokeChecks.sidesProblem(state(new long[] {BIG, 7}, new int[] {1, 0}), order, List.of(0, 1))).isNull();
        assertThat(BattleSmokeChecks.sidesProblem(state(new long[] {7, BIG}, new int[] {1, 0}), order, List.of(0, 1)))
                .isEqualTo("按次序的队号是 [1, 0]，期望 [0, 1]");
        assertThat(BattleSmokeChecks.sidesProblem(state(new long[] {7}, new int[] {0}), order, List.of(0, 1))).contains("[0, 缺席]");
        assertThatThrownBy(() -> BattleSmokeChecks.sidesProblem(state(new long[] {7}, new int[] {0}), order, List.of(0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- 终局（评审 ROBOT-2）

    @Test
    void 同一个号的第二局_只要求打出结果且与settlement一致_不要求打赢() {
        eBattleOutcome win = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
        eBattleOutcome lose = eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN;
        eBattleOutcome draw = eBattleOutcome.BATTLE_OUTCOME_DRAW;
        long battle = BIG + 41;
        assertThat(BattleSmokeChecks.pveFinishedProblem(pveEnd(battle, battle, BIG, win, win, 9), battle, BIG, 9)).isNull();
        assertThat(BattleSmokeChecks.pveFinishedProblem(pveEnd(battle, battle, BIG, lose, lose, 9), battle, BIG, 9))
                .as("带着上一局的残血阵亡：合法结果").isNull();
        assertThat(BattleSmokeChecks.pveFinishedProblem(pveEnd(battle, battle, BIG, draw, draw, 30), battle, BIG, 30)).isNull();
        // 打赢才算的那个判据对同一个包仍然判不过
        assertThat(BattleSmokeChecks.pveVictoryProblem(pveEnd(battle, battle, BIG, lose, lose, 9), battle, BIG, 9)).contains("终局不是 SIDE_A_WIN");

        assertThat(BattleSmokeChecks.pveFinishedProblem(pveEnd(battle, battle, BIG, lose, win, 9), battle, BIG, 9))
                .as("外层输了、settlement 却是赢").contains("终局不是外层与 settlement 一致的胜 / 负 / 平", "外层 BATTLE_OUTCOME_SIDE_B_WIN", "settlement BATTLE_OUTCOME_SIDE_A_WIN");
        eBattleOutcome ongoing = eBattleOutcome.BATTLE_OUTCOME_ONGOING;
        assertThat(BattleSmokeChecks.pveFinishedProblem(pveEnd(battle, battle, BIG, ongoing, ongoing, 9), battle, BIG, 9))
                .as("两边一致但不是终局").contains("终局不是外层与 settlement 一致的胜 / 负 / 平");
        // 其余几项与打赢的判据同一套
        assertThat(BattleSmokeChecks.pveFinishedProblem(pveEnd(battle, battle + 1, BIG, lose, lose, 9), battle, BIG, 9))
                .contains("battle_id 对不上本局", Long.toUnsignedString(battle + 1));
        assertThat(BattleSmokeChecks.pveFinishedProblem(pveEnd(battle, battle, 7, lose, lose, 9), battle, BIG, 9)).contains("settlement.player_id = 7");
        assertThat(BattleSmokeChecks.pveFinishedProblem(pveEnd(battle, battle, BIG, lose, lose, 0), battle, BIG, 9)).contains("total_rounds = 0");
        assertThat(BattleSmokeChecks.pveFinishedProblem(pveEnd(battle, battle, BIG, lose, lose, 9), battle, BIG, 0)).contains("139 × 0");
    }

    private static BattleEndS2C pveEnd(long battleId, long settlementBattle, long settlementPlayer, eBattleOutcome outer, eBattleOutcome inner,
                                       int rounds) {
        return BattleEndS2C.newBuilder().setBattleId(battleId).setOutcome(outer).setSettlement(BattleSettlementData.newBuilder()
                .setBattleId(settlementBattle).setPlayerId(settlementPlayer).setOutcome(inner).setTotalRounds(rounds)).build();
    }

    @Test
    void 单人PVE的终局_外层与settlement都要指向本局本人且都是0队胜_至少打过一回合() {
        eBattleOutcome win = eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN;
        long battle = BIG + 40;
        assertThat(BattleSmokeChecks.pveVictoryProblem(pveEnd(battle, battle, BIG, win, win, 2), battle, BIG, 1)).isNull();

        // 基线 features_battle_smoke.go:83-90 的每一项各错一处
        assertThat(BattleSmokeChecks.pveVictoryProblem(pveEnd(battle, battle + 1, BIG, win, win, 2), battle, BIG, 1))
                .as("settlement 是别的局的").contains("battle_id 对不上本局", Long.toUnsignedString(battle + 1));
        assertThat(BattleSmokeChecks.pveVictoryProblem(pveEnd(battle, 0, BIG, win, win, 2), battle, BIG, 1))
                .as("settlement 没填 battle_id").contains("settlement 0");
        assertThat(BattleSmokeChecks.pveVictoryProblem(pveEnd(battle, battle, 7, win, win, 2), battle, BIG, 1))
                .contains("settlement.player_id = 7", "期望本人 " + Long.toUnsignedString(BIG));
        assertThat(BattleSmokeChecks.pveVictoryProblem(pveEnd(battle, battle, BIG, win, eBattleOutcome.BATTLE_OUTCOME_DRAW, 2), battle, BIG, 1))
                .as("外层赢了、settlement 却是平局").contains("终局不是 SIDE_A_WIN", "settlement BATTLE_OUTCOME_DRAW");
        assertThat(BattleSmokeChecks.pveVictoryProblem(pveEnd(battle, battle, BIG, eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN, win, 2), battle, BIG, 1))
                .contains("外层 BATTLE_OUTCOME_SIDE_B_WIN");
        assertThat(BattleSmokeChecks.pveVictoryProblem(pveEnd(battle, battle, BIG, win, win, 0), battle, BIG, 1)).contains("total_rounds = 0");
        assertThat(BattleSmokeChecks.pveVictoryProblem(pveEnd(battle, battle, BIG, win, win, 2), battle, BIG, 0)).contains("139 × 0");
        // 几处同时不对：都写出来
        assertThat(BattleSmokeChecks.pveVictoryProblem(pveEnd(battle, battle + 1, 7, win, win, 2), battle, BIG, 1))
                .contains("battle_id 对不上本局", "settlement.player_id = 7");
    }

    @Test
    void 五对五_评分相同时按入队次序蛇形_前5后5不是() {
        assertThat(BattleSmokeChecks.SNAKE_5V5).containsExactly(0, 1, 1, 0, 0, 1, 1, 0, 0, 1);
        assertThat(BattleSmokeChecks.SNAKE_5V5.stream().filter(t -> t == 0).count()).as("两队各 5 人").isEqualTo(5);
        long[] players = new long[10];
        List<Long> order = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            players[i] = 100 + i;
            order.add(players[i]);
        }
        assertThat(BattleSmokeChecks.sidesProblem(state(players, new int[] {0, 1, 1, 0, 0, 1, 1, 0, 0, 1}), order, BattleSmokeChecks.SNAKE_5V5)).isNull();
        assertThat(BattleSmokeChecks.sidesProblem(state(players, new int[] {0, 0, 0, 0, 0, 1, 1, 1, 1, 1}), order, BattleSmokeChecks.SNAKE_5V5))
                .as("过时的「前 5 后 5」").contains("[0, 0, 0, 0, 0, 1, 1, 1, 1, 1]");
        assertThat(BattleSmokeChecks.sidesProblem(state(players, new int[] {0, 1, 0, 1, 0, 1, 0, 1, 0, 1}), order, BattleSmokeChecks.SNAKE_5V5))
                .as("交替不是蛇形").isNotNull();
    }

    // ---------------------------------------------------------------- 切磋

    @Test
    void 邀请推送156_逐字段_名字是发起者的账号名_有效期约60秒() {
        long sent = 1_760_000_000_000L;
        ChallengeInviteS2C invite = ChallengeInviteS2C.newBuilder().setChallengeId(BIG).setChallengerId(7).setChallengerName("robot_java_bmx1_a")
                .setBattleConfigId(0).setExpiresAtMs(sent + 60_050).build();
        assertThat(BattleSmokeChecks.inviteProblem(invite, BIG, 7, "robot_java_bmx1_a", 0, sent, sent + 120)).isNull();

        assertThat(BattleSmokeChecks.inviteProblem(invite, BIG - 1, 7, "robot_java_bmx1_a", 0, sent, sent + 120))
                .contains("challenge_id=9223372036854775809（期望 9223372036854775808）");
        assertThat(BattleSmokeChecks.inviteProblem(invite, BIG, 8, "robot_java_bmx1_a", 0, sent, sent + 120)).contains("challenger_id=7（期望 8）");
        assertThat(BattleSmokeChecks.inviteProblem(invite, BIG, 7, "角色名", 0, sent, sent + 120)).contains("期望发起者的账号名「角色名」");
        assertThat(BattleSmokeChecks.inviteProblem(invite, BIG, 7, "robot_java_bmx1_a", 1, sent, sent + 120)).contains("battle_config_id=0（期望 1）");
        assertThat(BattleSmokeChecks.inviteProblem(invite.toBuilder().setExpiresAtMs(sent + 300_000).build(), BIG, 7, "robot_java_bmx1_a", 0, sent,
                sent + 120)).contains("expires_at_ms");
        assertThat(BattleSmokeChecks.inviteProblem(invite.toBuilder().clearExpiresAtMs().build(), BIG, 7, "robot_java_bmx1_a", 0, sent, sent + 120))
                .contains("expires_at_ms");
    }

    @Test
    void 结果推送154_逐字段() {
        ChallengeResultS2C declined = ChallengeResultS2C.newBuilder().setChallengeId(5).setAccepted(false).setResponderId(BIG).build();
        assertThat(BattleSmokeChecks.resultProblem(declined, 5, false, BIG)).isNull();
        assertThat(BattleSmokeChecks.resultProblem(declined, 5, true, BIG)).contains("accepted=false", "期望 {5, true, 9223372036854775809}");
        assertThat(BattleSmokeChecks.resultProblem(declined, 6, false, BIG)).isNotNull();
        assertThat(BattleSmokeChecks.resultProblem(declined, 5, false, 7)).isNotNull();
    }

    // ---------------------------------------------------------------- 观战段（批次 6.5）

    @Test
    void S6的配置号_900000加runTag按36进制读的低16位_缺省的时间戳标签与16位长标签都落在范围内() {
        assertThat(BattleSmokeChecks.soloQueueConfig("0")).isEqualTo(900_000);
        assertThat(BattleSmokeChecks.soloQueueConfig("z")).as("z = 35").isEqualTo(900_035);
        assertThat(BattleSmokeChecks.soloQueueConfig("10")).as("36 进制的 10 = 36").isEqualTo(900_036);
        // 低 16 位：36^4 = 1679616 = 0x19A100 → 0xA100 = 41216
        assertThat(BattleSmokeChecks.soloQueueConfig("10000")).isEqualTo(900_000 + 0xA100);
        // 缺省 run-tag 是当前毫秒的 36 进制串
        long now = 1_760_000_000_000L;
        assertThat(BattleSmokeChecks.soloQueueConfig(Long.toString(now, 36))).isEqualTo(900_000 + (int) (now & 0xFFFF));
        // 16 位的标签超出 long：照样只取低 16 位，不溢出、不为负
        assertThat(BattleSmokeChecks.soloQueueConfig("z".repeat(16))).isBetween(900_000, 900_000 + 0xFFFF);
        assertThat(BattleSmokeChecks.soloQueueConfig("x1")).as("不同的标签给出不同的队列").isNotEqualTo(BattleSmokeChecks.soloQueueConfig("x2"));
        assertThat(BattleSmokeChecks.SOLO_QUEUE_CONFIG_BASE + 0xFFFF).as("与 battle-smoke 的 0、跨区场景的 1、PVE 的副本号都不相交").isGreaterThan(900_000);
    }

    @Test
    void 角色名取自143里本人的actor_不在名单里为空() {
        BattleStateS2C state = BattleStateS2C.newBuilder()
                .addActors(BattleActorState.newBuilder().setActorId(BIG).setName("侠客一"))
                .addActors(BattleActorState.newBuilder().setActorId(7).setName("怪")).build();
        assertThat(BattleSmokeChecks.actorName(state, BIG)).contains("侠客一");
        assertThat(BattleSmokeChecks.actorName(state, 7)).contains("怪");
        assertThat(BattleSmokeChecks.actorName(state, 8)).isEmpty();
        assertThat(BattleSmokeChecks.actorName(BattleStateS2C.newBuilder().addActors(BattleActorState.newBuilder().setActorId(9)).build(), 9))
                .as("actor 在、名字没填：空串，由场景报「无从核对」").contains("");
    }

    @Test
    void 屏障期预算_恰好用满不算超_超出时写明用时与预算() {
        assertThat(BattleSmokeChecks.budgetProblem(0, 60_000)).isNull();
        assertThat(BattleSmokeChecks.budgetProblem(60_000, 60_000)).isNull();
        assertThat(BattleSmokeChecks.budgetProblem(60_001, 60_000)).contains("已用 60001 ms", "超出预算 60000 ms");
    }

    @Test
    void S12的应答判读_16015才是结局_16014只在ready残留的窗口内算过渡态_文案不对或别的应答都判错() {
        com.game.proto.match.WatchBattleResponse inBattle = watchTip(16015, "战斗尚未结束,无法观战");
        com.game.proto.match.WatchBattleResponse queued = watchTip(16014, "匹配中无法观战");
        assertThat(BattleSmokeChecks.inBattleWatch(inBattle, true)).isEqualTo(BattleSmokeChecks.InBattleWatch.IN_BATTLE);
        assertThat(BattleSmokeChecks.inBattleWatch(inBattle, false)).isEqualTo(BattleSmokeChecks.InBattleWatch.IN_BATTLE);
        assertThat(BattleSmokeChecks.inBattleWatch(queued, true)).isEqualTo(BattleSmokeChecks.InBattleWatch.READY_RESIDUE);
        assertThat(BattleSmokeChecks.inBattleWatch(queued, false)).as("过了窗口还回 16014：ready 票早该过期了")
                .isEqualTo(BattleSmokeChecks.InBattleWatch.WRONG);
        // 全角逗号、缺文案、别的码、居然成功了
        assertThat(BattleSmokeChecks.inBattleWatch(watchTip(16015, "战斗尚未结束，无法观战"), true)).isEqualTo(BattleSmokeChecks.InBattleWatch.WRONG);
        assertThat(BattleSmokeChecks.inBattleWatch(com.game.proto.match.WatchBattleResponse.newBuilder()
                .setErrorMessage(TipInfoMessage.newBuilder().setId(16014)).build(), true)).isEqualTo(BattleSmokeChecks.InBattleWatch.WRONG);
        assertThat(BattleSmokeChecks.inBattleWatch(watchTip(16004, "服务器繁忙,请稍后再试"), true)).isEqualTo(BattleSmokeChecks.InBattleWatch.WRONG);
        assertThat(BattleSmokeChecks.inBattleWatch(com.game.proto.match.WatchBattleResponse.newBuilder().setBattleId(BIG).build(), true))
                .isEqualTo(BattleSmokeChecks.InBattleWatch.WRONG);
        // 带着 battle_id 的拒绝也不算
        assertThat(BattleSmokeChecks.inBattleWatch(inBattle.toBuilder().setBattleId(1).build(), true)).isEqualTo(BattleSmokeChecks.InBattleWatch.WRONG);
        assertThat(BattleSmokeChecks.READY_TICKET_TTL_MS).isEqualTo(60_000);
        assertThat(SpectateSteps.Timing.STANDARD.readyResidue().toMillis()).as("等的窗口 = ready TTL + 2 s 余量")
                .isEqualTo(BattleSmokeChecks.READY_TICKET_TTL_MS + 2_000);
    }

    private static com.game.proto.match.WatchBattleResponse watchTip(int code, String text) {
        return com.game.proto.match.WatchBattleResponse.newBuilder().setErrorMessage(TipInfoMessage.newBuilder().setId(code).addParameters(text)).build();
    }

    @Test
    void 结果行的观战字段_战斗号按无符号十进制_两个回合数字段同值_布尔写成0和1() {
        assertThat(BattleSmokeChecks.spectateFields(BIG, 14, true, false))
                .isEqualTo("spectate_battle_id=9223372036854775809 b_spectate_turns=14 b_direct_spectate_turns=14 removed_ok=1 s12_ready_residue=0");
        assertThat(BattleSmokeChecks.spectateFields(0, 0, false, true))
                .isEqualTo("spectate_battle_id=0 b_spectate_turns=0 b_direct_spectate_turns=0 removed_ok=0 s12_ready_residue=1");
    }
}
