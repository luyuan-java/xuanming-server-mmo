package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.net.client.ClientFrames;
import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 客户端管线（xm-net 的帧编解码 + 入站处理）在 EmbeddedChannel 上的往返，不需要服务端。 */
class ClientPipelineTest {

    private final Inbox inbox = new Inbox();
    private final ConnectionHandler handler = new ConnectionHandler(inbox);
    private final EmbeddedChannel channel = newChannel(handler);

    private static EmbeddedChannel newChannel(ConnectionHandler handler) {
        EmbeddedChannel ch = new EmbeddedChannel();
        GameConnection.configure(ch.pipeline(), handler);
        return ch;
    }

    private static ByteBuf frame(Message message) {
        return ClientFrames.encode(ByteBufAllocator.DEFAULT, message);
    }

    @Test
    void 上行_ClientRequest_按客户端帧格式编码_服务端解码器解得回原消息() throws Exception {
        ClientRequest request = ClientRequest.newBuilder().setId(1).setMessageId(48)
                .setBody(ByteString.copyFromUtf8("login")).build();
        assertThat(channel.writeOutbound(request)).isTrue();
        ByteBuf out = channel.readOutbound();
        try {
            int len = out.readInt();
            assertThat(len).isEqualTo(out.readableBytes());
            int nameLen = out.getInt(out.readerIndex());
            // typeName = 全名 + 1 字节终止符（message.proto 没有 package，全名即裸名）。
            assertThat(out.toString(out.readerIndex() + 4, nameLen - 1, StandardCharsets.US_ASCII)).isEqualTo("ClientRequest");
            Message decoded = ClientFrames.decodeBody(out.slice(), Map.of("ClientRequest", ClientRequest.getDefaultInstance()));
            assertThat(decoded).isEqualTo(request);
        } finally {
            out.release();
        }
    }

    @Test
    void 上行握手帧也是客户端帧格式() throws Exception {
        ClientTokenVerifyRequest verify = ClientTokenVerifyRequest.newBuilder()
                .setPayload(ByteString.copyFromUtf8("p")).setSignature(ByteString.copyFromUtf8("s")).build();
        channel.writeOutbound(verify);
        ByteBuf out = channel.readOutbound();
        try {
            out.skipBytes(4);
            assertThat(ClientFrames.decodeBody(out.slice(),
                    Map.of("ClientTokenVerifyRequest", ClientTokenVerifyRequest.getDefaultInstance()))).isEqualTo(verify);
        } finally {
            out.release();
        }
    }

    @Test
    void 下行_MessageContent_记进收件箱() {
        MessageContent push = MessageContent.newBuilder().setMessageId(79)
                .setSerializedMessage(ByteString.copyFromUtf8("x")).build();
        channel.writeInbound(frame(push));
        assertThat(inbox.size()).isEqualTo(1);
        Received received = inbox.snapshot(0).get(0);
        assertThat(received.content()).isEqualTo(push);
        assertThat(received.requestId()).isZero();
        assertThat(channel.isOpen()).isTrue();
    }

    @Test
    void 握手应答完成握手_不进收件箱() throws Exception {
        handler.expectHandshake();
        channel.writeInbound(frame(ClientTokenVerifyResponse.newBuilder().setSuccess(true).build()));
        assertThat(handler.handshake().get().getSuccess()).isTrue();
        assertThat(inbox.size()).isZero();
    }

    @Test
    void 握手后第一帧不是握手应答即握手失败() {
        handler.expectHandshake();
        channel.writeInbound(frame(MessageContent.newBuilder().setMessageId(23).build()));
        assertThat(handler.handshake()).isCompletedExceptionally();
        assertThat(inbox.size()).isEqualTo(1);
    }

    @Test
    void 握手之外的握手应答视为违约_断开() {
        channel.writeInbound(frame(ClientTokenVerifyResponse.newBuilder().setSuccess(true).build()));
        assertThat(inbox.closedReason()).contains("ClientTokenVerifyResponse");
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void 校验和错的下行帧_记原因并断开() {
        ByteBuf bad = frame(MessageContent.newBuilder().setMessageId(79).build());
        int last = bad.writerIndex() - 1;
        bad.setByte(last, bad.getByte(last) ^ 0x1);
        channel.writeInbound(bad);
        assertThat(inbox.closedReason()).contains("非法下行帧").contains("CHECKSUM");
        assertThat(channel.isOpen()).isFalse();
    }

    @Test
    void 下行只接受两种类型_其它类型即非法帧() {
        channel.writeInbound(frame(ClientRequest.newBuilder().setMessageId(48).build()));
        assertThat(inbox.closedReason()).contains("UNKNOWN_TYPE");
        assertThat(channel.isOpen()).isFalse();
    }
}
