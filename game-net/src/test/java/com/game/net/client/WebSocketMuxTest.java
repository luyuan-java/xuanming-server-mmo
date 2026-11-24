package com.game.net.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.ClientHeartbeatRequest;
import com.game.proto.ClientHeartbeatResponse;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 同端口 TCP / RFC 6455 WebSocket 自动分流与违规帧关闭码单测。 */
class WebSocketMuxTest {

    private static final Map<String, Message> ACCEPTED = Map.of(
            ClientHeartbeatRequest.getDescriptor().getFullName(), ClientHeartbeatRequest.getDefaultInstance());
    private static final String RFC_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final String RFC_ACCEPT = "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=";

    @Test
    void RFC6455官方握手向量与原生TCP零干扰() {
        assertThat(WebSocketMuxHandler.computeAccept(RFC_KEY)).isEqualTo(RFC_ACCEPT);

        // 原生 TCP 帧直通 ClientFrameDecoder，WebSocketMuxHandler 首字节即自移除
        EmbeddedChannel tcp = newChannel(WebSocketObserver.NOOP);
        ByteBuf rawFrame = ClientFrames.encode(tcp.alloc(), ClientHeartbeatRequest.newBuilder().setSeq(7).build());
        tcp.writeInbound(rawFrame);
        assertThat(tcp.pipeline().get(WebSocketMuxHandler.class)).isNull();
        ClientHeartbeatRequest decoded = tcp.readInbound();
        assertThat(decoded).isNotNull();
        assertThat(decoded.getSeq()).isEqualTo(7);
    }

