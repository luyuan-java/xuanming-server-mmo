package com.game.net.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.proto.ClientRequest;
import com.game.proto.ClientTokenVerifyRequest;
import com.game.proto.MessageContent;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

/**
 * 逐帧分发（基线 {@code codec.cpp:158-191}）：一次读里的多帧逐帧交给下游、处理完一帧才解下一帧；坏帧之前的合法帧照常处理；
 * 下游已关闭或决定关闭连接时，剩余字节丢弃、不解析、不报错。
 */
class ClientFrameDecoderTest {

    private static final Map<String, Message> ACCEPTED = Map.of(
            "ClientRequest", ClientRequest.getDefaultInstance(),
            "ClientTokenVerifyRequest", ClientTokenVerifyRequest.getDefaultInstance());

    private final List<ClientFrameException> errors = new ArrayList<>();
    /** 下游收到的帧，以及收到那一刻连接是否还开着、下一帧是否已经解出来（逐帧分发时一定还没有）。 */
    private final List<Received> received = new ArrayList<>();
    /** 下游的「会话还收帧」（接解码器的 keepDecoding）。 */
    private boolean accepting = true;

    private record Received(Message message, boolean channelOpen) {
    }

    private static ClientRequest request(long id) {
        return ClientRequest.newBuilder().setId(id).setMessageId(140).setBody(ByteString.copyFromUtf8("b")).build();
    }

    private static ByteBuf frames(Object... parts) {
        ByteBuf all = Unpooled.buffer();
        for (Object part : parts) {
            if (part instanceof Message m) {
                ByteBuf one = ClientFrames.encode(Unpooled.buffer().alloc(), m);
                all.writeBytes(one);
                one.release();
            } else {
                all.writeBytes((byte[]) part);
            }
        }
        return all;
    }

    /** 校验和错误的一帧。 */
    private static byte[] corruptFrame() {
        ByteBuf one = ClientFrames.encode(Unpooled.buffer().alloc(), request(99));
        byte[] raw = new byte[one.readableBytes()];
        one.readBytes(raw);
        one.release();
        raw[raw.length - 1] ^= 0x5A;
        return raw;
    }

    /** 长度头越界（不需要后续字节就能判定非法）。 */
    private static byte[] invalidLength() {
        return new byte[] {0x7f, 0x7f, 0x7f, 0x7f};
    }

    /** 解码器 + 记录下游；{@code onMessage} 返回 true 时下游同步关闭连接。 */
    private EmbeddedChannel channel(Predicate<Message> closeOn) {
        ChannelInboundHandlerAdapter downstream = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                Message m = (Message) msg;
                received.add(new Received(m, ctx.channel().isOpen()));
                if (closeOn.test(m)) {
                    ctx.close();
                }
            }
        };
        return new EmbeddedChannel(
                new ClientFrameDecoder(ACCEPTED, ClientFrames.DEFAULT_MAX_LEN, () -> accepting, (ctx, e) -> errors.add(e)),
                downstream);
    }

    @Test
    void 同一次读里合法帧后跟坏帧_合法帧先交给下游_连接还开着_然后才因坏帧断开() {
        EmbeddedChannel ch = channel(m -> false);

        ch.writeInbound(frames(request(1), request(2), corruptFrame(), request(3)));

        assertThat(received).extracting(Received::message).containsExactly(request(1), request(2));
        assertThat(received).extracting(Received::channelOpen).as("交给下游时连接还开着，应答写得出去").containsOnly(true);
        assertThat(errors).extracting(ClientFrameException::reason).containsExactly(ClientFrameException.Reason.CHECKSUM);
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void 同一次读里合法帧后跟长度越界_先交付再断开() {
        EmbeddedChannel ch = channel(m -> false);

        ch.writeInbound(frames(request(1), invalidLength()));

        assertThat(received).extracting(Received::message).containsExactly(request(1));
        assertThat(received.get(0).channelOpen()).isTrue();
        assertThat(errors).extracting(ClientFrameException::reason).containsExactly(ClientFrameException.Reason.INVALID_LENGTH);
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void 下游处理一帧时关闭连接_同一次读里剩余字节丢弃_坏帧也不解析不报错() {
        EmbeddedChannel ch = channel(m -> m.equals(request(1)));

        ch.writeInbound(frames(request(1), request(2), corruptFrame()));

        assertThat(received).extracting(Received::message).as("关闭之后的帧不再分发").containsExactly(request(1));
        assertThat(errors).as("剩余字节不解析，坏帧不计非法帧").isEmpty();
        assertThat(ch.isOpen()).isFalse();
    }

    @Test
    void 下游决定关闭但连接还开着_keepDecoding为假_剩余字节丢弃不解析_不当场强关() {
        EmbeddedChannel ch = channel(m -> {
            if (m.equals(request(1))) {
                accepting = false;   // 例如：已回拒绝应答，等输出排空后再 FIN
            }
            return false;
        });

        ch.writeInbound(frames(request(1), request(2), corruptFrame()));

        assertThat(received).extracting(Received::message).containsExactly(request(1));
        assertThat(errors).isEmpty();
        assertThat(ch.isOpen()).as("关闭由下游自己负责，解码器不当场强关").isTrue();

        ch.writeInbound(frames(request(3), invalidLength()));
        assertThat(received).as("之后的读同样丢弃").hasSize(1);
        assertThat(errors).isEmpty();
    }

    @Test
    void 多帧一次到达_按序逐帧交付_半帧留到下一次读() {
        EmbeddedChannel ch = channel(m -> false);
        ByteBuf all = frames(request(1), request(2), request(3));
        int cut = all.readableBytes() - 5;

        ch.writeInbound(all.retainedSlice(0, cut));
        assertThat(received).extracting(Received::message).containsExactly(request(1), request(2));

        ch.writeInbound(all.retainedSlice(cut, 5));
        assertThat(received).extracting(Received::message).containsExactly(request(1), request(2), request(3));
        assertThat(ch.isOpen()).isTrue();
        assertThat(errors).isEmpty();
        all.release();
    }

    @Test
    void 不带keepDecoding的构造_连接活跃就一直解() {
        List<Object> got = new ArrayList<>();
        EmbeddedChannel ch = new EmbeddedChannel(
                new ClientFrameDecoder(ACCEPTED, ClientFrames.DEFAULT_MAX_LEN, (ctx, e) -> errors.add(e)),
                new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        got.add(msg);
                    }
                });

        ch.writeInbound(frames(request(1), request(2)));
        assertThat(got).containsExactly(request(1), request(2));

        ch.writeInbound(frames(MessageContent.newBuilder().setMessageId(1).build()));
        assertThat(errors).extracting(ClientFrameException::reason).containsExactly(ClientFrameException.Reason.UNKNOWN_TYPE);
        assertThat(ch.isOpen()).isFalse();
    }
}
