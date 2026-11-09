package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.common.token.GateTokens;
import com.game.gate.metrics.GateMetrics;
import com.game.net.client.ClientFrames;
import com.game.proto.ClientHeartbeatRequest;
import com.game.proto.ClientHeartbeatResponse;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.game.proto.MessageContent;
import com.google.protobuf.Message;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 正式编解码与会话链路，时钟和 Netty 定时器一起推进，不睡眠。 */
class ClientHeartbeatTest {
    private static final Map<String, Message> DOWNSTREAM = Map.of(
            "ClientTokenVerifyResponse", ClientTokenVerifyResponse.getDefaultInstance(),
            "ClientHeartbeatResponse", ClientHeartbeatResponse.getDefaultInstance(),
            "MessageContent", MessageContent.getDefaultInstance());

    private static final class Fixture implements AutoCloseable {
        final AtomicLong time = new AtomicLong();
        final GateTokens tokens = GateTokens.ofUtf8("heartbeat-test-secret");
        final SessionRegistry sessions = new SessionRegistry(new SessionIdAllocator(3));
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final ClientDispatcher dispatcher = new ClientDispatcher(new GateIdentity(3, "gate-uuid", 1), tokens,
                InstantSource.fixed(Instant.ofEpochSecond(100)), id -> null, 23, new FakeLogin(), new FakeLinks(),
                sessions, new GateLimits(8, 2, Duration.ofSeconds(10)), new GateMetrics(meters), PresenceRecorder.NONE,
                time::get);
        final EmbeddedChannel channel = new EmbeddedChannel(new ChannelInitializer<Channel>() {
            @Override protected void initChannel(Channel ch) { ClientPipeline.install(ch.pipeline(), sessions, dispatcher); }
        });

