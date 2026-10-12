package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.game.net.client.ClientFrameDecoder;
import com.game.net.client.ClientFrameEncoder;
import com.game.net.client.ClientFrames;
import com.game.proto.BattleAssignedS2C;
import com.game.proto.BattleStateS2C;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.GetBattleStateRequest;
import com.game.proto.MessageContent;
import com.game.proto.StopWatchBattleRequest;
import com.game.proto.eBattleTicketRole;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * battle 直连客户端：EmbeddedChannel 上的编解码与关闭标记，以及本机回环上的假直连面（只按客户端契约回帧，不含房间逻辑）：
 * 握手应答必须是首帧、参战票握手后自动补拉 140、观众票不补拉、拒绝后 FIN 记为 {@value BattleFrame#FIN}、应答之后 FIN 的顺序可断言。
 */
class BattleDirectConnectionTest {

    private static final int GET_STATE = 140;
    private static final int STOP_WATCH = 165;
    private static final long BATTLE_ID = 42;
    private static final Duration SHORT = Duration.ofSeconds(3);

    private static EventLoopGroup group;
    private static Channel server;
    private static int port;
    private static final List<ClientRequest> received = new CopyOnWriteArrayList<>();

    /** 假直连面：payload "ok" / "observer" 放行；"push-first" 先推一条再应答；其它拒绝后 FIN。165 应答后 FIN。 */
    private static final class FakeEdge extends SimpleChannelInboundHandler<Message> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
            if (msg instanceof BattleTokenVerifyRequest verify) {
                String payload = verify.getPayload().toStringUtf8();
                if (payload.equals("push-first")) {
                    ctx.write(MessageContent.newBuilder().setMessageId(139).build());
                }
                boolean ok = payload.equals("ok") || payload.equals("observer") || payload.equals("push-first");
                ctx.writeAndFlush(ok ? BattleTokenVerifyResponse.newBuilder().setSuccess(true).setBattleId(BATTLE_ID).build()
                        : BattleTokenVerifyResponse.newBuilder().setError("invalid ticket signature").build());
                if (!ok) {
                    ((SocketChannel) ctx.channel()).shutdownOutput();
                }
                return;
            }
            ClientRequest request = (ClientRequest) msg;
            received.add(request);
            MessageContent.Builder reply = MessageContent.newBuilder().setId(request.getId()).setMessageId(request.getMessageId());
            if (request.getMessageId() == GET_STATE) {
                reply.setSerializedMessage(BattleStateS2C.newBuilder().setBattleId(BATTLE_ID).build().toByteString());
            }
            ctx.writeAndFlush(reply.build());
            if (request.getMessageId() == STOP_WATCH) {
                ((SocketChannel) ctx.channel()).shutdownOutput();
            }
        }
    }

    @BeforeAll
    static void startFakeEdge() throws Exception {
        group = new NioEventLoopGroup(2);
        server = new ServerBootstrap().group(group).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(new ClientFrameDecoder(Map.of(
                                "BattleTokenVerifyRequest", BattleTokenVerifyRequest.getDefaultInstance(),
                                "ClientRequest", ClientRequest.getDefaultInstance()), ClientFrames.DEFAULT_MAX_LEN, (ctx, e) -> { }));
                        ch.pipeline().addLast(ClientFrameEncoder.INSTANCE);
                        ch.pipeline().addLast(new FakeEdge());
                    }
                })
                .bind("127.0.0.1", 0).sync().channel();
        port = ((InetSocketAddress) server.localAddress()).getPort();
    }

    @AfterAll
    static void stop() {
        server.close().awaitUninterruptibly();
        group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).awaitUninterruptibly();
    }

    private static BattleDirectConnection connect() throws RobotException {
        return BattleDirectConnection.open(group, "127.0.0.1", port, Duration.ofSeconds(3));
    }

    private static BattleAssignedS2C ticket(String payload, eBattleTicketRole role) {
        return BattleAssignedS2C.newBuilder().setBattleId(BATTLE_ID).setHost("127.0.0.1").setPort(port)
                .setTokenPayload(ByteString.copyFromUtf8(payload)).setTokenSignature(ByteString.copyFromUtf8("sig")).setRole(role).build();
    }

    // ---------------------------------------------------------------- 回环

    @Test
    void 参战票握手_应答是首帧_成功后自动补拉140_应答按id对上() throws Exception {
        received.clear();
        try (BattleDirectConnection c = connect()) {
            BattleDirectConnection.Handshake hs = c.handshake(ticket("ok", eBattleTicketRole.BATTLE_TICKET_ROLE_PARTICIPANT), GET_STATE, SHORT);
            assertThat(hs.success()).isTrue();
            assertThat(hs.response().getBattleId()).isEqualTo(BATTLE_ID);
            assertThat(hs.stateRequestId()).isEqualTo(1);
            BattleFrame reply = c.await(0, f -> f.isReplyTo(GET_STATE, 1), SHORT).orElseThrow();
            assertThat(reply.parse(BattleStateS2C.parser()).getBattleId()).isEqualTo(BATTLE_ID);
            assertThat(c.inbox().snapshot(0)).extracting(BattleFrame::label).containsExactly("verify-ok:42", "reply:140");
            assertThat(received).singleElement().satisfies(r -> {
                assertThat(r.getMessageId()).isEqualTo(GET_STATE);
                assertThat(GetBattleStateRequest.parseFrom(r.getBody()).getBattleId()).isEqualTo(BATTLE_ID);
            });
        }
    }

    @Test
    void 观众票握手成功后不补拉() throws Exception {
        received.clear();
        try (BattleDirectConnection c = connect()) {
            BattleDirectConnection.Handshake hs = c.handshake(ticket("observer", eBattleTicketRole.BATTLE_TICKET_ROLE_OBSERVER), GET_STATE, SHORT);
            assertThat(hs.success()).isTrue();
            assertThat(hs.stateRequestId()).isZero();
            assertThat(c.await(1, f -> true, Duration.ofMillis(200))).isEmpty();
            assertThat(received).isEmpty();
        }
    }

    @Test
    void 握手被拒_照常返回拒绝串_随后记到对端FIN() throws Exception {
        try (BattleDirectConnection c = connect()) {
            BattleTokenVerifyResponse response = c.verify(ByteString.copyFromUtf8("bad"), ByteString.copyFromUtf8("sig"), SHORT);
            assertThat(response.getSuccess()).isFalse();
            assertThat(response.getError()).isEqualTo("invalid ticket signature");
            assertThat(c.awaitClosed(SHORT)).contains(BattleFrame.FIN);
            assertThat(c.inbox().snapshot(0)).extracting(BattleFrame::label)
                    .containsExactly("verify-fail:invalid ticket signature", "closed:fin");
            assertThat(c.isOpen()).isFalse();
            assertThatThrownBy(() -> c.request(GET_STATE, GetBattleStateRequest.getDefaultInstance()))
                    .isInstanceOf(RobotException.class).hasMessageContaining("fin");
        }
    }

    @Test
    void 握手后的第一帧不是应答_抛出() throws Exception {
        try (BattleDirectConnection c = connect()) {
            assertThatThrownBy(() -> c.verify(ByteString.copyFromUtf8("push-first"), ByteString.copyFromUtf8("sig"), SHORT))
                    .isInstanceOf(RobotException.class).hasMessageContaining("第一帧不是 BattleTokenVerifyResponse").hasMessageContaining("push:139");
        }
    }

    @Test
    void 应答之后FIN_顺序可按序号断言() throws Exception {
        try (BattleDirectConnection c = connect()) {
            c.verify(ByteString.copyFromUtf8("ok"), ByteString.copyFromUtf8("sig"), SHORT);
            int mark = c.inbox().size();
            BattleFrame reply = c.call(STOP_WATCH, StopWatchBattleRequest.newBuilder().setBattleId(BATTLE_ID).build(), SHORT);
            assertThat(reply.isReply()).isTrue();
            assertThat(reply.content().getSerializedMessage()).isEmpty();
            assertThat(c.inbox().awaitClosed(mark, SHORT)).get().satisfies(tail ->
                    assertThat(tail).extracting(BattleFrame::label).containsExactly("reply:165", "closed:fin"));
        }
    }

    @Test
    void 原始字节原样发出_坏校验和让服务端断开() throws Exception {
        try (BattleDirectConnection c = connect()) {
            ByteBuf frame = ClientFrames.encode(ByteBufAllocator.DEFAULT, BattleTokenVerifyRequest.newBuilder()
                    .setPayload(ByteString.copyFromUtf8("ok")).build());
            byte[] bytes = new byte[frame.readableBytes()];
            frame.readBytes(bytes);
            frame.release();
            bytes[bytes.length - 1] ^= 0x5a;
            c.sendRaw(bytes);
            assertThat(c.awaitClosed(SHORT)).isPresent();
            assertThat(c.inbox().snapshot(0)).allMatch(BattleFrame::isClosed);
        }
    }

    @Test
    void 连不上时报错带地址() {
        assertThatThrownBy(() -> BattleDirectConnection.open(group, "127.0.0.1", 1, Duration.ofSeconds(2)))
                .isInstanceOf(RobotException.class).hasMessageContaining("127.0.0.1:1");
    }

    // ---------------------------------------------------------------- EmbeddedChannel

    private static EmbeddedChannel embedded(BattleInbox inbox) {
        EmbeddedChannel ch = new EmbeddedChannel();
        BattleDirectConnection.configure(ch.pipeline(), inbox);
        return ch;
    }

    @Test
    void 上行握手帧按客户端帧格式编码_类型名是全名加终止符() throws Exception {
        EmbeddedChannel ch = embedded(new BattleInbox());
        BattleTokenVerifyRequest verify = BattleTokenVerifyRequest.newBuilder().setPayload(ByteString.copyFromUtf8("p"))
                .setSignature(ByteString.copyFromUtf8("s")).build();
        ch.writeOutbound(verify);
        ByteBuf out = ch.readOutbound();
        try {
            assertThat(out.readInt()).isEqualTo(out.readableBytes());
            int nameLen = out.getInt(out.readerIndex());
            assertThat(out.toString(out.readerIndex() + 4, nameLen - 1, StandardCharsets.US_ASCII)).isEqualTo("BattleTokenVerifyRequest");
            assertThat(ClientFrames.decodeBody(out.slice(), Map.of("BattleTokenVerifyRequest", BattleTokenVerifyRequest.getDefaultInstance())))
                    .isEqualTo(verify);
        } finally {
            out.release();
        }
    }

    @Test
    void 下行只收握手应答与信封_别的类型记非法帧并关闭() {
        BattleInbox inbox = new BattleInbox();
        EmbeddedChannel ch = embedded(inbox);
        ch.writeInbound(ClientFrames.encode(ByteBufAllocator.DEFAULT, BattleTokenVerifyResponse.newBuilder().setSuccess(true).setBattleId(9).build()));
        ch.writeInbound(ClientFrames.encode(ByteBufAllocator.DEFAULT, MessageContent.newBuilder().setMessageId(139).build()));
        assertThat(inbox.snapshot(0)).extracting(BattleFrame::label).containsExactly("verify-ok:9", "push:139");

        ch.writeInbound(ClientFrames.encode(ByteBufAllocator.DEFAULT, ClientTokenVerifyResponse.newBuilder().setSuccess(true).build()));
        assertThat(inbox.closedBy()).startsWith("invalid-frame:");
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void 对端半关闭记FIN_读异常记RESET() {
        BattleInbox fin = new BattleInbox();
        EmbeddedChannel a = embedded(fin);
        a.pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
        assertThat(fin.closedBy()).isEqualTo(BattleFrame.FIN);
        assertThat(a.isOpen()).isFalse();

        BattleInbox reset = new BattleInbox();
        EmbeddedChannel b = embedded(reset);
        b.pipeline().fireExceptionCaught(new IOException("Connection reset by peer"));
        assertThat(reset.closedBy()).isEqualTo(BattleFrame.RESET);
        assertThat(b.isOpen()).isFalse();
    }
}
