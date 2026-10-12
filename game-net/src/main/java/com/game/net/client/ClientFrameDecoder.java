package com.game.net.client;

import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/**
 * 客户端帧解码器。每条连接一个实例（非 {@code @Sharable}）；gate 大厅连接、battle 直连、robot 的下行共用。
 *
 * <p>不用 {@code LengthFieldBasedFrameDecoder}：它不查最小长度，超长时的 failFast 会跳帧继续读；
 * C++ 的语义是任何非法帧都清空缓冲并立即断开，这里照此实现。
 *
 * <p><b>逐帧分发</b>，照基线 {@code ProtobufCodec::onMessage}（{@code codec.cpp:158-191}）：
 * <ul>
 *   <li>每次 {@link #decode} 至多解一帧。{@code ByteToMessageDecoder.callDecode} 先把这一帧交给下游 handler、处理完（应答已写出），
 *       才回来解下一帧。同一次读里先到的合法帧因此一定先被处理：后面跟着坏帧（长度 / 校验和 / 类型名 / 体解析）时，先到的握手、请求照常
 *       处理、回包，然后才因坏帧断开。一次解完整批再分发的话，坏帧当场 {@code close()}，先到的帧交给 handler 时连接已断，全部作废、不回包。</li>
 *   <li>每解一帧之前先看连接是否仍活跃、再问 {@code keepDecoding}（接 handler 的「会话还收帧」）：handler 已经同步关闭连接或决定关闭
 *       （握手被拒、握手前发请求、非法包达阈值、强关……），剩下的字节整批丢弃——不解析、不回调 {@code onError}（不计非法帧）、不当场强关，
 *       对应基线 {@code if (!conn->connected()) { buf->retrieveAll(); break; }}。所以「被拒的握手 + 坏帧」同一批到达时，
 *       客户端照样先收到拒绝应答，然后才断开。</li>
 * </ul>
 * 不用 {@code setSingleDecode(true)}：那样同一次读里剩下的帧要等下一次读才解，pipeline 的请求会卡住。
 *
 * <p>全部回调都在该连接的 EventLoop 上（线程所有权同 handler），{@code keepDecoding} 也只在那里被调用。
 */
public final class ClientFrameDecoder extends ByteToMessageDecoder {

    private final Map<String, Message> accepted;
    private final int maxLen;
    private final BooleanSupplier keepDecoding;
    private final BiConsumer<ChannelHandlerContext, ClientFrameException> onError;
    private boolean failed;

    /**
     * 不挂「会话还收帧」判据：连接活跃就一直逐帧解（下游没有自己的「关闭中」状态时用，例如 robot 的下行）。
     *
     * @param accepted 允许的类型（全名 → 默认实例）
     * @param maxLen   len 上限（客户端面 {@link ClientFrames#DEFAULT_MAX_LEN}）
     * @param onError  非法帧回调（打点 / 采样日志）；回调之后连接一律被关闭
     */
    public ClientFrameDecoder(Map<String, Message> accepted, int maxLen,
                              BiConsumer<ChannelHandlerContext, ClientFrameException> onError) {
        this(accepted, maxLen, () -> true, onError);
    }

    /**
     * @param accepted     允许的类型（全名 → 默认实例）
     * @param maxLen       len 上限（客户端面 {@link ClientFrames#DEFAULT_MAX_LEN}）
     * @param keepDecoding 是否还解下一帧；false = handler 已关闭或决定关闭这条连接：剩余字节丢弃，不解析、不报错。
     *                     在连接的 EventLoop 上调用，读 handler 自己的会话状态即可
     * @param onError      非法帧回调（打点 / 采样日志）；回调之后连接一律被关闭
     */
    public ClientFrameDecoder(Map<String, Message> accepted, int maxLen, BooleanSupplier keepDecoding,
                              BiConsumer<ChannelHandlerContext, ClientFrameException> onError) {
        this.accepted = Map.copyOf(accepted);
        this.maxLen = maxLen;
        this.keepDecoding = Objects.requireNonNull(keepDecoding, "keepDecoding");
        this.onError = Objects.requireNonNull(onError, "onError");
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (failed || !ctx.channel().isActive() || !keepDecoding.getAsBoolean()) {
            in.skipBytes(in.readableBytes());
            return;
        }
        if (in.readableBytes() < ClientFrames.HEADER_LEN) {
            return;
        }
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
