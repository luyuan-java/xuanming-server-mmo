package com.game.net.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * 验签前帧长上限（对齐主仓 {@code pre_auth_message_limit_test.cpp}）与传输层选择回归单测。
 */
class PreAuthFrameLimitTest {

    private static final Map<String, Message> ACCEPTED = Map.of(
            ClientTokenVerifyRequest.getDescriptor().getFullName(), ClientTokenVerifyRequest.getDefaultInstance(),
            ClientRequest.getDescriptor().getFullName(), ClientRequest.getDefaultInstance());

    @Test
    void 未验签声明超过4KB帧_仅发4字节长度头即拒绝关闭_验签后放宽至64KB() {
        AtomicBoolean verified = new AtomicBoolean(false);
        List<ClientFrameException.Reason> errors = new ArrayList<>();
        EmbeddedChannel ch = new EmbeddedChannel(new ClientFrameDecoder(
                ACCEPTED,
                () -> verified.get() ? ClientFrames.DEFAULT_MAX_LEN : ClientFrames.PRE_AUTH_MAX_LEN,
                () -> true,
                (ctx, e) -> errors.add(e.reason())));

        // 场景 1：未验签状态下只写 4 字节长度头（4097 > 4096），不等帧体到齐即拒绝关闭
        ByteBuf oversizedHeaderOnly = ch.alloc().buffer(4);
        oversizedHeaderOnly.writeInt(ClientFrames.PRE_AUTH_MAX_LEN + 1);
        ch.writeInbound(oversizedHeaderOnly);
        assertThat(errors).containsExactly(ClientFrameException.Reason.INVALID_LENGTH);
        assertThat(ch.isOpen()).isFalse();

        // 场景 2：真实验签包远小于 4 KiB，正常通过；验签成功后 > 4 KiB（例如 8 KiB）帧正常解码
        errors.clear();
        EmbeddedChannel ok = new EmbeddedChannel(new ClientFrameDecoder(
                ACCEPTED,
                () -> verified.get() ? ClientFrames.DEFAULT_MAX_LEN : ClientFrames.PRE_AUTH_MAX_LEN,
                () -> true,
                (ctx, e) -> errors.add(e.reason())));
        ClientTokenVerifyRequest verify = ClientTokenVerifyRequest.newBuilder()
                .setPayload(ByteString.copyFrom(new byte[256]))
                .setSignature(ByteString.copyFrom(new byte[32]))
                .build();
        ok.writeInbound(ClientFrames.encode(ok.alloc(), verify));
        assertThat((Object) ok.readInbound()).isEqualTo(verify);
        assertThat(errors).isEmpty();
        assertThat(ok.isOpen()).isTrue();

        verified.set(true);
        ClientRequest large = ClientRequest.newBuilder()
                .setId(42)
                .setMessageId(100)
                .setBody(ByteString.copyFrom(new byte[8192]))
                .build();
        ok.writeInbound(ClientFrames.encode(ok.alloc(), large));
        assertThat((Object) ok.readInbound()).isEqualTo(large);
        assertThat(errors).isEmpty();
        assertThat(ok.isOpen()).isTrue();
    }

