package com.game.login.dispatch;

import com.game.api.proto.SessionDirective;
import com.google.protobuf.Message;
import java.util.List;
import java.util.Optional;

/**
 * 处理器的结果：应答体（可无）+ 给 gate 的会话指令（按顺序执行）。
 *
 * @param body       应答消息；为空表示不回包（空应答类型的方法）
 * @param directives 会话指令，只在成功路径上给出
 */
public record HandlerReply(Optional<Message> body, List<SessionDirective> directives) {

    public HandlerReply {
        directives = List.copyOf(directives);
    }

    public static HandlerReply of(Message body) {
        return new HandlerReply(Optional.of(body), List.of());
    }

    public static HandlerReply of(Message body, SessionDirective directive) {
        return new HandlerReply(Optional.of(body), List.of(directive));
    }

    /** 不回包、无指令。 */
    public static HandlerReply none() {
        return new HandlerReply(Optional.empty(), List.of());
    }
}
