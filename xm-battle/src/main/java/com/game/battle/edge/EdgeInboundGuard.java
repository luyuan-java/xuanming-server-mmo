package com.game.battle.edge;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

/**
 * 解码器之前的入站闸：会话进入关闭中（握手被拒、终局优雅关闭、强关）或从未分配（并发上限）之后，丢弃一切入站字节，不再交给解码器。
 *
 * <p>对应基线 {@code codec.cpp:158-162}：{@code shutdown()} / {@code forceClose()} 之后连接不再是 connected，codec 直接清空缓冲、不解析。
 * 没有这道闸的话，关闭中的连接再收到坏帧会让解码器当场强关，把还在输出缓冲里的终局包 / 应答丢掉（R3），还会多计一次非法帧。
 *
 * <p>同时是「一次读」的边界：一次 socket 读到的字节交给解码器、其中的帧逐个分发完之后，房间在这次读里发起的优雅关闭在这里生效
 * （{@link BattleEdgeHandler#afterInboundRead}）。基线 {@code queueInLoop} 推迟的 {@code shutdown()} 在本轮 I/O 处理完之后才执行，
 * 同一次读里的帧照常分发，下一次读到的字节才作废；Netty 一轮里可能对同一连接连读几次，所以不能只靠 {@link DirectClose} 排在任务队列里的那一步。
 * 每连接一个实例（不可共享），只在连接所属的 EventLoop 上运行。
 */
final class EdgeInboundGuard extends ChannelInboundHandlerAdapter {

    private final BattleEdgeHandler handler;

    EdgeInboundGuard(BattleEdgeHandler handler) {
        this.handler = handler;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!handler.acceptsInbound()) {
            ReferenceCountUtil.release(msg);
            return;
        }
        try {
            ctx.fireChannelRead(msg);
        } finally {
            handler.afterInboundRead();
        }
    }
}
