package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.proto.ClientReply;
import com.game.api.proto.EnterScene;
import com.game.api.proto.PlayerTransfer;
import com.game.api.proto.SessionDirective;
import com.game.api.proto.ZoneRedirect;
import com.game.common.token.GateTokens;
import com.game.gate.metrics.GateMetrics;
import com.game.net.client.ClientFrames;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.game.proto.MessageContent;
import com.game.proto.RedirectToGateNotify;
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
import java.util.Arrays;
import java.util.HexFormat;
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

    /** 2026-10-15 起未进场景回带请求 id 的信封 1003（原为 23 号 tip 帧；mmorpg client-request-ordering.md O6，两版同批）。 */
    @Test
    void 握手后scene请求未进场景_收到带请求id的信封1003() throws Exception {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(verifyRequest(GATE_NODE), ' ')));
        ((ByteBuf) ch.readOutbound()).release();

        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(ClientRequest.newBuilder().setId(3).setMessageId(77).build(), ' ')));
        MessageContent envelope = (MessageContent) decode(ch.readOutbound());
        assertThat(envelope.getMessageId()).as("以请求号为 message_id，不是 23").isEqualTo(77);
        assertThat(envelope.getId()).as("带请求 id").isEqualTo(3);
        assertThat(envelope.getSerializedMessage()).as("body 为空").isEmpty();
        assertThat(envelope.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat(ch.isOpen()).isTrue();
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
        assertThat(meters.get("game.gate.client.invalid.frames").tag("reason", "unknown_type").counter().count()).isEqualTo(1);
        assertThat(meters.get("game.gate.disconnects").tag("reason", "invalid_frame").counter().count()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 逐帧分发（基线 codec.cpp:158-191）

    @Test
    void 同一次读里握手与请求后跟坏帧_先回握手应答与请求的回包_然后才断开() throws Exception {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(
                robotFrame(verifyRequest(GATE_NODE), ' '),
                robotFrame(ClientRequest.newBuilder().setId(3).setMessageId(77).build(), ' '),
                corruptFrame(),
                robotFrame(ClientRequest.newBuilder().setId(4).setMessageId(77).build(), ' ')));

        ClientTokenVerifyResponse verified = (ClientTokenVerifyResponse) decode(ch.readOutbound());
        assertThat(verified.getSuccess()).as("先到的握手照常通过并回包").isTrue();
        MessageContent envelope = (MessageContent) decode(ch.readOutbound());
        assertThat(envelope.getMessageId()).as("先到的请求照常处理（未进场景 → 带请求 id 的信封 1003）").isEqualTo(77);
        assertThat(envelope.getId()).isEqualTo(3);
        assertThat(envelope.getErrorMessage().getId()).isEqualTo(ClientDispatcher.TIP_SERVICE_UNAVAILABLE);
        assertThat((Object) ch.readOutbound()).as("坏帧之后的帧随缓冲一起丢弃").isNull();
        assertThat(ch.isOpen()).isFalse();
        assertThat(meters.get("game.gate.client.invalid.frames").tag("reason", "checksum").counter().count()).isEqualTo(1);
        assertThat(meters.get("game.gate.disconnects").tag("reason", "invalid_frame").counter().count()).isEqualTo(1);
    }

    @Test
    void 同一次读里被拒的握手后跟坏帧_只收到拒绝应答_坏帧不解析不计非法帧() throws Exception {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(verifyRequest(GATE_NODE + 1), ' '), corruptFrame()));

        ClientTokenVerifyResponse rejected = (ClientTokenVerifyResponse) decode(ch.readOutbound());
        assertThat(rejected.getSuccess()).isFalse();
        assertThat(rejected.getError()).isEqualTo("token not for this gate");
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(ch.isOpen()).isFalse();
        assertThat(meters.get("game.gate.client.invalid.frames").tag("reason", "checksum").counter().count())
                .as("会话已决定关闭，剩余字节不再解析").isZero();
        assertThat(meters.get("game.gate.disconnects").tag("reason", "handshake_rejected").counter().count()).isEqualTo(1);
        assertThat(meters.get("game.gate.disconnects").tag("reason", "invalid_frame").counter().count())
                .as("每条连接只计一个断开原因").isZero();
    }

    @Test
    void 同一次读里握手前的请求后跟坏帧_不回包断开_坏帧不计非法帧() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(
                robotFrame(ClientRequest.newBuilder().setId(1).setMessageId(77).build(), ' '), corruptFrame()));

        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(ch.isOpen()).isFalse();
        assertThat(meters.get("game.gate.disconnects").tag("reason", "no_handshake").counter().count()).isEqualTo(1);
        assertThat(meters.get("game.gate.disconnects").tag("reason", "invalid_frame").counter().count()).isZero();
        assertThat(meters.get("game.gate.client.invalid.frames").tag("reason", "checksum").counter().count()).isZero();
    }

    /**
     * 批次 5.4 先行件：生产装配走带 {@link PushMessageIds} 的新构造器（tip 与重定向通知两个号一起给）。握手与 tip 推送
     * 的字节与只给 tip 号的旧构造器完全一样——tip 用的仍是 {@code tip()}，不会因为多带了一个号而发错消息号。
     * 2026-10-15 起「未进场景」改回带请求 id 的信封（mmorpg client-request-ordering.md O6，两版同批），不再经 {@code tip()}；
     * 这里改用仍是推送的进场失败（login 下发进场、到 scene 的链路层不可用 → 推 23 {3023}）来走 {@code tip()}。
     */
    @Test
    void 带两个推送消息号的新构造器_握手与tip推送同旧构造器_tip不会发成重定向的号() throws Exception {
        SessionRegistry sessions = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
        FakeLogin login = new FakeLogin();
        FakeLinks links = new FakeLinks();
        links.generation = 0; // 链路层不可用（send 返回 0）：进场失败，推 23 {3023}
        ClientDispatcher wired = new ClientDispatcher(
                new GateIdentity(GATE_NODE, "gate-uuid", 1), tokens, InstantSource.fixed(Instant.ofEpochSecond(NOW)),
                id -> id == 26 ? new MessageRoute(id, "login") : null, new PushMessageIds(23, 124), login, Map.of(),
                links, sessions, new GateLimits(8, 50, Duration.ZERO, com.game.net.limit.MessageLimits.UNLIMITED,
                        false, Duration.ofSeconds(15)), new GateMetrics(new SimpleMeterRegistry()), PresenceRecorder.NONE);
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel c) {
                ClientPipeline.install(c.pipeline(), sessions, wired);
            }
        });

        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(verifyRequest(GATE_NODE), ' ')));
        assertThat(((ClientTokenVerifyResponse) decode(ch.readOutbound())).getSuccess()).isTrue();
        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(ClientRequest.newBuilder().setId(3).setMessageId(26).build(), ' ')));
        login.complete(ClientReply.newBuilder()
                .addDirectives(SessionDirective.newBuilder().setEnterScene(EnterScene.newBuilder()
                        .setPlayerId(42).setSceneNodeId(7).setSceneId(900).setOwnerEpoch(5)))
                .build());
        ch.runPendingTasks();
        MessageContent reply = (MessageContent) decode(ch.readOutbound());
        assertThat(reply.getMessageId()).as("先回 26 的应答").isEqualTo(26);
        assertThat(reply.getId()).isEqualTo(3);

        MessageContent tip = (MessageContent) decode(ch.readOutbound());
        assertThat(tip.getMessageId()).isEqualTo(23);
        assertThat(tip.getId()).as("推送形状，不带请求 id").isZero();
        assertThat(TipInfoMessage.parseFrom(tip.getSerializedMessage()).getId()).isEqualTo(ClientDispatcher.TIP_ENTER_SCENE_FAILED);
        assertThat((Object) ch.readOutbound()).as("进场失败只推 tip，不会发 124").isNull();
        assertThat(ch.isOpen()).isTrue();
    }

    // ---------------------------------------------------------------- 124 RedirectToGateNotify 的线上字节（批次 5.4，zone-travel-spec §4.5）

    /** 金样里的票据签名：64 字节小写 hex ASCII 的形状（gate 不验签，只原样转发）。 */
    private static final String GOLDEN_SIGNATURE = "0123456789abcdef".repeat(4);
    /**
     * 金样里的票据原字节：故意不是规范序列化（zone_id 排在 gate_node_id 前面），gate 若解析后重新序列化就会变序，
     * 字节与签名对不上。依次是 zone_id = 2、gate_node_id = 1、expire_timestamp = 1800000300、player_id = 42、target_zone_id = 2。
     */
    private static final String GOLDEN_TICKET_HEX = "1002" + "0801" + "18aca6a7da06" + "282a" + "3002";
    /**
     * 124 的信封 {@code MessageContent} 字节，按 proto3 线格式手工排出（不取自被测代码）：
     * 字段 1 serialized_message（0x0a，长 0x66 = 102）里是 {@code RedirectToGateNotify}：
     * target_ip（0x0a，9 字节 "127.0.0.1"）、target_port（0x10，varint 11010 = 82 56）、token_payload（0x1a，14 字节票据原样）、
     * token_signature（0x22，64 字节）、token_deadline（0x28，varint 1800000300 = ac a6 a7 da 06）；
     * 字段 2 message_id（0x10，124 = 0x7c）；字段 3 id 不设（推送没有请求 id），字段 4 error_message 不设。
     */
    private static final String GOLDEN_124_HEX = "0a66"
            + "0a09" + "3132372e302e302e31"
            + "108256"
            + "1a0e" + GOLDEN_TICKET_HEX
            + "2240" + "30313233343536373839616263646566".repeat(4)
            + "28aca6a7da06"
            + "107c";

    @Test
    void 重定向通知124的线上字节金样_五项逐字段等于帧_票据原字节不重排_信封不带id() throws Exception {
        SessionRegistry sessions = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
        FakeLogin login = new FakeLogin();
        FakeLinks links = new FakeLinks();
        ClientDispatcher wired = new ClientDispatcher(
                new GateIdentity(GATE_NODE, "gate-uuid", 1), tokens, InstantSource.fixed(Instant.ofEpochSecond(NOW)),
                id -> id == 26 ? new MessageRoute(id, "login") : null, new PushMessageIds(23, 124), login, Map.of(),
                links, sessions, new GateLimits(8, 50, Duration.ZERO), new GateMetrics(new SimpleMeterRegistry()),
                PresenceRecorder.NONE);
        SceneEventRouter router = new SceneEventRouter(sessions, wired);
        EmbeddedChannel ch = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel c) {
                ClientPipeline.install(c.pipeline(), sessions, wired);
            }
        });
        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(verifyRequest(GATE_NODE), ' ')));
        ((ByteBuf) ch.readOutbound()).release();
        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(ClientRequest.newBuilder().setId(1).setMessageId(26).build(), ' ')));
        login.complete(ClientReply.newBuilder()
                .addDirectives(SessionDirective.newBuilder().setEnterScene(EnterScene.newBuilder()
                        .setPlayerId(42).setSceneNodeId(7).setSceneId(900).setOwnerEpoch(5)))
                .build());
        ch.runPendingTasks();
        assertThat(((MessageContent) decode(ch.readOutbound())).getMessageId()).isEqualTo(26);
        int sessionId = sessions.all().iterator().next().sessionId();
        ZoneRedirect redirect = ZoneRedirect.newBuilder()
                .setTargetZoneId(2).setGateNodeId(1).setGateHost("127.0.0.1").setGatePort(11010)
                .setTokenPayload(ByteString.copyFrom(HexFormat.of().parseHex(GOLDEN_TICKET_HEX)))
                .setTokenSignature(ByteString.copyFromUtf8(GOLDEN_SIGNATURE))
                .setTokenDeadline(1_800_000_300L)
                .build();

        router.onPlayerTransfer(7, links.generation, PlayerTransfer.newBuilder()
                .setSessionId(sessionId).setPlayerId(42).setFromEpoch(5).setToEpoch(6).setRedirect(redirect).build());
        ch.runPendingTasks();

        ByteBuf out = ch.readOutbound();
        byte[] raw = new byte[out.readableBytes()];
        out.getBytes(out.readerIndex(), raw);
        int nameLen = out.getInt(4);
        assertThat(new String(raw, 8, nameLen, StandardCharsets.US_ASCII)).isEqualTo("MessageContent\0");
        byte[] body = Arrays.copyOfRange(raw, 8 + nameLen, raw.length - 4);
        assertThat(HexFormat.of().formatHex(body)).isEqualTo(GOLDEN_124_HEX);

        MessageContent content = (MessageContent) decode(out);
        assertThat(content.getId()).isZero();
        assertThat(content.hasErrorMessage()).isFalse();
        RedirectToGateNotify notify = RedirectToGateNotify.parseFrom(content.getSerializedMessage());
        assertThat(notify).isEqualTo(RedirectToGateNotify.newBuilder()
                .setTargetIp(redirect.getGateHost())
                .setTargetPort(redirect.getGatePort())
                .setTokenPayload(redirect.getTokenPayload())
                .setTokenSignature(redirect.getTokenSignature())
                .setTokenDeadline(redirect.getTokenDeadline())
                .build());
        assertThat((Object) ch.readOutbound()).isNull();
        assertThat(ch.isOpen()).as("推 124 不断开").isTrue();
    }

    // ---------------------------------------------------------------- 工具

    /** 校验和错误的一帧（解码层判非法、立即断开）。 */
    private static byte[] corruptFrame() {
        byte[] frame = robotFrame(ClientRequest.newBuilder().setId(9).setMessageId(77).build(), ' ');
        frame[frame.length - 1] ^= 0x5A;
        return frame;
    }

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
