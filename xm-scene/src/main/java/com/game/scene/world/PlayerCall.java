package com.game.scene.world;

import com.game.contract.MessageMethod;
import com.game.proto.Empty;
import com.google.protobuf.Message;

/**
 * 一条已通过会话 / 消息号 / GM 闸校验的玩家请求（只在场景逻辑线程上用）。处理器经 {@link #reply} 回应答：
 * 应答的 {@code message_id} 同请求、{@code id} 回显请求号；每条请求至多回一次。可以先发别的消息再回应答
 * （放技能先广播 70），也可以先回应答再做后续（换场景先回 63 再发 79）。
 *
 * <p><b>延迟应答</b>（批次 5.4，zone-travel-spec §5.5 的 3026）：处理器要先做一次异步读才能决定应答内容时，调 {@link #defer} 拿走一个
 * {@link DeferredReply}，处理器返回后分发处不再补 1006；之后由拿着它的人在逻辑线程上回（至多一次）或作废。
 * 一条请求要么 {@link #reply}，要么 {@link #defer}，不能两样都做。
 */
public final class PlayerCall {

    private final SceneWorld world;
    private final ScenePlayer player;
    private final MessageMethod method;
    private final long requestId;
    private boolean replied;
    private DeferredReply deferred;

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

    /** @throws IllegalStateException 已经回过、已转成延迟应答、应答类型不符或方法没有应答（处理器的编程错误） */
    public void reply(Message response) {
        if (replied) {
            throw new IllegalStateException("同一请求回了两次应答 " + method.key());
        }
        if (deferred != null) {
            throw new IllegalStateException(method.key() + " 的应答已转成延迟应答，只能经 DeferredReply 回");
        }
        checkResponseType(method, response);
        replied = true;
        world.sendTo(player, SceneMessageIds.reply(method.messageId(), requestId, response));
    }

    /**
     * 把这条请求的应答转成延迟应答：处理器返回时不必已经回过，分发处也不再补 1006。每条请求至多调一次。
     *
     * <p>调用方从此对「这条请求最终有且只有一个应答」负责：拿到的 {@link DeferredReply} 要么 {@link DeferredReply#reply}，
     * 要么在确定不该回时 {@link DeferredReply#cancel}（会话已走等）。处理器在 {@code defer()} 之后又抛了异常时，分发处会经它补回 1006
     * （之后再 {@code reply} 返回 false、什么也不发）。
     *
     * @throws IllegalStateException 已经回过、已经延迟过，或方法没有应答（处理器的编程错误）
     */
    public DeferredReply defer() {
        if (replied) {
            throw new IllegalStateException(method.key() + " 已经回过应答，不能再转成延迟应答");
        }
        if (deferred != null) {
            throw new IllegalStateException("同一请求转了两次延迟应答 " + method.key());
        }
        if (method.responsePrototype() instanceof Empty) {
            throw new IllegalStateException(method.key() + " 没有应答，不能延迟");
        }
        deferred = new DeferredReply(world, player, method, requestId);
        return deferred;
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

    /** 应答是否已转成延迟应答（{@link #defer} 调过）。分发处据此不补 1006。 */
    boolean deferred() {
        return deferred != null;
    }

    /** {@link #defer} 交出去的那个延迟应答；没延迟过为 null。只给分发处用（处理器在延迟之后抛异常时经它补 1006）。 */
    DeferredReply deferredReply() {
        return deferred;
    }

    /** @throws IllegalStateException 方法没有应答，或应答类型与契约不符 */
    static void checkResponseType(MessageMethod method, Message response) {
        Message prototype = method.responsePrototype();
        if (prototype instanceof Empty || !prototype.getClass().isInstance(response)) {
            throw new IllegalStateException(method.key() + " 的应答类型是 " + prototype.getClass().getName()
                    + "，回的是 " + response.getClass().getName());
        }
    }
}
