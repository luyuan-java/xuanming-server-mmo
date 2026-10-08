package com.game.robot.scenario;

import com.game.proto.BattleActorState;
import com.game.proto.BattleStateS2C;
import com.game.proto.TipInfoMessage;
import com.game.proto.eBattleOutcome;
import com.game.proto.match.ChallengeInviteS2C;
import com.game.proto.match.ChallengeResultS2C;
import com.game.proto.match.GetQueueStatusResponse;
import com.game.proto.match.JoinQueueResponse;
import com.game.robot.client.MatchAdminClient.Rating;
import com.game.table.CommonErrorTip;
import com.game.table.MatchErrorTip;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/**
 * 匹配各场景（battle-smoke / match-activity / match-5v5 / team 的开战段）的纯判据（match-spec §15.5）：不碰网络，输入是已解析的应答 / 推送，
 * 输出是问题描述（没问题返回 null）。判据集中在这里便于单测；场景只负责收发。
 *
 * <p><b>文案是客户端契约</b>：{@code parameters[0]} 的中文串逐字节照搬 match-spec §2.2 / §4.1 / §6.1（基线 Go 源码写死的串，<b>逗号是半角</b>）。
 * robot 是纯客户端、不依赖 xm-match，只能各写一份；{@code MatchTextsTest} 读 xm-match 的源码把两边钉成一样。
 */
final class BattleSmokeChecks {

    // ---- tip 码（导表生成的枚举，不手写数字） ----
    static final int TIP_IN_BATTLE = MatchErrorTip.match_error.kMatchInBattle_VALUE;
    static final int TIP_ALREADY_QUEUED = MatchErrorTip.match_error.kMatchAlreadyQueued_VALUE;
    static final int TIP_MODE_NOT_OPEN = MatchErrorTip.match_error.kMatchModeNotOpen_VALUE;
    static final int TIP_TEAM_SIZE_NOT_CONFIGURED = MatchErrorTip.match_error.kMatchTeamSizeNotConfigured_VALUE;
    static final int TIP_CHALLENGE_SELF = MatchErrorTip.match_error.kMatchChallengeSelf_VALUE;
    static final int TIP_CHALLENGE_TARGET_OFFLINE = MatchErrorTip.match_error.kMatchChallengeTargetOffline_VALUE;
    static final int TIP_CHALLENGE_TARGET_BUSY = MatchErrorTip.match_error.kMatchChallengeTargetBusy_VALUE;
    static final int TIP_CHALLENGE_SELF_BUSY = MatchErrorTip.match_error.kMatchChallengeSelfBusy_VALUE;
    static final int TIP_CHALLENGE_PENDING = MatchErrorTip.match_error.kMatchChallengePending_VALUE;
    static final int TIP_CHALLENGE_EXPIRED = MatchErrorTip.match_error.kMatchChallengeExpired_VALUE;
    static final int TIP_CHALLENGE_NOT_TARGET = MatchErrorTip.match_error.kMatchChallengeNotTarget_VALUE;
    static final int TIP_INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    static final int TIP_FEATURE_UNAVAILABLE = CommonErrorTip.common_error.kFeatureUnavailable_VALUE;

    // ---- parameters[0]（逐字节；半角逗号） ----
    static final String TEXT_IN_BATTLE = "战斗尚未结束,无法排队";
    static final String TEXT_ALREADY_QUEUED = "已在匹配队列中";
    static final String TEXT_MODE_NOT_OPEN = "该匹配模式未开放";
    static final String TEXT_TEAM_SIZE_NOT_CONFIGURED = "该副本未开放组队";
    static final String TEXT_CHALLENGE_SELF = "不能挑战自己";
    static final String TEXT_CHALLENGE_TARGET_OFFLINE = "对方不在线";
    static final String TEXT_CHALLENGE_TARGET_BUSY = "对方正在战斗中";
    static final String TEXT_CHALLENGE_SELF_BUSY = "战斗尚未结束,无法发起切磋";
    static final String TEXT_CHALLENGE_PENDING = "对方已有待处理的切磋邀请";
    static final String TEXT_CHALLENGE_EXPIRED = "切磋邀请已过期";
    static final String TEXT_CHALLENGE_NOT_TARGET = "该邀请不是发给你的";
    static final String TEXT_BATTLE_GONE = "该战斗不存在或已结束";

