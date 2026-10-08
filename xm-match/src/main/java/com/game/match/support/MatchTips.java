package com.game.match.support;

import com.game.proto.RequestBattleTicketResponse;
import com.game.proto.match.ChallengePlayerResponse;
import com.game.proto.match.JoinQueueResponse;
import com.game.proto.match.RespondChallengeResponse;
import com.game.proto.match.WatchBattleResponse;
import com.game.table.CommonErrorTip;
import com.game.table.MatchErrorTip;

/**
 * 匹配的 tip 码与「按 tip 组装各应答体」的小工厂（match-spec §8.1、§8.2；基线 {@code go/match/internal/constants/errors.go}）。
 *
 * <p>码值只取自导表生成的 {@code MatchErrorTip.match_error} / {@code CommonErrorTip.common_error}，不手写数字。每个发生点的码与中文串在 {@link MatchTip}。
 * 三层应答（{@code ClientMessageService} 的契约）在匹配里的落点：
 * <ul>
 *   <li><b>in-band</b>（应答体自己的 {@code error_message}）：157 / 152 / 151 / 179 / 163 的业务拒绝、依赖故障与过载——用下面的工厂；</li>
 *   <li><b>信封</b>（{@code ClientReply.tip_id} = {@link #SERVICE_UNAVAILABLE}，不带 parameters）：未知号、请求体解析失败、处理器异常；
 *       以及<b>没有 in-band 错误字段</b>的 148 / 153 的依赖故障与过载；</li>
 *   <li>148 成功、156 / 154 上行：应答类型是 Empty、{@code tip_id = 0}，gate 不回包。</li>
 * </ul>
 * 成功应答体里<b>绝不能</b>出现 {@code error_message}（客户端只看应答体）：157 成功是 {@code error_code = 0} 加新票号，不设 {@code error_message}。
 */
public final class MatchTips {

    // ---- match 段（match_error_tip.proto；16005 kMatchTicketMismatch / 16006 kMatchCancelTooLate 基线从不发出，Java 同样不发，不列） ----

    public static final int IN_BATTLE = MatchErrorTip.match_error.kMatchInBattle_VALUE;
    public static final int ALREADY_QUEUED = MatchErrorTip.match_error.kMatchAlreadyQueued_VALUE;
    public static final int MODE_NOT_OPEN = MatchErrorTip.match_error.kMatchModeNotOpen_VALUE;
    public static final int TEAM_SIZE_NOT_CONFIGURED = MatchErrorTip.match_error.kMatchTeamSizeNotConfigured_VALUE;
    /** 身份缺失、依赖故障、过载、邀请发送失败、179 读记录失败——匹配段唯一的「故障」码。 */
    public static final int INTERNAL = MatchErrorTip.match_error.kMatchInternal_VALUE;
    public static final int CHALLENGE_SELF = MatchErrorTip.match_error.kMatchChallengeSelf_VALUE;
    public static final int CHALLENGE_TARGET_OFFLINE = MatchErrorTip.match_error.kMatchChallengeTargetOffline_VALUE;
    public static final int CHALLENGE_TARGET_BUSY = MatchErrorTip.match_error.kMatchChallengeTargetBusy_VALUE;
    public static final int CHALLENGE_SELF_BUSY = MatchErrorTip.match_error.kMatchChallengeSelfBusy_VALUE;
    public static final int CHALLENGE_PENDING = MatchErrorTip.match_error.kMatchChallengePending_VALUE;
    public static final int CHALLENGE_EXPIRED = MatchErrorTip.match_error.kMatchChallengeExpired_VALUE;
    public static final int CHALLENGE_NOT_TARGET = MatchErrorTip.match_error.kMatchChallengeNotTarget_VALUE;
    public static final int NOT_IN_SCENE = MatchErrorTip.match_error.kMatchNotInScene_VALUE;

    // ---- match 段的观战码（163；spectate-spec §3.1、§3.2。每个码的发生点与文案见 MatchTip 的 WATCH_* 常量） ----

    /** 16014：持票（任意状态）、抢标记时发现有票、登记成功后的复查命中票据或战斗锁。 */
    public static final int SPECTATE_WHILE_QUEUED = MatchErrorTip.match_error.kMatchSpectateWhileQueued_VALUE;
    /** 16015：入口检查时有战斗锁。 */
    public static final int SPECTATE_WHILE_IN_BATTLE = MatchErrorTip.match_error.kMatchSpectateWhileInBattle_VALUE;
    /** 16016：抢观战标记时被同一玩家的另一条并发 163 占着（唯一出口）。 */
    public static final int ALREADY_WATCHING = MatchErrorTip.match_error.kMatchAlreadyWatching_VALUE;
    /** 16017：随机观战没有可看的场。 */
    public static final int NO_WATCHABLE_BATTLE = MatchErrorTip.match_error.kMatchNoWatchableBattle_VALUE;
    /** 16018：「该战斗不存在或已结束」与「该战斗当前无法观战」两种文案共用这个码。 */
    public static final int BATTLE_NOT_WATCHABLE = MatchErrorTip.match_error.kMatchBattleNotWatchable_VALUE;
    /** 16019：在线目录里没有条目。 */
    public static final int SPECTATE_OFFLINE = MatchErrorTip.match_error.kMatchSpectateOffline_VALUE;

