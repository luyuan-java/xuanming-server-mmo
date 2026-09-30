package com.game.gate.session;

import java.util.Map;

/** 各客户端消息号的发送频率上限（启动时构建，之后只读，线程安全）。 */
@FunctionalInterface
public interface MessageLimits {

    /** 不限频（测试与不需要限流的装配用）。 */
    MessageLimits UNLIMITED = messageId -> MessageLimit.UNLIMITED;

    MessageLimit limitOf(int messageId);

    /** 表里有的按表，其余用 {@link MessageLimit#DEFAULT}（与 C++ gate 一致）。 */
    static MessageLimits of(Map<Integer, MessageLimit> overrides) {
        Map<Integer, MessageLimit> frozen = Map.copyOf(overrides);
        return messageId -> frozen.getOrDefault(messageId, MessageLimit.DEFAULT);
    }
}
