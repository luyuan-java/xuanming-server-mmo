package com.game.gate.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.api.ClientMessageService;
import com.game.api.proto.AbandonedEnter;
import com.game.api.proto.ClientCall;
import com.game.api.proto.ClientReply;
import com.game.api.proto.SessionClosed;
import com.game.common.net.ConnectionAdmissionController;
import com.game.common.net.ConnectionAdmissionPolicy;
import com.game.common.net.LoopStallDetector;
import com.game.common.token.GateTokens;
import com.game.gate.link.SceneLinks;
import com.game.gate.metrics.GateMetrics;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GateTokenPayload;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * gate 连接准入（P1 / P0-2 / P2-2）与 P2-4 票据先行生命周期单测：
 * <ul>
 *   <li>验签前不建会话不发号、验签成功才发号且继承建连代次并透传 account_digest；</li>
 *   <li>未验签断开只删预认证记录、零 login/scene 通知；</li>
 *   <li>单票单连：票据被占回 {@code token already in use} 且不建会话；</li>
 *   <li>dev 免验签建连即建会话；</li>
 *   <li>承压淘汰按建连先后关闭最老未验签连接并导出同名 Prometheus 指标；</li>
 *   <li>真实回环 TCP 准入满员以 {@code SO_LINGER=0} 发 RST 拒绝且不进入会话层。</li>
 * </ul>
 */
class PreAuthAdmissionLifecycleTest {

    private static final int GATE_NODE = 5;
    private static final int ZONE = 1;
    private static final long NOW = 1_700_000_000L;
    private static final int LOGIN_MSG = 10;

    private static final class RecordingLogin implements ClientMessageService {
        final List<ClientCall> calls = new ArrayList<>();
        final AtomicInteger closedCount = new AtomicInteger();

        @Override
        public CompletableFuture<ClientReply> handle(ClientCall call) {
            calls.add(call);
            return CompletableFuture.completedFuture(ClientReply.getDefaultInstance());
        }

        @Override
        public CompletableFuture<Void> sessionClosed(SessionClosed closed) {
            closedCount.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> abandonEnter(AbandonedEnter abandoned) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class NoopLinks implements SceneLinks {
        @Override
        public long send(int sceneNodeId, com.game.api.proto.NodeLinkFrame frame) {
            return 0;
        }

        @Override
        public void ensureConnected(int sceneNodeId) {
        }
    }

    private static final MessageRoutes ROUTES = id -> id == LOGIN_MSG
            ? new MessageRoute(LOGIN_MSG, ClientDispatcher.DOMAIN_LOGIN, "ClientPlayerLogin.Login", true, false, false, false)
            : null;

    @Test
    void 验签前不建会话不发号_验签成功继承代次并透传accountDigest_单票单连与未验签断开零通知() {
        AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(100));
        GateTokens tokens = GateTokens.ofUtf8("p24-secret");
        SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GateMetrics metrics = new GateMetrics(meters);
        RecordingLogin login = new RecordingLogin();
        ClientDispatcher dispatcher = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-p24", ZONE),
                tokens, InstantSource.fixed(Instant.ofEpochSecond(NOW)), ROUTES,
                new PushMessageIds(23, 124, 34), login, Map.of(), new NoopLinks(), registry,
                new GateLimits(8, 5, Duration.ofSeconds(10)), metrics, PresenceRecorder.NONE, nanos::get);

        LoopStallDetector stall = new LoopStallDetector();
        stall.recordBeat(nanos.get());
        var policy = ConnectionAdmissionPolicy.defaults(100, 10_000L);
        var admission = new ConnectionAdmissionController<Channel>(policy, registry::size, nanos::get, () -> 0.5, stall);
        metrics.bindAdmission(admission);

