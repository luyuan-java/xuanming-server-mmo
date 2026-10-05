package com.game.battle.edge;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.socket.DuplexChannel;
import io.netty.util.concurrent.ScheduledFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 直连的优雅关闭（battle-node-spec §7.4「关闭」、R3 / R4；基线 {@code room.cpp:1595-1653} 的 {@code shutdown()} 推迟到本轮 loop 之后 +
 * {@code forceCloseWithDelay}，以及 {@code edge.cpp:267-274} 的握手被拒 {@code shutdown()} + 0.1 s 强关）。
 *
 * <p>做法：
 * <ol>
 *   <li>投递到连接所属 EventLoop（= 逻辑线程）的<b>下一个</b>任务里执行，所以当前处理器 / 任务里随后才写的应答（R2：直连面在房间处理器返回之后
 *       才写应答）先进输出缓冲；</li>
 *   <li>写一个空缓冲：它的 promise 在此前全部数据写进内核之后才完成；完成后 {@code shutdownOutput()} 发 FIN。不能直接 {@code shutdownOutput()}：
 *       Netty 会丢弃输出缓冲里还没写出的数据（终局包 / 应答丢失）；也不能直接 {@code close()}（同理）；</li>
 *   <li>对端不读时空写永远不完成，由 {@code forceAfterMs} 后的强关兜底（终局 1 s、握手被拒 0.1 s）。</li>
 * </ol>
 * 不是全双工连接（测试里的 {@code EmbeddedChannel}）时第 2 步改为 {@code close()}。连接关闭时取消强关计时器，不长期钉住 Channel。
 *
 * <p>调用方负责状态：直连面自己发起的（握手被拒）先把会话置为关闭中；房间发起的先摘槽（{@code room.cpp:1632-1634}），会话在第 1 步的任务开头
 * （或当前这次读处理完时，{@link EdgeInboundGuard}）才置为关闭中——在那之前同一批里后面的上行帧照常分发、应答照常写出（基线
 * {@code queueInLoop} 推迟的 {@code shutdown()}，见 {@link DirectSession} 类注释）。
 */
final class DirectClose {

    private static final Logger log = LoggerFactory.getLogger(DirectClose.class);

    private DirectClose() {
    }

    /**
     * 排在当前任务之后：先跑 {@code onStart}（房间发起的关闭在这里置 CLOSING，对应基线排队的 functor 里那次 {@code shutdown()}），
     * 再等此前写出的全部字节进内核后发 FIN；{@code forceAfterMs} 毫秒后强关兜底。任何线程可调（实际只在逻辑线程上调）；
     * EventLoop 已关闭时退化为立即 {@code close()}（不跑 {@code onStart}：连接随即断开，会话由 {@code channelInactive} 置 CLOSED）。
     */
    static void afterCurrentTask(Channel ch, long forceAfterMs, Runnable onStart) {
        EventLoop loop = ch.eventLoop();
        try {
            ScheduledFuture<?> force = loop.schedule(() -> forceClose(ch, forceAfterMs), forceAfterMs, TimeUnit.MILLISECONDS);
            ch.closeFuture().addListener(f -> force.cancel(false));
            loop.execute(() -> {
                onStart.run();
                flushThenFin(ch);
            });
        } catch (RejectedExecutionException e) {
            ch.close();
        }
    }

    private static void flushThenFin(Channel ch) {
        if (!ch.isActive()) {
            return;
        }
        ch.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(f -> {
            if (f.isSuccess() && ch instanceof DuplexChannel duplex && ch.isActive()) {
                // 只关写方向：对端读到 EOF 后关自己的 socket，本端读到 EOF 即关闭（ALLOW_HALF_CLOSURE = false），或由强关兜底
                duplex.shutdownOutput();
            } else {
                ch.close();
            }
        });
    }

    private static void forceClose(Channel ch, long forceAfterMs) {
        if (ch.isOpen()) {
            log.debug("battle 直连优雅关闭超时（{} ms 内没排空），强关 peer={}", forceAfterMs, ch.remoteAddress());
            ch.close();
        }
    }
}
