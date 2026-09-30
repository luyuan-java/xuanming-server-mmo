package com.game.gate.session;

/**
 * 一个客户端可发消息号的路由。
 *
 * @param messageId   消息号（{@code message_id.txt}）
 * @param domain      处理它的后端（{@link MessageRoutes#backendOf}：scene / login / unsupported）
 * @param hasResponse 契约里的应答类型不是 {@code Empty}：这类方法必须回包，哪怕应答序列化后是 0 字节
 *                    （例：新账号的 LoginResponse 全是默认值，0 字节，但 robot / 客户端在等 48 的应答）
 */
public record MessageRoute(int messageId, String domain, boolean hasResponse) {

    /** 有应答的路由（测试与大多数方法的常见形态）。 */
    public MessageRoute(int messageId, String domain) {
        this(messageId, domain, true);
    }
}
