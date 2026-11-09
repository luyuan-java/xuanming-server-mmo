package com.game.robot.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.game.net.client.ClientFrames;
import com.game.proto.ClientHeartbeatRequest;
import com.game.proto.ClientHeartbeatResponse;
import com.game.proto.ClientTokenVerifyResponse;
import com.game.proto.MessageContent;
import com.google.protobuf.Message;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 按正式线上帧验证心跳节拍与收包隔离，Netty 虚拟时间不需要睡眠。 */
class ClientHeartbeatTest {
    private final Inbox inbox = new Inbox();
    private final ConnectionHandler handler = new ConnectionHandler(inbox);
    private final EmbeddedChannel channel = new EmbeddedChannel();

    ClientHeartbeatTest() { GameConnection.configure(channel.pipeline(), handler); channel.freezeTime(); }
    @AfterEach void close() { channel.finishAndReleaseAll(); }
    private void receive(Message message) { channel.writeInbound(ClientFrames.encode(ByteBufAllocator.DEFAULT, message)); }
    private void verify(boolean success, int interval) {
        handler.expectHandshake();
        receive(ClientTokenVerifyResponse.newBuilder().setSuccess(success).setHeartbeatIntervalMs(interval)
                .setHeartbeatTimeoutMs(30_000).build());
    }
    private void advance(long millis) { channel.advanceTimeBy(millis, TimeUnit.MILLISECONDS); channel.runScheduledPendingTasks(); }
    private ClientHeartbeatRequest next() throws Exception {
        ByteBuf frame = channel.readOutbound();
        if (frame == null) return null;
        try {
            frame.skipBytes(4);
            return (ClientHeartbeatRequest) ClientFrames.decodeBody(frame,
                    Map.of("ClientHeartbeatRequest", ClientHeartbeatRequest.getDefaultInstance()));
        } finally { frame.release(); }
    }

    @Test void 验签成功立即首发随后每个通告间隔一条() throws Exception {
        verify(true, 10_000);
        assertThat(next().getSeq()).isEqualTo(1);
        advance(9_999);
        assertThat(next()).isNull();
        advance(1);
        assertThat(next().getSeq()).isEqualTo(2);
        advance(10_000);
        assertThat(next().getSeq()).isEqualTo(3);
    }

    @Test void 未通告不发() throws Exception {
        verify(true, 0);
        advance(60_000);
        assertThat(next()).isNull();
    }

    @Test void 新连接验签前不发() throws Exception {
        advance(60_000);
        assertThat(next()).isNull();
        verify(true, 10_000);
        assertThat(next().getSeq()).isEqualTo(1);
    }

    @Test void 验签失败即使带参数也不发() throws Exception {
        verify(false, 10_000);
        advance(60_000);
        assertThat(next()).isNull();
    }

    @Test void 心跳回包被认领不进业务收件箱() throws Exception {
        verify(true, 10_000);
        next();
        receive(ClientHeartbeatResponse.newBuilder().setSeq(1).build());
        receive(MessageContent.newBuilder().setMessageId(79).build());
        assertThat(inbox.size()).isEqualTo(1);
        assertThat(inbox.snapshot(0).getFirst().messageId()).isEqualTo(79);
        assertThat(channel.isOpen()).isTrue();
    }

    @Test void 断开撤销定时任务() throws Exception {
        verify(true, 10_000);
        next();
        channel.close();
        advance(60_000);
        assertThat(next()).isNull();
        assertThat(channel.runScheduledPendingTasks()).isEqualTo(-1);
    }

    @Test void 线程迟到不突发补齐过期节拍() throws Exception {
        verify(true, 10_000);
        next();
        advance(60_000);
        assertThat(next().getSeq()).isEqualTo(2);
        assertThat(next()).isNull();
    }

    @Test void 握手前的心跳回包不能冒充握手成功() {
        handler.expectHandshake();
        receive(ClientHeartbeatResponse.getDefaultInstance());
        assertThat(handler.handshake().isCompletedExceptionally()).isTrue();
        assertThat(channel.isOpen()).isFalse();
    }
}
