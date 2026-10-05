package com.game.battle.edge;

import com.game.battle.BattleProperties;
import com.game.common.token.BattleTickets;
import com.game.net.client.ClientFrameException;
import com.game.net.client.ClientFrames;
import com.game.proto.BattleTicketPayload;
import com.game.proto.BattleTokenVerifyRequest;
import com.game.proto.BattleTokenVerifyResponse;
import com.game.proto.ClientRequest;
import com.game.proto.MessageContent;
import com.google.protobuf.ByteString;
import com.google.protobuf.Message;
import com.google.protobuf.MessageLite;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.Adler32;

/** 直连面测试的公共件：票据、按 robot 写法拼上行帧、解下行帧、线上事件记录器。 */
final class EdgeTestKit {

    static final long NOW_MS = 1_900_000_000_000L;
    static final int NODE_ID = 7;
    static final String INSTANCE = "battle-instance-7";
    /** 仅测试用的票据密钥（不是任何环境的真密钥）。 */
    static final String SECRET = "edge-unit-test-only-ticket-secret-0123456789";

    static final long BATTLE = 1001;
    static final long PLAYER = 501;
    static final long OBSERVER = 601;

    static final int PARTICIPANT_ROLE = 1;
    static final int OBSERVER_ROLE = 2;

    /** 下行只有两种类型。 */
    static final Map<String, Message> DOWNSTREAM = Map.of(
            BattleTokenVerifyResponse.getDescriptor().getFullName(), BattleTokenVerifyResponse.getDefaultInstance(),
            MessageContent.getDescriptor().getFullName(), MessageContent.getDefaultInstance());

    private EdgeTestKit() {
    }

    static BattleProperties props(int maxConnections, Duration handshakeTimeout, int illegalPacketThreshold) {
        return new BattleProperties(null, null, null, null, maxConnections, handshakeTimeout, illegalPacketThreshold, null, null, null, null);
    }

    static BattleProperties loopbackProps(int port, int maxConnections) {
        return new BattleProperties(port, "127.0.0.1", null, null, maxConnections, null, null, null, null, null, null);
    }

    // ---------------------------------------------------------------- 票据

    static BattleTokenVerifyRequest ticket(BattleTickets tickets, long battleId, long playerId, int nodeId, String instance,
                                           long expireAtMs, int role) {
        ByteString payload = BattleTicketPayload.newBuilder()
                .setBattleId(battleId).setPlayerId(playerId).setBattleNodeId(nodeId).setBattleInstanceId(instance)
                .setExpireAtMs(expireAtMs).setRoleValue(role).build().toByteString();
        return signed(tickets, payload);
    }

    static BattleTokenVerifyRequest signed(BattleTickets tickets, ByteString payload) {
        return BattleTokenVerifyRequest.newBuilder().setPayload(payload).setSignature(tickets.sign(payload)).build();
    }

    static BattleTokenVerifyRequest participant(BattleTickets tickets, long battleId, long playerId) {
        return ticket(tickets, battleId, playerId, NODE_ID, INSTANCE, NOW_MS + 300_000, PARTICIPANT_ROLE);
    }

    static BattleTokenVerifyRequest observer(BattleTickets tickets, long battleId, long playerId) {
        return ticket(tickets, battleId, playerId, NODE_ID, INSTANCE, NOW_MS + 300_000, OBSERVER_ROLE);
    }

    // ---------------------------------------------------------------- 上行

    static ClientRequest request(long id, int messageId, MessageLite body) {
        return ClientRequest.newBuilder().setId(id).setMessageId(messageId).setBody(body.toByteString()).build();
    }

    static ClientRequest rawRequest(long id, int messageId, byte[] body) {
        return ClientRequest.newBuilder().setId(id).setMessageId(messageId).setBody(ByteString.copyFrom(body)).build();
    }

