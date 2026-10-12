package com.game.login.dispatch;

import com.game.api.proto.SessionContext;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.Message;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code ClientPlayerLogin} 服务里一个方法的处理器。{@link ClientMessageDispatcher} 按消息号选中它、解析好请求体后调用。
 *
 * <p>契约：
 * <ul>
 *   <li>{@link #handle} 在 login 工作线程上被调用，可以直接做阻塞 I/O；需要等别的服务时返回组合好的 future，
 *       不得在线程里阻塞等待远程结果。</li>
 *   <li>业务失败放进应答体的 {@code error_message}（字段 1），future 正常完成；成功应答<b>绝不</b>设置
 *       {@code error_message}（哪怕是默认实例，Go 端也会解成非 nil 判为失败）。</li>
 *   <li>future 异常完成表示内部故障，由分发器统一转成 {@link #failureBody} 的「服务不可用」应答。</li>
 *   <li>同一会话的调用由 gate 串行发出；跨会话的并发（同账号多连接）由处理器自己控制。</li>
 * </ul>
 *
 * @param <Req> 请求消息类型
 */
public interface ClientMessageHandler<Req extends Message> {

    /** 方法名（与 message_id.txt 的键「服务名 + 方法名」对应），分发器据此取消息号。 */
    String methodName();

    Class<Req> requestType();

    Class<? extends Message> responseType();

    CompletableFuture<HandlerReply> handle(SessionContext session, Req request);

    /**
     * 以 {@code tip} 表达失败的应答体。应答类型没有 {@code error_message}（如 {@code LoginEmptyResponse}）时返回空，
     * 分发器据此不回包。
     */
    Optional<Message> failureBody(TipInfoMessage tip);
}