        // 1. 连上未验签：不建 ClientSession、不发号，仅占未验签池；未验签直接断开不通知 login
        ClientChannelHandler h0 = new ClientChannelHandler(registry, dispatcher, admission, false, false);
        EmbeddedChannel unauth = new EmbeddedChannel(h0);
        assertThat(registry.size()).isZero();
        assertThat(h0.session()).isNull();
        assertThat(h0.preAuthEntry()).isNotNull();
        assertThat(admission.preAuthCount()).isEqualTo(1);
        assertThat(meters.get("gate.connections.current").gauge().value()).isEqualTo(1.0);
        assertThat(meters.get("gate.unauthenticated.connections").gauge().value()).isEqualTo(1.0);

        unauth.close();
        assertThat(admission.preAuthCount()).isZero();
        assertThat(registry.size()).isZero();
        assertThat(login.closedCount.get()).isZero();

        // 2. 第二条连接：建连代次 = 2，验签后建会话并严格继承代次 2，透传 account_digest = 16 字节
        ClientChannelHandler h1 = new ClientChannelHandler(registry, dispatcher, admission, false, false);
        EmbeddedChannel ch1 = new EmbeddedChannel(h1);
        long gen1 = h1.preAuthEntry().generation();
        assertThat(gen1).isEqualTo(2L);
        assertThat(registry.size()).isZero();

        nanos.addAndGet(TimeUnit.MILLISECONDS.toNanos(42));
        ByteString digest = ByteString.copyFrom(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16});
        ByteString payload = GateTokenPayload.newBuilder()
                .setGateNodeId(GATE_NODE).setZoneId(ZONE).setExpireTimestamp(NOW + 600)
                .setAccountDigest(digest).build().toByteString();
        ClientTokenVerifyRequest verifyReq = ClientTokenVerifyRequest.newBuilder()
                .setPayload(payload).setSignature(tokens.sign(payload)).build();
        ch1.writeInbound(verifyReq);
        ClientTokenVerifyResponse ok = ch1.readOutbound();
        assertThat(ok.getSuccess()).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(admission.preAuthCount()).isZero();
        assertThat(h1.session()).isNotNull();
        assertThat(h1.session().connectionGeneration()).isEqualTo(gen1);
        assertThat(meters.get("gate.handshake.duration.ms").summary().count()).isEqualTo(1L);

        // 业务请求必须透传 ticket_account_digest = 11
        ch1.writeInbound(ClientRequest.newBuilder().setId(1).setMessageId(LOGIN_MSG).build());
        assertThat(login.calls).hasSize(1);
        assertThat(login.calls.getFirst().getSession().getTicketAccountDigest()).isEqualTo(digest);

        // 3. 同一张票再开第二条连接：拒绝 token already in use，且不建会话、不发号
        ClientChannelHandler h2 = new ClientChannelHandler(registry, dispatcher, admission, false, false);
        EmbeddedChannel ch2 = new EmbeddedChannel(h2);
        ch2.writeInbound(verifyReq);
        ClientTokenVerifyResponse dup = ch2.readOutbound();
        assertThat(dup.getSuccess()).isFalse();
        assertThat(dup.getError()).isEqualTo("token already in use");
        assertThat(ch2.isOpen()).isFalse();
        assertThat(h2.session()).isNull();
        assertThat(registry.size()).isEqualTo(1);

        // 4. ch1 断开释放票据后，同一张票可再次建连验签
        ch1.close();
        assertThat(registry.size()).isZero();
        ClientChannelHandler h3 = new ClientChannelHandler(registry, dispatcher, admission, false, false);
        EmbeddedChannel ch3 = new EmbeddedChannel(h3);
        ch3.writeInbound(verifyReq);
        assertThat(((ClientTokenVerifyResponse) ch3.readOutbound()).getSuccess()).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        ch3.close();