    /** 排队票号的形状（§15.5 第 3 步；服务端发的是 UUIDv4 小写带连字符）。 */
    static final Pattern QUEUE_TICKET = Pattern.compile("[0-9a-f-]{36}");
    /** 一局的最长时长：177 的 {@code expire_at_ms} = gather 起点 + 300 s（§8.4）。 */
    static final long BATTLE_DURATION_MS = 300_000;
    /** 切磋邀请的有效期：156 的 {@code expires_at_ms} = 发起时刻 + 60 s（§8.4）。 */
    static final long CHALLENGE_TTL_MS = 60_000;
    /** 核对服务端时刻的容差（robot 与切片同机，时钟一致；留给 gather 耗时与调度抖动，§15.5 第 4 步）。 */
    static final long CLOCK_SLACK_MS = 5_000;
    /** 回合打满按平局的阈值（§5.5：对所有 config 恒为 30）。 */
    static final int RATING_DRAW_ROUND_CAP = 30;
    /** K = 32：两边赛前同分时胜负局的 |Δ| = 16.00（centi）。 */
    static final long EVEN_DELTA_CENTI = 1_600;
    /** K 本身（centi）：赛前不同分时 |Δ| 的上界。 */
    static final long MAX_DELTA_CENTI = 3_200;
    /** 5V5 评分相同时按弹出序的蛇形分队（§2.9）。 */
    static final List<Integer> SNAKE_5V5 = List.of(0, 1, 1, 0, 0, 1, 1, 0, 0, 1);

    private BattleSmokeChecks() {
    }

    // ---------------------------------------------------------------- 排队

    static boolean isQueueTicket(String ticket) {
        return QUEUE_TICKET.matcher(ticket).matches();
    }

    /** 157 受理：{@code error_code = 0} 且不带 {@code error_message}（§2.2 末行；空的 error_message 也算带了）。 */
    static boolean accepted(JoinQueueResponse response) {
        return response.getErrorCode() == 0 && !response.hasErrorMessage();
    }

    /**
     * 157 的受理应答：受理、票号是 36 位的小写 UUID 形状。
     *
     * @return null = 没问题
     */
    static String joinAcceptedProblem(JoinQueueResponse response) {
        if (response.getErrorCode() != 0 || response.getErrorMessage().getId() != 0) {
            return "期望受理，实得 " + describe(response);
        }
        if (response.hasErrorMessage()) {
            return "受理的应答不得带 error_message 字段（哪怕 id = 0）：" + describe(response);
        }
        if (!isQueueTicket(response.getQueueTicket())) {
            return "queue_ticket 应是 36 位小写 UUID（[0-9a-f-]{36}），实得「" + response.getQueueTicket() + "」";
        }
        return null;
    }

