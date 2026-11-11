package com.game.login.dispatch;

import com.game.api.proto.SessionContext;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.Message;
import java.time.Duration;
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
 *   <li>同一会话的调用由 gate 串行发出，但 gate 在调用超时之后就放行下一条：有写的处理器要保证截止之后不提交（见四参 {@code handle}）；
 *       跨会话的并发（同账号多连接）由处理器自己控制。</li>
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
     * 分发器在 Dubbo 线程上受理，到工作线程开始处理为止）。没有调用方截止时等价于
     * {@link #handle(SessionContext, Message, long, long) handle(session, request, queuedNanos, NO_CALLER_DEADLINE)}。
     *
     * <p>gate 调 login 的 Dubbo 超时从 gate 发出请求就开始计，排队同样耗掉这段等待；超时一到 gate 就放行同一会话的下一条请求。
     * 所以有请求预算的处理器（建角、进游戏、轮换 refresh）的截止取「受理时刻 + 预算」（含排队），
     * 不按开始处理的时刻算——积压时按后者算的截止本身就晚于 gate 放行。其余处理器走缺省实现，忽略排队时长。
     * 传时长而不传受理时刻的绝对读数：处理器的单调时钟可以注入（测试用），不必与分发器的时钟是同一个。
     */
    default CompletableFuture<HandlerReply> handle(SessionContext session, Req request, long queuedNanos) {
        return handle(session, request);
    }

    /** {@code callerRemainingNanos} 的取值：调用方没有传截止（旧 gate、单测、进程内直调），不设上限。 */
    long NO_CALLER_DEADLINE = Long.MAX_VALUE;

    /**
     * 同 {@link #handle(SessionContext, Message, long)}，另带调用方（gate）截止在开始处理这一刻还剩多久（{@code callerRemainingNanos}，纳秒，≥ 0，
     * 已扣过传播余量；没有传时为 {@link #NO_CALLER_DEADLINE}）。<b>分发器只调本方法</b>。有请求预算的处理器覆写本方法，截止用
     * {@link #deadlineNanos} 取 min(受理时刻 + 预算, 调用方截止)：gate 按它自己的超时放行下一条，本地预算再小也不该晚于它
     * （mmorpg client-request-ordering.md §6.1）。缺省实现忽略调用方截止，转给三参版本。
     */
    default CompletableFuture<HandlerReply> handle(SessionContext session, Req request, long queuedNanos, long callerRemainingNanos) {
        return handle(session, request, queuedNanos);
    }

    /**
     * 有请求预算的处理器的截止（与 {@code nowNanos} 同一个单调时钟）= now + min(预算 − 排队, 调用方剩余)。
     *
     * @param nowNanos             处理器开始处理时的单调时钟读数
     * @param queuedNanos          受理到开始处理排了多久（≥ 0）
     * @param callerRemainingNanos 调用方截止此刻还剩多久；{@link #NO_CALLER_DEADLINE} 表示不设上限
     */
    static long deadlineNanos(long nowNanos, Duration budget, long queuedNanos, long callerRemainingNanos) {
        return nowNanos + Math.min(budget.toNanos() - queuedNanos, callerRemainingNanos);
    }

    /**
     * 以 {@code tip} 表达失败的应答体。应答类型没有 {@code error_message}（如 {@code LoginEmptyResponse}）时返回空，
     * 分发器据此不回包。
     */
    Optional<Message> failureBody(TipInfoMessage tip);
}
