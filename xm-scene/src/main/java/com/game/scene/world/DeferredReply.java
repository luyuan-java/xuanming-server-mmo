package com.game.scene.world;

import com.game.contract.MessageMethod;
import com.game.proto.MessageContent;
import com.google.protobuf.Message;

/**
 * 一条请求的延迟应答（{@link PlayerCall#defer}，批次 5.4）：处理器先返回，应答稍后在场景逻辑线程上回。
 * <b>只在场景逻辑线程上用</b>，不加锁。
 *
 * <p>三个状态，只往前走：待回（{@link #pending}）→ 已回（{@link #reply} 第一次成功）或已作废（{@link #cancel}）。
 * 应答的 {@code message_id} 同请求、{@code id} 回显原请求号——客户端按信封 {@code id} 配对，所以晚回、乱序回都认得出是哪一条。
 * 发给的是<b>发起请求的那个实例的会话</b>：实例已被移出世界时照发（会话已走的由 gate 丢弃；同会话重进换了实例的，客户端仍收得到，
 * 不至于等满请求超时）。该不该回由持有者判断，这里不替它查实例是否还在。
 */
public final class DeferredReply {

    private final SceneWorld world;
    private final ScenePlayer player;
    private final MessageMethod method;
    private final long requestId;
    private boolean done;

    DeferredReply(SceneWorld world, ScenePlayer player, MessageMethod method, long requestId) {
        this.world = world;
        this.player = player;
        this.method = method;
        this.requestId = requestId;
    }

    /**
     * 回应答。第一次调用下发并返回 true；已经回过或已作废时返回 false、<b>什么也不发</b>（迟到的结果不会造成第二个应答）。
     *
     * @throws IllegalStateException 应答类型与契约不符（处理器的编程错误；状态不变，仍可用正确的类型再回）
     */
    public boolean reply(Message response) {
        PlayerCall.checkResponseType(method, response);
        return send(SceneMessageIds.reply(method.messageId(), requestId, response));
    }

    /** 作废、不回（确定不该再有应答时：会话已走）。已回过或已作废时什么也不做。 */
    public void cancel() {
        done = true;
    }

    /** 还没回、也没作废。 */
    public boolean pending() {
        return !done;
    }

    public long requestId() {
        return requestId;
    }

    public MessageMethod method() {
        return method;
    }

    /** 直接下发一条已组好的应答信封（分发处补 1006 用）；语义同 {@link #reply}。 */
    boolean send(MessageContent content) {
        if (done) {
            return false;
        }
        done = true;
        world.sendTo(player, content);
        return true;
    }
}
