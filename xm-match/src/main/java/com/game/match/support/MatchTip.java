package com.game.match.support;

import com.game.proto.TipInfoMessage;

/**
 * xm-match 回给客户端的每一种 in-band tip：码 + {@code parameters[0]} 的中文串（match-spec §2.2、§4.1、§6.1、§8.2）。
 *
 * <p><b>客户端契约，逐字节照搬基线</b>（{@code tipErr}，{@code join.go:18-21}：{@code TipInfoMessage{id, parameters = [文案]}}）：客户端按 id 查表内文案，
 * {@code parameters[0]} 另带服务端写死的中文说明。<b>文案里的逗号是半角</b>（基线源码如此），不要「顺手」改成全角；{@code MatchTipsTest} 逐字节钉住。
 * 同一个码在不同发生点文案不同（16004 有三种、16010 有两种、16012 有两种），所以按「发生点」列常量，不按码列。
 *
 * <p>码一律取自 {@link MatchTips}（其值来自导表生成的枚举），不手写数字。永不发出的码（16005 / 16006）与 6.5 的观战码（16014–16019）不在这里。
 */
public enum MatchTip {

    // ---- 16004 kMatchInternal：身份缺失、依赖故障、过载、邀请发送失败 ----

    /** 会话没有绑定玩家（{@code SessionContext.player_id = 0}）：157 / 152 / 151 / 179 的第 1 行。 */
    NO_IDENTITY(MatchTips.INTERNAL, "缺少玩家身份"),
    /** 依赖故障（读战斗锁 / 票据 / 位置 / 在线目录 / 落点出错、发号失败、建票出错）与工作池过载：157 / 152 / 151 / 179 共用。 */
    BUSY(MatchTips.INTERNAL, "服务器繁忙,请稍后再试"),
    /** 152 第 10 行：156 没推到（目标刚好下线、gate 不可达、推送异常）；记录与占坑已清理。 */
    CHALLENGE_INVITE_PUSH_FAILED(MatchTips.INTERNAL, "邀请发送失败,请稍后再试"),

    // ---- 157 JoinQueue ----

    /** 第 4 行：战斗锁存在。 */
    JOIN_IN_BATTLE(MatchTips.IN_BATTLE, "战斗尚未结束,无法排队"),
    /** 第 6、10 行：已有在途票据（应答另带现有的 queue_ticket）。 */
    JOIN_ALREADY_QUEUED(MatchTips.ALREADY_QUEUED, "已在匹配队列中"),
    /** 第 2b 行：3V3 / PVP_CHALLENGE / UNSPECIFIED / 契约里没有的值。 */
    JOIN_MODE_NOT_OPEN(MatchTips.MODE_NOT_OPEN, "该匹配模式未开放"),
    /** 第 2a 行：PVE_TEAM 且该副本没配组队人数。 */
    JOIN_TEAM_SIZE_NOT_CONFIGURED(MatchTips.TEAM_SIZE_NOT_CONFIGURED, "该副本未开放组队"),
    /** 第 8 行：没有在线的位置记录。 */
    JOIN_NOT_IN_SCENE(MatchTips.NOT_IN_SCENE, "请先进入场景"),

    // ---- 152 ChallengePlayer ----

    /** 第 2 行：目标为 0 或是自己。 */
    CHALLENGE_SELF(MatchTips.CHALLENGE_SELF, "不能挑战自己"),
    /** 第 3 行：发起者有战斗锁（读失败也算有）。 */
    CHALLENGE_SELF_BUSY(MatchTips.CHALLENGE_SELF_BUSY, "战斗尚未结束,无法发起切磋"),
    /** 第 4 行：目标有战斗锁（读失败也算有）。 */
    CHALLENGE_TARGET_BUSY(MatchTips.CHALLENGE_TARGET_BUSY, "对方正在战斗中"),
    /** 第 6 行：目标不在线。 */
    CHALLENGE_TARGET_OFFLINE(MatchTips.CHALLENGE_TARGET_OFFLINE, "对方不在线"),
    /** 第 8 行：目标已有待应答的挑战。 */
    CHALLENGE_PENDING(MatchTips.CHALLENGE_PENDING, "对方已有待处理的切磋邀请"),

    // ---- 151 RespondChallenge ----

    /** 第 3、6 行：记录不存在 / 已过期；并发的两条接受里后到的那条也回它。 */
    CHALLENGE_EXPIRED(MatchTips.CHALLENGE_EXPIRED, "切磋邀请已过期"),
    /** 第 4 行：应答者不是目标（不消费记录）。 */
    CHALLENGE_NOT_TARGET(MatchTips.CHALLENGE_NOT_TARGET, "该邀请不是发给你的"),
    /** 第 8 行：接受时发起者有战斗锁（或读失败）。码是 16012，文案与「已过期」不同。 */
    CHALLENGE_CHALLENGER_BUSY(MatchTips.CHALLENGE_EXPIRED, "发起者已进入其它战斗"),
    /** 第 9 行：接受时应答者有战斗锁（或读失败）。码是 16010，文案与发起时的不同。 */
    CHALLENGE_RESPONDER_BUSY(MatchTips.CHALLENGE_SELF_BUSY, "战斗尚未结束,无法应战"),

    // ---- 179 RequestBattleTicket ----

    /** 落点记录不存在；或直拨建连失败且目录里同号节点已换实例。<b>1005 只用于「这局确实没了」</b>：客户端据此永久放弃本局。 */
    REISSUE_BATTLE_GONE(MatchTips.INVALID_PARAMETER, "该战斗不存在或已结束"),
    /** 其余传输失败（超时、连上后断开、同实例、目录缺席或读失败）：客户端退避后再补签。 */
    REISSUE_BATTLE_UNAVAILABLE(MatchTips.SERVICE_UNAVAILABLE, "战斗服务暂不可用"),

    // ---- 163 WatchBattle（6.4 临时应答，M22；6.5 换成真语义） ----

    /** 「该功能当前不可用」：只有 id、<b>不带 parameters</b>（Java 对未做的号的统一形状）。 */
    FEATURE_UNAVAILABLE(MatchTips.FEATURE_UNAVAILABLE, null);

    private final int code;
    private final String text;
    private final TipInfoMessage proto;

    MatchTip(int code, String text) {
        this.code = code;
        this.text = text;
        TipInfoMessage.Builder tip = TipInfoMessage.newBuilder().setId(code);
        if (text != null) {
            tip.addParameters(text);
        }
        this.proto = tip.build();
    }

    /** tip 码（{@code TipInfoMessage.id}；157 的 {@code error_code} 与它同值）。 */
    public int code() {
        return code;
    }

    /** {@code parameters[0]} 的中文串；{@link #FEATURE_UNAVAILABLE} 为 null（不带 parameters）。 */
    public String text() {
        return text;
    }

    /** 写进应答 {@code error_message} 的消息（不可变，可共享）。 */
    public TipInfoMessage proto() {
        return proto;
    }
}
