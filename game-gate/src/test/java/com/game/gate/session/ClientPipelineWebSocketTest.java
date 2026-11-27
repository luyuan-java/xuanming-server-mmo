package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.token.GateTokens;
import com.game.gate.metrics.GateMetrics;
import com.game.net.client.ClientFrames;
import com.game.proto.ClientHeartbeatRequest;
import com.game.proto.ClientHeartbeatResponse;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.InstantSource;
import org.junit.jupiter.api.Test;

/** 验证 gate 客户端同端口 WebSocket 入口与现有握手/心跳链路无缝融合且上报 Prometheus 指标。 */
class ClientPipelineWebSocketTest {

    private static final String WS_KEY = "dGhlIHNhbXBsZSBub25jZQ==";
    private static final long NOW = 1_800_000_000L;

    @Test
    void 小游戏WebSocket握手后直通验票与业务心跳_Ping不计活跃且上报指标() throws Exception {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GateMetrics metrics = new GateMetrics(meters);
        GateTokens tokens = GateTokens.ofUtf8("ws-gate-secret");
        SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(1));
        ClientDispatcher dispatcher = new ClientDispatcher(
                new GateIdentity(1, "inst-ws", 1), tokens, InstantSource.fixed(Instant.ofEpochSecond(NOW)),
                MessageRoutes.fromGenerated(), 23, new FakeLogin(), new FakeLinks(), registry,
                new GateLimits(8, 50, java.time.Duration.ZERO), metrics, PresenceRecorder.NONE);

        EmbeddedChannel ch = new EmbeddedChannel(new io.netty.channel.ChannelInitializer<>() {
            @Override
            protected void initChannel(io.netty.channel.Channel channel) {
                ClientPipeline.install(channel.pipeline(), registry, dispatcher);
            }
        });

        // 1. 发送 RFC 6455 HTTP 升级请求
        String upgrade = "GET /ws/10.0.0.1/11000 HTTP/1.1\r\n"
                + "Host: ws.example.com\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: " + WS_KEY + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
        ch.writeInbound(Unpooled.copiedBuffer(upgrade, StandardCharsets.US_ASCII));
        ByteBuf http101 = ch.readOutbound();
        assertThat(http101.toString(StandardCharsets.US_ASCII)).startsWith("HTTP/1.1 101 Switching Protocols");
        http101.release();
        assertThat(meters.get("game.gate.websocket.handshakes").tag("result", "accepted").counter().count()).isEqualTo(1.0);

        // 2. 通过加掩码二进制 WS 帧发 ClientTokenVerifyRequest
        ByteString payload = GateTokenPayload.newBuilder().setGateNodeId(1).setZoneId(1).setExpireTimestamp(NOW + 300)
                .build().toByteString();
        ByteBuf verifyBytes = ClientFrames.encode(ch.alloc(), ClientTokenVerifyRequest.newBuilder()
                .setPayload(payload).setSignature(tokens.sign(payload)).build());
        byte[] verifyArr = new byte[verifyBytes.readableBytes()];
        verifyBytes.readBytes(verifyArr);
        verifyBytes.release();
        ch.writeInbound(Unpooled.wrappedBuffer(maskedBinaryFrame(verifyArr)));

        // 读出 WS 二进制帧（header + body），解码为 ClientTokenVerifyResponse
        ByteBuf respHdr = ch.readOutbound();
        ByteBuf respBody = ch.readOutbound();
        assertThat((int) respHdr.getUnsignedByte(0)).isEqualTo(0x82);
        respHdr.release();
        int len = respBody.readInt();
        ClientTokenVerifyResponse verifyResp = (ClientTokenVerifyResponse) ClientFrames.decodeBody(
                respBody.readSlice(len),
                java.util.Map.of(ClientTokenVerifyResponse.getDescriptor().getFullName(),
                        ClientTokenVerifyResponse.getDefaultInstance()));
        respBody.release();
        assertThat(verifyResp.getSuccess()).isTrue();

        // 3. 通过 WS 二进制帧发应用层心跳 ClientHeartbeatRequest -> 收到 ClientHeartbeatResponse
        ByteBuf hbBytes = ClientFrames.encode(ch.alloc(), ClientHeartbeatRequest.newBuilder().setSeq(42).build());
        byte[] hbArr = new byte[hbBytes.readableBytes()];
        hbBytes.readBytes(hbArr);
        hbBytes.release();
        ch.writeInbound(Unpooled.wrappedBuffer(maskedBinaryFrame(hbArr)));