    // ---- 通用段 ----

    /** 信封的故障码；也是 179「战斗服务暂不可用」的码。 */
    public static final int SERVICE_UNAVAILABLE = CommonErrorTip.common_error.kServiceUnavailable_VALUE;
    /** 179「该战斗不存在或已结束」的码（battle 的同义裁决也原样透传它）。 */
    public static final int INVALID_PARAMETER = CommonErrorTip.common_error.kInvalidParameter_VALUE;
    /**
     * battle 在 {@code addObserver} 里回的「房间不存在」（1004）：163 判「这一场已收尾 / 还没建好」的<b>唯一</b>信号
     * （spectate-spec §7.1 第 1 条）；battle 的其它拒绝码（1005 是参战者 / 参数、1008 观众已满、1003 签不出票）一律按「当前无法观战」。
     * 只用来<b>解读 battle 的应答</b>，xm-match 自己不把它发给客户端。
     */
    public static final int BATTLE_ROOM_NOT_FOUND = CommonErrorTip.common_error.kEntityIsNull_VALUE;

    private MatchTips() {
    }

    // ---------------------------------------------------------------- 157 JoinQueue

    /** 157 被拒：{@code error_code} 与 {@code error_message.id} 同值，{@code queue_ticket} 为空。 */
    public static JoinQueueResponse joinRejected(MatchTip tip) {
        return JoinQueueResponse.newBuilder().setErrorCode(tip.code()).setErrorMessage(tip.proto()).build();
    }

    /**
     * 157 回 16001「已在匹配队列中」：带<b>现有</b>票据的 id（并发重复建票时是赢家的 id；读不到时传空串或 null，字段留空）。
     */
    public static JoinQueueResponse joinAlreadyQueued(String existingTicket) {
        JoinQueueResponse.Builder response = JoinQueueResponse.newBuilder()
                .setErrorCode(MatchTip.JOIN_ALREADY_QUEUED.code()).setErrorMessage(MatchTip.JOIN_ALREADY_QUEUED.proto());
        if (existingTicket != null && !existingTicket.isEmpty()) {
            response.setQueueTicket(existingTicket);
        }
        return response.build();
    }

    /** 157 受理：{@code error_code = 0}、新票号、<b>不带</b> {@code error_message}。 */
    public static JoinQueueResponse joinAccepted(String queueTicket) {
        if (queueTicket == null || queueTicket.isEmpty()) {
            throw new IllegalArgumentException("受理的排队必须带票号");
        }
        return JoinQueueResponse.newBuilder().setQueueTicket(queueTicket).build();
    }

    // ---------------------------------------------------------------- 152 / 151 切磋

    /** 152 被拒：只有 {@code error_message}，{@code challenge_id} 为 0。 */
    public static ChallengePlayerResponse challengeRejected(MatchTip tip) {
        return ChallengePlayerResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    /** 152 成功：只有 {@code challenge_id}。 */
    public static ChallengePlayerResponse challengeSent(long challengeId) {
        if (challengeId == 0) {
            throw new IllegalArgumentException("challenge_id 不能为 0");
        }
        return ChallengePlayerResponse.newBuilder().setChallengeId(challengeId).build();
    }

    /** 151 被拒。成功（拒绝邀请、或接受并已开始开局）的应答是空消息 {@code RespondChallengeResponse.getDefaultInstance()}。 */
    public static RespondChallengeResponse respondRejected(MatchTip tip) {
        return RespondChallengeResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    // ---------------------------------------------------------------- 179 补签

    /** 179 由 match 自己拒绝（身份缺失、读记录失败、记录不存在、调不通）：只有 {@code error_message}。battle 的裁决是原样透传，不经这里。 */
    public static RequestBattleTicketResponse reissueRejected(MatchTip tip) {
        return RequestBattleTicketResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    // ---------------------------------------------------------------- 163 观战

    /**
     * 163 被拒（业务拒绝、依赖故障、过载、预算不足都走它）：只有 {@code error_message}，{@code battle_id} 为 0
     * （基线 {@code tipErr}；spectate-spec §3.1 表头）。{@code tip} 取 {@code MatchTip.WATCH_*}，或 16004 的 {@link MatchTip#NO_IDENTITY} / {@link MatchTip#BUSY}。
     */
    public static WatchBattleResponse watchRejected(MatchTip tip) {
        return WatchBattleResponse.newBuilder().setErrorMessage(tip.proto()).build();
    }

    /** 163 成功：只有 {@code battle_id}（随机模式回填实际挑中的那一场），<b>不带</b> {@code error_message}。 */
    public static WatchBattleResponse watchAccepted(long battleId) {
        if (battleId == 0) {
            throw new IllegalArgumentException("观战成功的应答必须带 battle_id");
        }
        return WatchBattleResponse.newBuilder().setBattleId(battleId).build();
    }
}
