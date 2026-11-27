package com.game.net.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.nio.channels.ClosedChannelException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

/**
 * RFC 6455 二进制帧编解码桥（紧贴 {@link ClientFrameDecoder} / {@link ClientFrameEncoder} 下方）。
 *
 * <p>对齐权威设计 {@code minigame-websocket-gate.md} W3 / W5：
 * <ul>
 *   <li>入站：按 RFC 6455 拆解客户端加掩码的二进制数据帧与续帧，将解掩码后的载荷按字节流喂给下游
 *       {@link ClientFrameDecoder}；同批排在 Close / 违规帧之前的数据帧照常交付；Ping 只回 Pong 且不穿透到下游
 *       （不刷新业务心跳）；Pong 忽略；违规帧与超长帧发对应关闭码（1002 / 1003 / 1009）并短延迟强关；</li>
 *   <li>出站：把下游写出的每个 {@link ByteBuf} 零拷贝包成一条 FIN=1 无掩码二进制帧（opcode 0x2）。</li>
 * </ul>
 */
public final class WebSocketBinaryCodec extends ChannelDuplexHandler {

    static final int CLOSE_NORMAL = 1000;
    static final int CLOSE_PROTOCOL_ERROR = 1002;
    static final int CLOSE_UNSUPPORTED_DATA = 1003;
    static final int CLOSE_MESSAGE_TOO_BIG = 1009;
    static final long SHORT_CLOSE_DELAY_MS = 100L;

    private static final int OP_CONTINUATION = 0x0;
    private static final int OP_TEXT = 0x1;
    private static final int OP_BINARY = 0x2;
    private static final int OP_CLOSE = 0x8;
    private static final int OP_PING = 0x9;
    private static final int OP_PONG = 0xA;

    private final IntSupplier maxPayloadBytes;
    private final WebSocketObserver observer;
    private ByteBuf cumulation;
    private boolean fragmentedOpen;
    private boolean closing;

