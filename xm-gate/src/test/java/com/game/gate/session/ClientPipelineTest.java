package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.token.GateTokens;
import com.game.gate.metrics.GateMetrics;
import com.game.net.client.ClientFrames;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.zip.Adler32;
import org.junit.jupiter.api.Test;

/** 线上字节层面的握手与拒绝：用 Go robot 的写法拼帧（短名 + 空格结尾），读回 gate 下发的帧。 */
class ClientPipelineTest {

    private static final long NOW = 1_800_000_000L;
    private static final int GATE_NODE = 4;
    private static final Map<String, Message> DOWNSTREAM = Map.of(
            "ClientTokenVerifyResponse", ClientTokenVerifyResponse.getDefaultInstance(),
            "MessageContent", MessageContent.getDefaultInstance());

    private final GateTokens tokens = GateTokens.ofUtf8("pipeline-secret");
    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final ClientDispatcher dispatcher = new ClientDispatcher(
            new GateIdentity(GATE_NODE, "gate-uuid", 1), tokens, InstantSource.fixed(Instant.ofEpochSecond(NOW)),
            id -> id == 77 ? new MessageRoute(id, "scene") : null, 23, new FakeLogin(), new FakeLinks(), registry,
            new GateLimits(8, 50, Duration.ZERO), new GateMetrics(meters), PresenceRecorder.NONE);

    @Test
    void robot写法的握手帧通过_下发帧名是全名加零结尾() throws Exception {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(verifyRequest(GATE_NODE), ' ')));

        ByteBuf out = ch.readOutbound();
        byte[] raw = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), raw);
        int nameLen = out.getInt(4);
        assertThat(new String(raw, 8, nameLen, StandardCharsets.US_ASCII)).isEqualTo("ClientTokenVerifyResponse\0");

        ClientTokenVerifyResponse response = (ClientTokenVerifyResponse) decode(out);
        assertThat(response.getSuccess()).isTrue();
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void 握手后scene请求未进场景_收到23号tip帧() throws Exception {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(verifyRequest(GATE_NODE), ' ')));
        ((ByteBuf) ch.readOutbound()).release();

        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(ClientRequest.newBuilder().setId(3).setMessageId(77).build(), ' ')));
        MessageContent tip = (MessageContent) decode(ch.readOutbound());
        assertThat(tip.getMessageId()).isEqualTo(23);
        assertThat(TipInfoMessage.parseFrom(tip.getSerializedMessage()).getId()).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
    }

    @Test
    void 握手失败回失败帧后断开() throws Exception {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(verifyRequest(GATE_NODE + 1), ' ')));
        ClientTokenVerifyResponse response = (ClientTokenVerifyResponse) decode(ch.readOutbound());
        assertThat(response.getSuccess()).isFalse();
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void 白名单外的上行类型直接断开不回包() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(MessageContent.newBuilder().setMessageId(48).build(), ' ')));
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(ch.isOpen()).isFalse();
        assertThat(registry.size()).isZero();
        assertThat(meters.get("xm.gate.client.invalid.frames").tag("reason", "unknown_type").counter().count()).isEqualTo(1);
        assertThat(meters.get("xm.gate.disconnects").tag("reason", "invalid_frame").counter().count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 工具

    private EmbeddedChannel channel() {
        return new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                ClientPipeline.install(ch.pipeline(), registry, dispatcher);
            }
        });
    }

    private ClientTokenVerifyRequest verifyRequest(int gateNodeId) {
        ByteString payload = GateTokenPayload.newBuilder()
                .setGateNodeId(gateNodeId).setZoneId(1).setExpireTimestamp(NOW + 600).build().toByteString();
        return ClientTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tokens.sign(payload)).build();
    }

    private static Message decode(ByteBuf out) throws Exception {
        try {
            int len = out.readInt();
            return ClientFrames.decodeBody(out.readSlice(len), DOWNSTREAM);
        } finally {
            out.release();
        }
    }

    /** 按 Go robot 的写法手工拼帧：typeName 用短名 + 结尾符。 */
    private static byte[] robotFrame(Message msg, char terminator) {
        byte[] name = (msg.getDescriptorForType().getName() + terminator).getBytes(StandardCharsets.US_ASCII);
        byte[] body = msg.toByteArray();
        int len = 4 + name.length + body.length + 4;
        ByteBuf buf = Unpooled.buffer();
        buf.writeInt(len);
        buf.writeInt(name.length);
        buf.writeBytes(name);
        buf.writeBytes(body);
        Adler32 adler = new Adler32();
        adler.update(buf.array(), buf.arrayOffset() + 4, len - 4);
        buf.writeInt((int) adler.getValue());
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }
}
