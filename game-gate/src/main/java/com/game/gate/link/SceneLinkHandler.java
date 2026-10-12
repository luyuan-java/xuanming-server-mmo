package com.game.gate.link;

import com.game.api.proto.NodeLinkFrame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 链路 channel 上的业务 handler：把连接事件与入站帧交给 {@link SceneLink}。每条连接一个实例。 */
public final class SceneLinkHandler extends SimpleChannelInboundHandler<NodeLinkFrame> {

    private static final Logger log = LoggerFactory.getLogger(SceneLinkHandler.class);

    private final SceneLink link;

    public SceneLinkHandler(SceneLink link) {
        this.link = link;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        link.onConnected(ctx.channel());
        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, NodeLinkFrame frame) {
        link.onFrame(frame);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        link.fail("连接断开");
        ctx.fireChannelInactive();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("scene 链路异常，断开 node={} gen={}", link.nodeId(), link.generation(), cause);
        ctx.close();
    }
}
