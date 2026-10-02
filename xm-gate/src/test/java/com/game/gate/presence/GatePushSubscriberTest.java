package com.game.gate.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.discovery.proto.GatePush;
import com.game.discovery.proto.PushTarget;
import com.game.gate.metrics.GateMetrics;
import com.game.gate.metrics.GateMetrics.PushResult;
import com.game.gate.session.ClientDispatcher;
import com.game.gate.session.ClientSession;
import com.game.gate.session.SessionIdAllocator;
import com.game.gate.session.SessionRegistry;
import com.game.proto.MessageContent;
import com.google.protobuf.ByteString;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.redisson.api.RTopic;

class GatePushSubscriberTest {

    private static final String INSTANCE = "gate-uuid";

    private final SessionRegistry registry = new SessionRegistry(new SessionIdAllocator(3));
    private final ClientDispatcher dispatcher = mock(ClientDispatcher.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final GatePushSubscriber subscriber = new GatePushSubscriber(mock(RTopic.class), INSTANCE, registry, dispatcher,
            new GateMetrics(meters));
    private final EmbeddedChannel channel = new EmbeddedChannel();
    private final ClientSession session = registry.open(channel, "127.0.0.1");

    private static final MessageContent CONTENT =
            MessageContent.newBuilder().setMessageId(235).setSerializedMessage(ByteString.copyFromUtf8("evt")).build();

    private double pushes(String kind, String result) {
        return meters.counter("xm.gate.pushes", "kind", kind, "result", result).count();
    }

    private GatePush.Builder push(String instance, int sessionId, long playerId) {
        return GatePush.newBuilder().setGateInstanceId(instance)
                .addTargets(PushTarget.newBuilder().setSessionId(sessionId).setPlayerId(playerId));
    }

    @Test
    void 消息在会话线程上交给分发器_计结局() {
        when(dispatcher.deliverPush(eq(session), eq(42L), any())).thenReturn(PushResult.DELIVERED);

        subscriber.accept(push(INSTANCE, session.sessionId(), 42).setMessageContent(CONTENT.toByteString()).build().toByteArray());

        verify(dispatcher, never()).deliverPush(any(), anyLong(), any());
        channel.runPendingTasks();
        verify(dispatcher).deliverPush(session, 42L, CONTENT);
        assertThat(pushes("message", "delivered")).isEqualTo(1);
    }

    @Test
    void 踢下线交给分发器() {
        when(dispatcher.kickByServer(session, 42L, 2017)).thenReturn(PushResult.DELIVERED);

        subscriber.accept(push(INSTANCE, session.sessionId(), 42).setKickTipId(2017).build().toByteArray());
        channel.runPendingTasks();

        verify(dispatcher).kickByServer(session, 42L, 2017);
        assertThat(pushes("kick", "delivered")).isEqualTo(1);
    }

    @Test
    void 别的gate实例的推送整条丢弃() {
        subscriber.accept(push("other-uuid", session.sessionId(), 42).setMessageContent(CONTENT.toByteString()).build().toByteArray());
        channel.runPendingTasks();

        verify(dispatcher, never()).deliverPush(any(), anyLong(), any());
        assertThat(pushes("message", "stale_instance")).isEqualTo(1);
    }

    @Test
    void 会话号不存在_计no_session() {
        subscriber.accept(push(INSTANCE, session.sessionId() + 1, 42).setMessageContent(CONTENT.toByteString()).build().toByteArray());
        assertThat(pushes("message", "no_session")).isEqualTo(1);
    }

    @Test
    void 格式不对的丢弃() {
        subscriber.accept(new byte[] {(byte) 0xff, (byte) 0xff, (byte) 0xff});
        subscriber.accept(push(INSTANCE, session.sessionId(), 42).build().toByteArray());
        subscriber.accept(push(INSTANCE, session.sessionId(), 42)
                .setMessageContent(ByteString.copyFrom(new byte[] {(byte) 0xff, 0x01})).build().toByteArray());
        channel.runPendingTasks();

        verify(dispatcher, never()).deliverPush(any(), anyLong(), any());
        verify(dispatcher, never()).kickByServer(any(), anyLong(), anyInt());
        assertThat(pushes("message", "invalid")).isEqualTo(3);
    }
}
