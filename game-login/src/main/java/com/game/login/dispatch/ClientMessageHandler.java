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
 *   <li>{@link #handle(SessionContext, Message, long)} 在 login 工作线程上被调用，可以直接做阻塞 I/O；需要等别的服务时返回组合好的 future，
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
     * 同 {@link #handle(SessionContext, Message)}，另带这条请求在 login 工作队列里排了多久（{@code queuedNanos}，纳秒，≥ 0：
     * 分发器在 Dubbo 线程上受理，到工作线程开始处理为止）。分发器只调本方法。
     *
     * <p>gate 调 login 的 Dubbo 超时从 gate 发出请求就开始计，排队同样耗掉这段等待；超时一到 gate 就放行同一会话的下一条请求。
     * 所以有请求预算的处理器（建角、进游戏、轮换 refresh）覆写本方法，截止取「受理时刻 + 预算」（含排队），
     * 不按开始处理的时刻算——积压时按后者算的截止本身就晚于 gate 放行。其余处理器走缺省实现，忽略排队时长。
     * 传时长而不传受理时刻的绝对读数：处理器的单调时钟可以注入（测试用），不必与分发器的时钟是同一个。
     */
    default CompletableFuture<HandlerReply> handle(SessionContext session, Req request, long queuedNanos) {
        return handle(session, request);
    }

    /**
     * 以 {@code tip} 表达失败的应答体。应答类型没有 {@code error_message}（如 {@code LoginEmptyResponse}）时返回空，
     * 分发器据此不回包。
     */
    Optional<Message> failureBody(TipInfoMessage tip);
}