    /**
     * 157 的拒绝应答：{@code error_code == error_message.id == code}、{@code parameters} 恰好一项且逐字节等于 {@code text}、
     * {@code queue_ticket} 等于 {@code ticket}（16001 带现有票号，其余为空）。
     *
     * @return null = 没问题
     */
    static String joinRejectProblem(JoinQueueResponse response, int code, String text, String ticket) {
        List<String> problems = new ArrayList<>();
        if (response.getErrorCode() != code) {
            problems.add("error_code=" + response.getErrorCode() + "（期望 " + code + "）");
        }
        if (response.getErrorMessage().getId() != response.getErrorCode()) {
            problems.add("error_message.id=" + response.getErrorMessage().getId() + " 与 error_code=" + response.getErrorCode() + " 不同值");
        }
        String params = parametersProblem(response.getErrorMessage(), text);
        if (params != null) {
            problems.add(params);
        }
        if (!response.getQueueTicket().equals(ticket)) {
            problems.add("queue_ticket=「" + response.getQueueTicket() + "」（期望「" + ticket + "」）");
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    /**
     * 一条 in-band tip：id 等于 {@code code}，{@code parameters} 恰好一项且逐字节等于 {@code text}；{@code text} 为 null 时只核对 id
     * （battle 透传的码不带文案）。
     *
     * @return null = 没问题
     */
    static String tipProblem(TipInfoMessage tip, int code, String text) {
        if (tip.getId() != code) {
            return "tip=" + tip.getId() + "（期望 " + code + "）" + (tip.getParametersCount() > 0 ? " parameters=" + tip.getParametersList() : "");
        }
        return text == null ? null : parametersProblem(tip, text);
    }

    private static String parametersProblem(TipInfoMessage tip, String text) {
        if (tip.getParametersCount() != 1 || !tip.getParameters(0).equals(text)) {
            return "parameters=" + tip.getParametersList() + "（期望恰好一项「" + text + "」，逐字节）";
        }
        return null;
    }

    static String describe(JoinQueueResponse response) {
        return "error_code=" + response.getErrorCode() + " " + describe(response.getErrorMessage())
                + (response.getQueueTicket().isEmpty() ? "" : " queue_ticket=" + response.getQueueTicket());
    }

    static String describe(TipInfoMessage tip) {
        return "tip=" + tip.getId() + (tip.getParametersCount() > 0 ? " parameters=" + tip.getParametersList() : "");
    }

    static String describe(GetQueueStatusResponse status) {
        return "state=" + status.getState() + " estimated_wait_seconds=" + status.getEstimatedWaitSeconds() + " queued_seconds="
                + status.getQueuedSeconds();
    }

    // ---------------------------------------------------------------- 时刻

    /**
     * 服务端按「某个起点 + {@code ttlMs}」算出的时刻（177 的 {@code expire_at_ms}、156 的 {@code expires_at_ms}）落在
     * {@code [发请求的时刻 + ttl − 容差, 收到推送的时刻 + ttl + 容差]} 之内：起点一定在发请求之后、推送到达之前。
     *
     * @return null = 没问题
     */
    static String deadlineProblem(long serverDeadlineMs, long sentAtMs, long receivedAtMs, long ttlMs) {
        long low = sentAtMs + ttlMs - CLOCK_SLACK_MS;
        long high = receivedAtMs + ttlMs + CLOCK_SLACK_MS;
        if (serverDeadlineMs < low || serverDeadlineMs > high) {
            return "时刻 " + serverDeadlineMs + " 不在 [发起 + " + ttlMs / 1000 + " s − 5 s, 收到 + " + ttlMs / 1000 + " s + 5 s] = [" + low + ", " + high
                    + "] 内（相对发起 " + (serverDeadlineMs - sentAtMs) + " ms；robot 与服务端不同机时先对时钟）";
        }
        return null;
    }

    // ---------------------------------------------------------------- 分队

    /** 143 / 140 的状态里某个玩家所在的队；不在 actors 里为空。 */
    static OptionalInt teamOf(BattleStateS2C state, long playerId) {
        for (BattleActorState actor : state.getActorsList()) {
            if (actor.getActorId() == playerId) {
                return OptionalInt.of(actor.getTeamIndex());
            }
        }
        return OptionalInt.empty();
    }

    /**
     * 各玩家的队号与期望一致（1V1 / 切磋：先受理的锚点或发起者在 0 队、另一位在 1 队；5V5：{@link #SNAKE_5V5}）。
     *
     * @param players  按入队 / 名单次序
     * @param expected 与 {@code players} 同下标的期望队号
     * @return null = 没问题
     */
    static String sidesProblem(BattleStateS2C state, List<Long> players, List<Integer> expected) {
        if (players.size() != expected.size()) {
            throw new IllegalArgumentException("玩家数 " + players.size() + " 与期望队号数 " + expected.size() + " 不一致");
        }
        List<String> actual = new ArrayList<>();
        boolean same = true;
        for (int i = 0; i < players.size(); i++) {
            OptionalInt team = teamOf(state, players.get(i));
            actual.add(team.isPresent() ? Integer.toString(team.getAsInt()) : "缺席");
            same &= team.isPresent() && team.getAsInt() == expected.get(i);
        }
        return same ? null : "按次序的队号是 " + actual + "，期望 " + expected;
    }

    // ---------------------------------------------------------------- 评分

    /**
     * 两边赛前同分时 0 队每人的评分增量（centi，§5.1）：SIDE_A_WIN 且不满 {@value #RATING_DRAW_ROUND_CAP} 回合 → +16.00；SIDE_B_WIN 且不满 → −16.00；
     * 平局、或回合数 ≥ {@value #RATING_DRAW_ROUND_CAP}（打满按平局）→ 0。1 队每人是它的相反数。
     *
     * @throws IllegalArgumentException 终局不是胜 / 负 / 平之一（服务端对这种结果不计分，调用方先用 {@link #rated} 判）
     */
    static long evenDeltaCenti(eBattleOutcome outcome, int totalRounds) {
        if (!rated(outcome)) {
            throw new IllegalArgumentException("不计分的终局：" + outcome);
        }
        if (outcome == eBattleOutcome.BATTLE_OUTCOME_DRAW || totalRounds >= RATING_DRAW_ROUND_CAP) {
            return 0;
        }
        return outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN ? EVEN_DELTA_CENTI : -EVEN_DELTA_CENTI;
    }

    /** 这种终局会不会入账（只认 0 队胜 / 1 队胜 / 平局）。 */
    static boolean rated(eBattleOutcome outcome) {
        return outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_A_WIN || outcome == eBattleOutcome.BATTLE_OUTCOME_SIDE_B_WIN
                || outcome == eBattleOutcome.BATTLE_OUTCOME_DRAW;
    }

    /**
     * 一局计分对局（1V1 / 5V5）打完之后的评分判据（§15.5 第 8 步、§5.1）：
     * <ul>
     *   <li>每人 {@code games} 恰好 + 1；</li>
     *   <li>全员赛前同分（新号都是 1500.00）：0 队每人的 Δ 恰好是 {@link #evenDeltaCenti}，1 队每人是它的相反数；</li>
     *   <li>赛前不同分（复用了旧账号）：只核对方向与上界——同队同增量、两队互为相反数（各自四舍五入，容 0.01）、胜方为正且 |Δ| ≤ 32；
     *       打满 / 平局时 |Δ| ≤ 16。评分已触下限 0 的人不参与比较。</li>
     * </ul>
     *
     * @param teams 与 {@code before} / {@code after} 同下标的队号（0 / 1）
     * @return null = 没问题
     */
    static String ratingProblem(List<Rating> before, List<Rating> after, List<Integer> teams, eBattleOutcome outcome, int totalRounds) {
        if (before.size() != after.size() || before.size() != teams.size() || before.isEmpty()) {
            throw new IllegalArgumentException("赛前 / 赛后 / 队号三份名单必须等长且非空");
        }
        if (!rated(outcome)) {
            return "终局 " + outcome + " 不是胜 / 负 / 平，服务端不会入账（150 的 outcome 不该是它）";
        }
        List<String> problems = new ArrayList<>();
        boolean even = before.stream().allMatch(r -> r.ratingCenti() == before.get(0).ratingCenti());
        long expected = evenDeltaCenti(outcome, totalRounds);
        boolean drawn = expected == 0;
        Long team0Delta = null;
        for (int i = 0; i < before.size(); i++) {
            Rating b = before.get(i);
            Rating a = after.get(i);
            int team = teams.get(i);
            if (team != 0 && team != 1) {
                throw new IllegalArgumentException("队号只能是 0 / 1：" + team);
            }
            String who = "玩家 " + Long.toUnsignedString(b.playerId()) + "（" + team + " 队）";
            if (a.playerId() != b.playerId()) {
                throw new IllegalArgumentException("赛前 / 赛后名单的玩家次序不一致");
            }
            if (a.games() != b.games() + 1) {
                problems.add(who + " games " + b.games() + " → " + a.games() + "（期望 + 1）");
            }
            long delta = a.ratingCenti() - b.ratingCenti();
            long asTeam0 = team == 0 ? delta : -delta;
            if (even) {
                if (asTeam0 != expected) {
                    problems.add(who + " 评分 " + b.ratingText() + " → " + a.ratingText() + "，Δ = " + centi(delta) + "（期望 "
                            + centi(team == 0 ? expected : -expected) + "）");
                }
                continue;
            }
            if (a.ratingCenti() == 0) {
                continue;
            }
            if (team0Delta == null) {
                team0Delta = asTeam0;
            } else if (Math.abs(asTeam0 - team0Delta) > 1) {
                problems.add(who + " 的 Δ = " + centi(delta) + " 与别人的不成对（同队同增量、两队互为相反数；0 队口径 " + centi(team0Delta) + "）");
            }
            long bound = drawn ? EVEN_DELTA_CENTI : MAX_DELTA_CENTI;
            boolean wrongSign = !drawn && (expected > 0 ? asTeam0 <= 0 : asTeam0 >= 0);
            if (Math.abs(asTeam0) > bound || wrongSign) {
                problems.add(who + " 的 Δ = " + centi(delta) + " 方向或幅度不对（" + (drawn ? "平局 / 打满：|Δ| ≤ 16" : "胜方为正、负方为负，|Δ| ≤ 32") + "）");
            }
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    /** centi → 带符号的两位小数（1600 → {@code +16.00}，0 → {@code 0.00}）。 */
    static String centi(long deltaCenti) {
        long abs = Math.abs(deltaCenti);
        String text = abs / 100 + "." + (abs % 100 < 10 ? "0" : "") + abs % 100;
        return deltaCenti > 0 ? "+" + text : deltaCenti < 0 ? "-" + text : text;
    }

    // ---------------------------------------------------------------- 切磋

    /**
     * 156 邀请推送：{@code challenge_id}、发起者、发起者的<b>账号名</b>（§6.1：一期用账号名占位）、{@code battle_config_id} 都对上，
     * {@code expires_at_ms} ≈ 发起时刻 + 60 s。
     *
     * @return null = 没问题
     */
    static String inviteProblem(ChallengeInviteS2C invite, long challengeId, long challengerId, String challengerAccount, int battleConfigId,
                                long sentAtMs, long receivedAtMs) {
        List<String> problems = new ArrayList<>();
        if (invite.getChallengeId() != challengeId) {
            problems.add("challenge_id=" + Long.toUnsignedString(invite.getChallengeId()) + "（期望 " + Long.toUnsignedString(challengeId) + "）");
        }
        if (invite.getChallengerId() != challengerId) {
            problems.add("challenger_id=" + Long.toUnsignedString(invite.getChallengerId()) + "（期望 " + Long.toUnsignedString(challengerId) + "）");
        }
        if (!invite.getChallengerName().equals(challengerAccount)) {
            problems.add("challenger_name=「" + invite.getChallengerName() + "」（期望发起者的账号名「" + challengerAccount + "」）");
        }
        if (invite.getBattleConfigId() != battleConfigId) {
            problems.add("battle_config_id=" + invite.getBattleConfigId() + "（期望 " + battleConfigId + "）");
        }
        String expires = deadlineProblem(invite.getExpiresAtMs(), sentAtMs, receivedAtMs, CHALLENGE_TTL_MS);
        if (expires != null) {
            problems.add("expires_at_ms " + expires);
        }
        return problems.isEmpty() ? null : String.join("；", problems);
    }

    /**
     * 154 结果推送逐字段。
     *
     * @return null = 没问题
     */
    static String resultProblem(ChallengeResultS2C result, long challengeId, boolean accepted, long responderId) {
        if (result.getChallengeId() == challengeId && result.getAccepted() == accepted && result.getResponderId() == responderId) {
            return null;
        }
        return "154 {challenge_id=" + Long.toUnsignedString(result.getChallengeId()) + ", accepted=" + result.getAccepted() + ", responder_id="
                + Long.toUnsignedString(result.getResponderId()) + "}，期望 {" + Long.toUnsignedString(challengeId) + ", " + accepted + ", "
                + Long.toUnsignedString(responderId) + "}";
    }
}
