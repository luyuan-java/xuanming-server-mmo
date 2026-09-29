package com.game.net.client;

import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 客户端帧解码器。每条连接一个实例（非 {@code @Sharable}）。
 *
 * <p>不用 {@code LengthFieldBasedFrameDecoder}：它不查最小长度，超长时的 failFast 会跳帧继续读；
 * C++ 的语义是任何非法帧都清空缓冲并立即断开，这里照此实现。
 */
public final class ClientFrameDecoder extends ByteToMessageDecoder {

    private final Map<String, Message> accepted;
    private final int maxLen;
    private final BiConsumer<ChannelHandlerContext, ClientFrameException> onError;
    private boolean failed;

    /**
     * @param accepted 允许上行的类型（全名 → 默认实例）
     * @param maxLen   len 上限（客户端面 {@link ClientFrames#DEFAULT_MAX_LEN}）
     * @param onError  非法帧回调（打点 / 采样日志）；回调之后连接一律被关闭
     */
    public ClientFrameDecoder(Map<String, Message> accepted, int maxLen,
                              BiConsumer<ChannelHandlerContext, ClientFrameException> onError) {
        this.accepted = Map.copyOf(accepted);
        this.maxLen = maxLen;
        this.onError = onError;
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (!failed && in.readableBytes() >= ClientFrames.HEADER_LEN) {
            int len = in.getInt(in.readerIndex());
            if (len < ClientFrames.MIN_LEN || len > maxLen) {
                fail(ctx, in, new ClientFrameException(ClientFrameException.Reason.INVALID_LENGTH, "len=" + len));
                return;
            }
            if (in.readableBytes() < ClientFrames.HEADER_LEN + len) {
                return;
            }
            in.skipBytes(ClientFrames.HEADER_LEN);
            ByteBuf frame = in.readSlice(len);
            try {
                out.add(ClientFrames.decodeBody(frame, accepted));
            } catch (ClientFrameException e) {
                fail(ctx, in, e);
                return;
            }
        }
    }

    private void fail(ChannelHandlerContext ctx, ByteBuf in, ClientFrameException e) {
        failed = true;
        in.skipBytes(in.readableBytes());
        try {
            onError.accept(ctx, e);
        } finally {
            ctx.close();
        }
    }
}