    /**
     * 整条序列化后恰好 {@code targetSize} 字节的请求：体是一条合法消息，后面用未知的 length-delimited 字段（号 15）补齐，解析照常通过。
     */
    static ClientRequest paddedRequest(long id, int messageId, MessageLite body, int targetSize) {
        for (int pad = 0; pad < targetSize; pad++) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                body.writeTo(bytes);
                com.google.protobuf.CodedOutputStream out = com.google.protobuf.CodedOutputStream.newInstance(bytes);
                out.writeBytes(15, ByteString.copyFrom(new byte[pad]));
                out.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            ClientRequest request = rawRequest(id, messageId, bytes.toByteArray());
            if (request.getSerializedSize() == targetSize) {
                return request;
            }
        }
        throw new IllegalArgumentException("凑不出 " + targetSize + " 字节");
    }

    /** 按 Go robot 的写法拼一帧：短名（= 全名，这批 proto 没有 package）+ 结尾字节（robot 写空格，C++ 写 \0）。 */
    static byte[] frame(Message message, char terminator) {
        byte[] name = (message.getDescriptorForType().getName() + terminator).getBytes(StandardCharsets.US_ASCII);
        byte[] body = message.toByteArray();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(payload)) {
            out.writeInt(name.length);
            out.write(name);
            out.write(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] checked = payload.toByteArray();
        Adler32 adler = new Adler32();
        adler.update(checked);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(frame)) {
            out.writeInt(checked.length + 4);
            out.write(checked);
            out.writeInt((int) adler.getValue());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return frame.toByteArray();
    }

    static ByteBuf buf(Message... messages) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        for (Message m : messages) {
            all.writeBytes(frame(m, ' '));
        }
        return Unpooled.wrappedBuffer(all.toByteArray());
    }

    // ---------------------------------------------------------------- 下行

    /** 解一整帧（含长度头），不改动 {@code buf} 的读位置。 */
    static Message decodeFrame(ByteBuf buf) {
        ByteBuf d = buf.duplicate();
        int len = d.readInt();
        try {
            return ClientFrames.decodeBody(d.readSlice(len), DOWNSTREAM);
        } catch (ClientFrameException e) {
            throw new AssertionError("下行帧非法: " + e.getMessage(), e);
        }
    }

    /** 下行帧里的类型名字段（含结尾字节）。 */
    static String typeName(ByteBuf buf) {
        int nameLen = buf.getInt(buf.readerIndex() + 4);
        return buf.toString(buf.readerIndex() + 8, nameLen, StandardCharsets.US_ASCII);
    }

    /** 读出并解码全部下行帧（跳过优雅关闭写的空缓冲）。 */
    static List<Message> drain(EmbeddedChannel ch) {
        List<Message> out = new ArrayList<>();
        for (Object o = ch.readOutbound(); o != null; o = ch.readOutbound()) {
            ByteBuf buf = (ByteBuf) o;
            try {
                if (buf.isReadable()) {
                    out.add(decodeFrame(buf));
                }
            } finally {
                buf.release();
            }
        }
        return out;
    }

    /** 下行帧的简短标签：verify-ok:battle / verify-fail:错误串 / push:号 / reply:号 / error:号:tip。 */
    static String label(Message message) {
        if (message instanceof BattleTokenVerifyResponse r) {
            return r.getSuccess() ? "verify-ok:" + r.getBattleId() : "verify-fail:" + r.getError();
        }
        MessageContent c = (MessageContent) message;
        if (c.hasErrorMessage()) {
            return "error:" + c.getMessageId() + ":" + c.getErrorMessage().getId();
        }
        return (c.getId() == 0 ? "push:" : "reply:") + c.getMessageId();
    }

    static List<String> labels(List<Message> messages) {
        return messages.stream().map(EdgeTestKit::label).toList();
    }

    /**
     * 装在 pipeline 最靠传输层的一端，按顺序记录线上发生的事：每个下行帧、优雅关闭写的空缓冲（{@code fin-marker}）、关闭（{@code close}）。
     * {@link #stallFinMarker} 为 true 时扣住空缓冲的写（promise 永不完成），模拟对端不读：FIN 永远发不出去，只能等强关兜底。
     */
    static final class WireRecorder extends ChannelOutboundHandlerAdapter {

        final List<String> events = new ArrayList<>();
        final List<ChannelPromise> stalled = new ArrayList<>();
        boolean stallFinMarker;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (msg instanceof ByteBuf buf && !buf.isReadable()) {
                events.add("fin-marker");
                if (stallFinMarker) {
                    stalled.add(promise);
                    return;
                }
            } else if (msg instanceof ByteBuf buf) {
                events.add(label(decodeFrame(buf)));
            }
            ctx.write(msg, promise);
        }

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            events.add("close");
            ctx.close(promise);
        }
    }
}