    @Test
    void WebSocket验签前超4KB帧回1009关闭_验签后允许大帧() {
        AtomicBoolean verified = new AtomicBoolean(false);
        List<Integer> closeCodes = new ArrayList<>();
        WebSocketObserver obs = new WebSocketObserver() {
            @Override
            public void onProtocolClosed(int closeCode) {
                closeCodes.add(closeCode);
            }
        };
        EmbeddedChannel ch = new EmbeddedChannel(
                new WebSocketMuxHandler(
                        () -> verified.get()
                                ? WebSocketMuxHandler.DEFAULT_MAX_PAYLOAD_BYTES
                                : WebSocketMuxHandler.PRE_AUTH_MAX_PAYLOAD_BYTES,
                        obs),
                new ClientFrameDecoder(
                        ACCEPTED,
                        () -> verified.get() ? ClientFrames.DEFAULT_MAX_LEN : ClientFrames.PRE_AUTH_MAX_LEN,
                        () -> true,
                        (ctx, e) -> { }));
        upgradeWs(ch);

        // 声明超过 PRE_AUTH_MAX_PAYLOAD_BYTES 的 WS 二进制帧头（仅头即被 1009 拦截）
        int tooBig = WebSocketMuxHandler.PRE_AUTH_MAX_PAYLOAD_BYTES + 1;
        ByteBuf hdr = Unpooled.buffer(8);
        hdr.writeByte(0x82);
        hdr.writeByte(0x80 | 126);
        hdr.writeShort(tooBig);
        hdr.writeInt(0x11223344);
        ch.writeInbound(hdr);

        assertThat(closeCodes).containsExactly(WebSocketBinaryCodec.CLOSE_MESSAGE_TOO_BIG);
        ByteBuf closeFrame = ch.readOutbound();
        assertThat(closeFrame).isNotNull();
        assertThat(closeFrame.getUnsignedShort(2)).isEqualTo(1009);
        closeFrame.release();
        assertThat(ch.isOpen()).isFalse();

        // 验签后允许 8 KiB WS 二进制帧穿透并解码
        closeCodes.clear();
        verified.set(true);
        EmbeddedChannel ch2 = new EmbeddedChannel(
                new WebSocketMuxHandler(
                        () -> verified.get()
                                ? WebSocketMuxHandler.DEFAULT_MAX_PAYLOAD_BYTES
                                : WebSocketMuxHandler.PRE_AUTH_MAX_PAYLOAD_BYTES,
                        obs),
                new ClientFrameDecoder(
                        ACCEPTED,
                        () -> verified.get() ? ClientFrames.DEFAULT_MAX_LEN : ClientFrames.PRE_AUTH_MAX_LEN,
                        () -> true,
                        (ctx, e) -> { }));
        upgradeWs(ch2);
        ClientRequest large = ClientRequest.newBuilder()
                .setId(9)
                .setMessageId(100)
                .setBody(ByteString.copyFrom(new byte[8192]))
                .build();
        ByteBuf encoded = ClientFrames.encode(ch2.alloc(), large);
        byte[] payload = new byte[encoded.readableBytes()];
        encoded.readBytes(payload);
        encoded.release();
        ch2.writeInbound(maskedBinaryFrame(payload));
        assertThat((Object) ch2.readInbound()).isEqualTo(large);
        assertThat(closeCodes).isEmpty();
        assertThat(ch2.isOpen()).isTrue();
    }

    @Test
    void 传输层选择在NIO回落时可正常构造并绑定配置() {
        EventLoopGroup group = NettyServerTransports.newEventLoopGroup(1, "test-nio", false);
        try {
            assertThat(NettyServerTransports.isEpollGroup(group)).isFalse();
            ServerBootstrap bootstrap = NettyServerTransports.configureServer(
                    new ServerBootstrap().group(group), false, NettyServerTransports.DEFAULT_TCP_DEFER_ACCEPT_SECONDS);
            assertThat(bootstrap).isNotNull();
        } finally {
            group.shutdownGracefully().syncUninterruptibly();
        }
    }

    private static void upgradeWs(EmbeddedChannel ch) {
        String req = "GET /ws HTTP/1.1\r\n"
                + "Host: ws.example.com\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        ch.writeInbound(Unpooled.copiedBuffer(req, StandardCharsets.US_ASCII));
        ByteBuf resp = ch.readOutbound();
        assertThat(resp).isNotNull();
        resp.release();
    }

    private static ByteBuf maskedBinaryFrame(byte[] payload) {
        byte[] mask = {0x12, 0x34, 0x56, 0x78};
        ByteBuf out = Unpooled.buffer(8 + payload.length);
        out.writeByte(0x82);
        if (payload.length < 126) {
            out.writeByte(0x80 | payload.length);
        } else {
            out.writeByte(0x80 | 126);
            out.writeShort(payload.length);
        }
        out.writeBytes(mask);
        for (int i = 0; i < payload.length; i++) {
            out.writeByte(payload[i] ^ mask[i & 3]);
        }
        return out;
    }
}
