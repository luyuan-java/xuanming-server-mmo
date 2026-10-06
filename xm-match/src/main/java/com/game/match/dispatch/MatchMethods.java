package com.game.match.dispatch;

import java.util.List;

/**
 * 契约里 {@code match.MatchService} 的服务名与 10 个方法名（{@code proto/match/match_service.proto:21-46}）。消息号一律经
 * {@code MessageIdRegistry.requireId(SERVICE, 方法名)} 取，不写死数字（号表随契约同步，Java 没有生成的消息号常量）；现在的号见各常量的注释。
 * 处理器的 {@link MatchMethodHandler#method()} 与指标的 {@code method} 标签都用这里的常量。
 */
public final class MatchMethods {

    /** 服务裸名（与 {@code message_id.txt} 的键前缀一致）。 */
    public static final String SERVICE = "MatchService";

    /** 157：排队。 */
    public static final String JOIN_QUEUE = "JoinQueue";
    /** 148：取消排队（应答 Empty：成功不回包）。 */
    public static final String CANCEL_QUEUE = "CancelQueue";
    /** 153：查排队状态。 */
    public static final String GET_QUEUE_STATUS = "GetQueueStatus";
    /** 152：发起切磋。 */
    public static final String CHALLENGE_PLAYER = "ChallengePlayer";
    /** 151：应答切磋。 */
    public static final String RESPOND_CHALLENGE = "RespondChallenge";
    /** 156：切磋邀请推送的号；作为上行是空操作（回 Empty）。 */
    public static final String NOTIFY_CHALLENGE_INVITE = "NotifyChallengeInvite";
    /** 154：切磋结果推送的号；作为上行是空操作（回 Empty）。 */
    public static final String NOTIFY_CHALLENGE_RESULT = "NotifyChallengeResult";
    /** 163：观战（6.4 临时回 in-band 1006，6.5 接真语义）。 */
    public static final String WATCH_BATTLE = "WatchBattle";
    /** 164：可观战列表（6.4 临时回空列表）。 */
    public static final String LIST_WATCHABLE_BATTLES = "ListWatchableBattles";
    /** 179：战斗票据补签（请求 / 应答消息在 battle 包）。 */
    public static final String REQUEST_BATTLE_TICKET = "RequestBattleTicket";

    /** 全部 10 个方法（顺序无意义）。契约里的 {@code MatchService} 多出或少了方法时，{@code MatchMethodsTest} 会失败。 */
    public static final List<String> ALL = List.of(JOIN_QUEUE, CANCEL_QUEUE, GET_QUEUE_STATUS, CHALLENGE_PLAYER, RESPOND_CHALLENGE,
            NOTIFY_CHALLENGE_INVITE, NOTIFY_CHALLENGE_RESULT, WATCH_BATTLE, LIST_WATCHABLE_BATTLES, REQUEST_BATTLE_TICKET);

    private MatchMethods() {
    }
}