    @Test
    void WebSocket逐字节分片握手_分片数据夹杂Ping_同包多帧_先交付数据再回Close_出站包二进制帧() {
        AtomicInteger accepted = new AtomicInteger();
        EmbeddedChannel ch = newChannel(new WebSocketObserver() {
            @Override public void onHandshakeAccepted() { accepted.incrementAndGet(); }
        });

        String req = "GET /ws/10.0.0.1/18000 HTTP/1.1\r\n"
                + "Host: ws.example.com\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: keep-alive, Upgrade\r\n"
                + "Sec-WebSocket-Key: " + RFC_KEY + "\r\n"
                + "Sec-WebSocket-Version: 13\r\n\r\n";
        // 逐字节投喂握手请求
        byte[] reqBytes = req.getBytes(StandardCharsets.US_ASCII);
        for (byte b : reqBytes) {
            ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{b}));
        }
        assertThat(accepted.get()).isEqualTo(1);
        ByteBuf respBuf = ch.readOutbound();
        String resp = respBuf.toString(StandardCharsets.US_ASCII);
        respBuf.release();
        assertThat(resp).startsWith("HTTP/1.1 101 Switching Protocols\r\n")
                .contains("Sec-WebSocket-Accept: " + RFC_ACCEPT + "\r\n");

        // 将两条 ClientHeartbeatRequest 拼成字节流，按两段分片发送，中间夹杂一条 Ping，最后紧跟 Close(1000)
        ByteBuf f1 = ClientFrames.encode(ch.alloc(), ClientHeartbeatRequest.newBuilder().setSeq(11).build());
        ByteBuf f2 = ClientFrames.encode(ch.alloc(), ClientHeartbeatRequest.newBuilder().setSeq(22).build());
        byte[] combined = new byte[f1.readableBytes() + f2.readableBytes()];
        f1.readBytes(combined, 0, f1.readableBytes());
        int split = combined.length / 2;
        f2.readBytes(combined, combined.length - f2.readableBytes(), f2.readableBytes());
        f1.release();
        f2.release();

        byte[] part1 = java.util.Arrays.copyOfRange(combined, 0, split);
        byte[] part2 = java.util.Arrays.copyOfRange(combined, split, combined.length);

        // 先写服务端出站测试帧，核对包成了 FIN=1 无掩码二进制帧（0x82）
        ch.writeOutbound(ClientHeartbeatResponse.newBuilder().setSeq(99).build());
        ByteBuf outHdr = ch.readOutbound();
        ByteBuf outBody = ch.readOutbound();
        assertThat((int) outHdr.getUnsignedByte(0)).isEqualTo(0x82);
        assertThat(outHdr.getUnsignedByte(1) & 0x80).isZero();
        outHdr.release();
        outBody.release();

        // 投喂：分片1(FIN=0,op=2) + Ping(op=9) + 续片2(FIN=1,op=0) + Close(1000)
        ByteBuf batch = Unpooled.wrappedBuffer(
                clientFrame(false, 0x2, true, part1),
                clientFrame(true, 0x9, true, "hi".getBytes(StandardCharsets.US_ASCII)),
                clientFrame(true, 0x0, true, part2),
                clientFrame(true, 0x8, true, new byte[]{0x03, (byte) 0xE8}));
        ch.writeInbound(batch);

        // 两条业务帧在 Close 之前全部交付
        ClientHeartbeatRequest r1 = ch.readInbound();
        ClientHeartbeatRequest r2 = ch.readInbound();
        assertThat(r1.getSeq()).isEqualTo(11);
        assertThat(r2.getSeq()).isEqualTo(22);

        // 出站应先有 Pong("hi") 再有回显 Close(1000)
        ByteBuf pongHdr = ch.readOutbound();
        ByteBuf pongBody = ch.readOutbound();
        assertThat((int) pongHdr.getUnsignedByte(0)).isEqualTo(0x8A);
        assertThat(pongBody.toString(StandardCharsets.US_ASCII)).isEqualTo("hi");
        pongHdr.release();
        pongBody.release();

        ByteBuf closeResp = ch.readOutbound();
        assertThat((int) closeResp.getUnsignedByte(0)).isEqualTo(0x88);
        assertThat(closeResp.getUnsignedShort(2)).isEqualTo(1000);
        closeResp.release();
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void 握手版本错误回426_非升级GET回400_八类违规帧回对应关闭码() {
        List<String> rejected = new ArrayList<>();
        List<Integer> codes = new ArrayList<>();
        WebSocketObserver obs = new WebSocketObserver() {
            @Override public void onHandshakeRejected(String reason) { rejected.add(reason); }
            @Override public void onProtocolClosed(int closeCode) { codes.add(closeCode); }
        };

        // 1. Version 8 -> 426
        EmbeddedChannel c426 = newChannel(obs);
        c426.writeInbound(Unpooled.copiedBuffer(
                "GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: " + RFC_KEY
                        + "\r\nSec-WebSocket-Version: 8\r\n\r\n", StandardCharsets.US_ASCII));
        ByteBuf b426 = c426.readOutbound();
        assertThat(b426.toString(StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 426 Upgrade Required")
                .contains("Sec-WebSocket-Version: 13");
        b426.release();
        assertThat(c426.isOpen()).isFalse();

        // 2. 普通 HTTP GET -> 400
        EmbeddedChannel c400 = newChannel(obs);
        c400.writeInbound(Unpooled.copiedBuffer("GET /health HTTP/1.1\r\nHost: x\r\n\r\n", StandardCharsets.US_ASCII));
        ByteBuf b400 = c400.readOutbound();
        assertThat(b400.toString(StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 400 Bad Request");
        b400.release();
        assertThat(rejected).contains("unsupported_version", "missing_upgrade_websocket");

        // 3. 文本帧 -> 1003
        assertViolationCode(obs, clientFrame(true, 0x1, true, new byte[]{1}), 1003);
        // 4. 未加掩码 -> 1002
        assertViolationCode(obs, clientFrame(true, 0x2, false, new byte[]{1}), 1002);
        // 5. RSV 非 0 -> 1002
        byte[] rsvFrame = clientFrame(true, 0x2, true, new byte[]{1});
        rsvFrame[0] |= 0x40;
        assertViolationCode(obs, rsvFrame, 1002);
        // 6. 保留操作码 0x3 -> 1002
        assertViolationCode(obs, clientFrame(true, 0x3, true, new byte[]{1}), 1002);
        // 7. 控制帧分片 -> 1002
        assertViolationCode(obs, clientFrame(false, 0x9, true, new byte[]{1}), 1002);
        // 8. 控制帧载荷 > 125 -> 1002
        assertViolationCode(obs, clientFrame(true, 0x9, true, new byte[126]), 1002);
        // 9. 无起始帧的续帧 -> 1002
        assertViolationCode(obs, clientFrame(true, 0x0, true, new byte[]{1}), 1002);
        // 10. 载荷超上限 -> 1009
        EmbeddedChannel cBig = new EmbeddedChannel(
                new WebSocketMuxHandler(64, obs),
                new ClientFrameDecoder(ACCEPTED, ClientFrames.DEFAULT_MAX_LEN, (ctx, e) -> {}),
                ClientFrameEncoder.INSTANCE);
        upgrade(cBig);
        cBig.writeInbound(Unpooled.wrappedBuffer(clientFrame(true, 0x2, true, new byte[65])));
        ByteBuf closeBig = cBig.readOutbound();
        assertThat(closeBig.getUnsignedShort(2)).isEqualTo(1009);
        closeBig.release();
        assertThat(cBig.isOpen()).isFalse();
        assertThat(codes).contains(1003, 1002, 1009);
    }

    private static void assertViolationCode(WebSocketObserver obs, byte[] frame, int expectedCloseCode) {
        EmbeddedChannel ch = newChannel(obs);
        upgrade(ch);
        ch.writeInbound(Unpooled.wrappedBuffer(frame));
        ByteBuf close = ch.readOutbound();
        assertThat(close).isNotNull();
        assertThat((int) close.getUnsignedByte(0)).isEqualTo(0x88);
        assertThat(close.getUnsignedShort(2)).isEqualTo(expectedCloseCode);
        close.release();
        assertThat(ch.isOpen()).isFalse();
    }

    private static void upgrade(EmbeddedChannel ch) {
        String req = "GET / HTTP/1.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + RFC_KEY + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        ch.writeInbound(Unpooled.copiedBuffer(req, StandardCharsets.US_ASCII));
        ByteBuf resp = ch.readOutbound();
        resp.release();
    }

    private static EmbeddedChannel newChannel(WebSocketObserver obs) {
        return new EmbeddedChannel(
                new WebSocketMuxHandler(obs),
                new ClientFrameDecoder(ACCEPTED, ClientFrames.DEFAULT_MAX_LEN, (ctx, e) -> {}),
                ClientFrameEncoder.INSTANCE);
    }

    private static byte[] clientFrame(boolean fin, int opcode, boolean masked, byte[] payload) {
        int len = payload.length;
        int hdrLen = 2 + (len < 126 ? 0 : len <= 0xFFFF ? 2 : 8) + (masked ? 4 : 0);
        byte[] out = new byte[hdrLen + len];
        out[0] = (byte) ((fin ? 0x80 : 0) | (opcode & 0x0F));
        int pos = 2;
        if (len < 126) {
            out[1] = (byte) ((masked ? 0x80 : 0) | len);
        } else if (len <= 0xFFFF) {
            out[1] = (byte) ((masked ? 0x80 : 0) | 126);
            out[2] = (byte) (len >> 8);
            out[3] = (byte) len;
            pos = 4;
        } else {
            out[1] = (byte) ((masked ? 0x80 : 0) | 127);
            for (int i = 0; i < 8; i++) {
                out[2 + i] = (byte) (((long) len) >>> (56 - 8 * i));
            }
            pos = 10;
        }
        byte[] mask = {0x37, (byte) 0xFA, 0x21, 0x3D};
        if (masked) {
            System.arraycopy(mask, 0, out, pos, 4);
            pos += 4;
        }
        for (int i = 0; i < len; i++) {
            out[pos + i] = masked ? (byte) (payload[i] ^ mask[i & 3]) : payload[i];
        }
        return out;
    }
}
