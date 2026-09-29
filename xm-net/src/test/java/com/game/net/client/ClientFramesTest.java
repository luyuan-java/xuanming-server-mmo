package com.game.net.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.common.base.ClientRequest;
import com.game.proto.common.base.ClientTokenVerifyRequest;
import com.game.proto.common.base.MessageContent;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Adler32;
import org.junit.jupiter.api.Test;

class ClientFramesTest {

    private static final Map<String, Message> ACCEPTED = Map.of(
            "ClientRequest", ClientRequest.getDefaultInstance(),
            "ClientTokenVerifyRequest", ClientTokenVerifyRequest.getDefaultInstance());

    /** 按 Go robot 的写法手工拼帧：typeName 用短名 + 空格结尾。 */
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
        adler.update(buf.array(), 4, len - 4);
        buf.writeInt((int) adler.getValue());
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    private static EmbeddedChannel decoderChannel(List<ClientFrameException> errors) {
        return new EmbeddedChannel(new ClientFrameDecoder(ACCEPTED, ClientFrames.DEFAULT_MAX_LEN, (ctx, e) -> errors.add(e)));
    }

    @Test
    void 解得开_robot_写法的帧_短名加空格() {
        ClientRequest req = ClientRequest.newBuilder().setId(7).setMessageId(48)
                .setBody(ByteString.copyFromUtf8("x")).build();
        EmbeddedChannel ch = decoderChannel(new ArrayList<>());
        ch.writeInbound(Unpooled.wrappedBuffer(robotFrame(req, ' ')));
        assertThat((Object) ch.readInbound()).isEqualTo(req);
        assertThat(ch.isOpen()).isTrue();
    }

    @Test
    void 分片到达时攒齐再解() {
        ClientRequest req = ClientRequest.newBuilder().setMessageId(26).build();
        byte[] frame = robotFrame(req, '\0');
        EmbeddedChannel ch = decoderChannel(new ArrayList<>());
        ch.writeInbound(Unpooled.wrappedBuffer(frame, 0, 3));
        assertThat((Object) ch.readInbound()).isNull();
        ch.writeInbound(Unpooled.wrappedBuffer(frame, 3, frame.length - 3));
        assertThat((Object) ch.readInbound()).isEqualTo(req);
    }

    @Test
    void 编码结果与_C_plus_plus_布局一致() {
        MessageContent content = MessageContent.newBuilder().setMessageId(79).setId(3)
                .setSerializedMessage(ByteString.copyFromUtf8("abc")).build();
        ByteBuf frame = ClientFrames.encode(Unpooled.buffer().alloc(), content);

        int len = frame.readInt();
        assertThat(len).isEqualTo(frame.readableBytes());
        int nameLen = frame.readInt();
        assertThat(nameLen).isEqualTo("MessageContent".length() + 1);
        byte[] name = new byte[nameLen];
        frame.readBytes(name);
        assertThat(new String(name, StandardCharsets.US_ASCII)).isEqualTo("MessageContent\0");
        byte[] body = new byte[len - 4 - nameLen - 4];
        frame.readBytes(body);
        assertThat(body).isEqualTo(content.toByteArray());
        int checksum = frame.readInt();
        Adler32 adler = new Adler32();
        ByteBuf all = ClientFrames.encode(Unpooled.buffer().alloc(), content);
        byte[] raw = new byte[all.readableBytes()];
        all.readBytes(raw);
        adler.update(raw, 4, len - 4);
        assertThat(checksum).isEqualTo((int) adler.getValue());
    }

    @Test
    void 编码再解码往返() {
        ClientTokenVerifyRequest req = ClientTokenVerifyRequest.newBuilder()
                .setPayload(ByteString.copyFromUtf8("p")).setSignature(ByteString.copyFromUtf8("s")).build();
        EmbeddedChannel ch = decoderChannel(new ArrayList<>());
        ch.writeInbound(ClientFrames.encode(Unpooled.buffer().alloc(), req));
        assertThat((Object) ch.readInbound()).isEqualTo(req);
    }

    @Test
    void 长度越界立即断开且不产出消息() {
        List<ClientFrameException> errors = new ArrayList<>();
        EmbeddedChannel ch = decoderChannel(errors);
        ch.writeInbound(Unpooled.buffer().writeInt(ClientFrames.DEFAULT_MAX_LEN + 1).writeInt(0));
        assertThat((Object) ch.readInbound()).isNull();
        assertThat(ch.isOpen()).isFalse();
        assertThat(errors).extracting(ClientFrameException::reason).containsExactly(ClientFrameException.Reason.INVALID_LENGTH);
    }

    @Test
    void 校验和错误断开() {
        byte[] frame = robotFrame(ClientRequest.newBuilder().setMessageId(1).build(), ' ');
        frame[frame.length - 1] ^= 0x5A;
        List<ClientFrameException> errors = new ArrayList<>();
        EmbeddedChannel ch = decoderChannel(errors);
        ch.writeInbound(Unpooled.wrappedBuffer(frame));
        assertThat(ch.isOpen()).isFalse();
        assertThat(errors).extracting(ClientFrameException::reason).containsExactly(ClientFrameException.Reason.CHECKSUM);
    }

    @Test
    void 白名单外的类型断开() {
        byte[] frame = robotFrame(MessageContent.newBuilder().setMessageId(1).build(), ' ');
        List<ClientFrameException> errors = new ArrayList<>();
        EmbeddedChannel ch = decoderChannel(errors);
        ch.writeInbound(Unpooled.wrappedBuffer(frame));
        assertThat(ch.isOpen()).isFalse();
        assertThat(errors).extracting(ClientFrameException::reason).containsExactly(ClientFrameException.Reason.UNKNOWN_TYPE);
    }
}
