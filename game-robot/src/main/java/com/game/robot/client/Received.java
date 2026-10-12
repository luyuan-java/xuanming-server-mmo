package com.game.robot.client;

import com.game.proto.MessageContent;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.Parser;

/**
 * 一条收到的下行 {@code MessageContent}（应答或推送），带到达顺序与到达时刻。不可变。
 *
 * @param index         本连接上的到达序号（从 0 起）
 * @param receivedNanos 到达时刻（{@link System#nanoTime()}，只用于算时延，不是墙钟）
 * @param content       信封原文
 */
public record Received(int index, long receivedNanos, MessageContent content) {

    public int messageId() {
        return content.getMessageId();
    }

    /** 应答回显的 {@code ClientRequest.id}；推送为 0（robot 契约 §3.3）。 */
    public long requestId() {
        return content.getId();
    }

    /** 信封上的传输层错误（限频 1008、服务不可用 1006 等）；没有时为 0。 */
    public int envelopeTipId() {
        return content.hasErrorMessage() ? content.getErrorMessage().getId() : 0;
    }

    /** 解析消息体；失败说明服务端发了与契约类型不符的字节。 */
    public <T extends Message> T parse(Parser<T> parser) throws RobotException {
        try {
            return parser.parseFrom(content.getSerializedMessage());
        } catch (InvalidProtocolBufferException e) {
            throw new RobotException("message_id=" + messageId() + " 的消息体解析失败：" + e.getMessage(), e);
        }
    }

    /** 解析消息体，失败返回 null（用在等待条件里：解析失败的帧不算匹配，由调用方另行报告）。 */
    public <T extends Message> T parseOrNull(Parser<T> parser) {
        try {
            return parser.parseFrom(content.getSerializedMessage());
        } catch (InvalidProtocolBufferException e) {
            return null;
        }
    }
}
