package com.game.match.support;

import com.game.proto.TipInfoMessage;
import java.util.Objects;

/**
 * xm-match 回给客户端的每一种 in-band tip：码 + {@code parameters[0]} 的中文串（match-spec §2.2、§4.1、§6.1、§8.2）。
 *
 * <p><b>客户端契约，逐字节照搬基线</b>（{@code tipErr}，{@code join.go:18-21}：{@code TipInfoMessage{id, parameters = [文案]}}）：客户端按 id 查表内文案，
 * {@code parameters[0]} 另带服务端写死的中文说明。<b>文案里的逗号是半角</b>（基线源码如此），不要「顺手」改成全角；{@code MatchTipsTest} 逐字节钉住。
 * 同一个码在不同发生点文案不同（16004 有三种、16010 有两种、16012 有两种、16018 有两种），所以按「发生点」列常量，不按码列。
 *
 * <p>码一律取自 {@link MatchTips}（其值来自导表生成的枚举），不手写数字。永不发出的码（16005 / 16006）不在这里。
 * 163 观战的六个码（16014–16019）与它的 9 条文案（其中两条 16004 与别的号共用）见文件末尾一段（spectate-spec §3.2）。
 */
public enum MatchTip {

    // ---- 16004 kMatchInternal：身份缺失、依赖故障、过载、邀请发送失败 ----

    /** 会话没有绑定玩家（{@code SessionContext.player_id = 0}）：157 / 152 / 151 / 179 / 163 的第 1 行。 */
    NO_IDENTITY(MatchTips.INTERNAL, "缺少玩家身份"),
    /** 依赖故障（读战斗锁 / 票据 / 位置 / 在线目录 / 落点 / 观战标记与索引出错、发号失败、建票出错）、过载（工作池满、163 的在途已满）与 163 的预算不足：157 / 152 / 151 / 179 / 163 共用。 */
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

    /** 落点记录不存在；或请求确定没送达、目录里同号节点已换实例、且对原地址的 TCP 建连探测明确连不上（三条都成立）。<b>1005 只用于「这局确实没了」</b>：客户端据此永久放弃本局。 */
    REISSUE_BATTLE_GONE(MatchTips.INVALID_PARAMETER, "该战斗不存在或已结束"),
    /** 其余传输失败（超时、连上后断开、同实例、目录缺席或读失败）：客户端退避后再补签。 */
    REISSUE_BATTLE_UNAVAILABLE(MatchTips.SERVICE_UNAVAILABLE, "战斗服务暂不可用"),

    // ---- 163 WatchBattle（批次 6.5；spectate-spec §3.1 的判定顺序、§3.2 的 9 条文案。身份缺失 / 依赖故障 / 过载两条 16004 复用上面的
    //      NO_IDENTITY 与 BUSY） ----

    /** 第 3、14 行与抢标记回「有票」：持票（任意状态，含开局后 60 s 的 ready 残留，BW1）；登记成功后的复查命中票据或战斗锁（含读锁出错，BW2）也回它。 */
    WATCH_QUEUED(MatchTips.SPECTATE_WHILE_QUEUED, "匹配中无法观战"),
    /** 第 5 行：入口检查时战斗锁存在。 */
    WATCH_IN_BATTLE(MatchTips.SPECTATE_WHILE_IN_BATTLE, "战斗尚未结束,无法观战"),
    /** 第 13 行：抢观战标记时标记已被同一玩家的另一条并发 163 占着。<b>只有这一个出口</b>——已有标记本身不拒绝（换场 / 重看，第 7 行）。 */
    WATCH_ALREADY(MatchTips.ALREADY_WATCHING, "已在观战另一场战斗"),
    /** 第 18 行：随机观战两轮都没成（索引为空、或挑到的都已收尾）。 */
    WATCH_NO_BATTLE(MatchTips.NO_WATCHABLE_BATTLE, "当前没有可观战的战斗"),
    /** 第 12、16 行：落点记录不存在；battle 回「房间不存在」（1004）或节点已判死。码与 {@link #WATCH_NOT_WATCHABLE} 相同（16018），文案不同。 */
    WATCH_NOT_FOUND(MatchTips.BATTLE_NOT_WATCHABLE, "该战斗不存在或已结束"),
    /** 第 17 行：battle 的其它拒绝（观众已满、是参战者、签不出票）与传输失败；随机模式也不换场（BW6）。 */
    WATCH_NOT_WATCHABLE(MatchTips.BATTLE_NOT_WATCHABLE, "该战斗当前无法观战"),
    /** 第 9 行：在线目录里没有这名玩家的条目（没进游戏、断线、离场）。 */
    WATCH_OFFLINE(MatchTips.SPECTATE_OFFLINE, "会话不在线,无法观战");

    private final int code;
    private final String text;
    private final TipInfoMessage proto;

    MatchTip(int code, String text) {
        this.code = code;
        this.text = Objects.requireNonNull(text, "text");
        this.proto = TipInfoMessage.newBuilder().setId(code).addParameters(text).build();
    }

    /** tip 码（{@code TipInfoMessage.id}；157 的 {@code error_code} 与它同值）。 */
    public int code() {
        return code;
    }

    /** {@code parameters[0]} 的中文串（非 null：xm-match 的每一条 in-band tip 都带这一条服务端说明）。 */
    public String text() {
        return text;
    }

    /** 写进应答 {@code error_message} 的消息（不可变，可共享）。 */
    public TipInfoMessage proto() {
        return proto;
    }
}