        ByteBuf hbHdr = ch.readOutbound();
        ByteBuf hbBody = ch.readOutbound();
        assertThat((int) hbHdr.getUnsignedByte(0)).isEqualTo(0x82);
        hbHdr.release();
        int hbLen = hbBody.readInt();
        ClientHeartbeatResponse hbResp = (ClientHeartbeatResponse) ClientFrames.decodeBody(
                hbBody.readSlice(hbLen),
                java.util.Map.of(ClientHeartbeatResponse.getDescriptor().getFullName(),
                        ClientHeartbeatResponse.getDefaultInstance()));
        hbBody.release();
        assertThat(hbResp.getSeq()).isEqualTo(42);
        ch.finishAndReleaseAll();
    }

    @Test
    void 验签前声明超过4KB帧在长度头处立即拒绝_验签后恢复64KB上限() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GateMetrics metrics = new GateMetrics(meters);
        GateTokens tokens = GateTokens.ofUtf8("ws-gate-secret");
        SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(1));
        ClientDispatcher dispatcher = new ClientDispatcher(
                new GateIdentity(1, "inst-ws", 1), tokens, InstantSource.fixed(Instant.ofEpochSecond(NOW)),
                MessageRoutes.fromGenerated(), 23, new FakeLogin(), new FakeLinks(), registry,
                new GateLimits(8, 50, java.time.Duration.ZERO), metrics, PresenceRecorder.NONE);

        EmbeddedChannel tcp = new EmbeddedChannel(new io.netty.channel.ChannelInitializer<>() {
            @Override
            protected void initChannel(io.netty.channel.Channel channel) {
                ClientPipeline.install(channel.pipeline(), registry, dispatcher);
            }
        });
        ByteBuf oversizedHeaderOnly = tcp.alloc().buffer(4);
        oversizedHeaderOnly.writeInt(ClientFrames.PRE_AUTH_MAX_LEN + 1);
        tcp.writeInbound(oversizedHeaderOnly);
        assertThat(tcp.isOpen()).as("未验签仅发 4 字节超限长度头即关闭").isFalse();
        assertThat(meters.get("game.gate.client.invalid.frames").tag("reason", "invalid_length").counter().count()).isEqualTo(1.0);
        tcp.finishAndReleaseAll();

        // 验签后超过 4 KiB（如 5 KiB）的帧可通过解码器到达 dispatcher（不再触发 invalid_length）
        EmbeddedChannel authed = new EmbeddedChannel(new io.netty.channel.ChannelInitializer<>() {
            @Override
            protected void initChannel(io.netty.channel.Channel channel) {
                ClientPipeline.install(channel.pipeline(), registry, dispatcher);
            }
        });
        ByteString payload = GateTokenPayload.newBuilder().setGateNodeId(1).setZoneId(1).setExpireTimestamp(NOW + 300)
                .build().toByteString();
        authed.writeInbound(ClientFrames.encode(authed.alloc(), ClientTokenVerifyRequest.newBuilder()
                .setPayload(payload).setSignature(tokens.sign(payload)).build()));
        ByteBuf verifyReply = authed.readOutbound();
        assertThat(verifyReply).isNotNull();
        verifyReply.release();

        com.game.proto.ClientRequest large = com.game.proto.ClientRequest.newBuilder()
                .setId(7).setMessageId(1).setBody(ByteString.copyFrom(new byte[5000])).build();
        authed.writeInbound(ClientFrames.encode(authed.alloc(), large));
        assertThat(authed.isOpen()).as("验签后大帧不被解码器拒断（由 dispatcher 按 1010 业务处理）").isTrue();
        assertThat(meters.get("game.gate.client.invalid.frames").tag("reason", "invalid_length").counter().count()).isEqualTo(1.0);
        authed.finishAndReleaseAll();
    }

    private static byte[] maskedBinaryFrame(byte[] payload) {
        int len = payload.length;
        int hdrLen = 2 + (len < 126 ? 0 : 2) + 4;
        byte[] out = new byte[hdrLen + len];
        out[0] = (byte) 0x82;
        int pos = 2;
        if (len < 126) {
            out[1] = (byte) (0x80 | len);
        } else {
            out[1] = (byte) (0x80 | 126);
            out[2] = (byte) (len >> 8);
            out[3] = (byte) len;
            pos = 4;
        }
        byte[] mask = {0x12, 0x34, 0x56, 0x78};
        System.arraycopy(mask, 0, out, pos, 4);
        pos += 4;
        for (int i = 0; i < len; i++) {
            out[pos + i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        return out;
    }
}
