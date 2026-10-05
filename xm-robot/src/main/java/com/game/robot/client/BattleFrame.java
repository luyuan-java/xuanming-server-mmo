package com.game.robot.client;

import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;
import java.util.Objects;

/**
 * battle 直连上按到达顺序记下的一项（battle-node-spec §3.2）：握手应答 {@code BattleTokenVerifyResponse}、{@code MessageContent}（应答 / 信封错误 / 推送
 * 三种形状），或者连接关闭标记（区分对端 FIN、RST、本端关闭、非法下行帧）。关闭标记也进同一本账，所以「某帧之后紧跟着 FIN」「这段时间只收到了
 * FIN」都能按序号断言。不可变。
 *
 * @param index    本连接上的到达序号（从 0 起）
 * @param atNanos  到达时刻（{@link System#nanoTime()}，只用于算间隔）
 * @param verify   握手应答；不是这一种时为 null
 * @param content  信封；不是这一种时为 null
 * @param closedBy 关闭标记的原因（{@value #FIN} / {@value #RESET} / {@value #LOCAL} / {@code invalid-frame:…} / {@code error:…}）；不是关闭标记时为 null
 */
public record BattleFrame(int index, long atNanos, BattleTokenVerifyResponse verify, MessageContent content, String closedBy) {

    /** 对端正常关闭（收到 FIN：服务端排空输出后 {@code shutdownOutput}，battle-node-spec §3.6）。 */
    public static final String FIN = "fin";
    /** 对端复位（RST：强关时输出缓冲里还有没发出去的数据，或对端进程直接退出）。 */
    public static final String RESET = "reset";
    /** 本端主动关闭。 */
    public static final String LOCAL = "local";
    /** 连接不活跃但没看到 FIN / RST 的其它情形。 */
    public static final String CLOSED = "closed";

    public BattleFrame {
        int kinds = (verify != null ? 1 : 0) + (content != null ? 1 : 0) + (closedBy != null ? 1 : 0);
        if (kinds != 1) {
            throw new IllegalArgumentException("BattleFrame 必须恰好是握手应答、信封、关闭标记之一");
        }
    }

    static BattleFrame ofVerify(int index, long atNanos, BattleTokenVerifyResponse verify) {
        return new BattleFrame(index, atNanos, Objects.requireNonNull(verify), null, null);
    }

    static BattleFrame ofContent(int index, long atNanos, MessageContent content) {
        return new BattleFrame(index, atNanos, null, Objects.requireNonNull(content), null);
    }

    static BattleFrame ofClosed(int index, long atNanos, String closedBy) {
        return new BattleFrame(index, atNanos, null, null, Objects.requireNonNull(closedBy));
    }

    public boolean isVerify() {
        return verify != null;
    }

    public boolean isClosed() {
        return closedBy != null;
    }

    /** 推送形状：{@code id = 0}、没有信封错误（battle-node-spec §3.2）。 */
    public boolean isPush() {
        return content != null && content.getId() == 0 && !content.hasErrorMessage();
    }

    /** 应答形状：{@code id} 回显请求、没有信封错误。 */
    public boolean isReply() {
        return content != null && content.getId() != 0 && !content.hasErrorMessage();
    }

    /** 信封错误形状：带 {@code error_message}（闸门拒绝：1005 / 1008 / 1010）。 */
    public boolean isEnvelopeError() {
        return content != null && content.hasErrorMessage();
    }

    /** 信封的消息号；不是信封时为 -1。 */
    public int messageId() {
        return content == null ? -1 : content.getMessageId();
    }

    /** 信封回显的请求 id；推送为 0，不是信封时为 -1。 */
    public long requestId() {
        return content == null ? -1 : content.getId();
    }

    /** 信封错误的 tip；没有时为 0。 */
    public int envelopeTipId() {
        return content != null && content.hasErrorMessage() ? content.getErrorMessage().getId() : 0;
    }

    public boolean isPush(int id) {
        return isPush() && messageId() == id;
    }

    public boolean isReplyTo(int id, long requestId) {
        return isReply() && messageId() == id && content.getId() == requestId;
    }

    /**
     * 一眼能看懂的标签（报告与顺序断言用）：{@code verify-ok:<battle_id>} / {@code verify-fail:<错误串>} / {@code push:<号>} /
     * {@code reply:<号>} / {@code error:<号>:<tip>} / {@code closed:<原因>}。
     */
    public String label() {
        if (closedBy != null) {
            return "closed:" + closedBy;
        }
        if (verify != null) {
            return verify.getSuccess() ? "verify-ok:" + Long.toUnsignedString(verify.getBattleId()) : "verify-fail:" + verify.getError();
        }
        if (content.hasErrorMessage()) {
            return "error:" + content.getMessageId() + ":" + content.getErrorMessage().getId();
        }
        return (content.getId() == 0 ? "push:" : "reply:") + content.getMessageId();
    }

    /** 解析信封体；不是信封或解析失败抛出（服务端发了与契约类型不符的字节）。 */
    public <T extends Message> T parse(Parser<T> parser) throws RobotException {
        if (content == null) {
            throw new RobotException("期望信封，实际 " + label());
        }
        try {
            return parser.parseFrom(content.getSerializedMessage());
        } catch (InvalidProtocolBufferException e) {
            throw new RobotException("message_id=" + content.getMessageId() + " 的消息体解析失败：" + e.getMessage(), e);
        }
    }

    /** 解析信封体，失败或不是信封返回 null（用在等待条件里）。 */
    public <T extends Message> T parseOrNull(Parser<T> parser) {
        if (content == null) {
            return null;
        }
        try {
            return parser.parseFrom(content.getSerializedMessage());
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "#" + index + " " + label();
    }
}
