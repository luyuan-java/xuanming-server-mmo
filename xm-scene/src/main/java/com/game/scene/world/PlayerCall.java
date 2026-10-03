package com.game.scene.world;

import com.game.contract.MessageMethod;
import com.game.proto.Empty;
import com.google.protobuf.Message;

/**
 * 一条已通过会话 / 消息号 / GM 闸校验的玩家请求（只在场景逻辑线程上用）。处理器经 {@link #reply} 回应答：
 * 应答的 {@code message_id} 同请求、{@code id} 回显请求号；每条请求至多回一次。可以先发别的消息再回应答
 * （放技能先广播 70），也可以先回应答再做后续（换场景先回 63 再发 79）。
 */
public final class PlayerCall {

    private final SceneWorld world;
    private final ScenePlayer player;
    private final MessageMethod method;
    private final long requestId;
    private boolean replied;

    PlayerCall(SceneWorld world, ScenePlayer player, MessageMethod method, long requestId) {
        this.world = world;
        this.player = player;
        this.method = method;
        this.requestId = requestId;
    }

    public ScenePlayer player() {
        return player;
    }

    public SceneWorld world() {
        return world;
    }

    public long requestId() {
        return requestId;
    }

    public MessageMethod method() {
        return method;
    }

    /** @throws IllegalStateException 已经回过、应答类型不符或方法没有应答（处理器的编程错误） */
    public void reply(Message response) {
        if (replied) {
            throw new IllegalStateException("同一请求回了两次应答 " + method.key());
        }
        Message prototype = method.responsePrototype();
        if (prototype instanceof Empty || !prototype.getClass().isInstance(response)) {
            throw new IllegalStateException(method.key() + " 的应答类型是 " + prototype.getClass().getName()
                    + "，回的是 " + response.getClass().getName());
        }
        replied = true;
        world.sendTo(player, SceneMessageIds.reply(method.messageId(), requestId, response));
    }

    /**
     * 处理过程中给本人推一条服务器消息（信封 {@code id} 为 0，客户端按推送处理，不会当成本请求的应答）。
     * 用于有顺序要求的连带推送，例如 GM 设等级先推 170 面板再回应答。
     */
    public void push(int messageId, Message body) {
        world.sendTo(player, SceneMessageIds.push(messageId, body));
    }

    boolean replied() {
        return replied;
    }
}
