package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.net.client.ClientFrameDecoder;
import com.game.net.client.ClientFrameEncoder;
import com.game.net.client.ClientFrames;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.game.proto.TipInfoMessage;
import com.game.proto.login.LoginRequest;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * 用本机回环上的假 gate 验证整条客户端链路：TCP 连接 → 首帧握手 → 请求 / 应答按 (message_id, id) 对上 → 推送进收件箱。
 * 假 gate 只按客户端契约回帧，不含任何游戏逻辑；不需要真服务端。
 */
class GameConnectionTest {

    private static final int TIP = 23;
    private static final int ECHO = 48;
    private static final int PUSH = 79;
    private static final int ENVELOPE_ERROR = 99;
    private static final int TIP_ONLY = 77;
    private static final Duration SHORT = Duration.ofSeconds(3);

    private static EventLoopGroup group;
    private static Channel server;
    private static int port;

    /** 假 gate：payload 为 "ok" 才放行；48 先推一条 79 再原样回显；99 回信封错误；77 只推 23 不回包。 */
    private static final class FakeGate extends SimpleChannelInboundHandler<Message> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
            if (msg instanceof ClientTokenVerifyRequest verify) {
                boolean ok = verify.getPayload().toStringUtf8().equals("ok");
                ctx.writeAndFlush(ClientTokenVerifyResponse.newBuilder().setSuccess(ok).setError(ok ? "" : "bad token").build());
                return;
            }
            ClientRequest request = (ClientRequest) msg;
            switch (request.getMessageId()) {
                case ECHO -> {
                    ctx.write(MessageContent.newBuilder().setMessageId(PUSH)
                            .setSerializedMessage(ByteString.copyFromUtf8("push")).build());
                    ctx.writeAndFlush(MessageContent.newBuilder().setMessageId(ECHO).setId(request.getId())
                            .setSerializedMessage(request.getBody()).build());
                }
                case ENVELOPE_ERROR -> ctx.writeAndFlush(MessageContent.newBuilder().setMessageId(ENVELOPE_ERROR)
                        .setId(request.getId()).setErrorMessage(TipInfoMessage.newBuilder().setId(1008)).build());
                case TIP_ONLY -> ctx.writeAndFlush(MessageContent.newBuilder().setMessageId(TIP)
                        .setSerializedMessage(TipInfoMessage.newBuilder().setId(1003).build().toByteString()).build());
                default -> {
                    // 不回包（如 Empty 应答的方法）
                }
            }
        }
    }

    @BeforeAll
    static void startFakeGate() throws InterruptedException {
        group = new NioEventLoopGroup(2);
        server = new ServerBootstrap()
                .group(group)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ClientFrameDecoder(Map.of(
                                "ClientTokenVerifyRequest", ClientTokenVerifyRequest.getDefaultInstance(),
                                "ClientRequest", ClientRequest.getDefaultInstance()), ClientFrames.DEFAULT_MAX_LEN, (c, e) -> {
                        }));
                        ch.pipeline().addLast(ClientFrameEncoder.INSTANCE);
                        ch.pipeline().addLast(new FakeGate());
                    }
                })
                .bind("127.0.0.1", 0).sync().channel();
        port = ((InetSocketAddress) server.localAddress()).getPort();
    }

    @AfterAll
    static void stop() {
        server.close().syncUninterruptibly();
        group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
    }

    private static GameConnection connect() throws RobotException {
        return GameConnection.open(group, "127.0.0.1", port, SHORT, TIP);
    }

    @Test
    void 握手后请求按_message_id_与_id_对上应答_推送留在收件箱() throws Exception {
        try (GameConnection connection = connect()) {
            connection.verifyToken("ok".getBytes(), "sig".getBytes(), SHORT);
            LoginRequest body = LoginRequest.newBuilder().setAccount("robot_java_0001").build();

            LoginRequest echoed = connection.call(ECHO, body, LoginRequest.parser(), SHORT);
            LoginRequest second = connection.call(ECHO, body, LoginRequest.parser(), SHORT);

            assertThat(echoed).isEqualTo(body);
            assertThat(second).isEqualTo(body);
            assertThat(connection.inbox().snapshot(0))
                    .filteredOn(r -> r.messageId() == ECHO).extracting(Received::requestId).containsExactly(1L, 2L);
            assertThat(connection.inbox().snapshot(0))
                    .filteredOn(r -> r.messageId() == PUSH).hasSize(2)
                    .allSatisfy(r -> assertThat(r.requestId()).isZero());
            assertThat(connection.isOpen()).isTrue();
        }
    }

    @Test
    void 令牌被拒() throws Exception {
        try (GameConnection connection = connect()) {
            assertThatThrownBy(() -> connection.verifyToken("bad".getBytes(), new byte[0], SHORT))
                    .isInstanceOf(RobotException.class)
                    .hasMessageContaining("拒绝")
                    .hasMessageContaining("bad token");
        }
    }

    @Test
    void 信封带传输层错误即失败() throws Exception {
        try (GameConnection connection = connect()) {
            connection.verifyToken("ok".getBytes(), new byte[0], SHORT);
            assertThatThrownBy(() -> connection.call(ENVELOPE_ERROR, LoginRequest.getDefaultInstance(),
                    LoginRequest.parser(), SHORT))
                    .isInstanceOf(RobotException.class)
                    .hasMessageContaining("tip=1008");
        }
    }

    @Test
    void 等应答超时_说明里带期间收到的_23_tip() throws Exception {
        try (GameConnection connection = connect()) {
            connection.verifyToken("ok".getBytes(), new byte[0], SHORT);
            assertThatThrownBy(() -> connection.call(TIP_ONLY, LoginRequest.getDefaultInstance(),
                    LoginRequest.parser(), Duration.ofMillis(300)))
                    .isInstanceOf(RobotException.class)
                    .hasMessageContaining("没有收到")
                    .hasMessageContaining("1003");
        }
    }

    @Test
    void 不回包的方法只发不等() throws Exception {
        try (GameConnection connection = connect()) {
            connection.verifyToken("ok".getBytes(), new byte[0], SHORT);
            long id = connection.send(134, LoginRequest.getDefaultInstance());
            assertThat(id).isEqualTo(1);
            assertThat(connection.await(0, r -> r.messageId() == 134, Duration.ofMillis(200))).isEmpty();
        }
    }

    @Test
    void 关闭后发送失败() throws Exception {
        GameConnection connection = connect();
        connection.close();
        assertThatThrownBy(() -> connection.send(ECHO, LoginRequest.getDefaultInstance()))
                .isInstanceOf(RobotException.class)
                .hasMessageContaining("主动断开");
        assertThat(connection.isOpen()).isFalse();
    }

    @Test
    void 连不上时报告地址() {
        assertThatThrownBy(() -> GameConnection.open(group, "127.0.0.1", 1, Duration.ofMillis(500), TIP))
                .isInstanceOf(RobotException.class)
                .hasMessageContaining("127.0.0.1:1");
    }
}
