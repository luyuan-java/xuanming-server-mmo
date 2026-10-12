package com.game.net.link;

import com.google.protobuf.MessageLite;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;

/**
 * 节点间长连接（gate ↔ scene）的编解码：4 字节大端长度头 + 一条 protobuf 信封，全部用 Netty 自带组件。
 *
 * <p>只在 Java 版内部使用，不与 C++ 的 "RPC0" 帧兼容（两版不混部）。长度上限防止对端异常时无界分配。
 */
public final class NodeLinkCodec {

    /** 单帧上限。下行携带的是客户端消息（客户端帧上限 64KB），批量广播留足余量。 */
    public static final int MAX_FRAME_LENGTH = 16 * 1024 * 1024;

    private NodeLinkCodec() {
    }

    /** 往 pipeline 末尾装上解码 / 编码器；{@code frameType} 是信封消息的默认实例。 */
    public static void install(ChannelPipeline pipeline, MessageLite frameType) {
        pipeline.addLast("linkFrameDecoder", new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4));
        pipeline.addLast("linkProtobufDecoder", new ProtobufDecoder(frameType));
        pipeline.addLast("linkFramePrepender", new LengthFieldPrepender(4));
        pipeline.addLast("linkProtobufEncoder", new ProtobufEncoder());
    }
}