        Fixture() { channel.freezeTime(); }
        void send(Message message) { channel.writeInbound(ClientFrames.encode(ByteBufAllocator.DEFAULT, message)); }
        ClientTokenVerifyResponse verify() throws Exception {
            var payload = GateTokenPayload.newBuilder().setGateNodeId(3).setExpireTimestamp(1000).build().toByteString();
            send(ClientTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tokens.sign(payload)).build());
            return (ClientTokenVerifyResponse) read();
        }
        Message read() throws Exception {
            ByteBuf data = channel.readOutbound();
            if (data == null) return null;
            try { data.skipBytes(4); return ClientFrames.decodeBody(data, DOWNSTREAM); }
            finally { data.release(); }
        }
        void advance(long millis) {
            time.addAndGet(TimeUnit.MILLISECONDS.toNanos(millis));
            channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
        }
        double count(String result) { return meters.get("game.gate.client.heartbeat").tag("result", result).counter().count(); }
        public void close() { channel.finishAndReleaseAll(); meters.close(); }
    }

    @Test void 验签通告参数且重复验签只跟踪一次() throws Exception {
        try (var f = new Fixture()) {
            var response = f.verify();
            assertThat(response.getSuccess()).isTrue();
            assertThat(response.getHeartbeatIntervalMs()).isEqualTo(10_000);
            assertThat(response.getHeartbeatTimeoutMs()).isEqualTo(30_000);
            assertThat(f.verify()).isEqualTo(response);
            assertThat(f.count("tracked")).isEqualTo(1);
        }
    }

    @Test void 验签前心跳直接断开且不回包() throws Exception {
        try (var f = new Fixture()) {
            f.send(ClientHeartbeatRequest.newBuilder().setSeq(1).build());
            assertThat(f.channel.isOpen()).isFalse();
            assertThat(f.read()).isNull();
            assertThat(f.count("before_verify")).isEqualTo(1);
        }
    }

    @Test void 窗口前三条回显第四条静默并累计非法包() throws Exception {
        try (var f = new Fixture()) {
            f.verify();
            for (long seq : new long[]{1, 2, -1}) {
                f.send(ClientHeartbeatRequest.newBuilder().setSeq(seq).build());
                assertThat(f.read()).isEqualTo(ClientHeartbeatResponse.newBuilder().setSeq(seq).build());
            }
            f.send(ClientHeartbeatRequest.getDefaultInstance());
            assertThat(f.read()).isNull();
            assertThat(f.sessions.all().iterator().next().illegalPackets).isEqualTo(1);
            assertThat(f.channel.isOpen()).isTrue();
            f.send(ClientHeartbeatRequest.getDefaultInstance());
            assertThat(f.channel.isOpen()).isFalse();
            assertThat(f.count("answered")).isEqualTo(3);
            assertThat(f.count("excess")).isEqualTo(2);
        }
    }

    @Test void 恰满十秒窗口重置() throws Exception {
        try (var f = new Fixture()) {
            f.verify();
            for (int i = 0; i < 3; i++) { f.send(ClientHeartbeatRequest.getDefaultInstance()); f.read(); }
            f.advance(10_000);
            f.send(ClientHeartbeatRequest.newBuilder().setSeq(4).build());
            assertThat(f.read()).isEqualTo(ClientHeartbeatResponse.newBuilder().setSeq(4).build());
            assertThat(f.count("excess")).isZero();
        }
    }

    @Test void 验签后一帧不发到三十秒关且只关一次() throws Exception {
        try (var f = new Fixture()) {
            f.verify();
            f.advance(29_999);
            assertThat(f.channel.isOpen()).isTrue();
            f.advance(1);
            assertThat(f.channel.isOpen()).isFalse();
            f.advance(30_000);
            assertThat(f.count("idle_closed")).isEqualTo(1);
            assertThat(f.sessions.size()).isZero();
        }
    }

    @Test void 被拒业务完整帧也续期() throws Exception {
        try (var f = new Fixture()) {
            f.verify();
            f.advance(20_000);
            f.send(ClientRequest.newBuilder().setMessageId(Integer.MAX_VALUE).build());
            f.advance(10_000);
            assertThat(f.channel.isOpen()).isTrue();
            f.advance(19_999);
            assertThat(f.channel.isOpen()).isTrue();
            f.advance(1);
            assertThat(f.channel.isOpen()).isFalse();
        }
    }

    @Test void 验签后零碎字节不能续期() throws Exception {
        try (var f = new Fixture()) {
            f.verify();
            f.advance(20_000);
            f.channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0}));
            f.advance(10_000);
            assertThat(f.channel.isOpen()).isFalse();
        }
    }

    @Test void 未验签十秒关闭且零碎字节不续期() {
        try (var f = new Fixture()) {
            f.advance(9_999);
            f.channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{0}));
            assertThat(f.channel.isOpen()).isTrue();
            f.advance(1);
            assertThat(f.channel.isOpen()).isFalse();
            assertThat(f.count("handshake_timeouts")).isEqualTo(1);
        }
    }

    @Test void 十秒前验签取消握手期限() throws Exception {
        try (var f = new Fixture()) {
            f.advance(9_999);
            f.verify();
            f.advance(1);
            assertThat(f.channel.isOpen()).isTrue();
            assertThat(f.count("handshake_timeouts")).isZero();
        }
    }

    @Test void 握手检查迟到给积压的验签一次机会() throws Exception {
        try (var f = new Fixture()) {
            f.advance(13_000);
            assertThat(f.channel.isOpen()).isTrue();
            assertThat(f.count("deferred")).isEqualTo(1);
            f.verify();
            f.advance(1_000);
            assertThat(f.channel.isOpen()).isTrue();
            assertThat(f.count("handshake_timeouts")).isZero();
        }
    }

    @Test void 握手检查迟到后仍无验签则宽限到期关() {
        try (var f = new Fixture()) {
            f.advance(13_000);
            f.advance(1_000);
            assertThat(f.channel.isOpen()).isFalse();
            assertThat(f.count("handshake_timeouts")).isEqualTo(1);
        }
    }

    @Test void 正常心跳持续保活() throws Exception {
        try (var f = new Fixture()) {
            f.verify();
            for (int i = 0; i < 12; i++) {
                f.send(ClientHeartbeatRequest.newBuilder().setSeq(i).build());
                assertThat(f.read()).isInstanceOf(ClientHeartbeatResponse.class);
                f.advance(10_000);
                assertThat(f.channel.isOpen()).isTrue();
            }
            assertThat(f.count("idle_closed")).isZero();
        }
    }

    @Test void 定时器迟到先宽限一秒恢复入站就不误关() throws Exception {
        try (var f = new Fixture()) {
            f.verify();
            f.advance(33_000);
            assertThat(f.channel.isOpen()).isTrue();
            assertThat(f.count("deferred")).isEqualTo(1);
            f.send(ClientHeartbeatRequest.getDefaultInstance());
            f.read();
            f.advance(1_000);
            assertThat(f.channel.isOpen()).isTrue();
        }
    }

    @Test void 定时器迟到后没有入站则宽限到期关() throws Exception {
        try (var f = new Fixture()) {
            f.verify();
            f.advance(33_000);
            f.advance(1_000);
            assertThat(f.channel.isOpen()).isFalse();
            assertThat(f.count("idle_closed")).isEqualTo(1);
        }
    }
}
