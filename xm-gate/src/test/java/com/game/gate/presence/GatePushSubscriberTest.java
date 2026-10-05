package com.game.gate.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.game.discovery.proto.GatePush;
import com.game.discovery.proto.MessageBatch;
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
import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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

    // ---------------------------------------------------------------- 按序的一批消息（MessageBatch，battle-node-spec §7.7 / §13.7）

    private static final MessageContent ASSIGNED =
            MessageContent.newBuilder().setMessageId(177).setSerializedMessage(ByteString.copyFromUtf8("assigned")).build();
    private static final MessageContent START =
            MessageContent.newBuilder().setMessageId(143).setSerializedMessage(ByteString.copyFromUtf8("start")).build();

    private static MessageBatch batch(ByteString... contents) {
        return MessageBatch.newBuilder().addAllMessageContents(List.of(contents)).build();
    }

    /** 会话所属线程的任务由测试手动执行（数一数一个推送投递了几个会话任务）。 */
    private ClientSession sessionOnRecordingLoop(List<Runnable> tasks) {
        Channel recording = mock(Channel.class);
        EventLoop loop = mock(EventLoop.class);
        when(recording.eventLoop()).thenReturn(loop);
        doAnswer(inv -> {
            tasks.add(inv.getArgument(0));
            return null;
        }).when(loop).execute(any(Runnable.class));
        return registry.open(recording, "127.0.0.1");
    }

    @Test
    void 一批消息对每个目标只投递一个会话任务_任务里按批内顺序逐条交给分发器() {
        List<Runnable> tasks = new ArrayList<>();
        ClientSession recorded = sessionOnRecordingLoop(tasks);
        when(dispatcher.deliverPush(eq(recorded), eq(42L), any())).thenReturn(PushResult.DELIVERED);

        subscriber.accept(push(INSTANCE, recorded.sessionId(), 42)
                .setMessageBatch(batch(ASSIGNED.toByteString(), START.toByteString())).build().toByteArray());

        assertThat(tasks).as("一个目标一个会话任务（两条不拆成两个任务，中间插不进别的事件）").hasSize(1);
        verify(dispatcher, never()).deliverPush(any(), anyLong(), any());
        tasks.get(0).run();
        InOrder order = inOrder(dispatcher);
        order.verify(dispatcher).deliverPush(recorded, 42L, ASSIGNED);
        order.verify(dispatcher).deliverPush(recorded, 42L, START);
        order.verifyNoMoreInteractions();
        assertThat(pushes("message", "delivered")).as("每个目标只计一次").isEqualTo(1);
    }

    @Test
    void 一批消息发给多个目标_各自一个任务_各自按序() {
        List<Runnable> tasks = new ArrayList<>();
        ClientSession recorded = sessionOnRecordingLoop(tasks);
        when(dispatcher.deliverPush(any(), anyLong(), any())).thenReturn(PushResult.DELIVERED);

        subscriber.accept(push(INSTANCE, recorded.sessionId(), 42)
                .addTargets(PushTarget.newBuilder().setSessionId(session.sessionId()).setPlayerId(43))
                .setMessageBatch(batch(ASSIGNED.toByteString(), START.toByteString())).build().toByteArray());
        tasks.forEach(Runnable::run);
        channel.runPendingTasks();

        InOrder first = inOrder(dispatcher);
        first.verify(dispatcher).deliverPush(recorded, 42L, ASSIGNED);
        first.verify(dispatcher).deliverPush(recorded, 42L, START);
        InOrder second = inOrder(dispatcher);
        second.verify(dispatcher).deliverPush(session, 43L, ASSIGNED);
        second.verify(dispatcher).deliverPush(session, 43L, START);
        assertThat(tasks).hasSize(1);
        assertThat(pushes("message", "delivered")).isEqualTo(2);
    }

    @Test
    void 一批消息里某条没过玩家栅栏_停在这条_后面的不再发_计一次not_bound() {
        when(dispatcher.deliverPush(eq(session), eq(42L), eq(ASSIGNED))).thenReturn(PushResult.NOT_BOUND);
        when(dispatcher.deliverPush(eq(session), eq(42L), eq(START))).thenReturn(PushResult.DELIVERED);

        subscriber.accept(push(INSTANCE, session.sessionId(), 42)
                .setMessageBatch(batch(ASSIGNED.toByteString(), START.toByteString())).build().toByteArray());
        channel.runPendingTasks();

        verify(dispatcher).deliverPush(session, 42L, ASSIGNED);
        verify(dispatcher, never()).deliverPush(session, 42L, START);
        assertThat(pushes("message", "not_bound")).isEqualTo(1);
        assertThat(pushes("message", "delivered")).isZero();
    }

    @Test
    void 一批消息里前面送出后面没过栅栏_计这条的结局() {
        when(dispatcher.deliverPush(eq(session), eq(42L), eq(ASSIGNED))).thenReturn(PushResult.DELIVERED);
        when(dispatcher.deliverPush(eq(session), eq(42L), eq(START))).thenReturn(PushResult.NOT_BOUND);

        subscriber.accept(push(INSTANCE, session.sessionId(), 42)
                .setMessageBatch(batch(ASSIGNED.toByteString(), START.toByteString())).build().toByteArray());
        channel.runPendingTasks();

        verify(dispatcher).deliverPush(session, 42L, ASSIGNED);
        verify(dispatcher).deliverPush(session, 42L, START);
        assertThat(pushes("message", "not_bound")).isEqualTo(1);
        assertThat(pushes("message", "delivered")).isZero();
    }

    @Test
    void 一批消息里任一条损坏或一条都没有_整条丢弃_按目标数计invalid() {
        subscriber.accept(push(INSTANCE, session.sessionId(), 42)
                .addTargets(PushTarget.newBuilder().setSessionId(session.sessionId() + 1).setPlayerId(43))
                .setMessageBatch(batch(ASSIGNED.toByteString(), ByteString.copyFrom(new byte[] {(byte) 0xff, 0x01})))
                .build().toByteArray());
        subscriber.accept(push(INSTANCE, session.sessionId(), 42).setMessageBatch(MessageBatch.getDefaultInstance())
                .build().toByteArray());
        channel.runPendingTasks();

        verify(dispatcher, never()).deliverPush(any(), anyLong(), any());
        assertThat(pushes("message", "invalid")).as("两个目标 + 一个目标").isEqualTo(3);
    }

    @Test
    void 一批消息指向别的gate实例_整条丢弃() {
        subscriber.accept(push("other-uuid", session.sessionId(), 42)
                .setMessageBatch(batch(ASSIGNED.toByteString(), START.toByteString())).build().toByteArray());
        channel.runPendingTasks();

        verify(dispatcher, never()).deliverPush(any(), anyLong(), any());
        assertThat(pushes("message", "stale_instance")).isEqualTo(1);
    }

    @Test
    void 一批消息的会话号不存在_计no_session() {
        subscriber.accept(push(INSTANCE, session.sessionId() + 1, 42)
                .setMessageBatch(batch(ASSIGNED.toByteString())).build().toByteArray());
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