    public WebSocketBinaryCodec(int maxPayloadBytes, WebSocketObserver observer) {
        if (maxPayloadBytes <= 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be positive");
        }
        this.maxPayloadBytes = () -> maxPayloadBytes;
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /** 支持按认证状态动态切换单帧载荷上限（验签前收紧至 4 KiB 级、验签后放宽至 64 KiB 级）。 */
    public WebSocketBinaryCodec(IntSupplier maxPayloadBytes, WebSocketObserver observer) {
        this.maxPayloadBytes = Objects.requireNonNull(maxPayloadBytes, "maxPayloadBytes");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf incoming)) {
            ctx.fireChannelRead(msg);
            return;
        }
        if (closing || !ctx.channel().isActive()) {
            incoming.release();
            return;
        }
        append(ctx, incoming);
        decodeLoop(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof ByteBuf payload)) {
            ctx.write(msg, promise);
            return;
        }
        if (closing) {
            payload.release();
            promise.tryFailure(new ClosedChannelException());
            return;
        }
        int len = payload.readableBytes();
        ByteBuf header = encodeServerFrameHeader(ctx, (byte) (0x80 | OP_BINARY), len);
        ctx.write(header);
        ctx.write(payload, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        releaseCumulation();
        super.channelInactive(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        releaseCumulation();
    }

    private void append(ChannelHandlerContext ctx, ByteBuf incoming) {
        if (cumulation == null) {
            cumulation = incoming;
            return;
        }
        if (cumulation instanceof CompositeByteBuf composite) {
            composite.addComponent(true, incoming);
            return;
        }
        CompositeByteBuf composite = ctx.alloc().compositeBuffer();
        composite.addComponents(true, cumulation, incoming);
        cumulation = composite;
    }

    private void decodeLoop(ChannelHandlerContext ctx) {
        while (!closing && ctx.channel().isActive() && cumulation != null && cumulation.readableBytes() >= 2) {
            int reader = cumulation.readerIndex();
            int b0 = cumulation.getUnsignedByte(reader);
            int b1 = cumulation.getUnsignedByte(reader + 1);

            boolean fin = (b0 & 0x80) != 0;
            int rsv = b0 & 0x70;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            int len7 = b1 & 0x7F;

            if (rsv != 0 || !masked) {
                failProtocol(ctx, CLOSE_PROTOCOL_ERROR, rsv != 0 ? "rsv_nonzero" : "unmasked_client_frame");
                return;
            }
            if ((opcode >= 3 && opcode <= 7) || opcode >= 11) {
                failProtocol(ctx, CLOSE_PROTOCOL_ERROR, "reserved_opcode_" + opcode);
                return;
            }
            boolean control = opcode >= 8;
            if (control && (!fin || len7 > 125)) {
                failProtocol(ctx, CLOSE_PROTOCOL_ERROR, !fin ? "fragmented_control" : "control_too_large");
                return;
            }
            if (opcode == OP_TEXT) {
                failProtocol(ctx, CLOSE_UNSUPPORTED_DATA, "text_frame_unsupported");
                return;
            }
            if (opcode == OP_CONTINUATION && !fragmentedOpen) {
                failProtocol(ctx, CLOSE_PROTOCOL_ERROR, "unexpected_continuation");
                return;
            }
            if (opcode == OP_BINARY && fragmentedOpen) {
                failProtocol(ctx, CLOSE_PROTOCOL_ERROR, "interleaved_data_frame");
                return;
            }

            int headerLen = 2;
            long payloadLen;
            if (len7 < 126) {
                payloadLen = len7;
            } else if (len7 == 126) {
                if (cumulation.readableBytes() < 4) {
                    return;
                }
                payloadLen = cumulation.getUnsignedShort(reader + 2);
                headerLen = 4;
            } else {
                if (cumulation.readableBytes() < 10) {
                    return;
                }
                long rawLen = cumulation.getLong(reader + 2);
                if (rawLen < 0) {
                    failProtocol(ctx, CLOSE_PROTOCOL_ERROR, "negative_64bit_length");
                    return;
                }
                payloadLen = rawLen;
                headerLen = 10;
            }

            if (payloadLen > maxPayloadBytes.getAsInt()) {
                failProtocol(ctx, CLOSE_MESSAGE_TOO_BIG, "payload_too_big_" + payloadLen);
                return;
            }
            int frameLen = headerLen + 4 + (int) payloadLen;
            if (cumulation.readableBytes() < frameLen) {
                return;
            }

            cumulation.skipBytes(headerLen);
            byte[] mask = new byte[4];
            cumulation.readBytes(mask);
            int pLen = (int) payloadLen;
            ByteBuf payload = ctx.alloc().buffer(pLen);
            try {
                for (int i = 0; i < pLen; i++) {
                    payload.writeByte(cumulation.readByte() ^ mask[i & 3]);
                }
            } catch (Throwable t) {
                payload.release();
                throw t;
            }
            compactIfEmpty();

            switch (opcode) {
                case OP_BINARY, OP_CONTINUATION -> {
                    fragmentedOpen = !fin;
                    if (payload.isReadable()) {
                        ctx.fireChannelRead(payload);
                    } else {
                        payload.release();
                    }
                }
                case OP_PING -> {
                    sendControlFrame(ctx, OP_PONG, payload);
                }
                case OP_PONG -> payload.release();
                case OP_CLOSE -> {
                    int code = CLOSE_NORMAL;
                    if (pLen == 1) {
                        payload.release();
                        failProtocol(ctx, CLOSE_PROTOCOL_ERROR, "close_payload_1_byte");
                        return;
                    }
                    if (pLen >= 2) {
                        code = payload.getUnsignedShort(payload.readerIndex());
                    }
                    payload.release();
                    echoCloseAndShut(ctx, code);
                    return;
                }
                default -> {
                    payload.release();
                    failProtocol(ctx, CLOSE_PROTOCOL_ERROR, "unexpected_opcode");
                    return;
                }
            }
        }
    }

    private void sendControlFrame(ChannelHandlerContext ctx, int opcode, ByteBuf payload) {
        ByteBuf header = encodeServerFrameHeader(ctx, (byte) (0x80 | opcode), payload.readableBytes());
        ctx.write(header);
        ctx.writeAndFlush(payload);
    }

    private void failProtocol(ChannelHandlerContext ctx, int closeCode, String reason) {
        closing = true;
        releaseCumulation();
        observer.onProtocolClosed(closeCode);
        echoCloseAndShut(ctx, closeCode);
    }

    private void echoCloseAndShut(ChannelHandlerContext ctx, int closeCode) {
        closing = true;
        releaseCumulation();
        ByteBuf closeFrame = ctx.alloc().buffer(4);
        closeFrame.writeByte(0x80 | OP_CLOSE);
        closeFrame.writeByte(2);
        closeFrame.writeShort(closeCode);
        ctx.writeAndFlush(closeFrame).addListener(ChannelFutureListener.CLOSE);
        scheduleShortForceClose(ctx);
    }

    static void scheduleShortForceClose(ChannelHandlerContext ctx) {
        if (ctx.channel().isActive()) {
            ctx.executor().schedule(() -> {
                if (ctx.channel().isOpen()) {
                    ctx.close();
                }
            }, SHORT_CLOSE_DELAY_MS, TimeUnit.MILLISECONDS);
        }
    }

    static ByteBuf encodeServerFrameHeader(ChannelHandlerContext ctx, byte firstByte, int payloadLen) {
        if (payloadLen < 126) {
            ByteBuf buf = ctx.alloc().buffer(2);
            buf.writeByte(firstByte);
            buf.writeByte(payloadLen);
            return buf;
        }
        if (payloadLen <= 0xFFFF) {
            ByteBuf buf = ctx.alloc().buffer(4);
            buf.writeByte(firstByte);
            buf.writeByte(126);
            buf.writeShort(payloadLen);
            return buf;
        }
        ByteBuf buf = ctx.alloc().buffer(10);
        buf.writeByte(firstByte);
        buf.writeByte(127);
        buf.writeLong(payloadLen);
        return buf;
    }

    private void compactIfEmpty() {
        if (cumulation == null) {
            return;
        }
        if (!cumulation.isReadable()) {
            cumulation.release();
            cumulation = null;
        } else if (cumulation instanceof CompositeByteBuf composite) {
            composite.discardReadComponents();
        }
    }

    private void releaseCumulation() {
        if (cumulation != null) {
            cumulation.release();
            cumulation = null;
        }
    }
}
