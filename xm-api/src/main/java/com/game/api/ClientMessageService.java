package com.game.api;

import com.game.api.proto.Ack;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import java.util.concurrent.CompletableFuture;

/**
 * 客户端消息的后端入口（Dubbo 服务）。gate 按消息号所属的 proto 域（{@code proto/login/...} 为 {@code login}）
 * 选择 Dubbo group 转发；后端内部再按消息号派发到具体处理方法。
 *
 * <p>契约：
 * <ul>
 *   <li>返回的 future 不会以异常完成来表达业务失败；业务失败放在 {@link ClientReply#getTipId()}。
 *       future 异常只表示调用本身失败（超时、后端不可用），gate 回通用的「服务不可用」tip。</li>
 *   <li>同一会话的调用由 gate 串行发出（上一个完成才发下一个），后端不需要为同一会话做并发控制。</li>
 *   <li>后端只信任 {@link ClientCall#getSession()} 里 gate 填的字段。</li>
 * </ul>
 */
public interface ClientMessageService {

    CompletableFuture<ClientReply> handle(ClientCall call);

    /** 会话结束通知（断线或主动离开）。幂等：同一会话重复通知不产生副作用。 */
    CompletableFuture<Ack> sessionClosed(SessionClosed event);
}