        // 5. dev 免验签模式：建连即建会话并立即 verified
        ClientChannelHandler hDev = new ClientChannelHandler(registry, dispatcher, admission, true, false);
        EmbeddedChannel chDev = new EmbeddedChannel(hDev);
        assertThat(hDev.session()).isNotNull();
        assertThat(hDev.isVerified()).isTrue();
        assertThat(registry.size()).isEqualTo(1);
        assertThat(admission.preAuthCount()).isZero();
        chDev.close();
    }

    @Test
    void 承压淘汰按建连先后关闭最老未验签连接_真实回环满员发RST拒绝() throws Exception {
        AtomicLong nanos = new AtomicLong(TimeUnit.SECONDS.toNanos(10));
        GateTokens tokens = GateTokens.ofUtf8("evict-secret");
        SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        GateMetrics metrics = new GateMetrics(meters);
        ClientDispatcher dispatcher = new ClientDispatcher(new GateIdentity(GATE_NODE, "gate-ev", ZONE),
                tokens, InstantSource.fixed(Instant.ofEpochSecond(NOW)), ROUTES,
                new PushMessageIds(23, 124, 34), new RecordingLogin(), Map.of(), new NoopLinks(), registry,
                new GateLimits(8, 5, Duration.ofSeconds(10)), metrics, PresenceRecorder.NONE, nanos::get);

        LoopStallDetector stall = new LoopStallDetector();
        stall.recordBeat(nanos.get());
        // max=10, budget=2, earlyStart=1, evictAge=3000ms
        var policy = ConnectionAdmissionPolicy.of(10, 10_000L, 100, 100, 2, 1, 3_000L, null, null);
        var admission = new ConnectionAdmissionController<Channel>(policy, registry::size, nanos::get, () -> 0.99, stall);
        metrics.bindAdmission(admission);

        EmbeddedChannel oldest = new EmbeddedChannel(new ClientChannelHandler(registry, dispatcher, admission, false, true));
        assertThat(oldest.isOpen()).isTrue();
        assertThat(admission.preAuthCount()).isEqualTo(1);

        // 推进 4 秒并持续健康打拍 -> 新连接到来时淘汰 oldest 并正常关闭它
        for (int i = 0; i < 4; i++) {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(1));
            stall.recordBeat(nanos.get());
        }
        EmbeddedChannel newer = new EmbeddedChannel(new ClientChannelHandler(registry, dispatcher, admission, false, true));
        oldest.runPendingTasks();
        assertThat(oldest.isOpen()).isFalse();
        assertThat(newer.isOpen()).isTrue();
        assertThat(admission.preAuthCount()).isEqualTo(1);
        assertThat(meters.get("gate.preauth.evicted").functionCounter().count()).isEqualTo(1.0);
        newer.close();

        // 真实回环 TCP：maxConnections=1 时第 2 条连接在准入层被 SO_LINGER=0 RST 拒绝，不进入会话层
        SessionRegistry tcpReg = new SessionRegistry(new SessionIdAllocator(GATE_NODE));
        var tcpAdmission = new ConnectionAdmissionController<Channel>(
                ConnectionAdmissionPolicy.defaults(1, 10_000L), tcpReg::size);
        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup(1);
        Channel server = null;
        try {
            server = new ServerBootstrap()
                    .group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ClientPipeline.install(ch.pipeline(), tcpReg, dispatcher, tcpAdmission, false, true);
                        }
                    })
                    .bind("127.0.0.1", 0).syncUninterruptibly().channel();
            int port = ((InetSocketAddress) server.localAddress()).getPort();
            try (Socket s1 = new Socket("127.0.0.1", port)) {
                // 等待 s1 登记进 preAuth 池
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (tcpAdmission.preAuthCount() < 1 && System.nanoTime() < deadline) {
                    Thread.sleep(5);
                }
                assertThat(tcpAdmission.preAuthCount()).isEqualTo(1);
                try (Socket s2 = new Socket("127.0.0.1", port)) {
                    s2.setSoTimeout(2000);
                    boolean resetOrClosed = false;
                    try {
                        int b = s2.getInputStream().read();
                        resetOrClosed = (b == -1);
                    } catch (IOException reset) {
                        resetOrClosed = true;
                    }
                    assertThat(resetOrClosed).isTrue();
                }
                assertThat(tcpReg.size()).isZero();
                assertThat(meters.get("gate.rejected").tag("reason", "capacity").counter().count()).isGreaterThanOrEqualTo(1.0);
            }
        } finally {
            if (server != null) {
                server.close().syncUninterruptibly();
            }
            worker.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            boss.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }
}
