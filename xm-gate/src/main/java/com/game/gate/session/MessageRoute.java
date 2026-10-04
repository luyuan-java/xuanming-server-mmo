package com.game.gate.session;

/**
 * 一个客户端可发消息号的路由。
 *
 * @param messageId   消息号（{@code message_id.txt}）
 * @param domain      处理它的后端（{@link MessageRoutes#backendOf}：scene / login / unsupported）
 * @param hasResponse 契约里的应答类型不是 {@code Empty}：这类方法必须回包，哪怕应答序列化后是 0 字节
 *                    （例：新账号的 LoginResponse 全是默认值，0 字节，但 robot / 客户端在等 48 的应答）
 * @param method      契约里的方法名 {@code 服务裸名.方法名}（如 {@code ClientPlayerLogin.Login}），用作指标标签：
 *                    取值只来自客户端白名单，基数有界（architecture.md §11）
 * @param gm          GM 类指令（方法名 Gm* / Debug* / Test*）：运行模式不是 dev / test 时 gate 直接拒绝
 * @param rpcPath     热关停规则匹配用的方法全路径 {@code /proto 包.服务名/方法名}（如 {@code /friendpb.ClientPlayerFriend/AddFriend}）
 */
public record MessageRoute(int messageId, String domain, boolean hasResponse, String method, boolean gm, String rpcPath) {

    /** 全路径缺省由 {@code 服务裸名.方法名} 推出（没有 proto 包名；测试里手写的路由用）。 */
    public MessageRoute(int messageId, String domain, boolean hasResponse, String method, boolean gm) {
        this(messageId, domain, hasResponse, method, gm, "/" + method.replace('.', '/'));
    }

    /** 非 GM 指令。 */
    public MessageRoute(int messageId, String domain, boolean hasResponse, String method) {
        this(messageId, domain, hasResponse, method, false);
    }

    /** 方法名缺省为消息号本身（测试里手写的路由用）。 */
    public MessageRoute(int messageId, String domain, boolean hasResponse) {
        this(messageId, domain, hasResponse, Integer.toString(messageId));
    }

    /** 有应答的路由（测试与大多数方法的常见形态）。 */
    public MessageRoute(int messageId, String domain) {
        this(messageId, domain, true);
    }
}
