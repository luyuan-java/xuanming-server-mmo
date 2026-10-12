package com.game.net.client;

import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import java.util.List;

/** 客户端帧编码器，无状态可共享。 */
@Sharable
public final class ClientFrameEncoder extends MessageToMessageEncoder<Message> {

    public static final ClientFrameEncoder INSTANCE = new ClientFrameEncoder();

    private ClientFrameEncoder() {
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, Message msg, List<Object> out) {
        ByteBuf frame = ClientFrames.encode(ctx.alloc(), msg);
        out.add(frame);
    }
}
