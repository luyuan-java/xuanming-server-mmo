package com.game.api;

import com.game.common.deadline.Deadline;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.apache.dubbo.common.constants.CommonConstants;
import org.apache.dubbo.rpc.RpcContext;
import org.apache.dubbo.rpc.TimeoutCountDown;

/**
 * 调用方经 Dubbo 3 的超时倒计时（timeout countdown）传来的截止：gate 调 {@link ClientMessageService#handle} 时带上它，
 * 后端的截止取「本地预算」与它的较早者（{@link Deadline#within}；mmorpg {@code docs/design/client-request-ordering.md} §5、§6.1）。
 *
 * <p><b>机制</b>（Dubbo 3.3.6，按字节码核实）：调用方引用在方法上开 {@code enable-timeout-countdown=true} 时，每次调用把这一次的超时（毫秒）
 * 放进附件 {@code _TO}（Triple 把附件当 HTTP/2 头传，名字转成小写 {@code _to}）；提供方的 {@code ContextFilter} 读到后在
 * {@code RpcContext.getServerAttachment()} 上放一个 {@link TimeoutCountDown}（键 {@code timeout-countdown}），
 * 从过滤器执行那一刻起倒计时。传的是相对值，两机时钟偏差进不来；传输与解码耗时不在里面，由 {@link Deadline#CALLER_MARGIN_MILLIS} 兜住。
 *
 * <p><b>副作用</b>（Dubbo 自己的语义，不是本类加的）：提供方在<b>同一条 Dubbo 线程上</b>发出的下游 Dubbo 调用会继承这个倒计时——
 * 下游超时换成剩余时长、到期直接不发（{@code ConsumerContextFilter}；下游引用不用任何配置就继承：它读下游方法的
 * {@code enable-timeout-countdown} 时缺省为 true）。各后端的 {@code handle} 都把工作投到自己的工作线程上
 * （match 的 inline 处理器不做 I/O），下游调用不在 Dubbo 线程上发出，不受影响；以后有人在 Dubbo 线程上直接调下游，要先想到这一条。
 *
 * <p>线程安全：只读线程局部状态。
 */
public final class CallerDeadline {

    private CallerDeadline() {
    }

    /**
     * 本次调用的调用方截止，已扣掉 {@link Deadline#CALLER_MARGIN_MILLIS}；调用方没有开倒计时（旧 gate、单测直调、进程内调用）时为空。
     *
     * <p>必须在 Dubbo 提供方线程上、服务方法入口<b>同步</b>调用：{@code RpcContext} 是线程局部的，投到工作线程之后就读不到了；
     * 服务方法返回之后提供方的 {@code ContextFilter} 会清掉服务端附件（{@code RpcContext.removeServerAttachment}），不会漏给这条线程上的下一次调用。
     */
    public static Optional<Deadline> fromRpcContext() {
        Object countdown = RpcContext.getServerAttachment().getObjectAttachment(CommonConstants.TIME_COUNTDOWN_KEY);
        if (countdown instanceof TimeoutCountDown timeout) {
            return Optional.of(Deadline.fromCallerRemaining(timeout.timeRemaining(TimeUnit.NANOSECONDS)));
        }
        return Optional.empty();
    }
}
